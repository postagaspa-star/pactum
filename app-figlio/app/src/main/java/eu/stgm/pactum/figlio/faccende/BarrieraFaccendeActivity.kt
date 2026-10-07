package eu.stgm.pactum.figlio.faccende

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import eu.stgm.pactum.design.attivaBordoPieno
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.figlio.MainActivity
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.ui.theme.PactumTheme
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.time.ZoneId

/**
 * (0.13) La barriera delle faccende: "Prima i lavori di casa", sopra un'app che
 * durante il blocco non si usa. L'elenco delle faccende da fare con chi le ha
 * date, e un solo pulsante, "Apri Pactum", che porta alla pagina Faccende
 * (dove c'è "Scatta la foto"); il tasto indietro fa lo stesso. Se si torna
 * nell'app, il servizio la riapre (SorveglianzaFaccende).
 *
 * Come la barriera delle Sessioni (BarrieraActivity), mai una trappola:
 *  - parte solo se il telefono è davvero bloccato, e si chiude da sola appena
 *    il blocco finisce;
 *  - non compare mai sopra il blocco schermo (niente showWhenLocked);
 *  - Pactum, la Home, il Telefono, gli SMS e le Impostazioni non li copre mai;
 *  - task suo, fuori dalle app recenti: uscendo (Home, un'altra app davanti)
 *    si chiude, non resta nascosta sotto.
 */
class BarrieraFaccendeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // (0.15) Bordo pieno con le icone scure della barra di stato (B11): lo
        // Scaffold qui sotto tiene il contenuto fuori dalle barre di sistema.
        attivaBordoPieno()
        super.onCreate(savedInstanceState)
        if (!StatoBlocco.applicatoAdesso()) {
            finish()
            return
        }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = apriPactum()
            },
        )
        setContent {
            PactumTheme {
                // Il blocco finisce (l'ultima foto è arrivata, una faccenda
                // annullata): la barriera se ne va subito.
                val memoria by StatoBlocco.memoria.collectAsState()
                // (0.17) L'ora del server, per dire quali lavori bloccano adesso.
                var ora by remember { mutableStateOf(Orologio.adesso()) }
                LaunchedEffect(memoria) {
                    while (true) {
                        ora = Orologio.adesso()
                        // (0.18) Anche quando parte la Sessione Studio: il blocco aspetta.
                        if (!StatoBlocco.applicatoAdesso(ora)) {
                            finish()
                            return@LaunchedEffect
                        }
                        delay(CONTROLLO_MS)
                    }
                }
                val adesso = memoria.oraServer(ora)
                // (0.18, contratto v4.0) Due gruppi: «Da fare» e «Aspettano l'approvazione».
                val (aspettano, daFare) = memoria.daFare.partition { it.aspettaApprovazione }
                SchermataBarrieraFaccende(
                    divisi = VistaFaccende.divisi(daFare, { it.bloccoDa }, adesso, bloccato = aspettano.isEmpty()),
                    inApprovazione = aspettano,
                    approvazione = memoria.approvazione,
                    adesso = adesso,
                    onApri = { apriPactum() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!StatoBlocco.applicatoAdesso()) {
            finish()
            return
        }
        visibile = true
        // Arrivata sullo schermo: questa apertura non conta per l'interruttore di sicurezza.
        comparse += 1
    }

    override fun onPause() {
        visibile = false
        super.onPause()
    }

    /** Coperta da un'altra app o lasciata con Home: si chiude. Con lo schermo spento resta. */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && schermoAcceso()) finish()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        uscite += 1
        finish()
    }

    /** "Apri Pactum": la pagina Faccende. Se Android non la apre, la barriera si chiude comunque. */
    private fun apriPactum() {
        uscite += 1
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(MainActivity.EXTRA_DESTINAZIONE, MainActivity.DEST_FACCENDE),
            )
        } catch (e: Exception) {
            // Pactum non si apre: si chiude e basta
        }
        finish()
    }

    private fun schermoAcceso(): Boolean =
        try {
            getSystemService(PowerManager::class.java)?.isInteractive ?: true
        } catch (e: Exception) {
            true
        }

    companion object {
        private const val CONTROLLO_MS = 2_000L

        /** La barriera è davanti adesso: il servizio non la riapre sopra se stessa. */
        @Volatile
        var visibile: Boolean = false
            private set

        /** Quante volte si è usciti dalla barriera: il servizio riguarda da capo l'app davanti. */
        @Volatile
        var uscite: Int = 0
            private set

        /** Quante volte la barriera è arrivata sullo schermo (RitmoBarriera.comparsa). */
        @Volatile
        var comparse: Int = 0
            private set

        /** Apre la barriera sopra l'app in uso. False se Android non l'ha lasciata partire. */
        fun apri(context: Context): Boolean {
            val intent = Intent(context, BarrieraFaccendeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            return try {
                context.startActivity(intent)
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}

/**
 * Il titolo, i lavori che bloccano adesso con chi li ha dati; (0.17) sotto, in
 * piccolo, quelli che bloccheranno più tardi con la loro ora ("Poi, dalle
 * 18:00: Letto"); in basso, sotto il pollice, "Apri Pactum".
 */
@Composable
private fun SchermataBarrieraFaccende(
    divisi: VistaFaccende.Divisi<FaccendaDaFare>,
    inApprovazione: List<FaccendaDaFare>,
    approvazione: Boolean,
    adesso: Long,
    onApri: () -> Unit,
) {
    val context = LocalContext.current
    Scaffold { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spazi.xl, vertical = Spazi.xxl),
                verticalArrangement = Arrangement.spacedBy(Spazi.l),
            ) {
                Text(
                    text = stringResource(R.string.barriera_faccende_titolo),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(if (approvazione) R.string.barriera_faccende_testo_approvazione else R.string.barriera_faccende_testo),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (inApprovazione.isNotEmpty() && divisi.adesso.isNotEmpty()) {
                    Text(text = stringResource(R.string.barriera_faccende_da_fare), style = MaterialTheme.typography.labelLarge)
                }
                divisi.adesso.forEach { faccenda ->
                    Column(verticalArrangement = Arrangement.spacedBy(Spazi.xs)) {
                        Text(
                            text = faccenda.titolo.ifBlank { stringResource(R.string.faccenda_senza_titolo) },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        faccenda.genitore?.let {
                            Text(
                                text = stringResource(R.string.faccenda_da, it),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (divisi.poi.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spazi.xs)) {
                        divisi.poi.forEach { faccenda ->
                            Text(
                                text = testoPoi(context, faccenda, adesso),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                // (0.18, contratto v4.0) La foto è arrivata: si aspetta un genitore.
                if (inApprovazione.isNotEmpty()) {
                    Text(text = stringResource(R.string.barriera_faccende_approvazione), style = MaterialTheme.typography.labelLarge)
                    inApprovazione.forEach { faccenda ->
                        Column(verticalArrangement = Arrangement.spacedBy(Spazi.xs)) {
                            Text(
                                text = faccenda.titolo.ifBlank { stringResource(R.string.faccenda_senza_titolo) },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = eu.stgm.pactum.figlio.ui.testoFotoMandata(context, faccenda.fotoIl, adesso),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.faccende_usabili),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spazi.xl, vertical = Spazi.l),
            ) {
                Button(onClick = onApri, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.barriera_faccende_apri))
                }
            }
        }
    }
}

/** (0.17) "Poi, dalle 18:00: Letto", "Poi, domani dalle 9:00: …", "Poi, giovedì dalle 16:00: …". */
private fun testoPoi(context: Context, faccenda: FaccendaDaFare, adesso: Long): String {
    val titolo = faccenda.titolo.ifBlank { context.getString(R.string.faccenda_senza_titolo) }
    return when (val quando = TestoFaccende.quandoBlocca(faccenda.bloccoDa, adesso, ZoneId.systemDefault())) {
        // Non succede (i lavori di "poi" non sono ancora partiti): il titolo e basta.
        QuandoBlocca.Subito -> titolo
        is QuandoBlocca.Oggi -> context.getString(R.string.barriera_faccende_poi_alle, quando.ora, titolo)
        is QuandoBlocca.Domani -> context.getString(R.string.barriera_faccende_poi_domani, quando.ora, titolo)
        is QuandoBlocca.Giorno -> context.getString(R.string.barriera_faccende_poi_giorno, quando.giorno, quando.ora, titolo)
        is QuandoBlocca.Data -> context.getString(R.string.barriera_faccende_poi_giorno, quando.data, quando.ora, titolo)
    }
}
