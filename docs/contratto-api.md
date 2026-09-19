# Pactum — Contratto API v1 (app ⇄ postino)

**Questo file è la fonte di verità del protocollo.** Ogni modifica al modo in cui app e server si parlano passa da qui PRIMA di toccare il codice. Server e app si allineano a questo documento, non l'uno all'altro.

## Regole generali

- Tutti gli endpoint sotto `/api`. Base URL configurabile nell'app.
- Autenticazione: header `Authorization: Bearer <token>` — token del **figlio** per gli endpoint del figlio, del **genitore** per quelli del genitore. `401` token assente o ignoto, `403` token valido ma del ruolo sbagliato.
- **`ts_device` = epoch in millisecondi UTC (numero intero).** È SOLO informativo: fa sempre fede `ts_server` (ISO 8601 UTC) assegnato dal server alla ricezione (architettura.md).
- Tolleranza evolutiva: il server ignora i campi che non conosce; l'app ignora i campi sconosciuti nelle risposte.

## Endpoint del figlio

### POST /api/battito
Il battito "sono viva", ogni ~15 minuti.
```json
{ "ts_device": 1784056519000, "versione_app": "0.1.0", "elapsed_realtime": 123456789, "batteria": 87 }
```
- `batteria` opzionale (0-100). `elapsed_realtime` = millisecondi dall'ultimo avvio del telefono (serve alle euristiche su riavvii/orologio).
- Risposta `200`: `{ "ricevuto": true }`.

### POST /api/eventi
Batch di eventi del registro.
```json
{ "eventi": [
  { "id": "550e8400-e29b-41d4-a716-446655440000",
    "tipo": "uso_giornaliero",
    "ts_device": 1784056519000,
    "dettagli": { "giorno": "2026-07-14", "uso_minuti": { "com.instagram.android": 42 }, "totale_minuti": 137 } }
] }
```
- `id`: UUID generato dall'app alla creazione dell'evento — **chiave di idempotenza**: un id già visto non viene reinserito.
- `tipo` ∈ `uso_giornaliero · siti_giornalieri · riavvio · manomissione · sforamento · bonus_usato · dichiarazione`.
- `dettagli` per tipo:
  - **uso_giornaliero** — fotografia cumulativa del giorno: `{ "giorno": "YYYY-MM-DD", "uso_minuti": {package: minuti}, "totale_minuti": n, "nomi": {package: "TikTok"}, "uso_categorie": {"categoria:social": n} }`. (v2.2) `nomi` = etichette leggibili risolte sul telefono del figlio (il genitore non può risolvere i pacchetti); `uso_categorie` = totali per categoria calcolati dall'app col suo mapping interno — entrambi opzionali per tolleranza evolutiva. Il `giorno` è il giorno locale del telefono e deve essere una data reale `YYYY-MM-DD` (altrimenti la fotografia resta solo nel registro, senza indicizzare la vigente). La fotografia **vigente** per un `giorno` è **monotona su `totale_minuti`**: una fotografia con `totale_minuti` inferiore a quella vigente non la sovrascrive (protegge da consegne fuori ordine; `totale_minuti` mancante o non valido vale 0). Il registro eventi conserva comunque ogni fotografia ricevuta.
  - **siti_giornalieri** (v2.3) — fotografia cumulativa del giorno per i **siti visitati**, stessa filosofia di `uso_giornaliero`: `{ "giorno": "YYYY-MM-DD", "domini": {"instagram.com": 12, "youtube.com": 5}, "totale_domini": 2, "dns_cifrato": false }`. Il numero è **quante volte quel dominio è stato richiesto** nel giorno (richieste osservate, non minuti e non "sessioni"). Il `giorno` è il giorno locale del telefono e deve essere una data reale `YYYY-MM-DD` (altrimenti la fotografia resta solo nel registro, senza indicizzare la vigente). Le chiavi di `domini` sono **domini registrabili in minuscolo** (`scontent.cdninstagram.com` → `instagram.com`, v. la sezione "Siti visitati"); le voci con valore non intero o negativo si scartano (una fotografia sporca non deve far crollare la finestra). L'app manda al massimo i **200 domini più richiesti** del giorno, ma `totale_domini` resta il conteggio VERO dei domini distinti: se la lista è tagliata la differenza si vede (`totale_domini` > lunghezza della lista), non si finge. `totale_domini` mancante o non valido = numero di chiavi valide in `domini` (0 se manca anche `domini`).
    La fotografia **vigente** per un `giorno` è **monotona** sulla coppia `(totale_domini, somma delle richieste)`: una fotografia con valori inferiori a quella vigente non la sovrascrive (protegge da consegne fuori ordine); a parità di entrambi vince la più recente. Il registro eventi conserva comunque ogni fotografia ricevuta.
    **`dns_cifrato`**: `true` quando l'app ha rilevato che il DNS cifrato (DoH/DoT) le ha impedito di vedere i domini in quel periodo. È un **DATO, non un errore**: il registro dichiara di non aver potuto vedere invece di fingere zero traffico. È **appiccicoso sul giorno**: se una qualsiasi fotografia del giorno lo dichiara `true`, il giorno resta `dns_cifrato: true` anche quando la fotografia vigente è un'altra — la monotonia impedisce ai numeri di andare indietro, l'appiccicosità impedisce alla confessione di sparire.
    **Nessuna notifica e nessun semaforo**: un sito visitato non è uno sforamento e non viene mai trattato come tale.
  - **riavvio** — `{ }`. Marca l'azzeramento di `elapsed_realtime`; NON è una manomissione.
  - **manomissione** — `{ "sotto_tipo": "cambio_ora" | "cambio_fuso" | "silenzio" | altro, "drift_secondi": n? , "regola_id": n? }`.
  - **sforamento** — `{ "regola_id": n, "limite_efficace"?: n, "minuti_oltre"?: n, ... }` (dettagli liberi in più). Generato **sul telefono** dal valutatore locale (tappa 5): massimo UN sforamento per regola per giorno (fuso del telefono); per le `limite_tempo` il limite efficace del giorno = `minuti_al_giorno` + bonus concessi oggi su quella regola. (v2.1) Per le `fascia_oraria` che scavalcano la mezzanotte l'unità di dedup è **l'occorrenza**, identificata dal giorno di **ancoraggio** (quando la fascia parte): la coda mattutina e la testa serale della stessa notte sono un solo sforamento, non due.
  - **bonus_usato / dichiarazione** — riservati: il bonus autoritativo passa SOLO da `POST /api/bonus`, le dichiarazioni SOLO da `POST /api/dichiarazioni`.
- Risposta `200`: `{ "ricevuti": N, "nuovi": M, "duplicati": K }`.

### POST /api/bonus
L'unico canale con cui il figlio si concede bonus time (i tetti li applica il server).
```json
{ "minuti": 15, "regola_id": 1, "motivo": "..." }
```
- `minuti` ∈ {5, 15, 30}; **`regola_id` obbligatorio** (v2): il bonus allunga una regola `limite_tempo` **attiva** specifica ("mi do +15 su TikTok") — `409 {"errore": "regola_non_valida"}` se la regola non esiste, non è attiva o non è di tipo limite_tempo. `motivo` opzionale.
- `200` con i residui aggiornati; `409 {"errore": "tetto_superato", ...}` se un tetto (giorno o settimana) verrebbe superato, con i residui nel corpo.
- I bucket giorno/settimana si calcolano nel **fuso del patto** (config server `PACTUM_TIMEZONE`, default `Europe/Rome`); i timestamp restano in UTC ISO.
- Effetto sulla valutazione locale: il limite efficace di quella regola per oggi = `minuti_al_giorno` + bonus concessi oggi su di essa. Le fasce orarie non si allungano col bonus (v2).

### GET /api/patto
Lo stato completo del patto per il sync dell'app del figlio, in una risposta sola. Risposta `200`:
```json
{
  "regole": [ { "id": 1, "tipo": "limite_tempo", "parametri": { }, "attiva": true,
                "creata_ts": "…", "ultima_modifica_ts": "…", "allentabile_dal": "…" } ],
  "bonus": { "giorno": { "usati": 15, "tetto": 30, "residui": 15 },
             "settimana": { "usati": 15, "tetto": 90, "residui": 75 } },
  "bonus_oggi_per_regola": { "1": 15 },
  "proposte_pendenti": [ ],
  "dichiarazioni_in_attesa": [ ],
  "siti_recenti": [ ],
  "striscia": [ { "data": "2026-07-07", "stato": "verde" } ],
  "fuso": "Europe/Rome"
}
```
- `regole`: solo le **attive** (il patto vigente), stessa forma della finestra ma senza semaforo.
- **`striscia`** (v2.4, redesign C2): **identica**, voce per voce, alla `striscia` di `GET /api/finestra`, calcolata dalla stessa funzione del server. È la striscia grande degli 8 giorni che le due app mostrano in cima. Il figlio ci calcola **in locale** la serie di giorni di fila e il record, che non hanno campo nel contratto e non arrivano mai al genitore (tavola rotonda D3/C3).
- `bonus_oggi_per_regola`: minuti bonus concessi OGGI per regola (chiave = regola_id come stringa) — serve al valutatore locale per il limite efficace.
- `proposte_pendenti` / `dichiarazioni_in_attesa`: stesse forme delle sezioni sotto.
- **`siti_recenti`** (v2.3): **identico**, campo per campo, al `siti_recenti` di `GET /api/finestra` — stessi 8 giorni, stessa forma, stesso ordinamento, calcolato dalla stessa funzione del server. È il principio della tavola rotonda: **niente esiste nella finestra del genitore che il figlio non veda identico**. Se i due campi divergono è un bug del contratto, non una scelta di prodotto.

### POST /api/dichiarazioni
Le dichiarazioni del figlio sulle regole di vita reale.
```json
{ "regola_id": 3, "esito": "successo", "nota": "…", "giorno": "2026-07-15" }
```
- `regola_id` deve essere una regola `vita_reale` **attiva**; `esito` ∈ `successo · fallimento`; `nota` opzionale; `giorno` opzionale (default: oggi nel fuso del patto), max UNA dichiarazione per regola per giorno → `409 {"errore": "gia_dichiarato"}` (vincolo garantito anche sotto richieste concorrenti).
- (v2.1) `giorno` non può essere nel futuro né più vecchio di **7 giorni** (fuso del patto) → `409 {"errore": "giorno_non_valido"}`.
- (v2.1) L'`arbitro_nome` della regola viene **congelato sulla dichiarazione** alla creazione: i verdetti "per conto di" citano l'arbitro di allora, anche se la regola cambia dopo.
- **Fallimento** → stato `registrata` (creduto sulla parola, va a registro), notifica al genitore.
- **Successo** → stato `in_attesa` (serve il verdetto del genitore/arbitro), notifica al genitore.
- Risposta `200` con la dichiarazione creata:
```json
{ "id": 5, "regola_id": 3, "giorno": "2026-07-15", "esito": "successo", "nota": null,
  "stato": "in_attesa", "ts_server": "…", "verdetto": null }
```
- `stato` ∈ `registrata · in_attesa · confermata · confermata_per_conto · ribaltata`.

### GET /api/dichiarazioni (figlio E genitore)
Le dichiarazioni, dalla più recente, max 50. Risposta `200`: `{ "dichiarazioni": [ … ] }` (stessa forma sopra; `verdetto` = `{ "verdetto": "conferma_per_conto", "nota": null, "registro": "confermato dal genitore per conto di Nonna", "ts_server": "…" }` quando emesso — `registro` (v2.1) è la frase autoritativa congelata dal server: le app mostrano QUELLA, non la ricostruiscono).

### Regole — GET /api/regole · POST /api/regole · PATCH /api/regole/{id} · DELETE /api/regole/{id}
- Tipi: `limite_tempo {app_o_categoria, minuti_al_giorno}` · `fascia_oraria {dalle:"HH:MM", alle:"HH:MM", giorni:[lun..dom]}` · `vita_reale {descrizione, arbitro_nome, frequenza}`.
- (v2.1) Convenzione `app_o_categoria`: un **nome pacchetto Android** (es. `com.instagram.android`, scelto da un selettore delle app installate — mai testo libero) oppure una **chiave di categoria** tra `categoria:social · categoria:giochi · categoria:video · categoria:musica · categoria:altro`. Il valutatore locale fa il match esatto sul pacchetto o sulla categoria (mapping interno all'app).
- PATCH: corpo `{ "parametri": {...}, "proposta_id": n? }` — `proposta_id` (proposta accettata, monouso) bypassa il lock dei 4 giorni (modifica concordata). La modifica concordata applica **esattamente** i parametri della proposta: se `parametri` non coincide con i `parametri_proposti` della proposta → `409 {"errore": "parametri_non_concordati"}` e la proposta NON viene consumata. Su una modifica che STRINGE, `proposta_id` viene ignorato (niente consumo, `concordata=false`).
- Lock asimmetrico: modifica che ALLENTA entro 4 giorni dall'ultima creazione/modifica → `409` con i secondi residui; modifica che STRINGE → subito.
- DELETE = allentamento massimo (stesso lock) e soft-delete; eliminare l'ultima regola attiva → `409 errore=ultima_regola`. DELETE concordato: `?proposta_id=n` vale solo se i `parametri_proposti` della proposta sono il marcatore `{"azione": "elimina"}`, altrimenti `409 parametri_non_concordati` senza consumo.
- Nota v2: il flusso PATCH/DELETE con `proposta_id` resta valido, ma il percorso primario delle modifiche concordate è **l'accettazione della proposta, che applica da sola la modifica** (v. Proposte).

### POST /api/proposte/{id}/risposta
La risposta del figlio a una proposta del genitore.
```json
{ "esito": "accetta", "motivazione": "…" }
```
- `esito` ∈ `accetta · rifiuta`; `motivazione` opzionale. Solo su proposte `pendenti` → `409 {"errore": "proposta_non_pendente"}` altrimenti. La risposta è atomica anche sotto richieste concorrenti (due "accetta" simultanei: uno solo applica).
- (v2.1) L'eliminazione diretta di una regola **annulla** le sue proposte pendenti (stato `annullata`, notifica al genitore); rispondere a una proposta annullata → `409 proposta_non_pendente`.
- **`accetta` APPLICA subito la modifica** lato server, atomicamente (modifica concordata: lock bypassato, parametri esattamente quelli proposti, `concordata=true` nello storico; eliminazione se il marcatore è `{"azione": "elimina"}` — vale il vincolo `ultima_regola`). Niente secondo passaggio dall'app.
- Notifica al genitore (`tipo: proposta_risposta`) in entrambi i casi.
- Risposta `200` con la proposta aggiornata e, su accettazione, la regola risultante (`"regola": {…}` oppure `"regola": null` se eliminata).

## Endpoint del genitore

Tutti con il token del **genitore**. Ogni `ts_server` è ISO 8601 UTC con offset esplicito e secondi interi (es. `"2026-07-14T09:00:00+00:00"`): l'app lo mostra nel fuso del telefono.

### GET /api/finestra
La finestra: tutto ciò che riguarda il patto in una risposta sola. Risposta `200`, forma esatta:
```json
{
  "regole": [
    { "id": 1,
      "tipo": "limite_tempo",
      "parametri": { "app_o_categoria": "com.zhiliaoapp.musically", "minuti_al_giorno": 60 },
      "nome": "TikTok",
      "attiva": true,
      "creata_ts": "2026-07-14T09:00:00+00:00",
      "ultima_modifica_ts": "2026-07-14T09:00:00+00:00",
      "allentabile_dal": "2026-07-18T09:00:00+00:00",
      "semaforo": [ { "data": "2026-07-07", "stato": "verde" } ] }
  ],
  "sforamenti_recenti": [
    { "id": "550e8400-e29b-41d4-a716-446655440000",
      "tipo": "sforamento",
      "dettagli": { "regola_id": 1 },
      "ts_device": 1784056519000,
      "ts_server": "2026-07-14T10:00:00+00:00" }
  ],
  "manomissioni_recenti": [ ],
  "storico_modifiche": [
    { "id": 2, "regola_id": 1, "azione": "modifica", "direzione": "allenta",
      "prima": { "app_o_categoria": "TikTok", "minuti_al_giorno": 60 },
      "dopo":  { "app_o_categoria": "TikTok", "minuti_al_giorno": 90 },
      "concordata": true, "ts_server": "2026-07-14T11:00:00+00:00" }
  ],
  "bonus": {
    "giorno":    { "usati": 15, "tetto": 30, "residui": 15 },
    "settimana": { "usati": 15, "tetto": 90, "residui": 75 }
  },
  "bonus_giornalieri": [ { "giorno": "2026-07-07", "minuti": 0 } ],
  "stato_silenzio": { "ultimo_battito": "2026-07-14T10:00:00+00:00", "silente": false },
  "medie": { "settimana": { "minuti": 131, "giorni": 7 }, "mese": { "minuti": 118, "giorni": 30 } },
  "striscia": [ { "data": "2026-07-07", "stato": "verde" } ],
  "segno_oggi": false
}
```
- **`striscia`** (v2.4, redesign B1/C2): la striscia **aggregata** del patto, 8 voci (gli stessi 8 giorni del semaforo), dal più vecchio a oggi. `stato` ∈ `verde · rosso · grigio` e si ricava dai `semaforo` di **tutte** le regole (anche le eliminate, per i giorni in cui erano in vita): `rosso` se almeno una regola è `rosso` quel giorno; altrimenti `verde` se almeno una è `verde`; altrimenti `grigio` (nessuna regola in vita o nessun dato). Esce dalla **stessa funzione** che alimenta la `striscia` di `GET /api/patto`: le due app mostrano la stessa striscia per costruzione. La frase "6 su 7" è una funzione pura della striscia, calcolata nelle app (i `grigio` escono dal denominatore).
- **`segno_oggi`** (v2.4, redesign C7): `true` se il genitore ha già mandato il segno di riconoscimento oggi (fuso del patto). Serve all'app per spegnere il pulsante.
- **`regole`**: TUTTE le regole, anche le eliminate (`attiva=false`) — la finestra mostra la storia, mentre `GET /api/regole` (il patto vigente) mostra solo le attive. Ordinate per `id` crescente. `allentabile_dal` = `ultima_modifica_ts` + 4 giorni (il lock asimmetrico, informativo per il genitore).
- **`nome`** (S2, solo nella finestra): per le regole `limite_tempo` il cui `app_o_categoria` è un pacchetto Android, il server allega un `nome` leggibile — l'etichetta più recente vista per quel pacchetto nelle fotografie `uso_giornaliero` (fallback: il pacchetto stesso) — così il genitore legge "TikTok" e non `com.zhiliaoapp.musically`. Il campo è **assente** per le regole di categoria (`app_o_categoria` = `categoria:*`): la traduce l'app. Assente anche in `GET /api/regole` (solo la finestra lo aggiunge).
- **`semaforo`**: 8 voci per regola (oggi + i 7 giorni precedenti), dal più vecchio a oggi (oggi in coda). `stato` ∈ **solo `verde` / `rosso` / `grigio`** (niente `giallo`). Per `limite_tempo` e `fascia_oraria`: `rosso` = almeno uno sforamento della regola nel giorno; `grigio` = giorno prima della creazione oppure giorno **strettamente** successivo all'eliminazione (il giorno stesso dell'eliminazione non è grigio); `verde` = il resto. (v2.1) Per le regole **`vita_reale`**: `verde` = dichiarazione **confermata** (anche per conto) nel giorno; `rosso` = **fallimento dichiarato** o successo **ribaltato**; `grigio` = nessuna dichiarazione o verdetto ancora in attesa. Il rosso di un fallimento dichiarato fotografa il fatto, non punisce l'onestà: l'onestà è visibile perché la dichiarazione l'ha fatta il figlio.
  (v2.4) **Niente verde senza dati.** Per `limite_tempo` e `fascia_oraria` un giorno senza sforamenti è `verde` solo se per quel giorno è arrivata almeno una fotografia `uso_giornaliero`. Senza fotografia è `grigio`: il telefono non ha raccontato niente, e un giorno di cui non si sa nulla non può figurare come mantenuto (tavola rotonda §3.4: "vuoto se non c'erano dati"). Il `rosso` resta `rosso` anche senza fotografia, perché lo sforamento è già un dato.
- **`sforamenti_recenti` / `manomissioni_recenti`**: eventi del registro (stessa forma di POST /api/eventi + `ts_server`), max 20 ciascuno, dal più recente. `ts_device` può essere `null`.
- **`storico_modifiche`**: max 50, dal più recente. `azione` ∈ `creazione · modifica · eliminazione`; `direzione` ∈ `allenta · stringe` per le modifiche, `allenta` per le eliminazioni, `null` per le creazioni; `prima`/`dopo` = i parametri della regola (`prima=null` su creazione, `dopo=null` su eliminazione); `concordata=true` solo se nata da proposta accettata.
- **`bonus`**: contatori del giorno e della settimana ISO (lun–dom) nel fuso del patto, dalla tabella bonus autoritativa.
- **`bonus_giornalieri`**: riepilogo globale (non per regola) dei minuti bonus concessi in ciascun giorno della stessa finestra di 8 giorni, dal più vecchio a oggi, `minuti: 0` esplicito nei giorni senza bonus — dalla tabella bonus autoritativa (`POST /api/bonus`), non dagli eventi.
- **`stato_silenzio`**: `silente` = nessun battito da > 45 minuti (calcolato in lettura sull'orologio del server); `ultimo_battito` è il `ts_server` dell'ultimo battito, `null` se non è mai arrivato niente (⇒ `silente=true`).
- **`medie`** (S2.3): media dei minuti d'uso (`totale_minuti`) sui **SOLI** giorni con una fotografia, in due finestre — `settimana` = ultimi 7 giorni locali, `mese` = ultimi 30 — contate nel fuso del patto (come il resto della finestra). Ogni sotto-oggetto: `{ "minuti": <intero>, "giorni": <quanti giorni della finestra avevano dati> }`; `minuti` è la media **arrotondata all'intero**. Se nella finestra non c'è nessuna fotografia il sotto-oggetto è **`null`** (mai uno zero finto: "nessun dato" è un'informazione). Un giorno con `totale_minuti: 0` è una fotografia reale (uso zero minuti) e **conta** nella media. Le medie usano `AVG(totale_minuti)`: i giorni assenti non sono righe, quindi non abbassano la media. Retro-compatibile: se il campo manca (server vecchio) l'app nasconde la riga.
- **`uso_recente`** (v2.2, deciso da Andrea il 15/07 su richiesta del padre: il genitore vede i tempi di TUTTE le app, non solo di quelle coi limiti): 8 voci dal più vecchio a oggi, dalla fotografia `uso_giornaliero` vigente —
```json
{ "giorno": "2026-07-15", "totale_minuti": 192, "aggiornato_ts": "…",
  "app": [ { "chiave": "com.zhiliaoapp.musically", "nome": "TikTok", "minuti": 65, "limite": 60, "regola_id": 1 } ],
  "categorie": [ { "chiave": "categoria:social", "minuti": 130, "limite": 120, "regola_id": 4 } ] }
```
  `app` ordinate per minuti decrescenti; `nome` = etichetta dai `nomi` della fotografia (fallback: il pacchetto); `limite`/`regola_id` presenti SOLO dove una regola `limite_tempo` **attiva** combacia esattamente con la `chiave` (limite base: gli eventuali bonus del giorno sono già visibili in `bonus_giornalieri`); `categorie` dai totali `uso_categorie`. Un giorno senza fotografia ha `totale_minuti: null` e liste vuote — MAI uno zero finto: "nessun dato ricevuto" è un'informazione.
- **`siti_recenti`** (v2.3, deciso da Andrea col padre il 01/08: il genitore vede QUALI siti, mai cosa ci fa dentro): 8 voci — gli stessi 8 giorni del semaforo e di `uso_recente` — dal più vecchio a oggi, dalla fotografia `siti_giornalieri` vigente di ciascun giorno —
```json
{ "giorno": "2026-08-01", "totale_domini": 37, "dns_cifrato": false, "aggiornato_ts": "…",
  "domini": [ { "dominio": "instagram.com", "visite": 128 },
              { "dominio": "youtube.com", "visite": 54 } ] }
```
  `domini` ordinati per `visite` **decrescenti** (a parità, per dominio in ordine alfabetico: l'ordine è deterministico, le due app mostrano la stessa lista); `visite` = quante volte quel dominio è stato richiesto nel giorno; `totale_domini` = quanti domini distinti in tutto (può essere maggiore della lunghezza della lista se la fotografia era tagliata ai primi 200); `aggiornato_ts` = `ts_server` della fotografia vigente. Un giorno senza fotografia ha `domini: []`, **`totale_domini: null`**, `dns_cifrato: false`, `aggiornato_ts: null` — MAI uno zero finto: `null` dice "nessuna fotografia arrivata", non "zero siti". `dns_cifrato: true` con dei domini elencati significa "questi li ho visti, ma per un pezzo di giornata ero cieco". Nessun URL, nessun contenuto, nessuna ricerca, nessun orario: v. **Siti visitati — limiti e patto etico**. Retro-compatibile: se il campo manca (server vecchio) l'app nasconde la sezione.
- I giorni della finestra (semaforo e `bonus_giornalieri`) si contano nel fuso del patto (`PACTUM_TIMEZONE`, default `Europe/Rome`).

### POST /api/proposte
Il genitore propone una modifica (mai la impone). Il server calcola il **confronto col valore attuale** che comparirà nella notifica al figlio.
```json
{ "regola_id": 1, "parametri_proposti": { "app_o_categoria": "TikTok", "minuti_al_giorno": 60 }, "motivazione": "…" }
```
- `parametri_proposti` validati contro il tipo della regola, OPPURE il marcatore `{"azione": "elimina"}`. Regola inesistente o non attiva → `409 {"errore": "regola_non_valida"}`. Una sola proposta `pendente` per regola → `409 {"errore": "proposta_gia_pendente"}`.
- Il server calcola `confronto`: testo in italiano semplice della differenza rispetto ad ora (es. `"−30 min al giorno rispetto ad ora"` per limite_tempo; per fascia_oraria la differenza di copertura; `"propone di eliminare la regola"` per il marcatore) e `direzione` ∈ `allenta · stringe · elimina`.
- Notifica al figlio (`tipo: nuova_proposta`, il `confronto` dentro il messaggio e il payload).
- Risposta `200` con la proposta creata:
```json
{ "id": 4, "regola_id": 1, "parametri_proposti": { }, "motivazione": "…",
  "confronto": "−30 min al giorno rispetto ad ora", "direzione": "stringe",
  "stato": "pendente", "usata": false, "ts_server": "…", "risposta": null }
```
- `stato` ∈ `pendente · accettata · rifiutata · annullata` (v2.1); `usata=true` quando la modifica concordata è stata applicata (con l'auto-applicazione avviene insieme all'accettazione). `risposta` = `{ "esito", "motivazione", "ts_server" }` quando il figlio risponde. Una sola `pendente` per regola, garantito anche sotto richieste concorrenti.

### GET /api/proposte (figlio E genitore)
Le proposte, dalla più recente, max 50. Risposta `200`: `{ "proposte": [ … ] }` (stessa forma sopra).
- (v2.1) Per le proposte **pendenti** il `confronto` (e la `direzione`) sono **ricalcolati a ogni lettura** rispetto ai parametri attuali della regola — qui e in `/api/patto` — così il figlio decide sempre su un confronto vero anche se la regola è cambiata dopo la proposta. Per le proposte chiuse resta il confronto del momento della risposta.

### POST /api/dichiarazioni/{id}/verdetto
Il verdetto del genitore su una dichiarazione di successo `in_attesa` (`409 {"errore": "dichiarazione_non_in_attesa"}` altrimenti).
```json
{ "verdetto": "conferma_per_conto", "nota": "ho sentito la nonna al telefono" }
```
- `verdetto` ∈ `conferma` (l'arbitro è il genitore o ha confermato nell'app) · `conferma_per_conto` (il genitore garantisce di aver sentito l'arbitro fuori dall'app — nel registro resta *"confermato dal genitore per conto di [arbitro_nome]"*) · `ribalta` (il successo dichiarato non regge; la dichiarazione conta come fallimento).
- Notifica al figlio (`tipo: verdetto`). Risposta `200` con la dichiarazione aggiornata.

### POST /api/segno (v2.4, redesign C7)
Il gesto non poliziesco del genitore: un riconoscimento al figlio a **testo fisso**, al massimo **uno al giorno**.
- Nessun corpo. Il testo NON lo sceglie il genitore: un canale libero dal genitore al figlio dentro un'app basata sulla fiducia diventa in fretta un canale di pressione, e la conversazione vera sta fuori dall'app (tavola rotonda, FinestraScreen).
- Il server crea una notifica per il **figlio** con `tipo: segno`, `messaggio: "Ho visto la settimana. Bene così."`, `payload: {}`.
- `200 { "mandato": true, "ts_server": "…" }`. `409 {"errore": "segno_gia_mandato"}` se oggi (fuso del patto) ne è già partito uno, garantito anche sotto richieste concorrenti.
- Non va nel registro degli eventi: è un gesto del genitore, non un fatto del patto.

### GET /api/versione (nessun auth) — tappa 6
Per l'auto-aggiornamento: l'ultima versione disponibile di ciascuna app.
```json
{ "figlio":   { "versione_code": 2, "versione_nome": "0.2.0", "url": "/scarica/pactum-figlio.apk", "note": "…" },
  "genitore": { "versione_code": 2, "versione_nome": "0.2.0", "url": "/scarica/pactum-genitore.apk", "note": "…" } }
```
- L'app confronta `versione_code` col proprio `versionCode`: se il server è maggiore, scarica `url` (relativo al base del server) e lancia l'installazione (PackageInstaller). `note` = changelog breve, opzionale.
- Nessun auth: è solo metadata pubblico + il download dell'APK; la firma dell'APK (stessa chiave) è la vera garanzia d'integrità.

### GET /scarica (nessun auth) — tappa 6
Pagina HTML statica in italiano servita dal postino: i due APK con pulsanti di download, le istruzioni di installazione (avviso Play Protect, "consenti impostazioni con limitazioni" per l'accesso all'uso), e quale app installa chi. `GET /scarica/pactum-figlio.apk` e `GET /scarica/pactum-genitore.apk` servono i file.

### GET /api/notifiche
Le notifiche **non lette** (polling; ogni notifica ha un `tipo` macchina-leggibile), ordinate per `id` crescente. Risposta `200`:
```json
{ "notifiche": [
  { "id": 7,
    "tipo": "sforamento",
    "messaggio": "Evento sforamento registrato",
    "payload": { "evento_id": "…", "dettagli": { "regola_id": 1 } },
    "ts_server": "2026-07-14T10:00:00+00:00" }
] }
```
- `id`: intero autoincrementale del server — chiave per la marcatura come letta e per non ri-avvisare due volte lato app.
- `destinatario` (v2): ogni notifica nasce per un destinatario — `figlio` o `genitore` — e ciascuno riceve SOLO le proprie (il GET filtra sul ruolo del token; anche l'app del figlio ora legge le notifiche, es. nuove proposte e verdetti).
- `tipo` ∈ `sforamento · manomissione · bonus · modifica_regola · nuova_proposta · proposta_risposta · dichiarazione · verdetto · segno` (le app tollerano tipi nuovi). `segno` (v2.4) è destinato al figlio, con `payload: {}`.
- `payload` per tipo: **sforamento/manomissione** `{evento_id, dettagli}` (l'evento del registro che l'ha generata); **bonus** `{minuti, regola_id, motivo, residuo_giorno, residuo_settimana}`; **modifica_regola** `{regola_id, azione, …}` con `parametri` su creazione, `direzione/concordata/prima/dopo` su modifica, `concordata/prima` su eliminazione; **nuova_proposta** `{proposta_id, regola_id, confronto, direzione}`; **proposta_risposta** `{proposta_id, regola_id, esito}`; **dichiarazione** `{dichiarazione_id, regola_id, esito, giorno}`; **verdetto** `{dichiarazione_id, regola_id, verdetto}`.
- POST /api/notifiche/{id}/letta funziona per il proprio ruolo: ciascuno può marcare come lette solo le notifiche a lui destinate (`404` sulle altrui).

### POST /api/notifiche/{id}/letta
Marcatura come letta (gesto del genitore nell'app, NON del polling automatico).
- `200`: `{ "id": 7, "letta": true }`.
- `404` se l'id non esiste: `{ "detail": "notifica non trovata" }`. Rimarcare una notifica già letta risponde `200` (idempotente).

## Siti visitati — limiti e patto etico (v2.3)

Questa sezione è parte del contratto quanto gli endpoint: descrive **cosa il protocollo può dire e cosa non dirà mai** sui siti. Chi implementa non può allargarla senza passare da qui.

### Cosa si vede (e solo quello)
Il **dominio registrabile** e quante volte è stato richiesto in un giorno. Punto: `instagram.com: 128 il 01/08`.

### I limiti (fanno parte del contratto, non sono scuse)
1. **Solo domini, mai il resto.** Con HTTPS tutto ciò che sta dopo il nome del sito — percorso, parametri, contenuto, ricerche — viaggia cifrato: non lo vede nemmeno chi osserva il traffico dal telefono con una VPN locale. Si vede che è stato chiesto `youtube.com`, **non quale video**. Non è una scelta di prodotto reversibile: è come funziona la rete.
2. **Il DNS cifrato può rendere ciechi.** Se il telefono o il browser risolvono i nomi via DoH/DoT, le richieste sono cifrate e l'app smette di vedere i domini. In quel caso la fotografia porta `dns_cifrato: true` e il registro **dichiara di non aver visto** invece di raccontare una giornata a zero traffico. Un buco dichiarato vale più di un numero comodo.
3. **I domini tecnici non entrano.** CDN, analytics, telemetria, pubblicità, aggiornamenti di sistema e simili vengono **filtrati sul telefono del figlio**, prima dell'invio: nel registro finisce ciò che una persona riconoscerebbe come "un sito che ho visitato", non il rumore di rete. Il filtro vive nell'app del figlio, quindi il figlio può vederlo e discuterlo.
4. **Aggregazione sul dominio registrabile.** `scontent.cdninstagram.com` → `instagram.com`, `m.youtube.com` → `youtube.com` (regola: dominio registrabile secondo la Public Suffix List, in minuscolo). Un sottodominio non diventa mai una riga a sé: elencare i sottodomini direbbe cose sul contenuto, e il contenuto è fuori dal patto.
5. **Granularità: il giorno.** Nessun orario, nessuna sequenza, nessuna durata. "128 richieste il 1 agosto", mai "alle 23:14".
6. **Se manca, manca.** Un giorno senza fotografia resta con `totale_domini: null`: telefono spento, app ferma o osservazione non attiva si vedono tutti come **assenza**, mai come zero. La cecità dichiarata (`dns_cifrato`) e l'assenza di dati sono due informazioni diverse e restano distinte.

### Il patto etico
- **Il genitore vede, non blocca.** Non esiste — e non esisterà — nessun endpoint per bloccare, filtrare o limitare un sito. Pactum non blocca niente: se un sito è un problema, il problema si affronta parlando (concept.md, *"testimone, non carceriere"*).
- **Il figlio vede la stessa lista.** `GET /api/patto` restituisce `siti_recenti` **identico** a `GET /api/finestra`: nessuna riga esiste solo dalla parte del genitore. È il principio della tavola rotonda applicato alla lettera.
- **Nessun URL completo, nessun contenuto, nessuna ricerca.** Mai, per contratto. Aggiungerli non sarebbe una v2.4: sarebbe un'altra app.
- **Niente di nascosto.** L'osservazione dei domini vive nell'app del figlio e si vede nell'app del figlio: è il **suo** registro, che lui condivide, non una registrazione fatta su di lui.
- **I siti non sono infrazioni.** `siti_giornalieri` non genera notifiche, non entra nel semaforo, non produce sforamenti. È materiale per una conversazione, non per un verdetto.

---
**Versione: v2.3 — 01/08/2026** (decisione di Andrea col padre): il genitore **vede** i siti visitati dal figlio, senza poterli bloccare — nuovo evento `siti_giornalieri` (fotografia cumulativa del giorno, monotona, `dns_cifrato` appiccicoso come dichiarazione di cecità), `siti_recenti` in `GET /api/finestra` **e identico** in `GET /api/patto` (tavola rotonda), limiti del dato e patto etico messi per iscritto nella sezione "Siti visitati". Solo domini, mai URL, contenuti o ricerche.
**v2.2 — 15/07/2026** (richieste di Andrea + feedback del padre): fotografia uso_giornaliero con `nomi` e `uso_categorie`; finestra con `uso_recente` (tempi di TUTTE le app, 8 giorni, limiti accanto dove esistono, mai zeri finti). Il digest giornaliero del genitore (notifica all'ora scelta con totale + prime app) è comportamento dell'app genitore, nessun endpoint nuovo.
**v2.1 — 15/07/2026** (dopo revisione adversariale tappa 5): atomicità garantita su risposta-proposta/regole/dichiarazioni concorrenti; proposte `annullata` all'eliminazione della regola; confronto ricalcolato in lettura per le pendenti; `giorno` dichiarazioni vincolato (oggi ↔ −7gg); arbitro congelato sulla dichiarazione; campo `verdetto.registro`; semaforo per le `vita_reale`; convenzione `app_o_categoria` (pacchetto o `categoria:*`).
**v2 — 15/07/2026.** Novità v2 (tappa 5): proposte (creazione col confronto calcolato dal server, risposta del figlio con auto-applicazione delle accettate), dichiarazioni vita reale con verdetto (conferma / per conto di / ribalta), bonus agganciato a una regola limite_tempo (`regola_id` obbligatorio), `GET /api/patto` per il sync del figlio, notifiche con `destinatario` e nuovi tipi, sforamenti generati dal valutatore locale (max 1 per regola per giorno, limite efficace = limite + bonus della regola).
**v1 — 14/07/2026.** Cambi al contratto: prima qui, poi nel codice di entrambi i lati.
