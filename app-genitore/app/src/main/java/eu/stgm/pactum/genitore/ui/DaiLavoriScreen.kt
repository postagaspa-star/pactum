package eu.stgm.pactum.genitore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.design.RigaStato
import eu.stgm.pactum.design.StatoVuoto
import eu.stgm.pactum.design.Caricamento
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.CodiciErrore
import eu.stgm.pactum.genitore.dati.MASSIMO_FACCENDE_PER_VOLTA
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.ZonedDateTime

// (0.15) "Dai lavori di casa", a pagina intera (prima era un dialogo che con la
// tastiera aperta non entrava: B21/B36). Gli stessi campi, la stessa validazione,
// la stessa scelta del blocco ("Subito" / "Dalle 16:00", righe che si toccano per
// intero) e la stessa frase che dice che cosa succede, prima del tocco. Uno o più
// lavori (fino a 10), quelli usati di recente da toccare per riusarli, una nota
// facoltativa (uguale per tutti) e da quando bloccano. Se l'ora scelta è già
// passata, vale domani e la pagina lo dice; se il giorno cambia con la pagina
// aperta, il primo tocco aggiorna la frase e non manda niente.

/** I rifiuti dopo i quali la pagina si chiude: riprovare lì non serve. */
private val RIFIUTI_CHE_CHIUDONO = setOf(
    CodiciErrore.ESITO_INCERTO,
    CodiciErrore.NON_TROVATO,
    CodiciErrore.SERVER_DA_AGGIORNARE,
)

/** Come si salva l'elenco dei lavori scritti (una rotazione non lo perde). */
private val SalvaTitoli = listSaver<List<String>, String>(save = { it }, restore = { it })

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DaiLavoriScreen(
    vm: FaccendeViewModel = viewModel(),
    famigliaVm: FamigliaViewModel = viewModel(),
) {
    val cornice = LocalCornice.current
    val stato by vm.stato.collectAsStateWithLifecycle()
    val famiglia by famigliaVm.stato.collectAsStateWithLifecycle()
    val figlioId = famiglia.figlioId
    val nomeFiglio = famiglia.figlioScelto?.nome
    val p = parole()
    val senzaBlocco = remember(famiglia.figlioScelto) {
        dispositiviSenzaBlocco(famiglia.figlioScelto?.dispositivi.orEmpty())
    }
    val storia = stato.faccende.takeIf { stato.di(figlioId) }.orEmpty()

    // Se l'elenco non è di questo figlio (la pagina ritrovata dopo che Android ha
    // chiuso l'app), lo si legge: serve a sapere se il server conosce i lavori di casa.
    LaunchedEffect(figlioId, famiglia.pronta) {
        if (famiglia.pronta && !stato.di(figlioId)) vm.aggiorna(figlioId)
    }

    var titoli by rememberSaveable(stateSaver = SalvaTitoli) { mutableStateOf(listOf("")) }
    var nota by rememberSaveable { mutableStateOf("") }
    var subito by rememberSaveable { mutableStateOf(true) }
    var minutoDelGiorno by rememberSaveable {
        mutableIntStateOf(oraProposta(LocalTime.now()).toSecondOfDay() / 60)
    }
    var sceltaOra by rememberSaveable { mutableStateOf(false) }
    // (0.15) Il rifiuto del server che si corregge qui sopravvive alla rotazione (B15).
    var errore by rememberSaveable { mutableStateOf<String?>(null) }
    var adesso by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            adesso = ZonedDateTime.now()
        }
    }

    // Gli esiti di "Dai lavori di casa": dati → si torna ai Lavori e lo si dice; i
    // rifiuti che si correggono qui (troppi lavori, un titolo che non va) restano
    // scritti sulla pagina; gli altri chiudono la pagina e si dicono in basso.
    LaunchedEffect(stato.evento) {
        val evento = stato.evento ?: return@LaunchedEffect
        when (evento) {
            is EventoFaccende.Date -> {
                vm.consumaEvento()
                famigliaVm.aggiorna()
                cornice.messaggi.mostra(testoFaccendeDate(p, evento.quante))
                cornice.indietro()
            }
            is EventoFaccende.Rifiuto -> if (evento.gesto == GestoFaccende.DAI) {
                vm.consumaEvento()
                famigliaVm.aggiorna()
                val nome = famiglia.figli.firstOrNull { it.id == evento.figlioId }?.nome
                val frase = messaggioRifiutoFaccende(p, evento.codice, evento.gesto, nome)
                if (evento.codice !in RIFIUTI_CHE_CHIUDONO) {
                    errore = frase
                } else {
                    cornice.messaggi.mostra(frase)
                    cornice.indietro()
                }
            }
            // Bocciature e lavori tolti li dice la scheda Lavori, appena si torna lì.
            else -> Unit
        }
    }

    val ora = LocalTime.of(minutoDelGiorno / 60, minutoDelGiorno % 60)
    val inizio = if (subito) null else inizioBlocco(ora, adesso)
    val problemi = titoli.map { problemaTitolo(it) }
    val problemaDellaNota = problemaNota(nota)
    // Le righe lasciate vuote non contano: partono i lavori scritti.
    val scritti = titoli.filter { it.isNotBlank() }
    val valido = scritti.isNotEmpty() && scritti.size <= MASSIMO_FACCENDE_PER_VOLTA &&
        scritti.all { problemaTitolo(it) == null } && problemaDellaNota == null
    val recenti = remember(storia, titoli) { titoliRecenti(storia, titoli) }
    val disponibile = figlioId != null && stato.di(figlioId) && !stato.serverVecchio

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            BarraPagina(
                nomeDaScrivere(nomeFiglio)?.let { stringResource(R.string.dai_titolo, it) }
                    ?: stringResource(R.string.dai_titolo_senza_nome),
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (!disponibile) {
                if (stato.serverVecchio) {
                    StatoVuoto(stringResource(R.string.faccende_server_vecchio), centrato = true)
                } else {
                    Caricamento(testo = stringResource(R.string.faccende_caricamento))
                }
                return@Box
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(Spazi.l),
                verticalArrangement = Arrangement.spacedBy(Spazi.s),
            ) {
                senzaBlocco.forEach { RigaStato(testoDispositivoSenzaBlocco(p, it)) }
                titoli.forEachIndexed { indice, titolo ->
                    Row(verticalAlignment = Alignment.Top) {
                        // Il problema si dice solo su una riga già scritta: una riga vuota appena aggiunta non è un errore.
                        val avviso = testoProblemaTitolo(p, problemi[indice]).takeIf { titolo.isNotEmpty() }
                        OutlinedTextField(
                            value = titolo,
                            onValueChange = { nuovo -> titoli = titoli.toMutableList().also { it[indice] = nuovo } },
                            label = { Text(stringResource(R.string.dai_campo_faccenda, indice + 1)) },
                            placeholder = { Text(stringResource(R.string.dai_faccenda_esempio)) },
                            singleLine = true,
                            isError = avviso != null,
                            supportingText = if (avviso != null) {
                                { Text(avviso) }
                            } else {
                                null
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (titoli.size > 1) {
                            IconButton(onClick = { titoli = titoli.toMutableList().also { it.removeAt(indice) } }) {
                                Icon(Icons.Filled.Close, stringResource(R.string.dai_togli_riga))
                            }
                        }
                    }
                }
                if (titoli.size < MASSIMO_FACCENDE_PER_VOLTA) {
                    TextButton(onClick = { titoli = titoli + "" }) { Text(stringResource(R.string.dai_aggiungi)) }
                } else {
                    Text(
                        text = stringResource(R.string.dai_massimo, MASSIMO_FACCENDE_PER_VOLTA),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // I lavori usati di recente: un tocco li mette nella prima riga vuota (o in una nuova).
                if (recenti.isNotEmpty()) {
                    SopraTitolo(stringResource(R.string.dai_recenti))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spazi.s)) {
                        recenti.forEach { recente ->
                            SuggestionChip(
                                onClick = {
                                    val vuota = titoli.indexOfFirst { it.isBlank() }
                                    titoli = when {
                                        vuota >= 0 -> titoli.toMutableList().also { it[vuota] = recente }
                                        titoli.size < MASSIMO_FACCENDE_PER_VOLTA -> titoli + recente
                                        else -> titoli
                                    }
                                },
                                label = { Text(recente) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = nota,
                    onValueChange = { nota = it },
                    label = { Text(stringResource(R.string.dai_nota)) },
                    isError = problemaDellaNota != null,
                    supportingText = {
                        Text(testoProblemaNota(p, problemaDellaNota) ?: stringResource(R.string.dai_nota_spiega))
                    },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                SopraTitolo(stringResource(R.string.dai_blocco), modifier = Modifier.padding(top = Spazi.s))
                RigaScelta(selezionata = subito, testo = stringResource(R.string.dai_subito), onClick = { subito = true })
                RigaScelta(
                    selezionata = !subito,
                    testo = testoDalle(p, ora),
                    onClick = { subito = false },
                    azione = stringResource(R.string.dai_cambia_ora),
                    onAzione = { sceltaOra = true },
                )
                // Che cosa succede, prima del tocco: domani si dice in evidenza.
                Text(
                    text = testoInizioBlocco(p, inizio, nomeFiglio),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (inizio?.domani == true) FontWeight.SemiBold else null,
                    color = if (inizio?.domani == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                errore?.let {
                    Text(text = it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                Button(
                    enabled = valido && !stato.invio,
                    onClick = {
                        errore = null
                        // L'istante vero, adesso: se non è quello che la pagina mostra
                        // (mezzanotte passata con la pagina aperta, un'ora intanto passata),
                        // niente parte e la pagina mostra la data nuova.
                        val adessoVero = ZonedDateTime.now()
                        when (val controllo = controlloPrimaDiMandare(inizio, ora.takeIf { !subito }, adessoVero)) {
                            is ControlloInvio.Cambiato -> adesso = adessoVero
                            is ControlloInvio.Manda -> if (figlioId != null) {
                                vm.daiFaccende(figlioId, scritti, nota.ifBlank { null }, controllo.bloccoDa, famiglia.io)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = Spazi.s),
                ) {
                    Text(stringResource(R.string.dai_manda))
                }
            }
        }
    }

    if (sceltaOra) {
        val statoOra = rememberTimePickerState(
            initialHour = minutoDelGiorno / 60,
            initialMinute = minutoDelGiorno % 60,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { sceltaOra = false },
            title = { Text(stringResource(R.string.dai_scegli_ora)) },
            // (0.15) Il quadrante scorre se non entra (schermo piccolo, telefono girato: B21).
            text = { Box(modifier = Modifier.verticalScroll(rememberScrollState())) { TimePicker(state = statoOra) } },
            confirmButton = {
                TextButton(
                    onClick = {
                        minutoDelGiorno = statoOra.hour * 60 + statoOra.minute
                        subito = false
                        sceltaOra = false
                        adesso = ZonedDateTime.now()
                    },
                ) {
                    Text(stringResource(R.string.azione_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { sceltaOra = false }) { Text(stringResource(R.string.azione_annulla)) }
            },
        )
    }
}

/** Una scelta del blocco: si tocca tutta la riga (B12); [azione] a destra, se c'è. */
@Composable
private fun RigaScelta(
    selezionata: Boolean,
    testo: String,
    onClick: () -> Unit,
    azione: String? = null,
    onAzione: () -> Unit = {},
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .selectable(selected = selezionata, onClick = onClick, role = Role.RadioButton),
        ) {
            RadioButton(selected = selezionata, onClick = null)
            Text(text = testo, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = Spazi.s))
        }
        if (azione != null) TextButton(onClick = onAzione) { Text(azione) }
    }
}
