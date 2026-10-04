package eu.stgm.pactum.design

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import android.graphics.Color as ColoreAndroid

/**
 * Il velo chiaro dietro la navigazione a 3 tasti, dove Android lo vuole (sotto
 * Android 10 non c'è il velo automatico): bianco al 90%, come quello di Material.
 */
private val VeloChiaro = ColoreAndroid.argb(0xE6, 0xFF, 0xFF, 0xFF)

/** Richiesto dall'API ma mai usato: Pactum è sempre chiaro (v. sotto). */
private val VeloScuro = ColoreAndroid.argb(0x80, 0x1B, 0x1B, 0x1B)

/**
 * Il bordo pieno (edge-to-edge) con le barre di sistema CHIARE: icone scure
 * su fondo trasparente in alto; in basso trasparente con la navigazione a gesti
 * e un velo chiaro con quella a 3 tasti (lo mette Android). Sempre chiaro anche
 * col telefono in modalità scura: Pactum non ha il tema scuro (scelta di Andrea),
 * e senza questo su Android 15 (targetSdk 35, bordo pieno obbligatorio) le icone
 * della barra di stato potevano venire bianche su fondo bianco.
 *
 * Va chiamata in `onCreate`, PRIMA di `setContent`:
 * ```
 * override fun onCreate(savedInstanceState: Bundle?) {
 *     attivaBordoPieno()
 *     super.onCreate(savedInstanceState)   // (o prima: basta che sia prima di setContent)
 *     setContent { PactumTheme { ... } }
 * }
 * ```
 *
 * Cosa devono fare poi le schermate (il contenuto ora va SOTTO le barre):
 * - Con `Scaffold` + `TopAppBar`/`bottomBar` ([BarraSchede]): niente, i bordi li
 *   gestiscono loro. Il contenuto usa il `PaddingValues` dello Scaffold.
 * - Senza Scaffold (pagine a tutto schermo, barriere, l'avviso, le pagine
 *   animate delle sessioni): `Modifier.safeDrawingPadding()` (oppure
 *   `windowInsetsPadding(WindowInsets.safeDrawing)`) sul contenitore esterno.
 *   Il fondo colorato va PRIMA del padding, così riempie anche dietro le barre.
 * - Scaffold annidati (una scheda con il suo Scaffold dentro lo Scaffold con la
 *   barra delle schede): quello interno con `contentWindowInsets = WindowInsets(0)`
 *   e la sua `TopAppBar` con `windowInsets = WindowInsets(0)` se la barra di stato
 *   la gestisce già quello esterno — altrimenti i margini si contano due volte.
 * - Le liste lunghe: il padding dello Scaffold va in `contentPadding` della
 *   LazyColumn (non come `Modifier.padding`), così si scorre fin sotto la barra.
 */
fun ComponentActivity.attivaBordoPieno() {
    enableEdgeToEdge(
        // `auto` con "mai scuro": icone scure sempre, e da Android 10 il sistema
        // decide da solo il velo per la navigazione a 3 tasti.
        statusBarStyle = SystemBarStyle.auto(ColoreAndroid.TRANSPARENT, ColoreAndroid.TRANSPARENT) { false },
        navigationBarStyle = SystemBarStyle.auto(VeloChiaro, VeloScuro) { false },
    )
}
