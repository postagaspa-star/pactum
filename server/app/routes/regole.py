import json
import sqlite3
from datetime import datetime, timedelta

from fastapi import APIRouter, Depends, HTTPException
from pydantic import ValidationError

from .. import clock, lock
from ..auth import richiede_figlio, richiede_patto
from ..config import LOCK_GIORNI
from ..db import accoda_notifica, get_conn, registra_modifica
from ..schemas import RegolaCrea, RegolaPatch, valida_parametri

router = APIRouter()


def _valida_o_422(tipo: str, parametri: dict) -> dict:
    try:
        return valida_parametri(tipo, parametri)
    except ValidationError as errore:
        raise HTTPException(
            status_code=422,
            detail=[{"loc": list(e["loc"]), "msg": e["msg"]} for e in errore.errors()],
        )


def _riga_regola(riga: sqlite3.Row) -> dict:
    sblocco = datetime.fromisoformat(riga["ultima_modifica_ts"]) + timedelta(days=LOCK_GIORNI)
    return {
        "id": riga["id"],
        "tipo": riga["tipo"],
        "parametri": json.loads(riga["parametri"]),
        "attiva": bool(riga["attiva"]),
        "creata_ts": riga["creata_ts"],
        "ultima_modifica_ts": riga["ultima_modifica_ts"],
        "allentabile_dal": clock.iso(sblocco),
    }


def _regola_attiva_o_404(conn: sqlite3.Connection, regola_id: int) -> sqlite3.Row:
    riga = conn.execute(
        "SELECT * FROM regole WHERE id = ? AND attiva = 1", (regola_id,)
    ).fetchone()
    if riga is None:
        raise HTTPException(status_code=404, detail="regola non trovata")
    return riga


def _controlla_lock(riga: sqlite3.Row, ora: datetime) -> None:
    sblocco = datetime.fromisoformat(riga["ultima_modifica_ts"]) + timedelta(days=LOCK_GIORNI)
    if ora < sblocco:
        raise HTTPException(
            status_code=409,
            detail={
                "errore": "lock_attivo",
                "secondi_rimanenti": int((sblocco - ora).total_seconds()),
                "sblocco_ts": clock.iso(sblocco),
            },
        )


# Marcatore dei parametri_proposti per una proposta di ELIMINAZIONE (db.py):
# il DELETE concordato vale solo se la proposta accettata dice esattamente questo.
MARCATORE_ELIMINA = {"azione": "elimina"}


def _consuma_proposta(
    conn: sqlite3.Connection, proposta_id: int, regola_id: int, parametri_attesi: dict
) -> None:
    """Una modifica e' concordata solo se nasce da una proposta accettata e mai usata,
    e applica ESATTAMENTE i parametri concordati: il proposta_id sblocca il lock dei
    4 giorni solo per quei parametri, non per quello che il client decide di mandare.
    Parametri diversi -> 409 parametri_non_concordati e la proposta NON si consuma."""
    riga = conn.execute("SELECT * FROM proposte WHERE id = ?", (proposta_id,)).fetchone()
    if (
        riga is None
        or riga["regola_id"] != regola_id
        or riga["stato"] != "accettata"
        or riga["usata"]
    ):
        raise HTTPException(status_code=400, detail={"errore": "proposta_non_valida"})
    proposti = json.loads(riga["parametri_proposti"]) if riga["parametri_proposti"] else None
    if proposti != parametri_attesi:
        raise HTTPException(status_code=409, detail={"errore": "parametri_non_concordati"})
    conn.execute("UPDATE proposte SET usata = 1 WHERE id = ?", (proposta_id,))


@router.get("/regole")
def elenca_regole(
    ruolo: str = Depends(richiede_patto), conn: sqlite3.Connection = Depends(get_conn)
):
    righe = conn.execute("SELECT * FROM regole WHERE attiva = 1 ORDER BY id").fetchall()
    return {"regole": [_riga_regola(r) for r in righe]}


@router.post("/regole", status_code=201)
def crea_regola(
    corpo: RegolaCrea,
    ruolo: str = Depends(richiede_figlio),
    conn: sqlite3.Connection = Depends(get_conn),
):
    parametri = _valida_o_422(corpo.tipo, corpo.parametri)
    ts = clock.iso(clock.now())
    cursore = conn.execute(
        "INSERT INTO regole (tipo, parametri, attiva, creata_ts, ultima_modifica_ts)"
        " VALUES (?, ?, 1, ?, ?)",
        (corpo.tipo, json.dumps(parametri), ts, ts),
    )
    regola_id = cursore.lastrowid
    registra_modifica(conn, regola_id, "creazione", None, None, parametri, False, ts)
    accoda_notifica(
        conn,
        "modifica_regola",
        f"Nuova regola {corpo.tipo} creata",
        {"regola_id": regola_id, "azione": "creazione", "parametri": parametri},
        ts,
    )
    conn.commit()
    riga = conn.execute("SELECT * FROM regole WHERE id = ?", (regola_id,)).fetchone()
    return _riga_regola(riga)


@router.patch("/regole/{regola_id}")
def modifica_regola(
    regola_id: int,
    corpo: RegolaPatch,
    ruolo: str = Depends(richiede_figlio),
    conn: sqlite3.Connection = Depends(get_conn),
):
    riga = _regola_attiva_o_404(conn, regola_id)
    parametri_prima = json.loads(riga["parametri"])
    parametri_dopo = _valida_o_422(riga["tipo"], corpo.parametri)
    ora = clock.now()

    # La proposta serve SOLO a scavalcare il lock di un allentamento:
    # su una stretta (gia' immediata) il proposta_id si ignora e non si consuma.
    concordata = False
    e_allentamento = lock.allenta(riga["tipo"], parametri_prima, parametri_dopo)
    if e_allentamento:
        if corpo.proposta_id is not None:
            _consuma_proposta(conn, corpo.proposta_id, regola_id, parametri_dopo)
            concordata = True
        else:
            _controlla_lock(riga, ora)

    ts = clock.iso(ora)
    direzione = "allenta" if e_allentamento else "stringe"
    conn.execute(
        "UPDATE regole SET parametri = ?, ultima_modifica_ts = ? WHERE id = ?",
        (json.dumps(parametri_dopo), ts, regola_id),
    )
    registra_modifica(
        conn, regola_id, "modifica", direzione, parametri_prima, parametri_dopo, concordata, ts
    )
    accoda_notifica(
        conn,
        "modifica_regola",
        f"Regola {regola_id} ({riga['tipo']}) modificata ({direzione})",
        {
            "regola_id": regola_id,
            "azione": "modifica",
            "direzione": direzione,
            "concordata": concordata,
            "prima": parametri_prima,
            "dopo": parametri_dopo,
        },
        ts,
    )
    conn.commit()
    aggiornata = conn.execute("SELECT * FROM regole WHERE id = ?", (regola_id,)).fetchone()
    return _riga_regola(aggiornata)


@router.delete("/regole/{regola_id}")
def elimina_regola(
    regola_id: int,
    proposta_id: int | None = None,
    ruolo: str = Depends(richiede_figlio),
    conn: sqlite3.Connection = Depends(get_conn),
):
    riga = _regola_attiva_o_404(conn, regola_id)
    attive = conn.execute("SELECT COUNT(*) AS n FROM regole WHERE attiva = 1").fetchone()["n"]
    if attive <= 1:
        # concept.md: almeno una regola obbligatoria.
        raise HTTPException(status_code=409, detail={"errore": "ultima_regola"})

    ora = clock.now()
    concordata = False
    if proposta_id is not None:
        # L'eliminazione concordata vale solo se la proposta accettata era
        # proprio un'eliminazione (marcatore {"azione": "elimina"}, db.py).
        _consuma_proposta(conn, proposta_id, regola_id, MARCATORE_ELIMINA)
        concordata = True
    if not concordata:
        _controlla_lock(riga, ora)  # eliminare = sempre allentare

    ts = clock.iso(ora)
    parametri_prima = json.loads(riga["parametri"])
    conn.execute(
        "UPDATE regole SET attiva = 0, ultima_modifica_ts = ? WHERE id = ?", (ts, regola_id)
    )
    registra_modifica(
        conn, regola_id, "eliminazione", "allenta", parametri_prima, None, concordata, ts
    )
    accoda_notifica(
        conn,
        "modifica_regola",
        f"Regola {regola_id} ({riga['tipo']}) eliminata",
        {
            "regola_id": regola_id,
            "azione": "eliminazione",
            "concordata": concordata,
            "prima": parametri_prima,
        },
        ts,
    )
    conn.commit()
    return {"id": regola_id, "eliminata": True}
