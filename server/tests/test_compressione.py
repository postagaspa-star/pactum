"""(v3.3) Risposte /api/ compresse per chi le chiede: l'app del genitore 0.9 chiede
le notifiche ogni minuto. Gli APK e lo zip di /scarica non si toccano: sono gia'
compressi e il telefono deve vedere la loro lunghezza vera."""

import pytest
from fastapi.testclient import TestClient

from conftest import FIGLIO, GENITORE, TOKEN_FIGLIO, TOKEN_GENITORE, crea_regola, fotografia_uso


@pytest.fixture
def client_apk(db_path, monkeypatch, orologio, tmp_path):
    cartella = tmp_path / "apk"
    cartella.mkdir()
    (cartella / "pactum-figlio.apk").write_bytes(b"PK" + b"\x00" * 3000)
    monkeypatch.setenv("PACTUM_DB", db_path)
    monkeypatch.setenv("PACTUM_TOKEN_FIGLIO", TOKEN_FIGLIO)
    monkeypatch.setenv("PACTUM_TOKEN_GENITORE", TOKEN_GENITORE)
    monkeypatch.setenv("PACTUM_APK_DIR", str(cartella))
    from app.main import create_app

    with TestClient(create_app()) as c:
        yield c


def _finestra_non_piccola(client):
    for i in range(3):
        crea_regola(client, parametri={"app_o_categoria": f"com.gioco.{i}", "minuti_al_giorno": 30 + i})
    fotografia_uso(client, "2026-07-14", "2026-07-15")


def test_finestra_compressa_se_il_telefono_la_chiede(client):
    _finestra_non_piccola(client)
    r = client.get("/api/finestra", headers={**GENITORE, "Accept-Encoding": "gzip"})
    assert r.status_code == 200, r.text
    assert r.headers.get("content-encoding") == "gzip"
    assert r.json()["regole"]  # il client la decomprime: il contenuto e' quello di sempre


def test_senza_richiesta_niente_compressione(client):
    _finestra_non_piccola(client)
    r = client.get("/api/finestra", headers={**GENITORE, "Accept-Encoding": "identity"})
    assert r.status_code == 200, r.text
    assert "content-encoding" not in r.headers


def test_le_risposte_piccole_restano_come_sono(client):
    r = client.get("/api/salute", headers={"Accept-Encoding": "gzip"})
    assert r.status_code == 200
    assert "content-encoding" not in r.headers


def test_gli_apk_non_si_comprimono(client_apk):
    r = client_apk.get("/scarica/pactum-figlio.apk", headers={"Accept-Encoding": "gzip"})
    assert r.status_code == 200
    assert "content-encoding" not in r.headers
    assert r.headers.get("content-length") == str(3002)


def test_le_notifiche_del_figlio_arrivano_uguali(client):
    crea_regola(client)
    r = client.get("/api/notifiche", headers={**FIGLIO, "Accept-Encoding": "gzip"})
    assert r.status_code == 200, r.text
    assert "notifiche" in r.json()
