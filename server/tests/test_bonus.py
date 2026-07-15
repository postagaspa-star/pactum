"""Tetti bonus: 30 minuti al giorno, 90 alla settimana (default nel patto).
Giorno e settimana ISO (lunedi'-domenica) si contano nel fuso del patto
(default Europe/Rome, in luglio = UTC+2); il clock dei test parte
martedi' 14/07/2026 alle 10:00 UTC (12:00 locali)."""

import threading
from datetime import datetime, timezone

from conftest import FIGLIO, GENITORE

def _bonus(client, minuti, motivo=None):
    corpo = {"minuti": minuti}
    if motivo:
        corpo["motivo"] = motivo
    return client.post("/api/bonus", json=corpo, headers=FIGLIO)


def test_bonus_valido_e_residui(client):
    risposta = _bonus(client, 15, motivo="serie con gli amici")
    assert risposta.status_code == 200
    assert risposta.json() == {"minuti": 15, "residuo_giorno": 15, "residuo_settimana": 75}


def test_minuti_non_ammessi_422(client):
    assert _bonus(client, 10).status_code == 422
    assert _bonus(client, 0).status_code == 422
    assert _bonus(client, 45).status_code == 422


def test_tetto_giornaliero_al_limite_esatto(client):
    assert _bonus(client, 30).status_code == 200  # esattamente il tetto: passa
    risposta = _bonus(client, 5)
    assert risposta.status_code == 409
    dettaglio = risposta.json()["detail"]
    assert dettaglio["errore"] == "tetto_superato"
    assert dettaglio["residuo_giorno"] == 0
    assert dettaglio["residuo_settimana"] == 60


def test_richiesta_oltre_il_residuo_parziale(client):
    assert _bonus(client, 15).status_code == 200
    assert _bonus(client, 5).status_code == 200
    risposta = _bonus(client, 15)  # residuo giorno = 10
    assert risposta.status_code == 409
    assert risposta.json()["detail"]["residuo_giorno"] == 10


def test_il_giorno_dopo_il_tetto_giornaliero_riparte(client, orologio):
    assert _bonus(client, 30).status_code == 200
    assert _bonus(client, 5).status_code == 409
    orologio.avanza(days=1)
    risposta = _bonus(client, 5)
    assert risposta.status_code == 200
    assert risposta.json()["residuo_giorno"] == 25
    assert risposta.json()["residuo_settimana"] == 55  # la settimana non si azzera


def test_cavallo_di_mezzanotte_locale(client, orologio):
    # 21:50 UTC = 23:50 locali (Europe/Rome, luglio = UTC+2)
    orologio.vai_a(datetime(2026, 7, 14, 21, 50, 0, tzinfo=timezone.utc))
    assert _bonus(client, 30).status_code == 200
    orologio.avanza(minutes=20)  # 00:10 locali del giorno dopo
    assert _bonus(client, 5).status_code == 200


def test_il_tetto_non_si_azzera_alle_2_di_notte_locali(client, orologio):
    """Il bucket del giorno e' LOCALE: col bucket UTC il tetto si azzerava alle
    02:00 locali (00:00 UTC) e la notte raddoppiava i minuti disponibili."""
    # 00:30 locali del 15/07 = 22:30 UTC del 14/07: e' gia' il giorno locale 15
    orologio.vai_a(datetime(2026, 7, 14, 22, 30, 0, tzinfo=timezone.utc))
    assert _bonus(client, 30).status_code == 200  # esaurisce il tetto del 15 locale
    orologio.avanza(hours=2)  # 02:30 locali = 00:30 UTC del 15: il giorno UTC e' scattato
    assert _bonus(client, 5).status_code == 409  # ...ma quello locale no: niente ricarica
    # il tetto riparte solo alla mezzanotte locale successiva
    orologio.vai_a(datetime(2026, 7, 15, 22, 30, 0, tzinfo=timezone.utc))  # 00:30 locali del 16
    assert _bonus(client, 5).status_code == 200


def test_tetto_settimanale(client, orologio):
    for _ in range(3):  # mar + mer + gio = 90 minuti
        assert _bonus(client, 30).status_code == 200
        orologio.avanza(days=1)
    risposta = _bonus(client, 5)  # venerdi': giorno libero, settimana piena
    assert risposta.status_code == 409
    dettaglio = risposta.json()["detail"]
    assert dettaglio["residuo_settimana"] == 0
    assert dettaglio["residuo_giorno"] == 30


def test_lunedi_la_settimana_riparte(client, orologio):
    for _ in range(3):
        assert _bonus(client, 30).status_code == 200
        orologio.avanza(days=1)
    assert _bonus(client, 5).status_code == 409
    orologio.vai_a(datetime(2026, 7, 20, 8, 0, 0, tzinfo=timezone.utc))  # lunedi'
    risposta = _bonus(client, 30)
    assert risposta.status_code == 200
    assert risposta.json()["residuo_settimana"] == 60


def test_bonus_genera_notifica_al_genitore(client):
    _bonus(client, 5, motivo="dieci minuti in piu' di musica")
    notifiche = client.get("/api/notifiche", headers=GENITORE).json()["notifiche"]
    di_bonus = [n for n in notifiche if n["tipo"] == "bonus"]
    assert len(di_bonus) == 1
    assert di_bonus[0]["payload"]["minuti"] == 5


def test_bonus_rifiutato_non_genera_notifica(client):
    assert _bonus(client, 30).status_code == 200
    assert _bonus(client, 30).status_code == 409
    notifiche = client.get("/api/notifiche", headers=GENITORE).json()["notifiche"]
    assert len([n for n in notifiche if n["tipo"] == "bonus"]) == 1


# --- concorrenza: leggi-controlla-inserisci deve essere atomico ---

def test_bonus_concorrenti_ne_passa_uno_solo(client):
    """12 richieste simultanee da 30 minuti contro un tetto giornaliero di 30:
    esattamente UNA deve passare. Senza transazione atomica tutte leggevano
    residuo=30 prima che la prima scrivesse, e passavano tutte (360 minuti)."""
    quante = 12
    barriera = threading.Barrier(quante)
    esiti = []

    def spara():
        barriera.wait()  # partenza simultanea
        esiti.append(_bonus(client, 30).status_code)

    thread = [threading.Thread(target=spara) for _ in range(quante)]
    for t in thread:
        t.start()
    for t in thread:
        t.join()
    assert sorted(esiti) == [200] + [409] * (quante - 1)
    # e i contatori riflettono UN solo bonus concesso
    bonus = client.get("/api/finestra", headers=GENITORE).json()["bonus"]
    assert bonus["giorno"] == {"usati": 30, "tetto": 30, "residui": 0}


def test_bonus_concorrenti_sotto_il_tetto_passano_tutti(client):
    """Il lock serializza ma non blocca il legittimo: 3 bonus da 5 in parallelo
    stanno tutti nel tetto (15 <= 30) e passano tutti."""
    quante = 3
    barriera = threading.Barrier(quante)
    esiti = []

    def spara():
        barriera.wait()
        esiti.append(_bonus(client, 5).status_code)

    thread = [threading.Thread(target=spara) for _ in range(quante)]
    for t in thread:
        t.start()
    for t in thread:
        t.join()
    assert esiti == [200, 200, 200]
    bonus = client.get("/api/finestra", headers=GENITORE).json()["bonus"]
    assert bonus["giorno"]["usati"] == 15
