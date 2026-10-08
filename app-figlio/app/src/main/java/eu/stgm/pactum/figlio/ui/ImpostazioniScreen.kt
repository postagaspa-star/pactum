package eu.stgm.pactum.figlio.ui

import eu.stgm.pactum.design.RigaToccabile
import eu.stgm.pactum.design.TitoloSezione
import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.figlio.BuildConfig
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.design.RigaStato
import eu.stgm.pactum.design.Tono
import eu.stgm.pactum.figlio.dati.Battito
import eu.stgm.pactum.figlio.dati.Collegamento
import eu.stgm.pactum.figlio.dati.CorsaCollegamento
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.permessi.StatoPermessi
import eu.stgm.pactum.figlio.rete.PostinoClient
import eu.stgm.pactum.figlio.siti.OsservazioneSiti
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import androidx.compose.ui.graphics.Color
import eu.stgm.pactum.design.SchermataColorata
import eu.stgm.pactum.design.Sezione
import eu.stgm.pactum.design.TitoloBarra
import eu.stgm.pactum.design.coloriBarra

/**
 * (0.15) Le Impostazioni del figlio, da ⚙ in ogni scheda, in quest'ordine: il
 * collegamento (una riga quando è collegato, il modulo dopo "Cambia"), i
 * quattro permessi (ognuno col suo stato e "Apri"), l'avviso della sera, i
 * siti visitati, cosa vedono i genitori, e in fondo, piccolo, l'ultimo
 * aggiornamento inviato e "Prova il collegamento". [suiPermessi] = aperte
 * da "Da sistemare" in Oggi: si va dritti ai permessi.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ImpostazioniScreen(
    onChiudi: () -> Unit,
    onApriCosaVede: () -> Unit,
    onApriSiti: () -> Unit,
    suiPermessi: Boolean = false,
) {
    val context = LocalContext.current
    val ambito = rememberCoroutineScope()
    val impostazioni = remember { Impostazioni(context.applicationContext) }

    var provaInCorso by remember { mutableStateOf(false) }
    val configurazione by impostazioni.configurazione.collectAsState(initial = null)
    val ultimoBattito by impostazioni.ultimoBattitoConsegnato.collectAsState(initial = null)
    val serale by impostazioni.chiusuraSerale.collectAsState(initial = null)
    var sceltaOra by rememberSaveable { mutableStateOf(false) }
    // "Cambia": il modulo del collegamento aperto anche da collegati.
    var cambiaCollegamento by rememberSaveable { mutableStateOf(false) }
    // (0.15) L'esito di un collegamento fatto da qui ("Collegamento riuscito.",
    // "Codice lungo salvato", "ora sei collegato come un dispositivo nuovo…"):
    // resta sopra "Collegato come" finché non si escono dalle Impostazioni.
    var avvisoCollegamento by rememberSaveable { mutableStateOf<String?>(null) }
    var avvisoCambioDispositivo by rememberSaveable { mutableStateOf(false) }
    val statoCollegamento by Collegamento.stato.collectAsState()
    val moduloAperto = configurazione?.let { !it.completa || cambiaCollegamento }
    // Un collegamento partito da qui e finito a modulo chiuso (le Impostazioni
    // chiuse a metà e riaperte): il suo esito si dice qui, e non riapre niente.
    LaunchedEffect(statoCollegamento, moduloAperto) {
        val finito = statoCollegamento as? CorsaCollegamento.Stato.Finito ?: return@LaunchedEffect
        if (moduloAperto != false || !OriginiCollegamento.eDi(finito.numero, OriginiCollegamento.IMPOSTAZIONI)) return@LaunchedEffect
        val letto = leggiEsitoCollegamento(context, finito.esito)
        avvisoCollegamento = letto.testo ?: context.getString(R.string.impostazioni_url_non_valido)
        avvisoCambioDispositivo = letto.cambioDispositivo
        Collegamento.consuma(finito)
    }
    var permessi by remember { mutableStateOf(StatoPermessi.leggi(context)) }
    var sitiAttivi by remember { mutableStateOf<Boolean?>(null) }
    var rilettura by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        permessi = StatoPermessi.leggi(context)
        rilettura++
        onPauseOrDispose { }
    }
    LaunchedEffect(rilettura) { sitiAttivi = OsservazioneSiti.attivaOra(context) }
    val snackbarHostState = remember { SnackbarHostState() }
    val messaggioProvaOk = stringResource(R.string.impostazioni_prova_ok)
    val messaggioProvaFallita = stringResource(R.string.impostazioni_prova_fallita)
    val messaggioProvaScollegato = stringResource(R.string.scollegato)
    val messaggioConfigIncompleta = stringResource(R.string.impostazioni_config_incompleta)
    val versoPermessi = remember { BringIntoViewRequester() }
    LaunchedEffect(suiPermessi) {
        if (suiPermessi) {
            delay(150)
            runCatching { versoPermessi.bringIntoView() }
        }
    }

    SchermataColorata(Sezione.IMPOSTAZIONI) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { TitoloBarra(stringResource(R.string.impostazioni_titolo)) },
                    colors = coloriBarra(),
                    navigationIcon = {
                        IconButton(onClick = onChiudi) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.azione_indietro))
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(Spazi.l + Spazi.xs),
                verticalArrangement = Arrangement.spacedBy(Spazi.l),
            ) {
                // 1. Il collegamento. configurazione null = non ancora letta dal disco:
                // il modulo aspetta, così i campi partono già con l'indirizzo salvato.
                TitoloSezione(stringResource(R.string.impostazioni_sezione_collegamento))
                configurazione?.let { attuale ->
                    if (attuale.completa && !cambiaCollegamento) {
                        avvisoCollegamento?.let {
                            RigaStato(testo = it, tono = if (avvisoCambioDispositivo) Tono.Attenzione else Tono.Neutro)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                RigaCollegatoCome(alternativa = stringResource(R.string.impostazioni_collegato))
                            }
                            TextButton(onClick = { cambiaCollegamento = true }) {
                                Text(stringResource(R.string.impostazioni_cambia))
                            }
                        }
                    } else {
                        ModuloCollegamento(
                            origine = OriginiCollegamento.IMPOSTAZIONI,
                            onCollegato = { avviso ->
                                avvisoCollegamento = avviso.testo
                                avvisoCambioDispositivo = avviso.cambioDispositivo
                                cambiaCollegamento = false
                            },
                            giaCollegato = attuale.completa,
                        )
                    }
                }

                // 2. I permessi: tutti e quattro, ognuno col suo stato.
                Column(
                    modifier = Modifier.bringIntoViewRequester(versoPermessi),
                    verticalArrangement = Arrangement.spacedBy(Spazi.s),
                ) {
                    TitoloSezione(stringResource(R.string.permessi_titolo))
                    ElencoPermessi(stato = permessi, onAggiorna = { permessi = StatoPermessi.leggi(context) })
                }

                // 3. La chiusura della sera (C5): una notifica sola, all'ora scelta.
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

                // 4. I siti visitati (v2.3): il SUO registro, che lui condivide.
                // 5. Cosa vedono i genitori, per sempre a un tocco (C6).
                Column {
                    RigaToccabile(
                        titolo = stringResource(R.string.siti_titolo),
                        sottotitolo = sitiAttivi?.let { stringResource(if (it) R.string.siti_stato_attiva else R.string.siti_stato_spenta) },
                        onClick = onApriSiti,
                    )
                    RigaToccabile(
                        titolo = stringResource(R.string.cosa_vede_titolo),
                        onClick = onApriCosaVede,
                    )
                }

                // 6. In fondo, piccolo: la verifica onesta del canale.
                Column(verticalArrangement = Arrangement.spacedBy(Spazi.xs)) {
                    Text(
                        text = stringResource(
                            R.string.impostazioni_ultimo_battito,
                            ultimoBattito?.let { quandoLocale(Instant.ofEpochMilli(it)) }
                                ?: stringResource(R.string.impostazioni_ultimo_battito_mai),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        enabled = !provaInCorso,
                        onClick = {
                            ambito.launch {
                                provaInCorso = true
                                try {
                                    val attuale = impostazioni.leggiConfigurazione()
                                    val esito = if (!attuale.completa) {
                                        messaggioConfigIncompleta
                                    } else {
                                        val codice = PostinoClient(attuale).provaBattito(
                                            Battito(
                                                tsDevice = System.currentTimeMillis(),
                                                versioneApp = BuildConfig.VERSION_NAME,
                                                elapsedRealtime = SystemClock.elapsedRealtime(),
                                            ),
                                        )
                                        when (codice) {
                                            in 200..299 -> {
                                                impostazioni.registraBattitoConsegnato()
                                                messaggioProvaOk
                                            }
                                            // Token revocato, o sostituito da un codice nuovo.
                                            401 -> messaggioProvaScollegato
                                            else -> messaggioProvaFallita
                                        }
                                    }
                                    snackbarHostState.showSnackbar(esito)
                                } finally {
                                    provaInCorso = false
                                }
                            }
                        },
                    ) {
                        Text(stringResource(R.string.impostazioni_prova_adesso))
                    }
                }
            }
        }
    }
}

/**
 * L'ora della chiusura serale. (0.15) Il quadrante solo dove ci sta in
 * altezza; sul telefono in orizzontale (o molto basso) l'ora si scrive, così
 * niente esce dallo schermo. Il dialogo scorre.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DialogoOra(minuti: Int, onAnnulla: () -> Unit, onScegli: (Int) -> Unit) {
    val stato = rememberTimePickerState(
        initialHour = minuti / 60,
        initialMinute = minuti % 60,
        is24Hour = true,
    )
    val alto = LocalConfiguration.current.screenHeightDp >= 560
    AlertDialog(
        onDismissRequest = onAnnulla,
        title = { Text(stringResource(R.string.impostazioni_serale_ora)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (alto) TimePicker(state = stato) else TimeInput(state = stato)
            }
        },
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

private fun testoOra(minuti: Int): String = oraDaMinuti(minuti)
