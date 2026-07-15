"""Dichiarazioni vita reale (tappa 5): il figlio dichiara successo/fallimento,
fallimento e' creduto sulla parola (registrata), successo resta in attesa del
verdetto del genitore/arbitro (conferma / conferma per conto di / ribalta). Max una
per regola per giorno. Il registro conserva chi ha garantito cosa."""

from datetime import datetime, timezone

from conftest import FIGLIO, GENITORE, crea_regola


def _vita(descrizione="Cammino un'ora al giorno", arbitro="Mamma"):
    return {"descrizione": descrizione, "arbitro_nome": arbitro, "frequenza": "ogni giorno"}


def _regola_vita(client, arbitro="Mamma"):
    return crea_regola(client, tipo="vita_reale", parametri=_vita(arbitro=arbitro))


def _dichiara(client, regola_id, esito, nota=None, giorno=None):
    corpo = {"regola_id": regola_id, "esito": esito}
    if nota is not None:
        corpo["nota"] = nota
    if giorno is not None:
        corpo["giorno"] = giorno
    return client.post("/api/dichiarazioni", json=corpo, headers=FIGLIO)


def _verdetto(client, dichiarazione_id, verdetto, nota=None):
    corpo = {"verdetto": verdetto}
    if nota is not None:
        corpo["nota"] = nota
    return client.post(f"/api/dichiarazioni/{dichiarazione_id}/verdetto", json=corpo, headers=GENITORE)


# --- creazione ---

def test_fallimento_registrata(client):
    regola = _regola_vita(client)
    risposta = _dichiara(client, regola["id"], "fallimento", nota="non ce l'ho fatta")
    assert risposta.status_code == 200
    dati = risposta.json()
    assert dati["stato"] == "registrata"
    assert dati["esito"] == "fallimento"
    assert dati["nota"] == "non ce l'ho fatta"
    assert dati["verdetto"] is None
    assert dati["giorno"] == "2026-07-14"  # oggi nel fuso del patto


def test_successo_in_attesa(client):
    regola = _regola_vita(client)
    dati = _dichiara(client, regola["id"], "successo").json()
    assert dati["stato"] == "in_attesa"
    assert dati["verdetto"] is None


def test_giorno_esplicito(client):
    regola = _regola_vita(client)
    dati = _dichiara(client, regola["id"], "successo", giorno="2026-07-13").json()
    assert dati["giorno"] == "2026-07-13"


def test_giorno_di_default_e_locale(client, orologio):
    # 23:30 UTC del 14/07 = 01:30 locali del 15/07: il default e' il giorno LOCALE
    orologio.vai_a(datetime(2026, 7, 14, 23, 30, 0, tzinfo=timezone.utc))
    regola = _regola_vita(client)
    dati = _dichiara(client, regola["id"], "successo").json()
    assert dati["giorno"] == "2026-07-15"


def test_giorno_non_valido_422(client):
    regola = _regola_vita(client)
    assert _dichiara(client, regola["id"], "successo", giorno="2026-13-40").status_code == 422
    assert _dichiara(client, regola["id"], "successo", giorno="oggi").status_code == 422


# --- solo regole vita_reale attive ---

def test_dichiarazione_solo_su_vita_reale(client):
    limite = crea_regola(client)  # limite_tempo
    risposta = _dichiara(client, limite["id"], "successo")
    assert risposta.status_code == 409
    assert risposta.json()["detail"]["errore"] == "regola_non_valida"


def test_dichiarazione_su_regola_inesistente_409(client):
    crea_regola(client)
    risposta = _dichiara(client, 999, "successo")
    assert risposta.status_code == 409
    assert risposta.json()["detail"]["errore"] == "regola_non_valida"


def test_dichiarazione_su_vita_reale_eliminata_409(client, orologio):
    crea_regola(client)  # la regola che resta
    vita = _regola_vita(client)
    orologio.avanza(days=4)
    client.delete(f"/api/regole/{vita['id']}", headers=FIGLIO)
    risposta = _dichiara(client, vita["id"], "successo")
    assert risposta.status_code == 409
    assert risposta.json()["detail"]["errore"] == "regola_non_valida"


# --- una per regola per giorno ---

def test_una_dichiarazione_per_giorno(client):
    regola = _regola_vita(client)
    assert _dichiara(client, regola["id"], "successo").status_code == 200
    di_nuovo = _dichiara(client, regola["id"], "fallimento")
    assert di_nuovo.status_code == 409
    assert di_nuovo.json()["detail"]["errore"] == "gia_dichiarato"


def test_stessa_regola_giorni_diversi_ok(client):
    regola = _regola_vita(client)
    assert _dichiara(client, regola["id"], "successo", giorno="2026-07-13").status_code == 200
    assert _dichiara(client, regola["id"], "successo", giorno="2026-07-14").status_code == 200


def test_regole_diverse_stesso_giorno_ok(client):
    a = _regola_vita(client, arbitro="Mamma")
    b = _regola_vita(client, arbitro="Papa")
    assert _dichiara(client, a["id"], "successo").status_code == 200
    assert _dichiara(client, b["id"], "successo").status_code == 200


# --- GET leggibile da entrambi ---

def test_get_dichiarazioni_entrambi(client):
    regola = _regola_vita(client)
    _dichiara(client, regola["id"], "successo")
    da_figlio = client.get("/api/dichiarazioni", headers=FIGLIO).json()
    da_genitore = client.get("/api/dichiarazioni", headers=GENITORE).json()
    assert da_figlio == da_genitore
    assert len(da_figlio["dichiarazioni"]) == 1


# --- verdetto: transizioni di stato e frase del registro ---

def test_verdetto_conferma(client):
    regola = _regola_vita(client)
    dic = _dichiara(client, regola["id"], "successo").json()
    risposta = _verdetto(client, dic["id"], "conferma")
    assert risposta.status_code == 200
    dati = risposta.json()
    assert dati["stato"] == "confermata"
    assert dati["verdetto"]["verdetto"] == "conferma"
    assert dati["verdetto"]["registro"] == "confermato da Mamma"


def test_verdetto_conferma_per_conto_frase_registro(client):
    regola = _regola_vita(client, arbitro="la nonna")
    dic = _dichiara(client, regola["id"], "successo").json()
    dati = _verdetto(client, dic["id"], "conferma_per_conto", nota="sentita al telefono").json()
    assert dati["stato"] == "confermata_per_conto"
    assert dati["verdetto"]["registro"] == "confermato dal genitore per conto di la nonna"
    assert dati["verdetto"]["nota"] == "sentita al telefono"


def test_verdetto_ribalta(client):
    regola = _regola_vita(client)
    dic = _dichiara(client, regola["id"], "successo").json()
    dati = _verdetto(client, dic["id"], "ribalta").json()
    assert dati["stato"] == "ribaltata"
    assert dati["verdetto"]["verdetto"] == "ribalta"


def test_verdetto_solo_su_in_attesa(client):
    regola = _regola_vita(client)
    # un fallimento e' 'registrata', non 'in_attesa': niente verdetto
    dic = _dichiara(client, regola["id"], "fallimento").json()
    risposta = _verdetto(client, dic["id"], "conferma")
    assert risposta.status_code == 409
    assert risposta.json()["detail"]["errore"] == "dichiarazione_non_in_attesa"


def test_verdetto_non_ripetibile(client):
    regola = _regola_vita(client)
    dic = _dichiara(client, regola["id"], "successo").json()
    assert _verdetto(client, dic["id"], "conferma").status_code == 200
    assert _verdetto(client, dic["id"], "ribalta").status_code == 409


def test_verdetto_dichiarazione_inesistente_404(client):
    assert _verdetto(client, 999, "conferma").status_code == 404


def test_verdetto_frase_resta_anche_se_la_regola_cambia_dopo(client, orologio):
    """La frase del registro e' congelata al verdetto: se poi la regola cambia
    arbitro, la dichiarazione gia' giudicata continua a dire chi garanti' allora."""
    regola = _regola_vita(client, arbitro="Mamma")
    dic = _dichiara(client, regola["id"], "successo").json()
    _verdetto(client, dic["id"], "conferma_per_conto")
    orologio.avanza(days=4)
    client.patch(
        f"/api/regole/{regola['id']}",
        json={"parametri": _vita(arbitro="Papa")},
        headers=FIGLIO,
    )
    giudicata = client.get("/api/dichiarazioni", headers=GENITORE).json()["dichiarazioni"][0]
    assert giudicata["verdetto"]["registro"] == "confermato dal genitore per conto di Mamma"


# --- notifiche ---

def test_dichiarazione_notifica_il_genitore(client):
    regola = _regola_vita(client)
    dic = _dichiara(client, regola["id"], "successo").json()
    genitore = client.get("/api/notifiche", headers=GENITORE).json()["notifiche"]
    di_dic = [n for n in genitore if n["tipo"] == "dichiarazione"]
    assert len(di_dic) == 1
    assert di_dic[0]["payload"]["dichiarazione_id"] == dic["id"]
    assert di_dic[0]["payload"]["esito"] == "successo"


def test_verdetto_notifica_il_figlio(client):
    regola = _regola_vita(client)
    dic = _dichiara(client, regola["id"], "successo").json()
    _verdetto(client, dic["id"], "conferma")
    figlio = client.get("/api/notifiche", headers=FIGLIO).json()["notifiche"]
    di_verdetto = [n for n in figlio if n["tipo"] == "verdetto"]
    assert len(di_verdetto) == 1
    assert di_verdetto[0]["payload"]["verdetto"] == "conferma"
    assert di_verdetto[0]["payload"]["dichiarazione_id"] == dic["id"]
