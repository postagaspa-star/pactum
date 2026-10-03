"""(v3) Abbinamento con codice (contratto-api.md, "Abbinamento con codice"): niente
piu' token da copiare. Il genitore chiede un codice per un dispositivo, il
dispositivo lo scambia con il suo token.

- Il codice e' di 6 cifre (secrets), vale 15 minuti, una volta sola; un codice
  nuovo per lo stesso dispositivo annulla il precedente.
- Il token nasce all'abbinamento e invalida quello vecchio del dispositivo: la
  storia del dispositivo continua, cambia solo la chiave.
- Contro chi prova i codici a caso: dopo 10 tentativi falliti in 10 minuti,
  contati su tutto il server, ogni abbinamento risponde 429 per 10 minuti, anche
  con un codice giusto.

Come i token, anche i codici nel database ci sono solo come hash.

(v3.6) Anche i genitori nuovi si abbinano con un codice: stesse regole, stesso
conteggio dei tentativi falliti, e mai due codici aperti uguali tra genitori e
dispositivi (codici_genitori accanto a codici_abbinamento)."""

import secrets
import sqlite3
from datetime import datetime, timedelta

from . import clock
from .config import (
    BLOCCO_ABBINAMENTO_MINUTI,
    CIFRE_CODICE,
    DURATA_CODICE_MINUTI,
    FINESTRA_TENTATIVI_MINUTI,
    TENTATIVI_MASSIMI,
)
from .db import hash_segreto


def nuovo_token() -> str:
    return secrets.token_urlsafe(32)


def _codice_casuale() -> str:
    return f"{secrets.randbelow(10 ** CIFRE_CODICE):0{CIFRE_CODICE}d}"


def _codice_libero(conn: sqlite3.Connection, ts: str) -> str:
    """Un codice che in questo momento non e' aperto per nessun altro, dispositivo o
    (v3.6) genitore: due codici uguali aperti insieme non saprebbero chi abbinare."""
    while True:
        codice = _codice_casuale()
        gia_aperto = any(
            conn.execute(
                f"SELECT 1 FROM {tabella} WHERE codice_hash = ? AND usato_ts IS NULL"
                " AND annullato_ts IS NULL AND scade_ts > ?",
                (hash_segreto(codice), ts),
            ).fetchone()
            is not None
            for tabella in ("codici_abbinamento", "codici_genitori")
        )
        if not gia_aperto:
            return codice


def _nuovo_codice(
    conn: sqlite3.Connection, tabella: str, colonna: str, titolare_id: int, ora: datetime
) -> tuple[str, str]:
    """Annulla i codici ancora aperti del titolare (dispositivo o genitore) e ne crea
    uno nuovo. Restituisce (codice, scade_ts). Chi chiama tiene la transazione."""
    ts = clock.iso(ora)
    conn.execute(
        f"UPDATE {tabella} SET annullato_ts = ?"
        f" WHERE {colonna} = ? AND usato_ts IS NULL AND annullato_ts IS NULL",
        (ts, titolare_id),
    )
    codice = _codice_libero(conn, ts)
    scade_ts = clock.iso(ora + timedelta(minutes=DURATA_CODICE_MINUTI))
    conn.execute(
        f"INSERT INTO {tabella} ({colonna}, codice_hash, creato_ts, scade_ts) VALUES (?, ?, ?, ?)",
        (titolare_id, hash_segreto(codice), ts, scade_ts),
    )
    return codice, scade_ts


def crea_codice(conn: sqlite3.Connection, dispositivo_id: int, ora: datetime) -> tuple[str, str]:
    """Un codice nuovo per il dispositivo: annulla i suoi codici ancora aperti e
    non ne ripete uno valido in quel momento per un altro dispositivo (due codici
    uguali aperti insieme non saprebbero quale dispositivo abbinare).
    Restituisce (codice, scade_ts). Chi chiama tiene la transazione."""
    return _nuovo_codice(conn, "codici_abbinamento", "dispositivo_id", dispositivo_id, ora)


def crea_codice_genitore(conn: sqlite3.Connection, genitore_id: int, ora: datetime) -> tuple[str, str]:
    """(v3.6) Lo stesso per un genitore: stesse regole, e mai uguale a un codice
    aperto di un dispositivo."""
    return _nuovo_codice(conn, "codici_genitori", "genitore_id", genitore_id, ora)


def fine_blocco(conn: sqlite3.Connection, ora: datetime) -> datetime | None:
    """Fino a quando l'abbinamento e' bloccato, o None. Il blocco scatta quando gli
    ultimi 10 tentativi falliti stanno dentro 10 minuti e dura 10 minuti dall'ultimo
    di loro. Mentre e' bloccato i tentativi non si registrano (il codice non si
    guarda nemmeno), quindi finito il blocco si riparte da capo."""
    ultimi = conn.execute(
        "SELECT ts_server FROM tentativi_abbinamento ORDER BY id DESC LIMIT ?",
        (TENTATIVI_MASSIMI,),
    ).fetchall()
    if len(ultimi) < TENTATIVI_MASSIMI:
        return None
    piu_recente = datetime.fromisoformat(ultimi[0]["ts_server"])
    piu_vecchio = datetime.fromisoformat(ultimi[-1]["ts_server"])
    if piu_recente - piu_vecchio >= timedelta(minutes=FINESTRA_TENTATIVI_MINUTI):
        return None
    fine = piu_recente + timedelta(minutes=BLOCCO_ABBINAMENTO_MINUTI)
    return fine if ora < fine else None


def registra_fallimento(conn: sqlite3.Connection, ora: datetime) -> None:
    conn.execute("INSERT INTO tentativi_abbinamento (ts_server) VALUES (?)", (clock.iso(ora),))


def codice_valido(conn: sqlite3.Connection, codice: str, ora: datetime) -> sqlite3.Row | None:
    """Il codice aperto (non usato, non annullato, non scaduto) di un dispositivo
    non revocato, con il dispositivo e il suo figlio; None se non c'e'. Sbagliato,
    scaduto e gia' usato sono la stessa cosa: chi prova non deve capire quale."""
    return conn.execute(
        "SELECT c.id AS codice_id, d.id AS dispositivo_id, d.nome AS dispositivo_nome,"
        " d.tipo AS dispositivo_tipo, f.id AS figlio_id, f.nome AS figlio_nome"
        " FROM codici_abbinamento c"
        " JOIN dispositivi d ON d.id = c.dispositivo_id"
        " JOIN figli f ON f.id = d.figlio_id"
        " WHERE c.codice_hash = ? AND c.usato_ts IS NULL AND c.annullato_ts IS NULL"
        " AND c.scade_ts > ? AND d.revocato_ts IS NULL",
        (hash_segreto(codice), clock.iso(ora)),
    ).fetchone()


def codice_genitore_valido(conn: sqlite3.Connection, codice: str, ora: datetime) -> sqlite3.Row | None:
    """(v3.6) Il codice aperto di un genitore non revocato, col genitore; None se non
    c'e'. Come per i dispositivi: sbagliato, scaduto e gia' usato sono la stessa cosa."""
    return conn.execute(
        "SELECT c.id AS codice_id, g.id AS genitore_id, g.nome AS genitore_nome"
        " FROM codici_genitori c JOIN genitori g ON g.id = c.genitore_id"
        " WHERE c.codice_hash = ? AND c.usato_ts IS NULL AND c.annullato_ts IS NULL"
        " AND c.scade_ts > ? AND g.revocato_ts IS NULL",
        (hash_segreto(codice), clock.iso(ora)),
    ).fetchone()


def abbina_genitore(conn: sqlite3.Connection, trovato: sqlite3.Row, ora: datetime) -> str:
    """(v3.6) Consuma il codice e da' al genitore un token nuovo: le sue credenziali
    di prima (anche il token d'ambiente, per il genitore 1 riabbinato) smettono di
    valere. Restituisce il token, che non si salva."""
    ts = clock.iso(ora)
    token = nuovo_token()
    conn.execute("UPDATE codici_genitori SET usato_ts = ? WHERE id = ?", (ts, trovato["codice_id"]))
    conn.execute(
        "UPDATE credenziali SET revocata_ts = ? WHERE genitore_id = ? AND revocata_ts IS NULL",
        (ts, trovato["genitore_id"]),
    )
    conn.execute(
        "INSERT INTO credenziali (ruolo, genitore_id, token_hash, origine, creata_ts)"
        " VALUES ('genitore', ?, ?, 'abbinamento', ?)",
        (trovato["genitore_id"], hash_segreto(token), ts),
    )
    conn.execute("UPDATE genitori SET abbinato_ts = ? WHERE id = ?", (ts, trovato["genitore_id"]))
    return token


def abbina(
    conn: sqlite3.Connection, trovato: sqlite3.Row, versione_app: str | None, ora: datetime
) -> str:
    """Consuma il codice e da' al dispositivo un token nuovo: le sue credenziali di
    prima (il token d'ambiente del telefono 0.7, o quello di un abbinamento
    precedente) smettono di valere. Restituisce il token, che non si salva."""
    ts = clock.iso(ora)
    token = nuovo_token()
    conn.execute("UPDATE codici_abbinamento SET usato_ts = ? WHERE id = ?", (ts, trovato["codice_id"]))
    conn.execute(
        "UPDATE credenziali SET revocata_ts = ? WHERE dispositivo_id = ? AND revocata_ts IS NULL",
        (ts, trovato["dispositivo_id"]),
    )
    conn.execute(
        "INSERT INTO credenziali (ruolo, dispositivo_id, token_hash, origine, creata_ts)"
        " VALUES ('dispositivo', ?, ?, 'abbinamento', ?)",
        (trovato["dispositivo_id"], hash_segreto(token), ts),
    )
    conn.execute(
        "UPDATE dispositivi SET abbinato_ts = ?, versione_app = COALESCE(?, versione_app)"
        " WHERE id = ?",
        (ts, versione_app, trovato["dispositivo_id"]),
    )
    return token
