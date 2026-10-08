package eu.stgm.pactum.genitore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.design.Caricamento
import eu.stgm.pactum.design.RigaStato
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.design.StatoVuoto
import eu.stgm.pactum.design.TitoloSezione
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.RiferimentoGenitore
import java.time.Instant
import androidx.compose.ui.graphics.Color
import eu.stgm.pactum.design.SchermataColorata
import eu.stgm.pactum.design.Sezione

// (0.16) Le sessioni del figlio scelto, in una pagina loro (nella 0.15 stavano
// solo nello Storico): quelle in corso adesso, quelle approvate (con le app e chi
// ha deciso), quelle fatte negli ultimi 8 giorni e quelle non più valide (di un
// telefono scollegato). Si apre dalla riga "Sessioni" della Panoramica e dalla
// riga in cima "In sessione …".

@Composable
fun SessioniScreen(
    finestraVm: FinestraViewModel = viewModel(),
    famigliaVm: FamigliaViewModel = viewModel(),
) {
    val statoFinestra by finestraVm.stato.collectAsStateWithLifecycle()
    val famiglia by famigliaVm.stato.collectAsStateWithLifecycle()
    val figlioId = famiglia.figlioId

    // La stessa lettura della Panoramica (la finestra), a ogni ritorno in primo piano.
    LifecycleResumeEffect(figlioId, famiglia.pronta) {
        if (famiglia.pronta) finestraVm.aggiorna(figlioId)
        onPauseOrDispose { }
    }

    SchermataColorata(Sezione.SESSIONI) {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = { BarraPagina(stringResource(R.string.sezione_sessioni)) },
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
                        Caricamento(testo = stringResource(R.string.sessioni_caricamento))
                    finestra == null -> StatoVuoto(stringResource(R.string.finestra_errore_nessun_dato), centrato = true)
                    else -> ContenutoSessioni(finestra = finestra, io = famiglia.io, errore = statoFinestra.errore)
                }
            }
        }
    }
}

@Composable
private fun ContenutoSessioni(finestra: Finestra, io: RiferimentoGenitore?, errore: Boolean) {
    val dispositivi = remember(finestra) { dispositiviDellaFinestra(finestra) }
    val scollegati = remember(finestra) { dispositiviScollegati(finestra) }
    val nomi = remember(finestra) { nomiDelleApp(finestra) }
    // Le fatte raccontate rispetto ad adesso: una "in corso" letta prima della
    // fine prevista è finita.
    // (0.16) Quelle dei dispositivi scollegati non sono "in corso" (v. sessioniInCorsoEFatte).
    val (inCorso, fatte) = remember(finestra) { sessioniInCorsoEFatte(finestra, Instant.now()) }
    val approvate = remember(finestra) { sessioniApprovate(finestra.sessioni, scollegati) }
    val nonPiuValide = remember(finestra) { sessioniNonPiuValide(finestra.sessioni, scollegati) }
    val conPiuTelefoni = remember(finestra) { piuTelefoni(finestra) }
    val telefonoDi: (Long?) -> String? = { id -> if (conPiuTelefoni) nomeDispositivo(id, dispositivi) else null }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spazi.l),
        verticalArrangement = Arrangement.spacedBy(Spazi.m),
    ) {
        if (errore) item(key = "dati-vecchi") { RigaStato(stringResource(R.string.turno_dati_vecchi)) }

        if (inCorso.isEmpty() && fatte.isEmpty() && approvate.isEmpty() && nonPiuValide.isEmpty()) {
            item(key = "nessuna") { StatoVuoto(stringResource(R.string.sessioni_nessuna), emoji = "🎯") }
            return@LazyColumn
        }

        if (inCorso.isNotEmpty()) {
            item(key = "in-corso-titolo") { TitoloSezione(stringResource(R.string.sessioni_in_corso_titolo)) }
            items(inCorso, key = { "in-corso-${it.svolta.id}" }) { RigaSessioneSvolta(it, telefonoDi(it.svolta.dispositivoId)) }
        }

        item(key = "approvate-titolo") { TitoloSezione(stringResource(R.string.sessioni_approvate_titolo)) }
        if (approvate.isEmpty()) {
            item(key = "approvate-vuoto") { StatoVuoto(stringResource(R.string.sessioni_nessuna_approvata), emoji = "🎯") }
        } else {
            items(approvate, key = { "approvata-${it.id}" }) { sessione ->
                RigaSessioneApprovata(
                    sessione = sessione,
                    nomiFinestra = nomi,
                    telefono = telefonoDi(sessione.dispositivoId ?: sessione.dispositivo?.id),
                    io = io,
                )
            }
        }

        item(key = "fatte-titolo") { TitoloSezione(stringResource(R.string.sessioni_fatte_titolo)) }
        if (fatte.isEmpty()) {
            item(key = "fatte-vuoto") { StatoVuoto(stringResource(R.string.sessioni_nessuna_svolta), emoji = "🎯") }
        } else {
            items(fatte, key = { "fatta-${it.svolta.id}" }) { RigaSessioneSvolta(it, telefonoDi(it.svolta.dispositivoId)) }
        }

        if (nonPiuValide.isNotEmpty()) {
            item(key = "non-valide-titolo") { TitoloSezione(stringResource(R.string.sessioni_non_piu_valide_titolo)) }
            items(nonPiuValide, key = { "non-valida-${it.id}" }) { sessione ->
                RigaSessioneNonPiuValida(sessione, telefonoDi(sessione.dispositivoId ?: sessione.dispositivo?.id))
            }
        }

        // Che cos'è una sessione, una volta, in fondo.
        item(key = "spiega") {
            Text(
                text = stringResource(R.string.sessione_non_conta),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
