"""(v3.3) Il limite sul totale del dispositivo (contratto-api.md, "v3.3 — limite sul
totale del dispositivo"): app_o_categoria = "totale" vale su telefoni e computer;
lo valutano le app e il loro sforamento colora il semaforo come per ogni altra
regola; bonus, blocco dei 4 giorni e proposte come per le altre limite_tempo; nella
finestra niente `nome` e il limite accanto al totale del giorno, mai accanto a
un'app."""

import pytest

from aiuti_v3 import dispositivo_abbinato, eventi, regola
from conftest import FIGLIO, GENITORE

OGGI = "2026-07-14"
UN_GIORNO = 86400
MENO = "−"  # segno meno tipografico U+2212, come nel contratto
TIKTOK = "com.zhiliaoapp.musically"
INSTAGRAM = "com.instagram.android"


@pytest.fixture
def pc(client):
    return dispositivo_abbinato(client, 1, "Computer di camera", "computer")


def _limite(chiave, minuti):
    return {"app_o_categoria": chiave, "minuti_al_giorno": minuti}


def _totale(minuti=180):
    return _limite("totale", minuti)


def _finestra(client):
    risposta = client.get("/api/finestra", headers=GENITORE)
    assert risposta.status_code == 200
    return risposta.json()


def _dispositivo(finestra, dispositivo_id):
    (voce,) = [d for d in finestra["dispositivi"] if d["id"] == dispositivo_id]
    return voce


def _regola_in(finestra, regola_id):
    (voce,) = [r for r in finestra["regole"] if r["id"] == regola_id]
    return voce


def _foto(client, headers, evento_id, totale_minuti, uso_minuti=None, giorno=OGGI, **extra):
    eventi(client, headers, {"id": evento_id, "tipo": "uso_giornaliero", "dettagli": {
        "giorno": giorno, "totale_minuti": totale_minuti, "uso_minuti": uso_minuti or {}, **extra}})


def _patch(client, regola_id, parametri, headers=FIGLIO):
    return client.patch(f"/api/regole/{regola_id}", json={"parametri": parametri}, headers=headers)


def _proponi(client, regola_id, parametri):
    return client.post(
        "/api/proposte", json={"regola_id": regola_id, "parametri_proposti": parametri}, headers=GENITORE
    )


def _rispondi(client, proposta_id, esito, headers=FIGLIO):
    return client.post(f"/api/proposte/{proposta_id}/risposta", json={"esito": esito}, headers=headers)


# --- la chiave: accettata sul telefono e sul computer ---

def test_totale_sul_telefono(client):
    creata = regola(client, FIGLIO, parametri=_totale(180))
    assert creata["parametri"] == _totale(180)
    assert creata["dispositivo"]["tipo"] == "telefono"


def test_totale_sul_computer(client, pc):
    headers, dispositivo_id = pc
    creata = regola(client, headers, parametri=_totale(120))
    assert creata["parametri"] == _totale(120)
    assert creata["dispositivo"] == {"id": dispositivo_id, "nome": "Computer di camera", "tipo": "computer"}
    # anche dal telefono su una regola del computer: conta il dispositivo della regola
    dal_telefono = regola(client, FIGLIO, parametri=_totale(90), dispositivo_id=dispositivo_id)
    assert dal_telefono["dispositivo_id"] == dispositivo_id


@pytest.mark.parametrize("chiave", ["Totale", "TOTALE", " totale", "totale ", "totale:", "tutto"])
def test_il_computer_vuole_totale_proprio_cosi(client, pc, chiave):
    """Solo "totale" esatto: per il resto le chiavi del computer restano come prima."""
    headers, _ = pc
    r = client.post("/api/regole", json={"tipo": "limite_tempo", "parametri": _limite(chiave, 60)},
                    headers=headers)
    assert r.status_code == 422, chiave
    assert r.json()["detail"][0]["loc"] == ["parametri", "app_o_categoria"]


# --- proposte: confronto, accettazione, eliminazione ---

def test_proposta_accettata_su_una_regola_totale(client):
    totale = regola(client, FIGLIO, parametri=_totale(180))
    risposta = _proponi(client, totale["id"], _totale(240))
    assert risposta.status_code == 200
    proposta = risposta.json()
    assert (proposta["confronto"], proposta["direzione"]) == ("+60 min al giorno rispetto ad ora", "allenta")
    patto = client.get("/api/patto", headers=FIGLIO).json()
    assert [p["id"] for p in patto["proposte_pendenti"]] == [proposta["id"]]

    # regola appena nata: da solo il figlio non potrebbe allentarla, la proposta accettata si'
    risposta = _rispondi(client, proposta["id"], "accetta")
    assert risposta.status_code == 200
    assert risposta.json()["proposta"]["stato"] == "accettata"
    assert risposta.json()["regola"]["parametri"] == _totale(240)
    ultima = _finestra(client)["storico_modifiche"][0]
    assert (ultima["azione"], ultima["direzione"], ultima["concordata"]) == ("modifica", "allenta", True)
    assert (ultima["prima"], ultima["dopo"]) == (_totale(180), _totale(240))


def test_proposta_che_stringe_il_totale(client):
    totale = regola(client, FIGLIO, parametri=_totale(180))
    proposta = _proponi(client, totale["id"], _totale(150)).json()
    assert (proposta["confronto"], proposta["direzione"]) == (f"{MENO}30 min al giorno rispetto ad ora", "stringe")


def test_eliminazione_concordata_di_una_regola_totale(client):
    regola(client, FIGLIO, parametri=_limite(TIKTOK, 60))  # l'ultima regola non si elimina
    totale = regola(client, FIGLIO, parametri=_totale(180))
    proposta = _proponi(client, totale["id"], {"azione": "elimina"}).json()
    assert proposta["direzione"] == "elimina"
    risposta = _rispondi(client, proposta["id"], "accetta")
    assert risposta.status_code == 200 and risposta.json()["regola"] is None
    attive = [r["id"] for r in client.get("/api/regole", headers=FIGLIO).json()["regole"]]
    assert totale["id"] not in attive


def test_confronto_col_cambio_di_bersaglio_dice_tutto_il_telefono(client):
    """"totale" non si scrive come se fosse un'app: il figlio legge "tutto il telefono"."""
    instagram = regola(client, FIGLIO, parametri=_limite(INSTAGRAM, 60))
    proposta = _proponi(client, instagram["id"], _totale(120)).json()
    atteso = f"da {INSTAGRAM} (60 min) a tutto il telefono (120 min) al giorno"
    assert (proposta["confronto"], proposta["direzione"]) == (atteso, "allenta")
    # ricalcolato in lettura finche' e' pendente: lo stesso testo per tutti e due
    assert client.get("/api/proposte", headers=GENITORE).json()["proposte"][0]["confronto"] == atteso
    assert client.get("/api/patto", headers=FIGLIO).json()["proposte_pendenti"][0]["confronto"] == atteso
    (notifica,) = [n for n in client.get("/api/notifiche", headers=FIGLIO).json()["notifiche"]
                   if n["tipo"] == "nuova_proposta"]
    assert notifica["payload"]["confronto"] == atteso


def test_confronto_col_cambio_di_bersaglio_dice_tutto_il_computer(client, pc):
    headers, _ = pc
    totale = regola(client, headers, parametri=_totale(180))
    # la chiave proposta deve andare bene per il computer
    assert _proponi(client, totale["id"], _limite("com.mojang.minecraftpe", 60)).status_code == 422
    proposta = _proponi(client, totale["id"], _limite("exe:minecraft.exe", 60)).json()
    assert proposta["confronto"] == "da tutto il computer (180 min) a exe:minecraft.exe (60 min) al giorno"
    assert proposta["direzione"] == "allenta"


# --- blocco dei 4 giorni ---

def test_blocco_dei_4_giorni_sul_totale(client, orologio):
    totale = regola(client, FIGLIO, parametri=_totale(180))
    # piu' minuti: allenta, bloccato col conto alla rovescia esatto
    r = _patch(client, totale["id"], _totale(240))
    assert r.status_code == 409
    assert r.json()["detail"]["errore"] == "lock_attivo"
    assert r.json()["detail"]["secondi_rimanenti"] == 4 * UN_GIORNO
    # dal totale a un'app: cambiare bersaglio allenta
    assert _patch(client, totale["id"], _limite(TIKTOK, 60)).status_code == 409
    # meno minuti: stringe, subito
    r = _patch(client, totale["id"], _totale(150))
    assert r.status_code == 200 and r.json()["parametri"] == _totale(150)
    orologio.avanza(days=4)
    r = _patch(client, totale["id"], _totale(200))
    assert r.status_code == 200 and r.json()["parametri"] == _totale(200)


def test_da_un_app_al_totale_e_un_cambio_di_bersaglio(client):
    tiktok = regola(client, FIGLIO, parametri=_limite(TIKTOK, 60))
    r = _patch(client, tiktok["id"], _totale(60))
    assert r.status_code == 409 and r.json()["detail"]["errore"] == "lock_attivo"


def test_eliminare_il_totale_aspetta_4_giorni_e_poi_il_limite_sparisce(client, orologio):
    regola(client, FIGLIO, parametri=_limite(TIKTOK, 60))
    totale = regola(client, FIGLIO, parametri=_totale(180))
    r = client.delete(f"/api/regole/{totale['id']}", headers=FIGLIO)
    assert r.status_code == 409 and r.json()["detail"]["errore"] == "lock_attivo"
    orologio.avanza(days=4)  # 18/07
    assert client.delete(f"/api/regole/{totale['id']}", headers=FIGLIO).status_code == 200
    _foto(client, FIGLIO, "foto-18", 200, giorno="2026-07-18")
    oggi = _finestra(client)["uso_recente"][-1]
    assert oggi["totale_minuti"] == 200 and "limite" not in oggi  # solo le regole attive


# --- la finestra del genitore ---

def test_finestra_regola_totale_senza_nome_e_limite_accanto_al_totale(client):
    tiktok = regola(client, FIGLIO, parametri=_limite(TIKTOK, 60))
    totale = regola(client, FIGLIO, parametri=_totale(180))
    _foto(client, FIGLIO, "foto-oggi", 192, {TIKTOK: 65, INSTAGRAM: 40},
          nomi={TIKTOK: "TikTok", INSTAGRAM: "Instagram"}, uso_categorie={"categoria:social": 105})
    finestra = _finestra(client)
    voce_totale = _regola_in(finestra, totale["id"])
    assert "nome" not in voce_totale  # "Tutto il telefono" lo scrive l'app dal tipo
    assert voce_totale["dispositivo"]["tipo"] == "telefono"
    assert voce_totale["semaforo"][-1] == {"data": OGGI, "stato": "verde"}
    assert _regola_in(finestra, tiktok["id"])["nome"] == "TikTok"

    assert finestra["uso_recente"][-1] == {
        "giorno": OGGI, "totale_minuti": 192,
        "sessioni_minuti": None,  # (v3.5) la fotografia non lo dice
        "limite": 180, "regola_id": totale["id"], "bonus": 0,
        "aggiornato_ts": "2026-07-14T10:00:00+00:00",
        "app": [
            {"chiave": TIKTOK, "nome": "TikTok", "minuti": 65,
             "limite": 60, "regola_id": tiktok["id"], "bonus": 0},
            {"chiave": INSTAGRAM, "nome": "Instagram", "minuti": 40},
        ],
        "categorie": [{"chiave": "categoria:social", "minuti": 105}],
    }
    # i giorni senza fotografia restano com'erano: nessun limite accanto a un totale che non c'e'
    for voce in finestra["uso_recente"][:-1]:
        assert voce == {"giorno": voce["giorno"], "totale_minuti": None, "sessioni_minuti": None,
                        "aggiornato_ts": None, "app": [], "categorie": []}
    # il primo livello vale per il primo dispositivo: la stessa lista
    assert _dispositivo(finestra, 1)["uso_recente"] == finestra["uso_recente"]


def test_finestra_totale_del_computer(client, pc):
    headers, dispositivo_id = pc
    totale = regola(client, headers, parametri=_totale(120))
    _foto(client, headers, "foto-pc", 131, {"exe:minecraft.exe": 70}, nomi={"exe:minecraft.exe": "Minecraft"})
    _foto(client, FIGLIO, "foto-tel", 50, {INSTAGRAM: 50})
    finestra = _finestra(client)
    voce_totale = _regola_in(finestra, totale["id"])
    assert "nome" not in voce_totale and voce_totale["dispositivo"]["tipo"] == "computer"
    oggi_pc = _dispositivo(finestra, dispositivo_id)["uso_recente"][-1]
    assert (oggi_pc["totale_minuti"], oggi_pc["limite"], oggi_pc["regola_id"], oggi_pc["bonus"]) == (
        131, 120, totale["id"], 0)
    assert "limite" not in oggi_pc["app"][0]
    # il limite del computer non finisce sul telefono
    oggi_telefono = finestra["uso_recente"][-1]
    assert oggi_telefono["totale_minuti"] == 50 and "limite" not in oggi_telefono


def test_totale_non_e_il_nome_di_un_app(client):
    """Una fotografia con una voce "totale" (un pacchetto Android non si chiama cosi',
    ma il server non si fida): il limite del totale non le si attacca e la regola
    non prende un nome da li'."""
    totale = regola(client, FIGLIO, parametri=_totale(180))
    _foto(client, FIGLIO, "foto-strana", 30, {"totale": 30}, nomi={"totale": "Finto"})
    finestra = _finestra(client)
    assert "nome" not in _regola_in(finestra, totale["id"])
    oggi = finestra["uso_recente"][-1]
    assert oggi["app"] == [{"chiave": "totale", "nome": "Finto", "minuti": 30}]
    assert (oggi["limite"], oggi["regola_id"]) == (180, totale["id"])


# --- bonus ---

def test_bonus_sulla_regola_totale(client, pc):
    totale = regola(client, FIGLIO, parametri=_totale(180))
    r = client.post("/api/bonus", json={"minuti": 15, "regola_id": totale["id"]}, headers=FIGLIO)
    assert r.status_code == 200
    assert r.json() == {"minuti": 15, "residuo_giorno": 15, "residuo_settimana": 75}
    # per il valutatore del telefono: il limite efficace di oggi e' 180 + 15
    patto = client.get("/api/patto", headers=FIGLIO).json()
    assert patto["bonus_oggi_per_regola"] == {str(totale["id"]): 15}
    # il genitore vede il bonus accanto al limite del totale: oltre vuol dire oltre 180 + 15
    _foto(client, FIGLIO, "foto-bonus", 190)
    oggi = _finestra(client)["uso_recente"][-1]
    assert (oggi["limite"], oggi["regola_id"], oggi["bonus"]) == (180, totale["id"], 15)
    # i bonus sono per dispositivo: il computer non allunga il totale del telefono
    headers_pc, _ = pc
    r = client.post("/api/bonus", json={"minuti": 5, "regola_id": totale["id"]}, headers=headers_pc)
    assert r.status_code == 409 and r.json()["detail"] == {"errore": "regola_non_valida"}


# --- sforamento e semaforo ---

def test_sforamento_del_totale_colora_di_rosso(client):
    totale = regola(client, FIGLIO, parametri=_totale(180))
    _foto(client, FIGLIO, "foto-oltre", 200)
    eventi(client, FIGLIO, {"id": "sfora-totale", "tipo": "sforamento", "dettagli": {
        "regola_id": totale["id"], "giorno": OGGI, "limite_efficace": 180, "minuti_oltre": 20}})
    finestra = _finestra(client)
    voce_totale = _regola_in(finestra, totale["id"])
    assert voce_totale["semaforo"][-1] == {"data": OGGI, "stato": "rosso"}
    assert finestra["striscia"][-1] == {"data": OGGI, "stato": "rosso"}
    assert _dispositivo(finestra, 1)["striscia"][-1]["stato"] == "rosso"
    assert finestra["riepilogo"]["giorni_fuori_regola"] == 1
    assert [e["id"] for e in finestra["sforamenti_recenti"]] == ["sfora-totale"]
    notifiche = client.get("/api/notifiche", headers=GENITORE).json()["notifiche"]
    assert [n["payload"]["dettagli"]["regola_id"] for n in notifiche if n["tipo"] == "sforamento"] == [
        totale["id"]]
    # il figlio vede lo stesso rosso
    patto = client.get("/api/patto", headers=FIGLIO).json()
    (nel_patto,) = [r for r in patto["regole"] if r["id"] == totale["id"]]
    assert nel_patto["semaforo"] == voce_totale["semaforo"]
    assert patto["striscia"] == finestra["striscia"]


def test_sforamento_del_totale_del_computer_resta_sul_computer(client, pc):
    headers, dispositivo_id = pc
    regola(client, FIGLIO, parametri=_limite(TIKTOK, 60))
    totale_pc = regola(client, headers, parametri=_totale(120))
    _foto(client, FIGLIO, "foto-tel", 30)
    _foto(client, headers, "foto-pc", 150)
    eventi(client, headers, {"id": "sfora-pc", "tipo": "sforamento", "dettagli": {
        "regola_id": totale_pc["id"], "giorno": OGGI, "limite_efficace": 120, "minuti_oltre": 30}})
    finestra = _finestra(client)
    assert _regola_in(finestra, totale_pc["id"])["semaforo"][-1]["stato"] == "rosso"
    assert _dispositivo(finestra, dispositivo_id)["striscia"][-1]["stato"] == "rosso"
    assert _dispositivo(finestra, 1)["striscia"][-1]["stato"] == "verde"
    assert finestra["striscia"][-1]["stato"] == "rosso"  # del figlio: basta una regola rossa
