"""(v3) Figli, dispositivi e abbinamento con codice (contratto-api.md, "Abbinamento
con codice"): il codice e' di 6 cifre, vale 15 minuti e una volta sola, uno nuovo
annulla il precedente; il token nasce all'abbinamento e invalida quello vecchio;
la revoca spegne il token senza cancellare niente; 10 tentativi falliti in 10
minuti (su tutto il server) bloccano ogni abbinamento per 10 minuti."""

import re
import sqlite3
import threading
from pathlib import Path

import pytest

from aiuti_v3 import abbina, auth, dispositivo_abbinato, eventi, nuovo_dispositivo, nuovo_figlio
from conftest import FIGLIO, GENITORE


def _famiglia(client):
    risposta = client.get("/api/famiglia", headers=GENITORE)
    assert risposta.status_code == 200
    return risposta.json()


def _codice_sbagliato(codice: str) -> str:
    return f"{(int(codice) + 1) % 1_000_000:06d}"


# --- figli ---

def test_crea_e_rinomina_un_figlio(client):
    r = client.post("/api/figli", json={"nome": "  Giulia  "}, headers=GENITORE)
    assert r.status_code == 201
    assert r.json() == {"id": 2, "nome": "Giulia", "creato_ts": "2026-07-14T10:00:00+00:00"}
    r = client.patch("/api/figli/2", json={"nome": "Giulia B."}, headers=GENITORE)
    assert r.status_code == 200 and r.json()["nome"] == "Giulia B."
    r = client.patch("/api/figli/1", json={"nome": "Andrea"}, headers=GENITORE)
    assert r.json()["nome"] == "Andrea"  # il figlio 1 della migrazione si rinomina
    assert [f["nome"] for f in _famiglia(client)["figli"]] == ["Andrea", "Giulia B."]


@pytest.mark.parametrize("nome", ["", "   ", "x" * 41])
def test_nome_del_figlio_da_1_a_40_caratteri(client, nome):
    assert client.post("/api/figli", json={"nome": nome}, headers=GENITORE).status_code == 422
    assert client.patch("/api/figli/1", json={"nome": nome}, headers=GENITORE).status_code == 422


def test_quaranta_caratteri_vanno_bene(client):
    assert client.post("/api/figli", json={"nome": "x" * 40}, headers=GENITORE).status_code == 201


def test_figlio_inesistente_404(client):
    assert client.patch("/api/figli/99", json={"nome": "Nessuno"}, headers=GENITORE).status_code == 404
    r = client.post("/api/figli/99/dispositivi", json={"nome": "PC", "tipo": "computer"}, headers=GENITORE)
    assert r.status_code == 404


# --- dispositivi e codici ---

def test_dispositivo_nasce_non_abbinato_col_codice(client):
    figlio = nuovo_figlio(client)
    creato = nuovo_dispositivo(client, figlio["id"], "Computer di camera", "computer")
    assert re.fullmatch(r"\d{6}", creato["codice"])
    assert creato["scade_ts"] == "2026-07-14T10:15:00+00:00"  # 15 minuti
    dispositivo = creato["dispositivo"]
    assert (dispositivo["nome"], dispositivo["tipo"], dispositivo["abbinato"], dispositivo["revocato"]) == (
        "Computer di camera", "computer", False, False,
    )
    assert dispositivo["figlio_id"] == figlio["id"]


@pytest.mark.parametrize("corpo", [
    {"nome": "PC", "tipo": "tablet"}, {"nome": "", "tipo": "computer"}, {"tipo": "computer"},
])
def test_dispositivo_con_corpo_sbagliato_422(client, corpo):
    assert client.post("/api/figli/1/dispositivi", json=corpo, headers=GENITORE).status_code == 422


def test_abbinamento_riuscito(client):
    figlio = nuovo_figlio(client, "Luca")
    creato = nuovo_dispositivo(client, figlio["id"], "Computer", "computer")
    r = abbina(client, creato["codice"], versione_app="0.8.0")
    assert r.status_code == 200
    dati = r.json()
    assert dati["dispositivo"] == {"id": creato["dispositivo"]["id"], "nome": "Computer", "tipo": "computer"}
    assert dati["figlio"] == {"id": figlio["id"], "nome": "Luca"}
    assert len(dati["token"]) >= 40
    patto = client.get("/api/patto", headers=auth(dati["token"]))
    assert patto.status_code == 200
    assert patto.json()["figlio"] == {"id": figlio["id"], "nome": "Luca"}
    (luca,) = [f for f in _famiglia(client)["figli"] if f["id"] == figlio["id"]]
    assert luca["dispositivi"][0]["abbinato"] is True
    assert luca["dispositivi"][0]["versione_app"] == "0.8.0"


def test_il_codice_vale_una_volta_sola(client):
    creato = nuovo_dispositivo(client, 1, "Computer", "computer")
    assert abbina(client, creato["codice"]).status_code == 200
    r = abbina(client, creato["codice"])
    assert r.status_code == 409 and r.json()["detail"] == {"errore": "codice_non_valido"}


def test_il_codice_scade_dopo_15_minuti(client, orologio):
    primo = nuovo_dispositivo(client, 1, "Computer", "computer")
    secondo = nuovo_dispositivo(client, 1, "Telefono nuovo", "telefono")
    orologio.avanza(minutes=14, seconds=59)
    assert abbina(client, primo["codice"]).status_code == 200
    orologio.avanza(seconds=1)  # 15 minuti esatti: scaduto
    r = abbina(client, secondo["codice"])
    assert r.status_code == 409 and r.json()["detail"] == {"errore": "codice_non_valido"}


def test_un_codice_nuovo_annulla_il_precedente(client):
    creato = nuovo_dispositivo(client, 1, "Computer", "computer")
    nuovo = client.post(f"/api/dispositivi/{creato['dispositivo']['id']}/codice", headers=GENITORE)
    assert nuovo.status_code == 200
    assert nuovo.json()["dispositivo"]["id"] == creato["dispositivo"]["id"]
    assert abbina(client, creato["codice"]).status_code == 409
    assert abbina(client, nuovo.json()["codice"]).status_code == 200


# --- (v3.1) il tipo di chi si abbina ---

def _abbina_col_tipo(client, codice: str, tipo):
    return client.post("/api/abbina", json={"codice": codice, "versione_app": "0.8.0", "tipo": tipo})


@pytest.mark.parametrize("tipo", ["telefono", "computer"])
def test_abbinamento_col_tipo_giusto(client, tipo):
    creato = nuovo_dispositivo(client, 1, "Nuovo", tipo)
    r = _abbina_col_tipo(client, creato["codice"], tipo)
    assert r.status_code == 200 and r.json()["dispositivo"]["tipo"] == tipo


def test_tipo_sbagliato_409_e_il_codice_resta_valido(client):
    """Un computer che scrive il codice del telefono non ne prende il posto: 409 col
    tipo atteso, e il codice non si consuma (il telefono lo usa subito dopo)."""
    telefono = nuovo_dispositivo(client, 1, "Telefono nuovo", "telefono")
    r = _abbina_col_tipo(client, telefono["codice"], "computer")
    assert r.status_code == 409
    assert r.json()["detail"] == {"errore": "tipo_non_corrispondente", "tipo_atteso": "telefono"}
    (figlio,) = _famiglia(client)["figli"]
    assert [d["abbinato"] for d in figlio["dispositivi"] if d["id"] == telefono["dispositivo"]["id"]] == [False]
    r = _abbina_col_tipo(client, telefono["codice"], "telefono")
    assert r.status_code == 200
    assert r.json()["dispositivo"] == {"id": telefono["dispositivo"]["id"], "nome": "Telefono nuovo", "tipo": "telefono"}
    # e il contrario: il codice di un computer non abbina un telefono
    computer = nuovo_dispositivo(client, 1, "Computer", "computer")
    r = _abbina_col_tipo(client, computer["codice"], "telefono")
    assert r.status_code == 409 and r.json()["detail"]["tipo_atteso"] == "computer"


def test_senza_tipo_l_abbinamento_va_come_prima(client):
    creato = nuovo_dispositivo(client, 1, "Computer", "computer")
    assert abbina(client, creato["codice"]).status_code == 200  # nessun `tipo` nel corpo


def test_tipo_sbagliato_conta_come_tentativo_fallito(client):
    """Senza, il tipo sbagliato direbbe gratis se un codice provato a caso e' giusto."""
    creato = nuovo_dispositivo(client, 1, "Computer", "computer")
    _sbaglia(client, 9, creato["codice"])
    assert _abbina_col_tipo(client, creato["codice"], "telefono").status_code == 409  # il decimo
    r = _abbina_col_tipo(client, creato["codice"], "computer")  # anche giusto: bloccato
    assert r.status_code == 429 and r.json()["detail"]["errore"] == "troppi_tentativi"


def test_tipo_sconosciuto_422(client):
    creato = nuovo_dispositivo(client, 1, "Computer", "computer")
    assert _abbina_col_tipo(client, creato["codice"], "tablet").status_code == 422
    assert _abbina_col_tipo(client, creato["codice"], "computer").status_code == 200


def test_codice_sbagliato_scaduto_o_usato_stessa_risposta(client, orologio):
    creato = nuovo_dispositivo(client, 1, "Computer", "computer")
    for codice in ("abc", "12345", _codice_sbagliato(creato["codice"])):
        r = abbina(client, codice)
        assert r.status_code == 409 and r.json()["detail"] == {"errore": "codice_non_valido"}


def test_riabbinare_da_un_token_nuovo_e_la_storia_continua(client):
    """Telefono reinstallato: un codice nuovo per lo stesso dispositivo. Il token
    vecchio vale fino all'abbinamento, poi 401; eventi e regole restano suoi."""
    headers, dispositivo_id = dispositivo_abbinato(client, 1, "Computer", "computer")
    eventi(client, headers, {"id": "prima-del-riabbinamento", "tipo": "manomissione",
                             "dettagli": {"sotto_tipo": "cambio_ora"}})
    codice = client.post(f"/api/dispositivi/{dispositivo_id}/codice", headers=GENITORE).json()["codice"]
    assert client.get("/api/patto", headers=headers).status_code == 200  # non ancora
    nuovo = abbina(client, codice).json()
    assert client.get("/api/patto", headers=headers).status_code == 401
    patto = client.get("/api/patto", headers=auth(nuovo["token"]))
    assert patto.status_code == 200 and patto.json()["dispositivo"]["id"] == dispositivo_id
    finestra = client.get("/api/finestra", headers=GENITORE).json()
    assert [(e["id"], e["dispositivo_id"]) for e in finestra["manomissioni_recenti"]] == [
        ("prima-del-riabbinamento", dispositivo_id),
    ]


def test_il_telefono_07_passa_al_codice(client):
    """Il dispositivo 1 (token d'ambiente) si abbina col codice: il token
    d'ambiente smette di valere, quello nuovo parla per lo stesso telefono."""
    codice = client.post("/api/dispositivi/1/codice", headers=GENITORE).json()["codice"]
    nuovo = abbina(client, codice).json()
    assert nuovo["dispositivo"] == {"id": 1, "nome": "Telefono", "tipo": "telefono"}
    assert client.get("/api/patto", headers=FIGLIO).status_code == 401
    assert client.post("/api/battito", json={}, headers=auth(nuovo["token"])).status_code == 200


# --- revoca ---

def test_revoca_spegne_il_token_e_non_cancella_niente(client):
    headers, dispositivo_id = dispositivo_abbinato(client, 1, "Computer", "computer")
    eventi(client, headers, {"id": "sfor-pc", "tipo": "sforamento", "dettagli": {"regola_id": 99}})
    codice_aperto = client.post(f"/api/dispositivi/{dispositivo_id}/codice", headers=GENITORE).json()["codice"]
    r = client.delete(f"/api/dispositivi/{dispositivo_id}", headers=GENITORE)
    assert r.status_code == 200 and r.json() == {"id": dispositivo_id, "revocato": True}
    assert client.get("/api/patto", headers=headers).status_code == 401
    assert client.post("/api/battito", json={}, headers=headers).status_code == 401
    assert abbina(client, codice_aperto).status_code == 409  # i codici aperti si annullano
    r = client.post(f"/api/dispositivi/{dispositivo_id}/codice", headers=GENITORE)
    assert r.status_code == 409 and r.json()["detail"] == {"errore": "dispositivo_revocato"}
    # la storia resta: il dispositivo c'e' (revocato) e il suo evento pure
    (figlio,) = _famiglia(client)["figli"]
    assert [(d["id"], d["revocato"]) for d in figlio["dispositivi"]] == [(1, False), (dispositivo_id, True)]
    finestra = client.get("/api/finestra", headers=GENITORE).json()
    assert [e["id"] for e in finestra["sforamenti_recenti"]] == ["sfor-pc"]
    assert finestra["dispositivi"][1]["revocato"] is True
    # rifarla non cambia niente
    assert client.delete(f"/api/dispositivi/{dispositivo_id}", headers=GENITORE).status_code == 200


def test_dispositivo_inesistente_404(client):
    assert client.post("/api/dispositivi/99/codice", headers=GENITORE).status_code == 404
    assert client.delete("/api/dispositivi/99", headers=GENITORE).status_code == 404


# --- contro chi prova i codici a caso ---

def _sbaglia(client, quante: int, codice_giusto: str):
    for _ in range(quante):
        r = abbina(client, _codice_sbagliato(codice_giusto))
        assert r.status_code == 409, r.text


def test_dieci_tentativi_falliti_bloccano_tutto_per_dieci_minuti(client, orologio):
    creato = nuovo_dispositivo(client, 1, "Computer", "computer")
    _sbaglia(client, 10, creato["codice"])
    r = abbina(client, creato["codice"])  # anche col codice giusto
    assert r.status_code == 429
    assert r.json()["detail"] == {"errore": "troppi_tentativi", "riprova_tra_secondi": 600}
    assert r.headers["retry-after"] == "600"
    orologio.avanza(minutes=4)
    r = abbina(client, creato["codice"])
    assert r.status_code == 429 and r.json()["detail"]["riprova_tra_secondi"] == 360
    orologio.avanza(minutes=6)  # dieci minuti dall'ultimo tentativo fallito
    assert abbina(client, creato["codice"]).status_code == 200


def test_nove_tentativi_falliti_non_bloccano(client):
    creato = nuovo_dispositivo(client, 1, "Computer", "computer")
    _sbaglia(client, 9, creato["codice"])
    assert abbina(client, creato["codice"]).status_code == 200


def test_tentativi_sparsi_su_piu_di_dieci_minuti_non_bloccano(client, orologio):
    creato = nuovo_dispositivo(client, 1, "Computer", "computer")
    for _ in range(10):
        _sbaglia(client, 1, creato["codice"])
        orologio.avanza(seconds=70)  # 10 tentativi in 10 minuti e 30 secondi
    assert abbina(client, creato["codice"]).status_code == 200


def test_i_tentativi_si_contano_su_tutto_il_server(client, orologio):
    """Il blocco non e' per dispositivo: chi prova codici a caso non sa di chi sono."""
    uno = nuovo_dispositivo(client, 1, "Computer", "computer")
    figlio = nuovo_figlio(client)
    altro = nuovo_dispositivo(client, figlio["id"], "Telefono", "telefono")
    _sbaglia(client, 5, uno["codice"])
    _sbaglia(client, 5, altro["codice"])
    assert abbina(client, altro["codice"]).status_code == 429
    assert abbina(client, uno["codice"]).status_code == 429


def test_durante_il_blocco_i_tentativi_non_si_contano(client, orologio):
    """Finito il blocco si riparte da capo: un solo errore non blocca di nuovo."""
    creato = nuovo_dispositivo(client, 1, "Computer", "computer")
    _sbaglia(client, 10, creato["codice"])
    for _ in range(5):
        assert abbina(client, _codice_sbagliato(creato["codice"])).status_code == 429
    orologio.avanza(minutes=10)
    _sbaglia(client, 1, creato["codice"])
    assert abbina(client, creato["codice"]).status_code == 200


def test_due_abbinamenti_simultanei_con_lo_stesso_codice(client):
    creato = nuovo_dispositivo(client, 1, "Computer", "computer")
    quanti = 4
    barriera = threading.Barrier(quanti)
    esiti = []

    def prova():
        barriera.wait()
        esiti.append(abbina(client, creato["codice"]).status_code)

    fili = [threading.Thread(target=prova) for _ in range(quanti)]
    for f in fili:
        f.start()
    for f in fili:
        f.join()
    assert sorted(esiti) == [200] + [409] * (quanti - 1)


def test_codici_e_token_solo_come_hash(client, db_path):
    creato = nuovo_dispositivo(client, 1, "Computer", "computer")
    token = abbina(client, creato["codice"]).json()["token"]
    aperto = nuovo_dispositivo(client, 1, "Telefono nuovo", "telefono")["codice"]
    contenuto = Path(db_path).read_bytes()
    assert token.encode() not in contenuto
    conn = sqlite3.connect(db_path)
    try:
        codici = [r[0] for r in conn.execute("SELECT codice_hash FROM codici_abbinamento")]
    finally:
        conn.close()
    assert aperto not in codici and creato["codice"] not in codici
    assert all(len(h) == 64 for h in codici)


# --- la famiglia vista dal genitore ---

def test_forma_della_famiglia(client, orologio):
    client.post("/api/battito", json={"versione_app": "0.7.0"}, headers=FIGLIO)
    luca = nuovo_figlio(client, "Luca")
    headers, pc = dispositivo_abbinato(client, luca["id"], "Computer", "computer")
    nuovo_dispositivo(client, luca["id"], "Telefono", "telefono")  # non ancora abbinato
    eventi(client, headers, {"id": "m-luca", "tipo": "manomissione", "dettagli": {"sotto_tipo": "silenzio"}})
    famiglia = _famiglia(client)
    assert [f["id"] for f in famiglia["figli"]] == [1, luca["id"]]
    primo, secondo = famiglia["figli"]
    assert set(primo) == {"id", "nome", "striscia", "riepilogo", "notifiche_non_lette", "dispositivi"}
    assert len(primo["striscia"]) == 8
    assert primo["notifiche_non_lette"] == 0 and secondo["notifiche_non_lette"] == 1
    assert secondo["riepilogo"] == {"giorni_fuori_regola": 0, "interruzioni": 1}
    telefono = primo["dispositivi"][0]
    assert set(telefono) == {"id", "nome", "tipo", "abbinato", "revocato", "versione_app", "stato_silenzio"}
    assert telefono["versione_app"] == "0.7.0"
    assert set(telefono["stato_silenzio"]) == {"ultimo_battito", "silente", "spento", "spento_dal"}
    assert [(d["id"], d["tipo"], d["abbinato"]) for d in secondo["dispositivi"]] == [
        (pc, "computer", True), (pc + 1, "telefono", False),
    ]


# --- chi puo' chiamare cosa ---

GENITORE_SOLO = [
    ("GET", "/api/famiglia", None),
    ("POST", "/api/figli", {"nome": "X"}),
    ("PATCH", "/api/figli/1", {"nome": "X"}),
    ("POST", "/api/figli/1/dispositivi", {"nome": "PC", "tipo": "computer"}),
    ("POST", "/api/dispositivi/1/codice", None),
    ("DELETE", "/api/dispositivi/1", None),
]


@pytest.mark.parametrize("metodo,percorso,corpo", GENITORE_SOLO)
def test_endpoint_della_famiglia_solo_col_token_del_genitore(client, metodo, percorso, corpo):
    kwargs = {"json": corpo} if corpo is not None else {}
    assert client.request(metodo, percorso, **kwargs).status_code == 401
    assert client.request(metodo, percorso, headers=auth("inventato"), **kwargs).status_code == 401
    assert client.request(metodo, percorso, headers=FIGLIO, **kwargs).status_code == 403


def test_abbina_non_vuole_il_token(client):
    creato = nuovo_dispositivo(client, 1, "Computer", "computer")
    assert client.post("/api/abbina", json={"codice": creato["codice"]}).status_code == 200
    assert client.post("/api/abbina", json={}).status_code == 422
