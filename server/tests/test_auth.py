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
    ("POST", "/api/bonus", {"minuti": 5, "regola_id": 1}),
    ("GET", "/api/patto", None),
    ("POST", "/api/dichiarazioni", {"regola_id": 1, "esito": "successo"}),
    ("POST", "/api/proposte/1/risposta", {"esito": "accetta"}),
]
ENDPOINT_GENITORE = [
    ("GET", "/api/finestra", None),
    ("POST", "/api/proposte", {"regola_id": 1, "parametri_proposti": {}}),
    ("POST", "/api/dichiarazioni/1/verdetto", {"verdetto": "conferma"}),
]
# Dual-role (v2): serve un token valido di UNO dei due ruoli, ma nessuno dei due e' escluso.
ENDPOINT_ENTRAMBI = [
    ("GET", "/api/regole", None),
    ("GET", "/api/notifiche", None),
    ("POST", "/api/notifiche/1/letta", None),
    ("GET", "/api/proposte", None),
    ("GET", "/api/dichiarazioni", None),
]
TUTTI = ENDPOINT_FIGLIO + ENDPOINT_GENITORE + ENDPOINT_ENTRAMBI


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
    # La salute segnala anche ambiente e stato del database (tappa deploy).
    assert dati["env"] == "dev"
    assert dati["db_ok"] is True


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


@pytest.mark.parametrize("metodo,percorso,corpo", ENDPOINT_ENTRAMBI)
def test_endpoint_dual_role_accettano_entrambi_i_ruoli(client, metodo, percorso, corpo):
    # Con un token valido di uno qualsiasi dei due ruoli non si prende mai 401/403.
    for headers in (FIGLIO, GENITORE):
        codice = _chiama(client, metodo, percorso, corpo, headers).status_code
        assert codice not in (401, 403), (percorso, headers, codice)


def test_regole_leggibili_da_entrambi(client):
    crea_regola(client)
    per_figlio = client.get("/api/regole", headers=FIGLIO)
    per_genitore = client.get("/api/regole", headers=GENITORE)
    assert per_figlio.status_code == 200
    assert per_genitore.status_code == 200
    assert per_figlio.json() == per_genitore.json()
