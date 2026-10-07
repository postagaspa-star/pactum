"""Aiuti per i test della v3 (famiglia, figli, dispositivi): creare un figlio,
aggiungergli un dispositivo e abbinarlo col codice come farebbe l'app, e
confrontare una risposta v2.4 con una v3 ignorando solo i campi nuovi."""

from conftest import GENITORE


def auth(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


def nuovo_figlio(client, nome="Luca") -> dict:
    risposta = client.post("/api/figli", json={"nome": nome}, headers=GENITORE)
    assert risposta.status_code == 201, risposta.text
    return risposta.json()


def nuovo_dispositivo(client, figlio_id: int, nome="Computer", tipo="computer") -> dict:
    """Il dispositivo appena creato dal genitore: {dispositivo, codice, scade_ts}."""
    risposta = client.post(
        f"/api/figli/{figlio_id}/dispositivi", json={"nome": nome, "tipo": tipo}, headers=GENITORE
    )
    assert risposta.status_code == 201, risposta.text
    return risposta.json()


def abbina(client, codice: str, versione_app="0.8.0"):
    return client.post("/api/abbina", json={"codice": codice, "versione_app": versione_app})


def dispositivo_abbinato(client, figlio_id: int, nome="Computer", tipo="computer") -> tuple[dict, int]:
    """Crea e abbina un dispositivo: (header col suo token, id del dispositivo)."""
    creato = nuovo_dispositivo(client, figlio_id, nome, tipo)
    risposta = abbina(client, creato["codice"])
    assert risposta.status_code == 200, risposta.text
    return auth(risposta.json()["token"]), creato["dispositivo"]["id"]


def regola(client, headers, tipo="limite_tempo", parametri=None, **extra) -> dict:
    if parametri is None:
        parametri = {"app_o_categoria": "TikTok", "minuti_al_giorno": 60}
    risposta = client.post(
        "/api/regole", json={"tipo": tipo, "parametri": parametri, **extra}, headers=headers
    )
    assert risposta.status_code == 201, risposta.text
    return risposta.json()


def eventi(client, headers, *lista):
    risposta = client.post("/api/eventi", json={"eventi": list(lista)}, headers=headers)
    assert risposta.status_code == 200, risposta.text
    return risposta.json()


def contenuto_in(prima, dopo, percorso="$") -> None:
    """Ogni chiave e ogni valore di `prima` (la risposta della v2.4) ci sono uguali
    in `dopo`: i campi che la v3 aggiunge si ignorano, tutto il resto no. Le liste
    devono avere la stessa lunghezza e lo stesso ordine."""
    if isinstance(prima, dict):
        assert isinstance(dopo, dict), f"{percorso}: non e' piu' un oggetto"
        for chiave, valore in prima.items():
            assert chiave in dopo, f"{percorso}.{chiave}: manca"
            contenuto_in(valore, dopo[chiave], f"{percorso}.{chiave}")
    elif isinstance(prima, list):
        assert isinstance(dopo, list), f"{percorso}: non e' piu' una lista"
        assert len(prima) == len(dopo), f"{percorso}: {len(prima)} voci prima, {len(dopo)} dopo"
        for i, (a, b) in enumerate(zip(prima, dopo)):
            contenuto_in(a, b, f"{percorso}[{i}]")
    else:
        assert prima == dopo, f"{percorso}: {prima!r} prima, {dopo!r} dopo"


# (v4.0) Le due righe che la v4.0 scrive in `patto` al primo avvio (i lavori approvati e
# l'ultimo giro dello Studio): i test delle migrazioni di prima confrontano il resto.
RIGHE_PATTO_V40 = ("faccende_approvazione_dal", "studio_ultimo_giro")


def senza_righe_v40(righe: dict) -> dict:
    """Le righe del database (dati_v24.righe) senza le due righe della v4.0 in `patto`,
    dopo aver controllato che ci siano tutte e due."""
    patto = righe.get("patto", [])
    assert {r["chiave"] for r in patto} >= set(RIGHE_PATTO_V40), patto
    return {**righe, "patto": [r for r in patto if r["chiave"] not in RIGHE_PATTO_V40]}
