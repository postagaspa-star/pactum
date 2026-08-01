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
import eu.stgm.pactum.figlio.permessi.PermessiHelper
import eu.stgm.pactum.figlio.servizio.PactumService
import eu.stgm.pactum.figlio.siti.OsservazioneSiti
import eu.stgm.pactum.figlio.sync.BattitoWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Al riavvio del telefono: ripianifica il battito, riaccende il testimone e
 * marca il reboot nel registro. Il marcatore serve perché elapsedRealtime si
 * azzera al riavvio (architettura.md): senza, l'azzeramento sembrerebbe una
 * manomissione.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        BattitoWorker.pianifica(context)

        // La notifica "Pactum sta facendo da testimone" deve tornare da sola
        // dopo il riavvio: senza, la promessa di trasparenza si rompe in
        // silenzio finché qualcuno non riapre l'app. FGS specialUse avviabile
        // da BOOT_COMPLETED (architettura.md); il controllo sull'accesso ai
        // dati di utilizzo evita di partire prima dell'onboarding.
        if (PermessiHelper.haAccessoUso(context)) {
            PactumService.avvia(context)
        }

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val adesso = System.currentTimeMillis()
                val elapsed = SystemClock.elapsedRealtime()
                // Nuova ancora subito: l'orologio post-riavvio è la nuova base.
                Impostazioni(context).salvaAncoraTempo(AncoraTempo(adesso, elapsed))
                // L'osservazione dei siti (v2.3) non sopravvive da sola al
                // riavvio: se il figlio l'aveva accesa e il consenso VPN c'è
                // ancora, riparte qui. Senza, il registro dei siti si
                // interromperebbe in silenzio a ogni spegnimento.
                OsservazioneSiti.riprendiSeConsentita(context)
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
