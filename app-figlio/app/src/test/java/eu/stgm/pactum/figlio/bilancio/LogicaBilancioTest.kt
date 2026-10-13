package eu.stgm.pactum.figlio.bilancio

import eu.stgm.pactum.figlio.bilancio.LogicaBilancio.NuovaPromessa
import eu.stgm.pactum.figlio.bilancio.LogicaBilancio.Problema
import eu.stgm.pactum.figlio.dati.Patto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** (0.24) Il bilancio della sera: giorni, promesse, promemoria, tre volte di fila. */
class LogicaBilancioTest {

    private val lun = LocalDate.of(2026, 10, 12)
    private val mar = lun.plusDays(1)
    private fun alle(giorno: LocalDate, ore: Int, minuti: Int = 0) = LocalDateTime.of(giorno, LocalTime.of(ore, minuti))

    private fun promessa(
        id: String,
        giorno: LocalDate = mar,
        ora: String? = null,
        esito: String? = null,
        testo: String = "Mate prima del telefono",
        regolaId: Long? = null,
        avvisoDato: Boolean = false,
    ) = Promessa(id, testo, giorno.toString(), ora, nataIl = giorno.minusDays(1).toString(), regolaId, esito, avvisoDato = avvisoDato)

    // --- il giorno del bilancio ---

    @Test
    fun `prima delle 4 il bilancio e' ancora di ieri sera`() {
        assertEquals(lun, LogicaBilancio.giornoDelBilancio(alle(mar, 0, 30)))
        assertEquals(lun, LogicaBilancio.giornoDelBilancio(alle(mar, 3, 59)))
        assertEquals(mar, LogicaBilancio.giornoDelBilancio(alle(mar, 4, 0)))
        assertEquals(mar, LogicaBilancio.giornoDelBilancio(alle(mar, 21, 30)))
    }

    @Test
    fun `il bilancio si chiede dopo l'ora della sera, finche' non c'e'`() {
        val sera = LocalTime.of(21, 30)
        assertFalse(LogicaBilancio.bilancioDaChiedere(Diario(), alle(lun, 20, 0), sera))
        assertTrue(LogicaBilancio.bilancioDaChiedere(Diario(), alle(lun, 21, 30), sera))
        assertTrue(LogicaBilancio.bilancioDaChiedere(Diario(), alle(mar, 1, 0), sera)) // ancora quello di lunedi'
        val fatto = Diario(bilanci = listOf(Bilancio(lun.toString(), Voti.BENE)))
        assertFalse(LogicaBilancio.bilancioDaChiedere(fatto, alle(lun, 22, 0), sera))
        assertFalse(LogicaBilancio.bilancioDaChiedere(fatto, alle(mar, 1, 0), sera))
    }

    // --- salvare ---

    @Test
    fun `con bene basta un tocco, con male servono il perche' e la promessa`() {
        val adesso = alle(lun, 21, 30)
        assertNull(LogicaBilancio.problema(Voti.BENE, null, null, adesso))
        assertEquals(Problema.COSA_MANCANTE, LogicaBilancio.problema(Voti.MALE, " ok ", null, adesso))
        assertEquals(Problema.PROMESSA_MANCANTE, LogicaBilancio.problema(Voti.MALE, "Troppo TikTok", null, adesso))
        val buona = NuovaPromessa("Mate alle 15:30", mar, LocalTime.of(15, 30))
        assertNull(LogicaBilancio.problema(Voti.COSI_COSI, "Troppo TikTok", buona, adesso))
        // Per oggi no: e' il giorno del bilancio. Un'ora gia' passata nemmeno.
        assertEquals(Problema.GIORNO_NON_VALIDO, LogicaBilancio.problema(Voti.MALE, "Troppo TikTok", buona.copy(giorno = lun), adesso))
        assertEquals(
            Problema.ORA_PASSATA,
            LogicaBilancio.problema(Voti.MALE, "Troppo TikTok", buona.copy(ora = LocalTime.of(0, 10)), alle(mar, 0, 30)),
        )
        assertEquals(Problema.TROPPO_LUNGO, LogicaBilancio.problema(Voti.MALE, "x".repeat(501), buona, adesso))
    }

    @Test
    fun `salvare scrive il bilancio e la promessa, e il secondo bilancio sostituisce il primo`() {
        val primo = LogicaBilancio.salva(
            Diario(), lun, Voti.MALE, "Troppo TikTok", NuovaPromessa("Mate", mar, LocalTime.of(15, 30)), "p1", 1L,
        )
        assertEquals(listOf("p1"), primo.promesse.map { it.id })
        assertEquals("15:30", primo.promesse.single().ora)
        assertEquals(lun.toString(), primo.promesse.single().nataIl)
        assertEquals("p1", primo.bilanci.single().promessaId)
        // Riscritto con "bene": la promessa non risposta se ne va.
        val secondo = LogicaBilancio.salva(primo, lun, Voti.BENE, "ignorato", null, "p2", 2L)
        assertEquals(1, secondo.bilanci.size)
        assertNull(secondo.bilanci.single().cosaNonHaFunzionato)
        assertTrue(secondo.promesse.isEmpty())
    }

    @Test
    fun `una promessa misurabile resta anche se il bilancio si riscrive`() {
        val primo = LogicaBilancio.salva(
            Diario(), lun, Voti.MALE, "Troppo social", NuovaPromessa("Al massimo 1 h di social", mar, regolaId = 9), "p1", 1L,
        )
        val secondo = LogicaBilancio.salva(primo, lun, Voti.BENE, null, null, "p2", 2L)
        assertEquals(listOf(9L), secondo.promesse.map { it.regolaId })
        assertEquals("p1", secondo.bilanci.single().promessaId)
    }

    // --- chiedere e ricordare ---

    @Test
    fun `si chiedono le promesse passate, e di oggi quelle senza ora o con l'ora passata`() {
        val diario = Diario(
            promesse = listOf(
                promessa("ieri", giorno = lun),
                promessa("oggi-dopo", ora = "18:00"),
                promessa("oggi-prima", ora = "15:30"),
                promessa("oggi-senza-ora"),
                promessa("detta", giorno = lun, esito = EsitiPromessa.FATTA),
                promessa("misurabile", giorno = lun, regolaId = 4),
                promessa("vecchia", giorno = lun.minusDays(8)),
                promessa("domani", giorno = mar.plusDays(1)),
            ),
        )
        assertEquals(
            listOf("ieri", "oggi-prima", "oggi-senza-ora"),
            LogicaBilancio.daRispondere(diario, alle(mar, 16, 0)).map { it.id },
        )
    }

    @Test
    fun `il promemoria parte una volta, all'ora detta`() {
        val diario = Diario(promesse = listOf(promessa("a", ora = "15:30"), promessa("b", ora = "18:00")))
        assertTrue(LogicaBilancio.promemoriaDaDare(diario, alle(mar, 15, 29)).isEmpty())
        assertEquals(listOf("a"), LogicaBilancio.promemoriaDaDare(diario, alle(mar, 15, 30)).map { it.id })
        val avvisato = LogicaBilancio.segnaAvvisate(diario, setOf("a"))
        assertTrue(LogicaBilancio.promemoriaDaDare(avvisato, alle(mar, 16, 0)).isEmpty())
        assertEquals(alle(mar, 18, 0), LogicaBilancio.prossimoPromemoria(avvisato, alle(mar, 16, 0)))
        assertNull(LogicaBilancio.prossimoPromemoria(avvisato, alle(mar, 18, 0)))
        // Il giorno dopo, un promemoria di ieri non parte: si chiede nel bilancio.
        assertTrue(LogicaBilancio.promemoriaDaDare(diario, alle(mar.plusDays(1), 9, 0)).isEmpty())
    }

    @Test
    fun `rimandare sposta la promessa e fa ripartire il promemoria`() {
        val diario = LogicaBilancio.segnaAvvisate(Diario(promesse = listOf(promessa("a", ora = "15:30"))), setOf("a"))
        val (giorno, ora) = LogicaBilancio.traUnOra(alle(mar, 15, 35))
        val dopo = LogicaBilancio.rimanda(diario, "a", giorno, ora).promesse.single()
        assertEquals("16:35", dopo.ora)
        assertEquals(1, dopo.rimandi)
        assertFalse(dopo.avvisoDato)
        // Fra un'ora e' gia' domani: stasera, nel bilancio.
        assertEquals(mar to null, LogicaBilancio.traUnOra(alle(mar, 23, 30)))
    }

    @Test
    fun `rispondere vale solo per le promesse non misurabili`() {
        val diario = Diario(promesse = listOf(promessa("a"), promessa("m", regolaId = 3)))
        val dopo = LogicaBilancio.rispondi(LogicaBilancio.rispondi(diario, "a", EsitiPromessa.FATTA), "m", EsitiPromessa.FATTA)
        assertEquals(listOf(EsitiPromessa.FATTA, null), dopo.promesse.map { it.esito })
    }

    // --- le misurabili ---

    @Test
    fun `una misurabile si legge dal semaforo della sua regola di un giorno`() {
        val p = promessa("m", giorno = lun, regolaId = 7)
        fun regola(stato: String) = listOf(RegolaDiUnGiorno(7, lun.toString(), mapOf(lun.toString() to stato)))
        assertEquals(EsitiPromessa.NON_FATTA, LogicaBilancio.esito(p, regola("rosso"), lun)) // gia' oggi
        assertNull(LogicaBilancio.esito(p, regola("verde"), lun)) // oggi puo' ancora andare storta
        assertEquals(EsitiPromessa.FATTA, LogicaBilancio.esito(p, regola("verde"), mar))
        assertEquals(EsitiPromessa.SENZA_DATI, LogicaBilancio.esito(p, regola("grigio"), mar))
        assertEquals(EsitiPromessa.SENZA_DATI, LogicaBilancio.esito(p, emptyList(), mar))
    }

    @Test
    fun `le regole di un giorno arrivano dal patto`() {
        val patto = Json { ignoreUnknownKeys = true }.decodeFromString(
            Patto.serializer(),
            """{ "regole_un_giorno": [ { "id": 7, "giorno": "2026-10-12",
                 "semaforo": [ { "data": "2026-10-12", "stato": "rosso" } ] } ] }""",
        )
        assertTrue(patto.conosceRegoleUnGiorno)
        assertEquals(listOf(RegolaDiUnGiorno(7, "2026-10-12", mapOf("2026-10-12" to "rosso"))), patto.regoleDiUnGiorno())
        assertFalse(Json { ignoreUnknownKeys = true }.decodeFromString(Patto.serializer(), "{}").conosceRegoleUnGiorno)
    }

    // --- tre volte di fila ---

    @Test
    fun `tre volte di fila non riuscita, si chiede di cambiarla`() {
        val tre = (1L..3L).map { promessa("p$it", giorno = lun.minusDays(3 - it), esito = EsitiPromessa.NON_FATTA, testo = "  Mate  prima del TELEFONO ") }
        val diario = Diario(promesse = tre)
        assertEquals(1, LogicaBilancio.daCambiare(diario, emptyList(), mar).size)
        assertTrue(LogicaBilancio.eDaCambiare("mate prima del telefono", diario, emptyList(), mar))
        // Una riuscita in mezzo (o alla fine) rompe la fila.
        val rotta = Diario(promesse = tre + promessa("p4", giorno = mar, esito = EsitiPromessa.FATTA, testo = "Mate prima del telefono"))
        assertTrue(LogicaBilancio.daCambiare(rotta, emptyList(), mar).isEmpty())
        // Due non bastano.
        assertTrue(LogicaBilancio.daCambiare(Diario(promesse = tre.drop(1)), emptyList(), mar).isEmpty())
    }

    // --- la storia e la pulizia ---

    @Test
    fun `la storia degli ultimi giorni, dal piu' recente`() {
        val diario = LogicaBilancio.salva(Diario(), lun, Voti.MALE, "Troppo TikTok", NuovaPromessa("Mate", mar), "p1", 1L)
            .let { LogicaBilancio.rispondi(it, "p1", EsitiPromessa.FATTA) }
        val storia = LogicaBilancio.storia(diario, emptyList(), mar.plusDays(1), quanti = 3)
        assertEquals(listOf(mar, lun, lun.minusDays(1)), storia.map { it.giorno })
        assertEquals(listOf(null, Voti.MALE, null), storia.map { it.voto })
        assertEquals(EsitiPromessa.FATTA, storia[1].esito)
    }

    @Test
    fun `quello di piu' di due mesi fa si butta`() {
        val vecchio = lun.minusDays(61)
        val diario = Diario(
            bilanci = listOf(Bilancio(vecchio.toString(), Voti.BENE), Bilancio(lun.toString(), Voti.BENE)),
            promesse = listOf(promessa("v", giorno = vecchio), promessa("n", giorno = lun)),
        )
        val pulito = LogicaBilancio.pulisci(diario, lun)
        assertEquals(listOf(lun.toString()), pulito.bilanci.map { it.giorno })
        assertEquals(listOf("n"), pulito.promesse.map { it.id })
    }

    // --- le misurabili: la regola di un giorno ---

    @Test
    fun `una misurabile diventa i parametri di una regola, la fascia vale quel giorno della settimana`() {
        val limite = PromesseMisurabili.Misura.Limite("categoria:social", 60)
        assertEquals("limite_tempo", PromesseMisurabili.tipo(limite))
        assertEquals(
            """{"app_o_categoria":"categoria:social","minuti_al_giorno":60}""",
            PromesseMisurabili.parametri(limite, mar).toString(),
        )
        val fascia = PromesseMisurabili.Misura.Fascia(LocalTime.of(15, 0), LocalTime.of(17, 0))
        assertEquals("fascia_oraria", PromesseMisurabili.tipo(fascia))
        // martedi' 13/10/2026
        assertEquals("""{"dalle":"15:00","alle":"17:00","giorni":["mar"]}""", PromesseMisurabili.parametri(fascia, mar).toString())
        assertTrue(PromesseMisurabili.valida(fascia))
        assertFalse(PromesseMisurabili.valida(PromesseMisurabili.Misura.Fascia(LocalTime.of(22, 0), LocalTime.of(7, 0))))
        assertFalse(PromesseMisurabili.valida(PromesseMisurabili.Misura.Limite("totale", 0)))
    }

    // --- la card in Oggi ---

    @Test
    fun `la card in Oggi c'e' solo se c'e' qualcosa da dire`() {
        val sera = LocalTime.of(21, 30)
        assertNull(eu.stgm.pactum.figlio.ui.BilancioInOggi.calcola(Diario(), alle(mar, 10, 0), sera))
        val conPromessa = Diario(promesse = listOf(promessa("p", ora = "15:30")))
        val mattina = eu.stgm.pactum.figlio.ui.BilancioInOggi.calcola(conPromessa, alle(mar, 10, 0), sera)!!
        assertEquals("p", mattina.diOggi?.id)
        assertFalse(mattina.daFare)
        val dopo = eu.stgm.pactum.figlio.ui.BilancioInOggi.calcola(conPromessa, alle(mar, 21, 40), sera)!!
        assertTrue(dopo.daFare)
        assertEquals(1, dopo.daDire)
        assertNull(dopo.diOggi)
        // Con la chiusura della sera spenta il bilancio non si chiede, le promesse si'.
        assertFalse(eu.stgm.pactum.figlio.ui.BilancioInOggi.calcola(conPromessa, alle(mar, 21, 40), null)!!.daFare)
    }
}
