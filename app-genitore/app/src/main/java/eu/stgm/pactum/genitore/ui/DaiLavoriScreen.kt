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
import eu.stgm.pactum.genitore.dati.StatiFaccenda
import eu.stgm.pactum.genitore.rete.PostinoClient
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import androidx.compose.ui.graphics.Color
import eu.stgm.pactum.design.SchermataColorata
import eu.stgm.pactum.design.Sezione

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

    SchermataColorata(Sezione.LAVORI) {
        Scaffold(
            containerColor = Color.Transparent,
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
                        text = testoInizioBlocco(p, inizio, nomeFiglio, stato.conApprovazione, inStudio(stato.blocco.takeIf { stato.di(figlioId) })),
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

// --- (0.17, contratto v3.9) "Cambia il lavoro" ------------------------------------------

/** I rifiuti di "Cambia il lavoro" che si correggono sulla pagina (gli altri la chiudono). */
private val RIFIUTI_DA_CORREGGERE = setOf(PostinoClient.PARAMETRI_NON_VALIDI, null)

/**
 * "Cambia il lavoro": la pagina di "Dai lavori di casa" per UN lavoro ancora da
 * fare. Il titolo, la nota e da quando blocca (Subito / Dalle HH:MM, con la frase
 * di che cosa succede e che spostare l'ora sposta il blocco). "Salva" manda solo
 * quello che cambia (PATCH); senza cambi non chiama il server. Se nel frattempo è
 * arrivata la foto, il lavoro non si cambia più e la pagina lo dice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModificaLavoroScreen(
    faccendaId: Long,
    vm: FaccendeViewModel = viewModel(),
    famigliaVm: FamigliaViewModel = viewModel(),
) {
    val cornice = LocalCornice.current
    val stato by vm.stato.collectAsStateWithLifecycle()
    val famiglia by famigliaVm.stato.collectAsStateWithLifecycle()
    val figlioId = famiglia.figlioId
    val nomeFiglio = famiglia.figlioScelto?.nome
    val p = parole()

    LaunchedEffect(figlioId, famiglia.pronta) {
        if (famiglia.pronta && !stato.di(figlioId)) vm.aggiorna(figlioId)
    }
    val elenco = stato.faccende.takeIf { stato.di(figlioId) }
    val faccenda = elenco?.firstOrNull { it.id == faccendaId }

    // Il modulo parte dal lavoro com'è, una volta sola (poi resta quello scritto, anche ruotando).
    var caricato by rememberSaveable { mutableStateOf(false) }
    var titolo by rememberSaveable { mutableStateOf("") }
    var nota by rememberSaveable { mutableStateOf("") }
    // I valori con cui la pagina è partita, salvati con lei: "Salva" manda solo
    // quello che cambia rispetto a QUESTI (non al lavoro riletto dopo).
    var titoloPrima by rememberSaveable { mutableStateOf("") }
    var notaPrima by rememberSaveable { mutableStateOf("") }
    var subito by rememberSaveable { mutableStateOf(true) }
    var subitoIniziale by rememberSaveable { mutableStateOf(true) }
    var minutoDelGiorno by rememberSaveable { mutableIntStateOf(oraProposta(LocalTime.now()).toSecondOfDay() / 60) }
    var minutoIniziale by rememberSaveable { mutableIntStateOf(-1) }
    var sceltaOra by rememberSaveable { mutableStateOf(false) }
    var errore by rememberSaveable { mutableStateOf<String?>(null) }
    var adesso by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            adesso = ZonedDateTime.now()
        }
    }
    LaunchedEffect(faccenda?.id) {
        if (caricato || faccenda == null) return@LaunchedEffect
        titolo = faccenda.titolo
        nota = faccenda.nota.orEmpty()
        titoloPrima = titolo
        notaPrima = nota
        val futuro = bloccoIniziale(faccenda, Instant.now())
        subito = futuro == null
        subitoIniziale = subito
        if (futuro != null) {
            val ora = futuro.atZone(ZoneId.systemDefault()).toLocalTime()
            minutoDelGiorno = ora.hour * 60 + ora.minute
            minutoIniziale = minutoDelGiorno
        }
        caricato = true
    }

    // Gli esiti: cambiato (o niente da cambiare) → si torna ai Lavori e lo si dice; un
    // dato da correggere o la rete restano scritti qui; gli altri chiudono la pagina.
    LaunchedEffect(stato.evento) {
        val evento = stato.evento ?: return@LaunchedEffect
        when (evento) {
            EventoFaccende.Modificata, EventoFaccende.NessunCambio -> {
                vm.consumaEvento()
                famigliaVm.aggiorna()
                cornice.messaggi.mostra(
                    p.testo(if (evento == EventoFaccende.Modificata) R.string.modifica_fatto else R.string.modifica_nessun_cambio),
                )
                cornice.indietro()
            }
            is EventoFaccende.Rifiuto -> if (evento.gesto == GestoFaccende.MODIFICA) {
                vm.consumaEvento()
                val nome = famiglia.figli.firstOrNull { it.id == evento.figlioId }?.nome
                val frase = messaggioRifiutoFaccende(p, evento.codice, evento.gesto, nome)
                if (evento.codice in RIFIUTI_DA_CORREGGERE) {
                    errore = frase
                } else {
                    cornice.messaggi.mostra(frase)
                    cornice.indietro()
                }
            }
            else -> Unit
        }
    }

    val ora = LocalTime.of(minutoDelGiorno / 60, minutoDelGiorno % 60)
    // L'ora scelta all'inizio e non toccata: vale com'era (anche "giovedì dalle 16:00").
    val bloccoInvariato = if (subito) subitoIniziale else (!subitoIniziale && minutoDelGiorno == minutoIniziale)
    val inizio = if (subito || bloccoInvariato) null else inizioBlocco(ora, adesso)
    val problemaDelTitolo = problemaTitolo(titolo)
    val problemaDellaNota = problemaNota(nota)
    val valido = problemaDelTitolo == null && problemaDellaNota == null

    SchermataColorata(Sezione.LAVORI) {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = { BarraPagina(stringResource(R.string.modifica_titolo)) },
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                when {
                    // Senza collegamento non c'è niente da cambiare: si dice dove si fa.
                    stato.configurazioneMancante -> StatoVuoto(
                        centrato = true,
                        titolo = stringResource(R.string.config_mancante_titolo),
                        testo = stringResource(R.string.faccende_config_mancante),
                        azione = stringResource(R.string.azione_collega),
                        onAzione = { cornice.apri(Pagina.Impostazioni(SezioneImpostazioni.COLLEGAMENTO)) },
                    )
                    elenco == null && stato.serverVecchio -> StatoVuoto(stringResource(R.string.faccende_server_vecchio), centrato = true)
                    elenco == null -> Caricamento(testo = stringResource(R.string.faccende_caricamento))
                    // Non c'è più, o non è più da fare (è arrivata la foto, l'hanno tolto).
                    faccenda == null || faccenda.stato != StatiFaccenda.DA_FARE ->
                        StatoVuoto(stringResource(R.string.modifica_non_trovato), centrato = true)
                    else -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .imePadding()
                            .verticalScroll(rememberScrollState())
                            .padding(Spazi.l),
                        verticalArrangement = Arrangement.spacedBy(Spazi.s),
                    ) {
                        val avviso = testoProblemaTitolo(p, problemaDelTitolo)
                        OutlinedTextField(
                            value = titolo,
                            onValueChange = { titolo = it },
                            label = { Text(stringResource(R.string.modifica_campo)) },
                            singleLine = true,
                            isError = avviso != null,
                            supportingText = avviso?.let { { Text(it) } },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = nota,
                            onValueChange = { nota = it },
                            label = { Text(stringResource(R.string.dai_nota)) },
                            isError = problemaDellaNota != null,
                            // Un lavoro solo: niente "vale per tutti i lavori di questa volta".
                            supportingText = testoProblemaNota(p, problemaDellaNota)?.let { { Text(it) } },
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
                        // Che cosa succede: com'è adesso se non si cambia, se no come sarà.
                        Text(
                            text = if (bloccoInvariato) {
                                testoOraBlocco(p, faccenda).orEmpty()
                            } else {
                                testoInizioBlocco(p, inizio, nomeFiglio, stato.conApprovazione, inStudio(stato.blocco.takeIf { stato.di(figlioId) }))
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (inizio?.domani == true) FontWeight.SemiBold else null,
                            color = if (inizio?.domani == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.modifica_sposta_blocco),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        errore?.let {
                            Text(text = it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        }
                        Button(
                            enabled = valido && !stato.invio,
                            onClick = {
                                errore = null
                                val adessoVero = ZonedDateTime.now()
                                val blocco: BloccoModificato? = when {
                                    bloccoInvariato -> BloccoModificato.Invariato
                                    subito -> BloccoModificato.Subito
                                    else -> when (val controllo = controlloPrimaDiMandare(inizio, ora, adessoVero)) {
                                        // L'istante vero non è quello mostrato: niente parte, si mostra il nuovo.
                                        is ControlloInvio.Cambiato -> {
                                            adesso = adessoVero
                                            null
                                        }
                                        is ControlloInvio.Manda -> controllo.bloccoDa?.let { BloccoModificato.Dalle(it) } ?: BloccoModificato.Subito
                                    }
                                }
                                if (blocco != null) {
                                    vm.modifica(figlioId, faccenda, cambiDellaModifica(titoloPrima, notaPrima, titolo, nota, blocco))
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = Spazi.s),
                        ) {
                            Text(stringResource(R.string.modifica_salva))
                        }
                    }
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
