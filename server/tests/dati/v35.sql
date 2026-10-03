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
INSERT INTO "battiti" VALUES(1,80,'0.12.0',NULL,NULL,'2026-09-24T08:00:00+00:00',1);
INSERT INTO "battiti" VALUES(2,80,'0.10.0',NULL,NULL,'2026-09-24T08:00:00+00:00',2);
INSERT INTO "battiti" VALUES(3,80,'0.12.0',NULL,NULL,'2026-09-24T08:00:00+00:00',3);
CREATE TABLE bonus (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    minuti INTEGER NOT NULL,
    regola_id INTEGER REFERENCES regole(id),
    motivo TEXT,
    ts_server TEXT NOT NULL,
    dispositivo_id INTEGER REFERENCES dispositivi(id)
);
INSERT INTO "bonus" VALUES(1,15,1,'video di scuola','2026-09-24T18:00:00+00:00',1);
CREATE TABLE codici_abbinamento (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    dispositivo_id INTEGER NOT NULL REFERENCES dispositivi(id),
    codice_hash TEXT NOT NULL,
    creato_ts TEXT NOT NULL,
    scade_ts TEXT NOT NULL,
    usato_ts TEXT,
    annullato_ts TEXT
);
INSERT INTO "codici_abbinamento" VALUES(1,2,'23acd4033686d190946ebb5315b433766d5499faae46111494e4c205fb846974','2026-09-24T08:00:00+00:00','2026-09-24T08:15:00+00:00','2026-09-24T08:00:00+00:00',NULL);
INSERT INTO "codici_abbinamento" VALUES(2,3,'aff96f475f85580fb49fe73077ec4fbb5e4ac880e8725af41ad8070006c9db89','2026-09-24T08:00:00+00:00','2026-09-24T08:15:00+00:00','2026-09-24T08:00:00+00:00',NULL);
CREATE TABLE credenziali (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ruolo TEXT NOT NULL CHECK (ruolo IN ('genitore', 'dispositivo')),
    dispositivo_id INTEGER REFERENCES dispositivi(id),
    token_hash TEXT NOT NULL,
    origine TEXT NOT NULL CHECK (origine IN ('ambiente', 'abbinamento')),
    creata_ts TEXT NOT NULL,
    revocata_ts TEXT
);
INSERT INTO "credenziali" VALUES(1,'genitore',NULL,'957d2f70d98e65c05fc3efdeed8d3051eb4bc282fff3809e430ccfc5c44dfa99','ambiente','2026-09-24T08:00:00+00:00',NULL);
INSERT INTO "credenziali" VALUES(2,'dispositivo',1,'df127b0dc21472236d0fa73adc905dd2f6f3837e904e361d5a5d2cbe0d18ae41','ambiente','2026-09-24T08:00:00+00:00',NULL);
INSERT INTO "credenziali" VALUES(3,'dispositivo',2,'fe0e754f01d8858da50307ebadb0bdbdbae09e5ff2aac833447b26365af6b3ff','abbinamento','2026-09-24T08:00:00+00:00',NULL);
INSERT INTO "credenziali" VALUES(4,'dispositivo',3,'57b69ae76b64539599897ada09ed413247ebf39d9a600ff99d6c9764d0cdeb5f','abbinamento','2026-09-24T08:00:00+00:00',NULL);
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
    ts_server TEXT NOT NULL
);
INSERT INTO "dichiarazioni" VALUES(1,4,'2026-09-24','successo',NULL,'Nonna','confermata_per_conto','conferma_per_conto','sentita al telefono','confermato dal genitore per conto di Nonna','2026-09-24T18:00:00+00:00','2026-09-24T18:00:00+00:00');
INSERT INTO "dichiarazioni" VALUES(2,4,'2026-09-23','fallimento',NULL,'Nonna','registrata',NULL,NULL,NULL,NULL,'2026-09-24T18:00:00+00:00');
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
INSERT INTO "dispositivi" VALUES(1,1,'Telefono','telefono','0.12.0','2026-09-24T08:00:00+00:00','2026-09-24T08:00:00+00:00',NULL);
INSERT INTO "dispositivi" VALUES(2,1,'Computer','computer','0.10.0','2026-09-24T08:00:00+00:00','2026-09-24T08:00:00+00:00',NULL);
INSERT INTO "dispositivi" VALUES(3,2,'Telefono di Sara','telefono','0.12.0','2026-09-24T08:00:00+00:00','2026-09-24T08:00:00+00:00',NULL);
CREATE TABLE eventi (
    id TEXT PRIMARY KEY,
    tipo TEXT NOT NULL,
    dettagli TEXT NOT NULL DEFAULT '{}',
    ts_device INTEGER,
    ts_server TEXT NOT NULL,
    dispositivo_id INTEGER REFERENCES dispositivi(id)
);
INSERT INTO "eventi" VALUES('uso-tel-2026-09-24','uso_giornaliero','{"giorno": "2026-09-24", "uso_minuti": {"com.zhiliaoapp.musically": 75, "eu.spaggiari.classevivafamiglia": 20}, "nomi": {"com.zhiliaoapp.musically": "TikTok", "eu.spaggiari.classevivafamiglia": "ClasseViva"}, "totale_minuti": 95}',NULL,'2026-09-24T18:00:00+00:00',1);
INSERT INTO "eventi" VALUES('sfora-tel-0924','sforamento','{"regola_id": 1, "giorno": "2026-09-24", "limite_efficace": 60, "minuti_oltre": 15}',NULL,'2026-09-24T18:00:00+00:00',1);
INSERT INTO "eventi" VALUES('uso-pc-2026-09-24','uso_giornaliero','{"giorno": "2026-09-24", "uso_minuti": {"exe:minecraft.exe": 50}, "nomi": {"exe:minecraft.exe": "Minecraft"}, "totale_minuti": 50}',NULL,'2026-09-24T18:00:00+00:00',2);
INSERT INTO "eventi" VALUES('siti-pc-0924','siti_giornalieri','{"giorno": "2026-09-24", "domini": {"youtube.com": 4}, "minuti": {"youtube.com": 30}, "totale_domini": 1, "dns_cifrato": false}',NULL,'2026-09-24T18:00:00+00:00',2);
INSERT INTO "eventi" VALUES('uso-sara-2026-09-24','uso_giornaliero','{"giorno": "2026-09-24", "uso_minuti": {"com.instagram.android": 40}, "nomi": {"com.instagram.android": "Instagram"}, "totale_minuti": 40}',NULL,'2026-09-24T18:00:00+00:00',3);
INSERT INTO "eventi" VALUES('mano-sara-0924','manomissione','{"sotto_tipo": "permesso_revocato"}',NULL,'2026-09-24T18:00:00+00:00',3);
INSERT INTO "eventi" VALUES('uso-tel-2026-09-25','uso_giornaliero','{"giorno": "2026-09-25", "uso_minuti": {"com.zhiliaoapp.musically": 55, "eu.spaggiari.classevivafamiglia": 25}, "nomi": {"com.zhiliaoapp.musically": "TikTok"}, "totale_minuti": 80, "sessioni_minuti": 30}',NULL,'2026-09-26T13:30:00+00:00',1);
INSERT INTO "eventi" VALUES('uso-pc-2026-09-25','uso_giornaliero','{"giorno": "2026-09-25", "uso_minuti": {"exe:minecraft.exe": 25}, "nomi": {"exe:minecraft.exe": "Minecraft"}, "totale_minuti": 25}',NULL,'2026-09-26T13:30:00+00:00',2);
INSERT INTO "eventi" VALUES('uso-sara-2026-09-25','uso_giornaliero','{"giorno": "2026-09-25", "uso_minuti": {"com.instagram.android": 45}, "nomi": {"com.instagram.android": "Instagram"}, "totale_minuti": 45}',NULL,'2026-09-26T13:30:00+00:00',3);
INSERT INTO "eventi" VALUES('uso-tel-2026-09-26','uso_giornaliero','{"giorno": "2026-09-26", "uso_minuti": {"com.zhiliaoapp.musically": 56, "eu.spaggiari.classevivafamiglia": 25}, "nomi": {"com.zhiliaoapp.musically": "TikTok"}, "totale_minuti": 81, "sessioni_minuti": 30}',NULL,'2026-09-26T13:30:00+00:00',1);
INSERT INTO "eventi" VALUES('uso-pc-2026-09-26','uso_giornaliero','{"giorno": "2026-09-26", "uso_minuti": {"exe:minecraft.exe": 26}, "nomi": {"exe:minecraft.exe": "Minecraft"}, "totale_minuti": 26}',NULL,'2026-09-26T13:30:00+00:00',2);
INSERT INTO "eventi" VALUES('uso-sara-2026-09-26','uso_giornaliero','{"giorno": "2026-09-26", "uso_minuti": {"com.instagram.android": 46}, "nomi": {"com.instagram.android": "Instagram"}, "totale_minuti": 46}',NULL,'2026-09-26T13:30:00+00:00',3);
INSERT INTO "eventi" VALUES('uso-tel-2026-09-27','uso_giornaliero','{"giorno": "2026-09-27", "uso_minuti": {"com.zhiliaoapp.musically": 57, "eu.spaggiari.classevivafamiglia": 25}, "nomi": {"com.zhiliaoapp.musically": "TikTok"}, "totale_minuti": 82, "sessioni_minuti": 30}',NULL,'2026-09-26T13:30:00+00:00',1);
INSERT INTO "eventi" VALUES('uso-pc-2026-09-27','uso_giornaliero','{"giorno": "2026-09-27", "uso_minuti": {"exe:minecraft.exe": 27}, "nomi": {"exe:minecraft.exe": "Minecraft"}, "totale_minuti": 27}',NULL,'2026-09-26T13:30:00+00:00',2);
INSERT INTO "eventi" VALUES('uso-sara-2026-09-27','uso_giornaliero','{"giorno": "2026-09-27", "uso_minuti": {"com.instagram.android": 47}, "nomi": {"com.instagram.android": "Instagram"}, "totale_minuti": 47}',NULL,'2026-09-26T13:30:00+00:00',3);
INSERT INTO "eventi" VALUES('uso-tel-2026-09-28','uso_giornaliero','{"giorno": "2026-09-28", "uso_minuti": {"com.zhiliaoapp.musically": 58, "eu.spaggiari.classevivafamiglia": 25}, "nomi": {"com.zhiliaoapp.musically": "TikTok"}, "totale_minuti": 83, "sessioni_minuti": 30}',NULL,'2026-09-26T13:30:00+00:00',1);
INSERT INTO "eventi" VALUES('uso-pc-2026-09-28','uso_giornaliero','{"giorno": "2026-09-28", "uso_minuti": {"exe:minecraft.exe": 28}, "nomi": {"exe:minecraft.exe": "Minecraft"}, "totale_minuti": 28}',NULL,'2026-09-26T13:30:00+00:00',2);
INSERT INTO "eventi" VALUES('uso-sara-2026-09-28','uso_giornaliero','{"giorno": "2026-09-28", "uso_minuti": {"com.instagram.android": 48}, "nomi": {"com.instagram.android": "Instagram"}, "totale_minuti": 48}',NULL,'2026-09-26T13:30:00+00:00',3);
INSERT INTO "eventi" VALUES('uso-tel-2026-09-29','uso_giornaliero','{"giorno": "2026-09-29", "uso_minuti": {"com.zhiliaoapp.musically": 59, "eu.spaggiari.classevivafamiglia": 25}, "nomi": {"com.zhiliaoapp.musically": "TikTok"}, "totale_minuti": 84, "sessioni_minuti": 30}',NULL,'2026-09-26T13:30:00+00:00',1);
INSERT INTO "eventi" VALUES('uso-pc-2026-09-29','uso_giornaliero','{"giorno": "2026-09-29", "uso_minuti": {"exe:minecraft.exe": 29}, "nomi": {"exe:minecraft.exe": "Minecraft"}, "totale_minuti": 29}',NULL,'2026-09-26T13:30:00+00:00',2);
INSERT INTO "eventi" VALUES('uso-sara-2026-09-29','uso_giornaliero','{"giorno": "2026-09-29", "uso_minuti": {"com.instagram.android": 49}, "nomi": {"com.instagram.android": "Instagram"}, "totale_minuti": 49}',NULL,'2026-09-26T13:30:00+00:00',3);
INSERT INTO "eventi" VALUES('uso-tel-2026-09-30','uso_giornaliero','{"giorno": "2026-09-30", "uso_minuti": {"com.zhiliaoapp.musically": 60, "eu.spaggiari.classevivafamiglia": 25}, "nomi": {"com.zhiliaoapp.musically": "TikTok"}, "totale_minuti": 85, "sessioni_minuti": 30}',NULL,'2026-09-26T13:30:00+00:00',1);
INSERT INTO "eventi" VALUES('uso-pc-2026-09-30','uso_giornaliero','{"giorno": "2026-09-30", "uso_minuti": {"exe:minecraft.exe": 30}, "nomi": {"exe:minecraft.exe": "Minecraft"}, "totale_minuti": 30}',NULL,'2026-09-26T13:30:00+00:00',2);
INSERT INTO "eventi" VALUES('uso-sara-2026-09-30','uso_giornaliero','{"giorno": "2026-09-30", "uso_minuti": {"com.instagram.android": 50}, "nomi": {"com.instagram.android": "Instagram"}, "totale_minuti": 50}',NULL,'2026-09-26T13:30:00+00:00',3);
INSERT INTO "eventi" VALUES('sfora-sara-0929','sforamento','{"regola_id": 6, "giorno": "2026-09-29", "limite_efficace": 45, "minuti_oltre": 5}',NULL,'2026-09-26T13:30:00+00:00',3);
CREATE TABLE figli (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    nome TEXT NOT NULL,
    creato_ts TEXT NOT NULL
);
INSERT INTO "figli" VALUES(1,'Luca','2026-09-24T08:00:00+00:00');
INSERT INTO "figli" VALUES(2,'Sara','2026-09-24T08:00:00+00:00');
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
INSERT INTO "notifiche" VALUES(1,'genitore','modifica_regola','Nuova regola limite_tempo creata','{"regola_id": 1, "azione": "creazione", "parametri": {"app_o_categoria": "com.zhiliaoapp.musically", "minuti_al_giorno": 60}}',1,'2026-09-24T08:00:00+00:00',1,1);
INSERT INTO "notifiche" VALUES(2,'genitore','modifica_regola','Nuova regola limite_tempo creata','{"regola_id": 2, "azione": "creazione", "parametri": {"app_o_categoria": "totale", "minuti_al_giorno": 180}}',1,'2026-09-24T08:00:00+00:00',1,1);
INSERT INTO "notifiche" VALUES(3,'genitore','modifica_regola','Nuova regola fascia_oraria creata','{"regola_id": 3, "azione": "creazione", "parametri": {"dalle": "22:00", "alle": "07:00", "giorni": ["lun", "mar", "mer", "gio", "ven", "sab", "dom"]}}',1,'2026-09-24T08:00:00+00:00',1,1);
INSERT INTO "notifiche" VALUES(4,'genitore','modifica_regola','Nuova regola vita_reale creata','{"regola_id": 4, "azione": "creazione", "parametri": {"descrizione": "Leggere 20 minuti", "arbitro_nome": "Nonna", "frequenza": "ogni giorno"}}',1,'2026-09-24T08:00:00+00:00',1,NULL);
INSERT INTO "notifiche" VALUES(5,'genitore','modifica_regola','Nuova regola limite_tempo creata','{"regola_id": 5, "azione": "creazione", "parametri": {"app_o_categoria": "exe:minecraft.exe", "minuti_al_giorno": 60}}',1,'2026-09-24T08:00:00+00:00',1,2);
INSERT INTO "notifiche" VALUES(6,'genitore','modifica_regola','Nuova regola limite_tempo creata','{"regola_id": 6, "azione": "creazione", "parametri": {"app_o_categoria": "com.instagram.android", "minuti_al_giorno": 45}}',1,'2026-09-24T08:00:00+00:00',2,3);
INSERT INTO "notifiche" VALUES(7,'genitore','sforamento','Evento sforamento registrato','{"evento_id": "sfora-tel-0924", "dettagli": {"regola_id": 1, "giorno": "2026-09-24", "limite_efficace": 60, "minuti_oltre": 15}}',0,'2026-09-24T18:00:00+00:00',1,1);
INSERT INTO "notifiche" VALUES(8,'genitore','manomissione','Evento manomissione registrato','{"evento_id": "mano-sara-0924", "dettagli": {"sotto_tipo": "permesso_revocato"}}',0,'2026-09-24T18:00:00+00:00',2,3);
INSERT INTO "notifiche" VALUES(9,'genitore','bonus','Bonus di 15 minuti auto-concesso','{"minuti": 15, "regola_id": 1, "motivo": "video di scuola", "residuo_giorno": 15, "residuo_settimana": 75}',0,'2026-09-24T18:00:00+00:00',1,1);
INSERT INTO "notifiche" VALUES(10,'genitore','dichiarazione','Dichiarazione del figlio: successo (2026-09-24)','{"dichiarazione_id": 1, "regola_id": 4, "esito": "successo", "giorno": "2026-09-24"}',0,'2026-09-24T18:00:00+00:00',1,NULL);
INSERT INTO "notifiche" VALUES(11,'figlio','verdetto','Esito della tua dichiarazione: confermato dal genitore per conto di Nonna','{"dichiarazione_id": 1, "regola_id": 4, "verdetto": "conferma_per_conto"}',0,'2026-09-24T18:00:00+00:00',1,NULL);
INSERT INTO "notifiche" VALUES(12,'genitore','dichiarazione','Dichiarazione del figlio: fallimento (2026-09-23)','{"dichiarazione_id": 2, "regola_id": 4, "esito": "fallimento", "giorno": "2026-09-23"}',0,'2026-09-24T18:00:00+00:00',1,NULL);
INSERT INTO "notifiche" VALUES(13,'figlio','segno','Ho visto la settimana. Bene così.','{}',0,'2026-09-24T18:00:00+00:00',1,NULL);
INSERT INTO "notifiche" VALUES(14,'figlio','nuova_proposta','Nuova proposta del genitore: −15 min al giorno rispetto ad ora','{"proposta_id": 1, "regola_id": 1, "confronto": "\u221215 min al giorno rispetto ad ora", "direzione": "stringe", "autore": "genitore"}',0,'2026-09-25T10:00:00+00:00',1,1);
INSERT INTO "notifiche" VALUES(15,'genitore','modifica_regola','Regola 1 (limite_tempo) modificata (stringe)','{"regola_id": 1, "azione": "modifica", "direzione": "stringe", "concordata": true, "prima": {"app_o_categoria": "com.zhiliaoapp.musically", "minuti_al_giorno": 60}, "dopo": {"app_o_categoria": "com.zhiliaoapp.musically", "minuti_al_giorno": 45}}',0,'2026-09-25T10:00:00+00:00',1,1);
INSERT INTO "notifiche" VALUES(16,'genitore','proposta_risposta','Il figlio ha risposto alla proposta: accetta','{"proposta_id": 1, "regola_id": 1, "esito": "accetta", "autore": "genitore"}',0,'2026-09-25T10:00:00+00:00',1,1);
INSERT INTO "notifiche" VALUES(17,'figlio','nuova_proposta','Nuova proposta del genitore: −30 min al giorno rispetto ad ora','{"proposta_id": 2, "regola_id": 2, "confronto": "\u221230 min al giorno rispetto ad ora", "direzione": "stringe", "autore": "genitore"}',0,'2026-09-25T10:00:00+00:00',1,1);
INSERT INTO "notifiche" VALUES(18,'genitore','proposta_risposta','Il figlio ha risposto alla proposta: rifiuta','{"proposta_id": 2, "regola_id": 2, "esito": "rifiuta", "autore": "genitore"}',0,'2026-09-25T10:00:00+00:00',1,1);
INSERT INTO "notifiche" VALUES(19,'genitore','nuova_proposta','Luca propone: +30 min al giorno rispetto ad ora','{"proposta_id": 3, "regola_id": 5, "confronto": "+30 min al giorno rispetto ad ora", "direzione": "allenta", "autore": "figlio"}',0,'2026-09-25T10:00:00+00:00',1,2);
INSERT INTO "notifiche" VALUES(20,'figlio','proposta_risposta','Il genitore ha accettato la tua proposta: +30 min al giorno rispetto ad ora','{"proposta_id": 3, "regola_id": 5, "esito": "accetta", "autore": "figlio"}',0,'2026-09-25T10:00:00+00:00',1,NULL);
INSERT INTO "notifiche" VALUES(21,'genitore','nuova_proposta','Luca propone: orario da 22:00-07:00 a 23:00-07:00','{"proposta_id": 4, "regola_id": 3, "confronto": "orario da 22:00-07:00 a 23:00-07:00", "direzione": "allenta", "autore": "figlio"}',0,'2026-09-25T10:00:00+00:00',1,1);
INSERT INTO "notifiche" VALUES(22,'figlio','proposta_risposta','Il genitore ha rifiutato la tua proposta: orario da 22:00-07:00 a 23:00-07:00','{"proposta_id": 4, "regola_id": 3, "esito": "rifiuta", "autore": "figlio"}',0,'2026-09-25T10:00:00+00:00',1,NULL);
INSERT INTO "notifiche" VALUES(23,'figlio','nuova_proposta','Nuova proposta del genitore: descrizione: "Leggere 20 minuti" -> "Leggere 30 minuti"','{"proposta_id": 5, "regola_id": 4, "confronto": "descrizione: \"Leggere 20 minuti\" -> \"Leggere 30 minuti\"", "direzione": "allenta", "autore": "genitore"}',0,'2026-09-25T10:00:00+00:00',1,NULL);
INSERT INTO "notifiche" VALUES(24,'figlio','proposta_ritirata','Il genitore ha ritirato la sua proposta','{"proposta_id": 5, "regola_id": 4, "autore": "genitore"}',0,'2026-09-25T10:00:00+00:00',1,NULL);
INSERT INTO "notifiche" VALUES(25,'genitore','nuova_proposta','Luca propone: +20 min al giorno rispetto ad ora','{"proposta_id": 6, "regola_id": 2, "confronto": "+20 min al giorno rispetto ad ora", "direzione": "allenta", "autore": "figlio"}',0,'2026-09-25T10:00:00+00:00',1,1);
INSERT INTO "notifiche" VALUES(26,'genitore','sessione_da_approvare','Luca chiede di approvare la sessione «Studio»','{"sessione_id": 1, "nome": "Studio", "cambio": false}',1,'2026-09-26T10:00:00+00:00',1,1);
INSERT INTO "notifiche" VALUES(27,'figlio','sessione_risposta','Il genitore ha approvato la sessione «Studio»','{"sessione_id": 1, "nome": "Studio", "esito": "approva", "cambio": false}',0,'2026-09-26T10:00:00+00:00',1,1);
INSERT INTO "notifiche" VALUES(28,'genitore','sessione_da_approvare','Luca chiede di approvare la sessione «Giochi»','{"sessione_id": 2, "nome": "Giochi", "cambio": false}',1,'2026-09-26T10:00:00+00:00',1,1);
INSERT INTO "notifiche" VALUES(29,'figlio','sessione_risposta','Il genitore non ha approvato la sessione «Giochi»','{"sessione_id": 2, "nome": "Giochi", "esito": "rifiuta", "cambio": false}',0,'2026-09-26T10:00:00+00:00',1,1);
INSERT INTO "notifiche" VALUES(30,'genitore','sessione_da_approvare','Luca chiede di approvare la sessione «Musica»','{"sessione_id": 3, "nome": "Musica", "cambio": false}',0,'2026-09-26T10:00:00+00:00',1,1);
INSERT INTO "notifiche" VALUES(31,'genitore','sessione_da_approvare','Luca chiede di cambiare la sessione «Studio»','{"sessione_id": 1, "nome": "Studio", "cambio": true}',0,'2026-09-26T13:30:00+00:00',1,1);
INSERT INTO "notifiche" VALUES(32,'figlio','segno','Ho visto la settimana. Bene così.','{}',0,'2026-09-26T13:30:00+00:00',2,NULL);
INSERT INTO "notifiche" VALUES(33,'genitore','sforamento','Evento sforamento registrato','{"evento_id": "sfora-sara-0929", "dettagli": {"regola_id": 6, "giorno": "2026-09-29", "limite_efficace": 45, "minuti_oltre": 5}}',0,'2026-09-26T13:30:00+00:00',2,3);
CREATE TABLE notifiche_lette (
    notifica_id INTEGER NOT NULL REFERENCES notifiche(id),
    dispositivo_id INTEGER NOT NULL REFERENCES dispositivi(id),
    ts_server TEXT NOT NULL,
    PRIMARY KEY (notifica_id, dispositivo_id)
);
INSERT INTO "notifiche_lette" VALUES(11,1,'2026-10-01T09:00:00+00:00');
INSERT INTO "notifiche_lette" VALUES(13,1,'2026-10-01T09:00:00+00:00');
INSERT INTO "notifiche_lette" VALUES(13,2,'2026-10-01T09:00:00+00:00');
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
    autore TEXT NOT NULL DEFAULT 'genitore' CHECK (autore IN ('genitore', 'figlio'))
);
INSERT INTO "proposte" VALUES(1,1,'{"app_o_categoria": "com.zhiliaoapp.musically", "minuti_al_giorno": 45}','meno TikTok','−15 min al giorno rispetto ad ora','stringe','accettata',1,'accetta','va bene','2026-09-25T10:00:00+00:00','2026-09-25T10:00:00+00:00','genitore');
INSERT INTO "proposte" VALUES(2,2,'{"app_o_categoria": "totale", "minuti_al_giorno": 150}',NULL,'−30 min al giorno rispetto ad ora','stringe','rifiutata',0,'rifiuta','troppo poco','2026-09-25T10:00:00+00:00','2026-09-25T10:00:00+00:00','genitore');
INSERT INTO "proposte" VALUES(3,5,'{"app_o_categoria": "exe:minecraft.exe", "minuti_al_giorno": 90}','nel weekend','+30 min al giorno rispetto ad ora','allenta','accettata',1,'accetta','solo sabato','2026-09-25T10:00:00+00:00','2026-09-25T10:00:00+00:00','figlio');
INSERT INTO "proposte" VALUES(4,3,'{"dalle": "23:00", "alle": "07:00", "giorni": ["lun", "mar", "mer", "gio", "ven", "sab", "dom"]}',NULL,'orario da 22:00-07:00 a 23:00-07:00','allenta','rifiutata',0,'rifiuta','no','2026-09-25T10:00:00+00:00','2026-09-25T10:00:00+00:00','figlio');
INSERT INTO "proposte" VALUES(5,4,'{"descrizione": "Leggere 30 minuti", "arbitro_nome": "Nonna", "frequenza": "ogni giorno"}',NULL,'descrizione: "Leggere 20 minuti" -> "Leggere 30 minuti"','allenta','ritirata',0,NULL,NULL,NULL,'2026-09-25T10:00:00+00:00','genitore');
INSERT INTO "proposte" VALUES(6,2,'{"app_o_categoria": "totale", "minuti_al_giorno": 200}','per i compiti','+20 min al giorno rispetto ad ora','allenta','pendente',0,NULL,NULL,NULL,'2026-09-25T10:00:00+00:00','figlio');
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
INSERT INTO "regole" VALUES(1,'limite_tempo','{"app_o_categoria": "com.zhiliaoapp.musically", "minuti_al_giorno": 45}',1,'2026-09-24T08:00:00+00:00','2026-09-25T10:00:00+00:00',1,1);
INSERT INTO "regole" VALUES(2,'limite_tempo','{"app_o_categoria": "totale", "minuti_al_giorno": 180}',1,'2026-09-24T08:00:00+00:00','2026-09-24T08:00:00+00:00',1,1);
INSERT INTO "regole" VALUES(3,'fascia_oraria','{"dalle": "22:00", "alle": "07:00", "giorni": ["lun", "mar", "mer", "gio", "ven", "sab", "dom"]}',1,'2026-09-24T08:00:00+00:00','2026-09-24T08:00:00+00:00',1,1);
INSERT INTO "regole" VALUES(4,'vita_reale','{"descrizione": "Leggere 20 minuti", "arbitro_nome": "Nonna", "frequenza": "ogni giorno"}',1,'2026-09-24T08:00:00+00:00','2026-09-24T08:00:00+00:00',1,NULL);
INSERT INTO "regole" VALUES(5,'limite_tempo','{"app_o_categoria": "exe:minecraft.exe", "minuti_al_giorno": 90}',1,'2026-09-24T08:00:00+00:00','2026-09-25T10:00:00+00:00',1,2);
INSERT INTO "regole" VALUES(6,'limite_tempo','{"app_o_categoria": "com.instagram.android", "minuti_al_giorno": 45}',1,'2026-09-24T08:00:00+00:00','2026-09-24T08:00:00+00:00',2,3);
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
    eliminata_ts TEXT
);
INSERT INTO "sessioni" VALUES(1,1,1,'Studio','["eu.spaggiari.classevivafamiglia", "com.google.android.apps.classroom"]','{"eu.spaggiari.classevivafamiglia": "ClasseViva", "com.google.android.apps.classroom": "Classroom"}','approvata','{"nome": "Studio", "app": ["eu.spaggiari.classevivafamiglia", "com.google.android.apps.classroom", "com.duolingo"], "nomi": {"eu.spaggiari.classevivafamiglia": "ClasseViva", "com.google.android.apps.classroom": "Classroom"}, "richiesta_ts": "2026-09-26T13:30:00+00:00"}',NULL,3,'2026-09-26T10:00:00+00:00','2026-09-26T10:00:00+00:00',NULL);
INSERT INTO "sessioni" VALUES(2,1,1,'Giochi','["com.supercell.clashroyale"]','{"com.supercell.clashroyale": "Clash Royale"}','rifiutata',NULL,'non e'' studio',2,'2026-09-26T10:00:00+00:00',NULL,NULL);
INSERT INTO "sessioni" VALUES(3,1,1,'Musica','["com.spotify.music"]','{"com.spotify.music": "Spotify"}','in_attesa',NULL,NULL,1,'2026-09-26T10:00:00+00:00',NULL,NULL);
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
INSERT INTO "sessioni_svolte" VALUES(1,1,1,'Studio','["eu.spaggiari.classevivafamiglia", "com.google.android.apps.classroom"]','{"eu.spaggiari.classevivafamiglia": "ClasseViva", "com.google.android.apps.classroom": "Classroom"}','2026-09-26T10:00:00+00:00',60,'2026-09-26T11:00:00+00:00','2026-09-26T10:30:00+00:00','terminata');
INSERT INTO "sessioni_svolte" VALUES(2,1,1,'Studio','["eu.spaggiari.classevivafamiglia", "com.google.android.apps.classroom"]','{"eu.spaggiari.classevivafamiglia": "ClasseViva", "com.google.android.apps.classroom": "Classroom"}','2026-09-26T12:30:00+00:00',30,'2026-09-26T13:00:00+00:00',NULL,NULL);
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
INSERT INTO "siti_giornalieri" VALUES(2,'2026-09-24','{"giorno": "2026-09-24", "domini": {"youtube.com": 4}, "minuti": {"youtube.com": 30}, "totale_domini": 1, "dns_cifrato": false}','siti-pc-0924','2026-09-24T18:00:00+00:00',1,4,0);
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
INSERT INTO "storico_modifiche" VALUES(1,1,'creazione',NULL,NULL,'{"app_o_categoria": "com.zhiliaoapp.musically", "minuti_al_giorno": 60}',0,'2026-09-24T08:00:00+00:00');
INSERT INTO "storico_modifiche" VALUES(2,2,'creazione',NULL,NULL,'{"app_o_categoria": "totale", "minuti_al_giorno": 180}',0,'2026-09-24T08:00:00+00:00');
INSERT INTO "storico_modifiche" VALUES(3,3,'creazione',NULL,NULL,'{"dalle": "22:00", "alle": "07:00", "giorni": ["lun", "mar", "mer", "gio", "ven", "sab", "dom"]}',0,'2026-09-24T08:00:00+00:00');
INSERT INTO "storico_modifiche" VALUES(4,4,'creazione',NULL,NULL,'{"descrizione": "Leggere 20 minuti", "arbitro_nome": "Nonna", "frequenza": "ogni giorno"}',0,'2026-09-24T08:00:00+00:00');
INSERT INTO "storico_modifiche" VALUES(5,5,'creazione',NULL,NULL,'{"app_o_categoria": "exe:minecraft.exe", "minuti_al_giorno": 60}',0,'2026-09-24T08:00:00+00:00');
INSERT INTO "storico_modifiche" VALUES(6,6,'creazione',NULL,NULL,'{"app_o_categoria": "com.instagram.android", "minuti_al_giorno": 45}',0,'2026-09-24T08:00:00+00:00');
INSERT INTO "storico_modifiche" VALUES(7,1,'modifica','stringe','{"app_o_categoria": "com.zhiliaoapp.musically", "minuti_al_giorno": 60}','{"app_o_categoria": "com.zhiliaoapp.musically", "minuti_al_giorno": 45}',1,'2026-09-25T10:00:00+00:00');
INSERT INTO "storico_modifiche" VALUES(8,5,'modifica','allenta','{"app_o_categoria": "exe:minecraft.exe", "minuti_al_giorno": 60}','{"app_o_categoria": "exe:minecraft.exe", "minuti_al_giorno": 90}',1,'2026-09-25T10:00:00+00:00');
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
INSERT INTO "uso_giornaliero" VALUES(1,'2026-09-24','{"giorno": "2026-09-24", "uso_minuti": {"com.zhiliaoapp.musically": 75, "eu.spaggiari.classevivafamiglia": 20}, "nomi": {"com.zhiliaoapp.musically": "TikTok", "eu.spaggiari.classevivafamiglia": "ClasseViva"}, "totale_minuti": 95}','uso-tel-2026-09-24','2026-09-24T18:00:00+00:00',95);
INSERT INTO "uso_giornaliero" VALUES(2,'2026-09-24','{"giorno": "2026-09-24", "uso_minuti": {"exe:minecraft.exe": 50}, "nomi": {"exe:minecraft.exe": "Minecraft"}, "totale_minuti": 50}','uso-pc-2026-09-24','2026-09-24T18:00:00+00:00',50);
INSERT INTO "uso_giornaliero" VALUES(3,'2026-09-24','{"giorno": "2026-09-24", "uso_minuti": {"com.instagram.android": 40}, "nomi": {"com.instagram.android": "Instagram"}, "totale_minuti": 40}','uso-sara-2026-09-24','2026-09-24T18:00:00+00:00',40);
INSERT INTO "uso_giornaliero" VALUES(1,'2026-09-25','{"giorno": "2026-09-25", "uso_minuti": {"com.zhiliaoapp.musically": 55, "eu.spaggiari.classevivafamiglia": 25}, "nomi": {"com.zhiliaoapp.musically": "TikTok"}, "totale_minuti": 80, "sessioni_minuti": 30}','uso-tel-2026-09-25','2026-09-26T13:30:00+00:00',80);
INSERT INTO "uso_giornaliero" VALUES(2,'2026-09-25','{"giorno": "2026-09-25", "uso_minuti": {"exe:minecraft.exe": 25}, "nomi": {"exe:minecraft.exe": "Minecraft"}, "totale_minuti": 25}','uso-pc-2026-09-25','2026-09-26T13:30:00+00:00',25);
INSERT INTO "uso_giornaliero" VALUES(3,'2026-09-25','{"giorno": "2026-09-25", "uso_minuti": {"com.instagram.android": 45}, "nomi": {"com.instagram.android": "Instagram"}, "totale_minuti": 45}','uso-sara-2026-09-25','2026-09-26T13:30:00+00:00',45);
INSERT INTO "uso_giornaliero" VALUES(1,'2026-09-26','{"giorno": "2026-09-26", "uso_minuti": {"com.zhiliaoapp.musically": 56, "eu.spaggiari.classevivafamiglia": 25}, "nomi": {"com.zhiliaoapp.musically": "TikTok"}, "totale_minuti": 81, "sessioni_minuti": 30}','uso-tel-2026-09-26','2026-09-26T13:30:00+00:00',81);
INSERT INTO "uso_giornaliero" VALUES(2,'2026-09-26','{"giorno": "2026-09-26", "uso_minuti": {"exe:minecraft.exe": 26}, "nomi": {"exe:minecraft.exe": "Minecraft"}, "totale_minuti": 26}','uso-pc-2026-09-26','2026-09-26T13:30:00+00:00',26);
INSERT INTO "uso_giornaliero" VALUES(3,'2026-09-26','{"giorno": "2026-09-26", "uso_minuti": {"com.instagram.android": 46}, "nomi": {"com.instagram.android": "Instagram"}, "totale_minuti": 46}','uso-sara-2026-09-26','2026-09-26T13:30:00+00:00',46);
INSERT INTO "uso_giornaliero" VALUES(1,'2026-09-27','{"giorno": "2026-09-27", "uso_minuti": {"com.zhiliaoapp.musically": 57, "eu.spaggiari.classevivafamiglia": 25}, "nomi": {"com.zhiliaoapp.musically": "TikTok"}, "totale_minuti": 82, "sessioni_minuti": 30}','uso-tel-2026-09-27','2026-09-26T13:30:00+00:00',82);
INSERT INTO "uso_giornaliero" VALUES(2,'2026-09-27','{"giorno": "2026-09-27", "uso_minuti": {"exe:minecraft.exe": 27}, "nomi": {"exe:minecraft.exe": "Minecraft"}, "totale_minuti": 27}','uso-pc-2026-09-27','2026-09-26T13:30:00+00:00',27);
INSERT INTO "uso_giornaliero" VALUES(3,'2026-09-27','{"giorno": "2026-09-27", "uso_minuti": {"com.instagram.android": 47}, "nomi": {"com.instagram.android": "Instagram"}, "totale_minuti": 47}','uso-sara-2026-09-27','2026-09-26T13:30:00+00:00',47);
INSERT INTO "uso_giornaliero" VALUES(1,'2026-09-28','{"giorno": "2026-09-28", "uso_minuti": {"com.zhiliaoapp.musically": 58, "eu.spaggiari.classevivafamiglia": 25}, "nomi": {"com.zhiliaoapp.musically": "TikTok"}, "totale_minuti": 83, "sessioni_minuti": 30}','uso-tel-2026-09-28','2026-09-26T13:30:00+00:00',83);
INSERT INTO "uso_giornaliero" VALUES(2,'2026-09-28','{"giorno": "2026-09-28", "uso_minuti": {"exe:minecraft.exe": 28}, "nomi": {"exe:minecraft.exe": "Minecraft"}, "totale_minuti": 28}','uso-pc-2026-09-28','2026-09-26T13:30:00+00:00',28);
INSERT INTO "uso_giornaliero" VALUES(3,'2026-09-28','{"giorno": "2026-09-28", "uso_minuti": {"com.instagram.android": 48}, "nomi": {"com.instagram.android": "Instagram"}, "totale_minuti": 48}','uso-sara-2026-09-28','2026-09-26T13:30:00+00:00',48);
INSERT INTO "uso_giornaliero" VALUES(1,'2026-09-29','{"giorno": "2026-09-29", "uso_minuti": {"com.zhiliaoapp.musically": 59, "eu.spaggiari.classevivafamiglia": 25}, "nomi": {"com.zhiliaoapp.musically": "TikTok"}, "totale_minuti": 84, "sessioni_minuti": 30}','uso-tel-2026-09-29','2026-09-26T13:30:00+00:00',84);
INSERT INTO "uso_giornaliero" VALUES(2,'2026-09-29','{"giorno": "2026-09-29", "uso_minuti": {"exe:minecraft.exe": 29}, "nomi": {"exe:minecraft.exe": "Minecraft"}, "totale_minuti": 29}','uso-pc-2026-09-29','2026-09-26T13:30:00+00:00',29);
INSERT INTO "uso_giornaliero" VALUES(3,'2026-09-29','{"giorno": "2026-09-29", "uso_minuti": {"com.instagram.android": 49}, "nomi": {"com.instagram.android": "Instagram"}, "totale_minuti": 49}','uso-sara-2026-09-29','2026-09-26T13:30:00+00:00',49);
INSERT INTO "uso_giornaliero" VALUES(1,'2026-09-30','{"giorno": "2026-09-30", "uso_minuti": {"com.zhiliaoapp.musically": 60, "eu.spaggiari.classevivafamiglia": 25}, "nomi": {"com.zhiliaoapp.musically": "TikTok"}, "totale_minuti": 85, "sessioni_minuti": 30}','uso-tel-2026-09-30','2026-09-26T13:30:00+00:00',85);
INSERT INTO "uso_giornaliero" VALUES(2,'2026-09-30','{"giorno": "2026-09-30", "uso_minuti": {"exe:minecraft.exe": 30}, "nomi": {"exe:minecraft.exe": "Minecraft"}, "totale_minuti": 30}','uso-pc-2026-09-30','2026-09-26T13:30:00+00:00',30);
INSERT INTO "uso_giornaliero" VALUES(3,'2026-09-30','{"giorno": "2026-09-30", "uso_minuti": {"com.instagram.android": 50}, "nomi": {"com.instagram.android": "Instagram"}, "totale_minuti": 50}','uso-sara-2026-09-30','2026-09-26T13:30:00+00:00',50);
CREATE UNIQUE INDEX idx_dichiarazioni_regola_giorno
    ON dichiarazioni (regola_id, giorno);
CREATE INDEX idx_sessioni_dispositivo ON sessioni (dispositivo_id);
CREATE INDEX idx_sessioni_figlio ON sessioni (figlio_id);
CREATE INDEX idx_sessioni_svolte_dispositivo
    ON sessioni_svolte (dispositivo_id, inizio_ts);
CREATE UNIQUE INDEX idx_sessioni_svolte_una_aperta
    ON sessioni_svolte (dispositivo_id) WHERE fine_ts IS NULL;
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
INSERT INTO "sqlite_sequence" VALUES('credenziali',4);
INSERT INTO "sqlite_sequence" VALUES('codici_abbinamento',2);
INSERT INTO "sqlite_sequence" VALUES('battiti',3);
INSERT INTO "sqlite_sequence" VALUES('regole',6);
INSERT INTO "sqlite_sequence" VALUES('storico_modifiche',8);
INSERT INTO "sqlite_sequence" VALUES('notifiche',33);
INSERT INTO "sqlite_sequence" VALUES('bonus',1);
INSERT INTO "sqlite_sequence" VALUES('dichiarazioni',2);
INSERT INTO "sqlite_sequence" VALUES('proposte',6);
INSERT INTO "sqlite_sequence" VALUES('sessioni',3);
INSERT INTO "sqlite_sequence" VALUES('sessioni_svolte',2);
COMMIT;
