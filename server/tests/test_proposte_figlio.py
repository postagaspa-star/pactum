"""(v3.4) Le proposte del figlio: propone anche il figlio (da qualsiasi suo
dispositivo) e risponde il genitore; chi ha proposto puo' ritirare.

- Dal dispositivo: sulle regole del suo figlio (di qualsiasi suo dispositivo o di
  vita reale), con gli stessi controlli del genitore (409 regola_non_valida, 409
  dispositivo_revocato, 422) e una sola pendente per regola chiunque l'abbia fatta.
  Notifica nuova_proposta al genitore, col nome del figlio.
- Risposta del genitore: accetta = vale subito anche se allenta (il blocco dei 4
  giorni non c'entra), concordata nello storico, niente modifica_regola doppia al
  genitore; la risposta arriva a TUTTI i dispositivi del figlio, letta per
  dispositivo. Alla propria proposta non si risponde: 403.
- Ritiro: solo chi ha proposto, solo le pendenti, atomico contro l'accetta.
- Dove si vedono: patto (proposte_pendenti / proposte_inviate), finestra, famiglia.
- Migrazione dalla v3.3: autore 'genitore' per tutte, 'ritirata' ammesso, e prima la
  copia <db>.prima-v3.4-<data>."""

import logging
import sqlite3
import threading
import uuid
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

import dati_v24
from aiuti_v3 import dispositivo_abbinato, eventi, nuovo_figlio, regola
from conftest import FIGLIO, GENITORE

MENO = "−"  # segno meno tipografico U+2212, come nel contratto
VITA = {"descrizione": "Un'ora di cammino", "arbitro_nome": "Mamma", "frequenza": "ogni giorno"}
MINECRAFT = {"app_o_categoria": "exe:minecraft.exe", "minuti_al_giorno": 60}
TIKTOK = "com.zhiliaoapp.musically"
INSTAGRAM = "com.instagram.android"
# (v3.6) Il genitore del token d'ambiente, come lo citano proposte e notifiche.
GENITORE_1 = {"id": 1, "nome": "Genitore"}


def _limite(minuti, app="TikTok"):
    return {"app_o_categoria": app, "minuti_al_giorno": minuti}


def _minecraft(minuti):
    return {**MINECRAFT, "minuti_al_giorno": minuti}


@pytest.fixture
def famiglia(client):
    """Andrea (figlio 1) col telefono del token d'ambiente (FIGLIO) e un computer;
    Marta col suo telefono."""
    assert client.patch("/api/figli/1", json={"nome": "Andrea"}, headers=GENITORE).status_code == 200
    marta = nuovo_figlio(client, "Marta")
    pc_andrea, pc_andrea_id = dispositivo_abbinato(client, 1, "Computer", "computer")
    tel_marta, tel_marta_id = dispositivo_abbinato(client, marta["id"], "Telefono di Marta", "telefono")
    return SimpleNamespace(
        marta=marta["id"],
        pc_andrea=pc_andrea, pc_andrea_id=pc_andrea_id,
        tel_marta=tel_marta, tel_marta_id=tel_marta_id,
    )


def _proponi(client, headers, regola_id, parametri, **extra):
    return client.post(
        "/api/proposte", json={"regola_id": regola_id, "parametri_proposti": parametri, **extra},
        headers=headers,
    )


def _proposta(client, headers, regola_id, parametri, **extra) -> dict:
    risposta = _proponi(client, headers, regola_id, parametri, **extra)
    assert risposta.status_code == 200, risposta.text
    return risposta.json()


def _rispondi(client, headers, proposta_id, esito, **extra):
    return client.post(
        f"/api/proposte/{proposta_id}/risposta", json={"esito": esito, **extra}, headers=headers
    )


def _ritira(client, headers, proposta_id):
    return client.post(f"/api/proposte/{proposta_id}/ritira", headers=headers)


def _notifiche(client, headers, tipo=None) -> list:
    risposta = client.get("/api/notifiche", headers=headers)
    assert risposta.status_code == 200, risposta.text
    return [n for n in risposta.json()["notifiche"] if tipo is None or n["tipo"] == tipo]


def _ids_notifiche(client, headers) -> set:
    return {n["id"] for n in _notifiche(client, headers)}


def _proposte(client, headers=GENITORE, **parametri) -> list:
    """Le proposte di tutti e due gli autori: senza ?autori=tutti arrivano solo quelle
    del genitore, come per le app 0.8/0.9."""
    risposta = client.get("/api/proposte", headers=headers, params={"autori": "tutti", **parametri})
    assert risposta.status_code == 200, risposta.text
    return risposta.json()["proposte"]


def _stato(client, proposta_id) -> str:
    (trovata,) = [p for p in _proposte(client) if p["id"] == proposta_id]
    return trovata["stato"]


def _finestra(client, figlio_id=1) -> dict:
    risposta = client.get(f"/api/finestra?figlio_id={figlio_id}", headers=GENITORE)
    assert risposta.status_code == 200, risposta.text
    return risposta.json()


def _patto(client, headers) -> dict:
    risposta = client.get("/api/patto", headers=headers)
    assert risposta.status_code == 200, risposta.text
    return risposta.json()


def _regola_in_finestra(client, regola_id) -> dict:
    (trovata,) = [r for r in _finestra(client)["regole"] if r["id"] == regola_id]
    return trovata


def _storico(client, regola_id) -> list:
    return [s for s in _finestra(client)["storico_modifiche"] if s["regola_id"] == regola_id]


def _revoca(client, dispositivo_id):
    assert client.delete(f"/api/dispositivi/{dispositivo_id}", headers=GENITORE).status_code == 200


def _foto(client, headers, nomi, giorno="2026-07-14", totale=30):
    """Una fotografia uso_giornaliero con le etichette delle app, come la manda l'app:
    da li' vengono i nomi della finestra e del confronto."""
    eventi(client, headers, {
        "id": f"uso-{giorno}-{uuid.uuid4().hex[:8]}",
        "tipo": "uso_giornaliero",
        "dettagli": {"giorno": giorno, "uso_minuti": {chiave: 1 for chiave in nomi},
                     "totale_minuti": totale, "nomi": nomi},
    })


# --- il figlio propone ---

def test_il_figlio_propone_sulla_sua_regola(client, famiglia):
    tiktok = regola(client, FIGLIO, parametri=_limite(60))
    r = _proponi(client, FIGLIO, tiktok["id"], _limite(90), motivazione="nel fine settimana")
    assert r.status_code == 200, r.text
    p = r.json()
    assert p["autore"] == "figlio"
    assert p["confronto"] == "+30 min al giorno rispetto ad ora" and p["direzione"] == "allenta"
    assert (p["stato"], p["usata"], p["risposta"]) == ("pendente", False, None)
    assert p["motivazione"] == "nel fine settimana"
    assert p["parametri_proposti"] == _limite(90)
    # finche' il genitore non decide, la regola resta com'e'
    assert _regola_in_finestra(client, tiktok["id"])["parametri"] == _limite(60)


def test_dal_computer_sulla_regola_del_telefono(client, famiglia):
    del_telefono = regola(client, FIGLIO)
    p = _proposta(client, famiglia.pc_andrea, del_telefono["id"], {"azione": "elimina"})
    assert (p["autore"], p["direzione"]) == ("figlio", "elimina")
    assert p["confronto"] == "propone di eliminare la regola"


def test_sulla_vita_reale(client, famiglia):
    vita = regola(client, famiglia.pc_andrea, tipo="vita_reale", parametri=VITA)
    p = _proposta(client, FIGLIO, vita["id"], {**VITA, "frequenza": "tre volte a settimana"})
    assert "frequenza: ogni giorno -> tre volte a settimana" in p["confronto"]
    (avviso,) = _notifiche(client, GENITORE, "nuova_proposta")
    assert (avviso["figlio_id"], avviso["dispositivo_id"]) == (1, None)  # la vita reale e' del figlio


def test_la_proposta_del_figlio_avvisa_il_genitore(client, famiglia):
    del_pc = regola(client, famiglia.pc_andrea, parametri=MINECRAFT)
    p = _proposta(client, FIGLIO, del_pc["id"], _minecraft(90))
    (avviso,) = _notifiche(client, GENITORE, "nuova_proposta")
    assert avviso["messaggio"] == "Andrea propone: +30 min al giorno rispetto ad ora"
    assert avviso["payload"] == {
        "proposta_id": p["id"], "regola_id": del_pc["id"],
        "confronto": "+30 min al giorno rispetto ad ora", "direzione": "allenta", "autore": "figlio",
    }
    assert (avviso["figlio_id"], avviso["dispositivo_id"]) == (1, famiglia.pc_andrea_id)
    # ai dispositivi del figlio non arriva: la proposta e' la sua
    for headers in (FIGLIO, famiglia.pc_andrea):
        assert _notifiche(client, headers, "nuova_proposta") == []


def test_la_proposta_del_genitore_dice_l_autore(client, famiglia):
    tiktok = regola(client, FIGLIO)
    p = _proposta(client, GENITORE, tiktok["id"], _limite(30))
    assert p["autore"] == "genitore"
    (avviso,) = _notifiche(client, FIGLIO, "nuova_proposta")
    assert avviso["messaggio"] == f"Nuova proposta di Genitore: {MENO}30 min al giorno rispetto ad ora"
    assert avviso["payload"]["autore"] == "genitore"
    assert _notifiche(client, GENITORE, "nuova_proposta") == []


def test_il_figlio_id_nel_corpo_si_ignora(client, famiglia):
    tiktok = regola(client, FIGLIO)
    # per il genitore un figlio che non c'e' e' 404; dal dispositivo non conta
    assert _proponi(client, GENITORE, tiktok["id"], _limite(90), figlio_id=999).status_code == 404
    assert _proponi(client, FIGLIO, tiktok["id"], _limite(90), figlio_id=999).status_code == 200
    # e non apre le regole di un altro figlio
    di_marta = regola(client, famiglia.tel_marta)
    r = _proponi(client, FIGLIO, di_marta["id"], _limite(90), figlio_id=famiglia.marta)
    assert r.status_code == 409 and r.json()["detail"] == {"errore": "regola_non_valida"}


# --- i nomi nel confronto ---

def test_i_bersagli_si_scrivono_coi_nomi(client, famiglia):
    """Quando cambia il bersaglio, il confronto scrive le app come le leggono le
    persone: l'etichetta delle fotografie del figlio, la stessa del `nome` della
    finestra. Per tutti e due gli autori, e nei messaggi."""
    _foto(client, FIGLIO, {TIKTOK: "TikTok", INSTAGRAM: "Instagram"})
    tiktok = regola(client, FIGLIO, parametri=_limite(60, app=TIKTOK))
    social = regola(client, FIGLIO, parametri=_limite(120, app="categoria:social"))
    del_figlio = _proposta(client, FIGLIO, tiktok["id"], _limite(60, app=INSTAGRAM))
    assert del_figlio["confronto"] == "da TikTok (60 min) a Instagram (60 min) al giorno"
    (al_genitore,) = _notifiche(client, GENITORE, "nuova_proposta")
    assert al_genitore["messaggio"] == "Andrea propone: da TikTok (60 min) a Instagram (60 min) al giorno"
    assert al_genitore["payload"]["confronto"] == del_figlio["confronto"]
    del_genitore = _proposta(client, GENITORE, social["id"], _limite(60, app=TIKTOK))
    assert del_genitore["confronto"] == "da Social (120 min) a TikTok (60 min) al giorno"
    (al_figlio,) = _notifiche(client, FIGLIO, "nuova_proposta")
    assert al_figlio["messaggio"] == "Nuova proposta di Genitore: da Social (120 min) a TikTok (60 min) al giorno"
    assert _regola_in_finestra(client, tiktok["id"])["nome"] == "TikTok"  # la stessa etichetta


def test_a_bersaglio_uguale_il_testo_non_cambia(client, famiglia):
    _foto(client, FIGLIO, {TIKTOK: "TikTok"})
    tiktok = regola(client, FIGLIO, parametri=_limite(60, app=TIKTOK))
    p = _proposta(client, FIGLIO, tiktok["id"], _limite(90, app=TIKTOK))
    assert p["confronto"] == "+30 min al giorno rispetto ad ora"


@pytest.mark.parametrize("prima,dopo,atteso", [
    ("categoria:social", "categoria:giochi", "da Social (60 min) a Giochi (60 min) al giorno"),
    ("categoria:video", "categoria:musica", "da Video (60 min) a Musica (60 min) al giorno"),
    ("categoria:altro", "totale", "da Altre app (60 min) a tutto il telefono (60 min) al giorno"),
])
def test_le_categorie_come_nelle_app(client, famiglia, prima, dopo, atteso):
    da_cambiare = regola(client, FIGLIO, parametri=_limite(60, app=prima))
    assert _proposta(client, FIGLIO, da_cambiare["id"], _limite(60, app=dopo))["confronto"] == atteso


def test_sul_computer_programmi_e_siti(client, famiglia):
    _foto(client, famiglia.pc_andrea, {"exe:minecraft.exe": "Minecraft"})
    minecraft = regola(client, famiglia.pc_andrea, parametri=MINECRAFT)
    p = _proposta(client, famiglia.pc_andrea, minecraft["id"], _limite(60, app="sito:youtube.com"))
    assert p["confronto"] == "da Minecraft (60 min) a youtube.com (60 min) al giorno"
    # un programma senza etichetta resta com'e'; il totale come dalla v3.3
    roblox = regola(client, famiglia.pc_andrea, parametri=_limite(60, app="exe:roblox.exe"))
    q = _proposta(client, GENITORE, roblox["id"], _limite(180, app="totale"))
    assert q["confronto"] == "da exe:roblox.exe (60 min) a tutto il computer (180 min) al giorno"


def test_i_nomi_si_aggiornano_finche_e_pendente_poi_si_fermano(client, famiglia, orologio):
    tiktok = regola(client, FIGLIO, parametri=_limite(60, app=TIKTOK))
    p = _proposta(client, FIGLIO, tiktok["id"], _limite(60, app=INSTAGRAM))
    assert p["confronto"] == f"da {TIKTOK} (60 min) a {INSTAGRAM} (60 min) al giorno"  # nessuna etichetta
    _foto(client, FIGLIO, {TIKTOK: "TikTok", INSTAGRAM: "Instagram"})
    coi_nomi = "da TikTok (60 min) a Instagram (60 min) al giorno"
    assert [x["confronto"] for x in _proposte(client)] == [coi_nomi]
    assert [x["confronto"] for x in _patto(client, FIGLIO)["proposte_inviate"]] == [coi_nomi]
    assert [x["confronto"] for x in _finestra(client)["proposte_pendenti"]] == [coi_nomi]
    assert _rispondi(client, GENITORE, p["id"], "rifiuta").status_code == 200
    (avviso,) = _notifiche(client, FIGLIO, "proposta_risposta")
    assert avviso["messaggio"] == f"Genitore ha rifiutato la tua proposta: {coi_nomi}"
    # chiusa, resta il testo del momento della risposta anche se l'etichetta cambia
    orologio.avanza(minutes=5)
    _foto(client, FIGLIO, {TIKTOK: "TikTok Lite", INSTAGRAM: "Instagram"}, totale=60)
    assert [x["confronto"] for x in _proposte(client)] == [coi_nomi]
    # e il ritiro ferma il testo coi nomi di quel momento
    q = _proposta(client, FIGLIO, tiktok["id"], _limite(60, app=INSTAGRAM))
    assert q["confronto"] == "da TikTok Lite (60 min) a Instagram (60 min) al giorno"
    assert _ritira(client, FIGLIO, q["id"]).json()["confronto"] == q["confronto"]


# --- i controlli della creazione ---

def test_regola_di_un_altro_figlio_409(client, famiglia):
    di_marta = regola(client, famiglia.tel_marta)
    for headers in (FIGLIO, famiglia.pc_andrea):
        r = _proponi(client, headers, di_marta["id"], _limite(90))
        assert r.status_code == 409 and r.json()["detail"] == {"errore": "regola_non_valida"}
    assert _proposte(client, GENITORE, figlio_id=famiglia.marta) == []
    assert _notifiche(client, GENITORE, "nuova_proposta") == []


def test_regola_inesistente_o_eliminata_409(client, famiglia, orologio):
    regola(client, FIGLIO)  # la regola che resta
    youtube = regola(client, FIGLIO, parametri=_limite(90, app="YouTube"))
    orologio.avanza(days=5)
    assert client.delete(f"/api/regole/{youtube['id']}", headers=FIGLIO).status_code == 200
    for regola_id in (999, youtube["id"]):
        r = _proponi(client, FIGLIO, regola_id, _limite(120, app="YouTube"))
        assert r.status_code == 409 and r.json()["detail"] == {"errore": "regola_non_valida"}


def test_regola_di_un_dispositivo_revocato_409(client, famiglia):
    regola(client, FIGLIO)
    del_pc = regola(client, famiglia.pc_andrea, parametri=MINECRAFT)
    _revoca(client, famiglia.pc_andrea_id)
    for parametri in (_minecraft(90), {"azione": "elimina"}):
        r = _proponi(client, FIGLIO, del_pc["id"], parametri)
        assert r.status_code == 409 and r.json()["detail"] == {"errore": "dispositivo_revocato"}
    assert _proposte(client) == []


def test_parametri_sbagliati_422(client, famiglia):
    tiktok = regola(client, FIGLIO)
    del_pc = regola(client, famiglia.pc_andrea, parametri=MINECRAFT)
    assert _proponi(client, FIGLIO, tiktok["id"], _limite(0)).status_code == 422  # fuori scala
    assert _proponi(client, FIGLIO, tiktok["id"], {"dalle": "22:00"}).status_code == 422  # non e' un limite
    # una chiave da telefono su una regola del computer
    r = _proponi(client, FIGLIO, del_pc["id"], _limite(90, app="com.mojang.minecraftpe"))
    assert r.status_code == 422
    assert _proposte(client) == []


@pytest.mark.parametrize("primo,secondo", [("figlio", "genitore"), ("genitore", "figlio")])
def test_una_sola_pendente_per_regola_chiunque_l_abbia_fatta(client, famiglia, primo, secondo):
    chi = {"figlio": FIGLIO, "genitore": GENITORE}
    tiktok = regola(client, FIGLIO)
    _proposta(client, chi[primo], tiktok["id"], _limite(90))
    for headers in (chi[secondo], chi[primo], famiglia.pc_andrea):
        r = _proponi(client, headers, tiktok["id"], _limite(30))
        assert r.status_code == 409 and r.json()["detail"] == {"errore": "proposta_gia_pendente"}
    (unica,) = _proposte(client)
    assert unica["autore"] == primo


def test_proposte_simultanee_dei_due_autori_ne_nasce_una(client, famiglia):
    """Genitore, telefono e computer propongono insieme sulla stessa regola: il
    controllo "una sola pendente" sta dentro il lock per tutti e due gli autori."""
    tiktok = regola(client, FIGLIO)
    chi = [GENITORE, FIGLIO, famiglia.pc_andrea, GENITORE, FIGLIO, famiglia.pc_andrea]
    barriera = threading.Barrier(len(chi))
    esiti = []

    def spara(headers, minuti):
        barriera.wait()
        esiti.append(_proponi(client, headers, tiktok["id"], _limite(minuti)).status_code)

    thread = [threading.Thread(target=spara, args=(h, 70 + i)) for i, h in enumerate(chi)]
    for t in thread:
        t.start()
    for t in thread:
        t.join()
    assert sorted(esiti) == [200] + [409] * (len(chi) - 1)
    assert len([p for p in _proposte(client) if p["stato"] == "pendente"]) == 1


# --- il genitore risponde ---

def test_il_genitore_accetta_e_vale_subito_anche_se_allenta(client, famiglia):
    """La regola e' appena nata: allentarla da soli si puo' solo fra 4 giorni. Se la
    proposta del figlio la accetta il genitore, vale subito."""
    tiktok = regola(client, FIGLIO, parametri=_limite(60))
    da_solo = client.patch(f"/api/regole/{tiktok['id']}", json={"parametri": _limite(90)}, headers=FIGLIO)
    assert da_solo.status_code == 409 and da_solo.json()["detail"]["errore"] == "lock_attivo"
    p = _proposta(client, famiglia.pc_andrea, tiktok["id"], _limite(90))
    gia_viste = _ids_notifiche(client, GENITORE)

    r = _rispondi(client, GENITORE, p["id"], "accetta", motivazione="va bene, ma il sabato")
    assert r.status_code == 200, r.text
    dati = r.json()
    assert dati["regola"]["id"] == tiktok["id"] and dati["regola"]["parametri"] == _limite(90)
    assert (dati["proposta"]["stato"], dati["proposta"]["usata"]) == ("accettata", True)
    assert dati["proposta"]["autore"] == "figlio"
    assert dati["proposta"]["risposta"]["esito"] == "accetta"
    assert dati["proposta"]["risposta"]["motivazione"] == "va bene, ma il sabato"
    assert _regola_in_finestra(client, tiktok["id"])["parametri"] == _limite(90)
    assert [x["parametri"] for x in _patto(client, FIGLIO)["regole"]] == [_limite(90)]
    ultima = _storico(client, tiktok["id"])[0]
    assert (ultima["azione"], ultima["direzione"], ultima["concordata"]) == ("modifica", "allenta", True)
    assert (ultima["prima"], ultima["dopo"]) == (_limite(60), _limite(90))
    # niente modifica_regola al genitore: la modifica l'ha appena decisa lui
    assert _ids_notifiche(client, GENITORE) == gia_viste


def test_quando_accetta_il_figlio_il_genitore_riceve_tutto_come_prima(client, famiglia):
    tiktok = regola(client, FIGLIO)
    p = _proposta(client, GENITORE, tiktok["id"], _limite(90))
    gia_viste = _ids_notifiche(client, GENITORE)
    assert _rispondi(client, FIGLIO, p["id"], "accetta").status_code == 200
    nuove = [n for n in _notifiche(client, GENITORE) if n["id"] not in gia_viste]
    assert [n["tipo"] for n in nuove] == ["modifica_regola", "proposta_risposta"]
    risposta = nuove[1]
    assert risposta["messaggio"] == "Il figlio ha risposto alla proposta: accetta"
    assert risposta["payload"] == {
        "proposta_id": p["id"], "regola_id": tiktok["id"], "esito": "accetta", "autore": "genitore",
    }
    assert _notifiche(client, FIGLIO, "proposta_risposta") == []


def test_la_risposta_del_genitore_arriva_a_tutti_i_dispositivi(client, famiglia):
    """La regola e' del computer, ma la risposta la cerca il figlio dove si trova:
    arriva al telefono e al computer, e ciascuno la legge per conto suo."""
    del_pc = regola(client, famiglia.pc_andrea, parametri=MINECRAFT)
    p = _proposta(client, famiglia.pc_andrea, del_pc["id"], _minecraft(90))
    assert _rispondi(client, GENITORE, p["id"], "accetta").status_code == 200
    (sul_telefono,) = _notifiche(client, FIGLIO, "proposta_risposta")
    (sul_pc,) = _notifiche(client, famiglia.pc_andrea, "proposta_risposta")
    assert sul_telefono == sul_pc
    assert sul_telefono["messaggio"] == "Genitore ha accettato la tua proposta: +30 min al giorno rispetto ad ora"
    assert sul_telefono["payload"] == {
        "proposta_id": p["id"], "regola_id": del_pc["id"], "esito": "accetta", "autore": "figlio",
        "genitore": GENITORE_1,  # (v3.6) chi ha risposto
    }
    assert (sul_telefono["destinatario"], sul_telefono["figlio_id"], sul_telefono["dispositivo_id"]) == (
        "figlio", 1, None,
    )
    # letta sul telefono, resta da leggere sul computer
    assert client.post(f"/api/notifiche/{sul_telefono['id']}/letta", headers=FIGLIO).status_code == 200
    assert _notifiche(client, FIGLIO, "proposta_risposta") == []
    assert [n["id"] for n in _notifiche(client, famiglia.pc_andrea, "proposta_risposta")] == [sul_pc["id"]]
    assert client.post(f"/api/notifiche/{sul_pc['id']}/letta", headers=famiglia.pc_andrea).status_code == 200
    assert _notifiche(client, famiglia.pc_andrea, "proposta_risposta") == []
    # Marta non la vede e non la marca
    assert _notifiche(client, famiglia.tel_marta) == []
    assert client.post(f"/api/notifiche/{sul_pc['id']}/letta", headers=famiglia.tel_marta).status_code == 404


def test_accetta_del_genitore_concorrenti_uno_solo_applica(client, famiglia):
    """Atomica come la risposta del figlio: sei "accetta" simultanei del genitore (un
    doppio tocco, due telefoni dei genitori) applicano la modifica una volta sola."""
    tiktok = regola(client, FIGLIO, parametri=_limite(60))
    p = _proposta(client, FIGLIO, tiktok["id"], _limite(90))
    quante = 6
    barriera = threading.Barrier(quante)
    esiti = []

    def spara():
        barriera.wait()
        esiti.append(_rispondi(client, GENITORE, p["id"], "accetta").status_code)

    thread = [threading.Thread(target=spara) for _ in range(quante)]
    for t in thread:
        t.start()
    for t in thread:
        t.join()
    assert sorted(esiti) == [200] + [409] * (quante - 1)
    assert [s["azione"] for s in _storico(client, tiktok["id"])] == ["modifica", "creazione"]
    assert len(_notifiche(client, FIGLIO, "proposta_risposta")) == 1


def test_il_genitore_rifiuta(client, famiglia):
    tiktok = regola(client, FIGLIO, parametri=_limite(60))
    p = _proposta(client, FIGLIO, tiktok["id"], _limite(120))
    r = _rispondi(client, GENITORE, p["id"], "rifiuta", motivazione="parliamone a cena")
    assert r.status_code == 200, r.text
    assert r.json()["regola"] is None
    chiusa = r.json()["proposta"]
    assert (chiusa["stato"], chiusa["usata"], chiusa["autore"]) == ("rifiutata", False, "figlio")
    assert chiusa["risposta"]["esito"] == "rifiuta"
    assert chiusa["risposta"]["motivazione"] == "parliamone a cena"
    assert _regola_in_finestra(client, tiktok["id"])["parametri"] == _limite(60)
    assert [s["azione"] for s in _storico(client, tiktok["id"])] == ["creazione"]
    for headers in (FIGLIO, famiglia.pc_andrea):
        (avviso,) = _notifiche(client, headers, "proposta_risposta")
        assert avviso["messaggio"] == "Genitore ha rifiutato la tua proposta: +60 min al giorno rispetto ad ora"
        assert avviso["payload"]["esito"] == "rifiuta"
    r = _rispondi(client, GENITORE, p["id"], "accetta")
    assert r.status_code == 409 and r.json()["detail"] == {"errore": "proposta_non_pendente"}


def test_il_genitore_accetta_un_eliminazione(client, famiglia):
    regola(client, FIGLIO)  # la regola che resta
    youtube = regola(client, FIGLIO, parametri=_limite(90, app="YouTube"))
    p = _proposta(client, FIGLIO, youtube["id"], {"azione": "elimina"})
    gia_viste = _ids_notifiche(client, GENITORE)
    r = _rispondi(client, GENITORE, p["id"], "accetta")
    assert r.status_code == 200, r.text
    assert r.json()["regola"] is None and r.json()["proposta"]["usata"] is True
    attive = [x["id"] for x in client.get("/api/regole", headers=FIGLIO).json()["regole"]]
    assert youtube["id"] not in attive
    ultima = _storico(client, youtube["id"])[0]
    assert (ultima["azione"], ultima["concordata"]) == ("eliminazione", True)
    assert _ids_notifiche(client, GENITORE) == gia_viste  # niente modifica_regola doppia
    (avviso,) = _notifiche(client, FIGLIO, "proposta_risposta")
    assert avviso["messaggio"] == "Genitore ha accettato la tua proposta di eliminare la regola"


def test_eliminare_l_ultima_regola_resta_vietato(client, famiglia):
    tiktok = regola(client, FIGLIO)
    p = _proposta(client, FIGLIO, tiktok["id"], {"azione": "elimina"})
    r = _rispondi(client, GENITORE, p["id"], "accetta")
    assert r.status_code == 409 and r.json()["detail"] == {"errore": "ultima_regola"}
    (ancora,) = _proposte(client)
    assert (ancora["stato"], ancora["usata"]) == ("pendente", False)
    assert _regola_in_finestra(client, tiktok["id"])["attiva"] is True
    assert _notifiche(client, FIGLIO, "proposta_risposta") == []  # niente: e' tornato tutto indietro


def test_dispositivo_revocato_prima_della_risposta(client, famiglia):
    """Come per le proposte del genitore (v3.1): accettarla risponde 409
    dispositivo_revocato e resta pendente; rifiutarla si puo'."""
    regola(client, FIGLIO)
    del_pc = regola(client, famiglia.pc_andrea, parametri=MINECRAFT)
    p = _proposta(client, FIGLIO, del_pc["id"], _minecraft(90))
    _revoca(client, famiglia.pc_andrea_id)
    r = _rispondi(client, GENITORE, p["id"], "accetta")
    assert r.status_code == 409 and r.json()["detail"] == {"errore": "dispositivo_revocato"}
    assert _stato(client, p["id"]) == "pendente"
    assert _regola_in_finestra(client, del_pc["id"])["parametri"] == MINECRAFT
    r = _rispondi(client, GENITORE, p["id"], "rifiuta")
    assert r.status_code == 200 and r.json()["proposta"]["stato"] == "rifiutata"


def test_alla_propria_proposta_non_si_risponde(client, famiglia):
    tiktok = regola(client, FIGLIO)
    youtube = regola(client, FIGLIO, parametri=_limite(90, app="YouTube"))
    del_figlio = _proposta(client, FIGLIO, tiktok["id"], _limite(90))
    del_genitore = _proposta(client, GENITORE, youtube["id"], _limite(60, app="YouTube"))
    # il figlio e' lo stesso da tutti i suoi dispositivi
    for headers in (FIGLIO, famiglia.pc_andrea):
        for esito in ("accetta", "rifiuta"):
            assert _rispondi(client, headers, del_figlio["id"], esito).status_code == 403
    assert _rispondi(client, GENITORE, del_genitore["id"], "accetta").status_code == 403
    # un altro figlio non risponde a niente di Andrea
    for p in (del_figlio, del_genitore):
        assert _rispondi(client, famiglia.tel_marta, p["id"], "accetta").status_code == 403
    assert {p["stato"] for p in _proposte(client)} == {"pendente"}
    assert _regola_in_finestra(client, tiktok["id"])["parametri"] == _limite(60)


def test_il_genitore_risponde_col_figlio_id(client, famiglia):
    """figlio_id facoltativo, come negli altri endpoint del genitore: un figlio che non
    c'e' o che non e' quello della proposta -> 404."""
    tiktok = regola(client, FIGLIO)
    p = _proposta(client, FIGLIO, tiktok["id"], _limite(90))
    assert _rispondi(client, GENITORE, 999, "rifiuta").status_code == 404
    assert _rispondi(client, GENITORE, p["id"], "rifiuta", figlio_id=999).status_code == 404
    assert _rispondi(client, GENITORE, p["id"], "rifiuta", figlio_id=famiglia.marta).status_code == 404
    assert _stato(client, p["id"]) == "pendente"
    assert _rispondi(client, GENITORE, p["id"], "rifiuta", figlio_id=1).status_code == 200


# --- ritirare ---

def test_il_figlio_ritira_la_sua_proposta(client, famiglia):
    tiktok = regola(client, FIGLIO, parametri=_limite(60))
    p = _proposta(client, FIGLIO, tiktok["id"], _limite(90))
    r = _ritira(client, famiglia.pc_andrea, p["id"])  # da un altro suo dispositivo
    assert r.status_code == 200, r.text
    ritirata = r.json()
    assert (ritirata["stato"], ritirata["usata"], ritirata["risposta"]) == ("ritirata", False, None)
    assert ritirata["autore"] == "figlio"
    assert ritirata["confronto"] == "+30 min al giorno rispetto ad ora"
    assert _regola_in_finestra(client, tiktok["id"])["parametri"] == _limite(60)
    assert [s["azione"] for s in _storico(client, tiktok["id"])] == ["creazione"]
    (avviso,) = _notifiche(client, GENITORE, "proposta_ritirata")
    assert avviso["messaggio"] == "Andrea ha ritirato la sua proposta"
    assert avviso["payload"] == {"proposta_id": p["id"], "regola_id": tiktok["id"], "autore": "figlio"}
    assert (avviso["figlio_id"], avviso["dispositivo_id"]) == (1, 1)  # la regola e' del telefono
    for headers in (FIGLIO, famiglia.pc_andrea):
        assert _notifiche(client, headers, "proposta_ritirata") == []
    # chiusa: non si risponde e non si ritira piu', e sulla regola se ne puo' fare un'altra
    for chiamata in (_rispondi(client, GENITORE, p["id"], "accetta"), _ritira(client, FIGLIO, p["id"])):
        assert chiamata.status_code == 409 and chiamata.json()["detail"] == {"errore": "proposta_non_pendente"}
    assert _proponi(client, GENITORE, tiktok["id"], _limite(30)).status_code == 200


def test_il_genitore_ritira_la_sua_proposta(client, famiglia):
    del_pc = regola(client, famiglia.pc_andrea, parametri=MINECRAFT)
    p = _proposta(client, GENITORE, del_pc["id"], _minecraft(30))
    r = _ritira(client, GENITORE, p["id"])
    assert r.status_code == 200, r.text
    assert (r.json()["stato"], r.json()["autore"]) == ("ritirata", "genitore")
    assert _regola_in_finestra(client, del_pc["id"])["parametri"] == MINECRAFT
    # all'altro, col dispositivo della regola, come la nuova_proposta che l'aveva annunciata
    (avviso,) = _notifiche(client, famiglia.pc_andrea, "proposta_ritirata")
    assert avviso["messaggio"] == "Genitore ha ritirato la sua proposta"
    assert avviso["payload"] == {"proposta_id": p["id"], "regola_id": del_pc["id"], "autore": "genitore",
                                 "genitore": GENITORE_1}
    assert (avviso["destinatario"], avviso["dispositivo_id"]) == ("figlio", famiglia.pc_andrea_id)
    assert _notifiche(client, FIGLIO, "proposta_ritirata") == []
    assert _notifiche(client, GENITORE, "proposta_ritirata") == []
    assert _patto(client, famiglia.pc_andrea)["proposte_pendenti"] == []
    r = _rispondi(client, famiglia.pc_andrea, p["id"], "accetta")
    assert r.status_code == 409 and r.json()["detail"] == {"errore": "proposta_non_pendente"}


def test_si_ritira_solo_la_propria(client, famiglia):
    tiktok = regola(client, FIGLIO)
    youtube = regola(client, FIGLIO, parametri=_limite(90, app="YouTube"))
    del_figlio = _proposta(client, FIGLIO, tiktok["id"], _limite(90))
    del_genitore = _proposta(client, GENITORE, youtube["id"], _limite(60, app="YouTube"))
    assert _ritira(client, GENITORE, del_figlio["id"]).status_code == 403
    for headers in (FIGLIO, famiglia.pc_andrea):
        assert _ritira(client, headers, del_genitore["id"]).status_code == 403
    for p in (del_figlio, del_genitore):  # nemmeno un altro figlio
        assert _ritira(client, famiglia.tel_marta, p["id"]).status_code == 403
    assert _ritira(client, GENITORE, 999).status_code == 404
    assert {p["stato"] for p in _proposte(client)} == {"pendente"}
    assert _notifiche(client, GENITORE, "proposta_ritirata") == []


def test_il_confronto_si_ferma_al_ritiro(client, famiglia):
    tiktok = regola(client, FIGLIO, parametri=_limite(60))
    p = _proposta(client, FIGLIO, tiktok["id"], _limite(90))
    # il figlio stringe da solo a 40 (subito): la proposta ora vale +50
    assert client.patch(f"/api/regole/{tiktok['id']}", json={"parametri": _limite(40)},
                        headers=FIGLIO).status_code == 200
    (pendente,) = _proposte(client)
    assert pendente["confronto"] == "+50 min al giorno rispetto ad ora"
    assert _ritira(client, FIGLIO, p["id"]).json()["confronto"] == "+50 min al giorno rispetto ad ora"
    assert client.patch(f"/api/regole/{tiktok['id']}", json={"parametri": _limite(30)},
                        headers=FIGLIO).status_code == 200
    (chiusa,) = _proposte(client)
    assert chiusa["confronto"] == "+50 min al giorno rispetto ad ora"


@pytest.mark.parametrize("autore", ["figlio", "genitore"])
def test_ritira_e_accetta_insieme_ne_passa_uno(client, famiglia, autore):
    """Un "ritira" e un "accetta" che arrivano insieme: uno solo passa (200), l'altro
    trova la proposta gia' chiusa (409 proposta_non_pendente), e lo stato finale e'
    quello di chi ha vinto."""
    chi_propone, chi_risponde = (FIGLIO, GENITORE) if autore == "figlio" else (GENITORE, FIGLIO)
    tiktok = regola(client, FIGLIO, parametri=_limite(60))
    for _ in range(4):
        attuale = _regola_in_finestra(client, tiktok["id"])["parametri"]["minuti_al_giorno"]
        p = _proposta(client, chi_propone, tiktok["id"], _limite(attuale + 10))
        barriera = threading.Barrier(2)
        esiti = {}

        def ritira():
            barriera.wait()
            esiti["ritira"] = _ritira(client, chi_propone, p["id"])

        def accetta():
            barriera.wait()
            esiti["accetta"] = _rispondi(client, chi_risponde, p["id"], "accetta")

        thread = [threading.Thread(target=ritira), threading.Thread(target=accetta)]
        for t in thread:
            t.start()
        for t in thread:
            t.join()
        assert sorted(r.status_code for r in esiti.values()) == [200, 409]
        (perdente,) = [r for r in esiti.values() if r.status_code == 409]
        assert perdente.json()["detail"] == {"errore": "proposta_non_pendente"}
        dopo = _regola_in_finestra(client, tiktok["id"])["parametri"]["minuti_al_giorno"]
        if esiti["accetta"].status_code == 200:
            assert (_stato(client, p["id"]), dopo) == ("accettata", attuale + 10)
        else:
            assert (_stato(client, p["id"]), dopo) == ("ritirata", attuale)


# --- i messaggi di un'eliminazione ---

def test_i_messaggi_di_un_eliminazione_non_ripetono_il_verbo(client, famiglia):
    """Il campo `confronto` resta "propone di eliminare la regola" (le app lo mostrano
    com'e'); i messaggi delle notifiche, che hanno gia' il loro verbo, non lo ripetono."""
    regola(client, FIGLIO)  # la regola che resta
    youtube = regola(client, FIGLIO, parametri=_limite(90, app="YouTube"))
    instagram = regola(client, FIGLIO, parametri=_limite(30, app="Instagram"))

    def ultimo(headers, tipo):
        return _notifiche(client, headers, tipo)[-1]

    # il figlio propone, il genitore rifiuta
    p = _proposta(client, FIGLIO, youtube["id"], {"azione": "elimina"})
    assert ultimo(GENITORE, "nuova_proposta")["messaggio"] == "Andrea propone di eliminare la regola"
    assert ultimo(GENITORE, "nuova_proposta")["payload"]["confronto"] == "propone di eliminare la regola"
    assert _rispondi(client, GENITORE, p["id"], "rifiuta").status_code == 200
    assert ultimo(FIGLIO, "proposta_risposta")["messaggio"] == (
        "Genitore ha rifiutato la tua proposta di eliminare la regola"
    )
    # il figlio propone e ritira
    p = _proposta(client, FIGLIO, youtube["id"], {"azione": "elimina"})
    assert _ritira(client, famiglia.pc_andrea, p["id"]).status_code == 200
    assert ultimo(GENITORE, "proposta_ritirata")["messaggio"] == (
        "Andrea ha ritirato la sua proposta di eliminare la regola"
    )
    # il genitore propone e ritira
    p = _proposta(client, GENITORE, instagram["id"], {"azione": "elimina"})
    assert ultimo(FIGLIO, "nuova_proposta")["messaggio"] == "Genitore propone di eliminare la regola"
    assert _ritira(client, GENITORE, p["id"]).status_code == 200
    assert ultimo(FIGLIO, "proposta_ritirata")["messaggio"] == (
        "Genitore ha ritirato la sua proposta di eliminare la regola"
    )
    # il figlio propone, il genitore accetta
    p = _proposta(client, FIGLIO, instagram["id"], {"azione": "elimina"})
    assert _rispondi(client, GENITORE, p["id"], "accetta").status_code == 200
    assert ultimo(FIGLIO, "proposta_risposta")["messaggio"] == (
        "Genitore ha accettato la tua proposta di eliminare la regola"
    )
    # il confronto delle proposte resta quello di sempre
    assert {x["confronto"] for x in _proposte(client)} == {"propone di eliminare la regola"}


# --- dove si vedono ---

def test_patto_pendenti_e_inviate(client, famiglia):
    tiktok = regola(client, FIGLIO, parametri=_limite(60))
    youtube = regola(client, FIGLIO, parametri=_limite(90, app="YouTube"))
    del_pc = regola(client, famiglia.pc_andrea, parametri=MINECRAFT)
    vita = regola(client, FIGLIO, tipo="vita_reale", parametri=VITA)
    del_genitore = _proposta(client, GENITORE, tiktok["id"], _limite(30))
    prima_mia = _proposta(client, FIGLIO, youtube["id"], _limite(120, app="YouTube"))
    seconda_mia = _proposta(client, famiglia.pc_andrea, del_pc["id"], _minecraft(90))
    ritirata = _proposta(client, FIGLIO, vita["id"], {"azione": "elimina"})
    assert _ritira(client, FIGLIO, ritirata["id"]).status_code == 200
    # di tutto il figlio, uguali da tutti e due i dispositivi
    for headers in (FIGLIO, famiglia.pc_andrea):
        patto = _patto(client, headers)
        assert [(p["id"], p["autore"]) for p in patto["proposte_pendenti"]] == [(del_genitore["id"], "genitore")]
        assert [(p["id"], p["autore"]) for p in patto["proposte_inviate"]] == [
            (seconda_mia["id"], "figlio"), (prima_mia["id"], "figlio"),  # dalla piu' recente
        ]
    # le inviate hanno il confronto di adesso, come le pendenti (v2.1)
    assert client.patch(f"/api/regole/{youtube['id']}", json={"parametri": _limite(60, app="YouTube")},
                        headers=FIGLIO).status_code == 200
    (_, inviata) = _patto(client, FIGLIO)["proposte_inviate"]
    assert inviata["confronto"] == "+60 min al giorno rispetto ad ora"
    # niente di Andrea nel patto di Marta
    patto_marta = _patto(client, famiglia.tel_marta)
    assert patto_marta["proposte_pendenti"] == [] and patto_marta["proposte_inviate"] == []


def test_finestra_proposte_pendenti(client, famiglia):
    tiktok = regola(client, FIGLIO)
    youtube = regola(client, FIGLIO, parametri=_limite(90, app="YouTube"))
    vita = regola(client, FIGLIO, tipo="vita_reale", parametri=VITA)
    del_genitore = _proposta(client, GENITORE, tiktok["id"], _limite(30))
    del_figlio = _proposta(client, famiglia.pc_andrea, youtube["id"], _limite(120, app="YouTube"))
    rifiutata = _proposta(client, FIGLIO, vita["id"], {"azione": "elimina"})
    assert _rispondi(client, GENITORE, rifiutata["id"], "rifiuta").status_code == 200
    finestra = _finestra(client)
    assert [(p["id"], p["autore"]) for p in finestra["proposte_pendenti"]] == [
        (del_figlio["id"], "figlio"), (del_genitore["id"], "genitore"),
    ]
    # la stessa forma di GET /api/proposte
    per_id = {p["id"]: p for p in _proposte(client)}
    assert all(p == per_id[p["id"]] for p in finestra["proposte_pendenti"])
    assert _finestra(client, famiglia.marta)["proposte_pendenti"] == []


def test_famiglia_proposte_da_decidere(client, famiglia):
    tiktok = regola(client, FIGLIO)
    youtube = regola(client, FIGLIO, parametri=_limite(90, app="YouTube"))
    del_pc = regola(client, famiglia.pc_andrea, parametri=MINECRAFT)
    di_marta = regola(client, famiglia.tel_marta)
    _proposta(client, FIGLIO, tiktok["id"], _limite(90))
    dal_pc = _proposta(client, famiglia.pc_andrea, del_pc["id"], _minecraft(90))
    _proposta(client, GENITORE, youtube["id"], _limite(60, app="YouTube"))  # aspetta il figlio
    di_marta_ritirata = _proposta(client, famiglia.tel_marta, di_marta["id"], _limite(90))
    assert _ritira(client, famiglia.tel_marta, di_marta_ritirata["id"]).status_code == 200

    def da_decidere():
        risposta = client.get("/api/famiglia", headers=GENITORE)
        assert risposta.status_code == 200, risposta.text
        return {f["id"]: f["proposte_da_decidere"] for f in risposta.json()["figli"]}

    assert da_decidere() == {1: 2, famiglia.marta: 0}
    assert _rispondi(client, GENITORE, dal_pc["id"], "rifiuta").status_code == 200
    assert da_decidere() == {1: 1, famiglia.marta: 0}


def test_get_proposte_di_tutti_e_due_gli_autori_solo_a_richiesta(client, famiglia):
    """Senza parametri GET /api/proposte resta com'era: solo le proposte del genitore,
    cosi' le app 0.8/0.9 non scambiano una proposta del figlio per una del genitore.
    Con ?autori=tutti, quelle di tutti e due (anche insieme a figlio_id)."""
    tiktok = regola(client, FIGLIO)
    youtube = regola(client, FIGLIO, parametri=_limite(90, app="YouTube"))
    del_genitore = _proposta(client, GENITORE, tiktok["id"], _limite(30))
    del_figlio = _proposta(client, famiglia.pc_andrea, youtube["id"], _limite(120, app="YouTube"))
    tutte = [(del_figlio["id"], "figlio"), (del_genitore["id"], "genitore")]
    for headers in (FIGLIO, famiglia.pc_andrea, GENITORE):
        come_prima = client.get("/api/proposte", headers=headers)
        assert come_prima.status_code == 200
        assert [(p["id"], p["autore"]) for p in come_prima.json()["proposte"]] == [(del_genitore["id"], "genitore")]
        assert [(p["id"], p["autore"]) for p in _proposte(client, headers)] == tutte
    assert [(p["id"], p["autore"]) for p in _proposte(client, GENITORE, figlio_id=1)] == tutte
    assert _proposte(client, GENITORE, figlio_id=famiglia.marta) == []
    assert _proposte(client, famiglia.tel_marta) == []
    # un altro valore di `autori` non vale
    for valore in ("figlio", "genitore", "TUTTI", ""):
        for headers in (FIGLIO, GENITORE):
            r = client.get("/api/proposte", headers=headers, params={"autori": valore})
            assert r.status_code == 422, (valore, r.text)


def test_get_proposte_resta_al_massimo_50(client, famiglia, db_path):
    """Al massimo 50, dalla piu' recente, con e senza ?autori=tutti."""
    tiktok = regola(client, FIGLIO)
    conn = sqlite3.connect(db_path)
    try:  # 120 proposte chiuse: id dispari del genitore, id pari del figlio
        conn.executemany(
            "INSERT INTO proposte (regola_id, parametri_proposti, stato, usata, ts_server, autore)"
            " VALUES (?, '{\"azione\": \"elimina\"}', 'rifiutata', 0, '2026-07-14T09:00:00+00:00', ?)",
            [(tiktok["id"], "figlio" if i % 2 else "genitore") for i in range(120)],
        )
        conn.commit()
    finally:
        conn.close()
    assert [p["id"] for p in _proposte(client)] == list(range(120, 70, -1))
    come_prima = client.get("/api/proposte", headers=GENITORE).json()["proposte"]
    assert [p["id"] for p in come_prima] == list(range(119, 20, -2))  # 50, solo del genitore


def test_eliminazione_diretta_annulla_le_pendenti_di_tutti_e_due(client, famiglia, orologio):
    regola(client, FIGLIO)  # la regola che resta
    youtube = regola(client, FIGLIO, parametri=_limite(90, app="YouTube"))
    del_pc = regola(client, famiglia.pc_andrea, parametri=MINECRAFT)
    del_figlio = _proposta(client, FIGLIO, youtube["id"], _limite(120, app="YouTube"))
    del_genitore = _proposta(client, GENITORE, del_pc["id"], _minecraft(30))
    orologio.avanza(days=5)  # niente blocco dei 4 giorni
    for r in (youtube, del_pc):
        assert client.delete(f"/api/regole/{r['id']}", headers=FIGLIO).status_code == 200
    assert (_stato(client, del_figlio["id"]), _stato(client, del_genitore["id"])) == ("annullata", "annullata")
    annullate = _notifiche(client, GENITORE, "proposta_annullata")
    assert [n["payload"] for n in annullate] == [
        {"proposta_id": del_figlio["id"], "regola_id": youtube["id"], "motivo": "regola_eliminata",
         "autore": "figlio"},
        {"proposta_id": del_genitore["id"], "regola_id": del_pc["id"], "motivo": "regola_eliminata",
         "autore": "genitore"},
    ]
    # l'avviso va solo al genitore
    for headers in (FIGLIO, famiglia.pc_andrea):
        assert _notifiche(client, headers, "proposta_annullata") == []
    r = _rispondi(client, GENITORE, del_figlio["id"], "accetta")
    assert r.status_code == 409 and r.json()["detail"] == {"errore": "proposta_non_pendente"}
    r = _ritira(client, GENITORE, del_genitore["id"])
    assert r.status_code == 409 and r.json()["detail"] == {"errore": "proposta_non_pendente"}


# --- casi limite: regola tolta a mano, dispositivo revocato ---

def test_proposta_con_la_regola_tolta_a_mano_404(client, famiglia, db_path):
    """Una regola cancellata a mano dal database (le chiavi esterne non scattano fuori
    dal server) lascia proposte orfane: risposta e ritiro rispondono 404 come per una
    proposta che non c'e', mai un errore del server; gli elenchi non le mostrano."""
    regola(client, FIGLIO)  # la regola che resta
    youtube = regola(client, FIGLIO, parametri=_limite(90, app="YouTube"))
    del_pc = regola(client, famiglia.pc_andrea, parametri=MINECRAFT)
    del_figlio = _proposta(client, FIGLIO, youtube["id"], _limite(120, app="YouTube"))
    del_genitore = _proposta(client, GENITORE, del_pc["id"], _minecraft(30))
    conn = sqlite3.connect(db_path)
    try:
        conn.execute("DELETE FROM regole WHERE id IN (?, ?)", (youtube["id"], del_pc["id"]))
        conn.commit()
    finally:
        conn.close()
    chiamate = [
        _rispondi(client, GENITORE, del_figlio["id"], "accetta"),
        _rispondi(client, FIGLIO, del_genitore["id"], "rifiuta"),
        _rispondi(client, famiglia.tel_marta, del_genitore["id"], "rifiuta"),
        _ritira(client, FIGLIO, del_figlio["id"]),
        _ritira(client, GENITORE, del_genitore["id"]),
    ]
    for r in chiamate:
        assert r.status_code == 404 and r.json() == {"detail": "proposta non trovata"}, r.text
    assert _proposte(client) == []
    assert _patto(client, FIGLIO)["proposte_inviate"] == []
    assert _finestra(client)["proposte_pendenti"] == []
    assert client.get("/api/famiglia", headers=GENITORE).status_code == 200


def test_da_decidere_non_conta_le_regole_di_un_dispositivo_revocato(client, famiglia):
    """Non si possono accettare (409 dispositivo_revocato), solo rifiutare: non le
    conta, ma restano nella finestra."""
    tiktok = regola(client, FIGLIO)
    del_pc = regola(client, famiglia.pc_andrea, parametri=MINECRAFT)
    _proposta(client, FIGLIO, tiktok["id"], _limite(90))
    _proposta(client, FIGLIO, del_pc["id"], _minecraft(90))

    def da_decidere():
        (andrea, _) = client.get("/api/famiglia", headers=GENITORE).json()["figli"]
        return andrea["proposte_da_decidere"]

    assert da_decidere() == 2
    _revoca(client, famiglia.pc_andrea_id)
    assert da_decidere() == 1
    assert len(_finestra(client)["proposte_pendenti"]) == 2


def test_le_notifiche_per_un_dispositivo_revocato_vanno_a_tutto_il_figlio(client, famiglia):
    """Il genitore ritira una sua proposta su una regola del computer revocato: il
    computer non legge piu' niente, l'avviso va a tutto il figlio. Le notifiche per il
    genitore tengono invece il dispositivo della regola: dicono solo di quale e'."""
    regola(client, FIGLIO)
    minecraft = regola(client, famiglia.pc_andrea, parametri=MINECRAFT)
    roblox = regola(client, famiglia.pc_andrea, parametri=_limite(60, app="exe:roblox.exe"))
    da_ritirare = _proposta(client, GENITORE, minecraft["id"], _minecraft(30))
    da_rifiutare = _proposta(client, GENITORE, roblox["id"], _limite(30, app="exe:roblox.exe"))
    _revoca(client, famiglia.pc_andrea_id)
    assert _ritira(client, GENITORE, da_ritirare["id"]).status_code == 200
    (avviso,) = _notifiche(client, FIGLIO, "proposta_ritirata")
    assert (avviso["destinatario"], avviso["dispositivo_id"]) == ("figlio", None)
    assert avviso["payload"] == {"proposta_id": da_ritirare["id"], "regola_id": minecraft["id"],
                                 "autore": "genitore", "genitore": GENITORE_1}
    assert _rispondi(client, FIGLIO, da_rifiutare["id"], "rifiuta").status_code == 200
    (al_genitore,) = _notifiche(client, GENITORE, "proposta_risposta")
    assert al_genitore["dispositivo_id"] == famiglia.pc_andrea_id


# --- migrazione dalla v3.3 ---

# La tabella proposte com'era fino alla v3.3 (git show 78c61ad:server/app/db.py):
# senza autore e senza 'ritirata'. Tutto il resto del database della v3.3 e' uguale.
PROPOSTE_V33 = """
CREATE TABLE proposte (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    regola_id INTEGER NOT NULL REFERENCES regole(id),
    parametri_proposti TEXT,
    motivazione TEXT,
    confronto TEXT,
    direzione TEXT,
    stato TEXT NOT NULL DEFAULT 'pendente' CHECK (stato IN ('pendente', 'accettata', 'rifiutata', 'annullata')),
    usata INTEGER NOT NULL DEFAULT 0,
    risposta_esito TEXT,
    risposta_motivazione TEXT,
    risposta_ts TEXT,
    ts_server TEXT NOT NULL
)"""
COLONNE_V33 = (
    "id, regola_id, parametri_proposti, motivazione, confronto, direzione, stato, usata,"
    " risposta_esito, risposta_motivazione, risposta_ts, ts_server"
)


def _torna_alla_v33(db_path, contatore=None):
    """Il database com'era col server v3.3: la tabella proposte nella forma di prima,
    con le stesse righe (nella v3.3 proponeva solo il genitore). `contatore` mette il
    contatore dell'AUTOINCREMENT, come se delle proposte fossero state tolte a mano."""
    conn = sqlite3.connect(db_path)
    try:
        conn.execute("PRAGMA foreign_keys=OFF")
        conn.execute("BEGIN")
        conn.execute("ALTER TABLE proposte RENAME TO _proposte_v34")
        conn.execute(PROPOSTE_V33)
        conn.execute(f"INSERT INTO proposte ({COLONNE_V33}) SELECT {COLONNE_V33} FROM _proposte_v34")
        conn.execute("DROP TABLE _proposte_v34")
        if contatore is not None:
            conn.execute("UPDATE sqlite_sequence SET seq = ? WHERE name = 'proposte'", (contatore,))
        conn.commit()
    finally:
        conn.close()


def _sql_proposte(db_path) -> str:
    conn = sqlite3.connect(db_path)
    try:
        return conn.execute(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'proposte'"
        ).fetchone()[0]
    finally:
        conn.close()


def _copie(db_path, suffisso) -> list[Path]:
    percorso = Path(db_path)
    return sorted(percorso.parent.glob(percorso.name + suffisso + "*"))


def _senza_autore(righe) -> list:
    """Le proposte senza le colonne nate dopo la v3.3: autore (v3.4), chi tra i genitori
    ha proposto e chi ha risposto (v3.6, NULL sulle righe di prima)."""
    nuove = {"autore", "genitore_id", "risposta_genitore_id"}
    return [{k: v for k, v in r.items() if k not in nuove} for r in righe]


def _storia_v33(client, orologio) -> dict:
    """Una storia della v3.3: proposte del genitore in tutti e quattro gli stati."""
    tiktok = regola(client, FIGLIO, parametri=_limite(60))
    youtube = regola(client, FIGLIO, parametri=_limite(90, app="YouTube"))
    instagram = regola(client, FIGLIO, parametri=_limite(30, app="Instagram"))
    accettata = _proposta(client, GENITORE, tiktok["id"], _limite(90), motivazione="nel weekend")
    assert _rispondi(client, FIGLIO, accettata["id"], "accetta", motivazione="grazie").status_code == 200
    rifiutata = _proposta(client, GENITORE, youtube["id"], _limite(60, app="YouTube"))
    assert _rispondi(client, FIGLIO, rifiutata["id"], "rifiuta").status_code == 200
    annullata = _proposta(client, GENITORE, instagram["id"], {"azione": "elimina"})
    orologio.avanza(days=4)
    assert client.delete(f"/api/regole/{instagram['id']}", headers=FIGLIO).status_code == 200
    pendente = _proposta(client, GENITORE, youtube["id"], _limite(45, app="YouTube"))
    return {"tiktok": tiktok, "youtube": youtube, "pendente": pendente,
            "ids": [accettata["id"], rifiutata["id"], annullata["id"], pendente["id"]]}


def test_migrazione_dalla_v33(client, db_path, orologio):
    storia = _storia_v33(client, orologio)
    _torna_alla_v33(db_path)
    prima = dati_v24.righe(db_path)
    assert [(p["id"], p["stato"]) for p in prima["proposte"]] == list(
        zip(storia["ids"], ["accettata", "rifiutata", "annullata", "pendente"])
    )
    assert "autore" not in prima["proposte"][0]

    from app.main import create_app

    with TestClient(create_app()):  # il riavvio col server v3.4 migra
        pass
    dopo = dati_v24.righe(db_path)
    # ogni proposta e' quella di prima (id, stato, risposta, tutto) con autore 'genitore'
    assert _senza_autore(dopo["proposte"]) == prima["proposte"]
    assert {p["autore"] for p in dopo["proposte"]} == {"genitore"}
    for tabella in prima:
        if tabella != "proposte":
            assert dopo[tabella] == prima[tabella], tabella
    # la tabella e' quella di un database nuovo, e non resta niente di mezzo
    assert "_proposte_v33" not in dopo
    nuovo = str(Path(db_path).parent / "nuovo.db")
    from app import db

    db.init_db(nuovo, 30, 90)
    assert _sql_proposte(db_path) == _sql_proposte(nuovo)

    # prima di toccarlo, la copia completa del database v3.3; niente copia v3 (era gia' v3)
    (copia,) = _copie(db_path, ".prima-v3.4-")
    assert copia.name == "pactum-test.db.prima-v3.4-20260718-120000"  # 10:00 UTC = 12:00 a Roma
    conn = sqlite3.connect(copia)
    try:
        assert conn.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    finally:
        conn.close()
    assert dati_v24.righe(str(copia)) == prima
    assert _copie(db_path, ".prima-v3-") == []
    assert not list(Path(db_path).parent.glob("*.parziale"))

    # e la v3.4 funziona sul database migrato
    with TestClient(create_app()) as c:
        r = c.post(f"/api/proposte/{storia['pendente']['id']}/ritira", headers=GENITORE)
        assert r.status_code == 200 and r.json()["stato"] == "ritirata"
        r = c.post("/api/proposte", json={"regola_id": storia["youtube"]["id"],
                                          "parametri_proposti": _limite(120, app="YouTube")}, headers=FIGLIO)
        assert r.status_code == 200 and r.json()["autore"] == "figlio"
        vecchie = {p["id"]: p for p in _proposte(c)}
        assert [vecchie[i]["autore"] for i in storia["ids"]] == ["genitore"] * 4
        assert vecchie[storia["ids"][0]]["risposta"]["motivazione"] == "grazie"


def test_la_migrazione_v34_si_fa_una_volta_sola(client, db_path, orologio):
    _storia_v33(client, orologio)
    _torna_alla_v33(db_path)
    from app.main import create_app

    with TestClient(create_app()):
        pass
    dopo_il_primo = dati_v24.righe(db_path)
    for _ in range(2):
        orologio.avanza(hours=1)
        with TestClient(create_app()):  # gia' v3.4: niente da copiare ne' da rifare
            pass
    assert dati_v24.righe(db_path) == dopo_il_primo
    assert len(_copie(db_path, ".prima-v3.4-")) == 1


def test_la_migrazione_non_ricicla_gli_id(client, db_path):
    """Una proposta tolta a mano lascia un buco nel contatore: dopo la ricostruzione
    della tabella il suo id non torna a una proposta nuova (le notifiche vecchie lo
    citano ancora nel payload)."""
    tiktok = regola(client, FIGLIO)
    p = _proposta(client, GENITORE, tiktok["id"], _limite(30))
    assert _rispondi(client, FIGLIO, p["id"], "rifiuta").status_code == 200
    _torna_alla_v33(db_path, contatore=40)  # come se le proposte 2..40 fossero state tolte
    from app.main import create_app

    with TestClient(create_app()) as c:
        r = c.post("/api/proposte", json={"regola_id": tiktok["id"], "parametri_proposti": _limite(90)},
                   headers=FIGLIO)
        assert r.status_code == 200 and r.json()["id"] == 41


def test_un_errore_a_meta_migrazione_v34_lascia_tutto_com_era(client, db_path, orologio, monkeypatch):
    """La ricostruzione e' una transazione sola: se si rompe a meta' (qui dopo aver
    gia' rinominato la tabella e creato la nuova) il database resta quello della
    v3.3, e il prossimo avvio riprova da capo."""
    from app import db
    from app.main import create_app

    _storia_v33(client, orologio)
    _torna_alla_v33(db_path)
    prima = dati_v24.righe(db_path)
    copia_vera = db._copia_proposte

    def guasto(*_):
        raise RuntimeError("corrente saltata a meta' migrazione")

    monkeypatch.setattr(db, "_copia_proposte", guasto)
    with pytest.raises(RuntimeError, match="corrente saltata"):
        create_app()
    assert dati_v24.righe(db_path) == prima
    assert "'ritirata'" not in _sql_proposte(db_path)

    monkeypatch.setattr(db, "_copia_proposte", copia_vera)
    with TestClient(create_app()) as c:
        assert {p["autore"] for p in _proposte(c)} == {"genitore"}
    assert _senza_autore(dati_v24.righe(db_path)["proposte"]) == prima["proposte"]


def test_se_la_copia_v34_non_riesce_il_server_non_parte(client, db_path, orologio, monkeypatch, caplog):
    from app import db
    from app.main import create_app

    _storia_v33(client, orologio)
    _torna_alla_v33(db_path)
    prima = dati_v24.righe(db_path)
    percorso_vero = db._percorso_copia
    cartella_che_non_c_e = Path(db_path).parent / "non-esiste" / "copia.db"
    monkeypatch.setattr(db, "_percorso_copia", lambda *_: str(cartella_che_non_c_e))
    with caplog.at_level(logging.ERROR, logger="uvicorn.error"):
        with pytest.raises(RuntimeError, match=r"MIGRAZIONE v3\.4 FERMATA"):
            create_app()
    assert dati_v24.righe(db_path) == prima
    assert _copie(db_path, ".prima-v3.4-") == []
    (errore,) = [r for r in caplog.records if r.levelno == logging.ERROR]
    assert "copia di sicurezza" in errore.getMessage() and "NON parte" in errore.getMessage()

    monkeypatch.setattr(db, "_percorso_copia", percorso_vero)
    with TestClient(create_app()):
        pass
    (copia,) = _copie(db_path, ".prima-v3.4-")
    assert dati_v24.righe(str(copia)) == prima


def test_su_un_database_nuovo_niente_copia(client, db_path):
    assert client.get("/api/patto", headers=FIGLIO).status_code == 200
    assert "'ritirata'" in _sql_proposte(db_path) and "autore" in _sql_proposte(db_path)
    from app.main import create_app

    with TestClient(create_app()):
        pass
    assert _copie(db_path, ".prima-v3.4-") == [] and _copie(db_path, ".prima-v3-") == []


def test_un_database_v33_senza_storia_si_rifa_senza_copia(client, db_path):
    """Senza storia (solo la famiglia, nessuna regola) non c'e' niente da mettere al
    sicuro, come per la v3: la tabella si rifa' e basta."""
    _torna_alla_v33(db_path)
    from app.main import create_app

    with TestClient(create_app()):
        pass
    assert "'ritirata'" in _sql_proposte(db_path)
    assert _copie(db_path, ".prima-v3.4-") == []


def test_tre_avvii_falliti_una_copia_sola(client, db_path, orologio, monkeypatch):
    """Un server che in Docker riparte da solo in un giro di errori non riempie il
    disco del NAS: la copia si fa al primo avvio, quelli dopo la trovano e non la
    rifanno. La migrazione fallita non ha toccato il database: la copia vale ancora."""
    from app import db
    from app.main import create_app

    _storia_v33(client, orologio)
    _torna_alla_v33(db_path)
    prima = dati_v24.righe(db_path)
    copia_vera = db._copia_proposte

    def guasto(*_):
        raise RuntimeError("corrente saltata a meta' migrazione")

    monkeypatch.setattr(db, "_copia_proposte", guasto)
    for _ in range(3):
        orologio.avanza(seconds=30)  # ogni avvio in un altro secondo: il nome cambierebbe
        with pytest.raises(RuntimeError, match="corrente saltata"):
            create_app()
    (copia,) = _copie(db_path, ".prima-v3.4-")
    assert dati_v24.righe(str(copia)) == prima == dati_v24.righe(db_path)

    monkeypatch.setattr(db, "_copia_proposte", copia_vera)
    with TestClient(create_app()):  # adesso riesce, e la copia resta quella
        pass
    assert _copie(db_path, ".prima-v3.4-") == [copia]
    assert "'ritirata'" in _sql_proposte(db_path)


def test_la_ricostruzione_non_rompe_viste_trigger_e_indici(client, db_path, orologio):
    """Una vista e dei trigger messi a mano che nominano proposte funzionano ancora
    dopo la migrazione: la ricostruzione rinomina la tabella vecchia, e senza
    legacy_alter_table SQLite li farebbe puntare a quella, che poi sparisce. Indici e
    trigger della tabella si rifanno sulla nuova, e la copia delle righe non li fa
    scattare."""
    from app.main import create_app

    storia = _storia_v33(client, orologio)
    _torna_alla_v33(db_path)
    conn = sqlite3.connect(db_path)
    try:
        conn.executescript(
            """
            CREATE TABLE registro_prova (proposta_id INTEGER, cosa TEXT);
            CREATE INDEX idx_proposte_prova ON proposte (regola_id, stato);
            CREATE VIEW proposte_aperte AS SELECT id FROM proposte WHERE stato = 'pendente';
            CREATE TRIGGER proposte_nuove AFTER INSERT ON proposte
                BEGIN INSERT INTO registro_prova VALUES (new.id, 'nuova'); END;
            CREATE TRIGGER proposte_cambiate AFTER UPDATE OF stato ON proposte
                BEGIN INSERT INTO registro_prova VALUES (new.id, new.stato); END;
            CREATE TRIGGER regole_tolte AFTER DELETE ON regole
                BEGIN UPDATE proposte SET stato = 'annullata' WHERE regola_id = old.id; END;
            """
        )
    finally:
        conn.close()

    with TestClient(create_app()) as c:  # migra, e poi una proposta nuova e un ritiro
        nuova = c.post("/api/proposte", json={"regola_id": storia["tiktok"]["id"],
                                              "parametri_proposti": _limite(120)}, headers=FIGLIO)
        assert nuova.status_code == 200, nuova.text
        assert c.post(f"/api/proposte/{storia['pendente']['id']}/ritira", headers=GENITORE).status_code == 200

    conn = sqlite3.connect(db_path)
    try:
        oggetti = {
            (tipo, nome): (tabella, sql)
            for tipo, nome, tabella, sql in conn.execute(
                "SELECT type, name, tbl_name, sql FROM sqlite_master WHERE sql IS NOT NULL"
            )
        }
        assert oggetti[("index", "idx_proposte_prova")][0] == "proposte"
        assert oggetti[("trigger", "proposte_nuove")][0] == "proposte"
        assert oggetti[("trigger", "proposte_cambiate")][0] == "proposte"
        assert all("_proposte_v33" not in sql for _, sql in oggetti.values())
        assert "proposte" in oggetti[("trigger", "regole_tolte")][1]
        # la vista legge la tabella nuova: resta aperta solo la proposta nuova
        assert conn.execute("SELECT id FROM proposte_aperte").fetchall() == [(nuova.json()["id"],)]
        # i trigger scattano sulla tabella nuova, e non sono scattati durante la copia
        assert conn.execute("SELECT * FROM registro_prova ORDER BY rowid").fetchall() == [
            (nuova.json()["id"], "nuova"), (storia["pendente"]["id"], "ritirata"),
        ]
        assert conn.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        assert conn.execute("PRAGMA foreign_key_check").fetchall() == []
    finally:
        conn.close()


def test_una_colonna_sconosciuta_si_dice_nel_log(client, db_path, orologio, caplog):
    """Una colonna aggiunta a mano a proposte non passa nella tabella nuova, ma non
    sparisce in silenzio: lo dice il log, e resta nella copia di sicurezza."""
    from app.main import create_app

    _storia_v33(client, orologio)
    _torna_alla_v33(db_path)
    conn = sqlite3.connect(db_path)
    try:
        conn.execute("ALTER TABLE proposte ADD COLUMN nota_a_mano TEXT")
        conn.execute("UPDATE proposte SET nota_a_mano = 'scritta a mano'")
        conn.commit()
    finally:
        conn.close()
    with caplog.at_level(logging.WARNING, logger="uvicorn.error"):
        with TestClient(create_app()):
            pass
    (avviso,) = [r.getMessage() for r in caplog.records if "nota_a_mano" in r.getMessage()]
    assert "MIGRAZIONE v3.4" in avviso
    assert "nota_a_mano" not in _sql_proposte(db_path)
    (copia,) = _copie(db_path, ".prima-v3.4-")
    conn = sqlite3.connect(copia)
    try:
        assert conn.execute("SELECT DISTINCT nota_a_mano FROM proposte").fetchall() == [("scritta a mano",)]
    finally:
        conn.close()
