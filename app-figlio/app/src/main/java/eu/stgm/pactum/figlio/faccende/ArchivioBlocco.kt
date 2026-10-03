package eu.stgm.pactum.figlio.faccende

import android.content.Context
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
 * (0.13) Il blocco delle faccende adesso, per tutto il processo: lo guardano
 * il servizio (la barriera), la barriera stessa, la pagina Faccende e le
 * Sessioni (che col blocco non si avviano). Si aggiorna a ogni scrittura
 * dell'archivio. Il blocco dipende anche dall'ora (parte da solo all'ora di
 * `prossimo`): chi lo usa chiede sempre [attivoAdesso].
 */
object StatoBlocco {

    private val _memoria = MutableStateFlow(MemoriaBlocco())
    val memoria: StateFlow<MemoriaBlocco> = _memoria.asStateFlow()

    /** "Guarda di nuovo": la sveglia di `prossimo`, lo schermo che si riaccende, una risposta nuova. */
    private val _sveglia = Channel<Unit>(Channel.CONFLATED)
    val sveglia: ReceiveChannel<Unit> get() = _sveglia

    fun svegliati() {
        _sveglia.trySend(Unit)
    }

    fun attivoAdesso(ora: Istante = Orologio.adesso()): Boolean = _memoria.value.attivoAdesso(ora)

    /** Ogni cambio sveglia anche il giro della barriera che aspetta (PactumService). */
    internal fun aggiorna(memoria: MemoriaBlocco) {
        if (memoria == _memoria.value) return
        _memoria.value = memoria
        _sveglia.trySend(Unit)
    }
}

/**
 * (0.13) Il blocco su disco (un file JSON in filesDir, scrittura atomica come
 * ArchivioSessioni): sopravvive alla morte del processo e al riavvio, così un
 * telefono bloccato resta bloccato anche spento e riacceso senza rete.
 */
object ArchivioBlocco {

    private const val NOME_FILE = "blocco_faccende.json"
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var memoria: MemoriaBlocco? = null

    /** Carica l'archivio fuori dal thread principale, all'avvio del processo. */
    fun precarica(context: Context) {
        val app = context.applicationContext
        ambito.launch { runCatching { leggi(app) } }
    }

    fun leggi(context: Context): MemoriaBlocco = memoria ?: synchronized(lock) {
        memoria ?: caricaDaDisco(context).also {
            memoria = it
            StatoBlocco.aggiorna(it)
        }
    }

    /** Una modifica sotto il lock: [trasforma] (pura) dà la memoria nuova, su disco e nello stato del processo. */
    fun modifica(context: Context, trasforma: (MemoriaBlocco) -> MemoriaBlocco): MemoriaBlocco = synchronized(lock) {
        val attuale = leggi(context)
        val nuova = trasforma(attuale)
        if (nuova != attuale) scrivi(context, nuova)
        memoria = nuova
        StatoBlocco.aggiorna(nuova)
        nuova
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, NOME_FILE)

    private fun caricaDaDisco(context: Context): MemoriaBlocco {
        val f = file(context)
        if (!f.exists()) return MemoriaBlocco()
        return runCatching { json.decodeFromString(MemoriaBlocco.serializer(), f.readText()) }
            // Un file rovinato: meglio ripartire e chiedere al server che bloccare a caso.
            .getOrDefault(MemoriaBlocco())
    }

    private fun scrivi(context: Context, nuova: MemoriaBlocco) {
        runCatching {
            val f = file(context)
            val temp = File(f.parentFile, f.name + ".tmp")
            temp.writeText(json.encodeToString(MemoriaBlocco.serializer(), nuova))
            if (!temp.renameTo(f)) {
                f.delete()
                temp.renameTo(f)
            }
        }
    }
}
