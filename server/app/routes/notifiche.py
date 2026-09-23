"""Notifiche a polling (tappa 5): ogni notifica nasce per un destinatario e
ciascun ruolo legge/marca SOLO le proprie. Il genitore riceve sforamenti,
manomissioni, bonus, modifiche regola, risposte alle proposte, dichiarazioni;
il figlio riceve le nuove proposte, i verdetti e il segno del genitore (v2.4).

(v3) Il genitore riceve quelle di tutti i figli. Un dispositivo riceve quelle del
SUO figlio che hanno il suo dispositivo_id o nessuno (quelle del figlio: vita
reale, segno). Marcarne una come letta la marca per tutti."""

import json
import sqlite3

from fastapi import APIRouter, Depends, HTTPException

from ..auth import Identita, richiede_patto
from ..db import get_conn

router = APIRouter()


def _filtro(chi: Identita) -> tuple[str, tuple]:
    """La condizione SQL che dice quali notifiche sono di chi chiama."""
    if chi.ruolo == "genitore":
        return "destinatario = 'genitore'", ()
    return (
        "destinatario = 'figlio' AND figlio_id = ? AND (dispositivo_id = ? OR dispositivo_id IS NULL)",
        (chi.figlio_id, chi.dispositivo_id),
    )


@router.get("/notifiche")
def elenca_notifiche(
    chi: Identita = Depends(richiede_patto), conn: sqlite3.Connection = Depends(get_conn)
):
    condizione, parametri = _filtro(chi)
    righe = conn.execute(
        f"SELECT * FROM notifiche WHERE letta = 0 AND {condizione} ORDER BY id", parametri
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
                "figlio_id": r["figlio_id"],
                "dispositivo_id": r["dispositivo_id"],
            }
            for r in righe
        ]
    }


@router.post("/notifiche/{notifica_id}/letta")
def segna_letta(
    notifica_id: int,
    chi: Identita = Depends(richiede_patto),
    conn: sqlite3.Connection = Depends(get_conn),
):
    # Ciascuno marca come lette solo le proprie: 404 sulle altrui (contratto-api.md),
    # comprese (v3) quelle di un altro figlio o di un altro dispositivo.
    condizione, parametri = _filtro(chi)
    cursore = conn.execute(
        f"UPDATE notifiche SET letta = 1 WHERE id = ? AND {condizione}",
        (notifica_id, *parametri),
    )
    if cursore.rowcount == 0:
        raise HTTPException(status_code=404, detail="notifica non trovata")
    conn.commit()
    return {"id": notifica_id, "letta": True}
