package eu.stgm.pactum.figlio.ui

import eu.stgm.pactum.design.RigaStato
import eu.stgm.pactum.design.StatoVuoto
import eu.stgm.pactum.design.Caricamento
import eu.stgm.pactum.design.CardEvidenza
import eu.stgm.pactum.design.CardNormale
import eu.stgm.pactum.design.SezioneEspandibile
import eu.stgm.pactum.design.Tono
import eu.stgm.pactum.design.Pillola
import eu.stgm.pactum.design.TitoloSezione
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.faccende.ArchivioCodaFoto
import eu.stgm.pactum.figlio.faccende.FaccendaLocale
import eu.stgm.pactum.figlio.faccende.FotoFaccenda
import eu.stgm.pactum.figlio.faccende.MemoriaBlocco
import eu.stgm.pactum.figlio.faccende.Orologio
import eu.stgm.pactum.figlio.faccende.QuandoBlocca
import eu.stgm.pactum.figlio.faccende.ScattoInCorso
import eu.stgm.pactum.figlio.faccende.StatoBlocco
import eu.stgm.pactum.figlio.faccende.TestoFaccende
import eu.stgm.pactum.figlio.faccende.VistaFaccende
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * (0.13) Il telefono è bloccato dalle faccende adesso? Per le schermate: segue
 * l'archivio del blocco e l'ora (un blocco programmato parte da solo).
 */
@Composable
fun rememberBloccoFaccende(): Boolean {
    val memoria by StatoBlocco.memoria.collectAsStateWithLifecycle()
    var ora by remember { mutableStateOf(Orologio.adesso()) }
    LaunchedEffect(memoria) {
        while (true) {
            ora = Orologio.adesso()
            val attesa = memoria.attesaPartenza(ora) ?: break
            delay((attesa + 50).coerceIn(50, INTERVALLO_ORA_MS))
        }
    }
    return memoria.attivoAdesso(ora)
}

/**
 * (0.13) "Scatta la foto": la fotocamera DI SISTEMA, aperta direttamente
 * ([pacchetto], scelto da ScattoInCorso.fotocameraDiSistema), così anche su
 * Android 8-10 non risponde un'altra app che scatta.
 */
private class ScattaConFotocamera : ActivityResultContracts.TakePicture() {
    var pacchetto: String? = null

    override fun createIntent(context: Context, input: Uri): Intent =
        super.createIntent(context, input).apply { pacchetto?.let { setPackage(it) } }
}

/**
 * (0.13) Le faccende (contratto v3.6): quelle che un genitore ti ha dato, con
 * chi te le ha date, la nota, da quando bloccano il telefono, le bocciature e
 * a che punto è la foto. Per ognuna "Scatta la foto": solo la fotocamera, una
 * foto fatta in quel momento, mai dalla galleria. Sotto, quelle fatte e
 * annullate negli ultimi 30 giorni.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaccendeScreen(
    onApriImpostazioni: (() -> Unit)? = null,
    vm: FaccendeViewModel = viewModel(),
) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    val memoria by StatoBlocco.memoria.collectAsStateWithLifecycle()
    val coda by ArchivioCodaFoto.stato.collectAsStateWithLifecycle()
    val bloccato = rememberBloccoFaccende()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val ambito = rememberCoroutineScope()
    var orologio by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(INTERVALLO_ORA_MS)
            orologio = System.currentTimeMillis()
        }
    }
    // "Adesso" sull'ora del server (il blocco programmato si conta lì).
    val adesso = remember(orologio, memoria) { memoria.oraServer(Orologio.adesso()) }

    // Lo scatto in corso: per quale faccenda e su quale file. Sopravvive a una
    // rotazione e alla morte del processo mentre la fotocamera è aperta.
    var scattoFaccenda by rememberSaveable { mutableStateOf<Long?>(null) }
    var scattoBocciature by rememberSaveable { mutableStateOf(0) }
    var scattoFile by rememberSaveable { mutableStateOf<String?>(null) }
    val contratto = remember { ScattaConFotocamera() }
    val fotocamera = rememberLauncherForActivityResult(contratto) { riuscito ->
        ScattoInCorso.finisce()
        val id = scattoFaccenda
        val percorso = scattoFile
        scattoFaccenda = null
        scattoFile = null
        if (id == null || percorso == null) return@rememberLauncherForActivityResult
        val file = File(percorso)
        if (riuscito) vm.scattata(id, scattoBocciature, file) else file.delete()
    }

    fun scatta(faccenda: FaccendaLocale) {
        // Solo la fotocamera di sistema: senza, niente foto (mai la galleria).
        val pacchetto = ScattoInCorso.fotocameraDiSistema(context)
        if (pacchetto == null) {
            ambito.launch { snackbarHostState.showSnackbar(context.getString(R.string.faccende_nessuna_fotocamera)) }
            return
        }
        val file = FotoFaccenda.nuovoScatto(context)
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.foto", file)
        } catch (e: Exception) {
            null
        }
        if (uri == null) {
            ambito.launch { snackbarHostState.showSnackbar(context.getString(R.string.faccende_foto_non_riuscita)) }
            return
        }
        scattoFaccenda = faccenda.id
        scattoBocciature = faccenda.bocciature
        scattoFile = file.absolutePath
        // La fotocamera aperta da qui resta usabile anche durante il blocco.
        contratto.pacchetto = pacchetto
        ScattoInCorso.inizia(pacchetto)
        try {
            fotocamera.launch(uri)
        } catch (e: ActivityNotFoundException) {
            ScattoInCorso.finisce()
            scattoFaccenda = null
            scattoFile = null
            file.delete()
            ambito.launch { snackbarHostState.showSnackbar(context.getString(R.string.faccende_nessuna_fotocamera)) }
        }
    }

    LifecycleResumeEffect(Unit) {
        vm.aggiorna()
        onPauseOrDispose { }
    }

    LaunchedEffect(stato.evento) {
        val evento = stato.evento ?: return@LaunchedEffect
        vm.consumaEvento()
        val messaggio = when (evento) {
            FaccendeViewModel.Evento.FotoInCoda -> context.getString(R.string.faccende_foto_pronta)
            FaccendeViewModel.Evento.FotoNonRiuscita -> context.getString(R.string.faccende_foto_non_riuscita)
            FaccendeViewModel.Evento.NonCollegato -> context.getString(R.string.regole_config_mancante)
            is FaccendeViewModel.Evento.FotoNonScaricata -> context.getString(
                if (evento.nonCe) R.string.faccenda_foto_non_c_e else R.string.faccenda_foto_non_scaricata,
            )
        }
        ambito.launch { snackbarHostState.showSnackbar(messaggio) }
    }

    val daFare = VistaFaccende.daFare(memoria)
    val chiuse = VistaFaccende.chiuse(memoria)

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.faccende_titolo)) },
                actions = { AzioniBarra(onAggiorna = { vm.aggiorna() }, onApriImpostazioni = onApriImpostazioni) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                stato.caricamento && !stato.letto && daFare.isEmpty() && chiuse.isEmpty() ->
                    Caricamento(testo = stringResource(R.string.faccende_caricamento))

                stato.configurazioneMancante ->
                    StatoVuoto(stringResource(R.string.regole_config_mancante), centrato = true)

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(Spazi.l + Spazi.xs),
                    verticalArrangement = Arrangement.spacedBy(Spazi.l),
                ) {
                    if (stato.scollegato || memoria.scollegato) {
                        // Questo telefono non è più collegato (401): il blocco è tolto.
                        item { RigaStato(stringResource(R.string.scollegato)) }
                    } else if (stato.datiFermi) {
                        // Un'ora salvata nel futuro (l'orologio spostato) non si mostra.
                        item { RigaStato(testoDatiVecchi(listOfNotNull(memoria.sentitoIl, memoria.elencoIl).filter { it <= orologio }.maxOrNull())) }
                    }
                    if (stato.serverDaAggiornare) {
                        // Mai "errore": il server va aggiornato, il resto dell'app funziona.
                        item { RigaStato(stringResource(R.string.faccende_server_da_aggiornare)) }
                    }
                    // (0.15) Lo stato del blocco, compatto: la card quando il telefono
                    // è bloccato, una riga quando è programmato, niente se non c'è.
                    if (bloccato || (memoria.prossimo != null && daFare.isNotEmpty())) {
                        item(key = "stato-blocco") { SchedaBlocco(bloccato, memoria, adesso) }
                    }
                    if (daFare.isEmpty()) {
                        if (!stato.serverDaAggiornare) {
                            // La spiegazione solo qui, quando non c'è niente da fare.
                            item {
                                StatoVuoto(
                                    titolo = stringResource(R.string.faccende_vuoto),
                                    testo = stringResource(R.string.faccende_intro),
                                )
                            }
                        }
                    } else {
                        item { TitoloSezione(stringResource(R.string.faccende_sezione_da_fare)) }
                        items(daFare, key = { "da-fare-${it.id}" }) { faccenda ->
                            CardDaFare(
                                faccenda = faccenda,
                                foto = VistaFaccende.foto(faccenda, coda),
                                adesso = adesso,
                                bloccato = bloccato,
                                occupato = stato.preparazioneInCorso || scattoFaccenda != null,
                                onScatta = { scatta(faccenda) },
                            )
                        }
                    }
                    // (0.15) Fatti e annullati: chiusi, si aprono quando servono.
                    if (chiuse.isNotEmpty()) {
                        item(key = "chiuse") {
                            SezioneEspandibile(
                                titolo = stringResource(R.string.faccende_sezione_chiuse),
                                conteggio = chiuse.size,
                                chiave = "faccende-chiuse",
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(Spazi.m)) {
                                    chiuse.forEach { faccenda ->
                                        CardChiusa(
                                            faccenda = faccenda,
                                            scaricando = stato.scaricamentoInCorso == faccenda.id,
                                            onVediFoto = { vm.apriFoto(faccenda.id, faccenda.titolo) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    stato.fotoAperta?.let { aperta ->
        AlertDialog(
            onDismissRequest = { vm.chiudiFoto() },
            title = { Text(aperta.titolo.ifBlank { stringResource(R.string.faccenda_senza_titolo) }) },
            text = {
                Image(
                    bitmap = aperta.immagine.asImageBitmap(),
                    contentDescription = stringResource(R.string.faccenda_foto_descrizione),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.chiudiFoto() }) { Text(stringResource(R.string.pagina_chiudi)) }
            },
        )
    }
}

/**
 * (0.15) In cima: il telefono è bloccato (card, con le app che intanto si
 * possono usare, scritte leggibili), oppure in una riga quando si bloccherà
 * se i lavori non sono fatti.
 */
@Composable
private fun SchedaBlocco(bloccato: Boolean, memoria: MemoriaBlocco, adesso: Long) {
    val context = LocalContext.current
    if (bloccato) {
        CardEvidenza(tono = Tono.Attenzione) {
            Column(verticalArrangement = Arrangement.spacedBy(Spazi.s)) {
                Text(text = stringResource(R.string.faccende_bloccato), style = MaterialTheme.typography.titleMedium)
                Text(text = stringResource(R.string.faccende_bloccato_spiega), style = MaterialTheme.typography.bodyMedium)
                Text(text = stringResource(R.string.faccende_usabili), style = MaterialTheme.typography.bodyMedium)
            }
        }
    } else {
        memoria.prossimo?.let { RigaStato(testoProssimoBlocco(context, it, adesso)) }
    }
}

/** Una faccenda da fare: chi l'ha data, la nota, da quando blocca, le bocciature, la foto, "Scatta la foto". */
@Composable
private fun CardDaFare(
    faccenda: FaccendaLocale,
    foto: VistaFaccende.Foto,
    adesso: Long,
    bloccato: Boolean,
    occupato: Boolean,
    onScatta: () -> Unit,
) {
    val context = LocalContext.current
    CardNormale {
        Column(verticalArrangement = Arrangement.spacedBy(Spazi.s)) {
            Text(
                text = faccenda.titolo.ifBlank { stringResource(R.string.faccenda_senza_titolo) },
                style = MaterialTheme.typography.titleMedium,
            )
            faccenda.genitore?.let { Nota(stringResource(R.string.faccenda_data_da, it)) }
            faccenda.nota?.let {
                Text(text = stringResource(R.string.faccenda_nota, it), style = MaterialTheme.typography.bodyMedium)
            }
            // (0.15) Da quando blocca, solo se il telefono non è già bloccato.
            if (!bloccato) {
                Text(
                    text = testoBloccaDa(context, faccenda.bloccoDa, adesso),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            faccenda.ultimaBocciatura?.let { b ->
                val chi = b.da ?: stringResource(R.string.faccende_genitore_senza_nome)
                Text(
                    text = b.nota?.let { stringResource(R.string.faccenda_bocciata_con_nota, chi, it) }
                        ?: stringResource(R.string.faccenda_bocciata, chi),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (faccenda.bocciature > 1) {
                    Nota(pluralStringResource(R.plurals.faccenda_bocciature, faccenda.bocciature, faccenda.bocciature))
                }
            }
            when (foto) {
                VistaFaccende.Foto.IN_CODA -> Nota(stringResource(R.string.faccenda_foto_in_coda))
                VistaFaccende.Foto.MANDATA -> Pillola(stringResource(R.string.faccenda_foto_mandata), tono = Tono.Positivo)
                VistaFaccende.Foto.RIFIUTATA -> Nota(stringResource(R.string.faccenda_foto_rifiutata))
                VistaFaccende.Foto.NESSUNA -> Unit
            }
            // Un solo pulsante: "Scatta la foto", o "Scatta di nuovo".
            if (foto != VistaFaccende.Foto.MANDATA) {
                if (foto == VistaFaccende.Foto.NESSUNA) {
                    Button(enabled = !occupato, onClick = onScatta) { Text(stringResource(R.string.faccenda_scatta)) }
                } else {
                    OutlinedButton(enabled = !occupato, onClick = onScatta) {
                        Text(stringResource(R.string.faccenda_scatta_di_nuovo))
                    }
                }
            }
        }
    }
}

/** Una faccenda fatta (con la foto da guardare, finché il server la tiene) o annullata. */
@Composable
private fun CardChiusa(faccenda: FaccendaLocale, scaricando: Boolean, onVediFoto: () -> Unit) {
    CardNormale {
        Column(verticalArrangement = Arrangement.spacedBy(Spazi.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = faccenda.titolo.ifBlank { stringResource(R.string.faccenda_senza_titolo) },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Pillola(
                    stringResource(if (faccenda.fatta) R.string.faccenda_stato_fatta else R.string.faccenda_stato_annullata),
                    tono = if (faccenda.fatta) Tono.Positivo else Tono.Neutro,
                )
            }
            faccenda.genitore?.let { Nota(stringResource(R.string.faccenda_data_da, it)) }
            if (faccenda.fatta) {
                faccenda.fotoIl?.let { Nota(stringResource(R.string.faccenda_foto_arrivata, quandoLocale(Instant.ofEpochMilli(it)))) }
                if (faccenda.foto) {
                    TextButton(onClick = onVediFoto, enabled = !scaricando, contentPadding = PaddingValues(0.dp)) {
                        Text(stringResource(R.string.faccenda_vedi_foto))
                    }
                } else if (faccenda.fotoIl != null) {
                    Nota(stringResource(R.string.faccenda_foto_non_piu))
                }
            } else {
                faccenda.annullataDa?.let { Nota(stringResource(R.string.faccenda_annullata_da, it)) }
            }
        }
    }
}


/** "Blocca il telefono da subito", "…dalle 16:00", "…domani dalle 16:00", "…giovedì dalle 16:00". */
private fun testoBloccaDa(context: Context, bloccoDa: Long?, adesso: Long): String =
    when (val quando = TestoFaccende.quandoBlocca(bloccoDa, adesso, ZoneId.systemDefault())) {
        QuandoBlocca.Subito -> context.getString(R.string.faccenda_blocca_subito)
        is QuandoBlocca.Oggi -> context.getString(R.string.faccenda_blocca_alle, quando.ora)
        is QuandoBlocca.Domani -> context.getString(R.string.faccenda_blocca_domani, quando.ora)
        is QuandoBlocca.Giorno -> context.getString(R.string.faccenda_blocca_giorno, quando.giorno, quando.ora)
        is QuandoBlocca.Data -> context.getString(R.string.faccenda_blocca_giorno, quando.data, quando.ora)
    }

/** "Se non le hai fatte, alle 16:00 il telefono si blocca", nei suoi modi. */
private fun testoProssimoBlocco(context: Context, prossimo: Long, adesso: Long): String =
    when (val quando = TestoFaccende.quandoBlocca(prossimo, adesso, ZoneId.systemDefault())) {
        QuandoBlocca.Subito -> context.getString(R.string.faccende_bloccato)
        is QuandoBlocca.Oggi -> context.getString(R.string.faccende_prossimo_alle, quando.ora)
        is QuandoBlocca.Domani -> context.getString(R.string.faccende_prossimo_domani, quando.ora)
        is QuandoBlocca.Giorno -> context.getString(R.string.faccende_prossimo_giorno, quando.giorno, quando.ora)
        is QuandoBlocca.Data -> context.getString(R.string.faccende_prossimo_giorno, quando.data, quando.ora)
    }

/**
 * (0.13) Prima delle schede (Collega, i permessi, "Cosa vedono i tuoi
 * genitori", la prima regola): se [faccende] (un blocco, faccende da fare, o
 * l'arrivo da "Apri Pactum"), la pagina dei lavori di casa, e in fondo un
 * pulsante per il resto ([etichettaResto]); dal resto, in fondo, si torna ai
 * lavori. Senza faccende, il resto e basta. (0.15) I due pulsanti stanno in
 * fondo: in cima al resto c'è al massimo una cosa (la sessione in corso).
 */
@Composable
fun ConFaccendePrima(faccende: Boolean, etichettaResto: Int, resto: @Composable () -> Unit) {
    if (!faccende) {
        resto()
        return
    }
    var mostraResto by rememberSaveable(etichettaResto) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(modifier = Modifier.weight(1f).consumeWindowInsets(WindowInsets.navigationBars)) {
            if (mostraResto) resto() else FaccendeScreen()
        }
        OutlinedButton(
            onClick = { mostraResto = !mostraResto },
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = Spazi.l + Spazi.xs, vertical = Spazi.m),
        ) {
            Text(stringResource(if (mostraResto) R.string.faccende_torna else etichettaResto))
        }
    }
}

/**
 * (0.15) Un passo del primo avvio: prima i lavori di casa se servono
 * ([ConFaccendePrima]), e con una sessione in corso la sua riga in cima
 * ([ConSessioneInCorso]).
 */
@Composable
fun PassoPrimoAvvio(faccende: Boolean, etichettaResto: Int, passo: @Composable () -> Unit) {
    ConFaccendePrima(faccende, etichettaResto) {
        ConSessioneInCorso { passo() }
    }
}

/** L'ora sulla pagina si rilegge ogni quarto di minuto. */
private const val INTERVALLO_ORA_MS = 15_000L
