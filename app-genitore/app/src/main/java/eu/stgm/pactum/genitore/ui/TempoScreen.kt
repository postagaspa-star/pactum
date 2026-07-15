package eu.stgm.pactum.genitore.ui

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.delay
import java.time.Instant

/** Rosso fisso (come il semaforo) per i minuti oltre il limite: uguale in chiaro e scuro. */
private val RossoOltreLimite = Color(0xFFE53935)

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
                            modifier = Modifier.padding(top = 8.dp),
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
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (mostraErrore) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.tempo_dati_vecchi),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
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

        item { TotaleGiorno(giorno = selezionato, oggi = selezionato == usoRecente.last()) }

        if (selezionato.totaleMinuti != null) {
            item { TitoloSezioneTempo(stringResource(R.string.tempo_sezione_app)) }
            val perMinuti = selezionato.app.sortedByDescending { it.minuti }
            if (perMinuti.isEmpty()) {
                item { TestoVuotoTempo(stringResource(R.string.tempo_app_vuoto)) }
            } else {
                items(perMinuti, key = { "app-${selezionato.giorno}-${it.chiave}" }) {
                    RigaUsoApp(it)
                }
            }

            if (selezionato.categorie.isNotEmpty()) {
                item { TitoloSezioneTempo(stringResource(R.string.tempo_sezione_categorie)) }
                items(
                    selezionato.categorie.sortedByDescending { it.minuti },
                    key = { "cat-${selezionato.giorno}-${it.chiave}" },
                ) {
                    RigaUsoCategoria(it)
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
        horizontalArrangement = Arrangement.spacedBy(6.dp),
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

/** Il totale del giorno in evidenza; un giorno senza fotografia lo dice, mai zero. */
@Composable
private fun TotaleGiorno(giorno: UsoGiorno, oggi: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
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
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                Text(
                    text = if (oggi) {
                        stringResource(R.string.tempo_totale_oggi, testoDurata(totale.toLong()))
                    } else {
                        stringResource(
                            R.string.tempo_totale_giorno,
                            giornoBreve(giorno.giorno),
                            testoDurata(totale.toLong()),
                        )
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
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
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RigaUsoApp(app: UsoApp) {
    RigaUso(
        nome = app.nome ?: app.chiave,
        minuti = app.minuti,
        limite = app.limite,
    )
}

@Composable
private fun RigaUsoCategoria(categoria: UsoCategoria) {
    RigaUso(
        nome = etichettaCategoria(categoria.chiave),
        minuti = categoria.minuti,
        limite = categoria.limite,
    )
}

/** Una riga d'uso: nome, badge "limite" dove una regola esiste, minuti a destra. */
@Composable
private fun RigaUso(nome: String, minuti: Int, limite: Int?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = nome,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (limite != null) {
                EtichettaLimite(
                    stringResource(R.string.tempo_limite, testoDurata(limite.toLong())),
                )
            }
            Text(
                text = testoDurata(minuti.toLong()),
                style = MaterialTheme.typography.bodyLarge,
                // Oltre il limite: rosso, come il quadretto del semaforo.
                color = if (limite != null && minuti > limite) {
                    RossoOltreLimite
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun EtichettaLimite(testo: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = testo,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun TitoloSezioneTempo(testo: String) {
    Text(
        text = testo,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp),
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
        modifier = Modifier.padding(horizontal = 32.dp),
    )
}
