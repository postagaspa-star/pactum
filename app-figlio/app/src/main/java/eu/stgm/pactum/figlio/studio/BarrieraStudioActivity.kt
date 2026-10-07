package eu.stgm.pactum.figlio.studio

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.design.attivaBordoPieno
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.catalogo.CatalogoApp
import eu.stgm.pactum.figlio.sessione.AppDellaSessione
import eu.stgm.pactum.figlio.ui.theme.PactumTheme
import kotlinx.coroutines.delay

/**
 * (0.18, contratto v4.0) La barriera dello Studio: «Sei in Studio», sopra
 * un'app che non è nella lista. La lista (cosa si può usare) e un solo
 * pulsante, **Esci**, che porta alla schermata Home; il tasto indietro fa lo
 * stesso. Se si torna nell'app, il servizio la riapre (SorveglianzaStudio).
 *
 * Mai una trappola, come quella delle Sessioni: parte solo se lo Studio c'è
 * e si chiude appena finisce; mai sopra il blocco schermo; task sua, fuori
 * dalle recenti; Pactum, la Home, il Telefono e le Impostazioni non li copre.
 */
class BarrieraStudioActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        attivaBordoPieno()
        super.onCreate(savedInstanceState)
        if (!StatoStudio.inCorsoAdesso()) {
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
                val memoria by StatoStudio.memoria.collectAsState()
                LaunchedEffect(memoria) {
                    while (true) {
                        if (!StatoStudio.inCorsoAdesso()) {
                            finish()
                            return@LaunchedEffect
                        }
                        delay(CONTROLLO_MS)
                    }
                }
                val studio = StatoStudio.attivoAdesso()
                SchermataBarrieraStudio(studio = studio, onEsci = { esci() })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!StatoStudio.inCorsoAdesso()) {
            finish()
            return
        }
        visibile = true
        // Arrivata sullo schermo: non conta per l'interruttore di sicurezza (ContiBarriera).
        comparse += 1
    }

    override fun onPause() {
        visibile = false
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && schermoAcceso()) finish()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        uscite += 1
        finish()
    }

    /** «Esci»: alla schermata Home. */
    private fun esci() {
        uscite += 1
        try {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
        private const val CONTROLLO_MS = 2_000L

        @Volatile
        var visibile: Boolean = false
            private set

        @Volatile
        var uscite: Int = 0
            private set

        /** Quante volte la barriera è arrivata sullo schermo (onResume). */
        @Volatile
        var comparse: Int = 0
            private set

        fun apri(context: Context): Boolean {
            val intent = Intent(context, BarrieraStudioActivity::class.java)
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

/** «Sei in Studio», la lista, come si chiude; in basso, sotto il pollice, «Esci». */
@Composable
fun SchermataBarrieraStudio(studio: StudioAttivo?, onEsci: () -> Unit) {
    val context = LocalContext.current
    val zona = StatoStudio.memoria.value.zona()
    val nomi = studio?.let { s ->
        s.app.map { chiave ->
            when (chiave) {
                AppDellaSessione.GRUPPO_APK -> context.getString(R.string.gruppo_apk_nome)
                else -> CatalogoApp.etichettaValore(context, chiave)
            }
        }
    }.orEmpty()
    Scaffold { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spazi.xl, vertical = Spazi.xxl),
                verticalArrangement = Arrangement.spacedBy(Spazi.l),
            ) {
                Text(text = stringResource(R.string.studio_barriera_titolo), style = MaterialTheme.typography.headlineSmall)
                Text(text = stringResource(R.string.studio_barriera_testo), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (nomi.isEmpty()) {
                        stringResource(R.string.studio_lista_vuota)
                    } else {
                        stringResource(R.string.studio_puoi_usare, nomi.joinToString(", "))
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.studio_sempre_usabili),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                studio?.chiudibileDal?.let {
                    Text(
                        text = stringResource(R.string.studio_barriera_chiusura, TestoStudio.ora(it, zona), studio.minutiMinimi),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spazi.xl, vertical = Spazi.l)) {
                Button(onClick = onEsci, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.barriera_esci))
                }
            }
        }
    }
}
