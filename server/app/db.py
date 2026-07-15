"""Persistenza SQLite: una connessione per richiesta, schema creato all'avvio.
Lo storico e gli eventi non si cancellano mai (il registro e' il prodotto):
le regole eliminate diventano attiva=0."""

import json
import sqlite3
from datetime import datetime, timedelta, timezone

from fastapi import Request

from . import clock, config

SCHEMA = """
CREATE TABLE IF NOT EXISTS patto (
    chiave TEXT PRIMARY KEY,
    valore TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS regole (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    tipo TEXT NOT NULL CHECK (tipo IN ('limite_tempo', 'fascia_oraria', 'vita_reale')),
    parametri TEXT NOT NULL,
    attiva INTEGER NOT NULL DEFAULT 1,
    creata_ts TEXT NOT NULL,
    ultima_modifica_ts TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS storico_modifiche (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    regola_id INTEGER NOT NULL REFERENCES regole(id),
    azione TEXT NOT NULL CHECK (azione IN ('creazione', 'modifica', 'eliminazione')),
    direzione TEXT CHECK (direzione IN ('allenta', 'stringe')),
    parametri_prima TEXT,
    parametri_dopo TEXT,
    concordata INTEGER NOT NULL DEFAULT 0,
    ts_server TEXT NOT NULL
);

-- Stub: il flusso completo delle proposte arriva alla tappa 5.
-- Serve gia' adesso perche' concordata=true e' legittimo solo se nasce
-- da una proposta accettata, mai da un campo libero del client.
-- parametri_proposti (JSON) sono i parametri ESATTI concordati: la modifica
-- concordata li applica tali e quali (il figlio non puo' cambiarli al volo).
-- Per una proposta di ELIMINAZIONE il valore e' il marcatore {"azione": "elimina"}
-- (gli endpoint che creano le proposte arrivano alla tappa 5).
CREATE TABLE IF NOT EXISTS proposte (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    regola_id INTEGER NOT NULL REFERENCES regole(id),
    parametri_proposti TEXT,
    motivazione TEXT,
    stato TEXT NOT NULL DEFAULT 'in_attesa' CHECK (stato IN ('in_attesa', 'accettata', 'rifiutata')),
    usata INTEGER NOT NULL DEFAULT 0,
    ts_server TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS eventi (
    id TEXT PRIMARY KEY,
    tipo TEXT NOT NULL,
    dettagli TEXT NOT NULL DEFAULT '{}',
    ts_device INTEGER,
    ts_server TEXT NOT NULL
);

-- uso_giornaliero e' una fotografia CUMULATIVA del giorno (contratto-api.md):
-- il registro eventi conserva ogni fotografia ricevuta, ma la verita' sull'uso
-- di un giorno e' custodita qui ed e' MONOTONA su totale_minuti: una fotografia
-- sostituisce la vigente solo se il suo totale non regredisce (una consegna in
-- ritardo di una fotografia piu' vecchia/bassa non cancella quella piu' alta).
CREATE TABLE IF NOT EXISTS uso_giornaliero (
    giorno TEXT PRIMARY KEY,
    dettagli TEXT NOT NULL,
    evento_id TEXT NOT NULL REFERENCES eventi(id),
    ts_server TEXT NOT NULL,
    totale_minuti INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS battiti (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    batteria INTEGER,
    versione_app TEXT,
    elapsed_realtime INTEGER,
    ts_device INTEGER,
    ts_server TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS bonus (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    minuti INTEGER NOT NULL,
    motivo TEXT,
    ts_server TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS notifiche (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    tipo TEXT NOT NULL,
    messaggio TEXT NOT NULL,
    payload TEXT NOT NULL DEFAULT '{}',
    letta INTEGER NOT NULL DEFAULT 0,
    ts_server TEXT NOT NULL
);
"""


def connetti(db_path: str) -> sqlite3.Connection:
    # check_same_thread=False: la connessione vive dentro una singola richiesta,
    # ma FastAPI puo' spostare dependency e handler su thread diversi del pool.
    conn = sqlite3.connect(db_path, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    return conn


def _migra(conn: sqlite3.Connection) -> None:
    """Micro-migrazioni per database creati con schemi precedenti."""
    colonne = {r[1] for r in conn.execute("PRAGMA table_info(uso_giornaliero)")}
    if "totale_minuti" not in colonne:
        conn.execute(
            "ALTER TABLE uso_giornaliero ADD COLUMN totale_minuti INTEGER NOT NULL DEFAULT 0"
        )


def init_db(db_path: str, tetto_giorno: int, tetto_settimana: int) -> None:
    conn = connetti(db_path)
    try:
        conn.executescript(SCHEMA)
        _migra(conn)
        conn.execute(
            "INSERT OR IGNORE INTO patto (chiave, valore) VALUES ('tetto_bonus_giorno', ?)",
            (str(tetto_giorno),),
        )
        conn.execute(
            "INSERT OR IGNORE INTO patto (chiave, valore) VALUES ('tetto_bonus_settimana', ?)",
            (str(tetto_settimana),),
        )
        conn.commit()
    finally:
        conn.close()


def get_conn(request: Request):
    conn = connetti(request.app.state.settings.db_path)
    try:
        yield conn
    finally:
        conn.close()


def valore_patto(conn: sqlite3.Connection, chiave: str) -> str:
    riga = conn.execute("SELECT valore FROM patto WHERE chiave = ?", (chiave,)).fetchone()
    if riga is None:
        raise KeyError(chiave)
    return riga["valore"]


def accoda_notifica(conn: sqlite3.Connection, tipo: str, messaggio: str, payload: dict, ts: str) -> None:
    conn.execute(
        "INSERT INTO notifiche (tipo, messaggio, payload, ts_server) VALUES (?, ?, ?, ?)",
        (tipo, messaggio, json.dumps(payload), ts),
    )


def registra_modifica(
    conn: sqlite3.Connection,
    regola_id: int,
    azione: str,
    direzione: str | None,
    prima: dict | None,
    dopo: dict | None,
    concordata: bool,
    ts: str,
) -> None:
    conn.execute(
        "INSERT INTO storico_modifiche"
        " (regola_id, azione, direzione, parametri_prima, parametri_dopo, concordata, ts_server)"
        " VALUES (?, ?, ?, ?, ?, ?, ?)",
        (
            regola_id,
            azione,
            direzione,
            json.dumps(prima) if prima is not None else None,
            json.dumps(dopo) if dopo is not None else None,
            int(concordata),
            ts,
        ),
    )


def stato_bonus(conn: sqlite3.Connection, ora: datetime) -> dict:
    """Contatori bonus del giorno e della settimana ISO (lunedi'-domenica).
    I confini dei bucket sono nel fuso del patto (config.fuso_patto);
    il confronto avviene sui ts_server UTC."""
    tetto_giorno = int(valore_patto(conn, "tetto_bonus_giorno"))
    tetto_settimana = int(valore_patto(conn, "tetto_bonus_settimana"))
    ora_locale = ora.astimezone(config.fuso_patto())
    inizio_giorno_locale = ora_locale.replace(hour=0, minute=0, second=0, microsecond=0)
    inizio_settimana_locale = inizio_giorno_locale - timedelta(days=ora_locale.weekday())
    inizio_giorno = inizio_giorno_locale.astimezone(timezone.utc)
    inizio_settimana = inizio_settimana_locale.astimezone(timezone.utc)

    def usati_da(inizio: datetime) -> int:
        riga = conn.execute(
            "SELECT COALESCE(SUM(minuti), 0) AS totale FROM bonus WHERE ts_server >= ?",
            (clock.iso(inizio),),
        ).fetchone()
        return riga["totale"]

    usati_giorno = usati_da(inizio_giorno)
    usati_settimana = usati_da(inizio_settimana)
    return {
        "giorno": {
            "usati": usati_giorno,
            "tetto": tetto_giorno,
            "residui": tetto_giorno - usati_giorno,
        },
        "settimana": {
            "usati": usati_settimana,
            "tetto": tetto_settimana,
            "residui": tetto_settimana - usati_settimana,
        },
    }
