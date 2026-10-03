package eu.stgm.pactum.figlio.faccende

import eu.stgm.pactum.figlio.dati.Patto
import eu.stgm.pactum.figlio.sessione.EsitiSessioni
import eu.stgm.pactum.figlio.sessione.EsitoAvvio
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

/**
 * (0.13) Le forme del contratto v3.6 lette con pazienza: un campo scritto male
 * non fa cadere niente, e una risposta che non si legge non sblocca mai.
 */
class LetturaFaccendeTest {

    private fun ms(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    private val bloccoAttivo = """
        { "attivo": true, "dal": "2026-10-03T14:00:00+00:00", "prossimo": null,
          "da_fare": [ { "id": 5, "titolo": "Svuota la lavastoviglie", "nota": "anche le pentole",
                         "blocco_da": "2026-10-03T14:00:00+00:00", "creata_da": { "id": 2, "nome": "Mamma" },
                         "bocciature": 1,
                         "ultima_bocciatura": { "ts": "2026-10-03T13:00:00+00:00", "nota": "le pentole no", "da": { "id": 3, "nome": "Papà" } } } ] }
    """.trimIndent()

    @Test
    fun `il blocco attivo con le faccende da fare`() {
        val b = LetturaFaccende.bloccoDaCorpo(bloccoAttivo)!!
        assertTrue(b.attivo)
        assertEquals(ms("2026-10-03T14:00:00+00:00"), b.dal)
        assertNull(b.prossimo)
        val f = b.daFare.single()
        assertEquals(5L, f.id)
        assertEquals("Svuota la lavastoviglie", f.titolo)
        assertEquals("anche le pentole", f.nota)
        assertEquals("Mamma", f.genitore)
        assertEquals(1, f.bocciature)
        assertEquals(Bocciatura(ms("2026-10-03T13:00:00+00:00"), "le pentole no", "Papà"), f.ultimaBocciatura)
    }

    @Test
    fun `il blocco programmato`() {
        val b = LetturaFaccende.bloccoDaCorpo("""{ "attivo": false, "dal": null, "prossimo": "2026-10-03T16:00:00Z", "da_fare": [] }""")!!
        assertFalse(b.attivo)
        assertEquals(ms("2026-10-03T16:00:00Z"), b.prossimo)
    }

    @Test
    fun `una risposta senza attivo leggibile non è una risposta`() {
        assertNull(LetturaFaccende.bloccoDaCorpo("""{ "dal": null }"""))
        assertNull(LetturaFaccende.bloccoDaCorpo("""{ "attivo": "false" }"""))
        assertNull(LetturaFaccende.bloccoDaCorpo("""{ "attivo": null }"""))
        assertNull(LetturaFaccende.bloccoDaCorpo("<html>Not Found</html>"))
        assertNull(LetturaFaccende.bloccoDaCorpo(""))
        assertNull(LetturaFaccende.bloccoDaCorpo(null))
    }

    @Test
    fun `una faccenda scritta male resta fuori, le altre no`() {
        val b = LetturaFaccende.bloccoDaCorpo(
            """{ "attivo": true, "da_fare": [ { "titolo": "senza id" }, { "id": 7, "titolo": "Porta fuori il cane", "bocciature": "x" }, 3 ] }""",
        )!!
        assertEquals(listOf(7L), b.daFare.map { it.id })
        assertEquals(0, b.daFare.single().bocciature)
        assertNull(b.daFare.single().genitore)
    }

    @Test
    fun `l'elenco intero delle faccende`() {
        val corpo = """
            { "faccende": [
              { "id": 5, "figlio_id": 1, "titolo": "Svuota la lavastoviglie", "nota": null, "stato": "fatta",
                "blocco_da": "2026-10-03T14:00:00+00:00", "creata_ts": "2026-10-03T12:00:00+00:00",
                "creata_da": { "id": 2, "nome": "Mamma" }, "foto_ts": "2026-10-03T14:20:00+00:00", "foto": true,
                "bocciature": 0, "ultima_bocciatura": null, "chiusa_ts": "2026-10-03T14:20:00+00:00", "annullata_da": null },
              { "id": 6, "titolo": "Rifai il letto", "stato": "annullata", "annullata_da": { "id": 3, "nome": "Papà" } }
            ] }
        """.trimIndent()
        val elenco = LetturaFaccende.elenco(corpo)!!
        assertEquals(2, elenco.size)
        assertTrue(elenco[0].fatta)
        assertTrue(elenco[0].foto)
        assertEquals(ms("2026-10-03T14:20:00+00:00"), elenco[0].fotoIl)
        assertTrue(elenco[1].annullata)
        assertEquals("Papà", elenco[1].annullataDa)
        assertNull(LetturaFaccende.elenco("""{ "altro": [] }"""))
    }

    @Test
    fun `il patto porta blocco e faccende, un patto vecchio no`() {
        val json = Json { ignoreUnknownKeys = true }
        val nuovo = json.decodeFromString(Patto.serializer(), """{ "regole": [], "faccende": [], "blocco": $bloccoAttivo }""")
        assertNotNull(nuovo.blocco)
        assertEquals(emptyList<FaccendaLocale>(), nuovo.faccende)
        val vecchio = json.decodeFromString(Patto.serializer(), """{ "regole": [] }""")
        assertNull(vecchio.blocco)
        assertNull(vecchio.faccende)
        // Un blocco scritto male non fa cadere il patto.
        val storto = json.decodeFromString(Patto.serializer(), """{ "regole": [], "blocco": "boh" }""")
        assertNull(storto.blocco)
    }

    // --- Gli esiti della foto -----------------------------------------------

    @Test
    fun `la foto arrivata, anche ripetuta`() {
        assertEquals(EsitoFoto.ARRIVATA, EsitiFaccende.foto(true, 200, """{ "id": 5, "stato": "fatta" }"""))
    }

    @Test
    fun `non_da_fare e faccenda non trovata - non serve più`() {
        assertEquals(EsitoFoto.NON_SERVE, EsitiFaccende.foto(false, 409, """{ "detail": { "errore": "non_da_fare" } }"""))
        assertEquals(EsitoFoto.NON_SERVE, EsitiFaccende.foto(false, 409, """{ "errore": "non_da_fare" }"""))
        assertEquals(EsitoFoto.NON_SERVE, EsitiFaccende.foto(false, 404, """{ "detail": "faccenda non trovata" }"""))
    }

    @Test
    fun `bocciata dopo lo scatto (409 bocciata_nel_frattempo) - la foto non vale più`() {
        assertEquals(
            EsitoFoto.BOCCIATA_NEL_FRATTEMPO,
            EsitiFaccende.foto(false, 409, """{ "detail": { "errore": "bocciata_nel_frattempo" } }"""),
        )
    }

    @Test
    fun `troppo grande o non valida - va scattata di nuovo`() {
        assertEquals(EsitoFoto.RIFIUTATA, EsitiFaccende.foto(false, 413, null))
        assertEquals(EsitoFoto.RIFIUTATA, EsitiFaccende.foto(false, 413, """{ "detail": { "errore": "foto_troppo_grande" } }"""))
        assertEquals(EsitoFoto.RIFIUTATA, EsitiFaccende.foto(false, 422, """{ "detail": { "errore": "foto_non_valida" } }"""))
    }

    @Test
    fun `server vecchio, rete, collegamento, errori - si tiene e si riprova`() {
        assertEquals(EsitoFoto.SERVER_VECCHIO, EsitiFaccende.foto(false, 404, """{ "detail": "Not Found" }"""))
        assertEquals(EsitoFoto.SERVER_VECCHIO, EsitiFaccende.foto(false, 404, "<html></html>"))
        assertEquals(EsitoFoto.SERVER_VECCHIO, EsitiFaccende.foto(false, 405, null))
        assertEquals(EsitoFoto.SCOLLEGATO, EsitiFaccende.foto(false, 401, null))
        assertEquals(EsitoFoto.SCOLLEGATO, EsitiFaccende.foto(false, 409, """{ "detail": { "errore": "dispositivo_revocato" } }"""))
        assertEquals(EsitoFoto.SENZA_RETE, EsitiFaccende.foto(false, 0, null))
        assertEquals(EsitoFoto.ERRORE, EsitiFaccende.foto(false, 502, null))
    }

    @Test
    fun `GET delle faccende - 404 e 405 vogliono dire server da aggiornare`() {
        assertTrue(EsitiFaccende.serverVecchio(404))
        assertTrue(EsitiFaccende.serverVecchio(405))
        assertFalse(EsitiFaccende.serverVecchio(500))
        assertFalse(EsitiFaccende.serverVecchio(0))
    }

    @Test
    fun `la sessione non parte col blocco delle faccende - 409 blocco_faccende`() {
        assertEquals(
            EsitoAvvio.BloccoFaccende,
            EsitiSessioni.avvio(false, 409, """{ "detail": { "errore": "blocco_faccende" } }"""),
        )
        assertEquals(EsitoAvvio.BloccoFaccende, EsitiSessioni.avvio(false, 409, """{ "errore": "blocco_faccende" }"""))
    }
}
