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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.design.Caricamento
import eu.stgm.pactum.design.CardEvidenza
import eu.stgm.pactum.design.CardNormale
import eu.stgm.pactum.design.FilaPulsanti
import eu.stgm.pactum.design.RigaStato
import eu.stgm.pactum.design.SezioneEspandibile
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.design.StatoVuoto
import eu.stgm.pactum.design.TitoloSezione
import eu.stgm.pactum.design.Tono
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.RiferimentoGenitore
import eu.stgm.pactum.genitore.dati.StatiConfigStudio
import eu.stgm.pactum.genitore.dati.StudioSvolto
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import androidx.compose.ui.graphics.Color
import eu.stgm.pactum.design.SchermataColorata
import eu.stgm.pactum.design.Sezione
import eu.stgm.pactum.design.AnelloAttivita
import eu.stgm.pactum.design.ColoriAttivita
import eu.stgm.pactum.design.FettaAttivita
import eu.stgm.pactum.design.rememberContatore
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import eu.stgm.pactum.genitore.dati.TipiTratto

// (0.18, contratto v4.0, parte C) La pagina della Sessione Studio del figlio
// scelto: lo Studio in corso (con "Chiudi lo Studio"), dove lo Studio non c'è
// (app più vecchie della 0.18), com'è approvato adesso, gli Studi fatti (inizio,
// tratti con tipo e minuti, chiusura con la dichiarazione o il motivo, oppure "non
// chiuso") e le versioni approvate con chi le ha approvate. Si apre dalla
// Panoramica ("Vedi lo Studio") e dalle notifiche dello Studio.

/** Ogni quanto si rilegge lo Studio mentre la pagina è davanti. */
private const val INTERVALLO_RILETTURA_STUDIO_MS = 60_000L

@Composable
fun StudioScreen(
    vm: StudioViewModel = viewModel(),
    finestraVm: FinestraViewModel = viewModel(),
    famigliaVm: FamigliaViewModel = viewModel(),
) {
    val cornice = LocalCornice.current
    val stato by vm.stato.collectAsStateWithLifecycle()
    val statoFinestra by finestraVm.stato.collectAsStateWithLifecycle()
    val famiglia by famigliaVm.stato.collectAsStateWithLifecycle()
    val figlioId = famiglia.figlioId
    val nomeFiglio = famiglia.figlioScelto?.nome
    val p = parole()

    val cicloVita = LocalLifecycleOwner.current.lifecycle
    var adesso by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(cicloVita, figlioId, famiglia.pronta) {
        if (!famiglia.pronta) return@LaunchedEffect
        cicloVita.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // I titoli dei lavori dei tratti "lavori di casa" vengono dalla finestra:
            // aperta da una notifica, la pagina la chiede se non c'è.
            if (!finestraVm.stato.value.di(figlioId)) finestraVm.aggiorna(figlioId)
            while (true) {
                vm.aggiorna(figlioId)
                adesso = Instant.now()
                delay(INTERVALLO_RILETTURA_STUDIO_MS)
            }
        }
    }

    // Gli esiti si dicono una volta; dopo una chiusura anche finestra e famiglia si rileggono.
    LaunchedEffect(stato.evento) {
        val evento = stato.evento ?: return@LaunchedEffect
        vm.consumaEvento()
        famigliaVm.aggiorna()
        if (famiglia.pronta) finestraVm.aggiorna(figlioId)
        cornice.messaggi.mostra(messaggioEventoStudio(p, evento))
    }

    var daChiudere by rememberSaveable { mutableStateOf<Long?>(null) }

    SchermataColorata(Sezione.STUDIO) {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = { BarraPagina(stringResource(R.string.studio_titolo)) },
        ) { padding ->
            val pacco = stato.pacco.takeIf { stato.di(figlioId) }
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                when {
                    famiglia.collegamentoNonValido || stato.collegamentoNonValido -> StatoVuoto(
                        titolo = stringResource(R.string.collegamento_non_valido_titolo),
                        testo = stringResource(R.string.collegamento_non_valido),
                        centrato = true,
                    )
                    stato.configurazioneMancante -> StatoVuoto(
                        centrato = true,
                        titolo = stringResource(R.string.config_mancante_titolo),
                        testo = stringResource(R.string.finestra_config_mancante),
                    )
                    stato.serverVecchio && stato.di(figlioId) -> StatoVuoto(
                        titolo = stringResource(R.string.faccende_server_vecchio_titolo),
                        testo = stringResource(R.string.studio_server_vecchio),
                        centrato = true,
                    )
                    pacco == null && (stato.caricamento || !stato.di(figlioId)) ->
                        Caricamento(testo = stringResource(R.string.studio_caricamento))
                    pacco == null -> StatoVuoto(stringResource(R.string.studio_errore), centrato = true)
                    else -> ContenutoStudio(
                        stato = stato,
                        famiglia = famiglia,
                        nomeFiglio = nomeFiglio,
                        adesso = adesso,
                        titoloLavoro = { id ->
                            statoFinestra.finestra?.takeIf { statoFinestra.di(figlioId) }?.faccende
                                ?.firstOrNull { it.id == id }?.titolo
                        },
                        onChiudi = { daChiudere = it.id },
                        onAltri = { vm.altri(figlioId) },
                        onDaDecidere = { cornice.vaiAScheda(Scheda.DA_DECIDERE) },
                    )
                }
            }
        }
    }

    daChiudere?.let { id ->
        DialogoChiudiStudio(
            nomeFiglio = nomeFiglio,
            studioId = id,
            onChiudi = { motivo ->
                daChiudere = null
                vm.chiudi(figlioId, id, motivo)
            },
            onAnnulla = { daChiudere = null },
        )
    }
}

@Composable
private fun ContenutoStudio(
    stato: StatoStudio,
    famiglia: FamigliaViewModel.StatoFamiglia,
    nomeFiglio: String?,
    adesso: Instant,
    titoloLavoro: (Long) -> String?,
    onChiudi: (StudioSvolto) -> Unit,
    onAltri: () -> Unit,
    onDaDecidere: () -> Unit,
) {
    val p = parole()
    val pacco = stato.pacco ?: return
    val io = famiglia.io
    val inCorso = pacco.inCorso?.takeIf { it.fineTs == null }
    val svolte = (stato.svolte ?: pacco.recenti).filter { it.id != inCorso?.id }
    val senzaStudio = remember(famiglia.figlioScelto) { dispositiviSenzaStudio(famiglia.figlioScelto?.dispositivi.orEmpty()) }
    val config = pacco.config
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spazi.l),
        verticalArrangement = Arrangement.spacedBy(Spazi.m),
    ) {
        if (stato.errore) item(key = "dati-vecchi") { RigaStato(stringResource(R.string.studio_dati_vecchi)) }

        // --- In corso ------------------------------------------------------------------------
        if (inCorso != null) {
            item(key = "in-corso") {
                CardEvidenza(tono = Tono.Neutro) {
                    SopraTitolo(stringResource(R.string.studio_in_corso_titolo), colore = androidx.compose.material3.LocalContentColor.current)
                    Text(
                        text = testoStatoStudio(p, inCorso, adesso),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = Spazi.xs),
                    )
                    Text(
                        text = testoOrigineStudio(p, inCorso).replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = Spazi.xs),
                    )
                    testoChiudibile(p, inCorso, nomeFiglio)?.let {
                        Text(text = it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Spazi.xs))
                    }
                    // (0.19) L'anello: quanto manca al minimo, a colori per attività (come sul telefono del figlio).
                    AnelloStudioInCorso(inCorso)
                    Column(modifier = Modifier.padding(top = Spazi.s), verticalArrangement = Arrangement.spacedBy(Spazi.xs)) {
                        val tratti = trattiInOrdine(inCorso)
                        if (tratti.isEmpty()) {
                            Text(stringResource(R.string.studio_nessun_tratto), style = MaterialTheme.typography.bodyMedium)
                        }
                        tratti.forEach { Text("• " + testoTratto(p, it, titoloLavoro), style = MaterialTheme.typography.bodyMedium) }
                    }
                    if (chiudibileDalGenitore(inCorso)) {
                        FilaPulsanti(modifier = Modifier.padding(top = Spazi.m)) {
                            OutlinedButton(onClick = { onChiudi(inCorso) }, enabled = !stato.invio) {
                                Text(stringResource(R.string.studio_chiudi), maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
        }

        // Dove lo Studio non c'è (app più vecchie della 0.18): detto una volta.
        items(senzaStudio, key = { "senza-studio-${it.dispositivo.id}" }) { RigaStato(testoDispositivoSenzaStudio(p, it)) }

        // --- Com'è approvato ----------------------------------------------------------------
        item(key = "config-titolo") { TitoloSezione(stringResource(R.string.studio_config_titolo)) }
        item(key = "config") {
            val approvata = config?.approvata
            CardNormale {
                if (approvata == null) {
                    Text(
                        text = nomeDaScrivere(nomeFiglio)?.let { p.testo(R.string.studio_non_approvato, it) }
                            ?: p.testo(R.string.studio_non_approvato_senza_nome),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                } else {
                    righeConfigStudio(p, approvata).forEach {
                        Text(text = it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = Spazi.xs))
                    }
                    Text(
                        text = testoDecisaStudio(p, approvata, io),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spazi.xs),
                    )
                    testoOrariDal(p, approvata.orariDal, LocalDate.now(FUSO_PATTO))?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = Spazi.xs),
                        )
                    }
                }
                if (config?.inAttesa != null && config.stato != StatiConfigStudio.RIFIUTATA) {
                    TextButton(onClick = onDaDecidere, modifier = Modifier.padding(top = Spazi.xs)) {
                        Text(testoChiedeStudio(p, nomeFiglio, cambio = approvata != null))
                    }
                }
            }
        }
        item(key = "minuti-dichiarati") {
            Text(
                text = testoMinutiDichiarati(p, nomeFiglio),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // --- Gli Studi fatti -------------------------------------------------------------------
        item(key = "svolti-titolo") { TitoloSezione(stringResource(R.string.studio_svolti_titolo)) }
        if (svolte.isEmpty()) {
            item(key = "svolti-vuoto") { StatoVuoto(stringResource(R.string.studio_svolti_vuoto)) }
        }
        items(svolte, key = { "svolto-${it.id}" }) { studio ->
            CardStudioSvolto(studio = studio, io = io, nomeFiglio = nomeFiglio, adesso = adesso, titoloLavoro = titoloLavoro)
        }
        if (stato.altre && stato.svolte != null) {
            item(key = "altri") {
                if (stato.caricoAltre) {
                    Caricamento(testo = stringResource(R.string.studio_altri_caricamento), centrato = false)
                } else {
                    OutlinedButton(onClick = onAltri, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.studio_altri))
                    }
                }
            }
        }

        // --- Le versioni approvate --------------------------------------------------------------
        val versioni = stato.versioni.orEmpty()
        if (versioni.isNotEmpty()) {
            item(key = "versioni") {
                SezioneEspandibile(
                    titolo = stringResource(R.string.studio_versioni_titolo),
                    conteggio = versioni.size,
                    chiave = "studio-versioni",
                ) {
                    versioni.forEach { versione -> RigaVersioneStudio(versione, io) }
                }
            }
        }
    }
}

/** Una versione approvata: chi e quando, da quando valgono gli orari, e com'era. */
@Composable
private fun RigaVersioneStudio(versione: eu.stgm.pactum.genitore.dati.ContenutoStudio, io: RiferimentoGenitore?) {
    val p = parole()
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spazi.s)) {
        Text(text = testoVersioneStudio(p, versione, io), style = MaterialTheme.typography.labelLarge)
        righeConfigStudio(p, versione).forEach {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spazi.xs),
            )
        }
    }
}

/**
 * (0.19) L'anello dello Studio in corso: si riempie verso il minimo di attività,
 * un colore per tipo (compiti blu, lavori di casa verde, il resto arancio), coi
 * minuti nel mezzo. I minuti sono quelli del server; le fette i secondi contati
 * dei tratti.
 */
@Composable
private fun AnelloStudioInCorso(studio: StudioSvolto) {
    val minuti = studio.minutiAttivita ?: 0
    val minimi = (studio.minutiMinimi ?: 60).coerceAtLeast(1)
    val perTipo = studio.tratti.groupBy { coloreTratto(it.tipo) }
        .map { (colore, tratti) -> FettaAttivita(colore, tratti.sumOf { it.secondiContati ?: it.secondi ?: 0L }) }
    val descrizione = stringResource(R.string.studio_anello_descrizione, minuti, minimi)
    AnelloAttivita(
        fette = perTipo,
        progresso = minuti.toFloat() / minimi,
        modifier = Modifier
            .padding(top = Spazi.m)
            .clearAndSetSemantics { contentDescription = descrizione },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = rememberContatore(minuti).toString(), style = MaterialTheme.typography.headlineMedium)
            Text(text = stringResource(R.string.studio_anello_di, minimi), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** (0.19) Il colore di un tipo di tratto nell'anello. */
private fun coloreTratto(tipo: String) = when (tipo) {
    TipiTratto.COMPITI -> ColoriAttivita.Compiti
    TipiTratto.LAVORI_DI_CASA -> ColoriAttivita.LavoriDiCasa
    else -> ColoriAttivita.Altro
}
