package eu.stgm.pactum.design

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding

// (0.25, contratto v4.3) Le immagini che un genitore allega a un lavoro di casa, a tutto
// schermo su fondo nero, una pagina per immagine (si scorre di lato). Le stesse nelle due
// app: chi le mostra dà il modo di caricarle ([carica]) e le parole ([TestiVisore]).

/** Le parole del visore: core-design non ha risorse sue. */
data class TestiVisore(
    val chiudi: String,
    val caricamento: String,
    val errore: String,
    val riprova: String,
    /** (numero, quante) → "Immagine 1 di 3". */
    val numero: (Int, Int) -> String,
)

/**
 * Il visore: [titolo] in alto (il lavoro), le immagini [numeri] (i numeri del server,
 * 1..3) caricate una alla volta quando si arriva sulla loro pagina con [carica] (null =
 * non si è riusciti: "Riprova"). I margini di sicurezza dello schermo si prendono FUORI
 * dal dialogo: dentro, su certi telefoni, arrivano a zero (lo stesso difetto della foto
 * del lavoro nell'app del genitore, 0.25).
 */
@Composable
fun VisoreImmagini(
    titolo: String,
    numeri: List<Int>,
    testi: TestiVisore,
    onChiudi: () -> Unit,
    carica: suspend (Int) -> ImageBitmap?,
) {
    if (numeri.isEmpty()) return
    val esterni = WindowInsets.safeDrawing.asPaddingValues()
    val direzione = LocalLayoutDirection.current
    Dialog(
        onDismissRequest = onChiudi,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val interni = WindowInsets.safeDrawing.asPaddingValues()
        val margini = PaddingValues(
            start = maxOf(esterni.calculateStartPadding(direzione), interni.calculateStartPadding(direzione)),
            top = maxOf(esterni.calculateTopPadding(), interni.calculateTopPadding()),
            end = maxOf(esterni.calculateEndPadding(direzione), interni.calculateEndPadding(direzione)),
            bottom = maxOf(esterni.calculateBottomPadding(), interni.calculateBottomPadding()),
        )
        val pagine = rememberPagerState { numeri.size }
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Column(modifier = Modifier.fillMaxSize().padding(margini)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = onChiudi) {
                        Icon(Icons.Filled.Close, testi.chiudi, tint = Color.White)
                    }
                    Text(
                        text = titolo,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = Spazi.l),
                    )
                }
                HorizontalPager(state = pagine, modifier = Modifier.weight(1f).fillMaxWidth()) { pagina ->
                    PaginaImmagine(numeri[pagina], titolo, testi, carica)
                }
                if (numeri.size > 1) {
                    Text(
                        text = testi.numero(pagine.currentPage + 1, numeri.size),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(Spazi.l),
                    )
                }
            }
        }
    }
}

@Composable
private fun PaginaImmagine(n: Int, titolo: String, testi: TestiVisore, carica: suspend (Int) -> ImageBitmap?) {
    var tentativo by remember(n) { mutableIntStateOf(0) }
    var immagine by remember(n) { mutableStateOf<ImageBitmap?>(null) }
    var fallita by remember(n) { mutableStateOf(false) }
    LaunchedEffect(n, tentativo) {
        fallita = false
        immagine = carica(n)
        fallita = immagine == null
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val trovata = immagine
        when {
            trovata != null -> Image(
                bitmap = trovata,
                contentDescription = titolo,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            fallita -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(Spazi.xxl)) {
                Text(testi.errore, color = Color.White, textAlign = TextAlign.Center)
                TextButton(onClick = { tentativo++ }) { Text(testi.riprova, color = Color.White) }
            }
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Text(testi.caricamento, color = Color.White, modifier = Modifier.padding(top = Spazi.s))
            }
        }
    }
}

/**
 * Un JPEG (o altro formato che Android legge) in un'immagine da mostrare, rimpicciolita se
 * più grande di [latoMassimo] pixel per lato (un'immagine da 12 megapixel non serve a
 * schermo). Null se i byte non sono un'immagine.
 */
fun decodificaImmagine(dati: ByteArray, latoMassimo: Int = 2048): ImageBitmap? {
    val misure = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(dati, 0, dati.size, misure)
    if (misure.outWidth <= 0 || misure.outHeight <= 0) return null
    var campione = 1
    while (maxOf(misure.outWidth, misure.outHeight) / (campione * 2) >= latoMassimo) campione *= 2
    val opzioni = BitmapFactory.Options().apply { inSampleSize = campione }
    return BitmapFactory.decodeByteArray(dati, 0, dati.size, opzioni)?.asImageBitmap()
}
