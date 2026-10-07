"""(v4.0) I lavori di casa si sbloccano quando un genitore approva (contratto-api.md, "v4.0
— A. I lavori di casa si sbloccano quando un genitore approva").

- La foto non sblocca: il lavoro e' `fatta` ma ancora aperto (`da_approvare`), resta nel
  blocco con `stato` e `foto_ts`, e i genitori ricevono un solo avviso aperto per lavoro.
- Approvare = la conferma della v3.9, col `foto_ts` obbligatorio (422 dopo il 404); se era
  l'ultimo che bloccava il blocco finisce; `faccenda_confermata` al figlio e ai genitori,
  `faccende_finite` all'ultima approvazione; la stessa approvazione ripetuta dallo stesso
  genitore e' un 200 senza avvisi.
- Un lavoro da approvare si boccia senza limite di tempo; la sua foto non si cancella; un
  lavoro fatto in anticipo e non approvato blocca dal suo blocco_da.
- I campi nuovi in famiglia, finestra e GET /api/faccende.

Le foto di prima della v4.0 restano come nella v3.9: v. test_migrazione_v40.py. Tutti i
dati sono finti."""

import threading
from datetime import datetime, timezone

from conftest import FIGLIO, GENITORE
from test_faccende import (  # noqa: F401  (famiglia e foto_di_prima sono fixture)
    GENITORE_1,
    MAMMA,
    ORA,
    SENZA_STUDIO,
    _approva,
    _blocco,
    _cartella,
    _date,
    _elenco,
    _errore,
    _foto,
    _notifiche,
    _ok,
    famiglia,
    foto_di_prima,
    jpeg,
)


def _t(minuti: int) -> str:
    ore, minuti = divmod(minuti, 60)
    return f"2026-07-14T{10 + ore:02d}:{minuti:02d}:00+00:00"


def _conferma(client, faccenda_id, headers=GENITORE, **corpo):
    return client.post(f"/api/faccende/{faccenda_id}/conferma", headers=headers,
                       **({"json": corpo} if corpo else {}))


def _boccia(client, faccenda_id, headers=GENITORE):
    return client.post(f"/api/faccende/{faccenda_id}/boccia", headers=headers)


def _famiglia_di_luca(client, headers=GENITORE) -> dict:
    return _ok(client.get("/api/famiglia", headers=headers))["figli"][0]


# --- la foto non sblocca ---

def test_la_foto_non_sblocca_aspetta_l_approvazione(client, famiglia, orologio):
    (letto,) = _date(client, "Letto", headers=famiglia.mamma)
    orologio.avanza(minutes=10)
    fatta = _ok(_foto(client, letto["id"]))
    assert (fatta["stato"], fatta["foto_ts"], fatta["da_approvare"]) == ("fatta", _t(10), True)
    blocco = _blocco(client)
    assert blocco == {
        "attivo": True, "dal": ORA, "prossimo": None, **SENZA_STUDIO,
        "da_fare": [{"id": letto["id"], "titolo": "Letto", "nota": None, "blocco_da": ORA, "creata_da": MAMMA,
                     "bocciature": 0, "ultima_bocciatura": None, "stato": "fatta", "foto_ts": _t(10)}],
    }
    # uguale dal computer, nel patto, nella finestra e in GET /api/faccende (che ha il blocco)
    for headers in (FIGLIO, famiglia.pc):
        assert _blocco(client, headers) == blocco
        assert _ok(client.get("/api/patto", headers=headers))["blocco"] == blocco
        elenco = _ok(client.get("/api/faccende", headers=headers))
        assert elenco["blocco"] == blocco and elenco["faccende"][0]["da_approvare"] is True
    finestra = _ok(client.get("/api/finestra", headers=GENITORE))
    assert (finestra["blocco"], finestra["faccende_da_approvare"]) == (blocco, 1)
    assert finestra["faccende"][0] == fatta
    luca = _famiglia_di_luca(client)
    assert (luca["faccende_da_fare"], luca["faccende_da_approvare"], luca["blocco_attivo"],
            luca["blocco_rimandato"]) == (0, 1, True, False)
    # l'avviso ai genitori: la foto aspetta la loro approvazione
    for headers in (GENITORE, famiglia.mamma):
        (avviso,) = _notifiche(client, headers, "faccenda_fatta")
        assert avviso["messaggio"] == "Luca ha mandato la foto di «Letto»: aspetta la vostra approvazione"
        assert avviso["payload"] == {"faccenda_id": letto["id"], "titolo": "Letto"}
        assert _notifiche(client, headers, "faccende_finite") == []
    # la foto non si sostituisce: per rifarla serve una bocciatura
    _errore(_foto(client, letto["id"], jpeg(scan=b"un'altra")), 409, "non_da_fare")


def test_approvare_sblocca(client, famiglia, orologio):
    (letto,) = _date(client, "Letto")
    foto_ts = _ok(_foto(client, letto["id"]))["foto_ts"]
    orologio.avanza(minutes=5)
    approvato = _ok(_conferma(client, letto["id"], headers=famiglia.mamma, foto_ts=foto_ts))
    assert (approvato["confermata_ts"], approvato["confermata_da"], approvato["da_approvare"]) == (
        _t(5), MAMMA, False)
    assert [v["tipo"] for v in approvato["storia"]] == ["data", "foto", "confermata"]
    assert _blocco(client) == {"attivo": False, "dal": None, "prossimo": None, "da_fare": [], **SENZA_STUDIO}
    # al figlio, su tutti i suoi dispositivi
    for headers in (FIGLIO, famiglia.pc):
        (avviso,) = _notifiche(client, headers, "faccenda_confermata")
        assert avviso["messaggio"] == "Mamma ha approvato «Letto»: telefono e computer sbloccati"
        assert avviso["payload"] == {"faccenda_id": letto["id"], "titolo": "Letto", "genitore": MAMMA,
                                     "sblocca": True}
        assert avviso["dispositivo_id"] is None
    # agli altri genitori, e gia' letta per chi ha approvato
    (all_altro,) = _notifiche(client, GENITORE, "faccenda_confermata")
    assert (all_altro["destinatario"], all_altro["messaggio"]) == (
        "genitore", "Mamma ha approvato «Letto»: telefono e computer sbloccati")
    assert all_altro["payload"]["sblocca"] is True
    assert _notifiche(client, famiglia.mamma, "faccenda_confermata") == []
    # il giro si chiude all'ultima approvazione; l'avviso della foto e' chiuso per tutti
    for headers in (GENITORE, famiglia.mamma):
        (finite,) = _notifiche(client, headers, "faccende_finite")
        assert finite["messaggio"] == "Luca ha i lavori di casa tutti approvati: telefono e computer sbloccati"
        assert finite["payload"] == {"faccenda_ids": [letto["id"]]}
        assert _notifiche(client, headers, "faccenda_fatta") == []
    luca = _famiglia_di_luca(client)
    assert (luca["faccende_da_approvare"], luca["blocco_attivo"]) == (0, False)


def test_approvare_uno_dei_due_non_sblocca_ancora(client, famiglia, orologio):
    lavatrice, letto = _date(client, "Lavatrice", "Letto")
    assert _foto(client, lavatrice["id"]).status_code == 200
    assert _foto(client, letto["id"], jpeg(scan=b"letto")).status_code == 200
    assert _approva(client, lavatrice["id"]).status_code == 200
    blocco = _blocco(client)
    assert blocco["attivo"] is True and [v["id"] for v in blocco["da_fare"]] == [letto["id"]]
    (primo,) = _notifiche(client, FIGLIO, "faccenda_confermata")
    assert primo["messaggio"] == "Genitore ha approvato «Lavatrice»" and primo["payload"]["sblocca"] is False
    assert _notifiche(client, GENITORE, "faccende_finite") == []
    assert _approva(client, letto["id"], headers=famiglia.mamma).status_code == 200
    assert _notifiche(client, FIGLIO, "faccenda_confermata")[-1]["payload"]["sblocca"] is True
    (finite,) = _notifiche(client, GENITORE, "faccende_finite")
    assert finite["payload"] == {"faccenda_ids": [lavatrice["id"], letto["id"]]}


def test_approvare_un_lavoro_prima_dell_ora_del_blocco(client, famiglia, orologio):
    """Fatto e approvato prima del suo blocco_da: il blocco non parte, e i messaggi non
    dicono "sbloccati" (niente era bloccato)."""
    (letto,) = _date(client, "Letto", blocco_da="2026-07-14T16:00:00+00:00")
    assert _foto(client, letto["id"]).status_code == 200
    assert _blocco(client)["prossimo"] == "2026-07-14T16:00:00+00:00"  # conta anche se fotografato
    assert _approva(client, letto["id"]).status_code == 200
    (avviso,) = _notifiche(client, FIGLIO, "faccenda_confermata")
    assert avviso["messaggio"] == "Genitore ha approvato «Letto»" and avviso["payload"]["sblocca"] is False
    (finite,) = _notifiche(client, GENITORE, "faccende_finite")
    assert finite["messaggio"] == "Luca ha i lavori di casa tutti approvati"
    orologio.vai_a(datetime(2026, 7, 14, 16, 0, tzinfo=timezone.utc))
    assert _blocco(client)["attivo"] is False


def test_fatto_in_anticipo_e_non_approvato_blocca_dalla_sua_ora(client, famiglia, orologio):
    (letto,) = _date(client, "Letto", blocco_da="2026-07-14T16:00:00+00:00")
    foto_ts = _ok(_foto(client, letto["id"]))["foto_ts"]
    assert _blocco(client)["attivo"] is False
    orologio.vai_a(datetime(2026, 7, 14, 16, 0, tzinfo=timezone.utc))
    blocco = _blocco(client)
    assert (blocco["attivo"], blocco["dal"]) == (True, "2026-07-14T16:00:00+00:00")
    assert [(v["stato"], v["foto_ts"]) for v in blocco["da_fare"]] == [("fatta", foto_ts)]
    # nessuna scadenza: dopo tre giorni, se nessuno approva, il blocco c'e' ancora
    orologio.avanza(days=3)
    assert _blocco(client)["attivo"] is True
    assert _ok(_conferma(client, letto["id"], foto_ts=foto_ts))["da_approvare"] is False
    assert _blocco(client)["attivo"] is False


# --- i controlli dell'approvazione ---

def test_il_foto_ts_e_obbligatorio_dopo_il_404(client, famiglia):
    """I controlli: corpo (422) -> 404 -> foto_ts mancante su un lavoro da approvare (422)
    -> non_confermabile -> foto_cambiata."""
    lavatrice, letto = _date(client, "Lavatrice", "Letto")
    foto_ts = _ok(_foto(client, lavatrice["id"]))["foto_ts"]
    for sbagliato in ("ieri", "", 5, True):
        for faccenda_id in (lavatrice["id"], 999):
            assert _conferma(client, faccenda_id, foto_ts=sbagliato).status_code == 422, sbagliato
    for corpo in ({}, {"foto_ts": None}, None):
        r = client.post("/api/faccende/999/conferma", headers=GENITORE, **({"json": corpo} if corpo is not None else {}))
        assert r.status_code == 404 and r.json() == {"detail": "faccenda non trovata"}
        r = client.post(f"/api/faccende/{lavatrice['id']}/conferma", headers=GENITORE,
                        **({"json": corpo} if corpo is not None else {}))
        assert r.status_code == 422, corpo
        assert r.json()["detail"][0]["loc"] == ["body", "foto_ts"]
        # un lavoro da fare non e' da approvare: senza foto_ts e' non_confermabile come nella v3.9
        _errore(client.post(f"/api/faccende/{letto['id']}/conferma", headers=GENITORE,
                            **({"json": corpo} if corpo is not None else {})), 409, "non_confermabile")
    _errore(_conferma(client, lavatrice["id"], foto_ts="2026-07-01T10:00:00Z"), 409, "foto_cambiata")
    assert _notifiche(client, FIGLIO, "faccenda_confermata") == []
    assert _elenco(client)[-1]["da_approvare"] is True
    assert _ok(_conferma(client, lavatrice["id"], foto_ts="2026-07-14T12:00:00+02:00"))["confermata_da"] == GENITORE_1


def test_la_stessa_approvazione_ripetuta(client, famiglia, orologio):
    """La risposta si perde e l'app riprova: lo stesso genitore, lo stesso foto_ts, sul
    lavoro che ha gia' approvato -> 200 col lavoro, senza avvisi nuovi. Un altro genitore
    che approva lo stesso lavoro riceve non_confermabile: e' una seconda decisione."""
    (letto,) = _date(client, "Letto")
    foto_ts = _ok(_foto(client, letto["id"]))["foto_ts"]
    prima = _ok(_conferma(client, letto["id"], headers=famiglia.mamma, foto_ts=foto_ts))
    orologio.avanza(minutes=2)
    di_nuovo = _ok(_conferma(client, letto["id"], headers=famiglia.mamma, foto_ts="2026-07-14T12:00:00+02:00"))
    assert di_nuovo == prima
    assert len(_notifiche(client, FIGLIO, "faccenda_confermata")) == 1
    assert len(_notifiche(client, GENITORE, "faccende_finite")) == 1
    assert [v["tipo"] for v in di_nuovo["storia"]].count("confermata") == 1
    _errore(_conferma(client, letto["id"], foto_ts=foto_ts), 409, "non_confermabile")
    _errore(_conferma(client, letto["id"], headers=famiglia.mamma), 409, "non_confermabile")  # senza foto_ts
    _errore(_conferma(client, letto["id"], headers=famiglia.mamma, foto_ts="2026-07-01T10:00:00Z"),
            409, "non_confermabile")


def test_due_genitori_approvano_insieme_ne_passa_uno(client, famiglia):
    for giro in range(4):
        (faccenda,) = _date(client, f"Faccenda {giro}")
        foto_ts = _ok(_foto(client, faccenda["id"], jpeg(scan=bytes([giro]))))["foto_ts"]
        barriera = threading.Barrier(2)
        esiti = []

        def approva(headers):
            barriera.wait()
            esiti.append(_conferma(client, faccenda["id"], headers=headers, foto_ts=foto_ts))

        fili = [threading.Thread(target=approva, args=(h,)) for h in (GENITORE, famiglia.mamma)]
        for f in fili:
            f.start()
        for f in fili:
            f.join()
        assert sorted(r.status_code for r in esiti) == [200, 409]
        (perdente,) = [r for r in esiti if r.status_code == 409]
        assert perdente.json()["detail"] == {"errore": "non_confermabile"}
        assert len([n for n in _notifiche(client, FIGLIO, "faccenda_confermata")
                    if n["payload"]["faccenda_id"] == faccenda["id"]]) == 1


def test_approvazione_e_bocciatura_insieme_ne_passa_una(client, famiglia):
    for giro in range(4):
        (faccenda,) = _date(client, f"Faccenda {giro}")
        foto_ts = _ok(_foto(client, faccenda["id"], jpeg(scan=bytes([giro]))))["foto_ts"]
        barriera = threading.Barrier(2)
        esiti = {}

        def approva():
            barriera.wait()
            esiti["approva"] = _conferma(client, faccenda["id"], headers=famiglia.mamma, foto_ts=foto_ts)

        def boccia():
            barriera.wait()
            esiti["boccia"] = _boccia(client, faccenda["id"])

        fili = [threading.Thread(target=approva), threading.Thread(target=boccia)]
        for f in fili:
            f.start()
        for f in fili:
            f.join()
        assert sorted(r.status_code for r in esiti.values()) == [200, 409]
        dopo = next(f for f in _elenco(client) if f["id"] == faccenda["id"])
        if esiti["approva"].status_code == 200:
            assert (dopo["stato"], dopo["da_approvare"], dopo["confermata_da"]) == ("fatta", False, MAMMA)
        else:
            assert esiti["approva"].json()["detail"] == {"errore": "non_confermabile"}
            assert (dopo["stato"], dopo["bocciature"]) == ("da_fare", 1)
            _ok(client.post(f"/api/faccende/{faccenda['id']}/annulla", headers=GENITORE))


def test_un_genitore_revocato_non_approva(client, famiglia):
    (letto,) = _date(client, "Letto")
    foto_ts = _ok(_foto(client, letto["id"]))["foto_ts"]
    assert client.delete("/api/genitori/2", headers=GENITORE).status_code == 200
    assert _conferma(client, letto["id"], headers=famiglia.mamma, foto_ts=foto_ts).status_code == 401
    assert _elenco(client)[0]["da_approvare"] is True


# --- bocciare, annullare, modificare ---

def test_un_lavoro_da_approvare_si_boccia_senza_limite(client, famiglia, db_path, orologio):
    (letto,) = _date(client, "Letto")
    assert _foto(client, letto["id"]).status_code == 200
    orologio.avanza(days=3)
    bocciato = _ok(_boccia(client, letto["id"], headers=famiglia.mamma))
    adesso = "2026-07-17T10:00:00+00:00"
    assert (bocciato["stato"], bocciato["blocco_da"], bocciato["foto_ts"], bocciato["da_approvare"]) == (
        "da_fare", adesso, None, False)
    assert not (_cartella(db_path) / f"{letto['id']}.jpg").exists()  # bocciare la cancella subito
    blocco = _blocco(client)
    assert (blocco["attivo"], blocco["dal"]) == (True, adesso)
    # rifatto e approvato: non si boccia piu'
    foto_ts = _ok(_foto(client, letto["id"], jpeg(scan=b"rifatto")))["foto_ts"]
    _ok(_conferma(client, letto["id"], foto_ts=foto_ts))
    _errore(_boccia(client, letto["id"]), 409, "non_bocciabile")


def test_annulla_e_modifica_solo_per_un_lavoro_da_fare(client, famiglia):
    (letto,) = _date(client, "Letto")
    assert _foto(client, letto["id"]).status_code == 200
    _errore(client.post(f"/api/faccende/{letto['id']}/annulla", headers=GENITORE), 409, "non_annullabile")
    _errore(client.patch(f"/api/faccende/{letto['id']}", json={"titolo": "Letto e cuscino"}, headers=GENITORE),
            409, "non_modificabile")


def test_un_solo_avviso_aperto_per_lavoro(client, famiglia, orologio):
    """La foto, la bocciatura e la foto nuova: ai genitori resta aperto solo l'avviso
    dell'ultima foto; l'approvazione lo chiude per tutti."""
    (letto,) = _date(client, "Letto")
    assert _foto(client, letto["id"]).status_code == 200
    assert len(_notifiche(client, GENITORE, "faccenda_fatta")) == 1
    orologio.avanza(minutes=1)
    _ok(_boccia(client, letto["id"], headers=famiglia.mamma))
    for headers in (GENITORE, famiglia.mamma):  # la bocciatura chiude l'avviso per tutti
        assert _notifiche(client, headers, "faccenda_fatta") == []
    orologio.avanza(minutes=1)
    seconda = _ok(_foto(client, letto["id"], jpeg(scan=b"seconda")))["foto_ts"]
    for headers in (GENITORE, famiglia.mamma):
        (avviso,) = _notifiche(client, headers, "faccenda_fatta")
        assert avviso["ts_server"] == seconda
    _ok(_conferma(client, letto["id"], foto_ts=seconda))
    for headers in (GENITORE, famiglia.mamma):
        assert _notifiche(client, headers, "faccenda_fatta") == []


def test_una_bocciatura_dopo_le_foto_resta_nello_stesso_giro(client, famiglia, orologio):
    lavatrice, letto = _date(client, "Lavatrice", "Letto")
    assert _foto(client, lavatrice["id"]).status_code == 200
    assert _foto(client, letto["id"], jpeg(scan=b"letto")).status_code == 200
    assert _approva(client, letto["id"]).status_code == 200
    _ok(_boccia(client, lavatrice["id"]))
    (cane,) = _date(client, "Cane")  # dato mentre il giro e' aperto: nello stesso giro
    assert _foto(client, lavatrice["id"], jpeg(scan=b"rifatta")).status_code == 200
    assert _foto(client, cane["id"], jpeg(scan=b"cane")).status_code == 200
    assert _approva(client, lavatrice["id"]).status_code == 200
    assert _notifiche(client, GENITORE, "faccende_finite") == []
    assert _approva(client, cane["id"]).status_code == 200
    (finite,) = _notifiche(client, GENITORE, "faccende_finite")
    assert finite["payload"] == {"faccenda_ids": [lavatrice["id"], letto["id"], cane["id"]]}


def test_il_tetto_di_20_conta_solo_i_lavori_da_fare(client, famiglia):
    date = _date(client, *[f"Prima {i}" for i in range(10)]) + _date(client, *[f"Seconda {i}" for i in range(10)])
    _errore(client.post("/api/faccende", json={"figlio_id": 1, "faccende": [{"titolo": "Altro"}]},
                        headers=GENITORE), 409, "troppe_faccende")
    assert _foto(client, date[0]["id"]).status_code == 200  # fatto, aspetta l'approvazione
    assert _ok(client.post("/api/faccende", json={"figlio_id": 1, "faccende": [{"titolo": "Altro"}]},
                           headers=GENITORE), 201)
    luca = _famiglia_di_luca(client)
    assert (luca["faccende_da_fare"], luca["faccende_da_approvare"]) == (20, 1)


# --- l'elenco e le foto ---

def test_i_lavori_da_approvare_restano_nell_elenco(client, famiglia, orologio):
    (letto,) = _date(client, "Letto")
    assert _foto(client, letto["id"]).status_code == 200
    orologio.avanza(days=40)
    (vista,) = _elenco(client, GENITORE)
    assert (vista["id"], vista["da_approvare"], vista["foto"]) == (letto["id"], True, True)
    assert _approva(client, letto["id"]).status_code == 200
    # approvato, conta la chiusura (la foto di 40 giorni fa): esce dall'elenco
    assert _elenco(client, GENITORE) == []


def test_le_foto_da_approvare_non_si_cancellano(client, famiglia, db_path, orologio):
    from app import faccende

    cartella = _cartella(db_path)
    (letto,) = _date(client, "Letto")
    assert _foto(client, letto["id"]).status_code == 200
    file = cartella / f"{letto['id']}.jpg"
    orologio.avanza(days=40)
    faccende.pulisci_foto(db_path, orologio.corrente)
    assert file.exists()  # aspetta l'approvazione: resta
    assert _approva(client, letto["id"]).status_code == 200
    orologio.avanza(days=29, hours=23)
    faccende.pulisci_foto(db_path, orologio.corrente)
    assert file.exists()  # 30 giorni dall'approvazione, non dalla foto
    orologio.avanza(hours=1, seconds=1)
    faccende.pulisci_foto(db_path, orologio.corrente)
    assert not file.exists()
    (dopo,) = [f for f in _ok(client.get("/api/faccende", headers=GENITORE, params={"cerca": "letto"}))["faccende"]]
    assert (dopo["foto"], dopo["foto_ts"] is not None) == (False, True)


def test_le_foto_di_prima_della_v40_si_cancellano_dopo_30_giorni_dall_arrivo(client, famiglia, db_path,
                                                                             orologio, foto_di_prima):
    from app import faccende

    (letto,) = _date(client, "Letto")
    assert _foto(client, letto["id"]).status_code == 200
    assert _elenco(client)[0]["da_approvare"] is False
    assert _blocco(client)["attivo"] is False  # una foto di prima ha gia' sbloccato
    orologio.avanza(days=20)
    assert _ok(_conferma(client, letto["id"]))["confermata_da"] == GENITORE_1  # riconoscimento
    orologio.avanza(days=10, seconds=1)
    faccende.pulisci_foto(db_path, orologio.corrente)
    assert not (_cartella(db_path) / f"{letto['id']}.jpg").exists()


def test_la_conferma_di_una_foto_di_prima_non_cambia_niente(client, famiglia, foto_di_prima):
    """Una foto di prima della v4.0: confermabile senza foto_ts, come un riconoscimento,
    coi testi della v3.9; niente faccende_finite (il giro era gia' chiuso)."""
    (letto,) = _date(client, "Letto")
    assert _foto(client, letto["id"]).status_code == 200
    _ok(_conferma(client, letto["id"]))
    (avviso,) = _notifiche(client, FIGLIO, "faccenda_confermata")
    assert avviso["messaggio"] == "Genitore ha confermato «Letto»"
    assert avviso["payload"] == {"faccenda_id": letto["id"], "titolo": "Letto", "genitore": GENITORE_1}
    assert _notifiche(client, GENITORE, "faccenda_confermata") == []
    assert _notifiche(client, GENITORE, "faccende_finite") == []


def test_un_lavoro_di_un_altro_figlio(client, famiglia):
    (di_sara,) = _date(client, "Camera", figlio_id=famiglia.sara)
    foto_ts = _ok(_foto(client, di_sara["id"], headers=famiglia.tel_sara))["foto_ts"]
    assert _blocco(client, famiglia.tel_sara)["attivo"] is True
    assert _blocco(client)["attivo"] is False
    _ok(_conferma(client, di_sara["id"], headers=famiglia.mamma, foto_ts=foto_ts))
    assert _blocco(client, famiglia.tel_sara)["attivo"] is False
    (avviso,) = _notifiche(client, famiglia.tel_sara, "faccenda_confermata")
    assert avviso["messaggio"] == "Mamma ha approvato «Camera»: telefono e computer sbloccati"
    assert _notifiche(client, FIGLIO, "faccenda_confermata") == []
    (finite,) = _notifiche(client, GENITORE, "faccende_finite")
    assert finite["messaggio"] == "Sara ha i lavori di casa tutti approvati: telefono e computer sbloccati"
    assert finite["figlio_id"] == famiglia.sara
