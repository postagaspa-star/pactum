package eu.stgm.pactum.figlio.sessione

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * (0.12) L'app e la schermata in primo piano adesso, dagli eventi d'uso
 * dell'ultima mezz'ora (lo stesso conto della barriera: TracciaPrimoPiano).
 * Serve alla pagina della fine di una sessione: come la barriera, non si apre
 * sopra la schermata di una chiamata via internet (ClassiAttivita.chiamata).
 * Se non si sa: niente app (attuale e classe null).
 */
object PrimoPianoAdesso {

    private const val INDIETRO_MS = 30L * 60 * 1000

    fun leggi(context: Context, adesso: Long = System.currentTimeMillis()): TracciaPrimoPiano {
        val traccia = TracciaPrimoPiano()
        try {
            val usm = context.applicationContext.getSystemService(UsageStatsManager::class.java) ?: return traccia
            val eventi = usm.queryEvents(adesso - INDIETRO_MS, adesso + 1) ?: return traccia
            val evento = UsageEvents.Event()
            while (eventi.hasNextEvent()) {
                eventi.getNextEvent(evento)
                traccia.evento(evento.eventType, evento.packageName, evento.timeStamp, evento.className)
            }
        } catch (e: Exception) {
            // eventi non letti: non si sa chi c'è davanti
        }
        return traccia
    }

    /** È aperta la schermata di una chiamata via internet (WhatsApp, Telegram, Meet…)? */
    fun inChiamata(context: Context, adesso: Long = System.currentTimeMillis()): Boolean =
        ClassiAttivita.chiamata(leggi(context, adesso).classe)
}
