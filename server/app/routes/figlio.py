"""Endpoint del figlio: battito (heartbeat), eventi in batch, bonus."""

import json
import sqlite3
from datetime import date

from fastapi import APIRouter, Depends, HTTPException

from .. import clock
from ..auth import richiede_figlio
from ..db import accoda_notifica, get_conn, stato_bonus
from ..schemas import BattitoIn, BonusIn, EventiIn, EventoIn

router = APIRouter(dependencies=[Depends(richiede_figlio)])

# riavvio NON e' qui per scelta (contratto-api.md): marca l'azzeramento di
# elapsed_realtime, non e' una manomissione e non genera notifiche.
TIPI_EVENTO_DA_NOTIFICARE = {"sforamento", "manomissione"}


@router.post("/battito")
def battito(
    corpo: BattitoIn | None = None, conn: sqlite3.Connection = Depends(get_conn)
):
    corpo = corpo or BattitoIn()
    ts = clock.iso(clock.now())
    conn.execute(
        "INSERT INTO battiti (batteria, versione_app, elapsed_realtime, ts_device, ts_server)"
        " VALUES (?, ?, ?, ?, ?)",
        (corpo.batteria, corpo.versione_app, corpo.elapsed_realtime, corpo.ts_device, ts),
    )
    conn.commit()
    return {"ricevuto": True}


def _giorno_valido(giorno) -> bool:
    """Vero solo per una data reale in forma YYYY-MM-DD (contratto-api.md)."""
    if not isinstance(giorno, str) or len(giorno) != 10:
        return False
    try:
        return date.fromisoformat(giorno).isoformat() == giorno
    except ValueError:
        return False


def _totale_minuti(dettagli: dict) -> int:
    """totale_minuti mancante o non valido vale 0 (contratto-api.md)."""
    totale = dettagli.get("totale_minuti")
    if isinstance(totale, bool) or not isinstance(totale, int) or totale < 0:
        return 0
    return totale


def _aggiorna_uso_giornaliero(conn: sqlite3.Connection, evento: EventoIn, ts: str) -> None:
    """uso_giornaliero e' una fotografia CUMULATIVA del giorno e la vigente e'
    MONOTONA su totale_minuti (contratto-api.md): una fotografia arrivata in
    ritardo con un totale piu' basso non regredisce quella vigente. Il registro
    eventi conserva comunque tutte le fotografie; qui si aggiorna solo la vigente."""
    giorno = evento.dettagli.get("giorno")
    if not _giorno_valido(giorno):
        # Senza un giorno valido non c'e' fotografia da indicizzare:
        # l'evento resta comunque nel registro.
        return
    conn.execute(
        "INSERT INTO uso_giornaliero (giorno, dettagli, evento_id, ts_server, totale_minuti)"
        " VALUES (?, ?, ?, ?, ?)"
        " ON CONFLICT(giorno) DO UPDATE SET"
        " dettagli = excluded.dettagli,"
        " evento_id = excluded.evento_id,"
        " ts_server = excluded.ts_server,"
        " totale_minuti = excluded.totale_minuti"
        " WHERE excluded.totale_minuti >= uso_giornaliero.totale_minuti",
        (giorno, json.dumps(evento.dettagli), evento.id, ts, _totale_minuti(evento.dettagli)),
    )


@router.post("/eventi")
def registra_eventi(corpo: EventiIn, conn: sqlite3.Connection = Depends(get_conn)):
    """Batch idempotente: l'id lo genera il client, un id gia' visto viene ignorato."""
    ts = clock.iso(clock.now())
    nuovi = 0
    duplicati = 0
    for evento in corpo.eventi:
        cursore = conn.execute(
            "INSERT OR IGNORE INTO eventi (id, tipo, dettagli, ts_device, ts_server)"
            " VALUES (?, ?, ?, ?, ?)",
            (evento.id, evento.tipo, json.dumps(evento.dettagli), evento.ts_device, ts),
        )
        if cursore.rowcount:
            nuovi += 1
            if evento.tipo == "uso_giornaliero":
                _aggiorna_uso_giornaliero(conn, evento, ts)
            if evento.tipo in TIPI_EVENTO_DA_NOTIFICARE:
                accoda_notifica(
                    conn,
                    evento.tipo,
                    f"Evento {evento.tipo} registrato",
                    {"evento_id": evento.id, "dettagli": evento.dettagli},
                    ts,
                )
        else:
            duplicati += 1
    conn.commit()
    return {"ricevuti": len(corpo.eventi), "nuovi": nuovi, "duplicati": duplicati}


@router.post("/bonus")
def concedi_bonus(corpo: BonusIn, conn: sqlite3.Connection = Depends(get_conn)):
    # Risposta 200 con i residui aggiornati (contratto-api.md), 409 se un tetto salta.
    # BEGIN IMMEDIATE: leggi-controlla-inserisci deve essere atomico, altrimenti
    # N richieste simultanee leggono lo stesso residuo e il tetto salta N volte.
    # Il lock di scrittura serializza i concorrenti; chi arriva secondo rilegge
    # i contatori gia' aggiornati.
    ora = clock.now()
    conn.execute("BEGIN IMMEDIATE")
    try:
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
    except BaseException:
        conn.rollback()
        raise
    return {
        "minuti": corpo.minuti,
        "residuo_giorno": residuo_giorno - corpo.minuti,
        "residuo_settimana": residuo_settimana - corpo.minuti,
    }
