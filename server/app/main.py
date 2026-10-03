import asyncio
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from starlette.middleware.gzip import GZipMiddleware

from . import copie, db
from .config import (
    TETTO_BONUS_GIORNO_DEFAULT,
    TETTO_BONUS_SETTIMANA_DEFAULT,
    VERSIONE,
    assicura_cartella_db,
    carica_settings,
    riassunto_config,
    valida_produzione,
)
from .controllo_corpo import PERCORSO_FOTO, CorpoJsonSano
from .routes import (
    dichiarazioni,
    distribuzione,
    faccende,
    famiglia,
    figlio,
    genitore,
    notifiche,
    proposte,
    regole,
    sessioni,
)

# uvicorn.error e' il logger che uvicorn configura con un handler a livello INFO:
# scriverci sopra fa comparire la riga d'avvio nel log del container. Sotto pytest
# (niente uvicorn) resta silenziosa, come deve.
log = logging.getLogger("uvicorn.error")


def _db_ok(db_path: str) -> bool:
    """Controllo veloce che il database sia raggiungibile e interrogabile
    (SELECT 1). Non solleva mai: qualsiasi problema diventa db_ok=false."""
    try:
        conn = db.connetti(db_path)
        try:
            conn.execute("SELECT 1")
            return True
        finally:
            conn.close()
    except Exception:
        return False


class GzipSoloApi:
    """(v3.3) Risposte JSON compresse per chi le chiede (Accept-Encoding: gzip):
    l'app del genitore 0.9 chiede le notifiche ogni minuto e il server le rimanda
    tutte finche' non sono lette. Solo sotto /api/: gli APK e lo zip di /scarica
    sono gia' compressi e devono conservare la loro lunghezza. (v3.6) Neanche le foto
    delle faccende: un JPEG e' gia' compresso."""

    def __init__(self, app):
        self.app = app
        self.gzip = GZipMiddleware(app, minimum_size=500)

    async def __call__(self, scope, receive, send):
        if (
            scope["type"] == "http"
            and scope["path"].startswith("/api/")
            and not PERCORSO_FOTO.fullmatch(scope["path"])
        ):
            await self.gzip(scope, receive, send)
        else:
            await self.app(scope, receive, send)


@asynccontextmanager
async def _ciclo_di_vita(app: FastAPI):
    """(v3.2) La copia notturna gira finche' gira il server. Allo spegnimento il
    compito si ferma subito; una copia a meta' la si lascia finire (dura poco).
    (v3.6) Accanto, la pulizia giornaliera delle foto delle faccende, che gira anche
    quando la copia e' spenta."""
    lavori = [app.state.pulizia_foto]
    if app.state.copia_notturna is not None:
        lavori.insert(0, app.state.copia_notturna)
    ferma = asyncio.Event()
    compiti = [asyncio.create_task(lavoro.gira(ferma)) for lavoro in lavori]
    try:
        yield
    finally:
        ferma.set()
        await asyncio.gather(*compiti)


def create_app() -> FastAPI:
    settings = carica_settings()
    # Log della config effettiva (senza segreti) PRIMA della validazione: se prod
    # ha token deboli, l'operatore vede lo stato ('dev-default') e poi l'errore.
    log.info("Pactum avvio: %s", riassunto_config(settings))
    valida_produzione(settings)
    assicura_cartella_db(settings.db_path)
    # (v3.2) Il ripristino chiesto con PACTUM_RIPRISTINA avviene PRIMA di aprire il
    # database: una copia vecchia (anche v2) viene poi migrata come le altre.
    copie.ripristina_se_chiesto(settings)
    # (v3) I due token d'ambiente restano quelli del genitore 1 e del dispositivo 1
    # (le app 0.7 installate): init_db li registra come hash, e al primo avvio
    # migra il database a figli e dispositivi.
    db.init_db(
        settings.db_path,
        TETTO_BONUS_GIORNO_DEFAULT,
        TETTO_BONUS_SETTIMANA_DEFAULT,
        token_figlio=settings.token_figlio,
        token_genitore=settings.token_genitore,
    )

    app = FastAPI(title="Pactum — postino", version=VERSIONE, lifespan=_ciclo_di_vita)
    app.add_middleware(GzipSoloApi)
    # (v3.5) Ogni corpo JSON sotto /api/ passa da qui prima di qualsiasi route: NaN,
    # Infinity, surrogati da soli e interi oltre i 64 bit -> 422 (controllo_corpo.py).
    app.add_middleware(CorpoJsonSano)
    app.state.settings = settings
    app.state.copia_notturna = copie.prepara_copia_notturna(settings)
    # (v3.6) Le foto delle faccende arrivate da piu' di 30 giorni, e i file senza
    # faccenda, si tolgono gia' all'avvio; poi una volta al giorno.
    app.state.pulizia_foto = copie.PuliziaFoto(settings.db_path)
    app.state.pulizia_foto.subito()

    @app.exception_handler(OverflowError)
    async def numero_fuori_misura(request: Request, errore: OverflowError):
        # (v3.5) Un intero che SQLite non sa tenere (oltre i 64 bit) in un percorso o in
        # una query (/api/regole/99999999999999999999): non e' mai un id che esiste.
        # 422, mai un 500. Nei corpi lo ferma gia' CorpoJsonSano.
        return JSONResponse(status_code=422, content={"detail": "numero fuori misura"})

    @app.get("/api/salute")
    def salute():
        return {
            "stato": "ok",
            "versione": VERSIONE,
            "env": settings.env,
            "db_ok": _db_ok(settings.db_path),
            "backup": copie.stato_copie(settings.backup_dir),
        }

    app.include_router(regole.router, prefix="/api")
    app.include_router(proposte.router, prefix="/api")
    app.include_router(dichiarazioni.router, prefix="/api")
    app.include_router(notifiche.router, prefix="/api")
    app.include_router(figlio.router, prefix="/api")
    app.include_router(genitore.router, prefix="/api")
    app.include_router(famiglia.router, prefix="/api")
    app.include_router(famiglia.abbina_router, prefix="/api")
    app.include_router(sessioni.router, prefix="/api")  # (v3.5)
    app.include_router(faccende.router, prefix="/api")  # (v3.6)
    # Distribuzione (tappa 6): /api/versione sotto /api; /scarica alla radice.
    app.include_router(distribuzione.versione_router, prefix="/api")
    app.include_router(distribuzione.scarica_router)
    return app
