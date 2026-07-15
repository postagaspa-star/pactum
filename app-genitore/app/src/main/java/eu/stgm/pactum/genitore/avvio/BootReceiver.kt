package eu.stgm.pactum.genitore.avvio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import eu.stgm.pactum.genitore.sync.VedettaWorker

/** Al riavvio del telefono la vedetta riparte da sola. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        VedettaWorker.pianifica(context)
    }
}
