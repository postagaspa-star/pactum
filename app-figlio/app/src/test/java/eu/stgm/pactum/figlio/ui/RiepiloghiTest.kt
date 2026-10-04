package eu.stgm.pactum.figlio.ui

import eu.stgm.pactum.figlio.permessi.StatoPermessi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (0.15) Le scelte di presentazione di Oggi e delle regole: la sola card in
 * cima, i permessi da sistemare, i giorni di una fascia in parole.
 */
class RiepiloghiTest {

    @Test
    fun `nessuna card in cima quando non c'è niente`() {
        assertEquals(CimaOggi(null, sessioneInRiga = false, bloccoProgrammatoInRiga = false), Cima.di(false, false, false))
    }

    @Test
    fun `la sessione in corso ha la card se non c'è il blocco`() {
        assertEquals(CimaOggi(CardCima.SESSIONE, sessioneInRiga = false, bloccoProgrammatoInRiga = false), Cima.di(false, true, false))
    }

    @Test
    fun `il blocco vince, e la sessione diventa una riga - mai due card piene`() {
        val cima = Cima.di(bloccato = true, sessioneInCorso = true, bloccoProgrammato = false)
        assertEquals(CardCima.BLOCCO, cima.card)
        assertTrue(cima.sessioneInRiga)
        assertFalse(cima.bloccoProgrammatoInRiga)
    }

    @Test
    fun `un blocco programmato è sempre e solo una riga`() {
        assertEquals(CimaOggi(null, sessioneInRiga = false, bloccoProgrammatoInRiga = true), Cima.di(false, false, true))
        // Anche con la sessione in corso: la card è della sessione, il blocco una riga.
        assertEquals(CimaOggi(CardCima.SESSIONE, sessioneInRiga = false, bloccoProgrammatoInRiga = true), Cima.di(false, true, true))
        // Col blocco già partito la riga del programmato non serve.
        assertFalse(Cima.di(true, false, true).bloccoProgrammatoInRiga)
    }

    @Test
    fun `i permessi che mancano, nell'ordine dei passi`() {
        val tutti = StatoPermessi(accessoUso = true, esenzioneBatteria = true, notifiche = true, mostraSopra = true)
        assertEquals(emptyList<Permesso>(), Permessi.mancanti(tutti))
        assertEquals(listOf(Permesso.MOSTRA_SOPRA), Permessi.mancanti(tutti.copy(mostraSopra = false)))
        assertEquals(
            listOf(Permesso.BATTERIA, Permesso.NOTIFICHE),
            Permessi.mancanti(tutti.copy(esenzioneBatteria = false, notifiche = false)),
        )
        val nessuno = StatoPermessi(accessoUso = false, esenzioneBatteria = false, notifiche = false, mostraSopra = false)
        assertEquals(Permesso.entries.toList(), Permessi.mancanti(nessuno))
        assertTrue(Permessi.concesso(tutti, Permesso.USO))
        assertFalse(Permessi.concesso(nessuno, Permesso.NOTIFICHE))
    }

    private val parole = ParoleGiorniFascia(ogniGiorno = "ogni giorno", feriali = "dal lunedì al venerdì")

    @Test
    fun `tutti e sette i giorni sono ogni giorno, in qualunque ordine`() {
        assertEquals("ogni giorno", FraseGiorni.di(listOf("lun", "mar", "mer", "gio", "ven", "sab", "dom"), parole))
        assertEquals("ogni giorno", FraseGiorni.di(listOf("dom", "sab", "ven", "gio", "mer", "mar", "lun"), parole))
    }

    @Test
    fun `da lunedì a venerdì si dice in parole`() {
        assertEquals("dal lunedì al venerdì", FraseGiorni.di(listOf("lun", "mar", "mer", "gio", "ven"), parole))
        assertEquals("dal lunedì al venerdì", FraseGiorni.di(listOf("ven", "lun", "gio", "mar", "mer"), parole))
    }

    @Test
    fun `gli altri giorni restano un elenco, in ordine di settimana`() {
        assertEquals("lun, mer, ven", FraseGiorni.di(listOf("ven", "lun", "mer"), parole))
        assertEquals("sab, dom", FraseGiorni.di(listOf("dom", "sab"), parole))
        assertEquals("lun, mar, mer, gio, ven, sab", FraseGiorni.di(listOf("lun", "mar", "mer", "gio", "ven", "sab"), parole))
    }

    @Test
    fun `nessun giorno è una stringa vuota, e un giorno sconosciuto si mostra com'è in fondo`() {
        assertEquals("", FraseGiorni.di(emptyList(), parole))
        assertEquals("", FraseGiorni.di(listOf(" ", ""), parole))
        assertEquals("lun, xyz", FraseGiorni.di(listOf("xyz", "lun"), parole))
        // I doppioni non contano due volte.
        assertEquals("ogni giorno", FraseGiorni.di(listOf("lun", "lun", "mar", "mer", "gio", "ven", "sab", "dom"), parole))
    }
}
