package eu.stgm.pactum.figlio.studio

import eu.stgm.pactum.figlio.sessione.SessioneDefinita
import eu.stgm.pactum.figlio.sessione.StatiSessione
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * (0.22) Le app dello Studio dalla sessione di studio che c'è già: quale
 * sessione, e quando la proposta parte (solo con la lista vuota e niente in attesa).
 */
class StudioDaSessioneTest {

    private fun sessione(
        id: Long,
        nome: String,
        app: List<String> = listOf("eu.spaggiari.classevivafamiglia"),
        stato: String = StatiSessione.APPROVATA,
    ) = SessioneDefinita(
        id = id,
        nome = nome,
        app = app,
        nomi = app.associateWith { "Nome di $it" },
        stato = stato,
        modificaInAttesa = null,
        motivazione = null,
        versione = 3,
    )

    private val approvataVuota = ConfigStudio(
        stato = StatiConfigStudio.APPROVATA,
        versione = 1,
        approvata = ContenutoStudio(),
    )

    @Test
    fun `si sceglie la sessione di studio approvata, prima quella che si chiama Studio`() {
        val compiti = sessione(1, "Compiti", app = listOf("a.b", "c.d", "e.f"))
        val studio = sessione(2, "Studio")
        val calcio = sessione(3, "Calcio")
        assertEquals(studio, StudioDaSessione.sessione(listOf(compiti, studio, calcio)))
        assertEquals(compiti, StudioDaSessione.sessione(listOf(compiti, calcio)))
        // Una sessione non approvata, o senza app, o non di studio, non vale.
        assertNull(StudioDaSessione.sessione(listOf(sessione(4, "Studio", stato = StatiSessione.IN_ATTESA))))
        assertNull(StudioDaSessione.sessione(listOf(sessione(5, "Studio", app = emptyList()))))
        assertNull(StudioDaSessione.sessione(listOf(calcio)))
    }

    @Test
    fun `la proposta prende le app della sessione e tiene il resto approvato`() {
        val studio = sessione(2, "Studio", app = listOf("a.b", "a.b", "c.d"))
        val p = StudioDaSessione.proposta(approvataVuota, studio)!!
        assertEquals(listOf("a.b", "c.d"), p.app)
        assertEquals(setOf("a.b", "c.d"), p.nomi.keys)
        assertEquals(approvataVuota.approvata!!.giorni, p.giorni)
        assertEquals(approvataVuota.approvata!!.inizio, p.inizio)
        assertEquals(approvataVuota.approvata!!.minutiMinimi, p.minutiMinimi)
    }

    @Test
    fun `niente proposta se la lista c'è già o se una proposta aspetta`() {
        val studio = sessione(2, "Studio")
        val conApp = approvataVuota.copy(approvata = ContenutoStudio(app = listOf("x.y")))
        assertNull(StudioDaSessione.proposta(conApp, studio))
        val inAttesa = approvataVuota.copy(inAttesa = ContenutoStudio(app = listOf("x.y")))
        assertNull(StudioDaSessione.proposta(inAttesa, studio))
        assertNull(StudioDaSessione.proposta(ConfigStudio(), studio))
        assertNull(StudioDaSessione.proposta(approvataVuota, null))
        assertNull(StudioDaSessione.proposta(null, studio))
    }

    @Test
    fun `una proposta automatica per sessione e versione`() {
        assertEquals("2@3", StudioDaSessione.chiave(sessione(2, "Studio")))
    }
}
