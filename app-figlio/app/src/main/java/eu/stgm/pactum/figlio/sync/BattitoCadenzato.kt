package eu.stgm.pactum.figlio.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import eu.stgm.pactum.figlio.BuildConfig
import eu.stgm.pactum.figlio.dati.Battito
import eu.stgm.pactum.figlio.dati.CodaEventi
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.permessi.PermessiHelper
import eu.stgm.pactum.figlio.rete.PostinoClient
import eu.stgm.pactum.figlio.servizio.PactumService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * (0.14) Quando mandare il battito (logica pura, orologio che non si sposta).
 * Uno ogni ~15 minuti, anche a schermo spento; mai due insieme e mai due a
 * pochi minuti l'uno dall'altro, chiunque lo chieda (la sveglia, il giro del
 * servizio, il worker).
 */
object CadenzaBattito {

    /** Ogni quanto parte il battito. */
    const val INTERVALLO_MS = 15L * 60 * 1000

    /** Due battiti più vicini di così sono un doppione: il secondo non parte. */
    const val MINIMO_MS = 10L * 60 * 1000

    /** Si manda adesso? [ultimo] = l'ultimo battito partito (null = nessuno in questo processo). */
    fun serve(ultimo: Long?, adesso: Long): Boolean = ultimo == null || adesso - ultimo >= MINIMO_MS || adesso < ultimo

    /** La sveglia del prossimo battito. */
    fun prossimo(adesso: Long): Long = adesso + INTERVALLO_MS
}

/**
 * (0.14, contratto v3.7) Il battito che continua anche in stand-by.
 *
 * A schermo spento Android addormenta il processore, e il giro del servizio
 * (un "aspetta 15 minuti") si addormenta con lui: i battiti si fermavano e
 * dopo 45 minuti il genitore leggeva "il telefono non invia aggiornamenti".
 * Adesso una sveglia permessa anche in stand-by (setExactAndAllowWhileIdle,
 * col permesso USE_EXACT_ALARM; in stand-by Android la concede al massimo una
 * volta ogni ~9 minuti, e ne basta una ogni 15) sveglia il telefono il tempo
 * di un battito, tenendolo sveglio con un wakelock breve. La rete in
 * stand-by c'è grazie all'esenzione dal risparmio batteria che Pactum chiede
 * già.
 *
 * Attento alla batteria: la sveglia manda solo il battito e la coda degli
 * eventi, se c'è; niente letture dell'uso. A schermo acceso resta il giro di
 * sempre (PactumService), che passa da qui: un solo lucchetto, mai due
 * battiti insieme ([CadenzaBattito]).
 */
object BattitoCadenzato {

    private const val AZIONE = "eu.stgm.pactum.figlio.SVEGLIA_BATTITO"

    /** Il telefono sveglio al massimo così, durante il battito della sveglia. */
    const val RISVEGLIO_MASSIMO_MS = 60_000L

    /** Quanto il ricevitore della sveglia aspetta il battito prima di dire "finito" ad Android. */
    const val ATTESA_RICEVITORE_MS = 30_000L

    private val mutex = Mutex()

    /** L'ultimo battito partito in questo processo (orologio che non si sposta). */
    @Volatile
    private var ultimo: Long? = null

    /** La sveglia già chiesta in questo processo (orologio che non si sposta). */
    @Volatile
    private var svegliaPer: Long? = null

    /**
     * Il battito, se serve (e prima la coda degli eventi, se c'è). True =
     * consegnato; false = non è andato (rete, collegamento); null = non
     * serviva, ne è partito uno da poco. Sotto un solo lucchetto.
     */
    suspend fun batti(context: Context): Boolean? = mutex.withLock {
        val adesso = SystemClock.elapsedRealtime()
        if (!CadenzaBattito.serve(ultimo, adesso)) return@withLock null
        ultimo = adesso
        val app = context.applicationContext
        val impostazioni = Impostazioni(app)
        val configurazione = impostazioni.leggiConfigurazione()
        if (!configurazione.completa) return@withLock false
        val postino = PostinoClient(configurazione)
        // La coda prima del battito: una `sospensione` rimasta indietro arriva
        // prima del segno di vita che la chiude.
        val coda = CodaEventi(app)
        val eventi = coda.inAttesa()
        if (eventi.isNotEmpty()) {
            val esito = postino.inviaEventi(eventi)
            coda.rimuoviConsegnati(esito.consegnati + esito.scartati)
        }
        val consegnato = postino.inviaBattito(
            Battito(
                tsDevice = System.currentTimeMillis(),
                versioneApp = BuildConfig.VERSION_NAME,
                elapsedRealtime = SystemClock.elapsedRealtime(),
            ),
        )
        if (consegnato) impostazioni.registraBattitoConsegnato()
        consegnato
    }

    /**
     * La sveglia del prossimo battito, fra ~15 minuti. Se ce n'è già una in
     * arrivo (chiesta da questo processo), resta quella: chiamarla spesso non
     * la sposta in avanti per sempre. Con [rifai] si richiede comunque (dopo
     * che è suonata).
     */
    fun programma(context: Context, rifai: Boolean = false) {
        val adesso = SystemClock.elapsedRealtime()
        val gia = svegliaPer
        if (!rifai && gia != null && gia > adesso) return
        val quando = CadenzaBattito.prossimo(adesso)
        val allarmi = context.applicationContext.getSystemService(AlarmManager::class.java) ?: return
        val sveglia = intentSveglia(context)
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || allarmi.canScheduleExactAlarms()) {
                allarmi.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, quando, sveglia)
            } else {
                allarmi.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, quando, sveglia)
            }
            svegliaPer = quando
        } catch (e: SecurityException) {
            try {
                allarmi.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, quando, sveglia)
                svegliaPer = quando
            } catch (e: Exception) {
                // niente sveglia: resta il giro del servizio e il worker
            }
        } catch (e: Exception) {
            // idem
        }
    }

    private fun intentSveglia(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context.applicationContext,
        0,
        Intent(context.applicationContext, SvegliaBattitoReceiver::class.java).setAction(AZIONE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/**
 * (0.14) La sveglia del battito: il telefono resta sveglio (wakelock breve)
 * il tempo del battito, poi si chiede la sveglia dopo. Se il servizio del
 * testimone non c'è più, si rimette in piedi (la sveglia esatta lo permette).
 */
class SvegliaBattitoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val sveglio = try {
            app.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pactum:battito")
                ?.apply {
                    setReferenceCounted(false)
                    acquire(BattitoCadenzato.RISVEGLIO_MASSIMO_MS)
                }
        } catch (e: Exception) {
            null
        }
        val pending = goAsync()
        val ambito = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ambito.launch {
            val lavoro = ambito.launch {
                try {
                    BattitoCadenzato.batti(app)
                } catch (e: Exception) {
                    // il battito dopo
                } finally {
                    BattitoCadenzato.programma(app, rifai = true)
                    if (!PactumService.vivo && PermessiHelper.haAccessoUso(app)) {
                        try {
                            PactumService.avvia(app)
                        } catch (e: Exception) {
                            // rifiutato: lo riaccende il worker
                        }
                    }
                    try {
                        if (sveglio?.isHeld == true) sveglio.release()
                    } catch (e: Exception) {
                        // già lasciato
                    }
                }
            }
            // Il ricevitore non aspetta più di così (Android lo vuole finito
            // presto); il battito, se la rete è lenta, finisce da solo, col
            // telefono ancora sveglio fino al massimo del wakelock.
            withTimeoutOrNull(BattitoCadenzato.ATTESA_RICEVITORE_MS) { lavoro.join() }
            pending.finish()
        }
    }
}
