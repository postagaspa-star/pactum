"""Un database come quello vero sul NAS prima della v3.9: lo ha scritto il server v3.8
attraverso le sue API, per una famiglia con due figli e due genitori: Luca col telefono
(il token d'ambiente) e un computer, Sara col suo telefono, il genitore 1 e la Mamma.
Dentro ci sono soprattutto i lavori di casa in tutti gli stati: da fare (uno che blocca
gia' e uno programmato), fatti (uno con una bocciatura e la foto rifatta), annullati,
con le loro storie e le foto; piu' una regola e qualche fotografia d'uso.

Serve ai test della migrazione v3.9 (test_faccende_v39.py): la copia si fa prima,
niente si perde (la storia delle faccende si rifa' con gli stessi id), gli stessi
numeri di prima.

- dati/v38.sql e' il database (sqlite3 iterdump), cosi' com'era dopo i passi di
  `genera`, alla stessa ora (ORA_V38);
- dati/v38_foto.json sono i file della cartella foto/ accanto al database (in
  esadecimale): le foto non stanno nel database;
- dati/v38_prima.json sono le risposte del server v3.8 su quel database a ORA_V38,
  per le letture di LETTURE.

Se servisse rigenerarli si fa col server v3.8 (la cartella server/ di prima della v3.9)
e mai con quello nuovo: `python tests/dati_v38.py <cartella>/server` (i token abbinati
sono fissi solo per i test: `abbinamento.nuovo_token` sostituito). Tutti i dati sono
finti."""

import json
import os
import sqlite3
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

# Lunedi' 5 ottobre 2026, 10:00 UTC = 12:00 a Roma.
ORA_V38 = datetime(2026, 10, 5, 10, 0, 0, tzinfo=timezone.utc)
INIZIO = datetime(2026, 10, 3, 8, 0, 0, tzinfo=timezone.utc)

TOKEN_FIGLIO = "tok-figlio-test"
TOKEN_GENITORE = "tok-genitore-test"
TOKEN_COMPUTER = "tok-computer-di-luca-v38"
TOKEN_MAMMA = "tok-mamma-v38"
TOKEN_SARA = "tok-telefono-di-sara-v38"

CARTELLA = Path(__file__).parent / "dati"
FILE_SQL = CARTELLA / "v38.sql"
FILE_FOTO = CARTELLA / "v38_foto.json"
FILE_PRIMA = CARTELLA / "v38_prima.json"

TIKTOK = "com.zhiliaoapp.musically"

LETTURE = [
    ("faccende_luca", "/api/faccende", "genitore"),
    ("faccende_sara", "/api/faccende?figlio_id=2", "mamma"),
    ("faccende_telefono", "/api/faccende", "telefono"),
    ("faccende_computer", "/api/faccende", "computer"),
    ("blocco_telefono", "/api/faccende/blocco", "telefono"),
    ("blocco_sara", "/api/faccende/blocco", "sara"),
    ("patto_telefono", "/api/patto", "telefono"),
    ("patto_sara", "/api/patto", "sara"),
    ("finestra_luca", "/api/finestra", "genitore"),
    ("famiglia", "/api/famiglia", "genitore"),
    ("notifiche_telefono", "/api/notifiche", "telefono"),
    ("notifiche_genitore", "/api/notifiche", "genitore"),
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


def crea_db_v38(path: str) -> None:
    """Crea in `path` il database v3.8 di dati/v38.sql e, accanto, la cartella foto/."""
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
            + _segmento(0xE1, b"Exif\x00\x00MM\x00\x2a\x00\x00\x00\x08GPS:POSIZIONE-DI-PROVA")
            + _segmento(0xDB, b"\x00" + bytes(range(1, 65)))
            + _segmento(0xC0, b"\x08\x00\x10\x00\x10\x01\x01\x11\x00")
            + _segmento(0xC4, b"\x00" + bytes([1] + [0] * 15) + b"\x00")
            + _segmento(0xDA, b"\x01\x01\x00\x00\x3f\x00") + b"\x12\x34" + diverso + b"\xff\xd9")


# --- la generazione, col server v3.8 ---

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

    # Sabato 3/10: la famiglia, i dispositivi, la Mamma, una regola.
    ok(c.patch("/api/figli/1", json={"nome": "Luca"}, headers=G))
    pc = ok(c.post("/api/figli/1/dispositivi", json={"nome": "Computer", "tipo": "computer"}, headers=G), 201)
    ok(c.post("/api/abbina", json={"codice": pc["codice"], "tipo": "computer", "versione_app": "0.14.0"}))
    mamma = ok(c.post("/api/genitori", json={"nome": "Mamma"}, headers=G), 201)
    ok(c.post("/api/abbina", json={"codice": mamma["codice"], "tipo": "genitore", "versione_app": "0.15.0"}))
    sara = ok(c.post("/api/figli", json={"nome": "Sara"}, headers=G), 201)
    tel_sara = ok(c.post(f"/api/figli/{sara['id']}/dispositivi",
                         json={"nome": "Telefono di Sara", "tipo": "telefono"}, headers=MAMMA), 201)
    ok(c.post("/api/abbina", json={"codice": tel_sara["codice"], "tipo": "telefono", "versione_app": "0.15.0"}))
    for h, versione in ((TEL, "0.15.0"), (PC, "0.14.0"), (SARA, "0.15.0")):
        ok(c.post("/api/battito", json={"versione_app": versione, "batteria": 80}, headers=h))
    ok(c.post("/api/regole", json={"tipo": "limite_tempo",
                                   "parametri": {"app_o_categoria": TIKTOK, "minuti_al_giorno": 60}}, headers=TEL), 201)
    ok(c.post("/api/eventi", json={"eventi": [
        {"id": "uso-tel-1003", "tipo": "uso_giornaliero", "ts_device": None,
         "dettagli": {"giorno": "2026-10-03", "uso_minuti": {TIKTOK: 40}, "nomi": {TIKTOK: "TikTok"},
                      "totale_minuti": 40}}]}, headers=TEL))

    # I lavori di sabato: due della Mamma (uno con la nota), uno del genitore 1, uno a Sara.
    orologio.avanza(hours=1)  # 09:00 UTC
    lavastoviglie, letto = dai(MAMMA, 1, {"titolo": "Svuota la lavastoviglie", "nota": "anche le pentole"},
                               "Rifai il letto")
    (cane,) = dai(G, 1, "Porta fuori il cane")
    (camera,) = dai(MAMMA, sara["id"], "Riordina la camera")
    orologio.avanza(minutes=30)
    foto(TEL, letto, b"letto-prima")
    orologio.avanza(minutes=10)
    letto = ok(c.post(f"/api/faccende/{letto['id']}/boccia", json={"nota": "le lenzuola!"}, headers=MAMMA))
    orologio.avanza(minutes=20)
    foto(TEL, letto, b"letto-seconda")
    ok(c.post(f"/api/faccende/{cane['id']}/annulla", headers=G))
    foto(SARA, camera, b"camera")

    # Domenica 4/10: un lavoro che blocca da subito, fatto; uno col caffe' (per la ricerca).
    orologio.vai_a(datetime(2026, 10, 4, 15, 0, tzinfo=timezone.utc))
    (caffe,) = dai(G, 1, "Prepara il caffè per papà")
    orologio.avanza(minutes=45)
    foto(TEL, caffe, b"caffe")

    # Lunedi' 5/10 mattina: la lavastoviglie fatta (foto di meno di 24 ore a ORA_V38),
    # un lavoro da fare che blocca gia' e uno programmato per il pomeriggio.
    orologio.vai_a(ORA_V38 - timedelta(hours=2))
    foto(TEL, lavastoviglie, b"lavastoviglie")
    orologio.avanza(minutes=30)
    dai(G, 1, "Stendi i panni")
    orologio.avanza(minutes=30)
    dai(MAMMA, 1, {"titolo": "Compiti di matematica", "nota": "pagina 42"},
        blocco_da="2026-10-05T16:00:00+02:00")


def genera(cartella_server: str) -> None:
    """Scrive dati/v38.sql, dati/v38_foto.json e dati/v38_prima.json col server di
    `cartella_server` (quello della v3.8: v. sopra)."""
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
            orologio.vai_a(ORA_V38)
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
