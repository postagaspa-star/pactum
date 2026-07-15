"""Endpoint del genitore: la finestra (non vetrata) e le notifiche a polling.
Il silenzio si calcola in lettura: nessun job in background nella v1."""

import json
import sqlite3
from collections import defaultdict
from datetime import datetime, timedelta

from fastapi import APIRouter, Depends

from .. import clock
from ..auth import richiede_genitore
from ..config import SOGLIA_SILENZIO_MINUTI, fuso_patto
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
        "dettagli": json.loads(riga["dettagli"]),
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


def _data_locale(ts_server: str, tz) -> str:
    """Il giorno LOCALE (fuso del patto) di un ts_server UTC ISO."""
    return datetime.fromisoformat(ts_server).astimezone(tz).date().isoformat()


@router.get("/finestra")
def finestra(conn: sqlite3.Connection = Depends(get_conn)):
    ora = clock.now()
    tz = fuso_patto()
    # I giorni della finestra sono giorni LOCALI del patto (contratto-api.md):
    # in UTC il confine cadrebbe alle 02:00 locali italiane.
    oggi = ora.astimezone(tz).date()
    giorni = [oggi - timedelta(days=n) for n in range(GIORNI_SEMAFORO, -1, -1)]

    eventi = conn.execute(
        "SELECT * FROM eventi WHERE tipo IN ('sforamento', 'manomissione')"
        " ORDER BY ts_server DESC, id DESC"
    ).fetchall()

    sforamenti_per_regola = defaultdict(set)  # regola_id -> {data ISO locale}
    for evento in eventi:
        dettagli = json.loads(evento["dettagli"])
        regola_id = dettagli.get("regola_id")
        if regola_id is None:
            continue
        if evento["tipo"] == "sforamento":
            sforamenti_per_regola[regola_id].add(_data_locale(evento["ts_server"], tz))

    # Semaforo a tre colori: verde/rosso/grigio. Il giallo non esiste piu':
    # il bonus autoritativo vive nella tabella bonus (senza regola_id) e viene
    # riassunto per giorno in bonus_giornalieri, non appeso a una regola.
    regole = []
    for riga in conn.execute("SELECT * FROM regole ORDER BY id").fetchall():
        creata = _data_locale(riga["creata_ts"], tz)
        eliminata = None
        if not riga["attiva"]:
            # Soft-delete: i giorni STRETTAMENTE successivi all'eliminazione
            # sono fuori dalla vita della regola -> grigio, non verde.
            eliminata = _data_locale(riga["ultima_modifica_ts"], tz)
        semaforo = []
        for giorno in giorni:
            data = giorno.isoformat()
            if data < creata or (eliminata is not None and data > eliminata):
                stato = "grigio"
            elif data in sforamenti_per_regola[riga["id"]]:
                stato = "rosso"
            else:
                stato = "verde"
            semaforo.append({"data": data, "stato": stato})
        regole.append({**_riga_regola(riga), "semaforo": semaforo})

    # Riepilogo bonus per giorno (globale, stessa finestra di 8 giorni):
    # dalla tabella bonus autoritativa, coi giorni nel fuso del patto.
    minuti_per_giorno = defaultdict(int)
    for riga in conn.execute("SELECT minuti, ts_server FROM bonus").fetchall():
        minuti_per_giorno[_data_locale(riga["ts_server"], tz)] += riga["minuti"]
    bonus_giornalieri = [
        {"giorno": g.isoformat(), "minuti": minuti_per_giorno[g.isoformat()]} for g in giorni
    ]

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
        "bonus_giornalieri": bonus_giornalieri,
        "stato_silenzio": _stato_silenzio(conn, ora),
    }
