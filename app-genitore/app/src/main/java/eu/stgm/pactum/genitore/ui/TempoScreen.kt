package eu.stgm.pactum.genitore.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.UsoApp
import eu.stgm.pactum.genitore.dati.UsoCategoria
import eu.stgm.pactum.genitore.dati.UsoGiorno
import eu.stgm.pactum.genitore.ui.theme.Spazi
import eu.stgm.pactum.genitore.ui.theme.coloreCategoria
import kotlinx.coroutines.delay
import java.time.Instant

// Niente rosso qui dentro: il colore del patto vive SOLO nella striscia degli 8
// giorni della finestra. La colpa, se c'è, è la differenza da una promessa che
// il figlio si è dato — non il totale dei minuti, che resta `onSurface`.

/** Ogni quanto si rileggono i tempi mentre la schermata è in primo piano. */
private const val INTERVALLO_RILETTURA_MS = 60_000L

/**
 * La sezione Tempo (contratto v2.2, `uso_recente`): i tempi d'uso giornalieri
 * di TUTTE le app del figlio — 8 giorni, totale in evidenza, il limite accanto
 * dove una regola esiste. Un giorno senza fotografia dice "nessun dato
 * ricevuto", MAI uno zero finto: l'assenza di notizie è un'informazione.
 *
 * Condivide il [FinestraViewModel] della finestra (stesso scope dell'attività):
 * una lettura sola di GET /api/finestra serve entrambe le schede.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TempoScreen(vm: FinestraViewModel = viewModel()) {
    val stato by vm.stato.collectAsStateWithLifecycle()

    // Come la finestra: prima lettura a ogni ritorno in primo piano, poi
    // rilettura periodica finché la schermata resta visibile.
    val cicloVita = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(cicloVita) {
        cicloVita.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.aggiorna()
                delay(INTERVALLO_RILETTURA_MS)
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tempo_titolo)) },
                actions = {
                    IconButton(onClick = { vm.aggiorna() }) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.azione_aggiorna))
                    }
                },
            )
        },
    ) { padding ->
        val finestra = stato.finestra
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                stato.caricamento && finestra == null -> Centro {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.tempo_caricamento),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = Spazi.s),
                        )
                    }
                }

                stato.configurazioneMancante -> Centro {
                    TestoCentratoTempo(stringResource(R.string.tempo_config_mancante))
                }

                finestra == null -> Centro {
                    TestoCentratoTempo(stringResource(R.string.tempo_errore))
                }

                else -> ContenutoTempo(
                    usoRecente = finestra.usoRecente,
                    mostraErrore = stato.errore,
                    ricevutaAlle = stato.ricevutaAlle,
                )
            }
        }
    }
}

@Composable
private fun ContenutoTempo(
    usoRecente: List<UsoGiorno>,
    mostraErrore: Boolean,
    ricevutaAlle: Instant?,
) {
    // Il giorno scelto dal selettore; null = oggi (l'ultima voce: il contratto
    // ordina dal più vecchio a oggi). Se la voce scelta sparisce al cambio di
    // giornata, si ricade su oggi invece di restare su un giorno fantasma.
    var giornoScelto by rememberSaveable { mutableStateOf<String?>(null) }
    val selezionato = usoRecente.firstOrNull { it.giorno == giornoScelto }
        ?: usoRecente.lastOrNull()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spazi.l),
        verticalArrangement = Arrangement.spacedBy(Spazi.m),
    ) {
        if (mostraErrore) {
            item { RigaDatiVecchi(stringResource(R.string.tempo_dati_vecchi)) }
        }

        if (ricevutaAlle != null) {
            item {
                Text(
                    text = stringResource(
                        R.string.tempo_aggiornato_alle,
                        oraOppureDataOra(ricevutaAlle),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (selezionato == null) {
            // Il server non ha mandato `uso_recente` (o è vuoto): niente da
            // inventare — si dice che non ci sono fotografie, punto.
            item { TestoVuotoTempo(stringResource(R.string.tempo_nessuna_fotografia)) }
            return@LazyColumn
        }

        item {
            SelettoreGiorni(
                giorni = usoRecente,
                selezionato = selezionato,
                onScelta = { giornoScelto = it },
            )
        }

        // L'anello del giorno scelto: il totale al centro, le categorie intorno.
        item { SchedaGiorno(giorno = selezionato, oggi = selezionato == usoRecente.last()) }

        // Gli otto giorni: si guardano, e si toccano per cambiare giorno.
        item {
            SchedaOttoGiorni(
                giorni = usoRecente,
                selezionato = selezionato.giorno,
                onScelta = { giornoScelto = it },
            )
        }

        if (selezionato.totaleMinuti != null) {
            item { TitoloSezioneTempo(stringResource(R.string.tempo_sezione_app)) }
            val perMinuti = selezionato.app.sortedByDescending { it.minuti }
            if (perMinuti.isEmpty()) {
                item { TestoVuotoTempo(stringResource(R.string.tempo_app_vuoto)) }
            } else {
                // Senza un limite la barra si misura sull'app più usata del
                // giorno: è un confronto tra pari, non un giudizio.
                val riferimento = perMinuti.first().minuti
                items(perMinuti, key = { "app-${selezionato.giorno}-${it.chiave}" }) {
                    SchedaBarraApp(app = it, riferimento = riferimento)
                }
            }

            // Le categorie con un limite hanno una barra propria: la legenda
            // dell'anello dice quanto, la barra dice quanto MANCA.
            val conLimite = selezionato.categorie
                .filter { it.limite != null && it.minuti > 0 }
                .sortedByDescending { it.minuti }
            if (conLimite.isNotEmpty()) {
                item {
                    TitoloSezioneTempo(
                        stringResource(R.string.tempo_sezione_categorie_limite),
                    )
                }
                items(conLimite, key = { "cat-${selezionato.giorno}-${it.chiave}" }) {
                    SchedaBarraCategoria(it)
                }
            }
        }
    }
}

/** Otto chip, dal più vecchio a oggi (l'ultimo dice "oggi"). */
@Composable
private fun SelettoreGiorni(
    giorni: List<UsoGiorno>,
    selezionato: UsoGiorno,
    onScelta: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spazi.s),
    ) {
        giorni.forEachIndexed { indice, giorno ->
            val oggi = indice == giorni.lastIndex
            FilterChip(
                selected = giorno.giorno == selezionato.giorno,
                onClick = { onScelta(giorno.giorno) },
                label = {
                    Text(
                        if (oggi) {
                            stringResource(R.string.tempo_chip_oggi)
                        } else {
                            giornoBreve(giorno.giorno)
                        },
                    )
                },
            )
        }
    }
}

/**
 * Il giorno scelto come ANELLO: il totale al centro, le categorie tutt'intorno,
 * la legenda sotto. Un giorno senza fotografia non ha anello e lo dice a parole:
 * mai uno zero finto, mai una ciambella vuota che sembra "zero minuti".
 */
@Composable
private fun SchedaGiorno(giorno: UsoGiorno, oggi: Boolean) {
    // Con la fetta "resto" (non categorizzato) la legenda somma sempre al totale
    // al centro dell'anello: gli stessi conti, in Tempo come nella Panoramica.
    val fette = fetteConResto(giorno.categorie, giorno.totaleMinuti)
    Card(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spazi.l)) {
            val totale = giorno.totaleMinuti
            if (totale == null) {
                Text(
                    text = if (oggi) {
                        stringResource(R.string.tempo_nessun_dato_oggi)
                    } else {
                        stringResource(R.string.tempo_nessun_dato_giorno, giornoBreve(giorno.giorno))
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(R.string.tempo_nessun_dato_spiega),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spazi.xs),
                )
            } else {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AnelloCategorie(
                        fette = fette,
                        totaleMinuti = totale,
                        etichettaCentro = if (oggi) {
                            stringResource(R.string.tempo_chip_oggi)
                        } else {
                            giornoBreve(giorno.giorno)
                        },
                    )
                }
                Spacer(modifier = Modifier.height(Spazi.l))
                if (fette.isEmpty()) {
                    Text(
                        text = stringResource(R.string.grafico_categorie_vuoto),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LegendaCategorie(fette)
                }
                // Quando è arrivata la fotografia su cui poggia il totale: un
                // "oggi 3 h" delle 14:00 non racconta la serata.
                val fotografia = istanteServer(giorno.aggiornatoTs)
                if (fotografia != null) {
                    Text(
                        text = stringResource(
                            R.string.tempo_fotografia_delle,
                            oraOppureDataOra(fotografia),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spazi.m),
                    )
                }
            }
        }
    }
}

/** La striscia degli otto giorni: si guarda, e si tocca per cambiare giorno. */
@Composable
private fun SchedaOttoGiorni(
    giorni: List<UsoGiorno>,
    selezionato: String,
    onScelta: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spazi.l)) {
            Text(
                text = stringResource(R.string.grafico_ultimi_giorni),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(Spazi.m))
            BarreGiorni(
                giorni = giorni,
                selezionato = selezionato,
                onScelta = onScelta,
            )
        }
    }
}

@Composable
private fun SchedaBarraApp(app: UsoApp, riferimento: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        RigaBarraUso(
            nome = app.nome ?: app.chiave,
            minuti = app.minuti,
            limite = app.limite,
            riferimento = riferimento,
            colore = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = Spazi.l, vertical = Spazi.m),
        )
    }
}

@Composable
private fun SchedaBarraCategoria(categoria: UsoCategoria) {
    Card(modifier = Modifier.fillMaxWidth()) {
        RigaBarraUso(
            nome = etichettaCategoria(categoria.chiave),
            minuti = categoria.minuti,
            limite = categoria.limite,
            riferimento = maxOf(categoria.minuti, categoria.limite ?: 0),
            colore = coloreCategoria(categoria.chiave),
            modifier = Modifier.padding(horizontal = Spazi.l, vertical = Spazi.m),
        )
    }
}

@Composable
private fun TitoloSezioneTempo(testo: String) {
    Text(
        text = testo,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = Spazi.s),
    )
}

@Composable
private fun TestoVuotoTempo(testo: String) {
    Text(
        text = testo,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Centro(contenuto: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        contenuto()
    }
}

@Composable
private fun TestoCentratoTempo(testo: String) {
    Text(
        text = testo,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = Spazi.xxl),
    )
}
