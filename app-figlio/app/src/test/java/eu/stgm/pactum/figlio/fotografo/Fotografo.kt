package eu.stgm.pactum.figlio.fotografo

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.AppNotIdleException
import androidx.test.espresso.IdlingPolicies
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import eu.stgm.pactum.figlio.MainActivity
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Il "fotografo" delle schermate dell'app del figlio: disegna sul PC, senza
 * telefono né emulatore (Robolectric con la grafica vera + Roborazzi), ogni
 * schermata in PNG. Solo test: si lancia a parte con -Pfotografo (v. app/build.gradle.kts).
 *
 * (0.15) Meno varianti di prima (l'app è sempre chiara: niente tema scuro):
 * ogni foto esce a 360 dp con testo normale e grande (1,3) — "chiaro_360" e
 * "chiaro_360_grande" — e le quattro schede principali anche a 411 dp
 * ([Variante.SCHEDE]). Le schermate lunghe hanno in più le pagine successive
 * ("_p2", "_p3"...) solo a 360 dp col testo normale.
 * -Pfotografo.solo=<pezzo di nome> (un'espressione regolare) rifà solo le
 * foto il cui nome la contiene.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
abstract class Fotografo {

    @get:Rule
    val compose = createEmptyComposeRule()

    protected val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun preparaIlMondo() {
        // Un campo di testo col cursore che lampeggia non lascia mai "ferma" la
        // schermata: si aspetta al massimo qualche secondo, poi si scatta lo stesso.
        IdlingPolicies.setMasterPolicyTimeout(4, TimeUnit.SECONDS)
        Mondo.azzera(app)
    }

    /** Aspetta che la schermata sia ferma (o qualche secondo, se non lo diventa mai). */
    protected fun calma() {
        try {
            compose.waitForIdle()
        } catch (e: AppNotIdleException) {
            // il cursore che lampeggia: va bene così
        }
    }

    /** Le misure di una foto. */
    data class Variante(val scuro: Boolean, val larghezza: Int, val grande: Boolean) {
        val altezza: Int get() = if (larghezza == 360) 800 else 914

        val suffisso: String
            get() = buildString {
                append(if (scuro) "_scuro" else "_chiaro")
                append("_").append(larghezza)
                if (grande) append("_grande")
            }

        val descrizione: String
            get() = "tema ${if (scuro) "scuro" else "chiaro"}, $larghezza dp, testo ${if (grande) "grande (1,3)" else "normale"}"

        fun qualificatori(): String =
            "it-rIT-w${larghezza}dp-h${altezza}dp-port-${if (scuro) "night" else "notnight"}-xhdpi"

        /** Le pagine dopo la prima si fotografano solo qui: chiaro, 360 dp, testo normale. */
        val conPagine: Boolean get() = !scuro && larghezza == 360 && !grande

        companion object {
            /** Ogni stato: 360 dp, testo normale e grande. */
            val BASE: List<Variante> = listOf(Variante(false, 360, false), Variante(false, 360, true))

            /** Le quattro schede principali: in più 411 dp. */
            val SCHEDE: List<Variante> = BASE + Variante(false, 411, false)
        }
    }

    /** Qualcosa di aperto da chiudere dopo la foto (un'activity). */
    fun interface Aperta {
        fun chiudi()
    }

    /**
     * Una foto in tutte le [varianti]: per ognuna si imposta il telefono finto,
     * [apri] apre la schermata (e la porta nello stato giusto), si scatta, si chiude.
     * [descrizione] va nell'elenco (elenco.tsv) da cui nasce l'indice.
     */
    protected fun scatta(
        nome: String,
        descrizione: String,
        varianti: List<Variante> = Variante.BASE,
        pagine: Boolean = false,
        apri: (Variante) -> Aperta,
    ) {
        // -Pfotografo.solo=<pezzo di nome>: rifà solo le foto che lo contengono.
        val solo = System.getProperty("fotografo.solo")?.takeIf { it.isNotBlank() }?.let { Regex(it) }
        for (v in varianti) {
            if (solo != null && !solo.containsMatchIn("$nome${v.suffisso}")) continue
            RuntimeEnvironment.setQualifiers(v.qualificatori())
            RuntimeEnvironment.setFontScale(if (v.grande) 1.3f else 1f)
            val aperta = apri(v)
            try {
                calma()
                var ultima = salva("$nome${v.suffisso}", "$descrizione — ${v.descrizione}")
                if (pagine && v.conPagine) {
                    var pagina = 2
                    while (pagina <= PAGINE_MASSIME && scorriGiu()) {
                        calma()
                        val file = "$nome${v.suffisso}_p$pagina"
                        val nuova = File(cartella(), "$file.png")
                        cattura(nuova)
                        // Uguale alla pagina prima: era già in fondo.
                        if (nuova.readBytes().contentEquals(ultima.readBytes())) {
                            nuova.delete()
                            break
                        }
                        registra(file, "$descrizione — ${v.descrizione}, pagina $pagina")
                        ultima = nuova
                        pagina++
                    }
                }
            } finally {
                aperta.chiudi()
                calma()
            }
        }
    }

    private fun salva(file: String, descrizione: String): File {
        val png = File(cartella(), "$file.png")
        cattura(png)
        registra(file, descrizione)
        return png
    }

    private fun registra(file: String, descrizione: String) {
        File(cartella(), "elenco.tsv").appendText("$file.png\t$descrizione\n")
    }

    /**
     * La foto di tutte le finestre aperte. Roborazzi le mette in ordine per tipo
     * di finestra, e due dialoghi hanno lo stesso tipo: senza questo un dialogo
     * aperto da un altro dialogo finirebbe disegnato SOTTO. Per il tempo della
     * foto i dialoghi successivi prendono un tipo "più alto", poi tornano com'erano.
     */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun cattura(file: File) {
        val dialoghi = finestre()
            .mapNotNull { it.layoutParams as? WindowManager.LayoutParams }
            .filter { it.type == WindowManager.LayoutParams.TYPE_APPLICATION }
        val tipi = dialoghi.map { it.type }
        dialoghi.forEachIndexed { i, lp -> if (i > 0) lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG + i }
        try {
            captureScreenRoboImage(file)
        } finally {
            dialoghi.forEachIndexed { i, lp -> lp.type = tipi[i] }
        }
    }

    /** Le finestre aperte, dalla più in basso alla più in alto. */
    private fun finestre(): List<View> {
        val classe = Class.forName("android.view.WindowManagerGlobal")
        val globale = classe.getMethod("getInstance").invoke(null)
        val campo = classe.getDeclaredField("mViews").apply { isAccessible = true }
        return (campo.get(globale) as List<*>).filterIsInstance<View>()
    }

    /**
     * Scorre di poco meno di una schermata il contenitore più grande che scorre
     * in verticale (quello di un dialogo, se c'è un dialogo aperto). False =
     * niente che scorra. Se era già in fondo la foto viene uguale e ci si ferma.
     */
    private fun scorriGiu(): Boolean {
        // La finestra più in alto (il dialogo, se c'è), e lì il contenitore più grande che scorre in verticale.
        val radice = radiciCompose().lastOrNull() ?: return false
        val scelto = nodiDi(radice.semanticsOwner.rootSemanticsNode)
            .filter { it.config.contains(SemanticsActions.ScrollBy) && it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
            .maxByOrNull { it.size.width.toLong() * it.size.height }
            ?: return false
        scelto.config[SemanticsActions.ScrollBy].action?.invoke(0f, scelto.size.height * 0.85f)
        return true
    }

    // --- Aprire le schermate ---------------------------------------------------

    /** Apre un'activity con [intent]; [prima] gira dopo onCreate e prima che si veda (per i ViewModel finti). */
    protected fun <A : Activity> apriActivity(
        classe: Class<A>,
        intent: Intent = Intent(app, classe),
        prima: (A) -> Unit = {},
    ): ActivityController<A> {
        val controller = Robolectric.buildActivity(classe, intent).create()
        prima(controller.get())
        controller.start().postCreate(null).resume().visible()
        calma()
        return controller
    }

    protected fun ActivityController<*>.comeAperta(): Aperta = Aperta {
        runCatching { pause() }
        runCatching { stop() }
        runCatching { destroy() }
    }

    /**
     * L'app vera (MainActivity, con la barra delle schede) sugli stati finti
     * [stati]: i ViewModel dell'activity si creano prima che si veda e prendono
     * lo stato finto al posto di quello letto da server e disco.
     */
    protected fun apriPactum(stati: StatiFinti, destinazione: String? = null): ActivityController<MainActivity> {
        val intent = Intent(app, MainActivity::class.java)
        destinazione?.let { intent.putExtra(MainActivity.EXTRA_DESTINAZIONE, it) }
        val controller = apriActivity(MainActivity::class.java, intent) { activity ->
            stati.applica(ViewModelProvider(activity))
        }
        // "Cosa vedono" si legge dal disco in un altro filo: finché non c'è, la
        // schermata è vuota. Si aspetta che compaia qualche parola.
        aspettaChe { compose.onAllNodes(conTesto).fetchSemanticsNodes().isNotEmpty() }
        return controller
    }

    /** Un'activity vuota, per ospitare una vista (la copertura, le notifiche). */
    protected fun apriVuota(): ActivityController<Activity> {
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, Activity::class.java))
        return apriActivity(Activity::class.java)
    }

    // --- Toccare -----------------------------------------------------------------

    /**
     * Tocca [testo] (il [indice]-esimo, dall'alto). [nelDialogo] = solo dentro
     * il dialogo aperto, non nella schermata sotto. Se non è sullo schermo (una
     * riga in fondo a una lista lunga) prima si scorre fin lì.
     */
    protected fun tocca(testo: String, indice: Int = 0, sottostringa: Boolean = false, nelDialogo: Boolean = false) {
        val conTesto = { n: SemanticsNode -> testiDi(n).any { if (sottostringa) it.contains(testo) else it == testo } }
        if (toccaDiretto(nelDialogo, indice, conTesto)) return
        val cerca = hasText(testo, substring = sottostringa)
        val contenitori = compose.onAllNodes(hasScrollAction())
        for (i in 0 until contenitori.fetchSemanticsNodes().size) {
            if (runCatching { contenitori[i].performScrollToNode(cerca) }.isSuccess) break
        }
        calma()
        check(toccaDiretto(nelDialogo, indice, conTesto)) { "Non trovo «$testo» da toccare" }
    }

    protected fun toccaNelDialogo(testo: String, indice: Int = 0, sottostringa: Boolean = false) =
        tocca(testo, indice, sottostringa, nelDialogo = true)

    /** Il [indice]-esimo pallino (scelta a cerchietto) del dialogo aperto. */
    protected fun toccaPallino(indice: Int) {
        check(toccaDiretto(true, indice) { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton }) {
            "Non trovo il pallino $indice"
        }
    }

    protected fun toccaIcona(descrizione: String, indice: Int = 0) {
        check(toccaDiretto(false, indice) { n -> n.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(descrizione) == true }) {
            "Non trovo l'icona «$descrizione»"
        }
    }

    /**
     * Il tocco senza aspettare che Compose sia "fermo": i nodi dell'accessibilità
     * si leggono direttamente dalle finestre aperte (la più in alto per prima) e
     * si chiama la loro azione "clic". Serve perché un dialogo con un campo di
     * testo, nel simulatore, non risulta mai "fermo" e le ricerche normali
     * aspetterebbero per sempre. False = niente da toccare.
     */
    private fun toccaDiretto(soloDialoghi: Boolean, indice: Int, scegli: (SemanticsNode) -> Boolean): Boolean {
        val radici = radiciCompose().let { if (soloDialoghi) it.drop(1) else it }.reversed()
        for (radice in radici) {
            val nodi = nodiDi(radice.semanticsOwner.rootSemanticsNode)
                .filter { it.config.contains(SemanticsActions.OnClick) && scegli(it) }
            if (nodi.size > indice) {
                nodi[indice].config[SemanticsActions.OnClick].action?.invoke()
                calma()
                return true
            }
        }
        return false
    }

    /** Le radici Compose di tutte le finestre aperte (l'activity, poi i dialoghi), nell'ordine in cui sono state aperte. */
    private fun radiciCompose(): List<RootForTest> {
        val viste = finestre()
        val radici = mutableListOf<RootForTest>()
        fun cerca(v: View) {
            if (v is RootForTest) radici += v
            if (v is ViewGroup) for (i in 0 until v.childCount) cerca(v.getChildAt(i))
        }
        viste.forEach { cerca(it) }
        return radici
    }

    private fun nodiDi(nodo: SemanticsNode): List<SemanticsNode> = listOf(nodo) + nodo.children.flatMap { nodiDi(it) }

    private fun testiDi(nodo: SemanticsNode): List<String> =
        nodo.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    /** Aspetta che un testo compaia (letture dal disco in un altro filo, un dialogo che si apre). */
    protected fun aspetta(testo: String, sottostringa: Boolean = true, ms: Long = 6_000) {
        aspettaChe(ms) {
            radiciCompose().any { radice ->
                nodiDi(radice.semanticsOwner.unmergedRootSemanticsNode).any { n ->
                    testiDi(n).any { if (sottostringa) it.contains(testo) else it == testo }
                }
            }
        }
    }


    /** Aspetta [condizione] fino a [ms] (tempo vero: i fili del disco vanno da soli); poi si va avanti lo stesso. */
    protected fun aspettaChe(ms: Long = 6_000, condizione: () -> Boolean) {
        val fine = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < fine) {
            calma()
            val fatto = try {
                condizione()
            } catch (e: AppNotIdleException) {
                false
            }
            if (fatto) return
            Thread.sleep(50)
        }
        calma()
    }

    companion object {
        const val PAGINE_MASSIME = 6

        private val conTesto = SemanticsMatcher("ha del testo") { it.config.contains(SemanticsProperties.Text) }

        fun cartella(): File =
            File(System.getProperty("fotografo.cartella") ?: "build/fotografo").apply { mkdirs() }
    }
}

/**
 * Lo stato finto di un ViewModel: la sua coroutine si ferma (niente server,
 * niente disco) e la schermata legge [stato] al posto del suo.
 */
fun <S> ViewModel.fingi(stato: S): MutableStateFlow<S> {
    viewModelScope.cancel()
    val finto = MutableStateFlow(stato)
    val campo = javaClass.getDeclaredField("stato")
    campo.isAccessible = true
    campo.set(this, finto)
    return finto
}
