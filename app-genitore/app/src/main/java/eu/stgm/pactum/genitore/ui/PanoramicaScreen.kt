package eu.stgm.pactum.genitore.ui

import androidx.annotation.PluralsRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import eu.stgm.pactum.design.CardEvidenza
import eu.stgm.pactum.design.LegendaStriscia
import eu.stgm.pactum.design.RigaStato
import eu.stgm.pactum.design.RigaToccabile
import eu.stgm.pactum.design.Tono
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.design.ColoriPatto
import eu.stgm.pactum.design.GiornoPatto
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.design.StrisciaGiorni
import eu.stgm.pactum.design.contaGiorni
import eu.stgm.pactum.design.TitoloSezione
import eu.stgm.pactum.design.StatoVuoto
import eu.stgm.pactum.design.Caricamento
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.EventoFinestra
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.Impostazioni
import eu.stgm.pactum.genitore.dati.RegolaFinestra
import eu.stgm.pactum.genitore.sync.Vedetta
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate

// (0.15) La Panoramica risponde a UNA domanda: "com'è andata, e c'è qualcosa per
// me?". Dall'alto:
//  1. righe compatte SOLO se c'è qualcosa (richieste da decidere, blocco dei
//     lavori, un dispositivo silenzioso, avvisi spenti o in ritardo, dati vecchi),
//     ciascuna porta dove si sistema;
//  2. la card del patto: "5 su 7", la striscia con la legenda, il riepilogo, le
//     regole di oggi (una riga ciascuna, si toccano per il dettaglio) e "Manda un
//     segno";
//  3. i dispositivi, una riga ciascuno;
//  4. "Da guardare insieme": le prime 3 voci, poi "Vedi tutte";
//  5. la riga "Storico del patto".
// Le proposte, le sessioni, i lavori, i bonus e "Come funziona Pactum" stanno nel
// loro posto (Da decidere, Lavori, Tempo, Impostazioni).

/** Ogni quanto si rilegge la finestra mentre la schermata è in primo piano. */
private const val INTERVALLO_RILETTURA_MS = 60_000L

@Composable
fun PanoramicaScreen(
    vm: FinestraViewModel = viewModel(),
    famigliaVm: FamigliaViewModel = viewModel(),
    proposteVm: ProposteViewModel = viewModel(),
    verdettiVm: VerdettiViewModel = viewModel(),
) {
    val cornice = LocalCornice.current
    val stato by vm.stato.collectAsStateWithLifecycle()
    val famiglia by famigliaVm.stato.collectAsStateWithLifecycle()
    val proposte by proposteVm.stato.collectAsStateWithLifecycle()
    val verdetti by verdettiVm.stato.collectAsStateWithLifecycle()
    val figlioId = famiglia.figlioId

    // Prima lettura a ogni ritorno in primo piano (e a ogni cambio di figlio),
    // poi rilettura periodica finché la schermata resta visibile: "in contatto"
    // non può restare fermo per ore su un telefono lasciato acceso. Si aspetta
    // di sapere di quale figlio (famiglia pronta): mai una finestra di un figlio
    // sotto il nome di un altro.
    val cicloVita = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(cicloVita, figlioId, famiglia.pronta) {
        if (!famiglia.pronta) return@LaunchedEffect
        cicloVita.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.aggiorna(figlioId)
                delay(INTERVALLO_RILETTURA_MS)
            }
        }
    }

    val messaggioMandato = stringResource(R.string.segno_mandato)
    val messaggioGiaMandato = stringResource(R.string.segno_gia_mandato_avviso)
    val messaggioFallito = stringResource(R.string.segno_fallito)
    LaunchedEffect(stato.esitoSegno) {
        val messaggio = when (stato.esitoSegno) {
            FinestraViewModel.EsitoSegno.MANDATO -> messaggioMandato
            FinestraViewModel.EsitoSegno.GIA_MANDATO -> messaggioGiaMandato
            FinestraViewModel.EsitoSegno.FALLITO -> messaggioFallito
            null -> return@LaunchedEffect
        }
        // Consumato subito; la frase la mostra la radice (non si perde cambiando scheda).
        vm.consumaEsitoSegno()
        cornice.messaggi.mostra(messaggio)
    }

    // (0.10) Ogni finestra nuova si dice al ViewModel delle proposte: quando anche
    // la Panoramica ha i dati di dopo, una proposta chiusa non fa più da ponte.
    LaunchedEffect(stato.figlioId, stato.lettaAlle) {
        val letta = stato.lettaAlle ?: return@LaunchedEffect
        if (stato.richiesta) proposteVm.finestraLetta(stato.figlioId, letta)
    }

    val aggiorna = {
        famigliaVm.aggiorna()
        if (famiglia.pronta) vm.aggiorna(figlioId)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = { BarraScheda(stringResource(R.string.finestra_titolo), onAggiorna = aggiorna) },
    ) { padding ->
        // (0.15) La scelta del figlio: fissa in cima mentre si carica, con un errore o
        // senza elenco (si cambia figlio anche quando i dati di uno non arrivano);
        // con l'elenco è la sua prima riga e scorre col resto.
        val conElenco = !famiglia.collegamentoNonValido && !stato.configurazioneMancante && stato.finestra != null && stato.di(figlioId)
        ConSceltaFiglio(famiglia, fissa = !conElenco, modifier = Modifier.padding(padding).fillMaxSize()) {
            // Solo i dati DI QUESTO figlio: finché non arrivano, la rotella.
            val finestra = stato.finestra.takeIf { stato.di(figlioId) }
            when {
                // (0.13) 401: il collegamento di questo telefono non vale più (un
                // altro genitore l'ha tolto). Non è la rete, e i dati di prima non
                // si mostrano come se valessero.
                famiglia.collegamentoNonValido -> StatoVuoto(
                    centrato = true,
                    titolo = stringResource(R.string.collegamento_non_valido_titolo),
                    testo = stringResource(R.string.collegamento_non_valido),
                    azione = stringResource(R.string.azione_collega_di_nuovo),
                    onAzione = { cornice.apri(Pagina.Impostazioni(SezioneImpostazioni.COLLEGAMENTO)) },
                )

                !stato.di(figlioId) || (stato.caricamento && finestra == null && !stato.configurazioneMancante) ->
                    Caricamento(testo = stringResource(R.string.finestra_caricamento))

                // Primo avvio: manca il collegamento. Il pulsante porta dove si fa.
                stato.configurazioneMancante -> StatoVuoto(
                    centrato = true,
                    titolo = stringResource(R.string.config_mancante_titolo),
                    testo = stringResource(R.string.finestra_config_mancante),
                    azione = stringResource(R.string.azione_collega),
                    onAzione = { cornice.apri(Pagina.Impostazioni(SezioneImpostazioni.COLLEGAMENTO)) },
                )

                finestra == null -> StatoVuoto(stringResource(R.string.finestra_errore_nessun_dato), centrato = true)

                else -> ContenutoPanoramica(
                    finestra = finestra,
                    famiglia = famiglia,
                    errore = stato.errore,
                    ricevutaAlle = stato.ricevutaAlle,
                    // Lo stesso conto della barra e di "Da decidere" (stesse letture).
                    daDecidere = quanteDaDecidereDellaFinestra(
                        finestra = finestra,
                        giaChiuse = proposte.giaChiuse,
                        sessioniDecise = stato.sessioniDecise,
                        lettaAlle = stato.lettaAlle,
                        dichiarazioni = verdetti.dichiarazioni.takeIf { verdetti.di(figlioId) },
                    ),
                    segnoSpento = segnoGiaMandato(finestra.segnoOggi, stato.segnoMandatoIl, LocalDate.now()),
                    invioSegno = stato.invioSegno,
                    onMandaSegno = { vm.mandaSegno(figlioId) },
                )
            }
        }
    }
}

@Composable
private fun ContenutoPanoramica(
    finestra: Finestra,
    famiglia: FamigliaViewModel.StatoFamiglia,
    errore: Boolean,
    ricevutaAlle: Instant?,
    daDecidere: Int,
    segnoSpento: Boolean,
    invioSegno: Boolean,
    onMandaSegno: () -> Unit,
) {
    val cornice = LocalCornice.current
    val figlio = famiglia.figlioScelto.takeIf { !famiglia.serverVecchio }
    val regolePerId = remember(finestra) { finestra.regole.associateBy { it.id } }
    val giorni = remember(finestra.striscia) { giorniDaQuadretti(finestra.striscia) }
    // (v3) La finestra per dispositivo; su un server 0.7 uno solo, senza nome.
    val perDispositivo = finestraPerDispositivo(finestra)
    val dispositivi = remember(finestra) { dispositiviDellaFinestra(finestra) }
    val piuDispositivi = perDispositivo && dispositivi.size > 1
    // Un figlio v3 senza nessun dispositivo: si dice come si comincia.
    val senzaDispositivi = figlio != null && figlio.dispositivi.isEmpty() && !perDispositivo
    // I giorni si contano nel fuso del patto, non in quello di chi legge.
    val riepilogo = remember(finestra) {
        riepilogoPatto(
            dalServer = finestra.riepilogo,
            giorni = giorni,
            sforamenti = finestra.sforamentiRecenti,
            manomissioni = finestra.manomissioniRecenti,
            zona = FUSO_PATTO,
            oggi = LocalDate.now(FUSO_PATTO),
        )
    }
    val daGuardare = remember(finestra) {
        daGuardareInsieme(
            sforamenti = finestra.sforamentiRecenti,
            manomissioni = finestra.manomissioniRecenti,
            giorni = giorni,
            zona = FUSO_PATTO,
            oggi = LocalDate.now(FUSO_PATTO),
        )
    }
    // (0.9) Il giorno delle regole nella card del patto: oggi, l'ultimo della
    // striscia (dal più vecchio a oggi). Senza striscia (server vecchio) niente elenco.
    val oggiDelPatto = finestra.striscia.lastOrNull()?.data
    val regoleDiOggi = remember(finestra) { oggiDelPatto?.let { regoleDelGiorno(finestra, it) }.orEmpty() }

    // Le righe in cima: solo se c'è qualcosa.
    val context = LocalContext.current
    val impostazioni = remember { Impostazioni(context.applicationContext) }
    val inMemoria by Vedetta.ultimoControllo.collectAsState()
    val salvato by impostazioni.ultimoControlloAvvisi.collectAsState(initial = null)
    var avvisiAccesi by remember { mutableStateOf(Vedetta.avvisiAccesi(context)) }
    var inRete by remember { mutableStateOf(Vedetta.reteDisponibile(context)) }
    LifecycleResumeEffect(Unit) {
        avvisiAccesi = Vedetta.avvisiAccesi(context)
        inRete = Vedetta.reteDisponibile(context)
        onPauseOrDispose { }
    }
    val adesso = remember(finestra) { Instant.now() }
    val blocco = remember(finestra) { finestra.faccende?.let { statoBlocco(it, adesso, finestra.blocco) } }
    val fotoNuove = remember(finestra) { finestra.faccende?.let { fotoDaGuardare(it, adesso) } ?: 0 }
    val righe = righeInCima(
        daDecidere = daDecidere,
        blocco = blocco.takeIf { figlio != null },
        fotoDaGuardare = if (figlio != null) fotoNuove else 0,
        silenziosi = if (perDispositivo) dispositivi.filter { statoCanale(it) == StatoCanale.SILENTE } else emptyList(),
        avvisiAccesi = avvisiAccesi,
        ultimoControllo = listOfNotNull(inMemoria, salvato).maxOrNull()?.let(Instant::ofEpochMilli),
        adesso = Instant.now(),
        inRete = inRete,
        errore = errore,
        ricevutaAlle = ricevutaAlle,
    )

    var tuttiDaGuardare by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spazi.l),
        verticalArrangement = Arrangement.spacedBy(Spazi.m),
    ) {
        sceltaDelFiglio(famiglia)

        items(righe, key = ::chiaveRiga) { riga -> RigaInCimaVista(riga) }

        if (senzaDispositivi) {
            item(key = "senza-dispositivi") {
                StatoVuoto(
                    titolo = stringResource(R.string.nessun_dispositivo_titolo),
                    testo = stringResource(R.string.nessun_dispositivo),
                    azione = stringResource(R.string.famiglia_aggiungi_dispositivo),
                    onAzione = { cornice.apri(Pagina.Impostazioni(SezioneImpostazioni.FAMIGLIA)) },
                )
            }
        }

        // --- La card del patto ---------------------------------------------------------
        if (finestra.regole.isEmpty()) {
            if (!senzaDispositivi) {
                item(key = "senza-regole") {
                    StatoVuoto(
                        titolo = stringResource(R.string.regole_vuoto_titolo),
                        testo = stringResource(R.string.regole_vuoto),
                    )
                }
            }
        } else {
            item(key = "patto") {
                CardPatto(
                    nomeFiglio = figlio?.nome.takeIf { !famiglia.piuFigli },
                    giorni = giorni,
                    riepilogo = riepilogo,
                    regoleDiOggi = regoleDiOggi,
                    giornoRegole = oggiDelPatto,
                    dispositivi = if (perDispositivo) dispositivi else emptyList(),
                    strisceDispositivi = if (perDispositivo) strisceDeiDispositivi(dispositivi) else emptyList(),
                    // POST /api/segno è v2.4 come la striscia: senza striscia il
                    // server è più vecchio, e il pulsante porterebbe a un errore.
                    mostraSegno = finestra.striscia.isNotEmpty(),
                    segnoSpento = segnoSpento,
                    invioSegno = invioSegno,
                    onMandaSegno = onMandaSegno,
                    onApriRegola = { cornice.apri(Pagina.Regola(it.id)) },
                )
            }
        }

        // --- I dispositivi -----------------------------------------------------------------
        if (!senzaDispositivi) {
            item(key = "dispositivi-titolo") { TitoloSezione(stringResource(R.string.sezione_dispositivi)) }
            items(dispositivi, key = { "dispositivo-${it.id}" }) { dispositivo ->
                RigaDispositivo(dispositivo, serverVecchio = !perDispositivo)
            }
        }

        // --- Da guardare insieme -------------------------------------------------------------
        // Vuota = non esiste: il "tutto bene" lo dice già la riga di riepilogo.
        if (daGuardare.isNotEmpty()) {
            val (visibili, nascoste) = primeVoci(daGuardare, VOCI_DA_GUARDARE_IN_PANORAMICA, tuttiDaGuardare)
            item(key = "da-guardare-titolo") {
                TitoloSezione(
                    testo = stringResource(R.string.sezione_da_guardare),
                    azione = when {
                        tuttiDaGuardare -> stringResource(R.string.azione_mostra_meno)
                        nascoste > 0 -> stringResource(R.string.azione_vedi_tutte)
                        else -> null
                    },
                    onAzione = { tuttiDaGuardare = !tuttiDaGuardare },
                )
            }
            items(visibili, key = { "da-guardare-${it.genere}-${it.evento.id}-${it.evento.tsServer}" }) { voce ->
                RigaDaGuardare(
                    voce = voce,
                    regolePerId = regolePerId,
                    // Con più dispositivi si dice da quale viene il fatto.
                    dispositivo = if (piuDispositivi) {
                        nomeDispositivo(voce.evento.dispositivoId ?: dispositivoDellaRegola(voce.evento, regolePerId), dispositivi)
                    } else {
                        null
                    },
                )
            }
        }

        // --- Lo storico -------------------------------------------------------------------------
        item(key = "storico") {
            RigaToccabile(titolo = stringResource(R.string.sezione_storico), onClick = { cornice.apri(Pagina.Storico) })
        }
    }
}

/** Una riga in cima, col tocco che porta dove si guarda o si sistema. */
@Composable
private fun RigaInCimaVista(riga: RigaInCima) {
    val cornice = LocalCornice.current
    val p = parole()
    val apriAvvisi = { cornice.apri(Pagina.Impostazioni(SezioneImpostazioni.AVVISI)) }
    when (riga) {
        is RigaInCima.DaDecidere -> RigaStato(
            testo = pluralStringResource(R.plurals.riga_richieste_da_decidere, riga.quante, riga.quante),
            tono = Tono.Positivo,
            onClick = { cornice.vaiAScheda(Scheda.DA_DECIDERE) },
        )
        is RigaInCima.Blocco -> RigaStato(
            tono = if (riga.stato.attivo) Tono.Attenzione else Tono.Neutro,
            testo = listOfNotNull(
                testoStatoBlocco(p, riga.stato),
                riga.stato.daFare.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.riga_lavori_da_fare, it, it) },
            ).joinToString(" · "),
            onClick = { cornice.vaiAScheda(Scheda.LAVORI) },
        )
        is RigaInCima.FotoDaGuardare -> RigaStato(
            testo = pluralStringResource(R.plurals.riga_foto_da_guardare, riga.quante, riga.quante),
            onClick = { cornice.vaiAScheda(Scheda.LAVORI) },
        )
        is RigaInCima.Silenzioso -> {
            val istante = istanteServer(riga.dispositivo.statoSilenzio?.ultimoBattito)
            RigaStato(
                testo = if (istante != null) {
                    stringResource(R.string.silenzio_dispositivo_titolo, nomeDelDispositivo(riga.dispositivo), dalleQuando(p, istante))
                } else {
                    stringResource(R.string.silenzio_dispositivo_mai, nomeDelDispositivo(riga.dispositivo))
                },
            )
        }
        RigaInCima.AvvisiSpenti -> RigaStato(
            testo = stringResource(R.string.avvisi_spenti_panoramica),
            azione = stringResource(R.string.avvisi_risolvi),
            onAzione = apriAvvisi,
        )
        is RigaInCima.AvvisiInRitardo -> RigaStato(
            testo = stringResource(R.string.avvisi_controllo_vecchio, alleQuando(p, riga.ultimoControllo)),
            azione = stringResource(R.string.avvisi_risolvi),
            onAzione = apriAvvisi,
        )
        is RigaInCima.DatiVecchi -> RigaStato(
            testo = riga.ricevutaAlle?.let { stringResource(R.string.dati_fermi_alle, oraOppureDataOra(it)) }
                ?: stringResource(R.string.finestra_errore),
        )
    }
}

/**
 * La card del patto: com'è andata la parola data negli ultimi 8 giorni. Il numero
 * grande è il conteggio dei fatti ("5 su 7"), la stessa striscia che vede il
 * figlio, la riga di riepilogo, poi le regole di OGGI una per una (con lo stato a
 * parole: si toccano per il dettaglio), il "5 su 7" di ogni dispositivo quando
 * sono più d'uno, e in fondo "Manda un segno".
 */
@Composable
private fun CardPatto(
    nomeFiglio: String?,
    giorni: List<GiornoPatto>,
    riepilogo: RiepilogoPatto,
    regoleDiOggi: List<RegolaDelGiorno>,
    giornoRegole: String?,
    dispositivi: List<VistaDispositivo>,
    strisceDispositivi: List<VistaDispositivo>,
    mostraSegno: Boolean,
    segnoSpento: Boolean,
    invioSegno: Boolean,
    onMandaSegno: () -> Unit,
    onApriRegola: (RegolaFinestra) -> Unit,
) {
    CardEvidenza(tono = Tono.Positivo) {
        Column {
            nomeDaScrivere(nomeFiglio)?.let {
                Text(
                    text = stringResource(R.string.patto_di, it),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = Spazi.xs),
                )
            }
            SopraTitolo(stringResource(R.string.patto_ultimi_giorni), colore = LocalContentColor.current)
            if (giorni.isNotEmpty()) {
                val (mantenuti, conDati) = contaGiorni(giorni)
                val senzaDati = giorni.size - conDati
                Spacer(Modifier.height(Spazi.s))
                if (conDati > 0) {
                    Text(
                        text = stringResource(R.string.patto_su, mantenuti, conDati),
                        style = MaterialTheme.typography.displaySmall,
                    )
                    Text(
                        text = pluralStringResource(R.plurals.patto_giorni_dentro, conDati),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (senzaDati > 0) {
                        Text(
                            text = pluralStringResource(R.plurals.patto_senza_dati, senzaDati, senzaDati),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.patto_nessun_giorno_con_dati),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(Spazi.m))
                StrisciaGiorni(
                    giorni = giorni,
                    lato = 32.dp,
                    descrizione = descrizioneStriscia(giorni, R.plurals.striscia_descrizione),
                )
                // La legenda dei colori, una volta sola, sotto la striscia grande.
                LegendaStriscia(
                    mantenuta = stringResource(R.string.legenda_mantenuta),
                    fuoriRegola = stringResource(R.string.legenda_fuori_regola),
                    senzaDati = stringResource(R.string.legenda_senza_dati),
                    oggi = stringResource(R.string.legenda_oggi),
                    modifier = Modifier.padding(top = Spazi.s),
                )
            }
            Spacer(Modifier.height(Spazi.s))
            Text(text = testoRiepilogo(riepilogo), style = MaterialTheme.typography.bodyMedium)

            // (0.9) Oggi, regola per regola: anche quelle mantenute. Una riga ciascuna,
            // lo stato a parole; si tocca per il dettaglio della regola.
            if (giornoRegole != null && regoleDiOggi.isNotEmpty()) {
                HorizontalDivider(
                    color = LocalContentColor.current.copy(alpha = 0.25f),
                    modifier = Modifier.padding(top = Spazi.m, bottom = Spazi.xs),
                )
                RegoleDiOggi(regoleDiOggi, dispositivi, onApriRegola)
            }

            // (v3) Con più dispositivi, il "5 su 7" di ciascuno in una riga.
            if (strisceDispositivi.isNotEmpty()) {
                HorizontalDivider(
                    color = LocalContentColor.current.copy(alpha = 0.25f),
                    modifier = Modifier.padding(vertical = Spazi.s),
                )
                strisceDispositivi.forEach { RigaSuDispositivo(it) }
            }

            // Il gesto non poliziesco: un riconoscimento a testo fisso, uno al
            // giorno. Il genitore sa prima che cosa arriva al figlio.
            if (mostraSegno) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = Spazi.m)) {
                    OutlinedButton(
                        onClick = onMandaSegno,
                        enabled = !segnoSpento && !invioSegno,
                        border = BorderStroke(1.dp, LocalContentColor.current.copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = LocalContentColor.current),
                    ) {
                        Text(
                            stringResource(if (segnoSpento) R.string.segno_gia_mandato else R.string.segno_manda),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    if (!segnoSpento) {
                        Text(
                            text = stringResource(R.string.segno_cosa_arriva),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = Spazi.xs),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Le regole di oggi nella card del patto: una riga per regola con lo stato a
 * parole ("mantenuta", "fuori regola · 15 min oltre", "senza dati"), niente più
 * colonna di quadretti. Con più dispositivi (o con gli impegni) un'intestazione
 * piccola per gruppo.
 */
@Composable
private fun RegoleDiOggi(
    righe: List<RegolaDelGiorno>,
    dispositivi: List<VistaDispositivo>,
    onApriRegola: (RegolaFinestra) -> Unit,
) {
    val p = parole()
    val perId = righe.associateBy { it.regola.id }
    // Server 0.7 (nessun dispositivo): un elenco solo, senza intestazioni.
    val gruppi = if (dispositivi.isEmpty()) {
        listOf(GruppoRegole(GenereGruppo.ALTRE, null, righe.map { it.regola }))
    } else {
        raggruppaRegole(righe.map { it.regola }, dispositivi).filter { it.regole.isNotEmpty() }
    }
    val conIntestazioni = dispositivi.isNotEmpty() && gruppi.size > 1
    SopraTitolo(stringResource(R.string.patto_oggi), modifier = Modifier.padding(top = Spazi.xs), colore = LocalContentColor.current)
    gruppi.forEach { gruppo ->
        if (conIntestazioni) IntestazioneGruppo(gruppo)
        gruppo.regole.forEach { regola ->
            val riga = perId[regola.id] ?: return@forEach
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onApriRegola(riga.regola) }
                    .heightIn(min = 48.dp)
                    .padding(vertical = Spazi.xs),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = descrizioneRegola(riga.regola), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = testoStatoRegola(p, riga),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        }
    }
}

/** Il sopra-titolo di un gruppo: l'icona e il nome del dispositivo, o gli impegni. */
@Composable
internal fun IntestazioneGruppo(gruppo: GruppoRegole) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Spazi.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val dispositivo = gruppo.dispositivo
        when {
            gruppo.genere == GenereGruppo.DISPOSITIVO && dispositivo != null -> {
                IconaDispositivo(dispositivo.tipo, tinta = LocalContentColor.current)
                val nome = nomeDelDispositivo(dispositivo)
                SopraTitolo(
                    testo = if (dispositivo.revocato) stringResource(R.string.dispositivo_chip_scollegato, nome) else nome,
                    modifier = Modifier.padding(start = Spazi.s),
                    colore = LocalContentColor.current,
                )
            }
            gruppo.genere == GenereGruppo.IMPEGNI -> SopraTitolo(stringResource(R.string.gruppo_impegni), colore = LocalContentColor.current)
            else -> SopraTitolo(stringResource(R.string.gruppo_altre_regole), colore = LocalContentColor.current)
        }
    }
}

/** Il "5 su 7" di un dispositivo, in una riga, quando i dispositivi che contano sono più d'uno. */
@Composable
private fun RigaSuDispositivo(dispositivo: VistaDispositivo) {
    val giorni = giorniDaQuadretti(dispositivo.striscia)
    val (mantenuti, conDati) = contaGiorni(giorni)
    val nome = nomeDelDispositivo(dispositivo)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = Spazi.xs),
    ) {
        IconaDispositivo(dispositivo.tipo, tinta = LocalContentColor.current)
        Text(
            text = if (dispositivo.revocato) stringResource(R.string.dispositivo_chip_scollegato, nome) else nome,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(start = Spazi.s),
        )
        Text(
            text = if (conDati > 0) {
                stringResource(R.string.patto_su, mantenuti, conDati)
            } else {
                stringResource(R.string.stato_regola_senza_dati)
            },
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * Un dispositivo: l'icona del SUO tipo (un telefono spento ha l'icona del
 * telefono), il nome, e lo stato del contatto a parole con un pallino leggibile.
 * [serverVecchio] = server 0.7, un dispositivo solo: le frasi di allora.
 */
@Composable
private fun RigaDispositivo(dispositivo: VistaDispositivo, serverVecchio: Boolean) {
    val p = parole()
    val stato = statoCanale(dispositivo)
    val testo = if (serverVecchio) {
        testoStatoServerVecchio(dispositivo)
    } else {
        testoStatoCanale(p, stato, dispositivo.statoSilenzio, computer = dispositivo.computer)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        IconaDispositivo(dispositivo.tipo, modifier = Modifier.padding(top = Spazi.xs))
        Column(modifier = Modifier.weight(1f).padding(start = Spazi.m)) {
            Text(
                text = if (serverVecchio) stringResource(R.string.dispositivo_telefono) else nomeDelDispositivo(dispositivo),
                style = MaterialTheme.typography.bodyLarge,
                color = if (dispositivo.revocato) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.Top) {
                // Il pallino sta al centro della PRIMA riga della frase, a qualunque
                // misura del testo (la riga si misura in sp).
                val altezzaRiga = with(LocalDensity.current) { MaterialTheme.typography.bodyMedium.lineHeight.toDp() }
                Box(modifier = Modifier.height(altezzaRiga), contentAlignment = Alignment.Center) {
                    Box(modifier = Modifier.size(8.dp).background(colorePallino(stato), CircleShape))
                }
                Text(
                    text = testo,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Spazi.s),
                )
            }
        }
    }
}

/** Server 0.7: le frasi di allora ("In contatto — ultimo aggiornamento alle 15:10"). */
@Composable
private fun testoStatoServerVecchio(dispositivo: VistaDispositivo): String {
    val silenzio = dispositivo.statoSilenzio ?: return stringResource(R.string.dispositivo_stato_sconosciuto)
    val istante = istanteServer(silenzio.ultimoBattito)
    return when {
        silenzio.silente && istante != null -> stringResource(R.string.silenzio_allarme, oraOppureDataOra(istante))
        silenzio.silente -> stringResource(R.string.silenzio_mai)
        istante != null -> stringResource(R.string.silenzio_in_contatto, oraOppureDataOra(istante))
        else -> stringResource(R.string.dispositivo_in_contatto_senza_ora)
    }
}

/**
 * Il pallino dello stato: blu quando c'è contatto, grigio-blu quando tace, grigio
 * scuro (`outline`, contrasto vero sul bianco) per tutto il resto.
 */
@Composable
private fun colorePallino(stato: StatoCanale): Color = when (stato) {
    StatoCanale.IN_CONTATTO -> MaterialTheme.colorScheme.primary
    StatoCanale.SILENTE -> ColoriPatto.Silenzio
    else -> MaterialTheme.colorScheme.outline
}

/**
 * La frase che TalkBack legge al posto dei singoli quadretti. [frase] dice di
 * chi è la striscia: tutte le regole (la card del patto), un dispositivo o una
 * sola regola. Senza nessun giorno con dati si dicono solo i giorni senza dati.
 */
@Composable
internal fun descrizioneStriscia(giorni: List<GiornoPatto>, @PluralsRes frase: Int): String {
    val (mantenuti, conDati) = contaGiorni(giorni)
    val senzaDati = giorni.size - conDati
    val parteSenzaDati = pluralStringResource(R.plurals.patto_senza_dati, senzaDati, senzaDati)
    if (conDati == 0) return parteSenzaDati
    val base = pluralStringResource(frase, mantenuti, mantenuti, conDati)
    return if (senzaDati > 0) stringResource(R.string.elenco_due_parti, base, parteSenzaDati) else base
}

/** "Nessun giorno fuori regola · registrazione completa", oppure i conti. */
@Composable
private fun testoRiepilogo(riepilogo: RiepilogoPatto): String {
    val fuori = riepilogo.giorniFuoriRegola
    val buchi = riepilogo.interruzioni
    val parteFuori = if (fuori == 0) {
        stringResource(R.string.riepilogo_nessun_fuori_regola)
    } else {
        pluralStringResource(R.plurals.riepilogo_giorni_fuori_regola, fuori, fuori)
    }
    val parteBuchi = if (buchi == 0) {
        stringResource(R.string.riepilogo_registro_completo)
    } else {
        pluralStringResource(R.plurals.riepilogo_buchi, buchi, buchi)
    }
    return stringResource(R.string.riepilogo_due_parti, parteFuori, parteBuchi)
}

/**
 * Una riga di "Da guardare insieme": che cosa, e il giorno (lo stesso formato
 * per tutte le righe: "oggi", "ieri", "14/09"). (v3) Con più dispositivi, sopra
 * si dice da quale ([dispositivo]).
 */
@Composable
internal fun RigaDaGuardare(
    voce: VoceDaGuardare,
    regolePerId: Map<Long, RegolaFinestra>,
    dispositivo: String?,
) {
    val titolo = when (voce.genere) {
        GenereVoce.FUORI_REGOLA -> testoFuoriRegola(voce.evento, regolePerId)
        GenereVoce.INTERRUZIONE -> descrizioneBuco(voce.evento.dettagli)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        val sopra = listOfNotNull(dispositivo, testoGiorno(parole(), voce.giorno, LocalDate.now(FUSO_PATTO)))
        SopraTitolo(sopra.joinToString(" · "))
        Text(text = titolo, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun testoFuoriRegola(evento: EventoFinestra, regolePerId: Map<Long, RegolaFinestra>): String {
    val regola = campoLong(evento.dettagli, "regola_id")?.let { regolePerId[it] }
    return if (regola != null) {
        stringResource(R.string.sforamento_su_regola, descrizioneRegola(regola))
    } else {
        stringResource(R.string.sforamento_generico)
    }
}

/** Il dispositivo della regola di uno sforamento, se l'evento non lo dice da sé. */
internal fun dispositivoDellaRegola(evento: EventoFinestra, regolePerId: Map<Long, RegolaFinestra>): Long? =
    campoLong(evento.dettagli, "regola_id")
        ?.let { regolePerId[it] }
        ?.let { it.dispositivoId ?: it.dispositivo?.id }

private fun campoLong(oggetto: JsonObject, nome: String): Long? =
    (oggetto[nome] as? JsonPrimitive)?.content?.toLongOrNull()
