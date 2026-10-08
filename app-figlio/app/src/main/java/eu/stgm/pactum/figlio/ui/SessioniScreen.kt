package eu.stgm.pactum.figlio.ui

import eu.stgm.pactum.design.RigaStato
import eu.stgm.pactum.design.StatoVuoto
import eu.stgm.pactum.design.Caricamento
import eu.stgm.pactum.design.Pillola
import eu.stgm.pactum.design.CardNormale
import eu.stgm.pactum.design.VoceMenu
import eu.stgm.pactum.design.MenuAzioni
import eu.stgm.pactum.design.Tono
import eu.stgm.pactum.design.TitoloSezione
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.catalogo.AppInstallata
import eu.stgm.pactum.figlio.catalogo.CatalogoApp
import eu.stgm.pactum.figlio.permessi.PermessiHelper
import eu.stgm.pactum.figlio.sessione.AppDellaSessione
import eu.stgm.pactum.figlio.sessione.DurataSessione
import eu.stgm.pactum.figlio.sessione.EsitoAvvio
import eu.stgm.pactum.figlio.sessione.EsitoSessione
import eu.stgm.pactum.figlio.sessione.NomeSessione
import eu.stgm.pactum.figlio.sessione.PaginaSessioneActivity
import eu.stgm.pactum.figlio.sessione.PagineSessione
import eu.stgm.pactum.figlio.sessione.SessioneDefinita
import eu.stgm.pactum.figlio.sessione.nomeSessioneConEmoji
import eu.stgm.pactum.figlio.sessione.nomeSessioneTraVirgolette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.Color
import eu.stgm.pactum.design.SchermataColorata
import eu.stgm.pactum.design.Sezione
import eu.stgm.pactum.design.TitoloBarra
import eu.stgm.pactum.design.coloriBarra

/**
 * (0.11) Le Sessioni (contratto v3.5): "Studio", "Lavoro". Le scrive il
 * figlio (un nome e le app del telefono), il genitore le approva una volta e
 * ogni cambio; il figlio le inizia quando vuole, per quanto vuole, e le può
 * terminare prima. Durante una sessione il tempo nelle sue app non conta, e le
 * altre app si coprono con "Sei in sessione".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessioniScreen(
    onApriImpostazioni: () -> Unit = {},
    vm: SessioniViewModel = viewModel(),
    studioVm: StudioViewModel = viewModel(),
) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    // (0.18, contratto v4.0) La Sessione Studio, in cima.
    val statoStudio by studioVm.stato.collectAsStateWithLifecycle()
    val studio = rememberStudio()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    // Le snackbar partono fuori dall'effetto degli eventi: l'evento si consuma
    // subito, e la snackbar non viene interrotta da quel cambio.
    val ambito = rememberCoroutineScope()
    val inCorso = rememberSessioneInCorso()
    val avvioIncerto = rememberAvvioIncerto()
    // (0.13) Col blocco delle faccende le sessioni non si iniziano.
    val bloccoFaccende = rememberBloccoFaccende()

    // I dialoghi si ricordano per id: una rotazione o la morte del processo
    // non li chiudono. Per il modulo: null = chiuso, NUOVA_SESSIONE = nuova.
    var moduloId by rememberSaveable { mutableStateOf<Long?>(null) }
    var daEliminareId by rememberSaveable { mutableStateOf<Long?>(null) }
    var daAvviareId by rememberSaveable { mutableStateOf<Long?>(null) }
    var cambioDaRitirareId by rememberSaveable { mutableStateOf<Long?>(null) }
    // L'ultima copia di ogni sessione aperta in un dialogo: se sparisce dalla
    // lista (rilettura) il dialogo resta e dice perché.
    val viste = remember { HashMap<Long, SessioneDefinita>() }
    fun aperta(id: Long?): SessioneDefinita? =
        id?.let { cercata -> stato.sessioni.firstOrNull { it.id == cercata } ?: viste[cercata] }
    fun ricorda(sessione: SessioneDefinita) {
        viste[sessione.id] = sessione
    }

    LifecycleResumeEffect(Unit) {
        vm.aggiorna()
        studioVm.aggiorna()
        onPauseOrDispose { }
    }

    LaunchedEffect(stato.evento) {
        val evento = stato.evento ?: return@LaunchedEffect
        vm.consumaEvento()
        val messaggio = when (evento) {
            is SessioniViewModel.Evento.Mandata -> {
                moduloId = null
                vm.dimenticaEsiti()
                testoEsitoSessione(context, EsitoSessione.Fatta(null), cambio = evento.cambio)
            }
            SessioniViewModel.Evento.Eliminata -> {
                daEliminareId = null
                context.getString(R.string.sessione_eliminata)
            }
            is SessioniViewModel.Evento.NonEliminata -> {
                daEliminareId = null
                testoEsitoSessione(context, evento.esito)
            }
            SessioniViewModel.Evento.CambioRitirato -> {
                cambioDaRitirareId = null
                context.getString(R.string.sessione_cambio_ritirato)
            }
            is SessioniViewModel.Evento.CambioNonRitirato -> {
                cambioDaRitirareId = null
                testoEsitoSessione(context, evento.esito)
            }
            is SessioniViewModel.Evento.Iniziata -> {
                daAvviareId = null
                vm.dimenticaEsiti()
                // (0.12) Il server l'ha confermata: la pagina animata dell'inizio
                // (solo se è di adesso, non per una risposta letta tornando qui dopo).
                (evento.esito as? EsitoAvvio.Avviata)?.svolta
                    ?.takeIf { PagineSessione.inizioDaMostrare(it, System.currentTimeMillis()) }
                    ?.let { PaginaSessioneActivity.apriInizio(context, it) }
                testoEsitoAvvio(context, evento.esito)
            }
        }
        ambito.launch { snackbarHostState.showSnackbar(messaggio) }
    }

    // "Nuova sessione" non copre mai l'ultima card: lo spazio in fondo è la sua
    // altezza misurata, più il margine.
    val densita = LocalDensity.current
    var altezzaPulsante by remember { mutableIntStateOf(0) }
    val spazioInFondo = with(densita) { altezzaPulsante.toDp() } + Spazi.l * 2

    SchermataColorata(Sezione.SESSIONI) {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                TopAppBar(
                    title = { TitoloBarra(stringResource(R.string.sessioni_titolo)) },
                    colors = coloriBarra(),
                    actions = { AzioniBarra(onAggiorna = { vm.aggiorna() }, onApriImpostazioni = onApriImpostazioni) },
                )
            },
            floatingActionButton = {
                if (stato.letto && !stato.configurazioneMancante && !stato.serverDaAggiornare) {
                    ExtendedFloatingActionButton(
                        onClick = {
                            if (stato.sessioni.size >= SESSIONI_MASSIME) {
                                // Il server ne tiene al massimo 20 per telefono: lo si dice prima.
                                ambito.launch { snackbarHostState.showSnackbar(context.getString(R.string.sessione_esito_troppe)) }
                            } else {
                                vm.dimenticaEsiti()
                                moduloId = NUOVA_SESSIONE
                            }
                        },
                        // La scritta del pulsante allungato non arriva a TalkBack (Material la
                        // nasconde): la dice l'icona.
                        icon = { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.sessioni_nuova)) },
                        text = { Text(stringResource(R.string.sessioni_nuova), maxLines = 1) },
                        modifier = Modifier.onSizeChanged { altezzaPulsante = it.height },
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                when {
                    stato.caricamento && !stato.letto ->
                        Caricamento(testo = stringResource(R.string.sessioni_caricamento))

                    stato.configurazioneMancante ->
                        StatoVuoto(stringResource(R.string.regole_config_mancante), centrato = true, modifier = Modifier.padding(Spazi.xl))

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        // In fondo lo spazio di "Nuova sessione", perché non copra l'ultima.
                        contentPadding = PaddingValues(
                            start = Spazi.l + Spazi.xs,
                            end = Spazi.l + Spazi.xs,
                            top = Spazi.l + Spazi.xs,
                            bottom = spazioInFondo,
                        ),
                        verticalArrangement = Arrangement.spacedBy(Spazi.l),
                    ) {
                        if (stato.scollegato) {
                            item {
                                RigaStato(
                                    testo = stringResource(R.string.scollegato),
                                    azione = stringResource(R.string.azione_collega),
                                    onAzione = onApriImpostazioni,
                                )
                            }
                        } else if (stato.datiFermi) {
                            item { RigaStato(testoDatiVecchi(stato.datiFermiAlle)) }
                        }
                        // (0.18, contratto v4.0) La Sessione Studio, in cima alla scheda.
                        item(key = "studio") {
                            SezioneStudio(statoStudio, studio, studioVm) { messaggio ->
                                ambito.launch { snackbarHostState.showSnackbar(messaggio) }
                            }
                        }
                        item(key = "titolo-sessioni") { TitoloSezione(stringResource(R.string.sessioni_le_tue)) }
                        inCorso.attiva?.let { attiva ->
                            item(key = "in-corso") {
                                SchedaSessioneInCorso(
                                    attiva = attiva,
                                    adesso = inCorso.adesso,
                                    // "Termina la sessione" della notifica porta a Oggi: qui non si ascolta.
                                    ascoltaNotifica = false,
                                    // Le app stanno già sulla card della sessione, qui sotto.
                                    mostraApp = false,
                                    onTerminata = {
                                        ambito.launch {
                                            snackbarHostState.showSnackbar(context.getString(R.string.sessione_terminata))
                                        }
                                    },
                                )
                            }
                        }
                        // Un "Inizia" rimasto senza risposta: si dice finché non si chiarisce.
                        avvioIncerto?.let { incerto -> item(key = "incerta") { RigaAvvioIncerto(incerto) } }
                        if (stato.serverDaAggiornare) {
                            // Mai "errore": il server va aggiornato, il resto dell'app funziona.
                            item { RigaStato(stringResource(R.string.sessioni_server_da_aggiornare)) }
                        } else {
                            if (stato.sessioni.isEmpty()) {
                                // Cos'è una sessione, detto una volta: qui, quando non ce n'è nessuna.
                                item {
                                    StatoVuoto(
                                        titolo = stringResource(R.string.sessioni_vuoto_titolo),
                                        testo = stringResource(R.string.sessioni_vuoto),
                                    )
                                }
                            } else {
                                items(stato.sessioni, key = { it.id }) { sessione ->
                                    CardSessione(
                                        sessione = sessione,
                                        inCorsoQuesta = inCorso.attiva?.sessioneId == sessione.id,
                                        unaInCorso = inCorso.attiva != null,
                                        invioInCorso = stato.invioInCorso,
                                        bloccoFaccende = bloccoFaccende,
                                        inStudio = studio.studio != null,
                                        onInizia = {
                                            ricorda(sessione)
                                            vm.dimenticaEsiti()
                                            daAvviareId = sessione.id
                                        },
                                        onModifica = {
                                            ricorda(sessione)
                                            vm.dimenticaEsiti()
                                            moduloId = sessione.id
                                        },
                                        onElimina = {
                                            ricorda(sessione)
                                            daEliminareId = sessione.id
                                        },
                                        onRitiraCambio = {
                                            ricorda(sessione)
                                            cambioDaRitirareId = sessione.id
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Nuova sessione, o il cambio di una che c'è.
    moduloId?.let { id ->
        val nuova = id == NUOVA_SESSIONE
        val sessione = if (nuova) null else aperta(id)
        if (nuova || sessione != null) {
            DialogoSessione(
                sessione = sessione,
                // Conta anche il nome di un cambio in attesa (contratto v3.5):
                // approvato, due sessioni si chiamerebbero uguali.
                altriNomi = stato.sessioni
                    .filter { it.id != sessione?.id }
                    .flatMap { listOfNotNull(it.nome, it.modificaInAttesa?.nome) },
                invioInCorso = stato.invioInCorso,
                esito = stato.esitoModulo,
                onAnnulla = {
                    moduloId = null
                    vm.dimenticaEsiti()
                },
                onManda = { nome, app, nomi ->
                    if (sessione == null) {
                        vm.crea(nome, app, nomi)
                    } else {
                        vm.modifica(sessione.id, sessione.approvata, nome, app, nomi)
                    }
                },
            )
        }
    }

    aperta(daEliminareId)?.let { sessione ->
        AlertDialog(
            onDismissRequest = { daEliminareId = null },
            title = { Text(stringResource(R.string.sessione_elimina_titolo)) },
            text = { Text(stringResource(R.string.sessione_elimina_testo, nomeSessioneTraVirgolette(context, sessione.nome))) },
            confirmButton = {
                Button(enabled = !stato.invioInCorso, onClick = { vm.elimina(sessione.id) }) {
                    Text(stringResource(R.string.azione_elimina))
                }
            },
            dismissButton = {
                TextButton(onClick = { daEliminareId = null }) { Text(stringResource(R.string.azione_annulla)) }
            },
        )
    }

    aperta(daAvviareId)?.let { sessione ->
        DialogoAvvio(
            sessione = sessione,
            invioInCorso = stato.invioInCorso,
            esito = stato.esitoAvvio,
            onAnnulla = {
                daAvviareId = null
                vm.dimenticaEsiti()
            },
            onInizia = { durata -> vm.avvia(sessione.id, sessione.nome, durata) },
        )
    }

    // "Ritira il cambio": resta la sessione approvata.
    aperta(cambioDaRitirareId)?.let { sessione ->
        AlertDialog(
            onDismissRequest = { cambioDaRitirareId = null },
            title = { Text(stringResource(R.string.sessione_ritira_cambio_titolo)) },
            text = { Text(stringResource(R.string.sessione_ritira_cambio_testo, nomeSessioneTraVirgolette(context, sessione.nome))) },
            confirmButton = {
                Button(enabled = !stato.invioInCorso, onClick = { vm.ritiraCambio(sessione) }) {
                    Text(stringResource(R.string.sessione_ritira_cambio))
                }
            },
            dismissButton = {
                TextButton(onClick = { cambioDaRitirareId = null }) { Text(stringResource(R.string.azione_annulla)) }
            },
        )
    }
}

/** Il modulo aperto su una sessione nuova. */
private const val NUOVA_SESSIONE = -1L

/** Il server ne tiene al massimo 20 per telefono (contratto v3.5). */
private const val SESSIONI_MASSIME = 20

/** La scelta "Altro" della durata: ore e minuti scritti a mano. */
private const val DURATA_ALTRO = -1

/**
 * (0.15) Una sessione, compatta: il nome con l'emoji e lo stato in una
 * pillola, le app in una riga, una nota corta solo se serve (rifiutata col
 * perché, cambio in attesa o non approvato), UN pulsante ("Inizia", se
 * approvata e non in corso, col motivo in una riga quando è spento) e il ⋯
 * con Modifica, Ritira il cambio, Elimina. Si inizia solo una sessione
 * approvata (con o senza un cambio in attesa: vale quella approvata), una alla volta.
 */
@Composable
private fun CardSessione(
    sessione: SessioneDefinita,
    inCorsoQuesta: Boolean,
    unaInCorso: Boolean,
    invioInCorso: Boolean,
    bloccoFaccende: Boolean,
    inStudio: Boolean = false,
    onInizia: () -> Unit,
    onModifica: () -> Unit,
    onElimina: () -> Unit,
    onRitiraCambio: () -> Unit,
) {
    val context = LocalContext.current
    val cambio = sessione.modificaInAttesa
    val voci = buildList {
        add(VoceMenu(stringResource(R.string.sessione_modifica), onModifica))
        // Ci ha ripensato: torna la sessione approvata, il genitore non deve più decidere.
        if (cambio != null) add(VoceMenu(stringResource(R.string.sessione_ritira_cambio), onRitiraCambio, abilitata = !invioInCorso))
        // La sessione in corso non si elimina (il server direbbe di no): prima si termina.
        if (!inCorsoQuesta) add(VoceMenu(stringResource(R.string.azione_elimina), onElimina, distruttiva = true))
    }
    CardNormale {
        Column(verticalArrangement = Arrangement.spacedBy(Spazi.s)) {
            // Il nome e lo stato a sinistra, il ⋯ a destra.
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spazi.xs)) {
                    Text(
                        // (0.12) Con l'emoji del suo tema, come nell'app del genitore: "📚 Studio".
                        text = nomeSessioneConEmoji(context, sessione.nome),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Pillola(
                        stringResource(
                            when {
                                inCorsoQuesta -> R.string.sessione_in_corso_etichetta
                                sessione.approvata -> R.string.sessione_stato_approvata
                                sessione.rifiutata -> R.string.sessione_stato_rifiutata
                                else -> R.string.sessione_stato_in_attesa
                            },
                        ),
                        tono = when {
                            inCorsoQuesta || sessione.approvata -> Tono.Positivo
                            sessione.rifiutata -> Tono.Negativo
                            else -> Tono.Attenzione
                        },
                    )
                }
                MenuAzioni(voci = voci, descrizione = stringResource(R.string.sessione_menu))
            }
            Text(
                text = stringResource(R.string.sessione_app, elencoAppSessione(context, sessione.app, sessione.nomi)),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Una nota sola, e solo se serve.
            val nota = when {
                // (0.15) Col nome del genitore che ha deciso, se il server lo dice.
                sessione.rifiutata -> sessione.motivazione
                    ?.let { modelloGenitoreDice(context, sessione.decisaDa).format(it) }
                    ?: stringResource(R.string.sessione_rifiutata_spiegazione)
                cambio != null -> {
                    val appChieste = cambio.app ?: sessione.app
                    val elenco = elencoAppSessione(context, appChieste, sessione.nomi + cambio.nomi)
                    val nomeChiesto = cambio.nome?.trim()?.takeIf { it.isNotEmpty() && it != sessione.nome }
                    if (nomeChiesto != null) {
                        stringResource(R.string.sessione_cambio_chiesto_nome, nomeSessioneTraVirgolette(context, nomeChiesto), elenco)
                    } else {
                        stringResource(R.string.sessione_cambio_chiesto, elenco)
                    }
                }
                // Un cambio chiesto e non approvato: resta la sessione di prima, e il perché.
                sessione.approvata && sessione.motivazione != null -> sessione.decisaDa
                    ?.let { stringResource(R.string.sessione_cambio_rifiutato_perche_nome, it, sessione.motivazione) }
                    ?: stringResource(R.string.sessione_cambio_rifiutato_perche, sessione.motivazione)
                else -> null
            }
            nota?.let { Nota(it) }
            if (sessione.approvata && !inCorsoQuesta) {
                Button(enabled = !unaInCorso && !invioInCorso && !bloccoFaccende && !inStudio, onClick = onInizia) {
                    Text(stringResource(R.string.sessione_inizia))
                }
                // Il pulsante spento, e il perché in una riga.
                if (unaInCorso) {
                    Nota(stringResource(R.string.sessione_una_gia_in_corso))
                } else if (inStudio) {
                    // (0.18, contratto v4.0) Durante lo Studio le sessioni non si iniziano.
                    Nota(stringResource(R.string.sessione_esito_studio_in_corso))
                } else if (bloccoFaccende) {
                    Nota(stringResource(R.string.sessione_blocco_faccende))
                }
            }
        }
    }
}


/** Le app scelte, come testo che sopravvive a una rotazione. */
private val salvataggioLista = listSaver<List<String>, String>(save = { it }, restore = { it })

/**
 * Nuova sessione o cambio: il nome (1–40 caratteri, unico) e le app, scelte
 * dall'elenco delle app installate. Un cambio parte dal cambio già in attesa,
 * se c'è (lo sostituisce), altrimenti dalla sessione com'è.
 */
@Composable
private fun DialogoSessione(
    sessione: SessioneDefinita?,
    altriNomi: List<String>,
    invioInCorso: Boolean,
    esito: EsitoSessione?,
    onAnnulla: () -> Unit,
    onManda: (nome: String, app: List<String>, nomi: Map<String, String>) -> Unit,
) {
    val context = LocalContext.current
    val partenzaNome = sessione?.modificaInAttesa?.nome ?: sessione?.nome ?: ""
    val partenzaApp = sessione?.modificaInAttesa?.app ?: sessione?.app ?: emptyList()
    // I nomi che il telefono aveva mandato: servono per le app non più installate.
    val nomiNoti = remember(sessione) { (sessione?.nomi ?: emptyMap()) + (sessione?.modificaInAttesa?.nomi ?: emptyMap()) }
    var nome by rememberSaveable(sessione?.id) { mutableStateOf(partenzaNome) }
    var scelte by rememberSaveable(sessione?.id, stateSaver = salvataggioLista) { mutableStateOf(partenzaApp) }
    var sceltaAperta by rememberSaveable(sessione?.id) { mutableStateOf(false) }
    // Le etichette viste nella scelta delle app: vanno al genitore con la sessione.
    val etichette = remember(sessione?.id) { HashMap<String, String>() }

    // Il nome come lo vuole il server (contratto v3.5): senza spazi ai bordi, in
    // forma NFC, niente caratteri invisibili, unico anche coi cambi in attesa.
    val pulito = NomeSessione.pulito(nome)
    val problema = NomeSessione.problema(nome)
    val doppio = pulito.isNotEmpty() && altriNomi.any { NomeSessione.stesso(it, pulito) }
    val valida = problema == null && !doppio && scelte.size in 1..APP_MASSIME
    val cambiata = sessione == null || pulito != NomeSessione.pulito(partenzaNome) || scelte.toSet() != partenzaApp.toSet()
    val avvisoNome = when {
        problema == NomeSessione.Problema.TROPPO_LUNGO -> stringResource(R.string.sessione_nome_troppo_lungo)
        problema == NomeSessione.Problema.INVISIBILI -> stringResource(R.string.sessione_nome_invisibili)
        doppio -> stringResource(R.string.sessione_nome_doppio)
        else -> null
    }

    AlertDialog(
        onDismissRequest = onAnnulla,
        title = {
            Text(stringResource(if (sessione == null) R.string.sessione_crea_titolo else R.string.sessione_modifica_titolo))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spazi.m),
            ) {
                OutlinedTextField(
                    value = nome,
                    onValueChange = { nome = it.take(NomeSessione.MASSIMO * 2) },
                    label = { Text(stringResource(R.string.sessione_campo_nome)) },
                    // Validazione del campo: l'unico posto dove il rosso di sistema vale (§3.1).
                    isError = avvisoNome != null,
                    supportingText = avvisoNome?.let { { Text(it) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.sessione_app_titolo),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = if (scelte.isEmpty()) {
                        stringResource(R.string.sessione_app_nessuna)
                    } else {
                        elencoAppSessione(context, scelte, nomiNoti + etichette, massimo = 12)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (scelte.size > APP_MASSIME) {
                    Text(
                        text = stringResource(R.string.sessione_app_troppe),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedButton(onClick = { sceltaAperta = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.sessione_scegli_app))
                }
                Text(
                    text = stringResource(
                        if (sessione?.approvata == true) R.string.sessione_spiegazione_cambio else R.string.sessione_spiegazione_nuova,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                esito?.let { RigaStato(testoEsitoSessione(context, it)) }
            }
        },
        confirmButton = {
            Button(
                enabled = valida && cambiata && !invioInCorso,
                onClick = { onManda(pulito, scelte, nomiPerIlGenitore(context, scelte, etichette, nomiNoti)) },
            ) {
                Text(stringResource(R.string.sessione_manda))
            }
        },
        dismissButton = {
            TextButton(onClick = onAnnulla) { Text(stringResource(R.string.azione_annulla)) }
        },
    )

    if (sceltaAperta) {
        DialogoSceltaAppSessione(
            iniziali = scelte,
            nomiNoti = nomiNoti + etichette,
            onFatto = { nuove, nuoveEtichette ->
                scelte = nuove
                etichette.putAll(nuoveEtichette)
                sceltaAperta = false
            },
            onAnnulla = { sceltaAperta = false },
        )
    }
}

/**
 * I `nomi` da mandare con la sessione: solo il telefono sa come si chiamano
 * le app (il genitore vede i pacchetti). Mai un nome uguale al pacchetto;
 * fino a 100 caratteri, come vuole il contratto.
 */
private fun nomiPerIlGenitore(
    context: android.content.Context,
    app: List<String>,
    etichette: Map<String, String>,
    nomiNoti: Map<String, String>,
): Map<String, String> =
    app.mapNotNull { chiave ->
        val nome = if (chiave == AppDellaSessione.GRUPPO_APK) {
            context.getString(R.string.gruppo_apk_nome)
        } else {
            etichette[chiave] ?: CatalogoApp.etichettaValore(context, chiave).takeIf { it != chiave } ?: nomiNoti[chiave]
        }
        nome?.trim()?.takeIf { it.isNotEmpty() && it != chiave }?.let { chiave to primiCaratteri(it, ETICHETTA_MASSIMA) }
    }.toMap()

/** I primi [n] caratteri veri (un'emoji è uno, come li conta il server), mai metà di uno. */
private fun primiCaratteri(testo: String, n: Int): String =
    if (testo.codePointCount(0, testo.length) <= n) testo else testo.substring(0, testo.offsetByCodePoints(0, n))

private const val ETICHETTA_MASSIMA = 100

/**
 * La scelta delle app: in cima "App installate da APK" (`gruppo:apk`) con la
 * sua riga di spiegazione, poi le app installate (lo stesso elenco delle
 * regole), con la ricerca. Le app scelte e non più installate restano
 * nell'elenco, per poterle togliere.
 */
@Composable
internal fun DialogoSceltaAppSessione(
    iniziali: List<String>,
    nomiNoti: Map<String, String>,
    onFatto: (List<String>, Map<String, String>) -> Unit,
    onAnnulla: () -> Unit,
) {
    val context = LocalContext.current
    // PackageManager è lento: l'elenco si carica fuori dal thread principale, una volta.
    val installate by produceState<List<AppInstallata>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { CatalogoApp.appInstallate(context) }
    }
    var scelte by rememberSaveable(stateSaver = salvataggioLista) { mutableStateOf(iniziali) }
    var cerca by rememberSaveable { mutableStateOf("") }
    fun cambia(chiave: String) {
        scelte = if (chiave in scelte) scelte - chiave else scelte + chiave
    }
    val gruppoApk = stringResource(R.string.gruppo_apk_nome)
    val sconosciuta = stringResource(R.string.chiave_app_sconosciuta)
    val filtro = cerca.trim().lowercase()
    fun trovata(etichetta: String) = filtro.isEmpty() || etichetta.lowercase().contains(filtro)

    AlertDialog(
        onDismissRequest = onAnnulla,
        title = { Text(stringResource(R.string.sessione_scegli_app_titolo)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spazi.s)) {
                OutlinedTextField(
                    value = cerca,
                    onValueChange = { cerca = it },
                    label = { Text(stringResource(R.string.sessione_cerca)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                    if (trovata(gruppoApk)) {
                        item(key = AppDellaSessione.GRUPPO_APK) {
                            RigaSceltaApp(
                                nome = gruppoApk,
                                spiegazione = stringResource(R.string.gruppo_apk_spiegazione),
                                scelta = AppDellaSessione.GRUPPO_APK in scelte,
                            ) { cambia(AppDellaSessione.GRUPPO_APK) }
                        }
                    }
                    val lista = installate
                    // Scelte prima e non più installate: si vedono, per poterle togliere.
                    val pacchettiInstallati = lista?.map { it.pacchetto }?.toSet()
                    val mancanti = if (pacchettiInstallati == null) {
                        emptyList()
                    } else {
                        scelte.filter { it != AppDellaSessione.GRUPPO_APK && it !in pacchettiInstallati }
                    }
                    items(mancanti, key = { "mancante-$it" }) { chiave ->
                        val etichetta = nomiNoti[chiave]?.takeIf { it != chiave } ?: sconosciuta
                        if (trovata(etichetta)) {
                            RigaSceltaApp(nome = etichetta, spiegazione = null, scelta = chiave in scelte) { cambia(chiave) }
                        }
                    }
                    item { TitoloSezione(stringResource(R.string.regola_sezione_app)) }
                    if (lista == null) {
                        item {
                            Caricamento(modifier = Modifier.padding(vertical = Spazi.m), centrato = false)
                        }
                    } else {
                        val trovate = lista.filter { trovata(it.etichetta) }
                        if (trovate.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.sessione_nessuna_app_trovata),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = Spazi.m),
                                )
                            }
                        }
                        items(trovate, key = { it.pacchetto }) { installata ->
                            RigaSceltaApp(
                                nome = installata.etichetta,
                                spiegazione = null,
                                scelta = installata.pacchetto in scelte,
                            ) { cambia(installata.pacchetto) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val etichette = installate.orEmpty()
                        .filter { it.pacchetto in scelte }
                        .associate { it.pacchetto to it.etichetta }
                    onFatto(scelte, etichette)
                },
            ) {
                Text(stringResource(R.string.sessione_scelta_fatto))
            }
        },
        dismissButton = {
            TextButton(onClick = onAnnulla) { Text(stringResource(R.string.azione_annulla)) }
        },
    )
}

@Composable
private fun RigaSceltaApp(nome: String, spiegazione: String?, scelta: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = scelta, onValueChange = { onClick() }, role = Role.Checkbox)
            .padding(vertical = Spazi.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Il tocco è sulla riga intera: la casella mostra soltanto.
        Checkbox(checked = scelta, onCheckedChange = null, modifier = Modifier.padding(end = Spazi.m))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = nome, style = MaterialTheme.typography.bodyLarge)
            spiegazione?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * "Inizia": per quanto tempo (30 min, 1 h, 2 h, 3 h, o ore e minuti scritti,
 * fino a 24 ore), fino a che ora, e cosa vuol dire. Prima di iniziare servono
 * "Mostra sopra le altre app" e l'accesso ai dati di utilizzo: se mancano lo
 * si dice qui, con la strada per darli, e la sessione non parte.
 */
@Composable
private fun DialogoAvvio(
    sessione: SessioneDefinita,
    invioInCorso: Boolean,
    esito: EsitoAvvio?,
    onAnnulla: () -> Unit,
    onInizia: (Int) -> Unit,
) {
    val context = LocalContext.current
    val mostraSopra = rememberMostraSopra()
    val accessoUso = rememberAccessoUso()
    var scelta by rememberSaveable(sessione.id) { mutableIntStateOf(DurataSessione.SCELTE[1]) }
    var ore by rememberSaveable(sessione.id) { mutableStateOf("") }
    var altroAperto by rememberSaveable(sessione.id) { mutableStateOf(false) }
    var minuti by rememberSaveable(sessione.id) { mutableStateOf("") }
    val durata = if (scelta == DURATA_ALTRO) DurataSessione.daOreMinuti(ore, minuti) else scelta
    // "Fino alle …" segue l'orologio mentre la finestra è aperta.
    var adesso by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            adesso = System.currentTimeMillis()
        }
    }

    AlertDialog(
        onDismissRequest = onAnnulla,
        title = { Text(stringResource(R.string.sessione_avvio_titolo, nomeSessioneTraVirgolette(context, sessione.nome))) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spazi.m),
            ) {
                Text(
                    text = stringResource(R.string.sessione_avvio_quanto),
                    style = MaterialTheme.typography.titleSmall,
                )
                val voci = DurataSessione.SCELTE + DURATA_ALTRO
                voci.chunked(3).forEach { riga ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Spazi.xs)) {
                        riga.forEach { voce ->
                            FilterChip(
                                selected = scelta == voce,
                                onClick = { scelta = voce },
                                label = {
                                    Text(
                                        if (voce == DURATA_ALTRO) {
                                            stringResource(R.string.sessione_durata_altro)
                                        } else {
                                            testoDurata(voce.toLong())
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
                if (scelta == DURATA_ALTRO) {
                    val troppa = DurataSessione.oltreIlMassimo(ore, minuti)
                    Row(horizontalArrangement = Arrangement.spacedBy(Spazi.s)) {
                        CampoNumero(ore, { ore = it }, R.string.sessione_durata_ore, troppa, Modifier.weight(1f))
                        CampoNumero(minuti, { minuti = it }, R.string.sessione_durata_minuti, troppa, Modifier.weight(1f))
                    }
                    if (troppa) {
                        Text(
                            text = stringResource(R.string.sessione_durata_troppa),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (durata != null) {
                    Text(
                        text = testoFinoAlle(
                            context,
                            adesso + durata * 60_000L,
                            adesso,
                            R.string.sessione_fino_alle,
                            R.string.sessione_fino_a_domani,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Text(
                    text = stringResource(R.string.sessione_avvio_breve),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // (0.16) Cosa resta usabile: la riga corta sempre, il resto dietro "Altro".
                Text(
                    text = stringResource(R.string.sessione_avvio_sempre),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (altroAperto) {
                    Text(
                        text = stringResource(R.string.sessione_avvio_aperte),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    TextButton(onClick = { altroAperto = true }, contentPadding = PaddingValues(0.dp)) {
                        Text(stringResource(R.string.sessione_avvio_altro))
                    }
                }
                // Senza questi due permessi la sessione non parte: una riga ciascuno, con "Risolvi".
                if (!mostraSopra) {
                    RigaStato(
                        testo = stringResource(R.string.sessione_avvio_manca_permesso, stringResource(R.string.permesso_mostra_sopra)),
                        tono = Tono.Attenzione,
                        azione = stringResource(R.string.azione_risolvi),
                        onAzione = { PermessiHelper.apri(context, PermessiHelper.intentMostraSopra(context)) },
                    )
                }
                if (!accessoUso) {
                    RigaStato(
                        testo = stringResource(R.string.sessione_avvio_manca_permesso, stringResource(R.string.permesso_uso)),
                        tono = Tono.Attenzione,
                        azione = stringResource(R.string.azione_risolvi),
                        onAzione = { PermessiHelper.apri(context, PermessiHelper.intentAccessoUso()) },
                    )
                }
                esito?.let { RigaStato(testoEsitoAvvio(context, it)) }
            }
        },
        confirmButton = {
            Button(
                enabled = DurataSessione.valida(durata) && mostraSopra && accessoUso && !invioInCorso,
                onClick = { durata?.let(onInizia) },
            ) {
                Text(stringResource(R.string.sessione_avvia))
            }
        },
        dismissButton = {
            TextButton(onClick = onAnnulla) { Text(stringResource(R.string.azione_annulla)) }
        },
    )
}

@Composable
private fun CampoNumero(valore: String, onValore: (String) -> Unit, etichetta: Int, errore: Boolean, modifier: Modifier) {
    OutlinedTextField(
        value = valore,
        onValueChange = { nuovo -> onValore(nuovo.filter { it.isDigit() }.take(4)) },
        label = { Text(stringResource(etichetta)) },
        isError = errore,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

private const val APP_MASSIME = 200
