# Pactum — Analisi e ragionamento

**Data:** 14 luglio 2026. Questo documento registra *come* siamo arrivati alle decisioni scritte in [concept.md](concept.md): il percorso delle domande, le tensioni affrontate, la ricerca fatta e cosa ne abbiamo tenuto. Le idee sono di Andrea; la ricerca ha avuto ruolo di validazione e arricchimento (per scelta di metodo).

## 1. Il punto di partenza

Il progetto è nato da domande, non da una lista di funzionalità:

- **La paura da risolvere** (del genitore): *"non so cosa sta facendo ora mio figlio"*.
- **Target:** ragazzi 15-17, **consapevoli** dell'app (niente sorveglianza nascosta).
- **L'idea differenziante** (di Andrea, presente fin dall'inizio): il figlio **si dà da solo le proprie regole**, e sceglie un modo perché il genitore possa verificarle — anche fuori dallo schermo ("chiedi alla mamma, lei lo sa").
- **Vincoli:** solo Android, APK da browser (sideload), un figlio per semplificare.

## 2. La prima tensione: sorveglianza o patto?

Le prime due risposte si contraddicevano a vicenda: la paura "non so cosa fa ORA" chiede *sorveglianza in tempo reale*; il modello "si dà le regole da solo" risponde a una domanda diversa — *"sta rispettando la parola data?"*. Non si possono avere entrambe fino in fondo: un sedicenne che si dà le regole ma viene comunque guardato minuto per minuto percepisce il patto come finto.

**Risoluzione (di Andrea):** l'app risponde alla seconda domanda. Il prodotto è un patto con testimone, non un binocolo. Questa scelta ha guidato tutte le successive.

## 3. La ricerca di mercato (4 angoli, 14/07/2026)

Fatta *dopo* che la visione era definita, per validarla o arricchirla — non per sostituirla.

### Cosa lamentano i genitori di adolescenti (app esistenti)
- **La guerra tecnica si perde sempre:** i ragazzi aggirano Qustodio, Screen Time, Family Link e Life360 in giorni; i trucchi girano su TikTok come meme. Il "blocco" diventa un gioco a guardie e ladri che logora tutti.
- **Il modello sorveglianza danneggia la relazione:** "digital leash", litigi settimanali, "è la prova che non ti fidi di me". È il motivo n°1 di abbandono con i 15-17enni.
- **Non esiste un percorso di crescita:** le app sono pensate per bambini; a 15 anni diventano infantilizzanti, e sia il design (Family Link molla la presa all'età del consenso digitale, 14 anni in Italia) sia gli esperti spingono i genitori a disattivarle. **La fascia 15-17 è scoperta** — esattamente il target di Pactum.
- Attriti operativi (valanghe di falsi allarmi, bug, batteria, abbonamenti) accelerano l'abbandono. Lezione per Pactum: notifiche rare e significative.

### Come la vedono i ragazzi (15-17)
- La ricerca accademica dice una cosa forte: la protezione reale viene dalla **condivisione volontaria del ragazzo**, non dalla sorveglianza del genitore (Stattin & Kerr 2000, il lavoro fondativo del campo). Le app di blocco correlano con *più* rischi online, non meno (UCF/Ghosh: il 76% delle recensioni scritte da minorenni è 1 stella; parole ricorrenti: "stalking", "lazy parenting").
- I ragazzi accettano supervisione quando è **"una finestra, non una vetrata"**: il genitore vede se le regole sono rispettate e i riassunti, non tutto il contenuto (Badillo-Urquiola/Wisniewski). Il 64% dei teen in uno studio di co-design ha apprezzato strumenti dove genitore e figlio sono *pari*.
- Conferma dal mercato: Life360, dopo la rivolta dei teen su TikTok, ha lanciato "Bubbles" (posizione approssimativa invece che esatta) proprio per riconquistarli.

### Esiste già qualcosa come Pactum?
**No.** Cercato esplicitamente in inglese e italiano: nessun prodotto combina "il figlio si dà le regole + il genitore verifica senza bloccare". I pezzi esistono separati:
- **stickK** (adulti): l'"arbitro" che conferma i tuoi impegni — adottato (v. §5).
- **Beeminder** (adulti): il ritardo sulle modifiche che allentano — adottato (v. §5).
- **Clearspace / BePresent**: notifica al partner quando sfori il tuo budget — conferma il meccanismo bonus/notifiche.
- **Accountable2You / Covenant Eyes**: filosofia "segnala, non bloccare" già commercialmente provata (in una nicchia adiacente).
- **Patti Digitali** (Italia, ~6.000 famiglie): contratti carta-e-penna genitori-figli sull'uso dello smartphone. Domanda organizzata per il modello patto — senza un'app che lo faccia vivere.

### Le meccaniche specifiche
- **Pausa + "continua pure, ma tracciato"** funziona (studio PNAS sull'app one sec: 36% delle volte l'utente rinuncia ad aprire l'app; −37% tentativi in 6 settimane) ma l'effetto si consuma con l'abitudine → servono **tetti** che facciano salire il costo — esattamente i tetti giornaliero/settimanale del bonus time di Andrea.
- **Lock sulle modifiche:** Beeminder usa 7 giorni, e solo per le modifiche che *allentano* (stringere è immediato). I 4 giorni di Andrea sono in linea; l'asimmetria è stata adottata.
- **Notifica al partner quando ti allenti le regole:** meccanica già in produzione (Clearspace, stickK); stickK dichiara che avere un arbitro raddoppia il tasso di successo (dato del vendor, da prendere con le pinze).

## 4. La contraddizione affrontata

**Scelta iniziale di Andrea:** "il genitore deve vedere tutto". **Evidenza raccolta:** i teen accettano la finestra, non la vetrata; la trasparenza totale rischia di far percepire il patto come finto. Segnalata apertamente (col limite dichiarato: evidenza qualitativa, nessuno studio controllato che confronti le due condizioni sui 15-17).

**Decisione di Andrea:** finestra al posto della vetrata. Il genitore vede tutto ciò che riguarda *il patto* (regole, semaforo, sforamenti, storico, bonus, manomissioni, silenzi), non il minuto-per-minuto fuori dalle regole.

## 5. Gli spunti adottati (e le varianti di Andrea)

| Spunto | Origine | Variante di Andrea |
|---|---|---|
| Arbitro che conferma solo i successi (i fallimenti auto-dichiarati sono creduti sulla parola) | stickK | Il genitore può confermare *per conto* dell'arbitro ("ho parlato con lei fuori dall'app") — registrato come conferma-per-conto-di, così si vede sempre chi ha garantito cosa |
| Lock asimmetrico sulle modifiche (allentare = attesa, stringere = subito) | Beeminder (7gg) | 4 giorni; **eccezione concordata**: proposta accettata da entrambi = effetto immediato — così l'unica scorciatoia per allentare subito è *parlarne col genitore*, che è il comportamento da insegnare |
| Il registro deve accorgersi di essere imbavagliato (aereo, force-stop, cambio orologio, disinstallazione = eventi) | Accountable2You + evidenza bypass | Elevato a requisito di prodotto: "il registro È il prodotto" |
| Notifica al genitore su bonus/modifiche | Clearspace | Le proposte del genitore mostrano sempre il **confronto col valore attuale** ("−30 min rispetto ad ora") |

## 6. La verifica tecnica (fattibilità, 14/07/2026, era Android 16)

Verificati contro fonti attuali tutti i presupposti dell'architettura. Esito: **tutto fattibile, nessun muro.** In sintesi (dettagli in [architettura.md](architettura.md)):

- **Il regalo:** Android registra la storia d'uso da solo, a livello di sistema → l'app non deve restare sempre viva; si sveglia, rilegge la storia, sincronizza. Il design "testimone" si sposa con la piattaforma.
- **Pedaggio 1:** su Android 15/16 gli APK installati dal browser hanno il permesso "accesso ai dati di utilizzo" bloccato di default (*restricted settings*); lo sblocco esiste sempre ma è nascosto → l'onboarding deve guidarlo passo-passo.
- **Pedaggio 2:** alcune marche (Xiaomi, Samsung, OnePlus, Huawei) uccidono le app in sottofondo oltre le regole di Android. I telefoni della famiglia sono tutti Motorola → fascia moderata, corazza media. Il segnale affidabile resta comunque il **silenzio dei battiti visto dal server**.
- **Scelta confermata:** niente Device Admin per impedire la disinstallazione — Android tratta "sideload + device admin" come pattern da stalkerware, e comunque contraddice la filosofia. Disinstallazione = silenzio nel registro = conversazione.
- **Nota 2027:** la "developer verification" di Google renderà necessario registrarsi (tier gratuito fino a 20 dispositivi) perché gli APK si installino sui telefoni normali anche in sideload. Fino a fine 2026 in Italia nessun impatto.

## 7. Fonti principali

- Stattin & Kerr 2000 (monitoring = disclosure volontaria): https://srcd.onlinelibrary.wiley.com/doi/10.1111/1467-8624.00210
- UCF/Ghosh, recensioni dei minori e correlazione rischi: https://www.ucf.edu/news/apps-keep-children-safe-online-may-counterproductive/
- "Finestra, non vetrata" (Value Sensitive Design con teen): https://journals.sagepub.com/doi/full/10.1177/0743558419884692
- Joint Family Oversight (genitori e figli come pari): https://arxiv.org/html/2204.07749v2
- Studio PNAS su one sec (pausa + continua): https://www.pnas.org/doi/abs/10.1073/pnas.2213114120
- Beeminder, akrasia horizon: https://blog.beeminder.com/akrasia/
- stickK, modello referee: https://www.stickk.com/faq/referees/Commitment+Contracts
- Patti Digitali: https://www.economyup.it/innovazione/patti-digitali-cosi-i-genitori-si-auto-aiutano-per-gestire-il-rapporto-dei-figli-con-la-tecnologia/
- Life360 "Bubbles": https://techcrunch.com/2020/10/12/family-tracking-app-life360-launches-bubbles-a-location-sharing-feature-inspired-by-teens-on-tiktok/
- Restricted settings Android 15: https://www.androidauthority.com/android-15-restricted-settings-sideloading-3481098/
- Battery killer per marca: https://dontkillmyapp.com/
