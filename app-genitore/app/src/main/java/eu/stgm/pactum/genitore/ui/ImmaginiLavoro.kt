package eu.stgm.pactum.genitore.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.design.TestiVisore
import eu.stgm.pactum.design.VisoreImmagini
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.Faccenda
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import androidx.compose.runtime.staticCompositionLocalOf

// (0.25, contratto v4.3) Le immagini che il genitore allega a un lavoro di casa: al massimo
// 3, scelte dalla galleria col selettore di foto di Android (nessun permesso da chiedere),
// rimpicciolite e mandate come JPEG. Le vedono il genitore e il telefono del figlio.

/** Quante immagini si allegano a un lavoro (le stesse del server). */
const val IMMAGINI_MASSIME = 3

/**
 * Un'immagine della galleria pronta da mandare: girata nel verso giusto, il lato lungo al
 * massimo [LATO] pixel, JPEG. Null se non si legge. Va chiamata fuori dal filo principale.
 */
object PreparaImmagine {
    private const val LATO = 1600
    private const val MASSIMO_BYTE = 3_500_000

    fun jpeg(context: Context, uri: Uri): ByteArray? = runCatching {
        val immagine = leggi(context, uri) ?: return@runCatching null
        // Di solito basta la prima qualità; una foto piena di dettagli scende un poco.
        for (qualita in listOf(85, 70, 55, 40)) {
            val uscita = ByteArrayOutputStream()
            immagine.compress(Bitmap.CompressFormat.JPEG, qualita, uscita)
            val dati = uscita.toByteArray()
            if (dati.size <= MASSIMO_BYTE) return@runCatching dati
        }
        null
    }.getOrNull()

    /** Una miniatura (lato lungo [lato] pixel) per il modulo. */
    fun miniatura(context: Context, uri: Uri, lato: Int = 256): ImageBitmap? =
        runCatching { leggi(context, uri, lato)?.asImageBitmap() }.getOrNull()

    private fun leggi(context: Context, uri: Uri, lato: Int = LATO): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder gira l'immagine secondo i dati della fotocamera (EXIF).
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val lungo = maxOf(info.size.width, info.size.height)
                if (lungo > lato) {
                    val scala = lato.toFloat() / lungo
                    decoder.setTargetSize((info.size.width * scala).toInt().coerceAtLeast(1), (info.size.height * scala).toInt().coerceAtLeast(1))
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val misure = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, misure) }
            var campione = 1
            while (maxOf(misure.outWidth, misure.outHeight) / (campione * 2) >= lato) campione *= 2
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = campione })
            }
        }
}

/** Le parole del visore delle immagini. */
@Composable
fun testiVisore(): TestiVisore {
    val chiudi = stringResource(R.string.visore_chiudi)
    val caricamento = stringResource(R.string.visore_caricamento)
    val errore = stringResource(R.string.visore_errore)
    val riprova = stringResource(R.string.visore_riprova)
    val modello = stringResource(R.string.visore_numero)
    return remember(chiudi, caricamento, errore, riprova, modello) {
        TestiVisore(chiudi, caricamento, errore, riprova) { n, quante -> String.format(modello, n, quante) }
    }
}

/**
 * La scelta delle immagini nel modulo "Dai lavori di casa": le miniature di quelle scelte
 * (con la ✕ per toglierle) e "Aggiungi immagini" finché ce ne stanno.
 */
@Composable
fun SceltaImmagini(scelte: List<Uri>, onCambia: (List<Uri>) -> Unit) {
    val posti = IMMAGINI_MASSIME - scelte.size
    val galleria = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(IMMAGINI_MASSIME)) { nuove ->
        if (nuove.isNotEmpty()) onCambia((scelte + nuove).distinct().take(IMMAGINI_MASSIME))
    }
    val galleriaUna = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { nuova ->
        if (nuova != null) onCambia((scelte + nuova).distinct().take(IMMAGINI_MASSIME))
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spazi.s)) {
        SopraTitolo(stringResource(R.string.dai_immagini))
        Text(
            text = stringResource(R.string.dai_immagini_spiega, IMMAGINI_MASSIME),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (scelte.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spazi.s)) {
                scelte.forEach { uri -> MiniaturaScelta(uri) { onCambia(scelte - uri) } }
            }
        }
        if (posti > 0) {
            OutlinedButton(onClick = {
                val richiesta = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                // Il selettore di più immagini vuole almeno 2 posti: con uno solo, una.
                if (posti >= 2) galleria.launch(richiesta) else galleriaUna.launch(richiesta)
            }) {
                Text(stringResource(R.string.dai_immagini_aggiungi))
            }
        }
    }
}

@Composable
private fun MiniaturaScelta(uri: Uri, onTogli: () -> Unit) {
    val context = LocalContext.current
    val miniatura by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { PreparaImmagine.miniatura(context, uri) }
    }
    Miniatura(miniatura, stringResource(R.string.dai_immagini_togli), onTogli)
}

@Composable
private fun Miniatura(immagine: ImageBitmap?, descrizioneTogli: String, onTogli: (() -> Unit)?) {
    Box(
        modifier = Modifier
            .size(80.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (immagine != null) {
            Image(bitmap = immagine, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(80.dp))
        }
        if (onTogli != null) {
            IconButton(
                onClick = onTogli,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(32.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(16.dp)),
            ) {
                Icon(Icons.Filled.Close, descrizioneTogli, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * Le immagini di un lavoro nella pagina "Cambia il lavoro": quelle già allegate (dal
 * server, con la ✕ per toglierle) e "Aggiungi immagini" finché ce ne stanno. Ogni gesto
 * parte subito, non con "Salva".
 */
@Composable
fun ImmaginiDelLavoro(faccenda: Faccenda, figlioId: Long?, vm: FaccendeViewModel, invio: Boolean) {
    val posti = IMMAGINI_MASSIME - faccenda.immagini.size
    val galleria = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(IMMAGINI_MASSIME)) { nuove ->
        if (nuove.isNotEmpty()) vm.allegaImmagini(figlioId, faccenda, nuove.take(posti.coerceAtLeast(0)))
    }
    val galleriaUna = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { nuova ->
        if (nuova != null) vm.allegaImmagini(figlioId, faccenda, listOf(nuova))
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spazi.s)) {
        SopraTitolo(stringResource(R.string.dai_immagini))
        if (faccenda.immagini.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spazi.s)) {
                faccenda.immagini.forEach { immagine ->
                    val anteprima by produceState<ImageBitmap?>(null, faccenda.id, immagine.n, immagine.ts) {
                        value = vm.caricaImmagine(faccenda.id, immagine.n)
                    }
                    Miniatura(anteprima, stringResource(R.string.dai_immagini_togli)) {
                        if (!invio) vm.togliImmagine(figlioId, faccenda, immagine.n)
                    }
                }
            }
        }
        if (posti > 0) {
            OutlinedButton(enabled = !invio, onClick = {
                val richiesta = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                if (posti >= 2) galleria.launch(richiesta) else galleriaUna.launch(richiesta)
            }) {
                Text(stringResource(R.string.dai_immagini_aggiungi))
            }
        }
    }
}

/**
 * Come si carica un'immagine allegata (id del lavoro, numero) nelle schede dei lavori:
 * la dà la pagina dei lavori, così le schede non devono conoscere il ViewModel.
 */
val LocalCaricaImmagine = staticCompositionLocalOf<(suspend (Long, Int) -> ImageBitmap?)?> { null }

/** "Guarda le immagini (2)" sotto un lavoro che ne ha, e il visore a tutto schermo. */
@Composable
fun PulsanteImmagini(faccenda: Faccenda) {
    val carica = LocalCaricaImmagine.current
    if (faccenda.immagini.isEmpty() || carica == null) return
    var aperto by remember { mutableStateOf(false) }
    TextButton(onClick = { aperto = true }) {
        Text(stringResource(R.string.lavoro_immagini_guarda, faccenda.immagini.size))
    }
    if (aperto) {
        VisoreImmagini(
            titolo = faccenda.titolo,
            numeri = faccenda.immagini.map { it.n },
            testi = testiVisore(),
            onChiudi = { aperto = false },
            carica = { n -> carica(faccenda.id, n) },
        )
    }
}
