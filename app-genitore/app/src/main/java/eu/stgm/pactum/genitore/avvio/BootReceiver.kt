package eu.stgm.pactum.genitore.avvio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import eu.stgm.pactum.genitore.servizio.VedettaService
import eu.stgm.pactum.genitore.sync.VedettaWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Al riavvio del telefono la vedetta riparte da sola: il worker di riserva e
 * (0.9) il servizio sempre attivo, se l'app è configurata. Lo stesso subito
 * dopo un aggiornamento dell'app (MY_PACKAGE_REPLACED): l'aggiornamento chiude
 * l'app, e senza questo il servizio aspetterebbe la prossima apertura. Tutti e
 * due i momenti sono tra quelli in cui Android lascia avviare un servizio in
 * primo piano da dietro le quinte.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        VedettaWorker.pianifica(context)

        // La configurazione sta in DataStore: si legge fuori dal thread principale.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                VedettaService.avviaSeConfigurata(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}
