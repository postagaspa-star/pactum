package eu.stgm.pactum.genitore

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.genitore.dati.Impostazioni
import eu.stgm.pactum.genitore.servizio.EsenzioneBatteria
import eu.stgm.pactum.genitore.servizio.VedettaService
import eu.stgm.pactum.genitore.sync.Vedetta
import eu.stgm.pactum.genitore.ui.CollegamentoViewModel
import eu.stgm.pactum.genitore.ui.FaccendeScreen
import eu.stgm.pactum.genitore.ui.FaccendeViewModel
import eu.stgm.pactum.genitore.ui.FamigliaViewModel
import eu.stgm.pactum.genitore.ui.FinestraScreen
import eu.stgm.pactum.genitore.ui.FinestraViewModel
import eu.stgm.pactum.genitore.ui.ProposteViewModel
import eu.stgm.pactum.genitore.ui.VerdettiViewModel
import eu.stgm.pactum.genitore.ui.ImpostazioniScreen
import eu.stgm.pactum.genitore.ui.NotificheScreen
import eu.stgm.pactum.genitore.ui.NotificheViewModel
import eu.stgm.pactum.genitore.ui.TempoScreen
import eu.stgm.pactum.genitore.ui.TurnoScreen
import eu.stgm.pactum.genitore.ui.theme.PactumTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    // La scheda su cui aprirsi quando si arriva da una notifica di sistema
    // (hook di navigazione). Attività a singleTop: onNewIntent la aggiorna
    // quando l'app è già viva. null = avvio normale, si parte dalla finestra.
    private val destinazioneRichiesta = mutableStateOf<String?>(null)

    // (v3) Il figlio di cui parla la notifica toccata: si sceglie lui in cima,
    // così l'avviso su Luca apre il patto di Luca. null = nessuna richiesta.
    private val figlioRichiesto = mutableStateOf<Long?>(null)

    // (0.13) La faccenda di cui la notifica toccata mostra la foto. null = nessuna.
    private val faccendaRichiesta = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // La destinazione vale solo per un tocco VERO sulla notifica. Due casi in
        // cui Android riconsegna lo stesso intent vecchio, extra compreso:
        // - la ricreazione dopo la morte del processo (savedInstanceState non
        //   null): la scheda giusta è quella salvata, non quella della notifica;
        // - l'apertura dai recenti (FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY): il
        //   genitore riapre l'app, non la notifica di ieri.
        // (removeExtra non basta: dopo la morte del processo l'intent torna intero.)
        val daiRecenti =
            ((intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (savedInstanceState == null && !daiRecenti) {
            destinazioneRichiesta.value = intent?.getStringExtra(EXTRA_DESTINAZIONE)
            figlioRichiesto.value = intent?.figlioDellaNotifica()
            faccendaRichiesta.value = intent?.faccendaDellaNotifica()
        }
        // Consumati comunque: una rotazione non deve rileggerli nello stesso processo.
        intent?.removeExtra(EXTRA_DESTINAZIONE)
        intent?.removeExtra(EXTRA_FIGLIO)
        intent?.removeExtra(EXTRA_FACCENDA)
        setContent {
            PactumTheme {
                GenitoreRoot(
                    destinazioneRichiesta = destinazioneRichiesta.value,
                    onDestinazioneConsumata = { destinazioneRichiesta.value = null },
                    figlioRichiesto = figlioRichiesto.value,
                    onFiglioConsumato = { figlioRichiesto.value = null },
                    faccendaRichiesta = faccendaRichiesta.value,
                    onFaccendaConsumata = { faccendaRichiesta.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        destinazioneRichiesta.value = intent.getStringExtra(EXTRA_DESTINAZIONE)
        figlioRichiesto.value = intent.figlioDellaNotifica()
        faccendaRichiesta.value = intent.faccendaDellaNotifica()
        // Consumati subito, come in onCreate: evita che una rotazione successiva
        // rilegga gli extra e ri-salti alla scheda della notifica.
        intent.removeExtra(EXTRA_DESTINAZIONE)
        intent.removeExtra(EXTRA_FIGLIO)
        intent.removeExtra(EXTRA_FACCENDA)
    }

    private fun Intent.figlioDellaNotifica(): Long? =
        if (hasExtra(EXTRA_FIGLIO)) getLongExtra(EXTRA_FIGLIO, -1L).takeIf { it >= 0 } else null

    private fun Intent.faccendaDellaNotifica(): Long? =
        if (hasExtra(EXTRA_FACCENDA)) getLongExtra(EXTRA_FACCENDA, -1L).takeIf { it >= 0 } else null

    companion object {
        const val EXTRA_DESTINAZIONE = "destinazione_iniziale"
        const val EXTRA_FIGLIO = "figlio"

        /** (0.13) La faccenda di cui aprire la foto (notifica `faccenda_fatta`). */
        const val EXTRA_FACCENDA = "faccenda"
        const val DEST_FINESTRA = "finestra"

        /** (0.13) La pagina delle faccende del figlio della notifica (sopra la Panoramica). */
        const val DEST_FACCENDE = "faccende"
        const val DEST_TEMPO = "tempo"
        const val DEST_TURNO = "turno"
        const val DEST_NOTIFICHE = "notifiche"

        // (0.9) La sezione "Avvisi del patto" delle Impostazioni: ci porta la
        // notifica fissa quando gli avvisi possono arrivare in ritardo o sono spenti.
        const val DEST_AVVISI = "avvisi"

        // Le destinazioni di prima (6 schede): le notifiche già nella tendina le
        // portano ancora nel loro PendingIntent. Restano riconosciute e finiscono
        // su "Proposte e conferme", così nessun tocco cade nel vuoto dopo l'aggiornamento.
        const val DEST_PROPOSTE = "proposte"
        const val DEST_VERDETTI = "verdetti"
    }
}

/** Le quattro voci della barra, con le icone disegnate per Pactum. */
private enum class Destinazione(@DrawableRes val icona: Int, @StringRes val etichetta: Int) {
    FINESTRA(R.drawable.ic_notifica_binocolo, R.string.scheda_finestra),
    TEMPO(R.drawable.ic_scheda_tempo, R.string.scheda_tempo),
    TURNO(R.drawable.ic_scheda_turno, R.string.scheda_turno),
    IMPOSTAZIONI(R.drawable.ic_scheda_impostazioni, R.string.scheda_impostazioni),
}

/** Ogni quanto si ricontano le notifiche non lette, per il badge. */
private const val INTERVALLO_NON_LETTE_MS = 60_000L

/** L'altezza della barra di Material 3 (NavigationBarTokens.ContainerHeight). */
private val ALTEZZA_BARRA_MATERIAL = 80.dp

/**
 * Quattro voci: guarda · misura · proposte e conferme · impostazioni (tavola rotonda
 * C4). La finestra è la casa; le notifiche non sono una scheda, sono la lista
 * che si apre dalla campanella della finestra, col conto delle non lette come
 * badge sulla campanella (e solo lì).
 */
@Composable
private fun GenitoreRoot(
    destinazioneRichiesta: String?,
    onDestinazioneConsumata: () -> Unit,
    figlioRichiesto: Long?,
    onFiglioConsumato: () -> Unit,
    faccendaRichiesta: Long? = null,
    onFaccendaConsumata: () -> Unit = {},
) {
    var destinazione by rememberSaveable { mutableStateOf(Destinazione.FINESTRA) }
    var notificheAperte by rememberSaveable { mutableStateOf(false) }
    // (0.13) La pagina delle faccende, sopra la Panoramica come le notifiche; con
    // "Dai faccende" già aperto, o con la foto di una faccenda.
    var faccendeAperte by rememberSaveable { mutableStateOf(false) }
    var daiSubito by rememberSaveable { mutableStateOf(false) }
    var fotoDaAprire by rememberSaveable { mutableStateOf<Long?>(null) }
    // (0.9) Le Impostazioni si aprono già sulla sezione "Avvisi del patto".
    var avvisiDaMostrare by rememberSaveable { mutableStateOf(false) }

    // Lo stesso ViewModel che usa la lista delle notifiche (scope dell'attività):
    // il badge e la lista contano le stesse cose.
    val notificheVm: NotificheViewModel = viewModel()
    val statoNotifiche by notificheVm.stato.collectAsStateWithLifecycle()
    val nonLette = statoNotifiche.notifiche.size
    // (v3) La famiglia, condivisa da tutte le schermate: si rilegge insieme al
    // badge, così i figli, i loro dispositivi e i loro numeri restano freschi.
    val famigliaVm: FamigliaViewModel = viewModel()
    val cicloVita = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(cicloVita) {
        cicloVita.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                notificheVm.aggiorna()
                famigliaVm.aggiorna()
                delay(INTERVALLO_NON_LETTE_MS)
            }
        }
    }

    // Arrivo da una notifica su un figlio: si sceglie lui, una volta sola.
    LaunchedEffect(figlioRichiesto) {
        if (figlioRichiesto == null) return@LaunchedEffect
        famigliaVm.scegli(figlioRichiesto)
        onFiglioConsumato()
    }

    // (0.9) Pactum sempre attivo: il servizio parte all'apertura dell'app e
    // appena il collegamento è salvato, solo se c'è (indirizzo + codice).
    val context = LocalContext.current
    val impostazioni = remember { Impostazioni(context.applicationContext) }
    val configurazione by impostazioni.configurazione.collectAsState(initial = null)
    val configurata = configurazione?.completa == true
    AvvioVedetta(configurata)

    // (0.13) Un altro collegamento (un altro server, un altro codice: anche quello
    // fatto col codice di 6 cifre mentre la pagina delle Impostazioni non c'era più)
    // è un'altra famiglia: tutte le schermate dimenticano quello che sapevano. Qui,
    // e non nella pagina che salva, così non si perde mai.
    val collegamentoVm: CollegamentoViewModel = viewModel()
    val finestraVm: FinestraViewModel = viewModel()
    val proposteVm: ProposteViewModel = viewModel()
    val verdettiVm: VerdettiViewModel = viewModel()
    val faccendeVm: FaccendeViewModel = viewModel()
    LaunchedEffect(configurazione) {
        val attuale = configurazione ?: return@LaunchedEffect
        if (!collegamentoVm.eUnAltroCollegamento(attuale)) return@LaunchedEffect
        famigliaVm.ricomincia()
        famigliaVm.aggiornaGenitori()
        finestraVm.dimentica()
        proposteVm.dimentica()
        verdettiVm.dimentica()
        notificheVm.dimentica()
        faccendeVm.dimentica()
    }

    RichiestaPermessoNotifiche()
    RichiestaEsenzioneBatteria(configurata)

    // Arrivo da una notifica: salta alla scheda giusta, una volta sola.
    LaunchedEffect(destinazioneRichiesta) {
        if (destinazioneRichiesta == null) return@LaunchedEffect
        when (destinazioneRichiesta) {
            MainActivity.DEST_TEMPO -> {
                destinazione = Destinazione.TEMPO
                notificheAperte = false
                faccendeAperte = false
            }
            MainActivity.DEST_TURNO,
            MainActivity.DEST_PROPOSTE,
            MainActivity.DEST_VERDETTI -> {
                destinazione = Destinazione.TURNO
                notificheAperte = false
                faccendeAperte = false
            }
            MainActivity.DEST_NOTIFICHE -> {
                destinazione = Destinazione.FINESTRA
                notificheAperte = true
                faccendeAperte = false
            }
            MainActivity.DEST_AVVISI -> {
                destinazione = Destinazione.IMPOSTAZIONI
                notificheAperte = false
                faccendeAperte = false
                avvisiDaMostrare = true
            }
            // (0.13) Le faccende del figlio della notifica, e la foto se è di una faccenda.
            MainActivity.DEST_FACCENDE -> {
                destinazione = Destinazione.FINESTRA
                notificheAperte = false
                faccendeAperte = true
                fotoDaAprire = faccendaRichiesta
            }
            // DEST_FINESTRA e qualunque valore sconosciuto: la casa.
            else -> {
                destinazione = Destinazione.FINESTRA
                notificheAperte = false
                faccendeAperte = false
            }
        }
        onDestinazioneConsumata()
        onFaccendaConsumata()
    }

    // Indietro chiude le notifiche (o le faccende) e torna alla finestra.
    BackHandler(enabled = notificheAperte) { notificheAperte = false }
    BackHandler(enabled = faccendeAperte && !notificheAperte) { faccendeAperte = false }

    // "Proposte e conferme" va a capo su 360 e su 411dp (è ~124dp, una voce ne
    // ha 84-97): tutte le etichette tengono due righe (minLines) così icone ed
    // etichette restano sulla stessa linea. Ma Material centra icona+etichette
    // in 80dp: con due righe la pillola dell'icona finiva a 4dp dal bordo alto
    // (12 di norma). La barra cresce di UNA riga d'etichetta: icone ed etichette
    // corte stanno dove le mette Material, la seconda riga ha il suo posto sotto.
    val altezzaVoce = with(LocalDensity.current) {
        ALTEZZA_BARRA_MATERIAL + MaterialTheme.typography.labelMedium.lineHeight.toDp()
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destinazione.entries.forEach { voce ->
                    NavigationBarItem(
                        modifier = Modifier.heightIn(min = altezzaVoce),
                        selected = destinazione == voce,
                        onClick = {
                            destinazione = voce
                            notificheAperte = false
                            faccendeAperte = false
                        },
                        icon = {
                            // L'etichetta sotto dice già il nome: l'icona tace.
                            // Niente badge qui: il conto delle non lette sta
                            // solo sulla campanella, da dove si aprono.
                            Icon(painterResource(voce.icona), contentDescription = null)
                        },
                        // Va a capo, centrata, mai troncata (v. altezzaVoce).
                        label = {
                            Text(
                                text = stringResource(voce.etichetta),
                                textAlign = TextAlign.Center,
                                minLines = 2,
                            )
                        },
                    )
                }
            }
        },
    ) { padding ->
        // consumeWindowInsets: il padding dello Scaffold esterno copre già le
        // barre di sistema; senza consumarlo, le TopAppBar degli Scaffold interni
        // riapplicherebbero l'inset della status bar (doppio spazio su Android 15).
        Box(modifier = Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
            when (destinazione) {
                Destinazione.FINESTRA -> when {
                    notificheAperte -> NotificheScreen(
                        onChiudi = { notificheAperte = false },
                        vm = notificheVm,
                        // (0.13) Da una notifica delle faccende: le faccende di quel figlio (e la foto).
                        onApriFaccende = { figlioId, faccendaId ->
                            if (figlioId != null) famigliaVm.scegli(figlioId)
                            fotoDaAprire = faccendaId
                            notificheAperte = false
                            faccendeAperte = true
                        },
                    )
                    faccendeAperte -> FaccendeScreen(
                        onChiudi = { faccendeAperte = false },
                        fotoRichiesta = fotoDaAprire,
                        onFotoRichiestaConsumata = { fotoDaAprire = null },
                        daiSubito = daiSubito,
                        onDaiSubitoConsumato = { daiSubito = false },
                    )
                    else -> FinestraScreen(
                        notificheNonLette = nonLette,
                        onApriNotifiche = { notificheAperte = true },
                        onApriAvvisi = {
                            destinazione = Destinazione.IMPOSTAZIONI
                            avvisiDaMostrare = true
                        },
                        onApriFaccende = { dai ->
                            daiSubito = dai
                            faccendeAperte = true
                        },
                    )
                }
                Destinazione.TEMPO -> TempoScreen()
                Destinazione.TURNO -> TurnoScreen()
                Destinazione.IMPOSTAZIONI -> ImpostazioniScreen(
                    mostraAvvisi = avvisiDaMostrare,
                    onAvvisiMostrati = { avvisiDaMostrare = false },
                )
            }
        }
    }
}

/**
 * Alla prima apertura (Android 13+): prima una spiegazione gentile del perché,
 * poi la richiesta di sistema. Una volta sola — se il genitore dice "non ora",
 * le novità restano visibili aprendo l'app.
 */
@Composable
private fun RichiestaPermessoNotifiche() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContext.current
    val ambito = rememberCoroutineScope()
    val impostazioni = remember { Impostazioni(context.applicationContext) }
    val richiestaFatta by impostazioni.richiestaNotificheFatta.collectAsState(initial = null)
    var mostraDialogo by rememberSaveable { mutableStateOf(false) }
    val lancioPermesso = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(richiestaFatta) {
        // null = DataStore non ancora letto: aspettare, non richiedere due volte.
        if (richiestaFatta == false && !Vedetta.puoAvvisare(context)) {
            mostraDialogo = true
        }
    }

    if (!mostraDialogo) return

    val chiudi: () -> Unit = {
        mostraDialogo = false
        ambito.launch { impostazioni.registraRichiestaNotificheFatta() }
    }
    AlertDialog(
        onDismissRequest = chiudi,
        title = { Text(stringResource(R.string.permesso_notifiche_titolo)) },
        text = { Text(stringResource(R.string.permesso_notifiche_testo)) },
        confirmButton = {
            TextButton(
                onClick = {
                    chiudi()
                    lancioPermesso.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
            ) {
                Text(stringResource(R.string.permesso_notifiche_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = chiudi) {
                Text(stringResource(R.string.permesso_notifiche_non_ora))
            }
        },
    )
}

/**
 * (0.9) Il servizio sempre attivo, a ogni ritorno dell'app in primo piano e
 * appena il collegamento c'è. Avviarlo quando gira già non fa niente: il loop è
 * uno solo. Con l'app davanti Android lo lascia sempre partire.
 */
@Composable
private fun AvvioVedetta(configurata: Boolean) {
    val context = LocalContext.current
    val cicloVita = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(configurata, cicloVita) {
        if (!configurata) return@LaunchedEffect
        cicloVita.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            VedettaService.avvia(context.applicationContext)
        }
    }
}

/**
 * (0.9) L'esenzione dalla batteria: una volta, con l'app collegata e gli
 * avvisi accesi, prima una spiegazione semplice del perché, poi la domanda di
 * Android (da rispondere con «Consenti»). "Non ora" non insiste: la stessa
 * richiesta resta nelle Impostazioni, sezione Avvisi del patto. Dopo la
 * richiesta del permesso notifiche, mai insieme: lo stato si rilegge a ogni
 * ritorno sull'app.
 */
@Composable
private fun RichiestaEsenzioneBatteria(configurata: Boolean) {
    val context = LocalContext.current
    val ambito = rememberCoroutineScope()
    val impostazioni = remember { Impostazioni(context.applicationContext) }
    val richiestaFatta by impostazioni.richiestaBatteriaFatta.collectAsState(initial = null)
    var daChiedere by remember { mutableStateOf(false) }
    LifecycleResumeEffect(configurata) {
        daChiedere = configurata && Vedetta.avvisiAccesi(context) && !EsenzioneBatteria.concessa(context)
        onPauseOrDispose { }
    }
    var mostraDialogo by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(richiestaFatta, daChiedere) {
        // null = DataStore non ancora letto: aspettare, non chiedere due volte.
        if (richiestaFatta == false && daChiedere) mostraDialogo = true
    }

    if (!mostraDialogo) return

    val chiudi: () -> Unit = {
        mostraDialogo = false
        ambito.launch { impostazioni.registraRichiestaBatteriaFatta() }
    }
    AlertDialog(
        onDismissRequest = chiudi,
        title = { Text(stringResource(R.string.batteria_titolo)) },
        text = { Text(stringResource(R.string.batteria_testo)) },
        confirmButton = {
            TextButton(
                onClick = {
                    chiudi()
                    EsenzioneBatteria.chiedi(context)
                },
            ) {
                Text(stringResource(R.string.batteria_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = chiudi) {
                Text(stringResource(R.string.batteria_non_ora))
            }
        },
    )
}
