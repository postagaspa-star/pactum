package eu.stgm.pactum.figlio.faccende

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * (0.13) Le parole delle notifiche dei lavori di casa (0.14), col nome vero
 * del genitore: "Mamma ti ha dato 3 lavori di casa · blocco dalle 16:00".
 */
class TestoFaccendeTest {

    private val roma = ZoneId.of("Europe/Rome")

    /** Venerdì 2 ottobre 2026, 15:00 a Roma. */
    private val adesso = ZonedDateTime.of(2026, 10, 2, 15, 0, 0, 0, roma).toInstant().toEpochMilli()

    private fun ora(giorno: Int, ore: Int, minuti: Int = 0) =
        ZonedDateTime.of(2026, 10, giorno, ore, minuti, 0, 0, roma).toInstant().toEpochMilli()

    private val parole = ParoleFaccende(
        nuove = { nome, quante -> if (quante == 1) "$nome ti ha dato un lavoro di casa" else "$nome ti ha dato $quante lavori di casa" },
        bloccoSubito = "%1\$s · blocco da subito",
        bloccoAlle = "%1\$s · blocco dalle %2\$s",
        bloccoDomani = "%1\$s · blocco domani dalle %2\$s",
        bloccoGiorno = "%1\$s · blocco %2\$s dalle %3\$s",
        nuoveTesto = "Quando ne hai fatto uno, scatta la foto da Pactum.",
        bocciata = "%1\$s ha bocciato «%2\$s»",
        bocciataTesto = "Rifai il lavoro e scatta un'altra foto.",
        annullata = "%1\$s ha annullato «%2\$s»",
        annullataTesto = "Questo lavoro non lo devi più fare.",
        genitoreSenzaNome = "Il genitore",
    )

    private fun payload(testo: String): JsonObject = Json.parseToJsonElement(testo) as JsonObject

    @Test
    fun `quando blocca, visto da adesso`() {
        assertEquals(QuandoBlocca.Subito, TestoFaccende.quandoBlocca(null, adesso, roma))
        assertEquals(QuandoBlocca.Subito, TestoFaccende.quandoBlocca(adesso - 1000, adesso, roma))
        assertEquals(QuandoBlocca.Subito, TestoFaccende.quandoBlocca(adesso + 30_000, adesso, roma))
        assertEquals(QuandoBlocca.Oggi("16:00"), TestoFaccende.quandoBlocca(ora(2, 16), adesso, roma))
        assertEquals(QuandoBlocca.Domani("09:30"), TestoFaccende.quandoBlocca(ora(3, 9, 30), adesso, roma))
        assertEquals(QuandoBlocca.Giorno("lunedì", "16:00"), TestoFaccende.quandoBlocca(ora(5, 16), adesso, roma))
        assertEquals(QuandoBlocca.Data("9/10", "16:00"), TestoFaccende.quandoBlocca(ora(9, 16), adesso, roma))
    }

    @Test
    fun `Mamma ti ha dato 3 lavori di casa - blocco dalle 16`() {
        val p = payload("""{ "faccenda_ids": [5, 6, 7], "blocco_da": "2026-10-02T14:00:00+00:00", "genitore": { "id": 2, "nome": "Mamma" } }""")
        val (titolo, testo) = TestoFaccende.avvisoNuove(p, listOf("Svuota la lavastoviglie", "Porta fuori il cane"), adesso, roma, parole)!!
        assertEquals("Mamma ti ha dato 3 lavori di casa · blocco dalle 16:00", titolo)
        assertEquals("«Svuota la lavastoviglie», «Porta fuori il cane»\nQuando ne hai fatto uno, scatta la foto da Pactum.", testo)
    }

    @Test
    fun `un lavoro di casa, subito, da Papà`() {
        val p = payload("""{ "faccenda_ids": [5], "blocco_da": "2026-10-02T13:00:00+00:00", "genitore": { "id": 3, "nome": "Papà" } }""")
        val (titolo, testo) = TestoFaccende.avvisoNuove(p, emptyList(), adesso, roma, parole)!!
        assertEquals("Papà ti ha dato un lavoro di casa · blocco da subito", titolo)
        assertEquals("Quando ne hai fatto uno, scatta la foto da Pactum.", testo)
    }

    @Test
    fun `senza il nome del genitore, e domani`() {
        val p = payload("""{ "faccenda_ids": [5, 6], "blocco_da": "2026-10-03T07:00:00Z" }""")
        assertEquals(
            "Il genitore ti ha dato 2 lavori di casa · blocco domani dalle 09:00",
            TestoFaccende.avvisoNuove(p, emptyList(), adesso, roma, parole)!!.first,
        )
    }

    @Test
    fun `un payload senza i lavori vale il messaggio del server`() {
        assertNull(TestoFaccende.avvisoNuove(payload("""{ "genitore": { "nome": "Mamma" } }"""), emptyList(), adesso, roma, parole))
        assertNull(TestoFaccende.avvisoNuove(payload("""{ "faccenda_ids": [] }"""), emptyList(), adesso, roma, parole))
    }

    @Test
    fun `bocciata, con e senza nota`() {
        val con = TestoFaccende.avvisoBocciata(
            payload("""{ "faccenda_id": 5, "titolo": "Svuota la lavastoviglie", "nota": "anche le pentole", "genitore": { "nome": "Mamma" } }"""),
            parole,
        )!!
        assertEquals("Mamma ha bocciato «Svuota la lavastoviglie»", con.first)
        assertEquals("«anche le pentole»\nRifai il lavoro e scatta un'altra foto.", con.second)
        val senza = TestoFaccende.avvisoBocciata(payload("""{ "faccenda_id": 5, "titolo": "Rifai il letto", "nota": null }"""), parole)!!
        assertEquals("Il genitore ha bocciato «Rifai il letto»", senza.first)
        assertEquals("Rifai il lavoro e scatta un'altra foto.", senza.second)
        assertNull(TestoFaccende.avvisoBocciata(payload("""{ "faccenda_id": 5 }"""), parole))
    }

    @Test
    fun `annullata`() {
        val (titolo, testo) = TestoFaccende.avvisoAnnullata(
            payload("""{ "faccenda_id": 5, "titolo": "Rifai il letto", "genitore": { "id": 3, "nome": "Papà" } }"""),
            parole,
        )!!
        assertEquals("Papà ha annullato «Rifai il letto»", titolo)
        assertEquals("Questo lavoro non lo devi più fare.", testo)
    }

    @Test
    fun `gli id dei lavori nuovi`() {
        assertEquals(listOf(5L, 6L), TestoFaccende.idNuove(payload("""{ "faccenda_ids": [5, 6, "x"] }""")))
        assertEquals(emptyList<Long>(), TestoFaccende.idNuove(payload("""{ }""")))
    }
}
