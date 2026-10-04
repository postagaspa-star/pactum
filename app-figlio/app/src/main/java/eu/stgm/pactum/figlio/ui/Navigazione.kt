package eu.stgm.pactum.figlio.ui

import eu.stgm.pactum.figlio.MainActivity

/**
 * (0.15) Le quattro schede fisse della barra in basso, sempre le stesse e
 * sempre nello stesso ordine: Oggi · Regole · Sessioni · Lavori. Le proposte
 * vivono dentro Regole, le dichiarazioni sulle regole di vita reale (e la loro
 * storia nella pagina Storico); i lavori di casa hanno la loro scheda sempre.
 */
enum class Scheda { OGGI, REGOLE, SESSIONI, LAVORI }

/**
 * (0.15) Le pagine che si aprono SOPRA le schede (Indietro le chiude, una alla
 * volta): le Impostazioni (anche aperte sui Permessi), i Siti visitati e
 * "Cosa vedono i tuoi genitori" (dalle Impostazioni), lo Storico delle
 * proposte e delle dichiarazioni (da Regole; anche aperto sulle
 * dichiarazioni) e l'elenco di tutte le app di oggi (da Oggi).
 */
enum class Pagina {
    IMPOSTAZIONI,
    IMPOSTAZIONI_PERMESSI,
    SITI,
    COSA_VEDE,
    STORICO,
    STORICO_DICHIARAZIONI,
    TUTTE_LE_APP,
}

/**
 * Dove porta un segnalibro: la scheda, la pagina aperta sopra (o nessuna) e se
 * la lista va riportata in cima (le proposte: "Da decidere" sta lì).
 */
data class Ingresso(val scheda: Scheda, val pagina: Pagina? = null, val inCima: Boolean = false)

/**
 * (0.15) La navigazione del figlio senza Android: i segnalibri delle notifiche
 * (gli stessi valori di sempre, già nella tendina di chi aggiorna) verso il
 * posto nuovo, il nome della scheda salvato da una versione vecchia, e la pila
 * delle pagine come testo (sopravvive a una rotazione e alla morte del
 * processo). Si prova con JUnit (NavigazioneTest).
 */
object Navigazione {

    /**
     * Il segnalibro di una notifica (o di "Apri Pactum") → dove si arriva. Un
     * valore che non si conosce (versione nuova del server, notifica vecchia)
     * apre Oggi: un tocco su una notifica non finisce mai nel vuoto.
     * `termina_sessione` apre Oggi: la conferma la apre la sessione in corso
     * (RichiestaTermine, chiesta da MainActivity).
     */
    fun ingresso(destinazione: String?): Ingresso = when (destinazione) {
        MainActivity.DEST_OGGI, MainActivity.DEST_TERMINA_SESSIONE -> Ingresso(Scheda.OGGI)
        MainActivity.DEST_REGOLE -> Ingresso(Scheda.REGOLE)
        // Le proposte del genitore sono in cima a Regole, in "Da decidere".
        MainActivity.DEST_PROPOSTE -> Ingresso(Scheda.REGOLE, inCima = true)
        // L'esito di una dichiarazione: lo Storico, sulle dichiarazioni, sopra Regole.
        MainActivity.DEST_DIARIO -> Ingresso(Scheda.REGOLE, pagina = Pagina.STORICO_DICHIARAZIONI)
        MainActivity.DEST_SESSIONI -> Ingresso(Scheda.SESSIONI)
        MainActivity.DEST_FACCENDE -> Ingresso(Scheda.LAVORI)
        else -> Ingresso(Scheda.OGGI)
    }

    /**
     * La scheda dal nome salvato (rememberSaveable). Una versione vecchia
     * salvava anche PROPOSTE, DIARIO e FACCENDE: diventano la scheda dove ora
     * sta quella cosa, e un nome che non si conosce torna a Oggi. Mai un crash
     * al ripristino.
     */
    fun schedaSalvata(nome: String?): Scheda = when (nome) {
        "PROPOSTE", "DIARIO" -> Scheda.REGOLE
        "FACCENDE" -> Scheda.LAVORI
        else -> Scheda.entries.firstOrNull { it.name == nome } ?: Scheda.OGGI
    }

    /** La pila delle pagine aperte, dal basso, come testo da salvare. */
    fun pilaInTesto(pila: List<Pagina>): String = pila.joinToString(",") { it.name }

    /** La pila dal testo salvato: le pagine che non si conoscono si saltano. */
    fun pilaDaTesto(testo: String?): List<Pagina> =
        testo.orEmpty().split(',').mapNotNull { nome -> Pagina.entries.firstOrNull { it.name == nome } }

    /**
     * Indietro di sistema: prima si chiude la pagina in cima; senza pagine, da
     * una scheda diversa da Oggi si torna a Oggi; da Oggi si esce (null).
     */
    fun indietro(scheda: Scheda, pila: List<Pagina>): Pair<Scheda, List<Pagina>>? = when {
        pila.isNotEmpty() -> scheda to pila.dropLast(1)
        scheda != Scheda.OGGI -> Scheda.OGGI to pila
        else -> null
    }
}
