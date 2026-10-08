package eu.stgm.pactum.design

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (0.19) L'app più viva (core-design, condiviso con l'app del genitore): i
 * colori delle sezioni si leggono, i movimenti partono e finiscono dove
 * devono, l'anello dello Studio divide il giro giusto.
 */
class VivaceTest {

    // --- I colori delle sezioni ------------------------------------------------------

    @Test
    fun `titolo e icone si leggono sul fondo di ogni sezione`() {
        Sezione.entries.forEach { s ->
            assertTrue("${s.name}: ${contrasto(s.inchiostro, s.fondo)}", contrasto(s.inchiostro, s.fondo) >= 4.5)
        }
    }

    @Test
    fun `l'inchiostro si legge anche sul bianco delle card`() {
        Sezione.entries.forEach { s ->
            assertTrue(s.name, contrasto(s.inchiostro, Color.White) >= 4.5)
        }
    }

    @Test
    fun `ogni sezione ha almeno due adesivi`() {
        Sezione.entries.forEach { s ->
            assertTrue(s.name, s.emoji.size >= 2)
            assertEquals(s.emoji.first(), s.emojiPrincipale)
        }
    }

    @Test
    fun `lo Studio ha lo stesso verde acqua delle Sessioni di studio`() {
        assertEquals(TemaSessione.STUDIO.sfondoChiaro, Sezione.STUDIO.fondo)
    }

    @Test
    fun `Rimuovi animazioni ferma tutto`() {
        assertFalse(animazioniAttive(0f))
        assertTrue(animazioniAttive(1f))
        assertTrue(animazioniAttive(0.5f))
    }

    // --- Le entrate a cascata ------------------------------------------------------------

    @Test
    fun `più in basso arriva più tardi, mai oltre il massimo`() {
        assertEquals(0L, Movimento.ritardoCascata(0f, 2000f))
        assertEquals(Movimento.RITARDO_MASSIMO_MS / 2, Movimento.ritardoCascata(1000f, 2000f))
        assertEquals(Movimento.RITARDO_MASSIMO_MS, Movimento.ritardoCascata(5000f, 2000f))
        assertEquals(0L, Movimento.ritardoCascata(-50f, 2000f))
        assertEquals(0L, Movimento.ritardoCascata(500f, 0f))
    }

    @Test
    fun `entrano solo le card nate appena comparsa la schermata`() {
        assertTrue(Movimento.entra(1_000L, 1_000L))
        assertTrue(Movimento.entra(1_000L, 1_000L + Movimento.FINESTRA_ENTRATA_MS))
        assertFalse(Movimento.entra(1_000L, 1_001L + Movimento.FINESTRA_ENTRATA_MS))
        // Fuori da una schermata colorata (barriere, dialoghi): mai.
        assertFalse(Movimento.entra(null, 1_000L))
    }

    // --- Grafici e numeri ----------------------------------------------------------------

    @Test
    fun `le colonne crescono da sinistra a destra e arrivano tutte in cima`() {
        val otto = 8
        assertEquals(0f, Movimento.crescitaColonna(0f, 0, otto), 0f)
        assertEquals(1f, Movimento.crescitaColonna(1f, 0, otto), 0f)
        assertEquals(1f, Movimento.crescitaColonna(1f, otto - 1, otto), 0f)
        // A metà, la prima è più avanti dell'ultima.
        assertTrue(Movimento.crescitaColonna(0.5f, 0, otto) > Movimento.crescitaColonna(0.5f, otto - 1, otto))
        assertEquals(0.3f, Movimento.crescitaColonna(0.3f, 0, 1), 0.0001f)
    }

    @Test
    fun `il conteggio parte da dove era e arriva al numero giusto`() {
        assertEquals(0L, Movimento.conteggio(0, 90, 0f))
        assertEquals(45L, Movimento.conteggio(0, 90, 0.5f))
        assertEquals(90L, Movimento.conteggio(0, 90, 1f))
        assertEquals(90L, Movimento.conteggio(0, 90, 3f))
        assertEquals(70L, Movimento.conteggio(100, 40, 0.5f))
    }

    // --- Gli adesivi -----------------------------------------------------------------------

    @Test
    fun `gli adesivi entrano uno dopo l'altro e si fermano al loro posto`() {
        assertEquals(0f, MotoAdesivi.entrata(0f, 0), 0.0001f)
        assertEquals(1f, MotoAdesivi.entrata(1f, 0), 0.0001f)
        assertEquals(1f, MotoAdesivi.entrata(1f, 2), 0.0001f)
        assertTrue(MotoAdesivi.entrata(0.3f, 0) > MotoAdesivi.entrata(0.3f, 2))
        // Il rimbalzo va un filo oltre e torna.
        assertTrue((0..100).map { MotoAdesivi.rimbalzo(it / 100f) }.max() > 1f)
    }

    @Test
    fun `il dondolio si spegne e alla fine sono fermi`() {
        assertEquals(0f, MotoAdesivi.galleggio(0f, 1), 0f)
        assertEquals(0f, MotoAdesivi.galleggio(1f, 1), 0f)
        assertTrue(kotlin.math.abs(MotoAdesivi.galleggio(0.98f, 0)) <= 0.02f)
    }

    // --- L'anello dello Studio ------------------------------------------------------------

    @Test
    fun `l'anello divide il giro fatto fra le attività in proporzione`() {
        val fette = listOf(
            FettaAttivita(ColoriAttivita.Compiti, 1800),
            FettaAttivita(ColoriAttivita.LavoriDiCasa, 600),
            FettaAttivita(ColoriAttivita.Altro, 0),
        )
        // 40 minuti su 60: due terzi del giro, diviso 3 a 1.
        val gradi = gradiAttivita(fette, 40f / 60f)
        assertEquals(2, gradi.size)
        assertEquals(180f, gradi[0].second, 0.01f)
        assertEquals(60f, gradi[1].second, 0.01f)
        // Oltre il minimo resta pieno, mai più di un giro.
        assertEquals(360f, gradiAttivita(fette, 1.5f).sumOf { it.second.toDouble() }.toFloat(), 0.01f)
        // Niente tempo, niente fette.
        assertTrue(gradiAttivita(listOf(FettaAttivita(ColoriAttivita.Altro, 0)), 0.5f).isEmpty())
    }

    @Test
    fun `i colori delle attività si vedono sulle card chiare`() {
        listOf(ColoriAttivita.Compiti, ColoriAttivita.LavoriDiCasa, ColoriAttivita.Altro).forEach {
            assertTrue(contrasto(it, Color.White) >= 3.0)
            assertTrue(contrasto(it, Sezione.STUDIO.fondo) >= 3.0)
        }
    }
}
