package eu.stgm.pactum.figlio.dati

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (0.16, contratto v3.8) `uso_recente` e `medie` in GET /api/patto, letti con
 * prudenza: un pezzo scritto male si lascia cadere da solo, il resto del patto
 * si legge lo stesso, e un giorno senza fotografia non diventa mai uno zero.
 */
class LetturaTempiTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun el(testo: String): JsonElement = json.parseToJsonElement(testo)

    @Test
    fun `il giorno del contratto si legge tutto`() {
        val giorni = LetturaTempi.usoRecente(
            el(
                """
                [ { "giorno": "2026-10-04", "totale_minuti": 192, "aggiornato_ts": "2026-10-04T20:00:00+00:00",
                    "app": [ { "chiave": "com.zhiliaoapp.musically", "nome": "TikTok", "minuti": 65, "limite": 60, "regola_id": 1, "bonus": 0 },
                             { "chiave": "com.whatsapp", "minuti": 12 } ],
                    "categorie": [ { "chiave": "categoria:social", "minuti": 130, "limite": 120, "regola_id": 4, "bonus": 15 } ],
                    "sessioni_minuti": 40, "limite": 300, "regola_id": 9, "bonus": 0 } ]
                """,
            ),
        )!!
        val giorno = giorni.single()
        assertEquals("2026-10-04", giorno.giorno)
        assertEquals(192, giorno.totaleMinuti)
        assertEquals(listOf(UsoAppServer("com.zhiliaoapp.musically", "TikTok", 65), UsoAppServer("com.whatsapp", null, 12)), giorno.app)
        assertEquals(listOf(UsoCategoriaServer("categoria:social", 130, 120)), giorno.categorie)
        assertEquals(40, giorno.sessioniMinuti)
    }

    @Test
    fun `senza il campo, o se non e' una lista, non si sa nulla`() {
        assertNull(LetturaTempi.usoRecente(null))
        assertNull(LetturaTempi.usoRecente(el("null")))
        assertNull(LetturaTempi.usoRecente(el("""{ "giorno": "2026-10-04" }""")))
        assertNull(LetturaTempi.usoRecente(el("\"tanti\"")))
        assertNull(LetturaTempi.medie(null))
        assertNull(LetturaTempi.medie(el("[]")))
    }

    @Test
    fun `una lista vuota e' una lista vuota, non un server vecchio`() {
        assertEquals(emptyList<UsoGiornoServer>(), LetturaTempi.usoRecente(el("[]")))
    }

    @Test
    fun `un giorno senza fotografia resta senza dati, e senza liste`() {
        val giorni = LetturaTempi.usoRecente(
            el(
                """
                [ { "giorno": "2026-10-01", "totale_minuti": null, "app": [], "categorie": [] },
                  { "giorno": "2026-10-02", "app": [ { "chiave": "a", "minuti": 5 } ] },
                  { "giorno": "2026-10-03", "totale_minuti": "120", "app": [ { "chiave": "a", "minuti": 5 } ] },
                  { "giorno": "2026-10-04", "totale_minuti": 1441 },
                  { "giorno": "2026-10-05", "totale_minuti": -3 },
                  { "giorno": "2026-10-06", "totale_minuti": 12.5 },
                  { "giorno": "2026-10-07", "totale_minuti": true } ]
                """,
            ),
        )!!
        assertEquals(7, giorni.size)
        giorni.forEach {
            assertNull(it.giorno, it.totaleMinuti)
            assertTrue(it.app.isEmpty() && it.categorie.isEmpty())
        }
    }

    @Test
    fun `lo zero vero resta zero`() {
        val giorno = LetturaTempi.usoRecente(el("""[ { "giorno": "2026-10-04", "totale_minuti": 0 } ]"""))!!.single()
        assertEquals(0, giorno.totaleMinuti)
    }

    @Test
    fun `i pezzi scritti male cadono da soli, il resto resta`() {
        val giorni = LetturaTempi.usoRecente(
            el(
                """
                [ 7, "x", null, { "totale_minuti": 10 }, { "giorno": "4 ottobre", "totale_minuti": 10 },
                  { "giorno": "2026-10-04", "totale_minuti": 100,
                    "app": [ { "chiave": "buona", "minuti": 30 }, { "chiave": "", "minuti": 3 }, { "minuti": 3 },
                             { "chiave": "testo", "minuti": "9" }, { "chiave": "troppa", "minuti": 2000 }, 42,
                             { "chiave": "nome-vuoto", "nome": "  ", "minuti": 4 } ],
                    "categorie": [ { "chiave": "categoria:social", "minuti": 20, "limite": "molto" },
                                   { "chiave": "categoria:video", "minuti": -1 } ],
                    "sessioni_minuti": 5000 } ]
                """,
            ),
        )!!
        val giorno = giorni.single()
        assertEquals("2026-10-04", giorno.giorno)
        assertEquals(listOf("buona", "nome-vuoto"), giorno.app.map { it.chiave })
        assertNull(giorno.app.last().nome)
        // Un limite che non si legge non c'è; la categoria resta.
        assertEquals(listOf(UsoCategoriaServer("categoria:social", 20, null)), giorno.categorie)
        assertNull(giorno.sessioniMinuti)
    }

    @Test
    fun `i giorni in ordine, una volta sola`() {
        val giorni = LetturaTempi.usoRecente(
            el(
                """
                [ { "giorno": "2026-10-05", "totale_minuti": 1 },
                  { "giorno": "2026-10-03", "totale_minuti": 2 },
                  { "giorno": "2026-10-05", "totale_minuti": 3 } ]
                """,
            ),
        )!!
        assertEquals(listOf("2026-10-03", "2026-10-05"), giorni.map { it.giorno })
        assertEquals(1, giorni.last().totaleMinuti)
    }

    @Test
    fun `medie con il totale della v3_8`() {
        val medie = LetturaTempi.medie(
            el("""{ "settimana": { "minuti": 131, "giorni": 7, "totale": 917 }, "mese": { "minuti": 118, "giorni": 30, "totale": 3540 } }"""),
        )!!
        assertEquals(PeriodoServer(131, 7, 917), medie.settimana)
        assertEquals(PeriodoServer(118, 30, 3540), medie.mese)
    }

    @Test
    fun `medie di un server di prima, senza totale`() {
        val medie = LetturaTempi.medie(el("""{ "settimana": { "minuti": 131, "giorni": 6 }, "mese": null }"""))!!
        assertEquals(PeriodoServer(131, 6, null), medie.settimana)
        assertNull(medie.mese)
    }

    @Test
    fun `un periodo scritto male non c'e', mai uno zero finto`() {
        val medie = LetturaTempi.medie(
            el(
                """{ "settimana": { "minuti": 131, "giorni": 0, "totale": 0 },
                     "mese": { "minuti": "118", "giorni": 30, "totale": 3540 } }""",
            ),
        )!!
        assertNull(medie.settimana)
        assertNull(medie.mese)
        val totaleStrano = LetturaTempi.medie(el("""{ "settimana": { "minuti": 10, "giorni": 2, "totale": -5 } }"""))!!
        assertEquals(PeriodoServer(10, 2, null), totaleStrano.settimana)
    }

    @Test
    fun `il patto con i campi nuovi si legge, anche nei dispositivi`() {
        val patto = json.decodeFromString(
            Patto.serializer(),
            """
            { "regole": [],
              "uso_recente": [ { "giorno": "2026-10-05", "totale_minuti": 42 } ],
              "medie": { "settimana": { "minuti": 40, "giorni": 1, "totale": 42 }, "mese": null },
              "dispositivo": { "id": 2, "nome": "Telefono di Luca", "tipo": "telefono" },
              "dispositivi": [ { "id": 2, "nome": "Telefono di Luca", "tipo": "telefono",
                                 "uso_recente": [ { "giorno": "2026-10-05", "totale_minuti": 42 } ], "medie": null },
                               { "id": 3, "nome": "PC di Luca", "tipo": "computer",
                                 "uso_recente": [ { "giorno": "2026-10-05", "totale_minuti": 90 } ],
                                 "medie": { "settimana": { "minuti": 90, "giorni": 1, "totale": 90 } } } ] }
            """,
        )
        assertEquals(42, LetturaTempi.usoRecente(patto.usoRecenteGrezzo)!!.single().totaleMinuti)
        assertEquals(42, LetturaTempi.medie(patto.medieGrezze)!!.settimana!!.totale)
        assertEquals(90, LetturaTempi.usoRecente(patto.dispositivi[1].usoRecenteGrezzo)!!.single().totaleMinuti)
        assertNotNull(LetturaTempi.medie(patto.dispositivi[1].medieGrezze))
    }

    @Test
    fun `campi nuovi rotti non fanno cadere il patto`() {
        val patto = json.decodeFromString(
            Patto.serializer(),
            """{ "regole": [ { "id": 1, "tipo": "limite_tempo" } ], "uso_recente": "rotto", "medie": 7,
                 "dispositivi": [ { "id": 3, "uso_recente": { "x": 1 }, "medie": [] } ] }""",
        )
        assertEquals(1, patto.regole.size)
        assertNull(LetturaTempi.usoRecente(patto.usoRecenteGrezzo))
        assertNull(LetturaTempi.medie(patto.medieGrezze))
        assertNull(LetturaTempi.usoRecente(patto.dispositivi.single().usoRecenteGrezzo))
    }

    @Test
    fun `un patto di prima non ha i campi`() {
        val patto = json.decodeFromString(Patto.serializer(), """{ "regole": [] }""")
        assertNull(LetturaTempi.usoRecente(patto.usoRecenteGrezzo))
        assertNull(LetturaTempi.medie(patto.medieGrezze))
    }

    @Test
    fun `la copia locale li conserva`() {
        val corpo = """{ "regole": [], "uso_recente": [ { "giorno": "2026-10-05", "totale_minuti": 42 } ],
                         "medie": { "settimana": { "minuti": 42, "giorni": 1, "totale": 42 } } }"""
        val letto = json.decodeFromString(Patto.serializer(), corpo)
        val riletto = json.decodeFromString(Patto.serializer(), json.encodeToString(Patto.serializer(), letto))
        assertEquals(LetturaTempi.usoRecente(letto.usoRecenteGrezzo), LetturaTempi.usoRecente(riletto.usoRecenteGrezzo))
        assertEquals(LetturaTempi.medie(letto.medieGrezze), LetturaTempi.medie(riletto.medieGrezze))
    }
}
