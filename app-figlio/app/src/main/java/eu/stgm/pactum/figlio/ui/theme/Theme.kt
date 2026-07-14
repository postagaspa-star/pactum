package eu.stgm.pactum.figlio.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SchemaChiaro = lightColorScheme(
    primary = Color(0xFF1F6E5C),
)

private val SchemaScuro = darkColorScheme(
    primary = Color(0xFF7FD3BD),
)

@Composable
fun PactumTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) SchemaScuro else SchemaChiaro,
        content = content,
    )
}
