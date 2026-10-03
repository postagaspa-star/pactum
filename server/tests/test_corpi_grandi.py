"""(v3.6) Il tetto ai corpi delle richieste sotto /api/ (contratto-api.md, v3.6,
"Precisazioni"): oltre 8 MB -> 413 {"detail": {"errore": "corpo_troppo_grande"}},
contando i byte mentre arrivano e senza leggere il resto. Anche senza token (POST
/api/abbina), anche con un Content-Length falso o assente, anche con un corpo che si
dichiara image/jpeg. Le foto delle faccende hanno il loro tetto di 4 MB
(test_faccende.py); un pacco di eventi grande ma vero passa."""

import asyncio
import json
import uuid

from conftest import FIGLIO

MEGA = 1024 * 1024
TETTO = 8 * MEGA
PEZZO = 256 * 1024


def _manda(app, percorso, pezzi, headers=None) -> tuple[int, dict | None, int]:
    """Una POST mandata direttamente all'app ASGI, a pezzi, come da una rete vera:
    (stato, corpo della risposta, pezzi letti dal server)."""
    scope = {
        "type": "http", "asgi": {"version": "3.0"}, "http_version": "1.1", "method": "POST",
        "scheme": "http", "path": percorso, "raw_path": percorso.encode(), "query_string": b"",
        "root_path": "", "client": ("prova", 1), "server": ("prova", 80),
        "headers": [(k.lower().encode(), v.encode()) for k, v in (headers or {}).items()],
    }
    letti, risposta, corpo = 0, {}, bytearray()

    async def receive():
        nonlocal letti
        if letti < len(pezzi):
            letti += 1
            return {"type": "http.request", "body": pezzi[letti - 1], "more_body": letti < len(pezzi)}
        await asyncio.sleep(3600)  # il client non manda altro

    async def send(messaggio):
        if messaggio["type"] == "http.response.start":
            risposta["stato"] = messaggio["status"]
        elif messaggio["type"] == "http.response.body":
            corpo.extend(messaggio.get("body", b""))

    asyncio.run(app(scope, receive, send))
    try:
        dati = json.loads(corpo) if corpo else None
    except ValueError:
        dati = None
    return risposta["stato"], dati, letti


def test_un_corpo_enorme_si_ferma_al_primo_pezzo_di_troppo(client):
    """Un POST anonimo da 9 MB su /api/abbina, a pezzi e senza Content-Length: il server
    ne legge 8 MB e un pezzo, poi risponde 413 senza leggere il resto."""
    pezzi = [b" " * PEZZO] * 36
    for intestazioni in ({}, {"Content-Type": "application/json"}, {"Content-Type": "image/jpeg"},
                         {"Content-Length": "100"}):
        stato, dati, letti = _manda(client.app, "/api/abbina", pezzi, intestazioni)
        assert (stato, dati) == (413, {"detail": {"errore": "corpo_troppo_grande"}}), intestazioni
        assert letti == TETTO // PEZZO + 1


def test_un_content_length_troppo_grande_si_ferma_subito(client):
    stato, dati, letti = _manda(client.app, "/api/eventi", [b"{}"],
                                {**FIGLIO, "Content-Length": str(TETTO + 1)})
    assert (stato, dati, letti) == (413, {"detail": {"errore": "corpo_troppo_grande"}}, 0)


def test_fino_al_tetto_si_legge_tutto(client):
    """8 MB giusti passano il tetto e arrivano alla route, che li giudica come sempre
    (qui un abbinamento senza codice: 422, non 413)."""
    corpo = b" " * (TETTO - 2) + b"{}"
    stato, dati, letti = _manda(client.app, "/api/abbina", [corpo[i:i + PEZZO] for i in range(0, TETTO, PEZZO)])
    assert letti == TETTO // PEZZO
    assert stato == 422 and dati["detail"] != {"errore": "corpo_troppo_grande"}, dati


def test_un_pacco_di_eventi_grande_ma_vero_passa(client):
    """Il pacco legittimo piu' grande: il telefono rimasto mesi senza rete manda tutta la
    coda in una volta (una fotografia d'uso e una dei siti per giorno). Qui 200 giorni
    di fotografie, piu' di 2 MB: passa."""
    eventi = []
    for giorno in range(200):
        data = f"2026-{1 + giorno // 28:02d}-{1 + giorno % 28:02d}"
        app = {f"com.esempio.app{i:03d}": i % 90 for i in range(80)}
        eventi.append({"id": str(uuid.uuid4()), "tipo": "uso_giornaliero", "dettagli": {
            "giorno": data, "uso_minuti": app, "nomi": {k: f"App di prova {k[-3:]}" for k in app},
            "totale_minuti": 300}})
        eventi.append({"id": str(uuid.uuid4()), "tipo": "siti_giornalieri", "dettagli": {
            "giorno": data, "domini": {f"sito-di-prova-{i}.it": i for i in range(1, 200)}, "totale_domini": 199}})
    corpo = json.dumps({"eventi": eventi}).encode()
    assert 2 * MEGA < len(corpo) < TETTO
    r = client.post("/api/eventi", content=corpo, headers={**FIGLIO, "Content-Type": "application/json"})
    assert r.status_code == 200, r.text[:200]
    assert r.json()["nuovi"] == 400
