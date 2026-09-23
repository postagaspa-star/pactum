"""Heartbeat e rilevamento silenzio: risposta {"ricevuto": true} (contratto),
ts_device epoch in millisecondi + versione_app + elapsed_realtime conservati,
silente = nessun battito da piu' di 45 minuti sull'orologio del server."""

import sqlite3

from conftest import FIGLIO, GENITORE

BATTITO_PIENO = {
    "ts_device": 1784056519000,
    "versione_app": "0.1.0",
    "elapsed_realtime": 123456789,
    "batteria": 87,
}


def _silenzio(client):
    return client.get("/api/finestra", headers=GENITORE).json()["stato_silenzio"]


def test_battito_risponde_ricevuto(client):
    risposta = client.post("/api/battito", json=BATTITO_PIENO, headers=FIGLIO)
    assert risposta.status_code == 200
    assert risposta.json() == {"ricevuto": True}


def test_battito_conserva_tutti_i_campi(client, db_path):
    client.post("/api/battito", json=BATTITO_PIENO, headers=FIGLIO)
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    riga = conn.execute("SELECT * FROM battiti").fetchone()
    conn.close()
    assert riga["ts_device"] == 1784056519000
    assert riga["versione_app"] == "0.1.0"
    assert riga["elapsed_realtime"] == 123456789
    assert riga["batteria"] == 87
    assert riga["ts_server"] == "2026-07-14T10:00:00+00:00"  # fa fede il server


def test_battito_senza_corpo(client):
    assert client.post("/api/battito", headers=FIGLIO).status_code == 200


def test_battito_con_campi_sconosciuti_ignorati(client):
    # Tolleranza evolutiva: il server ignora i campi che non conosce.
    corpo = {**BATTITO_PIENO, "campo_del_futuro": True}
    risposta = client.post("/api/battito", json=corpo, headers=FIGLIO)
    assert risposta.status_code == 200
    assert risposta.json() == {"ricevuto": True}


def test_batteria_fuori_scala_422(client):
    assert client.post("/api/battito", json={"batteria": 150}, headers=FIGLIO).status_code == 422


def test_ts_device_non_numerico_422(client):
    # ts_device e' epoch in millisecondi (numero intero), non ISO 8601.
    corpo = {"ts_device": "2026-07-14T09:55:19+00:00"}
    assert client.post("/api/battito", json=corpo, headers=FIGLIO).status_code == 422


def test_mai_sentita_e_silente(client):
    stato = _silenzio(client)
    # (v3) stato_silenzio porta anche spento/spento_dal: per un telefono sempre false/null.
    assert stato == {"ultimo_battito": None, "silente": True, "spento": False, "spento_dal": None}


def test_battito_recente_non_silente(client, orologio):
    client.post("/api/battito", json={}, headers=FIGLIO)
    orologio.avanza(minutes=10)
    stato = _silenzio(client)
    assert stato["silente"] is False
    assert stato["ultimo_battito"] == "2026-07-14T10:00:00+00:00"


def test_a_45_minuti_esatti_non_ancora_silente(client, orologio):
    client.post("/api/battito", json={}, headers=FIGLIO)
    orologio.avanza(minutes=45)
    assert _silenzio(client)["silente"] is False


def test_oltre_45_minuti_silente(client, orologio):
    client.post("/api/battito", json={}, headers=FIGLIO)
    orologio.avanza(minutes=46)
    stato = _silenzio(client)
    assert stato["silente"] is True
    assert stato["ultimo_battito"] == "2026-07-14T10:00:00+00:00"


def test_nuovo_battito_azzera_il_silenzio(client, orologio):
    client.post("/api/battito", json={}, headers=FIGLIO)
    orologio.avanza(hours=2)
    assert _silenzio(client)["silente"] is True
    client.post("/api/battito", json={"batteria": 15}, headers=FIGLIO)
    stato = _silenzio(client)
    assert stato["silente"] is False
    assert stato["ultimo_battito"] == "2026-07-14T12:00:00+00:00"
