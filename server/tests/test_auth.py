import pytest

from conftest import FIGLIO, GENITORE, crea_regola

CORPO_REGOLA = {"tipo": "limite_tempo", "parametri": {"app_o_categoria": "TikTok", "minuti_al_giorno": 60}}
CORPO_PATCH = {"parametri": {"app_o_categoria": "TikTok", "minuti_al_giorno": 30}}

ENDPOINT_FIGLIO = [
    ("POST", "/api/battito", {}),
    ("POST", "/api/eventi", {"eventi": []}),
    ("POST", "/api/regole", CORPO_REGOLA),
    ("PATCH", "/api/regole/1", CORPO_PATCH),
    ("DELETE", "/api/regole/1", None),
    ("POST", "/api/bonus", {"minuti": 5}),
]
ENDPOINT_GENITORE = [
    ("GET", "/api/finestra", None),
    ("GET", "/api/notifiche", None),
    ("POST", "/api/notifiche/1/letta", None),
]
TUTTI = ENDPOINT_FIGLIO + ENDPOINT_GENITORE + [("GET", "/api/regole", None)]


def _chiama(client, metodo, percorso, corpo, headers):
    kwargs = {"headers": headers}
    if corpo is not None:
        kwargs["json"] = corpo
    return client.request(metodo, percorso, **kwargs)


def test_salute_senza_token(client):
    risposta = client.get("/api/salute")
    assert risposta.status_code == 200
    dati = risposta.json()
    assert dati["stato"] == "ok"
    assert "versione" in dati


@pytest.mark.parametrize("metodo,percorso,corpo", TUTTI)
def test_token_mancante(client, metodo, percorso, corpo):
    assert _chiama(client, metodo, percorso, corpo, {}).status_code == 401


@pytest.mark.parametrize("metodo,percorso,corpo", TUTTI)
def test_token_sconosciuto(client, metodo, percorso, corpo):
    headers = {"Authorization": "Bearer token-inventato"}
    assert _chiama(client, metodo, percorso, corpo, headers).status_code == 401


@pytest.mark.parametrize("metodo,percorso,corpo", TUTTI)
def test_schema_sbagliato(client, metodo, percorso, corpo):
    headers = {"Authorization": "Basic abcdef"}
    assert _chiama(client, metodo, percorso, corpo, headers).status_code == 401


@pytest.mark.parametrize("metodo,percorso,corpo", ENDPOINT_FIGLIO)
def test_token_genitore_su_endpoint_figlio(client, metodo, percorso, corpo):
    assert _chiama(client, metodo, percorso, corpo, GENITORE).status_code == 403


@pytest.mark.parametrize("metodo,percorso,corpo", ENDPOINT_GENITORE)
def test_token_figlio_su_endpoint_genitore(client, metodo, percorso, corpo):
    assert _chiama(client, metodo, percorso, corpo, FIGLIO).status_code == 403


def test_regole_leggibili_da_entrambi(client):
    crea_regola(client)
    per_figlio = client.get("/api/regole", headers=FIGLIO)
    per_genitore = client.get("/api/regole", headers=GENITORE)
    assert per_figlio.status_code == 200
    assert per_genitore.status_code == 200
    assert per_figlio.json() == per_genitore.json()
