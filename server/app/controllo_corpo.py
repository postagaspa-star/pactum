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

Un corpo vuoto, o che non e' JSON, passa com'e': ci pensa FastAPI, come prima.

(v3.6) Un tetto alla misura: un corpo oltre CORPO_MASSIMO_BYTE (8 MB) -> 413
{"detail": {"errore": "corpo_troppo_grande"}}, contando i byte mentre arrivano e senza
leggere il resto (un Content-Length piu' grande si ferma subito). Senza, un POST anonimo
da un giga (su /api/abbina, che non vuole token) finirebbe tutto in memoria. Il corpo
legittimo piu' grande e' il pacco di eventi del telefono, che manda tutta la sua coda
in una volta: una fotografia d'uso e una dei siti per giorno, qualche KB ciascuna, cioe'
meno di 1 MB anche dopo tre mesi senza rete. 8 MB lasciano un margine ampio.

Le foto delle faccende (le consegne a /api/faccende/<id>/foto) non passano di qui: la
route le legge a pezzi col suo tetto di 4 MB. Le altre route le controlla tutte, anche
quando il corpo si dichiara image/jpeg."""

import json
import math
import re

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


# (v3.6) Le foto delle faccende: /api/faccende/<id>/foto. Anche la compressione gzip
# (main.py) le lascia stare.
PERCORSO_FOTO = re.compile(r"/api/faccende/[^/]+/foto")

# (v3.6) Il tetto ai corpi delle richieste sotto /api/ (tranne le foto: 4 MB loro).
CORPO_MASSIMO_BYTE = 8 * 1024 * 1024


def _e_una_foto(scope) -> bool:
    """(v3.6) La consegna (o la lettura) di una foto: la route la legge a pezzi col suo
    tetto, qui non deve finire in memoria. Conta solo il percorso, mai il Content-Type
    che il client dichiara: su un'altra route un corpo "image/jpeg" e' un corpo come gli
    altri, e il tetto vale anche per lui."""
    return PERCORSO_FOTO.fullmatch(scope["path"]) is not None


def _dichiarata(scope) -> int | None:
    for nome, valore in scope.get("headers", []):
        if nome.lower() == b"content-length":
            testo = valore.strip()
            return int(testo) if testo.isdigit() else None
    return None


class CorpoJsonSano:
    """Il middleware ASGI: legge tutto il corpo di una richiesta sotto /api/, lo
    controlla e, se va bene, lo ripassa uguale alla route; se no risponde 422 subito,
    nella forma degli altri 422 del server ({"detail": [{"loc", "msg"}]})."""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or not scope["path"].startswith("/api/") or _e_una_foto(scope):
            await self.app(scope, receive, send)
            return
        troppo = JSONResponse({"detail": {"errore": "corpo_troppo_grande"}}, status_code=413)
        dichiarata = _dichiarata(scope)
        if dichiarata is not None and dichiarata > CORPO_MASSIMO_BYTE:
            await troppo(scope, receive, send)
            return
        pezzi, letti = [], 0
        while True:
            messaggio = await receive()
            if messaggio["type"] != "http.request":
                return  # il client se n'e' andato prima di finire di mandare: niente da dire
            pezzo = messaggio.get("body", b"")
            letti += len(pezzo)
            if letti > CORPO_MASSIMO_BYTE:  # (v3.6) il resto non si legge
                await troppo(scope, receive, send)
                return
            pezzi.append(pezzo)
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
