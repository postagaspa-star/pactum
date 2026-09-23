"""Proposte del genitore (tappa 5). Il genitore propone, non impone: il server
calcola il confronto col valore attuale (per la notifica al figlio) e, quando il
figlio ACCETTA, applica da solo la modifica concordata (lock bypassato, parametri
esatti, concordata=true nello storico). Niente secondo passaggio dall'app."""

import json
import sqlite3

from fastapi import APIRouter, Depends, HTTPException

from .. import clock, confronto, famiglia
from ..auth import Identita, richiede_dispositivo, richiede_genitore, richiede_patto
from ..db import accoda_notifica, get_conn
from ..schemas import ProponiIn, RispostaPropostaIn
from .regole import (
    MARCATORE_ELIMINA,
    _valida_o_422,
    applica_eliminazione,
    applica_modifica,
    formatta_regola,
    regola_del_figlio_o_errore,
    tipo_dispositivo,
    verifica_non_ultima,
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
    chi: Identita = Depends(richiede_genitore),
    conn: sqlite3.Connection = Depends(get_conn),
):
    if corpo.figlio_id is not None:
        famiglia.figlio_o_404(conn, corpo.figlio_id)
    riga = conn.execute(
        "SELECT * FROM regole WHERE id = ? AND attiva = 1", (corpo.regola_id,)
    ).fetchone()
    if riga is None or (corpo.figlio_id is not None and riga["figlio_id"] != corpo.figlio_id):
        # Regola inesistente, non attiva o (v3) non di quel figlio: non c'e' niente
        # da proporre.
        raise HTTPException(status_code=409, detail={"errore": "regola_non_valida"})

    if corpo.parametri_proposti == MARCATORE_ELIMINA:
        parametri = MARCATORE_ELIMINA
        testo = "propone di eliminare la regola"
        direzione = "elimina"
    else:
        parametri = _valida_o_422(riga["tipo"], corpo.parametri_proposti, tipo_dispositivo(conn, riga))
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
            # (v3) Sulla regola di un dispositivo: la vede quel dispositivo. Sulla
            # vita reale (dispositivo NULL): tutti i dispositivi del figlio.
            figlio_id=riga["figlio_id"],
            dispositivo_id=riga["dispositivo_id"],
        )
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    return formatta_proposta(_proposta_o_404(conn, proposta_id), conn)


def proposte_del_figlio(
    conn: sqlite3.Connection, figlio_id: int, solo_pendenti: bool = False
) -> list[sqlite3.Row]:
    """(v3) Le proposte sulle regole del figlio (di tutti i suoi dispositivi), dalla
    piu' recente: una proposta su una regola del computer si vede e si accetta
    anche dal telefono."""
    filtro = " AND p.stato = 'pendente'" if solo_pendenti else ""
    limite = "" if solo_pendenti else f" LIMIT {ELENCO_MASSIMO}"
    return conn.execute(
        "SELECT p.* FROM proposte p JOIN regole r ON r.id = p.regola_id"
        f" WHERE r.figlio_id = ?{filtro} ORDER BY p.id DESC{limite}",
        (figlio_id,),
    ).fetchall()


@router.get("/proposte")
def elenca_proposte(
    figlio_id: int | None = None,
    chi: Identita = Depends(richiede_patto),
    conn: sqlite3.Connection = Depends(get_conn),
):
    if chi.ruolo == "dispositivo":
        figlio = chi.figlio_id
    else:
        figlio = famiglia.figlio_scelto(conn, figlio_id)["id"]
    # conn passato: il confronto delle pendenti si ricalcola vs la regola attuale (v2.1).
    return {"proposte": [formatta_proposta(r, conn) for r in proposte_del_figlio(conn, figlio)]}


@router.post("/proposte/{proposta_id}/risposta")
def rispondi_proposta(
    proposta_id: int,
    corpo: RispostaPropostaIn,
    chi: Identita = Depends(richiede_dispositivo),
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
        della_regola = conn.execute(
            "SELECT figlio_id, dispositivo_id FROM regole WHERE id = ?", (proposta["regola_id"],)
        ).fetchone()
        # (v3) Si risponde alle proposte del proprio figlio, di qualsiasi suo dispositivo.
        if della_regola["figlio_id"] != chi.figlio_id:
            raise HTTPException(status_code=403, detail="proposta per un altro figlio")
        dispositivo_della_regola = della_regola["dispositivo_id"]
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
            riga = regola_del_figlio_o_errore(conn, proposta["regola_id"], chi.figlio_id)
            if parametri == MARCATORE_ELIMINA:
                verifica_non_ultima(conn, chi.figlio_id)  # eliminare l'ultima regola resta vietato
                applica_eliminazione(conn, riga, concordata=True, ora=ora)
                regola_risultante = None
            else:
                aggiornata = applica_modifica(conn, riga, parametri, concordata=True, ora=ora)
                regola_risultante = formatta_regola(conn, aggiornata)
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
            figlio_id=chi.figlio_id,
            dispositivo_id=dispositivo_della_regola,
        )
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    return {"proposta": formatta_proposta(_proposta_o_404(conn, proposta_id), conn), "regola": regola_risultante}
