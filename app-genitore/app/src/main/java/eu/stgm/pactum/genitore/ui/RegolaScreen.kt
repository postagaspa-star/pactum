package eu.stgm.pactum.genitore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.design.StrisciaGiorni
import eu.stgm.pactum.design.StatoVuoto
import eu.stgm.pactum.design.Caricamento
import eu.stgm.pactum.design.Pillola
import eu.stgm.pactum.design.CardNormale
import eu.stgm.pactum.design.LegendaStriscia
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.RegolaFinestra
import eu.stgm.pactum.genitore.dati.TipiRegola

// (0.15) Il dettaglio di UNA regola, aperto toccandola nella card del patto (o da
// una notifica che ne parla): che cosa dice, la sua striscia degli 8 giorni, com'è
// andata oggi, di quale dispositivo è, i bonus del dispositivo, e "Proponi una
// modifica" — lo stesso dialogo di prima, che prima si apriva da un elenco di
// tutte le regole in "Proposte e conferme".

@Composable
fun RegolaScreen(
    regolaId: Long,
    finestraVm: FinestraViewModel = viewModel(),
    proposteVm: ProposteViewModel = viewModel(),
    famigliaVm: FamigliaViewModel = viewModel(),
) {
    val cornice = LocalCornice.current
    val statoFinestra by finestraVm.stato.collectAsStateWithLifecycle()
    val proposte by proposteVm.stato.collectAsStateWithLifecycle()
    val famiglia by famigliaVm.stato.collectAsStateWithLifecycle()
    val figlioId = famiglia.figlioId
    val testi = parole()
    val figlioMostrato by rememberUpdatedState(figlioId)

    // Le proposte servono a sapere se sulla regola ce n'è già una in attesa (il
    // server ne accetta una sola per regola): si rileggono a ogni ritorno, come in
    // "Proposte e conferme". La finestra, se non è di questo figlio.
    LifecycleResumeEffect(figlioId, famiglia.pronta) {
        if (famiglia.pronta) {
            proposteVm.aggiorna(figlioId)
            if (!statoFinestra.di(figlioId)) finestraVm.aggiorna(figlioId)
        }
        onPauseOrDispose { }
    }

    // rememberSaveable: una rotazione non deve buttare via la proposta in corso né
    // il confronto appena ricevuto.
    var proponi by rememberSaveable { mutableStateOf(false) }
    var confrontoInviato by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(proposteVm) {
        proposteVm.esiti(ProposteViewModel.Schermata.PROPOSTE).collect { evento ->
            val messaggio = when (evento) {
                is ProposteViewModel.Evento.Inviata -> {
                    proponi = false // chiudi il dialogo di creazione
                    confrontoInviato = evento.confronto
                    null
                }
                is ProposteViewModel.Evento.Errore -> {
                    // Già una proposta in attesa, o regola non più attiva: riprovare
                    // non serve. Via il dialogo, e si rilegge com'è davvero.
                    if (rifiutoPropostaDefinitivo(evento.codice)) {
                        proponi = false
                        proposteVm.aggiorna(figlioMostrato)
                    }
                    testi.testo(messaggioRifiutoProposta(evento.codice))
                }
                // Gli esiti delle risposte date da "Da decidere", arrivati mentre si è qui.
                is ProposteViewModel.Evento.Decisa -> {
                    famigliaVm.aggiorna()
                    testi.testo(messaggioDecisione(evento.esito, evento.eliminazione))
                }
                is ProposteViewModel.Evento.NonDecisa -> {
                    famigliaVm.aggiorna()
                    testi.testo(messaggioRifiutoDecisione(evento.codice, evento.statoFinale))
                }
                ProposteViewModel.Evento.Ritirata -> testi.testo(R.string.proposta_ritirata_fatto)
                is ProposteViewModel.Evento.NonRitirata -> testi.testo(messaggioRifiutoRitiro(evento.codice, evento.statoFinale))
            }
            if (messaggio != null) cornice.messaggi.mostra(messaggio)
        }
    }

    val finestra = statoFinestra.finestra.takeIf { statoFinestra.di(figlioId) }
    val regola = finestra?.regole?.firstOrNull { it.id == regolaId }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = { BarraPagina(stringResource(R.string.regola_titolo)) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                finestra == null && (statoFinestra.caricamento || !statoFinestra.di(figlioId)) ->
                    Caricamento(testo = stringResource(R.string.finestra_caricamento))
                finestra == null -> StatoVuoto(stringResource(R.string.finestra_errore_nessun_dato), centrato = true)
                regola == null -> StatoVuoto(stringResource(R.string.regola_non_trovata), centrato = true)
                else -> DettaglioRegola(
                    finestra = finestra,
                    regola = regola,
                    proposte = proposte.takeIf { it.di(figlioId) },
                    nomeFiglio = famiglia.figlioScelto?.nome,
                    onProponi = { proponi = true },
                )
            }
        }
    }

    if (proponi && regola != null) {
        DialogoNuovaProposta(
            regola = regola,
            invioInCorso = proposte.invioInCorso,
            onAnnulla = { proponi = false },
            onInvia = { parametri, motivazione -> proposteVm.creaProposta(figlioId, regola.id, parametri, motivazione) },
        )
    }

    confrontoInviato?.let { confronto ->
        AlertDialog(
            onDismissRequest = { confrontoInviato = null },
            title = { Text(stringResource(R.string.proposta_inviata_titolo)) },
            text = { Text(stringResource(R.string.proposta_inviata_confronto, confronto)) },
            confirmButton = {
                TextButton(onClick = { confrontoInviato = null }) { Text(stringResource(R.string.azione_ok)) }
            },
        )
    }
}

@Composable
private fun DettaglioRegola(
    finestra: Finestra,
    regola: RegolaFinestra,
    proposte: ProposteViewModel.StatoProposte?,
    nomeFiglio: String?,
    onProponi: () -> Unit,
) {
    val p = parole()
    val dispositivi = dispositiviDellaFinestra(finestra)
    val idDispositivo = regola.dispositivoId ?: regola.dispositivo?.id
    val dispositivo = dispositivi.firstOrNull { it.id != null && it.id == idDispositivo }
    val giorni = giorniDaQuadretti(regola.semaforo)
    val oggi = finestra.striscia.lastOrNull()?.data
    val rigaDiOggi = oggi?.let { giorno -> regoleDelGiorno(finestra, giorno).firstOrNull { it.regola.id == regola.id } }
    val proponibile = regoleProponibili(finestra).any { it.id == regola.id }
    // Una proposta appena chiusa da qui non tiene più ferma la sua regola.
    val ancoraAperte = proposte?.proposte.orEmpty()
        .filterNot { chiusaPrimaDellaLettura(it.id, proposte?.giaChiuse.orEmpty(), proposte?.lettaAlle) }
    val conPropostaInAttesa = regola.id in regoleConPropostaInAttesa(ancoraAperte)
    val propostaDelFiglio = regola.id in regoleConPropostaDelFiglio(ancoraAperte)

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spazi.l),
        verticalArrangement = Arrangement.spacedBy(Spazi.m),
    ) {
        Column {
            SopraTitolo(etichettaTipoRegola(regola.tipo), colore = MaterialTheme.colorScheme.primary)
            Text(
                text = descrizioneRegola(regola),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = Spazi.xs),
            )
            if (!regola.attiva) {
                Spacer(Modifier.height(Spazi.s))
                Pillola(stringResource(R.string.regola_eliminata))
            }
        }

        if (giorni.isNotEmpty()) {
            CardNormale {
                Column {
                    SopraTitolo(stringResource(R.string.patto_ultimi_giorni))
                    Spacer(Modifier.height(Spazi.s))
                    StrisciaGiorni(
                        giorni = giorni,
                        lato = 32.dp,
                        descrizione = descrizioneStriscia(giorni, R.plurals.striscia_descrizione_regola),
                    )
                    LegendaStriscia(
                        mantenuta = stringResource(R.string.legenda_mantenuta),
                        fuoriRegola = stringResource(R.string.legenda_fuori_regola),
                        senzaDati = stringResource(R.string.legenda_senza_dati),
                        oggi = stringResource(R.string.legenda_oggi),
                        modifier = Modifier.padding(top = Spazi.s),
                    )
                    if (rigaDiOggi != null) {
                        Text(
                            text = stringResource(R.string.regola_oggi, testoStatoRegola(p, rigaDiOggi)),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = Spazi.s),
                        )
                    }
                }
            }
        }

        // Di quale dispositivo è (la vita reale è del figlio, non di un dispositivo).
        if (dispositivo != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconaDispositivo(dispositivo.tipo)
                Column(modifier = Modifier.weight(1f).padding(start = Spazi.m)) {
                    Text(text = nomeDelDispositivo(dispositivo), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = testoStatoCanale(p, statoCanale(dispositivo), dispositivo.statoSilenzio, computer = dispositivo.computer),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // I bonus sono del dispositivo, non della regola: si dicono qui perché allungano
        // i suoi limiti di tempo. (I minuti di ogni giorno stanno nel Tempo.)
        val bonus = (dispositivo?.bonus ?: finestra.bonus.takeIf { dispositivi.size == 1 && dispositivi.first().id == null })
            ?.takeIf { regola.tipo == TipiRegola.LIMITE_TEMPO && regola.attiva && dispositivo?.revocato != true }
        if (bonus != null) {
            Column {
                SopraTitolo(stringResource(R.string.bonus_titolo))
                Text(
                    text = stringResource(
                        R.string.bonus_residui,
                        bonus.giorno.residui,
                        bonus.giorno.tetto,
                        bonus.settimana.residui,
                        bonus.settimana.tetto,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = Spazi.xs),
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Proporre, mai imporre. Con una proposta già in attesa il pulsante non c'è:
        // il server rifiuterebbe la seconda (409 `proposta_gia_pendente`), e al suo
        // posto una riga dice perché.
        when {
            // (0.15) Attiva ma di un dispositivo scollegato: non è "non più attiva".
            !proponibile -> StatoVuoto(
                stringResource(
                    if (regola.attiva && suDispositivoScollegato(regola, dispositiviScollegati(finestra))) {
                        R.string.regola_di_dispositivo_scollegato
                    } else {
                        R.string.regola_non_proponibile
                    },
                ),
            )
            conPropostaInAttesa -> {
                val nome = nomeDaScrivere(nomeFiglio)
                StatoVuoto(
                    when {
                        !propostaDelFiglio -> stringResource(R.string.proposte_gia_in_attesa)
                        nome != null -> stringResource(R.string.proposte_gia_proposta_del_figlio, nome)
                        else -> stringResource(R.string.proposte_gia_proposta_del_figlio_senza_nome)
                    },
                )
            }
            else -> Button(onClick = onProponi, enabled = proposte != null, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.proposte_bottone_proponi))
            }
        }
    }
}
