package eu.stgm.pactum.figlio.fotografo

import android.Manifest
import android.app.AppOpsManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.PowerManager
import android.os.Process
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import eu.stgm.pactum.figlio.dati.Dispositivo
import eu.stgm.pactum.figlio.dati.Figlio
import eu.stgm.pactum.figlio.dati.Identita
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.faccende.ArchivioBlocco
import eu.stgm.pactum.figlio.faccende.ArchivioCodaFoto
import eu.stgm.pactum.figlio.faccende.MemoriaBlocco
import eu.stgm.pactum.figlio.faccende.MemoriaCodaFoto
import eu.stgm.pactum.figlio.sessione.ArchivioSessioni
import eu.stgm.pactum.figlio.sessione.MemoriaSessioni
import eu.stgm.pactum.figlio.sessione.RichiestaTermine
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSettings

/**
 * Il telefono finto di Luca: le app installate (coi loro nomi veri), i
 * permessi, il collegamento al patto e gli archivi del processo (sessioni,
 * blocco dei lavori di casa, coda delle foto). Si rimette a zero prima di
 * ogni test: Robolectric tiene vivi gli oggetti fra un test e l'altro.
 */
object Mondo {

    const val SERVER = "https://pactum-casa.example.it"

    /** Le app del telefono di Luca: pacchetto → nome. */
    val APP: List<Pair<String, String>> = listOf(
        "com.instagram.android" to "Instagram",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.google.android.youtube" to "YouTube",
        "com.whatsapp" to "WhatsApp",
        "com.spaggiari.classevivastudenti" to "ClasseViva Studenti",
        "com.google.android.apps.classroom" to "Classroom",
        "com.spotify.music" to "Spotify",
        "com.android.chrome" to "Chrome",
        "com.supercell.brawlstars" to "Brawl Stars",
        "com.duolingo" to "Duolingo",
        "com.google.android.calculator" to "Calcolatrice",
        "com.google.android.apps.docs" to "Drive",
        "com.snapchat.android" to "Snapchat",
        "com.netflix.mediaclient" to "Netflix",
    )

    fun nome(pacchetto: String): String = APP.first { it.first == pacchetto }.second

    fun azzera(app: Application) {
        permessi(app)
        installaApp(app)
        runBlocking { dataStore(app).edit { it.clear() } }
        ArchivioSessioni.svuota(app)
        ArchivioSessioni.modifica(app) { MemoriaSessioni() }
        ArchivioBlocco.modifica(app) { MemoriaBlocco() }
        ArchivioCodaFoto.modifica(app) { MemoriaCodaFoto() to emptyList() }
        RichiestaTermine.consuma()
    }

    /** I permessi del telefono: di base tutti concessi. */
    fun permessi(
        app: Application,
        uso: Boolean = true,
        batteria: Boolean = true,
        notifiche: Boolean = true,
        sopra: Boolean = true,
    ) {
        val appOps = app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(appOps).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            app.packageName,
            if (uso) AppOpsManager.MODE_ALLOWED else AppOpsManager.MODE_IGNORED,
        )
        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(power).setIgnoringBatteryOptimizations(app.packageName, batteria)
        if (notifiche) {
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        }
        ShadowSettings.setCanDrawOverlays(sopra)
    }

    private fun installaApp(app: Application) {
        val pm = shadowOf(app.packageManager)
        for ((pacchetto, nome) in APP) {
            val info = PackageInfo().apply {
                packageName = pacchetto
                applicationInfo = ApplicationInfo().apply {
                    packageName = pacchetto
                    nonLocalizedLabel = nome
                    flags = 0
                    category = if (pacchetto == "com.supercell.brawlstars") ApplicationInfo.CATEGORY_GAME else ApplicationInfo.CATEGORY_UNDEFINED
                }
            }
            pm.installPackage(info)
            val componente = ComponentName(pacchetto, "$pacchetto.Avvio")
            pm.addActivityIfNotPresent(componente)
            pm.addIntentFilterForActivity(
                componente,
                IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
            )
        }
    }

    /** Collegato al patto come "Telefono di Luca", con "Cosa vedono" già letto. */
    fun collegato(app: Application, cosaVedeVista: Boolean = true) = runBlocking {
        val impostazioni = Impostazioni(app)
        impostazioni.salvaCollegamento(
            serverUrl = SERVER,
            token = "token-finto-di-luca",
            identita = Identita(
                Dispositivo(id = DatiFinti.QUESTO_TELEFONO, nome = "Telefono di Luca", tipo = "telefono"),
                Figlio(id = 7, nome = "Luca"),
            ),
            stessoDispositivo = false,
            stessoFiglio = false,
        )
        if (cosaVedeVista) impostazioni.registraCosaVedeVista()
        impostazioni.registraBattitoConsegnato(System.currentTimeMillis() - 7 * 60_000)
        impostazioni.salvaChiusuraSerale(true, 21 * 60 + 30)
    }

    /** Il DataStore delle Impostazioni (privato nel codice dell'app): solo per azzerarlo. */
    @Suppress("UNCHECKED_CAST")
    fun dataStore(context: Context): DataStore<Preferences> {
        val classe = Class.forName("eu.stgm.pactum.figlio.dati.ImpostazioniKt")
        val metodo = classe.declaredMethods.first {
            it.parameterTypes.size == 1 && it.parameterTypes[0] == Context::class.java &&
                DataStore::class.java.isAssignableFrom(it.returnType)
        }
        metodo.isAccessible = true
        return metodo.invoke(null, context) as DataStore<Preferences>
    }
}
