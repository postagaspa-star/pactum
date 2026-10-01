package eu.stgm.pactum.figlio.sessione

import eu.stgm.pactum.figlio.dati.Patto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * (0.11) Le forme del contratto v3.5 lette dal telefono. Il punto: un campo
 * delle sessioni scritto male non fa mai cadere la lettura del patto, e una
 * sessione di cui non si sa quando vale non diventa mai una barriera.
 */
class ModelliSessioneTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonClient = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private val pattoV35 = """
        {
          "regole": [],
          "sessioni": [
            { "id": 3, "dispositivo_id": 1, "dispositivo": { "id": 1, "nome": "Telefono", "tipo": "telefono" },
              "nome": "Studio", "app": ["eu.spaggiari.classevivafamiglia", "gruppo:apk"],
              "nomi": { "eu.spaggiari.classevivafamiglia": "ClasseViva", "gruppo:apk": "App installate da APK" },
              "stato": "approvata", "modifica_in_attesa": null, "motivazione": null,
              "creata_ts": "2026-10-01T08:00:00+00:00", "approvata_ts": "2026-10-01T09:00:00+00:00" },
            { "id": 4, "nome": "Lavoro", "app": ["com.microsoft.teams"], "stato": "rifiutata",
              "motivazione": "Teams di sera no",
              "modifica_in_attesa": null }
          ],
          "sessione_in_corso": { "id": 12, "sessione_id": 3, "dispositivo_id": 1, "nome": "Studio",
            "app": ["eu.spaggiari.classevivafamiglia", "gruppo:apk"], "nomi": {},
            "inizio_ts": "2026-10-01T14:00:00+00:00", "durata_minuti": 120,
            "fine_prevista_ts": "2026-10-01T16:00:00+00:00", "fine_ts": null, "chiusura": null, "in_corso": true },
          "sessioni_svolte": [
            { "id": 12, "sessione_id": 3, "nome": "Studio", "app": ["eu.spaggiari.classevivafamiglia"],
              "inizio_ts": "2026-10-01T14:00:00+00:00", "durata_minuti": 120,
              "fine_prevista_ts": "2026-10-01T16:00:00+00:00", "fine_ts": null, "chiusura": null, "in_corso": true },
            { "id": 11, "sessione_id": 3, "nome": "Studio", "app": ["eu.spaggiari.classevivafamiglia"],
              "inizio_ts": "2026-09-30T15:00:00Z", "durata_minuti": 60,
              "fine_prevista_ts": "2026-09-30T16:00:00Z", "fine_ts": "2026-09-30T15:20:00Z",
              "chiusura": "terminata", "in_corso": false }
          ]
        }
    """.trimIndent()

    @Test
    fun `il patto v3_5 porta le sessioni, quella in corso e quelle svolte`() {
        val patto = json.decodeFromString(Patto.serializer(), pattoV35)
        assertTrue(patto.conosceSessioni)
        assertEquals(listOf(3L, 4L), patto.sessioni.map { it.id })
        val studio = patto.sessioni.first()
        assertTrue(studio.approvata)
        assertEquals(listOf("eu.spaggiari.classevivafamiglia", "gruppo:apk"), studio.app)
        assertEquals("ClasseViva", studio.nomi["eu.spaggiari.classevivafamiglia"])
        assertEquals(1L, studio.dispositivoId)
        val lavoro = patto.sessioni.last()
        assertTrue(lavoro.rifiutata)
        assertEquals("Teams di sera no", lavoro.motivazione)

        val inCorso = patto.sessioneInCorso
        assertEquals(12L, inCorso?.id)
        assertEquals(ms("2026-10-01T14:00:00Z"), inCorso?.inizio)
        assertEquals(ms("2026-10-01T16:00:00Z"), inCorso?.finePrevista)
        assertNull(inCorso?.fine)
        assertEquals(listOf(12L, 11L), patto.sessioniSvolte.map { it.id })
        assertEquals(ChiusureSessione.TERMINATA, patto.sessioniSvolte.last().chiusura)
        assertEquals(ms("2026-09-30T15:20:00Z"), patto.sessioniSvolte.last().fine)
    }

    @Test
    fun `un server vecchio non conosce le sessioni, e niente si rompe`() {
        val patto = json.decodeFromString(Patto.serializer(), """{ "regole": [] }""")
        assertFalse(patto.conosceSessioni)
        assertTrue(patto.sessioni.isEmpty())
        assertNull(patto.sessioneInCorso)
        assertTrue(patto.sessioniSvolte.isEmpty())
    }

    @Test
    fun `un elenco vuoto vuol dire server nuovo senza sessioni`() {
        val patto = json.decodeFromString(
            Patto.serializer(),
            """{ "regole": [], "sessioni": [], "sessione_in_corso": null, "sessioni_svolte": [] }""",
        )
        assertTrue(patto.conosceSessioni)
        assertNull(patto.sessioneInCorso)
    }

    @Test
    fun `campi delle sessioni scritti male non fanno cadere il patto`() {
        val corpo = """
            { "regole": [ { "id": 1, "tipo": "limite_tempo" } ],
              "sessioni": [ { "nome": "senza id" }, "boh", { "id": "7", "nome": "Musica", "app": ["a", null, 5, "a"],
                              "nomi": { "a": null, "b": "B" }, "stato": "in_attesa" } ],
              "sessione_in_corso": "rotto",
              "sessioni_svolte": { "non": "una lista" } }
        """.trimIndent()
        val patto = json.decodeFromString(Patto.serializer(), corpo)
        assertEquals(1, patto.regole.size)
        val musica = patto.sessioni.single()
        assertEquals(7L, musica.id)
        // Niente null, niente doppioni; i numeri diventano testo.
        assertEquals(listOf("a", "5"), musica.app)
        assertEquals(mapOf("b" to "B"), musica.nomi)
        assertNull(patto.sessioneInCorso)
        assertTrue(patto.sessioniSvolte.isEmpty())
    }

    @Test
    fun `la copia locale del patto conserva le sessioni`() {
        val letto = json.decodeFromString(Patto.serializer(), pattoV35)
        // Come PattoLocale: si riscrive e si rilegge con lo stesso modello.
        val copia = json.decodeFromString(Patto.serializer(), json.encodeToString(Patto.serializer(), letto))
        assertEquals(letto.sessioni, copia.sessioni)
        assertEquals(letto.sessioneInCorso, copia.sessioneInCorso)
        assertEquals(letto.sessioniSvolte, copia.sessioniSvolte)
    }

    @Test
    fun `la sessione svolta diventa la copia del telefono`() {
        val patto = json.decodeFromString(Patto.serializer(), pattoV35)
        val locale = patto.sessioneInCorso?.inLocale()
        assertEquals(12L, locale?.id)
        assertEquals(3L, locale?.sessioneId)
        assertEquals(ms("2026-10-01T14:00:00Z"), locale?.inizio)
        assertEquals(ms("2026-10-01T16:00:00Z"), locale?.finePrevista)
        assertNull(locale?.fineServer)
        val chiusa = patto.sessioniSvolte.last().inLocale()
        assertEquals(ms("2026-09-30T15:20:00Z"), chiusa?.fine)
    }

    @Test
    fun `senza inizio o con una fine prima dell'inizio non vale niente`() {
        val senzaInizio = LetturaSessioni.svoltaDaCorpo("""{ "id": 5, "app": ["a"], "fine_prevista_ts": "2026-10-01T16:00:00Z" }""")
        assertNull(senzaInizio?.inLocale())
        val alRovescio = LetturaSessioni.svoltaDaCorpo(
            """{ "id": 5, "app": ["a"], "inizio_ts": "2026-10-01T16:00:00Z", "fine_prevista_ts": "2026-10-01T15:00:00Z" }""",
        )
        assertNull(alRovescio?.inLocale())
        assertNull(LetturaSessioni.svoltaDaCorpo("""{ "id": 0, "inizio_ts": "2026-10-01T16:00:00Z", "durata_minuti": 5 }""")?.inLocale())
    }

    @Test
    fun `senza fine prevista vale la durata, e mai piu' di 24 ore`() {
        val daDurata = LetturaSessioni.svoltaDaCorpo("""{ "id": 5, "app": ["a"], "inizio_ts": "2026-10-01T14:00:00Z", "durata_minuti": 90 }""")
        assertEquals(ms("2026-10-01T15:30:00Z"), daDurata?.inLocale()?.finePrevista)
        val troppo = LetturaSessioni.svoltaDaCorpo(
            """{ "id": 5, "app": ["a"], "inizio_ts": "2026-10-01T14:00:00Z", "fine_prevista_ts": "2026-10-04T14:00:00Z" }""",
        )
        assertEquals(ms("2026-10-01T14:00:00Z") + SessioneSvolta.DURATA_MASSIMA_MS, troppo?.inLocale()?.finePrevista)
    }

    @Test
    fun `gli istanti del server si leggono con offset, con Z, senza offset e come numero`() {
        val atteso = ms("2026-10-01T14:00:00Z")
        assertEquals(atteso, LetturaSessioni.istante(JsonPrimitive("2026-10-01T14:00:00+00:00")))
        assertEquals(atteso, LetturaSessioni.istante(JsonPrimitive("2026-10-01T16:00:00+02:00")))
        assertEquals(atteso, LetturaSessioni.istante(JsonPrimitive("2026-10-01T14:00:00Z")))
        assertEquals(atteso, LetturaSessioni.istante(JsonPrimitive("2026-10-01T14:00:00")))
        assertEquals(atteso, LetturaSessioni.istante(JsonPrimitive(atteso)))
        assertEquals(atteso, LetturaSessioni.istante(JsonPrimitive(atteso / 1000)))
        assertNull(LetturaSessioni.istante(JsonPrimitive("ieri")))
        assertNull(LetturaSessioni.istante(null))
    }

    @Test
    fun `l'elenco di GET api sessioni`() {
        val elenco = LetturaSessioni.elenco("""{ "sessioni": [ { "id": 3, "nome": "Studio", "app": ["a"], "stato": "in_attesa" } ] }""")
        assertEquals(listOf(3L), elenco?.map { it.id })
        assertTrue(elenco?.single()?.inAttesa == true)
        assertNull(LetturaSessioni.elenco("<html>Not Found</html>"))
        assertNull(LetturaSessioni.elenco(null))
    }

    @Test
    fun `il cambio in attesa si legge con le sue app`() {
        val sessione = LetturaSessioni.definitaDaCorpo(
            """{ "id": 3, "nome": "Studio", "app": ["a"], "stato": "approvata",
                 "modifica_in_attesa": { "nome": "Studio bis", "app": ["a", "b"], "nomi": { "b": "Bi" } } }""",
        )
        assertEquals("Studio bis", sessione?.modificaInAttesa?.nome)
        assertEquals(listOf("a", "b"), sessione?.modificaInAttesa?.app)
        assertEquals(mapOf("b" to "Bi"), sessione?.modificaInAttesa?.nomi)
    }

    @Test
    fun `i corpi delle richieste sono quelli del contratto`() {
        assertEquals(
            """{"nome":"Studio","app":["a","gruppo:apk"]}""",
            jsonClient.encodeToString(SessioneIn.serializer(), SessioneIn("Studio", listOf("a", "gruppo:apk"))),
        )
        assertEquals(
            """{"nome":"Studio","app":["a"],"nomi":{"a":"A"}}""",
            jsonClient.encodeToString(SessioneIn.serializer(), SessioneIn("Studio", listOf("a"), mapOf("a" to "A"))),
        )
        assertEquals(
            """{"app":["b"]}""",
            jsonClient.encodeToString(SessioneModificaIn.serializer(), SessioneModificaIn(app = listOf("b"))),
        )
        assertEquals("""{"durata_minuti":90}""", jsonClient.encodeToString(AvvioSessioneIn.serializer(), AvvioSessioneIn(90)))
        // La chiusura dice sempre quale sessione svolta chiude.
        assertEquals(
            """{"ts_device":1790000000000,"svolta_id":12}""",
            jsonClient.encodeToString(TerminaSessioneIn.serializer(), TerminaSessioneIn(1_790_000_000_000, svoltaId = 12)),
        )
    }

    @Test
    fun `la versione e il cambio in attesa completo, con quando e' stato chiesto`() {
        val sessione = LetturaSessioni.definitaDaCorpo(
            """{ "id": 3, "nome": "Studio", "app": ["a"], "stato": "approvata", "versione": 4,
                 "modifica_in_attesa": { "nome": "Studio", "app": ["a", "b"], "nomi": {}, "richiesta_ts": "2026-10-01T10:00:00+00:00" } }""",
        )
        assertEquals(4L, sessione?.versione)
        assertEquals(ms("2026-10-01T10:00:00Z"), sessione?.modificaInAttesa?.richiestaTs)
        // Un server che non manda la versione: si legge lo stesso.
        assertNull(LetturaSessioni.definitaDaCorpo("""{ "id": 3, "nome": "Studio", "app": ["a"], "stato": "approvata" }""")?.versione)
    }
}
