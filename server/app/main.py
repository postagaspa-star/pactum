import logging

from fastapi import FastAPI

from . import db
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


def create_app() -> FastAPI:
    settings = carica_settings()
    # Log della config effettiva (senza segreti) PRIMA della validazione: se prod
    # ha token deboli, l'operatore vede lo stato ('dev-default') e poi l'errore.
    log.info("Pactum avvio: %s", riassunto_config(settings))
    valida_produzione(settings)
    assicura_cartella_db(settings.db_path)
    db.init_db(settings.db_path, TETTO_BONUS_GIORNO_DEFAULT, TETTO_BONUS_SETTIMANA_DEFAULT)

    app = FastAPI(title="Pactum — postino", version=VERSIONE)
    app.state.settings = settings

    @app.get("/api/salute")
    def salute():
        return {
            "stato": "ok",
            "versione": VERSIONE,
            "env": settings.env,
            "db_ok": _db_ok(settings.db_path),
        }

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
