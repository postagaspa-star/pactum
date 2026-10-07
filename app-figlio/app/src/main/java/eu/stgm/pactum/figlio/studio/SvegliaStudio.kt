package eu.stgm.pactum.figlio.studio

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import eu.stgm.pactum.figlio.permessi.PermessiHelper
import eu.stgm.pactum.figlio.servizio.PactumService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * (0.18, contratto v4.0) La sveglia ESATTA dello Studio, come quella del
 * blocco dei lavori (SvegliaFaccende): 5 minuti prima della partenza
 * (l'avviso), alla partenza (lo Studio parte anche a schermo spento e senza
 * rete) e alla mezzanotte dello Studio in corso. Contata sull'orologio che
 * non si sposta: spostare l'ora del telefono non la anticipa né la ritarda.
 */
object SvegliaStudio {

    private const val AZIONE = "eu.stgm.pactum.figlio.SVEGLIA_STUDIO"

    /** La sveglia per [quando] (SystemClock.elapsedRealtime). Sostituisce quella di prima. */
    fun programma(context: Context, quando: Long) {
        val allarmi = allarmi(context) ?: return
        val sveglia = intentSveglia(context)
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || allarmi.canScheduleExactAlarms()) {
                allarmi.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, quando, sveglia)
                return
            }
        } catch (e: SecurityException) {
            // permesso tolto nel frattempo: si ripiega sulla sveglia inesatta
        }
        try {
            allarmi.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, quando, sveglia)
        } catch (e: Exception) {
            // niente sveglia: lo Studio parte comunque al primo giro dopo l'ora
        }
    }

    fun annulla(context: Context) {
        try {
            allarmi(context)?.cancel(intentSveglia(context))
        } catch (e: Exception) {
            // niente da annullare
        }
    }

    private fun allarmi(context: Context): AlarmManager? = context.applicationContext.getSystemService(AlarmManager::class.java)

    private fun intentSveglia(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context.applicationContext,
        1,
        Intent(context.applicationContext, SvegliaStudioReceiver::class.java).setAction(AZIONE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** Riceve la sveglia dello Studio: sveglia il giro, rimette in piedi il servizio, e fa il giro dello Studio. */
class SvegliaStudioReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        StatoStudio.svegliati()
        ControlloStudio.dimenticaSveglia()
        if (!PactumService.vivo && PermessiHelper.haAccessoUso(app)) {
            try {
                PactumService.avvia(app)
            } catch (e: Exception) {
                // rifiutato: lo riaccende il worker al suo giro
            }
        }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ControlloStudio.dopo(app)
            } catch (e: Exception) {
                // al prossimo giro
            } finally {
                pending.finish()
            }
        }
    }
}
