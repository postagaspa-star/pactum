"""(v3) La famiglia vista e gestita dal genitore: figli, dispositivi, codici di
abbinamento, revoca. E l'abbinamento stesso, senza auth: il dispositivo nuovo non
ha ancora un token, ha solo il codice che gli ha dato il genitore."""

import math
import sqlite3

from fastapi import APIRouter, Depends, HTTPException

from .. import abbinamento, clock, famiglia, semaforo
from ..auth import richiede_genitore
from ..db import get_conn
from ..schemas import AbbinaIn, DispositivoIn, NomeIn

router = APIRouter(dependencies=[Depends(richiede_genitore)])
abbina_router = APIRouter()  # nessun auth: e' il codice a fare da chiave


def _figlio_out(riga: sqlite3.Row) -> dict:
    return {"id": riga["id"], "nome": riga["nome"], "creato_ts": riga["creato_ts"]}


def _dispositivo_out(riga: sqlite3.Row) -> dict:
    return {
        **famiglia.descrizione(riga),
        "figlio_id": riga["figlio_id"],
        "versione_app": riga["versione_app"],
    }


def _dispositivo_o_404(conn: sqlite3.Connection, dispositivo_id: int) -> sqlite3.Row:
    riga = conn.execute("SELECT * FROM dispositivi WHERE id = ?", (dispositivo_id,)).fetchone()
    if riga is None:
        raise HTTPException(status_code=404, detail="dispositivo non trovato")
    return riga


@router.get("/famiglia")
def leggi_famiglia(conn: sqlite3.Connection = Depends(get_conn)):
    """I figli in ordine di id, ciascuno con la sua striscia, il riepilogo, le
    notifiche per il genitore non ancora lette e i dispositivi in ordine di id,
    revocati compresi."""
    ora = clock.now()
    figli = []
    for figlio in conn.execute("SELECT * FROM figli ORDER BY id").fetchall():
        dispositivi = famiglia.dispositivi_del_figlio(conn, figlio["id"])
        quadro = semaforo.quadro(conn, ora, figlio["id"], dispositivi)
        non_lette = conn.execute(
            "SELECT COUNT(*) AS n FROM notifiche"
            " WHERE letta = 0 AND destinatario = 'genitore' AND figlio_id = ?",
            (figlio["id"],),
        ).fetchone()["n"]
        figli.append(
            {
                "id": figlio["id"],
                "nome": figlio["nome"],
                "striscia": quadro["striscia"],
                "riepilogo": quadro["riepilogo"],
                "notifiche_non_lette": non_lette,
                "dispositivi": [
                    {
                        **famiglia.descrizione(d),
                        "versione_app": d["versione_app"],
                        "stato_silenzio": famiglia.stato_silenzio(conn, d, ora),
                    }
                    for d in dispositivi
                ],
            }
        )
    return {"figli": figli}


@router.post("/figli", status_code=201)
def crea_figlio(corpo: NomeIn, conn: sqlite3.Connection = Depends(get_conn)):
    ts = clock.iso(clock.now())
    figlio_id = conn.execute(
        "INSERT INTO figli (nome, creato_ts) VALUES (?, ?)", (corpo.nome, ts)
    ).lastrowid
    conn.commit()
    return _figlio_out(famiglia.figlio_o_404(conn, figlio_id))


@router.patch("/figli/{figlio_id}")
def rinomina_figlio(figlio_id: int, corpo: NomeIn, conn: sqlite3.Connection = Depends(get_conn)):
    famiglia.figlio_o_404(conn, figlio_id)
    conn.execute("UPDATE figli SET nome = ? WHERE id = ?", (corpo.nome, figlio_id))
    conn.commit()
    return _figlio_out(famiglia.figlio_o_404(conn, figlio_id))


@router.post("/figli/{figlio_id}/dispositivi", status_code=201)
def crea_dispositivo(
    figlio_id: int, corpo: DispositivoIn, conn: sqlite3.Connection = Depends(get_conn)
):
    """Il dispositivo nasce NON abbinato, col suo primo codice."""
    ora = clock.now()
    conn.execute("BEGIN IMMEDIATE")
    try:
        famiglia.figlio_o_404(conn, figlio_id)
        dispositivo_id = conn.execute(
            "INSERT INTO dispositivi (figlio_id, nome, tipo, creato_ts) VALUES (?, ?, ?, ?)",
            (figlio_id, corpo.nome, corpo.tipo, clock.iso(ora)),
        ).lastrowid
        codice, scade_ts = abbinamento.crea_codice(conn, dispositivo_id, ora)
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    return {
        "dispositivo": _dispositivo_out(_dispositivo_o_404(conn, dispositivo_id)),
        "codice": codice,
        "scade_ts": scade_ts,
    }


@router.post("/dispositivi/{dispositivo_id}/codice")
def nuovo_codice(dispositivo_id: int, conn: sqlite3.Connection = Depends(get_conn)):
    """Un codice nuovo per un dispositivo gia' creato: primo abbinamento non
    riuscito, o telefono reinstallato. Annulla il codice di prima; il token vecchio
    vale finche' il dispositivo non si abbina col codice nuovo."""
    ora = clock.now()
    conn.execute("BEGIN IMMEDIATE")
    try:
        dispositivo = _dispositivo_o_404(conn, dispositivo_id)
        if dispositivo["revocato_ts"] is not None:
            # Una revoca non si annulla con un codice: per ricominciare si crea un
            # dispositivo nuovo, e la storia di quello revocato resta com'e'.
            raise HTTPException(status_code=409, detail={"errore": "dispositivo_revocato"})
        codice, scade_ts = abbinamento.crea_codice(conn, dispositivo_id, ora)
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    return {"dispositivo": _dispositivo_out(dispositivo), "codice": codice, "scade_ts": scade_ts}


@router.delete("/dispositivi/{dispositivo_id}")
def revoca_dispositivo(dispositivo_id: int, conn: sqlite3.Connection = Depends(get_conn)):
    """La revoca: il token del dispositivo smette di funzionare (401) e i suoi codici
    aperti si annullano. Niente si cancella: regole, registro e storia restano; le
    sue regole non contano piu' nella striscia dal giorno dopo. Rifarla non cambia
    niente (la data della revoca resta la prima)."""
    ts = clock.iso(clock.now())
    conn.execute("BEGIN IMMEDIATE")
    try:
        dispositivo = _dispositivo_o_404(conn, dispositivo_id)
        if dispositivo["revocato_ts"] is None:
            conn.execute("UPDATE dispositivi SET revocato_ts = ? WHERE id = ?", (ts, dispositivo_id))
            conn.execute(
                "UPDATE credenziali SET revocata_ts = ?"
                " WHERE dispositivo_id = ? AND revocata_ts IS NULL",
                (ts, dispositivo_id),
            )
            conn.execute(
                "UPDATE codici_abbinamento SET annullato_ts = ?"
                " WHERE dispositivo_id = ? AND usato_ts IS NULL AND annullato_ts IS NULL",
                (ts, dispositivo_id),
            )
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    return {"id": dispositivo_id, "revocato": True}


@abbina_router.post("/abbina")
def abbina(corpo: AbbinaIn, conn: sqlite3.Connection = Depends(get_conn)):
    """Il codice diventa il token del dispositivo, restituito UNA volta sola.
    409 codice_non_valido per un codice sbagliato, scaduto o gia' usato (stessa
    risposta per tutti e tre); 429 troppi_tentativi quando l'abbinamento e' bloccato.
    (v3.1) 409 tipo_non_corrispondente se il codice e' di un dispositivo di un altro
    tipo: un computer non prende il posto del telefono (o il contrario) per un codice
    scritto male. Il codice non si consuma, ma il tentativo conta come fallito.

    BEGIN IMMEDIATE: blocco, codice e token sono un unico atto, cosi' lo stesso
    codice non abbina due volte e i tentativi falliti si contano tutti anche sotto
    richieste simultanee."""
    ora = clock.now()
    conn.execute("BEGIN IMMEDIATE")
    try:
        fine = abbinamento.fine_blocco(conn, ora)
        if fine is not None:
            secondi = max(1, math.ceil((fine - ora).total_seconds()))
            raise HTTPException(
                status_code=429,
                detail={"errore": "troppi_tentativi", "riprova_tra_secondi": secondi},
                headers={"Retry-After": str(secondi)},
            )
        trovato = abbinamento.codice_valido(conn, corpo.codice, ora)
        if trovato is None:
            abbinamento.registra_fallimento(conn, ora)
            conn.commit()  # il tentativo fallito resta contato anche se si risponde 409
            raise HTTPException(status_code=409, detail={"errore": "codice_non_valido"})
        if corpo.tipo is not None and corpo.tipo != trovato["dispositivo_tipo"]:
            # Conta come fallito: senza, il tipo sbagliato sarebbe un modo gratuito
            # per sapere se un codice provato a caso e' giusto.
            abbinamento.registra_fallimento(conn, ora)
            conn.commit()
            raise HTTPException(
                status_code=409,
                detail={"errore": "tipo_non_corrispondente", "tipo_atteso": trovato["dispositivo_tipo"]},
            )
        token = abbinamento.abbina(conn, trovato, corpo.versione_app, ora)
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    return {
        "token": token,
        "dispositivo": {
            "id": trovato["dispositivo_id"],
            "nome": trovato["dispositivo_nome"],
            "tipo": trovato["dispositivo_tipo"],
        },
        "figlio": {"id": trovato["figlio_id"], "nome": trovato["figlio_nome"]},
    }
