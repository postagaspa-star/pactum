package eu.stgm.pactum.genitore.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.genitore.BuildConfig
import eu.stgm.pactum.design.TitoloSezione
import eu.stgm.pactum.design.RigaStato
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.aggiornamento.Aggiornatore
import eu.stgm.pactum.genitore.aggiornamento.EsitoAggiornamento
import eu.stgm.pactum.genitore.dati.ConfigurazionePostino
import eu.stgm.pactum.genitore.dati.Impostazioni
import eu.stgm.pactum.genitore.rete.PostinoClient
import eu.stgm.pactum.genitore.servizio.EsenzioneBatteria
import eu.stgm.pactum.genitore.servizio.MarcaConRisparmio
import eu.stgm.pactum.genitore.servizio.marcaConRisparmio
import eu.stgm.pactum.genitore.sync.Vedetta
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.Locale
import androidx.compose.ui.graphics.Color
import eu.stgm.pactum.design.SchermataColorata
import eu.stgm.pactum.design.Sezione

/**
 * (0.15) Le Impostazioni, una pagina che si apre dall'icona in alto di ogni
 * scheda. In quest'ordine: la Famiglia (genitori, figli, dispositivi: la parte che
 * si usa), gli Avvisi del patto, il Riassunto della sera, il Collegamento (chiuso
 * in una riga quando il telefono è collegato), la Versione dell'app e "Come
 * funziona Pactum". [sezione] = la sezione da portare in vista all'apertura (dalla
 * notifica fissa, da "Risolvi" nella Panoramica, dal primo avvio).
 */
@Composable
fun ImpostazioniScreen(
    sezione: SezioneImpostazioni? = null,
    /** Cambia a ogni arrivo dalla notifica fissa: la sezione si riporta in vista. */
    richiesta: Int = 0,
    famigliaVm: FamigliaViewModel = viewModel(),
    collegamentoVm: CollegamentoViewModel = viewModel(),
) {
    val cornice = LocalCornice.current
    val messaggi = cornice.messaggi
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
    // (0.15) Il codice lungo si vede solo toccando l'occhio (B35).
    var codiceLungoVisibile by rememberSaveable { mutableStateOf(false) }
    var domandaCollega by rememberSaveable { mutableStateOf(false) }
    // (0.15) Il modulo del collegamento, quando il telefono è già collegato, si apre con "Cambia".
    var collegamentoAperto by rememberSaveable { mutableStateOf(sezione == SezioneImpostazioni.COLLEGAMENTO) }
    var controlloInCorso by remember { mutableStateOf(false) }
    val aggiornatore = remember { Aggiornatore(context.applicationContext) }
    val ultimaVerifica by impostazioni.ultimaVerificaRiuscita.collectAsState(initial = null)
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

    // (0.15) La sezione chiesta si porta in vista una volta sola (non a ogni rotazione).
    // Dove comincia ogni sezione nella pagina: si scorre fin lì, col titolo in alto.
    val scorrimento = rememberScrollState()
    val inizioSezione = remember { mutableStateMapOf<SezioneImpostazioni, Int>() }
    // Per quale [richiesta] la sezione è già stata portata in vista (null = mai):
    // una volta sola, non a ogni rotazione; di nuovo a ogni nuovo arrivo.
    var mostrataPer by rememberSaveable { mutableStateOf<Int?>(null) }
    // La Famiglia sta in cima e cresce quando arriva dal server: prima di scorrere
    // agli Avvisi o al Collegamento si aspetta che abbia la sua misura vera (al
    // massimo 3 secondi), altrimenti la sezione chiesta scivola giù fuori vista.
    val famigliaAssestata = (famiglia.lettaDalServer || famiglia.errore || famiglia.configurazioneMancante || famiglia.serverVecchio || famiglia.collegamentoNonValido) &&
        (!famiglia.lettaDalServer || famiglia.genitoriLetti || famiglia.genitoriErrore || famiglia.genitoriServerVecchio)
    val assestata by rememberUpdatedState(famigliaAssestata)
    LaunchedEffect(sezione, richiesta) {
        if (sezione == null || mostrataPer == richiesta) return@LaunchedEffect
        if (sezione != SezioneImpostazioni.FAMIGLIA) {
            withTimeoutOrNull(ATTESA_FAMIGLIA_MS) { snapshotFlow { assestata }.first { it } }
        }
        withFrameNanos { } // prima si dispone la pagina, poi si scorre
        withFrameNanos { }
        inizioSezione[sezione]?.let { scorrimento.animateScrollTo(it) }
        mostrataPer = richiesta
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

    val configurato = configurazioneSalvata?.completa == true
    // Collegato e valido: il collegamento sta chiuso in una riga, finché non si tocca "Cambia".
    val collegamentoChiuso = configurato && !famiglia.collegamentoNonValido && !collegamentoAperto

    SchermataColorata(Sezione.IMPOSTAZIONI) {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = { BarraPagina(stringResource(R.string.impostazioni_titolo)) },
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(scorrimento)
                    .padding(Spazi.l),
                verticalArrangement = Arrangement.spacedBy(Spazi.m),
            ) {
                // --- 1. La famiglia (la parte che si usa) -----------------------------------
                Column(
                    modifier = Modifier.fillMaxWidth().inizio(inizioSezione, SezioneImpostazioni.FAMIGLIA),
                    verticalArrangement = Arrangement.spacedBy(Spazi.m),
                ) {
                    SezioneFamiglia(
                        famigliaVm = famigliaVm,
                        indirizzoServer = configurazioneSalvata?.serverUrl?.takeIf { it.isNotBlank() },
                        mostraMessaggio = messaggi::mostra,
                    )
                }

                // --- 2. Gli avvisi del patto (0.9) --------------------------------------------
                Divisore()
                Column(
                    modifier = Modifier.fillMaxWidth().inizio(inizioSezione, SezioneImpostazioni.AVVISI),
                    verticalArrangement = Arrangement.spacedBy(Spazi.m),
                ) {
                    SezioneAvvisi()
                }

                // --- 3. Il riassunto della sera ------------------------------------------------
                Divisore()
                SezioneDigest(impostazioni)

                // --- 4. Il collegamento ---------------------------------------------------------
                Divisore()
                Column(
                    modifier = Modifier.fillMaxWidth().inizio(inizioSezione, SezioneImpostazioni.COLLEGAMENTO),
                    verticalArrangement = Arrangement.spacedBy(Spazi.m),
                ) {
                    TitoloSezione(stringResource(R.string.impostazioni_connessione_titolo))
                    // (0.13) Chi sei tu, quando il server lo dice (contratto v3.6); o che il
                    // collegamento di questo telefono non vale più (401).
                    val io = famiglia.io?.takeIf { configurato && !famiglia.collegamentoNonValido }
                    if (famiglia.collegamentoNonValido && configurato) {
                        RigaStato(stringResource(R.string.collegamento_non_valido))
                    }
                    if (collegamentoChiuso) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (io != null) {
                                    stringResource(R.string.connessione_collegato_come, nomeDelGenitore(p, io.nome))
                                } else {
                                    stringResource(R.string.connessione_collegato)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { collegamentoAperto = true }) {
                                Text(stringResource(R.string.connessione_cambia))
                            }
                        }
                    } else {
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
                        // la pagina non interrompe; l'esito lo dice la radice (MainActivity), che
                        // dopo un collegamento riuscito torna alla Panoramica.
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
                                    messaggi.mostra(messaggioUrlNonValido)
                                } else {
                                    urlNonValido = false
                                    serverUrl = urlNormalizzato
                                    // Già collegato: prima una domanda (smetterà di essere chi è adesso).
                                    if (domandaPrimaDiCollegare(p, configurato, famiglia.io) != null) {
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
                                visualTransformation = if (codiceLungoVisibile) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { codiceLungoVisibile = !codiceLungoVisibile }) {
                                        Icon(
                                            painterResource(if (codiceLungoVisibile) R.drawable.ic_occhio_chiuso else R.drawable.ic_occhio),
                                            contentDescription = stringResource(
                                                if (codiceLungoVisibile) R.string.codice_lungo_nascondi else R.string.codice_lungo_mostra,
                                            ),
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                onClick = {
                                    val urlNormalizzato = PostinoClient.normalizzaUrlServer(serverUrl)
                                    if (urlNormalizzato == null) {
                                        urlNonValido = true
                                        messaggi.mostra(messaggioUrlNonValido)
                                    } else {
                                        urlNonValido = false
                                        serverUrl = urlNormalizzato
                                        val primoCollegamento = !configurato
                                        ambito.launch {
                                            impostazioni.salvaConfigurazione(urlNormalizzato, token)
                                            messaggi.mostra(messaggioSalvato)
                                            // (0.15) Al primo collegamento si torna alla Panoramica.
                                            if (primoCollegamento) cornice.allaPanoramica()
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.azione_salva))
                            }
                        }
                    }

                    // Verifica onesta del canale: quando il server ha risposto l'ultima
                    // volta e un pulsante per provare adesso, con esito esplicito.
                    Text(
                        text = stringResource(
                            R.string.impostazioni_ultima_verifica,
                            ultimaVerifica?.let { testoQuando(p, Instant.ofEpochMilli(it)) }
                                ?: stringResource(R.string.impostazioni_ultima_verifica_mai),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                serverUrl.isBlank() || tokenProva.isEmpty() -> messaggi.mostra(messaggioConfigIncompleta)

                                urlProva == null -> {
                                    urlNonValido = true
                                    messaggi.mostra(messaggioUrlNonValido)
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
                                        messaggi.mostra(esito)
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
                }

                // --- 5. La versione dell'app (tappa 6) -------------------------------------------
                // La versione installata e un controllo manuale. La vedetta lo fa anche da
                // sola a ogni giro; questo è per chi non vuole aspettare.
                Divisore()
                TitoloSezione(stringResource(R.string.impostazioni_aggiornamenti_titolo))
                Text(
                    text = stringResource(R.string.impostazioni_versione_attuale, BuildConfig.VERSION_NAME),
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
                                messaggi.mostra(messaggio)
                            } finally {
                                controlloInCorso = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.impostazioni_controlla_aggiornamenti))
                }

                // --- 6. Come funziona Pactum ----------------------------------------------------
                // La cornice (prima una card in mezzo alla Panoramica): cos'è Pactum, perché
                // non impone il genitore le regole, e chi è l'arbitro delle regole di vita reale.
                Divisore()
                TitoloSezione(stringResource(R.string.intro_titolo))
                Text(text = stringResource(R.string.intro_testo), style = MaterialTheme.typography.bodyMedium)
                Text(text = stringResource(R.string.intro_arbitro), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** La linea sottile fra due sezioni. */
@Composable
private fun Divisore() {
    HorizontalDivider(modifier = Modifier.padding(top = Spazi.s), color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * (0.9) Avvisi del patto: cosa fa Pactum sempre attivo, e le tre cose del
 * telefono che decidono se gli avvisi arrivano in tempo — gli avvisi accesi,
 * l'esenzione dalla batteria di Android, e il risparmio batteria della marca
 * (Xiaomi, Huawei, Oppo, Vivo, OnePlus, Samsung…), con un passo in parole
 * semplici. Ogni pulsante apre la schermata di Android giusta; lo stato si
 * rilegge al ritorno. (0.15) Ci si arriva anche dalla notifica fissa e da
 * "Risolvi" nella Panoramica: la pagina la porta in vista da sola.
 */
@Composable
private fun SezioneAvvisi() {
    val context = LocalContext.current
    var esente by remember { mutableStateOf(EsenzioneBatteria.concessa(context)) }
    var accesi by remember { mutableStateOf(Vedetta.avvisiAccesi(context)) }
    LifecycleResumeEffect(Unit) {
        esente = EsenzioneBatteria.concessa(context)
        accesi = Vedetta.avvisiAccesi(context)
        onPauseOrDispose { }
    }
    val marca = remember { marcaConRisparmio(Build.MANUFACTURER) }
    val nomeApp = stringResource(R.string.nome_app)

    Column(
        modifier = Modifier.fillMaxWidth(),
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
 * Il riassunto della sera (il "digest giornaliero"): ogni giorno, all'ora scelta,
 * la vedetta manda una notifica col tempo totale di oggi e le prime app (il
 * dettaglio nella scheda Tempo). Interruttore + ora, salvati subito in DataStore.
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

/** Quanto si aspetta la Famiglia prima di portare in vista la sezione chiesta. */
private const val ATTESA_FAMIGLIA_MS = 3_000L

/** Si segna dove comincia la sezione [chi] dentro la pagina che scorre. */
private fun Modifier.inizio(inizi: MutableMap<SezioneImpostazioni, Int>, chi: SezioneImpostazioni): Modifier =
    onPlaced { inizi[chi] = it.positionInParent().y.roundToInt() }
