"""Hardening per il deploy (Cloudflare Tunnel + NAS): in PACTUM_ENV=prod il
postino non deve MAI partire con token deboli o di default, e il log/salute non
devono mai esporre i token. In dev tutto resta permissivo (test e sviluppo)."""

import secrets

import pytest
from fastapi.testclient import TestClient

from app.config import (
    TOKEN_FIGLIO_DEV,
    TOKEN_GENITORE_DEV,
    Settings,
    carica_settings,
    riassunto_config,
)


def _token_forte() -> str:
    # token_urlsafe(32) -> ~43 caratteri, ben oltre la soglia di 24.
    return secrets.token_urlsafe(32)


def _prod_env(monkeypatch, db_path, token_figlio, token_genitore):
    monkeypatch.setenv("PACTUM_ENV", "prod")
    monkeypatch.setenv("PACTUM_DB", db_path)
    monkeypatch.setenv("PACTUM_TOKEN_FIGLIO", token_figlio)
    monkeypatch.setenv("PACTUM_TOKEN_GENITORE", token_genitore)


def test_prod_con_token_dev_rifiuta_avvio(monkeypatch, db_path):
    """prod + token di sviluppo -> create_app() solleva RuntimeError."""
    _prod_env(monkeypatch, db_path, TOKEN_FIGLIO_DEV, TOKEN_GENITORE_DEV)
    from app.main import create_app

    with pytest.raises(RuntimeError) as errore:
        create_app()
    assert "PACTUM_ENV=prod" in str(errore.value)


def test_prod_con_token_corti_rifiuta_avvio(monkeypatch, db_path):
    """prod + token piu' corti di 24 caratteri -> RuntimeError."""
    _prod_env(monkeypatch, db_path, "corto-figlio", "corto-genitore")
    from app.main import create_app

    with pytest.raises(RuntimeError):
        create_app()


def test_prod_con_token_uguali_rifiuta_avvio(monkeypatch, db_path):
    """prod + due token forti ma IDENTICI -> RuntimeError."""
    stesso = _token_forte()
    _prod_env(monkeypatch, db_path, stesso, stesso)
    from app.main import create_app

    with pytest.raises(RuntimeError):
        create_app()


def test_prod_con_token_forti_distinti_parte(monkeypatch, db_path):
    """prod + token forti e distinti -> parte; /api/salute dice env=prod, db_ok."""
    _prod_env(monkeypatch, db_path, _token_forte(), _token_forte())
    from app.main import create_app

    with TestClient(create_app()) as c:
        dati = c.get("/api/salute").json()
    assert dati["stato"] == "ok"
    assert dati["env"] == "prod"
    assert dati["db_ok"] is True


def test_dev_parte_con_i_default(monkeypatch, db_path):
    """Senza PACTUM_ENV (dev) i token di default vanno bene: nessun errore."""
    monkeypatch.delenv("PACTUM_ENV", raising=False)
    monkeypatch.setenv("PACTUM_DB", db_path)
    monkeypatch.delenv("PACTUM_TOKEN_FIGLIO", raising=False)
    monkeypatch.delenv("PACTUM_TOKEN_GENITORE", raising=False)
    from app.main import create_app

    with TestClient(create_app()) as c:
        dati = c.get("/api/salute").json()
    assert dati["env"] == "dev"
    assert dati["db_ok"] is True


def test_riassunto_config_non_espone_i_token():
    """Il riassunto per il log descrive lo stato dei token, mai il valore."""
    settings = Settings(
        env="prod",
        token_figlio="figlio-segretissimo-che-non-deve-comparire",
        token_genitore="genitore-segretissimo-che-non-deve-comparire",
        db_path="/data/pactum.db",
        versioni_path="/app/versioni.json",
        apk_dir="/apk",
    )
    riga = riassunto_config(settings)
    assert "segretissimo" not in riga
    assert "token_figlio=forte" in riga
    assert "token_genitore=forte" in riga
    assert "env=prod" in riga


def test_carica_settings_default_env_e_dev():
    """Senza PACTUM_ENV l'ambiente e' 'dev'."""
    import os

    if "PACTUM_ENV" in os.environ:
        pytest.skip("PACTUM_ENV impostato nell'ambiente di test")
    assert carica_settings().env == "dev"
