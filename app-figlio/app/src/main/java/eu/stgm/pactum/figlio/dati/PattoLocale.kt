package eu.stgm.pactum.figlio.dati

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/**
 * Copia locale del patto per la valutazione OFFLINE degli sforamenti. Il server
 * resta la fonte di verità (lock, bonus, storia); qui si tiene solo l'ultima
 * fotografia delle regole e dei bonus di oggi, risincronizzata a ogni giro del
 * worker (GET /api/patto). Se il worker gira senza rete, il valutatore usa
 * questa copia: una serata offline deve comunque finire nel registro.
 *
 * Un file JSON in filesDir: niente database per una struttura sola.
 */
class PattoLocale(context: Context) {

    private val file = File(context.filesDir, "patto_locale.json")

    suspend fun salva(patto: Patto) = withContext(Dispatchers.IO) {
        // Stampa il giorno del patto a cui i bonus_oggi_per_regola si riferiscono:
        // serve al valutatore per non applicare i bonus di ieri al limite di oggi
        // dopo una notte offline (i bonus sono del giorno, contratto-api.md).
        val giorno = LocalDate.now(zonaPatto(patto.fuso)).toString()
        val daScrivere = patto.copy(bonusGiornoLocale = giorno)
        mutex.withLock {
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(json.encodeToString(Patto.serializer(), daScrivere))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }

    suspend fun leggi(): Patto? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!file.exists()) return@withLock null
            runCatching { json.decodeFromString(Patto.serializer(), file.readText()) }.getOrNull()
        }
    }

    private companion object {
        val mutex = Mutex()
        val json = Json { ignoreUnknownKeys = true }
    }
}
