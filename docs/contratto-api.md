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
  - **sforamento** — `{ "regola_id": n, "giorno"?: "YYYY-MM-DD", "limite_efficace"?: n, "minuti_oltre"?: n, ... }` (dettagli liberi in più). (v2.4) **`giorno`** = il giorno locale del telefono in cui è avvenuto lo sforamento (per le fasce che scavalcano la mezzanotte, il giorno di ancoraggio). Se è una data reale, il semaforo mette lo sforamento in QUEL giorno. Se manca o non è valido, lo mette nel giorno in cui l'evento arriva al server, come prima. Serve perché uno sforamento consegnato in ritardo (telefono offline fino al giorno dopo) non lasci verde il giorno in cui è davvero successo. Generato **sul telefono** dal valutatore locale (tappa 5): massimo UN sforamento per regola per giorno (fuso del telefono); per le `limite_tempo` il limite efficace del giorno = `minuti_al_giorno` + bonus concessi oggi su quella regola. (v2.1) Per le `fascia_oraria` che scavalcano la mezzanotte l'unità di dedup è **l'occorrenza**, identificata dal giorno di **ancoraggio** (quando la fascia parte): la coda mattutina e la testa serale della stessa notte sono un solo sforamento, non due.
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
  "riepilogo": { "giorni_fuori_regola": 1, "interruzioni": 0 },
  "fuso": "Europe/Rome"
}
```
- `regole`: solo le **attive** (il patto vigente), stessa forma della finestra. (v2.4) Ciascuna porta il suo **`semaforo`**, identico a quello della stessa regola in `GET /api/finestra`: il genitore vede la striscia regola per regola, quindi la vede anche il figlio (tavola rotonda D3).
- **`riepilogo`** (v2.4): identico al `riepilogo` di `GET /api/finestra` (v. lì).
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
- (v2.1) Convenzione `app_o_categoria`: un **nome pacchetto Android** (es. `com.instagram.android`, scelto da un selettore delle app installate — mai testo libero) oppure una **chiave di categoria** tra `categoria:social · categoria:giochi · categoria:video · categoria:musica · categoria:altro`. Il valutatore locale fa il match esatto sul pacchetto o sulla categoria (mapping interno all'app). (v3.3) Oppure **`totale`**: tutto il tempo del dispositivo nel giorno (v. la sezione v3.3).
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
  "medie": { "settimana": { "minuti": 131, "giorni": 7, "totale": 917 }, "mese": { "minuti": 118, "giorni": 30, "totale": 3540 } },
  "striscia": [ { "data": "2026-07-07", "stato": "verde" } ],
  "riepilogo": { "giorni_fuori_regola": 1, "interruzioni": 0 },
  "segno_oggi": false
}
```
- **`striscia`** (v2.4, redesign B1/C2): la striscia **aggregata** del patto, 8 voci (gli stessi 8 giorni del semaforo), dal più vecchio a oggi. `stato` ∈ `verde · rosso · grigio` e si ricava dai `semaforo` di **tutte** le regole (anche le eliminate, per i giorni in cui erano in vita): `rosso` se almeno una regola è `rosso` quel giorno; altrimenti `verde` se almeno una è `verde`; altrimenti `grigio` (nessuna regola in vita o nessun dato). Esce dalla **stessa funzione** che alimenta la `striscia` di `GET /api/patto`: le due app mostrano la stessa striscia per costruzione. La frase "6 su 7" è una funzione pura della striscia, calcolata nelle app (i `grigio` escono dal denominatore).
- **`riepilogo`** (v2.4): `{ "giorni_fuori_regola": n, "interruzioni": n }`, cioè la riga sotto la striscia, uguale nelle due app. `giorni_fuori_regola` = voci `rosso` della `striscia`. `interruzioni` = eventi `manomissione` il cui `ts_server`, nel fuso del patto, cade negli stessi 8 giorni. Lo calcola il server, dalla stessa funzione per `GET /api/finestra` e `GET /api/patto`. Le app ricevono al massimo 20 eventi e il giorno va preso nel fuso del patto, non in quello del telefono che legge.
- **`segno_oggi`** (v2.4, redesign C7): `true` se il genitore ha già mandato il segno di riconoscimento oggi (fuso del patto). Serve all'app per spegnere il pulsante.
- **`regole`**: TUTTE le regole, anche le eliminate (`attiva=false`) — la finestra mostra la storia, mentre `GET /api/regole` (il patto vigente) mostra solo le attive. Ordinate per `id` crescente. `allentabile_dal` = `ultima_modifica_ts` + 4 giorni (il lock asimmetrico, informativo per il genitore).
- **`nome`** (S2, solo nella finestra): per le regole `limite_tempo` il cui `app_o_categoria` è un pacchetto Android, il server allega un `nome` leggibile — l'etichetta più recente vista per quel pacchetto nelle fotografie `uso_giornaliero` (fallback: il pacchetto stesso) — così il genitore legge "TikTok" e non `com.zhiliaoapp.musically`. Il campo è **assente** per le regole di categoria (`app_o_categoria` = `categoria:*`): la traduce l'app. (v3.3) Assente anche per le regole sul totale (`app_o_categoria` = `totale`): le app scrivono "Tutto il telefono" / "Tutto il computer" dal tipo del dispositivo. Assente anche in `GET /api/regole` (solo la finestra lo aggiunge).
- **`semaforo`**: 8 voci per regola (oggi + i 7 giorni precedenti), dal più vecchio a oggi (oggi in coda). `stato` ∈ **solo `verde` / `rosso` / `grigio`** (niente `giallo`). Per `limite_tempo` e `fascia_oraria`: `rosso` = almeno uno sforamento della regola nel giorno; `grigio` = giorno prima della creazione oppure giorno **strettamente** successivo all'eliminazione (il giorno stesso dell'eliminazione non è grigio); `verde` = il resto. (v2.1) Per le regole **`vita_reale`**: `verde` = dichiarazione **confermata** (anche per conto) nel giorno; `rosso` = **fallimento dichiarato** o successo **ribaltato**; `grigio` = nessuna dichiarazione o verdetto ancora in attesa. Il rosso di un fallimento dichiarato fotografa il fatto, non punisce l'onestà: l'onestà è visibile perché la dichiarazione l'ha fatta il figlio.
  (v2.4) **Niente verde senza dati.** Per `limite_tempo` e `fascia_oraria` un giorno senza sforamenti è `verde` solo se per quel giorno è arrivata almeno una fotografia `uso_giornaliero`. Senza fotografia è `grigio`: il telefono non ha raccontato niente, e un giorno di cui non si sa nulla non può figurare come mantenuto (tavola rotonda §3.4: "vuoto se non c'erano dati"). Il `rosso` resta `rosso` anche senza fotografia, perché lo sforamento è già un dato.
- **`sforamenti_recenti` / `manomissioni_recenti`**: eventi del registro (stessa forma di POST /api/eventi + `ts_server`), max 20 ciascuno, dal più recente. `ts_device` può essere `null`.
- **`storico_modifiche`**: max 50, dal più recente. `azione` ∈ `creazione · modifica · eliminazione`; `direzione` ∈ `allenta · stringe` per le modifiche, `allenta` per le eliminazioni, `null` per le creazioni; `prima`/`dopo` = i parametri della regola (`prima=null` su creazione, `dopo=null` su eliminazione); `concordata=true` solo se nata da proposta accettata.
- **`bonus`**: contatori del giorno e della settimana ISO (lun–dom) nel fuso del patto, dalla tabella bonus autoritativa.
- **`bonus_giornalieri`**: riepilogo globale (non per regola) dei minuti bonus concessi in ciascun giorno della stessa finestra di 8 giorni, dal più vecchio a oggi, `minuti: 0` esplicito nei giorni senza bonus — dalla tabella bonus autoritativa (`POST /api/bonus`), non dagli eventi.
- **`stato_silenzio`**: `silente` = nessun battito da > 45 minuti (calcolato in lettura sull'orologio del server); `ultimo_battito` è il `ts_server` dell'ultimo battito, `null` se non è mai arrivato niente (⇒ `silente=true`).
- **`medie`** (S2.3): media dei minuti d'uso (`totale_minuti`) sui **SOLI** giorni con una fotografia, in due finestre — `settimana` = ultimi 7 giorni locali, `mese` = ultimi 30 — contate nel fuso del patto (come il resto della finestra). Ogni sotto-oggetto: `{ "minuti": <intero>, "giorni": <quanti giorni della finestra avevano dati> }` (v3.8: più `"totale"`, la somma); `minuti` è la media **arrotondata all'intero**. Se nella finestra non c'è nessuna fotografia il sotto-oggetto è **`null`** (mai uno zero finto: "nessun dato" è un'informazione). Un giorno con `totale_minuti: 0` è una fotografia reale (uso zero minuti) e **conta** nella media. Le medie usano `AVG(totale_minuti)`: i giorni assenti non sono righe, quindi non abbassano la media. Retro-compatibile: se il campo manca (server vecchio) l'app nasconde la riga.
- **`uso_recente`** (v2.2, deciso da Andrea il 15/07 su richiesta del padre: il genitore vede i tempi di TUTTE le app, non solo di quelle coi limiti): 8 voci dal più vecchio a oggi, dalla fotografia `uso_giornaliero` vigente —
```json
{ "giorno": "2026-07-15", "totale_minuti": 192, "aggiornato_ts": "…",
  "app": [ { "chiave": "com.zhiliaoapp.musically", "nome": "TikTok", "minuti": 65, "limite": 60, "regola_id": 1 } ],
  "categorie": [ { "chiave": "categoria:social", "minuti": 130, "limite": 120, "regola_id": 4 } ] }
```
  `app` ordinate per minuti decrescenti; `nome` = etichetta dai `nomi` della fotografia (fallback: il pacchetto); `limite`/`regola_id` presenti SOLO dove una regola `limite_tempo` **attiva** combacia esattamente con la `chiave` (limite base: gli eventuali bonus del giorno sono già visibili in `bonus_giornalieri`); (v2.4) accanto a `limite` c'è sempre **`bonus`**, cioè i minuti concessi QUEL giorno (fuso del patto) su QUELLA regola, `0` se nessuno: "oltre il limite" per il genitore significa oltre `limite + bonus`, come per il figlio; `categorie` dai totali `uso_categorie`. Un giorno senza fotografia ha `totale_minuti: null` e liste vuote — MAI uno zero finto: "nessun dato ricevuto" è un'informazione. (v3.3) Il limite di una regola `totale` sta nella voce del giorno, accanto a `totale_minuti` (`limite`, `regola_id`, `bonus`), mai in `app` o `categorie`: v. la sezione v3.3.
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
- `tipo` ∈ `sforamento · manomissione · bonus · modifica_regola · nuova_proposta · proposta_risposta · proposta_annullata · proposta_ritirata · dichiarazione · verdetto · segno` (le app tollerano tipi nuovi). `segno` (v2.4) è destinato al figlio, con `payload: {}`. `proposta_annullata` (v2.1) va al genitore; `proposta_ritirata` (v3.4) va a chi non ha ritirato. Dalla v3.4 `nuova_proposta` e `proposta_risposta` possono andare a tutti e due: chi le riceve lo dice `autore` nel `payload` (v. la sezione v3.4).
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

### Sul computer (v3, deciso da Andrea il 23/09/2026)
Su Windows 11 un programma senza diritti di amministratore non può leggere le richieste DNS (verificato sul PC di casa: serve l'elevazione). Andrea ha scelto questa strada:
- il programma legge **l'indirizzo nella barra del browser in primo piano** (Chrome, Edge, Firefox) e ne tiene **solo il dominio registrabile**; l'indirizzo completo resta in memoria per un istante e poi si butta. Non si salva, non si manda, non si mostra, mai. Non si leggono mai i titoli delle pagine né quello che c'è dentro;
- in cambio si misura anche **quanto tempo** si passa su ogni sito;
- la promessa quindi è diversa da quella del telefono: sul telefono l'app **non può** vedere le pagine, sul computer il programma **le vede solo per un istante e non le registra mai**. Lo dicono chiaro "Cosa vede tuo padre" e questo contratto; va detto al padre;
- stessa granularità: per giorno, nessun orario, nessuna sequenza;
- se per un browser il programma non riesce a leggere l'indirizzo, lo dichiara (`dns_cifrato: true` sul giorno) invece di fingere zero siti.

### Il patto etico
- **Il genitore vede, non blocca.** Non esiste — e non esisterà — nessun endpoint per bloccare, filtrare o limitare un sito. Pactum non blocca niente: se un sito è un problema, il problema si affronta parlando (concept.md, *"testimone, non carceriere"*).
- **Il figlio vede la stessa lista.** `GET /api/patto` restituisce `siti_recenti` **identico** a `GET /api/finestra`: nessuna riga esiste solo dalla parte del genitore. È il principio della tavola rotonda applicato alla lettera.
- **Nessun URL completo, nessun contenuto, nessuna ricerca.** Mai, per contratto. Aggiungerli non sarebbe una v2.4: sarebbe un'altra app.
- **Niente di nascosto.** L'osservazione dei domini vive nell'app del figlio e si vede nell'app del figlio: è il **suo** registro, che lui condivide, non una registrazione fatta su di lui.
- **I siti non sono infrazioni.** `siti_giornalieri` non genera notifiche, non entra nel semaforo, non produce sforamenti. È materiale per una conversazione, non per un verdetto.

## v3 — Famiglia, figli e dispositivi (deciso da Andrea il 23/09/2026)

Fino alla v2.4 il sistema conosceva **un figlio con un telefono** e **un genitore**. Dalla v3:

- la **famiglia** ha uno o più **figli**;
- ogni figlio ha uno o più **dispositivi**, di tipo `telefono` (Android) o `computer` (un account di Windows su un PC: due fratelli sullo stesso PC con account diversi sono due dispositivi);
- **ogni dispositivo ha regole, tempi, bonus, siti e registro separati**;
- le regole `vita_reale` appartengono al **figlio**, non a un dispositivo;
- la **striscia** degli 8 giorni resta **del figlio**, con la stessa aggregazione della v2.4 applicata a tutte le regole di tutti i suoi dispositivi: un giorno è `rosso` se almeno una regola è rossa, altrimenti `verde` se almeno una è verde, altrimenti `grigio` (nessun dato). Un dispositivo senza dati quel giorno non lo rende grigio né rosso: semplicemente non conta. Accanto c'è la striscia di ciascun dispositivo.

Tutto il resto del contratto resta valido. Questa sezione dice solo cosa cambia.

### Identità e credenziali

- Ogni **dispositivo** e ogni **genitore** hanno il proprio token (casuale, `secrets.token_urlsafe(32)`). Il server salva solo l'**hash SHA-256** del token, mai il token.
- Ruoli: `genitore` (vede tutta la famiglia) e `dispositivo` (agisce per il proprio figlio; è il vecchio ruolo `figlio`). `401` token assente o sconosciuto o revocato, `403` token valido ma del ruolo sbagliato o di un altro figlio.
- **Compatibilità con le app 0.7 già installate**: `PACTUM_TOKEN_GENITORE` resta il token del **genitore 1**; `PACTUM_TOKEN_FIGLIO` resta il token del **dispositivo 1** (`"Telefono"`, tipo `telefono`) del **figlio 1**. Al primo avvio della v3 il server crea queste righe e attacca tutti i dati esistenti al figlio 1 / dispositivo 1 (v. Migrazione). Le app 0.7 continuano a funzionare senza toccare niente. In produzione i due token d'ambiente restano obbligatori e forti come prima.

### Abbinamento con codice (niente più token da copiare)

- `POST /api/figli` (genitore) `{ "nome": "Andrea" }` → `201 { "id", "nome", "creato_ts" }`. `PATCH /api/figli/{id}` `{ "nome" }` per rinominare. Nome 1-40 caratteri.
- `POST /api/figli/{id}/dispositivi` (genitore) `{ "nome": "Computer di camera", "tipo": "computer" }` → `201 { "dispositivo": {…}, "codice": "483920", "scade_ts": "…" }`. Il dispositivo nasce **non abbinato**.
- `POST /api/dispositivi/{id}/codice` (genitore): nuovo codice per un dispositivo già creato (primo abbinamento non riuscito, oppure telefono reinstallato). Genera un token nuovo **al momento dell'abbinamento** e invalida il vecchio: la storia del dispositivo continua.
- Il **codice** è di 6 cifre, casuale, vale **15 minuti**, **una volta sola**. Un nuovo codice per lo stesso dispositivo annulla il precedente.
- `POST /api/abbina` (**nessun auth**) `{ "codice": "483920", "tipo": "computer", "versione_app": "0.8.0" }` → `200 { "token": "…", "dispositivo": { "id", "nome", "tipo" }, "figlio": { "id", "nome" } }`. Il token si restituisce una volta sola: l'app lo conserva.
  - `409 { "errore": "codice_non_valido" }`: sbagliato, scaduto o già usato (stessa risposta per tutti e tre).
  - (v3.1) `tipo` (`telefono` | `computer`, facoltativo ma le app 0.8 lo mandano sempre): se il codice è di un dispositivo di un altro tipo → `409 { "errore": "tipo_non_corrispondente", "tipo_atteso": "telefono" }` e il codice **non** viene consumato. Evita che un computer prenda il posto del telefono (o il contrario) scrivendo il codice sbagliato. Un `tipo` sbagliato conta come tentativo fallito.
  - Contro chi prova i codici a caso: dopo **10 tentativi falliti in 10 minuti** (contati su tutto il server) ogni abbinamento risponde `429 { "errore": "troppi_tentativi", "riprova_tra_secondi": n }` per 10 minuti, anche con un codice giusto.
- `DELETE /api/dispositivi/{id}` (genitore): **revoca** il dispositivo (il suo token smette di funzionare, `401`). Niente si cancella: regole, registro e storia restano. Le regole attive di un dispositivo revocato restano nella storia ma non contano più nella striscia dai giorni successivi alla revoca (come una regola eliminata).

### La famiglia vista dal genitore

`GET /api/famiglia` (genitore) →

```json
{ "figli": [
  { "id": 1, "nome": "Andrea",
    "striscia": [ { "data": "…", "stato": "verde" } ],
    "riepilogo": { "giorni_fuori_regola": 1, "interruzioni": 0 },
    "notifiche_non_lette": 3,
    "dispositivi": [
      { "id": 1, "nome": "Telefono", "tipo": "telefono", "abbinato": true, "revocato": false,
        "versione_app": "0.8.0",
        "stato_silenzio": { "ultimo_battito": "…", "silente": false, "spento": false, "spento_dal": null } } ] } ] }
```

Figli in ordine di `id`, dispositivi in ordine di `id`, revocati compresi (`revocato: true`).

### Quale figlio: il parametro `figlio_id`

Tutti gli endpoint del genitore che riguardano un figlio accettano `figlio_id` (query per i `GET`, corpo per i `POST`): `GET /api/finestra?figlio_id=2`, `POST /api/segno { "figlio_id": 2 }`, `GET /api/proposte?figlio_id=2`, `GET /api/dichiarazioni?figlio_id=2`. **Se manca vale il figlio con l'`id` più basso**: così l'app del genitore 0.7 continua a vedere il primo figlio. Un `figlio_id` inesistente → `404`.

Gli endpoint del dispositivo non hanno `figlio_id`: il figlio è quello del token.

### Regole

- Ogni regola ha `figlio_id` e `dispositivo_id`. `limite_tempo` e `fascia_oraria` appartengono a un dispositivo; `vita_reale` ha `dispositivo_id: null` (è del figlio).
- `POST /api/regole` (dispositivo): `dispositivo_id` facoltativo nel corpo; se manca vale **il dispositivo che chiama**. Deve essere un dispositivo dello stesso figlio (`403` altrimenti). Per `vita_reale` il server lo ignora e mette `null`.
- `GET /api/regole` (dispositivo): le regole attive **del figlio**, di tutti i suoi dispositivi più quelle di vita reale, ciascuna con `dispositivo_id`.
- `PATCH` / `DELETE` su qualsiasi regola **dello stesso figlio** (`403` su quelle di un altro figlio). Il blocco dei 4 giorni resta per regola.
- `ultima_regola` vale **per figlio**: non si può eliminare l'ultima regola attiva del figlio, contando tutti i suoi dispositivi **non revocati** e la vita reale.
- (v3.1) Le regole di un dispositivo **revocato** non si modificano più, né direttamente né con una proposta: `PATCH`, `DELETE` e `POST /api/proposte` rispondono `409 { "errore": "dispositivo_revocato" }`. Restano nella storia.
- `app_o_categoria`:
  - sui **telefoni** resta come prima: nome del pacchetto Android oppure `categoria:*`;
  - sui **computer**: `exe:<nome>` (nome del file del programma, minuscolo, es. `exe:minecraft.exe`), oppure `sito:<dominio>` (dominio registrabile minuscolo, es. `sito:youtube.com`: il tempo passato su quel sito nel browser), oppure `categoria:*`;
  - (v3.3) su **tutti e due** anche `totale`: tutto il tempo del dispositivo nel giorno (v. la sezione v3.3);
  - il server rifiuta con `422` una chiave che non va bene per il tipo del dispositivo della regola.
- Ogni regola nelle risposte porta anche `"dispositivo": { "id", "nome", "tipo" }` (o `null` per la vita reale), così le app scrivono "sul computer" senza un'altra chiamata.

### Bonus: per dispositivo

- `POST /api/bonus` dal dispositivo: `regola_id` deve essere una `limite_tempo` attiva **di quel dispositivo** (`409 regola_non_valida` altrimenti).
- I tetti (30 al giorno, 90 alla settimana) valgono **per dispositivo**. `bonus`, `bonus_oggi_per_regola` e `bonus_giornalieri` sono sempre quelli di un dispositivo.

### Il dispositivo: `GET /api/patto`

Come prima, con queste regole:

- `regole`: quelle attive **di questo dispositivo** più quelle di **vita reale del figlio** (ciascuna col suo `semaforo`);
- `bonus`, `bonus_oggi_per_regola`, `siti_recenti`: **di questo dispositivo**;
- `proposte_pendenti` e `dichiarazioni_in_attesa`: **di tutto il figlio** (una proposta su una regola del computer si vede e si può accettare anche dal telefono);
- `striscia` e `riepilogo`: **del figlio** (tutti i dispositivi, identici alla finestra del genitore);
- in più:
  - `"figlio": { "id", "nome" }`
  - `"dispositivo": { "id", "nome", "tipo" }`
  - `"striscia_dispositivo": [ … ]`: la striscia di questo dispositivo, identica a quella del genitore;
  - `"dispositivi": [ { "id", "nome", "tipo", "striscia": [ … ] } ]`: tutti i dispositivi del figlio (per la riga "sul computer 5 su 7").

### Il genitore: `GET /api/finestra?figlio_id=…`

La forma della v2.4 resta, riferita al figlio indicato:

- `regole`: tutte le regole del figlio (tutti i dispositivi, eliminate comprese), ciascuna con `dispositivo_id` e `dispositivo`;
- `striscia`, `riepilogo`, `storico_modifiche`, `sforamenti_recenti`, `manomissioni_recenti`, `segno_oggi`: del figlio (gli eventi portano `dispositivo_id`);
- in più **`dispositivi`**: un elemento per dispositivo del figlio (revocati compresi), con tutto quello che è **per dispositivo**:

```json
"dispositivi": [ { "id": 2, "nome": "Computer", "tipo": "computer", "abbinato": true, "revocato": false,
  "stato_silenzio": { "ultimo_battito": "…", "silente": false, "spento": true, "spento_dal": "…" },
  "striscia": [ … ], "uso_recente": [ … ], "siti_recenti": [ … ], "medie": { … },
  "bonus": { … }, "bonus_giornalieri": [ … ] } ]
```

- **Compatibilità 0.7**: i campi di primo livello `uso_recente`, `siti_recenti`, `medie`, `bonus`, `bonus_giornalieri`, `stato_silenzio` restano e valgono **per il primo dispositivo del figlio** (quello con `id` più basso non revocato).

### Registro: tutto per dispositivo

- Eventi, battiti, fotografie `uso_giornaliero` e `siti_giornalieri` appartengono al dispositivo che li manda. La fotografia vigente è per **(dispositivo, giorno)**, con la stessa monotonia di prima.
- "Niente verde senza dati": una regola di un dispositivo è `verde` in un giorno solo se **quel dispositivo** ha mandato la fotografia `uso_giornaliero` di quel giorno.
- `riepilogo.interruzioni`: eventi `manomissione` di **qualsiasi dispositivo del figlio** negli 8 giorni.
- Le notifiche portano `figlio_id` e `dispositivo_id` (o `null`). Il genitore riceve quelle di tutti i figli. Un dispositivo riceve quelle del suo figlio che hanno `dispositivo_id` uguale al suo o `null`.
  - Quelle su una regola di un dispositivo (proposta, verdetto su regola del dispositivo) hanno il suo `dispositivo_id`.
  - Quelle del figlio (proposte e verdetti sulla vita reale, `segno`) hanno `null`.
  - **Lettura per dispositivo** (correzione v3.1): per le notifiche del figlio, `POST /api/notifiche/{id}/letta` marca la notifica come letta **solo per il dispositivo che chiama**, e `GET /api/notifiche` restituisce le notifiche non ancora lette **da quel dispositivo**. Così una notifica per tutto il figlio (`dispositivo_id: null`, come il `segno`) arriva sia al telefono sia al computer, anche se il telefono l'ha già mostrata. Le notifiche del genitore restano condivise: marcarne una come letta la marca per tutti i genitori.
- `POST /api/segno { "figlio_id" }`: un segno al giorno **per figlio**, notificato a tutti i suoi dispositivi.

### Computer: cosa cambia nel registro

- `uso_giornaliero` di un computer:
  - `uso_minuti` ha chiavi `exe:<nome>` e `nomi` ha il nome leggibile (es. `"exe:minecraft.exe": "Minecraft"`);
  - `totale_minuti` è il tempo attivo al computer;
  - `uso_categorie` conta il tempo nel browser nella categoria **del sito** (se il sito ha una categoria), altrimenti in quella del browser.
- `siti_giornalieri` di un computer:
  - `domini` = quante **visite** (quante volte il sito è diventato quello in primo piano);
  - in più **`minuti`**: `{ "youtube.com": 42 }`, i minuti passati con quel sito in primo piano;
  - `dns_cifrato: true` sul computer vuol dire "per una parte del giorno il programma non è riuscito a leggere i siti" (per esempio un browser che non sa leggere). Il nome resta per compatibilità.
  - In `siti_recenti` ogni voce dei computer ha anche `minuti`, e le voci sono ordinate per `minuti` decrescenti (poi per dominio).
- Nuovi eventi dei computer:
  - `sospensione` `{ "motivo": "spegnimento" | "sospensione" | "disconnessione" }`, mandato quando Windows si spegne, va in sospensione o l'utente esce;
  - `ripresa` `{ "motivo": "avvio" | "riattivazione" | "accesso", "avvio_sistema_ts": ms }`.
  - Per un computer **il silenzio dopo una `sospensione` non è un'interruzione**: `stato_silenzio` dà `silente: false`, `spento: true`, `spento_dal: ts della sospensione`. Un computer spento la sera è normale; un telefono no, e per i telefoni non cambia niente.
- `manomissione` dei computer, nuovi `sotto_tipo`:
  - `programma_chiuso` `{ "dal": ms, "al": ms }`: al riavvio il programma si accorge di essere stato chiuso mentre Windows era acceso;
  - `siti_non_leggibili`.

### Versioni e download

- `GET /api/versione` ha anche `"computer": { "versione_code", "versione_nome", "url": "/scarica/pactum-computer.zip", "note" }`.
- (v3.4) Il `versione_code` del computer segue quello delle app del telefono (0.8.0 = 8, 0.9.0 = 9, 0.10.0 = 10), perché il programma lo confronta col proprio codice, che fin dalla 0.8 è numerato così. Il server per la 0.8 e la 0.9 annunciava per sbaglio 1 e 2, e il programma non avvisava mai.
- `/scarica` offre anche `pactum-computer.zip`.

### Migrazione (una volta, automatica, al primo avvio della v3)

1. Crea `figli`, `dispositivi`, `credenziali`, `codici_abbinamento` e il registro dei tentativi falliti.
2. Crea il figlio 1 (`"nome": "Figlio"`, rinominabile dall'app del genitore) e il dispositivo 1 (`"Telefono"`, `telefono`, abbinato) con l'hash di `PACTUM_TOKEN_FIGLIO`. Crea il genitore 1 con l'hash di `PACTUM_TOKEN_GENITORE`.
3. Attacca **tutti** i dati esistenti a figlio 1 / dispositivo 1:
   - regole: `vita_reale` → figlio 1 senza dispositivo; le altre → dispositivo 1;
   - al dispositivo 1 vanno anche eventi, battiti, bonus, fotografie;
   - notifiche → figlio 1.
4. Niente si perde e niente si duplica. Riavviare il server non ripete la migrazione.
5. (v3.1) **Prima di toccare un database che ha già dati, il server ne fa una copia completa** accanto al file (`<db>.prima-v3-<data>`). Se la copia non riesce, il server **non parte**: meglio fermo che migrato senza rete di sicurezza.

### Copia notturna del registro (v3.2)

- Il server copia da solo il registro una volta al giorno, la prima volta dopo le **03:00 del fuso del patto**, nella cartella `PACTUM_BACKUP_DIR` (default `/backup`; in Docker è `server/backup` del NAS): `pactum-AAAAMMGG.db`, col giorno del patto. Se all'avvio sono già passate le 03:00 e la copia di oggi manca, la fa subito (un NAS spento di notte ha comunque la sua copia). Stesso modo prudente della copia prima della migrazione: `VACUUM INTO` in un `.parziale`, fsync, controllo con `PRAGMA integrity_check`, e il nome vero solo se il controllo dice "ok"; altrimenti il file resta come `.rotta` e il log lo dice. Tiene le ultime 30 e cancella solo i file con quel nome esatto. Se la cartella non c'è, la copia resta spenta (lo dice il log) e il server parte lo stesso.
- `GET /api/salute` (nessun auth) ha anche `"backup": { "ultima": "pactum-20260925.db" | null, "quando": ISO 8601 UTC | null, "copie": n }`: il nome dell'ultima copia (mai il percorso), quando è stata scritta, quante ce ne sono.
- **Ripristino**: con `PACTUM_RIPRISTINA=<nome di una copia in PACTUM_BACKUP_DIR>` (solo il nome: `/`, `\` e `..` sono rifiutati), all'avvio e **prima** di aprire e migrare il database il server controlla la copia con `integrity_check`, mette da parte il registro attuale come `<db>.prima-del-ripristino-AAAAMMGG-HHMMSS` (insieme ai suoi `-wal`/`-shm`/`-journal`) e rimette la copia al suo posto; una copia vecchia (anche v2) viene poi migrata come le altre. Nome non valido, copia che non c'è o rotta: il server **non parte**. Il segno `.ripristinato-<nome>` accanto al database impedisce di ripetere lo stesso ripristino a ogni riavvio; il log chiede di togliere `PACTUM_RIPRISTINA` dal `.env`.

## v3.3 — limite sul totale del dispositivo (30/09/2026, decisione di Andrea)

Fino alla v3.2 un `limite_tempo` valeva su un'app, un programma, un sito o una categoria. Dalla v3.3 può valere anche su **tutto il dispositivo**: "al telefono al massimo 3 ore al giorno".

Tutto il resto del contratto resta valido. Questa sezione dice solo cosa cambia.

### La chiave `totale`

- `app_o_categoria = "totale"` (proprio così, minuscolo) = **tutto l'uso di quel dispositivo nel giorno**, cioè lo stesso `totale_minuti` della fotografia `uso_giornaliero` del dispositivo: sul telefono il tempo di tutte le app, sul computer il tempo attivo al computer.
- Vale per i **telefoni** e per i **computer**, in `POST /api/regole`, in `PATCH /api/regole/{id}` e nelle proposte:
  ```json
  { "tipo": "limite_tempo", "parametri": { "app_o_categoria": "totale", "minuti_al_giorno": 180 } }
  ```
- Tutte le altre chiavi restano come prima: `422` per quelle che non vanno bene per il tipo del dispositivo della regola.

### Chi valuta: le app, come sempre

- Le app valutano da sole anche il totale: quando il `totale_minuti` del giorno supera il limite efficace (`minuti_al_giorno` + bonus concessi oggi su quella regola), mandano l'evento **`sforamento`** con gli stessi dettagli delle altre regole (`regola_id`, `giorno`, `limite_efficace`, `minuti_oltre`), al massimo uno per regola per giorno.
- Il server **non cambia** il modo in cui colora: il semaforo della regola, la striscia del dispositivo, quella del figlio e il `riepilogo` vengono dagli sforamenti, come per ogni altra regola. Vale ancora "niente verde senza dati".

### Bonus, blocco dei 4 giorni, proposte

- **Bonus**: `POST /api/bonus` con il `regola_id` di una regola `totale` allunga il totale di oggi, con gli stessi tetti per dispositivo.
- **Blocco dei 4 giorni**: come per le altre `limite_tempo`. Più minuti = allenta (aspetta 4 giorni o una proposta accettata), meno minuti = stringe (subito), eliminare = allenta. Cambiare bersaglio (da `totale` a un'app, o da un'app a `totale`) conta come allentamento, come ogni cambio di bersaglio.
- **Proposte**: il genitore propone modifiche o l'eliminazione di una regola `totale` come di ogni altra; se il figlio accetta, il server applica la modifica da solo.
- **Confronto**: a bersaglio uguale il testo non cambia (`"−30 min al giorno rispetto ad ora"`). Quando il bersaglio cambia, nel testo `totale` si legge **"tutto il telefono"** o **"tutto il computer"** secondo il tipo del dispositivo della regola: `"da tutto il telefono (180 min) a com.instagram.android (60 min) al giorno"`.

### Nella finestra del genitore

- **`nome`**: una regola `totale` **non** ha `nome`, come le regole di categoria. Le app scrivono **"Tutto il telefono"** o **"Tutto il computer"** dal `dispositivo.tipo` della regola.
- **`uso_recente`**: il limite di una regola `totale` sta **accanto al totale del giorno**, mai accanto a un'app o a una categoria (`totale` non combacia mai con una voce di `app` o di `categorie`). Nella voce del giorno, vicino a `totale_minuti`, ci sono `limite`, `regola_id` e `bonus`, con lo stesso significato che hanno nelle voci di `app` e `categorie` (limite base; `bonus` = minuti concessi quel giorno su quella regola; "oltre il limite" = oltre `limite + bonus`):
  ```json
  { "giorno": "2026-09-30", "totale_minuti": 192, "limite": 180, "regola_id": 7, "bonus": 15,
    "aggiornato_ts": "…", "app": [ … ], "categorie": [ … ] }
  ```
  - I tre campi ci sono **solo** se il dispositivo ha una regola `totale` **attiva** e quel giorno ha la sua fotografia. Un giorno senza fotografia resta com'era: `totale_minuti: null`, liste vuote, nessun limite.
  - Con più regole `totale` attive sullo stesso dispositivo vale quella con l'`id` più basso, come per le app.
  - Vale per `dispositivi[].uso_recente` e quindi anche per `uso_recente` di primo livello (il primo dispositivo).

### Compatibilità

- Nessun cambio al database e nessun endpoint nuovo.
- Un'app che non conosce `totale` ignora i tre campi in più nella voce del giorno (tolleranza evolutiva) e mostra la regola con la chiave com'è.

### Solo le notifiche nuove

- `GET /api/notifiche?dopo_id=N` (facoltativo, intero ≥ 0) restituisce solo le notifiche **non lette** con `id > N`, nella stessa forma. Senza `dopo_id` tutto come prima. Vale per il genitore e per i dispositivi. L'app del genitore 0.9 lo usa nel giro di ogni minuto con l'id più alto già visto, e rilegge la lista intera solo nel giro lento. `dopo_id` negativo → `422`.

### Risposte compresse

- Le risposte sotto `/api/` più grandi di 500 byte arrivano compresse (gzip) se il client manda `Accept-Encoding: gzip`, come fa da solo OkHttp nelle app Android. Serve all'app del genitore 0.9, che chiede le notifiche ogni minuto e riceve ogni volta tutte quelle non lette. `/scarica` non si comprime mai: APK e zip sono già compressi e devono mantenere la loro lunghezza.

## v3.4 — le proposte del figlio (01/10/2026, decisione di Andrea del 30/09)

Fino alla v3.3 propone solo il genitore e risponde solo il figlio. Dalla v3.4 **propone anche il figlio e risponde il genitore**. Resta tutto quello che c'era: il figlio continua a cambiare le sue regole da solo (stringere subito, allentare dopo 4 giorni); in più può chiedere al genitore un cambio che, **se il genitore accetta, vale subito**, anche se allenta.

Tutto il resto del contratto resta valido. Questa sezione dice solo cosa cambia.

### Chi propone, chi risponde

- Ogni proposta ha un **`autore`**: `"genitore"` oppure `"figlio"`. Le proposte nate prima della v3.4 sono tutte `"genitore"`.
- Risponde sempre **l'altro**: a una proposta del genitore risponde il figlio (un suo dispositivo qualsiasi, come prima); a una proposta del figlio risponde il genitore. Rispondere a una proposta propria → `403`.
- Resta **una sola proposta `pendente` per regola**, chiunque l'abbia fatta: `409 {"errore": "proposta_gia_pendente"}`. Sulla stessa regola non ci sono mai due richieste incrociate.
- Le proposte servono a **modificare o eliminare** una regola che esiste. Una regola nuova il figlio la crea da solo (creare = stringere), come prima.

### `POST /api/proposte` dal dispositivo (nuovo)

- Stesso corpo del genitore: `{ "regola_id": n, "parametri_proposti": { … } | {"azione": "elimina"}, "motivazione": "…"? }`. `figlio_id` nel corpo si ignora: il figlio è quello del token.
- La regola deve essere **attiva** e **dello stesso figlio** del dispositivo (di qualsiasi suo dispositivo, oppure di vita reale): altrimenti `409 {"errore": "regola_non_valida"}`, la stessa risposta che riceve il genitore.
- Regola di un dispositivo revocato → `409 {"errore": "dispositivo_revocato"}`. Parametri che non vanno bene per il tipo della regola o del dispositivo → `422`, come per il genitore.
- Il figlio può proporre anche un cambio che stringe (il server non lo vieta), ma le app glielo offrono soprattutto quando allenta, perché stringere lo può già fare da solo e subito.
- Il server calcola `confronto` e `direzione` come per le proposte del genitore (stessa funzione).
- Notifica al **genitore**: `tipo: "nuova_proposta"`, `messaggio: "<nome del figlio> propone: <confronto>"`, `payload: { "proposta_id", "regola_id", "confronto", "direzione", "autore": "figlio" }`, con `figlio_id` e il `dispositivo_id` della regola (`null` per la vita reale).
- Risposta `200` con la proposta creata (`"autore": "figlio"`).

### `POST /api/proposte/{id}/risposta` dal genitore (nuovo)

- Stesso corpo: `{ "esito": "accetta" | "rifiuta", "motivazione": "…"? }`. Stesso comportamento della risposta del figlio: atomica anche sotto richieste concorrenti; `accetta` **applica subito** la modifica (lock dei 4 giorni saltato, parametri esattamente quelli proposti, `concordata: true` nello storico; eliminazione col marcatore, col vincolo `ultima_regola`; `409 dispositivo_revocato` se nel frattempo il dispositivo è stato revocato, e la proposta resta pendente). Stessa risposta `200 { "proposta": {…}, "regola": {…} | null }`.
- Proposta di un figlio inesistente → `404`, come per gli altri endpoint del genitore: il corpo può avere `figlio_id` (facoltativo, come nel verdetto); un figlio che non c'è, o che non è quello della proposta, → `404`. Anche una proposta che non c'è → `404`.
- Notifica al **figlio**: `tipo: "proposta_risposta"`, `messaggio: "Il genitore ha accettato la tua proposta: <confronto>"` oppure `"Il genitore ha rifiutato la tua proposta: <confronto>"`, `payload: { "proposta_id", "regola_id", "esito", "autore": "figlio" }`, con **`dispositivo_id: null`**: la risposta arriva a **tutti** i dispositivi del figlio, perché la cerca dove si trova.
- Quando accetta il genitore, il server **non** gli manda anche la notifica `modifica_regola` (la modifica l'ha appena decisa lui). Lo storico la registra come sempre.
- La notifica `proposta_risposta` al genitore (il figlio risponde a una proposta del genitore) ha in più `"autore": "genitore"` nel `payload`. Il resto non cambia.

### `POST /api/proposte/{id}/ritira` (nuovo, figlio e genitore)

- Ritira una proposta **propria** ancora in attesa: il genitore le sue, un dispositivo del figlio quelle del figlio (da qualsiasi suo dispositivo). La proposta di un altro → `403`. Una proposta non più `pendente` → `409 {"errore": "proposta_non_pendente"}`.
- Nessun corpo. `stato` diventa **`ritirata`**, `usata: false`, la regola non cambia. Il `confronto` (e la `direzione`) si fermano a come sono in quel momento, come quando si risponde.
- Atomico come la risposta: un "ritira" e un "accetta" che arrivano insieme → ne passa uno solo, l'altro riceve `409 proposta_non_pendente`.
- Notifica **all'altro**: `tipo: "proposta_ritirata"` (tipo nuovo), `payload: { "proposta_id", "regola_id", "autore" }`.
  - Se ritira il figlio: al genitore, `messaggio: "<nome del figlio> ha ritirato la sua proposta"`, col `dispositivo_id` della regola.
  - Se ritira il genitore: al figlio, `messaggio: "Il genitore ha ritirato la sua proposta"`, col `dispositivo_id` della regola (come la `nuova_proposta` che l'aveva annunciata).
- Risposta `200` con la proposta aggiornata.

### La proposta nelle risposte

- Ovunque compaia una proposta (`POST /api/proposte`, `GET /api/proposte`, le risposte di `risposta` e `ritira`, `proposte_pendenti`, `proposte_inviate`) c'è in più **`autore`**: `"genitore" | "figlio"`.
- `stato` ∈ `pendente · accettata · rifiutata · annullata · ritirata`.
- Il `confronto` delle pendenti si ricalcola a ogni lettura rispetto alla regola di adesso (v2.1), per tutte e due gli autori.

### Dove si vedono

- **`GET /api/proposte`** (figlio e genitore): **senza parametri resta com'era**: solo le proposte del genitore (`autore: "genitore"`), dalla più recente, al massimo 50. Così le app 0.8 e 0.9 (e il programma del computer non ancora aggiornato) non scambiano una proposta del figlio per una del genitore. **Con `?autori=tutti`** arrivano le proposte di **tutti e due gli autori**, sempre al massimo 50: le app 0.10 lo chiedono sempre. Per il genitore il parametro si aggiunge a `figlio_id` (`?figlio_id=2&autori=tutti`). Un altro valore di `autori` → `422`.
- **`GET /api/patto`**:
  - `proposte_pendenti` resta **solo quelle a cui il figlio deve rispondere** (`autore: "genitore"`), come le vedevano le app 0.9;
  - in più **`proposte_inviate`**: le proposte del figlio **ancora pendenti** (aspettano il genitore), stessa forma, dalla più recente. Di tutto il figlio, come `proposte_pendenti`.
- **`GET /api/finestra?figlio_id=…`**: in più **`proposte_pendenti`**: tutte le proposte pendenti del figlio, di tutti e due gli autori, dalla più recente, ciascuna col suo `autore`. Quelle con `autore: "figlio"` aspettano la decisione del genitore.
- **`GET /api/famiglia`**: per ogni figlio in più **`proposte_da_decidere`**: quante proposte di quel figlio aspettano il genitore (pendenti con `autore: "figlio"`), senza contare quelle sulle regole di un dispositivo revocato (non si possono accettare, solo rifiutare: restano visibili nella finestra).
- Le notifiche che andrebbero al `dispositivo_id` di una regola di un dispositivo **revocato** (per esempio il genitore che ritira una sua proposta su quella regola) vanno invece a tutto il figlio (`dispositivo_id: null`): un dispositivo revocato non legge più niente.

### I nomi nel confronto

- Quando una proposta **cambia il bersaglio** di un `limite_tempo` (da un'app a un'altra, da una categoria a un'app, a o da `totale`), il `confronto` scrive i bersagli **con i nomi che leggono le persone**, mai con le chiavi tecniche:
  - app del telefono e programmi del computer (`exe:`): l'etichetta più recente vista nelle fotografie `uso_giornaliero` del figlio, la stessa che dà il `nome` della finestra; se non è mai arrivata, la chiave com'è;
  - categorie: come le scrivono le app: `Social`, `Giochi`, `Video`, `Musica`, `Altre app`;
  - siti (`sito:`): il dominio (`youtube.com`);
  - `totale`: "tutto il telefono" / "tutto il computer", come dalla v3.3.
- Esempio: `"da TikTok (60 min) a Instagram (60 min) al giorno"`, `"da Social (120 min) a TikTok (60 min) al giorno"`. Vale per le proposte di tutti e due gli autori e per i messaggi delle notifiche. A bersaglio uguale il testo non cambia.
- Le proposte di **eliminazione** tengono `confronto: "propone di eliminare la regola"`, ma nei messaggi delle notifiche il verbo non si ripete: `"<nome del figlio> propone di eliminare la regola"`, `"Il genitore propone di eliminare la regola"`, `"Il genitore ha accettato la tua proposta di eliminare la regola"` (o `rifiutato`), `"<nome del figlio> ha ritirato la sua proposta di eliminare la regola"`, `"Il genitore ha ritirato la sua proposta di eliminare la regola"`.

### L'eliminazione diretta di una regola

- Come dalla v2.1: eliminare una regola **annulla** tutte le sue proposte pendenti, ora di tutti e due gli autori (`stato: "annullata"`).
- La notifica `proposta_annullata` va sempre al **genitore**, come prima (una regola la elimina direttamente solo il figlio, quindi chi va avvisato è il genitore), con in più `"autore"` nel `payload`.

### Compatibilità

- **Database**: la tabella `proposte` prende la colonna `autore` (`"genitore"` per tutte le righe che ci sono già) e il nuovo stato `ritirata`. Si fa da sola al primo avvio, tutta in una transazione. Come per la v3, prima di toccare un database che ha già dati il server ne fa una copia completa accanto al file (`<db>.prima-v3.4-<data>`), e se la copia non riesce **non parte**. Un database che fa anche la migrazione v3 (per esempio uno della v2.4) ha tutte e due le copie, fatte tutte e due prima di toccarlo.
- **App 0.8/0.9 con server 0.10**: continuano a funzionare e vedono le proposte come prima: le proposte del figlio non arrivano né in `proposte_pendenti` né in `GET /api/proposte` senza `?autori=tutti`. Ignorano i campi nuovi e mostrano il tipo nuovo `proposta_ritirata` col suo `messaggio` (o lo ignorano). Per decidere sulle proposte del figlio il genitore deve avere l'app 0.10.
- **Tornare indietro al server v3.3** dopo la migrazione si fa solo **rimettendo la copia `.prima-v3.4-…`** (o con `PACTUM_RIPRISTINA` di una copia della notte precedente): un server v3.3 su un database v3.4 non sa chi ha fatto una proposta e lascerebbe il figlio rispondere alle sue.
- **Ordine sul NAS**: prima i file nuovi in `server/apk` (APK 0.10 e `pactum-computer.zip`), poi la ricostruzione dell'immagine: `versioni.json` sta dentro l'immagine e annuncia la 0.10 appena il server riparte.
- **App 0.10 con server vecchio**: `POST /api/proposte` col token del dispositivo riceve `403` (ruolo sbagliato) e `ritira` riceve `404` o `405`. In questi casi l'app non dice "errore": dice che per mandare proposte serve aggiornare il server di Pactum. Il resto dell'app funziona come la 0.9.

## v3.5 — le Sessioni (01/10/2026, decisioni di Andrea)

Una **sessione** è un periodo in cui il telefono del figlio si limita da solo ad alcune app: "Studio" con le app di scuola, "Lavoro" con le app di lavoro. La sessione la **decide il figlio**, il **genitore la approva** (una volta, e di nuovo a ogni cambio della lista), la **avvia il figlio** quando vuole, per quanto vuole. Durante la sessione il tempo nelle app della sessione **non conta**. È la prima cosa di Pactum che limita: non la impone il genitore, se la dà il figlio.

Tutto il resto del contratto resta valido. Questa sezione dice solo cosa cambia.

### La sessione

- Appartiene a un **dispositivo** di tipo `telefono` (per ora solo i telefoni: su un computer `422`).
- Forma:
  ```json
  { "id": 3, "dispositivo_id": 1, "dispositivo": { "id": 1, "nome": "Telefono", "tipo": "telefono" },
    "nome": "Studio", "app": ["eu.spaggiari.classevivafamiglia", "gruppo:apk"],
    "nomi": { "eu.spaggiari.classevivafamiglia": "ClasseViva", "gruppo:apk": "App installate da APK" },
    "stato": "approvata", "modifica_in_attesa": null, "motivazione": null,
    "versione": 4, "creata_ts": "…", "approvata_ts": "…" }
  ```
- `nome`: 1–40 caratteri, senza spazi ai bordi; unico (senza distinguere maiuscole) tra le sessioni del dispositivo non eliminate → altrimenti `409 {"errore": "nome_gia_usato"}`. Conta anche il nome di un cambio in attesa: se il genitore lo approvasse, due sessioni si chiamerebbero uguali. Eliminata una sessione, il suo nome torna libero. Il server lo porta in forma **NFC** (la stessa parola scritta in due modi è lo stesso nome); un nome con caratteri invisibili o di controllo (a capo, tab, zero-width, i segni che girano il verso del testo: categorie Unicode `Cc`, `Cf`, `Zl`, `Zp`) → `422`, perché potrebbe sembrare un altro nome.
- `app`: da 1 a 200 chiavi, senza doppioni (il server toglie i doppioni, tiene il primo nell'ordine mandato, e poi conta). Ogni chiave è il **nome di un pacchetto Android** oppure **`gruppo:apk`** = tutte le app installate su quel telefono fuori dal Play Store. Chiavi `exe:`, `sito:`, `categoria:*`, `totale` → `422`. Il nome di un pacchetto segue la regola di Android: almeno due pezzi separati da punti, ciascuno che comincia con una lettera e fatto di lettere, cifre e `_`, al massimo 255 caratteri; il resto → `422`.
- `nomi`: le etichette leggibili delle chiavi, risolte sul telefono come in `uso_giornaliero` (il genitore non può risolvere i pacchetti). Facoltativo; chiavi fino a 255 caratteri, nomi fino a 100 (in forma NFC, senza i caratteri invisibili o di controllo, che si tolgono: un'app vera può averne nel nome, e per questo la sessione non si rifiuta; spazi ai bordi tolti). Le etichette vuote e quelle di chiavi che non sono in `app` si lasciano cadere.
  - **Le etichette che si leggono** (nelle risposte, ovunque compaia la sessione: anche in `modifica_in_attesa` e nelle sessioni svolte): per ogni app che le fotografie `uso_giornaliero` del figlio conoscono (le stesse da cui viene il `nome` delle regole nella finestra, di qualsiasi suo dispositivo, ultimi 60 giorni) vale **quell'etichetta**, non quella mandata con la sessione; quella mandata resta solo per le app mai viste nell'uso (e per `gruppo:apk`). Così il genitore non si fa ingannare da un'etichetta scritta apposta ("ClasseViva" su TikTok). Nel database resta quella mandata dal telefono.
- `stato` ∈ `in_attesa · approvata · rifiutata`. `modifica_in_attesa` = `null` oppure `{ "nome", "app", "nomi", "richiesta_ts" }`, **sempre completa**: il contenuto intero che la sessione avrà se il genitore approva (i campi che il figlio non ha cambiato sono copiati da quella approvata). `motivazione` = quella dell'ultimo rifiuto del genitore (o `null`), al massimo 500 caratteri.
- `versione`: un numero che il server aumenta a **ogni** cambio della sessione (creazione = 1, ogni `PATCH`, ogni decisione del genitore). Serve al genitore per dire **che cosa** ha visto quando decide (v. "La risposta del genitore").
- Una sessione che non esiste, è eliminata o è di un altro dispositivo o figlio → `404 {"detail": "sessione non trovata"}` (mai il "Not Found" generico, che per le app vuol dire "server vecchio").

### Endpoint del dispositivo

- Una sessione si cambia, si elimina e si avvia **solo dal telefono che l'ha creata** (la barriera gira lì): per un altro dispositivo, anche dello stesso figlio, non c'è (`404 {"detail": "sessione non trovata"}`, v. sopra). `POST`, `PATCH`, `DELETE` e `avvia` rispondono `409 dispositivo_revocato` se il dispositivo è stato revocato mentre la richiesta arrivava (il token di un revocato risponde già `401`).
- `GET /api/sessioni`: le sessioni non eliminate **di questo dispositivo**, dalla più vecchia. `{ "sessioni": [ … ] }`. Dal dispositivo `figlio_id` si ignora.
- `POST /api/sessioni` `{ "nome", "app": [ … ], "nomi": { … }? }` → `201` con la sessione, `stato: "in_attesa"`. Al massimo **20 sessioni non eliminate per telefono** → altrimenti `409 {"errore": "troppe_sessioni"}`. Notifica al genitore `sessione_da_approvare`, `messaggio: "<nome del figlio> chiede di approvare la sessione «<nome>»"`, `payload: { "sessione_id", "nome", "cambio": false }`, col `dispositivo_id` della sessione.
- **Un solo avviso aperto per sessione**: una `sessione_da_approvare` nuova prende il posto di quella sulla stessa sessione che il genitore non ha ancora letto (la vecchia si segna come letta). Anche quando il genitore decide, quando il figlio ritira il cambio o elimina la sessione, la richiesta aperta si segna come letta. Le notifiche non si cancellano.
- `PATCH /api/sessioni/{id}` `{ "nome"?, "app"?, "nomi"? }` (almeno un campo):
  - sessione `in_attesa` o `rifiutata`: il contenuto cambia subito e la sessione è (di nuovo) `in_attesa`, `motivazione: null`; notifica `sessione_da_approvare` con `cambio: false`;
  - sessione `approvata`: il contenuto approvato **non** cambia; nasce `modifica_in_attesa` (ne sostituisce una precedente). La versione approvata resta usabile finché il genitore non decide. Notifica `sessione_da_approvare`, `messaggio: "<nome del figlio> chiede di cambiare la sessione «<nome>»"`, `cambio: true`. Qui `<nome>` (anche nel `payload`) è il nome approvato, quello che il genitore conosce; se il cambio rinomina la sessione, il `payload` ha in più `nuovo_nome`.
  - i campi che mancano (o `null`) restano come sono: su una sessione approvata si copiano dalla versione approvata, e il cambio nuovo sostituisce del tutto quello di prima. Le etichette di `nomi` seguono la lista: quelle delle app tolte cadono. Nessun campo → `422`. Ogni `PATCH` aumenta la `versione`.
  - su una sessione `approvata`, un `PATCH` il cui risultato è proprio la versione approvata (stesso nome, stesse app anche in un altro ordine, stesse etichette) è il figlio che **ritira il suo cambio**: `modifica_in_attesa` torna `null`, la `versione` cresce, nessun avviso nuovo e la richiesta che il genitore non aveva letto si segna come letta.
- `DELETE /api/sessioni/{id}`: elimina subito (togliere una sessione non allenta niente; la storia delle sessioni svolte resta). Se è quella in corso → `409 {"errore": "sessione_in_corso"}`. Notifica al genitore `sessione_eliminata` (`{ "sessione_id", "nome" }`), `messaggio: "<nome del figlio> ha eliminato la sessione «<nome>»"`. Risposta `200 { "id", "eliminata": true }`.
- `POST /api/sessioni/{id}/avvia` `{ "durata_minuti": n }` → `201` con la **sessione svolta**:
  - `durata_minuti` da 1 a 1440, un intero vero (`true`, `"30"`, `30.0` → `422`). Il genitore non mette un tetto (decisione di Andrea): 1440 è solo il limite tecnico di un giorno;
  - solo una sessione `approvata` (con o senza modifica in attesa: vale la versione approvata) → altrimenti `409 {"errore": "sessione_non_approvata"}`;
  - una sola sessione in corso per dispositivo → altrimenti `409 {"errore": "sessione_gia_in_corso"}`; dispositivo revocato → `409 dispositivo_revocato`;
  - il `nome`, la lista `app` e i `nomi` si **congelano** nella sessione svolta: un cambio approvato dopo vale dalla prossima.
- `POST /api/sessioni/in_corso/termina` `{ "ts_device": ms?, "svolta_id": n? }` (il corpo si può omettere) → `200` con la sessione svolta chiusa (`chiusura: "terminata"`); `404` se non c'è una sessione in corso. `svolta_id` (facoltativo, un intero vero) è l'`id` della sessione svolta che il telefono vuole chiudere: se non è quella in corso → `404`. L'app lo manda sempre: senza, una chiusura rimasta in coda e consegnata dopo l'avvio di un'altra sessione chiuderebbe quella nuova.
  - **La fine** è l'istante d'arrivo al server. `ts_device` conta solo per una chiusura fatta senza rete e consegnata dopo, cioè se cade **più di 2 minuti prima dell'arrivo** (entro 2 minuti è una chiusura fatta con la rete: vale l'arrivo, così un orologio un po' avanti o indietro non sposta niente), **non prima dell'inizio**, **mai dopo l'arrivo** (una fine nel futuro non esiste: vale l'arrivo) e **non più di 48 ore prima dell'arrivo** (oltre, vale l'arrivo). Mai oltre la fine prevista.
  - Una chiusura fatta prima della fine prevista e consegnata quando la durata era già finita vale quindi da quando è stata fatta (`terminata`); se invece la fine così calcolata non viene prima della fine prevista, la sessione era già finita da sola: resta `scaduta` e la risposta è `404`. Una chiusura già scritta (`terminata` o `scaduta`) non si riscrive mai.
- Allo scadere della durata la sessione si chiude da sola: `chiusura: "scaduta"`, `fine_ts` = `fine_prevista_ts`. Il server lo calcola quando legge, senza processi in sottofondo.

### La sessione svolta

```json
{ "id": 12, "sessione_id": 3, "dispositivo_id": 1, "nome": "Studio",
  "app": [ … ], "nomi": { … },
  "inizio_ts": "…", "durata_minuti": 120, "fine_prevista_ts": "…",
  "fine_ts": null, "chiusura": null, "in_corso": true }
```

`chiusura` ∈ `null` (in corso) · `scaduta` · `terminata` (chiusa prima dal figlio).

### Dove si vedono

- **`GET /api/patto`** (dispositivo), in più:
  - `sessioni`: come `GET /api/sessioni`;
  - `sessione_in_corso`: la sessione svolta in corso di questo dispositivo, o `null`;
  - `sessioni_svolte`: quelle di questo dispositivo che toccano gli 8 giorni della striscia (finite dopo la mezzanotte, nel fuso del patto, del primo degli 8 giorni, o ancora aperte), dalla più recente, al massimo 200. Servono al telefono per sapere quali periodi non contano (anche dopo un riavvio o una reinstallazione).
- **`GET /api/sessioni?figlio_id=…`** (genitore): `{ "sessioni": [ … ] }`, le sessioni non eliminate di tutti i dispositivi del figlio (revocati compresi), dalla più vecchia. Senza `figlio_id` il figlio con l'`id` più basso; un `figlio_id` che non c'è → `404`.
- **`GET /api/finestra`**, in più: `sessioni` (come sopra), `sessioni_da_approvare` (quante: sessioni `in_attesa` più modifiche in attesa) e `sessioni_svolte` (del figlio, gli stessi 8 giorni, dalla più recente, al massimo 200, ciascuna col suo `dispositivo_id`).
- **`GET /api/famiglia`**: per ogni figlio in più `sessioni_da_approvare`. Qui e nella finestra non si contano le sessioni dei dispositivi revocati (non si avviano più e non si decidono più: `risposta` → `409 dispositivo_revocato`): restano visibili in `sessioni`.
- **`uso_recente`** (finestra, sia di primo livello sia `dispositivi[].uso_recente`): ogni voce del giorno ha in più `sessioni_minuti`, accanto a `totale_minuti`, dalla fotografia `uso_giornaliero` vigente di quel giorno: un intero da 0 a 1440 se la fotografia lo dice, altrimenti `null` (anche nei giorni senza fotografia). Mai uno zero finto: un telefono 0.10 non lo manda. Un valore non valido nella fotografia (negativo, non intero, oltre 1440) vale `null` e non fa rifiutare il pacco di eventi.
- **Minuti oltre un giorno** (in tutte le fotografie `uso_giornaliero`, telefoni e computer): un `totale_minuti` oltre 1440 non è valido e vale 0, come uno mancante (quindi non scavalca la fotografia vigente e non sporca le `medie`); in `uso_recente` le voci di `uso_minuti` e `uso_categorie` oltre 1440 si lasciano cadere.
- Il genitore vede **inizio, durata, fine e chiusure anticipate**; non vede quali app il figlio ha provato ad aprire né quante volte è comparsa la barriera (decisione di Andrea). Per l'inizio e la fine non ci sono notifiche: si vedono nella finestra.

### La risposta del genitore

- `POST /api/sessioni/{id}/risposta` `{ "esito": "approva" | "rifiuta", "versione": n, "motivazione": "…"? }` (genitore; `figlio_id` facoltativo nel corpo, `404` se non combacia). **`versione`** (un intero vero: `true`, `"1"`, `1.0` → `422`) è quella della sessione che il genitore ha sullo schermo: se nel frattempo il figlio l'ha cambiata (la `versione` sul server è diversa) → `409 {"errore": "richiesta_cambiata", "sessione": {…}}` con la sessione com'è adesso, e niente viene deciso: il genitore non approva mai una lista che non ha visto. `versione` mancante → `422`. Sessione di un dispositivo revocato → `409 dispositivo_revocato` (e non conta in `sessioni_da_approvare`). Decide quello che è in attesa:
  - una sessione `in_attesa` → `approvata` (`approvata_ts`) o `rifiutata` (con `motivazione`);
  - una `modifica_in_attesa` → `approva`: nome, app e nomi diventano quelli della modifica; `rifiuta`: la modifica sparisce, resta la versione approvata (la `motivazione` si conserva).
  - Niente in attesa → `409 {"errore": "niente_da_decidere"}`. Atomica anche sotto richieste concorrenti.
  - I controlli, in quest'ordine: sessione (`404`), dispositivo revocato, niente in attesa (anche con una `versione` vecchia: due decisioni insieme, la seconda riceve `niente_da_decidere`), poi la `versione`.
  - Approvare (la sessione o il cambio) rimette `motivazione: null`; approvare un cambio porta `approvata_ts` all'ora della decisione.
- Notifica al figlio `sessione_risposta`, `payload: { "sessione_id", "nome", "esito", "cambio" }` (`nome` = quello della sessione dopo la decisione), col `dispositivo_id` della sessione. Nelle notifiche `sessione_da_approvare` di un cambio che rinomina, `nome` è quello attuale e `nuovo_nome` quello chiesto. `messaggio`: "Il genitore ha approvato la sessione «Studio»" / "…non ha approvato…" (per un cambio: "…il cambio alla sessione «Studio»"), col nome di dopo la decisione, come il `payload`.
- Risposta `200` con la sessione aggiornata.

### Cosa conta durante una sessione (lo calcola il telefono)

- Mentre una sessione svolta è in corso (da `inizio_ts` a `fine_ts`, o a `fine_prevista_ts` se non è ancora chiusa), il tempo passato nelle **app della sua lista** (comprese quelle del gruppo `gruppo:apk`, se c'è) **non conta**: non entra in `uso_minuti`, `uso_categorie` e `totale_minuti` della fotografia `uso_giornaliero`, né negli sforamenti di limiti (app, categoria, totale) e fasce orarie.
- Il tempo nelle app **fuori dalla lista conta come sempre**, anche durante la sessione (per esempio se la barriera non è potuta comparire): una sessione non rende gratis le app che non ci sono.
- La fotografia `uso_giornaliero` ha in più `sessioni_minuti` (facoltativo, un intero da 0 a 1440): i minuti del giorno non contati perché passati in sessione. È solo un'informazione per la finestra.

### La barriera (comportamento dell'app del telefono)

- Durante una sessione, se in primo piano c'è un'app fuori dalla lista, il telefono la copre entro pochi secondi con una schermata "Sei in sessione «Studio»" con un solo pulsante, **Esci**, che porta alla schermata Home. Non si può chiudere la schermata e restare nell'app: se ci si torna, ricompare.
- **Sempre usabili** (mai coperte, il loro tempo conta come sempre): Pactum, la schermata Home, la tastiera, l'interfaccia di sistema, il Telefono e le chiamate (anche la schermata della chiamata e le emergenze), le Impostazioni.
- Servono "Mostra sopra le altre app" e l'accesso all'uso: senza, l'app non avvia la sessione e lo dice.
- Chiudere prima: dentro Pactum, "Termina la sessione" (decisione di Andrea: si può, resta nel registro e il genitore lo vede).

### Compatibilità

- Database: due tabelle nuove (`sessioni`, `sessioni_svolte`); nessuna tabella esistente cambia. Nascono da sole al primo avvio, senza copia prima (non si tocca niente di quello che c'è). Un server v3.4 su un database v3.5 le ignora.
- App 0.10 con server 0.11: ignorano i campi nuovi. Il programma del computer non cambia.
- App 0.11 con server vecchio: `/api/sessioni` risponde `404`/`405` → l'app dice che per le sessioni serve aggiornare il server di Pactum; il resto funziona come la 0.10.
- **Ordine sul NAS**: prima i file nuovi in `server/apk` (APK 0.11 di figlio e genitore), poi la ricostruzione dell'immagine: `versioni.json` sta dentro l'immagine e annuncia la 0.11 appena il server riparte. Il programma del computer resta 0.10.

### I corpi delle richieste e i numeri (tutto il server)

- Ogni corpo JSON di una richiesta sotto `/api/` si controlla **prima di qualsiasi endpoint**: `NaN`, `Infinity`, `-Infinity` (e un numero come `1e400`, che diventa infinito), un **surrogato da solo** in un testo (per esempio `"\ud800"`, con l'escape o coi byte grezzi; una coppia di surrogati, cioè un'emoji, va bene) in una chiave o in un valore, e un **intero oltre i 64 bit** → `422 {"detail": [{"loc": ["body"], "msg": "…"}]}` e non si scrive niente. Python li legge come JSON, ma una volta nel registro (i dettagli di uno sforamento, le etichette di una fotografia, il nome di una sessione) farebbero cadere ogni lettura che li ripresenta, comprese le notifiche del genitore. Un corpo vuoto o che non è JSON resta com'era (lo giudica FastAPI).
- Un intero oltre i 64 bit in un percorso o in una query (`/api/regole/99999999999999999999`, `?figlio_id=…`) → `422 {"detail": "numero fuori misura"}`, mai un `500`; sulle sessioni → `404 {"detail": "sessione non trovata"}`.

## v3.6 — la famiglia con più genitori e le faccende (02/10/2026, decisioni di Andrea)

Due novità.

1. **Più genitori.** Più figli c'erano già (v3); adesso anche più genitori, ognuno col suo nome e il suo token. Tutti i genitori sono uguali: vedono tutto, ricevono gli avvisi, propongono, approvano, danno faccende.
2. **Le faccende.** Un genitore dà al figlio delle faccende di casa. Da quando lo decide il genitore (subito o da un'ora scelta), e finché il figlio non le ha fatte **tutte**, mandando **una foto per ognuna**, i suoi dispositivi sono **bloccati**: il telefono tranne poche app fondamentali, il computer del tutto. Si sblocca **appena arriva l'ultima foto**; un genitore può **bocciare** una foto (entro 24 ore): quella faccenda si riapre e il blocco torna subito.

È la prima cosa di Pactum che il **genitore impone**. Va contro il "niente blocchi" del progetto e Andrea lo sa: è una regola decisa da lui con la sua famiglia, per fare le faccende subito. Il blocco si appoggia sugli stessi strumenti della barriera delle sessioni e non diventa un'app spia: chi lo rompe (disinstallare, togliere un permesso, chiudere il programma del computer) lo può fare, ma resta nel registro e i genitori lo vedono, come sempre.

Tutto il resto del contratto resta valido. Questa sezione dice solo cosa cambia.

### I genitori

- Ogni genitore ha un **`id`**, un **`nome`** (1–40 caratteri, stesse regole del nome di un figlio) e il proprio token (salvato come hash, come quelli dei dispositivi).
- **Il genitore 1** è quello del token d'ambiente `PACTUM_TOKEN_GENITORE`: le app del genitore già installate continuano a funzionare senza far niente. Al primo avvio della v3.6 nasce col nome `"Genitore"` (si può cambiare). Tutte le credenziali `genitore` che ci sono già diventano sue.
- `GET /api/genitori` (genitore) → `200 { "io": { "id", "nome" }, "genitori": [ { "id", "nome", "abbinato": true, "revocato": false, "creato_ts": "…" } ] }`, in ordine di `id`, revocati compresi.
- `POST /api/genitori` (genitore) `{ "nome": "Mamma" }` → `201 { "genitore": { … }, "codice": "483920", "scade_ts": "…" }`. Il genitore nasce **non abbinato**; il codice ha le stesse regole di quello dei dispositivi (6 cifre, 15 minuti, una volta sola, un codice nuovo annulla il vecchio, salvato come hash). Al massimo 10 genitori non revocati → `409 {"errore": "troppi_genitori"}`.
- `POST /api/genitori/{id}/codice` (genitore): nuovo codice per un genitore già creato (telefono cambiato o reinstallato). Come per i dispositivi, al momento dell'abbinamento nasce un token nuovo e il vecchio smette di funzionare.
- `PATCH /api/genitori/{id}` `{ "nome" }`: rinomina (anche un altro genitore: sono tutti uguali).
- `DELETE /api/genitori/{id}`: **revoca** (il suo token risponde `401`). Non sé stessi → `409 {"errore": "non_te_stesso"}`; non l'ultimo genitore non revocato → `409 {"errore": "ultimo_genitore"}`. Niente si cancella: le sue proposte, decisioni e faccende restano col suo nome. La revoca del genitore 1 resta anche dopo un riavvio del server (il token d'ambiente non lo resuscita).
- Un genitore che non c'è → `404 {"detail": "genitore non trovato"}`.
- **Abbinamento**: `POST /api/abbina` accetta anche `"tipo": "genitore"`: `{ "codice", "tipo": "genitore", "versione_app" }` → `200 { "token": "…", "genitore": { "id", "nome" } }`. Codici di genitori e di dispositivi sono dello stesso tipo e hanno lo stesso conteggio dei tentativi sbagliati. Un codice da genitore usato con `tipo: "telefono"` o `"computer"` (o viceversa) → `409 { "errore": "tipo_non_corrispondente", "tipo_atteso": "genitore" }` (o `"telefono"`/`"computer"`) e il codice non si consuma.
- `GET /api/famiglia` in più: `"io": { "id", "nome" }` e `"genitori": [ { "id", "nome", "revocato" } ]`.

### Chi ha fatto cosa

Dove decide o scrive un genitore, le risposte dicono quale: `{ "id", "nome" }` (il nome di adesso, non quello di allora).

- proposte del genitore: `"genitore": { … }` (e `null` sulle proposte del figlio); nel `payload` di `nuova_proposta` al figlio, `genitore`;
- risposta del genitore a una proposta del figlio: `"risposta_di": { … }`;
- sessioni: `"decisa_da": { … }` (l'ultima decisione) e, nel `payload` di `sessione_risposta`, `genitore`;
- verdetti sulle dichiarazioni e `segno`: `"da": { … }`;
- faccende: v. sotto.

Le righe scritte prima della v3.6 valgono come fatte dal genitore 1. Nei `messaggio` delle notifiche per il figlio, dove prima c'era "Il genitore", adesso c'è il nome del genitore ("Mamma ha approvato la sessione «Studio»").

### Le notifiche del genitore: lette da ciascuno

- Prima (v3.1) una notifica per il genitore, marcata come letta, era letta per tutti. **Adesso ogni genitore le legge per conto suo**, come i dispositivi: `GET /api/notifiche` dà le non lette **da quel genitore**, `POST /api/notifiche/{id}/letta` le marca **solo per lui**, `?dopo_id` e il conteggio `notifiche_non_lette` di `GET /api/famiglia` sono **di chi chiama**.
- Un genitore appena abbinato non riceve le notifiche nate prima che fosse creato: valgono come già lette per lui.
- Le notifiche già lette prima della v3.6 restano lette per il genitore 1.

### La faccenda

```json
{ "id": 5, "figlio_id": 1, "titolo": "Svuota la lavastoviglie", "nota": "anche le pentole",
  "stato": "da_fare", "blocco_da": "2026-10-03T14:00:00+00:00", "creata_ts": "…",
  "creata_da": { "id": 2, "nome": "Mamma" },
  "foto_ts": null, "foto": false,
  "bocciature": 0, "ultima_bocciatura": null,
  "chiusa_ts": null, "annullata_da": null,
  "storia": [ { "tipo": "data", "ts": "…", "genitore": { "id": 2, "nome": "Mamma" } } ] }
```

- `titolo`: 1–80 caratteri, in forma NFC, senza caratteri invisibili o di controllo (stesse regole del nome di una sessione) → altrimenti `422`. `nota`: facoltativa, fino a 300 caratteri, stesse regole; a capo ammessi.
- `stato` ∈ `da_fare · fatta · annullata`.
- `blocco_da`: da quando questa faccenda blocca. Mai `null` nelle risposte.
- `foto_ts`: quando è arrivata la foto (ora del server); `foto`: `true` se il file c'è ancora (le foto si tengono 30 giorni, poi si cancellano e `foto` torna `false`; `foto_ts` resta).
- `ultima_bocciatura`: `null` oppure `{ "ts", "nota", "da": { "id", "nome" } }`.
- `chiusa_ts`: quando è diventata `fatta` o `annullata`; `annullata_da`: il genitore che l'ha annullata.
- `storia` (precisazione del 03/10): tutto quello che è successo alla faccenda, dalla più vecchia, e non si perde niente (una bocciatura non cancella la foto di prima dalla storia, la seconda bocciatura non cancella la prima). Ogni voce è `{ "tipo", "ts" }`, più `"genitore": { "id", "nome" }` dove ha fatto qualcosa un genitore e `"nota"` sulle bocciature: `{ "tipo": "data", "ts", "genitore" }`, `{ "tipo": "foto", "ts" }` (la foto arrivata), `{ "tipo": "bocciata", "ts", "genitore", "nota" }` (`nota` anche `null`), `{ "tipo": "annullata", "ts", "genitore" }`. C'è ovunque compare la faccenda (non nelle voci di `blocco.da_fare`). Nel server è una tabella in sola aggiunta.
- Una faccenda che non c'è, o di un altro figlio → `404 {"detail": "faccenda non trovata"}`.

### Endpoint del genitore

- `POST /api/faccende` `{ "figlio_id": 1, "faccende": [ { "titolo": "…", "nota": "…"? } ], "blocco_da": "…"? }` → `201 { "faccende": [ … ] }`, nell'ordine mandato.
  - Da 1 a 10 faccende per volta; tutte hanno lo stesso `blocco_da`.
  - `blocco_da` assente o `null` = **subito** (l'ora del server). Una data passata vale subito. Più di 7 giorni avanti → `422`. Un testo che non è una data con fuso → `422`.
  - Al massimo **20 faccende `da_fare`** per figlio → altrimenti `409 {"errore": "troppe_faccende"}` e non si crea niente.
  - `figlio_id` obbligatorio (qui non vale "il primo figlio": dare faccende al figlio sbagliato blocca il telefono sbagliato) → `422` se manca, `404` se non c'è.
  - Notifica al figlio `nuove_faccende` (`dispositivo_id: null`, quindi a tutti i suoi dispositivi), `messaggio: "<nome del genitore> ti ha dato 3 faccende"` (una: `"<nome del genitore> ti ha dato una faccenda: «<titolo>»"`), `payload: { "faccenda_ids": [ … ], "blocco_da": "…", "genitore": { "id", "nome" } }`.
- `GET /api/faccende?figlio_id=…` → `{ "faccende": [ … ] }`: tutte le `da_fare`, più le `fatta` e `annullata` chiuse negli ultimi 30 giorni; dalla più recente (`creata_ts`, poi `id`). Senza `figlio_id` il primo figlio, come gli altri `GET`.
- `GET /api/faccende/{id}/foto` → `200` col file (`Content-Type: image/jpeg`); `404 {"detail": "foto non trovata"}` se non c'è (mai arrivata, bocciata o già cancellata). Mai compressa con gzip.
- `POST /api/faccende/{id}/boccia` `{ "nota": "…"? }` (nota fino a 300 caratteri):
  - solo una faccenda `fatta` con la foto arrivata da **meno di 24 ore** → altrimenti `409 {"errore": "non_bocciabile"}`;
  - la faccenda torna `da_fare` con `blocco_da` = adesso (il blocco torna subito), `bocciature` + 1, `ultima_bocciatura` riempita, `foto_ts: null`, `chiusa_ts: null`; il file della foto si cancella;
  - notifica al figlio `faccenda_bocciata`, `messaggio: "<nome del genitore> ha bocciato «<titolo>»: <nota>"` (senza nota, senza i due punti), `payload: { "faccenda_id", "titolo", "nota", "genitore" }`;
  - risposta `200` con la faccenda. Atomica come le altre decisioni: due bocciature insieme, la seconda riceve `non_bocciabile`.
- `POST /api/faccende/{id}/annulla` → solo una `da_fare` (altrimenti `409 {"errore": "non_annullabile"}`) → `annullata`, `annullata_da`, `chiusa_ts`; notifica al figlio `faccenda_annullata` (`payload: { "faccenda_id", "titolo", "genitore" }`); risposta `200` con la faccenda. Se era l'ultima che bloccava, il blocco finisce.

### Endpoint del dispositivo

- `GET /api/faccende` → come per il genitore, del figlio del token.
- `PUT /api/faccende/{id}/foto` con il corpo = la foto, `Content-Type: image/jpeg` (non JSON):
  - solo da un dispositivo di tipo `telefono` del figlio della faccenda → da un computer `422 {"errore": "solo_dal_telefono"}`;
  - solo su una faccenda `da_fare` → altrimenti `409 {"errore": "non_da_fare"}`. Si può mandare anche prima di `blocco_da`: fare le faccende in anticipo va benissimo, e allora il blocco non parte;
  - al massimo **4 MB**; il file deve essere un JPEG (comincia con `FF D8 FF`) → altrimenti `413` / `422 {"errore": "foto_non_valida"}`;
  - **il server toglie dal file i dati nascosti** (i segmenti APP1–APP15 e i commenti: EXIF con la posizione, XMP, IPTC) prima di salvarlo; se il file non si riesce a leggere come JPEG → `422 {"errore": "foto_non_valida"}`. (Precisazione del 03/10: il server tiene solo i pezzi che servono a mostrare l'immagine e butta tutto il resto, anche quello che sta tra le scansioni o dopo la fine dell'immagine: v. Precisazioni, "La foto");
  - (precisazione del 03/10) facoltativo **`?bocciature=N`**: quante bocciature della faccenda il telefono conosceva quando ha scattato la foto (il `bocciature` della faccenda in quel momento). Se intanto la faccenda ne ha avute di più, la foto è quella vecchia, arrivata dopo una bocciatura → `409 {"errore": "bocciata_nel_frattempo"}` e non sblocca niente; l'app la toglie dalla coda e ne chiede una nuova. Le app 0.13 lo mandano sempre; senza (app 0.12) tutto come prima;
  - la faccenda diventa `fatta` (`foto_ts`, `chiusa_ts` = adesso). Notifica ai genitori `faccenda_fatta`, `messaggio: "<nome del figlio> ha fatto «<titolo>»"`, `payload: { "faccenda_id", "titolo" }`. Se era l'ultima `da_fare` del figlio, in più `faccende_finite`, `messaggio: "<nome del figlio> ha finito le faccende: telefono e computer sbloccati"` (se nessuna bloccava ancora: "<nome del figlio> ha finito le faccende"), `payload: { "faccenda_ids": [ … ] }` (quelle chiuse con la foto da quando è iniziato il giro);
  - risposta `200` con la faccenda. La stessa foto mandata due volte (la seconda trova la faccenda già `fatta` con la stessa foto, byte per byte) → `200` con la faccenda, senza nuove notifiche: una consegna ripetuta dopo una rete che cade non è un errore.
- `GET /api/faccende/{id}/foto` → anche dal dispositivo dello stesso figlio.
- `GET /api/faccende/blocco` → la risposta piccola che i dispositivi chiedono spesso:
  ```json
  { "attivo": true, "dal": "…", "prossimo": null,
    "da_fare": [ { "id", "titolo", "nota", "blocco_da", "creata_da": { "id", "nome" }, "bocciature", "ultima_bocciatura" } ] }
  ```
  - `attivo`: c'è almeno una faccenda `da_fare` con `blocco_da` già passato. `dal`: il più vecchio di quei `blocco_da` (o `null`).
  - `prossimo`: se non è attivo, il `blocco_da` più vicino nel futuro di una faccenda `da_fare` (o `null`): il dispositivo lo usa per partire da solo all'ora giusta, anche senza rete.
  - `da_fare`: tutte le `da_fare` del figlio, dalla più vecchia.

### Dove si vedono

- `GET /api/patto` (dispositivo), in più: `faccende` (come `GET /api/faccende`) e `blocco` (come `GET /api/faccende/blocco`).
- `GET /api/finestra` (genitore), in più: `faccende` e `blocco`, del figlio.
- `GET /api/famiglia`: per ogni figlio in più `faccende_da_fare` (quante) e `blocco_attivo`.
- Il registro delle faccende è la tabella stessa: chi le ha date, quando, quando è arrivata ogni foto, le bocciature, gli annullamenti. Nessun evento nuovo nel registro degli eventi.

### Sessioni e faccende

- Con il blocco attivo una sessione non si avvia: `POST /api/sessioni/{id}/avvia` → `409 {"errore": "blocco_faccende"}`. (Precisazione del 03/10: solo sui telefoni con `versione_app` dalla 0.13 in su; su quelli più vecchi, o che non hanno mai detto la loro versione, il blocco non c'è e la sessione parte come prima.)
- Se il blocco parte durante una sessione, la sessione continua sul server (finisce da sola o la chiude il figlio), ma sul telefono vale la barriera del blocco, che è più stretta.

### Il blocco sul telefono (comportamento dell'app del figlio)

- Quando `blocco.attivo` (o quando arriva l'ora di `prossimo`, anche senza rete), ogni app che non è nell'elenco qui sotto si copre entro pochi secondi con la **barriera delle faccende**: "Prima le faccende", l'elenco di quelle da fare con chi le ha date, e un solo pulsante, **Apri Pactum**, che porta alla pagina delle faccende. Se si torna nell'app, ricompare.
- **Sempre usabili durante il blocco** (decisione di Andrea): Pactum, la schermata Home, la tastiera, l'interfaccia di sistema, **Telefono** e la sua schermata di chiamata, e le emergenze (solo se sono app **di sistema**; 03/10, decisione di Andrea: una chiamata **non** spegne la barriera — tutto il resto si copre anche durante una chiamata, comprese WhatsApp e le altre app con una chiamata via internet in corso, che continua in sottofondo e si chiude dalla notifica), **Contatti**, **Messaggi = solo l'app degli SMS** (quella predefinita del telefono; WhatsApp, Telegram e le altre app di messaggi sono **bloccate**), **Wallet** (Google Wallet), **eWeLink**, **Fotocamera** (quella di sistema), **Foto** (Google Foto e la galleria del telefono), **Tinaba**, e le **Impostazioni** (decisione di Andrea: aperte, come nelle sessioni). In più le stesse eccezioni tecniche della barriera delle sessioni (finestre dei permessi, scelta di file e foto, schede del browser aperte da un'app permessa, installazione degli aggiornamenti di Pactum).
- Le faccende si fanno dalla pagina di Pactum: per ognuna **Scatta la foto** apre la fotocamera (solo una foto scattata in quel momento, mai una presa dalla galleria), il telefono la rimpicciolisce (lato lungo al massimo 2048 pixel, JPEG), la salva **senza dati nascosti** e la manda. Se la rete manca, la foto resta in coda e parte da sola appena può; il blocco resta finché il server non l'ha ricevuta.
- **Senza rete il blocco resta com'era**: un telefono bloccato resta bloccato finché il server non dice il contrario; un blocco programmato parte all'ora di `prossimo` anche offline. (03/10) Un `404`/`405` non toglie il blocco; un `401` su `GET /api/faccende/blocco` o su `GET /api/patto` (questo telefono non è più collegato) lo toglie, e l'app lo dice, come sul computer.
- Il telefono chiede `GET /api/faccende/blocco` almeno ogni minuto a schermo acceso, e subito quando arriva una notifica di faccende o si sblocca lo schermo.
- Avvisi sul telefono: alla notifica `nuove_faccende` ("Mamma ti ha dato 3 faccende · blocco dalle 16:00"), quando il blocco parte ("Prima le faccende: il telefono è bloccato"), a ogni bocciatura e annullamento.
- Se durante il blocco Pactum perde "Mostra sopra le altre app" o l'accesso all'uso, il telefono manda un evento `manomissione` con `sotto_tipo: "permesso_revocato"` e il permesso nei dettagli, come per gli altri permessi. (03/10) Il permesso sta in `"permesso": "accesso_uso"` oppure `"mostra_sopra"`; l'evento nasce una volta sola, al passaggio da concesso a tolto (l'accesso all'uso sempre, come dalla tappa 6; "Mostra sopra le altre app" solo durante il blocco).
- (03/10) **Forza arresto.** Le Impostazioni restano libere durante il blocco, e da lì si può fermare Pactum: la barriera sparisce e il server vede solo silenzio. Quando Pactum riparte, se era stato fermato a mano mentre il telefono era bloccato (da Android 11, `ApplicationExitInfo` con `REASON_USER_REQUESTED` o `REASON_USER_STOPPED`), il telefono manda un evento `manomissione` con `sotto_tipo: "fermato_durante_blocco"` e `{ "dal": ms, "al": ms, "minuti": n }` (da quando era fermo a quando è ripartito). Mai dopo un riavvio del telefono (contano solo le uscite di questa accensione) né per un aggiornamento di Pactum. Per il server è una `manomissione` come le altre.
- (03/10) **Limiti** (scritti, non corretti: chiuderli vorrebbe dire leggere tutte le notifiche del telefono, e Pactum non lo fa): durante il blocco si può ancora rispondere a un messaggio (anche di WhatsApp) dalla tendina delle notifiche, comandare la musica dalla sua notifica e usare l'assistente vocale. La barriera copre le app, non le notifiche. Una finestrella (picture-in-picture) o metà di uno schermo diviso di un'app bloccata si copre con una finestra sopra le altre app; per chiuderla il ragazzo ha qualche secondo ("Chiudi la finestrella").

### Il blocco sul computer (comportamento del programma)

- Quando `blocco.attivo` (o all'ora di `prossimo`, anche senza rete), il programma copre **tutti gli schermi** con una finestra sempre in primo piano: "Prima le faccende", l'elenco, e la frase "Si sblocca da solo quando dal telefono hai mandato la foto di ogni faccenda". **Nessuna app resta usabile** (decisione di Andrea): la finestra non si chiude, non si sposta, torna davanti se un'altra la scavalca. Il programma non chiude e non tocca le altre app (niente lavoro perso): le copre e basta.
- Vale solo sull'account Windows del figlio, dove gira il programma.
- Chiede `GET /api/faccende/blocco` ogni 30 secondi mentre è bloccato e almeno ogni minuto altrimenti. Senza rete il blocco resta com'era.
- Se il programma viene chiuso durante un blocco (per esempio dal Task Manager), al riavvio manda un evento `manomissione` con `sotto_tipo: "chiuso_durante_blocco"`; nel frattempo il server vede il silenzio, come sempre.
  - Vale sia se Windows è rimasto acceso, sia se è stato poi riavviato o spento **pulito** mentre Pactum era già morto (il programma lo riconosce dall'ora dell'ultimo spegnimento pulito di Windows). Non vale dopo uno spegnimento/disconnessione/sospensione normali, né dopo uno spegnimento **non** pulito (corrente, schermata blu), né per un crash del programma. Il server non deve fare niente di diverso: è una `manomissione` come le altre.
- Se all'avvio il programma ha perso il suo stato del blocco salvato ma sa di essere stato bloccato, resta coperto con un elenco generico finché il server non risponde, e manda una `manomissione` con `sotto_tipo: "stato_blocco_perso"` (anche questa una `manomissione` come le altre per il server).
- Un `GET /api/faccende/blocco` che risponde `403`, o un `404`/`405` che **non** è il "Not Found"/"Method Not Allowed" di FastAPI, non toglie il blocco (potrebbe essere un errore di un proxy): il programma lo toglie solo su un `404`/`405` di un server che non ha mai mandato il `blocco`, o su un `401` (dispositivo revocato).

### Minecraft Java sul computer

- Minecraft nell'edizione Java gira come `javaw.exe` (o `java.exe`), cioè "Java", e finiva in `altro`. Dalla 0.13, quando il programma in primo piano è `javaw.exe` o `java.exe` e il titolo della sua finestra comincia con `Minecraft`, il computer lo conta come il programma **`exe:minecraft-java`**, nome leggibile `"Minecraft (Java)"`, categoria `giochi`. Il titolo della finestra si legge solo per questo controllo e si butta: non esce dal programma.
- Per il server `exe:minecraft-java` è una chiave `exe:` come le altre (anche per le regole: un limite su Minecraft Java è `exe:minecraft-java`).

### L'app del genitore (comportamento)

- **Famiglia**: l'elenco dei genitori e dei figli; "Aggiungi un genitore" (nome → codice di 6 cifre grande, con la scadenza); rinomina; togli. Un telefono nuovo si collega come genitore con indirizzo + codice di 6 cifre (resta anche il vecchio modo con indirizzo + codice d'accesso lungo, per il genitore 1).
- **Faccende**, per figlio: "Dai faccende" (uno o più titoli, con i titoli usati di recente a portata di dito, nota facoltativa, "Blocco: subito / dalle …"), l'elenco con lo stato, la foto a tutto schermo, **Boccia** (con una nota) entro 24 ore, **Annulla**.
- Se un dispositivo del figlio ha un'app più vecchia della 0.13 (`versione_app`), lo dice: lì il blocco non parte.
- Chi ha fatto cosa: il nome del genitore accanto a proposte, decisioni e faccende.

### Il server: foto e pulizia

- Le foto stanno in una cartella `foto/` accanto al database (`<cartella del db>/foto/<id della faccenda>.jpg`), mai dentro il database; la copia notturna del database non le comprende.
- Una volta al giorno (insieme alla copia notturna) e all'avvio si cancellano le foto arrivate da più di 30 giorni e i file senza una faccenda.
- Le foto si servono solo con un token valido (genitore, o dispositivo dello stesso figlio).

### Precisazioni (scritte costruendo il server 0.13, 02/10/2026)

Dicono quello che le sezioni qui sopra lasciavano aperto. Quelle segnate **(03/10)** vengono dalla revisione del server del 03/10/2026: chiudono buchi trovati dopo (foto, revoche, corpi grandi) e, dove cambiano qualcosa, lo dicono anche nella sezione sopra.

- **Genitori**
  - Il codice di un genitore vuole `"tipo": "genitore"`. Senza `tipo` (come lo mandavano le app 0.7 del figlio) → `409 { "errore": "tipo_non_corrispondente", "tipo_atteso": "genitore" }`, come con un tipo sbagliato. In tutti questi casi il codice non si consuma e il tentativo conta tra quelli falliti (come dalla v3.1).
  - `POST /api/genitori/{id}/codice` → `200 { "genitore": { … }, "codice", "scade_ts" }`, come per i dispositivi. Su un genitore revocato → `409 { "errore": "genitore_revocato" }`.
  - `PATCH /api/genitori/{id}` → `200` col genitore nella forma di `GET /api/genitori`.
  - `DELETE /api/genitori/{id}` → `200 { "id", "revocato": true }`; rifarlo su uno già revocato risponde ancora `200`. Annulla anche il suo codice aperto. I controlli, in quest'ordine: `404`, `non_te_stesso`, `ultimo_genitore`. Due genitori che si revocano a vicenda nello stesso momento: ne passa una sola, l'altra riceve `401` (chi la manda è appena stato revocato). Per questo, con un token valido, `ultimo_genitore` in pratica non capita più: resta come rete.
  - Il token di un genitore revocato risponde `401`, come quello di un dispositivo revocato.
  - (03/10) **Ogni scrittura di un genitore** (genitori, figli, dispositivi, proposte, risposte, sessioni, verdetti, segno, faccende) ricontrolla, nel momento in cui scrive, che chi la manda non sia stato revocato: una richiesta partita un attimo prima della revoca riceve `401 {"detail": "genitore revocato"}` e non scrive niente.
- **Chi ha fatto cosa**
  - Verdetto: `"da"` sta dentro l'oggetto `verdetto` della dichiarazione: `{ "verdetto", "nota", "registro", "ts_server", "da" }`. La frase `registro` resta quella della v2.1 ("confermato dal genitore per conto di …"): è congelata e le app la mostrano com'è.
  - Segno: `"da"` sta nella risposta di `POST /api/segno` (`{ "mandato", "ts_server", "da" }`). Il testo resta fisso e resta **uno al giorno per figlio**, chiunque dei genitori lo mandi.
  - `decisa_da` di una sessione è il genitore dell'ultima decisione (sulla sessione o su un suo cambio). Resta anche quando dopo il figlio la cambia; `null` se nessuno l'ha mai decisa. Una sessione di prima della v3.6 già `approvata` o `rifiutata` vale come decisa dal genitore 1.
  - `risposta_di` è `null` anche quando risponde il figlio (alle proposte del genitore) e finché nessuno ha risposto.
  - Tutti i genitori sono uguali anche nel ritirare: un genitore può ritirare la proposta di un altro genitore. Il figlio legge il nome di chi l'ha ritirata.
  - Nel `payload` di **ogni** notifica per il figlio nata da un gesto di un genitore c'è `"genitore": { "id", "nome" }`: `nuova_proposta`, `proposta_risposta`, `proposta_ritirata`, `verdetto`, `segno` (che quindi non ha più `payload: {}`), `sessione_risposta` e le notifiche delle faccende.
  - I messaggi: "Nuova proposta del genitore: …" diventa "Nuova proposta di Mamma: …"; "Il genitore propone di eliminare la regola" diventa "Mamma propone di eliminare la regola"; così per accettato, rifiutato, ritirato e per le sessioni. Il genitore 1 si chiama "Genitore" finché nessuno lo rinomina: "Genitore ha approvato la sessione «Studio»".
- **Letture del genitore**
  - Le notifiche del genitore che il server chiude da solo (l'avviso `sessione_da_approvare` sostituito da uno nuovo, v3.5) si chiudono per tutti i genitori.
  - `POST /api/notifiche/{id}/letta` su una notifica nata prima del genitore che chiama → `200` (per lui era già letta).
- **Faccende**
  - `blocco_da` va scritto in ISO 8601 col fuso (`+02:00` oppure `Z`). Nelle risposte è in UTC a secondi interi, come ogni `ts_server`. Una data passata, o adesso, diventa l'ora del server. "Più di 7 giorni avanti" vuol dire oltre adesso + 7 × 24 ore: 7 giorni esatti vanno bene.
  - Titolo e nota: gli spazi e gli a capo ai bordi si tolgono (come per i nomi), quindi `"Letto\n"` è `"Letto"`. Nella nota `\r\n` e `\r` diventano `\n`; una nota vuota è `null`. La nota di `boccia` ha le stesse regole della nota di una faccenda, e il corpo di `boccia` si può anche omettere.
  - `faccenda_annullata`: `messaggio: "<nome del genitore> ha annullato «<titolo>»"`. Le notifiche delle faccende hanno tutte `dispositivo_id: null`: quelle per il figlio arrivano a tutti i suoi dispositivi, quelle per i genitori (`faccenda_fatta`, `faccende_finite`) sono del figlio, non di un dispositivo.
  - Il **giro** di `faccende_finite` comincia quando il figlio passa da nessuna faccenda `da_fare` ad almeno una (anche quando una bocciatura riapre una faccenda dopo che erano finite). `faccenda_ids` sono le faccende `fatta` di quel giro, in ordine di `id`. Il messaggio dice "telefono e computer sbloccati" se l'ultima faccenda bloccava già (`blocco_da` passato) quando è arrivata la sua foto.
  - `PUT /api/faccende/{id}/foto`, i controlli in quest'ordine: `404 {"detail": "faccenda non trovata"}` (anche di un altro figlio), `422 solo_dal_telefono`, `413 {"errore": "foto_troppo_grande"}`, `422 foto_non_valida`, `409 bocciata_nel_frattempo` (solo con `?bocciature=N`, 03/10), `409 non_da_fare`. 4 MB = 4 × 1024 × 1024 byte, contati leggendo il corpo, anche senza `Content-Length`. Il server legge il corpo come JPEG qualunque sia il `Content-Type` (l'app manda `image/jpeg`). La "stessa foto" è quella che, ripulita dai dati nascosti, è uguale byte per byte a quella salvata; se il file nel frattempo è stato cancellato (dopo 30 giorni) → `409 non_da_fare`. Un telefono che perde la rete a metà invio non riceve niente e non si scrive niente.
  - (03/10) **La foto, nel dettaglio.** Il server tiene **solo** questi pezzi del JPEG: l'inizio (FF D8), l'intestazione dell'immagine (SOF), le tabelle (DHT, DQT, DRI), le scansioni (SOS) coi loro dati compressi, APP0 solo se comincia con `JFIF\0`, APP2 solo se comincia con `ICC_PROFILE\0` (i colori), APP14 solo se comincia con `Adobe` (serve a decodificare), e la fine (FF D9). Tutto il resto si butta: gli altri APPn (EXIF, XMP, IPTC, MPF, la miniatura JFXX…), i commenti, i JPGn, anche quando stanno tra le scansioni di un JPEG progressivo. Al primo FF D9 il file finisce: quello che segue (una seconda immagine come in Ultra HDR o MPF, il video di una foto in movimento) si butta. Dentro i dati compressi FF 00 e FF D0–D7 sono dati. → `422 foto_non_valida` se: manca FF D9; l'intestazione SOF non torna (da 1 a 4 componenti, lunghezza 8 + 3 × componenti, altezza e larghezza maggiori di zero); una scansione SOS non torna o non ha dati; ci sono più di 64 segmenti prima della prima scansione (o più di 256 in tutto).
  - `GET /api/faccende/{id}/foto` risponde con `Cache-Control: private, no-store`. Un `id` oltre i 64 bit → `404 {"detail": "faccenda non trovata"}`, come per le sessioni.
  - `GET /api/faccende/blocco` funziona anche col token del genitore, con `figlio_id` (senza, il primo figlio): è la stessa `blocco` della finestra. `prossimo` è `null` quando `attivo` è `true`.
  - `POST /api/sessioni/{id}/avvia`: `blocco_faccende` si controlla dopo i controlli della v3.5 (sessione, revoca, approvata, già in corso).
  - La pulizia delle foto toglie un file senza faccenda solo se ha più di un'ora (uno più giovane può essere una foto che sta arrivando proprio adesso). Gira anche quando la copia notturna è spenta.
  - (03/10) Il file della foto bocciata si toglie dopo che la bocciatura è scritta; se la bocciatura non si scrive, la foto resta al suo posto.
- **I corpi delle richieste (03/10, tutto il server)**
  - Un corpo di una richiesta sotto `/api/` oltre **8 MB** (8 × 1024 × 1024 byte) → `413 {"detail": {"errore": "corpo_troppo_grande"}}`, contato mentre arriva (un `Content-Length` più grande si ferma subito, il resto non si legge), con o senza token e qualunque `Content-Type` dichiari. Le foto delle faccende hanno il loro tetto di 4 MB. Il corpo legittimo più grande è il pacco di eventi del telefono, che manda tutta la coda in una volta: una fotografia d'uso e una dei siti per giorno, qualche KB ciascuna, cioè meno di 1 MB anche dopo tre mesi senza rete. Se comunque un'app riceve `413 corpo_troppo_grande` su `POST /api/eventi`, manda la coda in pacchi più piccoli (per esempio a metà) invece di riprovare lo stesso pacco.
- **Database e ritorno indietro**
  - Oltre a `genitori`, `faccende` e alla colonna `genitore_id` delle credenziali, nascono le tabelle dei codici dei genitori e delle loro letture, e le colonne di chi ha deciso su proposte, dichiarazioni e sessioni (vuote nelle righe di prima: valgono come del genitore 1). Le letture di prima non si spostano: una notifica del genitore già letta resta letta.
  - Tornare al server v3.5 dopo la migrazione si fa solo **rimettendo la copia `.prima-v3.6-…`** (o con `PACTUM_RIPRISTINA` di una copia della notte prima): un server v3.5 non sa di quale genitore è un token, e a un riavvio (se il genitore 1 è stato revocato o riabbinato) darebbe il token d'ambiente alla credenziale di un altro genitore.
  - (03/10) Se accanto al database c'è già una copia di quella migrazione (un avvio di prima ha copiato e poi non è riuscito a migrare), il server la riusa **solo se i dati del database sono ancora quelli della copia**. Dopo un ritorno alla v3.5 il server vecchio scrive dati nuovi: al secondo aggiornamento si fa una copia nuova, con un altro nome, e la vecchia resta. Vale per le copie di tutte le migrazioni (`.prima-v3-`, `.prima-v3.4-`, `.prima-v3.6-`).
  - (03/10) In più nasce la tabella della storia delle faccende (sola aggiunta: il database rifiuta di cambiarla o cancellarla).

### Compatibilità

- Database: tabelle nuove `genitori` e `faccende`; colonna `genitore_id` sulle credenziali; le letture delle notifiche dei genitori diventano per genitore. È un cambio di tabelle esistenti: **prima della migrazione la copia completa** `<db>.prima-v3.6-<data>`, come per la v3 e la v3.4.
- App 0.12 con server v3.6: funzionano come prima (l'app del genitore 0.12 è il genitore 1 e legge le sue notifiche); non conoscono le faccende, quindi **su un dispositivo 0.12 il blocco non parte**.
- App 0.13 con server vecchio: `/api/faccende` e `/api/genitori` rispondono `404`/`405` → l'app dice che per le faccende e i genitori serve aggiornare il server di Pactum.
- **Ordine sul NAS**: come sempre, prima gli APK e lo zip 0.13 in `server/apk`, poi la ricostruzione dell'immagine.

## v3.7 — telefono spento o in stand-by, e "lavori di casa" (04/10/2026, richieste di Andrea)

Due correzioni, nessuna funzione nuova.

1. **Un telefono spento o in stand-by non è un'interruzione.** Finora il telefono, a differenza del computer, non avvisava quando si spegneva, e a schermo spento Android addormentava Pactum: i battiti si fermavano e dopo 45 minuti il genitore riceveva "Il telefono non invia aggiornamenti", come se il figlio avesse staccato Pactum.
2. **Nei testi non si dice più "faccende" ma "lavori di casa"** (decisione di Andrea). Cambiano solo le parole che si leggono; i nomi tecnici restano (`/api/faccende`, `nuove_faccende`, `faccenda_fatta`, …).

Tutto il resto del contratto resta valido.

### Il telefono si spegne: `sospensione` anche dai telefoni

- Il telefono manda l'evento `sospensione` `{ "motivo": "spegnimento" }` quando Android si spegne o si riavvia (avviso di spegnimento di sistema), subito, prima che la rete se ne vada; se non ce la fa, lo manda alla riaccensione con il `ts_device` dello spegnimento (dagli eventi d'uso di Android, `DEVICE_SHUTDOWN`), insieme a `ripresa` `{ "motivo": "avvio" }`.
- Il server tratta i telefoni come i computer: dopo una `sospensione`, finché non arriva un segno di vita (battito o `ripresa`), `stato_silenzio` dà `silente: false`, `spento: true`, `spento_dal` = l'ora della sospensione. Per una `sospensione` consegnata in ritardo vale il suo `ts_device` (con le stesse tutele della chiusura delle sessioni: non nel futuro, non più di 48 ore prima dell'arrivo), così il genitore che aveva ricevuto un avviso di silenzio può sapere che il telefono era spento.
- Un telefono spento per più di 24 ore: come per il computer, l'app del genitore dice "spento, oppure Pactum non è partito".

### Il telefono in stand-by: i battiti continuano

- A schermo spento il telefono continua a mandare un battito circa ogni 15 minuti (sveglia permessa anche in stand-by, con l'esenzione dal risparmio batteria che Pactum chiede già). Così un telefono acceso in tasca o sul comodino non risulta mai silenzioso.
- Il silenzio (`silente: true`, oltre 45 minuti senza battiti) resta per i casi veri: Pactum fermato o tolto, telefono senza rete a lungo.

### L'app del genitore: avvisi di silenzio senza accuse

- Telefono spento: si vede "Telefono spento dalle …" come per il computer, nessun avviso.
- Se un avviso di silenzio è già partito e poi si scopre che il dispositivo era spento, l'app manda "Il telefono era spento dalle …: non è un'interruzione" (come già fa per il computer).
- Il testo del silenzio non accusa: "Il telefono non manda aggiornamenti dalle …: può essere senza rete o scarico, oppure Pactum è stato fermato."

### "Lavori di casa" nei messaggi del server

I `messaggio` delle notifiche cambiano così (i `tipo` e i `payload` no):
- `nuove_faccende`: "<nome del genitore> ti ha dato 3 lavori di casa" / "<nome del genitore> ti ha dato un lavoro di casa: «<titolo>»";
- `faccende_finite`: "<nome del figlio> ha finito i lavori di casa: telefono e computer sbloccati" / "<nome del figlio> ha finito i lavori di casa";
- `faccenda_fatta`, `faccenda_bocciata`, `faccenda_annullata`: come prima (contengono solo il titolo).
Nelle app: la scheda e la pagina si chiamano **Lavori di casa**, la barriera dice **"Prima i lavori di casa"**; al singolare "lavoro" (maschile: fatto, bocciato, annullato).

### Precisazioni (scritte costruendo il server 0.14, 04/10/2026)

- **La `sospensione` dai telefoni il server la accettava già**: la validazione degli eventi non guarda il tipo del dispositivo. Fino alla v3.6 finiva nel registro senza cambiare lo stato; dalla v3.7 il telefono risulta spento.
- **Il momento della sospensione** (quello di `spento_dal`) è il suo `ts_device` solo se cade **più di 2 minuti prima dell'arrivo e non più di 48 ore prima**; negli altri casi (un `ts_device` che manca o non è un'ora possibile, nel futuro, entro 2 minuti, oltre 48 ore) vale l'arrivo. Sono le tutele della chiusura delle sessioni, scritte una volta sola nel server. **Valgono anche per i computer**: prima, per una sospensione del computer consegnata in ritardo, `spento_dal` era l'arrivo.
- **Quale viene dopo, tra sospensione e ripresa**: il server le mette in fila per il loro momento (la stessa regola, anche per la `ripresa`) e, a parità, per ordine d'arrivo. Così, alla riaccensione, la sospensione recuperata e la ripresa possono arrivare nello stesso pacco **in qualsiasi ordine**: vince quella successa dopo. Senza `ts_device`, o entro 2 minuti dall'arrivo, conta l'ordine del pacco, come prima per il computer: "ripresa e poi sospensione" vuol dire che si è appena spento.
- **Un battito è un segno di vita** se è arrivato dopo il momento della sospensione: se la sospensione arriva in ritardo e il telefono aveva già mandato un battito dopo l'ora che dichiara, non è spento (ed è silente se tace da più di 45 minuti).
- **Testi**: oltre ai due messaggi qui sopra, il server non ha altri testi per le persone con "faccende". Restano com'erano i testi tecnici: il `detail` `"faccenda non trovata"` dei `404` (le app lo riconoscono, come `"sessione non trovata"`), i codici d'errore (`troppe_faccende`, `blocco_faccende`, …) e il log del server.

### Compatibilità

- Nessun cambio al database. Un server v3.6 con un telefono 0.14: la `sospensione` del telefono si registra ma non cambia lo stato (resta il silenzio come prima). Un server v3.7 con un telefono 0.13: niente `sospensione`, si comporta come prima.


## v3.8 — il tempo nelle due app, e il tempo che finisce (05/10/2026, richieste di Andrea)

Andrea, usando la 0.15: l'avviso a tutto schermo arriva tardi (a 31 su 30, non a 30 su 30, e con un ritardo in più); nell'app del figlio non si vede se una fascia oraria è stata rispettata; il figlio deve avere il grafico del tempo come il genitore; in tutte e due le app servono il tempo di ognuno dei 7 giorni, il totale della settimana e il totale del mese. Decisioni di Andrea (05/10): avviso a 30, "fuori regola" da 31; settimana e mese = **ultimi 7 e ultimi 30 giorni** (gli stessi delle medie); il grafico del figlio sta in Oggi, con una pagina Tempo completa.

Tutto il resto del contratto resta valido.

### Totali della settimana e del mese: `medie` con `totale`

- Ogni sotto-oggetto di `medie` (`settimana`, `mese`), ovunque compaia (`GET /api/finestra` di primo livello e `dispositivi[].medie`, e ora `GET /api/patto`), ha in più **`totale`**: la **somma** dei `totale_minuti` degli STESSI giorni che contano nella media (le fotografie vigenti del dispositivo nella finestra; finestra = da 6 / 29 giorni prima di oggi a oggi compreso, nel fuso del patto). Un intero ≥ 0. Forma: `{ "minuti": <media arrotondata>, "giorni": <giorni con dati>, "totale": <somma> }`.
- Il sotto-oggetto resta **`null`** se nella finestra non c'è nessuna fotografia (mai uno zero finto). I giorni senza fotografia non sono zero: le app scrivono quanti giorni avevano dati ("6 giorni su 7 con dati") quando `giorni` è minore della finestra.
- `totale_minuti` non valido (oltre 1440, mancante) vale 0 come per la media: la fotografia esiste, ma non sporca la somma.
- Le app mostrano `minuti` (la media) **così com'è**, senza rifare il conto da `totale` e `giorni` (il server arrotonda i ,5 al pari: rifacendolo potrebbe uscire un minuto diverso).
- Per coerenza con la somma, anche in **`uso_recente`** una fotografia salvata con `totale_minuti` oltre 1440 (possibile solo per quelle arrivate prima della v3.5) si legge **0**: la somma dei giorni del grafico torna sempre col `totale`.

### Il figlio vede i suoi tempi come il genitore

`GET /api/patto?tempi=1` (dispositivo), in più — **solo se la richiesta ha `tempi=1`** (i tempi pesano: con telefono e computer il patto passa da ~3 KB a ~90 KB, e il patto lo leggono spesso anche la sentinella del telefono e il programma del computer, che non li usano; senza `tempi=1` la risposta resta quella della v3.7):
- **`uso_recente`** e **`medie`** di **questo dispositivo**: identici, campo per campo, a `uso_recente` e `medie` dello stesso dispositivo in `dispositivi[]` di `GET /api/finestra` (stessi 8 giorni, stessa forma, `limite`/`regola_id`/`bonus` compresi, `sessioni_minuti` compreso), calcolati dalle **stesse funzioni** del server, come già `siti_recenti` e `striscia`;
- in ogni elemento di **`dispositivi[]`**: `uso_recente` e `medie` di quel dispositivo (identici alla finestra), così il figlio vede anche il computer.
È il principio di sempre: niente esiste nella finestra del genitore che il figlio non veda identico.

### Il tempo che finisce (comportamento dell'app del figlio; il protocollo non cambia)

- **A 30 su 30 l'avviso, da 31 il fuori regola.** Quando l'uso di una regola `limite_tempo` di questo telefono raggiunge il limite efficace (limite + bonus di oggi su quella regola) — al secondo per una regola su una sola app o sul `totale`; per una regola di **categoria** quando la somma dei minuti interi delle sue app (lo stesso conto del valutatore, di Oggi e del genitore) arriva al limite — l'app apre l'avviso a tutto schermo "il tempo è finito" (se c'è il permesso "Mostra sopra le altre app", altrimenti la notifica in alto) e manda una notifica. Non è uno `sforamento` e non va al server: per "al massimo 30 minuti" i 30 minuti sono permessi.
- Lo **`sforamento`** resta com'era: parte quando l'uso supera il limite efficace (minuti interi: a 31 su 30), una volta per regola per giorno, va al registro e al genitore, con la sua notifica. Se per quella regola e quel limite efficace oggi l'avviso del tempo finito è già apparso, allo sforamento niente secondo avviso a tutto schermo: solo la notifica.
- **Puntualità**: col telefono acceso e un'app che consuma una regola davanti, l'app guarda l'uso esattamente quando la regola arriva al limite (come già per i preavvisi a 5 e 1 minuto) e quando lo supera, non al giro del minuto.
- **Niente attese lunghe prima dell'avviso**: prima di registrare uno sforamento l'app rilegge il patto (per non segnarne uno falso dopo un bonus appena dato); quella lettura ha ora un'attesa massima di pochi secondi, poi vale la copia locale (come già senza rete). L'avviso del tempo finito usa la copia locale.
- Il programma del computer non cambia in questa versione.

### Compatibilità

- Nessun cambio al database. App 0.15 e programma del computer con server v3.8: non chiedono `tempi=1`, quindi il patto resta com'era; ignorano `totale`. App 0.16 con server v3.7: senza `totale` la riga dei totali non si mostra; senza `uso_recente` nel patto il grafico del figlio si basa sull'uso letto sul telefono (solo questo telefono, senza medie né totali), o si nasconde.


## v3.9 — i lavori di casa: modificarli, confermarli, cercarli (05/10/2026, richieste di Andrea)

Andrea: i genitori devono vedere per che ora hanno messo un lavoro di casa e poterlo modificare dopo averlo dato; dopo aver guardato la foto, il pulsante per vederla diventa quello per segnarlo come svolto; nello storico dei lavori una ricerca per nome. Decisione di Andrea (05/10): **"svolto" è la conferma del genitore; lo sblocco resta all'ultima foto**, come prima.

Tutto il resto del contratto resta valido (nomi tecnici invariati: `faccende`, `faccenda_*`).

### L'ora del lavoro (solo app)

Le app mostrano **sempre** l'ora del blocco di ogni lavoro da fare, anche quando il blocco è già partito: "Blocco dalle 16:00" / "Blocco da subito (14:02)" / "Blocco domani dalle 16:00", dal `blocco_da` che c'è già. Niente cambia nel protocollo.

### Modificare un lavoro: `PATCH /api/faccende/{id}` (genitore)

- Corpo: `{ "titolo"?, "nota"?, "blocco_da"? }`, almeno un campo (nessun campo → `422`).
  - `titolo`, `nota`: stesse regole della creazione; `"nota": null` o `""` toglie la nota.
  - `blocco_da`: una data con fuso, stesse regole della creazione (passata = subito, cioè l'ora del server; più di 7 giorni avanti → `422`); `null` = subito. Un campo assente resta com'è.
- Solo una faccenda `da_fare` → altrimenti `409 {"errore": "non_modificabile"}` (fatta, annullata). Una faccenda di un altro figlio o che non c'è → `404 {"detail": "faccenda non trovata"}`. Atomica con la foto: se la foto arriva mentre si modifica, chi arriva secondo trova la faccenda già `fatta` (`409 non_modificabile` per il `PATCH`; la foto vale).
- Se niente cambia davvero (stessi valori) → `200` con la faccenda, nessuna notifica, niente nella storia.
- Se qualcosa cambia: nella `storia` una voce `{ "tipo": "modificata", "ts", "genitore", "cambi": { "titolo"?: { "prima", "dopo" }, "nota"?: { "prima", "dopo" }, "blocco_da"?: { "prima", "dopo" } } }` (solo i campi cambiati); notifica al figlio `faccenda_modificata` (`dispositivo_id: null`), `messaggio: "<nome del genitore> ha cambiato «<titolo nuovo>»"` (se è cambiato il titolo: `"<nome del genitore> ha cambiato «<titolo vecchio>» in «<titolo nuovo>»"`), `payload: { "faccenda_id", "titolo", "genitore", "cambi" }`. Risposta `200` con la faccenda.
- **Il blocco segue `blocco_da`** come sempre (`GET /api/faccende/blocco` lo ricalcola): spostare l'ora più avanti toglie il blocco fino a quell'ora, metterla prima (o subito) lo fa partire. Il telefono, alla notifica `faccenda_modificata`, rilegge subito il blocco, come per le altre notifiche dei lavori.

### "Svolto": la conferma del genitore — `POST /api/faccende/{id}/conferma`

- Solo una faccenda `fatta` (con `foto_ts`), non ancora confermata → altrimenti `409 {"errore": "non_confermabile"}`. Vale anche dopo le 24 ore della bocciatura e anche se la foto è già stata cancellata (30 giorni).
- Corpo facoltativo **`{ "foto_ts": "…" }`**: il `foto_ts` della foto che il genitore ha guardato (l'app lo manda sempre). Se la faccenda ha intanto un `foto_ts` diverso (bocciata da un altro genitore e rifatta con una foto nuova) → `409 {"errore": "foto_cambiata"}` e non si conferma niente: nessuno conferma una foto che non ha visto. Si confronta l'istante (stesso istante con un altro fuso = uguale). Senza `foto_ts` (o `null`) si conferma come prima.
- La faccenda ha due campi nuovi: **`confermata_ts`** e **`confermata_da`** (`{ "id", "nome" }`), `null` finché nessuno conferma. Nella `storia`: `{ "tipo": "confermata", "ts", "genitore" }`.
- Una faccenda confermata **non si può più bocciare**: `POST …/boccia` → `409 non_bocciabile`. Atomica con la bocciatura: tra conferma e bocciatura insieme, la seconda riceve il suo `409`.
- Lo sblocco **non cambia**: è già avvenuto all'arrivo dell'ultima foto. Confermare non blocca e non sblocca niente.
- Notifica al figlio `faccenda_confermata` (`dispositivo_id: null`), `messaggio: "<nome del genitore> ha confermato «<titolo>»"`, `payload: { "faccenda_id", "titolo", "genitore" }`. Risposta `200` con la faccenda.
- Nelle app del genitore: dopo che **questo telefono** ha aperto la foto di una faccenda fatta e non confermata, il pulsante "Guarda la foto" diventa **"Segna come svolto"** (la foto resta apribile toccando il lavoro); confermata, la faccenda dice "Confermato da Mamma". Quale foto è stata guardata lo ricorda l'app, non il server (ogni genitore guarda col suo telefono).
- Database: due colonne nuove in `faccende`, con la copia del database prima della migrazione, come le altre volte.

### Cercare nello storico: `GET /api/faccende?cerca=…`

- Col parametro **`cerca`** (1–80 caratteri dopo aver tolto gli spazi ai bordi; vuoto = come senza) la risposta contiene **tutte** le faccende del figlio di **qualunque data e stato** (non solo gli ultimi 30 giorni) il cui `titolo` contiene il testo, senza distinguere maiuscole, minuscole e accenti; dalla più recente, al massimo **50**; `{ "faccende": [ … ], "altre": true|false }` (`altre` = ce ne sono più di 50).
- Vale per il genitore (con `figlio_id`, come senza `cerca`) e per il dispositivo (le faccende del suo figlio). Senza `cerca` tutto resta com'era.
- Le foto delle faccende vecchie possono non esserci più (`foto: false`): le app lo dicono.

### Precisazioni (scritte costruendo il server v3.9, 05/10/2026)

- **Database**: oltre alle colonne `confermata_ts` e `confermata_genitore_id` (nell'API `confermata_da`), la migrazione ricostruisce la tabella della storia dei lavori per accettare i tipi `modificata` e `confermata` e la colonna `cambi` (stessi id, stesso contatore, stesso indice, sempre in sola aggiunta). Copia `<db>.prima-v3.9-…` prima, tutto in una transazione, una volta sola.
- **"Stesso valore" per `blocco_da`**: lo stesso istante (anche scritto con un altro fuso) non è un cambio; "subito" (`null` o una data passata) su un lavoro che blocca già lascia l'ora com'è (niente avviso per niente); altrimenti vale la regola della creazione.
- **Ordine dei controlli del `PATCH`**: prima il corpo (`422`, anche `blocco_da` oltre i 7 giorni), poi `404`, poi `409`.
- **`cerca` oltre 80 caratteri** → `422`. "Senza accenti" = senza segni sulle lettere e senza maiuscole/minuscole ("ß" vale "ss"); `%` e `_` sono caratteri normali.
- Le voci di `blocco.da_fare` restano nella forma ridotta di prima (senza i campi nuovi).
- Un lavoro bocciato è di nuovo `da_fare`, quindi si può modificare.

### Compatibilità

- App 0.16 con server v3.9: non conoscono `PATCH`, `conferma`, `cerca` (non li chiamano); ignorano `confermata_ts`/`confermata_da`; le notifiche `faccenda_modificata` e `faccenda_confermata` le mostrano col `messaggio` del server, come ogni tipo che non conoscono. Il programma del computer non cambia.
- App 0.17 con server v3.8: `PATCH`, `conferma` e `cerca` rispondono `404`/`405` o ignorano il parametro → le app nascondono "Modifica" e "Segna come svolto", e per la ricerca dicono "serve aggiornare il server".

## v4.0 — lavori approvati, il computer che non resta chiuso, la Sessione Studio (07/10/2026, decisioni di Andrea)

Tre novità e una precisazione.

1. **I lavori di casa si sbloccano solo con l'approvazione.** Mandare la foto non basta più: telefono e computer restano bloccati finché **un** genitore (uno qualsiasi) non approva la foto di ogni lavoro che blocca. Non c'è scadenza: se nessuno approva, resta bloccato. Approvare è la conferma della v3.9 («svolto»), che da adesso sblocca.
2. **Il programma del computer non resta chiuso.** Un'attività pianificata di Windows lo riapre da sola; uno spegnimento annullato non lo lascia chiuso; all'avvio non rimette un blocco vecchio prima di aver sentito il server.
3. **La Sessione Studio.** Dal lunedì al venerdì alle 15:00 parte da sola, senza eccezioni, anche senza rete. Telefono e computer lasciano usare solo app, programmi e siti di una lista approvata. Si chiude solo dopo le 16:00 **e** dopo almeno un'ora di attività cronometrata col timer dell'app del telefono; chiudendo, il figlio scrive cosa ha fatto. Durante lo Studio il blocco dei lavori aspetta.
4. **Fasce orarie:** un uso di meno di un minuto dentro una fascia non è uno sforamento (parte D).

Lo Studio è la seconda cosa di Pactum che limita per un orario (dopo i lavori di casa), ed è la **prima** che, sul computer, copre dei **siti**: il patto etico della v2.3 (`:286`) qui viene emendato in modo esplicito (v. «Lo Studio e i siti»). Come sempre: chi la rompe (ferma Pactum, toglie un permesso, chiude il programma del computer) lo può fare, ma resta nel registro e i genitori lo vedono.

Tutto il resto del contratto resta valido. I nomi tecnici non cambiano: `faccende`, `faccenda_*`, `conferma`, `da_fare`.

### A. I lavori di casa si sbloccano quando un genitore approva

#### La regola

- Un lavoro è **aperto** in due casi:
  - è `da_fare`;
  - è `fatta`, ma la sua foto (arrivata dalla v4.0) **non è ancora stata approvata** (v. `da_approvare`).
- Un lavoro aperto **blocca** dal suo `blocco_da`. Il blocco del figlio è attivo se almeno un lavoro aperto blocca. Vale su tutti i suoi dispositivi, come dalla v3.6.
- **Approvare = la conferma della v3.9** (`POST /api/faccende/{id}/conferma`). Basta un genitore qualsiasi.
- **Nessuna scadenza.** Se nessuno approva, il blocco resta. Il server non approva mai da solo.
- **Lavoro fatto in anticipo.** Il figlio manda la foto prima di `blocco_da` e a quell'ora nessuno l'ha ancora approvata: il lavoro blocca da `blocco_da` finché un genitore non approva. Se un genitore approva prima dell'ora, il blocco non parte.

#### Quali lavori chiedono l'approvazione: `faccende_approvazione_dal`

- La regola nuova vale **solo per le foto arrivate dalla v4.0 in poi**. Le foto arrivate prima hanno già sbloccato (regola v3.6–v3.9) e **non tornano a bloccare**.
- Al primo avvio della v4.0 il server scrive, una volta sola, la riga `faccende_approvazione_dal` nella tabella `patto`: l'ora di quell'avvio, in UTC. Non cambia più.
- Un lavoro `fatta` **chiede l'approvazione** se `foto_ts >= faccende_approvazione_dal` e `confermata_ts` è `null`.
- Un lavoro `fatta` con la foto arrivata **prima** resta come nella v3.9: sbloccato, confermabile (resta un riconoscimento), bocciabile entro 24 ore. Nessuna riga vecchia si riscrive: riempire `confermata_ts` a posteriori falserebbe il registro.
- **Niente colonne nuove** sulla tabella `faccende`: la riga in `patto` basta, e l'informazione «questo lavoro va approvato» si ricava da `foto_ts`, `confermata_ts` e quella data.

#### Il campo `da_approvare`

- Ovunque compare una faccenda (non nelle voci ridotte del `blocco`) c'è in più **`da_approvare`**: `true` se `stato = "fatta"`, `confermata_ts = null` e `foto_ts >= faccende_approvazione_dal`; `false` negli altri casi. Il server lo calcola, non è salvato.

#### Il calcolo del blocco (e di `rimandato`)

`GET /api/faccende/blocco` (e il `blocco` dentro patto, finestra e `GET /api/faccende`) diventa:

```json
{ "attivo": true, "dal": "…", "prossimo": null, "rimandato": false,
  "studio": { "in_corso": false, "id": null, "inizio_ts": null },
  "da_fare": [
    { "id": 5, "titolo": "Svuota la lavastoviglie", "nota": null, "blocco_da": "…",
      "creata_da": { "id": 2, "nome": "Mamma" }, "bocciature": 0, "ultima_bocciatura": null,
      "stato": "fatta", "foto_ts": "2026-10-07T13:10:00+00:00" } ] }
```

- **`attivo`**: c'è almeno un lavoro **aperto** (`da_fare`, oppure `da_approvare`) con `blocco_da` già passato.
  - `attivo` **non** tiene conto dello Studio. Dice soltanto che il blocco è dovuto. Così un'app vecchia, che lo Studio non lo conosce, resta più stretta (bloccata) e mai più larga.
- **`dal`**: il più vecchio di quei `blocco_da`.
- **`prossimo`**: se non è attivo, il `blocco_da` futuro più vicino tra i lavori aperti (`null` se `attivo`). Serve a partire da soli all'ora giusta anche senza rete; conta anche una foto mandata in anticipo e non approvata.
- **`da_fare`**: **tutti i lavori aperti** (da fare e da approvare), dal più vecchio. Il nome resta quello di prima perché le app e i programmi vecchi lo leggono così. Ogni voce ha la forma ridotta di prima **più** due campi:
  - **`stato`** ∈ `"da_fare"` | `"fatta"`;
  - **`foto_ts`**: `null` per un lavoro da fare, l'ora della foto per uno che aspetta l'approvazione.
  - Le app e i programmi che non li conoscono li ignorano (tolleranza evolutiva) e restano bloccati come prima.
- **`rimandato`** (nuovo): `true` quando `attivo` è vero **e** c'è una Sessione Studio in corso del figlio. Il blocco aspetta la fine dello Studio (v. parte C). `attivo` resta vero: un dispositivo che perde la rete durante lo Studio sa che, alla fine, deve bloccare.
- **`studio`** (nuovo): `{ "in_corso": bool, "id": n|null, "inizio_ts": "…"|null }` della Sessione Studio in corso. Il computer la legge da qui per sapere quando lo Studio finisce.
- Funziona come prima anche col token del genitore (`figlio_id`).

#### La foto: `PUT /api/faccende/{id}/foto` (cambia)

- Controlli, ordine e risposte come nella v3.6/v3.9.
- Il lavoro diventa `fatta`. **Il blocco non cambia**: la foto non sblocca più, aspetta l'approvazione.
- Una foto mandata non si sostituisce: su un lavoro `fatta` → `409 {"errore": "non_da_fare"}` come prima. Per rifare una foto serve prima una bocciatura.
- Notifica ai genitori `faccenda_fatta` (`payload` invariato: `{ "faccenda_id", "titolo" }`):
  - `messaggio: "<figlio> ha mandato la foto di «<titolo>»: aspetta la vostra approvazione"`.
  - **Un solo avviso aperto per lavoro**: una `faccenda_fatta` nuova sullo stesso lavoro prende il posto della precedente che un genitore non ha ancora letto (la vecchia si segna come letta per tutti), come per `sessione_da_approvare` (v3.5). Anche un'approvazione o una bocciatura segnano l'avviso come letto per tutti i genitori.
- `faccende_finite` **non parte più qui**: si sposta all'ultima approvazione (v. sotto).

#### Approvare: `POST /api/faccende/{id}/conferma` (cambia)

- Corpo, controlli e errori sono quelli della v3.9: `non_confermabile`, `foto_cambiata`. Restano `confermata_ts`, `confermata_da` e la voce `confermata` nella storia. È atomica con la bocciatura e con un'altra approvazione.
- **Per un lavoro `da_approvare` il `foto_ts` nel corpo è obbligatorio**: senza → `422`. Nessuno approva una foto che non ha visto. Un `foto_ts` diverso da quello del lavoro (bocciato da un altro genitore e rifatto) → `409 {"errore": "foto_cambiata"}`.
  - Per un lavoro **di prima della v4.0** (foto vecchia, già sbloccato) `foto_ts` resta facoltativo, come nella v3.9.
- **Effetto su un lavoro `da_approvare`:** smette di essere aperto. Se era l'ultimo che bloccava, **il blocco finisce**:
  - il telefono che riceve la notifica si sblocca subito;
  - gli altri dispositivi si sbloccano alla loro prossima domanda, entro un minuto.
- I controlli, in quest'ordine: **corpo malformato** (`422`: i tipi dei campi); **`404`** (lavoro non trovato); **`foto_ts` mancante** su un lavoro `da_approvare` (`422`) — questo controllo viene **dopo** il `404`, perché per sapere che un lavoro è `da_approvare` bisogna prima averlo trovato; **`non_confermabile`**; **`foto_cambiata`**.
- **Approvazione ripetuta (idempotente).** Se la risposta si perde e l'app riprova, una seconda approvazione **dello stesso genitore, con lo stesso `foto_ts`, su un lavoro che lui ha già approvato** → `200` con il lavoro, **senza** notifiche nuove e senza un secondo sblocco. Così «Approva» toccato con la rete che balla non diventa un `non_confermabile` mentre telefono e computer sono già sbloccati. (Un altro genitore che approva un lavoro già approvato riceve `non_confermabile` come nella v3.9: lì non è un doppione, è una seconda decisione.)
- Notifica al figlio `faccenda_confermata` (`dispositivo_id: null`):
  - `messaggio: "<genitore> ha approvato «<titolo>»"`; **se il blocco era attivo e non rimandato** e adesso finisce, in coda `": telefono e computer sbloccati"`; **se il blocco era rimandato dallo Studio** (`rimandato`), in coda `": il blocco non partirà a fine Studio"` (niente sblocco annunciato, perché niente era coperto);
  - `payload: { "faccenda_id", "titolo", "genitore", "sblocca": true|false }`.
- Notifica ai genitori `faccenda_confermata` (per loro un tipo nuovo), stessa forma, **già letta per chi ha approvato**: serve all'altro genitore per non approvare due volte.
- **`faccende_finite`** (ai genitori) parte quando un'approvazione chiude il giro, cioè quando non resta nessun lavoro da fare né da approvare:
  - `messaggio: "<figlio> ha i lavori di casa tutti approvati"`, più `": telefono e computer sbloccati"` **solo se** il lavoro appena approvato bloccava già **e non era rimandato dallo Studio**;
  - `payload: { "faccenda_ids" }` (le faccende confermate del giro).
- **Annulla** e **modifica** restano solo per un lavoro `da_fare` (`non_annullabile`, `non_modificabile`). Un lavoro con la foto si approva o si boccia.

#### Bocciare (cambia)

- Un lavoro **`da_approvare`** si boccia **senza limite di tempo**, finché nessuno l'ha approvato (cade il tetto delle 24 ore in attesa). Mai dopo l'approvazione (`non_bocciabile`, come v3.9).
- Un lavoro **di prima della v4.0** (foto vecchia): si boccia entro 24 ore, come prima.
- L'effetto è quello della v3.6: il lavoro torna `da_fare` con `blocco_da` = adesso (il blocco riparte subito, oppure resta `rimandato` se il figlio è in Studio); la foto si cancella.

#### Le foto da approvare non si cancellano

Una frase sola, uguale qui e nel ritocco a `:839`:

- La foto di un lavoro `da_approvare` **non si cancella**: nessuno approva una foto che non può più guardare.
- La foto di un lavoro **approvato** si cancella **30 giorni dopo l'approvazione** (`confermata_ts`).
- Le foto di **prima della v4.0** si cancellano 30 giorni dopo l'arrivo, come prima.
- **Bocciare** cancella la foto subito (effetto v3.6): non c'è un «30 giorni dopo la bocciatura».

#### `GET /api/faccende` (cambia)

- I lavori `da_approvare` compaiono sempre, come le `da_fare`, anche oltre i 30 giorni (finché aspettano).
- In più **`blocco`**, uguale a `GET /api/faccende/blocco`: la scheda Lavori dell'app del genitore non lo calcola più da sola.

#### Il giro

- Il giro resta aperto finché il figlio ha un lavoro aperto, anche solo da approvare. Una bocciatura dopo le foto resta quindi nello stesso giro.
- `faccende_finite` parte quando nel giro non resta nessun lavoro da fare né da approvare (cioè all'ultima approvazione).

#### Dove si vedono

- `GET /api/famiglia`, per ogni figlio, in più: `faccende_da_approvare` (quante) e `blocco_rimandato`. `blocco_attivo` è `blocco.attivo`. `faccende_da_fare` resta il numero dei soli `da_fare` (il tetto di 20 conta solo quelli).
- `GET /api/finestra`, in più: `faccende_da_approvare`.

#### Il telefono (0.18)

- Segue `attivo` del server: **nessuno sblocco locale**. Alla notifica `faccenda_confermata` (aggiunta a `CAMBIANO_IL_BLOCCO`) rilegge subito il blocco.
- La pagina dei lavori e la barriera mostrano due gruppi:
  - **«Da fare»**, con «Scatta la foto»;
  - **«Aspettano l'approvazione»**: «Foto mandata alle 16:10 · aspetta che un genitore la approvi», senza «Scatta la foto».
- Testo della barriera: «Si sblocca quando un genitore ha approvato la foto di ogni lavoro».
- Una foto in coda «mandata» resta tale finché il lavoro è tra gli aperti, non più solo per 24 ore.
- **La coda delle foto non butta mai una foto ancora da mandare.** Nella v4.0 un lavoro resta aperto finché non è approvato, quindi più di 30 lavori aperti insieme potrebbero riempire la coda (tetto di 30, `CodaFoto`). Quando serve posto, la coda toglie **prima** le foto già mandate (quelle che aspettano solo l'approvazione), **mai** una foto scattata e non ancora consegnata. (In più il tetto di 20 lavori che si possono dare, `troppe_faccende`, resta sui soli `da_fare`: i lavori già fotografati e in attesa non lo riempiono.)

#### Il computer (0.18)

- Segue `attivo` e l'elenco come prima; copre tutti gli schermi.
- L'elenco mostra, accanto a un lavoro con `foto_ts`, «foto mandata, aspetta l'approvazione».
- La frase della copertura: «Si sblocca da solo quando un genitore ha approvato la foto di ogni lavoro».

#### L'app del genitore (0.18)

- **Lavori:** un gruppo **«Da approvare»** in cima; **«Approva»** compare dopo che questo telefono ha aperto la foto (come «Segna come svolto» della v3.9); **«Boccia»** resta finché il lavoro non è approvato.
- **Domanda prima di approvare:** «Approvi «<titolo>»? Non si potrà più bocciare.» Se è l'ultimo che blocca: «…Telefono e computer di Luca si sbloccano.»
- **Stato del blocco:** sempre quello del server (`blocco` di `GET /api/faccende`), mai ricalcolato dall'elenco. «Bloccato dalle 16:00 · 1 foto da approvare» / «Il blocco parte a fine Studio».
- **Panoramica:** «N foto da approvare»; contano anche nel numero di «Da decidere».
- Notifica `faccenda_fatta`: «…tocca per vedere la foto e approvarla».

### B. Il programma del computer non resta chiuso

Quasi tutto è comportamento del programma del computer dalla 0.18: il server accetta già un `motivo` qualsiasi nella `ripresa` e un `sotto_tipo` qualsiasi nella `manomissione`. **Una sola cosa cambia nel server**, la rete di sicurezza qui sotto, perché i segnali del PC non bastano quando il programma non riparte più.

#### La rete di sicurezza lato server (il computer che sparisce durante un blocco o uno Studio)

Il guardiano, l'eseguibile, l'attività pianificata, `programma_chiuso` e `chiuso_durante_studio` nascono **tutti dal programma stesso al suo prossimo avvio**. Se il programma non riparte più — il figlio lo chiude dal Task Manager e poi **rinomina o sposta `Pactum.exe`** (ha pieni diritti sulla sua cartella profilo, `%LOCALAPPDATA%\Pactum`), così l'attività lancia un percorso che non esiste e non parte niente — nessuno di quei segnali arriva mai. Serve una rilevazione che **non dipenda dal riavvio del PC**:

- Il server conosce già lo stato del computer: era `spento: false` ed era **in blocco o in Studio** (lo sa da `GET /api/faccende/blocco` e dallo Studio in corso). Se da quel computer **smette di arrivare il battito** per più della finestra attesa (battito ogni ~15 minuti: soglia del silenzio, 45 minuti) **senza** un evento pulito di `sospensione`/spegnimento, il server scrive da solo una `manomissione` `{ "sotto_tipo": "computer_sparito", "durante": "blocco" | "studio", "dal": <ultimo battito>, "ts_server": <adesso> }` e avvisa i genitori. È distinta dallo spegnimento normale della sera, che manda `sospensione` `spegnimento` (→ `spento: true`, nessuna accusa).
- **Limite noto, scritto:** il server non può distinguere con certezza un programma ucciso da una lunga assenza di rete del PC (tutti e due smettono di battere senza `sospensione`). Perciò il testo ai genitori non accusa («Il computer di Luca non risponde durante il blocco/lo Studio: può essere senza rete, oppure Pactum è stato fermato»). Resta però un avviso **attivo e puntato**, non il silenzio generico.
- L'eseguibile vive in una cartella scrivibile dal figlio: **spostare, rinominare o cancellare `Pactum.exe` è trattato come il caso "programma assente"** (l'attività punta a un percorso fisso, quello scritto all'ultimo avvio; se non c'è, non parte niente, e la rete di sicurezza qui sopra lo coglie). È un **limite noto**: un'installazione nella cartella utente è modificabile dal figlio, e la garanzia vera contro un Pactum ucciso e nascosto è questo controllo lato server, non un segnale del PC.

#### Il guardiano: un'attività pianificata di utente

- A ogni avvio il programma **crea**, o **ripara** se è cambiata, un'attività dell'Utilità di pianificazione per l'account Windows dove gira:
  - nome **«Pactum»**, nella cartella principale, **visibile**; descrizione «Riapre Pactum se si chiude»;
  - **nessun diritto di amministratore** (l'account del figlio è standard: un account standard crea attività per sé);
  - parte **all'accesso dell'utente e poi ogni minuto**, senza fine; azione: `"<percorso>" --guardiano`;
  - **nessun limite di durata** (di base Windows ferma un'attività dopo 72 ore); parte e resta accesa anche a batteria; «se è già in esecuzione non avviarne un'altra»; priorità normale;
  - gira solo quando il figlio ha fatto l'accesso.
- La voce di avvio di Windows (`HKCU\…\Run`) resta come riserva.
- **`--guardiano`** (opzione nuova, da aggiungere accanto a `--avvio`):
  - se Pactum è già vivo su quell'account, esce subito **senza aprire niente**;
  - se non lo è, parte come all'avvio di Windows (senza finestra).
- **`--avvio` e `--guardiano` escono in silenzio** quando un'istanza c'è già. Oggi un secondo avvio chiama sempre `ChiediApertura` e apre la finestra (`Program.cs:181-185`): per il guardiano, ogni minuto si aprirebbe la finestra. Solo un avvio **a mano** (doppio clic, senza opzioni) chiede all'istanza viva di mostrarsi, come oggi.
- **Niente comportamenti da programma malevolo** (Bitdefender ha già messo in quarantena un exe che si auto-copiava): nessuna copia dell'eseguibile, nessuna iniezione, nessuna attività nascosta o con un nome finto, nessun secondo processo che sorveglia il primo, nessun servizio. La 0.18 si prova sul PC di casa con Bitdefender acceso prima del rilascio.
- **Attività tolta o disattivata** (il figlio la può togliere, è sua): il programma la ricrea e manda una `manomissione` `{ "sotto_tipo": "guardiano_assente", "stato": "mancante" | "disattivata" }`, una volta ogni volta che la trova tolta; se non riesce a crearla, `{ "sotto_tipo": "guardiano_assente", "stato": "non_creata", "errore": "<codice>" }`, al massimo una volta al giorno. Il controllo all'avvio e ogni 15 minuti.
- **Nessuna istanza chiude un'altra istanza** (correzione: la bozza diceva «se parte una versione più alta, la vecchia si chiude», ma per farlo ci vorrebbe proprio quel segnale di chiusura che la riga sotto vieta — è il buco del vecchio evento `.Esci`, e qualunque processo dell'account lo imiterebbe). Quindi: **niente chiusura comandata, nemmeno per l'aggiornamento**, e **niente versione registrata in HKCU** (l'account è del figlio, che lì può scrivere una versione finta).
  - Un avvio **a mano** di una versione più alta, con una più bassa viva, mostra: «È aperta una versione più vecchia di Pactum. Chiudila dal menu (icona vicino all'orologio → Chiudi Pactum) e riapri questo entro un minuto», poi esce. Dalla 0.18 la domanda di «Chiudi Pactum» dice anche che Pactum si riapre da solo entro un minuto (lo fa l'attività).
  - **Procedura di aggiornamento dalla 0.18 (per chi installa):** 1) scompattare lo zip in una cartella **nuova**, mai sopra quella in uso (i file dell'exe in uso sono bloccati, e l'attività riaprirebbe il vecchio entro 60 s); 2) **fuori da blocco e Studio**, «Chiudi Pactum» dal menu (manda `programma_chiuso` volontario, come oggi); 3) aprire il nuovo `Pactum.exe` entro 60 secondi; 4) il nuovo riscrive la voce Run e l'attività col suo percorso. Dalla **0.14 alla 0.18** (nessuna attività ancora): chiudere la 0.14 dal menu, scompattare, aprire la 0.18.

#### Il mutex occupato da un altro processo

Oggi un secondo avvio esce subito se il mutex `Local\Pactum.Computer…` è già preso (`Istanza.cs`, `Program.cs:179-185`). Basta una riga di PowerShell che tiene quel mutex: il guardiano parte ogni minuto e ogni volta esce senza dire niente, e per il server il PC risulta spento. Il repo è **pubblico**, quindi i nomi degli eventi e il protocollo sono noti: la risposta non può essere un semplice evento che chiunque fa scattare.

- **La prima istanza ascolta subito.** Appena preso il mutex, e **prima di ogni altro lavoro** (interfaccia, voce Run, attività, motore — oggi invece `Istanza.Ascolta()` è dentro il costruttore di `ContestoPactum`, dopo `AssicuraUi`, `RegistraAvvio` e il costruttore del motore), la prima istanza crea l'evento di risposta e si mette in ascolto su un filo del pool. Risponde senza aprire la finestra.
- **`--guardiano` non si fida del solo mutex.** Trovato il mutex preso, chiede e aspetta **2 secondi**; se nessuno risponde, **riprova una volta dopo 10 secondi**. Questo evita l'accusa falsa all'accesso lento: al login la voce Run e l'attività partono insieme, e col disco freddo e l'antivirus la prima istanza può metterci più di 2 secondi a essere pronta.
- **Chi risponde deve essere davvero Pactum.** Il guardiano **non** manda `istanza_occupata` se chi tiene il mutex è un processo `Pactum.exe` (si controlla il nome del file del processo che tiene la finestra/l'evento di risposta). Un falso risponditore (uno script che imita l'evento) non è `Pactum.exe`: contro quello il guardiano **parte lo stesso** e manda `istanza_occupata`. **Limite noto:** un figlio con pieni diritti sul profilo potrebbe nominare `Pactum.exe` un suo programma; la garanzia vera contro un Pactum che non gira resta la **rete di sicurezza lato server** (il battito del computer: se il guardiano crede vivo ma il server non riceve battito, scatta «computer sparito»).
- **Se parte lo stesso** (nessuno ha risposto alle due prove e nessun processo `Pactum.exe` tiene il mutex): l'istanza partita prende un **secondo mutex fisso** (`Local\Pactum.Computer.Riserva`, **senza suffisso suo**) e da lì risponde **anch'essa** alle richieste dei guardiani dei minuti dopo, così non ne nasce uno nuovo ogni minuto. Manda `manomissione` `{ "sotto_tipo": "istanza_occupata" }` **una volta sola per accensione**, non a ogni minuto. Un guardiano che trova viva un'istanza di Pactum esce **sempre** in silenzio: due Pactum vivi insieme non ci sono mai.
- **Nessun segnale fa chiudere Pactum.** Resta tolto il vecchio evento `.Esci` (0.13): chiunque, nello stesso account, potrebbe farlo scattare. Chi vuole chiudere Pactum usa il menu (lascia traccia) o il Task Manager.

#### Uno spegnimento che non avviene

- **Alla fine della sessione di Windows** il programma fa la sua contabilità come oggi (sospensione, sforamenti, fotografie, entro 2,5 secondi). Ma **non si chiude da solo**: lo chiude Windows, se lo spegnimento va avanti. Correzione sul codice: oggi l'uscita **non** è in `SessionEnding` (`SuFineSessione`, che fa solo i conti) ma in **`SessionEnded`** (`SuSessioneFinita` → `Esci` → `ExitThread`, `ContestoPactum.cs:456-464`). In `SuSessioneFinita` si **toglie `Esci`**: si fa la contabilità (`FineSessione` con `soloSeMancante`, come oggi) e si resta vivi finché Windows chiude il processo.
- **La prova dell'annullamento è il messaggio di Windows.** Lo spegnimento annullato lo dice `WM_ENDSESSION` con `fEndSession = FALSE` (letto nella finestra nascosta del programma): è la prova vera. Il **timer di 60 secondi** resta solo come **riserva** (uno spegnimento forzato non manda `SessionEnding`, quindi il timer deve poter partire anche dal primo dei due eventi, `SessionEnding` **o** `SessionEnded`). Quando l'annullamento è accertato — dal messaggio o dal timer — il programma:
  - manda `ripresa` `{ "motivo": "spegnimento_annullato", "avvio_sistema_ts": ms }` (per il server è un segno di vita: il computer torna `spento: false`);
  - **azzera il segno «fine sessione già fatta»**: un `SessionEnding`/`SessionEnded` che arriva dopo rifà la contabilità e **rimanda la `sospensione`**. Senza questo, se lo spegnimento è solo rimandato (un'altra app tiene aperta la schermata «queste app impediscono l'arresto») e poi avviene davvero, il computer resterebbe `spento: false` e muto, e dopo 45 minuti `silente`: un computer spento che risulta acceso.
  - ricontrolla blocco e Studio e torna a coprire se serve.
- **Se il programma era stato chiuso ma Windows no** (riconosciuto al riavvio, nello stesso avvio di Windows, leggendo `HKLM\…\Control\Windows\ShutdownTime` in sola lettura, come oggi per `chiuso_durante_blocco`):
  - dopo una chiusura **`spegnimento`** non seguita da uno spegnimento pulito: `ripresa` `{ "motivo": "spegnimento_annullato", "dal": ms }`, e se era in blocco o Studio una `manomissione` `{ "sotto_tipo": "programma_chiuso", "dal", "al", "causa": "spegnimento_annullato" }`;
  - dopo una chiusura **`disconnessione`** (uscita dall'account o cambio utente, **non** uno spegnimento annullato): al rientro nello stesso avvio si manda `ripresa` `{ "motivo": "accesso" }` (come oggi), e se era in blocco o Studio una `manomissione` `{ "sotto_tipo": "programma_chiuso", "dal", "al", "causa": "disconnessione" }`. (La bozza chiamava tutti e due «spegnimento_annullato»: una disconnessione non lo è.)
  - la `manomissione` `programma_chiuso` parte **solo se** il programma era chiuso **durante un blocco o uno Studio** (come oggi `BloccatoFaccende`, esteso allo Studio). Fuori dal blocco e dallo Studio non è una manomissione: il registro dichiara, senza accusare, che Windows non si è spento.
  - se in quel momento era coperto, in più `chiuso_durante_blocco` o `chiuso_durante_studio`.

#### All'avvio: prima il server, poi la copia

- Prima di coprire con lo stato salvato (blocco e Studio), il programma chiede `GET /api/faccende/blocco` e aspetta al massimo **5 secondi**:
  - risposta `200` → vale quella, e si salva;
  - nessuna rete all'avvio, errore di rete, `5xx` o tempo scaduto → vale la **copia salvata**: bloccato resta bloccato, in Studio resta in Studio;
  - `401` e `404`/`405` → come dice la v3.6.
- In quei 5 secondi non copre niente (oggi copre subito dalla copia, `Motore.cs:145,739`). Lo stato generico (`stato_blocco_perso`) segue la stessa regola.

#### La copertura non si chiude da fuori

- Nessuna richiesta di chiusura chiude una finestra di copertura (del blocco o dello Studio). Fa eccezione solo lo spegnimento di Windows. Una copertura che manca si rifà entro un secondo (`FinestraBlocco.cs:268-277`, che oggi rifà solo quando ne manca una e lascia in lista quelle chiuse da fuori: da correggere).

#### Tornare alla 0.14 o togliere Pactum dal computer

- **Prima si toglie l'attività «Pactum»** dall'Utilità di pianificazione dell'account del figlio (Libreria → Pactum → Elimina, oppure `schtasks /delete /tn Pactum /f`). La 0.14 ignora le opzioni che non conosce (`Program.cs:56-80`): per lei `--guardiano` è un avvio a mano, quindi se l'attività resta, ogni minuto **aprirebbe la finestra di Pactum** davanti al figlio mentre studia o gioca.

#### Eventi del computer, nuovi o cambiati

- `ripresa` con `motivo`: `spegnimento_annullato` (nuovo) e `accesso` (già esistente, usato ora anche dopo una disconnessione in blocco/Studio).
- `manomissione` con `sotto_tipo`: `guardiano_assente` (con `stato`), `istanza_occupata`, `programma_chiuso` (con `causa`: `spegnimento_annullato` o `disconnessione`), `chiuso_durante_studio` (stesse condizioni di `chiuso_durante_blocco`), `stato_studio_perso` (come `stato_blocco_perso`: all'avvio il promemoria dello Studio sul computer manca o è rovinato ma lo Studio era aperto; il programma tiene lo Studio aperto con la partenza ricordata finché il server non risponde, e lo dice una volta), **`computer_sparito`** (con `durante`: `blocco`/`studio`), **scritto dal server** (non dal PC) quando il computer smette di battere in blocco/Studio senza una `sospensione` pulita.

### C. La Sessione Studio

Lo **Studio** è un periodo in cui, su tutti i dispositivi del figlio con Pactum dalla 0.18 (telefoni e computer), restano usabili solo le app e i programmi della sua lista approvata. A differenza delle sessioni della v3.5: è **del figlio** (non di un dispositivo), vale anche sui computer, **parte da sola**, non ha una durata, parte anche senza rete, e **non si chiude liberamente**. Le sessioni della v3.5 restano come sono.

#### La configurazione

Una per figlio.

```json
{ "stato": "approvata", "versione": 4,
  "approvata": {
    "giorni": ["lun", "mar", "mer", "gio", "ven"],
    "inizio": "15:00", "chiusura_minima": "16:00", "minuti_minimi": 60,
    "telefono": { "app": ["eu.spaggiari.classevivafamiglia", "gruppo:apk"],
                  "nomi": { "eu.spaggiari.classevivafamiglia": "ClasseViva" } },
    "computer": { "programmi": ["exe:winword.exe", "sito:classeviva.it"],
                  "nomi": { "exe:winword.exe": "Word" },
                  "firme": { "exe:winword.exe": "Microsoft Corporation" } },
    "orari_dal": "2026-10-08", "approvata_ts": "…", "decisa_da": { "id": 2, "nome": "Mamma" } },
  "in_attesa": null, "motivazione": null }
```

- **`stato`** ∈ `nessuna` (mai proposta) · `in_attesa` (prima proposta, da decidere) · `approvata` · `rifiutata` (prima proposta rifiutata, niente di approvato).
- **`approvata`**: il contenuto in vigore (`null` finché nessuno approva). **`in_attesa`**: il contenuto **completo** proposto (`null` se non c'è), più `richiesta_ts` e `da: { "id", "nome", "tipo" }` (il dispositivo che ha proposto per ultimo). **`versione`**: cresce a ogni proposta e a ogni decisione. **`motivazione`**: l'ultimo rifiuto (≤ 500 caratteri).
- **Finché non c'è niente di approvato, lo Studio non parte**, né da solo né a mano.
- **Regole dei campi:**
  - `giorni`: da 1 a 7 tra `lun mar mer gio ven sab dom`; i doppioni si tolgono. Almeno un giorno (lo Studio parte «senza eccezioni»: un giorno vuoto lo spegnerebbe, e qui non è permesso).
  - `inizio` e `chiusura_minima`: `"HH:MM"` nel fuso del patto, con `chiusura_minima >= inizio` nello stesso giorno (lo Studio non scavalca la mezzanotte con i suoi orari).
  - **Studio chiudibile in giornata:** `max(chiusura_minima, inizio + minuti_minimi) ≤ 23:30`, altrimenti `422 {"errore": "orari_impossibili"}`. Senza questo si potrebbe approvare uno Studio che non si può chiudere prima di mezzanotte (per esempio inizio 23:00 con 120 minuti): finirebbe per forza come `non_chiuso`, con un'accusa automatica su una cosa che il figlio non poteva fare.
  - `minuti_minimi`: un intero vero da 10 a 600.
  - `telefono.app`: da 0 a 200 chiavi, con le regole di `app` delle sessioni (pacchetti Android e `gruppo:apk`; `exe:`, `sito:`, `categoria:*`, `totale` → `422`).
  - `computer.programmi`: da 0 a 200 chiavi `exe:<nome>` o `sito:<dominio>`, con le regole delle chiavi del computer (v3). **Un `exe:` di un browser → `422 {"errore": "browser_nella_lista"}`**: nei browser si elencano i **siti**, mai il browser come programma (altrimenti aprirebbe tutti i siti). Sono browser almeno: `chrome.exe`, `msedge.exe`, `firefox.exe`, `brave.exe`, `opera.exe`, `opera_gx.exe`, `vivaldi.exe`, `arc.exe`, `chromium.exe`, `iexplore.exe`, `waterfox.exe`, `librewolf.exe`, `tor.exe` (elenco nel server, ampliabile): non solo i quattro di cui il programma sa leggere la barra. `categoria:*`, `totale` e pacchetti → `422`.
  - `nomi`: come nelle sessioni. Le etichette che si leggono sono quelle viste nell'uso (anche per `exe:`); per `sito:` è il dominio.
  - Una lista vuota vuol dire «solo le app sempre usabili».
- **Prima proposta:** i campi che mancano prendono i valori di partenza: `lun`–`ven`, `15:00`, `16:00`, `60`, liste vuote.
- **Quando vale una decisione:**
  - giorni, `inizio`, `chiusura_minima` e `minuti_minimi` approvati valgono **dal giorno dopo** l'approvazione (`orari_dal`, fuso del patto), **anche alla prima approvazione**: un dispositivo offline non può sapere di un'approvazione fatta alle 14:50;
  - le **liste** approvate valgono **dalla partenza successiva** (quando lo Studio seguente comincia; quello in corso tiene le sue liste congelate).

#### Proporre, ritirare, approvare

- **`PATCH /api/studio/config`** (dispositivo) `{ "giorni"?, "inizio"?, "chiusura_minima"?, "minuti_minimi"?, "telefono"?: { "app", "nomi"? }, "computer"?: { "programmi", "nomi"?, "firme"? } }`
  - Almeno un campo; nessun campo → `422`. `telefono` e `computer` si sostituiscono interi (le etichette delle chiavi tolte cadono).
  - I campi che mancano si prendono dalla **proposta in attesa, se c'è**, altrimenti da quella approvata: così il telefono propone i suoi orari e la sua lista, il computer la sua, e i due cambi non si cancellano a vicenda (diverso dalle sessioni, che sostituiscono tutto).
  - Senza niente di approvato: la proposta cambia, `stato` → `in_attesa`, `motivazione: null`. Con una configurazione approvata: quella resta, nasce o si aggiorna `in_attesa`.
  - Un `PATCH` il cui risultato è esattamente quella approvata **ritira** il cambio: `in_attesa: null`, nessun avviso nuovo, e l'avviso aperto si segna come letto.
  - Ogni `PATCH` aumenta la `versione`. Notifica ai genitori `studio_da_approvare`. `409 dispositivo_revocato` come sempre. Risposta `200` con la configurazione.
  - **`PATCH` e `DELETE` della proposta sono atomici** (`BEGIN IMMEDIATE`): l'unione con la proposta `in_attesa` (leggere e poi riscrivere) si fa **dentro** la transazione. Altrimenti telefono e computer che propongono nello stesso istante leggerebbero tutti e due `in_attesa` e l'ultima scrittura cancellerebbe la lista dell'altro.
- **`DELETE /api/studio/config/proposta`** (dispositivo): ritira la proposta in attesa; senza proposta → `409 {"errore": "niente_da_ritirare"}`. L'avviso aperto si chiude. `200` con la configurazione.
- **`POST /api/studio/config/risposta`** (genitore) `{ "esito": "approva" | "rifiuta", "versione": n, "motivazione"?, "figlio_id"? }`
  - `versione` è quella che il genitore ha sullo schermo, un intero vero (mancante → `422`). `figlio_id` facoltativo (senza, il figlio con l'`id` più basso; inesistente → `404`). Ogni scrittura ricontrolla la revoca (v3.6).
  - Controlli, in quest'ordine: figlio (`404`), niente in attesa (`409 niente_da_decidere`), `versione` diversa (`409 richiesta_cambiata` con la configurazione di adesso). È atomica.
  - **`approva`:** la proposta diventa quella approvata; si aggiunge una riga a `studio_versioni`. `orari_dal` passa al giorno dopo **solo se** giorni, orari o minimo sono cambiati (le liste no). `motivazione: null`.
  - **`rifiuta`:** la proposta sparisce con la sua `motivazione`; se non c'era niente di approvato, `stato: "rifiutata"`.
  - Notifica al figlio `studio_risposta`. Risposta `200` con la configurazione.
- **`GET /api/studio/versioni`** (dispositivo e genitore) → `{ "versioni": [ … ] }`: tutte le configurazioni approvate, dalla più recente, ciascuna con `versione`, il contenuto, `orari_dal`, `approvata_ts` e `decisa_da`.

#### Le partenze automatiche (le decide il server)

- **La partenza del giorno D** è l'istante in cui, nel fuso del patto, nel giorno D sono le ore `inizio`.
  - Vale solo se D è tra i `giorni` degli orari in vigore quel giorno (l'ultima approvazione con `orari_dal` ≤ D).
  - Porta con sé `chiudibile_dal` (D alle `chiusura_minima`) e `minuti_minimi` di quel giorno.
  - Se l'ora non esiste per il cambio dell'ora, vale la prima ora valida dopo; se esiste due volte, la prima.
- **Nessun processo in sottofondo** (come le sessioni). A **ogni** richiesta che riguarda il figlio, prima di rispondere, il server guarda le partenze passate (dopo la prima approvazione, non più vecchie di **48 ore**). Le richieste: `GET /api/patto`, `GET /api/faccende/blocco`, ogni `/api/studio/…`, l'avvio di una sessione, il `POST /api/battito` (anche del computer), e per il genitore `GET /api/finestra` e `GET /api/famiglia`.
- **Prima si legge, poi (solo se serve) si scrive.** Il server controlla **in sola lettura** se c'è una partenza passata non ancora elaborata (o una mezzanotte da chiudere). Solo in quel caso apre `BEGIN IMMEDIATE`, ricontrolla e scrive. Le letture normali **non** prendono il lucchetto di scrittura: altrimenti ogni `GET` (il patto ogni minuto dal computer, il blocco ogni 30-60 secondi, finestra e famiglia dei genitori) si metterebbe in fila dietro alle scritture lente come le foto, e il computer all'avvio, con una lettura in errore per database bloccato (`5xx`), applicherebbe la copia vecchia.
- **Ogni partenza si elabora una volta sola.** Una tabella `studio_partenze` (`figlio_id`, `giorno`, `studio_id`, chiave primaria `(figlio_id, giorno)`) segna ogni partenza trattata, sia che abbia creato uno Studio sia che sia entrata in uno aperto sia che sia stata **saltata**. Gli indici unici su `studio_svolte` (uno aperto per figlio, un automatico per `(figlio, giorno)`) non bastano: una partenza può essere assorbita da uno Studio a mano, e un automatico può diventare manuale (uscendo dall'indice parziale), quindi serve il segno esplicito. Per ogni partenza non ancora segnata:
  - se a quell'istante uno Studio del figlio era aperto, la partenza **entra in quello Studio** (diventa una sua `partenza`) e non nasce niente;
  - altrimenti nasce lo **Studio automatico di D**, con `inizio_ts` = l'istante della partenza (non l'ora della richiesta);
  - se **nasce già oltre la sua mezzanotte** (server stato giù a lungo), nasce e **si chiude nella stessa transazione** come `non_chiuso` (v. «La chiusura a mezzanotte»): niente `studio_iniziato`, solo `studio_non_chiuso`.
  - una partenza già segmentata in `studio_partenze` **non si rielabora** (salvo il caso della chiusura tardiva del figlio con `T` prima della partenza, v. «La chiusura del figlio»).
- **Mai due:** al massimo uno Studio automatico per figlio per giorno e al massimo uno Studio aperto per figlio, anche sotto richieste concorrenti.
- **Lo Studio parte solo** se il figlio ha almeno un **telefono** non revocato con l'app **dalla 0.18** (modello `conosce_le_faccende`, letto dall'ultimo `versione_app` del battito): altrimenti nessuno potrebbe chiuderlo.
  - Una partenza elaborata **senza** telefono 0.18 si **segna come saltata** in `studio_partenze` e **non si rielabora**: lo Studio di quel giorno non nasce più, nemmeno se il telefono si aggiorna dopo (così non nasce uno Studio alle 15:20 che chiude a posteriori una sessione delle 15:00).
  - A ogni partenza **saltata** per questo motivo il server manda ai genitori una notifica **attiva** `studio_non_partito` («Lo Studio di Luca non è partito: il telefono non è aggiornato alla 0.18»), **un solo avviso aperto** per figlio: tenere il telefono alla 0.17 diventa visibile e ripetuto, non solo una card passiva nella panoramica.
- **`prossime_partenze`** è l'elenco che il server manda ai dispositivi per partire da soli anche senza rete:

```json
"prossime_partenze": [ { "giorno": "2026-10-08", "inizio_ts": "2026-10-08T13:00:00+00:00",
                         "chiudibile_dal": "2026-10-08T14:00:00+00:00", "minuti_minimi": 60 } ]
```

  - le partenze da oggi (compresa quella di oggi, anche se già passata) ai prossimi **14 giorni**, già calcolate col fuso, col cambio dell'ora e con `orari_dal`;
  - **è `[]`** (e `studio.in_corso` è `null`) **quando il figlio non ha un telefono non revocato dalla 0.18**. In quel caso **né il telefono né il computer partono da soli**, nemmeno coi conti oltre i 14 giorni: senza un telefono 0.18 il server non creerà mai lo Studio, e un computer 0.18 che partisse da solo resterebbe coperto fino a mezzanotte senza che nessuno possa chiuderlo. I dispositivi partono **solo** con le partenze dell'ultima risposta ricevuta, e un elenco vuoto vuol dire «nessuna partenza».

#### Lo Studio svolto

```json
{ "id": 41, "origine": "automatica", "giorno": "2026-10-08", "chiave": null,
  "inizio_ts": "2026-10-08T13:00:00+00:00", "avviato_da": null,
  "partenze": [ { "giorno": "2026-10-08", "inizio_ts": "…", "chiudibile_dal": "…", "minuti_minimi": 60 } ],
  "conta_dal": "2026-10-08T13:00:00+00:00", "chiudibile_dal": "2026-10-08T14:00:00+00:00",
  "minuti_minimi": 60, "minuti_attivita": 42, "minuti_alla_chiusura": null, "chiudibile": false,
  "tratti": [ { "id": "…", "dispositivo_id": 1, "tipo": "compiti", "parola": null, "faccenda_id": null,
                "inizio": 1791291600000, "fine": 1791293400000, "ora_agganciata": true,
                "secondi": 1800, "secondi_contati": 1800, "minuti": 30, "esito": "finito", "conta": true } ],
  "sessione_chiusa": null,
  "fine_ts": null, "chiusura": null, "chiusa_da": null, "dichiarazione": null, "motivo": null,
  "in_corso": true }
```

- **`origine`** ∈ `automatica · manuale`. `giorno`: il giorno locale di `inizio_ts`. `chiave`: quella dell'avvio a mano (`null` per l'automatico). `avviato_da`: il dispositivo dell'avvio a mano.
- **`partenze`**: le partenze automatiche cadute dentro questo Studio, compresa quella che l'ha fatto nascere.
- **Le condizioni di chiusura** si calcolano dall'**ultima** partenza P dentro lo Studio (fino a adesso, o fino alla chiusura):
  - `conta_dal` = P, oppure `inizio_ts` se non ce n'è nessuna (avvio a mano senza partenze);
  - `chiudibile_dal` = quello di P, oppure `null` (a mano, nessuna partenza: niente vincolo d'orario);
  - `minuti_minimi` = quello di P, oppure quello approvato al momento dell'avvio a mano.
  - Così uno Studio a mano aperto alle 14:00 che assorbe la partenza delle 15:00 prende `conta_dal = 15:00` e `chiudibile_dal = 16:00`: **il tempo di prima delle 15:00 non conta per lui** e vale il vincolo delle 16:00.
- **`minuti_attivita`**: i minuti di attività che contano fino a adesso (v. «I tratti»: è la durata dell'**unione** dei tratti validi, tagliata a `[conta_dal, adesso o fine dello Studio]`, divisa per 60 per difetto). È «tempo col timer acceso dichiarato dal figlio», **non** attività verificata (v. «Cosa misura il timer»).
- **`minuti_alla_chiusura`**: `null` finché lo Studio è aperto; alla chiusura diventa il valore di `minuti_attivita` **congelato in quel momento**. È il numero delle condizioni, della notifica e dello storico: i tratti che arrivano **dopo** la chiusura restano nel registro con `conta: false` e non cambiano questo numero (così la notifica «65 min» e lo storico coincidono sempre).
- **`chiudibile`**: `true` se `ora del server ≥ chiudibile_dal` (o `chiudibile_dal` è `null`) **e** `minuti_attivita ≥ minuti_minimi`.
- **`chiusura`** ∈ `null` (in corso) · `figlio` · `genitore` · `non_chiuso` (chiuso da solo a mezzanotte). `chiusa_da`: il dispositivo `{ "id", "nome", "tipo" }` o il genitore `{ "id", "nome" }` (`null` per `non_chiuso`). `dichiarazione`: il testo del figlio. `dichiarazione_ts`: quando il figlio l'ha scritta (serve alla chiusura tardiva, v. sotto). `motivo`: quello del genitore. `sessione_chiusa`: `{ "id", "nome" }` della sessione normale chiusa all'inizio, o `null`.
- Uno Studio chiuso **non si riscrive mai**, con **una sola eccezione**: un `non_chiuso` di mezzanotte cede a una chiusura del figlio valida, consegnata dopo, avvenuta prima di quella mezzanotte (v. «La chiusura a mezzanotte»). Uno Studio che non c'è, o è di un altro figlio → `404 {"detail": "studio non trovato"}`.

#### I tratti di attività (il timer)

Il timer è nell'app del telefono, solo durante uno Studio. Cronometra **tratti di durata libera e mescolabili** (es. 10 min lavori di casa + 30 min compiti + 40 min allenamento): conta il **totale**, non un numero di pomodori fissi. Ne gira **uno alla volta**.

- Ogni tratto ha un **tipo** ∈ `compiti · lavori_di_casa · altro`:
  - `altro` = attività lontana dai dispositivi, con una **`parola`** obbligatoria (1–30 caratteri, regole dei nomi), es. «allenamento»; `parola` facoltativa per gli altri;
  - `faccenda_id` facoltativo, **solo** con `lavori_di_casa`; se non è un lavoro del figlio vale `null`.
- La **durata** (`secondi`) si misura con **l'orologio che non si sposta** (`elapsedRealtime`). Un **riavvio del telefono** chiude il tratto in corso **all'ultimo punto salvato** (il suo `secondi` arriva fino a lì); diventa `interrotto` ma **conta** lo stesso (`elapsedRealtime` si azzera al riavvio, quindi il tratto non può continuare oltre).
- **Forma di un tratto mandato dal telefono:** `{ "id": "<uuid>", "tipo", "parola"?, "faccenda_id"?, "inizio": ms?, "fine": ms, "ora_agganciata": bool, "secondi": n, "esito" }`.
  - `esito` ∈ `in_corso · finito · interrotto`. `secondi`: la durata misurata (da 1; `in_corso` può averlo parziale).
  - **`fine`** (e, per un tratto `in_corso`, **`inizio`**) è l'**ora del server agganciata** all'orologio che non si sposta nella stessa accensione (`MemoriaBlocco.oraServer`). Senza aggancio (riavvio senza rete) è l'orologio del telefono più lo scarto misurato, con **`ora_agganciata: false`**. Per un tratto finito `fine` non è `null`; per uno `in_corso` `fine` è `null` e l'inizio è `inizio` (così il server lo sa anche senza `fine`, che con `fine - secondi` non potrebbe calcolare).
- **Quando lo manda il telefono:** quando comincia (`in_corso`, se ha rete), quando finisce (`finito` o `interrotto`); senza rete, solo alla fine.
- **`POST /api/studio/tratti`** `{ "tratti": [ … ] }`, da 1 a 50 per volta. **Solo dai telefoni**; da un computer → `422 {"errore": "solo_dal_telefono"}`. `id` è la chiave di idempotenza (`INSERT OR IGNORE`, come gli eventi).
  - Un `id` nuovo si scrive; un `in_corso` passa **una volta sola** a un esito finale; lo stesso esito ripetuto non è un errore; un esito diverso su un tratto già finito si ignora.
  - Risposta `200`: `{ "ricevuti", "nuovi", "aggiornati", "ignorati", "tratti": [ … ] }`.
- **Le ore** seguono le tutele (non nel futuro, non più di 48 ore prima dell'arrivo). Una `fine` entro 2 minuti dall'arrivo vale l'arrivo. Un tratto con ore fuori dalle tutele resta nel registro con `conta: false`. Il server **non calcola la sovrapposizione** fra tratti con `ora_agganciata` diversa sulla stessa accensione (dopo un riavvio senza rete l'orologio del telefono può essere indietro, e due tratti sembrerebbero sovrapposti): così il figlio non perde il suo tempo per colpa dell'orologio.
- **A quale Studio appartiene un tratto:** quello con cui si **sovrappone di più** (non solo «dove finisce»). Fuori da ogni Studio resta nel registro senza Studio e non conta.
- **`secondi_contati`** di un tratto = la parte dei suoi secondi che cade dentro `[conta_dal, fine dello Studio o adesso]`. Un tratto cominciato **prima** di `conta_dal` conta solo per la parte dopo (così il tempo di prima delle 15:00 di uno Studio a mano che assorbe la partenza **non conta**, come ha deciso Andrea); un tratto che sfora la chiusura o la mezzanotte conta solo fino a lì. Il telefono chiude un tratto in corso **alla mezzanotte del patto** (con l'ora agganciata) come `interrotto`; il server comunque **taglia** ogni tratto alla fine dello Studio.
- **`conta: true`** si decide **una volta sola**, quando il tratto arriva al suo **esito finale** (`finito`/`interrotto`), e non cambia più. Vale se:
  1. ha un esito finale (una `fine` e dei `secondi`);
  2. `secondi_contati > 0` (cioè almeno un secondo cade in `[conta_dal, fine dello Studio]`);
  3. le sue ore sono dentro le tutele;
  4. per la parte comune, non si **sovrappone** a un altro tratto del figlio già contato (la parte comune si conta una volta sola — v. `minuti_attivita`). Con due telefoni l'ordine è quello d'**arrivo degli esiti finali** (`ts_server` dell'esito).
  Un tratto che arriva al suo esito finale quando lo Studio a cui appartiene **non è ancora nato sul server** (per esempio un avvio senza rete consegnato dopo) resta con `conta` **«da decidere»** (nel database `NULL`, in uscita `false`): si decide una volta sola quando lo Studio nasce e il tratto viene agganciato a lui.
- **`minuti_attivita`** dello Studio = durata dell'**unione** degli intervalli `[fine − secondi, fine]` dei tratti validi del figlio, tagliata a `[conta_dal, fine dello Studio o adesso]`, divisa per 60 per difetto. Due tratti sovrapposti contano **una volta sola** per la parte comune (così «il totale è l'unione» della parte E, e non si scarta un intero tratto perché sfora di due minuti). Non c'è soglia per il singolo tratto.
- **Cosa misura il timer (scritto chiaro, per il figlio e per i genitori):** i «minuti di attività» sono **minuti col timer acceso dichiarati dal figlio**, **non** attività verificata. Un tratto `altro` o `lavori_di_casa` matura anche a schermo spento, col telefono nel cassetto; la **dichiarazione** di fine è una responsabilità del figlio. Pactum non vede i dispositivi dove non è installato, quindi non può sapere se il figlio ha davvero fatto quello che dichiara: è un **limite noto**, non un difetto da coprire. Anche i tratti `compiti` maturano a schermo spento: i compiti si fanno anche su carta (scelta del 07/10).

#### `GET /api/studio`

- Vale per il dispositivo e per il genitore (`?figlio_id=`; senza, il primo figlio). → `{ "config", "in_corso", "prossime_partenze", "recenti" }`.
  - `in_corso`: lo Studio aperto (con `tratti` e liste) oppure `null`.
  - `recenti`: gli Studi che toccano le ultime 48 ore, dal più recente (al telefono servono a riconoscere gli Studi fatti senza rete).
- **`GET /api/studio/svolte?figlio_id=…&prima_di=<id>`** → `{ "svolte": [ … ], "altre": bool }`, dal più recente, 20 per volta. Identico per figlio e genitore.

#### Avvio a mano: `POST /api/studio/avvia`

`{ "chiave": "<uuid>", "ts_device": ms? }` (dispositivo). Risposta `201` con lo Studio (stessa `chiave` già vista → `200` con lo stesso Studio).

- Valgono le stesse regole della configurazione approvata, **ma senza `chiudibile_dal`** (niente vincolo delle 16:00), a meno che lo Studio non assorba poi una partenza automatica.
- **L'inizio** (`I`) è `ts_device` con le tutele della chiusura delle sessioni (conta solo se cade più di 2 minuti prima dell'arrivo e non più di 48 ore prima; mai nel futuro; altrimenti vale l'arrivo). Un `ts_device` **oltre le 48 ore** → `409 {"errore": "avvio_scaduto"}`, e **non nasce niente** (senza questo, le tutele metterebbero l'inizio all'arrivo e il figlio entrerebbe in Studio adesso per un avvio di giorni prima).
- Niente di approvato → `409 {"errore": "studio_non_approvato"}`.
- **Le liste** con cui nasce lo Studio a mano sono quelle dell'**ultima versione approvata prima di `I`** (non quelle all'arrivo).
- **Troppo tardi:** se da `I` a mezzanotte mancano meno di `minuti_minimi` → `409 {"errore": "troppo_tardi"}` (non si apre uno Studio che non si potrà chiudere prima di mezzanotte).
- **Blocco dei lavori attivo** → `409 {"errore": "blocco_faccende"}`: lo Studio a mano non serve a rinviare un blocco già partito. Vale sia per un avvio con la rete, sia per uno consegnato in ritardo il cui `I` **cade quando il blocco era già attivo** (ricostruibile dai dati del server): riceve `409` lo stesso, non viene accettato per fiducia. **Lo Studio automatico resta l'unica partenza ammessa col blocco attivo.** (Lato telefono: v. «Il telefono» — l'avvio a mano **offline** è rifiutato dal telefono stesso se la sua copia del blocco è attiva o lo diventerà all'inizio dichiarato.)
- **Se `I` cade dentro uno Studio del figlio, aperto o chiuso** (`inizio_ts ≤ I < fine_ts`): non nasce niente → `200` con quello Studio, e il dispositivo lo **adotta** (se è chiuso, chiude il suo).
- **Se uno Studio aperto è cominciato dopo `I`, nello stesso giorno locale:** il server sposta indietro l'inizio di quello aperto a `I`, lo segna `manuale` con questa `chiave`, e la partenza automatica diventa una sua `partenza`.
- **Se `I` è di un giorno locale precedente:** lo Studio a mano nasce **già chiuso** alla sua mezzanotte (`non_chiuso`), e lo Studio aperto di oggi **non si tocca** (non si sposta il `giorno` di uno Studio che a quella mezzanotte avrebbe già dovuto chiudersi).
- `chiave`: da 1 a 64 caratteri. Niente notifica (come le sessioni). `409 dispositivo_revocato` come sempre.

#### La chiusura del figlio: `POST /api/studio/{id}/chiudi` (e senza id)

`{ "chiave": "<uuid>", "ts_device": ms?, "dichiarazione": "…", "tratti"?: [ … ] }`, **solo dal telefono**, anche senza rete (consegnata dopo). **Dal computer lo Studio non si chiude** (v. «Il computer»): `POST /api/studio/{id}/chiudi` col token di un computer → `422 {"errore": "solo_dal_telefono"}`.

- **Chiusura senza id.** Uno Studio automatico partito sul telefono senza rete non ha ancora un `id` del server (lo crea il server più tardi). Per questo c'è anche **`POST /api/studio/chiudi`** (senza id nel percorso), con nel corpo, al posto dell'`id`, `studio: { "giorno": "YYYY-MM-DD" }` (lo Studio che contiene la partenza di quel giorno) **oppure** `studio: { "chiave" }` (uno Studio avviato a mano). Il server trova (o crea, se la partenza è dentro le 48 ore) lo Studio e fa gli **stessi controlli** di `POST /api/studio/{id}/chiudi`, che resta per chi conosce l'`id`. Nella coda del telefono, l'`avvia` di uno Studio parte **sempre prima** della sua `chiudi`.
- **I tratti nel corpo** si registrano **prima** dei controlli, nella stessa transazione, anche se la chiusura poi è rifiutata. Chiudere dal telefono ferma prima il tratto in corso (esito `finito`, `fine = T`) e lo manda qui.
- **L'ora della chiusura** T è `ts_device` con le tutele della v3.5 (più di 2 minuti prima dell'arrivo, non prima dell'inizio, non nel futuro, non oltre 48 ore prima; altrimenti l'arrivo).
- Controlli, in quest'ordine:
  1. corpo (`422`): `chiave`/`studio` mancante, oppure `dichiarazione` che dopo aver tolto gli spazi ai bordi non è tra **10 e 1000** caratteri (regole della nota di un lavoro);
  2. `404` (studio non trovato); stessa `chiave` già applicata → `200` con lo Studio;
  3. già chiuso → `409 {"errore": "gia_chiuso", "studio": {…}}`;
  4. T prima di `chiudibile_dal` → `409 {"errore": "troppo_presto", "chiudibile_dal": "…", "studio": {…}}`;
  5. minuti di attività (fino a T) sotto `minuti_minimi` → `409 {"errore": "attivita_insufficiente", "minuti": n, "minimi": n, "studio": {…}}`.
- Le condizioni si calcolano **a T**, con le partenze dentro lo Studio fino a T.
- **Una chiusura fatta senza rete prima di una partenza assorbita.** Nella stessa transazione, ogni partenza `P` dello Studio con `P > T` **si toglie** dalle sue `partenze` e si **rielabora** come se a `P` non ci fosse uno Studio aperto: nasce lo Studio automatico di quel giorno con `inizio_ts = P` (con `studio_iniziato` e la chiusura delle sessioni in corso a `P`), e i tratti che finiscono dopo `T` passano al nuovo Studio. Senza questa regola la partenza resterebbe attaccata a uno Studio chiuso e i tratti di quelle ore non conterebbero, mentre i dispositivi sono in Studio.
- È atomica. `chiusura: "figlio"`, `fine_ts` = T, `dichiarazione_ts` = T. `minuti_alla_chiusura` congelato. Notifica ai genitori `studio_chiuso`. Risposta `200` con lo Studio chiuso.

#### La chiusura del genitore: `POST /api/studio/{id}/chiudi`

`{ "figlio_id"?, "motivo" }` col token del genitore: il genitore chiude lo Studio **senza condizioni** (visita medica, malattia, telefono rotto…). Il figlio **non** può chiudere il suo Studio in questo modo.

- **`motivo` obbligatorio**, da 3 a 300 caratteri dopo aver tolto gli spazi ai bordi (regole di una nota): senza → `422`. Resta nel registro (Andrea: «con un motivo»). Nell'app del genitore «Chiudi lo Studio» chiede il motivo e il pulsante si attiva solo quando c'è.
- `chiusura: "genitore"`, `fine_ts` = adesso. **Un tratto in corso non si tocca:** il suo esito finale vale quando arriva (tagliato al `fine_ts` dello Studio). Così, se il telefono manda poi il tratto `finito` con i suoi secondi veri, quel tempo resta nel registro invece di essere buttato come `interrotto` senza secondi.
- Già chiuso → `409 {"errore": "gia_chiuso", "studio": {…}}`. Atomica con la chiusura del figlio (chi arriva secondo riceve `gia_chiuso`).
- **Una chiusura del figlio con `T` prima del `fine_ts` del genitore, consegnata dopo:** non cambia la chiusura (resta `genitore`), ma la sua **dichiarazione si salva** sullo Studio (`dichiarazione`, con `dichiarazione_ts = T`) e i genitori la ricevono. La risposta è `200` con lo Studio, non `gia_chiuso`.
- Notifica `studio_chiuso` al figlio (`dispositivo_id: null`) e agli altri genitori (già letta per chi ha chiuso).

#### La chiusura a mezzanotte («non chiuso»)

- Lo Studio **non ha fine automatica**, tranne a **mezzanotte**: uno Studio aperto alle 00:00 (fuso del patto) del giorno dopo il suo `giorno` si chiude da solo come **`non_chiuso`**, `fine_ts` = quella mezzanotte.
- Lo calcola il server **quando legge** (come lo scadere delle sessioni), in `BEGIN IMMEDIATE`, senza processi in sottofondo. Nella **stessa richiesta** il server applica **prima** le chiusure consegnate (del figlio) e **poi** le chiusure di mezzanotte. I tratti entro la mezzanotte restano nel registro; la `dichiarazione` resta `null` (il figlio non l'ha chiuso), `minuti_alla_chiusura` è il totale a mezzanotte.
- **`non_chiuso` è l'unica chiusura che cede.** Se entro 48 ore da `T` arriva una **chiusura del figlio valida a `T`** (con `T` prima della mezzanotte dello Studio e le condizioni rispettate a `T`), lo Studio passa, **una volta sola**, da `non_chiuso` a `figlio`, con `fine_ts = T`, la `dichiarazione` e `dichiarazione_ts = T`. È l'unico passaggio che il trigger di «uno Studio chiuso non si riscrive» permette. L'avviso `studio_non_chiuso` si segna come **letto per tutti**, e parte `studio_chiuso` con in coda «(chiusa senza rete alle 16:40, arrivata dopo)». Senza questo, una chiusura regolare fatta senza rete prima di mezzanotte e consegnata il mattino dopo riceverebbe `409 gia_chiuso`, con un «non chiuso» falso e la dichiarazione persa.
- **Niente doppio avviso.** Uno Studio creato in ritardo e già oltre la sua mezzanotte nasce e si chiude nella stessa transazione: parte **solo** `studio_non_chiuso`, **non** `studio_iniziato` (non ha senso «Luca è in Studio dalle 15:00» per uno Studio di ieri già finito). Uno Studio **a mano senza rete** che a mezzanotte era già tale: la chiusura di mezzanotte **non manda** `studio_non_chiuso` se dall'inizio a mezzanotte mancava meno del minimo (non era chiudibile: non è una colpa).
- Notifica ai genitori `studio_non_chiuso` con i minuti di attività fatti. Il figlio, al mattino dopo, non resta in Studio: lo Studio del giorno prima è chiuso e quello nuovo parte alle 15:00.
- **Un genitore può chiudere lo Studio** (`chiusura: "genitore"`); anche una sua chiusura, consegnata prima, prevale sul `non_chiuso` di mezzanotte (vince chi ha chiuso prima, non chi arriva prima).

#### Studio e lavori di casa (il calcolo di `rimandato`)

- Durante lo Studio **il blocco dei lavori aspetta**: `blocco.attivo` resta vero se è dovuto, `blocco.rimandato` è vero, e telefono e computer applicano lo **Studio**, non il blocco.
- Ogni dispositivo decide da sé: copre per il blocco quando `attivo` (o all'ora di `prossimo`) **e**, secondo la sua memoria, **non** è in Studio. `rimandato` del server è l'informazione per i genitori e per i testi.
- Le **foto** dei lavori si scattano anche durante lo Studio (la fotocamera aperta da Pactum resta usabile).
- Un lavoro dato durante lo Studio arriva come sempre (`nuove_faccende`); anche il suo blocco aspetta.
- **Alla fine dello Studio** (chiuso dal figlio, dal genitore o a mezzanotte), se restano lavori aperti che bloccano, **il blocco parte allora**, con l'avviso «Prima i lavori di casa» del telefono.
- Questo **ribalta la v3.6 per lo Studio** (lì «se il blocco parte durante una sessione vince la barriera del blocco»): per le sessioni normali resta così, per lo Studio no.
- **Conseguenza (scelta di Andrea, rischio detto una volta).** Finché lo Studio è aperto il blocco dei lavori **non si applica**, fino alla chiusura o a mezzanotte. In un giorno con lista Studio ampia, uno Studio lasciato aperto trasforma tutto il pomeriggio e la sera in «modalità Studio», più larga della barriera del blocco: è il modo per schivare il blocco dei lavori per ore. Perciò **la lista Studio approvata va tenuta stretta**, e questo va detto ai genitori. Andrea l'ha confermato sapendo il rischio; la chiusura a mezzanotte lo limita alla giornata.

#### Studio e sessioni

- **Si chiudono solo le sessioni in corso all'istante `inizio_ts` dello Studio** (iniziate prima e non ancora finite a quell'istante), sui telefoni del figlio dalla 0.18: `chiusura: "terminata"` (valore che esiste già, nessun cambio al CHECK di `sessioni_svolte`), `fine_ts` = `inizio_ts`. Le sessioni iniziate **dopo la fine** dello Studio non si toccano. (Senza questo, uno Studio creato a posteriori chiuderebbe una sessione nata ore dopo, con durata zero.) Lo Studio lo registra in `sessione_chiusa`.
- Le sessioni dei telefoni **0.17 non vengono chiuse** dallo Studio (le chiude solo chi conosce lo Studio).
- **Durante lo Studio** `POST /api/sessioni/{id}/avvia` → `409 {"errore": "studio_in_corso"}`, solo per i telefoni dalla 0.18, controllato **prima** di `blocco_faccende`.

#### Cosa conta durante lo Studio (lo calcolano telefono e computer)

- Come per le sessioni (v3.5): il tempo nelle app, nei programmi e nei siti **della lista dello Studio** **non conta** per limiti, categorie, totale e fasce orarie. Fuori lista conta sempre.
- `sessioni_minuti` della fotografia `uso_giornaliero` comprende anche i minuti dello Studio; dalla 0.18 lo manda anche il computer.

#### Lo Studio e i siti (emendamento al patto etico della v2.3)

La v2.3 (`:286`) dice che non esiste e non esisterà nessun endpoint per bloccare, filtrare o limitare un sito. Dalla v4.0 c'è **un'eccezione sola, scritta qui**: durante lo Studio il programma del computer copre i siti che non sono nella lista. I suoi confini:

- la lista è un elenco di siti **permessi**; la propone il figlio e la approva un genitore. Nessun genitore può scrivere da solo un sito da bloccare;
- vale solo durante lo Studio e solo sul computer. Fuori dallo Studio niente cambia;
- non si legge niente di nuovo: lo stesso dominio registrabile della v3, mai l'indirizzo completo né il contenuto;
- non nasce nessun registro dei siti coperti: il genitore non vede quali siti sono stati coperti né quante volte, come per la barriera delle sessioni;
- sul **telefono** lo Studio non guarda i siti: un browser nella lista delle app apre tutti i siti.

#### Il telefono (0.18)

- **Partenza.** All'`inizio_ts` di ogni `prossime_partenze` lo Studio parte da solo, anche senza rete, a schermo spento e dopo un riavvio, con una sveglia esatta. L'ora è quella del server agganciata all'orologio che non si sposta (come il blocco); dopo un riavvio senza rete vale l'orologio del telefono. 5 minuti prima: notifica «Tra 5 minuti parte lo Studio».
- **Oltre i 14 giorni senza rete** il telefono calcola le partenze dalla configurazione, nel fuso del patto.
- **In corso** quando lo dice il server (`in_corso`), quando è passata una partenza dopo l'ultima risposta del server senza una chiusura nota dopo di lei, o con uno Studio a mano. **Finisce** quando il server lo dà chiuso, con una chiusura fatta sul telefono, a **mezzanotte** (si chiude da solo come `non_chiuso`), o con un `401`. Una risposta di `GET /api/patto` senza il campo `studio` (server più vecchio della v4.0) spegne lo Studio.
- **Rilettura dello stato.** Il telefono rilegge `GET /api/studio` almeno **ogni minuto** durante lo Studio, e **subito** alle notifiche `studio_chiuso` (chiusura del genitore) e `studio_risposta` (un insieme **`CAMBIANO_LO_STUDIO`**, come `faccenda_confermata` per il blocco). Senza questo, uno Studio chiuso dal genitore alle 15:20 per la visita medica resterebbe in Studio sul telefono fino al giro dopo.
- **Alla partenza, anche senza rete, il telefono chiude in locale la sua sessione normale in corso** (`terminata`, `fine` = inizio dello Studio) e ne consegna la chiusura come oggi: altrimenti senza rete terrebbe due barriere insieme (sessione e Studio).
- **Avvio a mano offline col blocco in arrivo:** il telefono **rifiuta** un avvio a mano fatto senza rete se la sua copia del blocco è attiva, o lo diventerà all'inizio dichiarato (lo stesso `409 blocco_faccende` del server): lo Studio automatico resta l'unica partenza ammessa col blocco attivo.
- **La barriera.** Ogni app fuori dalla lista si copre entro pochi secondi: «Sei in Studio», la lista, **Esci** (porta alla Home). Sempre usabili: quelle della barriera delle **sessioni** (Pactum, Home, tastiera, interfaccia di sistema, Telefono, chiamate ed emergenze, Impostazioni) più la **fotocamera** quando la apre Pactum per la foto di un lavoro. Lo Studio non si annuncia: parte e basta; la pagina d'inizio la apre il servizio. Senza «Mostra sopra le altre app» o l'accesso all'uso, lo Studio vale lo stesso e il telefono manda le manomissioni di sempre.
- **Notifica fissa:** «Studio dalle 15:00 · 42 min su 60 · si chiude dopo le 16:00».
- **Timer.** «Comincia un'attività» chiede il tipo (`compiti` / `lavori di casa` / `altro`) e, per `altro`, la parola. Il tempo si conta con l'orologio che non si sposta; un riavvio del telefono chiude il tratto in corso (interrotto, conta fino all'ultimo punto salvato) e, a mezzanotte, il telefono chiude il tratto in corso lì.
- **Chiudere.** «Chiudi lo Studio» compare solo quando le condizioni ci sono. **Con la rete** segue `chiudibile` e `minuti_attivita` del server; **senza rete** usa l'ultimo valore del server più i tratti locali arrivati dopo. Chiede «Cosa hai fatto?» (10–1000 caratteri) e mostra il riepilogo dei tratti. Chiude subito sul telefono e manda la chiusura; se il server la rifiuta, lo Studio torna col motivo («Per il server sono le 15:20» / «Mancano 15 minuti») e il testo resta come bozza. Con **due telefoni**, «Chiudi lo Studio» non si decide dai soli tratti locali: segue i `minuti_attivita` del server (l'altro telefono può averne mandati già abbastanza).
- **Chiusura senza rete:** vale solo se l'ora del server è agganciata nella **stessa accensione**; dopo un riavvio senza rete, per chiudere serve la rete.
- **Manomissioni durante lo Studio:** Pactum fermato a mano → `fermato_durante_studio` `{ "dal", "al", "minuti" }` (regole di `fermato_durante_blocco`); permessi tolti → `permesso_revocato` con in più `"durante": "studio"`.

#### Il computer (0.18)

- **Partenza.** Parte da solo alle stesse partenze, che legge dal patto salvato, anche senza rete; l'ora è quella del server agganciata. 5 minuti prima un fumetto avvisa.
- **In corso / finisce:** con le regole del telefono, **ma la chiusura la sa solo dal server** (`studio.in_corso` di `GET /api/faccende/blocco`, chiesto ogni 30 secondi in Studio e almeno ogni minuto fuori). Senza rete lo Studio continua. Vale la regola dei 5 secondi all'avvio.
- **La copertura (dello schermo, non per finestra).** Se su uno schermo è visibile (non ridotta a icona) almeno una finestra di un programma fuori lista, o un browser su un sito fuori lista o mai letto, **quello schermo si copre per intero tranne la barra delle applicazioni**: la copertura prende l'area di lavoro, sempre in primo piano, e dice «Sei in Studio» con la lista. Gli schermi senza finestre fuori lista restano liberi. La copertura sparisce appena quella finestra è ridotta a icona o chiusa, cosa che il figlio fa dalla barra rimasta libera. Il programma non chiude e non tocca le altre app. (Correzione: la bozza copriva «ogni finestra con un riquadro sopra di lei» e «il riquadro prende il primo piano» — la copertura per finestra del Progetto 1, che i due giudici hanno scartato perché fragile, DPI, finestre spostate, riquadri da riallineare; e diversa da quello che la decisione dice alla famiglia.)
- **Sempre usabili:** Pactum; il desktop, la barra, Start ed Esplora file (`explorer.exe`); le Impostazioni; le finestre di sistema (permessi, blocco schermo). **Gestione attività è coperta.** La **ricerca di Start / barra** e i suoi risultati web **aprono il browser**, quindi ricadono nelle regole dei siti dello Studio (coperti se fuori lista). Fermare Pactum dalle Impostazioni resta possibile (limite già noto), ma genera `fermato_durante_studio` / `permesso_revocato` con `durante: studio`: queste vie lasciano traccia.
- **I browser:** un `exe:` di un browser è rifiutato alla configurazione (`422`, elenco ampio in parte C), quindi non apre tutti i siti. Chrome, Edge, Firefox e Brave sono usabili **solo** sui siti `sito:` della lista; il dominio si legge come nella v3 dalla barra della finestra in primo piano. Una scheda nuova o una pagina interna del browser è usabile finché da lì non parte una navigazione. **Un browser che il programma non sa leggere** (Opera, Vivaldi, Tor…) durante lo Studio è **sempre coperto**, come un sito mai letto.
  - **Durante lo Studio, una barra che non si legge copre.** Fuori dallo Studio resta la regola della v3 (vale l'ultimo sito letto). Durante lo Studio, invece, una finestra di browser il cui sito **non si legge adesso** — schermo intero/F11, cursore nella barra mentre si scrive, lettura fallita — **si copre** finché la lettura non torna a mostrare un sito della lista. Così non si apre un sito in lista per poi premere F11 (o tenere il cursore nella barra) e navigare altrove. Una scheda nuova o una pagina interna del browser resta usabile finché da lì non parte una navigazione. (Scelta tecnica del 07/10: il fallback «ultimo letto» era una proposta dei progettisti, non una decisione di Andrea.)
  - **Programmi firmati: si controlla anche la firma.** La lista `exe:` riconosce il programma dal **nome del file**, e il figlio, con pieni diritti sui suoi file, potrebbe rinominare `gioco.exe` in `winword.exe`. Perciò la configurazione del computer ha in più **`firme`** (facoltativo): `{ "exe:winword.exe": "Microsoft Corporation" }`, il soggetto (CN) del certificato con cui è firmato l'eseguibile, proposto dal **computer** quando il figlio sceglie un programma dai programmi visti (il programma legge la firma Authenticode del file che ha visto girare). Durante lo Studio, per una voce con firma, conta solo un processo il cui file ha una **firma valida con quel soggetto**; altrimenti si copre. Le voci senza firma (programmi non firmati) si riconoscono dal solo nome: **limite noto**, e conviene preferire programmi firmati. Regole del server: le chiavi di `firme` devono essere voci `exe:` della stessa lista (le altre si lasciano cadere), valori da 1 a 200 caratteri. L'app del genitore mostra «firmato da Microsoft Corporation» accanto al programma.
- **Limiti dello Studio (scritti, non corretti):** l'audio di una finestra coperta **continua** e le notifiche di Windows restano (la copertura prende lo schermo, non l'audio né le notifiche). È un limite accettato anche per lo Studio, non solo per il blocco.
- **La finestra di Pactum:** mostra lo Studio (tratti in sola lettura) e lo **stato** («dalle 15:00 · 42 min su 60 · si chiude dopo le 16:00») con scritto **«Si chiude dal telefono»**; propone la lista del computer dai programmi e dai siti visti negli ultimi 30 giorni; «Chiudi Pactum» sparisce durante lo Studio. **Dal computer lo Studio non si chiude** (decisione di Andrea): niente pulsante «Chiudi lo Studio».
- **Lavori:** il blocco aspetta e parte a fine Studio. Chiuso durante lo Studio → `chiuso_durante_studio` al riavvio.
- **Un secondo account Windows, o «Cambia utente» (limite noto).** Studio e blocco valgono solo sull'account del figlio dove gira il programma (il guardiano è un'attività di **quell'utente**). Un altro account standard sullo stesso PC non ha guardiano né copertura: là il figlio navigherebbe libero, e il server vedrebbe solo il computer del figlio andare «spento» (→ la rete di sicurezza lato server, parte B, lo coglie come «computer sparito» durante blocco/Studio). Va detto ad Andrea e al padre che, senza un account amministratore che impedisca di creare o usare altri account, un secondo account aggira sia il blocco sia lo Studio.

#### L'app del genitore (0.18)

- **Configurazione da approvare:** una card con orari, giorni, minimo di minuti, le due liste (con cosa è stato aggiunto e cosa tolto rispetto all'approvata), «i nuovi orari valgono da domani»; Approva e Rifiuta con la `versione` vista. La configurazione non va **mai** dentro `sessioni[]`.
- **Panoramica (lo Studio di oggi):** «Studio dalle 15:00 · 42 min su 60 · si chiude dopo le 16:00»; poi «chiuso alle 17:40» con la dichiarazione; «Il blocco dei lavori parte a fine Studio» quando `rimandato`. «Chiudi lo Studio» con una domanda e un **motivo obbligatorio** (il pulsante si attiva solo quando c'è).
- I «minuti di attività» si mostrano come **minuti col timer acceso dichiarati dal figlio**, non come attività verificata: il testo lo dice («42 min dichiarati col timer»), così il genitore sa cosa legge (v. «Cosa misura il timer»).
- **Storico, per ogni Studio:** l'inizio (da solo, o a mano da quale dispositivo); i tratti con tipo, parola, lavoro e minuti (compresi gli interrotti); la chiusura (ora, chi, dichiarazione o motivo, oppure «non chiuso»); più le versioni approvate con chi le ha approvate.
- **Versioni dei dispositivi:** se un dispositivo del figlio è più vecchio della 0.18, l'app dice che lì lo Studio non c'è e il blocco dei lavori non aspetta.

#### Notifiche

- **`studio_da_approvare`** ai genitori (`dispositivo_id: null`): «<figlio> chiede di approvare lo Studio» / «…di cambiare lo Studio». `payload: { "versione", "cambio": bool, "da": { dispositivo } }`. **Un solo avviso aperto**: uno nuovo, una decisione o un ritiro chiudono il precedente (come le sessioni).
- **`studio_risposta`** al figlio (`dispositivo_id: null`): «<genitore> ha approvato lo Studio» / «…non ha approvato…», con in coda **«(i nuovi orari valgono da domani)» solo se** sono cambiati giorni, orari o minimo; **«(la nuova lista vale dal prossimo Studio)» se** sono cambiate le liste. `payload: { "esito", "versione", "cambio", "orari_dal", "genitore" }`.
- **`studio_non_partito`** ai genitori (`dispositivo_id: null`), a ogni partenza saltata perché manca un telefono 0.18: «Lo Studio di <figlio> non è partito: il telefono non è aggiornato alla 0.18». **Un solo avviso aperto** per figlio.
- **`studio_iniziato`** ai genitori (`dispositivo_id: null`), all'inizio di uno Studio: «Luca è in Studio dalle 15:00». `payload: { "studio_id", "origine", "inizio_ts" }`. **Non parte** per uno Studio che nasce **già chiuso** (creato in ritardo oltre la sua mezzanotte): in quel caso c'è solo `studio_non_chiuso`.
- **`studio_chiuso`**: alla chiusura del **figlio**, ai genitori: «<figlio> ha chiuso lo Studio alle 16:40 (dalle 15:00): 65 min — compiti (matematica), lavori di casa, allenamento. «<dichiarazione, ≤ 200 caratteri>»» (se chiusa senza rete e arrivata dopo, in coda «(chiusa senza rete alle 16:40)»); alla chiusura del **genitore**, al figlio e agli altri genitori: «<genitore> ha chiuso lo Studio» + «: <motivo>». `payload: { "studio_id", "fine_ts", "chiusura", "tratti": [ { "tipo", "parola", "minuti" } ], "minuti_attivita", "dichiarazione", "motivo" }`.
- **`studio_non_chiuso`** ai genitori, alla chiusura di mezzanotte: «Lo Studio di Luca non è stato chiuso: 40 min di attività». `payload: { "studio_id", "fine_ts", "minuti_attivita" }`.
- **Testi del blocco coerenti con lo Studio:** `faccenda_confermata` e `faccende_finite` dicono «telefono e computer sbloccati» **solo se** il blocco era attivo **e non rimandato**; se era rimandato dallo Studio, niente sblocco annunciato (v. parte A).

#### Dove si vede

- **`GET /api/patto`** (dispositivo), in più `studio`: `{ "config", "in_corso", "prossime_partenze" }`. Lo storico non c'è (sta in `GET /api/studio/svolte`): il patto lo legge ogni minuto anche il computer, e deve restare leggero (`?tempi=1` resta solo per i tempi della v3.8).
- **`GET /api/faccende/blocco`**: `studio` e `rimandato` (v. parte A).
- **`GET /api/finestra`**, in più: `studio` (come nel patto), `studio_svolte` (quelle che toccano gli 8 giorni, dal più recente, ≤ 50), `studio_da_approvare` (0 o 1).
- **`GET /api/famiglia`**, per ogni figlio in più: `studio_da_approvare`, `studio_in_corso`, `faccende_da_approvare`, `blocco_rimandato`.

### D. Fasce orarie: meno di un minuto non è uno sforamento (precisazione)

Era già così sul telefono e sul computer; ora è scritto. Nessun cambio di codice.

- Una `fascia_oraria` è sforata quando il tempo d'uso dentro un'occorrenza arriva a **un minuto intero** (60 secondi). Si contano le stesse app del totale.
- **Sotto i 60 secondi:** niente `sforamento`, niente avviso, e «Oggi» dice «rispettata».
- **Limite noto, lasciato così:** in una fascia che scavalca la mezzanotte i minuti si sommano **dopo** l'arrotondamento per difetto di ogni pezzo, ciascuno nel suo giorno. Per esempio 59 secondi prima della mezzanotte e 59 dopo non fanno un minuto: restano zero. È il comportamento di oggi (`SentinellaPatto.kt:387-389`, `Giornata.cs:86-97`), accettato.
- Vale per telefono e computer.

### E. Casi limite

- **Mezzanotte.** Uno Studio aperto si chiude da solo come `non_chiuso` a mezzanotte (fuso del patto): il mattino dopo il telefono non è in Studio. Il **telefono** chiude il tratto in corso a quella mezzanotte (interrotto); il server comunque taglia ogni tratto alla fine dello Studio. Gli orari non scavalcano la mezzanotte. `non_chiuso` **cede** a una chiusura del figlio valida consegnata dopo con `T` prima di mezzanotte (v. «La chiusura a mezzanotte»).
- **Fuso.** Sempre il fuso del patto, non quello del telefono: in viaggio lo Studio parte alle 15:00 di casa.
- **Cambio dell'ora.** `prossime_partenze` è già calcolato dal server; per le ore che non esistono o esistono due volte v. «Le partenze automatiche».
- **Orologio del telefono cambiato.** Partenze e chiusure si decidono sull'ora del server agganciata; senza rete dopo un riavvio vale l'orologio del telefono, e resta `cambio_ora` nel registro. Il server ricontrolla tutto: una chiusura con un'ora nel futuro vale all'arrivo (quindi `troppo_presto`, lo Studio torna). I tratti usano `elapsedRealtime`, che l'orologio non sposta.
- **Senza rete per ore.** Telefono e computer partono, cronometrano, coprono. Tratti e chiusure partono dopo e valgono col loro momento (entro 48 ore). Il server crea lo Studio alla prima richiesta, con l'inizio alle 15:00.
- **Telefono spento alle 15:00.** Il server crea lo Studio lo stesso (basta una richiesta qualsiasi, anche del computer o del genitore). Il telefono, riacceso prima di una chiusura nota, è in Studio da quell'istante.
- **Computer acceso senza telefono.** Il computer parte in Studio alla partenza (la legge dal patto), ma **non può chiuderlo**: mostra solo lo stato, la chiusura serve il telefono (o il genitore). Lo Studio automatico nasce solo se il figlio ha un telefono 0.18: perciò `prossime_partenze` è `[]` quando non c'è, e **il computer non parte da solo** (niente Studio che nessuno può chiudere). Un figlio col **solo** computer non entra mai in Studio.
- **Nessun telefono 0.18 (o tenuto alla 0.17 apposta).** `prossime_partenze` è `[]`: né telefono né computer partono da soli. A ogni partenza prevista ma saltata il server manda ai genitori `studio_non_partito` (un solo avviso aperto). Tenere il telefono vecchio diventa visibile e ripetuto, non una card passiva.
- **Secondo account Windows / Cambia utente.** Studio e blocco valgono solo sull'account del figlio. Un altro account non è coperto; il server vede il computer del figlio andare «spento» e la rete di sicurezza (parte B) lo coglie come «computer sparito» se era in blocco/Studio. Limite noto: senza un amministratore che impedisca altri account, un secondo account aggira tutto.
- **Spegnimento annullato ma poi avvenuto.** Un'altra app tiene la schermata «queste app impediscono l'arresto»: Pactum dopo 60 s manda `ripresa spegnimento_annullato`, ma azzera il segno «fine sessione già fatta»; quando lo spegnimento avviene davvero, il `SessionEnding`/`SessionEnded` successivo rimanda la `sospensione` (il computer non resta «acceso e muto»).
- **Sessione normale in corso alle 15:00.** Si chiude `terminata` all'inizio dello Studio (con `sessione_chiusa` scritto sullo Studio). Durante lo Studio non se ne avviano (`studio_in_corso`).
- **Lavori scaduti o dati durante lo Studio.** Il blocco aspetta (`rimandato`) e parte alla chiusura (o a mezzanotte), se restano lavori aperti che bloccano. Una bocciatura durante lo Studio riporta il lavoro a `da_fare` col blocco subito, ma aspetta lo Studio.
- **Avvio a mano.** Col blocco attivo e la rete → `409 blocco_faccende`. Prima delle 15:00 → alle 15:00 assorbe la partenza, prende il vincolo delle 16:00 e `conta_dal` = 15:00 (il tempo di prima non conta). Senza rete → v. `avvia`.
- **Configurazione cambiata durante lo Studio.** Le liste valgono dalla prossima partenza; orari, giorni e minimo dal giorno dopo. Lo Studio aperto tiene le condizioni delle sue partenze. Un dispositivo offline si allinea quando sente il server.
- **Riavvio durante un tratto.** Il tratto in corso si chiude all'ultimo punto salvato (`interrotto`), e conta fino a lì. Il tempo usa `elapsedRealtime`: un riavvio lo azzera, quindi il tratto non può continuare oltre il riavvio.
- **Due telefoni.** La barriera c'è su tutti. I tratti dei due telefoni vanno tutti al server; i `minuti_attivita` sono la durata dell'**unione** degli intervalli validi, quindi la parte comune di due tratti sovrapposti conta una volta sola (non si scarta un intero tratto). «Chiudi lo Studio» segue i `minuti_attivita` del server, non i soli tratti locali. Limite noto: un telefono-Pactum può fare da «segnatempo» nel cassetto mentre il figlio usa un dispositivo che Pactum non conosce (v. «Cosa misura il timer»).
- **Prima approvazione.** Lo Studio parte dal giorno dopo l'approvazione.
- **Server spento più di 48 ore.** Le partenze più vecchie non creano Studi; tratti e chiusure di quei giorni restano senza Studio o ricevono `404` (il telefono li toglie e lo dice). Il genitore vede il buco. Una partenza dentro le 48 ore ma già oltre la sua mezzanotte nasce **e** si chiude `non_chiuso` nella stessa transazione (solo `studio_non_chiuso`, niente `studio_iniziato`).

### F. Database e migrazione

- **Nessuna tabella che c'è già cambia.** I lavori approvati usano la sola riga `faccende_approvazione_dal` in `patto` (`INSERT OR IGNORE`, scritta una volta al primo avvio). Lo Studio è **tabelle nuove** (`CREATE TABLE IF NOT EXISTS`):
  - **`studio_config`**: una riga per figlio;
  - **`studio_versioni`**: le configurazioni approvate, in sola aggiunta;
  - **`studio_svolte`**: indice unico su (`figlio_id`) `WHERE fine_ts IS NULL` (una aperta per figlio) e unico su (`figlio_id`, `giorno`) per le `automatica`; una riga chiusa non cambia più (trigger), **con l'unica eccezione** del passaggio `non_chiuso` → `figlio` (il trigger permette solo quello);
  - **`studio_tratti`**: `id` del telefono, in sola aggiunta; cambia solo da `in_corso` a un esito finale (trigger);
  - **`studio_partenze`** (`figlio_id`, `giorno`, `studio_id`, chiave primaria `(figlio_id, giorno)`): ogni partenza trattata vi è scritta una volta sola — crei uno Studio, entri in uno aperto o sia saltata (nessun telefono 0.18). La valutazione legge **prima**, senza transazione, se c'è una partenza passata non ancora qui dentro, e apre `BEGIN IMMEDIATE` solo in quel caso.
- Una riga `studio_ultimo_giro` in `patto` segna l'ultima volta che la v4.0 ha valutato le partenze: serve a riconoscere, al ritorno dalla v3.9, che nel frattempo ha girato un server che lo Studio non lo conosce.
- **Cosa fa partire la migrazione:** `_va_migrato_a_v40` = ci sono dati **e** manca la riga `faccende_approvazione_dal`. La copia `.prima-v4.0-<data>` si fa in `init_db` **prima** dello schema, col codice di oggi; se non riesce, il server **non parte**; una copia vecchia si riusa solo se i dati sono ancora quelli. La riga `faccende_approvazione_dal`, una `studio_config` approvata (versione 1) per **ogni figlio che c'è** e la riga 1 di `studio_versioni` si scrivono **nella stessa transazione**, al commit finale di `init_db`.
- **Configurazione iniziale alla migrazione:** la `studio_config` del figlio nasce con i valori decisi dalla famiglia — `lun`–`ven`, `15:00`, `16:00`, `60` min, **liste vuote** — `stato: "approvata"` (versione 1), `orari_dal` = il **giorno dopo** quell'avvio (fuso del patto), `decisa_da: null` (l'app la mostra come «Decisa dalla famiglia all'aggiornamento»). Un figlio **creato dopo** la migrazione (`POST /api/figli`) nasce con `stato: "nessuna"` (niente Studio finché non lo propone e un genitore approva).
- *(La nota «con le liste vuote lo Studio copre tutto fuori dalle app sempre usabili, e le liste vanno proposte dal figlio e approvate da un genitore prima che lo Studio sia utile» va nelle **note di `versioni.json`**, che la famiglia legge all'aggiornamento, non solo nel contratto.)*
- **Tornare al server v3.9** si fa in due modi:
  - **(a) Rimettere l'immagine v3.9 senza toccare il database** (il modo consigliato: la v4.0 aggiunge solo tabelle nuove e una riga, quindi la v3.9 ci gira — `init_db` fa `CREATE IF NOT EXISTS`, il patto si legge per chiave). Un server v3.9 però sblocca all'arrivo delle foto e non conosce `studio`. Perciò, **prima di tornare alla v4.0** dopo il modo (a), al primo avvio la v4.0: 1) riconosce dal confronto fra `studio_ultimo_giro` e i battiti arrivati dopo che nel frattempo ha girato un server senza Studio; 2) in quel caso **porta `faccende_approvazione_dal` ad adesso** (così le foto arrivate e già sbloccate dalla v3.9 **non tornano a bloccare a posteriori**, come ha deciso Andrea) e **non crea** Studi per le partenze cadute fra `studio_ultimo_giro` e adesso.
  - **(b) Rimettere la copia `.prima-v4.0-<data>`** (si perde tutto quello che è successo dopo). Dopo il modo (b) la riga `faccende_approvazione_dal` non c'è, e nasce di nuovo al primo avvio della v4.0 con l'ora di quell'avvio.
  - In nessun caso una foto già sbloccata torna a bloccare: è la decisione di Andrea «niente blocchi a posteriori per le foto vecchie».

### G. Compatibilità

- **App del figlio 0.17 con server v4.0:** segue `attivo`, quindi resta **bloccata fino all'approvazione**; i lavori da approvare sono in `blocco.da_fare` (ignora `stato`/`foto_ts`), così la **barriera** li mostra sempre. **Però, aprendo la pagina dei lavori**, per fino a un minuto un lavoro `da_approvare` (stato `fatta`) sparisce dai «da fare» e compare tra i «Fatti» (la pagina usa l'elenco di `GET /api/faccende`, `VistaFaccende.daFare`, più fresco del blocco), e la scheda del blocco resta senza elenco: può sembrare un errore, ma la barriera lo mostra sempre. Dopo 24 ore o una reinstallazione può tornare «Scatta la foto», e la foto nuova riceve `409 non_da_fare`, che la 0.17 butta in silenzio. Non conosce lo Studio: durante lo Studio, se ci sono lavori aperti che bloccano, resta bloccata (più stretta); le sue sessioni **non** vengono chiuse dallo Studio e partono (niente `studio_in_corso` sotto la 0.18). I tipi nuovi di notifica li mostra col `messaggio`.
- **App del genitore 0.17 con server v4.0:** «Segna come svolto» approva e sblocca, ma la domanda dice solo «non si potrà più bocciare»; la notifica della foto dice ancora «…ha fatto «X»: tocca per vedere la foto», **senza parlare di approvazione** (il testo lo scrive l'app, non il server); dopo 24 ore la foto sparisce dal conteggio della Panoramica (`fotoDaGuardare` conta solo 24 ore); la scheda Lavori, che calcola il blocco da sola, può dire «nessun blocco» mentre il figlio è bloccato (la Panoramica, che usa `blocco` del server, è giusta); «Boccia» sparisce dopo 24 ore; durante lo Studio la Panoramica dice «Blocco attivo» (non sa del rinvio); **non può chiudere lo Studio né approvarne la configurazione**, che arriva come «Novità». Perciò: **tutti i genitori vanno portati alla 0.18** (v. ordine di rilascio).
- **App del genitore 0.16:** non ha la conferma e non può approvare.
- **App del genitore 0.18 con un server più vecchio della v4.0:** lo riconosce dalla risposta di `GET /api/faccende` **senza il campo `blocco`** (che la v4.0 aggiunge): in quel caso usa i testi della v3.9 («Segna come svolto», senza promettere lo sblocco) e **non mostra lo Studio**. Altrimenti direbbe «Telefono e computer si sbloccano» mentre su un server v3.9 la conferma non sblocca.
- **Programma del computer 0.13/0.14 (quello installato):** resta coperto fino all'approvazione, perché i lavori da approvare sono in `da_fare` (ignora `stato`/`foto_ts`); ma dice ancora «si sblocca con la foto». Non conosce lo Studio (niente copertura dello Studio, il blocco dei lavori non aspetta) né il guardiano. Si aggiorna solo reinstallando a mano la 0.18.
- **App 0.18 con server v3.9:** `/api/studio*` → `404`/`405` → «per lo Studio serve aggiornare il server»; senza `faccende_approvazione_dal` e senza i campi nuovi del blocco, i lavori si comportano come nella v3.9 (sblocco alla foto).
- **Versioni:** figlio e genitore **0.18** (codice 18); programma del computer **0.18** (codice 18, dopo lo 0.14 installato).
- **Ordine di rilascio (decisione di Andrea):** prima il **server** sul NAS (APK e `pactum-computer.zip` in `server/apk`, poi la ricostruzione dell'immagine); **subito dopo, su ogni telefono dei genitori: Impostazioni → Controlla aggiornamenti, fino alla 0.18, prima delle 15:00 del giorno dopo** (un genitore 0.17 approva i lavori ma non lo Studio né la configurazione; da quel momento lo Studio parte con le liste vuote finché un genitore 0.18 non le approva); poi le **app del figlio** si aggiornano da sole; poi il **programma del computer** reinstallato a mano. *(Caveat: con il server v4.0 e tutti i genitori ancora alla 0.16, nessuno può approvare e il blocco resta; un genitore alla 0.17 può già approvare i lavori, quindi la finestra è breve.)* Mettere la stessa avvertenza nelle note di `versioni.json`.

### H. Cosa cambia nelle sezioni di prima

Queste regole delle versioni precedenti diventano, dalla v4.0, così (il numero è la riga del contratto v3.9 a cui si riferiscono):

- **`:42`** (sforamento `fascia_oraria`): «(v4.0) Per le `fascia_oraria` lo sforamento c'è solo da **60 secondi** d'uso dentro l'occorrenza; sotto, niente sforamento. V. v4.0 parte D.»
- **`:286`** (patto etico dei siti, primo punto «Il genitore vede, non blocca»): «(v4.0) Con **un'eccezione**, decisa da Andrea: durante la **Sessione Studio** il programma del computer copre i siti che non sono nella lista approvata (proposta dal figlio, approvata da un genitore). Fuori dallo Studio nessun sito si copre e un genitore da solo non copre nessun sito. V. v4.0, «Lo Studio e i siti».»
- **`:694`** (v3.6, «Si sblocca **appena arriva l'ultima foto**»): «(dalla v4.0: quando un genitore **approva** la foto dell'ultimo lavoro; mandare la foto non sblocca più — v. v4.0 parte A)».
- **`:763`** (boccia «da **meno di 24 ore**»): «(v4.0: un lavoro **da approvare** si boccia senza limite di tempo, finché nessuno l'ha approvato; le 24 ore restano solo per le foto di prima della v4.0)».
- **`:778`** (foto → `faccende_finite` «…telefono e computer sbloccati»): «(v4.0: la foto non sblocca più; `faccenda_fatta` dice «aspetta l'approvazione» e `faccende_finite` parte all'**ultima approvazione**)».
- **`:786`** (`blocco.attivo` = almeno una `da_fare` con `blocco_da` passato): «(v4.0: contano anche i lavori **da approvare** — `fatta` non confermati con foto dalla v4.0; `blocco.da_fare` li porta con `stato`/`foto_ts`, più `rimandato` e `studio`)».
- **`:799-800`** (sessioni e faccende: col blocco `avvia` → `409 blocco_faccende`; «se il blocco parte durante una sessione, vince la barriera del blocco»): «(v4.0: durante la **Sessione Studio** vale il contrario — il blocco dei lavori **aspetta** (`rimandato`) e parte a fine Studio; e durante lo Studio non si avviano sessioni, `409 studio_in_corso`, prima di `blocco_faccende`)».
- **`:806`** (telefono: «il blocco resta finché il server non l'ha ricevuta»): «(v4.0: e finché un genitore non ha **approvato** la foto)».
- **`:816`** (computer: «Si sblocca da solo quando dal telefono hai mandato la foto di ogni faccenda»): «(v4.0: la frase diventa «Si sblocca da solo quando un genitore ha **approvato** la foto di ogni lavoro»)».
- **`:819-820`** (computer: `chiuso_durante_blocco` al riavvio): «(v4.0: anche `chiuso_durante_studio`, stesse condizioni; e, per uno spegnimento annullato, `programma_chiuso` con `causa: "spegnimento_annullato"` solo se chiuso in blocco o in Studio, più `ripresa` `spegnimento_annullato`)».
- **`:839`** (pulizia: le foto si tengono 30 giorni): «(v4.0: la foto di un lavoro `da_approvare` **non si cancella**; quella di un lavoro **approvato** si cancella 30 giorni dopo l'approvazione (`confermata_ts`); le foto di prima della v4.0 30 giorni dopo l'arrivo, come prima; **bocciare** la cancella subito. V. v4.0 «Le foto da approvare non si cancellano».)».
- **`:868`** (il giro di `faccende_finite` e «telefono e computer sbloccati» all'ultima foto): «(v4.0: il giro si chiude all'**ultima approvazione**, e `faccende_finite` parte allora)».
- **`:994`-`:998`** (v3.9 conferma: «Lo sblocco **non cambia**… Confermare non blocca e non sblocca niente»): «(fino alla v3.9; **dalla v4.0 approvare = confermare, e sblocca**: per un lavoro `da_approvare` il `foto_ts` nel corpo è obbligatorio, 422 senza. V. v4.0 parte A)».
- **`:1015`** (le voci di `blocco.da_fare` restano nella forma ridotta, senza i campi nuovi): «(v4.0: ogni voce ha in più `stato` e `foto_ts`; le app vecchie li ignorano)».
- **`:420`-`:421`** (eventi dei computer `sospensione`/`ripresa`, motivi di `ripresa`): «(v4.0: `ripresa` ha anche `motivo: "spegnimento_annullato"`; dopo una disconnessione in blocco/Studio resta `motivo: "accesso"`)».
- **`:424`-`:425`** (manomissione dei computer, `sotto_tipo`): «(v4.0: anche `guardiano_assente` (con `stato`), `istanza_occupata`, `programma_chiuso` con `causa` (`spegnimento_annullato` o `disconnessione`), `chiuso_durante_studio`, `stato_studio_perso`, e `computer_sparito` **scritto dal server** quando il computer smette di battere in blocco/Studio senza una `sospensione` pulita)».

---
**Versione: v4.0 — 07/10/2026** (decisioni di Andrea): i lavori di casa si sbloccano solo quando un genitore li approva (`POST /api/faccende/{id}/conferma` sblocca, idempotente per lo stesso genitore/`foto_ts`; i lavori da approvare restano in `blocco.da_fare` con `stato`/`foto_ts`; campo `da_approvare`; `foto_ts` obbligatorio dopo il 404 — 422 senza; bocciatura senza scadenza finché non approvato; foto del `da_approvare` tenute, quella approvata cancellata 30 giorni dopo l'approvazione; `faccenda_fatta` «foto da approvare» con un solo avviso aperto per lavoro; `faccende_finite` all'ultima approvazione; «sbloccati» solo se il blocco era attivo e non rimandato; `faccenda_confermata` al figlio e ai genitori; `faccende_da_approvare` in finestra e famiglia; `faccende_approvazione_dal` in `patto` per i lavori di prima, nessuna colonna nuova; il telefono non sblocca più localmente). Il programma del computer non resta chiuso: attività pianificata di utente «Pactum» ogni minuto con `--guardiano` (esce in silenzio se un'istanza c'è già; contro il mutex occupato ascolta subito in `Main`, chiede, riprova dopo 10 s, non accusa se chi tiene il mutex è un processo `Pactum.exe`, e se parte prende il mutex `.Riserva` e manda `manomissione istanza_occupata` una volta per accensione); **nessuna istanza chiude un'altra** (aggiornamento chiudendo dal menu), nessuna versione in HKCU; spegnimento annullato provato da `WM_ENDSESSION fEndSession=FALSE` (timer di 60 s di riserva, niente uscita in `SessionEnded`, segno «fine sessione» azzerato), `ripresa spegnimento_annullato`/`accesso`, `manomissione programma_chiuso` con `causa` (`spegnimento_annullato`/`disconnessione`) solo in blocco o Studio; all'avvio sente il server fino a 5 s prima di coprire; `manomissione guardiano_assente`, `chiuso_durante_studio`, e **`computer_sparito` scritta dal server** quando il computer smette di battere in blocco/Studio senza `sospensione` pulita (rete di sicurezza contro un Pactum ucciso che non riparte). La Sessione Studio (`/api/studio`): configurazione per figlio proposta dal figlio e approvata da un genitore (`studio_versioni`, orari/giorni/minimo dal giorno dopo, liste dalla partenza successiva, guardia `orari_impossibili`), partenze automatiche lun–ven alle 15:00 decise dal server con `prossime_partenze` anche senza rete (telefono 0.18 obbligatorio, altrimenti `prossime_partenze` `[]` e `studio_non_partito` ai genitori; tabella `studio_partenze`, ogni partenza una volta sola, lettura prima e `BEGIN IMMEDIATE` solo se serve), avvio a mano (`avvio_scaduto`/`troppo_tardi`/`blocco_faccende` anche in ritardo), tratti di attività di durata libera (`compiti`/`lavori_di_casa`/`altro`) cronometrati con `elapsedRealtime`, con `secondi_contati` e `minuti_attivita` come **unione** tagliata a `[conta_dal, fine]`, `conta` deciso una volta sola, `minuti_alla_chiusura` congelato; chiusura dal **solo telefono** (anche senza id: `giorno`/`chiave`) dopo le 16:00 e il minimo con dichiarazione (10–1000), chiusura del genitore con **motivo obbligatorio**, chiusura automatica a mezzanotte (`non_chiuso`, che cede a una chiusura tardiva valida del figlio); copertura **dello schermo** (barra libera), browser rifiutati in lista (elenco ampio) e non leggibili coperti, durante lo Studio una barra degli indirizzi che non si legge copre, `firme` dei programmi firmati controllate; `rimandato` e `studio` nel blocco; sessioni normali chiuse `terminata` all'`inizio_ts` e `409 studio_in_corso` durante; emendamento al patto etico dei siti sul computer. Fasce: soglia di 60 secondi scritta. Solo tabelle nuove e righe in `patto`; copia del database prima per sicurezza.
**v3.9 — 05/10/2026** (richieste di Andrea): `PATCH /api/faccende/{id}` per modificare un lavoro da fare (titolo, nota, ora del blocco; notifica `faccenda_modificata`); `POST /api/faccende/{id}/conferma` ("svolto", `confermata_ts`/`confermata_da`, non più bocciabile, notifica `faccenda_confermata`; lo sblocco resta all'ultima foto); `GET /api/faccende?cerca=` su tutta la storia. Due colonne nuove nel database.
**v3.8 — 05/10/2026** (richieste di Andrea): `totale` in `medie` (somma degli ultimi 7 e 30 giorni con dati); `uso_recente` e `medie` anche in `GET /api/patto?tempi=1` (questo dispositivo e `dispositivi[]`, solo su richiesta), identici alla finestra; avviso del tempo finito a 30 su 30 sul telefono, `sforamento` invariato da 31. Nessun cambio al database.
**v3.7 — 04/10/2026** (richieste di Andrea): `sospensione` anche dai telefoni (spento come i computer, anche consegnata in ritardo col suo `ts_device`), battiti ogni ~15 minuti anche in stand-by, avvisi di silenzio senza accuse; nei testi "lavori di casa" al posto di "faccende". Nessun cambio al database.
**v3.6 — 02/10/2026** (decisioni di Andrea): più genitori (tabella `genitori`, `GET/POST /api/genitori`, codice di 6 cifre, `POST /api/abbina` con `tipo: "genitore"`, revoca, `io` e `genitori` in `GET /api/famiglia`), chi ha fatto cosa nelle risposte, notifiche del genitore lette da ciascuno; le faccende (`/api/faccende`, foto JPEG fino a 4 MB senza dati nascosti tenute 30 giorni, boccia entro 24 ore, annulla, `GET /api/faccende/blocco`, `faccende` e `blocco` in patto e finestra, `faccende_da_fare` e `blocco_attivo` in famiglia, notifiche `nuove_faccende`, `faccenda_fatta`, `faccende_finite`, `faccenda_bocciata`, `faccenda_annullata`); blocco del telefono tranne le app fondamentali e del computer intero finché le faccende non sono fatte; niente sessioni durante il blocco. Copia del database prima della migrazione.
**v3.5 — 01/10/2026** (decisioni di Andrea): le Sessioni — il figlio crea sessioni (nome + app del telefono, anche `gruppo:apk`), il genitore le approva una volta e approva ogni cambio della lista (`modifica_in_attesa`); il figlio le avvia quando vuole per 1–1440 minuti e le può chiudere prima; nelle app della sessione il tempo non conta, fuori lista conta come sempre; barriera "Esci" sulle app fuori lista; `sessioni`, `sessione_in_corso`, `sessioni_svolte` in `GET /api/patto`, `sessioni`, `sessioni_da_approvare`, `sessioni_svolte` in `GET /api/finestra`, `sessioni_da_approvare` in `GET /api/famiglia`; notifiche `sessione_da_approvare`, `sessione_risposta`, `sessione_eliminata`; `sessioni_minuti` nella fotografia e accanto a `totale_minuti` in `uso_recente`. Due tabelle nuove. In più, per tutto il server: i corpi JSON con `NaN`/`Infinity`, surrogati da soli o interi oltre i 64 bit → `422` prima di ogni endpoint; un intero oltre i 64 bit in un percorso o in una query → `422` (`404` sulle sessioni), mai un `500`; minuti del giorno oltre 1440 non validi.
**v3.4 — 01/10/2026** (decisione di Andrea del 30/09): le proposte del figlio — `POST /api/proposte` anche col token del dispositivo (notifica `nuova_proposta` al genitore), `POST /api/proposte/{id}/risposta` anche col token del genitore sulle proposte del figlio (accetta = vale subito; notifica `proposta_risposta` a tutti i dispositivi del figlio; niente `modifica_regola` doppia al genitore), `POST /api/proposte/{id}/ritira` per chi ha proposto (stato `ritirata`, notifica `proposta_ritirata` all'altro); campo `autore` su ogni proposta; `proposte_inviate` in `GET /api/patto` (`proposte_pendenti` resta quelle a cui risponde il figlio), `proposte_pendenti` in `GET /api/finestra`, `proposte_da_decidere` in `GET /api/famiglia`; una sola pendente per regola di chiunque sia. Database: colonna `autore` e stato `ritirata`, con la copia prima della migrazione.
**v3.3 — 30/09/2026** (decisione di Andrea): limite sul totale del dispositivo — `app_o_categoria = "totale"` accettato per telefoni e computer, valutato dalle app con lo `sforamento` di sempre (semaforo invariato); nella finestra la regola totale non ha `nome` e il suo limite sta accanto al `totale_minuti` del giorno in `uso_recente` (`limite`, `regola_id`, `bonus`); nel confronto delle proposte "tutto il telefono" / "tutto il computer"; `GET /api/notifiche?dopo_id=N` (solo le non lette arrivate dopo) e risposte `/api/` compresse gzip su richiesta, per l'app del genitore sempre attiva. Nessun cambio al database.
**v3.2 — 25/09/2026**: copia notturna del registro fatta dal server stesso (sul NAS non c'è un programmatore di attività): una al giorno dopo le 03:00 del patto, controllata con `integrity_check`, ultime 30; campo `backup` in `GET /api/salute`; ripristino di una copia all'avvio con `PACTUM_RIPRISTINA`. Nessun cambio per le app.
**v3 — 23/09/2026** (decisione di Andrea): famiglia con più figli, ogni figlio con più dispositivi (telefoni e computer) con regole, tempi, bonus e registro separati; vita reale e striscia per figlio; token per dispositivo e per genitore con abbinamento a codice di 6 cifre; computer con programmi (`exe:`), siti (`sito:`, letti dalla barra degli indirizzi, solo il dominio) e spegnimento che non è un'interruzione; compatibile con le app 0.7.
**v2.4 — 19/09/2026** (redesign Fascia B/C della tavola rotonda, deciso da Andrea): `striscia` aggregata degli 8 giorni in `GET /api/finestra` **e identica** in `GET /api/patto`, uscita da una sola funzione del server; semaforo senza verde nei giorni senza fotografia (un giorno di cui non si sa niente non è un giorno mantenuto); `giorno` opzionale nei dettagli di `sforamento`, così gli sforamenti consegnati in ritardo cadono nel giorno giusto; `riepilogo` (giorni fuori regola + interruzioni negli 8 giorni) e `semaforo` per regola anche in `GET /api/patto`, cosi' il figlio vede gli stessi fatti del genitore; `POST /api/segno` (riconoscimento del genitore a testo fisso, max 1 al giorno) + `segno_oggi` nella finestra + notifica di tipo `segno` al figlio.
**v2.3 — 01/08/2026** (decisione di Andrea col padre): il genitore **vede** i siti visitati dal figlio, senza poterli bloccare — nuovo evento `siti_giornalieri` (fotografia cumulativa del giorno, monotona, `dns_cifrato` appiccicoso come dichiarazione di cecità), `siti_recenti` in `GET /api/finestra` **e identico** in `GET /api/patto` (tavola rotonda), limiti del dato e patto etico messi per iscritto nella sezione "Siti visitati". Solo domini, mai URL, contenuti o ricerche.
**v2.2 — 15/07/2026** (richieste di Andrea + feedback del padre): fotografia uso_giornaliero con `nomi` e `uso_categorie`; finestra con `uso_recente` (tempi di TUTTE le app, 8 giorni, limiti accanto dove esistono, mai zeri finti). Il digest giornaliero del genitore (notifica all'ora scelta con totale + prime app) è comportamento dell'app genitore, nessun endpoint nuovo.
**v2.1 — 15/07/2026** (dopo revisione adversariale tappa 5): atomicità garantita su risposta-proposta/regole/dichiarazioni concorrenti; proposte `annullata` all'eliminazione della regola; confronto ricalcolato in lettura per le pendenti; `giorno` dichiarazioni vincolato (oggi ↔ −7gg); arbitro congelato sulla dichiarazione; campo `verdetto.registro`; semaforo per le `vita_reale`; convenzione `app_o_categoria` (pacchetto o `categoria:*`).
**v2 — 15/07/2026.** Novità v2 (tappa 5): proposte (creazione col confronto calcolato dal server, risposta del figlio con auto-applicazione delle accettate), dichiarazioni vita reale con verdetto (conferma / per conto di / ribalta), bonus agganciato a una regola limite_tempo (`regola_id` obbligatorio), `GET /api/patto` per il sync del figlio, notifiche con `destinatario` e nuovi tipi, sforamenti generati dal valutatore locale (max 1 per regola per giorno, limite efficace = limite + bonus della regola).
**v1 — 14/07/2026.** Cambi al contratto: prima qui, poi nel codice di entrambi i lati.
