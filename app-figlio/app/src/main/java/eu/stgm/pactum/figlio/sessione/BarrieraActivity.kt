package eu.stgm.pactum.figlio.sessione

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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.ui.theme.PactumTheme
import kotlinx.coroutines.delay
import java.time.ZoneId

/**
 * (0.11) La barriera di una Sessione: "Sei in sessione «Studio»", sopra
 * un'app che non è nella sessione. Un solo pulsante, "Esci", che porta alla
 * schermata Home; il tasto indietro fa lo stesso. Non si chiude per restare
 * nell'app: se ci si torna, il servizio la riapre (SorveglianzaSessione).
 *
 * Mai una trappola:
 *  - parte solo se c'è davvero una sessione in corso, e si chiude da sola
 *    appena la sessione finisce, scade o viene terminata;
 *  - non compare mai sopra il blocco schermo (niente showWhenLocked);
 *  - Pactum, la Home, il Telefono e le Impostazioni non li copre mai, quindi
 *    "Termina la sessione" (in Pactum) si raggiunge sempre;
 *  - qualsiasi cosa non torni (dati mancanti, sessione diversa) = si chiude.
 *
 * Come l'avviso a tutto schermo (AvvisoActivity): task suo, fuori dalle app
 * recenti, aperta dal servizio grazie a "Mostra sopra le altre app". Uscendo
 * (Home, recenti, un'altra app davanti) si chiude: non resta nascosta sotto.
 */
class BarrieraActivity : ComponentActivity() {

    private val nome = mutableStateOf("")
    private val fine = mutableLongStateOf(0L)
    private var svoltaId = NESSUNA

    override fun onCreate(savedInstanceState: Bundle?) {
        // (0.15) Bordo pieno con le icone scure della barra di stato (B11): lo
        // Scaffold qui sotto tiene il contenuto fuori dalle barre di sistema.
        attivaBordoPieno()
        super.onCreate(savedInstanceState)
        if (!leggi(intent)) {
            finish()
            return
        }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = esci()
            },
        )
        setContent {
            PactumTheme {
                // La sessione finisce (terminata in Pactum, scaduta, sparita):
                // la barriera se ne va subito, e l'app sotto torna libera.
                val attiva by StatoSessione.attiva.collectAsState()
                LaunchedEffect(attiva, fine.longValue) {
                    val adesso = attiva
                    if (adesso == null || adesso.svoltaId != svoltaId) {
                        finish()
                        return@LaunchedEffect
                    }
                    val resta = adesso.fine - System.currentTimeMillis()
                    if (resta > 0) delay(resta)
                    finish()
                }
                SchermataBarriera(nome = nome.value, fine = fine.longValue, onEsci = { esci() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!leggi(intent)) finish()
    }

    override fun onResume() {
        super.onResume()
        if (StatoSessione.attivaAdesso()?.svoltaId != svoltaId) {
            finish()
            return
        }
        visibile = true
    }

    override fun onPause() {
        visibile = false
        super.onPause()
    }

    /**
     * Coperta da un'altra app (una notifica toccata, una chiamata) o lasciata
     * con Home: si chiude. Con lo schermo spento resta: allo sblocco c'è ancora.
     */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && schermoAcceso()) finish()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Uscita con Home: come "Esci".
        uscite += 1
        finish()
    }

    /**
     * I dati della sessione in corso: solo se è proprio quella per cui è stata
     * aperta, e il ragazzo sa che è partita.
     */
    private fun leggi(intent: Intent?): Boolean {
        val attiva = StatoSessione.attivaAdesso()?.takeIf { it.annunciata } ?: return false
        val richiesta = intent?.getLongExtra(EXTRA_SVOLTA, NESSUNA) ?: NESSUNA
        if (richiesta != attiva.svoltaId) return false
        svoltaId = attiva.svoltaId
        nome.value = attiva.nome
        fine.longValue = attiva.fine
        return true
    }

    /** "Esci": alla schermata Home. Se Android non la apre, la barriera si chiude comunque. */
    private fun esci() {
        // Il servizio riguarda da capo l'app davanti: tornarci subito la copre di nuovo.
        uscite += 1
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            // nessuna Home da aprire: si chiude e basta
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
        private const val EXTRA_SVOLTA = "svolta_id"
        private const val NESSUNA = -1L

        /** La barriera è davanti adesso: il servizio non la riapre sopra se stessa. */
        @Volatile
        var visibile: Boolean = false
            private set

        /** Quante volte si è usciti dalla barriera ("Esci", indietro, Home): il servizio riguarda da capo. */
        @Volatile
        var uscite: Int = 0
            private set

        /**
         * Apre la barriera sopra l'app in uso. False se non è partita (niente
         * "Mostra sopra le altre app", Android l'ha rifiutata): nessun danno,
         * il tempo fuori dalla sessione conta come sempre.
         */
        fun apri(context: Context, attiva: SessioneAttiva): Boolean {
            // NO_USER_ACTION: per l'app sotto non è "l'utente se ne va" (un video
            // non passa in riquadro, come per l'avviso a tutto schermo).
            val intent = Intent(context, BarrieraActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra(EXTRA_SVOLTA, attiva.svoltaId)
            return try {
                context.startActivity(intent)
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}

/** Il titolo, due righe, quando finisce; in basso, sotto il pollice, "Esci" (l'unico pulsante). */
@Composable
private fun SchermataBarriera(nome: String, fine: Long, onEsci: () -> Unit) {
    val context = LocalContext.current
    val quando = TestoSessioni.quandoFinisce(fine, System.currentTimeMillis(), ZoneId.systemDefault())
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
                    text = stringResource(R.string.barriera_titolo, nomeSessioneTraVirgolette(context, nome)),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(R.string.barriera_testo),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.barriera_aggiungila, nomeSessioneTraVirgolette(context, nome, conEmoji = false)),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = context.getString(
                        if (quando.domani) R.string.barriera_fine_domani else R.string.barriera_fine,
                        quando.ora,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.barriera_terminare),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spazi.xl, vertical = Spazi.l),
            ) {
                Button(onClick = onEsci, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.barriera_esci))
                }
            }
        }
    }
}
