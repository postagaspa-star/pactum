package eu.stgm.pactum.genitore.servizio

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.util.Locale
import kotlin.random.Random

/**
 * (0.25) Perché Pactum si è fermato l'ultima volta (richiesta di Andrea, 27/10: «le
 * notifiche continuano a non apparire finché non apri l'app»). Dai registri del server:
 * su certi telefoni il servizio sempre attivo smette di chiedere le novità per ore e
 * riparte solo quando si apre l'app. Android tiene il motivo di ogni chiusura
 * (ApplicationExitInfo, da Android 11): se Pactum è stato chiuso DEL TUTTO (per esempio
 * togliendolo dalla lista delle app aperte, su certi telefoni, o con "Arresto forzato"),
 * niente lo può far ripartire da solo — né il servizio, né la sveglia, né il worker —
 * finché qualcuno non lo riapre. Questo lo si dice al genitore, una volta, in Panoramica.
 *
 * In più la vedetta si fa riconoscere nei registri del server ([Diagnosi]): quale
 * telefono chiede le novità e, al primo giro dopo una ripartenza, perché si era fermato.
 */
data class Fermata(val quando: Long, val motivo: Int, val descrizione: String?)

object Fermate {

    /** Motivi di ApplicationExitInfo (costanti numeriche, così vale anche dove l'API è più vecchia). */
    const val UTENTE = 10 // REASON_USER_REQUESTED: arresto forzato, o chiusa dalla lista su certi telefoni
    const val UTENTE_FERMATA = 11 // REASON_USER_STOPPED (Android 15): l'app messa "in pausa" dall'utente

    /** Per quanto tempo si dice che Pactum era stato chiuso: due giorni. */
    const val VALIDITA_MS = 48L * 60 * 60 * 1000

    /** Cresce a ogni «Ho capito»: chi mostra la riga la rilegge. */
    val viste = androidx.compose.runtime.mutableIntStateOf(0)

    private const val FILE = "fermate"
    private const val VISTA_FINO = "vista_fino"

    /** L'ultima chiusura che Android ricorda per questa app, o null (Android vecchio, nessuna). */
    fun ultima(context: Context): Fermata? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val gestore = context.getSystemService(ActivityManager::class.java) ?: return null
        return runCatching {
            gestore.getHistoricalProcessExitReasons(context.packageName, 0, 1)
                .firstOrNull()
                ?.let { Fermata(it.timestamp, it.reason, it.description) }
        }.getOrNull()
    }

    /**
     * Logica pura: la chiusura da dire al genitore. Solo quelle "del tutto" (che
     * fermano anche gli avvisi), degli ultimi due giorni, non già viste.
     */
    fun daDire(fermata: Fermata?, adesso: Long, vistaFino: Long): Fermata? {
        if (fermata == null) return null
        if (fermata.motivo != UTENTE && fermata.motivo != UTENTE_FERMATA) return null
        if (adesso - fermata.quando > VALIDITA_MS || fermata.quando <= vistaFino) return null
        return fermata
    }

    fun vistaFino(context: Context): Long =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getLong(VISTA_FINO, 0L)

    /** «Ho capito»: questa chiusura non si dice più. */
    fun segnaVista(context: Context, fermata: Fermata) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putLong(VISTA_FINO, fermata.quando).apply()
        viste.intValue++
    }

    /** Il motivo in una parola, per i registri del server. */
    fun parola(motivo: Int): String = when (motivo) {
        1 -> "uscita"
        2 -> "segnale"
        3 -> "memoria"
        4 -> "crash"
        5 -> "crash-nativo"
        6 -> "anr"
        7 -> "avvio-fallito"
        8 -> "permesso"
        9 -> "risorse"
        UTENTE -> "utente"
        UTENTE_FERMATA -> "utente-pausa"
        12 -> "dipendenza"
        13 -> "altro"
        14 -> "congelata"
        15 -> "aggiornamento"
        16 -> "aggiornamento-pacchetto"
        else -> "motivo-$motivo"
    }
}

/**
 * (0.25) Come la vedetta si fa riconoscere nei registri del server: un numero a caso di
 * questo telefono (niente di personale), la versione dell'app e, al primo giro dopo una
 * ripartenza del processo, perché Pactum si era fermato. Il server ignora questi
 * parametri (contratto v4.3): servono solo a leggere i suoi registri.
 */
object Diagnosi {
    private const val FILE = "diagnosi"
    private const val ID = "id"

    @Volatile
    private var primoGiro = true

    private fun id(context: Context): String {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs.getString(ID, null)?.let { return it }
        val nuovo = String.format(Locale.ROOT, "%06x", Random.nextInt(0x1000000))
        prefs.edit().putString(ID, nuovo).apply()
        return nuovo
    }

    /** I parametri da aggiungere alla richiesta delle notifiche (senza "?" né "&" iniziali). */
    fun parametri(context: Context, versione: Int): String {
        val base = "app=$versione&tel=${id(context)}"
        if (!primoGiro) return base
        primoGiro = false
        val fermata = Fermate.ultima(context) ?: return "$base&avvio=nuovo"
        val minuti = (System.currentTimeMillis() - fermata.quando) / 60_000
        return "$base&avvio=${Fermate.parola(fermata.motivo)}&da_min=$minuti"
    }
}
