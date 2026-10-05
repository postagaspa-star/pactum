"""(v3.9) I lavori di casa: modificarli, confermarli, cercarli (contratto-api.md, "v3.9
— i lavori di casa: modificarli, confermarli, cercarli").

- PATCH /api/faccende/{id} (genitore): titolo, nota, ora del blocco di una faccenda da
  fare; voce 'modificata' coi cambi, avviso `faccenda_modificata`; niente se non cambia
  niente; 409 non_modificabile; il blocco segue la nuova ora; con la foto ne passa una.
- POST /api/faccende/{id}/conferma (genitore): "svolto", `confermata_ts`/`confermata_da`,
  voce 'confermata', avviso `faccenda_confermata`; poi non si boccia piu'; con una
  bocciatura insieme ne passa una.
- GET /api/faccende?cerca=: tutta la storia, senza maiuscole/minuscole e accenti, 50 al
  massimo e `altre`.
- La migrazione da un database v3.8 (tests/dati/v38.sql): la copia prima, niente si
  perde, gli stessi numeri di prima, una volta sola.

Tutti i dati sono finti."""

import json
import logging
import sqlite3
import threading
from datetime import timedelta
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

import dati_v24
import dati_v38
from aiuti_v3 import contenuto_in
from conftest import FIGLIO, GENITORE, TOKEN_FIGLIO, TOKEN_GENITORE, Orologio
from test_faccende import (  # noqa: F401  (famiglia e' una fixture)
    EXIF,
    GENITORE_1,
    MAMMA,
    ORA,
    _blocco,
    _cartella,
    _date,
    _elenco,
    _errore,
    _foto,
    _notifiche,
    _ok,
    famiglia,
    jpeg,
)


def _t(minuti: int) -> str:
    """L'ora del server ORA + `minuti` (conftest: 14/07/2026 10:00 UTC)."""
    ore, minuti = divmod(minuti, 60)
    return f"2026-07-14T{10 + ore:02d}:{minuti:02d}:00+00:00"


def _patch(client, faccenda_id, corpo, headers=GENITORE):
    return client.patch(f"/api/faccende/{faccenda_id}", json=corpo, headers=headers)


def _conferma(client, faccenda_id, headers=GENITORE, **corpo):
    """La conferma; `corpo` (foto_ts=...) diventa il corpo JSON, senza niente non c'e'."""
    if "json" in corpo:
        return client.post(f"/api/faccende/{faccenda_id}/conferma", headers=headers, json=corpo["json"])
    return client.post(f"/api/faccende/{faccenda_id}/conferma", headers=headers,
                       **({"json": corpo} if corpo else {}))


def _boccia(client, faccenda_id, headers=GENITORE):
    return client.post(f"/api/faccende/{faccenda_id}/boccia", headers=headers)


def _cerca(client, testo, headers=GENITORE, **query):
    return _ok(client.get("/api/faccende", headers=headers, params={"cerca": testo, **query}))


def _ovunque(client, faccenda_id) -> list[dict]:
    """La stessa faccenda in GET /api/faccende (genitore e telefono), nel patto e nella
    finestra: ovunque compare."""
    viste = [
        _ok(client.get("/api/faccende", headers=GENITORE))["faccende"],
        _ok(client.get("/api/faccende", headers=FIGLIO))["faccende"],
        _ok(client.get("/api/patto", headers=FIGLIO))["faccende"],
        _ok(client.get("/api/finestra", headers=GENITORE))["faccende"],
    ]
    return [next(f for f in vista if f["id"] == faccenda_id) for vista in viste]


# --- modificare ---

def test_modificare_titolo_nota_e_ora(client, famiglia, orologio):
    (lavatrice,) = _date(client, {"titolo": "Lavatrice", "nota": "i bianchi"}, headers=famiglia.mamma,
                         blocco_da="2026-07-14T14:00:00+02:00")  # 12:00 UTC
    orologio.avanza(minutes=5)
    cambiata = _ok(_patch(client, lavatrice["id"], {
        "titolo": " Lavatrice e stendi ", "nota": "i colorati", "blocco_da": "2026-07-14T13:30:00Z"}))
    cambi = {
        "titolo": {"prima": "Lavatrice", "dopo": "Lavatrice e stendi"},
        "nota": {"prima": "i bianchi", "dopo": "i colorati"},
        "blocco_da": {"prima": "2026-07-14T12:00:00+00:00", "dopo": "2026-07-14T13:30:00+00:00"},
    }
    assert (cambiata["titolo"], cambiata["nota"], cambiata["blocco_da"], cambiata["stato"]) == (
        "Lavatrice e stendi", "i colorati", "2026-07-14T13:30:00+00:00", "da_fare")
    assert cambiata["creata_da"] == MAMMA and cambiata["creata_ts"] == ORA  # chi l'ha data non cambia
    assert cambiata["storia"] == [
        {"tipo": "data", "ts": ORA, "genitore": MAMMA},
        {"tipo": "modificata", "ts": _t(5), "genitore": GENITORE_1, "cambi": cambi},
    ]
    # l'avviso a tutti i dispositivi di Luca, a nessun altro
    for headers in (FIGLIO, famiglia.pc):
        (avviso,) = _notifiche(client, headers, "faccenda_modificata")
        assert avviso["messaggio"] == "Genitore ha cambiato «Lavatrice» in «Lavatrice e stendi»"
        assert avviso["payload"] == {"faccenda_id": lavatrice["id"], "titolo": "Lavatrice e stendi",
                                     "genitore": GENITORE_1, "cambi": cambi}
        assert (avviso["destinatario"], avviso["figlio_id"], avviso["dispositivo_id"]) == ("figlio", 1, None)
    assert _notifiche(client, famiglia.tel_sara, "faccenda_modificata") == []
    # uguale ovunque compare
    for vista in _ovunque(client, lavatrice["id"]):
        assert vista == cambiata


def test_solo_i_campi_mandati_e_la_nota_si_toglie(client, famiglia, orologio):
    (letto,) = _date(client, {"titolo": "Letto", "nota": "con le lenzuola"})
    orologio.avanza(minutes=1)
    solo_nota = _ok(_patch(client, letto["id"], {"nota": "e il cuscino"}, headers=famiglia.mamma))
    assert (solo_nota["titolo"], solo_nota["nota"], solo_nota["blocco_da"]) == ("Letto", "e il cuscino", ORA)
    assert solo_nota["storia"][-1]["cambi"] == {"nota": {"prima": "con le lenzuola", "dopo": "e il cuscino"}}
    (avviso,) = _notifiche(client, FIGLIO, "faccenda_modificata")
    assert avviso["messaggio"] == "Mamma ha cambiato «Letto»"  # il titolo non e' cambiato
    orologio.avanza(minutes=1)
    for togli in (None, "", "  \n "):
        letto = _ok(_patch(client, letto["id"], {"nota": togli}))
        assert letto["nota"] is None
    # una sola voce in piu': la prima toglie la nota, le altre non cambiano niente
    assert [v["tipo"] for v in letto["storia"]] == ["data", "modificata", "modificata"]
    assert letto["storia"][-1]["cambi"] == {"nota": {"prima": "e il cuscino", "dopo": None}}
    assert len(_notifiche(client, FIGLIO, "faccenda_modificata")) == 2


def test_se_niente_cambia_niente_storia_e_niente_avviso(client, famiglia, orologio):
    (panni,) = _date(client, {"titolo": "Stendi i panni", "nota": "fuori"}, blocco_da="2026-07-14T15:00:00Z")
    orologio.avanza(minutes=10)
    for corpo in (
        {"titolo": "Stendi i panni"},
        {"titolo": "  Stendi i panni\n", "nota": "fuori"},
        {"blocco_da": "2026-07-14T17:00:00+02:00"},  # lo stesso istante, con un altro fuso
        {"titolo": "Stendi i panni", "nota": "fuori", "blocco_da": "2026-07-14T15:00:00+00:00"},
    ):
        assert _ok(_patch(client, panni["id"], corpo)) == _ovunque(client, panni["id"])[0], corpo
    assert _ovunque(client, panni["id"])[0]["storia"] == [{"tipo": "data", "ts": ORA, "genitore": GENITORE_1}]
    assert _notifiche(client, FIGLIO, "faccenda_modificata") == []

    # una faccenda che blocca gia': "subito" (null o una data passata) e la stessa ora
    # gia' passata non spostano niente, e l'ora da cui blocca resta quella
    (letto,) = _date(client, "Letto")  # blocca da subito: 10:10
    orologio.avanza(minutes=20)
    for blocco_da in (None, "2026-07-14T09:00:00Z", _t(10)):
        dopo = _ok(_patch(client, letto["id"], {"blocco_da": blocco_da}))
        assert dopo["blocco_da"] == _t(10) and len(dopo["storia"]) == 1, blocco_da
    assert _blocco(client)["dal"] == _t(10)
    assert _notifiche(client, FIGLIO, "faccenda_modificata") == []


def test_il_blocco_segue_la_nuova_ora(client, famiglia, orologio):
    """Piu' avanti toglie il blocco fino a quell'ora; prima, o subito, lo fa partire.
    Uguale in GET /api/faccende/blocco (telefono, computer, genitore), nel patto e nella
    finestra."""
    (letto,) = _date(client, "Letto")  # subito
    assert _blocco(client)["attivo"] is True

    def blocchi():
        viste = [_blocco(client), _blocco(client, famiglia.pc), _blocco(client, GENITORE),
                 _ok(client.get("/api/patto", headers=FIGLIO))["blocco"],
                 _ok(client.get("/api/finestra", headers=GENITORE))["blocco"]]
        assert all(v == viste[0] for v in viste)
        return viste[0]

    orologio.avanza(minutes=5)
    _ok(_patch(client, letto["id"], {"blocco_da": "2026-07-14T16:00:00+02:00"}))  # 14:00 UTC
    blocco = blocchi()
    assert (blocco["attivo"], blocco["dal"], blocco["prossimo"]) == (False, None, "2026-07-14T14:00:00+00:00")
    assert blocco["da_fare"][0]["blocco_da"] == "2026-07-14T14:00:00+00:00"
    orologio.avanza(minutes=5)
    _ok(_patch(client, letto["id"], {"blocco_da": "2026-07-14T11:00:00Z"}))  # piu' vicino
    assert blocchi()["prossimo"] == "2026-07-14T11:00:00+00:00"
    orologio.avanza(minutes=5)
    subito = _ok(_patch(client, letto["id"], {"blocco_da": None}))  # subito: l'ora del server
    assert subito["blocco_da"] == _t(15)
    blocco = blocchi()
    assert (blocco["attivo"], blocco["dal"], blocco["prossimo"]) == (True, _t(15), None)
    # una data passata vale subito
    _ok(_patch(client, letto["id"], {"blocco_da": "2026-07-14T23:00:00Z"}))
    orologio.avanza(minutes=5)
    passata = _ok(_patch(client, letto["id"], {"blocco_da": "2026-07-01T08:00:00Z"}))
    assert passata["blocco_da"] == _t(20) and blocchi()["attivo"] is True
    # all'ora nuova il blocco parte da solo
    _ok(_patch(client, letto["id"], {"blocco_da": "2026-07-14T12:00:00Z"}))
    assert blocchi()["attivo"] is False
    orologio.avanza(hours=2)
    assert blocchi()["attivo"] is True


def test_il_corpo_del_patch(client, famiglia, orologio):
    (letto,) = _date(client, "Letto")
    for corpo in (
        {}, {"altro": 1}, {"titolo": None}, {"titolo": ""}, {"titolo": "   "}, {"titolo": "x" * 81},
        {"titolo": "Let\nto"}, {"titolo": 5}, {"nota": "x" * 301}, {"nota": "a​b"},
        {"blocco_da": "domani"}, {"blocco_da": "2026-07-14T16:00:00"},  # senza fuso
        {"blocco_da": "2026-07-21T10:00:01Z"},  # piu' di 7 giorni avanti
    ):
        assert _patch(client, letto["id"], corpo).status_code == 422, corpo
    assert client.patch(f"/api/faccende/{letto['id']}", headers=GENITORE).status_code == 422  # senza corpo
    # 7 giorni esatti vanno bene; un titolo di 80 caratteri anche
    assert _ok(_patch(client, letto["id"], {"blocco_da": "2026-07-21T10:00:00Z"}))["blocco_da"] == (
        "2026-07-21T10:00:00+00:00")
    assert _ok(_patch(client, letto["id"], {"titolo": "y" * 80}))["titolo"] == "y" * 80
    assert len(_ovunque(client, letto["id"])[0]["storia"]) == 3


def test_chi_puo_modificare_e_cosa(client, famiglia, orologio):
    lavatrice, letto, cane = _date(client, "Lavatrice", "Letto", "Cane")
    (di_sara,) = _date(client, "Camera", figlio_id=famiglia.sara)
    # i dispositivi no, nemmeno quelli del figlio
    for headers in (FIGLIO, famiglia.pc):
        assert _patch(client, letto["id"], {"titolo": "Letto!"}, headers=headers).status_code == 403
    # che non c'e', o con un id fuori misura
    for faccenda_id in (999, 2 ** 70):
        r = _patch(client, faccenda_id, {"titolo": "x"})
        assert r.status_code == 404 and r.json() == {"detail": "faccenda non trovata"}
    # fatta o annullata: non si modifica piu'
    assert _foto(client, lavatrice["id"]).status_code == 200
    _ok(client.post(f"/api/faccende/{cane['id']}/annulla", headers=GENITORE))
    for chiusa in (lavatrice, cane):
        _errore(_patch(client, chiusa["id"], {"titolo": "Altro"}), 409, "non_modificabile")
        _errore(_patch(client, chiusa["id"], {"titolo": chiusa["titolo"]}), 409, "non_modificabile")
    # bocciata torna da fare: si modifica di nuovo
    _ok(_boccia(client, lavatrice["id"]))
    assert _ok(_patch(client, lavatrice["id"], {"nota": "meglio"}))["nota"] == "meglio"
    # i genitori vedono tutta la famiglia: anche quelle di Sara
    assert _ok(_patch(client, di_sara["id"], {"titolo": "La camera"}, headers=famiglia.mamma))["titolo"] == "La camera"
    (avviso,) = _notifiche(client, famiglia.tel_sara, "faccenda_modificata")
    assert avviso["messaggio"] == "Mamma ha cambiato «Camera» in «La camera»"


def test_un_genitore_revocato_non_modifica_e_non_conferma(client, famiglia):
    (letto,) = _date(client, "Letto")
    (lavatrice,) = _date(client, "Lavatrice")
    assert _foto(client, lavatrice["id"]).status_code == 200
    assert client.delete("/api/genitori/2", headers=GENITORE).status_code == 200
    assert _patch(client, letto["id"], {"titolo": "x"}, headers=famiglia.mamma).status_code == 401
    assert _conferma(client, lavatrice["id"], headers=famiglia.mamma).status_code == 401


def test_modifica_e_foto_insieme(client, famiglia, db_path):
    """Una foto che arriva mentre si modifica: si mettono in fila. Se passa prima la
    foto, il PATCH trova la faccenda fatta (409 non_modificabile) e la foto vale; se
    passa prima il PATCH, la foto arriva su una faccenda ancora da fare e vale anche lei.
    Mai una faccenda fatta e poi cambiata."""
    for giro in range(6):
        (faccenda,) = _date(client, f"Faccenda {giro}")
        barriera = threading.Barrier(2)
        esiti = {}

        def foto():
            barriera.wait()
            esiti["foto"] = _foto(client, faccenda["id"], jpeg(EXIF, scan=bytes([giro])))

        def modifica():
            barriera.wait()
            esiti["patch"] = _patch(client, faccenda["id"], {"titolo": f"Cambiata {giro}"})

        fili = [threading.Thread(target=foto), threading.Thread(target=modifica)]
        for f in fili:
            f.start()
        for f in fili:
            f.join()
        assert esiti["foto"].status_code == 200, esiti["foto"].text
        dopo = next(f for f in _elenco(client) if f["id"] == faccenda["id"])
        assert (dopo["stato"], dopo["foto"]) == ("fatta", True)
        tipi = [v["tipo"] for v in dopo["storia"]]
        if esiti["patch"].status_code == 200:
            assert tipi == ["data", "modificata", "foto"] and dopo["titolo"] == f"Cambiata {giro}"
        else:
            assert esiti["patch"].json()["detail"] == {"errore": "non_modificabile"}
            assert tipi == ["data", "foto"] and dopo["titolo"] == f"Faccenda {giro}"
    assert not [n for n in Path(_cartella(db_path)).iterdir() if not n.name.endswith(".jpg")]


# --- confermare ---

def test_confermare_una_faccenda_fatta(client, famiglia, orologio):
    lavatrice, letto = _date(client, "Lavatrice", "Letto")
    orologio.avanza(minutes=1)
    assert _foto(client, lavatrice["id"]).status_code == 200
    prima = _blocco(client)
    orologio.avanza(minutes=1)
    confermata = _ok(_conferma(client, lavatrice["id"], headers=famiglia.mamma))
    assert (confermata["stato"], confermata["confermata_ts"], confermata["confermata_da"]) == (
        "fatta", _t(2), MAMMA)
    assert (confermata["foto_ts"], confermata["chiusa_ts"], confermata["foto"]) == (_t(1), _t(1), True)
    assert confermata["storia"] == [
        {"tipo": "data", "ts": ORA, "genitore": GENITORE_1},
        {"tipo": "foto", "ts": _t(1)},
        {"tipo": "confermata", "ts": _t(2), "genitore": MAMMA},
    ]
    # non blocca e non sblocca niente: il blocco e' quello di prima (Letto blocca ancora)
    assert _blocco(client) == prima and prima["attivo"] is True
    for headers in (FIGLIO, famiglia.pc):
        (avviso,) = _notifiche(client, headers, "faccenda_confermata")
        assert avviso["messaggio"] == "Mamma ha confermato «Lavatrice»"
        assert avviso["payload"] == {"faccenda_id": lavatrice["id"], "titolo": "Lavatrice", "genitore": MAMMA}
        assert (avviso["destinatario"], avviso["figlio_id"], avviso["dispositivo_id"]) == ("figlio", 1, None)
    assert _notifiche(client, famiglia.tel_sara, "faccenda_confermata") == []
    for vista in _ovunque(client, lavatrice["id"]):
        assert vista == confermata
    # le faccende non confermate dicono null
    non_ancora = _ovunque(client, letto["id"])[0]
    assert (non_ancora["confermata_ts"], non_ancora["confermata_da"]) == (None, None)


def test_cosa_non_si_conferma(client, famiglia, orologio):
    lavatrice, letto, cane = _date(client, "Lavatrice", "Letto", "Cane")
    _ok(client.post(f"/api/faccende/{cane['id']}/annulla", headers=GENITORE))
    for non_fatta in (letto, cane):  # da fare, annullata
        _errore(_conferma(client, non_fatta["id"]), 409, "non_confermabile")
    assert _foto(client, lavatrice["id"]).status_code == 200
    _ok(_boccia(client, lavatrice["id"]))  # bocciata: torna da fare
    _errore(_conferma(client, lavatrice["id"]), 409, "non_confermabile")
    assert _foto(client, lavatrice["id"], jpeg(scan=b"rifatta")).status_code == 200
    _ok(_conferma(client, lavatrice["id"]))
    _errore(_conferma(client, lavatrice["id"], headers=famiglia.mamma), 409, "non_confermabile")  # gia' confermata
    for headers in (FIGLIO, famiglia.pc):
        assert _conferma(client, lavatrice["id"], headers=headers).status_code == 403
    for faccenda_id in (999, 2 ** 70):
        r = _conferma(client, faccenda_id)
        assert r.status_code == 404 and r.json() == {"detail": "faccenda non trovata"}
    assert len(_notifiche(client, FIGLIO, "faccenda_confermata")) == 1


def test_confermata_non_si_boccia_piu(client, famiglia, orologio):
    (lavatrice,) = _date(client, "Lavatrice")
    assert _foto(client, lavatrice["id"]).status_code == 200
    _ok(_conferma(client, lavatrice["id"]))
    _errore(_boccia(client, lavatrice["id"], headers=famiglia.mamma), 409, "non_bocciabile")
    dopo = _ovunque(client, lavatrice["id"])[0]
    assert (dopo["stato"], dopo["bocciature"], dopo["foto"]) == ("fatta", 0, True)
    assert _notifiche(client, FIGLIO, "faccenda_bocciata") == []


def test_si_conferma_anche_dopo_24_ore_e_senza_il_file(client, famiglia, db_path, orologio):
    lavatrice, letto = _date(client, "Lavatrice", "Letto")
    assert _foto(client, lavatrice["id"]).status_code == 200
    assert _foto(client, letto["id"], jpeg(scan=b"letto")).status_code == 200
    orologio.avanza(hours=25)
    _errore(_boccia(client, lavatrice["id"]), 409, "non_bocciabile")  # troppo tardi per bocciare
    assert _ok(_conferma(client, lavatrice["id"]))["confermata_ts"] == "2026-07-15T11:00:00+00:00"
    # dopo 30 giorni la foto si cancella: la conferma vale lo stesso
    (_cartella(db_path) / f"{letto['id']}.jpg").unlink()
    confermata = _ok(_conferma(client, letto["id"]))
    assert (confermata["foto"], confermata["foto_ts"] is not None) == (False, True)


def test_conferma_e_bocciatura_insieme_ne_passa_una(client, famiglia):
    for giro in range(6):
        (faccenda,) = _date(client, f"Faccenda {giro}")
        foto_ts = _ok(_foto(client, faccenda["id"], jpeg(scan=bytes([giro]))))["foto_ts"]
        barriera = threading.Barrier(2)
        esiti = {}

        def conferma():  # con la foto che la mamma ha guardato, come fa l'app
            barriera.wait()
            esiti["conferma"] = _conferma(client, faccenda["id"], headers=famiglia.mamma, foto_ts=foto_ts)

        def boccia():
            barriera.wait()
            esiti["boccia"] = _boccia(client, faccenda["id"])

        fili = [threading.Thread(target=conferma), threading.Thread(target=boccia)]
        for f in fili:
            f.start()
        for f in fili:
            f.join()
        assert sorted(r.status_code for r in esiti.values()) == [200, 409]
        dopo = next(f for f in _elenco(client) if f["id"] == faccenda["id"])
        if esiti["conferma"].status_code == 200:
            assert esiti["boccia"].json()["detail"] == {"errore": "non_bocciabile"}
            assert (dopo["stato"], dopo["confermata_da"], dopo["bocciature"]) == ("fatta", MAMMA, 0)
        else:
            assert esiti["conferma"].json()["detail"] == {"errore": "non_confermabile"}
            assert (dopo["stato"], dopo["confermata_ts"], dopo["bocciature"]) == ("da_fare", None, 1)
            _ok(client.post(f"/api/faccende/{faccenda['id']}/annulla", headers=GENITORE))


def test_la_conferma_dice_quale_foto_ha_guardato(client, famiglia, orologio):
    """Corpo facoltativo {"foto_ts"}: la stessa foto (anche con un altro fuso) si
    conferma; senza corpo, {} o null come prima."""
    date = _date(client, *[f"Faccenda {n}" for n in range(5)])
    orologio.avanza(minutes=7)
    for f in date:
        assert _foto(client, f["id"], jpeg(scan=f["titolo"].encode())).status_code == 200
    stessa = _t(7)
    for faccenda, corpo in zip(date, (
        {"foto_ts": stessa}, {"foto_ts": "2026-07-14T12:07:00+02:00"}, {"foto_ts": "2026-07-14T10:07:00Z"},
        {}, {"foto_ts": None},
    )):
        confermata = _ok(_conferma(client, faccenda["id"], json=corpo))
        assert confermata["confermata_da"] == GENITORE_1, corpo


def test_una_foto_cambiata_non_si_conferma(client, famiglia, orologio):
    """Il genitore ha guardato la prima foto; intanto la mamma l'ha bocciata e Luca ne ha
    mandata un'altra: confermare con la prima -> 409 foto_cambiata, niente di scritto."""
    (letto,) = _date(client, "Letto")
    guardata = _ok(_foto(client, letto["id"], jpeg(scan=b"prima")))["foto_ts"]
    orologio.avanza(minutes=3)
    _ok(_boccia(client, letto["id"], headers=famiglia.mamma))
    orologio.avanza(minutes=2)
    nuova = _ok(_foto(client, letto["id"], jpeg(scan=b"seconda")))["foto_ts"]
    assert nuova != guardata
    for vista in (guardata, "2026-07-14T12:00:00+02:00"):  # la prima, anche con un altro fuso
        _errore(_conferma(client, letto["id"], foto_ts=vista), 409, "foto_cambiata")
    dopo = _ovunque(client, letto["id"])[0]
    assert (dopo["confermata_ts"], dopo["confermata_da"]) == (None, None)
    assert [v["tipo"] for v in dopo["storia"]] == ["data", "foto", "bocciata", "foto"]
    assert _notifiche(client, FIGLIO, "faccenda_confermata") == []
    # con la foto nuova si'
    assert _ok(_conferma(client, letto["id"], foto_ts=nuova))["confermata_ts"] == _t(5)


def test_l_ordine_dei_controlli_della_conferma(client, famiglia):
    """Corpo (422) -> 404 -> non_confermabile -> foto_cambiata."""
    lavatrice, letto = _date(client, "Lavatrice", "Letto")
    assert _foto(client, lavatrice["id"]).status_code == 200
    for sbagliato in ("ieri", "2026-07-14T10:00:00", "", 5, True, ["2026-07-14T10:00:00Z"]):
        for faccenda_id in (lavatrice["id"], 999):
            assert _conferma(client, faccenda_id, foto_ts=sbagliato).status_code == 422, sbagliato
    r = _conferma(client, 999, foto_ts="2026-07-01T10:00:00Z")
    assert r.status_code == 404 and r.json() == {"detail": "faccenda non trovata"}
    _errore(_conferma(client, letto["id"], foto_ts="2026-07-01T10:00:00Z"), 409, "non_confermabile")  # da fare
    _errore(_conferma(client, lavatrice["id"], foto_ts="2026-07-01T10:00:00Z"), 409, "foto_cambiata")
    _ok(_conferma(client, lavatrice["id"], foto_ts=ORA))
    _errore(_conferma(client, lavatrice["id"], foto_ts="2026-07-01T10:00:00Z"), 409, "non_confermabile")


def test_due_conferme_insieme_ne_passa_una(client, famiglia):
    (faccenda,) = _date(client, "Lavatrice")
    assert _foto(client, faccenda["id"]).status_code == 200
    barriera = threading.Barrier(2)
    esiti = []

    def conferma(headers):
        barriera.wait()
        esiti.append(_conferma(client, faccenda["id"], headers=headers))

    fili = [threading.Thread(target=conferma, args=(h,)) for h in (GENITORE, famiglia.mamma)]
    for f in fili:
        f.start()
    for f in fili:
        f.join()
    assert sorted(r.status_code for r in esiti) == [200, 409]
    assert len(_notifiche(client, FIGLIO, "faccenda_confermata")) == 1
    assert [v["tipo"] for v in _ovunque(client, faccenda["id"])[0]["storia"]].count("confermata") == 1


# --- cercare ---

def test_cercare_senza_maiuscole_e_senza_accenti(client, famiglia):
    _date(client, "Svuota la LAVASTOVIGLIE", "Lavatrice", "Prepara il caffè per papà", "Perché no?",
          "Straße pulita", "Sconto 50%", "Letto")
    def titoli(testo, **query):
        return [f["titolo"] for f in _cerca(client, testo, **query)["faccende"]]

    assert titoli("lava") == ["Lavatrice", "Svuota la LAVASTOVIGLIE"]  # dalla piu' recente
    assert titoli("LAVA") == titoli("lava")
    for testo in ("caffe", "CAFFÈ", "caffé", "papa", "PAPÀ"):
        assert titoli(testo) == ["Prepara il caffè per papà"], testo
    assert titoli("perche") == ["Perché no?"]
    assert titoli("strasse") == ["Straße pulita"]
    assert titoli("50%") == ["Sconto 50%"]  # i caratteri speciali di LIKE sono caratteri
    assert titoli("_") == [] and titoli("%") == ["Sconto 50%"]
    assert titoli("  letto  ") == ["Letto"]  # spazi ai bordi tolti
    assert titoli("niente del genere") == []
    risposta = _cerca(client, "lava")
    assert set(risposta) == {"faccende", "altre"} and risposta["altre"] is False


def test_un_testo_fatto_solo_di_segni_non_trova_niente(client, famiglia):
    """Piegato non resta niente: niente risultati (instr col testo vuoto direbbe "tutto")."""
    _date(client, "Letto", "Caffè")
    for testo in ("́", "́̈", " ́ ", "̧̀"):
        assert _cerca(client, testo) == {"faccende": [], "altre": False}, repr(testo)
        assert _cerca(client, testo, headers=FIGLIO) == {"faccende": [], "altre": False}, repr(testo)
    # un segno dentro un testo vero si toglie e basta
    assert [f["titolo"] for f in _cerca(client, "caffé")["faccende"]] == ["Caffè"]


def test_le_lettere_che_non_si_scompongono(client, famiglia):
    """ł, ø, đ, æ, œ, þ (e le loro maiuscole) valgono l, o, d, ae, oe, th, nel titolo e
    nel testo cercato."""
    _date(client, "Pacco da Łódź", "Søren porta il cane", "Đurđevac", "Æble e pere", "Cœur di carciofo",
          "Þór il gatto")
    titoli = lambda testo: [f["titolo"] for f in _cerca(client, testo)["faccende"]]
    for testo, atteso in (
        ("lodz", "Pacco da Łódź"), ("ŁÓDŹ", "Pacco da Łódź"), ("soren", "Søren porta il cane"),
        ("SØREN", "Søren porta il cane"), ("durdevac", "Đurđevac"), ("đurđ", "Đurđevac"),
        ("aeble", "Æble e pere"), ("ÆBLE", "Æble e pere"), ("coeur", "Cœur di carciofo"),
        ("CŒUR", "Cœur di carciofo"), ("thor", "Þór il gatto"), ("þór", "Þór il gatto"),
    ):
        assert titoli(testo) == [atteso], testo


def test_la_ricerca_guarda_tutta_la_storia(client, famiglia, orologio):
    """Qualunque data e stato: anche le chiuse da piu' di 30 giorni, che l'elenco di
    sempre non mostra piu'."""
    vecchia, fatta = _date(client, "Letto vecchio", "Letto fatto")
    _ok(client.post(f"/api/faccende/{vecchia['id']}/annulla", headers=GENITORE))
    assert _foto(client, fatta["id"]).status_code == 200
    _ok(_conferma(client, fatta["id"]))
    orologio.avanza(days=40)
    (nuova,) = _date(client, "Letto nuovo")
    assert [f["id"] for f in _elenco(client, GENITORE)] == [nuova["id"]]  # senza cerca: come prima
    trovate = _cerca(client, "letto")["faccende"]
    assert [f["id"] for f in trovate] == [nuova["id"], fatta["id"], vecchia["id"]]
    assert [f["stato"] for f in trovate] == ["da_fare", "fatta", "annullata"]
    # nella forma di sempre, con la storia e la conferma
    assert trovate[1]["confermata_da"] == GENITORE_1
    assert [v["tipo"] for v in trovate[1]["storia"]] == ["data", "foto", "confermata"]


def test_al_massimo_50_e_altre(client, famiglia, orologio):
    ids = []
    for giro in range(6):  # 60 faccende "Letto": 20 da fare al massimo, si annullano a gruppi
        date = _date(client, *[f"Letto {giro}-{n}" for n in range(10)])
        ids += [f["id"] for f in date]
        for f in date[:-1] if giro == 5 else date:
            _ok(client.post(f"/api/faccende/{f['id']}/annulla", headers=GENITORE))
        orologio.avanza(minutes=1)
    risposta = _cerca(client, "letto")
    assert (len(risposta["faccende"]), risposta["altre"]) == (50, True)
    # dalla piu' recente: creata_ts, poi id (le dieci di uno stesso giro hanno la stessa ora)
    assert [f["id"] for f in risposta["faccende"]] == sorted(ids, reverse=True)[:50]
    esatte = _cerca(client, "letto 1-")  # 10
    assert (len(esatte["faccende"]), esatte["altre"]) == (10, False)
    # 50 esatte: altre e' false
    for n in range(5):
        orologio.avanza(minutes=1)
        for f in _date(client, *[f"Altro {n}-{m}" for m in range(10)]):
            _ok(client.post(f"/api/faccende/{f['id']}/annulla", headers=GENITORE))
    cinquanta = _cerca(client, "altro")
    assert (len(cinquanta["faccende"]), cinquanta["altre"]) == (50, False)


def test_cerca_vuoto_e_troppo_lungo(client, famiglia):
    _date(client, "Letto")
    senza = _ok(client.get("/api/faccende", headers=GENITORE))
    for vuoto in ("", "   ", "\n"):
        assert _ok(client.get("/api/faccende", headers=GENITORE, params={"cerca": vuoto})) == senza
    assert set(senza) == {"faccende"}  # senza cerca tutto com'era: niente `altre`
    assert _cerca(client, "x" * 80) == {"faccende": [], "altre": False}
    assert _cerca(client, "  " + "x" * 80 + "  ") == {"faccende": [], "altre": False}
    r = client.get("/api/faccende", headers=GENITORE, params={"cerca": "x" * 81})
    assert r.status_code == 422


def test_chi_cerca_e_in_quale_figlio(client, famiglia):
    (di_luca,) = _date(client, "Letto di Luca")
    (di_sara,) = _date(client, "Letto di Sara", figlio_id=famiglia.sara)
    trovate = lambda headers, **q: [f["id"] for f in _cerca(client, "letto", headers=headers, **q)["faccende"]]
    assert trovate(GENITORE) == [di_luca["id"]]  # senza figlio_id il primo figlio
    assert trovate(famiglia.mamma, figlio_id=famiglia.sara) == [di_sara["id"]]
    assert trovate(FIGLIO) == trovate(famiglia.pc) == [di_luca["id"]]
    assert trovate(famiglia.tel_sara) == [di_sara["id"]]
    assert trovate(famiglia.tel_sara, figlio_id=1) == [di_sara["id"]]  # il figlio del token
    assert client.get("/api/faccende", headers=GENITORE, params={"cerca": "letto", "figlio_id": 99}).status_code == 404


# --- la migrazione da un database v3.8 ---

@pytest.fixture
def orologio_v38(monkeypatch):
    from app import clock

    o = Orologio(dati_v38.ORA_V38)
    monkeypatch.setattr(clock, "now", lambda: o.corrente)
    return o


@pytest.fixture
def db_v38(tmp_path):
    path = str(tmp_path / "nas-v38.db")
    dati_v38.crea_db_v38(path)
    return path


@pytest.fixture
def avvia_v38(monkeypatch, orologio_v38, db_v38):
    def _avvia():
        monkeypatch.setenv("PACTUM_DB", db_v38)
        monkeypatch.setenv("PACTUM_TOKEN_FIGLIO", TOKEN_FIGLIO)
        monkeypatch.setenv("PACTUM_TOKEN_GENITORE", TOKEN_GENITORE)
        from app.main import create_app

        return TestClient(create_app())

    return _avvia


def _copie(db_path, suffisso) -> list[Path]:
    percorso = Path(db_path)
    return sorted(percorso.parent.glob(percorso.name + suffisso + "*"))


def _schema(db_path, tabella) -> str:
    conn = sqlite3.connect(db_path)
    try:
        return conn.execute("SELECT sql FROM sqlite_master WHERE name = ?", (tabella,)).fetchone()[0]
    finally:
        conn.close()


def _contatore(db_path, tabella):
    conn = sqlite3.connect(db_path)
    try:
        riga = conn.execute("SELECT seq FROM sqlite_sequence WHERE name = ?", (tabella,)).fetchone()
        return riga[0] if riga else None
    finally:
        conn.close()


def test_prima_di_migrare_la_copia_completa(avvia_v38, db_v38):
    prima = dati_v24.righe(db_v38)
    assert "confermata_ts" not in _schema(db_v38, "faccende")
    with avvia_v38() as c:
        assert c.get("/api/faccende", headers=GENITORE).status_code == 200
    (copia,) = _copie(db_v38, ".prima-v3.9-")
    assert copia.name == "nas-v38.db.prima-v3.9-20261005-120000"  # 10:00 UTC = 12:00 a Roma
    conn = sqlite3.connect(copia)
    try:
        assert conn.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    finally:
        conn.close()
    assert dati_v24.righe(str(copia)) == prima  # la copia e' il database di prima, intero
    assert "confermata_ts" not in _schema(str(copia), "faccende")
    # era gia' v3.8: nessuna delle copie delle migrazioni di prima
    for suffisso in (".prima-v3-", ".prima-v3.4-", ".prima-v3.6-"):
        assert _copie(db_v38, suffisso) == []
    assert not list(Path(db_v38).parent.glob("*.parziale"))


def test_niente_si_perde_e_la_storia_resta_in_sola_aggiunta(avvia_v38, db_v38):
    prima = dati_v24.righe(db_v38)
    contatore = _contatore(db_v38, "faccende_storia")
    with avvia_v38():
        pass
    dopo = dati_v24.righe(db_v38)
    for tabella, righe in prima.items():
        if tabella == "faccende":
            senza = [{k: v for k, v in r.items() if k not in ("confermata_ts", "confermata_genitore_id")}
                     for r in dopo[tabella]]
            assert senza == righe
            assert all(r["confermata_ts"] is None and r["confermata_genitore_id"] is None for r in dopo[tabella])
        elif tabella == "faccende_storia":
            assert [{k: v for k, v in r.items() if k != "cambi"} for r in dopo[tabella]] == righe
            assert all(r["cambi"] is None for r in dopo[tabella])
        else:
            assert dopo[tabella] == righe, tabella
    assert "_faccende_storia_v38" not in dopo
    assert _contatore(db_v38, "faccende_storia") == contatore
    schema = _schema(db_v38, "faccende_storia")
    assert "'modificata'" in schema and "'confermata'" in schema and "cambi" in schema
    conn = sqlite3.connect(db_v38)
    try:
        assert conn.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        assert conn.execute("PRAGMA foreign_key_check").fetchall() == []
        indici = {r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE tbl_name = 'faccende_storia' AND type IN ('index', 'trigger')")}
        assert {"idx_faccende_storia", "faccende_storia_non_si_cambia", "faccende_storia_non_si_cancella"} <= indici
        for sql in ("UPDATE faccende_storia SET nota = 'cambiata'", "DELETE FROM faccende_storia"):
            with pytest.raises(sqlite3.IntegrityError, match="storia delle faccende"):
                conn.execute(sql)
        with pytest.raises(sqlite3.IntegrityError):  # il CHECK dei tipi c'e' ancora
            conn.execute("INSERT INTO faccende_storia (faccenda_id, tipo, ts) VALUES (1, 'inventata', 'x')")
    finally:
        conn.close()


def test_dopo_la_migrazione_gli_stessi_numeri_di_prima(avvia_v38):
    """Le letture del server v3.8 su questo database a ORA_V38 (dati/v38_prima.json): la
    v3.9 aggiunge solo confermata_ts e confermata_da (null) alle faccende."""
    prima = dati_v38.prima()
    with avvia_v38() as c:
        for nome, percorso, chi in dati_v38.LETTURE:
            risposta = c.get(percorso, headers=dati_v38.intestazione(chi))
            assert risposta.status_code == 200, (nome, risposta.text)
            dopo = risposta.json()
            contenuto_in(prima[nome], dopo, nome)
            elenchi = [dopo.get("faccende")] if nome.startswith(("faccende", "patto", "finestra")) else []
            for elenco in elenchi:
                for f, di_prima in zip(elenco, prima[nome]["faccende"]):
                    assert set(f) == set(di_prima) | {"confermata_ts", "confermata_da"}, nome
                    assert (f["confermata_ts"], f["confermata_da"]) == (None, None)
            if nome.startswith("blocco"):
                assert dopo == prima[nome]  # il blocco non cambia forma
        # le foto ci sono ancora (stanno accanto al database, la migrazione non le tocca)
        luca = {f["titolo"]: f for f in c.get("/api/faccende", headers=GENITORE).json()["faccende"]}
        assert luca["Svuota la lavastoviglie"]["foto"] is True
        assert c.get(f"/api/faccende/{luca['Rifai il letto']['id']}/foto", headers=GENITORE).status_code == 200


def test_dopo_la_migrazione_si_conferma_si_modifica_si_cerca(avvia_v38, db_v38, orologio_v38):
    contatore = _contatore(db_v38, "faccende_storia")
    with avvia_v38() as c:
        luca = {f["titolo"]: f for f in c.get("/api/faccende", headers=GENITORE).json()["faccende"]}
        mamma = dati_v38.intestazione("mamma")
        # la lavastoviglie: foto di 2 ore fa, si conferma; poi non si boccia piu'
        confermata = _ok(c.post(f"/api/faccende/{luca['Svuota la lavastoviglie']['id']}/conferma", headers=mamma))
        assert confermata["confermata_da"] == MAMMA
        assert [v["tipo"] for v in confermata["storia"]] == ["data", "foto", "confermata"]
        _errore(c.post(f"/api/faccende/{luca['Svuota la lavastoviglie']['id']}/boccia", headers=GENITORE),
                409, "non_bocciabile")
        # il letto: la storia di prima (con la bocciatura) e la conferma in coda
        letto = _ok(c.post(f"/api/faccende/{luca['Rifai il letto']['id']}/conferma", headers=GENITORE))
        assert [v["tipo"] for v in letto["storia"]] == ["data", "foto", "bocciata", "foto", "confermata"]
        assert letto["storia"][2]["nota"] == "le lenzuola!"
        # i compiti (programmati per le 16 di Roma): spostati alle 13, il blocco li prende
        compiti = _ok(c.patch(f"/api/faccende/{luca['Compiti di matematica']['id']}",
                              json={"blocco_da": "2026-10-05T13:00:00+02:00"}, headers=mamma))
        assert compiti["storia"][-1]["cambi"] == {"blocco_da": {
            "prima": "2026-10-05T14:00:00+00:00", "dopo": "2026-10-05T11:00:00+00:00"}}
        blocco = c.get("/api/faccende/blocco", headers=FIGLIO).json()
        assert [f["blocco_da"] for f in blocco["da_fare"]] == ["2026-10-05T08:30:00+00:00", "2026-10-05T11:00:00+00:00"]
        # le voci nuove della storia continuano dopo quelle di prima
        conn = sqlite3.connect(db_v38)
        try:
            nuove = conn.execute("SELECT id, tipo, cambi FROM faccende_storia WHERE id > ? ORDER BY id",
                                 (contatore,)).fetchall()
        finally:
            conn.close()
        assert [(t, json.loads(cambi) if cambi else None) for _, t, cambi in nuove] == [
            ("confermata", None), ("confermata", None), ("modificata", compiti["storia"][-1]["cambi"])]
        assert [i for i, _, _ in nuove] == list(range(contatore + 1, contatore + 4))
        # la ricerca trova anche il caffe' di ieri e il cane annullato
        trovate = c.get("/api/faccende", headers=FIGLIO, params={"cerca": "CAFFE PER PAPA"}).json()
        assert [f["titolo"] for f in trovate["faccende"]] == ["Prepara il caffè per papà"]
        assert [f["stato"] for f in c.get("/api/faccende", headers=GENITORE, params={"cerca": "cane"}).json()["faccende"]] == ["annullata"]


def test_la_migrazione_v39_si_fa_una_volta_sola(avvia_v38, db_v38, orologio_v38):
    with avvia_v38():
        pass
    dopo_la_prima = dati_v24.righe(db_v38)
    schema = _schema(db_v38, "faccende_storia")
    orologio_v38.avanza(hours=1)
    with avvia_v38():
        pass
    assert dati_v24.righe(db_v38) == dopo_la_prima
    assert _schema(db_v38, "faccende_storia") == schema
    assert len(_copie(db_v38, ".prima-v3.9-")) == 1


def test_una_migrazione_che_non_riesce_lascia_il_database_com_era(avvia_v38, db_v38, monkeypatch):
    """Se la ricostruzione della storia si rompe a meta', niente e' cambiato: ne' le
    colonne della conferma ne' la storia. Il server non parte; al riavvio la migrazione
    si fa da capo e riusa la copia di prima (i dati sono ancora quelli)."""
    from app import db

    prima = dati_v24.righe(db_v38)
    schema_faccende = _schema(db_v38, "faccende")
    vera = db.TABELLA_FACCENDE_STORIA
    monkeypatch.setattr(db, "TABELLA_FACCENDE_STORIA", "CREATE TABLE faccende_storia (rotta")
    with pytest.raises(sqlite3.Error):
        with avvia_v38():
            pass
    assert dati_v24.righe(db_v38) == prima
    assert _schema(db_v38, "faccende") == schema_faccende
    (copia,) = _copie(db_v38, ".prima-v3.9-")
    monkeypatch.setattr(db, "TABELLA_FACCENDE_STORIA", vera)
    with avvia_v38() as c:
        assert c.get("/api/faccende", headers=GENITORE).status_code == 200
    assert _copie(db_v38, ".prima-v3.9-") == [copia]  # la stessa copia, non un'altra
    assert "confermata_ts" in _schema(db_v38, "faccende")


def test_una_colonna_sconosciuta_della_storia_lo_dice_il_log(avvia_v38, db_v38, caplog):
    """Una colonna aggiunta a mano alla storia non passa nella tabella nuova: il log lo
    dice, e resta nella copia .prima-v3.9-. Le righe passano tutte."""
    conn = sqlite3.connect(db_v38)
    try:
        conn.execute("ALTER TABLE faccende_storia ADD COLUMN appunto TEXT")
        conn.execute("ALTER TABLE faccende_storia ADD COLUMN altro INTEGER")
        conn.commit()
    finally:
        conn.close()
    prima = dati_v24.righe(db_v38)["faccende_storia"]
    with caplog.at_level(logging.WARNING, logger="uvicorn.error"):
        with avvia_v38():
            pass
    (avviso,) = [r for r in caplog.records if "faccende_storia ha colonne" in r.getMessage()]
    assert avviso.levelno == logging.WARNING
    assert "altro, appunto" in avviso.getMessage() and ".prima-v3.9-" in avviso.getMessage()
    dopo = dati_v24.righe(db_v38)["faccende_storia"]
    assert "appunto" not in dopo[0] and len(dopo) == len(prima)
    assert [r["id"] for r in dopo] == [r["id"] for r in prima]
    (copia,) = _copie(db_v38, ".prima-v3.9-")
    assert "appunto" in dati_v24.righe(str(copia))["faccende_storia"][0]


def test_senza_colonne_sconosciute_il_log_non_dice_niente(avvia_v38, caplog):
    with caplog.at_level(logging.WARNING, logger="uvicorn.error"):
        with avvia_v38():
            pass
    assert not [r for r in caplog.records if "colonne che il server non conosce" in r.getMessage()]


def test_un_database_nuovo_nasce_gia_v39_senza_copie(client, db_path):
    assert "confermata_genitore_id" in _schema(db_path, "faccende")
    assert "'modificata'" in _schema(db_path, "faccende_storia")
    assert list(Path(db_path).parent.glob("*.prima-v3.9-*")) == []
