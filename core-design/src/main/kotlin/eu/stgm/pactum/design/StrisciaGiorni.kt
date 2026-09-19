package eu.stgm.pactum.design

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spazio attorno a ogni quadretto, per lato: è il posto dell'anello di oggi. */
private val RiservaAnello = 4.dp
private val SpessoreAnello = 2.dp
private val SpessoreBordoVuoto = 1.dp

/**
 * La striscia dei giorni del patto, dal più vecchio a oggi (oggi in coda).
 * Stesso composable, stessa misura, nelle due app: è un fatto, e i fatti si
 * mostrano identici (tavola rotonda D3/C2).
 *
 * - `lato` 32 = la striscia protagonista in cima, col numero del giorno DENTRO
 *   il quadretto; 20 = il dettaglio dentro la scheda di una regola, senza numeri.
 * - Oggi è un ANELLO `primary`, non una parola: si disegna nello spazio che ogni
 *   cella riserva comunque (lato + 8), così la fila non si deforma in coda.
 * - NESSUN_DATO è vuoto: `surfaceVariant` + bordo `outline`. Un "non lo so" deve
 *   sembrare assente, non guasto.
 * - Se la larghezza non basta, tutte le celle si stringono della stessa misura
 *   (e il raggio con loro) invece di uscire dallo schermo. Vedi [misuraStriscia].
 *
 * `descrizione` è la frase che legge TalkBack al posto dei singoli numeri
 * (es. "6 giorni su 7 dentro le regole"): arriva dall'app, perché qui dentro
 * non si toccano le risorse.
 */
@Composable
fun StrisciaGiorni(
    giorni: List<GiornoPatto>,
    lato: Dp = 32.dp,
    mostraNumero: Boolean = lato >= 32.dp,
    modifier: Modifier = Modifier,
    descrizione: String? = null,
) {
    if (giorni.isEmpty()) return
    // Raggio in proporzione al lato: 10 su 32, 6 su 20. Quando la striscia si
    // stringe, l'arrotondamento resta lo stesso a occhio.
    val raggioRelativo = if (lato >= 32.dp) 10f / 32f else 6f / 20f
    val semantica = if (descrizione != null) {
        Modifier.clearAndSetSemantics { contentDescription = descrizione }
    } else {
        Modifier
    }

    Layout(
        content = {
            giorni.forEachIndexed { indice, giorno ->
                Quadretto(
                    giorno = giorno,
                    oggi = indice == giorni.lastIndex,
                    raggioRelativo = raggioRelativo,
                    mostraNumero = mostraNumero,
                )
            }
        },
        modifier = modifier.then(semantica),
    ) { celle, vincoli ->
        val spazio = Spazi.xs.roundToPx()
        val cella = misuraStriscia(
            celle = celle.size,
            cellaNaturale = (lato + RiservaAnello * 2).roundToPx(),
            spazio = spazio,
            larghezzaMassima = if (vincoli.hasBoundedWidth) vincoli.maxWidth else null,
        )
        val fisse = Constraints.fixed(cella, cella)
        val piazzabili = celle.map { it.measure(fisse) }
        val larghezza = (cella * celle.size + spazio * (celle.size - 1))
            .coerceIn(vincoli.minWidth, vincoli.maxWidth)
        layout(larghezza, cella.coerceIn(vincoli.minHeight, vincoli.maxHeight)) {
            piazzabili.forEachIndexed { i, p -> p.placeRelative(i * (cella + spazio), 0) }
        }
    }
}

/**
 * Il lato di ogni cella (in px): quello di progetto se ci sta, altrimenti la
 * larghezza disponibile divisa in parti uguali. Mai negativo.
 */
internal fun misuraStriscia(
    celle: Int,
    cellaNaturale: Int,
    spazio: Int,
    larghezzaMassima: Int?,
): Int {
    if (celle <= 0) return 0
    if (larghezzaMassima == null) return cellaNaturale
    val disponibile = (larghezzaMassima - spazio * (celle - 1)) / celle
    return minOf(cellaNaturale, disponibile).coerceAtLeast(0)
}

@Composable
private fun Quadretto(
    giorno: GiornoPatto,
    oggi: Boolean,
    raggioRelativo: Float,
    mostraNumero: Boolean,
) {
    val schema = MaterialTheme.colorScheme
    val pieno = when (giorno.segnale) {
        Segnale.MANTENUTA -> ColoriPatto.Mantenuta
        Segnale.FUORI_REGOLA -> ColoriPatto.FuoriRegola
        Segnale.NESSUN_DATO -> schema.surfaceVariant
    }
    val bordoVuoto = schema.outline
    val anello = schema.primary

    Box(
        modifier = Modifier.drawBehind {
            val riserva = RiservaAnello.toPx()
            val latoQuadretto = (size.minDimension - riserva * 2).coerceAtLeast(0f)
            val raggio = latoQuadretto * raggioRelativo
            drawRoundRect(
                color = pieno,
                topLeft = Offset(riserva, riserva),
                size = Size(latoQuadretto, latoQuadretto),
                cornerRadius = CornerRadius(raggio),
            )
            // I tratti si disegnano a cavallo della linea: si rientra di mezzo
            // spessore perché restino dentro la loro sagoma, come fa border().
            if (giorno.segnale == Segnale.NESSUN_DATO) {
                val tratto = SpessoreBordoVuoto.toPx()
                drawRoundRect(
                    color = bordoVuoto,
                    topLeft = Offset(riserva + tratto / 2, riserva + tratto / 2),
                    size = Size(latoQuadretto - tratto, latoQuadretto - tratto),
                    cornerRadius = CornerRadius((raggio - tratto / 2).coerceAtLeast(0f)),
                    style = Stroke(tratto),
                )
            }
            if (oggi) {
                // Concentrico al quadretto: raggio del quadretto + la riserva.
                val tratto = SpessoreAnello.toPx()
                drawRoundRect(
                    color = anello,
                    topLeft = Offset(tratto / 2, tratto / 2),
                    size = Size(size.width - tratto, size.height - tratto),
                    cornerRadius = CornerRadius(raggio + riserva - tratto / 2),
                    style = Stroke(tratto),
                )
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        if (mostraNumero && giorno.segnale != Segnale.NESSUN_DATO) {
            Text(
                // Il giorno del mese: "2026-07-14" → "14".
                text = giorno.data.takeLast(2),
                style = MaterialTheme.typography.labelMedium,
                color = if (giorno.segnale == Segnale.MANTENUTA) {
                    ColoriPatto.InchiostroSuMantenuta
                } else {
                    ColoriPatto.InchiostroSuFuoriRegola
                },
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
