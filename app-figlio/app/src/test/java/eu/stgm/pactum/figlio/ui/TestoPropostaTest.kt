package eu.stgm.pactum.figlio.ui

import eu.stgm.pactum.figlio.dati.DirezioniProposta
import eu.stgm.pactum.figlio.dati.Dispositivo
import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.TipiDispositivo
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
        oraSu = "Ora %1\$s: %2\$s",
        togliereSu = "Propone di togliere la regola %1\$s: %2\$s",
    )

    private val nomi = mapOf("com.zhiliaoapp.musically" to "TikTok")

    private fun durata(m: Long) = if (m < 60) "$m min" else if (m % 60 == 0L) "${m / 60} h" else "${m / 60} h ${m % 60} min"

    // Come l'app: una regola di questo telefono "TikTok: al massimo 1 h al giorno";
    // una di un altro dispositivo nella forma breve, senza i due punti.
    private val descrivi: (Regola, JsonObject) -> String = { regola, parametri ->
        when (regola.tipo) {
            TipiRegola.LIMITE_TEMPO -> {
                val app = (parametri["app_o_categoria"] as JsonPrimitive).content
                val minuti = (parametri["minuti_al_giorno"] as JsonPrimitive).content.toLong()
                val nome = ChiaviComputer.etichetta(app, null, "%1\$s (sito)") ?: nomi[app] ?: app
                if (regola.dispositivo?.tipo == TipiDispositivo.COMPUTER) {
                    "$nome al massimo ${durata(minuti)} al giorno"
                } else {
                    "$nome: al massimo ${durata(minuti)} al giorno"
                }
            }
            else -> regola.tipo
        }
    }

    private val sulComputer: (Regola) -> String? = {
        if (it.dispositivo?.tipo == TipiDispositivo.COMPUTER) "sul computer" else null
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

    // --- v3: la proposta su una regola del computer, vista dal telefono -------

    private fun sitoYoutube(minuti: Int) = buildJsonObject {
        put("app_o_categoria", "sito:youtube.com")
        put("minuti_al_giorno", minuti)
    }

    private val youtubeSulComputer = Regola(
        id = 12,
        tipo = TipiRegola.LIMITE_TEMPO,
        parametri = sitoYoutube(60),
        dispositivoId = 2,
        dispositivo = Dispositivo(id = 2, nome = "Computer", tipo = TipiDispositivo.COMPUTER),
    )

    private fun raccontoV3(confronto: String?, oggetto: OggettoProposta?) =
        TestoProposta.racconto(confronto, oggetto, parole, descrivi, sulComputer)

    @Test
    fun `una modifica su una regola del computer dice su quale dispositivo`() {
        val oggetto = TestoProposta.oggetto(
            12, DirezioniProposta.STRINGE, sitoYoutube(30), regole + youtubeSulComputer,
        )
        val racconto = raccontoV3("−30 min al giorno rispetto ad ora", oggetto)
        assertEquals("−30 min al giorno rispetto ad ora", racconto.titolo)
        assertEquals(
            listOf(
                "Ora sul computer: youtube.com (sito) al massimo 1 h al giorno",
                "Se accetti: youtube.com (sito) al massimo 30 min al giorno",
            ),
            racconto.righe,
        )
    }

    @Test
    fun `togliere una regola del computer dice il dispositivo nel titolo`() {
        val oggetto = TestoProposta.oggetto(12, DirezioniProposta.ELIMINA, marcatoreElimina, listOf(youtubeSulComputer))
        assertEquals(
            "Propone di togliere la regola sul computer: youtube.com (sito) al massimo 1 h al giorno",
            raccontoV3("propone di eliminare la regola", oggetto).titolo,
        )
    }

    @Test
    fun `una regola di questo telefono resta com'era anche con i dispositivi`() {
        val oggetto = TestoProposta.oggetto(7, DirezioniProposta.STRINGE, limite(45), regole)
        assertEquals(
            listOf(
                "Ora: TikTok: al massimo 1 h al giorno",
                "Se accetti: TikTok: al massimo 45 min al giorno",
            ),
            raccontoV3("−15 min al giorno rispetto ad ora", oggetto).righe,
        )
    }
}
