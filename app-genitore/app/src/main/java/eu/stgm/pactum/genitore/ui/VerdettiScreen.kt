package eu.stgm.pactum.genitore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.Dichiarazione
import eu.stgm.pactum.genitore.dati.RegolaFinestra
import eu.stgm.pactum.genitore.dati.StatiDichiarazione
import eu.stgm.pactum.genitore.dati.TipiVerdetto

/** I verdetti: dai il tuo sui successi in attesa, e rivedi quelli già dati. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerdettiScreen(vm: VerdettiViewModel = viewModel()) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LifecycleResumeEffect(Unit) {
        vm.aggiorna()
        onPauseOrDispose { }
    }

    val messaggioInviato = stringResource(R.string.verdetto_inviato)
    val messaggioErrore = stringResource(R.string.verdetto_errore)
    val messaggioNonInAttesa = stringResource(R.string.verdetto_errore_non_in_attesa)
    LaunchedEffect(stato.evento) {
        when (val evento = stato.evento) {
            is VerdettiViewModel.Evento.Inviato -> snackbarHostState.showSnackbar(messaggioInviato)
            is VerdettiViewModel.Evento.Errore -> {
                val messaggio = if (evento.codice == "dichiarazione_non_in_attesa") {
                    messaggioNonInAttesa
                } else {
                    messaggioErrore
                }
                snackbarHostState.showSnackbar(messaggio)
            }
            null -> Unit
        }
        if (stato.evento != null) vm.consumaEvento()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.verdetti_titolo)) },
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
                stato.caricamento && stato.dichiarazioni.isEmpty() -> Centro {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.verdetti_caricamento),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }

                stato.configurazioneMancante -> Centro {
                    TestoCentrato(stringResource(R.string.verdetti_config_mancante))
                }

                stato.errore && stato.dichiarazioni.isEmpty() -> Centro {
                    TestoCentrato(stringResource(R.string.verdetti_errore))
                }

                else -> ContenutoVerdetti(
                    dichiarazioni = stato.dichiarazioni,
                    regolePerId = stato.regolePerId,
                    invioInCorso = stato.invioInCorso,
                    mostraErrore = stato.errore,
                    onVerdetto = { id, verdetto, nota -> vm.emettiVerdetto(id, verdetto, nota) },
                )
            }
        }
    }
}

@Composable
private fun ContenutoVerdetti(
    dichiarazioni: List<Dichiarazione>,
    regolePerId: Map<Long, RegolaFinestra>,
    invioInCorso: Boolean,
    mostraErrore: Boolean,
    onVerdetto: (Long, String, String?) -> Unit,
) {
    val inAttesa = dichiarazioni.filter { it.stato == StatiDichiarazione.IN_ATTESA }
    val risolte = dichiarazioni.filter { it.stato != StatiDichiarazione.IN_ATTESA }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (mostraErrore) {
            item { BannerDatiVecchi() }
        }

        item { TitoloSezione(stringResource(R.string.verdetti_sezione_attesa)) }
        if (inAttesa.isEmpty()) {
            item { TestoVuoto(stringResource(R.string.verdetti_attesa_vuoto)) }
        } else {
            items(inAttesa, key = { "attesa-${it.id}" }) { dichiarazione ->
                CardInAttesa(
                    dichiarazione = dichiarazione,
                    regola = regolePerId[dichiarazione.regolaId],
                    invioInCorso = invioInCorso,
                    onVerdetto = onVerdetto,
                )
            }
        }

        item { TitoloSezione(stringResource(R.string.verdetti_sezione_risolte)) }
        if (risolte.isEmpty()) {
            item { TestoVuoto(stringResource(R.string.verdetti_risolte_vuoto)) }
        } else {
            items(risolte, key = { "risolta-${it.id}" }) { dichiarazione ->
                CardRisolta(dichiarazione, regolePerId[dichiarazione.regolaId])
            }
        }
    }
}

@Composable
private fun CardInAttesa(
    dichiarazione: Dichiarazione,
    regola: RegolaFinestra?,
    invioInCorso: Boolean,
    onVerdetto: (Long, String, String?) -> Unit,
) {
    var nota by remember(dichiarazione.id) { mutableStateOf("") }
    val notaPulita = { nota.trim().ifBlank { null } }
    val arbitro = regola?.let { parametroTesto(it.parametri, "arbitro_nome") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            IntestazioneDichiarazione(dichiarazione, regola)
            Text(
                text = stringResource(R.string.dichiarazione_successo_dichiarato),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 4.dp),
            )
            dichiarazione.nota?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = stringResource(R.string.dichiarazione_nota, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = nota,
                onValueChange = { nota = it },
                label = { Text(stringResource(R.string.verdetto_nota_campo)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))

            Button(
                enabled = !invioInCorso,
                onClick = { onVerdetto(dichiarazione.id, TipiVerdetto.CONFERMA, notaPulita()) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.verdetto_conferma))
            }
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedButton(
                enabled = !invioInCorso,
                onClick = {
                    onVerdetto(dichiarazione.id, TipiVerdetto.CONFERMA_PER_CONTO, notaPulita())
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (arbitro != null) {
                        stringResource(R.string.verdetto_conferma_per_conto, arbitro)
                    } else {
                        stringResource(R.string.verdetto_conferma_per_conto_arbitro)
                    },
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedButton(
                enabled = !invioInCorso,
                onClick = { onVerdetto(dichiarazione.id, TipiVerdetto.RIBALTA, notaPulita()) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.verdetto_ribalta))
            }
        }
    }
}

@Composable
private fun CardRisolta(dichiarazione: Dichiarazione, regola: RegolaFinestra?) {
    val arbitro = regola?.let { parametroTesto(it.parametri, "arbitro_nome") } ?: "?"
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            IntestazioneDichiarazione(dichiarazione, regola)
            Text(
                text = descrizioneStato(dichiarazione, arbitro),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 4.dp),
            )
            dichiarazione.verdetto?.nota?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = stringResource(R.string.dichiarazione_verdetto_nota, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun IntestazioneDichiarazione(dichiarazione: Dichiarazione, regola: RegolaFinestra?) {
    val titolo = regola?.let { descrizioneRegola(it.tipo, it.parametri) }
        ?: stringResource(R.string.dichiarazione_regola_sconosciuta)
    Text(text = titolo, style = MaterialTheme.typography.titleSmall)
    if (dichiarazione.giorno.isNotBlank()) {
        Text(
            text = stringResource(R.string.dichiarazione_giorno, dichiarazione.giorno),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun descrizioneStato(dichiarazione: Dichiarazione, arbitro: String): String =
    when (dichiarazione.stato) {
        StatiDichiarazione.REGISTRATA -> stringResource(R.string.dichiarazione_stato_registrata)
        StatiDichiarazione.CONFERMATA -> stringResource(R.string.dichiarazione_stato_confermata)
        StatiDichiarazione.CONFERMATA_PER_CONTO ->
            stringResource(R.string.dichiarazione_stato_confermata_per_conto, arbitro)
        StatiDichiarazione.RIBALTATA -> stringResource(R.string.dichiarazione_stato_ribaltata)
        else -> dichiarazione.stato
    }

@Composable
private fun BannerDatiVecchi() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Text(
            text = stringResource(R.string.notifiche_dati_vecchi),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun TitoloSezione(testo: String) {
    Text(
        text = testo,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun TestoVuoto(testo: String) {
    Text(
        text = testo,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Centro(contenuto: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        contenuto()
    }
}

@Composable
private fun TestoCentrato(testo: String) {
    Text(
        text = testo,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 32.dp),
    )
}
