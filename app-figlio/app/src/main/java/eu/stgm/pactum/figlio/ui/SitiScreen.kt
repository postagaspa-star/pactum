package eu.stgm.pactum.figlio.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.dati.SitiGiorno
import eu.stgm.pactum.figlio.siti.OsservazioneSiti
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * I siti visitati, dalla parte del figlio: **la stessa identica lista che vede
 * il genitore** (`siti_recenti`, calcolato dal server per entrambe le app).
 * È la tavola rotonda applicata alla lettera — se le due liste divergessero
 * sarebbe un bug, non una scelta di prodotto (docs/contratto-api.md).
 *
 * In cima lo stato dell'osservazione, con l'interruttore: accenderla passa
 * dalla schermata del consenso, spegnerla è un fatto che va a registro.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SitiScreen(
    onChiudi: () -> Unit,
    vm: SitiViewModel = viewModel(),
) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    var mostraAttivazione by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LifecycleResumeEffect(Unit) {
        vm.aggiorna()
        onPauseOrDispose { }
    }

    val messaggioAttivata = stringResource(R.string.siti_attivata)
    val messaggioDisattivata = stringResource(R.string.siti_disattivata)
    val messaggioNegato = stringResource(R.string.siti_consenso_negato)
    LaunchedEffect(stato.evento) {
        val messaggio = when (stato.evento) {
            SitiViewModel.Evento.Attivata -> messaggioAttivata
            SitiViewModel.Evento.Disattivata -> messaggioDisattivata
            SitiViewModel.Evento.ConsensoNegato -> messaggioNegato
            null -> null
        }
        if (messaggio != null) {
            snackbarHostState.showSnackbar(messaggio)
            vm.consumaEvento()
        }
    }

    if (mostraAttivazione) {
        BackHandler { mostraAttivazione = false }
        AttivazioneSitiScreen(
            onAnnulla = { mostraAttivazione = false },
            onConsensoDato = {
                mostraAttivazione = false
                vm.accendi()
            },
            onConsensoNegato = {
                mostraAttivazione = false
                vm.consensoNegato()
            },
        )
        return
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.siti_titolo)) },
                navigationIcon = {
                    IconButton(onClick = onChiudi) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.azione_indietro),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.aggiorna() }) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.azione_aggiorna))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(Spazi.l),
            verticalArrangement = Arrangement.spacedBy(Spazi.m),
        ) {
            item {
                SchedaStato(
                    attiva = stato.osservazioneAttiva,
                    dominiOggi = stato.dominiOggi,
                    onAttiva = { mostraAttivazione = true },
                    onSpegni = { vm.spegni() },
                )
            }

            item {
                Text(
                    text = stringResource(R.string.siti_tavola_rotonda),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (stato.configurazioneMancante) {
                item { Text(stringResource(R.string.siti_config_mancante)) }
            } else if (stato.datiVecchi) {
                item {
                    Text(
                        text = stringResource(R.string.dati_vecchi),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (stato.caricamento && stato.giorni.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text(
                                text = stringResource(R.string.siti_caricamento),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = Spazi.s),
                            )
                        }
                    }
                }
            } else if (stato.giorni.isEmpty()) {
                item { Text(stringResource(R.string.siti_vuoto)) }
            } else {
                // Il server manda dal più vecchio a oggi; qui oggi sta in cima.
                // Nessuna chiave: la lista è corta e fissa, e una chiave
                // ricavata dal server (giorni doppi in una risposta storta)
                // farebbe cadere la schermata invece di mostrarla storta.
                items(stato.giorni.reversed()) { giorno ->
                    SchedaGiorno(giorno)
                }
            }
        }
    }
}

@Composable
private fun SchedaStato(
    attiva: Boolean,
    dominiOggi: Int,
    onAttiva: () -> Unit,
    onSpegni: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(Spazi.l),
            verticalArrangement = Arrangement.spacedBy(Spazi.s),
        ) {
            Text(
                text = stringResource(
                    if (attiva) R.string.siti_stato_attiva else R.string.siti_stato_spenta,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(
                    if (attiva) R.string.siti_stato_attiva_testo else R.string.siti_stato_spenta_testo,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (attiva) {
                // Quello che è già stato osservato ma non ancora consegnato: il
                // figlio vede sempre almeno quanto il genitore, mai meno.
                Text(
                    text = stringResource(R.string.siti_oggi_locale, dominiOggi),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onSpegni, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.siti_disattiva))
                }
            } else {
                Button(onClick = onAttiva, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.siti_attiva))
                }
            }
        }
    }
}

@Composable
private fun SchedaGiorno(giorno: SitiGiorno) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(Spazi.l)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(etichettaGiorno(giorno.giorno), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = testoTotale(giorno),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (giorno.dnsCifrato) {
                Text(
                    text = stringResource(R.string.siti_dns_cifrato),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = Spazi.xs),
                )
            }

            if (giorno.domini.isEmpty()) {
                if (!giorno.dnsCifrato) {
                    Text(
                        text = stringResource(R.string.siti_giorno_vuoto),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spazi.xs),
                    )
                }
            } else {
                giorno.domini.forEach { voce ->
                    HorizontalDivider(modifier = Modifier.padding(vertical = Spazi.s))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = voce.dominio,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(R.string.siti_visite, voce.visite),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * "N domini distinti" — e se la fotografia era tagliata ai primi 200 lo dice:
 * il numero vero resta quello, la differenza si vede invece di sparire.
 */
@Composable
private fun testoTotale(giorno: SitiGiorno): String {
    val totale = giorno.totaleDomini ?: return stringResource(R.string.siti_giorno_vuoto)
    return if (totale > giorno.domini.size) {
        stringResource(R.string.siti_giorno_tagliato, totale, giorno.domini.size)
    } else {
        stringResource(R.string.siti_giorno_totale, totale)
    }
}

private val formatoGiorno: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALY)

@Composable
private fun etichettaGiorno(giorno: String): String {
    val data = runCatching { LocalDate.parse(giorno) }.getOrNull() ?: return giorno
    val oggi = LocalDate.now()
    return when (data) {
        oggi -> stringResource(R.string.dichiarazione_giorno_oggi)
        oggi.minusDays(1) -> stringResource(R.string.dichiarazione_giorno_ieri)
        else -> formatoGiorno.format(data).replaceFirstChar { it.uppercase() }
    }
}

/**
 * Il consenso, scritto in modo che si capisca DAVVERO cosa si accetta: cosa
 * viene registrato, cosa non lo sarà mai, come funziona, e che il genitore
 * vede senza poter bloccare. Poi — e solo poi — arriva la richiesta VPN di
 * sistema di Android.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttivazioneSitiScreen(
    onAnnulla: () -> Unit,
    onConsensoDato: () -> Unit,
    onConsensoNegato: () -> Unit,
) {
    val context = LocalContext.current
    val richiestaConsenso = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { risultato ->
        if (risultato.resultCode == Activity.RESULT_OK) onConsensoDato() else onConsensoNegato()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.siti_consenso_titolo)) },
                navigationIcon = {
                    IconButton(onClick = onAnnulla) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.azione_indietro),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(Spazi.l)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spazi.m),
        ) {
            Text(
                text = stringResource(R.string.siti_consenso_intro),
                style = MaterialTheme.typography.bodyMedium,
            )
            BloccoConsenso(R.string.siti_consenso_si_titolo, R.string.siti_consenso_si)
            BloccoConsenso(R.string.siti_consenso_no_titolo, R.string.siti_consenso_no)
            BloccoConsenso(R.string.siti_consenso_come_titolo, R.string.siti_consenso_come)
            BloccoConsenso(R.string.siti_consenso_patto_titolo, R.string.siti_consenso_patto)

            Button(
                onClick = {
                    // Se il consenso c'è già (riattivazione), Android non
                    // chiede niente: si accende e basta.
                    val intent = OsservazioneSiti.intentConsenso(context)
                    if (intent == null) onConsensoDato() else richiestaConsenso.launch(intent)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.siti_consenso_attiva))
            }
            OutlinedButton(onClick = onAnnulla, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.siti_consenso_rifiuta))
            }
        }
    }
}

@Composable
private fun BloccoConsenso(titolo: Int, testo: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(Spazi.l),
            verticalArrangement = Arrangement.spacedBy(Spazi.s),
        ) {
            Text(stringResource(titolo), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(testo), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
