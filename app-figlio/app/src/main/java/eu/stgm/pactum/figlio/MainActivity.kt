package eu.stgm.pactum.figlio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import eu.stgm.pactum.figlio.permessi.StatoPermessi
import eu.stgm.pactum.figlio.servizio.PactumService
import eu.stgm.pactum.figlio.ui.ImpostazioniScreen
import eu.stgm.pactum.figlio.ui.OggiScreen
import eu.stgm.pactum.figlio.ui.OnboardingScreen
import eu.stgm.pactum.figlio.ui.theme.PactumTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PactumTheme {
                PactumRoot()
            }
        }
    }
}

private enum class Schermata { OGGI, IMPOSTAZIONI }

/**
 * Navigazione minima: onboarding finché manca l'accesso ai dati di utilizzo
 * (il permesso indispensabile), poi Oggi con le Impostazioni a un tocco.
 * Lo stato dei permessi È la persistenza: niente flag da tenere allineati.
 */
@Composable
private fun PactumRoot() {
    val context = LocalContext.current
    var statoPermessi by remember { mutableStateOf(StatoPermessi.leggi(context)) }
    var schermata by rememberSaveable { mutableStateOf(Schermata.OGGI) }

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
    } else {
        LaunchedEffect(Unit) { PactumService.avvia(context) }
        when (schermata) {
            Schermata.OGGI -> OggiScreen(
                onApriImpostazioni = { schermata = Schermata.IMPOSTAZIONI },
            )
            Schermata.IMPOSTAZIONI -> {
                BackHandler { schermata = Schermata.OGGI }
                ImpostazioniScreen(onChiudi = { schermata = Schermata.OGGI })
            }
        }
    }
}
