"""Endpoint del genitore: la finestra (non vetrata) e il segno di riconoscimento.
Il silenzio si calcola in lettura: nessun job in background nella v1.

(v3) La finestra e' di UN figlio (`figlio_id`, o il primo): regole, striscia,
storico ed eventi di tutti i suoi dispositivi, e in `dispositivi` tutto quello che
e' per dispositivo. I campi di primo livello per dispositivo (tempi, siti, medie,
bonus, silenzio) restano e valgono per il primo dispositivo del figlio: l'app del
genitore 0.7 continua a leggere quelli.

(v3.6) Nella finestra anche le faccende del figlio e il suo blocco; il segno dice
quale genitore l'ha mandato."""

import json
import sqlite3
from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException, Request

from .. import clock, faccende, famiglia, semaforo, siti, studio, tempi
from ..auth import Identita, richiede_genitore
from ..db import accoda_notifica, get_conn, segno_mandato_oggi, stato_bonus
from ..genitori import Firme, ancora_valido
from ..schemas import CHIAVE_TOTALE, SegnoIn
from .faccende import cartella
from .proposte import formatta_proposta, proposte_del_figlio
from .regole import _riga_regola
from .sessioni import inizio_finestra as sessioni_inizio_finestra
from .sessioni import sessioni_da_approvare, sessioni_del_figlio, sessioni_svolte_del_figlio

router = APIRouter(dependencies=[Depends(richiede_genitore)])

# La finestra e' lunga 8 giorni (oggi + i 7 precedenti): la misura sta in
# siti.GIORNI_FINESTRA, unica per tutte le sezioni.
RECENTI = 20
STORICO_MASSIMO = 50

# (v2.4) Il testo del segno e' fisso: non lo sceglie il genitore (contratto-api.md).
MESSAGGIO_SEGNO = "Ho visto la settimana. Bene così."


def _evento_out(riga: sqlite3.Row) -> dict:
    return {
        "id": riga["id"],
        "tipo": riga["tipo"],
        "dettagli": json.loads(riga["dettagli"]),
        "ts_device": riga["ts_device"],
        "ts_server": riga["ts_server"],
        "dispositivo_id": riga["dispositivo_id"],
    }


def _eventi_recenti(conn: sqlite3.Connection, dispositivi: list, tipo: str) -> list:
    """Gli ultimi RECENTI eventi di un tipo, di tutti i dispositivi del figlio, dal
    piu' recente (contratto: max 20 ciascuno, senza limite di data).

    (v3.1) Il limite sta nella query, una per dispositivo: con l'indice
    (tipo, dispositivo_id, ts_server) ciascuna legge solo le sue ultime 20 righe,
    mentre una query sola su piu' dispositivi dovrebbe ordinare tutta la storia.
    Poi i pochi candidati si mettono in fila con lo stesso ordine della query."""
    candidati = []
    for dispositivo in dispositivi:
        candidati += conn.execute(
            "SELECT * FROM eventi WHERE tipo = ? AND dispositivo_id = ?"
            " ORDER BY ts_server DESC, id DESC LIMIT ?",
            (tipo, dispositivo["id"], RECENTI),
        ).fetchall()
    candidati.sort(key=lambda e: (e["ts_server"], e["id"]), reverse=True)
    return [_evento_out(e) for e in candidati[:RECENTI]]


def _misure(
    conn: sqlite3.Connection,
    ora: datetime,
    giorni: list,
    dispositivo: sqlite3.Row | None,
    righe_regole: list,
) -> dict:
    """(v3) Tutto quello che e' per dispositivo, come in v2.4 era per il telefono:
    tempi, siti, medie, bonus, bonus del giorno e silenzio. Senza dispositivo
    (figlio appena creato, o tutti revocati) le stesse forme senza dati: null e
    liste vuote, mai zeri finti."""
    dispositivo_id = dispositivo["id"] if dispositivo is not None else None
    # (v3.8) Tempi, medie e bonus del giorno escono da tempi.py, le stesse funzioni
    # del patto del figlio: il figlio vede i suoi tempi identici a questi.
    misure = tempi.tempi(conn, ora, giorni, dispositivo_id, righe_regole)
    return {
        "stato_silenzio": famiglia.stato_silenzio(conn, dispositivo, ora),
        "uso_recente": misure["uso_recente"],
        # (v2.3) I siti visitati: il genitore vede QUALI siti, mai cosa ci fa
        # dentro. Stessa funzione di GET /api/patto — il figlio vede la stessa
        # identica lista (tavola rotonda). Non entra nel semaforo: non e' un'infrazione.
        "siti_recenti": siti.siti_recenti(
            conn, ora, dispositivo_id, dispositivo["tipo"] if dispositivo is not None else "telefono"
        ),
        "medie": misure["medie"],
        "bonus": stato_bonus(conn, ora, dispositivo_id),
        "bonus_giornalieri": misure["bonus_giornalieri"],
    }


@router.get("/finestra")
def finestra(
    request: Request, figlio_id: int | None = None, conn: sqlite3.Connection = Depends(get_conn)
):
    ora = clock.now()
    figlio = famiglia.figlio_scelto(conn, figlio_id)
    studio.valuta(conn, figlio["id"], ora)  # (v4.0) partenze e mezzanotti dello Studio
    firme = Firme(conn)
    # Una sola definizione degli 8 giorni (siti.giorni_finestra) per semaforo,
    # bonus_giornalieri, uso_recente e siti_recenti: cosi' le sezioni della
    # finestra non possono raccontare finestre temporali diverse. I giorni sono
    # giorni LOCALI del patto (contratto-api.md): in UTC il confine cadrebbe alle
    # 02:00 locali italiane.
    giorni = siti.giorni_finestra(ora)
    dispositivi = famiglia.dispositivi_del_figlio(conn, figlio["id"])
    per_id = {d["id"]: d for d in dispositivi}

    # Il semaforo per regola si calcola in semaforo.py: da li' esce anche la
    # striscia aggregata, condivisa con GET /api/patto. Le regole si leggono
    # PRIMA dei semafori: le righe non si cancellano mai (soft-delete), quindi
    # ogni regola letta qui ha il suo semaforo anche se intanto ne nasce una.
    righe_regole = conn.execute(
        "SELECT * FROM regole WHERE figlio_id = ? ORDER BY id", (figlio["id"],)
    ).fetchall()
    quadro = semaforo.quadro(conn, ora, figlio["id"], dispositivi)
    nomi = famiglia.nomi_recenti(conn, figlio["id"], giorni[-1])
    regole = []
    for riga in righe_regole:
        voce = {**_riga_regola(riga, per_id), "semaforo": quadro["semafori"][riga["id"]]}
        if riga["tipo"] == "limite_tempo":
            chiave = json.loads(riga["parametri"])["app_o_categoria"]
            # (S2) Le categorie le traduce l'app: il nome si allega solo ai pacchetti.
            # (v3.3) Neanche al totale: "Tutto il telefono" / "Tutto il computer" lo
            # scrivono le app dal tipo del dispositivo.
            if not chiave.startswith("categoria:") and chiave != CHIAVE_TOTALE:
                voce["nome"] = nomi.get(chiave) or chiave
        regole.append(voce)

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
            "SELECT s.* FROM storico_modifiche s JOIN regole r ON r.id = s.regola_id"
            " WHERE r.figlio_id = ? ORDER BY s.id DESC LIMIT ?",
            (figlio["id"], STORICO_MASSIMO),
        ).fetchall()
    ]

    per_dispositivo = [
        {
            **famiglia.descrizione(d),
            **_misure(conn, ora, giorni, d, righe_regole),
            "striscia": quadro["strisce_dispositivi"][d["id"]],
        }
        for d in dispositivi
    ]
    primo = famiglia.primo_dispositivo(dispositivi)
    if primo is not None:
        compatibili = next(v for v in per_dispositivo if v["id"] == primo["id"])
    else:
        compatibili = _misure(conn, ora, giorni, None, righe_regole)

    return {
        "regole": regole,
        "sforamenti_recenti": _eventi_recenti(conn, dispositivi, "sforamento"),
        "manomissioni_recenti": _eventi_recenti(conn, dispositivi, "manomissione"),
        "storico_modifiche": storico,
        "bonus": compatibili["bonus"],
        "bonus_giornalieri": compatibili["bonus_giornalieri"],
        "stato_silenzio": compatibili["stato_silenzio"],
        "uso_recente": compatibili["uso_recente"],
        "siti_recenti": compatibili["siti_recenti"],
        "medie": compatibili["medie"],
        # (v2.4) Stessa funzione di GET /api/patto: le due app mostrano la stessa
        # striscia per costruzione. (v3) E' la striscia del figlio, su tutti i suoi
        # dispositivi e sulla vita reale.
        "striscia": quadro["striscia"],
        "riepilogo": quadro["riepilogo"],
        "segno_oggi": segno_mandato_oggi(conn, ora, figlio["id"]),
        "dispositivi": per_dispositivo,
        # (v3.4) Le proposte pendenti del figlio, di tutti e due gli autori, dalla piu'
        # recente, col confronto ricalcolato: quelle con autore "figlio" aspettano il
        # genitore, quelle con autore "genitore" il figlio.
        "proposte_pendenti": [
            formatta_proposta(r, conn, firme)
            for r in proposte_del_figlio(conn, figlio["id"], solo_pendenti=True)
        ],
        # (v3.5) Le sessioni di tutti i dispositivi del figlio, quante decisioni
        # aspettano il genitore, e le sessioni svolte negli 8 giorni: inizio, durata,
        # fine e chiusure anticipate (mai quali app ha provato ad aprire). Le etichette
        # delle app sono quelle dell'uso (`nomi`, le stesse del `nome` delle regole).
        "sessioni": sessioni_del_figlio(conn, figlio["id"], nomi, dispositivi),
        "sessioni_da_approvare": sessioni_da_approvare(conn, figlio["id"]),
        "sessioni_svolte": sessioni_svolte_del_figlio(conn, figlio["id"], ora, nomi),
        # (v3.6) Le faccende del figlio (come GET /api/faccende) e il suo blocco (come
        # GET /api/faccende/blocco): quello che vede il figlio, uguale.
        "faccende": faccende.faccende_del_figlio(conn, figlio["id"], ora, firme, cartella(request)),
        "blocco": faccende.blocco(conn, figlio["id"], ora, firme),
        # (v4.0) Quanti lavori aspettano l'approvazione; lo Studio come nel patto, gli
        # Studi che toccano gli 8 giorni (al massimo 50) e la configurazione da decidere.
        "faccende_da_approvare": faccende.quante_da_approvare(conn, figlio["id"]),
        "studio": studio.vista_per_i_dispositivi(conn, figlio["id"], ora, nomi),
        "studio_svolte": studio.svolte_della_finestra(
            conn, figlio["id"], ora, sessioni_inizio_finestra(ora), nomi
        ),
        "studio_da_approvare": studio.da_approvare(conn, figlio["id"]),
    }


@router.post("/segno")
def manda_segno(
    corpo: SegnoIn | None = None,
    chi: Identita = Depends(richiede_genitore),
    conn: sqlite3.Connection = Depends(get_conn),
):
    """(v2.4) Il segno di riconoscimento al figlio: testo fisso, al massimo uno al
    giorno nel fuso del patto. Nessuna traccia nel registro eventi: e' un gesto del
    genitore, non un fatto del patto. (v3) Uno al giorno PER FIGLIO (`figlio_id`,
    o il primo), notificato a tutti i suoi dispositivi. (v3.6) Uno al giorno per
    figlio anche con piu' genitori (il primo che lo manda); dice chi l'ha mandato,
    nella risposta (`da`) e nel payload (`genitore`). Il testo resta quello."""
    figlio = famiglia.figlio_scelto(conn, corpo.figlio_id if corpo is not None else None)
    # BEGIN IMMEDIATE: il controllo "gia' mandato oggi" e l'invio devono essere un
    # unico atto, altrimenti due tocchi simultanei leggono entrambi "non ancora" e
    # partono due segni. Chi arriva secondo rilegge dentro il lock e viene respinto.
    ora = clock.now()
    ts = clock.iso(ora)
    conn.execute("BEGIN IMMEDIATE")
    try:
        ancora_valido(conn, chi)  # (v3.6) non revocato nel frattempo
        if segno_mandato_oggi(conn, ora, figlio["id"]):
            raise HTTPException(status_code=409, detail={"errore": "segno_gia_mandato"})
        da = Firme(conn).di(chi.genitore_id)
        accoda_notifica(
            conn, "segno", MESSAGGIO_SEGNO, {"genitore": da}, ts, destinatario="figlio",
            figlio_id=figlio["id"], dispositivo_id=None,
        )
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    return {"mandato": True, "ts_server": ts, "da": da}
