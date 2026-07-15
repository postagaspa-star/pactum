package eu.stgm.pactum.figlio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.dati.Dichiarazione
import eu.stgm.pactum.figlio.dati.EsitiDichiarazione
import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.StatiDichiarazione
import java.time.LocalDate

/** Il diario: dichiara com'è andata sulle regole di vita reale, a viso aperto. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DichiarazioniScreen(vm: DichiarazioniViewModel = viewModel()) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Il dialogo di dichiarazione: regola + esito già scelti dai due pulsanti.
    var dichiarazioneInCorso by remember { mutableStateOf<Pair<Regola, String>?>(null) }

    LifecycleResumeEffect(Unit) {
        vm.aggiorna()
        onPauseOrDispose { }
    }

    val messaggioSuccesso = stringResource(R.string.dichiarazione_inviata_successo)
    val messaggioFallimento = stringResource(R.string.dichiarazione_inviata_fallimento)
    val messaggioGia = stringResource(R.string.dichiarazione_gia_dichiarato)
    val messaggioErrore = stringResource(R.string.dichiarazione_errore)
    LaunchedEffect(stato.evento) {
        when (val evento = stato.evento) {
            is DichiarazioniViewModel.Evento.Inviata -> {
                dichiarazioneInCorso = null
                snackbarHostState.showSnackbar(
                    if (evento.esito == EsitiDichiarazione.SUCCESSO) {
                        messaggioSuccesso
                    } else {
                        messaggioFallimento
                    },
                )
            }
            is DichiarazioniViewModel.Evento.GiaDichiarato -> {
                dichiarazioneInCorso = null
                snackbarHostState.showSnackbar(messaggioGia)
            }
            is DichiarazioniViewModel.Evento.Errore -> snackbarHostState.showSnackbar(messaggioErrore)
            null -> Unit
        }
        if (stato.evento != null) vm.consumaEvento()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diario_titolo)) },
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
                stato.caricamento && stato.regoleVitaReale.isEmpty() &&
                    stato.dichiarazioni.isEmpty() -> Centro {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.diario_caricamento),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }

                stato.configurazioneMancante -> Centro {
                    TestoCentrato(stringResource(R.string.regole_config_mancante))
                }

                else -> ContenutoDiario(
                    regole = stato.regoleVitaReale,
                    dichiarazioni = stato.dichiarazioni,
                    mostraErrore = stato.errore,
                    onDichiara = { regola, esito -> dichiarazioneInCorso = regola to esito },
                )
            }
        }
    }

    dichiarazioneInCorso?.let { (regola, esito) ->
        DialogoDichiarazione(
            regola = regola,
            esito = esito,
            invioInCorso = stato.invioInCorso,
            onAnnulla = { dichiarazioneInCorso = null },
            onConferma = { nota -> vm.dichiara(regola.id, esito, nota) },
        )
    }
}

@Composable
private fun ContenutoDiario(
    regole: List<Regola>,
    dichiarazioni: List<Dichiarazione>,
    mostraErrore: Boolean,
    onDichiara: (Regola, String) -> Unit,
) {
    val oggi = LocalDate.now().toString()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (mostraErrore) {
            item { BannerDatiVecchi() }
        }

        item { TitoloSezione(stringResource(R.string.diario_sezione_regole)) }
        if (regole.isEmpty()) {
            item { TestoVuoto(stringResource(R.string.diario_regole_vuoto)) }
        } else {
            items(regole, key = { "regola-${it.id}" }) { regola ->
                val giaOggi = dichiarazioni.any { it.regolaId == regola.id && it.giorno == oggi }
                CardRegolaVitaReale(regola, giaOggi, onDichiara)
            }
        }

        item { TitoloSezione(stringResource(R.string.diario_sezione_dichiarazioni)) }
        if (dichiarazioni.isEmpty()) {
            item { TestoVuoto(stringResource(R.string.diario_dichiarazioni_vuoto)) }
        } else {
            items(dichiarazioni, key = { "dich-${it.id}" }) { dichiarazione ->
                CardDichiarazione(
                    dichiarazione = dichiarazione,
                    regola = regole.firstOrNull { it.id == dichiarazione.regolaId },
                )
            }
        }
    }
}

@Composable
private fun CardRegolaVitaReale(
    regola: Regola,
    giaDichiaratoOggi: Boolean,
    onDichiara: (Regola, String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = descrizioneRegola(regola.tipo, regola.parametri),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (giaDichiaratoOggi) {
                Text(
                    text = stringResource(R.string.diario_gia_dichiarato_oggi),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Row {
                    FilledTonalButton(
                        onClick = { onDichiara(regola, EsitiDichiarazione.SUCCESSO) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.dichiara_successo))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { onDichiara(regola, EsitiDichiarazione.FALLIMENTO) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.dichiara_fallimento))
                    }
                }
            }
        }
    }
}

@Composable
private fun CardDichiarazione(dichiarazione: Dichiarazione, regola: Regola?) {
    val arbitro = regola?.let { parametroTesto(it.parametri, "arbitro_nome") } ?: "?"
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            regola?.let {
                Text(
                    text = descrizioneRegola(it.tipo, it.parametri),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            if (dichiarazione.giorno.isNotBlank()) {
                Text(
                    text = stringResource(R.string.dichiarazione_giorno, dichiarazione.giorno),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = descrizioneStato(dichiarazione, arbitro),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 4.dp),
            )
            dichiarazione.nota?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = stringResource(R.string.dichiarazione_tua_nota, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
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

/** Lo stato raccontato dal punto di vista del figlio. */
@Composable
private fun descrizioneStato(dichiarazione: Dichiarazione, arbitro: String): String =
    when (dichiarazione.stato) {
        StatiDichiarazione.IN_ATTESA -> stringResource(R.string.dichiarazione_stato_in_attesa)
        StatiDichiarazione.REGISTRATA -> stringResource(R.string.dichiarazione_stato_registrata)
        StatiDichiarazione.CONFERMATA -> stringResource(R.string.dichiarazione_stato_confermata)
        StatiDichiarazione.CONFERMATA_PER_CONTO ->
            stringResource(R.string.dichiarazione_stato_confermata_per_conto, arbitro)
        StatiDichiarazione.RIBALTATA -> stringResource(R.string.dichiarazione_stato_ribaltata)
        else -> dichiarazione.stato
    }

@Composable
private fun DialogoDichiarazione(
    regola: Regola,
    esito: String,
    invioInCorso: Boolean,
    onAnnulla: () -> Unit,
    onConferma: (String?) -> Unit,
) {
    var nota by remember(regola.id, esito) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onAnnulla,
        title = {
            Text(
                stringResource(
                    if (esito == EsitiDichiarazione.SUCCESSO) {
                        R.string.dichiarazione_conferma_successo_titolo
                    } else {
                        R.string.dichiarazione_conferma_fallimento_titolo
                    },
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = descrizioneRegola(regola.tipo, regola.parametri),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        if (esito == EsitiDichiarazione.SUCCESSO) {
                            R.string.dichiarazione_conferma_successo_testo
                        } else {
                            R.string.dichiarazione_conferma_fallimento_testo
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = nota,
                    onValueChange = { nota = it },
                    label = { Text(stringResource(R.string.dichiarazione_nota_campo)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !invioInCorso,
                onClick = { onConferma(nota.trim().ifBlank { null }) },
            ) {
                Text(stringResource(R.string.azione_conferma))
            }
        },
        dismissButton = {
            TextButton(onClick = onAnnulla) { Text(stringResource(R.string.azione_annulla)) }
        },
    )
}
