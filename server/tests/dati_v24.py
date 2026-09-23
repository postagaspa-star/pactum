"""Un database come quello vero sul NAS prima della v3: lo SCHEMA e' quello di
`git show 89a380f:server/app/db.py` (v2.4, senza i commenti SQL) e i dati sono
quelli che la v2.4 scrive, riga per riga, per un figlio con un telefono.

Serve a due test: la migrazione (niente si perde, gli stessi numeri di prima) e
il cliente 0.7 (le app installate continuano a funzionare sul database migrato).
I numeri "di prima" stanno in dati/v24_prima.json: li ha calcolati il server v2.4
(il codice di 89a380f, esportato con `git archive`) su questo stesso database,
alla stessa ora (ORA_V24), leggendo finestra, patto, regole, proposte,
dichiarazioni e notifiche coi due token. Se questi dati cambiano, il file va
rigenerato allo stesso modo: col server v2.4, mai con quello nuovo."""

import json
import sqlite3
from datetime import datetime, timezone

# Mercoledi' 23 settembre 2026, 10:00 UTC = 12:00 a Roma. Finestra: 16-23/09.
ORA_V24 = datetime(2026, 9, 23, 10, 0, 0, tzinfo=timezone.utc)

SCHEMA_V24 = """
CREATE TABLE IF NOT EXISTS patto (
    chiave TEXT PRIMARY KEY,
    valore TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS regole (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    tipo TEXT NOT NULL CHECK (tipo IN ('limite_tempo', 'fascia_oraria', 'vita_reale')),
    parametri TEXT NOT NULL,
    attiva INTEGER NOT NULL DEFAULT 1,
    creata_ts TEXT NOT NULL,
    ultima_modifica_ts TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS storico_modifiche (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    regola_id INTEGER NOT NULL REFERENCES regole(id),
    azione TEXT NOT NULL CHECK (azione IN ('creazione', 'modifica', 'eliminazione')),
    direzione TEXT CHECK (direzione IN ('allenta', 'stringe')),
    parametri_prima TEXT,
    parametri_dopo TEXT,
    concordata INTEGER NOT NULL DEFAULT 0,
    ts_server TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS proposte (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    regola_id INTEGER NOT NULL REFERENCES regole(id),
    parametri_proposti TEXT,
    motivazione TEXT,
    confronto TEXT,
    direzione TEXT,
    stato TEXT NOT NULL DEFAULT 'pendente' CHECK (stato IN ('pendente', 'accettata', 'rifiutata', 'annullata')),
    usata INTEGER NOT NULL DEFAULT 0,
    risposta_esito TEXT,
    risposta_motivazione TEXT,
    risposta_ts TEXT,
    ts_server TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS dichiarazioni (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    regola_id INTEGER NOT NULL REFERENCES regole(id),
    giorno TEXT NOT NULL,
    esito TEXT NOT NULL CHECK (esito IN ('successo', 'fallimento')),
    nota TEXT,
    arbitro_nome TEXT,
    stato TEXT NOT NULL CHECK (stato IN ('registrata', 'in_attesa', 'confermata', 'confermata_per_conto', 'ribaltata')),
    verdetto_verdetto TEXT,
    verdetto_nota TEXT,
    verdetto_registro TEXT,
    verdetto_ts TEXT,
    ts_server TEXT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_dichiarazioni_regola_giorno
    ON dichiarazioni (regola_id, giorno);

CREATE TABLE IF NOT EXISTS eventi (
    id TEXT PRIMARY KEY,
    tipo TEXT NOT NULL,
    dettagli TEXT NOT NULL DEFAULT '{}',
    ts_device INTEGER,
    ts_server TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS uso_giornaliero (
    giorno TEXT PRIMARY KEY,
    dettagli TEXT NOT NULL,
    evento_id TEXT NOT NULL REFERENCES eventi(id),
    ts_server TEXT NOT NULL,
    totale_minuti INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS siti_giornalieri (
    giorno TEXT PRIMARY KEY,
    dettagli TEXT NOT NULL,
    evento_id TEXT NOT NULL REFERENCES eventi(id),
    ts_server TEXT NOT NULL,
    totale_domini INTEGER NOT NULL DEFAULT 0,
    totale_visite INTEGER NOT NULL DEFAULT 0,
    dns_cifrato INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS battiti (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    batteria INTEGER,
    versione_app TEXT,
    elapsed_realtime INTEGER,
    ts_device INTEGER,
    ts_server TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS bonus (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    minuti INTEGER NOT NULL,
    regola_id INTEGER REFERENCES regole(id),
    motivo TEXT,
    ts_server TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS notifiche (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    destinatario TEXT NOT NULL DEFAULT 'genitore' CHECK (destinatario IN ('figlio', 'genitore')),
    tipo TEXT NOT NULL,
    messaggio TEXT NOT NULL,
    payload TEXT NOT NULL DEFAULT '{}',
    letta INTEGER NOT NULL DEFAULT 0,
    ts_server TEXT NOT NULL
);
"""

TIKTOK = "com.zhiliaoapp.musically"
INSTAGRAM = "com.instagram.android"
WHATSAPP = "com.whatsapp"
YOUTUBE = "com.google.android.youtube"
TUTTI_I_GIORNI = ["lun", "mar", "mer", "gio", "ven", "sab", "dom"]

LIMITE_TIKTOK_45 = {"app_o_categoria": TIKTOK, "minuti_al_giorno": 45}
LIMITE_TIKTOK_60 = {"app_o_categoria": TIKTOK, "minuti_al_giorno": 60}
LIMITE_YOUTUBE = {"app_o_categoria": YOUTUBE, "minuti_al_giorno": 90}
LIMITE_SOCIAL = {"app_o_categoria": "categoria:social", "minuti_al_giorno": 120}
FASCIA_NOTTE = {"dalle": "22:00", "alle": "07:00", "giorni": TUTTI_I_GIORNI}
FASCIA_PRIMA = {"dalle": "21:30", "alle": "07:00", "giorni": TUTTI_I_GIORNI}
CAMMINO = {"descrizione": "Un'ora di cammino", "arbitro_nome": "Mamma", "frequenza": "ogni giorno"}

# giorno -> (totale_minuti della fotografia vigente, TikTok, Instagram, WhatsApp).
# Il 19/09 non c'e': un giorno senza fotografia. Il 20/09 e' una fotografia vera
# a zero minuti. Le prime tre sono fuori dagli 8 giorni ma dentro il mese.
USO = {
    "2026-08-28": (150, 90, 40, 20),
    "2026-09-05": (95, 40, 35, 20),
    "2026-09-10": (120, 50, 50, 20),
    "2026-09-15": (80, 30, 30, 20),
    "2026-09-16": (110, 50, 40, 20),
    "2026-09-17": (140, 57, 60, 23),
    "2026-09-18": (60, 20, 25, 15),
    "2026-09-20": (0, 0, 0, 0),
    "2026-09-21": (130, 44, 60, 26),
    "2026-09-22": (175, 72, 70, 33),
    "2026-09-23": (45, 20, 15, 10),
}


def _j(valore) -> str:
    return json.dumps(valore)


def _dettagli_uso(giorno: str, totale: int, tiktok: int, instagram: int, whatsapp: int) -> dict:
    dettagli = {
        "giorno": giorno,
        "uso_minuti": {TIKTOK: tiktok, INSTAGRAM: instagram, WHATSAPP: whatsapp},
        "totale_minuti": totale,
    }
    if giorno >= "2026-09-01":
        # Le fotografie piu' vecchie sono nate prima della v2.2: niente nomi ne' categorie.
        dettagli["nomi"] = {TIKTOK: "TikTok", INSTAGRAM: "Instagram", WHATSAPP: "WhatsApp"}
        dettagli["uso_categorie"] = {
            "categoria:social": tiktok + instagram,
            "categoria:altro": whatsapp,
        }
    return dettagli


def _riempi(conn: sqlite3.Connection) -> None:
    conn.executemany(
        "INSERT INTO patto (chiave, valore) VALUES (?, ?)",
        [("tetto_bonus_giorno", "30"), ("tetto_bonus_settimana", "90")],
    )

    conn.executemany(
        "INSERT INTO regole (id, tipo, parametri, attiva, creata_ts, ultima_modifica_ts)"
        " VALUES (?, ?, ?, ?, ?, ?)",
        [
            (1, "limite_tempo", _j(LIMITE_TIKTOK_60), 1,
             "2026-09-01T08:00:00+00:00", "2026-09-20T08:00:00+00:00"),
            (2, "fascia_oraria", _j(FASCIA_NOTTE), 1,
             "2026-09-05T18:00:00+00:00", "2026-09-05T18:00:00+00:00"),
            (3, "vita_reale", _j(CAMMINO), 1,
             "2026-09-05T18:05:00+00:00", "2026-09-05T18:05:00+00:00"),
            # Eliminata il 19/09 (19:00 a Roma) con una proposta accettata.
            (4, "limite_tempo", _j(LIMITE_YOUTUBE), 0,
             "2026-09-02T09:00:00+00:00", "2026-09-19T17:00:00+00:00"),
            # Nata dentro la finestra: grigia nei giorni prima del 18/09.
            (5, "limite_tempo", _j(LIMITE_SOCIAL), 1,
             "2026-09-18T07:30:00+00:00", "2026-09-18T07:30:00+00:00"),
        ],
    )

    conn.executemany(
        "INSERT INTO storico_modifiche (id, regola_id, azione, direzione, parametri_prima,"
        " parametri_dopo, concordata, ts_server) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        [
            (1, 1, "creazione", None, None, _j(LIMITE_TIKTOK_45), 0, "2026-09-01T08:00:00+00:00"),
            (2, 4, "creazione", None, None, _j(LIMITE_YOUTUBE), 0, "2026-09-02T09:00:00+00:00"),
            (3, 2, "creazione", None, None, _j(FASCIA_NOTTE), 0, "2026-09-05T18:00:00+00:00"),
            (4, 3, "creazione", None, None, _j(CAMMINO), 0, "2026-09-05T18:05:00+00:00"),
            (5, 5, "creazione", None, None, _j(LIMITE_SOCIAL), 0, "2026-09-18T07:30:00+00:00"),
            (6, 4, "eliminazione", "allenta", _j(LIMITE_YOUTUBE), None, 1,
             "2026-09-19T17:00:00+00:00"),
            (7, 1, "modifica", "allenta", _j(LIMITE_TIKTOK_45), _j(LIMITE_TIKTOK_60), 1,
             "2026-09-20T08:00:00+00:00"),
        ],
    )

    conn.executemany(
        "INSERT INTO proposte (id, regola_id, parametri_proposti, motivazione, confronto,"
        " direzione, stato, usata, risposta_esito, risposta_motivazione, risposta_ts, ts_server)"
        " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        [
            (1, 4, _j({"azione": "elimina"}), "YouTube ora lo guardi sulla TV",
             "propone di eliminare la regola", "elimina", "accettata", 1,
             "accetta", None, "2026-09-19T17:00:00+00:00", "2026-09-19T16:00:00+00:00"),
            (2, 1, _j(LIMITE_TIKTOK_60), "Un po' di piu' nel fine settimana",
             "+15 min al giorno rispetto ad ora", "allenta", "accettata", 1,
             "accetta", "grazie", "2026-09-20T08:00:00+00:00", "2026-09-19T20:00:00+00:00"),
            (3, 2, _j(FASCIA_PRIMA), "Mezz'ora prima",
             "orario da 22:00-07:00 a 21:30-07:00", "stringe", "rifiutata", 0,
             "rifiuta", "Il martedi' ho allenamento", "2026-09-21T19:00:00+00:00",
             "2026-09-21T18:00:00+00:00"),
            # Pendente: il confronto si ricalcola in lettura contro la regola di adesso.
            (4, 1, _j(LIMITE_TIKTOK_45), "Torniamo a 45?",
             "−15 min al giorno rispetto ad ora", "stringe", "pendente", 0,
             None, None, None, "2026-09-22T20:00:00+00:00"),
        ],
    )

    conn.executemany(
        "INSERT INTO dichiarazioni (id, regola_id, giorno, esito, nota, arbitro_nome, stato,"
        " verdetto_verdetto, verdetto_nota, verdetto_registro, verdetto_ts, ts_server)"
        " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        [
            (1, 3, "2026-09-10", "successo", None, "Mamma", "confermata", "conferma", None,
             "confermato da Mamma", "2026-09-10T19:00:00+00:00", "2026-09-10T18:00:00+00:00"),
            (2, 3, "2026-09-17", "successo", "al parco", "Mamma", "confermata_per_conto",
             "conferma_per_conto", "sentita al telefono",
             "confermato dal genitore per conto di Mamma", "2026-09-17T19:00:00+00:00",
             "2026-09-17T18:00:00+00:00"),
            (3, 3, "2026-09-18", "fallimento", "pioveva", "Mamma", "registrata",
             None, None, None, None, "2026-09-18T20:00:00+00:00"),
            (4, 3, "2026-09-20", "successo", None, "Mamma", "ribaltata", "ribalta", None,
             "non confermato dal genitore: conta come non riuscito",
             "2026-09-20T21:00:00+00:00", "2026-09-20T20:00:00+00:00"),
            (5, 3, "2026-09-21", "successo", None, "Mamma", "confermata", "conferma", None,
             "confermato da Mamma", "2026-09-21T20:00:00+00:00", "2026-09-21T19:30:00+00:00"),
            (6, 3, "2026-09-23", "successo", None, "Mamma", "in_attesa",
             None, None, None, None, "2026-09-23T09:00:00+00:00"),
        ],
    )

    eventi = []
    uso_vigenti = []
    for giorno, (totale, tiktok, instagram, whatsapp) in USO.items():
        mattina = f"{giorno}T08:00:00+00:00"
        sera = f"{giorno}T20:00:00+00:00" if giorno != "2026-09-23" else "2026-09-23T09:30:00+00:00"
        if totale:
            parziale = _dettagli_uso(giorno, totale // 2, tiktok // 2, instagram // 2, whatsapp // 2)
            eventi.append((f"uso-{giorno}-a", "uso_giornaliero", _j(parziale), None, mattina))
        finale = _dettagli_uso(giorno, totale, tiktok, instagram, whatsapp)
        eventi.append((f"uso-{giorno}-b", "uso_giornaliero", _j(finale), 1790000000000, sera))
        uso_vigenti.append((giorno, _j(finale), f"uso-{giorno}-b", sera, totale))
    # Consegnata in ritardo e piu' bassa: nel registro si', vigente no (monotonia).
    eventi.append((
        "uso-2026-09-22-ritardo", "uso_giornaliero",
        _j(_dettagli_uso("2026-09-22", 100, 40, 40, 20)), None, "2026-09-23T06:00:00+00:00",
    ))

    siti = [
        ("siti-10", "2026-09-10", {"x.com": 1}, 1, False, "2026-09-10T20:00:00+00:00"),
        ("siti-21", "2026-09-21", {"instagram.com": 40, "youtube.com": 12, "wikipedia.org": 3},
         5, False, "2026-09-21T20:00:00+00:00"),
        # Il 22 una fotografia cieca (DNS cifrato) e poi quella vigente: la cecita' resta.
        ("siti-22-a", "2026-09-22", {"instagram.com": 10}, 1, True, "2026-09-22T12:00:00+00:00"),
        ("siti-22-b", "2026-09-22", {"instagram.com": 55, "youtube.com": 20, "reddit.com": 7},
         3, False, "2026-09-22T20:00:00+00:00"),
        ("siti-23", "2026-09-23", {"youtube.com": 8, "instagram.com": 8},
         2, False, "2026-09-23T09:30:00+00:00"),
    ]
    siti_vigenti = {}
    for evento_id, giorno, domini, totale, cieco, ts in siti:
        dettagli = {"giorno": giorno, "domini": domini, "totale_domini": totale, "dns_cifrato": cieco}
        eventi.append((evento_id, "siti_giornalieri", _j(dettagli), None, ts))
        precedente = siti_vigenti.get(giorno)
        siti_vigenti[giorno] = (
            giorno, _j(dettagli), evento_id, ts, totale, sum(domini.values()),
            int(cieco or (precedente is not None and precedente[6])),
        )

    eventi += [
        ("sf-1", "sforamento",
         _j({"regola_id": 1, "giorno": "2026-09-17", "limite_efficace": 45, "minuti_oltre": 12}),
         1789670000000, "2026-09-17T19:30:00+00:00"),
        # Consegnato il giorno dopo, ma e' successo il 16.
        ("sf-2", "sforamento",
         _j({"regola_id": 4, "giorno": "2026-09-16", "limite_efficace": 90, "minuti_oltre": 20}),
         None, "2026-09-17T06:00:00+00:00"),
        # Fascia notturna senza `giorno`: cade il giorno d'arrivo (21/09 alle 22:40 a Roma).
        ("sf-3", "sforamento", _j({"regola_id": 2}), None, "2026-09-21T20:40:00+00:00"),
        ("sf-4", "sforamento",
         _j({"regola_id": 1, "giorno": "2026-09-22", "limite_efficace": 75, "minuti_oltre": 5}),
         None, "2026-09-22T19:00:00+00:00"),
        # YouTube era gia' eliminata il 21: fuori dalla sua vita, non tinge niente.
        ("sf-5", "sforamento", _j({"regola_id": 4, "giorno": "2026-09-21"}),
         None, "2026-09-21T21:00:00+00:00"),
        ("ma-1", "manomissione", _j({"sotto_tipo": "cambio_ora", "drift_secondi": 3600}),
         None, "2026-09-18T10:00:00+00:00"),
        ("ma-2", "manomissione", _j({"sotto_tipo": "silenzio"}), None, "2026-09-22T03:00:00+00:00"),
        # Fuori dagli 8 giorni: non conta nelle interruzioni.
        ("ma-3", "manomissione", _j({"sotto_tipo": "permesso_revocato"}),
         None, "2026-09-12T10:00:00+00:00"),
        ("ri-1", "riavvio", _j({}), None, "2026-09-20T07:00:00+00:00"),
        ("bu-1", "bonus_usato", _j({"regola_id": 1, "minuti": 15}), None, "2026-09-23T08:00:00+00:00"),
    ]
    conn.executemany(
        "INSERT INTO eventi (id, tipo, dettagli, ts_device, ts_server) VALUES (?, ?, ?, ?, ?)",
        eventi,
    )
    conn.executemany(
        "INSERT INTO uso_giornaliero (giorno, dettagli, evento_id, ts_server, totale_minuti)"
        " VALUES (?, ?, ?, ?, ?)",
        uso_vigenti,
    )
    conn.executemany(
        "INSERT INTO siti_giornalieri (giorno, dettagli, evento_id, ts_server, totale_domini,"
        " totale_visite, dns_cifrato) VALUES (?, ?, ?, ?, ?, ?, ?)",
        list(siti_vigenti.values()),
    )

    conn.executemany(
        "INSERT INTO battiti (batteria, versione_app, elapsed_realtime, ts_device, ts_server)"
        " VALUES (?, ?, ?, ?, ?)",
        [
            (91, "0.6.0", 1000, 1789000000000, "2026-09-18T08:00:00+00:00"),
            (64, "0.7.0", 2000, None, "2026-09-22T21:00:00+00:00"),
            (None, "0.7.0", 3000, None, "2026-09-23T09:00:00+00:00"),
            (58, None, 4000, None, "2026-09-23T09:40:00+00:00"),
        ],
    )

    conn.executemany(
        "INSERT INTO bonus (id, minuti, regola_id, motivo, ts_server) VALUES (?, ?, ?, ?, ?)",
        [
            (1, 15, None, "vecchio, di prima della v2", "2026-08-20T10:00:00+00:00"),
            (2, 30, 4, "partita", "2026-09-16T15:00:00+00:00"),
            (3, 15, 1, "serie con gli amici", "2026-09-22T18:00:00+00:00"),
            (4, 5, 1, None, "2026-09-23T07:00:00+00:00"),
            (5, 15, 5, "compleanno di Luca", "2026-09-23T08:30:00+00:00"),
        ],
    )

    conn.executemany(
        "INSERT INTO notifiche (id, destinatario, tipo, messaggio, payload, letta, ts_server)"
        " VALUES (?, ?, ?, ?, ?, ?, ?)",
        [
            (1, "genitore", "modifica_regola", "Nuova regola limite_tempo creata",
             _j({"regola_id": 1, "azione": "creazione", "parametri": LIMITE_TIKTOK_45}), 1,
             "2026-09-01T08:00:00+00:00"),
            (2, "genitore", "sforamento", "Evento sforamento registrato",
             _j({"evento_id": "sf-1", "dettagli": {"regola_id": 1, "giorno": "2026-09-17",
                                                   "limite_efficace": 45, "minuti_oltre": 12}}),
             1, "2026-09-17T19:30:00+00:00"),
            (3, "genitore", "manomissione", "Evento manomissione registrato",
             _j({"evento_id": "ma-1", "dettagli": {"sotto_tipo": "cambio_ora", "drift_secondi": 3600}}),
             0, "2026-09-18T10:00:00+00:00"),
            (4, "figlio", "nuova_proposta", "Nuova proposta del genitore: orario da 22:00-07:00 a 21:30-07:00",
             _j({"proposta_id": 3, "regola_id": 2, "confronto": "orario da 22:00-07:00 a 21:30-07:00",
                 "direzione": "stringe"}), 1, "2026-09-21T18:00:00+00:00"),
            (5, "genitore", "proposta_risposta", "Il figlio ha risposto alla proposta: rifiuta",
             _j({"proposta_id": 3, "regola_id": 2, "esito": "rifiuta"}), 0,
             "2026-09-21T19:00:00+00:00"),
            (6, "figlio", "verdetto", "Esito della tua dichiarazione: confermato da Mamma",
             _j({"dichiarazione_id": 5, "regola_id": 3, "verdetto": "conferma"}), 1,
             "2026-09-21T20:00:00+00:00"),
            (7, "genitore", "bonus", "Bonus di 15 minuti auto-concesso",
             _j({"minuti": 15, "regola_id": 1, "motivo": "serie con gli amici",
                 "residuo_giorno": 15, "residuo_settimana": 75}), 0, "2026-09-22T18:00:00+00:00"),
            (8, "genitore", "sforamento", "Evento sforamento registrato",
             _j({"evento_id": "sf-4", "dettagli": {"regola_id": 1, "giorno": "2026-09-22",
                                                   "limite_efficace": 75, "minuti_oltre": 5}}),
             0, "2026-09-22T19:00:00+00:00"),
            (9, "figlio", "nuova_proposta", "Nuova proposta del genitore: −15 min al giorno rispetto ad ora",
             _j({"proposta_id": 4, "regola_id": 1, "confronto": "−15 min al giorno rispetto ad ora",
                 "direzione": "stringe"}), 0, "2026-09-22T20:00:00+00:00"),
            (10, "figlio", "segno", "Ho visto la settimana. Bene così.", _j({}), 0,
             "2026-09-23T07:30:00+00:00"),
            (11, "genitore", "dichiarazione", "Dichiarazione del figlio: successo (2026-09-23)",
             _j({"dichiarazione_id": 6, "regola_id": 3, "esito": "successo", "giorno": "2026-09-23"}),
             0, "2026-09-23T09:00:00+00:00"),
        ],
    )


def crea_db_v24(path: str) -> None:
    """Crea in `path` un database v2.4 pieno (schema di HEAD prima della v3)."""
    conn = sqlite3.connect(path)
    try:
        conn.executescript(SCHEMA_V24)
        _riempi(conn)
        conn.commit()
    finally:
        conn.close()


def righe(path: str) -> dict:
    """Tutte le righe di tutte le tabelle, per confrontare prima e dopo."""
    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    try:
        tabelle = [
            r[0]
            for r in conn.execute(
                "SELECT name FROM sqlite_master WHERE type = 'table'"
                " AND name NOT LIKE 'sqlite_%' ORDER BY name"
            )
        ]
        return {
            t: [dict(r) for r in conn.execute(f"SELECT * FROM {t} ORDER BY rowid")]
            for t in tabelle
        }
    finally:
        conn.close()
