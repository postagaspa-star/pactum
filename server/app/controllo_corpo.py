"""(v3.5) Il controllo dei corpi JSON di tutte le richieste sotto /api/, prima di
qualsiasi route (contratto-api.md, "Regole generali").

Python legge come JSON anche valori che il JSON vero non ha e che poi non si riescono
piu' a scrivere in una risposta:
- NaN, Infinity, -Infinity, e un numero come 1e400 che diventa infinito;
- un pezzo di carattere da solo (un "surrogato" come \\ud800, scritto con l'escape o
  coi byte grezzi): non si scrive in UTF-8.
Se finissero nel registro (i dettagli di uno sforamento, le etichette di una
fotografia, il nome di una sessione), ogni lettura che li ripresenta (finestra, patto,
sessioni, le notifiche del genitore) cadrebbe con un 500: con un solo pacco sporco il
figlio farebbe tacere gli avvisi del genitore. Si fermano qui, con un 422, prima che
una route li tocchi. Lo stesso per gli interi oltre i 64 bit, che SQLite non sa tenere
(un 500 a meta' scrittura).

Un corpo vuoto, o che non e' JSON, passa com'e': ci pensa FastAPI, come prima."""

import json
import math

from starlette.responses import JSONResponse

# Gli interi che SQLite sa tenere (64 bit con segno).
INTERO_MINIMO = -(2**63)
INTERO_MASSIMO = 2**63 - 1


class _Rifiutato(Exception):
    """Un valore che il JSON vero non ha."""


def _costante(nome: str):
    raise _Rifiutato(f"{nome} non e' un numero JSON")


def _decimale(testo: str) -> float:
    valore = float(testo)
    if not math.isfinite(valore):
        raise _Rifiutato(f"{testo} e' un numero fuori misura")
    return valore


def _si_scrive_in_utf8(testo: str) -> bool:
    try:
        testo.encode("utf-8")
    except UnicodeEncodeError:
        return False
    return True


def problema_nel_corpo(corpo: bytes) -> str | None:
    """Perche' il corpo va rifiutato, oppure None se va bene. Anche un corpo vuoto o
    che non e' JSON da' None: lo giudica FastAPI, come ha sempre fatto. La visita e'
    senza ricorsione: un JSON molto annidato non la fa cadere."""
    if not corpo.strip():
        return None
    try:
        dati = json.loads(corpo, parse_constant=_costante, parse_float=_decimale)
    except _Rifiutato as errore:
        return str(errore)
    except (ValueError, RecursionError):
        return None
    da_guardare = [dati]
    while da_guardare:
        valore = da_guardare.pop()
        if isinstance(valore, str):
            if not _si_scrive_in_utf8(valore):
                return "testo con un carattere non valido (un surrogato da solo)"
        elif isinstance(valore, bool):  # prima di int: True e False sono int
            continue
        elif isinstance(valore, int):
            if not INTERO_MINIMO <= valore <= INTERO_MASSIMO:
                return "numero intero oltre i 64 bit"
        elif isinstance(valore, dict):
            for chiave, dentro in valore.items():
                da_guardare.append(chiave)
                da_guardare.append(dentro)
        elif isinstance(valore, list):
            da_guardare.extend(valore)
    return None


class CorpoJsonSano:
    """Il middleware ASGI: legge tutto il corpo di una richiesta sotto /api/, lo
    controlla e, se va bene, lo ripassa uguale alla route; se no risponde 422 subito,
    nella forma degli altri 422 del server ({"detail": [{"loc", "msg"}]})."""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or not scope["path"].startswith("/api/"):
            await self.app(scope, receive, send)
            return
        pezzi = []
        while True:
            messaggio = await receive()
            if messaggio["type"] != "http.request":
                return  # il client se n'e' andato prima di finire di mandare: niente da dire
            pezzi.append(messaggio.get("body", b""))
            if not messaggio.get("more_body", False):
                break
        corpo = b"".join(pezzi)
        motivo = problema_nel_corpo(corpo)
        if motivo is not None:
            risposta = JSONResponse({"detail": [{"loc": ["body"], "msg": motivo}]}, status_code=422)
            await risposta(scope, receive, send)
            return

        consegnato = False

        async def rileggi():
            nonlocal consegnato
            if not consegnato:
                consegnato = True
                return {"type": "http.request", "body": corpo, "more_body": False}
            return await receive()

        await self.app(scope, rileggi, send)
