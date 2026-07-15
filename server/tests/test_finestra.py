"""La finestra del genitore: semaforo per regola (oggi + 7 giorni, giorni nel
fuso del patto, verde/rosso/grigio), sforamenti e manomissioni recenti, storico,
bonus residui + riepilogo bonus per giorno, stato silenzio."""

from datetime import datetime, timezone

from conftest import FIGLIO, GENITORE, crea_regola

CHIAVI_ATTESE = {
    "regole",
    "sforamenti_recenti",
    "manomissioni_recenti",
    "storico_modifiche",
    "bonus",
    "bonus_giornalieri",
    "stato_silenzio",
}


def _finestra(client):
    risposta = client.get("/api/finestra", headers=GENITORE)
    assert risposta.status_code == 200
    return risposta.json()


def test_forma_della_finestra(client):
    crea_regola(client)
    finestra = _finestra(client)
    assert set(finestra.keys()) == CHIAVI_ATTESE
    assert finestra["bonus"]["giorno"] == {"usati": 0, "tetto": 30, "residui": 30}
    assert finestra["bonus"]["settimana"] == {"usati": 0, "tetto": 90, "residui": 90}
    regola = finestra["regole"][0]
    assert len(regola["semaforo"]) == 8
    assert regola["semaforo"][-1]["data"] == "2026-07-14"  # oggi in coda


def test_semaforo_colori(client, orologio):
    regola = crea_regola(client)  # creata il 14/07
    orologio.avanza(days=1)  # 15/07
    client.post(
        "/api/eventi",
        json={"eventi": [{"id": "s1", "tipo": "sforamento", "dettagli": {"regola_id": regola["id"]}}]},
        headers=FIGLIO,
    )
    orologio.avanza(days=1)  # oggi = 16/07
    semaforo = {v["data"]: v["stato"] for v in _finestra(client)["regole"][0]["semaforo"]}
    assert semaforo["2026-07-13"] == "grigio"  # prima della creazione
    assert semaforo["2026-07-14"] == "verde"
    assert semaforo["2026-07-15"] == "rosso"  # sforamento
    assert semaforo["2026-07-16"] == "verde"  # oggi, nessun evento
    assert set(v["stato"] for v in _finestra(client)["regole"][0]["semaforo"]) <= {
        "verde", "rosso", "grigio",
    }  # il giallo non esiste piu'


def test_evento_bonus_usato_non_colora_il_semaforo(client):
    """Il giallo e' stato tolto: il bonus autoritativo vive nella tabella bonus
    (POST /api/bonus, senza regola_id) e si legge in bonus_giornalieri; un evento
    bonus_usato del registro non tocca i quadretti della regola."""
    regola = crea_regola(client)
    client.post(
        "/api/eventi",
        json={"eventi": [{"id": "b1", "tipo": "bonus_usato", "dettagli": {"regola_id": regola["id"]}}]},
        headers=FIGLIO,
    )
    semaforo = _finestra(client)["regole"][0]["semaforo"]
    assert semaforo[-1]["stato"] == "verde"


def test_sforamento_colora_rosso_anche_col_bonus_nello_stesso_giorno(client):
    regola = crea_regola(client)
    client.post(
        "/api/eventi",
        json={"eventi": [
            {"id": "b2", "tipo": "bonus_usato", "dettagli": {"regola_id": regola["id"]}},
            {"id": "s2", "tipo": "sforamento", "dettagli": {"regola_id": regola["id"]}},
        ]},
        headers=FIGLIO,
    )
    semaforo = _finestra(client)["regole"][0]["semaforo"]
    assert semaforo[-1]["stato"] == "rosso"


def test_manomissioni_e_sforamenti_recenti(client):
    crea_regola(client)
    client.post(
        "/api/eventi",
        json={"eventi": [
            {"id": "m1", "tipo": "manomissione", "dettagli": {"sotto_tipo": "cambio_ora"}},
            {"id": "s3", "tipo": "sforamento", "dettagli": {"regola_id": 1}},
        ]},
        headers=FIGLIO,
    )
    finestra = _finestra(client)
    assert [e["id"] for e in finestra["manomissioni_recenti"]] == ["m1"]
    assert [e["id"] for e in finestra["sforamenti_recenti"]] == ["s3"]


def test_regola_eliminata_resta_visibile_nella_finestra(client, orologio):
    crea_regola(client)
    regola = crea_regola(client, parametri={"app_o_categoria": "YouTube", "minuti_al_giorno": 120})
    orologio.avanza(days=4)
    client.delete(f"/api/regole/{regola['id']}", headers=FIGLIO)
    finestra = _finestra(client)
    assert len(finestra["regole"]) == 2  # la finestra mostra anche le eliminate
    eliminata = [r for r in finestra["regole"] if r["id"] == regola["id"]][0]
    assert eliminata["attiva"] is False
    # ma GET /regole (il patto vigente) no
    assert len(client.get("/api/regole", headers=GENITORE).json()["regole"]) == 1


def test_bonus_residui_nella_finestra(client):
    crea_regola(client)
    client.post("/api/bonus", json={"minuti": 15}, headers=FIGLIO)
    bonus = _finestra(client)["bonus"]
    assert bonus["giorno"] == {"usati": 15, "tetto": 30, "residui": 15}
    assert bonus["settimana"] == {"usati": 15, "tetto": 90, "residui": 75}


# --- giorni dopo il soft-delete: grigi, non verdi ---

def test_giorni_dopo_eliminazione_grigi(client, orologio):
    crea_regola(client)  # la regola che resta (l'ultima non si elimina)
    regola = crea_regola(client, parametri={"app_o_categoria": "YouTube", "minuti_al_giorno": 120})
    orologio.avanza(days=4)  # 18/07: lock scaduto
    assert client.delete(f"/api/regole/{regola['id']}", headers=FIGLIO).status_code == 200
    orologio.avanza(days=2)  # oggi = 20/07
    eliminata = [r for r in _finestra(client)["regole"] if r["id"] == regola["id"]][0]
    semaforo = {v["data"]: v["stato"] for v in eliminata["semaforo"]}
    assert semaforo["2026-07-13"] == "grigio"  # prima della creazione
    assert semaforo["2026-07-14"] == "verde"   # viva
    assert semaforo["2026-07-18"] == "verde"   # il giorno dell'eliminazione non e' grigio
    assert semaforo["2026-07-19"] == "grigio"  # strettamente dopo: fuori dalla vita della regola
    assert semaforo["2026-07-20"] == "grigio"


# --- bonus_giornalieri: riepilogo globale per giorno, dalla tabella bonus ---

def test_bonus_giornalieri_forma_e_somme(client, orologio):
    crea_regola(client)
    client.post("/api/bonus", json={"minuti": 15}, headers=FIGLIO)
    client.post("/api/bonus", json={"minuti": 5}, headers=FIGLIO)
    orologio.avanza(days=1)  # 15/07
    client.post("/api/bonus", json={"minuti": 30}, headers=FIGLIO)
    per_giorno = _finestra(client)["bonus_giornalieri"]
    assert len(per_giorno) == 8  # stessa finestra del semaforo, dal piu' vecchio a oggi
    assert per_giorno[-1] == {"giorno": "2026-07-15", "minuti": 30}
    assert per_giorno[-2] == {"giorno": "2026-07-14", "minuti": 20}
    assert all(v["minuti"] == 0 for v in per_giorno[:-2])  # giorni senza bonus: 0 esplicito


def test_bonus_giornalieri_ignora_gli_eventi_bonus_usato(client):
    crea_regola(client)
    client.post(
        "/api/eventi",
        json={"eventi": [{"id": "b-ev", "tipo": "bonus_usato", "dettagli": {"minuti": 30}}]},
        headers=FIGLIO,
    )
    per_giorno = _finestra(client)["bonus_giornalieri"]
    assert all(v["minuti"] == 0 for v in per_giorno)  # conta solo la tabella bonus autoritativa


# --- i giorni della finestra sono giorni locali del patto (Europe/Rome) ---

def test_finestra_conta_i_giorni_nel_fuso_del_patto(client, orologio):
    crea_regola(client)
    # 23:30 UTC del 14/07 = 01:30 locali del 15/07: per il patto e' gia' il 15
    orologio.vai_a(datetime(2026, 7, 14, 23, 30, 0, tzinfo=timezone.utc))
    client.post(
        "/api/eventi",
        json={"eventi": [{"id": "s-notte", "tipo": "sforamento", "dettagli": {"regola_id": 1}}]},
        headers=FIGLIO,
    )
    client.post("/api/bonus", json={"minuti": 5}, headers=FIGLIO)
    finestra = _finestra(client)
    semaforo = {v["data"]: v["stato"] for v in finestra["regole"][0]["semaforo"]}
    assert finestra["regole"][0]["semaforo"][-1]["data"] == "2026-07-15"  # oggi locale
    assert semaforo["2026-07-15"] == "rosso"  # lo sforamento cade nel giorno locale giusto
    assert semaforo["2026-07-14"] == "verde"
    assert finestra["bonus_giornalieri"][-1] == {"giorno": "2026-07-15", "minuti": 5}
