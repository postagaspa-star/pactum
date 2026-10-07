"""(v3.6) Le faccende (contratto-api.md, "La faccenda" e seguenti): un genitore le da'
al figlio; da blocco_da, e finche' il figlio non le ha fatte tutte mandando una foto
per ognuna dal telefono, il blocco e' attivo.

- Dare faccende: controlli del corpo (titolo e nota come i nomi delle sessioni,
  blocco_da subito / passato / entro 7 giorni / con il fuso, 1-10 per volta, 20 da fare
  al massimo, figlio_id obbligatorio) e l'avviso a tutti i dispositivi del figlio.
- Il blocco: attivo, dal, prossimo, da_fare; uguale in GET /api/faccende/blocco, nel
  patto, nella finestra e (riassunto) nella famiglia. Niente sessioni col blocco attivo.
- La foto: solo dal telefono, solo JPEG fino a 4 MB (letta a pezzi: Content-Length non
  basta), senza i dati nascosti (APP1-APP15 e commenti tolti, il resto byte per byte),
  la stessa foto due volte non e' un errore; mai compressa all'uscita.
- Boccia entro 24 ore, annulla; sotto richieste simultanee ne passa una.
- La pulizia: foto di piu' di 30 giorni e file senza faccenda, all'avvio e ogni giorno."""

import asyncio
import logging
import os
import sqlite3
import threading
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from aiuti_v3 import auth, dispositivo_abbinato, nuovo_figlio
from conftest import FIGLIO, GENITORE

ORA = "2026-07-14T10:00:00+00:00"
MAMMA = {"id": 2, "nome": "Mamma"}
GENITORE_1 = {"id": 1, "nome": "Genitore"}
MEGA = 4 * 1024 * 1024
SCUOLA = ["eu.spaggiari.classevivafamiglia", "com.google.android.apps.classroom"]
# (v4.0) Il blocco di un figlio che non e' in Studio: niente rimandato, nessuno Studio.
SENZA_STUDIO = {"rimandato": False, "studio": {"in_corso": False, "id": None, "inizio_ts": None}}


# --- un JPEG fatto a mano, segmento per segmento ---

def _segmento(marcatore: int, dati: bytes) -> bytes:
    return bytes((0xFF, marcatore)) + (len(dati) + 2).to_bytes(2, "big") + dati


APP0 = _segmento(0xE0, b"JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00")
DQT = _segmento(0xDB, b"\x00" + bytes(range(1, 65)))
SOF0 = _segmento(0xC0, b"\x08\x00\x10\x00\x10\x01\x01\x11\x00")
DHT = _segmento(0xC4, b"\x00" + bytes([1] + [0] * 15) + b"\x00")
SOS = _segmento(0xDA, b"\x01\x01\x00\x00\x3f\x00")
# I dati compressi dell'immagine, con un FF 00 e un RST (che sono dati, non marcatori).
DATI = b"\x12\x34\xff\x00\x56\xff\xd0\x78\x9a"
EOI = b"\xff\xd9"

# I dati nascosti: EXIF con una posizione (finta), XMP, IPTC (APP13), un commento, un
# APP15, un JPGn (F0), le firme sbagliate degli APP che altrimenti restano (JFXX in APP0,
# MPF in APP2: l'indice delle seconde immagini).
EXIF = _segmento(0xE1, b"Exif\x00\x00MM\x00\x2a\x00\x00\x00\x08GPS:POSIZIONE-DI-PROVA")
XMP = _segmento(0xE1, b"http://ns.adobe.com/xap/1.0/\x00<x:xmpmeta>POSIZIONE-DI-PROVA</x:xmpmeta>")
IPTC = _segmento(0xED, b"Photoshop 3.0\x008BIM\x04\x04CITTA-DI-PROVA")
COMMENTO = _segmento(0xFE, b"scattata con il TELEFONO-DI-PROVA")
APP15 = _segmento(0xEF, b"ALTRO-DATO-NASCOSTO")
JPG0 = _segmento(0xF0, b"ESTENSIONE-NASCOSTA")
JFXX = _segmento(0xE0, b"JFXX\x00\x10MINIATURA-NASCOSTA")
MPF = _segmento(0xE2, b"MPF\x00MM\x00\x2aSECONDA-IMMAGINE")
# Quelli che restano perche' servono a mostrare l'immagine: i colori e la firma Adobe.
ICC = _segmento(0xE2, b"ICC_PROFILE\x00\x01\x01PROFILO-COLORI")
ADOBE = _segmento(0xEE, b"Adobe\x00\x64\x00\x00\x00\x00\x01")

PULITA = b"\xff\xd8" + APP0 + DQT + SOF0 + DHT + SOS + DATI + EOI


def jpeg(*nascosti: bytes, scan: bytes = b"") -> bytes:
    """Un JPEG con i segmenti `nascosti` tra APP0 e il resto (anche dopo DQT, come
    capita); `scan` allunga i dati compressi (senza FF: cosi' restano dati), per fare
    foto diverse o grandi."""
    return (b"\xff\xd8" + APP0 + b"".join(nascosti[:2]) + DQT + b"".join(nascosti[2:]) + SOF0 + DHT
            + SOS + DATI + scan + EOI)


@pytest.fixture
def famiglia(client):
    """Luca (figlio 1) col telefono del token d'ambiente e un computer; la mamma
    abbinata come genitore 2; Sara col suo telefono."""
    assert client.patch("/api/figli/1", json={"nome": "Luca"}, headers=GENITORE).status_code == 200
    pc, pc_id = dispositivo_abbinato(client, 1, "Computer", "computer")
    creato = client.post("/api/genitori", json={"nome": "Mamma"}, headers=GENITORE).json()
    mamma = auth(client.post("/api/abbina", json={"codice": creato["codice"], "tipo": "genitore"}).json()["token"])
    sara = nuovo_figlio(client, "Sara")
    tel_sara, _ = dispositivo_abbinato(client, sara["id"], "Telefono di Sara", "telefono")
    return SimpleNamespace(pc=pc, pc_id=pc_id, mamma=mamma, sara=sara["id"], tel_sara=tel_sara)


@pytest.fixture
def foto_di_prima(client, db_path):
    """(v4.0) Le foto di un test valgono come arrivate PRIMA della v4.0: la riga
    `faccende_approvazione_dal` va oltre ogni ora dei test. Per quelle la v4.0 non cambia
    niente (contratto, parte A: sbloccate alla foto, confermabili come riconoscimento,
    bocciabili entro 24 ore): i test della v3.9 sulla conferma restano veri per loro."""
    conn = sqlite3.connect(db_path)
    try:
        conn.execute("UPDATE patto SET valore = '9999-12-31T23:59:59+00:00'"
                     " WHERE chiave = 'faccende_approvazione_dal'")
        conn.commit()
    finally:
        conn.close()


def _ok(risposta, atteso=200):
    assert risposta.status_code == atteso, risposta.text
    return risposta.json()


def _dai(client, *titoli, headers=None, figlio_id=1, **altro):
    faccende = [t if isinstance(t, dict) else {"titolo": t} for t in (titoli or ("Lavatrice",))]
    corpo = {"figlio_id": figlio_id, "faccende": faccende, **altro}
    return client.post("/api/faccende", json=corpo, headers=headers or GENITORE)


def _date(client, *titoli, **altro) -> list[dict]:
    return _ok(_dai(client, *titoli, **altro), 201)["faccende"]


def _foto(client, faccenda_id, dati=None, headers=FIGLIO, params=None, **intestazioni):
    return client.put(
        f"/api/faccende/{faccenda_id}/foto",
        content=jpeg(EXIF) if dati is None else dati,
        headers={**headers, "Content-Type": "image/jpeg", **intestazioni},
        params=params,
    )


def _approva(client, faccenda_id, headers=None):
    """(v4.0) Un genitore approva la foto di un lavoro (la conferma col foto_ts che ha
    guardato): e' l'approvazione che sblocca, non la foto."""
    figli = [f["id"] for f in _ok(client.get("/api/famiglia", headers=GENITORE))["figli"]]
    (faccenda,) = [
        f for figlio_id in figli for f in _elenco(client, GENITORE, figlio_id=figlio_id)
        if f["id"] == faccenda_id
    ]
    return client.post(f"/api/faccende/{faccenda_id}/conferma", json={"foto_ts": faccenda["foto_ts"]},
                       headers=headers or GENITORE)


def _blocco(client, headers=FIGLIO) -> dict:
    return _ok(client.get("/api/faccende/blocco", headers=headers))


def _elenco(client, headers=FIGLIO, **query) -> list:
    return _ok(client.get("/api/faccende", headers=headers, params=query))["faccende"]


def _notifiche(client, headers, tipo=None) -> list:
    tutte = _ok(client.get("/api/notifiche", headers=headers))["notifiche"]
    return [n for n in tutte if tipo is None or n["tipo"] == tipo]


def _cartella(db_path) -> Path:
    return Path(db_path).parent / "foto"


def _errore(risposta, stato, errore):
    assert risposta.status_code == stato, risposta.text
    assert risposta.json()["detail"] == {"errore": errore}


# --- dare faccende ---

def test_dare_faccende(client, famiglia):
    date = _date(client, {"titolo": "  Svuota la lavastoviglie ", "nota": "anche le pentole"}, "Letto",
                 headers=famiglia.mamma)
    assert [f["titolo"] for f in date] == ["Svuota la lavastoviglie", "Letto"]  # nell'ordine mandato
    assert date[0] == {
        "id": date[0]["id"], "figlio_id": 1, "titolo": "Svuota la lavastoviglie", "nota": "anche le pentole",
        "stato": "da_fare", "blocco_da": ORA, "creata_ts": ORA, "creata_da": MAMMA,
        "foto_ts": None, "foto": False, "bocciature": 0, "ultima_bocciatura": None,
        "chiusa_ts": None, "annullata_da": None,
        "confermata_ts": None, "confermata_da": None,  # (v3.9)
        "da_approvare": False,  # (v4.0)
        "storia": [{"tipo": "data", "ts": ORA, "genitore": MAMMA}],
    }
    # l'avviso arriva a tutti i dispositivi di Luca, e a nessun altro
    for headers in (FIGLIO, famiglia.pc):
        (avviso,) = _notifiche(client, headers, "nuove_faccende")
        assert avviso["messaggio"] == "Mamma ti ha dato 2 lavori di casa"  # (v3.7)
        assert avviso["payload"] == {"faccenda_ids": [f["id"] for f in date], "blocco_da": ORA, "genitore": MAMMA}
        assert (avviso["destinatario"], avviso["figlio_id"], avviso["dispositivo_id"]) == ("figlio", 1, None)
    assert _notifiche(client, famiglia.tel_sara, "nuove_faccende") == []
    (una,) = _date(client, "Porta fuori il cane")
    assert _notifiche(client, FIGLIO, "nuove_faccende")[-1]["messaggio"] == (
        "Genitore ti ha dato un lavoro di casa: «Porta fuori il cane»"
    )
    assert una["creata_da"] == GENITORE_1


def test_titolo_e_nota(client, famiglia):
    # (gli spazi e gli a capo ai bordi si tolgono, come nei nomi: "Letto\n" e' "Letto")
    for titolo in ("", "   ", "x" * 81, "Let\nto", "Let​to", "Letto‮", "Let\tto"):
        assert _dai(client, titolo).status_code == 422, repr(titolo)
    (lungo,) = _date(client, "x" * 80)
    assert len(lungo["titolo"]) == 80
    (composto,) = _date(client, "Caffé")  # NFC: la stessa parola scritta in due modi
    assert composto["titolo"] == "Caffé"
    (con_note,) = _date(client, {"titolo": "Spesa", "nota": "  latte\r\npane\rcaffe'  "})
    assert con_note["nota"] == "latte\npane\ncaffe'"
    (senza,) = _date(client, {"titolo": "Spesa", "nota": "   "})
    assert senza["nota"] is None
    for nota in ("x" * 301, "latte\tpane", "latte pane", "​"):
        assert _dai(client, {"titolo": "Spesa", "nota": nota}).status_code == 422, repr(nota)
    assert _dai(client, {"titolo": "Spesa", "nota": "x" * 300}).status_code == 201


def test_quante_e_per_chi(client, famiglia):
    assert _dai(client, *[f"F{i}" for i in range(11)]).status_code == 422
    assert client.post("/api/faccende", json={"figlio_id": 1, "faccende": []}, headers=GENITORE).status_code == 422
    # figlio_id obbligatorio: qui non vale "il primo figlio"
    assert client.post("/api/faccende", json={"faccende": [{"titolo": "Letto"}]}, headers=GENITORE).status_code == 422
    assert _dai(client, "Letto", figlio_id=True).status_code == 422
    r = _dai(client, "Letto", figlio_id=99)
    assert r.status_code == 404
    # le faccende si danno solo da genitore
    assert _dai(client, "Letto", headers=FIGLIO).status_code == 403
    assert client.get("/api/faccende", headers=FIGLIO).json()["faccende"] == []


def test_al_massimo_20_da_fare(client, famiglia):
    _date(client, *[f"Prima {i}" for i in range(10)])
    _date(client, *[f"Seconda {i}" for i in range(10)])
    _errore(_dai(client, "Ventunesima"), 409, "troppe_faccende")
    assert len(_elenco(client)) == 20  # non e' nato niente
    # a Sara si', sono per figlio
    assert _dai(client, "Letto", figlio_id=famiglia.sara).status_code == 201
    # fatta una, ce ne sta un'altra
    assert _foto(client, _elenco(client)[0]["id"]).status_code == 200
    assert _dai(client, "Ventunesima").status_code == 201


def test_due_genitori_insieme_non_superano_le_20(client, famiglia):
    """Due genitori che danno 10 faccende nello stesso momento, con 5 gia' da fare: ne
    passa una sola richiesta, l'altra riceve troppe_faccende e non crea niente."""
    _date(client, *[f"Prima {i}" for i in range(5)])
    barriera = threading.Barrier(2)
    esiti = []

    def dai(headers, nome):
        barriera.wait()
        esiti.append(_dai(client, *[f"{nome} {i}" for i in range(10)], headers=headers))

    fili = [threading.Thread(target=dai, args=(GENITORE, "Papa'")),
            threading.Thread(target=dai, args=(famiglia.mamma, "Mamma"))]
    for f in fili:
        f.start()
    for f in fili:
        f.join()
    assert sorted(r.status_code for r in esiti) == [201, 409]
    assert len(_elenco(client)) == 15


def test_blocco_da(client, famiglia, orologio):
    def blocco_da(valore):
        return _ok(_dai(client, "Letto", blocco_da=valore), 201)["faccende"][0]["blocco_da"]

    assert blocco_da(None) == ORA  # null = subito
    assert blocco_da("2026-07-13T08:00:00+00:00") == ORA  # nel passato = subito
    assert blocco_da("2026-07-14T18:00:00+02:00") == "2026-07-14T16:00:00+00:00"  # in UTC, come i ts_server
    assert blocco_da("2026-07-14T16:30:00Z") == "2026-07-14T16:30:00+00:00"
    assert blocco_da("2026-07-21T10:00:00+00:00") == "2026-07-21T10:00:00+00:00"  # 7 giorni giusti
    for valore in ("2026-07-21T10:00:01+00:00", "2026-07-15T16:00:00", "2026-07-15", "domani alle 16", "",
                   "9999-12-31T23:59:59+00:00", "0001-01-01T00:00:00+14:00", 1784056519000):
        assert _dai(client, "Letto", blocco_da=valore).status_code == 422, valore


# --- il blocco ---

def test_il_blocco_si_vede_uguale_dappertutto(client, famiglia, orologio):
    assert _blocco(client) == {"attivo": False, "dal": None, "prossimo": None, "da_fare": [], **SENZA_STUDIO}
    lavatrice, letto = _date(client, {"titolo": "Lavatrice", "nota": "i bianchi"}, "Letto", headers=famiglia.mamma)
    orologio.avanza(minutes=1)
    (cane,) = _date(client, "Cane", blocco_da="2026-07-14T16:00:00+00:00")
    blocco = _blocco(client)
    assert blocco == {
        "attivo": True, "dal": ORA, "prossimo": None, **SENZA_STUDIO,  # (v4.0)
        "da_fare": [  # (v4.0) con stato e foto_ts
            {"id": lavatrice["id"], "titolo": "Lavatrice", "nota": "i bianchi", "blocco_da": ORA, "creata_da": MAMMA,
             "bocciature": 0, "ultima_bocciatura": None, "stato": "da_fare", "foto_ts": None},
            {"id": letto["id"], "titolo": "Letto", "nota": None, "blocco_da": ORA, "creata_da": MAMMA,
             "bocciature": 0, "ultima_bocciatura": None, "stato": "da_fare", "foto_ts": None},
            {"id": cane["id"], "titolo": "Cane", "nota": None, "blocco_da": "2026-07-14T16:00:00+00:00",
             "creata_da": GENITORE_1, "bocciature": 0, "ultima_bocciatura": None, "stato": "da_fare",
             "foto_ts": None},
        ],
    }
    elenco = _elenco(client)
    assert [f["id"] for f in elenco] == [cane["id"], letto["id"], lavatrice["id"]]  # dalla piu' recente
    # uguale dal computer, nel patto dei due dispositivi e nella finestra del genitore
    assert _blocco(client, famiglia.pc) == blocco and _elenco(client, famiglia.pc) == elenco
    for headers in (FIGLIO, famiglia.pc):
        patto = _ok(client.get("/api/patto", headers=headers))
        assert (patto["blocco"], patto["faccende"]) == (blocco, elenco)
    finestra = _ok(client.get("/api/finestra?figlio_id=1", headers=famiglia.mamma))
    assert (finestra["blocco"], finestra["faccende"]) == (blocco, elenco)
    assert _blocco(client, GENITORE) == blocco  # il genitore lo chiede per il primo figlio
    assert _elenco(client, GENITORE, figlio_id=1) == elenco
    famiglia_vista = _ok(client.get("/api/famiglia", headers=GENITORE))["figli"]
    assert [(f["faccende_da_fare"], f["blocco_attivo"]) for f in famiglia_vista] == [(3, True), (0, False)]
    # Sara non ne sa niente
    assert _blocco(client, famiglia.tel_sara)["attivo"] is False
    assert _ok(client.get("/api/patto", headers=famiglia.tel_sara))["faccende"] == []
    assert _elenco(client, GENITORE, figlio_id=famiglia.sara) == []


def test_un_blocco_programmato(client, famiglia, orologio):
    (letto,) = _date(client, "Letto", blocco_da="2026-07-14T16:00:00+00:00")
    assert _blocco(client) | {"da_fare": []} == {
        "attivo": False, "dal": None, "prossimo": "2026-07-14T16:00:00+00:00", "da_fare": [], **SENZA_STUDIO,
    }
    assert _ok(client.get("/api/famiglia", headers=GENITORE))["figli"][0]["blocco_attivo"] is False
    orologio.vai_a(datetime(2026, 7, 14, 16, 0, tzinfo=timezone.utc))
    blocco = _blocco(client)
    assert (blocco["attivo"], blocco["dal"], blocco["prossimo"]) == (True, "2026-07-14T16:00:00+00:00", None)
    assert _foto(client, letto["id"]).status_code == 200
    assert _blocco(client)["attivo"] is True  # (v4.0) la foto non sblocca: aspetta l'approvazione
    assert _approva(client, letto["id"]).status_code == 200
    assert _blocco(client)["attivo"] is False
    (finite,) = _notifiche(client, GENITORE, "faccende_finite")
    assert finite["messaggio"] == "Luca ha i lavori di casa tutti approvati: telefono e computer sbloccati"


def test_le_faccende_fatte_in_anticipo(client, famiglia, orologio):
    """Si puo' mandare la foto anche prima di blocco_da: (v4.0) se un genitore la approva
    prima dell'ora, il blocco allora non parte."""
    lavatrice, letto = _date(client, "Lavatrice", "Letto", blocco_da="2026-07-15T14:00:00+00:00")
    assert _foto(client, lavatrice["id"]).status_code == 200
    assert _foto(client, letto["id"], jpeg(COMMENTO)).status_code == 200
    assert _approva(client, lavatrice["id"]).status_code == 200
    assert _approva(client, letto["id"], headers=famiglia.mamma).status_code == 200
    orologio.vai_a(datetime(2026, 7, 15, 15, 0, tzinfo=timezone.utc))
    assert _blocco(client) == {"attivo": False, "dal": None, "prossimo": None, "da_fare": [], **SENZA_STUDIO}
    (finite,) = _notifiche(client, GENITORE, "faccende_finite")
    assert finite["messaggio"] == "Luca ha i lavori di casa tutti approvati"
    assert finite["payload"] == {"faccenda_ids": [lavatrice["id"], letto["id"]]}


# --- il giro completo ---

def test_il_giro_completo(client, famiglia, db_path, orologio):
    lavatrice, letto = _date(client, "Lavatrice", "Letto", headers=famiglia.mamma)
    orologio.avanza(minutes=20)
    fatta = _ok(_foto(client, lavatrice["id"]))
    adesso = "2026-07-14T10:20:00+00:00"
    assert (fatta["stato"], fatta["foto_ts"], fatta["chiusa_ts"], fatta["foto"]) == ("fatta", adesso, adesso, True)
    assert _blocco(client)["attivo"] is True  # ne manca una
    for headers in (GENITORE, famiglia.mamma):  # tutti i genitori
        (avviso,) = _notifiche(client, headers, "faccenda_fatta")
        # (v4.0) la foto aspetta l'approvazione
        assert avviso["messaggio"] == "Luca ha mandato la foto di «Lavatrice»: aspetta la vostra approvazione"
        assert avviso["payload"] == {"faccenda_id": lavatrice["id"], "titolo": "Lavatrice"}
        assert (avviso["figlio_id"], avviso["dispositivo_id"]) == (1, None)
        assert _notifiche(client, headers, "faccende_finite") == []
    orologio.avanza(minutes=10)
    assert _foto(client, letto["id"]).status_code == 200
    assert _blocco(client)["attivo"] is True  # (v4.0) le foto non sbloccano
    assert _approva(client, lavatrice["id"]).status_code == 200
    assert _blocco(client)["attivo"] is True and _notifiche(client, GENITORE, "faccende_finite") == []
    assert _approva(client, letto["id"], headers=famiglia.mamma).status_code == 200
    assert _blocco(client)["attivo"] is False
    for headers in (GENITORE, famiglia.mamma):
        (finite,) = _notifiche(client, headers, "faccende_finite")
        assert finite["messaggio"] == "Luca ha i lavori di casa tutti approvati: telefono e computer sbloccati"
        assert finite["payload"] == {"faccenda_ids": [lavatrice["id"], letto["id"]]}
    assert sorted(os.listdir(_cartella(db_path))) == [f"{lavatrice['id']}.jpg", f"{letto['id']}.jpg"]
    # un giro nuovo: faccende_finite elenca solo quelle del suo giro
    (cane,) = _date(client, "Cane")
    assert _foto(client, cane["id"]).status_code == 200
    assert _approva(client, cane["id"]).status_code == 200
    assert _notifiche(client, GENITORE, "faccende_finite")[-1]["payload"] == {"faccenda_ids": [cane["id"]]}


# --- la foto ---

NASCOSTI = (b"POSIZIONE-DI-PROVA", b"CITTA-DI-PROVA", b"TELEFONO-DI-PROVA", b"ALTRO-DATO-NASCOSTO",
            b"ESTENSIONE-NASCOSTA", b"MINIATURA-NASCOSTA", b"SECONDA-IMMAGINE", b"VIDEO-DI-PROVA")


def _salvata(client, db_path, dati) -> bytes:
    (faccenda,) = _date(client, "Letto")
    r = _foto(client, faccenda["id"], dati)
    assert r.status_code == 200, r.text
    return (_cartella(db_path) / f"{faccenda['id']}.jpg").read_bytes()


def test_la_foto_perde_i_dati_nascosti(client, famiglia, db_path):
    """Restano solo i segmenti che servono a mostrare l'immagine (lista di quelli
    ammessi): SOI, APP0 JFIF, DQT, SOF, DHT, SOS, i dati, FF D9. Tutto il resto si butta:
    EXIF, XMP, IPTC, commenti, APP15, JPGn, un APP0 che non e' JFIF (la miniatura JFXX),
    un APP2 che non e' il profilo dei colori (l'indice MPF delle seconde immagini)."""
    con_tutto = jpeg(EXIF, XMP, IPTC, COMMENTO, APP15, JPG0, JFXX, MPF)
    assert all(nascosto in con_tutto for nascosto in NASCOSTI[:7])
    salvata = _salvata(client, db_path, con_tutto)
    assert salvata == PULITA
    assert not any(nascosto in salvata for nascosto in NASCOSTI)
    # nessun file temporaneo rimasto
    assert os.listdir(_cartella(db_path)) == ["1.jpg"]


def test_restano_i_colori_e_la_firma_adobe(client, famiglia, db_path):
    """APP2 ICC_PROFILE (i colori) e APP14 Adobe (serve a decodificare) restano, al loro
    posto; e anche DRI. Gli altri intorno no."""
    dri = _segmento(0xDD, b"\x00\x04")
    dati = b"\xff\xd8" + APP0 + EXIF + ICC + ADOBE + DQT + dri + SOF0 + DHT + SOS + DATI + EOI
    assert _salvata(client, db_path, dati) == (
        b"\xff\xd8" + APP0 + ICC + ADOBE + DQT + dri + SOF0 + DHT + SOS + DATI + EOI
    )


def test_dopo_la_fine_dell_immagine_non_resta_niente(client, famiglia, db_path):
    """Una seconda immagine (Ultra HDR, MPF) o il video di una foto in movimento stanno
    dopo FF D9: si buttano. E un FF D9 dentro un segmento buttato (la miniatura
    dell'EXIF ha il suo FF D8 ... FF D9) non inganna."""
    miniatura = _segmento(0xE1, b"Exif\x00\x00" + b"\xff\xd8\xff\xdb" + b"MINIATURA-NASCOSTA" + b"\xff\xd9")
    seconda = jpeg(EXIF, scan=b"SECONDA-IMMAGINE")
    video = b"\x00\x00\x00\x18ftypmp42VIDEO-DI-PROVA"
    salvata = _salvata(client, db_path, jpeg(miniatura, MPF) + seconda + video)
    assert salvata == PULITA
    assert not any(nascosto in salvata for nascosto in NASCOSTI)


def test_un_jpeg_progressivo_perde_i_dati_tra_le_scansioni(client, famiglia, db_path):
    """Tra una scansione e l'altra valgono le stesse regole che prima della prima: le
    tabelle nuove restano, un APP1 con la posizione infilato li' no. I dati di ogni
    scansione (anche con FF 00 e i RST) restano byte per byte."""
    sof2 = _segmento(0xC2, b"\x08\x00\x10\x00\x10\x01\x01\x11\x00")
    scansione1 = SOS + b"\x01\x02\xff\x00\x03"
    scansione2 = _segmento(0xDA, b"\x01\x01\x00\x01\x05\x00") + b"\x04\xff\xd3\x05"
    progressivo = (b"\xff\xd8" + APP0 + DQT + sof2 + DHT + scansione1 + DHT + EXIF + COMMENTO
                   + b"\xff\xff" + scansione2 + EOI + b"VIDEO-DI-PROVA")
    assert _salvata(client, db_path, progressivo) == (
        b"\xff\xd8" + APP0 + DQT + sof2 + DHT + scansione1 + DHT + scansione2 + EOI
    )


def test_la_foto_si_legge_solo_in_famiglia_e_mai_compressa(client, famiglia):
    (letto,) = _date(client, "Letto")
    grande = jpeg(EXIF, scan=bytes(range(255)) * 40)  # piu' di 500 byte: si comprimerebbe
    assert _foto(client, letto["id"], grande).status_code == 200
    for headers in (GENITORE, famiglia.mamma, FIGLIO, famiglia.pc):
        r = client.get(f"/api/faccende/{letto['id']}/foto", headers={**headers, "Accept-Encoding": "gzip"})
        assert r.status_code == 200
        assert r.headers["content-type"] == "image/jpeg"
        assert "content-encoding" not in r.headers
        assert r.headers["cache-control"] == "private, no-store"
        assert r.content == jpeg(scan=bytes(range(255)) * 40)
    r = client.get(f"/api/faccende/{letto['id']}/foto", headers=famiglia.tel_sara)
    assert r.status_code == 404 and r.json() == {"detail": "faccenda non trovata"}
    assert client.get(f"/api/faccende/{letto['id']}/foto").status_code == 401
    # una faccenda senza foto, e una che non c'e'
    (cane,) = _date(client, "Cane")
    r = client.get(f"/api/faccende/{cane['id']}/foto", headers=GENITORE)
    assert r.status_code == 404 and r.json() == {"detail": "foto non trovata"}
    for faccenda in (999, 99999999999999999999):
        r = client.get(f"/api/faccende/{faccenda}/foto", headers=GENITORE)
        assert r.status_code == 404 and r.json() == {"detail": "faccenda non trovata"}


def test_le_altre_risposte_restano_compresse(client, famiglia):
    _date(client, *[f"Faccenda numero {i}" for i in range(10)])
    r = client.get("/api/faccende", headers={**GENITORE, "Accept-Encoding": "gzip"})
    assert r.status_code == 200 and r.headers["content-encoding"] == "gzip"


def _sof(altezza=16, larghezza=16, componenti=1, extra=b"") -> bytes:
    corpo = b"\x08" + altezza.to_bytes(2, "big") + larghezza.to_bytes(2, "big") + bytes([componenti])
    return _segmento(0xC0, corpo + b"\x01\x11\x00" * componenti + extra)


def test_solo_un_jpeg_vero(client, famiglia):
    (letto,) = _date(client, "Letto")
    troncato = jpeg(EXIF)[:30]  # finisce dentro un segmento
    senza_immagine = b"\xff\xd8" + APP0 + DQT + SOS + DATI + EOI  # manca SOF
    finisce_presto = b"\xff\xd8" + APP0 + EOI
    segmento_bugiardo = b"\xff\xd8" + APP0 + b"\xff\xe1\xff\xff" + b"x" * 10
    undici_byte = b"\xff\xd8\xff\xc0\x00\x02\xff\xda\x00\x02\x00"  # sbloccava, prima
    def con(sof=SOF0, sos=SOS, dati=DATI, fine=EOI):
        return b"\xff\xd8" + APP0 + DQT + sof + DHT + sos + dati + fine
    sbagliati = [
        b"", b"\x89PNG\r\n\x1a\n" + b"\x00" * 50, b"\xff\xd8\x00", troncato, senza_immagine,
        finisce_presto, segmento_bugiardo, undici_byte,
        con(dati=b""),  # una scansione senza dati
        con(fine=b""),  # senza FF D9
        con(fine=b"\xff"),  # FF D9 a meta'
        con(sof=_sof(altezza=0)), con(sof=_sof(larghezza=0)),  # un'immagine alta o larga zero
        con(sof=_sof(componenti=0)), con(sof=_sof(componenti=5)),
        con(sof=_sof(extra=b"\x00")),  # lunghezza che non torna coi componenti
        con(sos=_segmento(0xDA, b"\x02\x01\x00\x00\x3f\x00")),  # SOS con 2 componenti e 1 descritto
        jpeg(*[COMMENTO] * 65),  # troppi segmenti prima della prima scansione
    ]
    for dati in sbagliati:
        _errore(_foto(client, letto["id"], dati), 422, "foto_non_valida")
    assert _elenco(client)[0]["stato"] == "da_fare"
    assert _notifiche(client, GENITORE, "faccenda_fatta") == []
    # 60 segmenti in piu' prima della scansione (con APP0, DQT, SOF e DHT fanno 64): ancora una foto
    assert _foto(client, letto["id"], jpeg(*[COMMENTO] * 60)).status_code == 200


def test_al_massimo_4_mega(client, famiglia, db_path):
    (letto,) = _date(client, "Letto")
    base = len(jpeg())
    troppo = jpeg(scan=b"\x00" * (MEGA - base + 1))
    r = _foto(client, letto["id"], troppo)
    assert r.status_code == 413 and r.json()["detail"] == {"errore": "foto_troppo_grande"}
    assert not _cartella(db_path).exists() or os.listdir(_cartella(db_path)) == []
    giusta = jpeg(scan=b"\x00" * (MEGA - base))
    assert len(giusta) == MEGA
    assert _foto(client, letto["id"], giusta).status_code == 200


def _put_a_pezzi(app, percorso, pezzi, headers) -> tuple[int, int]:
    """Una PUT mandata direttamente all'app ASGI, a pezzi e senza (o con un falso)
    Content-Length, come arriverebbe da una rete vera: (stato, pezzi letti dal server)."""
    scope = {
        "type": "http", "asgi": {"version": "3.0"}, "http_version": "1.1", "method": "PUT",
        "scheme": "http", "path": percorso, "raw_path": percorso.encode(), "query_string": b"",
        "root_path": "", "client": ("prova", 1), "server": ("prova", 80),
        "headers": [(k.lower().encode(), v.encode()) for k, v in headers.items()],
    }
    letti = 0
    risposta = {}

    async def receive():
        nonlocal letti
        if letti < len(pezzi):
            letti += 1
            return {"type": "http.request", "body": pezzi[letti - 1], "more_body": letti < len(pezzi)}
        await asyncio.sleep(3600)  # il client non manda altro

    async def send(messaggio):
        if messaggio["type"] == "http.response.start":
            risposta["stato"] = messaggio["status"]

    asyncio.run(app(scope, receive, send))
    return risposta["stato"], letti


def test_la_misura_si_conta_leggendo(client, famiglia):
    """Content-Length non basta: senza, o con uno falso, il server conta i byte che
    arrivano e si ferma al primo pezzo oltre i 4 MB, senza leggere il resto."""
    (letto,) = _date(client, "Letto")
    pezzo = b"\x00" * (256 * 1024)
    pezzi = [jpeg()] + [pezzo] * 40  # 10 MB
    percorso = f"/api/faccende/{letto['id']}/foto"
    intestazioni = {**FIGLIO, "Content-Type": "image/jpeg"}
    # anche con un altro Content-Type: il controllo dei corpi JSON non se la tiene in memoria
    for altre in ({}, {"Content-Length": "1000"}, {"Content-Type": "application/octet-stream"}):
        stato, letti = _put_a_pezzi(client.app, percorso, pezzi, {**intestazioni, **altre})
        assert stato == 413
        assert letti == 17  # 16 pezzi pieni fanno 4 MB: il diciassettesimo sfora, il resto non si legge
    assert _elenco(client)[0]["stato"] == "da_fare"
    # e una foto a pezzi che sta nei 4 MB va
    stato, _ = _put_a_pezzi(client.app, percorso, [jpeg(EXIF)[:40], jpeg(EXIF)[40:]], intestazioni)
    assert stato == 200 and _elenco(client)[0]["stato"] == "fatta"


def test_content_length_troppo_grande_si_ferma_subito(client, famiglia):
    (letto,) = _date(client, "Letto")
    stato, letti = _put_a_pezzi(client.app, f"/api/faccende/{letto['id']}/foto", [jpeg()],
                                {**FIGLIO, "Content-Type": "image/jpeg", "Content-Length": str(MEGA + 1)})
    assert (stato, letti) == (413, 0)


def test_chi_puo_mandare_la_foto(client, famiglia):
    (letto,) = _date(client, "Letto")
    _errore(_foto(client, letto["id"], headers=famiglia.pc), 422, "solo_dal_telefono")
    r = _foto(client, letto["id"], headers=famiglia.tel_sara)
    assert r.status_code == 404 and r.json() == {"detail": "faccenda non trovata"}
    assert _foto(client, letto["id"], headers=GENITORE).status_code == 403
    assert _foto(client, 999).json() == {"detail": "faccenda non trovata"}
    # un altro telefono di Luca si'
    tablet, _ = dispositivo_abbinato(client, 1, "Tablet", "telefono")
    assert _foto(client, letto["id"], headers=tablet).status_code == 200


def test_la_stessa_foto_due_volte(client, famiglia, orologio):
    """Una rete che cade dopo l'invio: il telefono rimanda la stessa foto, che il
    server ha gia'. 200 con la faccenda, nessun avviso nuovo. Una foto diversa su una
    faccenda gia' fatta no."""
    lavatrice, letto = _date(client, "Lavatrice", "Letto")
    prima = _ok(_foto(client, lavatrice["id"], jpeg(EXIF)))
    orologio.avanza(minutes=3)
    # la seconda volta arriva con un altro EXIF (l'ora dello scatto): pulita e' uguale
    di_nuovo = _ok(_foto(client, lavatrice["id"], jpeg(XMP, COMMENTO)))
    assert di_nuovo == prima
    assert len(_notifiche(client, GENITORE, "faccenda_fatta")) == 1
    _errore(_foto(client, lavatrice["id"], jpeg(scan=b"un'altra")), 409, "non_da_fare")
    assert _ok(_foto(client, letto["id"]))["stato"] == "fatta"
    # (v4.0) il giro si chiude all'ultima approvazione
    assert _approva(client, lavatrice["id"]).status_code == 200
    assert _approva(client, letto["id"]).status_code == 200
    assert len(_notifiche(client, GENITORE, "faccende_finite")) == 1
    assert _ok(_foto(client, letto["id"]))["stato"] == "fatta"
    assert len(_notifiche(client, GENITORE, "faccende_finite")) == 1


def test_il_controllo_dei_corpi_json_non_tocca_le_foto(client, famiglia):
    """Una foto contiene byte che come JSON non andrebbero (qui, un pezzo che sembra un
    surrogato da solo): il controllo dei corpi JSON non la guarda. E un corpo
    dichiarato image/jpeg non diventa mai JSON su un'altra route."""
    (letto,) = _date(client, "Letto")
    assert _foto(client, letto["id"], jpeg(scan=b'"\\ud800" NaN 1e400')).status_code == 200
    r = client.post("/api/eventi", content=b'{"eventi": [{"id": "x", "tipo": "riavvio", "ts_device": NaN}]}',
                    headers={**FIGLIO, "Content-Type": "image/jpeg"})
    assert r.status_code == 422


# --- boccia e annulla ---

def test_bocciare_una_foto(client, famiglia, db_path, orologio):
    lavatrice, letto = _date(client, "Lavatrice", "Letto")
    assert _foto(client, lavatrice["id"]).status_code == 200
    assert _foto(client, letto["id"]).status_code == 200
    assert _approva(client, letto["id"]).status_code == 200  # (v4.0) la lavatrice aspetta ancora
    orologio.avanza(hours=2)
    bocciata = _ok(client.post(f"/api/faccende/{lavatrice['id']}/boccia",
                               json={"nota": "c'e' ancora il cesto pieno"}, headers=famiglia.mamma))
    adesso = "2026-07-14T12:00:00+00:00"
    assert bocciata == {
        **bocciata,
        "stato": "da_fare", "blocco_da": adesso, "foto_ts": None, "foto": False, "chiusa_ts": None,
        "bocciature": 1,
        "ultima_bocciatura": {"ts": adesso, "nota": "c'e' ancora il cesto pieno", "da": MAMMA},
    }
    assert not (_cartella(db_path) / f"{lavatrice['id']}.jpg").exists()
    r = client.get(f"/api/faccende/{lavatrice['id']}/foto", headers=GENITORE)
    assert r.status_code == 404 and r.json() == {"detail": "foto non trovata"}
    blocco = _blocco(client)
    assert (blocco["attivo"], blocco["dal"]) == (True, adesso)  # il blocco torna subito
    assert blocco["da_fare"][0]["ultima_bocciatura"]["da"] == MAMMA
    for headers in (FIGLIO, famiglia.pc):
        (avviso,) = _notifiche(client, headers, "faccenda_bocciata")
        assert avviso["messaggio"] == "Mamma ha bocciato «Lavatrice»: c'e' ancora il cesto pieno"
        assert avviso["payload"] == {"faccenda_id": lavatrice["id"], "titolo": "Lavatrice",
                                     "nota": "c'e' ancora il cesto pieno", "genitore": MAMMA}
        assert avviso["dispositivo_id"] is None
    # rifatta e bocciata di nuovo, senza nota (e anche senza corpo): senza i due punti
    orologio.avanza(minutes=30)
    assert _foto(client, lavatrice["id"], jpeg(scan=b"seconda")).status_code == 200
    orologio.avanza(minutes=1)
    rifatta = _ok(client.post(f"/api/faccende/{lavatrice['id']}/boccia", headers=GENITORE))
    assert rifatta["bocciature"] == 2 and rifatta["ultima_bocciatura"]["nota"] is None
    assert _notifiche(client, FIGLIO, "faccenda_bocciata")[-1]["messaggio"] == "Genitore ha bocciato «Lavatrice»"
    # (v4.0) rifatta e approvata: il giro resta quello di prima (una bocciatura dopo le
    # foto non apre un giro nuovo), quindi ci sono tutte e due
    assert _foto(client, lavatrice["id"], jpeg(scan=b"terza")).status_code == 200
    assert _approva(client, lavatrice["id"]).status_code == 200
    (finite,) = _notifiche(client, GENITORE, "faccende_finite")
    assert finite["payload"] == {"faccenda_ids": [lavatrice["id"], letto["id"]]}


def test_si_boccia_solo_entro_24_ore(client, famiglia, orologio, foto_di_prima):
    """(v4.0) Le 24 ore restano per le foto arrivate prima della v4.0 (foto_di_prima); un
    lavoro da approvare si boccia senza limite (test_faccende_v40)."""
    lavatrice, letto, cane = _date(client, "Lavatrice", "Letto", "Cane")
    assert _foto(client, lavatrice["id"]).status_code == 200
    orologio.avanza(minutes=1)
    assert _foto(client, letto["id"]).status_code == 200
    _errore(client.post(f"/api/faccende/{cane['id']}/boccia", headers=GENITORE), 409, "non_bocciabile")  # da fare
    orologio.avanza(hours=23, minutes=59)  # la lavatrice ha 24 ore giuste, il letto un minuto in meno
    _errore(client.post(f"/api/faccende/{lavatrice['id']}/boccia", headers=GENITORE), 409, "non_bocciabile")
    assert client.post(f"/api/faccende/{letto['id']}/boccia", headers=GENITORE).status_code == 200
    assert _ok(client.post(f"/api/faccende/{cane['id']}/annulla", headers=GENITORE))["stato"] == "annullata"
    _errore(client.post(f"/api/faccende/{cane['id']}/boccia", headers=GENITORE), 409, "non_bocciabile")
    r = client.post("/api/faccende/999/boccia", headers=GENITORE)
    assert r.status_code == 404 and r.json() == {"detail": "faccenda non trovata"}
    assert client.post(f"/api/faccende/{letto['id']}/boccia", headers=FIGLIO).status_code == 403
    assert client.post(f"/api/faccende/{letto['id']}/boccia", json={"nota": "x" * 301},
                       headers=GENITORE).status_code == 422


def test_due_bocciature_insieme_ne_passa_una(client, famiglia, orologio):
    for giro in range(4):
        (faccenda,) = _date(client, f"Faccenda {giro}")
        assert _foto(client, faccenda["id"], jpeg(scan=bytes([giro]))).status_code == 200
        barriera = threading.Barrier(2)
        esiti = []

        def boccia(headers):
            barriera.wait()
            esiti.append(client.post(f"/api/faccende/{faccenda['id']}/boccia", json={"nota": "no"}, headers=headers))

        fili = [threading.Thread(target=boccia, args=(h,)) for h in (GENITORE, famiglia.mamma)]
        for f in fili:
            f.start()
        for f in fili:
            f.join()
        assert sorted(r.status_code for r in esiti) == [200, 409]
        (perdente,) = [r for r in esiti if r.status_code == 409]
        assert perdente.json()["detail"] == {"errore": "non_bocciabile"}
        dopo = next(f for f in _elenco(client) if f["id"] == faccenda["id"])
        assert (dopo["stato"], dopo["bocciature"]) == ("da_fare", 1)
        assert len([n for n in _notifiche(client, FIGLIO, "faccenda_bocciata")
                    if n["payload"]["faccenda_id"] == faccenda["id"]]) == 1
        assert _ok(client.post(f"/api/faccende/{faccenda['id']}/annulla", headers=GENITORE))["stato"] == "annullata"


def test_annullare_una_faccenda(client, famiglia, orologio):
    lavatrice, letto = _date(client, "Lavatrice", "Letto")
    assert _foto(client, lavatrice["id"]).status_code == 200
    assert _approva(client, lavatrice["id"]).status_code == 200  # (v4.0) la foto da sola non sblocca
    orologio.avanza(minutes=5)
    annullata = _ok(client.post(f"/api/faccende/{letto['id']}/annulla", headers=famiglia.mamma))
    assert (annullata["stato"], annullata["annullata_da"], annullata["chiusa_ts"]) == (
        "annullata", MAMMA, "2026-07-14T10:05:00+00:00",
    )
    assert _blocco(client)["attivo"] is False  # era l'ultima che bloccava
    (avviso,) = _notifiche(client, FIGLIO, "faccenda_annullata")
    assert avviso["messaggio"] == "Mamma ha annullato «Letto»"
    assert avviso["payload"] == {"faccenda_id": letto["id"], "titolo": "Letto", "genitore": MAMMA}
    assert _notifiche(client, GENITORE, "faccende_finite") == []  # annullare non e' fare
    _errore(client.post(f"/api/faccende/{letto['id']}/annulla", headers=GENITORE), 409, "non_annullabile")
    _errore(client.post(f"/api/faccende/{lavatrice['id']}/annulla", headers=GENITORE), 409, "non_annullabile")
    _errore(_foto(client, letto["id"]), 409, "non_da_fare")
    assert client.post(f"/api/faccende/{letto['id']}/annulla", headers=FIGLIO).status_code == 403


def test_foto_e_annullamento_insieme_ne_passa_uno(client, famiglia, db_path):
    for giro in range(4):
        (faccenda,) = _date(client, f"Faccenda {giro}")
        barriera = threading.Barrier(2)
        esiti = {}

        def foto():
            barriera.wait()
            esiti["foto"] = _foto(client, faccenda["id"], jpeg(EXIF, scan=bytes([giro])))

        def annulla():
            barriera.wait()
            esiti["annulla"] = client.post(f"/api/faccende/{faccenda['id']}/annulla", headers=GENITORE)

        fili = [threading.Thread(target=foto), threading.Thread(target=annulla)]
        for f in fili:
            f.start()
        for f in fili:
            f.join()
        assert sorted(r.status_code for r in esiti.values()) == [200, 409]
        dopo = next(f for f in _elenco(client) if f["id"] == faccenda["id"])
        file = _cartella(db_path) / f"{faccenda['id']}.jpg"
        if esiti["foto"].status_code == 200:
            assert esiti["annulla"].json()["detail"] == {"errore": "non_annullabile"}
            assert (dopo["stato"], dopo["foto"], file.exists()) == ("fatta", True, True)
        else:
            assert esiti["foto"].json()["detail"] == {"errore": "non_da_fare"}
            assert (dopo["stato"], dopo["foto"], file.exists()) == ("annullata", False, False)
    # nessun file temporaneo rimasto a meta'
    assert not [n for n in os.listdir(_cartella(db_path)) if not n.endswith(".jpg")]


# --- la foto vecchia, la storia, la bocciatura che non si scrive ---

def test_la_foto_scattata_prima_della_bocciatura_non_sblocca(client, famiglia, orologio):
    """Il telefono manda la foto, la rete cade, il genitore boccia; poi la stessa foto
    (gia' in viaggio) arriva: con ?bocciature=N (quante il telefono ne conosceva quando
    ha scattato) il server la riconosce come vecchia e non sblocca. Senza il parametro
    (le app 0.12) come prima."""
    (letto,) = _date(client, "Letto")
    vecchia = jpeg(EXIF, scan=b"vecchia")
    assert _foto(client, letto["id"], vecchia, params={"bocciature": 0}).status_code == 200
    assert client.post(f"/api/faccende/{letto['id']}/boccia", headers=famiglia.mamma).status_code == 200
    orologio.avanza(minutes=1)
    _errore(_foto(client, letto["id"], vecchia, params={"bocciature": 0}), 409, "bocciata_nel_frattempo")
    assert _blocco(client)["attivo"] is True and _elenco(client)[0]["stato"] == "da_fare"
    # quella nuova, scattata dopo la bocciatura, si'; e rimandata, e' la stessa foto: 200
    nuova = jpeg(scan=b"nuova")
    assert _ok(_foto(client, letto["id"], nuova, params={"bocciature": 1}))["stato"] == "fatta"
    assert _foto(client, letto["id"], nuova, params={"bocciature": 1}).status_code == 200
    # una foto vecchia che arriva quando la faccenda e' gia' rifatta: bocciata_nel_frattempo
    _errore(_foto(client, letto["id"], vecchia, params={"bocciature": 0}), 409, "bocciata_nel_frattempo")
    # un numero di bocciature che non e' un intero >= 0
    for sbagliato in ("-1", "uno", "1.5"):
        assert _foto(client, letto["id"], nuova, params={"bocciature": sbagliato}).status_code == 422
    # senza il parametro (app 0.12): la foto vecchia dopo una bocciatura vale, come prima
    orologio.avanza(minutes=1)
    assert client.post(f"/api/faccende/{letto['id']}/boccia", headers=GENITORE).status_code == 200
    assert _ok(_foto(client, letto["id"], vecchia))["stato"] == "fatta"


def test_la_storia_di_una_faccenda(client, famiglia, db_path, orologio):
    """La storia tiene tutto, dalla piu' vecchia: chi l'ha data, ogni foto arrivata, ogni
    bocciatura (con la nota e chi), l'annullamento. La bocciatura non la cancella."""
    lavatrice, letto = _date(client, "Lavatrice", "Letto", headers=famiglia.mamma)
    t = lambda minuti: f"2026-07-14T10:{minuti:02d}:00+00:00"
    orologio.avanza(minutes=1)
    assert _foto(client, lavatrice["id"], jpeg(scan=b"prima")).status_code == 200
    orologio.avanza(minutes=1)
    assert client.post(f"/api/faccende/{lavatrice['id']}/boccia", json={"nota": "non e' stesa"},
                       headers=famiglia.mamma).status_code == 200
    orologio.avanza(minutes=1)
    assert _foto(client, lavatrice["id"], jpeg(scan=b"seconda")).status_code == 200
    orologio.avanza(minutes=1)
    rifatta = _ok(client.post(f"/api/faccende/{lavatrice['id']}/boccia", headers=GENITORE))
    assert rifatta["storia"] == [
        {"tipo": "data", "ts": t(0), "genitore": MAMMA},
        {"tipo": "foto", "ts": t(1)},
        {"tipo": "bocciata", "ts": t(2), "genitore": MAMMA, "nota": "non e' stesa"},
        {"tipo": "foto", "ts": t(3)},
        {"tipo": "bocciata", "ts": t(4), "genitore": GENITORE_1, "nota": None},
    ]
    # l'ultima bocciatura sta anche in ultima_bocciatura, la prima resta nella storia
    assert rifatta["ultima_bocciatura"] == {"ts": t(4), "nota": None, "da": GENITORE_1}
    orologio.avanza(minutes=1)
    annullata = _ok(client.post(f"/api/faccende/{letto['id']}/annulla", headers=GENITORE))
    assert annullata["storia"] == [{"tipo": "data", "ts": t(0), "genitore": MAMMA},
                                   {"tipo": "annullata", "ts": t(5), "genitore": GENITORE_1}]
    # la stessa storia ovunque compaia la faccenda
    elenco = {f["id"]: f for f in _elenco(client)}
    assert elenco[lavatrice["id"]]["storia"] == rifatta["storia"]
    patto = {f["id"]: f for f in _ok(client.get("/api/patto", headers=FIGLIO))["faccende"]}
    finestra = {f["id"]: f for f in _ok(client.get("/api/finestra", headers=GENITORE))["faccende"]}
    assert patto[letto["id"]]["storia"] == finestra[letto["id"]]["storia"] == annullata["storia"]
    # in sola aggiunta: il database non lascia cambiarla ne' cancellarla
    conn = sqlite3.connect(db_path)
    try:
        for sql in ("UPDATE faccende_storia SET nota = 'cambiata'", "DELETE FROM faccende_storia"):
            with pytest.raises(sqlite3.IntegrityError, match="storia delle faccende"):
                conn.execute(sql)
    finally:
        conn.close()


def test_la_foto_bocciata_si_toglie_dopo_il_commit(client, famiglia, db_path, monkeypatch):
    """La foto bocciata si sposta a un nome suo dentro il lock e si toglie dopo il commit;
    se la bocciatura non si scrive (qui si rompe dopo lo spostamento, prima del commit),
    la foto torna al suo posto e la faccenda resta fatta."""
    from app.routes import faccende as rotte

    (letto,) = _date(client, "Letto")
    assert _foto(client, letto["id"]).status_code == 200
    file = _cartella(db_path) / f"{letto['id']}.jpg"
    salvata = file.read_bytes()
    vera = rotte.accoda_notifica

    def si_rompe(*argomenti, **altri):
        assert not file.exists()  # spostata, non ancora tolta
        raise RuntimeError("corrente saltata prima del commit")

    monkeypatch.setattr(rotte, "accoda_notifica", si_rompe)
    with pytest.raises(RuntimeError, match="corrente saltata"):
        client.post(f"/api/faccende/{letto['id']}/boccia", headers=GENITORE)
    assert file.read_bytes() == salvata
    (dopo,) = _elenco(client)
    assert (dopo["stato"], dopo["foto"], dopo["bocciature"]) == ("fatta", True, 0)
    assert [v["tipo"] for v in dopo["storia"]] == ["data", "foto"]
    assert os.listdir(_cartella(db_path)) == [file.name]

    monkeypatch.setattr(rotte, "accoda_notifica", vera)
    assert client.post(f"/api/faccende/{letto['id']}/boccia", headers=GENITORE).status_code == 200
    assert os.listdir(_cartella(db_path)) == []  # tolta, e nessun file messo da parte rimasto


def test_la_pulizia_della_foto_non_ferma_le_altre_richieste(client, famiglia, monkeypatch):
    """La pulizia del JPEG gira nel pool dei thread, non nel ciclo che serve tutte le
    richieste: qui dentro non c'e' un ciclo di asyncio che gira."""
    import asyncio as aio

    from app import faccende

    vero, dove = faccende.pulisci_jpeg, []

    def guarda(dati):
        try:
            aio.get_running_loop()
            dove.append("ciclo principale")
        except RuntimeError:
            dove.append("thread")
        return vero(dati)

    monkeypatch.setattr(faccende, "pulisci_jpeg", guarda)
    (letto,) = _date(client, "Letto")
    assert _foto(client, letto["id"]).status_code == 200
    assert dove == ["thread"]


def test_un_telefono_che_se_ne_va_a_meta_invio(client, famiglia, db_path, caplog):
    """Il telefono perde la rete mentre manda la foto: niente 500, niente traccia nel
    log, niente scritto."""
    (letto,) = _date(client, "Letto")
    scope = {
        "type": "http", "asgi": {"version": "3.0"}, "http_version": "1.1", "method": "PUT",
        "scheme": "http", "path": f"/api/faccende/{letto['id']}/foto",
        "raw_path": f"/api/faccende/{letto['id']}/foto".encode(), "query_string": b"",
        "root_path": "", "client": ("prova", 1), "server": ("prova", 80),
        "headers": [(b"authorization", FIGLIO["Authorization"].encode()), (b"content-type", b"image/jpeg")],
    }
    messaggi = iter([{"type": "http.request", "body": jpeg()[:20], "more_body": True},
                     {"type": "http.disconnect"}])
    risposta = {}

    async def receive():
        return next(messaggi)

    async def send(messaggio):
        if messaggio["type"] == "http.response.start":
            risposta["stato"] = messaggio["status"]

    with caplog.at_level(logging.DEBUG):
        asyncio.run(client.app(scope, receive, send))
    assert risposta["stato"] != 500
    assert not [r for r in caplog.records if r.levelno >= logging.ERROR or r.exc_info]
    assert _elenco(client)[0]["stato"] == "da_fare"
    assert not _cartella(db_path).exists() or os.listdir(_cartella(db_path)) == []


# --- sessioni e faccende ---

def _battito(client, versione, headers=FIGLIO):
    assert client.post("/api/battito", json={"versione_app": versione}, headers=headers).status_code == 200


def test_niente_sessioni_col_blocco_attivo(client, famiglia, orologio):
    _battito(client, "0.13.0")
    s = _ok(client.post("/api/sessioni", json={"nome": "Studio", "app": SCUOLA}, headers=FIGLIO), 201)
    assert client.post(f"/api/sessioni/{s['id']}/risposta", json={"esito": "approva", "versione": 1},
                       headers=GENITORE).status_code == 200
    (letto,) = _date(client, "Letto", blocco_da="2026-07-14T12:00:00+00:00")
    # programmato, non ancora attivo: la sessione parte
    svolta = _ok(client.post(f"/api/sessioni/{s['id']}/avvia", json={"durata_minuti": 30}, headers=FIGLIO), 201)
    assert _ok(client.post("/api/sessioni/in_corso/termina", json={"svolta_id": svolta["id"]}, headers=FIGLIO))
    orologio.vai_a(datetime(2026, 7, 14, 12, 0, tzinfo=timezone.utc))
    _errore(client.post(f"/api/sessioni/{s['id']}/avvia", json={"durata_minuti": 30}, headers=FIGLIO),
            409, "blocco_faccende")
    assert _foto(client, letto["id"]).status_code == 200
    # (v4.0) la foto da sola non basta: il lavoro aspetta l'approvazione, e blocca ancora
    _errore(client.post(f"/api/sessioni/{s['id']}/avvia", json={"durata_minuti": 30}, headers=FIGLIO),
            409, "blocco_faccende")
    assert _approva(client, letto["id"]).status_code == 200
    assert client.post(f"/api/sessioni/{s['id']}/avvia", json={"durata_minuti": 30}, headers=FIGLIO).status_code == 201


@pytest.mark.parametrize("versione", ["0.12.0", "0.12.3", None, "versione-strana"])
def test_un_telefono_che_non_conosce_le_faccende_avvia_come_prima(client, famiglia, versione):
    """Su un telefono piu' vecchio della 0.13 il blocco non c'e' (contratto,
    "Compatibilita'"): la sessione parte come prima, invece di un 409 blocco_faccende che
    l'app non saprebbe leggere. Una versione che manca o non si legge e' di un'app
    vecchia. Dalla 0.13 (anche 0.13.1, 1.0) il controllo vale."""
    if versione is not None:
        _battito(client, versione)
    s = _ok(client.post("/api/sessioni", json={"nome": "Studio", "app": SCUOLA}, headers=FIGLIO), 201)
    assert client.post(f"/api/sessioni/{s['id']}/risposta", json={"esito": "approva", "versione": 1},
                       headers=GENITORE).status_code == 200
    _date(client, "Letto")
    assert _blocco(client)["attivo"] is True
    svolta = _ok(client.post(f"/api/sessioni/{s['id']}/avvia", json={"durata_minuti": 30}, headers=FIGLIO), 201)
    assert _ok(client.post("/api/sessioni/in_corso/termina", json={"svolta_id": svolta["id"]}, headers=FIGLIO))
    for nuova in ("0.13.1", "1.0.0"):
        _battito(client, nuova)
        _errore(client.post(f"/api/sessioni/{s['id']}/avvia", json={"durata_minuti": 30}, headers=FIGLIO),
                409, "blocco_faccende")


# --- l'elenco ---

def test_l_elenco_tiene_le_chiuse_degli_ultimi_30_giorni(client, famiglia, orologio):
    vecchia, annullata, da_fare = _date(client, "Vecchia", "Annullata", "Da fare")
    assert _foto(client, vecchia["id"]).status_code == 200
    assert _approva(client, vecchia["id"]).status_code == 200  # (v4.0) da approvare resterebbe sempre
    assert client.post(f"/api/faccende/{annullata['id']}/annulla", headers=GENITORE).status_code == 200
    orologio.avanza(days=30)
    assert {f["id"] for f in _elenco(client)} == {vecchia["id"], annullata["id"], da_fare["id"]}
    orologio.avanza(seconds=1)
    assert [f["id"] for f in _elenco(client)] == [da_fare["id"]]  # le da fare restano sempre


# --- la pulizia ---

def test_la_pulizia_delle_foto(client, famiglia, db_path, orologio):
    """(v4.0) Con le foto approvate subito: i 30 giorni di una foto approvata si contano
    dall'approvazione (qui la stessa ora della foto)."""
    (vecchia,) = _date(client, "Vecchia")
    assert _foto(client, vecchia["id"]).status_code == 200
    assert _approva(client, vecchia["id"]).status_code == 200
    orologio.avanza(days=10)
    (recente,) = _date(client, "Recente")
    assert _foto(client, recente["id"]).status_code == 200
    assert _approva(client, recente["id"]).status_code == 200
    cartella = _cartella(db_path)
    un_ora_fa = time.time() - 2 * 3600
    orfani = {"999.jpg": un_ora_fa, "spazzatura.txt": un_ora_fa, f"{vecchia['id']}.jpg.abc.parziale": un_ora_fa,
              "12.jpg": None, "appena.jpg.def.parziale": None}  # gli ultimi due: appena scritti, restano
    for nome, quando in orfani.items():
        (cartella / nome).write_bytes(b"x")
        if quando is not None:
            os.utime(cartella / nome, (quando, quando))
    (cartella / "sottocartella").mkdir()
    orologio.avanza(days=20, seconds=1)  # la vecchia ha 30 giorni e un secondo, la recente 20
    from app.main import create_app

    with TestClient(create_app()) as c:  # all'avvio
        r = c.get(f"/api/faccende/{vecchia['id']}/foto", headers=GENITORE)
        assert r.status_code == 404 and r.json() == {"detail": "foto non trovata"}
        (ancora,) = _elenco(c)  # la vecchia e' chiusa da piu' di 30 giorni: non e' piu' nell'elenco
        assert (ancora["id"], ancora["foto"]) == (recente["id"], True)
    conn = sqlite3.connect(db_path)
    try:  # foto_ts resta: "la foto e' arrivata quel giorno" e' storia
        assert conn.execute("SELECT foto_ts FROM faccende WHERE id = ?", (vecchia["id"],)).fetchone() == (ORA,)
    finally:
        conn.close()
    assert sorted(os.listdir(cartella)) == sorted(
        [f"{recente['id']}.jpg", "12.jpg", "appena.jpg.def.parziale", "sottocartella"]
    )


def test_foto_e_false_quando_il_file_non_c_e_piu(client, famiglia, db_path):
    """`foto` dice se il file c'e' ancora (la pulizia l'ha tolto, o qualcuno a mano);
    `foto_ts` resta: e' la storia."""
    (letto,) = _date(client, "Letto")
    assert _foto(client, letto["id"]).status_code == 200
    (_cartella(db_path) / f"{letto['id']}.jpg").unlink()
    (dopo,) = _elenco(client)
    assert (dopo["stato"], dopo["foto"], dopo["foto_ts"]) == ("fatta", False, ORA)
    assert client.get(f"/api/faccende/{letto['id']}/foto", headers=GENITORE).json() == {"detail": "foto non trovata"}
    # e la stessa foto rimandata non si riconosce piu': non c'e' con cosa confrontarla
    _errore(_foto(client, letto["id"]), 409, "non_da_fare")


def test_la_pulizia_una_volta_al_giorno(client, famiglia, db_path, orologio, monkeypatch):
    from app import copie

    fatte = []
    monkeypatch.setattr(copie.faccende, "pulisci_foto", lambda percorso, ora: fatte.append(ora) or 0)
    pulizia = copie.PuliziaFoto(db_path)
    roma = lambda giorno, ora: datetime(2026, 7, giorno, ora, 0, tzinfo=timezone(timedelta(hours=2)))
    orologio.vai_a(roma(15, 2))
    assert pulizia.subito() == 0 and len(fatte) == 1  # all'avvio, anche prima delle 03:00
    assert pulizia.controlla() is None and len(fatte) == 1  # prima delle 03:00 niente
    orologio.vai_a(roma(15, 3))
    assert pulizia.controlla() == 0 and len(fatte) == 2  # la prima volta dopo le 03:00
    orologio.vai_a(roma(15, 22))
    assert pulizia.controlla() is None and len(fatte) == 2  # una sola al giorno
    orologio.vai_a(roma(16, 9))
    assert pulizia.controlla() == 0 and len(fatte) == 3
    # un avvio dopo le 03:00 conta come la pulizia di quel giorno
    altra = copie.PuliziaFoto(db_path)
    altra.subito()
    assert altra.controlla() is None and len(fatte) == 4


def test_una_pulizia_che_non_riesce_non_ferma_il_server(client, db_path, monkeypatch, caplog):
    from app import copie

    def rotta(*_):
        raise OSError("disco che non risponde")

    monkeypatch.setattr(copie.faccende, "pulisci_foto", rotta)
    from app.main import create_app

    with TestClient(create_app()) as c:
        assert c.get("/api/salute").status_code == 200
    assert "pulizia delle foto NON riuscita" in caplog.text
