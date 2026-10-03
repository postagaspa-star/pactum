package eu.stgm.pactum.genitore.ui

import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.genitore.BuildConfig
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.aggiornamento.Aggiornatore
import eu.stgm.pactum.genitore.aggiornamento.EsitoAggiornamento
import eu.stgm.pactum.genitore.dati.ConfigurazionePostino
import eu.stgm.pactum.genitore.dati.Impostazioni
import eu.stgm.pactum.genitore.rete.EsitoAbbinamento
import eu.stgm.pactum.genitore.rete.PostinoClient
import eu.stgm.pactum.genitore.servizio.EsenzioneBatteria
import eu.stgm.pactum.genitore.servizio.MarcaConRisparmio
import eu.stgm.pactum.genitore.servizio.marcaConRisparmio
import eu.stgm.pactum.genitore.sync.Vedetta
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.Locale

/**
 * Impostazioni del binocolo, in cinque blocchi: la connessione (indirizzo del
 * server e codice d'accesso del genitore), la famiglia (v3: figli, dispositivi,
 * codici per collegarli), gli avvisi del patto (0.9: Pactum sempre attivo), il
 * digest giornaliero, gli aggiornamenti dell'app. [mostraAvvisi] = aperte dalla
 * notifica fissa: la sezione degli avvisi viene in vista da sola.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImpostazioniScreen(
    mostraAvvisi: Boolean = false,
    onAvvisiMostrati: () -> Unit = {},
    famigliaVm: FamigliaViewModel = viewModel(),
    collegamentoVm: CollegamentoViewModel = viewModel(),
) {
    val context = LocalContext.current
    val ambito = rememberCoroutineScope()
    val impostazioni = remember { Impostazioni(context.applicationContext) }
    val configurazioneSalvata by impostazioni.configurazione.collectAsState(initial = null)
    val famiglia by famigliaVm.stato.collectAsStateWithLifecycle()
    val collegamento by collegamentoVm.stato.collectAsStateWithLifecycle()
    val p = parole()

    // La famiglia si rilegge entrando qui: è il posto dove si cambia. (0.13) Anche
    // i genitori, che si leggono solo qui.
    LaunchedEffect(Unit) {
        famigliaVm.aggiorna()
        famigliaVm.aggiornaGenitori()
    }

    var serverUrl by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    var caricato by rememberSaveable { mutableStateOf(false) }
    var urlNonValido by rememberSaveable { mutableStateOf(false) }
    var provaInCorso by remember { mutableStateOf(false) }
    // (0.13) Il collegamento con il codice di 6 cifre; il codice lungo dietro un tocco.
    var codiceSei by rememberSaveable { mutableStateOf("") }
    var codiceLungoAperto by rememberSaveable { mutableStateOf(false) }
    var domandaCollega by rememberSaveable { mutableStateOf(false) }
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

    // (0.13) Com'è andato il collegamento col codice di 6 cifre (anche se è finito
    // mentre questa pagina era chiusa): il token è già salvato dal ViewModel.
    LaunchedEffect(collegamento.esito) {
        val esito = collegamento.esito ?: return@LaunchedEffect
        collegamentoVm.consumaEsito()
        val messaggio = if (esito is EsitoAbbinamento.Collegato) {
            token = esito.token
            codiceSei = ""
            testoCollegato(p, esito.genitore)
        } else {
            messaggioAbbinamento(p, esito)
        }
        if (messaggio != null) ambito.launch { snackbarHostState.showSnackbar(messaggio) }
    }

    // (0.13) Un telefono già collegato come genitore: prima di collegarlo come un altro, una domanda.
    if (domandaCollega) {
        val testo = domandaPrimaDiCollegare(p, configurazioneSalvata?.completa == true, famiglia.io)
        AlertDialog(
            onDismissRequest = { domandaCollega = false },
            title = { Text(stringResource(R.string.connessione_conferma_titolo)) },
            text = { Text(testo.orEmpty()) },
            confirmButton = {
                TextButton(
                    onClick = {
                        domandaCollega = false
                        PostinoClient.normalizzaUrlServer(serverUrl)?.let { collegamentoVm.collega(it, codiceSei) }
                    },
                ) {
                    Text(stringResource(R.string.connessione_collega))
                }
            },
            dismissButton = {
                TextButton(onClick = { domandaCollega = false }) { Text(stringResource(R.string.azione_annulla)) }
            },
        )
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
            // (0.13) Chi sei tu, quando il server lo dice (contratto v3.6); o che il
            // collegamento di questo telefono non vale più (401).
            val io = famiglia.io?.takeIf { configurazioneSalvata?.completa == true && !famiglia.collegamentoNonValido }
            if (famiglia.collegamentoNonValido && configurazioneSalvata?.completa == true) {
                RigaDatiVecchi(stringResource(R.string.collegamento_non_valido))
            }
            if (io != null) {
                Text(
                    text = stringResource(R.string.connessione_collegato_come, nomeDelGenitore(p, io.nome)),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Text(
                text = stringResource(R.string.connessione_codice_spiega),
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
            // (0.13) Il codice di 6 cifre (contratto v3.6, POST /api/abbina col tipo
            // "genitore"): lo crea un genitore già collegato. Solo cifre, al massimo 6.
            // Collegamento e salvataggio li fa CollegamentoViewModel, in un blocco che
            // la pagina non interrompe; quello che le schermate sanno del collegamento
            // di prima lo fa dimenticare GenitoreRoot (MainActivity), a ogni cambio.
            OutlinedTextField(
                value = codiceSei,
                onValueChange = { codiceSei = soloCifre(it) },
                label = { Text(stringResource(R.string.connessione_codice)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = codiceCompleto(codiceSei) && !collegamento.inCorso,
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
                        // Già collegato: prima una domanda (smetterà di essere chi è adesso).
                        if (domandaPrimaDiCollegare(p, configurazioneSalvata?.completa == true, famiglia.io) != null) {
                            domandaCollega = true
                        } else {
                            collegamentoVm.collega(urlNormalizzato, codiceSei)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(if (collegamento.inCorso) R.string.connessione_in_corso else R.string.connessione_collega),
                )
            }

            // Il codice d'accesso lungo, come prima della 0.13: per il primo genitore.
            TextButton(onClick = { codiceLungoAperto = !codiceLungoAperto }) {
                Text(
                    stringResource(
                        if (codiceLungoAperto) R.string.connessione_codice_lungo_chiudi else R.string.connessione_codice_lungo_apri,
                    ),
                )
            }
            if (codiceLungoAperto) {
                Text(
                    text = stringResource(R.string.impostazioni_descrizione),
                    style = MaterialTheme.typography.bodyMedium,
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

            // (0.9) Avvisi del patto: Pactum sempre attivo, gli avvisi accesi,
            // l'esenzione dalla batteria e il risparmio batteria della marca.
            HorizontalDivider(
                modifier = Modifier.padding(top = Spazi.s),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            SezioneAvvisi(mostra = mostraAvvisi, onMostrata = onAvvisiMostrati)

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
 * (0.9) Avvisi del patto: cosa fa Pactum sempre attivo, e le tre cose del
 * telefono che decidono se gli avvisi arrivano in tempo — gli avvisi accesi,
 * l'esenzione dalla batteria di Android, e il risparmio batteria della marca
 * (Xiaomi, Huawei, Oppo, Vivo, OnePlus, Samsung…), con un passo in parole
 * semplici. Ogni pulsante apre la schermata di Android giusta; lo stato si
 * rilegge al ritorno. [mostra] = ci si arriva dalla notifica fissa ("tocca per
 * sistemare"): la sezione viene in vista da sola.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SezioneAvvisi(mostra: Boolean, onMostrata: () -> Unit) {
    val context = LocalContext.current
    var esente by remember { mutableStateOf(EsenzioneBatteria.concessa(context)) }
    var accesi by remember { mutableStateOf(Vedetta.avvisiAccesi(context)) }
    LifecycleResumeEffect(Unit) {
        esente = EsenzioneBatteria.concessa(context)
        accesi = Vedetta.avvisiAccesi(context)
        onPauseOrDispose { }
    }
    val inVista = remember { BringIntoViewRequester() }
    LaunchedEffect(mostra) {
        if (!mostra) return@LaunchedEffect
        withFrameNanos { } // prima si dispone la schermata, poi si scorre
        inVista.bringIntoView()
        onMostrata()
    }
    val marca = remember { marcaConRisparmio(Build.MANUFACTURER) }
    val nomeApp = stringResource(R.string.nome_app)

    Column(
        modifier = Modifier.fillMaxWidth().bringIntoViewRequester(inVista),
        verticalArrangement = Arrangement.spacedBy(Spazi.m),
    ) {
        TitoloSezione(stringResource(R.string.impostazioni_attivo_titolo))
        Text(
            text = stringResource(R.string.impostazioni_attivo_descrizione),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!accesi) {
            Text(
                text = stringResource(R.string.impostazioni_avvisi_spenti),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                onClick = { EsenzioneBatteria.apriNotifiche(context) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.impostazioni_avvisi_accendi))
            }
        }
        Text(
            text = stringResource(if (esente) R.string.impostazioni_attivo_si else R.string.impostazioni_attivo_no),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!esente) {
            OutlinedButton(
                onClick = { EsenzioneBatteria.chiedi(context) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.impostazioni_attivo_consenti))
            }
        }
        if (marca != null) {
            Text(
                text = when (marca) {
                    MarcaConRisparmio.XIAOMI -> stringResource(R.string.marca_xiaomi)
                    MarcaConRisparmio.HUAWEI -> stringResource(R.string.marca_huawei, nomeApp)
                    MarcaConRisparmio.OPPO_ONEPLUS -> stringResource(R.string.marca_oppo_oneplus)
                    MarcaConRisparmio.VIVO -> stringResource(R.string.marca_vivo, nomeApp)
                    MarcaConRisparmio.SAMSUNG -> stringResource(R.string.marca_samsung, nomeApp)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.marca_nota),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { EsenzioneBatteria.apriInfoApp(context) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.marca_apri_info_app))
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
