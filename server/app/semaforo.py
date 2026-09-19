"""Il semaforo delle regole e la striscia del patto (contratto-api.md, GET /api/finestra).

Il semaforo per regola (8 giorni, verde/rosso/grigio) vive qui e non dentro la
finestra perche' la striscia aggregata (v2.4) serve anche a GET /api/patto: come
per siti.siti_recenti, le due app leggono la striscia dalla STESSA funzione, quindi
quella del figlio e quella del genitore non possono divergere (tavola rotonda).

I giorni sono gli stessi 8 della finestra (siti.giorni_finestra), nel fuso del patto.
"""

import json
import sqlite3
from collections import defaultdict
from datetime import datetime

from . import clock, siti
from .config import fuso_patto


def data_locale(ts_server: str, tz) -> str:
    """Il giorno LOCALE (fuso del patto) di un ts_server UTC ISO."""
    return datetime.fromisoformat(ts_server).astimezone(tz).date().isoformat()


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


def semafori(conn: sqlite3.Connection, ora: datetime) -> dict:
    """Il semaforo di OGNI regola, eliminate comprese: {regola_id: 8 voci
    {"data", "stato"}}, dal piu' vecchio a oggi.

    Tre colori: verde/rosso/grigio. Il giallo non esiste piu': il bonus
    autoritativo vive nella tabella bonus e si legge in bonus_giornalieri, non
    appeso a una regola. Per limite_tempo/fascia_oraria il rosso viene dagli
    sforamenti; per le vita_reale (v2.1) dalle dichiarazioni e dai verdetti."""
    tz = fuso_patto()
    date_iso = [g.isoformat() for g in siti.giorni_finestra(ora)]

    sforamenti_per_regola = defaultdict(set)  # regola_id -> {data ISO locale}
    for evento in conn.execute(
        "SELECT dettagli, ts_server FROM eventi WHERE tipo = 'sforamento'"
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
        sforamenti_per_regola[regola_id].add(giorno)

    # Dichiarazioni per le regole vita_reale: (regola_id, giorno locale) -> stato.
    # Max una per regola per giorno, quindi la mappa e' univoca.
    dich_per_regola = defaultdict(dict)  # regola_id -> {giorno ISO: stato dichiarazione}
    for d in conn.execute("SELECT regola_id, giorno, stato FROM dichiarazioni").fetchall():
        dich_per_regola[d["regola_id"]][d["giorno"]] = d["stato"]

    # (v2.4) I giorni di cui il telefono ha raccontato qualcosa: quelli con una
    # fotografia uso_giornaliero vigente. `giorno` e' gia' il giorno locale.
    segnaposto = ",".join("?" * len(date_iso))
    giorni_con_dati = {
        r["giorno"]
        for r in conn.execute(
            f"SELECT giorno FROM uso_giornaliero WHERE giorno IN ({segnaposto})", date_iso
        ).fetchall()
    }

    per_regola = {}
    for riga in conn.execute(
        "SELECT id, tipo, attiva, creata_ts, ultima_modifica_ts FROM regole ORDER BY id"
    ).fetchall():
        creata = data_locale(riga["creata_ts"], tz)
        eliminata = None
        if not riga["attiva"]:
            # Soft-delete: i giorni STRETTAMENTE successivi all'eliminazione
            # sono fuori dalla vita della regola -> grigio, non verde.
            eliminata = data_locale(riga["ultima_modifica_ts"], tz)
        voci = []
        for data in date_iso:
            if data < creata or (eliminata is not None and data > eliminata):
                stato = "grigio"
            elif riga["tipo"] == "vita_reale":
                stato = _semaforo_vita(dich_per_regola[riga["id"]].get(data))
            elif data in sforamenti_per_regola[riga["id"]]:
                stato = "rosso"  # lo sforamento e' gia' un dato: rosso anche senza fotografia
            elif data in giorni_con_dati:
                stato = "verde"
            else:
                # (v2.4) Niente verde senza dati: un giorno di cui non si sa nulla
                # non puo' figurare come mantenuto.
                stato = "grigio"
            voci.append({"data": data, "stato": stato})
        per_regola[riga["id"]] = voci
    return per_regola


def striscia(conn: sqlite3.Connection, ora: datetime) -> list:
    """(v2.4) La striscia AGGREGATA del patto: 8 voci {"data", "stato"}, gli stessi
    giorni del semaforo, dal piu' vecchio a oggi. Si ricava dai semafori di TUTTE
    le regole, eliminate comprese (fuori dalla loro vita sono gia' grigie, quindi
    contano solo nei giorni in cui erano in vita): rosso se almeno una e' rossa,
    altrimenti verde se almeno una e' verde, altrimenti grigio.

    Unica fonte della striscia di GET /api/finestra e di GET /api/patto."""
    stati_per_giorno = defaultdict(set)
    for voci in semafori(conn, ora).values():
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
            stato = "grigio"  # nessuna regola in vita o nessun dato
        risultato.append({"data": data, "stato": stato})
    return risultato


def riepilogo(conn: sqlite3.Connection, ora: datetime) -> dict:
    """(v2.4) La riga sotto la striscia, uguale nelle due app: quanti giorni della
    striscia sono fuori regola e quante interruzioni della registrazione (eventi
    manomissione) cadono negli stessi 8 giorni, contati nel fuso del patto.

    Si calcola qui, e non nelle app, per due motivi: le app ricevono al massimo
    gli ultimi 20 eventi, e il giorno di un evento va preso nel fuso del patto,
    non in quello del telefono che legge. Unica fonte per /api/finestra e
    /api/patto."""
    tz = fuso_patto()
    giorni = {g.isoformat() for g in siti.giorni_finestra(ora)}
    interruzioni = sum(
        1
        for e in conn.execute("SELECT ts_server FROM eventi WHERE tipo = 'manomissione'")
        if data_locale(e["ts_server"], tz) in giorni
    )
    fuori_regola = sum(1 for voce in striscia(conn, ora) if voce["stato"] == "rosso")
    return {"giorni_fuori_regola": fuori_regola, "interruzioni": interruzioni}
