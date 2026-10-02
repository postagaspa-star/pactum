package eu.stgm.pactum.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (0.12) Il tema di una Sessione scelto dal nome (core-design, condiviso con
 * l'app del genitore): maiuscole e accenti non contano, decide la prima
 * parola riconosciuta, tutto il resto sono stelline.
 */
class TemiSessioneTest {

    @Test
    fun `i nomi di tutti i giorni`() {
        assertEquals(TemaSessione.STUDIO, TemaSessione.daNome("Studio"))
        assertEquals(TemaSessione.LETTURA, TemaSessione.daNome("Lettura"))
        assertEquals(TemaSessione.SPORT, TemaSessione.daNome("Allenamento"))
        assertEquals(TemaSessione.MUSICA, TemaSessione.daNome("Chitarra"))
        assertEquals(TemaSessione.ARTE, TemaSessione.daNome("Disegno"))
        assertEquals(TemaSessione.RELAX, TemaSessione.daNome("Relax"))
        assertEquals(TemaSessione.PROGRAMMAZIONE, TemaSessione.daNome("Coding"))
    }

    @Test
    fun `tutte le parole della lista`() {
        val attese = mapOf(
            TemaSessione.STUDIO to listOf(
                "studio", "studiare", "compiti", "scuola", "ripasso", "verifica", "esame",
                "matematica", "latino", "inglese", "storia",
            ),
            TemaSessione.LETTURA to listOf("lettura", "leggere", "libro"),
            TemaSessione.SPORT to listOf(
                "sport", "allenamento", "palestra", "calcio", "basket", "pallavolo", "corsa", "nuoto", "bici",
            ),
            TemaSessione.MUSICA to listOf("musica", "chitarra", "piano", "pianoforte", "canto", "batteria", "basso"),
            TemaSessione.ARTE to listOf("arte", "disegno", "disegnare", "pittura"),
            TemaSessione.RELAX to listOf("relax", "meditazione", "riposo"),
            TemaSessione.PROGRAMMAZIONE to listOf("programmazione", "coding", "progetto"),
        )
        for ((tema, parole) in attese) {
            for (parola in parole) assertEquals(parola, tema, TemaSessione.daNome(parola))
        }
    }

    @Test
    fun `maiuscole e accenti non contano`() {
        assertEquals(TemaSessione.STUDIO, TemaSessione.daNome("STUDIO"))
        assertEquals(TemaSessione.STUDIO, TemaSessione.daNome("Matemàtica"))
        assertEquals(TemaSessione.STUDIO, TemaSessione.daNome("MATEMATICA!"))
        assertEquals(TemaSessione.RELAX, TemaSessione.daNome("Meditazióne"))
        // "é" scritta come "e" + accento.
        assertEquals(TemaSessione.STUDIO, TemaSessione.daNome("Esamé"))
    }

    @Test
    fun `basta una parola del nome - la prima che si riconosce decide`() {
        assertEquals(TemaSessione.STUDIO, TemaSessione.daNome("Studio mate"))
        assertEquals(TemaSessione.STUDIO, TemaSessione.daNome("Compiti di matematica"))
        assertEquals(TemaSessione.SPORT, TemaSessione.daNome("Allenamento calcio"))
        assertEquals(TemaSessione.LETTURA, TemaSessione.daNome("Lettura per la scuola"))
        assertEquals(TemaSessione.STUDIO, TemaSessione.daNome("Scuola: lettura"))
        assertEquals(TemaSessione.MUSICA, TemaSessione.daNome("Prove di chitarra"))
        assertEquals(TemaSessione.STUDIO, TemaSessione.daNome("📚 Ripasso storia"))
    }

    @Test
    fun `anche al plurale`() {
        assertEquals(TemaSessione.STUDIO, TemaSessione.daNome("Verifiche"))
        assertEquals(TemaSessione.STUDIO, TemaSessione.daNome("Esami"))
        assertEquals(TemaSessione.LETTURA, TemaSessione.daNome("Libri"))
        assertEquals(TemaSessione.SPORT, TemaSessione.daNome("Allenamenti"))
        assertEquals(TemaSessione.PROGRAMMAZIONE, TemaSessione.daNome("Progetti"))
    }

    @Test
    fun `le altre materie di scuola sono studio`() {
        for (materia in listOf("Fisica", "Chimica", "Scienze", "Geografia", "Italiano", "Filosofia", "Diritto", "Economia")) {
            assertEquals(materia, TemaSessione.STUDIO, TemaSessione.daNome(materia))
        }
    }

    @Test
    fun `tutto il resto sono stelline`() {
        assertEquals(TemaSessione.STELLINE, TemaSessione.daNome("Lavoro"))
        assertEquals(TemaSessione.STELLINE, TemaSessione.daNome("Pomeriggio"))
        assertEquals(TemaSessione.STELLINE, TemaSessione.daNome(""))
        assertEquals(TemaSessione.STELLINE, TemaSessione.daNome("   "))
        assertEquals(TemaSessione.STELLINE, TemaSessione.daNome("✨✨"))
        // Una parola dentro un'altra non conta: "artefatto" non è arte, "pianificare" non è piano.
        assertEquals(TemaSessione.STELLINE, TemaSessione.daNome("Artefatto"))
        assertEquals(TemaSessione.STELLINE, TemaSessione.daNome("Pianificare"))
    }

    @Test
    fun `le parole del nome - minuscole, senza accenti, senza punteggiatura`() {
        assertEquals(listOf("studio", "di", "citta"), TemaSessione.paroleDi("  Studio di CITTÀ! "))
        assertEquals(listOf("3d", "art"), TemaSessione.paroleDi("3D-art"))
        assertTrue(TemaSessione.paroleDi("…").isEmpty())
    }

    @Test
    fun `ogni tema ha le sue emoji e i suoi fondi`() {
        assertEquals(
            listOf("📚", "✏️", "📐", "🧠", "🎓", "📝"),
            TemaSessione.STUDIO.emoji,
        )
        assertEquals(listOf("✨", "⭐", "🌟", "💫"), TemaSessione.STELLINE.emoji)
        for (tema in TemaSessione.values()) {
            assertTrue(tema.name, tema.emoji.size >= 3)
            assertEquals(tema.emoji.first(), tema.emojiPrincipale)
            assertNotEquals(tema.name, tema.sfondoChiaro, tema.sfondoScuro)
            // Il fondo chiaro è chiaro e lo scuro è scuro: la scheda e gli adesivi si leggono su tutti e due.
            assertTrue(tema.name, luminanza(tema.sfondoChiaro) > 0.6f)
            assertTrue(tema.name, luminanza(tema.sfondoScuro) < 0.1f)
        }
        // Temi diversi, fondi diversi.
        assertEquals(TemaSessione.values().size, TemaSessione.values().map { it.sfondoChiaro }.toSet().size)
    }

    /** La luminanza relativa (WCAG) di un colore. */
    private fun luminanza(colore: androidx.compose.ui.graphics.Color): Float {
        fun lineare(c: Float) = if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        return 0.2126f * lineare(colore.red) + 0.7152f * lineare(colore.green) + 0.0722f * lineare(colore.blue)
    }
}
