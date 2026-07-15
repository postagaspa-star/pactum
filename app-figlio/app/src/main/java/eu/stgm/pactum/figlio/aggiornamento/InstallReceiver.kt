package eu.stgm.pactum.figlio.aggiornamento

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import eu.stgm.pactum.figlio.notifiche.AvvisiLocali

/**
 * L'esito della sessione di PackageInstaller (auto-aggiornamento, tappa 6).
 *
 * Il caso che conta è STATUS_PENDING_USER_ACTION: al primo aggiornamento (e
 * finché Android non concede l'installazione silenziosa) il sistema chiede la
 * conferma dell'utente con una propria Activity. Poiché il worker gira in
 * background — dove l'avvio diretto di un'Activity è soppresso — la conferma si
 * offre come NOTIFICA gentile: toccarla apre il dialogo di sistema. A conferma
 * arrivata (STATUS_SUCCESS) la notifica sparisce; sugli altri esiti si lascia
 * stare (si potrà sempre aggiornare a mano dalla pagina /scarica).
 */
class InstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AZIONE) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val conferma = intentConferma(intent) ?: return
                conferma.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                AvvisiLocali.avvisaAggiornamento(context, conferma)
            }
            PackageInstaller.STATUS_SUCCESS -> AvvisiLocali.cancellaAggiornamento(context)
            else -> Unit // fallito o annullato: nessun avviso, resta la pagina /scarica
        }
    }

    private fun intentConferma(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    companion object {
        const val AZIONE = "eu.stgm.pactum.figlio.INSTALLA_AGGIORNAMENTO"
    }
}
