package eu.stgm.pactum.figlio.bilancio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/**
 * (0.24) Il diario del bilancio della sera, in `bilancio.json` dentro la cartella
 * privata dell'app: non va al server, i genitori non lo vedono. Un file solo, letto
 * e scritto intero sotto un lucchetto (è piccolo: due mesi di sere). Scritto prima
 * in un file accanto e poi rinominato: un telefono che si spegne a metà non lo rompe.
 *
 * [cambi] sale a ogni scrittura: la schermata e il giro dei promemoria nel servizio
 * lo guardano per rileggere (una promessa nuova, una risposta dalla notifica).
 */
object ArchivioBilancio {

    private const val FILE = "bilancio.json"
    private val lucchetto = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _cambi = MutableStateFlow(0)
    val cambi: StateFlow<Int> = _cambi.asStateFlow()

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE)

    suspend fun leggi(context: Context): Diario = withContext(Dispatchers.IO) {
        lucchetto.withLock { leggiDentro(context) }
    }

    /** Legge, cambia con [cambio] e riscrive, tutto sotto il lucchetto. Restituisce il diario nuovo. */
    suspend fun cambia(context: Context, cambio: (Diario) -> Diario): Diario = withContext(Dispatchers.IO) {
        val nuovo = lucchetto.withLock {
            val dopo = LogicaBilancio.pulisci(cambio(leggiDentro(context)), LocalDate.now())
            scrivi(context, dopo)
            dopo
        }
        _cambi.value = _cambi.value + 1
        nuovo
    }

    private fun leggiDentro(context: Context): Diario {
        val f = file(context)
        if (!f.exists()) return Diario()
        // Un file rovinato non deve far cadere l'app: si riparte vuoti.
        return runCatching { json.decodeFromString(Diario.serializer(), f.readText()) }.getOrDefault(Diario())
    }

    private fun scrivi(context: Context, diario: Diario) {
        val f = file(context)
        val temporaneo = File(f.parentFile, "$FILE.nuovo")
        temporaneo.writeText(json.encodeToString(Diario.serializer(), diario))
        if (!temporaneo.renameTo(f)) {
            f.delete()
            temporaneo.renameTo(f)
        }
    }
}
