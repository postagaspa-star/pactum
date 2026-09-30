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
) {
    companion object {

        fun di(context: Context, giorno: LocalDate, uso: List<UsoApp>, filtro: (String) -> Boolean): UsoContato {
            val contati = uso.filter { filtro(it.pacchetto) }
            // Dai millisecondi veri, non dalla somma dei minuti arrotondati per
            // app (contratto-api.md: totale_minuti), e mai meno di prima.
            val totale = MemoriaTotale.almeno(context, giorno, contati.sumOf { it.millisPrimoPiano } / 60_000)
            val indice = IndiceUso(
                uso = contati.map { it.pacchetto to it.millisPrimoPiano },
                categoriaDi = { CatalogoApp.categoriaDiPacchetto(context, it) },
                totaleMinimo = totale,
            )
            return UsoContato(giorno, contati, totale, indice)
        }

        fun di(context: Context, lettura: LetturaGiorno): UsoContato =
            di(context, lettura.giorno, lettura.perApp, CatalogoApp.filtroUso(context))
    }
}
