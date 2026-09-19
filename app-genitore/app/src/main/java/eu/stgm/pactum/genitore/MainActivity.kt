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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.genitore.dati.Impostazioni
import eu.stgm.pactum.genitore.sync.VedettaWorker
import eu.stgm.pactum.genitore.ui.FinestraScreen
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        destinazioneRichiesta.value = intent?.getStringExtra(EXTRA_DESTINAZIONE)
        // Consumato: senza rimuoverlo dall'intent, ogni ricreazione dell'attività
        // (es. rotazione) rileggerebbe lo stesso extra e ri-salterebbe alla scheda
        // della notifica, ignorando la scheda su cui il genitore si era spostato.
        intent?.removeExtra(EXTRA_DESTINAZIONE)
        setContent {
            PactumTheme {
                GenitoreRoot(
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
        // Consumato subito, come in onCreate: evita che una rotazione successiva
        // rilegga l'extra e ri-salti alla scheda della notifica.
        intent.removeExtra(EXTRA_DESTINAZIONE)
    }

    companion object {
        const val EXTRA_DESTINAZIONE = "destinazione_iniziale"
        const val DEST_FINESTRA = "finestra"
        const val DEST_TEMPO = "tempo"
        const val DEST_TURNO = "turno"
        const val DEST_NOTIFICHE = "notifiche"

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
) {
    var destinazione by rememberSaveable { mutableStateOf(Destinazione.FINESTRA) }
    var notificheAperte by rememberSaveable { mutableStateOf(false) }

    // Lo stesso ViewModel che usa la lista delle notifiche (scope dell'attività):
    // il badge e la lista contano le stesse cose.
    val notificheVm: NotificheViewModel = viewModel()
    val statoNotifiche by notificheVm.stato.collectAsStateWithLifecycle()
    val nonLette = statoNotifiche.notifiche.size
    val cicloVita = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(cicloVita) {
        cicloVita.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                notificheVm.aggiorna()
                delay(INTERVALLO_NON_LETTE_MS)
            }
        }
    }

    RichiestaPermessoNotifiche()

    // Arrivo da una notifica: salta alla scheda giusta, una volta sola.
    LaunchedEffect(destinazioneRichiesta) {
        if (destinazioneRichiesta == null) return@LaunchedEffect
        when (destinazioneRichiesta) {
            MainActivity.DEST_TEMPO -> {
                destinazione = Destinazione.TEMPO
                notificheAperte = false
            }
            MainActivity.DEST_TURNO,
            MainActivity.DEST_PROPOSTE,
            MainActivity.DEST_VERDETTI -> {
                destinazione = Destinazione.TURNO
                notificheAperte = false
            }
            MainActivity.DEST_NOTIFICHE -> {
                destinazione = Destinazione.FINESTRA
                notificheAperte = true
            }
            // DEST_FINESTRA e qualunque valore sconosciuto: la casa.
            else -> {
                destinazione = Destinazione.FINESTRA
                notificheAperte = false
            }
        }
        onDestinazioneConsumata()
    }

    // Indietro chiude le notifiche e torna alla finestra.
    BackHandler(enabled = notificheAperte) { notificheAperte = false }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destinazione.entries.forEach { voce ->
                    NavigationBarItem(
                        selected = destinazione == voce,
                        onClick = {
                            destinazione = voce
                            notificheAperte = false
                        },
                        icon = {
                            // L'etichetta sotto dice già il nome: l'icona tace.
                            // Niente badge qui: il conto delle non lette sta
                            // solo sulla campanella, da dove si aprono.
                            Icon(painterResource(voce.icona), contentDescription = null)
                        },
                        // "Proposte e conferme" non sta in una riga (4 voci su
                        // 360-411dp): va a capo, centrata, mai troncata. Tutte
                        // le etichette tengono due righe, così le icone restano
                        // allineate (la voce centra icona+etichetta in verticale).
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
                Destinazione.FINESTRA -> if (notificheAperte) {
                    NotificheScreen(onChiudi = { notificheAperte = false }, vm = notificheVm)
                } else {
                    FinestraScreen(
                        notificheNonLette = nonLette,
                        onApriNotifiche = { notificheAperte = true },
                    )
                }
                Destinazione.TEMPO -> TempoScreen()
                Destinazione.TURNO -> TurnoScreen()
                Destinazione.IMPOSTAZIONI -> ImpostazioniScreen()
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
        if (richiestaFatta == false && !VedettaWorker.puoAvvisare(context)) {
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
