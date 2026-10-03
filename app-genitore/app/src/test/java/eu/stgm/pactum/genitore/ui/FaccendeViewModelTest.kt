package eu.stgm.pactum.genitore.ui

import eu.stgm.pactum.genitore.dati.CodiciErrore
import eu.stgm.pactum.genitore.dati.CorpoNuoveFaccende
import eu.stgm.pactum.genitore.dati.Faccenda
import eu.stgm.pactum.genitore.dati.RiferimentoGenitore
import eu.stgm.pactum.genitore.dati.StatiFaccenda
import eu.stgm.pactum.genitore.rete.EsitoFaccende
import eu.stgm.pactum.genitore.rete.EsitoFoto
import eu.stgm.pactum.genitore.rete.EsitoScrittura
import eu.stgm.pactum.genitore.rete.FonteFaccende
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * (0.13) Il ViewModel delle faccende, cioè il suo motore ([GestoreFaccende]), con
 * un server finto: il cambio di figlio (mai le faccende di uno sotto il nome
 * dell'altro), il doppio tocco (mai faccende date due volte), la risposta persa
 * (mai "Faccenda data." per una faccenda vecchia), la memoria delle foto (mai la
 * foto vecchia con "Boccia" per quella nuova) e il collegamento tolto (401).
 */
class FaccendeViewModelTest {

    private val mamma = RiferimentoGenitore(2, "Mamma")
    private val papa = RiferimentoGenitore(1, "Papà")
    private val adesso: Instant = Instant.parse("2026-10-02T13:10:00Z")

    /** Il server finto: ogni risposta si decide nel test (anche una che aspetta). */
    private class ServerFinto : FonteFaccende {
        var lettura: suspend (Long?) -> EsitoFaccende = { EsitoFaccende.Lette(emptyList()) }
        var dai: suspend (CorpoNuoveFaccende) -> EsitoScrittura<List<Faccenda>?> = { EsitoScrittura.Fallito }
        var foto: suspend (Long) -> EsitoFoto = { EsitoFoto.Fallita }
        var invii = 0
        var fotoScaricate = 0

        override suspend fun leggiFaccende(figlioId: Long?): EsitoFaccende = lettura(figlioId)

        override suspend fun daiFaccende(corpo: CorpoNuoveFaccende): EsitoScrittura<List<Faccenda>?> {
            invii++
            return dai(corpo)
        }

        override suspend fun bocciaFaccenda(faccendaId: Long, nota: String?): EsitoScrittura<Faccenda?> =
            EsitoScrittura.Riuscito(null)

        override suspend fun annullaFaccenda(faccendaId: Long): EsitoScrittura<Faccenda?> = EsitoScrittura.Riuscito(null)

        override suspend fun scaricaFoto(faccendaId: Long): EsitoFoto {
            fotoScaricate++
            return foto(faccendaId)
        }
    }

    private fun CoroutineScope.gestore(server: ServerFinto) = GestoreFaccende(
        ambito = this,
        fonte = { server },
        decodifica = { String(it) },
        orologio = { adesso },
    )

    /** Lascia andare avanti i lavori del motore (un filo solo: niente attese vere). */
    private suspend fun calma() = repeat(50) { yield() }

    private fun faccenda(
        id: Long,
        titolo: String = "Svuota la lavastoviglie",
        stato: String = StatiFaccenda.DA_FARE,
        creataDa: RiferimentoGenitore? = mamma,
        creataTs: String = "2026-10-01T08:00:00+00:00",
        fotoTs: String? = null,
        foto: Boolean = false,
    ) = Faccenda(
        id = id,
        figlioId = 1,
        titolo = titolo,
        stato = stato,
        bloccoDa = "2026-10-01T08:00:00+00:00",
        creataTs = creataTs,
        creataDa = creataDa,
        fotoTs = fotoTs,
        foto = foto,
    )

    // --- il cambio di figlio -------------------------------------------------------------

    @Test
    fun `cambiando figlio non resta mai l'elenco dell'altro`() = runBlocking {
        val server = ServerFinto()
        val diLuca = listOf(faccenda(1, "Rifai il letto"))
        val diSara = listOf(faccenda(2, "Porta fuori il cane"))
        server.lettura = { id -> EsitoFaccende.Lette(if (id == 1L) diLuca else diSara) }
        val g = gestore(server)

        g.aggiorna(1)
        calma()
        assertTrue(g.stato.value.di(1))
        assertEquals(diLuca, g.stato.value.faccende)

        // Sara non è mai stata letta: niente, finché non arriva il suo elenco. Mai quello di Luca.
        g.aggiorna(2)
        assertTrue(g.stato.value.di(2))
        assertNull(g.stato.value.faccende)
        calma()
        assertEquals(diSara, g.stato.value.faccende)

        // Tornando a Luca si vede subito quello che si ricorda di lui.
        g.aggiorna(1)
        assertEquals(diLuca, g.stato.value.faccende)
        calma()
        g.dimentica()
    }

    @Test
    fun `una risposta in ritardo per il figlio di prima non finisce sotto l'altro`() = runBlocking {
        val server = ServerFinto()
        val porta = CompletableDeferred<EsitoFaccende>()
        val diSara = listOf(faccenda(2, "Porta fuori il cane"))
        server.lettura = { id -> if (id == 1L) porta.await() else EsitoFaccende.Lette(diSara) }
        val g = gestore(server)

        g.aggiorna(1)
        calma()
        g.aggiorna(2)
        calma()
        porta.complete(EsitoFaccende.Lette(listOf(faccenda(1, "Rifai il letto"))))
        calma()
        assertTrue(g.stato.value.di(2))
        assertEquals(diSara, g.stato.value.faccende)
        g.dimentica()
    }

    // --- il doppio invio -----------------------------------------------------------------

    @Test
    fun `un secondo tocco mentre il primo è in volo non manda niente`() = runBlocking {
        val server = ServerFinto()
        val porta = CompletableDeferred<EsitoScrittura<List<Faccenda>?>>()
        server.dai = { porta.await() }
        val g = gestore(server)
        g.aggiorna(1)
        calma()

        g.daiFaccende(1, listOf("Rifai il letto"), null, null, mamma)
        calma()
        assertTrue(g.stato.value.invio)
        g.daiFaccende(1, listOf("Rifai il letto"), null, null, mamma)
        calma()
        assertEquals(1, server.invii)

        porta.complete(EsitoScrittura.Riuscito(listOf(faccenda(9, "Rifai il letto"))))
        calma()
        assertFalse(g.stato.value.invio)
        assertEquals(EventoFaccende.Date(1), g.stato.value.evento)
        assertEquals(1, server.invii)
        g.dimentica()
    }

    // --- la risposta persa ---------------------------------------------------------------

    @Test
    fun `risposta persa con l'elenco mai letto, esito incerto e mai faccenda data`() = runBlocking {
        val server = ServerFinto()
        // Una faccenda VECCHIA con lo stesso titolo: senza l'elenco di prima sembrerebbe nuova.
        server.lettura = { EsitoFaccende.Lette(listOf(faccenda(3, "Rifai il letto"))) }
        server.dai = { EsitoScrittura.Fallito }
        val g = gestore(server)

        g.daiFaccende(1, listOf("Rifai il letto"), null, null, mamma)
        calma()
        assertEquals(EventoFaccende.Rifiuto(CodiciErrore.ESITO_INCERTO, GestoFaccende.DAI, 1), g.stato.value.evento)
        g.dimentica()
    }

    @Test
    fun `risposta persa con l'elenco letto, le tue nuove contano come date`() = runBlocking {
        val server = ServerFinto()
        var arrivate = false
        server.lettura = {
            EsitoFaccende.Lette(
                if (arrivate) {
                    listOf(faccenda(9, "Rifai il letto", creataTs = "2026-10-02T13:10:01+00:00"))
                } else {
                    emptyList()
                },
            )
        }
        server.dai = {
            arrivate = true // il server le ha create, ma la risposta si perde
            EsitoScrittura.Fallito
        }
        val g = gestore(server)
        g.aggiorna(1)
        calma()

        g.daiFaccende(1, listOf("Rifai il letto"), null, null, mamma)
        calma()
        assertEquals(EventoFaccende.Date(1), g.stato.value.evento)
        g.dimentica()
    }

    @Test
    fun `risposta persa e niente di nuovo, si può riprovare`() = runBlocking {
        val server = ServerFinto()
        server.dai = { EsitoScrittura.Fallito }
        val g = gestore(server)
        g.aggiorna(1)
        calma()

        g.daiFaccende(1, listOf("Rifai il letto"), null, null, papa)
        calma()
        assertEquals(EventoFaccende.Rifiuto(null, GestoFaccende.DAI, 1), g.stato.value.evento)
        g.dimentica()
    }

    // --- la memoria delle foto -------------------------------------------------------------

    private val primaFoto = "2026-10-02T10:00:00+00:00"
    private val fotoNuova = "2026-10-02T12:00:00+00:00"

    @Test
    fun `dopo una bocciatura e una foto nuova, mai la foto vecchia dalla memoria`() = runBlocking {
        val server = ServerFinto()
        var elenco = listOf(faccenda(5, stato = StatiFaccenda.FATTA, fotoTs = primaFoto, foto = true))
        var fotoSulServer = "foto vecchia"
        server.lettura = { EsitoFaccende.Lette(elenco) }
        server.foto = { EsitoFoto.Arrivata(fotoSulServer.toByteArray()) }
        val g = gestore(server)
        g.aggiorna(1)
        calma()

        g.apriFoto(5, primaFoto)
        calma()
        assertEquals("foto vecchia", g.stato.value.foto?.immagine)
        assertEquals(setOf(ChiaveFoto(5, primaFoto)), g.fotoTenute)
        g.chiudiFoto()

        // Un altro genitore la boccia, il figlio ne manda un'altra: l'elenco ha un'altra ora della foto.
        elenco = listOf(faccenda(5, stato = StatiFaccenda.FATTA, fotoTs = fotoNuova, foto = true))
        fotoSulServer = "foto nuova"
        g.aggiorna(1)
        calma()
        assertTrue(g.fotoTenute.isEmpty())

        g.apriFoto(5, fotoNuova)
        calma()
        assertEquals("foto nuova", g.stato.value.foto?.immagine)
        assertEquals(fotoNuova, g.stato.value.foto?.fotoTs)
        assertEquals(2, server.fotoScaricate)

        // Riaprire la stessa foto non la riscarica.
        g.chiudiFoto()
        g.apriFoto(5, fotoNuova)
        assertEquals("foto nuova", g.stato.value.foto?.immagine)
        assertEquals(2, server.fotoScaricate)
        g.dimentica()
    }

    @Test
    fun `la foto aperta si cambia da sola se intanto ne è arrivata un'altra, o se è stata bocciata`() = runBlocking {
        val server = ServerFinto()
        var elenco = listOf(faccenda(5, stato = StatiFaccenda.FATTA, fotoTs = primaFoto, foto = true))
        var fotoSulServer = "foto vecchia"
        server.lettura = { EsitoFaccende.Lette(elenco) }
        server.foto = { EsitoFoto.Arrivata(fotoSulServer.toByteArray()) }
        val g = gestore(server)
        g.aggiorna(1)
        calma()
        g.apriFoto(5, primaFoto)
        calma()

        elenco = listOf(faccenda(5, stato = StatiFaccenda.FATTA, fotoTs = fotoNuova, foto = true))
        fotoSulServer = "foto nuova"
        g.aggiorna(1)
        calma()
        assertEquals(fotoNuova, g.stato.value.foto?.fotoTs)
        assertEquals("foto nuova", g.stato.value.foto?.immagine)

        // Bocciata da un altro genitore, senza ancora una foto nuova: la foto non c'è più.
        elenco = listOf(faccenda(5, stato = StatiFaccenda.DA_FARE, fotoTs = null, foto = false))
        g.aggiorna(1)
        calma()
        assertNull(g.stato.value.foto?.immagine)
        assertEquals(ProblemaFoto.NON_TROVATA, g.stato.value.foto?.problema)
        g.dimentica()
    }

    // --- il collegamento tolto --------------------------------------------------------------

    @Test
    fun `un 401 butta faccende e foto, e lo dice`() = runBlocking {
        val server = ServerFinto()
        server.lettura = { EsitoFaccende.Lette(listOf(faccenda(5, stato = StatiFaccenda.FATTA, fotoTs = primaFoto, foto = true))) }
        server.foto = { EsitoFoto.Arrivata("foto".toByteArray()) }
        val g = gestore(server)
        g.aggiorna(1)
        calma()
        g.apriFoto(5, primaFoto)
        calma()
        assertEquals(1, g.fotoTenute.size)

        server.lettura = { EsitoFaccende.NonAutorizzato }
        g.aggiorna(1)
        calma()
        assertTrue(g.stato.value.collegamentoNonValido)
        assertNull(g.stato.value.faccende)
        assertNull(g.stato.value.foto)
        assertTrue(g.fotoTenute.isEmpty())
        assertFalse(g.stato.value.errore)
        g.dimentica()
    }

    @Test
    fun `un 401 su un gesto fa lo stesso`() = runBlocking {
        val server = ServerFinto()
        server.dai = { EsitoScrittura.Rifiutato(CodiciErrore.COLLEGAMENTO_NON_VALIDO) }
        val g = gestore(server)
        g.aggiorna(1)
        calma()
        g.daiFaccende(1, listOf("Rifai il letto"), null, null, mamma)
        calma()
        assertTrue(g.stato.value.collegamentoNonValido)
        assertEquals(
            EventoFaccende.Rifiuto(CodiciErrore.COLLEGAMENTO_NON_VALIDO, GestoFaccende.DAI, 1),
            g.stato.value.evento,
        )
        g.dimentica()
    }
}
