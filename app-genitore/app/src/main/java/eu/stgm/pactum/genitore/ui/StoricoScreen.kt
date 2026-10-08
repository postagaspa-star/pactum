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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import eu.stgm.pactum.design.TitoloSezione
import eu.stgm.pactum.design.RigaStato
import eu.stgm.pactum.design.StatoVuoto
import eu.stgm.pactum.design.Caricamento
import eu.stgm.pactum.design.Pillola
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.ModificaStorico
import eu.stgm.pactum.genitore.dati.RegolaFinestra
import eu.stgm.pactum.genitore.dati.StatiDichiarazione
import java.time.Instant
import androidx.compose.ui.graphics.Color
import eu.stgm.pactum.design.SchermataColorata
import eu.stgm.pactum.design.Sezione

// (0.15) Lo Storico del patto, a pagina intera: le quattro storie che prima
// stavano sparse in tre posti — le regole cambiate (in fondo alla Panoramica), le
// sessioni fatte e approvate (nella Panoramica), le proposte chiuse e le
// dichiarazioni nel registro (in "Proposte e conferme"). Del figlio scelto.

@Composable
fun StoricoScreen(
    finestraVm: FinestraViewModel = viewModel(),
    proposteVm: ProposteViewModel = viewModel(),
    verdettiVm: VerdettiViewModel = viewModel(),
    famigliaVm: FamigliaViewModel = viewModel(),
) {
    val statoFinestra by finestraVm.stato.collectAsStateWithLifecycle()
    val proposte by proposteVm.stato.collectAsStateWithLifecycle()
    val verdetti by verdettiVm.stato.collectAsStateWithLifecycle()
    val famiglia by famigliaVm.stato.collectAsStateWithLifecycle()
    val figlioId = famiglia.figlioId

    // Le stesse letture di "Proposte e conferme" (proposte e dichiarazioni) e della
    // Panoramica (la finestra), a ogni ritorno in primo piano.
    val aggiornaTutto = {
        finestraVm.aggiorna(figlioId)
        proposteVm.aggiorna(figlioId)
        verdettiVm.aggiorna(figlioId)
    }
    LifecycleResumeEffect(figlioId, famiglia.pronta) {
        if (famiglia.pronta) aggiornaTutto()
        onPauseOrDispose { }
    }

    SchermataColorata(Sezione.STORICO) {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = { BarraPagina(stringResource(R.string.sezione_storico)) },
        ) { padding ->
            val finestra = statoFinestra.finestra.takeIf { statoFinestra.di(figlioId) }
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                when {
                    famiglia.collegamentoNonValido -> StatoVuoto(
                        titolo = stringResource(R.string.collegamento_non_valido_titolo),
                        testo = stringResource(R.string.collegamento_non_valido),
                        centrato = true,
                    )
                    finestra == null && (statoFinestra.caricamento || !statoFinestra.di(figlioId)) ->
                        Caricamento(testo = stringResource(R.string.storico_caricamento))
                    finestra == null -> StatoVuoto(stringResource(R.string.finestra_errore_nessun_dato), centrato = true)
                    else -> ContenutoStorico(
                        finestra = finestra,
                        proposte = proposte.takeIf { it.di(figlioId) },
                        verdetti = verdetti.takeIf { it.di(figlioId) },
                        nomeFiglio = famiglia.figlioScelto?.nome,
                        io = famiglia.io,
                        errore = statoFinestra.errore || proposte.errore || verdetti.errore,
                    )
                }
            }
        }
    }
}

@Composable
private fun ContenutoStorico(
    finestra: eu.stgm.pactum.genitore.dati.Finestra,
    proposte: ProposteViewModel.StatoProposte?,
    verdetti: VerdettiViewModel.StatoVerdetti?,
    nomeFiglio: String?,
    io: eu.stgm.pactum.genitore.dati.RiferimentoGenitore?,
    errore: Boolean,
) {
    val regolePerId = remember(finestra) { finestra.regole.associateBy { it.id } }
    val dispositivi = remember(finestra) { dispositiviDellaFinestra(finestra) }
    val piuDispositivi = finestraPerDispositivo(finestra) && dispositivi.size > 1
    val nomi = remember(finestra) { nomiDelleApp(finestra) }
    // (0.11) Le sessioni: quelle fatte negli 8 giorni (raccontate rispetto ad
    // adesso: una "in corso" letta prima della fine prevista è finita), quelle
    // approvate, e il nome del telefono quando il figlio ne ha più d'uno.
    val svolte = remember(finestra) { sessioniSvolteRaccontate(finestra.sessioniSvolte, Instant.now()) }
    val conPiuTelefoni = remember(finestra) { piuTelefoni(finestra) }
    val telefonoDi: (Long?) -> String? = { id -> if (conPiuTelefoni) nomeDispositivo(id, dispositivi) else null }
    val chiuse = proposte?.let { proposteChiuse(it.proposte) }.orEmpty()
    val registro = verdetti?.dichiarazioni?.filter { it.stato != StatiDichiarazione.IN_ATTESA }.orEmpty()

    var tutteLeSvolte by rememberSaveable { mutableStateOf(false) }
    var approvateAperte by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spazi.l),
        verticalArrangement = Arrangement.spacedBy(Spazi.m),
    ) {
        if (errore) item(key = "dati-vecchi") { RigaStato(stringResource(R.string.turno_dati_vecchi)) }

        // 1. Le regole cambiate.
        item(key = "regole-titolo") { TitoloSezione(stringResource(R.string.storico_regole)) }
        if (finestra.storicoModifiche.isEmpty()) {
            item(key = "regole-vuoto") { StatoVuoto(stringResource(R.string.storico_regole_vuoto), emoji = "📜") }
        } else {
            items(finestra.storicoModifiche, key = { "modifica-${it.id}" }) {
                RigaStorico(it, regolePerId, mostraDispositivo = piuDispositivi)
            }
        }

        // 2. Le sessioni fatte (la storia). (0.16) Le approvate e le non più valide
        // sono lo stato di adesso: stanno nella pagina Sessioni.
        if (svolte.isNotEmpty()) {
            sezioneSessioni(
                svolte = svolte,
                approvate = emptyList(),
                nonPiuValide = emptyList(),
                nomiFinestra = nomi,
                telefono = telefonoDi,
                tutteLeSvolte = tutteLeSvolte,
                onTutteLeSvolte = { tutteLeSvolte = !tutteLeSvolte },
                approvateAperte = approvateAperte,
                onApprovate = { approvateAperte = !approvateAperte },
                io = io,
            )
        }

        // 3. Le proposte chiuse, di tutti e due.
        item(key = "proposte-titolo") { TitoloSezione(stringResource(R.string.storico_proposte)) }
        if (proposte == null) {
            item(key = "proposte-caricamento") { Caricamento(testo = stringResource(R.string.turno_caricamento), centrato = false) }
        } else if (chiuse.isEmpty()) {
            item(key = "proposte-vuoto") { StatoVuoto(stringResource(R.string.storico_proposte_vuoto), emoji = "📭") }
        } else {
            items(chiuse, key = { "proposta-${it.id}" }) {
                RigaPropostaChiusa(it, regolePerId[it.regolaId] ?: proposte.regolePerId[it.regolaId], nomeFiglio, piuDispositivi, nomi, io)
            }
        }

        // 4. Le dichiarazioni nel registro.
        item(key = "dichiarazioni-titolo") { TitoloSezione(stringResource(R.string.storico_dichiarazioni)) }
        if (verdetti == null) {
            item(key = "dichiarazioni-caricamento") { Caricamento(testo = stringResource(R.string.turno_caricamento), centrato = false) }
        } else if (registro.isEmpty()) {
            item(key = "dichiarazioni-vuoto") { StatoVuoto(stringResource(R.string.storico_dichiarazioni_vuoto), emoji = "📖") }
        } else {
            items(registro, key = { "dichiarazione-${it.id}" }) {
                RigaRisolta(it, regolePerId[it.regolaId] ?: verdetti.regolePerId[it.regolaId], io)
            }
        }
    }
}

/** Una regola cambiata: come, quando, e la regola com'era allora. */
@Composable
private fun RigaStorico(
    modifica: ModificaStorico,
    regolePerId: Map<Long, RegolaFinestra>,
    mostraDispositivo: Boolean,
) {
    val titolo = when (modifica.azione) {
        "creazione" -> stringResource(R.string.storico_creazione)
        "modifica" -> if (modifica.direzione == "stringe") {
            stringResource(R.string.storico_modifica_stringe)
        } else {
            stringResource(R.string.storico_modifica_allenta)
        }
        "eliminazione" -> stringResource(R.string.storico_eliminazione)
        else -> modifica.azione
    }
    // La regola raccontata coi parametri DELLA modifica (dopo, o prima se
    // eliminata), non con quelli vigenti: lo storico racconta il passato
    // (descrizioneParametri, la stessa delle notifiche di modifica).
    val parametri = modifica.dopo ?: modifica.prima
    val regola = regolePerId[modifica.regolaId]
    Column(modifier = Modifier.fillMaxWidth()) {
        val nomeDispositivo = regola?.dispositivo?.nome?.takeIf { mostraDispositivo && it.isNotBlank() }
        val quando = istanteServer(modifica.tsServer)?.let { testoQuando(parole(), it) }
        SopraTitolo(listOfNotNull(nomeDispositivo, quando).joinToString(" · "))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = titolo,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (modifica.concordata) {
                Pillola(stringResource(R.string.storico_concordata))
            }
        }
        if (regola != null && parametri != null) {
            Text(
                text = descrizioneParametri(regola, parametri),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
