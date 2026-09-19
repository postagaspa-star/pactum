package eu.stgm.pactum.figlio.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
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
)

private val SchemaScuro = darkColorScheme(
    primary = Color(0xFF7FD3BD),
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF1C5245),
    onPrimaryContainer = Color(0xFFCFE9E1),
    secondary = Color(0xFFB1CCC3),
    onSecondary = Color(0xFF1D3529),
    secondaryContainer = Color(0xFF32493F),
    onSecondaryContainer = Color(0xFFDCE9E4),
    tertiary = Color(0xFFE7C77A),
    onTertiary = Color(0xFF3E2E00),
    tertiaryContainer = Color(0xFF4A3A00),
    onTertiaryContainer = Color(0xFFF3E3C4),
    background = Color(0xFF0F1513),
    onBackground = Color(0xFFE1E7E4),
    surface = Color(0xFF0F1513),
    onSurface = Color(0xFFE1E7E4),
    surfaceVariant = Color(0xFF414944),
    onSurfaceVariant = Color(0xFFC1C9C4),
    outline = Color(0xFF8B938D),
    outlineVariant = Color(0xFF414944),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF601410),
    onErrorContainer = Color(0xFFF9DEDC),
    surfaceContainerLowest = Color(0xFF0A0F0E),
    surfaceContainerLow = Color(0xFF161B19),
    surfaceContainer = Color(0xFF1A201E),
    surfaceContainerHigh = Color(0xFF242A28),
    surfaceContainerHighest = Color(0xFF2E3533),
)

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
