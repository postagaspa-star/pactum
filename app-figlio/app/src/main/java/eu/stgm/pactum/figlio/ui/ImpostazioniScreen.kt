package eu.stgm.pactum.figlio.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.stgm.pactum.figlio.BuildConfig
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.dati.Battito
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.rete.PostinoClient
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Impostazioni minime del postino: indirizzo del server e token del patto. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImpostazioniScreen(onChiudi: () -> Unit) {
    val context = LocalContext.current
    val ambito = rememberCoroutineScope()
    val impostazioni = remember { Impostazioni(context.applicationContext) }

    var serverUrl by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    var caricato by rememberSaveable { mutableStateOf(false) }
    var urlNonValido by rememberSaveable { mutableStateOf(false) }
    var provaInCorso by remember { mutableStateOf(false) }
    val ultimoBattito by impostazioni.ultimoBattitoConsegnato.collectAsState(initial = null)
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
        }
    }
}

private val formattatoreBattito: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())
