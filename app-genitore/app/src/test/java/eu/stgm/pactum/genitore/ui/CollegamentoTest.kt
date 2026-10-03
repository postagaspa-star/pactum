package eu.stgm.pactum.genitore.ui

import eu.stgm.pactum.genitore.dati.RiferimentoGenitore
import eu.stgm.pactum.genitore.rete.EsitoAbbinamento
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (0.13) Il collegamento col codice di 6 cifre: il server consuma il codice e dà
 * il token UNA volta sola. Se la pagina si chiude a metà (rotazione, cambio di
 * scheda, cambio di tema), il token arrivato va salvato lo stesso: se no il
 * genitore, riprovando, si sente dire "codice già usato" senza essere collegato.
 */
class CollegamentoTest {

    private val mamma = RiferimentoGenitore(2, "Mamma")

    @Test
    fun `chiuso a metà mentre aspetta il server, il token arrivato si salva lo stesso`() = runBlocking {
        val server = CompletableDeferred<EsitoAbbinamento>()
        var salvato: String? = null
        val lavoro = launch {
            collegaESalva(abbina = { server.await() }, salva = { salvato = it })
        }
        yield() // il collegamento parte e aspetta la risposta
        lavoro.cancel() // la pagina si chiude
        server.complete(EsitoAbbinamento.Collegato("token-nuovo", mamma))
        lavoro.join()
        assertEquals("token-nuovo", salvato)
    }

    @Test
    fun `chiuso a metà mentre salva, il salvataggio finisce`() = runBlocking {
        val disco = CompletableDeferred<Unit>()
        var salvato: String? = null
        val lavoro = launch {
            collegaESalva(
                abbina = { EsitoAbbinamento.Collegato("token-nuovo", mamma) },
                salva = { token ->
                    disco.await()
                    salvato = token
                },
            )
        }
        yield()
        lavoro.cancel()
        disco.complete(Unit)
        lavoro.join()
        assertEquals("token-nuovo", salvato)
    }

    @Test
    fun `un collegamento non riuscito non tocca quello di prima`() = runBlocking {
        var salvato: String? = null
        val esiti = listOf(
            EsitoAbbinamento.CodiceNonValido,
            EsitoAbbinamento.TipoNonCorrispondente("telefono"),
            EsitoAbbinamento.TroppiTentativi(60),
            EsitoAbbinamento.ServerDaAggiornare,
            EsitoAbbinamento.SenzaRete,
            EsitoAbbinamento.Errore,
        )
        esiti.forEach { esito ->
            assertEquals(esito, collegaESalva(abbina = { esito }, salva = { salvato = it }))
        }
        // Il codice d'accesso lungo (o il collegamento di prima) resta finché quello nuovo non riesce.
        assertNull(salvato)
    }

    @Test
    fun `riuscito, si salva e si dice chi sei`() = runBlocking {
        var salvato: String? = null
        val esito = collegaESalva(abbina = { EsitoAbbinamento.Collegato("token-nuovo", mamma) }, salva = { salvato = it })
        assertTrue(esito is EsitoAbbinamento.Collegato)
        assertEquals("token-nuovo", salvato)
        assertEquals("Collegato: sei «Mamma».", testoCollegato(ParoleDiProva, (esito as EsitoAbbinamento.Collegato).genitore))
    }
}
