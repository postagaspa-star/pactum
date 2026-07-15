package eu.stgm.pactum.genitore

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import eu.stgm.pactum.genitore.dati.Impostazioni
import eu.stgm.pactum.genitore.sync.VedettaWorker
import eu.stgm.pactum.genitore.ui.FinestraScreen
import eu.stgm.pactum.genitore.ui.ImpostazioniScreen
import eu.stgm.pactum.genitore.ui.NotificheScreen
import eu.stgm.pactum.genitore.ui.ProposteScreen
import eu.stgm.pactum.genitore.ui.TempoScreen
import eu.stgm.pactum.genitore.ui.VerdettiScreen
import eu.stgm.pactum.genitore.ui.theme.PactumTheme
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
        const val DEST_TEMPO = "tempo"
        const val DEST_PROPOSTE = "proposte"
        const val DEST_VERDETTI = "verdetti"
        const val DEST_NOTIFICHE = "notifiche"
    }
}

private enum class Destinazione(val icona: ImageVector, val etichetta: Int) {
    FINESTRA(Icons.Filled.Home, R.string.scheda_finestra),
    TEMPO(Icons.Filled.DateRange, R.string.scheda_tempo),
    PROPOSTE(Icons.Filled.Edit, R.string.scheda_proposte),
    VERDETTI(Icons.Filled.CheckCircle, R.string.scheda_verdetti),
    NOTIFICHE(Icons.Filled.Notifications, R.string.scheda_notifiche),
    IMPOSTAZIONI(Icons.Filled.Settings, R.string.scheda_impostazioni),
}

/** Sei destinazioni, una barra in basso: la finestra è la casa. */
@Composable
private fun GenitoreRoot(
    destinazioneRichiesta: String?,
    onDestinazioneConsumata: () -> Unit,
) {
    var destinazione by rememberSaveable { mutableStateOf(Destinazione.FINESTRA) }

    RichiestaPermessoNotifiche()

    // Arrivo da una notifica: salta alla scheda giusta, una volta sola.
    LaunchedEffect(destinazioneRichiesta) {
        when (destinazioneRichiesta) {
            MainActivity.DEST_TEMPO -> destinazione = Destinazione.TEMPO
            MainActivity.DEST_PROPOSTE -> destinazione = Destinazione.PROPOSTE
            MainActivity.DEST_VERDETTI -> destinazione = Destinazione.VERDETTI
            MainActivity.DEST_NOTIFICHE -> destinazione = Destinazione.NOTIFICHE
        }
        if (destinazioneRichiesta != null) onDestinazioneConsumata()
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destinazione.entries.forEach { voce ->
                    NavigationBarItem(
                        selected = destinazione == voce,
                        onClick = { destinazione = voce },
                        icon = { Icon(voce.icona, stringResource(voce.etichetta)) },
                        label = { Text(stringResource(voce.etichetta)) },
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
                Destinazione.FINESTRA -> FinestraScreen()
                Destinazione.TEMPO -> TempoScreen()
                Destinazione.PROPOSTE -> ProposteScreen()
                Destinazione.VERDETTI -> VerdettiScreen()
                Destinazione.NOTIFICHE -> NotificheScreen()
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
