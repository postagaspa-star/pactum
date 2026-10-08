package eu.stgm.pactum.design

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// (0.19) Gli adesivi delle pagine della Sessione (0.12): le emoji col bordo
// bianco e un'ombra morbida. Il disegno sta qui, in core-design; lo usano solo
// le pagine animate di inizio e fine Sessione (niente emoji nel resto dell'app).

/** Il disegno degli adesivi. */
object Adesivi {

    /**
     * Un adesivo: l'emoji con il bordo bianco e un'ombra morbida, disegnata in
     * un quadrato di [lato] px (l'emoji è poco più di metà del lato: il resto è
     * bordo, ombra e aria per il dondolio). Il bordo è l'emoji tinta di bianco e
     * ripetuta tutto intorno; l'ombra è quella sagoma sfocata, un po' più in basso.
     */
    fun disegna(emoji: String, lato: Int): ImageBitmap {
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
                val angolo = (2.0 * PI * k / PASSI_BORDO).toFloat()
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
}
