"""(v3.5) Le Sessioni (contratto-api.md, "v3.5 — le Sessioni"). Una sessione e' un
periodo in cui il telefono del figlio si limita da solo ad alcune app: "Studio" con le
app di scuola. La DECIDE il figlio, il genitore la APPROVA (una volta, e di nuovo a
ogni cambio), la AVVIA il figlio quando vuole e per quanto vuole. Durante la sessione
il tempo nelle sue app non conta: lo calcola il telefono. Il server custodisce le
definizioni, le decisioni del genitore e la storia delle sessioni svolte.

- Una sessione e' del telefono che l'ha creata: si cambia, si elimina e si avvia solo
  da li'. Da un altro dispositivo (anche dello stesso figlio), o se non c'e' o e' stata
  eliminata: 404 "sessione non trovata", mai il "Not Found" generico, che per le app
  vuol dire "server vecchio". Al massimo 20 sessioni per telefono.
- Ogni sessione ha una `versione` che cresce a ogni cambio del figlio e a ogni
  decisione del genitore: il genitore decide dicendo quale versione ha visto, e se
  intanto il figlio l'ha cambiata non decide niente (409 richiesta_cambiata). Il
  genitore non approva mai una lista che non ha visto.
- Le etichette delle app che il genitore legge vengono, quando ci sono, dalle
  fotografie dell'uso (le stesse del `nome` della finestra), non da quelle scritte con
  la sessione: un'etichetta scritta apposta non lo inganna.
- Ogni scrittura che controlla e poi scrive (nome libero, una sola sessione in corso,
  la decisione del genitore) sta in BEGIN IMMEDIATE: due richieste simultanee si
  mettono in fila e la seconda rilegge quello che ha scritto la prima.
- Allo scadere la sessione svolta si chiude da sola: si calcola quando si legge
  (formatta_svolta) e si scrive al primo avvio successivo (_chiudi_scadute). Nessun
  processo in sottofondo.
- Notifiche al genitore quando c'e' da decidere (al massimo un avviso aperto per
  sessione) e quando una sessione sparisce; al telefono della sessione quando il
  genitore decide. Per l'inizio e la fine niente notifiche: si vedono nella finestra.
- (v3.6) Ogni sessione dice quale genitore l'ha decisa l'ultima volta (`decisa_da`), e
  il messaggio al figlio porta il suo nome. Con il blocco delle faccende attivo una
  sessione non si avvia (409 blocco_faccende)."""

import json
import sqlite3
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException

from .. import clock, config, faccende, famiglia, siti
from ..auth import Identita, richiede_dispositivo, richiede_genitore, richiede_patto
from ..controllo_corpo import INTERO_MASSIMO, INTERO_MINIMO
from ..db import accoda_notifica, get_conn
from ..genitori import Firme, ancora_valido
from ..schemas import (
    DURATA_MASSIMA_SESSIONE,
    SESSIONI_MASSIME_PER_DISPOSITIVO,
    AvviaSessioneIn,
    RispostaSessioneIn,
    SessioneIn,
    SessionePatch,
    TerminaSessioneIn,
)

router = APIRouter()

# Le sessioni svolte nel patto e nella finestra: al massimo 200, dalla piu' recente
# (con 8 giorni e sessioni brevi, 50 potevano non bastare al telefono per ricostruire
# quali periodi non contano).
ELENCO_MASSIMO = 200

# La chiusura anticipata (termina): un ts_device a meno di 2 minuti dall'arrivo e' una
# chiusura fatta con la rete, e vale l'arrivo (cosi' un orologio un po' avanti o
# indietro non sposta niente). Una chiusura fatta senza rete vale da quando e' stata
# fatta solo se arriva entro 48 ore.
TOLLERANZA_OROLOGIO = timedelta(minutes=2)
RITARDO_MASSIMO = timedelta(hours=48)

NON_TROVATA = "sessione non trovata"


# --- le forme ---

def etichette_note(conn: sqlite3.Connection, figlio_id: int) -> dict:
    """Le etichette delle app viste nelle fotografie uso_giornaliero del figlio: la
    stessa fonte del `nome` delle regole nella finestra (famiglia.nomi_recenti)."""
    oggi = clock.now().astimezone(config.fuso_patto()).date()
    return famiglia.nomi_recenti(conn, figlio_id, oggi)


def _etichette(app: list[str], del_figlio: dict, note: dict) -> dict:
    """Le etichette da mostrare per le app di una sessione: per un'app vista nell'uso
    quella delle fotografie, per le altre (mai viste, e gruppo:apk) quella mandata con
    la sessione. Il genitore non si fa ingannare da un'etichetta scritta apposta
    ("ClasseViva" su TikTok). Nel database resta quella mandata dal telefono."""
    etichette = {}
    for chiave in app:
        nome = note.get(chiave) or del_figlio.get(chiave)
        if nome:
            etichette[chiave] = nome
    return etichette


def _contenuto(riga: sqlite3.Row) -> dict:
    """La versione corrente di una sessione (quella approvata, se e' approvata), come
    l'ha mandata il telefono."""
    return {"nome": riga["nome"], "app": json.loads(riga["app"]), "nomi": json.loads(riga["nomi"])}


def _decisa_da(riga: sqlite3.Row, firme: Firme) -> dict | None:
    """(v3.6) Il genitore dell'ultima decisione. Una sessione di prima della v3.6 gia'
    approvata o rifiutata e' stata decisa dal genitore 1 (allora l'unico); una mai
    decisa non ha nessuno."""
    if riga["decisa_genitore_id"] is not None:
        return firme.di(riga["decisa_genitore_id"])
    return firme.primo() if riga["stato"] in ("approvata", "rifiutata") else None


def formatta_sessione(
    riga: sqlite3.Row, dispositivo: sqlite3.Row | None, note: dict, firme: Firme
) -> dict:
    contenuto = _contenuto(riga)
    modifica = json.loads(riga["modifica_in_attesa"]) if riga["modifica_in_attesa"] else None
    if modifica is not None:
        modifica = {**modifica, "nomi": _etichette(modifica["app"], modifica["nomi"], note)}
    return {
        "id": riga["id"],
        "dispositivo_id": riga["dispositivo_id"],
        "dispositivo": famiglia.riferimento(dispositivo) if dispositivo is not None else None,
        "nome": contenuto["nome"],
        "app": contenuto["app"],
        "nomi": _etichette(contenuto["app"], contenuto["nomi"], note),
        "stato": riga["stato"],
        "modifica_in_attesa": modifica,
        "motivazione": riga["motivazione"],
        "versione": riga["versione"],
        "creata_ts": riga["creata_ts"],
        "approvata_ts": riga["approvata_ts"],
        "decisa_da": _decisa_da(riga, firme),  # (v3.6)
    }


def _scaduta(svolta: sqlite3.Row, ora: datetime) -> bool:
    """Aperta (fine_ts NULL) ma col tempo finito: si e' chiusa da sola."""
    return svolta["fine_ts"] is None and ora >= datetime.fromisoformat(svolta["fine_prevista_ts"])


def formatta_svolta(riga: sqlite3.Row, ora: datetime, note: dict) -> dict:
    """La sessione svolta com'e' ADESSO: una aperta col tempo finito si legge gia'
    chiusa ('scaduta', fine_ts = fine_prevista_ts) anche se la riga non e' ancora
    stata scritta (lo fa il prossimo avvio). Chi legge non vede mai la differenza."""
    fine_ts, chiusura = riga["fine_ts"], riga["chiusura"]
    if _scaduta(riga, ora):
        fine_ts, chiusura = riga["fine_prevista_ts"], "scaduta"
    app = json.loads(riga["app"])
    return {
        "id": riga["id"],
        "sessione_id": riga["sessione_id"],
        "dispositivo_id": riga["dispositivo_id"],
        "nome": riga["nome"],
        "app": app,
        "nomi": _etichette(app, json.loads(riga["nomi"]), note),
        "inizio_ts": riga["inizio_ts"],
        "durata_minuti": riga["durata_minuti"],
        "fine_prevista_ts": riga["fine_prevista_ts"],
        "fine_ts": fine_ts,
        "chiusura": chiusura,
        "in_corso": fine_ts is None,
    }


# --- le letture condivise con patto, finestra e famiglia ---
# `note` = etichette_note(...) del figlio, letta una volta sola da chi chiama.

def sessioni_del_dispositivo(
    conn: sqlite3.Connection, dispositivo: sqlite3.Row, note: dict
) -> list[dict]:
    """Le sessioni non eliminate di un dispositivo, dalla piu' vecchia."""
    righe = conn.execute(
        "SELECT * FROM sessioni WHERE dispositivo_id = ? AND eliminata_ts IS NULL ORDER BY id",
        (dispositivo["id"],),
    ).fetchall()
    firme = Firme(conn)
    return [formatta_sessione(r, dispositivo, note, firme) for r in righe]


def sessioni_del_figlio(
    conn: sqlite3.Connection,
    figlio_id: int,
    note: dict,
    dispositivi: list[sqlite3.Row] | None = None,
) -> list[dict]:
    """Le sessioni non eliminate di tutti i dispositivi del figlio, revocati compresi
    (la storia resta), dalla piu' vecchia."""
    if dispositivi is None:
        dispositivi = famiglia.dispositivi_del_figlio(conn, figlio_id)
    per_id = {d["id"]: d for d in dispositivi}
    righe = conn.execute(
        "SELECT * FROM sessioni WHERE figlio_id = ? AND eliminata_ts IS NULL ORDER BY id",
        (figlio_id,),
    ).fetchall()
    firme = Firme(conn)
    return [formatta_sessione(r, per_id.get(r["dispositivo_id"]), note, firme) for r in righe]


def sessioni_da_approvare(conn: sqlite3.Connection, figlio_id: int) -> int:
    """Quante decisioni del figlio aspettano il genitore: le sessioni in_attesa piu' i
    cambi in attesa. Non quelle dei dispositivi revocati: non si avviano piu' e non si
    decidono piu' (la risposta e' 409 dispositivo_revocato). Restano visibili in
    `sessioni`."""
    return conn.execute(
        "SELECT COUNT(*) AS n FROM sessioni s JOIN dispositivi d ON d.id = s.dispositivo_id"
        " WHERE s.figlio_id = ? AND s.eliminata_ts IS NULL AND d.revocato_ts IS NULL"
        " AND (s.stato = 'in_attesa' OR s.modifica_in_attesa IS NOT NULL)",
        (figlio_id,),
    ).fetchone()["n"]


def inizio_finestra(ora: datetime) -> datetime:
    """L'inizio degli 8 giorni della striscia (siti.giorni_finestra): la mezzanotte del
    primo giorno nel fuso del patto, in UTC."""
    primo = siti.giorni_finestra(ora)[0]
    mezzanotte = datetime(primo.year, primo.month, primo.day, tzinfo=config.fuso_patto())
    return mezzanotte.astimezone(timezone.utc)


def _svolte_nella_finestra(
    conn: sqlite3.Connection, filtro: str, parametri: tuple, ora: datetime, note: dict
) -> list[dict]:
    """Le sessioni svolte che TOCCANO gli 8 giorni della striscia (finite dopo l'inizio
    della finestra, o ancora aperte), dalla piu' recente, al massimo ELENCO_MASSIMO.
    Una sessione dura al massimo un giorno, quindi quelle che toccano la finestra sono
    partite al piu' un giorno prima del suo inizio: il limite sull'inizio fa leggere
    all'indice (dispositivo_id, inizio_ts) solo quelle righe, non tutta la storia."""
    inizio = inizio_finestra(ora)
    righe = conn.execute(
        "SELECT s.* FROM sessioni_svolte s JOIN dispositivi d ON d.id = s.dispositivo_id"
        f" WHERE {filtro} AND s.inizio_ts >= ? AND COALESCE(s.fine_ts, s.fine_prevista_ts) > ?"
        " ORDER BY s.inizio_ts DESC, s.id DESC LIMIT ?",
        (
            *parametri,
            clock.iso(inizio - timedelta(minutes=DURATA_MASSIMA_SESSIONE)),
            clock.iso(inizio),
            ELENCO_MASSIMO,
        ),
    ).fetchall()
    return [formatta_svolta(r, ora, note) for r in righe]


def sessioni_svolte_del_dispositivo(
    conn: sqlite3.Connection, dispositivo_id: int, ora: datetime, note: dict
) -> list[dict]:
    """Per GET /api/patto: al telefono servono per sapere quali periodi non contano,
    anche dopo un riavvio o una reinstallazione."""
    return _svolte_nella_finestra(conn, "s.dispositivo_id = ?", (dispositivo_id,), ora, note)


def sessioni_svolte_del_figlio(
    conn: sqlite3.Connection, figlio_id: int, ora: datetime, note: dict
) -> list[dict]:
    """Per GET /api/finestra: quelle di tutti i dispositivi del figlio, ciascuna col suo
    dispositivo_id. Il genitore vede inizio, durata, fine e chiusure anticipate."""
    return _svolte_nella_finestra(conn, "d.figlio_id = ?", (figlio_id,), ora, note)


def _aperta(conn: sqlite3.Connection, dispositivo_id: int) -> sqlite3.Row | None:
    """La sessione svolta aperta (fine_ts NULL) del dispositivo: al massimo una,
    garantito dall'indice unico. Puo' essere gia' scaduta e non ancora chiusa."""
    return conn.execute(
        "SELECT * FROM sessioni_svolte WHERE dispositivo_id = ? AND fine_ts IS NULL",
        (dispositivo_id,),
    ).fetchone()


def sessione_in_corso(
    conn: sqlite3.Connection, dispositivo_id: int, ora: datetime, note: dict
) -> dict | None:
    """La sessione svolta in corso del dispositivo, o None (anche quando quella aperta
    e' gia' scaduta)."""
    riga = _aperta(conn, dispositivo_id)
    if riga is None or _scaduta(riga, ora):
        return None
    return formatta_svolta(riga, ora, note)


# --- gli aiuti delle scritture ---

def _sessione_o_404(conn: sqlite3.Connection, sessione_id: int) -> sqlite3.Row:
    # Un id oltre i 64 bit non e' mai una sessione (e SQLite non saprebbe cercarlo).
    if not INTERO_MINIMO <= sessione_id <= INTERO_MASSIMO:
        raise HTTPException(status_code=404, detail=NON_TROVATA)
    riga = conn.execute("SELECT * FROM sessioni WHERE id = ?", (sessione_id,)).fetchone()
    if riga is None or riga["eliminata_ts"] is not None:
        raise HTTPException(status_code=404, detail=NON_TROVATA)
    return riga


def _sessione_del_dispositivo(
    conn: sqlite3.Connection, sessione_id: int, chi: Identita
) -> sqlite3.Row:
    """La sessione del telefono che chiama. Una sessione e' del dispositivo che l'ha
    creata (la barriera gira li'): per un altro dispositivo, anche dello stesso figlio,
    non c'e' (404, come una che non esiste)."""
    riga = _sessione_o_404(conn, sessione_id)
    if riga["dispositivo_id"] != chi.dispositivo_id:
        raise HTTPException(status_code=404, detail=NON_TROVATA)
    return riga


def _dispositivo(conn: sqlite3.Connection, dispositivo_id: int) -> sqlite3.Row:
    return conn.execute("SELECT * FROM dispositivi WHERE id = ?", (dispositivo_id,)).fetchone()


def _dispositivo_vivo(conn: sqlite3.Connection, dispositivo_id: int) -> sqlite3.Row:
    """Il dispositivo, riletto dentro il lock: 409 dispositivo_revocato se e' stato
    revocato. Per il telefono che chiama copre la revoca arrivata in mezzo (il suo token
    risponde gia' 401), come per le regole (v3.1); per il genitore, le sessioni di un
    dispositivo revocato, che non si decidono piu'."""
    dispositivo = _dispositivo(conn, dispositivo_id)
    if dispositivo is None or dispositivo["revocato_ts"] is not None:
        raise HTTPException(status_code=409, detail={"errore": "dispositivo_revocato"})
    return dispositivo


def _svolta(conn: sqlite3.Connection, svolta_id: int) -> sqlite3.Row:
    return conn.execute("SELECT * FROM sessioni_svolte WHERE id = ?", (svolta_id,)).fetchone()


def _verifica_nome_libero(
    conn: sqlite3.Connection, dispositivo_id: int, nome: str, esclusa: int | None = None
) -> None:
    """Il nome e' unico, senza distinguere le maiuscole, tra le sessioni non eliminate
    del dispositivo. Conta anche il nome di un cambio in attesa: se il genitore lo
    approvasse, due sessioni si chiamerebbero uguali. `esclusa` e' la sessione che si
    sta cambiando: puo' tenere il suo nome, o cambiarne solo le maiuscole. I nomi sono
    gia' in forma NFC (schemas._nome_sessione)."""
    cercato = nome.casefold()
    for riga in conn.execute(
        "SELECT id, nome, modifica_in_attesa FROM sessioni"
        " WHERE dispositivo_id = ? AND eliminata_ts IS NULL",
        (dispositivo_id,),
    ).fetchall():
        if riga["id"] == esclusa:
            continue
        presi = {riga["nome"].casefold()}
        if riga["modifica_in_attesa"]:
            presi.add(json.loads(riga["modifica_in_attesa"])["nome"].casefold())
        if cercato in presi:
            raise HTTPException(status_code=409, detail={"errore": "nome_gia_usato"})


def _nomi_delle_app(nomi: dict, app: list[str]) -> dict:
    """Solo le etichette delle app della lista: quella di un'app che non c'e' non dice
    niente (e una lista cambiata si porta dietro solo le etichette che servono)."""
    nella_lista = set(app)
    return {chiave: nome for chiave, nome in nomi.items() if chiave in nella_lista}


def _stesso_contenuto(uno: dict, altro: dict) -> bool:
    """Lo stesso contenuto: stesso nome, stesse app (l'ordine non conta: e' un elenco
    di app permesse), stesse etichette."""
    return (
        uno["nome"] == altro["nome"]
        and set(uno["app"]) == set(altro["app"])
        and uno["nomi"] == altro["nomi"]
    )


def _chiudi_scadute(conn: sqlite3.Connection, dispositivo_id: int, ora: datetime) -> None:
    """Scrive la chiusura naturale delle sessioni svolte gia' scadute del dispositivo
    ('scaduta', fine_ts = fine_prevista_ts). Le letture la calcolano gia' da sole: qui
    serve a liberare il posto dell'unica sessione aperta prima di avviarne un'altra."""
    conn.execute(
        "UPDATE sessioni_svolte SET fine_ts = fine_prevista_ts, chiusura = 'scaduta'"
        " WHERE dispositivo_id = ? AND fine_ts IS NULL AND fine_prevista_ts <= ?",
        (dispositivo_id, clock.iso(ora)),
    )


def _da_ts_device(ts_device: int | None) -> datetime | None:
    """ts_device (epoch in millisecondi UTC) come istante; None se manca o non e' una
    data possibile."""
    if ts_device is None:
        return None
    try:
        return datetime.fromtimestamp(ts_device / 1000, tz=timezone.utc)
    except (OverflowError, OSError, ValueError):
        return None


def _nome_figlio(conn: sqlite3.Connection, figlio_id: int) -> str:
    return famiglia.figlio_o_404(conn, figlio_id)["nome"]


def _chiudi_avvisi_aperti(conn: sqlite3.Connection, sessione: sqlite3.Row) -> None:
    """Le richieste di approvazione di questa sessione che il genitore non ha ancora
    letto si segnano come lette: quando arriva una richiesta nuova (la sostituisce),
    quando il figlio ritira il cambio o elimina la sessione, quando il genitore decide.
    Cosi' nella lista del genitore c'e' al massimo un avviso aperto per sessione, ed e'
    quello di adesso. Le notifiche non si cancellano: si leggono."""
    for avviso in conn.execute(
        "SELECT id, payload FROM notifiche WHERE destinatario = 'genitore' AND letta = 0"
        " AND tipo = 'sessione_da_approvare' AND figlio_id = ?",
        (sessione["figlio_id"],),
    ).fetchall():
        if json.loads(avviso["payload"]).get("sessione_id") == sessione["id"]:
            conn.execute("UPDATE notifiche SET letta = 1 WHERE id = ?", (avviso["id"],))


def _chiedi_al_genitore(
    conn: sqlite3.Connection,
    sessione: sqlite3.Row,
    nome: str,
    cambio: bool,
    ts: str,
    nuovo_nome: str | None = None,
) -> None:
    """La notifica sessione_da_approvare, col nome del figlio (il genitore ne puo'
    avere piu' d'uno) e col dispositivo della sessione. `nome` e' quello che la
    sessione ha adesso; un cambio che la rinomina porta anche `nuovo_nome`. Prende il
    posto dell'avviso di prima sulla stessa sessione, se il genitore non l'ha letto."""
    _chiudi_avvisi_aperti(conn, sessione)
    figlio = _nome_figlio(conn, sessione["figlio_id"])
    messaggio = (
        f"{figlio} chiede di cambiare la sessione «{nome}»"
        if cambio
        else f"{figlio} chiede di approvare la sessione «{nome}»"
    )
    payload = {"sessione_id": sessione["id"], "nome": nome, "cambio": cambio}
    if nuovo_nome is not None:
        payload["nuovo_nome"] = nuovo_nome
    accoda_notifica(
        conn,
        "sessione_da_approvare",
        messaggio,
        payload,
        ts,
        destinatario="genitore",
        figlio_id=sessione["figlio_id"],
        dispositivo_id=sessione["dispositivo_id"],
    )


# --- gli endpoint ---

@router.get("/sessioni")
def elenca_sessioni(
    figlio_id: int | None = None,
    chi: Identita = Depends(richiede_patto),
    conn: sqlite3.Connection = Depends(get_conn),
):
    """Dal dispositivo: le sue sessioni non eliminate (un figlio_id nella query si
    ignora). Dal genitore: quelle di tutti i dispositivi del figlio di `figlio_id`, o
    del primo (404 se non c'e'). Dalla piu' vecchia."""
    if chi.ruolo == "dispositivo":
        note = etichette_note(conn, chi.figlio_id)
        return {"sessioni": sessioni_del_dispositivo(conn, _dispositivo(conn, chi.dispositivo_id), note)}
    figlio = famiglia.figlio_scelto(conn, figlio_id)
    return {"sessioni": sessioni_del_figlio(conn, figlio["id"], etichette_note(conn, figlio["id"]))}


@router.post("/sessioni", status_code=201)
def crea_sessione(
    corpo: SessioneIn,
    chi: Identita = Depends(richiede_dispositivo),
    conn: sqlite3.Connection = Depends(get_conn),
):
    """La sessione nasce in_attesa sul telefono che chiama e il genitore e' avvisato.
    Per ora solo sui telefoni: da un computer 422. Al massimo 20 per telefono (le
    eliminate non contano): 409 troppe_sessioni."""
    if chi.tipo != "telefono":
        raise HTTPException(
            status_code=422,
            detail=[{"loc": ["dispositivo", "tipo"], "msg": "le sessioni ci sono solo sui telefoni"}],
        )
    ts = clock.iso(clock.now())
    # BEGIN IMMEDIATE: "c'e' posto", "il nome e' libero" e l'inserimento sono un atto
    # solo. Due richieste simultanee con lo stesso nome si mettono in fila e la seconda
    # trova la prima (409 nome_gia_usato). Dentro il lock anche il controllo della revoca.
    conn.execute("BEGIN IMMEDIATE")
    try:
        dispositivo = _dispositivo_vivo(conn, chi.dispositivo_id)
        quante = conn.execute(
            "SELECT COUNT(*) AS n FROM sessioni WHERE dispositivo_id = ? AND eliminata_ts IS NULL",
            (chi.dispositivo_id,),
        ).fetchone()["n"]
        if quante >= SESSIONI_MASSIME_PER_DISPOSITIVO:
            raise HTTPException(status_code=409, detail={"errore": "troppe_sessioni"})
        _verifica_nome_libero(conn, chi.dispositivo_id, corpo.nome)
        nomi = _nomi_delle_app(corpo.nomi or {}, corpo.app)
        sessione_id = conn.execute(
            "INSERT INTO sessioni (figlio_id, dispositivo_id, nome, app, nomi, stato, creata_ts)"
            " VALUES (?, ?, ?, ?, ?, 'in_attesa', ?)",
            (chi.figlio_id, chi.dispositivo_id, corpo.nome, json.dumps(corpo.app),
             json.dumps(nomi), ts),
        ).lastrowid
        _chiedi_al_genitore(conn, _sessione_o_404(conn, sessione_id), corpo.nome, False, ts)
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    return formatta_sessione(
        _sessione_o_404(conn, sessione_id), dispositivo, etichette_note(conn, chi.figlio_id),
        Firme(conn),
    )


@router.patch("/sessioni/{sessione_id}")
def modifica_sessione(
    sessione_id: int,
    corpo: SessionePatch,
    chi: Identita = Depends(richiede_dispositivo),
    conn: sqlite3.Connection = Depends(get_conn),
):
    """Su una sessione in_attesa o rifiutata il contenuto cambia subito e la sessione
    torna in_attesa (senza la motivazione del rifiuto). Su una approvata la versione
    approvata NON cambia: il cambio aspetta in modifica_in_attesa e intanto la sessione
    si avvia com'era. Il cambio e' sempre completo (nome, app, nomi, richiesta_ts): i
    campi che il figlio non manda si copiano dalla versione approvata, e sostituisce
    del tutto un cambio di prima. Se il risultato e' proprio la versione approvata, il
    figlio ritira il cambio: niente in attesa, niente avviso nuovo, e quello di prima
    si chiude. Il genitore e' avvisato negli altri casi, e la versione cresce sempre:
    una decisione presa su quella di prima non passa piu'."""
    ts = clock.iso(clock.now())
    # BEGIN IMMEDIATE: rileggere la sessione, controllare il nome e scrivere sono un
    # atto solo, come per la creazione; e un cambio non si infila a meta' di una
    # decisione del genitore.
    conn.execute("BEGIN IMMEDIATE")
    try:
        riga = _sessione_del_dispositivo(conn, sessione_id, chi)
        dispositivo = _dispositivo_vivo(conn, chi.dispositivo_id)
        attuale = _contenuto(riga)
        app = corpo.app if corpo.app is not None else attuale["app"]
        nuovo = {
            "nome": corpo.nome if corpo.nome is not None else attuale["nome"],
            "app": app,
            "nomi": _nomi_delle_app(corpo.nomi if corpo.nomi is not None else attuale["nomi"], app),
        }
        _verifica_nome_libero(conn, riga["dispositivo_id"], nuovo["nome"], esclusa=sessione_id)
        if riga["stato"] == "approvata" and _stesso_contenuto(nuovo, attuale):
            conn.execute(
                "UPDATE sessioni SET modifica_in_attesa = NULL, versione = versione + 1 WHERE id = ?",
                (sessione_id,),
            )
            _chiudi_avvisi_aperti(conn, riga)
        elif riga["stato"] == "approvata":
            conn.execute(
                "UPDATE sessioni SET modifica_in_attesa = ?, versione = versione + 1 WHERE id = ?",
                (json.dumps({**nuovo, "richiesta_ts": ts}), sessione_id),
            )
            # Il genitore conosce la sessione col nome approvato: il cambio si annuncia
            # con quello, e se la rinomina porta anche il nome chiesto.
            rinomina = nuovo["nome"] if nuovo["nome"] != riga["nome"] else None
            _chiedi_al_genitore(conn, riga, riga["nome"], True, ts, nuovo_nome=rinomina)
        else:
            conn.execute(
                "UPDATE sessioni SET nome = ?, app = ?, nomi = ?, stato = 'in_attesa',"
                " motivazione = NULL, versione = versione + 1 WHERE id = ?",
                (nuovo["nome"], json.dumps(nuovo["app"]), json.dumps(nuovo["nomi"]), sessione_id),
            )
            _chiedi_al_genitore(conn, riga, nuovo["nome"], False, ts)
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    return formatta_sessione(
        _sessione_o_404(conn, sessione_id), dispositivo, etichette_note(conn, chi.figlio_id),
        Firme(conn),
    )


@router.delete("/sessioni/{sessione_id}")
def elimina_sessione(
    sessione_id: int,
    chi: Identita = Depends(richiede_dispositivo),
    conn: sqlite3.Connection = Depends(get_conn),
):
    """Elimina subito: togliere una sessione non allenta niente. La storia delle
    sessioni svolte resta, coi loro nomi congelati, e il nome torna libero. Non quella
    in corso: 409 sessione_in_corso (prima si termina). Il genitore e' avvisato, e la
    sua richiesta ancora aperta su questa sessione si chiude."""
    ora = clock.now()
    ts = clock.iso(ora)
    conn.execute("BEGIN IMMEDIATE")
    try:
        riga = _sessione_del_dispositivo(conn, sessione_id, chi)
        _dispositivo_vivo(conn, chi.dispositivo_id)
        aperta = _aperta(conn, riga["dispositivo_id"])
        if aperta is not None and aperta["sessione_id"] == sessione_id and not _scaduta(aperta, ora):
            raise HTTPException(status_code=409, detail={"errore": "sessione_in_corso"})
        conn.execute("UPDATE sessioni SET eliminata_ts = ? WHERE id = ?", (ts, sessione_id))
        _chiudi_avvisi_aperti(conn, riga)
        accoda_notifica(
            conn,
            "sessione_eliminata",
            f"{_nome_figlio(conn, riga['figlio_id'])} ha eliminato la sessione «{riga['nome']}»",
            {"sessione_id": sessione_id, "nome": riga["nome"]},
            ts,
            destinatario="genitore",
            figlio_id=riga["figlio_id"],
            dispositivo_id=riga["dispositivo_id"],
        )
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    return {"id": sessione_id, "eliminata": True}


# Prima delle route con {sessione_id}: "in_corso" non e' l'id di una sessione.
@router.post("/sessioni/in_corso/termina")
def termina_sessione(
    corpo: TerminaSessioneIn | None = None,
    chi: Identita = Depends(richiede_dispositivo),
    conn: sqlite3.Connection = Depends(get_conn),
):
    """Il figlio chiude prima la sessione in corso: 'terminata'. Di norma la fine e'
    l'arrivo al server. ts_device conta solo per una chiusura fatta senza rete e
    consegnata dopo: piu' di 2 minuti prima dell'arrivo (sotto, e' una chiusura con la
    rete e un orologio un po' storto: vale l'arrivo), dopo l'inizio, non dopo l'arrivo,
    ed entro 48 ore. Mai oltre la fine prevista: se la fine cosi' calcolata non viene
    prima, la sessione era gia' finita da sola, resta 'scaduta' e non c'e' niente in
    corso da chiudere (404). Una chiusura gia' scritta non si riscrive mai: si chiude
    solo la sessione aperta.

    `svolta_id` (facoltativo) dice quale sessione svolta chiudere: se non e' quella
    aperta, 404. Senza, una chiusura rimasta in coda sul telefono e consegnata dopo
    l'avvio di un'altra sessione chiuderebbe quella nuova (il suo ts_device cade prima
    dell'inizio, quindi varrebbe l'arrivo)."""
    corpo = corpo or TerminaSessioneIn()
    arrivo = clock.now()
    conn.execute("BEGIN IMMEDIATE")
    try:
        aperta = _aperta(conn, chi.dispositivo_id)
        if aperta is None or (corpo.svolta_id is not None and corpo.svolta_id != aperta["id"]):
            raise HTTPException(status_code=404, detail="nessuna sessione in corso")
        fine = arrivo
        dal_telefono = _da_ts_device(corpo.ts_device)
        if (
            dal_telefono is not None
            and arrivo - dal_telefono > TOLLERANZA_OROLOGIO
            and arrivo - dal_telefono <= RITARDO_MASSIMO
            and datetime.fromisoformat(aperta["inizio_ts"]) <= dal_telefono
        ):
            fine = dal_telefono
        if fine >= datetime.fromisoformat(aperta["fine_prevista_ts"]):
            raise HTTPException(status_code=404, detail="nessuna sessione in corso")
        conn.execute(
            "UPDATE sessioni_svolte SET fine_ts = ?, chiusura = 'terminata'"
            " WHERE id = ? AND fine_ts IS NULL",
            (clock.iso(fine), aperta["id"]),
        )
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    return formatta_svolta(_svolta(conn, aperta["id"]), arrivo, etichette_note(conn, chi.figlio_id))


@router.post("/sessioni/{sessione_id}/avvia", status_code=201)
def avvia_sessione(
    sessione_id: int,
    corpo: AvviaSessioneIn,
    chi: Identita = Depends(richiede_dispositivo),
    conn: sqlite3.Connection = Depends(get_conn),
):
    """Avvia la versione APPROVATA (anche con un cambio in attesa) per durata_minuti:
    nome, app e nomi si congelano nella sessione svolta, e un cambio approvato dopo
    vale dalla prossima. Una sola sessione in corso per dispositivo."""
    # Secondi interi, come ogni ts_server: la fine prevista e' esattamente inizio + durata.
    ora = clock.now().replace(microsecond=0)
    # BEGIN IMMEDIATE: "nessuna sessione in corso" e l'avvio sono un atto solo. Due
    # avvii simultanei (un doppio tocco) si mettono in fila e il secondo trova il primo.
    conn.execute("BEGIN IMMEDIATE")
    try:
        riga = _sessione_del_dispositivo(conn, sessione_id, chi)
        dispositivo = _dispositivo_vivo(conn, chi.dispositivo_id)
        if riga["stato"] != "approvata":
            raise HTTPException(status_code=409, detail={"errore": "sessione_non_approvata"})
        _chiudi_scadute(conn, chi.dispositivo_id, ora)
        if _aperta(conn, chi.dispositivo_id) is not None:
            raise HTTPException(status_code=409, detail={"errore": "sessione_gia_in_corso"})
        # (v3.6) Prima le faccende: col blocco attivo una sessione non parte. Dentro il
        # lock, come gli altri controlli: una faccenda data in quel momento conta. Solo
        # per i telefoni che conoscono le faccende (dalla 0.13): su quelli piu' vecchi il
        # blocco non c'e', e blocco_faccende sarebbe un codice che non sanno leggere.
        if faccende.conosce_le_faccende(dispositivo["versione_app"]) and faccende.blocco_attivo(
            conn, chi.figlio_id, ora
        ):
            raise HTTPException(status_code=409, detail={"errore": "blocco_faccende"})
        try:
            svolta_id = conn.execute(
                "INSERT INTO sessioni_svolte (sessione_id, dispositivo_id, nome, app, nomi,"
                " inizio_ts, durata_minuti, fine_prevista_ts) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    sessione_id, chi.dispositivo_id, riga["nome"], riga["app"], riga["nomi"],
                    clock.iso(ora), corpo.durata_minuti,
                    clock.iso(ora + timedelta(minutes=corpo.durata_minuti)),
                ),
            ).lastrowid
        except sqlite3.IntegrityError:
            # L'indice unico sulle aperte e' l'ultima parola: dentro il lock non
            # dovrebbe mai scattare, ma se scatta la risposta e' la stessa.
            raise HTTPException(status_code=409, detail={"errore": "sessione_gia_in_corso"})
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    return formatta_svolta(_svolta(conn, svolta_id), ora, etichette_note(conn, chi.figlio_id))


@router.post("/sessioni/{sessione_id}/risposta")
def rispondi_sessione(
    sessione_id: int,
    corpo: RispostaSessioneIn,
    chi: Identita = Depends(richiede_genitore),
    conn: sqlite3.Connection = Depends(get_conn),
):
    """Il genitore decide quello che e' in attesa: la sessione nuova (o cambiata prima
    di essere approvata) oppure il cambio chiesto su una sessione approvata. Decide su
    quello che ha visto: `versione` e' quella che ha sullo schermo, e se intanto il
    figlio ha cambiato la sessione non si decide niente (409 richiesta_cambiata, con la
    sessione com'e' adesso). Niente in attesa -> 409 niente_da_decidere; sessione di un
    dispositivo revocato -> 409 dispositivo_revocato. La risposta va al telefono della
    sessione, e la richiesta che il genitore non aveva ancora letto si chiude."""
    if corpo.figlio_id is not None:
        famiglia.figlio_o_404(conn, corpo.figlio_id)  # come gli altri endpoint del genitore
    ts = clock.iso(clock.now())
    # BEGIN IMMEDIATE: leggere cosa c'e' in attesa, controllare la versione e decidere
    # sono un atto solo. Un cambio del figlio che arriva insieme o passa prima (e la
    # versione non torna piu') o dopo (e diventa una richiesta nuova). Due decisioni
    # simultanee (un doppio tocco, due genitori) si mettono in fila: la seconda trova
    # gia' deciso e riceve 409 niente_da_decidere.
    conn.execute("BEGIN IMMEDIATE")
    try:
        ancora_valido(conn, chi)  # (v3.6) non revocato nel frattempo
        riga = _sessione_o_404(conn, sessione_id)
        if corpo.figlio_id is not None and riga["figlio_id"] != corpo.figlio_id:
            raise HTTPException(status_code=404, detail=NON_TROVATA)
        # Una sessione di un dispositivo revocato non si avvia piu': non c'e' niente da
        # decidere, ne' in un senso ne' nell'altro (e non si conta tra le da approvare).
        dispositivo = _dispositivo_vivo(conn, riga["dispositivo_id"])
        cambio = riga["stato"] == "approvata" and riga["modifica_in_attesa"] is not None
        if riga["stato"] != "in_attesa" and not cambio:
            raise HTTPException(status_code=409, detail={"errore": "niente_da_decidere"})
        firme = Firme(conn)
        if corpo.versione != riga["versione"]:
            raise HTTPException(
                status_code=409,
                detail={
                    "errore": "richiesta_cambiata",
                    "sessione": formatta_sessione(
                        riga, dispositivo, etichette_note(conn, riga["figlio_id"]), firme
                    ),
                },
            )
        approva = corpo.esito == "approva"
        nome = riga["nome"]
        if cambio and approva:
            # Il cambio diventa la versione approvata (approvata da adesso). La
            # motivazione di un rifiuto di prima non spiega piu' niente.
            modifica = json.loads(riga["modifica_in_attesa"])
            nome = modifica["nome"]
            conn.execute(
                "UPDATE sessioni SET nome = ?, app = ?, nomi = ?, modifica_in_attesa = NULL,"
                " motivazione = NULL, approvata_ts = ?, versione = versione + 1 WHERE id = ?",
                (nome, json.dumps(modifica["app"]), json.dumps(modifica["nomi"]), ts, sessione_id),
            )
        elif cambio:
            # Il cambio sparisce, resta la versione approvata; la motivazione si conserva.
            conn.execute(
                "UPDATE sessioni SET modifica_in_attesa = NULL, motivazione = ?,"
                " versione = versione + 1 WHERE id = ?",
                (corpo.motivazione, sessione_id),
            )
        elif approva:
            conn.execute(
                "UPDATE sessioni SET stato = 'approvata', motivazione = NULL, approvata_ts = ?,"
                " versione = versione + 1 WHERE id = ?",
                (ts, sessione_id),
            )
        else:
            conn.execute(
                "UPDATE sessioni SET stato = 'rifiutata', motivazione = ?, versione = versione + 1"
                " WHERE id = ?",
                (corpo.motivazione, sessione_id),
            )
        # (v3.6) Chi ha deciso: resta anche se poi il figlio cambia la sessione.
        conn.execute(
            "UPDATE sessioni SET decisa_genitore_id = ? WHERE id = ?", (chi.genitore_id, sessione_id)
        )
        _chiudi_avvisi_aperti(conn, riga)
        # Col nome che la sessione ha dopo la decisione (un cambio approvato puo'
        # averla rinominata): e' quello che il figlio vedra' nell'elenco. (v3.6) E col
        # nome del genitore che ha deciso, anche nel payload.
        verbo = "ha approvato" if approva else "non ha approvato"
        cosa = f"il cambio alla sessione «{nome}»" if cambio else f"la sessione «{nome}»"
        chi_decide = firme.di(chi.genitore_id)
        accoda_notifica(
            conn,
            "sessione_risposta",
            f"{chi_decide['nome']} {verbo} {cosa}",
            {"sessione_id": sessione_id, "nome": nome, "esito": corpo.esito, "cambio": cambio,
             "genitore": chi_decide},
            ts,
            destinatario="figlio",
            figlio_id=riga["figlio_id"],
            dispositivo_id=riga["dispositivo_id"],
        )
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    return formatta_sessione(
        _sessione_o_404(conn, sessione_id), dispositivo, etichette_note(conn, riga["figlio_id"]),
        Firme(conn),
    )
