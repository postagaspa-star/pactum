"""(v3) Il cliente 0.7: le app installate sui telefoni devono continuare a
funzionare senza toccare niente. Qui si fanno le chiamate di una volta, coi token
d'ambiente e senza figlio_id, e si controllano le forme che le app 0.7 leggono: i
campi obbligatori dei loro modelli (app-figlio dati/ModelliPatto.kt, app-genitore
dati/Modelli.kt, decodificati con ignoreUnknownKeys: i campi nuovi non disturbano,
quelli mancanti o del tipo sbagliato si').

Due partenze: il database vero migrato dalla v2.4, e un'installazione nuova."""

import pytest
from fastapi.testclient import TestClient

import dati_v24
from conftest import FIGLIO, GENITORE, TOKEN_FIGLIO, TOKEN_GENITORE, Orologio

INTERO = int
TESTO = str
LISTA = list
OGGETTO = dict
BOOL = bool
NULLA = type(None)


def _forma(oggetto, obbligatori: dict, facoltativi: dict | None = None, dove="$"):
    """I campi che l'app 0.7 esige ci sono e hanno il tipo giusto; quelli che
    tollera, se ci sono, hanno il tipo giusto (null compreso dove l'app lo ammette)."""
    assert isinstance(oggetto, dict), dove
    for campo, tipo in obbligatori.items():
        assert campo in oggetto, f"{dove}.{campo} manca"
        assert isinstance(oggetto[campo], tipo) and not (
            tipo is INTERO and isinstance(oggetto[campo], bool)
        ), f"{dove}.{campo}: {oggetto[campo]!r}"
    for campo, tipo in (facoltativi or {}).items():
        if campo in oggetto:
            assert isinstance(oggetto[campo], tipo), f"{dove}.{campo}: {oggetto[campo]!r}"


def _giorno_striscia(voce, dove):
    _forma(voce, {"data": TESTO, "stato": TESTO}, dove=dove)
    assert voce["stato"] in ("verde", "rosso", "grigio")


def _contatori_bonus(bonus, dove):
    for periodo in ("giorno", "settimana"):
        _forma(bonus[periodo], {"usati": INTERO, "tetto": INTERO, "residui": INTERO},
               dove=f"{dove}.{periodo}")


def _proposta(p, dove):
    _forma(p, {"id": INTERO, "regola_id": INTERO, "stato": TESTO,
               "parametri_proposti": OGGETTO, "confronto": TESTO, "direzione": TESTO},
           {"usata": BOOL, "ts_server": TESTO, "motivazione": (TESTO, NULLA),
            "risposta": (OGGETTO, NULLA)}, dove)


def _dichiarazione(d, dove):
    _forma(d, {"id": INTERO, "regola_id": INTERO, "esito": TESTO, "stato": TESTO},
           {"giorno": TESTO, "nota": (TESTO, NULLA), "ts_server": TESTO,
            "verdetto": (OGGETTO, NULLA)}, dove)


def _notifica(n, dove):
    _forma(n, {"id": INTERO, "tipo": TESTO, "messaggio": TESTO, "ts_server": TESTO},
           {"payload": OGGETTO}, dove)


def _regola(r, dove):
    _forma(r, {"id": INTERO, "tipo": TESTO, "parametri": OGGETTO},
           {"attiva": BOOL, "creata_ts": TESTO, "ultima_modifica_ts": TESTO,
            "allentabile_dal": (TESTO, NULLA), "semaforo": LISTA, "nome": (TESTO, NULLA)}, dove)
    for i, voce in enumerate(r.get("semaforo", [])):
        _giorno_striscia(voce, f"{dove}.semaforo[{i}]")


@pytest.fixture(params=["migrato_dalla_v24", "installazione_nuova"])
def cliente(request, tmp_path, monkeypatch):
    from app import clock

    path = str(tmp_path / "pactum.db")
    if request.param == "migrato_dalla_v24":
        dati_v24.crea_db_v24(path)
        inizio = dati_v24.ORA_V24
    else:
        from conftest import ORA_INIZIALE as inizio
    o = Orologio(inizio)
    o.avanza(days=1)  # un giorno nuovo: il segno di oggi non e' ancora partito
    monkeypatch.setattr(clock, "now", lambda: o.corrente)
    monkeypatch.setenv("PACTUM_DB", path)
    monkeypatch.setenv("PACTUM_TOKEN_FIGLIO", TOKEN_FIGLIO)
    monkeypatch.setenv("PACTUM_TOKEN_GENITORE", TOKEN_GENITORE)
    from app.main import create_app

    with TestClient(create_app()) as c:
        yield c, o


def test_le_app_07_continuano_a_funzionare(cliente):
    c, orologio = cliente
    oggi = orologio.corrente.date().isoformat()

    # --- l'app del figlio 0.7 ---
    r = c.post("/api/battito", json={"ts_device": 1790000000000, "versione_app": "0.7.0",
                                     "elapsed_realtime": 5000, "batteria": 80}, headers=FIGLIO)
    assert r.status_code == 200 and r.json() == {"ricevuto": True}

    limite = c.post("/api/regole", json={"tipo": "limite_tempo", "parametri": {
        "app_o_categoria": "com.instagram.android", "minuti_al_giorno": 60}}, headers=FIGLIO)
    assert limite.status_code == 201, limite.text
    _regola(limite.json(), "regola creata")
    limite = limite.json()
    vita = c.post("/api/regole", json={"tipo": "vita_reale", "parametri": {
        "descrizione": "Leggere", "arbitro_nome": "Papa", "frequenza": "ogni giorno"}}, headers=FIGLIO)
    assert vita.status_code == 201, vita.text
    vita = vita.json()

    r = c.post("/api/eventi", json={"eventi": [
        {"id": "c07-uso", "tipo": "uso_giornaliero", "ts_device": 1790000000000, "dettagli": {
            "giorno": oggi, "uso_minuti": {"com.instagram.android": 70}, "totale_minuti": 70,
            "nomi": {"com.instagram.android": "Instagram"}, "uso_categorie": {"categoria:social": 70}}},
        {"id": "c07-siti", "tipo": "siti_giornalieri", "dettagli": {
            "giorno": oggi, "domini": {"instagram.com": 12}, "totale_domini": 1, "dns_cifrato": False}},
        {"id": "c07-sfor", "tipo": "sforamento", "dettagli": {
            "regola_id": limite["id"], "giorno": oggi, "limite_efficace": 60, "minuti_oltre": 10}},
        {"id": "c07-mano", "tipo": "manomissione", "dettagli": {"sotto_tipo": "cambio_ora"}},
        {"id": "c07-riavvio", "tipo": "riavvio", "dettagli": {}},
    ]}, headers=FIGLIO)
    assert r.status_code == 200 and r.json() == {"ricevuti": 5, "nuovi": 5, "duplicati": 0}

    r = c.post("/api/bonus", json={"minuti": 15, "regola_id": limite["id"], "motivo": "film"},
               headers=FIGLIO)
    assert r.status_code == 200, r.text
    _forma(r.json(), {"minuti": INTERO, "residuo_giorno": INTERO, "residuo_settimana": INTERO})

    # allentare dentro il blocco dei 4 giorni: il 409 che l'app sa leggere
    r = c.patch(f"/api/regole/{limite['id']}", json={"parametri": {
        "app_o_categoria": "com.instagram.android", "minuti_al_giorno": 90}}, headers=FIGLIO)
    assert r.status_code == 409
    _forma(r.json()["detail"], {"errore": TESTO, "secondi_rimanenti": INTERO, "sblocco_ts": TESTO})

    r = c.post("/api/dichiarazioni", json={"regola_id": vita["id"], "esito": "successo"},
               headers=FIGLIO)
    assert r.status_code == 200, r.text
    _dichiarazione(r.json(), "dichiarazione")
    dichiarazione = r.json()

    patto = c.get("/api/patto", headers=FIGLIO)
    assert patto.status_code == 200
    patto = patto.json()
    _forma(patto, {"regole": LISTA, "bonus": OGGETTO, "bonus_oggi_per_regola": OGGETTO,
                   "proposte_pendenti": LISTA, "dichiarazioni_in_attesa": LISTA,
                   "siti_recenti": LISTA, "striscia": LISTA, "riepilogo": OGGETTO, "fuso": TESTO})
    for i, regola in enumerate(patto["regole"]):
        _regola(regola, f"patto.regole[{i}]")
    _contatori_bonus(patto["bonus"], "patto.bonus")
    assert patto["bonus_oggi_per_regola"][str(limite["id"])] == 15
    for voce in patto["striscia"]:
        _giorno_striscia(voce, "patto.striscia")
    _forma(patto["riepilogo"], {"giorni_fuori_regola": INTERO, "interruzioni": INTERO})
    for giorno in patto["siti_recenti"]:
        _forma(giorno, {"giorno": TESTO}, {"totale_domini": (INTERO, NULLA), "dns_cifrato": BOOL,
                                           "aggiornato_ts": (TESTO, NULLA), "domini": LISTA})
        for dominio in giorno["domini"]:
            _forma(dominio, {"dominio": TESTO, "visite": INTERO})
    assert [d["id"] for d in patto["dichiarazioni_in_attesa"]][0] == dichiarazione["id"]

    # --- l'app del genitore 0.7 ---
    finestra = c.get("/api/finestra", headers=GENITORE)
    assert finestra.status_code == 200
    finestra = finestra.json()
    _forma(finestra, {"bonus": OGGETTO, "stato_silenzio": OGGETTO},
           {"regole": LISTA, "sforamenti_recenti": LISTA, "manomissioni_recenti": LISTA,
            "storico_modifiche": LISTA, "bonus_giornalieri": LISTA, "uso_recente": LISTA,
            "medie": (OGGETTO, NULLA), "siti_recenti": (LISTA, NULLA), "striscia": LISTA,
            "riepilogo": (OGGETTO, NULLA), "segno_oggi": BOOL})
    _contatori_bonus(finestra["bonus"], "finestra.bonus")
    assert finestra["bonus"]["giorno"]["usati"] == 15
    _forma(finestra["stato_silenzio"], {"silente": BOOL}, {"ultimo_battito": (TESTO, NULLA)})
    assert finestra["stato_silenzio"]["silente"] is False
    for i, regola in enumerate(finestra["regole"]):
        _regola(regola, f"finestra.regole[{i}]")
    for chiave in ("sforamenti_recenti", "manomissioni_recenti"):
        for evento in finestra[chiave]:
            _forma(evento, {"id": TESTO, "tipo": TESTO, "ts_server": TESTO},
                   {"dettagli": OGGETTO, "ts_device": (INTERO, NULLA)}, chiave)
    assert finestra["sforamenti_recenti"][0]["id"] == "c07-sfor"
    for voce in finestra["storico_modifiche"]:
        _forma(voce, {"id": INTERO, "regola_id": INTERO, "azione": TESTO, "ts_server": TESTO},
               {"direzione": (TESTO, NULLA), "prima": (OGGETTO, NULLA), "dopo": (OGGETTO, NULLA),
                "concordata": BOOL})
    for voce in finestra["bonus_giornalieri"]:
        _forma(voce, {"giorno": TESTO, "minuti": INTERO})
    oggi_uso = finestra["uso_recente"][-1]
    _forma(oggi_uso, {"giorno": TESTO}, {"totale_minuti": (INTERO, NULLA), "app": LISTA,
                                         "categorie": LISTA})
    assert oggi_uso["app"][0] == {"chiave": "com.instagram.android", "nome": "Instagram",
                                  "minuti": 70, "limite": 60, "regola_id": limite["id"], "bonus": 15}
    assert finestra["siti_recenti"] == patto["siti_recenti"]  # tavola rotonda
    assert finestra["striscia"] == patto["striscia"]

    proposta = c.post("/api/proposte", json={"regola_id": limite["id"], "parametri_proposti": {
        "app_o_categoria": "com.instagram.android", "minuti_al_giorno": 45},
        "motivazione": "un po' meno"}, headers=GENITORE)
    assert proposta.status_code == 200, proposta.text
    _proposta(proposta.json(), "proposta creata")
    proposta = proposta.json()

    r = c.post(f"/api/dichiarazioni/{dichiarazione['id']}/verdetto",
               json={"verdetto": "conferma_per_conto", "nota": "sentito al telefono"}, headers=GENITORE)
    assert r.status_code == 200, r.text
    _dichiarazione(r.json(), "verdetto")
    assert r.json()["verdetto"]["registro"] == "confermato dal genitore per conto di Papa"

    # segno: la 0.7 non manda corpo (o un corpo vuoto)
    r = c.post("/api/segno", headers=GENITORE)
    assert r.status_code == 200 and r.json()["mandato"] is True
    r = c.post("/api/segno", json={}, headers=GENITORE)
    assert r.status_code == 409 and r.json()["detail"] == {"errore": "segno_gia_mandato"}

    # --- di nuovo il figlio: vede la proposta, il verdetto e il segno ---
    notifiche = c.get("/api/notifiche", headers=FIGLIO).json()["notifiche"]
    for i, n in enumerate(notifiche):
        _notifica(n, f"notifiche figlio[{i}]")
    tipi = [n["tipo"] for n in notifiche]
    assert {"nuova_proposta", "verdetto", "segno"} <= set(tipi)
    r = c.post(f"/api/notifiche/{notifiche[0]['id']}/letta", headers=FIGLIO)
    assert r.status_code == 200 and r.json() == {"id": notifiche[0]["id"], "letta": True}

    proposte = c.get("/api/proposte", headers=FIGLIO).json()["proposte"]
    for i, p in enumerate(proposte):
        _proposta(p, f"proposte[{i}]")
    r = c.post(f"/api/proposte/{proposta['id']}/risposta", json={"esito": "accetta"}, headers=FIGLIO)
    assert r.status_code == 200, r.text
    _proposta(r.json()["proposta"], "risposta.proposta")
    _regola(r.json()["regola"], "risposta.regola")
    assert r.json()["regola"]["parametri"]["minuti_al_giorno"] == 45

    for headers in (FIGLIO, GENITORE):
        for i, d in enumerate(c.get("/api/dichiarazioni", headers=headers).json()["dichiarazioni"]):
            _dichiarazione(d, f"dichiarazioni[{i}]")
        for i, p in enumerate(c.get("/api/proposte", headers=headers).json()["proposte"]):
            _proposta(p, f"proposte[{i}]")
        assert c.get("/api/regole", headers=headers).status_code == 200

    notifiche = c.get("/api/notifiche", headers=GENITORE).json()["notifiche"]
    for i, n in enumerate(notifiche):
        _notifica(n, f"notifiche genitore[{i}]")
    assert {"sforamento", "manomissione", "bonus", "dichiarazione", "proposta_risposta"} <= {
        n["tipo"] for n in notifiche
    }
    assert c.post(f"/api/notifiche/{notifiche[-1]['id']}/letta", headers=GENITORE).status_code == 200

    # --- l'auto-aggiornamento delle due app ---
    versioni = c.get("/api/versione").json()
    for ruolo in ("figlio", "genitore"):
        _forma(versioni[ruolo], {"versione_code": INTERO}, {"versione_nome": TESTO, "url": TESTO})
