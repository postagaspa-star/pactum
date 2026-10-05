package eu.stgm.pactum.figlio.servizio

import eu.stgm.pactum.figlio.misura.Ripresa
import eu.stgm.pactum.figlio.valutatore.IndiceUso

/**
 * (0.16) Il controllo leggero del servizio, vicino al limite (logica pura).
 *
 * Il giro completo della sentinella legge l'uso di tutto il giorno: costa. Un
 * cambio di app però non sveglia il servizio, quindi un'app aperta DOPO un giro
 * potrebbe arrivare al limite fino a un minuto prima che lo si veda. Così,
 * quando una regola di tempo è vicina al limite o allo sforamento
 * (TempiFiniti.vicine: schermo acceso, regola usata oggi, meno di 2 minuti di
 * uso), fra un giro e l'altro ogni [PASSO_MS] si guardano SOLO gli eventi degli
 * ultimi secondi: se è arrivata davanti un'app che cade in una di quelle
 * regole, subito il giro completo (che programma da solo il momento esatto del
 * limite e dello sforamento). Un'app della regola già davanti all'ultimo giro
 * no: per lei vale già la programmazione esatta. Chi chiude Instagram a 30:10
 * costa solo queste letture minime.
 */
object ControlloLeggero {

    /** Ogni quanto il controllo leggero, nella finestra vicina. */
    const val PASSO_MS = 5_000L

    /**
     * Quanto si rilegge all'indietro: Android scrive un evento con un attimo
     * di ritardo e con l'orario di prima. Gli eventi già visti si scartano.
     */
    const val MARGINE_INDIETRO_MS = 3_000L

    /** La riga di log del controllo leggero: quando arrivano app, se no una volta al minuto. */
    const val LOG_OGNI_MS = 60_000L

    /**
     * La finestra da leggere [da, a): dall'ultimo controllo (o dalla lettura
     * del giro completo) meno [MARGINE_INDIETRO_MS], fino ad [adesso] compreso.
     */
    fun finestra(ultimo: Long, adesso: Long): Pair<Long, Long> = (ultimo - MARGINE_INDIETRO_MS) to (adesso + 1)

    /** Le app venute davanti non ancora viste, in ordine; e le riprese da ricordare per la finestra dopo. */
    data class Lette(val nuove: List<String>, val viste: Set<Ripresa>)

    /**
     * Dalle [riprese] della finestra quelle non ancora [viste] (la finestra si
     * sovrappone alla precedente), in ordine di orario. Si ricordano quelle
     * che la finestra dopo, che parte da [adesso] meno il margine, rileggerà.
     */
    fun nuove(riprese: List<Ripresa>, viste: Set<Ripresa>, adesso: Long): Lette {
        val nuove = riprese.filter { it !in viste }.sortedBy { it.istante }.map { it.pacchetto }
        val daRicordare = (viste + riprese).filter { it.istante >= adesso - MARGINE_INDIETRO_MS }.toSet()
        return Lette(nuove, daRicordare)
    }

    /**
     * La riga di log ([TempiLog]) del controllo leggero: quando sono arrivate
     * app o parte il giro completo, altrimenti una volta ogni [LOG_OGNI_MS]
     * (non ogni 5 secondi). [ultimoLog]/[adesso] sull'orologio che non si sposta.
     */
    fun daLoggare(arrivate: Boolean, giro: Boolean, ultimoLog: Long?, adesso: Long): Boolean =
        arrivate || giro || ultimoLog == null || adesso - ultimoLog >= LOG_OGNI_MS

    /**
     * Quanto aspettare adesso: un passo del controllo leggero se [vicino],
     * altrimenti tutto quello che resta fino al giro completo ([restaMs]).
     */
    fun passo(restaMs: Long, vicino: Boolean): Long = if (vicino) minOf(restaMs, PASSO_MS) else restaMs

    /** Le app arrivate davanti in una finestra, e quella davanti alla fine. */
    data class Arrivi(val arrivate: List<String>, val davanti: Set<String>)

    /**
     * Dalle riprese (i pacchetti con ACTIVITY_RESUMED, in ordine) partendo da
     * chi era davanti prima ([davantiPrima]): un pacchetto è "arrivato" se
     * prima davanti c'era altro. Un'app che resta davanti e cambia schermata
     * non arriva; una che va via e torna sì.
     */
    fun arrivi(riprese: List<String>, davantiPrima: Set<String>): Arrivi {
        var davanti = davantiPrima
        val arrivate = ArrayList<String>()
        for (pacchetto in riprese) {
            if (pacchetto !in davanti) arrivate += pacchetto
            davanti = setOf(pacchetto)
        }
        return Arrivi(arrivate.distinct(), davanti)
    }

    /**
     * Serve subito il giro completo? Sì se fra le app [arrivate] ce n'è una che
     * conta ([conta]: lo stesso filtro dell'uso, niente Home né Pactum) e cade
     * in una delle chiavi [vicine] ([cade]: app, categoria, "totale").
     */
    fun serveGiro(
        arrivate: List<String>,
        vicine: List<String>,
        conta: (String) -> Boolean,
        cade: (chiave: String, pacchetto: String) -> Boolean,
    ): Boolean {
        if (arrivate.isEmpty() || vicine.isEmpty()) return false
        return arrivate.any { pacchetto -> conta(pacchetto) && vicine.any { cade(it, pacchetto) } }
    }

    /** Il pacchetto cade nella chiave? Lo stesso confronto della sentinella (IndiceUso.cade). */
    fun cade(chiave: String, pacchetto: String, categoriaDi: (String) -> String): Boolean =
        IndiceUso(emptyList(), categoriaDi).cade(chiave, pacchetto)
}
