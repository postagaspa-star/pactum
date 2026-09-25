"""(v3.2) Le copie del registro: una al giorno fatta dal server stesso, e il
ripristino di una copia all'avvio (contratto-api.md, "Copia notturna del registro").

Sul NAS (UGOS) non c'e' un programmatore di attivita': la copia la fa un compito
che gira dentro il server. Le copie vanno in PACTUM_BACKUP_DIR (in Docker /backup,
cioe' server/backup del NAS, che si vede dal gestore file); il database sta nel
volume, che dal NAS non si vede. Stesso modo prudente della copia prima della
migrazione v3 (db.py): VACUUM INTO in un file `.parziale`, fsync, e il nome vero
solo quando la copia e' completa, su disco e controllata."""

import asyncio
import logging
import os
import re
import shutil
import sqlite3
import threading
from datetime import date, datetime, timezone
from pathlib import Path
from typing import NoReturn

from . import clock, config
from .config import Settings

log = logging.getLogger("uvicorn.error")

ORA_COPIA = 3  # la copia del giorno si fa la prima volta dopo le 03:00 del fuso del patto
COPIE_DA_TENERE = 30
INTERVALLO_SECONDI = 60  # ogni quanto il compito guarda l'orologio

_NOME_COPIA = re.compile(r"pactum-(\d{8})\.db")
# I file che accompagnano un database SQLite: accanto a una copia rimessa al posto
# del registro la rovinerebbero (un -journal rimasto verrebbe "riapplicato").
_COMPAGNI_SQLITE = ("-wal", "-shm", "-journal")


def nome_copia(giorno: date) -> str:
    return f"pactum-{giorno:%Y%m%d}.db"


def _e_una_copia(nome: str) -> bool:
    """Vero solo per pactum-AAAAMMGG.db con una data vera: gli unici file della
    cartella che il server conta e toglie. Tutto il resto non si tocca mai."""
    trovato = _NOME_COPIA.fullmatch(nome)
    if trovato is None:
        return False
    try:
        datetime.strptime(trovato.group(1), "%Y%m%d")
    except ValueError:
        return False
    return True


def _copie_notturne(cartella: str) -> list[str]:
    """I nomi delle copie notturne, dalla piu' vecchia alla piu' nuova (AAAAMMGG nel
    nome: l'ordine dei nomi e' l'ordine dei giorni)."""
    with os.scandir(cartella) as voci:
        return sorted(v.name for v in voci if _e_una_copia(v.name) and v.is_file())


def stato_copie(cartella: str) -> dict:
    """Per /api/salute: il nome dell'ultima copia (mai il percorso), quando e' stata
    scritta e quante ce ne sono. Non solleva mai."""
    try:
        nomi = _copie_notturne(cartella)
        scritta = os.stat(os.path.join(cartella, nomi[-1])).st_mtime if nomi else None
    except OSError:
        nomi, scritta = [], None
    if scritta is None:
        return {"ultima": None, "quando": None, "copie": 0}
    return {
        "ultima": nomi[-1],
        "quando": clock.iso(datetime.fromtimestamp(scritta, timezone.utc)),
        "copie": len(nomi),
    }


def _sola_lettura(percorso: str) -> sqlite3.Connection:
    """Una connessione che non puo' scrivere niente: ne' sul file ne' accanto."""
    return sqlite3.connect(Path(percorso).resolve().as_uri() + "?mode=ro", uri=True, timeout=30)


def _problemi(percorso: str) -> str | None:
    """None se il file e' un registro sano: PRAGMA integrity_check dice "ok" e c'e' la
    tabella eventi (per SQLite anche un file vuoto e' un database sano, ma senza
    registro). Altrimenti, in breve, cosa non va."""
    try:
        conn = _sola_lettura(percorso)
        try:
            esito = [str(r[0]) for r in conn.execute("PRAGMA integrity_check")]
            if esito != ["ok"]:
                return " / ".join(riga for voce in esito for riga in voce.splitlines())[:300]
            if conn.execute(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'eventi'"
            ).fetchone() is None:
                return "dentro non c'e' il registro (manca la tabella eventi)"
        finally:
            conn.close()
    except sqlite3.Error as errore:
        return str(errore)
    return None


def _vacuum_into(db_path: str, destinazione: str) -> None:
    """La fotografia coerente del registro, da una connessione in sola lettura."""
    conn = _sola_lettura(db_path)
    try:
        conn.execute("VACUUM INTO ?", (destinazione,))
    finally:
        conn.close()


def _libero(percorso: str) -> str:
    """Il primo tra percorso, percorso-2, percorso-3... che non c'e' ancora: un file
    messo da parte non si sovrascrive mai."""
    candidato, numero = percorso, 2
    while os.path.lexists(candidato):
        candidato, numero = f"{percorso}-{numero}", numero + 1
    return candidato


def _togli(percorso: str) -> None:
    try:
        os.remove(percorso)
    except OSError:
        pass


def _dimensione(byte: int) -> str:
    if byte >= 1024 * 1024:
        return f"{byte / (1024 * 1024):.1f} MB".replace(".", ",")
    return f"{max(1, round(byte / 1024))} KB"


class CopiaNotturna:
    """controlla() e' un giro: fa la copia del giorno se tocca. gira() e' il compito
    in background che fa un giro all'avvio e poi uno al minuto."""

    def __init__(self, db_path: str, cartella: str):
        self.db_path = db_path
        self.cartella = cartella
        self._giorno_provato: date | None = None
        self._lucchetto = threading.Lock()

    def controlla(self) -> str | None:
        """Se sono passate le 03:00 (fuso del patto) e la copia di oggi non c'e', la fa
        e ne restituisce il nome. Un solo tentativo al giorno, riuscito o no: una copia
        che non riesce non riempie il log riprovando ogni minuto (riprova domani o al
        prossimo riavvio). Non solleva: una copia non riuscita e' un errore nel log,
        il server continua a funzionare."""
        with self._lucchetto:
            ora = clock.now().astimezone(config.fuso_patto())
            if ora.hour < ORA_COPIA or ora.date() == self._giorno_provato:
                return None
            self._giorno_provato = ora.date()
            nome = nome_copia(ora.date())
            if os.path.lexists(os.path.join(self.cartella, nome)):
                return None
            try:
                return self._copia(nome)
            except Exception as errore:
                log.error(
                    "copia del registro NON riuscita (%s): %s. Il server continua a"
                    " funzionare; riprovo domani dopo le 03:00 o al prossimo riavvio.",
                    nome,
                    errore,
                )
                return None

    def _copia(self, nome: str) -> str | None:
        finale = os.path.join(self.cartella, nome)
        parziale = finale + ".parziale"
        try:
            # "w" e non "x": se un tentativo interrotto (corrente saltata) ha lasciato
            # il suo .parziale, si riparte da un file vuoto, come vuole VACUUM INTO.
            open(parziale, "wb").close()
            _vacuum_into(self.db_path, parziale)
            with open(parziale, "rb+") as copia:
                os.fsync(copia.fileno())
            dimensione = os.path.getsize(parziale)
            problemi = _problemi(parziale)
            if problemi is None:
                os.replace(parziale, finale)
            else:
                rotta = _libero(finale + ".rotta")
                os.replace(parziale, rotta)
        except BaseException:
            _togli(parziale)
            raise
        if problemi is not None:
            log.error(
                "copia del registro ROTTA: %s non passa il controllo (%s). E' rimasta"
                " come %s, cosi' nessuno la scambia per buona; il registro non e' stato"
                " toccato. Riprovo domani dopo le 03:00 o al prossimo riavvio.",
                nome,
                problemi,
                os.path.basename(rotta),
            )
            return None
        log.info("copia del registro fatta: %s (%s)", nome, _dimensione(dimensione))
        self._togli_le_vecchie()
        return nome

    def _togli_le_vecchie(self) -> None:
        """Tiene le ultime COPIE_DA_TENERE copie notturne e toglie le piu' vecchie."""
        try:
            vecchie = _copie_notturne(self.cartella)[:-COPIE_DA_TENERE]
        except OSError as errore:
            log.warning("non riesco a guardare le copie vecchie: %s", errore)
            return
        for nome in vecchie:
            try:
                os.remove(os.path.join(self.cartella, nome))
                log.info("tolta la copia vecchia %s (tengo le ultime %d)", nome, COPIE_DA_TENERE)
            except OSError as errore:
                log.warning("non riesco a togliere la copia vecchia %s: %s", nome, errore)

    async def gira(self, ferma: asyncio.Event) -> None:
        """Un giro subito (la copia di recupero, se il NAS era spento alle 03:00) e poi
        uno al minuto, finche' `ferma` non scatta. Il lavoro su SQLite va in un thread:
        le richieste non aspettano la copia."""
        while not ferma.is_set():
            try:
                await asyncio.to_thread(self.controlla)
            except Exception:
                log.exception("copia notturna del registro: errore inatteso, il compito continua")
            try:
                await asyncio.wait_for(ferma.wait(), timeout=INTERVALLO_SECONDI)
            except TimeoutError:
                pass


def prepara_copia_notturna(settings: Settings) -> CopiaNotturna | None:
    """La copia notturna, se la cartella delle copie c'e'. Se non c'e' (sviluppo, o un
    Docker senza la cartella montata) resta spenta e il log lo dice: il server parte
    lo stesso."""
    if not os.path.isdir(settings.backup_dir):
        log.log(
            logging.ERROR if settings.env == "prod" else logging.WARNING,
            "copia notturna del registro DISATTIVATA: la cartella %s non c'e' (in Docker"
            " e' server/backup del NAS, montata su /backup).",
            settings.backup_dir,
        )
        return None
    log.info(
        "copia notturna del registro attiva: ogni giorno dopo le %02d:00 in %s, tengo le"
        " ultime %d",
        ORA_COPIA,
        settings.backup_dir,
        COPIE_DA_TENERE,
    )
    return CopiaNotturna(settings.db_path, settings.backup_dir)


def _non_parte(messaggio: str) -> NoReturn:
    log.error(messaggio)
    raise RuntimeError(messaggio)


def ripristina_se_chiesto(settings: Settings) -> None:
    """PACTUM_RIPRISTINA=<nome di una copia in PACTUM_BACKUP_DIR>: prima di aprire (e
    migrare) il database, rimette quella copia al posto del registro. Il registro di
    prima resta accanto come <db>.prima-del-ripristino-AAAAMMGG-HHMMSS, con i suoi
    -wal/-shm/-journal. Il segno .ripristinato-<nome> accanto al database impedisce
    di rifarlo a ogni riavvio. Nome non valido, copia che non c'e' o rotta: il server
    NON parte (meglio fermo che con un registro sbagliato) e il registro resta com'era."""
    nome = settings.ripristina
    if not nome:
        return
    if any(pezzo in nome for pezzo in ("/", "\\", "..", ":", "\0")):
        _non_parte(
            f"RIPRISTINO FERMATO: PACTUM_RIPRISTINA={nome!r} non va bene: ci vuole solo il"
            " nome di una copia della cartella delle copie (per esempio"
            " pactum-20260925.db), senza cartelle. Il registro non e' stato toccato e il"
            " server NON parte: correggi la riga nel file .env (o toglila) e riavvia."
        )
    db_path = os.path.abspath(settings.db_path)
    segno = os.path.join(os.path.dirname(db_path), f".ripristinato-{nome}")
    if os.path.lexists(segno):
        log.warning(
            "RIPRISTINO GIA' FATTO: %s e' gia' stata rimessa al posto del registro (c'e' il"
            " segno %s), non la rimetto un'altra volta. Togli PACTUM_RIPRISTINA dal file"
            " .env.",
            nome,
            os.path.basename(segno),
        )
        return
    copia = os.path.join(settings.backup_dir, nome)
    if not os.path.isfile(copia):
        _non_parte(
            f"RIPRISTINO FERMATO: la copia {nome} non c'e' nella cartella delle copie"
            f" ({settings.backup_dir}). Il registro non e' stato toccato e il server NON"
            " parte: controlla il nome in PACTUM_RIPRISTINA nel file .env (o togli la"
            " riga) e riavvia."
        )
    problemi = _problemi(copia)
    if problemi is not None:
        _non_parte(
            f"RIPRISTINO FERMATO: la copia {nome} e' rotta ({problemi}). Il registro non e'"
            " stato toccato e il server NON parte: scegli un'altra copia in"
            " PACTUM_RIPRISTINA nel file .env (o togli la riga) e riavvia."
        )

    ora = clock.now().astimezone(config.fuso_patto())
    da_parte = _libero(f"{db_path}.prima-del-ripristino-{ora:%Y%m%d-%H%M%S}")
    parziale = db_path + ".ripristino.parziale"
    c_era = os.path.lexists(db_path)
    spostati: list[tuple[str, str]] = []
    try:
        shutil.copyfile(copia, parziale)
        with open(parziale, "rb+") as file:
            os.fsync(file.fileno())
        for suffisso in ("",) + _COMPAGNI_SQLITE:
            if os.path.lexists(db_path + suffisso):
                os.replace(db_path + suffisso, da_parte + suffisso)
                spostati.append((db_path + suffisso, da_parte + suffisso))
        os.replace(parziale, db_path)
    except OSError as errore:
        rimesso = True
        for originale, spostato in reversed(spostati):
            try:
                os.replace(spostato, originale)
            except OSError:
                rimesso = False
        _togli(parziale)
        _non_parte(
            f"RIPRISTINO FERMATO: non riesco a rimettere {nome} al posto del registro"
            f" ({errore}). "
            + (
                "Il registro e' rimasto quello di prima"
                if rimesso
                else f"ATTENZIONE: il registro di prima e' in {os.path.basename(da_parte)},"
                " accanto al database"
            )
            + " e il server NON parte: controlla lo spazio libero e i permessi, poi"
            " riavvia (o togli PACTUM_RIPRISTINA dal file .env)."
        )

    prima = (
        f"il registro di prima e' messo da parte accanto al database come"
        f" {os.path.basename(da_parte)}"
        if c_era
        else "prima non c'era nessun registro"
    )
    try:
        with open(segno, "w", encoding="utf-8") as file:
            file.write(f"{clock.iso(clock.now())}: rimessa la copia {nome}; {prima}\n")
            file.flush()
            os.fsync(file.fileno())
    except OSError as errore:
        log.error(
            "non riesco a scrivere il segno %s (%s): togli SUBITO PACTUM_RIPRISTINA dal"
            " file .env, altrimenti al prossimo riavvio la copia viene rimessa di nuovo.",
            os.path.basename(segno),
            errore,
        )
    log.warning(
        "RIPRISTINO FATTO: il registro adesso e' la copia %s; %s. Adesso togli"
        " PACTUM_RIPRISTINA dal file .env (il segno %s impedisce comunque di rimetterla"
        " a ogni riavvio).",
        nome,
        prima,
        os.path.basename(segno),
    )
