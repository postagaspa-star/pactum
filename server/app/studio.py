"""(v4.0) La Sessione Studio (contratto-api.md, "v4.0 — C. La Sessione Studio") e la rete
di sicurezza del computer che sparisce (parte B).

Lo Studio e' del figlio (non di un dispositivo). Parte da solo nei giorni e all'ora della
configurazione approvata, anche senza rete: le partenze le decide il server e le manda
ai dispositivi (`prossime_partenze`). Durante lo Studio telefono e computer lasciano
usare solo le app, i programmi e i siti della lista approvata. Si chiude dal telefono
dopo `chiudibile_dal` e dopo `minuti_minimi` di attivita' col timer (i tratti), con una
dichiarazione; un genitore lo chiude quando vuole, con un motivo; a mezzanotte si chiude
da solo come `non_chiuso`.

Qui vivono:

- l'ora: le partenze del giorno D sono le ore `inizio` di D nel fuso del patto (un'ora
  che non esiste per il cambio dell'ora vale la prima ora valida dopo; una che esiste due
  volte, la prima). Gli orari valgono dal giorno dopo l'approvazione (`orari_dal`), le
  liste dalla partenza successiva (lo Studio congela le sue alla nascita);
- `valuta`: nessun processo in sottofondo. A ogni richiesta che riguarda il figlio il
  server guarda IN SOLA LETTURA se c'e' una partenza passata da elaborare (dopo la prima
  approvazione, non piu' vecchia di 48 ore), uno Studio aperto oltre la sua mezzanotte o
  un computer sparito; solo in quel caso apre BEGIN IMMEDIATE, ricontrolla e scrive
  (`elabora`), in ordine di tempo. Ogni partenza si elabora una volta sola
  (studio_partenze);
- lo Studio come lo vedono le app (`formatta`), con le condizioni di chiusura calcolate
  dall'ultima partenza dentro lo Studio, e i minuti di attivita': la durata dell'UNIONE
  degli intervalli dei tratti che contano, tagliata a [conta_dal, fine dello Studio o
  adesso];
- la configurazione (proposta dal figlio, approvata da un genitore), l'avvio a mano, i
  tratti, le chiusure (del figlio, del genitore, di mezzanotte) e le notifiche;
- il computer che sparisce durante un blocco o uno Studio: una `manomissione`
  `computer_sparito` scritta dal server stesso (parte B).

Le scritture stanno tutte dentro BEGIN IMMEDIATE (lo apre chi chiama, o `valuta`)."""

import json
import sqlite3
from datetime import date, datetime, time, timedelta, timezone

from fastapi import HTTPException

from . import clock, config, faccende, famiglia
from .config import SOGLIA_SILENZIO_MINUTI
from .controllo_corpo import INTERO_MASSIMO, INTERO_MINIMO
from .db import CHIAVE_ULTIMO_GIRO, CONFIG_STUDIO_INIZIALE, accoda_notifica, domani_nel_patto
from .genitori import Firme

NON_TROVATO = "studio non trovato"
GIORNI = ("lun", "mar", "mer", "gio", "ven", "sab", "dom")
ORE_RECUPERO = 48  # le partenze piu' vecchie di 48 ore non creano Studi
GIORNI_PROSSIME = 14  # prossime_partenze: oggi e i 14 giorni dopo
ULTIMO_MINUTO_CHIUSURA = 23 * 60 + 30  # uno Studio si deve poter chiudere entro le 23:30
SVOLTE_PER_PAGINA = 20
SVOLTE_FINESTRA = 50
LUNGHEZZA_DICHIARAZIONE_AVVISO = 200

# Gli eseguibili dei browser: in lista aprirebbero tutti i siti (nei browser si elencano
# i siti). L'elenco e' del server e si puo' allargare.
BROWSER = frozenset({
    "chrome.exe", "msedge.exe", "firefox.exe", "brave.exe", "opera.exe", "opera_gx.exe",
    "vivaldi.exe", "arc.exe", "chromium.exe", "iexplore.exe", "waterfox.exe", "librewolf.exe",
    "tor.exe", "yandex.exe", "browser.exe", "seamonkey.exe", "palemoon.exe", "floorp.exe",
    "thorium.exe", "zen.exe", "maxthon.exe", "duckduckgo.exe",
})

SOTTO_TIPO_SPARITO = "computer_sparito"


# --- l'ora, nel fuso del patto ---

def _zona():
    return config.fuso_patto()


def istante_locale(giorno: date, orario: str) -> datetime:
    """L'istante (UTC) in cui nel giorno `giorno`, nel fuso del patto, sono le `orario`
    ("HH:MM"). Se quell'ora non esiste (il cambio dell'ora in primavera) vale la prima ora
    valida dopo; se esiste due volte (in autunno), la prima."""
    ore, minuti = (int(x) for x in orario.split(":"))
    locale = datetime.combine(giorno, time(ore, minuti))
    zona = _zona()
    for _ in range(24 * 60):
        utc = locale.replace(tzinfo=zona).astimezone(timezone.utc)  # fold=0: la prima
        if utc.astimezone(zona).replace(tzinfo=None) == locale:
            return utc
        locale += timedelta(minutes=1)
    raise ValueError(f"nessuna ora valida dopo {giorno} {orario}")  # mai, nei fusi veri


def giorno_locale(istante: datetime) -> date:
    return istante.astimezone(_zona()).date()


def mezzanotte_dopo(giorno: date) -> datetime:
    """La mezzanotte (fuso del patto) che chiude il giorno `giorno`."""
    return istante_locale(giorno + timedelta(days=1), "00:00")


def ora_e_minuti(istante: datetime | str) -> str:
    """"16:40": l'ora di un istante nel fuso del patto, per i messaggi."""
    if isinstance(istante, str):
        istante = datetime.fromisoformat(istante)
    return istante.astimezone(_zona()).strftime("%H:%M")


def ms(istante: datetime) -> int:
    return int(round(istante.timestamp() * 1000))


def da_ms(valore: int) -> datetime:
    return datetime.fromtimestamp(valore / 1000, tz=timezone.utc)


def _minuti_del_giorno(orario: str) -> int:
    ore, minuti = (int(x) for x in orario.split(":"))
    return ore * 60 + minuti


def _dt(ts: str | None) -> datetime | None:
    return datetime.fromisoformat(ts) if ts is not None else None


# --- le versioni approvate e le partenze ---

def versioni(conn: sqlite3.Connection, figlio_id: int) -> list[dict]:
    """Le configurazioni approvate del figlio, dalla prima."""
    return [
        {
            "versione": r["versione"],
            "contenuto": json.loads(r["contenuto"]),
            "orari_dal": r["orari_dal"],
            "approvata_ts": r["approvata_ts"],
            "genitore_id": r["genitore_id"],
        }
        for r in conn.execute(
            "SELECT * FROM studio_versioni WHERE figlio_id = ? ORDER BY versione", (figlio_id,)
        ).fetchall()
    ]


def _orari_del_giorno(elenco: list[dict], giorno: date) -> dict | None:
    """Gli orari in vigore il giorno `giorno`: quelli dell'ultima approvazione con
    orari_dal <= giorno (un cambio delle sole liste tiene l'orari_dal di prima)."""
    valida = None
    for versione in elenco:
        if versione["orari_dal"] <= giorno.isoformat():
            valida = versione
    return valida["contenuto"] if valida is not None else None


def _versione_a(elenco: list[dict], istante: datetime) -> dict | None:
    """L'ultima versione approvata prima di `istante` (o in quell'istante): le sue liste
    sono quelle con cui nasce uno Studio che parte a `istante`."""
    valida = None
    for versione in elenco:
        if versione["approvata_ts"] <= clock.iso(istante):
            valida = versione
    return valida


def partenza_del_giorno(elenco: list[dict], giorno: date) -> dict | None:
    """La partenza automatica del giorno: l'istante in cui sono le ore `inizio`, se il
    giorno e' tra i `giorni` degli orari in vigore quel giorno. Porta con se'
    `chiudibile_dal` (il giorno alle `chiusura_minima`) e `minuti_minimi`."""
    orari = _orari_del_giorno(elenco, giorno)
    if orari is None or GIORNI[giorno.weekday()] not in orari["giorni"]:
        return None
    return {
        "giorno": giorno.isoformat(),
        "inizio_ts": clock.iso(istante_locale(giorno, orari["inizio"])),
        "chiudibile_dal": clock.iso(istante_locale(giorno, orari["chiusura_minima"])),
        "minuti_minimi": orari["minuti_minimi"],
    }


def prossime_partenze(conn: sqlite3.Connection, figlio_id: int, ora: datetime) -> list[dict]:
    """Le partenze da oggi (compresa quella di oggi, anche se gia' passata) ai prossimi 14
    giorni, gia' calcolate col fuso, col cambio dell'ora e con orari_dal. [] se il figlio
    non ha un telefono non revocato dalla 0.18: senza, il server non creera' mai lo Studio,
    e nessun dispositivo deve partire da solo."""
    if not faccende.ha_telefono_con_lo_studio(conn, figlio_id):
        return []
    elenco = versioni(conn, figlio_id)
    if not elenco:
        return []
    oggi = giorno_locale(ora)
    trovate = (partenza_del_giorno(elenco, oggi + timedelta(days=k)) for k in range(GIORNI_PROSSIME + 1))
    return [p for p in trovate if p is not None]


def _partenze_da_elaborare(conn: sqlite3.Connection, figlio_id: int, ora: datetime, elenco: list[dict]) -> list[dict]:
    """Le partenze passate non ancora elaborate (non in studio_partenze), non piu' vecchie
    di 48 ore, dalla piu' vecchia."""
    if not elenco:
        return []
    oggi = giorno_locale(ora)
    limite = ora - timedelta(hours=ORE_RECUPERO)
    fatte = {
        r["giorno"]
        for r in conn.execute(
            "SELECT giorno FROM studio_partenze WHERE figlio_id = ? AND giorno >= ?",
            (figlio_id, (oggi - timedelta(days=4)).isoformat()),
        ).fetchall()
    }
    trovate = []
    for indietro in range(3, -1, -1):
        partenza = partenza_del_giorno(elenco, oggi - timedelta(days=indietro))
        if partenza is None or partenza["giorno"] in fatte:
            continue
        inizio = datetime.fromisoformat(partenza["inizio_ts"])
        if limite <= inizio <= ora:
            trovate.append(partenza)
    return sorted(trovate, key=lambda p: p["inizio_ts"])


def segna_partenze_senza_studio(conn: sqlite3.Connection, dal: datetime, al: datetime) -> None:
    """(v4.0, ritorno dalla v3.9) Le partenze cadute tra `dal` e `al` (mentre girava un
    server che lo Studio non lo conosce) non creano Studi: si segnano come trattate."""
    ts = clock.iso(al)
    for (figlio_id,) in conn.execute("SELECT id FROM figli").fetchall():
        elenco = versioni(conn, figlio_id)
        if not elenco:
            continue
        giorno = giorno_locale(max(dal, al - timedelta(hours=ORE_RECUPERO + 24)))
        while giorno <= giorno_locale(al):
            partenza = partenza_del_giorno(elenco, giorno)
            if partenza is not None and dal < datetime.fromisoformat(partenza["inizio_ts"]) <= al:
                conn.execute(
                    "INSERT OR IGNORE INTO studio_partenze (figlio_id, giorno, studio_id, esito,"
                    " inizio_ts, chiudibile_dal, minuti_minimi, ts_server)"
                    " VALUES (?, ?, NULL, 'senza_studio', ?, ?, ?, ?)",
                    (figlio_id, partenza["giorno"], partenza["inizio_ts"], partenza["chiudibile_dal"],
                     partenza["minuti_minimi"], ts),
                )
            giorno += timedelta(days=1)


def chiudi_al_ritorno_dalla_v39(conn: sqlite3.Connection, ultimo_giro: datetime) -> None:
    """(Correzione, ritorno dalla v3.9) Uno Studio rimasto aperto mentre girava un server
    senza Studio: la v3.9 manda un patto senza `studio`, il telefono esce dallo Studio e
    nessuno lo puo' chiudere. Si chiude `non_chiuso` all'ultimo giro della v4.0 (o alla
    sua mezzanotte, se viene prima), coi minuti fatti fino a li', SENZA l'avviso
    studio_non_chiuso: non e' una colpa del figlio. Senza, alla prima richiesta si chiudeva
    a mezzanotte con un «non chiuso» falso ai genitori, o tornava in corso (telefono e
    computer di nuovo in Studio, il blocco ancora rimandato). Una chiusura del figlio
    valida prima di quell'ora, consegnata dopo, lo fa ancora cedere."""
    for aperto in conn.execute("SELECT * FROM studio_svolte WHERE fine_ts IS NULL").fetchall():
        inizio = datetime.fromisoformat(aperto["inizio_ts"])
        fine = max(inizio, min(ultimo_giro, _mezzanotte(aperto))).replace(microsecond=0)
        conn.execute(
            "UPDATE studio_svolte SET fine_ts = ?, chiusura = 'non_chiuso', minuti_alla_chiusura = ?"
            " WHERE id = ? AND fine_ts IS NULL",
            (clock.iso(fine), minuti_di_attivita(conn, aperto, fine), aperto["id"]),
        )


# --- gli Studi ---

def studio_o_404(conn: sqlite3.Connection, studio_id: int, figlio_id: int | None = None) -> sqlite3.Row:
    if not INTERO_MINIMO <= studio_id <= INTERO_MASSIMO:
        raise HTTPException(status_code=404, detail=NON_TROVATO)
    riga = conn.execute("SELECT * FROM studio_svolte WHERE id = ?", (studio_id,)).fetchone()
    if riga is None or (figlio_id is not None and riga["figlio_id"] != figlio_id):
        raise HTTPException(status_code=404, detail=NON_TROVATO)
    return riga


def _riga(conn: sqlite3.Connection, studio_id: int) -> sqlite3.Row:
    return conn.execute("SELECT * FROM studio_svolte WHERE id = ?", (studio_id,)).fetchone()


def _partenze_dello_studio(conn: sqlite3.Connection, studio_id: int) -> list[sqlite3.Row]:
    return conn.execute(
        "SELECT * FROM studio_partenze WHERE studio_id = ? AND esito IN ('nato', 'entrata')"
        " ORDER BY inizio_ts",
        (studio_id,),
    ).fetchall()


def _mezzanotte(studio: sqlite3.Row) -> datetime:
    return mezzanotte_dopo(date.fromisoformat(studio["giorno"]))


def condizioni(studio: sqlite3.Row, partenze: list, al: datetime) -> tuple[datetime, datetime | None, int]:
    """Le condizioni di chiusura a `al`, dall'ultima partenza P dentro lo Studio fino a
    `al`: (conta_dal, chiudibile_dal, minuti_minimi). Senza partenze (avvio a mano):
    l'inizio, nessun vincolo d'orario, i minuti approvati all'avvio."""
    dentro = [p for p in partenze if p["inizio_ts"] <= clock.iso(al)]
    if dentro:
        ultima = dentro[-1]
        return (datetime.fromisoformat(ultima["inizio_ts"]),
                datetime.fromisoformat(ultima["chiudibile_dal"]), ultima["minuti_minimi"])
    return datetime.fromisoformat(studio["inizio_ts"]), None, studio["minuti_minimi_avvio"]


def _intervallo(tratto) -> tuple[int, int] | None:
    """[inizio, fine] in ms di un tratto finito (dalla fine e dai secondi)."""
    if tratto["fine"] is None:
        return None
    return tratto["fine"] - tratto["secondi"] * 1000, tratto["fine"]


def _taglia(intervallo: tuple[int, int] | None, da: int, a: int) -> tuple[int, int] | None:
    if intervallo is None:
        return None
    inizio, fine = max(intervallo[0], da), min(intervallo[1], a)
    return (inizio, fine) if fine > inizio else None


def _lunghezza_unione(intervalli: list[tuple[int, int]]) -> int:
    totale, fine_corrente, inizio_corrente = 0, None, None
    for inizio, fine in sorted(intervalli):
        if fine_corrente is None or inizio > fine_corrente:
            if fine_corrente is not None:
                totale += fine_corrente - inizio_corrente
            inizio_corrente, fine_corrente = inizio, fine
        else:
            fine_corrente = max(fine_corrente, fine)
    if fine_corrente is not None:
        totale += fine_corrente - inizio_corrente
    return totale


def _senza_unione(uno, altro) -> bool:
    """Due tratti la cui parte comune NON si unisce: dello stesso telefono, uno con l'ora
    agganciata e l'altro no (contratto: «non calcola la sovrapposizione fra tratti con
    ora_agganciata diversa sulla stessa accensione»: dopo un riavvio senza rete l'orologio
    del telefono puo' essere indietro e due tratti sembrerebbero sovrapposti)."""
    return (uno["dispositivo_id"] == altro["dispositivo_id"]
            and bool(uno["ora_agganciata"]) != bool(altro["ora_agganciata"]))


def secondi_di_attivita(tratti: list, da: int, a: int) -> int:
    """I secondi di attivita' dei tratti che contano tra `da` e `a` (ms): l'unione degli
    intervalli (la parte comune di due tratti si conta una volta sola), con una sola
    eccezione: fra un tratto con l'ora agganciata e uno senza DELLO STESSO telefono la
    parte comune si conta due volte (il figlio non perde il suo tempo per colpa
    dell'orologio). (Correzione) Fra due telefoni diversi, e fra due tratti senza aggancio
    dello stesso telefono, vale l'unione: prima i tratti senza aggancio si sommavano
    sempre, e due telefoni riavviati senza rete col timer acceso insieme facevano 60
    minuti per 30 veri. Il totale non supera mai [da, a].

    Si conta a pezzi: fra due estremi consecutivi un pezzo vale due volte solo se lo
    coprono esattamente i due tipi (agganciato e no) di un telefono solo; altrimenti una."""
    if a <= da:
        return 0
    pezzi = []
    for tratto in tratti:
        tagliato = _taglia(_intervallo(tratto), da, a)
        if tagliato is not None:
            pezzi.append((tagliato[0], tagliato[1], tratto["dispositivo_id"], bool(tratto["ora_agganciata"])))
    punti = sorted({p for pezzo in pezzi for p in pezzo[:2]})
    totale = 0
    for inizio, fine in zip(punti, punti[1:]):
        sopra = {(d, g) for i, f, d, g in pezzi if i <= inizio and f >= fine}
        if not sopra:
            continue
        doppio = len(sopra) == 2 and len({d for d, _ in sopra}) == 1
        totale += (fine - inizio) * (2 if doppio else 1)
    return min(totale, a - da) // 1000


def _tratti_dello_studio(conn: sqlite3.Connection, studio_id: int) -> list[sqlite3.Row]:
    return conn.execute(
        "SELECT * FROM studio_tratti WHERE studio_id = ? ORDER BY COALESCE(fine - secondi * 1000, inizio), rowid",
        (studio_id,),
    ).fetchall()


def minuti_di_attivita(conn: sqlite3.Connection, studio: sqlite3.Row, al: datetime) -> int:
    """I minuti di attivita' dello Studio fino a `al` (o alla sua fine, se viene prima),
    per difetto."""
    partenze = _partenze_dello_studio(conn, studio["id"])
    conta_dal, _, _ = condizioni(studio, partenze, al)
    fine = min(al, _dt(studio["fine_ts"])) if studio["fine_ts"] is not None else al
    contano = [t for t in _tratti_dello_studio(conn, studio["id"]) if t["conta"]]
    return secondi_di_attivita(contano, ms(conta_dal), ms(fine)) // 60


def _etichette(chiavi: list[str], mandate: dict, note: dict) -> dict:
    """Le etichette che si leggono: per una chiave vista nell'uso quella delle fotografie
    (anche per exe:), per sito: il dominio, per le altre quella mandata."""
    etichette = {}
    for chiave in chiavi:
        if chiave.startswith("sito:"):
            etichette[chiave] = chiave[len("sito:"):]
            continue
        nome = note.get(chiave) or mandate.get(chiave)
        if nome:
            etichette[chiave] = nome
    return etichette


def _liste_out(liste: dict, note: dict) -> dict:
    telefono, computer = liste["telefono"], liste["computer"]
    return {
        "telefono": {"app": telefono["app"], "nomi": _etichette(telefono["app"], telefono["nomi"], note)},
        "computer": {
            "programmi": computer["programmi"],
            "nomi": _etichette(computer["programmi"], computer["nomi"], note),
            "firme": computer.get("firme", {}),
        },
    }


def _tratto_out(tratto: sqlite3.Row, conta_dal: int | None, fine: int | None) -> dict:
    intervallo = _intervallo(tratto)
    contati = 0
    if intervallo is not None and conta_dal is not None and fine is not None:
        tagliato = _taglia(intervallo, conta_dal, fine)
        contati = (tagliato[1] - tagliato[0]) // 1000 if tagliato else 0
    return {
        "id": tratto["id"],
        "dispositivo_id": tratto["dispositivo_id"],
        "tipo": tratto["tipo"],
        "parola": tratto["parola"],
        "faccenda_id": tratto["faccenda_id"],
        "inizio": intervallo[0] if intervallo is not None else tratto["inizio"],
        "fine": tratto["fine"],
        "ora_agganciata": bool(tratto["ora_agganciata"]),
        "secondi": tratto["secondi"],
        "secondi_contati": contati,
        "minuti": tratto["secondi"] // 60,
        "esito": tratto["esito"],
        "conta": bool(tratto["conta"]),
    }


def formatta(
    conn: sqlite3.Connection,
    studio: sqlite3.Row,
    ora: datetime,
    firme: Firme | None = None,
    note: dict | None = None,
) -> dict:
    """Lo Studio come lo vedono le app: le condizioni di chiusura a adesso (o alla
    chiusura), i minuti di attivita', i tratti (coi secondi che contano), le liste
    congelate, chi l'ha avviato e chi l'ha chiuso."""
    firme = firme or Firme(conn)
    note = note if note is not None else _note(conn, studio["figlio_id"])
    partenze = _partenze_dello_studio(conn, studio["id"])
    fine = _dt(studio["fine_ts"]) or ora
    conta_dal, chiudibile_dal, minimi = condizioni(studio, partenze, fine)
    tratti = _tratti_dello_studio(conn, studio["id"])
    contano = [t for t in tratti if t["conta"]]
    in_corso = studio["fine_ts"] is None
    if in_corso or studio["minuti_alla_chiusura"] is None:
        minuti = secondi_di_attivita(contano, ms(conta_dal), ms(fine)) // 60
    else:
        # (Correzione) Uno Studio chiuso mostra i minuti congelati alla chiusura
        # (contratto: «i tratti che arrivano dopo la chiusura ... non cambiano questo
        # numero»), cosi' vista, avviso e storico coincidono anche quando un tratto arriva
        # dopo la chiusura del genitore. I tratti restano nel registro coi loro secondi.
        minuti = studio["minuti_alla_chiusura"]
    chiudibile = in_corso and (chiudibile_dal is None or ora >= chiudibile_dal) and minuti >= minimi
    dispositivi = {
        d["id"]: d for d in famiglia.dispositivi_del_figlio(conn, studio["figlio_id"])
    }
    avviato = dispositivi.get(studio["avviato_dispositivo_id"])
    if studio["chiusa_dispositivo_id"] is not None and studio["chiusa_dispositivo_id"] in dispositivi:
        chiusa_da = famiglia.riferimento(dispositivi[studio["chiusa_dispositivo_id"]])
    else:
        chiusa_da = firme.di(studio["chiusa_genitore_id"])
    sessione = None
    if studio["sessione_chiusa_id"] is not None:
        riga = conn.execute(
            "SELECT id, nome FROM sessioni_svolte WHERE id = ?", (studio["sessione_chiusa_id"],)
        ).fetchone()
        sessione = {"id": riga["id"], "nome": riga["nome"]} if riga is not None else None
    return {
        "id": studio["id"],
        "origine": studio["origine"],
        "giorno": studio["giorno"],
        "chiave": studio["chiave"],
        "inizio_ts": studio["inizio_ts"],
        "avviato_da": famiglia.riferimento(avviato) if avviato is not None else None,
        "partenze": [
            {"giorno": p["giorno"], "inizio_ts": p["inizio_ts"], "chiudibile_dal": p["chiudibile_dal"],
             "minuti_minimi": p["minuti_minimi"]}
            for p in partenze
        ],
        "conta_dal": clock.iso(conta_dal),
        "chiudibile_dal": clock.iso(chiudibile_dal) if chiudibile_dal is not None else None,
        "minuti_minimi": minimi,
        "minuti_attivita": minuti,
        "minuti_alla_chiusura": studio["minuti_alla_chiusura"],
        "chiudibile": chiudibile,
        "tratti": [_tratto_out(t, ms(conta_dal), ms(fine)) for t in tratti],
        "liste": _liste_out(json.loads(studio["liste"]), note),
        "sessione_chiusa": sessione,
        "fine_ts": studio["fine_ts"],
        "chiusura": studio["chiusura"],
        "chiusa_da": chiusa_da,
        "dichiarazione": studio["dichiarazione"],
        "dichiarazione_ts": studio["dichiarazione_ts"],
        "motivo": studio["motivo"],
        "in_corso": in_corso,
    }


def _note(conn: sqlite3.Connection, figlio_id: int) -> dict:
    """Le etichette delle app e dei programmi viste nell'uso del figlio."""
    return famiglia.nomi_recenti(conn, figlio_id, giorno_locale(clock.now()))


def recenti(conn: sqlite3.Connection, figlio_id: int, ora: datetime, ore: int = ORE_RECUPERO,
            massimo: int | None = None, note: dict | None = None) -> list[dict]:
    """Gli Studi che toccano le ultime `ore` ore (aperti, o finiti dopo), dal piu' recente."""
    dal = clock.iso(ora - timedelta(hours=ore))
    righe = conn.execute(
        "SELECT * FROM studio_svolte WHERE figlio_id = ? AND (fine_ts IS NULL OR fine_ts >= ?)"
        " ORDER BY inizio_ts DESC, id DESC" + (f" LIMIT {int(massimo)}" if massimo else ""),
        (figlio_id, dal),
    ).fetchall()
    firme = Firme(conn)
    note = note if note is not None else _note(conn, figlio_id)
    return [formatta(conn, r, ora, firme, note) for r in righe]


def svolte_della_finestra(conn: sqlite3.Connection, figlio_id: int, ora: datetime, inizio: datetime,
                          note: dict | None = None) -> list[dict]:
    """GET /api/finestra: gli Studi che toccano gli 8 giorni, dal piu' recente, al massimo 50."""
    righe = conn.execute(
        "SELECT * FROM studio_svolte WHERE figlio_id = ? AND (fine_ts IS NULL OR fine_ts >= ?)"
        " ORDER BY inizio_ts DESC, id DESC LIMIT ?",
        (figlio_id, clock.iso(inizio), SVOLTE_FINESTRA),
    ).fetchall()
    firme = Firme(conn)
    note = note if note is not None else _note(conn, figlio_id)
    return [formatta(conn, r, ora, firme, note) for r in righe]


def pagina_di_svolte(conn: sqlite3.Connection, figlio_id: int, ora: datetime, prima_di: int | None) -> dict:
    """GET /api/studio/svolte: 20 per volta, dal piu' recente; `altre` se ce ne sono ancora."""
    filtro, parametri = ("", ())
    if prima_di is not None:
        filtro, parametri = " AND id < ?", (max(min(prima_di, INTERO_MASSIMO), INTERO_MINIMO),)
    righe = conn.execute(
        f"SELECT * FROM studio_svolte WHERE figlio_id = ?{filtro} ORDER BY id DESC LIMIT ?",
        (figlio_id, *parametri, SVOLTE_PER_PAGINA + 1),
    ).fetchall()
    firme, note = Firme(conn), _note(conn, figlio_id)
    return {
        "svolte": [formatta(conn, r, ora, firme, note) for r in righe[:SVOLTE_PER_PAGINA]],
        "altre": len(righe) > SVOLTE_PER_PAGINA,
    }


def vista_per_i_dispositivi(
    conn: sqlite3.Connection, figlio_id: int, ora: datetime, note: dict | None = None
) -> dict:
    """Lo `studio` di GET /api/patto (e della finestra): la configurazione, lo Studio in
    corso e le prossime partenze. Senza un telefono dalla 0.18 `in_corso` e' null e le
    partenze sono []: nessun dispositivo parte da solo. `note`: le etichette dell'uso, se
    chi chiama le ha gia' lette."""
    note = note if note is not None else _note(conn, figlio_id)
    in_corso = faccende.studio_in_corso(conn, figlio_id)
    return {
        "config": formatta_config(conn, figlio_id, note),
        "in_corso": formatta(conn, in_corso, ora, note=note) if in_corso is not None else None,
        "prossime_partenze": prossime_partenze(conn, figlio_id, ora),
    }


# --- la configurazione ---

def _config(conn: sqlite3.Connection, figlio_id: int) -> sqlite3.Row | None:
    return conn.execute("SELECT * FROM studio_config WHERE figlio_id = ?", (figlio_id,)).fetchone()


def _contenuto_out(contenuto: dict, note: dict) -> dict:
    return {
        "giorni": contenuto["giorni"],
        "inizio": contenuto["inizio"],
        "chiusura_minima": contenuto["chiusura_minima"],
        "minuti_minimi": contenuto["minuti_minimi"],
        **_liste_out(contenuto, note),
    }


def formatta_config(conn: sqlite3.Connection, figlio_id: int, note: dict | None = None) -> dict:
    riga = _config(conn, figlio_id)
    if riga is None:
        return {"stato": "nessuna", "versione": 0, "approvata": None, "in_attesa": None, "motivazione": None}
    note = note if note is not None else _note(conn, figlio_id)
    firme = Firme(conn)
    approvata = None
    if riga["approvata"] is not None:
        approvata = {
            **_contenuto_out(json.loads(riga["approvata"]), note),
            "orari_dal": riga["orari_dal"],
            "approvata_ts": riga["approvata_ts"],
            "decisa_da": firme.di(riga["decisa_genitore_id"]),
        }
    in_attesa = None
    if riga["in_attesa"] is not None:
        da = conn.execute(
            "SELECT * FROM dispositivi WHERE id = ?", (riga["richiesta_dispositivo_id"],)
        ).fetchone()
        in_attesa = {
            **_contenuto_out(json.loads(riga["in_attesa"]), note),
            "richiesta_ts": riga["richiesta_ts"],
            "da": famiglia.riferimento(da) if da is not None else None,
        }
    return {
        "stato": riga["stato"],
        "versione": riga["versione"],
        "approvata": approvata,
        "in_attesa": in_attesa,
        "motivazione": riga["motivazione"],
    }


def formatta_versioni(conn: sqlite3.Connection, figlio_id: int) -> list[dict]:
    note, firme = _note(conn, figlio_id), Firme(conn)
    return [
        {
            "versione": v["versione"],
            **_contenuto_out(v["contenuto"], note),
            "orari_dal": v["orari_dal"],
            "approvata_ts": v["approvata_ts"],
            "decisa_da": firme.di(v["genitore_id"]),
        }
        for v in reversed(versioni(conn, figlio_id))
    ]


def da_approvare(conn: sqlite3.Connection, figlio_id: int) -> int:
    """0 o 1: c'e' una configurazione dello Studio che aspetta un genitore."""
    riga = _config(conn, figlio_id)
    return int(riga is not None and riga["in_attesa"] is not None)


def _orari(contenuto: dict) -> tuple:
    return (tuple(contenuto["giorni"]), contenuto["inizio"], contenuto["chiusura_minima"],
            contenuto["minuti_minimi"])


def _liste_uguali(uno: dict, altro: dict) -> bool:
    """Le stesse liste: le stesse voci (l'ordine non conta), le stesse etichette e firme."""
    return (
        set(uno["telefono"]["app"]) == set(altro["telefono"]["app"])
        and uno["telefono"]["nomi"] == altro["telefono"]["nomi"]
        and set(uno["computer"]["programmi"]) == set(altro["computer"]["programmi"])
        and uno["computer"]["nomi"] == altro["computer"]["nomi"]
        and uno["computer"].get("firme", {}) == altro["computer"].get("firme", {})
    )


def _uguali(uno: dict, altro: dict) -> bool:
    return _orari(uno) == _orari(altro) and _liste_uguali(uno, altro)


def browser_in_lista(programmi: list[str]) -> bool:
    return any(p.startswith("exe:") and p[len("exe:"):] in BROWSER for p in programmi)


def unisci(base: dict, corpo) -> dict:
    """La proposta completa: i campi mandati, gli altri dalla base. telefono e computer si
    sostituiscono interi (le etichette delle voci tolte cadono; le firme valgono solo per
    le voci exe: della lista)."""
    nuovo = json.loads(json.dumps(base))
    for campo in ("giorni", "inizio", "chiusura_minima", "minuti_minimi"):
        valore = getattr(corpo, campo)
        if valore is not None:
            nuovo[campo] = valore
    if corpo.telefono is not None:
        app = corpo.telefono.app
        nomi = corpo.telefono.nomi or {}
        nuovo["telefono"] = {"app": app, "nomi": {k: v for k, v in nomi.items() if k in set(app)}}
    if corpo.computer is not None:
        programmi = corpo.computer.programmi
        nomi = corpo.computer.nomi or {}
        firme = corpo.computer.firme or {}
        exe = {p for p in programmi if p.startswith("exe:")}
        nuovo["computer"] = {
            "programmi": programmi,
            "nomi": {k: v for k, v in nomi.items() if k in set(programmi)},
            "firme": {k: v for k, v in firme.items() if k in exe},
        }
    return nuovo


def controlla_orari(contenuto: dict) -> None:
    """chiusura_minima non prima di inizio (lo Studio non scavalca la mezzanotte coi suoi
    orari), e uno Studio chiudibile in giornata: max(chiusura_minima, inizio + minimo)
    entro le 23:30 (422 orari_impossibili). Senza, si potrebbe approvare uno Studio che
    finisce per forza `non_chiuso`, con un'accusa su una cosa che il figlio non puo' fare."""
    inizio = _minuti_del_giorno(contenuto["inizio"])
    chiusura = _minuti_del_giorno(contenuto["chiusura_minima"])
    if chiusura < inizio:
        raise HTTPException(
            status_code=422,
            detail=[{"loc": ["body", "chiusura_minima"], "msg": "chiusura_minima viene prima di inizio"}],
        )
    if max(chiusura, inizio + contenuto["minuti_minimi"]) > ULTIMO_MINUTO_CHIUSURA:
        raise HTTPException(status_code=422, detail={"errore": "orari_impossibili"})


def _chiudi_avvisi(conn: sqlite3.Connection, figlio_id: int, tipo: str, filtro=None) -> None:
    """Un solo avviso aperto: quelli di questo tipo e di questo figlio che un genitore non
    ha ancora letto si chiudono per tutti (`letta = 1`)."""
    for avviso in conn.execute(
        "SELECT id, payload FROM notifiche WHERE destinatario = 'genitore' AND letta = 0"
        " AND tipo = ? AND figlio_id = ?",
        (tipo, figlio_id),
    ).fetchall():
        if filtro is None or filtro(json.loads(avviso["payload"])):
            conn.execute("UPDATE notifiche SET letta = 1 WHERE id = ?", (avviso["id"],))


def _nome_figlio(conn: sqlite3.Connection, figlio_id: int) -> str:
    return famiglia.figlio_o_404(conn, figlio_id)["nome"]


def proponi(conn: sqlite3.Connection, chi, corpo, ora: datetime) -> None:
    """PATCH /api/studio/config, dentro il lock: l'unione con la proposta in attesa (o con
    quella approvata, o coi valori di partenza) si legge e si riscrive qui, cosi' telefono
    e computer che propongono insieme non si cancellano a vicenda."""
    ts = clock.iso(ora)
    riga = _config(conn, chi.figlio_id)
    if riga is not None and riga["in_attesa"] is not None:
        base = json.loads(riga["in_attesa"])
    elif riga is not None and riga["approvata"] is not None:
        base = json.loads(riga["approvata"])
    else:
        base = CONFIG_STUDIO_INIZIALE
    nuovo = unisci(base, corpo)
    controlla_orari(nuovo)
    approvata = json.loads(riga["approvata"]) if riga is not None and riga["approvata"] is not None else None
    if riga is None:
        conn.execute(
            "INSERT INTO studio_config (figlio_id, stato, versione) VALUES (?, 'nessuna', 0)",
            (chi.figlio_id,),
        )
    if approvata is not None and _uguali(nuovo, approvata):
        # Il risultato e' proprio quella approvata: il figlio ritira il suo cambio.
        conn.execute(
            "UPDATE studio_config SET in_attesa = NULL, richiesta_ts = NULL,"
            " richiesta_dispositivo_id = NULL, versione = versione + 1 WHERE figlio_id = ?",
            (chi.figlio_id,),
        )
        _chiudi_avvisi(conn, chi.figlio_id, "studio_da_approvare")
        return
    conn.execute(
        "UPDATE studio_config SET in_attesa = ?, richiesta_ts = ?, richiesta_dispositivo_id = ?,"
        " versione = versione + 1,"
        " stato = CASE WHEN approvata IS NULL THEN 'in_attesa' ELSE stato END,"
        " motivazione = CASE WHEN approvata IS NULL THEN NULL ELSE motivazione END"
        " WHERE figlio_id = ?",
        (json.dumps(nuovo), ts, chi.dispositivo_id, chi.figlio_id),
    )
    versione = _config(conn, chi.figlio_id)["versione"]
    cambio = approvata is not None
    nome = _nome_figlio(conn, chi.figlio_id)
    dispositivo = conn.execute("SELECT * FROM dispositivi WHERE id = ?", (chi.dispositivo_id,)).fetchone()
    _chiudi_avvisi(conn, chi.figlio_id, "studio_da_approvare")
    accoda_notifica(
        conn,
        "studio_da_approvare",
        f"{nome} chiede di cambiare lo Studio" if cambio else f"{nome} chiede di approvare lo Studio",
        {"versione": versione, "cambio": cambio, "da": famiglia.riferimento(dispositivo)},
        ts,
        destinatario="genitore",
        figlio_id=chi.figlio_id,
        dispositivo_id=None,
    )


def ritira(conn: sqlite3.Connection, figlio_id: int) -> None:
    """DELETE /api/studio/config/proposta, dentro il lock: senza proposta 409
    niente_da_ritirare. L'avviso aperto si chiude."""
    riga = _config(conn, figlio_id)
    if riga is None or riga["in_attesa"] is None:
        raise HTTPException(status_code=409, detail={"errore": "niente_da_ritirare"})
    # (Correzione) Senza niente di approvato lo stato torna quello di prima della
    # proposta: `rifiutata` se un genitore ne aveva gia' rifiutata una (senza niente di
    # approvato ogni risposta e' stata un rifiuto), altrimenti `nessuna` («mai proposta»).
    rifiutata_prima = conn.execute(
        "SELECT 1 FROM notifiche WHERE destinatario = 'figlio' AND tipo = 'studio_risposta' AND figlio_id = ?"
        " LIMIT 1",
        (figlio_id,),
    ).fetchone() is not None
    conn.execute(
        "UPDATE studio_config SET in_attesa = NULL, richiesta_ts = NULL, richiesta_dispositivo_id = NULL,"
        " versione = versione + 1,"
        " stato = CASE WHEN approvata IS NULL THEN ? ELSE stato END WHERE figlio_id = ?",
        ("rifiutata" if rifiutata_prima else "nessuna", figlio_id),
    )
    _chiudi_avvisi(conn, figlio_id, "studio_da_approvare")


def rispondi(conn: sqlite3.Connection, chi, figlio_id: int, corpo, ora: datetime) -> None:
    """POST /api/studio/config/risposta, dentro il lock. I controlli, in quest'ordine: il
    figlio (404, gia' fatto da chi chiama), niente in attesa (409 niente_da_decidere), la
    versione vista (409 richiesta_cambiata con la configurazione di adesso). Approvare: la
    proposta diventa quella approvata, una riga nuova in studio_versioni, e gli orari
    valgono dal giorno dopo solo se giorni, orari o minimo sono cambiati (le liste valgono
    dalla partenza successiva). Rifiutare: la proposta sparisce, con la sua motivazione."""
    ts = clock.iso(ora)
    riga = _config(conn, figlio_id)
    if riga is None or riga["in_attesa"] is None:
        raise HTTPException(status_code=409, detail={"errore": "niente_da_decidere"})
    if corpo.versione != riga["versione"]:
        raise HTTPException(
            status_code=409,
            detail={"errore": "richiesta_cambiata", "config": formatta_config(conn, figlio_id)},
        )
    proposta = json.loads(riga["in_attesa"])
    approvata = json.loads(riga["approvata"]) if riga["approvata"] is not None else None
    cambio = approvata is not None
    approva = corpo.esito == "approva"
    orari_cambiati = approvata is None or _orari(proposta) != _orari(approvata)
    liste_cambiate = approvata is not None and not _liste_uguali(proposta, approvata)
    versione = riga["versione"] + 1
    if approva:
        orari_dal = domani_nel_patto(ora) if orari_cambiati else riga["orari_dal"]
        conn.execute(
            "UPDATE studio_config SET stato = 'approvata', versione = ?, approvata = ?, orari_dal = ?,"
            " approvata_ts = ?, decisa_genitore_id = ?, in_attesa = NULL, richiesta_ts = NULL,"
            " richiesta_dispositivo_id = NULL, motivazione = NULL WHERE figlio_id = ?",
            (versione, riga["in_attesa"], orari_dal, ts, chi.genitore_id, figlio_id),
        )
        conn.execute(
            "INSERT INTO studio_versioni (figlio_id, versione, contenuto, orari_dal, approvata_ts,"
            " genitore_id) VALUES (?, ?, ?, ?, ?, ?)",
            (figlio_id, versione, riga["in_attesa"], orari_dal, ts, chi.genitore_id),
        )
    else:
        orari_dal = riga["orari_dal"]
        conn.execute(
            "UPDATE studio_config SET versione = ?, in_attesa = NULL, richiesta_ts = NULL,"
            " richiesta_dispositivo_id = NULL, motivazione = ?,"
            " stato = CASE WHEN approvata IS NULL THEN 'rifiutata' ELSE stato END WHERE figlio_id = ?",
            (versione, corpo.motivazione, figlio_id),
        )
    _chiudi_avvisi(conn, figlio_id, "studio_da_approvare")
    genitore = Firme(conn).di(chi.genitore_id)
    messaggio = f"{genitore['nome']} {'ha approvato' if approva else 'non ha approvato'} lo Studio"
    if approva and orari_cambiati:
        messaggio += " (i nuovi orari valgono da domani)"
    if approva and liste_cambiate:
        messaggio += " (la nuova lista vale dal prossimo Studio)"
    accoda_notifica(
        conn,
        "studio_risposta",
        messaggio,
        {"esito": corpo.esito, "versione": versione, "cambio": cambio, "orari_dal": orari_dal,
         "genitore": genitore},
        ts,
        destinatario="figlio",
        figlio_id=figlio_id,
        dispositivo_id=None,
    )


# --- valutare: le partenze, le mezzanotti, i computer spariti ---

def _spariti(conn: sqlite3.Connection, figlio_id: int, ora: datetime) -> list[tuple]:
    """(Parte B) I computer del figlio che hanno smesso di battere durante un blocco o uno
    Studio, senza una sospensione pulita, e per cui il server non l'ha ancora scritto:
    (dispositivo, ultimo battito, durante). Conta il primo istante, da quando il silenzio
    diventa anormale (ultimo battito + 45 minuti) fino a adesso, in cui il computer doveva
    coprire: anche un blocco o uno Studio cominciati dopo che ha smesso di battere (il
    programma ucciso e nascosto prima, che non riparte piu'). Una volta sola per silenzio
    (l'id dell'evento porta l'ultimo battito).

    Da dove si cerca: dal piu' tardo fra il silenzio diventato strano, il primo avvio della
    v4.0 (`faccende_approvazione_dal`: prima girava un server senza questa rete) e 48 ore
    fa. Non si scarta un silenzio cominciato prima: un computer ancora muto adesso, mentre
    deve coprire, si segnala lo stesso (prima un silenzio cominciato sotto la v3.9, o dopo
    50 ore di server spento, restava cieco per sempre). E la ricerca non rilegge settimane
    di storico per un computer muto da tempo.

    (Correzione) Un computer `spento` per una sospensione `disconnessione` (uscita
    dall'account o cambio utente) non e' spento pulito: il contratto (parte E, «Secondo
    account Windows») promette che questa rete coglie chi esce dall'account e ne usa un
    altro. Il silenzio diventa strano 45 minuti dopo l'ultimo segno di vita (battito o
    disconnessione). Lo spegnimento e la sospensione di Windows restano puliti."""
    trovati = []
    approvazione = datetime.fromisoformat(faccende.approvazione_dal(conn))
    for dispositivo in conn.execute(
        "SELECT * FROM dispositivi WHERE figlio_id = ? AND tipo = 'computer' AND revocato_ts IS NULL",
        (figlio_id,),
    ).fetchall():
        stato = famiglia.stato_silenzio(conn, dispositivo, ora)
        if stato["ultimo_battito"] is None:
            continue
        ultimo = datetime.fromisoformat(stato["ultimo_battito"])
        if stato["spento"]:
            if famiglia.motivo_dello_spegnimento(conn, dispositivo["id"]) != "disconnessione":
                continue  # spento pulito: nessuna accusa
            segno = max(ultimo, datetime.fromisoformat(stato["spento_dal"]))
            if ora - segno <= timedelta(minutes=SOGLIA_SILENZIO_MINUTI):
                continue
        elif stato["silente"]:
            segno = ultimo
        else:
            continue
        evento_id = f"server:{SOTTO_TIPO_SPARITO}:{dispositivo['id']}:{stato['ultimo_battito']}"
        if conn.execute("SELECT 1 FROM eventi WHERE id = ?", (evento_id,)).fetchone() is not None:
            continue
        dal = max(segno + timedelta(minutes=SOGLIA_SILENZIO_MINUTI), approvazione,
                  ora - timedelta(hours=ORE_RECUPERO))
        if dal > ora:
            continue
        # Il primo istante, da `dal` fino a adesso, in cui il computer doveva coprire.
        # Anche un blocco o uno Studio partiti DOPO (il programma ucciso e nascosto alle
        # 14:00, lo Studio alle 15:00): e' proprio il caso che questa rete deve cogliere,
        # perche' il programma non riparte piu'.
        trovato = _primo_da_coprire(conn, figlio_id, dispositivo, dal, ora)
        if trovato is None:
            continue
        trovati.append((dispositivo, stato["ultimo_battito"], trovato[1], evento_id))
    return trovati


def _primo_da_coprire(conn: sqlite3.Connection, figlio_id: int, dispositivo: sqlite3.Row, dal: datetime,
                      al: datetime) -> tuple[datetime, str] | None:
    """(Parte B) Il primo istante fra `dal` e `al` in cui questo computer doveva coprire,
    con il perche' ("studio" o "blocco"); None se in quel tempo non ce n'era bisogno. Lo
    Studio conta solo per un computer dalla 0.18, il blocco per uno dalla 0.13. Gli
    istanti da provare sono `dal` e ogni momento in cui uno Studio o un blocco puo' essere
    cominciato dopo (inizio di uno Studio, blocco_da e creazione di un lavoro): il blocco
    ricostruito (faccende.blocco_attivo_a) comincia sempre in uno di questi."""
    da, a = clock.iso(dal), clock.iso(al)
    candidati: list[tuple[datetime, str]] = []
    if faccende.conosce_lo_studio(dispositivo["versione_app"]):
        riga = conn.execute(
            "SELECT MIN(MAX(inizio_ts, ?)) AS primo FROM studio_svolte WHERE figlio_id = ?"
            " AND inizio_ts <= ? AND (fine_ts IS NULL OR fine_ts > ?)",
            (da, figlio_id, a, da),
        ).fetchone()
        if riga["primo"] is not None:
            candidati.append((datetime.fromisoformat(riga["primo"]), "studio"))
    if faccende.conosce_le_faccende(dispositivo["versione_app"]):
        # In ordine di tempo, letti uno alla volta: ci si ferma al primo in cui il blocco
        # c'era (un computer silenzioso da settimane non rilegge tutto lo storico).
        istanti = conn.execute(
            "SELECT ? UNION SELECT blocco_da FROM faccende WHERE figlio_id = ? AND blocco_da > ? AND blocco_da <= ?"
            " UNION SELECT creata_ts FROM faccende WHERE figlio_id = ? AND creata_ts > ? AND creata_ts <= ?"
            " ORDER BY 1",
            (da, figlio_id, da, a, figlio_id, da, a),
        ).fetchall()
        for (istante,) in istanti:
            quando = datetime.fromisoformat(istante)
            if faccende.blocco_attivo_a(conn, figlio_id, quando):
                candidati.append((quando, "blocco"))
                break
    if not candidati:
        return None
    # A parita' d'istante vale lo Studio: durante lo Studio il blocco aspetta.
    return min(candidati, key=lambda c: (c[0], c[1] != "studio"))


def _scrivi_spariti(conn: sqlite3.Connection, figlio_id: int, ora: datetime) -> None:
    ts = clock.iso(ora)
    for dispositivo, ultimo, durante, evento_id in _spariti(conn, figlio_id, ora):
        dettagli = {
            "sotto_tipo": SOTTO_TIPO_SPARITO,
            "durante": durante,
            "dal": ms(datetime.fromisoformat(ultimo)),
            "ts_server": ts,
        }
        nuovo = conn.execute(
            "INSERT OR IGNORE INTO eventi (id, tipo, dettagli, ts_device, ts_server, dispositivo_id)"
            " VALUES (?, 'manomissione', ?, NULL, ?, ?)",
            (evento_id, json.dumps(dettagli), ts, dispositivo["id"]),
        ).rowcount
        if not nuovo:
            continue
        quando = "il blocco" if durante == "blocco" else "lo Studio"
        accoda_notifica(
            conn,
            "manomissione",
            f"Il computer di {_nome_figlio(conn, figlio_id)} non risponde durante {quando}:"
            " può essere senza rete, oppure Pactum è stato fermato",
            {"evento_id": evento_id, "dettagli": dettagli},
            ts,
            figlio_id=figlio_id,
            dispositivo_id=dispositivo["id"],
        )


def _da_elaborare(conn: sqlite3.Connection, figlio_id: int, ora: datetime) -> bool:
    """In sola lettura: c'e' qualcosa da scrivere? Una partenza passata non elaborata, uno
    Studio aperto oltre la sua mezzanotte, un computer sparito non ancora scritto."""
    aperto = faccende.studio_aperto(conn, figlio_id)
    if aperto is not None and _mezzanotte(aperto) <= ora:
        return True
    if _partenze_da_elaborare(conn, figlio_id, ora, versioni(conn, figlio_id)):
        return True
    return bool(_spariti(conn, figlio_id, ora))


def segna_giro(conn: sqlite3.Connection, ora: datetime) -> None:
    """`studio_ultimo_giro`: l'ultima volta che la v4.0 ha guardato le partenze (e
    ricevuto un battito). Dentro una scrittura: serve a riconoscere, al ritorno dalla
    v3.9, che nel frattempo ha girato un server senza Studio."""
    conn.execute(
        "INSERT INTO patto (chiave, valore) VALUES (?, ?)"
        " ON CONFLICT(chiave) DO UPDATE SET valore = excluded.valore WHERE excluded.valore > patto.valore",
        (CHIAVE_ULTIMO_GIRO, clock.iso(ora)),
    )


def valuta(conn: sqlite3.Connection, figlio_id: int, ora: datetime | None = None) -> None:
    """Prima di rispondere a una richiesta che riguarda il figlio: prima si legge, e solo
    se serve si scrive (BEGIN IMMEDIATE, ricontrolla, scrive). Le letture normali non
    prendono il lucchetto di scrittura: un GET non si mette in fila dietro a una foto."""
    ora = ora or clock.now()
    if not _da_elaborare(conn, figlio_id, ora):
        return
    conn.execute("BEGIN IMMEDIATE")
    try:
        elabora(conn, figlio_id, ora)
        segna_giro(conn, ora)
        conn.commit()
    except BaseException:
        conn.rollback()
        raise


def valuta_tutti(conn: sqlite3.Connection, ora: datetime | None = None) -> None:
    ora = ora or clock.now()
    for (figlio_id,) in conn.execute("SELECT id FROM figli ORDER BY id").fetchall():
        valuta(conn, figlio_id, ora)


def elabora(conn: sqlite3.Connection, figlio_id: int, ora: datetime) -> None:
    """Dentro il lock: le partenze passate e le mezzanotti degli Studi aperti, in ordine di
    tempo (la mezzanotte di uno Studio viene prima di una partenza del giorno dopo), poi i
    computer spariti."""
    elenco = versioni(conn, figlio_id)
    while True:
        aperto = faccende.studio_aperto(conn, figlio_id)
        da_fare = _partenze_da_elaborare(conn, figlio_id, ora, elenco)
        partenza = da_fare[0] if da_fare else None
        if aperto is not None:
            mezzanotte = _mezzanotte(aperto)
            if mezzanotte <= ora and (
                partenza is None or mezzanotte <= datetime.fromisoformat(partenza["inizio_ts"])
            ):
                chiudi_a_mezzanotte(conn, aperto, mezzanotte, ora)
                continue
        if partenza is None:
            break
        _elabora_partenza(conn, figlio_id, partenza, aperto, ora, elenco)
    _scrivi_spariti(conn, figlio_id, ora)


def _segna_partenza(conn, figlio_id: int, partenza: dict, studio_id: int | None, esito: str, ts: str) -> None:
    conn.execute(
        "INSERT INTO studio_partenze (figlio_id, giorno, studio_id, esito, inizio_ts, chiudibile_dal,"
        " minuti_minimi, ts_server) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        " ON CONFLICT(figlio_id, giorno) DO UPDATE SET studio_id = excluded.studio_id, esito = excluded.esito",
        (figlio_id, partenza["giorno"], studio_id, esito, partenza["inizio_ts"],
         partenza["chiudibile_dal"], partenza["minuti_minimi"], ts),
    )


def _elabora_partenza(conn, figlio_id: int, partenza: dict, aperto, ora: datetime, elenco: list) -> None:
    """Una partenza non ancora elaborata: se uno Studio era aperto ci entra (diventa una sua
    partenza); se il figlio non ha un telefono dalla 0.18 e' saltata (e i genitori lo
    sanno); altrimenti nasce lo Studio automatico di quel giorno."""
    ts = clock.iso(ora)
    if aperto is not None:
        _segna_partenza(conn, figlio_id, partenza, aperto["id"], "entrata", ts)
        return
    if not faccende.ha_telefono_con_lo_studio(conn, figlio_id):
        _segna_partenza(conn, figlio_id, partenza, None, "saltata", ts)
        if conn.execute(
            "SELECT 1 FROM dispositivi WHERE figlio_id = ? AND tipo = 'telefono' AND revocato_ts IS NULL",
            (figlio_id,),
        ).fetchone() is None:
            # (Correzione) Nessun telefono: non c'e' un telefono «non aggiornato alla 0.18»
            # da segnalare. La migrazione da' lo Studio a ogni figlio, anche a chi ha solo
            # il computer (che non entra mai in Studio, parte E): senza questo i genitori
            # ricevevano ogni giorno feriale un avviso con un testo falso.
            return
        _chiudi_avvisi(conn, figlio_id, "studio_non_partito")
        accoda_notifica(
            conn,
            "studio_non_partito",
            f"Lo Studio di {_nome_figlio(conn, figlio_id)} non è partito: il telefono non è"
            " aggiornato alla 0.18",
            {"giorno": partenza["giorno"], "inizio_ts": partenza["inizio_ts"]},
            ts,
            destinatario="genitore",
            figlio_id=figlio_id,
            dispositivo_id=None,
        )
        return
    crea_automatico(conn, figlio_id, partenza, ora, elenco)


def _chiudi_sessioni(conn: sqlite3.Connection, figlio_id: int, istante: datetime) -> int | None:
    """Le sessioni normali in corso all'istante in cui comincia lo Studio, sui telefoni del
    figlio dalla 0.18, si chiudono `terminata` a quell'istante (quelle dei telefoni piu'
    vecchi no: le chiude solo chi conosce lo Studio). Una chiusura gia' scritta non si
    riscrive. L'id della prima chiusa (per `sessione_chiusa`)."""
    t = clock.iso(istante)
    prima = None
    for sessione in conn.execute(
        "SELECT s.id, d.versione_app FROM sessioni_svolte s JOIN dispositivi d ON d.id = s.dispositivo_id"
        " WHERE d.figlio_id = ? AND s.fine_ts IS NULL AND s.inizio_ts <= ? AND s.fine_prevista_ts > ?"
        " ORDER BY s.id",
        (figlio_id, t, t),
    ).fetchall():
        if not faccende.conosce_lo_studio(sessione["versione_app"]):
            continue
        conn.execute(
            "UPDATE sessioni_svolte SET fine_ts = ?, chiusura = 'terminata' WHERE id = ? AND fine_ts IS NULL",
            (t, sessione["id"]),
        )
        prima = prima or sessione["id"]
    return prima


def _orfani(conn, figlio_id: int, da: datetime, a: datetime) -> list[sqlite3.Row]:
    """(Correzione) I tratti del figlio senza Studio che si sovrappongono a [da, a]: quelli
    arrivati prima che il loro Studio nascesse (la coda di un telefono tornato in rete che
    manda i tratti prima dell'`avvia`). In ordine d'arrivo dell'esito finale."""
    righe = conn.execute(
        "SELECT * FROM studio_tratti WHERE figlio_id = ? AND studio_id IS NULL AND (fine IS NULL OR fine > ?)"
        " ORDER BY COALESCE(esito_ts, arrivo_ts), rowid",
        (figlio_id, ms(da)),
    ).fetchall()
    trovati = []
    for riga in righe:
        intervallo = _intervallo(riga) if riga["fine"] is not None else (riga["inizio"], ms(a))
        if _taglia(intervallo, ms(da), ms(a)) is not None:
            trovati.append(riga)
    return trovati


def _decidi_orfani(conn, figlio_id: int, orfani: list, studio, ora: datetime, contati: list) -> list[tuple]:
    """(Correzione) Per ogni tratto senza Studio che passa allo Studio che nasce: il suo
    `conta`, deciso adesso una volta sola (se finito e non ancora deciso), con le regole di
    sempre (_decidi_conta) sullo Studio che nasce. `studio` puo' essere lo Studio come
    sara' (un dizionario, prima di scriverlo). [(tratto, conta)]."""
    contati = list(contati)
    decisi = []
    for tratto in orfani:
        conta = tratto["conta"]
        if tratto["esito"] != "in_corso" and conta is None:
            conta = int(_decidi_conta(conn, figlio_id, tratto, studio, ora, gia=contati))
        if conta:
            contati.append(tratto)
        decisi.append((tratto, conta))
    return decisi


def _adotta(conn, studio_id: int, decisi: list[tuple]) -> None:
    conn.executemany(
        "UPDATE studio_tratti SET studio_id = ?, conta = ? WHERE id = ? AND studio_id IS NULL",
        [(studio_id, conta, tratto["id"]) for tratto, conta in decisi],
    )


def _liste_della_versione(versione: dict | None) -> dict:
    contenuto = versione["contenuto"] if versione is not None else CONFIG_STUDIO_INIZIALE
    return {"telefono": contenuto["telefono"], "computer": contenuto["computer"]}


def crea_automatico(conn, figlio_id: int, partenza: dict, ora: datetime, elenco: list,
                    tratti_da: tuple[int, datetime] | None = None) -> int:
    """Lo Studio automatico di un giorno, con inizio_ts = l'istante della partenza (non
    l'ora della richiesta), le liste dell'ultima versione approvata prima di quell'istante,
    e le sessioni in corso a quell'istante chiuse. Se nasce gia' oltre la sua mezzanotte
    (server spento a lungo) si chiude subito `non_chiuso`: solo studio_non_chiuso, niente
    studio_iniziato. `tratti_da` (studio, T): i tratti di quello Studio che finiscono dopo
    T passano a questo (la chiusura tardiva del figlio)."""
    ts = clock.iso(ora)
    inizio = datetime.fromisoformat(partenza["inizio_ts"])
    versione = _versione_a(elenco, inizio)
    mezzanotte = mezzanotte_dopo(date.fromisoformat(partenza["giorno"]))
    # Uno Studio che nasce gia' oltre la sua mezzanotte nasce GIA' chiuso (nella stessa
    # riga, non aperto e poi chiuso): intanto puo' essere aperto lo Studio di oggi (la
    # chiusura tardiva di ieri, consegnata oggi alle 15:30, che rielabora la partenza di
    # ieri), e "uno aperto per figlio" vale sempre. I minuti a mezzanotte sono quelli dei
    # tratti che passano a lui (contati dalla sua partenza).
    gia_chiuso = mezzanotte <= ora
    da_spostare = []
    if tratti_da is not None:
        vecchio, t = tratti_da
        da_spostare = conn.execute(
            "SELECT * FROM studio_tratti WHERE studio_id = ? AND COALESCE(fine, inizio) > ?",
            (vecchio, ms(t)),
        ).fetchall()
    # (Correzione) I tratti senza Studio che cadono in questo passano a lui.
    come_sara = {"id": None, "inizio_ts": partenza["inizio_ts"],
                 "fine_ts": clock.iso(mezzanotte) if gia_chiuso else None,
                 "chiusura": "non_chiuso" if gia_chiuso else None}
    orfani = _decidi_orfani(conn, figlio_id, _orfani(conn, figlio_id, inizio, mezzanotte if gia_chiuso else ora),
                            come_sara, ora, [r for r in da_spostare if r["conta"]])
    minuti = None
    if gia_chiuso:
        contano = [r for r in da_spostare if r["conta"]] + [r for r, conta in orfani if conta]
        minuti = secondi_di_attivita(contano, ms(inizio), ms(mezzanotte)) // 60
    sessione = _chiudi_sessioni(conn, figlio_id, inizio)
    studio_id = conn.execute(
        "INSERT INTO studio_svolte (figlio_id, origine, giorno, inizio_ts, liste, liste_versione,"
        " minuti_minimi_avvio, sessione_chiusa_id, fine_ts, chiusura, minuti_alla_chiusura)"
        " VALUES (?, 'automatica', ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        (figlio_id, partenza["giorno"], partenza["inizio_ts"], json.dumps(_liste_della_versione(versione)),
         versione["versione"] if versione is not None else None, partenza["minuti_minimi"], sessione,
         clock.iso(mezzanotte) if gia_chiuso else None, "non_chiuso" if gia_chiuso else None, minuti),
    ).lastrowid
    _segna_partenza(conn, figlio_id, partenza, studio_id, "nato", ts)
    _adotta(conn, studio_id, orfani)
    if da_spostare:
        conn.executemany(
            "UPDATE studio_tratti SET studio_id = ? WHERE id = ?", [(studio_id, r["id"]) for r in da_spostare]
        )
    if gia_chiuso:
        _avvisa_non_chiuso(conn, _riga(conn, studio_id), mezzanotte, minuti, ora)
    else:
        accoda_notifica(
            conn,
            "studio_iniziato",
            f"{_nome_figlio(conn, figlio_id)} è in Studio dalle {ora_e_minuti(inizio)}",
            {"studio_id": studio_id, "origine": "automatica", "inizio_ts": partenza["inizio_ts"]},
            ts,
            destinatario="genitore",
            figlio_id=figlio_id,
            dispositivo_id=None,
        )
    return studio_id


def chiudi_a_mezzanotte(conn, studio: sqlite3.Row, mezzanotte: datetime, ora: datetime) -> None:
    """Uno Studio aperto alla mezzanotte del suo giorno si chiude da solo `non_chiuso`,
    coi minuti di attivita' fatti fino a li'. L'avviso ai genitori non parte se lo Studio
    non si poteva chiudere (dall'inizio dei conti a mezzanotte mancava meno del minimo:
    non e' una colpa)."""
    minuti = minuti_di_attivita(conn, studio, mezzanotte)
    conn.execute(
        "UPDATE studio_svolte SET fine_ts = ?, chiusura = 'non_chiuso', minuti_alla_chiusura = ?"
        " WHERE id = ? AND fine_ts IS NULL",
        (clock.iso(mezzanotte), minuti, studio["id"]),
    )
    _avvisa_non_chiuso(conn, studio, mezzanotte, minuti, ora)


def _avvisa_non_chiuso(conn, studio: sqlite3.Row, mezzanotte: datetime, minuti: int, ora: datetime) -> None:
    """studio_non_chiuso ai genitori, se lo Studio si poteva chiudere prima di mezzanotte."""
    conta_dal, _, minimi = condizioni(studio, _partenze_dello_studio(conn, studio["id"]), mezzanotte)
    if mezzanotte - conta_dal < timedelta(minutes=minimi):
        return
    accoda_notifica(
        conn,
        "studio_non_chiuso",
        f"Lo Studio di {_nome_figlio(conn, studio['figlio_id'])} non è stato chiuso: {minuti} min di attività",
        {"studio_id": studio["id"], "fine_ts": clock.iso(mezzanotte), "minuti_attivita": minuti},
        clock.iso(ora),
        destinatario="genitore",
        figlio_id=studio["figlio_id"],
        dispositivo_id=None,
    )


# --- i tratti ---

def _studio_per_intervallo(conn, figlio_id: int, inizio: int, fine: int, ora: datetime) -> sqlite3.Row | None:
    """Lo Studio del figlio con cui l'intervallo [inizio, fine] (ms) si sovrappone di piu'."""
    migliore, sovrapposto = None, 0
    for studio in conn.execute(
        "SELECT * FROM studio_svolte WHERE figlio_id = ? AND inizio_ts < ? AND (fine_ts IS NULL OR fine_ts > ?)",
        (figlio_id, clock.iso(da_ms(fine)), clock.iso(da_ms(inizio))),
    ).fetchall():
        da = ms(datetime.fromisoformat(studio["inizio_ts"]))
        a = ms(_dt(studio["fine_ts"]) or ora)
        comune = min(fine, a) - max(inizio, da)
        if comune > sovrapposto:
            migliore, sovrapposto = studio, comune
    return migliore


def _decidi_conta(conn, figlio_id: int, tratto, studio, ora: datetime, gia: list | None = None) -> bool:
    """Se un tratto arrivato al suo esito finale conta: ha le ore dentro le tutele, almeno
    un secondo dentro [conta_dal, fine dello Studio], non e' tutto coperto dai tratti gia'
    contati (la parte comune si conta una volta sola: con due telefoni vale l'ordine
    d'arrivo degli esiti finali), e il suo Studio non e' gia' stato chiuso dal figlio (i
    tratti arrivati dopo quella chiusura restano nel registro e non contano). Dopo la
    chiusura del genitore il tratto conta (tagliato alla chiusura); dopo il `non_chiuso` di
    mezzanotte anche (lo Studio puo' ancora cedere a una chiusura del figlio)."""
    if studio is None or not tratto["tutele"] or studio["chiusura"] == "figlio":
        return False
    # Dentro lo Studio, dal suo inizio: quanto del tratto cade dopo `conta_dal` lo dicono
    # secondi_contati e minuti_attivita, che si ricalcolano (una chiusura senza rete fatta
    # prima di una partenza assorbita si giudica a T, quando conta_dal era ancora l'inizio).
    fine_studio = _dt(studio["fine_ts"]) or ora
    inizio_studio = datetime.fromisoformat(studio["inizio_ts"])
    tagliato = _taglia(_intervallo(tratto), ms(inizio_studio), ms(fine_studio))
    if tagliato is None or tagliato[1] - tagliato[0] < 1000:
        return False
    if gia is None:
        gia = [t for t in _tratti_dello_studio(conn, studio["id"]) if t["conta"]]
    # (Correzione) La parte comune si conta una volta sola anche fra tratti senza l'ora
    # agganciata (e fra due telefoni); non si guarda solo fra un tratto agganciato e uno
    # no dello stesso telefono (_senza_unione).
    coperti = [
        x for x in (
            _taglia(_intervallo(t), tagliato[0], tagliato[1])
            for t in gia if t["id"] != tratto["id"] and not _senza_unione(t, tratto)
        )
        if x is not None
    ]
    return _lunghezza_unione(coperti) < tagliato[1] - tagliato[0]


def registra_tratti(conn, chi, tratti: list, ora: datetime) -> dict:
    """POST /api/studio/tratti (e i tratti nel corpo di una chiusura), dentro il lock.
    L'id del telefono e' la chiave di idempotenza: un id nuovo si scrive; un tratto in
    corso passa una volta sola a un esito finale; lo stesso esito di nuovo non e' un
    errore; un esito diverso su un tratto gia' finito si ignora (anche un id di un altro
    figlio). Le ore seguono le tutele: non nel futuro, non piu' di 48 ore prima
    dell'arrivo; una fine entro 2 minuti dall'arrivo vale l'arrivo. Un tratto con le ore
    fuori dalle tutele resta nel registro e non conta."""
    arrivo = ms(ora)
    ts = clock.iso(ora)
    tolleranza = int(clock.TOLLERANZA_OROLOGIO.total_seconds() * 1000)
    ritardo = int(clock.RITARDO_MASSIMO.total_seconds() * 1000)
    nuovi = aggiornati = ignorati = 0
    ids = []
    for t in tratti:
        ids.append(t.id)
        esistente = conn.execute("SELECT * FROM studio_tratti WHERE id = ?", (t.id,)).fetchone()
        if esistente is not None and (
            esistente["figlio_id"] != chi.figlio_id or esistente["esito"] != "in_corso" or t.esito == "in_corso"
        ):
            ignorati += 1
            continue
        faccenda_id = t.faccenda_id
        if faccenda_id is not None and conn.execute(
            "SELECT 1 FROM faccende WHERE id = ? AND figlio_id = ?",
            (faccenda_id if INTERO_MINIMO <= faccenda_id <= INTERO_MASSIMO else 0, chi.figlio_id),
        ).fetchone() is None:
            faccenda_id = None
        if t.esito == "in_corso":
            tutele = arrivo - ritardo <= t.inizio <= arrivo + tolleranza
            inizio = min(t.inizio, arrivo)
            studio = _studio_per_intervallo(conn, chi.figlio_id, inizio, max(arrivo, inizio + 1), ora) if tutele else None
            conn.execute(
                "INSERT INTO studio_tratti (id, figlio_id, dispositivo_id, tipo, parola, faccenda_id, inizio,"
                " fine, fine_mandata, ora_agganciata, secondi, esito, tutele, conta, studio_id, arrivo_ts)"
                " VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?, 'in_corso', ?, NULL, ?, ?)",
                (t.id, chi.figlio_id, chi.dispositivo_id, t.tipo, t.parola, faccenda_id, t.inizio,
                 int(t.ora_agganciata), t.secondi, int(tutele), studio["id"] if studio else None, ts),
            )
            nuovi += 1
            continue
        fine = arrivo if abs(t.fine - arrivo) <= tolleranza else t.fine
        tutele = fine <= arrivo and fine - t.secondi * 1000 >= arrivo - ritardo
        studio = _studio_per_intervallo(conn, chi.figlio_id, fine - t.secondi * 1000, fine, ora)
        valori = {
            "id": t.id, "ora_agganciata": int(t.ora_agganciata), "secondi": t.secondi,
            "fine": fine, "tutele": int(tutele), "dispositivo_id": chi.dispositivo_id,
        }
        # (Correzione) Senza uno Studio, `conta` resta da decidere (NULL): il tratto mandato
        # prima del suo `avvia` (la coda di un telefono tornato in rete) si decide quando lo
        # Studio nasce (_adotta_orfani). Per le app e' false.
        conta = _decidi_conta(conn, chi.figlio_id, valori, studio, ora) if studio is not None else None
        if esistente is None:
            conn.execute(
                "INSERT INTO studio_tratti (id, figlio_id, dispositivo_id, tipo, parola, faccenda_id, inizio,"
                " fine, fine_mandata, ora_agganciata, secondi, esito, tutele, conta, studio_id, arrivo_ts,"
                " esito_ts) VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (t.id, chi.figlio_id, chi.dispositivo_id, t.tipo, t.parola, faccenda_id, fine, t.fine,
                 int(t.ora_agganciata), t.secondi, t.esito, int(tutele), None if conta is None else int(conta),
                 studio["id"] if studio else None, ts, ts),
            )
            nuovi += 1
        else:
            conn.execute(
                "UPDATE studio_tratti SET fine = ?, fine_mandata = ?, secondi = ?, esito = ?, tutele = ?,"
                " conta = ?, studio_id = ?, esito_ts = ?, ora_agganciata = ? WHERE id = ? AND esito = 'in_corso'",
                (fine, t.fine, t.secondi, t.esito, int(tutele), None if conta is None else int(conta), studio["id"] if studio else None,
                 ts, int(t.ora_agganciata), t.id),
            )
            aggiornati += 1
    return {"ricevuti": len(tratti), "nuovi": nuovi, "aggiornati": aggiornati, "ignorati": ignorati,
            "ids": ids}


def tratti_out(conn, ids: list[str], ora: datetime) -> list[dict]:
    """I tratti di `ids` come sono adesso (coi secondi che contano nel loro Studio)."""
    out = []
    for tratto_id in dict.fromkeys(ids):
        tratto = conn.execute("SELECT * FROM studio_tratti WHERE id = ?", (tratto_id,)).fetchone()
        if tratto is None:
            continue
        conta_dal = fine = None
        if tratto["studio_id"] is not None:
            studio = _riga(conn, tratto["studio_id"])
            fine_dt = _dt(studio["fine_ts"]) or ora
            conta_dal = ms(condizioni(studio, _partenze_dello_studio(conn, studio["id"]), fine_dt)[0])
            fine = ms(fine_dt)
        out.append(_tratto_out(tratto, conta_dal, fine))
    return out


# --- l'avvio a mano ---

def avvia(conn, chi, corpo, arrivo: datetime) -> tuple[int, int]:
    """POST /api/studio/avvia, dentro il lock (partenze e mezzanotti gia' elaborate).
    (stato, id dello Studio): 201 se nasce, 200 se c'era gia'. I controlli: la stessa
    chiave (200), un avvio oltre le 48 ore (409 avvio_scaduto, non nasce niente), niente
    di approvato prima dell'inizio (409 studio_non_approvato), l'inizio dentro uno Studio
    (200: il dispositivo lo adotta), troppo tardi per chiuderlo prima di mezzanotte (409
    troppo_tardi), il blocco dei lavori attivo all'inizio (409 blocco_faccende, anche per
    un avvio consegnato in ritardo)."""
    esistente = conn.execute(
        "SELECT * FROM studio_svolte WHERE figlio_id = ? AND chiave = ?", (chi.figlio_id, corpo.chiave)
    ).fetchone()
    if esistente is not None:
        return 200, esistente["id"]
    dichiarato = clock.da_ts_device(corpo.ts_device)
    if dichiarato is not None and arrivo - dichiarato > clock.RITARDO_MASSIMO:
        raise HTTPException(status_code=409, detail={"errore": "avvio_scaduto"})
    inizio = clock.momento_dichiarato(arrivo, corpo.ts_device).replace(microsecond=0)
    elenco = versioni(conn, chi.figlio_id)
    versione = _versione_a(elenco, inizio)
    if versione is None:
        raise HTTPException(status_code=409, detail={"errore": "studio_non_approvato"})
    t = clock.iso(inizio)
    dentro = conn.execute(
        "SELECT * FROM studio_svolte WHERE figlio_id = ? AND inizio_ts <= ? AND (fine_ts IS NULL OR fine_ts > ?)"
        " ORDER BY inizio_ts DESC LIMIT 1",
        (chi.figlio_id, t, t),
    ).fetchone()
    if dentro is not None:
        return 200, dentro["id"]
    giorno = giorno_locale(inizio)
    # (Correzione) Il minimo (e il controllo troppo_tardi) e' quello degli orari IN VIGORE
    # quel giorno, come per lo Studio automatico dello stesso giorno: un minimo cambiato
    # oggi vale da domani (contratto, «Quando vale una decisione», «anche alla prima
    # approvazione»), e lo Studio a mano non diventa un modo per avere il minimo nuovo
    # prima. Le liste restano quelle dell'ultima versione approvata prima di I. Se quel
    # giorno non e' in vigore niente (la prima approvazione e' di oggi), vale il minimo di
    # quella versione: l'avvio a mano resta possibile, come prima.
    in_vigore = _orari_del_giorno(elenco, giorno)
    minimi = (in_vigore or versione["contenuto"])["minuti_minimi"]
    if mezzanotte_dopo(giorno) - inizio < timedelta(minutes=minimi):
        raise HTTPException(status_code=409, detail={"errore": "troppo_tardi"})
    if faccende.blocco_attivo_a(conn, chi.figlio_id, inizio):
        raise HTTPException(status_code=409, detail={"errore": "blocco_faccende"})
    dopo = conn.execute(
        "SELECT * FROM studio_svolte WHERE figlio_id = ? AND giorno = ? AND inizio_ts > ?"
        " ORDER BY inizio_ts LIMIT 1",
        (chi.figlio_id, giorno.isoformat(), t),
    ).fetchone()
    if dopo is not None and dopo["fine_ts"] is None:
        # Uno Studio aperto e' cominciato dopo I, lo stesso giorno: si sposta indietro a I
        # (contratto, avvio a mano), e la sua partenza resta una sua partenza.
        # - Automatico: diventa a mano con questa chiave.
        # - (Correzione) Gia' a mano (un altro telefono, con la rete): si sposta indietro
        #   lo stesso (prima il telefono lo adottava, e il tempo fra I e l'altro avvio non
        #   contava), ma TIENE la sua chiave, che l'altro telefono conosce; questo telefono
        #   lo chiude con l'id della risposta (o con la sua chiave: trova_per_chiusura).
        if dopo["origine"] == "automatica":
            conn.execute(
                "UPDATE studio_svolte SET inizio_ts = ?, origine = 'manuale', chiave = ?, avviato_dispositivo_id = ?"
                " WHERE id = ?",
                (t, corpo.chiave, chi.dispositivo_id, dopo["id"]),
            )
        else:
            conn.execute(
                "UPDATE studio_svolte SET inizio_ts = ?, avviato_dispositivo_id = ? WHERE id = ?",
                (t, chi.dispositivo_id, dopo["id"]),
            )
        sessione = _chiudi_sessioni(conn, chi.figlio_id, inizio)
        if sessione is not None and dopo["sessione_chiusa_id"] is None:
            conn.execute("UPDATE studio_svolte SET sessione_chiusa_id = ? WHERE id = ?", (sessione, dopo["id"]))
        spostato = _riga(conn, dopo["id"])
        contati = [r for r in _tratti_dello_studio(conn, dopo["id"]) if r["conta"]]
        _adotta(conn, dopo["id"],
                _decidi_orfani(conn, chi.figlio_id, _orfani(conn, chi.figlio_id, inizio, arrivo), spostato,
                               arrivo, contati))
        return 200, dopo["id"]
    if dopo is not None:
        # Uno Studio di quel giorno, cominciato dopo I, e' gia' chiuso: il dispositivo
        # adotta quello (e chiude il suo), invece di un secondo Studio sovrapposto.
        return 200, dopo["id"]
    sessione = _chiudi_sessioni(conn, chi.figlio_id, inizio)
    # Un avvio a mano di un giorno di prima, consegnato adesso: nasce gia' chiuso alla sua
    # mezzanotte (lo Studio di oggi, se c'e', non si tocca).
    di_ieri = giorno < giorno_locale(arrivo)
    mezzanotte = mezzanotte_dopo(giorno)
    # (Correzione) I tratti mandati prima di questo avvio (la coda del telefono tornato in
    # rete) passano allo Studio che nasce, e si decide adesso se contano.
    come_sara = {"id": None, "inizio_ts": t, "fine_ts": clock.iso(mezzanotte) if di_ieri else None,
                 "chiusura": "non_chiuso" if di_ieri else None}
    orfani = _decidi_orfani(conn, chi.figlio_id, _orfani(conn, chi.figlio_id, inizio, mezzanotte if di_ieri else arrivo),
                            come_sara, arrivo, [])
    minuti = None
    if di_ieri:
        minuti = secondi_di_attivita([r for r, conta in orfani if conta], ms(inizio), ms(mezzanotte)) // 60
    studio_id = conn.execute(
        "INSERT INTO studio_svolte (figlio_id, origine, giorno, chiave, inizio_ts, avviato_dispositivo_id,"
        " liste, liste_versione, minuti_minimi_avvio, sessione_chiusa_id, fine_ts, chiusura,"
        " minuti_alla_chiusura) VALUES (?, 'manuale', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        (chi.figlio_id, giorno.isoformat(), corpo.chiave, t, chi.dispositivo_id,
         json.dumps(_liste_della_versione(versione)), versione["versione"], minimi, sessione,
         clock.iso(mezzanotte) if di_ieri else None, "non_chiuso" if di_ieri else None, minuti),
    ).lastrowid
    _adotta(conn, studio_id, orfani)
    if di_ieri:
        _avvisa_non_chiuso(conn, _riga(conn, studio_id), mezzanotte, minuti, arrivo)
    return 201, studio_id


# --- le chiusure ---

def _voci_dei_tratti(tratti: list[dict]) -> list[str]:
    voci = []
    for tratto in tratti:
        if tratto["tipo"] == "altro":
            voce = tratto["parola"]
        elif tratto["tipo"] == "compiti":
            voce = f"compiti ({tratto['parola']})" if tratto["parola"] else "compiti"
        else:
            voce = f"lavori di casa ({tratto['parola']})" if tratto["parola"] else "lavori di casa"
        if voce not in voci:
            voci.append(voce)
    return voci


def _accorcia(testo: str) -> str:
    if len(testo) <= LUNGHEZZA_DICHIARAZIONE_AVVISO:
        return testo
    return testo[: LUNGHEZZA_DICHIARAZIONE_AVVISO - 1].rstrip() + "…"


def _tratti_dell_avviso(vista: dict) -> list[dict]:
    """(Correzione) I tratti che l'avviso studio_chiuso elenca: quelli che contano E hanno
    secondi dentro [conta_dal, fine] (un tratto «compiti» delle 14:05-14:50 in uno Studio
    a mano che ha assorbito la partenza delle 15:00 conta per la regola, ma i suoi minuti
    non entrano nel totale: non va nell'elenco), coi minuti contati. Cosi' l'elenco torna
    col totale."""
    return [t for t in vista["tratti"] if t["conta"] and t["secondi_contati"] > 0]


def _payload_chiuso(vista: dict) -> dict:
    contano = _tratti_dell_avviso(vista)
    return {
        "studio_id": vista["id"],
        "fine_ts": vista["fine_ts"],
        "chiusura": vista["chiusura"],
        "tratti": [{"tipo": t["tipo"], "parola": t["parola"], "minuti": t["secondi_contati"] // 60}
                   for t in contano],
        "minuti_attivita": vista["minuti_alla_chiusura"]
        if vista["minuti_alla_chiusura"] is not None else vista["minuti_attivita"],
        "dichiarazione": vista["dichiarazione"],
        "motivo": vista["motivo"],
    }


def _avvisa_chiusura_del_figlio(conn, studio_id: int, t: datetime, ora: datetime, in_ritardo: bool,
                                 arrivata_dopo: bool = False, dopo_il_genitore: dict | None = None) -> None:
    vista = formatta(conn, _riga(conn, studio_id), ora)
    voci = _voci_dei_tratti(_tratti_dell_avviso(vista))
    minuti = vista["minuti_alla_chiusura"] if vista["minuti_alla_chiusura"] is not None else vista["minuti_attivita"]
    messaggio = (
        f"{_nome_figlio(conn, vista_figlio(conn, studio_id))} ha chiuso lo Studio alle {ora_e_minuti(t)}"
        f" (dalle {ora_e_minuti(vista['inizio_ts'])}): {minuti} min"
        + (" — " + ", ".join(voci) if voci else "")
        + f". «{_accorcia(vista['dichiarazione'])}»"
    )
    if dopo_il_genitore is not None:
        messaggio += f" (dopo la chiusura di {dopo_il_genitore['nome']})"
    if in_ritardo:
        messaggio += f" (chiusa senza rete alle {ora_e_minuti(t)}" + (", arrivata dopo)" if arrivata_dopo else ")")
    payload = _payload_chiuso(vista)
    accoda_notifica(conn, "studio_chiuso", messaggio, payload, clock.iso(ora), destinatario="genitore",
                    figlio_id=vista_figlio(conn, studio_id), dispositivo_id=None)


def vista_figlio(conn, studio_id: int) -> int:
    return _riga(conn, studio_id)["figlio_id"]


def trova_per_chiusura(conn, chi, studio_id: int | None, quale, t: datetime | None = None) -> sqlite3.Row | None:
    """Lo Studio di una chiusura del figlio: per id, oppure (senza id) quello che contiene
    la partenza di quel giorno o quello avviato a mano con quella chiave.

    (Correzione) Una chiave che il server non conosce e' quella di un avvio a mano che il
    server ha fatto ADOTTARE (l'avvio di un secondo telefono dentro uno Studio gia'
    aperto: 200 con l'altro Studio, e la chiave non si registra). Se la chiusura di quel
    telefono era gia' in coda con la sua chiave, vale lo Studio del figlio che contiene
    `t`, l'ora della chiusura (inizio_ts <= t < fine_ts, o aperto): proprio quello che il
    telefono ha adottato. Senza, riceveva 404 e lo Studio restava aperto fino a
    mezzanotte, col computer in Studio e un «non chiuso» falso."""
    if studio_id is not None:
        if not INTERO_MINIMO <= studio_id <= INTERO_MASSIMO:
            return None
        riga = _riga(conn, studio_id)
        return riga if riga is not None and riga["figlio_id"] == chi.figlio_id else None
    if quale is None:
        return None
    if quale.chiave is not None:
        riga = conn.execute(
            "SELECT * FROM studio_svolte WHERE figlio_id = ? AND chiave = ?", (chi.figlio_id, quale.chiave)
        ).fetchone()
        if riga is not None or t is None:
            return riga
        quando = clock.iso(t)
        return conn.execute(
            "SELECT * FROM studio_svolte WHERE figlio_id = ? AND inizio_ts <= ?"
            " AND (fine_ts IS NULL OR fine_ts > ?) ORDER BY inizio_ts DESC, id DESC LIMIT 1",
            (chi.figlio_id, quando, quando),
        ).fetchone()
    partenza = conn.execute(
        "SELECT studio_id FROM studio_partenze WHERE figlio_id = ? AND giorno = ? AND studio_id IS NOT NULL",
        (chi.figlio_id, quale.giorno),
    ).fetchone()
    return _riga(conn, partenza["studio_id"]) if partenza is not None else None


def istantanea(conn, figlio_id: int) -> tuple[int | None, int, int]:
    """Prima di elaborare una richiesta di chiusura: lo Studio aperto, l'ultimo Studio e
    l'ultima notifica. Servono a riconoscere uno Studio chiuso a mezzanotte DENTRO questa
    stessa richiesta (chiuso_in_questa_richiesta)."""
    aperto = faccende.studio_aperto(conn, figlio_id)
    ultimo = conn.execute("SELECT COALESCE(MAX(id), 0) FROM studio_svolte").fetchone()[0]
    notifica = conn.execute("SELECT COALESCE(MAX(id), 0) FROM notifiche").fetchone()[0]
    return (aperto["id"] if aperto is not None else None, ultimo, notifica)


def chiuso_in_questa_richiesta(prima: tuple[int | None, int, int], studio: sqlite3.Row) -> int | None:
    """Se `studio` e' diventato `non_chiuso` in questa richiesta (era aperto prima, o e'
    nato adesso gia' oltre la sua mezzanotte), l'ultima notifica di prima; se no None."""
    aperto_id, ultimo, notifica = prima
    if studio["chiusura"] == "non_chiuso" and (studio["id"] == aperto_id or studio["id"] > ultimo):
        return notifica
    return None


def _togli_avviso_non_chiuso(conn, studio_id: int, figlio_id: int, dopo_notifica: int) -> None:
    """L'avviso `studio_non_chiuso` nato in QUESTA transazione per lo Studio che il figlio
    chiude adesso: nessuno l'ha visto (stessa transazione), e col giusto ordine (prima le
    chiusure consegnate, poi la mezzanotte) non sarebbe mai nato."""
    for avviso in conn.execute(
        "SELECT id, payload FROM notifiche WHERE id > ? AND tipo = 'studio_non_chiuso' AND figlio_id = ?",
        (dopo_notifica, figlio_id),
    ).fetchall():
        if json.loads(avviso["payload"]).get("studio_id") == studio_id:
            conn.execute("DELETE FROM notifiche WHERE id = ?", (avviso["id"],))


def chiudi_dal_figlio(conn, chi, corpo, studio: sqlite3.Row, arrivo: datetime,
                      chiuso_adesso: int | None = None) -> dict | None:
    """La chiusura del figlio, dentro il lock (partenze, mezzanotti e tratti del corpo gia'
    scritti). Restituisce None se lo Studio e' chiuso dal figlio (o se la chiusura si salva
    sullo Studio), altrimenti il `detail` del 409. Le condizioni si calcolano a T, l'ora
    della chiusura con le tutele della v3.5 (non prima dell'inizio).

    (Correzione) Il contratto chiede, nella stessa richiesta, PRIMA le chiusure consegnate
    del figlio e POI la mezzanotte. Lo Studio pero' si trova dopo aver elaborato partenze e
    mezzanotti (la partenza del giorno puo' crearlo). Percio', se lo Studio e' diventato
    `non_chiuso` proprio in questa richiesta (`chiuso_adesso`: l'ultima notifica di prima),
    la chiusura si giudica come se fosse ancora aperto: valida a T, prende il posto della
    mezzanotte senza «arrivata dopo» e l'avviso di mezzanotte nato adesso si toglie; non
    valida, riceve il suo 409 (troppo_presto o attivita_insufficiente), non gia_chiuso."""
    if studio["chiave_chiusura"] == corpo.chiave:
        return None
    t = clock.momento_dichiarato(arrivo, corpo.ts_device).replace(microsecond=0)
    if t < datetime.fromisoformat(studio["inizio_ts"]):
        t = arrivo.replace(microsecond=0)
    in_ritardo = t != arrivo.replace(microsecond=0)
    if studio["fine_ts"] is not None:
        fine = datetime.fromisoformat(studio["fine_ts"])
        if studio["chiusura"] == "genitore" and t < fine:
            if studio["dichiarazione"] is None:
                conn.execute(
                    "UPDATE studio_svolte SET dichiarazione = ?, dichiarazione_ts = ?, chiave_chiusura = ?"
                    " WHERE id = ?",
                    (corpo.dichiarazione, clock.iso(t), corpo.chiave, studio["id"]),
                )
                genitore = Firme(conn).di(studio["chiusa_genitore_id"])
                _avvisa_chiusura_del_figlio(conn, studio["id"], t, arrivo, True, arrivata_dopo=True,
                                            dopo_il_genitore=genitore)
            return None
        if studio["chiusura"] == "non_chiuso" and t < fine:
            errore = _condizioni_ok(conn, studio, t)
            if errore is None:
                _chiudi(conn, chi, corpo, studio, t, arrivo)
                if chiuso_adesso is not None:
                    _togli_avviso_non_chiuso(conn, studio["id"], studio["figlio_id"], chiuso_adesso)
                    _avvisa_chiusura_del_figlio(conn, studio["id"], t, arrivo, in_ritardo)
                else:
                    # L'avviso di mezzanotte e' superato: si segna come letto per tutti.
                    _chiudi_avvisi(conn, studio["figlio_id"], "studio_non_chiuso",
                                   lambda p: p.get("studio_id") == studio["id"])
                    _avvisa_chiusura_del_figlio(conn, studio["id"], t, arrivo, True, arrivata_dopo=True)
                return None
            if chiuso_adesso is not None:
                return {**errore, "studio": formatta(conn, studio, arrivo)}
        return {"errore": "gia_chiuso", "studio": formatta(conn, studio, arrivo)}
    errore = _condizioni_ok(conn, studio, t)
    if errore is not None:
        return {**errore, "studio": formatta(conn, studio, arrivo)}
    _chiudi(conn, chi, corpo, studio, t, arrivo)
    _avvisa_chiusura_del_figlio(conn, studio["id"], t, arrivo, in_ritardo)
    return None


def _condizioni_ok(conn, studio: sqlite3.Row, t: datetime) -> dict | None:
    """Le condizioni di chiusura a T: dopo chiudibile_dal (409 troppo_presto) e coi
    minuti di attivita' fino a T (409 attivita_insufficiente). None se ci sono."""
    conta_dal, chiudibile_dal, minimi = condizioni(studio, _partenze_dello_studio(conn, studio["id"]), t)
    if chiudibile_dal is not None and t < chiudibile_dal:
        return {"errore": "troppo_presto", "chiudibile_dal": clock.iso(chiudibile_dal)}
    minuti = minuti_di_attivita(conn, studio, t)
    if minuti < minimi:
        return {"errore": "attivita_insufficiente", "minuti": minuti, "minimi": minimi}
    return None


def _chiudi(conn, chi, corpo, studio: sqlite3.Row, t: datetime, arrivo: datetime) -> None:
    """Scrive la chiusura del figlio a T (minuti congelati), e rielabora le partenze dello
    Studio cadute dopo T: per ognuna nasce lo Studio automatico di quel giorno, coi tratti
    che finiscono dopo T."""
    minuti = minuti_di_attivita(conn, studio, t)
    dopo_t = [p for p in _partenze_dello_studio(conn, studio["id"]) if p["inizio_ts"] > clock.iso(t)]
    conn.execute(
        "UPDATE studio_svolte SET fine_ts = ?, chiusura = 'figlio', chiusa_dispositivo_id = ?, chiusa_genitore_id = NULL,"
        " dichiarazione = ?, dichiarazione_ts = ?, minuti_alla_chiusura = ?, chiave_chiusura = ? WHERE id = ?",
        (clock.iso(t), chi.dispositivo_id, corpo.dichiarazione, clock.iso(t), minuti, corpo.chiave, studio["id"]),
    )
    elenco = versioni(conn, studio["figlio_id"])
    for partenza in dopo_t:
        crea_automatico(
            conn, studio["figlio_id"],
            {"giorno": partenza["giorno"], "inizio_ts": partenza["inizio_ts"],
             "chiudibile_dal": partenza["chiudibile_dal"], "minuti_minimi": partenza["minuti_minimi"]},
            arrivo, elenco, tratti_da=(studio["id"], t),
        )


def chiudi_dal_genitore(conn, chi, studio: sqlite3.Row, motivo: str, ora: datetime) -> None:
    """Il genitore chiude lo Studio senza condizioni, adesso, con un motivo (gia' chiuso:
    409 gia_chiuso). Un tratto in corso non si tocca: il suo esito vale quando arriva,
    tagliato alla chiusura. Avvisi al figlio e agli altri genitori (gia' letto per chi
    chiude)."""
    if studio["fine_ts"] is not None:
        raise HTTPException(status_code=409, detail={"errore": "gia_chiuso", "studio": formatta(conn, studio, ora)})
    ts = clock.iso(ora.replace(microsecond=0))
    minuti = minuti_di_attivita(conn, studio, ora)
    conn.execute(
        "UPDATE studio_svolte SET fine_ts = ?, chiusura = 'genitore', chiusa_genitore_id = ?, motivo = ?,"
        " minuti_alla_chiusura = ? WHERE id = ? AND fine_ts IS NULL",
        (ts, chi.genitore_id, motivo, minuti, studio["id"]),
    )
    genitore = Firme(conn).di(chi.genitore_id)
    vista = formatta(conn, _riga(conn, studio["id"]), ora)
    messaggio = f"{genitore['nome']} ha chiuso lo Studio: {motivo}"
    payload = _payload_chiuso(vista)
    accoda_notifica(conn, "studio_chiuso", messaggio, payload, ts, destinatario="figlio",
                    figlio_id=studio["figlio_id"], dispositivo_id=None)
    notifica_id = accoda_notifica(conn, "studio_chiuso", messaggio, payload, ts, destinatario="genitore",
                                  figlio_id=studio["figlio_id"], dispositivo_id=None)
    conn.execute(
        "INSERT OR IGNORE INTO notifiche_lette_genitori (notifica_id, genitore_id, ts_server) VALUES (?, ?, ?)",
        (notifica_id, chi.genitore_id, ts),
    )
