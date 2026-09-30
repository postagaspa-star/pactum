package eu.stgm.pactum.figlio.misura

import android.content.Context
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/**
 * (0.9) Il totale di un giorno non torna mai indietro: la stessa regola della
 * fotografia vigente del server (monotona su `totale_minuti`). Senza, basta
 * disinstallare un'app già usata oggi perché i suoi minuti spariscano dal
 * conto e "Tutto il telefono" scenda. Logica pura; la memoria su disco sta in
 * MemoriaTotale.
 */
object TotaleDelGiorno {

    /** Si ricordano i giorni ancora in gioco: oggi e ieri (la fotografia di ieri), più un margine. */
    const val GIORNI_MEMORIA = 3L

    data class Esito(val totale: Long, val memoria: Map<String, Long>)

    /**
     * Il totale di [giorno]: il più alto tra quello [calcolato] adesso e quello
     * già visto. La memoria tiene solo gli ultimi giorni rispetto a [oggi].
     */
    fun aggiorna(memoria: Map<String, Long>, giorno: String, calcolato: Long, oggi: LocalDate): Esito {
        val totale = maxOf(calcolato, memoria[giorno] ?: 0L)
        val soglia = oggi.minusDays(GIORNI_MEMORIA)
        val nuova = (memoria + (giorno to totale)).filterKeys { chiave ->
            runCatching { LocalDate.parse(chiave) }.getOrNull()?.isAfter(soglia) == true
        }
        return Esito(totale, nuova)
    }
}

/**
 * Il totale più alto visto per giorno, su disco (un file piccolo in filesDir):
 * sopravvive alla morte del processo. Si scrive solo quando cambia.
 */
object MemoriaTotale {

    private const val NOME_FILE = "totale_giorni.json"
    private val serializzatore = MapSerializer(String.serializer(), Long.serializer())
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private var memoria: Map<String, Long>? = null

    /** Il totale di [giorno] da usare: mai meno del più alto già visto oggi. */
    fun almeno(context: Context, giorno: LocalDate, calcolato: Long): Long = synchronized(lock) {
        val file = File(context.applicationContext.filesDir, NOME_FILE)
        val attuale = memoria ?: leggi(file)
        val esito = TotaleDelGiorno.aggiorna(attuale, giorno.toString(), calcolato, LocalDate.now())
        if (esito.memoria != attuale) scrivi(file, esito.memoria)
        memoria = esito.memoria
        esito.totale
    }

    private fun leggi(file: File): Map<String, Long> =
        if (!file.exists()) {
            emptyMap()
        } else {
            runCatching { json.decodeFromString(serializzatore, file.readText()) }.getOrDefault(emptyMap())
        }

    private fun scrivi(file: File, memoria: Map<String, Long>) {
        runCatching {
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(json.encodeToString(serializzatore, memoria))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }
}
