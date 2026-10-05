package eu.stgm.pactum.figlio.faccende

import eu.stgm.pactum.figlio.dati.TipiNotifica
import eu.stgm.pactum.figlio.notifiche.AvvisiLocali
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * (0.17, correzioni della revisione) Quali lavori bloccano ADESSO (la barriera
 * e la card del blocco li nominano; quelli spostati più avanti stanno sotto,
 * con la loro ora), "da subito" solo per un lavoro dato a blocco subito, e una
 * sola notifica di modifica per lavoro (la nuova sostituisce la vecchia).
 */
class LavoriCheBloccanoTest {

    private val roma = ZoneId.of("Europe/Rome")
    private val min = 60_000L

    /** Venerdì 2 ottobre 2026, 16:30 a Roma. */
    private val adesso = ora(2, 16, 30)

    private fun ora(giorno: Int, ore: Int, minuti: Int = 0, secondi: Int = 0) =
        ZonedDateTime.of(2026, 10, giorno, ore, minuti, secondi, 0, roma).toInstant().toEpochMilli()

    private fun lavoro(id: Long, titolo: String, bloccoDa: Long?) = FaccendaDaFare(id, titolo, bloccoDa = bloccoDa, genitore = "Mamma")

    // --- quali lavori bloccano adesso ---------------------------------------------------

    @Test
    fun `bloccano adesso solo quelli gia' partiti, gli altri dopo, dal primo`() {
        val lavastoviglie = lavoro(1, "Lavastoviglie", ora(2, 14))
        val letto = lavoro(2, "Letto", ora(2, 18))
        val cane = lavoro(3, "Cane", ora(2, 17))
        val subito = lavoro(4, "Spazzatura", null)
        val d = VistaFaccende.divisi(listOf(lavastoviglie, letto, cane, subito), { it.bloccoDa }, adesso, bloccato = true)
        assertEquals(listOf(lavastoviglie, subito), d.adesso)
        assertEquals(listOf(cane, letto), d.poi)
    }

    @Test
    fun `fra pochissimo conta gia' come adesso (la stessa tolleranza del blocco)`() {
        val fraUnMinuto = lavoro(1, "Letto", adesso + min)
        val d = VistaFaccende.divisi(listOf(fraUnMinuto), { it.bloccoDa }, adesso, bloccato = true)
        assertEquals(listOf(fraUnMinuto), d.adesso)
    }

    @Test
    fun `bloccato ma nessuno risulta partito - tutti adesso, mai una barriera vuota`() {
        val letto = lavoro(2, "Letto", ora(2, 18))
        val d = VistaFaccende.divisi(listOf(letto), { it.bloccoDa }, adesso, bloccato = true)
        assertEquals(listOf(letto), d.adesso)
        assertTrue(d.poi.isEmpty())
        // Non bloccato: niente adesso, tutto dopo.
        val libero = VistaFaccende.divisi(listOf(letto), { it.bloccoDa }, adesso, bloccato = false)
        assertTrue(libero.adesso.isEmpty())
        assertEquals(listOf(letto), libero.poi)
    }

    @Test
    fun `lavoro spostato piu' avanti - non blocca piu' adesso`() {
        // Letto era dalle 14:00, Mamma l'ha spostato alle 18:00: resta solo la lavastoviglie.
        val lavastoviglie = lavoro(1, "Lavastoviglie", ora(2, 14))
        val letto = lavoro(2, "Letto", ora(2, 18))
        val d = VistaFaccende.divisi(listOf(letto, lavastoviglie), { it.bloccoDa }, adesso, bloccato = true)
        assertEquals(listOf(lavastoviglie), d.adesso)
        assertEquals(listOf(letto), d.poi)
    }

    // --- "da subito" solo se dato a blocco subito ----------------------------------------

    @Test
    fun `dato per le 16, dopo le 16 dice dalle 16 e non da subito`() {
        val o = TestoFaccende.oraLavoro(bloccoDa = ora(2, 16), creataIl = ora(2, 13), adesso = adesso, zona = roma)
        assertEquals(TestoFaccende.OraLavoro.AllOra(TestoFaccende.Partito.Oggi("16:00")), o)
    }

    @Test
    fun `dato da subito dice da subito con l'ora`() {
        val o = TestoFaccende.oraLavoro(bloccoDa = ora(2, 14, 2, 10), creataIl = ora(2, 14, 2), adesso = adesso, zona = roma)
        assertEquals(TestoFaccende.OraLavoro.DaSubito(TestoFaccende.Partito.Oggi("14:02")), o)
        // Senza ora del blocco è subito; senza ora di creazione non si sa, e si dice l'ora.
        assertEquals(TestoFaccende.OraLavoro.DaSubito(null), TestoFaccende.oraLavoro(null, ora(2, 14), adesso, roma))
        assertEquals(
            TestoFaccende.OraLavoro.AllOra(TestoFaccende.Partito.Oggi("14:02")),
            TestoFaccende.oraLavoro(ora(2, 14, 2), null, adesso, roma),
        )
    }

    @Test
    fun `partito un giorno prima a un'ora scelta, e non ancora partito`() {
        assertEquals(
            TestoFaccende.OraLavoro.AllOra(TestoFaccende.Partito.Prima("1/10", "16:00")),
            TestoFaccende.oraLavoro(ora(1, 16), ora(1, 9), adesso, roma),
        )
        assertEquals(
            TestoFaccende.OraLavoro.Prossimo(QuandoBlocca.Oggi("18:00")),
            TestoFaccende.oraLavoro(ora(2, 18), ora(2, 13), adesso, roma),
        )
        assertTrue(TestoFaccende.datoDaSubito(ora(2, 14, 1), ora(2, 14)))
        assertTrue(!TestoFaccende.datoDaSubito(ora(2, 14, 2), ora(2, 14)))
    }

    // --- una notifica di modifica per lavoro ---------------------------------------------

    private fun payload(testo: String): JsonObject = Json.parseToJsonElement(testo) as JsonObject

    @Test
    fun `la modifica nuova dello stesso lavoro sostituisce la vecchia in tendina`() {
        val prima = AvvisiLocali.idPerNotifica(TipiNotificaFaccende.FACCENDA_MODIFICATA, payload("""{ "faccenda_id": 7, "titolo": "Letto" }"""), idServer = 101)
        val dopo = AvvisiLocali.idPerNotifica(TipiNotificaFaccende.FACCENDA_MODIFICATA, payload("""{ "faccenda_id": 7, "titolo": "Letto" }"""), idServer = 102)
        assertEquals(prima, dopo)
        assertEquals(AvvisiLocali.idLavoroCambiato(7), prima)
        // Un altro lavoro ha la sua.
        assertNotEquals(prima, AvvisiLocali.idPerNotifica(TipiNotificaFaccende.FACCENDA_MODIFICATA, payload("""{ "faccenda_id": 8 }"""), 103))
    }

    @Test
    fun `gli altri tipi restano come prima`() {
        val conferma = payload("""{ "faccenda_id": 7, "titolo": "Letto" }""")
        assertEquals(AvvisiLocali.idNotificaServer(104), AvvisiLocali.idPerNotifica(TipiNotificaFaccende.FACCENDA_CONFERMATA, conferma, 104))
        assertEquals(AvvisiLocali.idProposta(55), AvvisiLocali.idPerNotifica(TipiNotifica.NUOVA_PROPOSTA, payload("""{ "proposta_id": 55 }"""), 105))
        // Senza faccenda_id: quello della notifica del server.
        assertEquals(AvvisiLocali.idNotificaServer(106), AvvisiLocali.idPerNotifica(TipiNotificaFaccende.FACCENDA_MODIFICATA, payload("""{ "titolo": "Letto" }"""), 106))
        // Non collide con gli altri id fissi.
        assertNotEquals(AvvisiLocali.ID_BLOCCO_FACCENDE, AvvisiLocali.idLavoroCambiato(1))
    }
}
