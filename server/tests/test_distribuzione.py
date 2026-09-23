"""Distribuzione (tappa 6): /api/versione (no auth), /scarica (HTML), download
degli APK con content-type giusto e 404 gentile se il file manca. In coda: la
prova che il vocabolario delle manomissioni resta aperto (permesso_revocato /
notifiche_disattivate affiorano nella finestra senza toccare lo schema)."""

import json

import pytest
from fastapi.testclient import TestClient

from conftest import FIGLIO, GENITORE, TOKEN_FIGLIO, TOKEN_GENITORE


@pytest.fixture
def apk_dir(tmp_path):
    d = tmp_path / "apk"
    d.mkdir()
    return d


@pytest.fixture
def client_apk(db_path, monkeypatch, orologio, apk_dir):
    """Come il client di conftest, ma con la cartella APK puntata su una tmp
    vuota: cosi' i test del download non dipendono da cosa c'e' in server/apk."""
    monkeypatch.setenv("PACTUM_DB", db_path)
    monkeypatch.setenv("PACTUM_TOKEN_FIGLIO", TOKEN_FIGLIO)
    monkeypatch.setenv("PACTUM_TOKEN_GENITORE", TOKEN_GENITORE)
    monkeypatch.setenv("PACTUM_APK_DIR", str(apk_dir))
    from app.main import create_app

    with TestClient(create_app()) as c:
        yield c


# --- GET /api/versione ---


def test_versione_shape_e_default(client):
    r = client.get("/api/versione")
    assert r.status_code == 200, r.text
    dati = r.json()
    # (v3) c'e' anche il programma per il computer, con la sua numerazione.
    assert set(dati) == {"figlio", "genitore", "computer"}
    assert dati["computer"]["versione_code"] == 1
    assert dati["computer"]["versione_nome"] == "0.8.0"
    assert dati["computer"]["url"] == "/scarica/pactum-computer.zip"
    for ruolo in ("figlio", "genitore"):
        blocco = dati[ruolo]
        assert set(blocco) >= {"versione_code", "versione_nome", "url"}
        assert blocco["versione_code"] == 7
        assert blocco["versione_nome"] == "0.7.0"
        assert blocco["url"] == f"/scarica/pactum-{ruolo}.apk"
    # Le due app viaggiano SEMPRE alla stessa versione: si rilasciano insieme e
    # la finestra del genitore deve mostrare quello che l'app del figlio manda.
    # Alzarne una sola e scordare l'altra e' l'errore facile: qui si vede subito.
    assert dati["figlio"]["versione_code"] == dati["genitore"]["versione_code"]
    assert dati["figlio"]["versione_nome"] == dati["genitore"]["versione_nome"]


def test_versione_senza_auth(client):
    # Nessun header: /api/versione e' metadata pubblico, deve rispondere lo stesso.
    r = client.get("/api/versione")
    assert r.status_code == 200
    # Anche un token del figlio non cambia nulla (endpoint senza auth).
    assert client.get("/api/versione", headers=FIGLIO).status_code == 200


def test_versione_letta_dal_json_aggiornabile(db_path, monkeypatch, orologio, tmp_path):
    # Il JSON e' la fonte: cambiarlo cambia la risposta, senza toccare il codice.
    percorso = tmp_path / "versioni.json"
    percorso.write_text(
        json.dumps(
            {
                "figlio": {
                    "versione_code": 7,
                    "versione_nome": "0.7.0",
                    "url": "/scarica/pactum-figlio.apk",
                    "note": "prova",
                },
                "genitore": {
                    "versione_code": 5,
                    "versione_nome": "0.5.0",
                    "url": "/scarica/pactum-genitore.apk",
                    "note": None,
                },
            }
        ),
        encoding="utf-8",
    )
    monkeypatch.setenv("PACTUM_DB", db_path)
    monkeypatch.setenv("PACTUM_TOKEN_FIGLIO", TOKEN_FIGLIO)
    monkeypatch.setenv("PACTUM_TOKEN_GENITORE", TOKEN_GENITORE)
    monkeypatch.setenv("PACTUM_VERSIONI", str(percorso))
    from app.main import create_app

    with TestClient(create_app()) as c:
        dati = c.get("/api/versione").json()
    assert dati["figlio"]["versione_code"] == 7
    assert dati["figlio"]["versione_nome"] == "0.7.0"
    assert dati["genitore"]["versione_code"] == 5


def test_versione_json_mancante_ricade_sui_default(db_path, monkeypatch, orologio, tmp_path):
    # Se il JSON manca, /api/versione non deve cadere: l'app ci si appoggia.
    monkeypatch.setenv("PACTUM_DB", db_path)
    monkeypatch.setenv("PACTUM_TOKEN_FIGLIO", TOKEN_FIGLIO)
    monkeypatch.setenv("PACTUM_TOKEN_GENITORE", TOKEN_GENITORE)
    monkeypatch.setenv("PACTUM_VERSIONI", str(tmp_path / "non-esiste.json"))
    from app.main import create_app

    with TestClient(create_app()) as c:
        r = c.get("/api/versione")
    assert r.status_code == 200
    assert r.json()["figlio"]["versione_code"] == 3


# --- GET /scarica ---


def test_scarica_pagina_html(client):
    r = client.get("/scarica")
    assert r.status_code == 200, r.text
    assert r.headers["content-type"].startswith("text/html")
    corpo = r.text
    # Deve spiegare quale app installa chi e le istruzioni chiave.
    assert "app figlio" in corpo.lower()
    assert "app genitore" in corpo.lower()
    assert "Play Protect" in corpo
    assert "Consenti impostazioni con limitazioni" in corpo
    # I pulsanti puntano ai due APK.
    assert "/scarica/pactum-figlio.apk" in corpo
    assert "/scarica/pactum-genitore.apk" in corpo


def test_scarica_senza_auth(client):
    assert client.get("/scarica").status_code == 200


# --- GET /scarica/pactum-*.apk ---


def test_download_apk_presente(client_apk, apk_dir):
    (apk_dir / "pactum-figlio.apk").write_bytes(b"PK\x03\x04 finto apk")
    r = client_apk.get("/scarica/pactum-figlio.apk")
    assert r.status_code == 200
    assert r.headers["content-type"] == "application/vnd.android.package-archive"
    assert "pactum-figlio.apk" in r.headers.get("content-disposition", "")
    assert r.content == b"PK\x03\x04 finto apk"


def test_download_apk_genitore_presente(client_apk, apk_dir):
    (apk_dir / "pactum-genitore.apk").write_bytes(b"contenuto")
    r = client_apk.get("/scarica/pactum-genitore.apk")
    assert r.status_code == 200
    assert r.headers["content-type"] == "application/vnd.android.package-archive"


def test_download_apk_mancante_404_gentile(client_apk):
    # Cartella APK vuota: il file non c'e' ancora.
    r = client_apk.get("/scarica/pactum-figlio.apk")
    assert r.status_code == 404
    assert r.headers["content-type"].startswith("text/html")
    assert "/scarica" in r.text  # rimanda alla pagina di download


def test_download_nome_sconosciuto_404(client_apk):
    r = client_apk.get("/scarica/qualcosa-altro.apk")
    assert r.status_code == 404
    assert r.headers["content-type"].startswith("text/html")


def test_nome_sconosciuto_nella_pagina_solo_come_testo(client_apk):
    """(v3.1) Il nome del file arriva dall'indirizzo, cioe' da chiunque: nella pagina
    404 finisce escapato, mai come HTML (niente script sul dominio del postino)."""
    r = client_apk.get("/scarica/%3Cimg%20src%3Dx%20onerror%3Dalert(1)%3E")
    assert r.status_code == 404
    assert r.headers["content-type"].startswith("text/html")
    assert "<img" not in r.text
    assert "&lt;img src=x onerror=alert(1)&gt;" in r.text
    r = client_apk.get("/scarica/%22%3E%3Csvg%20onload%3Dalert(1)%3E")
    assert r.status_code == 404
    assert "<svg" not in r.text and "&quot;&gt;&lt;svg onload=alert(1)&gt;" in r.text


def test_download_senza_auth(client_apk, apk_dir):
    (apk_dir / "pactum-figlio.apk").write_bytes(b"x")
    # Nessun header: il download e' pubblico.
    assert client_apk.get("/scarica/pactum-figlio.apk").status_code == 200


# --- manomissioni: vocabolario aperto, nessun cambio di schema ---


@pytest.mark.parametrize("sotto_tipo", ["permesso_revocato", "notifiche_disattivate"])
def test_manomissione_nuovo_sotto_tipo_affiora_nella_finestra(client, sotto_tipo):
    """L'app manda sotto_tipo che il server non conosce a priori: deve comunque
    finire nel registro, notificare il genitore e comparire in manomissioni_recenti
    con il sotto_tipo intatto — senza toccare lo schema."""
    evento_id = f"ev-{sotto_tipo}"
    r = client.post(
        "/api/eventi",
        json={"eventi": [{"id": evento_id, "tipo": "manomissione", "dettagli": {"sotto_tipo": sotto_tipo}}]},
        headers=FIGLIO,
    )
    assert r.status_code == 200, r.text
    assert r.json()["nuovi"] == 1

    finestra = client.get("/api/finestra", headers=GENITORE).json()
    mano = finestra["manomissioni_recenti"]
    assert [e["id"] for e in mano] == [evento_id]
    assert mano[0]["dettagli"]["sotto_tipo"] == sotto_tipo

    # E ha generato una notifica al genitore col sotto_tipo nel payload.
    notifiche = client.get("/api/notifiche", headers=GENITORE).json()["notifiche"]
    tipi = [(n["tipo"], n["payload"]["dettagli"].get("sotto_tipo")) for n in notifiche]
    assert ("manomissione", sotto_tipo) in tipi
