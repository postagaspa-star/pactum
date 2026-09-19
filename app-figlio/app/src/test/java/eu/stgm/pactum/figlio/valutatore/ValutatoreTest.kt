package eu.stgm.pactum.figlio.valutatore

import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.TipiRegola
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Il valutatore locale: il giorno degli sforamenti (v2.4), il limite efficace,
 * dove sta una fascia rispetto ad adesso, l'uso per regola.
 */
class ValutatoreTest {

    private val roma = ZoneId.of("Europe/Rome")
    private val tuttiIGiorni = listOf("lun", "mar", "mer", "gio", "ven", "sab", "dom")

    private fun ms(testo: String): Long =
        LocalDateTime.parse(testo).atZone(roma).toInstant().toEpochMilli()

    private fun limite(id: Long, app: String, minuti: Int) = Regola(
        id = id,
        tipo = TipiRegola.LIMITE_TEMPO,
        parametri = buildJsonObject {
            put("app_o_categoria", app)
            put("minuti_al_giorno", minuti)
        },
    )

    private fun fascia(id: Long, dalle: String, alle: String, giorni: List<String> = tuttiIGiorni) = Regola(
        id = id,
        tipo = TipiRegola.FASCIA_ORARIA,
        parametri = buildJsonObject {
            put("dalle", dalle)
            put("alle", alle)
            putJsonArray("giorni") { giorni.forEach { add(it) } }
        },
    )

    // --- il giorno degli sforamenti (v2.4) ---

    @Test
    fun `lo sforamento di un limite porta il giorno del telefono`() {
        val adesso = ms("2026-09-19T22:10:00")
        val sforamenti = Valutatore.valuta(
            regole = listOf(limite(1, "com.zhiliaoapp.musically", 60)),
            bonusOggiPerRegola = emptyMap(),
            usoMinutiEtichetta = { 100L },
            usoMinutiIntervallo = { _, _ -> 0L },
            now = adesso,
            zona = roma,
        )
        val dettagli = Valutatore.dettagliSforamento(sforamenti.single(), "2026-09-19")
        assertEquals(JsonPrimitive("2026-09-19"), dettagli["giorno"])
        assertEquals(JsonPrimitive(40), dettagli["minuti_oltre"])
        assertEquals(JsonPrimitive(60), dettagli["limite_efficace"])
        assertEquals(JsonPrimitive(1L), dettagli["regola_id"])
    }

    @Test
    fun `la coda mattutina di una fascia notturna cade nel giorno in cui la fascia e' partita`() {
        // Fascia 23:00→07:00; all'una di notte del 20 si usa il telefono.
        val adesso = ms("2026-09-20T01:00:00")
        val sforamenti = Valutatore.valuta(
            regole = listOf(fascia(2, "23:00", "07:00")),
            bonusOggiPerRegola = emptyMap(),
            usoMinutiEtichetta = { 0L },
            usoMinutiIntervallo = { _, _ -> 30L },
            now = adesso,
            zona = roma,
        )
        val s = sforamenti.single()
        assertEquals("2026-09-19", Valutatore.giornoDelloSforamento(s, giornoTelefono = "2026-09-20"))
        assertEquals(
            JsonPrimitive("2026-09-19"),
            Valutatore.dettagliSforamento(s, "2026-09-20")["giorno"],
        )
    }

    @Test
    fun `il bonus di oggi allunga il limite efficace della sua regola soltanto`() {
        val tiktok = limite(1, "com.zhiliaoapp.musically", 60)
        assertEquals(75, Valutatore.limiteEfficace(tiktok, mapOf("1" to 15, "2" to 30)))
        assertEquals(60, Valutatore.limiteEfficace(tiktok, emptyMap()))
        assertNull(Valutatore.limiteEfficace(fascia(3, "23:00", "07:00"), mapOf("3" to 15)))
    }

    // --- dove sta una fascia rispetto ad adesso ---

    @Test
    fun `prima della fascia si contano i minuti che mancano`() {
        val momento = Valutatore.momentoFascia(fascia(2, "23:00", "07:00"), ms("2026-09-19T19:47:00"), roma)
        assertEquals(MomentoFascia.Prima(193, LocalTime.of(23, 0)), momento)
    }

    @Test
    fun `dentro una fascia notturna dopo mezzanotte si dice fino a quando`() {
        val momento = Valutatore.momentoFascia(fascia(2, "23:00", "07:00"), ms("2026-09-20T01:00:00"), roma)
        assertEquals(MomentoFascia.InCorso(360, LocalTime.of(7, 0)), momento)
    }

    @Test
    fun `una fascia di oggi gia' passata e' finita`() {
        val momento = Valutatore.momentoFascia(fascia(2, "14:00", "16:00"), ms("2026-09-19T17:00:00"), roma)
        assertEquals(MomentoFascia.Finita, momento)
    }

    @Test
    fun `una fascia che oggi non vale lo dice`() {
        // Il 19/09/2026 è sabato.
        val momento = Valutatore.momentoFascia(
            fascia(2, "14:00", "16:00", listOf("lun")),
            ms("2026-09-19T10:00:00"),
            roma,
        )
        assertEquals(MomentoFascia.NonOggi, momento)
    }

    // --- l'uso di oggi per regola ---

    @Test
    fun `l'uso si legge per pacchetto esatto e per categoria`() {
        val indice = IndiceUso(
            uso = listOf(
                "com.zhiliaoapp.musically" to 30 * 60_000L,
                "com.instagram.android" to 20 * 60_000L + 59_000L,
            ),
            categoriaDi = { "categoria:social" },
        )
        assertEquals(30L, indice.minuti("com.zhiliaoapp.musically"))
        assertEquals(20L, indice.minuti("COM.Instagram.android "))
        assertEquals(50L, indice.minuti("categoria:social"))
        assertEquals(0L, indice.minuti("categoria:giochi"))
    }
}
