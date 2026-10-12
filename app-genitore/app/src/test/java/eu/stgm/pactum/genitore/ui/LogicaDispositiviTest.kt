package eu.stgm.pactum.genitore.ui

import eu.stgm.pactum.design.GiornoPatto
import eu.stgm.pactum.design.Segnale
import eu.stgm.pactum.genitore.dati.DispositivoFinestra
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.QuadrettoSemaforo
import eu.stgm.pactum.genitore.dati.RegolaFinestra
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (0.23) Le regole per dispositivo nella Panoramica e nella pagina di un
 * dispositivo: ogni dispositivo con la SUA striscia e le SUE regole, gli
 * impegni a parte. Un fuori regola sul computer non sporca il telefono.
 */
class LogicaDispositiviTest {

    private val M = Segnale.MANTENUTA
    private val F = Segnale.FUORI_REGOLA
    private val N = Segnale.NESSUN_DATO
    private val ieri = "2026-09-29"
    private val oggi = "2026-09-30"

    private fun striscia(ieri: String, oggi: String) =
        listOf(QuadrettoSemaforo(this.ieri, ieri), QuadrettoSemaforo(this.oggi, oggi))

    private fun regola(
        id: Long,
        dispositivo: Long?,
        ieri: String = "verde",
        oggi: String = "verde",
        tipo: String = "limite_tempo",
        attiva: Boolean = true,
    ) = RegolaFinestra(id = id, tipo = tipo, attiva = attiva, dispositivoId = dispositivo, semaforo = striscia(ieri, oggi))

    private fun dispositivo(id: Long, tipo: String, ieri: String, oggi: String, revocato: Boolean = false) =
        DispositivoFinestra(id = id, nome = "D$id", tipo = tipo, revocato = revocato, striscia = striscia(ieri, oggi))

    /** Il telefono è andato bene; il computer è andato fuori oggi; un impegno non riuscito ieri. */
    private val finestra = Finestra(
        regole = listOf(
            regola(1, dispositivo = 1),
            regola(2, dispositivo = 2, oggi = "rosso"),
            regola(3, dispositivo = 2, oggi = "verde"),
            regola(4, dispositivo = null, tipo = "vita_reale", ieri = "rosso", oggi = "grigio"),
            regola(5, dispositivo = 1, attiva = false, ieri = "verde", oggi = "grigio"), // eliminata ieri
        ),
        striscia = striscia("rosso", "rosso"),
        dispositivi = listOf(
            dispositivo(1, "telefono", ieri = "verde", oggi = "verde"),
            dispositivo(2, "computer", ieri = "verde", oggi = "rosso"),
        ),
    )

    @Test
    fun `una scheda per dispositivo, con la sua striscia, poi gli impegni`() {
        val schede = schedeDeiDispositivi(finestra)
        assertEquals(listOf(DiChi.Dispositivo(1), DiChi.Dispositivo(2), DiChi.Impegni), schede.map { it.diChi })
        // Il telefono resta verde anche se il computer oggi è fuori.
        assertEquals(listOf(M, M), schede[0].giorni.map { it.segnale })
        assertEquals(listOf(M, F), schede[1].giorni.map { it.segnale })
        assertEquals(listOf(1L), schede[0].oggi.map { it.regola.id })
        assertEquals(listOf(2L, 3L), schede[1].oggi.map { it.regola.id })
    }

    @Test
    fun `com'e' andata oggi, in breve`() {
        val schede = schedeDeiDispositivi(finestra).associateBy { it.diChi }
        assertEquals(OggiInBreve.TUTTO_DENTRO, schede.getValue(DiChi.Dispositivo(1)).oggiInBreve)
        assertEquals(OggiInBreve.FUORI, schede.getValue(DiChi.Dispositivo(2)).oggiInBreve)
        assertEquals(1, schede.getValue(DiChi.Dispositivo(2)).fuoriOggi)
        assertEquals(OggiInBreve.SENZA_DATI, schede.getValue(DiChi.Impegni).oggiInBreve)
    }

    @Test
    fun `gli impegni hanno la striscia messa insieme dai loro semafori`() {
        val impegni = schedaDi(finestra, DiChi.Impegni)!!
        assertEquals(listOf(GiornoPatto(ieri, F), GiornoPatto(oggi, N)), impegni.giorni)
        assertNull(impegni.dispositivo)
    }

    @Test
    fun `le regole non piu' attive stanno in fondo alla pagina, non oggi`() {
        val telefono = schedaDi(finestra, DiChi.Dispositivo(1))!!
        assertEquals(listOf(5L), telefono.nonPiuAttive.map { it.id })
        assertTrue(telefono.oggi.none { it.regola.id == 5L })
    }

    @Test
    fun `un dispositivo senza regole ha la sua scheda, che lo dice`() {
        val conTablet = finestra.copy(dispositivi = finestra.dispositivi + dispositivo(3, "telefono", "grigio", "grigio"))
        val tablet = schedaDi(conTablet, DiChi.Dispositivo(3))!!
        assertEquals(OggiInBreve.NESSUNA_REGOLA, tablet.oggiInBreve)
    }

    @Test
    fun `nella card un dispositivo scollegato solo finche' ha giorni con dati`() {
        val vecchio = dispositivo(3, "telefono", "grigio", "grigio", revocato = true)
        val conVecchio = finestra.copy(
            regole = finestra.regole + regola(6, dispositivo = 3, ieri = "grigio", oggi = "grigio"),
            dispositivi = finestra.dispositivi + vecchio,
        )
        assertTrue(schedeDellaCard(conVecchio).none { it.diChi == DiChi.Dispositivo(3) })
        // La sua pagina si apre ancora (dalle notifiche, da "Tutte le regole").
        assertEquals(listOf(6L), schedaDi(conVecchio, DiChi.Dispositivo(3))!!.nonPiuAttive.map { it.id })
        val conDati = conVecchio.copy(dispositivi = finestra.dispositivi + vecchio.copy(striscia = striscia("verde", "grigio")))
        assertTrue(schedeDellaCard(conDati).any { it.diChi == DiChi.Dispositivo(3) })
    }

    @Test
    fun `senza impegni niente scheda degli impegni`() {
        val senza = finestra.copy(regole = finestra.regole.filter { it.tipo != "vita_reale" })
        assertTrue(schedeDeiDispositivi(senza).none { it.diChi == DiChi.Impegni })
        assertNull(schedaDi(senza, DiChi.Impegni))
    }

    @Test
    fun `un server 0_7 senza dispositivi non ha schede`() {
        val vecchia = Finestra(regole = listOf(regola(1, dispositivo = null)), striscia = striscia("verde", "verde"))
        assertTrue(schedeDeiDispositivi(vecchia).isEmpty())
    }

    @Test
    fun `aggregare i semafori, un rosso basta, poi un verde, poi niente`() {
        val regole = listOf(
            regola(1, dispositivo = null, ieri = "verde", oggi = "grigio"),
            regola(2, dispositivo = null, ieri = "rosso", oggi = "grigio"),
        )
        assertEquals(listOf(F, N), aggregaSemafori(regole, listOf(ieri, oggi)).map { it.segnale })
        assertEquals(listOf(M), aggregaSemafori(regole.take(1), listOf(ieri)).map { it.segnale })
        assertEquals(listOf(N), aggregaSemafori(emptyList(), listOf(ieri)).map { it.segnale })
        assertTrue(aggregaSemafori(regole, emptyList()).isEmpty())
    }
}
