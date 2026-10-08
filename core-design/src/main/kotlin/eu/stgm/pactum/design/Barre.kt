package eu.stgm.pactum.design

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import kotlin.math.floor

// --- Testo su una riga -------------------------------------------------------------

/**
 * Un testo che sta SEMPRE su una riga: se nella larghezza disponibile non ci sta,
 * si rimpicciolisce quanto basta (misurato davvero, con `rememberTextMeasurer`),
 * fino a [minimo]. Solo sotto il minimo, come ultima difesa, finisce coi puntini.
 *
 * Quando usarlo: etichette corte in posti stretti che non possono andare a capo
 * (la barra delle schede, le etichette sotto un'icona). NON per le frasi.
 * Attenzione: dentro c'è un `BoxWithConstraints`, quindi non va messo in un
 * layout che chiede le misure "intrinseche" (`IntrinsicSize`, [FilaPulsanti]).
 */
@Composable
fun TestoSuUnaRiga(
    testo: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    minimo: TextUnit = 9.sp,
    color: Color = Color.Unspecified,
) = TestoSuUnaRiga(testo, style, modifier, minimo, color, tetto = null, onMisura = null)

/**
 * Come [TestoSuUnaRiga], più due cose per la [BarraSchede]: [onMisura] riceve la
 * misura (sp) che servirebbe a questo testo, e [tetto] la abbassa ancora se un
 * altro testo vicino ne ha bisogno (così le etichette della barra sono tutte uguali).
 */
@Composable
internal fun TestoSuUnaRiga(
    testo: String,
    style: TextStyle,
    modifier: Modifier,
    minimo: TextUnit,
    color: Color,
    tetto: Float?,
    onMisura: ((Float) -> Unit)?,
) {
    val base = LocalTextStyle.current.merge(style)
    val misuratore = rememberTextMeasurer()
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val disponibile = constraints.maxWidth
        val limitato = constraints.hasBoundedWidth
        val propria = remember(testo, base, disponibile, limitato, minimo, misuratore) {
            if (!limitato || !base.fontSize.isSpecified) return@remember base.fontSize.value
            fun larghezza(s: TextStyle) =
                misuratore.measure(testo, s, maxLines = 1, softWrap = false).size.width
            val naturale = base.fontSize.value
            val minimoSp = if (minimo.isSpecified) minimo.value else naturale
            var misura = misuraSuUnaRiga(naturale, larghezza(base), disponibile, minimoSp)
            // La proporzione è una stima (le lettere non scalano proprio in linea
            // retta): si scende di un quarto di sp finché ci sta davvero.
            var giri = 0
            while (misura > minimoSp && larghezza(base.ridotto(misura / naturale)) > disponibile && giri < 12) {
                misura = (misura - PassoSp).coerceAtLeast(minimoSp)
                giri++
            }
            misura
        }
        if (onMisura != null) {
            LaunchedEffect(propria) { onMisura(propria) }
        }
        val misura = if (tetto != null && tetto < propria) tetto else propria
        val stile = if (base.fontSize.isSpecified && misura.isFinite()) base.ridotto(misura / base.fontSize.value) else base
        Text(
            text = testo,
            style = stile,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Lo stile rimpicciolito di [fattore]: misura e spaziatura tra le lettere insieme (l'interlinea resta, la barra non cambia altezza). */
private fun TextStyle.ridotto(fattore: Float): TextStyle {
    if (fattore >= 1f) return this
    return copy(
        fontSize = fontSize * fattore,
        letterSpacing = if (letterSpacing.isSpecified) letterSpacing * fattore else letterSpacing,
    )
}

/** Di quanto si scende a ogni prova in [TestoSuUnaRiga]. */
private const val PassoSp = 0.25f

/**
 * La misura (in sp) con cui un testo largo [larghezzaNaturale] alla misura
 * [naturale] sta in [disponibile]: la stessa se ci sta, altrimenti in
 * proporzione, arrotondata per difetto al quarto di sp, mai sotto [minimo].
 */
internal fun misuraSuUnaRiga(naturale: Float, larghezzaNaturale: Int, disponibile: Int, minimo: Float): Float {
    if (larghezzaNaturale <= disponibile || larghezzaNaturale <= 0) return naturale
    val proporzionale = naturale * disponibile.coerceAtLeast(0) / larghezzaNaturale
    val arrotondata = floor(proporzionale / PassoSp) * PassoSp
    return arrotondata.coerceIn(minOf(minimo, naturale), naturale)
}

// --- Barra delle schede ------------------------------------------------------------

/**
 * Una scheda della [BarraSchede]. [badge] = il numero sul pallino (niente
 * pallino se null o 0); [descrizioneBadge] = cosa legge TalkBack al posto del
 * numero ("3 proposte da decidere").
 */
data class VoceBarra(
    val etichetta: String,
    val icona: Painter,
    val badge: Int? = null,
    val descrizioneBadge: String? = null,
    /** (0.19) La sezione della scheda: scelta, la pillola dietro l'icona prende il suo colore. */
    val sezione: Sezione? = null,
)

/**
 * La barra delle schede in basso (Material 3 `NavigationBar`, colori del tema),
 * al massimo 4 voci (le altre si ignorano). L'etichetta sta SEMPRE su una riga:
 * se non ci sta si rimpicciolisce ([TestoSuUnaRiga]), mai a capo né tagliata.
 * Il pallino col numero sta in alto a destra dell'icona senza coprirla ("99+" oltre 99).
 *
 * La barra gestisce da sola il margine della barra di navigazione di Android:
 * mettila nel `bottomBar` dello `Scaffold`.
 */
@Composable
fun BarraSchede(
    voci: List<VoceBarra>,
    selezionata: Int,
    onSeleziona: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val schema = MaterialTheme.colorScheme
    val colori = NavigationBarItemDefaults.colors(
        selectedIconColor = schema.onPrimaryContainer,
        selectedTextColor = schema.primary,
        indicatorColor = schema.primaryContainer,
        unselectedIconColor = schema.onSurfaceVariant,
        unselectedTextColor = schema.onSurfaceVariant,
    )
    // (0.19) Ogni scheda col colore della sua sezione: si capisce dove si è anche solo dal colore.
    val coloriVoci = voci.map { voce ->
        voce.sezione?.let { sezione ->
            NavigationBarItemDefaults.colors(
                selectedIconColor = sezione.inchiostro,
                selectedTextColor = sezione.inchiostro,
                indicatorColor = sezione.fondo,
                unselectedIconColor = schema.onSurfaceVariant,
                unselectedTextColor = schema.onSurfaceVariant,
            )
        } ?: colori
    }
    // La misura di cui ha bisogno ogni etichetta: tutte prendono la più piccola,
    // così una barra non ha "Panoramica" piccola e "Tempo" grande.
    val misure = remember { mutableStateMapOf<Int, Float>() }
    val comune = misure.values.minOrNull()
    NavigationBar(modifier = modifier, containerColor = schema.surfaceContainer) {
        voci.take(VociMassime).forEachIndexed { indice, voce ->
            NavigationBarItem(
                selected = indice == selezionata,
                onClick = { onSeleziona(indice) },
                icon = { IconaConPallino(voce) },
                label = {
                    TestoSuUnaRiga(
                        testo = voce.etichetta,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier,
                        minimo = 9.sp,
                        color = Color.Unspecified,
                        tetto = comune,
                        onMisura = { misure[indice] = it },
                    )
                },
                alwaysShowLabel = true,
                colors = coloriVoci[indice],
            )
        }
    }
}

/** Più di 4 schede non stanno su un telefono da 360 dp senza diventare illeggibili. */
private const val VociMassime = 4

/** Il numero del pallino: "99+" oltre 99. */
internal fun testoBadge(numero: Int): String = if (numero > 99) "99+" else numero.toString()

/** L'icona con il pallino appoggiato fuori dal suo angolo in alto a destra. */
@Composable
private fun IconaConPallino(voce: VoceBarra) {
    val numero = voce.badge
    if (numero == null || numero <= 0) {
        Icon(voce.icona, contentDescription = null)
        return
    }
    val testo = testoBadge(numero)
    Layout(
        content = {
            Icon(voce.icona, contentDescription = null)
            Badge(
                modifier = Modifier.semantics { contentDescription = voce.descrizioneBadge ?: testo },
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ) {
                Text(testo)
            }
        },
    ) { misurabili, _ ->
        val icona = misurabili[0].measure(Constraints())
        val pallino = misurabili[1].measure(Constraints())
        // Le icone Material hanno 2 dp di margine vuoto dentro i loro 24: il
        // pallino parte lì, così non tocca il disegno; e sale di metà altezza.
        val rientro = (Spazi.xs / 2).roundToPx()
        layout(icona.width, icona.height) {
            icona.placeRelative(0, 0)
            pallino.placeRelative(icona.width - rientro, -pallino.height / 2 + rientro)
        }
    }
}

// --- Foglio dal basso ----------------------------------------------------------------

/**
 * Il foglio che sale dal basso (Material 3 `ModalBottomSheet`), con i colori del
 * tema, il [titolo] se c'è, 20 di margine ai lati e lo spazio per la barra di
 * navigazione di Android in fondo. Si apre tutto (niente mezza altezza) e il
 * contenuto scorre se è lungo.
 *
 * Quando usarlo: per una scelta o un dettaglio veloce sopra la schermata (le
 * azioni di una regola, scegliere un figlio). Per un "sei sicuro?" usa un dialogo.
 * Per chiuderlo da codice, togli il composable (es. `if (aperto) FoglioDalBasso(...)`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoglioDalBasso(
    onChiudi: () -> Unit,
    modifier: Modifier = Modifier,
    titolo: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val stato = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val schema = MaterialTheme.colorScheme
    // Il margine della barra di navigazione lo mette già ModalBottomSheet
    // (windowInsets di default): qui non si aggiunge, sennò raddoppia.
    ModalBottomSheet(
        onDismissRequest = onChiudi,
        modifier = modifier,
        sheetState = stato,
        containerColor = schema.surfaceContainerLow,
        contentColor = schema.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = PaddingEvidenza, end = PaddingEvidenza, bottom = Spazi.xl),
        ) {
            if (titolo != null) {
                Text(
                    text = titolo,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .padding(bottom = Spazi.l)
                        .semantics { heading() },
                )
            }
            content()
        }
    }
}
