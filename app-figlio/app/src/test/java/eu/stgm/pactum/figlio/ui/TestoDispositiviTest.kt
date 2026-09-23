package eu.stgm.pactum.figlio.ui

import eu.stgm.pactum.figlio.dati.ContestoDispositivi
import eu.stgm.pactum.figlio.dati.Dispositivo
import eu.stgm.pactum.figlio.dati.GiornoStriscia
import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.TipiDispositivo
import eu.stgm.pactum.figlio.dati.TipiRegola
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3, più dispositivi per figlio: come si leggono le chiavi del computer, su
 * quale dispositivo sta una regola, le righe "Computer: 5 su 7" e "Collegato
 * come". Le parole sono quelle di strings.xml.
 */
class TestoDispositiviTest {

    private val formatoSito = "%1\$s (sito)"

    // --- Le chiavi del computer ---------------------------------------------

    @Test
    fun `un programma senza nome dal server e' il nome del file senza exe`() {
        assertEquals("Minecraft", ChiaviComputer.nomeProgramma("exe:minecraft.exe"))
        assertEquals("Discord", ChiaviComputer.nomeProgramma("exe:discord.exe", nomeServer = null))
        assertEquals("Minecraft.windows", ChiaviComputer.nomeProgramma("exe:minecraft.windows.exe"))
    }

    @Test
    fun `il nome leggibile del server vince sul nome del file`() {
        assertEquals("Google Chrome", ChiaviComputer.nomeProgramma("exe:chrome.exe", "Google Chrome"))
    }

    @Test
    fun `un nome del server vuoto o uguale alla chiave non e' un nome`() {
        assertEquals("Chrome", ChiaviComputer.nomeProgramma("exe:chrome.exe", ""))
        assertEquals("Chrome", ChiaviComputer.nomeProgramma("exe:chrome.exe", "  "))
        // Il server, quando non sa il nome, ripiega sulla chiave stessa.
        assertEquals("Chrome", ChiaviComputer.nomeProgramma("exe:chrome.exe", "exe:chrome.exe"))
    }

    @Test
    fun `una chiave di programma vuota resta com'e'`() {
        assertEquals("exe:", ChiaviComputer.nomeProgramma("exe:"))
        assertEquals("exe:.exe", ChiaviComputer.nomeProgramma("exe:.exe"))
    }

    @Test
    fun `un sito e' il dominio con accanto sito`() {
        assertEquals("youtube.com (sito)", ChiaviComputer.etichetta("sito:youtube.com", null, formatoSito))
        // Il nome del server non cambia un sito: il dominio si legge già.
        assertEquals("youtube.com (sito)", ChiaviComputer.etichetta("sito:youtube.com", "YouTube", formatoSito))
        assertEquals("sito:", ChiaviComputer.etichetta("sito:", null, formatoSito))
    }

    @Test
    fun `pacchetti e categorie non sono chiavi del computer`() {
        assertNull(ChiaviComputer.etichetta("com.zhiliaoapp.musically", null, formatoSito))
        assertNull(ChiaviComputer.etichetta("categoria:social", null, formatoSito))
        assertEquals("Minecraft", ChiaviComputer.etichetta("exe:minecraft.exe", null, formatoSito))
    }

    // --- Su quale dispositivo sta una regola --------------------------------

    private val parole = ParoleDispositivo(
        sulComputer = "sul computer",
        sulComputerNome = "sul computer “%1\$s”",
        sulTelefonoNome = "sul telefono “%1\$s”",
        sullAltroTelefono = "sull'altro telefono",
        suNome = "su “%1\$s”",
        suAltro = "su un altro dispositivo",
    )

    private val telefono = Dispositivo(id = 1, nome = "Telefono", tipo = TipiDispositivo.TELEFONO)
    private val computer = Dispositivo(id = 2, nome = "Computer", tipo = TipiDispositivo.COMPUTER)
    private val portatile = Dispositivo(id = 3, nome = "Portatile", tipo = TipiDispositivo.COMPUTER)
    private val vecchio = Dispositivo(id = 4, nome = "Vecchio", tipo = TipiDispositivo.TELEFONO)

    private fun regolaSu(d: Dispositivo?, conOggetto: Boolean = true) = Regola(
        id = 10,
        tipo = TipiRegola.LIMITE_TEMPO,
        dispositivoId = d?.id,
        dispositivo = d?.takeIf { conOggetto }?.copy(striscia = emptyList()),
    )

    private val contesto = ContestoDispositivi(questo = 1, dispositivi = listOf(telefono, computer))

    @Test
    fun `una regola di questo telefono o della vita reale non dice il dispositivo`() {
        assertNull(TestoDispositivi.etichetta(regolaSu(telefono), contesto, parole))
        assertNull(TestoDispositivi.etichetta(regolaSu(null), contesto, parole))
    }

    @Test
    fun `una regola del computer dice sul computer`() {
        assertEquals("sul computer", TestoDispositivi.etichetta(regolaSu(computer), contesto, parole))
        // Anche se la regola porta solo l'id: tipo e nome dall'elenco dei dispositivi.
        assertEquals(
            "sul computer",
            TestoDispositivi.etichetta(regolaSu(computer, conOggetto = false), contesto, parole),
        )
    }

    @Test
    fun `con due computer si dice quale`() {
        val due = contesto.copy(dispositivi = listOf(telefono, computer, portatile))
        assertEquals("sul computer “Portatile”", TestoDispositivi.etichetta(regolaSu(portatile), due, parole))
        // Un computer revocato non conta: ne resta uno solo.
        val unoRevocato = contesto.copy(dispositivi = listOf(telefono, computer, portatile.copy(revocato = true)))
        assertEquals("sul computer", TestoDispositivi.etichetta(regolaSu(computer), unoRevocato, parole))
    }

    @Test
    fun `un altro telefono porta sempre il nome, perche' anche questo e' un telefono`() {
        val conVecchio = contesto.copy(dispositivi = listOf(telefono, computer, vecchio))
        assertEquals("sul telefono “Vecchio”", TestoDispositivi.etichetta(regolaSu(vecchio), conVecchio, parole))
        val senzaNome = regolaSu(vecchio.copy(nome = ""))
        assertEquals(
            "sull'altro telefono",
            TestoDispositivi.etichetta(senzaNome, contesto.copy(dispositivi = listOf(telefono)), parole),
        )
    }

    @Test
    fun `un tipo che non si conosce usa il nome, o dice solo che e' un altro dispositivo`() {
        val tablet = Dispositivo(id = 5, nome = "Tablet", tipo = "tablet")
        assertEquals("su “Tablet”", TestoDispositivi.etichetta(regolaSu(tablet), contesto, parole))
        assertEquals(
            "su un altro dispositivo",
            TestoDispositivi.etichetta(regolaSu(Dispositivo(id = 6), conOggetto = true), contesto, parole),
        )
    }

    @Test
    fun `se il server non dice chi e' questo telefono, un computer e' comunque un altro`() {
        val ignoto = ContestoDispositivi(questo = null, dispositivi = emptyList())
        assertTrue(TestoDispositivi.diUnAltro(regolaSu(computer), ignoto))
        assertFalse(TestoDispositivi.diUnAltro(regolaSu(telefono), ignoto))
        assertEquals("sul computer", TestoDispositivi.etichetta(regolaSu(computer), ignoto, parole))
    }

    @Test
    fun `in testa alla frase l'iniziale e' maiuscola`() {
        assertEquals("Sul computer", TestoDispositivi.maiuscola("sul computer"))
        assertEquals("Sull'altro telefono", TestoDispositivi.maiuscola("sull'altro telefono"))
    }

    // --- Le righe degli altri dispositivi -------------------------------------

    private fun striscia(vararg stati: String) =
        stati.mapIndexed { i, stato -> GiornoStriscia("2026-09-${17 + i}", stato) }

    @Test
    fun `con un telefono solo non ci sono righe`() {
        assertEquals(emptyList<RigaDispositivo>(), RigheDispositivi.calcola(listOf(telefono), questo = 1))
        assertEquals(emptyList<RigaDispositivo>(), RigheDispositivi.calcola(emptyList(), questo = 1))
        assertEquals(emptyList<RigaDispositivo>(), RigheDispositivi.calcola(listOf(telefono), questo = null))
    }

    @Test
    fun `telefono e computer, questo telefono per primo, contati dalla loro striscia`() {
        val righe = RigheDispositivi.calcola(
            dispositivi = listOf(
                computer.copy(striscia = striscia("verde", "rosso", "verde", "grigio", "verde", "verde", "verde", "verde")),
                telefono.copy(striscia = striscia("verde", "verde", "verde", "verde", "verde", "verde", "verde", "grigio")),
            ),
            questo = 1,
        )
        assertEquals(
            listOf(
                RigaDispositivo(id = 1, nome = "Telefono", questo = true, mantenuti = 7, conDati = 7),
                // Il grigio esce dal conto, come nella frase grande: 6 su 7.
                RigaDispositivo(id = 2, nome = "Computer", questo = false, mantenuti = 6, conDati = 7),
            ),
            righe,
        )
    }

    @Test
    fun `per questo telefono vale la striscia_dispositivo, se c'e'`() {
        val righe = RigheDispositivi.calcola(
            dispositivi = listOf(telefono.copy(striscia = striscia("rosso")), computer),
            questo = 1,
            strisciaQuesto = striscia("verde", "verde"),
        )
        assertEquals(RigaDispositivo(1, "Telefono", true, mantenuti = 2, conDati = 2), righe.first())
    }

    @Test
    fun `un dispositivo senza dati ha zero giorni con dati`() {
        val righe = RigheDispositivi.calcola(
            dispositivi = listOf(telefono, computer.copy(striscia = striscia("grigio", "grigio"))),
            questo = 1,
        )
        assertEquals(RigaDispositivo(2, "Computer", false, mantenuti = 0, conDati = 0), righe.last())
    }

    @Test
    fun `un dispositivo scollegato senza piu' giorni con dati non ha riga`() {
        assertEquals(
            emptyList<RigaDispositivo>(),
            RigheDispositivi.calcola(listOf(telefono, computer.copy(revocato = true)), questo = 1),
        )
        val righe = RigheDispositivi.calcola(
            listOf(telefono, computer, portatile.copy(revocato = true, striscia = striscia("grigio"))),
            questo = 1,
        )
        assertEquals(listOf(1L, 2L), righe.map { it.id })
    }

    @Test
    fun `un dispositivo scollegato resta finche' i suoi giorni contano nella striscia`() {
        val righe = RigheDispositivi.calcola(
            listOf(telefono, vecchio.copy(revocato = true, striscia = striscia("rosso", "verde", "grigio"))),
            questo = 1,
        )
        assertEquals(
            RigaDispositivo(4, "Vecchio", questo = false, mantenuti = 1, conDati = 2, revocato = true),
            righe.last(),
        )
    }

    @Test
    fun `se questo telefono manca dall'elenco, restano le righe degli altri`() {
        val righe = RigheDispositivi.calcola(listOf(computer), questo = 1)
        assertEquals(listOf(RigaDispositivo(2, "Computer", false, 0, 0)), righe)
    }

    @Test
    fun `se il server non dice chi e' questo telefono, si vedono tutti senza segnarne uno`() {
        val righe = RigheDispositivi.calcola(listOf(telefono, computer), questo = null)
        assertEquals(listOf(1L, 2L), righe.map { it.id })
        assertTrue(righe.none { it.questo })
    }

    // --- Collegato come -------------------------------------------------------

    private val formato = "%1\$s di %2\$s"

    @Test
    fun `collegato come dice il dispositivo e il figlio`() {
        assertEquals("Telefono di Andrea", collegatoCome("Telefono", "Andrea", formato))
    }

    @Test
    fun `se il nome del dispositivo dice gia' il figlio non lo si ripete`() {
        assertEquals("Telefono di Andrea", collegatoCome("Telefono di Andrea", "Andrea", formato))
        assertEquals("Computer di andrea", collegatoCome("Computer di andrea", "Andrea", formato))
    }

    @Test
    fun `con un nome solo si dice quello, senza niente si dice niente`() {
        assertEquals("Telefono", collegatoCome("Telefono", null, formato))
        assertEquals("Telefono", collegatoCome("Telefono", " ", formato))
        assertEquals("Andrea", collegatoCome("", "Andrea", formato))
        assertNull(collegatoCome(null, null, formato))
        assertNull(collegatoCome(" ", "", formato))
    }
}
