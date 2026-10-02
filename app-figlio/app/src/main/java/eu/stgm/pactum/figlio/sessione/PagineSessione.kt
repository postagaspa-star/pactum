package eu.stgm.pactum.figlio.sessione

import java.util.Random
import kotlin.math.abs
import kotlin.math.PI

/**
 * (0.12) Le pagine animate di inizio e fine di una Sessione: quando si
 * mostrano (logica pura). La pagina la disegna PaginaSessioneActivity.
 *
 *  - Inizio: appena il server conferma "Inizia", in Pactum.
 *  - Fine con "Termina la sessione" in Pactum: lì, subito.
 *  - Fine da sola (o chiusa dal server): sopra l'app in uso, come la
 *    barriera ("Mostra sopra le altre app"), SOLO nei primi 10 minuti dalla
 *    fine, a schermo acceso e sbloccato (anche allo sblocco, se la sessione
 *    è finita a schermo spento in quei 10 minuti). Altrimenti una notifica, e
 *    la pagina alla prossima apertura di Pactum, entro 2 ore dalla fine. Una
 *    pagina già avvisata con la notifica non si apre più sopra le altre app.
 *    Mai durante una chiamata. Una volta sola: "fatta" la segna la pagina
 *    quando arriva sullo schermo.
 */
object PagineSessione {

    /** La pagina della fine solo entro 2 ore: mai una sessione di ore fa. */
    const val VALIDITA_FINE_MS = 2L * 60 * 60 * 1000

    /** La pagina dell'inizio solo se la conferma è di adesso (il ragazzo è ancora lì). */
    const val VALIDITA_INIZIO_MS = 2L * 60 * 1000

    /** Quanto resta la pagina, se nessuno la tocca (di più se lo chiede l'accessibilità). */
    const val DURATA_PAGINA_MS = 3_500L

    /** Sopra le altre app solo nei primi 10 minuti dalla fine (decisione del coordinatore, dopo lo spavento della 0.9). */
    const val FINESTRA_SOPRA_MS = 10L * 60 * 1000

    /** Come mostrare adesso la pagina della fine. */
    enum class Come {
        /** Pactum è davanti: la pagina si apre lì. */
        APRI_IN_PACTUM,

        /** Sopra l'app in uso: solo nei primi 10 minuti dalla fine. */
        APRI_SOPRA,

        /** Una notifica: la pagina si vede alla prossima apertura di Pactum. */
        NOTIFICA,

        /** Schermo spento, bloccato, una chiamata: al prossimo sblocco. */
        ASPETTA,

        /** La notifica c'è già: si aspetta che il ragazzo apra Pactum. */
        NIENTE,
    }

    /**
     * La sessione di cui mostrare adesso la pagina della fine: finita, entro
     * 2 ore, ancora da mostrare. Se ce n'è più d'una, l'ultima finita.
     */
    fun daMostrare(svolte: List<SvoltaLocale>, adesso: Long): SvoltaLocale? =
        svolte.asSequence()
            .filter { it.paginaFine in MemoriaSessioni.DA_MOSTRARE }
            .filter { it.fine <= adesso && adesso - it.fine < VALIDITA_FINE_MS }
            .maxByOrNull { it.fine }

    /**
     * Come mostrarla adesso: mai a schermo spento o bloccato, mai sopra una
     * chiamata. Con Pactum davanti, lì (entro le 2 ore). Sopra un'altra app
     * solo con "Mostra sopra le altre app", nei primi 10 minuti dalla fine e
     * se non è già partita la notifica; altrimenti la notifica, una volta.
     */
    fun come(
        svolta: SvoltaLocale,
        adesso: Long,
        schermoAcceso: Boolean,
        sbloccato: Boolean,
        inChiamata: Boolean,
        mostraSopra: Boolean,
        pactumDavanti: Boolean,
    ): Come = when {
        !schermoAcceso || !sbloccato || inChiamata -> Come.ASPETTA
        pactumDavanti -> Come.APRI_IN_PACTUM
        svolta.paginaFine == PaginaFine.AVVISATA -> Come.NIENTE
        mostraSopra && adesso - svolta.fine < FINESTRA_SOPRA_MS -> Come.APRI_SOPRA
        else -> Come.NOTIFICA
    }

    /** Fra quanto la notifica "Sessione finita" non serve più: alle 2 ore dalla fine (almeno un secondo). */
    fun validaAncora(svolta: SvoltaLocale, adesso: Long): Long =
        (svolta.fine + VALIDITA_FINE_MS - adesso).coerceAtLeast(1_000)

    /**
     * Quanto resta la pagina: 3,5 secondi, o di più se l'accessibilità del
     * telefono lo chiede ([raccomandata], AccessibilityManager).
     */
    fun durataPagina(raccomandata: Int?): Long = maxOf(DURATA_PAGINA_MS, raccomandata?.toLong() ?: 0L)

    /** La pagina dell'inizio: solo per una sessione appena confermata (l'orologio del server può sbagliare di poco). */
    fun inizioDaMostrare(svolta: SvoltaLocale?, adesso: Long): Boolean =
        svolta != null && abs(adesso - svolta.inizio) <= VALIDITA_INIZIO_MS

    /** Quanto è durata davvero: ore e minuti interi (meno di un minuto = 0 e 0). */
    fun durata(inizio: Long, fine: Long): DurataSvolta {
        val minuti = (fine - inizio).coerceAtLeast(0) / 60_000
        return DurataSvolta(ore = minuti / 60, minuti = minuti % 60)
    }

    /** Chiusa prima della fine prevista: dal ragazzo ("Termina la sessione") o dal server. */
    fun chiusaPrima(svolta: SvoltaLocale): Boolean =
        svolta.fineLocale != null || svolta.chiusura == ChiusureSessione.TERMINATA

    /** "Rimuovi animazioni" del sistema (durata delle animazioni a 0): adesivi fermi, stesse parole. */
    fun statiche(scalaAnimazioni: Float): Boolean = scalaAnimazioni == 0f
}

/** (0.12) Quanto è durata una sessione: "1 ora e 30 minuti". */
data class DurataSvolta(val ore: Long, val minuti: Long)

/** (0.12) Da dove entra un adesivo. */
enum class Bordo { SINISTRA, DESTRA, SOPRA, SOTTO }

/**
 * (0.12) Un adesivo della pagina: l'emoji [emoji] del tema (un indice), al
 * punto ([x], [y]) in frazioni dello schermo, grande [lato] dp e ruotato di
 * [rotazione] gradi. Entra dal [bordo] più vicino dopo [ritardo] (frazione
 * dell'entrata), poi galleggia di [galleggia] dp e dondola, con la sua
 * [velocita] e la sua [fase]: mai tutti insieme.
 */
data class Adesivo(
    val emoji: Int,
    val x: Float,
    val y: Float,
    val lato: Float,
    val rotazione: Float,
    val bordo: Bordo,
    val ritardo: Float,
    val galleggia: Float,
    val velocita: Float,
    val fase: Float,
) {
    /** Da dove parte, fuori dallo schermo (calcolato una volta, non a ogni fotogramma). */
    val xDa: Float = when (bordo) {
        Bordo.SINISTRA -> -FUORI
        Bordo.DESTRA -> 1f + FUORI
        else -> x
    }
    val yDa: Float = when (bordo) {
        Bordo.SOPRA -> -FUORI
        Bordo.SOTTO -> 1f + FUORI
        else -> y
    }

    private companion object {
        const val FUORI = 0.15f
    }
}

/**
 * (0.12) Gli adesivi di una pagina (logica pura): da 14 a 20, sparsi intorno
 * alla scheda al centro (mai sopra), di misure e inclinazioni diverse. Sempre
 * gli stessi per lo stesso [seme]: una rotazione dello schermo non li rimescola.
 */
object AdesiviSessione {

    const val MINIMI = 14
    const val MASSIMI = 20

    /** Le misure degli adesivi, in dp. */
    const val LATO_MINIMO_DP = 36f
    const val LATO_MASSIMO_DP = 62f

    /** L'inclinazione massima, in gradi, da una parte e dall'altra. */
    const val ROTAZIONE_MASSIMA = 20f

    /** Il ritardo massimo di entrata (frazione): l'ultimo entra dopo un terzo del tempo. */
    const val RITARDO_MASSIMO = 0.35f

    /** La zona della scheda al centro, in frazioni dello schermo: lì niente adesivi. */
    const val SCHEDA_X0 = 0.08f
    const val SCHEDA_X1 = 0.92f
    const val SCHEDA_Y0 = 0.30f
    const val SCHEDA_Y1 = 0.70f

    /** Distanza minima tra due adesivi (in larghezze di schermo; l'altezza vale circa il doppio). */
    private const val DISTANZA_MINIMA = 0.16f
    private const val TENTATIVI = 40

    fun disponi(quanteEmoji: Int, seme: Long): List<Adesivo> {
        val caso = Random(seme)
        val quanti = MINIMI + caso.nextInt(MASSIMI - MINIMI + 1)
        val messi = ArrayList<Adesivo>(quanti)
        for (i in 0 until quanti) {
            var x = 0f
            var y = 0f
            for (tentativo in 0 until TENTATIVI) {
                x = 0.05f + caso.nextFloat() * 0.90f
                y = 0.04f + caso.nextFloat() * 0.92f
                if (inScheda(x, y)) continue
                if (messi.none { distanza(it.x, it.y, x, y) < DISTANZA_MINIMA }) break
            }
            // Ancora sulla scheda dopo tutti i tentativi: subito sopra o sotto.
            if (inScheda(x, y)) y = if (y < 0.5f) SCHEDA_Y0 - 0.05f else SCHEDA_Y1 + 0.05f
            messi += Adesivo(
                emoji = if (quanteEmoji > 0) i % quanteEmoji else 0,
                x = x,
                y = y,
                lato = LATO_MINIMO_DP + caso.nextFloat() * (LATO_MASSIMO_DP - LATO_MINIMO_DP),
                rotazione = (caso.nextFloat() * 2f - 1f) * ROTAZIONE_MASSIMA,
                bordo = bordoVicino(x, y),
                ritardo = caso.nextFloat() * RITARDO_MASSIMO,
                galleggia = 3f + caso.nextFloat() * 6f,
                velocita = 0.8f + caso.nextFloat() * 0.6f,
                fase = caso.nextFloat() * DUE_PI,
            )
        }
        return messi
    }

    fun inScheda(x: Float, y: Float): Boolean = x in SCHEDA_X0..SCHEDA_X1 && y in SCHEDA_Y0..SCHEDA_Y1

    /** Il bordo più vicino (l'altezza dello schermo vale circa il doppio della larghezza). */
    fun bordoVicino(x: Float, y: Float): Bordo {
        val sinistra = x
        val destra = 1f - x
        val sopra = y * 2f
        val sotto = (1f - y) * 2f
        val minimo = minOf(minOf(sinistra, destra), minOf(sopra, sotto))
        return when (minimo) {
            sinistra -> Bordo.SINISTRA
            destra -> Bordo.DESTRA
            sopra -> Bordo.SOPRA
            else -> Bordo.SOTTO
        }
    }

    /**
     * Quanto è entrato un adesivo a [t] (0..1 dell'entrata), dato il suo
     * [ritardo]: 0 fermo fuori, 1 al suo posto, un po' oltre a metà strada
     * (il rimbalzo).
     */
    fun entrata(t: Float, ritardo: Float): Float {
        val p = ((t - ritardo) / (1f - RITARDO_MASSIMO)).coerceIn(0f, 1f)
        return rimbalzo(p)
    }

    /** La scheda al centro: arriva per prima, con un piccolo rimbalzo (scala da 0,8 a 1). */
    fun scalaScheda(t: Float): Float = 0.8f + 0.2f * rimbalzo((t / 0.4f).coerceIn(0f, 1f))

    fun alfaScheda(t: Float): Float = (t / 0.25f).coerceIn(0f, 1f)

    /** "Ease out back": arriva, va un filo oltre, torna. 0 → 0, 1 → 1. */
    fun rimbalzo(p: Float): Float {
        val q = p - 1f
        return 1f + C3 * q * q * q + C1 * q * q
    }

    private fun distanza(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = (y1 - y2) * 2f
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private const val C1 = 1.70158f
    private const val C3 = C1 + 1f

    /** Un giro intero, in radianti. */
    const val DUE_PI = (2 * PI).toFloat()
}
