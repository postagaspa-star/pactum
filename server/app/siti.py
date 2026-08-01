"""Siti visitati (v2.3, contratto-api.md — sezione "Siti visitati").

Il genitore VEDE quali siti, mai cosa ci fa dentro: SOLO il dominio registrabile
e quante volte e' stato richiesto in un giorno. Nessun URL, nessun contenuto,
nessuna ricerca, nessun orario. Il filtro dei domini tecnici e l'aggregazione sul
dominio registrabile (PSL, minuscolo) vivono nell'app del FIGLIO, prima
dell'invio: qui il server non re-inventa nulla, custodisce e restituisce.

Questo modulo e' condiviso da GET /api/finestra (genitore) e GET /api/patto
(figlio) apposta: `siti_recenti` deve essere IDENTICO per i due: e' il principio
della tavola rotonda — niente esiste nella finestra del genitore che il figlio
non veda identico. Una sola funzione = impossibile che divergano.

I siti non sono infrazioni: nessuna notifica, nessun semaforo, nessuno
sforamento nasce da qui.
"""

import json
import sqlite3
from datetime import date, datetime, timedelta

from . import clock, config

# Gli stessi 8 giorni del semaforo e di uso_recente (oggi + i 7 precedenti).
GIORNI_FINESTRA = 8


def giorni_finestra(ora: datetime) -> list[date]:
    """Gli 8 giorni della finestra, dal piu' vecchio a oggi, nel fuso del patto
    (contratto-api.md): in UTC il confine cadrebbe alle 02:00 locali italiane."""
    oggi = ora.astimezone(config.fuso_patto()).date()
    return [oggi - timedelta(days=n) for n in range(GIORNI_FINESTRA - 1, -1, -1)]


def domini_validi(dettagli: dict) -> dict:
    """Le voci {dominio: visite} sane di una fotografia: chiave stringa non vuota,
    valore intero non negativo (contratto-api.md: "le voci con valore non intero o
    negativo si scartano"). Una fotografia sporca non deve far crollare la
    finestra: si scartano le voci rotte, non l'intera giornata. `True`/`False`
    sono int in Python ma non sono conteggi: fuori."""
    mappa = dettagli.get("domini")
    if not isinstance(mappa, dict):
        return {}
    return {
        dominio: visite
        for dominio, visite in mappa.items()
        if isinstance(dominio, str)
        and dominio
        and isinstance(visite, int)
        and not isinstance(visite, bool)
        and visite >= 0
    }


def totale_domini(dettagli: dict, validi: dict) -> int:
    """Quanti domini distinti in TUTTO il giorno. L'app manda al massimo i 200
    domini piu' richiesti, ma questo resta il conteggio VERO: se la lista e'
    tagliata la differenza si vede (totale_domini > lunghezza della lista), non si
    finge. Mancante o non valido = numero di chiavi valide in `domini`
    (0 se manca anche `domini`)."""
    totale = dettagli.get("totale_domini")
    if isinstance(totale, bool) or not isinstance(totale, int) or totale < 0:
        return len(validi)
    return totale


def dns_cifrato(dettagli: dict) -> bool:
    """True quando l'app dichiara che il DNS cifrato (DoH/DoT) le ha impedito di
    vedere i domini: e' un DATO, non un errore — il registro dichiara di non aver
    potuto vedere invece di fingere zero traffico. Solo il booleano vero conta:
    una stringa "false" non deve diventare una cecita'."""
    return dettagli.get("dns_cifrato") is True


def aggiorna_vigente(
    conn: sqlite3.Connection, evento_id: str, dettagli: dict, ts: str
) -> None:
    """siti_giornalieri e' una fotografia CUMULATIVA del giorno e la vigente e'
    MONOTONA sulla coppia (totale_domini, somma delle richieste): una fotografia
    con valori inferiori non sovrascrive la vigente (protegge dalle consegne fuori
    ordine), a parita' di entrambi vince la piu' recente. Il registro eventi
    conserva comunque ogni fotografia ricevuta: qui si aggiorna solo la vigente.

    `dns_cifrato` e' APPICCICOSO sul giorno: se una qualsiasi fotografia del
    giorno lo dichiara true, il giorno resta cieco anche quando la fotografia
    vigente e' un'altra. La monotonia impedisce ai numeri di andare indietro,
    l'appiccicosita' impedisce alla confessione di sparire — per questo la
    seconda UPDATE e' fuori dalla condizione di monotonia."""
    giorno = dettagli.get("giorno")
    if not clock.giorno_valido(giorno):
        # Senza un giorno valido non c'e' fotografia da indicizzare:
        # l'evento resta comunque nel registro.
        return
    validi = domini_validi(dettagli)
    conn.execute(
        "INSERT INTO siti_giornalieri"
        " (giorno, dettagli, evento_id, ts_server, totale_domini, totale_visite, dns_cifrato)"
        " VALUES (?, ?, ?, ?, ?, ?, ?)"
        " ON CONFLICT(giorno) DO UPDATE SET"
        " dettagli = excluded.dettagli,"
        " evento_id = excluded.evento_id,"
        " ts_server = excluded.ts_server,"
        " totale_domini = excluded.totale_domini,"
        " totale_visite = excluded.totale_visite"
        " WHERE excluded.totale_domini >= siti_giornalieri.totale_domini"
        " AND excluded.totale_visite >= siti_giornalieri.totale_visite",
        (
            giorno,
            json.dumps(dettagli),
            evento_id,
            ts,
            totale_domini(dettagli, validi),
            sum(validi.values()),
            int(dns_cifrato(dettagli)),
        ),
    )
    if dns_cifrato(dettagli):
        conn.execute(
            "UPDATE siti_giornalieri SET dns_cifrato = 1 WHERE giorno = ?", (giorno,)
        )


def siti_recenti(conn: sqlite3.Connection, ora: datetime) -> list:
    """Gli 8 giorni della finestra, dal piu' vecchio a oggi, dalla fotografia
    siti_giornalieri VIGENTE di ciascun giorno. `domini` ordinati per visite
    DECRESCENTI (a parita', per dominio in ordine alfabetico: l'ordine e'
    deterministico, le due app mostrano la stessa lista).

    Un giorno senza fotografia ha domini [], totale_domini **null**,
    dns_cifrato false, aggiornato_ts null — MAI uno zero finto: null dice
    "nessuna fotografia arrivata", non "zero siti". Sono due informazioni
    diverse e restano distinte."""
    date_iso = [g.isoformat() for g in giorni_finestra(ora)]
    segnaposto = ",".join("?" * len(date_iso))
    vigenti = {
        r["giorno"]: r
        for r in conn.execute(
            f"SELECT * FROM siti_giornalieri WHERE giorno IN ({segnaposto})", date_iso
        ).fetchall()
    }

    voci = []
    for data in date_iso:
        riga = vigenti.get(data)
        if riga is None:
            voci.append(
                {
                    "giorno": data,
                    "totale_domini": None,
                    "dns_cifrato": False,
                    "aggiornato_ts": None,
                    "domini": [],
                }
            )
            continue
        dettagli = json.loads(riga["dettagli"])
        domini = [
            {"dominio": dominio, "visite": visite}
            for dominio, visite in sorted(
                domini_validi(dettagli).items(),
                key=lambda voce: (-voce[1], voce[0]),  # visite decrescenti, poi dominio
            )
        ]
        voci.append(
            {
                "giorno": data,
                "totale_domini": riga["totale_domini"],
                "dns_cifrato": bool(riga["dns_cifrato"]),
                "aggiornato_ts": riga["ts_server"],
                "domini": domini,
            }
        )
    return voci
