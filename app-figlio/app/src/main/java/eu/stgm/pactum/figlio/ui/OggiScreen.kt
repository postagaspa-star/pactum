package eu.stgm.pactum.figlio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.figlio.R

/** L'uso di oggi: totale in alto, elenco per app sotto. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OggiScreen(
    onApriImpostazioni: () -> Unit,
    vm: OggiViewModel = viewModel(),
) {
    val stato by vm.stato.collectAsStateWithLifecycle()

    // Prima lettura e rilettura a ogni ritorno in primo piano.
    LifecycleResumeEffect(Unit) {
        vm.aggiorna()
        onPauseOrDispose { }
    }

    Scaffold(
        // Le barre di sistema le copre lo Scaffold esterno (MainActivity).
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.oggi_titolo)) },
                actions = {
                    IconButton(onClick = { vm.aggiorna() }) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.azione_aggiorna))
                    }
                    IconButton(onClick = onApriImpostazioni) {
                        Icon(Icons.Filled.Settings, stringResource(R.string.azione_impostazioni))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            Card(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.oggi_totale),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        text = testoDurata(stato.minutiTotali),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
            }

            when {
                stato.caricamento && stato.righe.isEmpty() -> Contenitore {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.oggi_caricamento),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }

                stato.righe.isEmpty() -> Contenitore {
                    Text(stringResource(R.string.oggi_vuoto))
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                ) {
                    items(stato.righe, key = { it.pacchetto }) { riga ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(riga.etichetta, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = riga.pacchetto,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(testoDurata(riga.minuti), style = MaterialTheme.typography.bodyLarge)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun Contenitore(contenuto: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        contenuto()
    }
}
