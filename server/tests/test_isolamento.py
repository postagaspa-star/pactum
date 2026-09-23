"""(v3) Due figli, tre dispositivi: Andrea (figlio 1) col telefono del token
d'ambiente e un computer, Marta col suo telefono.

- Fra figli: un dispositivo non legge e non tocca NIENTE dell'altro figlio
  (regole, proposte, dichiarazioni, notifiche, bonus, semaforo): 403 dove il
  contratto dice "di un altro figlio", 404 sulle notifiche altrui, 409
  regola_non_valida sul bonus (contratto, "Bonus: per dispositivo").
- Nello stesso figlio: regole, tempi, bonus e registro per dispositivo; vita
  reale, proposte, dichiarazioni, striscia per figlio.
- (v3.1) Le notifiche del figlio si leggono per dispositivo; le regole di un
  dispositivo revocato non si toccano piu' e non tengono in piedi il patto."""

import hashlib
import sqlite3
from types import SimpleNamespace

import pytest

from aiuti_v3 import dispositivo_abbinato, eventi, nuovo_figlio, regola
from conftest import FIGLIO, GENITORE

VITA = {"descrizione": "Un'ora di cammino", "arbitro_nome": "Mamma", "frequenza": "ogni giorno"}
MINECRAFT = {"app_o_categoria": "exe:minecraft.exe", "minuti_al_giorno": 60}


@pytest.fixture
def famiglia(client):
    marta = nuovo_figlio(client, "Marta")
    pc_andrea, pc_andrea_id = dispositivo_abbinato(client, 1, "Computer", "computer")
    tel_marta, tel_marta_id = dispositivo_abbinato(client, marta["id"], "Telefono di Marta", "telefono")
    return SimpleNamespace(
        andrea=1, marta=marta["id"],
        tel_andrea=FIGLIO, tel_andrea_id=1,
        pc_andrea=pc_andrea, pc_andrea_id=pc_andrea_id,
        tel_marta=tel_marta, tel_marta_id=tel_marta_id,
    )


def _patto(client, headers):
    risposta = client.get("/api/patto", headers=headers)
    assert risposta.status_code == 200, risposta.text
    return risposta.json()


def _finestra(client, figlio_id):
    risposta = client.get(f"/api/finestra?figlio_id={figlio_id}", headers=GENITORE)
    assert risposta.status_code == 200, risposta.text
    return risposta.json()


def _proponi(client, regola_id, parametri):
    risposta = client.post(
        "/api/proposte", json={"regola_id": regola_id, "parametri_proposti": parametri}, headers=GENITORE
    )
    assert risposta.status_code == 200, risposta.text
    return risposta.json()


# --- fra figli: niente passa ---

def test_regole_di_un_altro_figlio_intoccabili(client, famiglia, orologio):
    di_andrea = regola(client, famiglia.tel_andrea)
    regola(client, famiglia.tel_andrea, parametri={"app_o_categoria": "YouTube", "minuti_al_giorno": 90})
    di_marta = regola(client, famiglia.tel_marta)
    orologio.avanza(days=5)  # nessun blocco dei 4 giorni in mezzo
    stretta = {"parametri": {"app_o_categoria": "TikTok", "minuti_al_giorno": 10}}
    assert client.patch(f"/api/regole/{di_andrea['id']}", json=stretta, headers=famiglia.tel_marta).status_code == 403
    assert client.delete(f"/api/regole/{di_andrea['id']}", headers=famiglia.tel_marta).status_code == 403
    assert client.patch("/api/regole/999", json=stretta, headers=famiglia.tel_marta).status_code == 404
    # e non si scrive una regola sul dispositivo di un altro figlio
    r = client.post("/api/regole", json={"tipo": "limite_tempo", "parametri": MINECRAFT,
                                         "dispositivo_id": famiglia.pc_andrea_id}, headers=famiglia.tel_marta)
    assert r.status_code == 403
    ids_marta = [r["id"] for r in client.get("/api/regole", headers=famiglia.tel_marta).json()["regole"]]
    assert ids_marta == [di_marta["id"]]
    assert [r["id"] for r in _patto(client, famiglia.tel_marta)["regole"]] == [di_marta["id"]]
    # la regola di Andrea e' intatta
    (intatta,) = [r for r in _finestra(client, famiglia.andrea)["regole"] if r["id"] == di_andrea["id"]]
    assert intatta["attiva"] is True and intatta["parametri"]["minuti_al_giorno"] == 60


def test_proposte_di_un_altro_figlio(client, famiglia):
    di_andrea = regola(client, famiglia.tel_andrea)
    proposta = _proponi(client, di_andrea["id"], {"app_o_categoria": "TikTok", "minuti_al_giorno": 30})
    assert client.get("/api/proposte", headers=famiglia.tel_marta).json()["proposte"] == []
    assert _patto(client, famiglia.tel_marta)["proposte_pendenti"] == []
    r = client.post(f"/api/proposte/{proposta['id']}/risposta", json={"esito": "accetta"},
                    headers=famiglia.tel_marta)
    assert r.status_code == 403
    assert client.get(f"/api/proposte?figlio_id={famiglia.marta}", headers=GENITORE).json()["proposte"] == []
    (ancora,) = client.get("/api/proposte", headers=FIGLIO).json()["proposte"]
    assert ancora["stato"] == "pendente"
    # il genitore non puo' proporre su Andrea dicendo che e' per Marta
    r = client.post("/api/proposte", json={"regola_id": di_andrea["id"], "figlio_id": famiglia.marta,
                                           "parametri_proposti": {"azione": "elimina"}}, headers=GENITORE)
    assert r.status_code == 409 and r.json()["detail"] == {"errore": "regola_non_valida"}


def test_dichiarazioni_di_un_altro_figlio(client, famiglia):
    vita_andrea = regola(client, famiglia.tel_andrea, tipo="vita_reale", parametri=VITA)
    r = client.post("/api/dichiarazioni", json={"regola_id": vita_andrea["id"], "esito": "successo"},
                    headers=famiglia.tel_marta)
    assert r.status_code == 403
    dichiarata = client.post("/api/dichiarazioni", json={"regola_id": vita_andrea["id"], "esito": "successo"},
                             headers=famiglia.tel_andrea).json()
    assert client.get("/api/dichiarazioni", headers=famiglia.tel_marta).json()["dichiarazioni"] == []
    assert _patto(client, famiglia.tel_marta)["dichiarazioni_in_attesa"] == []
    per_marta = client.get(f"/api/dichiarazioni?figlio_id={famiglia.marta}", headers=GENITORE)
    assert per_marta.json()["dichiarazioni"] == []
    # un verdetto dato "per Marta" su una dichiarazione di Andrea non trova niente
    r = client.post(f"/api/dichiarazioni/{dichiarata['id']}/verdetto",
                    json={"verdetto": "conferma", "figlio_id": famiglia.marta}, headers=GENITORE)
    assert r.status_code == 404


def test_notifiche_di_un_altro_figlio(client, famiglia):
    assert client.post("/api/segno", json={"figlio_id": famiglia.andrea}, headers=GENITORE).status_code == 200
    (segno,) = [n for n in client.get("/api/notifiche", headers=FIGLIO).json()["notifiche"] if n["tipo"] == "segno"]
    assert client.get("/api/notifiche", headers=famiglia.tel_marta).json()["notifiche"] == []
    assert client.post(f"/api/notifiche/{segno['id']}/letta", headers=famiglia.tel_marta).status_code == 404
    # quelle del genitore non si leggono ne' si marcano da un dispositivo
    eventi(client, famiglia.tel_marta, {"id": "m-marta", "tipo": "manomissione", "dettagli": {"sotto_tipo": "silenzio"}})
    (della_marta,) = [n for n in client.get("/api/notifiche", headers=GENITORE).json()["notifiche"]
                      if n["figlio_id"] == famiglia.marta]
    assert client.post(f"/api/notifiche/{della_marta['id']}/letta", headers=FIGLIO).status_code == 404
    assert client.post(f"/api/notifiche/{della_marta['id']}/letta", headers=famiglia.tel_marta).status_code == 404


def test_bonus_su_regola_altrui_409(client, famiglia):
    di_andrea = regola(client, famiglia.tel_andrea)
    r = client.post("/api/bonus", json={"minuti": 15, "regola_id": di_andrea["id"]}, headers=famiglia.tel_marta)
    assert r.status_code == 409 and r.json()["detail"] == {"errore": "regola_non_valida"}
    # neanche dal computer dello stesso figlio: il bonus e' del dispositivo della regola
    r = client.post("/api/bonus", json={"minuti": 15, "regola_id": di_andrea["id"]}, headers=famiglia.pc_andrea)
    assert r.status_code == 409 and r.json()["detail"] == {"errore": "regola_non_valida"}
    assert _finestra(client, famiglia.andrea)["bonus"]["giorno"]["usati"] == 0


def test_uno_sforamento_con_la_regola_altrui_non_tinge_niente(client, famiglia):
    di_andrea = regola(client, famiglia.tel_andrea)
    oggi = "2026-07-14"
    eventi(client, famiglia.tel_andrea, {"id": "foto-a", "tipo": "uso_giornaliero",
                                         "dettagli": {"giorno": oggi, "uso_minuti": {}, "totale_minuti": 5}})
    eventi(client, famiglia.tel_marta, {"id": "finto", "tipo": "sforamento",
                                        "dettagli": {"regola_id": di_andrea["id"], "giorno": oggi}})
    finestra = _finestra(client, famiglia.andrea)
    (semaforo,) = [r["semaforo"] for r in finestra["regole"] if r["id"] == di_andrea["id"]]
    assert semaforo[-1] == {"data": oggi, "stato": "verde"}
    assert finestra["striscia"][-1]["stato"] == "verde"
    assert finestra["sforamenti_recenti"] == []  # l'evento sta nel registro di Marta
    assert [e["id"] for e in _finestra(client, famiglia.marta)["sforamenti_recenti"]] == ["finto"]


def test_il_patto_parla_solo_del_proprio_figlio(client, famiglia):
    patto = _patto(client, famiglia.tel_marta)
    assert patto["figlio"] == {"id": famiglia.marta, "nome": "Marta"}
    assert patto["dispositivo"] == {"id": famiglia.tel_marta_id, "nome": "Telefono di Marta", "tipo": "telefono"}
    assert [d["id"] for d in patto["dispositivi"]] == [famiglia.tel_marta_id]


@pytest.mark.parametrize("percorso", [
    "/api/finestra?figlio_id=999", "/api/proposte?figlio_id=999",
    "/api/dichiarazioni?figlio_id=999", "/api/regole?figlio_id=999",
])
def test_figlio_inesistente_404(client, famiglia, percorso):
    assert client.get(percorso, headers=GENITORE).status_code == 404


def test_segno_a_un_figlio_inesistente_404(client, famiglia):
    assert client.post("/api/segno", json={"figlio_id": 999}, headers=GENITORE).status_code == 404


def test_un_segno_al_giorno_per_figlio(client, famiglia):
    assert client.post("/api/segno", json={"figlio_id": famiglia.andrea}, headers=GENITORE).status_code == 200
    assert _finestra(client, famiglia.marta)["segno_oggi"] is False
    assert client.post("/api/segno", json={"figlio_id": famiglia.marta}, headers=GENITORE).status_code == 200
    r = client.post("/api/segno", json={"figlio_id": famiglia.andrea}, headers=GENITORE)
    assert r.status_code == 409 and r.json()["detail"] == {"errore": "segno_gia_mandato"}
    assert client.post("/api/segno", headers=GENITORE).status_code == 409  # senza figlio_id = Andrea
    # il segno arriva a tutti i dispositivi di Andrea
    for headers in (famiglia.tel_andrea, famiglia.pc_andrea):
        assert [n["tipo"] for n in client.get("/api/notifiche", headers=headers).json()["notifiche"]] == ["segno"]


# --- nello stesso figlio: per dispositivo e per figlio ---

def test_regola_del_computer_scritta_dal_telefono(client, famiglia):
    r = client.post("/api/regole", json={"tipo": "limite_tempo", "parametri": MINECRAFT,
                                         "dispositivo_id": famiglia.pc_andrea_id}, headers=famiglia.tel_andrea)
    assert r.status_code == 201
    assert r.json()["dispositivo"] == {"id": famiglia.pc_andrea_id, "nome": "Computer", "tipo": "computer"}
    # ma nel patto del telefono ci sono solo le regole del telefono
    assert _patto(client, famiglia.tel_andrea)["regole"] == []
    assert [x["id"] for x in _patto(client, famiglia.pc_andrea)["regole"]] == [r.json()["id"]]
    # GET /api/regole: tutte le regole del figlio, di tutti i dispositivi
    assert [x["id"] for x in client.get("/api/regole", headers=famiglia.tel_andrea).json()["regole"]] == [r.json()["id"]]


def test_proposta_sul_computer_si_accetta_dal_telefono(client, famiglia):
    del_pc = regola(client, famiglia.pc_andrea, parametri=MINECRAFT)
    proposta = _proponi(client, del_pc["id"], {"app_o_categoria": "exe:minecraft.exe", "minuti_al_giorno": 45})
    assert [p["id"] for p in _patto(client, famiglia.tel_andrea)["proposte_pendenti"]] == [proposta["id"]]
    # la notifica della proposta arriva al computer (la regola e' sua), non al telefono
    assert [n["tipo"] for n in client.get("/api/notifiche", headers=famiglia.pc_andrea).json()["notifiche"]] == ["nuova_proposta"]
    assert client.get("/api/notifiche", headers=famiglia.tel_andrea).json()["notifiche"] == []
    r = client.post(f"/api/proposte/{proposta['id']}/risposta", json={"esito": "accetta"}, headers=famiglia.tel_andrea)
    assert r.status_code == 200
    assert r.json()["regola"]["parametri"]["minuti_al_giorno"] == 45
    assert r.json()["regola"]["dispositivo_id"] == famiglia.pc_andrea_id


def _ids_notifiche(client, headers):
    risposta = client.get("/api/notifiche", headers=headers)
    assert risposta.status_code == 200, risposta.text
    return [n["id"] for n in risposta.json()["notifiche"]]


def test_marcare_come_letta_vale_solo_per_quel_dispositivo(client, famiglia):
    """(v3.1) Il segno e' di tutto il figlio: il telefono lo legge e lo marca, ma
    arriva lo stesso al computer; poi il computer lo marca per se'. Rimarcarla
    resta 200."""
    client.post("/api/segno", json={"figlio_id": famiglia.andrea}, headers=GENITORE)
    (segno,) = _ids_notifiche(client, famiglia.tel_andrea)
    assert client.post(f"/api/notifiche/{segno}/letta", headers=famiglia.tel_andrea).status_code == 200
    assert _ids_notifiche(client, famiglia.tel_andrea) == []
    assert _ids_notifiche(client, famiglia.pc_andrea) == [segno]  # il telefono l'ha letto, il computer no
    r = client.post(f"/api/notifiche/{segno}/letta", headers=famiglia.pc_andrea)
    assert r.status_code == 200 and r.json() == {"id": segno, "letta": True}
    assert _ids_notifiche(client, famiglia.pc_andrea) == []
    assert client.post(f"/api/notifiche/{segno}/letta", headers=famiglia.pc_andrea).status_code == 200
    # quella su una regola del computer resta del computer: il telefono non la vede
    # e non la marca, e marcarla sul computer non tocca niente del telefono
    del_pc = regola(client, famiglia.pc_andrea, parametri=MINECRAFT)
    proposta = _proponi(client, del_pc["id"], {"app_o_categoria": "exe:minecraft.exe", "minuti_al_giorno": 45})
    (sul_pc,) = _ids_notifiche(client, famiglia.pc_andrea)
    assert _ids_notifiche(client, famiglia.tel_andrea) == []
    assert client.post(f"/api/notifiche/{sul_pc}/letta", headers=famiglia.tel_andrea).status_code == 404
    assert _ids_notifiche(client, famiglia.pc_andrea) == [sul_pc]
    assert proposta["stato"] == "pendente"


def test_letture_per_dispositivo_isolate_fra_figli(client, famiglia):
    """(v3.1) Marta non legge, non marca e non "consuma" le notifiche di Andrea."""
    client.post("/api/segno", json={"figlio_id": famiglia.andrea}, headers=GENITORE)
    client.post("/api/segno", json={"figlio_id": famiglia.marta}, headers=GENITORE)
    (di_andrea,) = _ids_notifiche(client, famiglia.tel_andrea)
    (di_marta,) = _ids_notifiche(client, famiglia.tel_marta)
    assert di_andrea != di_marta
    assert client.post(f"/api/notifiche/{di_andrea}/letta", headers=famiglia.tel_marta).status_code == 404
    assert client.post(f"/api/notifiche/{di_marta}/letta", headers=famiglia.pc_andrea).status_code == 404
    assert _ids_notifiche(client, famiglia.tel_andrea) == [di_andrea]
    assert _ids_notifiche(client, famiglia.pc_andrea) == [di_andrea]
    assert _ids_notifiche(client, famiglia.tel_marta) == [di_marta]
    assert client.post(f"/api/notifiche/{di_marta}/letta", headers=famiglia.tel_marta).status_code == 200
    assert _ids_notifiche(client, famiglia.tel_marta) == []
    assert _ids_notifiche(client, famiglia.tel_andrea) == [di_andrea]


def test_le_notifiche_del_genitore_restano_condivise(client, famiglia, db_path):
    """(v3.1) Solo quelle del figlio si leggono per dispositivo: una notifica del
    genitore marcata da un genitore e' letta per tutti i genitori."""
    conn = sqlite3.connect(db_path)
    try:
        conn.execute(
            "INSERT INTO credenziali (ruolo, token_hash, origine, creata_ts)"
            " VALUES ('genitore', ?, 'abbinamento', '2026-07-14T10:00:00+00:00')",
            (hashlib.sha256(b"tok-secondo-genitore").hexdigest(),),
        )
        conn.commit()
    finally:
        conn.close()
    secondo = {"Authorization": "Bearer tok-secondo-genitore"}
    eventi(client, famiglia.tel_andrea, {"id": "m-1", "tipo": "manomissione", "dettagli": {"sotto_tipo": "silenzio"}})
    (notifica,) = _ids_notifiche(client, GENITORE)
    assert _ids_notifiche(client, secondo) == [notifica]
    assert client.post(f"/api/notifiche/{notifica}/letta", headers=secondo).status_code == 200
    assert _ids_notifiche(client, GENITORE) == []
    assert client.get("/api/famiglia", headers=GENITORE).json()["figli"][0]["notifiche_non_lette"] == 0


def test_vita_reale_del_figlio_da_ogni_dispositivo(client, famiglia):
    vita = client.post("/api/regole", json={"tipo": "vita_reale", "parametri": VITA,
                                            "dispositivo_id": famiglia.pc_andrea_id}, headers=famiglia.pc_andrea)
    assert vita.status_code == 201
    vita = vita.json()
    assert vita["dispositivo_id"] is None and vita["dispositivo"] is None
    for headers in (famiglia.tel_andrea, famiglia.pc_andrea):
        assert [r["id"] for r in _patto(client, headers)["regole"]] == [vita["id"]]
    r = client.post("/api/dichiarazioni", json={"regola_id": vita["id"], "esito": "successo"}, headers=famiglia.tel_andrea)
    assert r.status_code == 200
    assert [d["id"] for d in _patto(client, famiglia.pc_andrea)["dichiarazioni_in_attesa"]] == [r.json()["id"]]


def test_tetti_del_bonus_per_dispositivo(client, famiglia):
    del_telefono = regola(client, famiglia.tel_andrea)
    del_pc = regola(client, famiglia.pc_andrea, parametri=MINECRAFT)
    assert client.post("/api/bonus", json={"minuti": 30, "regola_id": del_telefono["id"]}, headers=FIGLIO).status_code == 200
    assert client.post("/api/bonus", json={"minuti": 5, "regola_id": del_telefono["id"]}, headers=FIGLIO).status_code == 409
    r = client.post("/api/bonus", json={"minuti": 30, "regola_id": del_pc["id"]}, headers=famiglia.pc_andrea)
    assert r.status_code == 200 and r.json()["residuo_giorno"] == 0
    assert _patto(client, famiglia.pc_andrea)["bonus_oggi_per_regola"] == {str(del_pc["id"]): 30}
    assert _patto(client, famiglia.tel_andrea)["bonus_oggi_per_regola"] == {str(del_telefono["id"]): 30}
    finestra = _finestra(client, famiglia.andrea)
    per_id = {d["id"]: d for d in finestra["dispositivi"]}
    assert per_id[famiglia.pc_andrea_id]["bonus"]["giorno"]["usati"] == 30
    assert per_id[famiglia.pc_andrea_id]["bonus_giornalieri"][-1]["minuti"] == 30
    assert finestra["bonus"] == per_id[1]["bonus"]  # primo livello = il telefono


def test_ultima_regola_vale_per_figlio(client, famiglia, orologio):
    del_telefono = regola(client, famiglia.tel_andrea)
    del_pc = regola(client, famiglia.pc_andrea, parametri=MINECRAFT)
    regola(client, famiglia.tel_marta)  # quelle di Marta non contano per Andrea
    orologio.avanza(days=4)
    assert client.delete(f"/api/regole/{del_telefono['id']}", headers=FIGLIO).status_code == 200
    r = client.delete(f"/api/regole/{del_pc['id']}", headers=FIGLIO)  # dal telefono, sulla regola del pc
    assert r.status_code == 409 and r.json()["detail"] == {"errore": "ultima_regola"}


def test_striscia_del_figlio_su_tutti_i_dispositivi(client, famiglia):
    oggi = "2026-07-14"
    regola(client, famiglia.tel_andrea)
    del_pc = regola(client, famiglia.pc_andrea, parametri=MINECRAFT)
    foto = {"giorno": oggi, "uso_minuti": {}, "totale_minuti": 20}
    eventi(client, famiglia.tel_andrea, {"id": "foto-tel", "tipo": "uso_giornaliero", "dettagli": foto})
    eventi(client, famiglia.pc_andrea,
           {"id": "foto-pc", "tipo": "uso_giornaliero", "dettagli": foto},
           {"id": "sfor-pc", "tipo": "sforamento", "dettagli": {"regola_id": del_pc["id"], "giorno": oggi}})
    patto_tel = _patto(client, famiglia.tel_andrea)
    assert patto_tel["striscia"][-1]["stato"] == "rosso"  # del figlio: il computer ha sforato
    assert patto_tel["striscia_dispositivo"][-1]["stato"] == "verde"  # il telefono no
    strisce = {d["id"]: d["striscia"] for d in patto_tel["dispositivi"]}
    assert strisce[famiglia.pc_andrea_id][-1]["stato"] == "rosso"
    finestra = _finestra(client, famiglia.andrea)
    assert finestra["striscia"] == patto_tel["striscia"]
    assert {d["id"]: d["striscia"] for d in finestra["dispositivi"]} == strisce
    assert _patto(client, famiglia.pc_andrea)["striscia"] == patto_tel["striscia"]
    assert finestra["riepilogo"]["giorni_fuori_regola"] == 1
    # Marta non ne sa niente
    assert _patto(client, famiglia.tel_marta)["striscia"][-1]["stato"] == "grigio"


def test_eventi_recenti_di_tutti_i_dispositivi_in_ordine(client, famiglia, orologio):
    """(v3.1) La finestra legge gli ultimi 20 per dispositivo e li mette in fila: gli
    sforamenti di telefono e computer si alternano e restano i 20 piu' recenti del
    figlio, dal piu' recente; quelli di Marta non entrano."""
    del_telefono = regola(client, famiglia.tel_andrea)
    del_pc = regola(client, famiglia.pc_andrea, parametri=MINECRAFT)
    attesi = []
    for i in range(15):
        for chi, headers, regola_id in (("tel", famiglia.tel_andrea, del_telefono["id"]),
                                        ("pc", famiglia.pc_andrea, del_pc["id"])):
            eventi(client, headers, {"id": f"{chi}-{i:02d}", "tipo": "sforamento",
                                     "dettagli": {"regola_id": regola_id}})
            attesi.append(f"{chi}-{i:02d}")
            orologio.avanza(minutes=1)
    eventi(client, famiglia.tel_marta, {"id": "marta", "tipo": "sforamento", "dettagli": {"regola_id": 1}})
    finestra = _finestra(client, famiglia.andrea)
    assert [e["id"] for e in finestra["sforamenti_recenti"]] == attesi[::-1][:20]
    assert {e["dispositivo_id"] for e in finestra["sforamenti_recenti"]} == {1, famiglia.pc_andrea_id}


def test_niente_verde_senza_i_dati_di_quel_dispositivo(client, famiglia):
    """La fotografia del telefono non fa verde una regola del computer."""
    del_pc = regola(client, famiglia.pc_andrea, parametri=MINECRAFT)
    eventi(client, famiglia.tel_andrea, {"id": "foto-tel", "tipo": "uso_giornaliero",
                                         "dettagli": {"giorno": "2026-07-14", "totale_minuti": 20}})
    (semaforo,) = [r["semaforo"] for r in _finestra(client, famiglia.andrea)["regole"] if r["id"] == del_pc["id"]]
    assert semaforo[-1]["stato"] == "grigio"


def test_regole_di_un_dispositivo_revocato_non_contano_dal_giorno_dopo(client, famiglia, orologio):
    del_pc = regola(client, famiglia.pc_andrea, parametri=MINECRAFT)
    regola(client, famiglia.tel_andrea)
    eventi(client, famiglia.pc_andrea,
           {"id": "sfor-oggi", "tipo": "sforamento", "dettagli": {"regola_id": del_pc["id"], "giorno": "2026-07-14"}})
    assert client.delete(f"/api/dispositivi/{famiglia.pc_andrea_id}", headers=GENITORE).status_code == 200
    orologio.avanza(days=1)
    eventi(client, famiglia.tel_andrea, {"id": "foto-15", "tipo": "uso_giornaliero",
                                         "dettagli": {"giorno": "2026-07-15", "totale_minuti": 10}})
    finestra = _finestra(client, famiglia.andrea)
    (semaforo,) = [r["semaforo"] for r in finestra["regole"] if r["id"] == del_pc["id"]]
    assert semaforo[-2:] == [{"data": "2026-07-14", "stato": "rosso"}, {"data": "2026-07-15", "stato": "grigio"}]
    assert [v["stato"] for v in finestra["striscia"][-2:]] == ["rosso", "verde"]
    (regola_pc,) = [r for r in finestra["regole"] if r["id"] == del_pc["id"]]
    assert regola_pc["attiva"] is True  # resta nella storia com'era


# --- (v3.1) le regole di un dispositivo revocato ---

def _revoca(client, dispositivo_id):
    assert client.delete(f"/api/dispositivi/{dispositivo_id}", headers=GENITORE).status_code == 200


def _regola_in_finestra(client, regola_id):
    (trovata,) = [r for r in _finestra(client, 1)["regole"] if r["id"] == regola_id]
    return trovata


def test_eliminare_l_ultima_regola_viva_con_un_computer_revocato(client, famiglia, orologio):
    """La regola del computer revocato non conta piu' per ultima_regola: il telefono
    non puo' eliminare la sua, che e' l'ultima che tiene in piedi il patto."""
    del_telefono = regola(client, famiglia.tel_andrea)
    regola(client, famiglia.pc_andrea, parametri=MINECRAFT)
    _revoca(client, famiglia.pc_andrea_id)
    orologio.avanza(days=5)  # niente blocco dei 4 giorni in mezzo
    r = client.delete(f"/api/regole/{del_telefono['id']}", headers=FIGLIO)
    assert r.status_code == 409 and r.json()["detail"] == {"errore": "ultima_regola"}
    assert [x["id"] for x in _patto(client, FIGLIO)["regole"]] == [del_telefono["id"]]
    # con la vita reale accanto, invece, si puo'
    regola(client, FIGLIO, tipo="vita_reale", parametri=VITA)
    assert client.delete(f"/api/regole/{del_telefono['id']}", headers=FIGLIO).status_code == 200


def test_proposta_accettata_che_elimina_l_ultima_regola_viva(client, famiglia):
    """Stessa regola per l'eliminazione concordata (verifica_non_ultima in proposte)."""
    del_telefono = regola(client, famiglia.tel_andrea)
    regola(client, famiglia.pc_andrea, parametri=MINECRAFT)
    _revoca(client, famiglia.pc_andrea_id)
    proposta = _proponi(client, del_telefono["id"], {"azione": "elimina"})
    r = client.post(f"/api/proposte/{proposta['id']}/risposta", json={"esito": "accetta"}, headers=FIGLIO)
    assert r.status_code == 409 and r.json()["detail"] == {"errore": "ultima_regola"}
    assert _regola_in_finestra(client, del_telefono["id"])["attiva"] is True


def test_le_regole_di_un_dispositivo_revocato_non_si_modificano(client, famiglia, orologio):
    regola(client, famiglia.tel_andrea)
    del_pc = regola(client, famiglia.pc_andrea, parametri=MINECRAFT)
    _revoca(client, famiglia.pc_andrea_id)
    orologio.avanza(days=5)
    revocato = {"errore": "dispositivo_revocato"}
    stretta = {"parametri": {"app_o_categoria": "exe:minecraft.exe", "minuti_al_giorno": 10}}
    r = client.patch(f"/api/regole/{del_pc['id']}", json=stretta, headers=FIGLIO)
    assert r.status_code == 409 and r.json()["detail"] == revocato
    r = client.delete(f"/api/regole/{del_pc['id']}", headers=FIGLIO)
    assert r.status_code == 409 and r.json()["detail"] == revocato
    for parametri in ({"app_o_categoria": "exe:minecraft.exe", "minuti_al_giorno": 30}, {"azione": "elimina"}):
        r = client.post("/api/proposte", json={"regola_id": del_pc["id"], "parametri_proposti": parametri},
                        headers=GENITORE)
        assert r.status_code == 409 and r.json()["detail"] == revocato
    # resta nella storia com'era: attiva, stessi parametri, niente proposte ne' storico nuovo
    intatta = _regola_in_finestra(client, del_pc["id"])
    assert intatta["attiva"] is True and intatta["parametri"] == MINECRAFT
    assert client.get("/api/proposte", headers=GENITORE).json()["proposte"] == []
    finestra = _finestra(client, 1)
    assert [s["azione"] for s in finestra["storico_modifiche"] if s["regola_id"] == del_pc["id"]] == ["creazione"]


def test_proposta_pendente_quando_il_dispositivo_viene_revocato(client, famiglia):
    """Una proposta nata prima della revoca non modifica piu' la regola: accettarla
    risponde 409 dispositivo_revocato e resta pendente; rifiutarla si puo'."""
    regola(client, famiglia.tel_andrea)
    del_pc = regola(client, famiglia.pc_andrea, parametri=MINECRAFT)
    proposta = _proponi(client, del_pc["id"], {"azione": "elimina"})
    _revoca(client, famiglia.pc_andrea_id)
    r = client.post(f"/api/proposte/{proposta['id']}/risposta", json={"esito": "accetta"}, headers=FIGLIO)
    assert r.status_code == 409 and r.json()["detail"] == {"errore": "dispositivo_revocato"}
    assert _regola_in_finestra(client, del_pc["id"])["attiva"] is True
    (ancora,) = client.get("/api/proposte", headers=FIGLIO).json()["proposte"]
    assert ancora["stato"] == "pendente"
    r = client.post(f"/api/proposte/{proposta['id']}/risposta", json={"esito": "rifiuta"}, headers=FIGLIO)
    assert r.status_code == 200 and r.json()["proposta"]["stato"] == "rifiutata"


def test_la_vita_reale_resta_modificabile_dopo_una_revoca(client, famiglia, orologio):
    vita = regola(client, famiglia.pc_andrea, tipo="vita_reale", parametri=VITA)  # scritta dal computer
    regola(client, famiglia.tel_andrea)
    _revoca(client, famiglia.pc_andrea_id)
    orologio.avanza(days=5)
    r = client.patch(f"/api/regole/{vita['id']}", json={"parametri": {**VITA, "frequenza": "a giorni alterni"}},
                     headers=FIGLIO)
    assert r.status_code == 200
    orologio.avanza(days=5)  # la modifica ha rimesso il blocco dei 4 giorni
    assert client.delete(f"/api/regole/{vita['id']}", headers=FIGLIO).status_code == 200


def test_crea_regola_e_revoca_non_si_incrociano(client, famiglia, db_path, monkeypatch):
    """(v3.1) crea_regola tiene il lock dal controllo "revocato" all'inserimento: una
    revoca che prova a entrare in mezzo (qui da un'altra connessione, mentre la route
    valida i parametri) aspetta e non passa. Senza il lock passerebbe, e la regola
    nascerebbe su un dispositivo gia' revocato."""
    from app.routes import regole as rotte_regole

    originale = rotte_regole._valida_o_422
    esiti = []

    def valida_mentre_arriva_una_revoca(*args, **kwargs):
        altra = sqlite3.connect(db_path, timeout=0.2)
        try:
            altra.execute(
                "UPDATE dispositivi SET revocato_ts = '2026-07-14T10:00:00+00:00' WHERE id = ?",
                (famiglia.pc_andrea_id,),
            )
            altra.commit()
            esiti.append("revocato in mezzo")
        except sqlite3.OperationalError:
            esiti.append("aspetta il lock")
        finally:
            altra.close()
        return originale(*args, **kwargs)

    monkeypatch.setattr(rotte_regole, "_valida_o_422", valida_mentre_arriva_una_revoca)
    r = client.post("/api/regole", json={"tipo": "limite_tempo", "parametri": MINECRAFT,
                                         "dispositivo_id": famiglia.pc_andrea_id}, headers=FIGLIO)
    assert esiti == ["aspetta il lock"]
    assert r.status_code == 201  # la regola e' nata prima della revoca
    monkeypatch.setattr(rotte_regole, "_valida_o_422", originale)
    _revoca(client, famiglia.pc_andrea_id)
    r = client.post("/api/regole", json={"tipo": "limite_tempo", "parametri": MINECRAFT,
                                         "dispositivo_id": famiglia.pc_andrea_id}, headers=FIGLIO)
    assert r.status_code == 409 and r.json()["detail"] == {"errore": "dispositivo_revocato"}


def test_primo_livello_della_finestra_passa_al_primo_non_revocato(client, famiglia):
    eventi(client, famiglia.pc_andrea, {"id": "foto-pc", "tipo": "uso_giornaliero",
                                        "dettagli": {"giorno": "2026-07-14", "totale_minuti": 77}})
    assert _finestra(client, famiglia.andrea)["uso_recente"][-1]["totale_minuti"] is None  # il telefono
    assert client.delete("/api/dispositivi/1", headers=GENITORE).status_code == 200
    finestra = _finestra(client, famiglia.andrea)
    assert finestra["uso_recente"][-1]["totale_minuti"] == 77  # ora vale il computer
    assert [d["id"] for d in finestra["dispositivi"]] == [1, famiglia.pc_andrea_id]  # revocati compresi


def test_figlio_senza_dispositivi_ha_la_finestra_vuota_ma_intera(client, famiglia):
    nuovo = nuovo_figlio(client, "Piccolo")
    finestra = _finestra(client, nuovo["id"])
    assert finestra["dispositivi"] == [] and finestra["regole"] == []
    assert finestra["stato_silenzio"] == {"ultimo_battito": None, "silente": True, "spento": False, "spento_dal": None}
    assert finestra["bonus"]["giorno"] == {"usati": 0, "tetto": 30, "residui": 30}
    assert all(v["totale_minuti"] is None for v in finestra["uso_recente"])
    assert all(v["totale_domini"] is None for v in finestra["siti_recenti"])
    assert finestra["medie"] == {"settimana": None, "mese": None}
    assert {v["stato"] for v in finestra["striscia"]} == {"grigio"}
