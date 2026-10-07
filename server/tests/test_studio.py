"""(v4.0) La Sessione Studio (contratto-api.md, "v4.0 — C. La Sessione Studio" e "E. Casi
limite").

- La configurazione: proposta dal figlio (telefono e computer, le loro liste unite dentro
  il lock), approvata o rifiutata da un genitore sulla versione vista, ritirata; le
  regole dei campi (orari_impossibili, browser_nella_lista, firme); orari dal giorno
  dopo, liste dalla partenza successiva; le versioni approvate.
- Le partenze automatiche decise dal server: lo Studio nasce all'ora della partenza (non
  della richiesta), una volta sola, solo con un telefono dalla 0.18 (altrimenti
  studio_non_partito), non oltre le 48 ore; prossime_partenze col fuso e il cambio
  dell'ora.
- I tratti del timer (idempotenza, tutele, unione degli intervalli, conta deciso una
  volta), l'avvio a mano, le chiusure (figlio, anche senza id e senza rete; genitore con
  motivo; mezzanotte `non_chiuso` che cede a una chiusura tardiva valida), Studio e
  sessioni, Studio e blocco dei lavori (rimandato), e dove si vede.

L'ora dei test parte da martedi' 14 luglio 2026, 12:00 a Roma (10:00 UTC): una
configurazione approvata allora vale da mercoledi' 15, con la partenza alle 15:00 di Roma
(13:00 UTC). Tutti i dati sono finti."""

import threading
from datetime import datetime, timedelta, timezone

import pytest

from aiuti_v3 import dispositivo_abbinato
from conftest import FIGLIO, GENITORE
from test_faccende import (  # noqa: F401  (famiglia e' una fixture)
    MAMMA,
    SCUOLA,
    _approva,
    _blocco,
    _date,
    _errore,
    _foto,
    _notifiche,
    _ok,
    famiglia,
    jpeg,
)

UTC = timezone.utc
GENITORE_1 = {"id": 1, "nome": "Genitore"}
PARTENZA = datetime(2026, 7, 15, 13, 0, tzinfo=UTC)  # mercoledi' 15:00 a Roma
CHIUDIBILE = datetime(2026, 7, 15, 14, 0, tzinfo=UTC)  # le 16:00 a Roma
MEZZANOTTE = datetime(2026, 7, 15, 22, 0, tzinfo=UTC)  # la mezzanotte di Roma tra il 15 e il 16
LISTE = {
    "telefono": {"app": [SCUOLA[0], "gruppo:apk"], "nomi": {SCUOLA[0]: "ClasseViva", "gruppo:apk": "App da APK"}},
    "computer": {"programmi": ["exe:winword.exe", "sito:classeviva.it"],
                 "nomi": {"exe:winword.exe": "Word", "sito:classeviva.it": "ClasseViva"},
                 "firme": {"exe:winword.exe": "Microsoft Corporation", "sito:classeviva.it": "non vale"}},
}
DICHIARAZIONE = "Ho fatto gli esercizi di matematica e letto storia"


def _ms(quando: datetime) -> int:
    return int(quando.timestamp() * 1000)


def _iso(quando: datetime) -> str:
    return quando.isoformat()


def _battito(client, versione, headers=FIGLIO):
    assert client.post("/api/battito", json={"versione_app": versione}, headers=headers).status_code == 200


def _studio(client, headers=FIGLIO, **query) -> dict:
    return _ok(client.get("/api/studio", headers=headers, params=query))


def _proponi(client, corpo, headers=FIGLIO):
    return client.patch("/api/studio/config", json=corpo, headers=headers)


def _rispondi(client, versione, esito="approva", headers=GENITORE, **altro):
    return client.post("/api/studio/config/risposta", json={"esito": esito, "versione": versione, **altro},
                       headers=headers)


def _tratto(tratto_id, inizio, fine, tipo="compiti", esito="finito", agganciata=True, **altro) -> dict:
    return {"id": tratto_id, "tipo": tipo, "fine": _ms(fine), "ora_agganciata": agganciata,
            "secondi": int((fine - inizio).total_seconds()), "esito": esito, **altro}


def _in_corso(tratto_id, inizio, tipo="compiti", secondi=0, **altro) -> dict:
    return {"id": tratto_id, "tipo": tipo, "inizio": _ms(inizio), "ora_agganciata": True,
            "secondi": secondi, "esito": "in_corso", **altro}


def _tratti(client, *tratti, headers=FIGLIO):
    return client.post("/api/studio/tratti", json={"tratti": list(tratti)}, headers=headers)


def _chiudi(client, studio_id, chiave="chiudi-1", dichiarazione=DICHIARAZIONE, headers=FIGLIO, **altro):
    percorso = f"/api/studio/{studio_id}/chiudi" if studio_id is not None else "/api/studio/chiudi"
    return client.post(percorso, json={"chiave": chiave, "dichiarazione": dichiarazione, **altro},
                       headers=headers)


def _chiudi_genitore(client, studio_id, motivo="visita medica", headers=GENITORE, **altro):
    return client.post(f"/api/studio/{studio_id}/chiudi", json={"motivo": motivo, **altro}, headers=headers)


def _avvia(client, chiave="avvio-1", headers=FIGLIO, **altro):
    return client.post("/api/studio/avvia", json={"chiave": chiave, **altro}, headers=headers)


def _approva_config(client, corpo, headers=FIGLIO, genitore=GENITORE) -> dict:
    proposta = _ok(_proponi(client, corpo, headers=headers))
    return _ok(_rispondi(client, proposta["versione"], headers=genitore))


@pytest.fixture
def pronto(client, famiglia, orologio):
    """Luca col telefono dalla 0.18 (il token d'ambiente) e il computer dalla 0.18, e lo
    Studio approvato martedi' alle 12:00 di Roma coi valori di partenza (lun-ven, 15:00,
    16:00, 60 minuti) e le liste: parte da mercoledi'."""
    _battito(client, "0.18.0")
    _battito(client, "0.18.0", famiglia.pc)
    _approva_config(client, LISTE)
    return famiglia


def _alla_partenza(client, orologio, minuti=0) -> dict:
    orologio.vai_a(PARTENZA + timedelta(minutes=minuti))
    studio = _studio(client)["in_corso"]
    assert studio is not None
    return studio


# --- la configurazione ---

def test_senza_configurazione_niente_studio(client, famiglia):
    assert _studio(client) == {
        "config": {"stato": "nessuna", "versione": 0, "approvata": None, "in_attesa": None, "motivazione": None},
        "in_corso": None, "prossime_partenze": [], "recenti": [],
    }
    assert _ok(client.get("/api/patto", headers=FIGLIO))["studio"] == {
        "config": _studio(client)["config"], "in_corso": None, "prossime_partenze": [],
    }
    _errore(_avvia(client), 409, "studio_non_approvato")


def test_la_prima_proposta_e_la_prima_approvazione(client, famiglia, orologio):
    _battito(client, "0.18.0")
    proposta = _ok(_proponi(client, {"telefono": LISTE["telefono"]}))
    assert (proposta["stato"], proposta["versione"], proposta["approvata"], proposta["motivazione"]) == (
        "in_attesa", 1, None, None)
    in_attesa = proposta["in_attesa"]
    # i campi che mancano prendono i valori di partenza
    assert (in_attesa["giorni"], in_attesa["inizio"], in_attesa["chiusura_minima"], in_attesa["minuti_minimi"]) == (
        ["lun", "mar", "mer", "gio", "ven"], "15:00", "16:00", 60)
    assert in_attesa["telefono"] == LISTE["telefono"]
    assert in_attesa["computer"] == {"programmi": [], "nomi": {}, "firme": {}}
    assert in_attesa["da"] == {"id": 1, "nome": "Telefono", "tipo": "telefono"}
    assert in_attesa["richiesta_ts"] == "2026-07-14T10:00:00+00:00"
    for headers in (GENITORE, famiglia.mamma):
        (avviso,) = _notifiche(client, headers, "studio_da_approvare")
        assert avviso["messaggio"] == "Luca chiede di approvare lo Studio"
        assert avviso["payload"] == {"versione": 1, "cambio": False,
                                     "da": {"id": 1, "nome": "Telefono", "tipo": "telefono"}}
        assert avviso["dispositivo_id"] is None
    # finche' non c'e' niente di approvato lo Studio non parte, ne' da solo ne' a mano
    assert _studio(client)["prossime_partenze"] == []
    _errore(_avvia(client), 409, "studio_non_approvato")
    approvata = _ok(_rispondi(client, 1, headers=famiglia.mamma))
    assert (approvata["stato"], approvata["versione"], approvata["in_attesa"]) == ("approvata", 2, None)
    assert approvata["approvata"]["orari_dal"] == "2026-07-15"  # dal giorno dopo, anche la prima volta
    assert approvata["approvata"]["decisa_da"] == MAMMA
    assert approvata["approvata"]["approvata_ts"] == "2026-07-14T10:00:00+00:00"
    (risposta,) = _notifiche(client, FIGLIO, "studio_risposta")
    assert risposta["messaggio"] == "Mamma ha approvato lo Studio (i nuovi orari valgono da domani)"
    assert risposta["payload"] == {"esito": "approva", "versione": 2, "cambio": False, "orari_dal": "2026-07-15",
                                   "genitore": MAMMA}
    for headers in (GENITORE, famiglia.mamma):  # l'avviso aperto si chiude
        assert _notifiche(client, headers, "studio_da_approvare") == []
    partenze = _studio(client)["prossime_partenze"]
    assert partenze[0] == {"giorno": "2026-07-15", "inizio_ts": "2026-07-15T13:00:00+00:00",
                           "chiudibile_dal": "2026-07-15T14:00:00+00:00", "minuti_minimi": 60}
    (versione,) = _ok(client.get("/api/studio/versioni", headers=GENITORE))["versioni"]
    assert (versione["versione"], versione["orari_dal"], versione["decisa_da"]) == (2, "2026-07-15", MAMMA)


def test_telefono_e_computer_propongono_le_loro_liste(client, pronto, orologio):
    """I campi che mancano si prendono dalla proposta in attesa: il telefono propone la
    sua lista, il computer la sua, e i due cambi non si cancellano a vicenda."""
    telefono = {"app": [SCUOLA[1]], "nomi": {SCUOLA[1]: "Classroom"}}
    computer = {"programmi": ["exe:excel.exe"], "nomi": {"exe:excel.exe": "Excel"}}
    primo = _ok(_proponi(client, {"telefono": telefono}))
    secondo = _ok(_proponi(client, {"computer": computer, "minuti_minimi": 45}, headers=pronto.pc))
    assert secondo["versione"] == primo["versione"] + 1
    assert secondo["in_attesa"]["telefono"] == telefono
    assert secondo["in_attesa"]["computer"] == {**computer, "firme": {}}
    assert secondo["in_attesa"]["minuti_minimi"] == 45
    assert secondo["in_attesa"]["da"]["tipo"] == "computer"
    assert secondo["approvata"]["telefono"] == LISTE["telefono"]  # quella approvata resta
    # un solo avviso aperto: l'ultimo
    (avviso,) = _notifiche(client, GENITORE, "studio_da_approvare")
    assert avviso["messaggio"] == "Luca chiede di cambiare lo Studio"
    assert avviso["payload"]["cambio"] is True and avviso["payload"]["versione"] == secondo["versione"]
    # un PATCH che riporta esattamente all'approvata ritira il cambio
    ritiro = _ok(_proponi(client, {"telefono": LISTE["telefono"], "computer": LISTE["computer"],
                                   "minuti_minimi": 60}))
    assert (ritiro["in_attesa"], ritiro["versione"]) == (None, secondo["versione"] + 1)
    assert _notifiche(client, GENITORE, "studio_da_approvare") == []
    _errore(client.delete("/api/studio/config/proposta", headers=FIGLIO), 409, "niente_da_ritirare")
    # e il ritiro esplicito
    _ok(_proponi(client, {"inizio": "15:30"}))
    ritirata = _ok(client.delete("/api/studio/config/proposta", headers=pronto.pc))
    assert ritirata["in_attesa"] is None and ritirata["stato"] == "approvata"
    assert _notifiche(client, GENITORE, "studio_da_approvare") == []


def test_due_proposte_insieme_non_si_cancellano(client, pronto):
    for giro in range(4):
        barriera = threading.Barrier(2)
        esiti = []

        def proponi(corpo, headers):
            barriera.wait()
            esiti.append(_proponi(client, corpo, headers=headers))

        fili = [
            threading.Thread(target=proponi, args=({"telefono": {"app": [f"com.esempio.app{giro}"]}}, FIGLIO)),
            threading.Thread(target=proponi, args=({"computer": {"programmi": [f"exe:prog{giro}.exe"]}}, pronto.pc)),
        ]
        for f in fili:
            f.start()
        for f in fili:
            f.join()
        assert [r.status_code for r in esiti] == [200, 200]
        in_attesa = _studio(client, GENITORE)["config"]["in_attesa"]
        assert in_attesa["telefono"]["app"] == [f"com.esempio.app{giro}"]
        assert in_attesa["computer"]["programmi"] == [f"exe:prog{giro}.exe"]


def test_la_risposta_del_genitore(client, pronto):
    _errore(_rispondi(client, 5), 409, "niente_da_decidere")
    proposta = _ok(_proponi(client, {"inizio": "15:30"}))
    for corpo in ({"esito": "approva"}, {"esito": "approva", "versione": True},
                  {"esito": "approva", "versione": "3"}, {"esito": "forse", "versione": 3}):
        assert client.post("/api/studio/config/risposta", json=corpo, headers=GENITORE).status_code == 422, corpo
    r = _rispondi(client, proposta["versione"], figlio_id=99)
    assert r.status_code == 404
    assert _rispondi(client, proposta["versione"], headers=FIGLIO).status_code == 403
    cambiata = _rispondi(client, proposta["versione"] - 1)
    assert cambiata.status_code == 409
    assert cambiata.json()["detail"]["errore"] == "richiesta_cambiata"
    assert cambiata.json()["detail"]["config"]["in_attesa"]["inizio"] == "15:30"
    # Sara non ha niente in attesa
    _errore(_rispondi(client, proposta["versione"], figlio_id=pronto.sara), 409, "niente_da_decidere")
    rifiuto = _ok(_rispondi(client, proposta["versione"], esito="rifiuta", motivazione="alle 15 va bene"))
    assert (rifiuto["stato"], rifiuto["in_attesa"], rifiuto["motivazione"]) == ("approvata", None, "alle 15 va bene")
    assert rifiuto["approvata"]["inizio"] == "15:00"
    (risposta,) = [n for n in _notifiche(client, FIGLIO, "studio_risposta") if n["payload"]["esito"] == "rifiuta"]
    assert risposta["messaggio"] == "Genitore non ha approvato lo Studio"
    # una nuova proposta toglie la motivazione solo se non c'e' niente di approvato
    assert _ok(_proponi(client, {"inizio": "15:15"}))["motivazione"] == "alle 15 va bene"


def test_la_prima_proposta_rifiutata(client, famiglia):
    proposta = _ok(_proponi(client, {"minuti_minimi": 30}))
    rifiutata = _ok(_rispondi(client, proposta["versione"], esito="rifiuta", motivazione="almeno un'ora"))
    assert (rifiutata["stato"], rifiutata["approvata"], rifiutata["motivazione"]) == ("rifiutata", None, "almeno un'ora")
    di_nuovo = _ok(_proponi(client, {"minuti_minimi": 60}))
    assert (di_nuovo["stato"], di_nuovo["motivazione"]) == ("in_attesa", None)
    ritirata = _ok(client.delete("/api/studio/config/proposta", headers=FIGLIO))
    # (correzione) ritirata dopo un rifiuto: torna come prima della proposta, `rifiutata`
    # («nessuna» vuol dire «mai proposta», e una proposta c'e' stata)
    assert (ritirata["stato"], ritirata["in_attesa"]) == ("rifiutata", None)


def test_la_prima_proposta_ritirata_torna_nessuna(client, famiglia):
    _ok(_proponi(client, {"minuti_minimi": 30}))
    ritirata = _ok(client.delete("/api/studio/config/proposta", headers=FIGLIO))
    assert (ritirata["stato"], ritirata["approvata"], ritirata["in_attesa"]) == ("nessuna", None, None)


def test_le_regole_dei_campi(client, famiglia):
    sbagliati = [
        {}, {"giorni": None}, {"giorni": []}, {"giorni": ["lunedi"]}, {"inizio": "25:00"}, {"inizio": "9:00"},
        {"chiusura_minima": "15:00", "inizio": "15:30"},  # la chiusura prima dell'inizio
        {"minuti_minimi": 9}, {"minuti_minimi": 601}, {"minuti_minimi": True}, {"minuti_minimi": "60"},
        {"minuti_minimi": 60.0},
        {"telefono": {"app": ["exe:winword.exe"]}}, {"telefono": {"app": ["totale"]}},
        {"telefono": {"app": ["categoria:giochi"]}}, {"telefono": {"app": ["sito:classeviva.it"]}},
        {"telefono": {"app": [f"com.esempio.app{i}" for i in range(201)]}},
        {"computer": {"programmi": ["categoria:giochi"]}}, {"computer": {"programmi": ["totale"]}},
        {"computer": {"programmi": ["com.esempio.app"]}}, {"computer": {"programmi": ["exe:Word.exe"]}},
        {"computer": {"programmi": ["exe:mio programma.exe"]}}, {"computer": {"programmi": ["sito:"]}},
        {"computer": {"programmi": ["exe:winword.exe"], "firme": {"exe:winword.exe": "x" * 201}}},
        {"computer": {"programmi": ["exe:winword.exe"], "firme": {"exe:winword.exe": "  "}}},
    ]
    for corpo in sbagliati:
        assert _proponi(client, corpo).status_code == 422, corpo
    for browser in ("chrome.exe", "msedge.exe", "firefox.exe", "opera_gx.exe", "tor.exe", "vivaldi.exe"):
        _errore(_proponi(client, {"computer": {"programmi": ["sito:classeviva.it", f"exe:{browser}"]}}),
                422, "browser_nella_lista")
    # Studio chiudibile in giornata: max(chiusura, inizio + minimo) entro le 23:30
    for corpo in ({"inizio": "23:00", "chiusura_minima": "23:00", "minuti_minimi": 120},
                  {"inizio": "22:40", "chiusura_minima": "22:40"},  # 22:40 + 60 = 23:40
                  {"inizio": "20:00", "chiusura_minima": "23:31"}):
        _errore(_proponi(client, corpo), 422, "orari_impossibili")
    assert _studio(client)["config"]["stato"] == "nessuna"  # niente e' stato scritto
    giusta = _ok(_proponi(client, {
        "giorni": ["ven", "lun", "lun", "dom"], "inizio": "22:30", "chiusura_minima": "23:30", "minuti_minimi": 60,
        "telefono": {"app": ["gruppo:apk", SCUOLA[0], SCUOLA[0]], "nomi": {"com.altra.app": "Altra", SCUOLA[0]: " ClasseViva "}},
        "computer": {"programmi": ["exe:winword.exe", "sito:classeviva.it", "exe:winword.exe"],
                     "nomi": {"sito:classeviva.it": "Una scuola"},
                     "firme": {"exe:winword.exe": "Microsoft Corporation", "sito:classeviva.it": "x",
                               "exe:altro.exe": "y"}},
    }))["in_attesa"]
    assert giusta["giorni"] == ["lun", "ven", "dom"]  # senza doppioni, in ordine
    assert giusta["telefono"] == {"app": ["gruppo:apk", SCUOLA[0]], "nomi": {SCUOLA[0]: "ClasseViva"}}
    # per sito: l'etichetta e' il dominio; le firme solo per le voci exe: della lista
    assert giusta["computer"] == {"programmi": ["exe:winword.exe", "sito:classeviva.it"],
                                  "nomi": {"sito:classeviva.it": "classeviva.it"},
                                  "firme": {"exe:winword.exe": "Microsoft Corporation"}}
    # le liste vuote vanno bene: "solo le app sempre usabili"
    assert _ok(_proponi(client, {"telefono": {"app": []}, "computer": {"programmi": []}}))["in_attesa"]["telefono"] == {
        "app": [], "nomi": {}}


def test_le_etichette_che_si_leggono_sono_quelle_dell_uso(client, famiglia):
    """Un'etichetta scritta apposta ("ClasseViva" su TikTok) non inganna il genitore."""
    tiktok = "com.zhiliaoapp.musically"
    assert client.post("/api/eventi", json={"eventi": [{
        "id": "uso-1", "tipo": "uso_giornaliero",
        "dettagli": {"giorno": "2026-07-14", "uso_minuti": {tiktok: 5}, "nomi": {tiktok: "TikTok"}, "totale_minuti": 5},
    }]}, headers=FIGLIO).status_code == 200
    proposta = _ok(_proponi(client, {"telefono": {"app": [tiktok], "nomi": {tiktok: "ClasseViva"}}}))
    assert proposta["in_attesa"]["telefono"]["nomi"] == {tiktok: "TikTok"}


def test_orari_dal_giorno_dopo_liste_dalla_partenza_dopo(client, pronto, orologio):
    """Un cambio delle sole liste approvato durante lo Studio: lo Studio in corso tiene le
    sue, la partenza dopo prende le nuove, e gli orari non cambiano giorno. Un cambio
    degli orari vale dal giorno dopo."""
    in_corso = _alla_partenza(client, orologio, 10)
    assert in_corso["liste"]["telefono"]["app"] == LISTE["telefono"]["app"]
    config = _approva_config(client, {"telefono": {"app": [SCUOLA[1]]}})
    assert config["approvata"]["orari_dal"] == "2026-07-15"  # le liste non spostano gli orari
    (risposta,) = [n for n in _notifiche(client, FIGLIO, "studio_risposta") if n["payload"]["cambio"]]
    assert risposta["messaggio"] == "Genitore ha approvato lo Studio (la nuova lista vale dal prossimo Studio)"
    assert _studio(client)["in_corso"]["liste"]["telefono"]["app"] == LISTE["telefono"]["app"]
    config = _approva_config(client, {"inizio": "16:00", "chiusura_minima": "17:00"})
    assert config["approvata"]["orari_dal"] == "2026-07-16"
    assert _notifiche(client, FIGLIO, "studio_risposta")[-1]["messaggio"] == (
        "Genitore ha approvato lo Studio (i nuovi orari valgono da domani)")
    partenze = _studio(client)["prossime_partenze"]
    assert [(p["giorno"], p["inizio_ts"]) for p in partenze[:2]] == [
        ("2026-07-15", "2026-07-15T13:00:00+00:00"), ("2026-07-16", "2026-07-16T14:00:00+00:00")]
    # il giorno dopo: nuove liste e nuovi orari
    assert _ok(_chiudi_genitore(client, in_corso["id"]))["chiusura"] == "genitore"
    orologio.vai_a(datetime(2026, 7, 16, 14, 0, tzinfo=UTC))
    domani = _studio(client)["in_corso"]
    assert (domani["inizio_ts"], domani["liste"]["telefono"]["app"]) == ("2026-07-16T14:00:00+00:00", [SCUOLA[1]])
    assert domani["chiudibile_dal"] == "2026-07-16T15:00:00+00:00"
    versioni = _ok(client.get("/api/studio/versioni", headers=FIGLIO))["versioni"]
    assert [v["versione"] for v in versioni] == sorted((v["versione"] for v in versioni), reverse=True)
    assert len(versioni) == 3 and versioni[-1]["telefono"]["app"] == LISTE["telefono"]["app"]


# --- le partenze automatiche ---

def test_lo_studio_parte_da_solo_alla_partenza(client, pronto, orologio):
    orologio.vai_a(PARTENZA - timedelta(minutes=1))
    assert _studio(client)["in_corso"] is None
    orologio.vai_a(PARTENZA)
    patto = _ok(client.get("/api/patto", headers=FIGLIO))
    studio = patto["studio"]["in_corso"]
    assert (studio["origine"], studio["giorno"], studio["inizio_ts"], studio["chiave"], studio["avviato_da"]) == (
        "automatica", "2026-07-15", _iso(PARTENZA), None, None)
    assert studio["partenze"] == [{"giorno": "2026-07-15", "inizio_ts": _iso(PARTENZA),
                                   "chiudibile_dal": _iso(CHIUDIBILE), "minuti_minimi": 60}]
    assert (studio["conta_dal"], studio["chiudibile_dal"], studio["minuti_minimi"], studio["minuti_attivita"],
            studio["chiudibile"], studio["in_corso"]) == (_iso(PARTENZA), _iso(CHIUDIBILE), 60, 0, False, True)
    assert studio["liste"]["computer"]["firme"] == {"exe:winword.exe": "Microsoft Corporation"}
    assert patto["blocco"]["studio"] == {"in_corso": True, "id": studio["id"], "inizio_ts": _iso(PARTENZA)}
    for headers in (GENITORE, pronto.mamma):
        (avviso,) = _notifiche(client, headers, "studio_iniziato")
        assert avviso["messaggio"] == "Luca è in Studio dalle 15:00"
        assert avviso["payload"] == {"studio_id": studio["id"], "origine": "automatica", "inizio_ts": _iso(PARTENZA)}
    # dal computer, nel blocco, nella finestra e nella famiglia lo stesso Studio
    assert _blocco(client, pronto.pc)["studio"]["id"] == studio["id"]
    assert _ok(client.get("/api/patto", headers=pronto.pc))["studio"]["in_corso"]["id"] == studio["id"]
    finestra = _ok(client.get("/api/finestra", headers=GENITORE))
    assert finestra["studio"]["in_corso"]["id"] == studio["id"]
    assert [s["id"] for s in finestra["studio_svolte"]] == [studio["id"]]
    luca = _ok(client.get("/api/famiglia", headers=GENITORE))["figli"][0]
    assert (luca["studio_in_corso"], luca["studio_da_approvare"]) == (True, 0)
    # Sara non ne sa niente
    assert _studio(client, pronto.tel_sara)["in_corso"] is None


def test_creato_dopo_con_l_inizio_della_partenza(client, pronto, orologio):
    """Telefono spento alle 15:00: una richiesta qualsiasi (qui la famiglia del genitore,
    alle 15:40) crea lo Studio, con l'inizio alle 15:00."""
    orologio.vai_a(PARTENZA + timedelta(minutes=40))
    assert _ok(client.get("/api/famiglia", headers=GENITORE))["figli"][0]["studio_in_corso"] is True
    assert _studio(client)["in_corso"]["inizio_ts"] == _iso(PARTENZA)
    # una volta sola: le richieste dopo non ne creano altri
    for headers in (FIGLIO, pronto.pc):
        _ok(client.get("/api/faccende/blocco", headers=headers))
    assert len(_studio(client)["recenti"]) == 1
    assert len(_notifiche(client, GENITORE, "studio_iniziato")) == 1


def test_mai_due_studi_anche_insieme(client, pronto, orologio):
    orologio.vai_a(PARTENZA + timedelta(minutes=1))
    barriera = threading.Barrier(6)
    esiti = []

    def leggi(percorso, headers):
        barriera.wait()
        esiti.append(client.get(percorso, headers=headers).status_code)

    fili = [threading.Thread(target=leggi, args=(p, h)) for p, h in (
        ("/api/patto", FIGLIO), ("/api/faccende/blocco", pronto.pc), ("/api/studio", FIGLIO),
        ("/api/famiglia", GENITORE), ("/api/finestra", pronto.mamma), ("/api/patto", pronto.pc))]
    for f in fili:
        f.start()
    for f in fili:
        f.join()
    assert esiti == [200] * 6
    assert len(_studio(client)["recenti"]) == 1
    assert len(_notifiche(client, GENITORE, "studio_iniziato")) == 1


def test_senza_un_telefono_018_lo_studio_non_parte(client, famiglia, orologio):
    _battito(client, "0.18.0")
    _approva_config(client, {"giorni": ["lun", "mar", "mer", "gio", "ven"]})
    _battito(client, "0.17.2")  # il telefono torna alla 0.17
    _battito(client, "0.18.0", famiglia.pc)  # un computer 0.18 non basta
    assert _studio(client)["prossime_partenze"] == []
    assert _ok(client.get("/api/patto", headers=famiglia.pc))["studio"]["prossime_partenze"] == []
    orologio.vai_a(PARTENZA + timedelta(minutes=5))
    assert _studio(client, GENITORE)["in_corso"] is None and _studio(client, GENITORE)["recenti"] == []
    (avviso,) = _notifiche(client, GENITORE, "studio_non_partito")
    assert avviso["messaggio"] == "Lo Studio di Luca non è partito: il telefono non è aggiornato alla 0.18"
    assert avviso["payload"] == {"giorno": "2026-07-15", "inizio_ts": _iso(PARTENZA)}
    # aggiornato dopo: lo Studio di oggi non nasce piu'
    orologio.avanza(minutes=10)
    _battito(client, "0.18.0")
    assert _studio(client)["in_corso"] is None
    _battito(client, "0.17.2")
    # il giorno dopo un'altra partenza saltata: un solo avviso aperto
    orologio.vai_a(PARTENZA + timedelta(days=1))
    _ok(client.get("/api/famiglia", headers=GENITORE))
    (avviso,) = _notifiche(client, GENITORE, "studio_non_partito")
    assert avviso["payload"]["giorno"] == "2026-07-16"


def test_dopo_48_ore_niente_e_oltre_mezzanotte_nasce_chiuso(client, pronto, orologio):
    """Server fermo da mercoledi' mattina a venerdi' 16:00 di Roma: la partenza di
    mercoledi' e' piu' vecchia di 48 ore e non crea niente; quella di giovedi' e' dentro
    le 48 ore ma oltre la sua mezzanotte: nasce e si chiude `non_chiuso` (solo
    studio_non_chiuso); quella di venerdi' nasce aperta."""
    orologio.vai_a(datetime(2026, 7, 17, 14, 0, 30, tzinfo=UTC))
    _ok(client.get("/api/famiglia", headers=GENITORE))
    recenti = _studio(client)["recenti"]
    assert [(s["giorno"], s["chiusura"]) for s in recenti] == [("2026-07-17", None), ("2026-07-16", "non_chiuso")]
    assert recenti[1]["fine_ts"] == "2026-07-16T22:00:00+00:00" and recenti[1]["minuti_alla_chiusura"] == 0
    (non_chiuso,) = _notifiche(client, GENITORE, "studio_non_chiuso")
    assert non_chiuso["payload"] == {"studio_id": recenti[1]["id"], "fine_ts": "2026-07-16T22:00:00+00:00",
                                     "minuti_attivita": 0}
    assert non_chiuso["messaggio"] == "Lo Studio di Luca non è stato chiuso: 0 min di attività"
    assert [n["payload"]["studio_id"] for n in _notifiche(client, GENITORE, "studio_iniziato")] == [recenti[0]["id"]]
    # la partenza di mercoledi' non c'e', nemmeno con una chiusura senza id
    r = _chiudi(client, None, studio={"giorno": "2026-07-15"})
    assert r.status_code == 404 and r.json() == {"detail": "studio non trovato"}


def test_prossime_partenze_col_fuso_e_il_cambio_dell_ora(client, famiglia, orologio):
    orologio.vai_a(datetime(2026, 10, 19, 10, 0, tzinfo=UTC))  # lunedi' 19 ottobre
    _battito(client, "0.18.0")
    _approva_config(client, {"giorni": ["lun", "mar", "mer", "gio", "ven", "sab", "dom"], "inizio": "02:30",
                             "chiusura_minima": "03:30"})
    partenze = {p["giorno"]: p for p in _studio(client)["prossime_partenze"]}
    assert min(partenze) == "2026-10-20" and max(partenze) == "2026-11-02"  # da domani; oggi + 14 giorni
    assert partenze["2026-10-24"]["inizio_ts"] == "2026-10-24T00:30:00+00:00"  # ora legale: +02
    # domenica 25: le 02:30 esistono due volte, vale la prima (ancora +02)
    assert partenze["2026-10-25"]["inizio_ts"] == "2026-10-25T00:30:00+00:00"
    assert partenze["2026-10-25"]["chiudibile_dal"] == "2026-10-25T02:30:00+00:00"  # le 03:30 di +01
    assert partenze["2026-10-26"]["inizio_ts"] == "2026-10-26T01:30:00+00:00"  # ora solare: +01


def test_un_ora_che_non_esiste_vale_la_prima_ora_valida_dopo():
    from app import studio

    # domenica 28 marzo 2027: a Roma dalle 02:00 si passa alle 03:00
    assert studio.istante_locale(datetime(2027, 3, 28).date(), "02:30") == datetime(2027, 3, 28, 1, 0, tzinfo=UTC)
    assert studio.istante_locale(datetime(2027, 3, 28).date(), "03:00") == datetime(2027, 3, 28, 1, 0, tzinfo=UTC)
    assert studio.istante_locale(datetime(2027, 3, 28).date(), "15:00") == datetime(2027, 3, 28, 13, 0, tzinfo=UTC)
    assert studio.mezzanotte_dopo(datetime(2027, 3, 27).date()) == datetime(2027, 3, 27, 23, 0, tzinfo=UTC)


# --- i tratti ---

def test_i_tratti_e_i_minuti_di_attivita(client, pronto, orologio):
    studio = _alla_partenza(client, orologio, 70)
    adesso = PARTENZA + timedelta(minutes=70)
    esito = _ok(_tratti(client,
                        _tratto("t1", PARTENZA, PARTENZA + timedelta(minutes=30), parola="matematica"),
                        _tratto("t2", PARTENZA + timedelta(minutes=20), PARTENZA + timedelta(minutes=60),
                                tipo="altro", parola="allenamento", esito="interrotto"),
                        _in_corso("t3", adesso - timedelta(minutes=5), tipo="lavori_di_casa", secondi=300)))
    assert (esito["ricevuti"], esito["nuovi"], esito["aggiornati"], esito["ignorati"]) == (3, 3, 0, 0)
    per_id = {t["id"]: t for t in esito["tratti"]}
    assert per_id["t1"] == {"id": "t1", "dispositivo_id": 1, "tipo": "compiti", "parola": "matematica",
                            "faccenda_id": None, "inizio": _ms(PARTENZA), "fine": _ms(PARTENZA + timedelta(minutes=30)),
                            "ora_agganciata": True, "secondi": 1800, "secondi_contati": 1800, "minuti": 30,
                            "esito": "finito", "conta": True}
    assert (per_id["t3"]["esito"], per_id["t3"]["conta"], per_id["t3"]["fine"]) == ("in_corso", False, None)
    # l'unione: 15:00-15:30 e 15:20-16:00 fanno 60 minuti, non 70
    vista = _studio(client)["in_corso"]
    assert (vista["minuti_attivita"], vista["chiudibile"]) == (60, True)
    # di nuovo lo stesso: niente cambia; il tratto in corso passa una volta a finito
    di_nuovo = _ok(_tratti(client, _tratto("t1", PARTENZA, PARTENZA + timedelta(minutes=30), parola="matematica"),
                           _tratto("t3", adesso - timedelta(minutes=5), adesso, tipo="lavori_di_casa")))
    assert (di_nuovo["nuovi"], di_nuovo["aggiornati"], di_nuovo["ignorati"]) == (0, 1, 1)
    # un esito diverso su un tratto gia' finito si ignora
    assert _ok(_tratti(client, _tratto("t3", adesso - timedelta(minutes=5), adesso, tipo="lavori_di_casa",
                                       esito="interrotto")))["ignorati"] == 1
    assert _studio(client)["in_corso"]["minuti_attivita"] == 65
    assert [t["id"] for t in _studio(client)["in_corso"]["tratti"]] == ["t1", "t2", "t3"]


def test_i_tratti_solo_dal_telefono_e_le_regole(client, pronto, orologio):
    _alla_partenza(client, orologio, 30)
    fine = PARTENZA + timedelta(minutes=20)
    _errore(_tratti(client, _tratto("x", PARTENZA, fine), headers=pronto.pc), 422, "solo_dal_telefono")
    sbagliati = [
        _tratto("a", PARTENZA, fine, tipo="altro"),  # altro vuole la parola
        _tratto("b", PARTENZA, fine, parola="x" * 31),
        {**_tratto("d", PARTENZA, fine), "fine": None},
        {**_tratto("e", PARTENZA, fine), "secondi": 0},
        {**_tratto("f", PARTENZA, fine), "ora_agganciata": "si"},
        {**_tratto("g", PARTENZA, fine), "tipo": "gioco"},
        {**_in_corso("h", PARTENZA), "inizio": None},
        {**_in_corso("i", PARTENZA), "fine": _ms(fine)},
    ]
    for tratto in sbagliati:
        assert _tratti(client, tratto).status_code == 422, tratto
    assert client.post("/api/studio/tratti", json={"tratti": []}, headers=FIGLIO).status_code == 422
    troppi = [_tratto(f"n{i}", PARTENZA, fine) for i in range(51)]
    assert _tratti(client, *troppi).status_code == 422
    # un lavoro che non e' del figlio vale null
    (di_sara,) = _date(client, "Camera", figlio_id=pronto.sara)
    (mio,) = _date(client, "Letto", blocco_da="2026-07-16T10:00:00+00:00")
    esito = _ok(_tratti(client, _tratto("l1", PARTENZA, fine, tipo="lavori_di_casa", faccenda_id=di_sara["id"]),
                        _tratto("l2", fine, fine + timedelta(minutes=5), tipo="lavori_di_casa", faccenda_id=mio["id"])))
    assert [t["faccenda_id"] for t in esito["tratti"]] == [None, mio["id"]]
    # (correzione) faccenda_id su un tratto che non e' lavori_di_casa: vale null, e il
    # pacco passa (un 422 bloccherebbe la coda del telefono)
    esito = _ok(_tratti(client, _tratto("c", fine, fine + timedelta(minutes=1), faccenda_id=mio["id"])))
    assert [(t["id"], t["tipo"], t["faccenda_id"]) for t in esito["tratti"]] == [("c", "compiti", None)]


def test_le_tutele_delle_ore_dei_tratti(client, pronto, orologio):
    studio = _alla_partenza(client, orologio, 60)
    adesso = PARTENZA + timedelta(minutes=60)
    esito = _ok(_tratti(client,
                        # la fine entro 2 minuti dall'arrivo vale l'arrivo
                        _tratto("vicino", adesso - timedelta(minutes=21), adesso + timedelta(minutes=1)),
                        # nel futuro: resta nel registro e non conta
                        _tratto("futuro", adesso, adesso + timedelta(minutes=10)),
                        # piu' vecchio di 48 ore
                        _tratto("vecchio", adesso - timedelta(hours=49), adesso - timedelta(hours=48, minutes=30))))
    per_id = {t["id"]: t for t in esito["tratti"]}
    assert per_id["vicino"]["fine"] == _ms(adesso) and per_id["vicino"]["conta"] is True
    assert per_id["futuro"]["conta"] is False and per_id["vecchio"]["conta"] is False
    assert _studio(client)["in_corso"]["minuti_attivita"] == 22
    assert studio["id"] == _studio(client)["in_corso"]["id"]


def test_due_telefoni_la_parte_comune_conta_una_volta(client, pronto, orologio):
    tablet, _ = dispositivo_abbinato(client, 1, "Tablet", "telefono")
    _battito(client, "0.18.0", tablet)
    _alla_partenza(client, orologio, 90)
    ora_1 = _tratto("tel", PARTENZA, PARTENZA + timedelta(minutes=50))
    assert _ok(_tratti(client, ora_1))["tratti"][0]["conta"] is True
    # tutto dentro un tratto gia' contato: non aggiunge niente e non conta
    dentro = _ok(_tratti(client, _tratto("tab1", PARTENZA + timedelta(minutes=10), PARTENZA + timedelta(minutes=40)),
                         headers=tablet))["tratti"][0]
    assert (dentro["conta"], dentro["secondi_contati"]) == (False, 1800)
    # in parte fuori: conta, e il totale e' l'unione
    fuori = _ok(_tratti(client, _tratto("tab2", PARTENZA + timedelta(minutes=40), PARTENZA + timedelta(minutes=70)),
                        headers=tablet))["tratti"][0]
    assert fuori["conta"] is True
    assert _studio(client)["in_corso"]["minuti_attivita"] == 70


def test_un_tratto_senza_l_ora_agganciata_non_si_unisce(client, pronto, orologio):
    """Dopo un riavvio senza rete l'orologio del telefono puo' essere indietro: due tratti
    sembrano sovrapposti, ma il figlio non perde il suo tempo."""
    _alla_partenza(client, orologio, 90)
    _ok(_tratti(client, _tratto("a", PARTENZA, PARTENZA + timedelta(minutes=40)),
                _tratto("b", PARTENZA + timedelta(minutes=20), PARTENZA + timedelta(minutes=50), agganciata=False)))
    assert _studio(client)["in_corso"]["minuti_attivita"] == 70


# --- la chiusura del figlio ---

def test_la_chiusura_del_figlio_e_le_sue_condizioni(client, pronto, orologio):
    studio = _alla_partenza(client, orologio, 50)
    _ok(_tratti(client, _tratto("t1", PARTENZA, PARTENZA + timedelta(minutes=45), parola="matematica")))
    presto = _chiudi(client, studio["id"])
    assert presto.status_code == 409
    assert presto.json()["detail"]["errore"] == "troppo_presto"
    assert presto.json()["detail"]["chiudibile_dal"] == _iso(CHIUDIBILE)
    assert presto.json()["detail"]["studio"]["id"] == studio["id"]
    orologio.vai_a(CHIUDIBILE + timedelta(minutes=10))
    poco = _chiudi(client, studio["id"])
    assert poco.status_code == 409
    assert {k: v for k, v in poco.json()["detail"].items() if k != "studio"} == {
        "errore": "attivita_insufficiente", "minuti": 45, "minimi": 60}
    # la dichiarazione: da 10 a 1000 caratteri; dal computer no; la chiave serve
    for dichiarazione in ("corta", "  " + "x" * 9 + "  ", "x" * 1001, "riga\tcon tab qui"):
        assert _chiudi(client, studio["id"], dichiarazione=dichiarazione).status_code == 422
    assert client.post(f"/api/studio/{studio['id']}/chiudi", json={"dichiarazione": DICHIARAZIONE},
                       headers=FIGLIO).status_code == 422
    _errore(_chiudi(client, studio["id"], headers=pronto.pc), 422, "solo_dal_telefono")
    # i tratti nel corpo si registrano prima dei controlli: qui bastano
    chiuso = _ok(_chiudi(client, studio["id"], tratti=[
        _tratto("t2", PARTENZA + timedelta(minutes=50), CHIUDIBILE + timedelta(minutes=10), tipo="altro",
                parola="allenamento")]))
    fine = _iso(CHIUDIBILE + timedelta(minutes=10))
    assert (chiuso["chiusura"], chiuso["fine_ts"], chiuso["dichiarazione"], chiuso["dichiarazione_ts"],
            chiuso["minuti_alla_chiusura"], chiuso["in_corso"], chiuso["chiudibile"]) == (
        "figlio", fine, DICHIARAZIONE, fine, 65, False, False)
    assert chiuso["chiusa_da"] == {"id": 1, "nome": "Telefono", "tipo": "telefono"}
    for headers in (GENITORE, pronto.mamma):
        (avviso,) = _notifiche(client, headers, "studio_chiuso")
        assert avviso["messaggio"] == (
            f"Luca ha chiuso lo Studio alle 16:10 (dalle 15:00): 65 min — compiti (matematica), allenamento."
            f" «{DICHIARAZIONE}»")
        assert avviso["payload"] == {
            "studio_id": studio["id"], "fine_ts": fine, "chiusura": "figlio",
            "tratti": [{"tipo": "compiti", "parola": "matematica", "minuti": 45},
                       {"tipo": "altro", "parola": "allenamento", "minuti": 20}],
            "minuti_attivita": 65, "dichiarazione": DICHIARAZIONE, "motivo": None}
    assert _studio(client)["in_corso"] is None
    # la stessa chiave: 200 con lo Studio; un'altra: gia_chiuso
    assert _ok(_chiudi(client, studio["id"]))["id"] == studio["id"]
    rifiuto = _chiudi(client, studio["id"], chiave="altra")
    assert rifiuto.status_code == 409 and rifiuto.json()["detail"]["errore"] == "gia_chiuso"
    # un tratto che arriva dopo la chiusura resta nel registro e non conta
    tardi = _ok(_tratti(client, _tratto("t3", CHIUDIBILE, CHIUDIBILE + timedelta(minutes=5))))["tratti"][0]
    assert tardi["conta"] is False
    assert _ok(client.get("/api/studio/svolte", headers=GENITORE))["svolte"][0]["minuti_alla_chiusura"] == 65
    assert len(_notifiche(client, GENITORE, "studio_chiuso")) == 1
    # uno Studio che non c'e' o di un altro figlio
    for studio_id in (999, 2 ** 70):
        r = _chiudi(client, studio_id)
        assert r.status_code == 404 and r.json() == {"detail": "studio non trovato"}
    r = _chiudi(client, studio["id"], headers=pronto.tel_sara)
    assert r.status_code == 404


def test_la_chiusura_fatta_senza_rete_vale_dal_suo_momento(client, pronto, orologio):
    studio = _alla_partenza(client, orologio, 10)
    chiusa_alle = datetime(2026, 7, 15, 14, 40, tzinfo=UTC)
    orologio.vai_a(chiusa_alle + timedelta(minutes=50))
    chiuso = _ok(_chiudi(client, studio["id"], ts_device=_ms(chiusa_alle),
                         tratti=[_tratto("t1", PARTENZA, PARTENZA + timedelta(minutes=70))]))
    assert (chiuso["fine_ts"], chiuso["minuti_alla_chiusura"]) == (_iso(chiusa_alle), 70)
    (avviso,) = _notifiche(client, GENITORE, "studio_chiuso")
    assert avviso["messaggio"].endswith("(chiusa senza rete alle 16:40)")


def test_una_chiusura_col_orologio_avanti_vale_all_arrivo(client, pronto, orologio):
    studio = _alla_partenza(client, orologio, 40)
    _ok(_tratti(client, _tratto("t1", PARTENZA, PARTENZA + timedelta(minutes=40))))
    rifiuto = _chiudi(client, studio["id"], ts_device=_ms(CHIUDIBILE + timedelta(hours=1)))
    assert rifiuto.status_code == 409 and rifiuto.json()["detail"]["errore"] == "troppo_presto"


def test_la_chiusura_senza_id(client, pronto, orologio):
    """Il telefono e' partito senza rete e non sa l'id: chiude col giorno; il server crea
    lo Studio (la partenza e' dentro le 48 ore) e fa gli stessi controlli."""
    orologio.vai_a(CHIUDIBILE + timedelta(minutes=30))
    chiuso = _ok(_chiudi(client, None, studio={"giorno": "2026-07-15"}, ts_device=_ms(CHIUDIBILE),
                         tratti=[_tratto("t1", PARTENZA, CHIUDIBILE)]))
    assert (chiuso["origine"], chiuso["inizio_ts"], chiuso["chiusura"], chiuso["fine_ts"]) == (
        "automatica", _iso(PARTENZA), "figlio", _iso(CHIUDIBILE))
    for corpo in ({}, {"studio": {}}, {"studio": {"giorno": "2026-07-15", "chiave": "x"}},
                  {"studio": {"giorno": "15/07/2026"}}):
        r = client.post("/api/studio/chiudi", json={"chiave": "c", "dichiarazione": DICHIARAZIONE, **corpo},
                        headers=FIGLIO)
        assert r.status_code == 422, corpo
    r = _chiudi(client, None, chiave="altra", studio={"giorno": "2026-07-14"})  # quel giorno niente Studio
    assert r.status_code == 404
    assert client.post("/api/studio/chiudi", json={"chiave": "c", "dichiarazione": DICHIARAZIONE,
                                                   "studio": {"giorno": "2026-07-15"}},
                       headers=GENITORE).status_code == 403


def test_i_tratti_del_corpo_restano_anche_se_la_chiusura_e_rifiutata(client, pronto, orologio):
    studio = _alla_partenza(client, orologio, 20)
    rifiuto = _chiudi(client, studio["id"], tratti=[_tratto("t1", PARTENZA, PARTENZA + timedelta(minutes=20))])
    assert rifiuto.status_code == 409
    assert [t["id"] for t in _studio(client)["in_corso"]["tratti"]] == ["t1"]
    assert _chiudi(client, 999, chiave="x", tratti=[_tratto("t2", PARTENZA, PARTENZA + timedelta(minutes=1))]).status_code == 404
    assert [t["id"] for t in _studio(client)["in_corso"]["tratti"]] == ["t1", "t2"]


# --- la mezzanotte ---

def test_a_mezzanotte_si_chiude_da_solo(client, pronto, orologio):
    studio = _alla_partenza(client, orologio, 50)
    _ok(_tratti(client, _tratto("t1", PARTENZA, PARTENZA + timedelta(minutes=40))))
    # un tratto in corso che passa la mezzanotte: si taglia alla fine dello Studio
    orologio.vai_a(MEZZANOTTE - timedelta(minutes=10))
    _ok(_tratti(client, _in_corso("t2", MEZZANOTTE - timedelta(minutes=5))))
    orologio.vai_a(MEZZANOTTE + timedelta(hours=8))  # il mattino dopo
    assert _studio(client)["in_corso"] is None
    (chiuso,) = _studio(client)["recenti"]
    assert (chiuso["chiusura"], chiuso["fine_ts"], chiuso["minuti_alla_chiusura"], chiuso["dichiarazione"],
            chiuso["chiusa_da"]) == ("non_chiuso", _iso(MEZZANOTTE), 40, None, None)
    (avviso,) = _notifiche(client, GENITORE, "studio_non_chiuso")
    assert avviso["messaggio"] == "Lo Studio di Luca non è stato chiuso: 40 min di attività"
    # il telefono chiude il tratto in corso alla mezzanotte: conta fino a li'
    _ok(_tratti(client, _tratto("t2", MEZZANOTTE - timedelta(minutes=5), MEZZANOTTE + timedelta(minutes=3),
                                esito="interrotto")))
    tratti = {t["id"]: t for t in _studio(client)["recenti"][0]["tratti"]}
    assert tratti["t2"]["secondi_contati"] == 300
    assert studio["id"] == chiuso["id"]


def test_una_chiusura_tardiva_valida_vince_sulla_mezzanotte(client, pronto, orologio):
    """Chiusa senza rete alle 16:40 e consegnata il mattino dopo: il `non_chiuso` di
    mezzanotte cede, una volta sola; l'avviso di mezzanotte si chiude per tutti."""
    studio = _alla_partenza(client, orologio, 5)
    orologio.vai_a(MEZZANOTTE + timedelta(hours=8))
    _ok(client.get("/api/famiglia", headers=GENITORE))  # la mezzanotte e' gia' scritta
    assert len(_notifiche(client, GENITORE, "studio_non_chiuso")) == 1
    chiusa_alle = datetime(2026, 7, 15, 14, 40, tzinfo=UTC)
    chiuso = _ok(_chiudi(client, studio["id"], ts_device=_ms(chiusa_alle),
                         tratti=[_tratto("t1", PARTENZA, PARTENZA + timedelta(minutes=65))]))
    assert (chiuso["chiusura"], chiuso["fine_ts"], chiuso["dichiarazione_ts"], chiuso["minuti_alla_chiusura"]) == (
        "figlio", _iso(chiusa_alle), _iso(chiusa_alle), 65)
    for headers in (GENITORE, pronto.mamma):
        assert _notifiche(client, headers, "studio_non_chiuso") == []
        (avviso,) = _notifiche(client, headers, "studio_chiuso")
        assert avviso["messaggio"].endswith("(chiusa senza rete alle 16:40, arrivata dopo)")
    # una volta sola: un'altra chiusura e' gia_chiuso
    assert _chiudi(client, studio["id"], chiave="altra", ts_device=_ms(chiusa_alle)).status_code == 409


def test_una_chiusura_tardiva_non_valida_non_cede(client, pronto, orologio):
    """(Correzione) La mezzanotte gia' scritta da una richiesta di prima: gia_chiuso. Se
    invece la mezzanotte la scrive proprio questa richiesta, prima vale la chiusura
    consegnata (contratto: prima le chiusure del figlio, poi la mezzanotte), e il telefono
    riceve il motivo vero (attivita_insufficiente), non gia_chiuso."""
    studio = _alla_partenza(client, orologio, 5)
    orologio.vai_a(MEZZANOTTE + timedelta(hours=8))
    rifiuto = _chiudi(client, studio["id"], ts_device=_ms(CHIUDIBILE + timedelta(minutes=5)),
                      tratti=[_tratto("t1", PARTENZA, PARTENZA + timedelta(minutes=20))])
    assert rifiuto.status_code == 409
    assert {k: v for k, v in rifiuto.json()["detail"].items() if k != "studio"} == {
        "errore": "attivita_insufficiente", "minuti": 20, "minimi": 60}
    assert rifiuto.json()["detail"]["studio"]["chiusura"] == "non_chiuso"
    assert _studio(client)["recenti"][0]["chiusura"] == "non_chiuso"
    assert len(_notifiche(client, GENITORE, "studio_non_chiuso")) == 1
    # adesso la mezzanotte e' scritta: un'altra chiusura non valida e' gia_chiuso
    rifiuto = _chiudi(client, studio["id"], chiave="altra", ts_device=_ms(CHIUDIBILE + timedelta(minutes=5)))
    assert rifiuto.status_code == 409 and rifiuto.json()["detail"]["errore"] == "gia_chiuso"


def test_il_giorno_dopo_non_si_e_in_studio_fino_alle_15(client, pronto, orologio):
    _alla_partenza(client, orologio)
    orologio.vai_a(MEZZANOTTE + timedelta(hours=10))  # giovedi' alle 10 di Roma
    assert _studio(client)["in_corso"] is None
    orologio.vai_a(PARTENZA + timedelta(days=1))
    domani = _studio(client)["in_corso"]
    assert (domani["giorno"], domani["inizio_ts"]) == ("2026-07-16", _iso(PARTENZA + timedelta(days=1)))


# --- la chiusura del genitore ---

def test_il_genitore_chiude_con_un_motivo(client, pronto, orologio):
    studio = _alla_partenza(client, orologio, 20)
    _ok(_tratti(client, _in_corso("t1", PARTENZA + timedelta(minutes=5))))
    for motivo in (None, "", "  ok  ", "x" * 301):
        corpo = {} if motivo is None else {"motivo": motivo}
        assert client.post(f"/api/studio/{studio['id']}/chiudi", json=corpo, headers=GENITORE).status_code == 422
    assert _chiudi_genitore(client, studio["id"], figlio_id=pronto.sara).status_code == 404
    assert _chiudi_genitore(client, studio["id"], figlio_id=99).status_code == 404
    orologio.avanza(minutes=10)
    chiuso = _ok(_chiudi_genitore(client, studio["id"], headers=pronto.mamma, motivo="  visita medica  "))
    fine = _iso(PARTENZA + timedelta(minutes=30))
    assert (chiuso["chiusura"], chiuso["fine_ts"], chiuso["motivo"], chiuso["chiusa_da"]) == (
        "genitore", fine, "visita medica", MAMMA)
    for headers in (FIGLIO, pronto.pc, GENITORE):
        (avviso,) = _notifiche(client, headers, "studio_chiuso")
        assert avviso["messaggio"] == "Mamma ha chiuso lo Studio: visita medica"
        assert avviso["payload"]["chiusura"] == "genitore" and avviso["payload"]["motivo"] == "visita medica"
    assert _notifiche(client, pronto.mamma, "studio_chiuso") == []  # gia' letta per chi ha chiuso
    rifiuto = _chiudi_genitore(client, studio["id"])
    assert rifiuto.status_code == 409 and rifiuto.json()["detail"]["errore"] == "gia_chiuso"
    # il tratto in corso non si tocca: quando arriva finito vale, tagliato alla chiusura
    orologio.avanza(minutes=10)
    finito = _ok(_tratti(client, _tratto("t1", PARTENZA + timedelta(minutes=5), PARTENZA + timedelta(minutes=35))))
    assert (finito["tratti"][0]["conta"], finito["tratti"][0]["secondi_contati"]) == (True, 25 * 60)
    # la chiusura del figlio fatta prima, consegnata dopo: la dichiarazione si salva
    chiuso = _ok(_chiudi(client, studio["id"], ts_device=_ms(PARTENZA + timedelta(minutes=25))))
    assert (chiuso["chiusura"], chiuso["dichiarazione"], chiuso["dichiarazione_ts"], chiuso["fine_ts"]) == (
        "genitore", DICHIARAZIONE, _iso(PARTENZA + timedelta(minutes=25)), fine)
    assert len(_notifiche(client, GENITORE, "studio_chiuso")) == 2


def test_figlio_e_genitore_chiudono_insieme(client, pronto, orologio):
    for giorno in range(3):
        orologio.vai_a(PARTENZA + timedelta(days=giorno, minutes=70))
        studio = _studio(client)["in_corso"]
        _ok(_tratti(client, _tratto(f"t{giorno}", PARTENZA + timedelta(days=giorno),
                                    PARTENZA + timedelta(days=giorno, minutes=65))))
        barriera = threading.Barrier(2)
        esiti = {}

        def figlio():
            barriera.wait()
            esiti["figlio"] = _chiudi(client, studio["id"], chiave=f"c{giorno}")

        def genitore():
            barriera.wait()
            esiti["genitore"] = _chiudi_genitore(client, studio["id"])

        fili = [threading.Thread(target=figlio), threading.Thread(target=genitore)]
        for f in fili:
            f.start()
        for f in fili:
            f.join()
        stati = sorted(r.status_code for r in esiti.values())
        assert stati in ([200, 409], [200, 200]), stati  # il figlio dopo il genitore: dichiarazione salvata
        assert _studio(client)["in_corso"] is None


# --- l'avvio a mano ---

def test_l_avvio_a_mano(client, pronto, orologio):
    orologio.vai_a(datetime(2026, 7, 14, 15, 0, tzinfo=UTC))  # martedi' 17:00 di Roma: niente partenza oggi
    _errore(_avvia(client, headers=pronto.pc), 422, "solo_dal_telefono")
    for corpo in ({}, {"chiave": ""}, {"chiave": "x" * 65}, {"chiave": "a", "ts_device": "1"}):
        assert client.post("/api/studio/avvia", json=corpo, headers=FIGLIO).status_code == 422, corpo
    r = _avvia(client)
    assert r.status_code == 201
    studio = r.json()
    assert (studio["origine"], studio["chiave"], studio["avviato_da"], studio["partenze"], studio["chiudibile_dal"],
            studio["minuti_minimi"], studio["giorno"]) == (
        "manuale", "avvio-1", {"id": 1, "nome": "Telefono", "tipo": "telefono"}, [], None, 60, "2026-07-14")
    assert _notifiche(client, GENITORE, "studio_iniziato") == []  # niente notifica, come le sessioni
    stesso = _avvia(client)
    assert stesso.status_code == 200 and stesso.json()["id"] == studio["id"]
    # un altro avvio a mano consegnato tardi, di prima dell'inizio di questo: (correzione)
    # lo Studio a mano aperto si sposta indietro all'inizio dichiarato (contratto: «Se uno
    # Studio aperto e' cominciato dopo I, nello stesso giorno»), ma non cambia chiave (la
    # conosce chi l'ha avviato); il telefono lo adotta
    prima = _avvia(client, chiave="avvio-2", ts_device=_ms(datetime(2026, 7, 14, 14, 50, tzinfo=UTC)))
    assert prima.status_code == 200
    assert (prima.json()["id"], prima.json()["chiave"], prima.json()["inizio_ts"]) == (
        studio["id"], "avvio-1", "2026-07-14T14:50:00+00:00")
    dentro = _avvia(client, chiave="avvio-3", ts_device=_ms(datetime(2026, 7, 14, 15, 5, tzinfo=UTC)))
    assert dentro.status_code == 200 and dentro.json()["id"] == studio["id"]


def test_l_avvio_a_mano_rifiutato(client, famiglia, orologio):
    _battito(client, "0.18.0")
    _errore(_avvia(client), 409, "studio_non_approvato")
    _approva_config(client, {"minuti_minimi": 60})
    _errore(_avvia(client, ts_device=_ms(datetime(2026, 7, 12, 9, 0, tzinfo=UTC))), 409, "avvio_scaduto")
    # col blocco dei lavori attivo: lo Studio a mano non rinvia un blocco gia' partito
    orologio.vai_a(datetime(2026, 7, 14, 20, 0, tzinfo=UTC))
    (letto,) = _date(client, "Letto")
    _errore(_avvia(client), 409, "blocco_faccende")
    # troppo tardi: da adesso a mezzanotte mancano meno di 60 minuti
    orologio.vai_a(datetime(2026, 7, 14, 21, 10, tzinfo=UTC))  # le 23:10 di Roma
    _errore(_avvia(client), 409, "troppo_tardi")
    assert _studio(client)["recenti"] == []  # non e' nato niente


def test_un_avvio_consegnato_tardi_quando_il_blocco_era_gia_attivo(client, pronto, orologio):
    (letto,) = _date(client, "Letto", blocco_da="2026-07-14T12:00:00+00:00")
    orologio.vai_a(datetime(2026, 7, 14, 13, 0, tzinfo=UTC))
    foto_ts = _ok(_foto(client, letto["id"]))["foto_ts"]
    _ok(client.post(f"/api/faccende/{letto['id']}/conferma", json={"foto_ts": foto_ts}, headers=GENITORE))
    assert _blocco(client)["attivo"] is False
    # l'avvio fatto alle 12:30 (blocco attivo allora), consegnato adesso
    _errore(_avvia(client, ts_device=_ms(datetime(2026, 7, 14, 12, 30, tzinfo=UTC))), 409, "blocco_faccende")
    # quello fatto alle 11:30 (prima del blocco) si'
    assert _avvia(client, chiave="prima", ts_device=_ms(datetime(2026, 7, 14, 11, 30, tzinfo=UTC))).status_code == 201


def test_l_avvio_a_mano_prima_delle_15_assorbe_la_partenza(client, pronto, orologio):
    """Aperto alle 14:00, alle 15:00 la partenza entra nello Studio: conta_dal 15:00 (il
    tempo di prima non conta) e il vincolo delle 16:00."""
    orologio.vai_a(PARTENZA - timedelta(hours=1))
    studio = _ok(_avvia(client), 201)
    assert (studio["conta_dal"], studio["chiudibile_dal"]) == (_iso(PARTENZA - timedelta(hours=1)), None)
    orologio.vai_a(PARTENZA + timedelta(minutes=30))
    vista = _studio(client)["in_corso"]
    assert vista["id"] == studio["id"] and vista["origine"] == "manuale"
    assert [p["giorno"] for p in vista["partenze"]] == ["2026-07-15"]
    assert (vista["conta_dal"], vista["chiudibile_dal"], vista["minuti_minimi"]) == (
        _iso(PARTENZA), _iso(CHIUDIBILE), 60)
    tratti = _ok(_tratti(client, _tratto("prima", PARTENZA - timedelta(minutes=50), PARTENZA - timedelta(minutes=10)),
                         _tratto("a cavallo", PARTENZA - timedelta(minutes=10), PARTENZA + timedelta(minutes=20))))
    per_id = {t["id"]: t for t in tratti["tratti"]}
    assert per_id["a cavallo"]["secondi_contati"] == 1200
    assert _studio(client)["in_corso"]["minuti_attivita"] == 20
    assert _notifiche(client, GENITORE, "studio_iniziato") == []  # la partenza non ha creato niente


def test_un_avvio_tardivo_prima_di_uno_studio_aperto_lo_sposta_indietro(client, pronto, orologio):
    _alla_partenza(client, orologio, 20)
    inizio = PARTENZA - timedelta(minutes=30)
    r = _avvia(client, chiave="offline", ts_device=_ms(inizio))
    assert r.status_code == 200
    studio = r.json()
    assert (studio["inizio_ts"], studio["origine"], studio["chiave"], studio["conta_dal"]) == (
        _iso(inizio), "manuale", "offline", _iso(PARTENZA))
    assert len(_studio(client)["recenti"]) == 1
    # l'inizio dentro lo Studio: il telefono lo adotta
    dentro = _avvia(client, chiave="altro", ts_device=_ms(PARTENZA - timedelta(minutes=10)))
    assert dentro.status_code == 200 and dentro.json()["id"] == studio["id"]


def test_un_avvio_di_ieri_nasce_gia_chiuso(client, pronto, orologio):
    _alla_partenza(client, orologio, 20)
    ieri = datetime(2026, 7, 14, 17, 0, tzinfo=UTC)  # le 19:00 di Roma del giorno prima
    r = _avvia(client, chiave="ieri", ts_device=_ms(ieri))
    assert r.status_code == 201
    ieri_studio = r.json()
    assert (ieri_studio["giorno"], ieri_studio["chiusura"], ieri_studio["fine_ts"]) == (
        "2026-07-14", "non_chiuso", "2026-07-14T22:00:00+00:00")
    assert _studio(client)["in_corso"]["giorno"] == "2026-07-15"  # quello di oggi non si tocca
    # e la sua chiusura fatta ieri sera, consegnata adesso, lo chiude
    _ok(_tratti(client, _tratto("ieri", ieri, ieri + timedelta(minutes=70))))
    chiuso = _ok(_chiudi(client, None, chiave="c-ieri", studio={"chiave": "ieri"},
                         ts_device=_ms(ieri + timedelta(minutes=75))))
    assert (chiuso["id"], chiuso["chiusura"], chiuso["minuti_alla_chiusura"]) == (ieri_studio["id"], "figlio", 70)


def test_una_chiusura_senza_rete_prima_della_partenza_assorbita(client, pronto, orologio):
    """Aperto a mano alle 14:00, chiuso senza rete alle 14:50 (a mano: niente vincolo
    d'orario) e consegnato alle 15:30, dopo che la partenza delle 15:00 era entrata nello
    Studio: la partenza si toglie e si rielabora, nasce lo Studio automatico delle 15:00
    e i tratti dopo le 14:50 passano a lui."""
    _approva_config(client, {"minuti_minimi": 30})
    orologio.vai_a(PARTENZA - timedelta(hours=1))
    studio = _ok(_avvia(client), 201)
    orologio.vai_a(PARTENZA + timedelta(minutes=30))
    assert len(_studio(client)["in_corso"]["partenze"]) == 1
    chiuso = _ok(_chiudi(client, studio["id"], ts_device=_ms(PARTENZA - timedelta(minutes=10)), tratti=[
        _tratto("prima", PARTENZA - timedelta(minutes=55), PARTENZA - timedelta(minutes=15)),
        _tratto("dopo", PARTENZA + timedelta(minutes=5), PARTENZA + timedelta(minutes=25)),
    ]))
    assert (chiuso["chiusura"], chiuso["fine_ts"], chiuso["partenze"], chiuso["minuti_alla_chiusura"]) == (
        "figlio", _iso(PARTENZA - timedelta(minutes=10)), [], 40)
    nuovo = _studio(client)["in_corso"]
    assert (nuovo["origine"], nuovo["inizio_ts"], [t["id"] for t in nuovo["tratti"]]) == (
        "automatica", _iso(PARTENZA), ["dopo"])
    assert nuovo["minuti_attivita"] == 20
    (iniziato,) = _notifiche(client, GENITORE, "studio_iniziato")
    assert iniziato["payload"]["studio_id"] == nuovo["id"]


# --- Studio e sessioni, Studio e lavori ---

def test_lo_studio_chiude_le_sessioni_in_corso_e_non_ne_fa_partire(client, pronto, orologio):
    s = _ok(client.post("/api/sessioni", json={"nome": "Musica", "app": ["com.esempio.musica"]}, headers=FIGLIO), 201)
    assert client.post(f"/api/sessioni/{s['id']}/risposta", json={"esito": "approva", "versione": 1},
                       headers=GENITORE).status_code == 200
    orologio.vai_a(PARTENZA - timedelta(minutes=30))
    svolta = _ok(client.post(f"/api/sessioni/{s['id']}/avvia", json={"durata_minuti": 120}, headers=FIGLIO), 201)
    orologio.vai_a(PARTENZA + timedelta(minutes=5))
    studio = _studio(client)["in_corso"]
    assert studio["sessione_chiusa"] == {"id": svolta["id"], "nome": "Musica"}
    chiusa = next(x for x in _ok(client.get("/api/patto", headers=FIGLIO))["sessioni_svolte"] if x["id"] == svolta["id"])
    assert (chiusa["chiusura"], chiusa["fine_ts"]) == ("terminata", _iso(PARTENZA))
    # durante lo Studio niente sessioni, prima ancora del blocco dei lavori
    _date(client, "Letto")
    _errore(client.post(f"/api/sessioni/{s['id']}/avvia", json={"durata_minuti": 30}, headers=FIGLIO),
            409, "studio_in_corso")


def test_le_sessioni_dei_telefoni_017_non_si_chiudono(client, pronto, orologio):
    tablet, tablet_id = dispositivo_abbinato(client, 1, "Tablet", "telefono")
    _battito(client, "0.17.0", tablet)
    s = _ok(client.post("/api/sessioni", json={"nome": "Musica", "app": ["com.esempio.musica"]}, headers=tablet), 201)
    assert client.post(f"/api/sessioni/{s['id']}/risposta", json={"esito": "approva", "versione": 1},
                       headers=GENITORE).status_code == 200
    orologio.vai_a(PARTENZA - timedelta(minutes=30))
    _ok(client.post(f"/api/sessioni/{s['id']}/avvia", json={"durata_minuti": 120}, headers=tablet), 201)
    orologio.vai_a(PARTENZA + timedelta(minutes=5))
    assert _studio(client)["in_corso"]["sessione_chiusa"] is None
    assert _ok(client.get("/api/patto", headers=tablet))["sessione_in_corso"] is not None


def test_durante_lo_studio_il_blocco_dei_lavori_aspetta(client, pronto, orologio):
    studio = _alla_partenza(client, orologio, 10)
    (letto,) = _date(client, "Letto")  # dato durante lo Studio: blocca subito, ma aspetta
    blocco = _blocco(client)
    assert (blocco["attivo"], blocco["rimandato"], blocco["studio"]["id"]) == (True, True, studio["id"])
    assert _ok(client.get("/api/famiglia", headers=GENITORE))["figli"][0]["blocco_rimandato"] is True
    foto_ts = _ok(_foto(client, letto["id"]))["foto_ts"]  # le foto si scattano anche in Studio
    _ok(client.post(f"/api/faccende/{letto['id']}/conferma", json={"foto_ts": foto_ts}, headers=GENITORE))
    (avviso,) = _notifiche(client, FIGLIO, "faccenda_confermata")
    assert avviso["messaggio"] == "Genitore ha approvato «Letto»: il blocco non partirà a fine Studio"
    assert avviso["payload"]["sblocca"] is False
    (finite,) = _notifiche(client, GENITORE, "faccende_finite")
    assert finite["messaggio"] == "Luca ha i lavori di casa tutti approvati"


def test_a_fine_studio_il_blocco_parte(client, pronto, orologio):
    studio = _alla_partenza(client, orologio, 10)
    _date(client, "Letto")
    assert _blocco(client)["rimandato"] is True
    _ok(_chiudi_genitore(client, studio["id"]))
    blocco = _blocco(client)
    assert (blocco["attivo"], blocco["rimandato"], blocco["studio"]["in_corso"]) == (True, False, False)


# --- dove si vede ---

def test_lo_storico_degli_studi(client, pronto, orologio):
    for giorno in range(32):  # dal lunedi' al venerdi': 23 Studi
        orologio.vai_a(PARTENZA + timedelta(days=giorno, minutes=1))
        _ok(client.get("/api/patto", headers=FIGLIO))
    pagina = _ok(client.get("/api/studio/svolte", headers=GENITORE))
    assert (len(pagina["svolte"]), pagina["altre"]) == (20, True)
    ids = [s["id"] for s in pagina["svolte"]]
    assert ids == list(range(23, 3, -1))
    assert {s["giorno"] for s in pagina["svolte"]} & {"2026-07-18", "2026-07-19"} == set()  # sabato, domenica
    resto = _ok(client.get("/api/studio/svolte", headers=FIGLIO, params={"prima_di": ids[-1]}))
    assert resto["altre"] is False and [s["id"] for s in resto["svolte"]] == [3, 2, 1]
    assert len(_ok(client.get("/api/finestra", headers=GENITORE))["studio_svolte"]) <= 50
    # gli Studi della finestra toccano gli 8 giorni
    finestra = _ok(client.get("/api/finestra", headers=GENITORE))["studio_svolte"]
    assert 1 <= len(finestra) <= 9
    assert _ok(client.get("/api/studio/svolte", headers=pronto.tel_sara))["svolte"] == []


def test_la_configurazione_da_approvare_in_finestra_e_famiglia(client, pronto):
    _ok(_proponi(client, {"inizio": "15:30"}))
    assert _ok(client.get("/api/finestra", headers=GENITORE))["studio_da_approvare"] == 1
    assert _ok(client.get("/api/famiglia", headers=GENITORE))["figli"][0]["studio_da_approvare"] == 1
    assert _ok(client.get(f"/api/finestra?figlio_id={pronto.sara}", headers=GENITORE))["studio_da_approvare"] == 0


def test_senza_telefono_018_i_dispositivi_non_vedono_lo_studio_in_corso(client, pronto, orologio):
    """Un telefono che torna alla 0.17 durante lo Studio: per i dispositivi lo Studio non e'
    in corso (nessuno potrebbe chiuderlo), e il blocco non e' rimandato."""
    _alla_partenza(client, orologio, 10)
    _date(client, "Letto")
    _battito(client, "0.17.0")
    patto = _ok(client.get("/api/patto", headers=pronto.pc))
    assert (patto["studio"]["in_corso"], patto["studio"]["prossime_partenze"]) == (None, [])
    assert (patto["blocco"]["attivo"], patto["blocco"]["rimandato"]) == (True, False)


# --- il database tiene la storia ---

def test_uno_studio_chiuso_non_si_riscrive_e_i_tratti_finiti_non_cambiano(client, pronto, orologio, db_path):
    import sqlite3

    studio = _alla_partenza(client, orologio, 20)
    _ok(_tratti(client, _tratto("t1", PARTENZA, PARTENZA + timedelta(minutes=10))))
    _ok(_chiudi_genitore(client, studio["id"]))
    conn = sqlite3.connect(db_path)
    try:
        for sql in ("UPDATE studio_svolte SET motivo = 'un altro motivo'",
                    "UPDATE studio_svolte SET fine_ts = '2026-07-15T13:05:00+00:00'",
                    "UPDATE studio_svolte SET chiusura = 'figlio', dichiarazione = 'scritta a mano'",
                    "UPDATE studio_svolte SET dichiarazione = 'scritta', minuti_alla_chiusura = 99",
                    "DELETE FROM studio_svolte"):
            with pytest.raises(sqlite3.IntegrityError, match="Stud"):
                conn.execute(sql)
        for sql in ("UPDATE studio_tratti SET secondi = 1", "UPDATE studio_tratti SET conta = 0",
                    "DELETE FROM studio_tratti"):
            with pytest.raises(sqlite3.IntegrityError, match="tratt"):
                conn.execute(sql)
        conn.execute("UPDATE studio_tratti SET studio_id = NULL")  # passare a un altro Studio si puo'
        conn.rollback()
    finally:
        conn.close()


def test_una_bocciatura_durante_lo_studio_aspetta_lo_studio(client, pronto, orologio):
    """Parte E, «Lavori scaduti o dati durante lo Studio»: una bocciatura durante lo Studio
    riporta il lavoro a da_fare col blocco subito, ma il blocco aspetta lo Studio; a
    mezzanotte lo Studio si chiude e il blocco parte."""
    _alla_partenza(client, orologio, 10)
    (letto,) = _date(client, "Letto", blocco_da="2026-07-15T20:00:00+00:00")  # alle 22:00 di Roma
    foto_ts = _ok(_foto(client, letto["id"]))["foto_ts"]
    blocco = _blocco(client)
    assert (blocco["attivo"], blocco["rimandato"], blocco["prossimo"]) == (False, False, "2026-07-15T20:00:00+00:00")
    assert [(v["stato"], v["foto_ts"]) for v in blocco["da_fare"]] == [("fatta", foto_ts)]
    orologio.avanza(minutes=5)
    _ok(client.post(f"/api/faccende/{letto['id']}/boccia", headers=GENITORE))
    blocco = _blocco(client)
    assert (blocco["attivo"], blocco["rimandato"], blocco["studio"]["in_corso"]) == (True, True, True)
    assert [(v["stato"], v["foto_ts"]) for v in blocco["da_fare"]] == [("da_fare", None)]
    # la mezzanotte chiude lo Studio: il blocco non aspetta piu'
    orologio.vai_a(MEZZANOTTE + timedelta(minutes=1))
    blocco = _blocco(client)
    assert (blocco["attivo"], blocco["rimandato"], blocco["studio"]) == (
        True, False, {"in_corso": False, "id": None, "inizio_ts": None})


# --- (ripresa) casi limite in piu' ---

def test_una_chiusura_tardiva_di_ieri_prima_della_partenza_con_lo_studio_di_oggi_aperto(client, pronto, orologio):
    """Mercoledi': aperto a mano alle 14:00, chiuso senza rete alle 14:50 (prima della
    partenza delle 15:00, che poi e' entrata nello Studio). La chiusura arriva giovedi'
    alle 15:30, con lo Studio di giovedi' aperto. Il non_chiuso di mercoledi' cede, la
    partenza di mercoledi' si rielabora: lo Studio automatico di mercoledi' nasce gia'
    oltre la sua mezzanotte, quindi gia' chiuso (non_chiuso, coi tratti dopo le 14:50), e
    quello di giovedi' non si tocca."""
    _approva_config(client, {"minuti_minimi": 30})
    orologio.vai_a(PARTENZA - timedelta(hours=1))
    a_mano = _ok(_avvia(client), 201)
    orologio.vai_a(PARTENZA + timedelta(minutes=30))
    assert len(_studio(client)["in_corso"]["partenze"]) == 1
    orologio.vai_a(PARTENZA + timedelta(days=1, minutes=30))
    oggi = _studio(client)["in_corso"]
    assert oggi["giorno"] == "2026-07-16"
    chiuso = _ok(_chiudi(client, a_mano["id"], ts_device=_ms(PARTENZA - timedelta(minutes=10)), tratti=[
        _tratto("prima", PARTENZA - timedelta(minutes=55), PARTENZA - timedelta(minutes=15)),
        _tratto("dopo", PARTENZA + timedelta(minutes=5), PARTENZA + timedelta(minutes=45)),
    ]))
    assert (chiuso["chiusura"], chiuso["fine_ts"], chiuso["partenze"], chiuso["minuti_alla_chiusura"]) == (
        "figlio", _iso(PARTENZA - timedelta(minutes=10)), [], 40)
    recenti = {s["giorno"] + s["origine"]: s for s in _studio(client)["recenti"]}
    mercoledi = recenti["2026-07-15automatica"]
    assert (mercoledi["inizio_ts"], mercoledi["chiusura"], mercoledi["fine_ts"], mercoledi["minuti_alla_chiusura"],
            [t["id"] for t in mercoledi["tratti"]]) == (_iso(PARTENZA), "non_chiuso", _iso(MEZZANOTTE), 40, ["dopo"])
    assert _studio(client)["in_corso"]["id"] == oggi["id"]
    # lo Studio di mercoledi' nato in ritardo e gia' chiuso: niente studio_iniziato per lui
    assert mercoledi["id"] not in [n["payload"]["studio_id"] for n in _notifiche(client, GENITORE, "studio_iniziato")]


def test_un_tratto_va_allo_studio_con_cui_si_sovrappone_di_piu(client, pronto, orologio):
    """Parte C, «A quale Studio appartiene un tratto»: quello con cui si sovrappone di
    piu' (non solo dove finisce); fuori da ogni Studio resta nel registro senza Studio e
    non conta."""
    _approva_config(client, {"minuti_minimi": 30})
    orologio.vai_a(PARTENZA - timedelta(hours=1))
    a_mano = _ok(_avvia(client), 201)
    orologio.vai_a(PARTENZA + timedelta(minutes=30))
    _ok(_chiudi(client, a_mano["id"], ts_device=_ms(PARTENZA - timedelta(minutes=10)),
                tratti=[_tratto("prima", PARTENZA - timedelta(minutes=55), PARTENZA - timedelta(minutes=15))]))
    nuovo = _studio(client)["in_corso"]
    assert nuovo["id"] != a_mano["id"]
    # 14:45-15:10: 5 minuti nello Studio a mano (chiuso alle 14:50), 10 in quello delle 15:00
    esito = _ok(_tratti(client, _tratto("a cavallo", PARTENZA - timedelta(minutes=15), PARTENZA + timedelta(minutes=10)),
                        _tratto("fuori", PARTENZA - timedelta(hours=3), PARTENZA - timedelta(hours=2, minutes=30))))
    per_id = {t["id"]: t for t in esito["tratti"]}
    assert (per_id["a cavallo"]["conta"], per_id["a cavallo"]["secondi_contati"]) == (True, 600)
    assert (per_id["fuori"]["conta"], per_id["fuori"]["secondi_contati"]) == (False, 0)
    assert [t["id"] for t in _studio(client)["in_corso"]["tratti"]] == ["a cavallo"]
    assert _studio(client)["in_corso"]["minuti_attivita"] == 10
    vecchio = next(s for s in _studio(client)["recenti"] if s["id"] == a_mano["id"])
    assert [t["id"] for t in vecchio["tratti"]] == ["prima"] and vecchio["minuti_alla_chiusura"] == 40


def test_la_configurazione_cambiata_durante_lo_studio_non_cambia_le_sue_condizioni(client, pronto, orologio):
    """Parte E, «Configurazione cambiata durante lo Studio»: un minimo piu' basso approvato
    alle 15:30 vale dal giorno dopo; lo Studio aperto tiene le condizioni della sua
    partenza (60 minuti, chiudibile dalle 16:00)."""
    _alla_partenza(client, orologio, 30)
    _approva_config(client, {"minuti_minimi": 20, "chiusura_minima": "15:30"})
    _ok(_tratti(client, _tratto("t1", PARTENZA, PARTENZA + timedelta(minutes=30))))
    vista = _studio(client)["in_corso"]
    assert (vista["minuti_minimi"], vista["chiudibile_dal"], vista["chiudibile"]) == (60, _iso(CHIUDIBILE), False)
    orologio.vai_a(PARTENZA + timedelta(days=1, minutes=30))
    domani = _studio(client)["in_corso"]
    assert (domani["giorno"], domani["minuti_minimi"], domani["chiudibile_dal"]) == (
        "2026-07-16", 20, "2026-07-16T13:30:00+00:00")
