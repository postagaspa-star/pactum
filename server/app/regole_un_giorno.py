"""(v4.2) Le regole di un giorno: le promesse del bilancio della sera che il telefono
puo' controllare da solo ("domani al massimo 1 h di social", "domani niente telefono
dalle 15 alle 17"). Sono regole come le altre (le vede il genitore, tingono il semaforo,
il telefono le valuta), con tre differenze:

- valgono SOLO il loro giorno: fuori da quel giorno il loro semaforo e' grigio, e il
  telefono non le valuta;
- il giorno dopo si spengono da sole (soft-delete, come un'eliminazione: la storia
  resta), senza lock e senza avvisare il genitore;
- non tengono in piedi il patto: per "almeno una regola" contano solo le altre.

Il bilancio della sera e il testo delle promesse restano sul telefono: qui arriva solo
la regola. La tabella (db.TABELLA_REGOLE_UN_GIORNO) e' nuova: niente migrazione."""

import json
import sqlite3
from datetime import date, datetime, time, timedelta, timezone

from . import clock
from .config import fuso_patto
from .db import registra_modifica

GIORNI_SETTIMANA = ("lun", "mar", "mer", "gio", "ven", "sab", "dom")


def oggi_nel_patto(ora: datetime) -> date:
    return ora.astimezone(fuso_patto()).date()


def giorno_ammesso(giorno: str, ora: datetime) -> bool:
    """Una regola di un giorno vale oggi o domani, nel fuso del patto: e' la promessa
    della sera per il giorno dopo (o per il resto di oggi). Mai il passato, mai piu' in
    la': una promessa lontana non e' piu' una promessa della sera."""
    if not clock.giorno_valido(giorno):
        return False
    oggi = oggi_nel_patto(ora)
    return date.fromisoformat(giorno) in (oggi, oggi + timedelta(days=1))


def errore_parametri(tipo: str, parametri: dict, giorno: str) -> str | None:
    """Il motivo per cui questi parametri non vanno per una regola di un giorno, o None.
    Una fascia deve valere quel giorno della settimana e non passare la mezzanotte (la
    coda del mattino dopo cadrebbe in un giorno in cui la regola non c'e' piu')."""
    if tipo == "vita_reale":
        return "una regola di un giorno e' un limite di tempo o una fascia oraria"
    if tipo == "fascia_oraria":
        nome = GIORNI_SETTIMANA[date.fromisoformat(giorno).weekday()]
        if nome not in parametri["giorni"]:
            return f"la fascia deve valere di {nome}"
        if parametri["dalle"] >= parametri["alle"]:
            return "una fascia di un giorno non passa la mezzanotte"
    return None


def registra(conn: sqlite3.Connection, regola_id: int, giorno: str) -> None:
    conn.execute("INSERT INTO regole_un_giorno (regola_id, giorno) VALUES (?, ?)", (regola_id, giorno))


def giorno_della_regola(conn: sqlite3.Connection, regola_id: int) -> str | None:
    riga = conn.execute("SELECT giorno FROM regole_un_giorno WHERE regola_id = ?", (regola_id,)).fetchone()
    return riga["giorno"] if riga is not None else None


def giorni_del_figlio(conn: sqlite3.Connection, figlio_id: int) -> dict:
    """{regola_id: giorno} delle regole di un giorno del figlio, scadute comprese."""
    return {
        r["regola_id"]: r["giorno"]
        for r in conn.execute(
            "SELECT u.regola_id, u.giorno FROM regole_un_giorno u JOIN regole r ON r.id = u.regola_id"
            " WHERE r.figlio_id = ?",
            (figlio_id,),
        ).fetchall()
    }


def _fine_del_giorno(giorno: str) -> datetime:
    """L'ultimo secondo di `giorno` nel fuso del patto: la regola scaduta "muore" li',
    cosi' il semaforo la conta fino a quel giorno compreso e non dopo."""
    locale = datetime.combine(date.fromisoformat(giorno), time(23, 59, 59), tzinfo=fuso_patto())
    return locale.astimezone(timezone.utc)


def _da_spegnere(conn: sqlite3.Connection, figlio_id: int, oggi: str) -> list:
    return conn.execute(
        "SELECT u.regola_id, u.giorno FROM regole_un_giorno u JOIN regole r ON r.id = u.regola_id"
        " WHERE r.figlio_id = ? AND u.scaduta = 0 AND u.giorno < ?",
        (figlio_id, oggi),
    ).fetchall()


def valuta(conn: sqlite3.Connection, figlio_id: int, ora: datetime | None = None) -> None:
    """Prima di rispondere a una richiesta sul figlio: le regole di un giorno passate si
    spengono. Come studio.valuta, prima si legge e solo se serve si scrive (BEGIN
    IMMEDIATE, rilegge, scrive): un GET normale non prende il lucchetto di scrittura."""
    ora = ora or clock.now()
    oggi = oggi_nel_patto(ora).isoformat()
    if not _da_spegnere(conn, figlio_id, oggi):
        return
    # Importata qui: routes.regole importa questo modulo.
    from .routes.regole import annulla_proposte_pendenti

    conn.execute("BEGIN IMMEDIATE")
    try:
        for voce in _da_spegnere(conn, figlio_id, oggi):
            regola = conn.execute("SELECT * FROM regole WHERE id = ?", (voce["regola_id"],)).fetchone()
            if regola["attiva"]:
                ts = clock.iso(_fine_del_giorno(voce["giorno"]))
                conn.execute(
                    "UPDATE regole SET attiva = 0, ultima_modifica_ts = ? WHERE id = ?", (ts, regola["id"])
                )
                # Concordata: la scadenza era scritta nella regola fin dalla nascita.
                registra_modifica(
                    conn, regola["id"], "eliminazione", "allenta", json.loads(regola["parametri"]), None, True, ts
                )
                annulla_proposte_pendenti(conn, regola, clock.iso(ora))
            conn.execute("UPDATE regole_un_giorno SET scaduta = 1 WHERE regola_id = ?", (voce["regola_id"],))
        conn.commit()
    except BaseException:
        conn.rollback()
        raise


def del_dispositivo(
    conn: sqlite3.Connection, dispositivo_id: int, primo_giorno: str, semafori: dict
) -> list:
    """Le regole di un giorno di questo dispositivo con il giorno negli 8 della finestra,
    scadute comprese, ciascuna col suo semaforo: il telefono ci legge com'e' andata la
    promessa anche dopo che la regola si e' spenta (e quindi non e' piu' tra le `regole`)."""
    righe = conn.execute(
        "SELECT u.regola_id, u.giorno FROM regole_un_giorno u JOIN regole r ON r.id = u.regola_id"
        " WHERE r.dispositivo_id = ? AND u.giorno >= ? ORDER BY u.regola_id",
        (dispositivo_id, primo_giorno),
    ).fetchall()
    return [
        {"id": r["regola_id"], "giorno": r["giorno"], "semaforo": semafori.get(r["regola_id"], [])}
        for r in righe
    ]
