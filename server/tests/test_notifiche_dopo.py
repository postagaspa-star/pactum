"""(v3.3) GET /api/notifiche?dopo_id=N: solo le non lette arrivate dopo. L'app del
genitore 0.9 guarda ogni minuto e non deve riscaricare tutto a ogni giro."""

from conftest import FIGLIO, GENITORE, crea_regola


def _ids(client, headers, **params):
    r = client.get("/api/notifiche", headers=headers, params=params)
    assert r.status_code == 200, r.text
    return [n["id"] for n in r.json()["notifiche"]]


def test_senza_dopo_id_tutte_le_non_lette(client):
    for i in range(3):
        crea_regola(client, parametri={"app_o_categoria": f"com.app.{i}", "minuti_al_giorno": 30})
    tutte = _ids(client, GENITORE)
    assert len(tutte) >= 3
    assert tutte == sorted(tutte)


def test_dopo_id_da_solo_le_nuove(client):
    for i in range(3):
        crea_regola(client, parametri={"app_o_categoria": f"com.app.{i}", "minuti_al_giorno": 30})
    tutte = _ids(client, GENITORE)
    assert _ids(client, GENITORE, dopo_id=tutte[0]) == tutte[1:]
    assert _ids(client, GENITORE, dopo_id=tutte[-1]) == []
    assert _ids(client, GENITORE, dopo_id=0) == tutte


def test_dopo_id_non_mostra_le_lette(client):
    for i in range(2):
        crea_regola(client, parametri={"app_o_categoria": f"com.app.{i}", "minuti_al_giorno": 30})
    tutte = _ids(client, GENITORE)
    r = client.post(f"/api/notifiche/{tutte[-1]}/letta", headers=GENITORE)
    assert r.status_code in (200, 204), r.text
    assert tutte[-1] not in _ids(client, GENITORE, dopo_id=0)


def test_dopo_id_negativo_rifiutato(client):
    r = client.get("/api/notifiche", headers=GENITORE, params={"dopo_id": -1})
    assert r.status_code == 422


def test_dopo_id_vale_anche_per_il_dispositivo(client):
    assert _ids(client, FIGLIO, dopo_id=0) == _ids(client, FIGLIO)
