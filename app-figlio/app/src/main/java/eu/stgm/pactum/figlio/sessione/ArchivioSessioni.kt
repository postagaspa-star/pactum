package eu.stgm.pactum.figlio.sessione

import android.content.Context
import eu.stgm.pactum.figlio.dati.Patto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File

/**
 * (0.11) La sessione in corso adesso, per tutto il processo: la guardano il
 * servizio (barriera e notifica fissa), la barriera stessa e le schermate.
 * Si aggiorna a ogni scrittura dell'archivio. Una sessione arrivata alla sua
 * fine resta qui finché qualcuno non ricalcola: chi la usa guarda sempre
 * anche [SessioneAttiva.fine] ([attivaAdesso]).
 */
object StatoSessione {

    private val _attiva = MutableStateFlow<SessioneAttiva?>(null)
    val attiva: StateFlow<SessioneAttiva?> = _attiva.asStateFlow()

    private val _incerto = MutableStateFlow<AvvioIncerto?>(null)

    /** Un "Inizia" rimasto senza risposta, da chiarire appena c'è rete. */
    val incerto: StateFlow<AvvioIncerto?> = _incerto.asStateFlow()

    /** La sessione in corso a [adesso], null se non c'è o se è già finita. */
    fun attivaAdesso(adesso: Long = System.currentTimeMillis()): SessioneAttiva? =
        _attiva.value?.takeIf { adesso < it.fine }

    internal fun aggiorna(memoria: MemoriaSessioni, adesso: Long = System.currentTimeMillis()) {
        _attiva.value = memoria.inCorso(adesso)
        _incerto.value = memoria.avvioIncerto
    }
}

/**
 * (0.11) Una richiesta di "Termina la sessione" arrivata da fuori (il pulsante
 * della notifica fissa): la scheda della sessione in corso apre la sua
 * conferma. Terminare passa sempre da una conferma dentro Pactum.
 */
object RichiestaTermine {

    private val _richiesta = MutableStateFlow(0L)

    /** Quando è arrivata la richiesta (orologio monotono, nanosecondi); 0 = nessuna. */
    val richiesta: StateFlow<Long> = _richiesta.asStateFlow()

    fun chiedi() {
        _richiesta.value = System.nanoTime().coerceAtLeast(1L)
    }

    /** La richiesta è di adesso: una rimasta lì (nessuna sessione da terminare) non apre niente dopo. */
    fun fresca(quando: Long): Boolean = quando != 0L && System.nanoTime() - quando < VALIDITA_NS

    fun consuma() {
        _richiesta.value = 0L
    }

    private const val VALIDITA_NS = 30_000_000_000L
}

/**
 * (0.11) Le sessioni svolte su disco (un file JSON in filesDir, scrittura
 * atomica come PattoLocale): sopravvivono alla morte del processo e al
 * riavvio. Dopo una reinstallazione il file non c'è più, e le riporta
 * `GET /api/patto` (`sessione_in_corso`, `sessioni_svolte`) a ogni lettura
 * del patto ([daServer], chiamata da PattoLocale.salva).
 *
 * Una copia in memoria per processo: la misura la legge a ogni giro senza
 * toccare il disco. Le scritture passano da un solo lock; chi chiama sta su un
 * thread di I/O.
 */
object ArchivioSessioni {

    private const val NOME_FILE = "sessioni_svolte.json"
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var memoria: MemoriaSessioni? = null

    /** Il file c'è (o è appena stato scritto): il telefono sa quali sessioni ci sono state. */
    @Volatile
    private var conosciute: Boolean = false

    /** Carica l'archivio fuori dal thread principale, all'avvio del processo. */
    fun precarica(context: Context) {
        val app = context.applicationContext
        ambito.launch { runCatching { leggi(app) } }
    }

    /** Quello che si sa adesso (dal disco la prima volta). */
    fun leggi(context: Context): MemoriaSessioni = memoria ?: synchronized(lock) {
        memoria ?: caricaDaDisco(context).also {
            memoria = it
            StatoSessione.aggiorna(it)
        }
    }

    /** Il file c'è: il telefono ha già una memoria delle sessioni (o ha già letto il server). */
    fun esiste(context: Context): Boolean = file(context).exists()

    /**
     * Il telefono sa quali sessioni ci sono state: ha il suo archivio, scritto
     * da una lettura del server o da un avvio. Dopo una reinstallazione no,
     * finché il server non le riporta: allora i minuti in sessione non si sanno.
     */
    fun sessioniNote(context: Context): Boolean {
        leggi(context)
        return conosciute
    }

    /**
     * Una modifica sotto il lock: [trasforma] (pura) dà la memoria nuova, che
     * va su disco e nello stato del processo. [forzaScrittura] = si scrive
     * anche se non è cambiato niente (la prima lettura dal server crea il file).
     */
    fun modifica(
        context: Context,
        forzaScrittura: Boolean = false,
        trasforma: (MemoriaSessioni) -> MemoriaSessioni,
    ): MemoriaSessioni = synchronized(lock) {
        val attuale = leggi(context)
        val nuova = trasforma(attuale)
        if (nuova != attuale || forzaScrittura) scrivi(context, nuova)
        memoria = nuova
        StatoSessione.aggiorna(nuova)
        nuova
    }

    /** Come [modifica], con un risultato in più calcolato dalla stessa trasformazione. */
    fun <T> modificaCon(context: Context, trasforma: (MemoriaSessioni) -> Pair<MemoriaSessioni, T>): T =
        synchronized(lock) {
            val attuale = leggi(context)
            val (nuova, risultato) = trasforma(attuale)
            if (nuova != attuale) scrivi(context, nuova)
            memoria = nuova
            StatoSessione.aggiorna(nuova)
            risultato
        }

    /** Il tempo è passato: la sessione arrivata alla fine non è più "in corso". */
    fun ricalcola(context: Context, adesso: Long = System.currentTimeMillis()) {
        StatoSessione.aggiorna(leggi(context), adesso)
    }

    /** Il ragazzo ora sa che la sessione [svoltaId] è partita (notifica, o vista in Pactum). */
    fun annuncia(context: Context, svoltaId: Long) {
        modifica(context) { it.conAnnuncio(svoltaId) }
    }

    /**
     * Quello che dice il server, da una copia del patto appena entrata. Un
     * server di prima della v3.5 non dice niente delle sessioni: niente da fare.
     */
    fun daServer(context: Context, patto: Patto, adesso: Long = System.currentTimeMillis()) {
        if (!patto.conosceSessioni) return
        val svolte = patto.sessioniSvolte.mapNotNull { it.inLocale() }
        val inCorso = patto.sessioneInCorso?.inLocale()
        modifica(context, forzaScrittura = !esiste(context)) { it.conServer(svolte, inCorso, adesso) }
    }

    /**
     * Il telefono è passato a un ALTRO dispositivo del patto: le sessioni di
     * prima non sono sue. Se ce n'era una in corso, qui finisce subito.
     */
    fun svuota(context: Context) {
        synchronized(lock) {
            file(context).delete()
            val vuota = MemoriaSessioni()
            memoria = vuota
            conosciute = false
            StatoSessione.aggiorna(vuota)
        }
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, NOME_FILE)

    private fun caricaDaDisco(context: Context): MemoriaSessioni {
        val f = file(context)
        if (!f.exists()) return MemoriaSessioni()
        conosciute = true
        return runCatching { json.decodeFromString(MemoriaSessioni.serializer(), f.readText()) }
            .getOrDefault(MemoriaSessioni())
    }

    private fun scrivi(context: Context, nuova: MemoriaSessioni) {
        runCatching {
            val f = file(context)
            val temp = File(f.parentFile, f.name + ".tmp")
            temp.writeText(json.encodeToString(MemoriaSessioni.serializer(), nuova))
            if (!temp.renameTo(f)) {
                f.delete()
                temp.renameTo(f)
            }
            conosciute = true
        }
    }
}
