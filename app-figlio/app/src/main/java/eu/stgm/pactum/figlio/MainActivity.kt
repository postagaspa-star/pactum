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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import eu.stgm.pactum.figlio.permessi.StatoPermessi
import eu.stgm.pactum.figlio.servizio.PactumService
import eu.stgm.pactum.figlio.ui.BonusScreen
import eu.stgm.pactum.figlio.ui.DichiarazioniScreen
import eu.stgm.pactum.figlio.ui.ImpostazioniScreen
import eu.stgm.pactum.figlio.ui.OggiScreen
import eu.stgm.pactum.figlio.ui.OnboardingScreen
import eu.stgm.pactum.figlio.ui.ProposteScreen
import eu.stgm.pactum.figlio.ui.RegoleScreen
import eu.stgm.pactum.figlio.ui.theme.PactumTheme

class MainActivity : ComponentActivity() {
    // La scheda su cui aprirsi quando si arriva da una notifica locale
    // (sforamento → regole, proposta → proposte, verdetto → diario).
    // Attività a singleTop: onNewIntent la aggiorna quando l'app è già viva.
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
        const val DEST_REGOLE = "regole"
        const val DEST_PROPOSTE = "proposte"
        const val DEST_DIARIO = "diario"
    }
}

private enum class Scheda(val icona: ImageVector, val etichetta: Int) {
    OGGI(Icons.Filled.Home, R.string.scheda_oggi),
    REGOLE(Icons.AutoMirrored.Filled.List, R.string.scheda_regole),
    BONUS(Icons.Filled.Star, R.string.scheda_bonus),
    PROPOSTE(Icons.Filled.Email, R.string.scheda_proposte),
    DIARIO(Icons.Filled.CheckCircle, R.string.scheda_diario),
}

/**
 * Navigazione del figlio: onboarding finché manca l'accesso ai dati di
 * utilizzo (il permesso indispensabile), poi cinque schede — l'uso di oggi,
 * il patto completo (regole, bonus, proposte, diario) — con le Impostazioni
 * a un tocco dalla schermata Oggi. Lo stato dei permessi È la persistenza.
 */
@Composable
private fun PactumRoot(
    destinazioneRichiesta: String?,
    onDestinazioneConsumata: () -> Unit,
) {
    val context = LocalContext.current
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

    var scheda by rememberSaveable { mutableStateOf(Scheda.OGGI) }
    var mostraImpostazioni by rememberSaveable { mutableStateOf(false) }

    // Arrivo da una notifica: salta alla scheda giusta, una volta sola.
    LaunchedEffect(destinazioneRichiesta) {
        when (destinazioneRichiesta) {
            MainActivity.DEST_REGOLE -> scheda = Scheda.REGOLE
            MainActivity.DEST_PROPOSTE -> scheda = Scheda.PROPOSTE
            MainActivity.DEST_DIARIO -> scheda = Scheda.DIARIO
        }
        if (destinazioneRichiesta != null) {
            mostraImpostazioni = false
            onDestinazioneConsumata()
        }
    }

    if (mostraImpostazioni) {
        BackHandler { mostraImpostazioni = false }
        ImpostazioniScreen(onChiudi = { mostraImpostazioni = false })
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Scheda.entries.forEach { voce ->
                    NavigationBarItem(
                        selected = scheda == voce,
                        onClick = { scheda = voce },
                        icon = { Icon(voce.icona, stringResource(voce.etichetta)) },
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
                Scheda.OGGI -> OggiScreen(onApriImpostazioni = { mostraImpostazioni = true })
                Scheda.REGOLE -> RegoleScreen()
                Scheda.BONUS -> BonusScreen()
                Scheda.PROPOSTE -> ProposteScreen()
                Scheda.DIARIO -> DichiarazioniScreen()
            }
        }
    }
}
