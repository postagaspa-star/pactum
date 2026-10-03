package eu.stgm.pactum.figlio.sync

import android.content.Context
import eu.stgm.pactum.figlio.dati.CodaEventi
import eu.stgm.pactum.figlio.dati.Evento
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.dati.TipiEvento
import eu.stgm.pactum.figlio.rete.PostinoClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * (0.9) La consegna immediata della coda: la chiama la sentinella appena nasce
 * uno sforamento, così l'evento arriva al server entro un minuto invece di
 * aspettare il giro del worker (fino a 15 minuti, di più con Doze). Insieme
 * parte la fotografia d'uso dalla stessa lettura che l'ha fatto scattare.
 *
 * Se l'invio non riesce, il giro veloce riprova ([riprovaSeServe]) con attesa
 * crescente, 1, 2 e poi 4 minuti (Ritento): mai a raffica. Il worker resta la
 * riserva. Un doppio invio con il worker è innocuo (l'id dell'evento è la
 * chiave di idempotenza del server, e la coda si svuota per id).
 */
object ConsegnaEventi {

    private val mutex = Mutex()

    // I tentativi falliti di fila, e quando è partito l'ultimo. In memoria: se
    // il processo muore, al primo giro si guarda la coda una volta.
    @Volatile private var falliti = 0
    @Volatile private var ultimoTentativo = 0L
    @Volatile private var forseInAttesa = true

    /** Manda adesso la coda, con [fotografia] se c'è. True se è arrivata. */
    suspend fun subito(context: Context, fotografia: Evento? = null): Boolean = mutex.withLock {
        val inizio = System.currentTimeMillis()
        invia(context.applicationContext, fotografia).also { registra(it, inizio) }
    }

    /**
     * Dal giro veloce: se in coda c'è uno sforamento che non è arrivato,
     * riprova quando l'attesa è passata. Niente da fare se l'ultimo invio è
     * andato o se nel frattempo l'ha consegnato il worker.
     */
    suspend fun riprovaSeServe(context: Context, adesso: Long = System.currentTimeMillis()): Boolean {
        if (!forseInAttesa || !Ritento.pronto(falliti, ultimoTentativo, adesso)) return false
        return mutex.withLock {
            val app = context.applicationContext
            if (CodaEventi(app).inAttesa().none { it.tipo == TipiEvento.SFORAMENTO }) {
                falliti = 0
                forseInAttesa = false
                return@withLock false
            }
            invia(app, null).also { registra(it, adesso) }
        }
    }

    private suspend fun invia(app: Context, fotografia: Evento?): Boolean {
        val configurazione = Impostazioni(app).leggiConfigurazione()
        if (!configurazione.completa) return false
        val coda = CodaEventi(app)
        fotografia?.let { coda.sostituisciUsoGiornaliero(it) }
        val eventi = coda.inAttesa()
        // (0.13) In pacchi più piccoli se il server dice che il corpo è troppo
        // grande: escono dalla coda solo quelli arrivati (o impossibili da mandare).
        val esito = PostinoClient(configurazione).inviaEventi(eventi)
        coda.rimuoviConsegnati(esito.consegnati + esito.scartati)
        return esito.tutti
    }

    private fun registra(consegnati: Boolean, quando: Long) {
        if (consegnati) {
            falliti = 0
            forseInAttesa = false
        } else {
            falliti += 1
            ultimoTentativo = quando
            forseInAttesa = true
        }
    }
}

/**
 * Quanto aspettare prima di riprovare un invio fallito (logica pura): 1 minuto
 * dopo il primo fallimento, 2 dopo il secondo, poi 4 minuti ogni volta.
 */
object Ritento {

    private val ATTESE_MS = longArrayOf(60_000L, 120_000L, 240_000L)

    fun attesa(falliti: Int): Long = if (falliti <= 0) 0L else ATTESE_MS[minOf(falliti, ATTESE_MS.size) - 1]

    /** Si può riprovare: dall'ultimo tentativo è passata l'attesa che tocca. */
    fun pronto(falliti: Int, ultimoTentativo: Long, adesso: Long): Boolean =
        adesso - ultimoTentativo >= attesa(falliti)
}
