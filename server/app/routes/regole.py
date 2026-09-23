import json
import sqlite3
from datetime import datetime, timedelta

from fastapi import APIRouter, Depends, HTTPException
from pydantic import ValidationError

from .. import clock, famiglia, lock
from ..auth import Identita, richiede_dispositivo, richiede_patto
from ..config import LOCK_GIORNI
from ..db import accoda_notifica, get_conn, registra_modifica
from ..schemas import RegolaCrea, RegolaPatch, chiave_adatta, valida_parametri

router = APIRouter()


def _valida_o_422(tipo: str, parametri: dict, tipo_dispositivo: str | None = None) -> dict:
    """I parametri per il tipo della regola e (v3) la chiave per il tipo del
    dispositivo: un telefono non ha exe: ne' sito:, un computer non ha pacchetti."""
    try:
        validi = valida_parametri(tipo, parametri)
    except ValidationError as errore:
        raise HTTPException(
            status_code=422,
            detail=[{"loc": list(e["loc"]), "msg": e["msg"]} for e in errore.errors()],
        )
    if (
        tipo == "limite_tempo"
        and tipo_dispositivo is not None
        and not chiave_adatta(tipo_dispositivo, validi["app_o_categoria"])
    ):
        attese = (
            "exe:<programma>, sito:<dominio> o categoria:*"
            if tipo_dispositivo == "computer"
            else "il nome di un pacchetto Android o categoria:*"
        )
        raise HTTPException(
            status_code=422,
            detail=[{
                "loc": ["parametri", "app_o_categoria"],
                "msg": f"chiave non adatta a un {tipo_dispositivo}: servono {attese}",
            }],
        )
    return validi


def _riga_regola(riga: sqlite3.Row, dispositivi: dict) -> dict:
    """La regola come la vedono le app. `dispositivi` = {id: riga} dei dispositivi
    del figlio: (v3) ogni regola porta il suo dispositivo, cosi' le app scrivono
    "sul computer" senza un'altra chiamata (null per la vita reale)."""
    sblocco = datetime.fromisoformat(riga["ultima_modifica_ts"]) + timedelta(days=LOCK_GIORNI)
    dispositivo = dispositivi.get(riga["dispositivo_id"])
    return {
        "id": riga["id"],
        "tipo": riga["tipo"],
        "parametri": json.loads(riga["parametri"]),
        "attiva": bool(riga["attiva"]),
        "creata_ts": riga["creata_ts"],
        "ultima_modifica_ts": riga["ultima_modifica_ts"],
        "allentabile_dal": clock.iso(sblocco),
        "figlio_id": riga["figlio_id"],
        "dispositivo_id": riga["dispositivo_id"],
        "dispositivo": famiglia.riferimento(dispositivo) if dispositivo is not None else None,
    }


def dispositivi_per_id(conn: sqlite3.Connection, figlio_id: int) -> dict:
    return {d["id"]: d for d in famiglia.dispositivi_del_figlio(conn, figlio_id)}


def formatta_regola(conn: sqlite3.Connection, riga: sqlite3.Row) -> dict:
    return _riga_regola(riga, dispositivi_per_id(conn, riga["figlio_id"]))


def tipo_dispositivo(conn: sqlite3.Connection, regola: sqlite3.Row) -> str | None:
    """Il tipo del dispositivo della regola (None per la vita reale)."""
    if regola["dispositivo_id"] is None:
        return None
    riga = conn.execute(
        "SELECT tipo FROM dispositivi WHERE id = ?", (regola["dispositivo_id"],)
    ).fetchone()
    return riga["tipo"] if riga is not None else None


def regola_del_figlio_o_errore(
    conn: sqlite3.Connection, regola_id: int, figlio_id: int
) -> sqlite3.Row:
    """La regola ATTIVA `regola_id` del figlio che chiama: 404 se non c'e' o non e'
    piu' attiva, (v3) 403 se e' di un altro figlio."""
    riga = conn.execute("SELECT * FROM regole WHERE id = ?", (regola_id,)).fetchone()
    if riga is None:
        raise HTTPException(status_code=404, detail="regola non trovata")
    if riga["figlio_id"] != figlio_id:
        raise HTTPException(status_code=403, detail="regola di un altro figlio")
    if not riga["attiva"]:
        raise HTTPException(status_code=404, detail="regola non trovata")
    return riga


def verifica_non_ultima(conn: sqlite3.Connection, figlio_id: int) -> None:
    # concept.md: almeno una regola obbligatoria. Vale anche per l'eliminazione
    # concordata nata da proposta accettata. (v3) Per figlio, contando le regole di
    # tutti i suoi dispositivi.
    attive = conn.execute(
        "SELECT COUNT(*) AS n FROM regole WHERE attiva = 1 AND figlio_id = ?", (figlio_id,)
    ).fetchone()["n"]
    if attive <= 1:
        raise HTTPException(status_code=409, detail={"errore": "ultima_regola"})


def applica_modifica(
    conn: sqlite3.Connection,
    riga: sqlite3.Row,
    parametri_dopo: dict,
    concordata: bool,
    ora: datetime,
) -> sqlite3.Row:
    """Scrive la modifica di una regola attiva (senza controllare il lock: lo fa
    chi chiama), la registra nello storico e notifica il genitore. Condivisa dal
    PATCH col figlio e dall'auto-applicazione di una proposta accettata."""
    parametri_prima = json.loads(riga["parametri"])
    direzione = "allenta" if lock.allenta(riga["tipo"], parametri_prima, parametri_dopo) else "stringe"
    ts = clock.iso(ora)
    conn.execute(
        "UPDATE regole SET parametri = ?, ultima_modifica_ts = ? WHERE id = ?",
        (json.dumps(parametri_dopo), ts, riga["id"]),
    )
    registra_modifica(
        conn, riga["id"], "modifica", direzione, parametri_prima, parametri_dopo, concordata, ts
    )
    accoda_notifica(
        conn,
        "modifica_regola",
        f"Regola {riga['id']} ({riga['tipo']}) modificata ({direzione})",
        {
            "regola_id": riga["id"],
            "azione": "modifica",
            "direzione": direzione,
            "concordata": concordata,
            "prima": parametri_prima,
            "dopo": parametri_dopo,
        },
        ts,
        figlio_id=riga["figlio_id"],
        dispositivo_id=riga["dispositivo_id"],
    )
    return conn.execute("SELECT * FROM regole WHERE id = ?", (riga["id"],)).fetchone()


def applica_eliminazione(
    conn: sqlite3.Connection, riga: sqlite3.Row, concordata: bool, ora: datetime
) -> None:
    """Soft-delete di una regola attiva (senza controllare lock ne' ultima_regola:
    lo fa chi chiama), registrato e notificato. Condivisa dal DELETE del figlio e
    dall'auto-applicazione di una proposta di eliminazione accettata."""
    ts = clock.iso(ora)
    parametri_prima = json.loads(riga["parametri"])
    conn.execute(
        "UPDATE regole SET attiva = 0, ultima_modifica_ts = ? WHERE id = ?", (ts, riga["id"])
    )
    registra_modifica(
        conn, riga["id"], "eliminazione", "allenta", parametri_prima, None, concordata, ts
    )
    accoda_notifica(
        conn,
        "modifica_regola",
        f"Regola {riga['id']} ({riga['tipo']}) eliminata",
        {
            "regola_id": riga["id"],
            "azione": "eliminazione",
            "concordata": concordata,
            "prima": parametri_prima,
        },
        ts,
        figlio_id=riga["figlio_id"],
        dispositivo_id=riga["dispositivo_id"],
    )


def _annulla_proposte_pendenti(conn: sqlite3.Connection, riga: sqlite3.Row, ts: str) -> None:
    """L'eliminazione diretta di una regola annulla le sue proposte pendenti (v2.1):
    non ha senso rispondere a una proposta su una regola che non esiste piu'. Il
    genitore viene avvisato; rispondere a una annullata -> 409 proposta_non_pendente."""
    pendenti = conn.execute(
        "SELECT id FROM proposte WHERE regola_id = ? AND stato = 'pendente'", (riga["id"],)
    ).fetchall()
    for p in pendenti:
        conn.execute("UPDATE proposte SET stato = 'annullata' WHERE id = ?", (p["id"],))
        accoda_notifica(
            conn,
            "proposta_annullata",
            "Proposta annullata: la regola collegata e' stata eliminata",
            {"proposta_id": p["id"], "regola_id": riga["id"], "motivo": "regola_eliminata"},
            ts,
            destinatario="genitore",
            figlio_id=riga["figlio_id"],
            dispositivo_id=riga["dispositivo_id"],
        )


def _controlla_lock(riga: sqlite3.Row, ora: datetime) -> None:
    sblocco = datetime.fromisoformat(riga["ultima_modifica_ts"]) + timedelta(days=LOCK_GIORNI)
    if ora < sblocco:
        raise HTTPException(
            status_code=409,
            detail={
                "errore": "lock_attivo",
                "secondi_rimanenti": int((sblocco - ora).total_seconds()),
                "sblocco_ts": clock.iso(sblocco),
            },
        )


# Marcatore dei parametri_proposti per una proposta di ELIMINAZIONE (db.py):
# il DELETE concordato vale solo se la proposta accettata dice esattamente questo.
MARCATORE_ELIMINA = {"azione": "elimina"}


def _consuma_proposta(
    conn: sqlite3.Connection, proposta_id: int, regola_id: int, parametri_attesi: dict
) -> None:
    """Una modifica e' concordata solo se nasce da una proposta accettata e mai usata,
    e applica ESATTAMENTE i parametri concordati: il proposta_id sblocca il lock dei
    4 giorni solo per quei parametri, non per quello che il client decide di mandare.
    Parametri diversi -> 409 parametri_non_concordati e la proposta NON si consuma."""
    riga = conn.execute("SELECT * FROM proposte WHERE id = ?", (proposta_id,)).fetchone()
    if (
        riga is None
        or riga["regola_id"] != regola_id
        or riga["stato"] != "accettata"
        or riga["usata"]
    ):
        raise HTTPException(status_code=400, detail={"errore": "proposta_non_valida"})
    proposti = json.loads(riga["parametri_proposti"]) if riga["parametri_proposti"] else None
    if proposti != parametri_attesi:
        raise HTTPException(status_code=409, detail={"errore": "parametri_non_concordati"})
    conn.execute("UPDATE proposte SET usata = 1 WHERE id = ?", (proposta_id,))


@router.get("/regole")
def elenca_regole(
    figlio_id: int | None = None,
    chi: Identita = Depends(richiede_patto),
    conn: sqlite3.Connection = Depends(get_conn),
):
    """Il patto vigente: le regole attive del figlio, di tutti i suoi dispositivi
    piu' la vita reale (v3). Per un dispositivo il figlio e' quello del token (un
    figlio_id nella query si ignora); per il genitore quello di `figlio_id`, o il
    primo."""
    if chi.ruolo == "dispositivo":
        figlio = chi.figlio_id
    else:
        figlio = famiglia.figlio_scelto(conn, figlio_id)["id"]
    dispositivi = dispositivi_per_id(conn, figlio)
    righe = conn.execute(
        "SELECT * FROM regole WHERE attiva = 1 AND figlio_id = ? ORDER BY id", (figlio,)
    ).fetchall()
    return {"regole": [_riga_regola(r, dispositivi) for r in righe]}


@router.post("/regole", status_code=201)
def crea_regola(
    corpo: RegolaCrea,
    chi: Identita = Depends(richiede_dispositivo),
    conn: sqlite3.Connection = Depends(get_conn),
):
    dispositivo_id = None
    tipo = None
    if corpo.tipo != "vita_reale":
        # (v3) Una regola di tempo o di fascia vale su un dispositivo: quello che
        # chiama, o un altro dello STESSO figlio (una regola del computer si puo'
        # scrivere anche dal telefono). Mai su quello di un altro figlio.
        destinazione = corpo.dispositivo_id if corpo.dispositivo_id is not None else chi.dispositivo_id
        dispositivo = conn.execute(
            "SELECT * FROM dispositivi WHERE id = ?", (destinazione,)
        ).fetchone()
        if dispositivo is None or dispositivo["figlio_id"] != chi.figlio_id:
            raise HTTPException(status_code=403, detail="dispositivo di un altro figlio")
        if dispositivo["revocato_ts"] is not None:
            raise HTTPException(status_code=409, detail={"errore": "dispositivo_revocato"})
        dispositivo_id, tipo = dispositivo["id"], dispositivo["tipo"]
    parametri = _valida_o_422(corpo.tipo, corpo.parametri, tipo)
    ts = clock.iso(clock.now())
    cursore = conn.execute(
        "INSERT INTO regole"
        " (tipo, parametri, attiva, creata_ts, ultima_modifica_ts, figlio_id, dispositivo_id)"
        " VALUES (?, ?, 1, ?, ?, ?, ?)",
        (corpo.tipo, json.dumps(parametri), ts, ts, chi.figlio_id, dispositivo_id),
    )
    regola_id = cursore.lastrowid
    registra_modifica(conn, regola_id, "creazione", None, None, parametri, False, ts)
    accoda_notifica(
        conn,
        "modifica_regola",
        f"Nuova regola {corpo.tipo} creata",
        {"regola_id": regola_id, "azione": "creazione", "parametri": parametri},
        ts,
        figlio_id=chi.figlio_id,
        dispositivo_id=dispositivo_id,
    )
    conn.commit()
    riga = conn.execute("SELECT * FROM regole WHERE id = ?", (regola_id,)).fetchone()
    return formatta_regola(conn, riga)


@router.patch("/regole/{regola_id}")
def modifica_regola(
    regola_id: int,
    corpo: RegolaPatch,
    chi: Identita = Depends(richiede_dispositivo),
    conn: sqlite3.Connection = Depends(get_conn),
):
    ora = clock.now()
    # BEGIN IMMEDIATE: rileggi-regola, controlla-lock/consuma-proposta e applica
    # devono essere atomici. Senza, un PATCH e un'accettazione di proposta (o due
    # PATCH) concorrenti leggono lo stesso 'prima' e scrivono uno storico bugiardo,
    # o consumano due volte la stessa proposta. Il lock di scrittura serializza;
    # chi arriva secondo rilegge i parametri e lo stato proposta gia' aggiornati.
    conn.execute("BEGIN IMMEDIATE")
    try:
        riga = regola_del_figlio_o_errore(conn, regola_id, chi.figlio_id)
        parametri_prima = json.loads(riga["parametri"])
        parametri_dopo = _valida_o_422(riga["tipo"], corpo.parametri, tipo_dispositivo(conn, riga))

        # La proposta serve SOLO a scavalcare il lock di un allentamento:
        # su una stretta (gia' immediata) il proposta_id si ignora e non si consuma.
        concordata = False
        if lock.allenta(riga["tipo"], parametri_prima, parametri_dopo):
            if corpo.proposta_id is not None:
                _consuma_proposta(conn, corpo.proposta_id, regola_id, parametri_dopo)
                concordata = True
            else:
                _controlla_lock(riga, ora)

        aggiornata = applica_modifica(conn, riga, parametri_dopo, concordata, ora)
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    return formatta_regola(conn, aggiornata)


@router.delete("/regole/{regola_id}")
def elimina_regola(
    regola_id: int,
    proposta_id: int | None = None,
    chi: Identita = Depends(richiede_dispositivo),
    conn: sqlite3.Connection = Depends(get_conn),
):
    ora = clock.now()
    # BEGIN IMMEDIATE: rileggi-regola, verifica-non-ultima e soft-delete atomici.
    # Senza, due DELETE su regole diverse leggono entrambi 2 attive, passano il
    # controllo ultima_regola e lasciano ZERO regole attive. Il lock serializza;
    # chi arriva secondo rilegge il conteggio delle attive gia' sceso.
    conn.execute("BEGIN IMMEDIATE")
    try:
        riga = regola_del_figlio_o_errore(conn, regola_id, chi.figlio_id)
        verifica_non_ultima(conn, chi.figlio_id)

        concordata = False
        if proposta_id is not None:
            # L'eliminazione concordata vale solo se la proposta accettata era
            # proprio un'eliminazione (marcatore {"azione": "elimina"}, db.py).
            _consuma_proposta(conn, proposta_id, regola_id, MARCATORE_ELIMINA)
            concordata = True
        if not concordata:
            _controlla_lock(riga, ora)  # eliminare = sempre allentare

        applica_eliminazione(conn, riga, concordata, ora)
        # (v2.1) le proposte pendenti su questa regola non hanno piu' oggetto.
        _annulla_proposte_pendenti(conn, riga, clock.iso(ora))
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    return {"id": regola_id, "eliminata": True}
