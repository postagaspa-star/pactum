"""La finestra del genitore: semaforo per regola (oggi + 7 giorni),
sforamenti e manomissioni recenti, storico, bonus residui, stato silenzio."""

from conftest import FIGLIO, GENITORE, crea_regola

CHIAVI_ATTESE = {
    "regole",
    "sforamenti_recenti",
    "manomissioni_recenti",
    "storico_modifiche",
    "bonus",
    "stato_silenzio",
}


def _finestra(client):
    risposta = client.get("/api/finestra", headers=GENITORE)
    assert risposta.status_code == 200
    return risposta.json()


def test_forma_della_finestra(client):
    crea_regola(client)
    finestra = _finestra(client)
    assert set(finestra.keys()) == CHIAVI_ATTESE
    assert finestra["bonus"]["giorno"] == {"usati": 0, "tetto": 30, "residui": 30}
    assert finestra["bonus"]["settimana"] == {"usati": 0, "tetto": 90, "residui": 90}
    regola = finestra["regole"][0]
    assert len(regola["semaforo"]) == 8
    assert regola["semaforo"][-1]["data"] == "2026-07-14"  # oggi in coda


def test_semaforo_colori(client, orologio):
    regola = crea_regola(client)  # creata il 14/07
    client.post(
        "/api/eventi",
        json={"eventi": [{"id": "b1", "tipo": "bonus_usato", "dettagli": {"regola_id": regola["id"]}}]},
        headers=FIGLIO,
    )
    orologio.avanza(days=1)  # 15/07
    client.post(
        "/api/eventi",
        json={"eventi": [{"id": "s1", "tipo": "sforamento", "dettagli": {"regola_id": regola["id"]}}]},
        headers=FIGLIO,
    )
    orologio.avanza(days=1)  # oggi = 16/07
    semaforo = {v["data"]: v["stato"] for v in _finestra(client)["regole"][0]["semaforo"]}
    assert semaforo["2026-07-13"] == "grigio"  # prima della creazione
    assert semaforo["2026-07-14"] == "giallo"  # bonus usato
    assert semaforo["2026-07-15"] == "rosso"  # sforamento
    assert semaforo["2026-07-16"] == "verde"  # oggi, nessun evento


def test_sforamento_vince_sul_bonus_nello_stesso_giorno(client):
    regola = crea_regola(client)
    client.post(
        "/api/eventi",
        json={"eventi": [
            {"id": "b2", "tipo": "bonus_usato", "dettagli": {"regola_id": regola["id"]}},
            {"id": "s2", "tipo": "sforamento", "dettagli": {"regola_id": regola["id"]}},
        ]},
        headers=FIGLIO,
    )
    semaforo = _finestra(client)["regole"][0]["semaforo"]
    assert semaforo[-1]["stato"] == "rosso"


def test_manomissioni_e_sforamenti_recenti(client):
    crea_regola(client)
    client.post(
        "/api/eventi",
        json={"eventi": [
            {"id": "m1", "tipo": "manomissione", "dettagli": {"sotto_tipo": "cambio_ora"}},
            {"id": "s3", "tipo": "sforamento", "dettagli": {"regola_id": 1}},
        ]},
        headers=FIGLIO,
    )
    finestra = _finestra(client)
    assert [e["id"] for e in finestra["manomissioni_recenti"]] == ["m1"]
    assert [e["id"] for e in finestra["sforamenti_recenti"]] == ["s3"]


def test_regola_eliminata_resta_visibile_nella_finestra(client, orologio):
    crea_regola(client)
    regola = crea_regola(client, parametri={"app_o_categoria": "YouTube", "minuti_al_giorno": 120})
    orologio.avanza(days=4)
    client.delete(f"/api/regole/{regola['id']}", headers=FIGLIO)
    finestra = _finestra(client)
    assert len(finestra["regole"]) == 2  # la finestra mostra anche le eliminate
    eliminata = [r for r in finestra["regole"] if r["id"] == regola["id"]][0]
    assert eliminata["attiva"] is False
    # ma GET /regole (il patto vigente) no
    assert len(client.get("/api/regole", headers=GENITORE).json()["regole"]) == 1


def test_bonus_residui_nella_finestra(client):
    crea_regola(client)
    client.post("/api/bonus", json={"minuti": 15}, headers=FIGLIO)
    bonus = _finestra(client)["bonus"]
    assert bonus["giorno"] == {"usati": 15, "tetto": 30, "residui": 15}
    assert bonus["settimana"] == {"usati": 15, "tetto": 90, "residui": 75}
