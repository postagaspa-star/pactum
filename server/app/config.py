import os
from dataclasses import dataclass
from pathlib import Path
from zoneinfo import ZoneInfo

VERSIONE = "0.1.0"

# La distribuzione (tappa 6) vive fuori dal database: le versioni pubblicizzate
# stanno in un JSON aggiornabile senza toccare il codice, gli APK firmati in una
# cartella servita dal postino. I default puntano al pacchetto e alla radice del
# server; in produzione si possono spostare con PACTUM_VERSIONI / PACTUM_APK_DIR.
VERSIONI_PATH_DEFAULT = str(Path(__file__).resolve().parent / "versioni.json")
APK_DIR_DEFAULT = str(Path(__file__).resolve().parents[1] / "apk")

LOCK_GIORNI = 4
SOGLIA_SILENZIO_MINUTI = 45
TETTO_BONUS_GIORNO_DEFAULT = 30
TETTO_BONUS_SETTIMANA_DEFAULT = 90
TIMEZONE_DEFAULT = "Europe/Rome"

# (v3.2) La cartella della copia notturna del registro: in Docker /backup, montata
# su server/backup del NAS (quella che si vede dal gestore file del NAS).
BACKUP_DIR_DEFAULT = "/backup"

# (v3) Abbinamento con codice: 6 cifre, valido 15 minuti, una volta sola. Contro
# chi prova i codici a caso: 10 tentativi falliti in 10 minuti (su tutto il
# server) bloccano ogni abbinamento per 10 minuti.
CIFRE_CODICE = 6
DURATA_CODICE_MINUTI = 15
TENTATIVI_MASSIMI = 10
FINESTRA_TENTATIVI_MINUTI = 10
BLOCCO_ABBINAMENTO_MINUTI = 10

# Ambiente d'esecuzione. In "dev" (default) i token possono restare i default di
# sviluppo: comodo per test e sviluppo locale. In "prod" il postino RIFIUTA di
# partire con token deboli o di default (vedi valida_produzione).
ENV_DEFAULT = "dev"

# I token di sviluppo sono un segreto pubblico (stanno nel repo): vanno bene solo
# in dev. In prod devono essere rimpiazzati con token forti e distinti.
TOKEN_FIGLIO_DEV = "dev-token-figlio"
TOKEN_GENITORE_DEV = "dev-token-genitore"
LUNGHEZZA_MINIMA_TOKEN = 24


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
    env: str
    token_figlio: str
    token_genitore: str
    db_path: str
    versioni_path: str
    apk_dir: str
    backup_dir: str = BACKUP_DIR_DEFAULT
    # (v3.2) Il nome di una copia da rimettere al posto del registro all'avvio.
    ripristina: str = ""


def carica_settings() -> Settings:
    return Settings(
        env=os.environ.get("PACTUM_ENV", ENV_DEFAULT),
        token_figlio=os.environ.get("PACTUM_TOKEN_FIGLIO", TOKEN_FIGLIO_DEV),
        token_genitore=os.environ.get("PACTUM_TOKEN_GENITORE", TOKEN_GENITORE_DEV),
        db_path=os.environ.get("PACTUM_DB", "pactum.db"),
        versioni_path=os.environ.get("PACTUM_VERSIONI", VERSIONI_PATH_DEFAULT),
        apk_dir=os.environ.get("PACTUM_APK_DIR", APK_DIR_DEFAULT),
        backup_dir=os.environ.get("PACTUM_BACKUP_DIR", BACKUP_DIR_DEFAULT),
        ripristina=os.environ.get("PACTUM_RIPRISTINA", "").strip(),
    )


def _stato_token(token: str, dev_default: str) -> str:
    """Descrizione del token per il log/salute, MAI il valore: 'dev-default' se e'
    ancora il segreto di sviluppo, 'forte' se impostato e abbastanza lungo,
    'debole' se impostato ma piu' corto della soglia minima."""
    if token == dev_default:
        return "dev-default"
    if len(token) >= LUNGHEZZA_MINIMA_TOKEN:
        return "forte"
    return "debole"


def valida_produzione(settings: Settings) -> None:
    """In prod il postino non deve mai partire con token deboli: sono la sola
    barriera tra Internet (Cloudflare Tunnel) e il registro della famiglia.
    In dev non fa nulla, cosi' test e sviluppo locale restano invariati.
    Solleva RuntimeError elencando cosa sistemare, senza mai stampare i token."""
    if settings.env != "prod":
        return

    problemi: list[str] = []
    if settings.token_figlio == TOKEN_FIGLIO_DEV:
        problemi.append("PACTUM_TOKEN_FIGLIO e' ancora il default di sviluppo")
    elif len(settings.token_figlio) < LUNGHEZZA_MINIMA_TOKEN:
        problemi.append(
            f"PACTUM_TOKEN_FIGLIO e' piu' corto di {LUNGHEZZA_MINIMA_TOKEN} caratteri"
        )
    if settings.token_genitore == TOKEN_GENITORE_DEV:
        problemi.append("PACTUM_TOKEN_GENITORE e' ancora il default di sviluppo")
    elif len(settings.token_genitore) < LUNGHEZZA_MINIMA_TOKEN:
        problemi.append(
            f"PACTUM_TOKEN_GENITORE e' piu' corto di {LUNGHEZZA_MINIMA_TOKEN} caratteri"
        )
    if settings.token_figlio == settings.token_genitore:
        problemi.append("i due token sono uguali (figlio e genitore devono differire)")

    if problemi:
        elenco = "\n  - ".join(problemi)
        raise RuntimeError(
            "PACTUM_ENV=prod ma la configurazione dei token non e' sicura:\n  - "
            + elenco
            + "\nGenera token forti e distinti, es.:\n"
            "  python -c \"import secrets; print(secrets.token_urlsafe(32))\"\n"
            "e mettili in PACTUM_TOKEN_FIGLIO / PACTUM_TOKEN_GENITORE (file .env)."
        )


def riassunto_config(settings: Settings) -> str:
    """Riga sintetica per il log d'avvio: descrive la config effettiva SENZA mai
    esporre i token (solo il loro stato: dev-default / forte / debole)."""
    return (
        f"env={settings.env} db_path={settings.db_path} apk_dir={settings.apk_dir} "
        f"versioni_path={settings.versioni_path} backup_dir={settings.backup_dir} "
        f"timezone={nome_fuso()} "
        f"token_figlio={_stato_token(settings.token_figlio, TOKEN_FIGLIO_DEV)} "
        f"token_genitore={_stato_token(settings.token_genitore, TOKEN_GENITORE_DEV)}"
    )


def assicura_cartella_db(db_path: str) -> None:
    """Crea la cartella genitore del database se manca, cosi' un volume /data
    vergine (primo avvio sul NAS) non fa cadere l'inizializzazione dello schema."""
    genitore = Path(db_path).resolve().parent
    genitore.mkdir(parents=True, exist_ok=True)
