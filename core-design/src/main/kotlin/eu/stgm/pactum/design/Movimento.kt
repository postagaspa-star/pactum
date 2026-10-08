package eu.stgm.pactum.design

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// (0.19) I movimenti dell'app più viva: le card che entrano a cascata, le cose
// toccabili che si schiacciano e rimbalzano, i grafici che si riempiono e i
// numeri che salgono contando. Tutti brevi, tutti fermi con "Rimuovi
// animazioni" (v. animazioniAttive), e nessuno infinito.

/** I numeri dei movimenti (logica pura, li provano i test). */
object Movimento {
    /** Le card che nascono entro questo tempo dalla comparsa della schermata entrano; dopo, compaiono e basta. */
    const val FINESTRA_ENTRATA_MS = 900L

    /** Quanto dura l'entrata di una card. */
    const val DURATA_ENTRATA_MS = 380

    /** Il ritardo massimo della cascata (la card più in basso dello schermo). */
    const val RITARDO_MASSIMO_MS = 280L

    /** Quanto si rimpicciolisce una cosa premuta. */
    const val SCALA_PREMUTO = 0.965f

    /** Quanto dura il riempirsi di grafici e numeri. */
    const val DURATA_RIEMPIMENTO_MS = 700

    /**
     * Il ritardo della cascata per una card che sta a [y] px dall'alto, su
     * uno schermo alto [altezza] px: più è in basso, più arriva tardi.
     */
    fun ritardoCascata(y: Float, altezza: Float): Long {
        if (altezza <= 0f) return 0L
        val frazione = (y / altezza).coerceIn(0f, 1f)
        return (frazione * RITARDO_MASSIMO_MS).roundToInt().toLong()
    }

    /** true = la card nata a [nascita] entra (è nata nei primi istanti della schermata comparsa a [inizio]). */
    fun entra(inizio: Long?, nascita: Long): Boolean =
        inizio != null && nascita - inizio in 0..FINESTRA_ENTRATA_MS

    /**
     * Quanto è cresciuta la colonna [indice] di [quante] quando il grafico è a
     * [p] (0..1): da sinistra a destra, ognuna un poco dopo quella prima.
     */
    fun crescitaColonna(p: Float, indice: Int, quante: Int): Float {
        if (quante <= 1) return p.coerceIn(0f, 1f)
        val partenza = 0.4f * indice / (quante - 1)
        return ((p - partenza) / 0.6f).coerceIn(0f, 1f)
    }

    /** Il numero che si vede a [p] (0..1) del conteggio da [da] ad [a]. */
    fun conteggio(da: Long, a: Long, p: Float): Long = da + ((a - da) * p.coerceIn(0f, 1f)).roundToInt()
}

/**
 * Una card (o un blocco) che entra: sale di poco e si accende, un po' dopo
 * quelle sopra di lei. Solo dentro una [SchermataColorata] e solo per le card
 * nate appena la schermata è comparsa: quelle che arrivano scorrendo
 * compaiono subito, senza farsi aspettare.
 */
@Composable
fun Modifier.entrata(): Modifier {
    val inizio = LocalInizioSchermata.current
    val vive = animazioniAttive()
    val entra = remember { vive && Movimento.entra(inizio, SystemClock.uptimeMillis()) }
    if (!entra) return this
    val progresso = remember { Animatable(0f) }
    var ritardo by remember { mutableStateOf<Long?>(null) }
    val altezza = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val sposta = with(LocalDensity.current) { 18.dp.toPx() }
    LaunchedEffect(ritardo) {
        val attesa = ritardo ?: return@LaunchedEffect
        delay(attesa)
        progresso.animateTo(1f, tween(Movimento.DURATA_ENTRATA_MS, easing = FastOutSlowInEasing))
    }
    return this
        .onPlaced { coordinate ->
            if (ritardo == null) ritardo = Movimento.ritardoCascata(coordinate.positionInRoot().y, altezza)
        }
        .graphicsLayer {
            val p = progresso.value
            alpha = p
            translationY = (1f - p) * sposta
        }
}

/**
 * Una cosa toccabile che, premuta, si schiaccia un poco e al rilascio torna
 * su con un piccolo rimbalzo. [sorgente] è quella del `clickable`/`Surface`.
 */
@Composable
fun Modifier.rimbalzoTocco(sorgente: InteractionSource): Modifier {
    if (!animazioniAttive()) return this
    val premuto by sorgente.collectIsPressedAsState()
    val scala by animateFloatAsState(
        targetValue = if (premuto) Movimento.SCALA_PREMUTO else 1f,
        animationSpec = spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMedium),
        label = "rimbalzo",
    )
    return graphicsLayer {
        scaleX = scala
        scaleY = scala
    }
}

/**
 * Da 0 a 1 la prima volta che compare (grafici che si riempiono), poi resta a
 * 1. Con le animazioni tolte è subito 1.
 */
@Composable
fun rememberComparsa(ritardoMs: Long = 0L): Float {
    val vive = animazioniAttive()
    val valore = remember { Animatable(if (vive) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (!vive) return@LaunchedEffect
        if (ritardoMs > 0) delay(ritardoMs)
        valore.animateTo(1f, tween(Movimento.DURATA_RIEMPIMENTO_MS, easing = FastOutSlowInEasing))
    }
    return valore.value
}

/**
 * Un numero che sale contando fino a [valore]: la prima volta da 0, poi dal
 * numero di prima al nuovo. Con le animazioni tolte è subito il valore.
 */
@Composable
fun rememberContatore(valore: Long): Long {
    val vive = animazioniAttive()
    val da = remember { mutableStateOf(if (vive) 0L else valore) }
    val p = remember { Animatable(1f) }
    var mostrato by remember { mutableStateOf(if (vive) 0L else valore) }
    LaunchedEffect(valore) {
        if (!vive) {
            mostrato = valore
            return@LaunchedEffect
        }
        da.value = mostrato
        p.snapTo(0f)
        p.animateTo(1f, tween(Movimento.DURATA_RIEMPIMENTO_MS, easing = FastOutSlowInEasing)) {
            mostrato = Movimento.conteggio(da.value, valore, this.value)
        }
        mostrato = valore
    }
    return mostrato
}

/** Come [rememberContatore], per un intero. */
@Composable
fun rememberContatore(valore: Int): Int = rememberContatore(valore.toLong()).toInt()
