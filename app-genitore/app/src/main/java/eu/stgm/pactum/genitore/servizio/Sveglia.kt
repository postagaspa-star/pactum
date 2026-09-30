package eu.stgm.pactum.genitore.servizio

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import eu.stgm.pactum.genitore.sync.CadenzaVedetta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * (0.9) La sveglia del giro della vedetta.
 *
 * Un servizio in primo piano non tiene sveglio il telefono: a schermo spento il
 * processore si addormenta, e un semplice "aspetta un minuto" si addormenta con
 * lui. Così a ogni giro si chiede ad Android una sveglia (AlarmManager): quando
 * suona, il telefono resta sveglio il tempo del giro ([Risveglio]) e poi torna a
 * dormire.
 *
 * Sveglia ESATTA che suona anche a telefono fermo (setExactAndAllowWhileIdle),
 * col permesso USE_EXACT_ALARM (Android 13 e dopo) o SCHEDULE_EXACT_ALARM
 * (Android 12). Se Android non la concede (canScheduleExactAlarms falso), si
 * ripiega sulla sveglia inesatta, che Android può spostare. Cosa NON è
 * garantito, anche con la sveglia esatta: a telefono fermo Android ne concede
 * un numero limitato all'ora, e il risparmio batteria di certe marche può
 * fermare l'app. L'esenzione dalla batteria e il passo per la marca, nelle
 * Impostazioni, servono a questo.
 */
object Sveglia {

    private const val AZIONE = "eu.stgm.pactum.genitore.SVEGLIA_VEDETTA"

    /** Un solo "è suonata" in sospeso: due sveglie ravvicinate valgono un giro. */
    private val suonata = Channel<Unit>(Channel.CONFLATED)

    /**
     * Chiede la sveglia per [scadenza] (orologio monotono,
     * SystemClock.elapsedRealtime). La stessa PendingIntent sostituisce quella
     * di prima: ce n'è sempre una sola.
     */
    fun programma(context: Context, scadenza: Long) {
        val allarmi = allarmi(context) ?: return
        val sveglia = intentSveglia(context)
        if (esattePermesse(allarmi)) {
            try {
                allarmi.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, scadenza, sveglia)
                return
            } catch (e: SecurityException) {
                // permesso tolto nel frattempo: si ripiega sulla sveglia inesatta
            }
        }
        allarmi.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, scadenza, sveglia)
    }

    /**
     * Aspetta la sveglia di [scadenza]. Se è già suonata (un giro più lungo
     * dell'attesa) si riparte subito. Riserva: se la sveglia andasse persa, dopo
     * un intervallo in più di tempo da sveglio il giro riparte lo stesso.
     */
    suspend fun aspetta(scadenza: Long) {
        val resto = (scadenza - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        withTimeoutOrNull(resto + CadenzaVedetta.INTERVALLO_MS) { suonata.receive() }
    }

    /** Una sveglia vecchia (quella sostituita) non deve far partire un giro in anticipo. */
    fun dimenticaSuonate() {
        suonata.tryReceive()
    }

    /** La sveglia è suonata (o l'app chiede un giro subito): il giro può ripartire. */
    fun suona() {
        suonata.trySend(Unit)
    }

    /** Il servizio si ferma: niente più sveglie. */
    fun annulla(context: Context) {
        allarmi(context)?.cancel(intentSveglia(context))
    }

    /** true = Android concede le sveglie esatte a questa app (prima di Android 12 sempre). */
    fun esattePermesse(context: Context): Boolean = allarmi(context)?.let(::esattePermesse) ?: false

    private fun esattePermesse(allarmi: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || allarmi.canScheduleExactAlarms()

    private fun allarmi(context: Context): AlarmManager? = context.getSystemService(AlarmManager::class.java)

    private fun intentSveglia(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, SvegliaReceiver::class.java).setAction(AZIONE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/**
 * Riceve la sveglia. Android tiene sveglio il telefono solo finché questa
 * funzione non torna: prima di tornare, il risveglio passa alla vedetta, che lo
 * lascia a fine giro.
 *
 * Se il servizio non c'è più — il genitore l'ha fermato da «App attive», o
 * Android l'ha chiuso — lo rimette in piedi (se l'app è configurata): la
 * sveglia esatta dà ad Android il permesso di farlo partire da qui.
 */
class SvegliaReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (VedettaService.attivo) {
            Risveglio.tieni(context)
            Sveglia.suona()
            return
        }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                VedettaService.avviaSeConfigurata(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Il telefono sveglio per il tempo di un giro: un blocco del processore (non
 * dello schermo) con una scadenza più lunga del giro più lungo, solo come
 * sicurezza: di norma il giro finisce prima e lo lascia. Non conta le prese:
 * una sola [lascia] lo libera, e prenderlo due volte allunga solo la scadenza.
 */
object Risveglio {

    private var blocco: PowerManager.WakeLock? = null

    @Synchronized
    fun tieni(context: Context) {
        val attuale = blocco ?: context.applicationContext
            .getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pactum:vedetta")
            ?.apply { setReferenceCounted(false) }
            ?.also { blocco = it }
        attuale?.acquire(CadenzaVedetta.RISVEGLIO_MASSIMO_MS)
    }

    @Synchronized
    fun lascia() {
        blocco?.takeIf { it.isHeld }?.release()
    }

    /** [azione] col telefono sveglio, e poi di nuovo libero di dormire. */
    suspend fun <T> durante(context: Context, azione: suspend () -> T): T {
        tieni(context)
        try {
            return azione()
        } finally {
            lascia()
        }
    }
}
