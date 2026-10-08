package eu.stgm.pactum.figlio.ui

import eu.stgm.pactum.design.RigaStato
import eu.stgm.pactum.design.StatoVuoto
import eu.stgm.pactum.design.Caricamento
import eu.stgm.pactum.design.Pillola
import eu.stgm.pactum.design.CardEvidenza
import eu.stgm.pactum.design.FilaPulsanti
import eu.stgm.pactum.design.FoglioDalBasso
import eu.stgm.pactum.design.Tono
import eu.stgm.pactum.design.TitoloSezione
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.design.BarraUso
import eu.stgm.pactum.design.LegendaStriscia
import eu.stgm.pactum.design.TestoSuUnaRiga
import eu.stgm.pactum.design.GiornoPatto
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.design.StrisciaGiorni
import eu.stgm.pactum.design.contaGiorni
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.bonus.BonusInSospeso
import eu.stgm.pactum.figlio.bonus.EsitoBonus
import eu.stgm.pactum.figlio.dati.Riepilogo
import eu.stgm.pactum.figlio.dati.StatoBonus
import eu.stgm.pactum.figlio.faccende.StatoBlocco
import eu.stgm.pactum.figlio.faccende.TestoFaccende
import eu.stgm.pactum.figlio.faccende.QuandoBlocca
import eu.stgm.pactum.figlio.faccende.VistaFaccende
import eu.stgm.pactum.figlio.permessi.StatoPermessi
import eu.stgm.pactum.figlio.ui.OggiViewModel.RigaRegola
import eu.stgm.pactum.figlio.valutatore.MomentoFascia
import eu.stgm.pactum.figlio.valutatore.StatoFascia
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.ui.graphics.Color
import eu.stgm.pactum.design.SchermataColorata
import eu.stgm.pactum.design.Sezione
import eu.stgm.pactum.design.TitoloBarra
import eu.stgm.pactum.design.coloriBarra
import eu.stgm.pactum.design.rememberContatore

/**
 * (0.15) Oggi risponde a UNA domanda: "com'è oggi?". Dall'alto: al massimo
 * una card di stato (il blocco dei lavori di casa, o la sessione in corso),
 * le righe di stato che servono (dati vecchi, scollegato, un permesso da
 * sistemare, un "Inizia" senza risposta), la card del patto (serie, striscia,
 * "6 su 7"), una riga per regola, e dove è finito il tempo (le prime 3 app).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OggiScreen(
    onApriImpostazioni: () -> Unit,
    onApriPermessi: () -> Unit,
    onApriLavori: () -> Unit,
    onApriTempo: () -> Unit,
    vm: OggiViewModel = viewModel(),
    dichiarazioniVm: DichiarazioniViewModel = viewModel(),
) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    val statoDiario by dichiarazioniVm.stato.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val ambitoSnackbar = rememberCoroutineScope()
    // (0.11) La Sessione in corso; (0.13) il blocco dei lavori di casa.
    val inSessione = rememberSessioneInCorso()
    // (0.18, contratto v4.0) La Sessione Studio: una card in cima quando è in corso o sta per partire.
    val studio = rememberStudio()
    val avvioIncerto = rememberAvvioIncerto()
    val memoria by StatoBlocco.memoria.collectAsStateWithLifecycle()
    val bloccato = rememberBloccoFaccende()
    var permessi by remember { mutableStateOf(StatoPermessi.leggi(context)) }
    val conVitaReale = stato.regole.any { it is RigaRegola.VitaReale }

    // Prima lettura e rilettura a ogni ritorno in primo piano (e i permessi,
    // che si danno nelle impostazioni di sistema).
    LifecycleResumeEffect(Unit) {
        vm.aggiorna()
        permessi = StatoPermessi.leggi(context)
        onPauseOrDispose { }
    }
    // Lo stato di oggi delle regole di vita reale ("Segna", o già segnato).
    LifecycleResumeEffect(conVitaReale) {
        if (conVitaReale) dichiarazioniVm.aggiorna()
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
            is OggiViewModel.Evento.BonusPartito ->
                context.getString(R.string.bonus_partito_dopo_rete, evento.minuti)
            OggiViewModel.Evento.BonusGiaPartito -> context.getString(R.string.bonus_gia_partito)
            null -> null
        }
        if (messaggio != null) snackbarHostState.showSnackbar(messaggio)
        if (stato.evento != null) vm.consumaEvento()
    }

    // (0.15) "Segna" su una regola di vita reale: la dichiarazione, subito.
    val dichiarare = rememberDichiarare(
        vm = dichiarazioniVm,
        regole = stato.regole.filterIsInstance<RigaRegola.VitaReale>().map { it.regola },
        snackbarHostState = snackbarHostState,
    )
    // (0.15) Il foglio del bonus, aperto su una regola di tempo (sopravvive a una rotazione).
    var foglioBonusId by rememberSaveable { mutableStateOf<Long?>(null) }

    val daFare = VistaFaccende.daFare(memoria)
    val cima = Cima.di(
        bloccato = bloccato,
        sessioneInCorso = inSessione.attiva != null,
        // (0.18, v4.0) Conta anche una foto mandata in anticipo e non ancora approvata.
        bloccoProgrammato = !bloccato && memoria.prossimo != null && VistaFaccende.aperte(memoria).isNotEmpty(),
    )
    val permessiMancanti = Permessi.mancanti(permessi)
    // La prima lettura: niente numeri finti ("0 min") prima che i dati ci siano.
    val primaLettura = stato.caricamento && stato.striscia.isEmpty() && stato.regole.isEmpty() &&
        stato.righe.isEmpty() && stato.minutiTotali == 0L

    SchermataColorata(Sezione.OGGI) {
        Scaffold(
            containerColor = Color.Transparent,
            // Le barre di sistema le copre lo Scaffold esterno (MainActivity).
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                TopAppBar(
                    title = { TitoloBarra(stringResource(R.string.oggi_titolo)) },
                    colors = coloriBarra(),
                    actions = { AzioniBarra(onAggiorna = { vm.aggiorna() }, onApriImpostazioni = onApriImpostazioni) },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            LazyColumn(
                state = rememberLazyListState(),
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(Spazi.l + Spazi.xs),
                verticalArrangement = Arrangement.spacedBy(Spazi.l),
            ) {
                // 0. (0.18) La Sessione Studio, in corso o che sta per partire.
                if (haCardStudio(studio)) {
                    item(key = "studio") {
                        CardStudioOggi(studio) { messaggio -> ambitoSnackbar.launch { snackbarHostState.showSnackbar(messaggio) } }
                    }
                }
                // 1. Una sola card di stato, solo se c'è qualcosa.
                when (cima.card) {
                    CardCima.BLOCCO -> item(key = "blocco") {
                        CardBloccoLavori(
                            daFare = daFare.size,
                            daApprovare = VistaFaccende.inApprovazione(memoria).size,
                            onApriLavori = onApriLavori,
                        )
                    }
                    CardCima.SESSIONE -> inSessione.attiva?.let { attiva ->
                        item(key = "sessione-in-corso") {
                            SchedaSessioneInCorso(
                                attiva = attiva,
                                adesso = inSessione.adesso,
                                onTerminata = {
                                    ambitoSnackbar.launch {
                                        snackbarHostState.showSnackbar(context.getString(R.string.sessione_terminata))
                                    }
                                    vm.aggiorna()
                                },
                            )
                        }
                    }
                    null -> Unit
                }

                // 2. Le righe di stato, una per cosa e solo se servono.
                if (cima.sessioneInRiga) {
                    inSessione.attiva?.let { attiva ->
                        item(key = "sessione-riga") {
                            RigaSessioneInCorso(
                                attiva = attiva,
                                adesso = inSessione.adesso,
                                onTerminata = {
                                    ambitoSnackbar.launch {
                                        snackbarHostState.showSnackbar(context.getString(R.string.sessione_terminata))
                                    }
                                    vm.aggiorna()
                                },
                            )
                        }
                    }
                }
                if (cima.bloccoProgrammatoInRiga) {
                    memoria.prossimo?.let { prossimo ->
                        item(key = "blocco-programmato") {
                            RigaStato(
                                testo = testoBloccoProgrammato(context, prossimo, memoria.oraServer(eu.stgm.pactum.figlio.faccende.Orologio.adesso())),
                                tono = Tono.Attenzione,
                                azione = stringResource(R.string.oggi_vai_ai_lavori_breve),
                                onAzione = onApriLavori,
                            )
                        }
                    }
                }
                // (0.11) Un "Inizia" rimasto senza risposta: si dice finché non si chiarisce.
                avvioIncerto?.let { incerto -> item(key = "sessione-incerta") { RigaAvvioIncerto(incerto) } }
                if (stato.scollegato) {
                    // (v3) Non è un'età dei dati: il telefono va ricollegato, e si dice come.
                    item(key = "scollegato") {
                        RigaStato(
                            testo = stringResource(R.string.scollegato),
                            azione = stringResource(R.string.azione_collega),
                            onAzione = onApriImpostazioni,
                        )
                    }
                } else if (stato.datiFermi) {
                    item(key = "dati-fermi") { RigaStato(testoDatiVecchi(stato.datiFermiAlle)) }
                }
                // (0.15) Un permesso che manca (batteria, notifiche, "Mostra sopra le
                // altre app"): una riga, e "Risolvi" porta ai Permessi.
                if (permessiMancanti.isNotEmpty()) {
                    item(key = "permessi") {
                        RigaStato(
                            testo = testoPermessiMancanti(permessiMancanti),
                            tono = Tono.Attenzione,
                            azione = stringResource(R.string.azione_risolvi),
                            onAzione = onApriPermessi,
                        )
                    }
                }

                if (primaLettura) {
                    item(key = "caricamento") {
                        Caricamento(testo = stringResource(R.string.oggi_caricamento), centrato = false)
                    }
                    return@LazyColumn
                }

                // 3. La card del patto.
                if (stato.striscia.isNotEmpty()) {
                    item(key = "patto") {
                        SchedaPatto(
                            striscia = stato.striscia,
                            riepilogo = stato.riepilogo,
                            serie = stato.serie,
                            record = stato.record,
                            righeDispositivi = stato.righeDispositivi,
                        )
                    }
                }

                // 4. Le regole di oggi, una riga per regola.
                if (stato.regole.isNotEmpty()) {
                    item(key = "regole-titolo") {
                        Column(verticalArrangement = Arrangement.spacedBy(Spazi.xs)) {
                            TitoloSezione(stringResource(R.string.oggi_sezione_regole))
                            // (0.16) Il bonus di oggi, una riga sola (non sotto ogni regola).
                            val bonus = stato.bonus
                            if (bonus != null && stato.regole.any { it is RigaRegola.Tempo }) {
                                Nota(
                                    stringResource(
                                        if (stato.altriDispositivi) R.string.oggi_bonus_tetti_telefono else R.string.oggi_bonus_tetti,
                                        bonus.giorno.residui,
                                        bonus.giorno.tetto,
                                        bonus.settimana.residui,
                                        bonus.settimana.tetto,
                                    ),
                                )
                            }
                        }
                    }
                    item(key = "regole") {
                        Column {
                            stato.regole.forEachIndexed { indice, riga ->
                                if (indice > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                RigaDellaRegola(
                                    riga = riga,
                                    sospeso = sospeso,
                                    diOggi = (riga as? RigaRegola.VitaReale)?.let {
                                        dichiarazioneDiOggi(it.regola, statoDiario.tutte, oggiDelPatto(statoDiario.fuso))
                                    },
                                    onBonus = { foglioBonusId = riga.regola.id },
                                    onSegna = { dichiarare.apri(riga.regola) },
                                )
                            }
                        }
                    }
                }

                // 5. Dove è finito il tempo: il totale, (0.16) gli 8 giorni coi totali
                // di 7 e 30 giorni, le prime 3 app e "Vedi tutto" (la pagina Tempo).
                item(key = "tempo-titolo") {
                    Column(verticalArrangement = Arrangement.spacedBy(Spazi.xs)) {
                        TitoloSezione(
                            // (v3) Con altri dispositivi: questi minuti sono solo del telefono.
                            testo = stringResource(
                                if (stato.altriDispositivi) R.string.oggi_sezione_tempo_telefono else R.string.oggi_sezione_tempo,
                            ),
                        )
                        Text(
                            text = stringResource(R.string.oggi_tempo_totale, testoDurata(stato.minutiTotali)),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                // (0.16) Gli 8 giorni e i totali, come li vede il genitore (server v3.8).
                stato.tempi.firstOrNull()?.takeIf { it.storico && it.giorni.size > 1 }?.let { questo ->
                    item(key = "tempo-giorni") { TempoInOggi(questo) }
                }
                // (0.11) Il tempo passato in sessione: c'è, ma non conta. Lo si dice.
                if (stato.minutiInSessione > 0) {
                    item(key = "tempo-sessione") {
                        Text(
                            text = stringResource(R.string.oggi_in_sessione_non_contati, testoDurata(stato.minutiInSessione)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item(key = "tempo-app") {
                    Column {
                        if (stato.righe.isEmpty()) {
                            if (!stato.caricamento) {
                                StatoVuoto(stringResource(R.string.oggi_vuoto), icona = Icons.Outlined.CheckCircle)
                            }
                        } else {
                            ElencoApp(stato.righe.take(APP_IN_OGGI))
                        }
                        // (0.16) Sempre: la pagina Tempo ha anche le categorie, gli altri giorni, il computer.
                        if (!stato.caricamento || stato.tempi.isNotEmpty()) {
                            TextButton(onClick = onApriTempo, contentPadding = PaddingValues(horizontal = 0.dp, vertical = Spazi.s)) {
                                Text(stringResource(R.string.oggi_vedi_tutto))
                            }
                        }
                    }
                }
            }
        }
    }

    // Il foglio del bonus: +5 / +15 / +30, e il residuo detto una volta qui.
    stato.regole.filterIsInstance<RigaRegola.Tempo>().firstOrNull { it.regola.id == foglioBonusId }?.let { riga ->
        FoglioBonus(
            riga = riga,
            bonus = stato.bonus,
            sospeso = sospeso,
            altriDispositivi = stato.altriDispositivi,
            onChiudi = { foglioBonusId = null },
            onBonus = { minuti ->
                foglioBonusId = null
                vm.concedi(riga.regola.id, minuti)
            },
        )
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

/** Quante app si vedono in Oggi prima di "Vedi tutte". */
private const val APP_IN_OGGI = 3

/** Le app del giorno, una riga ciascuna (nome a sinistra, minuti a destra). */
@Composable
private fun ElencoApp(righe: List<OggiViewModel.RigaUso>) {
    righe.forEachIndexed { indice, riga ->
        if (indice > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        RigaApp(riga)
    }
}

@Composable
private fun RigaApp(riga: OggiViewModel.RigaUso) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spazi.m),
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

/**
 * (0.15) Il blocco dei lavori di casa, in cima a Oggi: "Prima i lavori di
 * casa · 2 da fare" e "Vai ai lavori".
 */
@Composable
private fun CardBloccoLavori(daFare: Int, daApprovare: Int, onApriLavori: () -> Unit) {
    CardEvidenza(tono = Tono.Attenzione) {
        Text(
            text = when {
                daFare > 0 -> pluralStringResource(R.plurals.oggi_blocco_lavori, daFare, daFare)
                // (0.18, contratto v4.0) Solo foto che aspettano un genitore.
                daApprovare > 0 -> pluralStringResource(R.plurals.oggi_blocco_approvazione, daApprovare, daApprovare)
                else -> stringResource(R.string.faccende_bloccato)
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(Spazi.m))
        Button(onClick = onApriLavori) { Text(stringResource(R.string.oggi_vai_ai_lavori), maxLines = 1) }
    }
}

/** "Alle 16:00 il telefono si blocca, se i lavori di casa non sono fatti" (e domani, giovedì…). */
private fun testoBloccoProgrammato(context: android.content.Context, prossimo: Long, adesso: Long): String =
    when (val quando = TestoFaccende.quandoBlocca(prossimo, adesso, ZoneId.systemDefault())) {
        QuandoBlocca.Subito -> context.getString(R.string.faccende_bloccato)
        is QuandoBlocca.Oggi -> context.getString(R.string.faccende_prossimo_alle, quando.ora)
        is QuandoBlocca.Domani -> context.getString(R.string.faccende_prossimo_domani, quando.ora)
        is QuandoBlocca.Giorno -> context.getString(R.string.faccende_prossimo_giorno, quando.giorno, quando.ora)
        is QuandoBlocca.Data -> context.getString(R.string.faccende_prossimo_giorno, quando.data, quando.ora)
    }

/** "Da sistemare: manca il permesso «Notifiche»", o "Da sistemare: mancano 2 permessi". */
@Composable
fun testoPermessiMancanti(mancanti: List<Permesso>): String =
    if (mancanti.size == 1) {
        stringResource(R.string.oggi_manca_permesso, stringResource(nomePermesso(mancanti.first())))
    } else {
        stringResource(R.string.oggi_mancano_permessi, mancanti.size)
    }

/**
 * La card del patto, in evidenza: la serie (su una riga), il record una riga
 * sotto, la striscia degli 8 giorni, "6 su 7", e in una riga ciascuno il
 * riepilogo del server e i dispositivi, se ci sono.
 */
@Composable
private fun SchedaPatto(
    striscia: List<GiornoPatto>,
    riepilogo: Riepilogo?,
    serie: Int,
    record: Int,
    righeDispositivi: List<RigaDispositivo>,
) {
    // (0.19) I giorni di fila salgono contando (da 1: mai "0 giorni di fila").
    val serieVista = rememberContatore(serie).coerceAtLeast(1)
    CardEvidenza(tono = Tono.Positivo) {
        // La serie non va mai a capo (B6): se non ci sta si rimpicciolisce.
        TestoSuUnaRiga(
            testo = if (serie > 0) {
                pluralStringResource(R.plurals.oggi_serie, serieVista, serieVista)
            } else {
                // Serie a zero: il numero grande non deve dare torto al ragazzo.
                stringResource(if (record > 0) R.string.oggi_si_riparte else R.string.oggi_si_comincia)
            },
            style = MaterialTheme.typography.displaySmall,
            minimo = 18.sp,
        )
        if (serie > 0) {
            Text(text = stringResource(R.string.oggi_serie_dentro), style = MaterialTheme.typography.bodyLarge)
        }
        if (record > 0) {
            // Una riga sotto, piccola, mai accanto al numero grande.
            Text(
                text = stringResource(R.string.oggi_record, record),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = Spazi.xs),
            )
        }
        val (mantenuti, conDati) = contaGiorni(striscia)
        val frase = if (conDati == 0) {
            stringResource(R.string.oggi_striscia_senza_dati)
        } else {
            pluralStringResource(R.plurals.oggi_striscia_frase, conDati, mantenuti, conDati)
        }
        Spacer(modifier = Modifier.height(Spazi.m))
        StrisciaGiorni(giorni = striscia, lato = 32.dp, descrizione = frase)
        Spacer(modifier = Modifier.height(Spazi.s))
        LegendaStriscia(
            mantenuta = stringResource(R.string.legenda_mantenuta),
            fuoriRegola = stringResource(R.string.legenda_fuori_regola),
            senzaDati = stringResource(R.string.legenda_senza_dati),
            oggi = stringResource(R.string.legenda_oggi),
        )
        Spacer(modifier = Modifier.height(Spazi.s))
        Text(text = frase, style = MaterialTheme.typography.bodyMedium)
        // Server vecchio senza `riepilogo`: la riga non c'è.
        if (riepilogo != null) {
            Text(text = testoRiepilogo(riepilogo), style = MaterialTheme.typography.bodySmall)
        }
        // (v3) I dispositivi, in una riga: "Questo telefono: 6 su 7 · Computer: 5 su 7".
        if (righeDispositivi.isNotEmpty()) {
            Text(
                text = righeDispositivi.map { testoRigaDispositivo(it) }
                    .joinToString(stringResource(R.string.elenco_separatore)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** "Computer: 5 su 7", "Questo telefono: 6 su 7", o "ancora nessun dato". */
@Composable
private fun testoRigaDispositivo(riga: RigaDispositivo): String {
    val base = when {
        riga.questo -> stringResource(R.string.oggi_questo_telefono)
        riga.nome.isNotBlank() -> riga.nome
        else -> stringResource(R.string.oggi_dispositivo_senza_nome)
    }
    val nome = if (riga.revocato) stringResource(R.string.oggi_dispositivo_scollegato, base) else base
    return if (riga.conDati == 0) {
        stringResource(R.string.oggi_dispositivo_riga_senza_dati, nome)
    } else {
        stringResource(R.string.oggi_dispositivo_riga, nome, riga.mantenuti, riga.conDati)
    }
}

/**
 * "Nessun giorno fuori regola · registrazione completa", oppure i conti. Le
 * stesse frasi dell'app del genitore: gli stessi fatti, con le stesse parole.
 */
@Composable
private fun testoRiepilogo(riepilogo: Riepilogo): String {
    val fuori = riepilogo.giorniFuoriRegola
    val interruzioni = riepilogo.interruzioni
    val parteFuori = if (fuori == 0) {
        stringResource(R.string.riepilogo_nessun_fuori_regola)
    } else {
        pluralStringResource(R.plurals.riepilogo_giorni_fuori_regola, fuori, fuori)
    }
    val parteInterruzioni = if (interruzioni == 0) {
        stringResource(R.string.riepilogo_registro_completo)
    } else {
        pluralStringResource(R.plurals.riepilogo_interruzioni, interruzioni, interruzioni)
    }
    return parteFuori + stringResource(R.string.elenco_separatore) + parteInterruzioni
}

@Composable
private fun RigaDellaRegola(
    riga: RigaRegola,
    sospeso: BonusInSospeso?,
    diOggi: eu.stgm.pactum.figlio.dati.Dichiarazione?,
    onBonus: () -> Unit,
    onSegna: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spazi.m),
        verticalArrangement = Arrangement.spacedBy(Spazi.s),
    ) {
        when (riga) {
            is RigaRegola.Tempo -> RigaTempo(riga, sospeso, onBonus)
            is RigaRegola.Fascia -> {
                Text(
                    text = descrizioneRegola(riga.regola.tipo, riga.regola.parametri),
                    style = MaterialTheme.typography.bodyLarge,
                )
                // (0.16) Si vede se oggi è rispettata: i minuti dentro la fascia sono
                // quelli dello sforamento (Valutatore.statoFasciaOggi).
                val stato = riga.stato
                if (stato != null) {
                    StatoDellaFascia(stato)
                } else {
                    riga.momento?.let {
                        Text(
                            text = testoMomento(it),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            is RigaRegola.VitaReale -> Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = descrizioneRegola(riga.regola.tipo, riga.regola.parametri),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (diOggi != null) {
                        Text(
                            text = descrizioneStato(diOggi, riga.regola),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (diOggi == null) {
                    OutlinedButton(onClick = onSegna, modifier = Modifier.padding(start = Spazi.s)) {
                        Text(stringResource(R.string.oggi_segna))
                    }
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
 * Limite di tempo: il nome, "48 min su 1 h" e il "+" del bonus; sotto la
 * barra sul limite efficace (l'unica scala che il ragazzo si è dato) e, se è
 * oltre, la pillola "7 min oltre".
 */
@Composable
private fun RigaTempo(
    riga: RigaRegola.Tempo,
    sospeso: BonusInSospeso?,
    onBonus: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = riga.nome,
                style = MaterialTheme.typography.bodyLarge,
            )
            val minutiSuLimite = stringResource(
                R.string.oggi_minuti_su_limite,
                testoDurata(riga.minuti),
                testoDurata(riga.limiteEfficace.toLong()),
            )
            // (0.16) Il bonus che ti sei dato oggi su questa regola, piccolo, accanto;
            // se non ci sta va a capo tutto intero (spazi che non si spezzano).
            val bonusRegola = if (riga.bonusOggi > 0) {
                stringResource(R.string.oggi_bonus_regola, riga.bonusOggi).replace(' ', '\u00A0')
            } else {
                null
            }
            val separatore = stringResource(R.string.elenco_separatore)
            val piccolo = MaterialTheme.typography.bodySmall.fontSize
            Text(
                text = buildAnnotatedString {
                    append(minutiSuLimite)
                    if (bonusRegola != null) {
                        append(separatore)
                        withStyle(SpanStyle(fontSize = piccolo)) { append(bonusRegola) }
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Un solo "+": apre il foglio del bonus (area di tocco 48 dp).
        FilledTonalIconButton(onClick = onBonus) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.oggi_bonus_apri, riga.nome))
        }
    }
    BarraUso(
        minuti = riga.minuti.toInt(),
        limite = riga.limiteEfficace,
        massimoDelGiorno = riga.limiteEfficace,
    )
    // Oltre il limite la barra resta piena e verde: l'eccedenza si dice a parole.
    if (riga.minuti > riga.limiteEfficace) {
        Pillola(stringResource(R.string.oggi_oltre, testoDurata(riga.minuti - riga.limiteEfficace)), tono = Tono.Attenzione)
    }
    if (sospeso?.regolaId == riga.regola.id) {
        Text(
            text = stringResource(R.string.oggi_bonus_in_partenza, sospeso.minuti),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * (0.15) Il foglio del bonus di una regola: +5 / +15 / +30 e quanto bonus
 * resta oggi e questa settimana, detto UNA volta, qui. Il bonus parte come
 * sempre: il tocco lo prepara, la snackbar offre "Aggiungi perché".
 */
@Composable
private fun FoglioBonus(
    riga: RigaRegola.Tempo,
    bonus: StatoBonus?,
    sospeso: BonusInSospeso?,
    altriDispositivi: Boolean,
    onChiudi: () -> Unit,
    onBonus: (Int) -> Unit,
) {
    // Il residuo vero di oggi è il più piccolo dei due tetti. Senza i contatori
    // (server vecchio) decide il server.
    val residuo = bonus?.let { minOf(it.giorno.residui, it.settimana.residui) }
    FoglioDalBasso(onChiudi = onChiudi, titolo = stringResource(R.string.oggi_bonus_foglio_titolo, riga.nome)) {
      Column(verticalArrangement = Arrangement.spacedBy(Spazi.m)) {
        if (bonus != null) {
            Text(
                // (v3) I tetti valgono per dispositivo: con un computer lo si dice.
                text = stringResource(
                    if (altriDispositivi) R.string.oggi_bonus_tetti_telefono else R.string.oggi_bonus_tetti,
                    bonus.giorno.residui,
                    bonus.giorno.tetto,
                    bonus.settimana.residui,
                    bonus.settimana.tetto,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (riga.bonusOggi > 0) {
            Text(
                text = stringResource(R.string.oggi_bonus_gia_dato_solo, riga.bonusOggi),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (sospeso != null) {
            Text(
                text = stringResource(R.string.oggi_bonus_in_partenza, sospeso.minuti),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilaPulsanti {
            listOf(5, 15, 30).forEach { minuti ->
                FilledTonalButton(
                    enabled = sospeso == null && (residuo == null || minuti <= residuo),
                    onClick = { onBonus(minuti) },
                ) {
                    Text(stringResource(R.string.bonus_piu_minuti, minuti), maxLines = 1, softWrap = false)
                }
            }
        }
      }
    }
}

/**
 * (0.16) Com'è andata oggi una fascia: una pillola quando si sa (rispettata
 * finora, rispettata, i minuti dentro, in tono di attenzione), una riga
 * quando deve ancora cominciare o oggi non c'è.
 */
@Composable
private fun StatoDellaFascia(stato: StatoFascia) {
    when (stato) {
        is StatoFascia.Fuori -> Pillola(
            stringResource(R.string.fascia_fuori, testoDurata(stato.minuti)),
            tono = Tono.Attenzione,
        )
        is StatoFascia.RispettataFinora -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spazi.s),
        ) {
            Pillola(stringResource(R.string.fascia_rispettata_finora), tono = Tono.Positivo)
            Text(
                text = stringResource(R.string.fascia_finisce_alle, orario(stato.fine)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatoFascia.Rispettata -> Pillola(stringResource(R.string.fascia_rispettata), tono = Tono.Positivo)
        is StatoFascia.Inizia -> Text(
            text = stringResource(R.string.fascia_inizia_alle, orario(stato.inizio)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StatoFascia.NonOggi -> Text(
            text = stringResource(R.string.fascia_oggi_non_c_e),
            style = MaterialTheme.typography.bodyMedium,
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
    // Era già partito: niente "non è partito in tempo".
    is EsitoBonus.GiornoCambiato -> context.getString(R.string.bonus_giorno_cambiato)
}
