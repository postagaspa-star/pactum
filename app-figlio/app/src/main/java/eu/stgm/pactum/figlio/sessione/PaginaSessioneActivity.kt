package eu.stgm.pactum.figlio.sessione

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.design.TemaSessione
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.notifiche.AvvisiLocali
import eu.stgm.pactum.figlio.ui.theme.PactumTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * (0.12) La pagina animata di inizio o fine di una Sessione: tutto schermo,
 * il fondo del tema (scelto dal nome: TemaSessione), da 14 a 20 adesivi-emoji
 * che entrano dai bordi, rimbalzano, galleggiano e dondolano, e al centro la
 * scheda con l'emoji grande e due righe ("Sessione «Studio» iniziata", "Fino
 * alle 17:00 · usa le app della sessione"). Dopo 3,5 secondi si chiude da
 * sola (di più se l'accessibilità del telefono lo chiede, e TalkBack la
 * legge); un tocco qualsiasi la chiude prima. Con "Rimuovi animazioni" gli
 * adesivi stanno fermi e le parole sono le stesse. La pagina della fine è
 * "fatta" solo quando arriva sullo schermo (onResume): chi prova ad aprirla
 * e non ci riesce non la perde.
 *
 * È di Pactum: la barriera non la copre mai, e non la conta (non è una sua
 * apertura). Task sua e fuori dalle recenti, come la barriera: chiusa, si
 * torna dove si era (Pactum, o l'app in uso se è stata aperta sopra).
 */
class PaginaSessioneActivity : ComponentActivity() {

    private val dati = mutableStateOf<DatiPagina?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val letti = DatiPagina.da(intent)
        if (letti == null) {
            finish()
            return
        }
        dati.value = letti
        // Il fondo del tema anche sotto le barre di sistema, con le icone scure: il fondo è chiaro.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(TRASPARENTE, TRASPARENTE),
            navigationBarStyle = SystemBarStyle.light(TRASPARENTE, TRASPARENTE),
        )
        val statiche = PagineSessione.statiche(scalaAnimazioni())
        val durata = PagineSessione.durataPagina(tempoRaccomandato())
        setContent {
            PactumTheme {
                dati.value?.let { PaginaSessione(dati = it, statiche = statiche, durata = durata, onChiudi = { finish() }) }
            }
        }
    }

    /**
     * (0.12) Sullo schermo: solo adesso la pagina della fine è "fatta" (non
     * torna più), e la notifica "Sessione finita", se c'era, non serve più.
     */
    override fun onResume() {
        super.onResume()
        val pagina = dati.value ?: return
        if (pagina.fine) {
            ArchivioSessioni.segnaPaginaVista(this, pagina.svoltaId)
            AvvisiLocali.cancella(this, AvvisiLocali.ID_SESSIONE_FINITA)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DatiPagina.da(intent)?.let { dati.value = it }
    }

    /** Lasciata (Home, un'altra app, schermo spento): se ne va, non resta nascosta sotto. */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) finish()
    }

    /**
     * Quanto tempo l'accessibilità del telefono vuole per una schermata che si
     * chiude da sola (Android 10+); null = nessuna richiesta.
     */
    private fun tempoRaccomandato(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            getSystemService(AccessibilityManager::class.java)?.getRecommendedTimeoutMillis(
                PagineSessione.DURATA_PAGINA_MS.toInt(),
                AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_ICONS,
            )
        } catch (e: Exception) {
            null
        }
    }

    /** La durata delle animazioni scelta nelle impostazioni del telefono (0 = "Rimuovi animazioni"). */
    private fun scalaAnimazioni(): Float = try {
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    } catch (e: Exception) {
        1f
    }

    companion object {
        /**
         * (0.12) La pagina della fine che si sta aprendo adesso, finché non
         * arriva sullo schermo (e lì diventa "fatta"): nel frattempo nessun
         * altro la riapre, né manda la notifica al suo posto.
         */
        @Volatile
        private var inAperturaId: Long = Long.MIN_VALUE

        @Volatile
        private var inAperturaDal: Long = 0L

        private const val APERTURA_MS = 15_000L

        fun inApertura(svoltaId: Long, adesso: Long = System.currentTimeMillis()): Boolean =
            inAperturaId == svoltaId && adesso - inAperturaDal in 0 until APERTURA_MS

        /** La pagina dell'inizio di [svolta] (appena confermata dal server). */
        fun apriInizio(context: Context, svolta: SvoltaLocale): Boolean = apri(context, svolta, fine = false)

        /** La pagina della fine di [svolta]: in Pactum, o sopra l'app in uso dal servizio. */
        fun apriFine(context: Context, svolta: SvoltaLocale): Boolean = apri(context, svolta, fine = true)

        private fun apri(context: Context, svolta: SvoltaLocale, fine: Boolean): Boolean {
            // NO_USER_ACTION: per l'app sotto non è "l'utente se ne va" (un video
            // non passa in riquadro), come per la barriera.
            val intent = Intent(context, PaginaSessioneActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra(EXTRA_TIPO, if (fine) TIPO_FINE else TIPO_INIZIO)
                .putExtra(EXTRA_SVOLTA, svolta.id)
                .putExtra(EXTRA_NOME, svolta.nome)
                .putExtra(EXTRA_INIZIO, svolta.inizio)
                .putExtra(EXTRA_FINE, svolta.fine)
                .putExtra(EXTRA_PRIMA, PagineSessione.chiusaPrima(svolta))
            if (fine) {
                inAperturaId = svolta.id
                inAperturaDal = System.currentTimeMillis()
            }
            return try {
                context.startActivity(intent)
                true
            } catch (e: Exception) {
                if (fine) inAperturaId = Long.MIN_VALUE
                false
            }
        }
    }
}

private const val EXTRA_TIPO = "tipo"
private const val EXTRA_SVOLTA = "svolta_id"
private const val EXTRA_NOME = "nome"
private const val EXTRA_INIZIO = "inizio"
private const val EXTRA_FINE = "fine"
private const val EXTRA_PRIMA = "chiusa_prima"
private const val TIPO_INIZIO = "inizio"
private const val TIPO_FINE = "fine"
private const val TRASPARENTE = android.graphics.Color.TRANSPARENT

/** L'entrata degli adesivi; poi galleggiano finché la pagina resta. */
private const val DURATA_ENTRATA_MS = 1_100

/** Un giro intero del galleggiare e del dondolare. */
private const val DURATA_GIRO_MS = 2_400

/** Quello che dice la pagina: inizio o fine, di quale sessione, da quando a quando. */
private data class DatiPagina(
    val fine: Boolean,
    val svoltaId: Long,
    val nome: String,
    val inizio: Long,
    val termine: Long,
    val chiusaPrima: Boolean,
) {
    companion object {
        fun da(intent: Intent?): DatiPagina? {
            val tipo = intent?.getStringExtra(EXTRA_TIPO) ?: return null
            val nome = intent.getStringExtra(EXTRA_NOME)?.takeIf { it.isNotBlank() } ?: return null
            val inizio = intent.getLongExtra(EXTRA_INIZIO, 0L)
            val termine = intent.getLongExtra(EXTRA_FINE, 0L)
            if (inizio <= 0L || termine < inizio) return null
            return DatiPagina(
                fine = tipo == TIPO_FINE,
                svoltaId = intent.getLongExtra(EXTRA_SVOLTA, 0L),
                nome = nome,
                inizio = inizio,
                termine = termine,
                chiusaPrima = intent.getBooleanExtra(EXTRA_PRIMA, false),
            )
        }
    }
}

@Composable
private fun PaginaSessione(dati: DatiPagina, statiche: Boolean, durata: Long, onChiudi: () -> Unit) {
    val tema = remember(dati.nome) { TemaSessione.daNome(dati.nome) }
    // Sempre gli stessi adesivi per la stessa pagina: una rotazione non li rimescola.
    val adesivi = remember(dati) {
        AdesiviSessione.disponi(tema.emoji.size, seme = dati.svoltaId * 31 + if (dati.fine) 1 else 0)
    }
    val latoPx = with(LocalDensity.current) { AdesiviSessione.LATO_MASSIMO_DP.dp.roundToPx() }
    // Gli adesivi si disegnano una volta sola, fuori dal filo principale. Un
    // adesivo che non si disegna resta fuori; nessuno = solo la scheda. Mai un
    // errore che chiude l'app (e con lei il servizio del testimone).
    var immagini by remember(tema, latoPx) { mutableStateOf<List<ImageBitmap>?>(null) }
    LaunchedEffect(tema, latoPx) {
        immagini = withContext(Dispatchers.Default) {
            tema.emoji.mapNotNull { emoji -> runCatching { disegnaAdesivo(emoji, latoPx) }.getOrNull() }
        }
    }

    // Si chiude da sola; un tocco la chiude prima.
    LaunchedEffect(dati) {
        delay(durata)
        onChiudi()
    }

    // L'entrata (0 → 1) e il giro del galleggiare: valori letti solo mentre
    // si disegna, così ogni fotogramma ridisegna e basta, senza ricomporre.
    // La scheda entra subito; gli adesivi la raggiungono appena disegnati.
    val entrata = remember(dati) { Animatable(if (statiche) 1f else 0f) }
    LaunchedEffect(dati) {
        if (!statiche) entrata.animateTo(1f, tween(DURATA_ENTRATA_MS, easing = LinearEasing))
    }
    val fase: State<Float> = if (statiche) {
        remember { mutableFloatStateOf(0f) }
    } else {
        rememberInfiniteTransition(label = "adesivi").animateFloat(
            initialValue = 0f,
            targetValue = AdesiviSessione.DUE_PI,
            animationSpec = infiniteRepeatable(tween(DURATA_GIRO_MS, easing = LinearEasing)),
            label = "fase",
        )
    }
    val sfondo = remember(tema) {
        Brush.verticalGradient(listOf(tema.sfondoChiaro, lerp(tema.sfondoChiaro, Color.White, 0.55f)))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(sfondo)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = stringResource(R.string.pagina_chiudi),
                onClick = onChiudi,
            ),
    ) {
        val lista = immagini
        if (lista != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                disegnaAdesivi(adesivi, lista, entrata.value, fase.value)
            }
        }
        SchedaPagina(
            dati = dati,
            tema = tema,
            modifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer {
                    val t = entrata.value
                    val scala = AdesiviSessione.scalaScheda(t)
                    scaleX = scala
                    scaleY = scala
                    alpha = AdesiviSessione.alfaScheda(t)
                },
        )
    }
}

/**
 * La scheda al centro: l'emoji grande del tema e le due righe (più "Chiusa
 * prima della fine"). Il nome fra «» senza un'altra emoji: quella del tema è
 * già lì, grande. Per TalkBack è una cosa sola, letta appena si apre; l'emoji
 * si legge col nome del tema ("Studio"), non come un'emoji.
 */
@Composable
private fun SchedaPagina(dati: DatiPagina, tema: TemaSessione, modifier: Modifier) {
    val context = LocalContext.current
    val vista = LocalView.current
    val titolo = stringResource(
        if (dati.fine) R.string.pagina_fine_titolo else R.string.pagina_inizio_titolo,
        nomeSessioneTraVirgolette(context, dati.nome, conEmoji = false),
    )
    val nomeTema = stringResource(nomeDelTema(tema))
    val chiusaPrima = stringResource(R.string.pagina_fine_chiusa_prima)
    val sottotitolo = remember(dati) {
        if (dati.fine) {
            testoDurataSvolta(context, dati.nome, dati.inizio, dati.termine)
        } else {
            val quando = TestoSessioni.quandoFinisce(dati.termine, System.currentTimeMillis(), ZoneId.systemDefault())
            context.getString(
                if (quando.domani) R.string.pagina_inizio_testo_domani else R.string.pagina_inizio_testo,
                quando.ora,
            )
        }
    }
    LaunchedEffect(dati) {
        val tutto = listOfNotNull(titolo, sottotitolo, chiusaPrima.takeIf { dati.fine && dati.chiusaPrima })
        vista.announceForAccessibility(tutto.joinToString(". "))
    }
    Card(
        modifier = modifier
            .padding(horizontal = Spazi.xl)
            .widthIn(max = 420.dp)
            .semantics(mergeDescendants = true) {},
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spazi.xl, vertical = Spazi.xl + Spazi.s),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spazi.m),
        ) {
            Text(
                text = tema.emojiPrincipale,
                style = TextStyle(fontSize = 64.sp, lineHeight = 76.sp),
                modifier = Modifier.semantics { contentDescription = nomeTema },
            )
            Text(
                text = titolo,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = sottotitolo,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (dati.fine && dati.chiusaPrima) {
                Text(
                    text = chiusaPrima,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Gli adesivi a [t] dell'entrata e alla [fase] del galleggiare: ognuno dal suo
 * bordo verso il suo posto, con un piccolo rimbalzo, poi su e giù e un po' di
 * dondolio. Nessun oggetto nuovo per fotogramma: solo numeri.
 */
private fun DrawScope.disegnaAdesivi(adesivi: List<Adesivo>, immagini: List<ImageBitmap>, t: Float, fase: Float) {
    if (immagini.isEmpty()) return
    val larghezza = size.width
    val altezza = size.height
    for (i in adesivi.indices) {
        val a = adesivi[i]
        val p = AdesiviSessione.entrata(t, a.ritardo)
        if (p <= 0f) continue
        val immagine = immagini[a.emoji % immagini.size]
        val su = sin(fase * a.velocita + a.fase)
        val x = (a.xDa + (a.x - a.xDa) * p) * larghezza
        val y = (a.yDa + (a.y - a.yDa) * p) * altezza + su * a.galleggia.dp.toPx()
        val lato = a.lato.dp.toPx() * (0.6f + 0.4f * p)
        val meta = lato / 2f
        val gradi = a.rotazione + sin(fase * 2f * a.velocita + a.fase) * DONDOLIO_GRADI
        rotate(degrees = gradi, pivot = Offset(x, y)) {
            drawImage(
                image = immagine,
                dstOffset = IntOffset((x - meta).roundToInt(), (y - meta).roundToInt()),
                dstSize = IntSize(lato.roundToInt(), lato.roundToInt()),
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}

/** Il nome del tema, letto da TalkBack al posto dell'emoji grande. */
private fun nomeDelTema(tema: TemaSessione): Int = when (tema) {
    TemaSessione.STUDIO -> R.string.tema_studio
    TemaSessione.LETTURA -> R.string.tema_lettura
    TemaSessione.SPORT -> R.string.tema_sport
    TemaSessione.MUSICA -> R.string.tema_musica
    TemaSessione.ARTE -> R.string.tema_arte
    TemaSessione.RELAX -> R.string.tema_relax
    TemaSessione.PROGRAMMAZIONE -> R.string.tema_programmazione
    TemaSessione.STELLINE -> R.string.tema_stelline
}

/** Quanto dondola un adesivo, in gradi, da una parte e dall'altra. */
private const val DONDOLIO_GRADI = 7f

/**
 * Un adesivo: l'emoji con il bordo bianco e un'ombra morbida, disegnata una
 * volta sola in un quadrato di [lato] px. Il bordo è l'emoji tinta di bianco e
 * ripetuta tutto intorno; l'ombra è quella sagoma sfocata, un po' più in basso.
 */
private fun disegnaAdesivo(emoji: String, lato: Int): ImageBitmap {
    val misura = lato * 0.56f
    val bordo = misura * 0.09f
    val pennello = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = misura
        textAlign = Paint.Align.CENTER
    }
    val cx = lato / 2f
    val cy = lato / 2f - (pennello.descent() + pennello.ascent()) / 2f

    val sagoma = Bitmap.createBitmap(lato, lato, Bitmap.Config.ARGB_8888)
    val bianco = Paint(pennello).apply {
        colorFilter = PorterDuffColorFilter(android.graphics.Color.WHITE, PorterDuff.Mode.SRC_IN)
    }
    android.graphics.Canvas(sagoma).apply {
        for (k in 0 until PASSI_BORDO) {
            val angolo = (2.0 * Math.PI * k / PASSI_BORDO).toFloat()
            drawText(emoji, cx + cos(angolo) * bordo, cy + sin(angolo) * bordo, bianco)
        }
        drawText(emoji, cx, cy, bianco)
    }

    val sfocatura = Paint().apply { maskFilter = BlurMaskFilter(bordo * 1.5f, BlurMaskFilter.Blur.NORMAL) }
    val scarto = IntArray(2)
    val ombra = sagoma.extractAlpha(sfocatura, scarto)

    val adesivo = Bitmap.createBitmap(lato, lato, Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(adesivo).apply {
        drawBitmap(ombra, scarto[0].toFloat(), scarto[1] + bordo * 0.8f, Paint().apply { color = COLORE_OMBRA })
        drawBitmap(sagoma, 0f, 0f, null)
        drawText(emoji, cx, cy, pennello)
    }
    ombra.recycle()
    sagoma.recycle()
    return adesivo.asImageBitmap()
}

private const val PASSI_BORDO = 16
private const val COLORE_OMBRA = 0x47000000

/**
 * (0.12) "45 minuti di Studio", "Un'ora e 20 minuti di Studio", "Meno di un
 * minuto di Studio": quanto è durata davvero, senza giudizi. Le parole delle
 * durate sono quelle di sempre (durata_*).
 */
internal fun testoDurataSvolta(context: Context, nome: String, inizio: Long, fine: Long): String {
    val durata = PagineSessione.durata(inizio, fine)
    if (durata.ore == 0L && durata.minuti == 0L) return context.getString(R.string.pagina_fine_meno_di_un_minuto, nome)
    fun ore(n: Long) = if (n == 1L) context.getString(R.string.durata_un_ora) else context.getString(R.string.durata_ore, n)
    fun minuti(n: Long) = if (n == 1L) context.getString(R.string.durata_un_minuto) else context.getString(R.string.durata_minuti, n)
    val tempo = when {
        durata.ore == 0L -> minuti(durata.minuti)
        durata.minuti == 0L -> ore(durata.ore)
        else -> context.getString(R.string.durata_e, ore(durata.ore), minuti(durata.minuti))
    }
    return context.getString(R.string.pagina_fine_durata, tempo, nome).replaceFirstChar { it.uppercase() }
}
