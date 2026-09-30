package eu.stgm.pactum.figlio.avviso

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** (0.9) Durante una chiamata, anche mentre squilla, l'avviso a tutto schermo non parte. */
class ChiamataTest {

    @Test
    fun `chiamata al telefono, via app o che squilla`() {
        assertTrue(Chiamata.inCorso(1)) // MODE_RINGTONE
        assertTrue(Chiamata.inCorso(2)) // MODE_IN_CALL
        assertTrue(Chiamata.inCorso(3)) // MODE_IN_COMMUNICATION (WhatsApp e simili)
    }

    @Test
    fun `audio normale, niente chiamata`() {
        assertFalse(Chiamata.inCorso(0)) // MODE_NORMAL
        assertFalse(Chiamata.inCorso(-2)) // MODE_INVALID
    }
}
