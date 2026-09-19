"""(v2.4) POST /api/segno: il riconoscimento del genitore al figlio, a testo fisso,
al massimo uno al giorno nel fuso del patto (409 segno_gia_mandato), atomico anche
sotto richieste concorrenti. Diventa una notifica `segno` per il figlio con
payload {} e non entra nel registro eventi. `segno_oggi` nella finestra dice se
oggi e' gia' partito."""

import sqlite3
import threading
from datetime import datetime, timezone

from conftest import FIGLIO, GENITORE

MESSAGGIO = "Ho visto la settimana. Bene così."


def _segno(client, headers=GENITORE, **kwargs):
    return client.post("/api/segno", headers=headers, **kwargs)


def _segno_oggi(client):
    risposta = client.get("/api/finestra", headers=GENITORE)
    assert risposta.status_code == 200
    return risposta.json()["segno_oggi"]


def _notifiche(client, headers):
    return client.get("/api/notifiche", headers=headers).json()["notifiche"]


def _conta(db_path, sql):
    conn = sqlite3.connect(db_path)
    try:
        return conn.execute(sql).fetchone()[0]
    finally:
        conn.close()


def _quanti_segni(db_path):
    return _conta(db_path, "SELECT COUNT(*) FROM notifiche WHERE tipo = 'segno'")


def test_segno_mandato(client):
    risposta = _segno(client)
    assert risposta.status_code == 200
    assert risposta.json() == {"mandato": True, "ts_server": "2026-07-14T10:00:00+00:00"}


def test_segno_diventa_una_notifica_per_il_figlio(client):
    _segno(client)
    segni = [n for n in _notifiche(client, FIGLIO) if n["tipo"] == "segno"]
    assert len(segni) == 1
    assert segni[0]["destinatario"] == "figlio"
    assert segni[0]["messaggio"] == MESSAGGIO
    assert segni[0]["payload"] == {}
    assert segni[0]["ts_server"] == "2026-07-14T10:00:00+00:00"
    # e' del figlio: il genitore non la riceve e non la puo' marcare
    assert all(n["tipo"] != "segno" for n in _notifiche(client, GENITORE))
    assert client.post(f"/api/notifiche/{segni[0]['id']}/letta", headers=GENITORE).status_code == 404
    assert client.post(f"/api/notifiche/{segni[0]['id']}/letta", headers=FIGLIO).status_code == 200


def test_il_testo_non_lo_sceglie_il_genitore(client):
    """Nessun corpo per contratto: se l'app ne manda uno, il server lo ignora."""
    assert _segno(client, json={"messaggio": "Devi fare di piu'"}).status_code == 200
    segni = [n for n in _notifiche(client, FIGLIO) if n["tipo"] == "segno"]
    assert [n["messaggio"] for n in segni] == [MESSAGGIO]


def test_segno_due_volte_nello_stesso_giorno_409(client, orologio, db_path):
    assert _segno(client).status_code == 200
    orologio.avanza(hours=3)
    di_nuovo = _segno(client)
    assert di_nuovo.status_code == 409
    assert di_nuovo.json()["detail"] == {"errore": "segno_gia_mandato"}
    assert _quanti_segni(db_path) == 1


def test_segno_gia_letto_conta_ancora(client, db_path):
    """Marcare la notifica come letta non ricarica il segno del giorno."""
    _segno(client)
    notifica = [n for n in _notifiche(client, FIGLIO) if n["tipo"] == "segno"][0]
    client.post(f"/api/notifiche/{notifica['id']}/letta", headers=FIGLIO)
    assert _segno(client).status_code == 409
    assert _segno_oggi(client) is True
    assert _quanti_segni(db_path) == 1


def test_segno_il_giorno_dopo_di_nuovo_200(client, orologio, db_path):
    assert _segno(client).status_code == 200
    orologio.avanza(days=1)
    risposta = _segno(client)
    assert risposta.status_code == 200
    assert risposta.json()["ts_server"] == "2026-07-15T10:00:00+00:00"
    assert _quanti_segni(db_path) == 2


def test_segno_il_giorno_e_quello_del_patto(client, orologio):
    """In luglio Europe/Rome = UTC+2: alle 22:00 UTC del 14/07 per il patto e' gia'
    il 15, anche se in UTC e' ancora lo stesso giorno."""
    assert _segno(client).status_code == 200  # 14/07 12:00 locali
    orologio.vai_a(datetime(2026, 7, 14, 21, 59, 0, tzinfo=timezone.utc))  # 23:59 locali
    assert _segno(client).status_code == 409
    orologio.vai_a(datetime(2026, 7, 14, 22, 0, 0, tzinfo=timezone.utc))  # 00:00 del 15
    assert _segno(client).status_code == 200


def test_segno_col_token_figlio_403(client, db_path):
    assert _segno(client, headers=FIGLIO).status_code == 403
    assert _quanti_segni(db_path) == 0
    assert _segno_oggi(client) is False


def test_segno_non_entra_nel_registro_eventi(client, db_path):
    assert _segno(client).status_code == 200
    assert _conta(db_path, "SELECT COUNT(*) FROM eventi") == 0


def test_segni_concorrenti_ne_passa_uno_solo(client, db_path):
    """Sei tocchi simultanei sul pulsante: ne parte uno solo (200), gli altri
    rileggono dentro la transazione il segno gia' mandato (409)."""
    quante = 6
    barriera = threading.Barrier(quante)
    esiti = []

    def spara():
        barriera.wait()
        esiti.append(_segno(client).status_code)

    thread = [threading.Thread(target=spara) for _ in range(quante)]
    for t in thread:
        t.start()
    for t in thread:
        t.join()
    assert sorted(esiti) == [200] + [409] * (quante - 1)
    assert _quanti_segni(db_path) == 1


# --- segno_oggi nella finestra ---

def test_segno_oggi(client, orologio):
    assert _segno_oggi(client) is False
    _segno(client)
    assert _segno_oggi(client) is True
    _segno(client)  # il 409 non cambia niente
    assert _segno_oggi(client) is True
    orologio.avanza(days=1)
    assert _segno_oggi(client) is False  # giorno nuovo: il pulsante si riaccende
    _segno(client)
    assert _segno_oggi(client) is True
