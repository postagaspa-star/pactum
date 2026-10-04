package eu.stgm.pactum.genitore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.design.TitoloSezione
import eu.stgm.pactum.design.RigaStato
import eu.stgm.pactum.design.StatoVuoto
import eu.stgm.pactum.design.Caricamento
import eu.stgm.pactum.design.RigaToccabile
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.CodiciErrore
import kotlinx.coroutines.delay

// (0.15) "Da decidere": UNA lista di tutto quello che aspetta il genitore per il
// figlio scelto, dal più vecchio al più nuovo — proposte del figlio (Accetta /
// Rifiuta), sessioni da approvare (Approva / Rifiuta), dichiarazioni da confermare
// (Confermo, e nel ⋯ "per conto di…" e "Non è andata così", con una domanda prima).
// Sotto, le proposte del genitore ancora in attesa (con Ritira). Le proposte chiuse
// e le dichiarazioni nel registro stanno nello Storico del patto.
//
// Prende il posto di "Proposte e conferme" e delle card in cima alla Panoramica:
// riusa gli stessi ViewModel e la stessa logica (versione vista delle sessioni,
// chiusura della domanda se la richiesta cambia, esiti), qui messi in una lista sola.

/** Ogni quanto si rilegge la finestra (le sessioni) mentre la scheda è davanti, come la Panoramica. */
private const val INTERVALLO_RILETTURA_MS = 60_000L

@Composable
fun DaDecidereScreen(
    proposteVm: ProposteViewModel = viewModel(),
    verdettiVm: VerdettiViewModel = viewModel(),
    finestraVm: FinestraViewModel = viewModel(),
    famigliaVm: FamigliaViewModel = viewModel(),
) {
    val cornice = LocalCornice.current
    val messaggi = cornice.messaggi
    val proposte by proposteVm.stato.collectAsStateWithLifecycle()
    val verdetti by verdettiVm.stato.collectAsStateWithLifecycle()
    val statoFinestra by finestraVm.stato.collectAsStateWithLifecycle()
    val famiglia by famigliaVm.stato.collectAsStateWithLifecycle()
    // (v3) Tutto è del figlio scelto in cima: letture e scritture.
    val figlioId = famiglia.figlioId
    val testi = parole()
    val figlioMostrato by rememberUpdatedState(figlioId)

    val aggiornaTutto = {
        proposteVm.aggiorna(figlioId)
        verdettiVm.aggiorna(figlioId)
    }
    // Proposte e dichiarazioni a ogni ritorno in primo piano e a ogni cambio di
    // figlio, quando si sa di quale figlio (famiglia pronta), come "Proposte e conferme".
    LifecycleResumeEffect(figlioId, famiglia.pronta) {
        if (famiglia.pronta) aggiornaTutto()
        onPauseOrDispose { }
    }
    // Le sessioni vengono dalla finestra: si rilegge come nella Panoramica (dove
    // stavano prima), così una richiesta cambiata nel frattempo chiude la domanda.
    val cicloVita = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(cicloVita, figlioId, famiglia.pronta) {
        if (!famiglia.pronta) return@LaunchedEffect
        cicloVita.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                finestraVm.aggiorna(figlioId)
                delay(INTERVALLO_RILETTURA_MS)
            }
        }
    }
    // (0.10) Ogni finestra nuova si dice al ViewModel delle proposte (v. Panoramica).
    LaunchedEffect(statoFinestra.figlioId, statoFinestra.lettaAlle) {
        val letta = statoFinestra.lettaAlle ?: return@LaunchedEffect
        if (statoFinestra.richiesta) proposteVm.finestraLetta(statoFinestra.figlioId, letta)
    }

    // Gli esiti delle proposte: il confronto in un dialogo, il resto in basso.
    // Le frasi vengono dal codice `errore` del 409 (Testi.kt). Arrivano da un canale,
    // uno per volta: nessuno copre l'altro.
    var confrontoInviato by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(proposteVm) {
        proposteVm.esiti(ProposteViewModel.Schermata.PROPOSTE).collect { evento ->
            val messaggio = when (evento) {
                is ProposteViewModel.Evento.Inviata -> {
                    confrontoInviato = evento.confronto
                    null
                }
                is ProposteViewModel.Evento.Errore -> {
                    if (rifiutoPropostaDefinitivo(evento.codice)) proposteVm.aggiorna(figlioMostrato)
                    testi.testo(messaggioRifiutoProposta(evento.codice))
                }
                // La risposta a una proposta del figlio, e il ritiro di una tua:
                // l'elenco si rilegge da sé, la famiglia qui (i numeri), e la finestra.
                is ProposteViewModel.Evento.Decisa -> {
                    famigliaVm.aggiorna()
                    finestraVm.aggiorna(figlioMostrato)
                    testi.testo(messaggioDecisione(evento.esito, evento.eliminazione))
                }
                is ProposteViewModel.Evento.NonDecisa -> {
                    famigliaVm.aggiorna()
                    finestraVm.aggiorna(figlioMostrato)
                    testi.testo(messaggioRifiutoDecisione(evento.codice, evento.statoFinale))
                }
                ProposteViewModel.Evento.Ritirata -> testi.testo(R.string.proposta_ritirata_fatto)
                is ProposteViewModel.Evento.NonRitirata ->
                    testi.testo(messaggioRifiutoRitiro(evento.codice, evento.statoFinale))
            }
            if (messaggio != null) messaggi.mostra(messaggio)
        }
    }
    // Gli esiti delle conferme.
    LaunchedEffect(verdetti.evento) {
        val evento = verdetti.evento ?: return@LaunchedEffect
        verdettiVm.consumaEvento()
        val messaggio = when (evento) {
            is VerdettiViewModel.Evento.Inviato -> testi.testo(R.string.verdetto_inviato)
            is VerdettiViewModel.Evento.Errore -> {
                // Qualcuno ha già risposto (l'arbitro, l'altro genitore): si rilegge,
                // così la dichiarazione esce dalla lista.
                if (evento.codice == CodiciErrore.DICHIARAZIONE_NON_IN_ATTESA) verdettiVm.aggiorna(figlioMostrato)
                testi.testo(messaggioRifiutoVerdetto(evento.codice))
            }
        }
        messaggi.mostra(messaggio)
    }

    // (0.11) L'esito della risposta a una sessione: una frase in basso. La finestra
    // si rilegge da sé (FinestraViewModel); qui la famiglia, per i numeri. Se nel
    // frattempo il genitore guarda un altro figlio, la frase dice di chi era.
    LaunchedEffect(statoFinestra.esitoSessione) {
        val arrivato = statoFinestra.esitoSessione ?: return@LaunchedEffect
        finestraVm.consumaEsitoSessione()
        if (daRileggereDopo(arrivato.esito)) famigliaVm.aggiorna()
        val frase = testi.testo(messaggioEsitoSessione(arrivato.esito))
        val messaggio = if (arrivato.figlioId != figlioMostrato) {
            esitoPerIlFiglio(testi, famiglia.figli.firstOrNull { it.id == arrivato.figlioId }?.nome, frase)
        } else {
            frase
        }
        messaggi.mostra(messaggio)
    }

    val aggiorna = {
        famigliaVm.aggiorna()
        if (famiglia.pronta) {
            aggiornaTutto()
            finestraVm.aggiorna(figlioId)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = { BarraScheda(stringResource(R.string.da_decidere_titolo), onAggiorna = aggiorna) },
    ) { padding ->
        // Solo i dati DI QUESTO figlio: finché non arrivano, la rotella.
        val delFiglio = proposte.di(figlioId) && verdetti.di(figlioId)
        // "Niente in mano": né regole, né proposte, né dichiarazioni lette finora.
        val nienteInMano = proposte.proposte.isEmpty() &&
            proposte.regoleAttive.isEmpty() &&
            verdetti.dichiarazioni.isEmpty()
        // (0.15) La scelta del figlio: fissa in cima mentre si carica, con un errore o
        // senza elenco (si cambia figlio anche quando i dati di uno non arrivano);
        // con l'elenco è la sua prima riga e scorre col resto.
        val conElenco = !famiglia.collegamentoNonValido &&
            !(proposte.configurazioneMancante || verdetti.configurazioneMancante) &&
            delFiglio &&
            !(nienteInMano && (proposte.caricamento || verdetti.caricamento || proposte.errore || verdetti.errore))
        ConSceltaFiglio(famiglia, fissa = !conElenco, modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                famiglia.collegamentoNonValido -> StatoVuoto(
                    centrato = true,
                    titolo = stringResource(R.string.collegamento_non_valido_titolo),
                    testo = stringResource(R.string.collegamento_non_valido),
                    azione = stringResource(R.string.azione_collega_di_nuovo),
                    onAzione = { cornice.apri(Pagina.Impostazioni(SezioneImpostazioni.COLLEGAMENTO)) },
                )

                proposte.configurazioneMancante || verdetti.configurazioneMancante -> StatoVuoto(
                    centrato = true,
                    titolo = stringResource(R.string.config_mancante_titolo),
                    testo = stringResource(R.string.turno_config_mancante),
                    azione = stringResource(R.string.azione_collega),
                    onAzione = { cornice.apri(Pagina.Impostazioni(SezioneImpostazioni.COLLEGAMENTO)) },
                )

                !delFiglio || (nienteInMano && (proposte.caricamento || verdetti.caricamento)) ->
                    Caricamento(testo = stringResource(R.string.turno_caricamento))

                nienteInMano && (proposte.errore || verdetti.errore) -> StatoVuoto(stringResource(R.string.turno_errore), centrato = true)

                else -> ListaDaDecidere(
                    proposte = proposte,
                    verdetti = verdetti,
                    statoFinestra = statoFinestra,
                    famiglia = famiglia,
                    onDecidiProposta = { proposta, esito, motivazione ->
                        proposteVm.decidi(figlioId, proposta, esito, motivazione, da = ProposteViewModel.Schermata.PROPOSTE)
                    },
                    onRitira = { proposteVm.ritira(figlioId, it) },
                    onDecidiSessione = { richiesta, esito, motivazione ->
                        finestraVm.decidiSessione(figlioId, richiesta, esito, motivazione)
                    },
                    onAvvisoSessione = { messaggi.mostra(testi.testo(it)) },
                    onVerdetto = { id, verdetto, nota -> verdettiVm.emettiVerdetto(figlioId, id, verdetto, nota) },
                )
            }
        }
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
private fun ListaDaDecidere(
    proposte: ProposteViewModel.StatoProposte,
    verdetti: VerdettiViewModel.StatoVerdetti,
    statoFinestra: FinestraViewModel.StatoFinestra,
    famiglia: FamigliaViewModel.StatoFamiglia,
    onDecidiProposta: (eu.stgm.pactum.genitore.dati.Proposta, String, String?) -> Unit,
    onRitira: (eu.stgm.pactum.genitore.dati.Proposta) -> Unit,
    onDecidiSessione: (SessioneDaApprovare, String, String?) -> Unit,
    onAvvisoSessione: (Int) -> Unit,
    onVerdetto: (Long, String, String?) -> Unit,
) {
    val cornice = LocalCornice.current
    val figlioId = famiglia.figlioId
    val nomeFiglio = famiglia.figlioScelto?.nome
    // Le sessioni da approvare: quelle appena decise da qui spariscono subito,
    // finché la finestra è di prima; quelle dei telefoni scollegati non ci sono.
    val finestra = statoFinestra.finestra.takeIf { statoFinestra.di(figlioId) }
    val dispositivi = remember(finestra) { finestra?.let(::dispositiviDellaFinestra).orEmpty() }
    val sessioni = finestra?.let {
        sessioniDaApprovare(it.sessioni, statoFinestra.sessioniDecise, statoFinestra.lettaAlle, dispositiviScollegati(it))
    }.orEmpty()
    val nomiFinestra = remember(finestra) { finestra?.let(::nomiDelleApp).orEmpty() }
    val piuDispositiviAttivi = finestra?.let(::piuDispositiviAttivi) ?: proposte.piuDispositivi

    // (0.15) Le proposte vengono dalla finestra, riletta ogni minuto (come nella
    // Panoramica di prima): una proposta arrivata con la scheda aperta compare
    // senza letture in più, e lista e numero nascono dalla stessa lettura.
    // Finché la finestra di questo figlio non c'è, quelle lette all'apertura.
    val elencoProposte = finestra?.propostePendenti ?: proposte.proposte
    val lettaProposte = if (finestra != null) statoFinestra.lettaAlle else proposte.lettaAlle
    val regoleFinestra = remember(finestra) { finestra?.regole?.associateBy { it.id }.orEmpty() }
    val scollegati = finestra?.let(::dispositiviScollegati) ?: proposte.scollegati
    val daDecidere = proposteDaDecidere(elencoProposte, proposte.giaChiuse, lettaProposte)
    val pendenti = proposteInAttesaDelFiglio(elencoProposte, proposte.giaChiuse, lettaProposte)
    val voci = vociDaDecidere(daDecidere, sessioni, verdetti.dichiarazioni)

    // (0.11) La domanda aperta su una sessione (approva / rifiuta): quale, con quale
    // gesto, e la versione che il genitore aveva davanti. [vista] = il contenuto di
    // allora: si mostra e si risponde su quello, mai su quello che il giro di ogni
    // minuto porta dopo. Se la card cambia (o sparisce) mentre la domanda è aperta,
    // la domanda si chiude e lo si dice; ritrovata dopo che Android ha chiuso l'app,
    // vale solo sulla stessa versione, se no si lascia cadere.
    var domanda by rememberSaveable(stateSaver = SalvaDomandaSessione) { mutableStateOf<DomandaSessione?>(null) }
    var vista by remember { mutableStateOf<SessioneDaApprovare?>(null) }
    var apertaQui by remember { mutableStateOf(false) }
    val statoDellaDomanda = domanda?.let { statoDomanda(it, sessioni) }
    LaunchedEffect(domanda, statoDellaDomanda) {
        if (domanda == null || statoDellaDomanda == null || statoDellaDomanda == StatoDomanda.VALIDA) return@LaunchedEffect
        val avvisa = apertaQui
        domanda = null
        vista = null
        apertaQui = false
        if (avvisa) messaggioDomandaChiusa(statoDellaDomanda)?.let(onAvvisoSessione)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spazi.l),
        verticalArrangement = Arrangement.spacedBy(Spazi.m),
    ) {
        sceltaDelFiglio(famiglia)
        // Un aggiornamento fallito con i dati già in mano: si dice che sono vecchi.
        if (proposte.errore || verdetti.errore) {
            item(key = "dati-vecchi") { RigaStato(stringResource(R.string.turno_dati_vecchi)) }
        }

        if (voci.isEmpty()) {
            item(key = "vuoto") {
                StatoVuoto(
                    stringResource(R.string.da_decidere_vuoto),
                    icona = Icons.Outlined.CheckCircle,
                    modifier = Modifier.padding(vertical = Spazi.s),
                )
            }
        }

        items(voci, key = { it.chiave }) { voce ->
            when (voce) {
                is VoceDaDecidere.DiProposta -> {
                    // La regola: dalla finestra (la più fresca, come la proposta), se c'è.
                    val regola = regoleFinestra[voce.proposta.regolaId] ?: proposte.regolePerId[voce.proposta.regolaId]
                    CardPropostaDaDecidere(
                        proposta = voce.proposta,
                        regola = regola,
                        nomeFiglio = nomeFiglio,
                        piuDispositivi = proposte.piuDispositivi,
                        invioInCorso = proposte.invioInCorso,
                        onDecidi = { esito, motivazione -> onDecidiProposta(voce.proposta, esito, motivazione) },
                        nomi = proposte.nomi,
                        scollegata = suDispositivoScollegato(regola, scollegati),
                    )
                }
                is VoceDaDecidere.DiSessione -> CardSessioneDaApprovare(
                    richiesta = voce.richiesta,
                    nomeFiglio = nomeFiglio,
                    dove = doveSta(parole(), telefonoDellaSessione(voce.richiesta.sessione, dispositivi), piuDispositiviAttivi),
                    nomiFinestra = nomiFinestra,
                    invioInCorso = statoFinestra.invioSessione,
                    onApri = { esito ->
                        val versione = voce.richiesta.sessione.versione
                        if (versione != null) {
                            domanda = DomandaSessione(voce.richiesta.sessione.id, esito, versione)
                            vista = voce.richiesta
                            apertaQui = true
                        }
                    },
                )
                is VoceDaDecidere.DiDichiarazione -> CardDichiarazione(
                    dichiarazione = voce.dichiarazione,
                    regola = verdetti.regolePerId[voce.dichiarazione.regolaId],
                    invioInCorso = verdetti.invioInCorso,
                    onVerdetto = onVerdetto,
                )
            }
        }

        // Che cos'è una sessione, detto UNA volta sola, sotto le card (non su ognuna).
        if (sessioni.isNotEmpty()) {
            item(key = "sessione-non-conta") {
                Text(
                    text = stringResource(R.string.sessione_non_conta),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Le proposte del genitore (anche degli altri genitori) che aspettano il figlio.
        if (pendenti.isNotEmpty()) {
            item(key = "pendenti-titolo") { TitoloSezione(stringResource(R.string.proposte_in_attesa)) }
            items(pendenti, key = { "pendente-${it.id}" }) {
                CardPropostaPendente(
                    proposta = it,
                    regola = proposte.regolePerId[it.regolaId],
                    invioInCorso = proposte.invioInCorso,
                    onRitira = onRitira,
                    io = famiglia.io,
                )
            }
        }

        item(key = "storico") {
            RigaToccabile(
                titolo = stringResource(R.string.sezione_storico),
                onClick = { cornice.apri(Pagina.Storico) },
                modifier = Modifier.padding(top = Spazi.s),
            )
        }
    }

    // (0.11) La domanda aperta, solo finché la card ha ancora la versione vista: si
    // mostra il contenuto di allora, e si risponde con quella versione.
    val aperta = domanda
    if (aperta != null && statoDellaDomanda == StatoDomanda.VALIDA) {
        val corrente = sessioni.firstOrNull { it.sessione.id == aperta.sessioneId }
        val contenuto = vista?.takeIf { it.sessione.id == aperta.sessioneId && it.sessione.versione == aperta.versione }
            ?: corrente
        if (contenuto != null) {
            val chiudi = {
                domanda = null
                vista = null
                apertaQui = false
            }
            DialogoDecisioneSessione(
                esito = aperta.esito,
                richiesta = contenuto,
                nomeFiglio = nomeFiglio,
                nomiFinestra = nomiFinestra,
                onConferma = { motivazione ->
                    chiudi()
                    onDecidiSessione(contenuto, aperta.esito, motivazione)
                },
                onAnnulla = chiudi,
            )
        }
    }
}
