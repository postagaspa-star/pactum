package eu.stgm.pactum.figlio.faccende

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.xml.parsers.DocumentBuilderFactory

/**
 * (0.14, contratto v3.7) Nei testi che il figlio legge non si dice più
 * "faccende" ma "lavori di casa" (al singolare "lavoro", maschile). Si legge
 * il vero strings.xml dell'app: i nomi nel codice restano, le parole no.
 */
class TestiLavoriDiCasaTest {

    private val file = listOf(
        File("src/main/res/values/strings.xml"),
        File("app/src/main/res/values/strings.xml"),
    ).first { it.exists() }

    private val radice: Element = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement

    /** Le stringhe semplici, per nome (gli apostrofi senza la barra di Android). */
    private val stringhe: Map<String, String> = elementi("string").associate { it.getAttribute("name") to testo(it) }

    private fun elementi(tag: String): List<Element> {
        val nodi = radice.getElementsByTagName(tag)
        return (0 until nodi.length).map { nodi.item(it) as Element }
    }

    private fun testo(e: Element): String = e.textContent.replace("\\'", "'")

    private fun plurale(nome: String, quantita: String): String {
        val p = elementi("plurals").first { it.getAttribute("name") == nome }
        val voci = p.getElementsByTagName("item")
        return (0 until voci.length).map { voci.item(it) as Element }.first { it.getAttribute("quantity") == quantita }.let(::testo)
    }

    @Test
    fun `nessun testo del figlio dice ancora faccende`() {
        val tutti = elementi("string").map { it.getAttribute("name") to testo(it) } +
            elementi("item").map { "item" to testo(it) }
        val rimasti = tutti.filter { (_, t) -> "faccend" in t.lowercase() }
        assertTrue("ancora \"faccende\": $rimasti", rimasti.isEmpty())
    }

    @Test
    fun `la scheda, la pagina e la barriera`() {
        assertEquals("Lavori di casa", stringhe["scheda_faccende"])
        assertEquals("Lavori di casa", stringhe["faccende_titolo"])
        assertEquals("Prima i lavori di casa", stringhe["barriera_faccende_titolo"])
        assertEquals("Prima i lavori di casa: il telefono è bloccato", stringhe["notifica_blocco_partito"])
        assertEquals("Nessun lavoro di casa da fare.", stringhe["faccende_vuoto"])
    }

    @Test
    fun `al singolare lavoro, maschile - fatto, bocciato, annullato`() {
        assertEquals("Fatto", stringhe["faccenda_stato_fatta"])
        assertEquals("Annullato", stringhe["faccenda_stato_annullata"])
        assertEquals("Annullato da %1\$s", stringhe["faccenda_annullata_da"])
        assertEquals("Bocciato una volta", plurale("faccenda_bocciature", "one"))
        assertEquals("Bocciato %1\$d volte", plurale("faccenda_bocciature", "other"))
        assertEquals("Fatti e annullati", stringhe["faccende_sezione_chiuse"])
        assertEquals("Questo lavoro non lo devi più fare.", stringhe["notifica_faccenda_annullata_testo"])
        assertEquals("Si sblocca da solo quando è arrivata la foto di ogni lavoro.", stringhe["faccende_bloccato_spiega"])
    }

    @Test
    fun `la notifica dei lavori nuovi con le frasi vere`() {
        val parole = ParoleFaccende(
            nuove = { nome, quante ->
                (if (quante == 1) plurale("notifica_faccende_nuove", "one") else plurale("notifica_faccende_nuove", "other")).format(nome, quante)
            },
            bloccoSubito = stringhe.getValue("notifica_faccende_blocco_subito"),
            bloccoAlle = stringhe.getValue("notifica_faccende_blocco_alle"),
            bloccoDomani = stringhe.getValue("notifica_faccende_blocco_domani"),
            bloccoGiorno = stringhe.getValue("notifica_faccende_blocco_giorno"),
            nuoveTesto = stringhe.getValue("notifica_faccende_nuove_testo"),
            bocciata = stringhe.getValue("notifica_faccenda_bocciata"),
            bocciataTesto = stringhe.getValue("notifica_faccenda_bocciata_testo"),
            annullata = stringhe.getValue("notifica_faccenda_annullata"),
            annullataTesto = stringhe.getValue("notifica_faccenda_annullata_testo"),
            genitoreSenzaNome = stringhe.getValue("faccende_genitore_senza_nome"),
        )
        val roma = ZoneId.of("Europe/Rome")
        val adesso = ZonedDateTime.of(2026, 10, 4, 15, 0, 0, 0, roma).toInstant().toEpochMilli()
        val tre = kotlinx.serialization.json.Json.parseToJsonElement(
            """{ "faccenda_ids": [5, 6, 7], "blocco_da": "2026-10-04T14:00:00+00:00", "genitore": { "id": 2, "nome": "Mamma" } }""",
        ) as kotlinx.serialization.json.JsonObject
        assertEquals("Mamma ti ha dato 3 lavori di casa · blocco dalle 16:00", TestoFaccende.avvisoNuove(tre, emptyList(), adesso, roma, parole)!!.first)
        val uno = kotlinx.serialization.json.Json.parseToJsonElement(
            """{ "faccenda_ids": [5], "genitore": { "id": 3, "nome": "Papà" } }""",
        ) as kotlinx.serialization.json.JsonObject
        val (titolo, testo) = TestoFaccende.avvisoNuove(uno, listOf("Porta fuori il cane"), adesso, roma, parole)!!
        assertEquals("Papà ti ha dato un lavoro di casa · blocco da subito", titolo)
        assertEquals("«Porta fuori il cane»\nQuando ne hai fatto uno, scatta la foto da Pactum.", testo)
    }
}
