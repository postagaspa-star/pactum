"""I tempi d'uso di un dispositivo: `uso_recente`, `medie` e il riepilogo dei bonus
degli 8 giorni.

(v3.8) Una sola fonte per GET /api/finestra (primo livello e `dispositivi[]`) e per
GET /api/patto (questo dispositivo e `dispositivi[]`): il figlio vede i suoi tempi
identici, campo per campo, a quelli che vede il genitore — come gia' `siti_recenti`
e `striscia` (contratto-api.md, "v3.8 — il tempo nelle due app"). Fino alla v3.7
queste funzioni stavano in routes/genitore.py, usate solo dalla finestra."""

import json
import sqlite3
from collections import defaultdict
from datetime import date, datetime, timedelta

from . import semaforo
from .config import MINUTI_IN_UN_GIORNO, fuso_patto
from .schemas import CHIAVE_TOTALE


def _minuti_validi(mappa) -> dict:
    """Tiene solo le voci {chiave: minuti} con minuti numerici non negativi:
    una fotografia sporca non deve far crollare la finestra. (v3.5) E non oltre i
    minuti di un giorno: una voce assurda si lascia cadere."""
    if not isinstance(mappa, dict):
        return {}
    return {
        chiave: minuti
        for chiave, minuti in mappa.items()
        if isinstance(minuti, (int, float))
        and not isinstance(minuti, bool)
        and 0 <= minuti <= MINUTI_IN_UN_GIORNO
    }


def _sessioni_minuti(dettagli: dict) -> int | None:
    """(v3.5) I minuti del giorno non contati perche' passati in una sessione, se la
    fotografia li dice: un intero da 0 a 1440. Altrimenti null, mai uno zero finto:
    un telefono 0.10 non li manda, e "non detto" non e' "zero". Un valore sporco si
    ignora, non fa cadere la fotografia ne' la finestra."""
    minuti = dettagli.get("sessioni_minuti")
    if isinstance(minuti, bool) or not isinstance(minuti, int) or not 0 <= minuti <= MINUTI_IN_UN_GIORNO:
        return None
    return minuti


def _totale_valido(totale) -> int:
    """(v3.8) Il totale_minuti salvato di una fotografia, letto con lo stesso criterio
    di _TOTALE_VALIDO (medie) e di routes/figlio.py `_totale_minuti` (salvataggio): un
    intero da 0 a 1440, altrimenti 0."""
    if isinstance(totale, bool) or not isinstance(totale, int) or not 0 <= totale <= MINUTI_IN_UN_GIORNO:
        return 0
    return totale


def _con_limite(voce: dict, limite: dict | None, bonus_regola: dict, giorno: str) -> None:
    if limite:
        voce.update(limite)
        voce["bonus"] = bonus_regola.get((giorno, limite["regola_id"]), 0)


def limiti_attivi(righe_regole: list, dispositivo_id: int | None) -> tuple[dict, dict | None]:
    """I limiti delle regole limite_tempo ATTIVE del dispositivo, fra le righe date
    (in ordine di id: a parita' di chiave vale la regola piu' vecchia):
    ({app_o_categoria: {"limite", "regola_id"}}, limite della regola "totale" o None).
    (v3.3) Il limite sul totale non e' di un'app ne' di una categoria: sta accanto al
    totale del giorno, e nessuna voce di uso_minuti lo prende."""
    limiti = {}
    for riga in righe_regole:
        if riga["tipo"] == "limite_tempo" and riga["attiva"] and riga["dispositivo_id"] == dispositivo_id:
            parametri = json.loads(riga["parametri"])
            limiti.setdefault(
                parametri["app_o_categoria"],
                {"limite": parametri["minuti_al_giorno"], "regola_id": riga["id"]},
            )
    return limiti, limiti.pop(CHIAVE_TOTALE, None)


def bonus_della_finestra(
    conn: sqlite3.Connection, ora: datetime, dispositivo_id: int | None
) -> tuple[dict, dict]:
    """I bonus del dispositivo negli 8 giorni, dalla tabella bonus autoritativa, coi
    giorni nel fuso del patto: ({giorno: minuti}, {(giorno, regola_id): minuti}).
    (v3.1) Solo i bonus dall'inizio della finestra (col margine di semaforo.py):
    quelli piu' vecchi cadrebbero fuori dagli 8 giorni comunque."""
    tz = fuso_patto()
    minuti_per_giorno = defaultdict(int)
    bonus_regola = defaultdict(int)
    for riga in conn.execute(
        "SELECT minuti, regola_id, ts_server FROM bonus WHERE dispositivo_id = ? AND ts_server >= ?",
        (dispositivo_id, semaforo.inizio_letture(ora)),
    ).fetchall():
        giorno = semaforo.data_locale(riga["ts_server"], tz)
        minuti_per_giorno[giorno] += riga["minuti"]
        if riga["regola_id"] is not None:
            bonus_regola[(giorno, riga["regola_id"])] += riga["minuti"]
    return minuti_per_giorno, bonus_regola


def uso_recente(
    conn: sqlite3.Connection,
    dispositivo_id: int | None,
    giorni: list,
    limiti: dict,
    bonus_regola: dict | None = None,
    limite_totale: dict | None = None,
) -> list:
    """(v2.2) I tempi d'uso di TUTTE le app negli 8 giorni della finestra, dalla
    fotografia uso_giornaliero VIGENTE di ciascun giorno. Un giorno senza
    fotografia ha totale_minuti null e liste vuote — MAI uno zero finto:
    "nessun dato ricevuto" e' un'informazione (contratto-api.md).
    `limiti` = {app_o_categoria: {"limite", "regola_id"}} delle regole
    limite_tempo ATTIVE: il limite compare SOLO dove la chiave combacia
    esattamente. E' il limite BASE (minuti_al_giorno): gli eventuali bonus del
    giorno sono gia' visibili in bonus_giornalieri. (v2.4) Accanto al limite va
    anche `bonus`, i minuti concessi QUEL giorno su QUELLA regola: senza, il
    genitore vedrebbe "10 min oltre" in un giorno che per il figlio (limite + bonus)
    e' dentro la regola. (v3) Tutto di un dispositivo: le sue fotografie, i limiti
    delle sue regole, i suoi bonus. (v3.3) `limite_totale` = {"limite", "regola_id"}
    della regola "totale" ATTIVA del dispositivo: va accanto a totale_minuti nella
    voce del giorno, solo nei giorni con la fotografia. (v3.5) Accanto a totale_minuti
    anche `sessioni_minuti`, dalla fotografia vigente (null se non lo dice o se la
    fotografia non c'e')."""
    bonus_regola = bonus_regola or {}
    date_iso = [g.isoformat() for g in giorni]
    segnaposto = ",".join("?" * len(date_iso))
    vigenti = {
        r["giorno"]: r
        for r in conn.execute(
            "SELECT * FROM uso_giornaliero"
            f" WHERE dispositivo_id = ? AND giorno IN ({segnaposto})",
            [dispositivo_id, *date_iso],
        ).fetchall()
    }

    voci = []
    for data in date_iso:
        riga = vigenti.get(data)
        if riga is None:
            voci.append(
                {"giorno": data, "totale_minuti": None, "sessioni_minuti": None,
                 "aggiornato_ts": None, "app": [], "categorie": []}
            )
            continue
        dettagli = json.loads(riga["dettagli"])
        # nomi e uso_categorie sono nati in v2.2: le fotografie vecchie non li
        # hanno (tolleranza evolutiva) -> fallback sul pacchetto e lista vuota.
        nomi = dettagli.get("nomi")
        if not isinstance(nomi, dict):
            nomi = {}
        app = []
        for chiave, minuti in sorted(
            _minuti_validi(dettagli.get("uso_minuti")).items(),
            key=lambda voce: (-voce[1], voce[0]),  # minuti decrescenti, poi chiave
        ):
            voce = {"chiave": chiave, "nome": nomi.get(chiave) or chiave, "minuti": minuti}
            _con_limite(voce, limiti.get(chiave), bonus_regola, data)
            app.append(voce)
        categorie = []
        for chiave, minuti in sorted(
            _minuti_validi(dettagli.get("uso_categorie")).items(),
            key=lambda voce: (-voce[1], voce[0]),
        ):
            voce = {"chiave": chiave, "minuti": minuti}
            _con_limite(voce, limiti.get(chiave), bonus_regola, data)
            categorie.append(voce)
        voce_giorno = {
            "giorno": data,
            # (v3.8) Come lo contano media e somma: una riga salvata prima della v3.5
            # con un totale oltre 1440 si legge 0, cosi' i giorni tornano col `totale`.
            "totale_minuti": _totale_valido(riga["totale_minuti"]),
            "sessioni_minuti": _sessioni_minuti(dettagli),  # (v3.5)
        }
        _con_limite(voce_giorno, limite_totale, bonus_regola, data)  # (v3.3) accanto al totale
        voce_giorno.update({"aggiornato_ts": riga["ts_server"], "app": app, "categorie": categorie})
        voci.append(voce_giorno)
    return voci


# (v3.8) Il totale_minuti di una fotografia come lo contano media e somma: lo stesso
# criterio di routes/figlio.py `_totale_minuti`, che lo applica gia' quando salva
# (un intero da 0 a 1440, altrimenti 0). Qui si riapplica in lettura per le righe
# salvate prima della v3.5, quando oltre 1440 si accettava ancora: la fotografia
# conta come giorno con dati, ma vale 0 e non sporca ne' la media ne' la somma.
_TOTALE_VALIDO = (
    "CASE WHEN typeof(totale_minuti) = 'integer' AND totale_minuti BETWEEN 0 AND ?"
    " THEN totale_minuti ELSE 0 END"
)


def medie(conn: sqlite3.Connection, dispositivo_id: int | None, oggi: date) -> dict:
    """(S1) Media dei minuti d'uso sui SOLI giorni con una fotografia, su due
    finestre: settimana (ultimi 7 giorni locali) e mese (ultimi 30). Ogni voce e'
    {"minuti": intero, "giorni": quanti giorni avevano dati, "totale": somma},
    oppure None se nella finestra non c'e' nessuna fotografia — MAI uno zero finto.
    `giorno` in uso_giornaliero e' gia' il giorno LOCALE del patto (contratto-api.md),
    quindi il confronto stringa e' corretto nel fuso senza conversioni; i giorni
    assenti non sono righe, cosi' l'AVG non li conta (la regola "solo giorni con
    dati" e' rispettata per costruzione). Un giorno con totale_minuti=0 e' una
    fotografia reale (uso zero) e va contato: l'AVG lo include. (v3) Di un
    dispositivo. (v3.8) `totale` = la somma degli STESSI giorni della media, dalla
    stessa query: media, giorni e totale non possono raccontare giorni diversi."""
    def media(giorni_finestra: int) -> dict | None:
        inizio = (oggi - timedelta(days=giorni_finestra - 1)).isoformat()
        r = conn.execute(
            f"SELECT AVG({_TOTALE_VALIDO}) AS m, SUM({_TOTALE_VALIDO}) AS s, COUNT(*) AS n"
            " FROM uso_giornaliero WHERE dispositivo_id = ? AND giorno >= ? AND giorno <= ?",
            (MINUTI_IN_UN_GIORNO, MINUTI_IN_UN_GIORNO, dispositivo_id, inizio, oggi.isoformat()),
        ).fetchone()
        n = r["n"]
        if not n:
            return None
        return {"minuti": round(r["m"]), "giorni": n, "totale": r["s"]}

    return {"settimana": media(7), "mese": media(30)}


def tempi(
    conn: sqlite3.Connection,
    ora: datetime,
    giorni: list,
    dispositivo_id: int | None,
    righe_regole: list,
) -> dict:
    """`uso_recente`, `medie` e `bonus_giornalieri` di un dispositivo, come li danno
    la finestra del genitore e (v3.8, i primi due) il patto del figlio. `righe_regole`
    = regole del figlio in ordine di id (servono le limite_tempo attive del
    dispositivo: le altre si ignorano)."""
    limiti, limite_totale = limiti_attivi(righe_regole, dispositivo_id)
    minuti_per_giorno, bonus_regola = bonus_della_finestra(conn, ora, dispositivo_id)
    return {
        "uso_recente": uso_recente(conn, dispositivo_id, giorni, limiti, bonus_regola, limite_totale),
        "medie": medie(conn, dispositivo_id, ora.astimezone(fuso_patto()).date()),
        "bonus_giornalieri": [
            {"giorno": g.isoformat(), "minuti": minuti_per_giorno[g.isoformat()]} for g in giorni
        ],
    }
