package eu.stgm.pactum.figlio.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import eu.stgm.pactum.design.FormePactum
import eu.stgm.pactum.design.TipografiaPactum

// Verde del patto: l'app del figlio si distingue dal blu del genitore.
// Lo schema è COMPLETO di proposito: con il solo `primary` tutto il resto
// restava la baseline M3, che è lavanda — il FAB delle regole e i
// FilledTonalButton del bonus erano viola dentro un'app verde.
private val SchemaChiaro = lightColorScheme(
    primary = Color(0xFF1F6E5C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCFE9E1),
    onPrimaryContainer = Color(0xFF06382D),
    secondary = Color(0xFF4C635C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE9E4),
    onSecondaryContainer = Color(0xFF17332B),
    tertiary = Color(0xFF7A5C00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF3E3C4),
    onTertiaryContainer = Color(0xFF3A2C00),
    background = Color(0xFFFBFDFC),
    onBackground = Color(0xFF181D1B),
    surface = Color(0xFFFBFDFC),
    onSurface = Color(0xFF181D1B),
    surfaceVariant = Color(0xFFE1E7E4),
    onSurfaceVariant = Color(0xFF414944),
    outline = Color(0xFF717973),
    outlineVariant = Color(0xFFC1C9C4),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F8F6),
    surfaceContainer = Color(0xFFEEF3F1),
    surfaceContainerHigh = Color(0xFFE8EEEB),
    surfaceContainerHighest = Color(0xFFE2E9E6),
    // Gli inversi vestono la snackbar. Senza, restava la baseline M3: fondo
    // quasi nero e azione lavanda ("il nero è molto brutto", Andrea). Qui un
    // verde-ardesia scuro della stessa famiglia: testo 9,15:1, azione 6,85:1.
    inverseSurface = Color(0xFF2F4540),
    inverseOnSurface = Color(0xFFEEF3F1),
    inversePrimary = Color(0xFF9FE0CC),
)

// Niente schema scuro, di proposito: Pactum va sempre su fondo chiaro (scelta
// di Andrea). Per lo stesso motivo non c'è values-night: il tema di piattaforma
// resta chiaro anche col telefono in modalità scura, niente lampi neri all'avvio.

// Spazi, forme, tipografia e colori del patto sono in core-design, identici
// nelle due app. Qui resta solo ciò che dice di chi è l'app: la palette.

@Composable
fun PactumTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SchemaChiaro,  // sempre fondo chiaro (scelta di Andrea, 31/07)
        typography = TipografiaPactum,
        shapes = FormePactum,
        content = content,
    )
}
