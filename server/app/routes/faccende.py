"""(v3.6) Le faccende (contratto-api.md, "La faccenda" e seguenti). Un genitore le da'
al figlio; da `blocco_da`, e finche' il figlio non le ha fatte tutte, i suoi
dispositivi sono bloccati. Il figlio le fa mandando una foto per ognuna dal telefono;
un genitore puo' bocciare una foto entro 24 ore (la faccenda si riapre e il blocco
torna subito) o annullare una faccenda ancora da fare.

- Ogni scrittura che controlla e poi scrive (le 20 da fare al massimo, la foto, la
  bocciatura, l'annullamento) sta in BEGIN IMMEDIATE: due richieste simultanee si
  mettono in fila e la seconda rilegge quello che ha scritto la prima (due bocciature
  insieme: la seconda riceve non_bocciabile; una foto e un annullamento insieme: ne
  passa uno solo).
- La foto non e' JSON: arriva come corpo image/jpeg, letta a pezzi fino a 4 MB (oltre,
  413, anche se Content-Length mentiva). Prima di salvarla si tolgono i dati nascosti
  (faccende.pulisci_jpeg, nel pool dei thread: non ferma le altre richieste). La stessa
  foto mandata due volte (una rete che cade e riprova) trova la faccenda gia' fatta con
  la stessa foto: 200, niente avvisi nuovi. Una foto scattata prima di una bocciatura e
  arrivata dopo non sblocca niente, se il telefono dice quante bocciature conosceva
  (`?bocciature=N`): 409 bocciata_nel_frattempo.
- Ogni scrittura di un genitore ricontrolla dentro il lock che non sia stato revocato
  nel frattempo (genitori.ancora_valido): 401 e niente scritto.
- Ogni passo finisce anche nella storia della faccenda (faccende_storia), che si scrive
  solo aggiungendo.
- Notifiche: ai dispositivi del figlio (tutti: dispositivo_id null) quando arrivano
  faccende, quando una foto e' bocciata e quando una faccenda e' annullata; ai genitori
  per ogni faccenda fatta e quando sono finite tutte."""

import os
import sqlite3
from datetime import datetime, timedelta

from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response
from starlette.concurrency import run_in_threadpool
from starlette.requests import ClientDisconnect

from .. import clock, faccende, famiglia, genitori
from ..auth import Identita, richiede_dispositivo, richiede_genitore, richiede_patto
from ..db import accoda_notifica, get_conn
from ..faccende import (
    DA_FARE_MASSIME,
    FOTO_MASSIMA_BYTE,
    GIORNI_AVANTI_BLOCCO,
    ORE_BOCCIATURA,
    FotoNonValida,
)
from ..genitori import Firme
from ..schemas import BocciaIn, FaccendeIn

router = APIRouter()


def cartella(request: Request) -> str:
    """La cartella delle foto di questo server: accanto al suo database."""
    return faccende.cartella_foto(request.app.state.settings.db_path)


def _figlio_di(conn: sqlite3.Connection, chi: Identita, figlio_id: int | None) -> int:
    """Per un dispositivo il figlio del token (un figlio_id nella query si ignora); per
    il genitore quello di `figlio_id`, o il primo, come gli altri GET."""
    if chi.ruolo == "dispositivo":
        return chi.figlio_id
    return famiglia.figlio_scelto(conn, figlio_id)["id"]


def _blocco_da(richiesto: str | None, ora: datetime) -> str:
    """Da quando bloccano le faccende nuove: assente o nel passato = subito (l'ora del
    server); piu' di 7 giorni avanti -> 422."""
    adesso = clock.iso(ora)
    if richiesto is None or richiesto <= adesso:
        return adesso
    if datetime.fromisoformat(richiesto) > ora + timedelta(days=GIORNI_AVANTI_BLOCCO):
        raise HTTPException(
            status_code=422,
            detail=[{"loc": ["body", "blocco_da"],
                     "msg": f"blocco_da arriva al massimo a {GIORNI_AVANTI_BLOCCO} giorni da adesso"}],
        )
    return richiesto


# --- il genitore ---

@router.post("/faccende", status_code=201)
def dai_faccende(
    corpo: FaccendeIn,
    request: Request,
    chi: Identita = Depends(richiede_genitore),
    conn: sqlite3.Connection = Depends(get_conn),
):
    """Da 1 a 10 faccende per volta, tutte con lo stesso blocco_da, per il figlio di
    `figlio_id` (obbligatorio: dare faccende al figlio sbagliato blocca il telefono
    sbagliato). Al massimo 20 da fare per figlio: oltre, 409 troppe_faccende e non nasce
    niente. Il figlio e' avvisato su tutti i suoi dispositivi."""
    ora = clock.now()
    ts = clock.iso(ora)
    blocco_da = _blocco_da(corpo.blocco_da, ora)
    # BEGIN IMMEDIATE: "ci stanno ancora" e gli inserimenti sono un atto solo; due genitori
    # che danno faccende insieme si mettono in fila e il secondo conta anche le prime.
    conn.execute("BEGIN IMMEDIATE")
    try:
        genitori.ancora_valido(conn, chi)
        figlio = famiglia.figlio_o_404(conn, corpo.figlio_id)
        if faccende.quante_da_fare(conn, figlio["id"]) + len(corpo.faccende) > DA_FARE_MASSIME:
            raise HTTPException(status_code=409, detail={"errore": "troppe_faccende"})
        giro = faccende.giro_per_nuove(conn, figlio["id"])
        ids = [
            conn.execute(
                "INSERT INTO faccende (figlio_id, titolo, nota, stato, blocco_da, creata_ts,"
                " creata_genitore_id, giro) VALUES (?, ?, ?, 'da_fare', ?, ?, ?, ?)",
                (figlio["id"], f.titolo, f.nota, blocco_da, ts, chi.genitore_id, giro),
            ).lastrowid
            for f in corpo.faccende
        ]
        for faccenda_id in ids:
            faccende.registra_storia(conn, faccenda_id, "data", ts, chi.genitore_id)
        firme = Firme(conn)
        chi_le_da = firme.di(chi.genitore_id)
        messaggio = (
            f"{chi_le_da['nome']} ti ha dato una faccenda: «{corpo.faccende[0].titolo}»"
            if len(ids) == 1
            else f"{chi_le_da['nome']} ti ha dato {len(ids)} faccende"
        )
        accoda_notifica(
            conn,
            "nuove_faccende",
            messaggio,
            {"faccenda_ids": ids, "blocco_da": blocco_da, "genitore": chi_le_da},
            ts,
            destinatario="figlio",
            figlio_id=figlio["id"],
            dispositivo_id=None,  # a tutti i dispositivi del figlio: il blocco e' di tutti
        )
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    dove = cartella(request)
    return {"faccende": [faccende.una(conn, i, firme, dove) for i in ids]}


@router.post("/faccende/{faccenda_id}/boccia")
def boccia(
    faccenda_id: int,
    request: Request,
    corpo: BocciaIn | None = None,
    chi: Identita = Depends(richiede_genitore),
    conn: sqlite3.Connection = Depends(get_conn),
):
    """Solo una faccenda fatta con la foto arrivata da meno di 24 ore (409 non_bocciabile
    altrimenti): torna da fare con blocco_da = adesso (il blocco torna subito), la foto si
    cancella e il figlio e' avvisato con la nota. La storia tiene la foto e ogni
    bocciatura."""
    nota = corpo.nota if corpo is not None else None
    ora = clock.now()
    ts = clock.iso(ora)
    dove = cartella(request)
    da_togliere = None
    # BEGIN IMMEDIATE: due bocciature insieme (due genitori, un doppio tocco) si mettono
    # in fila, e la seconda trova la faccenda gia' da fare. La foto qui dentro si sposta
    # soltanto (a un nome suo): si toglie dopo il commit, e se la bocciatura non si scrive
    # torna al suo posto. Una foto nuova del figlio, che arriva solo a bocciatura scritta,
    # prende il nome vero e non viene mai tolta al posto della vecchia.
    conn.execute("BEGIN IMMEDIATE")
    try:
        genitori.ancora_valido(conn, chi)
        riga = faccende.faccenda_o_404(conn, faccenda_id)
        if (
            riga["stato"] != "fatta"
            or riga["foto_ts"] is None
            or ora - datetime.fromisoformat(riga["foto_ts"]) >= timedelta(hours=ORE_BOCCIATURA)
        ):
            raise HTTPException(status_code=409, detail={"errore": "non_bocciabile"})
        giro = faccende.giro_per_nuove(conn, riga["figlio_id"])
        conn.execute(
            "UPDATE faccende SET stato = 'da_fare', blocco_da = ?, bocciature = bocciature + 1,"
            " bocciata_ts = ?, bocciata_nota = ?, bocciata_genitore_id = ?, foto_ts = NULL,"
            " chiusa_ts = NULL, giro = ? WHERE id = ?",
            (ts, ts, nota, chi.genitore_id, giro, faccenda_id),
        )
        faccende.registra_storia(conn, faccenda_id, "bocciata", ts, chi.genitore_id, nota)
        da_togliere = faccende.metti_da_parte(dove, faccenda_id)
        firme = Firme(conn)
        chi_boccia = firme.di(chi.genitore_id)
        accoda_notifica(
            conn,
            "faccenda_bocciata",
            f"{chi_boccia['nome']} ha bocciato «{riga['titolo']}»" + (f": {nota}" if nota else ""),
            {"faccenda_id": faccenda_id, "titolo": riga["titolo"], "nota": nota, "genitore": chi_boccia},
            ts,
            destinatario="figlio",
            figlio_id=riga["figlio_id"],
            dispositivo_id=None,
        )
        conn.commit()
    except BaseException:
        conn.rollback()
        if da_togliere is not None:  # la bocciatura non c'e': la foto torna al suo posto
            os.replace(da_togliere, faccende.percorso_foto(dove, faccenda_id))
        raise
    if da_togliere is not None:
        faccende.togli(da_togliere)  # se non riesce, la toglie la pulizia
    return faccende.una(conn, faccenda_id, firme, dove)


@router.post("/faccende/{faccenda_id}/annulla")
def annulla(
    faccenda_id: int,
    request: Request,
    chi: Identita = Depends(richiede_genitore),
    conn: sqlite3.Connection = Depends(get_conn),
):
    """Solo una faccenda da fare (409 non_annullabile altrimenti). Se era l'ultima che
    bloccava, il blocco finisce da se': il blocco e' "almeno una da fare gia' partita"."""
    ts = clock.iso(clock.now())
    # BEGIN IMMEDIATE: un annullamento e una foto che arrivano insieme si mettono in fila;
    # passa il primo, e il secondo trova la faccenda gia' chiusa.
    conn.execute("BEGIN IMMEDIATE")
    try:
        genitori.ancora_valido(conn, chi)
        riga = faccende.faccenda_o_404(conn, faccenda_id)
        if riga["stato"] != "da_fare":
            raise HTTPException(status_code=409, detail={"errore": "non_annullabile"})
        conn.execute(
            "UPDATE faccende SET stato = 'annullata', annullata_genitore_id = ?, chiusa_ts = ?"
            " WHERE id = ?",
            (chi.genitore_id, ts, faccenda_id),
        )
        faccende.registra_storia(conn, faccenda_id, "annullata", ts, chi.genitore_id)
        firme = Firme(conn)
        chi_annulla = firme.di(chi.genitore_id)
        accoda_notifica(
            conn,
            "faccenda_annullata",
            f"{chi_annulla['nome']} ha annullato «{riga['titolo']}»",
            {"faccenda_id": faccenda_id, "titolo": riga["titolo"], "genitore": chi_annulla},
            ts,
            destinatario="figlio",
            figlio_id=riga["figlio_id"],
            dispositivo_id=None,
        )
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    return faccende.una(conn, faccenda_id, firme, cartella(request))


# --- le letture (genitore e dispositivi) ---

@router.get("/faccende")
def elenca_faccende(
    request: Request,
    figlio_id: int | None = None,
    chi: Identita = Depends(richiede_patto),
    conn: sqlite3.Connection = Depends(get_conn),
):
    figlio = _figlio_di(conn, chi, figlio_id)
    elenco = faccende.faccende_del_figlio(conn, figlio, clock.now(), Firme(conn), cartella(request))
    return {"faccende": elenco}


@router.get("/faccende/blocco")
def leggi_blocco(
    figlio_id: int | None = None,
    chi: Identita = Depends(richiede_patto),
    conn: sqlite3.Connection = Depends(get_conn),
):
    """La risposta piccola che i dispositivi chiedono spesso (almeno ogni minuto). Al
    genitore, per il figlio di `figlio_id` o il primo, la stessa che ha nella finestra."""
    return faccende.blocco(conn, _figlio_di(conn, chi, figlio_id), clock.now(), Firme(conn))


@router.get("/faccende/{faccenda_id}/foto")
def leggi_foto(
    faccenda_id: int,
    request: Request,
    chi: Identita = Depends(richiede_patto),
    conn: sqlite3.Connection = Depends(get_conn),
):
    """La foto, al genitore e ai dispositivi dello stesso figlio. Mai compressa (main.py:
    un JPEG e' gia' compresso) e mai tenuta in una cache lungo la strada."""
    del_figlio = chi.figlio_id if chi.ruolo == "dispositivo" else None
    riga = faccende.faccenda_o_404(conn, faccenda_id, del_figlio)
    dati = faccende.leggi_foto(cartella(request), riga)
    if dati is None:
        raise HTTPException(status_code=404, detail=faccende.FOTO_NON_TROVATA)
    return Response(
        content=dati, media_type="image/jpeg", headers={"Cache-Control": "private, no-store"}
    )


# --- il dispositivo: la foto ---

def _pulita(dati: bytes) -> bytes:
    try:
        return faccende.pulisci_jpeg(dati)
    except FotoNonValida:
        raise HTTPException(status_code=422, detail={"errore": "foto_non_valida"})


@router.put("/faccende/{faccenda_id}/foto")
async def manda_foto(
    faccenda_id: int,
    request: Request,
    bocciature: int | None = Query(default=None, ge=0),
    chi: Identita = Depends(richiede_dispositivo),
    conn: sqlite3.Connection = Depends(get_conn),
):
    """La foto di una faccenda, dal telefono. I controlli, in quest'ordine: la faccenda
    (404), il telefono (422 solo_dal_telefono), la misura (413), il JPEG (422
    foto_non_valida), le bocciature (409 bocciata_nel_frattempo), lo stato (409
    non_da_fare, salvo la stessa foto mandata di nuovo).

    `bocciature` (facoltativo): quante bocciature della faccenda il telefono conosceva
    quando ha scattato la foto. Se intanto la faccenda ne ha avute di piu', questa foto
    e' quella vecchia, consegnata in ritardo: non vale. Senza, come prima (app 0.12).

    async perche' il corpo si legge a pezzi, contando: un corpo oltre i 4 MB si ferma
    al primo pezzo di troppo, qualsiasi cosa dica Content-Length. La pulizia del JPEG, il
    lavoro sul database e sul disco vanno nel pool dei thread, come per le route normali.
    Un telefono che se ne va a meta' invio non e' un errore del server: niente 500 e
    niente traccia nel log."""
    await run_in_threadpool(faccende.faccenda_o_404, conn, faccenda_id, chi.figlio_id)
    if chi.tipo != "telefono":
        raise HTTPException(status_code=422, detail={"errore": "solo_dal_telefono"})
    troppo = HTTPException(status_code=413, detail={"errore": "foto_troppo_grande"})
    dichiarata = request.headers.get("content-length", "")
    if dichiarata.isdigit() and int(dichiarata) > FOTO_MASSIMA_BYTE:
        raise troppo
    dati = bytearray()
    try:
        async for pezzo in request.stream():
            dati += pezzo
            if len(dati) > FOTO_MASSIMA_BYTE:
                raise troppo
    except ClientDisconnect:
        # Nessuno leggera' la risposta: niente da scrivere, niente da segnalare.
        return Response(status_code=400)
    foto = await run_in_threadpool(_pulita, bytes(dati))
    return await run_in_threadpool(_consegna, conn, faccenda_id, chi, foto, cartella(request), bocciature)


def _consegna(
    conn: sqlite3.Connection,
    faccenda_id: int,
    chi: Identita,
    foto: bytes,
    dove: str,
    bocciature: int | None = None,
) -> dict:
    """La foto gia' pulita diventa quella della faccenda, che si chiude: fatta. Il file
    si scrive prima in un temporaneo (fuori dal lock: e' il pezzo lento) e prende il suo
    nome solo dentro il lock, se la faccenda e' ancora da fare. Avvisi ai genitori: la
    faccenda fatta e, se era l'ultima da fare del figlio, le faccende finite."""
    ora = clock.now()
    ts = clock.iso(ora)
    temporanea = faccende.scrivi_temporanea(dove, faccenda_id, foto)
    try:
        conn.execute("BEGIN IMMEDIATE")
        try:
            riga = faccende.faccenda_o_404(conn, faccenda_id, chi.figlio_id)
            if bocciature is not None and riga["bocciature"] > bocciature:
                # Scattata prima di una bocciatura che il telefono non conosceva: e' la
                # foto vecchia, arrivata in ritardo. Non vale (ne' per sbloccare, ne'
                # come foto ripetuta).
                raise HTTPException(status_code=409, detail={"errore": "bocciata_nel_frattempo"})
            if riga["stato"] == "fatta" and faccende.leggi_foto(dove, riga) == foto:
                # La stessa foto di nuovo (la rete era caduta prima della risposta): e'
                # gia' tutto fatto, niente da scrivere e niente avvisi.
                conn.rollback()
                return faccende.una(conn, faccenda_id, Firme(conn), dove)
            if riga["stato"] != "da_fare":
                raise HTTPException(status_code=409, detail={"errore": "non_da_fare"})
            os.replace(temporanea, faccende.percorso_foto(dove, faccenda_id))
            temporanea = None
            conn.execute(
                "UPDATE faccende SET stato = 'fatta', foto_ts = ?, chiusa_ts = ? WHERE id = ?",
                (ts, ts, faccenda_id),
            )
            faccende.registra_storia(conn, faccenda_id, "foto", ts)
            nome_figlio = famiglia.figlio_o_404(conn, riga["figlio_id"])["nome"]
            accoda_notifica(
                conn,
                "faccenda_fatta",
                f"{nome_figlio} ha fatto «{riga['titolo']}»",
                {"faccenda_id": faccenda_id, "titolo": riga["titolo"]},
                ts,
                destinatario="genitore",
                figlio_id=riga["figlio_id"],
                dispositivo_id=None,
            )
            if faccende.quante_da_fare(conn, riga["figlio_id"]) == 0:
                # Era l'ultima: il giro si chiude. Il blocco c'era se questa faccenda
                # (l'unica rimasta da fare) bloccava gia'; se no le ha fatte in anticipo.
                fatte = [
                    r["id"]
                    for r in conn.execute(
                        "SELECT id FROM faccende WHERE figlio_id = ? AND giro = ? AND stato = 'fatta'"
                        " ORDER BY id",
                        (riga["figlio_id"], riga["giro"]),
                    )
                ]
                sbloccati = riga["blocco_da"] <= ts
                accoda_notifica(
                    conn,
                    "faccende_finite",
                    f"{nome_figlio} ha finito le faccende"
                    + (": telefono e computer sbloccati" if sbloccati else ""),
                    {"faccenda_ids": fatte},
                    ts,
                    destinatario="genitore",
                    figlio_id=riga["figlio_id"],
                    dispositivo_id=None,
                )
            conn.commit()
        except BaseException:
            conn.rollback()
            raise
    finally:
        if temporanea is not None:
            faccende.togli(temporanea)
    return faccende.una(conn, faccenda_id, Firme(conn), dove)
