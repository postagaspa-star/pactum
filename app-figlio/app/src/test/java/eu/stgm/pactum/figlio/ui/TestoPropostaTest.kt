package eu.stgm.pactum.figlio.ui

import eu.stgm.pactum.figlio.dati.DirezioniProposta
import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.TipiRegola
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La proposta del genitore deve dire su QUALE regola è: il ragazzo deve sapere
 * cosa accetta. Le parole sono le stesse di strings.xml, e la descrizione
 * finta delle regole scrive come la vera ("TikTok: al massimo 1 h al giorno").
 */
class TestoPropostaTest {

    private val parole = ParoleProposta(
        senzaConfronto = "Proposta di modifica",
        ora = "Ora: %1\$s",
        seAccetti = "Se accetti: %1\$s",
        togliere = "Propone di togliere la regola: %1\$s",
    )

    private val nomi = mapOf("com.zhiliaoapp.musically" to "TikTok")

    private fun durata(m: Long) = if (m < 60) "$m min" else if (m % 60 == 0L) "${m / 60} h" else "${m / 60} h ${m % 60} min"

    private val descrivi: (String, JsonObject) -> String = { tipo, parametri ->
        when (tipo) {
            TipiRegola.LIMITE_TEMPO -> {
                val app = (parametri["app_o_categoria"] as JsonPrimitive).content
                val minuti = (parametri["minuti_al_giorno"] as JsonPrimitive).content.toLong()
                "${nomi[app] ?: app}: al massimo ${durata(minuti)} al giorno"
            }
            else -> tipo
        }
    }

    private fun limite(minuti: Int) = buildJsonObject {
        put("app_o_categoria", "com.zhiliaoapp.musically")
        put("minuti_al_giorno", minuti)
    }

    private val tiktok = Regola(id = 7, tipo = TipiRegola.LIMITE_TEMPO, parametri = limite(60))
    private val fascia = Regola(id = 8, tipo = TipiRegola.FASCIA_ORARIA)
    private val regole = listOf(fascia, tiktok)

    private val marcatoreElimina = buildJsonObject { put("azione", "elimina") }

    private fun racconto(confronto: String?, oggetto: OggettoProposta?) =
        TestoProposta.racconto(confronto, oggetto, parole, descrivi)

    @Test
    fun `una modifica dice la regola di ora e come diventa se accetti`() {
        val oggetto = TestoProposta.oggetto(7, DirezioniProposta.STRINGE, limite(45), regole)
        val racconto = racconto("−15 min al giorno rispetto ad ora", oggetto)
        assertEquals("−15 min al giorno rispetto ad ora", racconto.titolo)
        assertEquals(
            listOf(
                "Ora: TikTok: al massimo 1 h al giorno",
                "Se accetti: TikTok: al massimo 45 min al giorno",
            ),
            racconto.righe,
        )
    }

    @Test
    fun `l'eliminazione dice quale regola toglie, senza ripetere la frase del server`() {
        val oggetto = TestoProposta.oggetto(7, DirezioniProposta.ELIMINA, marcatoreElimina, regole)
        assertTrue(oggetto is OggettoProposta.Eliminazione)
        val racconto = racconto("propone di eliminare la regola", oggetto)
        assertEquals("Propone di togliere la regola: TikTok: al massimo 1 h al giorno", racconto.titolo)
        assertEquals(emptyList<String>(), racconto.righe)
    }

    @Test
    fun `l'eliminazione si riconosce anche dal solo marcatore`() {
        val oggetto = TestoProposta.oggetto(7, null, marcatoreElimina, regole)
        assertTrue(oggetto is OggettoProposta.Eliminazione)
    }

    @Test
    fun `senza parametri proposti o con gli stessi di ora resta la sola regola di ora`() {
        for (proposti in listOf(null, limite(60), JsonObject(emptyMap()))) {
            val oggetto = TestoProposta.oggetto(7, DirezioniProposta.STRINGE, proposti, regole)
            assertEquals(
                listOf("Ora: TikTok: al massimo 1 h al giorno"),
                racconto("−15 min al giorno rispetto ad ora", oggetto).righe,
            )
        }
    }

    @Test
    fun `regola non trovata nel patto resta il solo confronto, com'era`() {
        val oggetto = TestoProposta.oggetto(99, DirezioniProposta.STRINGE, limite(45), regole)
        assertNull(oggetto)
        val racconto = racconto("−15 min al giorno rispetto ad ora", oggetto)
        assertEquals("−15 min al giorno rispetto ad ora", racconto.titolo)
        assertEquals(emptyList<String>(), racconto.righe)
    }

    @Test
    fun `senza confronto del server si dice che e' una proposta di modifica`() {
        val oggetto = TestoProposta.oggetto(7, null, limite(45), regole)
        assertEquals("Proposta di modifica", racconto(null, oggetto).titolo)
        assertEquals("Proposta di modifica", racconto("  ", oggetto).titolo)
    }

    @Test
    fun `nella notifica una riga per pezzo`() {
        val oggetto = TestoProposta.oggetto(7, DirezioniProposta.ALLENTA, limite(90), regole)
        assertEquals(
            "+30 min al giorno rispetto ad ora\n" +
                "Ora: TikTok: al massimo 1 h al giorno\n" +
                "Se accetti: TikTok: al massimo 1 h 30 min al giorno",
            racconto("+30 min al giorno rispetto ad ora", oggetto).testo,
        )
    }
}
