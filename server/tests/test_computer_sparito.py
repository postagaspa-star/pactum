"""(v4.0) Il programma del computer non resta chiuso: la rete di sicurezza lato server
(contratto-api.md, "v4.0 — B", "La rete di sicurezza lato server").

Se un computer era acceso (nessuna sospensione) e doveva coprire per un blocco dei lavori
o per uno Studio, e smette di battere oltre la soglia del silenzio (45 minuti), il server
scrive da solo una `manomissione` `computer_sparito` (`durante`: blocco o studio, `dal`:
l'ultimo battito, `ts_server`) e avvisa i genitori senza accusare. Una volta sola per
silenzio; mai dopo uno spegnimento pulito; non oltre le 48 ore. Il resto della parte B
(guardiano, spegnimento annullato) e' del programma: il server accetta i motivi e i
sotto_tipo nuovi come gli altri. Tutti i dati sono finti."""

import sqlite3
from datetime import datetime, timedelta, timezone

from conftest import FIGLIO, GENITORE
from test_faccende import _date, _notifiche, _ok, famiglia  # noqa: F401  (famiglia e' una fixture)
from test_studio import LISTE, PARTENZA, _approva_config, _battito, _studio

UTC = timezone.utc
ORA = datetime(2026, 7, 14, 10, 0, tzinfo=UTC)
TESTO_BLOCCO = ("Il computer di Luca non risponde durante il blocco: può essere senza rete, oppure Pactum"
                " è stato fermato")


def _sparite(client, headers=GENITORE) -> list[dict]:
    finestra = _ok(client.get("/api/finestra", headers=headers))
    return [m for m in finestra["manomissioni_recenti"]
            if m["dettagli"].get("sotto_tipo") == "computer_sparito"]


def _eventi(client, headers, *eventi):
    return _ok(client.post("/api/eventi", json={"eventi": list(eventi)}, headers=headers))


def test_il_computer_che_sparisce_durante_il_blocco(client, famiglia, orologio):
    _battito(client, "0.14.0", famiglia.pc)  # alle 10:00
    _date(client, "Letto")  # blocca da subito
    orologio.avanza(minutes=44)
    _ok(client.get("/api/famiglia", headers=GENITORE))
    assert _sparite(client) == []  # non e' ancora silenzioso
    orologio.avanza(minutes=2)  # 10:46: piu' di 45 minuti senza battito
    _ok(client.get("/api/patto", headers=FIGLIO))  # una richiesta qualsiasi lo scopre
    (sparito,) = _sparite(client)
    assert sparito["dispositivo_id"] == famiglia.pc_id
    assert sparito["dettagli"] == {"sotto_tipo": "computer_sparito", "durante": "blocco",
                                   "dal": int(ORA.timestamp() * 1000), "ts_server": "2026-07-14T10:46:00+00:00"}
    for headers in (GENITORE, famiglia.mamma):
        (avviso,) = _notifiche(client, headers, "manomissione")
        assert avviso["messaggio"] == TESTO_BLOCCO
        assert avviso["dispositivo_id"] == famiglia.pc_id
        assert avviso["payload"]["dettagli"]["sotto_tipo"] == "computer_sparito"
    # una volta sola per silenzio
    orologio.avanza(minutes=30)
    for headers in (FIGLIO, famiglia.pc, famiglia.mamma):
        client.get("/api/faccende/blocco", headers=headers)
    assert len(_sparite(client)) == 1
    assert _ok(client.get("/api/famiglia", headers=GENITORE))["figli"][0]["riepilogo"]["interruzioni"] == 1


def test_un_computer_che_torna_resta_nel_registro_e_puo_sparire_di_nuovo(client, famiglia, orologio):
    _battito(client, "0.14.0", famiglia.pc)
    _date(client, "Letto")
    orologio.avanza(minutes=50)
    _battito(client, "0.14.0", famiglia.pc)  # torna: il server lo scrive prima del battito
    assert [s["dettagli"]["dal"] for s in _sparite(client)] == [int(ORA.timestamp() * 1000)]
    orologio.avanza(minutes=46)
    _ok(client.get("/api/famiglia", headers=GENITORE))
    assert len(_sparite(client)) == 2


def test_durante_lo_studio(client, famiglia, orologio):
    _battito(client, "0.18.0")
    _battito(client, "0.18.0", famiglia.pc)
    _approva_config(client, LISTE)
    orologio.vai_a(PARTENZA - timedelta(minutes=5))
    _battito(client, "0.18.0", famiglia.pc)  # acceso e vivo prima della partenza
    orologio.vai_a(PARTENZA + timedelta(minutes=10))
    _battito(client, "0.18.0", famiglia.pc)
    assert _studio(client)["in_corso"] is not None
    orologio.avanza(minutes=46)
    _ok(client.get("/api/studio", headers=FIGLIO))
    (sparito,) = _sparite(client)
    assert sparito["dettagli"]["durante"] == "studio"
    (avviso,) = _notifiche(client, GENITORE, "manomissione")
    assert avviso["messaggio"] == (
        "Il computer di Luca non risponde durante lo Studio: può essere senza rete, oppure Pactum"
        " è stato fermato")


def test_dopo_uno_spegnimento_pulito_niente(client, famiglia, orologio):
    _battito(client, "0.14.0", famiglia.pc)
    _date(client, "Letto")
    orologio.avanza(minutes=5)
    _eventi(client, famiglia.pc, {"id": "s1", "tipo": "sospensione", "dettagli": {"motivo": "spegnimento"}})
    orologio.avanza(hours=2)
    _ok(client.get("/api/famiglia", headers=GENITORE))
    assert _sparite(client) == []


def test_senza_blocco_ne_studio_niente(client, famiglia, orologio):
    _battito(client, "0.14.0", famiglia.pc)
    orologio.avanza(hours=2)
    _ok(client.get("/api/famiglia", headers=GENITORE))
    assert _sparite(client) == []
    # e un computer che non conosce il blocco (piu' vecchio della 0.13) non deve coprire
    _battito(client, "0.12.0", famiglia.pc)
    _date(client, "Letto")
    orologio.avanza(hours=1)
    _ok(client.get("/api/famiglia", headers=GENITORE))
    assert _sparite(client) == []


def test_un_blocco_partito_dopo_l_ultimo_battito_conta(client, famiglia, orologio):
    """Il programma chiuso alle 15:59, il blocco programmato alle 16:00: a quell'ora il
    computer doveva coprire e non batte."""
    _battito(client, "0.14.0", famiglia.pc)
    _date(client, "Letto", blocco_da="2026-07-14T10:30:00+00:00")
    orologio.avanza(minutes=50)
    _ok(client.get("/api/famiglia", headers=GENITORE))
    (sparito,) = _sparite(client)
    assert sparito["dettagli"]["durante"] == "blocco"


def test_un_blocco_finito_prima_che_il_silenzio_diventi_strano_non_conta(client, famiglia, orologio):
    _battito(client, "0.14.0", famiglia.pc)
    (letto,) = _date(client, "Letto")
    orologio.avanza(minutes=10)
    _ok(client.post(f"/api/faccende/{letto['id']}/annulla", headers=GENITORE))
    orologio.avanza(hours=1)
    _ok(client.get("/api/famiglia", headers=GENITORE))
    assert _sparite(client) == []


def test_oltre_le_48_ore_conta_solo_se_doveva_coprire_nelle_ultime_48(client, famiglia, orologio):
    """(Correzione) Il silenzio cominciato piu' di 48 ore fa (server spento due giorni)
    si segnala lo stesso se il computer, nelle ultime 48 ore, doveva coprire: qui il
    blocco c'e' ancora. Prima il primo istante (di 49 ore fa) si scartava, e lo stesso
    silenzio non si segnalava mai piu'."""
    _battito(client, "0.14.0", famiglia.pc)
    _date(client, "Letto")
    orologio.avanza(hours=49)  # nessuno ha chiesto niente per due giorni
    _ok(client.get("/api/famiglia", headers=GENITORE))
    (sparito,) = _sparite(client)
    assert sparito["dettagli"] == {"sotto_tipo": "computer_sparito", "durante": "blocco",
                                   "dal": int(ORA.timestamp() * 1000), "ts_server": "2026-07-16T11:00:00+00:00"}
    orologio.avanza(hours=1)
    _ok(client.get("/api/famiglia", headers=GENITORE))
    assert len(_sparite(client)) == 1  # una volta sola per silenzio


def test_un_blocco_finito_piu_di_48_ore_fa_non_conta(client, famiglia, orologio):
    """Il blocco delle 10:50 annullato alle 10:55, e nessuna richiesta che lo guardi:
    49 ore dopo quel blocco e' fuori dalle 48 ore, e adesso il computer non deve coprire."""
    _battito(client, "0.14.0", famiglia.pc)
    (camera,) = _date(client, "Camera", blocco_da="2026-07-14T10:50:00+00:00")
    orologio.vai_a(datetime(2026, 7, 14, 10, 55, tzinfo=UTC))
    _ok(client.post(f"/api/faccende/{camera['id']}/annulla", headers=GENITORE))
    orologio.avanza(hours=49)
    _ok(client.get("/api/famiglia", headers=GENITORE))
    assert _sparite(client) == []


def test_un_silenzio_cominciato_prima_della_v40_si_segnala(client, famiglia, orologio, db_path):
    """(Correzione) Pactum 0.14 ucciso e rinominato alle 10:00, blocco gia' attivo; il
    server passa alla v4.0 alle 12:00 (faccende_approvazione_dal). Il silenzio e' strano
    dalle 10:45, prima della v4.0: si cerca dalle 12:00, e lo si segnala (prima restava
    cieco per sempre, anche ai blocchi dei giorni dopo)."""
    _battito(client, "0.14.0", famiglia.pc)
    _date(client, "Letto")
    _patto(db_path, "faccende_approvazione_dal", "2026-07-14T12:00:00+00:00")
    orologio.avanza(minutes=50)  # 10:50: per la v4.0 non e' ancora il suo tempo
    _ok(client.get("/api/famiglia", headers=GENITORE))
    assert _sparite(client) == []
    orologio.vai_a(datetime(2026, 7, 14, 12, 30, tzinfo=UTC))
    _ok(client.get("/api/patto", headers=FIGLIO))
    (sparito,) = _sparite(client)
    assert sparito["dettagli"]["durante"] == "blocco"
    assert sparito["dettagli"]["dal"] == int(ORA.timestamp() * 1000)
    orologio.avanza(hours=3)
    _ok(client.get("/api/famiglia", headers=GENITORE))
    assert len(_sparite(client)) == 1


def _patto(db_path, chiave, valore):
    conn = sqlite3.connect(db_path)
    try:
        conn.execute("UPDATE patto SET valore = ? WHERE chiave = ?", (valore, chiave))
        conn.commit()
    finally:
        conn.close()


def test_l_uscita_dall_account_durante_il_blocco_non_e_uno_spegnimento_pulito(client, famiglia, orologio):
    """(Correzione) Pactum.exe rinominato mentre gira, poi Luca esce dall'account (o cambia
    utente): il programma manda `sospensione` `disconnessione` e non riparte. Il contratto
    (parte E, secondo account Windows) promette che la rete di sicurezza lo coglie: 45
    minuti dopo l'ultimo segno di vita."""
    _battito(client, "0.14.0", famiglia.pc)
    _date(client, "Letto")
    orologio.avanza(minutes=5)
    _eventi(client, famiglia.pc, {"id": "s1", "tipo": "sospensione", "dettagli": {"motivo": "disconnessione"}})
    orologio.avanza(minutes=44)  # 10:49: 44 minuti dalla disconnessione
    _ok(client.get("/api/famiglia", headers=GENITORE))
    assert _sparite(client) == []
    orologio.avanza(minutes=2)
    _ok(client.get("/api/famiglia", headers=GENITORE))
    (sparito,) = _sparite(client)
    assert sparito["dettagli"]["durante"] == "blocco"
    assert sparito["dettagli"]["dal"] == int(ORA.timestamp() * 1000)
    (avviso,) = _notifiche(client, GENITORE, "manomissione")
    assert avviso["messaggio"] == TESTO_BLOCCO
    orologio.avanza(hours=3)
    _ok(client.get("/api/famiglia", headers=GENITORE))
    assert len(_sparite(client)) == 1


def test_una_disconnessione_fuori_dal_blocco_e_dallo_studio_non_conta(client, famiglia, orologio):
    _battito(client, "0.14.0", famiglia.pc)
    _eventi(client, famiglia.pc, {"id": "s1", "tipo": "sospensione", "dettagli": {"motivo": "disconnessione"}})
    orologio.avanza(hours=3)
    _ok(client.get("/api/famiglia", headers=GENITORE))
    assert _sparite(client) == []
    # e la sospensione di Windows (il coperchio chiuso) resta pulita anche in blocco
    _battito(client, "0.14.0", famiglia.pc)
    _date(client, "Letto")
    _eventi(client, famiglia.pc, {"id": "s2", "tipo": "sospensione", "dettagli": {"motivo": "sospensione"}})
    orologio.avanza(hours=3)
    _ok(client.get("/api/famiglia", headers=GENITORE))
    assert _sparite(client) == []


def test_i_motivi_e_i_sotto_tipo_nuovi_del_programma(client, famiglia, orologio):
    """Il server li accetta come gli altri: la ripresa dopo uno spegnimento annullato e'
    un segno di vita (il computer torna acceso), le manomissioni vanno ai genitori."""
    _battito(client, "0.18.0", famiglia.pc)
    _eventi(client, famiglia.pc, {"id": "s1", "tipo": "sospensione", "dettagli": {"motivo": "spegnimento"}})
    stato = _ok(client.get("/api/famiglia", headers=GENITORE))["figli"][0]["dispositivi"][1]["stato_silenzio"]
    assert stato["spento"] is True
    orologio.avanza(minutes=1)
    _eventi(client, famiglia.pc,
            {"id": "r1", "tipo": "ripresa", "dettagli": {"motivo": "spegnimento_annullato", "avvio_sistema_ts": 1}},
            {"id": "m1", "tipo": "manomissione", "dettagli": {"sotto_tipo": "guardiano_assente", "stato": "mancante"}},
            {"id": "m2", "tipo": "manomissione", "dettagli": {"sotto_tipo": "istanza_occupata"}},
            {"id": "m3", "tipo": "manomissione", "dettagli": {"sotto_tipo": "programma_chiuso", "dal": 1, "al": 2,
                                                             "causa": "disconnessione"}},
            {"id": "m4", "tipo": "manomissione", "dettagli": {"sotto_tipo": "chiuso_durante_studio"}})
    stato = _ok(client.get("/api/famiglia", headers=GENITORE))["figli"][0]["dispositivi"][1]["stato_silenzio"]
    assert stato["spento"] is False
    assert len(_notifiche(client, GENITORE, "manomissione")) == 4


def test_ucciso_prima_del_blocco_e_colto_quando_il_blocco_parte(client, famiglia, orologio):
    """Il programma chiuso e nascosto alle 10:00 (fuori dal blocco), il blocco alle 12:00:
    il programma non ripartira' piu', e l'unica rete e' questa. Il silenzio e' strano dalle
    10:45, ma li' non doveva coprire niente; alle 12:00 si'."""
    _battito(client, "0.14.0", famiglia.pc)  # alle 10:00
    _date(client, "Letto", blocco_da="2026-07-14T12:00:00+00:00")
    orologio.avanza(minutes=90)  # 11:30: silenzioso, ma nessun blocco ancora
    _ok(client.get("/api/famiglia", headers=GENITORE))
    assert _sparite(client) == []
    orologio.avanza(minutes=31)  # 12:01
    _ok(client.get("/api/patto", headers=FIGLIO))
    (sparito,) = _sparite(client)
    assert sparito["dettagli"] == {"sotto_tipo": "computer_sparito", "durante": "blocco",
                                   "dal": int(ORA.timestamp() * 1000), "ts_server": "2026-07-14T12:01:00+00:00"}
    (avviso,) = _notifiche(client, GENITORE, "manomissione")
    assert avviso["messaggio"] == TESTO_BLOCCO


def test_ucciso_prima_dello_studio_e_colto_quando_lo_studio_parte(client, famiglia, orologio):
    _battito(client, "0.18.0")
    _approva_config(client, LISTE)
    orologio.vai_a(PARTENZA - timedelta(hours=2))
    _battito(client, "0.18.0", famiglia.pc)  # vivo alle 13:00 di Roma, poi piu' niente
    orologio.vai_a(PARTENZA - timedelta(minutes=1))
    _ok(client.get("/api/studio", headers=FIGLIO))
    assert _sparite(client) == []  # silenzioso, ma prima dello Studio non doveva coprire
    orologio.vai_a(PARTENZA + timedelta(minutes=1))
    _ok(client.get("/api/studio", headers=FIGLIO))  # nasce lo Studio delle 15:00
    (sparito,) = _sparite(client)
    assert sparito["dettagli"]["durante"] == "studio"
    assert sparito["dettagli"]["dal"] == int((PARTENZA - timedelta(hours=2)).timestamp() * 1000)
    # una volta sola per silenzio
    orologio.avanza(hours=1)
    _ok(client.get("/api/famiglia", headers=GENITORE))
    assert len(_sparite(client)) == 1


def test_spento_bene_prima_del_blocco_niente(client, famiglia, orologio):
    _battito(client, "0.14.0", famiglia.pc)
    _eventi(client, famiglia.pc, {"id": "s1", "tipo": "sospensione", "dettagli": {"motivo": "spegnimento"}})
    _date(client, "Letto", blocco_da="2026-07-14T12:00:00+00:00")
    orologio.avanza(hours=3)
    _ok(client.get("/api/famiglia", headers=GENITORE))
    assert _sparite(client) == []


def test_un_computer_013_non_conosce_lo_studio(client, famiglia, orologio):
    """Un programma che lo Studio non lo conosce non doveva coprire per lo Studio."""
    _battito(client, "0.18.0")
    _approva_config(client, LISTE)
    orologio.vai_a(PARTENZA - timedelta(hours=2))
    _battito(client, "0.14.0", famiglia.pc)
    orologio.vai_a(PARTENZA + timedelta(minutes=30))
    _ok(client.get("/api/studio", headers=FIGLIO))
    assert _studio(client)["in_corso"] is not None
    assert _sparite(client) == []
