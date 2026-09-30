package eu.stgm.pactum.figlio.misura

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * (0.9) Il totale di un giorno non torna mai indietro, come la fotografia
 * vigente sul server: disinstallare un'app già usata non fa scendere "Tutto il
 * telefono".
 */
class TotaleDelGiornoTest {

    private val oggi = LocalDate.of(2026, 9, 30)

    @Test
    fun `il totale sale con l'uso`() {
        val primo = TotaleDelGiorno.aggiorna(emptyMap(), "2026-09-30", 40, oggi)
        assertEquals(40L, primo.totale)
        assertEquals(55L, TotaleDelGiorno.aggiorna(primo.memoria, "2026-09-30", 55, oggi).totale)
    }

    @Test
    fun `se un'app sparisce il totale non scende`() {
        val memoria = mapOf("2026-09-30" to 55L)
        val esito = TotaleDelGiorno.aggiorna(memoria, "2026-09-30", 20, oggi)
        assertEquals(55L, esito.totale)
        assertEquals(memoria, esito.memoria)
    }

    @Test
    fun `ogni giorno ha il suo totale`() {
        val memoria = mapOf("2026-09-29" to 200L)
        assertEquals(10L, TotaleDelGiorno.aggiorna(memoria, "2026-09-30", 10, oggi).totale)
        assertEquals(200L, TotaleDelGiorno.aggiorna(memoria, "2026-09-29", 150, oggi).totale)
    }

    @Test
    fun `si ricordano solo gli ultimi giorni`() {
        val memoria = mapOf("2026-09-25" to 90L, "2026-09-29" to 200L)
        val esito = TotaleDelGiorno.aggiorna(memoria, "2026-09-30", 10, oggi)
        assertEquals(mapOf("2026-09-29" to 200L, "2026-09-30" to 10L), esito.memoria)
    }
}
