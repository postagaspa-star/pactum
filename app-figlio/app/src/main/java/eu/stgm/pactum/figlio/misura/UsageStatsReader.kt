package eu.stgm.pactum.figlio.misura

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import eu.stgm.pactum.figlio.sessione.PeriodiSessione
import java.time.LocalDate
import java.time.ZoneId

data class UsoApp(val pacchetto: String, val millisPrimoPiano: Long)

/** (0.16) Un'app venuta davanti ([istante] = l'orario dell'evento ACTIVITY_RESUMED). */
data class Ripresa(val pacchetto: String, val istante: Long)

/**
 * Calcola il tempo in primo piano per app da queryEvents(), accoppiando
 * ACTIVITY_RESUMED/ACTIVITY_PAUSED per pacchetto. Come da architettura.md:
 * i bucket INTERVAL_DAILY divergono da Digital Wellbeing e non si usano.
 *
 * Le finestre di lettura (innesco sulle 12 ore prima, poi il giorno) e le
 * regole delle sessioni stanno in Sessioni, logica pura provata con JUnit:
 * qui ci sono solo le domande al sistema.
 */
class UsageStatsReader(context: Context) {

    private val app = context.applicationContext
    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    /**
     * (0.9) L'uso di [giorno] letto una volta sola, dalla sua mezzanotte fino ad
     * [adesso] o alla mezzanotte successiva: minuti per app, totale e fasce
     * escono tutti da qui.
     *
     * (0.11) Senza il tempo passato in una Sessione nelle sue app: da qui in
     * poi non conta per nessuno (TempoInSessione). Le app fuori dalla lista
     * contano come sempre.
     */
    fun leggiGiorno(
        giorno: LocalDate = LocalDate.now(),
        zona: ZoneId = ZoneId.systemDefault(),
        adesso: Long = System.currentTimeMillis(),
    ): LetturaGiorno {
        val tutto = Sessioni.giorno(giorno, zona, adesso, ::scorri)
        val periodi = PeriodiSessione.delGiorno(app, giorno, zona, tutto.fine)
        return TempoInSessione.togli(tutto, periodi.periodi, periodi.note)
    }

    /** Uso di [giorno] per app, dalla sua mezzanotte fino ad [adesso] o alla mezzanotte successiva. */
    fun usoDelGiorno(
        giorno: LocalDate = LocalDate.now(),
        zona: ZoneId = ZoneId.systemDefault(),
        adesso: Long = System.currentTimeMillis(),
    ): List<UsoApp> = leggiGiorno(giorno, zona, adesso).perApp

    /**
     * (0.16) Il controllo leggero (servizio.ControlloLeggero): le app che sono
     * venute davanti (ACTIVITY_RESUMED) fra [da] e [a), in ordine, col loro
     * orario. Una finestra di pochi secondi: niente lettura del giorno.
     */
    fun ripreseTra(da: Long, a: Long): List<Ripresa> {
        if (a <= da) return emptyList()
        val riprese = ArrayList<Ripresa>()
        scorri(da, a) { tipo, pacchetto, _, istante ->
            if (tipo == Sessioni.RIPRESA && pacchetto != null) riprese += Ripresa(pacchetto, istante)
        }
        return riprese
    }

    /** Gli eventi con istante in [da, a): la fine è esclusa, come dice queryEvents. */
    private fun scorri(da: Long, a: Long, azione: (Int, String?, String?, Long) -> Unit) {
        val eventi: UsageEvents? = usageStatsManager.queryEvents(da, a)
        val evento = UsageEvents.Event()
        while (eventi != null && eventi.hasNextEvent()) {
            eventi.getNextEvent(evento)
            azione(evento.eventType, evento.packageName, evento.className, evento.timeStamp)
        }
    }
}
