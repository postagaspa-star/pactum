package eu.stgm.pactum.figlio.dati

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * (0.16, contratto v3.8) I tempi che il server manda in GET /api/patto, per
 * questo telefono e per ciascun dispositivo del figlio: `uso_recente` (gli 8
 * giorni, dal più vecchio a oggi) e `medie` (ultimi 7 e 30 giorni, con
 * `totale`). Identici a quelli che il genitore vede nella sua finestra.
 *
 * Tenuti grezzi nel patto e letti qui, con prudenza, come le sessioni: un
 * campo scritto male non deve far cadere la lettura di tutto il patto, e un
 * pezzo che non si legge si lascia cadere da solo. Mai uno zero finto: un
 * giorno senza fotografia resta "senza dati" (`totaleMinuti` null).
 */

/** Un'app (o un programma del computer) di un giorno: `nome` = quello della fotografia, se c'è. */
data class UsoAppServer(val chiave: String, val nome: String?, val minuti: Int)

/** Una categoria di un giorno, col limite base se una regola attiva la riguarda. */
data class UsoCategoriaServer(val chiave: String, val minuti: Int, val limite: Int?)

/** Un giorno di `uso_recente`. [totaleMinuti] null = nessuna fotografia: non è zero. */
data class UsoGiornoServer(
    val giorno: String,
    val totaleMinuti: Int?,
    val app: List<UsoAppServer> = emptyList(),
    val categorie: List<UsoCategoriaServer> = emptyList(),
    val sessioniMinuti: Int? = null,
)

/**
 * Un periodo di `medie`: [minuti] = la media già arrotondata dal server (si
 * mostra così com'è), [giorni] = i giorni con dati, [totale] = la somma (v3.8;
 * null = server di prima, e allora la riga dei totali non c'è).
 */
data class PeriodoServer(val minuti: Int, val giorni: Int, val totale: Int?)

/** `medie`: ogni periodo null se nella finestra non c'è nessuna fotografia. */
data class MedieServer(val settimana: PeriodoServer?, val mese: PeriodoServer?)

/**
 * (0.16, contratto v3.8) Il patto porta i tempi: letto con `?tempi=1`. Una
 * lettura senza (sentinella, worker, le altre schermate) non li ha.
 */
val Patto.conTempi: Boolean
    get() = usoRecenteGrezzo != null || medieGrezze != null

/**
 * La copia da salvare: questo patto, coi tempi di [vecchia] se lui non li ha.
 * I tempi arrivano solo con `?tempi=1`; una rilettura senza (la sentinella, il
 * worker) non deve cancellare quelli già salvati, né quelli di ogni
 * dispositivo. Solo dalla copia dello stesso collegamento e dello stesso
 * telefono: i tempi di un altro dispositivo non passano. Tempi vecchi di
 * giorni restano storia vera: chi li mostra li mette al loro giorno.
 */
fun Patto.conTempiDi(vecchia: Patto?): Patto {
    if (conTempi || vecchia == null) return this
    val vecchiaConTempi = vecchia.conTempi || vecchia.dispositivi.any { it.usoRecenteGrezzo != null || it.medieGrezze != null }
    if (!vecchiaConTempi) return this
    if (vecchia.lettoCon != lettoCon || vecchia.dispositivo?.id != dispositivo?.id) return this
    val tempiAltri = vecchia.dispositivi.associateBy { it.id }
    return copy(
        usoRecenteGrezzo = vecchia.usoRecenteGrezzo,
        medieGrezze = vecchia.medieGrezze,
        dispositivi = dispositivi.map { d ->
            val prima = tempiAltri[d.id]
            if (d.usoRecenteGrezzo != null || d.medieGrezze != null || prima == null) {
                d
            } else {
                d.copy(usoRecenteGrezzo = prima.usoRecenteGrezzo, medieGrezze = prima.medieGrezze)
            }
        },
    )
}

object LetturaTempi {

    /** I minuti di un giorno (contratto: da 0 a 1440). */
    const val MINUTI_GIORNO = 1440

    /**
     * Gli 8 giorni di `uso_recente`, dal più vecchio, un giorno una volta sola.
     * null = il campo non c'è (server di prima della v3.8) o non è una lista.
     */
    fun usoRecente(grezzo: JsonElement?): List<UsoGiornoServer>? {
        val lista = grezzo as? JsonArray ?: return null
        return lista.mapNotNull(::giorno).distinctBy { it.giorno }.sortedBy { it.giorno }
    }

    /** `medie`; null = il campo non c'è o non è un oggetto. */
    fun medie(grezzo: JsonElement?): MedieServer? {
        val oggetto = grezzo as? JsonObject ?: return null
        return MedieServer(settimana = periodo(oggetto["settimana"]), mese = periodo(oggetto["mese"]))
    }

    private fun giorno(elemento: JsonElement): UsoGiornoServer? {
        val oggetto = elemento as? JsonObject ?: return null
        val giorno = testo(oggetto["giorno"])?.takeIf(::dataValida) ?: return null
        // Un totale che non si legge (o fuori da un giorno) vale "senza dati":
        // e allora nemmeno le liste, che senza fotografia non hanno senso.
        val totale = minutiDelGiorno(oggetto["totale_minuti"]) ?: return UsoGiornoServer(giorno, null)
        return UsoGiornoServer(
            giorno = giorno,
            totaleMinuti = totale,
            app = (oggetto["app"] as? JsonArray).orEmpty().mapNotNull(::app),
            categorie = (oggetto["categorie"] as? JsonArray).orEmpty().mapNotNull(::categoria),
            sessioniMinuti = minutiDelGiorno(oggetto["sessioni_minuti"]),
        )
    }

    private fun app(elemento: JsonElement): UsoAppServer? {
        val oggetto = elemento as? JsonObject ?: return null
        val chiave = testo(oggetto["chiave"])?.takeIf { it.isNotBlank() } ?: return null
        val minuti = minutiDelGiorno(oggetto["minuti"]) ?: return null
        return UsoAppServer(chiave, testo(oggetto["nome"])?.trim()?.takeIf { it.isNotEmpty() }, minuti)
    }

    private fun categoria(elemento: JsonElement): UsoCategoriaServer? {
        val oggetto = elemento as? JsonObject ?: return null
        val chiave = testo(oggetto["chiave"])?.takeIf { it.isNotBlank() } ?: return null
        val minuti = minutiDelGiorno(oggetto["minuti"]) ?: return null
        val limite = intero(oggetto["limite"])?.takeIf { it in 0..MINUTI_GIORNO }
        return UsoCategoriaServer(chiave, minuti, limite)
    }

    private fun periodo(elemento: JsonElement?): PeriodoServer? {
        val oggetto = elemento as? JsonObject ?: return null
        val minuti = minutiDelGiorno(oggetto["minuti"]) ?: return null
        // Un periodo esiste solo con almeno un giorno di dati (contratto).
        val giorni = intero(oggetto["giorni"])?.takeIf { it >= 1 } ?: return null
        val totale = intero(oggetto["totale"])?.takeIf { it >= 0 }
        return PeriodoServer(minuti, giorni, totale)
    }

    private fun minutiDelGiorno(elemento: JsonElement?): Int? = intero(elemento)?.takeIf { it in 0..MINUTI_GIORNO }

    /** Un intero vero: niente testo ("12"), niente vero/falso, niente decimali. */
    private fun intero(elemento: JsonElement?): Int? {
        val primitivo = elemento as? JsonPrimitive ?: return null
        if (primitivo.isString) return null
        val valore = primitivo.longOrNull ?: return null
        return valore.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    }

    private fun testo(elemento: JsonElement?): String? =
        (elemento as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun dataValida(giorno: String): Boolean = try {
        LocalDate.parse(giorno)
        true
    } catch (e: DateTimeParseException) {
        false
    }
}
