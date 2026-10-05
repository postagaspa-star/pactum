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
import androidx.compose.material.icons.filled.Search
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
import eu.stgm.pactum.design.MenuAzioni
import eu.stgm.pactum.design.VoceMenu
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
    val fotoViste by vm.fotoViste.collectAsStateWithLifecycle()
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
    // (0.17) La domanda di "svolto" non resta aperta cambiando scheda: tornando, si ricontrolla tutto.
    var svoltoId by remember { mutableStateOf<Long?>(null) }
    // (0.17) La ricerca nello storico: il testo resta ruotando e cambiando scheda.
    var cercato by rememberSaveable { mutableStateOf("") }
    // Si cerca ~300 ms dopo l'ultima lettera (ogni lettera nuova riparte da capo), e
    // di nuovo cambiando figlio; un testo vuoto chiude subito la ricerca.
    LaunchedEffect(figlioId, famiglia.pronta, cercato) {
        if (!famiglia.pronta) return@LaunchedEffect
        if (cercato.isNotBlank()) delay(ATTESA_RICERCA_MS)
        vm.cerca(figlioId, cercato)
    }

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
            EventoFaccende.Confermata -> p.testo(R.string.svolto_fatto)
            EventoFaccende.Modificata -> p.testo(R.string.modifica_fatto)
            EventoFaccende.NessunCambio -> p.testo(R.string.modifica_nessun_cambio)
            is EventoFaccende.Rifiuto -> {
                val nome = famiglia.figli.firstOrNull { it.id == evento.figlioId }?.nome
                messaggioRifiutoFaccende(p, evento.codice, evento.gesto, nome)
            }
        }
        cornice.messaggi.mostra(messaggio)
    }

    val faccende = stato.faccende.takeIf { stato.di(figlioId) }
    val ricerca = stato.ricerca?.takeIf { it.figlioId == figlioId && cercato.isNotBlank() }
    // Un lavoro per id: dall'elenco o (per i vecchi) dai risultati della ricerca.
    val trovaLavoro: (Long?) -> Faccenda? = { id ->
        faccende?.firstOrNull { it.id == id } ?: ricerca?.risultati?.firstOrNull { it.id == id }
    }
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
                    conModifiche = stato.conModifiche,
                    fotoViste = fotoViste,
                    cercato = cercato,
                    ricerca = ricerca,
                    onCerca = { cercato = it.take(MASSIMO_RICERCA) },
                    onDai = { cornice.apri(Pagina.DaiLavori) },
                    onModifica = { cornice.apri(Pagina.ModificaLavoro(it.id)) },
                    onTogli = { togliId = it.id },
                    onGuardaFoto = { vm.apriFoto(it.id, it.fotoTs) },
                    onSvolto = { svoltoId = it.id },
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
            faccenda = trovaLavoro(foto.faccendaId),
            io = famiglia.io,
            adesso = adesso,
            invio = stato.invio,
            conConferma = stato.conModifiche,
            onChiudi = vm::chiudiFoto,
            onRiprova = { vm.apriFoto(foto.faccendaId, foto.fotoTs) },
            onBoccia = { bocciaId = it.id },
            onSvolto = { svoltoId = it.id },
        )
    }

    // (0.17) "Segna come svolto": una domanda breve, non si torna indietro. Vale solo
    // finché la foto guardata è quella di adesso: se l'elenco dice un'altra foto (bocciata
    // e rifatta), la domanda si chiude. Il server lo ricontrolla comunque (`foto_cambiata`).
    val daConfermare = trovaLavoro(svoltoId)
    val ancoraConfermabile = daConfermare != null && azioniFatto(
        daConfermare,
        adesso,
        vista = daConfermare.fotoTs?.let { ChiaveFoto(daConfermare.id, it) in fotoViste } == true,
        conConferma = stato.conModifiche,
    ).principale == PulsanteFatto.SEGNA_SVOLTO
    LaunchedEffect(svoltoId, ancoraConfermabile) {
        if (svoltoId != null && !ancoraConfermabile) svoltoId = null
    }
    daConfermare?.takeIf { ancoraConfermabile }?.let { faccenda ->
        AlertDialog(
            onDismissRequest = { svoltoId = null },
            title = { Text(stringResource(R.string.svolto_titolo, faccenda.titolo)) },
            text = if (bocciabile(faccenda, adesso) is Bocciabile.Si) {
                { Text(stringResource(R.string.svolto_testo)) }
            } else {
                null
            },
            confirmButton = {
                // Spento mentre un'altra azione sta mandando: il tocco non va perso.
                Button(
                    enabled = !stato.invio,
                    onClick = {
                        svoltoId = null
                        // La foto guardata (è quella di adesso: se no il pulsante non c'era).
                        vm.conferma(figlioId, faccenda, fotoVista = faccenda.fotoTs)
                    },
                ) {
                    Text(stringResource(R.string.faccenda_segna_svolto))
                }
            },
            dismissButton = {
                TextButton(onClick = { svoltoId = null }) { Text(stringResource(R.string.azione_annulla)) }
            },
        )
    }

    trovaLavoro(bocciaId)?.let { faccenda ->
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
    conModifiche: Boolean,
    fotoViste: Set<ChiaveFoto>,
    cercato: String,
    ricerca: StatoRicerca?,
    onCerca: (String) -> Unit,
    onDai: () -> Unit,
    onModifica: (Faccenda) -> Unit,
    onTogli: (Faccenda) -> Unit,
    onGuardaFoto: (Faccenda) -> Unit,
    onSvolto: (Faccenda) -> Unit,
    onBoccia: (Faccenda) -> Unit,
) {
    val p = parole()
    val io = famiglia.io
    val vista: (Faccenda) -> Boolean = { f -> f.fotoTs?.let { ChiaveFoto(f.id, it) in fotoViste } == true }
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
                RigaDaFare(
                    faccenda = faccenda,
                    io = io,
                    invio = invio,
                    conModifiche = conModifiche,
                    onModifica = { onModifica(faccenda) },
                    onTogli = { onTogli(faccenda) },
                )
            }
        }

        // (0.17) La ricerca, in cima ai lavori chiusi: cerca in TUTTA la storia.
        item(key = "cerca") { CampoRicerca(cercato, onCerca) }

        val azioniDi: (Faccenda) -> AzioniFatto = { azioniFatto(it, adesso, vista(it), conModifiche) }

        if (ricerca != null) {
            // Con una ricerca aperta, i risultati al posto di Fatti e Tolti.
            risultatiRicerca(
                ricerca = ricerca,
                io = io,
                invio = invio,
                azioniDi = azioniDi,
                adesso = adesso,
                onGuardaFoto = onGuardaFoto,
                onSvolto = onSvolto,
                onBoccia = onBoccia,
            )
        } else {
        if (gruppi.fatte.isNotEmpty()) {
            item(key = "fatte-titolo") { SopraTitolo(stringResource(R.string.faccende_fatte)) }
            items(gruppi.fatte, key = { "fatta-${it.id}" }) { faccenda ->
                RigaFatta(
                    faccenda = faccenda,
                    io = io,
                    adesso = adesso,
                    invio = invio,
                    azioni = azioniDi(faccenda),
                    onGuardaFoto = { onGuardaFoto(faccenda) },
                    onSvolto = { onSvolto(faccenda) },
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

/**
 * Un lavoro da fare: chi l'ha dato e quando, (0.17) l'ora del blocco SEMPRE (anche
 * a blocco partito), le bocciature, e il ⋯ con "Modifica" e "Togli".
 */
@Composable
private fun RigaDaFare(
    faccenda: Faccenda,
    io: RiferimentoGenitore?,
    invio: Boolean,
    conModifiche: Boolean,
    onModifica: () -> Unit,
    onTogli: () -> Unit,
) {
    val p = parole()
    CardNormale {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                TitoloENota(faccenda)
                testoOraBlocco(p, faccenda)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = Spazi.xs),
                    )
                }
                val data = listOfNotNull(
                    testoDataDa(p, faccenda, io),
                    istanteServer(faccenda.creataTs)?.let { testoQuando(p, it) },
                ).joinToString(" · ")
                if (data.isNotEmpty()) RigaSottovoce(data)
                righeBocciature(p, faccenda, io).forEach { RigaSottovoce(it) }
            }
            MenuAzioni(
                voci = listOfNotNull(
                    VoceMenu(stringResource(R.string.faccenda_modifica), onModifica, abilitata = !invio).takeIf { conModifiche },
                    VoceMenu(stringResource(R.string.faccenda_togli), onTogli, distruttiva = true, abilitata = !invio),
                ),
                descrizione = stringResource(R.string.faccenda_azioni, faccenda.titolo),
            )
        }
    }
}

/**
 * Un lavoro fatto: quando è arrivata la foto, chi l'aveva dato, e (0.17) il
 * pulsante giusto: "Guarda la foto" finché questo telefono non l'ha aperta, poi
 * "Segna come svolto"; confermato, chi l'ha confermato e niente più "Boccia". La
 * foto si apre anche toccando il lavoro.
 */
@Composable
private fun RigaFatta(
    faccenda: Faccenda,
    io: RiferimentoGenitore?,
    adesso: Instant,
    invio: Boolean,
    azioni: AzioniFatto,
    onGuardaFoto: () -> Unit,
    onSvolto: () -> Unit,
    onBoccia: () -> Unit,
    /** (0.17) Nei risultati della ricerca: lo stato e la data al posto della riga solita. */
    riassunto: String? = null,
) {
    val p = parole()
    val stato = bocciabile(faccenda, adesso)
    CardNormale(onClick = if (faccenda.foto) onGuardaFoto else null) {
        Column {
            TitoloENota(faccenda)
            val righe = riassunto ?: listOfNotNull(
                istanteServer(faccenda.fotoTs ?: faccenda.chiusaTs)?.let { p.testo(R.string.faccenda_foto_arrivata, alleQuando(p, it)) },
                testoDataDa(p, faccenda, io),
            ).joinToString(" · ")
            if (righe.isNotEmpty()) RigaSottovoce(righe)
            righeBocciature(p, faccenda, io).forEach { RigaSottovoce(it) }
            testoConfermato(p, faccenda, io)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = Spazi.xs),
                )
            }
            if (azioni.boccia && stato is Bocciabile.Si) {
                Text(
                    text = testoBocciabile(p, stato).orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = Spazi.xs),
                )
            }
            if (!faccenda.foto) RigaSottovoce(stringResource(R.string.faccenda_foto_cancellata))
            if (azioni.principale != PulsanteFatto.NESSUNO || azioni.boccia) {
                FilaPulsanti(modifier = Modifier.padding(top = Spazi.s)) {
                    when (azioni.principale) {
                        PulsanteFatto.GUARDA_FOTO ->
                            Button(onClick = onGuardaFoto) { Text(stringResource(R.string.faccenda_guarda_foto), maxLines = 1, softWrap = false) }
                        PulsanteFatto.SEGNA_SVOLTO ->
                            Button(onClick = onSvolto, enabled = !invio) {
                                Text(stringResource(R.string.faccenda_segna_svolto), maxLines = 1, softWrap = false)
                            }
                        PulsanteFatto.NESSUNO -> Unit
                    }
                    // "Boccia" ha lo stesso peso qui e sotto la foto (B37).
                    if (azioni.boccia) {
                        OutlinedButton(onClick = onBoccia, enabled = !invio) {
                            Text(stringResource(R.string.faccenda_boccia), maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}

/** (0.17) La barra "Cerca un lavoro", con la X per cancellare. */
@Composable
private fun CampoRicerca(testo: String, onCambia: (String) -> Unit) {
    OutlinedTextField(
        value = testo,
        onValueChange = onCambia,
        placeholder = { Text(stringResource(R.string.ricerca_campo)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = if (testo.isNotEmpty()) {
            {
                IconButton(onClick = { onCambia("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.ricerca_cancella))
                }
            }
        } else {
            null
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** (0.17) I risultati della ricerca: ogni lavoro con stato e data; i fatti coi loro pulsanti. */
private fun androidx.compose.foundation.lazy.LazyListScope.risultatiRicerca(
    ricerca: StatoRicerca,
    io: RiferimentoGenitore?,
    invio: Boolean,
    azioniDi: (Faccenda) -> AzioniFatto,
    adesso: Instant,
    onGuardaFoto: (Faccenda) -> Unit,
    onSvolto: (Faccenda) -> Unit,
    onBoccia: (Faccenda) -> Unit,
) {
    val risultati = ricerca.risultati
    when {
        ricerca.problema == ProblemaRicerca.SERVER_VECCHIO ->
            item(key = "ricerca-server") { RigaStato(stringResource(R.string.ricerca_server_vecchio)) }
        ricerca.problema == ProblemaRicerca.SENZA_RETE ->
            item(key = "ricerca-rete") { RigaStato(stringResource(R.string.ricerca_senza_rete)) }
        ricerca.problema == ProblemaRicerca.ERRORE ->
            item(key = "ricerca-errore") { RigaStato(stringResource(R.string.ricerca_errore)) }
        risultati == null ->
            item(key = "ricerca-caricamento") { Caricamento(testo = stringResource(R.string.ricerca_caricamento), centrato = false) }
        risultati.isEmpty() && !ricerca.caricamento ->
            item(key = "ricerca-vuota") { StatoVuoto(stringResource(R.string.ricerca_nessuno, ricerca.testo)) }
        else -> {
            items(risultati, key = { "trovato-${it.id}" }) { faccenda ->
                if (faccenda.stato == eu.stgm.pactum.genitore.dati.StatiFaccenda.FATTA) {
                    RigaFatta(
                        faccenda = faccenda,
                        io = io,
                        adesso = adesso,
                        invio = invio,
                        azioni = azioniDi(faccenda),
                        onGuardaFoto = { onGuardaFoto(faccenda) },
                        onSvolto = { onSvolto(faccenda) },
                        onBoccia = { onBoccia(faccenda) },
                        riassunto = testoRisultato(parole(), faccenda),
                    )
                } else {
                    RigaTrovata(faccenda)
                }
            }
            if (ricerca.altre) {
                item(key = "ricerca-altre") {
                    Text(
                        text = stringResource(R.string.ricerca_altre),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** (0.17) Un lavoro trovato che non è fatto (da fare o tolto): il titolo, lo stato e la data. */
@Composable
private fun RigaTrovata(faccenda: Faccenda) {
    CardNormale {
        TitoloENota(faccenda, attenuato = faccenda.stato == eu.stgm.pactum.genitore.dati.StatiFaccenda.ANNULLATA)
        RigaSottovoce(testoRisultato(parole(), faccenda))
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
    io: RiferimentoGenitore?,
    adesso: Instant,
    invio: Boolean,
    conConferma: Boolean,
    onChiudi: () -> Unit,
    onRiprova: () -> Unit,
    onBoccia: (Faccenda) -> Unit,
    onSvolto: (Faccenda) -> Unit,
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
                    // La foto è sullo schermo: è guardata. (0.17) Confermato = niente "Boccia".
                    val azioni = azioniFatto(faccenda, adesso, vista = true, conConferma = conConferma)
                    Column(modifier = Modifier.fillMaxWidth().padding(Spazi.l)) {
                        istanteServer(faccenda.fotoTs)?.let {
                            Text(text = p.testo(R.string.faccenda_foto_arrivata, alleQuando(p, it)), color = Color.White)
                        }
                        val sotto = testoConfermato(p, faccenda, io) ?: testoBocciabile(p, stato).takeIf { azioni.boccia || stato is Bocciabile.Scaduta }
                        sotto?.let {
                            Text(text = it, color = Color.White, modifier = Modifier.padding(top = Spazi.xs))
                        }
                        val svolto = azioni.principale == PulsanteFatto.SEGNA_SVOLTO
                        if (svolto || azioni.boccia) {
                            // In fila se ci stanno, se no uno sotto l'altro (mai un testo tagliato).
                            FilaPulsanti(modifier = Modifier.fillMaxWidth().padding(top = Spazi.s)) {
                                if (svolto) {
                                    Button(
                                        onClick = { onSvolto(faccenda) },
                                        enabled = !invio,
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                                    ) {
                                        Text(stringResource(R.string.faccenda_segna_svolto), maxLines = 1, softWrap = false)
                                    }
                                }
                                if (azioni.boccia) {
                                    // Stesso peso della lista (B37): a contorno, bianco sul nero.
                                    OutlinedButton(
                                        onClick = { onBoccia(faccenda) },
                                        enabled = !invio,
                                        border = BorderStroke(1.dp, Color.White),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                    ) {
                                        Text(stringResource(R.string.faccenda_boccia), maxLines = 1, softWrap = false)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
