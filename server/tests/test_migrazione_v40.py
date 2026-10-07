"""(v4.0) La migrazione da un database v3.9 (contratto-api.md, "v4.0 — F. Database e
migrazione"), sul registro scritto dal server v3.9 (tests/dati_v39.py, tests/dati/v39.sql).

- Prima di toccare il database la copia completa `.prima-v4.0-<data>`; poi, in una
  transazione sola, la riga `faccende_approvazione_dal` (e `studio_ultimo_giro`) in
  `patto`, una configurazione dello Studio approvata (versione 1, lun-ven 15:00 16:00 60
  minuti, liste vuote, orari dal giorno dopo) per ogni figlio e la riga 1 di
  studio_versioni. Nessuna tabella che c'e' gia' cambia. Una volta sola.
- Le foto di prima hanno gia' sbloccato e non tornano a bloccare; si confermano e si
  bocciano come nella v3.9.
- Gli stessi numeri di prima, piu' i campi nuovi.
- Il ritorno dalla v3.9: rimettendo l'immagine senza toccare il database (le foto
  arrivate intanto non bloccano a posteriori, le partenze di quel periodo non creano
  Studi) o rimettendo la copia (la riga nasce di nuovo).

Tutti i dati sono finti."""

import json
import shutil
import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

import dati_v24
import dati_v39
from aiuti_v3 import contenuto_in
from conftest import TOKEN_FIGLIO, TOKEN_GENITORE, Orologio

UTC = timezone.utc
ORA = "2026-10-06T10:00:00+00:00"
PARTENZA = datetime(2026, 10, 7, 13, 0, tzinfo=UTC)  # mercoledi' 15:00 a Roma
TABELLE_STUDIO = ("studio_config", "studio_versioni", "studio_svolte", "studio_tratti", "studio_partenze")
CONFIG_DELLA_FAMIGLIA = {
    "giorni": ["lun", "mar", "mer", "gio", "ven"], "inizio": "15:00", "chiusura_minima": "16:00",
    "minuti_minimi": 60,
    "telefono": {"app": [], "nomi": {}}, "computer": {"programmi": [], "nomi": {}, "firme": {}},
}
G, MAMMA, TEL, PC, SARA = (dati_v39.intestazione(chi) for chi in ("genitore", "mamma", "telefono", "computer", "sara"))


@pytest.fixture
def orologio_v39(monkeypatch):
    from app import clock

    o = Orologio(dati_v39.ORA_V39)
    monkeypatch.setattr(clock, "now", lambda: o.corrente)
    return o


@pytest.fixture
def db_v39(tmp_path):
    path = str(tmp_path / "nas-v39.db")
    dati_v39.crea_db_v39(path)
    return path


@pytest.fixture
def avvia_v39(monkeypatch, orologio_v39, db_v39):
    def _avvia():
        monkeypatch.setenv("PACTUM_DB", db_v39)
        monkeypatch.setenv("PACTUM_TOKEN_FIGLIO", TOKEN_FIGLIO)
        monkeypatch.setenv("PACTUM_TOKEN_GENITORE", TOKEN_GENITORE)
        from app.main import create_app

        return TestClient(create_app())

    return _avvia


def _copie(db_path, suffisso) -> list[Path]:
    percorso = Path(db_path)
    return sorted(percorso.parent.glob(percorso.name + suffisso + "*"))


def _ok(risposta, atteso=200):
    assert risposta.status_code == atteso, risposta.text
    return risposta.json()


def _patto(db_path) -> dict:
    conn = sqlite3.connect(db_path)
    try:
        return dict(conn.execute("SELECT chiave, valore FROM patto").fetchall())
    finally:
        conn.close()


def _sql(db_path, sql, parametri=()):
    conn = sqlite3.connect(db_path)
    try:
        conn.execute(sql, parametri)
        conn.commit()
    finally:
        conn.close()


def _luca(c) -> dict:
    return {f["titolo"]: f for f in _ok(c.get("/api/faccende", headers=G))["faccende"]}


# --- la copia e le righe nuove ---

def test_prima_di_migrare_la_copia_completa(avvia_v39, db_v39):
    prima = dati_v24.righe(db_v39)
    with avvia_v39() as c:
        assert c.get("/api/faccende", headers=G).status_code == 200
    (copia,) = _copie(db_v39, ".prima-v4.0-")
    assert copia.name == "nas-v39.db.prima-v4.0-20261006-120000"  # 10:00 UTC = 12:00 a Roma
    conn = sqlite3.connect(copia)
    try:
        assert conn.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    finally:
        conn.close()
    assert dati_v24.righe(str(copia)) == prima  # la copia e' il database di prima, intero
    for suffisso in (".prima-v3-", ".prima-v3.4-", ".prima-v3.6-", ".prima-v3.9-"):
        assert _copie(db_v39, suffisso) == []  # era gia' v3.9
    assert not list(Path(db_v39).parent.glob("*.parziale"))


def test_niente_si_perde_e_le_righe_nuove(avvia_v39, db_v39):
    prima = dati_v24.righe(db_v39)
    with avvia_v39():
        pass
    dopo = dati_v24.righe(db_v39)
    for tabella, righe in prima.items():
        if tabella == "patto":
            continue
        assert dopo[tabella] == righe, tabella  # nessuna tabella che c'e' gia' cambia
    patto = {r["chiave"]: r["valore"] for r in dopo["patto"]}
    assert patto == {**{r["chiave"]: r["valore"] for r in prima["patto"]},
                     "faccende_approvazione_dal": ORA, "studio_ultimo_giro": ORA}
    # lo Studio di ogni figlio che c'e': approvato, versione 1, dal giorno dopo
    assert [(r["figlio_id"], r["stato"], r["versione"], r["orari_dal"], r["approvata_ts"], r["decisa_genitore_id"],
             r["in_attesa"]) for r in dopo["studio_config"]] == [
        (1, "approvata", 1, "2026-10-07", ORA, None, None), (2, "approvata", 1, "2026-10-07", ORA, None, None)]
    assert all(json.loads(r["approvata"]) == CONFIG_DELLA_FAMIGLIA for r in dopo["studio_config"])
    assert [(r["figlio_id"], r["versione"], json.loads(r["contenuto"]), r["orari_dal"]) for r in dopo["studio_versioni"]] == [
        (1, 1, CONFIG_DELLA_FAMIGLIA, "2026-10-07"), (2, 1, CONFIG_DELLA_FAMIGLIA, "2026-10-07")]
    assert dopo["studio_svolte"] == dopo["studio_tratti"] == dopo["studio_partenze"] == []
    conn = sqlite3.connect(db_v39)
    try:
        assert conn.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        assert conn.execute("PRAGMA foreign_key_check").fetchall() == []
        with pytest.raises(sqlite3.IntegrityError, match="versioni approvate"):
            conn.execute("UPDATE studio_versioni SET orari_dal = '2026-01-01'")
        with pytest.raises(sqlite3.IntegrityError, match="versioni approvate"):
            conn.execute("DELETE FROM studio_versioni")
    finally:
        conn.close()


def test_lo_studio_della_famiglia_si_vede_dappertutto(avvia_v39):
    with avvia_v39() as c:
        for headers, figlio_id in ((G, 1), (MAMMA, 2)):
            studio = _ok(c.get("/api/studio", headers=headers, params={"figlio_id": figlio_id}))
            config = studio["config"]
            assert (config["stato"], config["versione"], config["in_attesa"], config["motivazione"]) == (
                "approvata", 1, None, None)
            assert {k: config["approvata"][k] for k in CONFIG_DELLA_FAMIGLIA} == CONFIG_DELLA_FAMIGLIA
            assert (config["approvata"]["orari_dal"], config["approvata"]["decisa_da"]) == ("2026-10-07", None)
            # i telefoni sono alla 0.17: nessuna partenza per i dispositivi
            assert (studio["in_corso"], studio["prossime_partenze"], studio["recenti"]) == (None, [], [])
        versioni = _ok(c.get("/api/studio/versioni", headers=TEL))["versioni"]
        assert [(v["versione"], v["decisa_da"]) for v in versioni] == [(1, None)]
        # un figlio creato dopo la migrazione nasce senza Studio
        nuovo = _ok(c.post("/api/figli", json={"nome": "Marta"}, headers=G), 201)
        assert _ok(c.get("/api/studio", headers=G, params={"figlio_id": nuovo["id"]}))["config"]["stato"] == "nessuna"


def test_dopo_la_migrazione_gli_stessi_numeri_di_prima(avvia_v39):
    """Le letture del server v3.9 su questo database a ORA_V39 (dati/v39_prima.json): la
    v4.0 aggiunge solo campi (da_approvare, rimandato, studio, stato e foto_ts nelle voci
    del blocco, i conteggi nuovi), voce per voce lo stesso."""
    prima = dati_v39.prima()
    with avvia_v39() as c:
        for nome, percorso, chi in dati_v39.LETTURE:
            risposta = c.get(percorso, headers=dati_v39.intestazione(chi))
            assert risposta.status_code == 200, (nome, risposta.text)
            dopo = risposta.json()
            contenuto_in(prima[nome], dopo, nome)
            if nome.startswith("blocco"):
                assert set(dopo) == set(prima[nome]) | {"rimandato", "studio"}, nome
                assert all((v["stato"], v["foto_ts"]) == ("da_fare", None) for v in dopo["da_fare"]), nome
        # le foto di prima: niente da approvare, la lavatrice (foto di 2 ore fa) non blocca
        luca = _luca(c)
        assert all(f["da_approvare"] is False for f in luca.values())
        blocco = _ok(c.get("/api/faccende/blocco", headers=TEL))
        assert "Stendi la lavatrice" not in [v["titolo"] for v in blocco["da_fare"]]
        famiglia = _ok(c.get("/api/famiglia", headers=G))["figli"]
        assert [(f["faccende_da_approvare"], f["blocco_rimandato"], f["studio_in_corso"], f["studio_da_approvare"])
                for f in famiglia] == [(0, False, False, 0), (0, False, False, 0)]
        assert c.get(f"/api/faccende/{luca['Stendi la lavatrice']['id']}/foto", headers=G).status_code == 200


def test_le_foto_di_prima_restano_come_nella_v39(avvia_v39, orologio_v39, db_v39):
    with avvia_v39() as c:
        luca = _luca(c)
        lavatrice, piatti = luca["Stendi la lavatrice"], luca["Lava i piatti"]
        # si conferma senza foto_ts: un riconoscimento, coi testi della v3.9
        confermata = _ok(c.post(f"/api/faccende/{piatti['id']}/conferma", headers=MAMMA))
        assert confermata["confermata_da"] == {"id": 2, "nome": "Mamma"}
        avvisi = [n for n in _ok(c.get("/api/notifiche", headers=TEL))["notifiche"] if n["tipo"] == "faccenda_confermata"]
        assert avvisi[-1]["messaggio"] == "Mamma ha confermato «Lava i piatti»"
        assert "sblocca" not in avvisi[-1]["payload"]
        # si boccia entro 24 ore, e allora torna a bloccare
        assert c.post(f"/api/faccende/{piatti['id']}/boccia", headers=G).status_code == 409  # confermata
        orologio_v39.avanza(hours=21, minutes=50)  # la lavatrice ha 23 ore e 30 minuti
        bocciata = _ok(c.post(f"/api/faccende/{lavatrice['id']}/boccia", headers=MAMMA))
        assert bocciata["stato"] == "da_fare"
        assert "Stendi la lavatrice" in [v["titolo"] for v in _ok(c.get("/api/faccende/blocco", headers=TEL))["da_fare"]]
        # la foto nuova, dopo la bocciatura, e' della v4.0: aspetta l'approvazione
        rifatta = _ok(c.put(f"/api/faccende/{lavatrice['id']}/foto", content=dati_v39.jpeg(b"rifatta"),
                            headers={**TEL, "Content-Type": "image/jpeg"}))
        assert rifatta["da_approvare"] is True


def test_la_foto_di_prima_si_cancella_30_giorni_dopo_l_arrivo(avvia_v39, orologio_v39, db_v39):
    from app import faccende

    with avvia_v39() as c:
        letto = _luca(c)["Rifai il letto"]
    file = Path(db_v39).parent / "foto" / f"{letto['id']}.jpg"
    assert file.exists()
    orologio_v39.vai_a(datetime(2026, 11, 3, 9, 30, 1, tzinfo=UTC))  # 30 giorni e un secondo dall'arrivo
    faccende.pulisci_foto(db_v39, orologio_v39.corrente)
    assert not file.exists()


def test_la_migrazione_v40_si_fa_una_volta_sola(avvia_v39, db_v39, orologio_v39):
    with avvia_v39():
        pass
    dopo_la_prima = dati_v24.righe(db_v39)
    orologio_v39.avanza(hours=1)
    with avvia_v39():
        pass
    assert dati_v24.righe(db_v39) == dopo_la_prima
    assert len(_copie(db_v39, ".prima-v4.0-")) == 1


def test_una_migrazione_che_non_riesce_lascia_il_database_com_era(avvia_v39, db_v39, monkeypatch):
    from app import db

    prima = dati_v24.righe(db_v39)

    def rotta(_ora):
        raise RuntimeError("corrente saltata a meta' migrazione")

    vera = db.domani_nel_patto
    monkeypatch.setattr(db, "domani_nel_patto", rotta)
    with pytest.raises(RuntimeError, match="corrente saltata"):
        with avvia_v39():
            pass
    dopo = dati_v24.righe(db_v39)
    assert all(dopo.pop(tabella) == [] for tabella in TABELLE_STUDIO)  # nate con lo SCHEMA, vuote
    assert dopo == prima
    (copia,) = _copie(db_v39, ".prima-v4.0-")
    monkeypatch.setattr(db, "domani_nel_patto", vera)
    with avvia_v39() as c:
        assert _ok(c.get("/api/studio", headers=G))["config"]["stato"] == "approvata"
    assert _copie(db_v39, ".prima-v4.0-") == [copia]  # la stessa copia, non un'altra


def test_un_database_nuovo_ha_le_righe_ma_niente_studio(client, db_path):
    patto = _patto(db_path)
    assert patto["faccende_approvazione_dal"] == patto["studio_ultimo_giro"] == "2026-07-14T10:00:00+00:00"
    conn = sqlite3.connect(db_path)
    try:
        assert conn.execute("SELECT COUNT(*) FROM studio_config").fetchone()[0] == 0
    finally:
        conn.close()
    assert list(Path(db_path).parent.glob("*.prima-v4.0-*")) == []


# --- lo Studio dal giorno dopo ---

def test_lo_studio_parte_il_giorno_dopo_solo_col_telefono_018(avvia_v39, orologio_v39):
    with avvia_v39() as c:
        _ok(c.post("/api/battito", json={"versione_app": "0.18.0"}, headers=TEL))
        partenze = _ok(c.get("/api/patto", headers=TEL))["studio"]["prossime_partenze"]
        assert partenze[0] == {"giorno": "2026-10-07", "inizio_ts": "2026-10-07T13:00:00+00:00",
                               "chiudibile_dal": "2026-10-07T14:00:00+00:00", "minuti_minimi": 60}
        assert _ok(c.get("/api/patto", headers=SARA))["studio"]["prossime_partenze"] == []
        orologio_v39.vai_a(PARTENZA + timedelta(minutes=2))
        _ok(c.get("/api/famiglia", headers=G))
        luca = _ok(c.get("/api/studio", headers=TEL))["in_corso"]
        assert (luca["origine"], luca["inizio_ts"], luca["liste"]["telefono"]["app"]) == (
            "automatica", "2026-10-07T13:00:00+00:00", [])
        avvisi = [n for n in _ok(c.get("/api/notifiche", headers=MAMMA))["notifiche"]
                  if n["tipo"] in ("studio_iniziato", "studio_non_partito")]
        assert [(n["tipo"], n["figlio_id"], n["messaggio"]) for n in avvisi] == [
            ("studio_iniziato", 1, "Luca è in Studio dalle 15:00"),
            ("studio_non_partito", 2, "Lo Studio di Sara non è partito: il telefono non è aggiornato alla 0.18"),
        ]
        # il blocco dei lavori (la lavastoviglie) aspetta lo Studio
        blocco = _ok(c.get("/api/faccende/blocco", headers=PC))
        assert (blocco["attivo"], blocco["rimandato"], blocco["studio"]["id"]) == (True, True, luca["id"])


# --- tornare alla v3.9 ---

def test_tornare_alla_v39_senza_toccare_il_database(avvia_v39, db_v39, orologio_v39):
    """Modo (a): la v4.0 gira, poi si rimette l'immagine v3.9 (che sblocca alla foto e non
    conosce lo Studio), poi di nuovo la v4.0. Le foto arrivate intanto non tornano a
    bloccare; le partenze cadute intanto non creano Studi; quelle dopo si'."""
    with avvia_v39() as c:
        _ok(c.post("/api/battito", json={"versione_app": "0.18.0"}, headers=TEL))
        lavastoviglie = _luca(c)["Svuota la lavastoviglie"]
    dal_prima = _patto(db_v39)["faccende_approvazione_dal"]
    # la v3.9 (simulata nel database come la scriverebbe lei): battiti, e la foto della
    # lavastoviglie che per lei sblocca, mercoledi' alle 15:30 di Roma
    alle = "2026-10-07T13:30:00+00:00"
    _sql(db_v39, "INSERT INTO battiti (versione_app, ts_server, dispositivo_id) VALUES ('0.18.0', ?, 1)", (alle,))
    _sql(db_v39, "UPDATE faccende SET stato = 'fatta', foto_ts = ?, chiusa_ts = ? WHERE id = ?",
         (alle, alle, lavastoviglie["id"]))
    orologio_v39.vai_a(datetime(2026, 10, 7, 14, 0, tzinfo=UTC))
    with avvia_v39() as c:
        assert _patto(db_v39)["faccende_approvazione_dal"] == "2026-10-07T14:00:00+00:00" != dal_prima
        assert _luca(c)["Svuota la lavastoviglie"]["da_approvare"] is False
        assert "Svuota la lavastoviglie" not in [v["titolo"] for v in _ok(c.get("/api/faccende/blocco", headers=TEL))["da_fare"]]
        studio = _ok(c.get("/api/studio", headers=TEL))
        assert (studio["in_corso"], studio["recenti"]) == (None, [])  # la partenza delle 15:00 e' saltata
        # quella di domani si'
        orologio_v39.vai_a(PARTENZA + timedelta(days=1, minutes=1))
        assert _ok(c.get("/api/studio", headers=TEL))["in_corso"]["giorno"] == "2026-10-08"
    conn = sqlite3.connect(db_v39)
    try:
        assert conn.execute("SELECT giorno, esito FROM studio_partenze WHERE figlio_id = 1 ORDER BY giorno").fetchall() == [
            ("2026-10-07", "senza_studio"), ("2026-10-08", "nato")]
    finally:
        conn.close()


def test_tornare_alla_v39_una_foto_senza_battiti(avvia_v39, db_v39, orologio_v39):
    """La v3.9 ha girato per poco: nessun battito, ma una foto arrivata (per lei ha
    sbloccato). Al ritorno della v4.0 non torna a bloccare."""
    with avvia_v39() as c:
        lavastoviglie = _luca(c)["Svuota la lavastoviglie"]
        # una foto ricevuta dalla v4.0 non e' un segno della v3.9
        orologio_v39.avanza(minutes=5)
        foto = _ok(c.put(f"/api/faccende/{lavastoviglie['id']}/foto", content=dati_v39.jpeg(b"v40"),
                         headers={**TEL, "Content-Type": "image/jpeg"}))
        assert foto["da_approvare"] is True
    orologio_v39.avanza(minutes=5)
    with avvia_v39() as c:
        assert _patto(db_v39)["faccende_approvazione_dal"] == ORA
        assert _luca(c)["Svuota la lavastoviglie"]["da_approvare"] is True
        compiti = _luca(c)["Compiti di matematica"]
    alle = "2026-10-06T10:20:00+00:00"
    _sql(db_v39, "UPDATE faccende SET stato = 'fatta', foto_ts = ?, chiusa_ts = ? WHERE id = ?",
         (alle, alle, compiti["id"]))
    orologio_v39.avanza(minutes=20)
    with avvia_v39() as c:
        assert _patto(db_v39)["faccende_approvazione_dal"] == "2026-10-06T10:30:00+00:00"
        luca = _luca(c)
        assert luca["Compiti di matematica"]["da_approvare"] is False
        # quella arrivata alla v4.0 prima del ritorno: per la v3.9 era un lavoro fatto,
        # quindi sbloccato; anche lei non torna a bloccare (la riga si sposta per tutte)
        assert luca["Svuota la lavastoviglie"]["da_approvare"] is False


def test_tornare_alla_v39_con_uno_studio_aperto(avvia_v39, db_v39, orologio_v39):
    """(Correzione) Lo Studio di mercoledi' e' aperto quando si rimette la v3.9 (per lei il
    patto non ha `studio`: il telefono ne esce e nessuno lo puo' chiudere). Al ritorno
    della v4.0 il giorno dopo si chiude `non_chiuso` all'ultimo giro della v4.0, senza
    l'avviso «non e' stato chiuso» (non e' una colpa di Luca), e non torna in corso."""
    with avvia_v39() as c:
        _ok(c.post("/api/battito", json={"versione_app": "0.18.0"}, headers=TEL))
        orologio_v39.vai_a(PARTENZA + timedelta(minutes=1))
        aperto = _ok(c.get("/api/studio", headers=TEL))["in_corso"]
        assert aperto is not None
    # la v3.9 gira: un battito alle 15:20 di Roma
    _sql(db_v39, "INSERT INTO battiti (versione_app, ts_server, dispositivo_id) VALUES ('0.18.0', ?, 1)",
         ("2026-10-07T13:20:00+00:00",))
    orologio_v39.vai_a(datetime(2026, 10, 8, 8, 0, tzinfo=UTC))  # giovedi' mattina, di nuovo la v4.0
    with avvia_v39() as c:
        studio = _ok(c.get("/api/studio", headers=TEL))
        assert studio["in_corso"] is None
        (chiuso,) = [s for s in studio["recenti"] if s["id"] == aperto["id"]]
        assert (chiuso["chiusura"], chiuso["fine_ts"], chiuso["minuti_alla_chiusura"]) == (
            "non_chiuso", "2026-10-07T13:01:00+00:00", 0)
        avvisi = _ok(c.get("/api/notifiche", headers=G))["notifiche"]
        assert [n for n in avvisi if n["tipo"] == "studio_non_chiuso"] == []
        blocco = _ok(c.get("/api/faccende/blocco", headers=PC))
        assert blocco["rimandato"] is False and blocco["studio"]["in_corso"] is False


def test_un_riavvio_della_v40_non_sposta_niente(avvia_v39, db_v39, orologio_v39):
    with avvia_v39() as c:
        _ok(c.post("/api/battito", json={"versione_app": "0.18.0"}, headers=TEL))
    patto = _patto(db_v39)
    orologio_v39.avanza(hours=2)
    with avvia_v39():
        pass
    assert _patto(db_v39)["faccende_approvazione_dal"] == patto["faccende_approvazione_dal"]


def test_tornare_alla_v39_rimettendo_la_copia(avvia_v39, db_v39, orologio_v39):
    """Modo (b): si rimette la copia .prima-v4.0- (si perde quello che e' successo dopo):
    la riga non c'e', e nasce di nuovo al primo avvio della v4.0 con l'ora di quell'avvio."""
    with avvia_v39():
        pass
    (copia,) = _copie(db_v39, ".prima-v4.0-")
    shutil.copyfile(copia, db_v39)
    assert "faccende_approvazione_dal" not in _patto(db_v39)
    orologio_v39.avanza(hours=3)
    with avvia_v39():
        pass
    assert _patto(db_v39)["faccende_approvazione_dal"] == "2026-10-06T13:00:00+00:00"
    assert _copie(db_v39, ".prima-v4.0-") == [copia]  # gli stessi dati: la stessa copia
