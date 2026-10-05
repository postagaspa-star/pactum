package eu.stgm.pactum.figlio.ui

import eu.stgm.pactum.figlio.dati.Dispositivo
import eu.stgm.pactum.figlio.dati.MedieServer
import eu.stgm.pactum.figlio.dati.Patto
import eu.stgm.pactum.figlio.dati.PeriodoServer
import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.TipiRegola
import eu.stgm.pactum.figlio.dati.UsoAppServer
import eu.stgm.pactum.figlio.dati.UsoGiornoServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * (0.16, contratto v3.8) Il tempo nell'app del figlio: i giorni passati e i
 * totali dal server, oggi dal telefono (e nei totali al posto di quello del
 * server); senza i campi nuovi solo oggi, come prima.
 */
class TempiFiglioTest {

    private val oggi = LocalDate.of(2026, 10, 5)
    private val json = Json { ignoreUnknownKeys = true }

    private fun giornoServer(indietro: Long, minuti: Int?, app: List<UsoAppServer> = emptyList()) =
        UsoGiornoServer(oggi.minusDays(indietro).toString(), minuti, app)

    /** Gli 8 giorni del server, dal più vecchio a oggi; null = senza dati. */
    private fun ottoGiorni(vararg minuti: Int?) =
        minuti.mapIndexed { i, m -> giornoServer((minuti.size - 1 - i).toLong(), m) }

    private val locale = GiornoTempo(oggi.toString(), 50, listOf(AppDelGiorno("com.a", "A", 50)))

    // --- I giorni -----------------------------------------------------------

    @Test
    fun `otto giorni dal server, oggi dal telefono`() {
        val giorni = TempiFiglio.giorni(ottoGiorni(10, 20, null, 40, 50, 60, 70, 30), oggi, locale)
        assertEquals(8, giorni.size)
        assertEquals(oggi.minusDays(7).toString(), giorni.first().giorno)
        assertEquals(listOf(10, 20, null, 40, 50, 60, 70, 50), giorni.map { it.totaleMinuti })
        assertEquals(locale, giorni.last())
    }

    @Test
    fun `senza la lettura del telefono, oggi e' quello del server`() {
        val giorni = TempiFiglio.giorni(ottoGiorni(10, 20, 30, 40, 50, 60, 70, 30), oggi, null)
        assertEquals(30, giorni.last().totaleMinuti)
    }

    @Test
    fun `un giorno che il server non ha e' senza dati, non zero`() {
        // Una copia del patto di due giorni fa: i giorni dopo non ci sono.
        val vecchia = (9 downTo 2).map { giornoServer(it.toLong(), 100) }
        val giorni = TempiFiglio.giorni(vecchia, oggi, locale)
        assertEquals(8, giorni.size)
        assertEquals(listOf(100, 100, 100, 100, 100, 100, null, 50), giorni.map { it.totaleMinuti })
    }

    @Test
    fun `server vecchio senza uso_recente, solo oggi`() {
        assertEquals(listOf(locale), TempiFiglio.giorni(null, oggi, locale))
        assertEquals(listOf(GiornoTempo(oggi.toString(), null)), TempiFiglio.giorni(null, oggi, null))
    }

    @Test
    fun `le app del server col nome da leggere, dalla piu' usata, senza gli zeri`() {
        val server = listOf(
            giornoServer(1, 30, listOf(UsoAppServer("com.b", null, 5), UsoAppServer("com.a", "Alfa", 25), UsoAppServer("com.c", "C", 0))),
            giornoServer(0, 1),
        )
        val ieri = TempiFiglio.giorni(server, oggi, null) { chiave, nome -> nome ?: "?$chiave" }[6]
        assertEquals(listOf(AppDelGiorno("com.a", "Alfa", 25), AppDelGiorno("com.b", "?com.b", 5)), ieri.app)
    }

    // --- I totali con oggi dal telefono ---------------------------------------

    @Test
    fun `nel totale oggi del server lascia il posto a oggi del telefono`() {
        val uso = ottoGiorni(10, 20, 30, 40, 50, 60, 70, 30)
        val medie = TempiFiglio.medie(
            MedieServer(PeriodoServer(minuti = 46, giorni = 7, totale = 320), PeriodoServer(minuti = 40, giorni = 30, totale = 1200)),
            uso,
            oggi,
            oggiLocale = 50,
        )!!
        assertEquals(PeriodoTempo(minuti = 46, giorni = 7, totale = 340), medie.settimana)
        assertEquals(PeriodoTempo(minuti = 40, giorni = 30, totale = 1220), medie.mese)
    }

    @Test
    fun `oggi che il server non aveva diventa un giorno con dati in piu'`() {
        val uso = ottoGiorni(10, 20, 30, 40, 50, 60, 70, null)
        val medie = TempiFiglio.medie(MedieServer(PeriodoServer(47, 6, 280), null), uso, oggi, oggiLocale = 50)!!
        assertEquals(PeriodoTempo(minuti = 47, giorni = 7, totale = 330), medie.settimana)
        // Il mese senza dati, ma oggi c'è: un giorno solo, quello letto qui.
        assertEquals(PeriodoTempo(minuti = 50, giorni = 1, totale = 50), medie.mese)
    }

    @Test
    fun `la media resta quella del server`() {
        val uso = ottoGiorni(10, 20, 30, 40, 50, 60, 70, 30)
        val medie = TempiFiglio.medie(MedieServer(PeriodoServer(46, 7, 320), null), uso, oggi, oggiLocale = 500)!!
        assertEquals(46, medie.settimana!!.minuti)
        assertEquals(790, medie.settimana!!.totale)
    }

    @Test
    fun `i giorni con dati non superano la finestra`() {
        assertEquals(7, TempiFiglio.conOggi(PeriodoServer(10, 7, 70), null, 5, 7)!!.giorni)
    }

    @Test
    fun `senza la lettura del telefono i totali sono quelli del server`() {
        val uso = ottoGiorni(10, 20, 30, 40, 50, 60, 70, 30)
        val medie = TempiFiglio.medie(MedieServer(PeriodoServer(46, 7, 320), null), uso, oggi, oggiLocale = null)!!
        assertEquals(PeriodoTempo(46, 7, 320), medie.settimana)
        assertNull(medie.mese)
    }

    @Test
    fun `server senza totale, niente da correggere`() {
        assertEquals(PeriodoTempo(46, 7, null), TempiFiglio.conOggi(PeriodoServer(46, 7, null), 30, 50, 7))
    }

    @Test
    fun `senza medie o senza giorni non si sa nulla`() {
        val uso = ottoGiorni(10, 20, 30, 40, 50, 60, 70, 30)
        assertNull(TempiFiglio.medie(null, uso, oggi, 50))
        assertNull(TempiFiglio.medie(MedieServer(PeriodoServer(46, 7, 320), null), null, oggi, 50))
        assertNull(TempiFiglio.medie(MedieServer(null, null), uso, oggi, null))
    }

    @Test
    fun `una copia del patto di ieri non da' i totali di oggi`() {
        val ieri = (8 downTo 1).map { giornoServer(it.toLong(), 30) }
        assertNull(TempiFiglio.medie(MedieServer(PeriodoServer(30, 7, 210), null), ieri, oggi, 50))
    }

    // --- I dispositivi ---------------------------------------------------------

    private fun patto(testo: String): Patto = json.decodeFromString(Patto.serializer(), testo)

    private val nome: (String, String?, String) -> String = { chiave, nomeServer, _ -> nomeServer ?: chiave }

    @Test
    fun `questo telefono per primo, poi il computer`() {
        val p = patto(
            """
            { "uso_recente": [ { "giorno": "2026-10-05", "totale_minuti": 30 } ],
              "medie": { "settimana": { "minuti": 30, "giorni": 1, "totale": 30 } },
              "dispositivo": { "id": 2, "nome": "Telefono di Luca", "tipo": "telefono" },
              "dispositivi": [ { "id": 3, "nome": "PC di Luca", "tipo": "computer",
                                 "uso_recente": [ { "giorno": "2026-10-05", "totale_minuti": 90,
                                                    "app": [ { "chiave": "exe:minecraft.exe", "nome": "Minecraft", "minuti": 90 } ] } ],
                                 "medie": { "settimana": { "minuti": 90, "giorni": 1, "totale": 90 } } },
                               { "id": 2, "nome": "Telefono di Luca", "tipo": "telefono",
                                 "uso_recente": [ { "giorno": "2026-10-05", "totale_minuti": 30 } ] } ] }
            """,
        )
        val tempi = TempiFiglio.dispositivi(p, oggi, locale, nome)
        assertEquals(listOf(2L, 3L), tempi.map { it.id })
        val questo = tempi[0]
        assertTrue(questo.questo && questo.storico)
        assertEquals(50, questo.giorni.last().totaleMinuti)
        assertEquals(PeriodoTempo(30, 1, 50), questo.medie!!.settimana)
        val pc = tempi[1]
        assertTrue(pc.computer)
        assertFalse(pc.questo)
        // Il computer: oggi è quello del server, e i suoi totali così come sono.
        assertEquals(90, pc.giorni.last().totaleMinuti)
        assertEquals("Minecraft", pc.giorni.last().app.single().nome)
        assertEquals(PeriodoTempo(90, 1, 90), pc.medie!!.settimana)
    }

    @Test
    fun `un dispositivo senza tempi (server v3_7) non c'e'`() {
        val p = patto(
            """
            { "dispositivo": { "id": 2, "tipo": "telefono" },
              "dispositivi": [ { "id": 2, "tipo": "telefono" }, { "id": 3, "nome": "PC", "tipo": "computer" } ] }
            """,
        )
        val tempi = TempiFiglio.dispositivi(p, oggi, locale, nome)
        assertEquals(1, tempi.size)
        // Server di prima della v3.8: solo oggi, letto qui, senza totali.
        assertEquals(listOf(locale), tempi.single().giorni)
        assertFalse(tempi.single().storico)
        assertNull(tempi.single().medie)
    }

    @Test
    fun `uno scollegato senza dati negli 8 giorni esce, con dati resta`() {
        val p = patto(
            """
            { "dispositivo": { "id": 2, "tipo": "telefono" },
              "uso_recente": [ { "giorno": "2026-10-05", "totale_minuti": 30 } ],
              "dispositivi": [ { "id": 3, "nome": "Vecchio", "tipo": "telefono", "revocato": true,
                                 "uso_recente": [ { "giorno": "2026-10-04", "totale_minuti": null } ] },
                               { "id": 4, "nome": "PC", "tipo": "computer", "revocato": true,
                                 "uso_recente": [ { "giorno": "2026-10-03", "totale_minuti": 12 } ] } ] }
            """,
        )
        val tempi = TempiFiglio.dispositivi(p, oggi, locale, nome)
        assertEquals(listOf(2L, 4L), tempi.map { it.id })
        assertTrue(tempi[1].revocato)
    }

    @Test
    fun `senza patto c'e' almeno oggi letto qui`() {
        val tempi = TempiFiglio.dispositivi(null, oggi, locale, nome)
        assertEquals(1, tempi.size)
        assertEquals(listOf(locale), tempi.single().giorni)
        assertNull(tempi.single().id)
    }

    @Test
    fun `server che non dice chi e' questo telefono, solo i suoi tempi`() {
        val p = patto(
            """
            { "uso_recente": [ { "giorno": "2026-10-05", "totale_minuti": 30 } ],
              "dispositivi": [ { "id": 3, "tipo": "computer", "uso_recente": [ { "giorno": "2026-10-05", "totale_minuti": 9 } ] } ] }
            """,
        )
        val tempi = TempiFiglio.dispositivi(p, oggi, null, nome)
        assertEquals(1, tempi.size)
        assertEquals(30, tempi.single().giorni.last().totaleMinuti)
    }

    // --- Oggi letto qui: categorie e limiti ------------------------------------

    private fun limite(id: Long, chiave: String, minuti: Int, attiva: Boolean = true) = Regola(
        id = id,
        tipo = TipiRegola.LIMITE_TEMPO,
        parametri = buildJsonObject {
            put("app_o_categoria", chiave)
            put("minuti_al_giorno", minuti)
        },
        attiva = attiva,
    )

    @Test
    fun `i limiti per chiave, la regola piu' vecchia vince`() {
        val limiti = TempiFiglio.limitiPerChiave(
            listOf(
                limite(5, "categoria:social", 90),
                limite(2, "categoria:social", 60),
                limite(3, "com.a", 30, attiva = false),
                Regola(id = 4, tipo = TipiRegola.FASCIA_ORARIA, attiva = true),
            ),
        )
        assertEquals(mapOf("categoria:social" to 60), limiti)
    }

    @Test
    fun `le categorie di oggi sommano i minuti interi delle app`() {
        val categorie = TempiFiglio.categorieDiOggi(
            perApp = listOf(
                "com.ig" to 119_000L, // 1 min
                "com.tt" to 1_259_000L, // 20 min
                "com.gioco" to 59_000L, // 0 min: non conta
            ),
            categoriaDi = { if (it == "com.gioco") "categoria:giochi" else "categoria:social" },
            limiti = mapOf("categoria:social" to 60),
        )
        assertEquals(listOf(CategoriaDelGiorno("categoria:social", 21, 60)), categorie)
    }
}
