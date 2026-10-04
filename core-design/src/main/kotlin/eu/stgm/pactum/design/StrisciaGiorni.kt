package eu.stgm.pactum.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
 * - (0.15) Tra una cella e l'altra non c'è altro spazio: la riserva dell'anello
 *   (4 per lato) stacca già i quadretti di 8. Così 8 giorni da 32 stanno in
 *   320 dp (prima ne servivano 348 e la striscia si stringeva quasi sempre).
 * - (0.15) Il numero del giorno si misura sul quadretto VERO (dopo che si è
 *   stretto) e non cresce col testo grande di sistema: non esce mai dal
 *   quadretto. Vedi [misuraNumeroGiorno]. Per spiegare i colori: [LegendaStriscia].
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
        // Niente spazio in più tra le celle: lo fa già la riserva dell'anello.
        val spazio = 0
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
    val conNumero = mostraNumero && giorno.segnale != Segnale.NESSUN_DATO
    // Il giorno del mese: "2026-07-14" → "14".
    val numero = giorno.data.takeLast(2)
    val inchiostro = if (giorno.segnale == Segnale.MANTENUTA) {
        ColoriPatto.InchiostroSuMantenuta
    } else {
        ColoriPatto.InchiostroSuFuoriRegola
    }
    val stileNumero = MaterialTheme.typography.labelMedium
    val misuratore = rememberTextMeasurer()
    // (0.15) Il numero non è più un Text: si disegna qui sotto, sul lato VERO
    // del quadretto. TalkBack lo legge lo stesso (se la striscia non ha già una
    // `descrizione`, che copre tutto).
    val semantica = if (conNumero) Modifier.semantics { contentDescription = numero } else Modifier

    Box(
        modifier = semantica.drawBehind {
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
            if (conNumero) {
                val misura = misuraNumeroGiorno(latoQuadretto.toDp().value, fontScale)
                if (misura > 0f) {
                    val testo = misuratore.measure(
                        text = numero,
                        style = stileNumero.copy(
                            color = inchiostro,
                            fontSize = misura.sp,
                            lineHeight = (misura * InterlineaNumero).sp,
                            letterSpacing = 0.sp,
                        ),
                        maxLines = 1,
                        softWrap = false,
                    )
                    drawText(
                        testo,
                        topLeft = Offset(
                            (size.width - testo.size.width) / 2f,
                            (size.height - testo.size.height) / 2f,
                        ),
                    )
                }
            }
        },
    )
}

/** Sotto questo lato (dp) il numero non si leggerebbe: il quadretto resta muto. */
private const val LatoMinimoNumero = 16f

/** Il numero alto al massimo così (dp): come il labelMedium a testo normale. */
private const val NumeroMassimoDp = 12f

/** Quanto del lato del quadretto può prendersi il numero (come misura del carattere). */
private const val QuotaNumero = 0.4f

/** L'interlinea del numero rispetto alla sua misura. */
private const val InterlineaNumero = 1.2f

/**
 * La misura del numero del giorno, in sp, per un quadretto di lato [latoDp]
 * (quello vero, dopo che la striscia si è stretta). Il numero è grande al
 * massimo il 40% del lato e mai più di 12 dp; si divide per [fontScale] perché
 * il testo grande di sistema NON lo deve ingrandire: il quadretto non cresce, e
 * il numero ne uscirebbe. 0 = quadretto troppo piccolo, niente numero.
 */
internal fun misuraNumeroGiorno(latoDp: Float, fontScale: Float): Float {
    if (latoDp < LatoMinimoNumero) return 0f
    val dp = (latoDp * QuotaNumero).coerceAtMost(NumeroMassimoDp)
    return dp / fontScale.coerceAtLeast(0.5f)
}

/** Il quadretto della legenda: piccolo, come un segno nel testo. */
private val LatoLegenda = 12.dp

/**
 * La legenda della [StrisciaGiorni]: quattro voci (quadretto del colore +
 * parola) su una riga, che va a capo da sola se il posto non basta.
 * "Oggi" è l'anello. Le parole arrivano dall'app (es. "Mantenuta",
 * "Fuori regola", "Senza dati", "Oggi").
 *
 * Quando usarla: una volta sola, sotto la striscia grande, dove la si vede per
 * la prima volta (non sotto ogni striscia piccola).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LegendaStriscia(
    mantenuta: String,
    fuoriRegola: String,
    senzaDati: String,
    oggi: String,
    modifier: Modifier = Modifier,
) {
    val schema = MaterialTheme.colorScheme
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Spazi.m),
        verticalArrangement = Arrangement.spacedBy(Spazi.xs),
    ) {
        VoceLegenda(mantenuta) { Riquadro(pieno = ColoriPatto.Mantenuta) }
        VoceLegenda(fuoriRegola) { Riquadro(pieno = ColoriPatto.FuoriRegola) }
        VoceLegenda(senzaDati) { Riquadro(pieno = schema.surfaceVariant, bordo = schema.outline) }
        VoceLegenda(oggi) { Riquadro(pieno = null, bordo = schema.primary, spessore = SpessoreAnello) }
    }
}

@Composable
private fun VoceLegenda(parola: String, segno: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        segno()
        Text(
            text = parola,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spazi.s),
            maxLines = 1,
        )
    }
}

@Composable
private fun Riquadro(pieno: Color?, bordo: Color? = null, spessore: Dp = SpessoreBordoVuoto) {
    Canvas(Modifier.size(LatoLegenda)) {
        // Stesso arrotondamento dei quadretti piccoli della striscia (6 su 20).
        val raggio = CornerRadius(size.minDimension * (6f / 20f))
        if (pieno != null) drawRoundRect(color = pieno, cornerRadius = raggio)
        if (bordo != null) {
            val tratto = spessore.toPx()
            drawRoundRect(
                color = bordo,
                topLeft = Offset(tratto / 2, tratto / 2),
                size = Size(size.width - tratto, size.height - tratto),
                cornerRadius = raggio,
                style = Stroke(tratto),
            )
        }
    }
}
