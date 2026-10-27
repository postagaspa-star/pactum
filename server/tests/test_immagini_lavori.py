"""(v4.3) Le immagini allegate dal genitore a un lavoro di casa: al massimo 3, JPEG fino a
4 MB, pulite dei dati nascosti, lette dal genitore e dai dispositivi dello stesso figlio,
solo su un lavoro ancora aperto; tolte dalla pulizia 30 giorni dopo la chiusura."""

import os
import sqlite3
from datetime import datetime, timedelta, timezone

from conftest import FIGLIO, GENITORE
from test_faccende import EXIF, MEGA, _date, _foto, _ok, famiglia, jpeg  # noqa: F401 (famiglia: fixture)

from app import allegati, faccende


def _allega(client, faccenda_id, n=1, dati=None, headers=GENITORE):
    return client.put(
        f"/api/faccende/{faccenda_id}/immagini/{n}",
        content=jpeg(EXIF) if dati is None else dati,
        headers={**headers, "Content-Type": "image/jpeg"},
    )


def test_il_genitore_allega_e_tutta_la_famiglia_la_vede_pulita(client, famiglia):
    (letto,) = _date(client, "Rifai il letto")
    assert letto["immagini"] == []
    fatta = _ok(_allega(client, letto["id"], 1))
    assert [i["n"] for i in fatta["immagini"]] == [1]
    _ok(_allega(client, letto["id"], 2, headers=famiglia.mamma))
    for headers in (GENITORE, famiglia.mamma, FIGLIO, famiglia.pc):
        r = client.get(f"/api/faccende/{letto['id']}/immagini/1", headers=headers)
        assert r.status_code == 200
        assert r.headers["content-type"] == "image/jpeg"
        assert r.headers["cache-control"] == "private, no-store"
        assert r.content == jpeg()  # senza la posizione e gli altri dati nascosti
    # Un altro figlio non la vede, e senza token niente.
    r = client.get(f"/api/faccende/{letto['id']}/immagini/1", headers=famiglia.tel_sara)
    assert r.status_code == 404
    assert client.get(f"/api/faccende/{letto['id']}/immagini/1").status_code == 401
    # Le immagini nella lista del figlio e nel blocco.
    elenco = _ok(client.get("/api/faccende", headers=FIGLIO))["faccende"]
    assert [i["n"] for i in elenco[0]["immagini"]] == [1, 2]
    blocco = _ok(client.get("/api/faccende/blocco", headers=FIGLIO))
    assert [i["n"] for i in blocco["da_fare"][0]["immagini"]] == [1, 2]


def test_i_limiti(client, famiglia):
    (letto,) = _date(client, "Letto")
    for n in (0, 4, 99):
        assert _allega(client, letto["id"], n).status_code == 404
    assert _allega(client, 999).status_code == 404
    assert _allega(client, letto["id"], dati=b"non sono un jpeg").status_code == 422
    troppo = _allega(client, letto["id"], dati=jpeg(scan=b"\x01" * (MEGA + 1)))
    assert troppo.status_code == 413
    # Solo un genitore la allega.
    assert _allega(client, letto["id"], headers=FIGLIO).status_code in (401, 403)
    # Una che non c'e' non si legge.
    r = client.get(f"/api/faccende/{letto['id']}/immagini/3", headers=GENITORE)
    assert r.status_code == 404 and r.json() == {"detail": "immagine non trovata"}


def test_si_sostituisce_e_si_toglie(client, famiglia):
    (letto,) = _date(client, "Letto")
    _ok(_allega(client, letto["id"], 1, dati=jpeg(scan=b"\x01\x02")))
    _ok(_allega(client, letto["id"], 1, dati=jpeg(scan=b"\x03\x04")))
    assert client.get(f"/api/faccende/{letto['id']}/immagini/1", headers=FIGLIO).content == jpeg(scan=b"\x03\x04")
    tolta = _ok(client.delete(f"/api/faccende/{letto['id']}/immagini/1", headers=GENITORE))
    assert tolta["immagini"] == []
    assert client.delete(f"/api/faccende/{letto['id']}/immagini/1", headers=GENITORE).status_code == 404
    assert client.get(f"/api/faccende/{letto['id']}/immagini/1", headers=FIGLIO).status_code == 404


def test_su_un_lavoro_chiuso_non_si_allega_piu(client, famiglia):
    (letto,) = _date(client, "Letto")
    _ok(client.post(f"/api/faccende/{letto['id']}/annulla", headers=GENITORE))
    r = _allega(client, letto["id"])
    assert r.status_code == 409 and r.json()["detail"] == {"errore": "non_modificabile"}
    # Un lavoro fatto e ancora da approvare si', ancora.
    (cane,) = _date(client, "Cane")
    assert _foto(client, cane["id"]).status_code == 200
    assert _allega(client, cane["id"]).status_code == 200


def test_la_pulizia_toglie_le_immagini_dei_lavori_chiusi_da_un_mese(client, famiglia, db_path):
    (letto, cane) = _date(client, "Letto", "Cane")
    _ok(_allega(client, letto["id"]))
    _ok(_allega(client, cane["id"]))
    _ok(client.post(f"/api/faccende/{letto['id']}/annulla", headers=GENITORE))
    dove = allegati.cartella(faccende.cartella_foto(db_path))
    vecchio = (datetime.now(timezone.utc) - timedelta(hours=3)).timestamp()
    for nome in os.listdir(dove):
        os.utime(os.path.join(dove, nome), (vecchio, vecchio))
    conn = sqlite3.connect(db_path)
    try:
        chiusa = conn.execute("SELECT chiusa_ts FROM faccende WHERE id = ?", (letto["id"],)).fetchone()[0]
    finally:
        conn.close()
    # Il giorno dopo la chiusura restano tutte e due; 31 giorni dopo, solo quella aperta.
    adesso = datetime.fromisoformat(chiusa)
    assert allegati.pulisci(db_path, faccende.cartella_foto(db_path), adesso + timedelta(days=1)) == 0
    assert allegati.pulisci(db_path, faccende.cartella_foto(db_path), adesso + timedelta(days=31)) == 1
    assert sorted(os.listdir(dove)) == [f"{cane['id']}_1.jpg"]
