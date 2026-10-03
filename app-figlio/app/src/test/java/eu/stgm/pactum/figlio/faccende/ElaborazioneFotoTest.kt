package eu.stgm.pactum.figlio.faccende

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max

/**
 * (0.13) I conti della foto di una faccenda: lato lungo al massimo 2048 pixel,
 * le proporzioni giuste, il giro dell'orientamento, e nel JPEG che parte
 * niente dati nascosti (EXIF con la posizione, XMP, IPTC, commenti).
 */
class ElaborazioneFotoTest {

    // --- Misure ---------------------------------------------------------------

    private val misureDiProva = listOf(
        8160 to 6120, // 50 MP
        6120 to 8160,
        4000 to 3000,
        4032 to 3024,
        2048 to 1536,
        2049 to 10,
        1920 to 1080,
        640 to 480,
        12000 to 100,
        1 to 1,
    )

    @Test
    fun `il lato lungo finale non supera mai 2048 e le proporzioni restano`() {
        for ((l, a) in misureDiProva) {
            val (lf, af) = ElaborazioneFoto.misureFinali(l, a)
            assertTrue("$l×$a → $lf×$af", max(lf, af) <= 2048)
            assertTrue(lf >= 1 && af >= 1)
            if (max(l, a) > 2048) assertEquals("$l×$a", 2048, max(lf, af))
            // Proporzioni: al più un pixel di arrotondamento.
            val atteso = a.toDouble() * lf / l
            assertTrue("$l×$a → $lf×$af", abs(af - atteso) <= 1.0 || af == 1)
        }
    }

    @Test
    fun `una foto piccola resta com'è`() {
        assertEquals(1920 to 1080, ElaborazioneFoto.misureFinali(1920, 1080))
        assertEquals(1, ElaborazioneFoto.campionamento(1920, 1080))
    }

    @Test
    fun `la lettura rimpicciolita non scende mai sotto i 2048 pixel e usa potenze di 2`() {
        for ((l, a) in misureDiProva) {
            val passo = ElaborazioneFoto.campionamento(l, a)
            assertEquals("passo $passo", 0, passo and (passo - 1))
            val lungo = max(l, a)
            if (lungo > 2048) assertTrue("$l×$a passo $passo", lungo / passo >= 2048)
            assertTrue("$l×$a passo $passo", lungo / (passo * 2) < 2048)
        }
        assertEquals(2, ElaborazioneFoto.campionamento(8160, 6120))
        assertEquals(1, ElaborazioneFoto.campionamento(0, 0))
    }

    @Test
    fun `l'orientamento dello scatto diventa un giro`() {
        assertTrue(ElaborazioneFoto.giro(1).nessuno)
        assertTrue(ElaborazioneFoto.giro(0).nessuno)
        assertEquals(ElaborazioneFoto.Giro(90, false), ElaborazioneFoto.giro(6))
        assertEquals(ElaborazioneFoto.Giro(180, false), ElaborazioneFoto.giro(3))
        assertEquals(ElaborazioneFoto.Giro(270, false), ElaborazioneFoto.giro(8))
        assertEquals(ElaborazioneFoto.Giro(0, true), ElaborazioneFoto.giro(2))
        assertEquals(ElaborazioneFoto.Giro(180, true), ElaborazioneFoto.giro(4))
        assertEquals(ElaborazioneFoto.Giro(90, true), ElaborazioneFoto.giro(5))
        assertEquals(ElaborazioneFoto.Giro(270, true), ElaborazioneFoto.giro(7))
        assertTrue(ElaborazioneFoto.giro(99).nessuno)
    }

    // --- Niente dati nascosti -------------------------------------------------

    /**
     * Il codificatore JPEG del JDK (javax.imageio), preso per riflessione: i
     * test girano sulla JVM, ma si compilano contro le classi di Android, che
     * non hanno java.awt.
     */
    private object Jdk {
        private val bufferedImage = Class.forName("java.awt.image.BufferedImage")
        private val imageIO = Class.forName("javax.imageio.ImageIO")
        private const val TYPE_INT_RGB = 1

        /** Un JPEG vero (lo scrive il JDK), solo l'immagine. */
        fun jpeg(larghezza: Int, altezza: Int): ByteArray {
            val immagine = bufferedImage
                .getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .newInstance(larghezza, altezza, TYPE_INT_RGB)
            val setRgb = bufferedImage.getMethod(
                "setRGB",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            for (x in 0 until larghezza) for (y in 0 until altezza) setRgb.invoke(immagine, x, y, (x * 4 shl 16) or (y * 5 shl 8) or 0x40)
            val uscita = ByteArrayOutputStream()
            val scritto = imageIO
                .getMethod("write", Class.forName("java.awt.image.RenderedImage"), String::class.java, java.io.OutputStream::class.java)
                .invoke(null, immagine, "jpg", uscita) as Boolean
            assertTrue(scritto)
            return uscita.toByteArray()
        }

        /** Le misure di un JPEG letto dal JDK; null se non si legge come immagine. */
        fun misure(jpeg: ByteArray): Pair<Int, Int>? {
            val letta = imageIO.getMethod("read", java.io.InputStream::class.java)
                .invoke(null, ByteArrayInputStream(jpeg)) ?: return null
            val larghezza = bufferedImage.getMethod("getWidth").invoke(letta) as Int
            val altezza = bufferedImage.getMethod("getHeight").invoke(letta) as Int
            return larghezza to altezza
        }
    }

    private fun jpegVero(larghezza: Int = 64, altezza: Int = 48): ByteArray = Jdk.jpeg(larghezza, altezza)

    private fun segmento(marcatore: Int, contenuto: ByteArray): ByteArray {
        val lunghezza = contenuto.size + 2
        return byteArrayOf(0xFF.toByte(), marcatore.toByte(), (lunghezza shr 8).toByte(), lunghezza.toByte()) + contenuto
    }

    /** Lo stesso JPEG con dentro un EXIF con la posizione, un IPTC e un commento, subito dopo l'inizio. */
    private fun conDatiNascosti(jpeg: ByteArray): ByteArray {
        val exif = segmento(0xE1, "Exif\u0000\u0000GPSLatitude 12.3456 GPSLongitude 65.4321 Telefono".toByteArray(Charsets.ISO_8859_1))
        val xmp = segmento(0xE1, "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta>posizione</x:xmpmeta>".toByteArray(Charsets.ISO_8859_1))
        val iptc = segmento(0xED, "Photoshop 3.0\u0000Luca".toByteArray(Charsets.ISO_8859_1))
        val commento = segmento(0xFE, "scattata a casa di Sara".toByteArray(Charsets.ISO_8859_1))
        return jpeg.copyOfRange(0, 2) + exif + xmp + iptc + commento + jpeg.copyOfRange(2, jpeg.size)
    }

    private fun contiene(dati: ByteArray, testo: String): Boolean =
        String(dati, Charsets.ISO_8859_1).contains(testo)

    @Test
    fun `il JPEG scritto da un codificatore ha solo l'immagine`() {
        val jpeg = jpegVero()
        assertTrue(ElaborazioneFoto.eJpeg(jpeg))
        assertEquals(emptyList<Int>(), ElaborazioneFoto.segmentiNascosti(jpeg))
    }

    @Test
    fun `EXIF con la posizione, XMP, IPTC e commenti se ne vanno`() {
        val sporco = conDatiNascosti(jpegVero())
        assertEquals(listOf(0xE1, 0xE1, 0xED, 0xFE), ElaborazioneFoto.segmentiNascosti(sporco))
        val pulito = ElaborazioneFoto.senzaDatiNascosti(sporco)
        assertNotNull(pulito)
        pulito!!
        assertEquals(emptyList<Int>(), ElaborazioneFoto.segmentiNascosti(pulito))
        for (parola in listOf("Exif", "GPSLatitude", "xmpmeta", "Photoshop", "Luca", "Sara")) {
            assertFalse(parola, contiene(pulito, parola))
        }
        // Resta una foto vera, delle stesse misure.
        assertEquals(64 to 48, Jdk.misure(pulito))
    }

    @Test
    fun `la pulizia toglie solo i dati nascosti - l'immagine resta byte per byte`() {
        val originale = jpegVero()
        assertArrayEquals(originale, ElaborazioneFoto.senzaDatiNascosti(conDatiNascosti(originale)))
        assertArrayEquals(originale, ElaborazioneFoto.senzaDatiNascosti(originale))
    }

    @Test
    fun `un file che non è un JPEG non passa`() {
        assertFalse(ElaborazioneFoto.eJpeg("GIF89a".toByteArray()))
        assertFalse(ElaborazioneFoto.eJpeg(ByteArray(0)))
        assertNull(ElaborazioneFoto.senzaDatiNascosti("non è una foto".toByteArray()))
        assertNull(ElaborazioneFoto.segmentiNascosti(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
    }

    @Test
    fun `un JPEG troncato o rovinato non passa`() {
        val jpeg = conDatiNascosti(jpegVero())
        // Tagliato dentro l'EXIF: la lunghezza del segmento va oltre la fine.
        assertNull(ElaborazioneFoto.senzaDatiNascosti(jpeg.copyOfRange(0, 20)))
        // Un byte che non è un marcatore dove ce ne vuole uno.
        val rovinato = jpeg.copyOf().also { it[2] = 0x12 }
        assertNull(ElaborazioneFoto.senzaDatiNascosti(rovinato))
        assertNull(ElaborazioneFoto.segmentiNascosti(rovinato))
    }
}
