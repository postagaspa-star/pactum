package eu.stgm.pactum.figlio.tempo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import eu.stgm.pactum.figlio.dati.AncoraTempo
import eu.stgm.pactum.figlio.dati.CodaEventi
import eu.stgm.pactum.figlio.dati.Evento
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.dati.TipiEvento
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.TimeZone
import kotlin.math.abs

/**
 * Cambio di ora o fuso → evento manomissione nel registro.
 *
 * TIME_SET (la costante Intent.ACTION_TIME_CHANGED) arriva anche per la
 * sincronizzazione automatica dell'ora: si confronta il nuovo orologio con
 * l'ancora (wall clock vs elapsedRealtime) e si registra solo uno scarto
 * sopra soglia. Se in mezzo c'è stato un riavvio l'ancora non è confrontabile
 * e non si segnala nulla: il riavvio l'ha già marcato BootReceiver.
 * In ogni caso il timestamp che fa fede resta ts_server: qui si aggiunge solo
 * trasparenza locale.
 */
class OrologioReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val azione = intent.action ?: return
        if (azione != Intent.ACTION_TIME_CHANGED && azione != Intent.ACTION_TIMEZONE_CHANGED) return

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                gestisci(context, azione)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun gestisci(context: Context, azione: String) {
        val impostazioni = Impostazioni(context)
        val coda = CodaEventi(context)
        val adesso = System.currentTimeMillis()
        val elapsedAdesso = SystemClock.elapsedRealtime()

        if (azione == Intent.ACTION_TIMEZONE_CHANGED) {
            // Il fuso non sposta l'epoch: l'ancora resta valida.
            coda.accoda(
                Evento(
                    tipo = TipiEvento.MANOMISSIONE,
                    tsDevice = adesso,
                    dettagli = buildJsonObject {
                        put("sotto_tipo", "cambio_fuso")
                        put("fuso", TimeZone.getDefault().id)
                    },
                ),
            )
            return
        }

        val ancora = impostazioni.leggiAncoraTempo()
        val riavviatoNelFrattempo = ancora != null && elapsedAdesso < ancora.elapsedRealtime
        if (ancora != null && !riavviatoNelFrattempo) {
            val attesa = ancora.wallClock + (elapsedAdesso - ancora.elapsedRealtime)
            val scarto = adesso - attesa
            if (abs(scarto) > SOGLIA_SCARTO_MS) {
                coda.accoda(
                    Evento(
                        tipo = TipiEvento.MANOMISSIONE,
                        tsDevice = adesso,
                        dettagli = buildJsonObject {
                            put("sotto_tipo", "cambio_ora")
                            // Il contratto vuole secondi; il segno dice la direzione.
                            put("drift_secondi", scarto / 1000)
                        },
                    ),
                )
            }
        }
        // Qualunque sia l'esito, il nuovo orologio è la nuova base.
        impostazioni.salvaAncoraTempo(AncoraTempo(adesso, elapsedAdesso))
    }

    private companion object {
        // Sotto i 2 minuti = sincronizzazione automatica, non una mano umana.
        const val SOGLIA_SCARTO_MS = 2 * 60 * 1000L
    }
}
