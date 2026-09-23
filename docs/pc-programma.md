# Pactum per il computer (v0.8)

Il programma per Windows 10/11 che fa sul computer quello che l'app del figlio fa sul telefono: misura, registra, avvisa, e mostra al figlio il suo patto. **Non blocca niente**, come tutto Pactum.

Decisioni di Andrea (23/09/2026): il computer misura **programmi e siti**; il figlio gestisce le regole del computer **anche dal computer**, con una finestra completa; i siti si leggono **dalla barra degli indirizzi**, tenendo solo il dominio (v. `contratto-api.md`, "Sul computer"). Il protocollo col server è la **v3** del contratto.

## Come è fatto

- **C# .NET 8, WinForms**:
  - icona vicino all'orologio (NotifyIcon) con menu: Apri Pactum, Aggiorna adesso, Chiudi Pactum;
  - una finestra con **WebView2** che mostra l'interfaccia HTML.
- Cartelle:
  - `pc/Pactum/`: il motore (progetto C#);
  - `pc/ui/`: l'interfaccia (HTML/CSS/JS, niente librerie esterne, niente rete verso fuori).
- **Per utente, senza amministratore**: si installa in `%LOCALAPPDATA%\Programs\Pactum`, parte al login con la chiave `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`, un'istanza sola per utente (mutex con nome per sessione).
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
  - `discord.exe` e `instagram.com` → social;
  - `steam.exe`, `minecraft.windows.exe`, `roblox.com` → giochi;
  - `youtube.com`, `netflix.com`, `twitch.tv` → video;
  - `spotify.exe` → musica.
  - Il tempo nel browser va nella categoria del sito, se il sito ne ha una (contratto v3).
- **Giorno**: il giorno locale del computer.

## Cosa manda (contratto v3)

- `POST /api/battito` ogni 5 minuti, e subito all'avvio e alla ripresa.
- `POST /api/eventi` ogni 5 minuti con quello che è in coda:
  - le **fotografie cumulative** del giorno (`uso_giornaliero` con `exe:` e `siti_giornalieri` con `domini`, `minuti`, `totale_domini`, `dns_cifrato`);
  - gli sforamenti, le manomissioni, `sospensione` e `ripresa`.
  - Id degli eventi: UUID generati qui. La coda sopravvive ai riavvii; se non c'è rete, si riprova.
- `GET /api/patto` ogni 5 minuti e dopo ogni modifica: regole di questo computer + vita reale, bonus, striscia.
- `GET /api/notifiche` ogni 5 minuti: le nuove diventano un avviso di Windows (fumetto dell'icona). Stesso schema dell'app del telefono per i tipi: `nuova_proposta`, `verdetto`, `segno`, eccetera.

## Il registro onesto

- **Spegnimento, sospensione, uscita dall'account**: manda `sospensione` con `motivo` prima che succeda (`SystemEvents.PowerModeChanged`, `SessionEnding`). Al ritorno manda `ripresa`.
- **Programma chiuso a forza**:
  - il programma scrive ogni minuto "sono vivo alle…";
  - al riavvio confronta quell'orario con l'avvio di Windows (`Environment.TickCount64`);
  - se Windows era acceso da prima dell'ultimo "sono vivo" e non c'era stata una chiusura pulita, manda `manomissione {sotto_tipo: "programma_chiuso", dal, al}`.
- **Orologio spostato a mano**: confronta a ogni giro l'orologio di Windows con un cronometro interno. Un salto di più di 2 minuti manda `manomissione {sotto_tipo: "cambio_ora", drift_secondi}`. Un cambio di fuso manda `cambio_fuso`.

## Regole valutate sul computer

- `limite_tempo`: i minuti di oggi su `exe:`, `sito:` o `categoria:` contro `minuti_al_giorno` + bonus di oggi su quella regola. Oltre il limite:
  - **un** evento `sforamento` al giorno per regola, con `giorno`, `limite_efficace`, `minuti_oltre`;
  - avviso "Oggi sei andato oltre".
- `fascia_oraria`: tempo attivo dentro la fascia → uno `sforamento` per occorrenza, ancorato al giorno in cui la fascia parte (come il telefono).
- Nessun blocco. Mai.

## Abbinamento

- Primo avvio: finestra "Collega questo computer": indirizzo del server + codice di 6 cifre (glielo dà il genitore dall'app).
- Poi `POST /api/abbina` e il token salvato con DPAPI. Da lì in poi niente da configurare.

## L'accordo fra motore e interfaccia

- L'interfaccia è caricata da `https://pactum.locale/` (cartella `ui` mappata con `SetVirtualHostNameToFolderMapping`).
- Il motore intercetta con `WebResourceRequested` le richieste a `https://pactum.locale/locale/*` e `https://pactum.locale/server/*`: niente porte aperte sul computer.
- Tutte le risposte sono JSON.
- L'interfaccia fa **solo** queste chiamate, con percorsi relativi.

### Locali

| Chiamata | Cosa fa | Risposta |
|---|---|---|
| `GET /locale/stato` | Stato del programma | `{ "abbinato": bool, "server": "https://…", "figlio": {id, nome} \| null, "dispositivo": {id, nome, tipo} \| null, "versione": "0.8.0", "ultimo_invio_ok": ISO \| null, "rete_ok": bool, "patto_aggiornato": ISO \| null }` |
| `POST /locale/abbina` | Corpo `{ "server": "https://…", "codice": "123456" }` | `{ "ok": true, "figlio", "dispositivo" }` oppure `{ "ok": false, "errore": "codice_non_valido" \| "troppi_tentativi" \| "rete" \| "indirizzo_non_valido" }` |
| `GET /locale/oggi` | Misura locale di oggi | Vedi sotto |
| `GET /locale/visti` | Per scegliere il bersaglio di una regola: programmi e siti visti negli ultimi 30 giorni | `{ "programmi": [ {"chiave": "exe:…", "nome": "…"} ], "siti": [ "youtube.com", … ] }` |
| `POST /locale/bonus` | Corpo `{ "regola_id", "minuti", "motivo"? }`. Il motore lo manda al server (senza ritentativi automatici) e aggiorna subito i limiti locali | `{ "ok": true, "bonus": {…} }` oppure `{ "ok": false, "errore": "tetto_superato" \| "regola_non_valida" \| "rete", "dettagli": {…} }` |
| `GET /locale/serie` | Serie e record, calcolati qui dalla `striscia` del figlio, **mai mandati al server** (come sul telefono) | `{ "serie": n, "record": n }` |
| `POST /locale/aggiorna` | Invia la coda e rilegge patto e notifiche adesso | `{ "ok": bool }` |

Risposta di `GET /locale/oggi`:

```json
{ "giorno": "2026-09-24", "totale_minuti": 131,
  "programmi": [ { "chiave": "exe:chrome.exe", "nome": "Google Chrome", "categoria": "altro", "minuti": 80 } ],
  "siti": [ { "dominio": "youtube.com", "minuti": 42, "visite": 7 } ],
  "regole": { "12": { "minuti": 48, "limite_efficace": 75, "oltre": 0 } },
  "fasce": { "13": { "attiva_ora": false, "prossimo_inizio": "22:00", "fine": "07:00" } },
  "siti_non_leggibili": false }
```

### Verso il server

`GET|POST|PATCH|DELETE /server/<percorso>` → il motore gira la richiesta a `<server>/<percorso>` con il token del dispositivo e restituisce stato e JSON così come sono. Esempio: `GET /server/api/patto`, `POST /server/api/regole`. Dopo ogni `POST`, `PATCH` o `DELETE` il motore rilegge il patto.

**L'interfaccia non conosce il token e non parla mai direttamente col server.**

## Aggiornamento

- `GET /api/versione` → `computer`.
- Se c'è una versione nuova il programma scarica lo zip, lo scompatta accanto e al prossimo avvio si sostituisce. Il primo scaricamento, fatto a mano dal browser, passa dall'avviso di Windows SmartScreen ("Ulteriori informazioni" → "Esegui comunque"): il programma non è firmato.

## Pacchetto

`dotnet publish` self-contained per `win-x64`: un file `Pactum.exe` + la cartella `ui`, dentro `pactum-computer.zip`. Al primo avvio da una cartella qualsiasi il programma si copia in `%LOCALAPPDATA%\Programs\Pactum`, registra l'avvio al login e riparte da lì.
