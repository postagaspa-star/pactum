package eu.stgm.pactum.figlio.dati

import android.content.Context
import eu.stgm.pactum.figlio.faccende.ControlloBlocco
import eu.stgm.pactum.figlio.sessione.ArchivioSessioni
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

    private val app = context.applicationContext
    private val file = File(app.filesDir, "patto_locale.json")

    /**
     * Salva la copia appena letta dal server e, dal patto v3, chi è questo
     * telefono (Impostazioni.identita). Una copia letta col collegamento di
     * prima (una lettura in viaggio mentre il telefono veniva ricollegato) non
     * entra: sarebbe il patto di un altro dispositivo, e la sentinella ne
     * valuterebbe le regole. Controllo e scrittura stanno sotto lo stesso
     * mutex di [cambiaCollegamento], così non si possono incrociare. False se
     * la copia non è entrata.
     */
    suspend fun salva(patto: Patto): Boolean = withContext(Dispatchers.IO) {
        val impostazioni = Impostazioni(app)
        // Stampa il giorno del patto a cui i bonus_oggi_per_regola si riferiscono:
        // serve al valutatore per non applicare i bonus di ieri al limite di oggi
        // dopo una notte offline (i bonus sono del giorno, contratto-api.md).
        val giorno = LocalDate.now(zonaPatto(patto.fuso)).toString()
        val daScrivere = patto.copy(bonusGiornoLocale = giorno)
        mutex.withLock {
            val lettoCon = patto.lettoCon
            if (lettoCon != null && lettoCon != impostazioni.leggiConfigurazione().impronta) {
                return@withLock false
            }
            scrivi(daScrivere)
            impostazioni.aggiornaIdentita(patto.dispositivo, patto.figlio)
            // (0.11) Ogni patto fresco porta le sessioni svolte e quella in
            // corso: così una sessione torna anche dopo una reinstallazione, e
            // i periodi che non contano restano noti. Sotto lo stesso mutex del
            // cambio di collegamento: le sessioni di un altro dispositivo non
            // rientrano da una lettura vecchia. Mai a spese della copia del patto.
            runCatching { ArchivioSessioni.daServer(app, patto) }
            // (0.13) E il blocco delle faccende con il loro elenco: un patto
            // fresco vale come una risposta del server sul blocco.
            runCatching { ControlloBlocco.daPatto(app, patto) }
            true
        }
    }

    /**
     * (0.9) Una modifica fatta sul telefono alla copia che c'è (il bonus appena
     * concesso, prima della rilettura dal server), sotto lo stesso mutex. Senza
     * copia non c'è niente da modificare.
     */
    suspend fun modifica(trasforma: (Patto) -> Patto) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!file.exists()) return@withLock
            val attuale = runCatching { json.decodeFromString(Patto.serializer(), file.readText()) }.getOrNull()
                ?: return@withLock
            scrivi(trasforma(attuale))
        }
    }

    private fun scrivi(patto: Patto) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(json.encodeToString(Patto.serializer(), patto))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    suspend fun leggi(): Patto? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!file.exists()) return@withLock null
            runCatching { json.decodeFromString(Patto.serializer(), file.readText()) }.getOrNull()
        }
    }

    /** Quando è arrivata l'ultima copia dal server (epoch ms), null = mai: l'età dei dati. */
    suspend fun aggiornatoIl(): Long? = withContext(Dispatchers.IO) {
        mutex.withLock { file.takeIf { it.exists() }?.lastModified()?.takeIf { it > 0 } }
    }

    /**
     * Il cambio di collegamento ([cambia] scrive indirizzo, token e identità)
     * sotto lo stesso mutex di [salva]. Con [cancellaCopia] la copia del patto
     * vecchio se ne va: le sue regole sono di un altro dispositivo, e la
     * sentinella non deve valutarle nemmeno per un giro.
     */
    suspend fun cambiaCollegamento(cancellaCopia: Boolean, cambia: suspend () -> Unit) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                cambia()
                if (cancellaCopia) file.delete()
            }
        }

    private companion object {
        val mutex = Mutex()
        val json = Json { ignoreUnknownKeys = true }
    }
}
