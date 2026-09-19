package eu.stgm.pactum.figlio

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.permessi.StatoPermessi
import eu.stgm.pactum.figlio.servizio.PactumService
import eu.stgm.pactum.figlio.ui.CosaVedeScreen
import eu.stgm.pactum.figlio.ui.DichiarazioniScreen
import eu.stgm.pactum.figlio.ui.ImpostazioniScreen
import eu.stgm.pactum.figlio.ui.OggiScreen
import eu.stgm.pactum.figlio.ui.OnboardingScreen
import eu.stgm.pactum.figlio.ui.PrimaRegolaScreen
import eu.stgm.pactum.figlio.ui.ProposteScreen
import eu.stgm.pactum.figlio.ui.ProposteViewModel
import eu.stgm.pactum.figlio.ui.RegoleScreen
import eu.stgm.pactum.figlio.ui.RegoleViewModel
import eu.stgm.pactum.figlio.ui.SitiScreen
import eu.stgm.pactum.figlio.ui.theme.PactumTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    // La scheda su cui aprirsi quando si arriva da una notifica locale
    // (sforamento, segno, chiusura della sera → oggi; proposta → proposte;
    // verdetto → diario). Attività a singleTop: onNewIntent la aggiorna
    // quando l'app è già viva.
    private val destinazioneRichiesta = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        destinazioneRichiesta.value = intent?.getStringExtra(EXTRA_DESTINAZIONE)
        setContent {
            PactumTheme {
                PactumRoot(
                    destinazioneRichiesta = destinazioneRichiesta.value,
                    onDestinazioneConsumata = { destinazioneRichiesta.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        destinazioneRichiesta.value = intent.getStringExtra(EXTRA_DESTINAZIONE)
    }

    companion object {
        const val EXTRA_DESTINAZIONE = "destinazione_iniziale"
        const val DEST_OGGI = "oggi"
        const val DEST_REGOLE = "regole"
        const val DEST_PROPOSTE = "proposte"
        const val DEST_DIARIO = "diario"
    }
}

/**
 * Le quattro schede (redesign C8). La scheda Bonus non c'è più: il bonus vive
 * sulla riga della regola, in Oggi, dove il contesto è già dato. Le icone sono
 * disegnate per Pactum (C4), sul modello del quadretto della striscia.
 */
private enum class Scheda(val icona: Int, val etichetta: Int, val destinazione: String) {
    OGGI(R.drawable.ic_scheda_oggi, R.string.scheda_oggi, MainActivity.DEST_OGGI),
    REGOLE(R.drawable.ic_scheda_regole, R.string.scheda_regole, MainActivity.DEST_REGOLE),
    PROPOSTE(R.drawable.ic_scheda_proposte, R.string.scheda_proposte, MainActivity.DEST_PROPOSTE),
    DIARIO(R.drawable.ic_scheda_diario, R.string.scheda_diario, MainActivity.DEST_DIARIO),
}

/**
 * Navigazione del figlio: onboarding finché manca l'accesso ai dati di
 * utilizzo (il permesso indispensabile), poi — una volta — "Cosa vede tuo
 * padre", poi il gate della prima regola, poi le quattro schede. Le
 * Impostazioni sono un'icona nella barra in alto di Oggi.
 */
@Composable
private fun PactumRoot(
    destinazioneRichiesta: String?,
    onDestinazioneConsumata: () -> Unit,
) {
    val context = LocalContext.current
    val ambito = rememberCoroutineScope()
    val impostazioni = remember { Impostazioni(context.applicationContext) }
    var statoPermessi by remember { mutableStateOf(StatoPermessi.leggi(context)) }

    // Al ritorno dalle Impostazioni di sistema lo stato va riletto.
    LifecycleResumeEffect(Unit) {
        statoPermessi = StatoPermessi.leggi(context)
        onPauseOrDispose { }
    }

    if (!statoPermessi.accessoUso) {
        OnboardingScreen(
            statoPermessi = statoPermessi,
            onAggiorna = { statoPermessi = StatoPermessi.leggi(context) },
        )
        return
    }

    LaunchedEffect(Unit) { PactumService.avvia(context) }

    // Subito dopo i permessi, una volta: cosa arriva al genitore e cosa no.
    // null = non ancora letto dal disco: niente lampi di schermate sbagliate.
    val cosaVedeVista by impostazioni.cosaVedeVista.collectAsState(initial = null)
    when (cosaVedeVista) {
        null -> {
            Box(modifier = Modifier.fillMaxSize())
            return
        }
        false -> {
            CosaVedeScreen(onHoCapito = { ambito.launch { impostazioni.registraCosaVedeVista() } })
            return
        }
        true -> Unit
    }

    // Il nome della scheda, non l'enum: uno stato salvato da una versione con
    // cinque schede non deve far cadere l'app al ripristino.
    var nomeScheda by rememberSaveable { mutableStateOf(Scheda.OGGI.name) }
    val scheda = Scheda.entries.firstOrNull { it.name == nomeScheda } ?: Scheda.OGGI
    var mostraImpostazioni by rememberSaveable { mutableStateOf(false) }
    var mostraSiti by rememberSaveable { mutableStateOf(false) }
    var mostraCosaVede by rememberSaveable { mutableStateOf(false) }

    // Gate della prima regola (concept.md: almeno una regola obbligatoria). Lo
    // stesso RegoleViewModel dell'Activity serve il gate e la scheda Regole.
    val regoleVm: RegoleViewModel = viewModel()
    val statoRegole by regoleVm.stato.collectAsStateWithLifecycle()
    // Le proposte in attesa danno il badge sulla scheda: stesso ViewModel
    // (dell'Activity) che usa la scheda Proposte.
    val proposteVm: ProposteViewModel = viewModel()
    val statoProposte by proposteVm.stato.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        regoleVm.aggiorna()
        proposteVm.aggiorna()
        onPauseOrDispose { }
    }

    // "Cosa vede tuo padre" dalle Impostazioni: sopra a tutto, torna indietro
    // alle Impostazioni.
    if (mostraCosaVede) {
        BackHandler { mostraCosaVede = false }
        CosaVedeScreen(onChiudi = { mostraCosaVede = false })
        return
    }

    // Le Impostazioni sono raggiungibili anche dal gate (per inserire server e
    // codice quando mancano), quindi si valutano prima di tutto il resto.
    if (mostraImpostazioni) {
        BackHandler { mostraImpostazioni = false }
        ImpostazioniScreen(
            onChiudi = { mostraImpostazioni = false; regoleVm.aggiorna() },
            onApriCosaVede = { mostraCosaVede = true },
        )
        return
    }

    // I siti visitati (v2.3): schermata piena, raggiunta dalla scheda Oggi.
    // Fuori dalla barra in basso di proposito — è una sezione da leggere,
    // non un posto dove si sta.
    if (mostraSiti) {
        BackHandler { mostraSiti = false }
        SitiScreen(onChiudi = { mostraSiti = false })
        return
    }

    // Finché il patto non ha nemmeno una regola, prima si crea quella: è il
    // figlio a scrivere il patto. Creata la prima, il server vieta di togliere
    // l'ultima, così il gate non torna; offline la copia locale già sincronizzata
    // basta a superarlo.
    if (statoRegole.regole.isEmpty()) {
        if (statoRegole.caricamento) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            PrimaRegolaScreen(
                vm = regoleVm,
                onApriImpostazioni = { mostraImpostazioni = true },
            )
        }
        return
    }

    // Arrivo da una notifica: salta alla scheda giusta, una volta sola. Una
    // destinazione che non si conosce (versione vecchia) apre Oggi: un tocco su
    // una notifica non finisce mai nel vuoto.
    LaunchedEffect(destinazioneRichiesta) {
        if (destinazioneRichiesta != null) {
            nomeScheda = (Scheda.entries.firstOrNull { it.destinazione == destinazioneRichiesta }
                ?: Scheda.OGGI).name
            mostraImpostazioni = false
            mostraSiti = false
            mostraCosaVede = false
            onDestinazioneConsumata()
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Scheda.entries.forEach { voce ->
                    NavigationBarItem(
                        selected = scheda == voce,
                        onClick = { nomeScheda = voce.name },
                        icon = {
                            val inAttesa = if (voce == Scheda.PROPOSTE) statoProposte.pendenti else 0
                            BadgedBox(
                                badge = {
                                    if (inAttesa > 0) Badge { Text(inAttesa.toString()) }
                                },
                            ) {
                                Icon(painterResource(voce.icona), contentDescription = null)
                            }
                        },
                        label = { Text(stringResource(voce.etichetta)) },
                    )
                }
            }
        },
    ) { padding ->
        // consumeWindowInsets: il padding dello Scaffold esterno copre già le
        // barre di sistema; senza consumarlo, le TopAppBar degli Scaffold interni
        // riapplicherebbero l'inset della status bar (doppio spazio).
        Box(modifier = Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
            when (scheda) {
                Scheda.OGGI -> OggiScreen(
                    onApriImpostazioni = { mostraImpostazioni = true },
                    onApriSiti = { mostraSiti = true },
                    onApriDiario = { nomeScheda = Scheda.DIARIO.name },
                )
                Scheda.REGOLE -> RegoleScreen()
                Scheda.PROPOSTE -> ProposteScreen()
                Scheda.DIARIO -> DichiarazioniScreen()
            }
        }
    }
}
