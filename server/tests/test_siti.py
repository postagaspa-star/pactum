"""Siti visitati (v2.3, contratto-api.md): l'evento `siti_giornalieri` (fotografia
cumulativa del giorno, vigente MONOTONA sulla coppia (totale_domini, somma delle
richieste), `dns_cifrato` appiccicoso sul giorno) e `siti_recenti` — 8 giorni,
domini ordinati per visite decrescenti, giorno senza fotografia con
totale_domini NULL e mai uno zero finto — IDENTICO in GET /api/finestra e in
GET /api/patto (tavola rotonda). Solo domini: mai URL, contenuti o ricerche.
I siti non sono infrazioni: niente notifiche, niente semaforo."""

import json
import sqlite3

from conftest import FIGLIO, GENITORE, crea_regola, fotografia_uso


def _posta(client, eventi):
    return client.post("/api/eventi", json={"eventi": eventi}, headers=FIGLIO)


def _foto(evento_id, giorno="2026-07-14", domini=None, **extra):
    dettagli = {"giorno": giorno, "domini": {} if domini is None else domini}
    dettagli.update(extra)
    return {"id": evento_id, "tipo": "siti_giornalieri", "dettagli": dettagli}


def _posta_foto(client, evento_id, **kwargs):
    risposta = _posta(client, [_foto(evento_id, **kwargs)])
    assert risposta.status_code == 200, risposta.text
    return risposta


def _vigente(db_path):
    """La fotografia vigente per giorno, letta dal database."""
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    righe = conn.execute("SELECT * FROM siti_giornalieri ORDER BY giorno").fetchall()
    conn.close()
    return {
        r["giorno"]: {
            "dettagli": json.loads(r["dettagli"]),
            "evento_id": r["evento_id"],
            "totale_domini": r["totale_domini"],
            "totale_visite": r["totale_visite"],
            "dns_cifrato": r["dns_cifrato"],
        }
        for r in righe
    }


def _siti_finestra(client):
    risposta = client.get("/api/finestra", headers=GENITORE)
    assert risposta.status_code == 200
    return risposta.json()["siti_recenti"]


def _siti_patto(client):
    risposta = client.get("/api/patto", headers=FIGLIO)
    assert risposta.status_code == 200
    return risposta.json()["siti_recenti"]


def _voce(voci, giorno):
    return [v for v in voci if v["giorno"] == giorno][0]


# --- accettazione dell'evento nuovo ---

def test_evento_siti_giornalieri_accettato(client, db_path):
    risposta = _posta(
        client,
        [_foto("foto-siti-1", domini={"instagram.com": 12, "youtube.com": 5}, totale_domini=2)],
    )
    assert risposta.status_code == 200
    assert risposta.json() == {"ricevuti": 1, "nuovi": 1, "duplicati": 0}
    vigente = _vigente(db_path)["2026-07-14"]
    assert vigente["evento_id"] == "foto-siti-1"
    assert vigente["totale_domini"] == 2
    assert vigente["totale_visite"] == 17


def test_evento_siti_giornalieri_idempotente(client):
    foto = _foto("foto-doppia", domini={"instagram.com": 3})
    _posta(client, [foto])
    assert _posta(client, [foto]).json() == {"ricevuti": 1, "nuovi": 0, "duplicati": 1}


def test_registro_conserva_tutte_le_fotografie(client, db_path):
    _posta_foto(client, "s1", domini={"instagram.com": 5})
    _posta_foto(client, "s2", domini={"instagram.com": 9})
    conn = sqlite3.connect(db_path)
    quante = conn.execute(
        "SELECT COUNT(*) FROM eventi WHERE tipo = 'siti_giornalieri'"
    ).fetchone()[0]
    conn.close()
    assert quante == 2  # il registro non dimentica, la vigente e' una sola
    assert len(_vigente(db_path)) == 1


def test_tipo_sconosciuto_resta_422(client):
    # Il vocabolario e' chiuso: siti_giornalieri entra, "siti" no.
    assert _posta(client, [{"id": "x", "tipo": "siti", "dettagli": {}}]).status_code == 422


def test_siti_non_notificano(client):
    """Un sito visitato non e' uno sforamento: nessuna notifica al genitore."""
    _posta_foto(client, "foto-muta", domini={"instagram.com": 100})
    notifiche = client.get("/api/notifiche", headers=GENITORE).json()["notifiche"]
    assert notifiche == []


def test_siti_non_tingono_il_semaforo(client):
    regola = crea_regola(client)
    fotografia_uso(client, "2026-07-14")  # (v2.4) il verde vuole i dati d'uso
    _posta_foto(client, "foto-semaforo", domini={"instagram.com": 999})
    finestra = client.get("/api/finestra", headers=GENITORE).json()
    semaforo = [r for r in finestra["regole"] if r["id"] == regola["id"]][0]["semaforo"]
    assert semaforo[-1]["stato"] == "verde"  # oggi resta verde


def test_giorno_non_valido_resta_solo_nel_registro(client, db_path):
    _posta(
        client,
        [
            {
                "id": "foto-zoppa",
                "tipo": "siti_giornalieri",
                "dettagli": {"giorno": "2026-7-4", "domini": {"instagram.com": 3}},
            },
            {"id": "foto-senza-giorno", "tipo": "siti_giornalieri", "dettagli": {}},
        ],
    )
    assert _vigente(db_path) == {}  # niente giorno valido, niente fotografia vigente
    conn = sqlite3.connect(db_path)
    quante = conn.execute(
        "SELECT COUNT(*) FROM eventi WHERE tipo = 'siti_giornalieri'"
    ).fetchone()[0]
    conn.close()
    assert quante == 2


# --- monotonia della fotografia vigente ---

def test_fotografia_piu_alta_vince(client, db_path):
    _posta_foto(client, "bassa", domini={"instagram.com": 5})
    _posta_foto(client, "alta", domini={"instagram.com": 9, "youtube.com": 2})
    vigente = _vigente(db_path)["2026-07-14"]
    assert vigente["evento_id"] == "alta"
    assert vigente["totale_domini"] == 2
    assert vigente["totale_visite"] == 11


def test_fotografia_in_ritardo_non_regredisce(client, db_path):
    _posta_foto(client, "alta", domini={"instagram.com": 9, "youtube.com": 2})
    _posta_foto(client, "in-ritardo", domini={"instagram.com": 4})  # consegna fuori ordine
    vigente = _vigente(db_path)["2026-07-14"]
    assert vigente["evento_id"] == "alta"  # la vigente non torna indietro
    assert vigente["totale_visite"] == 11


def test_meno_domini_ma_piu_visite_non_sovrascrive(client, db_path):
    """Monotonia sulla COPPIA: se anche solo un valore regredisce, non passa."""
    _posta_foto(client, "due-domini", domini={"instagram.com": 5, "youtube.com": 5})
    _posta_foto(client, "un-dominio", domini={"instagram.com": 50})
    assert _vigente(db_path)["2026-07-14"]["evento_id"] == "due-domini"


def test_a_parita_vince_la_piu_recente(client, db_path):
    _posta_foto(client, "prima", domini={"instagram.com": 7})
    _posta_foto(client, "seconda", domini={"youtube.com": 7})
    assert _vigente(db_path)["2026-07-14"]["evento_id"] == "seconda"


def test_giorni_diversi_convivono(client, db_path):
    _posta_foto(client, "s-13", giorno="2026-07-13", domini={"instagram.com": 3})
    _posta_foto(client, "s-14", giorno="2026-07-14", domini={"youtube.com": 4})
    assert set(_vigente(db_path).keys()) == {"2026-07-13", "2026-07-14"}


def test_totale_domini_mancante_vale_le_chiavi_valide(client, db_path):
    _posta_foto(client, "senza-totale", domini={"instagram.com": 3, "youtube.com": 1})
    assert _vigente(db_path)["2026-07-14"]["totale_domini"] == 2


def test_totale_domini_non_valido_vale_le_chiavi_valide(client, db_path):
    _posta_foto(
        client, "totale-sporco", domini={"instagram.com": 3}, totale_domini="tanti"
    )
    assert _vigente(db_path)["2026-07-14"]["totale_domini"] == 1


def test_voci_sporche_scartate_ma_la_giornata_resta(client, db_path):
    """Una fotografia sporca non deve far crollare la finestra: si scartano le
    voci rotte (valore non intero o negativo), non l'intero giorno."""
    _posta_foto(
        client,
        "foto-sporca",
        domini={
            "instagram.com": 10,
            "rotto.com": "molte",
            "negativo.com": -5,
            "booleano.com": True,
            "float.com": 3.5,
        },
    )
    assert _vigente(db_path)["2026-07-14"]["totale_domini"] == 1
    domini = _voce(_siti_finestra(client), "2026-07-14")["domini"]
    assert domini == [{"dominio": "instagram.com", "visite": 10}]


# --- forma di siti_recenti nella finestra ---

def test_otto_voci_dal_piu_vecchio_a_oggi(client):
    crea_regola(client)
    voci = _siti_finestra(client)
    assert len(voci) == 8
    assert voci[0]["giorno"] == "2026-07-07"
    assert voci[-1]["giorno"] == "2026-07-14"  # oggi in coda
    assert [v["giorno"] for v in voci] == sorted(v["giorno"] for v in voci)


def test_stessa_finestra_di_uso_recente(client):
    """siti_recenti e uso_recente coprono gli stessi 8 giorni del semaforo."""
    crea_regola(client)
    finestra = client.get("/api/finestra", headers=GENITORE).json()
    giorni_siti = [v["giorno"] for v in finestra["siti_recenti"]]
    assert giorni_siti == [v["giorno"] for v in finestra["uso_recente"]]
    assert giorni_siti == [v["giorno"] for v in finestra["bonus_giornalieri"]]
    assert giorni_siti == [s["data"] for s in finestra["regole"][0]["semaforo"]]


def test_voce_completa(client):
    _posta_foto(
        client,
        "foto-oggi",
        domini={"instagram.com": 128, "youtube.com": 54},
        totale_domini=37,
    )
    oggi = _voce(_siti_finestra(client), "2026-07-14")
    assert oggi == {
        "giorno": "2026-07-14",
        "totale_domini": 37,  # il conteggio VERO: la lista era tagliata
        "dns_cifrato": False,
        "aggiornato_ts": "2026-07-14T10:00:00+00:00",
        "domini": [
            {"dominio": "instagram.com", "visite": 128},
            {"dominio": "youtube.com", "visite": 54},
        ],
    }


def test_domini_ordinati_per_visite_decrescenti(client):
    _posta_foto(
        client,
        "foto-ordine",
        domini={"a.com": 1, "youtube.com": 54, "instagram.com": 128},
    )
    domini = _voce(_siti_finestra(client), "2026-07-14")["domini"]
    assert [d["dominio"] for d in domini] == ["instagram.com", "youtube.com", "a.com"]


def test_a_parita_di_visite_ordine_alfabetico(client):
    """L'ordine e' deterministico: le due app mostrano la stessa lista."""
    _posta_foto(client, "foto-pari", domini={"zeta.com": 7, "alfa.com": 7, "mid.com": 7})
    domini = _voce(_siti_finestra(client), "2026-07-14")["domini"]
    assert [d["dominio"] for d in domini] == ["alfa.com", "mid.com", "zeta.com"]


def test_giorno_senza_fotografia_e_null_non_zero(client):
    """MAI uno zero finto: null dice "nessuna fotografia arrivata", non "zero siti"."""
    _posta_foto(client, "solo-oggi", domini={"instagram.com": 3})
    voci = _siti_finestra(client)
    vuoto = _voce(voci, "2026-07-10")
    assert vuoto == {
        "giorno": "2026-07-10",
        "totale_domini": None,
        "dns_cifrato": False,
        "aggiornato_ts": None,
        "domini": [],
    }
    assert vuoto["totale_domini"] is None  # non 0: l'assenza non e' una giornata pulita
    assert _voce(voci, "2026-07-14")["totale_domini"] == 1


def test_giorno_con_zero_domini_non_e_un_giorno_vuoto(client):
    """Una fotografia con zero domini e' un DATO (0), l'assenza e' null: due
    informazioni diverse e restano distinte."""
    _posta_foto(client, "foto-vuota", domini={})
    oggi = _voce(_siti_finestra(client), "2026-07-14")
    assert oggi["totale_domini"] == 0
    assert oggi["aggiornato_ts"] == "2026-07-14T10:00:00+00:00"


def test_fuori_finestra_non_compare(client):
    _posta_foto(client, "vecchia", giorno="2026-07-01", domini={"instagram.com": 3})
    assert [v["giorno"] for v in _siti_finestra(client)][0] == "2026-07-07"
    assert all(v["totale_domini"] is None for v in _siti_finestra(client))


# --- dns_cifrato: la cecita' dichiarata ---

def test_dns_cifrato_passa_fino_alla_finestra(client):
    _posta_foto(client, "foto-cieca", domini={"instagram.com": 2}, dns_cifrato=True)
    oggi = _voce(_siti_finestra(client), "2026-07-14")
    assert oggi["dns_cifrato"] is True
    # "questi li ho visti, ma per un pezzo di giornata ero cieco"
    assert oggi["domini"] == [{"dominio": "instagram.com", "visite": 2}]


def test_dns_cifrato_e_appiccicoso_sul_giorno(client):
    """Una confessione di cecita' non sparisce quando la vigente diventa un'altra."""
    _posta_foto(client, "cieca", domini={"instagram.com": 2}, dns_cifrato=True)
    _posta_foto(client, "vedente", domini={"instagram.com": 9, "youtube.com": 4})
    oggi = _voce(_siti_finestra(client), "2026-07-14")
    assert oggi["aggiornato_ts"] is not None
    assert oggi["dns_cifrato"] is True  # la vigente e' l'altra, la cecita' resta
    assert [d["dominio"] for d in oggi["domini"]] == ["instagram.com", "youtube.com"]


def test_dns_cifrato_appiccicoso_anche_da_una_fotografia_perdente(client):
    """La cecita' arriva con una fotografia che NON vince la monotonia: resta lo stesso."""
    _posta_foto(client, "alta", domini={"instagram.com": 9, "youtube.com": 4})
    _posta_foto(client, "cieca-bassa", domini={"instagram.com": 1}, dns_cifrato=True)
    oggi = _voce(_siti_finestra(client), "2026-07-14")
    assert oggi["dns_cifrato"] is True
    assert oggi["totale_domini"] == 2  # i numeri non sono regrediti


def test_dns_cifrato_solo_il_booleano_vero(client):
    _posta_foto(client, "falsa-cecita", domini={"instagram.com": 1}, dns_cifrato="false")
    assert _voce(_siti_finestra(client), "2026-07-14")["dns_cifrato"] is False


def test_dns_cifrato_senza_domini(client):
    """Cieco tutto il giorno: dichiara di non aver visto invece di fingere zero."""
    _posta_foto(client, "cieca-totale", domini={}, dns_cifrato=True)
    oggi = _voce(_siti_finestra(client), "2026-07-14")
    assert oggi["dns_cifrato"] is True
    assert oggi["domini"] == []
    assert oggi["totale_domini"] == 0  # fotografia arrivata: 0 e' un dato, non un null


# --- tavola rotonda: il figlio vede IDENTICO ---

def test_patto_e_finestra_identici(client):
    _posta_foto(
        client,
        "foto-tavola",
        domini={"instagram.com": 128, "youtube.com": 54},
        totale_domini=40,
        dns_cifrato=True,
    )
    _posta_foto(client, "foto-ieri", giorno="2026-07-13", domini={"wikipedia.org": 3})
    assert _siti_patto(client) == _siti_finestra(client)


def test_patto_e_finestra_identici_anche_senza_dati(client):
    crea_regola(client)
    voci = _siti_patto(client)
    assert voci == _siti_finestra(client)
    assert len(voci) == 8
    assert all(v["totale_domini"] is None and v["domini"] == [] for v in voci)


def test_il_figlio_vede_i_siti_senza_token_del_genitore(client):
    """La lista vive nell'app del figlio: e' il SUO registro, che lui condivide."""
    _posta_foto(client, "foto-figlio", domini={"instagram.com": 4})
    assert _voce(_siti_patto(client), "2026-07-14")["domini"] == [
        {"dominio": "instagram.com", "visite": 4}
    ]
