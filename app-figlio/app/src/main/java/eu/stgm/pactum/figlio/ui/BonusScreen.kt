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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.dati.Regola

/** Il bonus time: ti allunghi un limite, con i tetti del patto in vista. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BonusScreen(vm: BonusViewModel = viewModel()) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var regolaSceltaId by rememberSaveable { mutableStateOf<Long?>(null) }
    var motivo by rememberSaveable { mutableStateOf("") }

    LifecycleResumeEffect(Unit) {
        vm.aggiorna()
        onPauseOrDispose { }
    }

    val messaggioRegolaNonValida = stringResource(R.string.bonus_regola_non_valida)
    val messaggioErrore = stringResource(R.string.bonus_errore)
    LaunchedEffect(stato.evento) {
        when (val evento = stato.evento) {
            is BonusViewModel.Evento.Concesso -> {
                motivo = ""
                snackbarHostState.showSnackbar(
                    context.getString(R.string.bonus_concesso, evento.minuti),
                )
            }
            is BonusViewModel.Evento.TettoSuperato -> snackbarHostState.showSnackbar(
                context.getString(
                    R.string.bonus_tetto_superato,
                    evento.residuoGiorno,
                    evento.residuoSettimana,
                ),
            )
            is BonusViewModel.Evento.RegolaNonValida ->
                snackbarHostState.showSnackbar(messaggioRegolaNonValida)
            is BonusViewModel.Evento.Errore -> snackbarHostState.showSnackbar(messaggioErrore)
            null -> Unit
        }
        if (stato.evento != null) vm.consumaEvento()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bonus_titolo)) },
                actions = {
                    IconButton(onClick = { vm.aggiorna() }) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.azione_aggiorna))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                stato.caricamento && stato.bonus == null && stato.regoleLimite.isEmpty() ->
                    Centro {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text(
                                text = stringResource(R.string.bonus_caricamento),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }

                stato.configurazioneMancante -> Centro {
                    TestoCentrato(stringResource(R.string.regole_config_mancante))
                }

                else -> {
                    // La regola scelta deve esistere ancora tra le limite_tempo attive.
                    val scelta = stato.regoleLimite.firstOrNull { it.id == regolaSceltaId }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (stato.errore) {
                            item { BannerDatiVecchi() }
                        }

                        item {
                            Text(
                                text = stringResource(R.string.bonus_spiegazione),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        stato.bonus?.let { bonus ->
                            item {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(
                                            text = stringResource(
                                                R.string.bonus_residui_giorno,
                                                bonus.giorno.residui,
                                                bonus.giorno.tetto,
                                            ),
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                        Text(
                                            text = stringResource(
                                                R.string.bonus_residui_settimana,
                                                bonus.settimana.residui,
                                                bonus.settimana.tetto,
                                            ),
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                    }
                                }
                            }
                        }

                        item { TitoloSezione(stringResource(R.string.bonus_scegli_regola)) }
                        if (stato.regoleLimite.isEmpty()) {
                            item { TestoVuoto(stringResource(R.string.bonus_nessuna_regola)) }
                        } else {
                            items(stato.regoleLimite, key = { it.id }) { regola ->
                                RigaRegolaBonus(
                                    regola = regola,
                                    selezionata = regola.id == regolaSceltaId,
                                    bonusOggi = stato.bonusOggiPerRegola[regola.id.toString()] ?: 0,
                                    onScegli = { regolaSceltaId = regola.id },
                                )
                            }

                            item {
                                OutlinedTextField(
                                    value = motivo,
                                    onValueChange = { motivo = it },
                                    label = { Text(stringResource(R.string.bonus_motivo)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            item {
                                val residuoGiorno = stato.bonus?.giorno?.residui ?: Int.MAX_VALUE
                                val residuoSettimana =
                                    stato.bonus?.settimana?.residui ?: Int.MAX_VALUE
                                val residuo = minOf(residuoGiorno, residuoSettimana)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(5, 15, 30).forEach { minuti ->
                                        FilledTonalButton(
                                            enabled = scelta != null && !stato.invioInCorso &&
                                                minuti <= residuo,
                                            onClick = {
                                                scelta?.let { vm.concedi(it.id, minuti, motivo) }
                                            },
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Text(
                                                stringResource(R.string.bonus_piu_minuti, minuti),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RigaRegolaBonus(
    regola: Regola,
    selezionata: Boolean,
    bonusOggi: Int,
    onScegli: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selezionata, onClick = onScegli)
            Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    text = descrizioneRegola(regola.tipo, regola.parametri),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (bonusOggi > 0) {
                    Text(
                        text = stringResource(R.string.bonus_oggi_su_regola, bonusOggi),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
