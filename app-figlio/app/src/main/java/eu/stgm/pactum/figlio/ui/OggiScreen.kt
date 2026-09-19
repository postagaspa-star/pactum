package eu.stgm.pactum.figlio.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.design.BarraUso
import eu.stgm.pactum.design.GiornoPatto
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.design.StrisciaGiorni
import eu.stgm.pactum.design.contaGiorni
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.bonus.BonusInSospeso
import eu.stgm.pactum.figlio.bonus.EsitoBonus
import eu.stgm.pactum.figlio.dati.StatoBonus
import eu.stgm.pactum.figlio.ui.OggiViewModel.RigaRegola
import eu.stgm.pactum.figlio.valutatore.MomentoFascia
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Oggi: il patto prima del consumo (tavola rotonda D1). Un solo eroe, la
 * serie; sotto la striscia degli 8 giorni identica a quella del genitore; poi
 * una riga per regola; in fondo, più piccolo, dove è finito il tempo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OggiScreen(
    onApriImpostazioni: () -> Unit,
    onApriSiti: () -> Unit,
    onApriDiario: () -> Unit,
    vm: OggiViewModel = viewModel(),
) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Prima lettura e rilettura a ogni ritorno in primo piano.
    LifecycleResumeEffect(Unit) {
        vm.aggiorna()
        onPauseOrDispose { }
    }

    // La finestra del bonus: finché è aperta il bonus aspetta; si chiude da
    // sola quando il bonus parte (lo decide ConsegnaBonus, non la snackbar).
    val sospeso = stato.bonusInSospeso
    val nomeSospeso = stato.regole.filterIsInstance<RigaRegola.Tempo>()
        .firstOrNull { it.regola.id == sospeso?.regolaId }?.nome
    LaunchedEffect(sospeso?.id, stato.finestraBonus) {
        if (sospeso != null && stato.finestraBonus) {
            val esito = snackbarHostState.showSnackbar(
                message = context.getString(R.string.oggi_bonus_dato, sospeso.minuti, nomeSospeso ?: ""),
                actionLabel = context.getString(R.string.oggi_bonus_aggiungi_perche),
                duration = SnackbarDuration.Indefinite,
            )
            if (esito == SnackbarResult.ActionPerformed) vm.aggiungiPerche()
        }
    }

    LaunchedEffect(stato.evento) {
        val messaggio = when (val evento = stato.evento) {
            is OggiViewModel.Evento.Bonus -> testoEsitoBonus(context, evento.esito, stato.bonus)
            OggiViewModel.Evento.BonusGiaPartito -> context.getString(R.string.bonus_gia_partito)
            null -> null
        }
        if (messaggio != null) snackbarHostState.showSnackbar(messaggio)
        if (stato.evento != null) vm.consumaEvento()
    }

    Scaffold(
        // Le barre di sistema le copre lo Scaffold esterno (MainActivity).
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.oggi_titolo)) },
                actions = {
                    IconButton(onClick = { vm.aggiorna() }) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.azione_aggiorna))
                    }
                    IconButton(onClick = onApriImpostazioni) {
                        Icon(Icons.Filled.Settings, stringResource(R.string.azione_impostazioni))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(Spazi.l + Spazi.xs),
            verticalArrangement = Arrangement.spacedBy(Spazi.l),
        ) {
            if (stato.datiFermi) {
                item { BannerDatiVecchi(stato.datiFermiAlle) }
            }

            if (stato.caricamento && stato.striscia.isEmpty() && stato.regole.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text(
                                text = stringResource(R.string.oggi_caricamento),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = Spazi.s),
                            )
                        }
                    }
                }
            }

            if (stato.striscia.isNotEmpty()) {
                // xxl tra l'eroe e il resto: 16 dello spacedBy + 16 qui.
                item {
                    SchedaPatto(
                        striscia = stato.striscia,
                        serie = stato.serie,
                        record = stato.record,
                        modifier = Modifier.padding(bottom = Spazi.l),
                    )
                }
            }

            if (stato.regole.isNotEmpty()) {
                item { Sopratitolo(stringResource(R.string.oggi_sezione_regole)) }
                item {
                    Column {
                        stato.regole.forEachIndexed { indice, riga ->
                            if (indice > 0) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                            RigaDellaRegola(
                                riga = riga,
                                bonus = stato.bonus,
                                sospeso = sospeso,
                                onBonus = { minuti -> vm.concedi(riga.regola.id, minuti) },
                                onApriDiario = onApriDiario,
                            )
                        }
                    }
                }
                val bonus = stato.bonus
                if (bonus != null && stato.regole.any { it is RigaRegola.Tempo }) {
                    item {
                        Text(
                            text = stringResource(
                                R.string.oggi_bonus_tetti,
                                bonus.giorno.residui,
                                bonus.giorno.tetto,
                                bonus.settimana.residui,
                                bonus.settimana.tetto,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // I siti visitati (v2.3): stanno qui, in chiaro, nell'app del figlio.
            // È il SUO registro, che lui condivide — non una registrazione
            // fatta su di lui (contratto-api.md, "Siti visitati").
            item {
                Card(
                    onClick = onApriSiti,
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(modifier = Modifier.padding(Spazi.l)) {
                        Text(
                            text = stringResource(R.string.siti_scorciatoia_titolo),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(R.string.siti_scorciatoia_testo),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Spazi.xs),
                        )
                    }
                }
            }

            // Dove è finito il tempo: scende sotto, e il totale non è più un eroe.
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Sopratitolo(
                        testo = stringResource(R.string.oggi_sezione_tempo),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = testoDurata(stato.minutiTotali),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            item {
                if (stato.righe.isEmpty()) {
                    if (!stato.caricamento) {
                        RigaVuota(Icons.Outlined.CheckCircle, stringResource(R.string.oggi_vuoto))
                    }
                } else {
                    Column {
                        stato.righe.forEachIndexed { indice, riga ->
                            if (indice > 0) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = Spazi.m),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = riga.etichetta,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = testoDurata(riga.minuti),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Il perché, se il ragazzo ha toccato "Aggiungi perché". Il dialogo dipende
    // dal bonus su disco, non da uno stato della schermata: sopravvive a una
    // rotazione e perfino alla morte del processo.
    if (sospeso?.inScrittura == true) {
        DialogoPerche(
            chiave = sospeso.id,
            onManda = { vm.mandaBonus(it) },
            onSenza = { vm.mandaBonus(null) },
        )
    }
}

/** La scheda eroe: la serie, il record una riga sotto, la striscia degli 8 giorni. */
@Composable
private fun SchedaPatto(
    striscia: List<GiornoPatto>,
    serie: Int,
    record: Int,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Column(modifier = Modifier.padding(Spazi.l + Spazi.xs)) {
            if (serie > 0) {
                Text(
                    text = pluralStringResource(R.plurals.oggi_serie, serie, serie),
                    style = MaterialTheme.typography.displaySmall,
                )
                Text(
                    text = stringResource(R.string.oggi_serie_dentro),
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                // Serie a zero: il numero grande non deve dare torto al ragazzo.
                Text(
                    text = stringResource(
                        if (record > 0) R.string.oggi_si_riparte else R.string.oggi_si_comincia,
                    ),
                    style = MaterialTheme.typography.displaySmall,
                )
            }
            if (record > 0) {
                // Una riga sotto, senza colore, mai accanto al numero grande.
                Text(
                    text = stringResource(R.string.oggi_record, record),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spazi.xs),
                )
            }
            Spacer(modifier = Modifier.height(Spazi.m))
            val (mantenuti, conDati) = contaGiorni(striscia)
            val frase = if (conDati == 0) {
                stringResource(R.string.oggi_striscia_senza_dati)
            } else {
                pluralStringResource(R.plurals.oggi_striscia_frase, conDati, mantenuti, conDati)
            }
            StrisciaGiorni(giorni = striscia, lato = 32.dp, descrizione = frase)
            Text(
                text = frase,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = Spazi.s),
            )
        }
    }
}

@Composable
private fun RigaDellaRegola(
    riga: RigaRegola,
    bonus: StatoBonus?,
    sospeso: BonusInSospeso?,
    onBonus: (Int) -> Unit,
    onApriDiario: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spazi.m),
        verticalArrangement = Arrangement.spacedBy(Spazi.s),
    ) {
        when (riga) {
            is RigaRegola.Tempo -> RigaTempo(riga, bonus, sospeso, onBonus)
            is RigaRegola.Fascia -> {
                Text(
                    text = descrizioneRegola(riga.regola.tipo, riga.regola.parametri),
                    style = MaterialTheme.typography.bodyLarge,
                )
                riga.momento?.let {
                    Text(
                        text = testoMomento(it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            is RigaRegola.VitaReale -> {
                Text(
                    text = descrizioneRegola(riga.regola.tipo, riga.regola.parametri),
                    style = MaterialTheme.typography.bodyLarge,
                )
                TextButton(onClick = onApriDiario, contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(R.string.oggi_vita_reale_diario))
                }
            }
            is RigaRegola.Altra -> Text(
                text = descrizioneRegola(riga.regola.tipo, riga.regola.parametri),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/**
 * Limite di tempo: la barra sul limite efficace (l'unica scala che il ragazzo
 * si è dato), "48 min su 1 h", e sotto il bonus in due tocchi.
 */
@Composable
private fun RigaTempo(
    riga: RigaRegola.Tempo,
    bonus: StatoBonus?,
    sospeso: BonusInSospeso?,
    onBonus: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = riga.nome,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(
                R.string.oggi_minuti_su_limite,
                testoDurata(riga.minuti),
                testoDurata(riga.limiteEfficace.toLong()),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    BarraUso(
        minuti = riga.minuti.toInt(),
        limite = riga.limiteEfficace,
        massimoDelGiorno = riga.limiteEfficace,
    )
    // Oltre il limite la barra resta piena e verde: l'eccedenza si dice a parole.
    if (riga.minuti > riga.limiteEfficace) {
        Etichetta(
            stringResource(R.string.oggi_oltre, testoDurata(riga.minuti - riga.limiteEfficace)),
        )
    }

    // Il residuo vero di oggi è il più piccolo dei due tetti. Senza i contatori
    // (server vecchio) decide il server.
    val residuo = bonus?.let { minOf(it.giorno.residui, it.settimana.residui) }
    Row(horizontalArrangement = Arrangement.spacedBy(Spazi.s)) {
        listOf(5, 15, 30).forEach { minuti ->
            FilledTonalButton(
                enabled = sospeso == null && (residuo == null || minuti <= residuo),
                onClick = { onBonus(minuti) },
                contentPadding = PaddingValues(horizontal = Spazi.m),
            ) {
                Text(stringResource(R.string.bonus_piu_minuti, minuti))
            }
        }
    }
    val rigaBonus = when {
        sospeso?.regolaId == riga.regola.id ->
            stringResource(R.string.oggi_bonus_in_partenza, sospeso.minuti)
        riga.bonusOggi > 0 && residuo != null ->
            stringResource(R.string.oggi_bonus_gia_dato, riga.bonusOggi, residuo)
        riga.bonusOggi > 0 -> stringResource(R.string.oggi_bonus_gia_dato_solo, riga.bonusOggi)
        residuo != null -> stringResource(R.string.oggi_bonus_restano, residuo)
        else -> null
    }
    rigaBonus?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val formatoOraFascia: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun testoMomento(momento: MomentoFascia): String = when (momento) {
    is MomentoFascia.Prima -> stringResource(
        R.string.oggi_fascia_prima,
        testoDurata(momento.minuti),
        orario(momento.inizio),
    )
    is MomentoFascia.InCorso -> stringResource(R.string.oggi_fascia_in_corso, orario(momento.fine))
    MomentoFascia.Finita -> stringResource(R.string.oggi_fascia_finita)
    MomentoFascia.NonOggi -> stringResource(R.string.oggi_fascia_non_oggi)
}

private fun orario(ora: LocalTime): String = formatoOraFascia.format(ora)

/**
 * Il perché del bonus, facoltativo e DOPO. Qualunque uscita manda il bonus:
 * col perché, o senza. Il bonus non si perde mai per un dialogo chiuso.
 */
@Composable
private fun DialogoPerche(chiave: String, onManda: (String) -> Unit, onSenza: () -> Unit) {
    var testo by rememberSaveable(chiave) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onSenza,
        title = { Text(stringResource(R.string.oggi_perche_titolo)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spazi.m)) {
                Text(
                    text = stringResource(R.string.oggi_perche_testo),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = testo,
                    onValueChange = { testo = it },
                    label = { Text(stringResource(R.string.oggi_perche_campo)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onManda(testo) }) { Text(stringResource(R.string.oggi_perche_manda)) }
        },
        dismissButton = {
            TextButton(onClick = onSenza) { Text(stringResource(R.string.oggi_perche_senza)) }
        },
    )
}

/** Gli esiti del bonus nelle parole del patto (§3.5): una scelta già fatta, non una violazione. */
private fun testoEsitoBonus(
    context: android.content.Context,
    esito: EsitoBonus,
    bonus: StatoBonus?,
): String? = when (esito) {
    is EsitoBonus.Concesso -> null
    is EsitoBonus.TettoSuperato -> when {
        esito.residuoGiorno >= esito.minuti ->
            context.getString(R.string.bonus_tetto_settimana, esito.residuoSettimana)
        esito.residuoGiorno > 0 ->
            context.getString(R.string.bonus_tetto_restano_oggi, esito.residuoGiorno)
        else -> context.getString(R.string.bonus_tetto_superato, bonus?.giorno?.tetto ?: 0)
    }
    is EsitoBonus.RegolaNonValida -> context.getString(R.string.bonus_regola_non_valida)
    is EsitoBonus.Rifiutato -> context.getString(R.string.bonus_errore)
    is EsitoBonus.SenzaRete -> context.getString(R.string.bonus_senza_rete)
    is EsitoBonus.Scaduto -> context.getString(R.string.bonus_scaduto)
}
