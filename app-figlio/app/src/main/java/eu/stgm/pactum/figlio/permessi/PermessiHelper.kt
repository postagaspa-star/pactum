package eu.stgm.pactum.figlio.permessi

import android.Manifest
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

object PermessiHelper {

    /** Permesso speciale "Accesso ai dati di utilizzo", via AppOpsManager. */
    fun haAccessoUso(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
            )
        }
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    fun haEsenzioneBatteria(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun haPermessoNotifiche(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

    fun intentAccessoUso(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    fun intentEsenzioneBatteria(context: Context): Intent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )

    /** Pagina "Info app": passaggio obbligato per sbloccare le impostazioni con limitazioni. */
    fun intentInfoApp(context: Context): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    )

    fun intentImpostazioniNotifiche(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    /**
     * (0.9) "Mostra sopra le altre app": l'unico modo in cui Android (10+)
     * lascia aprire l'avviso a tutto schermo mentre si usa un'altra app.
     */
    fun puoMostrareSopra(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * La schermata di sistema per concederlo. Da Android 11 il pacchetto viene
     * ignorato e si apre l'elenco delle app: per questo i testi dicono di
     * cercare Pactum nell'elenco.
     */
    fun intentMostraSopra(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )

    /**
     * (0.9) Apre una schermata di sistema. Se il telefono non ce l'ha (marche
     * che la tolgono o la spostano), la pagina dell'app in "Info app": da lì
     * si arriva a tutto. Mai un crash per un tocco su "Apri impostazioni".
     */
    fun apri(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            try {
                context.startActivity(intentInfoApp(context))
            } catch (e: ActivityNotFoundException) {
                // nemmeno Info app: non resta niente da aprire
            }
        }
    }
}

data class StatoPermessi(
    val accessoUso: Boolean,
    val esenzioneBatteria: Boolean,
    val notifiche: Boolean,
    val mostraSopra: Boolean = false,
) {
    companion object {
        fun leggi(context: Context) = StatoPermessi(
            accessoUso = PermessiHelper.haAccessoUso(context),
            esenzioneBatteria = PermessiHelper.haEsenzioneBatteria(context),
            notifiche = PermessiHelper.haPermessoNotifiche(context),
            mostraSopra = PermessiHelper.puoMostrareSopra(context),
        )
    }
}
