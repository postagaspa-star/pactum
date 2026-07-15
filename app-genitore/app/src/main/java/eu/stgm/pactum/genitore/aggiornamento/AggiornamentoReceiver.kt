package eu.stgm.pactum.genitore.aggiornamento

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat

/**
 * Riceve gli esiti della sessione PackageInstaller lanciata dall'[Aggiornatore].
 *
 * Il solo caso che ci interessa è `STATUS_PENDING_USER_ACTION`: il sistema ci
 * consegna un intent da avviare per mostrare all'utente la conferma
 * d'installazione (e, se serve, la richiesta di "installa app sconosciute").
 * Successo e fallimento non richiedono nulla qui: la UI ha già detto che
 * l'aggiornamento è stato avviato, e un'installazione riuscita sostituisce
 * l'app da sola.
 */
class AggiornamentoReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val stato = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        if (stato != PackageInstaller.STATUS_PENDING_USER_ACTION) return

        val conferma = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
            ?: return
        // NEW_TASK: arriviamo da un broadcast (nessuna activity nostra in cima),
        // quindi il dialogo di conferma va avviato in un task proprio.
        conferma.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(conferma)
    }

    companion object {
        const val AZIONE = "eu.stgm.pactum.genitore.INSTALL_STATUS"
    }
}
