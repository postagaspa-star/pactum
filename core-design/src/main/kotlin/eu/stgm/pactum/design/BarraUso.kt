package eu.stgm.pactum.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * La barra del tempo d'uso di un'app, senza librerie.
 *
 * Scala: con un `limite` la barra è SUL limite, l'unica scala che il ragazzo si
 * è dato; senza, sull'app più usata del giorno (`massimoDelGiorno`).
 * Oltre il limite la barra resta piena e `primary`: MAI terracotta, il colore
 * del patto vive solo nella striscia. L'eccedenza si dice a parole, fuori da qui.
 */
@Composable
fun BarraUso(
    minuti: Int,
    limite: Int?,
    massimoDelGiorno: Int,
    modifier: Modifier = Modifier,
) {
    val denominatore = (limite ?: massimoDelGiorno).coerceAtLeast(1)
    val frazione = (minuti.toFloat() / denominatore).coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(frazione)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}
