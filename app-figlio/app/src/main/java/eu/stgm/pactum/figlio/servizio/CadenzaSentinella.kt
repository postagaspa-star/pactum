package eu.stgm.pactum.figlio.servizio

import eu.stgm.pactum.figlio.valutatore.ProssimoGiro
import java.time.LocalDate
import java.time.ZoneId

/**
 * (0.9) Quando il loop veloce del servizio guarda l'uso (logica pura).
 *
 * Un giro al minuto: un limite superato si vede entro circa un minuto. A
 * schermo spento l'uso non cresce, quindi non si guarda; ma allo spegnimento
 * si guarda subito (il ricevitore di SCREEN_OFF sveglia il loop), e comunque
 * al primo giro dopo, se lo spegnimento non è arrivato: il minuto appena prima
 * conta ancora (chi supera il limite e spegne subito).
 *
 * Al primo giro di un giorno nuovo si guarda una volta anche il giorno prima,
 * fino al suo ultimo istante: chi supera il limite nell'ultimo minuto prima di
 * mezzanotte altrimenti non verrebbe mai visto (a mezzanotte l'uso riparte da zero).
 */
object CadenzaSentinella {

    const val INTERVALLO_MS = 60_000L

    /** Cosa guardare in questo giro: oggi, e il giorno prima. */
    data class Giro(val oggi: Boolean, val ieri: Boolean)

    /**
     * [accesoOra]/[accesoPrima]: lo schermo adesso e al giro prima;
     * [spentoAdesso]: lo schermo si è appena spento (era acceso fino a un
     * attimo fa); [ultimoGiorno]: il giorno dell'ultima volta che si è guardato.
     */
    fun giro(
        accesoOra: Boolean,
        accesoPrima: Boolean,
        spentoAdesso: Boolean,
        oggi: LocalDate,
        ultimoGiorno: LocalDate?,
    ): Giro {
        val guarda = accesoOra || accesoPrima || spentoAdesso
        return Giro(oggi = guarda, ieri = guarda && ultimoGiorno == oggi.minusDays(1))
    }

    /** L'ultimo istante del giorno prima di [oggi]: lì si guarda "ieri". */
    fun fineDiIeri(oggi: LocalDate, zona: ZoneId): Long = oggi.atStartOfDay(zona).toInstant().toEpochMilli() - 1

    /**
     * (0.12) Fra quanto il giro dopo. Di solito un minuto; ma se l'app davanti,
     * restando lì, porta una regola a una soglia del preavviso ("mancano 5
     * minuti", "manca 1 minuto") o (0.16, contratto v3.8) al suo limite ("il
     * tempo è finito") o oltre (lo sforamento) prima di allora, si guarda
     * appena dopo ([prossimaSoglia] ms, più un secondo di margine): l'avviso
     * arriva entro pochi secondi, non fino a un minuto dopo. Mai meno di un
     * secondo. Senza niente in vista (o a schermo spento) resta il minuto.
     */
    fun attesa(prossimaSoglia: Long?): Long =
        prossimaSoglia?.let { (it + MARGINE_SOGLIA_MS).coerceIn(ATTESA_MINIMA_MS, INTERVALLO_MS) } ?: INTERVALLO_MS

    /**
     * (0.16) Il giro completo dopo questo: [attesa] della soglia (il momento
     * esatto dell'app davanti, o il minuto). Vicino al limite, nel frattempo,
     * il controllo leggero (ControlloLeggero) guarda solo gli ultimi eventi.
     */
    fun attesa(prossimo: ProssimoGiro): Long = attesa(prossimo.soglia)

    /**
     * (0.16) Quanto aspettare ancora, sull'orologio che non si sposta: [attesa]
     * conta dal momento della lettura dell'uso ([riferimento]), non dalla fine
     * del giro. Il momento esatto del limite è calcolato sull'uso letto allora,
     * e il giro può durare (il lucchetto col worker, l'invio di uno
     * sforamento, le riprove di consegna): partire da dopo lo farebbe arrivare
     * in ritardo. Mai meno di [ATTESA_MINIMA_MS].
     */
    fun resta(riferimento: Long, attesa: Long, adesso: Long): Long =
        (riferimento + attesa - adesso).coerceAtLeast(ATTESA_MINIMA_MS)

    /** Si guarda un secondo dopo la soglia: l'uso letto è già oltre. */
    const val MARGINE_SOGLIA_MS = 1_000L
    const val ATTESA_MINIMA_MS = 1_000L

}
