"""(v4.3) Le immagini di un lavoro di casa (richiesta di Andrea, 27/10: «quando crei un
lavoro tu possa allegare anche delle immagini in modo facoltativo»).

Un genitore allega al massimo IMMAGINI_MASSIME immagini (JPEG) a un lavoro ancora aperto
(da fare o da approvare): un esempio di com'e' fatto bene, dove stanno le cose. Le vedono
il genitore e i dispositivi del figlio. Sono una cosa a parte dalla foto del figlio: non
sbloccano niente e non cambiano lo stato del lavoro.

- Una riga per immagine in `faccende_immagini` (faccenda, numero 1..3, quando, chi); il
  file sta in <cartella delle foto>/allegati/<faccenda>_<numero>.jpg. La pulizia delle foto
  (faccende.pulisci_foto) non tocca le sottocartelle: qui c'e' la sua (pulisci).
- Il JPEG si pulisce dei dati nascosti come la foto del figlio (faccende.pulisci_jpeg: via
  la posizione GPS e il resto), stesso limite di 4 MB.
- Si tengono finche' il lavoro e' aperto, e 30 giorni dopo che si e' chiuso (fatto e
  approvato, o annullato), come le foto; poi il file si toglie (la riga resta: la lista
  dice ancora che c'erano, e GET risponde 404 "immagine non trovata")."""

import logging
import os
import re
import secrets
import sqlite3
import time
from datetime import datetime, timedelta

from . import clock

log = logging.getLogger("pactum.allegati")

IMMAGINI_MASSIME = 3
GIORNI_DOPO_LA_CHIUSURA = 30
NON_TROVATA = "immagine non trovata"
_NOME = re.compile(r"([1-9]\d{0,17})_([1-9])\.jpg")
_ETA_MINIMA_ORFANI_SECONDI = 3600

TABELLA = """
CREATE TABLE IF NOT EXISTS faccende_immagini (
    faccenda_id INTEGER NOT NULL REFERENCES faccende(id),
    n INTEGER NOT NULL CHECK (n BETWEEN 1 AND 3),
    ts TEXT NOT NULL,
    genitore_id INTEGER NOT NULL REFERENCES genitori(id),
    PRIMARY KEY (faccenda_id, n)
);
"""


def cartella(cartella_foto: str) -> str:
    """Le immagini allegate: una sottocartella di quella delle foto."""
    return os.path.join(cartella_foto, "allegati")


def percorso(cartella_allegati: str, faccenda_id: int, n: int) -> str:
    return os.path.join(cartella_allegati, f"{faccenda_id}_{n}.jpg")


def elenco(conn: sqlite3.Connection, ids: list[int]) -> dict[int, list]:
    """Per ogni faccenda di `ids` le sue immagini, dalla prima: [{"n", "ts"}]."""
    risultato: dict[int, list] = {i: [] for i in ids}
    if not ids:
        return risultato
    segnaposto = ",".join("?" * len(ids))
    for r in conn.execute(
        f"SELECT faccenda_id, n, ts FROM faccende_immagini WHERE faccenda_id IN ({segnaposto})"
        " ORDER BY faccenda_id, n",
        ids,
    ).fetchall():
        risultato[r["faccenda_id"]].append({"n": r["n"], "ts": r["ts"]})
    return risultato


def salva(cartella_allegati: str, faccenda_id: int, n: int, dati: bytes) -> None:
    """Il file, scritto prima in un temporaneo e poi rinominato (atomico): chi lo legge
    trova sempre o l'immagine di prima o quella nuova intera."""
    os.makedirs(cartella_allegati, exist_ok=True)
    finale = percorso(cartella_allegati, faccenda_id, n)
    temporanea = f"{finale}.{secrets.token_hex(6)}.parziale"
    with open(temporanea, "xb") as file:
        file.write(dati)
        file.flush()
        os.fsync(file.fileno())
    os.replace(temporanea, finale)


def leggi(cartella_allegati: str, faccenda_id: int, n: int) -> bytes | None:
    try:
        with open(percorso(cartella_allegati, faccenda_id, n), "rb") as file:
            return file.read()
    except FileNotFoundError:
        return None


def togli(cartella_allegati: str, faccenda_id: int, n: int) -> None:
    try:
        os.remove(percorso(cartella_allegati, faccenda_id, n))
    except FileNotFoundError:
        pass
    except OSError as errore:
        log.warning("non riesco a togliere l'immagine %s_%s: %s (ci riprova la pulizia)", faccenda_id, n, errore)


def pulisci(db_path: str, cartella_foto: str, ora: datetime) -> int:
    """Toglie i file delle immagini dei lavori chiusi da piu' di 30 giorni (fatti e
    approvati, o annullati) e quelli senza una riga (un'immagine tolta, un temporaneo di un
    invio interrotto), piu' vecchi di un'ora. Restituisce quanti file ha tolto."""
    dove = cartella(cartella_foto)
    if not os.path.isdir(dove):
        return 0
    limite = clock.iso(ora - timedelta(days=GIORNI_DOPO_LA_CHIUSURA))
    conn = sqlite3.connect(db_path, timeout=30)
    conn.row_factory = sqlite3.Row
    try:
        vive = set()
        for r in conn.execute(
            "SELECT i.faccenda_id, i.n FROM faccende_immagini i JOIN faccende f ON f.id = i.faccenda_id"
            " WHERE NOT ((f.stato = 'annullata' OR f.confermata_ts IS NOT NULL)"
            " AND COALESCE(f.confermata_ts, f.chiusa_ts, f.creata_ts) < ?)",
            (limite,),
        ).fetchall():
            vive.add((r["faccenda_id"], r["n"]))
    finally:
        conn.close()
    adesso = time.time()
    tolti = 0
    with os.scandir(dove) as voci:
        for voce in list(voci):
            if not voce.is_file(follow_symlinks=False):
                continue
            nome = _NOME.fullmatch(voce.name)
            if nome and (int(nome.group(1)), int(nome.group(2))) in vive:
                continue
            try:
                if adesso - voce.stat(follow_symlinks=False).st_mtime < _ETA_MINIMA_ORFANI_SECONDI:
                    continue  # appena nato: forse un invio in corso, la sua riga arriva subito dopo
                os.remove(voce.path)
                tolti += 1
            except OSError as errore:
                log.warning("pulizia delle immagini: non riesco a togliere %s: %s", voce.name, errore)
    if tolti:
        log.info("pulizia delle immagini dei lavori: tolti %d file", tolti)
    return tolti
