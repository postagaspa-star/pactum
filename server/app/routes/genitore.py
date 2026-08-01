"""Endpoint del genitore: la finestra (non vetrata) e le notifiche a polling.
Il silenzio si calcola in lettura: nessun job in background nella v1."""

import json
import sqlite3
from collections import defaultdict
from datetime import datetime, timedelta

from fastapi import APIRouter, Depends

from .. import clock, siti
from ..auth import richiede_genitore
from ..config import SOGLIA_SILENZIO_MINUTI, fuso_patto
from ..db import get_conn, stato_bonus
from .regole import _riga_regola

router = APIRouter(dependencies=[Depends(richiede_genitore)])

# La finestra e' lunga 8 giorni (oggi + i 7 precedenti): la misura sta in
# siti.GIORNI_FINESTRA, unica per tutte le sezioni.
RECENTI = 20
STORICO_MASSIMO = 50


def _evento_out(riga: sqlite3.Row) -> dict:
    return {
        "id": riga["id"],
        "tipo": riga["tipo"],
        "dettagli": json.loads(riga["dettagli"]),
        "ts_device": riga["ts_device"],
        "ts_server": riga["ts_server"],
    }


def _stato_silenzio(conn: sqlite3.Connection, ora: datetime) -> dict:
    riga = conn.execute("SELECT MAX(ts_server) AS ultimo FROM battiti").fetchone()
    ultimo = riga["ultimo"]
    if ultimo is None:
        return {"ultimo_battito": None, "silente": True}
    trascorso = ora - datetime.fromisoformat(ultimo)
    return {
        "ultimo_battito": ultimo,
        "silente": trascorso > timedelta(minutes=SOGLIA_SILENZIO_MINUTI),
    }


def _data_locale(ts_server: str, tz) -> str:
    """Il giorno LOCALE (fuso del patto) di un ts_server UTC ISO."""
    return datetime.fromisoformat(ts_server).astimezone(tz).date().isoformat()


def _semaforo_vita(stato_dich: str | None) -> str:
    """(v2.1) Colore di una regola vita_reale in un giorno, dalla dichiarazione:
    verde = confermata (anche per conto), rosso = fallimento dichiarato o successo
    ribaltato, grigio = nessuna dichiarazione o verdetto ancora in attesa. Il rosso
    di un fallimento dichiarato fotografa il fatto, non punisce l'onesta'."""
    if stato_dich in ("confermata", "confermata_per_conto"):
        return "verde"
    if stato_dich in ("registrata", "ribaltata"):
        return "rosso"
    return "grigio"  # in_attesa o nessuna dichiarazione


def _minuti_validi(mappa) -> dict:
    """Tiene solo le voci {chiave: minuti} con minuti numerici non negativi:
    una fotografia sporca non deve far crollare la finestra."""
    if not isinstance(mappa, dict):
        return {}
    return {
        chiave: minuti
        for chiave, minuti in mappa.items()
        if isinstance(minuti, (int, float)) and not isinstance(minuti, bool) and minuti >= 0
    }


def _uso_recente(conn: sqlite3.Connection, giorni: list, limiti: dict) -> list:
    """(v2.2) I tempi d'uso di TUTTE le app negli 8 giorni della finestra, dalla
    fotografia uso_giornaliero VIGENTE di ciascun giorno. Un giorno senza
    fotografia ha totale_minuti null e liste vuote — MAI uno zero finto:
    "nessun dato ricevuto" e' un'informazione (contratto-api.md).
    `limiti` = {app_o_categoria: {"limite", "regola_id"}} delle regole
    limite_tempo ATTIVE: il limite compare SOLO dove la chiave combacia
    esattamente. E' il limite BASE (minuti_al_giorno): gli eventuali bonus del
    giorno sono gia' visibili in bonus_giornalieri."""
    date_iso = [g.isoformat() for g in giorni]
    segnaposto = ",".join("?" * len(date_iso))
    vigenti = {
        r["giorno"]: r
        for r in conn.execute(
            f"SELECT * FROM uso_giornaliero WHERE giorno IN ({segnaposto})", date_iso
        ).fetchall()
    }

    voci = []
    for data in date_iso:
        riga = vigenti.get(data)
        if riga is None:
            voci.append(
                {"giorno": data, "totale_minuti": None, "aggiornato_ts": None,
                 "app": [], "categorie": []}
            )
            continue
        dettagli = json.loads(riga["dettagli"])
        # nomi e uso_categorie sono nati in v2.2: le fotografie vecchie non li
        # hanno (tolleranza evolutiva) -> fallback sul pacchetto e lista vuota.
        nomi = dettagli.get("nomi")
        if not isinstance(nomi, dict):
            nomi = {}
        app = []
        for chiave, minuti in sorted(
            _minuti_validi(dettagli.get("uso_minuti")).items(),
            key=lambda voce: (-voce[1], voce[0]),  # minuti decrescenti, poi chiave
        ):
            voce = {"chiave": chiave, "nome": nomi.get(chiave) or chiave, "minuti": minuti}
            voce.update(limiti.get(chiave, {}))
            app.append(voce)
        categorie = []
        for chiave, minuti in sorted(
            _minuti_validi(dettagli.get("uso_categorie")).items(),
            key=lambda voce: (-voce[1], voce[0]),
        ):
            voce = {"chiave": chiave, "minuti": minuti}
            voce.update(limiti.get(chiave, {}))
            categorie.append(voce)
        voci.append(
            {
                "giorno": data,
                "totale_minuti": riga["totale_minuti"],
                "aggiornato_ts": riga["ts_server"],
                "app": app,
                "categorie": categorie,
            }
        )
    return voci


def _medie(conn: sqlite3.Connection, oggi) -> dict:
    """(S1) Media dei minuti d'uso sui SOLI giorni con una fotografia, su due
    finestre: settimana (ultimi 7 giorni locali) e mese (ultimi 30). Ogni voce e'
    {"minuti": intero, "giorni": quanti giorni avevano dati}, oppure None se nella
    finestra non c'e' nessuna fotografia — MAI uno zero finto. `giorno` in
    uso_giornaliero e' gia' il giorno LOCALE del patto (contratto-api.md), quindi
    il confronto stringa e' corretto nel fuso senza conversioni; i giorni assenti
    non sono righe, cosi' l'AVG non li conta (la regola "solo giorni con dati" e'
    rispettata per costruzione). Un giorno con totale_minuti=0 e' una fotografia
    reale (uso zero) e va contato: l'AVG lo include."""
    def media(giorni_finestra: int) -> dict | None:
        inizio = (oggi - timedelta(days=giorni_finestra - 1)).isoformat()
        r = conn.execute(
            "SELECT AVG(totale_minuti) AS m, COUNT(*) AS n FROM uso_giornaliero"
            " WHERE giorno >= ? AND giorno <= ?",
            (inizio, oggi.isoformat()),
        ).fetchone()
        n = r["n"]
        if not n:
            return None
        return {"minuti": round(r["m"]), "giorni": n}

    return {"settimana": media(7), "mese": media(30)}


def _nomi_recenti(conn: sqlite3.Connection) -> dict:
    """(S2) L'ultima etichetta leggibile vista per ciascun pacchetto nelle
    fotografie uso_giornaliero (la piu' recente vince): serve a mostrare al
    genitore "TikTok" invece di com.zhiliaoapp.musically sulle regole
    limite_tempo. Le fotografie sono al piu' una per giorno (PRIMARY KEY giorno):
    l'insieme e' piccolo. Fotografie senza `nomi` (pre-v2.2) si saltano."""
    nomi: dict = {}
    for riga in conn.execute(
        "SELECT dettagli FROM uso_giornaliero ORDER BY ts_server DESC, giorno DESC"
    ).fetchall():
        mappa = json.loads(riga["dettagli"]).get("nomi")
        if not isinstance(mappa, dict):
            continue
        for chiave, nome in mappa.items():
            if chiave not in nomi and isinstance(nome, str) and nome:
                nomi[chiave] = nome
    return nomi


@router.get("/finestra")
def finestra(conn: sqlite3.Connection = Depends(get_conn)):
    ora = clock.now()
    tz = fuso_patto()
    # I giorni della finestra sono giorni LOCALI del patto (contratto-api.md):
    # in UTC il confine cadrebbe alle 02:00 locali italiane.
    oggi = ora.astimezone(tz).date()
    # Una sola definizione degli 8 giorni (siti.giorni_finestra) per semaforo,
    # bonus_giornalieri, uso_recente e siti_recenti: cosi' le sezioni della
    # finestra non possono raccontare finestre temporali diverse.
    giorni = siti.giorni_finestra(ora)

    eventi = conn.execute(
        "SELECT * FROM eventi WHERE tipo IN ('sforamento', 'manomissione')"
        " ORDER BY ts_server DESC, id DESC"
    ).fetchall()

    sforamenti_per_regola = defaultdict(set)  # regola_id -> {data ISO locale}
    for evento in eventi:
        dettagli = json.loads(evento["dettagli"])
        regola_id = dettagli.get("regola_id")
        if regola_id is None:
            continue
        if evento["tipo"] == "sforamento":
            sforamenti_per_regola[regola_id].add(_data_locale(evento["ts_server"], tz))

    # Dichiarazioni per le regole vita_reale: (regola_id, giorno locale) -> stato.
    # Max una per regola per giorno, quindi la mappa e' univoca.
    dich_per_regola = defaultdict(dict)  # regola_id -> {giorno ISO: stato dichiarazione}
    for d in conn.execute("SELECT regola_id, giorno, stato FROM dichiarazioni").fetchall():
        dich_per_regola[d["regola_id"]][d["giorno"]] = d["stato"]

    # Semaforo a tre colori: verde/rosso/grigio. Il giallo non esiste piu':
    # il bonus autoritativo vive nella tabella bonus (senza regola_id) e viene
    # riassunto per giorno in bonus_giornalieri, non appeso a una regola.
    # Per limite_tempo/fascia_oraria il rosso viene dagli sforamenti; per le
    # vita_reale (v2.1) dalle dichiarazioni e dai verdetti.
    regole = []
    limiti = {}  # app_o_categoria -> {"limite", "regola_id"} delle limite_tempo ATTIVE
    for riga in conn.execute("SELECT * FROM regole ORDER BY id").fetchall():
        if riga["attiva"] and riga["tipo"] == "limite_tempo":
            parametri = json.loads(riga["parametri"])
            limiti.setdefault(
                parametri["app_o_categoria"],
                {"limite": parametri["minuti_al_giorno"], "regola_id": riga["id"]},
            )
        creata = _data_locale(riga["creata_ts"], tz)
        eliminata = None
        if not riga["attiva"]:
            # Soft-delete: i giorni STRETTAMENTE successivi all'eliminazione
            # sono fuori dalla vita della regola -> grigio, non verde.
            eliminata = _data_locale(riga["ultima_modifica_ts"], tz)
        semaforo = []
        for giorno in giorni:
            data = giorno.isoformat()
            if data < creata or (eliminata is not None and data > eliminata):
                stato = "grigio"
            elif riga["tipo"] == "vita_reale":
                stato = _semaforo_vita(dich_per_regola[riga["id"]].get(data))
            elif data in sforamenti_per_regola[riga["id"]]:
                stato = "rosso"
            else:
                stato = "verde"
            semaforo.append({"data": data, "stato": stato})
        regole.append({**_riga_regola(riga), "semaforo": semaforo})

    # Riepilogo bonus per giorno (globale, stessa finestra di 8 giorni):
    # dalla tabella bonus autoritativa, coi giorni nel fuso del patto.
    minuti_per_giorno = defaultdict(int)
    for riga in conn.execute("SELECT minuti, ts_server FROM bonus").fetchall():
        minuti_per_giorno[_data_locale(riga["ts_server"], tz)] += riga["minuti"]
    bonus_giornalieri = [
        {"giorno": g.isoformat(), "minuti": minuti_per_giorno[g.isoformat()]} for g in giorni
    ]

    storico = [
        {
            "id": r["id"],
            "regola_id": r["regola_id"],
            "azione": r["azione"],
            "direzione": r["direzione"],
            "prima": json.loads(r["parametri_prima"]) if r["parametri_prima"] else None,
            "dopo": json.loads(r["parametri_dopo"]) if r["parametri_dopo"] else None,
            "concordata": bool(r["concordata"]),
            "ts_server": r["ts_server"],
        }
        for r in conn.execute(
            "SELECT * FROM storico_modifiche ORDER BY id DESC LIMIT ?", (STORICO_MASSIMO,)
        ).fetchall()
    ]

    return {
        "regole": regole,
        "sforamenti_recenti": [
            _evento_out(e) for e in eventi if e["tipo"] == "sforamento"
        ][:RECENTI],
        "manomissioni_recenti": [
            _evento_out(e) for e in eventi if e["tipo"] == "manomissione"
        ][:RECENTI],
        "storico_modifiche": storico,
        "bonus": stato_bonus(conn, ora),
        "bonus_giornalieri": bonus_giornalieri,
        "stato_silenzio": _stato_silenzio(conn, ora),
        "uso_recente": _uso_recente(conn, giorni, limiti),
        # (v2.3) I siti visitati: il genitore vede QUALI siti, mai cosa ci fa
        # dentro. Stessa funzione di GET /api/patto — il figlio vede la stessa
        # identica lista (tavola rotonda). Non entra nel semaforo: non e' un'infrazione.
        "siti_recenti": siti.siti_recenti(conn, ora),
        "medie": _medie(conn, oggi),
    }
