BEGIN TRANSACTION;
CREATE TABLE battiti (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    batteria INTEGER,
    versione_app TEXT,
    elapsed_realtime INTEGER,
    ts_device INTEGER,
    ts_server TEXT NOT NULL,
    dispositivo_id INTEGER REFERENCES dispositivi(id)
);
INSERT INTO "battiti" VALUES(1,80,'0.15.0',NULL,NULL,'2026-10-03T08:00:00+00:00',1);
INSERT INTO "battiti" VALUES(2,80,'0.14.0',NULL,NULL,'2026-10-03T08:00:00+00:00',2);
INSERT INTO "battiti" VALUES(3,80,'0.15.0',NULL,NULL,'2026-10-03T08:00:00+00:00',3);
CREATE TABLE bonus (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    minuti INTEGER NOT NULL,
    regola_id INTEGER REFERENCES regole(id),
    motivo TEXT,
    ts_server TEXT NOT NULL,
    dispositivo_id INTEGER REFERENCES dispositivi(id)
);
CREATE TABLE codici_abbinamento (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    dispositivo_id INTEGER NOT NULL REFERENCES dispositivi(id),
    codice_hash TEXT NOT NULL,
    creato_ts TEXT NOT NULL,
    scade_ts TEXT NOT NULL,
    usato_ts TEXT,
    annullato_ts TEXT
);
INSERT INTO "codici_abbinamento" VALUES(1,2,'44314570fa160d531538a89b64c3893e4613b38250c0dea7558c1d744fe703e1','2026-10-03T08:00:00+00:00','2026-10-03T08:15:00+00:00','2026-10-03T08:00:00+00:00',NULL);
INSERT INTO "codici_abbinamento" VALUES(2,3,'69413b5645f2be7d102cc1d0707d5ed21cc7b2dd8c3335b926dde9673c06bd7d','2026-10-03T08:00:00+00:00','2026-10-03T08:15:00+00:00','2026-10-03T08:00:00+00:00',NULL);
CREATE TABLE codici_genitori (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    genitore_id INTEGER NOT NULL REFERENCES genitori(id),
    codice_hash TEXT NOT NULL,
    creato_ts TEXT NOT NULL,
    scade_ts TEXT NOT NULL,
    usato_ts TEXT,
    annullato_ts TEXT
);
INSERT INTO "codici_genitori" VALUES(1,2,'2c53cb39e6873dbfc0e0cfea62cd43a2847ffc1dfd647bfb4f6e6012c6df0dfa','2026-10-03T08:00:00+00:00','2026-10-03T08:15:00+00:00','2026-10-03T08:00:00+00:00',NULL);
CREATE TABLE credenziali (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ruolo TEXT NOT NULL CHECK (ruolo IN ('genitore', 'dispositivo')),
    dispositivo_id INTEGER REFERENCES dispositivi(id),
    token_hash TEXT NOT NULL,
    origine TEXT NOT NULL CHECK (origine IN ('ambiente', 'abbinamento')),
    creata_ts TEXT NOT NULL,
    revocata_ts TEXT,
    genitore_id INTEGER REFERENCES genitori(id)
);
INSERT INTO "credenziali" VALUES(1,'genitore',NULL,'957d2f70d98e65c05fc3efdeed8d3051eb4bc282fff3809e430ccfc5c44dfa99','ambiente','2026-10-03T08:00:00+00:00',NULL,1);
INSERT INTO "credenziali" VALUES(2,'dispositivo',1,'df127b0dc21472236d0fa73adc905dd2f6f3837e904e361d5a5d2cbe0d18ae41','ambiente','2026-10-03T08:00:00+00:00',NULL,NULL);
INSERT INTO "credenziali" VALUES(3,'dispositivo',2,'e5a90215ac65c281c32fcd04305675bf7a1a43e21b8f503fe346312790d2945c','abbinamento','2026-10-03T08:00:00+00:00',NULL,NULL);
INSERT INTO "credenziali" VALUES(4,'genitore',NULL,'8dc9e93155d58506bacb20983c4f808d165342100d3c6fafa4b3cc360d35f234','abbinamento','2026-10-03T08:00:00+00:00',NULL,2);
INSERT INTO "credenziali" VALUES(5,'dispositivo',3,'4671465f424145dd299fa82586cb5d5402d57f023d478ed6841da8dc8a5debc6','abbinamento','2026-10-03T08:00:00+00:00',NULL,NULL);
CREATE TABLE dichiarazioni (
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
    ts_server TEXT NOT NULL,
    verdetto_genitore_id INTEGER REFERENCES genitori(id)
);
CREATE TABLE dispositivi (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    figlio_id INTEGER NOT NULL REFERENCES figli(id),
    nome TEXT NOT NULL,
    tipo TEXT NOT NULL CHECK (tipo IN ('telefono', 'computer')),
    versione_app TEXT,
    creato_ts TEXT NOT NULL,
    abbinato_ts TEXT,
    revocato_ts TEXT
);
INSERT INTO "dispositivi" VALUES(1,1,'Telefono','telefono','0.15.0','2026-10-03T08:00:00+00:00','2026-10-03T08:00:00+00:00',NULL);
INSERT INTO "dispositivi" VALUES(2,1,'Computer','computer','0.14.0','2026-10-03T08:00:00+00:00','2026-10-03T08:00:00+00:00',NULL);
INSERT INTO "dispositivi" VALUES(3,2,'Telefono di Sara','telefono','0.15.0','2026-10-03T08:00:00+00:00','2026-10-03T08:00:00+00:00',NULL);
CREATE TABLE eventi (
    id TEXT PRIMARY KEY,
    tipo TEXT NOT NULL,
    dettagli TEXT NOT NULL DEFAULT '{}',
    ts_device INTEGER,
    ts_server TEXT NOT NULL,
    dispositivo_id INTEGER REFERENCES dispositivi(id)
);
INSERT INTO "eventi" VALUES('uso-tel-1003','uso_giornaliero','{"giorno": "2026-10-03", "uso_minuti": {"com.zhiliaoapp.musically": 40}, "nomi": {"com.zhiliaoapp.musically": "TikTok"}, "totale_minuti": 40}',NULL,'2026-10-03T08:00:00+00:00',1);
CREATE TABLE faccende (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    figlio_id INTEGER NOT NULL REFERENCES figli(id),
    titolo TEXT NOT NULL,
    nota TEXT,
    stato TEXT NOT NULL DEFAULT 'da_fare' CHECK (stato IN ('da_fare', 'fatta', 'annullata')),
    blocco_da TEXT NOT NULL,
    creata_ts TEXT NOT NULL,
    creata_genitore_id INTEGER NOT NULL REFERENCES genitori(id),
    giro INTEGER NOT NULL,
    foto_ts TEXT,
    bocciature INTEGER NOT NULL DEFAULT 0,
    bocciata_ts TEXT,
    bocciata_nota TEXT,
    bocciata_genitore_id INTEGER REFERENCES genitori(id),
    chiusa_ts TEXT,
    annullata_genitore_id INTEGER REFERENCES genitori(id)
);
INSERT INTO "faccende" VALUES(1,1,'Svuota la lavastoviglie','anche le pentole','fatta','2026-10-03T09:00:00+00:00','2026-10-03T09:00:00+00:00',2,1,'2026-10-05T08:00:00+00:00',0,NULL,NULL,NULL,'2026-10-05T08:00:00+00:00',NULL);
INSERT INTO "faccende" VALUES(2,1,'Rifai il letto',NULL,'fatta','2026-10-03T09:40:00+00:00','2026-10-03T09:00:00+00:00',2,1,'2026-10-03T10:00:00+00:00',1,'2026-10-03T09:40:00+00:00','le lenzuola!',2,'2026-10-03T10:00:00+00:00',NULL);
INSERT INTO "faccende" VALUES(3,1,'Porta fuori il cane',NULL,'annullata','2026-10-03T09:00:00+00:00','2026-10-03T09:00:00+00:00',1,1,NULL,0,NULL,NULL,NULL,'2026-10-03T10:00:00+00:00',1);
INSERT INTO "faccende" VALUES(4,2,'Riordina la camera',NULL,'fatta','2026-10-03T09:00:00+00:00','2026-10-03T09:00:00+00:00',2,1,'2026-10-03T10:00:00+00:00',0,NULL,NULL,NULL,'2026-10-03T10:00:00+00:00',NULL);
INSERT INTO "faccende" VALUES(5,1,'Prepara il caffè per papà',NULL,'fatta','2026-10-04T15:00:00+00:00','2026-10-04T15:00:00+00:00',1,1,'2026-10-04T15:45:00+00:00',0,NULL,NULL,NULL,'2026-10-04T15:45:00+00:00',NULL);
INSERT INTO "faccende" VALUES(6,1,'Stendi i panni',NULL,'da_fare','2026-10-05T08:30:00+00:00','2026-10-05T08:30:00+00:00',1,2,NULL,0,NULL,NULL,NULL,NULL,NULL);
INSERT INTO "faccende" VALUES(7,1,'Compiti di matematica','pagina 42','da_fare','2026-10-05T14:00:00+00:00','2026-10-05T09:00:00+00:00',2,2,NULL,0,NULL,NULL,NULL,NULL,NULL);
CREATE TABLE faccende_storia (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    faccenda_id INTEGER NOT NULL REFERENCES faccende(id),
    tipo TEXT NOT NULL CHECK (tipo IN ('data', 'foto', 'bocciata', 'annullata')),
    ts TEXT NOT NULL,
    genitore_id INTEGER REFERENCES genitori(id),
    nota TEXT
);
INSERT INTO "faccende_storia" VALUES(1,1,'data','2026-10-03T09:00:00+00:00',2,NULL);
INSERT INTO "faccende_storia" VALUES(2,2,'data','2026-10-03T09:00:00+00:00',2,NULL);
INSERT INTO "faccende_storia" VALUES(3,3,'data','2026-10-03T09:00:00+00:00',1,NULL);
INSERT INTO "faccende_storia" VALUES(4,4,'data','2026-10-03T09:00:00+00:00',2,NULL);
INSERT INTO "faccende_storia" VALUES(5,2,'foto','2026-10-03T09:30:00+00:00',NULL,NULL);
INSERT INTO "faccende_storia" VALUES(6,2,'bocciata','2026-10-03T09:40:00+00:00',2,'le lenzuola!');
INSERT INTO "faccende_storia" VALUES(7,2,'foto','2026-10-03T10:00:00+00:00',NULL,NULL);
INSERT INTO "faccende_storia" VALUES(8,3,'annullata','2026-10-03T10:00:00+00:00',1,NULL);
INSERT INTO "faccende_storia" VALUES(9,4,'foto','2026-10-03T10:00:00+00:00',NULL,NULL);
INSERT INTO "faccende_storia" VALUES(10,5,'data','2026-10-04T15:00:00+00:00',1,NULL);
INSERT INTO "faccende_storia" VALUES(11,5,'foto','2026-10-04T15:45:00+00:00',NULL,NULL);
INSERT INTO "faccende_storia" VALUES(12,1,'foto','2026-10-05T08:00:00+00:00',NULL,NULL);
INSERT INTO "faccende_storia" VALUES(13,6,'data','2026-10-05T08:30:00+00:00',1,NULL);
INSERT INTO "faccende_storia" VALUES(14,7,'data','2026-10-05T09:00:00+00:00',2,NULL);
CREATE TABLE figli (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    nome TEXT NOT NULL,
    creato_ts TEXT NOT NULL
);
INSERT INTO "figli" VALUES(1,'Luca','2026-10-03T08:00:00+00:00');
INSERT INTO "figli" VALUES(2,'Sara','2026-10-03T08:00:00+00:00');
CREATE TABLE genitori (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    nome TEXT NOT NULL,
    creato_ts TEXT NOT NULL,
    abbinato_ts TEXT,
    revocato_ts TEXT,
    notifiche_dopo_id INTEGER NOT NULL DEFAULT 0
);
INSERT INTO "genitori" VALUES(1,'Genitore','2026-10-03T08:00:00+00:00','2026-10-03T08:00:00+00:00',NULL,0);
INSERT INTO "genitori" VALUES(2,'Mamma','2026-10-03T08:00:00+00:00','2026-10-03T08:00:00+00:00',NULL,0);
CREATE TABLE notifiche (
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
INSERT INTO "notifiche" VALUES(1,'genitore','modifica_regola','Nuova regola limite_tempo creata','{"regola_id": 1, "azione": "creazione", "parametri": {"app_o_categoria": "com.zhiliaoapp.musically", "minuti_al_giorno": 60}}',0,'2026-10-03T08:00:00+00:00',1,1);
INSERT INTO "notifiche" VALUES(2,'figlio','nuove_faccende','Mamma ti ha dato 2 lavori di casa','{"faccenda_ids": [1, 2], "blocco_da": "2026-10-03T09:00:00+00:00", "genitore": {"id": 2, "nome": "Mamma"}}',0,'2026-10-03T09:00:00+00:00',1,NULL);
INSERT INTO "notifiche" VALUES(3,'figlio','nuove_faccende','Genitore ti ha dato un lavoro di casa: «Porta fuori il cane»','{"faccenda_ids": [3], "blocco_da": "2026-10-03T09:00:00+00:00", "genitore": {"id": 1, "nome": "Genitore"}}',0,'2026-10-03T09:00:00+00:00',1,NULL);
INSERT INTO "notifiche" VALUES(4,'figlio','nuove_faccende','Mamma ti ha dato un lavoro di casa: «Riordina la camera»','{"faccenda_ids": [4], "blocco_da": "2026-10-03T09:00:00+00:00", "genitore": {"id": 2, "nome": "Mamma"}}',0,'2026-10-03T09:00:00+00:00',2,NULL);
INSERT INTO "notifiche" VALUES(5,'genitore','faccenda_fatta','Luca ha fatto «Rifai il letto»','{"faccenda_id": 2, "titolo": "Rifai il letto"}',0,'2026-10-03T09:30:00+00:00',1,NULL);
INSERT INTO "notifiche" VALUES(6,'figlio','faccenda_bocciata','Mamma ha bocciato «Rifai il letto»: le lenzuola!','{"faccenda_id": 2, "titolo": "Rifai il letto", "nota": "le lenzuola!", "genitore": {"id": 2, "nome": "Mamma"}}',0,'2026-10-03T09:40:00+00:00',1,NULL);
INSERT INTO "notifiche" VALUES(7,'genitore','faccenda_fatta','Luca ha fatto «Rifai il letto»','{"faccenda_id": 2, "titolo": "Rifai il letto"}',0,'2026-10-03T10:00:00+00:00',1,NULL);
INSERT INTO "notifiche" VALUES(8,'figlio','faccenda_annullata','Genitore ha annullato «Porta fuori il cane»','{"faccenda_id": 3, "titolo": "Porta fuori il cane", "genitore": {"id": 1, "nome": "Genitore"}}',0,'2026-10-03T10:00:00+00:00',1,NULL);
INSERT INTO "notifiche" VALUES(9,'genitore','faccenda_fatta','Sara ha fatto «Riordina la camera»','{"faccenda_id": 4, "titolo": "Riordina la camera"}',0,'2026-10-03T10:00:00+00:00',2,NULL);
INSERT INTO "notifiche" VALUES(10,'genitore','faccende_finite','Sara ha finito i lavori di casa: telefono e computer sbloccati','{"faccenda_ids": [4]}',0,'2026-10-03T10:00:00+00:00',2,NULL);
INSERT INTO "notifiche" VALUES(11,'figlio','nuove_faccende','Genitore ti ha dato un lavoro di casa: «Prepara il caffè per papà»','{"faccenda_ids": [5], "blocco_da": "2026-10-04T15:00:00+00:00", "genitore": {"id": 1, "nome": "Genitore"}}',0,'2026-10-04T15:00:00+00:00',1,NULL);
INSERT INTO "notifiche" VALUES(12,'genitore','faccenda_fatta','Luca ha fatto «Prepara il caffè per papà»','{"faccenda_id": 5, "titolo": "Prepara il caff\u00e8 per pap\u00e0"}',0,'2026-10-04T15:45:00+00:00',1,NULL);
INSERT INTO "notifiche" VALUES(13,'genitore','faccenda_fatta','Luca ha fatto «Svuota la lavastoviglie»','{"faccenda_id": 1, "titolo": "Svuota la lavastoviglie"}',0,'2026-10-05T08:00:00+00:00',1,NULL);
INSERT INTO "notifiche" VALUES(14,'genitore','faccende_finite','Luca ha finito i lavori di casa: telefono e computer sbloccati','{"faccenda_ids": [1, 2, 5]}',0,'2026-10-05T08:00:00+00:00',1,NULL);
INSERT INTO "notifiche" VALUES(15,'figlio','nuove_faccende','Genitore ti ha dato un lavoro di casa: «Stendi i panni»','{"faccenda_ids": [6], "blocco_da": "2026-10-05T08:30:00+00:00", "genitore": {"id": 1, "nome": "Genitore"}}',0,'2026-10-05T08:30:00+00:00',1,NULL);
INSERT INTO "notifiche" VALUES(16,'figlio','nuove_faccende','Mamma ti ha dato un lavoro di casa: «Compiti di matematica»','{"faccenda_ids": [7], "blocco_da": "2026-10-05T14:00:00+00:00", "genitore": {"id": 2, "nome": "Mamma"}}',0,'2026-10-05T09:00:00+00:00',1,NULL);
CREATE TABLE notifiche_lette (
    notifica_id INTEGER NOT NULL REFERENCES notifiche(id),
    dispositivo_id INTEGER NOT NULL REFERENCES dispositivi(id),
    ts_server TEXT NOT NULL,
    PRIMARY KEY (notifica_id, dispositivo_id)
);
CREATE TABLE notifiche_lette_genitori (
    notifica_id INTEGER NOT NULL REFERENCES notifiche(id),
    genitore_id INTEGER NOT NULL REFERENCES genitori(id),
    ts_server TEXT NOT NULL,
    PRIMARY KEY (notifica_id, genitore_id)
);
CREATE TABLE patto (
    chiave TEXT PRIMARY KEY,
    valore TEXT NOT NULL
);
INSERT INTO "patto" VALUES('tetto_bonus_giorno','30');
INSERT INTO "patto" VALUES('tetto_bonus_settimana','90');
CREATE TABLE proposte (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    regola_id INTEGER NOT NULL REFERENCES regole(id),
    parametri_proposti TEXT,
    motivazione TEXT,
    confronto TEXT,
    direzione TEXT,
    stato TEXT NOT NULL DEFAULT 'pendente'
        CHECK (stato IN ('pendente', 'accettata', 'rifiutata', 'annullata', 'ritirata')),
    usata INTEGER NOT NULL DEFAULT 0,
    risposta_esito TEXT,
    risposta_motivazione TEXT,
    risposta_ts TEXT,
    ts_server TEXT NOT NULL,
    autore TEXT NOT NULL DEFAULT 'genitore' CHECK (autore IN ('genitore', 'figlio')),
    genitore_id INTEGER REFERENCES genitori(id),
    risposta_genitore_id INTEGER REFERENCES genitori(id)
);
CREATE TABLE regole (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    tipo TEXT NOT NULL CHECK (tipo IN ('limite_tempo', 'fascia_oraria', 'vita_reale')),
    parametri TEXT NOT NULL,
    attiva INTEGER NOT NULL DEFAULT 1,
    creata_ts TEXT NOT NULL,
    ultima_modifica_ts TEXT NOT NULL,
    figlio_id INTEGER REFERENCES figli(id),
    dispositivo_id INTEGER REFERENCES dispositivi(id)
);
INSERT INTO "regole" VALUES(1,'limite_tempo','{"app_o_categoria": "com.zhiliaoapp.musically", "minuti_al_giorno": 60}',1,'2026-10-03T08:00:00+00:00','2026-10-03T08:00:00+00:00',1,1);
CREATE TABLE sessioni (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    figlio_id INTEGER NOT NULL REFERENCES figli(id),
    dispositivo_id INTEGER NOT NULL REFERENCES dispositivi(id),
    nome TEXT NOT NULL,
    app TEXT NOT NULL,
    nomi TEXT NOT NULL DEFAULT '{}',
    stato TEXT NOT NULL DEFAULT 'in_attesa'
        CHECK (stato IN ('in_attesa', 'approvata', 'rifiutata')),
    modifica_in_attesa TEXT,
    motivazione TEXT,
    versione INTEGER NOT NULL DEFAULT 1,
    creata_ts TEXT NOT NULL,
    approvata_ts TEXT,
    eliminata_ts TEXT,
    decisa_genitore_id INTEGER REFERENCES genitori(id)
);
CREATE TABLE sessioni_svolte (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    sessione_id INTEGER NOT NULL REFERENCES sessioni(id),
    dispositivo_id INTEGER NOT NULL REFERENCES dispositivi(id),
    nome TEXT NOT NULL,
    app TEXT NOT NULL,
    nomi TEXT NOT NULL DEFAULT '{}',
    inizio_ts TEXT NOT NULL,
    durata_minuti INTEGER NOT NULL CHECK (durata_minuti BETWEEN 1 AND 1440),
    fine_prevista_ts TEXT NOT NULL,
    fine_ts TEXT,
    chiusura TEXT CHECK (chiusura IN ('scaduta', 'terminata')),
    CHECK ((fine_ts IS NULL) = (chiusura IS NULL))
);
CREATE TABLE siti_giornalieri (
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
CREATE TABLE storico_modifiche (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    regola_id INTEGER NOT NULL REFERENCES regole(id),
    azione TEXT NOT NULL CHECK (azione IN ('creazione', 'modifica', 'eliminazione')),
    direzione TEXT CHECK (direzione IN ('allenta', 'stringe')),
    parametri_prima TEXT,
    parametri_dopo TEXT,
    concordata INTEGER NOT NULL DEFAULT 0,
    ts_server TEXT NOT NULL
);
INSERT INTO "storico_modifiche" VALUES(1,1,'creazione',NULL,NULL,'{"app_o_categoria": "com.zhiliaoapp.musically", "minuti_al_giorno": 60}',0,'2026-10-03T08:00:00+00:00');
CREATE TABLE tentativi_abbinamento (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ts_server TEXT NOT NULL
);
CREATE TABLE uso_giornaliero (
    dispositivo_id INTEGER NOT NULL REFERENCES dispositivi(id),
    giorno TEXT NOT NULL,
    dettagli TEXT NOT NULL,
    evento_id TEXT NOT NULL REFERENCES eventi(id),
    ts_server TEXT NOT NULL,
    totale_minuti INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (dispositivo_id, giorno)
);
INSERT INTO "uso_giornaliero" VALUES(1,'2026-10-03','{"giorno": "2026-10-03", "uso_minuti": {"com.zhiliaoapp.musically": 40}, "nomi": {"com.zhiliaoapp.musically": "TikTok"}, "totale_minuti": 40}','uso-tel-1003','2026-10-03T08:00:00+00:00',40);
CREATE UNIQUE INDEX idx_dichiarazioni_regola_giorno
    ON dichiarazioni (regola_id, giorno);
CREATE INDEX idx_sessioni_dispositivo ON sessioni (dispositivo_id);
CREATE INDEX idx_sessioni_figlio ON sessioni (figlio_id);
CREATE INDEX idx_sessioni_svolte_dispositivo
    ON sessioni_svolte (dispositivo_id, inizio_ts);
CREATE UNIQUE INDEX idx_sessioni_svolte_una_aperta
    ON sessioni_svolte (dispositivo_id) WHERE fine_ts IS NULL;
CREATE INDEX idx_codici_genitori_hash ON codici_genitori (codice_hash);
CREATE INDEX idx_faccende_figlio ON faccende (figlio_id, stato);
CREATE INDEX idx_faccende_storia ON faccende_storia (faccenda_id, id);
CREATE TRIGGER faccende_storia_non_si_cambia BEFORE UPDATE ON faccende_storia
BEGIN SELECT RAISE(ABORT, 'la storia delle faccende non si cambia'); END;
CREATE TRIGGER faccende_storia_non_si_cancella BEFORE DELETE ON faccende_storia
BEGIN SELECT RAISE(ABORT, 'la storia delle faccende non si cancella'); END;
CREATE INDEX idx_credenziali_hash ON credenziali (token_hash);
CREATE INDEX idx_codici_hash ON codici_abbinamento (codice_hash);
CREATE INDEX idx_dispositivi_figlio ON dispositivi (figlio_id);
CREATE INDEX idx_regole_figlio ON regole (figlio_id);
CREATE INDEX idx_eventi_tipo_dispositivo ON eventi (tipo, dispositivo_id);
CREATE INDEX idx_eventi_tipo_dispositivo_ts ON eventi (tipo, dispositivo_id, ts_server);
CREATE INDEX idx_battiti_dispositivo ON battiti (dispositivo_id, ts_server);
CREATE INDEX idx_bonus_dispositivo ON bonus (dispositivo_id, ts_server);
CREATE INDEX idx_notifiche_figlio ON notifiche (figlio_id, letta);
DELETE FROM "sqlite_sequence";
INSERT INTO "sqlite_sequence" VALUES('figli',2);
INSERT INTO "sqlite_sequence" VALUES('dispositivi',3);
INSERT INTO "sqlite_sequence" VALUES('genitori',2);
INSERT INTO "sqlite_sequence" VALUES('credenziali',5);
INSERT INTO "sqlite_sequence" VALUES('codici_abbinamento',2);
INSERT INTO "sqlite_sequence" VALUES('codici_genitori',1);
INSERT INTO "sqlite_sequence" VALUES('battiti',3);
INSERT INTO "sqlite_sequence" VALUES('regole',1);
INSERT INTO "sqlite_sequence" VALUES('storico_modifiche',1);
INSERT INTO "sqlite_sequence" VALUES('notifiche',16);
INSERT INTO "sqlite_sequence" VALUES('faccende',7);
INSERT INTO "sqlite_sequence" VALUES('faccende_storia',14);
COMMIT;
