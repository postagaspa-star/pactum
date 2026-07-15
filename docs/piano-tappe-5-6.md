# Pactum — Piano di dettaglio tappe 5 e 6

**Scritto il 15/07/2026.** Mandato di Andrea: completare tutte le tappe prima del collaudo su telefono reale. Questo file è la specifica per i costruttori; le forme esatte dei payload vanno PRIMA in contratto-api.md, poi nel codice.

## Tappa 5 — Il patto completo

### Decisioni di disegno (prese il 15/07, dichiarate ad Andrea)
- **Gli sforamenti si rilevano SUL TELEFONO** (app figlio): valutazione locale uso-vs-regole, evento `sforamento` con `regola_id` al registro + notifica gentile al figlio ("hai superato il limite che ti sei dato"). MAI blocchi. Il server resta fonte di verità del patto; la rilevazione è del testimone locale. Dedup: un solo sforamento per regola per giorno (fascia: per regola per occorrenza-giorno).
- **Il bonus si aggancia a una regola specifica** di tipo `limite_tempo` (`regola_id` nel POST /api/bonus): "mi do +15 su TikTok". Effetto in valutazione: limite efficace del giorno = `minuti_al_giorno` + bonus concessi oggi su quella regola. Le fasce orarie non si allungano col bonus in v1. Se non esistono regole limite_tempo, il bonus non è concedibile (si allunga un limite, non il vuoto).
- **Dichiarazioni vita reale**: il figlio dichiara `successo` o `fallimento` per ogni scadenza della regola. Fallimento = creduto sulla parola, va solo a registro. Successo = resta "in attesa di conferma" finché il genitore conferma / conferma **per conto dell'arbitro** / ribalta. Tutto nello storico con chi ha garantito cosa.
- **Proposte del genitore**: su qualsiasi regola (modifica parametri o eliminazione). Il server calcola e include il **confronto col valore attuale** (es. `"-30 min rispetto ad ora"`) che compare nella notifica al figlio. Il figlio accetta o rifiuta con motivazione opzionale; tutto a registro. La proposta accettata è il `proposta_id` monouso già implementato (parametri esatti, lock bypassato).
- **Sync regole nell'app figlio**: le regole si creano/modificano dall'app ma il server è la fonte di verità (lock 4gg applicato lato server; l'app mostra il conto alla rovescia dal 409). Copia locale per la valutazione offline, risincronizzata a ogni giro del worker.

### Lavori server (contratto prima, poi codice + test)
1. **Proposte**: POST /api/proposte (genitore: regola_id, parametri_proposti o {"azione":"elimina"}, motivazione) → il server valida i parametri col tipo di regola, calcola `confronto` testuale, notifica il figlio. GET /api/proposte (entrambi, stati). POST /api/proposte/{id}/risposta (figlio: accetta|rifiuta, motivazione?) → notifica genitore; accettata resta spendibile come proposta_id.
2. **Dichiarazioni**: POST /api/dichiarazioni (figlio: regola_id vita_reale, esito successo|fallimento, nota?) → fallimento a registro; successo `in_attesa`. GET /api/dichiarazioni (entrambi). POST /api/dichiarazioni/{id}/verdetto (genitore: conferma|conferma_per_conto_di|ribalta, nota?) → registro + notifica figlio.
3. **Bonus con regola**: `regola_id` obbligatorio nel POST /api/bonus (regola limite_tempo attiva), incluso in finestra/bonus_giornalieri.
4. **GET /api/patto** (figlio): stato completo per l'app — regole, bonus residui, proposte pendenti, dichiarazioni in attesa — un endpoint solo per il sync del worker.
5. Notifiche arricchite con `tipo` macchina-leggibile (nuova_proposta, proposta_risposta, sforamento, bonus, manomissione, dichiarazione, verdetto) per le notifiche locali delle due app.

### Lavori app figlio
1. **UI regole**: lista + crea/modifica/elimina per i 3 tipi; countdown del lock sugli allentamenti (dal 409 del server); badge "concordata" quando nasce da proposta; vincolo "almeno una regola" visibile.
2. **Valutatore**: nel giro del worker (e nel loop FGS) confronto uso-di-oggi vs regole limite_tempo (con bonus) e fascia_oraria → evento sforamento (dedup) + notifica locale gentile al figlio.
3. **UI bonus**: +5/15/30 su una regola limite_tempo, residui giorno/settimana visibili, motivo opzionale.
4. **UI proposte**: notifica → schermata con CONFRONTO in evidenza → accetta/rifiuta.
5. **UI dichiarazioni**: per le regole vita_reale, dichiara successo/fallimento; stato conferme visibile.
6. **Onboarding patto**: dopo i permessi, creazione della prima regola obbligatoria.

### Lavori app genitore
1. **UI proposte**: crea proposta da una regola esistente (il confronto lo calcola il server, mostrato in anteprima), stati (pendente/accettata/rifiutata).
2. **UI verdetti**: dichiarazioni in attesa → conferma / conferma per conto di [arbitro] / ribalta, con nota.
3. Notifiche locali per tipo (già VedettaWorker) arricchite col `tipo`.

## Tappa 6 — La corazza

1. **Manomissioni complete (app figlio)**: revoca del permesso usage access rilevata al giro del worker → evento `manomissione {sotto_tipo: permesso_revocato}`; notifica disattivata rilevata analogamente. (Force-stop/disinstallazione restano al rilevamento silenzio del server, come da architettura.)
2. **Chiave di firma release**: keystore generato con keytool (JBR), salvato in `C:\Users\andre\pactum-keys\` (FUORI dal repo e da OneDrive-Desktop del progetto), con README che spiega: la chiave è per sempre, va copiata in un posto sicuro (la passphrase sta nel README accanto — Andrea deve metterla al sicuro). `signingConfigs.release` nei due build.gradle legge da `pactum-keys/keystore.properties`.
3. **Versioni e auto-aggiornamento**: GET /api/versione (no auth: {figlio: {versionCode, url}, genitore: {...}}); il server serve gli APK da una cartella `apk/`; le app controllano al giro del worker, scaricano e lanciano PackageInstaller (REQUEST_INSTALL_PACKAGES) quando c'è una versione nuova. Primo aggiornamento col dialogo di sistema, successivi silenziosi dove Android lo consente.
4. **Pagina di download**: `GET /scarica` servita dal postino — pagina statica semplice in italiano con i due APK, istruzioni di installazione (inclusi Play Protect e "impostazioni con limitazioni") e QR code testuale dell'URL.
5. **Build release firmate** di entrambe le app + `assembleRelease` verde.

## Fuori da queste tappe (restano dopo, con Andrea)
- Trasloco del server sul NAS (Docker + Cloudflare Tunnel) — serve accesso di Andrea.
- Collaudo su telefono reale (percorso sideload vero).
- Push istantaneo FCM/ntfy (oggi: polling 15 min), timezone configurabile per famiglia, developer verification 2027.
