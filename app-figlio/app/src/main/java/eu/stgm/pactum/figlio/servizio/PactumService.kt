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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import eu.stgm.pactum.figlio.R

/**
 * FGS di tipo specialUse (v. manifest: PROPERTY_SPECIAL_USE_FGS_SUBTYPE).
 * Scheletro: per ora tiene solo la notifica del testimone. Il vero loop di
 * valutazione regole quasi-real-time arriverà in una tappa successiva; la
 * misura vive comunque in BattitoWorker (design retroattivo), quindi la morte
 * di questo servizio non buca il registro.
 */
class PactumService : Service() {

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
        // Qui arriverà il loop di valutazione delle regole (tappe successive).
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

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

        fun avvia(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PactumService::class.java))
        }
    }
}
