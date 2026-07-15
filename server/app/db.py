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

-- Proposte del genitore (tappa 5). Il genitore non impone mai: propone.
-- parametri_proposti (JSON) sono i parametri ESATTI concordati: la modifica
-- concordata li applica tali e quali (il figlio non puo' cambiarli al volo).
-- Per una proposta di ELIMINAZIONE il valore e' il marcatore {"azione": "elimina"}.
-- confronto/direzione li calcola il server alla creazione (differenza vs valore
-- attuale, testo per la notifica al figlio). risposta_* si riempiono quando il
-- figlio accetta/rifiuta; usata=1 quando la modifica concordata e' stata applicata
-- (con l'auto-applicazione avviene insieme all'accettazione).
CREATE TABLE IF NOT EXISTS proposte (
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

-- Dichiarazioni del figlio sulle regole di vita reale (tappa 5).
-- Fallimento = creduto sulla parola -> stato 'registrata'. Successo = serve il
-- verdetto del genitore/arbitro -> 'in_attesa'. Il verdetto porta a 'confermata',
-- 'confermata_per_conto' (il genitore garantisce di aver sentito l'arbitro fuori
-- dall'app) o 'ribaltata'. verdetto_registro conserva la frase leggibile del
-- registro (es. "confermato dal genitore per conto di [arbitro]") al momento del
-- verdetto, cosi' resta vera anche se la regola cambia dopo. Max una dichiarazione
-- per regola per giorno (fuso del patto), controllata in scrittura.
CREATE TABLE IF NOT EXISTS dichiarazioni (
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

-- Bonus autoritativo (tappa 5): agganciato a una regola limite_tempo specifica
-- ("mi do +15 su TikTok"). regola_id e' obbligatorio dagli endpoint v2; resta
-- nullable per le righe dei database v1 (nessun aggancio da retro-attribuire).
CREATE TABLE IF NOT EXISTS bonus (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    minuti INTEGER NOT NULL,
    regola_id INTEGER REFERENCES regole(id),
    motivo TEXT,
    ts_server TEXT NOT NULL
);

-- Ogni notifica nasce per un destinatario ('figlio' o 'genitore'): il GET e la
-- marcatura come letta filtrano sul ruolo del token. Le righe v1 erano tutte del
-- genitore (backfill via _migra).
CREATE TABLE IF NOT EXISTS notifiche (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    destinatario TEXT NOT NULL DEFAULT 'genitore' CHECK (destinatario IN ('figlio', 'genitore')),
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


def _colonne(conn: sqlite3.Connection, tabella: str) -> set[str]:
    return {r[1] for r in conn.execute(f"PRAGMA table_info({tabella})")}


def _migra(conn: sqlite3.Connection) -> None:
    """Micro-migrazioni per database creati con schemi precedenti. SCHEMA
    (CREATE TABLE IF NOT EXISTS) gira prima: le tabelle nuove (dichiarazioni)
    nascono gia' bene, qui si aggiornano solo quelle preesistenti."""
    if "totale_minuti" not in _colonne(conn, "uso_giornaliero"):
        conn.execute(
            "ALTER TABLE uso_giornaliero ADD COLUMN totale_minuti INTEGER NOT NULL DEFAULT 0"
        )

    # bonus: aggancio a una regola (v2). Le righe v1 restano senza regola_id.
    if "regola_id" not in _colonne(conn, "bonus"):
        conn.execute("ALTER TABLE bonus ADD COLUMN regola_id INTEGER REFERENCES regole(id)")

    # notifiche: destinatario (v2). Backfill delle righe v1 come 'genitore'.
    if "destinatario" not in _colonne(conn, "notifiche"):
        conn.execute(
            "ALTER TABLE notifiche ADD COLUMN destinatario TEXT NOT NULL DEFAULT 'genitore'"
        )

    # proposte: lo stub v1 non aveva confronto/direzione/risposta_* e usava lo
    # stato 'in_attesa'. Il CHECK dello stato non si altera con ALTER: si
    # ricostruisce la tabella (di norma vuota, gli endpoint non esistevano in v1).
    colonne_proposte = _colonne(conn, "proposte")
    if colonne_proposte and "confronto" not in colonne_proposte:
        # PRAGMA foreign_keys e' un no-op dentro una transazione: si chiude prima
        # quella eventualmente aperta dagli ALTER qui sopra.
        conn.commit()
        conn.execute("PRAGMA foreign_keys=OFF")
        conn.executescript(
            """
            ALTER TABLE proposte RENAME TO _proposte_v1;
            CREATE TABLE proposte (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                regola_id INTEGER NOT NULL REFERENCES regole(id),
                parametri_proposti TEXT,
                motivazione TEXT,
                confronto TEXT,
                direzione TEXT,
                stato TEXT NOT NULL DEFAULT 'pendente'
                    CHECK (stato IN ('pendente', 'accettata', 'rifiutata')),
                usata INTEGER NOT NULL DEFAULT 0,
                risposta_esito TEXT,
                risposta_motivazione TEXT,
                risposta_ts TEXT,
                ts_server TEXT NOT NULL
            );
            INSERT INTO proposte
                (id, regola_id, parametri_proposti, motivazione, stato, usata, ts_server)
            SELECT id, regola_id, parametri_proposti, motivazione,
                CASE stato WHEN 'in_attesa' THEN 'pendente' ELSE stato END,
                usata, ts_server
            FROM _proposte_v1;
            DROP TABLE _proposte_v1;
            """
        )
        conn.execute("PRAGMA foreign_keys=ON")


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


def accoda_notifica(
    conn: sqlite3.Connection,
    tipo: str,
    messaggio: str,
    payload: dict,
    ts: str,
    destinatario: str = "genitore",
) -> None:
    conn.execute(
        "INSERT INTO notifiche (destinatario, tipo, messaggio, payload, ts_server)"
        " VALUES (?, ?, ?, ?, ?)",
        (destinatario, tipo, messaggio, json.dumps(payload), ts),
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


def _inizio_giorno_settimana(ora: datetime) -> tuple[datetime, datetime]:
    """Confini (UTC) del giorno e della settimana ISO correnti, calcolati nel
    fuso del patto: il giorno di un ragazzo italiano non si azzera alle 02:00."""
    ora_locale = ora.astimezone(config.fuso_patto())
    inizio_giorno_locale = ora_locale.replace(hour=0, minute=0, second=0, microsecond=0)
    inizio_settimana_locale = inizio_giorno_locale - timedelta(days=ora_locale.weekday())
    return (
        inizio_giorno_locale.astimezone(timezone.utc),
        inizio_settimana_locale.astimezone(timezone.utc),
    )


def bonus_oggi_per_regola(conn: sqlite3.Connection, ora: datetime) -> dict:
    """Minuti bonus concessi OGGI (fuso del patto) per regola, chiave = regola_id
    come stringa: il valutatore locale del figlio ne ha bisogno per il limite
    efficace del giorno (minuti_al_giorno + bonus di quella regola)."""
    inizio_giorno, _ = _inizio_giorno_settimana(ora)
    righe = conn.execute(
        "SELECT regola_id, COALESCE(SUM(minuti), 0) AS totale FROM bonus"
        " WHERE ts_server >= ? AND regola_id IS NOT NULL GROUP BY regola_id",
        (clock.iso(inizio_giorno),),
    ).fetchall()
    return {str(r["regola_id"]): r["totale"] for r in righe}


def stato_bonus(conn: sqlite3.Connection, ora: datetime) -> dict:
    """Contatori bonus del giorno e della settimana ISO (lunedi'-domenica).
    I confini dei bucket sono nel fuso del patto (config.fuso_patto);
    il confronto avviene sui ts_server UTC."""
    tetto_giorno = int(valore_patto(conn, "tetto_bonus_giorno"))
    tetto_settimana = int(valore_patto(conn, "tetto_bonus_settimana"))
    inizio_giorno, inizio_settimana = _inizio_giorno_settimana(ora)

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
