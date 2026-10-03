package eu.stgm.pactum.figlio.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (0.13) La coda degli eventi in pacchi: se il server risponde 413 (corpo
 * oltre 8 MB) si rimanda in pacchi sempre più piccoli, senza perdere niente e
 * senza ripetere mai lo stesso pacco troppo grande.
 */
class PacchiEventiTest {

    /** Un server finto: accetta un pacco se pesa al massimo [massimo] (di solito ogni evento pesa quanto il suo numero). */
    private class Server(
        private val massimo: Int,
        private val peso: (List<Int>) -> Int = { it.sum() },
        private val rete: (Int) -> Boolean = { true },
    ) {
        val richieste = mutableListOf<List<Int>>()
        val arrivati = mutableListOf<Int>()

        fun manda(pacco: List<Int>): Int {
            richieste += pacco
            if (!rete(richieste.size)) return 0
            if (peso(pacco) > massimo) return PacchiEventi.TROPPO_GRANDE
            arrivati += pacco
            return 200
        }
    }

    @Test
    fun `una coda piccola parte tutta in una volta`() = runBlocking {
        val server = Server(massimo = 100)
        val esito = PacchiEventi.consegna(listOf(1, 2, 3)) { server.manda(it) }
        assertEquals(listOf(listOf(1, 2, 3)), server.richieste)
        assertEquals(listOf(1, 2, 3), esito.consegnati)
        assertTrue(esito.tutti)
    }

    @Test
    fun `413 - la coda si divide a metà, e ancora, finché passa - niente si perde`() = runBlocking {
        // Ogni evento pesa 10: passano al massimo 4 eventi per volta.
        val eventi = (1..16).toList()
        val server = Server(massimo = 40, peso = { it.size * 10 })
        val esito = PacchiEventi.consegna(eventi) { server.manda(it) }
        assertTrue(esito.tutti)
        assertEquals(eventi, esito.consegnati)
        assertEquals(eventi, server.arrivati)
        assertTrue(esito.scartati.isEmpty())
        // 16 → 8 + 8 → 4 + 4 + 4 + 4: mai lo stesso pacco troppo grande due volte.
        assertEquals(
            listOf((1..16).toList(), (1..8).toList(), (1..4).toList(), (5..8).toList(), (9..16).toList(), (9..12).toList(), (13..16).toList()),
            server.richieste,
        )
    }

    @Test
    fun `l'ordine degli eventi resta quello della coda`() = runBlocking {
        val eventi = (1..9).toList()
        val server = Server(massimo = 12)
        val esito = PacchiEventi.consegna(eventi) { server.manda(it) }
        assertTrue(esito.tutti)
        assertEquals(eventi, server.arrivati)
    }

    @Test
    fun `un evento troppo grande anche da solo esce dalla coda, gli altri arrivano`() = runBlocking {
        val server = Server(massimo = 50)
        val esito = PacchiEventi.consegna(listOf(5, 500, 7)) { server.manda(it) }
        assertEquals(listOf(500), esito.scartati)
        assertEquals(listOf(5, 7), esito.consegnati)
        assertTrue(esito.tutti)
        // Mai riprovato da solo una seconda volta.
        assertEquals(1, server.richieste.count { it == listOf(500) })
    }

    @Test
    fun `senza rete a metà - arrivati quelli arrivati, il resto resta in coda`() = runBlocking {
        // La terza richiesta non parte.
        val server = Server(massimo = 20, rete = { n -> n != 3 })
        val esito = PacchiEventi.consegna(listOf(10, 10, 10, 10)) { server.manda(it) }
        assertFalse(esito.tutti)
        assertEquals(listOf(10, 10), esito.consegnati)
        assertTrue(esito.scartati.isEmpty())
    }

    @Test
    fun `una coda vuota non manda niente`() = runBlocking {
        val server = Server(massimo = 1)
        val esito = PacchiEventi.consegna(emptyList<Int>()) { server.manda(it) }
        assertTrue(esito.tutti)
        assertTrue(server.richieste.isEmpty())
    }
}
