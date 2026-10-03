package eu.stgm.pactum.figlio.faccende

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** (0.13) A che punto è la foto di una faccenda, sul telefono. */
object StatiFoto {
    /** Pronta, in attesa di partire (o di riprovare). */
    const val IN_CODA = "in_coda"

    /** Il server l'ha presa: si aspetta che il blocco lo dica. */
    const val MANDATA = "mandata"

    /** Il server non l'ha accettata per com'è: va scattata di nuovo. */
    const val RIFIUTATA = "rifiutata"
}

/**
 * (0.13) Di quale collegamento è una foto: il codice (un'impronta del token,
 * mai il token) e, se si sa, il dispositivo e il figlio. È lo stesso
 * collegamento se il codice è lo stesso (anche con l'indirizzo del server
 * scritto in un altro modo) o se è lo stesso dispositivo dello stesso figlio
 * (anche ricollegato con un codice nuovo).
 */
@Serializable
data class ChiaveCollegamento(val codice: String, val dispositivo: String? = null) {

    fun stesso(altra: ChiaveCollegamento): Boolean =
        codice == altra.codice || (dispositivo != null && dispositivo == altra.dispositivo)

    companion object {
        /** Logica pura: [impronta] = l'impronta del solo token. */
        fun di(impronta: String, dispositivoId: Long?, figlioId: Long?): ChiaveCollegamento =
            ChiaveCollegamento(
                codice = impronta,
                dispositivo = dispositivoId?.takeIf { it > 0 }?.let { d -> "d$d-f${figlioId?.takeIf { it > 0 } ?: 0}" },
            )

        /** Un'impronta del token (SHA-256, i primi 8 byte): il token non si salva mai accanto alle foto. */
        fun impronta(token: String): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(token.trim().toByteArray(Charsets.UTF_8))
                .take(8)
                .joinToString("") { "%02x".format(it) }
    }
}

/**
 * Una foto in coda per una faccenda. [file] = il nome del file pronto in
 * FotoFaccenda.cartellaPronte; [collegamento] = il collegamento con cui è
 * stata scattata (il dispositivo e il figlio, o il codice: una foto non parte
 * mai verso il patto di un altro collegamento; cambiare solo l'indirizzo del
 * server non la butta); [bocciature] = le bocciature della faccenda quando è
 * stata scattata (una bocciatura dopo la rende vecchia: non si rimanda mai).
 */
@Serializable
data class FotoInCoda(
    val faccendaId: Long,
    val file: String,
    val scattataIl: Long,
    val collegamento: ChiaveCollegamento,
    val stato: String = StatiFoto.IN_CODA,
    val bocciature: Int = 0,
    val tentativi: Int = 0,
    val mandataIl: Long? = null,
)

/**
 * (0.13) La coda delle foto da mandare (logica pura; il file sta in
 * ArchivioCodaFoto). Una foto per faccenda: uno scatto nuovo prende il posto
 * di quello di prima. Persistente: sopravvive alla morte del processo e al
 * riavvio, e parte da sola quando torna la rete (ConsegnaFoto).
 */
@Serializable
data class MemoriaCodaFoto(val foto: List<FotoInCoda> = emptyList()) {

    /** Lo scatto nuovo al posto di quello di prima della stessa faccenda. Il secondo: i file da cancellare. */
    fun conScatto(nuova: FotoInCoda): Pair<MemoriaCodaFoto, List<String>> {
        val restano = foto.filterNot { it.faccendaId == nuova.faccendaId }
        val tenute = (restano + nuova).takeLast(MASSIMO)
        val fileTenuti = tenute.mapTo(HashSet()) { it.file }
        val daCancellare = (foto + nuova).map { it.file }.filter { it !in fileTenuti }.distinct()
        return MemoriaCodaFoto(tenute) to daCancellare
    }

    /** Le foto da mandare col collegamento [collegamento], dalla più vecchia. */
    fun daMandare(collegamento: ChiaveCollegamento): List<FotoInCoda> =
        foto.filter { it.stato == StatiFoto.IN_CODA && it.collegamento.stesso(collegamento) }.sortedBy { it.scattataIl }

    /** Le foto scattate con un altro collegamento: non partono mai (il secondo: i file da cancellare). */
    fun senzaAltriCollegamenti(collegamento: ChiaveCollegamento): Pair<MemoriaCodaFoto, List<String>> {
        val (mie, altre) = foto.partition { it.collegamento.stesso(collegamento) }
        return MemoriaCodaFoto(mie) to altre.map { it.file }
    }

    /**
     * Com'è andato l'invio di [file]. Arrivata: resta come "mandata" finché il
     * server non dice la faccenda fatta (il file si cancella subito). Non
     * serve più: via. Rifiutata: resta per dirlo, senza file. Il resto (rete,
     * server, collegamento): resta in coda e si riprova. Il secondo: i file da
     * cancellare.
     */
    fun conEsito(faccendaId: Long, file: String, esito: EsitoFoto, adesso: Long): Pair<MemoriaCodaFoto, List<String>> {
        val voce = foto.firstOrNull { it.faccendaId == faccendaId && it.file == file } ?: return this to emptyList()
        val nuova: FotoInCoda? = when (esito) {
            EsitoFoto.ARRIVATA -> voce.copy(stato = StatiFoto.MANDATA, mandataIl = adesso)
            EsitoFoto.NON_SERVE, EsitoFoto.BOCCIATA_NEL_FRATTEMPO -> null
            EsitoFoto.RIFIUTATA -> voce.copy(stato = StatiFoto.RIFIUTATA)
            EsitoFoto.SERVER_VECCHIO, EsitoFoto.SCOLLEGATO, EsitoFoto.SENZA_RETE, EsitoFoto.ERRORE ->
                voce.copy(tentativi = voce.tentativi + 1)
        }
        val altre = foto.filterNot { it === voce }
        val dopo = MemoriaCodaFoto(if (nuova == null) altre else foto.map { if (it === voce) nuova else it })
        val daCancellare = if (nuova == null || nuova.stato != StatiFoto.IN_CODA) listOf(file) else emptyList()
        return dopo to daCancellare
    }

    /**
     * Quello che dice il server delle faccende ([daFare] = le faccende da fare
     * adesso): una foto scattata prima di una bocciatura della sua faccenda
     * non conta più, nemmeno se è ancora in coda (era già arrivata, la
     * risposta si era persa, e il genitore l'ha bocciata: rimandarla
     * sbloccherebbe con la foto bocciata). Una mandata o rifiutata resta
     * finché la faccenda è ancora da fare (poi la dice il server: fatta,
     * annullata o bocciata); una mandata da più di un giorno se ne va
     * comunque. Una in coda di una faccenda che il blocco non dice (forse il
     * blocco è vecchio) resta: decide il server quando arriva. Il secondo: i
     * file da cancellare.
     */
    fun conDaFare(daFare: List<FaccendaDaFare>, adesso: Long): Pair<MemoriaCodaFoto, List<String>> {
        val perId = daFare.associateBy { it.id }
        val (tenute, via) = foto.partition { voce ->
            val faccenda = perId[voce.faccendaId]
            when {
                faccenda != null && faccenda.bocciature > voce.bocciature -> false
                voce.stato == StatiFoto.IN_CODA -> true
                faccenda == null -> false
                else -> voce.stato != StatiFoto.MANDATA || adesso - (voce.mandataIl ?: adesso) < UN_GIORNO_MS
            }
        }
        return MemoriaCodaFoto(tenute) to via.filter { it.stato == StatiFoto.IN_CODA }.map { it.file }
    }

    /**
     * Subito dopo una risposta fresca del server sul blocco: una foto in coda
     * di una faccenda che non è più da fare (fatta con un'altra foto, o
     * annullata) non serve più. Il secondo: i file da cancellare.
     */
    fun soloDaFare(daFare: List<FaccendaDaFare>): Pair<MemoriaCodaFoto, List<String>> {
        val ids = daFare.mapTo(HashSet()) { it.id }
        val (tenute, via) = foto.partition { it.stato != StatiFoto.IN_CODA || it.faccendaId in ids }
        return MemoriaCodaFoto(tenute) to via.map { it.file }
    }

    /** La foto di [faccendaId], se c'è. */
    fun di(faccendaId: Long): FotoInCoda? = foto.lastOrNull { it.faccendaId == faccendaId }

    companion object {
        /** Al massimo 20 faccende da fare per figlio (contratto): una foto ciascuna, con margine. */
        const val MASSIMO = 30
        private const val UN_GIORNO_MS = 24L * 60 * 60 * 1000
    }
}

/**
 * (0.13) La coda delle foto su disco (`faccende/coda.json`), scrittura
 * atomica sotto un solo lock, con una copia in memoria per le schermate.
 */
object ArchivioCodaFoto {

    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile
    private var memoria: MemoriaCodaFoto? = null

    private val _stato = MutableStateFlow(MemoriaCodaFoto())
    val stato: StateFlow<MemoriaCodaFoto> = _stato.asStateFlow()

    fun leggi(context: Context): MemoriaCodaFoto = memoria ?: synchronized(lock) {
        memoria ?: caricaDaDisco(context).also {
            memoria = it
            _stato.value = it
        }
    }

    /** Una modifica: [trasforma] dà la coda nuova e i file da cancellare. */
    fun modifica(context: Context, trasforma: (MemoriaCodaFoto) -> Pair<MemoriaCodaFoto, List<String>>): MemoriaCodaFoto =
        synchronized(lock) {
            val attuale = leggi(context)
            val (nuova, daCancellare) = trasforma(attuale)
            if (nuova != attuale) scrivi(context, nuova)
            memoria = nuova
            _stato.value = nuova
            val cartella = FotoFaccenda.cartellaPronte(context)
            daCancellare.forEach { nome -> runCatching { File(cartella, nome).delete() } }
            nuova
        }

    /**
     * I file pronti che nessuna voce della coda conosce (un processo morto a
     * metà): via, se hanno più di un'ora (uno appena preparato sta per entrare).
     */
    fun pulisciOrfani(context: Context, adesso: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val noti = leggi(context).foto.map { it.file }.toSet()
            runCatching {
                FotoFaccenda.cartellaPronte(context).listFiles()?.forEach { f ->
                    if (f.name !in noti && adesso - f.lastModified() > UN_ORA_MS) f.delete()
                }
            }
        }
    }

    private const val UN_ORA_MS = 60L * 60 * 1000

    private fun file(context: Context) =
        File(FotoFaccenda.cartellaPronte(context).parentFile, "coda.json")

    private fun caricaDaDisco(context: Context): MemoriaCodaFoto {
        val f = file(context)
        if (!f.exists()) return MemoriaCodaFoto()
        return runCatching { json.decodeFromString(MemoriaCodaFoto.serializer(), f.readText()) }
            .getOrDefault(MemoriaCodaFoto())
    }

    private fun scrivi(context: Context, nuova: MemoriaCodaFoto) {
        runCatching {
            val f = file(context)
            val temp = File(f.parentFile, f.name + ".tmp")
            temp.writeText(json.encodeToString(MemoriaCodaFoto.serializer(), nuova))
            if (!temp.renameTo(f)) {
                f.delete()
                temp.renameTo(f)
            }
        }
    }
}
