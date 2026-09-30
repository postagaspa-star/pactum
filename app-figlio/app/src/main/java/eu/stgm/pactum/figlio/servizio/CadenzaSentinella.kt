package eu.stgm.pactum.figlio.servizio

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
}
