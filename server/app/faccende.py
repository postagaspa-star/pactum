"""(v3.6) Le faccende (contratto-api.md, "v3.6 — la famiglia con piu' genitori e le
faccende"). Un genitore da' al figlio delle faccende di casa; da `blocco_da`, e finche'
il figlio non le ha fatte TUTTE mandando una foto per ognuna, i suoi dispositivi sono
bloccati (il blocco lo fanno le app: il server dice soltanto se c'e').

Qui vivono le letture condivise da patto, finestra, famiglia e sessioni (le faccende di
un figlio, il blocco, i conteggi), le foto e la loro pulizia:

- una foto e' un JPEG fino a 4 MB. Prima di salvarla il server toglie i dati nascosti
  percorrendo i segmenti del file, in Python e senza librerie, con una lista di quelli
  AMMESSI (solo quelli che servono a mostrare l'immagine): tutto il resto si butta,
  anche tra le scansioni di un JPEG progressivo, e cosi' tutto quello che viene dopo la
  fine dell'immagine (FF D9: una seconda immagine, il video di una foto in movimento).
  Un file che non si riesce a percorrere, o che non ha un'immagine vera, non e' una foto;
- le foto stanno in `foto/` accanto al database (`<id della faccenda>.jpg`), mai nel
  database: la copia notturna non le comprende. Si scrivono in un file temporaneo e
  poi si rinominano, cosi' una foto a meta' non esiste mai col nome vero;
- all'avvio e una volta al giorno (insieme alla copia notturna, copie.py) si cancellano
  le foto arrivate da piu' di 30 giorni e i file senza una faccenda. `foto_ts` resta:
  "la foto e' arrivata quel giorno" e' storia, il file no;
- la storia di ogni faccenda (data, foto arrivata, bocciata, annullata; dalla v3.9
  anche modificata, coi cambi, e confermata) sta in faccende_storia, che si scrive solo
  aggiungendo: una bocciatura non cancella la foto di prima dalla storia, e una seconda
  bocciatura non cancella la prima;
- (v3.9) la ricerca nello storico per titolo, senza maiuscole, minuscole e accenti
  (`piega`, registrata come funzione di SQLite sulla connessione che cerca)."""

import json
import logging
import os
import re
import secrets
import sqlite3
import time
import unicodedata
from datetime import datetime, timedelta

from fastapi import HTTPException

from . import clock
from .controllo_corpo import INTERO_MASSIMO, INTERO_MINIMO
from .genitori import Firme

log = logging.getLogger("uvicorn.error")

FOTO_MASSIMA_BYTE = 4 * 1024 * 1024
GIORNI_FOTO = 30  # le foto si tengono 30 giorni
GIORNI_CHIUSE = 30  # GET /api/faccende: le chiuse degli ultimi 30 giorni
ORE_BOCCIATURA = 24  # una foto si boccia entro 24 ore
GIORNI_AVANTI_BLOCCO = 7  # blocco_da al massimo 7 giorni avanti
DA_FARE_MASSIME = 20  # per figlio: oltre, 409 troppe_faccende
RISULTATI_RICERCA = 50  # (v3.9) GET /api/faccende?cerca=: al massimo 50, poi `altre`

NON_TROVATA = "faccenda non trovata"
FOTO_NON_TROVATA = "foto non trovata"

# I file della cartella delle foto: <id>.jpg; mentre si scrive <id>.jpg.<caso>.parziale,
# e la foto appena bocciata, finche' la bocciatura non e' scritta, <id>.jpg.<caso>.bocciata.
_NOME_FOTO = re.compile(r"([1-9]\d{0,17})\.jpg")
_SUFFISSO_PARZIALE = ".parziale"
_SUFFISSO_BOCCIATA = ".bocciata"

# (v3.6) Le app che conoscono le faccende: dalla 0.13. Su un dispositivo piu' vecchio il
# blocco non c'e' (contratto, "Compatibilita'"), e il server non gli risponde coi codici
# del blocco che non conosce.
VERSIONE_FACCENDE = (0, 13)
# (v4.0) Le app e i programmi del computer che conoscono lo Studio: dalla 0.18.
VERSIONE_STUDIO = (0, 18)
_VERSIONE = re.compile(r"\s*(\d+)\.(\d+)")
# Un file temporaneo, o una foto senza faccenda, si toglie solo se ha piu' di un'ora:
# uno piu' giovane puo' essere una consegna che sta arrivando proprio adesso (il file
# si rinomina prima che la sua riga sia scritta).
ETA_MINIMA_ORFANI_SECONDI = 3600


# --- le foto ---

class FotoNonValida(ValueError):
    """Il file non e' un JPEG che si riesce a percorrere."""


_SOI, _EOI, _SOS = 0xD8, 0xD9, 0xDA
# SOF0-SOF15 tranne DHT (C4), JPG (C8) e DAC (CC): l'intestazione dell'immagine.
_INIZIO_IMMAGINE = set(range(0xC0, 0xD0)) - {0xC4, 0xC8, 0xCC}
# Le tabelle per decodificare: DHT (Huffman), DQT (quantizzazione), DRI (restart).
_TABELLE = {0xC4, 0xDB, 0xDD}
# Gli unici APPn che restano, riconosciuti dalla firma all'inizio: APP0 JFIF (come
# leggere l'immagine), APP2 ICC_PROFILE (i colori), APP14 Adobe (serve alla decodifica
# dei JPEG a 3 e 4 componenti). Tutti gli altri APPn (EXIF con la posizione, XMP, IPTC,
# MPF delle seconde immagini, ...), i commenti e i JPGn si buttano.
_APP_AMMESSI = {0xE0: b"JFIF\x00", 0xE2: b"ICC_PROFILE\x00", 0xEE: b"Adobe"}
# Marcatori senza lunghezza fuori dai dati compressi (TEM, RST0-RST7): si buttano.
_SENZA_LUNGHEZZA = {0x01, *range(0xD0, 0xD8)}
# Dentro i dati compressi di una scansione FF 00 e FF D0-D7 sono dati; il primo FF (con
# gli eventuali FF di riempimento) seguito da qualsiasi altro byte e' un marcatore.
_MARCATORE_NEI_DATI = re.compile(rb"\xff+[^\x00\xd0-\xd7\xff]")
# Contro i file costruiti apposta (tanti segmenti piccoli): un JPEG vero ne ha una
# decina prima della prima scansione e qualche decina in tutto (un progressivo).
SEGMENTI_PRIMA_DELLA_SCANSIONE = 64
SEGMENTI_MASSIMI = 256


def _ammesso(marcatore: int, segmento: bytes) -> bool:
    """Un segmento che resta nella foto pulita. `segmento` comincia coi due byte della
    lunghezza."""
    if marcatore in _INIZIO_IMMAGINE or marcatore in _TABELLE or marcatore == _SOS:
        return True
    firma = _APP_AMMESSI.get(marcatore)
    return firma is not None and segmento[2:].startswith(firma)


def _controlla_intestazione(segmento: bytes) -> None:
    """SOF: precisione, altezza, larghezza, numero di componenti (1-4) e 3 byte per
    componente. Un'immagine alta o larga zero non e' un'immagine."""
    componenti = segmento[7] if len(segmento) > 7 else 0
    if not 1 <= componenti <= 4 or len(segmento) != 8 + 3 * componenti:
        raise FotoNonValida("intestazione dell'immagine sbagliata")
    if int.from_bytes(segmento[3:5], "big") == 0 or int.from_bytes(segmento[5:7], "big") == 0:
        raise FotoNonValida("immagine senza altezza o senza larghezza")


def _controlla_scansione(segmento: bytes) -> None:
    """SOS: numero di componenti (1-4) e 2 byte per componente, piu' 3 byte finali."""
    componenti = segmento[2] if len(segmento) > 2 else 0
    if not 1 <= componenti <= 4 or len(segmento) != 6 + 2 * componenti:
        raise FotoNonValida("intestazione della scansione sbagliata")


def pulisci_jpeg(dati: bytes) -> bytes:
    """La foto senza i dati nascosti. Percorre i segmenti e tiene SOLO quelli ammessi
    (SOI, SOF, DHT, DQT, DRI, SOS, APP0 JFIF, APP2 ICC_PROFILE, APP14 Adobe) e i dati
    compressi di ogni scansione, copiati com'erano. Tra una scansione e l'altra (JPEG
    progressivo) vale la stessa lista. Al primo FF D9 (fine dell'immagine) si ferma:
    quello che segue (una seconda immagine, un video) si butta.

    FotoNonValida se il file non comincia con FF D8 FF, se un segmento esce dal file, se
    l'intestazione dell'immagine o di una scansione non torna, se una scansione e' senza
    dati, se manca FF D9 o se i segmenti sono troppi."""
    if not dati.startswith(b"\xff\xd8\xff"):
        raise FotoNonValida("non comincia con FF D8 FF")
    pulita = bytearray(b"\xff\xd8")
    i, fine = 2, len(dati)
    intestazione, scansioni, segmenti = False, 0, 0
    while True:
        if i >= fine or dati[i] != 0xFF:
            raise FotoNonValida("atteso un marcatore")
        while i < fine and dati[i] == 0xFF:  # i byte FF di riempimento prima del marcatore
            i += 1
        if i >= fine:
            raise FotoNonValida("il file finisce dentro un marcatore")
        marcatore = dati[i]
        i += 1
        if marcatore == _EOI:
            if scansioni == 0:
                raise FotoNonValida("il file finisce prima dell'immagine")
            pulita += b"\xff\xd9"
            return bytes(pulita)  # quello che segue la fine dell'immagine si butta
        if marcatore in (0x00, _SOI):
            raise FotoNonValida("marcatore fuori posto")
        if marcatore in _SENZA_LUNGHEZZA:
            continue
        segmenti += 1
        prima_della_scansione = scansioni == 0 and marcatore != _SOS
        if segmenti > (SEGMENTI_PRIMA_DELLA_SCANSIONE if prima_della_scansione else SEGMENTI_MASSIMI):
            raise FotoNonValida("troppi segmenti")
        if i + 2 > fine:
            raise FotoNonValida("il file finisce dentro un segmento")
        lunghezza = int.from_bytes(dati[i:i + 2], "big")
        if lunghezza < 2 or i + lunghezza > fine:
            raise FotoNonValida("un segmento esce dal file")
        segmento = dati[i:i + lunghezza]
        i += lunghezza
        if not _ammesso(marcatore, segmento):
            continue
        if marcatore in _INIZIO_IMMAGINE:
            _controlla_intestazione(segmento)
            intestazione = True
        if marcatore == _SOS:
            if not intestazione:
                raise FotoNonValida("una scansione prima dell'intestazione")
            _controlla_scansione(segmento)
        pulita += bytes((0xFF, marcatore)) + segmento
        if marcatore == _SOS:
            # I dati compressi, fino al primo marcatore vero (FF 00 e RST sono dati).
            trovato = _MARCATORE_NEI_DATI.search(dati, i)
            if trovato is None:
                raise FotoNonValida("manca la fine dell'immagine (FF D9)")
            if trovato.start() == i:
                raise FotoNonValida("una scansione senza dati")
            pulita += dati[i:trovato.start()]
            i = trovato.start()
            scansioni += 1


def cartella_foto(db_path: str) -> str:
    """<cartella del database>/foto: nel volume del NAS, accanto al registro."""
    return os.path.join(os.path.dirname(os.path.abspath(db_path)), "foto")


def percorso_foto(cartella: str, faccenda_id: int) -> str:
    return os.path.join(cartella, f"{faccenda_id}.jpg")


def scrivi_temporanea(cartella: str, faccenda_id: int, foto: bytes) -> str:
    """La foto in un file temporaneo accanto a quello vero, forzata su disco: chi
    chiama la rinomina (os.replace, atomico) solo se la faccenda la accetta."""
    os.makedirs(cartella, exist_ok=True)
    temporanea = f"{percorso_foto(cartella, faccenda_id)}.{secrets.token_hex(6)}{_SUFFISSO_PARZIALE}"
    with open(temporanea, "xb") as file:
        file.write(foto)
        file.flush()
        os.fsync(file.fileno())
    return temporanea


def togli(percorso: str) -> None:
    try:
        os.remove(percorso)
    except FileNotFoundError:
        pass
    except OSError as errore:
        log.warning("non riesco a togliere %s: %s (ci riprova la pulizia)", percorso, errore)


def leggi_foto(cartella: str, riga: sqlite3.Row) -> bytes | None:
    """I byte della foto di una faccenda, se c'e': arrivata (foto_ts) e ancora sul disco.
    Una faccenda bocciata o tornata da fare non ha foto, anche se un file e' rimasto."""
    if riga["foto_ts"] is None:
        return None
    try:
        with open(percorso_foto(cartella, riga["id"]), "rb") as file:
            return file.read()
    except FileNotFoundError:
        return None


def metti_da_parte(cartella: str, faccenda_id: int) -> str | None:
    """La foto di una faccenda appena bocciata, spostata (dentro il lock della
    bocciatura) a un nome suo: una foto nuova del figlio, che arriva solo dopo la
    bocciatura, prende il nome vero e non viene mai tolta al posto della vecchia. Chi
    chiama la toglie dopo il commit, o la rimette a posto se la bocciatura non si scrive.
    None se il file non c'era."""
    vero = percorso_foto(cartella, faccenda_id)
    da_parte = f"{vero}.{secrets.token_hex(6)}{_SUFFISSO_BOCCIATA}"
    try:
        os.replace(vero, da_parte)
    except FileNotFoundError:
        return None
    return da_parte


def versione_almeno(versione_app: str | None, minima: tuple[int, int]) -> bool:
    """L'app ha almeno la versione `minima` (maggiore, minore). Una versione che manca o
    non si legge e' di un'app vecchia: quelle nuove la dichiarano sempre."""
    trovata = _VERSIONE.match(versione_app or "")
    return trovata is not None and (int(trovata.group(1)), int(trovata.group(2))) >= minima


def conosce_le_faccende(versione_app: str | None) -> bool:
    """Un'app dalla 0.13 in su. Una versione che manca o non si legge e' di un'app
    vecchia: quelle che conoscono le faccende la dichiarano sempre."""
    return versione_almeno(versione_app, VERSIONE_FACCENDE)


def conosce_lo_studio(versione_app: str | None) -> bool:
    """(v4.0) Un'app (o un programma del computer) dalla 0.18 in su: conosce lo Studio."""
    return versione_almeno(versione_app, VERSIONE_STUDIO)


def ha_telefono_con_lo_studio(conn: sqlite3.Connection, figlio_id: int) -> bool:
    """(v4.0) Il figlio ha almeno un telefono non revocato con l'app dalla 0.18 (letta
    dall'ultima versione dichiarata col battito): senza, nessuno potrebbe chiudere uno
    Studio, e lo Studio non parte."""
    return any(
        conosce_lo_studio(r["versione_app"])
        for r in conn.execute(
            "SELECT versione_app FROM dispositivi WHERE figlio_id = ? AND tipo = 'telefono'"
            " AND revocato_ts IS NULL",
            (figlio_id,),
        ).fetchall()
    )


def studio_aperto(conn: sqlite3.Connection, figlio_id: int) -> sqlite3.Row | None:
    """(v4.0) Lo Studio aperto del figlio (al massimo uno, garantito dall'indice unico)."""
    return conn.execute(
        "SELECT * FROM studio_svolte WHERE figlio_id = ? AND fine_ts IS NULL", (figlio_id,)
    ).fetchone()


def studio_in_corso(conn: sqlite3.Connection, figlio_id: int) -> sqlite3.Row | None:
    """(v4.0) Lo Studio in corso come lo vedono i dispositivi: quello aperto, ma solo se
    il figlio ha un telefono dalla 0.18 (contratto: senza, `studio.in_corso` e' null e
    nessun dispositivo parte da solo). Chi lo legge ha gia' elaborato partenze e
    mezzanotti (studio.valuta)."""
    aperto = studio_aperto(conn, figlio_id)
    if aperto is None or not ha_telefono_con_lo_studio(conn, figlio_id):
        return None
    return aperto


def registra_storia(
    conn: sqlite3.Connection,
    faccenda_id: int,
    tipo: str,
    ts: str,
    genitore_id: int | None = None,
    nota: str | None = None,
    cambi: dict | None = None,
) -> None:
    """Una riga in piu' nella storia della faccenda: 'data', 'foto', 'bocciata' o
    'annullata'; (v3.9) 'modificata' (coi `cambi`) e 'confermata'. Solo aggiunte: la
    tabella non si cambia e non si cancella."""
    conn.execute(
        "INSERT INTO faccende_storia (faccenda_id, tipo, ts, genitore_id, nota, cambi)"
        " VALUES (?, ?, ?, ?, ?, ?)",
        (faccenda_id, tipo, ts, genitore_id, nota,
         json.dumps(cambi, ensure_ascii=False) if cambi is not None else None),
    )


def storie(conn: sqlite3.Connection, ids: list[int], firme: Firme) -> dict[int, list]:
    """La storia di ogni faccenda di `ids`, dalla piu' vecchia, letta in una volta sola:
    {"tipo", "ts"}, piu' "genitore" dove ha fatto qualcosa un genitore, "nota" sulle
    bocciature e (v3.9) "cambi" sulle modifiche."""
    storia: dict[int, list] = {i: [] for i in ids}
    if not ids:
        return storia
    segnaposto = ",".join("?" * len(ids))
    for r in conn.execute(
        f"SELECT * FROM faccende_storia WHERE faccenda_id IN ({segnaposto}) ORDER BY id", ids
    ).fetchall():
        voce = {"tipo": r["tipo"], "ts": r["ts"]}
        if r["tipo"] != "foto":
            voce["genitore"] = firme.di(r["genitore_id"])
        if r["tipo"] == "bocciata":
            voce["nota"] = r["nota"]
        if r["tipo"] == "modificata":
            voce["cambi"] = json.loads(r["cambi"])
        storia[r["faccenda_id"]].append(voce)
    return storia


def _foto_presente(cartella: str, riga: sqlite3.Row) -> bool:
    return riga["foto_ts"] is not None and os.path.isfile(percorso_foto(cartella, riga["id"]))


# --- (v4.0) i lavori da approvare ---

# Dopo ogni ts_server vero: un database senza la riga `faccende_approvazione_dal` (mai,
# dopo init_db) non ha lavori da approvare.
_MAI = "9999-12-31T23:59:59+00:00"


def approvazione_dal(conn: sqlite3.Connection) -> str:
    """(v4.0) Da quando le foto chiedono l'approvazione di un genitore: la riga
    `faccende_approvazione_dal` di `patto`, scritta una volta al primo avvio della v4.0.
    Le foto arrivate prima hanno gia' sbloccato (regola v3.6-v3.9) e non tornano a
    bloccare."""
    riga = conn.execute(
        "SELECT valore FROM patto WHERE chiave = 'faccende_approvazione_dal'"
    ).fetchone()
    return riga[0] if riga is not None else _MAI


def da_approvare(riga: sqlite3.Row, dal: str) -> bool:
    """(v4.0) Un lavoro fatto la cui foto (arrivata dalla v4.0) nessun genitore ha
    ancora approvato: e' ancora aperto e blocca. Non e' salvato: si ricava da foto_ts,
    confermata_ts e `faccende_approvazione_dal`."""
    return (
        riga["stato"] == "fatta"
        and riga["confermata_ts"] is None
        and riga["foto_ts"] is not None
        and riga["foto_ts"] >= dal
    )


# La condizione SQL "lavoro aperto": da fare, oppure fatto e da approvare. Vuole come
# parametro `faccende_approvazione_dal`.
APERTA = "(stato = 'da_fare' OR (stato = 'fatta' AND confermata_ts IS NULL AND foto_ts >= ?))"


# --- le forme ---

def _ultima_bocciatura(riga: sqlite3.Row, firme: Firme) -> dict | None:
    if riga["bocciata_ts"] is None:
        return None
    return {
        "ts": riga["bocciata_ts"],
        "nota": riga["bocciata_nota"],
        "da": firme.di(riga["bocciata_genitore_id"]),
    }


def formatta(
    riga: sqlite3.Row, firme: Firme, cartella: str, storia: list | None = None, dal: str = _MAI
) -> dict:
    """La faccenda come la vedono le app. `storia`: quella gia' letta da chi formatta un
    elenco (storie); senza, la si legge qui. (v4.0) `dal`: `faccende_approvazione_dal`,
    per `da_approvare`."""
    if storia is None:
        storia = []
    return {
        "id": riga["id"],
        "figlio_id": riga["figlio_id"],
        "titolo": riga["titolo"],
        "nota": riga["nota"],
        "stato": riga["stato"],
        "blocco_da": riga["blocco_da"],
        "creata_ts": riga["creata_ts"],
        "creata_da": firme.di(riga["creata_genitore_id"]),
        "foto_ts": riga["foto_ts"],
        "foto": _foto_presente(cartella, riga),
        "bocciature": riga["bocciature"],
        "ultima_bocciatura": _ultima_bocciatura(riga, firme),
        "chiusa_ts": riga["chiusa_ts"],
        "annullata_da": firme.di(riga["annullata_genitore_id"]),
        # (v3.9) "svolto": la conferma di un genitore, null finche' nessuno conferma
        "confermata_ts": riga["confermata_ts"],
        "confermata_da": firme.di(riga["confermata_genitore_id"]),
        # (v4.0) fatta, con la foto dalla v4.0, e nessun genitore l'ha ancora approvata
        "da_approvare": da_approvare(riga, dal),
        "storia": storia,
    }


def faccenda_o_404(
    conn: sqlite3.Connection, faccenda_id: int, figlio_id: int | None = None
) -> sqlite3.Row:
    """La faccenda; 404 "faccenda non trovata" se non c'e' o (per un dispositivo) e' di
    un altro figlio. Un id oltre i 64 bit non e' mai una faccenda."""
    if not INTERO_MINIMO <= faccenda_id <= INTERO_MASSIMO:
        raise HTTPException(status_code=404, detail=NON_TROVATA)
    riga = conn.execute("SELECT * FROM faccende WHERE id = ?", (faccenda_id,)).fetchone()
    if riga is None or (figlio_id is not None and riga["figlio_id"] != figlio_id):
        raise HTTPException(status_code=404, detail=NON_TROVATA)
    return riga


# --- le letture condivise con patto, finestra, famiglia e sessioni ---

def faccende_del_figlio(
    conn: sqlite3.Connection, figlio_id: int, ora: datetime, firme: Firme, cartella: str
) -> list[dict]:
    """GET /api/faccende: tutte le da fare, piu' le fatte e le annullate chiuse negli
    ultimi 30 giorni; dalla piu' recente (creata_ts, poi id). (v4.0) Anche le fatte da
    approvare, sempre, come le da fare (finche' aspettano)."""
    dal = clock.iso(ora - timedelta(days=GIORNI_CHIUSE))
    approvazione = approvazione_dal(conn)
    righe = conn.execute(
        f"SELECT * FROM faccende WHERE figlio_id = ? AND ({APERTA} OR chiusa_ts >= ?)"
        " ORDER BY creata_ts DESC, id DESC",
        (figlio_id, approvazione, dal),
    ).fetchall()
    storia = storie(conn, [r["id"] for r in righe], firme)
    return [formatta(r, firme, cartella, storia[r["id"]], approvazione) for r in righe]


# (v3.9) Le lettere che Unicode non scompone in base + segno: per la ricerca valgono la
# lettera semplice (gia' minuscole: `piega` le cerca dopo casefold, che porta Ł a ł).
_LETTERE_SENZA_SCOMPOSIZIONE = str.maketrans(
    {"ł": "l", "ø": "o", "đ": "d", "æ": "ae", "œ": "oe", "þ": "th"}
)


def piega(testo: str | None) -> str:
    """(v3.9) Un testo come lo confronta la ricerca: senza maiuscole (casefold: anche "ß"
    e' "ss") e senza accenti (le lettere scomposte in base + segni, e i segni si buttano;
    le poche che non si scompongono, come ł, ø, æ, con una tabella). "Perché" e "PERCHE"
    diventano tutti e due "perche". Un testo fatto solo di segni diventa vuoto."""
    scomposto = unicodedata.normalize("NFKD", (testo or "").casefold())
    senza_segni = "".join(c for c in scomposto if not unicodedata.combining(c))
    return senza_segni.translate(_LETTERE_SENZA_SCOMPOSIZIONE)


def cerca(
    conn: sqlite3.Connection, figlio_id: int, testo: str, firme: Firme, cartella: str
) -> tuple[list[dict], bool]:
    """(v3.9) GET /api/faccende?cerca=: TUTTE le faccende del figlio (qualunque data e
    stato) il cui titolo contiene `testo`, senza maiuscole, minuscole e accenti; dalla
    piu' recente come l'elenco (creata_ts, poi id), al massimo 50. Il secondo valore dice
    se ce n'erano di piu'. Il confronto lo fa SQLite con `piega`, registrata su questa
    connessione: si leggono solo le righe che servono, non tutta la storia.

    Un testo che piegato non resta niente (solo segni, come U+0301 da solo) non trova
    niente: in SQLite instr(x, '') e' 1, cioe' "tutto"."""
    piegato = piega(testo)
    if not piegato:
        return [], False
    conn.create_function("pactum_piega", 1, piega, deterministic=True)
    righe = conn.execute(
        "SELECT * FROM faccende WHERE figlio_id = ? AND instr(pactum_piega(titolo), ?) > 0"
        " ORDER BY creata_ts DESC, id DESC LIMIT ?",
        (figlio_id, piegato, RISULTATI_RICERCA + 1),
    ).fetchall()
    altre = len(righe) > RISULTATI_RICERCA
    righe = righe[:RISULTATI_RICERCA]
    storia = storie(conn, [r["id"] for r in righe], firme)
    approvazione = approvazione_dal(conn)
    return [formatta(r, firme, cartella, storia[r["id"]], approvazione) for r in righe], altre


def una(conn: sqlite3.Connection, faccenda_id: int, firme: Firme, cartella: str) -> dict:
    """Una faccenda sola, con la sua storia: la risposta delle scritture."""
    riga = faccenda_o_404(conn, faccenda_id)
    return formatta(
        riga, firme, cartella, storie(conn, [faccenda_id], firme)[faccenda_id], approvazione_dal(conn)
    )


def aperte(conn: sqlite3.Connection, figlio_id: int) -> list[sqlite3.Row]:
    """(v4.0) I lavori aperti del figlio (da fare e da approvare), dal piu' vecchio."""
    return conn.execute(
        f"SELECT * FROM faccende WHERE figlio_id = ? AND {APERTA} ORDER BY creata_ts, id",
        (figlio_id, approvazione_dal(conn)),
    ).fetchall()


def blocco_attivo(conn: sqlite3.Connection, figlio_id: int, ora: datetime) -> bool:
    """C'e' almeno un lavoro aperto (v4.0: da fare, o da approvare) il cui blocco_da e'
    gia' passato. Non tiene conto dello Studio: dice solo che il blocco e' dovuto."""
    return conn.execute(
        f"SELECT 1 FROM faccende WHERE figlio_id = ? AND {APERTA} AND blocco_da <= ? LIMIT 1",
        (figlio_id, approvazione_dal(conn), clock.iso(ora)),
    ).fetchone() is not None


def blocco_attivo_a(conn: sqlite3.Connection, figlio_id: int, istante: datetime) -> bool:
    """(v4.0) Il blocco era attivo a `istante` (nel passato), ricostruito dai lavori come
    sono adesso: un lavoro gia' dato a quell'istante, col blocco_da passato e ancora aperto
    allora (da fare; da approvare fino all'approvazione; con una foto di prima della v4.0
    fino all'arrivo della foto; annullato fino all'annullamento). Serve all'avvio a mano
    consegnato in ritardo e al computer che sparisce. Una bocciatura porta il blocco_da
    all'ora della bocciatura: il tempo prima di lei non si ricostruisce (li' il lavoro
    era fatto, e il suo blocco gia' finito o ancora da approvare)."""
    t = clock.iso(istante)
    dal = approvazione_dal(conn)
    return conn.execute(
        "SELECT 1 FROM faccende WHERE figlio_id = ? AND creata_ts <= ? AND blocco_da <= ? AND ("
        " stato = 'da_fare'"
        " OR (stato = 'fatta' AND foto_ts >= ? AND (confermata_ts IS NULL OR confermata_ts > ?))"
        " OR (stato = 'fatta' AND foto_ts < ? AND foto_ts > ?)"
        " OR (stato = 'annullata' AND chiusa_ts > ?)) LIMIT 1",
        (figlio_id, t, t, dal, t, dal, t, t),
    ).fetchone() is not None


def blocco(conn: sqlite3.Connection, figlio_id: int, ora: datetime, firme: Firme) -> dict:
    """GET /api/faccende/blocco, la risposta piccola che i dispositivi chiedono spesso:
    `attivo` se almeno un lavoro aperto blocca gia', `dal` il piu' vecchio di quei
    blocco_da; se non e' attivo, `prossimo` il blocco_da piu' vicino nel futuro (il
    dispositivo lo usa per partire da solo anche senza rete); e tutti i lavori aperti,
    dal piu' vecchio.

    (v4.0) Aperti sono i da fare e i da approvare (fatti, con la foto dalla v4.0, che
    nessun genitore ha ancora approvato): restano in `da_fare` (le app vecchie lo leggono
    cosi') con in piu' `stato` e `foto_ts`. `attivo` non tiene conto dello Studio (un'app
    vecchia resta piu' stretta, mai piu' larga); `rimandato` e' vero quando il blocco e'
    dovuto ma il figlio e' in Studio (il blocco aspetta la fine dello Studio); `studio`
    dice quale Studio e' in corso."""
    adesso = clock.iso(ora)
    righe = aperte(conn, figlio_id)
    bloccano = [r["blocco_da"] for r in righe if r["blocco_da"] <= adesso]
    future = [r["blocco_da"] for r in righe if r["blocco_da"] > adesso]
    in_corso = studio_in_corso(conn, figlio_id)
    return {
        "attivo": bool(bloccano),
        "dal": min(bloccano) if bloccano else None,
        "prossimo": min(future) if future and not bloccano else None,
        "rimandato": bool(bloccano) and in_corso is not None,
        "studio": {
            "in_corso": in_corso is not None,
            "id": in_corso["id"] if in_corso is not None else None,
            "inizio_ts": in_corso["inizio_ts"] if in_corso is not None else None,
        },
        "da_fare": [
            {
                "id": r["id"],
                "titolo": r["titolo"],
                "nota": r["nota"],
                "blocco_da": r["blocco_da"],
                "creata_da": firme.di(r["creata_genitore_id"]),
                "bocciature": r["bocciature"],
                "ultima_bocciatura": _ultima_bocciatura(r, firme),
                "stato": r["stato"],  # (v4.0) da_fare | fatta (da approvare)
                "foto_ts": r["foto_ts"],  # (v4.0) null per un lavoro da fare
            }
            for r in righe
        ],
    }


def quante_da_fare(conn: sqlite3.Connection, figlio_id: int) -> int:
    return conn.execute(
        "SELECT COUNT(*) AS n FROM faccende WHERE figlio_id = ? AND stato = 'da_fare'", (figlio_id,)
    ).fetchone()["n"]


def quante_da_approvare(conn: sqlite3.Connection, figlio_id: int) -> int:
    """(v4.0) I lavori fatti che aspettano l'approvazione di un genitore."""
    return conn.execute(
        "SELECT COUNT(*) AS n FROM faccende WHERE figlio_id = ? AND stato = 'fatta'"
        " AND confermata_ts IS NULL AND foto_ts >= ?",
        (figlio_id, approvazione_dal(conn)),
    ).fetchone()["n"]


def giro_per_nuove(conn: sqlite3.Connection, figlio_id: int) -> int:
    """Il giro delle faccende che tornano o diventano da fare: quello dei lavori aperti
    che ci sono gia' (sono sempre di uno stesso giro), oppure uno nuovo se il figlio non
    ne aveva nessuno. (v4.0) Il giro resta aperto finche' c'e' un lavoro aperto, anche
    solo da approvare: una bocciatura dopo le foto resta nello stesso giro."""
    aperto = conn.execute(
        f"SELECT giro FROM faccende WHERE figlio_id = ? AND {APERTA} ORDER BY id LIMIT 1",
        (figlio_id, approvazione_dal(conn)),
    ).fetchone()
    if aperto is not None:
        return aperto["giro"]
    ultimo = conn.execute(
        "SELECT COALESCE(MAX(giro), 0) AS g FROM faccende WHERE figlio_id = ?", (figlio_id,)
    ).fetchone()["g"]
    return ultimo + 1


# --- la pulizia ---

def pulisci_foto(db_path: str, ora: datetime) -> int:
    """Toglie le foto arrivate da piu' di 30 giorni e i file senza una faccenda (una
    foto bocciata rimasta, un file temporaneo di una consegna interrotta, qualsiasi
    altro file). Le sottocartelle non si toccano. Restituisce quanti file ha tolto. Non
    solleva per un file che non si riesce a togliere: lo dice il log e ci riprova la
    volta dopo.

    (v4.0) La foto di un lavoro da approvare non si cancella (nessuno approva una foto
    che non puo' piu' guardare); quella di un lavoro approvato 30 giorni dopo
    l'approvazione (`confermata_ts`); quelle di prima della v4.0 30 giorni dopo l'arrivo,
    come prima."""
    cartella = cartella_foto(db_path)
    if not os.path.isdir(cartella):
        return 0
    limite = clock.iso(ora - timedelta(days=GIORNI_FOTO))
    conn = sqlite3.connect(db_path, timeout=30)
    conn.row_factory = sqlite3.Row
    try:
        dal = approvazione_dal(conn)
        # id della faccenda -> l'ora da cui si contano i 30 giorni; None = non si cancella
        foto_ts = {}
        for r in conn.execute(
            "SELECT id, foto_ts, confermata_ts FROM faccende WHERE foto_ts IS NOT NULL"
        ).fetchall():
            if r["foto_ts"] < dal:
                foto_ts[r["id"]] = r["foto_ts"]
            else:
                foto_ts[r["id"]] = r["confermata_ts"]
    finally:
        conn.close()
    adesso = time.time()
    tolti = 0
    with os.scandir(cartella) as voci:
        for voce in list(voci):
            if not voce.is_file(follow_symlinks=False):
                continue
            nome = _NOME_FOTO.fullmatch(voce.name)
            faccenda = int(nome.group(1)) if nome else None
            if faccenda in foto_ts and foto_ts[faccenda] is None:
                continue  # (v4.0) aspetta l'approvazione di un genitore: resta
            arrivata = foto_ts.get(faccenda)
            if arrivata is not None:
                da_togliere = arrivata < limite  # la foto di una faccenda: dopo 30 giorni
            else:
                try:
                    eta = adesso - voce.stat(follow_symlinks=False).st_mtime
                except OSError:
                    continue
                da_togliere = eta > ETA_MINIMA_ORFANI_SECONDI  # senza faccenda (o temporanea)
            if da_togliere:
                try:
                    os.remove(voce.path)
                    tolti += 1
                except OSError as errore:
                    log.warning("pulizia delle foto: non riesco a togliere %s: %s", voce.name, errore)
    if tolti:
        log.info("pulizia delle foto: tolti %d file (foto di piu' di %d giorni o senza faccenda)",
                 tolti, GIORNI_FOTO)
    return tolti
