package eu.stgm.pactum.genitore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import eu.stgm.pactum.genitore.dati.BonusGiorno
import eu.stgm.pactum.genitore.dati.EventoFinestra
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.ModificaStorico
import eu.stgm.pactum.genitore.dati.QuadrettoSemaforo
import eu.stgm.pactum.genitore.dati.RegolaFinestra
import eu.stgm.pactum.genitore.dati.StatiSemaforo
import eu.stgm.pactum.genitore.dati.StatoBonus
import eu.stgm.pactum.genitore.dati.StatoSilenzio
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

// Colori del semaforo e del banner: fissi di proposito, uguali in chiaro e scuro
// (il verde È verde e il rosso È rosso, come un semaforo vero).
private val VerdeSemaforo = Color(0xFF43A047)
private val RossoSemaforo = Color(0xFFE53935)
private val GrigioSemaforo = Color(0xFF9E9E9E)
private val VerdeCalmo = Color(0xFF1F6E5C)
private val RossoAllarme = Color(0xFFB3261E)

/** Ogni quanto si rilegge la finestra mentre la schermata è in primo piano. */
private const val INTERVALLO_RILETTURA_MS = 60_000L

/** La finestra del genitore: tutto ciò che riguarda il patto, niente altro. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinestraScreen(vm: FinestraViewModel = viewModel()) {
    val stato by vm.stato.collectAsStateWithLifecycle()

    // Prima lettura a ogni ritorno in primo piano, poi rilettura periodica
    // finché la schermata resta visibile: il banner verde "in contatto" non
    // può restare fermo per ore su un telefono lasciato acceso.
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
                title = { Text(stringResource(R.string.finestra_titolo)) },
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
                            text = stringResource(R.string.finestra_caricamento),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }

                stato.configurazioneMancante -> Centro {
                    TestoCentrato(stringResource(R.string.finestra_config_mancante))
                }

                finestra == null -> Centro {
                    TestoCentrato(stringResource(R.string.finestra_errore))
                }

                else -> ContenutoFinestra(
                    finestra = finestra,
                    mostraErrore = stato.errore,
                    ricevutaAlle = stato.ricevutaAlle,
                )
            }
        }
    }
}

@Composable
private fun ContenutoFinestra(
    finestra: Finestra,
    mostraErrore: Boolean,
    ricevutaAlle: Instant?,
) {
    // Per raccontare storico e sforamenti serve il tipo della regola:
    // la finestra porta TUTTE le regole (anche eliminate), quindi la mappa è completa.
    val regolePerId = finestra.regole.associateBy { it.id }

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
                        text = stringResource(R.string.finestra_errore),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        item {
            Column {
                BannerSilenzio(finestra.statoSilenzio)
                // L'età del dato accanto al banner: un "In contatto" senza data
                // di raccolta sembrerebbe il presente anche quando non lo è.
                if (ricevutaAlle != null) {
                    Text(
                        text = stringResource(
                            R.string.finestra_aggiornata_alle,
                            oraOppureDataOra(ricevutaAlle),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        item { TitoloSezione(stringResource(R.string.sezione_regole)) }
        if (finestra.regole.isEmpty()) {
            item { TestoVuoto(stringResource(R.string.regole_vuoto)) }
        } else {
            items(finestra.regole, key = { "regola-${it.id}" }) { SchedaRegola(it) }
        }

        item { TitoloSezione(stringResource(R.string.sezione_bonus)) }
        item { SchedaBonus(finestra.bonus, finestra.bonusGiornalieri) }

        item { TitoloSezione(stringResource(R.string.sezione_sforamenti)) }
        if (finestra.sforamentiRecenti.isEmpty()) {
            item { TestoVuoto(stringResource(R.string.sforamenti_vuoto)) }
        } else {
            items(finestra.sforamentiRecenti, key = { "sforamento-${it.id}" }) {
                RigaSforamento(it, regolePerId)
            }
        }

        item { TitoloSezione(stringResource(R.string.sezione_manomissioni)) }
        if (finestra.manomissioniRecenti.isEmpty()) {
            item { TestoVuoto(stringResource(R.string.manomissioni_vuoto)) }
        } else {
            items(finestra.manomissioniRecenti, key = { "manomissione-${it.id}" }) {
                RigaManomissione(it)
            }
        }

        item { TitoloSezione(stringResource(R.string.sezione_storico)) }
        if (finestra.storicoModifiche.isEmpty()) {
            item { TestoVuoto(stringResource(R.string.storico_vuoto)) }
        } else {
            items(finestra.storicoModifiche, key = { "storico-${it.id}" }) {
                RigaStorico(it, regolePerId)
            }
        }
    }
}

/** Lo stato del canale col figlio, deciso dal flag `silente` del SERVER. */
@Composable
private fun BannerSilenzio(statoSilenzio: StatoSilenzio) {
    val istante = istanteServer(statoSilenzio.ultimoBattito)
    val (colore, testo) = if (statoSilenzio.silente) {
        RossoAllarme to if (istante != null) {
            stringResource(R.string.silenzio_allarme, oraOppureDataOra(istante))
        } else {
            stringResource(R.string.silenzio_mai)
        }
    } else {
        VerdeCalmo to stringResource(
            R.string.silenzio_in_contatto,
            istante?.let { oraOppureDataOra(it) } ?: "—",
        )
    }
    Card(colors = CardDefaults.cardColors(containerColor = colore)) {
        Text(
            text = testo,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        )
    }
}

@Composable
private fun SchedaRegola(regola: RegolaFinestra) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = descrizioneRegola(regola.tipo, regola.parametri),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                if (!regola.attiva) {
                    Etichetta(stringResource(R.string.regola_eliminata))
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Semaforo(regola.semaforo)
        }
    }
}

/** Otto quadretti, dal più vecchio a oggi; oggi ha il bordo evidenziato. */
@Composable
private fun Semaforo(semaforo: List<QuadrettoSemaforo>) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        semaforo.forEachIndexed { indice, quadretto ->
            val oggi = indice == semaforo.lastIndex
            val colore = when (quadretto.stato) {
                StatiSemaforo.VERDE -> VerdeSemaforo
                StatiSemaforo.ROSSO -> RossoSemaforo
                else -> GrigioSemaforo
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(colore, RoundedCornerShape(6.dp))
                        .then(
                            if (oggi) {
                                Modifier.border(
                                    2.dp,
                                    MaterialTheme.colorScheme.onSurface,
                                    RoundedCornerShape(6.dp),
                                )
                            } else {
                                Modifier
                            },
                        ),
                )
                Text(
                    // Il giorno del mese sotto ogni quadretto ("2026-07-14" → "14").
                    text = quadretto.data.takeLast(2),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (oggi) {
                    Text(
                        text = stringResource(R.string.semaforo_oggi),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SchedaBonus(bonus: StatoBonus, bonusGiornalieri: List<BonusGiorno>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(
                    R.string.bonus_residui_giorno,
                    bonus.giorno.residui,
                    bonus.giorno.tetto,
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(
                    R.string.bonus_residui_settimana,
                    bonus.settimana.residui,
                    bonus.settimana.tetto,
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.bonus_striscia_titolo),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                bonusGiornalieri.forEachIndexed { indice, giorno ->
                    val oggi = indice == bonusGiornalieri.lastIndex
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(width = 34.dp, height = 26.dp)
                                .background(
                                    if (giorno.minuti > 0) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    RoundedCornerShape(6.dp),
                                )
                                .then(
                                    if (oggi) {
                                        Modifier.border(
                                            2.dp,
                                            MaterialTheme.colorScheme.onSurface,
                                            RoundedCornerShape(6.dp),
                                        )
                                    } else {
                                        Modifier
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = giorno.minuti.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (giorno.minuti > 0) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        Text(
                            text = giorno.giorno.takeLast(2),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RigaSforamento(evento: EventoFinestra, regolePerId: Map<Long, RegolaFinestra>) {
    val regola = campoLong(evento.dettagli, "regola_id")?.let { regolePerId[it] }
    val descrizione = if (regola != null) {
        stringResource(
            R.string.sforamento_su_regola,
            descrizioneRegola(regola.tipo, regola.parametri),
        )
    } else {
        stringResource(R.string.sforamento_generico)
    }
    RigaEvento(titolo = descrizione, tsServer = evento.tsServer)
}

@Composable
private fun RigaManomissione(evento: EventoFinestra) {
    val sottoTipo = campoTesto(evento.dettagli, "sotto_tipo")
    val titolo = when (sottoTipo) {
        "cambio_ora" -> stringResource(R.string.manomissione_cambio_ora)
        "cambio_fuso" -> stringResource(R.string.manomissione_cambio_fuso)
        "silenzio" -> stringResource(R.string.manomissione_silenzio)
        else -> stringResource(R.string.manomissione_generica, sottoTipo ?: "?")
    }
    RigaEvento(titolo = titolo, tsServer = evento.tsServer)
}

@Composable
private fun RigaStorico(modifica: ModificaStorico, regolePerId: Map<Long, RegolaFinestra>) {
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
    // eliminata), non con quelli vigenti: lo storico racconta il passato.
    val parametri = modifica.dopo ?: modifica.prima
    val tipo = regolePerId[modifica.regolaId]?.tipo
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = titolo,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                if (modifica.concordata) {
                    Etichetta(stringResource(R.string.storico_concordata))
                }
            }
            if (tipo != null && parametri != null) {
                Text(
                    text = descrizioneRegola(tipo, parametri),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TestoOrario(modifica.tsServer)
        }
    }
}

@Composable
private fun RigaEvento(titolo: String, tsServer: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(text = titolo, style = MaterialTheme.typography.bodyLarge)
            TestoOrario(tsServer)
        }
    }
}

@Composable
private fun TestoOrario(tsServer: String) {
    val istante = istanteServer(tsServer) ?: return
    Text(
        text = dataOraLocale(istante),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TitoloSezione(testo: String) {
    Text(
        text = testo,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun TestoVuoto(testo: String) {
    Text(
        text = testo,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Etichetta(testo: String) {
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
private fun Centro(contenuto: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        contenuto()
    }
}

@Composable
private fun TestoCentrato(testo: String) {
    Text(
        text = testo,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 32.dp),
    )
}

private fun campoLong(oggetto: JsonObject, nome: String): Long? =
    (oggetto[nome] as? JsonPrimitive)?.content?.toLongOrNull()

private fun campoTesto(oggetto: JsonObject, nome: String): String? =
    (oggetto[nome] as? JsonPrimitive)?.content
