package eu.stgm.pactum.genitore.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import eu.stgm.pactum.design.FormePactum
import eu.stgm.pactum.design.TipografiaPactum

// Blu del binocolo: l'app del genitore si distingue dal verde del figlio.
// Lo schema è COMPLETO di proposito: con il solo `primary` tutto il resto
// restava la baseline M3, che è lavanda — FAB, chip, FilledTonalButton e
// FilterChip selezionati erano viola dentro un'app blu.
private val SchemaChiaro = lightColorScheme(
    primary = Color(0xFF2C5D8F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD5E3F5),
    onPrimaryContainer = Color(0xFF10395E),
    secondary = Color(0xFF4C6379),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDDE4EC),
    onSecondaryContainer = Color(0xFF1B2C3A),
    tertiary = Color(0xFF7A5C00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF3E3C4),
    onTertiaryContainer = Color(0xFF3A2C00),
    background = Color(0xFFFBFCFD),
    onBackground = Color(0xFF191C1E),
    surface = Color(0xFFFBFCFD),
    onSurface = Color(0xFF191C1E),
    surfaceVariant = Color(0xFFE2E7EC),
    onSurfaceVariant = Color(0xFF43484D),
    outline = Color(0xFF73787D),
    outlineVariant = Color(0xFFC3C8CD),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F7FA),
    surfaceContainer = Color(0xFFEFF2F6),
    surfaceContainerHigh = Color(0xFFE9EDF2),
    surfaceContainerHighest = Color(0xFFE3E8EE),
    // I colori "inversi" vestono le snackbar (la conferma di "Manda un segno").
    // Lasciati alla baseline M3 erano un fondo quasi nero con l'azione lavanda:
    // qui un blu ardesia della stessa famiglia del binocolo, testo chiaro
    // (8,86:1) e l'azione nel blu chiaro del primary scuro (5,59:1).
    inverseSurface = Color(0xFF2B4560),
    inverseOnSurface = Color(0xFFEEF3F8),
    inversePrimary = Color(0xFF9CC7F2),
)

// Nessuno schema scuro: Pactum va solo su fondo chiaro (scelta di Andrea).

// Spazi, forme, tipografia, colori del patto (`ColoriPatto`) e (0.16) colori
// delle categorie d'uso (`ColoriCategorie`) sono in core-design, identici nelle
// due app. Qui resta solo ciò che è del genitore: la palette blu.

/** Pactum va sempre su fondo chiaro: la finestra è un referto, si legge su carta
 *  bianca. Su fondo nero i colori del patto perdono il loro significato e l'app
 *  sembra un pannello di diagnostica (scelta di Andrea: niente tema scuro). */
@Composable
fun PactumTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SchemaChiaro,
        typography = TipografiaPactum,
        shapes = FormePactum,
        content = content,
    )
}
