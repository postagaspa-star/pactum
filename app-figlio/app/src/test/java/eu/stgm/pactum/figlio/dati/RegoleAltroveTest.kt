package eu.stgm.pactum.figlio.dati

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * (v3) Le regole del figlio sugli altri suoi dispositivi: quante sono, e quale
 * numero vale quando manca la rete. Da qui dipende se il telefono chiede "Crea
 * la prima regola" a chi le regole le ha già sul computer.
 */
class RegoleAltroveTest {

    private val telefono = 1L
    private val computer = 2L
    private val vecchioComputer = 4L

    private fun regola(id: Long, dispositivo: Long?, attiva: Boolean = true) =
        Regola(id = id, tipo = TipiRegola.LIMITE_TEMPO, attiva = attiva, dispositivoId = dispositivo)

    private val vitaReale = Regola(id = 9, tipo = TipiRegola.VITA_REALE, dispositivoId = null)

    @Test
    fun `contano le regole attive degli altri dispositivi`() {
        val qui = listOf(regola(1, telefono), vitaReale)
        val delFiglio = qui + listOf(regola(2, computer), regola(3, computer))
        assertEquals(2, RegoleAltrove.conta(delFiglio, qui, scollegati = emptySet()))
    }

    @Test
    fun `non contano quelle di questo telefono, la vita reale, le spente e gli scollegati`() {
        val qui = listOf(regola(1, telefono), vitaReale)
        val delFiglio = qui + listOf(
            regola(2, computer, attiva = false),
            regola(3, vecchioComputer),
        )
        assertEquals(0, RegoleAltrove.conta(delFiglio, qui, scollegati = setOf(vecchioComputer)))
    }

    @Test
    fun `solo sul computer, nessuna qui`() {
        val delFiglio = listOf(regola(2, computer))
        assertEquals(1, RegoleAltrove.conta(delFiglio, qui = emptyList(), scollegati = emptySet()))
    }

    // --- Senza rete: l'ultimo numero saputo -----------------------------------

    private val impronta = ConfigurazionePostino("https://pactum.taildbae63.ts.net", "token-di-questo-telefono").impronta

    @Test
    fun `senza rete vale l'ultimo numero letto con questo collegamento`() {
        assertEquals(2, RegoleAltrove.ultimoNoto(RegoleAltroveSalvate(conteggio = 2, lettoCon = impronta), impronta))
    }

    @Test
    fun `un numero letto con un altro collegamento non vale`() {
        // Telefono ricollegato a un altro dispositivo (o a un altro figlio): era un altro patto.
        val altra = ConfigurazionePostino("https://pactum.taildbae63.ts.net", "token-di-prima").impronta
        assertEquals(0, RegoleAltrove.ultimoNoto(RegoleAltroveSalvate(conteggio = 2, lettoCon = altra), impronta))
        assertEquals(0, RegoleAltrove.ultimoNoto(RegoleAltroveSalvate(conteggio = 2, lettoCon = ""), impronta))
    }

    @Test
    fun `mai saputo, zero`() {
        assertEquals(0, RegoleAltrove.ultimoNoto(null, impronta))
        assertEquals(0, RegoleAltrove.ultimoNoto(RegoleAltroveSalvate(conteggio = -1, lettoCon = impronta), impronta))
    }
}
