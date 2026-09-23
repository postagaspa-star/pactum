"""(v3) La migrazione del database vero: dallo schema v2.4 (HEAD prima della v3,
tests/dati_v24.py) a famiglia/figli/dispositivi, al primo avvio, da sola.

- Gli STESSI NUMERI di prima: finestra, patto, regole, proposte, dichiarazioni e
  notifiche danno quello che dava il server v2.4 sullo stesso database alla stessa
  ora (tests/dati/v24_prima.json); la v3 puo' solo aggiungere campi.
- Niente si perde e niente si duplica: ogni riga di prima c'e' ancora, uguale, ed
  e' attaccata al figlio 1 / dispositivo 1 come dice il contratto.
- Riavviare non ripete niente; un errore a meta' lascia il database com'era.
- I token d'ambiente restano quelli delle app 0.7, salvati solo come hash."""

import json
import sqlite3
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

import dati_v24
from aiuti_v3 import auth, contenuto_in
from conftest import FIGLIO, GENITORE, TOKEN_FIGLIO, TOKEN_GENITORE, Orologio

PRIMA = json.loads((Path(__file__).parent / "dati" / "v24_prima.json").read_text(encoding="utf-8"))

TABELLE_V24 = [
    "battiti", "bonus", "dichiarazioni", "eventi", "notifiche", "patto", "proposte",
    "regole", "siti_giornalieri", "storico_modifiche", "uso_giornaliero",
]
TABELLE_V3 = ["figli", "dispositivi", "credenziali", "codici_abbinamento", "tentativi_abbinamento"]


@pytest.fixture
def orologio(monkeypatch):
    """Lo stesso orologio di conftest, ma fermo all'ora in cui la v2.4 ha dato i numeri."""
    from app import clock

    o = Orologio(dati_v24.ORA_V24)
    monkeypatch.setattr(clock, "now", lambda: o.corrente)
    return o


@pytest.fixture
def db_v24(tmp_path):
    path = str(tmp_path / "nas-v24.db")
    dati_v24.crea_db_v24(path)
    return path


@pytest.fixture
def avvia(monkeypatch, orologio, db_v24):
    """Avvia il server v3 sul database v2.4 (la prima volta lo migra)."""

    def _avvia(token_figlio=TOKEN_FIGLIO, token_genitore=TOKEN_GENITORE):
        monkeypatch.setenv("PACTUM_DB", db_v24)
        monkeypatch.setenv("PACTUM_TOKEN_FIGLIO", token_figlio)
        monkeypatch.setenv("PACTUM_TOKEN_GENITORE", token_genitore)
        from app.main import create_app

        return TestClient(create_app())

    return _avvia


def _get(client, percorso, headers):
    risposta = client.get(percorso, headers=headers)
    assert risposta.status_code == 200, risposta.text
    return risposta.json()


def _colonne(path, tabella):
    conn = sqlite3.connect(path)
    try:
        return {r[1] for r in conn.execute(f"PRAGMA table_info({tabella})")}
    finally:
        conn.close()


def _senza(riga: dict, *colonne) -> dict:
    return {k: v for k, v in riga.items() if k not in colonne}


# --- gli stessi numeri di prima ---

def test_dopo_la_migrazione_gli_stessi_numeri_di_prima(avvia):
    with avvia() as c:
        dopo = {
            "finestra": _get(c, "/api/finestra", GENITORE),
            "patto": _get(c, "/api/patto", FIGLIO),
            "regole_figlio": _get(c, "/api/regole", FIGLIO),
            "regole_genitore": _get(c, "/api/regole", GENITORE),
            "proposte_figlio": _get(c, "/api/proposte", FIGLIO),
            "proposte_genitore": _get(c, "/api/proposte", GENITORE),
            "dichiarazioni_figlio": _get(c, "/api/dichiarazioni", FIGLIO),
            "dichiarazioni_genitore": _get(c, "/api/dichiarazioni", GENITORE),
            "notifiche_figlio": _get(c, "/api/notifiche", FIGLIO),
            "notifiche_genitore": _get(c, "/api/notifiche", GENITORE),
        }
        esplicito = _get(c, "/api/finestra?figlio_id=1", GENITORE)
    assert set(dopo) == set(PRIMA)
    for chiave in PRIMA:
        contenuto_in(PRIMA[chiave], dopo[chiave], chiave)
    assert esplicito == dopo["finestra"]  # senza figlio_id = il primo figlio

    # I numeri principali anche per esteso, cosi' il test si legge da solo.
    finestra = dopo["finestra"]
    assert [v["stato"] for v in finestra["striscia"]] == [
        "rosso", "rosso", "rosso", "grigio", "rosso", "rosso", "rosso", "verde",
    ]
    assert finestra["riepilogo"] == {"giorni_fuori_regola": 6, "interruzioni": 2}
    assert finestra["medie"] == {
        "settimana": {"minuti": 92, "giorni": 6}, "mese": {"minuti": 100, "giorni": 11},
    }
    assert finestra["bonus"]["giorno"] == {"usati": 20, "tetto": 30, "residui": 10}
    assert finestra["bonus"]["settimana"] == {"usati": 35, "tetto": 90, "residui": 55}
    assert [v["minuti"] for v in finestra["bonus_giornalieri"]] == [30, 0, 0, 0, 0, 0, 15, 20]
    assert [v["totale_minuti"] for v in finestra["uso_recente"]] == [110, 140, 60, None, 0, 130, 175, 45]
    assert finestra["segno_oggi"] is True
    patto = dopo["patto"]
    assert [r["id"] for r in patto["regole"]] == [1, 2, 3, 5]
    assert patto["bonus_oggi_per_regola"] == {"1": 5, "5": 15}
    assert patto["striscia"] == finestra["striscia"]


def test_la_v3_aggiunge_il_figlio_1_e_il_telefono(avvia):
    with avvia() as c:
        finestra = _get(c, "/api/finestra", GENITORE)
        patto = _get(c, "/api/patto", FIGLIO)
        famiglia = _get(c, "/api/famiglia", GENITORE)
    telefono = {"id": 1, "nome": "Telefono", "tipo": "telefono"}
    assert patto["figlio"] == {"id": 1, "nome": "Figlio"}
    assert patto["dispositivo"] == telefono
    assert patto["striscia_dispositivo"] == [
        {"data": v["data"], "stato": "grigio" if v["data"] == "2026-09-19" else "rosso"
         if v["data"] in ("2026-09-16", "2026-09-17", "2026-09-21", "2026-09-22") else "verde"}
        for v in patto["striscia"]
    ]  # senza la vita reale, che e' del figlio: il 18 e il 20 sul telefono erano verdi
    regole = {r["id"]: r for r in finestra["regole"]}
    assert regole[1]["dispositivo"] == telefono and regole[1]["dispositivo_id"] == 1
    assert regole[3]["dispositivo"] is None and regole[3]["dispositivo_id"] is None  # vita reale
    assert all(r["figlio_id"] == 1 for r in finestra["regole"])
    assert all(e["dispositivo_id"] == 1 for e in finestra["sforamenti_recenti"])
    (unico,) = finestra["dispositivi"]
    assert unico["id"] == 1 and unico["abbinato"] is True and unico["revocato"] is False
    for campo in ("uso_recente", "siti_recenti", "medie", "bonus", "bonus_giornalieri", "stato_silenzio"):
        assert unico[campo] == finestra[campo], campo  # primo livello = primo dispositivo
    (figlio,) = famiglia["figli"]
    assert (figlio["id"], figlio["nome"]) == (1, "Figlio")
    assert figlio["striscia"] == finestra["striscia"]
    assert figlio["notifiche_non_lette"] == 5  # quelle del genitore ancora da leggere
    assert figlio["dispositivi"][0]["versione_app"] == "0.7.0"  # dall'ultimo battito


# --- niente si perde, niente si duplica ---

def test_ogni_riga_di_prima_resta_uguale_e_va_al_figlio_1(avvia, db_v24):
    prima = dati_v24.righe(db_v24)
    with avvia():
        pass
    dopo = dati_v24.righe(db_v24)

    for tabella in TABELLE_V24:
        assert len(dopo[tabella]) == len(prima[tabella]), tabella
        nuove = {"figlio_id", "dispositivo_id"}
        vecchie = [_senza(r, *nuove) for r in prima[tabella]]
        rimaste = [_senza(r, *nuove) for r in dopo[tabella]]
        if tabella in ("uso_giornaliero", "siti_giornalieri"):  # ricostruite: conta il contenuto
            vecchie.sort(key=lambda r: r["giorno"])
            rimaste.sort(key=lambda r: r["giorno"])
        assert rimaste == vecchie, tabella

    assert [(r["id"], r["nome"]) for r in dopo["figli"]] == [(1, "Figlio")]
    (telefono,) = dopo["dispositivi"]
    assert (telefono["id"], telefono["figlio_id"], telefono["nome"], telefono["tipo"]) == (
        1, 1, "Telefono", "telefono",
    )
    assert telefono["abbinato_ts"] is not None and telefono["revocato_ts"] is None

    # vita_reale al figlio senza dispositivo, tutto il resto al telefono
    for r in dopo["regole"]:
        assert r["figlio_id"] == 1
        assert r["dispositivo_id"] == (None if r["tipo"] == "vita_reale" else 1), r
    for tabella in ("eventi", "battiti", "bonus", "uso_giornaliero", "siti_giornalieri"):
        assert {r["dispositivo_id"] for r in dopo[tabella]} == {1}, tabella
    # notifiche al figlio 1, col dispositivo che avrebbero avuto nascendo in v3
    assert {r["figlio_id"] for r in dopo["notifiche"]} == {1}
    assert {r["id"]: r["dispositivo_id"] for r in dopo["notifiche"]} == {
        1: 1, 2: 1, 3: 1, 4: 1, 5: 1, 6: None, 7: 1, 8: 1, 9: 1, 10: None, 11: None,
    }
    for tabella in TABELLE_V3:
        assert tabella in dopo


def test_le_fotografie_hanno_la_chiave_per_dispositivo(avvia, db_v24):
    assert "dispositivo_id" not in _colonne(db_v24, "uso_giornaliero")
    with avvia():
        pass
    conn = sqlite3.connect(db_v24)
    try:
        for tabella in ("uso_giornaliero", "siti_giornalieri"):
            chiave = [r[1] for r in conn.execute(f"PRAGMA table_info({tabella})") if r[5]]
            assert chiave == ["dispositivo_id", "giorno"], tabella
    finally:
        conn.close()


def test_riavviare_non_ripete_la_migrazione(avvia, db_v24):
    with avvia():
        pass
    dopo_il_primo = dati_v24.righe(db_v24)
    with avvia():
        pass
    with avvia():
        pass
    assert dati_v24.righe(db_v24) == dopo_il_primo
    assert len(dopo_il_primo["credenziali"]) == 2  # genitore 1 e telefono


def test_un_errore_a_meta_lascia_il_database_com_era(db_v24, monkeypatch, orologio):
    from app import db

    prima = dati_v24.righe(db_v24)

    def guasto(*_):
        raise RuntimeError("corrente saltata a meta' migrazione")

    monkeypatch.setattr(db, "_attacca_dati_esistenti", guasto)
    with pytest.raises(RuntimeError):
        db.init_db(db_v24, 30, 90, TOKEN_FIGLIO, TOKEN_GENITORE)
    dopo = dati_v24.righe(db_v24)
    for tabella in TABELLE_V24:
        assert dopo[tabella] == prima[tabella], tabella
    assert "figlio_id" not in _colonne(db_v24, "regole")
    assert "dispositivo_id" not in _colonne(db_v24, "uso_giornaliero")
    assert dopo["figli"] == [] and dopo["dispositivi"] == []

    monkeypatch.undo()  # la corrente torna: il prossimo avvio migra da capo
    from app import clock

    monkeypatch.setattr(clock, "now", lambda: dati_v24.ORA_V24)
    db.init_db(db_v24, 30, 90, TOKEN_FIGLIO, TOKEN_GENITORE)
    assert [r["id"] for r in dati_v24.righe(db_v24)["figli"]] == [1]


def test_migrazione_senza_token_poi_avvio_col_token(db_v24, monkeypatch, orologio, avvia):
    """init_db senza token (solo lo schema) crea gia' figlio e telefono, perche' c'e'
    una storia; il primo avvio vero gli da' il token d'ambiente."""
    from app import db

    db.init_db(db_v24, 30, 90)
    assert dati_v24.righe(db_v24)["credenziali"] == []
    with avvia() as c:
        assert c.get("/api/patto", headers=FIGLIO).status_code == 200
        assert c.get("/api/finestra", headers=GENITORE).status_code == 200


# --- i token d'ambiente: le app 0.7 continuano a funzionare ---

def test_i_token_si_salvano_solo_come_hash(avvia, db_v24):
    with avvia() as c:
        assert c.get("/api/patto", headers=FIGLIO).status_code == 200
    contenuto = Path(db_v24).read_bytes()
    assert TOKEN_FIGLIO.encode() not in contenuto
    assert TOKEN_GENITORE.encode() not in contenuto


def test_cambiare_i_token_nel_env_funziona_come_prima(avvia):
    with avvia():
        pass
    with avvia(token_figlio="figlio-nuovo-dal-env", token_genitore="genitore-nuovo-dal-env") as c:
        assert c.get("/api/patto", headers=FIGLIO).status_code == 401
        assert c.get("/api/finestra", headers=GENITORE).status_code == 401
        assert c.get("/api/patto", headers=auth("figlio-nuovo-dal-env")).status_code == 200
        assert c.get("/api/finestra", headers=auth("genitore-nuovo-dal-env")).status_code == 200


def test_telefono_riabbinato_il_token_d_ambiente_non_torna(avvia):
    with avvia() as c:
        codice = c.post("/api/dispositivi/1/codice", headers=GENITORE).json()["codice"]
        assert c.get("/api/patto", headers=FIGLIO).status_code == 200  # fino all'abbinamento vale
        token = c.post("/api/abbina", json={"codice": codice}).json()["token"]
        assert c.get("/api/patto", headers=FIGLIO).status_code == 401
        assert c.get("/api/patto", headers=auth(token)).json()["dispositivo"]["id"] == 1
    with avvia() as c:  # riavvio: il vecchio token d'ambiente resta morto
        assert c.get("/api/patto", headers=FIGLIO).status_code == 401
        assert c.get("/api/patto", headers=auth(token)).status_code == 200


def test_telefono_revocato_il_token_d_ambiente_non_torna(avvia):
    with avvia() as c:
        assert c.delete("/api/dispositivi/1", headers=GENITORE).status_code == 200
        assert c.get("/api/patto", headers=FIGLIO).status_code == 401
    with avvia() as c:
        assert c.get("/api/patto", headers=FIGLIO).status_code == 401
        assert c.get("/api/finestra", headers=GENITORE).status_code == 200  # la storia resta


def test_primo_avvio_su_database_vuoto(client):
    """Installazione nuova: genitore 1 dal token d'ambiente, e il figlio 1 col suo
    telefono sul token del figlio, come nella migrazione."""
    famiglia = client.get("/api/famiglia", headers=GENITORE).json()
    (figlio,) = famiglia["figli"]
    assert figlio["nome"] == "Figlio"
    assert [(d["id"], d["nome"], d["tipo"], d["abbinato"]) for d in figlio["dispositivi"]] == [
        (1, "Telefono", "telefono", True),
    ]
    assert client.get("/api/patto", headers=FIGLIO).json()["dispositivo"]["id"] == 1
