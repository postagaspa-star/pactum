"""Batch di eventi (contratto-api.md): corpo {"eventi": [...]}, ts_server
assegnato dal server, ts_device epoch in millisecondi informativo, idempotenza
sull'id generato dal client, notifiche solo per sforamenti e manomissioni nuovi,
uso_giornaliero = fotografia cumulativa (la vigente e' monotona su totale_minuti:
una fotografia in ritardo con totale piu' basso non regredisce quella vigente)."""

import json
import sqlite3

from conftest import FIGLIO, GENITORE

TS_DEVICE = 1784056519000  # 14/07/2026 ~09:55 UTC in epoch ms


def _posta(client, eventi):
    return client.post("/api/eventi", json={"eventi": eventi}, headers=FIGLIO)


def _uso_giornaliero(db_path):
    """La fotografia vigente per giorno, letta dal database."""
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    righe = conn.execute("SELECT * FROM uso_giornaliero ORDER BY giorno").fetchall()
    conn.close()
    return {
        r["giorno"]: {"dettagli": json.loads(r["dettagli"]), "evento_id": r["evento_id"]}
        for r in righe
    }


EVENTI = [
    {
        "id": "550e8400-e29b-41d4-a716-446655440000",
        "tipo": "uso_giornaliero",
        "ts_device": TS_DEVICE,
        "dettagli": {
            "giorno": "2026-07-14",
            "uso_minuti": {"com.instagram.android": 42},
            "totale_minuti": 137,
        },
    },
    {"id": "ev-sfor-1", "tipo": "sforamento", "dettagli": {"regola_id": 1, "app": "TikTok"}},
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
    evento = {"id": "ev-doppio", "tipo": "riavvio", "dettagli": {}}
    risposta = _posta(client, [evento, evento])
    assert risposta.json() == {"ricevuti": 2, "nuovi": 1, "duplicati": 1}


def test_batch_vuoto(client):
    risposta = _posta(client, [])
    assert risposta.status_code == 200
    assert risposta.json() == {"ricevuti": 0, "nuovi": 0, "duplicati": 0}


def test_corpo_senza_chiave_eventi_422(client):
    # Il contratto incapsula il batch in {"eventi": [...]}: la lista nuda non vale.
    risposta = client.post("/api/eventi", json=[], headers=FIGLIO)
    assert risposta.status_code == 422


def test_tipo_evento_sconosciuto_422(client):
    risposta = _posta(client, [{"id": "x", "tipo": "spionaggio", "dettagli": {}}])
    assert risposta.status_code == 422


def test_uso_app_non_e_piu_un_tipo_valido(client):
    # Il vocabolario del contratto ha uso_giornaliero, non uso_app.
    risposta = _posta(client, [{"id": "x", "tipo": "uso_app", "dettagli": {}}])
    assert risposta.status_code == 422


def test_ts_device_non_numerico_422(client):
    # ts_device e' epoch in millisecondi (numero intero), non ISO 8601.
    risposta = _posta(
        client,
        [{"id": "x", "tipo": "riavvio", "ts_device": "2026-07-14T09:58:00+00:00"}],
    )
    assert risposta.status_code == 422


def test_campi_sconosciuti_ignorati(client):
    # Tolleranza evolutiva: il server ignora i campi che non conosce.
    risposta = client.post(
        "/api/eventi",
        json={
            "eventi": [
                {
                    "id": "ev-futuro",
                    "tipo": "riavvio",
                    "ts_device": TS_DEVICE,
                    "dettagli": {},
                    "campo_del_futuro": "ciao",
                }
            ],
            "altra_chiave_futura": 42,
        },
        headers=FIGLIO,
    )
    assert risposta.status_code == 200
    assert risposta.json() == {"ricevuti": 1, "nuovi": 1, "duplicati": 0}


def test_ts_server_fa_fede_e_ts_device_resta_informativo(client):
    # ts_device spara una data assurda (1999): viene conservato, ma ts_server fa fede
    _posta(
        client,
        [
            {
                "id": "ev-orologio-truccato",
                "tipo": "sforamento",
                "dettagli": {"regola_id": 1},
                "ts_device": 915148800000,
            }
        ],
    )
    finestra = client.get("/api/finestra", headers=GENITORE).json()
    sforamento = finestra["sforamenti_recenti"][0]
    assert sforamento["ts_server"] == "2026-07-14T10:00:00+00:00"
    assert sforamento["ts_device"] == 915148800000


# --- uso_giornaliero: fotografia cumulativa, la vigente e' monotona sul totale ---

def _foto(evento_id, giorno, totale, uso=None):
    return {
        "id": evento_id,
        "tipo": "uso_giornaliero",
        "ts_device": TS_DEVICE,
        "dettagli": {
            "giorno": giorno,
            "uso_minuti": uso or {"com.instagram.android": totale},
            "totale_minuti": totale,
        },
    }


def test_uso_giornaliero_fotografia_piu_alta_vince(client, db_path):
    _posta(client, [_foto("foto-1", "2026-07-14", 30)])
    _posta(client, [_foto("foto-2", "2026-07-14", 137)])
    vigente = _uso_giornaliero(db_path)
    assert list(vigente.keys()) == ["2026-07-14"]
    assert vigente["2026-07-14"]["evento_id"] == "foto-2"
    assert vigente["2026-07-14"]["dettagli"]["totale_minuti"] == 137


def test_uso_giornaliero_fotografia_in_ritardo_non_regredisce(client, db_path):
    """Consegna fuori ordine: la 137 arriva prima, poi (in ritardo) una vecchia
    fotografia da 30 con id NUOVO. La vigente resta 137: e' monotona sul totale."""
    _posta(client, [_foto("foto-alta", "2026-07-14", 137)])
    risposta = _posta(client, [_foto("foto-bassa-in-ritardo", "2026-07-14", 30)])
    assert risposta.json()["nuovi"] == 1  # il registro la conserva comunque
    vigente = _uso_giornaliero(db_path)
    assert vigente["2026-07-14"]["evento_id"] == "foto-alta"
    assert vigente["2026-07-14"]["dettagli"]["totale_minuti"] == 137


def test_uso_giornaliero_a_parita_di_totale_vince_la_piu_recente(client, db_path):
    _posta(client, [_foto("foto-a", "2026-07-14", 137)])
    _posta(client, [_foto("foto-b", "2026-07-14", 137)])
    assert _uso_giornaliero(db_path)["2026-07-14"]["evento_id"] == "foto-b"


def test_uso_giornaliero_totale_invalido_vale_zero(client, db_path):
    # totale non numerico: la fotografia vale 0 e non scalza una vigente vera...
    _posta(client, [_foto("foto-vera", "2026-07-14", 10)])
    _posta(
        client,
        [{
            "id": "foto-sballata",
            "tipo": "uso_giornaliero",
            "dettagli": {"giorno": "2026-07-14", "totale_minuti": "tanti"},
        }],
    )
    assert _uso_giornaliero(db_path)["2026-07-14"]["evento_id"] == "foto-vera"
    # ...ma per un giorno nuovo la fotografia (con totale 0) si indicizza comunque
    _posta(
        client,
        [{
            "id": "foto-senza-totale",
            "tipo": "uso_giornaliero",
            "dettagli": {"giorno": "2026-07-15", "uso_minuti": {}},
        }],
    )
    assert _uso_giornaliero(db_path)["2026-07-15"]["evento_id"] == "foto-senza-totale"
    _posta(client, [_foto("foto-15", "2026-07-15", 3)])  # 3 >= 0: sostituisce
    assert _uso_giornaliero(db_path)["2026-07-15"]["evento_id"] == "foto-15"


def test_uso_giornaliero_giorni_diversi_convivono(client, db_path):
    _posta(client, [_foto("foto-lun", "2026-07-13", 90), _foto("foto-mar", "2026-07-14", 30)])
    vigente = _uso_giornaliero(db_path)
    assert set(vigente.keys()) == {"2026-07-13", "2026-07-14"}
    assert vigente["2026-07-13"]["dettagli"]["totale_minuti"] == 90
    assert vigente["2026-07-14"]["dettagli"]["totale_minuti"] == 30


def test_uso_giornaliero_duplicato_non_sovrascrive(client, db_path):
    # Il replay di una vecchia fotografia (stesso id) non deve regredire la vigente.
    vecchia = _foto("foto-vecchia", "2026-07-14", 30)
    nuova = _foto("foto-nuova", "2026-07-14", 137)
    _posta(client, [vecchia])
    _posta(client, [nuova])
    risposta = _posta(client, [vecchia])  # replay: duplicato
    assert risposta.json() == {"ricevuti": 1, "nuovi": 0, "duplicati": 1}
    vigente = _uso_giornaliero(db_path)
    assert vigente["2026-07-14"]["evento_id"] == "foto-nuova"
    assert vigente["2026-07-14"]["dettagli"]["totale_minuti"] == 137


def test_uso_giornaliero_registro_conserva_tutte_le_fotografie(client, db_path):
    _posta(client, [_foto("foto-1", "2026-07-14", 30)])
    _posta(client, [_foto("foto-2", "2026-07-14", 137)])
    conn = sqlite3.connect(db_path)
    quante = conn.execute(
        "SELECT COUNT(*) FROM eventi WHERE tipo = 'uso_giornaliero'"
    ).fetchone()[0]
    conn.close()
    assert quante == 2  # il registro non dimentica, la vigente e' una sola


def test_uso_giornaliero_senza_giorno_resta_nel_registro(client, db_path):
    risposta = _posta(
        client, [{"id": "foto-zoppa", "tipo": "uso_giornaliero", "dettagli": {"totale_minuti": 5}}]
    )
    assert risposta.json()["nuovi"] == 1
    assert _uso_giornaliero(db_path) == {}  # niente giorno, niente fotografia vigente


def test_uso_giornaliero_giorno_non_valido_resta_nel_registro(client, db_path):
    """Il giorno deve essere una data reale YYYY-MM-DD: tutto il resto non
    indicizza la vigente (ma il registro conserva l'evento cosi' com'e')."""
    cattivi = ["14/07/2026", "2026-13-01", "2026-02-30", "2026-7-4", "oggi", "2026-07-14T00", 12345]
    for n, giorno in enumerate(cattivi):
        risposta = _posta(
            client,
            [{
                "id": f"foto-cattiva-{n}",
                "tipo": "uso_giornaliero",
                "dettagli": {"giorno": giorno, "totale_minuti": 5},
            }],
        )
        assert risposta.json()["nuovi"] == 1, giorno
    assert _uso_giornaliero(db_path) == {}
    conn = sqlite3.connect(db_path)
    quante = conn.execute(
        "SELECT COUNT(*) FROM eventi WHERE tipo = 'uso_giornaliero'"
    ).fetchone()[0]
    conn.close()
    assert quante == len(cattivi)  # il registro non giudica, conserva


def test_uso_giornaliero_non_notifica(client):
    _posta(client, [_foto("foto-quieta", "2026-07-14", 30)])
    assert client.get("/api/notifiche", headers=GENITORE).json()["notifiche"] == []


# --- riavvio: registrato ma NON manomissione, nessuna notifica ---

def test_riavvio_registrato_senza_notifica(client, db_path):
    risposta = _posta(client, [{"id": "ev-reboot", "tipo": "riavvio", "dettagli": {}}])
    assert risposta.json() == {"ricevuti": 1, "nuovi": 1, "duplicati": 0}
    assert client.get("/api/notifiche", headers=GENITORE).json()["notifiche"] == []
    conn = sqlite3.connect(db_path)
    tipo = conn.execute("SELECT tipo FROM eventi WHERE id = 'ev-reboot'").fetchone()[0]
    conn.close()
    assert tipo == "riavvio"


def test_riavvio_non_compare_tra_le_manomissioni(client):
    _posta(client, [{"id": "ev-reboot-2", "tipo": "riavvio", "dettagli": {}}])
    finestra = client.get("/api/finestra", headers=GENITORE).json()
    assert finestra["manomissioni_recenti"] == []


# --- notifiche: solo sforamento e manomissione, solo alla prima ricezione ---

def test_sforamento_e_manomissione_notificano_una_volta_sola(client):
    batch = [
        {"id": "ev-sfor-n", "tipo": "sforamento", "dettagli": {"regola_id": 1}},
        {"id": "ev-mano-n", "tipo": "manomissione", "dettagli": {"sotto_tipo": "silenzio"}},
        {"id": "ev-reboot-n", "tipo": "riavvio", "dettagli": {}},
    ]
    _posta(client, batch)
    _posta(client, batch)  # replay completo: nessuna nuova notifica
    notifiche = client.get("/api/notifiche", headers=GENITORE).json()["notifiche"]
    tipi = sorted(n["tipo"] for n in notifiche)
    assert tipi == ["manomissione", "sforamento"]


def test_notifica_di_evento_espone_i_dettagli(client):
    _posta(
        client,
        [{"id": "ev-mano-d", "tipo": "manomissione", "dettagli": {"sotto_tipo": "cambio_ora", "drift_secondi": 3600}}],
    )
    notifiche = client.get("/api/notifiche", headers=GENITORE).json()["notifiche"]
    assert notifiche[0]["payload"]["dettagli"] == {"sotto_tipo": "cambio_ora", "drift_secondi": 3600}


def test_notifica_marcabile_come_letta(client):
    _posta(client, [{"id": "ev-s", "tipo": "sforamento", "dettagli": {}}])
    notifiche = client.get("/api/notifiche", headers=GENITORE).json()["notifiche"]
    assert len(notifiche) == 1
    risposta = client.post(f"/api/notifiche/{notifiche[0]['id']}/letta", headers=GENITORE)
    assert risposta.status_code == 200
    assert client.get("/api/notifiche", headers=GENITORE).json()["notifiche"] == []


def test_notifica_inesistente_404(client):
    assert client.post("/api/notifiche/999/letta", headers=GENITORE).status_code == 404
