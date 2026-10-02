package eu.stgm.pactum.figlio.sessione

import eu.stgm.pactum.design.TemaSessione
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * (0.12) Il nome di una sessione con l'emoji del suo tema, con la stessa
 * regola (e gli stessi casi) dell'app del genitore: in testa a una riga
 * "📚 Studio", in una frase "📚 «Studio»", mai due volte la stessa emoji.
 */
class NomiSessioneTest {

    @Test
    fun `accanto al nome la prima emoji del suo tema, come nell'app del genitore`() {
        assertEquals("📚", NomiSessione.emoji("Studio"))
        assertEquals("📚", NomiSessione.emoji("Compiti di matematica"))
        assertEquals("⚽", NomiSessione.emoji("Allenamento calcio"))
        assertEquals("🎵", NomiSessione.emoji("MUSICA"))
        assertEquals("📖", NomiSessione.emoji("Lettura"))
        assertEquals("💻", NomiSessione.emoji("Coding"))
        assertEquals("✨", NomiSessione.emoji("Lavoro"))
        assertEquals("✨", NomiSessione.emoji(null))
        // È la prima emoji del tema: quella grande della pagina animata.
        assertEquals(TemaSessione.daNome("Sport").emojiPrincipale, NomiSessione.emoji("Sport"))
    }

    @Test
    fun `in testa a una riga l'emoji e il nome, in una frase il nome esatto fra virgolette con l'emoji fuori`() {
        assertEquals("📚 Studio", NomiSessione.inTesta(" Studio "))
        assertEquals("📚 «Studio»", NomiSessione.traVirgolette(" Studio "))
        assertEquals("✨ «Lavoro»", NomiSessione.traVirgolette("Lavoro"))
    }

    @Test
    fun `un nome che comincia gia' con quell'emoji non la ripete`() {
        assertNull(NomiSessione.emoji("📚 Ripasso storia"))
        assertEquals("📚 Ripasso storia", NomiSessione.inTesta("📚 Ripasso storia"))
        assertEquals("«📚 Ripasso storia»", NomiSessione.traVirgolette(" 📚 Ripasso storia"))
        // Una tastiera che aggiunge il selettore di variante: è la stessa emoji.
        assertEquals("⚽️ Calcio", NomiSessione.inTesta("⚽️ Calcio"))
        // Un'altra emoji in testa al nome non è quella del tema: l'emoji del tema c'è lo stesso.
        assertEquals("📚 🔥 Studio", NomiSessione.inTesta("🔥 Studio"))
    }

    @Test
    fun `senza emoji per la seconda volta nella stessa schermata, o sotto l'emoji grande della pagina`() {
        assertEquals("«Studio»", NomiSessione.traVirgolette(" Studio ", conEmoji = false))
    }

    @Test
    fun `i formati arrivano da strings_xml, come nell'app del genitore`() {
        assertEquals("📚 «Studio»", NomiSessione.traVirgolette("Studio", virgolette = "«%1\$s»", formatoEmoji = "%1\$s %2\$s"))
        assertEquals("📚 \"Studio\"", NomiSessione.traVirgolette("Studio", virgolette = "\"%1\$s\"", formatoEmoji = "%1\$s %2\$s"))
        assertEquals("📚 Studio", NomiSessione.inTesta("Studio", conEmoji = "%1\$s %2\$s"))
    }
}
