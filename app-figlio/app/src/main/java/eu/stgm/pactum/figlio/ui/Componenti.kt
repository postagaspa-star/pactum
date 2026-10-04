package eu.stgm.pactum.figlio.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import eu.stgm.pactum.figlio.R
import java.time.Instant

/*
 * (0.15) I pochi mattoni dell'app del figlio che core-design non ha (perché
 * sono solo di quest'app). Tutto il resto viene da eu.stgm.pactum.design.
 */

/**
 * In alto a destra in OGNI scheda: ⟳ Aggiorna e ⚙ Impostazioni. [onApriImpostazioni]
 * null = la pagina è mostrata fuori dalle schede (i lavori di casa prima del
 * primo avvio): niente ingranaggio.
 */
@Composable
fun AzioniBarra(onAggiorna: () -> Unit, onApriImpostazioni: (() -> Unit)?) {
    IconButton(onClick = onAggiorna) {
        Icon(Icons.Filled.Refresh, stringResource(R.string.azione_aggiorna))
    }
    if (onApriImpostazioni != null) {
        IconButton(onClick = onApriImpostazioni) {
            Icon(Icons.Filled.Settings, stringResource(R.string.azione_impostazioni))
        }
    }
}

/**
 * Una nota piccola e grigia sotto un fatto ("Da Mamma", "Bocciato 2 volte"):
 * `bodySmall`, `onSurfaceVariant`. Una sola, per tutta l'app (prima ce
 * n'erano due copie uguali). Non dentro una CardEvidenza: lì il colore lo dà la card.
 */
@Composable
fun Nota(testo: String, modifier: Modifier = Modifier) {
    Text(
        text = testo,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * Dati vecchi: è un'ETÀ, non un fallimento ("Dati non aggiornati: ultimo
 * aggiornamento alle 14:32."). [aggiornatiIl] null = età sconosciuta. Va in
 * una RigaStato, la stessa in ogni scheda.
 */
@Composable
fun testoDatiVecchi(aggiornatiIl: Long?): String =
    aggiornatiIl?.let { stringResource(R.string.dati_fermi_alle, quandoLocale(Instant.ofEpochMilli(it))) }
        ?: stringResource(R.string.dati_fermi)
