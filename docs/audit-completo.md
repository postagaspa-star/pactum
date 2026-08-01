# TERMINOLOGIA

Ho letto entrambi i file. Ecco l'analisi completa. Le chiavi restano invariate: cambio solo i testi.

---

## App GENITORE — `app-genitore/app/src/main/res/values/strings.xml`

| chiave | testo attuale | testo proposto | perché |
|---|---|---|---|
| `scheda_finestra` | Finestra | Panoramica | "Finestra" è la metafora interna del prodotto; come etichetta di navigazione non dice cosa contiene la schermata. |
| `finestra_titolo` | La finestra | Panoramica del patto | Stesso motivo: titolo descrittivo invece che evocativo. |
| `finestra_caricamento` | Sto aprendo la finestra… | Sto caricando la panoramica… | Rimuove la metafora "finestra", mantiene la voce dell'app. |
| `finestra_config_mancante` | Prima di aprire la finestra servono indirizzo del server e token: li trovi nelle Impostazioni. | Per vedere la panoramica servono l'indirizzo del server e il token: li trovi nelle Impostazioni. | Elimina "aprire la finestra". |
| `finestra_aggiornata_alle` | Finestra aggiornata alle %1$s | Aggiornato alle %1$s | "Finestra aggiornata" è gergo interno. |
| `silenzio_in_contatto` | In contatto — ultimo battito alle %1$s | In contatto — ultimo aggiornamento alle %1$s | "battito" (heartbeat) è termine da sviluppatori: un genitore non sa cosa sia. |
| `silenzio_allarme` | Silenzio dalle %1$s — l'app del figlio non si fa sentire | Nessun aggiornamento dalle %1$s — l'app del figlio non sta inviando dati | "Silenzio" e "non si fa sentire" antropomorfizzano; il fatto tecnico è che non arrivano dati. |
| `silenzio_mai` | Silenzio — l'app del figlio non si è mai fatta sentire | Nessun aggiornamento — l'app del figlio non ha ancora inviato dati | Come sopra. |
| `permesso_notifiche_testo` | …uno sforamento, un bonus, una regola cambiata, un silenzio. | …un limite superato, un bonus, una regola modificata o un'interruzione degli aggiornamenti. | "sforamento" e "un silenzio" sono gergo; li rende leggibili. |
| `regola_eliminata` | non più nel patto | non più attiva | Etichetta di stato più chiara e neutra. |
| `sezione_storico` | Come è cambiato il patto | Storico del patto | Titolo colloquiale (segnalato dal committente) → titolo standard. |
| `sezione_manomissioni` | Buchi nel registro | Interruzioni nella registrazione | "Buchi nel registro" è gergo (segnalato dal committente); il termine chiave "manomissione" è anche accusatorio. |
| `manomissioni_vuoto` | Registro completo: nessun buco. | Nessuna interruzione: la registrazione è completa. | Coerente con il nuovo titolo, niente "buco". |
| `manomissione_silenzio` | L'app non ha potuto vedere | Uso non registrato in questo periodo | Frase vaga e antropomorfica (segnalata dal committente); dice il fatto concreto. |
| `manomissione_permesso_revocato` | Permesso di lettura uso revocato | Accesso ai dati di utilizzo revocato | Allinea il nome esatto del permesso Android usato nell'app figlio. |
| `manomissione_generica` | Manomissione: %1$s | Anomalia: %1$s | "Manomissione" ha tono accusatorio/da sorveglianza. |
| `tipo_manomissione` | Manomissione | Anomalia | Come sopra, categoria di notifica. |
| `sforamento_generico` | Sforamento | Fuori regola | "Sforamento" è burocratico; "fuori regola" è già usato nella sezione ed è più caldo. |
| `tipo_sforamento` | Sforamento | Fuori regola | Coerenza con la sezione "Giorni fuori regola". |
| `notifiche_dati_vecchi` | Dati fermi: questi sono gli ultimi ricevuti. | Dati non aggiornati: questi sono gli ultimi ricevuti. | "Dati fermi" è gergo; "non aggiornati" è immediato. |
| `tempo_dati_vecchi` | Dati fermi: questi sono gli ultimi ricevuti. | Dati non aggiornati: questi sono gli ultimi ricevuti. | Stessa correzione, stessa stringa duplicata. |
| `canale_patto_descrizione` | Le novità del patto: sforamenti, bonus, modifiche, silenzi. | Le novità del patto: limiti superati, bonus, modifiche alle regole e interruzioni. | Rimuove "sforamenti" e "silenzi". |
| `notifica_silenzio_titolo` | Silenzio | Nessun aggiornamento | Titolo di notifica criptico da solo. |
| `notifica_silenzio_testo` | L'app del figlio non si fa sentire dalle %1$s. | L'app del figlio non invia aggiornamenti dalle %1$s. | Toglie l'antropomorfismo. |
| `notifica_silenzio_testo_mai` | L'app del figlio non si è mai fatta sentire. | L'app del figlio non ha ancora inviato aggiornamenti. | Come sopra. |
| `notifica_contatto_titolo` | Di nuovo in contatto | Aggiornamenti ripresi | Più descrittivo dell'evento reale. |
| `notifica_contatto_testo` | L'app del figlio si è rifatta sentire alle %1$s. Tutto a posto. | L'app del figlio ha ripreso a inviare aggiornamenti alle %1$s. Tutto a posto. | Toglie "si è rifatta sentire". |
| `impostazioni_descrizione` | Indirizzo del server postino e token del genitore. Te li dà chi ha preparato il server, insieme a te. | Indirizzo del server e token del genitore. Te li fornisce chi ha configurato il server. | "server postino" è gergo interno; la coda "insieme a te" è ambigua. |
| `impostazioni_prova_ok` | Il server risponde: finestra ricevuta. | Il server risponde correttamente. | "finestra ricevuta" è gergo. |
| `tempo_nessuna_fotografia` | Il telefono del figlio non ha ancora mandato nessuna fotografia d'uso. | Il telefono del figlio non ha ancora inviato i dati di utilizzo. | "fotografia d'uso" è metafora da sviluppatori. |
| `tempo_nessun_dato_spiega` | Il telefono del figlio non ha mandato la fotografia di questo giorno. Non è uno zero: è un'assenza di notizie. | Il telefono del figlio non ha inviato i dati di questo giorno. Non significa zero utilizzo: i dati semplicemente non sono arrivati. | Rimuove "fotografia" e chiarisce il concetto (assenza dato ≠ uso zero) in modo esplicito. |
| `tempo_fotografia_delle` | Ultima fotografia: %1$s | Ultimo aggiornamento: %1$s | Come sopra. |
| `grafico_giorno_senza_dati` | Giorno senza fotografia | Giorno senza dati | Come sopra. |
| `digest_testo_nessun_dato` | Il telefono del figlio non ha ancora mandato la fotografia di oggi. Tocca per il dettaglio. | Il telefono del figlio non ha ancora inviato i dati di oggi. Tocca per il dettaglio. | Come sopra. |
| `digest_freschezza` | Dati fermi alle %1$s — il telefono del figlio non manda aggiornamenti da un po'. | Ultimo aggiornamento alle %1$s — da allora il telefono del figlio non invia dati. | "Dati fermi" gergo + "da un po'" troppo informale. |
| `scheda_verdetti` | Il tuo turno | Da confermare | "Il tuo turno" è da gioco (segnalato dal committente); "Da confermare" dice cosa c'è da fare. |
| `verdetti_titolo` | Il tuo turno | Da confermare | Stessa correzione, titolo schermata. |
| `verdetti_sezione_attesa` | In attesa del tuo verdetto | In attesa della tua conferma | "verdetto" ha tono giudiziario/da sorveglianza; "conferma" è collaborativo. |
| `verdetto_ribalta` | Non è vero | Non è andata così | Il pulsante attuale accusa il figlio di mentire; la versione proposta contesta il fatto, non la persona. |
| `verdetto_inviato` | Verdetto registrato. | Esito registrato. | Coerente con l'abbandono del lessico "verdetto". |
| `dichiarazione_stato_registrata` | Fallimento dichiarato — creduto sulla parola | Non riuscito — creduto sulla parola | "Fallimento" è duro e stona con "Non ce l'ho fatta" usato nell'app figlio; mantiene la parte calda "creduto sulla parola". |
| `dichiarazione_stato_ribaltata` | Ribaltata — conta come fallimento | Non confermata — conta come non riuscito | "Ribaltata" è gergo giudiziario; "Fallimento" è duro. |

Nota: `verdetti_caricamento` = "Sto leggendo le dichiarazioni…" e gli altri loader in prima persona ("Sto leggendo…") li ho lasciati: sono la voce coerente dell'app e non sono fuorvianti. Se si volesse un registro più asciutto, andrebbero uniformati tutti insieme, ma non è un problema di chiarezza.

---

## App FIGLIO — `app-figlio/app/src/main/res/values/strings.xml`

| chiave | testo attuale | testo proposto | perché |
|---|---|---|---|
| `impostazioni_descrizione` | Indirizzo del server postino e token del patto. Te li dà chi ha preparato il server, insieme a te. | Indirizzo del server e token del patto. Te li fornisce chi ha configurato il server. | "server postino" è gergo interno; "insieme a te" è ambiguo. |
| `impostazioni_ultimo_battito` | Ultimo battito consegnato: %1$s | Ultimo aggiornamento inviato: %1$s | "battito" è gergo da sviluppatori. |
| `impostazioni_prova_ok` | Battito consegnato: il server risponde. | Aggiornamento inviato: il server risponde. | Come sopra. |
| `impostazioni_prova_fallita` | Consegna fallita: controlla indirizzo, token e connessione. | Invio fallito: controlla indirizzo, token e connessione. | Coerenza con "aggiornamento inviato". |
| `passo_batteria_descrizione` | Evita che Android addormenti Pactum e crei buchi nel registro. | Evita che Android sospenda Pactum e interrompa la registrazione dell'uso. | "buchi nel registro" è gergo (stesso problema dell'app genitore). |
| `dati_vecchi` | Dati fermi: questi sono gli ultimi ricevuti. | Dati non aggiornati: questi sono gli ultimi ricevuti. | "Dati fermi" gergo. |
| `notifica_sforamento_fascia_titolo` | Hai usato il telefono nella fascia che ti sei chiuso | Hai usato il telefono in una fascia che ti sei imposto | "fascia che ti sei chiuso" è costruzione contorta. |
| `notifica_sforamento_fascia_testo` | Hai usato il telefono %1$d min in una fascia che ti sei chiuso (%2$s–%3$s). Nessun blocco: è il tuo patto. | Hai usato il telefono %1$d min in una fascia che ti sei imposto (%2$s–%3$s). Nessun blocco: è il tuo patto. | Come sopra; mantiene la parte "Nessun blocco: è il tuo patto". |
| `notifica_permesso_revocato_titolo` | Il testimone è cieco | Pactum non può più misurare l'uso | Titolo drammatico e metaforico; la versione proposta dice il fatto. |
| `dichiarazione_stato_registrata` | Fallimento — creduto sulla parola | Non riuscito — creduto sulla parola | "Fallimento" è duro e stona con "Non ce l'ho fatta"; conserva la parte calda. |
| `dichiarazione_stato_ribaltata` | Ribaltata — conta come fallimento | Non confermata — conta come non riuscito | "Ribaltata" è gergo giudiziario. |
| `tipo_verdetto` | Verdetto sulla tua dichiarazione | Esito della tua dichiarazione | Abbandona il lessico giudiziario "verdetto". |

---

## Termini deliberati che consiglio di NON toccare (con un caveat)

- **patto / Pactum**: è l'identità del prodotto, chiaro e coerente. Tenere.
- **testimone / carceriere** (`onboarding_intro`, `notifica_testimone_*`): è il value proposition, spiegato nell'onboarding del figlio ("misura… senza bloccare niente"). Funziona ed è caldo. Tenere.
- **arbitro** (`regola_vita_reale`, `verdetto_conferma_per_conto_arbitro`, ecc.): è un ruolo, non gergo tecnico. **Caveat**: nell'app figlio è introdotto dal campo `regola_campo_arbitro` = "Arbitro: chi verifica (es. la mamma)", ma **nell'app genitore compare senza mai essere spiegato**. Va bene tenerlo, purché il padre lo incontri prima con quel contesto — altrimenti "arbitro: mamma" resta un po' criptico.

---

## Principi di tono seguiti

1. **Chiamare le cose col loro nome, non con metafore interne.** "aggiornamento" al posto di "battito", "dati di utilizzo" al posto di "fotografia d'uso", "interruzione della registrazione" al posto di "buco nel registro". Un genitore esterno non era nelle riunioni in cui quelle immagini avevano senso.
2. **Descrivere, non accusare.** Via il lessico da sorveglianza/tribunale ("manomissione", "verdetto", "Non è vero", "il testimone è cieco") e dentro termini neutri e fattuali ("anomalia", "esito/conferma", "Non è andata così", "Pactum non può più misurare"). Il patto è collaborativo: il testo deve dirlo.
3. **Coerenza terminologica** dentro e tra le due app: lo stesso evento ha sempre lo stesso nome ("Accesso ai dati di utilizzo", "Dati non aggiornati", "Non riuscito"). I sinonimi sparsi ("battito"/"consegna"/"verifica") confondono.
4. **Caldo ma sobrio, non freddo.** Ho conservato le frasi umane che funzionano ("creduto sulla parola", "Ce l'ho fatta", "Nessun blocco: è il tuo patto") e tolto solo il gergo. Chiarezza non è sterilità.
5. **Etichette e titoli che dicono il contenuto, non lo evocano.** "Panoramica" invece di "Finestra", "Da confermare" invece di "Il tuo turno": chi apre la schermata deve capire subito cosa ci trova.

---

# GRAFICI

Ho letto tutto il necessario. Ecco l'analisi concreta.

---

# Grafici app-genitore — analisi e piano d'intervento

## a) INTERATTIVITÀ delle barre 8 giorni

### Dov'è il grafico e perché nella Finestra il tap è morto

Il grafico a barre degli 8 giorni è **un solo composable riusato**: `BarreGiorni` in `Grafici.kt` (righe 260-358). La cliccabilità dipende da **un solo parametro**, `onScelta`, alle righe 293-297:

```kotlin
modifier = if (onScelta == null) {
    Modifier
} else {
    Modifier.clickable { onScelta.invoke(giorno.giorno) }
},
```

Viene chiamato in due punti, e la differenza è tutta lì:

- **Tempo** — `TempoScreen.kt`, `SchedaOttoGiorni` (righe 328-349), chiamata righe 342-346: passa `onScelta = onScelta`, che risale fino a `giornoScelto = it` (riga 192). → `.clickable` attaccato → **il tap cambia giorno**.
- **Finestra** — `Grafici.kt`, dentro `SchedaUsoOggi` (riga 507), chiamata **riga 558**: `BarreGiorni(giorni = usoRecente, selezionato = mostrato.giorno)` — **`onScelta` non è passato**, quindi resta il default `null` (riga 265). → nessun `.clickable` → **tap morto**.

C'è anche un secondo motivo, a monte del callback: `SchedaUsoOggi` **non ha nessuno stato di selezione**. `mostrato` è fisso (riga 509: l'ultimo giorno con dati) e passato come `selezionato`. Anche se collegassi `onScelta`, il tap non avrebbe dove scrivere.

Segnale forte che la feature era **progettata ma mai cablata**: in `strings.xml` esistono già `grafico_apri_giorno` ("Mostra il giorno %1$s") e `grafico_giorno_senza_dati` ("Giorno senza fotografia") — e **grep conferma che non sono usate da nessuna parte** nel codice.

### Come collegarlo (soluzione minima, riusa il meccanismo già presente in Tempo)

L'anello (`AnelloCategorie`), la legenda e l'etichetta al centro dentro `SchedaUsoOggi` seguono già la variabile `mostrato`: il numero al centro **è già** il totale del giorno mostrato. Basta far seguire `mostrato` alla selezione. Tre modifiche in `Grafici.kt`, `SchedaUsoOggi`:

1. **Import** (identici a quelli già in `TempoScreen.kt` righe 32-34): `androidx.compose.runtime.getValue`, `setValue`, `mutableStateOf`, `androidx.compose.runtime.saveable.rememberSaveable`.

2. **Stato + calcolo di `mostrato`** (sostituire riga 509):
```kotlin
val predefinito = usoRecente.lastOrNull { it.totaleMinuti != null } ?: usoRecente.last()
var giornoScelto by rememberSaveable { mutableStateOf<String?>(null) }
val mostrato = usoRecente.firstOrNull { it.giorno == giornoScelto } ?: predefinito
val eOggi = mostrato.giorno == usoRecente.last().giorno
```
(la fallback su `predefinito` fa sì che al cambio di giornata, se il giorno scelto sparisce, si ricada sul giorno buono invece che su un fantasma — stessa logica di `TempoScreen.kt` righe 142-144.)

3. **Callback sulla chiamata** (riga 558):
```kotlin
BarreGiorni(
    giorni = usoRecente,
    selezionato = mostrato.giorno,
    onScelta = { giornoScelto = it },
)
```

Firma esatta del callback: `onScelta: ((String) -> Unit)?`, dove la `String` è il `giorno` in ISO (es. `"2026-07-14"`), la stessa chiave di `selezionato`.

**Perché funziona senza toccare altro:** il titolo eroe `grafico_eroe_titolo` = "COM'È DIVISO IL TEMPO" non dice "oggi", quindi resta corretto anche su un giorno passato; e `etichettaCentro` (righe 525-530) mostra già `tempo_chip_oggi` / la data breve / `grafico_nessun_dato` a seconda del giorno. Toccando una barra, il numero al centro diventa il totale di quel giorno — esattamente la richiesta. Anche i giorni senza fotografia sono toccabili (il `.clickable` è sull'intera `Column`, non sulla barra): mostrano "—" + "nessun dato", onesto.

**Rifinitura consigliata (facoltativa):** usare le stringhe già pronte e inutilizzate per l'accessibilità — `onClickLabel = stringResource(R.string.grafico_apri_giorno, giornoBreve(giorno.giorno))` dentro `clickable`, e semantica `grafico_giorno_senza_dati` sui giorni null. Zero costo, e chiude il disegno originale.

---

## b) MEDIE settimanale e mensile

### Perché la mensile obbliga a toccare il server

`uso_recente` porta **8 giorni** (`genitore.py`: `GIORNI_SEMAFORO = 7` → `oggi + 7`). La **settimanale** si può ricavare lato app; la **mensile (30 gg) no** — quei giorni non arrivano all'app. Ma il dato grezzo esiste già: tabella `uso_giornaliero` (`db.py` righe 105-111), con `giorno` come **PRIMARY KEY** → **una riga per giorno**, e le righe esistono **solo per i giorni con fotografia**. Quindi un `AVG` salta da solo i giorni assenti: **niente zeri finti, per costruzione** — la regola "medie solo sui giorni con dati" è rispettata dal fatto stesso che il giorno assente non è una riga.

**Consiglio: calcolare ENTRAMBE le medie sul server**, così c'è una sola fonte di verità e finestre coerenti (evita che "settimana" abbia due valori diversi tra app e server).

### 1. Contratto (da fare PRIMA — `docs/contratto-api.md`, sezione GET /api/finestra)

Aggiungere al JSON di risposta (dopo `stato_silenzio`, riga 146) un oggetto `medie`:
```json
"medie": {
  "settimana": { "media_minuti": 168, "giorni_con_dati": 7 },
  "mese":      { "media_minuti": 152, "giorni_con_dati": 24 }
}
```
+ bullet: *"media dei `totale_minuti` sui SOLI giorni che hanno una fotografia (settimana = ultimi 7 giorni locali, mese = ultimi 30). `media_minuti: null` e `giorni_con_dati: 0` se nella finestra non c'è nessuna fotografia — mai uno zero finto. I giorni si contano nel fuso del patto, come il resto della finestra."*

### 2. Server (`server/app/routes/genitore.py`)

Nuova funzione dopo `_uso_recente` (riga 133):
```python
def _medie(conn: sqlite3.Connection, oggi) -> dict:
    def media(giorni_indietro: int) -> dict:
        inizio = (oggi - timedelta(days=giorni_indietro - 1)).isoformat()
        r = conn.execute(
            "SELECT AVG(totale_minuti) AS m, COUNT(*) AS n FROM uso_giornaliero"
            " WHERE giorno >= ? AND giorno <= ?",
            (inizio, oggi.isoformat()),
        ).fetchone()
        n = r["n"]
        return {"media_minuti": round(r["m"]) if n else None, "giorni_con_dati": n}
    return {"settimana": media(7), "mese": media(30)}
```
e nel `return` di `finestra()` (righe 224-237) aggiungere:
```python
"medie": _medie(conn, oggi),
```
`oggi` è già calcolato (riga 142). Nota di coerenza: `giorno` in `uso_giornaliero` è **già il giorno locale del telefono** (contratto riga 35, stessa base dei giorni della finestra), quindi il confronto stringa `giorno >= inizio` è corretto nel fuso del patto senza conversioni. Una riga con `totale_minuti = 0` è una fotografia reale (uso 0 minuti) e va contata — l'`AVG` la include correttamente, in linea con come `_uso_recente` già la tratta (riga 127 restituisce lo 0 reale, non `null`).

### 3. App — modelli (`app-genitore/.../dati/Modelli.kt`)

```kotlin
@Serializable
data class Medie(
    val settimana: MediaPeriodo = MediaPeriodo(),
    val mese: MediaPeriodo = MediaPeriodo(),
)

@Serializable
data class MediaPeriodo(
    @SerialName("media_minuti") val mediaMinuti: Int? = null,
    @SerialName("giorni_con_dati") val giorniConDati: Int = 0,
)
```
e nel data class `Finestra` (riga 14) aggiungere, **nullable** per tolleranza verso server vecchi:
```kotlin
val medie: Medie? = null,
```

### 4. App — dove mostrarle

Nella **scheda eroe `SchedaUsoOggi`** (è la prima cosa che il genitore vede, cfr. commento righe 493-505): è il posto naturale, accanto agli 8 giorni. Sotto `BarreGiorni` (riga 558) una riga a due valori:
```kotlin
if (medie != null) {
    Spacer(Modifier.height(Spazi.l))
    Row(horizontalArrangement = Arrangement.spacedBy(Spazi.l)) {
        MediaCella(R.string.media_settimana, medie.settimana, Modifier.weight(1f))
        MediaCella(R.string.media_mese, medie.mese, Modifier.weight(1f))
    }
}
```
dove `MediaCella` mostra `testoDurata(mediaMinuti.toLong())` grande + etichetta piccola; se `mediaMinuti == null` mostra "—" con `grafico_nessun_dato` ("nessun dato") — mai 0. Firmare `SchedaUsoOggi(usoRecente, medie, ...)` e aggiornare la chiamata in `FinestraScreen.kt` riga 167: `SchedaUsoOggi(finestra.usoRecente, finestra.medie)`.

Stringhe nuove in `strings.xml`: `media_settimana` = "MEDIA SETTIMANA", `media_mese` = "MEDIA MESE" (stile maiuscolo come `grafico_ultimi_giorni`).

Se non si volesse toccare il server, la settimanale è calcolabile in Kotlin da `usoRecente.mapNotNull { it.totaleMinuti }` (media sui non-null), ma la mensile resta impossibile: dato che il server va toccato comunque, conviene farci entrambe.

---

## c) Altri difetti dei grafici

**1. Donut — la somma delle fette può NON combaciare col numero al centro (il difetto più serio).** In `AnelloCategorie` il centro mostra `totaleMinuti` (riga 185, = `totale_minuti` della fotografia = somma su TUTTE le app), ma le fette sono disegnate in proporzione a `totale = fette.sumOf { it.minuti }` (riga 121), cioè solo le `uso_categorie`. Se le categorie non coprono tutto l'uso (app non mappate, categoria "altro" assente) o si sovrappongono, **l'anello riempie comunque i 360°** mentre il numero al centro è diverso dalla somma delle fette. Su una scheda che si intitola letteralmente "COM'È DIVISO IL TEMPO" è fuorviante: il genitore che somma le voci di `LegendaCategorie` (che somma anch'essa solo le categorie) non ritrova il numero grande. *Fix onesto:* disegnare le fette in proporzione a `totaleMinuti` e lasciare come binario grigio la parte non categorizzata, **oppure** aggiungere una fetta "Altro" = `totaleMinuti − Σfette`. Nota: esiste già la stringa inutilizzata `grafico_tutto_il_tempo` ("Tutto il tempo") — anche questa suggerisce che la fetta "resto" era prevista.

**2. Barre 8 giorni — nessun valore/asse leggibile.** Le barre sono in scala sul `massimo` della finestra (riga 269) ma **nessuna** porta un numero: senza il tap (fix a), un giorno "quanto vale?" resta muto, e una barra piccola diventa una lineetta di 3 dp (`coerceAtLeast(3.dp)`, riga 335) illeggibile. Il fix (a) risolve per il giorno selezionato (va al centro dell'anello); in più si potrebbe stampare il totale della barra accesa come piccola etichetta sopra la striscia.

**3. Due "oggi" segnati in modo diverso nella stessa schermata Finestra.** `Semaforo` (FinestraScreen.kt righe 350-425) marca "oggi" con un **anello quadrato** `primary`; `BarreGiorni` lo marca con la **barra piena** `primary`. Convivono nella stessa scroll: coerenza visiva migliorabile (stessa convenzione per "oggi" nelle due strisce). Non è un bug, ma per un genitore esterno è un dettaglio che confonde.

**4. Anello e barre orizzontali non interattivi (coerente ma limitante).** Le fette del donut e le barre orizzontali (`BarraOrizzontale`) sono `Canvas` puri: non si toccano e la legenda non evidenzia la voce corrispondente. Toccare una fetta per illuminarne la riga in legenda sarebbe naturale (stessa aspettativa del committente sulle barre), ma richiede hit-testing angolare (`atan2` sul tap) — lo segnalo come *nice-to-have*, non come intervento minimo.

**5. `BarraOrizzontale` — giunzione colore/terracotta.** Nel caso sforamento (righe 403-424) il rettangolo terracotta a piena larghezza e il rettangolo "entro limite" sopra hanno entrambi tutti gli angoli tondi (`cornerRadius = tondo`): dove il colore incontra il terracotta resta una piccola tacca arrotondata. È cosmetico (la scala `max(minuti, limite)` è corretta, non c'è errore di proporzione), ma su barre sottili si nota.

**Priorità per la presentazione al padre:** (a) tap sulle barre della Finestra + (b) medie sono le due richieste esplicite e vanno fatte; tra i difetti, il **#1 (donut che non torna)** è quello che un genitore attento scopre da solo sommando i numeri, quindi lo metterei subito dopo.

---

File toccati dal piano: `Grafici.kt` (SchedaUsoOggi, AnelloCategorie), `TempoScreen.kt` (nessuna modifica necessaria — già corretto, è il modello da copiare), `FinestraScreen.kt` (riga 167), `Modelli.kt` (nuovi data class + campo `medie`), `server/app/routes/genitore.py` (funzione `_medie` + return), `docs/contratto-api.md` (sezione GET /api/finestra), `strings.xml` genitore (3 stringhe nuove; 3 già esistenti da riusare: `grafico_apri_giorno`, `grafico_giorno_senza_dati`, `grafico_tutto_il_tempo`).

---

# FUNZIONALE

I have now reviewed both apps end-to-end (MainActivity, ui/, dati/, sync/, rete/, valutatore/, misura/) and the whole server (`server/app`). Here is the audit.

---

# Audit Pactum — qualità e completezza (revisione avversariale)

Ho esaminato server (`server/app/*`), app figlio e app genitore per intero. Nel complesso il codice è **sorprendentemente solido**: atomicità delle scritte concorrenti sul server (`BEGIN IMMEDIATE` in bonus/regole/proposte/dichiarazioni), coda eventi persistente e idempotente, gestione onesta di "dati vecchi vs errore", lettura uso da `queryEvents()` con confini di mezzanotte/riavvio/spegnimento. I problemi veri sono soprattutto di **chiarezza per un genitore esterno** e di **coerenza tra le due app e il valutatore**. Elenco per gravità.

---

## GRAVE

### 1. Il genitore vede i nomi-pacchetto grezzi (`com.instagram.android`) e le chiavi `categoria:social`, non nomi leggibili
- **Dove:** `app-genitore/.../ui/Testi.kt` → `descrizioneRegola()`, usata in `FinestraScreen.kt` (schede Regole, Sforamenti, Storico) e in `ProposteScreen.kt`. Stringa `regola_limite_tempo` = `"%1$s: al massimo %2$s al giorno"`, dove `%1$s` è `campo(parametri, "app_o_categoria")` **grezzo**.
- **Scenario:** il figlio crea "max 1h su TikTok" (che internamente è `com.zhiliaoapp.musically`) o "max 2h social" (`categoria:social`). Nella finestra del padre la scheda regola dice letteralmente **"com.zhiliaoapp.musically: al massimo 1h al giorno"** oppure **"categoria:social: al massimo 2h al giorno"**. Lo stesso testo grezzo compare negli sforamenti ("com.zhiliaoapp.musically — fuori regola") e nello storico. È esattamente il pubblico (un genitore che non ha costruito l'app) che non può decifrarlo.
- **Perché è incoerente:** la sezione Tempo (`TempoScreen.kt`, `Grafici.kt`) risolve già i nomi via `UsoApp.nome` e le categorie via `etichettaCategoria()`. Solo le *regole/sforamenti/storico* mostrano il grezzo. I `nomi` leggibili arrivano dal telefono del figlio ma sono agganciati solo a `uso_recente`, non alle regole.
- **Correzione:** (a) per le categorie è banale e locale — in `descrizioneRegola` chiamare `etichettaCategoria(chiave)` quando `app_o_categoria` inizia con `categoria:`; (b) per i pacchetti, far sì che il server alleghi alle regole un nome leggibile (riusando l'ultima mappa `nomi` vista nelle fotografie `uso_giornaliero`), oppure che il figlio salvi il nome accanto alla regola. Senza questo, la demo mostra stringhe tecniche proprio dove il patto dovrebbe essere più chiaro.

---

## MEDIO

### 2. La proposta del genitore su `limite_tempo` ha il bersaglio come testo libero → regola "morta" silenziosa
- **Dove:** `app-genitore/.../ui/ProposteScreen.kt`, `DialogoNuovaProposta` → `CampoTesto(app, { app = it }, R.string.proposta_campo_app)`.
- **Scenario:** il padre propone una modifica a un limite. Il campo "app" è un `OutlinedTextField` libero, precompilato col pacchetto grezzo. Se lo tocca/corregge e scrive "Instagram", il server valida solo `min_length≥1` (accetta qualsiasi testo). Se il figlio accetta, la regola punta a "Instagram" — che il valutatore (`SentinellaPatto`/`Valutatore.valutaLimite`) non fa mai match (non è un pacchetto né una `categoria:*`): **la regola non scatta più, in silenzio**.
- **Perché è incoerente:** il lato figlio ha *deliberatamente* eliminato il testo libero (commento in `RegoleScreen.SelettoreAppOCategoria`: *"niente più testo libero … non troverebbe mai un pacchetto e non scatterebbe mai in silenzio"*). La proposta del genitore reintroduce il difetto.
- **Correzione:** nella proposta di `limite_tempo` rendere `app_o_categoria` **non editabile** (il genitore propone solo i minuti), oppure una scelta ristretta alle categorie fisse. In ogni caso il server dovrebbe rifiutare un `app_o_categoria` che non sia un pacchetto plausibile o una `categoria:*` nota.

### 3. Il diario del figlio ignora la frase congelata del verdetto (`verdetto.registro`) e ricostruisce l'arbitro
- **Dove:** `app-figlio/.../ui/DichiarazioniScreen.kt` → `CardDichiarazione`/`descrizioneStato`: `val arbitro = regola?.let { parametroTesto(it.parametri, "arbitro_nome") } ?: "?"`.
- **Scenario:** il figlio dichiara un successo su "cammino 1h", arbitro "Nonna"; il genitore conferma per conto. Poi il figlio cambia l'arbitro della regola in "Mamma" (o elimina la regola). Nel diario del figlio la dichiarazione **passata** ora dice "confermata per conto di Mamma" (o "…di ?" se la regola è stata eliminata), mentre il server ha congelato "confermato dal genitore per conto di **Nonna**" in `verdetto.registro`.
- **Perché è incoerente:** il contratto dice *"le app mostrano QUELLA [registro], non la ricostruiscono"*; il lato genitore lo fa correttamente (`VerdettiScreen.CardRisolta` usa `dichiarazione.verdetto?.registro`). Il figlio no.
- **Correzione:** in `descrizioneStato`/`CardDichiarazione` usare `dichiarazione.verdetto?.registro` verbatim quando presente (fallback alla descrizione locale solo per gli stati senza verdetto, es. fallimento `registrata`), esattamente come fa il genitore.

### 4. I limiti per categoria contano app diverse da quelle mostrate al genitore
- **Dove:** `app-figlio/.../valutatore/SentinellaPatto.kt` (`minutiPerCategoria`) esclude solo `context.packageName`; la fotografia `app-figlio/.../sync/BattitoWorker.kt` (`eventoUsoGiornaliero`) filtra con `CatalogoApp.contaNellUso` (esclude schermata Home, **entrambe** le app Pactum, e i pacchetti senza icona).
- **Scenario:** una regola `categoria:altro` (o qualsiasi categoria che raccolga la Home/sistema). Il valutatore conta anche il tempo sulla schermata Home e sui componenti di sistema; la fotografia che il padre vede **non** li conta. Il figlio riceve uno sforamento su minuti che nella sezione Tempo non esistono → "non torna".
- **Perché è incoerente:** il commento in `BattitoWorker.eventoUsoGiornaliero` afferma che `uso_categorie` conta *"LE STESSE app che SentinellaPatto dà in pasto al valutatore"* — ma non è vero per le esclusioni.
- **Correzione:** applicare lo **stesso** filtro `CatalogoApp.contaNellUso` anche in `SentinellaPatto.valuta` prima di popolare `minutiPerPacchetto`/`minutiPerCategoria`.

### 5. Il totale "Oggi" del figlio ≠ il totale della fotografia che vede il genitore
- **Dove:** `app-figlio/.../ui/OggiViewModel.kt`: `UsageStatsReader(context).usoDelGiorno()` **senza** filtro `contaNellUso` → somma tutto, inclusi la Home e Pactum stessa; il genitore vede il totale filtrato.
- **Scenario:** nella demo, padre e figlio guardano lo stesso giorno affiancati. "Oggi: 3h 40m" sul telefono del figlio, "3h" nella finestra del padre. Sembra un bug dei dati.
- **Correzione:** filtrare `OggiViewModel` con `CatalogoApp.contaNellUso` (o almeno escludere Home + Pactum) per allineare il totale a quello della fotografia.

### 6. Il semaforo colora il giorno di *ricezione*, non il giorno dello sforamento
- **Dove:** `server/app/routes/genitore.py`, `finestra()`: `sforamenti_per_regola[regola_id].add(_data_locale(evento["ts_server"], tz))` — usa il `ts_server`, non il campo `giorno` che il telefono ora mette nei `dettagli` (`SentinellaPatto.valuta`).
- **Scenario:** il telefono resta offline la notte; uno sforamento maturato alle 23:30 viene consegnato alle 07:00 del giorno dopo. Il quadretto rosso finisce sul **giorno sbagliato** del semaforo.
- **Correzione:** far colorare il semaforo (e, dove serve, `uso_recente`) usando `dettagli["giorno"]` quando presente, con fallback su `ts_server`.

---

## MINORE

### 7. Nessun selettore d'orario per le fasce; validazione HH:MM disallineata tra le due app
- **Dove:** figlio `RegoleScreen.DialogoRegola` valida `dalle`/`alle` con `ORA_REGEX` ma su un `CampoTesto` a tastiera normale; genitore `ProposteScreen.DialogoNuovaProposta` controlla **solo** `isBlank()` e delega il formato al 422 del server.
- **Scenario:** il figlio scrive "23.00" o "11pm" → il pulsante Salva resta spento senza spiegazione; il genitore scrive un orario storto → round-trip e snackbar "parametri non validi". Attrito evitabile per un dato che vuole "23:00" esatto.
- **Correzione:** usare un `TimePicker`, o almeno validare `ORA_REGEX` anche nel lato genitore e mostrare un hint di formato inline.

### 8. Il figlio non può dichiarare una regola di vita reale per un giorno passato
- **Dove:** `app-figlio/.../ui/DichiarazioniViewModel.kt` → `dichiara()` non passa mai `giorno` (default "oggi"); il contratto permette fino a −7 giorni.
- **Scenario:** "ieri ho camminato ma ho scordato di segnarlo": impossibile. Il campo `giorno` esiste nell'API ma la UI non lo espone.
- **Correzione:** aggiungere un piccolo selettore di giorno (oggi ↔ −7) nel dialogo di dichiarazione.

### 9. L'app genitore non ha né foreground service né esenzione batteria
- **Dove:** `app-genitore/app/src/main/AndroidManifest.xml` (nessun FGS, nessun `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`); `VedettaWorker` è solo un `PeriodicWork` a 15 min con `NetworkType.CONNECTED`.
- **Scenario:** su telefoni con battery-killer aggressivo gli avvisi di **silenzio** e il **digest giornaliero** possono arrivare in forte ritardo. Su Motorola (moderato) è accettabile, ma è un rischio di affidabilità reale per una funzione "di vigilanza".
- **Correzione:** valutare per il genitore l'esenzione batteria (come il figlio) o accettare esplicitamente il ritardo nella comunicazione all'utente.

### 10. Pagina 404 del download riflette l'input senza escaping
- **Dove:** `server/app/routes/distribuzione.py` → `_non_trovato_html(nome_file)` interpola `{nome_file}` (segmento di path) dentro l'HTML senza escaping; raggiungibile da `GET /scarica/<qualcosa>` non riconosciuto.
- **Scenario:** `/scarica/<script>…` viene riflesso nella pagina. Impatto basso (pagina pubblica senza cookie/sessione), ma è XSS riflessa vera.
- **Correzione:** `html.escape(nome_file)` prima dell'interpolazione, o non ristampare affatto il nome file.

### 11. `normalizzaUrlServer` del figlio non ripulisce query/frammento/credenziali (il genitore sì)
- **Dove:** `app-figlio/.../rete/PostinoClient.kt` → `conSchema.toHttpUrlOrNull()?.toString()?.trimEnd('/')`; il genitore (`app-genitore/.../rete/PostinoClient.kt`) ricostruisce da scheme/host/port/path.
- **Scenario:** se nel campo server si incolla un URL con `?...`, la query sopravvive e la concatenazione `+ "/api/patto"` produce un endpoint rotto → "il figlio non consegna mai" senza errore chiaro.
- **Correzione:** allineare il figlio alla versione robusta del genitore.

### 12. Accettare una proposta genera due notifiche al genitore
- **Dove:** `server/app/routes/proposte.py` `rispondi_proposta` (accoda `proposta_risposta`) + `regole.py` `applica_modifica` (accoda `modifica_regola`), entrambe verso il genitore.
- **Scenario:** il figlio accetta → il padre riceve "Il figlio ha risposto: accetta" **e** "Regola X modificata (allenta)". Ridondante.
- **Correzione:** sopprimere la `modifica_regola` quando la modifica nasce da `concordata=True` (o unificare in una sola notifica).

### 13. Token del ruolo sbagliato → solo "prova fallita" generica
- **Dove:** auth server (`403` per ruolo errato) → client `leggi()` mappa qualsiasi non-2xx a `null` → `ImpostazioniScreen` mostra "prova fallita".
- **Scenario:** durante il setup manuale il padre incolla il token del **figlio** nell'app genitore (o viceversa). Ottiene "prova fallita" identica a "server irraggiungibile", difficile da diagnosticare.
- **Correzione:** distinguere il `403` nel client e mostrare "questo token è dell'altro ruolo".

---

## Aree solide (verificate, nessun problema)
- **Concorrenza server:** bonus atomico, una sola proposta pendente per regola, una dichiarazione per (regola, giorno) con indice UNIQUE come autorità, risposta-proposta atomica, `ultima_regola` protetta anche in concorrenza. Ben fatto.
- **Coda eventi / offline:** `CodaEventi` (rimozione per id, sostituzione fotografia per giorno, scrittura atomica temp+rename) e `PattoLocale` sono robuste.
- **Misura uso:** `UsageStatsReader` gestisce correttamente innesco, confine mezzanotte, `DEVICE_SHUTDOWN/STARTUP`, sessioni aperte; il totale usa i millisecondi veri, non la somma dei minuti arrotondati.
- **Anti-manomissione:** drift orologio "anti-salame" con ancora `elapsedRealtime`, rilevamento revoca permessi sulla transizione, cambio fuso; nessun Device Admin (coerente con la filosofia).
- **Auto-update:** rifiuto degli URL assoluti nel metadata (l'APK arriva solo dal server del patto), fallback su `VERSIONI_DEFAULT`, `versionCode 4` allineato tra build e `versioni.json` (nessun loop di update).
- **UX "dati vecchi vs errore":** distinzione coerente ovunque (banner `surfaceVariant`, mai `errorContainer`); "nessun dato" ≠ zero finto in tutta la sezione Tempo. Ottimo per un genitore.

**Priorità per la demo:** #1 (nomi grezzi) è quella che più intacca la chiarezza davanti a un genitore esterno — la parte categorie si sistema in poche righe lato client. Poi #5 e #4 (i totali che "non tornano" tra i due telefoni), quindi #2 e #3 (coerenza proposte/verdetti).

File principali citati (percorsi assoluti):
- `C:\Users\andre\OneDrive\Desktop\Pactum\app-genitore\app\src\main\java\eu\stgm\pactum\genitore\ui\Testi.kt`
- `C:\Users\andre\OneDrive\Desktop\Pactum\app-genitore\app\src\main\java\eu\stgm\pactum\genitore\ui\ProposteScreen.kt`
- `C:\Users\andre\OneDrive\Desktop\Pactum\app-figlio\app\src\main\java\eu\stgm\pactum\figlio\ui\DichiarazioniScreen.kt`
- `C:\Users\andre\OneDrive\Desktop\Pactum\app-figlio\app\src\main\java\eu\stgm\pactum\figlio\valutatore\SentinellaPatto.kt`
- `C:\Users\andre\OneDrive\Desktop\Pactum\app-figlio\app\src\main\java\eu\stgm\pactum\figlio\sync\BattitoWorker.kt`
- `C:\Users\andre\OneDrive\Desktop\Pactum\app-figlio\app\src\main\java\eu\stgm\pactum\figlio\ui\OggiViewModel.kt`
- `C:\Users\andre\OneDrive\Desktop\Pactum\server\app\routes\genitore.py`
- `C:\Users\andre\OneDrive\Desktop\Pactum\server\app\routes\distribuzione.py`

---

# CHIAREZZA

Ho letto entrambe le app schermata per schermata (navigazione, tutti gli screen `ui/*.kt` e i due `strings.xml`). Ecco l'analisi dai panni di un genitore che apre l'app per la prima volta e non sa niente di com'è fatta dentro.

---

# Pactum — Analisi di chiarezza per un genitore esterno

## Orientamento: cosa vede il papà appena entra

App genitore = `Pactum Genitore` (`app-genitore/.../res/values/strings.xml`, `nome_app`). In basso una barra con **6 schede**: `Finestra · Tempo · Proposte · Il tuo turno · Notifiche · Impostazioni` (`MainActivity.kt`, enum `Destinazione`). Sei voci sono già di per sé troppe per una barra Material (il consiglio è 3–5): su un telefono le etichette si stringono e due nomi — **"Il tuo turno"** e **"Proposte"** — non dicono cosa ci sia dentro.

Problema di fondo che pesa su TUTTE le schermate: **da nessuna parte, nell'app genitore, è spiegato cos'è Pactum e qual è il ruolo del genitore.** L'unico onboarding con la filosofia ("testimone, non carceriere", "le regole le scrivi tu") sta nell'app FIGLIO (`OnboardingScreen.kt`, `PrimaRegolaScreen`). Il papà a cui Andrea mostra l'app non ha visto quelle frasi: apre una console che osserva un telefono altrui, senza sapere perché non può imporre lui una regola. `MainActivity.kt` del genitore mostra solo il dialogo permessi notifiche (`permesso_notifiche_titolo` = "Avvisi del patto"), niente di più.

---

## Schermata per schermata — APP GENITORE

### 1. Finestra (`FinestraScreen.kt`) — la home
Titolo a schermo: **"La finestra"** (`finestra_titolo`). Ordine delle sezioni: stato canale → anello uso di oggi + 8 giorni → "Le regole" → "Bonus" → "Giorni fuori regola" → "Buchi nel registro" → "Come è cambiato il patto".

- **In 3 secondi capisco:** che sono "in contatto" con qualcosa, che c'è un grafico del tempo, che ci sono delle regole.
- **Cosa NON capisco / parole di un'altra app:**
  - **"Finestra"** (scheda + titolo). È la metafora centrale del progetto, ma per un genitore è una parola vuota: finestra su cosa? È la prima parola che legge e non significa niente.
  - **"In contatto — ultimo battito alle 14:32"** (`silenzio_in_contatto`). **"battito"** è gergo interno (heartbeat). Battito di cosa? Un genitore non lo lega all'app del figlio.
  - **"Buchi nel registro"** (`sezione_manomissioni`) con sottotipi: "Cambio manuale dell'ora", **"Cambio del fuso orario"**, "L'app non ha potuto vedere", "Permesso di lettura uso revocato". Questa è la parte più "cruscotto anti-frode" di tutta l'app: parla di manomissioni e registri. Mette in allarme ("mio figlio ha barato?") e — dettaglio pericoloso in demo — segnala il **cambio di fuso** come anomalia: basta una gita all'estero per accendere un allarme che sembra un imbroglio.
  - Gli **8 quadretti verde/rosso** sotto ogni regola (`Semaforo`) **non hanno legenda**: il significato (verde = mantenuta, rosso = fuori regola) vive solo nei commenti del codice. Il papà vede quadrati colorati con dei numeri e nessuna chiave di lettura.
- **Cosa mi aspetterei e non posso:** toccare un quadretto o una regola per il dettaglio (nella Finestra non è toccabile; in Tempo sì → incoerenza).
- **Tono:** la metà bassa (fuori regola → buchi nel registro → manomissioni) scivola nella sorveglianza, non nel dialogo. Nota positiva: gli **empty state** sono rassicuranti e onesti — "Registro completo: nessun buco" (`manomissioni_vuoto`), "Nessuna regola ancora: il patto nasce sul telefono del figlio" (`regole_vuoto`). Quest'ultimo è l'UNICO punto in cui il modello viene detto al genitore — ma sparisce appena arrivano i dati.

### 2. Tempo (`TempoScreen.kt`)
Anello per categorie + 8 giorni + barre per app/categoria. Titoli eroe in maiuscolo: **"COM'È DIVISO IL TEMPO"**, **"ULTIMI 8 GIORNI"** (`grafico_eroe_titolo`, `grafico_ultimi_giorni`).
- **In 3 secondi capisco:** quanto tempo e diviso in cosa. Questa schermata è la più chiara e la meglio riuscita.
- **Confusione:** **Finestra e Tempo mostrano tutte e due l'anello dell'uso + gli 8 giorni.** Il papà non capisce perché la home e la scheda "Tempo" abbiano lo stesso donut: "quale guardo?".
- Ottimo il messaggio onesto sull'assenza dati: "Non è uno zero: è un'assenza di notizie" (`tempo_nessun_dato_spiega`).

### 3. Proposte (`ProposteScreen.kt`)
"Proponi una modifica" su una regola attiva; lista "Le tue proposte" con stati e tag **allenta/stringe/eliminazione**.
- **Cosa NON capisco:** perché posso solo *proporre* e non *imporre*. Chi arriva da un normale parental-control si aspetta di **impostare** un limite; qui il genitore può solo suggerire e il figlio accetta/rifiuta. Questo ribaltamento — il cuore di Pactum — **non è spiegato in nessun punto della schermata**. L'unico indizio è l'empty state `proposte_nessuna_regola_attiva` ("le regole nascono sul telefono del figlio"), che sparisce appena c'è una regola.
- Dopo l'invio: "Il figlio vedrà: …" (`proposta_inviata_confronto`) — buono, chiarisce la trasparenza.

### 4. Il tuo turno / Verdetti (`VerdettiScreen.kt`)
Scheda **"Il tuo turno"** (`scheda_verdetti`), titolo uguale. Sezioni "In attesa del tuo verdetto" / "Dichiarazioni risolte".
- **In 3 secondi:** nulla finché non c'è contenuto; il nome "Il tuo turno" non dice il turno **di fare cosa**.
- **Parole da tribunale:** "verdetto", "arbitro", **"Confermo per conto dell'arbitro"** (`verdetto_conferma_per_conto_arbitro`), e soprattutto i due tasti/etichette che possono dare del bugiardo al figlio: **"Non è vero"** (`verdetto_ribalta`) e **"Ribaltata — conta come fallimento"** (`dichiarazione_stato_ribaltata`). Anche "Fallimento dichiarato — creduto sulla parola" (`dichiarazione_stato_registrata`). Per capirci serve conoscere il concetto di regola "vita reale" (il figlio si impegna, un arbitro verifica) che non è mai introdotto.
- **Tono:** è la schermata più fredda e adversariale dell'app, l'esatto opposto del "invito al dialogo". Ed è proprio quella che chiede al genitore di emettere giudizi.

### 5. Notifiche (`NotificheScreen.kt`)
Lista chiara, "Segna come letta", empty "Niente di nuovo nel patto". Unica stonatura: tra i tipi compare **"Manomissione"** (`tipo_manomissione`) e **"Sforamento"** (`tipo_sforamento`) — di nuovo lessico tecnico/burocratico.

### 6. Impostazioni (`ImpostazioniScreen.kt`)
- Descrizione: "Indirizzo del **server postino** e **token** del genitore" (`impostazioni_descrizione`). **"postino"** è una metafora interna che trapela all'utente; **"token"** e "Indirizzo del server" (placeholder `https://pactum.esempio.it`) sono da tecnico. Per un papà non tecnico questo è lo scoglio zero: se non lo supera, tutte le altre schede restano vuote. Attenuato bene dalla frase "Te li dà chi ha preparato il server, insieme a te" — ma il vocabolario resta infrastrutturale.

---

## In breve — APP FIGLIO (`Pactum`)

Tono nettamente più caldo e curato del genitore. 5 schede: `Oggi · Regole · Bonus · Proposte · Diario`.
- **Onboarding** (`OnboardingScreen.kt`): "Pactum è un **testimone**, non un **carceriere**" (`onboarding_intro`) — bella cornice, e la guida alle "impostazioni con limitazioni" di Android 15/16 è concreta.
- **Prima regola** (`PrimaRegolaScreen`): "Il tuo patto inizia qui / le regole le scrivi tu / dattene almeno una" — spiega il modello… ma **solo al figlio**.
- **Oggi:** "Oggi hai usato il telefono" + numero grande. Chiarissimo.
- **Regole / lock:** "Questa modifica allenta la regola: potrai allentarla tra X. Stringerla invece si può sempre" (`regola_lock_messaggio`) — ottimo, onesto.
- **Bonus:** "+5/+15/+30 minuti… Il genitore lo vede: il patto resta trasparente" (`bonus_spiegazione`). Buono.
- **Diario:** "Ce l'ho fatta / Non ce l'ho fatta", "Ti crediamo sulla parola… Dirlo a viso aperto vale già qualcosa" (`dichiarazione_conferma_fallimento_testo`). Tono giusto.
- Nota: l'icona della scheda **Proposte del figlio è una busta email** (`MainActivity.kt` figlio, `Icons.Filled.Email`), mentre nel genitore Proposte è una **matita**. Stesso concetto, due icone diverse tra le due app.

**Squilibrio chiave:** l'app figlio è accogliente e spiega tutto; l'app genitore — proprio quella mostrata al papà — è più tecnica e, in "Buchi nel registro" e "Il tuo turno", più poliziesca. Il papà non ha davanti la cornice gentile che ha il figlio.

---

## Le 5 cose che più migliorerebbero la comprensione per un genitore esterno (per impatto)

1. **Aggiungere all'app genitore una schermata iniziale che spiega cos'è Pactum e il ruolo del genitore.** Oggi non esiste (`MainActivity.kt` genitore ha solo il dialogo permessi). Una frase risolve la confusione madre ("perché non posso mettere io le regole?") e pre-inquadra come *fiducia* le sezioni che sembrano spionaggio: es. *"In Pactum è tuo figlio a darsi le regole. Tu le vedi e puoi proporre modifiche. L'app non blocca niente: è un testimone."* È la stessa cornice che oggi vede solo il figlio.

2. **Togliere le metafore interne dalle etichette del genitore.** Sono le parole che "sembrano di un'altra app": **"Finestra"** (scheda + `finestra_titolo`) → un nome concreto come "Il patto" o "Riepilogo"; **"ultimo battito"** (`silenzio_in_contatto`) → "ultimo collegamento/aggiornamento"; **"server postino"/"token"** (`impostazioni_descrizione`, `impostazioni_token`) → parole di setup più piane. "Finestra" e "postino" sono metafore da dietro-le-quinte finite davanti all'utente.

3. **Smorzare il lessico da tribunale/anti-frode che dà il tono sorveglianza.** In "Il tuo turno": **"Non è vero"** (`verdetto_ribalta`) e **"Ribaltata — conta come fallimento"** (`dichiarazione_stato_ribaltata`) andrebbero riscritti verso il dialogo (es. "Non è andata così"); "verdetto/arbitro" spiegati o ammorbiditi. In Finestra/Notifiche: **"Buchi nel registro"** e **"Manomissione"** (`sezione_manomissioni`, `tipo_manomissione`) suonano forensi — e segnalare il **cambio di fuso** come anomalia va spiegato, o un viaggio sembra un imbroglio. È il punto in cui il papà sentirà più disagio in demo.

4. **Sciogliere il doppione Finestra vs Tempo e mettere una legenda ai colori.** Le due schede mostrano lo stesso anello uso + 8 giorni (`SchedaUsoOggi` in `FinestraScreen.kt` e `TempoScreen.kt`): o si unifica, o si dichiara la differenza. E i quadretti **verde/rosso** del `Semaforo` non hanno chiave di lettura: aggiungere una micro-legenda "verde = giornata nella regola · rosso = fuori regola · vuoto = nessun dato".

5. **Rinominare le schede opache e la parola "sforamento", e sfoltire la barra a 6 voci.** **"Il tuo turno"** e **"Proposte"** non dicono cosa contengono; **"Sforamento"** (`sforamento_generico`, `tipo_sforamento`) è burocratico mentre esiste già la formula chiara **"Giorni fuori regola"** (`sezione_sforamenti`) — usare quella ovunque. Con 6 tab su una barra le etichette si troncano: valutare l'accorpamento (es. Impostazioni fuori dalla barra, come nel figlio dove sta dietro l'ingranaggio di "Oggi").

---

File esaminati (tutti in `C:\Users\andre\OneDrive\Desktop\Pactum`):
- Genitore: `app-genitore\app\src\main\java\eu\stgm\pactum\genitore\MainActivity.kt` e `ui\{FinestraScreen,TempoScreen,ProposteScreen,VerdettiScreen,NotificheScreen,ImpostazioniScreen,Testi}.kt`; `app-genitore\app\src\main\res\values\strings.xml`
- Figlio: `app-figlio\app\src\main\java\eu\stgm\pactum\figlio\MainActivity.kt` e `ui\{OggiScreen,RegoleScreen,BonusScreen,ProposteScreen,DichiarazioniScreen,OnboardingScreen,Testi}.kt`; `app-figlio\app\src\main\res\values\strings.xml`

---

# PIANO

Ho verificato tutte e quattro le audit contro il codice reale: ogni claim controllato combacia esattamente (SentinellaPatto.kt riga 56 esclude solo `context.packageName`; BattitoWorker.kt righe 216-217 usa `contaNellUso`; OggiViewModel.kt riga 32 non filtra; ProposteScreen.kt riga 425 campo libero; le 3 stringhe grafico inutilizzate; server riga 157 usa `ts_server`; FinestraScreen riga 167; XSS distribuzione.py riga 203; figlio non usa `verdetto.registro`). Procedo con il piano.

---

# PACTUM — Piano implementativo unico per la demo

Nota di lettura: ho unito le quattro audit, tolto i duplicati e verificato ogni riferimento nel codice. Dove due audit chiedevano la stessa cosa con parole diverse (es. "Finestra"), ho scelto **una** formulazione canonica. Segnalo per ogni voce se è **cosmetica** (solo testo/UI, rischio ~zero) o **comportamentale** (tocca server o valutatore → va ritestata e, per il server, ridistribuita).

---

## 1. Sintesi: i problemi veri, per tema (deduplicati)

### A. Terminologia e tono (audit TERMINOLOGIA + audit CHIAREZZA UX — larga sovrapposizione)
Le due audit convergono sugli stessi bersagli; li tratto come **un solo lavoro**:
1. **Metafore interne finite davanti all'utente**: "Finestra", "battito", "server postino", "fotografia d'uso", "buchi nel registro". Un genitore esterno non era nelle riunioni in cui avevano senso.
2. **Lessico da tribunale / anti-frode** che dà tono-sorveglianza proprio nella app mostrata al padre: "Manomissione", "verdetto", "Il tuo turno", "Non è vero", "Ribaltata — conta come fallimento", "Il testimone è cieco", "Fallimento".
3. **Etichette che non dicono il contenuto**: "Finestra", "Il tuo turno", "Proposte", "Sforamento".
4. **Rischio demo specifico** (solo UX): il **cambio di fuso** compare come anomalia → una gita all'estero sembra un imbroglio.

### B. Grafici e medie (audit GRAFICI, con un pezzo di UX)
5. **Barre 8 giorni non toccabili nella Panoramica** (richiesta esplicita): feature progettata ma mai cablata — `BarreGiorni` in `Grafici.kt` è chiamata senza `onScelta` (riga 558) e `SchedaUsoOggi` non ha stato di selezione. In Tempo invece funziona.
6. **Medie settimanale/mensile assenti** (richiesta esplicita): la mensile è impossibile lato app (arrivano solo 8 giorni), va calcolata sul server.
7. **Donut che non torna**: il centro mostra `totaleMinuti` (tutte le app), le fette solo `uso_categorie`, ma l'anello riempie comunque 360°. Sommando la legenda non si ritrova il numero grande.
8. **Semaforo senza legenda + doppione Panoramica/Tempo** (qui GRAFICI e UX dicono la stessa cosa): i quadretti verde/rosso non hanno chiave di lettura; le due schede mostrano lo stesso anello + 8 giorni.

### C. Coerenza funzionale (audit FUNZIONALE — in parte tocca il tema "chiarezza")
9. **Il genitore vede i pacchetti grezzi** (`com.zhiliaoapp.musically`, `categoria:social`) nelle regole/sforamenti/storico, mentre in Tempo sono già risolti. È il difetto di chiarezza n°1 davanti a un genitore.
10. **I totali "non tornano" tra i due telefoni**: "Oggi" del figlio (nessun filtro) ≠ fotografia del genitore (filtrata `contaNellUso`); e il valutatore conta categorie diverse dalla fotografia.
11. **Coerenza proposte/verdetti**: la proposta del genitore reintroduce il testo libero che il figlio aveva eliminato apposta (regola "morta" silenziosa); il diario del figlio ricostruisce l'arbitro invece di mostrare la frase congelata `verdetto.registro`.
12. **Minori**: semaforo colora il giorno di ricezione non dello sforamento; niente time-picker; XSS riflessa sulla 404 download; doppia notifica su accettazione; token ruolo sbagliato → errore generico; niente esenzione batteria app genitore.

### D. Cornice mancante (audit CHIAREZZA UX — punto unico e ad alto impatto)
13. **L'app genitore non spiega da nessuna parte cos'è Pactum e perché il genitore non impone le regole.** La cornice gentile ("testimone, non carceriere", "le regole le scrivi tu") esiste **solo nell'app figlio**. Il padre apre una console che osserva un telefono altrui senza contesto.

---

## 2. Piano per COMPONENTE

Le tre colonne sono **indipendenti e parallelizzabili** tranne un punto di coordinamento che segnalo. Priorità: 🔴 alta / 🟡 media / ⚪ bassa.

### ⚙️ SERVER (`server/app`) — comportamentale, richiede ridistribuzione

| # | Cosa | Dove | Prio |
|---|------|------|------|
| S1 | **Medie settimana/mese**. Contratto PRIMA: in `docs/contratto-api.md` (sezione GET /api/finestra) aggiungere l'oggetto `medie` con la regola "media sui SOLI giorni con fotografia; `null` se zero giorni, mai zero finto". Poi nuova funzione `_medie(conn, oggi)` dopo `_uso_recente` (riga 133) con `AVG(totale_minuti)`/`COUNT(*)` su `uso_giornaliero` finestra 7 e 30 gg, e aggiungere `"medie": _medie(conn, oggi)` al return (righe 224-237). `oggi` è già calcolato (riga 142); `giorno` è già locale del patto → confronto stringa corretto senza conversioni. | `genitore.py`, `docs/contratto-api.md` | 🔴 |
| S2 | **Nome leggibile sulle regole** (parte server del #9): allegare alle regole limite_tempo un `nome` risolto riusando l'ultima mappa `nomi` vista nelle fotografie `uso_giornaliero`, così il genitore non vede `com.zhiliaoapp.musically`. | `genitore.py` (`_riga_regola`/`finestra`) | 🟡 |
| S3 | **Semaforo sul giorno dello sforamento** (#12): usare `dettagli["giorno"]` quando presente invece di `_data_locale(evento["ts_server"], tz)` (riga 157), con fallback su `ts_server`. | `genitore.py` riga 157 | ⚪ |
| S4 | **XSS 404 download** (#12): `html.escape(nome_file)` prima dell'interpolazione (riga 203) in `_non_trovato_html`. Fix di 1 riga. | `distribuzione.py` | ⚪ |
| S5 | **Validare `app_o_categoria` nelle proposte** (#11): rifiutare 422 se non è un pacchetto plausibile o una `categoria:*` nota, così una proposta non può creare una regola che non scatterà mai. | `proposte.py` / `regole.py` | 🟡 |
| S6 | **Sopprimere la doppia notifica** su accettazione proposta (#12): niente `modifica_regola` quando `concordata=True`. | `proposte.py` + `regole.py` | ⚪ |

### 📱 APP GENITORE — grafici + medie + terminologia + UX

> ⚠️ **Punto di coordinamento**: G1 (interattività) e G2 (UI medie) modificano **la stessa funzione** `SchedaUsoOggi` in `Grafici.kt` e **lo stesso call-site** `FinestraScreen.kt` riga 167. Vanno fatte dalla stessa persona/nello stesso branch, non in parallelo.

| # | Cosa | Dove | Prio |
|---|------|------|------|
| G1 | **Tap sulle barre 8 giorni nella Panoramica** (cosmetico/client). In `SchedaUsoOggi`: aggiungere import (`getValue`,`setValue`,`mutableStateOf`,`rememberSaveable` — identici a `TempoScreen.kt`), sostituire riga 509 con stato di selezione (`giornoScelto` + `mostrato` con fallback su `predefinito`, come `TempoScreen.kt` righe 142-144), e passare `onScelta = { giornoScelto = it }` alla `BarreGiorni` (riga 558). Il numero al centro dell'anello segue già `mostrato`: nessun'altra modifica. Rifinitura: cablare le stringhe già pronte `grafico_apri_giorno`/`grafico_giorno_senza_dati` (oggi **inutilizzate**, grep confermato). | `Grafici.kt` (`SchedaUsoOggi`) | 🔴 |
| G2 | **Mostrare le medie** (client, dipende da S1). Nuovi data class `Medie`/`MediaPeriodo` + campo `val medie: Medie? = null` (nullable per tolleranza server vecchi) in `Finestra`. Nuovo composable `MediaCella` sotto `BarreGiorni`; firmare `SchedaUsoOggi(usoRecente, medie, …)` e aggiornare `FinestraScreen.kt` riga 167. Se `mediaMinuti==null` → "—" + `grafico_nessun_dato`. | `Modelli.kt`, `Grafici.kt`, `FinestraScreen.kt`, `strings.xml` | 🔴 |
| G3 | **Categorie leggibili nelle regole** (parte client del #9, cosmetico, poche righe): in `descrizioneRegola` (`Testi.kt` riga 66-71), quando `app_o_categoria` inizia con `categoria:` chiamare la già-esistente `etichettaCategoria(chiave)` (`Testi.kt` riga 122) invece del grezzo. Risolve subito `categoria:social` → "Social" in regole/sforamenti/storico. | `Testi.kt` | 🔴 |
| G4 | **Terminologia** (cosmetico): applicare la tabella §4. Solo testi, chiavi invariate. | `strings.xml` genitore | 🔴 |
| G5 | **Legenda del semaforo** (#8, UX): micro-legenda "verde = nella regola · rosso = fuori regola · vuoto = nessun dato" sotto la sezione regole. | `FinestraScreen.kt` | 🟡 |
| G6 | **Cornice/intro per il genitore** (#13): versione leggera = card/dialogo di primo avvio (riusa il pattern del dialogo permessi già in `MainActivity.kt`) con "In Pactum è tuo figlio a darsi le regole. Tu le vedi e puoi proporre modifiche. L'app non blocca niente: è un testimone." | `MainActivity.kt` genitore + `strings.xml` | 🟡 |
| G7 | **Proposta limite_tempo: campo app non editabile** (#11): in `DialogoNuovaProposta` rendere `app_o_categoria` non-editabile (il genitore propone solo i minuti) invece del `CampoTesto` libero (riga 425). | `ProposteScreen.kt` | 🟡 |
| G8 | **Donut coerente** (#7, cosmetico ma su Canvas delicato): fette in proporzione a `totaleMinuti` con arco "resto" grigio, **oppure** fetta "Altro" = `totaleMinuti − Σfette` (stringa `grafico_tutto_il_tempo` già pronta e inutilizzata). Da testare bene: è la visual eroe. | `Grafici.kt` (`AnelloCategorie`) | 🟡 |
| G9 | **Token ruolo sbagliato** (#12): distinguere il 403 nel client e mostrare "questo token è dell'altro ruolo" (utile in setup demo). | `PostinoClient.kt` / `ImpostazioniScreen.kt` | ⚪ |
| G10 | **Sfoltire barra a 6 tab / doppione Panoramica-Tempo / esenzione batteria** (#8, #12): decisioni di struttura, non quick-fix. | `MainActivity.kt`, `AndroidManifest.xml` | ⚪ |

### 📱 APP FIGLIO — terminologia + coerenza totali/verdetti

| # | Cosa | Dove | Prio |
|---|------|------|------|
| F1 | **Terminologia** (cosmetico): applicare la tabella §4. | `strings.xml` figlio | 🔴 |
| F2 | **"Oggi" filtrato come la fotografia** (#10, client display, basso rischio): in `OggiViewModel` filtrare `usoDelGiorno()` (riga 32) con `CatalogoApp.contaNellUso`, così "Oggi 3h40" del figlio == "3h" del genitore quando li guardano affiancati. | `OggiViewModel.kt` | 🟡 |
| F3 | **Diario usa la frase congelata** (#11, client): in `descrizioneStato`/`CardDichiarazione` usare `dichiarazione.verdetto?.registro` verbatim quando presente (fallback locale solo per stati senza verdetto), come fa già il genitore. Oggi ricostruisce `arbitro` (riga 241) e ignora `registro`. | `DichiarazioniScreen.kt` | 🟡 |
| F4 | **Filtro categorie del valutatore** (#10, **comportamentale** — cambia cosa scatena uno sforamento): applicare lo stesso `CatalogoApp.contaNellUso` in `SentinellaPatto.valuta` (oggi esclude solo `context.packageName`, righe 56 e 88) per allinearsi alla fotografia. Da ritestare, tocca il valutatore. | `SentinellaPatto.kt` | ⚪ |
| F5 | **normalizzaUrlServer robusto** (#12): allineare `PostinoClient.kt` figlio alla versione del genitore (ripulire query/frammento/credenziali). | `PostinoClient.kt` figlio | ⚪ |
| F6 | **Dichiarare un giorno passato + time-picker fasce** (#12): esporre `giorno` (−7) nel dialogo dichiarazione; `TimePicker` o validazione `ORA_REGEX` inline. | `DichiarazioniViewModel.kt`, `RegoleScreen.kt` | ⚪ |

---

## 3. TRIAGE per la demo

### 🟢 FASCIA A — fare subito (alto impatto, basso rischio, o richieste esplicite)
Tutto qui è **cosmetico o additivo**, nessun rischio di rompere comportamenti in demo.

1. **G4 + F1 — Terminologia** (entrambe le app). Solo stringhe, chiavi invariate: rischio nullo, impatto altissimo sul tono davanti al padre. È il primo lavoro perché è il più sicuro e il più visibile.
2. **G3 — Categorie leggibili nelle regole**. Poche righe in `Testi.kt`, riusa `etichettaCategoria`. Elimina "categoria:social" dalla schermata regole: il difetto di chiarezza n°1 che si risolve subito.
3. **G1 — Tap sulle barre 8 giorni** (richiesta esplicita). ~10 righe, riusa il meccanismo già collaudato in `TempoScreen`. Rischio basso.
4. **G2 + S1 — Medie settimana/mese** (richiesta esplicita). Additiva e backward-compatible (campo `medie` nullable: se il server non è aggiornato l'app nasconde la riga). ⚠️ Va **ridistribuito il server** perché il padre le veda: coordinare con Andrea.
5. **G6 (versione leggera) — Card/dialogo intro per il genitore**. Riusa il pattern dialogo già presente: additivo, alto impatto (dà al padre la cornice che oggi ha solo il figlio).

### 🟡 FASCIA B — se c'è tempo (buon impatto, un po' più di lavoro o rischio contenuto)
6. **F2 — "Oggi" del figlio filtrato** come la fotografia: chiude il "3h40 vs 3h" se padre e figlio guardano affiancati. Client, basso rischio.
7. **G5 — Legenda semaforo**: micro-aggiunta, toglie il "quadretti colorati senza chiave".
8. **G7 + S5 — Proposta con app non editabile + validazione server**: evita la regola "morta" silenziosa se il padre tocca il campo app in demo.
9. **F3 — Diario del figlio usa `verdetto.registro`**: coerenza tra le due app.
10. **G8 — Donut coerente**: il difetto che un genitore attento scopre sommando i numeri. In B (non A) perché tocca il Canvas eroe e va testato con cura.
11. **S2 — Nome leggibile sulle regole (server)**: completa G3 per i pacchetti (non solo categorie). Server → ridistribuzione.

### ⚪ FASCIA C — dopo la demo
12. **F4** filtro categorie del valutatore (comportamentale, gate/test). • **S3** semaforo sul giorno dello sforamento. • **S4** XSS 404 (1 riga, quando capita). • **S6** doppia notifica. • **G9** token ruolo sbagliato. • **G10** sfoltire tab / doppione Panoramica-Tempo / esenzione batteria genitore. • **F5** normalizzaUrl figlio. • **F6** giorno passato + time-picker.

**Riassunto operativo per il giorno prima della demo**: se si fa **solo** la Fascia A, il padre trova un'app col tono giusto, senza gergo, con nomi di app leggibili, barre toccabili, medie, e una frase che gli spiega perché non impone lui le regole. Sono le tre richieste esplicite (terminologia, tap 8 giorni, medie) più i due fix di chiarezza a rischio zero.

---

## 4. Terminologia consolidata — chiave → testo nuovo (pronta da applicare)

Chiavi **invariate**, cambiano solo i valori. Ho riconciliato i due audit dove proponevano cose diverse (vedi note in fondo).

### App GENITORE — `app-genitore/app/src/main/res/values/strings.xml`

| chiave | testo nuovo |
|---|---|
| `scheda_finestra` | Panoramica |
| `finestra_titolo` | Panoramica del patto |
| `finestra_caricamento` | Sto caricando la panoramica… |
| `finestra_config_mancante` | Per vedere la panoramica servono l'indirizzo del server e il token: li trovi nelle Impostazioni. |
| `finestra_aggiornata_alle` | Aggiornato alle %1$s |
| `silenzio_in_contatto` | In contatto — ultimo aggiornamento alle %1$s |
| `silenzio_allarme` | Nessun aggiornamento dalle %1$s — l'app del figlio non sta inviando dati |
| `silenzio_mai` | Nessun aggiornamento — l'app del figlio non ha ancora inviato dati |
| `permesso_notifiche_testo` | Pactum ti avvisa quando succede qualcosa nel patto: un limite superato, un bonus, una regola modificata o un'interruzione degli aggiornamenti. Senza questo permesso vedrai le novità solo aprendo l'app. |
| `regola_eliminata` | non più attiva |
| `sezione_storico` | Storico del patto |
| `sezione_manomissioni` | Interruzioni nella registrazione |
| `manomissioni_vuoto` | Nessuna interruzione: la registrazione è completa. |
| `manomissione_cambio_fuso` | Cambio di fuso orario del telefono |
| `manomissione_silenzio` | Uso non registrato in questo periodo |
| `manomissione_permesso_revocato` | Accesso ai dati di utilizzo revocato |
| `manomissione_generica` | Anomalia: %1$s |
| `tipo_manomissione` | Anomalia |
| `sforamento_generico` | Fuori regola |
| `tipo_sforamento` | Fuori regola |
| `notifiche_dati_vecchi` | Dati non aggiornati: questi sono gli ultimi ricevuti. |
| `tempo_dati_vecchi` | Dati non aggiornati: questi sono gli ultimi ricevuti. |
| `canale_patto_descrizione` | Le novità del patto: limiti superati, bonus, modifiche alle regole e interruzioni. |
| `notifica_silenzio_titolo` | Nessun aggiornamento |
| `notifica_silenzio_testo` | L'app del figlio non invia aggiornamenti dalle %1$s. |
| `notifica_silenzio_testo_mai` | L'app del figlio non ha ancora inviato aggiornamenti. |
| `notifica_contatto_titolo` | Aggiornamenti ripresi |
| `notifica_contatto_testo` | L'app del figlio ha ripreso a inviare aggiornamenti alle %1$s. Tutto a posto. |
| `impostazioni_descrizione` | Indirizzo del server e token del genitore. Te li fornisce chi ha configurato il server. |
| `impostazioni_prova_ok` | Il server risponde correttamente. |
| `tempo_nessuna_fotografia` | Il telefono del figlio non ha ancora inviato i dati di utilizzo. |
| `tempo_nessun_dato_spiega` | Il telefono del figlio non ha inviato i dati di questo giorno. Non significa zero utilizzo: i dati semplicemente non sono arrivati. |
| `tempo_fotografia_delle` | Ultimo aggiornamento: %1$s |
| `grafico_giorno_senza_dati` | Giorno senza dati |
| `digest_testo_nessun_dato` | Il telefono del figlio non ha ancora inviato i dati di oggi. Tocca per il dettaglio. |
| `digest_freschezza` | Ultimo aggiornamento alle %1$s — da allora il telefono del figlio non invia dati. |
| `scheda_verdetti` | Da confermare |
| `verdetti_titolo` | Da confermare |
| `verdetti_sezione_attesa` | In attesa della tua conferma |
| `verdetto_ribalta` | Non è andata così |
| `verdetto_inviato` | Esito registrato. |
| `dichiarazione_stato_registrata` | Non riuscito — creduto sulla parola |
| `dichiarazione_stato_ribaltata` | Non confermata — conta come non riuscito |
| **`media_settimana`** *(nuova, per G2)* | MEDIA SETTIMANA |
| **`media_mese`** *(nuova, per G2)* | MEDIA MESE |

### App FIGLIO — `app-figlio/app/src/main/res/values/strings.xml`

| chiave | testo nuovo |
|---|---|
| `impostazioni_descrizione` | Indirizzo del server e token del patto. Te li fornisce chi ha configurato il server. |
| `impostazioni_ultimo_battito` | Ultimo aggiornamento inviato: %1$s |
| `impostazioni_prova_ok` | Aggiornamento inviato: il server risponde. |
| `impostazioni_prova_fallita` | Invio fallito: controlla indirizzo, token e connessione. |
| `passo_batteria_descrizione` | Evita che Android sospenda Pactum e interrompa la registrazione dell'uso. |
| `dati_vecchi` | Dati non aggiornati: questi sono gli ultimi ricevuti. |
| `notifica_sforamento_fascia_titolo` | Hai usato il telefono in una fascia che ti sei imposto |
| `notifica_sforamento_fascia_testo` | Hai usato il telefono %1$d min in una fascia che ti sei imposto (%2$s–%3$s). Nessun blocco: è il tuo patto. |
| `notifica_permesso_revocato_titolo` | Pactum non può più misurare l'uso |
| `dichiarazione_stato_registrata` | Non riuscito — creduto sulla parola |
| `dichiarazione_stato_ribaltata` | Non confermata — conta come non riuscito |
| `tipo_verdetto` | Esito della tua dichiarazione |

### Note di riconciliazione (dove le due audit divergevano)
- **"Finestra"**: TERMINOLOGIA proponeva "Panoramica", UX "Il patto"/"Riepilogo". Ho scelto **"Panoramica" / "Panoramica del patto"**: descrittivo e coerente su scheda+titolo. (Se si preferisce "Il patto", cambiare solo queste due righe.)
- **`manomissione_cambio_fuso`**: nessuna delle due lo riscriveva, ma UX segnala il **rischio demo** (viaggio = imbroglio). Ho ammorbidito il testo e lo metto sotto l'ombrello neutro "Anomalia". Valutare, dopo la demo (Fascia C), se **non** contarlo affatto come interruzione: è un fatto tecnico, non un sospetto.
- **`verdetto_conferma_per_conto_arbitro` / "arbitro"**: NON toccato (è un ruolo, non gergo), ma l'app genitore non lo introduce mai. Con G6 (card intro) il padre incontra il concetto prima: allora "conferma per conto dell'arbitro" diventa leggibile senza rinominarlo.
- **Loader in prima persona** ("Sto leggendo…"): lasciati — sono la voce coerente dell'app, non sono fuorvianti.
- **Termini tenuti** (identità di prodotto, spiegati nell'onboarding figlio): patto/Pactum, testimone/carceriere, "creduto sulla parola", "Ce l'ho fatta", "Nessun blocco: è il tuo patto".

---

