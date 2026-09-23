"""(v3) Il computer (contratto-api.md, "Computer: cosa cambia nel registro" e
"Sul computer"): chiavi exe:/sito:/categoria: (422 quelle che non vanno per il tipo
del dispositivo), `minuti` nei siti e ordinamento per minuti, `sospensione` e
`ripresa` (spento non e' silente, solo per i computer), nuovi sotto_tipo di
manomissione, pactum-computer.zip."""

import pytest
from fastapi.testclient import TestClient

from aiuti_v3 import dispositivo_abbinato, eventi, regola
from conftest import FIGLIO, GENITORE, TOKEN_FIGLIO, TOKEN_GENITORE

OGGI = "2026-07-14"


@pytest.fixture
def pc(client):
    headers, dispositivo_id = dispositivo_abbinato(client, 1, "Computer di camera", "computer")
    return headers, dispositivo_id


def _limite(chiave, minuti=60):
    return {"app_o_categoria": chiave, "minuti_al_giorno": minuti}


def _dispositivo(client, dispositivo_id):
    finestra = client.get("/api/finestra", headers=GENITORE).json()
    (voce,) = [d for d in finestra["dispositivi"] if d["id"] == dispositivo_id]
    return voce


# --- le chiavi delle regole secondo il tipo del dispositivo ---

@pytest.mark.parametrize("chiave", [
    "exe:minecraft.exe", "exe:minecraft.windows.exe", "sito:youtube.com", "categoria:giochi",
])
def test_chiavi_del_computer(client, pc, chiave):
    headers, dispositivo_id = pc
    creata = regola(client, headers, parametri=_limite(chiave))
    assert creata["dispositivo"] == {"id": dispositivo_id, "nome": "Computer di camera", "tipo": "computer"}


@pytest.mark.parametrize("chiave", [
    "com.mojang.minecraftpe", "TikTok", "exe:", "sito:", "categoria:", "exe:Minecraft.exe",
    "sito:YouTube.com", "sito:https://youtube.com", "sito:youtube.com/watch", "exe:c:\\giochi\\mc.exe",
    "exe:mine craft.exe",
])
def test_chiavi_che_il_computer_rifiuta(client, pc, chiave):
    headers, _ = pc
    r = client.post("/api/regole", json={"tipo": "limite_tempo", "parametri": _limite(chiave)}, headers=headers)
    assert r.status_code == 422, chiave
    assert r.json()["detail"][0]["loc"] == ["parametri", "app_o_categoria"]


@pytest.mark.parametrize("chiave", ["exe:minecraft.exe", "sito:youtube.com"])
def test_il_telefono_rifiuta_le_chiavi_del_computer(client, chiave):
    r = client.post("/api/regole", json={"tipo": "limite_tempo", "parametri": _limite(chiave)}, headers=FIGLIO)
    assert r.status_code == 422


def test_il_telefono_resta_come_prima(client):
    for chiave in ("com.instagram.android", "TikTok", "categoria:social"):
        regola(client, FIGLIO, parametri=_limite(chiave))


def test_la_chiave_segue_il_dispositivo_della_regola(client, pc):
    """Si guarda il dispositivo della REGOLA, non quello che chiama: dal telefono si
    scrive una regola del computer con exe:, e non una col pacchetto Android."""
    _, dispositivo_id = pc
    ok = client.post("/api/regole", json={"tipo": "limite_tempo", "parametri": _limite("exe:roblox.exe"),
                                          "dispositivo_id": dispositivo_id}, headers=FIGLIO)
    assert ok.status_code == 201
    no = client.post("/api/regole", json={"tipo": "limite_tempo", "parametri": _limite("com.roblox.client"),
                                          "dispositivo_id": dispositivo_id}, headers=FIGLIO)
    assert no.status_code == 422


def test_modifiche_e_proposte_rispettano_la_chiave(client, pc):
    headers, _ = pc
    creata = regola(client, headers, parametri=_limite("exe:minecraft.exe"))
    r = client.patch(f"/api/regole/{creata['id']}", json={"parametri": _limite("com.mojang.minecraftpe", 30)},
                     headers=headers)
    assert r.status_code == 422
    r = client.post("/api/proposte", json={"regola_id": creata["id"],
                                           "parametri_proposti": _limite("com.mojang.minecraftpe", 30)}, headers=GENITORE)
    assert r.status_code == 422
    r = client.post("/api/proposte", json={"regola_id": creata["id"],
                                           "parametri_proposti": _limite("sito:roblox.com", 30)}, headers=GENITORE)
    assert r.status_code == 200


def test_nuove_regole_non_su_un_dispositivo_revocato(client, pc):
    _, dispositivo_id = pc
    client.delete(f"/api/dispositivi/{dispositivo_id}", headers=GENITORE)
    r = client.post("/api/regole", json={"tipo": "limite_tempo", "parametri": _limite("exe:minecraft.exe"),
                                         "dispositivo_id": dispositivo_id}, headers=FIGLIO)
    assert r.status_code == 409 and r.json()["detail"] == {"errore": "dispositivo_revocato"}


# --- i tempi del computer ---

def test_programmi_del_computer_nella_finestra(client, pc):
    headers, dispositivo_id = pc
    minecraft = regola(client, headers, parametri=_limite("exe:minecraft.exe", 60))
    regola(client, headers, parametri=_limite("sito:youtube.com", 30))
    eventi(client, headers, {"id": "uso-pc", "tipo": "uso_giornaliero", "dettagli": {
        "giorno": OGGI, "totale_minuti": 131,
        "uso_minuti": {"exe:minecraft.exe": 70, "exe:chrome.exe": 61},
        "nomi": {"exe:minecraft.exe": "Minecraft", "exe:chrome.exe": "Google Chrome"},
        "uso_categorie": {"categoria:giochi": 70, "categoria:video": 42},
    }})
    finestra = client.get("/api/finestra", headers=GENITORE).json()
    (voce_regola,) = [r for r in finestra["regole"] if r["id"] == minecraft["id"]]
    assert voce_regola["nome"] == "Minecraft"
    oggi = _dispositivo(client, dispositivo_id)["uso_recente"][-1]
    assert oggi["totale_minuti"] == 131
    assert oggi["app"][0] == {"chiave": "exe:minecraft.exe", "nome": "Minecraft", "minuti": 70,
                              "limite": 60, "regola_id": minecraft["id"], "bonus": 0}
    assert finestra["uso_recente"][-1]["totale_minuti"] is None  # primo livello: il telefono


# --- i siti del computer: minuti e ordine per minuti ---

def test_siti_del_computer_con_i_minuti(client, pc):
    headers, dispositivo_id = pc
    eventi(client, headers, {"id": "siti-pc", "tipo": "siti_giornalieri", "dettagli": {
        "giorno": OGGI,
        "domini": {"youtube.com": 7, "wikipedia.org": 12, "roblox.com": 2, "zeta.com": 1},
        "minuti": {"youtube.com": 42, "wikipedia.org": 9, "roblox.com": 42, "zeta.com": 0},
        "totale_domini": 4, "dns_cifrato": False,
    }})
    oggi = _dispositivo(client, dispositivo_id)["siti_recenti"][-1]
    assert oggi["domini"] == [
        {"dominio": "roblox.com", "visite": 2, "minuti": 42},  # a pari minuti, per dominio
        {"dominio": "youtube.com", "visite": 7, "minuti": 42},
        {"dominio": "wikipedia.org", "visite": 12, "minuti": 9},
        {"dominio": "zeta.com", "visite": 1, "minuti": 0},
    ]
    assert oggi["totale_domini"] == 4 and oggi["dns_cifrato"] is False
    # il figlio vede la stessa identica lista dal computer
    assert client.get("/api/patto", headers=headers).json()["siti_recenti"] == _dispositivo(
        client, dispositivo_id)["siti_recenti"]


def test_siti_del_computer_voci_sporche_e_domini_senza_visite(client, pc):
    headers, dispositivo_id = pc
    eventi(client, headers, {"id": "siti-sporchi", "tipo": "siti_giornalieri", "dettagli": {
        "giorno": OGGI, "domini": {"youtube.com": 3},
        "minuti": {"youtube.com": 10, "solo-minuti.com": 4, "rotto.com": "tanti", "meno.com": -1},
    }})
    oggi = _dispositivo(client, dispositivo_id)["siti_recenti"][-1]
    assert oggi["domini"] == [
        {"dominio": "youtube.com", "visite": 3, "minuti": 10},
        {"dominio": "solo-minuti.com", "visite": 0, "minuti": 4},
    ]


def test_i_siti_del_telefono_non_hanno_minuti(client, pc):
    eventi(client, FIGLIO, {"id": "siti-tel", "tipo": "siti_giornalieri", "dettagli": {
        "giorno": OGGI, "domini": {"instagram.com": 5}, "minuti": {"instagram.com": 50},
    }})
    oggi = client.get("/api/patto", headers=FIGLIO).json()["siti_recenti"][-1]
    assert oggi["domini"] == [{"dominio": "instagram.com", "visite": 5}]


def test_siti_non_leggibili_e_la_stessa_cecita_dichiarata(client, pc):
    headers, dispositivo_id = pc
    eventi(client, headers, {"id": "siti-ciechi", "tipo": "siti_giornalieri", "dettagli": {
        "giorno": OGGI, "domini": {}, "minuti": {}, "totale_domini": 0, "dns_cifrato": True,
    }})
    oggi = _dispositivo(client, dispositivo_id)["siti_recenti"][-1]
    assert oggi["dns_cifrato"] is True and oggi["totale_domini"] == 0


def test_fotografie_del_telefono_e_del_computer_non_si_pestano(client, pc):
    """Stesso giorno, due dispositivi: due fotografie vigenti. Con la chiave di
    prima (solo il giorno) la piu' alta avrebbe cancellato l'altra."""
    headers, dispositivo_id = pc
    eventi(client, FIGLIO, {"id": "uso-tel", "tipo": "uso_giornaliero",
                            "dettagli": {"giorno": OGGI, "totale_minuti": 200}})
    eventi(client, headers, {"id": "uso-pc", "tipo": "uso_giornaliero",
                             "dettagli": {"giorno": OGGI, "totale_minuti": 30}})
    finestra = client.get("/api/finestra", headers=GENITORE).json()
    per_id = {d["id"]: d for d in finestra["dispositivi"]}
    assert per_id[1]["uso_recente"][-1]["totale_minuti"] == 200
    assert per_id[dispositivo_id]["uso_recente"][-1]["totale_minuti"] == 30
    assert per_id[dispositivo_id]["medie"]["settimana"] == {"minuti": 30, "giorni": 1}


# --- spento non e' silente ---

def _silenzio(client, dispositivo_id):
    return _dispositivo(client, dispositivo_id)["stato_silenzio"]


def test_computer_spento_la_sera_non_e_un_silenzio(client, pc, orologio):
    headers, dispositivo_id = pc
    client.post("/api/battito", json={}, headers=headers)
    orologio.avanza(minutes=10)
    eventi(client, headers, {"id": "notte", "tipo": "sospensione", "dettagli": {"motivo": "spegnimento"}})
    orologio.avanza(hours=10)
    assert _silenzio(client, dispositivo_id) == {
        "ultimo_battito": "2026-07-14T10:00:00+00:00", "silente": False,
        "spento": True, "spento_dal": "2026-07-14T10:10:00+00:00",
    }
    # si riaccende: battito e ripresa, e torna normale
    client.post("/api/battito", json={}, headers=headers)
    eventi(client, headers, {"id": "mattina", "tipo": "ripresa",
                             "dettagli": {"motivo": "avvio", "avvio_sistema_ts": 1784100000000}})
    assert _silenzio(client, dispositivo_id) == {
        "ultimo_battito": "2026-07-14T20:10:00+00:00", "silente": False, "spento": False, "spento_dal": None,
    }
    # e se poi tace senza sospensione, e' silente come un telefono
    orologio.avanza(minutes=46)
    assert _silenzio(client, dispositivo_id)["silente"] is True


def test_basta_la_ripresa_anche_nello_stesso_pacco(client, pc, orologio):
    """Offline di notte: sospensione e ripresa arrivano insieme al mattino, nello
    stesso ordine della coda. Il computer e' acceso."""
    headers, dispositivo_id = pc
    eventi(client, headers,
           {"id": "s", "tipo": "sospensione", "dettagli": {"motivo": "sospensione"}},
           {"id": "r", "tipo": "ripresa", "dettagli": {"motivo": "riattivazione"}})
    stato = _silenzio(client, dispositivo_id)
    assert stato["spento"] is False and stato["spento_dal"] is None


def test_il_telefono_non_si_spegne_mai(client, orologio):
    client.post("/api/battito", json={}, headers=FIGLIO)
    eventi(client, FIGLIO, {"id": "s-tel", "tipo": "sospensione", "dettagli": {"motivo": "spegnimento"}})
    orologio.avanza(hours=2)
    stato = client.get("/api/finestra", headers=GENITORE).json()["stato_silenzio"]
    assert stato == {"ultimo_battito": "2026-07-14T10:00:00+00:00", "silente": True,
                     "spento": False, "spento_dal": None}


def test_spento_anche_nella_famiglia(client, pc):
    headers, dispositivo_id = pc
    eventi(client, headers, {"id": "notte", "tipo": "sospensione", "dettagli": {"motivo": "disconnessione"}})
    (figlio,) = client.get("/api/famiglia", headers=GENITORE).json()["figli"]
    (voce,) = [d for d in figlio["dispositivi"] if d["id"] == dispositivo_id]
    assert voce["stato_silenzio"]["spento"] is True and voce["stato_silenzio"]["silente"] is False


def test_sospensione_e_ripresa_non_sono_interruzioni_ne_notifiche(client, pc):
    headers, _ = pc
    eventi(client, headers,
           {"id": "s", "tipo": "sospensione", "dettagli": {"motivo": "spegnimento"}},
           {"id": "r", "tipo": "ripresa", "dettagli": {"motivo": "avvio"}})
    finestra = client.get("/api/finestra", headers=GENITORE).json()
    assert finestra["riepilogo"]["interruzioni"] == 0
    assert finestra["manomissioni_recenti"] == []
    assert client.get("/api/notifiche", headers=GENITORE).json()["notifiche"] == []


# --- le manomissioni nuove del computer ---

@pytest.mark.parametrize("dettagli", [
    {"sotto_tipo": "programma_chiuso", "dal": 1784050000000, "al": 1784056000000},
    {"sotto_tipo": "siti_non_leggibili"},
])
def test_manomissioni_del_computer(client, pc, dettagli):
    headers, dispositivo_id = pc
    eventi(client, headers, {"id": "m-pc", "tipo": "manomissione", "dettagli": dettagli})
    finestra = client.get("/api/finestra", headers=GENITORE).json()
    assert [(e["id"], e["dettagli"], e["dispositivo_id"]) for e in finestra["manomissioni_recenti"]] == [
        ("m-pc", dettagli, dispositivo_id),
    ]
    assert finestra["riepilogo"]["interruzioni"] == 1
    (notifica,) = client.get("/api/notifiche", headers=GENITORE).json()["notifiche"]
    assert (notifica["tipo"], notifica["figlio_id"], notifica["dispositivo_id"]) == ("manomissione", 1, dispositivo_id)
    assert notifica["payload"]["dettagli"] == dettagli


# --- versione e download ---

@pytest.fixture
def client_zip(db_path, monkeypatch, orologio, tmp_path):
    cartella = tmp_path / "apk"
    cartella.mkdir()
    monkeypatch.setenv("PACTUM_DB", db_path)
    monkeypatch.setenv("PACTUM_TOKEN_FIGLIO", TOKEN_FIGLIO)
    monkeypatch.setenv("PACTUM_TOKEN_GENITORE", TOKEN_GENITORE)
    monkeypatch.setenv("PACTUM_APK_DIR", str(cartella))
    from app.main import create_app

    with TestClient(create_app()) as c:
        yield c, cartella


def test_scarica_il_programma_per_il_computer(client_zip):
    c, cartella = client_zip
    assert c.get("/scarica/pactum-computer.zip").status_code == 404  # non ancora pubblicato
    (cartella / "pactum-computer.zip").write_bytes(b"PK\x03\x04 finto zip")
    r = c.get("/scarica/pactum-computer.zip")
    assert r.status_code == 200
    assert r.headers["content-type"] == "application/zip"
    assert r.content == b"PK\x03\x04 finto zip"
    pagina = c.get("/scarica").text
    assert "/scarica/pactum-computer.zip" in pagina and "SmartScreen" in pagina
    assert c.get("/api/versione").json()["computer"]["versione_nome"] == "0.8.0"
