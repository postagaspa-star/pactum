package eu.stgm.pactum.figlio.ui

import eu.stgm.pactum.design.RigaStato
import eu.stgm.pactum.design.StatoVuoto
import eu.stgm.pactum.design.Caricamento
import eu.stgm.pactum.design.Pillola
import eu.stgm.pactum.design.CardNormale
import eu.stgm.pactum.design.VoceMenu
import eu.stgm.pactum.design.MenuAzioni
import eu.stgm.pactum.design.RigaToccabile
import eu.stgm.pactum.design.FilaPulsanti
import eu.stgm.pactum.design.Tono
import eu.stgm.pactum.design.TitoloSezione
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import eu.stgm.pactum.figlio.dati.Dichiarazione
import eu.stgm.pactum.figlio.dati.EsitiDichiarazione
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.design.GiornoPatto
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.design.StrisciaGiorni
import eu.stgm.pactum.design.contaGiorni
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.catalogo.AppInstallata
import eu.stgm.pactum.figlio.catalogo.CatalogoApp
import eu.stgm.pactum.figlio.dati.BloccoCambio
import eu.stgm.pactum.figlio.dati.CambioRegola
import eu.stgm.pactum.figlio.dati.EsitoProposta
import eu.stgm.pactum.figlio.dati.Proposta
import eu.stgm.pactum.figlio.dati.ProposteDelFiglio
import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.TipiRegola
import eu.stgm.pactum.figlio.dati.inGiorniPatto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/**
 * (0.15) La scheda Regole. In cima, solo se ci sono, le proposte del genitore
 * da decidere ("Da decidere"); poi una card compatta per regola, col menu ⋯
 * (Modifica · Proponi al genitore, o Ritira la proposta · Elimina) e, per la
 * vita reale, "Ce l'ho fatta / Non ce l'ho fatta"; in fondo lo Storico. La
 * logica è quella di sempre: RegoleViewModel per le regole, ProposteViewModel
 * per le proposte, DichiarazioniViewModel per le dichiarazioni.
 * [richiestaInCima] cresce quando si arriva da una notifica di proposta: la
 * lista torna in cima, dove sta "Da decidere".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegoleScreen(
    onApriImpostazioni: () -> Unit = {},
    onApriStorico: () -> Unit = {},
    richiestaInCima: Int = 0,
    vm: RegoleViewModel = viewModel(),
    proposteVm: ProposteViewModel = viewModel(),
    dichiarazioniVm: DichiarazioniViewModel = viewModel(),
) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    val statoProposte by proposteVm.stato.collectAsStateWithLifecycle()
    val statoDiario by dichiarazioniVm.stato.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    // Le snackbar delle proposte partono qui, fuori dall'effetto degli eventi:
    // l'evento si consuma subito.
    val ambito = rememberCoroutineScope()
    val lista = rememberLazyListState()

    // I dialoghi aperti si ricordano per id della regola (e il blocco come
    // testo): una rotazione o la morte del processo non li chiudono, e non
    // buttano quello che il ragazzo ha scritto (0.10). Per la modifica: null =
    // nessun dialogo, NUOVA_REGOLA = creazione, altrimenti la regola.
    var dialogoRegolaId by rememberSaveable { mutableStateOf<Long?>(null) }
    var regolaDaEliminareId by rememberSaveable { mutableStateOf<Long?>(null) }
    // (0.10) La regola su cui si sta scrivendo una proposta al genitore.
    var regolaDaProporreId by rememberSaveable { mutableStateOf<Long?>(null) }
    // (0.10) Il cambio fermato dal blocco dei 4 giorni (BloccoCambio.inTesto).
    var bloccoTesto by rememberSaveable { mutableStateOf<String?>(null) }
    // (0.15) La proposta del figlio da ritirare ("Ritira la proposta" nel ⋯).
    var daRitirareId by rememberSaveable { mutableStateOf<Long?>(null) }
    // L'ultima copia di ogni regola (e proposta) aperta in un dialogo: se intanto
    // sparisce dal patto (eliminata, rilettura) il dialogo resta e dice perché.
    val viste = remember { HashMap<Long, Regola>() }
    val proposteViste = remember { HashMap<Long, Proposta>() }
    fun regolaAperta(id: Long?): Regola? =
        id?.let { cercata -> stato.regole.firstOrNull { it.id == cercata } ?: viste[cercata] }
    fun ricorda(regola: Regola) {
        viste[regola.id] = regola
    }
    val daRitirare = daRitirareId?.let { id ->
        (statoProposte.inviate + stato.proposteInAttesa.values).firstOrNull { it.id == id } ?: proposteViste[id]
    }

    // (0.10) Chiuso il modulo della proposta (o il blocco), il suo esito non si dice più.
    fun chiudiProposta() {
        regolaDaProporreId = null
        vm.dimenticaEsitoProposta()
    }
    fun chiudiBlocco() {
        bloccoTesto = null
        vm.dimenticaEsitoProposta()
    }

    val conVitaReale = stato.regole.any { it.tipo == TipiRegola.VITA_REALE }
    LifecycleResumeEffect(Unit) {
        vm.aggiorna()
        proposteVm.aggiorna()
        onPauseOrDispose { }
    }
    LifecycleResumeEffect(conVitaReale) {
        if (conVitaReale) dichiarazioniVm.aggiorna()
        onPauseOrDispose { }
    }

    // Da una notifica di proposta: in cima, dove sta "Da decidere". Una volta
    // per richiesta, non a ogni ritorno sulla scheda.
    var inCimaFatta by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(richiestaInCima) {
        if (richiestaInCima != inCimaFatta) {
            inCimaFatta = richiestaInCima
            lista.scrollToItem(0)
        }
    }

    val messaggioSalvata = stringResource(R.string.regola_salvata)
    val messaggioEliminata = stringResource(R.string.regola_eliminata_ok)
    val messaggioUltima = stringResource(R.string.regola_ultima_messaggio)
    val messaggioErrore = stringResource(R.string.regola_errore_generico)
    LaunchedEffect(stato.evento) {
        when (val evento = stato.evento) {
            is RegoleViewModel.Evento.Salvata -> {
                dialogoRegolaId = null
                snackbarHostState.showSnackbar(messaggioSalvata)
            }
            is RegoleViewModel.Evento.Eliminata -> {
                regolaDaEliminareId = null
                snackbarHostState.showSnackbar(messaggioEliminata)
            }
            is RegoleViewModel.Evento.LockAttivo -> {
                regolaDaEliminareId = null
                // (0.10) L'attesa resta quella di prima, ma in un dialogo che offre
                // anche di chiederlo al genitore. Si apre sempre: il server guarda
                // "l'ultima regola" prima del blocco, quindi qui le regole sono almeno due.
                vm.dimenticaEsitoProposta()
                bloccoTesto = BloccoCambio(
                    evento.cambio,
                    sbloccoAlle = System.currentTimeMillis() + evento.secondiRimanenti * 1000,
                ).inTesto()
            }
            is RegoleViewModel.Evento.UltimaRegola -> {
                regolaDaEliminareId = null
                snackbarHostState.showSnackbar(messaggioUltima)
            }
            is RegoleViewModel.Evento.Errore -> snackbarHostState.showSnackbar(messaggioErrore)
            is RegoleViewModel.Evento.PropostaAlGenitore -> {
                val esito = evento.esito
                val moduloAperto = regolaDaProporreId != null || bloccoTesto != null
                vm.consumaEvento()
                if (ProposteDelFiglio.chiudeIlModulo(esito)) {
                    // Arrivata: si chiude tutto (anche la modifica sotto il blocco).
                    bloccoTesto = null
                    regolaDaProporreId = null
                    dialogoRegolaId = null
                } else if (moduloAperto) {
                    // Il resto lo dice il modulo aperto, dove il ragazzo guarda
                    // (stato.esitoProposta), non una snackbar sotto l'ombra.
                    return@LaunchedEffect
                }
                // La proposta ora sta sulla card della regola: la si rilegge anche qui.
                if (ProposteDelFiglio.serveRileggere(esito)) proposteVm.aggiorna()
                val messaggio = testoEsitoProposta(context, esito)
                ambito.launch { snackbarHostState.showSnackbar(messaggio, duration = SnackbarDuration.Long) }
                return@LaunchedEffect
            }
            null -> Unit
        }
        if (stato.evento != null) vm.consumaEvento()
    }

    // Gli esiti delle risposte alle proposte del genitore e dei ritiri (prima
    // nella scheda Proposte): la regola può essere cambiata, si rilegge.
    val messaggioAccettata = stringResource(R.string.proposta_accettata_ok)
    val messaggioRifiutata = stringResource(R.string.proposta_rifiutata_ok)
    val messaggioNonPendente = stringResource(R.string.proposta_non_pendente)
    val messaggioErroreRisposta = stringResource(R.string.proposta_errore)
    LaunchedEffect(statoProposte.evento) {
        val evento = statoProposte.evento ?: return@LaunchedEffect
        proposteVm.consumaEvento()
        val messaggio = when (evento) {
            is ProposteViewModel.Evento.Accettata -> {
                vm.aggiorna()
                // (v3) Accettata da qui una proposta su un altro dispositivo: si dice quale.
                statoProposte.regole.firstOrNull { it.id == evento.regolaId }
                    ?.let { TestoDispositivi.etichetta(it, statoProposte.contesto, paroleDispositivo(context)) }
                    ?.let { context.getString(R.string.proposta_accettata_ok_su, it) }
                    ?: messaggioAccettata
            }
            ProposteViewModel.Evento.Rifiutata -> messaggioRifiutata
            ProposteViewModel.Evento.NonPiuPendente -> messaggioNonPendente
            ProposteViewModel.Evento.Errore -> messaggioErroreRisposta
            is ProposteViewModel.Evento.PropostaMandata -> testoEsitoProposta(context, evento.esito)
            is ProposteViewModel.Evento.Ritiro -> {
                daRitirareId = null
                vm.aggiorna()
                testoRitiro(context, evento.esito)
            }
        }
        ambito.launch { snackbarHostState.showSnackbar(messaggio, duration = SnackbarDuration.Long) }
    }

    // (0.15) "Ce l'ho fatta / Non ce l'ho fatta" sulle regole di vita reale.
    val dichiarare = rememberDichiarare(
        vm = dichiarazioniVm,
        regole = stato.regole.filter { it.tipo == TipiRegola.VITA_REALE },
        snackbarHostState = snackbarHostState,
    )
    val oggiPatto = oggiDelPatto(statoDiario.fuso)

    // Il pulsante "Nuova regola" non copre mai l'ultima card: lo spazio in
    // fondo è la sua altezza misurata, più il margine.
    val densita = LocalDensity.current
    var altezzaPulsante by remember { mutableIntStateOf(0) }
    val spazioInFondo = with(densita) { altezzaPulsante.toDp() } + Spazi.l * 2

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.regole_titolo)) },
                actions = {
                    AzioniBarra(
                        onAggiorna = {
                            vm.aggiorna()
                            proposteVm.aggiorna()
                        },
                        onApriImpostazioni = onApriImpostazioni,
                    )
                },
            )
        },
        floatingActionButton = {
            if (!stato.configurazioneMancante) {
                ExtendedFloatingActionButton(
                    onClick = { dialogoRegolaId = NUOVA_REGOLA },
                    // La scritta del pulsante allungato non arriva a TalkBack (Material la
                    // nasconde): la dice l'icona.
                    icon = { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.regole_nuova)) },
                    text = { Text(stringResource(R.string.regole_nuova), maxLines = 1) },
                    modifier = Modifier.onSizeChanged { altezzaPulsante = it.height },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                stato.caricamento && stato.regole.isEmpty() ->
                    Caricamento(testo = stringResource(R.string.regole_caricamento))

                stato.configurazioneMancante ->
                    StatoVuoto(stringResource(R.string.regole_config_mancante), centrato = true, modifier = Modifier.padding(Spazi.xl))

                else -> LazyColumn(
                    state = lista,
                    // (0.15) La "motivazione" di una proposta da decidere resta sopra
                    // la tastiera (la barra in basso è già tolta dallo Scaffold fuori).
                    modifier = Modifier.fillMaxSize().imePadding(),
                    // Densità del figlio: 20 attorno, 16 tra i blocchi; in fondo lo
                    // spazio misurato del pulsante, perché non copra l'ultima regola.
                    contentPadding = PaddingValues(
                        start = Spazi.l + Spazi.xs,
                        end = Spazi.l + Spazi.xs,
                        top = Spazi.l + Spazi.xs,
                        bottom = spazioInFondo,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spazi.l),
                ) {
                    if (stato.errore) {
                        item(key = "dati-fermi") { RigaStato(testoDatiVecchi(stato.datiFermiAlle)) }
                    }

                    // In cima, solo se ci sono: le proposte del genitore da decidere.
                    val daDecidere = statoProposte.daDecidere
                    if (daDecidere.isNotEmpty()) {
                        item(key = "da-decidere") { TitoloSezione(stringResource(R.string.proposte_sezione_pendenti)) }
                        items(daDecidere, key = { "pendente-${it.id}" }) { proposta ->
                            CardPropostaPendente(
                                proposta = proposta,
                                regole = statoProposte.regole,
                                contesto = statoProposte.contesto,
                                invioInCorso = statoProposte.invioInCorso,
                                onRispondi = { id, esito, motivazione -> proposteVm.rispondi(id, esito, motivazione) },
                            )
                        }
                        item(key = "le-tue-regole") { TitoloSezione(stringResource(R.string.regole_sezione_tue)) }
                    }

                    if (stato.regole.isEmpty()) {
                        // (v3) Il figlio può avere regole solo sul computer: il patto
                        // c'è, su questo telefono no.
                        val vuoto = if (stato.regoleAltrove > 0) {
                            R.string.regole_vuoto_questo_telefono
                        } else {
                            R.string.regole_vuoto
                        }
                        item(key = "vuoto") { StatoVuoto(stringResource(vuoto)) }
                    } else {
                        items(stato.regole, key = { it.id }) { regola ->
                            CardRegola(
                                regola = regola,
                                concordata = regola.id in stato.concordate,
                                inAttesa = stato.proposteInAttesa[regola.id],
                                // "L'ultima non si toglie" vale per il figlio, su tutti i
                                // suoi dispositivi (contratto v3): conta anche il computer.
                                eliminabile = stato.totaleFiglio > 1,
                                diOggi = if (regola.tipo == TipiRegola.VITA_REALE) {
                                    dichiarazioneDiOggi(regola, statoDiario.tutte, oggiPatto)
                                } else {
                                    null
                                },
                                onModifica = {
                                    ricorda(regola)
                                    dialogoRegolaId = regola.id
                                },
                                onElimina = {
                                    ricorda(regola)
                                    regolaDaEliminareId = regola.id
                                },
                                onProponi = {
                                    ricorda(regola)
                                    vm.dimenticaEsitoProposta()
                                    regolaDaProporreId = regola.id
                                },
                                onRitira = { proposta ->
                                    proposteViste[proposta.id] = proposta
                                    daRitirareId = proposta.id
                                },
                                onDichiara = { esito -> dichiarare.apri(regola, esito) },
                            )
                        }
                    }

                    // Le proposte del figlio su regole che qui non ci sono (di un
                    // altro dispositivo): restano visibili, e si possono ritirare.
                    val qui = stato.regole.map { it.id }.toSet()
                    val altrove = statoProposte.inviate.filter { it.regolaId !in qui }
                    if (altrove.isNotEmpty()) {
                        item(key = "tue-altrove") { TitoloSezione(stringResource(R.string.proposte_sezione_tue)) }
                        items(altrove, key = { "tua-${it.id}" }) { proposta ->
                            CardPropostaTua(proposta, statoProposte.regole, statoProposte.contesto, statoProposte.invioInCorso) {
                                proposteViste[proposta.id] = proposta
                                daRitirareId = proposta.id
                            }
                        }
                    }

                    // (v3) Qui ci sono le regole di questo telefono e la vita reale;
                    // quelle degli altri dispositivi si vedono e si cambiano da lì.
                    if (stato.regoleAltrove > 0) {
                        item(key = "altrove") {
                            Text(
                                text = pluralStringResource(
                                    R.plurals.regole_altri_dispositivi,
                                    stato.regoleAltrove,
                                    stato.regoleAltrove,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item(key = "storico") {
                        RigaToccabile(titolo = stringResource(R.string.regole_storico), onClick = onApriStorico)
                    }
                }
            }
        }
    }

    // Creazione (NUOVA_REGOLA) o modifica di una regola: quella della modifica
    // si mostra solo quando la si ritrova (dopo la morte del processo, a
    // rilettura finita).
    dialogoRegolaId?.let { id ->
        val nuova = id == NUOVA_REGOLA
        val regola = if (nuova) null else regolaAperta(id)
        if (nuova || regola != null) {
            DialogoRegola(
                regola = regola,
                invioInCorso = stato.invioInCorso,
                onAnnulla = { dialogoRegolaId = null },
                onSalva = { tipo, parametri ->
                    if (regola == null) vm.crea(tipo, parametri) else vm.modifica(regola.id, parametri)
                },
            )
        }
    }

    regolaAperta(regolaDaEliminareId)?.let { regola ->
        AlertDialog(
            onDismissRequest = { regolaDaEliminareId = null },
            title = { Text(stringResource(R.string.regola_elimina_conferma_titolo)) },
            text = {
                Text(
                    stringResource(
                        R.string.regola_elimina_conferma_testo,
                        descrizioneRegola(regola.tipo, regola.parametri, leggibile = true),
                    ),
                )
            },
            confirmButton = {
                Button(
                    enabled = !stato.invioInCorso,
                    onClick = { vm.elimina(regola.id) },
                ) {
                    Text(stringResource(R.string.azione_elimina))
                }
            },
            dismissButton = {
                TextButton(onClick = { regolaDaEliminareId = null }) {
                    Text(stringResource(R.string.azione_annulla))
                }
            },
        )
    }

    regolaAperta(regolaDaProporreId)?.let { regola ->
        DialogoProposta(
            regola = regola,
            invioInCorso = stato.invioInCorso,
            // Eliminare l'ultima regola del patto non si può, nemmeno d'accordo.
            eliminabile = stato.totaleFiglio > 1,
            esito = stato.esitoProposta,
            onAnnulla = { chiudiProposta() },
            onManda = { cambio, perche -> vm.proponi(cambio, perche) },
        )
    }

    // Sopra al modulo della modifica, che resta aperto sotto: chiuso il
    // blocco, si torna lì e si può ancora stringere invece di allentare.
    BloccoCambio.daTesto(bloccoTesto)?.let { blocco ->
        DialogoBlocco(
            blocco = blocco,
            invioInCorso = stato.invioInCorso,
            esito = stato.esitoProposta,
            onChiudi = { chiudiBlocco() },
            onChiedi = { perche -> vm.proponi(blocco.cambio, perche) },
        )
    }

    // (0.10) "Ritirare la proposta?": la regola resta com'è.
    daRitirare?.let { proposta ->
        AlertDialog(
            onDismissRequest = { daRitirareId = null },
            title = { Text(stringResource(R.string.proposta_ritira_titolo)) },
            text = { Text(stringResource(R.string.proposta_ritira_testo)) },
            confirmButton = {
                Button(enabled = !statoProposte.invioInCorso, onClick = { proposteVm.ritira(proposta.id) }) {
                    Text(stringResource(R.string.proposta_ritira))
                }
            },
            dismissButton = {
                TextButton(onClick = { daRitirareId = null }) { Text(stringResource(R.string.azione_annulla)) }
            },
        )
    }
}

/** Il dialogo della modifica aperto su una regola nuova (creazione). */
private const val NUOVA_REGOLA = -1L

/**
 * (0.15) Una regola, compatta: il tipo, la descrizione, "Allentabile dal …",
 * la striscia piccola, la proposta che aspetta (in una pillola e una riga) e
 * il menu ⋯ con Modifica · Proponi al genitore (o Ritira la proposta) ·
 * Elimina (non sull'unica regola del patto). Le regole di vita reale hanno in
 * più "Ce l'ho fatta / Non ce l'ho fatta", o com'è andata oggi.
 */
@Composable
private fun CardRegola(
    regola: Regola,
    concordata: Boolean,
    inAttesa: Proposta?,
    eliminabile: Boolean,
    diOggi: Dichiarazione?,
    onModifica: () -> Unit,
    onElimina: () -> Unit,
    onProponi: () -> Unit,
    onRitira: (Proposta) -> Unit,
    onDichiara: (String) -> Unit,
) {
    val voci = buildList {
        add(VoceMenu(stringResource(R.string.azione_modifica), onModifica))
        when {
            // Su questa regola aspetta già una proposta: una seconda il server la rifiuterebbe.
            inAttesa == null -> add(VoceMenu(stringResource(R.string.regola_proponi), onProponi))
            inAttesa.delFiglio -> add(VoceMenu(stringResource(R.string.regola_ritira_proposta), { onRitira(inAttesa) }))
        }
        if (eliminabile) add(VoceMenu(stringResource(R.string.azione_elimina), onElimina, distruttiva = true))
    }
    CardNormale {
        Column(verticalArrangement = Arrangement.spacedBy(Spazi.s)) {
            // Il tipo e la regola a sinistra, il ⋯ a destra: la card resta bassa.
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spazi.xs)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spazi.s)) {
                        Text(
                            text = etichettaTipoRegola(regola.tipo),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (concordata) Pillola(stringResource(R.string.regola_concordata), tono = Tono.Positivo)
                    }
                    Text(
                        // (0.10) Mai il nome di un pacchetto, anche dopo un cambio di bersaglio.
                        text = descrizioneRegola(regola.tipo, regola.parametri, leggibile = true),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                MenuAzioni(voci = voci, descrizione = stringResource(R.string.regola_menu))
            }
            // Il lock asimmetrico, in chiaro: quando la regola tornerà allentabile.
            istanteServer(regola.allentabileDal)
                ?.takeIf { it.isAfter(Instant.now()) }
                ?.let {
                    Text(
                        text = stringResource(R.string.regola_allentabile_dal, dataOraLocale(it)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            // (v2.4) Gli 8 giorni di QUESTA regola, la stessa striscia piccola che
            // il genitore vede sulla sua scheda (D3). Server vecchio: niente.
            val giorni = remember(regola.semaforo) { regola.semaforo.inGiorniPatto() }
            if (giorni.isNotEmpty()) {
                StrisciaGiorni(
                    giorni = giorni,
                    lato = 20.dp,
                    mostraNumero = false,
                    descrizione = descrizioneStrisciaRegola(giorni),
                )
            }
            // (0.10) La proposta che aspetta su questa regola, di chiunque sia.
            if (inAttesa != null) {
                Pillola(
                    if (inAttesa.delFiglio) {
                        stringResource(R.string.regola_pillola_tua)
                    } else {
                        conNomeGenitore(LocalContext.current, inAttesa.nomeGenitore, R.string.regola_pillola_genitore, R.string.regola_pillola_genitore_nome)
                    },
                    tono = Tono.Attenzione,
                )
                Text(
                    text = testoPropostaInAttesa(inAttesa),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // (0.15) Vita reale: si dichiara da qui, o si vede com'è andata oggi.
            if (regola.tipo == TipiRegola.VITA_REALE) {
                if (diOggi != null) {
                    Text(
                        text = descrizioneStato(diOggi, regola),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    FilaPulsanti {
                        Button(onClick = { onDichiara(EsitiDichiarazione.SUCCESSO) }) {
                            Text(stringResource(R.string.dichiara_successo), maxLines = 1)
                        }
                        OutlinedButton(onClick = { onDichiara(EsitiDichiarazione.FALLIMENTO) }) {
                            Text(stringResource(R.string.dichiara_fallimento), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/**
 * (0.10) La proposta che aspetta su una regola, in una riga: il confronto del
 * server ("−30 min al giorno rispetto ad ora"), o per un'eliminazione chi l'ha
 * chiesta. Il confronto di un'eliminazione è scritto per il genitore.
 */
@Composable
private fun testoPropostaInAttesa(proposta: Proposta): String {
    val eliminazione = TestoProposta.eliminazione(proposta.direzione, proposta.parametriProposti)
    val confronto = proposta.confronto?.trim()?.ifEmpty { null }
        ?: stringResource(R.string.proposta_senza_confronto)
    return when {
        proposta.delFiglio && eliminazione -> stringResource(R.string.regola_proposta_tua_elimina)
        eliminazione -> stringResource(R.string.regola_proposta_genitore_elimina)
        else -> confronto
    }
}

/** Quello che TalkBack legge al posto dei quadretti: "6 su 7 giorni dentro questa regola". */
@Composable
private fun descrizioneStrisciaRegola(giorni: List<GiornoPatto>): String {
    val (mantenuti, conDati) = contaGiorni(giorni)
    return if (conDati == 0) {
        stringResource(R.string.oggi_striscia_senza_dati)
    } else {
        pluralStringResource(R.plurals.regola_striscia_frase, conDati, mantenuti, conDati)
    }
}

@Composable
private fun etichettaTipoRegola(tipo: String): String = when (tipo) {
    TipiRegola.LIMITE_TEMPO -> stringResource(R.string.regola_tipo_limite_tempo)
    TipiRegola.FASCIA_ORARIA -> stringResource(R.string.regola_tipo_fascia_oraria)
    TipiRegola.VITA_REALE -> stringResource(R.string.regola_tipo_vita_reale)
    else -> tipo
}

/**
 * I campi di una regola, partendo dai valori di [regola] (vuoti per una
 * nuova). (0.10) Lo stesso modulo serve a creare, a modificare e a proporre
 * al genitore: stessi selettori, stessi controlli (ParametriRegola), mai testo
 * libero per l'app o la categoria.
 */
@Stable
private class ModuloRegola(
    tipo: String,
    app: String = "",
    minuti: String = "",
    dalle: String = "",
    alle: String = "",
    giorni: Set<String> = emptySet(),
    descrizione: String = "",
    arbitro: String = "",
    frequenza: String = "",
) {
    var tipo by mutableStateOf(tipo)
    var app by mutableStateOf(app)
    var minuti by mutableStateOf(minuti)
    var dalle by mutableStateOf(dalle)
    var alle by mutableStateOf(alle)
    var giorni by mutableStateOf(giorni)
    var descrizione by mutableStateOf(descrizione)
    var arbitro by mutableStateOf(arbitro)
    var frequenza by mutableStateOf(frequenza)

    /** I parametri pronti da mandare, null se qualche campo non va: il pulsante resta spento. */
    val parametri: JsonObject?
        get() = ParametriRegola.daCampi(tipo, app, minuti, dalle, alle, giorni, descrizione, arbitro, frequenza)

    /** (0.10) Più minuti di un giorno: il campo lo dice, non solo il pulsante spento. */
    val minutiTroppi: Boolean
        get() = tipo == TipiRegola.LIMITE_TEMPO && ParametriRegola.minutiOltreIlGiorno(minuti)

    fun cambiaGiorno(giorno: String) {
        giorni = if (giorno in giorni) giorni - giorno else giorni + giorno
    }

    companion object {
        /** Il modulo coi valori di [regola]; vuoto (limite di tempo) per una regola nuova. */
        fun da(regola: Regola?): ModuloRegola {
            val iniziali = regola?.parametri ?: JsonObject(emptyMap())
            return ModuloRegola(
                tipo = regola?.tipo ?: TipiRegola.LIMITE_TEMPO,
                app = parametroTesto(iniziali, "app_o_categoria") ?: "",
                minuti = parametroTesto(iniziali, "minuti_al_giorno") ?: "",
                dalle = parametroTesto(iniziali, "dalle") ?: "",
                alle = parametroTesto(iniziali, "alle") ?: "",
                giorni = (iniziali["giorni"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
                    ?.toSet()
                    ?: emptySet(),
                descrizione = parametroTesto(iniziali, "descrizione") ?: "",
                arbitro = parametroTesto(iniziali, "arbitro_nome") ?: "",
                frequenza = parametroTesto(iniziali, "frequenza") ?: "",
            )
        }

        /**
         * (0.10) Quello che il ragazzo ha scritto resta attraverso una
         * rotazione o la morte del processo: tutti i campi come testo.
         */
        val Salvataggio: Saver<ModuloRegola, Any> = listSaver(
            save = {
                listOf(
                    it.tipo, it.app, it.minuti, it.dalle, it.alle, it.giorni.joinToString(","),
                    it.descrizione, it.arbitro, it.frequenza,
                )
            },
            restore = { valori ->
                ModuloRegola(
                    tipo = valori[0],
                    app = valori[1],
                    minuti = valori[2],
                    dalle = valori[3],
                    alle = valori[4],
                    giorni = valori[5].split(',').filter { it.isNotBlank() }.toSet(),
                    descrizione = valori[6],
                    arbitro = valori[7],
                    frequenza = valori[8],
                )
            },
        )
    }
}

/**
 * I campi del [modulo], dentro la colonna di un dialogo. [sceltaTipo] = si
 * sceglie anche il tipo: solo creando (il contratto non prevede di cambiarlo).
 */
@Composable
private fun CampiRegola(modulo: ModuloRegola, sceltaTipo: Boolean) {
    if (sceltaTipo) {
        Column {
            RigaRadio(
                selezionato = modulo.tipo == TipiRegola.LIMITE_TEMPO,
                testo = stringResource(R.string.regola_tipo_limite_tempo),
                onClick = { modulo.tipo = TipiRegola.LIMITE_TEMPO },
            )
            RigaRadio(
                selezionato = modulo.tipo == TipiRegola.FASCIA_ORARIA,
                testo = stringResource(R.string.regola_tipo_fascia_oraria),
                onClick = { modulo.tipo = TipiRegola.FASCIA_ORARIA },
            )
            RigaRadio(
                selezionato = modulo.tipo == TipiRegola.VITA_REALE,
                testo = stringResource(R.string.regola_tipo_vita_reale),
                onClick = { modulo.tipo = TipiRegola.VITA_REALE },
            )
        }
    }

    when (modulo.tipo) {
        TipiRegola.LIMITE_TEMPO -> {
            SelettoreAppOCategoria(valore = modulo.app, onScegli = { modulo.app = it })
            CampoTesto(
                modulo.minuti, { modulo.minuti = it }, R.string.regola_campo_minuti,
                numerico = true,
                // (0.10) Il server accetta al massimo 1440 minuti: lo si dice sul campo.
                avviso = if (modulo.minutiTroppi) stringResource(R.string.regola_minuti_troppi) else null,
            )
        }
        TipiRegola.FASCIA_ORARIA -> {
            CampoTesto(modulo.dalle, { modulo.dalle = it }, R.string.regola_campo_dalle)
            CampoTesto(modulo.alle, { modulo.alle = it }, R.string.regola_campo_alle)
            Text(
                text = stringResource(R.string.regola_campo_giorni),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spazi.xs)) {
                ParametriRegola.GIORNI.take(4).forEach { giorno ->
                    ChipGiorno(giorno, giorno in modulo.giorni) { modulo.cambiaGiorno(giorno) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spazi.xs)) {
                ParametriRegola.GIORNI.drop(4).forEach { giorno ->
                    ChipGiorno(giorno, giorno in modulo.giorni) { modulo.cambiaGiorno(giorno) }
                }
            }
        }
        TipiRegola.VITA_REALE -> {
            CampoTesto(modulo.descrizione, { modulo.descrizione = it }, R.string.regola_campo_descrizione)
            CampoTesto(modulo.arbitro, { modulo.arbitro = it }, R.string.regola_campo_arbitro)
            CampoTesto(modulo.frequenza, { modulo.frequenza = it }, R.string.regola_campo_frequenza)
        }
    }
}

/**
 * Creazione o modifica di una regola. In creazione si sceglie il tipo; in
 * modifica il tipo è fisso (il contratto non prevede di cambiarlo). Il
 * pulsante Salva si accende solo con parametri validi: un 422 evitabile è
 * un errore in meno da spiegare.
 */
@Composable
private fun DialogoRegola(
    regola: Regola?,
    invioInCorso: Boolean,
    onAnnulla: () -> Unit,
    onSalva: (String, JsonObject) -> Unit,
) {
    // (0.10) Salvato: una rotazione non butta quello che si sta scrivendo.
    val modulo = rememberSaveable(regola?.id, saver = ModuloRegola.Salvataggio) { ModuloRegola.da(regola) }
    val parametri = modulo.parametri

    AlertDialog(
        onDismissRequest = onAnnulla,
        title = {
            Text(
                stringResource(
                    if (regola == null) R.string.regola_crea_titolo else R.string.regola_modifica_titolo,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spazi.m),
            ) {
                CampiRegola(modulo, sceltaTipo = regola == null)
            }
        },
        confirmButton = {
            Button(
                enabled = parametri != null && !invioInCorso,
                onClick = { parametri?.let { onSalva(modulo.tipo, it) } },
            ) {
                Text(stringResource(R.string.azione_salva))
            }
        },
        dismissButton = {
            TextButton(onClick = onAnnulla) { Text(stringResource(R.string.azione_annulla)) }
        },
    )
}

/**
 * (0.10) "Proponi al genitore" (contratto v3.4): lo stesso modulo della
 * modifica, coi valori di adesso, più il perché facoltativo. Da qui si può
 * anche proporre di eliminarla ([eliminabile]: non l'unica regola del patto).
 * Se il genitore accetta, il server applica il cambio subito, anche se
 * allenta. (0.15) Si apre dal ⋯ di una regola, nella scheda Regole.
 *
 * [esito] = com'è andato l'ultimo invio, se non è arrivato: si dice qui
 * dentro. Il pulsante resta acceso solo quando rimandare ha senso.
 */
@Composable
private fun DialogoProposta(
    regola: Regola,
    invioInCorso: Boolean,
    eliminabile: Boolean,
    esito: EsitoProposta?,
    onAnnulla: () -> Unit,
    onManda: (CambioRegola, String?) -> Unit,
) {
    val modulo = rememberSaveable(regola.id, saver = ModuloRegola.Salvataggio) { ModuloRegola.da(regola) }
    var perche by rememberSaveable(regola.id) { mutableStateOf("") }
    var eliminazione by rememberSaveable(regola.id) { mutableStateOf(false) }
    // Il punto di partenza è il modulo stesso appena aperto, non i parametri
    // grezzi: i giorni di una fascia, per esempio, il modulo li rimette in ordine.
    val partenza = remember(regola.id) { ModuloRegola.da(regola).parametri }
    val parametri = modulo.parametri
    // Una proposta uguale alla regola di adesso non chiede niente: il pulsante resta spento.
    val cambio: CambioRegola? = when {
        eliminazione -> CambioRegola.Eliminazione(regola.id)
        parametri != null && parametri != partenza -> CambioRegola.Modifica(regola.id, parametri)
        else -> null
    }
    val adesso = descrizioneRegola(regola.tipo, regola.parametri, leggibile = true)

    AlertDialog(
        onDismissRequest = onAnnulla,
        title = {
            Text(stringResource(if (eliminazione) R.string.proponi_elimina else R.string.proponi_titolo))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spazi.m),
            ) {
                if (eliminazione) {
                    Text(
                        text = stringResource(R.string.proponi_elimina_testo, adesso),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.proposta_regola_ora, adesso),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CampiRegola(modulo, sceltaTipo = false)
                }
                CampoPerche(perche) { perche = it }
                Text(
                    text = stringResource(
                        if (eliminazione) R.string.proponi_elimina_spiegazione else R.string.proponi_spiegazione,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!eliminazione && eliminabile) {
                    TextButton(
                        onClick = { eliminazione = true },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(stringResource(R.string.proponi_elimina))
                    }
                }
                esito?.let { RigaEsitoProposta(it) }
            }
        },
        confirmButton = {
            Button(
                enabled = cambio != null && !invioInCorso && (esito == null || ProposteDelFiglio.riprovabile(esito)),
                onClick = { cambio?.let { onManda(it, perche) } },
            ) {
                Text(stringResource(R.string.proponi_manda))
            }
        },
        dismissButton = {
            // Dall'eliminazione si torna al modulo; dal modulo si chiude.
            TextButton(onClick = { if (eliminazione) eliminazione = false else onAnnulla() }) {
                Text(stringResource(if (eliminazione) R.string.azione_indietro else R.string.azione_annulla))
            }
        },
    )
}

/**
 * (0.10) Il blocco dei 4 giorni ha fermato un cambio che allenta (o
 * un'eliminazione). Resta l'attesa di sempre, "potrai allentarla tra…", e in
 * più "Chiedi al genitore": se accetta, vale subito. Manda ESATTAMENTE il
 * cambio fermato ([blocco].cambio), col perché se c'è. [esito] = com'è
 * andato l'ultimo invio, se non è arrivato: si dice qui dentro.
 */
@Composable
private fun DialogoBlocco(
    blocco: BloccoCambio,
    invioInCorso: Boolean,
    esito: EsitoProposta?,
    onChiudi: () -> Unit,
    onChiedi: (String?) -> Unit,
) {
    val context = LocalContext.current
    val perEliminazione = blocco.cambio is CambioRegola.Eliminazione
    var perche by rememberSaveable(blocco.cambio.regolaId) { mutableStateOf("") }
    // Dall'istante dello sblocco: dopo una rotazione o la morte del processo
    // l'attesa resta giusta.
    val attesa = testoAttesa(context, blocco.secondiRimanenti(System.currentTimeMillis()))

    AlertDialog(
        onDismissRequest = onChiudi,
        title = {
            Text(
                stringResource(
                    if (perEliminazione) R.string.regola_elimina_lock_titolo else R.string.regola_lock_titolo,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spazi.m),
            ) {
                Text(
                    text = if (perEliminazione) {
                        stringResource(R.string.regola_elimina_lock_messaggio, attesa)
                    } else {
                        stringResource(R.string.regola_lock_messaggio, attesa)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(
                        if (perEliminazione) {
                            R.string.regola_elimina_lock_chiedi_spiegazione
                        } else {
                            R.string.regola_lock_chiedi_spiegazione
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                CampoPerche(perche) { perche = it }
                esito?.let { RigaEsitoProposta(it) }
            }
        },
        confirmButton = {
            Button(
                enabled = !invioInCorso && (esito == null || ProposteDelFiglio.riprovabile(esito)),
                onClick = { onChiedi(perche) },
            ) {
                Text(stringResource(R.string.regola_lock_chiedi))
            }
        },
        dismissButton = {
            // Sotto un cambio che allenta resta aperta la modifica: si torna lì.
            // Sotto un'eliminazione non c'è niente da modificare.
            TextButton(onClick = onChiudi) {
                Text(stringResource(if (perEliminazione) R.string.azione_annulla else R.string.regola_lock_torna))
            }
        },
    )
}

/**
 * (0.10) Com'è andata la proposta, dentro il modulo ancora aperto: una riga
 * neutra (§3.1: niente rosso per un esito del server). (0.15) Una proposta
 * già in attesa su quella regola si vede sulla sua card, in Regole.
 */
@Composable
private fun RigaEsitoProposta(esito: EsitoProposta) {
    val context = LocalContext.current
    LocalConfiguration.current
    RigaStato(testoEsitoProposta(context, esito))
}

/** (0.10) Il perché di una proposta: facoltativo, arriva al genitore con la proposta. */
@Composable
private fun CampoPerche(valore: String, onValore: (String) -> Unit) {
    OutlinedTextField(
        value = valore,
        onValueChange = onValore,
        label = { Text(stringResource(R.string.proponi_perche)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ChipGiorno(giorno: String, selezionato: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selezionato,
        onClick = onClick,
        label = { Text(giorno) },
    )
}

/** (0.15) Si tocca tutta la riga, non solo il tondino; alta almeno 48 dp. */
@Composable
private fun RigaRadio(selezionato: Boolean, testo: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selezionato, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selezionato, onClick = null)
        Text(text = testo, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = Spazi.m))
    }
}

/** Un campo del modulo. (0.10) [avviso] = cosa non va nel valore scritto, detto sul campo. */
@Composable
private fun CampoTesto(
    valore: String,
    onValore: (String) -> Unit,
    etichetta: Int,
    numerico: Boolean = false,
    avviso: String? = null,
) {
    OutlinedTextField(
        value = valore,
        onValueChange = onValore,
        label = { Text(stringResource(etichetta)) },
        // Validazione del campo: l'unico posto dove il rosso di sistema vale (§3.1).
        isError = avviso != null,
        supportingText = avviso?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = if (numerico) {
            androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number)
        } else {
            androidx.compose.foundation.text.KeyboardOptions.Default
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Il selettore di `app_o_categoria` (contratto v2.1): niente più testo libero
 * (una regola scritta a mano non troverebbe mai un pacchetto e non scatterebbe
 * mai in silenzio). Si sceglie tutto il telefono (0.9), una categoria fissa o
 * un'app installata dal selettore; si salva "totale", la chiave `categoria:*`
 * o il nome pacchetto.
 */
@Composable
private fun SelettoreAppOCategoria(valore: String, onScegli: (String) -> Unit) {
    val context = LocalContext.current
    // (0.15) Salvato: ruotando la scelta resta aperta.
    var apertoPicker by rememberSaveable { mutableStateOf(false) }
    val etichetta = remember(valore) {
        if (valore.isBlank()) null else CatalogoApp.etichettaValore(context, valore)
    }

    OutlinedButton(onClick = { apertoPicker = true }, modifier = Modifier.fillMaxWidth()) {
        Text(etichetta ?: stringResource(R.string.regola_scegli_app))
    }

    if (apertoPicker) {
        DialogoSceltaApp(
            onScegli = { onScegli(it); apertoPicker = false },
            onAnnulla = { apertoPicker = false },
        )
    }
}

@Composable
private fun DialogoSceltaApp(onScegli: (String) -> Unit, onAnnulla: () -> Unit) {
    val context = LocalContext.current
    // PackageManager è lento: si carica l'elenco fuori dal main thread una volta.
    val app by produceState<List<AppInstallata>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { CatalogoApp.appInstallate(context) }
    }

    AlertDialog(
        onDismissRequest = onAnnulla,
        title = { Text(stringResource(R.string.regola_scegli_app_titolo)) },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                // (0.9) In cima: tutto l'uso del telefono, lo stesso totale che vede il genitore.
                item(key = CatalogoApp.CHIAVE_TOTALE) {
                    RigaScelta(stringResource(R.string.chiave_totale)) { onScegli(CatalogoApp.CHIAVE_TOTALE) }
                }
                item { TitoloSezione(stringResource(R.string.regola_sezione_categorie)) }
                items(CatalogoApp.CATEGORIE, key = { it }) { chiave ->
                    RigaScelta(CatalogoApp.nomeCategoria(context, chiave)) { onScegli(chiave) }
                }
                item { TitoloSezione(stringResource(R.string.regola_sezione_app)) }
                when (val lista = app) {
                    null -> item {
                        Caricamento(modifier = Modifier.padding(vertical = Spazi.m), centrato = false)
                    }
                    else -> items(lista, key = { it.pacchetto }) { installata ->
                        RigaScelta(installata.etichetta) { onScegli(installata.pacchetto) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onAnnulla) { Text(stringResource(R.string.azione_annulla)) }
        },
    )
}

@Composable
private fun RigaScelta(testo: String, onClick: () -> Unit) {
    Text(
        text = testo,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(vertical = Spazi.m),
    )
}

/**
 * Il gate della prima regola (concept.md: "almeno una obbligatoria"). Dopo i
 * permessi, il figlio non entra nell'app finché non si dà la prima regola: è lui
 * a scrivere il patto, e un patto senza regole non esiste. Creata la prima, il
 * server vieta di togliere l'ultima, quindi il gate non ricompare; offline la
 * copia locale (già sincronizzata almeno una volta) basta a superarlo.
 *
 * (v3) Prima ancora, se il telefono non è collegato, qui si collega: indirizzo
 * del server e codice di 6 cifre dal genitore. Fatto il collegamento, la
 * schermata dice "Collegato come: …" e passa alla prima regola (o, se il
 * figlio ha già un patto, direttamente all'app).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrimaRegolaScreen(vm: RegoleViewModel) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // (0.15) Salvato: ruotando il dialogo (e quello che c'è scritto) resta.
    var dialogoAperto by rememberSaveable { mutableStateOf(false) }

    val messaggioErrore = stringResource(R.string.regola_errore_generico)
    LaunchedEffect(stato.evento) {
        when (stato.evento) {
            is RegoleViewModel.Evento.Salvata -> dialogoAperto = false
            is RegoleViewModel.Evento.Errore -> snackbarHostState.showSnackbar(messaggioErrore)
            else -> Unit
        }
        if (stato.evento != null) vm.consumaEvento()
    }

    // La prima impressione dell'app: il titolo È l'eroe della schermata, quindi
    // niente barra in alto che lo ripeta.
    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spazi.l + Spazi.xs, vertical = Spazi.xl),
            verticalArrangement = Arrangement.spacedBy(Spazi.l),
        ) {
            Text(
                text = stringResource(R.string.prima_regola_titolo),
                style = MaterialTheme.typography.displaySmall,
            )
            if (stato.configurazioneMancante) {
                Text(
                    text = stringResource(R.string.prima_regola_config_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
                ModuloCollegamento(origine = OriginiCollegamento.PRIMA_REGOLA, onCollegato = { vm.aggiorna() })
            } else {
                // Appena collegato: il ragazzo vede con che nome lo vede il patto.
                RigaCollegatoCome()
                Text(
                    text = stringResource(R.string.prima_regola_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.prima_regola_spiegazione),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { dialogoAperto = true },
                    enabled = !stato.invioInCorso,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.prima_regola_crea))
                }
            }
        }
    }

    if (dialogoAperto) {
        DialogoRegola(
            regola = null,
            invioInCorso = stato.invioInCorso,
            onAnnulla = { dialogoAperto = false },
            onSalva = { tipo, parametri -> vm.crea(tipo, parametri) },
        )
    }
}
