package eu.stgm.pactum.genitore.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.design.Caricamento
import eu.stgm.pactum.design.Pillola
import eu.stgm.pactum.design.RigaStato
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.design.StatoVuoto
import eu.stgm.pactum.design.StrisciaGiorni
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.RegolaFinestra
import androidx.compose.ui.graphics.Color
import eu.stgm.pactum.design.SchermataColorata
import eu.stgm.pactum.design.Sezione

// (0.16) Tutte le regole del figlio scelto, come l'elenco della Panoramica della
// 0.14: per dispositivo (o gli Impegni), ciascuna con la sua striscia piccola,
// anche quelle non più attive e quelle dei dispositivi scollegati; un dispositivo
// senza regole lo dice. Ogni regola apre il suo dettaglio.

@Composable
fun TutteLeRegoleScreen(
    finestraVm: FinestraViewModel = viewModel(),
    famigliaVm: FamigliaViewModel = viewModel(),
) {
    val statoFinestra by finestraVm.stato.collectAsStateWithLifecycle()
    val famiglia by famigliaVm.stato.collectAsStateWithLifecycle()
    val figlioId = famiglia.figlioId

    LifecycleResumeEffect(figlioId, famiglia.pronta) {
        if (famiglia.pronta) finestraVm.aggiorna(figlioId)
        onPauseOrDispose { }
    }

    SchermataColorata(Sezione.REGOLE) {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = { BarraPagina(stringResource(R.string.tutte_le_regole)) },
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
                        Caricamento(testo = stringResource(R.string.tutte_le_regole_caricamento))
                    finestra == null -> StatoVuoto(stringResource(R.string.finestra_errore_nessun_dato), centrato = true)
                    else -> ElencoRegole(finestra = finestra, errore = statoFinestra.errore)
                }
            }
        }
    }
}

@Composable
private fun ElencoRegole(finestra: Finestra, errore: Boolean) {
    val cornice = LocalCornice.current
    val perDispositivo = finestraPerDispositivo(finestra)
    val dispositivi = remember(finestra) { dispositiviDellaFinestra(finestra) }
    // Server 0.7 (un telefono solo, senza dispositivi): un elenco solo.
    val gruppi = remember(finestra) {
        if (perDispositivo) {
            raggruppaRegole(finestra.regole, dispositivi)
        } else {
            listOf(GruppoRegole(GenereGruppo.ALTRE, null, finestra.regole.sortedBy { it.id }))
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spazi.l),
        verticalArrangement = Arrangement.spacedBy(Spazi.s),
    ) {
        if (errore) item(key = "dati-vecchi") { RigaStato(stringResource(R.string.turno_dati_vecchi)) }

        if (finestra.regole.isEmpty() && gruppi.none { it.genere == GenereGruppo.DISPOSITIVO }) {
            item(key = "vuoto") {
                StatoVuoto(
                    titolo = stringResource(R.string.regole_vuoto_titolo),
                    testo = stringResource(R.string.regole_vuoto),
                )
            }
            return@LazyColumn
        }

        gruppi.forEach { gruppo ->
            val chiave = when (gruppo.genere) {
                GenereGruppo.DISPOSITIVO -> "gruppo-${gruppo.dispositivo?.id}"
                GenereGruppo.IMPEGNI -> "gruppo-impegni"
                GenereGruppo.ALTRE -> "gruppo-altre"
            }
            if (perDispositivo) item(key = chiave) { IntestazioneGruppo(gruppo) }
            if (gruppo.regole.isEmpty()) {
                item(key = "$chiave-vuoto") { StatoVuoto(stringResource(R.string.regole_nessuna_sul_dispositivo)) }
            }
            items(gruppo.regole, key = { "regola-${it.id}" }) { regola ->
                RigaRegola(regola, onClick = { cornice.apri(Pagina.Regola(regola.id)) })
            }
        }
    }
}

/** Una regola: la frase, "non più attiva" se lo è, la sua striscia piccola degli 8 giorni. */
@Composable
private fun RigaRegola(regola: RegolaFinestra, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(vertical = Spazi.xs),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = descrizioneRegola(regola), style = MaterialTheme.typography.bodyLarge)
            if (!regola.attiva) {
                Pillola(stringResource(R.string.regola_eliminata), modifier = Modifier.padding(top = Spazi.xs))
            }
            val giorni = giorniDaQuadretti(regola.semaforo)
            if (giorni.isNotEmpty()) {
                StrisciaGiorni(
                    giorni = giorni,
                    lato = 20.dp,
                    mostraNumero = false,
                    descrizione = descrizioneStriscia(giorni, R.plurals.striscia_descrizione_regola),
                    modifier = Modifier.padding(top = Spazi.s),
                )
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}
