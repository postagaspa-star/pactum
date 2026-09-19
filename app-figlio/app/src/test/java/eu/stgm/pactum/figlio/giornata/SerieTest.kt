package eu.stgm.pactum.figlio.giornata

import eu.stgm.pactum.design.GiornoPatto
import eu.stgm.pactum.design.Segnale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Serie e record: le due cifre che il ragazzo legge per prime in Oggi. La
 * striscia del server è lunga 8 giorni; la serie no. E il record non deve
 * scendere MAI — "una serie che azzera è un ottimo motivo per disinstallare".
 */
class SerieTest {

    private val M = Segnale.MANTENUTA
    private val F = Segnale.FUORI_REGOLA
    private val N = Segnale.NESSUN_DATO

    /** Una striscia che finisce il giorno [oggi], dal più vecchio a oggi. */
    private fun striscia(oggi: String, vararg segnali: Segnale): List<GiornoPatto> {
        val fine = LocalDate.parse(oggi)
        return segnali.mapIndexed { i, s ->
            GiornoPatto(fine.minusDays((segnali.size - 1 - i).toLong()).toString(), s)
        }
    }

    // --- record ---

    @Test
    fun `il record non scende mai quando la serie si rompe`() {
        assertEquals(12, Serie.record(precedente = 12, serie = 0))
        assertEquals(12, Serie.record(precedente = 12, serie = 3))
    }

    @Test
    fun `il record sale con la serie`() {
        assertEquals(13, Serie.record(precedente = 12, serie = 13))
        assertEquals(1, Serie.record(precedente = 0, serie = 1))
    }

    @Test
    fun `un numero storto non porta il record sotto zero`() {
        assertEquals(0, Serie.record(precedente = 0, serie = -4))
    }

    // --- serie ---

    @Test
    fun `senza memoria la serie e' quella della striscia`() {
        val giorni = striscia("2026-09-19", F, M, M, M, M, M, M, M)
        assertEquals(SerieSalvata("2026-09-19", 7), Serie.calcola(giorni, null))
    }

    @Test
    fun `una serie a zero cancella la memoria`() {
        val giorni = striscia("2026-09-19", M, M, M, M, M, M, M, F)
        assertNull(Serie.calcola(giorni, SerieSalvata("2026-09-18", 20)))
    }

    @Test
    fun `oggi senza dati non rompe la serie e non la allunga`() {
        val giorni = striscia("2026-09-19", F, M, M, M, M, M, M, N)
        assertEquals(SerieSalvata("2026-09-18", 6), Serie.calcola(giorni, null))
    }

    @Test
    fun `la memoria allunga la serie oltre gli 8 giorni della striscia`() {
        // Ieri la serie era 12 (fino al 18); oggi la finestra è tutta mantenuta.
        val giorni = striscia("2026-09-19", M, M, M, M, M, M, M, M)
        val salvata = SerieSalvata("2026-09-18", 12)
        assertEquals(SerieSalvata("2026-09-19", 13), Serie.calcola(giorni, salvata))
    }

    @Test
    fun `ricalcolare lo stesso giorno non conta due volte`() {
        val giorni = striscia("2026-09-19", M, M, M, M, M, M, M, M)
        val salvata = SerieSalvata("2026-09-19", 13)
        assertEquals(SerieSalvata("2026-09-19", 13), Serie.calcola(giorni, salvata))
    }

    @Test
    fun `la memoria si attacca anche dal giorno prima della finestra`() {
        // La finestra parte il 12; la memoria finisce l'11 con 5 giorni.
        val giorni = striscia("2026-09-19", M, M, M, M, M, M, M, M)
        val salvata = SerieSalvata("2026-09-11", 5)
        assertEquals(SerieSalvata("2026-09-19", 13), Serie.calcola(giorni, salvata))
    }

    @Test
    fun `un buco di giorni mai visti non si conta`() {
        // La memoria finisce il 9: il 10 e l'11 nessuno li ha visti.
        val giorni = striscia("2026-09-19", M, M, M, M, M, M, M, M)
        val salvata = SerieSalvata("2026-09-09", 30)
        assertEquals(SerieSalvata("2026-09-19", 8), Serie.calcola(giorni, salvata))
    }

    @Test
    fun `una rottura dentro la finestra vince sulla memoria`() {
        val giorni = striscia("2026-09-19", M, M, M, F, M, M, M, M)
        val salvata = SerieSalvata("2026-09-18", 40)
        assertEquals(SerieSalvata("2026-09-19", 4), Serie.calcola(giorni, salvata))
    }

    @Test
    fun `una memoria piu' corta del vero non accorcia la serie`() {
        // Un giorno grigio diventato verde quando è arrivata la sua fotografia.
        val giorni = striscia("2026-09-19", M, M, M, M, M, M, M, M)
        val salvata = SerieSalvata("2026-09-17", 2)
        assertEquals(SerieSalvata("2026-09-19", 8), Serie.calcola(giorni, salvata))
    }

    // --- conOggi (chiusura della sera) ---

    @Test
    fun `la sera conta oggi come mantenuto`() {
        val giorni = striscia("2026-09-19", F, M, M, M, M, M, M, N)
        assertEquals(7, Serie.conOggi(giorni, null, "2026-09-19"))
    }

    @Test
    fun `la sera allunga una striscia ferma a ieri`() {
        val giorni = striscia("2026-09-18", F, M, M, M, M, M, M, M)
        assertEquals(8, Serie.conOggi(giorni, null, "2026-09-19"))
    }

    @Test
    fun `la sera con la memoria arriva al nono giorno`() {
        val giorni = striscia("2026-09-19", M, M, M, M, M, M, M, N)
        assertEquals(9, Serie.conOggi(giorni, SerieSalvata("2026-09-18", 8), "2026-09-19"))
    }

    @Test
    fun `una striscia vecchia non da' numeri inventati`() {
        val giorni = striscia("2026-09-15", M, M, M, M, M, M, M, M)
        assertNull(Serie.conOggi(giorni, null, "2026-09-19"))
    }
}
