"""(v3.2) La copia notturna del registro fatta dal server stesso (sul NAS UGOS non
c'e' un programmatore di attivita') e il ripristino di una copia all'avvio con
PACTUM_RIPRISTINA (contratto-api.md, "Copia notturna del registro")."""

import logging
import os
import sqlite3
import time
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

import pytest
from fastapi.testclient import TestClient

import dati_v24
from conftest import FIGLIO, TOKEN_FIGLIO, TOKEN_GENITORE, crea_regola


def roma(giorno: int, ore: int, minuti: int = 0) -> datetime:
    """Un'ora di luglio 2026 nel fuso del patto (ora legale, UTC+2), per l'orologio."""
    return datetime(2026, 7, giorno, ore, minuti, tzinfo=ZoneInfo("Europe/Rome")).astimezone(
        timezone.utc
    )


@pytest.fixture
def cartella(tmp_path):
    percorso = tmp_path / "backup"
    percorso.mkdir()
    return percorso


@pytest.fixture
def registro(db_path, orologio):
    from app import db

    db.init_db(db_path, 30, 90, TOKEN_FIGLIO, TOKEN_GENITORE)
    return db_path


@pytest.fixture
def avvia(monkeypatch, db_path, orologio):
    """Avvia il server con la sua cartella delle copie (e un ripristino, se chiesto)."""

    def _avvia(cartella, ripristina=None):
        monkeypatch.setenv("PACTUM_DB", db_path)
        monkeypatch.setenv("PACTUM_TOKEN_FIGLIO", TOKEN_FIGLIO)
        monkeypatch.setenv("PACTUM_TOKEN_GENITORE", TOKEN_GENITORE)
        monkeypatch.setenv("PACTUM_BACKUP_DIR", str(cartella))
        if ripristina is not None:
            monkeypatch.setenv("PACTUM_RIPRISTINA", ripristina)
        from app.main import create_app

        return TestClient(create_app())

    return _avvia


def _file(cartella) -> list[str]:
    return sorted(p.name for p in Path(cartella).iterdir())


def _app_delle_regole(client) -> list[str]:
    risposta = client.get("/api/regole", headers=FIGLIO)
    assert risposta.status_code == 200, risposta.text
    return [r["parametri"]["app_o_categoria"] for r in risposta.json()["regole"]]


def _quante_regole(path) -> int:
    conn = sqlite3.connect(path)
    try:
        return conn.execute("SELECT COUNT(*) FROM regole").fetchone()[0]
    finally:
        conn.close()


# --- la copia notturna ---


def test_la_copia_si_fa_dopo_le_3_e_non_prima(registro, cartella, orologio, caplog):
    """Le 03:00 sono quelle del patto: alle 01:00 UTC in Italia sono gia' le 03:00."""
    from app.copie import CopiaNotturna

    copia = CopiaNotturna(registro, str(cartella))
    orologio.vai_a(roma(15, 2, 59))
    assert copia.controlla() is None
    assert _file(cartella) == []

    orologio.vai_a(roma(15, 3, 0))
    with caplog.at_level(logging.INFO, logger="uvicorn.error"):
        assert copia.controlla() == "pactum-20260715.db"
    assert _file(cartella) == ["pactum-20260715.db"]  # niente .parziale in giro
    (riga,) = [r.getMessage() for r in caplog.records if "copia del registro fatta" in r.getMessage()]
    assert riga.startswith("copia del registro fatta: pactum-20260715.db (") and riga.endswith("KB)")
    conn = sqlite3.connect(cartella / "pactum-20260715.db")
    try:  # e' una copia vera del registro
        assert conn.execute(
            "SELECT valore FROM patto WHERE chiave = 'tetto_bonus_giorno'"
        ).fetchone() == ("30",)
    finally:
        conn.close()


def test_una_sola_copia_al_giorno(registro, cartella, orologio):
    from app.copie import CopiaNotturna

    copia = CopiaNotturna(registro, str(cartella))
    orologio.vai_a(roma(15, 3, 1))
    assert copia.controlla() == "pactum-20260715.db"
    scritta = os.stat(cartella / "pactum-20260715.db").st_mtime_ns

    orologio.vai_a(roma(15, 23, 59))
    assert copia.controlla() is None
    # Un riavvio nello stesso giorno trova la copia di oggi e non la rifa'.
    assert CopiaNotturna(registro, str(cartella)).controlla() is None
    assert os.stat(cartella / "pactum-20260715.db").st_mtime_ns == scritta

    orologio.vai_a(roma(16, 3, 0))
    assert copia.controlla() == "pactum-20260716.db"
    assert _file(cartella) == ["pactum-20260715.db", "pactum-20260716.db"]


def test_copia_di_recupero_all_avvio_e_salute(avvia, cartella, caplog):
    """Il NAS era spento alle 03:00 e si accende a mezzogiorno: la copia di oggi la fa
    subito, da solo. /api/salute la racconta, col nome e mai col percorso. Allo
    spegnimento il compito si ferma senza aspettare il suo prossimo giro."""
    with caplog.at_level(logging.INFO, logger="uvicorn.error"):
        with avvia(cartella) as c:
            fine = time.monotonic() + 10
            while (stato := c.get("/api/salute").json()["backup"])["copie"] == 0:
                assert time.monotonic() < fine, "la copia di recupero non e' arrivata"
                time.sleep(0.02)
            inizio_spegnimento = time.monotonic()
        spegnimento = time.monotonic() - inizio_spegnimento

    assert stato["ultima"] == "pactum-20260714.db" and stato["copie"] == 1
    assert datetime.fromisoformat(stato["quando"]).utcoffset() == timedelta(0)
    assert _file(cartella) == ["pactum-20260714.db"]
    assert any(
        r.getMessage().startswith("copia del registro fatta: pactum-20260714.db (")
        for r in caplog.records
    )
    assert spegnimento < 5


def test_tiene_le_ultime_30_e_non_tocca_nient_altro(registro, cartella, orologio):
    from app.copie import CopiaNotturna, stato_copie

    vecchie = [f"pactum-{date(2026, 6, 1) + timedelta(days=i):%Y%m%d}.db" for i in range(35)]
    altri = [
        "pactum-20260101-0300.db",  # lo script di prima
        "pactum-20260101.db.rotta",
        "pactum-20260101.db.parziale",
        "pactum-20260101.db.bak",
        "Pactum-20260102.db",
        "pactum-20261341.db",  # non e' una data
        "pactum-2026010.db",
        "copia-20260101.db",
        "leggimi.txt",
    ]
    for nome in vecchie + altri:
        (cartella / nome).write_bytes(b"x")
    (cartella / "pactum-20260103.db").mkdir()  # una cartella col nome giusto non e' una copia

    orologio.vai_a(roma(15, 3, 0))
    assert CopiaNotturna(registro, str(cartella)).controlla() == "pactum-20260715.db"

    tenute = sorted(vecchie + ["pactum-20260715.db"])[-30:]
    assert _file(cartella) == sorted(tenute + altri + ["pactum-20260103.db"])
    stato = stato_copie(str(cartella))
    assert (stato["ultima"], stato["copie"]) == ("pactum-20260715.db", 30)


def test_una_copia_rotta_resta_come_rotta(registro, cartella, orologio, monkeypatch, caplog):
    """Se la copia appena scritta non passa PRAGMA integrity_check non prende il suo
    nome: resta come .rotta, e il log lo dice. Un tentativo al giorno."""
    from app import copie

    vacuum_vero = copie._vacuum_into

    def vacuum_su_un_disco_guasto(db_path, destinazione):
        vacuum_vero(db_path, destinazione)
        with open(destinazione, "r+b") as file:  # la seconda pagina si rovina
            file.seek(4096)
            file.write(b"\xff" * 600)

    monkeypatch.setattr(copie, "_vacuum_into", vacuum_su_un_disco_guasto)
    copia = copie.CopiaNotturna(registro, str(cartella))
    orologio.vai_a(roma(15, 3, 0))
    with caplog.at_level(logging.ERROR, logger="uvicorn.error"):
        assert copia.controlla() is None
    assert _file(cartella) == ["pactum-20260715.db.rotta"]
    (errore,) = [r.getMessage() for r in caplog.records if r.levelno == logging.ERROR]
    assert "ROTTA" in errore and "pactum-20260715.db.rotta" in errore

    orologio.avanza(minutes=1)
    assert copia.controlla() is None
    assert _file(cartella) == ["pactum-20260715.db.rotta"]

    monkeypatch.setattr(copie, "_vacuum_into", vacuum_vero)
    orologio.vai_a(roma(16, 3, 0))
    assert copia.controlla() == "pactum-20260716.db"


def test_una_copia_non_riuscita_e_un_errore_nel_log(registro, cartella, orologio, monkeypatch, caplog):
    from app import copie

    def disco_pieno(*_):
        raise sqlite3.OperationalError("database or disk is full")

    monkeypatch.setattr(copie, "_vacuum_into", disco_pieno)
    orologio.vai_a(roma(15, 3, 0))
    with caplog.at_level(logging.ERROR, logger="uvicorn.error"):
        assert copie.CopiaNotturna(registro, str(cartella)).controlla() is None
    assert _file(cartella) == []  # neanche il .parziale
    (errore,) = [r.getMessage() for r in caplog.records if r.levelno == logging.ERROR]
    assert "NON riuscita" in errore and "disk is full" in errore


def test_cartella_delle_copie_assente_in_sviluppo(avvia, tmp_path, caplog):
    """In sviluppo /backup non c'e': il compito resta spento, il server parte."""
    with caplog.at_level(logging.WARNING, logger="uvicorn.error"):
        with avvia(tmp_path / "non-c-e") as c:
            assert c.app.state.copia_notturna is None
            dati = c.get("/api/salute").json()
    assert dati["stato"] == "ok" and dati["db_ok"] is True
    assert dati["backup"] == {"ultima": None, "quando": None, "copie": 0}
    assert "DISATTIVATA" in caplog.text
    assert not (tmp_path / "non-c-e").exists()


# --- il ripristino ---


def test_ripristino_di_una_copia(avvia, db_path, cartella, tmp_path, orologio, caplog):
    from app.copie import CopiaNotturna

    with avvia(tmp_path / "niente") as c:  # copia notturna spenta: la copia la facciamo qui
        crea_regola(c, parametri={"app_o_categoria": "TikTok", "minuti_al_giorno": 60})
        assert CopiaNotturna(db_path, str(cartella)).controlla() == "pactum-20260714.db"
        crea_regola(c, parametri={"app_o_categoria": "YouTube", "minuti_al_giorno": 30})
    for suffisso in ("-wal", "-shm", "-journal"):  # i compagni del registro di prima
        Path(db_path + suffisso).write_bytes(b"del registro di prima")

    with caplog.at_level(logging.INFO, logger="uvicorn.error"):
        with avvia(cartella, ripristina="pactum-20260714.db") as c:
            assert _app_delle_regole(c) == ["TikTok"]
            crea_regola(c, parametri={"app_o_categoria": "Instagram", "minuti_al_giorno": 45})

    da_parte = db_path + ".prima-del-ripristino-20260714-120000"
    for suffisso in ("-wal", "-shm", "-journal"):
        assert not os.path.exists(db_path + suffisso)
        assert Path(da_parte + suffisso).read_bytes() == b"del registro di prima"
    assert _quante_regole(da_parte) == 2  # il registro di prima, intero
    segno = Path(db_path).parent / ".ripristinato-pactum-20260714.db"
    assert segno.exists()
    (fatto,) = [r.getMessage() for r in caplog.records if "RIPRISTINO FATTO" in r.getMessage()]
    assert "togli PACTUM_RIPRISTINA dal file .env" in fatto
    assert os.path.basename(da_parte) in fatto
    assert (cartella / "pactum-20260714.db").exists()  # la copia resta dov'era

    # La riga e' ancora nel .env: il segno impedisce di rimetterla a ogni riavvio.
    caplog.clear()
    with caplog.at_level(logging.INFO, logger="uvicorn.error"):
        with avvia(cartella, ripristina="pactum-20260714.db") as c:
            assert _app_delle_regole(c) == ["TikTok", "Instagram"]
    assert "RIPRISTINO GIA' FATTO" in caplog.text
    assert "Togli PACTUM_RIPRISTINA dal file .env" in caplog.text
    messi_da_parte = [
        p.name
        for p in Path(db_path).parent.glob("*prima-del-ripristino-*")
        if not p.name.endswith(("-wal", "-shm", "-journal"))
    ]
    assert messi_da_parte == [os.path.basename(da_parte)]


def test_una_copia_v2_rimessa_viene_poi_migrata(avvia, registro, cartella):
    """Il ripristino avviene prima dell'apertura del database: una copia fatta con lo
    schema v2.4 viene migrata come al primo avvio della v3 (con la sua copia prima)."""
    dati_v24.crea_db_v24(str(cartella / "pactum-20260920.db"))
    prima = dati_v24.righe(str(cartella / "pactum-20260920.db"))
    with avvia(cartella, ripristina="pactum-20260920.db") as c:
        assert c.get("/api/salute").json()["db_ok"] is True
    dopo = dati_v24.righe(registro)
    assert {"figli", "dispositivi", "credenziali"} <= set(dopo)
    assert len(dopo["eventi"]) == len(prima["eventi"])
    assert list(Path(registro).parent.glob("pactum-test.db.prima-v3-*"))


@pytest.mark.parametrize(
    "nome", ["../pactum-20260714.db", "/backup/pactum-20260714.db", "sotto\\pactum-20260714.db", ".."]
)
def test_ripristino_rifiuta_i_percorsi(avvia, registro, cartella, nome, caplog):
    prima = Path(registro).read_bytes()
    with caplog.at_level(logging.ERROR, logger="uvicorn.error"):
        with pytest.raises(RuntimeError, match="RIPRISTINO FERMATO"):
            avvia(cartella, ripristina=nome)
    assert "senza cartelle" in caplog.text and "NON parte" in caplog.text
    assert Path(registro).read_bytes() == prima
    assert [p.name for p in Path(registro).parent.iterdir() if "ripristin" in p.name] == []


@pytest.mark.parametrize(
    "contenuto,motivo",
    [
        (b"questo non e' un registro " * 200, "rotta"),
        (b"", "rotta"),  # per SQLite un file vuoto e' sano, ma dentro non c'e' il registro
        (None, "non c'e'"),
    ],
)
def test_una_copia_rotta_o_assente_blocca_l_avvio(avvia, registro, cartella, contenuto, motivo, caplog):
    """Meglio fermo che con un registro sbagliato: il registro resta com'era."""
    if contenuto is not None:
        (cartella / "pactum-20260714.db").write_bytes(contenuto)
    prima = Path(registro).read_bytes()
    with caplog.at_level(logging.ERROR, logger="uvicorn.error"):
        with pytest.raises(RuntimeError, match="RIPRISTINO FERMATO"):
            avvia(cartella, ripristina="pactum-20260714.db")
    assert motivo in caplog.text and "NON parte" in caplog.text
    assert Path(registro).read_bytes() == prima
    assert [p.name for p in Path(registro).parent.iterdir() if "ripristin" in p.name] == []
