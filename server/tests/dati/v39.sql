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
INSERT INTO "battiti" VALUES(1,80,'0.17.0',NULL,NULL,'2026-10-04T08:00:00+00:00',1);
INSERT INTO "battiti" VALUES(2,80,'0.14.0',NULL,NULL,'2026-10-04T08:00:00+00:00',2);
INSERT INTO "battiti" VALUES(3,80,'0.17.0',NULL,NULL,'2026-10-04T08:00:00+00:00',3);
INSERT INTO "battiti" VALUES(4,70,NULL,NULL,NULL,'2026-10-06T09:55:00+00:00',1);
INSERT INTO "battiti" VALUES(5,70,NULL,NULL,NULL,'2026-10-06T09:55:00+00:00',2);
INSERT INTO "battiti" VALUES(6,70,NULL,NULL,NULL,'2026-10-06T09:55:00+00:00',3);
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
INSERT INTO "codici_abbinamento" VALUES(1,2,'3665c739fdcff0d21049780da2465834d5bba4730ab157ec0dd038695bc8b2f5','2026-10-04T08:00:00+00:00','2026-10-04T08:15:00+00:00','2026-10-04T08:00:00+00:00',NULL);
INSERT INTO "codici_abbinamento" VALUES(2,3,'866a67dd7c98c8b722aeba70a8335cf0bd97604af4873d4ef71cac97a0d243da','2026-10-04T08:00:00+00:00','2026-10-04T08:15:00+00:00','2026-10-04T08:00:00+00:00',NULL);
CREATE TABLE codici_genitori (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    genitore_id INTEGER NOT NULL REFERENCES genitori(id),
    codice_hash TEXT NOT NULL,
    creato_ts TEXT NOT NULL,
    scade_ts TEXT NOT NULL,
    usato_ts TEXT,
    annullato_ts TEXT
);
INSERT INTO "codici_genitori" VALUES(1,2,'46420096dfd5181385cd09505e228c35af44bae3b889940b55f4bcde7c22d3c9','2026-10-04T08:00:00+00:00','2026-10-04T08:15:00+00:00','2026-10-04T08:00:00+00:00',NULL);
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
INSERT INTO "credenziali" VALUES(1,'genitore',NULL,'957d2f70d98e65c05fc3efdeed8d3051eb4bc282fff3809e430ccfc5c44dfa99','ambiente','2026-10-04T08:00:00+00:00',NULL,1);
INSERT INTO "credenziali" VALUES(2,'dispositivo',1,'df127b0dc21472236d0fa73adc905dd2f6f3837e904e361d5a5d2cbe0d18ae41','ambiente','2026-10-04T08:00:00+00:00',NULL,NULL);
INSERT INTO "credenziali" VALUES(3,'dispositivo',2,'78e74cfd91294d930af7f223a12868c81c57ec7d60e773f1133cd1a410e24027','abbinamento','2026-10-04T08:00:00+00:00',NULL,NULL);
INSERT INTO "credenziali" VALUES(4,'genitore',NULL,'a3f34b9b2a2f90646298612d26a55ce8fd154a7282d435f7ebf1c39819e526bd','abbinamento','2026-10-04T08:00:00+00:00',NULL,2);
INSERT INTO "credenziali" VALUES(5,'dispositivo',3,'90a505946042c845438546d88c2d3e02085bf66e1137c88177d8a30bc47e7e0f','abbinamento','2026-10-04T08:00:00+00:00',NULL,NULL);
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
INSERT INTO "dispositivi" VALUES(1,1,'Telefono','telefono','0.17.0','2026-10-04T08:00:00+00:00','2026-10-04T08:00:00+00:00',NULL);
INSERT INTO "dispositivi" VALUES(2,1,'Computer','computer','0.14.0','2026-10-04T08:00:00+00:00','2026-10-04T08:00:00+00:00',NULL);
INSERT INTO "dispositivi" VALUES(3,2,'Telefono di Sara','telefono','0.17.0','2026-10-04T08:00:00+00:00','2026-10-04T08:00:00+00:00',NULL);
CREATE TABLE eventi (
    id TEXT PRIMARY KEY,
    tipo TEXT NOT NULL,
    dettagli TEXT NOT NULL DEFAULT '{}',
    ts_device INTEGER,
    ts_server TEXT NOT NULL,
    dispositivo_id INTEGER REFERENCES dispositivi(id)
);
INSERT INTO "eventi" VALUES('uso-tel-1004','uso_giornaliero','{"giorno": "2026-10-04", "uso_minuti": {"com.zhiliaoapp.musically": 40}, "nomi": {"com.zhiliaoapp.musically": "TikTok"}, "totale_minuti": 40}',NULL,'2026-10-04T08:00:00+00:00',1);
INSERT INTO "eventi" VALUES('uso-pc-1004','uso_giornaliero','{"giorno": "2026-10-04", "uso_minuti": {"exe:winword.exe": 30}, "nomi": {"exe:winword.exe": "Word"}, "totale_minuti": 30}',NULL,'2026-10-04T08:00:00+00:00',1);
INSERT INTO "eventi" VALUES('uso-tel-1006','uso_giornaliero','{"giorno": "2026-10-06", "uso_minuti": {"com.zhiliaoapp.musically": 15}, "nomi": {"com.zhiliaoapp.musically": "TikTok"}, "totale_minuti": 15}',NULL,'2026-10-06T09:30:00+00:00',1);
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
    annullata_genitore_id INTEGER REFERENCES genitori(id),
    confermata_ts TEXT,
    confermata_genitore_id INTEGER REFERENCES genitori(id)
);
INSERT INTO "faccende" VALUES(1,1,'Rifai il letto',NULL,'fatta','2026-10-04T09:00:00+00:00','2026-10-04T09:00:00+00:00',2,1,'2026-10-04T09:30:00+00:00',0,NULL,NULL,NULL,'2026-10-04T09:30:00+00:00',NULL,'2026-10-04T09:30:00+00:00',1);
INSERT INTO "faccende" VALUES(2,1,'Porta fuori il cane',NULL,'annullata','2026-10-04T09:00:00+00:00','2026-10-04T09:00:00+00:00',2,1,NULL,0,NULL,NULL,NULL,'2026-10-04T09:30:00+00:00',1,NULL,NULL);
INSERT INTO "faccende" VALUES(3,1,'Lava i piatti',NULL,'fatta','2026-10-04T09:00:00+00:00','2026-10-04T09:00:00+00:00',2,1,'2026-10-04T09:30:00+00:00',0,NULL,NULL,NULL,'2026-10-04T09:30:00+00:00',NULL,NULL,NULL);
INSERT INTO "faccende" VALUES(4,2,'Riordina la camera',NULL,'fatta','2026-10-04T09:00:00+00:00','2026-10-04T09:00:00+00:00',2,1,'2026-10-04T09:30:00+00:00',0,NULL,NULL,NULL,'2026-10-04T09:30:00+00:00',NULL,NULL,NULL);
INSERT INTO "faccende" VALUES(5,1,'Stendi la lavatrice','i bianchi','fatta','2026-10-06T08:00:00+00:00','2026-10-06T08:00:00+00:00',1,2,'2026-10-06T08:20:00+00:00',0,NULL,NULL,NULL,'2026-10-06T08:20:00+00:00',NULL,NULL,NULL);
INSERT INTO "faccende" VALUES(6,1,'Svuota la lavastoviglie',NULL,'da_fare','2026-10-06T09:00:00+00:00','2026-10-06T09:00:00+00:00',2,3,NULL,0,NULL,NULL,NULL,NULL,NULL,NULL,NULL);
INSERT INTO "faccende" VALUES(7,1,'Compiti di matematica','pagina 42','da_fare','2026-10-06T15:00:00+00:00','2026-10-06T09:30:00+00:00',1,3,NULL,0,NULL,NULL,NULL,NULL,NULL,NULL,NULL);
CREATE TABLE faccende_storia (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    faccenda_id INTEGER NOT NULL REFERENCES faccende(id),
    tipo TEXT NOT NULL CHECK (tipo IN ('data', 'foto', 'bocciata', 'annullata', 'modificata', 'confermata')),
    ts TEXT NOT NULL,
    genitore_id INTEGER REFERENCES genitori(id),
    nota TEXT,
    cambi TEXT
);
INSERT INTO "faccende_storia" VALUES(1,1,'data','2026-10-04T09:00:00+00:00',2,NULL,NULL);
INSERT INTO "faccende_storia" VALUES(2,2,'data','2026-10-04T09:00:00+00:00',2,NULL,NULL);
INSERT INTO "faccende_storia" VALUES(3,3,'data','2026-10-04T09:00:00+00:00',2,NULL,NULL);
INSERT INTO "faccende_storia" VALUES(4,4,'data','2026-10-04T09:00:00+00:00',2,NULL,NULL);
INSERT INTO "faccende_storia" VALUES(5,1,'foto','2026-10-04T09:30:00+00:00',NULL,NULL,NULL);
INSERT INTO "faccende_storia" VALUES(6,1,'confermata','2026-10-04T09:30:00+00:00',1,NULL,NULL);
INSERT INTO "faccende_storia" VALUES(7,3,'foto','2026-10-04T09:30:00+00:00',NULL,NULL,NULL);
INSERT INTO "faccende_storia" VALUES(8,2,'annullata','2026-10-04T09:30:00+00:00',1,NULL,NULL);
INSERT INTO "faccende_storia" VALUES(9,4,'foto','2026-10-04T09:30:00+00:00',NULL,NULL,NULL);
INSERT INTO "faccende_storia" VALUES(10,5,'data','2026-10-06T08:00:00+00:00',1,NULL,NULL);
INSERT INTO "faccende_storia" VALUES(11,5,'foto','2026-10-06T08:20:00+00:00',NULL,NULL,NULL);
INSERT INTO "faccende_storia" VALUES(12,6,'data','2026-10-06T09:00:00+00:00',2,NULL,NULL);
INSERT INTO "faccende_storia" VALUES(13,7,'data','2026-10-06T09:30:00+00:00',1,NULL,NULL);
CREATE TABLE figli (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    nome TEXT NOT NULL,
    creato_ts TEXT NOT NULL
);
INSERT INTO "figli" VALUES(1,'Luca','2026-10-04T08:00:00+00:00');
INSERT INTO "figli" VALUES(2,'Sara','2026-10-04T08:00:00+00:00');
CREATE TABLE genitori (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    nome TEXT NOT NULL,
    creato_ts TEXT NOT NULL,
    abbinato_ts TEXT,
    revocato_ts TEXT,
    notifiche_dopo_id INTEGER NOT NULL DEFAULT 0
);
INSERT INTO "genitori" VALUES(1,'Genitore','2026-10-04T08:00:00+00:00','2026-10-04T08:00:00+00:00',NULL,0);
INSERT INTO "genitori" VALUES(2,'Mamma','2026-10-04T08:00:00+00:00','2026-10-04T08:00:00+00:00',NULL,0);
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
INSERT INTO "notifiche" VALUES(1,'genitore','modifica_regola','Nuova regola limite_tempo creata','{"regola_id": 1, "azione": "creazione", "parametri": {"app_o_categoria": "com.zhiliaoapp.musically", "minuti_al_giorno": 60}}',0,'2026-10-04T08:00:00+00:00',1,1);
INSERT INTO "notifiche" VALUES(2,'genitore','sessione_da_approvare','Luca chiede di approvare la sessione «Compiti»','{"sessione_id": 1, "nome": "Compiti", "cambio": false}',1,'2026-10-04T08:00:00+00:00',1,1);
INSERT INTO "notifiche" VALUES(3,'figlio','sessione_risposta','Mamma ha approvato la sessione «Compiti»','{"sessione_id": 1, "nome": "Compiti", "esito": "approva", "cambio": false, "genitore": {"id": 2, "nome": "Mamma"}}',0,'2026-10-04T08:00:00+00:00',1,1);
INSERT INTO "notifiche" VALUES(4,'figlio','nuove_faccende','Mamma ti ha dato 3 lavori di casa','{"faccenda_ids": [1, 2, 3], "blocco_da": "2026-10-04T09:00:00+00:00", "genitore": {"id": 2, "nome": "Mamma"}}',0,'2026-10-04T09:00:00+00:00',1,NULL);
INSERT INTO "notifiche" VALUES(5,'figlio','nuove_faccende','Mamma ti ha dato un lavoro di casa: «Riordina la camera»','{"faccenda_ids": [4], "blocco_da": "2026-10-04T09:00:00+00:00", "genitore": {"id": 2, "nome": "Mamma"}}',0,'2026-10-04T09:00:00+00:00',2,NULL);
INSERT INTO "notifiche" VALUES(6,'genitore','faccenda_fatta','Luca ha fatto «Rifai il letto»','{"faccenda_id": 1, "titolo": "Rifai il letto"}',0,'2026-10-04T09:30:00+00:00',1,NULL);
INSERT INTO "notifiche" VALUES(7,'figlio','faccenda_confermata','Genitore ha confermato «Rifai il letto»','{"faccenda_id": 1, "titolo": "Rifai il letto", "genitore": {"id": 1, "nome": "Genitore"}}',0,'2026-10-04T09:30:00+00:00',1,NULL);
INSERT INTO "notifiche" VALUES(8,'genitore','faccenda_fatta','Luca ha fatto «Lava i piatti»','{"faccenda_id": 3, "titolo": "Lava i piatti"}',0,'2026-10-04T09:30:00+00:00',1,NULL);
INSERT INTO "notifiche" VALUES(9,'figlio','faccenda_annullata','Genitore ha annullato «Porta fuori il cane»','{"faccenda_id": 2, "titolo": "Porta fuori il cane", "genitore": {"id": 1, "nome": "Genitore"}}',0,'2026-10-04T09:30:00+00:00',1,NULL);
INSERT INTO "notifiche" VALUES(10,'genitore','faccenda_fatta','Sara ha fatto «Riordina la camera»','{"faccenda_id": 4, "titolo": "Riordina la camera"}',0,'2026-10-04T09:30:00+00:00',2,NULL);
INSERT INTO "notifiche" VALUES(11,'genitore','faccende_finite','Sara ha finito i lavori di casa: telefono e computer sbloccati','{"faccenda_ids": [4]}',0,'2026-10-04T09:30:00+00:00',2,NULL);
INSERT INTO "notifiche" VALUES(12,'figlio','nuove_faccende','Genitore ti ha dato un lavoro di casa: «Stendi la lavatrice»','{"faccenda_ids": [5], "blocco_da": "2026-10-06T08:00:00+00:00", "genitore": {"id": 1, "nome": "Genitore"}}',0,'2026-10-06T08:00:00+00:00',1,NULL);
INSERT INTO "notifiche" VALUES(13,'genitore','faccenda_fatta','Luca ha fatto «Stendi la lavatrice»','{"faccenda_id": 5, "titolo": "Stendi la lavatrice"}',0,'2026-10-06T08:20:00+00:00',1,NULL);
INSERT INTO "notifiche" VALUES(14,'genitore','faccende_finite','Luca ha finito i lavori di casa: telefono e computer sbloccati','{"faccenda_ids": [5]}',0,'2026-10-06T08:20:00+00:00',1,NULL);
INSERT INTO "notifiche" VALUES(15,'figlio','nuove_faccende','Mamma ti ha dato un lavoro di casa: «Svuota la lavastoviglie»','{"faccenda_ids": [6], "blocco_da": "2026-10-06T09:00:00+00:00", "genitore": {"id": 2, "nome": "Mamma"}}',0,'2026-10-06T09:00:00+00:00',1,NULL);
INSERT INTO "notifiche" VALUES(16,'figlio','nuove_faccende','Genitore ti ha dato un lavoro di casa: «Compiti di matematica»','{"faccenda_ids": [7], "blocco_da": "2026-10-06T15:00:00+00:00", "genitore": {"id": 1, "nome": "Genitore"}}',0,'2026-10-06T09:30:00+00:00',1,NULL);
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
INSERT INTO "regole" VALUES(1,'limite_tempo','{"app_o_categoria": "com.zhiliaoapp.musically", "minuti_al_giorno": 60}',1,'2026-10-04T08:00:00+00:00','2026-10-04T08:00:00+00:00',1,1);
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
INSERT INTO "sessioni" VALUES(1,1,1,'Compiti','["eu.spaggiari.classevivafamiglia", "com.google.android.apps.classroom"]','{}','approvata',NULL,NULL,2,'2026-10-04T08:00:00+00:00','2026-10-04T08:00:00+00:00',NULL,2);
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
INSERT INTO "sessioni_svolte" VALUES(1,1,1,'Compiti','["eu.spaggiari.classevivafamiglia", "com.google.android.apps.classroom"]','{}','2026-10-04T09:30:00+00:00',45,'2026-10-04T10:15:00+00:00','2026-10-04T09:50:00+00:00','terminata');
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
INSERT INTO "storico_modifiche" VALUES(1,1,'creazione',NULL,NULL,'{"app_o_categoria": "com.zhiliaoapp.musically", "minuti_al_giorno": 60}',0,'2026-10-04T08:00:00+00:00');
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
INSERT INTO "uso_giornaliero" VALUES(1,'2026-10-04','{"giorno": "2026-10-04", "uso_minuti": {"com.zhiliaoapp.musically": 40}, "nomi": {"com.zhiliaoapp.musically": "TikTok"}, "totale_minuti": 40}','uso-tel-1004','2026-10-04T08:00:00+00:00',40);
INSERT INTO "uso_giornaliero" VALUES(1,'2026-10-06','{"giorno": "2026-10-06", "uso_minuti": {"com.zhiliaoapp.musically": 15}, "nomi": {"com.zhiliaoapp.musically": "TikTok"}, "totale_minuti": 15}','uso-tel-1006','2026-10-06T09:30:00+00:00',15);
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
INSERT INTO "sqlite_sequence" VALUES('battiti',6);
INSERT INTO "sqlite_sequence" VALUES('regole',1);
INSERT INTO "sqlite_sequence" VALUES('storico_modifiche',1);
INSERT INTO "sqlite_sequence" VALUES('notifiche',16);
INSERT INTO "sqlite_sequence" VALUES('sessioni',1);
INSERT INTO "sqlite_sequence" VALUES('faccende',7);
INSERT INTO "sqlite_sequence" VALUES('faccende_storia',13);
INSERT INTO "sqlite_sequence" VALUES('sessioni_svolte',1);
COMMIT;
