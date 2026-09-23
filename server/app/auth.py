"""Auth v3: ogni dispositivo e ogni genitore ha il suo token; il server ne conosce
solo l'hash (tabella credenziali). Token assente, sconosciuto o revocato -> 401;
token valido ma del ruolo sbagliato -> 403. Il ruolo `dispositivo` e' il vecchio
ruolo `figlio`: agisce per il proprio figlio, quello del suo token."""

import sqlite3
from dataclasses import dataclass
from typing import Annotated

from fastapi import Depends, Header, HTTPException

from .db import get_conn, hash_segreto


@dataclass(frozen=True)
class Identita:
    ruolo: str  # 'genitore' | 'dispositivo'
    dispositivo_id: int | None = None
    figlio_id: int | None = None
    tipo: str | None = None  # del dispositivo: 'telefono' | 'computer'


def _estrai_token(authorization: str | None) -> str:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="token mancante")
    token = authorization.removeprefix("Bearer ").strip()
    if not token:
        raise HTTPException(status_code=401, detail="token mancante")
    return token


def _identita(conn: sqlite3.Connection, authorization: str | None) -> Identita:
    # Si cerca l'hash, non il token: il confronto avviene su un valore che non
    # dice niente del segreto, quindi non serve il confronto a tempo costante.
    riga = conn.execute(
        "SELECT c.ruolo, c.dispositivo_id, d.figlio_id, d.tipo, d.revocato_ts"
        " FROM credenziali c LEFT JOIN dispositivi d ON d.id = c.dispositivo_id"
        " WHERE c.token_hash = ? AND c.revocata_ts IS NULL ORDER BY c.id LIMIT 1",
        (hash_segreto(_estrai_token(authorization)),),
    ).fetchone()
    if riga is None:
        raise HTTPException(status_code=401, detail="token sconosciuto")
    if riga["ruolo"] == "genitore":
        return Identita(ruolo="genitore")
    if riga["dispositivo_id"] is None or riga["revocato_ts"] is not None:
        raise HTTPException(status_code=401, detail="dispositivo revocato")
    return Identita(
        ruolo="dispositivo",
        dispositivo_id=riga["dispositivo_id"],
        figlio_id=riga["figlio_id"],
        tipo=riga["tipo"],
    )


def richiede_dispositivo(
    authorization: Annotated[str | None, Header()] = None,
    conn: sqlite3.Connection = Depends(get_conn),
) -> Identita:
    chi = _identita(conn, authorization)
    if chi.ruolo != "dispositivo":
        raise HTTPException(status_code=403, detail="serve il token di un dispositivo del figlio")
    return chi


def richiede_genitore(
    authorization: Annotated[str | None, Header()] = None,
    conn: sqlite3.Connection = Depends(get_conn),
) -> Identita:
    chi = _identita(conn, authorization)
    if chi.ruolo != "genitore":
        raise HTTPException(status_code=403, detail="serve il token del genitore")
    return chi


def richiede_patto(
    authorization: Annotated[str | None, Header()] = None,
    conn: sqlite3.Connection = Depends(get_conn),
) -> Identita:
    return _identita(conn, authorization)
