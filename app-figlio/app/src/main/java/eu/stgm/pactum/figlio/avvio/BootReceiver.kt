package eu.stgm.pactum.figlio.avvio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import eu.stgm.pactum.figlio.dati.AncoraTempo
import eu.stgm.pactum.figlio.dati.CodaEventi
import eu.stgm.pactum.figlio.dati.Evento
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.dati.TipiEvento
import eu.stgm.pactum.figlio.sync.BattitoWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Al riavvio del telefono: ripianifica il battito e marca il reboot nel
 * registro. Il marcatore serve perché elapsedRealtime si azzera al riavvio
 * (architettura.md): senza, l'azzeramento sembrerebbe una manomissione.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        BattitoWorker.pianifica(context)

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val adesso = System.currentTimeMillis()
                val elapsed = SystemClock.elapsedRealtime()
                // Nuova ancora subito: l'orologio post-riavvio è la nuova base.
                Impostazioni(context).salvaAncoraTempo(AncoraTempo(adesso, elapsed))
                CodaEventi(context).accoda(
                    Evento(
                        tipo = TipiEvento.RIAVVIO,
                        tsDevice = adesso,
                        dettagli = buildJsonObject { put("elapsed_realtime", elapsed) },
                    ),
                )
            } finally {
                pending.finish()
            }
        }
    }
}
