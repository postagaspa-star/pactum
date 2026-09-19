package eu.stgm.pactum.figlio.dati

import eu.stgm.pactum.design.Segnale
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GET /api/patto v2.4: il `riepilogo` e il `semaforo` di ogni regola, gli
 * stessi fatti della finestra del genitore (D3). Un server vecchio senza i
 * campi non deve rompere niente: niente riga, niente striscia piccola.
 */
class ModelliPattoTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `il patto v2_4 porta riepilogo e semaforo per regola`() {
        val corpo = """
            {
              "regole": [ { "id": 1, "tipo": "limite_tempo", "parametri": {},
                            "semaforo": [ { "data": "2026-09-18", "stato": "rosso" },
                                          { "data": "2026-09-19", "stato": "verde" } ] } ],
              "striscia": [ { "data": "2026-09-19", "stato": "verde" } ],
              "riepilogo": { "giorni_fuori_regola": 1, "interruzioni": 2 },
              "fuso": "Europe/Rome"
            }
        """.trimIndent()
        val patto = json.decodeFromString(Patto.serializer(), corpo)
        assertEquals(Riepilogo(giorniFuoriRegola = 1, interruzioni = 2), patto.riepilogo)
        assertEquals(
            listOf(Segnale.FUORI_REGOLA, Segnale.MANTENUTA),
            patto.regole.single().semaforo.inGiorniPatto().map { it.segnale },
        )
    }

    @Test
    fun `un server vecchio senza i campi nuovi non mostra la riga`() {
        val corpo = """{ "regole": [ { "id": 1, "tipo": "limite_tempo" } ] }"""
        val patto = json.decodeFromString(Patto.serializer(), corpo)
        assertNull(patto.riepilogo)
        assertTrue(patto.regole.single().semaforo.isEmpty())
    }

    @Test
    fun `il residuo del bonus e' il piu' piccolo dei due tetti`() {
        val patto = Patto(
            bonus = StatoBonus(
                giorno = ContatoreBonus(usati = 15, tetto = 30, residui = 15),
                settimana = ContatoreBonus(usati = 80, tetto = 90, residui = 10),
            ),
        )
        assertEquals(10, patto.residuoBonusOggi())
        assertNull(Patto().residuoBonusOggi())
    }
}
