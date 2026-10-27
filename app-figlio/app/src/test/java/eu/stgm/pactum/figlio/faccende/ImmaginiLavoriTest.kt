package eu.stgm.pactum.figlio.faccende

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * (0.25, contratto v4.3) Le immagini che il genitore allega a un lavoro: si leggono
 * nel blocco e nell'elenco (solo i numeri 1..3), un server di prima non le manda, una
 * voce scritta male si salta senza far cadere il lavoro.
 */
class ImmaginiLavoriTest {

    @Test
    fun `nel blocco e nell'elenco si leggono i numeri`() {
        val voce = """{ "id": 5, "titolo": "Letto", "stato": "da_fare",
            "immagini": [ { "n": 2, "ts": "2026-10-27T10:00:00+00:00" }, { "n": 1, "ts": null } ] }"""
        assertEquals(listOf(1, 2), LetturaFaccende.daFare(Json.parseToJsonElement(voce))!!.immagini)
        assertEquals(listOf(1, 2), LetturaFaccende.faccenda(Json.parseToJsonElement(voce))!!.immagini)
    }

    @Test
    fun `un server di prima non le manda - nessuna`() {
        val voce = """{ "id": 5, "titolo": "Letto", "stato": "da_fare" }"""
        assertEquals(emptyList<Int>(), LetturaFaccende.daFare(Json.parseToJsonElement(voce))!!.immagini)
        assertEquals(emptyList<Int>(), LetturaFaccende.faccenda(Json.parseToJsonElement(voce))!!.immagini)
    }

    @Test
    fun `le voci scritte male si saltano, il lavoro resta`() {
        val voce = """{ "id": 5, "titolo": "Letto", "stato": "da_fare",
            "immagini": [ { "n": 0 }, { "n": 4 }, { "n": "1" }, 3, null, { "n": 3 }, { "n": 3 } ] }"""
        val f = LetturaFaccende.daFare(Json.parseToJsonElement(voce))!!
        assertEquals("Letto", f.titolo)
        assertEquals(listOf(1, 3), f.immagini) // "1" scritto come testo vale, come gli id
        assertEquals(emptyList<Int>(), LetturaFaccende.immagini(Json.parseToJsonElement(""""tante"""")))
    }

    @Test
    fun `nella pagina dei lavori arrivano dal blocco`() {
        val memoria = MemoriaBlocco(
            daFare = listOf(FaccendaDaFare(id = 5, titolo = "Letto", immagini = listOf(1, 3))),
        )
        assertEquals(listOf(1, 3), VistaFaccende.aperte(memoria).single().immagini)
    }
}
