package eu.stgm.pactum.genitore.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.design.RigaStato
import eu.stgm.pactum.design.StatoVuoto
import eu.stgm.pactum.design.Caricamento
import eu.stgm.pactum.design.CardNormale
import eu.stgm.pactum.design.CardEvidenza
import eu.stgm.pactum.design.Tono
import eu.stgm.pactum.design.SezioneEspandibile
import eu.stgm.pactum.design.FilaPulsanti
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.Faccenda
import eu.stgm.pactum.genitore.dati.MASSIMO_NOTA_FACCENDA
import eu.stgm.pactum.genitore.dati.RiferimentoGenitore
import kotlinx.coroutines.delay
import java.time.Instant

// (0.13) I lavori di casa (contratto v3.6), per il figlio scelto in cima. (0.15)
// Sono una scheda della barra, "Lavori" (titolo "Lavori di casa"): in alto "Dai
// lavori di casa", che apre una pagina intera; poi lo stato del blocco (in
// evidenza quando è attivo); poi DA FARE (con "Togli"), FATTI (con la foto e,
// entro 24 ore, "Boccia") e ANNULLATI, chiusi in una sezione che si apre. Chi ha
// fatto cosa accanto a ogni gesto. Le foto si vedono a tutto schermo e restano
// solo in memoria.

/** Ogni quanto si rilegge l'elenco mentre la scheda è davanti. */
private const val INTERVALLO_RILETTURA_FACCENDE_MS = 60_000L

/** Ogni quanto si ricalcolano le frasi che dipendono dall'ora ("puoi bocciarlo ancora per…"). */
private const val INTERVALLO_OROLOGIO_MS = 30_000L

/**
 * [fotoRichiesta] = il lavoro di cui aprire subito la foto (dal tocco su una
 * notifica `faccenda_fatta`, o sulla sua riga nelle Notifiche).
 */
@Composable
fun LavoriScreen(
    fotoRichiesta: Long? = null,
    onFotoRichiestaConsumata: () -> Unit = {},
    vm: FaccendeViewModel = viewModel(),
    famigliaVm: FamigliaViewModel = viewModel(),
) {
    val cornice = LocalCornice.current
    val stato by vm.stato.collectAsStateWithLifecycle()
    val famiglia by famigliaVm.stato.collectAsStateWithLifecycle()
    val figlioId = famiglia.figlioId
    val nomeFiglio = famiglia.figlioScelto?.nome
    val p = parole()

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

    // L'ora che passa: le frasi "puoi bocciarlo ancora per…" e "blocca dalle…".
    var adesso by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(INTERVALLO_OROLOGIO_MS)
            adesso = Instant.now()
        }
    }

    var bocciaId by rememberSaveable { mutableStateOf<Long?>(null) }
    var togliId by rememberSaveable { mutableStateOf<Long?>(null) }

    // Dalla notifica: la foto di quel lavoro, appena si sa QUALE foto è (l'ora
    // della foto sta nell'elenco: dopo una bocciatura lo stesso lavoro ne ha
    // un'altra). Se l'elenco non la conosce, la foto si apre lo stesso, senza "Boccia".
    val elencoPronto = stato.di(figlioId) && stato.faccende != null && !stato.caricamento
    LaunchedEffect(fotoRichiesta, elencoPronto) {
        val id = fotoRichiesta ?: return@LaunchedEffect
        if (!elencoPronto && !stato.collegamentoNonValido) return@LaunchedEffect
        vm.apriFoto(id, stato.faccende?.firstOrNull { it.id == id }?.fotoTs)
        onFotoRichiestaConsumata()
    }

    // Gli esiti si dicono una volta. (Quelli di "Dai lavori di casa" li dice la sua
    // pagina; se nel frattempo si è tornati qui, si dicono qui.)
    LaunchedEffect(stato.evento) {
        val evento = stato.evento ?: return@LaunchedEffect
        vm.consumaEvento()
        famigliaVm.aggiorna() // i lavori da fare e il blocco accanto al figlio
        val messaggio = when (evento) {
            is EventoFaccende.Date -> testoFaccendeDate(p, evento.quante)
            EventoFaccende.Bocciata -> p.testo(R.string.boccia_fatto)
            EventoFaccende.Annullata -> p.testo(R.string.togli_faccenda_fatto)
            is EventoFaccende.Rifiuto -> {
                val nome = famiglia.figli.firstOrNull { it.id == evento.figlioId }?.nome
                messaggioRifiutoFaccende(p, evento.codice, evento.gesto, nome)
            }
        }
        cornice.messaggi.mostra(messaggio)
    }

    val faccende = stato.faccende.takeIf { stato.di(figlioId) }
    val senzaBlocco = remember(famiglia.figlioScelto) {
        dispositiviSenzaBlocco(famiglia.figlioScelto?.dispositivi.orEmpty())
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            BarraScheda(stringResource(R.string.faccende_titolo)) {
                famigliaVm.aggiorna()
                if (famiglia.pronta) vm.aggiorna(figlioId)
            }
        },
    ) { padding ->
        // (0.15) La scelta del figlio: fissa in cima mentre si carica, con un errore o
        // senza elenco (si cambia figlio anche quando i dati di uno non arrivano);
        // con l'elenco è la sua prima riga e scorre col resto.
        val conElenco = !famiglia.collegamentoNonValido && !stato.collegamentoNonValido && !stato.configurazioneMancante &&
            stato.di(figlioId) && faccende != null && !stato.serverVecchio
        ConSceltaFiglio(famiglia, fissa = !conElenco, modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                // (0.13) 401: il collegamento di questo telefono non vale più. Non è la rete.
                famiglia.collegamentoNonValido || stato.collegamentoNonValido -> StatoVuoto(
                    centrato = true,
                    titolo = stringResource(R.string.collegamento_non_valido_titolo),
                    testo = stringResource(R.string.collegamento_non_valido),
                    azione = stringResource(R.string.azione_collega_di_nuovo),
                    onAzione = { cornice.apri(Pagina.Impostazioni(SezioneImpostazioni.COLLEGAMENTO)) },
                )

                stato.configurazioneMancante -> StatoVuoto(
                    centrato = true,
                    titolo = stringResource(R.string.config_mancante_titolo),
                    testo = stringResource(R.string.faccende_config_mancante),
                    azione = stringResource(R.string.azione_collega),
                    onAzione = { cornice.apri(Pagina.Impostazioni(SezioneImpostazioni.COLLEGAMENTO)) },
                )

                !stato.di(figlioId) || (stato.caricamento && faccende == null) ->
                    Caricamento(testo = stringResource(R.string.faccende_caricamento))

                stato.serverVecchio -> StatoVuoto(
                    titolo = stringResource(R.string.faccende_server_vecchio_titolo),
                    testo = stringResource(R.string.faccende_server_vecchio),
                    centrato = true,
                )

                faccende == null -> StatoVuoto(stringResource(R.string.faccende_errore), centrato = true)

                else -> ElencoFaccende(
                    faccende = faccende,
                    famiglia = famiglia,
                    errore = stato.errore,
                    nomeFiglio = nomeFiglio,
                    senzaBlocco = senzaBlocco,
                    adesso = adesso,
                    invio = stato.invio,
                    onDai = { cornice.apri(Pagina.DaiLavori) },
                    onTogli = { togliId = it.id },
                    onGuardaFoto = { vm.apriFoto(it.id, it.fotoTs) },
                    onBoccia = { bocciaId = it.id },
                )
            }
        }
    }

    // --- I dialoghi --------------------------------------------------------------------

    faccende?.firstOrNull { it.id == togliId }?.let { faccenda ->
        AlertDialog(
            onDismissRequest = { togliId = null },
            title = { Text(stringResource(R.string.togli_faccenda_titolo, faccenda.titolo)) },
            text = { Text(stringResource(R.string.togli_faccenda_testo)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        togliId = null
                        vm.annulla(figlioId, faccenda)
                    },
                ) {
                    Text(stringResource(R.string.togli_faccenda_conferma))
                }
            },
            dismissButton = {
                TextButton(onClick = { togliId = null }) { Text(stringResource(R.string.togli_faccenda_lascia)) }
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

/** L'elenco: "Dai lavori di casa", lo stato del blocco, e i lavori divisi per stato. */
@Composable
private fun ElencoFaccende(
    faccende: List<Faccenda>,
    famiglia: FamigliaViewModel.StatoFamiglia,
    errore: Boolean,
    nomeFiglio: String?,
    senzaBlocco: List<DispositivoSenzaBlocco>,
    adesso: Instant,
    invio: Boolean,
    onDai: () -> Unit,
    onTogli: (Faccenda) -> Unit,
    onGuardaFoto: (Faccenda) -> Unit,
    onBoccia: (Faccenda) -> Unit,
) {
    val p = parole()
    val io = famiglia.io
    val gruppi = remember(faccende) { faccendeInGruppi(faccende) }
    val blocco = statoBlocco(faccende, adesso)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spazi.l),
        verticalArrangement = Arrangement.spacedBy(Spazi.m),
    ) {
        sceltaDelFiglio(famiglia)
        if (errore) item(key = "dati-vecchi") { RigaStato(stringResource(R.string.faccende_dati_vecchi)) }

        item(key = "dai") {
            Button(onClick = onDai, enabled = !invio, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.faccende_dai))
            }
        }

        // Dove il blocco non parte (app più vecchia della 0.13): detto una volta sola.
        items(senzaBlocco, key = { "vecchia-${it.dispositivo.id}" }) {
            RigaStato(testoDispositivoSenzaBlocco(p, it))
        }

        // Lo stato del blocco: in evidenza quando è attivo.
        item(key = "stato") {
            val contenuto: @Composable () -> Unit = {
                    testoStatoBlocco(p, blocco)?.let {
                        Text(text = it, style = MaterialTheme.typography.titleMedium)
                    }
                    testoQuanteDaFare(p, blocco.daFare)?.let {
                        Text(text = it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = Spazi.xs))
                    }
            }
            if (blocco.attivo) {
                CardEvidenza(tono = Tono.Attenzione) { contenuto() }
            } else {
                CardNormale { contenuto() }
            }
        }

        if (faccende.isEmpty()) {
            item(key = "nessuna") {
                Column {
                    StatoVuoto(stringResource(R.string.faccende_nessuna))
                    Text(
                        text = spiegaFaccende(p, nomeFiglio),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spazi.s),
                    )
                }
            }
        }

        if (gruppi.daFare.isNotEmpty()) {
            item(key = "da-fare-titolo") { SopraTitolo(stringResource(R.string.faccende_da_fare)) }
            items(gruppi.daFare, key = { "da-fare-${it.id}" }) { faccenda ->
                RigaDaFare(faccenda, io, adesso, invio, onTogli = { onTogli(faccenda) })
            }
        }

        if (gruppi.fatte.isNotEmpty()) {
            item(key = "fatte-titolo") { SopraTitolo(stringResource(R.string.faccende_fatte)) }
            items(gruppi.fatte, key = { "fatta-${it.id}" }) { faccenda ->
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

        // I lavori tolti, chiusi in una sezione che si apre (resta come la si lascia).
        if (gruppi.annullate.isNotEmpty()) {
            item(key = "annullati") {
                SezioneEspandibile(
                    titolo = stringResource(R.string.faccende_annullate),
                    conteggio = gruppi.annullate.size,
                    chiave = "lavori-tolti",
                ) {
                    gruppi.annullate.forEach { faccenda -> RigaAnnullata(faccenda, io) }
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

/** Il titolo e la nota di un lavoro: le parole del genitore che l'ha dato. */
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

/** Un lavoro da fare: chi l'ha dato e quando, da quando blocca, le bocciature, e "Togli". */
@Composable
private fun RigaDaFare(faccenda: Faccenda, io: RiferimentoGenitore?, adesso: Instant, invio: Boolean, onTogli: () -> Unit) {
    val p = parole()
    CardNormale {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                TitoloENota(faccenda)
                val data = listOfNotNull(
                    testoDataDa(p, faccenda, io),
                    istanteServer(faccenda.creataTs)?.let { testoQuando(p, it) },
                ).joinToString(" · ")
                if (data.isNotEmpty()) RigaSottovoce(data)
                testoBloccaDalle(p, faccenda, adesso)?.let { RigaSottovoce(it) }
                righeBocciature(p, faccenda, io).forEach { RigaSottovoce(it) }
            }
            TextButton(onClick = onTogli, enabled = !invio) {
                Text(stringResource(R.string.faccenda_togli))
            }
        }
    }
}

/** Un lavoro fatto: quando è arrivata la foto, chi l'aveva dato, la foto e (entro 24 ore) "Boccia". */
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
    CardNormale {
        Column {
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
                FilaPulsanti(modifier = Modifier.padding(top = Spazi.s)) {
                    Button(onClick = onGuardaFoto) { Text(stringResource(R.string.faccenda_guarda_foto), maxLines = 1, softWrap = false) }
                    // "Boccia" ha lo stesso peso qui e sotto la foto (B37).
                    if (stato is Bocciabile.Si) {
                        OutlinedButton(onClick = onBoccia, enabled = !invio) {
                            Text(stringResource(R.string.faccenda_boccia), maxLines = 1, softWrap = false)
                        }
                    }
                }
            } else {
                RigaSottovoce(stringResource(R.string.faccenda_foto_cancellata))
            }
        }
    }
}

/** Un lavoro tolto: chi e quando. */
@Composable
private fun RigaAnnullata(faccenda: Faccenda, io: RiferimentoGenitore?) {
    val p = parole()
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spazi.xs)) {
        TitoloENota(faccenda, attenuato = true)
        RigaSottovoce(
            listOfNotNull(
                testoAnnullata(p, faccenda, io),
                istanteServer(faccenda.chiusaTs)?.let { testoQuando(p, it) },
            ).joinToString(" · "),
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
 * La foto a tutto schermo, su fondo nero fino ai bordi: si allarga con due dita
 * (fino a 5 volte) e si sposta senza uscire dallo schermo. Il titolo sta al
 * massimo su 2 righe. Sotto, quando è arrivata, quanto resta per bocciarla e
 * "Boccia". La foto è in memoria e basta: non c'è un "salva", e non va nella galleria.
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
    Dialog(
        onDismissRequest = onChiudi,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = onChiudi) {
                        Icon(Icons.Filled.Close, stringResource(R.string.foto_chiudi), tint = Color.White)
                    }
                    Text(
                        text = faccenda?.titolo.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = Spazi.l),
                    )
                }
                BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val immagine = foto.immagine
                    val larghezza = constraints.maxWidth.toFloat()
                    val altezza = constraints.maxHeight.toFloat()
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
                                            // (0.15) Lo spostamento resta dentro i bordi: la foto
                                            // ingrandita non esce mai dallo schermo (B22).
                                            spostamento = if (scala == 1f) Offset.Zero else limitaSpostamento(spostamento + pan, scala, larghezza, altezza)
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
                // Ora e "Boccia" solo se la foto sullo schermo è quella che il lavoro ha
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
                            // Stesso peso della lista (B37): a contorno, bianco sul nero.
                            OutlinedButton(
                                onClick = { onBoccia(faccenda) },
                                enabled = !invio,
                                border = BorderStroke(1.dp, Color.White),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
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
}
