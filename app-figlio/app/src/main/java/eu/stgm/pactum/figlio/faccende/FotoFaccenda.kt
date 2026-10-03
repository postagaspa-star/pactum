package eu.stgm.pactum.figlio.faccende

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * (0.13) La foto di una faccenda, dallo scatto al file da mandare.
 *
 * Solo dalla fotocamera, in quel momento: Pactum chiede uno scatto
 * (ActivityResultContracts.TakePicture) su un file suo, condiviso con la
 * fotocamera tramite il FileProvider ([nuovoScatto], [uriScatto]). Mai dalla
 * galleria: nella pagina non c'è nessuna strada per sceglierne una.
 *
 * Poi ([prepara]): l'orientamento dallo scatto, la lettura già rimpicciolita,
 * il giro, il lato lungo al massimo 2048 pixel, il JPEG di qualità 85 scritto
 * da Bitmap.compress (solo l'immagine: niente EXIF, niente posizione) e, come
 * cintura, la pulizia dei segmenti nascosti (ElaborazioneFoto). Lo scatto
 * originale si cancella sempre, riuscito o no.
 */
object FotoFaccenda {

    private const val CARTELLA = "faccende"
    private const val SCATTI = "scatti"
    private const val PRONTE = "foto"

    /** Gli scatti originali: la cartella che il FileProvider condivide con la fotocamera (res/xml/file_condivisi.xml). */
    fun cartellaScatti(context: Context): File =
        File(File(context.applicationContext.filesDir, CARTELLA), SCATTI).apply { mkdirs() }

    /** Le foto pronte, in coda per partire. */
    fun cartellaPronte(context: Context): File =
        File(File(context.applicationContext.filesDir, CARTELLA), PRONTE).apply { mkdirs() }

    /** Un file vuoto per lo scatto che sta per partire. */
    fun nuovoScatto(context: Context): File = File(cartellaScatti(context), "scatto-${UUID.randomUUID()}.jpg")

    /** Lo scatto di prima che non serve più (annullato, o il processo morto a metà): via quelli più vecchi di un giorno. */
    fun pulisciScattiVecchi(context: Context, adesso: Long = System.currentTimeMillis()) {
        runCatching {
            cartellaScatti(context).listFiles()?.forEach { f ->
                if (adesso - f.lastModified() > UN_GIORNO_MS) f.delete()
            }
        }
    }

    /**
     * Dallo scatto [originale] alla foto pronta in [cartellaPronte]: il nome
     * del file pronto, null se non è riuscito (lo scatto vuoto, una foto che
     * non si legge, la memoria finita). L'originale si cancella in ogni caso.
     */
    fun prepara(context: Context, originale: File): String? {
        try {
            if (!originale.exists() || originale.length() == 0L) return null
            val bytes = codifica(originale) ?: return null
            val nome = "foto-${UUID.randomUUID()}.jpg"
            val destinazione = File(cartellaPronte(context), nome)
            val temp = File(destinazione.parentFile, "$nome.tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(destinazione)) {
                temp.delete()
                return null
            }
            return nome
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        } finally {
            originale.delete()
        }
    }

    /** Il JPEG pronto da mandare, null se non si riesce. */
    private fun codifica(originale: File): ByteArray? {
        val orientamento = try {
            ExifInterface(originale.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val misure = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(originale.absolutePath, misure)
        if (misure.outWidth <= 0 || misure.outHeight <= 0) return null
        var passo = ElaborazioneFoto.campionamento(misure.outWidth, misure.outHeight)
        var letta: Bitmap? = null
        // Con poca memoria si rimpicciolisce di più già in lettura.
        while (passo <= PASSO_MASSIMO) {
            try {
                letta = BitmapFactory.decodeFile(originale.absolutePath, BitmapFactory.Options().apply { inSampleSize = passo })
                break
            } catch (e: OutOfMemoryError) {
                passo *= 2
            }
        }
        val sorgente = letta ?: return null
        val pronta = try {
            trasforma(sorgente, ElaborazioneFoto.giro(orientamento))
        } catch (e: OutOfMemoryError) {
            sorgente.recycle()
            return null
        }
        // trasforma può restituire la stessa bitmap: si ricicla una volta sola.
        try {
            for (qualita in listOf(ElaborazioneFoto.QUALITA) + ElaborazioneFoto.QUALITA_DI_RIPIEGO) {
                val uscita = ByteArrayOutputStream()
                if (!pronta.compress(Bitmap.CompressFormat.JPEG, qualita, uscita)) return null
                val pulita = ElaborazioneFoto.senzaDatiNascosti(uscita.toByteArray()) ?: return null
                if (pulita.size <= ElaborazioneFoto.BYTE_MASSIMI) return pulita
            }
            return null
        } finally {
            if (pronta !== sorgente) pronta.recycle()
            sorgente.recycle()
        }
    }

    /** Rimpicciolita (lato lungo ≤ 2048) e girata come dice lo scatto, in un passo solo. */
    private fun trasforma(sorgente: Bitmap, giro: ElaborazioneFoto.Giro): Bitmap {
        val (larghezza, altezza) = ElaborazioneFoto.misureFinali(sorgente.width, sorgente.height)
        val matrice = Matrix()
        matrice.postScale(larghezza.toFloat() / sorgente.width, altezza.toFloat() / sorgente.height)
        if (giro.gradi != 0) matrice.postRotate(giro.gradi.toFloat())
        if (giro.specchio) matrice.postScale(-1f, 1f)
        if (matrice.isIdentity) return sorgente
        return Bitmap.createBitmap(sorgente, 0, 0, sorgente.width, sorgente.height, matrice, true)
    }

    private const val PASSO_MASSIMO = 64
    private const val UN_GIORNO_MS = 24L * 60 * 60 * 1000
}
