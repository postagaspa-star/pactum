package eu.stgm.pactum.figlio.faccende

import eu.stgm.pactum.figlio.misura.Sessioni

/**
 * (0.13) Le app ancora VISIBILI sullo schermo, dagli eventi d'uso (logica
 * pura): una finestrella (picture-in-picture) o metà di uno schermo diviso
 * restano visibili anche quando davanti c'è un'altra app. Un'app è visibile
 * da quando una sua schermata riprende (ACTIVITY_RESUMED) finché non si ferma
 * (ACTIVITY_STOPPED, da Android 10) o non viene chiusa; una pausa non basta
 * (in una finestrella l'app è in pausa ma si vede). Schermo spento, blocco,
 * spegnimento o accensione: non si vede più niente.
 *
 * Per ogni app si sa da quando non è più quella davanti ([daCoprire]): la
 * barriera la copre solo se resta visibile così per qualche secondo (il
 * passaggio fra due app dura un attimo).
 */
class AppVisibili {

    private data class Schermata(val pacchetto: String, val classe: String?)

    /** Le schermate visibili, con da quando non sono più davanti (null = davanti adesso). */
    private val visibili = LinkedHashMap<Schermata, Long?>()
    private var istante = Long.MIN_VALUE

    fun evento(tipo: Int, pacchetto: String?, quando: Long, classe: String? = null) {
        if (quando < istante) return
        when (tipo) {
            Sessioni.RIPRESA -> {
                if (pacchetto.isNullOrBlank()) return
                istante = quando
                // Le altre app visibili non sono più quella davanti, da adesso.
                for (voce in visibili.entries) {
                    if (voce.key.pacchetto != pacchetto && voce.value == null) voce.setValue(quando)
                }
                // Le schermate della stessa app tornano davanti tutte insieme.
                for (voce in visibili.entries) if (voce.key.pacchetto == pacchetto) voce.setValue(null)
                visibili[Schermata(pacchetto, classe)] = null
            }
            Sessioni.STOP, CHIUSA -> {
                if (pacchetto.isNullOrBlank()) return
                istante = quando
                visibili.remove(Schermata(pacchetto, classe))
            }
            Sessioni.SCHERMO_SPENTO, Sessioni.BLOCCO, Sessioni.SPEGNIMENTO, Sessioni.ACCENSIONE -> {
                istante = quando
                visibili.clear()
            }
        }
    }

    /**
     * Le app visibili che non sono davanti ([davanti]) da almeno [attesa] ms
     * e che [daCoprire] dice di coprire.
     */
    fun daCoprire(adesso: Long, davanti: String?, attesa: Long, daCoprire: (pacchetto: String, classe: String?) -> Boolean): Set<String> {
        val trovate = LinkedHashSet<String>()
        for ((schermata, nonDavantiDa) in visibili) {
            if (schermata.pacchetto == davanti) continue
            val da = nonDavantiDa ?: continue
            if (adesso - da < attesa) continue
            if (schermata.pacchetto in trovate) continue
            if (daCoprire(schermata.pacchetto, schermata.classe)) trovate += schermata.pacchetto
        }
        return trovate
    }

    fun azzera() {
        visibili.clear()
        istante = Long.MIN_VALUE
    }

    companion object {
        /** ACTIVITY_DESTROYED (Android 10): la schermata è chiusa. */
        const val CHIUSA = 24
    }
}

/**
 * (0.13) Da dove rileggere gli eventi d'uso per sapere chi c'è davanti (logica
 * pura). All'inizio di ogni blocco, e dopo un errore, si riguarda una finestra
 * lunga: le ultime 24 ore, o dall'accensione se è più vicina. Un gioco o un
 * video aperto da più di 10 minuti si deve trovare lo stesso. Poi si riparte
 * da dove si era arrivati, con un po' di sovrapposizione; dopo uno schermo
 * spento bastano gli ultimi 10 minuti (allo sblocco l'app davanti riprende).
 */
object LetturaEventi {

    const val INDIETRO_LUNGO_MS = 24L * 60 * 60 * 1000
    const val INDIETRO_DOPO_SCHERMO_MS = 10L * 60 * 1000
    const val SOVRAPPOSIZIONE_MS = 5_000L

    fun inizio(daCapo: Boolean, lettoFinoA: Long?, adesso: Long, dallAccensione: Long): Long {
        val da = when {
            daCapo -> adesso - minOf(INDIETRO_LUNGO_MS, dallAccensione.coerceAtLeast(0L))
            lettoFinoA != null -> lettoFinoA - SOVRAPPOSIZIONE_MS
            else -> adesso - INDIETRO_DOPO_SCHERMO_MS
        }
        return da.coerceAtLeast(0L)
    }

    /** Oltre questo, un punto di lettura nel futuro vuol dire un orologio a muro spostato indietro. */
    const val SOVRAPPOSIZIONE_FUTURA_MS = 60_000L

    /**
     * (0.18) L'orologio a muro spostato indietro: il punto a cui si era
     * arrivati ([lettoFinoA]) è nel futuro. Si riparte da capo, con la
     * finestra lunga: altrimenti l'intervallo da leggere non è valido, nessun
     * evento arriva e la barriera crede che davanti ci sia ancora l'app di prima.
     */
    fun orologioIndietro(lettoFinoA: Long?, adesso: Long): Boolean =
        lettoFinoA != null && lettoFinoA > adesso + SOVRAPPOSIZIONE_FUTURA_MS
}

/**
 * (0.13) La copertura delle finestrelle (logica pura): si mostra se c'è
 * un'app da coprire ancora visibile, e non durante la pausa che il ragazzo
 * chiede per chiudere la finestrella ("Chiudi la finestrella"): senza la
 * pausa, la copertura sopra la finestrella non lascerebbe trascinarla via.
 */
object DecisioneFinestrelle {

    /** Quanto dura la pausa per chiudere la finestrella. */
    const val PAUSA_MS = 15_000L

    /** Quanto un'app deve restare visibile, non davanti, prima di coprirla. */
    const val ATTESA_MS = 3_000L

    fun mostra(daCoprire: Set<String>, monotono: Long, pausaFino: Long?): Boolean =
        daCoprire.isNotEmpty() && (pausaFino == null || monotono >= pausaFino)
}
