package eu.stgm.pactum.genitore.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Blu del binocolo: l'app del genitore si distingue dal verde del figlio.
private val SchemaChiaro = lightColorScheme(
    primary = Color(0xFF2C5D8F),
)

private val SchemaScuro = darkColorScheme(
    primary = Color(0xFF9CC7F2),
)

@Composable
fun PactumTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) SchemaScuro else SchemaChiaro,
        content = content,
    )
}
