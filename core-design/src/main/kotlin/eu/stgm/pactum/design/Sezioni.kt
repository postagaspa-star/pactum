package eu.stgm.pactum.design

import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// (0.19) Pactum più vivo e colorato (richiesta di Andrea, 08/10): ogni sezione
// delle due app ha il suo colore pastello e i suoi adesivi, nello stesso stile
// delle pagine animate delle Sessioni (fondi pastello, emoji col bordo bianco
// che entrano e galleggiano). Qui il colore; gli adesivi in Adesivi.kt, i
// movimenti in Movimento.kt. Le schermate di blocco NON usano niente di questo:
// restano calme (una festa mentre sei bloccato sembrerebbe una presa in giro).

/**
 * Una sezione delle app: il [fondo] pastello (la barra in alto e la sfumatura
 * sotto), l'[inchiostro] scuro della stessa famiglia (titolo e icone sopra il
 * fondo, contrasto ≥ 4,5:1, lo prova SezioniTest) e le sue [emoji] (la prima è
 * quella delle pagine vuote). I fondi sono della stessa famiglia di quelli
 * delle Sessioni ([TemaSessione]): lo Studio ha proprio lo stesso verde acqua.
 *
 * I colori del patto (la striscia degli 8 giorni) restano quelli di
 * [ColoriPatto]: qui niente verde "mantenuta" né terracotta.
 */
enum class Sezione(val fondo: Color, val inchiostro: Color, val emoji: List<String>) {
    /** Oggi (figlio). */
    OGGI(Color(0xFFFBE9B7), Color(0xFF574000), listOf("☀️", "🌈", "⭐")),

    /** La Panoramica del genitore. */
    PANORAMICA(Color(0xFFFBE9B7), Color(0xFF574000), listOf("🔭", "🏡", "⭐")),

    /** Il tempo d'uso. */
    TEMPO(Color(0xFFD7E5F2), Color(0xFF1A3A5C), listOf("⏱️", "📊", "⌛")),

    /** Le regole del patto. */
    REGOLE(Color(0xFFE6DFF5), Color(0xFF3F2E6E), listOf("🤝", "📜", "✅")),

    /** Le Sessioni. */
    SESSIONI(Color(0xFFF7E0CF), Color(0xFF6A3418), listOf("🎯", "🎧", "⏳")),

    /** I lavori di casa. */
    LAVORI(Color(0xFFDDECC8), Color(0xFF2F4A14), listOf("🧽", "🧺", "🪴")),

    /** La Sessione Studio: lo stesso fondo del tema Studio delle Sessioni. */
    STUDIO(Color(0xFFCFE9E1), Color(0xFF124D40), listOf("📚", "✏️", "🧠")),

    /** "Da decidere" del genitore: proposte, approvazioni. */
    DECIDERE(Color(0xFFF6DCE3), Color(0xFF6B2238), listOf("💬", "🤔", "📨")),

    /** Le notifiche. */
    NOTIFICHE(Color(0xFFF3E3C4), Color(0xFF5A4214), listOf("🔔", "📬")),

    /** Lo storico (proposte, dichiarazioni, lavori passati). */
    STORICO(Color(0xFFEDE4D3), Color(0xFF4A3B22), listOf("📖", "🗓️")),

    /** I siti visitati (figlio). */
    SITI(Color(0xFFD9E8EE), Color(0xFF1D3E4C), listOf("🌐", "🔍")),

    /** "Cosa vedono i tuoi genitori" (figlio). */
    COSA_VEDE(Color(0xFFDCEEEA), Color(0xFF183F38), listOf("👀", "🔭")),

    /** Le impostazioni e il primo collegamento. */
    IMPOSTAZIONI(Color(0xFFDDE3EA), Color(0xFF2A3644), listOf("⚙️", "🔧")),
    ;

    /** L'emoji delle pagine vuote della sezione. */
    val emojiPrincipale: String get() = emoji.first()
}

/** La sezione della schermata in cui ci si trova (null = fuori da [SchermataColorata]). */
val LocalSezione = staticCompositionLocalOf<Sezione?> { null }

/**
 * Quando è comparsa la schermata (orologio di [SystemClock.uptimeMillis]): le
 * card che nascono nei primi istanti entrano a cascata, quelle che arrivano
 * dopo (scorrendo) compaiono e basta. Null fuori da [SchermataColorata]: lì
 * niente entrate (le barriere, i dialoghi).
 */
internal val LocalInizioSchermata = staticCompositionLocalOf<Long?> { null }

/**
 * Il vestito di una schermata: sotto la barra in alto il [Sezione.fondo]
 * pastello, che sfuma nel fondo della pagina in [AltezzaSfumatura]. Dentro, la
 * barra prende i colori della sezione ([coloriBarra], [TitoloBarra]) e le card
 * entrano a cascata. Lo `Scaffold` della schermata va messo dentro con
 * `containerColor = Color.Transparent`, così la sfumatura si vede.
 */
@Composable
fun SchermataColorata(
    sezione: Sezione,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val inizio = remember(sezione) { SystemClock.uptimeMillis() }
    val pagina = MaterialTheme.colorScheme.background
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(pagina)
            .drawBehind {
                val alto = AltezzaSfumatura.toPx().coerceAtMost(size.height)
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to sezione.fondo,
                        0.32f to sezione.fondo,
                        1f to pagina,
                        startY = 0f,
                        endY = alto,
                    ),
                    topLeft = Offset.Zero,
                    size = Size(size.width, alto),
                )
            },
    ) {
        CompositionLocalProvider(
            LocalSezione provides sezione,
            LocalInizioSchermata provides inizio,
        ) {
            content()
        }
    }
}

/** Fin dove arriva il colore della sezione, dall'alto: la barra e un po' sotto. */
private val AltezzaSfumatura = 240.dp

/**
 * I colori della barra in alto dentro una [SchermataColorata]: trasparente
 * (sotto c'è già il pastello della sezione), titolo e icone nell'inchiostro
 * della sezione. Fuori da una sezione, quelli di sempre.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun coloriBarra(): TopAppBarColors {
    val sezione = LocalSezione.current ?: return TopAppBarDefaults.topAppBarColors()
    return TopAppBarDefaults.topAppBarColors(
        containerColor = Color.Transparent,
        scrolledContainerColor = sezione.fondo,
        titleContentColor = sezione.inchiostro,
        navigationIconContentColor = sezione.inchiostro,
        actionIconContentColor = sezione.inchiostro,
    )
}

/**
 * Il titolo della barra in alto con, accanto, gli adesivi della sezione che
 * entrano e galleggiano (si toccano: saltano di nuovo). Il titolo sta su una
 * riga (si rimpicciolisce se serve, mai tagliato); gli adesivi non spostano
 * il titolo e TalkBack non li legge.
 */
@Composable
fun TitoloBarra(
    testo: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleLarge,
    adesivi: Int = 2,
) {
    val sezione = LocalSezione.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TestoSuUnaRiga(
            testo = testo,
            style = style,
            minimo = 16.sp,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (sezione != null && adesivi > 0) {
            GruppoAdesivi(emoji = sezione.emoji.take(adesivi), lato = LatoAdesivoBarra)
        }
    }
}

/** Gli adesivi nella barra in alto: l'emoji dentro è circa metà del lato. */
private val LatoAdesivoBarra = 40.dp

/**
 * true = Android lascia muovere le cose ("Rimuovi animazioni" spento, scala
 * delle animazioni diversa da zero). Con le animazioni tolte tutto resta fermo
 * al suo posto finale: le stesse regole della pagina della Sessione.
 */
@Composable
fun animazioniAttive(): Boolean {
    val context = LocalContext.current
    return remember(context) { animazioniAttive(scalaAnimazioni(context)) }
}

/** Funzione pura: la scala delle animazioni di Android → si muove o no. */
fun animazioniAttive(scala: Float): Boolean = scala != 0f

private fun scalaAnimazioni(context: android.content.Context): Float =
    runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)
