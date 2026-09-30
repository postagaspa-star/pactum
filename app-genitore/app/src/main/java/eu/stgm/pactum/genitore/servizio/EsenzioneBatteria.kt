package eu.stgm.pactum.genitore.servizio

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import eu.stgm.pactum.genitore.R

/**
 * (0.9) Le impostazioni di Android che decidono se gli avvisi arrivano in
 * tempo: l'esenzione dall'ottimizzazione della batteria (come la chiede l'app
 * del figlio, PermessiHelper), le notifiche dell'app e la sua pagina "Info app"
 * (da lì si arriva al risparmio batteria delle marche che ne hanno uno loro).
 *
 * Ogni apertura intercetta tutti gli errori: su certi telefoni una schermata di
 * sistema non esiste o non si lascia aprire, e l'app non deve chiudersi per
 * questo.
 */
object EsenzioneBatteria {

    fun concessa(context: Context): Boolean = try {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName)
            ?: false
    } catch (e: RuntimeException) {
        false
    }

    /**
     * Apre la domanda di Android ("Consentire all'app di essere sempre eseguita
     * in background?", da rispondere con «Consenti»). Se il telefono non ce
     * l'ha, l'elenco delle app della batteria, e un messaggio dice che cosa
     * cercare. BatteryLife: è la regola del Play Store su questa domanda;
     * Pactum si installa a mano, e l'app del figlio la fa già.
     */
    @SuppressLint("BatteryLife")
    fun chiedi(context: Context) {
        val domanda = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
        if (apri(context, domanda)) return
        if (apri(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
            dici(context, context.getString(R.string.batteria_cerca_nell_elenco, context.getString(R.string.nome_app)))
            return
        }
        if (apriInfoApp(context)) {
            dici(context, context.getString(R.string.batteria_cerca_in_info_app))
        }
    }

    /** Le notifiche dell'app nelle impostazioni di Android, per riaccendere gli avvisi. */
    fun apriNotifiche(context: Context) {
        val notifiche = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        if (!apri(context, notifiche)) apriInfoApp(context)
    }

    /** La pagina "Info app" di Pactum Genitore: batteria, notifiche, avvio automatico. */
    fun apriInfoApp(context: Context): Boolean = apri(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
    )

    /** false = la schermata non c'è, o Android non la lascia aprire. */
    private fun apri(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: RuntimeException) {
        // ActivityNotFoundException, SecurityException e simili
        false
    }

    private fun dici(context: Context, testo: String) {
        try {
            Toast.makeText(context, testo, Toast.LENGTH_LONG).show()
        } catch (e: RuntimeException) {
            // un messaggio in meno, non un crash
        }
    }
}
