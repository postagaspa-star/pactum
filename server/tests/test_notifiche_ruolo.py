"""Notifiche con destinatario (v2): ciascun ruolo legge e marca SOLO le proprie.
Il figlio ora riceve nuove proposte e verdetti; il genitore tutto il resto."""

from conftest import FIGLIO, GENITORE, crea_regola


def _proponi(client, regola_id, minuti=30):
    return client.post(
        "/api/proposte",
        json={"regola_id": regola_id, "parametri_proposti": {"app_o_categoria": "TikTok", "minuti_al_giorno": minuti}},
        headers=GENITORE,
    ).json()


def test_ciascuno_vede_solo_le_proprie(client):
    regola = crea_regola(client)  # -> notifica modifica_regola al GENITORE
    _proponi(client, regola["id"])  # -> notifica nuova_proposta al FIGLIO
    tipi_figlio = [n["tipo"] for n in client.get("/api/notifiche", headers=FIGLIO).json()["notifiche"]]
    tipi_genitore = [n["tipo"] for n in client.get("/api/notifiche", headers=GENITORE).json()["notifiche"]]
    assert tipi_figlio == ["nuova_proposta"]
    assert "modifica_regola" in tipi_genitore
    assert "nuova_proposta" not in tipi_genitore


def test_destinatario_esposto(client):
    regola = crea_regola(client)
    _proponi(client, regola["id"])
    figlio = client.get("/api/notifiche", headers=FIGLIO).json()["notifiche"][0]
    genitore = client.get("/api/notifiche", headers=GENITORE).json()["notifiche"][0]
    assert figlio["destinatario"] == "figlio"
    assert genitore["destinatario"] == "genitore"


def test_marcare_come_letta_solo_le_proprie(client):
    regola = crea_regola(client)
    _proponi(client, regola["id"])
    id_genitore = client.get("/api/notifiche", headers=GENITORE).json()["notifiche"][0]["id"]
    id_figlio = client.get("/api/notifiche", headers=FIGLIO).json()["notifiche"][0]["id"]

    # il figlio non puo' marcare come letta una notifica del genitore -> 404
    assert client.post(f"/api/notifiche/{id_genitore}/letta", headers=FIGLIO).status_code == 404
    # la propria si', ed e' idempotente
    assert client.post(f"/api/notifiche/{id_figlio}/letta", headers=FIGLIO).status_code == 200
    assert client.post(f"/api/notifiche/{id_figlio}/letta", headers=FIGLIO).status_code == 200

    # dopo la marcatura, sparisce solo dalla lista del figlio
    assert client.get("/api/notifiche", headers=FIGLIO).json()["notifiche"] == []
    assert len(client.get("/api/notifiche", headers=GENITORE).json()["notifiche"]) >= 1
    # e il genitore non puo' marcare la notifica del figlio -> 404
    assert client.post(f"/api/notifiche/{id_figlio}/letta", headers=GENITORE).status_code == 404


def test_verdetto_notifica_al_figlio_non_al_genitore(client):
    vita = crea_regola(
        client, tipo="vita_reale",
        parametri={"descrizione": "Cammino", "arbitro_nome": "Mamma", "frequenza": "ogni giorno"},
    )
    dic = client.post(
        "/api/dichiarazioni", json={"regola_id": vita["id"], "esito": "successo"}, headers=FIGLIO
    ).json()
    client.post(
        f"/api/dichiarazioni/{dic['id']}/verdetto", json={"verdetto": "conferma"}, headers=GENITORE
    )
    tipi_figlio = [n["tipo"] for n in client.get("/api/notifiche", headers=FIGLIO).json()["notifiche"]]
    tipi_genitore = [n["tipo"] for n in client.get("/api/notifiche", headers=GENITORE).json()["notifiche"]]
    assert "verdetto" in tipi_figlio
    assert "verdetto" not in tipi_genitore
    assert "dichiarazione" in tipi_genitore  # la dichiarazione, invece, e' del genitore
