# Pactum per il computer (v0.10)

Il programma per Windows 10/11 che fa sul computer quello che l'app del figlio fa sul telefono: misura, registra, avvisa, e mostra al figlio il suo patto. **Non blocca niente**, come tutto Pactum.

Decisioni di Andrea (23/09/2026): il computer misura **programmi e siti**; il figlio gestisce le regole del computer **anche dal computer**, con una finestra completa; i siti si leggono **dalla barra degli indirizzi**, tenendo solo il dominio (v. `contratto-api.md`, "Sul computer"). Il protocollo col server è la **v3** del contratto.

Decisioni di Andrea (30/09/2026, versione 0.9): un limite di tempo può valere su **tutto il computer** (contratto **v3.3**, `app_o_categoria = "totale"`), e quando si va oltre una regola, oltre al fumetto, si apre un **avviso a tutto schermo** che si chiude sempre.

Decisione di Andrea (30/09/2026, versione 0.10): **«come adesso + proposte»**. Il figlio continua a cambiare da solo le sue regole (stringere subito, allentare dopo 4 giorni) e in più può **proporre un cambio al genitore**, che **se il genitore accetta vale subito**, anche se allenta (contratto **v3.4**, v. "Le proposte al genitore").

## Come è fatto

- **C# .NET 8, WinForms**:
  - icona vicino all'orologio (NotifyIcon) con menu: Apri Pactum, Aggiorna adesso, Chiudi Pactum;
  - una finestra con **WebView2** che mostra l'interfaccia HTML.
- Cartelle:
  - `pc/Pactum/`: il motore (progetto C#);
  - `pc/ui/`: l'interfaccia (HTML/CSS/JS, niente librerie esterne, niente rete verso fuori).
- **Per utente, senza amministratore**: gira dalla cartella dove è stato scompattato (non si copia da nessuna parte), parte al login con la chiave `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` che punta a quella cartella, un'istanza sola per utente (mutex con nome per sessione).
- **Dati** in `%LOCALAPPDATA%\Pactum\`:
  - `config.json`: indirizzo del server, token protetto con DPAPI dell'utente, dispositivo, figlio;
  - un file per giorno con la misura;
  - la coda degli eventi da mandare.
  - Scritture atomiche (file temporaneo + rinomina).
- **Visibile sempre**: l'icona c'è finché il programma gira. Niente di nascosto.
- **Chiudere si può**:
  - "Chiudi Pactum" chiede conferma ("Se chiudi, tuo padre vedrà un'interruzione nella registrazione") e manda subito `manomissione {sotto_tipo: "programma_chiuso", volontario: true}`;
  - se il programma viene chiuso in altro modo, se ne accorge al riavvio (v. sotto).

## Cosa misura

- **Programmi**
  - Ogni secondo prende il processo della finestra in primo piano e lo identifica con `exe:<nome.exe>` minuscolo.
  - Le app dello Store stanno dentro `ApplicationFrameHost.exe`: si risale al processo vero della finestra figlia.
  - Il nome leggibile viene dalla descrizione del file del programma ("Google Chrome"), altrimenti dal nome del file.
  - **Titoli delle finestre: mai letti, mai salvati.**
- **Tempo attivo**: conta solo se l'utente c'è:
  - un input negli ultimi 3 minuti (`GetLastInputInfo`), oppure
  - la finestra in primo piano è a schermo intero (film, giochi).
  - Schermo bloccato, sospensione o altra sessione: non conta.
- **Siti**, solo quando in primo piano c'è `chrome.exe`, `msedge.exe`, `firefox.exe` o `brave.exe`:
  - legge con UI Automation il testo della barra degli indirizzi e ne ricava il **dominio registrabile** (stesse regole del telefono: `m.youtube.com` → `youtube.com`, suffissi come `.co.uk` e `.gov.it` gestiti);
  - **l'indirizzo completo non si salva, non si registra, non si scrive nei log**: vive solo nella funzione che estrae il dominio;
  - minuti per dominio = tempo attivo con quel dominio in primo piano;
  - una visita = il dominio in primo piano cambia e diventa quello;
  - se per un browser la lettura fallisce per più di un minuto, il giorno diventa `dns_cifrato: true` ("siti non leggibili").
- **Categorie** (`social`, `giochi`, `video`, `musica`, `altro`): una tabella interna di programmi e siti comuni, per esempio:
  - `discord.exe`, `instagram.com` e (dal 30/09) `youtube.com` → social;
  - `steam.exe`, `minecraft.windows.exe`, `roblox.com` → giochi;
  - `netflix.com`, `twitch.tv` → video;
  - `spotify.exe` → musica.
  - (0.9, decisione di Andrea del 30/09) i programmi e i siti di **messaggi** (`whatsapp.exe`, `whatsapp.root.exe`, `telegram.exe`, `signal.exe`, `messenger.exe`, `skype.exe`; `whatsapp.com`, `telegram.org`, `messenger.com`, `signal.org`) non stanno in nessuna categoria della tabella: come ogni programma o sito non elencato, il loro tempo va in `altro`, mai in `social`. Discord, Instagram e gli altri social veri restano `social`.
  - Il tempo nel browser va nella categoria del sito, se il sito ne ha una (contratto v3).
- **Giorno**: il giorno locale del computer.

## Cosa manda (contratto v3)

- `POST /api/battito` ogni 5 minuti, e subito all'avvio e alla ripresa.
- `POST /api/eventi` ogni 5 minuti con quello che è in coda:
  - le **fotografie cumulative** del giorno (`uso_giornaliero` con `exe:` e `siti_giornalieri` con `domini`, `minuti`, `totale_domini`, `dns_cifrato`);
  - gli sforamenti, le manomissioni, `sospensione` e `ripresa`.
  - Id degli eventi: UUID generati qui. La coda sopravvive ai riavvii; se non c'è rete, si riprova.
- `GET /api/patto` ogni 5 minuti e dopo ogni modifica: regole di questo computer + vita reale, bonus, striscia. In più (0.10):
  - prima di registrare uno sforamento **nuovo** (v. "Quando si valuta");
  - il patto che la finestra legge ogni minuto (`GET /server/api/patto`) diventa anche quello del motore;
  - finché una proposta del figlio aspetta il genitore (`proposte_inviate` non vuota), un **giro veloce ogni minuto** legge patto e notifiche: se il genitore accetta, la regola vale sul computer entro un minuto e il fumetto arriva con lei.
  - Un patto chiesto prima di quello in uso, che arriva dopo, non lo sostituisce.
- `GET /api/notifiche` ogni 5 minuti (e nel giro veloce): le nuove diventano un avviso di Windows (fumetto dell'icona). Stesso schema dell'app del telefono per i tipi: `nuova_proposta`, `verdetto`, `segno`, eccetera; (0.10) anche `proposta_risposta` e `proposta_ritirata` (v. "Le proposte al genitore").
  - Il computer non le marca lette (dalla v3.1 varrebbe solo per lui): tiene il segno dell'ultima vista in `notifiche.json` e (0.10) chiede solo quelle dopo, `GET /api/notifiche?dopo_id=<ultima vista>` (contratto v3.3). Un server più vecchio ignora il parametro e le manda tutte: il segno tiene comunque solo le nuove. Il primo giro dopo l'abbinamento non le mostra: fa solo il segno.
  - Il testo del fumetto non ripete il titolo: il contratto scrive «<titolo>: <confronto>», nel testo resta il confronto (con cosa cambia: «Vale già da adesso.», «La regola resta com'è.»). Mai vuoto: se il messaggio manca o è solo il titolo, una frase che dice cosa cambia.
  - (0.10) Il clic sul fumetto di una proposta apre **Proposte**, quello di un verdetto il **Diario**, come le notifiche del telefono; gli altri aprono Pactum com'è.

## Il registro onesto

- **Spegnimento, sospensione, uscita dall'account**: manda `sospensione` con `motivo` prima che succeda (`SystemEvents.PowerModeChanged`, `SessionEnding`). Al ritorno manda `ripresa`.
- **Programma chiuso a forza**:
  - il programma scrive ogni minuto "sono vivo alle…";
  - al riavvio confronta quell'orario con l'avvio di Windows (`Environment.TickCount64`);
  - se Windows era acceso da prima dell'ultimo "sono vivo" e non c'era stata una chiusura pulita, manda `manomissione {sotto_tipo: "programma_chiuso", dal, al}`.
- **Avviato in ritardo** (Pactum tolto dall'avvio automatico, o aperto tardi a mano): se al primo giro Windows è acceso da più di 10 minuti e l'ultima chiusura era pulita, manda `manomissione {sotto_tipo: "programma_chiuso", dal: <avvio di Windows>, al: <adesso>, avvio_ritardato: true}` — così il tempo in cui il computer era acceso senza Pactum non sparisce dal registro (altrimenti il server lo vedrebbe "spento" all'infinito).
- **Orologio spostato a mano**: confronta a ogni giro l'orologio di Windows con un cronometro interno. Un salto di più di 2 minuti manda `manomissione {sotto_tipo: "cambio_ora", drift_secondi}`. Un cambio di fuso manda `cambio_fuso`.

## Regole valutate sul computer

- `limite_tempo`: i minuti di oggi su `exe:`, `sito:`, `categoria:` o (v3.3) `totale` contro `minuti_al_giorno` + bonus di oggi su quella regola. Oltre il limite:
  - **un** evento `sforamento` al giorno per regola, con `giorno`, `limite_efficace`, `minuti_oltre`;
  - avviso "Oggi sei andato oltre".
- `fascia_oraria`: tempo attivo dentro la fascia → uno `sforamento` per occorrenza, ancorato al giorno in cui la fascia parte (come il telefono).
- (0.9, contratto v3.3) `totale` = **tutto il computer**: tutto il tempo attivo del giorno, cioè lo stesso `totale_minuti` che parte nella fotografia `uso_giornaliero` (anche il tempo sul desktop o in Pactum stesso). Il nome leggibile, ovunque, è **"Tutto il computer"**; per una regola `totale` di un telefono (per esempio in una proposta) è "Tutto il telefono". Bonus, sforamento e deduplica come per le altre regole.
- **Quando si valuta**: ogni 15 secondi e dopo ogni lettura del patto del giro di rete. (0.10) **Prima di registrare uno sforamento nuovo** si rilegge il patto dal server (al massimo 10 secondi) e si rivaluta sulla **stessa lettura dell'uso**, come la sentinella del telefono: una copia rimasta indietro (una proposta appena accettata dal genitore, un bonus dato dal telefono) non registra mai uno sforamento falso. Senza rete si decide sulla copia, come prima. La misura intanto va avanti: la rete non la ferma. In più, (0.9) **a mezzanotte** il giorno che si chiude si valuta fino al suo ultimo secondo, prima di passare al nuovo (uno sforamento negli ultimi secondi resta di quel giorno); e **a sospensione, spegnimento, uscita dall'account e "Chiudi Pactum"** si valuta un'ultima volta. In questi casi si decide sulla copia del patto (non c'è tempo per chiedere al server) e parte solo l'evento `sforamento`: niente fumetto e niente avviso a schermo ("oggi sei andato oltre" a mezzanotte non sarebbe più vero, e lo schermo sta per spegnersi).
- **Disco al meglio possibile** (0.9): gli sforamenti nuovi stanno sempre nella coda in memoria e partono col giro di rete anche se `coda.json` non si riesce a scrivere (disco pieno, antivirus); il file si riscrive appena si può. Se `sforamenti.json` non si scrive, si riprova a ogni valutazione finché non ci riesce. Un errore sul disco non ferma mai fumetto e avviso. All'avvio gli sforamenti ancora in coda contano come già segnalati: un riavvio lo stesso giorno non ripete né l'evento né l'avviso.
- Nessun blocco. Mai.

### L'avviso a tutto schermo (0.9)

- Quando il valutatore trova uno sforamento **nuovo** (un limite, o una fascia), oltre al fumetto vicino all'orologio si apre una finestra **a tutto schermo, sopra tutto, sullo schermo della finestra in primo piano** (cioè dove si sta guardando; se non c'è una finestra in primo piano, sul monitor principale). Se cambiano la scala o gli schermi (un monitor staccato, una risoluzione nuova) si rifanno posizione e misure.
- Dice quale regola ("Tutto il computer", "Minecraft", "youtube.com", "Social", "Niente computer dalle 22:00 alle 07:00"), quanto hai usato ("2 h 10 min su 2 h", la barra piena, "10 min oltre") e il limite che ti sei dato ("Il limite che ti sei dato: 2 h al giorno, più 15 min di bonus oggi."); per una fascia: "Oggi 20 min di computer dentro questa fascia.". Poi la frase dei fumetti: **"Nessun blocco: è il tuo patto."**
- Due pulsanti: **"Ho capito"** chiude (anche Invio, Esc, Alt+F4); **"Apri Pactum"** chiude e apre la finestra del programma. **Si chiude sempre: non è un blocco.** Solo nei primi 0,7 secondi i pulsanti non rispondono, così un tasto premuto mentre si giocava o si scriveva non la chiude prima che la si veda. Se le regole sono tante e non ci stanno, scorre il contenuto: pulsanti e riga qui sotto restano in vista.
- **Tastiera e giochi, cosa succede davvero.** Windows non lascia a un programma in sottofondo il "primo piano" di un altro: se l'avviso compare mentre si usa un altro programma (tipicamente un gioco), la finestra si vede sopra tutto e il mouse funziona subito, ma **la tastiera resta al gioco** finché non si fa clic sull'avviso. Per questo sotto i pulsanti c'è una riga piccola: **"Se i tasti non rispondono, fai clic qui."** Dopo quel clic (o un clic qualsiasi sull'avviso) Invio ed Esc funzionano. Pactum non usa trucchi per rubare il primo piano.
- Quasi tutti i giochi su Windows 10/11 girano "a schermo intero" senza esclusiva (finestra senza bordi, o con le ottimizzazioni per lo schermo intero): lì l'avviso compare sopra il gioco. Un gioco in **esclusiva vera DirectX** invece tiene lo schermo per sé: nessuna finestra ci può comparire sopra, e l'avviso **si vede appena si esce dal gioco** (Alt+Tab, tasto Windows, gioco chiuso). Anche il fumetto, di solito, Windows lo tiene da parte mentre si gioca a schermo intero e lo mette nelle notifiche. L'evento `sforamento` parte comunque, subito. (Non provato con un gioco in esclusiva vera: è il comportamento di Windows.)
- **Mai sotto una domanda del programma.** "Chiudi Pactum" chiude prima l'avviso e poi fa la sua domanda; mentre la domanda è aperta gli avvisi nuovi aspettano e compaiono dopo, se Pactum resta aperto. (Prima la domanda finiva sotto l'avviso sempre in primo piano, che lei stessa disabilitava: lo schermo sembrava bloccato.)
- **Aprire Pactum chiude l'avviso**, da qualunque parte (menu dell'icona, doppio clic, clic sul fumetto, secondo avvio), come fa già il suo pulsante "Apri Pactum".
- **Una volta per regola per giorno**, con la stessa deduplica dei fumetti e degli eventi `sforamento` (per le fasce, per giorno di ancoraggio): gli sforamenti già segnalati non riaprono l'avviso, nemmeno dopo un bonus o un riavvio. Se l'avviso è già aperto, le regole nuove si aggiungono lì; se nel frattempo era stato coperto o ridotto a icona, torna com'era e in cima, senza rubare la tastiera. Se l'apertura della finestra fallisce a metà, la finestra rotta si libera subito: non ne resta una invisibile.
- È una finestra WinForms, non una pagina della WebView2: compare subito, non dipende da WebView2 e i pulsanti ci sono sempre (una pagina che non carica lascerebbe uno schermo vuoto, che sembrerebbe un blocco). Colori, caratteri e misure sono quelli di `stile.css`; col contrasto elevato di Windows usa i colori di sistema.
- Resta tutto sul computer: il server riceve lo `sforamento` di sempre, niente di nuovo.
- Per le prove sul PC di qualcuno, `--prova-avvisi <cartella>` manda fumetti e avvisi in quella cartella (testo e immagine) invece che sullo schermo.

### Le opzioni di prova (0.9)

`--prova-avvisi`, `--siti-solo`, `--prova-finestra`, `--esci-dopo` e `--esci-dopo-autoprova` servono solo alle prove: **valgono solo insieme a `--dati` con una cartella diversa da quella vera** (`%LOCALAPPDATA%\Pactum`). Senza, il programma le ignora e il diario lo scrive ("opzioni di prova ignorate…"): così nessuno le può aggiungere all'avvio per falsare quello che il programma misura o mostra, o per farlo chiudere da solo con una chiusura "pulita". Con `--dati` sulla cartella vera l'istanza resta quella del programma vero (non se ne apre una seconda sugli stessi dati). La prova rapida di `crea-pacchetto.ps1` (`--dati` in una cartella temporanea) funziona come prima.

## Le proposte al genitore (0.10)

Contratto v3.4: propone anche il figlio e risponde il genitore. Una sola proposta in attesa per regola, di chiunque sia.

- **Dove si propone.** In «Le mie regole», sotto ogni regola del computer e di vita reale, accanto a Modifica ed Elimina c'è **«Proponi al genitore»** (con l'icona delle proposte). Apre **lo stesso modulo della modifica**, già compilato e con gli stessi selettori (Tutto il computer, programma, sito, categoria; orari e giorni; impegno, arbitro, frequenza), più:
  - una riga in cima: «Scrivila come la vorresti. Se il genitore accetta, vale subito, anche se la allenta. Stringerla invece puoi farlo da solo, sempre, con «Modifica».» (per la vita reale solo la prima parte: lì ogni cambio conta come un allentamento);
  - **«Perché? (facoltativo)»**: arriva al genitore con la proposta;
  - **«Proponi di eliminarla»** (manda il marcatore `{"azione": "elimina"}`), «Se il genitore accetta, la regola esce dal patto.». Chiede conferma come sul telefono: «Chiedi al genitore di eliminare dal patto «…».», con **Manda** e **Indietro**. Non c'è sull'unica regola del patto, che nemmeno d'accordo si può eliminare: come per `ultima_regola` del server, le regole di un dispositivo revocato non contano.
  - Il pulsante è «Manda la proposta». Una proposta uguale alla regola di adesso non parte: «È uguale alla regola di adesso: cambia qualcosa, oppure proponi di eliminarla.»
- **Il blocco dei 4 giorni.** Quando Salva o Elimina ricevono `409 lock_attivo` resta il messaggio di sempre («Questa modifica allenta la regola: potrai allentarla dal…») e sotto compare un riquadro «Puoi chiederlo al genitore: se accetta, vale subito.» con il suo «Perché? (facoltativo)» e **«Chiedi al genitore»**: manda come proposta lo stesso cambio scritto nel modulo (o l'eliminazione). Invio nel «Perché?» manda la proposta, non il modulo della modifica. La nota in cima al modulo di modifica lo dice già prima: «Stringerla si può sempre; allentarla si potrà dal…, o prima se lo chiedi al genitore e accetta.»
- **Sulla regola, mentre si aspetta.** Se c'è una proposta in attesa, la card lo dice («Hai proposto al genitore: +30 min al giorno rispetto ad ora. Aspetta la sua risposta.» oppure «Il genitore propone: … Decidi tu.») con «Vai alle proposte», e «Proponi al genitore» torna quando la proposta è chiusa.
- **La sezione Proposte**, in tre parti:
  - «Da decidere»: le proposte del genitore, con Accetto / Rifiuto come prima. Il numero accanto a «Proposte» nel menu conta solo queste: alle sue proposte il figlio non risponde.
  - **«Le tue proposte»**: quelle che aspettano il genitore, con com'è adesso, come sarebbe se accetta, il tuo perché e **«Ritira»**, che chiede conferma («Il genitore non dovrà più deciderla e la regola resta com'è. Gli arriva un avviso.»). Se non ce ne sono, si spiega come farne una e c'è il collegamento a «Le mie regole».
  - «Storia»: le proposte chiuse di tutti e due, con chi l'ha fatta e com'è finita («TUA PROPOSTA · RIFIUTATA», «PROPOSTA DEL GENITORE · ACCETTATA», ritirata, annullata), il tuo perché e quello che ha detto il genitore. Una tua proposta di eliminare si legge «Eliminare la regola «…»» (il confronto del server, «propone di eliminare la regola», è scritto per il genitore).
  - Una proposta appena mandata o ritirata si vede subito, e resta così finché non torna un giro di lettura partito dopo: un giro già in corso, con le liste di prima, non la fa sparire; appena finisce ne parte un altro.
- **Gli errori, in parole semplici** (mai un "errore" e basta): `proposta_gia_pendente` → «C'è già una proposta in attesa su questa regola.»; `regola_non_valida` → «Questa regola non è più nel patto: la lista si aggiorna da sola.»; `dispositivo_revocato` → «Questa regola è di un dispositivo che non è più collegato al patto: non si può più cambiare.»; `422` → «Il server non ha accettato la proposta: controlla i campi e riprova.»; Ritira su una proposta già chiusa → «Questa proposta non è più in attesa: la lista si aggiorna da sola.»; Ritira su una proposta che il server v3.4 non trova più (`404 {"detail": "proposta non trovata"}`) → «Non trovo più questa proposta: la lista si aggiorna da sola.».
- **Server di prima della v3.4**: `403` a «Proponi al genitore» (le proposte le accettava solo dal genitore) → «Per mandare proposte serve aggiornare il server di Pactum.»; `404 Not Found` o `405` a «Ritira» → «Per ritirare una proposta serve aggiornare il server di Pactum.» Di solito lo si sa già prima: se il patto non ha proprio il campo `proposte_inviate` (la v3.4 lo manda sempre, anche vuoto), al posto di «Proponi al genitore» c'è la riga «Per mandare proposte serve aggiornare il server di Pactum.», e così nel riquadro del blocco dei 4 giorni e in «Le tue proposte». Tutto il resto funziona come la 0.9.
- **Come passa.** Proporre e ritirare passano dal motore (`/locale/proponi`, `/locale/ritira`), che manda la richiesta una volta sola e traduce gli esiti in codici; le liste arrivano come sempre da `/server/api/patto` (`proposte_pendenti` = quelle del genitore, **`proposte_inviate`** = quelle del figlio, se manca nessuna) e da **`/server/api/proposte?autori=tutti`** (tutti e due gli autori: senza il parametro la v3.4 manda solo quelle del genitore, per le app 0.8 e 0.9; `autore` mancante = genitore). Dove l'interfaccia scrive da sola una regola proposta usa i nomi (Minecraft, youtube.com, Social, Tutto il computer), mai le chiavi `exe:`/`sito:`; il `confronto` lo scrive il server, coi nomi anche lui quando il bersaglio cambia.
- **I fumetti.** `proposta_risposta` (con `autore: "figlio"`): «Il genitore ha accettato la tua proposta» («+30 min al giorno rispetto ad ora. Vale già da adesso.»; per un'eliminazione «La regola è uscita dal patto.») o «Il genitore ha rifiutato la tua proposta» («… La regola resta com'è.»). `proposta_ritirata`: «Il genitore ha ritirato la sua proposta», con «Non c'è più niente da decidere: la regola resta com'è.». Il clic apre Proposte. Il computer non marca lette le notifiche: tiene il segno dell'ultima vista e chiede solo quelle dopo (v. "Cosa manda").
- «Cosa vede tuo padre» dice anche: «Le proposte che gli mandi tu, con il tuo perché se lo scrivi, e quelle che ritiri.»
- Per le prove: `pc/ui/prova.js` (`?prova=1`, e `?prova=1&vecchio=1` per il server di prima della v3.4) e `pc/prove/finto_server.py` (proposte v3.4; `/prova/proposta`, `/prova/risposta`, `/prova/ritira` fanno quello che farebbe il genitore; `--server-vecchio`).

## Abbinamento

- Primo avvio: finestra "Collega questo computer": indirizzo del server + codice di 6 cifre (glielo dà il genitore dall'app).
- Poi `POST /api/abbina` e il token salvato con DPAPI. Da lì in poi niente da configurare.
- L'abbinamento manda sempre `tipo: "computer"` (contratto v3.1): se il codice era di un telefono il server risponde `tipo_non_corrispondente` e il figlio legge "Questo codice è per un telefono, non per questo computer".

## L'accordo fra motore e interfaccia

- L'interfaccia è caricata da `https://pactum.locale/` (cartella `ui` mappata con `SetVirtualHostNameToFolderMapping`).
- Il motore intercetta con `WebResourceRequested` le richieste a `https://pactum.locale/locale/*` e `https://pactum.locale/server/*`: niente porte aperte sul computer.
- Tutte le risposte sono JSON.
- L'interfaccia fa **solo** queste chiamate, con percorsi relativi.

### Locali

| Chiamata | Cosa fa | Risposta |
|---|---|---|
| `GET /locale/stato` | Stato del programma | `{ "abbinato": bool, "server": "https://…", "figlio": {id, nome} \| null, "dispositivo": {id, nome, tipo} \| null, "versione": "0.10.0", "ultimo_invio_ok": ISO \| null, "rete_ok": bool, "patto_aggiornato": ISO \| null }` |
| `POST /locale/abbina` | Corpo `{ "server": "https://…", "codice": "123456" }` | `{ "ok": true, "figlio", "dispositivo" }` oppure `{ "ok": false, "errore": "codice_non_valido" \| "troppi_tentativi" \| "rete" \| "indirizzo_non_valido" }` |
| `GET /locale/oggi` | Misura locale di oggi | Vedi sotto |
| `GET /locale/visti` | Per scegliere il bersaglio di una regola: programmi e siti visti negli ultimi 30 giorni | `{ "programmi": [ {"chiave": "exe:…", "nome": "…"} ], "siti": [ "youtube.com", … ] }` |
| `POST /locale/bonus` | Corpo `{ "regola_id", "minuti", "motivo"? }`. Il motore lo manda al server (senza ritentativi automatici) e aggiorna subito i limiti locali | `{ "ok": true, "bonus": {…} }` oppure `{ "ok": false, "errore": "tetto_superato" \| "regola_non_valida" \| "rete", "dettagli": {…} }` |
| `GET /locale/serie` | Serie e record, calcolati qui dalla `striscia` del figlio, **mai mandati al server** (come sul telefono) | `{ "serie": n, "record": n }` |
| `POST /locale/aggiorna` | Invia la coda e rilegge patto e notifiche adesso | `{ "ok": bool }` |
| `POST /locale/proponi` | (0.10) Corpo `{ "regola_id", "parametri_proposti": {…} \| {"azione": "elimina"}, "motivazione"? }`. Il motore la manda al server (`POST /api/proposte`, una volta sola, mai `figlio_id`) | `{ "ok": true, "proposta": {…} }` oppure `{ "ok": false, "errore": "proposta_gia_pendente" \| "regola_non_valida" \| "dispositivo_revocato" \| "parametri_non_validi" \| "server_da_aggiornare" \| "non_abbinato" \| "rete" \| "non_riuscita" }` |
| `POST /locale/ritira` | (0.10) Corpo `{ "proposta_id" }`: `POST /api/proposte/{id}/ritira`, senza corpo | `{ "ok": true, "proposta": {…} }` oppure `{ "ok": false, "errore": "proposta_non_pendente" \| "non_tua" \| "server_da_aggiornare" \| "non_abbinato" \| "rete" \| "non_riuscita" }` |

Risposta di `GET /locale/oggi`:

```json
{ "giorno": "2026-09-24", "totale_minuti": 131,
  "programmi": [ { "chiave": "exe:chrome.exe", "nome": "Google Chrome", "categoria": "altro", "minuti": 80 } ],
  "siti": [ { "dominio": "youtube.com", "minuti": 42, "visite": 7 } ],
  "regole": { "12": { "minuti": 48, "limite_efficace": 75, "oltre": 0 } },
  "fasce": { "13": { "attiva_ora": false, "prossimo_inizio": "22:00", "fine": "07:00" } },
  "siti_non_leggibili": false }
```

(0.9) In `regole` ci sono anche le regole su tutto il computer (`app_o_categoria = "totale"`): per loro `minuti` è `totale_minuti`. L'interfaccia le chiama "Tutto il computer" e, nella creazione di un limite di tempo, "Tutto il computer" è il primo dei bersagli, prima di programma, sito e categoria. Come sul telefono nessuno è già scelto: finché il figlio non sceglie, "Salva" risponde "Scegli su cosa vale il limite."

### Verso il server

`GET|POST|PATCH|DELETE /server/<percorso>` → il motore gira la richiesta a `<server>/<percorso>` con il token del dispositivo e restituisce stato e JSON così come sono. Esempio: `GET /server/api/patto`, `POST /server/api/regole`. Dopo ogni `POST`, `PATCH` o `DELETE` il motore rilegge il patto. (0.10) Un `GET /server/api/patto` riuscito diventa anche il patto del motore (la finestra lo legge ogni minuto).

**L'interfaccia non conosce il token e non parla mai direttamente col server.**

## Aggiornamento

- `GET /api/versione` → `computer`: ogni 12 ore il programma confronta `versione_code` col proprio.
- (0.10) Il codice segue quello delle app del telefono: 0.8.0 = 8, 0.9.0 = 9, **0.10.0 = 10**. Il server per la 0.8 e la 0.9 annunciava per sbaglio 1 e 2, più bassi del codice del programma, e il programma non avvisava mai. Dalla 0.10 il server annuncia 10: anche il programma 0.8 già installato (codice 8) vede la versione nuova e lo dice.
- Se ce n'è una più nuova **avvisa e basta**: un fumetto "C'è una versione nuova di Pactum" e, al clic, apre la pagina `<server>/scarica` nel browser predefinito. Il programma **non scarica e non sostituisce niente da solo**: il figlio scarica lo zip e lo reinstalla come la prima volta (vedi Installazione). Così l'aggiornamento resta un gesto visibile e non un file che si cambia da sé (che un antivirus scambierebbe per un programma sospetto).

## Pacchetto

`dotnet publish` self-contained per `win-x64`, **a cartella** (non single-file: niente eseguibile che si scompatta da solo). Lo zip `pactum-computer.zip` contiene una cartella `Pactum\` con `Pactum.exe`, le dll del runtime .NET e la cartella `ui`. Si costruisce con `pc/prove/crea-pacchetto.ps1`.

Il programma **non si copia da nessuna parte**: gira dalla cartella dove il figlio ha scompattato lo zip e vi resta. L'unica cosa che scrive è la voce di avvio al login in `HKCU\...\Run`, che punta proprio a quella cartella.

## Installazione (sabato)

1. Scaricare `pactum-computer.zip` dalla pagina `<server>/scarica`.
2. Scompattarlo in una cartella stabile, per esempio `Documenti\Pactum` (non nei Download, che si svuotano). Dentro c'è la cartella `Pactum\` con `Pactum.exe`.
3. Aprire `Pactum.exe`. Windows SmartScreen avvisa che il programma non è firmato: **"Ulteriori informazioni" → "Esegui comunque"**.
4. Se l'antivirus lo blocca, l'eccezione la aggiunge chi ha un account amministratore. Nella prova del 23/09 il pacchetto a cartella **non** è stato bloccato (a differenza del vecchio single-file da 66 MB, che era finito in quarantena).
5. Alla prima apertura il programma chiede l'**indirizzo del server** e un **codice di 6 cifre** (lo crea il genitore dall'app, vale 15 minuti). Da lì in poi parte da solo al login, dalla cartella dov'è.
