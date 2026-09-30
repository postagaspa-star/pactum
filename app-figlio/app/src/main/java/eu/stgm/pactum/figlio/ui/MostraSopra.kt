package eu.stgm.pactum.figlio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.permessi.PermessiHelper

/**
 * (0.9) Pactum può mostrarsi sopra le altre app? Si riguarda a ogni ritorno in
 * primo piano: il permesso si dà nelle impostazioni di sistema.
 */
@Composable
fun rememberMostraSopra(): Boolean {
    val context = LocalContext.current
    var concesso by remember { mutableStateOf(PermessiHelper.puoMostrareSopra(context)) }
    LifecycleResumeEffect(Unit) {
        concesso = PermessiHelper.puoMostrareSopra(context)
        onPauseOrDispose { }
    }
    return concesso
}

/**
 * (0.9) In Oggi, finché "Mostra sopra le altre app" manca: chi aggiorna da una
 * versione vecchia non ripassa dall'onboarding, e senza questa scheda il
 * permesso resterebbe sepolto nelle Impostazioni. Spiega a cosa serve, i
 * passi, e la strada per le impostazioni con limitazioni di Android 15/16.
 * Sparisce da sola appena il permesso c'è.
 */
@Composable
fun SchedaMostraSopra(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(Spazi.l + Spazi.xs),
            verticalArrangement = Arrangement.spacedBy(Spazi.s),
        ) {
            Text(
                text = stringResource(R.string.oggi_avviso_titolo),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.oggi_avviso_testo),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.oggi_avviso_passi),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { PermessiHelper.apri(context, PermessiHelper.intentMostraSopra(context)) }) {
                Text(stringResource(R.string.passo_apri_impostazioni))
            }
            AiutoRestrizioni(stringResource(R.string.aiuto_mostra_sopra_testo))
        }
    }
}
