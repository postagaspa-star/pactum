package eu.stgm.pactum.figlio.valutatore

import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.TipiRegola
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * (0.9) La decisione della sentinella: uno sforamento è nuovo? E va anche a
 * tutto schermo? Lo stesso dedup della notifica: una volta per regola per
 * giorno (per le fasce, il giorno in cui la fascia è partita).
 */
class SegnalazioniTest {

    private val oggi = "2026-09-30"

    private fun limite(id: Long, oltre: Int = 5, limiteEfficace: Int = 30) = Sforamento(
        regolaId = id,
        tipo = TipiRegola.LIMITE_TEMPO,
        limiteEfficace = limiteEfficace,
        minutiOltre = oltre,
    )

    private fun fascia(id: Long, ancora: String, minuti: Int = 3) = Sforamento(
        regolaId = id,
        tipo = TipiRegola.FASCIA_ORARIA,
        limiteEfficace = null,
        minutiOltre = minuti,
        giornoAncora = ancora,
    )

    @Test
    fun `uno sforamento nuovo con il permesso va anche a tutto schermo`() {
        val decisione = Segnalazioni.decidi(listOf(limite(1)), oggi, emptySet(), mostraSopra = true)
        assertEquals(listOf(limite(1)), decisione.nuovi)
        assertEquals(listOf(limite(1)), decisione.aTuttoSchermo)
    }

    @Test
    fun `senza Mostra sopra le altre app resta la sola notifica`() {
        val decisione = Segnalazioni.decidi(listOf(limite(1)), oggi, emptySet(), mostraSopra = false)
        assertEquals(listOf(limite(1)), decisione.nuovi)
        assertTrue(decisione.aTuttoSchermo.isEmpty())
    }

    @Test
    fun `gia' segnalato oggi non torna, ne' in notifica ne' a tutto schermo`() {
        val decisione = Segnalazioni.decidi(
            listOf(limite(1)),
            oggi,
            setOf(Segnalazioni.chiave(1, oggi)),
            mostraSopra = true,
        )
        assertTrue(decisione.nuovi.isEmpty())
        assertTrue(decisione.aTuttoSchermo.isEmpty())
    }

    @Test
    fun `il giorno dopo la stessa regola avvisa di nuovo`() {
        val decisione = Segnalazioni.decidi(
            listOf(limite(1)),
            "2026-10-01",
            setOf(Segnalazioni.chiave(1, oggi)),
            mostraSopra = true,
        )
        assertEquals(listOf(1L), decisione.aTuttoSchermo.map { it.regolaId })
    }

    @Test
    fun `dopo un bonus il limite nuovo superato non avvisa di nuovo nello stesso giorno`() {
        // 30 minuti superati (avviso dato), poi +15 di bonus: anche 45 superati.
        // Il dedup è per regola e giorno, non per limite: niente secondo avviso.
        val decisione = Segnalazioni.decidi(
            listOf(limite(1, oltre = 2, limiteEfficace = 45)),
            oggi,
            setOf(Segnalazioni.chiave(1, oggi)),
            mostraSopra = true,
        )
        assertTrue(decisione.nuovi.isEmpty())
        assertTrue(decisione.aTuttoSchermo.isEmpty())
    }

    @Test
    fun `una fascia notturna si segnala sul giorno in cui e' partita`() {
        // Fascia 23:00→07:00 partita il 29 e già segnalata: all'una del 30 la
        // coda mattutina è la stessa occorrenza, niente avviso.
        val giaSegnalati = setOf(Segnalazioni.chiave(2, "2026-09-29"))
        val mattina = Segnalazioni.decidi(listOf(fascia(2, ancora = "2026-09-29")), oggi, giaSegnalati, true)
        assertTrue(mattina.aTuttoSchermo.isEmpty())
        // Quella di stasera, partita il 30, è un'occorrenza nuova.
        val sera = Segnalazioni.decidi(listOf(fascia(2, ancora = oggi)), oggi, giaSegnalati, true)
        assertEquals(listOf(2L), sera.aTuttoSchermo.map { it.regolaId })
    }

    @Test
    fun `due regole fuori nello stesso giro vanno nello stesso avviso`() {
        val decisione = Segnalazioni.decidi(listOf(limite(1), limite(4)), oggi, emptySet(), true)
        assertEquals(listOf(1L, 4L), decisione.aTuttoSchermo.map { it.regolaId })
    }

    @Test
    fun `la stessa chiave due volte nello stesso giro conta una volta`() {
        val decisione = Segnalazioni.decidi(listOf(limite(1), limite(1, oltre = 7)), oggi, emptySet(), true)
        assertEquals(1, decisione.nuovi.size)
    }

    @Test
    fun `la chiave e' quella gia' salvata dalla 0_8`() {
        // Aggiornando non deve ripartire nessun avviso per uno sforamento già segnalato.
        assertEquals("7:2026-09-30", Segnalazioni.chiave(7, "2026-09-30"))
    }

    @Test
    fun `durante una chiamata niente tutto schermo, resta la notifica`() {
        val decisione = Segnalazioni.decidi(listOf(limite(1)), oggi, emptySet(), mostraSopra = true, inChiamata = true)
        assertEquals(listOf(limite(1)), decisione.nuovi)
        assertTrue(decisione.aTuttoSchermo.isEmpty())
    }

    @Test
    fun `lo sforamento di ieri visto dopo mezzanotte va solo in notifica`() {
        val decisione = Segnalazioni.decidi(
            listOf(limite(1)),
            "2026-09-29",
            emptySet(),
            mostraSopra = true,
            giornoPassato = true,
        )
        assertEquals(listOf(limite(1)), decisione.nuovi)
        assertTrue(decisione.aTuttoSchermo.isEmpty())
    }

    @Test
    fun `l'id dello sforamento e' sempre lo stesso per lo stesso fatto`() {
        val id = Segnalazioni.idEvento("1", 7, oggi)
        assertEquals(id, Segnalazioni.idEvento("1", 7, oggi))
        // Un UUID, come vuole il contratto.
        assertEquals(id, UUID.fromString(id).toString())
        // Un altro giorno, un'altra regola o un altro dispositivo: un altro id.
        assertNotEquals(id, Segnalazioni.idEvento("1", 7, "2026-10-01"))
        assertNotEquals(id, Segnalazioni.idEvento("1", 8, oggi))
        assertNotEquals(id, Segnalazioni.idEvento("2", 7, oggi))
    }

    @Test
    fun `senza limiti a tempo ne' fasce non c'e' niente da guardare`() {
        val vitaReale = Regola(id = 3, tipo = TipiRegola.VITA_REALE)
        val limiteSpento = Regola(id = 4, tipo = TipiRegola.LIMITE_TEMPO, attiva = false)
        assertFalse(Segnalazioni.daGuardare(listOf(vitaReale, limiteSpento)))
        assertTrue(Segnalazioni.daGuardare(listOf(vitaReale, Regola(id = 5, tipo = TipiRegola.FASCIA_ORARIA))))
        assertTrue(Segnalazioni.daGuardare(listOf(Regola(id = 6, tipo = TipiRegola.LIMITE_TEMPO))))
    }
}
