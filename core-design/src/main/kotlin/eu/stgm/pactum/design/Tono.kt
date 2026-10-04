package eu.stgm.pactum.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Il "tono" di una cosa che si mostra: lo stesso per [RigaStato], [Pillola] e
 * [CardEvidenza], così "attenzione" ha lo stesso colore dappertutto.
 *
 * - [Neutro]: un fatto e basta (grigi della superficie).
 * - [Positivo]: va bene, è attivo (la famiglia `primary`: verde nel figlio, blu nel genitore).
 * - [Attenzione]: da guardare, non è un guaio (la famiglia `tertiary`, ocra).
 * - [Negativo]: qualcosa non va davvero (la famiglia `error`). Da usare poco.
 */
enum class Tono { Neutro, Positivo, Attenzione, Negativo }

/**
 * I tre colori di un tono:
 * - [fondo]: il chiaro, per il fondo di pillole e righe di stato;
 * - [pieno]: il colore deciso, per il bordo della pillola e le icone;
 * - [testo]: l'inchiostro da usare sopra [fondo] (contrasto ≥ 4,5:1 con i due temi).
 */
internal data class ColoriTono(val fondo: Color, val pieno: Color, val testo: Color)

/** I colori di [tono] presi da uno schema: funzione pura, la provano i test coi colori veri delle due app. */
internal fun coloriTono(tono: Tono, schema: ColorScheme): ColoriTono = when (tono) {
    Tono.Neutro -> ColoriTono(schema.surfaceVariant, schema.outline, schema.onSurfaceVariant)
    Tono.Positivo -> ColoriTono(schema.primaryContainer, schema.primary, schema.onPrimaryContainer)
    Tono.Attenzione -> ColoriTono(schema.tertiaryContainer, schema.tertiary, schema.onTertiaryContainer)
    Tono.Negativo -> ColoriTono(schema.errorContainer, schema.error, schema.onErrorContainer)
}

/**
 * Il colore del pulsante di testo dentro una [RigaStato] di quel tono. Per il
 * Neutro il grigio `outline` non basta per un testo (3,5:1): si usa `primary`.
 */
internal fun coloreAzione(tono: Tono, schema: ColorScheme): Color =
    if (tono == Tono.Neutro) schema.primary else coloriTono(tono, schema).pieno

/**
 * Fondo e inchiostro della [CardEvidenza]: i `…Container` pieni del tono. Il
 * Neutro qui è `secondaryContainer` (una tinta, non il grigio delle pillole),
 * altrimenti una card "importante" neutra sembrerebbe spenta.
 */
internal fun coloriEvidenza(tono: Tono, schema: ColorScheme): Pair<Color, Color> = when (tono) {
    Tono.Neutro -> schema.secondaryContainer to schema.onSecondaryContainer
    Tono.Positivo -> schema.primaryContainer to schema.onPrimaryContainer
    Tono.Attenzione -> schema.tertiaryContainer to schema.onTertiaryContainer
    Tono.Negativo -> schema.errorContainer to schema.onErrorContainer
}

@Composable
@ReadOnlyComposable
internal fun coloriTono(tono: Tono): ColoriTono = coloriTono(tono, MaterialTheme.colorScheme)

/**
 * Il rapporto di contrasto WCAG 2.x tra due colori (da 1 a 21). L'alfa si
 * ignora: i colori di Pactum sono tutti pieni.
 * Soglie: testo normale ≥ 4,5; testo grande e parti di un controllo ≥ 3.
 */
fun contrasto(a: Color, b: Color): Double {
    val la = luminanzaRelativa(a)
    val lb = luminanzaRelativa(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

private fun luminanzaRelativa(colore: Color): Double {
    val c = colore.convert(ColorSpaces.Srgb)
    fun lineare(canale: Float): Double {
        val v = canale.toDouble()
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * lineare(c.red) + 0.7152 * lineare(c.green) + 0.0722 * lineare(c.blue)
}
