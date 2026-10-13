package eu.stgm.pactum.figlio.bilancio

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// (0.24) Il bilancio della sera (richiesta di Andrea): ogni sera, all'ora scelta,
// "com'è andata oggi?". Se non è andata bene: cosa non ha funzionato e cosa farai,
// quando. La promessa torna all'ora detta ("Fatto?"); una promessa che il telefono
// può controllare da solo diventa una regola di un giorno (contratto v4.2).
//
// Tutto resta su QUESTO telefono (bilancio.json): i genitori non leggono né il
// bilancio né le promesse. Vedono solo la regola di un giorno, se ne nasce una,
// come ogni altra regola. Niente blocchi: una promessa mancata torna nel bilancio
// dopo, e tre di fila fanno chiedere di cambiarla.

/** Com'è andata la giornata: tre risposte, la prima basta un tocco. */
object Voti {
    const val BENE = "bene"
    const val COSI_COSI = "cosi_cosi"
    const val MALE = "male"
    val TUTTI = listOf(BENE, COSI_COSI, MALE)
}

/** Com'è andata una promessa. */
object EsitiPromessa {
    const val FATTA = "fatta"
    const val NON_FATTA = "non_fatta"

    /** Solo per le misurabili: il giorno è passato e il telefono non ha dati. */
    const val SENZA_DATI = "senza_dati"
}

/**
 * Una promessa: cosa, il giorno e (se c'è) l'ora. [regolaId] = la regola di un
 * giorno che il telefono controlla da solo: allora l'esito viene dal suo semaforo
 * e non si chiede. [esito] null = ancora da dire.
 */
@Serializable
data class Promessa(
    val id: String,
    val testo: String,
    /** Il giorno in cui farla, YYYY-MM-DD. */
    val giorno: String,
    /** "HH:mm", null = nessun orario: si chiede nel bilancio di quella sera. */
    val ora: String? = null,
    /** Il giorno del bilancio che l'ha scritta. */
    @SerialName("nata_il") val nataIl: String,
    @SerialName("regola_id") val regolaId: Long? = null,
    val esito: String? = null,
    /** Quante volte è stata rimandata ("Rimando"). */
    val rimandi: Int = 0,
    /** Il promemoria all'ora è già partito (non riparte a ogni giro). */
    @SerialName("avviso_dato") val avvisoDato: Boolean = false,
)

/** Il bilancio di una sera. Uno per giorno: il secondo sostituisce il primo. */
@Serializable
data class Bilancio(
    val giorno: String,
    val voto: String,
    @SerialName("cosa_non_ha_funzionato") val cosaNonHaFunzionato: String? = null,
    @SerialName("promessa_id") val promessaId: String? = null,
    /** Epoch ms: quando l'ha scritto. */
    @SerialName("scritto_il") val scrittoIl: Long = 0,
)

/** Tutto quello che il telefono ricorda: bilanci e promesse degli ultimi giorni. */
@Serializable
data class Diario(
    val bilanci: List<Bilancio> = emptyList(),
    val promesse: List<Promessa> = emptyList(),
)

/**
 * (v4.2) Una regola di un giorno com'è nel patto (`regole_un_giorno`), anche
 * spenta: il suo giorno e il suo semaforo, da cui si legge com'è andata la promessa.
 */
data class RegolaDiUnGiorno(val id: Long, val giorno: String, val semaforo: Map<String, String>)

/** (v4.2) Le regole di un giorno del patto, nella forma che usa la logica del bilancio. */
fun eu.stgm.pactum.figlio.dati.Patto.regoleDiUnGiorno(): List<RegolaDiUnGiorno> =
    regoleUnGiorno.orEmpty().map { voce ->
        RegolaDiUnGiorno(voce.id, voce.giorno, voce.semaforo.associate { it.data to it.stato })
    }
