package eu.stgm.pactum.genitore.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.design.BarraUso
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.Medie
import eu.stgm.pactum.genitore.dati.SitiGiorno
import eu.stgm.pactum.genitore.dati.SitoVisitato
import eu.stgm.pactum.genitore.dati.UsoGiorno
import kotlinx.coroutines.delay
import java.time.Instant

// Tempo risponde a una domanda sola: quanto ha usato il telefono. L'eroe è il
// totale del giorno; sotto, i grafici; poi l'elenco in due blocchi — prima le
// promesse (dentro il patto), poi il contesto (il resto della giornata).
// Niente terracotta qui dentro: il colore del patto vive SOLO nella striscia
// degli 8 giorni. Andare oltre un limite si dice a parole ("20 min oltre"), e i
// minuti restano `onSurface`: la colpa, se c'è, è la differenza da una promessa
// che il figlio si è dato, non il totale.

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
                stato.caricamento && finestra == null ->
                    Caricamento(stringResource(R.string.tempo_caricamento))

                stato.configurazioneMancante -> Centro {
                    StatoPrimaApertura(
                        titolo = stringResource(R.string.config_mancante_titolo),
                        testo = stringResource(R.string.tempo_config_mancante),
                        centrato = true,
                        modifier = Modifier.padding(horizontal = Spazi.xxl),
                    )
                }

                finestra == null -> Centro {
                    TestoCentrato(stringResource(R.string.tempo_errore))
                }

                else -> ContenutoTempo(
                    usoRecente = finestra.usoRecente,
                    sitiRecenti = finestra.sitiRecenti,
                    medie = finestra.medie,
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
    sitiRecenti: List<SitiGiorno>?,
    medie: Medie?,
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
            item {
                RigaDatiVecchi(
                    ricevutaAlle?.let {
                        stringResource(R.string.dati_fermi_alle, oraOppureDataOra(it))
                    } ?: stringResource(R.string.tempo_dati_vecchi),
                )
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
            // inventare — si dice che non ci sono dati, punto. La sezione dei
            // siti resta comunque in fondo: vive di un campo suo.
            item {
                StatoPrimaApertura(
                    titolo = stringResource(R.string.tempo_nessuna_fotografia_titolo),
                    testo = stringResource(R.string.tempo_nessuna_fotografia),
                    modifier = Modifier.padding(vertical = Spazi.l),
                )
            }
        } else {
            item {
                SelettoreGiorni(
                    giorni = usoRecente,
                    selezionato = selezionato,
                    onScelta = { giornoScelto = it },
                )
            }

            // L'eroe: il totale del giorno scelto, e sotto come si divide.
            item { SchedaGiorno(giorno = selezionato, oggi = selezionato == usoRecente.last()) }

            // Gli otto giorni (si toccano per cambiare giorno) e le medie.
            item {
                SchedaOttoGiorni(
                    giorni = usoRecente,
                    selezionato = selezionato.giorno,
                    onScelta = { giornoScelto = it },
                    medie = medie,
                )
            }

            if (selezionato.totaleMinuti != null) {
                val elenco = elencoTempo(selezionato)
                if (elenco.dentroIlPatto.isEmpty() && elenco.restoDellaGiornata.isEmpty()) {
                    item { RigaVuota(stringResource(R.string.tempo_app_vuoto)) }
                }
                if (elenco.dentroIlPatto.isNotEmpty()) {
                    item {
                        BloccoElenco(stringResource(R.string.tempo_dentro_il_patto)) {
                            ListaRighe(elenco.dentroIlPatto) { RigaDentroIlPatto(it) }
                        }
                    }
                }
                if (elenco.restoDellaGiornata.isNotEmpty()) {
                    item {
                        BloccoElenco(stringResource(R.string.tempo_resto_della_giornata)) {
                            ListaRighe(elenco.restoDellaGiornata) {
                                RigaRestoDellaGiornata(it, elenco.massimoDelGiorno)
                            }
                        }
                    }
                }
            }
        }

        // I siti visitati del giorno SCELTO qui sopra: stesso selettore, stesse
        // otto barre. Se il server non manda il campo (versione vecchia) la
        // sezione non esiste proprio — meglio assente che vuota e inspiegata.
        if (!sitiRecenti.isNullOrEmpty()) {
            val ultimoGiorno = usoRecente.lastOrNull()?.giorno ?: sitiRecenti.last().giorno
            val giornoSiti = selezionato?.giorno ?: sitiRecenti.last().giorno
            sezioneSiti(
                siti = sitiRecenti.firstOrNull { it.giorno == giornoSiti },
                giorno = giornoSiti,
                oggi = giornoSiti == ultimoGiorno,
            )
        }
    }
}

/** Un blocco dell'elenco: il sopra-titolo attaccato alle sue righe. */
@Composable
private fun BloccoElenco(titolo: String, righe: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = Spazi.s)) {
        SopraTitolo(titolo)
        righe()
    }
}

private fun nomeVoce(voce: VoceTempo): String =
    if (voce.categoria) etichettaCategoria(voce.chiave) else voce.nome ?: voce.chiave

/**
 * Una voce DENTRO IL PATTO: la barra è sul limite — l'unica scala che il ragazzo
 * si è dato — e resta `primary` anche oltre. Quanto oltre, lo dice il chip.
 * "Oltre" e barra contano sul limite di QUEL giorno (base + bonus concessi),
 * come li conta il figlio; il chip del limite resta quello base della regola.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RigaDentroIlPatto(voce: VoceTempo) {
    val limite = voce.limite ?: return
    val limiteDelGiorno = voce.limiteDelGiorno ?: limite
    val oltre = minutiOltre(voce.minuti, limite, voce.bonus)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spazi.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = nomeVoce(voce),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = testoDurata(voce.minuti.toLong()),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = Spazi.s),
            )
        }
        Spacer(modifier = Modifier.height(Spazi.s))
        BarraUso(minuti = voce.minuti, limite = limiteDelGiorno, massimoDelGiorno = limiteDelGiorno)
        Spacer(modifier = Modifier.height(Spazi.s))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spazi.s),
            verticalArrangement = Arrangement.spacedBy(Spazi.xs),
        ) {
            Etichetta(stringResource(R.string.tempo_limite, testoDurata(limite.toLong())))
            if (oltre > 0) {
                Etichetta(stringResource(R.string.tempo_oltre, testoDurata(oltre.toLong())))
            }
        }
    }
}

/** Una voce del RESTO DELLA GIORNATA: contesto, sottovoce, sulla scala dell'app più usata. */
@Composable
private fun RigaRestoDellaGiornata(voce: VoceTempo, massimoDelGiorno: Int) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spazi.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = nomeVoce(voce),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = testoDurata(voce.minuti.toLong()),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spazi.s),
            )
        }
        Spacer(modifier = Modifier.height(Spazi.s))
        BarraUso(minuti = voce.minuti, limite = null, massimoDelGiorno = massimoDelGiorno)
    }
}

// --- Siti visitati (contratto v2.3) ------------------------------------------
// Il genitore vede QUALI siti, mai cosa ci fa dentro. Tre leggi che questa
// sezione non può violare (contratto-api.md, "Siti visitati — limiti e patto
// etico"): solo domini; l'assenza di dati non diventa mai uno zero; la cecità
// dichiarata (`dns_cifrato`) è un DATO, quindi non è colorata come un guasto.
// E niente blocchi: qui non c'è, e non ci sarà, nessun bottone per vietare un
// sito — se un sito è un problema, se ne parla.

private fun LazyListScope.sezioneSiti(siti: SitiGiorno?, giorno: String, oggi: Boolean) {
    item {
        Column(
            modifier = Modifier.padding(top = Spazi.s),
            verticalArrangement = Arrangement.spacedBy(Spazi.xs),
        ) {
            SopraTitolo(stringResource(R.string.siti_sezione_titolo))
            RigaContestoSiti()
        }
    }

    // La confessione di cecità viene PRIMA della lista: spiega i buchi di quello
    // che si sta per leggere. Neutra, mai rossa: non è un errore, è un dato.
    if (siti?.dnsCifrato == true) {
        item { RigaNotaNeutra(stringResource(R.string.siti_dns_cifrato)) }
    }

    // `totale_domini: null` (o il giorno che manca del tutto) = nessuna
    // fotografia. "Non lo so" non si traveste da "zero siti".
    val totale = siti?.totaleDomini
    if (siti == null || totale == null) {
        item { SitiSenzaDati(giorno = giorno, oggi = oggi) }
        return
    }

    // L'ordine è già garantito dal contratto (visite decrescenti, poi dominio in
    // ordine alfabetico): lo si riapplica lo stesso, con la STESSA regola, così
    // una fotografia disordinata non cambia la lista che il figlio vede.
    val domini = siti.domini.sortedWith(
        compareByDescending<SitoVisitato> { it.visite }.thenBy { it.dominio },
    )
    if (domini.isEmpty()) {
        item { RigaVuota(stringResource(R.string.siti_vuoto)) }
    } else {
        // Il riferimento della barra è il sito più richiesto del giorno: un
        // confronto tra pari dentro la giornata, mai una soglia da rispettare.
        val riferimento = domini.first().visite
        item {
            ListaRighe(domini) {
                RigaBarraSito(
                    dominio = it.dominio,
                    visite = it.visite,
                    riferimento = riferimento,
                    modifier = Modifier.padding(vertical = Spazi.s),
                )
            }
        }
        // La fotografia porta al massimo i 200 domini più richiesti, ma
        // `totale_domini` resta quello VERO: se la lista è tagliata lo si dice.
        val nascosti = totale - domini.size
        if (nascosti > 0) {
            item {
                Text(
                    text = pluralStringResource(R.plurals.siti_altri, nascosti, nascosti),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    val fotografia = istanteServer(siti.aggiornatoTs)
    if (fotografia != null) {
        item {
            Text(
                text = stringResource(
                    R.string.tempo_fotografia_delle,
                    oraOppureDataOra(fotografia),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Cosa si vede e cosa no: la riga che tiene la sezione dentro il patto. */
@Composable
private fun RigaContestoSiti() {
    Column(verticalArrangement = Arrangement.spacedBy(Spazi.xs)) {
        Text(
            text = stringResource(R.string.siti_contesto),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.siti_contesto_numeri),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Tavola rotonda: niente esiste nella finestra del genitore che il figlio
        // non veda identico. Dirlo qui è metà del patto.
        Text(
            text = stringResource(R.string.siti_stessa_lista),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Una nota di contesto su fondo neutro (`surfaceVariant`), MAI `errorContainer`:
 * il DNS cifrato non è un guasto e non è una colpa — è il registro che dichiara
 * di non aver potuto vedere. Stessa grammatica della riga "dati fermi".
 */
@Composable
private fun RigaNotaNeutra(testo: String) {
    RigaDatiVecchi(testo)
}

/** Giorno senza fotografia dei siti: si dice "nessun dato", mai zero. */
@Composable
private fun SitiSenzaDati(giorno: String, oggi: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        RigaVuota(
            if (oggi) {
                stringResource(R.string.siti_nessun_dato_oggi)
            } else {
                stringResource(R.string.siti_nessun_dato_giorno, giornoBreve(giorno))
            },
        )
        Text(
            text = stringResource(R.string.siti_nessun_dato_spiega),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spazi.xs),
        )
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
 * La scheda EROE del Tempo: il totale del giorno scelto (etichetta e valore
 * separati, un solo numero grande), poi l'anello delle categorie con la sua
 * legenda. Un giorno senza fotografia non ha anello e lo dice a parole: mai uno
 * zero finto, mai una ciambella vuota che sembra "zero minuti".
 */
@Composable
private fun SchedaGiorno(giorno: UsoGiorno, oggi: Boolean) {
    // Con la fetta "resto" (non categorizzato) la legenda somma sempre al totale.
    val fette = fetteConResto(giorno.categorie, giorno.totaleMinuti)
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Spazi.l)) {
            TotaleGiorno(giorno = giorno, oggi = oggi)
            val totale = giorno.totaleMinuti
            if (totale != null) {
                Spacer(modifier = Modifier.height(Spazi.l))
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AnelloCategorie(fette = fette, totaleMinuti = totale)
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
            }
        }
    }
}

/**
 * Il totale del giorno: `labelMedium` "OGGI" sopra, `displaySmall` il valore,
 * `labelSmall` quando è arrivato il dato — un "oggi 3 h" delle 14:00 non
 * racconta la serata. Senza fotografia lo si dice, esplicito.
 */
@Composable
private fun TotaleGiorno(giorno: UsoGiorno, oggi: Boolean) {
    SopraTitolo(
        if (oggi) {
            stringResource(R.string.tempo_etichetta_oggi)
        } else {
            stringResource(R.string.tempo_etichetta_giorno, giornoBreve(giorno.giorno))
        },
    )
    val totale = giorno.totaleMinuti
    if (totale == null) {
        Text(
            text = stringResource(R.string.tempo_nessun_dato),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = Spazi.xs),
        )
        Text(
            text = stringResource(R.string.tempo_nessun_dato_spiega),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spazi.xs),
        )
    } else {
        Text(
            text = testoDurata(totale.toLong()),
            style = MaterialTheme.typography.displaySmall,
        )
        val fotografia = istanteServer(giorno.aggiornatoTs)
        if (fotografia != null) {
            Text(
                text = stringResource(
                    R.string.tempo_fotografia_delle,
                    oraOppureDataOra(fotografia),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Gli otto giorni (si guardano, e si toccano per cambiare giorno) e le medie. */
@Composable
private fun SchedaOttoGiorni(
    giorni: List<UsoGiorno>,
    selezionato: String,
    onScelta: (String) -> Unit,
    medie: Medie?,
) {
    CardContenuto {
        Column(modifier = Modifier.padding(Spazi.l)) {
            SopraTitolo(stringResource(R.string.grafico_ultimi_giorni))
            Spacer(modifier = Modifier.height(Spazi.m))
            BarreGiorni(
                giorni = giorni,
                selezionato = selezionato,
                onScelta = onScelta,
            )
            // Le medie settimanale/mensile: nascoste se il server non le manda
            // (campo o sotto-oggetto null = niente da mostrare, mai uno zero finto).
            if (medie != null && (medie.settimana != null || medie.mese != null)) {
                Spacer(modifier = Modifier.height(Spazi.l))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(Spazi.l))
                BloccoMedie(medie)
            }
        }
    }
}
