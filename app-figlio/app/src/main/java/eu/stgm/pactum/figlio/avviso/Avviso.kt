package eu.stgm.pactum.figlio.avviso

import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.TipiRegola
import eu.stgm.pactum.figlio.valutatore.Sforamento
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * (0.9) Quello che l'avviso a tutto schermo racconta di uno sforamento appena
 * segnalato: il nome già tradotto dal telefono, il resto in numeri (le parole
 * le mette la schermata). Viaggia come JSON nell'intent di AvvisoActivity.
 */
@Serializable
data class Avviso(
    @SerialName("regola_id") val regolaId: Long,
    val tipo: String,
    /** Limite di tempo: l'app, la categoria o "Tutto il telefono". */
    val nome: String? = null,
    /** Limite di tempo: i minuti di oggi. */
    @SerialName("minuti_usati") val minutiUsati: Int? = null,
    /** Limite di tempo: il limite di oggi, bonus compresi. */
    @SerialName("limite_efficace") val limiteEfficace: Int? = null,
    /** Limite di tempo: il limite che si è dato (`minuti_al_giorno`), senza bonus. */
    val limite: Int? = null,
    /** Limite di tempo: i minuti oltre. Fascia: i minuti di telefono dentro la fascia. */
    @SerialName("minuti_oltre") val minutiOltre: Int = 0,
    val dalle: String? = null,
    val alle: String? = null,
) {
    val fascia: Boolean get() = tipo == TipiRegola.FASCIA_ORARIA

    /** I minuti di bonus di oggi su questa regola. */
    val bonus: Int
        get() = if (limiteEfficace != null && limite != null) (limiteEfficace - limite).coerceAtLeast(0) else 0

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val lista = ListSerializer(serializer())

        /** L'avviso di uno sforamento. [nome] = il bersaglio di un limite di tempo, in chiaro. */
        fun da(sforamento: Sforamento, regola: Regola?, nome: String?): Avviso {
            fun parametro(chiave: String) = (regola?.parametri?.get(chiave) as? JsonPrimitive)?.content
            if (sforamento.tipo == TipiRegola.FASCIA_ORARIA) {
                return Avviso(
                    regolaId = sforamento.regolaId,
                    tipo = sforamento.tipo,
                    minutiOltre = sforamento.minutiOltre,
                    dalle = parametro("dalle"),
                    alle = parametro("alle"),
                )
            }
            return Avviso(
                regolaId = sforamento.regolaId,
                tipo = sforamento.tipo,
                nome = nome,
                // Il valutatore dà i minuti OLTRE il limite efficace: l'uso è la somma.
                minutiUsati = sforamento.limiteEfficace?.plus(sforamento.minutiOltre),
                limiteEfficace = sforamento.limiteEfficace,
                limite = parametro("minuti_al_giorno")?.toIntOrNull(),
                minutiOltre = sforamento.minutiOltre,
            )
        }

        /**
         * (0.9) Un avviso nuovo mentre l'altro è ancora aperto: si aggiunge,
         * non lo cancella. Una regola compare una volta sola (tiene il posto e
         * prende i numeri nuovi).
         */
        fun unisci(aperti: List<Avviso>, nuovi: List<Avviso>): List<Avviso> {
            val perRegola = LinkedHashMap<Long, Avviso>()
            aperti.forEach { perRegola[it.regolaId] = it }
            nuovi.forEach { perRegola[it.regolaId] = it }
            return perRegola.values.toList()
        }

        fun inJson(avvisi: List<Avviso>): String = json.encodeToString(lista, avvisi)

        /** Assente o illeggibile = nessun avviso: la schermata si chiude da sola. */
        fun daJson(testo: String?): List<Avviso> =
            testo?.let { runCatching { json.decodeFromString(lista, it) }.getOrNull() }.orEmpty()
    }
}
