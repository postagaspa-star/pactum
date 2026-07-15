"""Micro-migrazioni per i database nati con lo schema v1: proposte ricostruita
(vocabolario stato in_attesa->pendente + colonne nuove), bonus.regola_id aggiunta,
notifiche.destinatario aggiunta col backfill a 'genitore'. Un DB fresco non passa
di qui (SCHEMA lo crea gia' v2): qui si simula il vecchio a mano."""

import sqlite3

from app import db

SCHEMA_V1 = """
CREATE TABLE regole (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    tipo TEXT NOT NULL,
    parametri TEXT NOT NULL,
    attiva INTEGER NOT NULL DEFAULT 1,
    creata_ts TEXT NOT NULL,
    ultima_modifica_ts TEXT NOT NULL
);
CREATE TABLE proposte (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    regola_id INTEGER NOT NULL REFERENCES regole(id),
    parametri_proposti TEXT,
    motivazione TEXT,
    stato TEXT NOT NULL DEFAULT 'in_attesa' CHECK (stato IN ('in_attesa', 'accettata', 'rifiutata')),
    usata INTEGER NOT NULL DEFAULT 0,
    ts_server TEXT NOT NULL
);
CREATE TABLE bonus (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    minuti INTEGER NOT NULL,
    motivo TEXT,
    ts_server TEXT NOT NULL
);
CREATE TABLE notifiche (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    tipo TEXT NOT NULL,
    messaggio TEXT NOT NULL,
    payload TEXT NOT NULL DEFAULT '{}',
    letta INTEGER NOT NULL DEFAULT 0,
    ts_server TEXT NOT NULL
);
"""

TS = "2026-07-14T09:00:00+00:00"


def _db_v1(path):
    conn = sqlite3.connect(path)
    conn.executescript(SCHEMA_V1)
    conn.execute(
        "INSERT INTO regole (id, tipo, parametri, attiva, creata_ts, ultima_modifica_ts)"
        " VALUES (1, 'limite_tempo', '{\"app_o_categoria\": \"TikTok\", \"minuti_al_giorno\": 60}', 1, ?, ?)",
        (TS, TS),
    )
    conn.execute(
        "INSERT INTO proposte (id, regola_id, parametri_proposti, motivazione, stato, usata, ts_server)"
        " VALUES (7, 1, '{\"app_o_categoria\": \"TikTok\", \"minuti_al_giorno\": 30}', 'meno tempo', 'in_attesa', 0, ?)",
        (TS,),
    )
    conn.execute("INSERT INTO bonus (minuti, motivo, ts_server) VALUES (15, 'vecchio', ?)", (TS,))
    conn.execute(
        "INSERT INTO notifiche (tipo, messaggio, payload, ts_server) VALUES ('bonus', 'vecchia', '{}', ?)",
        (TS,),
    )
    conn.commit()
    conn.close()


def _colonne(conn, tabella):
    return {r[1] for r in conn.execute(f"PRAGMA table_info({tabella})")}


def test_migrazione_da_v1(tmp_path):
    path = str(tmp_path / "vecchio.db")
    _db_v1(path)

    db.init_db(path, 30, 90)

    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row

    # proposte: ricostruita con le colonne nuove e lo stato tradotto
    assert {"confronto", "direzione", "risposta_esito", "risposta_motivazione", "risposta_ts"} <= _colonne(conn, "proposte")
    proposta = conn.execute("SELECT * FROM proposte WHERE id = 7").fetchone()
    assert proposta["stato"] == "pendente"  # in_attesa -> pendente
    assert proposta["regola_id"] == 1
    assert proposta["motivazione"] == "meno tempo"  # i dati sopravvivono
    assert proposta["confronto"] is None

    # il nuovo vocabolario stato e' davvero in vigore (il vecchio CHECK e' sparito)
    conn.execute(
        "INSERT INTO proposte (regola_id, stato, usata, ts_server) VALUES (1, 'pendente', 0, ?)",
        (TS,),
    )

    # bonus: colonna regola_id aggiunta, riga v1 senza aggancio
    assert "regola_id" in _colonne(conn, "bonus")
    assert conn.execute("SELECT regola_id FROM bonus").fetchone()["regola_id"] is None

    # notifiche: destinatario aggiunto e backfillato a 'genitore'
    assert "destinatario" in _colonne(conn, "notifiche")
    assert conn.execute("SELECT destinatario FROM notifiche").fetchone()["destinatario"] == "genitore"

    # e le tabelle nuove della v2 esistono
    nomi = {r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    assert "dichiarazioni" in nomi
    conn.close()


def test_init_idempotente_su_db_gia_v2(tmp_path):
    """Rilanciare init_db su un DB gia' migrato non deve rompere nulla."""
    path = str(tmp_path / "gia-v2.db")
    db.init_db(path, 30, 90)
    db.init_db(path, 30, 90)  # secondo giro: nessun errore
    conn = sqlite3.connect(path)
    assert "confronto" in _colonne(conn, "proposte")
    conn.close()
