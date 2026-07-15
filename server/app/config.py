import os
from dataclasses import dataclass
from zoneinfo import ZoneInfo

VERSIONE = "0.1.0"

LOCK_GIORNI = 4
SOGLIA_SILENZIO_MINUTI = 45
TETTO_BONUS_GIORNO_DEFAULT = 30
TETTO_BONUS_SETTIMANA_DEFAULT = 90
TIMEZONE_DEFAULT = "Europe/Rome"


def nome_fuso() -> str:
    """Il nome del fuso del patto (es. 'Europe/Rome'), esposto al figlio in /api/patto."""
    return os.environ.get("PACTUM_TIMEZONE", TIMEZONE_DEFAULT)


def fuso_patto() -> ZoneInfo:
    """Il fuso del patto: i bucket giorno/settimana (tetti bonus, semaforo della
    finestra) si contano nel giorno LOCALE della famiglia, non in UTC — altrimenti
    il tetto giornaliero di un ragazzo italiano si azzererebbe alle 02:00 locali.
    I timestamp restano UTC ISO: il fuso serve solo a decidere i confini dei giorni."""
    return ZoneInfo(nome_fuso())


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
