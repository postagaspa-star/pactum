package eu.stgm.pactum.figlio.sync

import eu.stgm.pactum.figlio.dati.TipiEvento
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (0.14, contratto v3.7) Il telefono spento non è un'interruzione
 * (`sospensione` allo spegnimento, o alla riaccensione se non era partita) e
 * in stand-by i battiti continuano, mai due insieme.
 */
class SpentoEStandByTest {

    private val min = 60_000L
    private val ore = 60 * min
    private val accensione = 1_790_000_000_000L

    // --- La sospensione ------------------------------------------------------

    @Test
    fun `l'evento sospensione e l'evento ripresa come il computer`() {
        val s = Spegnimento.eventoSospensione(accensione - ore)
        assertEquals(TipiEvento.SOSPENSIONE, s.tipo)
        assertEquals("sospensione", s.tipo)
        assertEquals(accensione - ore, s.tsDevice)
        assertEquals("spegnimento", (s.dettagli["motivo"] as JsonPrimitive).contentOrNull)
        val r = Spegnimento.eventoRipresa(accensione + min, accensione)
        assertEquals("ripresa", r.tipo)
        assertEquals("avvio", (r.dettagli["motivo"] as JsonPrimitive).contentOrNull)
        assertEquals(accensione, (r.dettagli["avvio_sistema_ts"] as JsonPrimitive).longOrNull)
    }

    @Test
    fun `l'avviso di Android non c'era - alla riaccensione la sospensione nasce dallo spegnimento negli eventi d'uso`() {
        val spento = accensione - 2 * ore
        assertEquals(
            spento,
            Spegnimento.daAnnunciareAllaRiaccensione(
                spegnimenti = listOf(accensione - 30 * ore, spento),
                accensioneIl = accensione,
                giaVisto = accensione - 30 * ore,
                giaAnnunciato = accensione - 30 * ore,
                inCoda = emptyList(),
            ),
        )
    }

    @Test
    fun `l'avviso di Android l'aveva già mandata - niente doppioni`() {
        val spento = accensione - 2 * ore
        // L'avviso è arrivato qualche secondo prima che Android registrasse lo spegnimento.
        assertNull(Spegnimento.daAnnunciareAllaRiaccensione(listOf(spento), accensione, null, giaAnnunciato = spento - 20_000, inCoda = emptyList()))
    }

    @Test
    fun `l'avviso l'aveva messa in coda senza riuscire a mandarla - parte quella, non una seconda`() {
        val spento = accensione - 2 * ore
        assertNull(Spegnimento.daAnnunciareAllaRiaccensione(listOf(spento), accensione, null, giaAnnunciato = null, inCoda = listOf(spento - 5_000)))
    }

    @Test
    fun `lo stesso spegnimento già guardato a un'altra accensione non rinasce`() {
        val spento = accensione - 2 * ore
        assertNull(Spegnimento.daAnnunciareAllaRiaccensione(listOf(spento), accensione, giaVisto = spento, giaAnnunciato = null, inCoda = emptyList()))
    }

    @Test
    fun `nessuno spegnimento negli eventi d'uso - niente sospensione`() {
        assertNull(Spegnimento.daAnnunciareAllaRiaccensione(emptyList(), accensione, null, null, emptyList()))
    }

    @Test
    fun `le azioni dell'avviso di spegnimento, comprese quelle di alcune marche`() {
        assertTrue("android.intent.action.ACTION_SHUTDOWN" in Spegnimento.AZIONI)
        assertTrue("android.intent.action.QUICKBOOT_POWEROFF" in Spegnimento.AZIONI)
        assertTrue("com.htc.intent.action.QUICKBOOT_POWEROFF" in Spegnimento.AZIONI)
        assertTrue("lo spegnimento non si trattiene", Spegnimento.LIMITE_INVIO_MS <= 5_000)
    }

    // --- Il battito in stand-by ---------------------------------------------

    @Test
    fun `il primo battito parte sempre`() {
        assertTrue(CadenzaBattito.serve(null, 5 * ore))
    }

    @Test
    fun `mai due battiti a pochi minuti l'uno dall'altro`() {
        val ultimo = 5 * ore
        assertFalse(CadenzaBattito.serve(ultimo, ultimo))
        assertFalse(CadenzaBattito.serve(ultimo, ultimo + 3 * min))
        assertFalse(CadenzaBattito.serve(ultimo, ultimo + 9 * min))
        assertTrue(CadenzaBattito.serve(ultimo, ultimo + 10 * min))
        assertTrue(CadenzaBattito.serve(ultimo, ultimo + 15 * min))
    }

    @Test
    fun `la sveglia del battito ogni 15 minuti - dentro i 45 minuti del silenzio e oltre i 9 dello stand-by`() {
        assertEquals(5 * ore + 15 * min, CadenzaBattito.prossimo(5 * ore))
        assertTrue(CadenzaBattito.INTERVALLO_MS * 2 < 45 * min)
        assertTrue(CadenzaBattito.INTERVALLO_MS >= 9 * min)
        assertTrue(CadenzaBattito.MINIMO_MS < CadenzaBattito.INTERVALLO_MS)
    }

    @Test
    fun `la sveglia, il giro del servizio e il worker insieme - un battito solo`() {
        // Tre richieste nello stesso momento, come le farebbe BattitoCadenzato sotto il suo lucchetto.
        var ultimo: Long? = null
        var battiti = 0
        val adesso = 7 * ore
        repeat(3) {
            if (CadenzaBattito.serve(ultimo, adesso)) {
                ultimo = adesso
                battiti += 1
            }
        }
        assertEquals(1, battiti)
    }
}
