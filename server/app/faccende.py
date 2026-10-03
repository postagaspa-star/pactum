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
- la storia di ogni faccenda (data, foto arrivata, bocciata, annullata) sta in
  faccende_storia, che si scrive solo aggiungendo: una bocciatura non cancella la foto
  di prima dalla storia, e una seconda bocciatura non cancella la prima."""

import logging
import os
import re
import secrets
import sqlite3
import time
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


def conosce_le_faccende(versione_app: str | None) -> bool:
    """Un'app dalla 0.13 in su. Una versione che manca o non si legge e' di un'app
    vecchia: quelle che conoscono le faccende la dichiarano sempre."""
    trovata = _VERSIONE.match(versione_app or "")
    return trovata is not None and (int(trovata.group(1)), int(trovata.group(2))) >= VERSIONE_FACCENDE


def registra_storia(
    conn: sqlite3.Connection,
    faccenda_id: int,
    tipo: str,
    ts: str,
    genitore_id: int | None = None,
    nota: str | None = None,
) -> None:
    """Una riga in piu' nella storia della faccenda: 'data', 'foto', 'bocciata' o
    'annullata'. Solo aggiunte: la tabella non si cambia e non si cancella."""
    conn.execute(
        "INSERT INTO faccende_storia (faccenda_id, tipo, ts, genitore_id, nota) VALUES (?, ?, ?, ?, ?)",
        (faccenda_id, tipo, ts, genitore_id, nota),
    )


def storie(conn: sqlite3.Connection, ids: list[int], firme: Firme) -> dict[int, list]:
    """La storia di ogni faccenda di `ids`, dalla piu' vecchia, letta in una volta sola:
    {"tipo", "ts"}, piu' "genitore" dove ha fatto qualcosa un genitore e "nota" sulle
    bocciature."""
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
        storia[r["faccenda_id"]].append(voce)
    return storia


def _foto_presente(cartella: str, riga: sqlite3.Row) -> bool:
    return riga["foto_ts"] is not None and os.path.isfile(percorso_foto(cartella, riga["id"]))


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
    riga: sqlite3.Row, firme: Firme, cartella: str, storia: list | None = None
) -> dict:
    """La faccenda come la vedono le app. `storia`: quella gia' letta da chi formatta un
    elenco (storie); senza, la si legge qui."""
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
    ultimi 30 giorni; dalla piu' recente (creata_ts, poi id)."""
    dal = clock.iso(ora - timedelta(days=GIORNI_CHIUSE))
    righe = conn.execute(
        "SELECT * FROM faccende WHERE figlio_id = ? AND (stato = 'da_fare' OR chiusa_ts >= ?)"
        " ORDER BY creata_ts DESC, id DESC",
        (figlio_id, dal),
    ).fetchall()
    storia = storie(conn, [r["id"] for r in righe], firme)
    return [formatta(r, firme, cartella, storia[r["id"]]) for r in righe]


def una(conn: sqlite3.Connection, faccenda_id: int, firme: Firme, cartella: str) -> dict:
    """Una faccenda sola, con la sua storia: la risposta delle scritture."""
    riga = faccenda_o_404(conn, faccenda_id)
    return formatta(riga, firme, cartella, storie(conn, [faccenda_id], firme)[faccenda_id])


def _da_fare(conn: sqlite3.Connection, figlio_id: int) -> list[sqlite3.Row]:
    """Le faccende da fare del figlio, dalla piu' vecchia."""
    return conn.execute(
        "SELECT * FROM faccende WHERE figlio_id = ? AND stato = 'da_fare' ORDER BY creata_ts, id",
        (figlio_id,),
    ).fetchall()


def blocco_attivo(conn: sqlite3.Connection, figlio_id: int, ora: datetime) -> bool:
    """C'e' almeno una faccenda da fare il cui blocco_da e' gia' passato."""
    return conn.execute(
        "SELECT 1 FROM faccende WHERE figlio_id = ? AND stato = 'da_fare' AND blocco_da <= ? LIMIT 1",
        (figlio_id, clock.iso(ora)),
    ).fetchone() is not None


def blocco(conn: sqlite3.Connection, figlio_id: int, ora: datetime, firme: Firme) -> dict:
    """GET /api/faccende/blocco, la risposta piccola che i dispositivi chiedono spesso:
    `attivo` se almeno una da fare blocca gia', `dal` il piu' vecchio di quei blocco_da;
    se non e' attivo, `prossimo` il blocco_da piu' vicino nel futuro (il dispositivo lo
    usa per partire da solo anche senza rete); e tutte le da fare, dalla piu' vecchia."""
    adesso = clock.iso(ora)
    righe = _da_fare(conn, figlio_id)
    bloccano = [r["blocco_da"] for r in righe if r["blocco_da"] <= adesso]
    future = [r["blocco_da"] for r in righe if r["blocco_da"] > adesso]
    return {
        "attivo": bool(bloccano),
        "dal": min(bloccano) if bloccano else None,
        "prossimo": min(future) if future and not bloccano else None,
        "da_fare": [
            {
                "id": r["id"],
                "titolo": r["titolo"],
                "nota": r["nota"],
                "blocco_da": r["blocco_da"],
                "creata_da": firme.di(r["creata_genitore_id"]),
                "bocciature": r["bocciature"],
                "ultima_bocciatura": _ultima_bocciatura(r, firme),
            }
            for r in righe
        ],
    }


def quante_da_fare(conn: sqlite3.Connection, figlio_id: int) -> int:
    return conn.execute(
        "SELECT COUNT(*) AS n FROM faccende WHERE figlio_id = ? AND stato = 'da_fare'", (figlio_id,)
    ).fetchone()["n"]


def giro_per_nuove(conn: sqlite3.Connection, figlio_id: int) -> int:
    """Il giro delle faccende che tornano o diventano da fare: quello delle da fare che
    ci sono gia' (sono sempre di uno stesso giro), oppure uno nuovo se il figlio non ne
    aveva nessuna."""
    aperto = conn.execute(
        "SELECT giro FROM faccende WHERE figlio_id = ? AND stato = 'da_fare' LIMIT 1", (figlio_id,)
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
    volta dopo."""
    cartella = cartella_foto(db_path)
    if not os.path.isdir(cartella):
        return 0
    limite = clock.iso(ora - timedelta(days=GIORNI_FOTO))
    conn = sqlite3.connect(db_path, timeout=30)
    conn.row_factory = sqlite3.Row
    try:
        foto_ts = {
            r["id"]: r["foto_ts"]
            for r in conn.execute("SELECT id, foto_ts FROM faccende WHERE foto_ts IS NOT NULL")
        }
    finally:
        conn.close()
    adesso = time.time()
    tolti = 0
    with os.scandir(cartella) as voci:
        for voce in list(voci):
            if not voce.is_file(follow_symlinks=False):
                continue
            nome = _NOME_FOTO.fullmatch(voce.name)
            arrivata = foto_ts.get(int(nome.group(1))) if nome else None
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
