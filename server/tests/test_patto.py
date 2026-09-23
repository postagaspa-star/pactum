"""GET /api/patto: lo stato completo del patto per il sync dell'app del figlio in
una risposta sola — regole attive (col semaforo, v2.4), residui bonus, bonus di oggi
per regola (per il limite efficace del valutatore locale), proposte pendenti,
dichiarazioni in attesa, fuso."""

from conftest import FIGLIO, GENITORE, crea_regola

CHIAVI_ATTESE = {
    "regole",
    "bonus",
    "bonus_oggi_per_regola",
    "proposte_pendenti",
    "dichiarazioni_in_attesa",
    "siti_recenti",
    "striscia",
    "riepilogo",
    "fuso",
    # (v3)
    "figlio",
    "dispositivo",
    "striscia_dispositivo",
    "dispositivi",
}


def _patto(client):
    risposta = client.get("/api/patto", headers=FIGLIO)
    assert risposta.status_code == 200
    return risposta.json()


def _vita(arbitro="Mamma"):
    return {"descrizione": "Cammino", "arbitro_nome": arbitro, "frequenza": "ogni giorno"}


def test_forma_del_patto(client):
    crea_regola(client)
    patto = _patto(client)
    assert set(patto.keys()) == CHIAVI_ATTESE
    assert patto["fuso"] == "Europe/Rome"
    assert patto["bonus"]["giorno"] == {"usati": 0, "tetto": 30, "residui": 30}
    assert patto["bonus_oggi_per_regola"] == {}
    assert patto["proposte_pendenti"] == []
    assert patto["dichiarazioni_in_attesa"] == []
    # (v2.4) le regole portano il semaforo, lo stesso della finestra del genitore (D3)
    assert len(patto["regole"][0]["semaforo"]) == 8


def test_patto_solo_regole_attive(client, orologio):
    crea_regola(client)  # resta
    eliminata = crea_regola(client, parametri={"app_o_categoria": "YouTube", "minuti_al_giorno": 120})
    orologio.avanza(days=4)
    client.delete(f"/api/regole/{eliminata['id']}", headers=FIGLIO)
    ids = [r["id"] for r in _patto(client)["regole"]]
    assert eliminata["id"] not in ids
    assert len(ids) == 1


def test_bonus_oggi_per_regola(client, orologio):
    a = crea_regola(client, parametri={"app_o_categoria": "TikTok", "minuti_al_giorno": 60})
    b = crea_regola(client, parametri={"app_o_categoria": "YouTube", "minuti_al_giorno": 90})
    # Il tetto giornaliero (30) e' globale: 15 + 5 su A e 5 su B stanno dentro.
    client.post("/api/bonus", json={"minuti": 15, "regola_id": a["id"]}, headers=FIGLIO)
    client.post("/api/bonus", json={"minuti": 5, "regola_id": a["id"]}, headers=FIGLIO)
    client.post("/api/bonus", json={"minuti": 5, "regola_id": b["id"]}, headers=FIGLIO)
    per_regola = _patto(client)["bonus_oggi_per_regola"]
    assert per_regola == {str(a["id"]): 20, str(b["id"]): 5}  # chiavi = regola_id come stringa


def test_bonus_di_ieri_non_conta_per_oggi(client, orologio):
    regola = crea_regola(client)
    client.post("/api/bonus", json={"minuti": 15, "regola_id": regola["id"]}, headers=FIGLIO)
    orologio.avanza(days=1)  # il giorno dopo
    assert _patto(client)["bonus_oggi_per_regola"] == {}


def test_patto_proposte_pendenti(client):
    regola = crea_regola(client)
    p = client.post(
        "/api/proposte",
        json={"regola_id": regola["id"], "parametri_proposti": {"app_o_categoria": "TikTok", "minuti_al_giorno": 30}},
        headers=GENITORE,
    ).json()
    pendenti = _patto(client)["proposte_pendenti"]
    assert [x["id"] for x in pendenti] == [p["id"]]
    assert pendenti[0]["stato"] == "pendente"
    # una volta risposta, sparisce dalle pendenti
    client.post(f"/api/proposte/{p['id']}/risposta", json={"esito": "rifiuta"}, headers=FIGLIO)
    assert _patto(client)["proposte_pendenti"] == []


def test_patto_dichiarazioni_in_attesa(client):
    limite = crea_regola(client)
    vita = crea_regola(client, tipo="vita_reale", parametri=_vita())
    # un successo resta in attesa, un fallimento no (registrata)
    successo = client.post(
        "/api/dichiarazioni", json={"regola_id": vita["id"], "esito": "successo"}, headers=FIGLIO
    ).json()
    client.post(
        "/api/dichiarazioni",
        json={"regola_id": vita["id"], "esito": "fallimento", "giorno": "2026-07-13"},
        headers=FIGLIO,
    )
    in_attesa = _patto(client)["dichiarazioni_in_attesa"]
    assert [d["id"] for d in in_attesa] == [successo["id"]]
    assert in_attesa[0]["stato"] == "in_attesa"
    _ = limite  # il limite serve solo a non lasciare il patto senza altre regole


def test_patto_dopo_il_verdetto_niente_in_attesa(client):
    vita = crea_regola(client, tipo="vita_reale", parametri=_vita())
    dic = client.post(
        "/api/dichiarazioni", json={"regola_id": vita["id"], "esito": "successo"}, headers=FIGLIO
    ).json()
    client.post(
        f"/api/dichiarazioni/{dic['id']}/verdetto", json={"verdetto": "conferma"}, headers=GENITORE
    )
    assert _patto(client)["dichiarazioni_in_attesa"] == []
