import os
from dataclasses import dataclass

VERSIONE = "0.1.0"

LOCK_GIORNI = 4
SOGLIA_SILENZIO_MINUTI = 45
TETTO_BONUS_GIORNO_DEFAULT = 30
TETTO_BONUS_SETTIMANA_DEFAULT = 90


@dataclass(frozen=True)
class Settings:
    token_figlio: str
    token_genitore: str
    db_path: str


def carica_settings() -> Settings:
    return Settings(
        token_figlio=os.environ.get("PACTUM_TOKEN_FIGLIO", "dev-token-figlio"),
        token_genitore=os.environ.get("PACTUM_TOKEN_GENITORE", "dev-token-genitore"),
        db_path=os.environ.get("PACTUM_DB", "pactum.db"),
    )
