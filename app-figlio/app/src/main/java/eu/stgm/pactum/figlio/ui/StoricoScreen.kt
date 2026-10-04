package eu.stgm.pactum.figlio.ui

import eu.stgm.pactum.design.StatoVuoto
import eu.stgm.pactum.design.Caricamento
import eu.stgm.pactum.design.CardEvidenza
import eu.stgm.pactum.design.Tono
import eu.stgm.pactum.design.TitoloSezione
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.dati.Dichiarazione
import eu.stgm.pactum.figlio.dati.ProposteDelFiglio
import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.StatiDichiarazione
import java.time.LocalDate

/**
 * (0.15) Lo Storico, aperto da Regole: le proposte chiuse (di tutti e due) e
 * tutte le dichiarazioni sulle regole di vita reale, ognuna una volta sola
 * (quelle in attesa in evidenza, poi le altre). Sono le informazioni delle
 * vecchie sezioni "Storia" (Proposte) e "Le tue dichiarazioni" (Diario).
 * [sulleDichiarazioni] = arrivo dalla notifica dell'esito di una
 * dichiarazione: la pagina si apre sulle dichiarazioni.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoricoScreen(
    onChiudi: () -> Unit,
    sulleDichiarazioni: Boolean = false,
    proposteVm: ProposteViewModel = viewModel(),
    dichiarazioniVm: DichiarazioniViewModel = viewModel(),
) {
    val statoProposte by proposteVm.stato.collectAsStateWithLifecycle()
    val statoDiario by dichiarazioniVm.stato.collectAsStateWithLifecycle()
    val lista = rememberLazyListState()

    LifecycleResumeEffect(Unit) {
        proposteVm.aggiorna()
        dichiarazioniVm.aggiorna()
        onPauseOrDispose { }
    }

    val storia = ProposteDelFiglio.storia(statoProposte.proposte)
    val dichiarazioni = statoDiario.tutte
    val inAttesa = dichiarazioni.filter { it.stato == StatiDichiarazione.IN_ATTESA }
    val risolte = dichiarazioni.filter { it.stato != StatiDichiarazione.IN_ATTESA }
    val oggi = oggiDelPatto(statoDiario.fuso)
    val regole = statoDiario.regoleVitaReale

    // Dalla notifica di un esito: una volta, sulle dichiarazioni.
    var portataSulleDichiarazioni by rememberSaveable { mutableStateOf(false) }
    // L'indice del titolo "Dichiarazioni": 1 (titolo Proposte) + le card (o il vuoto).
    val indiceDichiarazioni = 1 + maxOf(storia.size, 1)
    LaunchedEffect(sulleDichiarazioni, statoProposte.caricamento) {
        if (sulleDichiarazioni && !portataSulleDichiarazioni && !statoProposte.caricamento) {
            portataSulleDichiarazioni = true
            lista.scrollToItem(indiceDichiarazioni)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.storico_titolo)) },
                navigationIcon = {
                    IconButton(onClick = onChiudi) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.azione_indietro))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            state = lista,
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(Spazi.l + Spazi.xs),
            verticalArrangement = Arrangement.spacedBy(Spazi.l),
        ) {
            item(key = "titolo-proposte") { TitoloSezione(stringResource(R.string.storico_sezione_proposte)) }
            if (storia.isEmpty()) {
                item(key = "proposte-vuoto") {
                    if (statoProposte.caricamento) {
                        Caricamento(centrato = false)
                    } else {
                        StatoVuoto(stringResource(R.string.proposte_storia_vuota))
                    }
                }
            } else {
                items(storia, key = { "storia-${it.id}" }) { proposta ->
                    CardPropostaStorica(
                        proposta,
                        statoProposte.regole.firstOrNull { it.id == proposta.regolaId },
                        statoProposte.contesto,
                    )
                }
            }

            item(key = "titolo-dichiarazioni") { TitoloSezione(stringResource(R.string.storico_sezione_dichiarazioni)) }
            if (dichiarazioni.isEmpty()) {
                item(key = "dichiarazioni-vuoto") {
                    if (statoDiario.caricamento) {
                        Caricamento(centrato = false)
                    } else {
                        StatoVuoto(stringResource(R.string.diario_dichiarazioni_vuoto))
                    }
                }
            } else {
                // In attesa della conferma: già riconosciute, in evidenza.
                items(inAttesa, key = { "attesa-${it.id}" }) { dichiarazione ->
                    CardFatto(regole.firstOrNull { it.id == dichiarazione.regolaId }, dichiarazione, oggi)
                }
                // Le altre: una riga ciascuna, con la linea sottile. Nessun contatore.
                items(risolte, key = { "risolta-${it.id}" }) { dichiarazione ->
                    Column {
                        RigaDichiarazione(dichiarazione, regole.firstOrNull { it.id == dichiarazione.regolaId }, oggi)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

/**
 * Il successo riconosciuto (B7): spunta, card in evidenza e "L'hai fatto.
 * Manca la conferma dell'arbitro (la mamma)". La conferma vale verso il
 * genitore; il riconoscimento verso il figlio arriva adesso.
 */
@Composable
private fun CardFatto(regola: Regola?, dichiarazione: Dichiarazione, oggi: LocalDate) {
    CardEvidenza(tono = Tono.Positivo) {
        Column(verticalArrangement = Arrangement.spacedBy(Spazi.s)) {
            regola?.let {
                Text(text = descrizioneRegola(it.tipo, it.parametri), style = MaterialTheme.typography.titleSmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spazi.s)) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Text(text = descrizioneStato(dichiarazione, regola), style = MaterialTheme.typography.bodyLarge)
            }
            if (dichiarazione.giorno.isNotBlank()) {
                Text(
                    text = stringResource(R.string.dichiarazione_giorno, giornoBreve(dichiarazione.giorno, oggi)),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            dichiarazione.nota?.takeIf { it.isNotBlank() }?.let {
                Text(text = stringResource(R.string.dichiarazione_tua_nota, it), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Una dichiarazione risolta: una riga, col fatto congelato dal server. */
@Composable
private fun RigaDichiarazione(dichiarazione: Dichiarazione, regola: Regola?, oggi: LocalDate) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spazi.m),
        verticalArrangement = Arrangement.spacedBy(Spazi.xs),
    ) {
        regola?.let {
            Text(text = descrizioneRegola(it.tipo, it.parametri), style = MaterialTheme.typography.titleSmall)
        }
        Text(text = descrizioneStato(dichiarazione, regola), style = MaterialTheme.typography.bodyLarge)
        if (dichiarazione.giorno.isNotBlank()) {
            Text(
                text = stringResource(R.string.dichiarazione_giorno, giornoBreve(dichiarazione.giorno, oggi)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        dichiarazione.nota?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = stringResource(R.string.dichiarazione_tua_nota, it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        dichiarazione.verdetto?.nota?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = stringResource(R.string.dichiarazione_verdetto_nota, it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
