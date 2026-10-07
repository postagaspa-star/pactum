package eu.stgm.pactum.figlio.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * (0.18, contratto v4.0) I testi nuovi della 0.18 (strings_018.xml): mai
 * "faccende" a schermo, "Sessione Studio" nei titoli, le frasi del contratto.
 */
class TestiStudioTest {

    private val file = listOf(
        File("src/main/res/values/strings_018.xml"),
        File("app/src/main/res/values/strings_018.xml"),
    ).first { it.exists() }

    private val radice: Element = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement

    private fun elementi(tag: String): List<Element> {
        val nodi = radice.getElementsByTagName(tag)
        return (0 until nodi.length).map { nodi.item(it) as Element }
    }

    private val stringhe: Map<String, String> =
        elementi("string").associate { it.getAttribute("name") to it.textContent.replace("\\'", "'") }

    @Test
    fun `nessun testo nuovo dice faccende`() {
        val tutti = stringhe.values + elementi("item").map { it.textContent }
        assertTrue(tutti.filter { "faccend" in it.lowercase() }.toString(), tutti.none { "faccend" in it.lowercase() })
    }

    @Test
    fun `i titoli dicono Sessione Studio, il discorso lo Studio`() {
        assertEquals("Sessione Studio", stringhe["studio_titolo"])
        assertEquals("Sessione Studio", stringhe["tipo_studio"])
        assertEquals("Chiudi lo Studio", stringhe["studio_chiudi"])
        assertEquals("Sei in Studio", stringhe["studio_barriera_titolo"])
        assertEquals("Tra 5 minuti parte lo Studio", stringhe["studio_notifica_preavviso"])
        assertEquals("Cosa hai fatto?", stringhe["studio_chiudi_cosa"])
    }

    @Test
    fun `le frasi della parte A`() {
        assertEquals("Si sblocca quando un genitore ha approvato la foto di ogni lavoro.", stringhe["faccende_bloccato_spiega_approvazione"])
        assertEquals("Aspettano l'approvazione", stringhe["faccende_sezione_approvazione"])
        assertEquals(
            "Foto mandata alle 16:10 · aspetta che un genitore la approvi",
            stringhe.getValue("faccenda_aspetta_approvazione_alle").format("16:10"),
        )
    }

    @Test
    fun `la notifica fissa con le parole vere`() {
        val p = ParoleStudio(
            dalle = stringhe.getValue("studio_dalle"),
            minutiSu = stringhe.getValue("studio_minuti_su"),
            chiudeDopo = stringhe.getValue("studio_chiude_dopo"),
            chiudibile = stringhe.getValue("studio_chiudibile"),
        )
        val roma = java.time.ZoneId.of("Europe/Rome")
        val inizio = java.time.ZonedDateTime.of(2026, 10, 8, 15, 0, 0, 0, roma).toInstant().toEpochMilli()
        val s = StudioAttivo(
            RifStudio(id = 1), OriginiStudio.AUTOMATICA, "2026-10-08", inizio, inizio, inizio + 3_600_000, 60,
            emptyList(), emptyMap(), inizio + 9 * 3_600_000, dalServer = true,
        )
        assertEquals("Studio dalle 15:00 · 42 min su 60 · si chiude dopo le 16:00", TestoStudio.stato(s, 42, false, roma, p))
    }
}
