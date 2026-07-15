package eu.stgm.pactum.genitore

import android.Manifest
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
import eu.stgm.pactum.genitore.ui.theme.PactumTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PactumTheme {
                GenitoreRoot()
            }
        }
    }
}

private enum class Destinazione(val icona: ImageVector, val etichetta: Int) {
    FINESTRA(Icons.Filled.Home, R.string.scheda_finestra),
    NOTIFICHE(Icons.Filled.Notifications, R.string.scheda_notifiche),
    IMPOSTAZIONI(Icons.Filled.Settings, R.string.scheda_impostazioni),
}

/** Tre destinazioni, una barra in basso: la finestra è la casa. */
@Composable
private fun GenitoreRoot() {
    var destinazione by rememberSaveable { mutableStateOf(Destinazione.FINESTRA) }

    RichiestaPermessoNotifiche()

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
