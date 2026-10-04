package eu.stgm.pactum.figlio.faccende

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
 * (0.13) La sveglia del blocco programmato: all'ora di `prossimo` il telefono
 * si blocca da solo anche senza rete (MemoriaBlocco), ma a schermo spento il
 * processore dorme e un "aspetta fino alle 16" dorme con lui. Così si chiede
 * ad Android una sveglia ESATTA (setExactAndAllowWhileIdle, come la vedetta
 * dell'app del genitore): quando suona, il giro delle faccende si sveglia,
 * dice "Prima i lavori di casa" e, se il servizio non c'è, lo rimette in piedi.
 *
 * USE_EXACT_ALARM da Android 13 (concesso all'installazione), SCHEDULE_EXACT_ALARM
 * su Android 12. Se Android non concede la sveglia esatta si ripiega su quella
 * inesatta: il blocco parte comunque all'ora giusta appena lo schermo si
 * riaccende (il giro guarda l'ora), solo l'avviso può arrivare un po' dopo.
 */
object SvegliaFaccende {

    private const val AZIONE = "eu.stgm.pactum.figlio.SVEGLIA_FACCENDE"

    /**
     * La sveglia per [quando] sull'orologio che non si sposta
     * (SystemClock.elapsedRealtime): spostare l'ora del telefono non la
     * anticipa né la ritarda. Sostituisce quella di prima.
     */
    fun programma(context: Context, quando: Long) {
        val allarmi = allarmi(context) ?: return
        val sveglia = intentSveglia(context)
        try {
            if (esattePermesse(allarmi)) {
                allarmi.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, quando, sveglia)
                return
            }
        } catch (e: SecurityException) {
            // permesso tolto nel frattempo: si ripiega sulla sveglia inesatta
        }
        try {
            allarmi.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, quando, sveglia)
        } catch (e: Exception) {
            // niente sveglia: il blocco parte comunque allo sblocco dello schermo
        }
    }

    fun annulla(context: Context) {
        try {
            allarmi(context)?.cancel(intentSveglia(context))
        } catch (e: Exception) {
            // niente da annullare
        }
    }

    private fun esattePermesse(allarmi: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || allarmi.canScheduleExactAlarms()

    private fun allarmi(context: Context): AlarmManager? =
        context.applicationContext.getSystemService(AlarmManager::class.java)

    private fun intentSveglia(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context.applicationContext,
        0,
        Intent(context.applicationContext, SvegliaFaccendeReceiver::class.java).setAction(AZIONE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/**
 * Riceve la sveglia del blocco: sveglia il giro delle faccende e, se il
 * servizio non c'è più, lo rimette in piedi (la sveglia esatta lo permette).
 * In ogni caso l'avviso "Prima i lavori di casa" parte da qui.
 */
class SvegliaFaccendeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        StatoBlocco.svegliati()
        ControlloBlocco.richiedi()
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
                ControlloBlocco.dopo(app)
            } catch (e: Exception) {
                // l'avviso arriva al prossimo giro
            } finally {
                pending.finish()
            }
        }
    }
}
