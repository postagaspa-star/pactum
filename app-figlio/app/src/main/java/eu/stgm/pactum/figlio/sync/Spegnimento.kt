package eu.stgm.pactum.figlio.sync

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import eu.stgm.pactum.figlio.dati.CodaEventi
import eu.stgm.pactum.figlio.dati.Evento
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.dati.TipiEvento
import eu.stgm.pactum.figlio.misura.Sessioni
import eu.stgm.pactum.figlio.permessi.PermessiHelper
import eu.stgm.pactum.figlio.rete.PostinoClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs

/**
 * (0.14, contratto v3.7) Il telefono si spegne: `sospensione`
 * `{ "motivo": "spegnimento" }`, come il computer. Così il silenzio che segue
 * non è un'interruzione: il genitore vede "spento", non "non invia aggiornamenti".
 *
 *  - All'avviso di spegnimento di Android (ACTION_SHUTDOWN, e le varianti di
 *    alcune marche) l'evento entra in coda e parte SUBITO, con un tempo
 *    massimo breve: lo spegnimento non si trattiene ([annuncia]).
 *  - Se non ce la fa, resta in coda e parte alla riaccensione. Se l'avviso
 *    non è proprio arrivato (Pactum fermo, una marca che non lo manda), alla
 *    riaccensione si guarda l'ultimo spegnimento negli eventi d'uso di
 *    Android (DEVICE_SHUTDOWN) e la `sospensione` nasce lì, con quell'ora
 *    ([allaRiaccensione]). Dopo, sempre, `ripresa` `{ "motivo": "avvio" }`.
 *  - Niente doppioni: lo spegnimento già messo in coda dall'avviso non
 *    rinasce dagli eventi d'uso, e un evento consegnato due volte (la
 *    risposta persa mentre il telefono si spegneva) il server lo conta una
 *    volta sola (stesso id).
 */
object Spegnimento {

    /** Le azioni che dicono "il telefono si spegne o si riavvia". */
    val AZIONI: Set<String> = setOf(
        Intent.ACTION_SHUTDOWN,
        "android.intent.action.QUICKBOOT_POWEROFF",
        "com.htc.intent.action.QUICKBOOT_POWEROFF",
    )

    /** Il tempo massimo per mandare la `sospensione` mentre il telefono si spegne. */
    const val LIMITE_INVIO_MS = 3_000L

    /** Lo stesso spegnimento visto due volte (l'avviso e gli eventi d'uso) sta dentro questo margine. */
    const val MARGINE_MS = 5L * 60 * 1000

    /** Fin dove si cerca lo spegnimento negli eventi d'uso. */
    private const val INDIETRO_MS = 7L * 24 * 60 * 60 * 1000

    private val mutex = Mutex()

    /** L'avviso arrivato in questo processo (il ricevitore del servizio e quello del manifest): una volta sola. */
    @Volatile
    private var annunciatoIl: Long? = null

    fun eventoSospensione(quando: Long): Evento = Evento(
        tipo = TipiEvento.SOSPENSIONE,
        tsDevice = quando,
        dettagli = buildJsonObject { put("motivo", "spegnimento") },
    )

    fun eventoRipresa(adesso: Long, accensioneIl: Long): Evento = Evento(
        tipo = TipiEvento.RIPRESA,
        tsDevice = adesso,
        dettagli = buildJsonObject {
            put("motivo", "avvio")
            put("avvio_sistema_ts", accensioneIl)
        },
    )

    /**
     * Lo spegnimento da annunciare alla riaccensione (logica pura): l'ultimo
     * degli eventi d'uso prima dell'accensione ([spegnimenti]), se non è già
     * stato guardato ([giaVisto]) e se l'avviso di Android non l'ha già messo
     * in coda ([giaAnnunciato], o una `sospensione` ancora in coda,
     * [inCoda]). Null = niente da annunciare.
     */
    fun daAnnunciareAllaRiaccensione(
        spegnimenti: List<Long>,
        accensioneIl: Long,
        giaVisto: Long?,
        giaAnnunciato: Long?,
        inCoda: List<Long>,
    ): Long? {
        val ultimo = spegnimenti.filter { it <= accensioneIl + MARGINE_MS }.maxOrNull() ?: return null
        if (giaVisto != null && ultimo <= giaVisto) return null
        if (giaAnnunciato != null && abs(ultimo - giaAnnunciato) <= MARGINE_MS) return null
        if (inCoda.any { abs(ultimo - it) <= MARGINE_MS }) return null
        return ultimo
    }

    /** Dall'avviso di spegnimento: in coda e, se si riesce in pochi secondi, subito al server. */
    suspend fun annuncia(context: Context, adesso: Long = System.currentTimeMillis()) = mutex.withLock {
        val gia = annunciatoIl
        if (gia != null && abs(adesso - gia) <= MARGINE_MS) return@withLock
        annunciatoIl = adesso
        val app = context.applicationContext
        val impostazioni = Impostazioni(app)
        val evento = eventoSospensione(adesso)
        val coda = CodaEventi(app)
        coda.accoda(evento)
        impostazioni.registraSpegnimentoAnnunciato(adesso)
        val configurazione = impostazioni.leggiConfigurazione()
        if (!configurazione.completa) return@withLock
        if (PostinoClient(configurazione).inviaEventiSubito(listOf(evento), LIMITE_INVIO_MS)) {
            coda.rimuoviConsegnati(listOf(evento))
        }
    }

    /**
     * Alla riaccensione (BootReceiver): la `sospensione` che l'avviso non ha
     * messo in coda (dagli eventi d'uso), poi la `ripresa`. In coda, in
     * quest'ordine: partono col primo invio (il battito, il worker).
     */
    suspend fun allaRiaccensione(context: Context, adesso: Long = System.currentTimeMillis()) = mutex.withLock {
        val app = context.applicationContext
        val impostazioni = Impostazioni(app)
        val coda = CodaEventi(app)
        val accensioneIl = adesso - SystemClock.elapsedRealtime()
        val spegnimenti = if (PermessiHelper.haAccessoUso(app)) spegnimentiDagliEventi(app, adesso) else emptyList()
        val inCoda = coda.inAttesa().filter { it.tipo == TipiEvento.SOSPENSIONE }.map { it.tsDevice }
        val daAnnunciare = daAnnunciareAllaRiaccensione(
            spegnimenti = spegnimenti,
            accensioneIl = accensioneIl,
            giaVisto = impostazioni.leggiSpegnimentoVisto(),
            giaAnnunciato = impostazioni.leggiSpegnimentoAnnunciato(),
            inCoda = inCoda,
        )
        if (daAnnunciare != null) coda.accoda(eventoSospensione(daAnnunciare))
        spegnimenti.maxOrNull()?.let { impostazioni.registraSpegnimentoVisto(it) }
        coda.accoda(eventoRipresa(adesso, accensioneIl))
    }

    /** Gli spegnimenti negli eventi d'uso dell'ultima settimana (orologio a muro). */
    private fun spegnimentiDagliEventi(app: Context, adesso: Long): List<Long> = try {
        val usm = app.getSystemService(UsageStatsManager::class.java)
        val eventi = usm?.queryEvents(adesso - INDIETRO_MS, adesso + 1)
        val trovati = ArrayList<Long>()
        if (eventi != null) {
            val evento = UsageEvents.Event()
            while (eventi.hasNextEvent()) {
                eventi.getNextEvent(evento)
                if (evento.eventType == Sessioni.SPEGNIMENTO) trovati += evento.timeStamp
            }
        }
        trovati
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * (0.14) L'avviso di spegnimento di Android. Da Android 9 arriva solo a chi
 * lo ascolta mentre è vivo: lo registra il servizio del testimone
 * (PactumService); il ricevitore nel manifest vale per Android 8 e per le
 * varianti di alcune marche. Il lavoro si fa di corsa (goAsync, al massimo
 * pochi secondi): lo spegnimento non si trattiene.
 */
class SpegnimentoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in Spegnimento.AZIONI) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(Spegnimento.LIMITE_INVIO_MS + 1_500) { Spegnimento.annuncia(app) }
            } catch (e: Exception) {
                // resta in coda (o la ritrova la riaccensione negli eventi d'uso)
            } finally {
                pending.finish()
            }
        }
    }
}
