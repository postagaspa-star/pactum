package eu.stgm.pactum.figlio.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (0.9) Uno sforamento che non è arrivato al server: il giro veloce riprova
 * dopo 1, 2 e poi 4 minuti. Mai a raffica.
 */
class RitentoTest {

    private val min = 60_000L

    @Test
    fun `l'attesa cresce 1, 2, 4 minuti e poi resta a 4`() {
        assertEquals(0L, Ritento.attesa(0))
        assertEquals(1 * min, Ritento.attesa(1))
        assertEquals(2 * min, Ritento.attesa(2))
        assertEquals(4 * min, Ritento.attesa(3))
        assertEquals(4 * min, Ritento.attesa(10))
    }

    @Test
    fun `si riprova solo quando l'attesa e' passata`() {
        val ultimo = 1_000_000L
        assertFalse(Ritento.pronto(falliti = 2, ultimoTentativo = ultimo, adesso = ultimo + 119_000L))
        assertTrue(Ritento.pronto(falliti = 2, ultimoTentativo = ultimo, adesso = ultimo + 2 * min))
    }

    @Test
    fun `un giro al minuto non diventa una raffica`() {
        // Il giro veloce passa ogni minuto e l'invio fallisce sempre: in 15
        // minuti si riprova ai minuti 1, 3, 7, 11 e 15.
        var falliti = 1
        var ultimo = 0L
        val tentativi = (1..15).map { it * min }.filter { adesso ->
            Ritento.pronto(falliti, ultimo, adesso).also { pronto ->
                if (pronto) {
                    falliti += 1
                    ultimo = adesso
                }
            }
        }
        assertEquals(listOf(1, 3, 7, 11, 15).map { it * min }, tentativi)
    }
}
