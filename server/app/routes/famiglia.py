"""(v3) La famiglia vista e gestita dal genitore: figli, dispositivi, codici di
abbinamento, revoca. E l'abbinamento stesso, senza auth: il dispositivo nuovo non
ha ancora un token, ha solo il codice che gli ha dato il genitore.

(v3.6) Anche i genitori: piu' d'uno, tutti uguali, ciascuno col suo token. Un genitore
ne aggiunge un altro (nome -> codice di 6 cifre), lo rinomina, lo revoca; il genitore
nuovo si abbina con lo stesso POST /api/abbina dei dispositivi, con tipo "genitore".
Ogni scrittura di un genitore ricontrolla dentro il lock che chi la manda non sia stato
revocato nel frattempo (genitori.ancora_valido): 401 e niente scritto."""

import math
import sqlite3

from fastapi import APIRouter, Depends, HTTPException

from .. import abbinamento, clock, faccende, famiglia, genitori, semaforo, studio
from ..auth import Identita, richiede_genitore
from ..db import get_conn
from ..schemas import AbbinaIn, DispositivoIn, NomeIn
from .sessioni import sessioni_da_approvare

# (v3.6) Al massimo 10 genitori non revocati (gli abbinati e quelli che aspettano il
# codice): oltre, 409 troppi_genitori.
GENITORI_MASSIMI = 10

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
def leggi_famiglia(
    chi: Identita = Depends(richiede_genitore), conn: sqlite3.Connection = Depends(get_conn)
):
    """I figli in ordine di id, ciascuno con la sua striscia, il riepilogo, le
    notifiche per il genitore non ancora lette e i dispositivi in ordine di id,
    revocati compresi. (v3.4) E quante proposte del figlio aspettano il genitore.
    (v3.5) E quante sessioni (nuove o cambiate) aspettano la sua approvazione.
    (v3.6) Le notifiche non lette sono quelle di CHI CHIAMA; per ogni figlio quante
    faccende ha da fare e se il blocco e' attivo; e in cima chi chiama (`io`) e tutti
    i genitori, revocati compresi."""
    ora = clock.now()
    studio.valuta_tutti(conn, ora)  # (v4.0) partenze e mezzanotti dello Studio di ogni figlio
    non_letta, parametri_non_letta = genitori.non_letta(conn, chi.genitore_id)
    figli = []
    firme = genitori.Firme(conn)
    for figlio in conn.execute("SELECT * FROM figli ORDER BY id").fetchall():
        dispositivi = famiglia.dispositivi_del_figlio(conn, figlio["id"])
        quadro = semaforo.quadro(conn, ora, figlio["id"], dispositivi)
        non_lette = conn.execute(
            "SELECT COUNT(*) AS n FROM notifiche"
            f" WHERE destinatario = 'genitore' AND figlio_id = ? AND {non_letta}",
            (figlio["id"], *parametri_non_letta),
        ).fetchone()["n"]
        # (v3.4) Le pendenti con autore "figlio": quelle che decide il genitore. Non
        # quelle sulle regole di un dispositivo revocato: non si possono accettare,
        # solo rifiutare (restano visibili nella finestra).
        da_decidere = conn.execute(
            "SELECT COUNT(*) AS n FROM proposte p JOIN regole r ON r.id = p.regola_id"
            " LEFT JOIN dispositivi d ON d.id = r.dispositivo_id"
            " WHERE r.figlio_id = ? AND p.stato = 'pendente' AND p.autore = 'figlio'"
            " AND (r.dispositivo_id IS NULL OR d.revocato_ts IS NULL)",
            (figlio["id"],),
        ).fetchone()["n"]
        figli.append(
            {
                "id": figlio["id"],
                "nome": figlio["nome"],
                "striscia": quadro["striscia"],
                "riepilogo": quadro["riepilogo"],
                "notifiche_non_lette": non_lette,
                "proposte_da_decidere": da_decidere,
                "sessioni_da_approvare": sessioni_da_approvare(conn, figlio["id"]),  # (v3.5)
                "faccende_da_fare": faccende.quante_da_fare(conn, figlio["id"]),  # (v3.6)
                "blocco_attivo": faccende.blocco_attivo(conn, figlio["id"], ora),
                # (v4.0) i lavori che aspettano l'approvazione, il blocco che aspetta lo
                # Studio, lo Studio in corso e la sua configurazione da decidere
                "faccende_da_approvare": faccende.quante_da_approvare(conn, figlio["id"]),
                "blocco_rimandato": faccende.blocco(conn, figlio["id"], ora, firme)["rimandato"],
                "studio_in_corso": faccende.studio_in_corso(conn, figlio["id"]) is not None,
                "studio_da_approvare": studio.da_approvare(conn, figlio["id"]),
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
    tutti = conn.execute("SELECT * FROM genitori ORDER BY id").fetchall()
    return {
        "io": genitori.riferimento(next(g for g in tutti if g["id"] == chi.genitore_id)),
        "genitori": [
            {**genitori.riferimento(g), "revocato": g["revocato_ts"] is not None} for g in tutti
        ],
        "figli": figli,
    }


@router.post("/figli", status_code=201)
def crea_figlio(corpo: NomeIn, chi: Identita = Depends(richiede_genitore), conn: sqlite3.Connection = Depends(get_conn)):
    ts = clock.iso(clock.now())
    conn.execute("BEGIN IMMEDIATE")
    try:
        genitori.ancora_valido(conn, chi)  # (v3.6)
        figlio_id = conn.execute(
            "INSERT INTO figli (nome, creato_ts) VALUES (?, ?)", (corpo.nome, ts)
        ).lastrowid
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    return _figlio_out(famiglia.figlio_o_404(conn, figlio_id))


@router.patch("/figli/{figlio_id}")
def rinomina_figlio(
    figlio_id: int, corpo: NomeIn, chi: Identita = Depends(richiede_genitore), conn: sqlite3.Connection = Depends(get_conn)
):
    conn.execute("BEGIN IMMEDIATE")
    try:
        genitori.ancora_valido(conn, chi)  # (v3.6)
        famiglia.figlio_o_404(conn, figlio_id)
        conn.execute("UPDATE figli SET nome = ? WHERE id = ?", (corpo.nome, figlio_id))
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    return _figlio_out(famiglia.figlio_o_404(conn, figlio_id))


@router.post("/figli/{figlio_id}/dispositivi", status_code=201)
def crea_dispositivo(
    figlio_id: int,
    corpo: DispositivoIn,
    chi: Identita = Depends(richiede_genitore),
    conn: sqlite3.Connection = Depends(get_conn),
):
    """Il dispositivo nasce NON abbinato, col suo primo codice."""
    ora = clock.now()
    conn.execute("BEGIN IMMEDIATE")
    try:
        genitori.ancora_valido(conn, chi)  # (v3.6)
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
def nuovo_codice(
    dispositivo_id: int, chi: Identita = Depends(richiede_genitore), conn: sqlite3.Connection = Depends(get_conn)
):
    """Un codice nuovo per un dispositivo gia' creato: primo abbinamento non
    riuscito, o telefono reinstallato. Annulla il codice di prima; il token vecchio
    vale finche' il dispositivo non si abbina col codice nuovo."""
    ora = clock.now()
    conn.execute("BEGIN IMMEDIATE")
    try:
        genitori.ancora_valido(conn, chi)  # (v3.6)
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
def revoca_dispositivo(
    dispositivo_id: int, chi: Identita = Depends(richiede_genitore), conn: sqlite3.Connection = Depends(get_conn)
):
    """La revoca: il token del dispositivo smette di funzionare (401) e i suoi codici
    aperti si annullano. Niente si cancella: regole, registro e storia restano; le
    sue regole non contano piu' nella striscia dal giorno dopo. Rifarla non cambia
    niente (la data della revoca resta la prima)."""
    ts = clock.iso(clock.now())
    conn.execute("BEGIN IMMEDIATE")
    try:
        genitori.ancora_valido(conn, chi)  # (v3.6)
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

    (v3.6) Lo stesso per il codice di un genitore, con tipo "genitore": la risposta e'
    il token e il genitore. Un codice da genitore con un altro tipo (o senza: le app
    che non mandano il tipo sono quelle vecchie del figlio) e un codice da dispositivo
    con tipo "genitore" rispondono tipo_non_corrispondente, contano come tentativo
    fallito e non consumano il codice. I tentativi falliti sono gli stessi per tutti.

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
        del_genitore = (
            abbinamento.codice_genitore_valido(conn, corpo.codice, ora) if trovato is None else None
        )
        if trovato is None and del_genitore is None:
            abbinamento.registra_fallimento(conn, ora)
            conn.commit()  # il tentativo fallito resta contato anche se si risponde 409
            raise HTTPException(status_code=409, detail={"errore": "codice_non_valido"})
        tipo_atteso = "genitore" if del_genitore is not None else trovato["dispositivo_tipo"]
        # Senza tipo un codice da dispositivo va come prima (le app 0.7); un codice da
        # genitore no: il tipo "genitore" lo deve dire chi si abbina.
        if corpo.tipo != tipo_atteso and (corpo.tipo is not None or del_genitore is not None):
            # Conta come fallito: senza, il tipo sbagliato sarebbe un modo gratuito
            # per sapere se un codice provato a caso e' giusto.
            abbinamento.registra_fallimento(conn, ora)
            conn.commit()
            raise HTTPException(
                status_code=409,
                detail={"errore": "tipo_non_corrispondente", "tipo_atteso": tipo_atteso},
            )
        if del_genitore is not None:
            token = abbinamento.abbina_genitore(conn, del_genitore, ora)
        else:
            token = abbinamento.abbina(conn, trovato, corpo.versione_app, ora)
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    if del_genitore is not None:
        return {
            "token": token,
            "genitore": {"id": del_genitore["genitore_id"], "nome": del_genitore["genitore_nome"]},
        }
    return {
        "token": token,
        "dispositivo": {
            "id": trovato["dispositivo_id"],
            "nome": trovato["dispositivo_nome"],
            "tipo": trovato["dispositivo_tipo"],
        },
        "figlio": {"id": trovato["figlio_id"], "nome": trovato["figlio_nome"]},
    }


# --- (v3.6) i genitori ---

@router.get("/genitori")
def elenca_genitori(
    chi: Identita = Depends(richiede_genitore), conn: sqlite3.Connection = Depends(get_conn)
):
    """Chi chiama (`io`) e tutti i genitori in ordine di id, revocati compresi."""
    tutti = conn.execute("SELECT * FROM genitori ORDER BY id").fetchall()
    io = next(g for g in tutti if g["id"] == chi.genitore_id)
    return {"io": genitori.riferimento(io), "genitori": [genitori.descrizione(g) for g in tutti]}


@router.post("/genitori", status_code=201)
def crea_genitore(corpo: NomeIn, chi: Identita = Depends(richiede_genitore), conn: sqlite3.Connection = Depends(get_conn)):
    """Il genitore nasce NON abbinato, col suo primo codice. Le notifiche nate fin qui
    valgono come gia' lette per lui: non riceve la storia di prima, solo gli avvisi da
    adesso. Al massimo 10 non revocati: 409 troppi_genitori."""
    ora = clock.now()
    # BEGIN IMMEDIATE: il conteggio e l'inserimento sono un atto solo (due "aggiungi"
    # insieme non fanno l'undicesimo), e nessuna notifica nasce in mezzo tra la lettura
    # dell'ultimo id e la nascita del genitore.
    conn.execute("BEGIN IMMEDIATE")
    try:
        genitori.ancora_valido(conn, chi)
        vivi = conn.execute(
            "SELECT COUNT(*) AS n FROM genitori WHERE revocato_ts IS NULL"
        ).fetchone()["n"]
        if vivi >= GENITORI_MASSIMI:
            raise HTTPException(status_code=409, detail={"errore": "troppi_genitori"})
        ultima = conn.execute("SELECT COALESCE(MAX(id), 0) AS n FROM notifiche").fetchone()["n"]
        genitore_id = conn.execute(
            "INSERT INTO genitori (nome, creato_ts, notifiche_dopo_id) VALUES (?, ?, ?)",
            (corpo.nome, clock.iso(ora), ultima),
        ).lastrowid
        codice, scade_ts = abbinamento.crea_codice_genitore(conn, genitore_id, ora)
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    return {
        "genitore": genitori.descrizione(genitori.genitore_o_404(conn, genitore_id)),
        "codice": codice,
        "scade_ts": scade_ts,
    }


@router.post("/genitori/{genitore_id}/codice")
def nuovo_codice_genitore(
    genitore_id: int, chi: Identita = Depends(richiede_genitore), conn: sqlite3.Connection = Depends(get_conn)
):
    """Un codice nuovo per un genitore gia' creato (telefono cambiato o reinstallato).
    Annulla il codice di prima; il token vecchio vale finche' non si abbina col nuovo.
    Un genitore revocato no: 409 genitore_revocato (come per i dispositivi)."""
    ora = clock.now()
    conn.execute("BEGIN IMMEDIATE")
    try:
        genitori.ancora_valido(conn, chi)
        genitore = genitori.genitore_o_404(conn, genitore_id)
        if genitore["revocato_ts"] is not None:
            raise HTTPException(status_code=409, detail={"errore": "genitore_revocato"})
        codice, scade_ts = abbinamento.crea_codice_genitore(conn, genitore_id, ora)
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    return {"genitore": genitori.descrizione(genitore), "codice": codice, "scade_ts": scade_ts}


@router.patch("/genitori/{genitore_id}")
def rinomina_genitore(
    genitore_id: int, corpo: NomeIn, chi: Identita = Depends(richiede_genitore), conn: sqlite3.Connection = Depends(get_conn)
):
    """Anche un altro genitore: sono tutti uguali. Il nome nuovo vale ovunque, anche
    sulle decisioni di prima (le risposte dicono il nome di adesso)."""
    conn.execute("BEGIN IMMEDIATE")
    try:
        genitori.ancora_valido(conn, chi)
        genitori.genitore_o_404(conn, genitore_id)
        conn.execute("UPDATE genitori SET nome = ? WHERE id = ?", (corpo.nome, genitore_id))
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    return genitori.descrizione(genitori.genitore_o_404(conn, genitore_id))


@router.delete("/genitori/{genitore_id}")
def revoca_genitore(
    genitore_id: int,
    chi: Identita = Depends(richiede_genitore),
    conn: sqlite3.Connection = Depends(get_conn),
):
    """La revoca: il suo token smette di funzionare (401) e i suoi codici aperti si
    annullano. Niente si cancella: proposte, decisioni e faccende restano col suo nome.
    Non se stessi (409 non_te_stesso) e mai l'ultimo genitore non revocato (409
    ultimo_genitore). Rifarla non cambia niente. La revoca del genitore 1 resta anche
    dopo un riavvio: il token d'ambiente non lo resuscita (db._sincronizza_credenziali).

    BEGIN IMMEDIATE: due genitori che si revocano a vicenda nello stesso momento si
    mettono in fila; il secondo, appena revocato, riceve 401 (genitori.ancora_valido).
    Per questo ultimo_genitore non scatta piu' con un token valido: resta come rete."""
    ts = clock.iso(clock.now())
    conn.execute("BEGIN IMMEDIATE")
    try:
        genitori.ancora_valido(conn, chi)
        genitore = genitori.genitore_o_404(conn, genitore_id)
        if genitore_id == chi.genitore_id:
            raise HTTPException(status_code=409, detail={"errore": "non_te_stesso"})
        if genitore["revocato_ts"] is None:
            altri = conn.execute(
                "SELECT COUNT(*) AS n FROM genitori WHERE revocato_ts IS NULL AND id != ?",
                (genitore_id,),
            ).fetchone()["n"]
            if altri == 0:
                raise HTTPException(status_code=409, detail={"errore": "ultimo_genitore"})
            conn.execute("UPDATE genitori SET revocato_ts = ? WHERE id = ?", (ts, genitore_id))
            conn.execute(
                "UPDATE credenziali SET revocata_ts = ? WHERE genitore_id = ? AND revocata_ts IS NULL",
                (ts, genitore_id),
            )
            conn.execute(
                "UPDATE codici_genitori SET annullato_ts = ?"
                " WHERE genitore_id = ? AND usato_ts IS NULL AND annullato_ts IS NULL",
                (ts, genitore_id),
            )
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    return {"id": genitore_id, "revocato": True}
