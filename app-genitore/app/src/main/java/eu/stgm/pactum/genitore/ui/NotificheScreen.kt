package eu.stgm.pactum.genitore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.Notifica

/**
 * Le notifiche non lette del patto. Non è più una scheda: si apre dalla
 * campanella della finestra e si chiude col tasto indietro. "Letta" è un gesto
 * del genitore, qui — la vedetta non segna mai niente da sola.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificheScreen(onChiudi: () -> Unit, vm: NotificheViewModel = viewModel()) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val messaggioLettaFallita = stringResource(R.string.notifica_letta_fallita)

    LifecycleResumeEffect(Unit) {
        vm.aggiorna()
        onPauseOrDispose { }
    }

    // Ogni scatto del contatore = un fallimento di "segna come letta" da dire.
    LaunchedEffect(stato.lettaFallita) {
        if (stato.lettaFallita > 0) snackbarHostState.showSnackbar(messaggioLettaFallita)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notifiche_titolo)) },
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
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                stato.caricamento && stato.notifiche.isEmpty() ->
                    Caricamento(stringResource(R.string.notifiche_caricamento))

                stato.configurazioneMancante -> Centro {
                    StatoPrimaApertura(
                        titolo = stringResource(R.string.config_mancante_titolo),
                        testo = stringResource(R.string.notifiche_config_mancante),
                        centrato = true,
                        modifier = Modifier.padding(horizontal = Spazi.xxl),
                    )
                }

                stato.errore && stato.notifiche.isEmpty() -> Centro {
                    TestoCentrato(stringResource(R.string.notifiche_errore))
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(Spazi.l),
                    verticalArrangement = Arrangement.spacedBy(Spazi.m),
                ) {
                    // Aggiornamento fallito con una lista già in mano: onestà come
                    // nella finestra — si dice che i dati sono vecchi, invece di
                    // spacciarli per freschi in silenzio.
                    if (stato.errore) {
                        item { RigaDatiVecchi(stringResource(R.string.notifiche_dati_vecchi)) }
                    }
                    if (stato.notifiche.isEmpty()) {
                        item {
                            RigaVuota(
                                stringResource(R.string.notifiche_vuoto),
                                buonaNotizia = true,
                            )
                        }
                    } else {
                        item {
                            ListaRighe(stato.notifiche) { notifica ->
                                RigaNotifica(
                                    notifica = notifica,
                                    testo = testoNotifica(parole(), notifica, stato.regolePerId),
                                    onSegnaLetta = { vm.segnaLetta(notifica) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Una notifica: icona del tipo, cosa è successo, quando — e il segno di "letta".
 * Il [testo] lo scrive l'app (testoNotifica, lo stesso della notifica di sistema).
 */
@Composable
private fun RigaNotifica(notifica: Notifica, testo: TestoNotifica, onSegnaLetta: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spazi.m),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            painter = iconaTipo(notifica.tipo),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spazi.xs).size(24.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = Spazi.m),
        ) {
            Text(
                text = testo.titolo,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = testo.testo,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = Spazi.xs),
            )
            TestoOrario(notifica.tsServer, Modifier.padding(top = Spazi.xs))
        }
        IconButton(onClick = onSegnaLetta) {
            Icon(Icons.Outlined.Done, stringResource(R.string.notifica_segna_letta))
        }
    }
}

/** L'icona per tipo: mai un colore d'allarme, solo una forma che si riconosce. */
@Composable
private fun iconaTipo(tipo: String): Painter = when (tipo) {
    "sforamento" -> painterResource(R.drawable.ic_scheda_tempo)
    "manomissione" -> rememberVectorPainter(Icons.Outlined.Info)
    "bonus" -> rememberVectorPainter(Icons.Outlined.AddCircle)
    "modifica_regola" -> rememberVectorPainter(Icons.Outlined.Edit)
    "proposta_risposta", "proposta_annullata", "dichiarazione" ->
        painterResource(R.drawable.ic_scheda_turno)
    else -> painterResource(R.drawable.ic_notifica_binocolo)
}
