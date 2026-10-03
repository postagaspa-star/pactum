"""Proposte (tappa 5). Chi propone non impone: il server calcola il confronto col
valore attuale (per la notifica all'altro) e, quando l'altro ACCETTA, applica da
solo la modifica concordata (lock bypassato, parametri esatti, concordata=true
nello storico). Niente secondo passaggio dall'app.

(v3.4) Fino alla v3.3 proponeva solo il genitore e rispondeva solo il figlio. Ora
propone anche il figlio (da qualsiasi suo dispositivo) e risponde il genitore: ogni
proposta ha il suo `autore` e risponde sempre l'altro. Una sola pendente per regola,
chiunque l'abbia fatta. Chi ha proposto puo' ritirare la proposta finche' e'
pendente.

(v3.6) Piu' genitori, tutti uguali: ogni proposta del genitore dice quale (`genitore`),
e la risposta di un genitore a una proposta del figlio dice chi ha risposto
(`risposta_di`). Nei messaggi per il figlio c'e' il nome del genitore al posto di "Il
genitore"."""

import json
import sqlite3
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException

from .. import clock, config, confronto, famiglia
from ..auth import Identita, richiede_patto
from ..db import accoda_notifica, get_conn
from ..genitori import Firme, ancora_valido
from ..schemas import ProponiIn, RispostaPropostaIn
from .regole import (
    MARCATORE_ELIMINA,
    _valida_o_422,
    applica_eliminazione,
    applica_modifica,
    formatta_regola,
    regola_del_figlio_o_errore,
    tipo_dispositivo,
    verifica_dispositivo_vivo,
    verifica_non_ultima,
)

router = APIRouter()

ELENCO_MASSIMO = 50

# Il confronto di una proposta di eliminazione: il campo `confronto`, che le app
# mostrano com'e'. (v3.4) Nei messaggi delle notifiche, che hanno gia' il loro verbo,
# l'eliminazione si scrive DI_ELIMINARE: "Marta propone di eliminare la regola", non
# "Marta propone: propone di eliminare la regola".
CONFRONTO_ELIMINA = "propone di eliminare la regola"
DI_ELIMINARE = "di eliminare la regola"


def _autore(chi: Identita) -> str:
    """(v3.4) Chi chiama, con le parole delle proposte: il genitore, oppure il figlio
    da uno qualsiasi dei suoi dispositivi (tutti i suoi dispositivi sono lui)."""
    return "genitore" if chi.ruolo == "genitore" else "figlio"


def _risposta(riga: sqlite3.Row) -> dict | None:
    if riga["risposta_esito"] is None:
        return None
    return {
        "esito": riga["risposta_esito"],
        "motivazione": riga["risposta_motivazione"],
        "ts_server": riga["risposta_ts"],
    }


def _parametri(riga: sqlite3.Row) -> dict | None:
    return json.loads(riga["parametri_proposti"]) if riga["parametri_proposti"] else None


def _nomi_del_figlio(conn: sqlite3.Connection, figlio_id: int) -> confronto.Nomi:
    """(v3.4) Le etichette delle app e dei programmi del figlio per il confronto
    (contratto, "I nomi nel confronto"): le stesse del `nome` della finestra. Si
    leggono solo se servono, cioe' quando il bersaglio di un'app cambia, e una volta
    sola."""
    letti: dict = {}

    def nome(chiave: str) -> str | None:
        if "nomi" not in letti:
            oggi = clock.now().astimezone(config.fuso_patto()).date()
            letti["nomi"] = famiglia.nomi_recenti(conn, figlio_id, oggi)
        return letti["nomi"].get(chiave)

    return nome


def _confronto_vivo(
    conn: sqlite3.Connection, regola_id: int, parametri_proposti: dict | None
) -> tuple[str, str] | None:
    """Il confronto ricalcolato vs i parametri ATTUALI della regola (v2.1): serve
    perche' chi risponde decida sempre su un confronto vero anche se la regola e'
    cambiata dopo la proposta. None se non si puo' ricalcolare (regola sparita o
    parametri assenti): si tiene allora il confronto congelato. (v3.4) Coi nomi
    delle app di adesso: un'etichetta arrivata dopo la proposta si vede subito."""
    if parametri_proposti is None:
        return None
    regola = conn.execute(
        "SELECT r.tipo, r.parametri, r.figlio_id, d.tipo AS tipo_dispositivo FROM regole r"
        " LEFT JOIN dispositivi d ON d.id = r.dispositivo_id WHERE r.id = ? AND r.attiva = 1",
        (regola_id,),
    ).fetchone()
    if regola is None:
        return None
    if parametri_proposti == MARCATORE_ELIMINA:
        return CONFRONTO_ELIMINA, "elimina"
    return confronto.confronto_e_direzione(
        regola["tipo"], json.loads(regola["parametri"]), parametri_proposti, regola["tipo_dispositivo"],
        _nomi_del_figlio(conn, regola["figlio_id"]),
    )


def _confronto_del_momento(conn: sqlite3.Connection, proposta: sqlite3.Row) -> tuple[str, str]:
    """Il confronto da congelare su una proposta che si chiude (risposta o, v3.4,
    ritiro): quello vs la regola com'e' ADESSO, o il congelato se non si ricalcola."""
    vivo = _confronto_vivo(conn, proposta["regola_id"], _parametri(proposta))
    return vivo if vivo is not None else (proposta["confronto"], proposta["direzione"])


def formatta_proposta(
    riga: sqlite3.Row, conn: sqlite3.Connection | None = None, firme: Firme | None = None
) -> dict:
    """(v3.6) `firme`: i genitori letti una volta per chi formatta un elenco; senza, si
    leggono qui. Le righe di prima della v3.6 valgono come del genitore 1."""
    confronto_txt = riga["confronto"]
    direzione = riga["direzione"]
    if firme is None and conn is not None:
        firme = Firme(conn)
    # (v3.6) Chi tra i genitori: chi ha proposto (solo sulle proposte del genitore) e chi
    # ha risposto (solo a una proposta del figlio, una volta risposta).
    genitore = risposta_di = None
    if firme is not None and riga["autore"] == "genitore":
        genitore = firme.di_o_primo(riga["genitore_id"])
    if firme is not None and riga["autore"] == "figlio" and riga["risposta_esito"] is not None:
        risposta_di = firme.di_o_primo(riga["risposta_genitore_id"])
    # Solo le pendenti si ricalcolano in lettura (v2.1), (v3.4) di tutti e due gli
    # autori: le chiuse conservano il confronto congelato al momento della risposta
    # (o del ritiro).
    if conn is not None and riga["stato"] == "pendente":
        vivo = _confronto_vivo(conn, riga["regola_id"], _parametri(riga))
        if vivo is not None:
            confronto_txt, direzione = vivo
    return {
        "id": riga["id"],
        "regola_id": riga["regola_id"],
        "parametri_proposti": _parametri(riga),
        "motivazione": riga["motivazione"],
        "confronto": confronto_txt,
        "direzione": direzione,
        "stato": riga["stato"],
        "usata": bool(riga["usata"]),
        "ts_server": riga["ts_server"],
        "risposta": _risposta(riga),
        "autore": riga["autore"],  # (v3.4)
        "genitore": genitore,  # (v3.6)
        "risposta_di": risposta_di,
    }


def _proposta_o_404(conn: sqlite3.Connection, proposta_id: int) -> sqlite3.Row:
    riga = conn.execute("SELECT * FROM proposte WHERE id = ?", (proposta_id,)).fetchone()
    if riga is None:
        raise HTTPException(status_code=404, detail="proposta non trovata")
    return riga


def _della_regola(conn: sqlite3.Connection, proposta: sqlite3.Row, chi: Identita) -> sqlite3.Row:
    """Il figlio e il dispositivo della regola della proposta. (v3) Un dispositivo
    tocca solo le proposte del suo figlio, di qualsiasi suo dispositivo: 403 le altre.
    (v3.4) Una proposta la cui regola non c'e' piu' (tolta a mano dal database) non
    e' di nessuno: 404 come una proposta che non c'e', mai un errore del server."""
    della_regola = conn.execute(
        "SELECT figlio_id, dispositivo_id FROM regole WHERE id = ?", (proposta["regola_id"],)
    ).fetchone()
    if della_regola is None:
        raise HTTPException(status_code=404, detail="proposta non trovata")
    if chi.ruolo == "dispositivo" and della_regola["figlio_id"] != chi.figlio_id:
        raise HTTPException(status_code=403, detail="proposta per un altro figlio")
    return della_regola


def _nome_figlio(conn: sqlite3.Connection, figlio_id: int) -> str:
    return famiglia.figlio_o_404(conn, figlio_id)["nome"]


@router.post("/proposte")
def crea_proposta(
    corpo: ProponiIn,
    chi: Identita = Depends(richiede_patto),
    conn: sqlite3.Connection = Depends(get_conn),
):
    # (v3.4) Propongono il genitore e il figlio. Il figlio propone sulle regole sue
    # (di qualsiasi suo dispositivo o di vita reale): il figlio e' quello del token,
    # un figlio_id nel corpo si ignora.
    autore = _autore(chi)
    figlio_id = chi.figlio_id if autore == "figlio" else corpo.figlio_id
    # BEGIN IMMEDIATE: il controllo "una sola pendente per regola" deve essere
    # atomico, altrimenti due POST simultanei leggono entrambi "nessuna pendente"
    # e ne creano due. Il lock di scrittura serializza i concorrenti; chi arriva
    # secondo rilegge la pendente gia' creata e viene respinto. (v3.1) Dentro il
    # lock anche la lettura della regola e il controllo "dispositivo revocato": una
    # revoca (o un'eliminazione) che arriva in mezzo non lascia nascere una proposta
    # su una regola che non si puo' piu' modificare. (v3.4) Vale per tutti e due gli
    # autori: una proposta del genitore e una del figlio sulla stessa regola si
    # mettono in fila allo stesso modo, e la seconda trova la prima.
    ts = clock.iso(clock.now())
    conn.execute("BEGIN IMMEDIATE")
    try:
        ancora_valido(conn, chi)  # (v3.6) un genitore non revocato nel frattempo
        if autore == "genitore" and figlio_id is not None:
            famiglia.figlio_o_404(conn, figlio_id)
        riga = conn.execute(
            "SELECT * FROM regole WHERE id = ? AND attiva = 1", (corpo.regola_id,)
        ).fetchone()
        if riga is None or (figlio_id is not None and riga["figlio_id"] != figlio_id):
            # Regola inesistente, non attiva o (v3) non di quel figlio: non c'e' niente
            # da proporre. (v3.4) Per il figlio la stessa risposta del genitore.
            raise HTTPException(status_code=409, detail={"errore": "regola_non_valida"})
        verifica_dispositivo_vivo(conn, riga)  # (v3.1) 409 dispositivo_revocato

        elimina = corpo.parametri_proposti == MARCATORE_ELIMINA
        if elimina:
            parametri = MARCATORE_ELIMINA
            testo = CONFRONTO_ELIMINA
            direzione = "elimina"
        else:
            tipo_del_dispositivo = tipo_dispositivo(conn, riga)
            parametri = _valida_o_422(riga["tipo"], corpo.parametri_proposti, tipo_del_dispositivo)
            testo, direzione = confronto.confronto_e_direzione(
                riga["tipo"], json.loads(riga["parametri"]), parametri, tipo_del_dispositivo,
                _nomi_del_figlio(conn, riga["figlio_id"]),  # (v3.4) i nomi delle app
            )

        # (v3.4) Una sola pendente per regola, CHIUNQUE l'abbia fatta: sulla stessa
        # regola non ci sono mai due richieste incrociate.
        gia_pendente = conn.execute(
            "SELECT 1 FROM proposte WHERE regola_id = ? AND stato = 'pendente'",
            (corpo.regola_id,),
        ).fetchone()
        if gia_pendente is not None:
            raise HTTPException(status_code=409, detail={"errore": "proposta_gia_pendente"})
        cursore = conn.execute(
            "INSERT INTO proposte"
            " (regola_id, parametri_proposti, motivazione, confronto, direzione, stato, usata,"
            " ts_server, autore, genitore_id)"
            " VALUES (?, ?, ?, ?, ?, 'pendente', 0, ?, ?, ?)",
            (corpo.regola_id, json.dumps(parametri), corpo.motivazione, testo, direzione, ts, autore,
             chi.genitore_id),
        )
        proposta_id = cursore.lastrowid
        payload = {
            "proposta_id": proposta_id,
            "regola_id": corpo.regola_id,
            "confronto": testo,
            "direzione": direzione,
            "autore": autore,
        }
        # (v3.4) Un'eliminazione si legge senza il verbo ripetuto (DI_ELIMINARE).
        if autore == "genitore":
            # (v3.6) Col nome del genitore che propone, anche nel payload.
            destinatario = "figlio"
            payload["genitore"] = Firme(conn).di(chi.genitore_id)
            nome = payload["genitore"]["nome"]
            messaggio = (
                f"{nome} propone {DI_ELIMINARE}" if elimina else f"Nuova proposta di {nome}: {testo}"
            )
        else:
            # (v3.4) La proposta del figlio la decide il genitore: col nome del figlio,
            # perche' il genitore ne puo' avere piu' d'uno.
            destinatario = "genitore"
            nome = _nome_figlio(conn, riga["figlio_id"])
            messaggio = f"{nome} propone {DI_ELIMINARE}" if elimina else f"{nome} propone: {testo}"
        accoda_notifica(
            conn,
            "nuova_proposta",
            messaggio,
            payload,
            ts,
            destinatario=destinatario,
            # (v3) Sulla regola di un dispositivo: la vede quel dispositivo. Sulla
            # vita reale (dispositivo NULL): tutti i dispositivi del figlio. (v3.4)
            # Al genitore lo stesso: dice di quale dispositivo e' la regola.
            figlio_id=riga["figlio_id"],
            dispositivo_id=riga["dispositivo_id"],
        )
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    return formatta_proposta(_proposta_o_404(conn, proposta_id), conn)


def proposte_del_figlio(
    conn: sqlite3.Connection,
    figlio_id: int,
    solo_pendenti: bool = False,
    autore: str | None = None,
) -> list[sqlite3.Row]:
    """(v3) Le proposte sulle regole del figlio (di tutti i suoi dispositivi), dalla
    piu' recente: una proposta su una regola del computer si vede e si accetta
    anche dal telefono. (v3.4) `autore` ('genitore' o 'figlio') tiene solo quelle
    fatte da lui; senza, quelle di tutti e due."""
    filtro = " AND p.stato = 'pendente'" if solo_pendenti else ""
    parametri: list = [figlio_id]
    if autore is not None:
        filtro += " AND p.autore = ?"
        parametri.append(autore)
    limite = "" if solo_pendenti else f" LIMIT {ELENCO_MASSIMO}"
    return conn.execute(
        "SELECT p.* FROM proposte p JOIN regole r ON r.id = p.regola_id"
        f" WHERE r.figlio_id = ?{filtro} ORDER BY p.id DESC{limite}",
        parametri,
    ).fetchall()


@router.get("/proposte")
def elenca_proposte(
    figlio_id: int | None = None,
    autori: Literal["tutti"] | None = None,
    chi: Identita = Depends(richiede_patto),
    conn: sqlite3.Connection = Depends(get_conn),
):
    """(v3.4) Senza parametri come prima: solo le proposte del genitore. Le app 0.8 e
    0.9 (e il programma del computer non ancora aggiornato) non sanno di `autore` e
    scambierebbero una proposta del figlio per una del genitore. Con ?autori=tutti
    quelle di tutti e due gli autori; un altro valore di `autori` -> 422."""
    if chi.ruolo == "dispositivo":
        figlio = chi.figlio_id
    else:
        figlio = famiglia.figlio_scelto(conn, figlio_id)["id"]
    autore = None if autori == "tutti" else "genitore"
    # conn passato: il confronto delle pendenti si ricalcola vs la regola attuale (v2.1).
    firme = Firme(conn)
    return {
        "proposte": [
            formatta_proposta(r, conn, firme) for r in proposte_del_figlio(conn, figlio, autore=autore)
        ]
    }


@router.post("/proposte/{proposta_id}/risposta")
def rispondi_proposta(
    proposta_id: int,
    corpo: RispostaPropostaIn,
    chi: Identita = Depends(richiede_patto),
    conn: sqlite3.Connection = Depends(get_conn),
):
    """(v3.4) Risponde sempre l'altro: il figlio (da qualsiasi suo dispositivo) alle
    proposte del genitore, il genitore a quelle del figlio. Alla propria -> 403."""
    chi_risponde = _autore(chi)
    if chi_risponde == "genitore" and corpo.figlio_id is not None:
        famiglia.figlio_o_404(conn, corpo.figlio_id)  # come gli altri endpoint del genitore
    ora = clock.now()
    ts = clock.iso(ora)
    regola_risultante = None

    # BEGIN IMMEDIATE: leggi-stato, applica-modifica e chiudi-proposta devono
    # essere un unico atto. Senza, due "accetta" simultanei leggono entrambi
    # 'pendente' e applicano due volte (o, con due elimina su regole diverse,
    # svuotano il patto). Il lock serializza; chi arriva secondo rilegge lo stato
    # gia' aggiornato (proposta non piu' pendente, conteggio regole attive nuovo).
    conn.execute("BEGIN IMMEDIATE")
    try:
        ancora_valido(conn, chi)  # (v3.6)
        proposta = _proposta_o_404(conn, proposta_id)
        # (v3) Si risponde alle proposte del proprio figlio, di qualsiasi suo dispositivo.
        della_regola = _della_regola(conn, proposta, chi)
        figlio_id = della_regola["figlio_id"]
        if chi_risponde == "genitore" and corpo.figlio_id is not None and corpo.figlio_id != figlio_id:
            raise HTTPException(status_code=404, detail="proposta non trovata")
        if proposta["autore"] == chi_risponde:
            raise HTTPException(status_code=403, detail="a una proposta risponde l'altro, non chi l'ha fatta")
        dispositivo_della_regola = della_regola["dispositivo_id"]
        if proposta["stato"] != "pendente":
            raise HTTPException(status_code=409, detail={"errore": "proposta_non_pendente"})

        parametri = _parametri(proposta)
        # Confronto del momento della risposta (v2.1): si congela sulla proposta
        # chiusa il confronto vs la regola com'e' ORA, prima di applicare l'accetta.
        confronto_txt, direzione = _confronto_del_momento(conn, proposta)

        if corpo.esito == "accetta":
            # Auto-applicazione atomica della modifica concordata: lock bypassato,
            # parametri esatti della proposta, concordata=true nello storico.
            riga = regola_del_figlio_o_errore(conn, proposta["regola_id"], figlio_id)
            # (v3.1) Una proposta nata prima della revoca non modifica la regola di un
            # dispositivo revocato: accettarla risponde 409 dispositivo_revocato e la
            # proposta resta pendente (rifiutarla si puo', non tocca la regola).
            verifica_dispositivo_vivo(conn, riga)
            # (v3.4) Se accetta il genitore la modifica l'ha decisa lui: niente
            # modifica_regola anche a lui. Lo storico la registra come sempre.
            avvisa_genitore = chi_risponde == "figlio"
            if parametri == MARCATORE_ELIMINA:
                verifica_non_ultima(conn, figlio_id)  # eliminare l'ultima regola resta vietato
                applica_eliminazione(conn, riga, concordata=True, ora=ora, avvisa_genitore=avvisa_genitore)
                regola_risultante = None
            else:
                aggiornata = applica_modifica(
                    conn, riga, parametri, concordata=True, ora=ora, avvisa_genitore=avvisa_genitore
                )
                regola_risultante = formatta_regola(conn, aggiornata)
            stato = "accettata"
            usata = 1
        else:
            stato = "rifiutata"
            usata = 0

        conn.execute(
            "UPDATE proposte SET stato = ?, usata = ?, confronto = ?, direzione = ?,"
            " risposta_esito = ?, risposta_motivazione = ?, risposta_ts = ?,"
            " risposta_genitore_id = ? WHERE id = ?",
            (stato, usata, confronto_txt, direzione, corpo.esito, corpo.motivazione, ts,
             chi.genitore_id, proposta_id),
        )
        payload = {
            "proposta_id": proposta_id,
            "regola_id": proposta["regola_id"],
            "esito": corpo.esito,
            "autore": proposta["autore"],  # (v3.4) di chi era la proposta
        }
        if chi_risponde == "figlio":
            accoda_notifica(
                conn,
                "proposta_risposta",
                f"Il figlio ha risposto alla proposta: {corpo.esito}",
                payload,
                ts,
                destinatario="genitore",
                figlio_id=figlio_id,
                dispositivo_id=dispositivo_della_regola,
            )
        else:
            # (v3.4) La risposta del genitore arriva a TUTTI i dispositivi del figlio
            # (dispositivo_id NULL): il figlio la cerca dove si trova, non per forza sul
            # dispositivo della regola. Un'eliminazione si legge senza il verbo ripetuto.
            # (v3.6) Col nome del genitore che ha risposto, anche nel payload.
            verbo = "accettato" if corpo.esito == "accetta" else "rifiutato"
            payload["genitore"] = Firme(conn).di(chi.genitore_id)
            nome = payload["genitore"]["nome"]
            accoda_notifica(
                conn,
                "proposta_risposta",
                f"{nome} ha {verbo} la tua proposta {DI_ELIMINARE}"
                if parametri == MARCATORE_ELIMINA
                else f"{nome} ha {verbo} la tua proposta: {confronto_txt}",
                payload,
                ts,
                destinatario="figlio",
                figlio_id=figlio_id,
                dispositivo_id=None,
            )
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    return {"proposta": formatta_proposta(_proposta_o_404(conn, proposta_id), conn), "regola": regola_risultante}


@router.post("/proposte/{proposta_id}/ritira")
def ritira_proposta(
    proposta_id: int,
    chi: Identita = Depends(richiede_patto),
    conn: sqlite3.Connection = Depends(get_conn),
):
    """(v3.4) Chi ha proposto ritira la sua proposta finche' e' pendente: il genitore
    le sue, il figlio le sue da qualsiasi suo dispositivo (403 su quelle dell'altro).
    Stato 'ritirata', usata=false, la regola non cambia; il confronto si ferma a com'e'
    adesso, come quando si risponde. La notifica va all'altro."""
    autore = _autore(chi)
    ts = clock.iso(clock.now())
    # BEGIN IMMEDIATE, come la risposta: un "ritira" e un "accetta" che arrivano
    # insieme si mettono in fila; passa il primo, il secondo rilegge lo stato gia'
    # cambiato e riceve 409 proposta_non_pendente.
    conn.execute("BEGIN IMMEDIATE")
    try:
        ancora_valido(conn, chi)  # (v3.6)
        proposta = _proposta_o_404(conn, proposta_id)
        della_regola = _della_regola(conn, proposta, chi)
        if proposta["autore"] != autore:
            raise HTTPException(status_code=403, detail="si ritira solo una proposta propria")
        if proposta["stato"] != "pendente":
            raise HTTPException(status_code=409, detail={"errore": "proposta_non_pendente"})

        confronto_txt, direzione = _confronto_del_momento(conn, proposta)
        conn.execute(
            "UPDATE proposte SET stato = 'ritirata', usata = 0, confronto = ?, direzione = ?"
            " WHERE id = ?",
            (confronto_txt, direzione, proposta_id),
        )
        # Un'eliminazione si dice: "ha ritirato la sua proposta di eliminare la regola".
        di_cosa = f" {DI_ELIMINARE}" if _parametri(proposta) == MARCATORE_ELIMINA else ""
        payload = {"proposta_id": proposta_id, "regola_id": proposta["regola_id"], "autore": autore}
        if autore == "figlio":
            destinatario = "genitore"
            nome = _nome_figlio(conn, della_regola["figlio_id"])
            messaggio = f"{nome} ha ritirato la sua proposta{di_cosa}"
        else:
            # (v3.6) Tutti i genitori sono uguali: ritira anche una proposta di un altro
            # genitore. Il figlio legge il nome di chi l'ha ritirata.
            payload["genitore"] = Firme(conn).di(chi.genitore_id)
            destinatario = "figlio"
            messaggio = f"{payload['genitore']['nome']} ha ritirato la sua proposta{di_cosa}"
        accoda_notifica(
            conn,
            "proposta_ritirata",
            messaggio,
            payload,
            ts,
            destinatario=destinatario,
            # Col dispositivo della regola, come la nuova_proposta che l'aveva annunciata
            # (se e' stato revocato, a tutto il figlio: accoda_notifica).
            figlio_id=della_regola["figlio_id"],
            dispositivo_id=della_regola["dispositivo_id"],
        )
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    return formatta_proposta(_proposta_o_404(conn, proposta_id), conn)
