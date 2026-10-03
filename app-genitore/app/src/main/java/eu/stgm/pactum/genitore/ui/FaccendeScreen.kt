package eu.stgm.pactum.genitore.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.CodiciErrore
import eu.stgm.pactum.genitore.dati.Faccenda
import eu.stgm.pactum.genitore.dati.MASSIMO_FACCENDE_PER_VOLTA
import eu.stgm.pactum.genitore.dati.MASSIMO_NOTA_FACCENDA
import eu.stgm.pactum.genitore.dati.RiferimentoGenitore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

// (0.13) La pagina delle faccende (contratto v3.6), per il figlio scelto in cima.
// Si apre dalla Panoramica (e dalle notifiche delle faccende), sopra di lei, come
// la lista delle notifiche; si chiude col tasto indietro.
//
// Dall'alto: le app troppo vecchie per il blocco, lo stato del blocco con "Dai
// faccende", poi le faccende DA FARE (con "Annulla"), quelle FATTE (con la foto e,
// entro 24 ore, "Boccia") e quelle ANNULLATE. Chi ha fatto cosa accanto a ogni
// gesto. Le foto si vedono a tutto schermo e restano solo in memoria.

/** Ogni quanto si rilegge l'elenco mentre la pagina è davanti. */
private const val INTERVALLO_RILETTURA_FACCENDE_MS = 60_000L

/** Ogni quanto si ricalcolano le frasi che dipendono dall'ora ("puoi bocciarla ancora per…"). */
private const val INTERVALLO_OROLOGIO_MS = 30_000L

/** I rifiuti dopo i quali il dialogo "Dai faccende" si chiude: riprovare lì non serve. */
private val RIFIUTI_CHE_CHIUDONO = setOf(
    CodiciErrore.ESITO_INCERTO,
    CodiciErrore.NON_TROVATO,
    CodiciErrore.SERVER_DA_AGGIORNARE,
)

/**
 * [fotoRichiesta] = la faccenda di cui aprire subito la foto (dal tocco su una
 * notifica `faccenda_fatta`); [daiSubito] = apri subito "Dai faccende" (dal
 * pulsante della Panoramica).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaccendeScreen(
    onChiudi: () -> Unit,
    fotoRichiesta: Long? = null,
    onFotoRichiestaConsumata: () -> Unit = {},
    daiSubito: Boolean = false,
    onDaiSubitoConsumato: () -> Unit = {},
    vm: FaccendeViewModel = viewModel(),
    famigliaVm: FamigliaViewModel = viewModel(),
) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    val famiglia by famigliaVm.stato.collectAsStateWithLifecycle()
    val figlioId = famiglia.figlioId
    val nomeFiglio = famiglia.figlioScelto?.nome
    val p = parole()
    val snackbarHostState = remember { SnackbarHostState() }
    val ambito = rememberCoroutineScope()

    // Come la Panoramica: una lettura a ogni ritorno e a ogni cambio di figlio, poi
    // ogni minuto; si aspetta di sapere di quale figlio (famiglia pronta).
    val cicloVita = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(cicloVita, figlioId, famiglia.pronta) {
        if (!famiglia.pronta) return@LaunchedEffect
        cicloVita.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.aggiorna(figlioId)
                delay(INTERVALLO_RILETTURA_FACCENDE_MS)
            }
        }
    }

    // L'ora che passa: le frasi "puoi bocciarla ancora per…" e "blocca dalle…".
    var adesso by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(INTERVALLO_OROLOGIO_MS)
            adesso = Instant.now()
        }
    }

    var daiAperto by rememberSaveable { mutableStateOf(false) }
    var erroreDai by remember { mutableStateOf<String?>(null) }
    var bocciaId by rememberSaveable { mutableStateOf<Long?>(null) }
    var annullaId by rememberSaveable { mutableStateOf<Long?>(null) }

    // Dalla notifica: la foto di quella faccenda, appena si sa QUALE foto è (l'ora
    // della foto sta nell'elenco: dopo una bocciatura la stessa faccenda ne ha
    // un'altra). Se l'elenco non la conosce, la foto si apre lo stesso, senza "Boccia".
    // Dalla Panoramica: "Dai faccende".
    val elencoPronto = stato.di(figlioId) && stato.faccende != null && !stato.caricamento
    LaunchedEffect(fotoRichiesta, elencoPronto) {
        val id = fotoRichiesta ?: return@LaunchedEffect
        if (!elencoPronto && !stato.collegamentoNonValido) return@LaunchedEffect
        vm.apriFoto(id, stato.faccende?.firstOrNull { it.id == id }?.fotoTs)
        onFotoRichiestaConsumata()
    }
    LaunchedEffect(daiSubito) {
        if (!daiSubito) return@LaunchedEffect
        erroreDai = null
        daiAperto = true
        onDaiSubitoConsumato()
    }

    // Gli esiti si dicono una volta. Il dialogo "Dai faccende" resta aperto sui
    // rifiuti che si correggono lì (troppe faccende, un titolo che non va), e lo
    // dice dentro; si chiude sul resto.
    LaunchedEffect(stato.evento) {
        val evento = stato.evento ?: return@LaunchedEffect
        vm.consumaEvento()
        famigliaVm.aggiorna() // le faccende da fare e il blocco accanto al figlio
        val messaggio = when (evento) {
            is EventoFaccende.Date -> {
                daiAperto = false
                erroreDai = null
                testoFaccendeDate(p, evento.quante)
            }
            EventoFaccende.Bocciata -> p.testo(R.string.boccia_fatto)
            EventoFaccende.Annullata -> p.testo(R.string.annulla_faccenda_fatto)
            is EventoFaccende.Rifiuto -> {
                val nome = famiglia.figli.firstOrNull { it.id == evento.figlioId }?.nome
                val frase = messaggioRifiutoFaccende(p, evento.codice, evento.gesto, nome)
                if (evento.gesto == GestoFaccende.DAI && daiAperto && evento.codice !in RIFIUTI_CHE_CHIUDONO) {
                    erroreDai = frase
                    null
                } else {
                    if (evento.gesto == GestoFaccende.DAI) daiAperto = false
                    frase
                }
            }
        }
        if (messaggio != null) ambito.launch { snackbarHostState.showSnackbar(messaggio) }
    }

    val faccende = stato.faccende.takeIf { stato.di(figlioId) }
    val senzaBlocco = remember(famiglia.figlioScelto) {
        dispositiviSenzaBlocco(famiglia.figlioScelto?.dispositivi.orEmpty())
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.faccende_titolo)) },
                navigationIcon = {
                    IconButton(onClick = onChiudi) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.azione_indietro))
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            famigliaVm.aggiorna()
                            if (famiglia.pronta) vm.aggiorna(figlioId)
                        },
                    ) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.azione_aggiorna))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            IntestazioneFiglio(famiglia, onScegli = famigliaVm::scegli)
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !stato.di(figlioId) || (stato.caricamento && faccende == null && !stato.configurazioneMancante) ->
                        Caricamento(stringResource(R.string.faccende_caricamento))

                    stato.configurazioneMancante -> Centro {
                        StatoPrimaApertura(
                            titolo = stringResource(R.string.config_mancante_titolo),
                            testo = stringResource(R.string.finestra_config_mancante),
                            centrato = true,
                            modifier = Modifier.padding(horizontal = Spazi.xxl),
                        )
                    }

                    // (0.13) 401: il collegamento di questo telefono non vale più. Non è la rete.
                    stato.collegamentoNonValido -> Centro {
                        StatoPrimaApertura(
                            titolo = stringResource(R.string.collegamento_non_valido_titolo),
                            testo = stringResource(R.string.collegamento_non_valido),
                            centrato = true,
                            modifier = Modifier.padding(horizontal = Spazi.xxl),
                        )
                    }

                    stato.serverVecchio -> Centro {
                        StatoPrimaApertura(
                            titolo = stringResource(R.string.faccende_server_vecchio_titolo),
                            testo = stringResource(R.string.faccende_server_vecchio),
                            centrato = true,
                            modifier = Modifier.padding(horizontal = Spazi.xxl),
                        )
                    }

                    faccende == null -> Centro { TestoCentrato(stringResource(R.string.faccende_errore)) }

                    else -> ElencoFaccende(
                        faccende = faccende,
                        errore = stato.errore,
                        nomeFiglio = nomeFiglio,
                        io = famiglia.io,
                        senzaBlocco = senzaBlocco,
                        adesso = adesso,
                        invio = stato.invio,
                        onDai = {
                            erroreDai = null
                            daiAperto = true
                        },
                        onAnnulla = { annullaId = it.id },
                        onGuardaFoto = { vm.apriFoto(it.id, it.fotoTs) },
                        onBoccia = { bocciaId = it.id },
                    )
                }
            }
        }
    }

    // --- I dialoghi --------------------------------------------------------------------

    if (daiAperto && figlioId != null && stato.di(figlioId) && !stato.serverVecchio) {
        DialogoDaiFaccende(
            nomeFiglio = nomeFiglio,
            storia = faccende.orEmpty(),
            senzaBlocco = senzaBlocco,
            invio = stato.invio,
            errore = erroreDai,
            onDai = { titoli, nota, bloccoDa ->
                erroreDai = null
                vm.daiFaccende(figlioId, titoli, nota, bloccoDa, famiglia.io)
            },
            onAnnulla = {
                daiAperto = false
                erroreDai = null
            },
        )
    }

    faccende?.firstOrNull { it.id == annullaId }?.let { faccenda ->
        AlertDialog(
            onDismissRequest = { annullaId = null },
            title = { Text(stringResource(R.string.annulla_faccenda_titolo, faccenda.titolo)) },
            text = { Text(stringResource(R.string.annulla_faccenda_testo)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        annullaId = null
                        vm.annulla(figlioId, faccenda)
                    },
                ) {
                    Text(stringResource(R.string.annulla_faccenda_conferma))
                }
            },
            dismissButton = {
                TextButton(onClick = { annullaId = null }) { Text(stringResource(R.string.annulla_faccenda_lascia)) }
            },
        )
    }

    // La foto a tutto schermo, sopra la pagina; la domanda prima di bocciare, sopra la foto.
    stato.foto?.let { foto ->
        VistaFoto(
            foto = foto,
            faccenda = faccende?.firstOrNull { it.id == foto.faccendaId },
            adesso = adesso,
            invio = stato.invio,
            onChiudi = vm::chiudiFoto,
            onRiprova = { vm.apriFoto(foto.faccendaId, foto.fotoTs) },
            onBoccia = { bocciaId = it.id },
        )
    }

    faccende?.firstOrNull { it.id == bocciaId }?.let { faccenda ->
        DialogoBoccia(
            faccenda = faccenda,
            nomeFiglio = nomeFiglio,
            onBoccia = { nota ->
                bocciaId = null
                vm.boccia(figlioId, faccenda, nota)
            },
            onAnnulla = { bocciaId = null },
        )
    }
}

/** L'elenco: avvisi, stato del blocco, e le faccende divise per stato. */
@Composable
private fun ElencoFaccende(
    faccende: List<Faccenda>,
    errore: Boolean,
    nomeFiglio: String?,
    io: RiferimentoGenitore?,
    senzaBlocco: List<DispositivoSenzaBlocco>,
    adesso: Instant,
    invio: Boolean,
    onDai: () -> Unit,
    onAnnulla: (Faccenda) -> Unit,
    onGuardaFoto: (Faccenda) -> Unit,
    onBoccia: (Faccenda) -> Unit,
) {
    val p = parole()
    val gruppi = remember(faccende) { faccendeInGruppi(faccende) }
    val blocco = statoBlocco(faccende, adesso)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spazi.l),
        verticalArrangement = Arrangement.spacedBy(Spazi.m),
    ) {
        if (errore) item { RigaDatiVecchi(stringResource(R.string.faccende_dati_vecchi)) }

        // Dove il blocco non parte (app più vecchia della 0.13): prima di tutto.
        items(senzaBlocco, key = { "vecchia-${it.dispositivo.id}" }) {
            RigaDatiVecchi(testoDispositivoSenzaBlocco(p, it))
        }

        item(key = "stato") {
            CardContenuto {
                Column(modifier = Modifier.padding(Spazi.l)) {
                    testoStatoBlocco(p, blocco)?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (blocco.attivo) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    testoQuanteDaFare(p, blocco.daFare)?.let {
                        Text(text = it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = Spazi.xs))
                    }
                    Text(
                        text = spiegaFaccende(p, nomeFiglio),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spazi.s),
                    )
                    Button(
                        onClick = onDai,
                        enabled = !invio,
                        modifier = Modifier.fillMaxWidth().padding(top = Spazi.m),
                    ) {
                        Text(stringResource(R.string.faccende_dai))
                    }
                }
            }
        }

        if (faccende.isEmpty()) {
            item(key = "nessuna") { RigaVuota(stringResource(R.string.faccende_nessuna)) }
        }

        if (gruppi.daFare.isNotEmpty()) {
            item(key = "da-fare") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SopraTitolo(stringResource(R.string.faccende_da_fare))
                    ListaRighe(gruppi.daFare) { faccenda ->
                        RigaDaFare(faccenda, io, adesso, invio, onAnnulla = { onAnnulla(faccenda) })
                    }
                }
            }
        }

        if (gruppi.fatte.isNotEmpty()) {
            item(key = "fatte") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SopraTitolo(stringResource(R.string.faccende_fatte))
                    ListaRighe(gruppi.fatte) { faccenda ->
                        RigaFatta(
                            faccenda = faccenda,
                            io = io,
                            adesso = adesso,
                            invio = invio,
                            onGuardaFoto = { onGuardaFoto(faccenda) },
                            onBoccia = { onBoccia(faccenda) },
                        )
                    }
                }
            }
        }

        if (gruppi.annullate.isNotEmpty()) {
            item(key = "annullate") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SopraTitolo(stringResource(R.string.faccende_annullate))
                    ListaRighe(gruppi.annullate) { faccenda -> RigaAnnullata(faccenda, io) }
                }
            }
        }

        item(key = "trenta-giorni") {
            Text(
                text = stringResource(R.string.faccende_foto_30_giorni),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Il titolo e la nota di una faccenda: le parole del genitore che l'ha data. */
@Composable
private fun TitoloENota(faccenda: Faccenda, attenuato: Boolean = false) {
    Text(
        text = faccenda.titolo,
        style = MaterialTheme.typography.bodyLarge,
        color = if (attenuato) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    )
    faccenda.nota?.trim()?.takeIf { it.isNotEmpty() }?.let {
        Text(
            text = stringResource(R.string.faccenda_nota, it),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spazi.xs),
        )
    }
}

/** Una riga sottovoce (chi, quando, le bocciature). */
@Composable
private fun RigaSottovoce(testo: String) {
    Text(
        text = testo,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spazi.xs),
    )
}

/** Una faccenda da fare: chi l'ha data e quando, da quando blocca, le bocciature, e "Annulla". */
@Composable
private fun RigaDaFare(faccenda: Faccenda, io: RiferimentoGenitore?, adesso: Instant, invio: Boolean, onAnnulla: () -> Unit) {
    val p = parole()
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spazi.m)) {
        TitoloENota(faccenda)
        val data = listOfNotNull(
            testoDataDa(p, faccenda, io),
            istanteServer(faccenda.creataTs)?.let { dataOraLocale(it) },
        ).joinToString(" · ")
        if (data.isNotEmpty()) RigaSottovoce(data)
        testoBloccaDalle(p, faccenda, adesso)?.let { RigaSottovoce(it) }
        righeBocciature(p, faccenda, io).forEach { RigaSottovoce(it) }
        TextButton(onClick = onAnnulla, enabled = !invio, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.faccenda_annulla))
        }
    }
}

/** Una faccenda fatta: quando è arrivata la foto, chi l'aveva data, la foto e (entro 24 ore) "Boccia". */
@Composable
private fun RigaFatta(
    faccenda: Faccenda,
    io: RiferimentoGenitore?,
    adesso: Instant,
    invio: Boolean,
    onGuardaFoto: () -> Unit,
    onBoccia: () -> Unit,
) {
    val p = parole()
    val stato = bocciabile(faccenda, adesso)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spazi.m)) {
        TitoloENota(faccenda)
        val righe = listOfNotNull(
            istanteServer(faccenda.fotoTs ?: faccenda.chiusaTs)?.let { p.testo(R.string.faccenda_foto_arrivata, alleQuando(p, it)) },
            testoDataDa(p, faccenda, io),
        ).joinToString(" · ")
        if (righe.isNotEmpty()) RigaSottovoce(righe)
        righeBocciature(p, faccenda, io).forEach { RigaSottovoce(it) }
        if (stato is Bocciabile.Si) {
            Text(
                text = testoBocciabile(p, stato).orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = Spazi.xs),
            )
        }
        if (faccenda.foto) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Spazi.s),
                horizontalArrangement = Arrangement.spacedBy(Spazi.s),
            ) {
                OutlinedButton(onClick = onGuardaFoto) { Text(stringResource(R.string.faccenda_guarda_foto)) }
                if (stato is Bocciabile.Si) {
                    TextButton(onClick = onBoccia, enabled = !invio) { Text(stringResource(R.string.faccenda_boccia)) }
                }
            }
        } else {
            RigaSottovoce(stringResource(R.string.faccenda_foto_cancellata))
        }
    }
}

/** Una faccenda annullata: chi e quando. */
@Composable
private fun RigaAnnullata(faccenda: Faccenda, io: RiferimentoGenitore?) {
    val p = parole()
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spazi.m)) {
        TitoloENota(faccenda, attenuato = true)
        RigaSottovoce(
            listOfNotNull(
                testoAnnullata(p, faccenda, io),
                istanteServer(faccenda.chiusaTs)?.let { dataOraLocale(it) },
            ).joinToString(" · "),
        )
    }
}

/** Come si salva l'elenco dei titoli del dialogo (una rotazione non lo perde). */
private val SalvaTitoli = listSaver<List<String>, String>(save = { it }, restore = { it })

/**
 * "Dai faccende": uno o più titoli (fino a 10), i titoli usati di recente da
 * toccare per riusarli, una nota facoltativa (uguale per tutte), e da quando
 * bloccano: subito o dalle… Se l'ora scelta è già passata, vale domani e il
 * dialogo lo dice prima del tocco; se il giorno cambia mentre il dialogo è aperto,
 * il primo tocco aggiorna la frase e non manda niente.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun DialogoDaiFaccende(
    nomeFiglio: String?,
    storia: List<Faccenda>,
    senzaBlocco: List<DispositivoSenzaBlocco>,
    invio: Boolean,
    errore: String?,
    onDai: (titoli: List<String>, nota: String?, bloccoDa: String?) -> Unit,
    onAnnulla: () -> Unit,
) {
    val p = parole()
    var titoli by rememberSaveable(stateSaver = SalvaTitoli) { mutableStateOf(listOf("")) }
    var nota by rememberSaveable { mutableStateOf("") }
    var subito by rememberSaveable { mutableStateOf(true) }
    var minutoDelGiorno by rememberSaveable {
        mutableIntStateOf(oraProposta(LocalTime.now()).toSecondOfDay() / 60)
    }
    var sceltaOra by rememberSaveable { mutableStateOf(false) }
    var adesso by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            adesso = ZonedDateTime.now()
        }
    }
    val ora = LocalTime.of(minutoDelGiorno / 60, minutoDelGiorno % 60)
    val inizio = if (subito) null else inizioBlocco(ora, adesso)
    val problemi = titoli.map { problemaTitolo(it) }
    val problemaDellaNota = problemaNota(nota)
    // Le righe lasciate vuote non contano: partono le faccende scritte.
    val scritti = titoli.filter { it.isNotBlank() }
    val valido = scritti.isNotEmpty() && scritti.size <= MASSIMO_FACCENDE_PER_VOLTA &&
        scritti.all { problemaTitolo(it) == null } && problemaDellaNota == null
    val recenti = remember(storia, titoli) { titoliRecenti(storia, titoli) }

    AlertDialog(
        onDismissRequest = onAnnulla,
        title = {
            Text(
                nomeDaScrivere(nomeFiglio)?.let { stringResource(R.string.dai_titolo, it) }
                    ?: stringResource(R.string.dai_titolo_senza_nome),
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spazi.s),
            ) {
                senzaBlocco.forEach { RigaDatiVecchi(testoDispositivoSenzaBlocco(p, it)) }
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
                // I titoli usati di recente: un tocco li mette nella prima riga vuota (o in una nuova).
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
                SopraTitolo(stringResource(R.string.dai_blocco))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = subito, onClick = { subito = true })
                    Text(stringResource(R.string.dai_subito), style = MaterialTheme.typography.bodyLarge)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !subito, onClick = { subito = false })
                    TextButton(onClick = { sceltaOra = true }) { Text(testoDalle(p, ora)) }
                }
                // Che cosa succede, prima del tocco: domani si dice in evidenza.
                Text(
                    text = testoInizioBlocco(p, inizio, nomeFiglio),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (inizio?.domani == true) FontWeight.SemiBold else null,
                    color = if (inizio?.domani == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (errore != null) {
                    Text(
                        text = errore,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = valido && !invio,
                onClick = {
                    // L'istante vero, adesso: se non è quello che il dialogo mostra
                    // (mezzanotte passata col dialogo aperto, un'ora intanto passata),
                    // niente parte e il dialogo mostra la data nuova.
                    val adessoVero = ZonedDateTime.now()
                    when (val controllo = controlloPrimaDiMandare(inizio, ora.takeIf { !subito }, adessoVero)) {
                        is ControlloInvio.Cambiato -> adesso = adessoVero
                        is ControlloInvio.Manda -> onDai(scritti, nota.ifBlank { null }, controllo.bloccoDa)
                    }
                },
            ) {
                Text(stringResource(R.string.dai_manda))
            }
        },
        dismissButton = {
            TextButton(onClick = onAnnulla) { Text(stringResource(R.string.azione_annulla)) }
        },
    )

    if (sceltaOra) {
        val statoOra = rememberTimePickerState(
            initialHour = minutoDelGiorno / 60,
            initialMinute = minutoDelGiorno % 60,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { sceltaOra = false },
            title = { Text(stringResource(R.string.dai_scegli_ora)) },
            text = { TimePicker(state = statoOra) },
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

/** "Bocciare «…»?": che cosa succede, e il perché facoltativo (lo legge il figlio). */
@Composable
private fun DialogoBoccia(faccenda: Faccenda, nomeFiglio: String?, onBoccia: (String?) -> Unit, onAnnulla: () -> Unit) {
    val p = parole()
    var nota by rememberSaveable(faccenda.id) { mutableStateOf("") }
    val problema = problemaNota(nota)
    val sottoLaNota = testoProblemaNota(p, problema)
        ?: stringResource(R.string.sessione_perche_massimo, MASSIMO_NOTA_FACCENDA)
    AlertDialog(
        onDismissRequest = onAnnulla,
        title = { Text(stringResource(R.string.boccia_titolo, faccenda.titolo)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spazi.s)) {
                Text(
                    nomeDaScrivere(nomeFiglio)?.let { stringResource(R.string.boccia_testo, it) }
                        ?: stringResource(R.string.boccia_testo_senza_nome),
                )
                OutlinedTextField(
                    value = nota,
                    onValueChange = { nota = it },
                    label = { Text(stringResource(R.string.proposta_campo_perche)) },
                    isError = problema != null,
                    supportingText = { Text(sottoLaNota) },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onBoccia(nota.ifBlank { null }) }, enabled = problema == null) {
                Text(stringResource(R.string.faccenda_boccia))
            }
        },
        dismissButton = {
            TextButton(onClick = onAnnulla) { Text(stringResource(R.string.azione_annulla)) }
        },
    )
}

/**
 * La foto a tutto schermo, su fondo nero: si allarga con due dita. Sotto, quando è
 * arrivata, quanto resta per bocciarla e "Boccia". La foto è in memoria e basta:
 * non c'è un "salva", e non va nella galleria.
 */
@Composable
private fun VistaFoto(
    foto: FotoAperta<ImageBitmap>,
    faccenda: Faccenda?,
    adesso: Instant,
    invio: Boolean,
    onChiudi: () -> Unit,
    onRiprova: () -> Unit,
    onBoccia: (Faccenda) -> Unit,
) {
    val p = parole()
    Dialog(onDismissRequest = onChiudi, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .safeDrawingPadding(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onChiudi) {
                    Icon(Icons.Filled.Close, stringResource(R.string.foto_chiudi), tint = Color.White)
                }
                Text(
                    text = faccenda?.titolo.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val immagine = foto.immagine
                when {
                    foto.caricamento -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Text(
                            text = stringResource(R.string.foto_caricamento),
                            color = Color.White,
                            modifier = Modifier.padding(top = Spazi.s),
                        )
                    }
                    immagine != null -> {
                        var scala by remember(foto.faccendaId) { mutableFloatStateOf(1f) }
                        var spostamento by remember(foto.faccendaId) { mutableStateOf(Offset.Zero) }
                        Image(
                            bitmap = immagine,
                            contentDescription = stringResource(R.string.foto_descrizione, faccenda?.titolo.orEmpty()),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(foto.faccendaId) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        scala = (scala * zoom).coerceIn(1f, 5f)
                                        spostamento = if (scala == 1f) Offset.Zero else spostamento + pan
                                    }
                                }
                                .graphicsLayer {
                                    scaleX = scala
                                    scaleY = scala
                                    translationX = spostamento.x
                                    translationY = spostamento.y
                                },
                        )
                    }
                    else -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(Spazi.xxl),
                    ) {
                        Text(
                            text = stringResource(
                                when (foto.problema) {
                                    ProblemaFoto.NON_TROVATA -> R.string.foto_non_trovata
                                    ProblemaFoto.SERVER_VECCHIO -> R.string.faccende_server_vecchio
                                    else -> R.string.foto_errore
                                },
                            ),
                            color = Color.White,
                            textAlign = TextAlign.Center,
                        )
                        if (foto.problema == ProblemaFoto.ERRORE) {
                            TextButton(onClick = onRiprova) { Text(stringResource(R.string.foto_riprova), color = Color.White) }
                        }
                    }
                }
            }
            // Ora e "Boccia" solo se la foto sullo schermo è quella che la faccenda ha
            // adesso: mai bocciare una foto nuova guardando quella vecchia.
            if (faccenda != null && foto.fotoTs != null && foto.fotoTs == faccenda.fotoTs) {
                val stato = bocciabile(faccenda, adesso)
                Column(modifier = Modifier.fillMaxWidth().padding(Spazi.l)) {
                    istanteServer(faccenda.fotoTs)?.let {
                        Text(text = p.testo(R.string.faccenda_foto_arrivata, alleQuando(p, it)), color = Color.White)
                    }
                    testoBocciabile(p, stato)?.let {
                        Text(text = it, color = Color.White, modifier = Modifier.padding(top = Spazi.xs))
                    }
                    if (stato is Bocciabile.Si) {
                        Button(
                            onClick = { onBoccia(faccenda) },
                            enabled = !invio,
                            modifier = Modifier.fillMaxWidth().padding(top = Spazi.s),
                        ) {
                            Text(stringResource(R.string.faccenda_boccia))
                        }
                    }
                }
            }
        }
    }
}
