package eu.stgm.pactum.figlio.diagnostica

import android.os.SystemClock
import android.util.Log

/**
 * (0.16) Le righe di tempo per capire perché l'avviso a tutto schermo arriva
 * tardi (richiesta di Andrea): si leggono con `adb logcat -s PactumTempi`.
 * SOLO tempi (ms, sull'orologio che non si sposta) e id di regola: mai nomi di
 * app, testi o altri dati personali.
 */
object TempiLog {

    const val TAG = "PactumTempi"

    /** L'istante di adesso, da sottrarre dopo con [da]. */
    fun ora(): Long = SystemClock.elapsedRealtime()

    /** Quanti ms sono passati da [inizio] (un valore di [ora]). */
    fun da(inizio: Long): Long = SystemClock.elapsedRealtime() - inizio

    /** Una riga: "<evento> <ms> ms [dettagli]". Mai un errore se il log non c'è (test). */
    fun riga(evento: String, ms: Long? = null, dettagli: String = "") {
        runCatching {
            Log.i(TAG, buildString {
                append(evento)
                if (ms != null) append(' ').append(ms).append(" ms")
                if (dettagli.isNotEmpty()) append(' ').append(dettagli)
            })
        }
    }
}
