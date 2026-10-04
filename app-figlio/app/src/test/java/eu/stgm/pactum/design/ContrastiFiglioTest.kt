package eu.stgm.pactum.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I contrasti VERI dei componenti comuni con i colori dell'app del figlio.
 * Il problema della 0.14: card normali e card "importanti" e pillole quasi dello
 * stesso colore (1,03:1), non si vedevano. Qui si misura che ora si vedono.
 *
 * Soglie (WCAG): testo ≥ 4,5:1; bordo di un controllo ≥ 3:1. Per due fondi
 * chiari affiancati (card in evidenza contro card bianca o pagina) non esiste
 * una soglia WCAG: si chiede almeno 1,2:1 di luminosità, più la tinta.
 */
class ContrastiFiglioTest {

    // Copiati da app-figlio/.../figlio/ui/theme/Theme.kt (lo schema lì è privato):
    // se cambia la palette, aggiornarli qui.
    private val schema: ColorScheme = lightColorScheme(
        primary = Color(0xFF1F6E5C),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFCFE9E1),
        onPrimaryContainer = Color(0xFF06382D),
        secondary = Color(0xFF4C635C),
        secondaryContainer = Color(0xFFDCE9E4),
        onSecondaryContainer = Color(0xFF17332B),
        tertiary = Color(0xFF7A5C00),
        tertiaryContainer = Color(0xFFF3E3C4),
        onTertiaryContainer = Color(0xFF3A2C00),
        background = Color(0xFFFBFDFC),
        surface = Color(0xFFFBFDFC),
        onSurface = Color(0xFF181D1B),
        surfaceVariant = Color(0xFFE1E7E4),
        onSurfaceVariant = Color(0xFF414944),
        outline = Color(0xFF717973),
        outlineVariant = Color(0xFFC1C9C4),
        error = Color(0xFFB3261E),
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF410E0B),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerHighest = Color(0xFFE2E9E6),
    )

    @Test
    fun `pillole righe di stato e card in evidenza si leggono e si vedono`() {
        verificaContrasti(schema)
    }
}

/** Le stesse verifiche per le due app (il genitore ha la sua copia, con la sua palette). */
internal fun verificaContrasti(schema: ColorScheme) {
    val pagina = schema.background
    val cardNormale = schema.surfaceContainerLowest
    val fondiEvidenza = Tono.entries.map { coloriEvidenza(it, schema).first }

    for (tono in Tono.entries) {
        val c = coloriTono(tono, schema)
        // Pillola e riga di stato: il testo sul suo fondo.
        controlla(contrasto(c.testo, c.fondo) >= 4.5, "testo $tono sul suo fondo", contrasto(c.testo, c.fondo))
        // Il pulsante di testo della riga di stato.
        val azione = coloreAzione(tono, schema)
        controlla(contrasto(azione, c.fondo) >= 4.5, "azione $tono sulla riga", contrasto(azione, c.fondo))
        // Il bordo della pillola si stacca da ogni fondo su cui può stare.
        for ((nome, fondo) in listOf("pagina" to pagina, "card normale" to cardNormale) +
            fondiEvidenza.mapIndexed { i, f -> "card evidenza ${Tono.entries[i]}" to f }) {
            val r = contrasto(c.pieno, fondo)
            controlla(r >= 3.0, "bordo pillola $tono su $nome", r)
        }
        // La card in evidenza: il suo testo, e lo stacco da pagina e card normale.
        val (fondoEv, testoEv) = coloriEvidenza(tono, schema)
        controlla(contrasto(testoEv, fondoEv) >= 4.5, "testo card evidenza $tono", contrasto(testoEv, fondoEv))
        controlla(contrasto(fondoEv, cardNormale) >= 1.2, "card evidenza $tono contro card normale", contrasto(fondoEv, cardNormale))
        controlla(contrasto(fondoEv, pagina) >= 1.2, "card evidenza $tono contro pagina", contrasto(fondoEv, pagina))
    }
    // La card normale (bianca) si stacca dalla pagina col suo bordo.
    val bordo = contrasto(schema.outlineVariant, cardNormale)
    controlla(bordo >= 1.4, "bordo card normale", bordo)
}

private fun controlla(ok: Boolean, cosa: String, valore: Double) {
    assertTrue("$cosa: contrasto %.2f:1 troppo basso".format(valore), ok)
}
