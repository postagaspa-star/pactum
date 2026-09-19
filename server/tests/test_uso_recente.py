"""uso_recente nella finestra (v2.2, contratto-api.md): 8 voci dal piu' vecchio
a oggi dalla fotografia uso_giornaliero vigente; app ordinate per minuti
decrescenti col nome leggibile (fallback: il pacchetto); limite/regola_id SOLO
dove una regola limite_tempo ATTIVA combacia esattamente con la chiave (limite
BASE: i bonus stanno in bonus_giornalieri); giorno senza fotografia =
totale_minuti null e liste vuote, MAI uno zero finto."""

from conftest import FIGLIO, GENITORE, crea_regola

PACCHETTO_TIKTOK = "com.zhiliaoapp.musically"
PACCHETTO_IG = "com.instagram.android"


def _uso_recente(client):
    risposta = client.get("/api/finestra", headers=GENITORE)
    assert risposta.status_code == 200
    return risposta.json()["uso_recente"]


def _posta_foto(client, evento_id, dettagli):
    risposta = client.post(
        "/api/eventi",
        json={"eventi": [{"id": evento_id, "tipo": "uso_giornaliero", "dettagli": dettagli}]},
        headers=FIGLIO,
    )
    assert risposta.status_code == 200
    return risposta


def _voce(voci, giorno):
    return [v for v in voci if v["giorno"] == giorno][0]


# --- forma: 8 voci dal piu' vecchio a oggi, stessa finestra del semaforo ---

def test_otto_voci_dal_piu_vecchio_a_oggi(client):
    crea_regola(client)
    voci = _uso_recente(client)
    assert len(voci) == 8
    assert voci[0]["giorno"] == "2026-07-07"
    assert voci[-1]["giorno"] == "2026-07-14"  # oggi in coda
    assert [v["giorno"] for v in voci] == sorted(v["giorno"] for v in voci)


def test_voce_completa_con_nomi_e_categorie(client):
    regola_app = crea_regola(
        client, parametri={"app_o_categoria": PACCHETTO_TIKTOK, "minuti_al_giorno": 60}
    )
    regola_cat = crea_regola(
        client, parametri={"app_o_categoria": "categoria:social", "minuti_al_giorno": 120}
    )
    _posta_foto(
        client,
        "foto-completa",
        {
            "giorno": "2026-07-14",
            "uso_minuti": {PACCHETTO_TIKTOK: 65, PACCHETTO_IG: 40},
            "totale_minuti": 192,
            "nomi": {PACCHETTO_TIKTOK: "TikTok", PACCHETTO_IG: "Instagram"},
            "uso_categorie": {"categoria:social": 130, "categoria:giochi": 20},
        },
    )
    oggi = _voce(_uso_recente(client), "2026-07-14")
    assert oggi["totale_minuti"] == 192
    assert oggi["aggiornato_ts"] == "2026-07-14T10:00:00+00:00"
    assert oggi["app"] == [
        {"chiave": PACCHETTO_TIKTOK, "nome": "TikTok", "minuti": 65,
         "limite": 60, "regola_id": regola_app["id"]},
        {"chiave": PACCHETTO_IG, "nome": "Instagram", "minuti": 40},
    ]
    assert oggi["categorie"] == [
        {"chiave": "categoria:social", "minuti": 130,
         "limite": 120, "regola_id": regola_cat["id"]},
        {"chiave": "categoria:giochi", "minuti": 20},
    ]


def test_app_ordinate_per_minuti_decrescenti(client):
    crea_regola(client)
    _posta_foto(
        client,
        "foto-ordine",
        {
            "giorno": "2026-07-14",
            "uso_minuti": {"a.poca": 5, "b.tanta": 90, "c.media": 30},
            "totale_minuti": 125,
        },
    )
    oggi = _voce(_uso_recente(client), "2026-07-14")
    assert [a["minuti"] for a in oggi["app"]] == [90, 30, 5]


# --- onesta' sui giorni senza fotografia: null, MAI zero finto ---

def test_giorno_senza_fotografia_e_null_non_zero(client):
    crea_regola(client)
    _posta_foto(
        client,
        "foto-13",
        {"giorno": "2026-07-13", "uso_minuti": {PACCHETTO_IG: 10}, "totale_minuti": 10},
    )
    voci = _uso_recente(client)
    ieri = _voce(voci, "2026-07-13")
    assert ieri["totale_minuti"] == 10
    for voce in voci:
        if voce["giorno"] == "2026-07-13":
            continue
        assert voce["totale_minuti"] is None  # nessun dato ricevuto: null, non 0
        assert voce["aggiornato_ts"] is None
        assert voce["app"] == []
        assert voce["categorie"] == []


# --- nomi: etichetta dalla fotografia, fallback sul pacchetto ---

def test_nome_mancante_ricade_sul_pacchetto(client):
    crea_regola(client)
    _posta_foto(
        client,
        "foto-nomi",
        {
            "giorno": "2026-07-14",
            "uso_minuti": {PACCHETTO_TIKTOK: 65, PACCHETTO_IG: 40},
            "totale_minuti": 105,
            "nomi": {PACCHETTO_TIKTOK: "TikTok"},  # Instagram senza etichetta
        },
    )
    nomi = {a["chiave"]: a["nome"] for a in _voce(_uso_recente(client), "2026-07-14")["app"]}
    assert nomi[PACCHETTO_TIKTOK] == "TikTok"
    assert nomi[PACCHETTO_IG] == PACCHETTO_IG  # fallback: il pacchetto


def test_fotografia_vecchia_senza_nomi_ne_categorie(client):
    """Le fotografie pre-v2.2 non hanno nomi/uso_categorie: tolleranza evolutiva."""
    crea_regola(client)
    _posta_foto(
        client,
        "foto-v1",
        {"giorno": "2026-07-14", "uso_minuti": {PACCHETTO_IG: 42}, "totale_minuti": 137},
    )
    oggi = _voce(_uso_recente(client), "2026-07-14")
    assert oggi["totale_minuti"] == 137
    assert oggi["app"] == [{"chiave": PACCHETTO_IG, "nome": PACCHETTO_IG, "minuti": 42}]
    assert oggi["categorie"] == []


# --- limite/regola_id: SOLO sul match esatto di una limite_tempo ATTIVA ---

def test_limite_solo_dove_la_chiave_combacia(client):
    regola = crea_regola(
        client, parametri={"app_o_categoria": PACCHETTO_TIKTOK, "minuti_al_giorno": 60}
    )
    _posta_foto(
        client,
        "foto-match",
        {
            "giorno": "2026-07-14",
            "uso_minuti": {PACCHETTO_TIKTOK: 65, PACCHETTO_IG: 40},
            "totale_minuti": 105,
        },
    )
    app = {a["chiave"]: a for a in _voce(_uso_recente(client), "2026-07-14")["app"]}
    assert app[PACCHETTO_TIKTOK]["limite"] == 60
    assert app[PACCHETTO_TIKTOK]["regola_id"] == regola["id"]
    assert "limite" not in app[PACCHETTO_IG]  # nessuna regola su Instagram
    assert "regola_id" not in app[PACCHETTO_IG]


def test_limite_di_categoria_combacia_sulla_chiave_categoria(client):
    regola = crea_regola(
        client, parametri={"app_o_categoria": "categoria:giochi", "minuti_al_giorno": 45}
    )
    _posta_foto(
        client,
        "foto-cat",
        {
            "giorno": "2026-07-14",
            "uso_minuti": {"com.supercell.clashroyale": 50},
            "totale_minuti": 50,
            "uso_categorie": {"categoria:giochi": 50, "categoria:altro": 5},
        },
    )
    oggi = _voce(_uso_recente(client), "2026-07-14")
    categorie = {c["chiave"]: c for c in oggi["categorie"]}
    assert categorie["categoria:giochi"]["limite"] == 45
    assert categorie["categoria:giochi"]["regola_id"] == regola["id"]
    assert "limite" not in categorie["categoria:altro"]
    # la regola di categoria NON si appiccica al pacchetto (match esatto)
    assert "limite" not in oggi["app"][0]


def test_regola_eliminata_non_porta_limite(client, orologio):
    crea_regola(client)  # la regola che resta (l'ultima non si elimina)
    regola = crea_regola(
        client, parametri={"app_o_categoria": PACCHETTO_IG, "minuti_al_giorno": 30}
    )
    orologio.avanza(days=4)  # 18/07: lock scaduto
    assert client.delete(f"/api/regole/{regola['id']}", headers=FIGLIO).status_code == 200
    _posta_foto(
        client,
        "foto-dopo-delete",
        {"giorno": "2026-07-18", "uso_minuti": {PACCHETTO_IG: 25}, "totale_minuti": 25},
    )
    app = _voce(_uso_recente(client), "2026-07-18")["app"]
    assert app == [{"chiave": PACCHETTO_IG, "nome": PACCHETTO_IG, "minuti": 25}]


def test_fascia_oraria_non_porta_limite(client):
    crea_regola(client)
    crea_regola(
        client,
        tipo="fascia_oraria",
        parametri={"dalle": "23:00", "alle": "07:00",
                   "giorni": ["lun", "mar", "mer", "gio", "ven", "sab", "dom"]},
    )
    _posta_foto(
        client,
        "foto-fascia",
        {"giorno": "2026-07-14", "uso_minuti": {PACCHETTO_IG: 10}, "totale_minuti": 10},
    )
    app = _voce(_uso_recente(client), "2026-07-14")["app"]
    assert "limite" not in app[0]


def test_limite_e_quello_base_senza_bonus(client):
    """Il limite mostrato e' minuti_al_giorno: il bonus del giorno non lo gonfia
    (i bonus sono gia' visibili in bonus_giornalieri)."""
    regola = crea_regola(
        client, parametri={"app_o_categoria": PACCHETTO_TIKTOK, "minuti_al_giorno": 60}
    )
    risposta = client.post(
        "/api/bonus", json={"minuti": 15, "regola_id": regola["id"]}, headers=FIGLIO
    )
    assert risposta.status_code == 200
    _posta_foto(
        client,
        "foto-bonus",
        {"giorno": "2026-07-14", "uso_minuti": {PACCHETTO_TIKTOK: 70}, "totale_minuti": 70},
    )
    app = _voce(_uso_recente(client), "2026-07-14")["app"]
    assert app[0]["limite"] == 60  # base, non 75


# --- la voce segue la fotografia VIGENTE (monotona su totale_minuti) ---

def test_aggiornato_ts_e_totale_seguono_la_vigente(client, orologio):
    crea_regola(client)
    _posta_foto(
        client,
        "foto-mattina",
        {"giorno": "2026-07-14", "uso_minuti": {PACCHETTO_IG: 30}, "totale_minuti": 30},
    )
    orologio.avanza(hours=2)
    _posta_foto(
        client,
        "foto-mezzogiorno",
        {"giorno": "2026-07-14", "uso_minuti": {PACCHETTO_IG: 90}, "totale_minuti": 90},
    )
    orologio.avanza(hours=1)
    _posta_foto(  # in ritardo, totale piu' basso: NON regredisce la vigente
        client,
        "foto-in-ritardo",
        {"giorno": "2026-07-14", "uso_minuti": {PACCHETTO_IG: 10}, "totale_minuti": 10},
    )
    oggi = _voce(_uso_recente(client), "2026-07-14")
    assert oggi["totale_minuti"] == 90
    assert oggi["aggiornato_ts"] == "2026-07-14T12:00:00+00:00"
    assert oggi["app"][0]["minuti"] == 90


def test_fotografia_sporca_non_fa_crollare_la_finestra(client):
    """Minuti non numerici o negativi nella fotografia: si scartano le voci
    malate, la finestra risponde comunque."""
    crea_regola(client)
    _posta_foto(
        client,
        "foto-sporca",
        {
            "giorno": "2026-07-14",
            "uso_minuti": {PACCHETTO_IG: 42, "b.rotta": "tanti", "c.negativa": -5},
            "totale_minuti": 42,
            "nomi": "non-un-dizionario",
            "uso_categorie": ["non", "un", "dizionario"],
        },
    )
    oggi = _voce(_uso_recente(client), "2026-07-14")
    assert oggi["app"] == [{"chiave": PACCHETTO_IG, "nome": PACCHETTO_IG, "minuti": 42}]
    assert oggi["categorie"] == []


def _regole_finestra(client):
    return {r["id"]: r for r in client.get("/api/finestra", headers=GENITORE).json()["regole"]}


def test_regola_su_pacchetto_porta_il_nome_leggibile(client):
    """(S2) Il genitore legge "TikTok", non com.zhiliaoapp.musically: il nome
    arriva dall'ultima fotografia che lo conosce, anche per le regole eliminate."""
    tiktok = crea_regola(client, parametri={"app_o_categoria": "com.zhiliaoapp.musically", "minuti_al_giorno": 60})["id"]
    social = crea_regola(client, parametri={"app_o_categoria": "categoria:social", "minuti_al_giorno": 120})["id"]
    _posta_foto(client, "foto-nomi", {
        "giorno": "2026-07-14", "uso_minuti": {"com.zhiliaoapp.musically": 40},
        "totale_minuti": 40, "nomi": {"com.zhiliaoapp.musically": "TikTok"},
    })
    regole = _regole_finestra(client)
    assert regole[tiktok]["nome"] == "TikTok"
    assert "nome" not in regole[social]  # le categorie le traduce l'app


def test_regola_senza_nome_noto_ricade_sul_pacchetto(client):
    regola = crea_regola(client, parametri={"app_o_categoria": "com.esempio.app", "minuti_al_giorno": 30})["id"]
    assert _regole_finestra(client)[regola]["nome"] == "com.esempio.app"
