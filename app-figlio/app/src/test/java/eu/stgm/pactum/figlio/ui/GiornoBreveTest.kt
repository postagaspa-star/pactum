package eu.stgm.pactum.figlio.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * I giorni del server arrivano in ISO ("2026-09-18") e a schermo devono
 * leggersi all'italiana: "oggi", "ieri", "18/09".
 */
class GiornoBreveTest {

    private val parole = ParoleGiorno(oggi = "oggi", ieri = "ieri")
    private val oggi = LocalDate.of(2026, 9, 19)

    @Test
    fun `oggi e ieri si dicono a parole`() {
        assertEquals("oggi", giornoBreve("2026-09-19", oggi, parole))
        assertEquals("ieri", giornoBreve("2026-09-18", oggi, parole))
    }

    @Test
    fun `gli altri giorni come giorno e mese`() {
        assertEquals("17/09", giornoBreve("2026-09-17", oggi, parole))
        assertEquals("01/08", giornoBreve("2026-08-01", oggi, parole))
    }

    @Test
    fun `ieri a cavallo del mese e dell'anno`() {
        assertEquals("ieri", giornoBreve("2026-08-31", LocalDate.of(2026, 9, 1), parole))
        assertEquals("ieri", giornoBreve("2025-12-31", LocalDate.of(2026, 1, 1), parole))
    }

    @Test
    fun `un giorno di un altro anno porta l'anno`() {
        assertEquals("30/12/2025", giornoBreve("2025-12-30", LocalDate.of(2026, 1, 1), parole))
    }

    @Test
    fun `un testo che non e' una data resta com'e'`() {
        assertEquals("", giornoBreve("", oggi, parole))
        assertEquals("boh", giornoBreve("boh", oggi, parole))
    }

    @Test
    fun `sui chip le stesse regole con le parole maiuscole`() {
        val chip = ParoleGiorno(oggi = "Oggi", ieri = "Ieri")
        assertEquals("Oggi", giornoBreve(oggi, oggi, chip))
        assertEquals("Ieri", giornoBreve(oggi.minusDays(1), oggi, chip))
        assertEquals("12/09", giornoBreve(oggi.minusDays(7), oggi, chip))
    }
}
