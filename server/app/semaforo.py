"""Il semaforo delle regole e la striscia del patto (contratto-api.md, GET /api/finestra).

Il semaforo per regola (8 giorni, verde/rosso/grigio) vive qui e non dentro la
finestra perche' la striscia aggregata (v2.4) serve anche a GET /api/patto: come
per siti.siti_recenti, le due app leggono la striscia dalla STESSA funzione, quindi
quella del figlio e quella del genitore non possono divergere (tavola rotonda).

(v3) Tutto e' per figlio: la striscia del figlio aggrega le regole di tutti i suoi
dispositivi piu' la vita reale; accanto c'e' la striscia di ciascun dispositivo,
fatta delle sole regole di quel dispositivo. Un unico `quadro` le calcola tutte
per GET /api/finestra, GET /api/patto e GET /api/famiglia.

I giorni sono gli stessi 8 della finestra (siti.giorni_finestra), nel fuso del patto.
"""

import json
import sqlite3
from collections import defaultdict
from datetime import datetime, timedelta, timezone

from . import clock, famiglia, siti
from .config import fuso_patto

# (v3.1) Il registro cresce ogni giorno: le letture per gli 8 giorni della finestra
# partono dall'inizio della finestra meno questo margine, non dall'inizio dei tempi.
# Il filtro e' su ts_server (l'arrivo al server). Uno sforamento consegnato in
# ritardo arriva DOPO il suo `giorno`: se il giorno cade nella finestra, l'arrivo
# pure. Il margine copre il caso opposto, un `giorno` del telefono piu' avanti
# dell'arrivo (telefono in un fuso piu' avanti del patto, orologio un po' avanti).
MARGINE_LETTURE_GIORNI = 2


def data_locale(ts_server: str, tz) -> str:
    """Il giorno LOCALE (fuso del patto) di un ts_server UTC ISO."""
    return datetime.fromisoformat(ts_server).astimezone(tz).date().isoformat()


def inizio_letture(ora: datetime) -> str:
    """(v3.1) Il ts_server (UTC ISO, confrontabile come stringa con quelli salvati)
    da cui leggere il registro per la finestra: la mezzanotte locale del primo degli
    8 giorni, meno MARGINE_LETTURE_GIORNI."""
    primo = siti.giorni_finestra(ora)[0]
    mezzanotte = datetime(primo.year, primo.month, primo.day, tzinfo=fuso_patto())
    inizio = mezzanotte.astimezone(timezone.utc) - timedelta(days=MARGINE_LETTURE_GIORNI)
    return clock.iso(inizio)


def _semaforo_vita(stato_dich: str | None) -> str:
    """(v2.1) Colore di una regola vita_reale in un giorno, dalla dichiarazione:
    verde = confermata (anche per conto), rosso = fallimento dichiarato o successo
    ribaltato, grigio = nessuna dichiarazione o verdetto ancora in attesa. Il rosso
    di un fallimento dichiarato fotografa il fatto, non punisce l'onesta'."""
    if stato_dich in ("confermata", "confermata_per_conto"):
        return "verde"
    if stato_dich in ("registrata", "ribaltata"):
        return "rosso"
    return "grigio"  # in_attesa o nessuna dichiarazione


def _semafori(
    conn: sqlite3.Connection, ora: datetime, figlio_id: int, dispositivi: list[sqlite3.Row]
) -> tuple[dict, dict]:
    """Il semaforo di OGNI regola del figlio, eliminate comprese: {regola_id: 8 voci
    {"data", "stato"}}, dal piu' vecchio a oggi; e {regola_id: dispositivo_id}.

    Tre colori: verde/rosso/grigio. Il giallo non esiste piu': il bonus
    autoritativo vive nella tabella bonus e si legge in bonus_giornalieri, non
    appeso a una regola. Per limite_tempo/fascia_oraria il rosso viene dagli
    sforamenti; per le vita_reale (v2.1) dalle dichiarazioni e dai verdetti.

    (v3) Una regola di un dispositivo guarda solo il registro di QUEL dispositivo:
    i suoi sforamenti e le sue fotografie. Un evento con il regola_id di un altro
    dispositivo (o di un altro figlio) non tinge niente."""
    tz = fuso_patto()
    date_iso = [g.isoformat() for g in siti.giorni_finestra(ora)]
    revoche = {
        d["id"]: data_locale(d["revocato_ts"], tz) for d in dispositivi if d["revocato_ts"]
    }

    sforamenti = defaultdict(set)  # (dispositivo_id, regola_id) -> {data ISO locale}
    for evento in conn.execute(
        "SELECT e.dettagli, e.ts_server, e.dispositivo_id FROM eventi e"
        " JOIN dispositivi d ON d.id = e.dispositivo_id"
        " WHERE e.tipo = 'sforamento' AND d.figlio_id = ? AND e.ts_server >= ?",
        (figlio_id, inizio_letture(ora)),
    ).fetchall():
        dettagli = json.loads(evento["dettagli"])
        regola_id = dettagli.get("regola_id")
        if regola_id is None:
            continue
        # (v2.4) Lo sforamento cade nel giorno in cui e' successo (dettagli.giorno,
        # giorno locale del telefono), non in quello in cui e' arrivato: un telefono
        # offline fino al giorno dopo non deve lasciare verde il giorno sforato.
        # Senza un giorno valido si ripiega sull'arrivo, come prima.
        giorno = dettagli.get("giorno")
        if not clock.giorno_valido(giorno):
            giorno = data_locale(evento["ts_server"], tz)
        try:
            sforamenti[(evento["dispositivo_id"], regola_id)].add(giorno)
        except TypeError:
            continue  # un regola_id non confrontabile (lista, oggetto) non e' di nessuna regola

    # Dichiarazioni per le regole vita_reale: (regola_id, giorno locale) -> stato.
    # Max una per regola per giorno, quindi la mappa e' univoca. (v3.1) Solo quelle
    # dei giorni della finestra: `giorno` e' gia' il giorno locale, niente margine.
    dich_per_regola = defaultdict(dict)  # regola_id -> {giorno ISO: stato dichiarazione}
    for d in conn.execute(
        "SELECT d.regola_id, d.giorno, d.stato FROM dichiarazioni d"
        " JOIN regole r ON r.id = d.regola_id WHERE r.figlio_id = ? AND d.giorno >= ?",
        (figlio_id, date_iso[0]),
    ).fetchall():
        dich_per_regola[d["regola_id"]][d["giorno"]] = d["stato"]

    # (v2.4) I giorni di cui il dispositivo ha raccontato qualcosa: quelli con una
    # fotografia uso_giornaliero vigente. `giorno` e' gia' il giorno locale.
    segnaposto = ",".join("?" * len(date_iso))
    giorni_con_dati = {
        (r["dispositivo_id"], r["giorno"])
        for r in conn.execute(
            "SELECT u.dispositivo_id, u.giorno FROM uso_giornaliero u"
            " JOIN dispositivi d ON d.id = u.dispositivo_id"
            f" WHERE d.figlio_id = ? AND u.giorno IN ({segnaposto})",
            [figlio_id, *date_iso],
        ).fetchall()
    }

    per_regola = {}
    dispositivo_della_regola = {}
    for riga in conn.execute(
        "SELECT id, tipo, attiva, creata_ts, ultima_modifica_ts, dispositivo_id FROM regole"
        " WHERE figlio_id = ? ORDER BY id",
        (figlio_id,),
    ).fetchall():
        dispositivo_id = riga["dispositivo_id"]
        creata = data_locale(riga["creata_ts"], tz)
        fine = None
        if not riga["attiva"]:
            # Soft-delete: i giorni STRETTAMENTE successivi all'eliminazione
            # sono fuori dalla vita della regola -> grigio, non verde.
            fine = data_locale(riga["ultima_modifica_ts"], tz)
        revoca = revoche.get(dispositivo_id)
        if revoca is not None and (fine is None or revoca < fine):
            # (v3) Il dispositivo revocato: le sue regole restano nella storia ma
            # dal giorno dopo la revoca non contano piu', come se eliminate.
            fine = revoca
        voci = []
        for data in date_iso:
            if data < creata or (fine is not None and data > fine):
                stato = "grigio"
            elif riga["tipo"] == "vita_reale":
                stato = _semaforo_vita(dich_per_regola[riga["id"]].get(data))
            elif data in sforamenti[(dispositivo_id, riga["id"])]:
                stato = "rosso"  # lo sforamento e' gia' un dato: rosso anche senza fotografia
            elif (dispositivo_id, data) in giorni_con_dati:
                stato = "verde"
            else:
                # (v2.4) Niente verde senza dati: un giorno di cui non si sa nulla
                # non puo' figurare come mantenuto.
                stato = "grigio"
            voci.append({"data": data, "stato": stato})
        per_regola[riga["id"]] = voci
        dispositivo_della_regola[riga["id"]] = dispositivo_id
    return per_regola, dispositivo_della_regola


def _aggrega(semafori: list, ora: datetime) -> list:
    """(v2.4) L'aggregazione della striscia: 8 voci {"data", "stato"}, gli stessi
    giorni del semaforo, dal piu' vecchio a oggi. Rosso se almeno una regola e'
    rossa, altrimenti verde se almeno una e' verde, altrimenti grigio (nessuna
    regola in vita o nessun dato). Le regole eliminate contano solo nei giorni in
    cui erano in vita: fuori sono gia' grigie."""
    stati_per_giorno = defaultdict(set)
    for voci in semafori:
        for voce in voci:
            stati_per_giorno[voce["data"]].add(voce["stato"])

    risultato = []
    for giorno in siti.giorni_finestra(ora):
        data = giorno.isoformat()
        stati = stati_per_giorno[data]
        if "rosso" in stati:
            stato = "rosso"
        elif "verde" in stati:
            stato = "verde"
        else:
            stato = "grigio"
        risultato.append({"data": data, "stato": stato})
    return risultato


def _interruzioni(conn: sqlite3.Connection, ora: datetime, figlio_id: int) -> int:
    """Gli eventi `manomissione` di qualsiasi dispositivo del figlio il cui
    ts_server, nel fuso del patto, cade negli 8 giorni della striscia. Si contano
    qui, e non nelle app, perche' le app ricevono al massimo gli ultimi 20 eventi
    e il giorno va preso nel fuso del patto, non in quello di chi legge."""
    tz = fuso_patto()
    giorni = {g.isoformat() for g in siti.giorni_finestra(ora)}
    return sum(
        1
        for e in conn.execute(
            "SELECT e.ts_server FROM eventi e JOIN dispositivi d ON d.id = e.dispositivo_id"
            " WHERE e.tipo = 'manomissione' AND d.figlio_id = ? AND e.ts_server >= ?",
            (figlio_id, inizio_letture(ora)),
        )
        if data_locale(e["ts_server"], tz) in giorni
    )


def quadro(
    conn: sqlite3.Connection,
    ora: datetime,
    figlio_id: int,
    dispositivi: list[sqlite3.Row] | None = None,
) -> dict:
    """Tutto quello che il semaforo dice di un figlio, in un colpo solo:

    - `semafori`: {regola_id: 8 voci} per ogni regola del figlio, eliminate comprese;
    - `striscia`: la striscia del FIGLIO (v2.4, ora su tutti i dispositivi + vita reale);
    - `strisce_dispositivi`: {dispositivo_id: striscia delle sole regole di quel dispositivo};
    - `riepilogo`: {"giorni_fuori_regola", "interruzioni"}, la riga sotto la striscia.

    Unica fonte di striscia e riepilogo per GET /api/finestra, GET /api/patto e
    GET /api/famiglia: le app mostrano gli stessi fatti per costruzione."""
    if dispositivi is None:
        dispositivi = famiglia.dispositivi_del_figlio(conn, figlio_id)
    semafori, dispositivo_della_regola = _semafori(conn, ora, figlio_id, dispositivi)
    striscia = _aggrega(list(semafori.values()), ora)
    strisce_dispositivi = {
        d["id"]: _aggrega(
            [voci for regola_id, voci in semafori.items()
             if dispositivo_della_regola[regola_id] == d["id"]],
            ora,
        )
        for d in dispositivi
    }
    return {
        "semafori": semafori,
        "striscia": striscia,
        "strisce_dispositivi": strisce_dispositivi,
        "riepilogo": {
            "giorni_fuori_regola": sum(1 for voce in striscia if voce["stato"] == "rosso"),
            "interruzioni": _interruzioni(conn, ora, figlio_id),
        },
    }
