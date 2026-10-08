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
import eu.stgm.pactum.design.Caricamento
import eu.stgm.pactum.design.CardNormale
import eu.stgm.pactum.design.FilaPulsanti
import eu.stgm.pactum.design.RigaStato
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.design.StatoVuoto
import eu.stgm.pactum.design.TitoloSezione
import eu.stgm.pactum.figlio.dati.DominioVisite
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.dati.SitiGiorno
import eu.stgm.pactum.figlio.siti.OsservazioneSiti
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.ui.graphics.Color
import eu.stgm.pactum.design.SchermataColorata
import eu.stgm.pactum.design.Sezione
import eu.stgm.pactum.design.TitoloBarra
import eu.stgm.pactum.design.coloriBarra

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

    SchermataColorata(Sezione.SITI) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { TitoloBarra(stringResource(R.string.siti_titolo)) },
                    colors = coloriBarra(),
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
                // (0.15) La densità del figlio: 20 attorno, 16 tra i blocchi.
                contentPadding = PaddingValues(Spazi.l + Spazi.xs),
                verticalArrangement = Arrangement.spacedBy(Spazi.l),
            ) {
                item(key = "stato") {
                    SchedaStato(
                        attiva = stato.osservazioneAttiva,
                        dominiOggi = stato.dominiOggi,
                        onAttiva = { mostraAttivazione = true },
                        onSpegni = { vm.spegni() },
                    )
                }

                item(key = "tavola-rotonda") {
                    Nota(stringResource(R.string.siti_tavola_rotonda))
                }

                if (stato.configurazioneMancante) {
                    item(key = "config") { RigaStato(stringResource(R.string.siti_config_mancante)) }
                } else if (stato.datiVecchi) {
                    item(key = "dati-vecchi") { RigaStato(stringResource(R.string.dati_vecchi)) }
                }

                if (stato.caricamento && stato.giorni.isEmpty()) {
                    item(key = "caricamento") { Caricamento(testo = stringResource(R.string.siti_caricamento), centrato = false) }
                } else if (stato.giorni.isEmpty()) {
                    item(key = "vuoto") { StatoVuoto(stringResource(R.string.siti_vuoto), emoji = "🌐") }
                } else {
                    // Il server manda dal più vecchio a oggi; qui oggi sta in cima.
                    // (0.15) Un elemento della lista per riga (anche 200 domini in un
                    // giorno scorrono leggeri), senza chiavi: la lista viene dal server
                    // e un giorno doppio farebbe cadere la schermata invece di mostrarla storta.
                    stato.giorni.reversed().forEach { giorno ->
                        item { IntestazioneGiorno(giorno) }
                        items(giorno.domini) { voce -> RigaDominio(voce) }
                    }
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
    CardNormale {
        Column(verticalArrangement = Arrangement.spacedBy(Spazi.s)) {
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

/**
 * (0.15) Il giorno: "Oggi", "Ieri", "Giovedì 2 ottobre" e quanti domini, e se
 * per un po' il telefono ha tenuto nascosti i nomi. I domini seguono, uno per
 * riga (RigaDominio), senza card.
 */
@Composable
private fun IntestazioneGiorno(giorno: SitiGiorno) {
    Column(modifier = Modifier.padding(top = Spazi.s), verticalArrangement = Arrangement.spacedBy(Spazi.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TitoloSezione(etichettaGiorno(giorno.giorno), modifier = Modifier.weight(1f))
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
            )
        }
        if (giorno.domini.isEmpty() && !giorno.dnsCifrato) {
            Nota(stringResource(R.string.siti_giorno_vuoto))
        }
    }
}

@Composable
private fun RigaDominio(voce: DominioVisite) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = Spazi.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = voce.dominio, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                text = stringResource(R.string.siti_visite, voce.visite),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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

    SchermataColorata(Sezione.SITI) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { TitoloBarra(stringResource(R.string.siti_consenso_titolo)) },
                    colors = coloriBarra(),
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
                    .verticalScroll(rememberScrollState())
                    .padding(Spazi.l + Spazi.xs),
                verticalArrangement = Arrangement.spacedBy(Spazi.l),
            ) {
                Text(
                    text = stringResource(R.string.siti_consenso_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
                BloccoConsenso(R.string.siti_consenso_si_titolo, R.string.siti_consenso_si)
                BloccoConsenso(R.string.siti_consenso_no_titolo, R.string.siti_consenso_no)
                BloccoConsenso(R.string.siti_consenso_come_titolo, R.string.siti_consenso_come)
                BloccoConsenso(R.string.siti_consenso_patto_titolo, R.string.siti_consenso_patto)

                FilaPulsanti {
                    Button(
                        onClick = {
                            // Se il consenso c'è già (riattivazione), Android non
                            // chiede niente: si accende e basta.
                            val intent = OsservazioneSiti.intentConsenso(context)
                            if (intent == null) onConsensoDato() else richiestaConsenso.launch(intent)
                        },
                    ) {
                        Text(stringResource(R.string.siti_consenso_attiva), maxLines = 1)
                    }
                    OutlinedButton(onClick = onAnnulla) {
                        Text(stringResource(R.string.siti_consenso_rifiuta), maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun BloccoConsenso(titolo: Int, testo: Int) {
    CardNormale {
        Column(verticalArrangement = Arrangement.spacedBy(Spazi.s)) {
            Text(stringResource(titolo), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(testo), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
