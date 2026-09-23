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
    suspend fun sostituisciUsoGiornaliero(evento: Evento) =
        sostituisciFotografia(TipiEvento.USO_GIORNALIERO, evento)

    /**
     * (v2.3) Stessa cosa per la fotografia dei siti del giorno: cumulativa e
     * idempotente lato server, quindi in coda ne basta l'ultima per giorno.
     */
    suspend fun sostituisciSitiGiornalieri(evento: Evento) =
        sostituisciFotografia(TipiEvento.SITI_GIORNALIERI, evento)

    private suspend fun sostituisciFotografia(tipo: String, evento: Evento) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val giorno = evento.dettagli["giorno"]
                val altri = if (giorno == null) {
                    leggi()
                } else {
                    leggi().filterNot { it.tipo == tipo && it.dettagli["giorno"] == giorno }
                }
                scrivi((altri + evento).takeLast(MAX_EVENTI))
            }
        }

    suspend fun inAttesa(): List<Evento> = withContext(Dispatchers.IO) {
        mutex.withLock { leggi() }
    }

    /**
     * (v3) Toglie dalla coda gli eventi di un [tipo]. Serve quando il telefono
     * passa a un ALTRO dispositivo del patto: uno sforamento non ancora
     * consegnato porta il `regola_id` di una regola del dispositivo di prima, e
     * mandato col nuovo token finirebbe su una regola che non è sua.
     */
    suspend fun scartaTipo(tipo: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val eventi = leggi()
            val restano = eventi.filterNot { it.tipo == tipo }
            if (restano.size != eventi.size) scrivi(restano)
        }
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
