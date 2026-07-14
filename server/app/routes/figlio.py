"""Endpoint del figlio: battito (heartbeat), eventi in batch, bonus."""

import json
import sqlite3

from fastapi import APIRouter, Depends, HTTPException

from .. import clock
from ..auth import richiede_figlio
from ..db import accoda_notifica, get_conn, stato_bonus
from ..schemas import BattitoIn, BonusIn, EventoIn

router = APIRouter(dependencies=[Depends(richiede_figlio)])

TIPI_EVENTO_DA_NOTIFICARE = {"sforamento", "manomissione"}


@router.post("/battito")
def battito(
    corpo: BattitoIn | None = None, conn: sqlite3.Connection = Depends(get_conn)
):
    corpo = corpo or BattitoIn()
    ts = clock.iso(clock.now())
    conn.execute(
        "INSERT INTO battiti (batteria, ts_device, ts_server) VALUES (?, ?, ?)",
        (corpo.batteria, corpo.ts_device, ts),
    )
    conn.commit()
    return {"ts_server": ts}


@router.post("/eventi")
def registra_eventi(eventi: list[EventoIn], conn: sqlite3.Connection = Depends(get_conn)):
    """Batch idempotente: l'id lo genera il client, un id gia' visto viene ignorato."""
    ts = clock.iso(clock.now())
    nuovi = 0
    duplicati = 0
    for evento in eventi:
        cursore = conn.execute(
            "INSERT OR IGNORE INTO eventi (id, tipo, payload, ts_device, ts_server)"
            " VALUES (?, ?, ?, ?, ?)",
            (evento.id, evento.tipo, json.dumps(evento.payload), evento.ts_device, ts),
        )
        if cursore.rowcount:
            nuovi += 1
            if evento.tipo in TIPI_EVENTO_DA_NOTIFICARE:
                accoda_notifica(
                    conn,
                    evento.tipo,
                    f"Evento {evento.tipo} registrato",
                    {"evento_id": evento.id, "payload": evento.payload},
                    ts,
                )
        else:
            duplicati += 1
    conn.commit()
    return {"ricevuti": len(eventi), "nuovi": nuovi, "duplicati": duplicati}


@router.post("/bonus", status_code=201)
def concedi_bonus(corpo: BonusIn, conn: sqlite3.Connection = Depends(get_conn)):
    ora = clock.now()
    stato = stato_bonus(conn, ora)
    residuo_giorno = stato["giorno"]["residui"]
    residuo_settimana = stato["settimana"]["residui"]
    if corpo.minuti > residuo_giorno or corpo.minuti > residuo_settimana:
        raise HTTPException(
            status_code=409,
            detail={
                "errore": "tetto_superato",
                "residuo_giorno": residuo_giorno,
                "residuo_settimana": residuo_settimana,
            },
        )
    ts = clock.iso(ora)
    conn.execute(
        "INSERT INTO bonus (minuti, motivo, ts_server) VALUES (?, ?, ?)",
        (corpo.minuti, corpo.motivo, ts),
    )
    accoda_notifica(
        conn,
        "bonus",
        f"Bonus di {corpo.minuti} minuti auto-concesso",
        {
            "minuti": corpo.minuti,
            "motivo": corpo.motivo,
            "residuo_giorno": residuo_giorno - corpo.minuti,
            "residuo_settimana": residuo_settimana - corpo.minuti,
        },
        ts,
    )
    conn.commit()
    return {
        "minuti": corpo.minuti,
        "residuo_giorno": residuo_giorno - corpo.minuti,
        "residuo_settimana": residuo_settimana - corpo.minuti,
    }
