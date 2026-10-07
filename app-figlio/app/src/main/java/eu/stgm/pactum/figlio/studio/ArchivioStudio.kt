package eu.stgm.pactum.figlio.studio

import android.content.Context
import eu.stgm.pactum.figlio.faccende.ArchivioBlocco
import eu.stgm.pactum.figlio.faccende.Istante
import eu.stgm.pactum.figlio.faccende.MemoriaBlocco
import eu.stgm.pactum.figlio.faccende.Orologio
import eu.stgm.pactum.figlio.faccende.StatoBlocco
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File

/**
 * (0.18) L'ora del server adesso, come per il blocco dei lavori di casa:
 * agganciata all'orologio che non si sposta nella stessa accensione
 * dell'ultima risposta (MemoriaBlocco.oraServer), altrimenti l'orologio del
 * telefono più lo scarto misurato. [agganciata] = è quella vera.
 *
 * [perFine] = l'ora che decide se uno Studio già in corso è finito per la
 * mezzanotte. Di solito è [server]. Ma senza aggancio (riavvio senza rete) e
 * con l'orologio del telefono spostato a mano, [server] è solo l'orologio
 * del telefono: portato alle 23:59 chiuderebbe lo Studio a una mezzanotte
 * finta. Allora vale un'ora che è di sicuro già passata
 * (MemoriaBlocco.oraServerMinima): lo Studio resta finché torna la rete o
 * finché l'orologio che non si sposta conferma che è passata la mezzanotte.
 */
data class OraServer(val ora: Istante, val server: Long, val agganciata: Boolean, val perFine: Long = server) {
    companion object {
        fun di(blocco: MemoriaBlocco, ora: Istante): OraServer {
            val server = blocco.oraServer(ora)
            val perFine = if (blocco.orologioAMano(ora)) blocco.oraServerMinima(ora) ?: server else server
            return OraServer(ora, server, blocco.oraAgganciata(ora), perFine)
        }

        /** Adesso, con la memoria del blocco di questo processo. */
        fun adesso(): OraServer = di(StatoBlocco.memoria.value, Orologio.adesso())

        /** Adesso, leggendo la memoria del blocco dal disco se serve. */
        fun adesso(context: Context): OraServer = di(ArchivioBlocco.leggi(context), Orologio.adesso())
    }
}

/**
 * (0.18) Lo Studio adesso, per tutto il processo: lo guardano il servizio
 * (barriera, notifica fissa, sveglie), le schermate, il blocco dei lavori
 * (che durante lo Studio aspetta) e le sessioni (che durante lo Studio non
 * partono). Si aggiorna a ogni scrittura dell'archivio.
 */
object StatoStudio {

    private val _memoria = MutableStateFlow(MemoriaStudio())
    val memoria: StateFlow<MemoriaStudio> = _memoria.asStateFlow()

    /** "Guarda di nuovo": una sveglia, una risposta nuova. */
    private val _sveglia = Channel<Unit>(Channel.CONFLATED)
    val sveglia: ReceiveChannel<Unit> get() = _sveglia

    fun svegliati() {
        _sveglia.trySend(Unit)
    }

    /** Lo Studio in corso adesso, sull'ora del server; null se non c'è. */
    fun attivoAdesso(o: OraServer = OraServer.adesso()): StudioAttivo? = _memoria.value.attivo(o)

    fun inCorsoAdesso(o: OraServer = OraServer.adesso()): Boolean = attivoAdesso(o) != null

    internal fun aggiorna(memoria: MemoriaStudio) {
        if (memoria == _memoria.value) return
        _memoria.value = memoria
        _sveglia.trySend(Unit)
    }
}

/**
 * (0.18) Lo Studio su disco (un file JSON in filesDir, scrittura atomica come
 * ArchivioBlocco): sopravvive alla morte del processo e al riavvio, così lo
 * Studio parte e continua anche senza rete, e il tratto in corso si chiude
 * al suo ultimo punto salvato dopo un riavvio.
 */
object ArchivioStudio {

    private const val NOME_FILE = "studio.json"
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var memoria: MemoriaStudio? = null

    fun precarica(context: Context) {
        val app = context.applicationContext
        ambito.launch { runCatching { leggi(app) } }
    }

    fun leggi(context: Context): MemoriaStudio = memoria ?: synchronized(lock) {
        memoria ?: caricaDaDisco(context).also {
            memoria = it
            StatoStudio.aggiorna(it)
        }
    }

    /** Una modifica sotto il lock: [trasforma] (pura) dà la memoria nuova, su disco e nello stato del processo. */
    fun modifica(context: Context, trasforma: (MemoriaStudio) -> MemoriaStudio): MemoriaStudio = synchronized(lock) {
        val attuale = leggi(context)
        val nuova = trasforma(attuale)
        if (nuova != attuale) scrivi(context, nuova)
        memoria = nuova
        StatoStudio.aggiorna(nuova)
        nuova
    }

    /** Il telefono è passato a un altro dispositivo del patto: lo Studio di prima non è suo. */
    fun svuota(context: Context) {
        synchronized(lock) {
            file(context).delete()
            val vuota = MemoriaStudio()
            memoria = vuota
            StatoStudio.aggiorna(vuota)
        }
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, NOME_FILE)

    private fun caricaDaDisco(context: Context): MemoriaStudio {
        val f = file(context)
        if (!f.exists()) return MemoriaStudio()
        return runCatching { json.decodeFromString(MemoriaStudio.serializer(), f.readText()) }.getOrDefault(MemoriaStudio())
    }

    private fun scrivi(context: Context, nuova: MemoriaStudio) {
        runCatching {
            val f = file(context)
            val temp = File(f.parentFile, f.name + ".tmp")
            temp.writeText(json.encodeToString(MemoriaStudio.serializer(), nuova))
            if (!temp.renameTo(f)) {
                f.delete()
                temp.renameTo(f)
            }
        }
    }
}
