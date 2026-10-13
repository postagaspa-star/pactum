package eu.stgm.pactum.figlio.bilancio

import eu.stgm.pactum.figlio.catalogo.CatalogoApp
import eu.stgm.pactum.figlio.dati.TipiRegola
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.LocalDate
import java.time.LocalTime

/**
 * (0.24, contratto v4.2) Le promesse che il telefono può controllare da solo: diventano
 * una regola di un giorno, per domani. Due forme, le stesse delle regole di sempre:
 * "al massimo N minuti di …" (tutto il telefono o una categoria) e "niente telefono
 * dalle … alle …" (senza passare la mezzanotte: il server non la accetta per un giorno
 * solo). Senza Android: provata in LogicaBilancioTest.
 */
object PromesseMisurabili {

    /** I bersagli che si offrono: tutto il telefono e le categorie più usate. */
    val BERSAGLI = listOf(
        CatalogoApp.CHIAVE_TOTALE,
        CatalogoApp.CAT_SOCIAL,
        CatalogoApp.CAT_GIOCHI,
        CatalogoApp.CAT_VIDEO,
    )

    /** I minuti tra cui scegliere. */
    val MINUTI = listOf(15, 30, 60, 90, 120)

    sealed interface Misura {
        data class Limite(val chiave: String, val minuti: Int) : Misura
        data class Fascia(val dalle: LocalTime, val alle: LocalTime) : Misura
    }

    private val SETTIMANA = listOf("lun", "mar", "mer", "gio", "ven", "sab", "dom")

    fun tipo(misura: Misura): String = when (misura) {
        is Misura.Limite -> TipiRegola.LIMITE_TEMPO
        is Misura.Fascia -> TipiRegola.FASCIA_ORARIA
    }

    /** Valida: minuti tra 1 e un giorno, una fascia che comincia prima di finire (stesso giorno). */
    fun valida(misura: Misura): Boolean = when (misura) {
        is Misura.Limite -> misura.chiave.isNotBlank() && misura.minuti in 1..1440
        is Misura.Fascia -> misura.dalle.isBefore(misura.alle)
    }

    /** I parametri della regola, come li vuole POST /api/regole; una fascia vale il giorno della settimana di [giorno]. */
    fun parametri(misura: Misura, giorno: LocalDate): JsonObject = when (misura) {
        is Misura.Limite -> buildJsonObject {
            put("app_o_categoria", misura.chiave)
            put("minuti_al_giorno", misura.minuti)
        }
        is Misura.Fascia -> buildJsonObject {
            put("dalle", LogicaBilancio.testoOra(misura.dalle))
            put("alle", LogicaBilancio.testoOra(misura.alle))
            putJsonArray("giorni") { add(JsonPrimitive(SETTIMANA[giorno.dayOfWeek.value - 1])) }
        }
    }
}
