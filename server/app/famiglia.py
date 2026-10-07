"""(v3) La famiglia: figli e dispositivi (contratto-api.md, "v3 — Famiglia, figli e
dispositivi"). Qui vivono le letture condivise da piu' endpoint: quale figlio
vuole il genitore, quali dispositivi ha, qual e' il primo (quello a cui valgono i
campi di primo livello della finestra per le app 0.7), se un dispositivo tace o
e' solo spento (v3.7: telefoni e computer), (v3.4) come si chiamano le sue app
(finestra e proposte)."""

import json
import sqlite3
from datetime import datetime, timedelta

from fastapi import HTTPException

from . import clock
from .config import SOGLIA_SILENZIO_MINUTI

# (v3.1) Le etichette leggibili delle app si cercano nelle fotografie degli ultimi
# 60 giorni, non in tutta la storia: una regola su un'app mai piu' usata da due
# mesi mostra il nome del pacchetto (il ripiego del contratto).
GIORNI_NOMI = 60


def figlio_o_404(conn: sqlite3.Connection, figlio_id: int) -> sqlite3.Row:
    riga = conn.execute("SELECT * FROM figli WHERE id = ?", (figlio_id,)).fetchone()
    if riga is None:
        raise HTTPException(status_code=404, detail="figlio non trovato")
    return riga


def figlio_scelto(conn: sqlite3.Connection, figlio_id: int | None) -> sqlite3.Row:
    """Il figlio di una richiesta del genitore: quello indicato da `figlio_id`,
    oppure, se manca, quello con l'id piu' basso (cosi' l'app del genitore 0.7
    continua a vedere il primo figlio). Un figlio_id inesistente -> 404."""
    if figlio_id is not None:
        return figlio_o_404(conn, figlio_id)
    riga = conn.execute("SELECT * FROM figli ORDER BY id LIMIT 1").fetchone()
    if riga is None:
        raise HTTPException(status_code=404, detail="nessun figlio")
    return riga


def dispositivi_del_figlio(conn: sqlite3.Connection, figlio_id: int) -> list[sqlite3.Row]:
    """Tutti i dispositivi del figlio in ordine di id, revocati compresi."""
    return conn.execute(
        "SELECT * FROM dispositivi WHERE figlio_id = ? ORDER BY id", (figlio_id,)
    ).fetchall()


def primo_dispositivo(dispositivi: list[sqlite3.Row]) -> sqlite3.Row | None:
    """Il dispositivo con l'id piu' basso non revocato: i campi di primo livello
    della finestra valgono per lui (compatibilita' 0.7). None se non ce n'e'."""
    return next((d for d in dispositivi if d["revocato_ts"] is None), None)


def riferimento(dispositivo: sqlite3.Row) -> dict:
    """Il dispositivo come lo allegano regole, patto e abbinamento: solo chi e'."""
    return {"id": dispositivo["id"], "nome": dispositivo["nome"], "tipo": dispositivo["tipo"]}


def descrizione(dispositivo: sqlite3.Row) -> dict:
    return {
        **riferimento(dispositivo),
        "abbinato": dispositivo["abbinato_ts"] is not None,
        "revocato": dispositivo["revocato_ts"] is not None,
    }


def nomi_recenti(conn: sqlite3.Connection, figlio_id: int, oggi) -> dict:
    """(S2) L'ultima etichetta leggibile vista per ciascun pacchetto nelle
    fotografie uso_giornaliero (la piu' recente vince): serve a mostrare al
    genitore "TikTok" invece di com.zhiliaoapp.musically sulle regole
    limite_tempo. Le fotografie sono al piu' una per giorno per dispositivo.
    Fotografie senza `nomi` (pre-v2.2) si saltano. (v3) Dalle fotografie di tutti
    i dispositivi del figlio: anche "Minecraft" per exe:minecraft.exe arriva cosi'
    dal computer. (v3.1) Solo quelle degli ultimi GIORNI_NOMI giorni: la storia
    cresce, e ogni fotografia letta qui e' un JSON da aprire. (v3.4) Le stesse
    etichette scrivono i bersagli nel confronto delle proposte: qui e non in
    routes/genitore.py, che le proposte non possono importare (genitore importa
    proposte)."""
    nomi: dict = {}
    for riga in conn.execute(
        "SELECT u.dettagli FROM uso_giornaliero u JOIN dispositivi d ON d.id = u.dispositivo_id"
        " WHERE d.figlio_id = ? AND u.giorno >= ? ORDER BY u.ts_server DESC, u.giorno DESC",
        (figlio_id, (oggi - timedelta(days=GIORNI_NOMI - 1)).isoformat()),
    ).fetchall():
        mappa = json.loads(riga["dettagli"]).get("nomi")
        if not isinstance(mappa, dict):
            continue
        for chiave, nome in mappa.items():
            if chiave not in nomi and isinstance(nome, str) and nome:
                nomi[chiave] = nome
    return nomi


def _ultimo_fra_sospensioni_e_riprese(conn: sqlite3.Connection, dispositivo_id: int):
    """(v3.7) L'ultimo fatto tra sospensioni e riprese del dispositivo, con il suo momento
    (clock.momento_dichiarato: l'ora del dispositivo per un fatto consegnato in ritardo,
    con le tutele; altrimenti l'arrivo). Si mettono in fila per momento e, a parita',
    per ordine d'arrivo (rowid): una sospensione recuperata alla riaccensione e la ripresa
    dello stesso avvio possono arrivare nello stesso pacco in qualsiasi ordine, e vince
    quella successa dopo. Con un orologio sano e' lo stesso ordine della coda.

    Un momento non e' mai piu' di 48 ore prima del suo arrivo: l'ultimo fatto e' sempre
    tra quelli arrivati nelle 48 ore prima dell'ultimo arrivo, e solo quelli si leggono.
    (tipo, momento ISO, motivo) oppure None se il dispositivo non ne ha mai mandati.
    (v4.0) `motivo`: quello scritto nei dettagli (per una sospensione: spegnimento,
    sospensione o disconnessione), None se non c'e'."""
    tipi = "tipo IN ('sospensione', 'ripresa')"
    ultimo_arrivo = conn.execute(
        f"SELECT MAX(ts_server) AS t FROM eventi WHERE dispositivo_id = ? AND {tipi}", (dispositivo_id,)
    ).fetchone()["t"]
    if ultimo_arrivo is None:
        return None
    dal = clock.iso(datetime.fromisoformat(ultimo_arrivo) - clock.RITARDO_MASSIMO)
    candidati = []
    for riga in conn.execute(
        f"SELECT rowid, tipo, ts_server, ts_device, dettagli FROM eventi WHERE dispositivo_id = ? AND {tipi}"
        " AND ts_server >= ?",
        (dispositivo_id, dal),
    ).fetchall():
        momento = clock.momento_dichiarato(datetime.fromisoformat(riga["ts_server"]), riga["ts_device"])
        candidati.append((momento, riga["rowid"], riga["tipo"], riga["dettagli"]))
    momento, _, tipo, dettagli = max(candidati)
    return tipo, clock.iso(momento), _motivo(dettagli)


def _motivo(dettagli: str | None) -> str | None:
    try:
        letti = json.loads(dettagli) if dettagli else None
    except ValueError:
        return None
    motivo = letti.get("motivo") if isinstance(letti, dict) else None
    return motivo if isinstance(motivo, str) else None


def motivo_dello_spegnimento(conn: sqlite3.Connection, dispositivo_id: int) -> str | None:
    """(v4.0) Il `motivo` della sospensione che tiene il dispositivo `spento` (l'ultimo
    fatto tra sospensioni e riprese), None se l'ultimo fatto non e' una sospensione. Serve
    alla rete di sicurezza del computer (studio._spariti): un'uscita dall'account
    (`disconnessione`) non e' uno spegnimento pulito."""
    fatto = _ultimo_fra_sospensioni_e_riprese(conn, dispositivo_id)
    if fatto is None or fatto[0] != "sospensione":
        return None
    return fatto[2]


def stato_silenzio(conn: sqlite3.Connection, dispositivo: sqlite3.Row | None, ora: datetime) -> dict:
    """`silente` = nessun battito da piu' di 45 minuti, calcolato in lettura
    sull'orologio del server; `ultimo_battito` null se non e' mai arrivato niente
    (=> silente).

    (v3) Per un COMPUTER il silenzio dopo una `sospensione` non e'
    un'interruzione: un computer spento la sera e' normale. Finche' dopo la
    sospensione non arriva un segno di vita (un battito o una `ripresa`), il
    computer e' `spento` dal momento della sospensione e non `silente`.

    (v3.7) Lo stesso per i TELEFONI, che ora mandano la sospensione quando si spengono.
    Il momento della sospensione e' l'ora del dispositivo se e' stata consegnata in
    ritardo (alla riaccensione), con le tutele delle sessioni; se no, l'arrivo. Un
    battito conta come segno di vita se e' arrivato dopo quel momento; una ripresa se e'
    successa dopo (_ultimo_fra_sospensioni_e_riprese)."""
    if dispositivo is None:
        return {"ultimo_battito": None, "silente": True, "spento": False, "spento_dal": None}
    ultimo = conn.execute(
        "SELECT MAX(ts_server) AS ultimo FROM battiti WHERE dispositivo_id = ?",
        (dispositivo["id"],),
    ).fetchone()["ultimo"]

    fatto = _ultimo_fra_sospensioni_e_riprese(conn, dispositivo["id"])
    if fatto is not None and fatto[0] == "sospensione":
        spento_dal = fatto[1]
        if ultimo is None or ultimo <= spento_dal:  # nessun battito dopo: e' spento
            return {
                "ultimo_battito": ultimo,
                "silente": False,
                "spento": True,
                "spento_dal": spento_dal,
            }

    if ultimo is None:
        return {"ultimo_battito": None, "silente": True, "spento": False, "spento_dal": None}
    trascorso = ora - datetime.fromisoformat(ultimo)
    return {
        "ultimo_battito": ultimo,
        "silente": trascorso > timedelta(minutes=SOGLIA_SILENZIO_MINUTI),
        "spento": False,
        "spento_dal": None,
    }
