"""(v4.0) Gli endpoint della Sessione Studio (contratto-api.md, "v4.0 — C. La Sessione
Studio"). La logica sta in app/studio.py; qui i controlli d'accesso, l'ordine dei
controlli e le transazioni.

- Ogni richiesta prima di rispondere elabora le partenze passate e le mezzanotti del
  figlio (studio.valuta per le letture, studio.elabora dentro il lock per le scritture).
- Ogni scrittura sta in BEGIN IMMEDIATE e ricontrolla la revoca (del dispositivo: 409
  dispositivo_revocato; del genitore: 401). La proposta della configurazione legge e
  riscrive la proposta in attesa dentro il lock: telefono e computer che propongono
  insieme non si cancellano a vicenda.
- Uno Studio che non c'e', o di un altro figlio: 404 {"detail": "studio non trovato"}.
- La chiusura del figlio scrive i tratti del corpo PRIMA dei controlli, nella stessa
  transazione, anche se poi la chiusura e' rifiutata (404 o 409): i tratti restano."""

import sqlite3
from typing import Any

from fastapi import APIRouter, Body, Depends, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ValidationError

from .. import clock, famiglia, genitori, studio
from ..auth import Identita, richiede_dispositivo, richiede_genitore, richiede_patto
from ..controllo_corpo import INTERO_MASSIMO, INTERO_MINIMO
from ..db import get_conn
from ..schemas import (
    AvviaStudioIn,
    ChiudiStudioGenitoreIn,
    ChiudiStudioIn,
    RispostaStudioIn,
    StudioConfigPatch,
    TrattiIn,
)

router = APIRouter()


def _figlio(conn: sqlite3.Connection, chi: Identita, figlio_id: int | None) -> int:
    """Per un dispositivo il figlio del token (un figlio_id si ignora); per il genitore
    quello di `figlio_id`, o il primo (404 se non c'e')."""
    if chi.ruolo == "dispositivo":
        return chi.figlio_id
    return famiglia.figlio_scelto(conn, figlio_id)["id"]


def _dispositivo_vivo(conn: sqlite3.Connection, chi: Identita) -> sqlite3.Row:
    riga = conn.execute("SELECT * FROM dispositivi WHERE id = ?", (chi.dispositivo_id,)).fetchone()
    if riga is None or riga["revocato_ts"] is not None:
        raise HTTPException(status_code=409, detail={"errore": "dispositivo_revocato"})
    return riga


def _telefono_dalla_018(conn: sqlite3.Connection, dispositivo: sqlite3.Row) -> None:
    """(Correzione) Solo un'app dalla 0.18 conosce /api/studio: una scrittura dello Studio
    dal telefono prova che lo e', anche prima del suo primo battito con la versione nuova
    (il telefono appena aggiornato che avvia subito lo Studio). Senza, lo Studio nasceva
    ma restava invisibile (in_corso null in patto, blocco e /api/studio, il computer non
    entrava in Studio) fino al battito. Si scrive la versione minima che la chiamata
    prova; il battito dopo scrive quella vera."""
    from ..faccende import conosce_lo_studio

    if not conosce_lo_studio(dispositivo["versione_app"]):
        conn.execute("UPDATE dispositivi SET versione_app = '0.18' WHERE id = ?", (dispositivo["id"],))


def _solo_dal_telefono(chi: Identita) -> None:
    if chi.tipo != "telefono":
        raise HTTPException(status_code=422, detail={"errore": "solo_dal_telefono"})


def _valida(modello: type[BaseModel], corpo: Any) -> BaseModel:
    """Il corpo letto come `modello`, con gli stessi 422 di FastAPI."""
    try:
        return modello.model_validate(corpo)
    except ValidationError as errore:
        raise RequestValidationError(
            [{"loc": ("body", *e["loc"]), "msg": e["msg"], "type": e["type"]} for e in errore.errors()]
        )


# --- le letture ---

@router.get("/studio")
def leggi_studio(
    figlio_id: int | None = None,
    chi: Identita = Depends(richiede_patto),
    conn: sqlite3.Connection = Depends(get_conn),
):
    """Per il dispositivo e per il genitore: la configurazione, lo Studio in corso (con i
    tratti e le liste), le prossime partenze e gli Studi che toccano le ultime 48 ore (al
    telefono servono a riconoscere gli Studi fatti senza rete)."""
    figlio = _figlio(conn, chi, figlio_id)
    ora = clock.now()
    studio.valuta(conn, figlio, ora)
    return {
        **studio.vista_per_i_dispositivi(conn, figlio, ora),
        "recenti": studio.recenti(conn, figlio, ora),
    }


@router.get("/studio/svolte")
def leggi_svolte(
    figlio_id: int | None = None,
    prima_di: int | None = None,
    chi: Identita = Depends(richiede_patto),
    conn: sqlite3.Connection = Depends(get_conn),
):
    """Lo storico degli Studi, dal piu' recente, 20 per volta (`prima_di`: l'id da cui
    continuare). Uguale per figlio e genitore."""
    figlio = _figlio(conn, chi, figlio_id)
    ora = clock.now()
    studio.valuta(conn, figlio, ora)
    return studio.pagina_di_svolte(conn, figlio, ora, prima_di)


@router.get("/studio/versioni")
def leggi_versioni(
    figlio_id: int | None = None,
    chi: Identita = Depends(richiede_patto),
    conn: sqlite3.Connection = Depends(get_conn),
):
    """Tutte le configurazioni approvate, dalla piu' recente, con chi le ha approvate."""
    figlio = _figlio(conn, chi, figlio_id)
    studio.valuta(conn, figlio)
    return {"versioni": studio.formatta_versioni(conn, figlio)}


# --- la configurazione ---

@router.patch("/studio/config")
def proponi_config(
    corpo: StudioConfigPatch,
    chi: Identita = Depends(richiede_dispositivo),
    conn: sqlite3.Connection = Depends(get_conn),
):
    """Il figlio propone (o cambia, o ritira) la configurazione dello Studio. Un exe: di
    un browser nella lista del computer -> 422 browser_nella_lista: nei browser si
    elencano i siti."""
    if corpo.computer is not None and studio.browser_in_lista(corpo.computer.programmi):
        raise HTTPException(status_code=422, detail={"errore": "browser_nella_lista"})
    ora = clock.now()
    conn.execute("BEGIN IMMEDIATE")
    try:
        _dispositivo_vivo(conn, chi)
        studio.elabora(conn, chi.figlio_id, ora)
        studio.segna_giro(conn, ora)
        studio.proponi(conn, chi, corpo, ora)
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    return studio.formatta_config(conn, chi.figlio_id)


@router.delete("/studio/config/proposta")
def ritira_proposta(
    chi: Identita = Depends(richiede_dispositivo),
    conn: sqlite3.Connection = Depends(get_conn),
):
    ora = clock.now()
    conn.execute("BEGIN IMMEDIATE")
    try:
        _dispositivo_vivo(conn, chi)
        studio.elabora(conn, chi.figlio_id, ora)
        studio.segna_giro(conn, ora)
        studio.ritira(conn, chi.figlio_id)
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    return studio.formatta_config(conn, chi.figlio_id)


@router.post("/studio/config/risposta")
def rispondi_config(
    corpo: RispostaStudioIn,
    chi: Identita = Depends(richiede_genitore),
    conn: sqlite3.Connection = Depends(get_conn),
):
    """Il genitore approva o rifiuta la configurazione che ha sullo schermo (`versione`)."""
    ora = clock.now()
    conn.execute("BEGIN IMMEDIATE")
    try:
        genitori.ancora_valido(conn, chi)
        figlio = famiglia.figlio_scelto(conn, corpo.figlio_id)["id"]
        studio.elabora(conn, figlio, ora)
        studio.segna_giro(conn, ora)
        studio.rispondi(conn, chi, figlio, corpo, ora)
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    return studio.formatta_config(conn, figlio)


# --- i tratti, l'avvio, le chiusure ---

@router.post("/studio/tratti")
def manda_tratti(
    corpo: TrattiIn,
    chi: Identita = Depends(richiede_dispositivo),
    conn: sqlite3.Connection = Depends(get_conn),
):
    """I tratti del timer, solo dai telefoni (da un computer 422 solo_dal_telefono)."""
    _solo_dal_telefono(chi)
    ora = clock.now()
    conn.execute("BEGIN IMMEDIATE")
    try:
        _telefono_dalla_018(conn, _dispositivo_vivo(conn, chi))
        studio.elabora(conn, chi.figlio_id, ora)
        studio.segna_giro(conn, ora)
        esito = studio.registra_tratti(conn, chi, corpo.tratti, ora)
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    ids = esito.pop("ids")
    return {**esito, "tratti": studio.tratti_out(conn, ids, ora)}


@router.post("/studio/avvia", status_code=201)
def avvia_studio(
    corpo: AvviaStudioIn,
    request: Request,
    chi: Identita = Depends(richiede_dispositivo),
    conn: sqlite3.Connection = Depends(get_conn),
):
    """L'avvio a mano dal telefono (dal computer 422 solo_dal_telefono: lo Studio lo chiude
    solo il telefono). 201 con lo Studio che nasce; 200 con quello che c'era gia' (la
    stessa chiave, o l'inizio dentro uno Studio, che il telefono adotta)."""
    _solo_dal_telefono(chi)
    arrivo = clock.now()
    conn.execute("BEGIN IMMEDIATE")
    try:
        _telefono_dalla_018(conn, _dispositivo_vivo(conn, chi))
        studio.elabora(conn, chi.figlio_id, arrivo)
        studio.segna_giro(conn, arrivo)
        stato, studio_id = studio.avvia(conn, chi, corpo, arrivo)
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    return JSONResponse(status_code=stato, content=studio.formatta(conn, studio.studio_o_404(conn, studio_id), arrivo))


def _chiusura_del_figlio(conn: sqlite3.Connection, chi: Identita, corpo: ChiudiStudioIn, studio_id: int | None):
    """La chiusura del figlio, con o senza id. I tratti del corpo si scrivono prima dei
    controlli e restano anche se la chiusura e' rifiutata: per questo il 404 e il 409 si
    mandano dopo il commit. (Correzione) Prima le chiusure consegnate, poi la mezzanotte:
    uno Studio chiuso a mezzanotte proprio da questa richiesta si giudica come aperto
    (studio.chiuso_in_questa_richiesta)."""
    arrivo = clock.now()
    t = clock.momento_dichiarato(arrivo, corpo.ts_device)
    conn.execute("BEGIN IMMEDIATE")
    try:
        _telefono_dalla_018(conn, _dispositivo_vivo(conn, chi))
        prima = studio.istantanea(conn, chi.figlio_id)
        studio.elabora(conn, chi.figlio_id, arrivo)
        studio.segna_giro(conn, arrivo)
        if corpo.tratti:
            studio.registra_tratti(conn, chi, corpo.tratti, arrivo)
        trovato = studio.trova_per_chiusura(conn, chi, studio_id, corpo.studio, t)
        rifiuto = None
        if trovato is not None:
            rifiuto = studio.chiudi_dal_figlio(conn, chi, corpo, trovato, arrivo,
                                               studio.chiuso_in_questa_richiesta(prima, trovato))
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    if trovato is None:
        raise HTTPException(status_code=404, detail=studio.NON_TROVATO)
    if rifiuto is not None:
        raise HTTPException(status_code=409, detail=rifiuto)
    return studio.formatta(conn, studio.studio_o_404(conn, trovato["id"]), arrivo)


@router.post("/studio/chiudi")
def chiudi_senza_id(
    corpo: ChiudiStudioIn,
    chi: Identita = Depends(richiede_dispositivo),
    conn: sqlite3.Connection = Depends(get_conn),
):
    """La chiusura del figlio di uno Studio di cui il telefono non sa l'id (partito senza
    rete): `studio: {giorno}` (lo Studio che contiene la partenza di quel giorno, creato
    se serve entro le 48 ore) oppure `studio: {chiave}` (avviato a mano)."""
    _solo_dal_telefono(chi)
    if corpo.studio is None:
        raise RequestValidationError(
            [{"loc": ("body", "studio"), "msg": "serve lo Studio: giorno o chiave", "type": "missing"}]
        )
    return _chiusura_del_figlio(conn, chi, corpo, None)


@router.post("/studio/{studio_id}/chiudi")
def chiudi(
    studio_id: int,
    corpo: Any = Body(default=None),
    chi: Identita = Depends(richiede_patto),
    conn: sqlite3.Connection = Depends(get_conn),
):
    """Col token del telefono: la chiusura del figlio (dal computer 422 solo_dal_telefono).
    Col token del genitore: la chiusura del genitore, senza condizioni e con un motivo."""
    if chi.ruolo == "dispositivo":
        _solo_dal_telefono(chi)
        figlio_corpo = _valida(ChiudiStudioIn, corpo)
        if not INTERO_MINIMO <= studio_id <= INTERO_MASSIMO:
            raise HTTPException(status_code=404, detail=studio.NON_TROVATO)
        return _chiusura_del_figlio(conn, chi, figlio_corpo, studio_id)
    genitore_corpo = _valida(ChiudiStudioGenitoreIn, corpo)
    ora = clock.now()
    conn.execute("BEGIN IMMEDIATE")
    try:
        genitori.ancora_valido(conn, chi)
        if genitore_corpo.figlio_id is not None:
            famiglia.figlio_o_404(conn, genitore_corpo.figlio_id)
        riga = studio.studio_o_404(conn, studio_id, genitore_corpo.figlio_id)
        studio.elabora(conn, riga["figlio_id"], ora)
        studio.segna_giro(conn, ora)
        studio.chiudi_dal_genitore(conn, chi, studio.studio_o_404(conn, studio_id), genitore_corpo.motivo, ora)
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    return studio.formatta(conn, studio.studio_o_404(conn, studio_id), ora)
