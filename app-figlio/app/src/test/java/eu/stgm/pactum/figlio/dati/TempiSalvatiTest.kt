package eu.stgm.pactum.figlio.dati

import eu.stgm.pactum.figlio.rete.PostinoClient
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (0.16, contratto v3.8) I tempi arrivano solo con GET /api/patto?tempi=1 (Oggi
 * e la pagina Tempo). La copia locale li tiene: una rilettura senza tempi (la
 * sentinella, il worker, le altre schermate) non cancella quelli salvati —
 * PattoLocale.salva scrive `nuovo.conTempiDi(copia di prima)`.
 */
class TempiSalvatiTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun patto(testo: String): Patto = json.decodeFromString(Patto.serializer(), testo)

    private val conTempi = patto(
        """
        { "regole": [ { "id": 1, "tipo": "limite_tempo" } ], "letto_con": "imp-A",
          "dispositivo": { "id": 2, "tipo": "telefono" },
          "uso_recente": [ { "giorno": "2026-10-05", "totale_minuti": 42 } ],
          "medie": { "settimana": { "minuti": 42, "giorni": 1, "totale": 42 } },
          "dispositivi": [ { "id": 2, "tipo": "telefono", "uso_recente": [ { "giorno": "2026-10-05", "totale_minuti": 42 } ] },
                           { "id": 3, "nome": "PC", "tipo": "computer",
                             "uso_recente": [ { "giorno": "2026-10-05", "totale_minuti": 90 } ],
                             "medie": { "settimana": { "minuti": 90, "giorni": 1, "totale": 90 } } } ] }
        """,
    )

    /** La rilettura della sentinella: il patto della v3.7, senza tempi, con una regola nuova. */
    private val senzaTempi = patto(
        """
        { "regole": [ { "id": 1, "tipo": "limite_tempo" }, { "id": 5, "tipo": "fascia_oraria" } ], "letto_con": "imp-A",
          "dispositivo": { "id": 2, "tipo": "telefono" },
          "dispositivi": [ { "id": 2, "tipo": "telefono" }, { "id": 3, "nome": "PC", "tipo": "computer" } ] }
        """,
    )

    @Test
    fun `chiede i tempi solo chi li vuole`() {
        assertEquals("/api/patto?tempi=1", PostinoClient.percorsoPatto(conTempi = true))
        assertEquals("/api/patto", PostinoClient.percorsoPatto(conTempi = false))
    }

    @Test
    fun `una lettura con i tempi li porta, una senza no`() {
        assertTrue(conTempi.conTempi)
        assertFalse(senzaTempi.conTempi)
    }

    @Test
    fun `una rilettura senza tempi non cancella quelli salvati`() {
        val salvata = senzaTempi.conTempiDi(conTempi)
        // Il resto è quello nuovo (la regola appena nata c'è)…
        assertEquals(listOf(1L, 5L), salvata.regole.map { it.id })
        // …i tempi sono quelli di prima, di questo telefono e del computer.
        assertEquals(42, LetturaTempi.usoRecente(salvata.usoRecenteGrezzo)!!.single().totaleMinuti)
        assertEquals(42, LetturaTempi.medie(salvata.medieGrezze)!!.settimana!!.totale)
        val pc = salvata.dispositivi.first { it.id == 3L }
        assertEquals(90, LetturaTempi.usoRecente(pc.usoRecenteGrezzo)!!.single().totaleMinuti)
        assertEquals(90, LetturaTempi.medie(pc.medieGrezze)!!.settimana!!.totale)
    }

    @Test
    fun `e nemmeno due riletture di fila`() {
        val dopoUna = senzaTempi.conTempiDi(conTempi)
        val dopoDue = senzaTempi.conTempiDi(dopoUna)
        assertEquals(LetturaTempi.usoRecente(conTempi.usoRecenteGrezzo), LetturaTempi.usoRecente(dopoDue.usoRecenteGrezzo))
    }

    @Test
    fun `una lettura coi tempi nuovi prende il posto dei vecchi`() {
        val nuova = patto(
            """
            { "letto_con": "imp-A", "dispositivo": { "id": 2, "tipo": "telefono" },
              "uso_recente": [ { "giorno": "2026-10-06", "totale_minuti": 7 } ], "medie": null,
              "dispositivi": [ { "id": 3, "tipo": "computer", "uso_recente": [] } ] }
            """,
        )
        val salvata = nuova.conTempiDi(conTempi)
        assertEquals("2026-10-06", LetturaTempi.usoRecente(salvata.usoRecenteGrezzo)!!.single().giorno)
        assertEquals(emptyList<UsoGiornoServer>(), LetturaTempi.usoRecente(salvata.dispositivi.single().usoRecenteGrezzo))
    }

    @Test
    fun `i tempi di un altro collegamento o di un altro telefono non passano`() {
        val altroCollegamento = senzaTempi.copy(lettoCon = "imp-B")
        assertNull(altroCollegamento.conTempiDi(conTempi).usoRecenteGrezzo)
        val altroTelefono = senzaTempi.copy(dispositivo = Dispositivo(id = 9, tipo = "telefono"))
        assertNull(altroTelefono.conTempiDi(conTempi).usoRecenteGrezzo)
    }

    @Test
    fun `senza una copia coi tempi non si inventa niente`() {
        assertNull(senzaTempi.conTempiDi(null).usoRecenteGrezzo)
        assertNull(senzaTempi.conTempiDi(senzaTempi).usoRecenteGrezzo)
    }

    @Test
    fun `un dispositivo nuovo resta senza tempi, uno sparito non torna`() {
        val conNuovo = senzaTempi.copy(
            dispositivi = listOf(Dispositivo(id = 2, tipo = "telefono"), Dispositivo(id = 4, nome = "Tablet", tipo = "telefono")),
        )
        val salvata = conNuovo.conTempiDi(conTempi)
        assertEquals(listOf(2L, 4L), salvata.dispositivi.map { it.id })
        assertNull(salvata.dispositivi.first { it.id == 4L }.usoRecenteGrezzo)
    }
}
