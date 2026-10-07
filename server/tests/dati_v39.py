"""Un database come quello vero sul NAS prima della v4.0: lo ha scritto il server v3.9
(il codice di a36e9b4, cioe' la cartella server/ di prima della v4.0) attraverso le sue
API, per una famiglia con due figli e due genitori: Luca col telefono (il token
d'ambiente, app 0.17) e un computer (programma 0.14), Sara col suo telefono (0.17), il
genitore 1 e la Mamma. Dentro ci sono i lavori di casa in tutti gli stati della v3.9: da
fare (uno che blocca gia' e uno programmato), fatti con la foto (uno di meno di 24 ore e
non confermato, uno confermato, uno di piu' di 24 ore), annullati; una sessione approvata
e una svolta; regole e fotografie d'uso.

Serve ai test della migrazione v4.0 (test_migrazione_v40.py): la copia si fa prima,
niente si perde, le foto di prima non tornano a bloccare, lo Studio approvato per ogni
figlio dal giorno dopo, gli stessi numeri di prima.

- dati/v39.sql e' il database (sqlite3 iterdump), cosi' com'era dopo i passi di
  `genera`, alla stessa ora (ORA_V39);
- dati/v39_foto.json sono i file della cartella foto/ accanto al database (in
  esadecimale): le foto non stanno nel database;
- dati/v39_prima.json sono le risposte del server v3.9 su quel database a ORA_V39,
  per le letture di LETTURE.

Se servisse rigenerarli si fa col server v3.9 e mai con quello nuovo:
`git archive a36e9b4 server | tar -x -C <cartella>` e poi
`python tests/dati_v39.py <cartella>/server` (i token abbinati sono fissi solo per i
test: `abbinamento.nuovo_token` sostituito). Tutti i dati sono finti."""

import json
import os
import sqlite3
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

# Martedi' 6 ottobre 2026, 10:00 UTC = 12:00 a Roma. La v4.0 che parte a quest'ora mette
# gli orari dello Studio dal giorno dopo: mercoledi' 7, alle 15:00 di Roma (13:00 UTC).
ORA_V39 = datetime(2026, 10, 6, 10, 0, 0, tzinfo=timezone.utc)
INIZIO = datetime(2026, 10, 4, 8, 0, 0, tzinfo=timezone.utc)

TOKEN_FIGLIO = "tok-figlio-test"
TOKEN_GENITORE = "tok-genitore-test"
TOKEN_COMPUTER = "tok-computer-di-luca-v39"
TOKEN_MAMMA = "tok-mamma-v39"
TOKEN_SARA = "tok-telefono-di-sara-v39"

CARTELLA = Path(__file__).parent / "dati"
FILE_SQL = CARTELLA / "v39.sql"
FILE_FOTO = CARTELLA / "v39_foto.json"
FILE_PRIMA = CARTELLA / "v39_prima.json"

TIKTOK = "com.zhiliaoapp.musically"
SCUOLA = ["eu.spaggiari.classevivafamiglia", "com.google.android.apps.classroom"]

LETTURE = [
    ("faccende_luca", "/api/faccende", "genitore"),
    ("faccende_sara", "/api/faccende?figlio_id=2", "mamma"),
    ("faccende_telefono", "/api/faccende", "telefono"),
    ("blocco_telefono", "/api/faccende/blocco", "telefono"),
    ("blocco_computer", "/api/faccende/blocco", "computer"),
    ("blocco_sara", "/api/faccende/blocco", "sara"),
    ("patto_telefono", "/api/patto", "telefono"),
    ("patto_computer", "/api/patto", "computer"),
    ("patto_sara", "/api/patto", "sara"),
    ("finestra_luca", "/api/finestra", "genitore"),
    ("finestra_sara", "/api/finestra?figlio_id=2", "mamma"),
    ("famiglia", "/api/famiglia", "genitore"),
    ("sessioni_telefono", "/api/sessioni", "telefono"),
    ("notifiche_telefono", "/api/notifiche", "telefono"),
    ("notifiche_genitore", "/api/notifiche", "genitore"),
    ("notifiche_mamma", "/api/notifiche", "mamma"),
]

TOKEN = {
    "genitore": TOKEN_GENITORE,
    "mamma": TOKEN_MAMMA,
    "telefono": TOKEN_FIGLIO,
    "computer": TOKEN_COMPUTER,
    "sara": TOKEN_SARA,
}


def intestazione(chi: str) -> dict:
    return {"Authorization": f"Bearer {TOKEN[chi]}"}


def crea_db_v39(path: str) -> None:
    """Crea in `path` il database v3.9 di dati/v39.sql e, accanto, la cartella foto/."""
    conn = sqlite3.connect(path)
    try:
        conn.executescript(FILE_SQL.read_text(encoding="utf-8"))
        conn.commit()
    finally:
        conn.close()
    cartella = Path(path).parent / "foto"
    cartella.mkdir(exist_ok=True)
    for nome, esadecimale in json.loads(FILE_FOTO.read_text(encoding="utf-8")).items():
        (cartella / nome).write_bytes(bytes.fromhex(esadecimale))


def prima() -> dict:
    return json.loads(FILE_PRIMA.read_text(encoding="utf-8"))


# --- un JPEG piccolo (come in test_faccende.py), diverso per ogni foto ---

def _segmento(marcatore: int, dati: bytes) -> bytes:
    return bytes((0xFF, marcatore)) + (len(dati) + 2).to_bytes(2, "big") + dati


def jpeg(diverso: bytes) -> bytes:
    return (b"\xff\xd8" + _segmento(0xE0, b"JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00")
            + _segmento(0xDB, b"\x00" + bytes(range(1, 65)))
            + _segmento(0xC0, b"\x08\x00\x10\x00\x10\x01\x01\x11\x00")
            + _segmento(0xC4, b"\x00" + bytes([1] + [0] * 15) + b"\x00")
            + _segmento(0xDA, b"\x01\x01\x00\x00\x3f\x00") + b"\x12\x34" + diverso + b"\xff\xd9")


# --- la generazione, col server v3.9 ---

def _passi(c, orologio) -> None:
    G, MAMMA, TEL, PC, SARA = (intestazione(chi) for chi in ("genitore", "mamma", "telefono", "computer", "sara"))

    def ok(risposta, atteso=200):
        assert risposta.status_code == atteso, risposta.text
        return risposta.json()

    def dai(h, figlio_id, *titoli, blocco_da=None):
        corpo = {"figlio_id": figlio_id, "faccende": [t if isinstance(t, dict) else {"titolo": t} for t in titoli]}
        if blocco_da is not None:
            corpo["blocco_da"] = blocco_da
        return ok(c.post("/api/faccende", json=corpo, headers=h), 201)["faccende"]

    def foto(h, faccenda, diverso):
        return ok(c.put(f"/api/faccende/{faccenda['id']}/foto", content=jpeg(diverso),
                        headers={**h, "Content-Type": "image/jpeg"},
                        params={"bocciature": faccenda.get("bocciature", 0)}))

    # Domenica 4/10: la famiglia, i dispositivi, la Mamma, una regola, una sessione.
    ok(c.patch("/api/figli/1", json={"nome": "Luca"}, headers=G))
    pc = ok(c.post("/api/figli/1/dispositivi", json={"nome": "Computer", "tipo": "computer"}, headers=G), 201)
    ok(c.post("/api/abbina", json={"codice": pc["codice"], "tipo": "computer", "versione_app": "0.14.0"}))
    mamma = ok(c.post("/api/genitori", json={"nome": "Mamma"}, headers=G), 201)
    ok(c.post("/api/abbina", json={"codice": mamma["codice"], "tipo": "genitore", "versione_app": "0.17.0"}))
    sara = ok(c.post("/api/figli", json={"nome": "Sara"}, headers=G), 201)
    tel_sara = ok(c.post(f"/api/figli/{sara['id']}/dispositivi",
                         json={"nome": "Telefono di Sara", "tipo": "telefono"}, headers=MAMMA), 201)
    ok(c.post("/api/abbina", json={"codice": tel_sara["codice"], "tipo": "telefono", "versione_app": "0.17.0"}))
    for h, versione in ((TEL, "0.17.0"), (PC, "0.14.0"), (SARA, "0.17.0")):
        ok(c.post("/api/battito", json={"versione_app": versione, "batteria": 80}, headers=h))
    ok(c.post("/api/regole", json={"tipo": "limite_tempo",
                                   "parametri": {"app_o_categoria": TIKTOK, "minuti_al_giorno": 60}}, headers=TEL), 201)
    ok(c.post("/api/eventi", json={"eventi": [
        {"id": "uso-tel-1004", "tipo": "uso_giornaliero", "ts_device": None,
         "dettagli": {"giorno": "2026-10-04", "uso_minuti": {TIKTOK: 40}, "nomi": {TIKTOK: "TikTok"},
                      "totale_minuti": 40}},
        {"id": "uso-pc-1004", "tipo": "uso_giornaliero", "ts_device": None,
         "dettagli": {"giorno": "2026-10-04", "uso_minuti": {"exe:winword.exe": 30},
                      "nomi": {"exe:winword.exe": "Word"}, "totale_minuti": 30}}]}, headers=TEL))
    sessione = ok(c.post("/api/sessioni", json={"nome": "Compiti", "app": SCUOLA}, headers=TEL), 201)
    ok(c.post(f"/api/sessioni/{sessione['id']}/risposta", json={"esito": "approva", "versione": 1}, headers=MAMMA))

    # I lavori di domenica: uno fatto e confermato (oltre le 24 ore a ORA_V39), uno fatto
    # e mai confermato (oltre le 24 ore), uno annullato, uno di Sara fatto.
    orologio.avanza(hours=1)  # 09:00 UTC
    letto, cane, piatti = dai(MAMMA, 1, "Rifai il letto", "Porta fuori il cane", "Lava i piatti")
    (camera,) = dai(MAMMA, sara["id"], "Riordina la camera")
    orologio.avanza(minutes=30)
    letto_fatto = foto(TEL, letto, b"letto")
    ok(c.post(f"/api/faccende/{letto['id']}/conferma", json={"foto_ts": letto_fatto["foto_ts"]}, headers=G))
    foto(TEL, piatti, b"piatti")
    ok(c.post(f"/api/faccende/{cane['id']}/annulla", headers=G))
    foto(SARA, camera, b"camera")
    svolta = ok(c.post(f"/api/sessioni/{sessione['id']}/avvia", json={"durata_minuti": 45}, headers=TEL), 201)
    orologio.avanza(minutes=20)
    ok(c.post("/api/sessioni/in_corso/termina", json={"svolta_id": svolta["id"]}, headers=TEL))

    # Martedi' 6/10 mattina: un lavoro fatto da meno di 24 ore e non confermato (la sua
    # foto ha gia' sbloccato, e deve restare cosi'), uno da fare che blocca gia', uno
    # programmato per il pomeriggio. Le ultime fotografie e i battiti di stamattina.
    orologio.vai_a(ORA_V39 - timedelta(hours=2))
    (lavatrice,) = dai(G, 1, {"titolo": "Stendi la lavatrice", "nota": "i bianchi"})
    orologio.avanza(minutes=20)
    foto(TEL, lavatrice, b"lavatrice")
    orologio.avanza(minutes=40)
    dai(MAMMA, 1, "Svuota la lavastoviglie")
    orologio.avanza(minutes=30)
    dai(G, 1, {"titolo": "Compiti di matematica", "nota": "pagina 42"}, blocco_da="2026-10-06T17:00:00+02:00")
    ok(c.post("/api/eventi", json={"eventi": [
        {"id": "uso-tel-1006", "tipo": "uso_giornaliero", "ts_device": None,
         "dettagli": {"giorno": "2026-10-06", "uso_minuti": {TIKTOK: 15}, "nomi": {TIKTOK: "TikTok"},
                      "totale_minuti": 15}}]}, headers=TEL))
    orologio.avanza(minutes=25)
    for h in (TEL, PC, SARA):
        ok(c.post("/api/battito", json={"batteria": 70}, headers=h))


def genera(cartella_server: str) -> None:
    """Scrive dati/v39.sql, dati/v39_foto.json e dati/v39_prima.json col server di
    `cartella_server` (quello della v3.9: v. sopra)."""
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
    token_fissi = iter([TOKEN_COMPUTER, TOKEN_MAMMA, TOKEN_SARA])
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
            orologio.vai_a(ORA_V39)
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
        cartella_foto = Path(cartella) / "foto"
        foto = {f.name: f.read_bytes().hex() for f in sorted(cartella_foto.iterdir()) if f.is_file()}
    FILE_FOTO.write_text(json.dumps(foto, indent=1, sort_keys=True) + "\n", encoding="utf-8")
    FILE_PRIMA.write_text(json.dumps(risposte, ensure_ascii=False, indent=1, sort_keys=True) + "\n",
                          encoding="utf-8")


if __name__ == "__main__":
    genera(sys.argv[1])
