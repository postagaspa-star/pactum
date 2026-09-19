"""Notifiche a polling (tappa 5): ogni notifica nasce per un destinatario e
ciascun ruolo legge/marca SOLO le proprie. Il genitore riceve sforamenti,
manomissioni, bonus, modifiche regola, risposte alle proposte, dichiarazioni;
il figlio riceve le nuove proposte, i verdetti e il segno del genitore (v2.4)."""

import json
import sqlite3

from fastapi import APIRouter, Depends, HTTPException

from ..auth import richiede_patto
from ..db import get_conn

router = APIRouter()


@router.get("/notifiche")
def elenca_notifiche(
    ruolo: str = Depends(richiede_patto), conn: sqlite3.Connection = Depends(get_conn)
):
    righe = conn.execute(
        "SELECT * FROM notifiche WHERE letta = 0 AND destinatario = ? ORDER BY id",
        (ruolo,),
    ).fetchall()
    return {
        "notifiche": [
            {
                "id": r["id"],
                "destinatario": r["destinatario"],
                "tipo": r["tipo"],
                "messaggio": r["messaggio"],
                "payload": json.loads(r["payload"]),
                "ts_server": r["ts_server"],
            }
            for r in righe
        ]
    }


@router.post("/notifiche/{notifica_id}/letta")
def segna_letta(
    notifica_id: int,
    ruolo: str = Depends(richiede_patto),
    conn: sqlite3.Connection = Depends(get_conn),
):
    # Ciascuno marca come lette solo le proprie: 404 sulle altrui (contratto-api.md).
    cursore = conn.execute(
        "UPDATE notifiche SET letta = 1 WHERE id = ? AND destinatario = ?",
        (notifica_id, ruolo),
    )
    if cursore.rowcount == 0:
        raise HTTPException(status_code=404, detail="notifica non trovata")
    conn.commit()
    return {"id": notifica_id, "letta": True}
