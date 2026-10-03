package eu.stgm.pactum.figlio.faccende

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
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

/**
 * (0.13) La barriera delle faccende: "Prima le faccende", sopra un'app che
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
        super.onCreate(savedInstanceState)
        if (!StatoBlocco.attivoAdesso()) {
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
                LaunchedEffect(memoria) {
                    while (true) {
                        if (!memoria.attivoAdesso(Orologio.adesso())) {
                            finish()
                            return@LaunchedEffect
                        }
                        delay(CONTROLLO_MS)
                    }
                }
                SchermataBarrieraFaccende(daFare = memoria.daFare, onApri = { apriPactum() })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!StatoBlocco.attivoAdesso()) {
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

/** Il titolo, l'elenco con chi ha dato ogni faccenda; in basso, sotto il pollice, "Apri Pactum". */
@Composable
private fun SchermataBarrieraFaccende(daFare: List<FaccendaDaFare>, onApri: () -> Unit) {
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
                    text = stringResource(R.string.barriera_faccende_testo),
                    style = MaterialTheme.typography.bodyLarge,
                )
                daFare.forEach { faccenda ->
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
                Text(
                    text = stringResource(R.string.faccende_usabili),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
