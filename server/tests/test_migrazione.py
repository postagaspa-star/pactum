"""Micro-migrazioni per i database nati con lo schema v1: proposte ricostruita
(vocabolario stato in_attesa->pendente + colonne nuove), bonus.regola_id aggiunta,
notifiche.destinatario aggiunta col backfill a 'genitore'. Un DB fresco non passa
di qui (SCHEMA lo crea gia' v2): qui si simula il vecchio a mano."""

import sqlite3

import pytest

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


# --- v2 -> v2.1: 'annullata' nel CHECK proposte, arbitro_nome + indice UNIQUE dichiarazioni ---

SCHEMA_V2_PRE21 = """
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
    confronto TEXT,
    direzione TEXT,
    stato TEXT NOT NULL DEFAULT 'pendente' CHECK (stato IN ('pendente', 'accettata', 'rifiutata')),
    usata INTEGER NOT NULL DEFAULT 0,
    risposta_esito TEXT,
    risposta_motivazione TEXT,
    risposta_ts TEXT,
    ts_server TEXT NOT NULL
);
CREATE TABLE dichiarazioni (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    regola_id INTEGER NOT NULL REFERENCES regole(id),
    giorno TEXT NOT NULL,
    esito TEXT NOT NULL CHECK (esito IN ('successo', 'fallimento')),
    nota TEXT,
    stato TEXT NOT NULL CHECK (stato IN ('registrata', 'in_attesa', 'confermata', 'confermata_per_conto', 'ribaltata')),
    verdetto_verdetto TEXT,
    verdetto_nota TEXT,
    verdetto_registro TEXT,
    verdetto_ts TEXT,
    ts_server TEXT NOT NULL
);
"""


def _db_v2_pre21(path):
    conn = sqlite3.connect(path)
    conn.executescript(SCHEMA_V2_PRE21)
    conn.execute(
        "INSERT INTO regole (id, tipo, parametri, attiva, creata_ts, ultima_modifica_ts)"
        " VALUES (1, 'vita_reale',"
        " '{\"descrizione\": \"Cammino\", \"arbitro_nome\": \"Mamma\", \"frequenza\": \"ogni giorno\"}',"
        " 1, ?, ?)",
        (TS, TS),
    )
    conn.execute(
        "INSERT INTO proposte (id, regola_id, parametri_proposti, stato, usata, ts_server)"
        " VALUES (5, 1, '{\"azione\": \"elimina\"}', 'pendente', 0, ?)",
        (TS,),
    )
    conn.execute(
        "INSERT INTO dichiarazioni (id, regola_id, giorno, esito, stato, ts_server)"
        " VALUES (9, 1, '2026-07-10', 'fallimento', 'registrata', ?)",
        (TS,),
    )
    conn.commit()
    conn.close()


def test_migrazione_siti_giornalieri_su_db_esistente(tmp_path):
    """v2.2 -> v2.3: un database esistente (senza i siti) guadagna la tabella
    siti_giornalieri completa, senza toccare i dati che c'erano gia'."""
    path = str(tmp_path / "senza-siti.db")
    _db_v1(path)
    conn = sqlite3.connect(path)
    nomi = {r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    assert "siti_giornalieri" not in nomi  # il vecchio non ne sapeva niente
    conn.close()

    db.init_db(path, 30, 90)

    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    assert _colonne(conn, "siti_giornalieri") == {
        "giorno", "dettagli", "evento_id", "ts_server",
        "totale_domini", "totale_visite", "dns_cifrato",
    }
    # nessun dato inventato: i siti non esistevano prima, la tabella nasce vuota
    assert conn.execute("SELECT COUNT(*) FROM siti_giornalieri").fetchone()[0] == 0
    # e la regola v1 e' ancora li'
    assert conn.execute("SELECT COUNT(*) FROM regole").fetchone()[0] == 1
    conn.close()


def test_migrazione_siti_giornalieri_da_tabella_magra(tmp_path):
    """Un DB che avesse la tabella dei siti in forma ridotta guadagna le colonne
    di servizio (monotonia + cecita' dichiarata) senza perdere le fotografie."""
    path = str(tmp_path / "siti-magri.db")
    conn = sqlite3.connect(path)
    conn.executescript(
        """
        CREATE TABLE eventi (
            id TEXT PRIMARY KEY,
            tipo TEXT NOT NULL,
            dettagli TEXT NOT NULL DEFAULT '{}',
            ts_device INTEGER,
            ts_server TEXT NOT NULL
        );
        CREATE TABLE siti_giornalieri (
            giorno TEXT PRIMARY KEY,
            dettagli TEXT NOT NULL,
            evento_id TEXT NOT NULL REFERENCES eventi(id),
            ts_server TEXT NOT NULL,
            totale_domini INTEGER NOT NULL DEFAULT 0
        );
        """
    )
    conn.execute(
        "INSERT INTO eventi (id, tipo, dettagli, ts_server) VALUES ('e1', 'siti_giornalieri', '{}', ?)",
        (TS,),
    )
    conn.execute(
        "INSERT INTO siti_giornalieri (giorno, dettagli, evento_id, ts_server, totale_domini)"
        " VALUES ('2026-07-14', '{\"domini\": {\"instagram.com\": 3}}', 'e1', ?, 1)",
        (TS,),
    )
    conn.commit()
    conn.close()

    db.init_db(path, 30, 90)

    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    assert {"totale_visite", "dns_cifrato"} <= _colonne(conn, "siti_giornalieri")
    riga = conn.execute("SELECT * FROM siti_giornalieri").fetchone()
    assert riga["totale_domini"] == 1  # la fotografia sopravvive
    assert riga["dns_cifrato"] == 0
    conn.close()


def test_migrazione_da_v2_a_v21(tmp_path):
    path = str(tmp_path / "v2.db")
    _db_v2_pre21(path)

    db.init_db(path, 30, 90)

    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row

    # proposte: i dati sopravvivono e il CHECK ora ammette 'annullata'
    proposta = conn.execute("SELECT * FROM proposte WHERE id = 5").fetchone()
    assert proposta["stato"] == "pendente"
    assert proposta["parametri_proposti"] == '{"azione": "elimina"}'
    conn.execute("UPDATE proposte SET stato = 'annullata' WHERE id = 5")  # non solleva piu'
    assert conn.execute("SELECT stato FROM proposte WHERE id = 5").fetchone()["stato"] == "annullata"

    # dichiarazioni: arbitro_nome aggiunto (NULL sulle righe vecchie), dati vivi
    assert "arbitro_nome" in _colonne(conn, "dichiarazioni")
    riga = conn.execute("SELECT * FROM dichiarazioni WHERE id = 9").fetchone()
    assert riga["arbitro_nome"] is None
    assert riga["esito"] == "fallimento"

    # indice UNIQUE (regola_id, giorno): niente due dichiarazioni stesso giorno
    with pytest.raises(sqlite3.IntegrityError):
        conn.execute(
            "INSERT INTO dichiarazioni (regola_id, giorno, esito, stato, ts_server)"
            " VALUES (1, '2026-07-10', 'successo', 'in_attesa', ?)",
            (TS,),
        )
    conn.close()
