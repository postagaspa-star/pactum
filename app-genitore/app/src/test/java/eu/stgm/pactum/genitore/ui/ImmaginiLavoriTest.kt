package eu.stgm.pactum.genitore.ui

import eu.stgm.pactum.genitore.dati.CodiciErrore
import eu.stgm.pactum.genitore.dati.CorpoNuoveFaccende
import eu.stgm.pactum.genitore.dati.Faccenda
import eu.stgm.pactum.genitore.dati.ImmagineFaccenda
import eu.stgm.pactum.genitore.dati.RiferimentoGenitore
import eu.stgm.pactum.genitore.dati.StatiFaccenda
import eu.stgm.pactum.genitore.rete.EsitoFaccende
import eu.stgm.pactum.genitore.rete.EsitoFoto
import eu.stgm.pactum.genitore.rete.EsitoScrittura
import eu.stgm.pactum.genitore.rete.FonteFaccende
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * (0.25, contratto v4.3) Le immagini allegate ai lavori, nel motore dei lavori: date
 * insieme ai lavori vanno a ognuno coi numeri 1, 2, 3; quelle che non partono si
 * contano; aggiunte dopo vanno nei numeri liberi; un server vecchio lo si dice.
 */
class ImmaginiLavoriTest {

    private val mamma = RiferimentoGenitore(2, "Mamma")

    private class ServerFinto : FonteFaccende {
        var date: List<Faccenda> = emptyList()
        var allegate = mutableListOf<Triple<Long, Int, String>>()
        var tolte = mutableListOf<Pair<Long, Int>>()
        var esitoAllega: (Long, Int) -> EsitoScrittura<Faccenda?> = { _, _ -> EsitoScrittura.Riuscito(null) }

        override suspend fun leggiFaccende(figlioId: Long?): EsitoFaccende = EsitoFaccende.Lette(date)

        override suspend fun daiFaccende(corpo: CorpoNuoveFaccende): EsitoScrittura<List<Faccenda>?> =
            EsitoScrittura.Riuscito(date)

        override suspend fun bocciaFaccenda(faccendaId: Long, nota: String?): EsitoScrittura<Faccenda?> =
            EsitoScrittura.Riuscito(null)

        override suspend fun annullaFaccenda(faccendaId: Long): EsitoScrittura<Faccenda?> = EsitoScrittura.Riuscito(null)

        override suspend fun scaricaFoto(faccendaId: Long): EsitoFoto = EsitoFoto.Fallita

        override suspend fun allegaImmagine(faccendaId: Long, n: Int, jpeg: ByteArray): EsitoScrittura<Faccenda?> {
            allegate += Triple(faccendaId, n, String(jpeg))
            return esitoAllega(faccendaId, n)
        }

        override suspend fun togliImmagine(faccendaId: Long, n: Int): EsitoScrittura<Faccenda?> {
            tolte += faccendaId to n
            return EsitoScrittura.Riuscito(null)
        }

        override suspend fun scaricaImmagine(faccendaId: Long, n: Int): EsitoFoto =
            if (n == 1) EsitoFoto.Arrivata("immagine $faccendaId-$n".toByteArray()) else EsitoFoto.Fallita
    }

    private fun CoroutineScope.gestore(server: ServerFinto) = GestoreFaccende(
        ambito = this,
        fonte = { server },
        decodifica = { String(it) },
        orologio = { Instant.parse("2026-10-27T13:10:00Z") },
    )

    private suspend fun calma() = repeat(50) { yield() }

    private fun faccenda(id: Long, vararg immagini: Int) = Faccenda(
        id = id,
        figlioId = 1,
        titolo = "Lavoro $id",
        stato = StatiFaccenda.DA_FARE,
        bloccoDa = "2026-10-27T13:10:00+00:00",
        creataTs = "2026-10-27T13:10:00+00:00",
        creataDa = mamma,
        immagini = immagini.map { ImmagineFaccenda(it) },
    )

    @Test
    fun `le immagini date coi lavori vanno a ognuno, coi numeri 1 2 3`() = runBlocking {
        val server = ServerFinto()
        server.date = listOf(faccenda(7), faccenda(8))
        val g = gestore(server)
        g.daiFaccende(1, listOf("Letto", "Cane"), null, null, mamma, listOf("a".toByteArray(), "b".toByteArray()))
        calma()
        assertEquals(EventoFaccende.Date(2, 0), g.stato.value.evento)
        assertEquals(
            listOf(Triple(7L, 1, "a"), Triple(7L, 2, "b"), Triple(8L, 1, "a"), Triple(8L, 2, "b")),
            server.allegate,
        )
        g.dimentica()
    }

    @Test
    fun `senza immagini non parte niente in piu`() = runBlocking {
        val server = ServerFinto()
        server.date = listOf(faccenda(7))
        val g = gestore(server)
        g.daiFaccende(1, listOf("Letto"), null, null, mamma)
        calma()
        assertEquals(EventoFaccende.Date(1, 0), g.stato.value.evento)
        assertEquals(emptyList<Triple<Long, Int, String>>(), server.allegate)
        g.dimentica()
    }

    @Test
    fun `quelle che non partono si contano, il lavoro resta dato`() = runBlocking {
        val server = ServerFinto()
        server.date = listOf(faccenda(7))
        server.esitoAllega = { _, n -> if (n == 2) EsitoScrittura.Fallito else EsitoScrittura.Riuscito(null) }
        val g = gestore(server)
        g.daiFaccende(1, listOf("Letto"), null, null, mamma, listOf("a".toByteArray(), "b".toByteArray()))
        calma()
        assertEquals(EventoFaccende.Date(1, 1), g.stato.value.evento)
        g.dimentica()
    }

    @Test
    fun `aggiunte dopo vanno nei numeri liberi, al massimo 3`() = runBlocking {
        val server = ServerFinto()
        val g = gestore(server)
        g.allegaImmagini(1, faccenda(7, 2), listOf("x".toByteArray(), "y".toByteArray(), "z".toByteArray()))
        calma()
        assertEquals(listOf(Triple(7L, 1, "x"), Triple(7L, 3, "y")), server.allegate)
        // "z" non ci sta: conta come non partita
        assertEquals(EventoFaccende.ImmaginiCambiate(mancate = 1), g.stato.value.evento)
        g.dimentica()
    }

    @Test
    fun `un server vecchio lo si dice`() = runBlocking {
        val server = ServerFinto()
        server.esitoAllega = { _, _ -> EsitoScrittura.Rifiutato(CodiciErrore.SERVER_DA_AGGIORNARE) }
        val g = gestore(server)
        g.allegaImmagini(1, faccenda(7), listOf("x".toByteArray()))
        calma()
        assertEquals(EventoFaccende.ImmaginiCambiate(mancate = 1, serverVecchio = true), g.stato.value.evento)
        g.dimentica()
    }

    @Test
    fun `togliere e caricare`() = runBlocking {
        val server = ServerFinto()
        val g = gestore(server)
        g.togliImmagine(1, faccenda(7, 1), 1)
        calma()
        assertEquals(listOf(7L to 1), server.tolte)
        assertEquals(EventoFaccende.ImmaginiCambiate(), g.stato.value.evento)
        assertEquals("immagine 7-1", g.caricaImmagine(7, 1))
        assertNull(g.caricaImmagine(7, 2))
        g.dimentica()
    }
}
