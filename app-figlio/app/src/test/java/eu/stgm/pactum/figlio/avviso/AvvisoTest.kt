package eu.stgm.pactum.figlio.avviso

import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.TipiRegola
import eu.stgm.pactum.figlio.valutatore.Sforamento
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (0.9) Cosa racconta l'avviso a tutto schermo di uno sforamento: quanto hai
 * usato, il limite che ti sei dato, il bonus; per le fasce, le ore e i minuti.
 */
class AvvisoTest {

    private fun limite(id: Long, chiave: String, minuti: Int) = Regola(
        id = id,
        tipo = TipiRegola.LIMITE_TEMPO,
        parametri = buildJsonObject {
            put("app_o_categoria", chiave)
            put("minuti_al_giorno", minuti)
        },
    )

    @Test
    fun `tutto il telefono con il bonus dice uso, limite e bonus`() {
        // Limite 2 h, +15 di bonus oggi (limite efficace 135), 5 minuti oltre.
        val sforamento = Sforamento(regolaId = 4, tipo = TipiRegola.LIMITE_TEMPO, limiteEfficace = 135, minutiOltre = 5)
        val avviso = Avviso.da(sforamento, limite(4, "totale", 120), nome = "Tutto il telefono")
        assertEquals("Tutto il telefono", avviso.nome)
        assertEquals(140, avviso.minutiUsati)
        assertEquals(135, avviso.limiteEfficace)
        assertEquals(120, avviso.limite)
        assertEquals(15, avviso.bonus)
        assertFalse(avviso.fascia)
    }

    @Test
    fun `senza bonus il limite di oggi e' quello che ti sei dato`() {
        val sforamento = Sforamento(regolaId = 1, tipo = TipiRegola.LIMITE_TEMPO, limiteEfficace = 30, minutiOltre = 1)
        val avviso = Avviso.da(sforamento, limite(1, "categoria:social", 30), nome = "Social")
        assertEquals(31, avviso.minutiUsati)
        assertEquals(30, avviso.limite)
        assertEquals(0, avviso.bonus)
    }

    @Test
    fun `una fascia dice le ore e i minuti di telefono dentro`() {
        val regola = Regola(
            id = 2,
            tipo = TipiRegola.FASCIA_ORARIA,
            parametri = buildJsonObject {
                put("dalle", "22:00")
                put("alle", "07:00")
                putJsonArray("giorni") { add("mer") }
            },
        )
        val sforamento = Sforamento(
            regolaId = 2,
            tipo = TipiRegola.FASCIA_ORARIA,
            limiteEfficace = null,
            minutiOltre = 3,
            giornoAncora = "2026-09-30",
        )
        val avviso = Avviso.da(sforamento, regola, nome = null)
        assertTrue(avviso.fascia)
        assertEquals("22:00", avviso.dalle)
        assertEquals("07:00", avviso.alle)
        assertEquals(3, avviso.minutiOltre)
        assertNull(avviso.limite)
        assertEquals(0, avviso.bonus)
    }

    @Test
    fun `gli avvisi fanno il viaggio nell'intent senza perdere niente`() {
        val avvisi = listOf(
            Avviso(regolaId = 4, tipo = TipiRegola.LIMITE_TEMPO, nome = "Tutto il telefono", minutiUsati = 140, limiteEfficace = 135, limite = 120, minutiOltre = 5),
            Avviso(regolaId = 2, tipo = TipiRegola.FASCIA_ORARIA, minutiOltre = 3, dalle = "22:00", alle = "07:00"),
        )
        assertEquals(avvisi, Avviso.daJson(Avviso.inJson(avvisi)))
    }

    @Test
    fun `un secondo avviso si aggiunge al primo, non lo cancella`() {
        val social = Avviso(regolaId = 1, tipo = TipiRegola.LIMITE_TEMPO, nome = "Social", minutiUsati = 31, limiteEfficace = 30, minutiOltre = 1)
        val totale = Avviso(regolaId = 4, tipo = TipiRegola.LIMITE_TEMPO, nome = "Tutto il telefono", minutiUsati = 121, limiteEfficace = 120, minutiOltre = 1)
        assertEquals(listOf(social, totale), Avviso.unisci(listOf(social), listOf(totale)))
        // La stessa regola una volta sola: tiene il posto, prende i numeri nuovi.
        val socialDopo = social.copy(minutiUsati = 33, minutiOltre = 3)
        assertEquals(listOf(socialDopo, totale), Avviso.unisci(listOf(social, totale), listOf(socialDopo)))
    }

    @Test
    fun `un intent senza avvisi o rovinato non apre niente`() {
        assertEquals(emptyList<Avviso>(), Avviso.daJson(null))
        assertEquals(emptyList<Avviso>(), Avviso.daJson("{rotto"))
    }
}
