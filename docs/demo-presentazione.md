# Pactum — canovaccio della presentazione (8 minuti)

Prima di iniziare: doppio clic su **`AVVIA-PACTUM-DEMO.bat`** (Desktop). Lascia la
finestra nera aperta. Telefono sul **WiFi di casa**.

---

## 0. L'apertura (30 secondi) — la frase che inquadra tutto

> "Le app di parental control che esistono partono dal presupposto che il figlio sia
> il problema e il genitore il controllore. Pactum parte dal presupposto opposto:
> **è il figlio che si dà le regole**, e il genitore ha una finestra per verificare
> che le stia rispettando. Non blocca niente: registra e racconta la verità."

Poi apri l'app **Pactum Genitore**.

## 1. La Finestra — "cosa vedo io come genitore" (2 min)

Mostra la schermata principale:

- **Il banner in alto**: "In contatto — ultimo battito alle HH:MM".
  > "Questa è la prima cosa che vedo: l'app del figlio è viva e sta parlando. Se il
  > telefono venisse spento o l'app disinstallata, qui diventerebbe rosso: **il
  > silenzio è un'informazione**, non un buco."

- **Le regole con il semaforo a 8 giorni** (scorri: la regola social ha 2 quadretti rossi):
  > "Queste regole non le ho messe io: se le è date lui. Ogni quadretto è un giorno.
  > Verde = rispettata, rosso = sforata. Qui vedo che martedì e giovedì ha sforato
  > i social."

- **Lo storico modifiche** (c'è una modifica con etichetta *concordata*):
  > "Ogni modifica alle regole resta scritta. Questa ha l'etichetta 'concordata':
  > vuol dire che è nata da una mia proposta che lui ha accettato."

## 2. La sezione Tempo — la richiesta di papà (1 min)

Vai su **Tempo**. Mostra oggi, poi tocca i giorni precedenti.

> "Qui c'è il tempo su **tutte** le app, non solo su quelle con un limite — questa
> è esattamente la cosa che mi avevi chiesto tu. TikTok, YouTube, WhatsApp, con
> accanto il limite dove esiste. E se un giorno non arrivano dati, scrive 'nessun
> dato ricevuto': **non mette uno zero finto**, perché uno zero finto sarebbe una
> bugia."

## 3. Il momento forte: la proposta accettata in diretta (2 min)

C'è una **proposta pendente**: hai proposto di scendere da 90 a 75 minuti sui social.

1. Sull'app **Genitore**, scheda **Proposte**: mostra che è in attesa.
   > "Io non posso cambiargli le regole. Posso solo **proporre**. Guarda cosa vede lui."
2. Passa all'app **Figlio**, scheda **Proposte**: si vede in grande
   **"−15 min al giorno rispetto ad ora"**.
   > "Il confronto lo calcola il server, non l'app: lui vede subito **quanto** cambia,
   > non deve fare i conti. E decide: accetto o rifiuto."
3. Tocca **Accetto**.
4. Torna sull'app **Genitore** → **Aggiorna**: la regola ora è a 75 minuti e nello
   storico compare la modifica **concordata**.
   > "Fatto. La regola è cambiata perché **l'ha accettata lui**, e resta scritto che
   > era d'accordo."

## 4. Il lock dei 4 giorni — "non è aggirabile" (1 min)

Sull'app **Figlio** → **Regole** → prova ad **allentare** il limite YouTube (creato oggi).

> "Se stasera mi pento e voglio allargare una regola, non posso: c'è un blocco di 4
> giorni su ogni allentamento. Al contrario, **stringere** una regola è immediato.
> Serve a proteggere il patto dai ripensamenti del momento."

## 5. Le regole di vita reale + l'arbitro (1 min)

Sull'app **Genitore** → **Verdetti**: c'è una dichiarazione in attesa
("Leggere 20 minuti prima di dormire", arbitro: Mamma).

> "Non tutte le regole riguardano il telefono. Questa è 'leggo 20 minuti prima di
> dormire': l'app non può verificarla, quindi si sceglie un **arbitro** — la mamma.
> Lui dichiara, e io confermo. Posso anche dire 'ho sentito la mamma, confermo per
> conto suo' — e resta scritto che l'ho fatto per conto di lei."

Dai il verdetto **Confermo** davanti a lui.

## 6. Chiusura (30 sec) — dove sta il valore

> "Il server è testato con **282 test automatici**, le app sono firmate, tutto il
> registro è progettato per non poter mentire: se l'app viene messa a tacere, il
> silenzio si vede. Adesso gira sul mio PC per la demo; è già pronto anche per stare
> su un NAS acceso 24 ore su 24, con l'accesso da fuori casa."

---

## Se qualcosa non risponde durante la demo

- App dice "impossibile contattare il server" → controlla che la finestra nera del
  `.bat` sia ancora aperta e che il telefono sia sul WiFi di casa (non rete dati).
- Dati non aggiornati → tocca **Aggiorna** in alto a destra.
- In extremis: le due app hanno l'indirizzo nelle **Impostazioni** — deve essere
  `http://<IP-del-PC>:8000` (l'IP lo scrive il `.bat` all'avvio).

## Domande che papà potrebbe farti (risposte pronte)

- **"E se lo disinstalla?"** → Il server smette di ricevere battiti e la finestra
  del genitore diventa "silente". Non si può impedire una disinstallazione, ma **non
  si può nasconderla**.
- **"Funziona anche fuori casa?"** → Sì: oggi gira sul PC per la demo, la versione
  per il NAS è già pronta (Docker + tunnel) e funziona da qualsiasi rete.
- **"Chi vede i messaggi?"** → Nessuno. Pactum non legge contenuti, chat o
  posizione: solo tempi d'uso e rispetto delle regole che il figlio si è dato.
- **"Quanto costa mantenerlo?"** → Zero se gira sul NAS di casa. Un dominio per
  l'indirizzo fisso sarebbe qualche euro l'anno, opzionale.
