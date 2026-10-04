package eu.stgm.pactum.genitore.fotografo

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.IdlingRegistry
import androidx.test.espresso.IdlingResource
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import eu.stgm.pactum.genitore.MainActivity
import kotlinx.coroutines.runBlocking
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.Locale
import java.util.TimeZone

/** Il tema del telefono. L'app del genitore resta chiara anche col telefono scuro (scelta di Andrea). */
enum class Tema(val nome: String, val qualificatore: String) {
    CHIARO("chiaro", "notnight"),
    SCURO("scuro", "night"),
}

/** Una misura di telefono: tema, larghezza (360 o 411 dp) e testo normale o grande (1,3). */
data class Variante(val tema: Tema, val larghezza: Int, val testoGrande: Boolean = false) {
    val altezza: Int get() = if (larghezza <= 360) 800 else 914
    val nome: String get() = "${tema.nome}_$larghezza" + if (testoGrande) "_testo-grande" else ""
    val descrizione: String
        get() = "tema ${tema.nome}, ${larghezza}×$altezza dp, testo ${if (testoGrande) "grande (1,3)" else "normale"}"

    fun qualificatori(): String = "it-rIT-w${larghezza}dp-h${altezza}dp-port-${tema.qualificatore}-xhdpi"

    companion object {
        val CHIARO_360 = Variante(Tema.CHIARO, 360)
        val CHIARO_411 = Variante(Tema.CHIARO, 411)
        val SCURO_360 = Variante(Tema.SCURO, 360)
        val SCURO_411 = Variante(Tema.SCURO, 411)
        val CHIARO_360_GRANDE = Variante(Tema.CHIARO, 360, true)
        val CHIARO_411_GRANDE = Variante(Tema.CHIARO, 411, true)
        val SCURO_360_GRANDE = Variante(Tema.SCURO, 360, true)
        val SCURO_411_GRANDE = Variante(Tema.SCURO, 411, true)

        /** Le otto combinazioni: per lo stato principale di ogni schermata. */
        val TUTTE = listOf(
            CHIARO_360, CHIARO_411, SCURO_360, SCURO_411,
            CHIARO_360_GRANDE, CHIARO_411_GRANDE, SCURO_360_GRANDE, SCURO_411_GRANDE,
        )

        /** Per gli stati secondari e i dialoghi: chiaro piccolo, scuro grande, testo grande sul piccolo. */
        val RIDOTTE = listOf(CHIARO_360, SCURO_411, CHIARO_360_GRANDE)
    }
}

/** Come preparare il telefono finto prima di aprire l'app. */
data class Preparazione(
    /** Indirizzo e codice salvati (false = prima apertura). */
    val configurato: Boolean = true,
    val introChiusa: Boolean = true,
    val figlioScelto: Long? = null,
    /** Permesso delle notifiche dato. */
    val notifiche: Boolean = true,
    /** Le due domande della prima apertura (notifiche, batteria) già fatte. */
    val domandeFatte: Boolean = true,
    val esenteBatteria: Boolean = true,
    /** Quanti minuti fa la vedetta ha controllato l'ultima volta (null = mai). */
    val ultimoControlloMinutiFa: Long? = 2,
)

/**
 * Il fotografo: apre l'app vera (MainActivity, ViewModel veri) contro il
 * [ServerFinto], aspetta che la schermata sia pronta, fa i gesti e scatta.
 * Ogni foto finisce in [cartella] e in [elenco] (per l'indice).
 */
class Fotografo(private val compose: ComposeTestRule, val server: ServerFinto) {

    val cartella: File = File(System.getProperty("fotografo.cartella") ?: "build/fotografo").also { it.mkdirs() }
    val app: Application get() = ApplicationProvider.getApplicationContext()

    data class Foto(val file: String, val descrizione: String, val note: String = "")

    val elenco = mutableListOf<Foto>()
    val falliti = mutableListOf<String>()

    /**
     * Robolectric non "disegna" le finestre dei dialoghi, e Compose misura un
     * dialogo che cambia solo al disegno: la prova aspetterebbe per sempre una
     * misura in sospeso ("Compose did not get idle"). Questo segnaposto di Espresso,
     * interrogato a ogni giro d'attesa, misura a mano tutte le radici di Compose.
     * Sull'app vera il problema non esiste.
     */
    private val misuratore = object : IdlingResource {
        override fun getName() = "fotografo-misura-i-dialoghi"
        override fun isIdleNow(): Boolean {
            try {
                misuraTutto()
            } catch (_: Throwable) {
            }
            return true
        }
        override fun registerIdleTransitionCallback(callback: IdlingResource.ResourceCallback?) {}
    }

    init {
        IdlingRegistry.getInstance().register(misuratore)
    }

    /** Da chiamare alla fine della prova. */
    fun chiudi() {
        IdlingRegistry.getInstance().unregister(misuratore)
    }

    fun s(@StringRes id: Int, vararg argomenti: Any): String = app.getString(id, *argomenti)

    // --- Il telefono -------------------------------------------------------------------

    private fun prepara(variante: Variante, preparazione: Preparazione) {
        Locale.setDefault(Locale.ITALY)
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Rome"))
        RuntimeEnvironment.setQualifiers(variante.qualificatori())
        RuntimeEnvironment.setFontScale(if (variante.testoGrande) 1.3f else 1.0f)
        val ombra = shadowOf(app)
        if (preparazione.notifiche) {
            ombra.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            ombra.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        }
        shadowOf(app.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(app.packageName, preparazione.esenteBatteria)
        val ds = preferenze(app)
        runBlocking {
            ds.edit { p ->
                p.clear()
                if (preparazione.configurato) {
                    p[stringPreferencesKey("server_url")] = server.indirizzo
                    p[stringPreferencesKey("token")] = "codice-finto-del-genitore"
                }
                if (preparazione.domandeFatte) {
                    p[booleanPreferencesKey("richiesta_notifiche_fatta")] = true
                    p[booleanPreferencesKey("richiesta_batteria_fatta")] = true
                }
                if (preparazione.introChiusa) p[booleanPreferencesKey("intro_chiusa")] = true
                preparazione.figlioScelto?.let { p[longPreferencesKey("figlio_scelto")] = it }
                preparazione.ultimoControlloMinutiFa?.let {
                    val ts = System.currentTimeMillis() - it * 60_000
                    p[longPreferencesKey("ultimo_controllo_avvisi")] = ts
                    p[longPreferencesKey("ultima_verifica_ok")] = ts
                }
            }
        }
    }

    // --- Lo scatto ---------------------------------------------------------------------

    /**
     * Apre l'app su [destinazione] (le stesse delle notifiche: "tempo", "turno",
     * "notifiche", "faccende", …), aspetta [pronto], fa [gesti], aspetta [dopo] e
     * scatta [nome]_[variante].png. Con [pagine] scorre la schermata e scatta una
     * foto per pagina (-p1, -p2, …): quante ne servono dice quanto si scorre.
     */
    fun scatta(
        nome: String,
        descrizione: String,
        variante: Variante,
        scenario: Scenario = DatiFinti.scenarioNormale(),
        destinazione: String? = null,
        faccenda: Long? = null,
        preparazione: Preparazione = Preparazione(),
        pagine: Boolean = false,
        /** Le pagine si scorrono DENTRO il dialogo aperto (non la schermata sotto). */
        pagineDialogo: Boolean = false,
        pronto: Fotografo.() -> Boolean,
        gesti: Fotografo.() -> Unit = {},
        dopo: (Fotografo.() -> Boolean)? = null,
    ) {
        val base = "${nome}_${variante.nome}"
        // -Pfotografo.solo=<regex>: rifà solo le foto che combaciano.
        val solo = System.getProperty("fotografo.solo")?.takeIf { it.isNotBlank() }
        if (solo != null && !Regex(solo).containsMatchIn(base)) return
        var attivita: ActivityScenario<MainActivity>? = null
        try {
            server.scenario = scenario
            prepara(variante, preparazione)
            val intent = Intent(app, MainActivity::class.java)
            destinazione?.let { intent.putExtra(MainActivity.EXTRA_DESTINAZIONE, it) }
            faccenda?.let { intent.putExtra(MainActivity.EXTRA_FACCENDA, it) }
            attivita = ActivityScenario.launch(intent)
            aspetta("pronto $base") { pronto() }
            gesti()
            dopo?.let { condizione -> aspetta("dopo i gesti $base") { condizione() } }
            assesta()
            if (pagine || pagineDialogo) scattaPagine(base, descrizione, variante, pagineDialogo) else scattaUna("$base.png", "$descrizione — ${variante.descrizione}")
        } catch (e: Throwable) {
            falliti += "$base: ${e::class.simpleName}: ${e.message?.lineSequence()?.firstOrNull()}"
            File(cartella, "errori-completi.txt").appendText("== $base\n${e.stackTraceToString().take(6000)}\n", Charsets.UTF_8)
            // Anche lo stato sbagliato è un'informazione: si scatta lo stesso, col nome che lo dice.
            try {
                scattaUna("ERRORE_$base.png", "NON RIUSCITA: $descrizione — ${variante.descrizione}")
            } catch (_: Throwable) {
            }
        } finally {
            try {
                attivita?.close()
            } catch (_: Throwable) {
            }
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun scattaUna(file: String, descrizione: String, note: String = "") {
        misuraTutto()
        conFinestreInOrdine { captureScreenRoboImage(File(cartella, file).absolutePath) }
        elenco += Foto(file, descrizione, note)
    }

    /**
     * Roborazzi mette le finestre una sopra l'altra nell'ordine in cui le elenca
     * Espresso (dalla più nuova alla più vecchia): con DUE dialoghi aperti (la foto e,
     * sopra, "Bocciare…?") il più vecchio finirebbe sopra. Per lo scatto si gira
     * l'elenco interno delle finestre, così la più nuova resta sopra come sul telefono.
     */
    private fun conFinestreInOrdine(scatto: () -> Unit) {
        val elenchi = try {
            val wmg = Class.forName("android.view.WindowManagerGlobal")
            val istanza = wmg.getMethod("getInstance").invoke(null)
            listOf("mViews", "mRoots", "mParams").map { nome ->
                @Suppress("UNCHECKED_CAST")
                wmg.getDeclaredField(nome).apply { isAccessible = true }.get(istanza) as MutableList<Any?>
            }
        } catch (e: ReflectiveOperationException) {
            null
        }
        val daGirare = elenchi != null && elenchi[0].size >= 3
        if (daGirare) elenchi!!.forEach { it.reverse() }
        try {
            scatto()
        } finally {
            if (daGirare) elenchi!!.forEach { it.reverse() }
        }
    }

    /** Scorre la colonna principale una pagina alla volta (85% dell'altezza), fino in fondo. */
    private fun scattaPagine(base: String, descrizione: String, variante: Variante, dialogo: Boolean = false) {
        val colonna = if (dialogo) colonnaDialogo() else colonnaPrincipale()
        if (colonna == null) {
            scattaUna("$base.png", "$descrizione — ${variante.descrizione}", "non scorre")
            return
        }
        val altezzaPx = colonna.fetchSemanticsNode().boundsInRoot.height
        val densita = app.resources.displayMetrics.density
        val passo = altezzaPx * 0.85f
        var pagina = 1
        while (true) {
            val nodo = colonna.fetchSemanticsNode()
            val intervallo = nodo.config[SemanticsProperties.VerticalScrollAxisRange]
            val fine = intervallo.value() >= intervallo.maxValue() - 1f
            val nota = "colonna alta ${(altezzaPx / densita).toInt()} dp" +
                if (pagina == 1) ", contenuto oltre lo schermo ≈ ${(intervallo.maxValue() / densita).toInt()} dp" else ""
            scattaUna("$base-p$pagina.png", "$descrizione — ${variante.descrizione} — pagina $pagina", nota)
            if (fine || pagina >= 12) break
            misuraTutto()
            colonna.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, passo) }
            assesta()
            pagina++
        }
    }

    /** La colonna che scorre più alta sullo schermo (la pagina, non un elenco orizzontale). */
    /** La colonna che scorre dentro il dialogo aperto. */
    fun colonnaDialogo(): SemanticsNodeInteraction? {
        val trovati = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and hasAnyAncestor(isDialog()),
        )
        return if (trovati.fetchSemanticsNodes().isEmpty()) null else trovati[0]
    }

    fun colonnaPrincipale(): SemanticsNodeInteraction? {
        val trovati = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        val nodi = trovati.fetchSemanticsNodes()
        if (nodi.isEmpty()) return null
        val indice = nodi.indices.maxBy { nodi[it].boundsInRoot.height }
        return trovati[indice]
    }

    // --- Aspettare, cercare, toccare --------------------------------------------------------

    /**
     * L'orologio di Compose va a mano (autoAdvance spento): le schermate hanno
     * cicli `while (true) { delay(…) }` (l'ora che passa, il conto alla rovescia del
     * codice) che con l'orologio automatico non lasciano mai "ferma" la prova.
     * Qui si fanno passare i fotogrammi solo quando servono.
     */
    fun fotogrammi(quanti: Int = 1) {
        repeat(quanti) {
            compose.mainClock.advanceTimeByFrame()
            misuraTutto()
            compose.waitForIdle()
        }
    }

    /**
     * Robolectric non "disegna" le finestre dei dialoghi: Compose, che misura i
     * dialoghi al momento del disegno, resterebbe con una misura in sospeso per
     * sempre (e la prova non sarebbe mai ferma). Qui si misura a mano ogni radice
     * di Compose, in tutte le finestre aperte. Sull'app vera non succede.
     */
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
    fun misuraTutto() {
        val radici = try {
            val wmg = Class.forName("android.view.WindowManagerGlobal")
            val istanza = wmg.getMethod("getInstance").invoke(null)
            val campo = wmg.getDeclaredField("mRoots").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (campo.get(istanza) as List<Any>).mapNotNull { it.javaClass.getMethod("getView").invoke(it) as? View }
        } catch (e: ReflectiveOperationException) {
            emptyList()
        }
        fun cerca(v: View) {
            if (v is ViewRootForTest && v.view.isAttachedToWindow) v.measureAndLayoutForTest()
            if (v is ViewGroup) for (i in 0 until v.childCount) cerca(v.getChildAt(i))
        }
        radici.toList().forEach { cerca(it) }
    }

    fun aspetta(cosa: String, massimoMs: Long = 20_000, condizione: () -> Boolean) {
        val fine = System.currentTimeMillis() + massimoMs
        while (true) {
            fotogrammi(2)
            val ok = try {
                condizione()
            } catch (e: AssertionError) {
                false
            } catch (e: IllegalStateException) {
                false
            }
            if (ok) return
            if (System.currentTimeMillis() > fine) throw IllegalStateException("Non arriva: $cosa (dopo $massimoMs ms)")
            Thread.sleep(25)
        }
    }

    /** Lascia finire richieste, decodifiche e animazioni prima dello scatto. */
    fun assesta() {
        repeat(4) {
            fotogrammi(3)
            Thread.sleep(120)
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        fotogrammi(2)
    }

    fun ce(testo: String): Boolean =
        compose.onAllNodesWithText(testo, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    fun ce(@StringRes id: Int): Boolean = ce(s(id))

    fun nonCe(testo: String): Boolean = !ce(testo)

    fun ceEsatto(testo: String): Boolean =
        compose.onAllNodesWithText(testo, substring = false, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    fun tocca(testo: String, indice: Int = 0, esatto: Boolean = false) {
        misuraTutto()
        val nodo = compose.onAllNodesWithText(testo, substring = !esatto, useUnmergedTree = false)[indice]
        // Dentro una lista o un dialogo che scorre: prima lo si porta sullo schermo.
        try {
            nodo.performScrollTo()
            fotogrammi(2)
        } catch (_: AssertionError) {
        }
        misuraTutto()
        nodo.performClick()
        fotogrammi(5)
    }

    fun tocca(@StringRes id: Int, indice: Int = 0) = tocca(s(id), indice, esatto = true)

    fun toccaDescrizione(descrizione: String, indice: Int = 0) {
        misuraTutto()
        compose.onAllNodesWithContentDescription(descrizione, substring = true)[indice].performClick()
        fotogrammi(5)
    }

    /** Scorre la colonna principale finché [testo] è tutto sullo schermo. */
    fun scorriFino(testo: String, esatto: Boolean = false) {
        val colonna = colonnaPrincipale() ?: return
        repeat(40) {
            misuraTutto()
            val nodi = compose.onAllNodesWithText(testo, substring = !esatto)
            if (nodi.fetchSemanticsNodes().isNotEmpty()) {
                // Il nodo c'è (composto): lo si porta tutto sullo schermo, sopra la barra in basso.
                nodi[0].performScrollTo()
                fotogrammi(2)
                return
            }
            misuraTutto()
            colonna.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 300f) }
            fotogrammi(2)
        }
    }

    /** Torna in cima alla colonna principale. */
    /** Scorre la colonna principale fino in fondo. */
    fun inFondo() {
        val colonna = colonnaPrincipale() ?: return
        repeat(60) {
            val r = colonna.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            if (r.value() >= r.maxValue() - 1f) return
            misuraTutto()
            colonna.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 1500f) }
            fotogrammi(2)
        }
    }

    fun inCima() {
        inCimaA(colonnaPrincipale() ?: return)
    }

    /** Torna in cima a [colonna] (anche quella di un dialogo). */
    fun inCimaA(colonna: SemanticsNodeInteraction) {
        repeat(40) {
            val r = colonna.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            if (r.value() <= 0f) return
            misuraTutto()
            colonna.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -2000f) }
            fotogrammi(2)
        }
    }

    fun compose(): ComposeTestRule = compose

    /** Scrive [testo] nel campo numero [indice] (in ordine sullo schermo, dialoghi compresi). */
    fun scrivi(indice: Int, testo: String) {
        misuraTutto()
        compose.onAllNodes(hasSetTextAction())[indice].performTextReplacement(testo)
        fotogrammi(3)
    }

    fun toccaNodo(matcher: SemanticsMatcher, indice: Int = 0) {
        misuraTutto()
        compose.onAllNodes(matcher)[indice].performClick()
        fotogrammi(5)
    }

    /** Aggiunge le foto di questo giro all'elenco [nomeFile] nella cartella (per l'indice). */
    fun scriviElenco(nomeFile: String = "elenco.tsv") {
        val file = File(cartella, nomeFile)
        file.appendText(
            elenco.joinToString("") { "${it.file}\t${it.descrizione}\t${it.note}\n" },
            Charsets.UTF_8,
        )
        if (falliti.isNotEmpty()) {
            File(cartella, "non-riuscite.txt").appendText(falliti.joinToString("") { "$it\n" }, Charsets.UTF_8)
        }
        elenco.clear()
        falliti.clear()
    }

    companion object {
        /** Il DataStore delle impostazioni dell'app (privato in Impostazioni.kt): per partire ogni volta da zero. */
        @Suppress("UNCHECKED_CAST")
        fun preferenze(context: Context): DataStore<Preferences> {
            val classe = Class.forName("eu.stgm.pactum.genitore.dati.ImpostazioniKt")
            val metodo = classe.declaredMethods.first { it.name.contains("getDataStore") }
            metodo.isAccessible = true
            return metodo.invoke(null, context.applicationContext) as DataStore<Preferences>
        }
    }
}
