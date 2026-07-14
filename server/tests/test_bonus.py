"""Tetti bonus: 30 minuti al giorno, 90 alla settimana (default nel patto).
La settimana e' quella ISO (lunedi'-domenica) in UTC; il clock dei test
parte martedi' 14/07/2026."""

from datetime import datetime, timezone

from conftest import FIGLIO, GENITORE

def _bonus(client, minuti, motivo=None):
    corpo = {"minuti": minuti}
    if motivo:
        corpo["motivo"] = motivo
    return client.post("/api/bonus", json=corpo, headers=FIGLIO)


def test_bonus_valido_e_residui(client):
    risposta = _bonus(client, 15, motivo="serie con gli amici")
    assert risposta.status_code == 201
    assert risposta.json() == {"minuti": 15, "residuo_giorno": 15, "residuo_settimana": 75}


def test_minuti_non_ammessi_422(client):
    assert _bonus(client, 10).status_code == 422
    assert _bonus(client, 0).status_code == 422
    assert _bonus(client, 45).status_code == 422


def test_tetto_giornaliero_al_limite_esatto(client):
    assert _bonus(client, 30).status_code == 201  # esattamente il tetto: passa
    risposta = _bonus(client, 5)
    assert risposta.status_code == 409
    dettaglio = risposta.json()["detail"]
    assert dettaglio["errore"] == "tetto_superato"
    assert dettaglio["residuo_giorno"] == 0
    assert dettaglio["residuo_settimana"] == 60


def test_richiesta_oltre_il_residuo_parziale(client):
    assert _bonus(client, 15).status_code == 201
    assert _bonus(client, 5).status_code == 201
    risposta = _bonus(client, 15)  # residuo giorno = 10
    assert risposta.status_code == 409
    assert risposta.json()["detail"]["residuo_giorno"] == 10


def test_il_giorno_dopo_il_tetto_giornaliero_riparte(client, orologio):
    assert _bonus(client, 30).status_code == 201
    assert _bonus(client, 5).status_code == 409
    orologio.avanza(days=1)
    risposta = _bonus(client, 5)
    assert risposta.status_code == 201
    assert risposta.json()["residuo_giorno"] == 25
    assert risposta.json()["residuo_settimana"] == 55  # la settimana non si azzera


def test_cavallo_di_mezzanotte(client, orologio):
    orologio.vai_a(datetime(2026, 7, 14, 23, 50, 0, tzinfo=timezone.utc))
    assert _bonus(client, 30).status_code == 201
    orologio.avanza(minutes=20)  # 00:10 del giorno dopo
    assert _bonus(client, 5).status_code == 201


def test_tetto_settimanale(client, orologio):
    for _ in range(3):  # mar + mer + gio = 90 minuti
        assert _bonus(client, 30).status_code == 201
        orologio.avanza(days=1)
    risposta = _bonus(client, 5)  # venerdi': giorno libero, settimana piena
    assert risposta.status_code == 409
    dettaglio = risposta.json()["detail"]
    assert dettaglio["residuo_settimana"] == 0
    assert dettaglio["residuo_giorno"] == 30


def test_lunedi_la_settimana_riparte(client, orologio):
    for _ in range(3):
        assert _bonus(client, 30).status_code == 201
        orologio.avanza(days=1)
    assert _bonus(client, 5).status_code == 409
    orologio.vai_a(datetime(2026, 7, 20, 8, 0, 0, tzinfo=timezone.utc))  # lunedi'
    risposta = _bonus(client, 30)
    assert risposta.status_code == 201
    assert risposta.json()["residuo_settimana"] == 60


def test_bonus_genera_notifica_al_genitore(client):
    _bonus(client, 5, motivo="dieci minuti in piu' di musica")
    notifiche = client.get("/api/notifiche", headers=GENITORE).json()["notifiche"]
    di_bonus = [n for n in notifiche if n["tipo"] == "bonus"]
    assert len(di_bonus) == 1
    assert di_bonus[0]["payload"]["minuti"] == 5


def test_bonus_rifiutato_non_genera_notifica(client):
    assert _bonus(client, 30).status_code == 201
    assert _bonus(client, 30).status_code == 409
    notifiche = client.get("/api/notifiche", headers=GENITORE).json()["notifiche"]
    assert len([n for n in notifiche if n["tipo"] == "bonus"]) == 1
