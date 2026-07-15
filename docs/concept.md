# Pactum — Documento di fondazione

**Data:** 14 luglio 2026 · **Stato:** concept chiuso, architettura verificata, costruzione avviata
**Autore delle decisioni:** Andrea. Questo documento registra le SUE scelte; la ricerca di mercato ha avuto solo ruolo di validazione.

## Visione

Un'app Android per ragazzi di 15-17 anni **che sanno di averla**, distribuita come APK scaricabile da browser (sideload, niente store). Rovescia il parental control classico: **il figlio si dà le proprie regole, il genitore verifica**. L'app non blocca mai niente: è un testimone, non un carceriere. Obiettivo educativo: autonomia e autogestione. Prima versione: un figlio, un genitore.

Pitch: *"un'app che permette di istruire tuo figlio nel mentre gli insegna cosa significa autonomia e autogestione"*.

## Missione

Pactum esiste per trasformare il momento più conflittuale della vita digitale di una famiglia — il controllo del telefono — in un allenamento all'autogestione. Non promette genitori più tranquilli grazie a più controllo: promette figli più capaci di gestirsi, e genitori che possono vederlo accadere.

Il successo di Pactum non si misura in "il ragazzo usa meno il telefono". Si misura in: **il ragazzo mantiene la parola che si è dato, e quando non ce la fa lo affronta a viso aperto invece di nasconderlo**. Le app di controllo classiche funzionano finché il genitore vince la guerra tecnica — e la perde sempre. Pactum non combatte quella guerra: sostituisce la sorveglianza con una testimonianza concordata, e sposta le conseguenze dove devono stare, nella relazione tra genitore e figlio.

Se un giorno una famiglia disinstalla Pactum perché non serve più, Pactum ha vinto.

*(Il percorso di ragionamento e le analisi dietro ogni scelta: [analisi-e-ragionamento.md](analisi-e-ragionamento.md).)*

## Le decisioni

### Le regole (le scrive il figlio)
- **Almeno una obbligatoria.** Tipi disponibili, tutti opzionali a scelta sua:
  - limiti di tempo per app (es. "max 1h TikTok al giorno")
  - fasce orarie (es. "telefono spento dopo le 23")
  - categorie (es. "niente social durante i compiti")
  - **regole di vita reale** (es. "cammino un'ora al giorno") — verificate da un arbitro umano
- **Modifiche in autonomia ma con lock asimmetrico:** allentare una regola richiede che siano passati **4 giorni** dall'ultima creazione/modifica; stringerla ha effetto immediato. Ogni modifica genera notifica al genitore e finisce nello storico.
- **Eccezione concordata:** una modifica nata da proposta accettata da entrambe le parti ha effetto immediato anche se allenta (il lock protegge il figlio da sé stesso; se sono d'accordo tutti e due, non serve).

### Quando sfora
- **Registro + notifica al genitore. Mai blocchi.** La conseguenza di un registro brutto vive nel mondo reale (la conversazione), non nell'app.

### Bonus time
- Il figlio può auto-concedersi **+5, +15 o +30 minuti** per una ragione personale.
- Tetto **giornaliero** e **settimanale** (valori di default da confermare — v. Decisioni aperte).
- Il genitore vede i minuti bonus residui entrando nell'app e riceve una **notifica** che lo invita a controllare.

### La verifica umana (arbitro, modello stickK + variante Andrea)
- Per le regole di vita reale il figlio sceglie un **arbitro** (es. la mamma).
- Il figlio **dichiara** nell'app com'è andata. Successo dichiarato → serve conferma dell'arbitro. **Fallimento dichiarato → creduto sulla parola** (nessuno mente contro sé stesso). L'arbitro può ribaltare una dichiarazione.
- **Variante di Andrea:** il genitore può confermare per conto dell'arbitro ("ho parlato con lei fuori dall'app"). Nel registro risulta come *"confermato dal genitore per conto di [arbitro]"* — si vede sempre chi ha garantito cosa.

### Le proposte del genitore
- Il genitore **non modifica MAI direttamente** le regole. Può **proporre** una modifica su qualsiasi regola, con motivazione.
- Il figlio accetta o rifiuta; tutto va nello storico.
- **La notifica della proposta mostra sempre il confronto col valore attuale** (es. "IG a 1h" → "−30 min rispetto ad ora").

### La finestra del genitore (finestra, non vetrata)
Il genitore vede **tutto ciò che riguarda il patto**: regole esistenti, semaforo di rispetto, sforamenti, storico modifiche, bonus residui, tentativi di manomissione, silenzi dell'app. (Se una regola nomina TikTok, gli sforamenti nominano TikTok: è dentro il patto, quindi dentro la finestra.)

**Allargamento deciso il 15/07/2026** (richiesta di Andrea su feedback diretto del padre, primo utente genitore): la finestra include anche **i tempi d'uso giornalieri di TUTTE le app** (totale + per app, con l'eventuale limite accanto), non solo di quelle con una regola. È un allargamento consapevole rispetto alla scelta iniziale: restano comunque fuori i contenuti, i messaggi, la posizione e il tempo reale — sono tempi aggregati per giornata, e vedere "2h su TikTok, nessun limite" è materiale per una conversazione, non per una punizione.

**Il digest giornaliero:** il genitore sceglie un'ora e riceve ogni giorno una notifica con il tempo totale del figlio e le prime app (il dettaglio completo nella sezione Tempo dell'app).

### Anti-manomissione ("il registro è il prodotto")
Siccome l'app non blocca, tutto il valore sta nella credibilità del registro:
- modalità aereo prolungata, chiusura forzata, cambio orologio, disinstallazione → **eventi registrati e notificati** come un qualsiasi sforamento ("l'app non ha potuto vedere dalle 15 alle 18");
- la difesa più forte è **fuori dal telefono**: il server nota il silenzio dei battiti di cuore;
- **niente Device Admin / blocco disinstallazione**: contraddice la filosofia e (verificato) fa scattare i filtri anti-stalkerware di Android per app sideload. Disinstallazione = silenzio = conversazione umana.

### Cosa Pactum NON fa (per scelta)
- Non blocca app, siti o funzioni. Mai.
- Non fa leggere al genitore messaggi o contenuti.
- Non permette al genitore di modificare o imporre regole.
- Non gira di nascosto: il figlio sa di averla, sempre.

### Motivazione del figlio
Self-improvement: se il figlio non vuole migliorare, l'app non ha senso. Tema volutamente parcheggiato (decisione di Andrea, 14/07).

## Decisioni aperte
- **Tetti bonus:** chi li fissa e i valori di default (proposta di lavoro: 30 min/giorno, 90 min/settimana, configurabili nel patto).
- **Push:** server Google (FCM) vs sistema self-hosted (ntfy sul NAS) — si decide alla tappa postino; inclinazione attuale: FCM per semplicità v1.
- **Developer verification Google 2027:** per uso famiglia basta il tier gratuito (≤20 dispositivi); distribuzione larga richiederà la verifica completa. Da ripesare nel 2027.
- **Nome definitivo:** "Pactum" (proposta di Andrea, 14/07) — confermato come nome di lavoro.

## Riferimenti (dalla ricerca del 14/07)
- Nessun prodotto esistente combina "figlio si dà le regole + genitore verifica senza bloccare".
- Modello arbitro: stickK (referee). Lock ritardato: Beeminder (akrasia horizon, 7 giorni, asimmetrico). Notifica al partner: Clearspace. Prodotto più vicino: BePresent (manca tutto il resto).
- Italia: movimento Patti Digitali (~6.000 famiglie, contratti carta-e-penna genitori-figli) = domanda organizzata per il modello patto, senza app.
- Ricerca accademica: la protezione viene dalla condivisione volontaria del ragazzo, non dalla sorveglianza (Stattin & Kerr 2000); le app di blocco correlano con PIÙ rischi (UCF/Ghosh); i teen accettano "una finestra, non una vetrata" (Badillo-Urquiola/Wisniewski).

## Metodo di lavoro (richiesto da Andrea)
Le idee vengono da Andrea; Claude fa le domande giuste e struttura. Ricerca online solo per validare/arricchire (ruolo piccolo). Le contraddizioni vanno segnalate chiaramente ma decide Andrea. Linguaggio semplice, termini tecnici spiegati in una riga.
