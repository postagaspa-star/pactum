package eu.stgm.pactum.figlio.ui

import eu.stgm.pactum.figlio.dati.CorsaCollegamento
import eu.stgm.pactum.figlio.dati.EsitoAbbinamento
import eu.stgm.pactum.figlio.dati.EsitoCollegamento
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (0.15) Ogni schermata prende solo l'esito dei collegamenti che ha fatto
 * partire lei: l'esito lasciato dal passo Collega del primo avvio non chiude
 * il modulo delle Impostazioni al primo "Cambia".
 */
class OriginiCollegamentoTest {

    private val processo = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun chiudi() {
        processo.cancel()
    }

    private fun finito(corsa: CorsaCollegamento): CorsaCollegamento.Stato.Finito = runBlocking {
        withTimeout(5_000) { corsa.stato.first { it is CorsaCollegamento.Stato.Finito } as CorsaCollegamento.Stato.Finito }
    }

    @Test
    fun `l'esito del primo avvio non e' delle Impostazioni`() {
        val corsa = CorsaCollegamento(processo)
        val registro = RegistroOrigini()
        assertTrue(corsa.avvia { EsitoCollegamento.ConCodice(EsitoAbbinamento.Collegato("token", null, null)) })
        registro.segna(corsa.ultimoAvviato, OriginiCollegamento.PRIMO_AVVIO)
        // Il passo Collega è sparito prima dell'esito: l'esito resta lì, non preso.
        val rimasto = finito(corsa)
        assertTrue(registro.eDi(rimasto.numero, OriginiCollegamento.PRIMO_AVVIO))
        assertFalse(registro.eDi(rimasto.numero, OriginiCollegamento.IMPOSTAZIONI))
    }

    @Test
    fun `l'esito di un collegamento fatto dalle Impostazioni e' loro, anche ricreate`() {
        val corsa = CorsaCollegamento(processo)
        val registro = RegistroOrigini()
        assertTrue(corsa.avvia { EsitoCollegamento.CodiceLungoSalvato })
        registro.segna(corsa.ultimoAvviato, OriginiCollegamento.IMPOSTAZIONI)
        val esito = finito(corsa)
        assertEquals(corsa.ultimoAvviato, esito.numero)
        assertTrue(registro.eDi(esito.numero, OriginiCollegamento.IMPOSTAZIONI))
    }

    @Test
    fun `il numero dell'ultimo avviato cresce a ogni collegamento e uno mai segnato non e' di nessuno`() {
        val corsa = CorsaCollegamento(processo)
        val registro = RegistroOrigini()
        assertEquals(0L, corsa.ultimoAvviato)
        assertTrue(corsa.avvia { EsitoCollegamento.IndirizzoNonValido })
        val primo = finito(corsa).numero
        assertTrue(corsa.avvia { EsitoCollegamento.IndirizzoNonValido })
        assertEquals(primo + 1, corsa.ultimoAvviato)
        assertFalse(registro.eDi(primo, OriginiCollegamento.IMPOSTAZIONI))
    }

    @Test
    fun `se ne tengono solo gli ultimi`() {
        val registro = RegistroOrigini(massimo = 3)
        (1L..5L).forEach { registro.segna(it, OriginiCollegamento.IMPOSTAZIONI) }
        assertFalse(registro.eDi(1, OriginiCollegamento.IMPOSTAZIONI))
        assertFalse(registro.eDi(2, OriginiCollegamento.IMPOSTAZIONI))
        assertTrue(registro.eDi(5, OriginiCollegamento.IMPOSTAZIONI))
    }
}
