package eu.stgm.pactum.figlio.servizio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import eu.stgm.pactum.figlio.BuildConfig
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.dati.Battito
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.rete.PostinoClient
import eu.stgm.pactum.figlio.valutatore.SentinellaPatto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * FGS di tipo specialUse (v. manifest: PROPERTY_SPECIAL_USE_FGS_SUBTYPE).
 *
 * Canale PRIMARIO del battito (decisione di design, review 14/07): di notte
 * Doze rinvia WorkManager anche di 2-6 ore, e ogni mattina la finestra del
 * genitore mostrerebbe un falso "silente". Il servizio è foreground e
 * l'esenzione batteria concede la rete anche in Doze, quindi il loop qui
 * dentro manda un battito ogni ~15 minuti; BattitoWorker resta come misura +
 * mittente di riserva. I doppi battiti sono innocui lato server.
 *
 * Il vero loop di valutazione regole quasi-real-time arriverà in una tappa
 * successiva; la misura vive comunque in BattitoWorker (design retroattivo),
 * quindi la morte di questo servizio non buca il registro: al massimo
 * riconsegna il battito al worker.
 */
class PactumService : Service() {

    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopBattito: Job? = null

    override fun onCreate() {
        super.onCreate()
        creaCanale()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            ID_NOTIFICA,
            notificaTestimone(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
        avviaLoopBattito()
        return START_STICKY
    }

    override fun onDestroy() {
        ambito.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Idempotente: onStartCommand può arrivare più volte, il loop è uno solo. */
    private fun avviaLoopBattito() {
        if (loopBattito?.isActive == true) return
        loopBattito = ambito.launch {
            while (isActive) {
                inviaBattito()
                // Sentinella quasi-real-time (tappa 5): valuta l'uso di oggi
                // contro la copia locale del patto. Dedup interno (una per
                // regola per giorno); un errore qui non deve uccidere il
                // battito, che è la promessa più vecchia.
                try {
                    SentinellaPatto(applicationContext).valuta()
                } catch (e: Exception) {
                    // meglio un giro senza valutazione che un testimone morto
                }
                delay(INTERVALLO_BATTITO_MS)
            }
        }
    }

    private suspend fun inviaBattito() {
        val impostazioni = Impostazioni(applicationContext)
        val configurazione = impostazioni.leggiConfigurazione()
        if (!configurazione.completa) return // patto non ancora configurato
        val consegnato = PostinoClient(configurazione).inviaBattito(
            Battito(
                tsDevice = System.currentTimeMillis(),
                versioneApp = BuildConfig.VERSION_NAME,
                elapsedRealtime = SystemClock.elapsedRealtime(),
            ),
        )
        if (consegnato) impostazioni.registraBattitoConsegnato()
    }

    private fun notificaTestimone(): Notification =
        NotificationCompat.Builder(this, CANALE_TESTIMONE)
            .setSmallIcon(R.drawable.ic_notifica_testimone)
            .setContentTitle(getString(R.string.notifica_testimone_titolo))
            .setContentText(getString(R.string.notifica_testimone_testo))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun creaCanale() {
        val canale = NotificationChannel(
            CANALE_TESTIMONE,
            getString(R.string.canale_testimone_nome),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = getString(R.string.canale_testimone_descrizione)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(canale)
    }

    companion object {
        private const val CANALE_TESTIMONE = "testimone"
        private const val ID_NOTIFICA = 1
        private const val INTERVALLO_BATTITO_MS = 15L * 60 * 1000

        fun avvia(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PactumService::class.java))
        }
    }
}
