"""Unica fonte dell'ora del server (UTC). Tutto il codice chiama clock.now():
i test sostituiscono questa funzione, il resto del sistema non tocca mai
datetime.now() direttamente. ts_device resta informativo, ts_server fa fede."""

from datetime import datetime, timezone


def now() -> datetime:
    return datetime.now(timezone.utc)


def iso(dt: datetime) -> str:
    return dt.isoformat(timespec="seconds")
