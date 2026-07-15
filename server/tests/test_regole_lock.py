"""Il lock asimmetrico e' la regola di business centrale: allentare aspetta
4 giorni dall'ultima creazione/modifica, stringere e' immediato, una proposta
accettata (concordata) scavalca il lock."""

from conftest import FIGLIO, GENITORE, crea_regola, inserisci_proposta

UN_GIORNO = 86400


def _patch(client, regola_id, parametri, proposta_id=None):
    corpo = {"parametri": parametri}
    if proposta_id is not None:
        corpo["proposta_id"] = proposta_id
    return client.patch(f"/api/regole/{regola_id}", json=corpo, headers=FIGLIO)


def _limite(minuti, app="TikTok"):
    return {"app_o_categoria": app, "minuti_al_giorno": minuti}


# --- allentare dentro i 4 giorni: bloccato con conto alla rovescia esatto ---

def test_allentare_subito_dopo_la_creazione_bloccato(client):
    regola = crea_regola(client, parametri=_limite(60))
    risposta = _patch(client, regola["id"], _limite(90))
    assert risposta.status_code == 409
    dettaglio = risposta.json()["detail"]
    assert dettaglio["errore"] == "lock_attivo"
    assert dettaglio["secondi_rimanenti"] == 4 * UN_GIORNO


def test_allentare_dopo_un_giorno_bloccato_con_conto_esatto(client, orologio):
    regola = crea_regola(client, parametri=_limite(60))
    orologio.avanza(days=1)
    risposta = _patch(client, regola["id"], _limite(90))
    assert risposta.status_code == 409
    assert risposta.json()["detail"]["secondi_rimanenti"] == 3 * UN_GIORNO


def test_allentare_dopo_4_giorni_esatti_permesso(client, orologio):
    regola = crea_regola(client, parametri=_limite(60))
    orologio.avanza(days=4)
    risposta = _patch(client, regola["id"], _limite(90))
    assert risposta.status_code == 200
    assert risposta.json()["parametri"]["minuti_al_giorno"] == 90


def test_stringere_e_immediato(client):
    regola = crea_regola(client, parametri=_limite(60))
    risposta = _patch(client, regola["id"], _limite(30))
    assert risposta.status_code == 200
    assert risposta.json()["parametri"]["minuti_al_giorno"] == 30


def test_stringere_azzera_il_conto_del_lock(client, orologio):
    regola = crea_regola(client, parametri=_limite(60))
    orologio.avanza(days=3)
    assert _patch(client, regola["id"], _limite(30)).status_code == 200
    orologio.avanza(days=2)  # 5 giorni dalla creazione, 2 dalla stretta
    risposta = _patch(client, regola["id"], _limite(45))
    assert risposta.status_code == 409
    assert risposta.json()["detail"]["secondi_rimanenti"] == 2 * UN_GIORNO
    orologio.avanza(days=2)
    assert _patch(client, regola["id"], _limite(45)).status_code == 200


def test_cambiare_app_bersaglio_conta_come_allentamento(client):
    regola = crea_regola(client, parametri=_limite(60, app="TikTok"))
    risposta = _patch(client, regola["id"], _limite(60, app="Instagram"))
    assert risposta.status_code == 409


# --- fascia_oraria: allargare la fascia stringe, accorciarla allenta ---

def _fascia(dalle="23:00", alle="07:00", giorni=None):
    return {"dalle": dalle, "alle": alle, "giorni": giorni or ["lun", "mar", "mer", "gio", "ven", "sab", "dom"]}


def test_fascia_allargata_e_immediata(client):
    regola = crea_regola(client, tipo="fascia_oraria", parametri=_fascia())
    risposta = _patch(client, regola["id"], _fascia(dalle="22:00", alle="08:00"))
    assert risposta.status_code == 200


def test_fascia_accorciata_bloccata(client):
    regola = crea_regola(client, tipo="fascia_oraria", parametri=_fascia())
    risposta = _patch(client, regola["id"], _fascia(dalle="23:30"))
    assert risposta.status_code == 409


def test_togliere_un_giorno_alla_fascia_bloccato(client):
    regola = crea_regola(client, tipo="fascia_oraria", parametri=_fascia(giorni=["ven", "sab"]))
    risposta = _patch(client, regola["id"], _fascia(giorni=["sab"]))
    assert risposta.status_code == 409


def test_aggiungere_un_giorno_alla_fascia_e_immediato(client):
    regola = crea_regola(client, tipo="fascia_oraria", parametri=_fascia(giorni=["ven", "sab"]))
    risposta = _patch(client, regola["id"], _fascia(giorni=["gio", "ven", "sab"]))
    assert risposta.status_code == 200


def test_fascia_spostata_a_parita_di_durata_bloccata(client):
    regola = crea_regola(client, tipo="fascia_oraria", parametri=_fascia(dalle="23:00", alle="07:00"))
    risposta = _patch(client, regola["id"], _fascia(dalle="22:00", alle="06:00"))
    assert risposta.status_code == 409  # non copre piu' le 06:00-07:00: non e' una stretta


# --- vita_reale: scelta conservativa, ogni modifica e' un allentamento ---

def _vita(descrizione="Cammino un'ora al giorno"):
    return {"descrizione": descrizione, "arbitro_nome": "Mamma", "frequenza": "ogni giorno"}


def test_vita_reale_ogni_modifica_bloccata(client):
    regola = crea_regola(client, tipo="vita_reale", parametri=_vita())
    risposta = _patch(client, regola["id"], _vita(descrizione="Cammino mezz'ora"))
    assert risposta.status_code == 409


def test_vita_reale_modificabile_dopo_4_giorni(client, orologio):
    regola = crea_regola(client, tipo="vita_reale", parametri=_vita())
    orologio.avanza(days=4)
    assert _patch(client, regola["id"], _vita(descrizione="Corro")).status_code == 200


# --- DELETE = sempre allentare ---

def test_eliminare_dentro_il_lock_bloccato(client):
    crea_regola(client)
    regola = crea_regola(client, parametri=_limite(120, app="YouTube"))
    risposta = client.delete(f"/api/regole/{regola['id']}", headers=FIGLIO)
    assert risposta.status_code == 409
    assert risposta.json()["detail"]["errore"] == "lock_attivo"


def test_eliminare_dopo_4_giorni_permesso(client, orologio):
    prima = crea_regola(client)
    regola = crea_regola(client, parametri=_limite(120, app="YouTube"))
    orologio.avanza(days=4)
    risposta = client.delete(f"/api/regole/{regola['id']}", headers=FIGLIO)
    assert risposta.status_code == 200
    ids = [r["id"] for r in client.get("/api/regole", headers=FIGLIO).json()["regole"]]
    assert ids == [prima["id"]]


def test_ultima_regola_non_eliminabile(client, orologio):
    regola = crea_regola(client)
    orologio.avanza(days=5)
    risposta = client.delete(f"/api/regole/{regola['id']}", headers=FIGLIO)
    assert risposta.status_code == 409
    assert risposta.json()["detail"]["errore"] == "ultima_regola"


# --- concordata: la proposta accettata scavalca il lock ---

def test_proposta_accettata_scavalca_il_lock(client, db_path):
    regola = crea_regola(client, parametri=_limite(60))
    proposta_id = inserisci_proposta(db_path, regola["id"], parametri=_limite(120))
    risposta = _patch(client, regola["id"], _limite(120), proposta_id=proposta_id)
    assert risposta.status_code == 200
    assert risposta.json()["parametri"]["minuti_al_giorno"] == 120
    storico = client.get("/api/finestra", headers=GENITORE).json()["storico_modifiche"]
    assert storico[0]["concordata"] is True
    assert storico[0]["direzione"] == "allenta"


def test_proposta_in_attesa_non_scavalca(client, db_path):
    regola = crea_regola(client, parametri=_limite(60))
    proposta_id = inserisci_proposta(db_path, regola["id"], stato="in_attesa", parametri=_limite(120))
    risposta = _patch(client, regola["id"], _limite(120), proposta_id=proposta_id)
    assert risposta.status_code == 400
    assert risposta.json()["detail"]["errore"] == "proposta_non_valida"


def test_proposta_di_altra_regola_non_vale(client, db_path):
    regola_a = crea_regola(client, parametri=_limite(60))
    regola_b = crea_regola(client, parametri=_limite(45, app="Instagram"))
    proposta_id = inserisci_proposta(db_path, regola_a["id"], parametri=_limite(120))
    risposta = _patch(client, regola_b["id"], _limite(90, app="Instagram"), proposta_id=proposta_id)
    assert risposta.status_code == 400


def test_proposta_usabile_una_sola_volta(client, db_path):
    regola = crea_regola(client, parametri=_limite(60))
    proposta_id = inserisci_proposta(db_path, regola["id"], parametri=_limite(120))
    assert _patch(client, regola["id"], _limite(120), proposta_id=proposta_id).status_code == 200
    risposta = _patch(client, regola["id"], _limite(180), proposta_id=proposta_id)
    assert risposta.status_code == 400


def test_eliminazione_concordata_scavalca_il_lock(client, db_path):
    crea_regola(client)
    regola = crea_regola(client, parametri=_limite(120, app="YouTube"))
    # una proposta di eliminazione porta il marcatore {"azione": "elimina"} (db.py)
    proposta_id = inserisci_proposta(db_path, regola["id"], parametri={"azione": "elimina"})
    risposta = client.delete(
        f"/api/regole/{regola['id']}?proposta_id={proposta_id}", headers=FIGLIO
    )
    assert risposta.status_code == 200


def test_eliminazione_con_proposta_di_modifica_non_vale(client, db_path):
    """Una proposta accettata di MODIFICA non autorizza un'eliminazione:
    serve il marcatore {"azione": "elimina"}. E la proposta non si brucia."""
    crea_regola(client)
    regola = crea_regola(client, parametri=_limite(120, app="YouTube"))
    proposta_id = inserisci_proposta(db_path, regola["id"], parametri=_limite(240, app="YouTube"))
    risposta = client.delete(
        f"/api/regole/{regola['id']}?proposta_id={proposta_id}", headers=FIGLIO
    )
    assert risposta.status_code == 409
    assert risposta.json()["detail"]["errore"] == "parametri_non_concordati"
    # la regola e' ancora viva e la proposta resta spendibile per il SUO scopo
    risposta = _patch(client, regola["id"], _limite(240, app="YouTube"), proposta_id=proposta_id)
    assert risposta.status_code == 200


def test_eliminazione_con_proposta_senza_parametri_non_vale(client, db_path):
    crea_regola(client)
    regola = crea_regola(client, parametri=_limite(120, app="YouTube"))
    proposta_id = inserisci_proposta(db_path, regola["id"], parametri=None)
    risposta = client.delete(
        f"/api/regole/{regola['id']}?proposta_id={proposta_id}", headers=FIGLIO
    )
    assert risposta.status_code == 409
    assert risposta.json()["detail"]["errore"] == "parametri_non_concordati"


# --- concordata = ESATTAMENTE i parametri della proposta ---

def test_proposta_applica_solo_i_parametri_concordati(client, db_path):
    """Il genitore ha accettato 90 minuti: il proposta_id non e' un lasciapassare
    per applicare 1440. Parametri diversi -> 409 e la proposta NON si consuma."""
    regola = crea_regola(client, parametri=_limite(60))
    proposta_id = inserisci_proposta(db_path, regola["id"], parametri=_limite(90))
    risposta = _patch(client, regola["id"], _limite(1440), proposta_id=proposta_id)
    assert risposta.status_code == 409
    assert risposta.json()["detail"]["errore"] == "parametri_non_concordati"
    # la regola non e' cambiata e non c'e' traccia nello storico
    regole = client.get("/api/regole", headers=FIGLIO).json()["regole"]
    assert regole[0]["parametri"]["minuti_al_giorno"] == 60
    storico = client.get("/api/finestra", headers=GENITORE).json()["storico_modifiche"]
    assert [s["azione"] for s in storico] == ["creazione"]
    # la proposta non e' bruciata: coi parametri concordati passa
    risposta = _patch(client, regola["id"], _limite(90), proposta_id=proposta_id)
    assert risposta.status_code == 200
    assert risposta.json()["parametri"]["minuti_al_giorno"] == 90


# --- una stretta ignora la proposta: niente consumo, niente concordata ---

def test_stringere_ignora_la_proposta(client, db_path):
    """La proposta serve solo a scavalcare il lock di un allentamento: su una
    stretta (gia' immediata) va ignorata, non consumata ne' registrata concordata."""
    regola = crea_regola(client, parametri=_limite(60))
    proposta_id = inserisci_proposta(db_path, regola["id"], parametri=_limite(120))
    risposta = _patch(client, regola["id"], _limite(30), proposta_id=proposta_id)
    assert risposta.status_code == 200
    storico = client.get("/api/finestra", headers=GENITORE).json()["storico_modifiche"]
    assert storico[0]["direzione"] == "stringe"
    assert storico[0]["concordata"] is False  # non era una modifica concordata
    # la proposta e' intatta: resta spendibile per l'allentamento concordato
    risposta = _patch(client, regola["id"], _limite(120), proposta_id=proposta_id)
    assert risposta.status_code == 200
    assert risposta.json()["parametri"]["minuti_al_giorno"] == 120


def test_stringere_con_proposta_inesistente_passa_comunque(client):
    regola = crea_regola(client, parametri=_limite(60))
    assert _patch(client, regola["id"], _limite(30), proposta_id=999).status_code == 200


def test_concordata_non_impostabile_dal_corpo(client):
    """Il client non puo' dichiararsi 'concordata' da solo: il campo non esiste nel corpo."""
    regola = crea_regola(client, parametri=_limite(60))
    risposta = client.patch(
        f"/api/regole/{regola['id']}",
        json={"parametri": _limite(90), "concordata": True},
        headers=FIGLIO,
    )
    assert risposta.status_code == 409  # il campo estraneo viene ignorato, il lock resta


# --- storico e notifiche ---

def test_ogni_modifica_applicata_finisce_nello_storico_e_notifica(client):
    regola = crea_regola(client, parametri=_limite(60))
    _patch(client, regola["id"], _limite(30))
    storico = client.get("/api/finestra", headers=GENITORE).json()["storico_modifiche"]
    assert [s["azione"] for s in storico] == ["modifica", "creazione"]
    assert storico[0]["direzione"] == "stringe"
    assert storico[0]["prima"]["minuti_al_giorno"] == 60
    assert storico[0]["dopo"]["minuti_al_giorno"] == 30
    notifiche = client.get("/api/notifiche", headers=GENITORE).json()["notifiche"]
    assert len([n for n in notifiche if n["tipo"] == "modifica_regola"]) == 2


def test_modifica_rifiutata_non_lascia_traccia(client):
    regola = crea_regola(client, parametri=_limite(60))
    assert _patch(client, regola["id"], _limite(90)).status_code == 409
    storico = client.get("/api/finestra", headers=GENITORE).json()["storico_modifiche"]
    assert [s["azione"] for s in storico] == ["creazione"]
    regole = client.get("/api/regole", headers=FIGLIO).json()["regole"]
    assert regole[0]["parametri"]["minuti_al_giorno"] == 60


# --- validazione e 404 ---

def test_parametri_sbagliati_422(client):
    risposta = client.post(
        "/api/regole",
        json={"tipo": "limite_tempo", "parametri": {"app_o_categoria": "TikTok"}},
        headers=FIGLIO,
    )
    assert risposta.status_code == 422


def test_tipo_sconosciuto_422(client):
    risposta = client.post(
        "/api/regole", json={"tipo": "coprifuoco", "parametri": {}}, headers=FIGLIO
    )
    assert risposta.status_code == 422


def test_regola_inesistente_404(client):
    assert _patch(client, 999, _limite(30)).status_code == 404
    assert client.delete("/api/regole/999", headers=FIGLIO).status_code == 404


def test_regola_eliminata_non_modificabile(client, orologio):
    crea_regola(client)
    regola = crea_regola(client, parametri=_limite(120, app="YouTube"))
    orologio.avanza(days=4)
    client.delete(f"/api/regole/{regola['id']}", headers=FIGLIO)
    assert _patch(client, regola["id"], _limite(30, app="YouTube")).status_code == 404
