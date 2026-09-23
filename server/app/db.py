"""Persistenza SQLite: una connessione per richiesta, schema creato all'avvio.
Lo storico e gli eventi non si cancellano mai (il registro e' il prodotto):
le regole eliminate diventano attiva=0, i dispositivi revocati restano (v3)."""

import hashlib
import json
import logging
import os
import sqlite3
from datetime import datetime, timedelta, timezone

from fastapi import Request

from . import clock, config

# Lo stesso logger di main.py: uvicorn lo manda nel log del container.
log = logging.getLogger("uvicorn.error")

# uso_giornaliero e' una fotografia CUMULATIVA del giorno (contratto-api.md):
# il registro eventi conserva ogni fotografia ricevuta, ma la verita' sull'uso
# di un giorno e' custodita qui ed e' MONOTONA su totale_minuti: una fotografia
# sostituisce la vigente solo se il suo totale non regredisce (una consegna in
# ritardo di una fotografia piu' vecchia/bassa non cancella quella piu' alta).
# (v3) La vigente e' per (dispositivo, giorno): telefono e computer dello stesso
# figlio hanno ciascuno la sua giornata.
TABELLA_USO_GIORNALIERO = """
CREATE TABLE IF NOT EXISTS uso_giornaliero (
    dispositivo_id INTEGER NOT NULL REFERENCES dispositivi(id),
    giorno TEXT NOT NULL,
    dettagli TEXT NOT NULL,
    evento_id TEXT NOT NULL REFERENCES eventi(id),
    ts_server TEXT NOT NULL,
    totale_minuti INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (dispositivo_id, giorno)
);
"""

# siti_giornalieri (v2.3) e' la gemella di uso_giornaliero per i SITI VISITATI:
# fotografia cumulativa del giorno, il registro eventi conserva tutto, qui vive
# la VIGENTE. Monotona sulla coppia (totale_domini, totale_visite): una
# fotografia con valori inferiori non sovrascrive quella vigente (consegne fuori
# ordine). totale_domini e' il conteggio VERO dei domini distinti e puo' essere
# maggiore delle voci elencate (l'app taglia ai 200 piu' richiesti): la
# differenza si vede, non si finge. totale_visite (somma delle richieste) serve
# solo alla monotonia. dns_cifrato e' la dichiarazione di cecita' (DoH/DoT) ed e'
# APPICCICOSA sul giorno: una volta dichiarata non sparisce, anche se la
# fotografia vigente diventa un'altra. SOLO domini: mai URL, contenuti, ricerche.
# (v3) Anche qui la vigente e' per (dispositivo, giorno).
TABELLA_SITI_GIORNALIERI = """
CREATE TABLE IF NOT EXISTS siti_giornalieri (
    dispositivo_id INTEGER NOT NULL REFERENCES dispositivi(id),
    giorno TEXT NOT NULL,
    dettagli TEXT NOT NULL,
    evento_id TEXT NOT NULL REFERENCES eventi(id),
    ts_server TEXT NOT NULL,
    totale_domini INTEGER NOT NULL DEFAULT 0,
    totale_visite INTEGER NOT NULL DEFAULT 0,
    dns_cifrato INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (dispositivo_id, giorno)
);
"""

SCHEMA = """
CREATE TABLE IF NOT EXISTS patto (
    chiave TEXT PRIMARY KEY,
    valore TEXT NOT NULL
);

-- (v3) La famiglia: uno o piu' figli, ciascuno con uno o piu' dispositivi (un
-- telefono Android o un account di Windows su un PC). Ogni dispositivo ha regole,
-- tempi, bonus, siti e registro suoi; vita reale e striscia sono del figlio.
-- Un dispositivo nasce NON abbinato (abbinato_ts NULL) e si abbina con un codice.
-- revocato_ts: revocato dal genitore, il suo token non vale piu' ma niente si
-- cancella. versione_app: l'ultima dichiarata (all'abbinamento o col battito).
CREATE TABLE IF NOT EXISTS figli (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    nome TEXT NOT NULL,
    creato_ts TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS dispositivi (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    figlio_id INTEGER NOT NULL REFERENCES figli(id),
    nome TEXT NOT NULL,
    tipo TEXT NOT NULL CHECK (tipo IN ('telefono', 'computer')),
    versione_app TEXT,
    creato_ts TEXT NOT NULL,
    abbinato_ts TEXT,
    revocato_ts TEXT
);

-- (v3) I token: il server salva solo l'hash SHA-256, mai il token. ruolo
-- 'genitore' (dispositivo_id NULL) o 'dispositivo'. origine 'ambiente' = il
-- token e' PACTUM_TOKEN_GENITORE o PACTUM_TOKEN_FIGLIO (le app 0.7 installate) e
-- segue la variabile a ogni avvio; 'abbinamento' = nato da un codice.
-- revocata_ts: non vale piu' (dispositivo revocato o riabbinato).
CREATE TABLE IF NOT EXISTS credenziali (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ruolo TEXT NOT NULL CHECK (ruolo IN ('genitore', 'dispositivo')),
    dispositivo_id INTEGER REFERENCES dispositivi(id),
    token_hash TEXT NOT NULL,
    origine TEXT NOT NULL CHECK (origine IN ('ambiente', 'abbinamento')),
    creata_ts TEXT NOT NULL,
    revocata_ts TEXT
);

-- (v3) I codici di abbinamento: 6 cifre, 15 minuti, una volta sola; un codice
-- nuovo per lo stesso dispositivo annulla il precedente. Come i token, anche i
-- codici si salvano solo come hash.
CREATE TABLE IF NOT EXISTS codici_abbinamento (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    dispositivo_id INTEGER NOT NULL REFERENCES dispositivi(id),
    codice_hash TEXT NOT NULL,
    creato_ts TEXT NOT NULL,
    scade_ts TEXT NOT NULL,
    usato_ts TEXT,
    annullato_ts TEXT
);

-- (v3) Il registro dei tentativi di abbinamento falliti, contati su tutto il
-- server: dieci in dieci minuti bloccano ogni abbinamento per dieci minuti.
CREATE TABLE IF NOT EXISTS tentativi_abbinamento (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ts_server TEXT NOT NULL
);

-- (v3) figlio_id: di chi e' la regola. dispositivo_id: su quale dispositivo
-- vale; NULL per le vita_reale, che sono del figlio e non di un dispositivo.
CREATE TABLE IF NOT EXISTS regole (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    tipo TEXT NOT NULL CHECK (tipo IN ('limite_tempo', 'fascia_oraria', 'vita_reale')),
    parametri TEXT NOT NULL,
    attiva INTEGER NOT NULL DEFAULT 1,
    creata_ts TEXT NOT NULL,
    ultima_modifica_ts TEXT NOT NULL,
    figlio_id INTEGER REFERENCES figli(id),
    dispositivo_id INTEGER REFERENCES dispositivi(id)
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

-- Proposte del genitore (tappa 5). Il genitore non impone mai: propone.
-- parametri_proposti (JSON) sono i parametri ESATTI concordati: la modifica
-- concordata li applica tali e quali (il figlio non puo' cambiarli al volo).
-- Per una proposta di ELIMINAZIONE il valore e' il marcatore {"azione": "elimina"}.
-- confronto/direzione li calcola il server alla creazione (differenza vs valore
-- attuale, testo per la notifica al figlio). risposta_* si riempiono quando il
-- figlio accetta/rifiuta; usata=1 quando la modifica concordata e' stata applicata
-- (con l'auto-applicazione avviene insieme all'accettazione).
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

-- Dichiarazioni del figlio sulle regole di vita reale (tappa 5).
-- Fallimento = creduto sulla parola -> stato 'registrata'. Successo = serve il
-- verdetto del genitore/arbitro -> 'in_attesa'. Il verdetto porta a 'confermata',
-- 'confermata_per_conto' (il genitore garantisce di aver sentito l'arbitro fuori
-- dall'app) o 'ribaltata'. verdetto_registro conserva la frase leggibile del
-- registro (es. "confermato dal genitore per conto di [arbitro]") al momento del
-- verdetto, cosi' resta vera anche se la regola cambia dopo. arbitro_nome (v2.1)
-- e' l'arbitro CONGELATO alla creazione: i verdetti "per conto di" citano
-- l'arbitro di allora, anche se la regola cambia arbitro dopo. Max una
-- dichiarazione per regola per giorno (fuso del patto), garantita anche sotto
-- richieste concorrenti dall'indice UNIQUE (regola_id, giorno) qui sotto,
-- non dalla sola SELECT di controllo.
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

-- (v3) Eventi, battiti e bonus portano il dispositivo che li ha mandati.
CREATE TABLE IF NOT EXISTS eventi (
    id TEXT PRIMARY KEY,
    tipo TEXT NOT NULL,
    dettagli TEXT NOT NULL DEFAULT '{}',
    ts_device INTEGER,
    ts_server TEXT NOT NULL,
    dispositivo_id INTEGER REFERENCES dispositivi(id)
);
""" + TABELLA_USO_GIORNALIERO + TABELLA_SITI_GIORNALIERI + """
CREATE TABLE IF NOT EXISTS battiti (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    batteria INTEGER,
    versione_app TEXT,
    elapsed_realtime INTEGER,
    ts_device INTEGER,
    ts_server TEXT NOT NULL,
    dispositivo_id INTEGER REFERENCES dispositivi(id)
);

-- Bonus autoritativo (tappa 5): agganciato a una regola limite_tempo specifica
-- ("mi do +15 su TikTok"). regola_id e' obbligatorio dagli endpoint v2; resta
-- nullable per le righe dei database v1 (nessun aggancio da retro-attribuire).
-- (v3) I tetti valgono per dispositivo: dispositivo_id e' chi se l'e' concesso.
CREATE TABLE IF NOT EXISTS bonus (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    minuti INTEGER NOT NULL,
    regola_id INTEGER REFERENCES regole(id),
    motivo TEXT,
    ts_server TEXT NOT NULL,
    dispositivo_id INTEGER REFERENCES dispositivi(id)
);

-- Ogni notifica nasce per un destinatario ('figlio' o 'genitore'): il GET e la
-- marcatura come letta filtrano sul ruolo del token. Le righe v1 erano tutte del
-- genitore (backfill via _migra). (v3) figlio_id: il figlio di cui parla.
-- dispositivo_id: il dispositivo della regola o dell'evento, NULL per quelle del
-- figlio (vita reale, segno). 'figlio' vuol dire i dispositivi di quel figlio:
-- ciascuno legge quelle col suo dispositivo_id o NULL. (v3.1) `letta` vale per
-- quelle del genitore; quelle del figlio si leggono per dispositivo (notifiche_lette).
CREATE TABLE IF NOT EXISTS notifiche (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    destinatario TEXT NOT NULL DEFAULT 'genitore' CHECK (destinatario IN ('figlio', 'genitore')),
    tipo TEXT NOT NULL,
    messaggio TEXT NOT NULL,
    payload TEXT NOT NULL DEFAULT '{}',
    letta INTEGER NOT NULL DEFAULT 0,
    ts_server TEXT NOT NULL,
    figlio_id INTEGER REFERENCES figli(id),
    dispositivo_id INTEGER REFERENCES dispositivi(id)
);
"""

# (v3.1) Chi ha letto una notifica del figlio: ogni dispositivo per conto suo, cosi'
# una notifica per tutto il figlio (il segno) arriva al telefono E al computer anche
# se il telefono l'ha gia' mostrata. Per le notifiche del figlio `notifiche.letta`
# resta com'era (storia della v2.4) e non si usa piu'; quelle del genitore restano
# condivise e usano `letta`. Non sta in SCHEMA apposta: nasce in _migra_letture,
# nella stessa transazione che ci copia le letture di prima, cosi' "la tabella
# esiste" vuol dire "la migrazione delle letture e' fatta" e non si ripete mai.
TABELLA_NOTIFICHE_LETTE = """
CREATE TABLE notifiche_lette (
    notifica_id INTEGER NOT NULL REFERENCES notifiche(id),
    dispositivo_id INTEGER NOT NULL REFERENCES dispositivi(id),
    ts_server TEXT NOT NULL,
    PRIMARY KEY (notifica_id, dispositivo_id)
)
"""

# (v3.1) La copia completa fatta prima della migrazione v3: <db>.prima-v3-<data>.
SUFFISSO_COPIA_V3 = ".prima-v3-"

# Le tabelle che dicono se un database ha gia' una storia (_ci_sono_dati).
TABELLE_STORIA = ("regole", "eventi", "battiti", "bonus", "notifiche")

# (v3) Le colonne che i database v2.4 non hanno. ALTER TABLE ADD COLUMN con
# REFERENCES vuole il default NULL: che siano sempre riempite lo garantisce il codice.
COLONNE_V3 = [
    ("regole", "figlio_id", "INTEGER REFERENCES figli(id)"),
    ("regole", "dispositivo_id", "INTEGER REFERENCES dispositivi(id)"),
    ("eventi", "dispositivo_id", "INTEGER REFERENCES dispositivi(id)"),
    ("battiti", "dispositivo_id", "INTEGER REFERENCES dispositivi(id)"),
    ("bonus", "dispositivo_id", "INTEGER REFERENCES dispositivi(id)"),
    ("notifiche", "figlio_id", "INTEGER REFERENCES figli(id)"),
    ("notifiche", "dispositivo_id", "INTEGER REFERENCES dispositivi(id)"),
]

# (v3) Le fotografie vigenti passano da chiave `giorno` a (dispositivo_id, giorno):
# tabella -> (DDL nuovo, colonne da copiare).
FOTOGRAFIE_V3 = {
    "uso_giornaliero": (
        TABELLA_USO_GIORNALIERO,
        ["giorno", "dettagli", "evento_id", "ts_server", "totale_minuti"],
    ),
    "siti_giornalieri": (
        TABELLA_SITI_GIORNALIERI,
        ["giorno", "dettagli", "evento_id", "ts_server", "totale_domini", "totale_visite",
         "dns_cifrato"],
    ),
}

# Indici sulle colonne v3: si creano DOPO la migrazione, quando le colonne esistono.
# (v3.1) idx_eventi_tipo_dispositivo_ts: le letture della finestra e del patto
# partono da una data (semaforo.inizio_letture) e le liste "recenti" vogliono gli
# ultimi 20: con ts_server nell'indice si leggono solo quelle righe, non tutta la
# storia del dispositivo. Quello senza ts_server resta per le ricerche per rowid
# (sospensione e ripresa del computer).
INDICI_V3 = """
CREATE INDEX IF NOT EXISTS idx_credenziali_hash ON credenziali (token_hash);
CREATE INDEX IF NOT EXISTS idx_codici_hash ON codici_abbinamento (codice_hash);
CREATE INDEX IF NOT EXISTS idx_dispositivi_figlio ON dispositivi (figlio_id);
CREATE INDEX IF NOT EXISTS idx_regole_figlio ON regole (figlio_id);
CREATE INDEX IF NOT EXISTS idx_eventi_tipo_dispositivo ON eventi (tipo, dispositivo_id);
CREATE INDEX IF NOT EXISTS idx_eventi_tipo_dispositivo_ts ON eventi (tipo, dispositivo_id, ts_server);
CREATE INDEX IF NOT EXISTS idx_battiti_dispositivo ON battiti (dispositivo_id, ts_server);
CREATE INDEX IF NOT EXISTS idx_bonus_dispositivo ON bonus (dispositivo_id, ts_server);
CREATE INDEX IF NOT EXISTS idx_notifiche_figlio ON notifiche (figlio_id, letta);
"""

# (v3) Il figlio e il dispositivo che nascono al primo avvio (contratto-api.md,
# "Migrazione"): il nome del figlio si cambia dall'app del genitore.
NOME_FIGLIO_INIZIALE = "Figlio"
NOME_DISPOSITIVO_INIZIALE = "Telefono"


def connetti(db_path: str) -> sqlite3.Connection:
    # check_same_thread=False: la connessione vive dentro una singola richiesta,
    # ma FastAPI puo' spostare dependency e handler su thread diversi del pool.
    conn = sqlite3.connect(db_path, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    return conn


def hash_segreto(segreto: str) -> str:
    """(v3) L'hash SHA-256 di un token o di un codice: nel database finisce solo
    questo, mai il segreto."""
    return hashlib.sha256(segreto.encode("utf-8")).hexdigest()


def _colonne(conn: sqlite3.Connection, tabella: str) -> set[str]:
    return {r[1] for r in conn.execute(f"PRAGMA table_info({tabella})")}


def _tabelle(conn: sqlite3.Connection) -> set[str]:
    return {r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type = 'table'")}


def _migra(conn: sqlite3.Connection, crea_famiglia: bool = False) -> None:
    """Micro-migrazioni per database creati con schemi precedenti. SCHEMA
    (CREATE TABLE IF NOT EXISTS) gira prima: le tabelle nuove (dichiarazioni)
    nascono gia' bene, qui si aggiornano solo quelle preesistenti."""
    if "totale_minuti" not in _colonne(conn, "uso_giornaliero"):
        conn.execute(
            "ALTER TABLE uso_giornaliero ADD COLUMN totale_minuti INTEGER NOT NULL DEFAULT 0"
        )

    # siti_giornalieri (v2.3): sui database esistenti la tabella nasce da SCHEMA
    # (CREATE TABLE IF NOT EXISTS gira prima di qui), quindi non serve backfill —
    # nessun dato sui siti esisteva prima. Questi ALTER coprono il caso di un DB
    # che avesse gia' la tabella in una forma piu' magra (colonne di servizio
    # aggiunte dopo): idempotenti, no-op su un DB fresco.
    colonne_siti = _colonne(conn, "siti_giornalieri")
    if colonne_siti and "totale_visite" not in colonne_siti:
        conn.execute(
            "ALTER TABLE siti_giornalieri ADD COLUMN totale_visite INTEGER NOT NULL DEFAULT 0"
        )
    if colonne_siti and "dns_cifrato" not in colonne_siti:
        conn.execute(
            "ALTER TABLE siti_giornalieri ADD COLUMN dns_cifrato INTEGER NOT NULL DEFAULT 0"
        )

    # bonus: aggancio a una regola (v2). Le righe v1 restano senza regola_id.
    if "regola_id" not in _colonne(conn, "bonus"):
        conn.execute("ALTER TABLE bonus ADD COLUMN regola_id INTEGER REFERENCES regole(id)")

    # notifiche: destinatario (v2). Backfill delle righe v1 come 'genitore'.
    if "destinatario" not in _colonne(conn, "notifiche"):
        conn.execute(
            "ALTER TABLE notifiche ADD COLUMN destinatario TEXT NOT NULL DEFAULT 'genitore'"
        )

    # dichiarazioni: arbitro_nome congelato sulla riga (v2.1). Le righe preesistenti
    # restano NULL: la route ricade sull'arbitro corrente della regola solo per loro.
    if _colonne(conn, "dichiarazioni") and "arbitro_nome" not in _colonne(conn, "dichiarazioni"):
        conn.execute("ALTER TABLE dichiarazioni ADD COLUMN arbitro_nome TEXT")

    # proposte: lo stub v1 non aveva confronto/direzione/risposta_* e usava lo
    # stato 'in_attesa'. Il CHECK dello stato non si altera con ALTER: si
    # ricostruisce la tabella (di norma vuota, gli endpoint non esistevano in v1).
    colonne_proposte = _colonne(conn, "proposte")
    if colonne_proposte and "confronto" not in colonne_proposte:
        # PRAGMA foreign_keys e' un no-op dentro una transazione: si chiude prima
        # quella eventualmente aperta dagli ALTER qui sopra.
        conn.commit()
        conn.execute("PRAGMA foreign_keys=OFF")
        conn.executescript(
            """
            ALTER TABLE proposte RENAME TO _proposte_v1;
            CREATE TABLE proposte (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                regola_id INTEGER NOT NULL REFERENCES regole(id),
                parametri_proposti TEXT,
                motivazione TEXT,
                confronto TEXT,
                direzione TEXT,
                stato TEXT NOT NULL DEFAULT 'pendente'
                    CHECK (stato IN ('pendente', 'accettata', 'rifiutata', 'annullata')),
                usata INTEGER NOT NULL DEFAULT 0,
                risposta_esito TEXT,
                risposta_motivazione TEXT,
                risposta_ts TEXT,
                ts_server TEXT NOT NULL
            );
            INSERT INTO proposte
                (id, regola_id, parametri_proposti, motivazione, stato, usata, ts_server)
            SELECT id, regola_id, parametri_proposti, motivazione,
                CASE stato WHEN 'in_attesa' THEN 'pendente' ELSE stato END,
                usata, ts_server
            FROM _proposte_v1;
            DROP TABLE _proposte_v1;
            """
        )
        conn.execute("PRAGMA foreign_keys=ON")

    # proposte v2 -> v2.1: aggiungere 'annullata' al CHECK dello stato (l'eliminazione
    # diretta di una regola annulla le sue proposte pendenti). SQLite non altera un
    # CHECK: si ricostruisce preservando tutte le colonne v2. Il ramo v1 qui sopra
    # crea gia' la tabella col CHECK nuovo, quindi qui si intercetta solo il DB v2.
    sql_proposte = conn.execute(
        "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'proposte'"
    ).fetchone()
    if sql_proposte is not None and "annullata" not in sql_proposte[0]:
        conn.commit()
        conn.execute("PRAGMA foreign_keys=OFF")
        conn.executescript(
            """
            ALTER TABLE proposte RENAME TO _proposte_v2;
            CREATE TABLE proposte (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                regola_id INTEGER NOT NULL REFERENCES regole(id),
                parametri_proposti TEXT,
                motivazione TEXT,
                confronto TEXT,
                direzione TEXT,
                stato TEXT NOT NULL DEFAULT 'pendente'
                    CHECK (stato IN ('pendente', 'accettata', 'rifiutata', 'annullata')),
                usata INTEGER NOT NULL DEFAULT 0,
                risposta_esito TEXT,
                risposta_motivazione TEXT,
                risposta_ts TEXT,
                ts_server TEXT NOT NULL
            );
            INSERT INTO proposte
                (id, regola_id, parametri_proposti, motivazione, confronto, direzione,
                 stato, usata, risposta_esito, risposta_motivazione, risposta_ts, ts_server)
            SELECT id, regola_id, parametri_proposti, motivazione, confronto, direzione,
                 stato, usata, risposta_esito, risposta_motivazione, risposta_ts, ts_server
            FROM _proposte_v2;
            DROP TABLE _proposte_v2;
            """
        )
        conn.execute("PRAGMA foreign_keys=ON")

    # Indici UNIQUE: garantiscono l'unicita' anche sotto richieste concorrenti (una
    # sola dichiarazione per regola per giorno). Ricreati qui perche' una ricostruzione
    # della tabella qui sopra li avrebbe persi. IF NOT EXISTS = idempotente.
    conn.execute(
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_dichiarazioni_regola_giorno"
        " ON dichiarazioni (regola_id, giorno)"
    )

    _migra_v3(conn, crea_famiglia)
    conn.executescript("BEGIN;" + INDICI_V3 + "COMMIT;")
    _migra_letture(conn)


def _ci_sono_dati(conn: sqlite3.Connection) -> bool:
    """Il database ha gia' una storia (regole, registro, battiti, bonus o notifiche):
    va attaccata al figlio 1 / dispositivo 1. Proposte, dichiarazioni, storico e
    fotografie non esistono senza regole o eventi. Funziona anche prima che SCHEMA
    abbia creato le tabelle (v3.1: la copia si decide prima di toccare il file)."""
    esistenti = _tabelle(conn)
    return any(
        tabella in esistenti
        and conn.execute(f"SELECT 1 FROM {tabella} LIMIT 1").fetchone() is not None
        for tabella in TABELLE_STORIA
    )


def _mancanze_v3(conn: sqlite3.Connection) -> tuple[list, list]:
    """(v3) Cosa manca al database per essere in forma v3: le colonne nuove
    (tabella, colonna, definizione) e le fotografie ancora con la chiave `giorno`.
    Una tabella che non c'e' ancora ha tutto da fare."""
    colonne_mancanti = [
        (tabella, colonna, definizione)
        for tabella, colonna, definizione in COLONNE_V3
        if colonna not in _colonne(conn, tabella)
    ]
    da_ricostruire = [
        tabella for tabella in FOTOGRAFIE_V3 if "dispositivo_id" not in _colonne(conn, tabella)
    ]
    return colonne_mancanti, da_ricostruire


def _senza_figli(conn: sqlite3.Connection) -> bool:
    return "figli" not in _tabelle(conn) or conn.execute("SELECT 1 FROM figli LIMIT 1").fetchone() is None


def _va_migrato_a_v3(conn: sqlite3.Connection) -> bool:
    """(v3.1) Un database con una storia che la migrazione v3 toccherebbe: colonne o
    fotografie da rifare, o nessun figlio a cui attaccare la storia. Un database
    nuovo (niente storia) o gia' in forma v3 no."""
    if not _ci_sono_dati(conn):
        return False
    colonne_mancanti, da_ricostruire = _mancanze_v3(conn)
    return bool(colonne_mancanti or da_ricostruire) or _senza_figli(conn)


def _percorso_copia(db_path: str, ora: datetime) -> str:
    """<db>.prima-v3-AAAAMMGG-HHMMSS accanto al database, con l'ora del patto (quella
    che legge chi apre la cartella). Una copia non si sovrascrive mai: se il nome e'
    gia' preso (un avvio che non era riuscito a migrare, nello stesso secondo) si
    aggiunge -2, -3..."""
    base = f"{db_path}{SUFFISSO_COPIA_V3}{ora.astimezone(config.fuso_patto()):%Y%m%d-%H%M%S}"
    percorso, numero = base, 2
    while os.path.exists(percorso) or os.path.exists(percorso + ".parziale"):
        percorso, numero = f"{base}-{numero}", numero + 1
    return percorso


def _copia_prima_della_migrazione(conn: sqlite3.Connection, db_path: str) -> str:
    """(v3.1) Contratto, "Migrazione" punto 5: prima di toccare un database che ha gia'
    una storia, una copia completa accanto al file. Se non riesce il server NON parte:
    meglio fermo che migrato senza rete di sicurezza.

    VACUUM INTO scrive una copia coerente (una fotografia del database, come un
    backup) ma non la forza su disco: lo fa os.fsync, perche' la copia serve proprio
    se dopo va storto qualcosa, anche un NAS che si spegne. La copia nasce col nome
    `.parziale` e prende il suo nome solo quando e' completa e su disco: un file
    `.prima-v3-...` e' sempre una copia buona. Se non riesce, la copia a meta' si
    toglie: non serve a niente e occuperebbe il disco del NAS (con il disco pieno,
    anche quello degli altri servizi)."""
    percorso = _percorso_copia(db_path, clock.now())
    parziale = percorso + ".parziale"
    creato = False
    try:
        # "x": il file lo crea questo avvio (VACUUM INTO accetta un file vuoto), cosi'
        # se qualcosa va storto si toglie solo quello che abbiamo scritto noi.
        open(parziale, "xb").close()
        creato = True
        conn.execute("VACUUM INTO ?", (parziale,))
        with open(parziale, "rb+") as copia:
            os.fsync(copia.fileno())
        os.replace(parziale, percorso)
    except (sqlite3.Error, OSError) as errore:
        if creato:
            try:
                os.remove(parziale)
            except OSError:
                pass
        messaggio = (
            f"MIGRAZIONE v3 FERMATA: non riesco a fare la copia di sicurezza del database"
            f" ({percorso}): {errore}. Il database NON e' stato toccato e il server NON"
            f" parte. Controlla lo spazio libero e i permessi della cartella"
            f" {os.path.dirname(os.path.abspath(db_path))}, poi riavvia."
        )
        log.error(messaggio)
        raise RuntimeError(messaggio) from errore
    log.info("Copia di sicurezza prima della migrazione v3: %s", percorso)
    return percorso


def _crea_famiglia_iniziale(conn: sqlite3.Connection, ts: str) -> tuple[int, int]:
    """Il figlio 1 e il suo dispositivo 1, il telefono gia' abbinato (quello delle
    app 0.7). La versione dell'app e' l'ultima dichiarata nei battiti, se c'e'."""
    figlio_id = conn.execute(
        "INSERT INTO figli (nome, creato_ts) VALUES (?, ?)", (NOME_FIGLIO_INIZIALE, ts)
    ).lastrowid
    versione = conn.execute(
        "SELECT versione_app FROM battiti WHERE versione_app IS NOT NULL ORDER BY id DESC LIMIT 1"
    ).fetchone()
    dispositivo_id = conn.execute(
        "INSERT INTO dispositivi (figlio_id, nome, tipo, versione_app, creato_ts, abbinato_ts)"
        " VALUES (?, ?, 'telefono', ?, ?, ?)",
        (figlio_id, NOME_DISPOSITIVO_INIZIALE, versione[0] if versione else None, ts, ts),
    ).lastrowid
    return figlio_id, dispositivo_id


def _attacca_dati_esistenti(conn: sqlite3.Connection, figlio_id: int, dispositivo_id: int) -> None:
    """Tutto quello che c'era diventa del figlio 1 / dispositivo 1 (contratto-api.md,
    "Migrazione"): le vita_reale al figlio senza dispositivo, le altre regole, gli
    eventi, i battiti e i bonus al telefono, le notifiche al figlio. Alle notifiche
    va anche il dispositivo che avrebbero avuto nascendo in v3 (quello della regola
    o dell'evento di cui parlano), cosi' un computer abbinato dopo non ripesca gli
    avvisi vecchi del telefono."""
    conn.execute("UPDATE regole SET figlio_id = ? WHERE figlio_id IS NULL", (figlio_id,))
    conn.execute(
        "UPDATE regole SET dispositivo_id = ?"
        " WHERE dispositivo_id IS NULL AND tipo != 'vita_reale'",
        (dispositivo_id,),
    )
    for tabella in ("eventi", "battiti", "bonus"):
        conn.execute(
            f"UPDATE {tabella} SET dispositivo_id = ? WHERE dispositivo_id IS NULL",
            (dispositivo_id,),
        )

    dispositivo_della_regola = {
        r["id"]: r["dispositivo_id"] for r in conn.execute("SELECT id, dispositivo_id FROM regole")
    }
    for riga in conn.execute("SELECT id, payload FROM notifiche WHERE figlio_id IS NULL").fetchall():
        try:
            payload = json.loads(riga["payload"])
        except ValueError:
            payload = None
        if not isinstance(payload, dict):
            payload = {}
        regola_id = payload.get("regola_id")
        if isinstance(regola_id, int):
            dispositivo = dispositivo_della_regola.get(regola_id)
        elif "evento_id" in payload:
            dispositivo = dispositivo_id
        else:
            dispositivo = None  # il segno e gli avvisi senza regola: del figlio
        conn.execute(
            "UPDATE notifiche SET figlio_id = ?, dispositivo_id = ? WHERE id = ?",
            (figlio_id, dispositivo, riga["id"]),
        )


def _ricostruisci_fotografie(
    conn: sqlite3.Connection, tabella: str, dispositivo_id: int | None
) -> None:
    """La chiave primaria non si altera: la tabella si ricostruisce con la chiave
    (dispositivo_id, giorno) e le fotografie di prima vanno al dispositivo 1."""
    ddl, colonne = FOTOGRAFIE_V3[tabella]
    vecchia = f"_{tabella}_v24"
    elenco = ", ".join(colonne)
    conn.execute(f"ALTER TABLE {tabella} RENAME TO {vecchia}")
    conn.execute(ddl)
    conn.execute(
        f"INSERT INTO {tabella} (dispositivo_id, {elenco}) SELECT ?, {elenco} FROM {vecchia}",
        (dispositivo_id,),
    )
    conn.execute(f"DROP TABLE {vecchia}")


def _migra_v3(conn: sqlite3.Connection, crea_famiglia: bool) -> None:
    """(v3) Famiglia, figli e dispositivi (contratto-api.md, "Migrazione"): una
    volta sola e tutta dentro UNA transazione. Se qualcosa va storto a meta' (anche
    un riavvio del NAS), il database resta com'era e il prossimo avvio riprova da
    capo: niente mezzi stati, niente si perde e niente si duplica. Sulle tabelle
    gia' in forma v3 non fa niente.

    Il figlio 1 / dispositivo 1 nasce quando nel database non c'e' ancora nessun
    figlio e c'e' qualcosa a cui darlo: una storia da migrare o il token del
    telefono (PACTUM_TOKEN_FIGLIO, sempre presente quando il server parte)."""
    colonne_mancanti, da_ricostruire = _mancanze_v3(conn)
    nasce_famiglia = _senza_figli(conn) and (crea_famiglia or _ci_sono_dati(conn))
    if not (colonne_mancanti or da_ricostruire or nasce_famiglia):
        return

    # PRAGMA foreign_keys si cambia solo fuori da una transazione. Spento durante
    # la ricostruzione, come raccomanda SQLite: i riferimenti che il database ha
    # gia' si copiano come sono, e quelli nuovi li scrive questo codice.
    conn.commit()
    conn.execute("PRAGMA foreign_keys=OFF")
    try:
        conn.execute("BEGIN IMMEDIATE")
        try:
            for tabella, colonna, definizione in colonne_mancanti:
                conn.execute(f"ALTER TABLE {tabella} ADD COLUMN {colonna} {definizione}")
            dispositivo_id = None
            if nasce_famiglia:
                figlio_id, dispositivo_id = _crea_famiglia_iniziale(conn, clock.iso(clock.now()))
                _attacca_dati_esistenti(conn, figlio_id, dispositivo_id)
            if dispositivo_id is None:
                dispositivo_id = conn.execute("SELECT MIN(id) FROM dispositivi").fetchone()[0]
            for tabella in da_ricostruire:
                _ricostruisci_fotografie(conn, tabella, dispositivo_id)
            conn.commit()
        except BaseException:
            conn.rollback()
            raise
    finally:
        conn.execute("PRAGMA foreign_keys=ON")


def _migra_letture(conn: sqlite3.Connection) -> None:
    """(v3.1) Le notifiche del figlio si leggono per dispositivo (tabella
    notifiche_lette). Una volta sola, in una transazione: la tabella nasce qui
    insieme alle letture di prima, cosi' se esiste la migrazione e' fatta.

    Le notifiche del figlio gia' lette (`letta = 1`) risultano lette per i
    dispositivi del figlio che esistono adesso e che le ricevono (quello della
    notifica, o tutti per quelle del figlio). Quelle non lette restano da leggere
    per tutti. Un dispositivo nato dopo non ne ha lette nessuna: per lui valgono le
    notifiche "non ancora lette da quel dispositivo" del contratto."""
    if "notifiche_lette" in _tabelle(conn):
        return
    conn.commit()
    conn.execute("BEGIN IMMEDIATE")
    try:
        if "notifiche_lette" not in _tabelle(conn):  # riletto dentro il lock
            conn.execute(TABELLA_NOTIFICHE_LETTE)
            conn.execute(
                "INSERT INTO notifiche_lette (notifica_id, dispositivo_id, ts_server)"
                " SELECT n.id, d.id, ? FROM notifiche n JOIN dispositivi d ON d.figlio_id = n.figlio_id"
                " WHERE n.destinatario = 'figlio' AND n.letta = 1"
                " AND (n.dispositivo_id IS NULL OR n.dispositivo_id = d.id)",
                (clock.iso(clock.now()),),
            )
        conn.commit()
    except BaseException:
        conn.rollback()
        raise


def _sincronizza_credenziali(
    conn: sqlite3.Connection, token_figlio: str | None, token_genitore: str | None
) -> None:
    """(v3) Compatibilita' con le app 0.7: PACTUM_TOKEN_GENITORE resta il token del
    genitore 1 e PACTUM_TOKEN_FIGLIO quello del dispositivo 1. A ogni avvio l'hash
    segue la variabile d'ambiente (cambiare il token nel .env funziona come prima).

    Il dispositivo 1 pero' smette di seguirla quando esce dalla compatibilita': se
    e' stato revocato, o riabbinato con un codice (il token nuovo ha invalidato il
    vecchio), il token d'ambiente non deve tornare a valere a un riavvio."""
    ts = clock.iso(clock.now())
    if token_genitore:
        genitore = conn.execute(
            "SELECT id, token_hash FROM credenziali WHERE ruolo = 'genitore'"
            " AND revocata_ts IS NULL ORDER BY id LIMIT 1"
        ).fetchone()
        if genitore is None:
            conn.execute(
                "INSERT INTO credenziali (ruolo, token_hash, origine, creata_ts)"
                " VALUES ('genitore', ?, 'ambiente', ?)",
                (hash_segreto(token_genitore), ts),
            )
        elif genitore["token_hash"] != hash_segreto(token_genitore):
            conn.execute(
                "UPDATE credenziali SET token_hash = ? WHERE id = ?",
                (hash_segreto(token_genitore), genitore["id"]),
            )

    if token_figlio:
        primo = conn.execute("SELECT * FROM dispositivi ORDER BY id LIMIT 1").fetchone()
        if primo is None or primo["revocato_ts"] is not None:
            return
        ultima = conn.execute(
            "SELECT * FROM credenziali WHERE dispositivo_id = ? ORDER BY id DESC LIMIT 1",
            (primo["id"],),
        ).fetchone()
        if ultima is None:
            # Solo il telefono nato abbinato dalla migrazione riceve il token
            # d'ambiente: un dispositivo creato dal genitore si abbina col codice.
            if primo["abbinato_ts"] is not None:
                conn.execute(
                    "INSERT INTO credenziali (ruolo, dispositivo_id, token_hash, origine, creata_ts)"
                    " VALUES ('dispositivo', ?, ?, 'ambiente', ?)",
                    (primo["id"], hash_segreto(token_figlio), ts),
                )
        elif (
            ultima["origine"] == "ambiente"
            and ultima["revocata_ts"] is None
            and ultima["token_hash"] != hash_segreto(token_figlio)
        ):
            conn.execute(
                "UPDATE credenziali SET token_hash = ? WHERE id = ?",
                (hash_segreto(token_figlio), ultima["id"]),
            )


def init_db(
    db_path: str,
    tetto_giorno: int,
    tetto_settimana: int,
    token_figlio: str | None = None,
    token_genitore: str | None = None,
) -> None:
    conn = connetti(db_path)
    try:
        # (v3.1) Prima di scrivere qualsiasi cosa (anche solo le tabelle nuove dello
        # SCHEMA): un database con una storia da migrare alla v3 si copia accanto al
        # file. Se la copia non riesce, init_db si ferma qui e il server non parte.
        if _va_migrato_a_v3(conn):
            _copia_prima_della_migrazione(conn, db_path)
        # Tutto lo schema in una transazione: una scrittura sola su disco invece di
        # una per tabella (su Windows ogni transazione e' un file di journal in piu').
        conn.executescript("BEGIN;" + SCHEMA + "COMMIT;")
        _migra(conn, crea_famiglia=token_figlio is not None)
        conn.execute(
            "INSERT OR IGNORE INTO patto (chiave, valore) VALUES ('tetto_bonus_giorno', ?)",
            (str(tetto_giorno),),
        )
        conn.execute(
            "INSERT OR IGNORE INTO patto (chiave, valore) VALUES ('tetto_bonus_settimana', ?)",
            (str(tetto_settimana),),
        )
        _sincronizza_credenziali(conn, token_figlio, token_genitore)
        conn.commit()
    finally:
        conn.close()


def get_conn(request: Request):
    conn = connetti(request.app.state.settings.db_path)
    try:
        yield conn
    finally:
        conn.close()


def valore_patto(conn: sqlite3.Connection, chiave: str) -> str:
    riga = conn.execute("SELECT valore FROM patto WHERE chiave = ?", (chiave,)).fetchone()
    if riga is None:
        raise KeyError(chiave)
    return riga["valore"]


def accoda_notifica(
    conn: sqlite3.Connection,
    tipo: str,
    messaggio: str,
    payload: dict,
    ts: str,
    destinatario: str = "genitore",
    *,
    figlio_id: int,
    dispositivo_id: int | None,
) -> None:
    """(v3) Ogni notifica dice di quale figlio parla e, se riguarda un dispositivo
    (una sua regola, un suo evento, un suo bonus), di quale: NULL per quelle del
    figlio, che arrivano a tutti i suoi dispositivi."""
    conn.execute(
        "INSERT INTO notifiche"
        " (destinatario, tipo, messaggio, payload, ts_server, figlio_id, dispositivo_id)"
        " VALUES (?, ?, ?, ?, ?, ?, ?)",
        (destinatario, tipo, messaggio, json.dumps(payload), ts, figlio_id, dispositivo_id),
    )


def registra_modifica(
    conn: sqlite3.Connection,
    regola_id: int,
    azione: str,
    direzione: str | None,
    prima: dict | None,
    dopo: dict | None,
    concordata: bool,
    ts: str,
) -> None:
    conn.execute(
        "INSERT INTO storico_modifiche"
        " (regola_id, azione, direzione, parametri_prima, parametri_dopo, concordata, ts_server)"
        " VALUES (?, ?, ?, ?, ?, ?, ?)",
        (
            regola_id,
            azione,
            direzione,
            json.dumps(prima) if prima is not None else None,
            json.dumps(dopo) if dopo is not None else None,
            int(concordata),
            ts,
        ),
    )


def _inizio_giorno_settimana(ora: datetime) -> tuple[datetime, datetime]:
    """Confini (UTC) del giorno e della settimana ISO correnti, calcolati nel
    fuso del patto: il giorno di un ragazzo italiano non si azzera alle 02:00."""
    ora_locale = ora.astimezone(config.fuso_patto())
    inizio_giorno_locale = ora_locale.replace(hour=0, minute=0, second=0, microsecond=0)
    inizio_settimana_locale = inizio_giorno_locale - timedelta(days=ora_locale.weekday())
    return (
        inizio_giorno_locale.astimezone(timezone.utc),
        inizio_settimana_locale.astimezone(timezone.utc),
    )


def bonus_oggi_per_regola(conn: sqlite3.Connection, ora: datetime, dispositivo_id: int | None) -> dict:
    """Minuti bonus concessi OGGI (fuso del patto) per regola, chiave = regola_id
    come stringa: il valutatore locale del figlio ne ha bisogno per il limite
    efficace del giorno (minuti_al_giorno + bonus di quella regola). (v3) Quelli
    di un dispositivo: il bonus e' per dispositivo."""
    inizio_giorno, _ = _inizio_giorno_settimana(ora)
    righe = conn.execute(
        "SELECT regola_id, COALESCE(SUM(minuti), 0) AS totale FROM bonus"
        " WHERE dispositivo_id = ? AND ts_server >= ? AND regola_id IS NOT NULL"
        " GROUP BY regola_id",
        (dispositivo_id, clock.iso(inizio_giorno)),
    ).fetchall()
    return {str(r["regola_id"]): r["totale"] for r in righe}


def segno_mandato_oggi(conn: sqlite3.Connection, ora: datetime, figlio_id: int) -> bool:
    """(v2.4) True se il genitore ha gia' mandato il segno OGGI (fuso del patto).
    La traccia del segno e' la sua notifica al figlio (nel registro eventi non
    entra): le notifiche non si cancellano, al massimo si marcano lette, quindi
    anche un segno gia' letto conta. (v3) Un segno al giorno per figlio."""
    inizio_giorno, _ = _inizio_giorno_settimana(ora)
    riga = conn.execute(
        "SELECT 1 FROM notifiche WHERE tipo = 'segno' AND destinatario = 'figlio'"
        " AND figlio_id = ? AND ts_server >= ?",
        (figlio_id, clock.iso(inizio_giorno)),
    ).fetchone()
    return riga is not None


def stato_bonus(conn: sqlite3.Connection, ora: datetime, dispositivo_id: int | None) -> dict:
    """Contatori bonus del giorno e della settimana ISO (lunedi'-domenica).
    I confini dei bucket sono nel fuso del patto (config.fuso_patto);
    il confronto avviene sui ts_server UTC. (v3) I tetti valgono per dispositivo:
    si contano solo i bonus che quel dispositivo si e' concesso."""
    tetto_giorno = int(valore_patto(conn, "tetto_bonus_giorno"))
    tetto_settimana = int(valore_patto(conn, "tetto_bonus_settimana"))
    inizio_giorno, inizio_settimana = _inizio_giorno_settimana(ora)

    def usati_da(inizio: datetime) -> int:
        riga = conn.execute(
            "SELECT COALESCE(SUM(minuti), 0) AS totale FROM bonus"
            " WHERE dispositivo_id = ? AND ts_server >= ?",
            (dispositivo_id, clock.iso(inizio)),
        ).fetchone()
        return riga["totale"]

    usati_giorno = usati_da(inizio_giorno)
    usati_settimana = usati_da(inizio_settimana)
    return {
        "giorno": {
            "usati": usati_giorno,
            "tetto": tetto_giorno,
            "residui": tetto_giorno - usati_giorno,
        },
        "settimana": {
            "usati": usati_settimana,
            "tetto": tetto_settimana,
            "residui": tetto_settimana - usati_settimana,
        },
    }
