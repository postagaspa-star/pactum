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

    // --- v3: famiglia, figli e dispositivi -----------------------------------

    private val pattoV3 = """
        {
          "regole": [
            { "id": 1, "tipo": "limite_tempo", "parametri": { "app_o_categoria": "com.zhiliaoapp.musically", "minuti_al_giorno": 60 },
              "dispositivo_id": 1, "dispositivo": { "id": 1, "nome": "Telefono", "tipo": "telefono" } },
            { "id": 3, "tipo": "vita_reale", "parametri": {}, "dispositivo_id": null, "dispositivo": null },
            { "id": 12, "tipo": "fascia_oraria", "parametri": { "dalle": "22:00", "alle": "07:00", "giorni": ["lun"] },
              "dispositivo_id": 2, "dispositivo": { "id": 2, "nome": "Computer", "tipo": "computer" } }
          ],
          "striscia": [ { "data": "2026-09-23", "stato": "verde" } ],
          "figlio": { "id": 1, "nome": "Andrea" },
          "dispositivo": { "id": 1, "nome": "Telefono", "tipo": "telefono" },
          "striscia_dispositivo": [ { "data": "2026-09-23", "stato": "verde" } ],
          "dispositivi": [
            { "id": 1, "nome": "Telefono", "tipo": "telefono", "striscia": [ { "data": "2026-09-23", "stato": "verde" } ] },
            { "id": 2, "nome": "Computer", "tipo": "computer", "striscia": [ { "data": "2026-09-23", "stato": "rosso" } ] }
          ],
          "campo_nuovo": 42
        }
    """.trimIndent()

    @Test
    fun `il patto v3 porta figlio, dispositivo e i dispositivi con la loro striscia`() {
        val patto = json.decodeFromString(Patto.serializer(), pattoV3)
        assertEquals(Figlio(1, "Andrea"), patto.figlio)
        assertEquals(Dispositivo(1, "Telefono", "telefono"), patto.dispositivo)
        assertEquals(listOf(1L, 2L), patto.dispositivi.map { it.id })
        assertEquals(Segnale.FUORI_REGOLA, patto.dispositivi[1].striscia.inGiorniPatto().single().segnale)
        assertEquals(1, patto.strisciaDispositivo.size)
        assertEquals(ContestoDispositivi(questo = 1, dispositivi = patto.dispositivi), patto.contestoDispositivi())
        assertEquals(2L, patto.regole.last().idDispositivo)
        assertNull(patto.regole[1].idDispositivo)
    }

    @Test
    fun `sul telefono valgono le sue regole e la vita reale, mai quelle del computer`() {
        val patto = json.decodeFromString(Patto.serializer(), pattoV3)
        assertEquals(listOf(1L, 3L), patto.regoleDiQuestoDispositivo().map { it.id })
    }

    @Test
    fun `un server vecchio che non dice il dispositivo, tutte le regole valgono qui`() {
        val patto = json.decodeFromString(
            Patto.serializer(),
            """{ "regole": [ { "id": 1, "tipo": "limite_tempo" }, { "id": 2, "tipo": "vita_reale" } ] }""",
        )
        assertNull(patto.dispositivo)
        assertEquals(listOf(1L, 2L), patto.regoleDiQuestoDispositivo().map { it.id })
        assertEquals(ContestoDispositivi(questo = null, dispositivi = emptyList()), patto.contestoDispositivi())
    }

    @Test
    fun `il dispositivo della regola si ricava anche dal solo oggetto`() {
        val regola = Regola(id = 5, tipo = "limite_tempo", dispositivo = Dispositivo(id = 2, tipo = "computer"))
        assertEquals(2L, regola.idDispositivo)
        assertEquals(emptyList<Long>(), regoleDelDispositivo(listOf(regola), questo = 1).map { it.id })
    }

    @Test
    fun `un dispositivo a meta' non fa cadere la lettura del patto`() {
        val patto = json.decodeFromString(
            Patto.serializer(),
            """{ "regole": [], "dispositivo": { "nome": "Telefono" }, "dispositivi": [ { "id": 2 } ] }""",
        )
        assertEquals(0L, patto.dispositivo?.id)
        // Un id che non c'è non è "questo telefono": le regole valgono tutte, come prima.
        assertNull(patto.contestoDispositivi().questo)
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
