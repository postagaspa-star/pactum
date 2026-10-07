"""Endpoint del dispositivo (il vecchio ruolo `figlio`): battito (heartbeat), eventi
in batch, bonus, patto. (v3) Tutto quello che arriva appartiene al dispositivo del
token: eventi, battiti, fotografie e bonus sono per dispositivo; il figlio e'
quello del dispositivo, e da qui non si vede ne' si tocca niente di un altro."""

import json
import sqlite3

from fastapi import APIRouter, Depends, HTTPException, Request

from .. import clock, faccende, famiglia, semaforo, siti, studio
from ..auth import Identita, richiede_dispositivo
from ..config import MINUTI_IN_UN_GIORNO, nome_fuso
from ..db import (
    accoda_notifica,
    bonus_oggi_per_regola,
    get_conn,
    stato_bonus,
)
from ..genitori import Firme
from ..tempi import tempi as tempi_del_dispositivo
from ..schemas import BattitoIn, BonusIn, EventiIn, EventoIn
from .dichiarazioni import dichiarazioni_del_figlio, formatta_dichiarazione
from .faccende import cartella
from .proposte import formatta_proposta, proposte_del_figlio
from .regole import _riga_regola
from .sessioni import (
    etichette_note,
    sessione_in_corso,
    sessioni_del_dispositivo,
    sessioni_svolte_del_dispositivo,
)

router = APIRouter(dependencies=[Depends(richiede_dispositivo)])

# riavvio NON e' qui per scelta (contratto-api.md): marca l'azzeramento di
# elapsed_realtime, non e' una manomissione e non genera notifiche. Neanche
# sospensione e ripresa (v3): un computer spento la sera e' normale.
TIPI_EVENTO_DA_NOTIFICARE = {"sforamento", "manomissione"}


@router.post("/battito")
def battito(
    corpo: BattitoIn | None = None,
    chi: Identita = Depends(richiede_dispositivo),
    conn: sqlite3.Connection = Depends(get_conn),
):
    corpo = corpo or BattitoIn()
    ora = clock.now()
    ts = clock.iso(ora)
    # (v4.0) Prima le partenze dello Studio, le mezzanotti e i computer spariti del figlio
    # (anche col battito del computer): prima di scrivere questo battito, cosi' un
    # computer che torna dopo essere sparito resta nel registro.
    studio.valuta(conn, chi.figlio_id, ora)
    conn.execute(
        "INSERT INTO battiti"
        " (batteria, versione_app, elapsed_realtime, ts_device, ts_server, dispositivo_id)"
        " VALUES (?, ?, ?, ?, ?, ?)",
        (corpo.batteria, corpo.versione_app, corpo.elapsed_realtime, corpo.ts_device, ts,
         chi.dispositivo_id),
    )
    if corpo.versione_app:
        # (v3) La versione che il genitore vede nella famiglia: l'ultima dichiarata.
        conn.execute(
            "UPDATE dispositivi SET versione_app = ? WHERE id = ?",
            (corpo.versione_app, chi.dispositivo_id),
        )
    # (v4.0) Questo server conosce lo Studio e ha sentito il dispositivo: al ritorno dalla
    # v3.9 si riconosce dai battiti arrivati dopo l'ultimo giro (db._migra_v40).
    studio.segna_giro(conn, ora)
    conn.commit()
    return {"ricevuto": True}


def _totale_minuti(dettagli: dict) -> int:
    """totale_minuti mancante o non valido vale 0 (contratto-api.md). (v3.5) Non e'
    valido nemmeno oltre i minuti di un giorno: un totale assurdo non diventa la
    fotografia vigente e non sporca le medie."""
    totale = dettagli.get("totale_minuti")
    if isinstance(totale, bool) or not isinstance(totale, int) or not 0 <= totale <= MINUTI_IN_UN_GIORNO:
        return 0
    return totale


def _aggiorna_uso_giornaliero(
    conn: sqlite3.Connection, dispositivo_id: int, evento: EventoIn, ts: str
) -> None:
    """uso_giornaliero e' una fotografia CUMULATIVA del giorno e la vigente e'
    MONOTONA su totale_minuti (contratto-api.md): una fotografia arrivata in
    ritardo con un totale piu' basso non regredisce quella vigente. Il registro
    eventi conserva comunque tutte le fotografie; qui si aggiorna solo la vigente.
    (v3) La vigente e' per (dispositivo, giorno)."""
    giorno = evento.dettagli.get("giorno")
    if not clock.giorno_valido(giorno):
        # Senza un giorno valido non c'e' fotografia da indicizzare:
        # l'evento resta comunque nel registro.
        return
    conn.execute(
        "INSERT INTO uso_giornaliero"
        " (dispositivo_id, giorno, dettagli, evento_id, ts_server, totale_minuti)"
        " VALUES (?, ?, ?, ?, ?, ?)"
        " ON CONFLICT(dispositivo_id, giorno) DO UPDATE SET"
        " dettagli = excluded.dettagli,"
        " evento_id = excluded.evento_id,"
        " ts_server = excluded.ts_server,"
        " totale_minuti = excluded.totale_minuti"
        " WHERE excluded.totale_minuti >= uso_giornaliero.totale_minuti",
        (dispositivo_id, giorno, json.dumps(evento.dettagli), evento.id, ts,
         _totale_minuti(evento.dettagli)),
    )


@router.post("/eventi")
def registra_eventi(
    corpo: EventiIn,
    chi: Identita = Depends(richiede_dispositivo),
    conn: sqlite3.Connection = Depends(get_conn),
):
    """Batch idempotente: l'id lo genera il client, un id gia' visto viene ignorato."""
    ts = clock.iso(clock.now())
    nuovi = 0
    duplicati = 0
    for evento in corpo.eventi:
        cursore = conn.execute(
            "INSERT OR IGNORE INTO eventi (id, tipo, dettagli, ts_device, ts_server, dispositivo_id)"
            " VALUES (?, ?, ?, ?, ?, ?)",
            (evento.id, evento.tipo, json.dumps(evento.dettagli), evento.ts_device, ts,
             chi.dispositivo_id),
        )
        if cursore.rowcount:
            nuovi += 1
            if evento.tipo == "uso_giornaliero":
                _aggiorna_uso_giornaliero(conn, chi.dispositivo_id, evento, ts)
            if evento.tipo == "siti_giornalieri":
                # (v2.3) Stessa filosofia di uso_giornaliero: il registro conserva
                # ogni fotografia, la vigente del giorno vive in siti_giornalieri
                # ed e' monotona. Nessuna notifica: un sito visitato non e' uno
                # sforamento e non viene mai trattato come tale.
                siti.aggiorna_vigente(conn, chi.dispositivo_id, evento.id, evento.dettagli, ts)
            if evento.tipo in TIPI_EVENTO_DA_NOTIFICARE:
                accoda_notifica(
                    conn,
                    evento.tipo,
                    f"Evento {evento.tipo} registrato",
                    {"evento_id": evento.id, "dettagli": evento.dettagli},
                    ts,
                    figlio_id=chi.figlio_id,
                    dispositivo_id=chi.dispositivo_id,
                )
        else:
            duplicati += 1
    conn.commit()
    return {"ricevuti": len(corpo.eventi), "nuovi": nuovi, "duplicati": duplicati}


@router.post("/bonus")
def concedi_bonus(
    corpo: BonusIn,
    chi: Identita = Depends(richiede_dispositivo),
    conn: sqlite3.Connection = Depends(get_conn),
):
    # Risposta 200 con i residui aggiornati (contratto-api.md), 409 se un tetto salta.
    # Il bonus allunga una regola limite_tempo ATTIVA specifica (v2): si allunga un
    # limite, non il vuoto -> 409 regola_non_valida se la regola non esiste, non e'
    # attiva o non e' di tipo limite_tempo. (v3) E dev'essere una regola di QUESTO
    # dispositivo: i bonus e i loro tetti sono per dispositivo.
    regola = conn.execute(
        "SELECT tipo, dispositivo_id FROM regole WHERE id = ? AND attiva = 1", (corpo.regola_id,)
    ).fetchone()
    if (
        regola is None
        or regola["tipo"] != "limite_tempo"
        or regola["dispositivo_id"] != chi.dispositivo_id
    ):
        raise HTTPException(status_code=409, detail={"errore": "regola_non_valida"})

    # BEGIN IMMEDIATE: leggi-controlla-inserisci deve essere atomico, altrimenti
    # N richieste simultanee leggono lo stesso residuo e il tetto salta N volte.
    # Il lock di scrittura serializza i concorrenti; chi arriva secondo rilegge
    # i contatori gia' aggiornati.
    ora = clock.now()
    conn.execute("BEGIN IMMEDIATE")
    try:
        stato = stato_bonus(conn, ora, chi.dispositivo_id)
        residuo_giorno = stato["giorno"]["residui"]
        residuo_settimana = stato["settimana"]["residui"]
        if corpo.minuti > residuo_giorno or corpo.minuti > residuo_settimana:
            raise HTTPException(
                status_code=409,
                detail={
                    "errore": "tetto_superato",
                    "residuo_giorno": residuo_giorno,
                    "residuo_settimana": residuo_settimana,
                },
            )
        ts = clock.iso(ora)
        conn.execute(
            "INSERT INTO bonus (minuti, regola_id, motivo, ts_server, dispositivo_id)"
            " VALUES (?, ?, ?, ?, ?)",
            (corpo.minuti, corpo.regola_id, corpo.motivo, ts, chi.dispositivo_id),
        )
        accoda_notifica(
            conn,
            "bonus",
            f"Bonus di {corpo.minuti} minuti auto-concesso",
            {
                "minuti": corpo.minuti,
                "regola_id": corpo.regola_id,
                "motivo": corpo.motivo,
                "residuo_giorno": residuo_giorno - corpo.minuti,
                "residuo_settimana": residuo_settimana - corpo.minuti,
            },
            ts,
            figlio_id=chi.figlio_id,
            dispositivo_id=chi.dispositivo_id,
        )
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    return {
        "minuti": corpo.minuti,
        "residuo_giorno": residuo_giorno - corpo.minuti,
        "residuo_settimana": residuo_settimana - corpo.minuti,
    }


@router.get("/patto")
def patto(
    request: Request,
    tempi: str | None = None,
    chi: Identita = Depends(richiede_dispositivo),
    conn: sqlite3.Connection = Depends(get_conn),
):
    """Lo stato completo del patto per il sync dell'app del figlio, in una risposta
    sola: regole attive (col semaforo, v2.4), residui bonus, bonus di oggi per regola
    (per il limite efficace del valutatore locale), proposte pendenti, dichiarazioni
    in attesa, siti recenti, striscia, fuso del patto.

    `siti_recenti` (v2.3) e `striscia` (v2.4) escono dalle STESSE funzioni che
    alimentano GET /api/finestra: il figlio vede quello che vede il genitore, voce
    per voce. Tavola rotonda: niente esiste nella finestra del genitore che il
    figlio non veda.

    (v3) Regole, bonus e siti sono di QUESTO dispositivo (piu' la vita reale del
    figlio); proposte, dichiarazioni, striscia e riepilogo sono di tutto il figlio.
    In piu' chi e' il figlio, chi e' il dispositivo, la striscia del dispositivo e
    quella di ciascun dispositivo del figlio.

    (v3.4) proposte_pendenti = quelle del genitore a cui il figlio risponde;
    proposte_inviate = quelle del figlio che aspettano il genitore.

    (v3.5) Le sessioni di QUESTO telefono, quella in corso e quelle svolte negli 8
    giorni della striscia: al telefono servono per sapere quali periodi non contano,
    anche dopo un riavvio o una reinstallazione.

    (v3.6) Le faccende del figlio e il blocco, come GET /api/faccende e GET
    /api/faccende/blocco: uguali a quelli della finestra del genitore.

    (v3.8) Con `?tempi=1`: `uso_recente` e `medie` di questo dispositivo e di ciascun
    dispositivo del figlio, dalle stesse funzioni (tempi.py) e con le stesse regole
    della finestra: identici, campo per campo, a `dispositivi[].uso_recente` /
    `.medie` di GET /api/finestra. Senza (o con un valore diverso da "1", mai un 422)
    la risposta e' quella della v3.7 e le query in piu' non si fanno: il programma del
    computer legge il patto ogni minuto senza gzip, e i tempi lo farebbero passare da
    ~3 KB a ~90 KB."""
    con_tempi = tempi == "1"
    ora = clock.now()
    studio.valuta(conn, chi.figlio_id, ora)  # (v4.0) partenze e mezzanotti dello Studio
    figlio = famiglia.figlio_o_404(conn, chi.figlio_id)
    dispositivi = famiglia.dispositivi_del_figlio(conn, chi.figlio_id)
    per_id = {d["id"]: d for d in dispositivi}
    questo = per_id[chi.dispositivo_id]
    # (v3.1) Le regole si leggono PRIMA dei semafori, come nella finestra: le righe
    # non si cancellano mai (soft-delete), quindi ogni regola letta qui ha il suo
    # semaforo anche se intanto ne nasce una (dal computer, mentre il telefono
    # sincronizza). Nell'ordine inverso la regola nuova non avrebbe semaforo: 500.
    righe_regole = conn.execute(
        "SELECT * FROM regole WHERE attiva = 1 AND figlio_id = ?"
        " AND (dispositivo_id = ? OR dispositivo_id IS NULL) ORDER BY id",
        (chi.figlio_id, chi.dispositivo_id),
    ).fetchall()
    quadro = semaforo.quadro(conn, ora, chi.figlio_id, dispositivi)
    # (v3.8) I tempi di ciascun dispositivo del figlio, come nella finestra: i limiti
    # accanto ai tempi sono quelli delle limite_tempo ATTIVE di quel dispositivo (anche
    # di un revocato, come li vede il genitore), in ordine di id come la' — le righe di
    # `regole` qui sopra sono solo quelle di questo dispositivo, non bastano.
    tempi_per_id = {}
    if con_tempi:
        limiti_del_figlio = conn.execute(
            "SELECT * FROM regole WHERE figlio_id = ? AND attiva = 1 AND tipo = 'limite_tempo'"
            " ORDER BY id",
            (chi.figlio_id,),
        ).fetchall()
        giorni = siti.giorni_finestra(ora)
        tempi_per_id = {
            d["id"]: tempi_del_dispositivo(conn, ora, giorni, d["id"], limiti_del_figlio)
            for d in dispositivi
        }
    # (v2.4) Ogni regola porta il suo semaforo, lo stesso della finestra: il
    # genitore vede la striscia regola per regola, quindi la vede anche il figlio.
    regole = [
        {**_riga_regola(r, per_id), "semaforo": quadro["semafori"][r["id"]]}
        for r in righe_regole
    ]
    # (v3.5) Le etichette delle app delle sessioni: dalle fotografie dell'uso, lette una volta.
    note = etichette_note(conn, chi.figlio_id)
    firme = Firme(conn)  # (v3.6) chi tra i genitori ha deciso cosa
    risposta = {
        "regole": regole,
        "bonus": stato_bonus(conn, ora, chi.dispositivo_id),
        "bonus_oggi_per_regola": bonus_oggi_per_regola(conn, ora, chi.dispositivo_id),
        # conn: confronto ricalcolato vs la regola attuale (v2.1). (v3.4) Solo quelle a
        # cui il figlio deve rispondere, cioe' del genitore: come le vedevano le app 0.9.
        "proposte_pendenti": [
            formatta_proposta(r, conn, firme)
            for r in proposte_del_figlio(conn, chi.figlio_id, solo_pendenti=True, autore="genitore")
        ],
        # (v3.4) Le proposte del figlio che aspettano il genitore, di tutto il figlio.
        "proposte_inviate": [
            formatta_proposta(r, conn, firme)
            for r in proposte_del_figlio(conn, chi.figlio_id, solo_pendenti=True, autore="figlio")
        ],
        "dichiarazioni_in_attesa": [
            formatta_dichiarazione(r, firme)
            for r in dichiarazioni_del_figlio(conn, chi.figlio_id, solo_in_attesa=True)
        ],
        "siti_recenti": siti.siti_recenti(conn, ora, questo["id"], questo["tipo"]),
        "striscia": quadro["striscia"],
        "riepilogo": quadro["riepilogo"],
        "fuso": nome_fuso(),
        "figlio": {"id": figlio["id"], "nome": figlio["nome"]},
        "dispositivo": famiglia.riferimento(questo),
        "striscia_dispositivo": quadro["strisce_dispositivi"][questo["id"]],
        # Per la riga "sul computer 5 su 7": tutti i dispositivi del figlio, anche
        # i revocati (il genitore li vede, quindi li vede anche il figlio).
        "dispositivi": [
            {
                **famiglia.riferimento(d),
                "revocato": d["revocato_ts"] is not None,
                "striscia": quadro["strisce_dispositivi"][d["id"]],
            }
            for d in dispositivi
        ],
        # (v3.5) Solo di questo dispositivo: una sessione vale sul telefono che l'ha creata.
        "sessioni": sessioni_del_dispositivo(conn, questo, note),
        "sessione_in_corso": sessione_in_corso(conn, questo["id"], ora, note),
        "sessioni_svolte": sessioni_svolte_del_dispositivo(conn, questo["id"], ora, note),
        # (v3.6) Del figlio, non del dispositivo: il blocco vale su tutti i suoi.
        "faccende": faccende.faccende_del_figlio(conn, chi.figlio_id, ora, firme, cartella(request)),
        "blocco": faccende.blocco(conn, chi.figlio_id, ora, firme),
        # (v4.0) La Sessione Studio: la configurazione, quella in corso, le prossime
        # partenze (per partire da soli anche senza rete). Lo storico non c'e': sta in
        # GET /api/studio/svolte (il patto lo legge ogni minuto anche il computer).
        "studio": studio.vista_per_i_dispositivi(conn, chi.figlio_id, ora, note),
    }
    if con_tempi:
        # (v3.8) I tempi di questo dispositivo, identici a quelli della finestra, e
        # quelli di ciascun dispositivo del figlio (cosi' il figlio vede anche il computer).
        risposta["uso_recente"] = tempi_per_id[questo["id"]]["uso_recente"]
        risposta["medie"] = tempi_per_id[questo["id"]]["medie"]
        for voce in risposta["dispositivi"]:
            voce["uso_recente"] = tempi_per_id[voce["id"]]["uso_recente"]
            voce["medie"] = tempi_per_id[voce["id"]]["medie"]
    return risposta
