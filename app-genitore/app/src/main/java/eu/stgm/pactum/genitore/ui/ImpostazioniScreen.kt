package eu.stgm.pactum.genitore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.genitore.BuildConfig
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.aggiornamento.Aggiornatore
import eu.stgm.pactum.genitore.aggiornamento.EsitoAggiornamento
import eu.stgm.pactum.genitore.dati.ConfigurazionePostino
import eu.stgm.pactum.genitore.dati.Impostazioni
import eu.stgm.pactum.genitore.rete.PostinoClient
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.Locale

/**
 * Impostazioni del binocolo, in quattro blocchi: la connessione (indirizzo del
 * server e codice d'accesso del genitore), la famiglia (v3: figli, dispositivi,
 * codici per collegarli), il digest giornaliero, gli aggiornamenti dell'app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImpostazioniScreen(
    famigliaVm: FamigliaViewModel = viewModel(),
    finestraVm: FinestraViewModel = viewModel(),
    proposteVm: ProposteViewModel = viewModel(),
    verdettiVm: VerdettiViewModel = viewModel(),
    notificheVm: NotificheViewModel = viewModel(),
) {
    val context = LocalContext.current
    val ambito = rememberCoroutineScope()
    val impostazioni = remember { Impostazioni(context.applicationContext) }
    val configurazioneSalvata by impostazioni.configurazione.collectAsState(initial = null)

    // La famiglia si rilegge entrando qui: è il posto dove si cambia.
    LaunchedEffect(Unit) { famigliaVm.aggiorna() }

    var serverUrl by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    var caricato by rememberSaveable { mutableStateOf(false) }
    var urlNonValido by rememberSaveable { mutableStateOf(false) }
    var provaInCorso by remember { mutableStateOf(false) }
    var controlloInCorso by remember { mutableStateOf(false) }
    val aggiornatore = remember { Aggiornatore(context.applicationContext) }
    val ultimaVerifica by impostazioni.ultimaVerificaRiuscita.collectAsState(initial = null)
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
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.impostazioni_titolo)) })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(Spazi.l),
            verticalArrangement = Arrangement.spacedBy(Spazi.m),
        ) {
            // Tre blocchi: connessione, digest, aggiornamenti. È l'unica
            // schermata densa dell'app, e va bene: è configurazione.
            TitoloSezione(stringResource(R.string.impostazioni_connessione_titolo))
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
                    // Un URL scritto male e accettato in silenzio = un binocolo
                    // che non vede mai niente senza dirlo: si rifiuta subito.
                    val urlNormalizzato = PostinoClient.normalizzaUrlServer(serverUrl)
                    if (urlNormalizzato == null) {
                        urlNonValido = true
                        ambito.launch { snackbarHostState.showSnackbar(messaggioUrlNonValido) }
                    } else {
                        urlNonValido = false
                        serverUrl = urlNormalizzato
                        ambito.launch {
                            impostazioni.salvaConfigurazione(urlNormalizzato, token)
                            // Un altro server (o un altro codice) è un'altra famiglia:
                            // niente di quello che si ricorda del server di prima
                            // deve finire sotto il nome di un figlio di adesso.
                            famigliaVm.ricomincia()
                            finestraVm.dimentica()
                            proposteVm.dimentica()
                            verdettiVm.dimentica()
                            notificheVm.dimentica()
                            snackbarHostState.showSnackbar(messaggioSalvato)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.azione_salva))
            }

            // Verifica onesta del canale: quando il server ha risposto l'ultima
            // volta e un pulsante per provare adesso, con esito esplicito.
            Text(
                text = stringResource(
                    R.string.impostazioni_ultima_verifica,
                    ultimaVerifica?.let { dataOraCompletaLocale(Instant.ofEpochMilli(it)) }
                        ?: stringResource(R.string.impostazioni_ultima_verifica_mai),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                enabled = !provaInCorso,
                onClick = {
                    // La prova usa quello che c'è SULLO SCHERMO, non l'ultima
                    // configurazione salvata: altrimenti si prova un indirizzo
                    // vecchio credendo di provare quello appena scritto.
                    val urlProva = PostinoClient.normalizzaUrlServer(serverUrl)
                    val tokenProva = token.trim()
                    when {
                        serverUrl.isBlank() || tokenProva.isEmpty() -> ambito.launch {
                            snackbarHostState.showSnackbar(messaggioConfigIncompleta)
                        }

                        urlProva == null -> {
                            urlNonValido = true
                            ambito.launch { snackbarHostState.showSnackbar(messaggioUrlNonValido) }
                        }

                        else -> ambito.launch {
                            provaInCorso = true
                            try {
                                val provata = ConfigurazionePostino(urlProva, tokenProva)
                                val finestra = PostinoClient(provata).leggiFinestra()
                                val esito = if (finestra != null) {
                                    // "Ultima verifica riuscita" racconta il canale
                                    // configurato: si registra solo se la prova ha
                                    // usato esattamente la configurazione salvata.
                                    if (provata == impostazioni.leggiConfigurazione()) {
                                        impostazioni.registraVerificaRiuscita()
                                    }
                                    messaggioProvaOk
                                } else {
                                    messaggioProvaFallita
                                }
                                snackbarHostState.showSnackbar(esito)
                            } finally {
                                provaInCorso = false
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.impostazioni_prova_adesso))
            }

            // (v3) La famiglia: figli, dispositivi e i codici per collegarli.
            HorizontalDivider(
                modifier = Modifier.padding(top = Spazi.s),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            SezioneFamiglia(
                famigliaVm = famigliaVm,
                indirizzoServer = configurazioneSalvata?.serverUrl?.takeIf { it.isNotBlank() },
                mostraMessaggio = { messaggio ->
                    ambito.launch { snackbarHostState.showSnackbar(messaggio) }
                },
            )

            // Digest giornaliero: l'ora scelta e l'interruttore. Si salva al
            // gesto, senza pulsante: è una preferenza, non una configurazione.
            HorizontalDivider(
                modifier = Modifier.padding(top = Spazi.s),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            SezioneDigest(impostazioni)

            // Aggiornamenti (tappa 6): la versione installata e un controllo
            // manuale. La vedetta lo fa anche da sola a ogni giro; questo è per
            // chi non vuole aspettare.
            HorizontalDivider(
                modifier = Modifier.padding(top = Spazi.s),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            TitoloSezione(stringResource(R.string.impostazioni_aggiornamenti_titolo))
            Text(
                text = stringResource(
                    R.string.impostazioni_versione_attuale,
                    BuildConfig.VERSION_NAME,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                enabled = !controlloInCorso,
                onClick = {
                    ambito.launch {
                        controlloInCorso = true
                        try {
                            // forza=true: il gesto esplicito del genitore può
                            // ritentare anche una versione già tentata (es. un
                            // dialogo di sistema chiuso per sbaglio).
                            val messaggio = when (val esito = aggiornatore.controlla(forza = true)) {
                                is EsitoAggiornamento.Avviato -> context.getString(
                                    R.string.aggiornamento_avviato,
                                    esito.versioneNome,
                                )

                                EsitoAggiornamento.GiaAggiornato ->
                                    context.getString(R.string.aggiornamento_gia_aggiornato)

                                EsitoAggiornamento.InstallazionePendente ->
                                    context.getString(R.string.aggiornamento_installazione_pendente)

                                EsitoAggiornamento.ConfigMancante ->
                                    context.getString(R.string.aggiornamento_config_mancante)

                                EsitoAggiornamento.Irraggiungibile ->
                                    context.getString(R.string.aggiornamento_irraggiungibile)

                                EsitoAggiornamento.Fallito ->
                                    context.getString(R.string.aggiornamento_fallito)
                            }
                            snackbarHostState.showSnackbar(messaggio)
                        } finally {
                            controlloInCorso = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.impostazioni_controlla_aggiornamenti))
            }
        }
    }
}

/**
 * Il digest giornaliero: ogni giorno, all'ora scelta, la vedetta manda una
 * notifica col tempo totale di oggi e le prime app (il dettaglio nella sezione
 * Tempo). Interruttore + ora, salvati subito in DataStore.
 */
@Composable
private fun SezioneDigest(impostazioni: Impostazioni) {
    val ambito = rememberCoroutineScope()
    // null = DataStore non ancora letto: meglio niente che valori inventati.
    val digest by impostazioni.configDigest.collectAsState(initial = null)
    val config = digest ?: return

    TitoloSezione(stringResource(R.string.impostazioni_digest_titolo))
    Text(
        text = stringResource(R.string.impostazioni_digest_descrizione),
        style = MaterialTheme.typography.bodyMedium,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.impostazioni_digest_attivo),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = config.attivo,
            onCheckedChange = { attivo ->
                ambito.launch { impostazioni.salvaConfigDigest(attivo, config.ora) }
            },
        )
    }
    if (config.attivo) {
        var menuOreAperto by remember { mutableStateOf(false) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.impostazioni_digest_ora),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Box {
                OutlinedButton(onClick = { menuOreAperto = true }) {
                    Text(testoOra(config.ora))
                }
                DropdownMenu(
                    expanded = menuOreAperto,
                    onDismissRequest = { menuOreAperto = false },
                ) {
                    (0..23).forEach { ora ->
                        DropdownMenuItem(
                            text = { Text(testoOra(ora)) },
                            onClick = {
                                menuOreAperto = false
                                ambito.launch { impostazioni.salvaConfigDigest(true, ora) }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** "21" → "21:00" (formato fisso: è un orario, non una frase da tradurre). */
private fun testoOra(ora: Int): String = String.format(Locale.ROOT, "%02d:00", ora)
