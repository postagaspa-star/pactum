import asyncio
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

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
from .routes import (
    dichiarazioni,
    distribuzione,
    famiglia,
    figlio,
    genitore,
    notifiche,
    proposte,
    regole,
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


@asynccontextmanager
async def _ciclo_di_vita(app: FastAPI):
    """(v3.2) La copia notturna gira finche' gira il server. Allo spegnimento il
    compito si ferma subito; una copia a meta' la si lascia finire (dura poco)."""
    copia = app.state.copia_notturna
    if copia is None:
        yield
        return
    ferma = asyncio.Event()
    compito = asyncio.create_task(copia.gira(ferma))
    try:
        yield
    finally:
        ferma.set()
        await compito


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
    app.state.settings = settings
    app.state.copia_notturna = copie.prepara_copia_notturna(settings)

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
    # Distribuzione (tappa 6): /api/versione sotto /api; /scarica alla radice.
    app.include_router(distribuzione.versione_router, prefix="/api")
    app.include_router(distribuzione.scarica_router)
    return app
