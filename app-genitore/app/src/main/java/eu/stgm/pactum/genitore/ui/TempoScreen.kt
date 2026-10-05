package eu.stgm.pactum.genitore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.design.AnelloCategorie
import eu.stgm.pactum.design.BarreGiorni
import eu.stgm.pactum.design.BloccoMedie
import eu.stgm.pactum.design.CellaMedia
import eu.stgm.pactum.design.GiornoGrafico
import eu.stgm.pactum.design.LegendaCategorie
import eu.stgm.pactum.design.BarraUso
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.design.RigaStato
import eu.stgm.pactum.design.StatoVuoto
import eu.stgm.pactum.design.Caricamento
import eu.stgm.pactum.design.Pillola
import eu.stgm.pactum.design.CardNormale
import eu.stgm.pactum.design.CardEvidenza
import eu.stgm.pactum.design.Tono
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.BonusGiorno
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.MediaPeriodo
import eu.stgm.pactum.genitore.dati.Medie
import eu.stgm.pactum.genitore.dati.SitiGiorno
import eu.stgm.pactum.genitore.dati.StatoBonus
import eu.stgm.pactum.genitore.dati.TipiRegola
import eu.stgm.pactum.genitore.dati.UsoGiorno
import kotlinx.coroutines.delay
import java.time.Instant

// Tempo risponde a una domanda sola: quanto ha usato il telefono (o il
// computer). (0.15) Dall'alto: il dispositivo (se più d'uno), UNA fila per
// scegliere il giorno (oggi visibile e scelto all'apertura), il totale del giorno
// con le categorie, gli 8 giorni con le medie, le app (dentro il patto, poi le
// prime 10 del resto e "Vedi tutte"), i siti (i primi 10 e "Vedi tutti") e i
// bonus degli ultimi 8 giorni (spostati qui dalla Panoramica).
// Niente terracotta qui dentro: il colore del patto vive SOLO nella striscia
// degli 8 giorni. Andare oltre un limite si dice a parole ("20 min oltre").
//
// (v3) I tempi sono PER DISPOSITIVO: con più dispositivi si sceglie quale
// guardare. Per un computer le voci sono i programmi (col nome leggibile) e i
// siti hanno i minuti, in ordine di minuti.

/** Ogni quanto si rileggono i tempi mentre la schermata è in primo piano. */
private const val INTERVALLO_RILETTURA_MS = 60_000L

/**
 * La scheda Tempo (contratto v2.2, `uso_recente`): i tempi d'uso giornalieri di
 * TUTTE le app del figlio — 8 giorni, totale in evidenza, il limite accanto dove
 * una regola esiste. Un giorno senza dati dice "nessun dato ricevuto", MAI uno
 * zero finto. Condivide il [FinestraViewModel] della Panoramica: una lettura sola
 * di GET /api/finestra serve entrambe.
 */
@Composable
fun TempoScreen(
    vm: FinestraViewModel = viewModel(),
    famigliaVm: FamigliaViewModel = viewModel(),
) {
    val cornice = LocalCornice.current
    val stato by vm.stato.collectAsStateWithLifecycle()
    val famiglia by famigliaVm.stato.collectAsStateWithLifecycle()
    val figlioId = famiglia.figlioId

    // Come la Panoramica: prima lettura a ogni ritorno in primo piano (e a ogni
    // cambio di figlio), poi rilettura periodica finché la schermata resta visibile.
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

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            BarraScheda(stringResource(R.string.tempo_titolo)) {
                famigliaVm.aggiorna()
                if (famiglia.pronta) vm.aggiorna(figlioId)
            }
        },
    ) { padding ->
        val finestra = stato.finestra.takeIf { stato.di(figlioId) }
        // (0.15) La scelta del figlio: fissa in cima mentre si carica, con un errore o
        // senza elenco (si cambia figlio anche quando i dati di uno non arrivano);
        // con l'elenco è la sua prima riga e scorre col resto.
        val conElenco = !famiglia.collegamentoNonValido && !stato.configurazioneMancante && finestra != null
        ConSceltaFiglio(famiglia, fissa = !conElenco, modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                // (0.15) 401: non è "non riesco a raggiungere il server" (B26).
                famiglia.collegamentoNonValido -> StatoVuoto(
                    centrato = true,
                    titolo = stringResource(R.string.collegamento_non_valido_titolo),
                    testo = stringResource(R.string.collegamento_non_valido),
                    azione = stringResource(R.string.azione_collega_di_nuovo),
                    onAzione = { cornice.apri(Pagina.Impostazioni(SezioneImpostazioni.COLLEGAMENTO)) },
                )

                stato.configurazioneMancante -> StatoVuoto(
                    centrato = true,
                    titolo = stringResource(R.string.config_mancante_titolo),
                    testo = stringResource(R.string.tempo_config_mancante),
                    azione = stringResource(R.string.azione_collega),
                    onAzione = { cornice.apri(Pagina.Impostazioni(SezioneImpostazioni.COLLEGAMENTO)) },
                )

                !stato.di(figlioId) || (stato.caricamento && finestra == null) ->
                    Caricamento(testo = stringResource(R.string.tempo_caricamento))

                finestra == null -> StatoVuoto(stringResource(R.string.tempo_errore), centrato = true)

                else -> ContenutoTempo(
                    finestra = finestra,
                    famiglia = famiglia,
                    mostraErrore = stato.errore,
                    ricevutaAlle = stato.ricevutaAlle,
                )
            }
        }
    }
}

@Composable
private fun ContenutoTempo(
    finestra: Finestra,
    famiglia: FamigliaViewModel.StatoFamiglia,
    mostraErrore: Boolean,
    ricevutaAlle: Instant?,
) {
    val figlioId = famiglia.figlioId
    val cornice = LocalCornice.current
    // (v3) Il dispositivo da guardare: quello scelto, se c'è ancora; altrimenti il
    // primo non scollegato. Su un server 0.7 ce n'è uno solo.
    val dispositivi = remember(finestra) { dispositiviDellaFinestra(finestra) }
    var dispositivoScelto by rememberSaveable(figlioId) { mutableStateOf<Long?>(null) }
    val dispositivo = dispositivoEffettivo(dispositivi, dispositivoScelto)
    // Un figlio v3 senza nessun dispositivo (o con tutti scollegati): si dice come
    // si comincia, col pulsante che porta alla Famiglia (dove si crea il codice).
    // Sempre con la scelta del figlio in cima: mai una pagina da cui non si esce.
    if (dispositivo == null ||
        (!famiglia.serverVecchio && famiglia.figlioScelto?.dispositivi?.isEmpty() == true && !finestraPerDispositivo(finestra))
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(Spazi.l),
            verticalArrangement = Arrangement.spacedBy(Spazi.m),
        ) {
            sceltaDelFiglio(famiglia)
            item(key = "senza-dispositivi") {
                StatoVuoto(
                    titolo = stringResource(R.string.nessun_dispositivo_titolo),
                    testo = stringResource(R.string.nessun_dispositivo),
                    azione = stringResource(R.string.famiglia_aggiungi_dispositivo),
                    onAzione = { cornice.apri(Pagina.Impostazioni(SezioneImpostazioni.FAMIGLIA)) },
                )
            }
        }
        return
    }
    val usoRecente = dispositivo.usoRecente
    val sitiRecenti = dispositivo.sitiRecenti
    val computer = dispositivo.computer
    // Le regole di questo dispositivo: servono ai limiti sui siti del computer.
    val regoleDelDispositivo = remember(finestra, dispositivo.id) {
        if (dispositivo.id == null) {
            emptyList()
        } else {
            finestra.regole.filter { (it.dispositivoId ?: it.dispositivo?.id) == dispositivo.id }
        }
    }

    // Il giorno scelto; null = oggi (l'ultima voce: il contratto ordina dal più
    // vecchio a oggi). Se la voce scelta sparisce al cambio di giornata, si ricade
    // su oggi invece di restare su un giorno fantasma. (0.9) Coi soli limiti che
    // valevano QUEL giorno.
    var giornoScelto by rememberSaveable(figlioId) { mutableStateOf<String?>(null) }
    val regolePerId = remember(finestra) { finestra.regole.associateBy { it.id } }
    val selezionato = (usoRecente.firstOrNull { it.giorno == giornoScelto } ?: usoRecente.lastOrNull())
        ?.let { giornoConLimitiValidi(it, regolePerId) }
    var tutteLeApp by rememberSaveable { mutableStateOf(false) }
    var tuttiISiti by rememberSaveable { mutableStateOf(false) }
    var spiegazioneSiti by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spazi.l),
        verticalArrangement = Arrangement.spacedBy(Spazi.m),
    ) {
        sceltaDelFiglio(famiglia)
        if (mostraErrore) {
            item(key = "dati-vecchi") {
                RigaStato(
                    ricevutaAlle?.let { stringResource(R.string.dati_fermi_alle, oraOppureDataOra(it)) }
                        ?: stringResource(R.string.tempo_dati_vecchi),
                )
            }
        }

        if (dispositivi.size > 1) {
            item(key = "dispositivi") {
                SelettoreDispositivi(
                    dispositivi = dispositivi,
                    scelto = dispositivo,
                    onScelta = { dispositivoScelto = it },
                )
            }
        }

        if (selezionato == null) {
            // Il server non ha mandato `uso_recente` (o è vuoto): niente da
            // inventare — si dice che non ci sono dati, punto. La sezione dei
            // siti resta comunque in fondo: vive di un campo suo.
            item(key = "nessun-dato") {
                StatoVuoto(
                    titolo = stringResource(R.string.tempo_nessuna_fotografia_titolo),
                    testo = stringResource(
                        if (computer) R.string.tempo_nessuna_fotografia_computer else R.string.tempo_nessuna_fotografia,
                    ),
                )
            }
        } else {
            // UNA fila per scegliere il giorno (B1: oggi visibile all'apertura).
            item(key = "giorni") {
                SelettoreGiorni(
                    giorni = usoRecente,
                    selezionato = selezionato,
                    onScelta = { giornoScelto = it },
                )
            }

            // Il totale del giorno scelto, e sotto come si divide.
            item(key = "giorno") {
                SchedaGiorno(
                    giorno = selezionato,
                    oggi = selezionato.giorno == usoRecente.last().giorno,
                    computer = computer,
                )
            }

            // Gli otto giorni (si guardano: il giorno si sceglie nella fila sopra) e le medie.
            item(key = "otto-giorni") {
                SchedaOttoGiorni(giorni = usoRecente, selezionato = selezionato.giorno, medie = dispositivo.medie)
            }

            if (selezionato.totaleMinuti != null) {
                // (v3) I limiti sui siti di un computer stanno nei siti del giorno.
                // (0.9) Solo quelli che valevano quel giorno.
                val sitiNelPatto = vociSitiNelPatto(
                    regoleDelDispositivo = regoleDelDispositivo.filter { limiteValidoIl(selezionato.giorno, it) },
                    giorno = selezionato,
                    siti = sitiRecenti?.firstOrNull { it.giorno == selezionato.giorno },
                    bonusDelGiorno = bonusDelGiorno(dispositivo.bonusGiornalieri, selezionato.giorno),
                )
                val elenco = elencoTempo(selezionato, sitiNelPatto)
                if (elenco.dentroIlPatto.isEmpty() && elenco.restoDellaGiornata.isEmpty()) {
                    item(key = "app-vuoto") {
                        StatoVuoto(stringResource(if (computer) R.string.tempo_app_vuoto_computer else R.string.tempo_app_vuoto))
                    }
                }
                if (elenco.dentroIlPatto.isNotEmpty()) {
                    item(key = "dentro-titolo") { SopraTitolo(stringResource(R.string.tempo_dentro_il_patto)) }
                    items(elenco.dentroIlPatto, key = { "dentro-${it.chiave}" }) { RigaDentroIlPatto(it) }
                }
                if (elenco.restoDellaGiornata.isNotEmpty()) {
                    val (visibili, nascoste) = primeVoci(elenco.restoDellaGiornata, VOCI_TEMPO_VISIBILI, tutteLeApp)
                    item(key = "resto-titolo") {
                        TitoloConAzione(
                            titolo = stringResource(R.string.tempo_resto_della_giornata),
                            azione = when {
                                nascoste > 0 -> stringResource(R.string.azione_vedi_tutte)
                                tutteLeApp -> stringResource(R.string.azione_mostra_meno)
                                else -> null
                            },
                            onAzione = { tutteLeApp = !tutteLeApp },
                        )
                    }
                    items(visibili, key = { "resto-${it.chiave}" }) { RigaRestoDellaGiornata(it, elenco.massimoDelGiorno) }
                }
            }
        }

        // I siti visitati del giorno SCELTO qui sopra. Se il server non manda il
        // campo (versione vecchia) la sezione non esiste proprio.
        if (!sitiRecenti.isNullOrEmpty()) {
            val ultimoGiorno = usoRecente.lastOrNull()?.giorno ?: sitiRecenti.last().giorno
            val giornoSiti = selezionato?.giorno ?: sitiRecenti.last().giorno
            sezioneSiti(
                siti = sitiRecenti.firstOrNull { it.giorno == giornoSiti },
                giorno = giornoSiti,
                oggi = giornoSiti == ultimoGiorno,
                computer = computer,
                tutti = tuttiISiti,
                onTutti = { tuttiISiti = !tuttiISiti },
                spiegazione = spiegazioneSiti,
                onSpiegazione = { spiegazioneSiti = !spiegazioneSiti },
            )
        }

        // (0.15) I bonus del dispositivo (spostati qui dalla Panoramica): quanti
        // minuti restano oggi e in settimana (solo se c'è un limite di tempo attivo:
        // il bonus allunga solo quelli), e i minuti di ciascuno degli 8 giorni.
        val residui = dispositivo.bonus.takeIf {
            !dispositivo.revocato && finestra.regole.any { r ->
                r.attiva && r.tipo == TipiRegola.LIMITE_TEMPO &&
                    (dispositivo.id == null || (r.dispositivoId ?: r.dispositivo?.id) == dispositivo.id)
            }
        }
        val strisciaBonus = dispositivo.bonusGiornalieri.takeIf { giorni -> giorni.any { it.minuti > 0 } }
        if (residui != null || strisciaBonus != null) {
            item(key = "bonus") { SezioneBonus(residui, strisciaBonus) }
        }
    }
}

/** Un titolo di blocco con, a destra, un'azione di testo ("Vedi tutte"). */
@Composable
private fun TitoloConAzione(titolo: String, azione: String?, onAzione: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = Spazi.s)) {
        SopraTitolo(titolo, modifier = Modifier.weight(1f))
        if (azione != null) TextButton(onClick = onAzione) { Text(azione) }
    }
}

/**
 * (v3) Quale dispositivo guardare: un chip per dispositivo, con l'icona del tipo.
 * Uno scollegato resta sceglibile (la sua storia c'è) e lo dice.
 */
@Composable
private fun SelettoreDispositivi(
    dispositivi: List<VistaDispositivo>,
    scelto: VistaDispositivo,
    onScelta: (Long?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spazi.s),
    ) {
        dispositivi.forEach { dispositivo ->
            val nome = nomeDelDispositivo(dispositivo)
            FilterChip(
                selected = dispositivo.id == scelto.id,
                onClick = { onScelta(dispositivo.id) },
                leadingIcon = { IconaDispositivo(dispositivo.tipo, modifier = Modifier) },
                label = {
                    Text(if (dispositivo.revocato) stringResource(R.string.dispositivo_chip_scollegato, nome) else nome)
                },
            )
        }
    }
}

/** Il nome di una voce: la categoria in italiano, l'app o il programma col nome leggibile. */
private fun nomeVoce(voce: VoceTempo): String =
    if (voce.categoria) etichettaCategoria(voce.chiave) else nomeLeggibile(voce.chiave, voce.nome)

/**
 * Una voce DENTRO IL PATTO: la barra è sul limite — l'unica scala che il ragazzo
 * si è dato — e resta `primary` anche oltre. Quanto oltre, lo dice il chip.
 * "Oltre" e barra contano sul limite di QUEL giorno (base + bonus concessi),
 * come li conta il figlio; il chip del limite resta quello base della regola.
 * Se il bonus di quella regola non si conosce (un sito, v3) "oltre" non si
 * calcola: meglio tacere che dire un numero sbagliato.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RigaDentroIlPatto(voce: VoceTempo) {
    val limite = voce.limite ?: return
    val limiteDelGiorno = voce.limiteDelGiorno ?: limite
    val oltre = if (voce.bonusNoto && voce.minutiNoti) minutiOltre(voce.minuti, limite, voce.bonus) else 0
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = nomeVoce(voce),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (voce.minutiNoti) {
                Text(
                    text = testoDurata(voce.minuti.toLong()),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = Spazi.s),
                )
            }
        }
        if (voce.minutiNoti) {
            Spacer(modifier = Modifier.height(Spazi.xs))
            BarraUso(minuti = voce.minuti, limite = limiteDelGiorno, massimoDelGiorno = limiteDelGiorno)
        }
        Spacer(modifier = Modifier.height(Spazi.xs))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spazi.s),
            verticalArrangement = Arrangement.spacedBy(Spazi.xs),
        ) {
            Pillola(stringResource(R.string.tempo_limite, testoDurata(limite.toLong())))
            if (oltre > 0) {
                Pillola(stringResource(R.string.tempo_oltre, testoDurata(oltre.toLong())))
            }
        }
        if (voce.parziale) {
            Text(
                text = stringResource(R.string.tempo_sito_non_leggibile),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spazi.xs),
            )
        }
    }
}

/** Una voce del RESTO DELLA GIORNATA: contesto, sulla scala dell'app più usata. */
@Composable
private fun RigaRestoDellaGiornata(voce: VoceTempo, massimoDelGiorno: Int) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = nomeVoce(voce),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = testoDurata(voce.minuti.toLong()),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spazi.s),
            )
        }
        Spacer(modifier = Modifier.height(Spazi.xs))
        BarraUso(minuti = voce.minuti, limite = null, massimoDelGiorno = massimoDelGiorno)
    }
}

// --- Siti visitati (contratto v2.3; v3 per il computer) ---------------------------
// Il genitore vede QUALI siti, mai cosa ci fa dentro. Tre leggi che questa
// sezione non può violare (contratto-api.md, "Siti visitati — limiti e patto
// etico"): solo domini; l'assenza di dati non diventa mai uno zero; la cecità
// dichiarata (`dns_cifrato`) è un DATO, quindi non è colorata come un guasto.
// E niente blocchi: qui non c'è, e non ci sarà, nessun bottone per vietare un
// sito — se un sito è un problema, se ne parla. (0.15) Le tre righe che lo
// spiegano diventano una; il resto dietro la "i".

private fun LazyListScope.sezioneSiti(
    siti: SitiGiorno?,
    giorno: String,
    oggi: Boolean,
    computer: Boolean,
    tutti: Boolean,
    onTutti: () -> Unit,
    spiegazione: Boolean,
    onSpiegazione: () -> Unit,
) {
    val domini = siti?.takeIf { it.totaleDomini != null }?.let(::sitiOrdinati).orEmpty()
    val (visibili, nascosti) = primeVoci(domini, VOCI_TEMPO_VISIBILI, tutti)
    item(key = "siti-titolo") {
        Column(modifier = Modifier.fillMaxWidth().padding(top = Spazi.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SopraTitolo(stringResource(R.string.siti_sezione_titolo), modifier = Modifier.weight(1f))
                IconButton(onClick = onSpiegazione) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = stringResource(R.string.siti_spiegazione),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = stringResource(if (computer) R.string.siti_contesto_breve_computer else R.string.siti_contesto_breve),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (spiegazione) RigaContestoSiti(computer)
        }
    }

    // La confessione di cecità viene PRIMA della lista: spiega i buchi di quello
    // che si sta per leggere. Neutra, mai rossa: non è un errore, è un dato.
    if (siti?.dnsCifrato == true) {
        item(key = "siti-dns") {
            RigaStato(stringResource(if (computer) R.string.siti_dns_cifrato_computer else R.string.siti_dns_cifrato))
        }
    }

    // `totale_domini: null` (o il giorno che manca del tutto) = nessun dato.
    // "Non lo so" non si traveste da "zero siti".
    val totale = siti?.totaleDomini
    if (siti == null || totale == null) {
        item(key = "siti-senza-dati") { SitiSenzaDati(giorno = giorno, oggi = oggi, computer = computer) }
        return
    }

    if (domini.isEmpty()) {
        item(key = "siti-vuoto") { StatoVuoto(stringResource(R.string.siti_vuoto)) }
        return
    }
    // Il riferimento della barra è il sito più richiesto (o più usato) del giorno:
    // un confronto tra pari dentro la giornata, mai una soglia.
    val perMinuti = sitiConMinuti(siti)
    val riferimento = if (perMinuti) domini.first().minuti ?: 0 else domini.first().visite
    items(visibili, key = { "sito-${it.dominio}" }) {
        RigaBarraSito(
            dominio = it.dominio,
            visite = it.visite,
            riferimento = riferimento,
            minuti = if (perMinuti) it.minuti ?: 0 else null,
        )
    }
    if (nascosti > 0 || tutti) {
        item(key = "siti-tutti") {
            TextButton(onClick = onTutti) {
                Text(stringResource(if (tutti) R.string.azione_mostra_meno else R.string.azione_vedi_tutti))
            }
        }
    }
    // La fotografia porta al massimo i 200 domini più richiesti, ma
    // `totale_domini` resta quello VERO: se la lista è tagliata lo si dice.
    val nonElencati = totale - domini.size
    if (nonElencati > 0 && (tutti || nascosti == 0)) {
        item(key = "siti-altri") {
            Text(
                text = pluralStringResource(R.plurals.siti_altri, nonElencati, nonElencati),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    istanteServer(siti.aggiornatoTs)?.let { fotografia ->
        item(key = "siti-aggiornati") {
            Text(
                text = stringResource(R.string.tempo_fotografia_delle, oraOppureDataOra(fotografia)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Cosa si vede e cosa no: le righe che tengono la sezione dentro il patto (dietro la "i"). */
@Composable
private fun RigaContestoSiti(computer: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(Spazi.xs), modifier = Modifier.padding(top = Spazi.s)) {
        Text(
            text = stringResource(if (computer) R.string.siti_contesto_computer else R.string.siti_contesto),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(if (computer) R.string.siti_contesto_numeri_computer else R.string.siti_contesto_numeri),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Tavola rotonda: niente esiste nella finestra del genitore che il figlio
        // non veda identico. Dirlo qui è metà del patto.
        Text(
            text = stringResource(if (computer) R.string.siti_stessa_lista_computer else R.string.siti_stessa_lista),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Giorno senza dati dei siti: si dice "nessun dato", mai zero. */
@Composable
private fun SitiSenzaDati(giorno: String, oggi: Boolean, computer: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        StatoVuoto(
            if (oggi) {
                stringResource(R.string.siti_nessun_dato_oggi)
            } else {
                stringResource(R.string.siti_nessun_dato_giorno, giornoBreve(giorno))
            },
        )
        Text(
            text = stringResource(
                if (computer) R.string.siti_nessun_dato_spiega_computer else R.string.siti_nessun_dato_spiega,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spazi.xs),
        )
    }
}

/**
 * La fila dei giorni, dal più vecchio a oggi (l'ultimo dice "oggi"), e l'UNICO
 * posto dove si sceglie il giorno. All'apertura è già scorsa fino in fondo: oggi,
 * quello scelto, si vede subito (B1). Ogni chip si tocca su almeno 48dp.
 */
@Composable
private fun SelettoreGiorni(
    giorni: List<UsoGiorno>,
    selezionato: UsoGiorno,
    onScelta: (String) -> Unit,
) {
    // Int.MAX_VALUE: la fila parte scorsa fino alla fine (lo scorrimento si ferma
    // da sé al massimo); dopo, ricorda dove l'ha lasciata il genitore.
    val scorrimento = rememberScrollState(initial = Int.MAX_VALUE)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scorrimento),
        horizontalArrangement = Arrangement.spacedBy(Spazi.s),
    ) {
        giorni.forEachIndexed { indice, giorno ->
            val oggi = indice == giorni.lastIndex
            FilterChip(
                selected = giorno.giorno == selezionato.giorno,
                onClick = { onScelta(giorno.giorno) },
                label = { Text(if (oggi) stringResource(R.string.tempo_chip_oggi) else giornoBreve(giorno.giorno)) },
            )
        }
    }
}

/**
 * La scheda del giorno: il totale del giorno scelto (etichetta e valore separati,
 * un solo numero grande), poi l'anello delle categorie con la sua legenda. Un
 * giorno senza dati non ha anello e lo dice a parole.
 */
@Composable
private fun SchedaGiorno(giorno: UsoGiorno, oggi: Boolean, computer: Boolean) {
    // Con la fetta "resto" (non categorizzato) la legenda somma sempre al totale.
    val fette = fetteDelGiorno(giorno)
    val p = parole()
    CardEvidenza(tono = Tono.Neutro) {
        Column {
            TotaleGiorno(giorno = giorno, oggi = oggi, computer = computer)
            val totale = giorno.totaleMinuti
            if (totale != null) {
                Spacer(modifier = Modifier.height(Spazi.l))
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AnelloCategorie(fette = fette, totaleMinuti = totale)
                }
                Spacer(modifier = Modifier.height(Spazi.l))
                if (fette.isEmpty()) {
                    Text(
                        text = stringResource(R.string.grafico_categorie_vuoto),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LegendaCategorie(
                        fette = fette,
                        durata = { testoDurata(p, it.toLong()) },
                        limite = { p.testo(R.string.tempo_limite, testoDurata(p, it.toLong())) },
                    )
                }
            }
        }
    }
}

/**
 * Il totale del giorno: "OGGI" sopra, il valore grande, e quando è arrivato il
 * dato — un "oggi 3 h" delle 14:00 non racconta la serata. Senza dati lo si
 * dice. (v3.3) Con un limite su tutto il dispositivo, sotto il valore il limite
 * come per le app. (0.11) Il tempo passato in sessione non è nel totale: se ce
 * n'è, una riga lo dice a parte.
 */
@Composable
private fun TotaleGiorno(giorno: UsoGiorno, oggi: Boolean, computer: Boolean) {
    SopraTitolo(
        if (oggi) {
            stringResource(R.string.tempo_etichetta_oggi)
        } else {
            stringResource(R.string.tempo_etichetta_giorno, giornoBreve(giorno.giorno))
        },
    )
    val totale = giorno.totaleMinuti
    if (totale == null) {
        Text(
            text = stringResource(R.string.tempo_nessun_dato),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = Spazi.xs),
        )
        Text(
            text = stringResource(if (computer) R.string.tempo_nessun_dato_spiega_computer else R.string.tempo_nessun_dato_spiega),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spazi.xs),
        )
    } else {
        Text(text = testoDurata(totale.toLong()), style = MaterialTheme.typography.displaySmall)
        val limiteTotale = voceTotale(giorno)
        if (limiteTotale != null) LimiteDelTotale(limiteTotale)
        testoInSessione(parole(), giorno.sessioniMinuti)?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = if (limiteTotale != null) Spazi.s else Spazi.xs),
            )
        }
        istanteServer(giorno.aggiornatoTs)?.let { fotografia ->
            Text(
                text = stringResource(R.string.tempo_fotografia_delle, oraOppureDataOra(fotografia)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spazi.xs),
            )
        }
    }
}

/**
 * (v3.3) Il limite su tutto il dispositivo, accanto al totale del giorno e detto
 * come quello di un'app DENTRO IL PATTO. Niente terracotta: quanto oltre si dice
 * a parole.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LimiteDelTotale(voce: VoceTempo) {
    val limite = voce.limite ?: return
    val limiteDelGiorno = voce.limiteDelGiorno ?: limite
    val oltre = minutiOltre(voce.minuti, limite, voce.bonus)
    Spacer(modifier = Modifier.height(Spazi.s))
    BarraUso(minuti = voce.minuti, limite = limiteDelGiorno, massimoDelGiorno = limiteDelGiorno)
    Spacer(modifier = Modifier.height(Spazi.s))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spazi.s),
        verticalArrangement = Arrangement.spacedBy(Spazi.xs),
    ) {
        Pillola(stringResource(R.string.tempo_limite, testoDurata(limite.toLong())))
        if (oltre > 0) Pillola(stringResource(R.string.tempo_oltre, testoDurata(oltre.toLong())))
    }
}

/** Gli otto giorni (si guardano; il giorno scelto è acceso) e le medie. */
@Composable
private fun SchedaOttoGiorni(giorni: List<UsoGiorno>, selezionato: String, medie: Medie?) {
    CardNormale {
        Column {
            SopraTitolo(stringResource(R.string.grafico_ultimi_giorni))
            Spacer(modifier = Modifier.height(Spazi.m))
            // (0.16) Ogni barra porta sopra il suo tempo: il totale di ogni giorno
            // si legge guardando (e TalkBack lo dice giorno per giorno).
            val p = parole()
            BarreGiorni(
                giorni = giorniGrafico(giorni),
                selezionato = selezionato,
                valore = { testoDurataBreve(p, it.toLong()) },
                descrizione = { descrizioneGiorno(p, it) },
            )
            // Le medie settimanale/mensile: nascoste se il server non le manda
            // (campo o sotto-oggetto null = niente da mostrare, mai uno zero finto).
            if (medie != null && (medie.settimana != null || medie.mese != null)) {
                Spacer(modifier = Modifier.height(Spazi.l))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(Spazi.l))
                BloccoMedie(celleTempi(parole(), medie))
            }
        }
    }
}

/**
 * I bonus del dispositivo: quanti minuti restano oggi e in settimana ([residui],
 * null se non c'è un limite di tempo attivo) e i minuti bonus di ciascuno degli 8
 * giorni ([giorni], null se sono tutti zero).
 */
@Composable
private fun SezioneBonus(residui: StatoBonus?, giorni: List<BonusGiorno>?) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = Spazi.s)) {
        SopraTitolo(stringResource(R.string.bonus_titolo))
        if (residui != null) {
            Text(
                text = stringResource(
                    R.string.bonus_residui,
                    residui.giorno.residui,
                    residui.giorno.tetto,
                    residui.settimana.residui,
                    residui.settimana.tetto,
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = Spazi.xs),
            )
        }
        if (giorni != null) {
            Text(
                text = stringResource(R.string.bonus_striscia_titolo),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spazi.s, bottom = Spazi.xs),
            )
            StrisciaBonus(giorni)
        }
    }
}

/**
 * I minuti bonus di ciascuno degli 8 giorni: le celle si dividono la larghezza
 * (niente misure fisse che escono dallo schermo: B7) e crescono col testo grande.
 */
@Composable
private fun StrisciaBonus(giorni: List<BonusGiorno>) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spazi.xs), modifier = Modifier.fillMaxWidth()) {
        giorni.forEachIndexed { indice, giorno ->
            val oggi = indice == giorni.lastIndex
            val forma = RoundedCornerShape(6.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (giorno.minuti > 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            forma,
                        )
                        .then(if (oggi) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, forma) else Modifier)
                        .padding(vertical = Spazi.xs),
                ) {
                    Text(
                        text = giorno.minuti.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        softWrap = false,
                        color = if (giorno.minuti > 0) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = if (oggi) stringResource(R.string.tempo_chip_oggi) else giorno.giorno.takeLast(2),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    softWrap = false,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** I giorni della settimana e del mese (v3.8: ultimi 7 e ultimi 30, oggi compreso). */
const val GIORNI_TOTALE_SETTIMANA = 7
const val GIORNI_TOTALE_MESE = 30

/**
 * (0.16) I numeri sotto le barre, come celle del grafico (core-design). Con il
 * `totale` del server (v3.8): "ULTIMI 7 GIORNI" / "ULTIMI 30 GIORNI" col totale
 * in grande, "giorni con dati: 6 su 7" quando non tutti i giorni avevano dati (i
 * giorni senza dati non sono zero), e la media più piccola sotto. Senza `totale`
 * (server prima della v3.8) le medie di prima, e basta. Un periodo senza dati
 * (null) non ha cella: mai uno zero finto.
 */
fun celleTempi(p: Parole, medie: Medie): List<CellaMedia> = listOfNotNull(
    medie.settimana?.let { cellaTempo(p, it, GIORNI_TOTALE_SETTIMANA, R.string.tempo_ultimi_7, R.string.media_settimana) },
    medie.mese?.let { cellaTempo(p, it, GIORNI_TOTALE_MESE, R.string.tempo_ultimi_30, R.string.media_mese) },
)

private fun cellaTempo(p: Parole, periodo: MediaPeriodo, giorniFinestra: Int, titoloTotale: Int, titoloMedia: Int): CellaMedia {
    val totale = periodo.totale
    return if (totale == null) {
        CellaMedia(
            etichetta = p.testo(titoloMedia),
            valore = testoDurata(p, periodo.minuti.toLong()),
            sotto = listOf(p.testo(R.string.media_su_giorni, periodo.giorni)),
        )
    } else {
        CellaMedia(
            etichetta = p.testo(titoloTotale),
            valore = testoDurata(p, totale.coerceAtLeast(0).toLong()),
            sotto = listOfNotNull(
                p.testo(R.string.tempo_giorni_con_dati, periodo.giorni, giorniFinestra)
                    .takeIf { periodo.giorni < giorniFinestra },
                // La durata non si spezza a metà ("3 h 33" / "min"): va a capo intera.
                p.testo(R.string.tempo_media_al_giorno, testoDurata(p, periodo.minuti.toLong()).replace(' ', '\u00A0')),
            ),
            grande = true,
        )
    }
}

/** La frase di un giorno del grafico per TalkBack: "03/10: 3 h 5 min", o senza dati. */
fun descrizioneGiorno(p: Parole, giorno: GiornoGrafico): String =
    p.testo(
        R.string.grafico_giorno_valore,
        giornoBreve(giorno.giorno),
        giorno.minuti?.let { testoDurata(p, it.toLong()) } ?: p.testo(R.string.stato_regola_senza_dati),
    )
