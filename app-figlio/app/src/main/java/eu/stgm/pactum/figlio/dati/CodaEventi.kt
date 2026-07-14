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

    /**
     * Accoda una fotografia uso_giornaliero SOSTITUENDO quella eventualmente
     * già in coda per lo stesso giorno (dedup stabile): il server tiene
     * comunque l'ultima per giorno, ma senza sostituzione la coda si
     * riempirebbe di fotografie quasi identiche a ogni battito.
     */
    suspend fun sostituisciUsoGiornaliero(evento: Evento) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val giorno = evento.dettagli["giorno"]
            val altri = if (giorno == null) {
                leggi()
            } else {
                leggi().filterNot {
                    it.tipo == TipiEvento.USO_GIORNALIERO && it.dettagli["giorno"] == giorno
                }
            }
            scrivi((altri + evento).takeLast(MAX_EVENTI))
        }
    }

    suspend fun inAttesa(): List<Evento> = withContext(Dispatchers.IO) {
        mutex.withLock { leggi() }
    }

    /**
     * Rimuove per id gli eventi appena accettati dal server. Per id e non per
     * posizione: tra lettura e rimozione una sostituzione per giorno può aver
     * cambiato la coda, e "togli i primi N" toglierebbe eventi mai consegnati.
     */
    suspend fun rimuoviConsegnati(consegnati: List<Evento>) {
        if (consegnati.isEmpty()) return
        val ids = consegnati.mapTo(HashSet()) { it.id }
        withContext(Dispatchers.IO) {
            mutex.withLock { scrivi(leggi().filterNot { it.id in ids }) }
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
