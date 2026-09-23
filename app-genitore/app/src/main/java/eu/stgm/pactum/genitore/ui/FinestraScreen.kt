package eu.stgm.pactum.genitore.ui

import androidx.annotation.PluralsRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.design.ColoriPatto
import eu.stgm.pactum.design.GiornoPatto
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.design.StrisciaGiorni
import eu.stgm.pactum.design.contaGiorni
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.BonusGiorno
import eu.stgm.pactum.genitore.dati.EventoFinestra
import eu.stgm.pactum.genitore.dati.Figlio
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.Impostazioni
import eu.stgm.pactum.genitore.dati.ModificaStorico
import eu.stgm.pactum.genitore.dati.RegolaFinestra
import eu.stgm.pactum.genitore.dati.StatoBonus
import eu.stgm.pactum.genitore.dati.StatoSilenzio
import eu.stgm.pactum.genitore.dati.TipiRegola
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate

// La finestra ha UN protagonista: il patto (tavola rotonda D1). Quanto ha usato
// il telefono vive nella scheda Tempo. Tre livelli, dall'alto:
//  1. il patto — la scheda eroe con la striscia aggregata, poi le regole;
//  2. da guardare insieme — fuori regola e interruzioni degli stessi 8 giorni, fusi;
//  3. la storia — dietro un tocco, chiusa di default.
// I colori del patto vivono in core-design (`ColoriPatto`): un solo rosso, in un
// solo posto — dentro la striscia degli 8 giorni.
//
// (v3) Il patto è del FIGLIO scelto in cima. Sotto la scheda del patto, una riga
// per dispositivo (telefono o computer) col suo stato; le regole raggruppate per
// dispositivo, poi gli Impegni della vita reale. Su un server 0.7 la finestra
// non ha `dispositivi` e tutto resta com'era.

/** Ogni quanto si rilegge la finestra mentre la schermata è in primo piano. */
private const val INTERVALLO_RILETTURA_MS = 60_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinestraScreen(
    notificheNonLette: Int,
    onApriNotifiche: () -> Unit,
    vm: FinestraViewModel = viewModel(),
    famigliaVm: FamigliaViewModel = viewModel(),
) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    val famiglia by famigliaVm.stato.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
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
    val ambito = rememberCoroutineScope()
    LaunchedEffect(stato.esitoSegno) {
        val messaggio = when (stato.esitoSegno) {
            FinestraViewModel.EsitoSegno.MANDATO -> messaggioMandato
            FinestraViewModel.EsitoSegno.GIA_MANDATO -> messaggioGiaMandato
            FinestraViewModel.EsitoSegno.FALLITO -> messaggioFallito
            null -> return@LaunchedEffect
        }
        // Consumato subito, e la conferma parte in uno scope suo: consumare
        // cambia la chiave e cancellerebbe questo effetto a metà snackbar.
        vm.consumaEsitoSegno()
        ambito.launch { snackbarHostState.showSnackbar(messaggio) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.finestra_titolo)) },
                actions = {
                    PulsanteNotifiche(notificheNonLette, onApriNotifiche)
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
            // Solo i dati DI QUESTO figlio: finché non arrivano, la rotella.
            val finestra = stato.finestra.takeIf { stato.di(figlioId) }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !stato.di(figlioId) || (stato.caricamento && finestra == null) ->
                        Caricamento(stringResource(R.string.finestra_caricamento))

                    stato.configurazioneMancante -> Centro {
                        StatoPrimaApertura(
                            titolo = stringResource(R.string.config_mancante_titolo),
                            testo = stringResource(R.string.finestra_config_mancante),
                            centrato = true,
                            modifier = Modifier.padding(horizontal = Spazi.xxl),
                        )
                    }

                    finestra == null -> Centro {
                        TestoCentrato(stringResource(R.string.finestra_errore_nessun_dato))
                    }

                    else -> ContenutoFinestra(
                        finestra = finestra,
                        figlio = famiglia.figlioScelto.takeIf { !famiglia.serverVecchio },
                        mostraErrore = stato.errore,
                        ricevutaAlle = stato.ricevutaAlle,
                        segnoSpento = segnoGiaMandato(
                            finestra.segnoOggi,
                            stato.segnoMandatoIl,
                            LocalDate.now(),
                        ),
                        invioSegno = stato.invioSegno,
                        onMandaSegno = { vm.mandaSegno(figlioId) },
                    )
                }
            }
        }
    }
}

/**
 * La campanella delle notifiche non lette: le notifiche si aprono da qui, e il
 * conto delle non lette sta solo qui. Il badge è nel blu dell'app, non nel
 * rosso `error` di Material: il rosso vive solo nella striscia dei giorni, e
 * `error` resta alla validazione dei form (§3.1, le tre leggi del colore).
 */
@Composable
private fun PulsanteNotifiche(nonLette: Int, onClick: () -> Unit) {
    val descrizione = if (nonLette > 0) {
        pluralStringResource(R.plurals.notifiche_non_lette, nonLette, nonLette)
    } else {
        stringResource(R.string.notifiche_titolo)
    }
    IconButton(onClick = onClick) {
        BadgedBox(
            badge = {
                if (nonLette > 0) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Text(testoBadge(nonLette))
                    }
                }
            },
        ) {
            Icon(Icons.Outlined.Notifications, contentDescription = descrizione)
        }
    }
}

@Composable
private fun ContenutoFinestra(
    finestra: Finestra,
    figlio: Figlio?,
    mostraErrore: Boolean,
    ricevutaAlle: Instant?,
    segnoSpento: Boolean,
    invioSegno: Boolean,
    onMandaSegno: () -> Unit,
) {
    // Per raccontare storico ed eventi serve la regola: la finestra porta TUTTE
    // le regole (anche eliminate), quindi la mappa è completa.
    val regolePerId = finestra.regole.associateBy { it.id }
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
    val gruppi = remember(finestra) {
        if (perDispositivo) raggruppaRegole(finestra.regole, dispositivi) else emptyList()
    }

    // "Ho capito" vale per sempre: sta in DataStore, non nello stato della
    // schermata. null = non ancora letto, e la scheda non lampeggia.
    val context = LocalContext.current
    val impostazioni = remember { Impostazioni(context.applicationContext) }
    val introChiusa by impostazioni.introChiusa.collectAsState(initial = null)
    val ambito = rememberCoroutineScope()

    var tuttiDaGuardare by rememberSaveable { mutableStateOf(false) }
    var storiaAperta by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spazi.l),
        verticalArrangement = Arrangement.spacedBy(Spazi.m),
    ) {
        if (mostraErrore) {
            item {
                RigaDatiVecchi(
                    ricevutaAlle?.let {
                        stringResource(R.string.dati_fermi_alle, oraOppureDataOra(it))
                    } ?: stringResource(R.string.finestra_errore),
                )
            }
        }

        if (perDispositivo) {
            // (v3) L'anomalia occupa spazio: un dispositivo che tace ha la sua
            // card in cima. Lo stato di tutti sta sotto la scheda del patto.
            val silenti = dispositivi.filter { statoCanale(it) == StatoCanale.SILENTE }
            items(silenti, key = { "silenzio-${it.id}" }) { CardSilenzioDispositivo(it) }
            if (ricevutaAlle != null) item { RigaEta(ricevutaAlle) }
        } else {
            // Un figlio senza dispositivi non "tace": non ha niente con cui parlare.
            // Il server gli manda comunque silente=true; qui si dice come si comincia.
            val silenzio = finestra.statoSilenzio.takeIf { !senzaDispositivi }
            if (silenzio != null) {
                item { RigaStato(silenzio, ricevutaAlle) }
            } else if (ricevutaAlle != null) {
                item { RigaEta(ricevutaAlle) }
            }
        }

        // La cornice: cos'è Pactum e perché non impone lui le regole.
        // Richiudibile: dopo averla letta non ingombra più, nemmeno dopo.
        if (introChiusa == false) {
            item {
                CardIntro(onChiudi = { ambito.launch { impostazioni.registraIntroChiusa() } })
            }
        }

        if (senzaDispositivi) {
            item {
                StatoPrimaApertura(
                    titolo = stringResource(R.string.nessun_dispositivo_titolo),
                    testo = stringResource(R.string.nessun_dispositivo),
                    modifier = Modifier.padding(vertical = Spazi.l),
                )
            }
        }

        // --- 1. Il patto --------------------------------------------------------
        if (finestra.regole.isEmpty()) {
            if (!senzaDispositivi) {
                item {
                    StatoPrimaApertura(
                        titolo = stringResource(R.string.regole_vuoto_titolo),
                        testo = stringResource(R.string.regole_vuoto),
                        modifier = Modifier.padding(vertical = Spazi.l),
                    )
                }
            }
        } else {
            item {
                SchedaPatto(
                    giorni = giorni,
                    riepilogo = riepilogo,
                    strisceDispositivi = if (perDispositivo) strisceDeiDispositivi(dispositivi) else emptyList(),
                    // POST /api/segno è v2.4 come la striscia: senza striscia il
                    // server è più vecchio, e il pulsante porterebbe a un errore.
                    mostraSegno = finestra.striscia.isNotEmpty(),
                    segnoSpento = segnoSpento,
                    invioSegno = invioSegno,
                    onMandaSegno = onMandaSegno,
                    // xxl tra l'eroe e il resto (§3.3): lo spacedBy ne mette già m.
                    modifier = Modifier.padding(bottom = Spazi.xxl - Spazi.m),
                )
            }
        }

        // (v3) Una riga per dispositivo: chi è, che tipo, com'è il contatto.
        if (perDispositivo) {
            item { BloccoDispositivi(dispositivi) }
        }

        if (finestra.regole.isNotEmpty()) {
            item { TitoloSezione(stringResource(R.string.sezione_regole)) }
            if (perDispositivo) {
                gruppi.forEach { gruppo -> gruppoDiRegole(gruppo) }
            } else {
                items(finestra.regole, key = { "regola-${it.id}" }) { SchedaRegola(it) }
                // Server 0.7: i bonus sono di tutto il patto, detti UNA volta in
                // coda alle regole. I residui solo se c'è una limite_tempo attiva
                // (il bonus allunga solo quelle), la striscia solo se negli 8
                // giorni ce n'è stato almeno uno.
                val residui = finestra.regole.any { it.attiva && it.tipo == TipiRegola.LIMITE_TEMPO }
                val strisciaBonus = finestra.bonusGiornalieri.any { it.minuti > 0 }
                if ((residui && finestra.bonus != null) || strisciaBonus) {
                    item {
                        SezioneBonus(
                            bonus = finestra.bonus.takeIf { residui },
                            bonusGiornalieri = finestra.bonusGiornalieri.takeIf { strisciaBonus },
                        )
                    }
                }
            }
        }

        // --- 2. Da guardare insieme ---------------------------------------------
        // Vuota = non esiste: il "tutto bene" lo dice già la riga di riepilogo.
        if (daGuardare.isNotEmpty()) {
            item { TitoloSezione(stringResource(R.string.sezione_da_guardare)) }
            val visibili = if (tuttiDaGuardare) {
                daGuardare
            } else {
                daGuardare.take(VOCI_DA_GUARDARE_VISIBILI)
            }
            item {
                ListaRighe(visibili) { voce ->
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
            val nascoste = daGuardare.size - VOCI_DA_GUARDARE_VISIBILI
            if (nascoste > 0) {
                item {
                    TextButton(onClick = { tuttiDaGuardare = !tuttiDaGuardare }) {
                        Text(
                            if (tuttiDaGuardare) {
                                stringResource(R.string.da_guardare_meno)
                            } else {
                                pluralStringResource(R.plurals.da_guardare_altri, nascoste, nascoste)
                            },
                        )
                    }
                }
            }
        }

        // --- 3. La storia -------------------------------------------------------
        if (finestra.storicoModifiche.isNotEmpty()) {
            item {
                TextButton(onClick = { storiaAperta = !storiaAperta }) {
                    Text(stringResource(R.string.sezione_storico))
                    Icon(
                        imageVector = if (storiaAperta) {
                            Icons.Filled.KeyboardArrowUp
                        } else {
                            Icons.Filled.KeyboardArrowDown
                        },
                        contentDescription = null,
                        modifier = Modifier.padding(start = Spazi.xs),
                    )
                }
            }
            if (storiaAperta) {
                item {
                    ListaRighe(finestra.storicoModifiche) {
                        RigaStorico(it, regolePerId, mostraDispositivo = piuDispositivi)
                    }
                }
            }
        }
    }
}

/** Il dispositivo della regola di uno sforamento, se l'evento non lo dice da sé. */
private fun dispositivoDellaRegola(evento: EventoFinestra, regolePerId: Map<Long, RegolaFinestra>): Long? =
    campoLong(evento.dettagli, "regola_id")
        ?.let { regolePerId[it] }
        ?.let { it.dispositivoId ?: it.dispositivo?.id }

/**
 * Un gruppo di regole (v3): il dispositivo (icona + nome) o gli Impegni, le sue
 * regole ciascuna con la sua striscia piccola, e i bonus DI QUEL dispositivo —
 * dalla v3 i bonus sono per dispositivo.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.gruppoDiRegole(gruppo: GruppoRegole) {
    val chiave = when (gruppo.genere) {
        GenereGruppo.DISPOSITIVO -> "gruppo-${gruppo.dispositivo?.id}"
        GenereGruppo.IMPEGNI -> "gruppo-impegni"
        GenereGruppo.ALTRE -> "gruppo-altre"
    }
    item(key = chiave) { IntestazioneGruppo(gruppo) }
    if (gruppo.regole.isEmpty()) {
        item(key = "$chiave-vuoto") { RigaVuota(stringResource(R.string.regole_nessuna_sul_dispositivo)) }
    }
    items(gruppo.regole, key = { "regola-${it.id}" }) { SchedaRegola(it) }
    val dispositivo = gruppo.dispositivo ?: return
    val residui = gruppo.regole.any { it.attiva && it.tipo == TipiRegola.LIMITE_TEMPO } &&
        dispositivo.bonus != null && !dispositivo.revocato
    val strisciaBonus = dispositivo.bonusGiornalieri.any { it.minuti > 0 }
    if (residui || strisciaBonus) {
        item(key = "$chiave-bonus") {
            SezioneBonus(
                bonus = dispositivo.bonus.takeIf { residui },
                bonusGiornalieri = dispositivo.bonusGiornalieri.takeIf { strisciaBonus },
            )
        }
    }
}

/** Il sopra-titolo di un gruppo: l'icona e il nome del dispositivo, o IMPEGNI. */
@Composable
private fun IntestazioneGruppo(gruppo: GruppoRegole) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Spazi.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val dispositivo = gruppo.dispositivo
        when {
            gruppo.genere == GenereGruppo.DISPOSITIVO && dispositivo != null -> {
                IconaDispositivo(dispositivo.tipo)
                val nome = nomeDelDispositivo(dispositivo)
                SopraTitolo(
                    testo = if (dispositivo.revocato) {
                        stringResource(R.string.dispositivo_chip_scollegato, nome).uppercase()
                    } else {
                        nome.uppercase()
                    },
                    modifier = Modifier.padding(start = Spazi.s),
                )
            }
            gruppo.genere == GenereGruppo.IMPEGNI -> SopraTitolo(stringResource(R.string.gruppo_impegni))
            else -> SopraTitolo(stringResource(R.string.gruppo_altre_regole))
        }
    }
}

/**
 * La cornice per il genitore: cos'è Pactum e perché non impone lui le regole.
 * È la spiegazione che finora esisteva solo nell'app del figlio. Richiudibile.
 */
@Composable
private fun CardIntro(onChiudi: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Spazi.l)) {
            Text(
                text = stringResource(R.string.intro_titolo),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(Spazi.s))
            Text(
                text = stringResource(R.string.intro_testo),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(Spazi.s))
            TextButton(
                onClick = onChiudi,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.intro_chiudi))
            }
        }
    }
}

/** L'età del dato, una riga sottovoce: "Aggiornato alle 15:12". */
@Composable
private fun RigaEta(ricevutaAlle: Instant) {
    Text(
        text = stringResource(R.string.finestra_aggiornata_alle, oraOppureDataOra(ricevutaAlle)),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Lo stato del canale col figlio, deciso dal flag `silente` del SERVER (server
 * 0.7: un dispositivo solo).
 *
 * La normalità non grida: "in contatto" è UNA riga (pallino + testo + età del
 * dato), niente card. L'anomalia occupa spazio: solo il silenzio è una Card
 * piena — nel grigio-blu del canale muto, MAI un rosso del patto, perché nove
 * volte su dieci è batteria scarica o rete.
 */
@Composable
private fun RigaStato(statoSilenzio: StatoSilenzio, ricevutaAlle: Instant?) {
    val istante = istanteServer(statoSilenzio.ultimoBattito)
    // L'età del dato: un "In contatto" senza data di raccolta sembrerebbe il
    // presente anche quando non lo è.
    val eta = ricevutaAlle?.let {
        stringResource(R.string.finestra_aggiornata_alle, oraOppureDataOra(it))
    }

    if (statoSilenzio.silente) {
        CardSilenzio(
            titolo = if (istante != null) {
                stringResource(R.string.silenzio_allarme, oraOppureDataOra(istante))
            } else {
                stringResource(R.string.silenzio_mai)
            },
            spiegazione = stringResource(R.string.silenzio_spiega),
            eta = eta,
        )
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
            Text(
                text = stringResource(
                    R.string.silenzio_in_contatto,
                    istante?.let { oraOppureDataOra(it) } ?: "—",
                ),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = Spazi.s),
            )
            if (eta != null) {
                Text(
                    text = eta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Spazi.s),
                )
            }
        }
    }
}

/**
 * (v3) La card del silenzio per UN dispositivo che tace: "Computer di camera:
 * nessun aggiornamento dalle 15:10". Un computer spento non arriva mai qui:
 * spento non è silente.
 */
@Composable
private fun CardSilenzioDispositivo(dispositivo: VistaDispositivo) {
    val p = parole()
    val nome = nomeDelDispositivo(dispositivo)
    val istante = istanteServer(dispositivo.statoSilenzio?.ultimoBattito)
    CardSilenzio(
        titolo = if (istante != null) {
            stringResource(R.string.silenzio_dispositivo_titolo, nome, dalleQuando(p, istante))
        } else {
            stringResource(R.string.silenzio_dispositivo_titolo, nome, "—")
        },
        spiegazione = stringResource(
            if (dispositivo.computer) R.string.silenzio_spiega_computer else R.string.silenzio_spiega,
        ),
        eta = null,
    )
}

/** La card piena del canale muto: grigio-blu, mai rosso. */
@Composable
private fun CardSilenzio(titolo: String, spiegazione: String, eta: String?) {
    val inchiostro = ColoriPatto.InchiostroSuSilenzio
    Card(
        colors = CardDefaults.cardColors(containerColor = ColoriPatto.Silenzio),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(Spazi.l)) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = inchiostro,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.padding(start = Spazi.m)) {
                Text(
                    text = titolo,
                    style = MaterialTheme.typography.titleMedium,
                    color = inchiostro,
                )
                Text(
                    text = spiegazione,
                    style = MaterialTheme.typography.bodyMedium,
                    color = inchiostro,
                    modifier = Modifier.padding(top = Spazi.xs),
                )
                if (eta != null) {
                    Text(
                        text = eta,
                        style = MaterialTheme.typography.labelSmall,
                        color = inchiostro,
                        modifier = Modifier.padding(top = Spazi.xs),
                    )
                }
            }
        }
    }
}

/**
 * (v3) I dispositivi del figlio, una riga ciascuno: icona del tipo, nome, e lo
 * stato del contatto detto a parole ("In contatto — ultimo aggiornamento alle
 * 15:10", "Spento dalle 23:10", "Nessun aggiornamento dalle 15:10"). Il
 * pallino è blu quando c'è contatto, grigio-blu quando tace, neutro altrimenti.
 */
@Composable
private fun BloccoDispositivi(dispositivi: List<VistaDispositivo>) {
    val p = parole()
    Column(modifier = Modifier.fillMaxWidth()) {
        SopraTitolo(stringResource(R.string.dispositivi_titolo))
        ListaRighe(dispositivi) { dispositivo ->
            val stato = statoCanale(dispositivo)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = Spazi.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconaDispositivo(dispositivo.tipo)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = Spazi.m),
                ) {
                    Text(
                        text = nomeDelDispositivo(dispositivo),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (dispositivo.revocato) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    // Il pallino sta sulla prima riga (bodyMedium: 20sp di riga),
                    // anche quando la frase va a capo.
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .size(8.dp)
                                .background(colorePallino(stato), CircleShape),
                        )
                        Text(
                            text = testoStatoCanale(p, stato, dispositivo.statoSilenzio),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = Spazi.s),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun colorePallino(stato: StatoCanale): Color = when (stato) {
    StatoCanale.IN_CONTATTO -> MaterialTheme.colorScheme.primary
    StatoCanale.SILENTE -> ColoriPatto.Silenzio
    else -> MaterialTheme.colorScheme.outlineVariant
}

/**
 * La scheda EROE: com'è andata la parola data negli ultimi 8 giorni.
 * Il numero grande è il conteggio dei fatti ("6 su 7"), la stessa striscia che
 * vede il figlio, e una riga che dice a parole ciò che la striscia disegna.
 * Nessuna serie: la serie è del figlio, non di chi guarda (tavola rotonda C6).
 *
 * (v3) Con più dispositivi, sotto la striscia del figlio c'è quella di ciascun
 * dispositivo ([strisceDispositivi]), piccola: la grande resta del figlio.
 *
 * Senza `striscia` (server vecchio) la scheda non va in errore: resta la riga
 * di riepilogo, e il segno si nasconde ([mostraSegno] false) perché quel server
 * non conosce POST /api/segno.
 */
@Composable
private fun SchedaPatto(
    giorni: List<GiornoPatto>,
    riepilogo: RiepilogoPatto,
    strisceDispositivi: List<VistaDispositivo>,
    mostraSegno: Boolean,
    segnoSpento: Boolean,
    invioSegno: Boolean,
    onMandaSegno: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(Spazi.l)) {
            SopraTitolo(stringResource(R.string.patto_ultimi_giorni))

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
                            text = pluralStringResource(
                                R.plurals.patto_senza_dati,
                                senzaDati,
                                senzaDati,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Spazi.xs),
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.patto_nessun_giorno_con_dati),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(Spazi.m))
                StrisciaGiorni(
                    giorni = giorni,
                    lato = 32.dp,
                    descrizione = descrizioneStriscia(giorni, R.plurals.striscia_descrizione),
                )
            }

            Spacer(Modifier.height(Spazi.m))
            Text(
                text = testoRiepilogo(riepilogo),
                style = MaterialTheme.typography.bodyMedium,
            )

            // (v3) Accanto alla striscia del figlio, quella di ciascun dispositivo.
            strisceDispositivi.forEach { dispositivo ->
                StrisciaDispositivo(dispositivo, modifier = Modifier.padding(top = Spazi.m))
            }

            // Il gesto non poliziesco: un riconoscimento a testo fisso, uno al
            // giorno. Il genitore sa prima che cosa arriva al figlio.
            if (mostraSegno) {
                Spacer(Modifier.height(Spazi.s))
                TextButton(
                    onClick = onMandaSegno,
                    enabled = !segnoSpento && !invioSegno,
                ) {
                    Text(
                        if (segnoSpento) {
                            stringResource(R.string.segno_gia_mandato)
                        } else {
                            stringResource(R.string.segno_manda)
                        },
                    )
                }
                Text(
                    text = stringResource(R.string.segno_cosa_arriva),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * La striscia piccola di un dispositivo, col suo "5 su 7": un dettaglio, non un
 * verdetto. Uno scollegato c'è finché i suoi giorni contano (strisceDeiDispositivi),
 * e lo dice accanto al nome.
 */
@Composable
private fun StrisciaDispositivo(dispositivo: VistaDispositivo, modifier: Modifier = Modifier) {
    val giorni = giorniDaQuadretti(dispositivo.striscia)
    val (mantenuti, conDati) = contaGiorni(giorni)
    val nome = nomeDelDispositivo(dispositivo)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconaDispositivo(dispositivo.tipo)
            Text(
                text = if (dispositivo.revocato) stringResource(R.string.dispositivo_chip_scollegato, nome) else nome,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = Spazi.s),
            )
            if (conDati > 0) {
                Text(
                    text = stringResource(R.string.patto_su, mantenuti, conDati),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(Spazi.xs))
        StrisciaGiorni(
            giorni = giorni,
            lato = 20.dp,
            mostraNumero = false,
            descrizione = descrizioneStriscia(giorni, R.plurals.striscia_descrizione_dispositivo),
        )
    }
}

/**
 * La frase che TalkBack legge al posto dei singoli quadretti. [frase] dice di
 * chi è la striscia: tutte le regole (la scheda del patto), un dispositivo o una
 * sola regola. Senza nessun giorno con dati si dicono solo i giorni senza dati:
 * "0 giorni su 0" non vuol dire niente.
 */
@Composable
private fun descrizioneStriscia(giorni: List<GiornoPatto>, @PluralsRes frase: Int): String {
    val (mantenuti, conDati) = contaGiorni(giorni)
    val senzaDati = giorni.size - conDati
    val parteSenzaDati = pluralStringResource(R.plurals.patto_senza_dati, senzaDati, senzaDati)
    if (conDati == 0) return parteSenzaDati
    val base = pluralStringResource(frase, mantenuti, mantenuti, conDati)
    return if (senzaDati > 0) "$base, $parteSenzaDati" else base
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
    return "$parteFuori · $parteBuchi"
}

/**
 * Una regola (§3.4): il tipo come sopra-titolo, la frase, la sua striscia
 * piccola da 20dp — un dettaglio, non un verdetto: il verdetto sta in cima.
 * Niente bonus qui: sono contatori del dispositivo, non di questa regola.
 */
@Composable
private fun SchedaRegola(regola: RegolaFinestra) {
    CardContenuto {
        Column(modifier = Modifier.padding(Spazi.l)) {
            SopraTitolo(
                testo = etichettaTipoRegola(regola.tipo),
                colore = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = descrizioneRegola(regola),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = Spazi.xs),
            )
            if (!regola.attiva) {
                Spacer(Modifier.height(Spazi.s))
                Etichetta(stringResource(R.string.regola_eliminata))
            }
            val giorni = giorniDaQuadretti(regola.semaforo)
            if (giorni.isNotEmpty()) {
                Spacer(Modifier.height(Spazi.m))
                StrisciaGiorni(
                    giorni = giorni,
                    lato = 20.dp,
                    mostraNumero = false,
                    descrizione = descrizioneStriscia(giorni, R.plurals.striscia_descrizione_regola),
                )
            }
        }
    }
}

/**
 * I bonus, detti una volta sola: quanti minuti restano oggi e in settimana
 * ([bonus], null se non c'è una limite_tempo attiva) e i minuti bonus di
 * ciascuno degli 8 giorni ([bonusGiornalieri], null se sono tutti zero).
 * Server 0.7: di tutto il patto; v3: di un dispositivo.
 */
@Composable
private fun SezioneBonus(bonus: StatoBonus?, bonusGiornalieri: List<BonusGiorno>?) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = Spazi.xs)) {
        if (bonus != null) {
            SopraTitolo(stringResource(R.string.bonus_titolo))
            Text(
                text = stringResource(
                    R.string.bonus_residui,
                    bonus.giorno.residui,
                    bonus.giorno.tetto,
                    bonus.settimana.residui,
                    bonus.settimana.tetto,
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = Spazi.xs),
            )
        }
        if (bonusGiornalieri != null) {
            if (bonus != null) Spacer(modifier = Modifier.height(Spazi.m))
            StrisciaBonus(bonusGiornalieri)
        }
    }
}

/** I minuti bonus di ciascuno degli 8 giorni (non per regola). */
@Composable
private fun StrisciaBonus(bonusGiornalieri: List<BonusGiorno>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SopraTitolo(stringResource(R.string.bonus_striscia_titolo))
        Spacer(modifier = Modifier.height(Spazi.s))
        Row(horizontalArrangement = Arrangement.spacedBy(Spazi.xs)) {
            bonusGiornalieri.forEachIndexed { indice, giorno ->
                val oggi = indice == bonusGiornalieri.lastIndex
                val forma = RoundedCornerShape(6.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(width = 34.dp, height = 26.dp)
                            .background(
                                if (giorno.minuti > 0) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                forma,
                            )
                            .then(
                                if (oggi) {
                                    // L'anello di "oggi" è primary, come nella
                                    // striscia: mai il nero di onSurface.
                                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, forma)
                                } else {
                                    Modifier
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = giorno.minuti.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (giorno.minuti > 0) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Text(
                        text = giorno.giorno.takeLast(2),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Una riga di "Da guardare insieme": che cosa, e quando. Uno sforamento col
 * `giorno` nei dettagli mostra QUEL giorno ("14/09"), non l'ora in cui è
 * arrivato al server: è il giorno che la striscia colora. (v3) Con più
 * dispositivi, sopra si dice da quale ([dispositivo]).
 */
@Composable
private fun RigaDaGuardare(
    voce: VoceDaGuardare,
    regolePerId: Map<Long, RegolaFinestra>,
    dispositivo: String?,
) {
    val titolo = when (voce.genere) {
        GenereVoce.FUORI_REGOLA -> testoFuoriRegola(voce.evento, regolePerId)
        GenereVoce.INTERRUZIONE -> testoBuco(voce.evento)
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spazi.m)) {
        if (dispositivo != null) {
            SopraTitolo(dispositivo.uppercase(), modifier = Modifier.padding(bottom = Spazi.xs))
        }
        Text(text = titolo, style = MaterialTheme.typography.bodyLarge)
        if (voce.giornoDichiarato) {
            Text(
                text = giornoBreve(voce.giorno.toString()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spazi.xs),
            )
        } else {
            TestoOrario(voce.evento.tsServer, Modifier.padding(top = Spazi.xs))
        }
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

// La stessa frase della notifica di quell'interruzione (Testi.kt), coi dettagli:
// (v3) "Pactum è stato chiuso sul computer (dalle 15:10 alle 15:40)".
@Composable
private fun testoBuco(evento: EventoFinestra): String = descrizioneBuco(evento.dettagli)

@Composable
private fun RigaStorico(
    modifica: ModificaStorico,
    regolePerId: Map<Long, RegolaFinestra>,
    mostraDispositivo: Boolean,
) {
    val titolo = when (modifica.azione) {
        "creazione" -> stringResource(R.string.storico_creazione)
        "modifica" -> if (modifica.direzione == "stringe") {
            stringResource(R.string.storico_modifica_stringe)
        } else {
            stringResource(R.string.storico_modifica_allenta)
        }
        "eliminazione" -> stringResource(R.string.storico_eliminazione)
        else -> modifica.azione
    }
    // La regola raccontata coi parametri DELLA modifica (dopo, o prima se
    // eliminata), non con quelli vigenti: lo storico racconta il passato
    // (descrizioneParametri, la stessa delle notifiche di modifica).
    val parametri = modifica.dopo ?: modifica.prima
    val regola = regolePerId[modifica.regolaId]
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spazi.m)) {
        val nomeDispositivo = regola?.dispositivo?.nome?.takeIf { mostraDispositivo && it.isNotBlank() }
        if (nomeDispositivo != null) {
            SopraTitolo(nomeDispositivo.uppercase(), modifier = Modifier.padding(bottom = Spazi.xs))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = titolo,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (modifica.concordata) {
                Etichetta(stringResource(R.string.storico_concordata))
            }
        }
        if (regola != null && parametri != null) {
            Text(
                text = descrizioneParametri(regola, parametri),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TestoOrario(modifica.tsServer, Modifier.padding(top = Spazi.xs))
    }
}

private fun campoLong(oggetto: JsonObject, nome: String): Long? =
    (oggetto[nome] as? JsonPrimitive)?.content?.toLongOrNull()
