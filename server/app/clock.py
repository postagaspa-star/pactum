"""Unica fonte dell'ora del server (UTC). Tutto il codice chiama clock.now():
i test sostituiscono questa funzione, il resto del sistema non tocca mai
datetime.now() direttamente. ts_device resta informativo, ts_server fa fede."""

from datetime import date, datetime, timedelta, timezone

# (v3.5) Quando conta l'ora del dispositivo (ts_device) di un fatto consegnato in
# ritardo: solo se cade piu' di 2 minuti prima dell'arrivo (entro, e' un fatto mandato con
# la rete e un orologio un po' storto: vale l'arrivo) e non piu' di 48 ore prima. (v3.7)
# Le stesse tutele per la chiusura delle sessioni e per l'ora di una sospensione.
TOLLERANZA_OROLOGIO = timedelta(minutes=2)
RITARDO_MASSIMO = timedelta(hours=48)


def now() -> datetime:
    return datetime.now(timezone.utc)


def da_ts_device(ts_device) -> datetime | None:
    """ts_device (epoch in millisecondi UTC) come istante; None se manca o non e' una
    data possibile."""
    if ts_device is None or isinstance(ts_device, bool) or not isinstance(ts_device, int):
        return None
    try:
        return datetime.fromtimestamp(ts_device / 1000, tz=timezone.utc)
    except (OverflowError, OSError, ValueError):
        return None


def momento_dichiarato(arrivo: datetime, ts_device) -> datetime:
    """Quando e' successo un fatto, secondo il dispositivo, con le tutele: l'ora del
    dispositivo vale solo per un fatto consegnato in ritardo (piu' di 2 minuti prima
    dell'arrivo e non piu' di 48 ore prima); mai nel futuro. Altrimenti vale l'arrivo."""
    dal_dispositivo = da_ts_device(ts_device)
    if dal_dispositivo is not None and TOLLERANZA_OROLOGIO < arrivo - dal_dispositivo <= RITARDO_MASSIMO:
        return dal_dispositivo
    return arrivo


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
