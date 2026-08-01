# Pactum — Tavola rotonda di design (31/07/2026)

Tre esperti: Giulia (visual/UI), Marco (prodotto famiglia/parental control), Sara (UX comportamentale).
Tre round: aperture indipendenti, confronto diretto, sintesi decisionale.

---

# PARTE 1 — LA DISCUSSIONE

### Giulia — Visual/UI Designer — apertura

## 1. LA MIA DIAGNOSI

**a) Tutto è una `Card`, quindi niente è importante.** In `FinestraScreen.kt` il `BannerSilenzio`, la `SchedaRegola`, la `SchedaBonus`, `RigaSforamento`, `RigaManomissione` e `RigaStorico` sono tutte Card con elevazione identica, dentro un `LazyColumn` con `spacedBy(12.dp)` uniforme. "Regola creata 6 giorni fa" pesa quanto "silenzio dell'app". Il genitore deve *leggere* per capire come sta andando: nessuna gerarchia, solo un elenco.

**b) Il semaforo — l'elemento più carico di significato — è un dettaglio.** In `Semaforo()` i quadretti sono 26.dp, sotto un `bodyLarge`. Il testo "oggi" aggiunge una terza riga solo all'ultima colonna, quindi la `Row` si allunga e l'allineamento salta. Il bordo di oggi usa `colorScheme.onSurface`: un rettangolo **nero** su verde saturo. E `GrigioSemaforo` (0xFF9E9E9E) su superficie chiara fa **2,7:1** — sotto il 3:1 minimo perfino per un oggetto grafico non testuale: il "non lo so" sembra un difetto di rendering, non un'informazione.

**c) Tre rossi diversi per tre cose diverse.** `RossoAllarme` (banner silenzio), `RossoSemaforo`/`RossoOltreLimite` (quadretto e minuti in `RigaUso`), `errorContainer` M3 (banner "Impossibile aggiornare"). Il genitore non deve confondere "il figlio ha sforato" con "il mio telefono non ha rete". In più `RossoOltreLimite` **come testo** su fondo chiaro fa 4,2:1, sotto il 4,5:1 richiesto.

**d) I numeri di tempo non hanno forma.** In `RigaUso` di `TempoScreen.kt` (e nelle righe di `OggiScreen.kt`) il tempo è testo a destra: dodici righe di "2 h 10 min / 48 min / 35 min" sono indistinguibili a colpo d'occhio. Il dato centrale del prodotto è l'elemento visivamente più debole.

**e) Le due app sono parenti per caso, non per sistema.** `VerdeCalmo` del banner "in contatto" nel genitore è **0xFF1F6E5C: identico** al `primary` dell'app figlio. Il colore d'identità di uno è già il semantico "tutto ok" dell'altra. E 6 voci in `NavigationBar` col set generico `Home/DateRange/Edit/Star/Email`: la barra è la prima cosa che si vede e dice "template".

## 2. IL PRINCIPIO CHE DIFENDO

**La finestra è un referto, non un feed:** ogni schermata deve rispondere a "come sta andando il patto?" in tre secondi *senza leggere*; il testo serve per il dettaglio, mai per il verdetto. Perché il prodotto è la conversazione: una conversazione parte da un colpo d'occhio condiviso ("guarda"), non da una lista da spulciare.

## 3. LE MIE PROPOSTE

**P1 — `PactumDesign.kt` gemello nelle due app.** `Spazi` (4/8/12/16/24/32) con regola: dentro una card solo 4-12, tra i blocchi solo 16-24. `MaterialTheme.shapes` a 8/14/20 (invece di 4/12/16). Sostituisce i 6/12/16 sparsi a mano in ogni file.

**P2 — Quattro segnali, un solo rosso di patto.** Enum `Segnale`: RISPETTATO 0xFF2E7D32, SFORATO 0xFFC62828, MUTO grigio-blu 0xFF5F6B7A, TECNICO = `errorContainer` M3. Regola dura: il rosso del patto non compare **mai** per un problema di rete. Due token separati per riempimento e testo, perché il significato è fisso ma il valore no.

**P3 — Il semaforo diventa il protagonista della `SchedaRegola`.** Quadretti 32.dp, `RoundedCornerShape(10.dp)`, gap 8; il numero del giorno **dentro** il quadretto (una riga sola, allineamento salvo). "Oggi" non è testo: è un anello — `Box` di 40.dp con `border(2.dp, colorScheme.primary, RoundedCornerShape(14.dp))`, così usa blu/verde dell'app e non il nero. MUTO diventa quadretto **vuoto** (`surfaceVariant` + `border(1.dp, outline)`): un "non lo so" deve sembrare vuoto, non spento. Sopra la striscia, `titleMedium`: **"6 giorni su 8 rispettati"** — la frase che il genitore ripete a voce.

**P4 — Il banner silenzio diventa asimmetrico.** *In contatto*: niente Card piena — pallino 8.dp verde + `labelLarge` "ultimo battito 14:32". *Silenzio*: allora sì, banner pieno con icona 24.dp, `titleMedium` + `bodyMedium`. La normalità non grida, l'anomalia occupa spazio. Oggi il primo schermo è un rettangolo verde grande quanto un allarme.

**P5 — Barre senza librerie.** In `RigaUso` e in `OggiScreen`: `Box` alto 6.dp con dentro `Box(Modifier.fillMaxWidth(frazione))`, `clip(RoundedCornerShape(3.dp))`. Scala relativa all'app più usata del giorno. Con `limite != null`: riempimento su `minuti/limite`, eccedenza in SFORATO, tacca verticale 2.dp sul limite. ~20 righe di Compose, zero dipendenze.

**P6 — Un solo numero grande per schermata.** `TotaleGiorno` oggi mescola etichetta e valore in `headlineSmall`. Separare: `labelMedium` "Oggi" + `displaySmall` SemiBold "4 h 12 min". Idem nella Card di `OggiScreen` (l'etichetta `oggi_totale` c'è già).

**P7 — `Type.kt` che tocca 4 stili.** `displaySmall`/`headlineSmall` SemiBold con `letterSpacing = (-0.5).sp`; `titleMedium` SemiBold (oggi `TitoloSezione` è indistinguibile dal `bodyLarge` delle card); `labelMedium` a `0.6.sp` e maiuscolo per i sopra-titoli tipo "Proposta del genitore". Font di sistema: nessun file, nessun peso.

**P8 — Barra a 5 voci + icone nostre.** Impostazioni esce dalla `NavigationBar` del genitore e va nella `TopAppBar` come `IconButton` (come fa già `OggiScreen` con `onApriImpostazioni`) → 5 voci. `Badge` sui non letti su Notifiche (il dato è già in `NotificheViewModel`). Le icone giuste (Visibility, Schedule, Handshake) non sono nel set core: **le disegniamo**, come è già stato fatto con `ic_notifica_binocolo.xml` — 5 vector XML, niente `material-icons-extended`. Il binocolo diventa l'icona della Finestra.

## 4. DOVE MI ASPETTO DI SCONTRARMI

**Sul semaforo grande:** mi diranno che ingigantire verde/rosso gamifica il patto e mette pressione. Rispondo: sta in `FinestraScreen`, l'app del genitore — nel figlio non lo metterei mai in Oggi. E un dato illeggibile non è gentile, è vago: la vaghezza produce più sospetto, non meno.

**Sulla navigazione:** chi ragiona per flussi vorrà rifare l'architettura informativa (fondere Notifiche dentro Finestra). Sono d'accordo che 6 voci siano un sintomo, ma l'IA si rifà una volta sola e costa; barra a 5 + badge è il 20% del lavoro per l'80% della resa.

**Sul Material You:** qualcuno proporrà `dynamicColorScheme` per sembrare "nativi". Mi oppongo: le due app diventerebbero indistinguibili tra loro e i contenitori attorno al semaforo cambierebbero col wallpaper. Qui il colore è semantica, non decorazione.

**Sul contrasto:** il commento in `FinestraScreen.kt` dice "il verde È verde". D'accordo sul significato, non sul valore: come riempimento 0xFF43A047 regge, come testo no. Il grigio a 2,7:1 non regge nemmeno come riempimento.

---

### Marco — Esperto di prodotti per famiglie e parental control — apertura

## 1. LA MIA DIAGNOSI

**a) La finestra si apre su una diagnostica di rete, non sul patto.** In `FinestraScreen.kt` il primo elemento è `BannerSilenzio`, una Card a piena larghezza con `RossoAllarme`/`VerdeCalmo` e testo bianco: "In contatto — ultimo battito alle 14:32". Il genitore apre l'app per sapere *com'è andata*, e la prima cosa che legge è lo stato del canale. Peggio: il rosso pieno a tutta larghezza è il colore della colpa, ma nove volte su dieci il silenzio è batteria scarica o wifi.

**b) Sei sezioni di pari peso = nessuna gerarchia.** `TitoloSezione` usa sempre `titleMedium`; `RigaEvento` e `RigaStorico` sono la stessa Card. Risultato: "Regola creata" pesa visivamente quanto "Cambio manuale dell'ora". Con 5 sezioni piatte più bonus, la finestra è lunga 4-5 schermate e non risponde mai alla domanda unica: *ha mantenuto la parola?*

**c) Il semaforo è una pagella.** In `Semaforo()` otto quadretti `VerdeSemaforo`/`RossoSemaforo` col giorno sotto, colori fissi in chiaro e scuro "perché il rosso È rosso". Una fila di rossi non apre una conversazione, chiude un ragazzo. E manca del tutto la lettura che genera dialogo: la serie, il "4 su 5", il miglioramento.

**d) Tempo è una classifica di sorveglianza.** `TempoScreen.kt` riga 199: `sortedByDescending { it.minuti }`. Il primo posto è l'app più usata — spesso senza nessuna regola sopra, quindi fuori dal patto. App con limite e app senza limite stanno mescolate nella stessa lista, stesso peso. E il `RossoOltreLimite` colora i *minuti totali*, non lo scarto: legge "sei colpevole di 47 minuti".

**e) Il genitore può solo chiedere di più.** Le sue azioni disponibili sono Proposte (stringi) e Verdetti (giudica). Non esiste un solo gesto per dire "bravo". Un'app dove l'unica interazione possibile è la richiesta diventa un'app di controllo per costruzione. Simmetrico: nell'app figlio non c'è nessuna schermata che dica *cosa* vede il padre. Senza quella, "finestra non vetrata" è una promessa non verificabile.

## 2. IL PRINCIPIO CHE DIFENDO

**La finestra deve rispondere prima a "com'è andata la parola data", e solo dopo mostrare cosa ha fatto col telefono.** Tutto ciò che precede quella risposta è sorveglianza — e la sorveglianza è ciò che fa disinstallare l'app al secondo litigio.

## 3. LE MIE PROPOSTE

**P1 — `SchedaPatto` al posto d'onore.** Nuovo composable in cima a `ContenutoFinestra`, `headlineMedium`: "Questa settimana 4 regole su 5 mantenute" + riga sotto "3 giorni di fila". Calcolabile sul client dai `QuadrettoSemaforo` che già arrivano. Il `BannerSilenzio` scende a chip grigio accanto a `finestra_aggiornata_alle`; torna rosso pieno solo oltre le 24h di silenzio.

**P2 — Semaforo → "striscia della parola data".** Stessi 8 quadretti, semantica diversa: verde pieno = mantenuta, **contorno** ambra = sforata (non rosso pieno), grigio = nessun dato. Sotto la `Row`, un `Text`: "6 giorni su 8". Un numero si migliora; una fila di rossi si subisce.

**P3 — Tre livelli invece di sei sezioni.** (a) *Il patto* — SchedaPatto + regole; (b) *Da guardare insieme* — sforamenti e manomissioni fusi in una lista sola per data, max 5; (c) *Storia* — storico dietro un `TextButton` che espande (`mutableStateOf(false)`). Da 5 schermate di scroll a una.

**P4 — Tempo diviso in due blocchi.** "Dentro il patto" (le voci con `limite != null`, ordinate per vicinanza al limite) e "Il resto della giornata" (le altre, `onSurfaceVariant`, nessun rosso). Stessi dati, ma il genitore legge prima le promesse e poi il contesto — e sparisce il podio dell'app più usata.

**P5 — Il rosso sullo scarto, non sul totale.** In `RigaUso`, minuti in colore normale + etichetta "20 min oltre" accanto al badge limite. Colpa → differenza da una promessa che si è dato lui.

**P6 — "Cosa vede tuo padre" nell'app figlio.** Card in `OggiScreen` che apre l'elenco letterale del contenuto della finestra, più la lista di ciò che resta fuori (messaggi, contenuti, posizione, tempo reale). Costo: un Composable e ~10 stringhe. È il pezzo che rende verificabile la frase su cui è costruito il prodotto.

**P7 — Un gesto non-poliziesco per il genitore.** Pulsante in fondo alla SchedaPatto che manda al figlio un riconoscimento breve, sul canale notifiche esistente. Senza, l'app resta a senso unico.

**P8 — Palette semantica nei due `Theme.kt`.** Quattro token (mantenuta / sforata / nessun dato / concordata) al posto dei `Color` hardcoded sparsi (`VerdeSemaforo`, `RossoAllarme`, `RossoOltreLimite`). Oggi lo stesso `0xFFE53935` significa tre cose diverse, e `errorContainer` è un quarto allarme identico.

## 4. DOVE MI ASPETTO DI SCONTRARMI

**Sul semaforo, col visual.** Chiederà contrasto e leggibilità; io voglio deliberatamente attenuare il rosso. Il contorno ambra è visivamente peggiore e prodottualmente giusto.

**Sulla SchedaPatto, con l'ingegnere.** Dirà che il contratto manda dati grezzi, non un giudizio sintetico. Rispondo: si calcola sul client, e se serve entra nel contratto — è la funzione principale dell'app, non un extra.

**Sulla neutralità.** Qualcuno dirà che "4 su 5" è un giudizio e che il concept dice *registrare e raccontare*. Rispondo: raccontare senza sintetizzare scarica la sintesi sul genitore, che la farà peggio e a caldo. La frase neutra protegge il figlio.

**Su Tempo.** Il padre di Andrea ha chiesto lui l'allargamento del 15/07 e probabilmente vuole la classifica. Non tolgo un dato: cambio solo l'ordine e il peso.

---

### Sara — UX Designer / design comportamentale — apertura

## 1. LA MIA DIAGNOSI

**1. Il figlio non ha memoria di sé; ce l'ha il genitore.** `GET /api/patto` (contratto-api.md §52-68) consegna regole, bonus, proposte, dichiarazioni: zero storico. Intanto il genitore ha `Semaforo()` — otto quadretti verde/rosso — e la striscia di otto giorni in `SchedaBonus`. Chi deve imparare l'autogestione non ha uno specchio; chi osserva ha l'archivio. È rovesciato.

**2. `OggiScreen` è un contatore, non un patto.** Card "Tempo nelle app oggi", poi righe app con `riga.pacchetto` sotto (`com.instagram.android`, mostrato al proprietario del telefono). Le regole che si è dato non compaiono da nessuna parte. Apre l'app del suo patto e trova un Digital Wellbeing più brutto.

**3. Il feedback esiste solo in negativo.** Le uniche volte in cui l'app parla per prima sono `notifica_sforamento_limite_titolo` ("Limite superato") e `notifica_sforamento_fascia_titolo` ("Fascia non rispettata"). Giornata dentro le regole: silenzio totale. E lo stato vuoto recita `oggi_vuoto` = "Nessun uso registrato per oggi." Un comportamento che non riceve mai riscontro non si consolida.

**4. Le parole.** "testimone, non carceriere", "creduto sulla parola", "è il tuo patto" sono le cose migliori del prodotto. Poi il figlio legge "Limite superato", "Tetto superato" (`bonus_tetto_superato`), e il genitore ha le sezioni "Sforamenti" e "Manomissioni": lessico da antifurto. La sproporzione è anche cromatica — `RossoSemaforo` e `RossoOltreLimite` esistono, un verde che celebra nell'app del figlio non esiste (`VerdeSemaforo` vive solo dal genitore).

**5. Il bonus è un modulo da sportello.** `BonusScreen`: paragrafo, card residui, "Su quale limite?", lista di `RadioButton`, campo "Motivo (facoltativo)", tre `FilledTonalButton`. Quattro tocchi e una giustificazione scritta per prendersi 15 minuti che il patto già gli concede.

## 2. IL PRINCIPIO CHE DIFENDO

**L'app del figlio dev'essere lo specchio in cui vede sé stesso mantenere la parola, non il cruscotto dei suoi consumi.** Perché il concept stesso dice che il successo è "mantiene la parola che si è dato": quel dato non esiste da nessun'altra parte, e è l'unica ragione non obbligata per cui un sedicenne riapre l'app.

## 3. LE MIE PROPOSTE

**P1 — `OggiScreen` diventa "come sta andando il patto".** Una riga per regola attiva con `LinearProgressIndicator`: minuti usati sul limite **efficace** (`SentinellaPatto` lo calcola già = limite + bonus), le fasce come "mancano 3 h alle 23:00". L'elenco per app scende sotto, come sezione secondaria, e sparisce `riga.pacchetto`.

**P2 — La striscia dei 7 giorni anche dal figlio, calcolata in locale, zero modifiche al contratto.** `UsageStatsReader.usoDelGiorno(giorno)` accetta qualsiasi data e Android conserva ~7 giorni di eventi: componente condiviso `StrisciaGiorni` (Row di Box 26.dp, `RoundedCornerShape`) identico a `Semaforo()`. Padre e figlio guardano lo stesso oggetto: è letteralmente la definizione di patto.

**P3 — Il numero per cui vale la pena aprire l'app.** In `headlineLarge` sopra tutto: "7 giorni di seguito dentro le tue regole". Regola anti-fragilità obbligatoria: quando si rompe **non riparte da zero da solo** — accanto resta "il tuo record: 12". Una serie che azzera è un ottimo motivo per disinstallare.

**P4 — La notifica di chiusura giornata (oggi non esiste).** Una sola, la sera, ora scelta dal figlio nelle sue Impostazioni: "Oggi dentro tutte le tue regole. Nono giorno." Nei giorni storti la versione onesta: "Oggi 40 min oltre su TikTok. Domani riparte." Simmetria: oggi l'app apre bocca solo per dire che hai sbagliato.

**P5 — Stato vuoto che dice esplicitamente che va bene.** `oggi_vuoto` → "Nessuno sforamento oggi. Il registro è pulito." Regola generale: quando va bene si scrive, non si lascia l'assenza di rosso a comunicarlo.

**P6 — Bonus in due tocchi.** I tre pulsanti +5/+15/+30 sotto la barra della regola in `OggiScreen`: la regola è già data dal contesto, la lista di `RadioButton` sparisce. Il motivo diventa facoltativo *dopo* ("aggiungi perché" nella snackbar). E "Tetto superato: oggi restano 0 min" → "Ti sei già dato i 30 minuti di oggi".

**P7 — Lessico da diario, non da tribunale.** Figlio: "Limite superato" → "Oggi sei andato oltre". Genitore: `sezione_sforamenti` → "Giorni fuori regola", `sezione_manomissioni` → "Buchi nel registro" (più preciso: `manomissione_silenzio` è un buco, non un dolo). Il concept vuole le conseguenze nella conversazione: se l'app parla già da sentenza, la conversazione parte con la sentenza scritta.

**P8 — Riconoscimento immediato sulla dichiarazione.** In `DichiarazioniScreen` "Ce l'ho fatta" finisce oggi in "In attesa di conferma". La card deve cambiare stato subito (spunta, colore pieno) e far salire "l'hai fatto 6 volte questo mese". L'arbitro convalida verso il genitore; il riconoscimento verso il figlio non può aspettare un adulto.

## 4. DOVE MI SCONTRERÒ

**Sulla "gamification".** Mi diranno che serie e numeroni tradiscono la sobrietà del prodotto, e che concept.md riga 71 parcheggia esplicitamente la motivazione del figlio. Ribatto: l'app è già motivazionale, solo col segno sbagliato — motiva per paura del rosso. Accetto il confine stretto: una sola metrica, niente punti, badge, confronti, valute.

**Sulla simmetria tra le due app.** Chi difende un design system unico vorrà stessi componenti e stesso tono. Condivido `StrisciaGiorni`; non il tono. Il genitore è un cruscotto da consultare, il figlio è uno specchio da abitare.

**Su P2, con l'ingegneria.** Una storia locale può divergere dal semaforo del server. Rischio vero: due numeri diversi sullo stesso patto uccidono la fiducia. Posizione: la striscia locale è specchio, non registro; se diverge vince il server. Se non basta, chiedo l'unica modifica al contratto che difendo: `semaforo` (già calcolato per `/api/finestra`) anche in `GET /api/patto`.

**Sul lessico**, mi diranno che ammorbidisco la verità. No: cambia il titolo, non il fatto registrato né la notifica.

=====================
## ROUND 2 — CONFRONTO
=====================

### Giulia — Visual/UI Designer — replica

## 1. DOVE HANNO RAGIONE

**Marco, il rosso sullo scarto mi cambia il P2.** Avevo previsto due token di rosso (riempimento e testo) proprio per salvare `RossoOltreLimite` come testo in `RigaUso`. Hai ragione: quel numero non deve essere rosso affatto. Cancello il token "rosso testo": i minuti restano `onSurface`, accanto compare un chip "20 min oltre". Risolvo un problema di contrasto togliendo l'elemento invece di correggerlo.

**Marco, sul banner vado più in là di quanto avevo scritto.** Il mio P4 teneva comunque una riga a sé (pallino verde + "ultimo battito 14:32"). Il tuo chip grigio accanto a `finestra_aggiornata_alle` è meglio: una riga invece di due, e lo stato del canale sta accanto all'età del dato, che è la stessa informazione. Adottato.

**Sara, la striscia condivisa mi cambia il P1.** Avevo scritto "`PactumDesign.kt` gemello": due copie, che divergono alla prima modifica. Se padre e figlio devono guardare *lo stesso oggetto*, serve un modulo Gradle `core-design` incluso da entrambe le app in `settings.gradle`, con dentro `Spazi`, `Forme`, `Segnale`, `Tipografia` e `StrisciaGiorni`. Non gemello: unico. E hai ragione sull'asimmetria cromatica — `VerdeSemaforo` oggi vive solo dal genitore: un verde che dice "mantenuta" deve esistere anche dal figlio.

## 2. DOVE NON SONO D'ACCORDO

**Marco, P2: "contorno ambra = sforata (non rosso pieno)".** Due problemi. Un bordo ambra 2.dp su `surface` chiara (#FFFBFE) fa circa 1,8:1: a mezzo metro quel quadretto sparisce. Peggio: tu usi il *vuoto* per dire "sforata", io lo uso per dire "nessun dato". Nella stessa fila di otto avremmo due stati opposti — "ha sbagliato" e "non lo so" — distinguibili solo dalla tinta di un bordo. Non è delicatezza, è ambiguità, ed è esattamente ciò che il registro non può permettersi. Controproposta che ti dà quello che vuoi: quadretto **pieno**, temperatura diversa — mattone desaturato `0xFFB3543F` invece di `0xFFE53935`. Pieno = fatto, vuoto = assenza: una dimensione, un significato.

**Marco, P1: `headlineMedium` "Questa settimana 4 regole su 5 mantenute".** 28sp: su 360dp quella frase va a tre righe. Un paragrafo grande non è un colpo d'occhio, è lettura lenta. Il corpo grande serve alle stringhe corte: `displaySmall` "4 su 5" + `bodyMedium` "regole mantenute questa settimana".

**Sara, P3: `headlineLarge` "7 giorni di seguito" con accanto "il tuo record: 12".** In `OggiScreen` c'è già un numero grande (`testoDurata`, `headlineMedium`): due numeri grandi sullo stesso schermo = nessun numero grande. E il record scritto accanto alla serie è un confronto visivo permanente: il giorno in cui la serie fa 1 e il record dice 12, quell'1 *sembra* piccolo — è tipografia che dà torto al ragazzo. Accetto la serie come unico numero grande (il totale minuti scende a `labelLarge` sopra l'elenco app), ma il record va in `labelMedium` `onSurfaceVariant`, una riga sotto, senza colore.

**Sara, "condivido StrisciaGiorni; non il tono".** Il tono non passa dai componenti. Componenti diversi = due app che non sembrano lo stesso prodotto, e la promessa "guardiamo la stessa cosa" cade. Il tono lo fanno il colore (blu/verde), la densità (padding card 16.dp dal genitore, 20.dp dal figlio; spacing 12 contro 16) e le parole. Stessi mattoni, respiro diverso.

## 3. LA TENSIONE VERA

Non è "quanto rosso": è **chi decide il peso visivo di un errore**. Voi lo volete attenuare per non umiliare, io lo voglio leggibile perché un dato vago produce più sospetto di un dato chiaro. Non si media: o il quadretto si legge a mezzo metro o non si legge.

Decisione da prendere e non più discutere: **un solo rosso, in un solo posto.** Il mattone `0xFFB3543F`, pieno, esiste **solo** dentro la striscia degli otto giorni, identica nelle due app. Sparisce da tutto il resto: niente minuti rossi in `RigaUso`, niente `RossoAllarme` sul banner silenzio, niente rosso nelle notifiche di sforamento. Gli errori tecnici restano su `errorContainer` e non condividono mai la tinta del patto. Io ottengo la leggibilità, voi l'attenuazione: un colore che compare in un punto solo smette di essere il tono dell'app e diventa un segno. Il resto dell'ammorbidimento lo fanno la frase sotto la striscia ("6 giorni su 8 rispettati") e la dimensione — protagonista in `FinestraScreen`, secondaria dal figlio.

Corollario da fissare adesso: **un eroe per schermata**. Finestra: striscia + frase. Oggi (figlio): la serie. Tempo: il totale del giorno.

## 4. LA MIA POSIZIONE AGGIORNATA

Difendo: modulo `core-design` unico; quadretti 32.dp col numero del giorno **dentro** e anello `primary` per oggi (il testo "oggi" sparisce, l'allineamento si salva); barre in `Box` senza librerie in `RigaUso` e `OggiScreen`; un numero grande per schermata; `Type.kt` su quattro stili; barra a 5 voci con Impostazioni nella `TopAppBar` e icone disegnate da noi.

Lascio cadere: il token "rosso testo" (Marco); il banner verde come riga autonoma (Marco); la duplicazione "gemella" del design system (Sara); e la mia riserva sul numero grande dal figlio — purché sia la serie, non i minuti.

---

### Marco — Esperto di prodotti per famiglie e parental control — replica

## 1. DOVE HANNO RAGIONE

**Giulia, sul rosso mi hai corretto un errore di diagnosi.** Io avevo scritto "il `BannerSilenzio` scende a chip grigio": stavo curando il sintomo. Il problema vero è il tuo punto (c): `RossoAllarme`, `RossoSemaforo`, `RossoOltreLimite` e `errorContainer` dicono quattro cose diverse con lo stesso segnale. La tua regola — *"il rosso del patto non compare mai per un problema di rete"* — è più forte della mia proposta. **Ritiro la mia P8** e la faccio confluire nella tua P2: quattro token, non i miei quattro (il mio "concordata" è un'etichetta, non un segnale). E prendo la tua P4 come legge generale, non solo per il banner: *"la normalità non grida"*. Conseguenza concreta che aggiungo: le sezioni vuote di `ContenutoFinestra` (`sforamenti_vuoto`, `manomissioni_vuoto`) non devono nemmeno esistere come `TestoVuoto`; collassano in una riga sola nella scheda in cima.

**Sara, mi hai fatto togliere una cosa che avevo scritto io.** Nella mia P1 c'era *"3 giorni di fila"* nella `SchedaPatto` **del genitore**. La tua regola anti-fragilità mi ha mostrato il difetto: una serie nell'app di chi osserva diventa un'arma verbale il giorno che si rompe ("hai perso la serie"). La serie è dell'atleta, non dell'allenatore. **La tolgo dal genitore**, resta solo il rapporto ("4 regole su 5 questa settimana"); serie + record vivono solo da te, col tuo vincolo. E il tuo punto 3 riqualifica la mia P7: non manca solo un gesto positivo al genitore, manca *qualsiasi* messaggio non-allarme verso di lui. Il digest giornaliero (concept.md riga 56) è l'unico candidato: va progettato come canale calmo, non come bollettino.

## 2. DOVE NON SONO D'ACCORDO

**Giulia, P3: "Il semaforo diventa il protagonista della `SchedaRegola`", quadretti 32.dp con anello.** No. Il protagonismo va dato una volta sola, in cima, sull'aggregato. Con cinque `SchedaRegola` ognuna con la sua striscia ingrandita, il genitore scorre **un muro di pagelle**: è esattamente la "fila di rossi" che dicevo, moltiplicata per cinque. Proposta netta: striscia grande (32.dp, il tuo anello, il tuo MUTO vuoto) **una sola**, in `SchedaPatto`, dove un giorno è verde se *tutte* le regole erano verdi; dentro `SchedaRegola` la striscia resta a 26.dp e secondaria.

**Giulia, P8: "Impostazioni esce dalla `NavigationBar` → 5 voci".** Stai contando le voci, non leggendole. Dopo il tuo taglio restano Finestra / Tempo / Proposte / Verdetti / Notifiche: **tre schede su cinque sono il commissariato**. Il tuo stesso argomento ("l'IA si rifà una volta sola e costa") è il motivo per non spendere il rifacimento sulla voce sbagliata. Io vado a 4: **Finestra · Tempo · Il tuo turno · Impostazioni**, dove "Il tuo turno" fonde Proposte e Verdetti (sono la stessa cosa: risposte che il genitore deve dare), e le notifiche diventano il `Badge` sulla Finestra.

**Sara, P2: striscia locale nel figlio "calcolata in locale, zero modifiche al contratto".** Rischio concreto, ed è il modo in cui muore Family Link: due numeri sullo stesso patto. `UsageStatsReader` misura sul telefono, il semaforo lo calcola il server nel fuso del patto (contratto §150, `PACTUM_TIMEZONE`); divergeranno, e la famiglia litigherà sul numero invece che sul comportamento. "Se diverge vince il server" è una regola che il ragazzo non può verificare a occhio. Faccio mia la tua richiesta subordinata e la promuovo a condizione: `semaforo` dentro `GET /api/patto`, poi la striscia. Un campo, zero ambiguità.

## 3. LA TENSIONE VERA

Non è "quanto grande" né "quanto rosso". È: **la sintesi in cima è un giudizio — chi ha diritto di leggerlo per primo?** Giulia e io la vogliamo grande; Sara ricorda che concept.md dice *registrare e raccontare*, non sentenziare. Metterla piccola sarebbe il peggio: un verdetto illeggibile.

Decisione, non compromesso: **la frase-sintesi è una sola, la calcola il server, ed è testualmente identica nelle due app**, in cima a `FinestraScreen` e in cima a `OggiScreen`. Da lì il criterio operativo: *si scrive solo ciò che il figlio può leggere su sé stesso senza sentirsi umiliato*. Se una frase non supera questo test, non entra nemmeno nell'app del genitore. Generalizzata: **niente esiste nella finestra che il figlio non veda verbatim e nello stesso momento**. Così la mia P6 ("cosa vede tuo padre") smette di essere una schermata-elenco e diventa una proprietà della struttura — e "finestra, non vetrata" diventa verificabile invece che promessa.

## 4. LA MIA POSIZIONE AGGIORNATA

**Difendo:** P1 senza la serie; P3 (tre livelli: patto / da guardare insieme / storia dietro `TextButton`); P4 (`TempoScreen` in "Dentro il patto" e "Il resto della giornata", via il `sortedByDescending { it.minuti }` come primo criterio); P5 (rosso sullo scarto, non sul totale in `RigaUso`); P7, che alzo a priorità alta.

**Lascio cadere:** la mia P8 (assorbita da Giulia P2); l'**ambra** — concedo la tinta di Giulia, tengo la **forma**: contorno, mai riempimento pieno; la P6 come schermata separata.

---

### Sara — UX Designer / design comportamentale — replica

## 1. DOVE HANNO RAGIONE

**Marco, la tua P6 mi ha cambiato l'ordine delle priorità.** Io avevo detto: il figlio riapre l'app per lo specchio. È vero al terzo mese, non al primo giorno. Il primo giorno un sedicenne apre l'app per un motivo solo: *vedere cosa vedono di lui*. Quindi "Cosa vede tuo padre" non è una card in fondo a `OggiScreen`: è uno schermo dell'`OnboardingScreen` subito dopo i permessi, più una voce permanente. La curiosità sulla propria esposizione batte la virtù. Sposto la mia P1: prima "cosa è visibile di me", poi lo specchio.

**Marco, P5 (rosso sullo scarto, non sul totale): la porto anche nell'app del figlio.** Colorare `testoDurata(minuti)` con `RossoOltreLimite` dice "sei colpevole di 2 h 10", non "di 20 minuti". Mi era sfuggito.

**Giulia, la tua P4 è il principio che avrei dovuto scrivere io:** "la normalità non grida, l'anomalia occupa spazio". Corregge la mia P5: quando va bene non voglio un banner verde speculare al rosso, voglio una riga asciutta. Chiedevo riconoscimento, avrei ottenuto una slot machine. Accetto anche la tua P6, "un solo numero grande per schermata", e lo spendo così: in `OggiScreen` del figlio il numero grande NON è "4 h 12 min" (quello è Digital Wellbeing) — è la serie; `oggi_totale` scende a labelMedium. E il tuo grigio a 2,7:1 è un argomento di prodotto, non di contrasto: un giorno senza dati deve sembrare *vuoto*, non guasto.

## 2. DOVE NON SONO D'ACCORDO

**Giulia, P3 — "sta in `FinestraScreen`, l'app del genitore — nel figlio non lo metterei mai in Oggi".** Qui ti fermo. Porti a 32.dp l'unico oggetto che riassume la condotta del ragazzo e lo lasci visibile solo a chi giudica. Il rischio è preciso: il figlio *sa* che esiste una pagella che non può guardare. Il concept promette "il figlio sa di averla, sempre" e "una finestra, non una vetrata": una metrica visibile solo al genitore è la cosa più vicina alla sorveglianza nascosta che questo design possa produrre. Ingrandisci pure — ma stesso componente, stessa misura, nelle due app.

**Marco, P2 — "contorno ambra = sforata (non rosso pieno)".** No, per due motivi. (1) Collisione: Giulia usa il contorno vuoto per "nessun dato". Con la tua proposta "ho sforato" e "il telefono era spento" diventano lo stesso segno. (2) Il concept dice che il successo è affrontarlo *a viso aperto*: se il figlio dichiara "non ce l'ho fatta" e l'app lo disegna più tenue del silenzio di una batteria scarica, stiamo premiando la reticenza con la visibilità. Attenua il *colore*, non il *peso*: sforato = ambra **pieno**; il contorno resta riservato al vuoto.

**Marco, P1 — "3 giorni di fila" nella SchedaPatto del genitore.** La serie è del figlio. Nel momento in cui il padre la vede smette di essere uno specchio e diventa un obiettivo di cui rendere conto: il giorno in cui si rompe, il ragazzo sa che l'ha vista rompersi anche lui. È il meccanismo che converte motivazione intrinseca in prestazione sorvegliata, e si paga nel momento peggiore, quando sta già andando male. Il conteggio dei fatti sì ("6 giorni su 8", visibile a entrambi). La serie no.

## 3. LA TENSIONE VERA

Non è "sobrio vs motivazionale". È: **chi possiede il numero, e l'altro lo vede?** Giulia vuole due app gemelle, Marco vuole che il cruscotto sintetizzi, io voglio che lo specchio resti privato. Il compromesso annacquato sarebbe mettere tutto ovunque in tono minore.

Decisione che propongo: **simmetria sui fatti, asimmetria sulle sintesi.**
- I fatti — striscia dei giorni, sforamenti, tempi — sono lo *stesso* componente `StrisciaGiorni`, stessa misura, stessi colori, nelle due app. Ciò che vede il padre, il figlio lo vede identico.
- Le interpretazioni no: **serie e record vivono solo nell'app del figlio e non passano mai dal server** (niente campo `serie` in `/api/finestra`; si calcola in locale da `UsageStatsReader.usoDelGiorno(giorno)`). Al genitore resta il conteggio dei quadretti che sta già guardando.

Test di collaudo: qualunque cosa stia nella finestra del genitore, il figlio deve poterla indicare nella sua app. Se non può, non ci va.

## 4. LA MIA POSIZIONE AGGIORNATA

**Difendo:** `OggiScreen` ricostruita sulle regole — oggi `StatoOggi` ha tre campi (caricamento, righe, minutiTotali) e non sa nemmeno che le regole esistono: il figlio apre il *suo* patto e trova un elenco di pacchetti; la notifica serale di chiusura (P4), l'unico appuntamento che dà un motivo per aprire l'app quando è andata bene; il record che non azzera; bonus in due tocchi; il lessico (P7).

**Lascio cadere:** la modifica al contratto che tenevo come fallback — `semaforo` in `GET /api/patto` non la chiedo più, ed è coerente: se il numero non viaggia, non può finire nella finestra. E taglio da P8 il contatore "l'hai fatto 6 volte questo mese", tengo solo il cambio di stato immediato della card. Un conteggio in più è un punteggio in più, e avevo promesso una metrica sola.

---

# PARTE 2 — DOCUMENTO DI REDESIGN (decisioni operative)

# Pactum — Documento di redesign
**Esito della tavola rotonda Giulia (visual) · Marco (prodotto famiglia) · Sara (UX comportamentale) — decisioni operative**
Design lead: sintesi e arbitrato. Data: 31/07/2026. Base di codice verificata: `C:\Users\andre\OneDrive\Desktop\Pactum`

---

## 1. Le 3 decisioni di fondo

### D1 — Un eroe per schermata, e l'eroe è il patto, non il consumo

**La decisione.** Ogni schermata ha **un solo elemento dominante** e risponde a una sola domanda. Finestra (genitore) e Oggi (figlio): *"come sta andando la parola data?"* → striscia degli 8 giorni + un numero grande. Tempo: *"quanto ha usato il telefono?"* → il totale del giorno. Tutto il resto scende di uno o due livelli tipografici. Nessuna schermata ha due numeri grandi.

**Chi la sosteneva.** Giulia ("un eroe per schermata", P6 "un solo numero grande") e Marco ("la finestra deve rispondere prima a com'è andata la parola data"). Sara l'ha accettata dopo il round 2 spendendo l'eroe del figlio sulla serie, non sui minuti.

**Cosa si scarta e perché.** Si scarta la struttura attuale di `ContenutoFinestra` — sei `TitoloSezione` in `titleMedium` con sotto `Card` tutte uguali dentro un `LazyColumn(spacedBy(12.dp))`. Motivo tecnico, non estetico: con elevazione e spaziatura identiche, "Regola creata 6 giorni fa" (`RigaStorico`) ha lo stesso peso di "Silenzio dell'app" (`BannerSilenzio`), e il genitore deve **leggere** quattro schermate per farsi un'idea. Si scarta anche `headlineMedium` per la frase di sintesi (proposta di Marco): 28sp su 360dp manda "Questa settimana 4 regole su 5 mantenute" a tre righe, cioè lettura lenta, cioè non è un colpo d'occhio.

### D2 — Un solo rosso, in un solo posto

**La decisione.** Il colore dello scivolone esiste **soltanto** dentro i quadretti della striscia degli 8 giorni, in entrambe le app. Sparisce da: `BannerSilenzio` (`RossoAllarme = 0xFFB3261E`), minuti di `RigaUso` (`RossoOltreLimite = 0xFFE53935`), notifiche di sforamento, badge, chip. Il problema **tecnico** (rete assente, dati vecchi) non usa mai un colore del patto e non usa nemmeno `errorContainer`: diventa una riga grigia su `surfaceVariant`, perché dati vecchi non sono un errore, sono un'età.

**Chi la sosteneva.** Giulia, che l'ha formulata come legge nel round 2. Marco ha ritirato la sua P8 per farla confluire qui. Sara l'ha usata per correggere la propria P5 ("chiedevo riconoscimento, avrei ottenuto una slot machine").

**Cosa si scarta e perché.** Si scartano i quattro rossi attuali (`RossoAllarme`, `RossoSemaforo`, `RossoOltreLimite`, `errorContainer`), che oggi dicono quattro cose diverse con lo stesso segnale. Un genitore non deve poter confondere "mio figlio ha sforato" con "il mio telefono non ha campo". E si scarta l'idea di *correggere* il contrasto di `RossoOltreLimite` (4,12:1 come testo su superficie chiara, sotto il minimo di 4,5:1): quel numero non deve essere colorato affatto, quindi il problema si risolve togliendo l'elemento, non ritoccandolo.

### D3 — Simmetria sui fatti, asimmetria sulle sintesi

**La decisione.** I **fatti** (striscia dei giorni, giorni fuori regola, minuti, buchi nel registro) sono lo **stesso componente, con gli stessi numeri, dalla stessa fonte** — il server — in entrambe le app. Le **interpretazioni** (serie di giorni consecutivi, record personale) esistono **solo** nell'app del figlio, si calcolano in locale e non viaggiano mai verso il genitore.
Corollario vincolante per chiunque tocchi il codice: **niente entra nella finestra del genitore se il figlio non lo vede identico nella sua app.**

**Chi la sosteneva.** La formulazione è di Sara; Marco l'ha resa strutturale ("niente esiste nella finestra che il figlio non veda verbatim"); Giulia l'ha resa realizzabile chiedendo un componente unico invece di due copie gemelle.

**Cosa si scarta e perché.** Si scarta la posizione finale di Sara ("`semaforo` in `GET /api/patto` non la chiedo più"): senza quel campo la striscia del figlio andrebbe ricostruita da `UsageStatsReader`, cioè misurata sul telefono nel fuso del telefono, mentre il server la calcola in `PACTUM_TIMEZONE`. Due numeri diversi sullo stesso patto sono il modo esatto in cui muore la fiducia — e "se diverge vince il server" è una regola che un sedicenne non può verificare a occhio. Si scarta anche la posizione di Marco per cui la frase di sintesi *la calcola il server*: non serve un campo nuovo, la frase è una funzione pura della striscia e sta in codice condiviso, quindi è identica per costruzione.

---

## 2. I conflitti risolti

### C1 — Il quadretto "non mantenuta": contorno ambra, o pieno?

**La tensione.** Marco vuole attenuare l'errore (contorno ambra, mai riempimento) per non trasformare la striscia in una pagella. Sara vuole il pieno perché "se il figlio dichiara «non ce l'ho fatta» e l'app lo disegna più tenue del silenzio di una batteria scarica, stiamo premiando la reticenza con la visibilità". Giulia vuole il pieno per leggibilità: un bordo ambra 2.dp su superficie chiara fa circa 1,8:1 e sparisce a mezzo metro; e il vuoto è già occupato dal significato "nessun dato".

**La decisione.** **Pieno**, terracotta, con il numero del giorno dentro. Vincono Sara e Giulia sulla forma. **Vince Marco sull'attenuazione, ma per luminanza, non per forma**: la giornata mantenuta è il quadretto **più contrastato** della striscia, quella fuori regola è il **meno contrastato**.

| stato | chiaro | contrasto vs superficie | scuro | contrasto vs superficie |
|---|---|---|---|---|
| mantenuta | `#1E6B33` | **6,38:1** | `#6FBF73` | **8,26:1** |
| fuori regola | `#C97C62` | **3,12:1** | `#B3543F` | **3,75:1** |
| nessun dato | vuoto + bordo `outline` | 4,43:1 (bordo) | vuoto + bordo | 5,85:1 |

**Perché.** Il rapporto di luminanza tra i due pieni è ~2:1: si distinguono anche in scala di grigi e con daltonismo, quindi il registro resta non ambiguo (requisito di Giulia). Ma nella lettura d'insieme la fila di giorni buoni **domina** e lo scivolone si legge senza urlare (requisito di Marco). Il vuoto resta un significato solo — "non lo so" — quindi cade l'obiezione di Sara sulla collisione. Nessuno dei tre ha ottenuto la sua proposta letterale; tutti e tre hanno ottenuto il loro vincolo.

### C2 — Chi è il protagonista: la striscia dentro ogni regola, o una sola in cima?

**La tensione.** Giulia: quadretti 32.dp dentro ogni `SchedaRegola`, il semaforo diventa il protagonista della card. Marco: no, con cinque regole diventa "un muro di pagelle", il protagonismo va dato una volta sola sull'aggregato. Sara: qualunque sia la misura, deve essere identica nelle due app, altrimenti "il figlio sa che esiste una pagella che non può guardare".

**La decisione.** **Marco sulla gerarchia, Sara sulla simmetria, Giulia sul disegno del singolo quadretto.**
- Una sola striscia grande (quadretti 32.dp, numero dentro, anello `primary` per oggi), in cima, **aggregata**: un giorno è verde se *tutte* le regole attive quel giorno erano verdi, terracotta se almeno una era rossa, vuoto se non c'erano dati.
- Dentro `SchedaRegola` la striscia resta a **20.dp**, senza numeri, senza anello: è un dettaglio, non un verdetto (più piccola dei 26.dp attuali, perché ora c'è una striscia grande sopra che comanda).
- La striscia grande è **lo stesso composable, alla stessa misura**, in `FinestraScreen` (genitore) e in `OggiScreen` (figlio).

**Perché.** Cinque strisce da 32.dp non sono cinque volte più informative: sono cinque volte più lunghe da leggere, e ripetono con l'enfasi massima l'unica informazione che il genitore userà a caldo. E l'argomento di Sara è più forte di quello di Giulia sul punto della visibilità: una metrica riassuntiva visibile solo a chi giudica è la cosa più vicina alla sorveglianza nascosta che questo prodotto possa produrre, e contraddice `concept.md` riga 68 ("non gira di nascosto: il figlio sa di averla, sempre").

### C3 — La striscia nel figlio: locale o dal server?

**La tensione.** Sara: calcolata in locale da `UsageStatsReader.usoDelGiorno(giorno)`, zero modifiche al contratto. Marco: divergenza garantita col semaforo del server (fusi diversi, misure diverse), quindi `semaforo` dentro `GET /api/patto` come condizione. Sara ha poi ritirato la richiesta di modifica al contratto.

**La decisione.** **Vince Marco. `semaforo` entra in `GET /api/patto`**, stessa forma già usata in `GET /api/finestra` (`RegolaFinestra.semaforo: List<QuadrettoSemaforo>`), e la striscia del figlio è quella del server. **La serie e il record restano di Sara**: si calcolano in locale **dalla striscia del server**, non da `UsageStatsReader`, non hanno campo nel contratto, non arrivano mai al genitore.

**Perché.** La striscia è un **fatto** (D3): i fatti hanno una fonte sola. Il costo è basso e il rischio nullo: il calcolo esiste già lato server per la finestra, è lo stesso codice, e i modelli del figlio ignorano i campi sconosciuti per contratto ("tolleranza evolutiva", `ModelliPatto.kt`). Derivare la serie dalla striscia del server invece che da `UsageStatsReader` è un miglioramento sulla proposta originale di Sara: elimina la divergenza mantenendo intatta la privatezza della metrica, perché è un conteggio locale su un dato che il figlio già possiede.

### C4 — La navigazione del genitore: 6, 5 o 4 voci?

**La tensione.** Giulia: 5 voci (Impostazioni esce dalla barra e va nella `TopAppBar`), "il 20% del lavoro per l'80% della resa". Marco: 4 voci, perché dopo il taglio di Giulia restano Finestra / Tempo / Proposte / Verdetti / Notifiche e "tre schede su cinque sono il commissariato".

**La decisione.** **Vince Marco: 4 voci — Finestra · Tempo · Il tuo turno · Impostazioni.** "Il tuo turno" fonde `ProposteScreen` e `VerdettiScreen` (sono la stessa cosa: risposte che il genitore deve dare); le notifiche non lette diventano un `Badge` sulla Finestra; `NotificheScreen` non è più una destinazione ma la lista che si apre da lì.

**Perché.** L'argomento decisivo è di Giulia usato contro Giulia: se l'architettura informativa si rifà una volta sola e costa, non la si spende per togliere una voce e lasciare intatto il problema che l'ha generata. La barra è la prima cosa che si vede e oggi dice, in ordine: guarda · misura · chiedi · giudica · avvisi. Quella sequenza è la promessa dell'app, e non è la promessa di `concept.md`.
*Sequenza realizzativa, non compromesso:* Fascia B porta 6→5 (Impostazioni fuori dalla barra, `Badge` sulle notifiche), Fascia C chiude a 4 con "Il tuo turno". Se la demo arriva prima della Fascia C si mostrano 5 voci; il bersaglio resta 4.

### C5 — Il design system condiviso: modulo Gradle o copia gemella?

**La tensione.** Giulia partiva da `PactumDesign.kt` "gemello" nelle due app, poi ha corretto in un modulo `core-design` incluso da entrambe in `settings.gradle`. Sara condivide i componenti ma non il tono.

**La decisione (correzione tecnica, non arbitrato).** Il modulo Gradle **non è realizzabile così**: `app-figlio` e `app-genitore` sono due build Gradle **separate** (due `settings.gradle.kts`, `rootProject.name` diversi, nessun progetto padre). Si fa con una **cartella sorgente condivisa**:

```kotlin
// in app-figlio/app/build.gradle.kts e app-genitore/app/build.gradle.kts
android {
    sourceSets["main"].kotlin.srcDir("../../core-design/src/main/kotlin")
}
```
Nuova cartella: `C:\Users\andre\OneDrive\Desktop\Pactum\core-design\src\main\kotlin\eu\stgm\pactum\design\`
**Regola dura:** in `core-design` non si entra mai in `R` né in `stringResource`. Le stringhe si passano come parametri (`StrisciaGiorni(giorni, etichettaOggi = stringResource(...))`), altrimenti il codice condiviso si lega alle risorse di una delle due app e smette di essere condiviso.
Sul tono vince Sara nella sostanza e Giulia nel metodo: stessi mattoni, respiro diverso — padding card 16.dp e spacing 12.dp dal genitore, 20.dp e 16.dp dal figlio, tono delle parole diverso, componenti identici.

### C6 — La serie nella scheda del genitore

**La tensione.** Marco l'aveva messa ("3 giorni di fila" in `SchedaPatto`), Sara l'ha attaccata: nel momento in cui il padre la vede smette di essere uno specchio e diventa una prestazione sorvegliata, e si paga proprio nel giorno peggiore. Marco l'ha ritirata da solo.

**La decisione.** **Confermata la posizione di Sara.** Al genitore va **solo il conteggio dei fatti** ("6 su 7 giorni dentro le regole"), che è la lettura ad alta voce della striscia che sta guardando. Serie e record vivono solo nel figlio, e il record non azzera mai: quando la serie si rompe resta scritto "il tuo record: 12" in `labelMedium` `onSurfaceVariant`, una riga sotto, senza colore — **mai** accanto al numero grande, perché un 1 accanto a un 12 è tipografia che dà torto al ragazzo.

---

## 3. Design system

Tutto quanto segue è Material 3 standard: nessuna libreria nuova, nessun font, nessuna immagine, `minSdk 26` invariato.

### 3.1 Palette

**Il problema di partenza, misurato.** Oggi i due `Theme.kt` fanno `lightColorScheme(primary = …)` e basta. Tutto il resto è la **baseline M3, che è viola**: `primaryContainer = #EADDFF`, `secondaryContainer = #E8DEF8`, `tertiary` rosa. Quindi il `FloatingActionButton` di `RegoleScreen`, i chip `Etichetta`/`EtichettaLimite`, i `FilterChip` selezionati di `SelettoreGiorni` e i `FilledTonalButton` del bonus sono **lavanda dentro un'app blu e dentro un'app verde**. È questo, più di ogni altra cosa, che fa sembrare le due app un template. Riempire lo schema è l'intervento a più alto rapporto impatto/rischio dell'intero documento.

#### App GENITORE — chiaro
```kotlin
private val SchemaChiaro = lightColorScheme(
    primary            = Color(0xFF2C5D8F), // blu binocolo — identità        6,66:1
    onPrimary          = Color(0xFFFFFFFF), //                                6,84:1
    primaryContainer   = Color(0xFFD5E3F5), // riempimento barre, chip attivi
    onPrimaryContainer = Color(0xFF10395E), //                                9,12:1
    secondary          = Color(0xFF4C6379),
    onSecondary        = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDDE4EC), // Etichetta / EtichettaLimite
    onSecondaryContainer = Color(0xFF1B2C3A), //                             11,16:1
    tertiary           = Color(0xFF7A5C00), // ocra: "concordata", accenti caldi
    onTertiary         = Color(0xFFFFFFFF),
    tertiaryContainer  = Color(0xFFF3E3C4),
    onTertiaryContainer= Color(0xFF3A2C00), //                               10,78:1
    background         = Color(0xFFFBFCFD),
    onBackground       = Color(0xFF191C1E),
    surface            = Color(0xFFFBFCFD), // neutro freddo, non il rosato M3
    onSurface          = Color(0xFF191C1E), //                               16,67:1
    surfaceVariant     = Color(0xFFE2E7EC), // binario delle barre, quadretto vuoto
    onSurfaceVariant   = Color(0xFF43484D), //                                9,00:1
    outline            = Color(0xFF73787D), // bordo del quadretto "nessun dato" 4,34:1
    outlineVariant     = Color(0xFFC3C8CD), // divisori
    error              = Color(0xFFB3261E), // SOLO validazione form
    onError            = Color(0xFFFFFFFF),
    errorContainer     = Color(0xFFF9DEDC),
    onErrorContainer   = Color(0xFF410E0B),
)
```
Se la versione di Material3 in uso espone i ruoli `surfaceContainer*`: `surfaceContainerLowest #FFFFFF`, `Low #F5F7FA`, `#EFF2F6`, `High #E9EDF2`, `Highest #E3E8EE`.

#### App GENITORE — scuro
```kotlin
primary #9CC7F2 (10,45:1) · onPrimary #123A5E (6,62:1) · primaryContainer #24486D ·
onPrimaryContainer #D5E3F5 (7,26:1) · secondary #B3C4D4 · secondaryContainer #33414E ·
onSecondaryContainer #DDE4EC · tertiary #E7C77A · tertiaryContainer #4A3A00 ·
background/surface #101418 · onSurface #E2E7EC (14,86:1) · surfaceVariant #42484E ·
onSurfaceVariant #C3C8CD (10,98:1) · outline #8D9299 (5,91:1) · outlineVariant #42484E ·
error #F2B8B5 · errorContainer #601410 · onErrorContainer #F9DEDC
surfaceContainer*: #0B0E11 / #171B1F / #1B1F23 / #252A2F / #303539
```

#### App FIGLIO — chiaro
```kotlin
primary #1F6E5C (5,97:1) · onPrimary #FFFFFF (6,10:1) · primaryContainer #CFE9E1 ·
onPrimaryContainer #06382D (10,18:1) · secondary #4C635C · secondaryContainer #DCE9E4 ·
onSecondaryContainer #17332B (10,89:1) · tertiary #7A5C00 · tertiaryContainer #F3E3C4 ·
onTertiaryContainer #3A2C00 · background/surface #FBFDFC · onSurface #181D1B (16,71:1) ·
surfaceVariant #E1E7E4 · onSurfaceVariant #414944 (9,09:1) · outline #717973 (4,39:1) ·
outlineVariant #C1C9C4 · error #B3261E · errorContainer #F9DEDC
surfaceContainer*: #FFFFFF / #F4F8F6 / #EEF3F1 / #E8EEEB / #E2E9E6
```

#### App FIGLIO — scuro
```kotlin
primary #7FD3BD (10,51:1) · onPrimary #00382C (7,46:1) · primaryContainer #1C5245 ·
onPrimaryContainer #CFE9E1 (7,00:1) · secondary #B1CCC3 · secondaryContainer #32493F ·
tertiary #E7C77A · background/surface #0F1513 · onSurface #E1E7E4 (14,73:1) ·
surfaceVariant #414944 · onSurfaceVariant #C1C9C4 (10,92:1) · outline #8B938D (5,85:1)
surfaceContainer*: #0A0F0E / #161B19 / #1A201E / #242A28 / #2E3533
```

#### Palette semantica del patto — identica nelle due app, in `core-design`
Sono gli unici colori **fuori** da `colorScheme`, perché il loro significato non dipende dal ruolo Material ma dal patto.

```kotlin
// core-design/…/design/Segnali.kt
enum class Segnale { MANTENUTA, FUORI_REGOLA, NESSUN_DATO }

object Patto {
    val MantenutaChiaro   = Color(0xFF1E6B33)  // pieno; numero bianco (6,55:1)
    val MantenutaScuro    = Color(0xFF6FBF73)  // pieno; numero ink   (8,26:1)
    val FuoriRegolaChiaro = Color(0xFFC97C62)  // pieno; numero ink   (5,34:1)
    val FuoriRegolaScuro  = Color(0xFFB3543F)  // pieno; numero bianco(4,93:1)
    val SilenzioChiaro    = Color(0xFF4C5A69)  // canale muto: grigio-blu, MAI rosso (6,87:1)
    val SilenzioScuro     = Color(0xFF9AA7B4)  //                                    (7,54:1)
    // NESSUN_DATO non ha colore: è surfaceVariant + border(1.dp, outline).
}
```

**Le tre leggi del colore, da non discutere più.**
1. `MantenutaChiaro`/`FuoriRegolaChiaro` (e i gemelli scuri) compaiono **solo** dentro `StrisciaGiorni`. Da nessun'altra parte: né minuti, né badge, né notifiche, né bordi di card.
2. Il **silenzio del canale** non è mai rosso: è `Patto.Silenzio*`, un grigio-blu. Nove volte su dieci è batteria scarica.
3. `error`/`errorContainer` restano **solo** per la validazione dei form (`impostazioni_url_non_valido`). Il banner "dati vecchi" **non** è un errore: `surfaceVariant` + `onSurfaceVariant`, una riga, niente card piena.

### 3.2 Tipografia

Font di sistema, nessun file. Si sovrascrivono **quattro** stili in un nuovo `ui/theme/Type.kt` per app (identici nei due file, o in `core-design`):

| stile M3 | dimensione / peso / tracking | uso esatto |
|---|---|---|
| `displaySmall` | 36sp · SemiBold · `letterSpacing = (-0.5).sp` | **l'eroe della schermata**, e mai due volte nella stessa: "6 su 7", "4 h 12 min", "9 giorni di fila" |
| `headlineSmall` | 24sp · SemiBold · `(-0.25).sp` | il `confronto` della proposta pendente (già usato in `ProposteScreen`), titolo dello stato vuoto forte |
| `titleMedium` | 16sp · **SemiBold** (oggi Medium) | `TitoloSezione` — oggi è indistinguibile dal `bodyLarge` delle card sotto |
| `labelMedium` | 12sp · Medium · `0.6.sp`, scritto MAIUSCOLO nella stringa | sopra-titoli: "PROPOSTA DEL GENITORE", "ULTIMI 8 GIORNI", "DENTRO IL PATTO" |

Il resto resta M3 di default: `titleSmall` 14sp (titolo di card), `bodyLarge` 16sp (contenuto), `bodyMedium` 14sp (secondario), `labelSmall` 11sp (orari e timestamp).

**Regole d'uso.** Un solo `displaySmall` per schermata. Etichetta e valore **non** stanno mai nella stessa stringa: `TotaleGiorno` oggi scrive `stringResource(R.string.tempo_totale_oggi, testoDurata(totale))` in `headlineSmall` — diventa `labelMedium` "OGGI" + `displaySmall` "4 h 12 min". Gli orari e le età del dato (`finestra_aggiornata_alle`, `tempo_fotografia_delle`) sono sempre `labelSmall` `onSurfaceVariant`.

### 3.3 Spaziature, forme, elevazioni

```kotlin
// core-design/…/design/Spazi.kt
object Spazi { val xs=4.dp; val s=8.dp; val m=12.dp; val l=16.dp; val xl=24.dp; val xxl=32.dp }
```
**Regola:** dentro una card solo `xs`/`s`/`m`; tra blocchi solo `l`/`xl`; `xxl` solo per separare la sezione eroe dal resto. Non esistono più i 6.dp, 10.dp e 14.dp sparsi a mano in `FinestraScreen.kt`, `TempoScreen.kt` e `OggiScreen.kt`.

**Densità per app** (è qui che passa il tono, non nei componenti): genitore `contentPadding = 16.dp`, `spacedBy(12.dp)`, padding interno card 16.dp. Figlio `contentPadding = 20.dp`, `spacedBy(16.dp)`, padding interno card 20.dp.

**Forme** (oggi M3 default 4/8/12/16/28):
```kotlin
Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small      = RoundedCornerShape(8.dp),
    medium     = RoundedCornerShape(14.dp),  // tutte le Card
    large      = RoundedCornerShape(20.dp),  // scheda eroe, dialoghi
    extraLarge = RoundedCornerShape(28.dp),
)
```
Quadretto della striscia: `RoundedCornerShape(10.dp)` a 32.dp, `RoundedCornerShape(6.dp)` a 20.dp. Chip: `RoundedCornerShape(50)` (invariato). Barre d'uso: `RoundedCornerShape(3.dp)`.

**Elevazioni.** Tre livelli, non uno:
- **Scheda eroe** (`SchedaPatto`): `Card(colors = cardColors(containerColor = colorScheme.surfaceContainerHighest))`, elevazione 0.dp, `shape = large`. Sta sopra per colore, non per ombra.
- **Card di contenuto** (regole, proposte, notifiche): `CardDefaults.cardElevation(1.dp)`, `shape = medium`. Come oggi.
- **Righe di lista** (eventi, storico, uso per app): **non sono card**. Sono `Row` su `surface` con `HorizontalDivider(color = outlineVariant)`. Questo da solo toglie una ventina di rettangoli galleggianti da `FinestraScreen` e da `TempoScreen`.

### 3.4 Componenti chiave

#### `StrisciaGiorni` — la striscia degli 8 giorni (in `core-design`)
```kotlin
data class GiornoPatto(val data: String, val segnale: Segnale)

@Composable
fun StrisciaGiorni(
    giorni: List<GiornoPatto>,
    lato: Dp = 32.dp,          // 32 = protagonista; 20 = dettaglio dentro SchedaRegola
    mostraNumero: Boolean = lato >= 32.dp,
    modifier: Modifier = Modifier,
) {
    val cella = lato + 8.dp    // TUTTE le celle hanno lo stesso ingombro:
    val forma = RoundedCornerShape(if (lato >= 32.dp) 10.dp else 6.dp)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Spazi.s)) {
        giorni.forEachIndexed { i, g ->
            val oggi = i == giorni.lastIndex
            Box(
                modifier = Modifier
                    .size(cella)
                    .then(if (oggi) Modifier.border(2.dp, MaterialTheme.colorScheme.primary,
                                                    RoundedCornerShape(forma.topStart.toPx() /*+4*/))
                          else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(lato)
                        .background(coloreSegnale(g.segnale), forma)
                        .then(if (g.segnale == Segnale.NESSUN_DATO)
                              Modifier.border(1.dp, MaterialTheme.colorScheme.outline, forma)
                              else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    if (mostraNumero && g.segnale != Segnale.NESSUN_DATO) {
                        Text(g.data.takeLast(2), style = MaterialTheme.typography.labelMedium,
                             color = inchiostroSu(g.segnale))
                    }
                }
            }
        }
    }
}
```
Tre dettagli che risolvono bug reali del codice attuale:
- **Il numero del giorno sta DENTRO il quadretto**, non sotto. Oggi `Semaforo()` mette il numero sotto e, solo per l'ultima colonna, aggiunge una terza riga "oggi": la `Row` si allunga e l'allineamento salta.
- **"Oggi" è un anello, non una parola**, e l'anello è `colorScheme.primary` (blu 6,66:1 / verde 5,97:1), non `onSurface` — oggi è un rettangolo **nero** su verde saturo.
- **Tutte le celle hanno lo stesso ingombro esterno** (`lato + 8.dp`): l'anello si disegna dentro lo spazio già riservato, quindi la striscia non si deforma sull'ultima colonna.
- `NESSUN_DATO` è **vuoto** (`surfaceVariant` + bordo `outline`): il "non lo so" deve sembrare assente, non guasto. Il grigio attuale `#9E9E9E` fa **2,61:1**, sotto il minimo di 3:1 perfino per un oggetto grafico.

#### `FraseStriscia` — la sintesi, funzione pura (in `core-design`)
```kotlin
fun contaGiorni(giorni: List<GiornoPatto>): Pair<Int, Int> {
    val conDati = giorni.filter { it.segnale != Segnale.NESSUN_DATO }
    return conDati.count { it.segnale == Segnale.MANTENUTA } to conDati.size
}
```
Resa: `labelMedium` "ULTIMI 8 GIORNI" · `displaySmall` **"6 su 7"** · `bodyMedium` "giorni dentro tutte le regole" · e, solo se serve, `labelSmall` "1 giorno senza dati".
I giorni senza dati **escono dal denominatore**: contarli come falliti sarebbe una bugia, contarli come riusciti anche. Essendo una funzione pura su un dato del server, la frase è identica nelle due app per costruzione — senza campi nuovi nel contratto oltre a `semaforo`.

#### `RigaStato` — il banner di stato (sostituisce `BannerSilenzio`)
La normalità non grida, l'anomalia occupa spazio.
- **In contatto:** nessuna card. Una `Row` sotto la `TopAppBar`: `Box(8.dp)` tondo `primary` + `labelLarge` "in contatto · ultimo battito 14:32" + l'età della finestra (`finestra_aggiornata_alle`) sulla stessa riga, `labelSmall` `onSurfaceVariant`. Una riga invece delle due attuali.
- **Silenzio:** allora sì, `Card` piena, `containerColor = Patto.Silenzio*` (grigio-blu, **non rosso**), `Icon` 24.dp, `titleMedium` "L'app non si fa sentire dalle 15:20" + `bodyMedium` "Può essere batteria, rete o l'app chiusa. Il registro ha un buco."
- **Dati vecchi / rete assente:** una riga `surfaceVariant`, `bodyMedium` `onSurfaceVariant`, altezza 40.dp. Mai `errorContainer`, mai a piena card. Sostituisce l'attuale card `errorContainer` in `FinestraScreen.kt` (righe 152-167), `TempoScreen.kt` (150-165), `NotificheScreen.kt` (110-125) e `BannerDatiVecchi()` in `RegoleScreen.kt` (659-672).

#### `SchedaRegola` dopo il redesign
```
Card (medium, 1.dp)
 ├ labelMedium  "LIMITE DI TEMPO"                          (primary)
 ├ bodyLarge    "Instagram: al massimo 1 h al giorno"
 ├ [chip "non più nel patto"] se !attiva                   (secondaryContainer)
 ├ Spacer(Spazi.m)
 └ StrisciaGiorni(lato = 20.dp, mostraNumero = false)
```
Il tipo di regola come sopra-titolo `labelMedium` esiste già nel figlio (`RegoleScreen.CardRegola`, riga 253) e manca nel genitore: si allinea.

#### Barre dei tempi d'uso — senza librerie
```kotlin
@Composable
fun BarraUso(minuti: Int, limite: Int?, massimoDelGiorno: Int) {
    val denominatore = (limite ?: massimoDelGiorno).coerceAtLeast(1)
    val frazione = (minuti.toFloat() / denominatore).coerceIn(0f, 1f)
    Box(
        Modifier.fillMaxWidth().height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(Modifier.fillMaxWidth(frazione).fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary))
    }
}
```
~12 righe, zero dipendenze. Scala: con `limite != null` la barra è **sul limite** (l'unica scala che il ragazzo si è dato); senza limite, sull'app più usata del giorno. `primary` sul binario `surfaceVariant` fa 5,30:1 (genitore) / 4,73:1 (figlio), largamente sopra il 3:1 richiesto.
**Oltre il limite:** la barra resta piena e `primary` — **non** diventa terracotta (legge 1 di D2). L'eccedenza si dice a parole: chip `secondaryContainer` **"20 min oltre"** accanto al badge del limite, e i minuti totali restano `onSurface`. È la P5 di Marco: la colpa, se c'è, è la differenza da una promessa che si è dato lui, non il totale.

#### Stati vuoti
Regola generale (Sara, P5): **quando va bene si scrive**, non si lascia l'assenza di rosso a comunicarlo. Ma non si costruisce un banner verde speculare al rosso — una riga asciutta.
Forma: `Icon` 20.dp `onSurfaceVariant` + `bodyMedium` `onSurfaceVariant`, allineati a sinistra, dentro il flusso. Solo lo stato vuoto di **prima apertura** (nessuna fotografia mai ricevuta, nessuna regola) usa `Centro { }` con `headlineSmall` + `bodyMedium`, perché lì il vuoto è la schermata.
Le sezioni vuote della finestra (`sforamenti_vuoto`, `manomissioni_vuoto`) **spariscono del tutto** e collassano in una riga sola dentro la scheda in cima: "Nessun giorno fuori regola · registro completo".

### 3.5 Linguaggio

Il concept dice *testimone, non carceriere*; le stringhe dicono *sforamento, manomissione, tetto superato, verdetti*. La sproporzione non è di tono: è che l'app scrive la sentenza prima che la conversazione cominci, e `concept.md` riga 34 mette esplicitamente la conseguenza nella relazione, non nell'app.
**Cambia il titolo, mai il fatto registrato.** Nessuna riga di questa tabella tocca il contratto, gli eventi o le notifiche al server: solo `res/values/strings.xml`.

| dove (chiave) | oggi | domani | perché |
|---|---|---|---|
| genitore `sezione_sforamenti` | "Sforamenti recenti" | **"Giorni fuori regola"** | "sforamento" è lessico da multa; il fatto è che un giorno è uscito da una regola che si è dato lui |
| genitore `sforamento_su_regola` | "Sforamento su: %1$s" | **"%1$s — fuori regola"** | il nome della regola prima dell'etichetta |
| genitore `sezione_manomissioni` | "Manomissioni recenti" | **"Buchi nel registro"** | `manomissione_silenzio` è quasi sempre batteria o rete: chiamarla manomissione accusa di dolo un fatto tecnico |
| genitore `manomissione_silenzio` | "Silenzio dell'app" | **"L'app non ha potuto vedere"** | è la frase del concept (riga 60), ed è vera |
| genitore `manomissioni_vuoto` | "Nessuna manomissione: buon segno." | **"Registro completo: nessun buco."** | dice un fatto invece di assolvere da un sospetto |
| genitore `sezione_storico` | "Storico modifiche" | **"Come è cambiato il patto"** | "storico" è da gestionale |
| genitore `scheda_verdetti` | "Verdetti" | **"Il tuo turno"** (voce fusa) | il genitore non emette verdetti: risponde |
| genitore `finestra_errore` | "Non riesco a raggiungere il postino…" | **"Non riesco a raggiungere il server. Questi sono gli ultimi dati ricevuti."** | "postino" è gergo interno del progetto: il padre non sa cosa sia |
| genitore `notifiche_vuoto` | "Nessuna novità." | **"Niente di nuovo nel patto."** | dice di cosa si sta parlando |
| figlio `notifica_sforamento_limite_titolo` | "Limite superato" | **"Oggi sei andato oltre"** | seconda persona, fatto, nessun giudizio |
| figlio `notifica_sforamento_fascia_titolo` | "Fascia non rispettata" | **"Hai usato il telefono nella fascia che ti sei chiuso"** | ricorda a chi appartiene la regola |
| figlio `bonus_tetto_superato` | "Tetto superato: oggi restano %1$d min…" | **"Ti sei già dato i %1$d minuti di oggi."** | non è una violazione: è una scelta già fatta |
| figlio `oggi_vuoto` | "Nessun uso registrato per oggi." | **"Nessun uso registrato finora. Oggi il registro è pulito."** | quando va bene, si scrive |
| figlio `oggi_totale` | "Tempo nelle app oggi" | **"Oggi hai usato il telefono"** | frase, non etichetta da cruscotto |
| figlio `dichiarazione_stato_in_attesa` | "In attesa di conferma" | **"L'hai fatto. Manca la firma di %1$s."** | il riconoscimento non può aspettare un adulto |
| figlio `dichiarazione_inviata_successo` | "Dichiarazione inviata: in attesa di conferma." | **"Segnato. Manca solo la firma di %1$s."** | idem, sullo snackbar |
| entrambe `regola_eliminata` (chip) | "eliminata" | **"non più nel patto"** | niente è cancellato: è uscito dal patto, e resta nella storia |
| entrambe `dati_vecchi` | "Impossibile aggiornare — questi sono gli ultimi dati ricevuti." | **"Dati fermi alle %1$s."** | è un'età, non un fallimento |

Restano **intoccati**: "creduto sulla parola", "testimone, non carceriere", "è il tuo patto", "Ti crediamo sulla parola: va a registro, senza conferme. Dirlo a viso aperto vale già qualcosa." Sono le cose migliori del prodotto.

---

## 4. Schermata per schermata

### Navigazione

**Genitore** (`app-genitore\app\src\main\java\eu\stgm\pactum\genitore\MainActivity.kt`): da **6 a 4** destinazioni.
`Finestra` (con `Badge` non lette) · `Tempo` · `Il tuo turno` (Proposte + Verdetti) · `Impostazioni`.
Icone: `ic_notifica_binocolo.xml` (già disegnato, diventa l'icona della Finestra) + 3 vettoriali nuovi. Via il set generico `Home/DateRange/Edit/CheckCircle/Notifications/Settings`, che è la prima cosa che si vede e dice "template".
`NotificheScreen` resta un composable, raggiunto dal badge della Finestra, non una scheda.

**Figlio** (`app-figlio\…\figlio\MainActivity.kt`): da **5 a 4**.
`Oggi` · `Le mie regole` · `Proposte` (con badge pendenti) · `Diario`. La scheda **Bonus sparisce**: i tre pulsanti +5/+15/+30 vanno sulla riga della regola in `Oggi`, dove il contesto è già dato.

---

### GENITORE — `FinestraScreen.kt`
`C:\Users\andre\OneDrive\Desktop\Pactum\app-genitore\app\src\main\java\eu\stgm\pactum\genitore\ui\FinestraScreen.kt`

**Oggi:** `BannerSilenzio` rosso a piena larghezza in cima, poi sei sezioni piatte (`regole`, `bonus`, `sforamenti`, `manomissioni`, `storico`) tutte in `titleMedium`, tutte fatte di `Card` identiche, 4-5 schermate di scroll.

**Dopo — tre livelli:**

1. **Il patto** (l'eroe). `RigaStato` compatta in cima (una riga). Poi `SchedaPatto`: `labelMedium` "ULTIMI 8 GIORNI" → `displaySmall` "6 su 7" → `bodyMedium` "giorni dentro tutte le regole" → `StrisciaGiorni(32.dp)` aggregata → riga di riepilogo "Nessun giorno fuori regola · registro completo" oppure "2 giorni fuori regola · 1 buco nel registro". In fondo alla scheda, il gesto non-poliziesco: `TextButton` **"Manda un segno"**, che invia al figlio una notifica di riconoscimento a **testo fisso** ("Ho visto la settimana. Bene così."), massimo una al giorno. Testo fisso e non libero di proposito: un canale libero dal genitore al figlio dentro un'app sulla fiducia diventa in fretta un canale di pressione, e la conversazione vera sta fuori dall'app.
Poi le `SchedaRegola` (striscia 20.dp, tipo come sopra-titolo, bonus residui come chip dentro la card della regola invece che in una sezione a sé).

2. **Da guardare insieme.** Sforamenti e buchi nel registro **fusi in una lista sola ordinata per data**, massimo 5, come righe con divisore — non come card. Se è vuota, non esiste.

3. **La storia.** `TextButton` "Come è cambiato il patto" che espande (`rememberSaveable { mutableStateOf(false) }`) lo `storicoModifiche`. Chiuso di default.

**Sale:** la striscia aggregata, il numero, il rapporto giorni. **Scende:** silenzio (da banner rosso a riga), bonus (da sezione a chip dentro la regola), storico (dietro un tocco), sforamenti e manomissioni (fusi, e invisibili quando vuoti). **Sparisce:** `RossoAllarme`, `VerdeCalmo`, `VerdeSemaforo`, `RossoSemaforo`, `GrigioSemaforo` (righe 62-68) — sostituiti dai token di `core-design`.

### GENITORE — `TempoScreen.kt`

**Oggi:** `SelettoreGiorni` (chip), `TotaleGiorno` in `headlineSmall` con etichetta e valore nella stessa stringa, poi **una classifica**: `selezionato.app.sortedByDescending { it.minuti }` (riga 199), con app dentro e fuori dal patto mescolate e i minuti totali colorati di rosso oltre il limite.

**Dopo:**
- `SelettoreGiorni` invariato (funziona).
- `TotaleGiorno`: `labelMedium` "OGGI" + `displaySmall` "4 h 12 min" + `labelSmall` "ultima fotografia 14:32". Il caso "nessun dato ricevuto" resta e resta esplicito: è la cosa più onesta dell'app.
- **Due blocchi al posto della classifica.** *"DENTRO IL PATTO"* — le voci con `limite != null`, ordinate per **vicinanza al limite** (`minuti.toFloat() / limite`, decrescente), ciascuna con nome, chip limite, `BarraUso` sul limite, minuti a destra in `onSurface` e, se serve, chip "20 min oltre". *"IL RESTO DELLA GIORNATA"* — le altre, `onSurfaceVariant`, barra sulla scala dell'app più usata, nessun rosso, nessun chip.
- Le righe non sono più `Card`: `Row` + `HorizontalDivider`.

**Sale:** le promesse. **Scende:** il podio dell'app più usata, che oggi mette al primo posto un'app che spesso non ha nessuna regola sopra, cioè non è nel patto. Nessun dato viene tolto: cambiano ordine e peso — l'allargamento del 15/07 chiesto dal padre resta integro.

### GENITORE — `ProposteScreen.kt` → metà di "Il tuo turno"

Struttura invariata nella sostanza (proponi / le tue proposte), ma:
- Diventa la **prima sezione** di "Il tuo turno", sopra le dichiarazioni da confermare, con `labelMedium` "DA MANDARE" / "IN ATTESA DI RISPOSTA".
- Il `confronto` calcolato dal server resta in `headlineSmall`: è già l'elemento più forte della schermata ed è giusto così.
- I chip `TagDirezione` (allenta/stringe/eliminazione) passano da `secondaryContainer` generico a: **stringe** → `tertiaryContainer` (ocra), **allenta** → `primaryContainer`, **eliminazione** → `surfaceVariant` con bordo. Sono tre azioni diverse e oggi hanno lo stesso vestito.
- Il dialogo di creazione resta invariato per la demo (`proposta_crea_titolo`): funziona, e ha già il confronto.

### GENITORE — `VerdettiScreen.kt` → metà di "Il tuo turno"

- Diventa la sezione "DA CONFERMARE" sotto le proposte, stessa schermata.
- `CardInAttesa`: il pulsante primario resta "Confermo"; "Non è vero" da `OutlinedButton` a `TextButton` — è l'azione rara e pesante, non merita il peso di un bordo pieno accanto alla conferma.
- Lo stato risolto usa sempre `verdetto.registro` (la frase congelata dal server): mai ricostruito a mano.
- Se non c'è niente da fare: stato vuoto forte, `headlineSmall` "Niente in attesa di te" — è una buona notizia, va scritta.

### GENITORE — `NotificheScreen.kt`

- Esce dalla barra, entra dal `Badge` sull'icona Finestra (il dato è già in `NotificheViewModel.stato.notifiche.size`).
- Le notifiche diventano righe (icona per tipo + `bodyLarge` messaggio + `labelSmall` orario), non card. "Segna come letta" da `TextButton` in fondo a ogni card a `IconButton` sulla riga.
- Il sopra-titolo del tipo resta `labelMedium` `primary` (`VedettaWorker.etichettaTipo`).

### GENITORE — `ImpostazioniScreen.kt`

- Resta l'unica schermata "densa", e va bene: è configurazione.
- Tre blocchi con `TitoloSezione` in `titleMedium` SemiBold: **Connessione** (URL, token, "Prova adesso", ultima verifica), **Digest giornaliero** (già presente, ottimo), **Aggiornamenti**.
- L'`error` di M3 resta usato solo qui (`impostazioni_url_non_valido`), che è esattamente dove non può essere confuso col patto.

---

### FIGLIO — `OggiScreen.kt`
`C:\Users\andre\OneDrive\Desktop\Pactum\app-figlio\app\src\main\java\eu\stgm\pactum\figlio\ui\OggiScreen.kt`

**Oggi:** una `Card` "Tempo nelle app oggi" + `headlineMedium` coi minuti, poi una lista di app con sotto il **nome del pacchetto** (`riga.pacchetto`, riga 123: `com.instagram.android` mostrato al proprietario del telefono). `StatoOggi` ha tre campi — `caricamento`, `righe`, `minutiTotali` — e **non sa nemmeno che le regole esistono**. Il figlio apre il suo patto e trova un Digital Wellbeing più brutto.

**Dopo — è la schermata che cambia di più:**

1. **L'eroe: la serie.** `displaySmall` "9 giorni di fila dentro le tue regole", e sotto, `labelMedium` `onSurfaceVariant` senza colore: "il tuo record: 12". Il record non azzera mai. La serie si calcola in locale dalla striscia del server (D3/C3), non viaggia, il genitore non la vede.
2. **`StrisciaGiorni(32.dp)`, identica a quella del padre**, con la stessa frase "6 su 7 giorni dentro tutte le regole". È letteralmente lo stesso composable con lo stesso dato: è la definizione operativa di patto.
3. **Una riga per regola attiva.** Nome della regola (`descrizioneRegola`), `BarraUso` su **limite efficace** (limite + bonus di oggi: `SentinellaPatto`/`Valutatore` lo calcolano già da `bonusOggiPerRegola`), "48 min su 1 h" a destra. Le fasce orarie non hanno barra: dicono "mancano 3 h alle 23:00". Sotto ogni regola di tempo, i tre pulsanti **+5 / +15 / +30** (bonus in due tocchi), disabilitati oltre il residuo, con una riga `labelSmall` "oggi ti sei già dato 15 min · restano 15".
4. **L'elenco per app scende sotto**, come sezione secondaria `labelMedium` "DOVE È FINITO IL TEMPO", con il totale del giorno in `labelLarge` (non più `headlineMedium`: l'eroe è uno solo) e **senza `riga.pacchetto`**.
5. Impostazioni resta l'`IconButton` nella `TopAppBar` (già così, riga 63), e da lì si raggiunge **"Cosa vede tuo padre"**.

**Sale:** il patto. **Scende:** il conteggio dei minuti. **Sparisce:** il nome del pacchetto.

### FIGLIO — `RegoleScreen.kt`

- La struttura è buona: `CardRegola` ha già il tipo in `labelMedium primary`, la descrizione in `bodyLarge` e il lock ("Allentabile dal…") esplicitato. Serve solo la nuova palette + `medium` shape + `Spazi`.
- Il FAB smette di essere lavanda (oggi `primaryContainer` M3 baseline): con lo schema completo diventa verde-acqua coerente.
- `regole_unica_regola` e `regole_vuoto` da `TestoVuoto` grigio a riga con icona: sono istruzioni, non note a piè di pagina.
- `DialogoRegola` invariato per la demo (validazione già buona), solo `spacedBy(Spazi.m)` al posto dei 10.dp a mano.

### FIGLIO — `BonusScreen.kt`

**Oggi:** paragrafo di spiegazione, card residui, "Su quale limite?", lista di `RadioButton`, campo "Motivo (facoltativo)", tre `FilledTonalButton`. Quattro tocchi e una giustificazione scritta per prendersi 15 minuti che il patto già concede.

**Dopo:** la scheda **esce dalla barra**. I tre pulsanti vivono sulla riga della regola in `Oggi` (la regola è già data dal contesto: la lista di `RadioButton` sparisce). Il motivo diventa facoltativo **dopo**: azione "aggiungi perché" nella snackbar di conferma. I tetti giorno/settimana restano visibili come riga sotto le regole in `Oggi`. `BonusScreen.kt` si smonta in Fascia C; fino ad allora resta come scheda, ma con i pulsanti già presenti anche in `Oggi`.

### FIGLIO — `ProposteScreen.kt`

- La schermata è già la migliore delle due app: il `confronto` in `headlineSmall`, "Il genitore dice: …", accetta/rifiuta a peso diverso (`Button` vs `OutlinedButton`). Si tiene.
- Cambi: sopra-titolo `labelMedium` MAIUSCOLO "PROPOSTA DEL GENITORE"; `TagDirezione` con i tre colori nuovi come nel genitore; campo motivazione **collassato** dietro "aggiungi una motivazione" (oggi un `OutlinedTextField` sempre aperto suggerisce che serva giustificarsi per rispondere); badge sulla scheda quando ci sono pendenti.

### FIGLIO — `DichiarazioniScreen.kt` (Diario)

- **Riconoscimento immediato** (P8 di Sara, accolta): appena parte "Ce l'ho fatta", la card cambia stato **subito** — spunta + `primaryContainer` pieno + "L'hai fatto. Manca la firma di [arbitro]". Oggi finisce in "In attesa di conferma" grigio, cioè l'unico feedback immediato che riceve è un'attesa. L'arbitro convalida verso il genitore; il riconoscimento verso il figlio non può aspettare un adulto.
- **Nessun contatore** "l'hai fatto 6 volte questo mese": una metrica sola, come promesso (Sara l'ha tagliata da sé).
- "Non ce l'ho fatta" resta `OutlinedButton` e il testo del dialogo resta **identico** — è la riga migliore del prodotto.
- Le dichiarazioni risolte diventano righe con divisore, non card.

### FIGLIO — `OnboardingScreen.kt`

- La checklist dei tre permessi resta com'è (funziona, e l'aiuto sulle restrizioni di Android 15/16 è indispensabile). Solo palette, forme e `Spazi`.
- **Si aggiunge uno schermo, subito dopo i permessi: "Cosa vede tuo padre"** — l'elenco letterale del contenuto della finestra (regole, striscia dei giorni, giorni fuori regola, storico, bonus, buchi nel registro, tempi di **tutte** le app) e, sotto, l'elenco di ciò che resta fuori (messaggi, contenuti, posizione, tempo reale). Stessa voce raggiungibile per sempre dalle Impostazioni del figlio.
- **Perché è entrata prima dello specchio:** il primo giorno un sedicenne apre l'app per un motivo solo, vedere cosa vedono di lui. La curiosità sulla propria esposizione batte la virtù. Ed è il pezzo che rende *verificabile* "una finestra, non una vetrata", invece di lasciarla una promessa.

### FIGLIO — `PrimaRegolaScreen` (gate)

Resta il gate (`concept.md`: almeno una regola). Il testo `prima_regola_intro` è già giusto. Si aggiunge, con la nuova tipografia, `displaySmall` "Il tuo patto inizia qui" e la spiegazione in `bodyMedium`: è la prima impressione dell'app e oggi è due paragrafi in `bodyLarge`.

---

## 5. Triage realizzativo

### FASCIA A — meno di un'ora in tutto, rischio bassissimo, resa massima

| # | Intervento | File | Impatto | Rischio |
|---|---|---|---|---|
| A1 | **Schema colori completo** (le ~26 voci di §3.1) al posto del solo `primary`. È l'intervento che toglie il viola M3 da FAB, chip, `FilledTonalButton`, `FilterChip` selezionati | `app-genitore\...\ui\theme\Theme.kt`, `app-figlio\...\ui\theme\Theme.kt` | **alto** | bassissimo |
| A2 | **`Type.kt`**: 4 stili (`displaySmall`, `headlineSmall`, `titleMedium`, `labelMedium`) + `typography = PactumTypography` nel `MaterialTheme` | nuovi `ui\theme\Type.kt` ×2 + le 2 `Theme.kt` | **alto** | bassissimo |
| A3 | **`Shapes`** 4/8/14/20/28 | le 2 `Theme.kt` | medio | bassissimo |
| A4 | **Colori del patto**: sostituire `VerdeSemaforo`/`RossoSemaforo`/`GrigioSemaforo` (`FinestraScreen.kt` 62-68) coi token nuovi; **cancellare** `RossoOltreLimite` (`TempoScreen.kt` 56) e mettere i minuti in `onSurface`; `RossoAllarme`→`Patto.Silenzio*` | `FinestraScreen.kt`, `TempoScreen.kt` | **alto** | basso |
| A5 | **`Semaforo()` ridisegnato**: 32.dp, raggio 10, numero **dentro**, anello `primary` per oggi, `NESSUN_DATO` vuoto con bordo, ingombro cella uniforme (~30 righe, sostituzione 1:1 della funzione esistente) | `FinestraScreen.kt` 275-319 | **alto** | basso |
| A6 | **Stringhe**: tutta la tabella §3.5 (nessun cambio di logica) | i 2 `res\values\strings.xml` | **alto** | bassissimo |
| A7 | **`RigaStato`**: "in contatto" da `Card` piena a una riga con pallino + età del dato accanto; card piena solo se `silente` | `FinestraScreen.kt` 169-186, 227-253 | alto | basso |
| A8 | **Banner dati vecchi** da `errorContainer` a riga `surfaceVariant` (4 punti) | `FinestraScreen.kt`, `TempoScreen.kt`, `NotificheScreen.kt`, `RegoleScreen.kt` (`BannerDatiVecchi`) | medio | bassissimo |
| A9 | **`TotaleGiorno`** e **card totale di `OggiScreen`**: `labelMedium` + `displaySmall` separati | `TempoScreen.kt` 253-304, `OggiScreen.kt` 75-90 | medio | bassissimo |
| A10 | **`Spazi`** al posto dei 6/10/12/14 a mano nelle 4 schermate principali | 4 file `ui\` | basso-medio | bassissimo |

> Ordine consigliato se il tempo è meno di un'ora: **A1 → A6 → A5 → A4 → A2**. Con questi cinque la demo cambia faccia.

### FASCIA B — poche ore, rischio medio (ristrutturazione di singole schermate)

| # | Intervento | File | Impatto | Rischio |
|---|---|---|---|---|
| B1 | **`SchedaPatto`**: striscia aggregata + `contaGiorni()` + frase + riga riepilogo. Solo dati già presenti in `Finestra` | `FinestraScreen.kt` | **alto** | medio (aggregazione da testare sui giorni senza dati) |
| B2 | **Finestra a tre livelli**: sforamenti+manomissioni fusi e ordinati per data, max 5; storico dietro `TextButton` espandibile; sezioni vuote che collassano | `FinestraScreen.kt` | **alto** | medio |
| B3 | **Tempo in due blocchi** + `BarraUso` + chip "20 min oltre" + righe con divisore al posto delle card | `TempoScreen.kt` | **alto** | medio (cambia l'ordinamento: verificare i giorni senza `totaleMinuti`) |
| B4 | **`OggiScreen` ricostruita sulle regole**: `StatoOggi` guadagna regole + limite efficace + bonus di oggi (dati già in `PattoLocale`/`Valutatore`); barre per regola; via `riga.pacchetto`; elenco app in seconda posizione | `OggiScreen.kt`, `OggiViewModel.kt` | **alto** | medio-alto (è la schermata con più logica nuova) |
| B5 | **Bonus in due tocchi** dentro `OggiScreen` (+5/+15/+30 sulla riga della regola, motivo dopo, nella snackbar) | `OggiScreen.kt`, `BonusViewModel.kt` | alto | medio |
| B6 | **Genitore 6→5 voci**: Impostazioni fuori dalla barra (stesso schema già usato dal figlio: `mostraImpostazioni` + `BackHandler`) + `Badge` non lette | `genitore\MainActivity.kt`, `FinestraScreen.kt` | medio | medio (stato di navigazione) |
| B7 | **Dichiarazione con riconoscimento immediato** (cambio stato ottimistico della card) | `DichiarazioniScreen.kt`, `DichiarazioniViewModel.kt` | medio | medio |
| B8 | **Stati vuoti** riscritti secondo §3.4 in tutte le schermate | tutti i file `ui\` | medio | basso |
| B9 | **Chip direzione a 3 colori** + campo motivazione collassato nelle Proposte (due app) | `ProposteScreen.kt` ×2 | basso-medio | basso |

### FASCIA C — lavoro vero

| # | Intervento | Cosa comporta | Impatto | Rischio |
|---|---|---|---|---|
| C1 | **`core-design` come cartella sorgente condivisa** (`Spazi`, `Forme`, `Segnale`, `Patto`, `Tipografia`, `StrisciaGiorni`, `BarraUso`, `contaGiorni`) | nuova cartella `Pactum\core-design\src\main\kotlin\eu\stgm\pactum\design\` + `sourceSets` nei due `app\build.gradle.kts`; vincolo "niente `R` dentro" | **alto** (è la precondizione della simmetria) | medio (build ×2) |
| C2 | **`semaforo` in `GET /api/patto`** + striscia + serie + record nel figlio | server (riuso del calcolo già fatto per `/api/finestra`) + `docs\contratto-api.md` + `ModelliPatto.kt` + test | **alto** | medio (tocca il contratto: prima il documento, poi i due lati) |
| C3 | **Genitore a 4 voci**: "Il tuo turno" = `ProposteScreen` + `VerdettiScreen` in una schermata a due sezioni; `NotificheScreen` raggiunta dal badge | `genitore\MainActivity.kt`, `ProposteScreen.kt`, `VerdettiScreen.kt`, `NotificheScreen.kt` | **alto** | alto (le destinazioni delle notifiche `DEST_PROPOSTE`/`DEST_VERDETTI`/`DEST_NOTIFICHE` vanno rimappate) |
| C4 | **Icone vettoriali nostre** (5 XML: finestra/binocolo, tempo, turno, regole, diario) | `res\drawable\` ×2, sul modello di `ic_notifica_binocolo.xml` | medio-alto | basso |
| C5 | **Notifica serale di chiusura giornata** (figlio), ora scelta nelle sue Impostazioni: "Oggi dentro tutte le tue regole. Nono giorno." / "Oggi 40 min oltre su TikTok. Domani riparte." | `AvvisiLocali.kt`, `PactumService.kt`/worker, `Impostazioni.kt`, strings | **alto** (è l'unico appuntamento che dà un motivo per aprire l'app quando è andata bene) | medio |
| C6 | **"Cosa vede tuo padre"**: schermo di onboarding + voce permanente in Impostazioni figlio | `OnboardingScreen.kt`, `ImpostazioniScreen.kt`, ~12 stringhe | **alto** (in adozione, non in demo) | basso |
| C7 | **"Manda un segno"**: riconoscimento genitore→figlio a testo fisso, max 1/giorno | server (nuovo tipo di notifica) + `FinestraScreen.kt` + `AvvisiLocali.kt` | medio | medio |
| C8 | **Smontaggio di `BonusScreen`** e figlio a 4 voci | `figlio\MainActivity.kt`, `BonusScreen.kt` | basso (visivo) / alto (di senso) | medio |

---

## 6. Le frasi da citare

1. **Giulia** — *"La finestra è un referto, non un feed: ogni schermata deve rispondere a «come sta andando il patto?» in tre secondi senza leggere; il testo serve per il dettaglio, mai per il verdetto."*
 È la ragione per cui esiste D1 e per cui `TitoloSezione` a peso costante non poteva restare: se il verdetto sta nel testo, il genitore lo formula da solo, a caldo.

2. **Marco** — *"Niente esiste nella finestra che il figlio non veda verbatim e nello stesso momento."*
 È la frase che ha trasformato "Cosa vede tuo padre" da schermata-elenco a **proprietà della struttura**, e che rende "finestra, non vetrata" una cosa collaudabile invece che una promessa. Da usare come checklist a ogni nuovo campo della finestra.

3. **Sara** — *"Attenua il colore, non il peso."*
 Sei parole che hanno risolto il conflitto più duro (C1): il quadretto fuori regola resta **pieno** — quindi il figlio che dichiara "non ce l'ho fatta" non viene disegnato più tenue del silenzio di una batteria scarica — ma diventa il meno contrastato della fila. La reticenza non può essere più visibile della franchezza.

4. **Giulia** — *"Un colore che compare in un punto solo smette di essere il tono dell'app e diventa un segno."*
 È la giustificazione di D2, e il motivo per cui si può essere insieme leggibili (il suo vincolo) e delicati (quello di Marco e Sara) senza mediare: non si abbassa il contrasto, si riduce la superficie.

5. **Sara** — *"Una serie che azzera è un ottimo motivo per disinstallare."*
 Da qui la regola anti-fragilità: il record non riparte mai da zero da solo, e sta in `labelMedium` grigio una riga sotto — mai accanto al numero grande. E da qui, per estensione, la scelta di togliere la serie dall'app del genitore: *"la serie è dell'atleta, non dell'allenatore"* (Marco, che l'aveva proposta lui e l'ha ritirata).