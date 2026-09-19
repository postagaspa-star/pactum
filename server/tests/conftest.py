import json
import sqlite3
import sys
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

TOKEN_FIGLIO = "tok-figlio-test"
TOKEN_GENITORE = "tok-genitore-test"
FIGLIO = {"Authorization": f"Bearer {TOKEN_FIGLIO}"}
GENITORE = {"Authorization": f"Bearer {TOKEN_GENITORE}"}

# Martedi' 14 luglio 2026, 10:00 UTC.
ORA_INIZIALE = datetime(2026, 7, 14, 10, 0, 0, tzinfo=timezone.utc)


class Orologio:
    def __init__(self, iniziale: datetime):
        self.corrente = iniziale

    def avanza(self, **kwargs):
        self.corrente += timedelta(**kwargs)

    def vai_a(self, dt: datetime):
        assert dt >= self.corrente, "l'orologio dei test non torna indietro"
        self.corrente = dt


@pytest.fixture
def orologio(monkeypatch):
    from app import clock

    o = Orologio(ORA_INIZIALE)
    monkeypatch.setattr(clock, "now", lambda: o.corrente)
    return o


@pytest.fixture
def db_path(tmp_path):
    return str(tmp_path / "pactum-test.db")


@pytest.fixture
def client(db_path, monkeypatch, orologio):
    monkeypatch.setenv("PACTUM_DB", db_path)
    monkeypatch.setenv("PACTUM_TOKEN_FIGLIO", TOKEN_FIGLIO)
    monkeypatch.setenv("PACTUM_TOKEN_GENITORE", TOKEN_GENITORE)
    from app.main import create_app

    with TestClient(create_app()) as c:
        yield c


def crea_regola(client, tipo="limite_tempo", parametri=None):
    if parametri is None:
        parametri = {"app_o_categoria": "TikTok", "minuti_al_giorno": 60}
    risposta = client.post(
        "/api/regole", json={"tipo": tipo, "parametri": parametri}, headers=FIGLIO
    )
    assert risposta.status_code == 201, risposta.text
    return risposta.json()


def fotografia_uso(client, *giorni, totale_minuti=30):
    """(v2.4) Una fotografia uso_giornaliero per ciascun giorno. Serve al semaforo:
    un giorno senza sforamenti e' verde SOLO se il telefono ha raccontato qualcosa
    (niente verde senza dati), altrimenti e' grigio."""
    eventi = [
        {
            "id": f"uso-{giorno}-{uuid.uuid4().hex[:8]}",
            "tipo": "uso_giornaliero",
            "dettagli": {"giorno": giorno, "uso_minuti": {}, "totale_minuti": totale_minuti},
        }
        for giorno in giorni
    ]
    risposta = client.post("/api/eventi", json={"eventi": eventi}, headers=FIGLIO)
    assert risposta.status_code == 200, risposta.text


def inserisci_proposta(db_path, regola_id, stato="accettata", parametri=None, usata=0):
    """Il flusso proposte arriva alla tappa 5: nei test la riga si inserisce a mano."""
    conn = sqlite3.connect(db_path)
    cursore = conn.execute(
        "INSERT INTO proposte (regola_id, parametri_proposti, motivazione, stato, usata, ts_server)"
        " VALUES (?, ?, ?, ?, ?, ?)",
        (
            regola_id,
            json.dumps(parametri) if parametri is not None else None,
            "proposta di test",
            stato,
            usata,
            "2026-07-14T09:00:00+00:00",
        ),
    )
    conn.commit()
    proposta_id = cursore.lastrowid
    conn.close()
    return proposta_id
