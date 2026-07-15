"""Proposte del genitore (tappa 5): creazione col confronto/direzione calcolati dal
server, notifica al figlio, e risposta del figlio con AUTO-APPLICAZIONE delle
accettate (lock bypassato, parametri esatti, concordata=true nello storico;
eliminazione via marcatore che rispetta il vincolo ultima_regola)."""

from conftest import FIGLIO, GENITORE, crea_regola

MENO = "−"  # segno meno tipografico U+2212, come nel contratto


def _limite(minuti, app="TikTok"):
    return {"app_o_categoria": app, "minuti_al_giorno": minuti}


def _fascia(dalle="23:00", alle="07:00", giorni=None):
    return {"dalle": dalle, "alle": alle, "giorni": giorni or ["lun", "mar", "mer"]}


def _vita(descrizione="Cammino un'ora al giorno", arbitro="Mamma", frequenza="ogni giorno"):
    return {"descrizione": descrizione, "arbitro_nome": arbitro, "frequenza": frequenza}


def _proponi(client, regola_id, parametri, motivazione=None):
    corpo = {"regola_id": regola_id, "parametri_proposti": parametri}
    if motivazione:
        corpo["motivazione"] = motivazione
    return client.post("/api/proposte", json=corpo, headers=GENITORE)


def _rispondi(client, proposta_id, esito, motivazione=None):
    corpo = {"esito": esito}
    if motivazione:
        corpo["motivazione"] = motivazione
    return client.post(f"/api/proposte/{proposta_id}/risposta", json=corpo, headers=FIGLIO)


# --- creazione: confronto e direzione calcolati dal server ---

def test_proposta_limite_stringe_confronto(client):
    regola = crea_regola(client, parametri=_limite(60))
    risposta = _proponi(client, regola["id"], _limite(30), motivazione="troppo tempo")
    assert risposta.status_code == 200
    dati = risposta.json()
    assert dati["confronto"] == f"{MENO}30 min al giorno rispetto ad ora"
    assert dati["direzione"] == "stringe"
    assert dati["stato"] == "pendente"
    assert dati["usata"] is False
    assert dati["risposta"] is None
    assert dati["motivazione"] == "troppo tempo"


def test_proposta_limite_allenta_confronto(client):
    regola = crea_regola(client, parametri=_limite(60))
    dati = _proponi(client, regola["id"], _limite(90)).json()
    assert dati["confronto"] == "+30 min al giorno rispetto ad ora"
    assert dati["direzione"] == "allenta"


def test_proposta_limite_cambio_app_confronto(client):
    regola = crea_regola(client, parametri=_limite(60, app="TikTok"))
    dati = _proponi(client, regola["id"], _limite(60, app="Instagram")).json()
    assert dati["confronto"] == "da TikTok (60 min) a Instagram (60 min) al giorno"
    assert dati["direzione"] == "allenta"  # cambiare bersaglio conta come allentamento


def test_proposta_fascia_confronto_copertura(client):
    regola = crea_regola(client, tipo="fascia_oraria", parametri=_fascia(dalle="23:00", alle="07:00"))
    dati = _proponi(client, regola["id"], _fascia(dalle="22:00", alle="08:00")).json()
    assert dati["confronto"] == "orario da 23:00-07:00 a 22:00-08:00"
    assert dati["direzione"] == "stringe"  # copre di piu': stringe


def test_proposta_fascia_toglie_un_giorno(client):
    regola = crea_regola(
        client, tipo="fascia_oraria", parametri=_fascia(giorni=["lun", "mar", "mer"])
    )
    dati = _proponi(client, regola["id"], _fascia(giorni=["lun", "mar"])).json()
    assert dati["confronto"] == "giorni da 3 a 2"
    assert dati["direzione"] == "allenta"


def test_proposta_vita_reale_confronto(client):
    regola = crea_regola(client, tipo="vita_reale", parametri=_vita(frequenza="ogni giorno"))
    dati = _proponi(client, regola["id"], _vita(frequenza="tre volte a settimana")).json()
    assert "frequenza: ogni giorno -> tre volte a settimana" in dati["confronto"]
    assert dati["direzione"] == "allenta"


def test_proposta_eliminazione_marcatore(client):
    regola = crea_regola(client, parametri=_limite(60))
    dati = _proponi(client, regola["id"], {"azione": "elimina"}).json()
    assert dati["confronto"] == "propone di eliminare la regola"
    assert dati["direzione"] == "elimina"
    assert dati["parametri_proposti"] == {"azione": "elimina"}


# --- validazioni e conflitti ---

def test_proposta_su_regola_inesistente_409(client):
    risposta = _proponi(client, 999, _limite(30))
    assert risposta.status_code == 409
    assert risposta.json()["detail"]["errore"] == "regola_non_valida"


def test_proposta_su_regola_eliminata_409(client, orologio):
    crea_regola(client)
    regola = crea_regola(client, parametri=_limite(120, app="YouTube"))
    orologio.avanza(days=4)
    client.delete(f"/api/regole/{regola['id']}", headers=FIGLIO)
    risposta = _proponi(client, regola["id"], _limite(60, app="YouTube"))
    assert risposta.status_code == 409
    assert risposta.json()["detail"]["errore"] == "regola_non_valida"


def test_proposta_parametri_invalidi_422(client):
    regola = crea_regola(client, parametri=_limite(60))
    # minuti_al_giorno fuori scala per il tipo limite_tempo
    risposta = _proponi(client, regola["id"], {"app_o_categoria": "TikTok", "minuti_al_giorno": 0})
    assert risposta.status_code == 422


def test_una_sola_proposta_pendente_per_regola(client):
    regola = crea_regola(client, parametri=_limite(60))
    assert _proponi(client, regola["id"], _limite(30)).status_code == 200
    risposta = _proponi(client, regola["id"], _limite(45))
    assert risposta.status_code == 409
    assert risposta.json()["detail"]["errore"] == "proposta_gia_pendente"


def test_dopo_la_risposta_si_puo_riproporre(client):
    regola = crea_regola(client, parametri=_limite(60))
    prima = _proponi(client, regola["id"], _limite(30)).json()
    _rispondi(client, prima["id"], "rifiuta")
    # la pendente e' stata smaltita: se ne puo' fare un'altra
    assert _proponi(client, regola["id"], _limite(40)).status_code == 200


# --- notifica al figlio ---

def test_proposta_notifica_il_figlio(client):
    regola = crea_regola(client, parametri=_limite(60))
    _proponi(client, regola["id"], _limite(30))
    figlio = client.get("/api/notifiche", headers=FIGLIO).json()["notifiche"]
    nuove = [n for n in figlio if n["tipo"] == "nuova_proposta"]
    assert len(nuove) == 1
    assert nuove[0]["payload"]["confronto"] == f"{MENO}30 min al giorno rispetto ad ora"
    assert nuove[0]["payload"]["direzione"] == "stringe"
    assert nuove[0]["payload"]["regola_id"] == regola["id"]


# --- GET leggibile da entrambi ---

def test_get_proposte_entrambi(client):
    regola = crea_regola(client, parametri=_limite(60))
    _proponi(client, regola["id"], _limite(30))
    da_figlio = client.get("/api/proposte", headers=FIGLIO).json()
    da_genitore = client.get("/api/proposte", headers=GENITORE).json()
    assert da_figlio == da_genitore
    assert len(da_figlio["proposte"]) == 1


def test_get_proposte_dalla_piu_recente(client):
    regola = crea_regola(client, parametri=_limite(60))
    p1 = _proponi(client, regola["id"], _limite(30)).json()
    _rispondi(client, p1["id"], "rifiuta")
    p2 = _proponi(client, regola["id"], _limite(40)).json()
    ids = [p["id"] for p in client.get("/api/proposte", headers=GENITORE).json()["proposte"]]
    assert ids == [p2["id"], p1["id"]]


# --- risposta: solo pendenti ---

def test_risposta_solo_su_pendenti(client):
    regola = crea_regola(client, parametri=_limite(60))
    proposta = _proponi(client, regola["id"], _limite(30)).json()
    assert _rispondi(client, proposta["id"], "rifiuta").status_code == 200
    di_nuovo = _rispondi(client, proposta["id"], "accetta")
    assert di_nuovo.status_code == 409
    assert di_nuovo.json()["detail"]["errore"] == "proposta_non_pendente"


def test_risposta_proposta_inesistente_404(client):
    assert _rispondi(client, 999, "accetta").status_code == 404


# --- accetta: auto-applicazione della modifica concordata ---

def test_accetta_applica_e_scavalca_il_lock(client):
    """La regola e' appena stata creata (lock attivo). Il genitore propone un
    allentamento, il figlio accetta: la modifica si applica SUBITO, senza aspettare
    i 4 giorni. E' l'unico modo di allentare dentro il lock."""
    regola = crea_regola(client, parametri=_limite(60))
    proposta = _proponi(client, regola["id"], _limite(120)).json()
    risposta = _rispondi(client, proposta["id"], "accetta", motivazione="ok ci sto")
    assert risposta.status_code == 200
    dati = risposta.json()
    assert dati["regola"]["parametri"]["minuti_al_giorno"] == 120
    assert dati["proposta"]["stato"] == "accettata"
    assert dati["proposta"]["usata"] is True
    assert dati["proposta"]["risposta"] == {
        "esito": "accetta",
        "motivazione": "ok ci sto",
        "ts_server": dati["proposta"]["risposta"]["ts_server"],
    }
    # la regola vigente e' davvero cambiata
    regole = client.get("/api/regole", headers=FIGLIO).json()["regole"]
    assert regole[0]["parametri"]["minuti_al_giorno"] == 120


def test_accetta_registra_concordata_nello_storico(client):
    regola = crea_regola(client, parametri=_limite(60))
    proposta = _proponi(client, regola["id"], _limite(120)).json()
    _rispondi(client, proposta["id"], "accetta")
    storico = client.get("/api/finestra", headers=GENITORE).json()["storico_modifiche"]
    assert storico[0]["azione"] == "modifica"
    assert storico[0]["direzione"] == "allenta"
    assert storico[0]["concordata"] is True
    assert storico[0]["dopo"]["minuti_al_giorno"] == 120


def test_accetta_resetta_il_lock(client):
    """Dopo l'auto-applicazione il lock riparte da adesso: un nuovo allentamento
    normale (senza proposta) e' di nuovo bloccato."""
    regola = crea_regola(client, parametri=_limite(60))
    proposta = _proponi(client, regola["id"], _limite(120)).json()
    _rispondi(client, proposta["id"], "accetta")
    ancora = client.patch(
        f"/api/regole/{regola['id']}", json={"parametri": _limite(180)}, headers=FIGLIO
    )
    assert ancora.status_code == 409
    assert ancora.json()["detail"]["errore"] == "lock_attivo"


def test_accetta_eliminazione_applica(client):
    crea_regola(client)  # la regola che resta
    regola = crea_regola(client, parametri=_limite(120, app="YouTube"))
    proposta = _proponi(client, regola["id"], {"azione": "elimina"}).json()
    risposta = _rispondi(client, proposta["id"], "accetta")
    assert risposta.status_code == 200
    assert risposta.json()["regola"] is None  # eliminata
    assert risposta.json()["proposta"]["usata"] is True
    # non e' piu' nel patto vigente, ma resta nella finestra
    attive = client.get("/api/regole", headers=FIGLIO).json()["regole"]
    assert regola["id"] not in [r["id"] for r in attive]
    storico = client.get("/api/finestra", headers=GENITORE).json()["storico_modifiche"]
    assert storico[0]["azione"] == "eliminazione"
    assert storico[0]["concordata"] is True


def test_accetta_eliminazione_ultima_regola_bloccata(client):
    """Anche concordata, l'eliminazione dell'ultima regola resta vietata: la
    proposta NON si consuma e resta pendente."""
    regola = crea_regola(client, parametri=_limite(60))
    proposta = _proponi(client, regola["id"], {"azione": "elimina"}).json()
    risposta = _rispondi(client, proposta["id"], "accetta")
    assert risposta.status_code == 409
    assert risposta.json()["detail"]["errore"] == "ultima_regola"
    # la regola e' ancora viva e la proposta e' ancora pendente
    assert len(client.get("/api/regole", headers=FIGLIO).json()["regole"]) == 1
    rimasta = client.get("/api/proposte", headers=GENITORE).json()["proposte"][0]
    assert rimasta["stato"] == "pendente"
    assert rimasta["usata"] is False


def test_accetta_stringe_si_applica_come_concordata(client):
    regola = crea_regola(client, parametri=_limite(60))
    proposta = _proponi(client, regola["id"], _limite(30)).json()
    risposta = _rispondi(client, proposta["id"], "accetta").json()
    assert risposta["regola"]["parametri"]["minuti_al_giorno"] == 30
    storico = client.get("/api/finestra", headers=GENITORE).json()["storico_modifiche"]
    assert storico[0]["direzione"] == "stringe"
    assert storico[0]["concordata"] is True


# --- rifiuta: registra soltanto ---

def test_rifiuta_non_applica_nulla(client):
    regola = crea_regola(client, parametri=_limite(60))
    proposta = _proponi(client, regola["id"], _limite(120)).json()
    risposta = _rispondi(client, proposta["id"], "rifiuta", motivazione="no grazie")
    assert risposta.status_code == 200
    assert risposta.json()["regola"] is None
    dati = risposta.json()["proposta"]
    assert dati["stato"] == "rifiutata"
    assert dati["usata"] is False
    assert dati["risposta"]["esito"] == "rifiuta"
    # la regola e' rimasta com'era e lo storico ha solo la creazione
    regole = client.get("/api/regole", headers=FIGLIO).json()["regole"]
    assert regole[0]["parametri"]["minuti_al_giorno"] == 60
    storico = client.get("/api/finestra", headers=GENITORE).json()["storico_modifiche"]
    assert [s["azione"] for s in storico] == ["creazione"]


# --- notifica al genitore in entrambi i casi ---

def test_risposta_notifica_il_genitore(client):
    regola = crea_regola(client, parametri=_limite(60))
    proposta = _proponi(client, regola["id"], _limite(120)).json()
    _rispondi(client, proposta["id"], "accetta")
    genitore = client.get("/api/notifiche", headers=GENITORE).json()["notifiche"]
    risposte = [n for n in genitore if n["tipo"] == "proposta_risposta"]
    assert len(risposte) == 1
    assert risposte[0]["payload"]["esito"] == "accetta"
    assert risposte[0]["payload"]["regola_id"] == regola["id"]
