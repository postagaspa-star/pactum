package eu.stgm.pactum.figlio.faccende

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import kotlinx.serialization.Serializable

/**
 * (0.13) Un istante visto dal telefono: l'orologio a muro ([muro], che il
 * ragazzo può spostare dalle Impostazioni), quello che non si sposta
 * ([monotono] = elapsedRealtime, riparte da zero a ogni accensione) e quale
 * accensione è ([avvio] = il numero di accensioni del telefono, null se non
 * si sa).
 *
 * Il blocco delle faccende non si fida dell'orologio a muro: l'ordine delle
 * risposte del server e l'ora del server si contano sull'orologio che non si
 * sposta (MemoriaBlocco). Così mettere la data al 2030 e poi rimetterla
 * giusta non spegne il blocco, e un telefono avanti di un'ora non si blocca
 * un'ora prima.
 */
@Serializable
data class Istante(val muro: Long, val monotono: Long, val avvio: Int? = null) {
    /** Lo stesso giro di accensione di [altro]: i due [monotono] si possono confrontare. */
    fun stessaAccensione(altro: Istante): Boolean = avvio != null && avvio == altro.avvio
}

object Orologio {

    /** Il numero di accensioni: non cambia finché il processo vive (un riavvio lo uccide). */
    @Volatile
    private var avvio: Int? = null

    /** All'avvio del processo (PactumApp). Senza, l'accensione resta "non si sa". */
    fun inizializza(context: Context) {
        avvio = try {
            Settings.Global.getInt(context.applicationContext.contentResolver, Settings.Global.BOOT_COUNT, -1).takeIf { it >= 0 }
        } catch (e: Exception) {
            null
        }
    }

    fun adesso(): Istante = Istante(System.currentTimeMillis(), SystemClock.elapsedRealtime(), avvio)
}
