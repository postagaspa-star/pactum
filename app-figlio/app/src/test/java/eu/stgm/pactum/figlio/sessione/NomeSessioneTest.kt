package eu.stgm.pactum.figlio.sessione

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (0.11) Il nome di una Sessione controllato come lo controlla il server:
 * senza spazi ai bordi, in forma NFC, al massimo 40 caratteri, niente
 * caratteri che non si vedono, e "Studio" e "studio" sono lo stesso nome.
 */
class NomeSessioneTest {

    @Test
    fun `il nome si pulisce come sul server`() {
        assertEquals("Studio", NomeSessione.pulito("  Studio  "))
        // "é" scritta come "e" + accento diventa una lettera sola.
        assertEquals("Caffé", NomeSessione.pulito("Caffé"))
    }

    @Test
    fun `un nome vuoto non va`() {
        assertEquals(NomeSessione.Problema.VUOTO, NomeSessione.problema(""))
        assertEquals(NomeSessione.Problema.VUOTO, NomeSessione.problema("    "))
    }

    @Test
    fun `al massimo 40 caratteri, contati come li vede chi legge`() {
        assertNull(NomeSessione.problema("a".repeat(40)))
        assertEquals(NomeSessione.Problema.TROPPO_LUNGO, NomeSessione.problema("a".repeat(41)))
        // Un'emoji è un carattere, anche se dentro sono due.
        assertNull(NomeSessione.problema("📚".repeat(40)))
        assertEquals(NomeSessione.Problema.TROPPO_LUNGO, NomeSessione.problema("📚".repeat(41)))
        // 40 lettere accentate scritte in due pezzi: dopo la NFC sono 40.
        assertNull(NomeSessione.problema("é".repeat(40)))
        // Gli spazi ai bordi non contano.
        assertNull(NomeSessione.problema("  " + "a".repeat(40) + "  "))
    }

    @Test
    fun `caratteri che non si vedono - a capo, tab, spazi a larghezza zero`() {
        for (nome in listOf("Studio\nSera", "Stu\tdio", "Stu\u0000dio", "Stu​dio", "Stu dio", "Stu dio", "‎Studio")) {
            assertEquals(nome, NomeSessione.Problema.INVISIBILI, NomeSessione.problema(nome))
        }
        assertTrue(NomeSessione.invisibile(0x200B))
        assertTrue(NomeSessione.invisibile('\n'.code))
        assertFalse(NomeSessione.invisibile(' '.code))
        assertFalse(NomeSessione.invisibile('a'.code))
        assertFalse(NomeSessione.invisibile(0x1F4DA))
        // Spazi normali in mezzo, accenti, emoji: vanno bene.
        assertNull(NomeSessione.problema("Studio di sera 📚"))
        assertNull(NomeSessione.problema("Perché no"))
    }

    @Test
    fun `lo stesso nome senza guardare maiuscole, spazi ai bordi e come sono scritti gli accenti`() {
        assertTrue(NomeSessione.stesso("Studio", " studio "))
        assertTrue(NomeSessione.stesso("CAFFÉ", "caffé"))
        assertFalse(NomeSessione.stesso("Studio", "Lavoro"))
        assertFalse(NomeSessione.stesso("Studio", "Studio 2"))
    }
}
