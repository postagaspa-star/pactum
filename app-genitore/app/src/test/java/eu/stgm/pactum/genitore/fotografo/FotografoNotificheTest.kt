package eu.stgm.pactum.genitore.fotografo

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.test.core.app.ApplicationProvider
import eu.stgm.pactum.genitore.fotografo.DatiFinti.PC_LUCA
import eu.stgm.pactum.genitore.fotografo.DatiFinti.TEL_LUCA
import eu.stgm.pactum.genitore.fotografo.DatiFinti.TEL_SARA
import eu.stgm.pactum.genitore.sync.Vedetta
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.TimeZone

/**
 * Le NOTIFICHE DI SISTEMA (la tendina di Android): un giro vero della vedetta
 * (Vedetta.giro, lo stesso codice del servizio) contro il server finto, poi ogni
 * notifica alzata viene disegnata col modello di Android (RemoteViews del
 * sistema, versione chiusa ed espansa). La tendina di un telefono vero
 * ha la sua grafica: qui conta COSA dice ogni notifica e quanto ci sta.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [35], application = AppFotografo::class)
class FotografoNotificheTest {

    private val server = ServerFinto()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val cartella = File(System.getProperty("fotografo.cartella") ?: "build/fotografo").also { it.mkdirs() }
    private val elenco = mutableListOf<String>()
    private var esitoGiro = ""

    @Before
    fun prima() {
        server.avvia()
        server.scenario = DatiFinti.scenarioNormale()
    }

    @After
    fun dopo() {
        File(cartella, "elenco.tsv").appendText(elenco.joinToString(""), Charsets.UTF_8)
        server.ferma()
    }

    @Test
    fun tendina() {
        listOf(360 to false, 411 to false, 360 to true).forEach { (larghezza, grande) ->
            Locale.setDefault(Locale.ITALY)
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Rome"))
            RuntimeEnvironment.setQualifiers("it-rIT-w${larghezza}dp-h800dp-port-notnight-xhdpi")
            RuntimeEnvironment.setFontScale(if (grande) 1.3f else 1.0f)
            val nome = "11-notifiche-di-sistema_chiaro_$larghezza" + if (grande) "_testo-grande" else ""
            val notifiche = giroDellaVedetta()
            val chiuse = notifiche.mapNotNull { disegna(it, espansa = false, larghezza) }
            val aperte = notifiche.mapNotNull { disegna(it, espansa = true, larghezza) }
            // A gruppi di 6, così ogni immagine resta leggibile (come una schermata di tendina).
            val testo = if (grande) "grande" else "normale"
            chiuse.chunked(6).forEachIndexed { i, gruppo ->
                val file = "$nome-chiuse-${i + 1}.png"
                salvaTendina(gruppo, larghezza, file)
                val note = if (i == 0) "giro della vedetta: $esitoGiro; ${notifiche.size} notifiche in tutto" else ""
                elenco += "$file\tNotifiche di sistema (tendina) alzate da un giro vero della vedetta, versione chiusa, gruppo ${i + 1} — $larghezza dp, testo $testo\t$note\n"
            }
            aperte.chunked(6).forEachIndexed { i, gruppo ->
                val file = "$nome-espanse-${i + 1}.png"
                salvaTendina(gruppo, larghezza, file)
                elenco += "$file\tLe stesse notifiche di sistema espanse, gruppo ${i + 1} — $larghezza dp, testo $testo\t\n"
            }
        }
    }

    /** Un giro completo della vedetta, come quello del servizio: restituisce le notifiche alzate. */
    private fun giroDellaVedetta(): List<Notification> {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val gestore = app.getSystemService(NotificationManager::class.java)
        gestore.cancelAll()
        // Ogni giro come il primo del servizio: la vedetta dimentica quello che ricordava in memoria
        // (così anche il riassunto della sera e l'avviso di silenzio ripartono ogni volta).
        Vedetta::class.java.getDeclaredField("stato").apply { isAccessible = true }.set(null, null)
        val ds = Fotografo.preferenze(app)
        // Prima di questo giro: tutte le notifiche già lette (niente riassunto), il
        // telefono di Sara e il computer di Luca ancora in contatto (così ora parte
        // l'avviso del cambio), e il riassunto della sera già all'ora giusta.
        val silenziPrima = """{"perDispositivo":{""" +
            """"$TEL_LUCA":{"silente":false,"ultimoBattito":"${DatiFinti.minutiFa(4)}"},""" +
            """"$PC_LUCA":{"silente":false,"ultimoBattito":"${DatiFinti.oreFa(20)}"},""" +
            """"$TEL_SARA":{"silente":false,"ultimoBattito":"${DatiFinti.oreFa(3)}"}}}"""
        runBlocking {
            ds.edit { p ->
                p.clear()
                p[stringPreferencesKey("server_url")] = server.indirizzo
                p[stringPreferencesKey("token")] = "codice-finto-del-genitore"
                p[stringSetPreferencesKey("notifiche_avvisate")] = setOf("1")
                p[stringPreferencesKey("silenzi_noti")] = silenziPrima
                p[booleanPreferencesKey("digest_attivo")] = true
                p[intPreferencesKey("digest_ora")] = 0
            }
            // Robolectric non dà capacità alla rete attiva: la vedetta crederebbe di essere offline.
            val connettivita = app.getSystemService(ConnectivityManager::class.java)
            val capacita = ShadowNetworkCapabilities.newInstance()
            shadowOf(capacita).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            connettivita.activeNetwork?.let { shadowOf(connettivita).setNetworkCapabilities(it, capacita) }
            esitoGiro = Vedetta(app).giro().toString()
        }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return shadowOf(gestore).allNotifications.sortedByDescending { it.`when` }
    }

    /** La notifica col modello di sistema, larga come nella tendina (16 dp di margine per lato). */
    private fun disegna(notifica: Notification, espansa: Boolean, larghezzaDp: Int): Bitmap? = try {
        val contesto = ContextThemeWrapper(app, android.R.style.Theme_DeviceDefault_Light)
        // L'ora della notifica non si disegna: l'orologio di Robolectric non è quello vero.
        val costruttore = Notification.Builder.recoverBuilder(contesto, notifica).setShowWhen(false)
        val remote = if (espansa) costruttore.createBigContentView() else costruttore.createContentView()
        val contenitore = FrameLayout(contesto)
        val vista: View = remote.apply(contesto, contenitore)
        contenitore.addView(vista, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val densita = app.resources.displayMetrics.density
        val larghezzaPx = ((larghezzaDp - 32) * densita).toInt()
        contenitore.measure(
            View.MeasureSpec.makeMeasureSpec(larghezzaPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((600 * densita).toInt(), View.MeasureSpec.AT_MOST),
        )
        contenitore.layout(0, 0, contenitore.measuredWidth, contenitore.measuredHeight)
        val bitmap = Bitmap.createBitmap(contenitore.measuredWidth, maxOf(1, contenitore.measuredHeight), Bitmap.Config.ARGB_8888)
        contenitore.draw(Canvas(bitmap))
        bitmap
    } catch (e: Throwable) {
        elenco += "ERRORE\tNotifica non disegnata: ${e::class.simpleName}: ${e.message?.lineSequence()?.firstOrNull()}\t\n"
        null
    }

    /** Le notifiche una sotto l'altra, su fondo grigio come la tendina, in schede bianche arrotondate. */
    private fun salvaTendina(schede: List<Bitmap>, larghezzaDp: Int, file: String) {
        if (schede.isEmpty()) return
        val densita = app.resources.displayMetrics.density
        val margine = (16 * densita).toInt()
        val spazio = (8 * densita).toInt()
        val larghezza = (larghezzaDp * densita).toInt()
        val altezza = margine * 2 + schede.sumOf { it.height } + spazio * (schede.size - 1)
        val tela = Bitmap.createBitmap(larghezza, altezza, Bitmap.Config.ARGB_8888)
        val c = Canvas(tela)
        c.drawColor(0xFFDDE3EA.toInt())
        val fondo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        var y = margine
        schede.forEach { scheda ->
            c.drawRoundRect(RectF(margine.toFloat(), y.toFloat(), (margine + scheda.width).toFloat(), (y + scheda.height).toFloat()), 20 * densita, 20 * densita, fondo)
            c.drawBitmap(scheda, margine.toFloat(), y.toFloat(), null)
            y += scheda.height + spazio
        }
        FileOutputStream(File(cartella, file)).use { tela.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
