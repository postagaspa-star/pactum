"""(v3.5) Le Sessioni: il figlio crea una sessione (nome + app del telefono), il
genitore la approva (una volta, e di nuovo a ogni cambio), il figlio la avvia quando
vuole per 1-1440 minuti e la puo' chiudere prima.

- Creazione e controlli: nome 1-40 senza spazi ai bordi, unico senza maiuscole tra le
  sessioni non eliminate del telefono (anche coi nomi dei cambi in attesa); app da 1 a
  200 (doppioni tolti), pacchetti Android o gruppo:apk; nomi con i loro limiti; solo
  sui telefoni (422 su un computer).
- PATCH: in attesa o rifiutata cambia subito; approvata -> modifica_in_attesa sempre
  completa (con richiesta_ts) e la versione approvata resta avviabile.
- Risposta del genitore sulla `versione` che ha visto: approva / rifiuta la sessione o
  il cambio; 409 richiesta_cambiata se intanto il figlio l'ha cambiata, 409
  niente_da_decidere, 409 dispositivo_revocato, figlio_id che non combacia -> 404.
- Avvio e fine: solo approvate, una in corso per telefono, copia congelata, fine
  all'arrivo o a ts_device, scadenza calcolata in lettura.
- Dove si vedono: patto (di questo telefono), finestra e GET /api/sessioni (di tutto il
  figlio), famiglia (quante da approvare), uso_recente (sessioni_minuti).
- Una sessione si tocca solo dal suo telefono: per gli altri dispositivi, e per un
  altro figlio, 404 "sessione non trovata".
- Sotto richieste simultanee: un avvio solo, una decisione sola, un nome solo, e mai
  approvata una lista che il genitore non ha visto."""

import json
import sqlite3
import threading
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

import dati_v24
from aiuti_v3 import auth, dispositivo_abbinato, eventi, nuovo_figlio, regola
from conftest import FIGLIO, GENITORE, ORA_INIZIALE

SCUOLA = ["eu.spaggiari.classevivafamiglia", "com.google.android.apps.classroom"]
NOMI_SCUOLA = {
    "eu.spaggiari.classevivafamiglia": "ClasseViva",
    "com.google.android.apps.classroom": "Classroom",
}
DUOLINGO = "com.duolingo"
TIKTOK = "com.zhiliaoapp.musically"
ORA = "2026-07-14T10:00:00+00:00"
# (v3.6) Il genitore del token d'ambiente, come lo citano le decisioni.
GENITORE_1 = {"id": 1, "nome": "Genitore"}


@pytest.fixture
def famiglia(client):
    """Andrea (figlio 1) col telefono del token d'ambiente (FIGLIO), un tablet (un
    altro telefono) e un computer; Marta col suo telefono."""
    assert client.patch("/api/figli/1", json={"nome": "Andrea"}, headers=GENITORE).status_code == 200
    tablet, tablet_id = dispositivo_abbinato(client, 1, "Tablet", "telefono")
    pc, pc_id = dispositivo_abbinato(client, 1, "Computer", "computer")
    marta = nuovo_figlio(client, "Marta")
    tel_marta, tel_marta_id = dispositivo_abbinato(client, marta["id"], "Telefono di Marta", "telefono")
    return SimpleNamespace(
        tablet=tablet, tablet_id=tablet_id, pc=pc, pc_id=pc_id,
        marta=marta["id"], tel_marta=tel_marta, tel_marta_id=tel_marta_id,
    )


def _crea(client, headers=FIGLIO, **corpo):
    return client.post("/api/sessioni", json={"nome": "Studio", "app": SCUOLA, **corpo}, headers=headers)


def _sessione(client, headers=FIGLIO, **corpo) -> dict:
    risposta = _crea(client, headers, **corpo)
    assert risposta.status_code == 201, risposta.text
    return risposta.json()


def _versione(client, sessione_id) -> int:
    """La versione che il genitore ha sullo schermo: quella di adesso, letta come la
    legge l'app (1 per una sessione che non trova)."""
    for figlio in client.get("/api/famiglia", headers=GENITORE).json()["figli"]:
        for s in _elenco(client, GENITORE, figlio_id=figlio["id"]):
            if s["id"] == sessione_id:
                return s["versione"]
    return 1


def _rispondi(client, sessione_id, esito, headers=GENITORE, versione=None, **extra):
    """La decisione del genitore sulla versione che vede adesso, se non se ne dice
    un'altra."""
    if versione is None:
        versione = _versione(client, sessione_id)
    return client.post(
        f"/api/sessioni/{sessione_id}/risposta",
        json={"esito": esito, "versione": versione, **extra},
        headers=headers,
    )


def _approvata(client, headers=FIGLIO, **corpo) -> dict:
    sessione = _sessione(client, headers, **corpo)
    risposta = _rispondi(client, sessione["id"], "approva")
    assert risposta.status_code == 200, risposta.text
    return risposta.json()


def _patch(client, sessione_id, headers=FIGLIO, **campi):
    return client.patch(f"/api/sessioni/{sessione_id}", json=campi, headers=headers)


def _elimina(client, sessione_id, headers=FIGLIO):
    return client.delete(f"/api/sessioni/{sessione_id}", headers=headers)


def _avvia(client, sessione_id, durata=60, headers=FIGLIO):
    return client.post(
        f"/api/sessioni/{sessione_id}/avvia", json={"durata_minuti": durata}, headers=headers
    )


def _termina(client, headers=FIGLIO, **corpo):
    return client.post("/api/sessioni/in_corso/termina", json=corpo, headers=headers)


def _elenco(client, headers=FIGLIO, **parametri) -> list:
    risposta = client.get("/api/sessioni", headers=headers, params=parametri)
    assert risposta.status_code == 200, risposta.text
    return risposta.json()["sessioni"]


def _notifiche(client, headers, tipo=None) -> list:
    risposta = client.get("/api/notifiche", headers=headers)
    assert risposta.status_code == 200, risposta.text
    return [n for n in risposta.json()["notifiche"] if tipo is None or n["tipo"] == tipo]


def _nuove(client, headers, gia_viste: set) -> list:
    return [n for n in _notifiche(client, headers) if n["id"] not in gia_viste]


def _ids_notifiche(client, headers) -> set:
    return {n["id"] for n in _notifiche(client, headers)}


def _patto(client, headers=FIGLIO) -> dict:
    risposta = client.get("/api/patto", headers=headers)
    assert risposta.status_code == 200, risposta.text
    return risposta.json()


def _finestra(client, figlio_id=1) -> dict:
    risposta = client.get(f"/api/finestra?figlio_id={figlio_id}", headers=GENITORE)
    assert risposta.status_code == 200, risposta.text
    return risposta.json()


def _da_approvare(client) -> dict:
    risposta = client.get("/api/famiglia", headers=GENITORE)
    assert risposta.status_code == 200, risposta.text
    return {f["id"]: f["sessioni_da_approvare"] for f in risposta.json()["figli"]}


def _errore(risposta, stato, errore) -> None:
    assert risposta.status_code == stato, risposta.text
    assert risposta.json()["detail"] == {"errore": errore}


def _revoca(client, dispositivo_id):
    assert client.delete(f"/api/dispositivi/{dispositivo_id}", headers=GENITORE).status_code == 200


def _ms(dt: datetime) -> int:
    return int(dt.timestamp() * 1000)


def _iso(dt: datetime) -> str:
    return dt.isoformat(timespec="seconds")


def _foto(giorno, totale, **extra) -> dict:
    return {
        "id": f"uso-{giorno}-{uuid.uuid4().hex[:8]}",
        "tipo": "uso_giornaliero",
        "dettagli": {"giorno": giorno, "uso_minuti": {}, "totale_minuti": totale, **extra},
    }


def _riga_svolta(db_path, svolta_id) -> tuple:
    conn = sqlite3.connect(db_path)
    try:
        return conn.execute(
            "SELECT fine_ts, chiusura FROM sessioni_svolte WHERE id = ?", (svolta_id,)
        ).fetchone()
    finally:
        conn.close()


# --- la sessione: creazione e controlli ---

def test_crea_una_sessione_in_attesa(client, famiglia):
    r = _crea(
        client,
        nome="  Studio  ",
        app=[*SCUOLA, SCUOLA[0], "gruppo:apk", SCUOLA[1]],
        nomi={
            "eu.spaggiari.classevivafamiglia": "ClasseViva",
            "com.google.android.apps.classroom": "  Classroom  ",
            "gruppo:apk": "App installate da APK",
            "com.non.nella.lista": "Un'altra app",  # non e' nella lista: si lascia cadere
        },
    )
    assert r.status_code == 201, r.text
    sessione = r.json()
    assert sessione == {
        "id": sessione["id"],
        "dispositivo_id": 1,
        "dispositivo": {"id": 1, "nome": "Telefono", "tipo": "telefono"},
        "nome": "Studio",
        "app": [*SCUOLA, "gruppo:apk"],  # doppioni tolti, nell'ordine mandato
        "nomi": {**NOMI_SCUOLA, "gruppo:apk": "App installate da APK"},
        "stato": "in_attesa",
        "modifica_in_attesa": None,
        "motivazione": None,
        "versione": 1,
        "creata_ts": ORA,
        "approvata_ts": None,
        "decisa_da": None,  # (v3.6) nessun genitore ha ancora deciso
    }
    assert _elenco(client) == [sessione]
    assert _sessione(client, nome="Senza nomi")["nomi"] == {}


def test_il_nome_va_da_1_a_40_caratteri(client, famiglia):
    for nome in ("", "    ", "x" * 41, None, 7):
        assert _crea(client, nome=nome).status_code == 422, nome
    assert _sessione(client, nome=" " + "x" * 40 + " ")["nome"] == "x" * 40
    assert client.post("/api/sessioni", json={"app": SCUOLA}, headers=FIGLIO).status_code == 422


@pytest.mark.parametrize("chiave", [
    "exe:minecraft.exe", "sito:youtube.com", "categoria:social", "categoria:giochi", "totale",
    "TikTok", "gruppo:altro", "GRUPPO:APK", "com..app", "1com.app", "com.1app", "com.app.",
    ".com.app", "com.app con spazi", "com/app.x", "", "a." + "b" * 254,
])
def test_solo_pacchetti_android_o_gruppo_apk(client, famiglia, chiave):
    r = _crea(client, app=[SCUOLA[0], chiave])
    assert r.status_code == 422, r.text
    assert _elenco(client) == []


def test_chiavi_che_vanno_bene(client, famiglia):
    valide = ["com.Slack", "org.telegram.messenger", "a.b", "com.app_1.x2", "gruppo:apk", "a." + "b" * 253]
    assert _sessione(client, app=valide)["app"] == valide


def test_da_1_a_200_app_contate_senza_doppioni(client, famiglia):
    tante = [f"com.app.n{i}" for i in range(200)]
    assert _crea(client, nome="Vuota", app=[]).status_code == 422
    assert _crea(client, nome="Troppe", app=[*tante, "com.app.ancora"]).status_code == 422
    # 250 voci ma 200 app: i doppioni si tolgono prima di contare
    assert _sessione(client, nome="Tante", app=[*tante, *tante[:50]])["app"] == tante
    for app in ("com.whatsapp", [1, 2], None, {"com.whatsapp": 1}):
        assert _crea(client, nome="Strana", app=app).status_code == 422, app


def test_i_limiti_dei_nomi(client, famiglia):
    lunga = "com." + "a" * 251  # 255 caratteri
    assert _crea(client, nome="A", app=[lunga], nomi={lunga: "x" * 100}).status_code == 201
    assert _crea(client, nome="B", nomi={"k" * 256: "x"}).status_code == 422
    assert _crea(client, nome="C", nomi={SCUOLA[0]: "x" * 101}).status_code == 422
    assert _crea(client, nome="D", nomi={SCUOLA[0]: 3}).status_code == 422
    assert _crea(client, nome="E", nomi=["ClasseViva"]).status_code == 422
    # le etichette vuote non dicono niente: si lasciano cadere
    s = _sessione(client, nome="F", nomi={SCUOLA[0]: "   ", SCUOLA[1]: "Classroom"})
    assert s["nomi"] == {SCUOLA[1]: "Classroom"}


def test_il_nome_e_unico_senza_maiuscole_sul_telefono(client, famiglia):
    studio = _sessione(client, nome="Studio")
    for nome in ("Studio", "studio", "  STUDIO "):
        _errore(_crea(client, nome=nome), 409, "nome_gia_usato")
    _sessione(client, nome="Città")
    _errore(_crea(client, nome="CITTÀ"), 409, "nome_gia_usato")
    # su un altro telefono dello stesso figlio, e per un altro figlio, lo stesso nome va bene
    _sessione(client, famiglia.tablet, nome="Studio")
    _sessione(client, famiglia.tel_marta, nome="studio")
    # un'altra sessione non lo prende; la propria puo' cambiarne le maiuscole
    lavoro = _sessione(client, nome="Lavoro")
    _errore(_patch(client, lavoro["id"], nome="STUDIO"), 409, "nome_gia_usato")
    assert _patch(client, studio["id"], nome="STUDIO").json()["nome"] == "STUDIO"
    # eliminata, il nome torna libero
    assert _elimina(client, studio["id"]).status_code == 200
    assert _crea(client, nome="studio").status_code == 201


def test_il_nome_di_un_cambio_in_attesa_e_gia_preso(client, famiglia):
    """Se il genitore approvasse il cambio, due sessioni si chiamerebbero uguali."""
    studio = _approvata(client, nome="Studio")
    assert _patch(client, studio["id"], nome="Compiti").status_code == 200
    _errore(_crea(client, nome="compiti"), 409, "nome_gia_usato")
    lavoro = _sessione(client, nome="Lavoro")
    _errore(_patch(client, lavoro["id"], nome="COMPITI"), 409, "nome_gia_usato")
    # e il nome approvato resta della sessione finche' il cambio non passa
    _errore(_crea(client, nome="Studio"), 409, "nome_gia_usato")
    assert _rispondi(client, studio["id"], "rifiuta").status_code == 200
    assert _crea(client, nome="Compiti").status_code == 201


def test_il_nome_in_forma_nfc_e_senza_caratteri_invisibili(client, famiglia):
    """La stessa parola scritta in due modi e' lo stesso nome (NFC); un nome con
    caratteri che non si vedono o che comandano la scrittura potrebbe sembrarne un
    altro: 422."""
    s = _sessione(client, nome="Città")  # a + accento grave da combinare
    assert s["nome"] == "Città"
    _errore(_crea(client, nome="CITTÀ"), 409, "nome_gia_usato")
    for nome in ("Stu​dio", "Stu‍dio", "‮oidutS", "﻿Studio", "Stu\ndio",
                 "Stu\tdio", "Stu\x00dio", "Stu dio", "Stu­dio"):
        r = _crea(client, nome=nome)
        assert r.status_code == 422, (repr(nome), r.text)
    assert _patch(client, s["id"], nome="Mi​o").status_code == 422
    assert [x["nome"] for x in _elenco(client)] == ["Città"]
    # le etichette si ripuliscono e basta: un'app vera puo' averne, non si rifiuta la sessione
    pulita = _sessione(client, nome="Lettura", app=["com.app.lettura"],
                       nomi={"com.app.lettura": " Let​tu‮ra\n"})
    assert pulita["nomi"] == {"com.app.lettura": "Lettura"}


def test_al_massimo_20_sessioni_per_telefono(client, famiglia):
    for i in range(20):
        _sessione(client, nome=f"Sessione {i}", app=[f"com.app.n{i}"])
    _errore(_crea(client, nome="Ventunesima"), 409, "troppe_sessioni")
    # le eliminate non contano
    assert _elimina(client, _elenco(client)[0]["id"]).status_code == 200
    assert _crea(client, nome="Ventunesima").status_code == 201
    _errore(_crea(client, nome="Ventiduesima"), 409, "troppe_sessioni")
    # ogni telefono ha le sue
    assert _crea(client, famiglia.tablet, nome="Del tablet").status_code == 201


def test_le_sessioni_ci_sono_solo_sui_telefoni(client, famiglia):
    r = _crea(client, famiglia.pc)
    assert r.status_code == 422, r.text
    assert r.json()["detail"][0]["msg"] == "le sessioni ci sono solo sui telefoni"
    assert _elenco(client, famiglia.pc) == []
    assert _notifiche(client, GENITORE, "sessione_da_approvare") == []
    patto = _patto(client, famiglia.pc)
    assert (patto["sessioni"], patto["sessione_in_corso"], patto["sessioni_svolte"]) == ([], None, [])
    assert _termina(client, famiglia.pc).status_code == 404


def test_i_ruoli(client, famiglia):
    s = _sessione(client)
    assert client.post("/api/sessioni", json={"nome": "X", "app": SCUOLA}, headers=GENITORE).status_code == 403
    assert _patch(client, s["id"], GENITORE, nome="X").status_code == 403
    assert _elimina(client, s["id"], GENITORE).status_code == 403
    assert _avvia(client, s["id"], headers=GENITORE).status_code == 403
    assert _termina(client, GENITORE).status_code == 403
    assert _rispondi(client, s["id"], "approva", headers=FIGLIO).status_code == 403
    assert client.get("/api/sessioni").status_code == 401
    assert client.get("/api/sessioni", headers=auth("token-che-non-esiste")).status_code == 401
    assert _elenco(client)[0]["stato"] == "in_attesa"


# --- PATCH ---

def test_patch_di_una_sessione_in_attesa_cambia_subito(client, famiglia):
    s = _sessione(client, nome="Studio", nomi=NOMI_SCUOLA)
    gia_viste = _ids_notifiche(client, GENITORE)
    r = _patch(client, s["id"], app=[SCUOLA[0], DUOLINGO])
    assert r.status_code == 200, r.text
    cambiata = r.json()
    # le etichette che servono ancora restano, quelle delle app tolte no
    assert (cambiata["nome"], cambiata["app"], cambiata["nomi"]) == (
        "Studio", [SCUOLA[0], DUOLINGO], {SCUOLA[0]: "ClasseViva"},
    )
    assert (cambiata["stato"], cambiata["modifica_in_attesa"]) == ("in_attesa", None)
    (avviso,) = _nuove(client, GENITORE, gia_viste)
    assert (avviso["tipo"], avviso["messaggio"]) == (
        "sessione_da_approvare", "Andrea chiede di approvare la sessione «Studio»",
    )
    assert avviso["payload"] == {"sessione_id": s["id"], "nome": "Studio", "cambio": False}
    # solo il nome: app e nomi restano; il messaggio dice il nome nuovo
    gia_viste = _ids_notifiche(client, GENITORE)
    cambiata = _patch(client, s["id"], nome="Compiti").json()
    assert (cambiata["nome"], cambiata["app"]) == ("Compiti", [SCUOLA[0], DUOLINGO])
    (avviso,) = _nuove(client, GENITORE, gia_viste)
    assert avviso["messaggio"] == "Andrea chiede di approvare la sessione «Compiti»"
    assert avviso["payload"] == {"sessione_id": s["id"], "nome": "Compiti", "cambio": False}


def test_patch_di_una_rifiutata_torna_in_attesa(client, famiglia):
    s = _sessione(client)
    rifiutata = _rispondi(client, s["id"], "rifiuta", motivazione="troppe app").json()
    assert (rifiutata["stato"], rifiutata["motivazione"]) == ("rifiutata", "troppe app")
    gia_viste = _ids_notifiche(client, GENITORE)
    di_nuovo = _patch(client, s["id"], app=[SCUOLA[0]]).json()
    assert (di_nuovo["stato"], di_nuovo["motivazione"], di_nuovo["app"]) == ("in_attesa", None, [SCUOLA[0]])
    (avviso,) = _nuove(client, GENITORE, gia_viste)
    assert avviso["payload"] == {"sessione_id": s["id"], "nome": "Studio", "cambio": False}


def test_patch_di_una_approvata_aspetta_il_genitore(client, famiglia, orologio):
    s = _approvata(client, nome="Studio", nomi=NOMI_SCUOLA)
    assert s["versione"] == 2  # creata (1) e approvata (2)
    orologio.avanza(minutes=5)
    gia_viste = _ids_notifiche(client, GENITORE)
    nuova_lista = [*SCUOLA, DUOLINGO]
    r = _patch(client, s["id"], app=nuova_lista, nomi={**NOMI_SCUOLA, DUOLINGO: "Duolingo"})
    assert r.status_code == 200, r.text
    dopo = r.json()
    # la versione approvata non cambia
    assert {k: dopo[k] for k in ("stato", "nome", "app", "nomi", "approvata_ts")} == {
        "stato": "approvata", "nome": "Studio", "app": SCUOLA, "nomi": NOMI_SCUOLA,
        "approvata_ts": s["approvata_ts"],
    }
    # il cambio e' sempre completo: il nome che il figlio non ha toccato viene da quella approvata
    assert dopo["modifica_in_attesa"] == {
        "nome": "Studio", "app": nuova_lista, "nomi": {**NOMI_SCUOLA, DUOLINGO: "Duolingo"},
        "richiesta_ts": "2026-07-14T10:05:00+00:00",
    }
    assert dopo["versione"] == 3
    (avviso,) = _nuove(client, GENITORE, gia_viste)
    assert avviso["messaggio"] == "Andrea chiede di cambiare la sessione «Studio»"
    assert avviso["payload"] == {"sessione_id": s["id"], "nome": "Studio", "cambio": True}  # non rinomina
    assert (avviso["figlio_id"], avviso["dispositivo_id"]) == (1, 1)
    # un secondo cambio sostituisce del tutto il primo: qui cambia solo il nome, e app e
    # nomi si copiano di nuovo da quella approvata. Il genitore lo legge col nome che
    # conosce, piu' quello chiesto.
    orologio.avanza(minutes=5)
    gia_viste = _ids_notifiche(client, GENITORE)
    dopo = _patch(client, s["id"], nome="Compiti").json()
    assert dopo["modifica_in_attesa"] == {
        "nome": "Compiti", "app": SCUOLA, "nomi": NOMI_SCUOLA, "richiesta_ts": "2026-07-14T10:10:00+00:00",
    }
    assert dopo["versione"] == 4
    (avviso,) = _nuove(client, GENITORE, gia_viste)
    assert avviso["messaggio"] == "Andrea chiede di cambiare la sessione «Studio»"
    assert avviso["payload"] == {"sessione_id": s["id"], "nome": "Studio", "cambio": True,
                                 "nuovo_nome": "Compiti"}
    # intanto la versione approvata si avvia com'era, e avviarla non cambia la versione
    svolta = _avvia(client, s["id"]).json()
    assert (svolta["nome"], svolta["app"], svolta["nomi"]) == ("Studio", SCUOLA, NOMI_SCUOLA)
    assert _elenco(client)[0]["versione"] == 4


def test_tornare_alla_versione_approvata_ritira_il_cambio(client, famiglia):
    """Un PATCH che rimette proprio la versione approvata (anche con le app in un altro
    ordine) e' il figlio che ritira il suo cambio: niente piu' in attesa, la versione
    cresce, nessun avviso nuovo, e la richiesta che il genitore non aveva letto si
    chiude."""
    s = _approvata(client, nome="Studio", nomi=NOMI_SCUOLA)  # versione 2
    cambiata = _patch(client, s["id"], app=[DUOLINGO]).json()
    assert cambiata["modifica_in_attesa"] is not None and cambiata["versione"] == 3
    assert _da_approvare(client)[1] == 1
    gia_viste = _ids_notifiche(client, GENITORE)
    ritirata = _patch(client, s["id"], app=list(reversed(SCUOLA)), nomi=NOMI_SCUOLA).json()
    assert (ritirata["modifica_in_attesa"], ritirata["versione"]) == (None, 4)
    assert (ritirata["stato"], ritirata["app"], ritirata["nomi"]) == ("approvata", SCUOLA, NOMI_SCUOLA)
    assert _nuove(client, GENITORE, gia_viste) == []
    assert [n for n in _notifiche(client, GENITORE, "sessione_da_approvare")
            if n["payload"]["sessione_id"] == s["id"]] == []
    assert _da_approvare(client)[1] == 0
    # la decisione presa sul cambio di prima non trova piu' niente
    _errore(_rispondi(client, s["id"], "approva", versione=3), 409, "niente_da_decidere")


def test_patch_senza_campi_o_sbagliata_422(client, famiglia):
    s = _sessione(client)
    for corpo in ({}, {"nome": None}, {"nome": None, "app": None, "nomi": None}, {"nome": ""},
                  {"app": []}, {"app": ["exe:minecraft.exe"]}, {"nomi": {SCUOLA[0]: "x" * 101}}):
        assert client.patch(f"/api/sessioni/{s['id']}", json=corpo, headers=FIGLIO).status_code == 422, corpo
    assert _elenco(client) == [s]


# --- la risposta del genitore ---

def test_il_genitore_approva(client, famiglia):
    s = _sessione(client)
    r = _rispondi(client, s["id"], "approva", motivazione="va bene")
    assert r.status_code == 200, r.text
    approvata = r.json()
    assert (approvata["stato"], approvata["approvata_ts"], approvata["motivazione"]) == ("approvata", ORA, None)
    assert approvata["dispositivo"] == {"id": 1, "nome": "Telefono", "tipo": "telefono"}
    (avviso,) = _notifiche(client, FIGLIO, "sessione_risposta")
    assert avviso["messaggio"] == "Genitore ha approvato la sessione «Studio»"
    assert avviso["payload"] == {"sessione_id": s["id"], "nome": "Studio", "esito": "approva", "cambio": False,
                                 "genitore": GENITORE_1}
    assert (avviso["destinatario"], avviso["figlio_id"], avviso["dispositivo_id"]) == ("figlio", 1, 1)
    # solo il telefono della sessione la riceve
    for headers in (famiglia.tablet, famiglia.pc, famiglia.tel_marta, GENITORE):
        assert _notifiche(client, headers, "sessione_risposta") == []


def test_il_genitore_rifiuta(client, famiglia):
    s = _sessione(client)
    rifiutata = _rispondi(client, s["id"], "rifiuta", motivazione="parliamone a cena").json()
    assert (rifiutata["stato"], rifiutata["motivazione"], rifiutata["approvata_ts"]) == (
        "rifiutata", "parliamone a cena", None,
    )
    (avviso,) = _notifiche(client, FIGLIO, "sessione_risposta")
    assert avviso["messaggio"] == "Genitore non ha approvato la sessione «Studio»"
    assert avviso["payload"] == {"sessione_id": s["id"], "nome": "Studio", "esito": "rifiuta", "cambio": False,
                                 "genitore": GENITORE_1}
    _errore(_avvia(client, s["id"]), 409, "sessione_non_approvata")


def test_il_genitore_approva_il_cambio(client, famiglia, orologio):
    s = _approvata(client, nome="Studio", nomi=NOMI_SCUOLA)
    # un cambio rifiutato prima: la motivazione resta, e la versione approvata pure
    assert _patch(client, s["id"], app=[SCUOLA[0]]).status_code == 200
    dopo = _rispondi(client, s["id"], "rifiuta", motivazione="Classroom serve").json()
    assert (dopo["modifica_in_attesa"], dopo["app"], dopo["motivazione"]) == (None, SCUOLA, "Classroom serve")
    orologio.avanza(hours=1)
    nuovo = {"nome": "Compiti", "app": [*SCUOLA, DUOLINGO], "nomi": {**NOMI_SCUOLA, DUOLINGO: "Duolingo"}}
    assert _patch(client, s["id"], **nuovo).status_code == 200
    gia_viste = _ids_notifiche(client, FIGLIO)
    r = _rispondi(client, s["id"], "approva")
    assert r.status_code == 200, r.text
    dopo = r.json()
    assert {k: dopo[k] for k in ("nome", "app", "nomi")} == nuovo
    assert (dopo["stato"], dopo["modifica_in_attesa"], dopo["motivazione"]) == ("approvata", None, None)
    assert dopo["approvata_ts"] == "2026-07-14T11:00:00+00:00"  # approvata adesso, cosi'
    (avviso,) = _nuove(client, FIGLIO, gia_viste)
    # col nome di dopo la decisione: quello che il figlio vede adesso nell'elenco
    assert avviso["messaggio"] == "Genitore ha approvato il cambio alla sessione «Compiti»"
    assert avviso["payload"] == {"sessione_id": s["id"], "nome": "Compiti", "esito": "approva", "cambio": True,
                                 "genitore": GENITORE_1}


def test_il_genitore_rifiuta_il_cambio(client, famiglia):
    s = _approvata(client, nome="Studio", nomi=NOMI_SCUOLA)
    assert _patch(client, s["id"], app=[DUOLINGO]).status_code == 200
    gia_viste = _ids_notifiche(client, FIGLIO)
    dopo = _rispondi(client, s["id"], "rifiuta", motivazione="Duolingo dopo cena").json()
    assert {k: dopo[k] for k in ("stato", "nome", "app", "nomi", "modifica_in_attesa", "motivazione", "approvata_ts")} == {
        "stato": "approvata", "nome": "Studio", "app": SCUOLA, "nomi": NOMI_SCUOLA,
        "modifica_in_attesa": None, "motivazione": "Duolingo dopo cena", "approvata_ts": s["approvata_ts"],
    }
    (avviso,) = _nuove(client, FIGLIO, gia_viste)
    assert avviso["messaggio"] == "Genitore non ha approvato il cambio alla sessione «Studio»"
    assert avviso["payload"] == {"sessione_id": s["id"], "nome": "Studio", "esito": "rifiuta", "cambio": True,
                                 "genitore": GENITORE_1}
    # resta avviabile la versione di prima
    assert _avvia(client, s["id"]).json()["app"] == SCUOLA


def test_niente_da_decidere(client, famiglia):
    approvata = _approvata(client, nome="Studio")
    for esito in ("approva", "rifiuta"):
        _errore(_rispondi(client, approvata["id"], esito), 409, "niente_da_decidere")
        # anche con la versione di prima: gia' deciso e' gia' deciso
        _errore(_rispondi(client, approvata["id"], esito, versione=1), 409, "niente_da_decidere")
    rifiutata = _sessione(client, nome="Lavoro")
    assert _rispondi(client, rifiutata["id"], "rifiuta").status_code == 200
    _errore(_rispondi(client, rifiutata["id"], "approva"), 409, "niente_da_decidere")
    # una sessione che non c'e', o eliminata: 404 "sessione non trovata"
    assert _rispondi(client, 999, "approva").json() == {"detail": "sessione non trovata"}
    eliminata = _sessione(client, nome="Lettura")
    assert _elimina(client, eliminata["id"]).status_code == 200
    r = _rispondi(client, eliminata["id"], "approva", versione=1)
    assert r.status_code == 404 and r.json() == {"detail": "sessione non trovata"}
    assert _rispondi(client, approvata["id"], "forse").status_code == 422


def test_la_versione_cresce_a_ogni_cambio_e_decisione(client, famiglia, orologio):
    s = _sessione(client, nome="Studio")
    assert s["versione"] == 1
    assert _patch(client, s["id"], app=[SCUOLA[0]]).json()["versione"] == 2  # in attesa: cambia subito
    assert _rispondi(client, s["id"], "rifiuta", versione=2).json()["versione"] == 3
    assert _patch(client, s["id"], app=SCUOLA).json()["versione"] == 4  # rifiutata: torna in attesa
    assert _rispondi(client, s["id"], "approva", versione=4).json()["versione"] == 5
    assert _patch(client, s["id"], app=[DUOLINGO]).json()["versione"] == 6  # approvata: cambio in attesa
    assert _rispondi(client, s["id"], "rifiuta", versione=6).json()["versione"] == 7
    assert _patch(client, s["id"], nome="Compiti").json()["versione"] == 8
    assert _rispondi(client, s["id"], "approva", versione=8).json()["versione"] == 9
    # avviare e terminare non cambiano la sessione
    assert _avvia(client, s["id"], 30).status_code == 201
    orologio.avanza(minutes=10)
    assert _termina(client).status_code == 200
    # ovunque compaia, la sessione ha la sua versione
    assert _elenco(client)[0]["versione"] == 9
    assert _patto(client)["sessioni"][0]["versione"] == 9
    assert _finestra(client)["sessioni"][0]["versione"] == 9
    assert _elenco(client, GENITORE)[0]["versione"] == 9


def test_il_genitore_non_approva_una_lista_che_non_ha_visto(client, famiglia):
    """Il genitore ha sullo schermo la versione 1 (ClasseViva e Classroom); intanto il
    figlio aggiunge Duolingo (versione 2). La decisione sulla 1 non passa: 409
    richiesta_cambiata con la sessione com'e' adesso, e niente viene deciso."""
    s = _sessione(client, nome="Studio", nomi=NOMI_SCUOLA)
    cambiata = _patch(client, s["id"], app=[*SCUOLA, DUOLINGO]).json()
    gia_viste = _ids_notifiche(client, FIGLIO)
    for esito in ("approva", "rifiuta"):
        r = _rispondi(client, s["id"], esito, versione=1, motivazione="no")
        assert r.status_code == 409, r.text
        assert r.json()["detail"] == {"errore": "richiesta_cambiata", "sessione": cambiata}
    assert _elenco(client) == [cambiata]  # niente deciso
    assert _nuove(client, FIGLIO, gia_viste) == []
    # sulla versione giusta si'
    assert _rispondi(client, s["id"], "approva", versione=cambiata["versione"]).json()["app"] == [*SCUOLA, DUOLINGO]
    # lo stesso per un cambio: il genitore ha visto il primo, il figlio ne ha chiesto un altro
    assert _patch(client, s["id"], app=[SCUOLA[0]]).status_code == 200
    visto = _elenco(client)[0]
    secondo = _patch(client, s["id"], app=[DUOLINGO]).json()
    r = _rispondi(client, s["id"], "approva", versione=visto["versione"])
    assert r.status_code == 409 and r.json()["detail"] == {"errore": "richiesta_cambiata", "sessione": secondo}
    assert _elenco(client)[0]["app"] == [*SCUOLA, DUOLINGO]  # la versione approvata resta


def test_la_versione_e_obbligatoria_e_la_motivazione_ha_un_limite(client, famiglia):
    s = _sessione(client)  # versione 1
    url = f"/api/sessioni/{s['id']}/risposta"
    # un intero vero: true non combacia con la versione 1, "1" e 1.0 neanche
    for corpo in ({"esito": "approva"}, {"esito": "approva", "versione": None},
                  {"esito": "approva", "versione": "uno"}, {"esito": "approva", "versione": True},
                  {"esito": "approva", "versione": "1"}, {"esito": "approva", "versione": 1.0}):
        assert client.post(url, json=corpo, headers=GENITORE).status_code == 422, corpo
    r = _rispondi(client, s["id"], "rifiuta", motivazione="x" * 501)
    assert r.status_code == 422, r.text
    assert _elenco(client)[0]["stato"] == "in_attesa"
    r = _rispondi(client, s["id"], "rifiuta", motivazione="x" * 500)
    assert r.status_code == 200 and r.json()["motivazione"] == "x" * 500


def test_la_risposta_col_figlio_id(client, famiglia):
    s = _sessione(client)
    for figlio_id in (famiglia.marta, 999):
        r = _rispondi(client, s["id"], "approva", figlio_id=figlio_id)
        assert r.status_code == 404, r.text
    assert _elenco(client)[0]["stato"] == "in_attesa"
    assert _rispondi(client, s["id"], "approva", figlio_id=1).status_code == 200


def test_le_sessioni_di_un_telefono_revocato(client, famiglia):
    """Non si avviano piu' e non si decidono piu': la risposta, in un senso o
    nell'altro, e' 409 dispositivo_revocato. Non si contano tra quelle da approvare,
    ma restano nella finestra."""
    lettura = _sessione(client, famiglia.tablet, nome="Lettura")
    disegno = _approvata(client, famiglia.tablet, nome="Disegno")
    assert _patch(client, disegno["id"], famiglia.tablet, app=[DUOLINGO]).status_code == 200
    assert _da_approvare(client)[1] == 2
    _revoca(client, famiglia.tablet_id)
    assert _da_approvare(client)[1] == 0
    finestra = _finestra(client)
    assert finestra["sessioni_da_approvare"] == 0
    assert [s["nome"] for s in finestra["sessioni"]] == ["Lettura", "Disegno"]
    gia_viste = _ids_notifiche(client, FIGLIO)
    for sessione_id in (lettura["id"], disegno["id"]):
        for esito in ("approva", "rifiuta"):
            _errore(_rispondi(client, sessione_id, esito), 409, "dispositivo_revocato")
    assert [(s["stato"], s["modifica_in_attesa"] is not None) for s in _elenco(client, GENITORE)] == [
        ("in_attesa", False), ("approvata", True),
    ]
    assert _nuove(client, FIGLIO, gia_viste) == []


def test_la_revoca_arrivata_in_mezzo(client, famiglia):
    """Il token di un dispositivo revocato risponde 401. Se la revoca arriva dopo il
    controllo del token, dentro il lock: 409 dispositivo_revocato, e niente cambia. La
    si simula facendo passare il token."""
    from app.auth import Identita, richiede_dispositivo

    lettura = _approvata(client, famiglia.tablet, nome="Lettura")
    _revoca(client, famiglia.tablet_id)
    assert client.get("/api/sessioni", headers=famiglia.tablet).status_code == 401
    client.app.dependency_overrides[richiede_dispositivo] = lambda: Identita(
        ruolo="dispositivo", dispositivo_id=famiglia.tablet_id, figlio_id=1, tipo="telefono"
    )
    try:
        for r in (
            _avvia(client, lettura["id"], headers=famiglia.tablet),
            _crea(client, famiglia.tablet, nome="Nuova"),
            _patch(client, lettura["id"], famiglia.tablet, nome="Altra"),
            _elimina(client, lettura["id"], famiglia.tablet),
        ):
            _errore(r, 409, "dispositivo_revocato")
    finally:
        client.app.dependency_overrides.clear()
    (rimasta,) = _finestra(client)["sessioni"]
    assert {k: rimasta[k] for k in ("id", "nome", "modifica_in_attesa")} == {
        "id": lettura["id"], "nome": "Lettura", "modifica_in_attesa": None,
    }
    assert _finestra(client)["sessioni_svolte"] == []


# --- le notifiche ---

def test_gli_avvisi_al_genitore(client, famiglia):
    studio = _sessione(client, nome="Studio")
    (nuova,) = _notifiche(client, GENITORE, "sessione_da_approvare")
    assert nuova["messaggio"] == "Andrea chiede di approvare la sessione «Studio»"
    assert nuova["payload"] == {"sessione_id": studio["id"], "nome": "Studio", "cambio": False}
    assert (nuova["destinatario"], nuova["figlio_id"], nuova["dispositivo_id"]) == ("genitore", 1, 1)
    # dal tablet: col dispositivo della sessione
    lettura = _sessione(client, famiglia.tablet, nome="Lettura")
    (dal_tablet,) = [n for n in _notifiche(client, GENITORE, "sessione_da_approvare")
                     if n["payload"]["sessione_id"] == lettura["id"]]
    assert dal_tablet["dispositivo_id"] == famiglia.tablet_id
    # Marta: col nome di Marta
    piano = _sessione(client, famiglia.tel_marta, nome="Piano")
    (da_marta,) = [n for n in _notifiche(client, GENITORE, "sessione_da_approvare")
                   if n["payload"]["sessione_id"] == piano["id"]]
    assert da_marta["messaggio"] == "Marta chiede di approvare la sessione «Piano»"
    assert (da_marta["figlio_id"], da_marta["dispositivo_id"]) == (famiglia.marta, famiglia.tel_marta_id)
    # eliminata
    assert _elimina(client, studio["id"]).status_code == 200
    (via,) = _notifiche(client, GENITORE, "sessione_eliminata")
    assert via["messaggio"] == "Andrea ha eliminato la sessione «Studio»"
    assert via["payload"] == {"sessione_id": studio["id"], "nome": "Studio"}
    assert (via["figlio_id"], via["dispositivo_id"]) == (1, 1)
    # ai dispositivi non arriva niente di tutto questo
    for headers in (FIGLIO, famiglia.tablet, famiglia.pc, famiglia.tel_marta):
        assert [n for n in _notifiche(client, headers) if n["tipo"].startswith("sessione")] == []


def _richieste_aperte(client, sessione_id) -> list:
    return [n for n in _notifiche(client, GENITORE, "sessione_da_approvare")
            if n["payload"]["sessione_id"] == sessione_id]


def test_un_solo_avviso_aperto_per_sessione(client, famiglia):
    """Una richiesta nuova sulla stessa sessione prende il posto di quella che il
    genitore non ha ancora letto (che si segna come letta); quando il genitore decide,
    o il figlio elimina la sessione, la richiesta aperta si chiude."""
    studio = _sessione(client, nome="Studio")
    lavoro = _sessione(client, nome="Lavoro")
    (prima,) = _richieste_aperte(client, studio["id"])
    assert _patch(client, studio["id"], app=[DUOLINGO]).status_code == 200
    (seconda,) = _richieste_aperte(client, studio["id"])
    assert seconda["id"] != prima["id"]
    assert len(_richieste_aperte(client, lavoro["id"])) == 1  # le altre sessioni non c'entrano
    assert _rispondi(client, studio["id"], "approva").status_code == 200
    assert _richieste_aperte(client, studio["id"]) == []
    # due cambi di fila: un avviso solo, quello dell'ultimo
    assert _patch(client, studio["id"], app=SCUOLA).status_code == 200
    assert _patch(client, studio["id"], app=[*SCUOLA, DUOLINGO]).status_code == 200
    (cambio,) = _richieste_aperte(client, studio["id"])
    assert cambio["payload"]["cambio"] is True
    # eliminata: la sua richiesta si chiude, resta l'avviso dell'eliminazione
    assert _elimina(client, lavoro["id"]).status_code == 200
    assert _richieste_aperte(client, lavoro["id"]) == []
    assert [n["payload"]["sessione_id"] for n in _notifiche(client, GENITORE, "sessione_eliminata")] == [
        lavoro["id"],
    ]
    # e il conto delle non lette nella famiglia lo dice: il cambio di Studio, l'eliminazione di Lavoro
    (andrea, _) = client.get("/api/famiglia", headers=GENITORE).json()["figli"]
    assert andrea["notifiche_non_lette"] == 2


def test_avvio_e_fine_senza_notifiche(client, famiglia, orologio):
    s = _approvata(client)
    del_genitore, del_figlio = _ids_notifiche(client, GENITORE), _ids_notifiche(client, FIGLIO)
    assert _avvia(client, s["id"], 30).status_code == 201
    orologio.avanza(minutes=10)
    assert _termina(client).status_code == 200
    assert _avvia(client, s["id"], 5).status_code == 201
    orologio.avanza(minutes=6)  # scaduta da sola
    assert _patto(client)["sessione_in_corso"] is None
    assert _nuove(client, GENITORE, del_genitore) == []
    assert _nuove(client, FIGLIO, del_figlio) == []


def test_la_risposta_si_legge_sul_telefono_della_sessione(client, famiglia):
    lettura = _sessione(client, famiglia.tablet, nome="Lettura")
    assert _rispondi(client, lettura["id"], "approva").status_code == 200
    (avviso,) = _notifiche(client, famiglia.tablet, "sessione_risposta")
    assert (avviso["figlio_id"], avviso["dispositivo_id"]) == (1, famiglia.tablet_id)
    # il telefono dello stesso figlio non la riceve e non la marca, Marta neppure
    assert _notifiche(client, FIGLIO, "sessione_risposta") == []
    for headers in (FIGLIO, famiglia.pc, famiglia.tel_marta):
        assert client.post(f"/api/notifiche/{avviso['id']}/letta", headers=headers).status_code == 404
    assert client.post(f"/api/notifiche/{avviso['id']}/letta", headers=famiglia.tablet).status_code == 200
    assert _notifiche(client, famiglia.tablet, "sessione_risposta") == []


# --- avvio e fine ---

def test_avvia_una_sessione_approvata(client, famiglia):
    s = _approvata(client, nome="Studio", nomi=NOMI_SCUOLA)
    r = _avvia(client, s["id"], 120)
    assert r.status_code == 201, r.text
    svolta = r.json()
    assert svolta == {
        "id": svolta["id"], "sessione_id": s["id"], "dispositivo_id": 1,
        "nome": "Studio", "app": SCUOLA, "nomi": NOMI_SCUOLA,
        "inizio_ts": ORA, "durata_minuti": 120, "fine_prevista_ts": "2026-07-14T12:00:00+00:00",
        "fine_ts": None, "chiusura": None, "in_corso": True,
    }
    patto = _patto(client)
    assert patto["sessione_in_corso"] == svolta
    assert patto["sessioni_svolte"] == [svolta]


def test_si_avvia_solo_una_sessione_approvata(client, famiglia):
    in_attesa = _sessione(client, nome="Studio")
    _errore(_avvia(client, in_attesa["id"]), 409, "sessione_non_approvata")
    rifiutata = _sessione(client, nome="Lavoro")
    assert _rispondi(client, rifiutata["id"], "rifiuta").status_code == 200
    _errore(_avvia(client, rifiutata["id"]), 409, "sessione_non_approvata")
    assert _avvia(client, 999).status_code == 404
    assert _patto(client)["sessione_in_corso"] is None


def test_la_durata_va_da_1_a_1440_minuti(client, famiglia, orologio):
    s = _approvata(client)
    for durata in (0, -5, 1441, "tanto", None, 2.5, True, "30", 30.0):  # interi veri, niente true
        assert _avvia(client, s["id"], durata).status_code == 422, durata
    assert client.post(f"/api/sessioni/{s['id']}/avvia", json={}, headers=FIGLIO).status_code == 422
    assert client.post(f"/api/sessioni/{s['id']}/avvia", headers=FIGLIO).status_code == 422
    breve = _avvia(client, s["id"], 1).json()
    assert breve["fine_prevista_ts"] == "2026-07-14T10:01:00+00:00"
    orologio.avanza(minutes=1)
    lunga = _avvia(client, s["id"], 1440).json()
    assert lunga["fine_prevista_ts"] == "2026-07-15T10:01:00+00:00"


def test_una_sola_sessione_in_corso_per_telefono(client, famiglia):
    studio = _approvata(client, nome="Studio")
    lavoro = _approvata(client, nome="Lavoro")
    assert _avvia(client, studio["id"]).status_code == 201
    _errore(_avvia(client, studio["id"]), 409, "sessione_gia_in_corso")
    _errore(_avvia(client, lavoro["id"]), 409, "sessione_gia_in_corso")
    # il tablet dello stesso figlio ha la sua
    lettura = _approvata(client, famiglia.tablet, nome="Lettura")
    assert _avvia(client, lettura["id"], headers=famiglia.tablet).status_code == 201
    # chiusa quella del telefono, se ne avvia un'altra
    assert _termina(client).status_code == 200
    assert _avvia(client, lavoro["id"]).status_code == 201


def test_il_cambio_approvato_vale_dalla_prossima(client, famiglia, orologio):
    s = _approvata(client, nome="Studio", nomi=NOMI_SCUOLA)
    prima = _avvia(client, s["id"], 60).json()
    assert _patch(client, s["id"], nome="Compiti", app=[DUOLINGO], nomi={DUOLINGO: "Duolingo"}).status_code == 200
    assert _rispondi(client, s["id"], "approva").status_code == 200
    assert _patto(client)["sessione_in_corso"] == prima  # congelata: com'era quando e' partita
    orologio.avanza(minutes=61)
    dopo = _avvia(client, s["id"], 30).json()
    assert (dopo["nome"], dopo["app"], dopo["nomi"]) == ("Compiti", [DUOLINGO], {DUOLINGO: "Duolingo"})
    assert [(x["nome"], x["app"], x["chiusura"]) for x in _patto(client)["sessioni_svolte"]] == [
        ("Compiti", [DUOLINGO], None), ("Studio", SCUOLA, "scaduta"),
    ]


def test_termina_all_arrivo(client, famiglia, orologio):
    s = _approvata(client)
    _avvia(client, s["id"], 120)
    orologio.avanza(minutes=45)
    r = client.post("/api/sessioni/in_corso/termina", headers=FIGLIO)  # anche senza corpo
    assert r.status_code == 200, r.text
    chiusa = r.json()
    assert (chiusa["fine_ts"], chiusa["chiusura"], chiusa["in_corso"]) == (
        "2026-07-14T10:45:00+00:00", "terminata", False,
    )
    # la durata resta quella decisa: il genitore vede che l'ha chiusa prima
    assert (chiusa["durata_minuti"], chiusa["fine_prevista_ts"]) == (120, "2026-07-14T12:00:00+00:00")
    assert _patto(client)["sessione_in_corso"] is None
    assert _finestra(client)["sessioni_svolte"] == [chiusa]
    r = _termina(client)
    assert r.status_code == 404 and r.json() == {"detail": "nessuna sessione in corso"}


def test_termina_con_ts_device(client, famiglia, orologio):
    """Chiusa sul telefono senza rete alle 10:20, consegnata alle 10:50: vale le 10:20."""
    s = _approvata(client)
    _avvia(client, s["id"], 120)
    orologio.avanza(minutes=50)
    r = _termina(client, ts_device=_ms(ORA_INIZIALE + timedelta(minutes=20)) + 500)
    assert r.status_code == 200, r.text
    assert (r.json()["fine_ts"], r.json()["chiusura"]) == ("2026-07-14T10:20:00+00:00", "terminata")


@pytest.mark.parametrize("ts_device", [
    _ms(ORA_INIZIALE + timedelta(minutes=4)),  # prima dell'inizio (10:05)
    _ms(ORA_INIZIALE + timedelta(minutes=36)),  # dopo l'arrivo (10:35): orologio un po' avanti
    _ms(ORA_INIZIALE + timedelta(minutes=40)),  # 5 minuti avanti: mai una fine nel futuro
    _ms(ORA_INIZIALE + timedelta(minutes=33, seconds=30)),  # 90 secondi prima: chiusura con la rete
    _ms(ORA_INIZIALE + timedelta(minutes=33)),  # 2 minuti esatti prima: ancora con la rete
    9 * 10**18, -9 * 10**18,  # nessuna data possibile
])
def test_ts_device_che_non_conta_vale_l_arrivo(client, famiglia, orologio, ts_device):
    """ts_device conta solo per una chiusura fatta senza rete: piu' di 2 minuti prima
    dell'arrivo (sotto e' una chiusura con la rete e un orologio un po' storto), dopo
    l'inizio, mai dopo l'arrivo. Altrimenti vale l'arrivo."""
    s = _approvata(client)
    orologio.avanza(minutes=5)
    _avvia(client, s["id"], 120)
    orologio.avanza(minutes=30)
    r = _termina(client, ts_device=ts_device)
    assert r.status_code == 200, r.text
    assert r.json()["fine_ts"] == "2026-07-14T10:35:00+00:00"


def test_ts_device_oltre_i_2_minuti_vale_lui(client, famiglia, orologio):
    s = _approvata(client)
    _avvia(client, s["id"], 120)
    orologio.avanza(minutes=30)
    r = _termina(client, ts_device=_ms(ORA_INIZIALE + timedelta(minutes=27, seconds=59)))
    assert r.status_code == 200, r.text
    assert r.json()["fine_ts"] == "2026-07-14T10:27:59+00:00"


def test_una_chiusura_consegnata_oltre_48_ore_non_vale(client, famiglia, orologio):
    """Una chiusura fatta senza rete vale da quando e' stata fatta solo se arriva entro
    48 ore; dopo, la sessione resta com'e' finita da sola (scaduta) e la risposta e' 404.
    Una chiusura gia' scritta non si riscrive mai."""
    studio = _approvata(client, nome="Studio")
    _avvia(client, studio["id"], 60)  # 10:00 - 11:00
    chiusa_alle = ORA_INIZIALE + timedelta(minutes=30)
    orologio.vai_a(chiusa_alle + timedelta(hours=48, minutes=1))
    assert _termina(client, ts_device=_ms(chiusa_alle)).status_code == 404
    svolte = _patto(client)["sessioni_svolte"]
    assert [(x["chiusura"], x["fine_ts"]) for x in svolte] == [("scaduta", "2026-07-14T11:00:00+00:00")]
    # entro le 48 ore invece vale
    lavoro = _approvata(client, nome="Lavoro")
    partita = _avvia(client, lavoro["id"], 60).json()
    chiusa_alle = datetime.fromisoformat(partita["inizio_ts"]) + timedelta(minutes=20)
    orologio.vai_a(chiusa_alle + timedelta(hours=47))
    r = _termina(client, ts_device=_ms(chiusa_alle), svolta_id=partita["id"])
    assert r.status_code == 200, r.text
    assert (r.json()["chiusura"], r.json()["fine_ts"]) == ("terminata", _iso(chiusa_alle))
    # gia' scritta: un'altra chiusura non la tocca
    assert _termina(client, svolta_id=partita["id"]).status_code == 404
    assert _termina(client, ts_device=_ms(chiusa_alle - timedelta(minutes=5))).status_code == 404
    (rimasta,) = [x for x in _finestra(client)["sessioni_svolte"] if x["id"] == partita["id"]]
    assert (rimasta["chiusura"], rimasta["fine_ts"]) == ("terminata", _iso(chiusa_alle))


def test_una_chiusura_senza_rete_consegnata_dopo_la_fine(client, famiglia, orologio):
    """Chiusa sul telefono prima della fine prevista e consegnata quando la durata era
    gia' finita: vale da quando e' stata fatta. Finche' non arriva, si legge scaduta."""
    s = _approvata(client)
    _avvia(client, s["id"], 60)
    orologio.avanza(minutes=90)
    (prima,) = _patto(client)["sessioni_svolte"]
    assert (prima["chiusura"], prima["fine_ts"]) == ("scaduta", "2026-07-14T11:00:00+00:00")
    r = _termina(client, ts_device=_ms(ORA_INIZIALE + timedelta(minutes=40)))
    assert r.status_code == 200, r.text
    assert (r.json()["chiusura"], r.json()["fine_ts"]) == ("terminata", "2026-07-14T10:40:00+00:00")
    (dopo,) = _patto(client)["sessioni_svolte"]
    assert dopo == r.json()


def test_una_chiusura_vecchia_non_chiude_la_sessione_nuova(client, famiglia, orologio):
    """La chiusura della sessione di prima, rimasta in coda sul telefono, arriva dopo
    l'avvio di un'altra: con svolta_id non chiude quella nuova (404). Senza svolta_id
    vale il contratto di sempre (i test qui sopra)."""
    studio = _approvata(client, nome="Studio")
    lavoro = _approvata(client, nome="Lavoro")
    prima = _avvia(client, studio["id"], 30).json()
    orologio.avanza(minutes=40)  # la prima e' finita da sola alle 10:30
    nuova = _avvia(client, lavoro["id"], 60).json()
    orologio.avanza(minutes=5)
    vecchia = _termina(client, svolta_id=prima["id"], ts_device=_ms(ORA_INIZIALE + timedelta(minutes=20)))
    assert vecchia.status_code == 404, vecchia.text
    assert _patto(client)["sessione_in_corso"] == nuova
    giusta = _termina(client, svolta_id=nuova["id"])
    assert giusta.status_code == 200, giusta.text
    assert (giusta.json()["id"], giusta.json()["fine_ts"]) == (nuova["id"], "2026-07-14T10:45:00+00:00")
    assert _termina(client, svolta_id=nuova["id"]).status_code == 404  # gia' chiusa
    assert _termina(client, svolta_id=999).status_code == 404
    # svolta_id e' un intero vero: true non e' la sessione svolta 1
    terza = _avvia(client, studio["id"], 30).json()
    for falso in (True, "1", 1.0):
        assert _termina(client, svolta_id=falso).status_code == 422, falso
    assert _patto(client)["sessione_in_corso"] == terza


def test_dopo_la_fine_prevista_non_c_e_niente_da_terminare(client, famiglia, orologio):
    """Mai oltre la fine prevista: una chiusura che arriva (o che dice di essere stata
    fatta) dopo la fine prevista trova la sessione gia' finita da sola."""
    s = _approvata(client)
    _avvia(client, s["id"], 60)
    orologio.avanza(minutes=61)
    assert _termina(client).status_code == 404
    assert _termina(client, ts_device=_ms(ORA_INIZIALE + timedelta(minutes=60, seconds=30))).status_code == 404
    assert _termina(client, ts_device=_ms(ORA_INIZIALE + timedelta(minutes=60))).status_code == 404
    (svolta,) = _patto(client)["sessioni_svolte"]
    assert (svolta["chiusura"], svolta["fine_ts"], svolta["in_corso"]) == (
        "scaduta", "2026-07-14T11:00:00+00:00", False,
    )


def test_allo_scadere_si_chiude_da_sola(client, famiglia, orologio, db_path):
    s = _approvata(client)
    svolta = _avvia(client, s["id"], 30).json()
    orologio.avanza(minutes=29, seconds=59)
    assert _patto(client)["sessione_in_corso"]["id"] == svolta["id"]
    orologio.avanza(seconds=1)  # 10:30: finita
    patto = _patto(client)
    assert patto["sessione_in_corso"] is None
    (scaduta,) = patto["sessioni_svolte"]
    assert (scaduta["fine_ts"], scaduta["chiusura"], scaduta["in_corso"]) == (
        "2026-07-14T10:30:00+00:00", "scaduta", False,
    )
    assert _finestra(client)["sessioni_svolte"] == [scaduta]
    # la riga non e' ancora scritta: lo calcola chi legge, nessun processo in sottofondo
    assert _riga_svolta(db_path, svolta["id"]) == (None, None)
    # la scrive il prossimo avvio, che trova il posto libero
    assert _avvia(client, s["id"], 30).status_code == 201
    assert _riga_svolta(db_path, svolta["id"]) == ("2026-07-14T10:30:00+00:00", "scaduta")


# --- eliminare ---

def test_eliminare_la_sessione_in_corso_409(client, famiglia, orologio):
    studio = _approvata(client, nome="Studio")
    lavoro = _approvata(client, nome="Lavoro")
    _avvia(client, studio["id"], 60)
    _errore(_elimina(client, studio["id"]), 409, "sessione_in_corso")
    assert _elimina(client, lavoro["id"]).status_code == 200  # un'altra si', anche durante
    orologio.avanza(minutes=60)  # finita da sola: ora si elimina
    r = _elimina(client, studio["id"])
    assert r.status_code == 200 and r.json() == {"id": studio["id"], "eliminata": True}
    assert _elenco(client) == []
    # la storia resta, col suo nome
    (svolta,) = _patto(client)["sessioni_svolte"]
    assert (svolta["sessione_id"], svolta["nome"], svolta["chiusura"]) == (studio["id"], "Studio", "scaduta")
    # eliminata: non si tocca piu'
    for r in (_patch(client, studio["id"], nome="X"), _elimina(client, studio["id"]),
              _avvia(client, studio["id"]), _rispondi(client, studio["id"], "approva")):
        assert r.status_code == 404, r.text


def test_eliminare_una_sessione_in_attesa(client, famiglia):
    s = _sessione(client)
    assert _da_approvare(client)[1] == 1
    assert _elimina(client, s["id"]).status_code == 200
    assert _da_approvare(client)[1] == 0
    assert _finestra(client)["sessioni"] == []


# --- di chi e' una sessione ---

def test_una_sessione_si_tocca_solo_dal_suo_telefono(client, famiglia):
    """Per un altro dispositivo, dello stesso figlio o di un altro, la sessione non
    c'e': 404 "sessione non trovata", come per una che non esiste (mai il "Not Found"
    generico, che per le app vuol dire "server vecchio")."""
    s = _approvata(client, nome="Studio")
    _avvia(client, s["id"], 60)
    non_trovata = {"detail": "sessione non trovata"}
    for headers in (famiglia.tablet, famiglia.pc, famiglia.tel_marta):
        for r in (_patch(client, s["id"], headers, nome="Mia"), _elimina(client, s["id"], headers),
                  _avvia(client, s["id"], headers=headers)):
            assert r.status_code == 404 and r.json() == non_trovata, r.text
        assert _termina(client, headers).status_code == 404  # non e' la loro: niente in corso
        assert _elenco(client, headers, figlio_id=1) == []  # figlio_id dal dispositivo si ignora
        patto = _patto(client, headers)
        assert (patto["sessioni"], patto["sessione_in_corso"], patto["sessioni_svolte"]) == ([], None, [])
    for r in (_patch(client, 999, nome="X"), _elimina(client, 999), _avvia(client, 999)):
        assert r.status_code == 404 and r.json() == non_trovata, r.text
    (intatta,) = _elenco(client)
    assert (intatta["nome"], intatta["modifica_in_attesa"], intatta["versione"]) == ("Studio", None, 2)
    assert _patto(client)["sessione_in_corso"]["sessione_id"] == s["id"]


def test_un_altro_figlio_non_vede_niente(client, famiglia):
    studio = _approvata(client, nome="Studio")
    _avvia(client, studio["id"], 60)
    piano = _sessione(client, famiglia.tel_marta, nome="Piano")
    finestra_marta = _finestra(client, famiglia.marta)
    assert [s["id"] for s in finestra_marta["sessioni"]] == [piano["id"]]
    assert (finestra_marta["sessioni_da_approvare"], finestra_marta["sessioni_svolte"]) == (1, [])
    assert [s["id"] for s in _elenco(client, GENITORE, figlio_id=famiglia.marta)] == [piano["id"]]
    assert [s["id"] for s in _elenco(client, famiglia.tel_marta)] == [piano["id"]]
    # il genitore non decide "per Marta" su una sessione di Andrea
    r = _rispondi(client, studio["id"], "rifiuta", figlio_id=famiglia.marta)
    assert r.status_code == 404 and r.json() == {"detail": "sessione non trovata"}
    # e Marta non tocca quella di Andrea
    r = _patch(client, studio["id"], famiglia.tel_marta, nome="Mia")
    assert r.status_code == 404 and r.json() == {"detail": "sessione non trovata"}


# --- le etichette che legge il genitore ---

def test_le_etichette_vengono_dalle_fotografie_dell_uso(client, famiglia, db_path):
    """Il genitore non si fa ingannare da un'etichetta scritta apposta ("ClasseViva" su
    TikTok): per un'app che le fotografie dell'uso del figlio conoscono (le stesse del
    `nome` della finestra, da qualsiasi suo dispositivo) vale quella; l'etichetta
    mandata con la sessione resta solo per le app mai viste nell'uso. Ovunque compaia
    la sessione: anche nel cambio in attesa e nella sessione svolta."""
    eventi(client, famiglia.tablet, _foto("2026-07-13", 30, uso_minuti={TIKTOK: 30},
                                          nomi={TIKTOK: "TikTok", DUOLINGO: "Duolingo"}))
    mandati = {TIKTOK: "ClasseViva", SCUOLA[0]: "ClasseViva", "gruppo:apk": "App da APK"}
    s = _sessione(client, nome="Studio", app=[TIKTOK, DUOLINGO, SCUOLA[0], "gruppo:apk"], nomi=mandati)
    attese = {TIKTOK: "TikTok", DUOLINGO: "Duolingo", SCUOLA[0]: "ClasseViva", "gruppo:apk": "App da APK"}
    assert s["nomi"] == attese
    for vista in (_elenco(client), _elenco(client, GENITORE), _patto(client)["sessioni"],
                  _finestra(client)["sessioni"]):
        assert vista[0]["nomi"] == attese
    # nel database resta quella mandata dal telefono
    conn = sqlite3.connect(db_path)
    try:
        assert json.loads(conn.execute("SELECT nomi FROM sessioni WHERE id = ?", (s["id"],)).fetchone()[0]) == mandati
    finally:
        conn.close()
    assert _rispondi(client, s["id"], "approva").status_code == 200
    cambiata = _patch(client, s["id"], app=[TIKTOK, "com.nuova.app"],
                      nomi={TIKTOK: "Compiti", "com.nuova.app": "Nuova"}).json()
    assert cambiata["modifica_in_attesa"]["nomi"] == {TIKTOK: "TikTok", "com.nuova.app": "Nuova"}
    r = _rispondi(client, s["id"], "rifiuta", versione=cambiata["versione"])
    assert r.status_code == 200 and r.json()["nomi"] == attese, r.text
    svolta = _avvia(client, s["id"], 30).json()
    assert svolta["nomi"] == attese
    assert _finestra(client)["sessioni_svolte"][0]["nomi"] == attese
    assert _patto(client)["sessione_in_corso"]["nomi"] == attese


# --- dove si vedono ---

def test_il_patto_ha_le_sessioni_del_telefono(client, famiglia):
    studio = _approvata(client, nome="Studio")
    lavoro = _sessione(client, nome="Lavoro")
    lettura = _approvata(client, famiglia.tablet, nome="Lettura")
    svolta = _avvia(client, studio["id"], 60).json()
    patto = _patto(client)
    assert [s["id"] for s in patto["sessioni"]] == [studio["id"], lavoro["id"]]  # dalla piu' vecchia
    assert patto["sessioni"] == _elenco(client)  # come GET /api/sessioni
    assert patto["sessione_in_corso"] == svolta
    assert patto["sessioni_svolte"] == [svolta]
    del_tablet = _patto(client, famiglia.tablet)
    assert [s["id"] for s in del_tablet["sessioni"]] == [lettura["id"]]
    assert (del_tablet["sessione_in_corso"], del_tablet["sessioni_svolte"]) == (None, [])


def test_la_finestra_ha_le_sessioni_del_figlio(client, famiglia, orologio):
    studio = _approvata(client, nome="Studio")
    lavoro = _sessione(client, nome="Lavoro")  # in attesa
    lettura = _approvata(client, famiglia.tablet, nome="Lettura")
    assert _patch(client, lettura["id"], famiglia.tablet, app=["com.amazon.kindle"]).status_code == 200
    _sessione(client, famiglia.tel_marta, nome="Piano")
    _avvia(client, studio["id"], 30)
    orologio.avanza(minutes=10)
    assert _termina(client).status_code == 200
    orologio.avanza(minutes=5)
    assert _avvia(client, lettura["id"], 60, headers=famiglia.tablet).status_code == 201
    finestra = _finestra(client)
    assert [s["id"] for s in finestra["sessioni"]] == [studio["id"], lavoro["id"], lettura["id"]]
    assert finestra["sessioni"] == _elenco(client, GENITORE, figlio_id=1)
    assert finestra["sessioni_da_approvare"] == 2  # Lavoro in attesa + il cambio di Lettura
    assert [(s["sessione_id"], s["dispositivo_id"], s["chiusura"], s["in_corso"])
            for s in finestra["sessioni_svolte"]] == [
        (lettura["id"], famiglia.tablet_id, None, True),
        (studio["id"], 1, "terminata", False),
    ]
    # ciascuna come la vede il suo telefono
    assert finestra["sessioni_svolte"][1] == _patto(client)["sessioni_svolte"][0]
    assert finestra["sessioni_svolte"][0] == _patto(client, famiglia.tablet)["sessione_in_corso"]


def test_la_famiglia_conta_le_sessioni_da_approvare(client, famiglia):
    assert _da_approvare(client) == {1: 0, famiglia.marta: 0}
    studio = _sessione(client, nome="Studio")
    _sessione(client, famiglia.tablet, nome="Lettura")
    piano = _sessione(client, famiglia.tel_marta, nome="Piano")
    assert _da_approvare(client) == {1: 2, famiglia.marta: 1}
    assert _rispondi(client, studio["id"], "approva").status_code == 200
    assert _rispondi(client, piano["id"], "rifiuta").status_code == 200
    assert _da_approvare(client) == {1: 1, famiglia.marta: 0}
    # un cambio a una approvata conta come una decisione da prendere
    assert _patch(client, studio["id"], app=[DUOLINGO]).status_code == 200
    assert _da_approvare(client) == {1: 2, famiglia.marta: 0}
    assert _finestra(client)["sessioni_da_approvare"] == 2
    # una rifiutata cambiata torna da decidere
    assert _patch(client, piano["id"], famiglia.tel_marta, nome="Pianoforte").status_code == 200
    assert _da_approvare(client) == {1: 2, famiglia.marta: 1}


def test_get_sessioni_del_genitore(client, famiglia):
    studio = _sessione(client, nome="Studio")
    lettura = _sessione(client, famiglia.tablet, nome="Lettura")
    piano = _sessione(client, famiglia.tel_marta, nome="Piano")
    eliminata = _sessione(client, nome="Vecchia")
    assert _elimina(client, eliminata["id"]).status_code == 200
    # senza figlio_id: il primo figlio, come per gli altri endpoint del genitore
    assert [s["id"] for s in _elenco(client, GENITORE)] == [studio["id"], lettura["id"]]
    assert [s["id"] for s in _elenco(client, GENITORE, figlio_id=1)] == [studio["id"], lettura["id"]]
    assert [s["id"] for s in _elenco(client, GENITORE, figlio_id=famiglia.marta)] == [piano["id"]]
    assert client.get("/api/sessioni", params={"figlio_id": 999}, headers=GENITORE).status_code == 404
    (del_tablet,) = [s for s in _elenco(client, GENITORE) if s["id"] == lettura["id"]]
    assert del_tablet["dispositivo"] == {"id": famiglia.tablet_id, "nome": "Tablet", "tipo": "telefono"}


def _inserisci_svolta(db_path, sessione_id, dispositivo_id, inizio, durata, fine=None, chiusura=None) -> int:
    """Una sessione svolta scritta a mano, per avere inizi nel passato."""
    conn = sqlite3.connect(db_path)
    try:
        cursore = conn.execute(
            "INSERT INTO sessioni_svolte (sessione_id, dispositivo_id, nome, app, nomi, inizio_ts,"
            " durata_minuti, fine_prevista_ts, fine_ts, chiusura)"
            " VALUES (?, ?, 'Studio', '[\"com.whatsapp\"]', '{}', ?, ?, ?, ?, ?)",
            (sessione_id, dispositivo_id, _iso(inizio), durata, _iso(inizio + timedelta(minutes=durata)),
             _iso(fine) if fine else None, chiusura),
        )
        conn.commit()
        return cursore.lastrowid
    finally:
        conn.close()


def _utc(giorno, ora, minuti=0) -> datetime:
    return datetime(2026, 7, giorno, ora, minuti, tzinfo=timezone.utc)


def test_le_sessioni_svolte_degli_8_giorni(client, famiglia, orologio, db_path):
    """Quelle che toccano gli 8 giorni della striscia: oggi 14/07, la finestra parte
    dalla mezzanotte del 07/07 a Roma (06/07 22:00 UTC). Anche una partita prima e
    finita dentro; non una finita proprio alla mezzanotte."""
    studio = _approvata(client, nome="Studio")
    lettura = _approvata(client, famiglia.tablet, nome="Lettura")
    s = studio["id"]
    _inserisci_svolta(db_path, s, 1, _utc(5, 20), 1440, _utc(6, 20), "scaduta")  # finita prima
    _inserisci_svolta(db_path, s, 1, _utc(6, 20), 120, _utc(6, 22), "scaduta")  # finita alla mezzanotte
    a_cavallo = _inserisci_svolta(db_path, s, 1, _utc(6, 21), 120, _utc(6, 23), "scaduta")
    dentro = _inserisci_svolta(db_path, s, 1, _utc(10, 8), 30, _utc(10, 8, 10), "terminata")
    del_tablet = _inserisci_svolta(db_path, lettura["id"], famiglia.tablet_id, _utc(12, 10), 60,
                                   _utc(12, 11), "scaduta")
    in_corso = _inserisci_svolta(db_path, s, 1, _utc(14, 9), 120)

    def ids(svolte):
        return [x["id"] for x in svolte]

    patto = _patto(client)
    assert ids(patto["sessioni_svolte"]) == [in_corso, dentro, a_cavallo]  # dalla piu' recente
    assert patto["sessione_in_corso"]["id"] == in_corso
    assert ids(_finestra(client)["sessioni_svolte"]) == [in_corso, del_tablet, dentro, a_cavallo]

    orologio.avanza(days=1)  # 15/07: la finestra parte dal 07/07 22:00 UTC
    svolte = _patto(client)["sessioni_svolte"]
    assert ids(svolte) == [in_corso, dentro]
    assert (svolte[0]["chiusura"], svolte[0]["fine_ts"]) == ("scaduta", "2026-07-14T11:00:00+00:00")
    orologio.avanza(days=7)  # 22/07: niente tocca piu' la finestra
    assert _patto(client)["sessioni_svolte"] == []
    assert _finestra(client)["sessioni_svolte"] == []


def test_al_massimo_200_sessioni_svolte(client, famiglia, db_path):
    s = _approvata(client)
    ids = [
        _inserisci_svolta(db_path, s["id"], 1, _utc(8, 0) + timedelta(minutes=30 * i), 10,
                          _utc(8, 0) + timedelta(minutes=30 * i + 5), "terminata")
        for i in range(210)
    ]
    assert [x["id"] for x in _patto(client)["sessioni_svolte"]] == ids[::-1][:200]
    assert [x["id"] for x in _finestra(client)["sessioni_svolte"]] == ids[::-1][:200]


# --- sessioni_minuti in uso_recente ---

def test_sessioni_minuti_in_uso_recente(client, famiglia):
    r = client.post("/api/eventi", json={"eventi": [
        _foto("2026-07-14", 90, sessioni_minuti=45),
        _foto("2026-07-13", 50),  # un telefono 0.10: non lo dice
        _foto("2026-07-12", 10, sessioni_minuti=-3),
        _foto("2026-07-11", 10, sessioni_minuti="12"),
        _foto("2026-07-10", 10, sessioni_minuti=1.5),
        _foto("2026-07-09", 10, sessioni_minuti=True),
        _foto("2026-07-08", 10, sessioni_minuti=0),
        _foto("2026-07-07", 10, sessioni_minuti=1441),  # piu' di un giorno
    ]}, headers=FIGLIO)
    # i valori sporchi non fanno cadere il pacco: si ignorano
    assert r.status_code == 200 and r.json()["nuovi"] == 8, r.text
    finestra = _finestra(client)
    assert {v["giorno"]: v["sessioni_minuti"] for v in finestra["uso_recente"]} == {
        "2026-07-07": None, "2026-07-08": 0, "2026-07-09": None, "2026-07-10": None,
        "2026-07-11": None, "2026-07-12": None, "2026-07-13": None, "2026-07-14": 45,
    }
    eventi(client, FIGLIO, _foto("2026-07-13", 60, sessioni_minuti=1440))  # il massimo vale
    assert _finestra(client)["uso_recente"][-2]["sessioni_minuti"] == 1440
    (telefono,) = [d for d in finestra["dispositivi"] if d["id"] == 1]
    assert telefono["uso_recente"] == finestra["uso_recente"]
    # il tablet ha la sua fotografia e il suo valore; il primo livello resta il telefono
    eventi(client, famiglia.tablet, _foto("2026-07-14", 20, sessioni_minuti=15))
    finestra = _finestra(client)
    (tablet,) = [d for d in finestra["dispositivi"] if d["id"] == famiglia.tablet_id]
    assert tablet["uso_recente"][-1]["sessioni_minuti"] == 15
    assert finestra["uso_recente"][-1]["sessioni_minuti"] == 45
    # vale la fotografia vigente: una piu' alta che non lo dice lo toglie
    eventi(client, FIGLIO, _foto("2026-07-14", 100))
    assert _finestra(client)["uso_recente"][-1]["sessioni_minuti"] is None


def test_minuti_oltre_un_giorno_non_valgono(client, famiglia):
    """Un totale oltre i 1440 minuti non e' valido (vale 0, come uno mancante) e non
    scavalca la fotografia vera; una voce di uso_minuti o uso_categorie oltre i 1440 si
    lascia cadere. Niente zeri inventati altrove, niente medie sporcate."""
    eventi(client, FIGLIO, _foto("2026-07-14", 100, uso_minuti={"com.whatsapp": 40}))
    eventi(client, FIGLIO, {
        "id": "uso-assurdo", "tipo": "uso_giornaliero",
        "dettagli": {"giorno": "2026-07-14", "totale_minuti": 99999,
                     "uso_minuti": {"com.whatsapp": 5000, "com.duolingo": 30},
                     "uso_categorie": {"categoria:social": 3000}},
    })
    finestra = _finestra(client)
    oggi = finestra["uso_recente"][-1]
    assert oggi["totale_minuti"] == 100  # la fotografia assurda non e' diventata la vigente
    assert finestra["medie"]["settimana"] == {"minuti": 100, "giorni": 1}
    eventi(client, FIGLIO, {
        "id": "uso-ieri", "tipo": "uso_giornaliero",
        "dettagli": {"giorno": "2026-07-13", "totale_minuti": 1441,
                     "uso_minuti": {"com.whatsapp": 2000, "com.duolingo": 1440},
                     "uso_categorie": {"categoria:social": 1441, "categoria:altro": 20}},
    })
    ieri = _finestra(client)["uso_recente"][-2]
    assert ieri["totale_minuti"] == 0  # non valido: vale 0, come uno mancante
    assert [(a["chiave"], a["minuti"]) for a in ieri["app"]] == [("com.duolingo", 1440)]
    assert ieri["categorie"] == [{"chiave": "categoria:altro", "minuti": 20}]


# --- sotto richieste simultanee ---

def test_avvii_simultanei_ne_parte_uno(client, famiglia, db_path):
    studio = _approvata(client, nome="Studio")
    lavoro = _approvata(client, nome="Lavoro")
    quante = 6
    barriera = threading.Barrier(quante)
    esiti = []

    def spara(sessione_id):
        barriera.wait()
        esiti.append(_avvia(client, sessione_id))

    thread = [threading.Thread(target=spara, args=((studio, lavoro)[i % 2]["id"],)) for i in range(quante)]
    for t in thread:
        t.start()
    for t in thread:
        t.join()
    assert sorted(r.status_code for r in esiti) == [201] + [409] * (quante - 1)
    for r in esiti:
        if r.status_code == 409:
            assert r.json()["detail"] == {"errore": "sessione_gia_in_corso"}
    conn = sqlite3.connect(db_path)
    try:
        assert conn.execute("SELECT COUNT(*) FROM sessioni_svolte").fetchone()[0] == 1
    finally:
        conn.close()


@pytest.mark.parametrize("cambio", [False, True])
def test_decisioni_simultanee_ne_passa_una(client, famiglia, cambio):
    s = _approvata(client, nome="Studio") if cambio else _sessione(client, nome="Studio")
    if cambio:
        assert _patch(client, s["id"], app=[DUOLINGO]).status_code == 200
    gia_viste = _ids_notifiche(client, FIGLIO)
    vista = _versione(client, s["id"])  # tutti decidono sulla stessa schermata
    quante = 6
    barriera = threading.Barrier(quante)
    esiti = []

    def spara(esito):
        barriera.wait()
        esiti.append((esito, _rispondi(client, s["id"], esito, versione=vista)))

    thread = [threading.Thread(target=spara, args=(("approva", "rifiuta")[i % 2],)) for i in range(quante)]
    for t in thread:
        t.start()
    for t in thread:
        t.join()
    assert sorted(r.status_code for _, r in esiti) == [200] + [409] * (quante - 1)
    (vincente,) = [esito for esito, r in esiti if r.status_code == 200]
    for _, r in esiti:
        if r.status_code == 409:
            assert r.json()["detail"] == {"errore": "niente_da_decidere"}
    (avviso,) = _nuove(client, FIGLIO, gia_viste)
    assert avviso["payload"]["esito"] == vincente and avviso["payload"]["cambio"] is cambio
    (finale,) = _elenco(client)
    assert finale["modifica_in_attesa"] is None
    if cambio:
        assert finale["app"] == ([DUOLINGO] if vincente == "approva" else SCUOLA)
    else:
        assert finale["stato"] == ("approvata" if vincente == "approva" else "rifiutata")


def test_un_cambio_e_un_approvazione_insieme(client, famiglia):
    """Il figlio cambia la lista mentre il genitore approva quella che vede: o passa
    prima l'approvazione (approvata la lista vista, il cambio diventa una richiesta
    nuova) o prima il cambio (409 richiesta_cambiata, niente approvato). Mai approvata
    una lista che il genitore non ha visto."""
    for giro in range(4):
        vista = [f"com.lista.vista{giro}"]
        nuova = [f"com.lista.nuova{giro}"]
        s = _sessione(client, nome=f"Giro {giro}", app=vista)
        barriera = threading.Barrier(2)
        esiti = {}

        def approva():
            barriera.wait()
            esiti["approva"] = _rispondi(client, s["id"], "approva", versione=s["versione"])

        def cambia():
            barriera.wait()
            esiti["cambia"] = _patch(client, s["id"], app=nuova)

        thread = [threading.Thread(target=approva), threading.Thread(target=cambia)]
        for t in thread:
            t.start()
        for t in thread:
            t.join()
        assert esiti["cambia"].status_code == 200, esiti["cambia"].text
        (finale,) = [x for x in _elenco(client) if x["id"] == s["id"]]
        if esiti["approva"].status_code == 200:
            assert (finale["stato"], finale["app"]) == ("approvata", vista)
            assert finale["modifica_in_attesa"]["app"] == nuova
        else:
            assert esiti["approva"].json()["detail"]["errore"] == "richiesta_cambiata"
            assert (finale["stato"], finale["app"]) == ("in_attesa", nuova)


def test_creazioni_simultanee_con_lo_stesso_nome(client, famiglia):
    quante = 6
    barriera = threading.Barrier(quante)
    esiti = []

    def spara(nome):
        barriera.wait()
        esiti.append(_crea(client, nome=nome))

    nomi = ["Studio", "studio", "STUDIO", " Studio", "StUdIo", "studio "]
    thread = [threading.Thread(target=spara, args=(nome,)) for nome in nomi]
    for t in thread:
        t.start()
    for t in thread:
        t.join()
    assert sorted(r.status_code for r in esiti) == [201] + [409] * (quante - 1)
    assert len(_elenco(client)) == 1


# --- compatibilita' ---

def test_un_database_della_v34_prende_le_tabelle_senza_copia(client, db_path):
    """Due tabelle nuove e basta: un database della v3.4 non va copiato prima, e quello
    che c'era resta com'era."""
    from app.main import create_app

    regola(client, FIGLIO)
    eventi(client, FIGLIO, _foto("2026-07-14", 30))
    conn = sqlite3.connect(db_path)
    try:  # il database com'era col server v3.4
        conn.execute("DROP TABLE sessioni_svolte")
        conn.execute("DROP TABLE sessioni")
        conn.commit()
    finally:
        conn.close()
    prima = dati_v24.righe(db_path)
    assert "sessioni" not in prima

    with TestClient(create_app()) as c:  # il riavvio col server v3.5
        assert c.get("/api/patto", headers=FIGLIO).json()["sessioni"] == []
    dopo = dati_v24.righe(db_path)
    assert (dopo["sessioni"], dopo["sessioni_svolte"]) == ([], [])
    assert {t: r for t, r in dopo.items() if t not in ("sessioni", "sessioni_svolte")} == prima
    percorso = Path(db_path)
    assert list(percorso.parent.glob(percorso.name + ".prima-*")) == []
    conn = sqlite3.connect(db_path)
    try:
        assert conn.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    finally:
        conn.close()
