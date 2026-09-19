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

    // --- la striscia più vecchia della memoria (copia locale senza rete) ---

    @Test
    fun `una striscia piu' vecchia della memoria non accorcia la serie`() {
        // La memoria arriva al 19 (12 giorni); senza rete si rilegge la copia del 17.
        val vecchia = striscia("2026-09-17", M, M, M, M, M, M, M, M)
        val salvata = SerieSalvata("2026-09-19", 12)
        assertEquals(salvata, Serie.calcola(vecchia, salvata))
        assertEquals(salvata, Serie.memoria(vecchia, salvata))
    }

    @Test
    fun `una striscia vecchia che finisce rossa non cancella una memoria piu' nuova`() {
        val vecchia = striscia("2026-09-15", M, M, M, M, M, M, M, F)
        val salvata = SerieSalvata("2026-09-18", 3)
        assertEquals(salvata, Serie.calcola(vecchia, salvata))
        assertEquals(salvata, Serie.memoria(vecchia, salvata))
    }

    // --- memoria: un grigio non è una rottura ---

    @Test
    fun `ieri grigio perche' le fotografie non sono ancora arrivate, poi verde`() {
        // 20 giorni di fila fino al 17. Il 18 è ancora grigio: le fotografie
        // sono in coda sul telefono. Oggi (19) non ha ancora dati.
        val salvata = SerieSalvata("2026-09-17", 20)
        val primaLettura = striscia("2026-09-19", M, M, M, M, M, M, N, N)
        // Da mostrare, adesso: la striscia non arriva a ieri, quindi zero...
        assertNull(Serie.calcola(primaLettura, salvata))
        // ...ma la memoria resta com'era.
        val tenuta = Serie.memoria(primaLettura, salvata)
        assertEquals(salvata, tenuta)

        // Arrivano le fotografie: il 18 diventa verde e la serie lunga torna.
        val secondaLettura = striscia("2026-09-19", M, M, M, M, M, M, M, N)
        assertEquals(SerieSalvata("2026-09-18", 21), Serie.calcola(secondaLettura, tenuta))
        assertEquals(SerieSalvata("2026-09-18", 21), Serie.memoria(secondaLettura, tenuta))
    }

    @Test
    fun `ieri rosso invece cancella la memoria`() {
        val salvata = SerieSalvata("2026-09-17", 20)
        val giorni = striscia("2026-09-19", M, M, M, M, M, M, F, N)
        assertNull(Serie.memoria(giorni, salvata))
    }

    @Test
    fun `un rosso dopo un grigio rompe comunque la serie`() {
        val salvata = SerieSalvata("2026-09-15", 20)
        val giorni = striscia("2026-09-19", M, M, M, M, N, F, M, M)
        assertEquals(SerieSalvata("2026-09-19", 2), Serie.memoria(giorni, salvata))
    }

    @Test
    fun `dopo un grigio si mostra la serie nuova ma si ricorda quella lunga`() {
        // Il 16 è grigio; dal 17 al 19 tre giorni verdi.
        val salvata = SerieSalvata("2026-09-15", 20)
        val giorni = striscia("2026-09-19", M, M, M, M, N, M, M, M)
        assertEquals(SerieSalvata("2026-09-19", 3), Serie.calcola(giorni, salvata))
        assertEquals(salvata, Serie.memoria(giorni, salvata))
        // Il 16 diventa verde: 20 fino al 15, più quattro giorni.
        val dopo = striscia("2026-09-19", M, M, M, M, M, M, M, M)
        assertEquals(SerieSalvata("2026-09-19", 24), Serie.calcola(dopo, salvata))
    }

    @Test
    fun `un grigio che resta grigio esce dalla finestra e la memoria non si riattacca`() {
        // Il 16 resta grigio per sempre. Quando la finestra parte dal 17, la
        // memoria del 15 non si può più attaccare: vale la serie dal 17 in poi.
        val salvata = SerieSalvata("2026-09-15", 20)
        val giorni = striscia("2026-09-24", M, M, M, M, M, M, M, M)
        assertEquals(SerieSalvata("2026-09-24", 8), Serie.calcola(giorni, salvata))
        assertEquals(SerieSalvata("2026-09-24", 8), Serie.memoria(giorni, salvata))
    }

    @Test
    fun `senza memoria la memoria e' la serie della striscia`() {
        val giorni = striscia("2026-09-19", F, M, M, M, M, M, M, M)
        assertEquals(SerieSalvata("2026-09-19", 7), Serie.memoria(giorni, null))
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
