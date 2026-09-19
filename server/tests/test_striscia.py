"""(v2.4) La striscia aggregata del patto e il "niente verde senza dati".

La striscia: 8 voci {data, stato}, gli stessi giorni del semaforo, ricavata dai
semafori di TUTTE le regole (eliminate comprese, solo nei giorni in cui erano in
vita): rosso se almeno una e' rossa, altrimenti verde se almeno una e' verde,
altrimenti grigio. Esce dalla stessa funzione in GET /api/finestra e GET /api/patto.

Niente verde senza dati: per limite_tempo e fascia_oraria un giorno senza
sforamenti e' verde solo se ha una fotografia uso_giornaliero; il rosso resta rosso
anche senza. Le vita_reale non cambiano."""

import sqlite3
from datetime import datetime, timezone

import pytest

from conftest import FIGLIO, GENITORE, crea_regola, fotografia_uso

TUTTI_I_GIORNI = ["lun", "mar", "mer", "gio", "ven", "sab", "dom"]
FASCIA = {"dalle": "22:00", "alle": "07:00", "giorni": TUTTI_I_GIORNI}
VITA = {"descrizione": "Cammino", "arbitro_nome": "Mamma", "frequenza": "ogni giorno"}
YOUTUBE = {"app_o_categoria": "YouTube", "minuti_al_giorno": 120}


def _finestra(client):
    risposta = client.get("/api/finestra", headers=GENITORE)
    assert risposta.status_code == 200
    return risposta.json()


def _patto(client):
    risposta = client.get("/api/patto", headers=FIGLIO)
    assert risposta.status_code == 200
    return risposta.json()


def _striscia(client):
    return {v["data"]: v["stato"] for v in _finestra(client)["striscia"]}


def _semaforo(client, regola_id):
    regola = [r for r in _finestra(client)["regole"] if r["id"] == regola_id][0]
    return {v["data"]: v["stato"] for v in regola["semaforo"]}


def _sforamento(client, evento_id, regola_id, **extra):
    dettagli = {"regola_id": regola_id, **extra}
    risposta = client.post(
        "/api/eventi",
        json={"eventi": [{"id": evento_id, "tipo": "sforamento", "dettagli": dettagli}]},
        headers=FIGLIO,
    )
    assert risposta.status_code == 200


def _conferma_oggi(client, regola_id):
    dic = client.post(
        "/api/dichiarazioni", json={"regola_id": regola_id, "esito": "successo"}, headers=FIGLIO
    ).json()
    client.post(
        f"/api/dichiarazioni/{dic['id']}/verdetto", json={"verdetto": "conferma"}, headers=GENITORE
    )


# --- niente verde senza dati (semaforo per regola) ---

def test_giorno_senza_fotografia_grigio(client):
    regola = crea_regola(client)
    assert _semaforo(client, regola["id"])["2026-07-14"] == "grigio"
    assert _striscia(client)["2026-07-14"] == "grigio"


def test_giorno_con_fotografia_verde(client):
    regola = crea_regola(client)
    fotografia_uso(client, "2026-07-14")
    assert _semaforo(client, regola["id"])["2026-07-14"] == "verde"
    assert _striscia(client)["2026-07-14"] == "verde"


def test_fotografia_a_zero_minuti_e_un_dato(client):
    """Uso zero minuti e' una fotografia reale: il giorno e' mantenuto, non vuoto."""
    regola = crea_regola(client)
    fotografia_uso(client, "2026-07-14", totale_minuti=0)
    assert _semaforo(client, regola["id"])["2026-07-14"] == "verde"


def test_sforamento_senza_fotografia_resta_rosso(client):
    regola = crea_regola(client)
    _sforamento(client, "s-senza-foto", regola["id"])
    assert _semaforo(client, regola["id"])["2026-07-14"] == "rosso"
    assert _striscia(client)["2026-07-14"] == "rosso"


def test_fotografia_di_un_altro_giorno_non_basta(client, orologio):
    regola = crea_regola(client)
    fotografia_uso(client, "2026-07-14")
    orologio.avanza(days=1)  # oggi = 15/07, nessuna fotografia per oggi
    semaforo = _semaforo(client, regola["id"])
    assert semaforo["2026-07-14"] == "verde"
    assert semaforo["2026-07-15"] == "grigio"


def test_fascia_oraria_stessa_regola(client, orologio):
    regola = crea_regola(client, tipo="fascia_oraria", parametri=FASCIA)
    fotografia_uso(client, "2026-07-14")
    orologio.avanza(days=1)  # 15/07: sforamento senza fotografia
    _sforamento(client, "s-fascia", regola["id"])
    orologio.avanza(days=1)  # oggi = 16/07: niente di niente
    semaforo = _semaforo(client, regola["id"])
    assert semaforo["2026-07-14"] == "verde"
    assert semaforo["2026-07-15"] == "rosso"
    assert semaforo["2026-07-16"] == "grigio"


def test_la_fotografia_dei_siti_non_e_un_dato_d_uso(client):
    """Il contratto chiede una fotografia uso_giornaliero: quella dei siti non basta."""
    regola = crea_regola(client)
    client.post(
        "/api/eventi",
        json={"eventi": [{
            "id": "solo-siti",
            "tipo": "siti_giornalieri",
            "dettagli": {"giorno": "2026-07-14", "domini": {"instagram.com": 3}},
        }]},
        headers=FIGLIO,
    )
    assert _semaforo(client, regola["id"])["2026-07-14"] == "grigio"


def test_vita_reale_non_cambia_senza_fotografia(client, db_path):
    crea_regola(client)  # la vita_reale non deve restare sola
    vita = crea_regola(client, tipo="vita_reale", parametri=VITA)
    _conferma_oggi(client, vita["id"])
    conn = sqlite3.connect(db_path)
    assert conn.execute("SELECT COUNT(*) FROM uso_giornaliero").fetchone()[0] == 0
    conn.close()
    assert _semaforo(client, vita["id"])["2026-07-14"] == "verde"


# --- striscia: forma e aggregazione ---

def test_forma_della_striscia(client):
    crea_regola(client)
    finestra = _finestra(client)
    striscia = finestra["striscia"]
    assert len(striscia) == 8
    assert [v["data"] for v in striscia] == [v["data"] for v in finestra["regole"][0]["semaforo"]]
    assert striscia[0]["data"] == "2026-07-07"
    assert striscia[-1]["data"] == "2026-07-14"  # oggi in coda
    assert all(set(v.keys()) == {"data", "stato"} for v in striscia)
    assert {v["stato"] for v in striscia} <= {"verde", "rosso", "grigio"}
    assert len(_patto(client)["striscia"]) == 8


def test_striscia_senza_regole_tutta_grigia(client):
    fotografia_uso(client, "2026-07-13", "2026-07-14")  # i dati da soli non bastano
    assert set(_striscia(client).values()) == {"grigio"}
    assert {v["stato"] for v in _patto(client)["striscia"]} == {"grigio"}


def test_striscia_rossa_se_almeno_una_regola_e_rossa(client):
    tiktok = crea_regola(client)
    youtube = crea_regola(client, parametri=YOUTUBE)
    fotografia_uso(client, "2026-07-14")
    _sforamento(client, "s-youtube", youtube["id"])
    assert _semaforo(client, tiktok["id"])["2026-07-14"] == "verde"
    assert _striscia(client)["2026-07-14"] == "rosso"


def test_striscia_verde_se_almeno_una_verde_e_nessuna_rossa(client):
    limite = crea_regola(client)  # senza fotografia: grigia
    vita = crea_regola(client, tipo="vita_reale", parametri=VITA)
    _conferma_oggi(client, vita["id"])
    assert _semaforo(client, limite["id"])["2026-07-14"] == "grigio"
    assert _striscia(client)["2026-07-14"] == "verde"


def _storia_con_eliminazione(client, orologio):
    """YouTube nasce il 14/07, sfora il 15, viene eliminata il 18; TikTok nasce il
    16 e resta. Il 19 arriva uno sforamento di YouTube senza `giorno`: cade il 19,
    quando YouTube e' gia' eliminata.
    Fotografie su tutti i giorni, cosi' ogni grigio viene dalla vita delle regole.
    Oggi = 20/07, finestra 13..20."""
    fotografia_uso(client, "2026-07-13", "2026-07-14", "2026-07-15")
    youtube = crea_regola(client, parametri=YOUTUBE)  # 14/07 10:00 UTC
    orologio.avanza(days=1)  # 15/07
    _sforamento(client, "s-youtube-15", youtube["id"])
    orologio.avanza(days=1)  # 16/07
    tiktok = crea_regola(client)
    fotografia_uso(client, "2026-07-16", "2026-07-17", "2026-07-18")
    orologio.avanza(days=2)  # 18/07 10:00: lock di YouTube scaduto
    assert client.delete(f"/api/regole/{youtube['id']}", headers=FIGLIO).status_code == 200
    orologio.avanza(days=1)  # 19/07
    _sforamento(client, "s-youtube-19", youtube["id"])
    fotografia_uso(client, "2026-07-19", "2026-07-20")
    orologio.avanza(days=1)  # oggi = 20/07
    return youtube, tiktok


def test_regola_eliminata_conta_da_viva_e_non_dopo(client, orologio):
    youtube, tiktok = _storia_con_eliminazione(client, orologio)
    assert _semaforo(client, youtube["id"])["2026-07-19"] == "grigio"  # fuori dalla sua vita
    assert _semaforo(client, tiktok["id"])["2026-07-14"] == "grigio"  # non ancora nata
    assert _striscia(client) == {
        "2026-07-13": "grigio",  # nessuna regola in vita, anche coi dati
        "2026-07-14": "verde",   # c'era solo YouTube: l'eliminata conta
        "2026-07-15": "rosso",   # lo sforamento di YouTube da viva
        "2026-07-16": "verde",
        "2026-07-17": "verde",
        "2026-07-18": "verde",   # il giorno dell'eliminazione YouTube e' ancora viva
        "2026-07-19": "verde",   # lo sforamento arrivato dopo l'eliminazione non conta
        "2026-07-20": "verde",
    }


def test_striscia_identica_tra_finestra_e_patto(client, orologio):
    """Stessi dati, stessa funzione: le due app vedono la stessa striscia voce per voce."""
    _storia_con_eliminazione(client, orologio)
    vita = crea_regola(client, tipo="vita_reale", parametri=VITA)
    client.post(
        "/api/dichiarazioni", json={"regola_id": vita["id"], "esito": "fallimento"}, headers=FIGLIO
    )
    della_finestra = _finestra(client)["striscia"]
    del_patto = _patto(client)["striscia"]
    assert della_finestra == del_patto
    assert {v["stato"] for v in del_patto} == {"verde", "rosso", "grigio"}  # confronto non banale
    assert del_patto[-1] == {"data": "2026-07-20", "stato": "rosso"}  # il fallimento di oggi


# --- (v2.4) sforamento consegnato in ritardo: cade nel suo `giorno` ---

def _ritardo(client, orologio, **extra):
    """Regola nata il 14/07, fotografie il 14 e il 15; lo sforamento arriva il 15."""
    regola = crea_regola(client)
    fotografia_uso(client, "2026-07-14", "2026-07-15")
    orologio.avanza(days=1)  # oggi = 15/07
    _sforamento(client, "s-ritardo", regola["id"], **extra)
    return regola


def test_sforamento_in_ritardo_cade_nel_suo_giorno(client, orologio):
    regola = _ritardo(client, orologio, giorno="2026-07-14")
    semaforo = _semaforo(client, regola["id"])
    assert semaforo["2026-07-14"] == "rosso"  # ieri, anche se ieri c'e' la fotografia
    assert semaforo["2026-07-15"] == "verde"  # NON oggi, giorno dell'arrivo
    striscia = _striscia(client)
    assert striscia["2026-07-14"] == "rosso"
    assert striscia["2026-07-15"] == "verde"


@pytest.mark.parametrize(
    "extra",
    [{}, {"giorno": "2026-7-14"}, {"giorno": "2026-02-30"}, {"giorno": "ieri"},
     {"giorno": 20260714}, {"giorno": None}],
    ids=["assente", "non_canonico", "impossibile", "testo", "numero", "null"],
)
def test_sforamento_senza_giorno_valido_cade_all_arrivo(client, orologio, extra):
    regola = _ritardo(client, orologio, **extra)
    semaforo = _semaforo(client, regola["id"])
    assert semaforo["2026-07-14"] == "verde"
    assert semaforo["2026-07-15"] == "rosso"  # come prima: il giorno in cui e' arrivato
    assert _striscia(client)["2026-07-15"] == "rosso"


def test_giorno_dello_sforamento_e_quello_del_telefono_non_dell_arrivo(client, orologio):
    """Arriva alle 01:30 locali del 15 (23:30 UTC del 14) ma dichiara il 14, come
    una fascia che scavalca la mezzanotte ancorata al giorno in cui parte."""
    regola = crea_regola(client, tipo="fascia_oraria", parametri=FASCIA)
    fotografia_uso(client, "2026-07-14", "2026-07-15")
    orologio.vai_a(datetime(2026, 7, 14, 23, 30, 0, tzinfo=timezone.utc))
    _sforamento(client, "s-notte", regola["id"], giorno="2026-07-14")
    semaforo = _semaforo(client, regola["id"])
    assert semaforo["2026-07-14"] == "rosso"
    assert semaforo["2026-07-15"] == "verde"


def test_sforamento_in_ritardo_identico_in_finestra_e_patto(client, orologio):
    _ritardo(client, orologio, giorno="2026-07-14")
    della_finestra = _finestra(client)["striscia"]
    assert della_finestra == _patto(client)["striscia"]
    assert {"data": "2026-07-14", "stato": "rosso"} in della_finestra


def test_sforamento_in_ritardo_di_una_regola_poi_eliminata(client, orologio):
    """Arriva dopo l'eliminazione ma dichiara un giorno in cui la regola era viva:
    conta in quel giorno."""
    fotografia_uso(client, "2026-07-14", "2026-07-15", "2026-07-18", "2026-07-19")
    crea_regola(client)  # resta
    youtube = crea_regola(client, parametri=YOUTUBE)
    orologio.avanza(days=4)  # 18/07 10:00: lock scaduto
    assert client.delete(f"/api/regole/{youtube['id']}", headers=FIGLIO).status_code == 200
    orologio.avanza(days=1)  # oggi = 19/07
    _sforamento(client, "s-postumo", youtube["id"], giorno="2026-07-15")
    assert _semaforo(client, youtube["id"])["2026-07-15"] == "rosso"
    striscia = _striscia(client)
    assert striscia["2026-07-15"] == "rosso"
    assert striscia["2026-07-19"] == "verde"
