package eu.stgm.pactum.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// (0.19) L'anello dello Studio: si riempie verso il minimo di attività (un'ora),
// a colori, un colore per tipo di attività. Così si vede a colpo d'occhio
// quanto manca e come è fatto il tempo segnato.

/** I colori dei tipi di attività dello Studio: si leggono sul fondo chiaro delle card (≥ 3:1). */
object ColoriAttivita {
    val Compiti = Color(0xFF2F6DAE)
    val LavoriDiCasa = Color(0xFF4E7F1E)
    val Altro = Color(0xFFB45A24)
}

/** Una fetta dell'anello: il suo [colore] e il suo peso (secondi, minuti: conta solo la proporzione). */
data class FettaAttivita(val colore: Color, val peso: Long)

/**
 * Le fette in gradi (logica pura, la provano i test): l'anello arriva a
 * [progresso] (0..1) del giro, diviso fra le [fette] in proporzione al loro
 * peso. Fette senza peso non ci sono.
 */
fun gradiAttivita(fette: List<FettaAttivita>, progresso: Float): List<Pair<Color, Float>> {
    val totale = fette.sumOf { it.peso.coerceAtLeast(0) }
    if (totale <= 0) return emptyList()
    val giro = 360f * progresso.coerceIn(0f, 1f)
    return fette.filter { it.peso > 0 }.map { it.colore to giro * it.peso / totale }
}

/**
 * L'anello: il binario grigio e sopra le fette colorate fino al [progresso]
 * (0..1, oltre 1 resta pieno). Quando compare gira fino al suo punto, e
 * quando il tempo cresce avanza piano. Nel mezzo, [centro].
 */
@Composable
fun AnelloAttivita(
    fette: List<FettaAttivita>,
    progresso: Float,
    modifier: Modifier = Modifier,
    diametro: Dp = 120.dp,
    spessore: Dp = 14.dp,
    centro: @Composable BoxScope.() -> Unit = {},
) {
    val binario = MaterialTheme.colorScheme.surfaceVariant
    val comparsa = rememberComparsa()
    val vive = animazioniAttive()
    val arrivo by animateFloatAsState(
        targetValue = progresso.coerceIn(0f, 1f),
        animationSpec = if (vive) tween(Movimento.DURATA_RIEMPIMENTO_MS) else tween(0),
        label = "anello attività",
    )
    Box(modifier = modifier.size(diametro), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val penna = spessore.toPx()
            val lato = size.minDimension - penna
            if (lato <= 0f) return@Canvas
            val angolo = Offset(penna / 2f, penna / 2f)
            val misura = Size(lato, lato)
            drawArc(binario, -90f, 360f, false, angolo, misura, style = Stroke(penna))
            var inizio = -90f
            val fetteGradi = gradiAttivita(fette, arrivo * comparsa)
            fetteGradi.forEachIndexed { i, (colore, gradi) ->
                if (gradi <= 0.5f) return@forEachIndexed
                // Il cappuccio tondo solo all'ultima fetta, la punta che avanza.
                val ultima = i == fetteGradi.lastIndex
                drawArc(
                    color = colore,
                    startAngle = inizio,
                    sweepAngle = gradi,
                    useCenter = false,
                    topLeft = angolo,
                    size = misura,
                    style = Stroke(penna, cap = if (ultima && gradi < 359f) StrokeCap.Round else StrokeCap.Butt),
                )
                inizio += gradi
            }
        }
        centro()
    }
}
