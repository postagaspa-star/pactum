package eu.stgm.pactum.figlio.sessione

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.autofill.AutofillManager
import android.view.inputmethod.InputMethodManager

/**
 * (0.11) Le app che la barriera non copre MAI (contratto v3.5, "Sempre
 * usabili"): Pactum (così "Termina la sessione" si raggiunge sempre), la
 * schermata Home scelta, le tastiere attive, l'interfaccia di sistema, il
 * Telefono, la schermata della chiamata e le emergenze, le Impostazioni. In
 * più i pezzi di sistema che servono a quelle: le finestre dei permessi,
 * l'installazione di un aggiornamento di Pactum, il menu "Condividi", gli
 * avvisi di emergenza, il menu della SIM e il servizio che compila le
 * password. E quello che serve alle app della sessione per i compiti: la
 * fotocamera di sistema, la scelta dei file e quella delle foto.
 * Nient'altro: un'app fuori dalla lista si copre anche se l'ha aperta un'app
 * della sessione. Nemmeno l'assistente: la sua finestra non è la schermata
 * di un'app (non si copre comunque), mentre l'app Google intera, che cerca e
 * apre il web, si copre come le altre se non è nella lista. Il loro tempo
 * conta come sempre: qui si decide solo cosa non si copre.
 *
 * Ogni domanda al sistema è a parte: se una non risponde, le altre restano.
 */
object SempreUsabili {

    /** Quelli che si conoscono per nome, su qualsiasi telefono. */
    val FISSE: Set<String> = setOf(
        "eu.stgm.pactum.figlio",
        // L'interfaccia di sistema e i pezzi del sistema stesso.
        "android",
        "com.android.systemui",
        "com.android.intentresolver",
        // Le Impostazioni, le finestre dei permessi, l'installazione delle app.
        "com.android.settings",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        // Il Telefono, la schermata della chiamata, il menu della SIM, le emergenze.
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.android.incallui",
        "com.samsung.android.incallui",
        "com.android.stk",
        "com.android.stk2",
        "com.android.emergency",
        "com.google.android.apps.safetyhub",
        "com.samsung.android.emergency",
        "com.android.cellbroadcastreceiver",
        "com.android.cellbroadcastreceiver.module",
        "com.google.android.cellbroadcastreceiver",
        // La scelta dei file del sistema (allegare un documento ai compiti).
        "com.android.documentsui",
        "com.google.android.documentsui",
        // La scelta delle foto del sistema (sta nel fornitore dei media).
        "com.android.providers.media",
        "com.android.providers.media.module",
        "com.google.android.providers.media.module",
    )

    /**
     * Le schermate Home da non coprire (logica pura): quella scelta. Se non ce
     * n'è una scelta (Android chiede ogni volta, [predefinita] è la finestra di
     * scelta "android" o non si sa), tutte quelle installate: "Esci" deve
     * portare in un posto libero, qualunque si scelga.
     */
    fun homeDaUsare(predefinita: String?, candidate: Collection<String>): Set<String> =
        if (predefinita.isNullOrBlank() || predefinita == "android") candidate.toSet() else setOf(predefinita)

    /**
     * La fotocamera da non coprire (logica pura): quella predefinita; se non
     * ce n'è una scelta ([predefinita] = la finestra di scelta "android", o non
     * si sa), tutte le [candidate]. Solo fotocamere di sistema ([diSistema]):
     * un'app installata dal ragazzo che si presenta come fotocamera si copre.
     */
    fun fotocamereDaUsare(predefinita: String?, candidate: Collection<String>, diSistema: (String) -> Boolean): Set<String> {
        val scelte = if (predefinita.isNullOrBlank() || predefinita == "android") candidate else listOf(predefinita)
        return scelte.filter { it.isNotBlank() && it != "android" && diSistema(it) }.toSet()
    }

    fun leggi(context: Context): Set<String> {
        val app = context.applicationContext
        val pm = app.packageManager
        val insieme = HashSet(FISSE)
        insieme += app.packageName
        // La schermata Home scelta (tutte, solo se non ce n'è una scelta).
        prova {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val predefinita = try {
                pacchettoPredefinito(pm, home)
            } catch (e: Exception) {
                null
            }
            val candidate = if (predefinita == null || predefinita == "android") pacchettiPer(pm, home) else emptyList()
            insieme += homeDaUsare(predefinita, candidate)
        }
        // Il Telefono scelto e quello di sistema.
        prova {
            app.getSystemService(TelecomManager::class.java)?.let { telecom ->
                telecom.defaultDialerPackage?.let { insieme += it }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) telecom.systemDialerPackage?.let { insieme += it }
            }
        }
        prova { pacchettoPredefinito(pm, Intent(Intent.ACTION_DIAL))?.let { insieme += it } }
        // Le tastiere attive (non tutte quelle installate), e quella in uso.
        prova {
            app.getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.forEach { insieme += it.packageName }
        }
        prova { pacchettoDaComponente(Settings.Secure.getString(app.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD))?.let { insieme += it } }
        // La fotocamera di sistema: quella che un'app chiama per una foto, e
        // quella del tasto rapido.
        for (azione in listOf(MediaStore.ACTION_IMAGE_CAPTURE, MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) {
            prova {
                val intent = Intent(azione)
                val predefinita = try {
                    pacchettoPredefinito(pm, intent)
                } catch (e: Exception) {
                    null
                }
                val candidate = if (predefinita == null || predefinita == "android") pacchettiPer(pm, intent) else emptyList()
                insieme += fotocamereDaUsare(predefinita, candidate) { diSistema(pm, it) }
            }
        }
        // Il servizio che compila password e moduli.
        prova {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                app.getSystemService(AutofillManager::class.java)?.autofillServiceComponentName?.packageName?.let { insieme += it }
            }
        }
        prova { pacchettoDaComponente(Settings.Secure.getString(app.contentResolver, "autofill_service"))?.let { insieme += it } }
        // Le Impostazioni di questo telefono (di solito com.android.settings).
        prova { pacchettoPredefinito(pm, Intent(Settings.ACTION_SETTINGS))?.let { insieme += it } }
        insieme.remove("")
        return insieme
    }

    /** "pacchetto/classe" (come li scrivono le Impostazioni) → il pacchetto. */
    fun pacchettoDaComponente(testo: String?): String? =
        testo?.substringBefore('/')?.trim()?.takeIf { it.isNotEmpty() }

    private inline fun prova(azione: () -> Unit) {
        try {
            azione()
        } catch (e: Exception) {
            // quella domanda non ha risposto: restano le altre
        }
    }

    /** Installata con il telefono (anche se aggiornata dopo). Se non si sa: no. */
    private fun diSistema(pm: PackageManager, pacchetto: String): Boolean = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(pacchetto, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(pacchetto, 0)
        }
        (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
    } catch (e: Exception) {
        false
    }

    private fun pacchettiPer(pm: PackageManager, intent: Intent): List<String> {
        val trovati = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        return trovati.mapNotNull { it.activityInfo?.packageName }
    }

    private fun pacchettoPredefinito(pm: PackageManager, intent: Intent): String? {
        val risolto = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        return risolto?.activityInfo?.packageName
    }
}
