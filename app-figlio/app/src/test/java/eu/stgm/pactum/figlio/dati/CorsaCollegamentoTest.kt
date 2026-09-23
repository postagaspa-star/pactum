package eu.stgm.pactum.figlio.dati

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Il collegamento col codice gira fuori dalla schermata: il server consuma il
 * codice appena lo riceve e il token lo dà una volta sola, quindi una
 * rotazione, una pausa o "Indietro" a metà non devono buttare via la risposta.
 * L'esito resta finché una schermata (anche ricreata) non lo prende.
 */
class CorsaCollegamentoTest {

    private val processo = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val corsa = CorsaCollegamento(processo)
    private val collegato = EsitoCollegamento.ConCodice(EsitoAbbinamento.Collegato("token", null, null))

    @After
    fun chiudi() {
        processo.cancel()
    }

    /** L'esito finito (aspetta al massimo 5 secondi). */
    private fun finito(): CorsaCollegamento.Stato.Finito = runBlocking {
        withTimeout(5_000) {
            corsa.stato.first { it is CorsaCollegamento.Stato.Finito } as CorsaCollegamento.Stato.Finito
        }
    }

    @Test
    fun `l'esito resta finche' una schermata non lo prende`() {
        assertTrue(corsa.avvia { collegato })
        val esito = finito()
        assertEquals(collegato, esito.esito)
        // Nessuna schermata l'ha preso: resta lì per la prossima.
        assertEquals(esito, corsa.stato.value)
        corsa.consuma(esito)
        assertEquals(CorsaCollegamento.Stato.Fermo, corsa.stato.value)
    }

    @Test
    fun `la schermata che se ne va non ferma il collegamento`() {
        val rispostaDelServer = CompletableDeferred<Unit>()
        val schermata = CoroutineScope(Job())
        schermata.launch { corsa.avvia { rispostaDelServer.await(); collegato } }
        runBlocking { withTimeout(5_000) { corsa.stato.first { it == CorsaCollegamento.Stato.InCorso } } }
        // Rotazione, pausa, "Indietro" dalle Impostazioni: la schermata non c'è più.
        schermata.cancel()
        rispostaDelServer.complete(Unit)
        assertEquals(collegato, finito().esito)
    }

    @Test
    fun `un secondo tocco mentre il primo e' in viaggio non manda un secondo codice`() {
        val rispostaDelServer = CompletableDeferred<Unit>()
        val partiti = AtomicInteger()
        assertTrue(corsa.avvia { partiti.incrementAndGet(); rispostaDelServer.await(); collegato })
        assertFalse(corsa.avvia { partiti.incrementAndGet(); collegato })
        rispostaDelServer.complete(Unit)
        assertEquals(collegato, finito().esito)
        assertEquals(1, partiti.get())
    }

    @Test
    fun `un imprevisto non lascia il collegamento appeso`() {
        assertTrue(corsa.avvia { throw IllegalStateException("disco pieno") })
        val esito = finito()
        assertEquals(EsitoCollegamento.ConCodice(EsitoAbbinamento.Errore), esito.esito)
        // Si può riprovare.
        corsa.consuma(esito)
        assertTrue(corsa.avvia { collegato })
        assertEquals(collegato, finito().esito)
    }

    @Test
    fun `un esito vecchio non tocca un collegamento partito dopo`() {
        assertTrue(corsa.avvia { EsitoCollegamento.IndirizzoNonValido })
        val vecchio = finito()
        val rispostaDelServer = CompletableDeferred<Unit>()
        assertTrue(corsa.avvia { rispostaDelServer.await(); collegato })
        corsa.consuma(vecchio)
        assertEquals(CorsaCollegamento.Stato.InCorso, corsa.stato.value)
        rispostaDelServer.complete(Unit)
        val nuovo = finito()
        assertEquals(collegato, nuovo.esito)
        assertNotEquals(vecchio.numero, nuovo.numero)
    }
}
