"""Proposte del genitore (tappa 5). Il genitore propone, non impone: il server
calcola il confronto col valore attuale (per la notifica al figlio) e, quando il
figlio ACCETTA, applica da solo la modifica concordata (lock bypassato, parametri
esatti, concordata=true nello storico). Niente secondo passaggio dall'app."""

import json
import sqlite3

from fastapi import APIRouter, Depends, HTTPException
from pydantic import ValidationError

from .. import clock, confronto
from ..auth import richiede_figlio, richiede_genitore, richiede_patto
from ..db import accoda_notifica, get_conn
from ..schemas import ProponiIn, RispostaPropostaIn, valida_parametri
from .regole import (
    MARCATORE_ELIMINA,
    _regola_attiva_o_404,
    _riga_regola,
    _verifica_non_ultima,
    applica_eliminazione,
    applica_modifica,
)

router = APIRouter()

ELENCO_MASSIMO = 50


def _risposta(riga: sqlite3.Row) -> dict | None:
    if riga["risposta_esito"] is None:
        return None
    return {
        "esito": riga["risposta_esito"],
        "motivazione": riga["risposta_motivazione"],
        "ts_server": riga["risposta_ts"],
    }


def _confronto_vivo(
    conn: sqlite3.Connection, regola_id: int, parametri_proposti: dict | None
) -> tuple[str, str] | None:
    """Il confronto ricalcolato vs i parametri ATTUALI della regola (v2.1): serve
    perche' il figlio decida sempre su un confronto vero anche se la regola e'
    cambiata dopo la proposta. None se non si puo' ricalcolare (regola sparita o
    parametri assenti): si tiene allora il confronto congelato."""
    if parametri_proposti is None:
        return None
    regola = conn.execute(
        "SELECT tipo, parametri FROM regole WHERE id = ? AND attiva = 1", (regola_id,)
    ).fetchone()
    if regola is None:
        return None
    if parametri_proposti == MARCATORE_ELIMINA:
        return "propone di eliminare la regola", "elimina"
    return confronto.confronto_e_direzione(
        regola["tipo"], json.loads(regola["parametri"]), parametri_proposti
    )


def formatta_proposta(riga: sqlite3.Row, conn: sqlite3.Connection | None = None) -> dict:
    parametri_proposti = (
        json.loads(riga["parametri_proposti"]) if riga["parametri_proposti"] else None
    )
    confronto_txt = riga["confronto"]
    direzione = riga["direzione"]
    # Solo le pendenti si ricalcolano in lettura (v2.1): le chiuse conservano il
    # confronto congelato al momento della risposta.
    if conn is not None and riga["stato"] == "pendente":
        vivo = _confronto_vivo(conn, riga["regola_id"], parametri_proposti)
        if vivo is not None:
            confronto_txt, direzione = vivo
    return {
        "id": riga["id"],
        "regola_id": riga["regola_id"],
        "parametri_proposti": parametri_proposti,
        "motivazione": riga["motivazione"],
        "confronto": confronto_txt,
        "direzione": direzione,
        "stato": riga["stato"],
        "usata": bool(riga["usata"]),
        "ts_server": riga["ts_server"],
        "risposta": _risposta(riga),
    }


def _proposta_o_404(conn: sqlite3.Connection, proposta_id: int) -> sqlite3.Row:
    riga = conn.execute("SELECT * FROM proposte WHERE id = ?", (proposta_id,)).fetchone()
    if riga is None:
        raise HTTPException(status_code=404, detail="proposta non trovata")
    return riga


@router.post("/proposte")
def crea_proposta(
    corpo: ProponiIn,
    ruolo: str = Depends(richiede_genitore),
    conn: sqlite3.Connection = Depends(get_conn),
):
    riga = conn.execute(
        "SELECT * FROM regole WHERE id = ? AND attiva = 1", (corpo.regola_id,)
    ).fetchone()
    if riga is None:
        # Regola inesistente o non attiva: non c'e' niente da proporre.
        raise HTTPException(status_code=409, detail={"errore": "regola_non_valida"})

    if corpo.parametri_proposti == MARCATORE_ELIMINA:
        parametri = MARCATORE_ELIMINA
        testo = "propone di eliminare la regola"
        direzione = "elimina"
    else:
        try:
            parametri = valida_parametri(riga["tipo"], corpo.parametri_proposti)
        except ValidationError as errore:
            raise HTTPException(
                status_code=422,
                detail=[{"loc": list(e["loc"]), "msg": e["msg"]} for e in errore.errors()],
            )
        testo, direzione = confronto.confronto_e_direzione(
            riga["tipo"], json.loads(riga["parametri"]), parametri
        )

    # BEGIN IMMEDIATE: il controllo "una sola pendente per regola" deve essere
    # atomico, altrimenti due POST simultanei leggono entrambi "nessuna pendente"
    # e ne creano due. Il lock di scrittura serializza i concorrenti; chi arriva
    # secondo rilegge la pendente gia' creata e viene respinto.
    ts = clock.iso(clock.now())
    conn.execute("BEGIN IMMEDIATE")
    try:
        gia_pendente = conn.execute(
            "SELECT 1 FROM proposte WHERE regola_id = ? AND stato = 'pendente'",
            (corpo.regola_id,),
        ).fetchone()
        if gia_pendente is not None:
            raise HTTPException(status_code=409, detail={"errore": "proposta_gia_pendente"})
        cursore = conn.execute(
            "INSERT INTO proposte"
            " (regola_id, parametri_proposti, motivazione, confronto, direzione, stato, usata, ts_server)"
            " VALUES (?, ?, ?, ?, ?, 'pendente', 0, ?)",
            (corpo.regola_id, json.dumps(parametri), corpo.motivazione, testo, direzione, ts),
        )
        proposta_id = cursore.lastrowid
        accoda_notifica(
            conn,
            "nuova_proposta",
            f"Nuova proposta del genitore: {testo}",
            {
                "proposta_id": proposta_id,
                "regola_id": corpo.regola_id,
                "confronto": testo,
                "direzione": direzione,
            },
            ts,
            destinatario="figlio",
        )
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    return formatta_proposta(_proposta_o_404(conn, proposta_id), conn)


@router.get("/proposte")
def elenca_proposte(
    ruolo: str = Depends(richiede_patto), conn: sqlite3.Connection = Depends(get_conn)
):
    righe = conn.execute(
        "SELECT * FROM proposte ORDER BY id DESC LIMIT ?", (ELENCO_MASSIMO,)
    ).fetchall()
    # conn passato: il confronto delle pendenti si ricalcola vs la regola attuale (v2.1).
    return {"proposte": [formatta_proposta(r, conn) for r in righe]}


@router.post("/proposte/{proposta_id}/risposta")
def rispondi_proposta(
    proposta_id: int,
    corpo: RispostaPropostaIn,
    ruolo: str = Depends(richiede_figlio),
    conn: sqlite3.Connection = Depends(get_conn),
):
    ora = clock.now()
    ts = clock.iso(ora)
    regola_risultante = None

    # BEGIN IMMEDIATE: leggi-stato, applica-modifica e chiudi-proposta devono
    # essere un unico atto. Senza, due "accetta" simultanei leggono entrambi
    # 'pendente' e applicano due volte (o, con due elimina su regole diverse,
    # svuotano il patto). Il lock serializza; chi arriva secondo rilegge lo stato
    # gia' aggiornato (proposta non piu' pendente, conteggio regole attive nuovo).
    conn.execute("BEGIN IMMEDIATE")
    try:
        proposta = _proposta_o_404(conn, proposta_id)
        if proposta["stato"] != "pendente":
            raise HTTPException(status_code=409, detail={"errore": "proposta_non_pendente"})

        parametri = (
            json.loads(proposta["parametri_proposti"]) if proposta["parametri_proposti"] else None
        )
        # Confronto del momento della risposta (v2.1): si congela sulla proposta
        # chiusa il confronto vs la regola com'e' ORA, prima di applicare l'accetta.
        vivo = _confronto_vivo(conn, proposta["regola_id"], parametri)
        confronto_txt, direzione = vivo if vivo is not None else (proposta["confronto"], proposta["direzione"])

        if corpo.esito == "accetta":
            # Auto-applicazione atomica della modifica concordata: lock bypassato,
            # parametri esatti della proposta, concordata=true nello storico.
            riga = _regola_attiva_o_404(conn, proposta["regola_id"])
            if parametri == MARCATORE_ELIMINA:
                _verifica_non_ultima(conn)  # eliminare l'ultima regola resta vietato
                applica_eliminazione(conn, riga, concordata=True, ora=ora)
                regola_risultante = None
            else:
                aggiornata = applica_modifica(conn, riga, parametri, concordata=True, ora=ora)
                regola_risultante = _riga_regola(aggiornata)
            stato = "accettata"
            usata = 1
        else:
            stato = "rifiutata"
            usata = 0

        conn.execute(
            "UPDATE proposte SET stato = ?, usata = ?, confronto = ?, direzione = ?,"
            " risposta_esito = ?, risposta_motivazione = ?, risposta_ts = ? WHERE id = ?",
            (stato, usata, confronto_txt, direzione, corpo.esito, corpo.motivazione, ts, proposta_id),
        )
        accoda_notifica(
            conn,
            "proposta_risposta",
            f"Il figlio ha risposto alla proposta: {corpo.esito}",
            {"proposta_id": proposta_id, "regola_id": proposta["regola_id"], "esito": corpo.esito},
            ts,
            destinatario="genitore",
        )
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    return {"proposta": formatta_proposta(_proposta_o_404(conn, proposta_id), conn), "regola": regola_risultante}
