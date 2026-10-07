"""(v4.0) Le correzioni del lato server dopo la revisione (contratto-api.md, "v4.0"):
i casi limite che i revisori hanno trovato nello Studio, nei lavori da approvare e nella
migrazione. Ogni test dice il caso in una riga. L'ora dei test e' quella di test_studio:
martedi' 14 luglio 2026, 12:00 a Roma; lo Studio approvato allora parte mercoledi' 15
alle 15:00 di Roma (13:00 UTC). Tutti i dati sono finti."""

import os
import sqlite3
from datetime import datetime, timedelta, timezone

from aiuti_v3 import dispositivo_abbinato, nuovo_figlio
from conftest import FIGLIO, GENITORE
from test_faccende import _blocco, _date, _foto, _notifiche, _ok, famiglia  # noqa: F401  (famiglia e' una fixture)
from test_studio import (  # noqa: F401  (pronto e' una fixture)
    CHIUDIBILE,
    LISTE,
    MEZZANOTTE,
    PARTENZA,
    _avvia,
    _battito,
    _chiudi,
    _ms,
    _studio,
    _tratti,
    _tratto,
    pronto,
)

UTC = timezone.utc
MARTEDI_17 = datetime(2026, 7, 14, 15, 0, tzinfo=UTC)  # martedi' 17:00 di Roma: nessuna partenza


def _conferma(client, faccenda_id, foto_ts, headers=GENITORE):
    return client.post(f"/api/faccende/{faccenda_id}/conferma", json={"foto_ts": foto_ts}, headers=headers)


def _sql(query, *parametri):
    conn = sqlite3.connect(os.environ["PACTUM_DB"])
    conn.row_factory = sqlite3.Row
    try:
        return conn.execute(query, parametri).fetchall()
    finally:
        conn.close()


def _tablet(client):
    tablet, tablet_id = dispositivo_abbinato(client, 1, "Tablet", "telefono")
    _battito(client, "0.18.0", tablet)
    return tablet, tablet_id


# --- 4. approvare un lavoro guarda prima lo Studio ---

def test_approvare_alle_15_05_senza_richieste_dopo_la_partenza(client, pronto, orologio):
    """Telefono senza rete e computer spento: nessuna richiesta tra le 15:00 e le 15:05.
    Mamma approva l'ultimo lavoro alle 15:05: i dispositivi erano in Studio, quindi niente
    «sbloccati», ma «il blocco non partirà a fine Studio»."""
    orologio.vai_a(PARTENZA - timedelta(minutes=30))
    (letto,) = _date(client, "Letto")
    foto_ts = _ok(_foto(client, letto["id"]))["foto_ts"]
    orologio.vai_a(PARTENZA + timedelta(minutes=5))
    _ok(_conferma(client, letto["id"], foto_ts, headers=pronto.mamma))
    (avviso,) = _notifiche(client, FIGLIO, "faccenda_confermata")
    assert avviso["messaggio"] == "Mamma ha approvato «Letto»: il blocco non partirà a fine Studio"
    assert avviso["payload"]["sblocca"] is False
    (finite,) = _notifiche(client, GENITORE, "faccende_finite")
    assert finite["messaggio"] == "Luca ha i lavori di casa tutti approvati"


def test_approvare_dopo_mezzanotte_con_lo_studio_di_ieri_ancora_aperto(client, pronto, orologio):
    """Lo Studio di ieri e' ancora aperto per il server (nessuna richiesta dopo
    mezzanotte), ma a mezzanotte e' finito e il blocco e' partito: l'approvazione delle
    00:05 sblocca davvero."""
    orologio.vai_a(PARTENZA + timedelta(minutes=10))
    assert _studio(client)["in_corso"] is not None
    (letto,) = _date(client, "Letto")
    foto_ts = _ok(_foto(client, letto["id"]))["foto_ts"]
    orologio.vai_a(MEZZANOTTE + timedelta(minutes=5))
    _ok(_conferma(client, letto["id"], foto_ts))
    (avviso,) = _notifiche(client, FIGLIO, "faccenda_confermata")
    assert avviso["messaggio"] == "Genitore ha approvato «Letto»: telefono e computer sbloccati"
    assert avviso["payload"]["sblocca"] is True


# --- 5 e 12. due telefoni: la chiave adottata e l'avvio tardivo prima di uno Studio a mano ---

def test_la_chiusura_con_la_chiave_di_uno_studio_adottato(client, pronto, orologio):
    """Il telefono avvia alle 17:00 con la rete (K1). Il tablet, senza rete, avvia alle
    17:10 (K2): il server gli fa adottare lo Studio del telefono. La sua chiusura era gia'
    in coda con `studio: {chiave: K2}`: vale lo Studio che contiene la sua ora, non 404."""
    tablet, _ = _tablet(client)
    orologio.vai_a(MARTEDI_17)
    studio = _ok(_avvia(client, chiave="K1"), 201)
    orologio.vai_a(MARTEDI_17 + timedelta(minutes=75))
    adottato = _ok(_avvia(client, chiave="K2", headers=tablet, ts_device=_ms(MARTEDI_17 + timedelta(minutes=10))))
    assert (adottato["id"], adottato["chiave"]) == (studio["id"], "K1")
    chiuso = _ok(_chiudi(client, None, headers=tablet, studio={"chiave": "K2"},
                         ts_device=_ms(MARTEDI_17 + timedelta(minutes=72)),
                         tratti=[_tratto("tb1", MARTEDI_17 + timedelta(minutes=10),
                                         MARTEDI_17 + timedelta(minutes=72))]))
    assert (chiuso["id"], chiuso["chiusura"], chiuso["minuti_alla_chiusura"]) == (studio["id"], "figlio", 62)
    # una chiave che non c'e' e un'ora fuori da ogni Studio: 404 come prima
    orologio.avanza(hours=1)
    assert _chiudi(client, None, chiave="c9", studio={"chiave": "K9"}).status_code == 404


def test_un_avvio_tardivo_prima_di_uno_studio_a_mano_lo_sposta_indietro(client, pronto, orologio):
    """Il telefono avvia alle 16:30 di Roma con la rete. Il tablet aveva avviato alle
    16:00 senza rete, con un tratto 16:00-16:30 mandato PRIMA del suo avvio: lo Studio si
    sposta indietro alle 16:00 (tiene la chiave K1), e quei 30 minuti contano."""
    tablet, tablet_id = _tablet(client)
    alle_16 = datetime(2026, 7, 14, 14, 0, tzinfo=UTC)
    orologio.vai_a(alle_16 + timedelta(minutes=30))
    studio = _ok(_avvia(client, chiave="K1"), 201)
    orologio.avanza(minutes=5)
    tratto = _ok(_tratti(client, _tratto("tb1", alle_16, alle_16 + timedelta(minutes=30)), headers=tablet))
    assert tratto["tratti"][0]["conta"] is False  # per ora fuori da ogni Studio
    spostato = _ok(_avvia(client, chiave="K2", headers=tablet, ts_device=_ms(alle_16)))
    assert (spostato["id"], spostato["chiave"], spostato["inizio_ts"], spostato["conta_dal"]) == (
        studio["id"], "K1", "2026-07-14T14:00:00+00:00", "2026-07-14T14:00:00+00:00")
    assert spostato["avviato_da"]["id"] == tablet_id
    (tb1,) = spostato["tratti"]
    assert (tb1["conta"], tb1["secondi_contati"]) == (True, 1800)
    assert spostato["minuti_attivita"] == 30


# --- 6. i tratti arrivati prima del loro avvio ---

def test_i_tratti_mandati_prima_dell_avvio_contano(client, pronto, orologio):
    """Avvio a mano senza rete alle 17:00, un'ora di timer. Tornata la rete, la coda manda
    prima i tratti, poi l'avvio, poi la chiusura: lo Studio prende i tratti e si chiude."""
    orologio.vai_a(MARTEDI_17 + timedelta(minutes=70))
    tratti = _ok(_tratti(client, _tratto("t1", MARTEDI_17, MARTEDI_17 + timedelta(minutes=60))))
    assert tratti["tratti"][0]["conta"] is False
    studio = _ok(_avvia(client, chiave="K1", ts_device=_ms(MARTEDI_17)), 201)
    (t1,) = studio["tratti"]
    assert (t1["conta"], t1["secondi_contati"], studio["minuti_attivita"]) == (True, 3600, 60)
    chiuso = _ok(_chiudi(client, None, studio={"chiave": "K1"}, ts_device=_ms(MARTEDI_17 + timedelta(minutes=61))))
    assert (chiuso["chiusura"], chiuso["minuti_alla_chiusura"]) == ("figlio", 60)


def test_i_tratti_di_ieri_mandati_prima_dell_avvio_di_ieri(client, pronto, orologio):
    """Lo stesso, consegnato il mattino dopo: lo Studio a mano di ieri nasce chiuso a
    mezzanotte coi minuti dei suoi tratti, e la chiusura di ieri lo fa cedere."""
    alle_22 = datetime(2026, 7, 14, 20, 0, tzinfo=UTC)
    orologio.vai_a(datetime(2026, 7, 15, 7, 0, tzinfo=UTC))
    _ok(_tratti(client, _tratto("t1", alle_22, alle_22 + timedelta(minutes=60))))
    studio = _ok(_avvia(client, chiave="K1", ts_device=_ms(alle_22)), 201)
    assert (studio["chiusura"], studio["minuti_alla_chiusura"]) == ("non_chiuso", 60)
    (avviso,) = _notifiche(client, GENITORE, "studio_non_chiuso")
    assert avviso["messaggio"] == "Lo Studio di Luca non è stato chiuso: 60 min di attività"
    chiuso = _ok(_chiudi(client, None, studio={"chiave": "K1"}, ts_device=_ms(alle_22 + timedelta(minutes=62))))
    assert (chiuso["chiusura"], chiuso["minuti_alla_chiusura"]) == ("figlio", 60)


# --- 8. studio_non_partito solo per chi ha un telefono ---

def test_un_figlio_senza_telefono_non_fa_partire_avvisi(client, orologio):
    """Sara ha solo il computer: la sua partenza e' saltata (nessuno potrebbe chiudere lo
    Studio), ma ai genitori non arriva «il telefono non e' aggiornato alla 0.18»: un
    telefono non c'e'."""
    sara = nuovo_figlio(client, "Sara")
    pc_sara, _ = dispositivo_abbinato(client, sara["id"], "Computer di Sara", "computer")
    _battito(client, "0.18.0", pc_sara)
    proposta = _ok(client.patch("/api/studio/config", json=LISTE, headers=pc_sara))
    _ok(client.post("/api/studio/config/risposta", json={"esito": "approva", "versione": proposta["versione"],
                                                          "figlio_id": sara["id"]}, headers=GENITORE))
    orologio.vai_a(PARTENZA + timedelta(minutes=5))
    _ok(client.get("/api/famiglia", headers=GENITORE))
    assert _notifiche(client, GENITORE, "studio_non_partito") == []
    assert _studio(client, GENITORE, figlio_id=sara["id"])["in_corso"] is None
    (riga,) = _sql("SELECT esito FROM studio_partenze WHERE figlio_id = ?", sara["id"])
    assert riga["esito"] == "saltata"


# --- 9. i tratti senza l'ora agganciata ---

def test_due_telefoni_senza_aggancio_non_contano_due_volte(client, pronto, orologio):
    """Due telefoni riavviati senza rete col timer acceso insieme, 15:00-15:30: 30 minuti,
    non 60 (l'eccezione all'unione vale solo sullo stesso telefono)."""
    tablet, _ = _tablet(client)
    orologio.vai_a(PARTENZA + timedelta(minutes=40))
    assert _studio(client)["in_corso"] is not None
    uno = _ok(_tratti(client, _tratto("a", PARTENZA, PARTENZA + timedelta(minutes=30), agganciata=False)))
    due = _ok(_tratti(client, _tratto("b", PARTENZA, PARTENZA + timedelta(minutes=30), agganciata=False),
                      headers=tablet))
    assert (uno["tratti"][0]["conta"], due["tratti"][0]["conta"]) == (True, False)
    assert _studio(client)["in_corso"]["minuti_attivita"] == 30
    # e fra un tratto agganciato del tablet e uno senza aggancio del telefono: l'unione
    _ok(_tratti(client, _tratto("c", PARTENZA + timedelta(minutes=20), PARTENZA + timedelta(minutes=35)),
                headers=tablet))
    assert _studio(client)["in_corso"]["minuti_attivita"] == 35


# --- 11. il minimo dello Studio a mano e' quello in vigore quel giorno ---

def test_l_avvio_a_mano_usa_il_minimo_in_vigore_quel_giorno(client, pronto, orologio):
    """Mercoledi' alle 10:00 di Roma Mamma approva un minimo di 30 minuti (vale da
    giovedi'). Lo Studio a mano di mercoledi' chiede ancora 60, come quello automatico, e
    alle 23:15 e' troppo tardi (45 minuti a mezzanotte)."""
    orologio.vai_a(datetime(2026, 7, 15, 8, 0, tzinfo=UTC))
    proposta = _ok(client.patch("/api/studio/config", json={"minuti_minimi": 30}, headers=FIGLIO))
    approvata = _ok(client.post("/api/studio/config/risposta",
                                json={"esito": "approva", "versione": proposta["versione"]}, headers=pronto.mamma))
    assert approvata["approvata"]["orari_dal"] == "2026-07-16"
    orologio.vai_a(datetime(2026, 7, 15, 10, 0, tzinfo=UTC))
    studio = _ok(_avvia(client, chiave="K1"), 201)
    assert studio["minuti_minimi"] == 60
    orologio.vai_a(datetime(2026, 7, 15, 14, 0, tzinfo=UTC))
    _ok(client.post(f"/api/studio/{studio['id']}/chiudi", json={"motivo": "visita medica"}, headers=GENITORE))
    orologio.vai_a(datetime(2026, 7, 15, 21, 15, tzinfo=UTC))
    rifiuto = _avvia(client, chiave="K2")
    assert rifiuto.status_code == 409 and rifiuto.json()["detail"] == {"errore": "troppo_tardi"}
    # giovedi' vale il minimo nuovo
    orologio.vai_a(datetime(2026, 7, 16, 8, 0, tzinfo=UTC))
    assert _ok(_avvia(client, chiave="K3"), 201)["minuti_minimi"] == 30


# --- 13. prima la chiusura consegnata, poi la mezzanotte ---

def test_la_chiusura_e_la_mezzanotte_nella_stessa_richiesta(client, pronto, orologio):
    """Nessuna richiesta dopo mezzanotte: la prima e' la chiusura senza rete delle 16:40.
    Prima vale la chiusura: l'avviso dice «(chiusa senza rete alle 16:40)», senza
    «arrivata dopo», e l'avviso «non e' stato chiuso» non nasce."""
    orologio.vai_a(PARTENZA + timedelta(minutes=5))
    studio = _studio(client)["in_corso"]
    orologio.vai_a(MEZZANOTTE + timedelta(hours=8))
    chiusa_alle = datetime(2026, 7, 15, 14, 40, tzinfo=UTC)
    chiuso = _ok(_chiudi(client, studio["id"], ts_device=_ms(chiusa_alle),
                         tratti=[_tratto("t1", PARTENZA, PARTENZA + timedelta(minutes=65))]))
    assert (chiuso["chiusura"], chiuso["fine_ts"], chiuso["minuti_alla_chiusura"]) == (
        "figlio", "2026-07-15T14:40:00+00:00", 65)
    for headers in (GENITORE, pronto.mamma):
        assert _notifiche(client, headers, "studio_non_chiuso") == []
        (avviso,) = _notifiche(client, headers, "studio_chiuso")
        assert avviso["messaggio"].endswith("(chiusa senza rete alle 16:40)")


# --- 14 e 15. l'avviso della chiusura e i minuti di uno Studio chiuso ---

def test_l_avviso_elenca_solo_i_tratti_coi_minuti_che_contano(client, pronto, orologio):
    """Studio a mano alle 14:00 di Roma che assorbe la partenza delle 15:00: i compiti
    14:05-14:50 contano per la regola ma non nel totale (prima delle 15:00). L'avviso
    elenca solo l'allenamento, coi suoi minuti contati."""
    alle_14 = datetime(2026, 7, 15, 12, 0, tzinfo=UTC)
    orologio.vai_a(alle_14)
    studio = _ok(_avvia(client, chiave="K1"), 201)
    orologio.vai_a(alle_14 + timedelta(minutes=50))
    _ok(_tratti(client, _tratto("c1", alle_14 + timedelta(minutes=5), alle_14 + timedelta(minutes=50))))
    orologio.vai_a(CHIUDIBILE + timedelta(minutes=10))
    _ok(_tratti(client, _tratto("a1", PARTENZA, CHIUDIBILE + timedelta(minutes=5), tipo="altro",
                                parola="allenamento")))
    chiuso = _ok(_chiudi(client, studio["id"]))
    assert chiuso["minuti_alla_chiusura"] == 65
    tratti = {t["id"]: (t["conta"], t["secondi_contati"]) for t in chiuso["tratti"]}
    assert tratti == {"c1": (True, 0), "a1": (True, 3900)}
    (avviso,) = _notifiche(client, GENITORE, "studio_chiuso")
    assert "65 min — allenamento." in avviso["messaggio"] and "compiti" not in avviso["messaggio"]
    assert avviso["payload"]["tratti"] == [{"tipo": "altro", "parola": "allenamento", "minuti": 65}]
    assert avviso["payload"]["minuti_attivita"] == 65


def test_uno_studio_chiuso_mostra_i_minuti_congelati(client, pronto, orologio):
    """Il genitore chiude alle 15:20 con 15 minuti; il tratto in corso arriva finito alle
    15:30: resta nel registro (conta fino alla chiusura), ma i minuti dello Studio chiuso
    restano quelli della chiusura, come nella notifica e nello storico."""
    orologio.vai_a(PARTENZA + timedelta(minutes=16))
    studio = _studio(client)["in_corso"]
    _ok(_tratti(client, _tratto("t1", PARTENZA, PARTENZA + timedelta(minutes=15))))
    _ok(_tratti(client, {"id": "t2", "tipo": "compiti", "inizio": _ms(PARTENZA + timedelta(minutes=15)),
                         "ora_agganciata": True, "secondi": 0, "esito": "in_corso"}))
    orologio.vai_a(PARTENZA + timedelta(minutes=20))
    _ok(client.post(f"/api/studio/{studio['id']}/chiudi", json={"motivo": "visita medica"}, headers=GENITORE))
    orologio.vai_a(PARTENZA + timedelta(minutes=30))
    (t2,) = _ok(_tratti(client, _tratto("t2", PARTENZA + timedelta(minutes=15),
                                        PARTENZA + timedelta(minutes=24))))["tratti"]
    assert (t2["conta"], t2["secondi_contati"]) == (True, 300)
    (chiuso,) = _studio(client)["recenti"]
    assert (chiuso["minuti_attivita"], chiuso["minuti_alla_chiusura"]) == (15, 15)


# --- 17. il telefono appena aggiornato, prima del suo battito ---

def test_l_avvio_dal_telefono_prima_del_battito_della_018(client, famiglia, orologio):
    """Il telefono si aggiorna alla 0.18 e Luca avvia lo Studio prima del primo battito
    con la versione nuova: solo un'app dalla 0.18 conosce /api/studio, quindi la chiamata
    stessa lo prova. Lo Studio si vede subito, anche dal computer."""
    _battito(client, "0.17.0")
    _battito(client, "0.18.0", famiglia.pc)
    proposta = _ok(client.patch("/api/studio/config", json=LISTE, headers=famiglia.pc))
    _ok(client.post("/api/studio/config/risposta", json={"esito": "approva", "versione": proposta["versione"]},
                    headers=GENITORE))
    orologio.vai_a(MARTEDI_17)
    studio = _ok(_avvia(client, chiave="K1"), 201)
    patto = _ok(client.get("/api/patto", headers=famiglia.pc))
    assert patto["studio"]["in_corso"]["id"] == studio["id"]
    assert _blocco(client, famiglia.pc)["studio"]["in_corso"] is True
