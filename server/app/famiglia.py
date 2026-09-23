"""(v3) La famiglia: figli e dispositivi (contratto-api.md, "v3 — Famiglia, figli e
dispositivi"). Qui vivono le letture condivise da piu' endpoint: quale figlio
vuole il genitore, quali dispositivi ha, qual e' il primo (quello a cui valgono i
campi di primo livello della finestra per le app 0.7), se un dispositivo tace o
e' solo spento."""

import sqlite3
from datetime import datetime, timedelta

from fastapi import HTTPException

from .config import SOGLIA_SILENZIO_MINUTI


def figlio_o_404(conn: sqlite3.Connection, figlio_id: int) -> sqlite3.Row:
    riga = conn.execute("SELECT * FROM figli WHERE id = ?", (figlio_id,)).fetchone()
    if riga is None:
        raise HTTPException(status_code=404, detail="figlio non trovato")
    return riga


def figlio_scelto(conn: sqlite3.Connection, figlio_id: int | None) -> sqlite3.Row:
    """Il figlio di una richiesta del genitore: quello indicato da `figlio_id`,
    oppure, se manca, quello con l'id piu' basso (cosi' l'app del genitore 0.7
    continua a vedere il primo figlio). Un figlio_id inesistente -> 404."""
    if figlio_id is not None:
        return figlio_o_404(conn, figlio_id)
    riga = conn.execute("SELECT * FROM figli ORDER BY id LIMIT 1").fetchone()
    if riga is None:
        raise HTTPException(status_code=404, detail="nessun figlio")
    return riga


def dispositivi_del_figlio(conn: sqlite3.Connection, figlio_id: int) -> list[sqlite3.Row]:
    """Tutti i dispositivi del figlio in ordine di id, revocati compresi."""
    return conn.execute(
        "SELECT * FROM dispositivi WHERE figlio_id = ? ORDER BY id", (figlio_id,)
    ).fetchall()


def primo_dispositivo(dispositivi: list[sqlite3.Row]) -> sqlite3.Row | None:
    """Il dispositivo con l'id piu' basso non revocato: i campi di primo livello
    della finestra valgono per lui (compatibilita' 0.7). None se non ce n'e'."""
    return next((d for d in dispositivi if d["revocato_ts"] is None), None)


def riferimento(dispositivo: sqlite3.Row) -> dict:
    """Il dispositivo come lo allegano regole, patto e abbinamento: solo chi e'."""
    return {"id": dispositivo["id"], "nome": dispositivo["nome"], "tipo": dispositivo["tipo"]}


def descrizione(dispositivo: sqlite3.Row) -> dict:
    return {
        **riferimento(dispositivo),
        "abbinato": dispositivo["abbinato_ts"] is not None,
        "revocato": dispositivo["revocato_ts"] is not None,
    }


def stato_silenzio(conn: sqlite3.Connection, dispositivo: sqlite3.Row | None, ora: datetime) -> dict:
    """`silente` = nessun battito da piu' di 45 minuti, calcolato in lettura
    sull'orologio del server; `ultimo_battito` null se non e' mai arrivato niente
    (=> silente).

    (v3) Per un COMPUTER il silenzio dopo una `sospensione` non e'
    un'interruzione: un computer spento la sera e' normale. Finche' dopo la
    sospensione non arriva un segno di vita (un battito o una `ripresa`), il
    computer e' `spento` dal momento della sospensione e non `silente`. Per i
    telefoni non cambia niente: `spento` e' sempre false."""
    if dispositivo is None:
        return {"ultimo_battito": None, "silente": True, "spento": False, "spento_dal": None}
    ultimo = conn.execute(
        "SELECT MAX(ts_server) AS ultimo FROM battiti WHERE dispositivo_id = ?",
        (dispositivo["id"],),
    ).fetchone()["ultimo"]

    if dispositivo["tipo"] == "computer":
        # rowid = ordine d'arrivo: sospensione e ripresa possono arrivare nello
        # stesso pacco (stesso ts_server), nell'ordine della coda del computer.
        sospensione = conn.execute(
            "SELECT rowid, ts_server FROM eventi WHERE dispositivo_id = ? AND tipo = 'sospensione'"
            " ORDER BY rowid DESC LIMIT 1",
            (dispositivo["id"],),
        ).fetchone()
        if sospensione is not None:
            ripresa_dopo = conn.execute(
                "SELECT 1 FROM eventi WHERE dispositivo_id = ? AND tipo = 'ripresa' AND rowid > ?"
                " LIMIT 1",
                (dispositivo["id"], sospensione["rowid"]),
            ).fetchone()
            battito_dopo = ultimo is not None and ultimo > sospensione["ts_server"]
            if ripresa_dopo is None and not battito_dopo:
                return {
                    "ultimo_battito": ultimo,
                    "silente": False,
                    "spento": True,
                    "spento_dal": sospensione["ts_server"],
                }

    if ultimo is None:
        return {"ultimo_battito": None, "silente": True, "spento": False, "spento_dal": None}
    trascorso = ora - datetime.fromisoformat(ultimo)
    return {
        "ultimo_battito": ultimo,
        "silente": trascorso > timedelta(minutes=SOGLIA_SILENZIO_MINUTI),
        "spento": False,
        "spento_dal": None,
    }
