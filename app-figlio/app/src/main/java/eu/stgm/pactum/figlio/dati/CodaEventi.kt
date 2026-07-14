package eu.stgm.pactum.figlio.dati

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Coda persistente degli eventi in attesa di consegna al postino
 * (un JSON per riga in filesDir). Se l'invio fallisce, gli eventi
 * restano qui fino al battito successivo: tolleranza all'offline
 * senza database. Un solo processo, più chiamanti (worker, receiver):
 * il mutex condiviso nel companion basta.
 */
class CodaEventi(context: Context) {

    private val file = File(context.filesDir, "coda_eventi.jsonl")

    suspend fun accoda(evento: Evento) = withContext(Dispatchers.IO) {
        mutex.withLock {
            file.appendText(json.encodeToString(Evento.serializer(), evento) + "\n")
            val eventi = leggi()
            if (eventi.size > MAX_EVENTI) scrivi(eventi.takeLast(MAX_EVENTI))
        }
    }

    suspend fun inAttesa(): List<Evento> = withContext(Dispatchers.IO) {
        mutex.withLock { leggi() }
    }

    /** Rimuove i primi [quanti] eventi: quelli appena consegnati con successo. */
    suspend fun rimuoviPrimi(quanti: Int) {
        if (quanti <= 0) return
        withContext(Dispatchers.IO) {
            mutex.withLock { scrivi(leggi().drop(quanti)) }
        }
    }

    private fun leggi(): List<Evento> {
        if (!file.exists()) return emptyList()
        return file.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { riga ->
                runCatching { json.decodeFromString(Evento.serializer(), riga) }.getOrNull()
            }
    }

    private fun scrivi(eventi: List<Evento>) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(eventi.joinToString("") { json.encodeToString(Evento.serializer(), it) + "\n" })
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    private companion object {
        const val MAX_EVENTI = 2000
        val mutex = Mutex()
        val json = Json { ignoreUnknownKeys = true }
    }
}
