"""(v3.8) Il tempo nelle due app (contratto-api.md, "v3.8 — il tempo nelle due app, e
il tempo che finisce"):

- ogni sotto-oggetto di `medie` ha `totale`, la somma dei totale_minuti degli STESSI
  giorni della media (giorni senza fotografia esclusi, una fotografia a 0 minuti
  conta), `null` senza fotografie, e un totale_minuti non valido vale 0;
- GET /api/patto?tempi=1 ha `uso_recente` e `medie` di questo dispositivo e di
  ciascuno in `dispositivi[]`, identici a quelli della finestra del genitore (limiti e
  bonus compresi, dispositivi revocati compresi);
- senza `tempi=1` (assente, 0 o un valore strano, mai un 422) il patto e' quello della
  v3.7, voce per voce, e i tempi non si calcolano: il programma del computer lo legge
  ogni minuto senza gzip.

Tutti i dati sono finti."""

import sqlite3
import uuid

import pytest
from fastapi.testclient import TestClient

import dati_v35
from aiuti_v3 import contenuto_in, dispositivo_abbinato, eventi, nuovo_dispositivo, nuovo_figlio, regola
from conftest import FIGLIO, GENITORE, TOKEN_FIGLIO, TOKEN_GENITORE, Orologio

OGGI = "2026-07-14"  # conftest: martedi' 14 luglio 2026, 10:00 UTC
TIKTOK = "com.zhiliaoapp.musically"
YOUTUBE = "com.google.android.youtube"
MINECRAFT = "exe:minecraft.exe"


def _limite(chiave, minuti):
    return {"app_o_categoria": chiave, "minuti_al_giorno": minuti}


def _foto(giorno, totale_minuti=None, **dettagli):
    """Una fotografia uso_giornaliero; senza totale_minuti se e' None."""
    if totale_minuti is not None:
        dettagli["totale_minuti"] = totale_minuti
    return {"id": f"uso-{giorno}-{uuid.uuid4().hex[:8]}", "tipo": "uso_giornaliero",
            "dettagli": {"giorno": giorno, **dettagli}}


def _foto_grezza(giorno, valore):
    """Una fotografia col totale_minuti cosi' com'e', anche sporco."""
    return {"id": f"uso-{giorno}-{uuid.uuid4().hex[:8]}", "tipo": "uso_giornaliero",
            "dettagli": {"giorno": giorno, "uso_minuti": {}, "totale_minuti": valore}}


def _patto(client, headers=FIGLIO, percorso="/api/patto?tempi=1"):
    """Il patto coi tempi (v3.8); `percorso="/api/patto"` per quello della v3.7."""
    risposta = client.get(percorso, headers=headers)
    assert risposta.status_code == 200, risposta.text
    return risposta.json()


def _finestra(client, figlio_id=1):
    risposta = client.get(f"/api/finestra?figlio_id={figlio_id}", headers=GENITORE)
    assert risposta.status_code == 200, risposta.text
    return risposta.json()


def _bonus(client, headers, regola_id, minuti):
    risposta = client.post("/api/bonus", json={"minuti": minuti, "regola_id": regola_id}, headers=headers)
    assert risposta.status_code == 200, risposta.text


def _come_la_finestra(patto, finestra) -> None:
    """Il patto di un dispositivo e la finestra del suo figlio: gli stessi dispositivi,
    e per ciascuno uso_recente e medie identici; in cima quelli del dispositivo che
    chiama."""
    nella_finestra = {d["id"]: d for d in finestra["dispositivi"]}
    assert [d["id"] for d in patto["dispositivi"]] == list(nella_finestra)
    for voce in patto["dispositivi"]:
        assert voce["uso_recente"] == nella_finestra[voce["id"]]["uso_recente"], voce["id"]
        assert voce["medie"] == nella_finestra[voce["id"]]["medie"], voce["id"]
    questo = nella_finestra[patto["dispositivo"]["id"]]
    assert patto["uso_recente"] == questo["uso_recente"]
    assert patto["medie"] == questo["medie"]


# --- medie: il totale ---

def test_il_totale_e_la_somma_degli_stessi_giorni_della_media(client):
    """Settimana = da 6 giorni fa a oggi, mese = da 29 giorni fa a oggi, nel fuso del
    patto: un giorno fuori dalla finestra non entra ne' nella media ne' nel totale."""
    eventi(client, FIGLIO,
           _foto(OGGI, 100),
           _foto("2026-07-12", 50),
           _foto("2026-07-08", 30),    # 6 giorni fa: l'ultimo della settimana
           _foto("2026-07-07", 200),   # 7 giorni fa: solo nel mese
           _foto("2026-06-15", 10),    # 29 giorni fa: l'ultimo del mese
           _foto("2026-06-14", 999))   # 30 giorni fa: fuori da tutto
    attese = {
        "settimana": {"minuti": 60, "giorni": 3, "totale": 180},
        "mese": {"minuti": 78, "giorni": 5, "totale": 390},
    }
    assert _finestra(client)["medie"] == attese
    assert _patto(client)["medie"] == attese


def test_i_giorni_senza_fotografia_non_sono_zero(client):
    """Un giorno senza fotografia non e' una riga: non abbassa la media e non conta tra
    i giorni. Una fotografia a 0 minuti invece e' un giorno vero: conta tra i giorni,
    abbassa la media, e il totale resta quello."""
    eventi(client, FIGLIO, _foto(OGGI, 90), _foto("2026-07-10", 30))
    assert _finestra(client)["medie"]["settimana"] == {"minuti": 60, "giorni": 2, "totale": 120}
    eventi(client, FIGLIO, _foto("2026-07-11", 0))
    assert _finestra(client)["medie"]["settimana"] == {"minuti": 40, "giorni": 3, "totale": 120}


def test_solo_fotografie_a_zero_minuti_danno_totale_zero_non_null(client):
    eventi(client, FIGLIO, _foto(OGGI, 0), _foto("2026-07-01", 0))
    medie = _finestra(client)["medie"]
    assert medie == {
        "settimana": {"minuti": 0, "giorni": 1, "totale": 0},
        "mese": {"minuti": 0, "giorni": 2, "totale": 0},
    }
    assert _patto(client)["medie"] == medie


def test_senza_fotografie_null_mai_uno_zero(client):
    assert _finestra(client)["medie"] == {"settimana": None, "mese": None}
    assert _patto(client)["medie"] == {"settimana": None, "mese": None}
    eventi(client, FIGLIO, _foto("2026-07-04", 45))  # 10 giorni fa: solo il mese
    attese = {"settimana": None, "mese": {"minuti": 45, "giorni": 1, "totale": 45}}
    assert _finestra(client)["medie"] == attese
    assert _patto(client)["medie"] == attese


def test_totale_minuti_non_valido_vale_zero(client):
    """Oltre 1440, mancante, negativo, testo, con la virgola, booleano: la fotografia
    c'e' (il giorno conta), ma vale 0 nella media e nella somma. 1440 vale."""
    eventi(client, FIGLIO,
           _foto_grezza(OGGI, 1440),
           _foto_grezza("2026-07-13", 1441),
           _foto("2026-07-12", None, uso_minuti={}),  # mancante
           _foto_grezza("2026-07-11", -5),
           _foto_grezza("2026-07-10", "100"),
           _foto_grezza("2026-07-09", 12.5),
           _foto_grezza("2026-07-08", True),
           _foto_grezza("2026-07-04", 61))
    finestra = _finestra(client)
    assert finestra["medie"] == {
        "settimana": {"minuti": 206, "giorni": 7, "totale": 1440},  # 1440 / 7 = 205,7
        "mese": {"minuti": 188, "giorni": 8, "totale": 1501},       # 1501 / 8 = 187,6
    }
    # uso_recente racconta gli stessi zeri: la somma dei giorni della settimana e' il totale
    settimana = finestra["uso_recente"][-7:]
    assert [v["totale_minuti"] for v in settimana] == [0, 0, 0, 0, 0, 0, 1440]
    assert sum(v["totale_minuti"] for v in settimana) == finestra["medie"]["settimana"]["totale"]
    assert _patto(client)["medie"] == finestra["medie"]


def test_righe_salvate_prima_della_v35_oltre_1440_valgono_zero(client, db_path):
    """Fino alla v3.4 il server salvava anche un totale oltre i 1440 minuti. Quelle
    righe restano nel registro: la fotografia conta come giorno con dati, ma vale 0
    nella media e nella somma, come una arrivata oggi."""
    eventi(client, FIGLIO, _foto(OGGI, 100), _foto("2026-07-13", 50))
    conn = sqlite3.connect(db_path)
    try:
        conn.execute("UPDATE uso_giornaliero SET totale_minuti = 5000 WHERE giorno = '2026-07-13'")
        conn.commit()
    finally:
        conn.close()
    finestra = _finestra(client)
    medie = finestra["medie"]
    assert medie["settimana"] == {"minuti": 50, "giorni": 2, "totale": 100}
    assert medie["mese"] == {"minuti": 50, "giorni": 2, "totale": 100}
    patto = _patto(client)
    assert patto["medie"] == medie


def test_righe_vecchie_oltre_1440_in_uso_recente_si_leggono_zero(client, db_path):
    """(v3.8) Anche nel grafico la riga vecchia oltre 1440 si legge 0 (non null: la
    fotografia c'e'), cosi' la somma dei giorni torna col totale, nel patto come nella
    finestra. Il resto della fotografia (le app) resta."""
    eventi(client, FIGLIO, _foto(OGGI, 100), _foto("2026-07-13", 50, uso_minuti={TIKTOK: 50}))
    conn = sqlite3.connect(db_path)
    try:
        conn.execute("UPDATE uso_giornaliero SET totale_minuti = 5000 WHERE giorno = '2026-07-13'")
        conn.commit()
    finally:
        conn.close()
    finestra = _finestra(client)
    ieri, oggi = finestra["uso_recente"][-2:]
    assert (ieri["totale_minuti"], oggi["totale_minuti"]) == (0, 100)
    assert ieri["app"] == [{"chiave": TIKTOK, "nome": TIKTOK, "minuti": 50}]
    giorni = [v["totale_minuti"] for v in finestra["uso_recente"][-7:] if v["totale_minuti"] is not None]
    assert sum(giorni) == finestra["medie"]["settimana"]["totale"] == 100
    assert len(giorni) == finestra["medie"]["settimana"]["giorni"] == 2
    patto = _patto(client)
    assert patto["uso_recente"] == finestra["uso_recente"]
    assert patto["medie"] == finestra["medie"]
    # 1440 resta 1440: e' il massimo di un giorno, non un valore assurdo
    conn = sqlite3.connect(db_path)
    try:
        conn.execute("UPDATE uso_giornaliero SET totale_minuti = 1440 WHERE giorno = '2026-07-13'")
        conn.commit()
    finally:
        conn.close()
    assert _finestra(client)["uso_recente"][-2]["totale_minuti"] == 1440


def test_i_giorni_si_contano_nel_fuso_del_patto(client, orologio):
    """15/07 alle 01:30 a Roma (23:30 UTC del 14): oggi e' il 15, la settimana parte
    dal 9 e l'8 resta fuori."""
    eventi(client, FIGLIO, _foto("2026-07-08", 70), _foto("2026-07-09", 20))
    orologio.avanza(hours=13, minutes=30)  # 23:30 UTC
    attese = {"minuti": 20, "giorni": 1, "totale": 20}
    assert _finestra(client)["medie"]["settimana"] == attese
    assert _patto(client)["medie"]["settimana"] == attese


# --- il patto come la finestra ---

def test_il_patto_ha_i_tempi_del_telefono_come_la_finestra(client, orologio):
    """Un telefono con limiti su un'app, su una categoria e sul totale, e bonus in due
    giorni diversi: uso_recente e medie del patto sono quelli della finestra, campo per
    campo (limite, regola_id, bonus, sessioni_minuti compresi)."""
    tiktok = regola(client, FIGLIO, parametri=_limite(TIKTOK, 60))
    social = regola(client, FIGLIO, parametri=_limite("categoria:social", 120))
    totale = regola(client, FIGLIO, parametri=_limite("totale", 180))
    _bonus(client, FIGLIO, tiktok["id"], 15)
    eventi(client, FIGLIO, _foto(
        OGGI, 150, uso_minuti={TIKTOK: 70, YOUTUBE: 40}, nomi={TIKTOK: "TikTok", YOUTUBE: "YouTube"},
        uso_categorie={"categoria:social": 70, "categoria:video": 40}, sessioni_minuti=20))
    orologio.avanza(days=1)  # 15/07
    _bonus(client, FIGLIO, totale["id"], 5)
    eventi(client, FIGLIO, _foto("2026-07-15", 190, uso_minuti={TIKTOK: 50}, nomi={TIKTOK: "TikTok"},
                                 uso_categorie={"categoria:social": 130}))

    patto = _patto(client)
    finestra = _finestra(client)
    _come_la_finestra(patto, finestra)
    assert patto["uso_recente"] == finestra["uso_recente"]  # primo livello = il telefono
    assert patto["medie"] == finestra["medie"]

    # e non sono uguali perche' vuoti
    ieri, oggi = patto["uso_recente"][-2:]
    assert ieri["app"][0] == {"chiave": TIKTOK, "nome": "TikTok", "minuti": 70, "limite": 60,
                              "regola_id": tiktok["id"], "bonus": 15}
    assert ieri["categorie"][0] == {"chiave": "categoria:social", "minuti": 70, "limite": 120,
                                    "regola_id": social["id"], "bonus": 0}
    assert (ieri["totale_minuti"], ieri["sessioni_minuti"], ieri["bonus"]) == (150, 20, 0)
    assert (oggi["totale_minuti"], oggi["limite"], oggi["regola_id"], oggi["bonus"]) == (
        190, 180, totale["id"], 5)
    assert oggi["app"][0]["bonus"] == 0  # il bonus su TikTok era di ieri
    assert patto["medie"]["settimana"] == {"minuti": 170, "giorni": 2, "totale": 340}


def test_il_patto_ha_i_tempi_di_tutti_i_dispositivi_del_figlio(client):
    """Telefono e computer di Andrea, telefono di Marta: dal telefono e dal computer
    si vedono i tempi di tutti e due, identici alla finestra; niente di Marta."""
    pc, pc_id = dispositivo_abbinato(client, 1, "Computer", "computer")
    marta = nuovo_figlio(client, "Marta")
    tel_marta, tel_marta_id = dispositivo_abbinato(client, marta["id"], "Telefono di Marta", "telefono")

    regola(client, FIGLIO, parametri=_limite(TIKTOK, 60))
    minecraft = regola(client, pc, parametri=_limite(MINECRAFT, 60))
    totale_pc = regola(client, pc, parametri=_limite("totale", 120))
    regola(client, tel_marta, parametri=_limite(TIKTOK, 30))
    _bonus(client, pc, minecraft["id"], 30)
    eventi(client, FIGLIO, _foto(OGGI, 80, uso_minuti={TIKTOK: 80}), _foto("2026-07-12", 40))
    eventi(client, pc, _foto(OGGI, 95, uso_minuti={MINECRAFT: 75}, nomi={MINECRAFT: "Minecraft"}),
           _foto("2026-07-13", 31, uso_minuti={MINECRAFT: 31}))
    eventi(client, tel_marta, _foto(OGGI, 300, uso_minuti={TIKTOK: 300}))

    finestra = _finestra(client)
    for headers in (FIGLIO, pc):
        patto = _patto(client, headers)
        _come_la_finestra(patto, finestra)
        assert [d["id"] for d in patto["dispositivi"]] == [1, pc_id]
    dal_telefono = {d["id"]: d for d in _patto(client)["dispositivi"]}
    computer = dal_telefono[pc_id]
    assert computer["uso_recente"][-1]["app"] == [{"chiave": MINECRAFT, "nome": "Minecraft", "minuti": 75,
                                                   "limite": 60, "regola_id": minecraft["id"], "bonus": 30}]
    assert (computer["uso_recente"][-1]["limite"], computer["uso_recente"][-1]["regola_id"]) == (
        120, totale_pc["id"])
    assert computer["medie"]["settimana"] == {"minuti": 63, "giorni": 2, "totale": 126}
    assert dal_telefono[1]["medie"]["settimana"] == {"minuti": 60, "giorni": 2, "totale": 120}
    assert _patto(client, pc)["uso_recente"] == computer["uso_recente"]

    # Marta vede solo il suo telefono, come la sua finestra
    patto_marta = _patto(client, tel_marta)
    _come_la_finestra(patto_marta, _finestra(client, marta["id"]))
    assert [d["id"] for d in patto_marta["dispositivi"]] == [tel_marta_id]
    assert patto_marta["medie"]["settimana"] == {"minuti": 300, "giorni": 1, "totale": 300}


def test_dispositivo_revocato_e_non_abbinato(client):
    """Chi c'e' in dispositivi[] non cambia: anche il computer revocato (con i limiti
    delle sue regole ancora attive, come li vede il genitore) e un tablet creato ma mai
    abbinato. Per tutti, i tempi identici alla finestra."""
    pc, pc_id = dispositivo_abbinato(client, 1, "Computer", "computer")
    tablet_id = nuovo_dispositivo(client, 1, "Tablet", "telefono")["dispositivo"]["id"]
    minecraft = regola(client, pc, parametri=_limite(MINECRAFT, 60))
    eventi(client, pc, _foto(OGGI, 70, uso_minuti={MINECRAFT: 70}))
    prima = [(d["id"], d["revocato"]) for d in _patto(client)["dispositivi"]]
    assert prima == [(1, False), (pc_id, False), (tablet_id, False)]

    assert client.delete(f"/api/dispositivi/{pc_id}", headers=GENITORE).status_code == 200
    patto = _patto(client)
    assert [(d["id"], d["revocato"]) for d in patto["dispositivi"]] == [
        (1, False), (pc_id, True), (tablet_id, False)]
    _come_la_finestra(patto, _finestra(client))
    revocato = next(d for d in patto["dispositivi"] if d["id"] == pc_id)
    assert revocato["uso_recente"][-1]["app"][0]["limite"] == 60
    assert revocato["uso_recente"][-1]["app"][0]["regola_id"] == minecraft["id"]
    assert revocato["medie"]["settimana"] == {"minuti": 70, "giorni": 1, "totale": 70}
    tablet = next(d for d in patto["dispositivi"] if d["id"] == tablet_id)
    assert tablet["medie"] == {"settimana": None, "mese": None}
    assert all(v["totale_minuti"] is None for v in tablet["uso_recente"])


def test_una_regola_eliminata_non_mette_il_limite(client, orologio):
    """Il limite accanto ai tempi e' solo delle regole attive, nel patto come nella
    finestra (anche per i giorni in cui la regola era ancora in vita)."""
    regola(client, FIGLIO, parametri=_limite(TIKTOK, 60))  # resta: non e' l'ultima
    youtube = regola(client, FIGLIO, parametri=_limite(YOUTUBE, 30))
    eventi(client, FIGLIO, _foto(OGGI, 50, uso_minuti={YOUTUBE: 50}))
    orologio.avanza(days=4)
    assert client.delete(f"/api/regole/{youtube['id']}", headers=FIGLIO).status_code == 200
    patto = _patto(client)
    _come_la_finestra(patto, _finestra(client))
    giorno = next(v for v in patto["uso_recente"] if v["giorno"] == OGGI)
    assert giorno["app"] == [{"chiave": YOUTUBE, "nome": YOUTUBE, "minuti": 50}]


# --- i campi di prima del patto ---

CHIAVI_PATTO_V37 = {
    "regole", "bonus", "bonus_oggi_per_regola", "proposte_pendenti", "dichiarazioni_in_attesa",
    "siti_recenti", "striscia", "riepilogo", "fuso", "figlio", "dispositivo", "striscia_dispositivo",
    "dispositivi", "proposte_inviate", "sessioni", "sessione_in_corso", "sessioni_svolte",
    "faccende", "blocco",
}


CHIAVI_DISPOSITIVO_V37 = {"id", "nome", "tipo", "revocato", "striscia"}
SENZA_TEMPI = ["/api/patto", "/api/patto?tempi=0", "/api/patto?tempi=", "/api/patto?tempi=true",
               "/api/patto?tempi=si", "/api/patto?tempi=01", "/api/patto?tempi=2",
               "/api/patto?tempi=99999999999999999999999", "/api/patto?altro=1"]


def _senza_tempi(patto: dict) -> dict:
    """Il patto coi tempi meno i due campi della v3.8, in cima e in dispositivi[]."""
    ridotto = {k: v for k, v in patto.items() if k not in ("uso_recente", "medie")}
    ridotto["dispositivi"] = [
        {k: v for k, v in voce.items() if k not in ("uso_recente", "medie")} for voce in patto["dispositivi"]
    ]
    return ridotto


def test_con_tempi_1_i_campi_nuovi_sono_solo_due_e_in_dispositivi_solo_due(client):
    pc, _ = dispositivo_abbinato(client, 1, "Computer", "computer")
    regola(client, FIGLIO)
    for headers in (FIGLIO, pc):
        patto = _patto(client, headers)
        assert set(patto) == CHIAVI_PATTO_V37 | {"uso_recente", "medie"}
        for voce in patto["dispositivi"]:
            assert set(voce) == CHIAVI_DISPOSITIVO_V37 | {"uso_recente", "medie"}


def test_senza_tempi_1_il_patto_e_quello_della_v37(client):
    """Assente, 0, vuoto o un valore strano: niente tempi e niente 422. Il resto e'
    identico, voce per voce, al patto con tempi=1 letto nello stesso momento."""
    pc, _ = dispositivo_abbinato(client, 1, "Computer", "computer")
    tiktok = regola(client, FIGLIO, parametri=_limite(TIKTOK, 60))
    _bonus(client, FIGLIO, tiktok["id"], 15)
    eventi(client, FIGLIO, _foto(OGGI, 80, uso_minuti={TIKTOK: 80}))
    eventi(client, pc, _foto(OGGI, 40, uso_minuti={MINECRAFT: 40}))
    for headers in (FIGLIO, pc):
        coi_tempi = _patto(client, headers)
        for percorso in SENZA_TEMPI:
            patto = _patto(client, headers, percorso)
            assert set(patto) == CHIAVI_PATTO_V37, percorso
            assert all(set(voce) == CHIAVI_DISPOSITIVO_V37 for voce in patto["dispositivi"]), percorso
            assert patto == _senza_tempi(coi_tempi), percorso


def test_senza_tempi_1_i_tempi_non_si_calcolano(client, monkeypatch):
    """Le query dei tempi si fanno solo con tempi=1."""
    from app.routes import figlio

    chiamate = []
    originale = figlio.tempi_del_dispositivo
    monkeypatch.setattr(figlio, "tempi_del_dispositivo",
                        lambda *a, **k: chiamate.append(a[3]) or originale(*a, **k))
    dispositivo_abbinato(client, 1, "Computer", "computer")
    for percorso in SENZA_TEMPI:
        _patto(client, FIGLIO, percorso)
    assert chiamate == []
    _patto(client)
    assert len(chiamate) == 2  # telefono e computer


@pytest.fixture
def orologio_v35(monkeypatch):
    from app import clock

    o = Orologio(dati_v35.ORA_V35)
    monkeypatch.setattr(clock, "now", lambda: o.corrente)
    return o


@pytest.fixture
def avvia_v35(monkeypatch, orologio_v35, tmp_path):
    """Il registro scritto dal server v3.5 (tests/dati/v35.sql: Luca col telefono e il
    computer, Sara col suo telefono, regole, fotografie, bonus, sessioni) a ORA_V35."""
    db = str(tmp_path / "nas-v35.db")
    dati_v35.crea_db_v35(db)
    monkeypatch.setenv("PACTUM_DB", db)
    monkeypatch.setenv("PACTUM_TOKEN_FIGLIO", TOKEN_FIGLIO)
    monkeypatch.setenv("PACTUM_TOKEN_GENITORE", TOKEN_GENITORE)
    from app.main import create_app

    return lambda: TestClient(create_app())


def test_su_un_registro_come_quello_vero(avvia_v35):
    """Sul registro della v3.5: senza tempi=1 il patto ha le chiavi di prima (piu'
    faccende e blocco della v3.6) coi valori di prima, voce per voce; con tempi=1 in
    piu' solo i due campi della v3.8, e il resto identico. I tempi di ogni dispositivo
    sono quelli della finestra; il totale della settimana e del mese e' la somma dei
    giorni che uso_recente racconta (tutti i dati stanno negli 8 giorni)."""
    prima = dati_v35.prima()
    with avvia_v35() as c:
        finestre = {
            figlio_id: c.get(f"/api/finestra?figlio_id={figlio_id}", headers=GENITORE).json()
            for figlio_id in (1, 2)
        }
        for nome, figlio_id in (("patto_telefono", 1), ("patto_computer", 1), ("patto_sara", 2)):
            chi = dati_v35.intestazione(nome.removeprefix("patto_"))
            senza = c.get("/api/patto", headers=chi)
            assert senza.status_code == 200, senza.text
            senza = senza.json()
            contenuto_in(prima[nome], senza, nome)
            assert set(senza) == set(prima[nome]) | {"faccende", "blocco"}, nome
            assert [set(v) for v in senza["dispositivi"]] == [set(v) for v in prima[nome]["dispositivi"]], nome
            patto = _patto(c, chi)
            assert _senza_tempi(patto) == senza, nome
            _come_la_finestra(patto, finestre[figlio_id])
            for voce in patto["dispositivi"]:
                for finestra_giorni, chiave in ((7, "settimana"), (8, "mese")):
                    giorni = [v["totale_minuti"] for v in voce["uso_recente"][-finestra_giorni:]
                              if v["totale_minuti"] is not None]
                    assert voce["medie"][chiave] == {
                        "minuti": round(sum(giorni) / len(giorni)), "giorni": len(giorni), "totale": sum(giorni),
                    }, (nome, voce["id"], chiave)
    # i numeri del telefono di Luca, per esteso: 24/09 = 75+20, poi (30+g)+25 dal 25 al 30
    telefono = next(d for d in finestre[1]["dispositivi"] if d["id"] == 1)
    assert telefono["medie"] == {
        "settimana": {"minuti": 82, "giorni": 6, "totale": 495},
        "mese": {"minuti": 84, "giorni": 7, "totale": 590},
    }
