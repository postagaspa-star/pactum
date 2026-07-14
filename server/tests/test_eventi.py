"""Batch di eventi: ts_server assegnato dal server, idempotenza sull'id
generato dal client, notifiche solo per sforamenti e manomissioni nuovi."""

from conftest import FIGLIO, GENITORE


def _posta(client, eventi):
    return client.post("/api/eventi", json=eventi, headers=FIGLIO)


EVENTI = [
    {
        "id": "ev-uso-1",
        "tipo": "uso_app",
        "payload": {"app": "TikTok", "minuti": 30},
        "ts_device": "2026-07-14T09:58:00+00:00",
    },
    {"id": "ev-sfor-1", "tipo": "sforamento", "payload": {"regola_id": 1, "app": "TikTok"}},
]


def test_batch_registrato(client):
    risposta = _posta(client, EVENTI)
    assert risposta.status_code == 200
    assert risposta.json() == {"ricevuti": 2, "nuovi": 2, "duplicati": 0}


def test_ripostare_lo_stesso_batch_non_duplica(client):
    _posta(client, EVENTI)
    risposta = _posta(client, EVENTI)
    assert risposta.json() == {"ricevuti": 2, "nuovi": 0, "duplicati": 2}


def test_duplicato_dentro_lo_stesso_batch(client):
    evento = {"id": "ev-doppio", "tipo": "uso_app", "payload": {}}
    risposta = _posta(client, [evento, evento])
    assert risposta.json() == {"ricevuti": 2, "nuovi": 1, "duplicati": 1}


def test_batch_vuoto(client):
    risposta = _posta(client, [])
    assert risposta.status_code == 200
    assert risposta.json() == {"ricevuti": 0, "nuovi": 0, "duplicati": 0}


def test_tipo_evento_sconosciuto_422(client):
    risposta = _posta(client, [{"id": "x", "tipo": "spionaggio", "payload": {}}])
    assert risposta.status_code == 422


def test_ts_server_fa_fede_e_ts_device_resta_informativo(client):
    # ts_device spara una data assurda: viene conservato, ma ts_server fa fede
    _posta(
        client,
        [
            {
                "id": "ev-orologio-truccato",
                "tipo": "sforamento",
                "payload": {"regola_id": 1},
                "ts_device": "1999-01-01T00:00:00+00:00",
            }
        ],
    )
    finestra = client.get("/api/finestra", headers=GENITORE).json()
    sforamento = finestra["sforamenti_recenti"][0]
    assert sforamento["ts_server"] == "2026-07-14T10:00:00+00:00"
    assert sforamento["ts_device"] == "1999-01-01T00:00:00+00:00"


def test_sforamento_e_manomissione_notificano_una_volta_sola(client):
    batch = [
        {"id": "ev-sfor-n", "tipo": "sforamento", "payload": {"regola_id": 1}},
        {"id": "ev-mano-n", "tipo": "manomissione", "payload": {"dettaglio": "aereo 3h"}},
        {"id": "ev-uso-n", "tipo": "uso_app", "payload": {}},
    ]
    _posta(client, batch)
    _posta(client, batch)  # replay completo: nessuna nuova notifica
    notifiche = client.get("/api/notifiche", headers=GENITORE).json()["notifiche"]
    tipi = sorted(n["tipo"] for n in notifiche)
    assert tipi == ["manomissione", "sforamento"]


def test_notifica_marcabile_come_letta(client):
    _posta(client, [{"id": "ev-s", "tipo": "sforamento", "payload": {}}])
    notifiche = client.get("/api/notifiche", headers=GENITORE).json()["notifiche"]
    assert len(notifiche) == 1
    risposta = client.post(f"/api/notifiche/{notifiche[0]['id']}/letta", headers=GENITORE)
    assert risposta.status_code == 200
    assert client.get("/api/notifiche", headers=GENITORE).json()["notifiche"] == []


def test_notifica_inesistente_404(client):
    assert client.post("/api/notifiche/999/letta", headers=GENITORE).status_code == 404
