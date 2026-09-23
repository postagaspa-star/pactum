"""Dichiarazioni del figlio sulle regole di vita reale (tappa 5), modello arbitro.
Fallimento = creduto sulla parola (va a registro). Successo = serve il verdetto del
genitore/arbitro: conferma, conferma per conto dell'arbitro (fuori dall'app) o
ribalta. Il registro conserva sempre chi ha garantito cosa."""

import json
import sqlite3
from datetime import date, timedelta

from fastapi import APIRouter, Depends, HTTPException

from .. import clock, famiglia
from ..auth import Identita, richiede_dispositivo, richiede_genitore, richiede_patto
from ..config import fuso_patto
from ..db import accoda_notifica, get_conn
from ..schemas import DichiarazioneIn, VerdettoIn

router = APIRouter()

ELENCO_MASSIMO = 50

# verdetto del genitore -> (nuovo stato della dichiarazione)
STATO_DA_VERDETTO = {
    "conferma": "confermata",
    "conferma_per_conto": "confermata_per_conto",
    "ribalta": "ribaltata",
}


def _verdetto(riga: sqlite3.Row) -> dict | None:
    if riga["verdetto_verdetto"] is None:
        return None
    return {
        "verdetto": riga["verdetto_verdetto"],
        "nota": riga["verdetto_nota"],
        "registro": riga["verdetto_registro"],
        "ts_server": riga["verdetto_ts"],
    }


def formatta_dichiarazione(riga: sqlite3.Row) -> dict:
    return {
        "id": riga["id"],
        "regola_id": riga["regola_id"],
        "giorno": riga["giorno"],
        "esito": riga["esito"],
        "nota": riga["nota"],
        "stato": riga["stato"],
        "ts_server": riga["ts_server"],
        "verdetto": _verdetto(riga),
    }


def _dichiarazione_o_404(conn: sqlite3.Connection, dichiarazione_id: int) -> sqlite3.Row:
    riga = conn.execute(
        "SELECT * FROM dichiarazioni WHERE id = ?", (dichiarazione_id,)
    ).fetchone()
    if riga is None:
        raise HTTPException(status_code=404, detail="dichiarazione non trovata")
    return riga


def _frase_registro(verdetto: str, arbitro_nome: str) -> str:
    """La frase leggibile del registro, congelata al momento del verdetto: si vede
    sempre chi ha garantito cosa (concept.md), anche se la regola cambia dopo."""
    if verdetto == "conferma_per_conto":
        return f"confermato dal genitore per conto di {arbitro_nome}"
    if verdetto == "conferma":
        return f"confermato da {arbitro_nome}"
    return "non confermato dal genitore: conta come non riuscito"


@router.post("/dichiarazioni")
def crea_dichiarazione(
    corpo: DichiarazioneIn,
    chi: Identita = Depends(richiede_dispositivo),
    conn: sqlite3.Connection = Depends(get_conn),
):
    regola = conn.execute(
        "SELECT tipo, parametri, attiva, figlio_id FROM regole WHERE id = ?", (corpo.regola_id,)
    ).fetchone()
    # (v3) La vita reale e' del figlio: si dichiara da qualsiasi suo dispositivo,
    # mai sulle regole di un altro figlio.
    if regola is not None and regola["figlio_id"] != chi.figlio_id:
        raise HTTPException(status_code=403, detail="regola di un altro figlio")
    if regola is None or not regola["attiva"] or regola["tipo"] != "vita_reale":
        raise HTTPException(status_code=409, detail={"errore": "regola_non_valida"})
    # (v2.1) l'arbitro si congela sulla dichiarazione: il verdetto "per conto di"
    # citera' l'arbitro di adesso anche se la regola cambia arbitro dopo.
    arbitro_nome = json.loads(regola["parametri"]).get("arbitro_nome", "arbitro")

    oggi = clock.now().astimezone(fuso_patto()).date()
    if corpo.giorno is None:
        giorno = oggi.isoformat()
    elif not clock.giorno_valido(corpo.giorno):
        raise HTTPException(status_code=422, detail="giorno non valido")
    else:
        giorno = corpo.giorno

    # (v2.1) giorno entro [oggi-7g, oggi] nel fuso del patto: niente dichiarazioni
    # nel futuro ne' piu' vecchie di una settimana.
    if not (oggi - timedelta(days=7) <= date.fromisoformat(giorno) <= oggi):
        raise HTTPException(status_code=409, detail={"errore": "giorno_non_valido"})

    gia = conn.execute(
        "SELECT 1 FROM dichiarazioni WHERE regola_id = ? AND giorno = ?",
        (corpo.regola_id, giorno),
    ).fetchone()
    if gia is not None:
        raise HTTPException(status_code=409, detail={"errore": "gia_dichiarato"})

    # Fallimento: creduto sulla parola -> registrata. Successo: serve il verdetto.
    stato = "registrata" if corpo.esito == "fallimento" else "in_attesa"
    ts = clock.iso(clock.now())
    try:
        cursore = conn.execute(
            "INSERT INTO dichiarazioni"
            " (regola_id, giorno, esito, nota, arbitro_nome, stato, ts_server)"
            " VALUES (?, ?, ?, ?, ?, ?, ?)",
            (corpo.regola_id, giorno, corpo.esito, corpo.nota, arbitro_nome, stato, ts),
        )
        dichiarazione_id = cursore.lastrowid
        accoda_notifica(
            conn,
            "dichiarazione",
            f"Dichiarazione del figlio: {corpo.esito} ({giorno})",
            {
                "dichiarazione_id": dichiarazione_id,
                "regola_id": corpo.regola_id,
                "esito": corpo.esito,
                "giorno": giorno,
            },
            ts,
            destinatario="genitore",
            figlio_id=chi.figlio_id,
            dispositivo_id=None,
        )
        conn.commit()
    except sqlite3.IntegrityError:
        # L'indice UNIQUE (regola_id, giorno) e' l'autorita' sotto concorrenza: due
        # POST simultanei superano entrambi la SELECT ma solo uno riesce a inserire.
        conn.rollback()
        raise HTTPException(status_code=409, detail={"errore": "gia_dichiarato"})
    return formatta_dichiarazione(_dichiarazione_o_404(conn, dichiarazione_id))


def dichiarazioni_del_figlio(
    conn: sqlite3.Connection, figlio_id: int, solo_in_attesa: bool = False
) -> list[sqlite3.Row]:
    """(v3) Le dichiarazioni sulle regole di vita reale del figlio, dalla piu' recente."""
    filtro = " AND d.stato = 'in_attesa'" if solo_in_attesa else ""
    limite = "" if solo_in_attesa else f" LIMIT {ELENCO_MASSIMO}"
    return conn.execute(
        "SELECT d.* FROM dichiarazioni d JOIN regole r ON r.id = d.regola_id"
        f" WHERE r.figlio_id = ?{filtro} ORDER BY d.id DESC{limite}",
        (figlio_id,),
    ).fetchall()


@router.get("/dichiarazioni")
def elenca_dichiarazioni(
    figlio_id: int | None = None,
    chi: Identita = Depends(richiede_patto),
    conn: sqlite3.Connection = Depends(get_conn),
):
    if chi.ruolo == "dispositivo":
        figlio = chi.figlio_id
    else:
        figlio = famiglia.figlio_scelto(conn, figlio_id)["id"]
    return {
        "dichiarazioni": [formatta_dichiarazione(r) for r in dichiarazioni_del_figlio(conn, figlio)]
    }


@router.post("/dichiarazioni/{dichiarazione_id}/verdetto")
def emetti_verdetto(
    dichiarazione_id: int,
    corpo: VerdettoIn,
    chi: Identita = Depends(richiede_genitore),
    conn: sqlite3.Connection = Depends(get_conn),
):
    if corpo.figlio_id is not None:
        famiglia.figlio_o_404(conn, corpo.figlio_id)
    ts = clock.iso(clock.now())
    # BEGIN IMMEDIATE: controllo "in attesa" e verdetto sono un unico atto, cosi'
    # un doppio tocco non scrive due verdetti (ne' due notifiche).
    conn.execute("BEGIN IMMEDIATE")
    try:
        dichiarazione = _dichiarazione_o_404(conn, dichiarazione_id)
        regola = conn.execute(
            "SELECT parametri, figlio_id, dispositivo_id FROM regole WHERE id = ?",
            (dichiarazione["regola_id"],),
        ).fetchone()
        if corpo.figlio_id is not None and regola["figlio_id"] != corpo.figlio_id:
            raise HTTPException(status_code=404, detail="dichiarazione non trovata")
        if dichiarazione["stato"] != "in_attesa":
            raise HTTPException(status_code=409, detail={"errore": "dichiarazione_non_in_attesa"})

        # (v2.1) l'arbitro e' quello CONGELATO sulla dichiarazione, non quello attuale
        # della regola: la frase del registro cita chi era l'arbitro quando il figlio
        # dichiaro'. Le righe vecchie (senza congelamento) ricadono sull'arbitro corrente.
        arbitro_nome = dichiarazione["arbitro_nome"]
        if arbitro_nome is None:
            arbitro_nome = json.loads(regola["parametri"]).get("arbitro_nome", "arbitro")
        registro = _frase_registro(corpo.verdetto, arbitro_nome)

        conn.execute(
            "UPDATE dichiarazioni SET stato = ?,"
            " verdetto_verdetto = ?, verdetto_nota = ?, verdetto_registro = ?, verdetto_ts = ?"
            " WHERE id = ?",
            (STATO_DA_VERDETTO[corpo.verdetto], corpo.verdetto, corpo.nota, registro, ts,
             dichiarazione_id),
        )
        accoda_notifica(
            conn,
            "verdetto",
            f"Esito della tua dichiarazione: {registro}",
            {
                "dichiarazione_id": dichiarazione_id,
                "regola_id": dichiarazione["regola_id"],
                "verdetto": corpo.verdetto,
            },
            ts,
            destinatario="figlio",
            figlio_id=regola["figlio_id"],
            dispositivo_id=regola["dispositivo_id"],
        )
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    return formatta_dichiarazione(_dichiarazione_o_404(conn, dichiarazione_id))
