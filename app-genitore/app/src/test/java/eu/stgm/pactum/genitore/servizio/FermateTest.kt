package eu.stgm.pactum.genitore.servizio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** (0.25) Quando dire al genitore che Pactum era stato chiuso del tutto (e gli avvisi fermi). */
class FermateTest {

    private val adesso = 1_800_000_000_000L
    private val ora = 60L * 60 * 1000

    @Test
    fun `si dice solo una chiusura del tutto, recente e non ancora vista`() {
        val chiusa = Fermata(adesso - 3 * ora, Fermate.UTENTE, null)
        assertEquals(chiusa, Fermate.daDire(chiusa, adesso, vistaFino = 0L))
        assertEquals(Fermata(adesso - ora, Fermate.UTENTE_FERMATA, null), Fermate.daDire(Fermata(adesso - ora, Fermate.UTENTE_FERMATA, null), adesso, 0L))
        // Già vista con «Ho capito».
        assertNull(Fermate.daDire(chiusa, adesso, vistaFino = chiusa.quando))
        // Di più di due giorni fa.
        assertNull(Fermate.daDire(Fermata(adesso - 49 * ora, Fermate.UTENTE, null), adesso, 0L))
        // Chiusa da Android per la memoria: il servizio riparte da solo, non c'è niente da dire.
        assertNull(Fermate.daDire(Fermata(adesso - ora, 3, null), adesso, 0L))
        assertNull(Fermate.daDire(null, adesso, 0L))
    }

    @Test
    fun `il motivo si scrive in una parola per i registri`() {
        assertEquals("utente", Fermate.parola(Fermate.UTENTE))
        assertEquals("memoria", Fermate.parola(3))
        assertEquals("motivo-99", Fermate.parola(99))
    }
}
