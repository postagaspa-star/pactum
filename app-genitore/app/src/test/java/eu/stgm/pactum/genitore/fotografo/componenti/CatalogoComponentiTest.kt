package eu.stgm.pactum.genitore.fotografo.componenti

import android.app.Application
import android.content.ComponentName
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.IdlingPolicies
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import eu.stgm.pactum.design.BarraSchede
import eu.stgm.pactum.design.Caricamento
import eu.stgm.pactum.design.CardEvidenza
import eu.stgm.pactum.design.CardNormale
import eu.stgm.pactum.design.FilaPulsanti
import eu.stgm.pactum.design.FoglioDalBasso
import eu.stgm.pactum.design.GiornoPatto
import eu.stgm.pactum.design.LegendaStriscia
import eu.stgm.pactum.design.MenuAzioni
import eu.stgm.pactum.design.Pillola
import eu.stgm.pactum.design.RigaStato
import eu.stgm.pactum.design.RigaToccabile
import eu.stgm.pactum.design.Segnale
import eu.stgm.pactum.design.SezioneEspandibile
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.design.StatoVuoto
import eu.stgm.pactum.design.StrisciaGiorni
import eu.stgm.pactum.design.TitoloSezione
import eu.stgm.pactum.design.Tono
import eu.stgm.pactum.design.VoceBarra
import eu.stgm.pactum.design.VoceMenu
import eu.stgm.pactum.design.attivaBordoPieno
import eu.stgm.pactum.genitore.ui.theme.PactumTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Il catalogo dei componenti comuni (core-design, 0.15) disegnato col tema VERO
 * dell'app del genitore, a 360 dp, testo normale e al 130%. Autonomo: non usa gli
 * aiutanti degli altri file del fotografo. Parte solo con -Pfotografo:
 *   gradlew :app:testDebugUnitTest -Pfotografo --tests "*CatalogoComponenti*"
 * Le foto vanno sempre in C:/Users/andre/Pactum-passaggio/scan/componenti-genitore/.
 * Dati finti (repo pubblico).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class CatalogoComponentiTest {

    // L'orologio di Compose lo guidano i test: la rotella di Caricamento gira
    // all'infinito, e col looper normale di Robolectric non si fermerebbe mai.
    @get:Rule
    val compose = createEmptyComposeRule()

    private val cartella = File("C:/Users/andre/Pactum-passaggio/scan/componenti-genitore")
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun catalogo() {
        cartella.mkdirs()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        for (grande in listOf(false, true)) {
            foto("1-card-pillole-stati", 1500, grande) { PaginaCard() }
            foto("2-liste-pulsanti-striscia", 1500, grande) { PaginaListe() }
            foto("3-menu-e-vuoto", 800, grande) { PaginaMenu() }
            foto("4-foglio-dal-basso", 800, grande) { PaginaFoglio() }
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun foto(nome: String, altezza: Int, grande: Boolean, contenuto: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("it-rIT-w360dp-h${altezza}dp-port-notnight-xhdpi")
        RuntimeEnvironment.setFontScale(if (grande) 1.3f else 1f)
        IdlingPolicies.setMasterPolicyTimeout(4, TimeUnit.SECONDS)
        compose.mainClock.autoAdvance = false
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.attivaBordoPieno()
        activity.setContent {
            PactumTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.safeDrawingPadding()) { contenuto() }
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        shadowOf(Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeBy(900)
        val file = File(cartella, "$nome${if (grande) "_grande" else "_normale"}.png")
        captureScreenRoboImage(file.absolutePath)
        controller.pause().stop().destroy()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TuttePillole() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spazi.s), verticalArrangement = Arrangement.spacedBy(Spazi.s)) {
        Pillola("In pausa", tono = Tono.Neutro)
        Pillola("Attiva", tono = Tono.Positivo)
        Pillola("Da decidere", tono = Tono.Attenzione)
        Pillola("Scollegato", tono = Tono.Negativo)
    }
}

@Composable
private fun PaginaCard() {
    Column(Modifier.padding(Spazi.l + Spazi.xs), verticalArrangement = Arrangement.spacedBy(Spazi.l)) {
        TitoloSezione("Oggi")
        TuttePillole()
        TitoloSezione("Le tue app", azione = "Vedi tutte", onAzione = {})
        CardNormale {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Instagram", style = MaterialTheme.typography.titleMedium)
                    Text("1 h 10 min oggi, limite 1 h 30", style = MaterialTheme.typography.bodyMedium)
                }
                MenuAzioni(
                    voci = listOf(VoceMenu("Chiedi più tempo", {}), VoceMenu("Togli il limite", {}, distruttiva = true)),
                    descrizione = "Altre azioni per Instagram",
                )
            }
            Spacer(Modifier.height(Spazi.m))
            TuttePillole()
        }
        CardEvidenza(tono = Tono.Positivo) {
            Text("6 giorni di fila", style = MaterialTheme.typography.headlineSmall)
            Text("Il patto tiene: continua così.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(Spazi.m))
            TuttePillole()
        }
        CardEvidenza(tono = Tono.Attenzione) {
            Text("Lavori di casa", style = MaterialTheme.typography.titleMedium)
            Text("Sistemare la camera prima delle 19:00", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(Spazi.m))
            Button(onClick = {}) { Text("Fatto, mando la foto") }
        }
        CardEvidenza(tono = Tono.Neutro) {
            Text("Sessione Studio in corso", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Spazi.s))
            TuttePillole()
        }
        TitoloSezione("Righe di stato")
        RigaStato("Dati aggiornati 5 minuti fa")
        RigaStato("Il telefono di Luca non si sente da 2 ore", tono = Tono.Attenzione, azione = "Riprova", onAzione = {})
        RigaStato("Il computer di Luca è scollegato dal patto", tono = Tono.Negativo, azione = "Ricollega il computer", onAzione = {})
        RigaStato("Tutto a posto: 3 regole attive", tono = Tono.Positivo, onClick = {})
    }
}

@Composable
private fun PaginaListe() {
    val oggi = listOf(
        Segnale.MANTENUTA, Segnale.MANTENUTA, Segnale.FUORI_REGOLA, Segnale.MANTENUTA,
        Segnale.NESSUN_DATO, Segnale.MANTENUTA, Segnale.MANTENUTA, Segnale.MANTENUTA,
    ).mapIndexed { i, s -> GiornoPatto("2026-10-%02d".format(i + 1), s) }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .padding(Spazi.l + Spazi.xs),
            verticalArrangement = Arrangement.spacedBy(Spazi.m),
        ) {
            TitoloSezione("Ultimi 8 giorni")
            StrisciaGiorni(giorni = oggi, lato = 32.dp, descrizione = "6 giorni su 7 dentro le regole")
            LegendaStriscia(mantenuta = "Mantenuta", fuoriRegola = "Fuori regola", senzaDati = "Senza dati", oggi = "Oggi")
            CardNormale {
                Text("Instagram al massimo 1 h", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(Spazi.s))
                StrisciaGiorni(giorni = oggi, lato = 32.dp)
            }
            StatoVuoto("Nessuna proposta da decidere.")
            StatoVuoto("Non ci sono ancora regole.", titolo = "Niente regole", azione = "Proponi una regola", onAzione = {})
            Caricamento(testo = "Carico le regole…", centrato = false)
            CardNormale {
                RigaToccabile(
                    "Luca",
                    onClick = {},
                    sottotitolo = "Telefono e computer",
                    inizio = { Icon(Icons.Filled.Person, contentDescription = null) },
                    fine = { Pillola("Attiva", tono = Tono.Positivo) },
                )
                RigaToccabile("TikTok", onClick = {}, fine = { Text("45 min") })
                RigaToccabile("Impostazioni", onClick = {})
            }
            SezioneEspandibile("Storico", conteggio = 3, apertaAllInizio = true) {
                Text("Lunedì: proposta accettata da Mamma")
                Text("Domenica: 20 minuti in più da Papà")
                Text("Sabato: regola nuova per TikTok")
            }
            SezioneEspandibile("Regole vecchie", conteggio = 5) {
                Text("non si vede")
            }
            TitoloSezione("Fila di pulsanti")
            FilaPulsanti(Modifier.fillMaxWidth()) {
                Button(onClick = {}) { Text("Accetta") }
                OutlinedButton(onClick = {}) { Text("Rifiuta") }
            }
            FilaPulsanti(Modifier.fillMaxWidth()) {
                Button(onClick = {}) { Text("Manda la proposta a Mamma") }
                OutlinedButton(onClick = {}) { Text("Ci penso ancora") }
            }
        }
        BarraSchede(
            voci = listOf(
                VoceBarra("Panoramica", rememberVectorPainter(Icons.Filled.Home)),
                VoceBarra("Da decidere", rememberVectorPainter(Icons.Filled.Notifications), badge = 3, descrizioneBadge = "3 proposte"),
                VoceBarra("Lavori", rememberVectorPainter(Icons.Filled.Build), badge = 120),
                VoceBarra("Tempo", rememberVectorPainter(Icons.Filled.DateRange)),
            ),
            selezionata = 0,
            onSeleziona = {},
        )
    }
}

@Composable
private fun PaginaMenu() {
    Column(Modifier.padding(Spazi.l + Spazi.xs), verticalArrangement = Arrangement.spacedBy(Spazi.l)) {
        CardNormale {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sara", style = MaterialTheme.typography.titleMedium)
                    Pillola("In pausa")
                }
                MenuAzioni(
                    voci = listOf(
                        VoceMenu("Rinomina", {}),
                        VoceMenu("Metti in pausa", {}, abilitata = false),
                        VoceMenu("Scollega il telefono", {}, distruttiva = true),
                    ),
                    descrizione = "Altre azioni per Sara",
                    modifier = Modifier,
                    // Solo per la foto: il menu già aperto (overload interno di core-design).
                    apertoAllInizio = true,
                )
            }
        }
        StatoVuoto(
            "Quando Luca manda una proposta, la trovi qui.",
            titolo = "Niente da decidere",
            azione = "Aggiorna",
            onAzione = {},
            centrato = true,
        )
    }
}

@Composable
private fun PaginaFoglio() {
    Column(Modifier.padding(Spazi.l + Spazi.xs), verticalArrangement = Arrangement.spacedBy(Spazi.l)) {
        TitoloSezione("La pagina sotto il foglio")
        CardNormale { Text("Instagram al massimo 1 h") }
    }
    FoglioDalBasso(onChiudi = {}, titolo = "Instagram al massimo 1 h") {
        RigaToccabile("Chiedi più tempo", onClick = {})
        RigaToccabile("Vedi i giorni", onClick = {}, sottotitolo = "Gli ultimi 8 giorni della regola")
        Spacer(Modifier.height(Spazi.m))
        FilaPulsanti(Modifier.fillMaxWidth()) {
            Button(onClick = {}) { Text("Chiudi") }
        }
    }
}
