# Venerdì: passare a Pactum 0.8

Tempo totale: circa 40 minuti, con papà per le parti segnate.

## Prima di iniziare

- **Il codice d'accesso di papà** è il valore di `PACTUM_TOKEN_GENITORE` nel file `Desktop\Pactum-NAS\server\.env`. Serve una volta sola, sul suo telefono: mandaglielo in modo privato e poi cancella il messaggio.
- **Il computer è di papà come amministratore**: se Bitdefender o Windows chiedono di decidere qualcosa sulla sicurezza, la decisione è sua.

## 1. Il NAS (10 minuti)

1. Copia la cartella `Desktop\Pactum-NAS\server` sul NAS dentro `docker/Pactum-NAS/`, **sovrascrivendo**. Il file `.env` è lo stesso di prima: non cambia niente.
2. App Docker del NAS → progetto `pactum` → **Ricostruisci**.
   Se "Ricostruisci" non c'è: ferma il progetto, elimina **solo** il container `pactum` e l'immagine `pactum-postino`, poi riavvia il progetto.
   **Non cancellare i volumi e non toccare `pactum-tailscale`.**
3. Al primo avvio il server fa da solo una copia completa del registro, poi lo aggiorna. Se la copia non riesce, il server non parte: in quel caso scrivimi.
4. Scrivimi "fatto". Controllo io che l'indirizzo risponda con la 0.8.0.
   Se il telefono dà `ERR_CONNECTION_CLOSED`, riavvia il container `pactum-tailscale` (solo riavvio).

## 2. Il tuo telefono (5 minuti)

1. L'aggiornamento arriva da solo entro circa 15 minuti. Se vuoi farlo subito: `https://pactum.taildbae63.ts.net/scarica` → `pactum-figlio.apk` → installa sopra.
2. Si apre "Cosa vede tuo padre": leggila fino in fondo → **Ho capito**.
3. In Impostazioni deve esserci "Collegato come: Telefono". **Nessun codice da scrivere**: il telefono resta quello di prima, con tutta la sua storia.

## 3. Il telefono di papà (5 minuti)

1. Dal suo telefono: `https://pactum.taildbae63.ts.net/scarica` → `pactum-genitore.apk` → installa. Se Play Protect avvisa, scegli "Installa comunque".
2. Impostazioni:
   - indirizzo del server: `https://pactum.taildbae63.ts.net`;
   - codice d'accesso: quello del `.env`;
   - poi **Salva** e **Prova adesso**.
3. Impostazioni → Famiglia:
   - rinomina "Figlio" con il tuo nome;
   - **Aggiungi dispositivo**, con nome "Computer di Andrea" e tipo "Computer". Esce un codice di 6 cifre che vale 15 minuti.
   - Il telefono non si aggiunge: c'è già.

## 4. Il computer (10 minuti, con papà)

1. Il pacchetto è già sul PC: scompatta `Desktop\Pactum\pc\dist\pactum-computer.zip` in `Documenti`. Deve venire fuori la cartella `Documenti\Pactum`.
2. Apri `Documenti\Pactum\Pactum.exe`. Possono comparire tre avvisi:
   - **"Windows ha protetto il PC"**: "Ulteriori informazioni" → "Esegui comunque";
   - **Bitdefender lo blocca**: con papà, ripristinalo dalla quarantena e aggiungete un'eccezione per la cartella `Documenti\Pactum`;
   - **"Smart App Control ha bloccato…"**: fermati e scrivimi. Non disattivarlo: una volta spento, Smart App Control non si riaccende senza reinstallare Windows. Decidiamo insieme.
3. Nella finestra "Collega questo computer": indirizzo `https://pactum.taildbae63.ts.net` + il codice di 6 cifre → **Collega**.
4. Vicino all'orologio compare l'icona di Pactum. Da qui in poi il programma parte da solo quando entri in Windows.

## 5. Controllo finale (2 minuti)

- Nell'app di papà, Panoramica → Dispositivi: "Telefono" e "Computer di Andrea", tutti e due "In contatto".
- Scrivimi e controllo anch'io dal server.

## Copia notturna e prova di ripristino

Da questa versione il server fa da solo una copia del registro ogni notte, dopo le 3, e tiene le ultime 30. Le copie le vedi dal gestore file del NAS, nella cartella `docker/Pactum-NAS/server/backup`. Il nome porta la data: `pactum-20260926.db` è la copia di sabato 26.

**Venerdì sera** c'è solo da guardare: nella cartella `backup` deve esserci `pactum-20260925.db`. La fa da solo appena il server riparte, perché sono già passate le 3 di notte. (Se finisci dopo mezzanotte, la prima copia arriva alle 3 e si chiama `pactum-20260926.db`.) Se non c'è, scrivimi.

**Sabato mattina, come prima cosa e prima del collaudo con papà: la prova di ripristino.** Serve a essere sicuri che, se un giorno il registro si rovina, una copia si rimette davvero al suo posto. Quello che è arrivato al server dopo le 3 di notte si perde: per questo la prova si fa appena svegli.

1. Nella cartella `backup` controlla che ci sia `pactum-20260926.db`, la copia di stanotte.
2. Apri il file `.env` in `docker/Pactum-NAS/server` (oppure modifica `Desktop\Pactum-NAS\server\.env` e ricopialo sul NAS) e aggiungi in fondo questa riga:
   `PACTUM_RIPRISTINA=pactum-20260926.db`
3. App Docker del NAS → progetto `pactum` → riavvia il progetto.
4. Apri il log del container `pactum`: ci deve essere una riga che comincia con `RIPRISTINO FATTO`.
   - Se non c'è, il NAS non ha riletto il file `.env`: rifai il punto 3 con **Ricostruisci**.
   - Se c'è una riga che comincia con `RIPRISTINO FERMATO`, il server è fermo apposta e il registro non è stato toccato: togli la riga dal `.env`, riavvia il progetto e scrivimi.
5. Togli la riga `PACTUM_RIPRISTINA` dal file `.env` e riavvia di nuovo il progetto. Se poi nel log compare `RIPRISTINO GIA' FATTO`, va bene lo stesso: vuol dire che non l'ha rifatto.
6. Nell'app di papà, Panoramica → Dispositivi: "Telefono" e "Computer di Andrea" devono essere ancora tutti e due "In contatto". Può volerci un quarto d'ora: ognuno si fa sentire ogni 15 minuti circa. Se in quel quarto d'ora a papà arriva un avviso che il telefono non manda dati, è per il ripristino: il server è tornato alle 3 di notte e non sa ancora che il telefono c'è. Sparisce da solo al primo contatto.

Il registro di prima non si butta: il server lo tiene da parte.

**Non usare la copia di venerdì** (`pactum-20260925.db`): è stata fatta prima che papà aggiungesse il computer, quindi rimetterla cancellerebbe il computer e il programma sul computer non verrebbe più riconosciuto.

## Sabato: il collaudo con papà

**Prima di tutto, da solo:** la prova di ripristino della sezione qui sopra.

Le prove da fare insieme:
- una regola nuova sul computer (per esempio un gioco o un sito);
- una proposta di papà su una regola del computer, che accetti dal telefono;
- un bonus sul telefono;
- una camminata dichiarata e confermata da papà;
- il segno di papà, che deve arrivare sia sul telefono sia sul computer.

Per i buchi nella registrazione: chiudi Pactum dal menu dell'icona e controllate che papà lo veda. La sera, spento il computer, la riga del computer deve dire "Spento", senza nessun allarme.
