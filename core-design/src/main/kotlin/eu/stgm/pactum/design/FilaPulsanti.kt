package eu.stgm.pactum.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

/**
 * I pulsanti uno accanto all'altro, ognuno largo quanto il suo testo su UNA riga.
 * Se non ci stanno tutti nella larghezza disponibile (telefono stretto, testo
 * grande), vanno uno sotto l'altro, ognuno a tutta larghezza: mai testo a capo
 * dentro un pulsante, mai pulsanti tagliati.
 *
 * Quando usarla: ogni volta che ci sono 2 o più pulsanti insieme (in fondo a una
 * schermata, in un dialogo fatto a mano, sotto un modulo). Su una card ricorda:
 * al massimo UN pulsante visibile, il resto nel [MenuAzioni].
 *
 * In fila i pulsanti partono da sinistra; l'ordine è quello del contenuto (in
 * colonna, il primo sta in alto: metti per primo quello principale).
 */
@Composable
fun FilaPulsanti(
    modifier: Modifier = Modifier,
    spazio: Dp = Spazi.s,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { misurabili, vincoli ->
        if (misurabili.isEmpty()) return@Layout layout(vincoli.minWidth, vincoli.minHeight) {}
        val spazioPx = spazio.roundToPx()
        // La larghezza "naturale" = quella col testo tutto su una riga.
        val naturali = misurabili.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val disponibile = if (vincoli.hasBoundedWidth) vincoli.maxWidth else Int.MAX_VALUE

        if (serveColonna(naturali, spazioPx, disponibile)) {
            val larghezza = if (vincoli.hasBoundedWidth) vincoli.maxWidth else naturali.max()
            val piazzabili = misurabili.map { it.measure(Constraints.fixedWidth(larghezza)) }
            val altezza = piazzabili.sumOf { it.height } + spazioPx * (piazzabili.size - 1)
            layout(larghezza, altezza.coerceIn(vincoli.minHeight, vincoli.maxHeight)) {
                var y = 0
                piazzabili.forEach { p ->
                    p.placeRelative(0, y)
                    y += p.height + spazioPx
                }
            }
        } else {
            val piazzabili = misurabili.mapIndexed { i, m ->
                m.measure(Constraints(minWidth = naturali[i], maxWidth = naturali[i]))
            }
            val altezza = piazzabili.maxOf { it.height }
            val larghezza = piazzabili.sumOf { it.width } + spazioPx * (piazzabili.size - 1)
            layout(
                larghezza.coerceIn(vincoli.minWidth, vincoli.maxWidth),
                altezza.coerceIn(vincoli.minHeight, vincoli.maxHeight),
            ) {
                var x = 0
                piazzabili.forEach { p ->
                    p.placeRelative(x, (altezza - p.height) / 2)
                    x += p.width + spazioPx
                }
            }
        }
    }
}

/**
 * True = i pulsanti vanno in colonna: le loro larghezze naturali, più gli
 * spazi tra uno e l'altro, superano la larghezza [disponibile]. Lista vuota = no.
 */
internal fun serveColonna(larghezzeNaturali: List<Int>, spazio: Int, disponibile: Int): Boolean {
    if (larghezzeNaturali.isEmpty()) return false
    val totale = larghezzeNaturali.sumOf { it.toLong() } + spazio.toLong() * (larghezzeNaturali.size - 1)
    return totale > disponibile
}

/**
 * True = nella [RigaStato] il pulsante va SOTTO il testo invece che a destra:
 * succede quando il pulsante (più lo spazio) si mangerebbe più del 45% della
 * riga, e al testo resterebbe una colonnina stretta che va a capo a ogni parola.
 */
internal fun azioneSotto(larghezzaPulsante: Int, spazio: Int, disponibile: Int): Boolean =
    (larghezzaPulsante + spazio) > disponibile * QuotaMassimaAzione

/** La parte della riga che il pulsante di una [RigaStato] può prendersi a destra. */
private const val QuotaMassimaAzione = 0.45f
