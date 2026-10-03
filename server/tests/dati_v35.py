"""Un database come quello vero sul NAS prima della v3.6: lo ha scritto il server
v3.5 (il codice di b3479b9) attraverso le sue API, per una famiglia con due figli:
Luca col telefono (il token d'ambiente) e un computer, Sara col suo telefono. Dentro
c'e' un po' di tutto: regole dei due tipi di dispositivo e di vita reale, fotografie,
sforamenti, bonus, proposte dei due autori in tutti gli stati, verdetti, il segno,
sessioni approvate, rifiutate, in attesa e con un cambio in attesa, sessioni svolte,
notifiche lette e non lette dal genitore e dai dispositivi.

Serve ai test della migrazione v3.6 (test_genitori.py): niente si perde, la copia si
fa prima, le letture del genitore restano sue, e gli stessi numeri di prima.

- dati/v35.sql e' il database (sqlite3 iterdump), cosi' com'era dopo i passi di
  `genera`, alla stessa ora (ORA_V35);
- dati/v35_prima.json sono le risposte del server v3.5 su quel database a ORA_V35,
  per le letture di LETTURE.

Se servisse rigenerarli si fa allo stesso modo, col server v3.5 e mai con quello
nuovo: `git archive b3479b9 server | tar -x -C <cartella>` e poi
`python tests/dati_v35.py <cartella>/server` (i token dei dispositivi abbinati sono
fissi solo per i test: `abbinamento.nuovo_token` sostituito)."""

import json
import sqlite3
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

# Giovedi' 1 ottobre 2026, 10:00 UTC = 12:00 a Roma. Finestra: 24/09-01/10.
ORA_V35 = datetime(2026, 10, 1, 10, 0, 0, tzinfo=timezone.utc)
INIZIO = datetime(2026, 9, 24, 8, 0, 0, tzinfo=timezone.utc)

# I token d'ambiente sono quelli di conftest; quelli abbinati sono fissi solo qui.
TOKEN_FIGLIO = "tok-figlio-test"
TOKEN_GENITORE = "tok-genitore-test"
TOKEN_COMPUTER = "tok-computer-di-luca-v35"
TOKEN_SARA = "tok-telefono-di-sara-v35"

CARTELLA = Path(__file__).parent / "dati"
FILE_SQL = CARTELLA / "v35.sql"
FILE_PRIMA = CARTELLA / "v35_prima.json"

TIKTOK = "com.zhiliaoapp.musically"
INSTAGRAM = "com.instagram.android"
SCUOLA = ["eu.spaggiari.classevivafamiglia", "com.google.android.apps.classroom"]
NOMI_SCUOLA = {SCUOLA[0]: "ClasseViva", SCUOLA[1]: "Classroom"}

# Le letture confrontate prima e dopo: (nome, percorso, chi).
LETTURE = [
    ("famiglia", "/api/famiglia", "genitore"),
    ("finestra_luca", "/api/finestra", "genitore"),
    ("finestra_sara", "/api/finestra?figlio_id=2", "genitore"),
    ("notifiche_genitore", "/api/notifiche", "genitore"),
    ("proposte_genitore", "/api/proposte?autori=tutti", "genitore"),
    ("proposte_genitore_012", "/api/proposte", "genitore"),
    ("dichiarazioni_genitore", "/api/dichiarazioni", "genitore"),
    ("sessioni_genitore", "/api/sessioni", "genitore"),
    ("regole_sara", "/api/regole?figlio_id=2", "genitore"),
    ("patto_telefono", "/api/patto", "telefono"),
    ("notifiche_telefono", "/api/notifiche", "telefono"),
    ("proposte_telefono", "/api/proposte?autori=tutti", "telefono"),
    ("sessioni_telefono", "/api/sessioni", "telefono"),
    ("patto_computer", "/api/patto", "computer"),
    ("notifiche_computer", "/api/notifiche", "computer"),
    ("patto_sara", "/api/patto", "sara"),
    ("notifiche_sara", "/api/notifiche", "sara"),
]

TOKEN = {
    "genitore": TOKEN_GENITORE,
    "telefono": TOKEN_FIGLIO,
    "computer": TOKEN_COMPUTER,
    "sara": TOKEN_SARA,
}


def intestazione(chi: str) -> dict:
    return {"Authorization": f"Bearer {TOKEN[chi]}"}


def crea_db_v35(path: str) -> None:
    """Crea in `path` il database v3.5 di dati/v35.sql."""
    conn = sqlite3.connect(path)
    try:
        conn.executescript(FILE_SQL.read_text(encoding="utf-8"))
        conn.commit()
    finally:
        conn.close()


def prima() -> dict:
    return json.loads(FILE_PRIMA.read_text(encoding="utf-8"))


# --- la generazione, col server v3.5 ---

def _passi(c, orologio) -> None:
    G, TEL, PC, SARA = (intestazione(chi) for chi in ("genitore", "telefono", "computer", "sara"))

    def ok(risposta, atteso=200):
        assert risposta.status_code == atteso, risposta.text
        return risposta.json()

    def eventi(h, *lista):
        ok(c.post("/api/eventi", json={"eventi": list(lista)}, headers=h))

    def uso(h, chiave, giorno, minuti, nomi, extra=None):
        return {"id": f"uso-{chiave}-{giorno}", "tipo": "uso_giornaliero", "ts_device": None,
                "dettagli": {"giorno": giorno, "uso_minuti": minuti, "nomi": nomi,
                             "totale_minuti": sum(minuti.values()), **(extra or {})}}

    # Giorno 1: la famiglia, i dispositivi, le regole.
    ok(c.patch("/api/figli/1", json={"nome": "Luca"}, headers=G))
    pc = ok(c.post("/api/figli/1/dispositivi", json={"nome": "Computer", "tipo": "computer"}, headers=G), 201)
    ok(c.post("/api/abbina", json={"codice": pc["codice"], "tipo": "computer", "versione_app": "0.10.0"}))
    sara = ok(c.post("/api/figli", json={"nome": "Sara"}, headers=G), 201)
    tel_sara = ok(c.post(f"/api/figli/{sara['id']}/dispositivi",
                         json={"nome": "Telefono di Sara", "tipo": "telefono"}, headers=G), 201)
    ok(c.post("/api/abbina", json={"codice": tel_sara["codice"], "tipo": "telefono", "versione_app": "0.12.0"}))
    for h, versione in ((TEL, "0.12.0"), (PC, "0.10.0"), (SARA, "0.12.0")):
        ok(c.post("/api/battito", json={"versione_app": versione, "batteria": 80}, headers=h))

    def regola(h, tipo, parametri):
        return ok(c.post("/api/regole", json={"tipo": tipo, "parametri": parametri}, headers=h), 201)

    tiktok = regola(TEL, "limite_tempo", {"app_o_categoria": TIKTOK, "minuti_al_giorno": 60})
    totale = regola(TEL, "limite_tempo", {"app_o_categoria": "totale", "minuti_al_giorno": 180})
    notte = regola(TEL, "fascia_oraria", {"dalle": "22:00", "alle": "07:00",
                                           "giorni": ["lun", "mar", "mer", "gio", "ven", "sab", "dom"]})
    lettura = regola(TEL, "vita_reale", {"descrizione": "Leggere 20 minuti", "arbitro_nome": "Nonna",
                                         "frequenza": "ogni giorno"})
    minecraft = regola(PC, "limite_tempo", {"app_o_categoria": "exe:minecraft.exe", "minuti_al_giorno": 60})
    instagram = regola(SARA, "limite_tempo", {"app_o_categoria": INSTAGRAM, "minuti_al_giorno": 45})

    orologio.avanza(hours=10)  # 18:00 UTC
    eventi(TEL, uso(TEL, "tel", "2026-09-24", {TIKTOK: 75, SCUOLA[0]: 20}, {TIKTOK: "TikTok", SCUOLA[0]: "ClasseViva"}),
           {"id": "sfora-tel-0924", "tipo": "sforamento", "ts_device": None,
            "dettagli": {"regola_id": tiktok["id"], "giorno": "2026-09-24", "limite_efficace": 60, "minuti_oltre": 15}})
    eventi(PC, uso(PC, "pc", "2026-09-24", {"exe:minecraft.exe": 50}, {"exe:minecraft.exe": "Minecraft"}),
           {"id": "siti-pc-0924", "tipo": "siti_giornalieri", "ts_device": None,
            "dettagli": {"giorno": "2026-09-24", "domini": {"youtube.com": 4}, "minuti": {"youtube.com": 30},
                         "totale_domini": 1, "dns_cifrato": False}})
    eventi(SARA, uso(SARA, "sara", "2026-09-24", {INSTAGRAM: 40}, {INSTAGRAM: "Instagram"}),
           {"id": "mano-sara-0924", "tipo": "manomissione", "ts_device": None,
            "dettagli": {"sotto_tipo": "permesso_revocato"}})
    ok(c.post("/api/bonus", json={"minuti": 15, "regola_id": tiktok["id"], "motivo": "video di scuola"}, headers=TEL))
    dichiarata = ok(c.post("/api/dichiarazioni", json={"regola_id": lettura["id"], "esito": "successo",
                                                       "giorno": "2026-09-24"}, headers=TEL))
    ok(c.post(f"/api/dichiarazioni/{dichiarata['id']}/verdetto",
              json={"verdetto": "conferma_per_conto", "nota": "sentita al telefono"}, headers=G))
    ok(c.post("/api/dichiarazioni", json={"regola_id": lettura["id"], "esito": "fallimento",
                                          "giorno": "2026-09-23"}, headers=TEL))
    ok(c.post("/api/segno", json={"figlio_id": 1}, headers=G))

    # Giorno 2: le proposte, dei due autori e in tutti gli stati.
    orologio.avanza(hours=16)  # 25/09 10:00 UTC

    def proposta(h, regola_id, parametri, motivazione=None):
        return ok(c.post("/api/proposte", json={"regola_id": regola_id, "parametri_proposti": parametri,
                                                "motivazione": motivazione}, headers=h))

    def risposta(h, proposta_id, esito, motivazione=None):
        return ok(c.post(f"/api/proposte/{proposta_id}/risposta",
                         json={"esito": esito, "motivazione": motivazione}, headers=h))

    p = proposta(G, tiktok["id"], {"app_o_categoria": TIKTOK, "minuti_al_giorno": 45}, "meno TikTok")
    risposta(TEL, p["id"], "accetta", "va bene")
    p = proposta(G, totale["id"], {"app_o_categoria": "totale", "minuti_al_giorno": 150})
    risposta(PC, p["id"], "rifiuta", "troppo poco")
    p = proposta(TEL, minecraft["id"], {"app_o_categoria": "exe:minecraft.exe", "minuti_al_giorno": 90},
                 "nel weekend")
    risposta(G, p["id"], "accetta", "solo sabato")
    p = proposta(PC, notte["id"], {"dalle": "23:00", "alle": "07:00",
                                   "giorni": ["lun", "mar", "mer", "gio", "ven", "sab", "dom"]})
    risposta(G, p["id"], "rifiuta", "no")
    p = proposta(G, lettura["id"], {"descrizione": "Leggere 30 minuti", "arbitro_nome": "Nonna",
                                    "frequenza": "ogni giorno"})
    ok(c.post(f"/api/proposte/{p['id']}/ritira", headers=G))
    proposta(TEL, totale["id"], {"app_o_categoria": "totale", "minuti_al_giorno": 200}, "per i compiti")

    # Giorno 3: le sessioni.
    orologio.avanza(days=1)  # 26/09 10:00 UTC

    def sessione(nome, app, nomi=None):
        return ok(c.post("/api/sessioni", json={"nome": nome, "app": app, "nomi": nomi or {}}, headers=TEL), 201)

    def decidi(s, esito, motivazione=None):
        return ok(c.post(f"/api/sessioni/{s['id']}/risposta",
                         json={"esito": esito, "versione": s["versione"], "motivazione": motivazione}, headers=G))

    studio = decidi(sessione("Studio", SCUOLA, NOMI_SCUOLA), "approva")
    decidi(sessione("Giochi", ["com.supercell.clashroyale"], {"com.supercell.clashroyale": "Clash Royale"}),
           "rifiuta", "non e' studio")
    sessione("Musica", ["com.spotify.music"], {"com.spotify.music": "Spotify"})
    svolta = ok(c.post(f"/api/sessioni/{studio['id']}/avvia", json={"durata_minuti": 60}, headers=TEL), 201)
    orologio.avanza(minutes=30)
    ok(c.post("/api/sessioni/in_corso/termina", json={"svolta_id": svolta["id"]}, headers=TEL))
    orologio.avanza(hours=2)
    ok(c.post(f"/api/sessioni/{studio['id']}/avvia", json={"durata_minuti": 30}, headers=TEL), 201)
    orologio.avanza(hours=1)  # la seconda e' scaduta da sola
    ok(c.patch(f"/api/sessioni/{studio['id']}", json={"app": SCUOLA + ["com.duolingo"]}, headers=TEL))
    ok(c.post("/api/segno", json={"figlio_id": 2}, headers=G))

    # Giorni 4-7: le fotografie di ogni giorno e qualche sforamento.
    for giorno in range(25, 31):
        data = f"2026-09-{giorno}"
        eventi(TEL, uso(TEL, "tel", data, {TIKTOK: 30 + giorno, SCUOLA[0]: 25}, {TIKTOK: "TikTok"},
                        {"sessioni_minuti": 30}))
        eventi(PC, uso(PC, "pc", data, {"exe:minecraft.exe": giorno}, {"exe:minecraft.exe": "Minecraft"}))
        eventi(SARA, uso(SARA, "sara", data, {INSTAGRAM: 20 + giorno}, {INSTAGRAM: "Instagram"}))
    eventi(SARA, {"id": "sfora-sara-0929", "tipo": "sforamento", "ts_device": None,
                  "dettagli": {"regola_id": instagram["id"], "giorno": "2026-09-29", "limite_efficace": 45, "minuti_oltre": 5}})

    # Le letture: il genitore ha letto le prime sei delle sue, il telefono le prime due
    # delle sue, il computer il segno.
    orologio.vai_a(ORA_V35 - timedelta(hours=1))
    del_genitore = [n["id"] for n in ok(c.get("/api/notifiche", headers=G))["notifiche"]]
    for notifica_id in del_genitore[:6]:
        ok(c.post(f"/api/notifiche/{notifica_id}/letta", headers=G))
    del_telefono = [n["id"] for n in ok(c.get("/api/notifiche", headers=TEL))["notifiche"]]
    for notifica_id in del_telefono[:2]:
        ok(c.post(f"/api/notifiche/{notifica_id}/letta", headers=TEL))
    for n in ok(c.get("/api/notifiche", headers=PC))["notifiche"]:
        if n["tipo"] == "segno":
            ok(c.post(f"/api/notifiche/{n['id']}/letta", headers=PC))


def genera(cartella_server: str) -> None:
    """Scrive dati/v35.sql e dati/v35_prima.json col server di `cartella_server`
    (quello della v3.5: v. sopra)."""
    import os
    import tempfile

    sys.path.insert(0, cartella_server)
    from fastapi.testclient import TestClient

    from app import abbinamento, clock

    class Orologio:
        def __init__(self):
            self.corrente = INIZIO

        def avanza(self, **kwargs):
            self.corrente += timedelta(**kwargs)

        def vai_a(self, quando):
            self.corrente = quando

    orologio = Orologio()
    clock.now = lambda: orologio.corrente
    token_fissi = iter([TOKEN_COMPUTER, TOKEN_SARA])
    abbinamento.nuovo_token = lambda: next(token_fissi)

    with tempfile.TemporaryDirectory() as cartella:
        db = os.path.join(cartella, "pactum.db")
        os.environ.update({
            "PACTUM_DB": db, "PACTUM_TOKEN_FIGLIO": TOKEN_FIGLIO,
            "PACTUM_TOKEN_GENITORE": TOKEN_GENITORE, "PACTUM_BACKUP_DIR": os.path.join(cartella, "niente"),
        })
        from app.main import create_app

        with TestClient(create_app()) as c:
            _passi(c, orologio)
            orologio.vai_a(ORA_V35)
            risposte = {}
            for nome, percorso, chi in LETTURE:
                r = c.get(percorso, headers=intestazione(chi))
                assert r.status_code == 200, r.text
                risposte[nome] = r.json()
        conn = sqlite3.connect(db)
        try:
            FILE_SQL.write_text("\n".join(conn.iterdump()) + "\n", encoding="utf-8")
        finally:
            conn.close()
    FILE_PRIMA.write_text(json.dumps(risposte, ensure_ascii=False, indent=1, sort_keys=True) + "\n",
                          encoding="utf-8")


if __name__ == "__main__":
    genera(sys.argv[1])
