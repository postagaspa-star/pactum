package eu.stgm.pactum.genitore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import eu.stgm.pactum.genitore.dati.CodiciErrore
import kotlinx.coroutines.launch

/**
 * "Proposte e conferme": le risposte che il genitore deve dare, in una schermata sola
 * (tavola rotonda C3/C4). Prima le proposte ((0.10) da decidere, da mandare, in
 * attesa, come sono andate), sotto le dichiarazioni del figlio da confermare.
 *
 * Riusa i due ViewModel di prima, senza toccarne la logica di rete: ognuno
 * legge i suoi dati e porta i suoi esiti; qui si mettono solo uno sopra l'altro.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TurnoScreen(
    proposteVm: ProposteViewModel = viewModel(),
    verdettiVm: VerdettiViewModel = viewModel(),
    famigliaVm: FamigliaViewModel = viewModel(),
) {
    val proposte by proposteVm.stato.collectAsStateWithLifecycle()
    val verdetti by verdettiVm.stato.collectAsStateWithLifecycle()
    val famiglia by famigliaVm.stato.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // (v3) Tutto è del figlio scelto in cima: letture e scritture.
    val figlioId = famiglia.figlioId

    // rememberSaveable: una rotazione non deve buttare via la proposta in corso né
    // il confronto appena ricevuto. Della regola scelta si salva l'id (Long,
    // salvabile) e la si risale dall'elenco corrente.
    var regolaSceltaId by rememberSaveable { mutableStateOf<Long?>(null) }
    var confrontoInviato by rememberSaveable { mutableStateOf<String?>(null) }
    val regolaScelta = regolaSceltaId?.let { id ->
        proposte.regoleAttive.firstOrNull { it.id == id }.takeIf { proposte.di(figlioId) }
    }

    val aggiornaTutto = {
        proposteVm.aggiorna(figlioId)
        verdettiVm.aggiorna(figlioId)
    }
    // A ogni ritorno in primo piano e a ogni cambio di figlio, quando si sa
    // di quale figlio (famiglia pronta).
    LifecycleResumeEffect(figlioId, famiglia.pronta) {
        if (famiglia.pronta) aggiornaTutto()
        onPauseOrDispose { }
    }

    // Gli esiti delle proposte: il confronto in un dialogo, gli errori in basso.
    // Le frasi vengono dal codice `errore` del 409 (Testi.kt). (0.10) Arrivano da un
    // canale di questa schermata, uno per volta: nessuno copre l'altro, e quelli
    // della Panoramica restano là. Lo snackbar parte in uno scope suo.
    val parole = parole()
    val ambito = rememberCoroutineScope()
    val figlioMostrato by rememberUpdatedState(figlioId)
    LaunchedEffect(proposteVm) {
        proposteVm.esiti(ProposteViewModel.Schermata.PROPOSTE).collect { evento ->
            val messaggio = when (evento) {
                is ProposteViewModel.Evento.Inviata -> {
                    regolaSceltaId = null // chiudi il dialogo di creazione
                    confrontoInviato = evento.confronto
                    null
                }
                is ProposteViewModel.Evento.Errore -> {
                    // Già una proposta in attesa, o regola non più attiva: riprovare
                    // non serve. Via il dialogo, e si rilegge com'è davvero.
                    if (rifiutoPropostaDefinitivo(evento.codice)) {
                        regolaSceltaId = null
                        proposteVm.aggiorna(figlioMostrato)
                    }
                    parole.testo(messaggioRifiutoProposta(evento.codice))
                }
                // (0.10) La risposta a una proposta del figlio, e il ritiro di una tua:
                // l'elenco si rilegge da sé, la famiglia qui (il numero accanto al nome).
                is ProposteViewModel.Evento.Decisa -> {
                    famigliaVm.aggiorna()
                    parole.testo(messaggioDecisione(evento.esito, evento.eliminazione))
                }
                is ProposteViewModel.Evento.NonDecisa -> {
                    famigliaVm.aggiorna()
                    parole.testo(messaggioRifiutoDecisione(evento.codice, evento.statoFinale))
                }
                ProposteViewModel.Evento.Ritirata -> parole.testo(R.string.proposta_ritirata_fatto)
                is ProposteViewModel.Evento.NonRitirata ->
                    parole.testo(messaggioRifiutoRitiro(evento.codice, evento.statoFinale))
            }
            if (messaggio != null) ambito.launch { snackbarHostState.showSnackbar(messaggio) }
        }
    }

    // Gli esiti delle conferme.
    LaunchedEffect(verdetti.evento) {
        val evento = verdetti.evento ?: return@LaunchedEffect
        verdettiVm.consumaEvento()
        val messaggio = when (evento) {
            is VerdettiViewModel.Evento.Inviato -> parole.testo(R.string.verdetto_inviato)
            is VerdettiViewModel.Evento.Errore -> {
                // Qualcuno ha già risposto (l'arbitro, l'altro genitore): si
                // rilegge, così la dichiarazione esce da "DA CONFERMARE".
                if (evento.codice == CodiciErrore.DICHIARAZIONE_NON_IN_ATTESA) verdettiVm.aggiorna(figlioId)
                parole.testo(messaggioRifiutoVerdetto(evento.codice))
            }
        }
        ambito.launch { snackbarHostState.showSnackbar(messaggio) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.turno_titolo)) },
                actions = {
                    IconButton(
                        onClick = {
                            famigliaVm.aggiorna()
                            if (famiglia.pronta) aggiornaTutto()
                        },
                    ) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.azione_aggiorna))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        // Solo i dati DI QUESTO figlio: finché non arrivano, la rotella.
        val delFiglio = proposte.di(figlioId) && verdetti.di(figlioId)
        // "Niente in mano": né regole, né proposte, né dichiarazioni lette finora.
        val nienteInMano = proposte.proposte.isEmpty() &&
            proposte.regoleAttive.isEmpty() &&
            verdetti.dichiarazioni.isEmpty()
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            IntestazioneFiglio(famiglia, onScegli = famigliaVm::scegli)
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !delFiglio || (nienteInMano && (proposte.caricamento || verdetti.caricamento)) ->
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
                            // (0.10) Le proposte del figlio da decidere, e il ritiro delle tue.
                            regolePerId = proposte.regolePerId,
                            nomeFiglio = famiglia.figlioScelto?.nome,
                            piuDispositivi = proposte.piuDispositivi,
                            giaChiuse = proposte.giaChiuse,
                            lettaAlle = proposte.lettaAlle,
                            nomi = proposte.nomi,
                            scollegati = proposte.scollegati,
                            invioInCorso = proposte.invioInCorso,
                            onDecidi = { proposta, esito, motivazione ->
                                proposteVm.decidi(
                                    figlioId,
                                    proposta,
                                    esito,
                                    motivazione,
                                    da = ProposteViewModel.Schermata.PROPOSTE,
                                )
                            },
                            onRitira = { proposteVm.ritira(figlioId, it) },
                            // (0.11) Il numero accanto al nome conta anche le sessioni:
                            // una riga dice dove si decidono.
                            sessioniDaApprovare = famiglia.figlioScelto?.sessioniDaApprovare ?: 0,
                            // (0.13) Chi sei tu: le proposte degli altri genitori col loro nome.
                            io = famiglia.io,
                        )
                        sezioneDichiarazioni(
                            dichiarazioni = verdetti.dichiarazioni,
                            regolePerId = verdetti.regolePerId,
                            invioInCorso = verdetti.invioInCorso,
                            onVerdetto = { id, verdetto, nota ->
                                verdettiVm.emettiVerdetto(figlioId, id, verdetto, nota)
                            },
                            io = famiglia.io,
                        )
                    }
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
                proposteVm.creaProposta(figlioId, regola.id, parametri, motivazione)
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
