"""(v3.5) Il controllo dei corpi JSON, per tutte le richieste sotto /api/ e prima di
qualsiasi route (app/controllo_corpo.py).

Python legge come JSON anche NaN, Infinity e i surrogati da soli ("\\ud800"): finiti
nel registro, farebbero cadere con un 500 ogni lettura che li ripresenta (finestra,
patto, sessioni, le notifiche del genitore). Si rifiutano con un 422 e non si scrive
niente; lo stesso per gli interi oltre i 64 bit. I corpi normali passano come prima.
Un numero oltre i 64 bit in un percorso o in una query: 422 (404 sulle sessioni), mai
un 500."""

import json

import pytest

from aiuti_v3 import eventi, regola
from conftest import FIGLIO, GENITORE

JSON_FIGLIO = {**FIGLIO, "Content-Type": "application/json"}
JSON_GENITORE = {**GENITORE, "Content-Type": "application/json"}
SURROGATO = "\\ud800"  # nel testo JSON: l'escape di un surrogato da solo
TROPPO = 2**63  # il primo intero che SQLite non sa tenere


def _sessioni(client) -> list:
    risposta = client.get("/api/sessioni", headers=FIGLIO)
    assert risposta.status_code == 200, risposta.text
    return risposta.json()["sessioni"]


def _letture_intatte(client) -> None:
    """Le letture che un valore sporco farebbe cadere rispondono ancora."""
    for percorso, headers in (("/api/finestra", GENITORE), ("/api/notifiche", GENITORE),
                              ("/api/famiglia", GENITORE), ("/api/patto", FIGLIO),
                              ("/api/sessioni", FIGLIO), ("/api/notifiche", FIGLIO)):
        risposta = client.get(percorso, headers=headers)
        assert risposta.status_code == 200, (percorso, risposta.text)


def _rifiutato(risposta) -> None:
    assert risposta.status_code == 422, risposta.text
    (errore,) = risposta.json()["detail"]
    assert errore["loc"] == ["body"]


# --- i surrogati da soli ---

@pytest.mark.parametrize("corpo", [
    '{"nome": "Stu' + SURROGATO + 'dio", "app": ["com.whatsapp"]}',
    '{"nome": "Studio", "app": ["com.whatsapp"], "nomi": {"com.whatsapp": "' + SURROGATO + '"}}',
    '{"nome": "Studio", "app": ["com.whatsapp"], "nomi": {"' + SURROGATO + '": "WhatsApp"}}',
])
def test_un_surrogato_in_una_sessione(client, corpo):
    _rifiutato(client.post("/api/sessioni", content=corpo, headers=JSON_FIGLIO))
    assert _sessioni(client) == []
    assert client.get("/api/notifiche", headers=GENITORE).json()["notifiche"] == []
    _letture_intatte(client)


def test_un_surrogato_nei_dettagli_di_uno_sforamento(client):
    """Senza il controllo lo sforamento finirebbe nel registro e nella notifica al
    genitore: le sue notifiche cadrebbero con un 500, e il figlio avrebbe fatto tacere
    tutti gli avvisi con una richiesta."""
    tiktok = regola(client, FIGLIO)
    corpo = ('{"eventi": [{"id": "sfor-sporco", "tipo": "sforamento", "dettagli": {"regola_id": '
             + str(tiktok["id"]) + ', "nota": "' + SURROGATO + '"}}]}')
    _rifiutato(client.post("/api/eventi", content=corpo, headers=JSON_FIGLIO))
    finestra = client.get("/api/finestra", headers=GENITORE).json()
    assert finestra["sforamenti_recenti"] == []
    tipi = [n["tipo"] for n in client.get("/api/notifiche", headers=GENITORE).json()["notifiche"]]
    assert "sforamento" not in tipi
    _letture_intatte(client)
    # lo stesso evento pulito passa (l'id non e' stato consumato)
    risposta = eventi(client, FIGLIO, {"id": "sfor-sporco", "tipo": "sforamento",
                                       "dettagli": {"regola_id": tiktok["id"]}})
    assert risposta["nuovi"] == 1


def test_un_surrogato_nei_nomi_di_una_fotografia(client):
    corpo = ('{"eventi": [{"id": "uso-sporco", "tipo": "uso_giornaliero", "dettagli": {"giorno": '
             '"2026-07-14", "uso_minuti": {"com.whatsapp": 5}, "totale_minuti": 5, "nomi": '
             '{"com.whatsapp": "Whats' + SURROGATO + 'App"}}}]}')
    _rifiutato(client.post("/api/eventi", content=corpo, headers=JSON_FIGLIO))
    oggi = client.get("/api/finestra", headers=GENITORE).json()["uso_recente"][-1]
    assert oggi["totale_minuti"] is None  # niente fotografia
    _letture_intatte(client)


def test_un_surrogato_coi_byte_grezzi(client):
    """Non solo l'escape: anche i byte di un surrogato scritti direttamente."""
    corpo = b'{"nome": "Stu\xed\xa0\x80dio", "app": ["com.whatsapp"]}'
    _rifiutato(client.post("/api/sessioni", content=corpo, headers=JSON_FIGLIO))
    assert _sessioni(client) == []


# --- NaN, Infinity e numeri fuori misura ---

@pytest.mark.parametrize("valore", ["NaN", "Infinity", "-Infinity", "1e400", "-1e400"])
def test_nan_e_infinito_nei_dettagli(client, valore):
    corpo = ('{"eventi": [{"id": "uso-nan", "tipo": "uso_giornaliero", "dettagli": {"giorno": '
             '"2026-07-14", "uso_minuti": {"com.whatsapp": ' + valore + '}, "totale_minuti": 5}}]}')
    _rifiutato(client.post("/api/eventi", content=corpo, headers=JSON_FIGLIO))
    assert client.get("/api/finestra", headers=GENITORE).json()["uso_recente"][-1]["totale_minuti"] is None
    _letture_intatte(client)


def test_un_battito_e_un_bonus_sporchi(client):
    """La versione dell'app di un battito finisce nella famiglia del genitore: con un
    surrogato, /api/famiglia cadrebbe. Lo stesso per il motivo di un bonus."""
    tiktok = regola(client, FIGLIO)
    for percorso, corpo in (
        ("/api/battito", '{"versione_app": "0.11' + SURROGATO + '"}'),
        ("/api/battito", '{"batteria": 50, "elapsed_realtime": NaN}'),
        ("/api/bonus", '{"minuti": 15, "regola_id": ' + str(tiktok["id"]) + ', "motivo": "' + SURROGATO + '"}'),
    ):
        _rifiutato(client.post(percorso, content=corpo, headers=JSON_FIGLIO))
    famiglia = client.get("/api/famiglia", headers=GENITORE).json()
    assert famiglia["figli"][0]["dispositivi"][0]["stato_silenzio"]["ultimo_battito"] is None
    assert client.get("/api/finestra", headers=GENITORE).json()["bonus"]["giorno"]["usati"] == 0
    _letture_intatte(client)


def test_interi_oltre_i_64_bit_nei_corpi(client):
    """SQLite non sa tenere un intero oltre i 64 bit: senza il controllo, un 500 a
    meta' scrittura (anche solo un totale_minuti enorme in una fotografia)."""
    regola(client, FIGLIO)
    corpo = {"regola_id": TROPPO, "parametri_proposti": {"azione": "elimina"}}
    _rifiutato(client.post("/api/proposte", content=json.dumps(corpo), headers=JSON_GENITORE))
    corpo = {"eventi": [{"id": "uso-enorme", "tipo": "uso_giornaliero",
                         "dettagli": {"giorno": "2026-07-14", "totale_minuti": TROPPO}}]}
    _rifiutato(client.post("/api/eventi", content=json.dumps(corpo), headers=JSON_FIGLIO))
    corpo = {"nome": "Studio", "app": ["com.whatsapp"], "nomi": {"com.whatsapp": "x"}, "extra": -TROPPO - 1}
    _rifiutato(client.post("/api/sessioni", content=json.dumps(corpo), headers=JSON_FIGLIO))
    # al limite si passa: e' la route a dire che la regola non c'e'
    corpo = {"regola_id": TROPPO - 1, "parametri_proposti": {"azione": "elimina"}}
    risposta = client.post("/api/proposte", content=json.dumps(corpo), headers=JSON_GENITORE)
    assert risposta.status_code == 409 and risposta.json()["detail"] == {"errore": "regola_non_valida"}
    assert client.get("/api/proposte", headers=GENITORE).json()["proposte"] == []
    assert client.get("/api/finestra", headers=GENITORE).json()["uso_recente"][-1]["totale_minuti"] is None
    _letture_intatte(client)


# --- i corpi normali passano come prima ---

def test_i_corpi_normali_passano(client):
    # un'emoji arriva come coppia di surrogati nell'escape: e' un carattere vero
    corpo = '{"nome": "\\ud83d\\udcda Studio", "app": ["com.whatsapp"], "nomi": {"com.whatsapp": "WhatsApp"}}'
    risposta = client.post("/api/sessioni", content=corpo, headers=JSON_FIGLIO)
    assert risposta.status_code == 201, risposta.text
    assert risposta.json()["nome"] == "📚 Studio"
    risposta = client.post("/api/sessioni", json={"nome": "Città", "app": ["com.whatsapp"],
                                                  "nomi": {"com.whatsapp": "Wätsäpp"}}, headers=FIGLIO)
    assert risposta.status_code == 201, risposta.text
    # numeri decimali normali nei dettagli
    eventi(client, FIGLIO, {"id": "uso-ok", "tipo": "uso_giornaliero",
                            "dettagli": {"giorno": "2026-07-14", "uso_minuti": {"com.whatsapp": 2.5},
                                         "totale_minuti": 3}})
    # un JSON rotto resta un 422 di FastAPI, come prima
    risposta = client.post("/api/sessioni", content='{"nome": ', headers=JSON_FIGLIO)
    assert risposta.status_code == 422 and risposta.json()["detail"][0]["type"] == "json_invalid"
    # e i corpi vuoti restano vuoti
    assert client.post("/api/segno", headers=GENITORE).status_code == 200
    assert client.post("/api/sessioni/in_corso/termina", headers=FIGLIO).status_code == 404
    assert client.get("/api/versione").status_code == 200
    _letture_intatte(client)


# --- numeri oltre i 64 bit nei percorsi e nelle query ---

def test_numeri_fuori_misura_nei_percorsi_e_nelle_query(client):
    regola(client, FIGLIO)
    fuori = {"detail": "numero fuori misura"}
    for metodo, percorso, headers, corpo in (
        ("PATCH", f"/api/regole/{TROPPO}", FIGLIO, {"parametri": {"app_o_categoria": "x", "minuti_al_giorno": 5}}),
        ("DELETE", f"/api/regole/{TROPPO}", FIGLIO, None),
        ("POST", f"/api/notifiche/{TROPPO}/letta", GENITORE, None),
        ("POST", f"/api/notifiche/{TROPPO}/letta", FIGLIO, None),
        ("POST", f"/api/proposte/{TROPPO}/risposta", FIGLIO, {"esito": "accetta"}),
        ("GET", f"/api/finestra?figlio_id={TROPPO}", GENITORE, None),
        ("GET", f"/api/sessioni?figlio_id={TROPPO}", GENITORE, None),
        ("GET", f"/api/notifiche?dopo_id={TROPPO}", GENITORE, None),
    ):
        risposta = client.request(metodo, percorso, headers=headers, json=corpo)
        assert risposta.status_code == 422 and risposta.json() == fuori, (percorso, risposta.text)
    # sulle sessioni: una sessione che non c'e'
    non_trovata = {"detail": "sessione non trovata"}
    for metodo, percorso, headers, corpo in (
        ("PATCH", f"/api/sessioni/{TROPPO}", FIGLIO, {"nome": "X"}),
        ("DELETE", f"/api/sessioni/{TROPPO}", FIGLIO, None),
        ("POST", f"/api/sessioni/{TROPPO}/avvia", FIGLIO, {"durata_minuti": 30}),
        ("POST", f"/api/sessioni/{TROPPO}/risposta", GENITORE, {"esito": "approva", "versione": 1}),
        ("POST", f"/api/sessioni/{-TROPPO - 1}/risposta", GENITORE, {"esito": "approva", "versione": 1}),
    ):
        risposta = client.request(metodo, percorso, headers=headers, json=corpo)
        assert risposta.status_code == 404 and risposta.json() == non_trovata, (percorso, risposta.text)
    _letture_intatte(client)
