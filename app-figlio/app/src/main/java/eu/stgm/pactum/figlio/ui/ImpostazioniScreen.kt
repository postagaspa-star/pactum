package eu.stgm.pactum.figlio.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.figlio.BuildConfig
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.dati.Battito
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.rete.PostinoClient
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Le Impostazioni del figlio: il collegamento al patto (indirizzo del server e
 * codice), la chiusura della sera, e "Cosa vede tuo padre" per sempre a un tocco.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImpostazioniScreen(onChiudi: () -> Unit, onApriCosaVede: () -> Unit) {
    val context = LocalContext.current
    val ambito = rememberCoroutineScope()
    val impostazioni = remember { Impostazioni(context.applicationContext) }

    var serverUrl by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    var caricato by rememberSaveable { mutableStateOf(false) }
    var urlNonValido by rememberSaveable { mutableStateOf(false) }
    var provaInCorso by remember { mutableStateOf(false) }
    val ultimoBattito by impostazioni.ultimoBattitoConsegnato.collectAsState(initial = null)
    val serale by impostazioni.chiusuraSerale.collectAsState(initial = null)
    var sceltaOra by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val messaggioSalvato = stringResource(R.string.impostazioni_salvate)
    val messaggioUrlNonValido = stringResource(R.string.impostazioni_url_non_valido)
    val messaggioProvaOk = stringResource(R.string.impostazioni_prova_ok)
    val messaggioProvaFallita = stringResource(R.string.impostazioni_prova_fallita)
    val messaggioConfigIncompleta = stringResource(R.string.impostazioni_config_incompleta)

    LaunchedEffect(Unit) {
        if (!caricato) {
            val configurazione = impostazioni.leggiConfigurazione()
            serverUrl = configurazione.serverUrl
            token = configurazione.token
            caricato = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.impostazioni_titolo)) },
                navigationIcon = {
                    IconButton(onClick = onChiudi) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.azione_indietro),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(Spazi.l + Spazi.xs),
            verticalArrangement = Arrangement.spacedBy(Spazi.l),
        ) {
            TitoloSezione(stringResource(R.string.impostazioni_sezione_collegamento))
            Text(
                text = stringResource(R.string.impostazioni_descrizione),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = serverUrl,
                onValueChange = {
                    serverUrl = it
                    urlNonValido = false
                },
                label = { Text(stringResource(R.string.impostazioni_server_url)) },
                placeholder = { Text(stringResource(R.string.impostazioni_server_url_esempio)) },
                isError = urlNonValido,
                supportingText = if (urlNonValido) {
                    { Text(stringResource(R.string.impostazioni_url_non_valido)) }
                } else {
                    null
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text(stringResource(R.string.impostazioni_token)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    // Un URL scritto male e accettato in silenzio = un'app che
                    // non consegna mai niente senza dirlo: si rifiuta subito.
                    val urlNormalizzato = PostinoClient.normalizzaUrlServer(serverUrl)
                    if (urlNormalizzato == null) {
                        urlNonValido = true
                        ambito.launch { snackbarHostState.showSnackbar(messaggioUrlNonValido) }
                    } else {
                        urlNonValido = false
                        serverUrl = urlNormalizzato
                        ambito.launch {
                            impostazioni.salvaConfigurazione(urlNormalizzato, token)
                            snackbarHostState.showSnackbar(messaggioSalvato)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.azione_salva))
            }

            // Verifica onesta del canale: quando è arrivato l'ultimo battito
            // e un pulsante per provarne uno adesso, con esito esplicito.
            Text(
                text = stringResource(
                    R.string.impostazioni_ultimo_battito,
                    ultimoBattito?.let { formattatoreBattito.format(Instant.ofEpochMilli(it)) }
                        ?: stringResource(R.string.impostazioni_ultimo_battito_mai),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                enabled = !provaInCorso,
                onClick = {
                    ambito.launch {
                        provaInCorso = true
                        try {
                            val configurazione = impostazioni.leggiConfigurazione()
                            val esito = if (!configurazione.completa) {
                                messaggioConfigIncompleta
                            } else {
                                val consegnato = PostinoClient(configurazione).inviaBattito(
                                    Battito(
                                        tsDevice = System.currentTimeMillis(),
                                        versioneApp = BuildConfig.VERSION_NAME,
                                        elapsedRealtime = SystemClock.elapsedRealtime(),
                                    ),
                                )
                                if (consegnato) {
                                    impostazioni.registraBattitoConsegnato()
                                    messaggioProvaOk
                                } else {
                                    messaggioProvaFallita
                                }
                            }
                            snackbarHostState.showSnackbar(esito)
                        } finally {
                            provaInCorso = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.impostazioni_prova_adesso))
            }

            // La chiusura della sera (C5): una notifica sola, all'ora scelta.
            TitoloSezione(stringResource(R.string.impostazioni_sezione_serale))
            serale?.let { config ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.impostazioni_serale_attiva),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = config.attiva,
                        onCheckedChange = { attiva ->
                            ambito.launch { impostazioni.salvaChiusuraSerale(attiva, config.minuti) }
                        },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.impostazioni_serale_ora),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(enabled = config.attiva, onClick = { sceltaOra = true }) {
                        Text(testoOra(config.minuti))
                    }
                }
                Text(
                    text = stringResource(R.string.impostazioni_serale_spiegazione),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (sceltaOra) {
                    DialogoOra(
                        minuti = config.minuti,
                        onAnnulla = { sceltaOra = false },
                        onScegli = { minuti ->
                            sceltaOra = false
                            ambito.launch { impostazioni.salvaChiusuraSerale(config.attiva, minuti) }
                        },
                    )
                }
            }

            // Per sempre a un tocco: cosa arriva al genitore, e cosa no (C6).
            TitoloSezione(stringResource(R.string.cosa_vede_titolo))
            Text(
                text = stringResource(R.string.impostazioni_cosa_vede_testo),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onApriCosaVede, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.impostazioni_cosa_vede_apri))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DialogoOra(minuti: Int, onAnnulla: () -> Unit, onScegli: (Int) -> Unit) {
    val stato = rememberTimePickerState(
        initialHour = minuti / 60,
        initialMinute = minuti % 60,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onAnnulla,
        title = { Text(stringResource(R.string.impostazioni_serale_ora)) },
        text = { TimePicker(state = stato) },
        confirmButton = {
            Button(onClick = { onScegli(stato.hour * 60 + stato.minute) }) {
                Text(stringResource(R.string.azione_conferma))
            }
        },
        dismissButton = {
            TextButton(onClick = onAnnulla) { Text(stringResource(R.string.azione_annulla)) }
        },
    )
}

private fun testoOra(minuti: Int): String = "%02d:%02d".format(minuti / 60, minuti % 60)

private val formattatoreBattito: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())
