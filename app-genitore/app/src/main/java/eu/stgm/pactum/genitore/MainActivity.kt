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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.design.BarraSchede
import eu.stgm.pactum.design.VoceBarra
import eu.stgm.pactum.design.attivaBordoPieno
import eu.stgm.pactum.genitore.dati.Impostazioni
import eu.stgm.pactum.genitore.rete.EsitoAbbinamento
import eu.stgm.pactum.genitore.servizio.EsenzioneBatteria
import eu.stgm.pactum.genitore.servizio.VedettaService
import eu.stgm.pactum.genitore.sync.Vedetta
import eu.stgm.pactum.genitore.ui.CollegamentoViewModel
import eu.stgm.pactum.genitore.ui.Cornice
import eu.stgm.pactum.genitore.ui.DaDecidereScreen
import eu.stgm.pactum.genitore.ui.DaiLavoriScreen
import eu.stgm.pactum.genitore.ui.FaccendeViewModel
import eu.stgm.pactum.genitore.ui.FamigliaViewModel
import eu.stgm.pactum.genitore.ui.FinestraViewModel
import eu.stgm.pactum.genitore.ui.ImpostazioniScreen
import eu.stgm.pactum.genitore.ui.LavoriScreen
import eu.stgm.pactum.genitore.ui.LocalCornice
import eu.stgm.pactum.genitore.ui.Messaggi
import eu.stgm.pactum.genitore.ui.Navigazione
import eu.stgm.pactum.genitore.ui.NotificheScreen
import eu.stgm.pactum.genitore.ui.NotificheViewModel
import eu.stgm.pactum.genitore.ui.Pagina
import eu.stgm.pactum.genitore.ui.PanoramicaScreen
import eu.stgm.pactum.genitore.ui.ProposteViewModel
import eu.stgm.pactum.genitore.ui.RegolaScreen
import eu.stgm.pactum.genitore.ui.Scheda
import eu.stgm.pactum.genitore.ui.Schermo
import eu.stgm.pactum.genitore.ui.ModificaLavoroScreen
import eu.stgm.pactum.genitore.ui.SessioniScreen
import eu.stgm.pactum.genitore.ui.StoricoScreen
import eu.stgm.pactum.genitore.ui.StudioScreen
import eu.stgm.pactum.genitore.ui.StudioViewModel
import eu.stgm.pactum.genitore.ui.TutteLeRegoleScreen
import eu.stgm.pactum.genitore.ui.TempoScreen
import eu.stgm.pactum.genitore.ui.VerdettiViewModel
import eu.stgm.pactum.genitore.ui.codificaNavigazione
import eu.stgm.pactum.genitore.ui.decodificaNavigazione
import eu.stgm.pactum.genitore.ui.daDecidereDelScelto
import eu.stgm.pactum.genitore.ui.dopoLaRiga
import eu.stgm.pactum.genitore.ui.ingresso
import eu.stgm.pactum.genitore.ui.messaggioAbbinamento
import eu.stgm.pactum.genitore.ui.parole
import eu.stgm.pactum.genitore.ui.quanteDaDecidereInTutto
import eu.stgm.pactum.genitore.ui.testoCollegato
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
        // (0.15) Bordo pieno con le icone scure della barra di stato (B14): lo
        // Scaffold della radice gestisce i margini delle barre di sistema.
        attivaBordoPieno()
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

        /** (0.15) La scheda "Da decidere" del figlio della notifica. */
        const val DEST_DECIDERE = "decidere"

        /** (0.13) I lavori di casa del figlio della notifica ((0.15) la scheda Lavori). */
        const val DEST_FACCENDE = "faccende"
        const val DEST_TEMPO = "tempo"

        /** (0.18) La pagina dello Studio del figlio della notifica (iniziato, chiuso, non chiuso, non partito). */
        const val DEST_STUDIO = "studio"
        const val DEST_TURNO = "turno"
        const val DEST_NOTIFICHE = "notifiche"

        // (0.9) La sezione "Avvisi del patto" delle Impostazioni: ci porta la
        // notifica fissa quando gli avvisi possono arrivare in ritardo o sono spenti.
        const val DEST_AVVISI = "avvisi"

        // Le destinazioni di prima (6 schede, poi "Proposte e conferme"): le notifiche
        // già nella tendina le portano ancora nel loro PendingIntent. Restano
        // riconosciute e (0.15) finiscono su "Da decidere", così nessun tocco cade
        // nel vuoto dopo l'aggiornamento (v. ingresso, ui/Navigazione.kt).
        const val DEST_PROPOSTE = "proposte"
        const val DEST_VERDETTI = "verdetti"
    }
}

/** (0.15) Le quattro schede fisse della barra in basso, con le icone disegnate per Pactum. */
private val VociBarra = listOf(
    Triple(Scheda.PANORAMICA, R.drawable.ic_notifica_binocolo, R.string.scheda_finestra),
    Triple(Scheda.DA_DECIDERE, R.drawable.ic_scheda_turno, R.string.scheda_da_decidere),
    Triple(Scheda.LAVORI, R.drawable.ic_scheda_lavori, R.string.scheda_lavori),
    Triple(Scheda.TEMPO, R.drawable.ic_scheda_tempo, R.string.scheda_tempo),
)

/** Ogni quanto si ricontano le notifiche non lette, per il badge. */
private const val INTERVALLO_NON_LETTE_MS = 60_000L

/** La chiave dello stato salvato di una schermata (scorrimento, sezioni aperte, scelte). */
private fun chiaveSchermo(schermo: Schermo): String = when (schermo) {
    is Schermo.SuScheda -> "scheda-${schermo.scheda.name}"
    is Schermo.SuPagina -> "pagina-" + when (val p = schermo.pagina) {
        Pagina.Notifiche -> "notifiche"
        is Pagina.Impostazioni -> "impostazioni"
        Pagina.Storico -> "storico"
        is Pagina.Regola -> "regola-${p.regolaId}"
        Pagina.DaiLavori -> "dai"
        Pagina.Sessioni -> "sessioni"
        Pagina.TutteLeRegole -> "regole"
        is Pagina.ModificaLavoro -> "modifica-${p.faccendaId}"
        Pagina.Studio -> "studio"
    }
}

/**
 * (0.15) Quattro schede fisse — Panoramica · Da decidere · Lavori · Tempo — e
 * sopra, in ogni scheda, la campanella delle notifiche, ⟳ e le Impostazioni, che
 * aprono pagine sopra. La navigazione è una pila ([Navigazione], logica pura):
 * Indietro torna da dove si era venuti, da una scheda alla Panoramica, dalla
 * Panoramica esce. Ogni schermata tiene il suo stato (scorrimento, sezioni
 * aperte, giorno scelto) cambiando scheda e ruotando: [rememberSaveableStateHolder].
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
    // La pila si salva come testo (rotazione, app chiusa da Android).
    var pila by rememberSaveable { mutableStateOf(codificaNavigazione(Navigazione())) }
    val navigazione = remember(pila) { decodificaNavigazione(pila) }
    val vai: (Navigazione) -> Unit = { pila = codificaNavigazione(it) }
    // (0.13) La foto di un lavoro da aprire nella scheda Lavori (da una notifica).
    var fotoDaAprire by rememberSaveable { mutableStateOf<Long?>(null) }

    // Lo stesso ViewModel che usa la lista delle notifiche (scope dell'attività):
    // il badge e la lista contano le stesse cose.
    val notificheVm: NotificheViewModel = viewModel()
    val statoNotifiche by notificheVm.stato.collectAsStateWithLifecycle()
    val nonLette = statoNotifiche.notifiche.size
    // (v3) La famiglia, condivisa da tutte le schermate: si rilegge insieme al
    // badge, così i figli, i loro dispositivi e i loro numeri restano freschi.
    val famigliaVm: FamigliaViewModel = viewModel()
    val famiglia by famigliaVm.stato.collectAsStateWithLifecycle()
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
    val studioVm: StudioViewModel = viewModel()
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
        studioVm.dimentica()
    }

    RichiestaPermessoNotifiche()
    RichiestaEsenzioneBatteria(configurata)

    // I messaggi in basso di tutta l'app: un esito si vede anche se la pagina che
    // l'ha chiesto si è chiusa (i lavori di casa appena dati).
    val statoMessaggi = remember { SnackbarHostState() }
    val ambitoMessaggi = rememberCoroutineScope()
    val messaggi = remember { Messaggi(statoMessaggi, ambitoMessaggi) }

    // (0.13) Com'è andato il collegamento col codice di 6 cifre, anche se è finito
    // mentre le Impostazioni erano chiuse: si dice, e (0.15) se è riuscito si torna
    // alla Panoramica, che adesso ha qualcosa da mostrare.
    val collegamento by collegamentoVm.stato.collectAsStateWithLifecycle()
    val p = parole()
    LaunchedEffect(collegamento.esito) {
        val esito = collegamento.esito ?: return@LaunchedEffect
        collegamentoVm.consumaEsito()
        val messaggio = if (esito is EsitoAbbinamento.Collegato) {
            vai(Navigazione())
            testoCollegato(p, esito.genitore)
        } else {
            messaggioAbbinamento(p, esito)
        }
        if (messaggio != null) messaggi.mostra(messaggio)
    }

    // Arrivo da una notifica: la scheda (o la pagina) giusta, una volta sola.
    var ingressiAvvisi by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(destinazioneRichiesta) {
        if (destinazioneRichiesta == null) return@LaunchedEffect
        vai(ingresso(destinazioneRichiesta, navigazione))
        // (0.15) Dalla notifica fissa degli avvisi le Impostazioni tornano sugli
        // Avvisi anche se erano già aperte e scorse altrove.
        if (destinazioneRichiesta == MainActivity.DEST_AVVISI) ingressiAvvisi++
        // (0.13) Le faccende del figlio della notifica, e la foto se è di una faccenda.
        if (destinazioneRichiesta == MainActivity.DEST_FACCENDE) fotoDaAprire = faccendaRichiesta
        onDestinazioneConsumata()
        onFaccendaConsumata()
    }

    // Indietro: da una pagina a dove si era; da una scheda alla Panoramica; dalla
    // Panoramica esce (BackHandler spento: ci pensa Android).
    val dopoIndietro = navigazione.indietro()
    BackHandler(enabled = dopoIndietro != null) { dopoIndietro?.let(vai) }

    // Lo stato salvato di ogni schermata. Quello di una pagina chiusa si butta: la
    // prossima volta si riapre dall'inizio. Le schede tengono sempre il loro.
    val contenitore = rememberSaveableStateHolder()
    val chiaviPagine = navigazione.pila.filterIsInstance<Schermo.SuPagina>().map(::chiaveSchermo).toSet()
    var pagineAperte by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(chiaviPagine) {
        (pagineAperte - chiaviPagine).forEach { contenitore.removeState(it) }
        pagineAperte = chiaviPagine
    }

    // Il numero sulla voce "Da decidere": per il figlio scelto dalla sua finestra
    // e dalle sue dichiarazioni (le letture della lista), per gli altri figli dalla
    // famiglia (proposte + sessioni).
    val daDecidere = quanteDaDecidereInTutto(
        figli = famiglia.figli,
        sceltoId = famiglia.figlioScelto?.id,
        delScelto = daDecidereDelScelto(famiglia, finestraVm, proposteVm, verdettiVm),
    )
    // (0.15) Le dichiarazioni del figlio scelto (la stessa lettura che fa "Da
    // decidere" quando si apre): all'apertura dell'app, al cambio di figlio e
    // quando arriva una notifica nuova (una dichiarazione nuova ne porta una), così
    // il numero c'è già prima di aprire la scheda. Niente giro in più ogni minuto.
    var nonLettePrima by remember { mutableIntStateOf(nonLette) }
    var arrivi by remember { mutableIntStateOf(0) }
    LaunchedEffect(nonLette) {
        if (nonLette > nonLettePrima) arrivi++
        nonLettePrima = nonLette
    }
    LifecycleResumeEffect(famiglia.figlioId, famiglia.pronta, arrivi) {
        if (famiglia.pronta) verdettiVm.aggiorna(famiglia.figlioId)
        onPauseOrDispose { }
    }

    val cornice = Cornice(
        notificheNonLette = nonLette,
        messaggi = messaggi,
        vaiAScheda = { vai(navigazione.apriScheda(it)) },
        apri = { vai(navigazione.apri(it)) },
        indietro = { vai(dopoIndietro ?: Navigazione()) },
        allaPanoramica = { vai(Navigazione()) },
        apriFoto = { id ->
            fotoDaAprire = id
            vai(navigazione.apriScheda(Scheda.LAVORI))
        },
    )

    CompositionLocalProvider(LocalCornice provides cornice) {
        Scaffold(
            bottomBar = {
                if (!navigazione.suUnaPagina) {
                    BarraInBasso(
                        scelta = navigazione.scheda,
                        daDecidere = daDecidere,
                        onScegli = { vai(navigazione.scegli(it)) },
                    )
                }
            },
            snackbarHost = { SnackbarHost(statoMessaggi) },
        ) { padding ->
            // consumeWindowInsets: il padding dello Scaffold esterno copre già le
            // barre di sistema; senza consumarlo, le TopAppBar degli Scaffold interni
            // riapplicherebbero l'inset della status bar (doppio spazio su Android 15).
            // (0.15) imePadding: con adjustResize (manifest) e il bordo pieno la
            // tastiera arriva come inset; qui ogni schermata le resta sopra, una volta
            // sola (consumato: gli imePadding delle pagine dentro non contano due volte).
            Box(modifier = Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
                val inCima = navigazione.inCima
                contenitore.SaveableStateProvider(chiaveSchermo(inCima)) {
                    when (inCima) {
                        is Schermo.SuScheda -> when (inCima.scheda) {
                            Scheda.PANORAMICA -> PanoramicaScreen()
                            Scheda.DA_DECIDERE -> DaDecidereScreen()
                            Scheda.LAVORI -> LavoriScreen(
                                fotoRichiesta = fotoDaAprire,
                                onFotoRichiestaConsumata = { fotoDaAprire = null },
                            )
                            Scheda.TEMPO -> TempoScreen()
                        }
                        is Schermo.SuPagina -> when (val pagina = inCima.pagina) {
                            Pagina.Notifiche -> NotificheScreen(
                                // (0.15) Ogni riga porta dove si guarda il fatto: il figlio
                                // della notifica, e per un lavoro fatto la sua foto.
                                onApri = { apri ->
                                    apri.figlioId?.let(famigliaVm::scegli)
                                    if (apri.faccendaId != null) fotoDaAprire = apri.faccendaId
                                    vai(navigazione.dopoLaRiga(apri))
                                },
                            )
                            is Pagina.Impostazioni -> ImpostazioniScreen(sezione = pagina.sezione, richiesta = ingressiAvvisi)
                            Pagina.Storico -> StoricoScreen()
                            is Pagina.Regola -> RegolaScreen(regolaId = pagina.regolaId)
                            Pagina.DaiLavori -> DaiLavoriScreen()
                            Pagina.Sessioni -> SessioniScreen()
                            Pagina.TutteLeRegole -> TutteLeRegoleScreen()
                            is Pagina.ModificaLavoro -> ModificaLavoroScreen(faccendaId = pagina.faccendaId)
                            Pagina.Studio -> StudioScreen()
                        }
                    }
                }
            }
        }
    }
}

/**
 * (0.15) La barra in basso: quattro voci fisse ([BarraSchede] di core-design:
 * etichette sempre su una riga). "Da decidere" porta il numero delle cose che
 * aspettano il genitore.
 */
@Composable
private fun BarraInBasso(scelta: Scheda, daDecidere: Int, onScegli: (Scheda) -> Unit) {
    val descrizioneBadge = if (daDecidere > 0) {
        pluralStringResource(R.plurals.da_decidere_badge, daDecidere, daDecidere)
    } else {
        null
    }
    val voci = VociBarra.map { (scheda, icona, etichetta) ->
        VoceBarra(
            etichetta = stringResource(etichetta),
            icona = painterResource(icona),
            badge = daDecidere.takeIf { scheda == Scheda.DA_DECIDERE && it > 0 },
            descrizioneBadge = descrizioneBadge.takeIf { scheda == Scheda.DA_DECIDERE },
        )
    }
    BarraSchede(
        voci = voci,
        selezionata = VociBarra.indexOfFirst { it.first == scelta },
        onSeleziona = { indice -> onScegli(VociBarra[indice].first) },
    )
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
