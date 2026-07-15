from fastapi import FastAPI

from . import db
from .config import (
    TETTO_BONUS_GIORNO_DEFAULT,
    TETTO_BONUS_SETTIMANA_DEFAULT,
    VERSIONE,
    carica_settings,
)
from .routes import (
    dichiarazioni,
    distribuzione,
    figlio,
    genitore,
    notifiche,
    proposte,
    regole,
)


def create_app() -> FastAPI:
    settings = carica_settings()
    db.init_db(settings.db_path, TETTO_BONUS_GIORNO_DEFAULT, TETTO_BONUS_SETTIMANA_DEFAULT)

    app = FastAPI(title="Pactum — postino", version=VERSIONE)
    app.state.settings = settings

    @app.get("/api/salute")
    def salute():
        return {"stato": "ok", "versione": VERSIONE}

    app.include_router(regole.router, prefix="/api")
    app.include_router(proposte.router, prefix="/api")
    app.include_router(dichiarazioni.router, prefix="/api")
    app.include_router(notifiche.router, prefix="/api")
    app.include_router(figlio.router, prefix="/api")
    app.include_router(genitore.router, prefix="/api")
    # Distribuzione (tappa 6): /api/versione sotto /api; /scarica alla radice.
    app.include_router(distribuzione.versione_router, prefix="/api")
    app.include_router(distribuzione.scarica_router)
    return app
