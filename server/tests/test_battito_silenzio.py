"""Heartbeat e rilevamento silenzio: silente = nessun battito da piu' di 45
minuti, calcolato in lettura sull'orologio del server."""

from conftest import FIGLIO, GENITORE


def _silenzio(client):
    return client.get("/api/finestra", headers=GENITORE).json()["stato_silenzio"]


def test_battito_registra_ts_server(client):
    risposta = client.post("/api/battito", json={"batteria": 80}, headers=FIGLIO)
    assert risposta.status_code == 200
    assert risposta.json() == {"ts_server": "2026-07-14T10:00:00+00:00"}


def test_battito_senza_corpo(client):
    assert client.post("/api/battito", headers=FIGLIO).status_code == 200


def test_batteria_fuori_scala_422(client):
    assert client.post("/api/battito", json={"batteria": 150}, headers=FIGLIO).status_code == 422


def test_mai_sentita_e_silente(client):
    stato = _silenzio(client)
    assert stato == {"ultimo_battito": None, "silente": True}


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
