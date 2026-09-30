package eu.stgm.pactum.figlio.avviso

import android.content.Context
import android.media.AudioManager

/**
 * (0.9) Durante una chiamata l'avviso a tutto schermo non parte: coprirebbe
 * la chiamata. Resta la notifica. Lo dice il modo dell'audio (nessun permesso
 * serve per leggerlo).
 */
object Chiamata {

    // I modi di AudioManager in cui c'è una chiamata: telefonica o via app
    // (WhatsApp e simili), anche mentre squilla o viene filtrata. Alcuni sono
    // di API recenti: i numeri sono quelli del sistema.
    private val MODI_IN_CHIAMATA = setOf(
        AudioManager.MODE_RINGTONE,
        AudioManager.MODE_IN_CALL,
        AudioManager.MODE_IN_COMMUNICATION,
        4, // MODE_CALL_SCREENING (API 30)
        5, // MODE_CALL_REDIRECT (API 33)
        6, // MODE_COMMUNICATION_REDIRECT (API 33)
    )

    fun inCorso(modo: Int): Boolean = modo in MODI_IN_CHIAMATA

    fun inCorso(context: Context): Boolean =
        context.getSystemService(AudioManager::class.java)?.mode?.let { inCorso(it) } ?: false
}
