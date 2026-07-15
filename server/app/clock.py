"""Unica fonte dell'ora del server (UTC). Tutto il codice chiama clock.now():
i test sostituiscono questa funzione, il resto del sistema non tocca mai
datetime.now() direttamente. ts_device resta informativo, ts_server fa fede."""

from datetime import date, datetime, timezone


def now() -> datetime:
    return datetime.now(timezone.utc)


def iso(dt: datetime) -> str:
    return dt.isoformat(timespec="seconds")


def giorno_valido(giorno) -> bool:
    """Vero solo per una data reale in forma YYYY-MM-DD (contratto-api.md).
    Rifiuta forme non canoniche (2026-7-4), date impossibili e non-stringhe."""
    if not isinstance(giorno, str) or len(giorno) != 10:
        return False
    try:
        return date.fromisoformat(giorno).isoformat() == giorno
    except ValueError:
        return False
