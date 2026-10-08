package eu.stgm.pactum.figlio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.permessi.StatoPermessi
import androidx.compose.ui.graphics.Color
import eu.stgm.pactum.design.SchermataColorata
import eu.stgm.pactum.design.Sezione
import eu.stgm.pactum.design.TitoloBarra
import eu.stgm.pactum.design.coloriBarra

/**
 * (0.15) Primo passo del primo avvio: chi è Pactum, in una frase, e il
 * collegamento al patto (indirizzo del server e codice di 6 cifre dal
 * genitore). Solo per un telefono che non si è mai collegato.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassoCollegaScreen() {
    SchermataColorata(Sezione.IMPOSTAZIONI) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = { TopAppBar(title = { TitoloBarra(stringResource(R.string.onboarding_titolo)) }, colors = coloriBarra()) },
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(Spazi.l + Spazi.xs),
                verticalArrangement = Arrangement.spacedBy(Spazi.l),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_intro),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.prima_regola_config_intro),
                    style = MaterialTheme.typography.titleMedium,
                )
                ModuloCollegamento(origine = OriginiCollegamento.PRIMO_AVVIO, onCollegato = {})
            }
        }
    }
}

/**
 * (0.15) Secondo passo del primo avvio: i quattro permessi, ognuno col suo
 * stato. La pagina resta finché manca l'accesso ai dati di utilizzo (senza,
 * Pactum non può misurare niente); gli altri, se mancano, si ritrovano nella
 * riga "Da sistemare" di Oggi e nelle Impostazioni.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(statoPermessi: StatoPermessi, onAggiorna: () -> Unit) {
    SchermataColorata(Sezione.IMPOSTAZIONI) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = { TopAppBar(title = { TitoloBarra(stringResource(R.string.permessi_titolo)) }, colors = coloriBarra()) },
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(Spazi.l + Spazi.xs),
                verticalArrangement = Arrangement.spacedBy(Spazi.l),
            ) {
                Text(
                    text = stringResource(R.string.permessi_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
                ElencoPermessi(stato = statoPermessi, onAggiorna = onAggiorna)
                OutlinedButton(onClick = onAggiorna, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.onboarding_ricontrolla))
                }
                Text(
                    text = stringResource(R.string.onboarding_nota_avvio),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
