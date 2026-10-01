package eu.stgm.pactum.figlio.misura

import android.content.Context
import eu.stgm.pactum.figlio.catalogo.CatalogoApp
import eu.stgm.pactum.figlio.valutatore.IndiceUso
import java.time.LocalDate

/**
 * L'uso di un giorno come lo contano tutti — la sentinella, la schermata Oggi
 * e la fotografia per il genitore: stesso filtro (CatalogoApp.filtroUso: fuori
 * la Home, i pezzi di sistema senza icona e le due app Pactum) e stesso totale,
 * che (0.9) durante il giorno non scende mai (TotaleDelGiorno). Così la barra
 * del figlio, lo sforamento e la finestra del padre contano gli stessi minuti.
 */
class UsoContato(
    val giorno: LocalDate,
    /** Solo le app che contano, dalla più usata. */
    val perApp: List<UsoApp>,
    /** Il `totale_minuti` della fotografia e il numero di "Tutto il telefono". */
    val totaleMinuti: Long,
    val indice: IndiceUso,
    /**
     * (0.11) I minuti del giorno passati in una Sessione nelle sue app, che
     * non contano (`sessioni_minuti` della fotografia). Zero vero nei giorni
     * senza sessioni; null quando il telefono non sa ancora quali sessioni ci
     * sono state (dopo una reinstallazione, finché il server non le riporta).
     */
    val sessioniMinuti: Long? = null,
) {
    companion object {

        /**
         * [inSessione] (0.11) = il tempo passato in sessione, per app; null se
         * non si sa. Passa dallo stesso [filtro]: la Home o Pactum in una
         * sessione non diventano minuti "in sessione".
         */
        fun di(
            context: Context,
            giorno: LocalDate,
            uso: List<UsoApp>,
            filtro: (String) -> Boolean,
            inSessione: List<UsoApp>? = null,
        ): UsoContato {
            val contati = uso.filter { filtro(it.pacchetto) }
            // Dai millisecondi veri, non dalla somma dei minuti arrotondati per
            // app (contratto-api.md: totale_minuti), e mai meno di prima.
            val totale = MemoriaTotale.almeno(context, giorno, contati.sumOf { it.millisPrimoPiano } / 60_000)
            val indice = IndiceUso(
                uso = contati.map { it.pacchetto to it.millisPrimoPiano },
                categoriaDi = { CatalogoApp.categoriaDiPacchetto(context, it) },
                totaleMinimo = totale,
            )
            return UsoContato(giorno, contati, totale, indice, minutiInSessione(inSessione, filtro))
        }

        /** L'uso da una lettura già fatta, col filtro di [filtro] (uno per giro). */
        fun di(
            context: Context,
            lettura: LetturaGiorno,
            filtro: (String) -> Boolean = CatalogoApp.filtroUso(context),
        ): UsoContato = di(context, lettura.giorno, lettura.perApp, filtro, inSessioneDi(lettura))

        /**
         * (0.11) Il tempo in sessione di una lettura, per app: null finché il
         * telefono non sa quali sessioni ci sono state (dopo una
         * reinstallazione, prima che il server le riporti).
         */
        fun inSessioneDi(lettura: LetturaGiorno): List<UsoApp>? =
            lettura.perAppInSessione.takeIf { lettura.sessioniNote }

        /** (0.11) I minuti in sessione: stesso [filtro] dell'uso, al massimo un giorno; null = non si sa. */
        fun minutiInSessione(inSessione: List<UsoApp>?, filtro: (String) -> Boolean): Long? =
            inSessione?.let { usi ->
                (usi.filter { filtro(it.pacchetto) }.sumOf { it.millisPrimoPiano } / 60_000).coerceAtMost(MINUTI_GIORNO)
            }

        /** I minuti in sessione stanno in un giorno (il contratto: da 0 a 1440). */
        private const val MINUTI_GIORNO = 1440L
    }
}
