"""(v3.6) La famiglia con piu' genitori (contratto-api.md, "v3.6 — la famiglia con piu'
genitori e le faccende").

- I genitori: il genitore 1 e' quello del token d'ambiente; un genitore ne aggiunge un
  altro (nome -> codice di 6 cifre), lo rinomina, lo revoca. Il genitore nuovo si
  abbina con POST /api/abbina e tipo "genitore", con gli stessi tentativi sbagliati
  dei dispositivi; il tipo sbagliato non consuma il codice, nei due versi.
- La revoca: mai se stessi, mai l'ultimo; quella del genitore 1 resta dopo un riavvio.
- Chi ha fatto cosa: proposte, risposte, sessioni, verdetti e segno dicono quale
  genitore, col nome di adesso; le righe di prima della v3.6 sono del genitore 1; nei
  messaggi per il figlio c'e' il nome del genitore.
- Le notifiche del genitore: ognuno le legge per conto suo; uno nuovo non riceve
  quelle nate prima di lui.
- La migrazione di un database v3.5 vero (tests/dati_v35.py): la copia prima, niente
  si perde, gli stessi numeri di prima, le lette restano lette per il genitore 1, e le
  app 0.12 continuano a funzionare."""

import logging
import re
import sqlite3
import threading
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

import dati_v24
import dati_v35
from aiuti_v3 import (
    auth, contenuto_in, dispositivo_abbinato, eventi, nuovo_dispositivo, regola, senza_righe_v40,
)
from conftest import FIGLIO, GENITORE, TOKEN_FIGLIO, TOKEN_GENITORE, Orologio

GENITORE_1 = {"id": 1, "nome": "Genitore"}
TIKTOK = "com.zhiliaoapp.musically"
SCUOLA = ["eu.spaggiari.classevivafamiglia", "com.google.android.apps.classroom"]
VITA = {"descrizione": "Leggere 20 minuti", "arbitro_nome": "Nonna", "frequenza": "ogni giorno"}


def _ok(risposta, atteso=200):
    assert risposta.status_code == atteso, risposta.text
    return risposta.json()


def _nuovo_genitore(client, nome="Mamma", headers=GENITORE) -> dict:
    return _ok(client.post("/api/genitori", json={"nome": nome}, headers=headers), 201)


def _abbina(client, codice, tipo="genitore", **altro):
    corpo = {"codice": codice, **altro}
    if tipo is not None:
        corpo["tipo"] = tipo
    return client.post("/api/abbina", json=corpo)


def _genitore_abbinato(client, nome="Mamma", headers=GENITORE) -> tuple[dict, int]:
    creato = _nuovo_genitore(client, nome, headers)
    risposta = _ok(_abbina(client, creato["codice"], versione_app="0.13.0"))
    return auth(risposta["token"]), creato["genitore"]["id"]


def _notifiche(client, headers, **query) -> list:
    return _ok(client.get("/api/notifiche", headers=headers, params=query))["notifiche"]


def _ids(client, headers, **query) -> list:
    return [n["id"] for n in _notifiche(client, headers, **query)]


def _genitori(client, headers=GENITORE) -> dict:
    return _ok(client.get("/api/genitori", headers=headers))


def _famiglia(client, headers=GENITORE) -> dict:
    return _ok(client.get("/api/famiglia", headers=headers))


def _limite(minuti, app=TIKTOK):
    return {"app_o_categoria": app, "minuti_al_giorno": minuti}


# --- i genitori ---

def test_il_genitore_1_e_quello_del_token(client):
    assert _genitori(client) == {
        "io": GENITORE_1,
        "genitori": [{"id": 1, "nome": "Genitore", "abbinato": True, "revocato": False,
                      "creato_ts": "2026-07-14T10:00:00+00:00"}],
    }
    famiglia = _famiglia(client)
    assert famiglia["io"] == GENITORE_1
    assert famiglia["genitori"] == [{"id": 1, "nome": "Genitore", "revocato": False}]


def test_aggiungere_un_genitore(client, orologio):
    creato = _nuovo_genitore(client, "  Mamma  ")
    assert creato["genitore"] == {"id": 2, "nome": "Mamma", "abbinato": False, "revocato": False,
                                  "creato_ts": "2026-07-14T10:00:00+00:00"}
    assert re.fullmatch(r"\d{6}", creato["codice"])
    assert creato["scade_ts"] == "2026-07-14T10:15:00+00:00"
    orologio.avanza(minutes=5)
    risposta = _ok(_abbina(client, creato["codice"], versione_app="0.13.0"))
    assert set(risposta) == {"token", "genitore"}
    assert risposta["genitore"] == {"id": 2, "nome": "Mamma"}
    mamma = auth(risposta["token"])
    # col suo token e' un genitore come gli altri
    assert _genitori(client, mamma)["io"] == {"id": 2, "nome": "Mamma"}
    assert [g["abbinato"] for g in _genitori(client)["genitori"]] == [True, True]
    assert client.get("/api/finestra", headers=mamma).status_code == 200
    assert client.get("/api/patto", headers=mamma).status_code == 403
    # e aggiunge a sua volta un genitore
    assert _nuovo_genitore(client, "Nonna", headers=mamma)["genitore"]["id"] == 3
    # il codice vale una volta sola
    assert _abbina(client, creato["codice"]).json()["detail"] == {"errore": "codice_non_valido"}


def test_il_codice_del_genitore_scade_dopo_15_minuti(client, orologio):
    creato = _nuovo_genitore(client)
    orologio.avanza(minutes=15)
    r = _abbina(client, creato["codice"])
    assert r.status_code == 409 and r.json()["detail"] == {"errore": "codice_non_valido"}


def test_il_tipo_sbagliato_non_consuma_il_codice_nei_due_versi(client, db_path):
    creato = _nuovo_genitore(client)
    # un codice da genitore con il tipo di un dispositivo, o senza tipo (le app vecchie del figlio)
    for tipo in ("telefono", "computer", None):
        r = _abbina(client, creato["codice"], tipo=tipo)
        assert r.status_code == 409, r.text
        assert r.json()["detail"] == {"errore": "tipo_non_corrispondente", "tipo_atteso": "genitore"}
    # un codice da dispositivo con tipo "genitore"
    pc = nuovo_dispositivo(client, 1, "Computer", "computer")
    r = _abbina(client, pc["codice"], tipo="genitore")
    assert r.status_code == 409
    assert r.json()["detail"] == {"errore": "tipo_non_corrispondente", "tipo_atteso": "computer"}
    # nessuno dei due si e' consumato, e i 4 tentativi sbagliati sono contati
    assert _ok(_abbina(client, creato["codice"]))["genitore"]["id"] == 2
    assert _ok(_abbina(client, pc["codice"], tipo="computer"))["dispositivo"]["tipo"] == "computer"
    conn = sqlite3.connect(db_path)
    try:
        assert conn.execute("SELECT COUNT(*) FROM tentativi_abbinamento").fetchone()[0] == 4
    finally:
        conn.close()


def test_i_tentativi_sbagliati_sono_gli_stessi_di_genitori_e_dispositivi(client, orologio):
    """Dieci tentativi falliti in dieci minuti, sommando codici sbagliati, tipi
    sbagliati di un codice da genitore e di uno da dispositivo: ogni abbinamento si
    blocca, anche quello giusto di un genitore."""
    creato = _nuovo_genitore(client)
    pc = nuovo_dispositivo(client, 1, "Computer", "computer")
    for i in range(4):
        assert _abbina(client, f"00000{i}", tipo="genitore").status_code == 409
    for _ in range(3):
        assert _abbina(client, creato["codice"], tipo="telefono").status_code == 409
    for _ in range(3):
        assert _abbina(client, pc["codice"], tipo="genitore").status_code == 409
    r = _abbina(client, creato["codice"])
    assert r.status_code == 429 and r.json()["detail"]["errore"] == "troppi_tentativi"
    orologio.avanza(minutes=10)
    assert _abbina(client, creato["codice"]).status_code == 200


def test_un_codice_nuovo_per_un_genitore(client):
    mamma, mamma_id = _genitore_abbinato(client)
    primo = _ok(client.post(f"/api/genitori/{mamma_id}/codice", headers=GENITORE))
    secondo = _ok(client.post(f"/api/genitori/{mamma_id}/codice", headers=mamma))
    assert primo["genitore"]["id"] == secondo["genitore"]["id"] == mamma_id
    # il codice nuovo annulla il vecchio (se il caso non li ha fatti uguali); il token
    # vecchio vale finche' non si riabbina
    if primo["codice"] != secondo["codice"]:
        assert _abbina(client, primo["codice"]).status_code == 409
    assert client.get("/api/genitori", headers=mamma).status_code == 200
    nuovo = auth(_ok(_abbina(client, secondo["codice"]))["token"])
    assert client.get("/api/genitori", headers=mamma).status_code == 401
    assert _genitori(client, nuovo)["io"]["id"] == mamma_id


def test_rinominare_un_genitore(client):
    mamma, mamma_id = _genitore_abbinato(client)
    assert _ok(client.patch(f"/api/genitori/{mamma_id}", json={"nome": "Mamma Sara"}, headers=mamma))["nome"] == "Mamma Sara"
    # anche un altro genitore: sono tutti uguali
    rinominato = _ok(client.patch("/api/genitori/1", json={"nome": "Papa'"}, headers=mamma))
    assert rinominato == {"id": 1, "nome": "Papa'", "abbinato": True, "revocato": False,
                          "creato_ts": "2026-07-14T10:00:00+00:00"}
    assert _genitori(client)["io"] == {"id": 1, "nome": "Papa'"}
    for nome in ("", "   ", "x" * 41):
        assert client.patch("/api/genitori/1", json={"nome": nome}, headers=GENITORE).status_code == 422


def test_un_genitore_che_non_c_e(client):
    for r in (
        client.post("/api/genitori/99/codice", headers=GENITORE),
        client.patch("/api/genitori/99", json={"nome": "Zia"}, headers=GENITORE),
        client.delete("/api/genitori/99", headers=GENITORE),
    ):
        assert r.status_code == 404 and r.json() == {"detail": "genitore non trovato"}


def test_al_massimo_10_genitori_non_revocati(client):
    for i in range(9):
        _nuovo_genitore(client, f"Genitore {i + 2}")
    r = client.post("/api/genitori", json={"nome": "Undicesimo"}, headers=GENITORE)
    assert r.status_code == 409 and r.json()["detail"] == {"errore": "troppi_genitori"}
    assert client.delete("/api/genitori/10", headers=GENITORE).status_code == 200
    assert _nuovo_genitore(client, "Undicesimo")["genitore"]["id"] == 11


def test_solo_i_genitori_gestiscono_i_genitori(client):
    for r in (
        client.get("/api/genitori", headers=FIGLIO),
        client.post("/api/genitori", json={"nome": "Zio"}, headers=FIGLIO),
        client.delete("/api/genitori/1", headers=FIGLIO),
    ):
        assert r.status_code == 403
    assert client.get("/api/genitori").status_code == 401


def test_token_e_codici_dei_genitori_solo_come_hash(client, db_path):
    creato = _nuovo_genitore(client)
    token = _ok(_abbina(client, creato["codice"]))["token"]
    contenuto = Path(db_path).read_bytes()
    assert token.encode() not in contenuto and creato["codice"].encode() not in contenuto


# --- la revoca ---

def test_revocare_un_genitore(client):
    mamma, mamma_id = _genitore_abbinato(client)
    assert _ok(client.delete(f"/api/genitori/{mamma_id}", headers=GENITORE)) == {"id": mamma_id, "revocato": True}
    assert client.get("/api/finestra", headers=mamma).status_code == 401
    assert client.get("/api/notifiche", headers=mamma).status_code == 401
    assert [g["revocato"] for g in _genitori(client)["genitori"]] == [False, True]
    assert _famiglia(client)["genitori"][1] == {"id": mamma_id, "nome": "Mamma", "revocato": True}
    # rifarla non cambia niente; un codice nuovo per un revocato no
    assert client.delete(f"/api/genitori/{mamma_id}", headers=GENITORE).status_code == 200
    r = client.post(f"/api/genitori/{mamma_id}/codice", headers=GENITORE)
    assert r.status_code == 409 and r.json()["detail"] == {"errore": "genitore_revocato"}


def test_la_revoca_annulla_il_codice_aperto(client):
    creato = _nuovo_genitore(client)
    assert client.delete(f"/api/genitori/{creato['genitore']['id']}", headers=GENITORE).status_code == 200
    assert _abbina(client, creato["codice"]).json()["detail"] == {"errore": "codice_non_valido"}


def test_non_te_stesso(client):
    _genitore_abbinato(client)
    r = client.delete("/api/genitori/1", headers=GENITORE)
    assert r.status_code == 409 and r.json()["detail"] == {"errore": "non_te_stesso"}


def test_la_revoca_partita_prima_di_essere_revocato_non_passa(client, db_path):
    """Due genitori che si revocano a vicenda: la prima revoca passa; la seconda, il cui
    token era stato controllato un attimo prima (qui chiamata a mano come arriverebbe),
    trova dentro il lock che chi la manda e' appena stato revocato: 401, niente scritto.
    Cosi' ultimo_genitore non puo' piu' scattare con un token valido."""
    from app.auth import Identita
    from app.db import connetti
    from app.routes.famiglia import revoca_genitore
    from fastapi import HTTPException

    mamma, mamma_id = _genitore_abbinato(client)
    assert client.delete(f"/api/genitori/{mamma_id}", headers=GENITORE).status_code == 200
    conn = connetti(db_path)
    try:
        with pytest.raises(HTTPException) as errore:
            revoca_genitore(1, chi=Identita(ruolo="genitore", genitore_id=mamma_id), conn=conn)
    finally:
        conn.close()
    assert errore.value.status_code == 401 and errore.value.detail == "genitore revocato"
    assert [g["revocato"] for g in _genitori(client)["genitori"]] == [False, True]


def test_le_scritture_di_un_genitore_appena_revocato_non_passano(client, db_path):
    """Ogni scrittura di un genitore ricontrolla la revoca dentro il lock: una richiesta
    autenticata un attimo prima della revoca (qui: l'identita' letta prima, la revoca
    scritta in mezzo) riceve 401 e non scrive niente."""
    from app.auth import Identita, richiede_genitore, richiede_patto

    regola_tiktok = regola(client, FIGLIO, parametri=_limite(60))
    proposta_figlio = _ok(client.post("/api/proposte", json={"regola_id": regola_tiktok["id"],
                                                             "parametri_proposti": _limite(90)}, headers=FIGLIO))
    s = _ok(client.post("/api/sessioni", json={"nome": "Studio", "app": SCUOLA}, headers=FIGLIO), 201)
    vita = regola(client, FIGLIO, tipo="vita_reale", parametri=VITA)
    d = _ok(client.post("/api/dichiarazioni", json={"regola_id": vita["id"], "esito": "successo"}, headers=FIGLIO))
    (lavatrice,) = _ok(client.post("/api/faccende", json={"figlio_id": 1, "faccende": [{"titolo": "Lavatrice"}]},
                                   headers=GENITORE), 201)["faccende"]
    zia = _nuovo_genitore(client, "Zia")["genitore"]["id"]
    mamma, mamma_id = _genitore_abbinato(client)
    assert client.delete(f"/api/genitori/{mamma_id}", headers=GENITORE).status_code == 200
    prima = dati_v24.righe(db_path)

    in_volo = Identita(ruolo="genitore", genitore_id=mamma_id)  # il token valeva quando e' arrivata
    client.app.dependency_overrides[richiede_genitore] = lambda: in_volo
    client.app.dependency_overrides[richiede_patto] = lambda: in_volo
    try:
        richieste = [
            ("post", "/api/genitori", {"nome": "Nonna"}),
            ("post", f"/api/genitori/{zia}/codice", None),
            ("patch", f"/api/genitori/{zia}", {"nome": "Zia Anna"}),
            ("delete", f"/api/genitori/{zia}", None),
            ("post", "/api/figli", {"nome": "Sara"}),
            ("patch", "/api/figli/1", {"nome": "Luca"}),
            ("post", "/api/figli/1/dispositivi", {"nome": "Computer", "tipo": "computer"}),
            ("post", "/api/dispositivi/1/codice", None),
            ("delete", "/api/dispositivi/1", None),
            ("post", "/api/faccende", {"figlio_id": 1, "faccende": [{"titolo": "Letto"}]}),
            ("post", f"/api/faccende/{lavatrice['id']}/annulla", None),
            ("post", "/api/proposte", {"regola_id": vita["id"], "parametri_proposti": {"azione": "elimina"}}),
            ("post", f"/api/proposte/{proposta_figlio['id']}/risposta", {"esito": "accetta"}),
            ("post", f"/api/sessioni/{s['id']}/risposta", {"esito": "approva", "versione": 1}),
            ("post", f"/api/dichiarazioni/{d['id']}/verdetto", {"verdetto": "conferma"}),
            ("post", "/api/segno", {"figlio_id": 1}),
        ]
        for metodo, percorso, corpo in richieste:
            r = client.request(metodo, percorso, json=corpo)
            assert (r.status_code, r.json()) == (401, {"detail": "genitore revocato"}), (percorso, r.text)
    finally:
        client.app.dependency_overrides.clear()
    assert dati_v24.righe(db_path) == prima  # niente scritto


def test_due_revoche_incrociate_insieme_ne_passa_una(client):
    """Due genitori che si revocano a vicenda nello stesso momento: ne passa una sola
    (200), l'altra riceve 401 (chi la manda e' appena stato revocato: se ne accorge il
    controllo del token o, se il token era gia' stato controllato, quello dentro il
    lock), e resta sempre un genitore. Tre giri, ogni volta con un genitore nuovo
    aggiunto da quello rimasto."""
    vivo_id, vivo = 1, GENITORE
    for giro in range(3):
        altro, altro_id = _genitore_abbinato(client, f"Genitore {giro + 2}", headers=vivo)
        coppia = {vivo_id: (vivo, altro_id), altro_id: (altro, vivo_id)}
        barriera = threading.Barrier(2)
        esiti = {}

        def revoca(chi_id):
            headers, bersaglio = coppia[chi_id]
            barriera.wait()
            esiti[chi_id] = client.delete(f"/api/genitori/{bersaglio}", headers=headers)

        fili = [threading.Thread(target=revoca, args=(i,)) for i in coppia]
        for f in fili:
            f.start()
        for f in fili:
            f.join()
        (vincitore,) = [i for i, r in esiti.items() if r.status_code == 200]
        (perdente,) = [r for r in esiti.values() if r.status_code != 200]
        assert perdente.status_code == 401, perdente.text
        vivo_id, vivo = vincitore, coppia[vincitore][0]
        assert [g["id"] for g in _genitori(client, vivo)["genitori"] if not g["revocato"]] == [vivo_id]


def test_la_revoca_del_genitore_1_resta_dopo_il_riavvio(client):
    mamma, _ = _genitore_abbinato(client)
    assert client.delete("/api/genitori/1", headers=mamma).status_code == 200
    assert client.get("/api/finestra", headers=GENITORE).status_code == 401
    from app.main import create_app

    with TestClient(create_app()) as c:  # il token d'ambiente non lo resuscita
        assert c.get("/api/finestra", headers=GENITORE).status_code == 401
        assert c.get("/api/finestra", headers=mamma).status_code == 200
        assert [g["revocato"] for g in _genitori(c, mamma)["genitori"]] == [True, False]


def test_il_genitore_1_riabbinato_il_token_d_ambiente_non_torna(client):
    codice = _ok(client.post("/api/genitori/1/codice", headers=GENITORE))["codice"]
    assert client.get("/api/finestra", headers=GENITORE).status_code == 200  # fino all'abbinamento vale
    nuovo = auth(_ok(_abbina(client, codice))["token"])
    assert client.get("/api/finestra", headers=GENITORE).status_code == 401
    assert _genitori(client, nuovo)["io"] == GENITORE_1
    from app.main import create_app

    with TestClient(create_app()) as c:
        assert c.get("/api/finestra", headers=GENITORE).status_code == 401
        assert c.get("/api/finestra", headers=nuovo).status_code == 200


def test_cambiare_il_token_del_genitore_1_nel_env_funziona_come_prima(client, monkeypatch):
    monkeypatch.setenv("PACTUM_TOKEN_GENITORE", "genitore-nuovo-dal-env")
    from app.main import create_app

    with TestClient(create_app()) as c:
        assert c.get("/api/finestra", headers=GENITORE).status_code == 401
        assert _genitori(c, auth("genitore-nuovo-dal-env"))["io"] == GENITORE_1


# --- le notifiche del genitore: lette da ciascuno ---

def test_ciascun_genitore_legge_le_sue(client):
    mamma, _ = _genitore_abbinato(client)
    eventi(client, FIGLIO, {"id": "m-1", "tipo": "manomissione", "dettagli": {"sotto_tipo": "silenzio"}},
           {"id": "m-2", "tipo": "manomissione", "dettagli": {"sotto_tipo": "silenzio"}})
    prima, seconda = _ids(client, mamma)
    assert _ids(client, GENITORE)[-2:] == [prima, seconda]
    assert _ok(client.post(f"/api/notifiche/{prima}/letta", headers=mamma)) == {"id": prima, "letta": True}
    assert _ids(client, mamma) == [seconda]
    assert prima in _ids(client, GENITORE)  # per l'altro genitore resta da leggere
    assert _ids(client, mamma, dopo_id=prima) == [seconda]
    assert _ids(client, mamma, dopo_id=seconda) == []
    assert _famiglia(client, mamma)["figli"][0]["notifiche_non_lette"] == 1
    da_leggere_g1 = _famiglia(client)["figli"][0]["notifiche_non_lette"]
    assert client.post(f"/api/notifiche/{seconda}/letta", headers=GENITORE).status_code == 200
    assert _famiglia(client)["figli"][0]["notifiche_non_lette"] == da_leggere_g1 - 1
    assert _famiglia(client, mamma)["figli"][0]["notifiche_non_lette"] == 1  # la sua non cambia
    # rimarcarla resta 200; quelle del figlio restano del figlio (404)
    assert client.post(f"/api/notifiche/{prima}/letta", headers=mamma).status_code == 200
    client.post("/api/segno", json={"figlio_id": 1}, headers=GENITORE)
    (segno,) = _ids(client, FIGLIO)
    assert client.post(f"/api/notifiche/{segno}/letta", headers=mamma).status_code == 404


def test_un_genitore_nuovo_non_riceve_le_notifiche_di_prima(client, orologio):
    eventi(client, FIGLIO, {"id": "m-prima", "tipo": "manomissione", "dettagli": {"sotto_tipo": "silenzio"}})
    vecchie = _ids(client, GENITORE)
    assert vecchie
    creato = _nuovo_genitore(client)
    # tra la creazione e l'abbinamento: questa la riceve
    eventi(client, FIGLIO, {"id": "m-durante", "tipo": "manomissione", "dettagli": {"sotto_tipo": "silenzio"}})
    orologio.avanza(minutes=5)
    mamma = auth(_ok(_abbina(client, creato["codice"]))["token"])
    nuove = _ids(client, mamma)
    assert len(nuove) == 1 and nuove[0] not in vecchie
    assert _famiglia(client, mamma)["figli"][0]["notifiche_non_lette"] == 1
    assert set(vecchie) < set(_ids(client, GENITORE))  # il genitore 1 le ha ancora tutte
    # marcare una notifica di prima di lui non fa male a nessuno
    assert client.post(f"/api/notifiche/{vecchie[0]}/letta", headers=mamma).status_code == 200
    assert vecchie[0] in _ids(client, GENITORE)


def test_l_avviso_di_sessione_superato_si_chiude_per_tutti(client):
    """Un solo avviso aperto per sessione (v3.5), per tutti i genitori: quando il
    figlio cambia la richiesta, quella vecchia si chiude anche per chi non l'ha letta."""
    mamma, _ = _genitore_abbinato(client)
    s = _ok(client.post("/api/sessioni", json={"nome": "Studio", "app": SCUOLA}, headers=FIGLIO), 201)
    assert _ok(client.patch(f"/api/sessioni/{s['id']}", json={"nome": "Compiti"}, headers=FIGLIO))
    for headers in (GENITORE, mamma):
        (avviso,) = [n for n in _notifiche(client, headers) if n["tipo"] == "sessione_da_approvare"]
        assert avviso["payload"]["nome"] == "Compiti"


# --- chi ha fatto cosa ---

@pytest.fixture
def due_genitori(client):
    """Luca (figlio 1) col telefono e un computer; la mamma abbinata come genitore 2."""
    assert client.patch("/api/figli/1", json={"nome": "Luca"}, headers=GENITORE).status_code == 200
    pc, pc_id = dispositivo_abbinato(client, 1, "Computer", "computer")
    mamma, mamma_id = _genitore_abbinato(client)
    return SimpleNamespace(pc=pc, pc_id=pc_id, mamma=mamma, mamma_id=mamma_id,
                           MAMMA={"id": mamma_id, "nome": "Mamma"})


def _ultima(client, headers, tipo) -> dict:
    return [n for n in _notifiche(client, headers) if n["tipo"] == tipo][-1]


def test_le_proposte_dicono_quale_genitore(client, due_genitori):
    g = due_genitori
    tiktok = regola(client, FIGLIO, parametri=_limite(60))
    youtube = regola(client, FIGLIO, parametri=_limite(90, app="com.google.android.youtube"))
    # la mamma propone: "genitore" e il suo nome nel messaggio e nel payload
    p = _ok(client.post("/api/proposte", json={"regola_id": tiktok["id"], "parametri_proposti": _limite(45)},
                        headers=g.mamma))
    assert (p["genitore"], p["risposta_di"]) == (g.MAMMA, None)
    avviso = _ultima(client, FIGLIO, "nuova_proposta")
    assert avviso["messaggio"] == "Nuova proposta di Mamma: −15 min al giorno rispetto ad ora"
    assert avviso["payload"]["genitore"] == g.MAMMA
    # il figlio risponde: risposta_di resta null (non e' una risposta di un genitore)
    risposta = _ok(client.post(f"/api/proposte/{p['id']}/risposta", json={"esito": "rifiuta"}, headers=FIGLIO))
    assert (risposta["proposta"]["genitore"], risposta["proposta"]["risposta_di"]) == (g.MAMMA, None)
    # il figlio propone, il genitore 1 risponde
    q = _ok(client.post("/api/proposte", json={"regola_id": youtube["id"],
                                               "parametri_proposti": _limite(120, app="com.google.android.youtube")},
                        headers=FIGLIO))
    assert (q["genitore"], q["risposta_di"]) == (None, None)
    risposta = _ok(client.post(f"/api/proposte/{q['id']}/risposta", json={"esito": "accetta"}, headers=GENITORE))
    assert (risposta["proposta"]["genitore"], risposta["proposta"]["risposta_di"]) == (None, GENITORE_1)
    avviso = _ultima(client, FIGLIO, "proposta_risposta")
    assert avviso["messaggio"] == "Genitore ha accettato la tua proposta: +30 min al giorno rispetto ad ora"
    assert avviso["payload"]["genitore"] == GENITORE_1
    # una proposta del genitore 1 ritirata dalla mamma: sono uguali, e il figlio legge chi
    r = _ok(client.post("/api/proposte", json={"regola_id": tiktok["id"], "parametri_proposti": {"azione": "elimina"}},
                        headers=GENITORE))
    assert _ok(client.post(f"/api/proposte/{r['id']}/ritira", headers=g.mamma))["genitore"] == GENITORE_1
    avviso = _ultima(client, FIGLIO, "proposta_ritirata")
    assert avviso["messaggio"] == "Mamma ha ritirato la sua proposta di eliminare la regola"
    assert avviso["payload"]["genitore"] == g.MAMMA
    # il nome di adesso, non quello di allora
    client.patch(f"/api/genitori/{g.mamma_id}", json={"nome": "Mamma Sara"}, headers=g.mamma)
    tutte = {x["id"]: x for x in _ok(client.get("/api/proposte?autori=tutti", headers=GENITORE))["proposte"]}
    assert tutte[p["id"]]["genitore"] == {"id": g.mamma_id, "nome": "Mamma Sara"}


def test_le_sessioni_dicono_chi_ha_deciso(client, due_genitori):
    g = due_genitori
    s = _ok(client.post("/api/sessioni", json={"nome": "Studio", "app": SCUOLA}, headers=FIGLIO), 201)
    assert s["decisa_da"] is None
    decisa = _ok(client.post(f"/api/sessioni/{s['id']}/risposta", json={"esito": "approva", "versione": 1},
                             headers=g.mamma))
    assert decisa["decisa_da"] == g.MAMMA
    avviso = _ultima(client, FIGLIO, "sessione_risposta")
    assert avviso["messaggio"] == "Mamma ha approvato la sessione «Studio»"
    assert avviso["payload"] == {"sessione_id": s["id"], "nome": "Studio", "esito": "approva", "cambio": False,
                                 "genitore": g.MAMMA}
    # un cambio chiesto dopo non cancella chi ha deciso l'ultima volta
    cambiata = _ok(client.patch(f"/api/sessioni/{s['id']}", json={"nome": "Compiti"}, headers=FIGLIO))
    assert cambiata["decisa_da"] == g.MAMMA
    decisa = _ok(client.post(f"/api/sessioni/{s['id']}/risposta",
                             json={"esito": "rifiuta", "versione": cambiata["versione"]}, headers=GENITORE))
    assert decisa["decisa_da"] == GENITORE_1
    assert _ultima(client, FIGLIO, "sessione_risposta")["messaggio"] == (
        "Genitore non ha approvato il cambio alla sessione «Studio»"
    )
    (in_finestra,) = _ok(client.get("/api/finestra", headers=g.mamma))["sessioni"]
    assert in_finestra["decisa_da"] == GENITORE_1


def test_verdetto_e_segno_dicono_chi(client, due_genitori):
    g = due_genitori
    vita = regola(client, FIGLIO, tipo="vita_reale", parametri=VITA)
    d = _ok(client.post("/api/dichiarazioni", json={"regola_id": vita["id"], "esito": "successo"}, headers=FIGLIO))
    assert d["verdetto"] is None
    v = _ok(client.post(f"/api/dichiarazioni/{d['id']}/verdetto", json={"verdetto": "conferma_per_conto"},
                        headers=g.mamma))
    assert v["verdetto"]["da"] == g.MAMMA
    assert v["verdetto"]["registro"] == "confermato dal genitore per conto di Nonna"  # la frase del registro resta
    assert _ultima(client, FIGLIO, "verdetto")["payload"]["genitore"] == g.MAMMA
    (dal_figlio,) = _ok(client.get("/api/dichiarazioni", headers=FIGLIO))["dichiarazioni"]
    assert dal_figlio["verdetto"]["da"] == g.MAMMA

    segno = _ok(client.post("/api/segno", json={"figlio_id": 1}, headers=g.mamma))
    assert segno["da"] == g.MAMMA
    avviso = _ultima(client, FIGLIO, "segno")
    assert (avviso["messaggio"], avviso["payload"]) == ("Ho visto la settimana. Bene così.", {"genitore": g.MAMMA})
    # uno al giorno per figlio, chiunque lo mandi
    r = client.post("/api/segno", json={"figlio_id": 1}, headers=GENITORE)
    assert r.status_code == 409 and r.json()["detail"] == {"errore": "segno_gia_mandato"}


def test_le_decisioni_di_un_revocato_restano_col_suo_nome(client, due_genitori):
    g = due_genitori
    tiktok = regola(client, FIGLIO, parametri=_limite(60))
    p = _ok(client.post("/api/proposte", json={"regola_id": tiktok["id"], "parametri_proposti": _limite(30)},
                        headers=g.mamma))
    assert client.delete(f"/api/genitori/{g.mamma_id}", headers=GENITORE).status_code == 200
    (pendente,) = _ok(client.get("/api/patto", headers=FIGLIO))["proposte_pendenti"]
    assert pendente["id"] == p["id"] and pendente["genitore"] == g.MAMMA


# --- la migrazione di un database v3.5 vero ---

@pytest.fixture
def orologio_v35(monkeypatch):
    from app import clock

    o = Orologio(dati_v35.ORA_V35)
    monkeypatch.setattr(clock, "now", lambda: o.corrente)
    return o


@pytest.fixture
def db_v35(tmp_path):
    path = str(tmp_path / "nas-v35.db")
    dati_v35.crea_db_v35(path)
    return path


@pytest.fixture
def avvia_v35(monkeypatch, orologio_v35, db_v35):
    def _avvia():
        monkeypatch.setenv("PACTUM_DB", db_v35)
        monkeypatch.setenv("PACTUM_TOKEN_FIGLIO", TOKEN_FIGLIO)
        monkeypatch.setenv("PACTUM_TOKEN_GENITORE", TOKEN_GENITORE)
        from app.main import create_app

        return TestClient(create_app())

    return _avvia


def _copie(db_path, suffisso) -> list[Path]:
    percorso = Path(db_path)
    return sorted(percorso.parent.glob(percorso.name + suffisso + "*"))


COLONNE_V36 = {"genitore_id", "risposta_genitore_id", "verdetto_genitore_id", "decisa_genitore_id"}


def _senza_v36(righe: list) -> list:
    return [{k: v for k, v in r.items() if k not in COLONNE_V36} for r in righe]


def test_prima_di_migrare_la_copia_completa(avvia_v35, db_v35):
    prima = dati_v24.righe(db_v35)
    assert "genitori" not in prima
    with avvia_v35() as c:
        assert c.get("/api/famiglia", headers=GENITORE).status_code == 200
    (copia,) = _copie(db_v35, ".prima-v3.6-")
    assert copia.name == "nas-v35.db.prima-v3.6-20261001-120000"  # 10:00 UTC = 12:00 a Roma
    conn = sqlite3.connect(copia)
    try:
        assert conn.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    finally:
        conn.close()
    assert dati_v24.righe(str(copia)) == prima
    # era gia' v3.5: nessuna delle copie di prima
    assert _copie(db_v35, ".prima-v3-") == [] and _copie(db_v35, ".prima-v3.4-") == []
    assert not list(Path(db_v35).parent.glob("*.parziale"))


def test_niente_si_perde_e_il_genitore_1_prende_la_storia(avvia_v35, db_v35):
    prima = dati_v24.righe(db_v35)
    with avvia_v35():
        pass
    dopo = senza_righe_v40(dati_v24.righe(db_v35))  # (v4.0) le sue due righe in patto
    for tabella, righe in prima.items():
        assert _senza_v36(dopo[tabella]) == righe, tabella
    for tabella in ("genitori", "codici_genitori", "notifiche_lette_genitori", "faccende"):
        assert tabella in dopo
    assert [(g["id"], g["nome"], g["revocato_ts"], g["notifiche_dopo_id"]) for g in dopo["genitori"]] == [
        (1, "Genitore", None, 0),
    ]
    assert dopo["genitori"][0]["creato_ts"] == dopo["genitori"][0]["abbinato_ts"] == "2026-09-24T08:00:00+00:00"
    assert {(c["ruolo"], c["genitore_id"]) for c in dopo["credenziali"]} == {
        ("genitore", 1), ("dispositivo", None),
    }
    # le decisioni di prima restano com'erano (NULL): valgono come del genitore 1 quando si leggono
    for tabella in ("proposte", "dichiarazioni", "sessioni"):
        assert all(r[c] is None for r in dopo[tabella] for c in COLONNE_V36 if c in r), tabella
    assert dopo["notifiche_lette_genitori"] == [] and dopo["faccende"] == []


def test_dopo_la_migrazione_gli_stessi_numeri_di_prima(avvia_v35):
    """Le letture del server v3.5 su questo database a ORA_V35 (dati/v35_prima.json):
    la v3.6 puo' solo aggiungere campi. Comprese le notifiche del genitore: quelle lette
    prima della v3.6 restano lette per il genitore 1."""
    prima = dati_v35.prima()
    with avvia_v35() as c:
        for nome, percorso, chi in dati_v35.LETTURE:
            risposta = c.get(percorso, headers=dati_v35.intestazione(chi))
            assert risposta.status_code == 200, (nome, risposta.text)
            contenuto_in(prima[nome], risposta.json(), nome)


def test_le_righe_di_prima_sono_del_genitore_1(avvia_v35):
    with avvia_v35() as c:
        proposte = {p["id"]: p for p in _ok(c.get("/api/proposte?autori=tutti", headers=GENITORE))["proposte"]}
        sessioni = {s["nome"]: s for s in _ok(c.get("/api/sessioni", headers=GENITORE))["sessioni"]}
        (verdetto,) = [d for d in _ok(c.get("/api/dichiarazioni", headers=GENITORE))["dichiarazioni"] if d["verdetto"]]
    for p in proposte.values():
        assert p["genitore"] == (GENITORE_1 if p["autore"] == "genitore" else None), p["id"]
        risposto_dal_genitore = p["autore"] == "figlio" and p["risposta"] is not None
        assert p["risposta_di"] == (GENITORE_1 if risposto_dal_genitore else None), p["id"]
    assert [p["id"] for p in proposte.values() if p["risposta_di"]] == [4, 3]
    assert sessioni["Studio"]["decisa_da"] == GENITORE_1  # approvata (con un cambio in attesa)
    assert sessioni["Giochi"]["decisa_da"] == GENITORE_1  # rifiutata
    assert sessioni["Musica"]["decisa_da"] is None  # mai decisa
    assert verdetto["verdetto"]["da"] == GENITORE_1


def test_le_lette_restano_lette_per_il_genitore_1_e_un_genitore_nuovo_parte_pulito(avvia_v35, db_v35):
    righe = dati_v24.righe(db_v35)
    lette_prima = {n["id"] for n in righe["notifiche"] if n["destinatario"] == "genitore" and n["letta"]}
    assert lette_prima == {1, 2, 3, 4, 5, 6, 26, 28}
    with avvia_v35() as c:
        da_leggere = _ids(c, GENITORE)
        assert not set(da_leggere) & lette_prima
        assert da_leggere == [n["id"] for n in righe["notifiche"]
                              if n["destinatario"] == "genitore" and not n["letta"]]
        luca = next(f for f in _famiglia(c)["figli"] if f["id"] == 1)
        assert luca["notifiche_non_lette"] == len([n for n in righe["notifiche"] if n["destinatario"] == "genitore"
                                                   and not n["letta"] and n["figlio_id"] == 1])
        mamma, _ = _genitore_abbinato(c)
        assert _ids(c, mamma) == []
        assert all(f["notifiche_non_lette"] == 0 for f in _famiglia(c, mamma)["figli"])


def test_la_migrazione_v36_si_fa_una_volta_sola(avvia_v35, db_v35, orologio_v35):
    with avvia_v35():
        pass
    dopo_il_primo = dati_v24.righe(db_v35)
    for _ in range(2):
        orologio_v35.avanza(hours=1)
        with avvia_v35():
            pass
    assert dati_v24.righe(db_v35) == dopo_il_primo
    assert len(_copie(db_v35, ".prima-v3.6-")) == 1


def test_un_errore_a_meta_migrazione_v36_lascia_tutto_com_era(avvia_v35, db_v35, monkeypatch):
    """La migrazione e' una transazione sola: se si rompe a meta' (qui dopo aver gia'
    aggiunto le colonne) il database resta quello della v3.5, e il prossimo avvio
    riprova da capo con la copia gia' fatta."""
    from app import db

    prima = dati_v24.righe(db_v35)
    vero = db._senza_genitori
    chiamate = []

    def guasto(conn):
        chiamate.append(1)
        if len(chiamate) == 2:  # la seconda volta e' dentro la transazione, dopo le colonne
            raise RuntimeError("corrente saltata a meta' migrazione")
        return vero(conn)

    monkeypatch.setattr(db, "_senza_genitori", guasto)
    with pytest.raises(RuntimeError, match="corrente saltata"):
        avvia_v35()
    dopo = dati_v24.righe(db_v35)
    for tabella, righe in prima.items():
        assert dopo[tabella] == righe, tabella
    conn = sqlite3.connect(db_v35)
    try:
        assert "genitore_id" not in {r[1] for r in conn.execute("PRAGMA table_info(credenziali)")}
    finally:
        conn.close()
    assert dopo["genitori"] == []

    monkeypatch.setattr(db, "_senza_genitori", vero)
    with avvia_v35() as c:
        assert _genitori(c)["io"] == GENITORE_1
    (copia,) = _copie(db_v35, ".prima-v3.6-")
    assert dati_v24.righe(str(copia)) == prima


def test_dopo_un_ritorno_alla_v35_la_copia_si_rifa(avvia_v35, db_v35, orologio_v35):
    """Migrato, poi riportato alla v3.5 rimettendo la copia .prima-v3.6-, e il server
    v3.5 scrive ancora (qui a mano, come farebbe: un battito e una notifica letta). Al
    secondo aggiornamento la copia vecchia non ha quei dati: se ne fa una nuova, con un
    altro nome, e la vecchia resta com'era."""
    import shutil

    with avvia_v35():
        pass
    (vecchia,) = _copie(db_v35, ".prima-v3.6-")
    shutil.copyfile(vecchia, db_v35)  # il ritorno alla v3.5
    conn = sqlite3.connect(db_v35)
    try:
        conn.execute("INSERT INTO battiti (versione_app, ts_server, dispositivo_id)"
                     " VALUES ('0.12.0', '2026-10-02T09:00:00+00:00', 1)")
        conn.execute("UPDATE notifiche SET letta = 1 WHERE id = 7")
        conn.commit()
    finally:
        conn.close()
    con_i_dati_nuovi = dati_v24.righe(db_v35)
    assert con_i_dati_nuovi != dati_v24.righe(str(vecchia))
    orologio_v35.avanza(days=1)
    with avvia_v35() as c:
        assert 7 not in _ids(c, GENITORE)  # la lettura fatta dal server v3.5 c'e'
    copie = _copie(db_v35, ".prima-v3.6-")
    assert len(copie) == 2 and vecchia in copie
    (nuova,) = [c for c in copie if c != vecchia]
    assert nuova.name == "nas-v35.db.prima-v3.6-20261002-120000"
    assert dati_v24.righe(str(nuova)) == con_i_dati_nuovi


def test_se_la_copia_v36_non_riesce_il_server_non_parte(avvia_v35, db_v35, monkeypatch, caplog):
    from app import db

    prima = dati_v24.righe(db_v35)
    cartella_che_non_c_e = Path(db_v35).parent / "non-esiste" / "copia.db"
    monkeypatch.setattr(db, "_percorso_copia", lambda *_: str(cartella_che_non_c_e))
    with caplog.at_level(logging.ERROR, logger="uvicorn.error"):
        with pytest.raises(RuntimeError, match=r"MIGRAZIONE v3\.6 FERMATA"):
            avvia_v35()
    assert dati_v24.righe(db_v35) == prima
    assert _copie(db_v35, ".prima-v3.6-") == []


def test_dalla_v24_anche_la_copia_prima_della_v36(tmp_path, monkeypatch):
    """Un database v2.4 fa tutte le migrazioni: ha tutte e tre le copie, fatte tutte
    prima di toccarlo."""
    from app import clock

    monkeypatch.setattr(clock, "now", lambda: dati_v24.ORA_V24)
    path = str(tmp_path / "nas-v24.db")
    dati_v24.crea_db_v24(path)
    prima = dati_v24.righe(path)
    monkeypatch.setenv("PACTUM_DB", path)
    monkeypatch.setenv("PACTUM_TOKEN_FIGLIO", TOKEN_FIGLIO)
    monkeypatch.setenv("PACTUM_TOKEN_GENITORE", TOKEN_GENITORE)
    from app.main import create_app

    with TestClient(create_app()) as c:
        assert _genitori(c)["io"] == GENITORE_1
    copie = [_copie(path, s) for s in (".prima-v3-", ".prima-v3.4-", ".prima-v3.6-")]
    assert [len(c) for c in copie] == [1, 1, 1]
    for (copia,) in copie:
        assert dati_v24.righe(str(copia)) == prima


def test_su_un_database_nuovo_niente_copia_v36(client, db_path):
    assert _genitori(client)["io"] == GENITORE_1
    assert _copie(db_path, ".prima-v3.6-") == []


# --- le app 0.12 sul database migrato ---

def test_le_app_012_continuano_a_funzionare(avvia_v35, orologio_v35):
    """L'app del genitore 0.12 (il token d'ambiente, cioe' il genitore 1) e quelle del
    figlio 0.12 fanno quello che facevano: leggono, marcano, propongono, rispondono,
    decidono, e nessuno chiede loro niente di nuovo."""
    with avvia_v35() as c:
        da_leggere = _ids(c, GENITORE)
        assert _ok(c.post(f"/api/notifiche/{da_leggere[0]}/letta", headers=GENITORE)) == {
            "id": da_leggere[0], "letta": True,
        }
        assert _ids(c, GENITORE) == da_leggere[1:]
        assert _ids(c, GENITORE, dopo_id=da_leggere[-2]) == [da_leggere[-1]]
        # la proposta del figlio ancora pendente (la 6) la decide il genitore 1
        r = _ok(c.post("/api/proposte/6/risposta", json={"esito": "rifiuta"}, headers=GENITORE))
        assert r["proposta"]["stato"] == "rifiutata" and r["proposta"]["risposta_di"] == GENITORE_1
        # il cambio in attesa della sessione Studio
        studio = next(s for s in _ok(c.get("/api/sessioni", headers=GENITORE))["sessioni"] if s["nome"] == "Studio")
        decisa = _ok(c.post(f"/api/sessioni/{studio['id']}/risposta",
                            json={"esito": "approva", "versione": studio["versione"]}, headers=GENITORE))
        assert decisa["modifica_in_attesa"] is None and "com.duolingo" in decisa["app"]
        # il telefono 0.12 di Luca: battito, eventi, patto, avvio della sessione
        tel = dati_v35.intestazione("telefono")
        assert c.post("/api/battito", json={"versione_app": "0.12.0"}, headers=tel).status_code == 200
        eventi(c, tel, {"id": "uso-tel-2026-10-01", "tipo": "uso_giornaliero",
                        "dettagli": {"giorno": "2026-10-01", "uso_minuti": {TIKTOK: 10}, "totale_minuti": 10}})
        patto = _ok(c.get("/api/patto", headers=tel))
        assert patto["blocco"]["attivo"] is False and patto["faccende"] == []
        assert c.post(f"/api/sessioni/{studio['id']}/avvia", json={"durata_minuti": 30}, headers=tel).status_code == 201
        # il segno del genitore 1 a Sara (ne aveva gia' avuto uno, ma giorni fa)
        assert _ok(c.post("/api/segno", json={"figlio_id": 2}, headers=GENITORE))["da"] == GENITORE_1
        sara = dati_v35.intestazione("sara")
        assert _notifiche(c, sara)[-1]["payload"] == {"genitore": GENITORE_1}
