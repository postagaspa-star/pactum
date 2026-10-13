"""(v4.2) Le regole di un giorno: le promesse del bilancio della sera che il telefono
controlla. Valgono solo il loro giorno, si spengono da sole il giorno dopo, non tengono
in piedi il patto. Oggi nei test e' martedi' 14/07/2026, 12:00 a Roma."""

import pytest

from conftest import FIGLIO, GENITORE, crea_regola, fotografia_uso

SOCIAL = {"app_o_categoria": "categoria:social", "minuti_al_giorno": 60}
POMERIGGIO_MER = {"dalle": "15:00", "alle": "17:00", "giorni": ["mer"]}


def _crea(client, solo_il, tipo="limite_tempo", parametri=None):
    return client.post(
        "/api/regole",
        json={"tipo": tipo, "parametri": parametri or SOCIAL, "solo_il": solo_il},
        headers=FIGLIO,
    )


def _patto(client):
    r = client.get("/api/patto", headers=FIGLIO)
    assert r.status_code == 200, r.text
    return r.json()


def _sforamento(client, evento_id, regola_id, giorno):
    r = client.post(
        "/api/eventi",
        json={"eventi": [{"id": evento_id, "tipo": "sforamento",
                          "dettagli": {"regola_id": regola_id, "giorno": giorno}}]},
        headers=FIGLIO,
    )
    assert r.status_code == 200, r.text


def test_nasce_per_domani_e_lo_dice(client):
    crea_regola(client)  # la regola di sempre
    r = _crea(client, "2026-07-15")
    assert r.status_code == 201, r.text
    assert r.json()["solo_il"] == "2026-07-15"
    regole = {x["id"]: x for x in _patto(client)["regole"]}
    assert regole[r.json()["id"]]["solo_il"] == "2026-07-15"
    assert all(x["solo_il"] is None for i, x in regole.items() if i != r.json()["id"])
    finestra = client.get("/api/finestra", headers=GENITORE).json()
    assert {x["id"]: x["solo_il"] for x in finestra["regole"]}[r.json()["id"]] == "2026-07-15"


@pytest.mark.parametrize("giorno", ["2026-07-13", "2026-07-16", "2026-7-15", "domani", ""])
def test_solo_oggi_o_domani(client, giorno):
    r = _crea(client, giorno)
    assert r.status_code == 422, r.text


def test_la_vita_reale_non_vale_un_giorno(client):
    vita = {"descrizione": "Camminare", "arbitro_nome": "Mamma", "frequenza": "ogni giorno"}
    assert _crea(client, "2026-07-15", tipo="vita_reale", parametri=vita).status_code == 422


def test_una_fascia_di_un_giorno_vale_quel_giorno_e_non_passa_la_mezzanotte(client):
    assert _crea(client, "2026-07-15", "fascia_oraria", POMERIGGIO_MER).status_code == 201
    di_martedi = {**POMERIGGIO_MER, "giorni": ["mar"]}
    assert _crea(client, "2026-07-15", "fascia_oraria", di_martedi).status_code == 422
    notte = {"dalle": "22:00", "alle": "07:00", "giorni": ["mer"]}
    assert _crea(client, "2026-07-15", "fascia_oraria", notte).status_code == 422


def test_conta_solo_il_suo_giorno_poi_si_spegne(client, orologio):
    crea_regola(client)
    promessa = _crea(client, "2026-07-15").json()
    fotografia_uso(client, "2026-07-14")
    semaforo = {v["data"]: v["stato"] for v in _patto(client)["regole_un_giorno"][0]["semaforo"]}
    assert semaforo["2026-07-14"] == "grigio"  # oggi non vale ancora, anche coi dati

    orologio.avanza(days=1)  # mercoledi' 15: il suo giorno
    fotografia_uso(client, "2026-07-15")
    _sforamento(client, "sf-promessa", promessa["id"], "2026-07-15")
    patto = _patto(client)
    assert promessa["id"] in [r["id"] for r in patto["regole"]]
    voce = patto["regole_un_giorno"][0]
    assert voce["id"] == promessa["id"] and voce["giorno"] == "2026-07-15"
    assert voce["semaforo"][-1] == {"data": "2026-07-15", "stato": "rosso"}

    orologio.avanza(days=1)  # giovedi' 16: spenta
    fotografia_uso(client, "2026-07-16")
    patto = _patto(client)
    assert promessa["id"] not in [r["id"] for r in patto["regole"]]
    voce = patto["regole_un_giorno"][0]
    semaforo = {v["data"]: v["stato"] for v in voce["semaforo"]}
    assert semaforo["2026-07-15"] == "rosso"  # com'e' andata resta
    assert semaforo["2026-07-16"] == "grigio"
    # La striscia di oggi non la sente piu'.
    assert patto["striscia_qui"][-1]["stato"] == "verde"


def test_spenta_senza_avvisare_il_genitore_e_nello_storico(client, orologio, db_path):
    import sqlite3

    crea_regola(client)
    promessa = _crea(client, "2026-07-14").json()
    orologio.avanza(days=1)
    client.get("/api/finestra", headers=GENITORE)
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    regola = conn.execute("SELECT * FROM regole WHERE id = ?", (promessa["id"],)).fetchone()
    assert regola["attiva"] == 0
    assert regola["ultima_modifica_ts"] == "2026-07-14T21:59:59+00:00"  # 23:59:59 a Roma
    azioni = [r["azione"] for r in conn.execute(
        "SELECT azione FROM storico_modifiche WHERE regola_id = ? ORDER BY id", (promessa["id"],))]
    assert azioni == ["creazione", "eliminazione"]
    eliminazioni = conn.execute(
        "SELECT COUNT(*) FROM notifiche WHERE tipo = 'modifica_regola' AND messaggio LIKE '%eliminata%'"
    ).fetchone()[0]
    assert eliminazioni == 0
    conn.close()


def test_non_tiene_in_piedi_il_patto(client, orologio):
    sempre = crea_regola(client)
    _crea(client, "2026-07-14")
    orologio.avanza(days=4)
    r = client.delete(f"/api/regole/{sempre['id']}", headers=FIGLIO)
    assert r.status_code == 409 and r.json()["detail"] == {"errore": "ultima_regola"}


def test_il_lock_vale_anche_per_lei(client):
    crea_regola(client)
    promessa = _crea(client, "2026-07-15").json()
    # Una promessa non si toglie la sera stessa: e' una regola come le altre. E il
    # controllo "ultima regola" non c'entra: lei non tiene in piedi il patto.
    r = client.delete(f"/api/regole/{promessa['id']}", headers=FIGLIO)
    assert r.status_code == 409 and r.json()["detail"]["errore"] == "lock_attivo"


def test_la_lista_delle_regole_dice_solo_il(client):
    crea_regola(client)
    promessa = _crea(client, "2026-07-14").json()
    regole = client.get("/api/regole", headers=FIGLIO).json()["regole"]
    assert {r["id"]: r["solo_il"] for r in regole}[promessa["id"]] == "2026-07-14"
