package eu.stgm.pactum.figlio.misura

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.time.LocalDate
import java.time.ZoneId

data class UsoApp(val pacchetto: String, val millisPrimoPiano: Long)

/**
 * Calcola il tempo in primo piano per app da queryEvents(), accoppiando
 * ACTIVITY_RESUMED/ACTIVITY_PAUSED per pacchetto. Come da architettura.md:
 * i bucket INTERVAL_DAILY divergono da Digital Wellbeing e non si usano.
 *
 * Confine di mezzanotte e sessioni ancora aperte:
 * - una passata di innesco sulle ore precedenti ricostruisce quali app erano
 *   già in primo piano all'inizio dell'intervallo;
 * - una PAUSED come primo evento di un pacchetto (sfuggito all'innesco)
 *   conta comunque dall'inizio dell'intervallo;
 * - una sessione ancora aperta alla fine conta fino alla fine dell'intervallo.
 */
class UsageStatsReader(context: Context) {

    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    /** Uso di [giorno], dalla sua mezzanotte fino ad [adesso] o alla mezzanotte successiva. */
    fun usoDelGiorno(
        giorno: LocalDate = LocalDate.now(),
        zona: ZoneId = ZoneId.systemDefault(),
        adesso: Long = System.currentTimeMillis(),
    ): List<UsoApp> {
        val inizio = giorno.atStartOfDay(zona).toInstant().toEpochMilli()
        val mezzanotteDopo = giorno.plusDays(1).atStartOfDay(zona).toInstant().toEpochMilli()
        val fine = minOf(adesso, mezzanotteDopo)
        if (fine <= inizio) return emptyList()
        return usoNellIntervallo(inizio, fine)
    }

    fun usoNellIntervallo(inizio: Long, fine: Long): List<UsoApp> {
        val stati = HashMap<String, StatoPacchetto>()
        for ((pacchetto, activity) in attiveAlMomento(inizio)) {
            stati[pacchetto] = StatoPacchetto().apply {
                activityAttive.addAll(activity)
                inPrimoPianoDa = inizio
                primoEventoVisto = true
            }
        }

        val eventi: UsageEvents? = usageStatsManager.queryEvents(inizio, fine)
        val evento = UsageEvents.Event()
        while (eventi != null && eventi.hasNextEvent()) {
            eventi.getNextEvent(evento)
            val pacchetto = evento.packageName ?: continue
            val classe = evento.className ?: pacchetto
            val ts = evento.timeStamp.coerceIn(inizio, fine)
            val stato = stati.getOrPut(pacchetto) { StatoPacchetto() }
            when (evento.eventType) {
                // Su API 26-28 il sistema emette MOVE_TO_FOREGROUND/MOVE_TO_BACKGROUND,
                // che hanno gli stessi valori numerici di RESUMED/PAUSED (1 e 2).
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (stato.activityAttive.isEmpty()) stato.inPrimoPianoDa = ts
                    stato.activityAttive.add(classe)
                    stato.primoEventoVisto = true
                }
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED,
                -> {
                    if (stato.activityAttive.isNotEmpty()) {
                        stato.activityAttive.remove(classe)
                        if (stato.activityAttive.isEmpty()) {
                            stato.accumulato += ts - stato.inPrimoPianoDa
                        }
                    } else if (!stato.primoEventoVisto) {
                        // Prima traccia del pacchetto = una chiusura: la sessione
                        // era aperta prima di [inizio] (mezzanotte attraversata).
                        stato.accumulato += ts - inizio
                    }
                    stato.primoEventoVisto = true
                }
            }
        }

        return stati.mapNotNull { (pacchetto, stato) ->
            var totale = stato.accumulato
            if (stato.activityAttive.isNotEmpty()) {
                // Sessione ancora aperta al momento della lettura.
                totale += fine - stato.inPrimoPianoDa
            }
            if (totale > 0) UsoApp(pacchetto, totale) else null
        }.sortedByDescending { it.millisPrimoPiano }
    }

    /**
     * Ricostruisce quali activity erano in primo piano a [istante], rigiocando
     * gli eventi delle ore precedenti. Sessioni iniziate prima della finestra
     * di innesco sfuggono qui, ma le raccoglie il fallback sulla prima PAUSED.
     */
    private fun attiveAlMomento(istante: Long): Map<String, Set<String>> {
        val eventi: UsageEvents? = usageStatsManager.queryEvents(istante - INNESCO_MS, istante - 1)
        val attive = HashMap<String, MutableSet<String>>()
        val evento = UsageEvents.Event()
        while (eventi != null && eventi.hasNextEvent()) {
            eventi.getNextEvent(evento)
            val pacchetto = evento.packageName ?: continue
            val classe = evento.className ?: pacchetto
            when (evento.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED ->
                    attive.getOrPut(pacchetto) { mutableSetOf() }.add(classe)
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED,
                -> attive[pacchetto]?.remove(classe)
            }
        }
        return attive.filterValues { it.isNotEmpty() }
    }

    private class StatoPacchetto {
        val activityAttive = mutableSetOf<String>()
        var inPrimoPianoDa = 0L
        var accumulato = 0L
        var primoEventoVisto = false
    }

    private companion object {
        const val INNESCO_MS = 12 * 60 * 60 * 1000L
    }
}
