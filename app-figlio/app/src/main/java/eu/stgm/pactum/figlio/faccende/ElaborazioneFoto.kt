package eu.stgm.pactum.figlio.faccende

import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * (0.13) I conti della foto di una faccenda (logica pura, provata senza
 * Android): quanto rimpicciolire, come girarla, e il controllo finale che nel
 * JPEG non resti niente di nascosto.
 *
 * La foto la ricodifica Bitmap.compress (FotoFaccenda), che scrive solo
 * l'immagine: niente EXIF, niente posizione, niente modello del telefono.
 * [senzaDatiNascosti] è la cintura in più: toglie dal file ogni segmento
 * APP1–APP15 (EXIF, XMP, IPTC…) e i commenti, se mai ce ne fossero, come fa
 * il server prima di salvarla.
 */
object ElaborazioneFoto {

    /** Il lato lungo massimo della foto mandata (contratto v3.6). */
    const val LATO_MASSIMO = 2048

    /** La qualità JPEG di partenza; se la foto venisse troppo pesante si scende. */
    const val QUALITA = 85
    val QUALITA_DI_RIPIEGO = listOf(75, 65)

    /** Il server accetta al massimo 4 MB: si resta ben sotto. */
    const val BYTE_MASSIMI = 3_500_000

    /**
     * Di quanto rimpicciolire già in lettura (inSampleSize, una potenza di 2):
     * il più possibile, ma senza mai scendere sotto [latoMassimo] sul lato
     * lungo (il resto lo fa la scala precisa dopo).
     */
    fun campionamento(larghezza: Int, altezza: Int, latoMassimo: Int = LATO_MASSIMO): Int {
        val lungo = max(larghezza, altezza)
        if (lungo <= 0) return 1
        var passo = 1
        while (lungo / (passo * 2) >= latoMassimo) passo *= 2
        return passo
    }

    /** Le misure finali: il lato lungo al massimo [latoMassimo], le proporzioni uguali, mai sotto 1 pixel. */
    fun misureFinali(larghezza: Int, altezza: Int, latoMassimo: Int = LATO_MASSIMO): Pair<Int, Int> {
        val lungo = max(larghezza, altezza)
        if (lungo <= latoMassimo || lungo <= 0) return larghezza.coerceAtLeast(1) to altezza.coerceAtLeast(1)
        val scala = latoMassimo.toDouble() / lungo
        val l = (larghezza * scala).roundToInt().coerceIn(1, latoMassimo)
        val a = (altezza * scala).roundToInt().coerceIn(1, latoMassimo)
        return l to a
    }

    /** Come girare la foto: gradi in senso orario e, dopo, uno specchio orizzontale. */
    data class Giro(val gradi: Int, val specchio: Boolean) {
        val nessuno: Boolean get() = gradi == 0 && !specchio
    }

    /**
     * L'orientamento EXIF (1–8, ExifInterface.ORIENTATION_*) tradotto in un
     * giro. Un valore che non si conosce vale "nessun giro".
     */
    fun giro(orientamento: Int): Giro = when (orientamento) {
        2 -> Giro(0, true) // specchio orizzontale
        3 -> Giro(180, false)
        4 -> Giro(180, true) // specchio verticale
        5 -> Giro(90, true) // trasposta
        6 -> Giro(90, false)
        7 -> Giro(270, true) // trasversa
        8 -> Giro(270, false)
        else -> Giro(0, false)
    }

    /** I primi tre byte di ogni JPEG: FF D8 FF (il server controlla gli stessi). */
    fun eJpeg(dati: ByteArray): Boolean =
        dati.size >= 3 && dati[0] == 0xFF.toByte() && dati[1] == 0xD8.toByte() && dati[2] == 0xFF.toByte()

    /**
     * I segmenti con dati nascosti in un JPEG (prima dell'immagine): i marcatori
     * APP1–APP15 (0xE1–0xEF) e i commenti (0xFE). Vuota = pulito. Null se il
     * file non si legge come JPEG.
     */
    fun segmentiNascosti(dati: ByteArray): List<Int>? {
        if (!eJpeg(dati)) return null
        val trovati = mutableListOf<Int>()
        var i = 2
        while (i + 1 < dati.size) {
            if (dati[i] != 0xFF.toByte()) return null
            val marcatore = dati[i + 1].toInt() and 0xFF
            when {
                marcatore == 0xFF -> { i += 1; continue } // riempitivo
                marcatore == 0xD9 || marcatore == 0xDA -> return trovati // fine, o inizio dell'immagine
                marcatore in 0xD0..0xD7 || marcatore == 0x01 -> { i += 2; continue } // senza lunghezza
            }
            if (i + 3 >= dati.size) return null
            val lunghezza = ((dati[i + 2].toInt() and 0xFF) shl 8) or (dati[i + 3].toInt() and 0xFF)
            if (lunghezza < 2 || i + 2 + lunghezza > dati.size) return null
            if (nascosto(marcatore)) trovati += marcatore
            i += 2 + lunghezza
        }
        return null
    }

    /**
     * Il JPEG senza i segmenti con dati nascosti (APP1–APP15, commenti); il
     * resto byte per byte com'era. Null se il file non si legge come JPEG:
     * meglio non mandare niente che mandare una foto che non si sa cosa porta.
     */
    fun senzaDatiNascosti(dati: ByteArray): ByteArray? {
        if (!eJpeg(dati)) return null
        val uscita = ByteArrayOutputStream(dati.size)
        uscita.write(dati, 0, 2)
        var i = 2
        while (i + 1 < dati.size) {
            if (dati[i] != 0xFF.toByte()) return null
            val marcatore = dati[i + 1].toInt() and 0xFF
            when {
                marcatore == 0xFF -> { i += 1; continue }
                marcatore == 0xD9 || marcatore == 0xDA -> {
                    // Da qui in poi è l'immagine (o la fine): si copia tutta.
                    uscita.write(dati, i, dati.size - i)
                    return uscita.toByteArray()
                }
                marcatore in 0xD0..0xD7 || marcatore == 0x01 -> {
                    uscita.write(dati, i, 2)
                    i += 2
                    continue
                }
            }
            if (i + 3 >= dati.size) return null
            val lunghezza = ((dati[i + 2].toInt() and 0xFF) shl 8) or (dati[i + 3].toInt() and 0xFF)
            if (lunghezza < 2 || i + 2 + lunghezza > dati.size) return null
            if (!nascosto(marcatore)) uscita.write(dati, i, 2 + lunghezza)
            i += 2 + lunghezza
        }
        return null
    }

    private fun nascosto(marcatore: Int): Boolean = marcatore in 0xE1..0xEF || marcatore == 0xFE
}
