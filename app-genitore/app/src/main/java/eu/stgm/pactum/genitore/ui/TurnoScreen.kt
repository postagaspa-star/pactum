package eu.stgm.pactum.genitore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.genitore.R

/**
 * "Proposte e conferme": le risposte che il genitore deve dare, in una schermata sola
 * (tavola rotonda C3/C4). Prima le proposte (da mandare, in attesa, come sono
 * andate), sotto le dichiarazioni del figlio da confermare.
 *
 * Riusa i due ViewModel di prima, senza toccarne la logica di rete: ognuno
 * legge i suoi dati e porta i suoi esiti; qui si mettono solo uno sopra l'altro.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TurnoScreen(
    proposteVm: ProposteViewModel = viewModel(),
    verdettiVm: VerdettiViewModel = viewModel(),
) {
    val proposte by proposteVm.stato.collectAsStateWithLifecycle()
    val verdetti by verdettiVm.stato.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // rememberSaveable: una rotazione non deve buttare via la proposta in corso né
    // il confronto appena ricevuto. Della regola scelta si salva l'id (Long,
    // salvabile) e la si risale dall'elenco corrente.
    var regolaSceltaId by rememberSaveable { mutableStateOf<Long?>(null) }
    var confrontoInviato by rememberSaveable { mutableStateOf<String?>(null) }
    val regolaScelta = regolaSceltaId?.let { id -> proposte.regoleAttive.firstOrNull { it.id == id } }

    val aggiornaTutto = {
        proposteVm.aggiorna()
        verdettiVm.aggiorna()
    }
    LifecycleResumeEffect(Unit) {
        aggiornaTutto()
        onPauseOrDispose { }
    }

    // Gli esiti delle proposte: il confronto in un dialogo, gli errori in basso.
    val messaggioErroreGenerico = stringResource(R.string.proposta_errore_generico)
    val messaggioGiaPendente = stringResource(R.string.proposta_errore_gia_pendente)
    val messaggioRegolaNonValida = stringResource(R.string.proposta_errore_regola_non_valida)
    val messaggioParametriNonValidi = stringResource(R.string.proposta_errore_parametri_non_validi)
    LaunchedEffect(proposte.evento) {
        when (val evento = proposte.evento) {
            is ProposteViewModel.Evento.Inviata -> {
                regolaSceltaId = null // chiudi il dialogo di creazione
                confrontoInviato = evento.confronto
            }
            is ProposteViewModel.Evento.Errore -> {
                val messaggio = when (evento.codice) {
                    "proposta_gia_pendente" -> messaggioGiaPendente
                    "regola_non_valida" -> messaggioRegolaNonValida
                    "parametri_non_validi" -> messaggioParametriNonValidi
                    else -> messaggioErroreGenerico
                }
                snackbarHostState.showSnackbar(messaggio)
            }
            null -> Unit
        }
        if (proposte.evento != null) proposteVm.consumaEvento()
    }

    // Gli esiti delle conferme.
    val messaggioInviato = stringResource(R.string.verdetto_inviato)
    val messaggioErrore = stringResource(R.string.verdetto_errore)
    val messaggioNonInAttesa = stringResource(R.string.verdetto_errore_non_in_attesa)
    LaunchedEffect(verdetti.evento) {
        when (val evento = verdetti.evento) {
            is VerdettiViewModel.Evento.Inviato -> snackbarHostState.showSnackbar(messaggioInviato)
            is VerdettiViewModel.Evento.Errore -> {
                val messaggio = if (evento.codice == "dichiarazione_non_in_attesa") {
                    messaggioNonInAttesa
                } else {
                    messaggioErrore
                }
                snackbarHostState.showSnackbar(messaggio)
            }
            null -> Unit
        }
        if (verdetti.evento != null) verdettiVm.consumaEvento()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.turno_titolo)) },
                actions = {
                    IconButton(onClick = aggiornaTutto) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.azione_aggiorna))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        // "Niente in mano": né regole, né proposte, né dichiarazioni lette finora.
        val nienteInMano = proposte.proposte.isEmpty() &&
            proposte.regoleAttive.isEmpty() &&
            verdetti.dichiarazioni.isEmpty()
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                nienteInMano && (proposte.caricamento || verdetti.caricamento) ->
                    Caricamento(stringResource(R.string.turno_caricamento))

                proposte.configurazioneMancante || verdetti.configurazioneMancante -> Centro {
                    StatoPrimaApertura(
                        titolo = stringResource(R.string.config_mancante_titolo),
                        testo = stringResource(R.string.turno_config_mancante),
                        centrato = true,
                        modifier = Modifier.padding(horizontal = Spazi.xxl),
                    )
                }

                nienteInMano && (proposte.errore || verdetti.errore) -> Centro {
                    TestoCentrato(stringResource(R.string.turno_errore))
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(Spazi.l),
                    verticalArrangement = Arrangement.spacedBy(Spazi.m),
                ) {
                    // Un aggiornamento fallito con i dati già in mano: si dice
                    // che sono vecchi, invece di spacciarli per freschi.
                    if (proposte.errore || verdetti.errore) {
                        item { RigaDatiVecchi(stringResource(R.string.turno_dati_vecchi)) }
                    }
                    sezioneProposte(
                        regoleAttive = proposte.regoleAttive,
                        proposte = proposte.proposte,
                        onProponi = { regolaSceltaId = it.id },
                    )
                    sezioneDichiarazioni(
                        dichiarazioni = verdetti.dichiarazioni,
                        regolePerId = verdetti.regolePerId,
                        invioInCorso = verdetti.invioInCorso,
                        onVerdetto = { id, verdetto, nota ->
                            verdettiVm.emettiVerdetto(id, verdetto, nota)
                        },
                    )
                }
            }
        }
    }

    regolaScelta?.let { regola ->
        DialogoNuovaProposta(
            regola = regola,
            invioInCorso = proposte.invioInCorso,
            onAnnulla = { regolaSceltaId = null },
            onInvia = { parametri, motivazione ->
                proposteVm.creaProposta(regola.id, parametri, motivazione)
            },
        )
    }

    confrontoInviato?.let { confronto ->
        AlertDialog(
            onDismissRequest = { confrontoInviato = null },
            title = { Text(stringResource(R.string.proposta_inviata_titolo)) },
            text = { Text(stringResource(R.string.proposta_inviata_confronto, confronto)) },
            confirmButton = {
                TextButton(onClick = { confrontoInviato = null }) {
                    Text(stringResource(R.string.azione_ok))
                }
            },
        )
    }
}
