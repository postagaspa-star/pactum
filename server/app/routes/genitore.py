"""Endpoint del genitore: la finestra (non vetrata) e le notifiche a polling.
Il silenzio si calcola in lettura: nessun job in background nella v1."""

import json
import sqlite3
from collections import defaultdict
from datetime import datetime, timedelta

from fastapi import APIRouter, Depends, HTTPException

from .. import clock
from ..auth import richiede_genitore
from ..config import SOGLIA_SILENZIO_MINUTI
from ..db import get_conn, stato_bonus
from .regole import _riga_regola

router = APIRouter(dependencies=[Depends(richiede_genitore)])

GIORNI_SEMAFORO = 7  # oggi + gli ultimi 7
RECENTI = 20
STORICO_MASSIMO = 50


def _evento_out(riga: sqlite3.Row) -> dict:
    return {
        "id": riga["id"],
        "tipo": riga["tipo"],
        "payload": json.loads(riga["payload"]),
        "ts_device": riga["ts_device"],
        "ts_server": riga["ts_server"],
    }


def _stato_silenzio(conn: sqlite3.Connection, ora: datetime) -> dict:
    riga = conn.execute("SELECT MAX(ts_server) AS ultimo FROM battiti").fetchone()
    ultimo = riga["ultimo"]
    if ultimo is None:
        return {"ultimo_battito": None, "silente": True}
    trascorso = ora - datetime.fromisoformat(ultimo)
    return {
        "ultimo_battito": ultimo,
        "silente": trascorso > timedelta(minutes=SOGLIA_SILENZIO_MINUTI),
    }


@router.get("/finestra")
def finestra(conn: sqlite3.Connection = Depends(get_conn)):
    ora = clock.now()
    oggi = ora.date()
    giorni = [oggi - timedelta(days=n) for n in range(GIORNI_SEMAFORO, -1, -1)]

    eventi = conn.execute(
        "SELECT * FROM eventi WHERE tipo IN ('sforamento', 'manomissione', 'bonus_usato')"
        " ORDER BY ts_server DESC, id DESC"
    ).fetchall()

    sforamenti_per_regola = defaultdict(set)  # regola_id -> {data ISO}
    bonus_per_regola = defaultdict(set)
    for evento in eventi:
        payload = json.loads(evento["payload"])
        regola_id = payload.get("regola_id")
        if regola_id is None:
            continue
        data = evento["ts_server"][:10]
        if evento["tipo"] == "sforamento":
            sforamenti_per_regola[regola_id].add(data)
        elif evento["tipo"] == "bonus_usato":
            bonus_per_regola[regola_id].add(data)

    regole = []
    for riga in conn.execute("SELECT * FROM regole ORDER BY id").fetchall():
        creata = riga["creata_ts"][:10]
        semaforo = []
        for giorno in giorni:
            data = giorno.isoformat()
            if data < creata:
                stato = "grigio"
            elif data in sforamenti_per_regola[riga["id"]]:
                stato = "rosso"
            elif data in bonus_per_regola[riga["id"]]:
                stato = "giallo"
            else:
                stato = "verde"
            semaforo.append({"data": data, "stato": stato})
        regole.append({**_riga_regola(riga), "semaforo": semaforo})

    storico = [
        {
            "id": r["id"],
            "regola_id": r["regola_id"],
            "azione": r["azione"],
            "direzione": r["direzione"],
            "prima": json.loads(r["parametri_prima"]) if r["parametri_prima"] else None,
            "dopo": json.loads(r["parametri_dopo"]) if r["parametri_dopo"] else None,
            "concordata": bool(r["concordata"]),
            "ts_server": r["ts_server"],
        }
        for r in conn.execute(
            "SELECT * FROM storico_modifiche ORDER BY id DESC LIMIT ?", (STORICO_MASSIMO,)
        ).fetchall()
    ]

    return {
        "regole": regole,
        "sforamenti_recenti": [
            _evento_out(e) for e in eventi if e["tipo"] == "sforamento"
        ][:RECENTI],
        "manomissioni_recenti": [
            _evento_out(e) for e in eventi if e["tipo"] == "manomissione"
        ][:RECENTI],
        "storico_modifiche": storico,
        "bonus": stato_bonus(conn, ora),
        "stato_silenzio": _stato_silenzio(conn, ora),
    }


@router.get("/notifiche")
def elenca_notifiche(conn: sqlite3.Connection = Depends(get_conn)):
    righe = conn.execute(
        "SELECT * FROM notifiche WHERE letta = 0 ORDER BY id"
    ).fetchall()
    return {
        "notifiche": [
            {
                "id": r["id"],
                "tipo": r["tipo"],
                "messaggio": r["messaggio"],
                "payload": json.loads(r["payload"]),
                "ts_server": r["ts_server"],
            }
            for r in righe
        ]
    }


@router.post("/notifiche/{notifica_id}/letta")
def segna_letta(notifica_id: int, conn: sqlite3.Connection = Depends(get_conn)):
    cursore = conn.execute(
        "UPDATE notifiche SET letta = 1 WHERE id = ?", (notifica_id,)
    )
    if cursore.rowcount == 0:
        raise HTTPException(status_code=404, detail="notifica non trovata")
    conn.commit()
    return {"id": notifica_id, "letta": True}
