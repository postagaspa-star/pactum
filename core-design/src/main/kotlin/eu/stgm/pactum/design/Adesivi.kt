package eu.stgm.pactum.design

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// (0.19) Gli adesivi: le emoji col bordo bianco e un'ombra morbida delle pagine
// della Sessione (0.12), adesso in tutta l'app. Qui il disegno (uno per emoji e
// misura, tenuto da parte) e i due modi di mostrarli: il gruppetto accanto al
// titolo della barra ([GruppoAdesivi]) e l'adesivo grande delle pagine vuote
// ([AdesivoGrande]).

/**
 * Il disegno degli adesivi, fatto una volta sola per emoji e misura e tenuto
 * da parte (pochi: le emoji delle sezioni sono una trentina).
 */
object Adesivi {

    private val cache = LruCache<String, ImageBitmap>(64)

    /** L'adesivo di [emoji] in un quadrato di [lato] px, dal magazzino o disegnato adesso. */
    fun di(emoji: String, lato: Int): ImageBitmap? {
        if (lato <= 0) return null
        val chiave = "$emoji@$lato"
        cache.get(chiave)?.let { return it }
        val nuovo = runCatching { disegna(emoji, lato) }.getOrNull() ?: return null
        cache.put(chiave, nuovo)
        return nuovo
    }

    /**
     * Un adesivo: l'emoji con il bordo bianco e un'ombra morbida, disegnata in
     * un quadrato di [lato] px (l'emoji è poco più di metà del lato: il resto è
     * bordo, ombra e aria per il dondolio). Il bordo è l'emoji tinta di bianco e
     * ripetuta tutto intorno; l'ombra è quella sagoma sfocata, un po' più in basso.
     */
    fun disegna(emoji: String, lato: Int): ImageBitmap {
        val misura = lato * 0.56f
        val bordo = misura * 0.09f
        val pennello = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = misura
            textAlign = Paint.Align.CENTER
        }
        val cx = lato / 2f
        val cy = lato / 2f - (pennello.descent() + pennello.ascent()) / 2f

        val sagoma = Bitmap.createBitmap(lato, lato, Bitmap.Config.ARGB_8888)
        val bianco = Paint(pennello).apply {
            colorFilter = PorterDuffColorFilter(android.graphics.Color.WHITE, PorterDuff.Mode.SRC_IN)
        }
        android.graphics.Canvas(sagoma).apply {
            for (k in 0 until PASSI_BORDO) {
                val angolo = (2.0 * PI * k / PASSI_BORDO).toFloat()
                drawText(emoji, cx + cos(angolo) * bordo, cy + sin(angolo) * bordo, bianco)
            }
            drawText(emoji, cx, cy, bianco)
        }

        val sfocatura = Paint().apply { maskFilter = BlurMaskFilter(bordo * 1.5f, BlurMaskFilter.Blur.NORMAL) }
        val scarto = IntArray(2)
        val ombra = sagoma.extractAlpha(sfocatura, scarto)

        val adesivo = Bitmap.createBitmap(lato, lato, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(adesivo).apply {
            drawBitmap(ombra, scarto[0].toFloat(), scarto[1] + bordo * 0.8f, Paint().apply { color = COLORE_OMBRA })
            drawBitmap(sagoma, 0f, 0f, null)
            drawText(emoji, cx, cy, pennello)
        }
        ombra.recycle()
        sagoma.recycle()
        return adesivo.asImageBitmap()
    }

    private const val PASSI_BORDO = 16
    private const val COLORE_OMBRA = 0x47000000
}

/**
 * Il movimento di un gruppo di adesivi, in numeri (logica pura, la provano i
 * test): l'entrata a rimbalzo uno dopo l'altro, poi qualche dondolio che si
 * spegne piano. Niente movimento infinito: dopo [GiriGalleggio] giri gli
 * adesivi stanno fermi (la batteria ringrazia), e un tocco li fa ripartire.
 */
object MotoAdesivi {
    /** Quanto dura l'entrata di tutto il gruppo. */
    const val DURATA_ENTRATA_MS = 900

    /** Quanto dura il galleggiare, prima di fermarsi. */
    const val DURATA_GALLEGGIO_MS = 6_000

    /** Quanti dondolii in [DURATA_GALLEGGIO_MS]. */
    const val GiriGalleggio = 3

    /** Di quanto in ritardo entra ogni adesivo rispetto a quello prima (frazione dell'entrata). */
    private const val RITARDO = 0.22f

    /**
     * Quanto è entrato l'adesivo [indice] (0 fuori, 1 al suo posto, un po' oltre
     * nel rimbalzo) quando l'entrata del gruppo è a [t] (0..1).
     */
    fun entrata(t: Float, indice: Int): Float {
        val inizio = (indice * RITARDO).coerceAtMost(0.6f)
        val p = ((t - inizio) / (1f - inizio)).coerceIn(0f, 1f)
        return rimbalzo(p)
    }

    /** Un "easeOutBack": arriva, va un filo oltre e torna. */
    fun rimbalzo(p: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val q = p - 1f
        return 1f + c3 * q * q * q + c1 * q * q
    }

    /**
     * Lo spostamento in su (frazione dell'ampiezza, -1..1) dell'adesivo
     * [indice] a [g] (0..1) del galleggiare: un'onda che si spegne verso la fine.
     */
    fun galleggio(g: Float, indice: Int): Float {
        if (g <= 0f || g >= 1f) return 0f
        val onda = sin(g * GiriGalleggio * 2f * PI.toFloat() + indice * 1.3f)
        return onda * (1f - g)
    }
}

/**
 * Un gruppetto di adesivi uno accanto all'altro, un po' sovrapposti e storti
 * (come attaccati a mano): entrano con un rimbalzo uno dopo l'altro, dondolano
 * qualche secondo e si fermano. Un tocco li fa saltare di nuovo. Decorativi:
 * TalkBack non li legge. Con le animazioni tolte stanno fermi al loro posto.
 */
@Composable
fun GruppoAdesivi(emoji: List<String>, lato: Dp, modifier: Modifier = Modifier) {
    if (emoji.isEmpty()) return
    val latoPx = with(LocalDensity.current) { lato.roundToPx() }
    val immagini = remember(emoji, latoPx) { emoji.mapNotNull { Adesivi.di(it, latoPx) } }
    if (immagini.isEmpty()) return
    val vive = animazioniAttive()
    val entrata = remember { Animatable(if (vive) 0f else 1f) }
    val galleggio = remember { Animatable(0f) }
    var tocchi by remember { mutableIntStateOf(0) }
    LaunchedEffect(tocchi, vive) {
        if (!vive) return@LaunchedEffect
        coroutineScope {
            if (tocchi > 0) {
                entrata.snapTo(0.55f)
                galleggio.snapTo(0f)
            }
            launch { entrata.animateTo(1f, tween(MotoAdesivi.DURATA_ENTRATA_MS, easing = LinearEasing)) }
            launch {
                galleggio.snapTo(0f)
                galleggio.animateTo(1f, tween(MotoAdesivi.DURATA_GALLEGGIO_MS, easing = LinearEasing))
            }
        }
    }
    val passo = 0.74f
    val larghezza = lato * (passo * (immagini.size - 1) + 1f)
    Canvas(
        modifier = modifier
            .size(width = larghezza, height = lato)
            .pointerInput(vive) {
                if (vive) detectTapGestures { tocchi++ }
            },
    ) {
        disegnaGruppo(immagini, latoPx, passo, entrata.value, galleggio.value)
    }
}

private fun DrawScope.disegnaGruppo(immagini: List<ImageBitmap>, latoPx: Int, passo: Float, t: Float, g: Float) {
    val meta = latoPx / 2f
    val ampiezza = latoPx * 0.08f
    for (i in immagini.indices) {
        val p = MotoAdesivi.entrata(t, i)
        if (p <= 0f) continue
        val x = meta + i * latoPx * passo
        val y = size.height / 2f - MotoAdesivi.galleggio(g, i) * ampiezza
        val gradi = RotazioniGruppo[i % RotazioniGruppo.size] + MotoAdesivi.galleggio(g, i + 2) * 8f
        rotate(degrees = gradi, pivot = Offset(x, y)) {
            scale(scale = p.coerceAtLeast(0f), pivot = Offset(x, y)) {
                drawImage(
                    image = immagini[i],
                    dstOffset = IntOffset((x - meta).roundToInt(), (y - meta).roundToInt()),
                    dstSize = IntSize(latoPx, latoPx),
                    filterQuality = FilterQuality.Medium,
                )
            }
        }
    }
}

/** Le pendenze del gruppetto: attaccati a mano, mai dritti tutti uguali. */
private val RotazioniGruppo = floatArrayOf(-10f, 8f, -4f)

/**
 * Un adesivo grande da solo (le pagine vuote): entra con un rimbalzo, dondola
 * un poco e si ferma; si tocca e salta. Decorativo come [GruppoAdesivi].
 */
@Composable
fun AdesivoGrande(emoji: String, lato: Dp, modifier: Modifier = Modifier) =
    GruppoAdesivi(emoji = listOf(emoji), lato = lato, modifier = modifier)
