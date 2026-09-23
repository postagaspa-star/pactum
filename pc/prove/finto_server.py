"""
Finto server Pactum v3, solo libreria standard: serve a provare il programma
per il computer finché il server vero della v3 non è pronto.

Implementa le chiamate che il computer usa (docs/contratto-api.md, v3):
abbina, battito, eventi, patto, notifiche, bonus, regole, proposte,
dichiarazioni, versione. Gli errori escono alla FastAPI ({"detail": {...}}),
come dal server vero. In più, per le prove, /prova/*: nuovo codice di
abbinamento, notifica finta, stato completo.

Nel suo diario scrive solo metodo, percorso e stato: mai i corpi.

    python finto_server.py --porta 8765 --dati CARTELLA [--codice 123456] [--versione-computer 8]
"""

import argparse
import datetime as dt
import json
import os
import secrets
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit, parse_qs
from zoneinfo import ZoneInfo

FUSO = "Europe/Rome"
TETTO_GIORNO = 30
TETTO_SETTIMANA = 90
CATEGORIE = {"categoria:social", "categoria:giochi", "categoria:video", "categoria:musica", "categoria:altro"}
GIORNI = ["lun", "mar", "mer", "gio", "ven", "sab", "dom"]

blocco = threading.RLock()


def adesso():
    return dt.datetime.now(dt.timezone.utc)


def iso(t):
    return t.replace(microsecond=0).isoformat()


def oggi_patto(t=None):
    return (t or adesso()).astimezone(ZoneInfo(FUSO)).date()


class Stato:
    def __init__(self, cartella, codice):
        self.cartella = cartella
        self.figlio = {"id": 1, "nome": "Andrea"}
        self.dispositivi = {
            1: {"id": 1, "nome": "Telefono", "tipo": "telefono", "abbinato": True, "revocato": False, "token": secrets.token_urlsafe(32)},
            2: {"id": 2, "nome": "Computer di camera", "tipo": "computer", "abbinato": False, "revocato": False, "token": None},
        }
        self.codici = {codice: {"dispositivo_id": 2, "scade": time.time() + 15 * 60}} if codice else {}
        self.tentativi_falliti = []
        self.bloccato_fino = 0.0
        self.eventi = {}          # id -> evento (con dispositivo_id e ts_server)
        self.ordine_eventi = []
        self.battiti = []
        self.fotografie_uso = {}  # (dispositivo, giorno) -> dettagli vigenti
        self.fotografie_siti = {}
        self.dns_cifrato = set()  # (dispositivo, giorno)
        self.regole = {}
        self.prossima_regola = 1
        self.bonus = []
        self.notifiche = []
        self.prossima_notifica = 1
        self.versione_computer = 8
        # Una regola di vita reale del figlio, come nel patto vero (dispositivo null).
        self.crea_regola("vita_reale", {"descrizione": "Un'ora di camminata", "arbitro_nome": "Nonna", "frequenza": "giornaliera"}, None)

    # ---- regole ----
    def crea_regola(self, tipo, parametri, dispositivo_id):
        rid = self.prossima_regola
        self.prossima_regola += 1
        t = adesso()
        self.regole[rid] = {
            "id": rid, "tipo": tipo, "parametri": parametri, "attiva": True,
            "creata_ts": iso(t), "ultima_modifica_ts": iso(t),
            "allentabile_dal": iso(t + dt.timedelta(days=4)),
            "figlio_id": 1, "dispositivo_id": dispositivo_id,
        }
        return self.regole[rid]

    def vista_regola(self, r):
        v = {k: r[k] for k in ("id", "tipo", "parametri", "attiva", "creata_ts", "ultima_modifica_ts", "allentabile_dal", "figlio_id", "dispositivo_id")}
        d = self.dispositivi.get(r["dispositivo_id"]) if r["dispositivo_id"] else None
        v["dispositivo"] = {"id": d["id"], "nome": d["nome"], "tipo": d["tipo"]} if d else None
        v["semaforo"] = self.semaforo(r)
        return v

    def giorni_finestra(self):
        o = oggi_patto()
        return [o - dt.timedelta(days=i) for i in range(7, -1, -1)]

    def semaforo(self, r):
        voci = []
        for g in self.giorni_finestra():
            gs = g.isoformat()
            if r["tipo"] == "vita_reale":
                stato = "grigio"
            else:
                rosso = any(
                    e["tipo"] == "sforamento" and e["dettagli"].get("regola_id") == r["id"] and e["dettagli"].get("giorno") == gs
                    for e in self.eventi.values())
                if rosso:
                    stato = "rosso"
                elif (r["dispositivo_id"], gs) in self.fotografie_uso:
                    stato = "verde"
                else:
                    stato = "grigio"
            voci.append({"data": gs, "stato": stato})
        return voci

    def striscia(self, regole):
        giorni = self.giorni_finestra()
        risultato = []
        for i, g in enumerate(giorni):
            stati = [self.semaforo(r)[i]["stato"] for r in regole]
            stato = "rosso" if "rosso" in stati else ("verde" if "verde" in stati else "grigio")
            risultato.append({"data": g.isoformat(), "stato": stato})
        return risultato

    def bonus_di(self, dispositivo_id):
        o = oggi_patto()
        inizio_settimana = o - dt.timedelta(days=o.weekday())
        giorno = sum(b["minuti"] for b in self.bonus if b["dispositivo_id"] == dispositivo_id and b["giorno"] == o.isoformat())
        settimana = sum(b["minuti"] for b in self.bonus
                        if b["dispositivo_id"] == dispositivo_id and dt.date.fromisoformat(b["giorno"]) >= inizio_settimana)
        return {
            "giorno": {"usati": giorno, "tetto": TETTO_GIORNO, "residui": max(0, TETTO_GIORNO - giorno)},
            "settimana": {"usati": settimana, "tetto": TETTO_SETTIMANA, "residui": max(0, TETTO_SETTIMANA - settimana)},
        }

    def siti_recenti(self, dispositivo_id):
        voci = []
        for g in self.giorni_finestra():
            gs = g.isoformat()
            f = self.fotografie_siti.get((dispositivo_id, gs))
            if f is None:
                voci.append({"giorno": gs, "totale_domini": None, "dns_cifrato": False, "aggiornato_ts": None, "domini": []})
                continue
            minuti = f.get("minuti") or {}
            domini = [{"dominio": d, "visite": v, "minuti": minuti.get(d, 0)} for d, v in (f.get("domini") or {}).items()]
            domini.sort(key=lambda x: (-x["minuti"], x["dominio"]))
            voci.append({"giorno": gs, "totale_domini": f.get("totale_domini"), "dns_cifrato": (dispositivo_id, gs) in self.dns_cifrato,
                         "aggiornato_ts": f["_ts"], "domini": domini})
        return voci

    def patto(self, disp):
        did = disp["id"]
        regole = [r for r in self.regole.values() if r["attiva"] and (r["dispositivo_id"] == did or r["dispositivo_id"] is None)]
        tutte_del_figlio = [r for r in self.regole.values() if r["attiva"]]
        o = oggi_patto().isoformat()
        per_regola = {}
        for b in self.bonus:
            if b["dispositivo_id"] == did and b["giorno"] == o:
                per_regola[str(b["regola_id"])] = per_regola.get(str(b["regola_id"]), 0) + b["minuti"]
        striscia = self.striscia(tutte_del_figlio)
        manomissioni = [e for e in self.eventi.values() if e["tipo"] == "manomissione"]
        return {
            "regole": [self.vista_regola(r) for r in sorted(regole, key=lambda r: r["id"])],
            "bonus": self.bonus_di(did),
            "bonus_oggi_per_regola": per_regola,
            "proposte_pendenti": [],
            "dichiarazioni_in_attesa": [],
            "siti_recenti": self.siti_recenti(did),
            "striscia": striscia,
            "riepilogo": {"giorni_fuori_regola": sum(1 for s in striscia if s["stato"] == "rosso"), "interruzioni": len(manomissioni)},
            "fuso": FUSO,
            "figlio": self.figlio,
            "dispositivo": {"id": did, "nome": disp["nome"], "tipo": disp["tipo"]},
            "striscia_dispositivo": self.striscia([r for r in regole if r["dispositivo_id"] == did]),
            "dispositivi": [{"id": d["id"], "nome": d["nome"], "tipo": d["tipo"],
                             "striscia": self.striscia([r for r in tutte_del_figlio if r["dispositivo_id"] == d["id"]])}
                            for d in self.dispositivi.values()],
        }

    def notifica(self, tipo, messaggio, payload, dispositivo_id, destinatario="figlio"):
        n = {"id": self.prossima_notifica, "tipo": tipo, "messaggio": messaggio, "payload": payload,
             "ts_server": iso(adesso()), "figlio_id": 1, "dispositivo_id": dispositivo_id,
             "destinatario": destinatario, "letta": False}
        self.prossima_notifica += 1
        self.notifiche.append(n)
        return n

    def salva(self):
        if not self.cartella:
            return
        os.makedirs(self.cartella, exist_ok=True)
        dati = {
            "dispositivi": {k: {kk: vv for kk, vv in v.items() if kk != "token"} for k, v in self.dispositivi.items()},
            "battiti": self.battiti,
            "eventi": [self.eventi[i] for i in self.ordine_eventi],
            "fotografie_uso": {f"{k[0]}|{k[1]}": v for k, v in self.fotografie_uso.items()},
            "fotografie_siti": {f"{k[0]}|{k[1]}": v for k, v in self.fotografie_siti.items()},
            "regole": list(self.regole.values()),
            "bonus": self.bonus,
            "notifiche": self.notifiche,
        }
        tmp = os.path.join(self.cartella, "stato.json.tmp")
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(dati, f, ensure_ascii=False, indent=1)
        os.replace(tmp, os.path.join(self.cartella, "stato.json"))


class Errore(Exception):
    def __init__(self, stato, corpo):
        super().__init__(stato)
        self.stato = stato
        self.corpo = corpo


def errore(stato, codice, **altro):
    return Errore(stato, {"detail": dict({"errore": codice}, **altro)})


def chiave_valida_computer(chiave):
    if not isinstance(chiave, str) or chiave != chiave.strip().lower():
        return False
    if chiave in CATEGORIE:
        return True
    if chiave.startswith("exe:") and len(chiave) > 4:
        return True
    if chiave.startswith("sito:") and "." in chiave[5:]:
        return True
    return False


def valida_parametri(tipo, p, dispositivo):
    if not isinstance(p, dict):
        raise Errore(422, {"detail": [{"loc": ["body", "parametri"], "msg": "oggetto richiesto"}]})
    if tipo == "limite_tempo":
        m = p.get("minuti_al_giorno")
        if not isinstance(m, int) or m < 0 or m > 1440:
            raise Errore(422, {"detail": [{"loc": ["body", "parametri", "minuti_al_giorno"], "msg": "0-1440"}]})
        chiave = p.get("app_o_categoria")
        if dispositivo["tipo"] == "computer" and not chiave_valida_computer(chiave):
            raise Errore(422, {"detail": [{"loc": ["body", "parametri", "app_o_categoria"], "msg": "chiave non valida per un computer"}]})
    elif tipo == "fascia_oraria":
        for campo in ("dalle", "alle"):
            try:
                dt.datetime.strptime(p.get(campo, ""), "%H:%M")
            except (TypeError, ValueError):
                raise Errore(422, {"detail": [{"loc": ["body", "parametri", campo], "msg": "HH:MM"}]})
        g = p.get("giorni")
        if not isinstance(g, list) or not g or any(x not in GIORNI for x in g):
            raise Errore(422, {"detail": [{"loc": ["body", "parametri", "giorni"], "msg": "lun..dom"}]})
    elif tipo != "vita_reale":
        raise Errore(422, {"detail": [{"loc": ["body", "tipo"], "msg": "tipo sconosciuto"}]})


class Gestore(BaseHTTPRequestHandler):
    server_version = "FintoPactum/3"
    # Come uvicorn: connessioni tenute aperte (HTTP/1.1), chiuse dopo 5 secondi ferme.
    protocol_version = "HTTP/1.1"
    timeout = 5
    stato: Stato = None

    def log_message(self, formato, *args):
        pass

    def diario(self, codice):
        percorso = urlsplit(self.path).path
        print(f"{time.strftime('%H:%M:%S')} {self.command} {percorso} -> {codice}", flush=True)

    def rispondi(self, codice, corpo):
        dati = json.dumps(corpo, ensure_ascii=False).encode("utf-8")
        self.send_response(codice)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(dati)))
        self.end_headers()
        self.wfile.write(dati)
        self.diario(codice)

    def corpo(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n == 0:
            return {}
        try:
            return json.loads(self.rfile.read(n).decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            raise Errore(422, {"detail": [{"loc": ["body"], "msg": "JSON non valido"}]})

    def dispositivo(self):
        auth = self.headers.get("Authorization") or ""
        if not auth.startswith("Bearer "):
            raise Errore(401, {"detail": "token mancante"})
        token = auth[7:]
        for d in self.stato.dispositivi.values():
            if d["token"] and secrets.compare_digest(d["token"], token) and not d["revocato"]:
                return d
        raise Errore(401, {"detail": "token sconosciuto"})

    def do_GET(self):
        self.gestisci("GET")

    def do_POST(self):
        self.gestisci("POST")

    def do_PATCH(self):
        self.gestisci("PATCH")

    def do_DELETE(self):
        self.gestisci("DELETE")

    def gestisci(self, metodo):
        parti = urlsplit(self.path)
        percorso = parti.path.rstrip("/")
        query = parse_qs(parti.query)
        try:
            with blocco:
                codice, corpo = self.instrada(metodo, percorso, query)
                self.stato.salva()
            self.rispondi(codice, corpo)
        except Errore as e:
            self.rispondi(e.stato, e.corpo)
        except Exception as e:  # noqa: BLE001 - un finto server non deve cadere
            self.rispondi(500, {"detail": f"errore interno: {type(e).__name__}"})

    def instrada(self, metodo, percorso, query):
        s = self.stato
        pezzi = percorso.strip("/").split("/")

        # ---- senza autenticazione ----
        if metodo == "GET" and percorso == "/api/versione":
            return 200, {
                "figlio": {"versione_code": 7, "versione_nome": "0.7.0", "url": "/scarica/pactum-figlio.apk", "note": ""},
                "genitore": {"versione_code": 7, "versione_nome": "0.7.0", "url": "/scarica/pactum-genitore.apk", "note": ""},
                "computer": {"versione_code": s.versione_computer, "versione_nome": f"0.{s.versione_computer}.0",
                             "url": "/scarica/pactum-computer.zip", "note": "finto"},
            }
        if metodo == "POST" and percorso == "/api/abbina":
            return self.abbina(self.corpo())

        # ---- solo per le prove ----
        if percorso.startswith("/prova/"):
            return self.prova(metodo, pezzi[1] if len(pezzi) > 1 else "", self.corpo() if metodo == "POST" else {})

        disp = self.dispositivo()
        did = disp["id"]

        if metodo == "POST" and percorso == "/api/battito":
            b = self.corpo()
            s.battiti.append({"dispositivo_id": did, "ts_server": iso(adesso()), "corpo": b})
            return 200, {"ricevuto": True}

        if metodo == "POST" and percorso == "/api/eventi":
            return self.ricevi_eventi(self.corpo(), did)

        if metodo == "GET" and percorso == "/api/patto":
            return 200, s.patto(disp)

        if metodo == "GET" and percorso == "/api/notifiche":
            mie = [n for n in s.notifiche
                   if not n["letta"] and n["destinatario"] == "figlio" and n["dispositivo_id"] in (did, None)]
            return 200, {"notifiche": [{k: n[k] for k in ("id", "tipo", "messaggio", "payload", "ts_server", "figlio_id", "dispositivo_id")} for n in mie]}

        if metodo == "POST" and len(pezzi) == 4 and pezzi[:2] == ["api", "notifiche"] and pezzi[3] == "letta":
            for n in s.notifiche:
                if str(n["id"]) == pezzi[2]:
                    n["letta"] = True
                    return 200, {"id": n["id"], "letta": True}
            raise Errore(404, {"detail": "notifica non trovata"})

        if metodo == "POST" and percorso == "/api/bonus":
            return self.bonus(self.corpo(), did)

        if percorso == "/api/regole" and metodo == "GET":
            return 200, {"regole": [s.vista_regola(r) for r in sorted(s.regole.values(), key=lambda r: r["id"]) if r["attiva"]]}
        if percorso == "/api/regole" and metodo == "POST":
            c = self.corpo()
            tipo = c.get("tipo")
            destinazione = c.get("dispositivo_id") or did
            if destinazione not in s.dispositivi:
                raise Errore(403, {"detail": "dispositivo di un altro figlio"})
            valida_parametri(tipo, c.get("parametri"), s.dispositivi[destinazione])
            r = s.crea_regola(tipo, c["parametri"], None if tipo == "vita_reale" else destinazione)
            return 200, s.vista_regola(r)
        if len(pezzi) == 3 and pezzi[:2] == ["api", "regole"] and metodo in ("PATCH", "DELETE"):
            r = s.regole.get(int(pezzi[2])) if pezzi[2].isdigit() else None
            if r is None or not r["attiva"]:
                raise Errore(404, {"detail": "regola non trovata"})
            if metodo == "PATCH":
                nuovi = self.corpo().get("parametri")
                valida_parametri(r["tipo"], nuovi, s.dispositivi.get(r["dispositivo_id"]) or {"tipo": "telefono"})
                allenta = r["tipo"] == "limite_tempo" and nuovi.get("minuti_al_giorno", 0) > r["parametri"].get("minuti_al_giorno", 0)
                if allenta and adesso() < dt.datetime.fromisoformat(r["allentabile_dal"]):
                    secondi = int((dt.datetime.fromisoformat(r["allentabile_dal"]) - adesso()).total_seconds())
                    raise errore(409, "lock_attivo", secondi_residui=secondi, allentabile_dal=r["allentabile_dal"])
                r["parametri"] = nuovi
                r["ultima_modifica_ts"] = iso(adesso())
                r["allentabile_dal"] = iso(adesso() + dt.timedelta(days=4))
                return 200, s.vista_regola(r)
            attive = [x for x in s.regole.values() if x["attiva"]]
            if len(attive) <= 1:
                raise errore(409, "ultima_regola")
            if adesso() < dt.datetime.fromisoformat(r["allentabile_dal"]):
                secondi = int((dt.datetime.fromisoformat(r["allentabile_dal"]) - adesso()).total_seconds())
                raise errore(409, "lock_attivo", secondi_residui=secondi, allentabile_dal=r["allentabile_dal"])
            r["attiva"] = False
            return 200, {"eliminata": True, "id": r["id"]}

        if metodo == "GET" and percorso == "/api/proposte":
            return 200, {"proposte": []}
        if metodo == "GET" and percorso == "/api/dichiarazioni":
            return 200, {"dichiarazioni": []}

        raise Errore(404, {"detail": "Not Found"})

    def abbina(self, c):
        s = self.stato
        ora = time.time()
        if ora < s.bloccato_fino:
            raise errore(429, "troppi_tentativi", riprova_tra_secondi=int(s.bloccato_fino - ora))
        codice = str(c.get("codice") or "")
        voce = s.codici.get(codice)
        if voce is None or voce["scade"] < ora:
            s.codici.pop(codice, None)
            self._tentativo_fallito(ora)
            raise errore(409, "codice_non_valido")
        d = s.dispositivi[voce["dispositivo_id"]]
        # (v3.1) tipo: un codice di un telefono non abbina un computer (e viceversa).
        # Il codice NON viene consumato; conta come tentativo fallito.
        tipo_dato = c.get("tipo")
        if tipo_dato is not None and tipo_dato != d["tipo"]:
            self._tentativo_fallito(ora)
            raise errore(409, "tipo_non_corrispondente", tipo_atteso=d["tipo"])
        s.codici.pop(codice, None)
        d["token"] = secrets.token_urlsafe(32)
        d["abbinato"] = True
        d["versione_app"] = c.get("versione_app")
        return 200, {"token": d["token"], "dispositivo": {"id": d["id"], "nome": d["nome"], "tipo": d["tipo"]}, "figlio": s.figlio}

    def _tentativo_fallito(self, ora):
        s = self.stato
        s.tentativi_falliti = [t for t in s.tentativi_falliti if t > ora - 600] + [ora]
        if len(s.tentativi_falliti) >= 10:
            s.bloccato_fino = ora + 600

    def ricevi_eventi(self, c, did):
        s = self.stato
        eventi = c.get("eventi")
        if not isinstance(eventi, list):
            raise Errore(422, {"detail": [{"loc": ["body", "eventi"], "msg": "lista richiesta"}]})
        tipi = {"uso_giornaliero", "siti_giornalieri", "riavvio", "manomissione", "sforamento", "bonus_usato",
                "dichiarazione", "sospensione", "ripresa"}
        for e in eventi:
            if not isinstance(e, dict) or not e.get("id") or e.get("tipo") not in tipi or not isinstance(e.get("dettagli"), dict):
                raise Errore(422, {"detail": [{"loc": ["body", "eventi"], "msg": "evento non valido"}]})
        nuovi = duplicati = 0
        for e in eventi:
            if e["id"] in s.eventi:
                duplicati += 1
                continue
            nuovi += 1
            ts = iso(adesso())
            registrato = dict(e, dispositivo_id=did, ts_server=ts)
            s.eventi[e["id"]] = registrato
            s.ordine_eventi.append(e["id"])
            d = e["dettagli"]
            giorno = d.get("giorno")
            if e["tipo"] == "uso_giornaliero" and giorno:
                vigente = s.fotografie_uso.get((did, giorno))
                if vigente is None or (d.get("totale_minuti") or 0) >= (vigente.get("totale_minuti") or 0):
                    s.fotografie_uso[(did, giorno)] = dict(d, _ts=ts)
            elif e["tipo"] == "siti_giornalieri" and giorno:
                if d.get("dns_cifrato"):
                    s.dns_cifrato.add((did, giorno))
                vigente = s.fotografie_siti.get((did, giorno))
                nuova = (d.get("totale_domini") or 0, sum((d.get("domini") or {}).values()))
                if vigente is None or nuova >= (vigente.get("totale_domini") or 0, sum((vigente.get("domini") or {}).values())):
                    s.fotografie_siti[(did, giorno)] = dict(d, _ts=ts)
            elif e["tipo"] in ("sforamento", "manomissione"):
                s.notifica(e["tipo"], f"Evento {e['tipo']} registrato", {"evento_id": e["id"], "dettagli": d}, did,
                           destinatario="genitore")
        return 200, {"ricevuti": len(eventi), "nuovi": nuovi, "duplicati": duplicati}

    def bonus(self, c, did):
        s = self.stato
        minuti = c.get("minuti")
        if minuti not in (5, 15, 30):
            raise Errore(422, {"detail": [{"loc": ["body", "minuti"], "msg": "5, 15 o 30"}]})
        r = s.regole.get(c.get("regola_id"))
        if r is None or not r["attiva"] or r["tipo"] != "limite_tempo" or r["dispositivo_id"] != did:
            raise errore(409, "regola_non_valida")
        stato = s.bonus_di(did)
        residuo_giorno = stato["giorno"]["residui"]
        residuo_settimana = stato["settimana"]["residui"]
        if minuti > residuo_giorno or minuti > residuo_settimana:
            raise errore(409, "tetto_superato", residuo_giorno=residuo_giorno, residuo_settimana=residuo_settimana)
        s.bonus.append({"dispositivo_id": did, "regola_id": r["id"], "minuti": minuti, "motivo": c.get("motivo"),
                        "giorno": oggi_patto().isoformat(), "ts_server": iso(adesso())})
        return 200, {"minuti": minuti, "residuo_giorno": residuo_giorno - minuti, "residuo_settimana": residuo_settimana - minuti}

    def prova(self, metodo, nome, c):
        s = self.stato
        if metodo == "POST" and nome == "codice":
            codice = f"{secrets.randbelow(1_000_000):06d}"
            s.codici[codice] = {"dispositivo_id": 2, "scade": time.time() + 15 * 60}
            return 200, {"codice": codice}
        if metodo == "POST" and nome == "notifica":
            n = s.notifica(c.get("tipo", "segno"), c.get("messaggio", "Ho visto la settimana. Bene così."), c.get("payload", {}),
                           c.get("dispositivo_id"))
            return 200, n
        if metodo == "POST" and nome == "versione":
            s.versione_computer = int(c.get("versione_code", 9))
            return 200, {"versione_code": s.versione_computer}
        if metodo == "GET" and nome == "stato":
            return 200, {
                "battiti": len(s.battiti),
                "ultimo_battito": s.battiti[-1] if s.battiti else None,
                "eventi": [s.eventi[i] for i in s.ordine_eventi],
                "fotografie_uso": {f"{k[0]}|{k[1]}": v for k, v in s.fotografie_uso.items()},
                "fotografie_siti": {f"{k[0]}|{k[1]}": v for k, v in s.fotografie_siti.items()},
                "bonus": s.bonus,
                "regole": list(s.regole.values()),
                "dispositivi": {k: {kk: vv for kk, vv in v.items() if kk != "token"} for k, v in s.dispositivi.items()},
            }
        raise Errore(404, {"detail": "Not Found"})


def main():
    p = argparse.ArgumentParser(description="Finto server Pactum v3 per le prove del computer")
    p.add_argument("--porta", type=int, default=8765)
    p.add_argument("--dati", default=None, help="cartella dove scrivere stato.json")
    p.add_argument("--codice", default="123456", help="codice di abbinamento valido all'avvio")
    p.add_argument("--versione-computer", type=int, default=8)
    a = p.parse_args()
    Gestore.stato = Stato(a.dati, a.codice)
    Gestore.stato.versione_computer = a.versione_computer
    server = ThreadingHTTPServer(("127.0.0.1", a.porta), Gestore)
    print(f"finto server Pactum v3 su http://127.0.0.1:{a.porta} (codice {a.codice})", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
