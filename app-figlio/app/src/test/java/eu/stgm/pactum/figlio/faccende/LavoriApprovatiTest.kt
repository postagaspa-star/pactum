package eu.stgm.pactum.figlio.faccende

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

/**
 * (0.18, contratto v4.0, parte A) I lavori di casa si sbloccano solo quando
 * un genitore approva la foto. Il telefono segue `attivo` del server: nessuno
 * sblocco locale. La pagina e la barriera dividono i lavori in «Da fare» e
 * «Aspettano l'approvazione». La coda non butta mai una foto da mandare.
 */
class LavoriApprovatiTest {

    private fun ms(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    private val min = 60_000L
    private val giorno = 24 * 60 * min
    private val t0 = ms("2026-10-07T14:00:00+00:00")

    private fun ora(muro: Long, avvio: Int? = 1) = Istante(muro, muro - t0 + 3_600_000L, avvio)

    /** Il blocco della v4.0: un lavoro da fare e uno con la foto che aspetta l'approvazione. */
    private val bloccoV40 = """
        { "attivo": true, "dal": "2026-10-07T14:00:00+00:00", "prossimo": null, "rimandato": false,
          "studio": { "in_corso": false, "id": null, "inizio_ts": null },
          "da_fare": [
            { "id": 5, "titolo": "Svuota la lavastoviglie", "nota": null, "blocco_da": "2026-10-07T14:00:00+00:00",
              "creata_da": { "id": 2, "nome": "Mamma" }, "bocciature": 0, "ultima_bocciatura": null,
              "stato": "fatta", "foto_ts": "2026-10-07T14:10:00+00:00" },
            { "id": 6, "titolo": "Porta fuori il cane", "nota": null, "blocco_da": "2026-10-07T14:00:00+00:00",
              "creata_da": { "id": 3, "nome": "Papà" }, "bocciature": 0, "ultima_bocciatura": null,
              "stato": "da_fare", "foto_ts": null } ] }
    """.trimIndent()

    @Test
    fun `il blocco v4_0 si legge con stato, foto_ts, rimandato e studio`() {
        val b = LetturaFaccende.bloccoDaCorpo(bloccoV40)!!
        assertTrue(b.attivo)
        assertTrue(b.approvazione)
        assertFalse(b.rimandato)
        assertEquals(StudioNelBlocco(false, null, null), b.studio)
        val (lavastoviglie, cane) = b.daFare
        assertTrue(lavastoviglie.aspettaApprovazione)
        assertEquals(ms("2026-10-07T14:10:00+00:00"), lavastoviglie.fotoIl)
        assertFalse(cane.aspettaApprovazione)
        assertNull(cane.fotoIl)
    }

    @Test
    fun `un blocco di prima della v4_0 - tutti da fare, niente approvazione`() {
        val vecchio = """{ "attivo": true, "dal": null, "prossimo": null,
            "da_fare": [ { "id": 5, "titolo": "Letto" } ] }"""
        val b = LetturaFaccende.bloccoDaCorpo(vecchio)!!
        assertFalse(b.approvazione)
        assertFalse(b.rimandato)
        assertNull(b.studio)
        assertFalse(b.daFare.single().aspettaApprovazione)
    }

    @Test
    fun `rimandato dallo Studio - si legge, e il blocco resta attivo`() {
        val corpo = """{ "attivo": true, "dal": "2026-10-07T14:00:00+00:00", "prossimo": null, "rimandato": true,
            "studio": { "in_corso": true, "id": 41, "inizio_ts": "2026-10-07T13:00:00+00:00" }, "da_fare": [] }"""
        val b = LetturaFaccende.bloccoDaCorpo(corpo)!!
        assertTrue(b.attivo)
        assertTrue(b.rimandato)
        assertEquals(StudioNelBlocco(true, 41, ms("2026-10-07T13:00:00+00:00")), b.studio)
        // Un "rimandato" scritto come testo non rimanda.
        assertFalse(LetturaFaccende.bloccoDaCorpo("""{ "attivo": true, "rimandato": "true", "da_fare": [] }""")!!.rimandato)
    }

    @Test
    fun `uno stato che non si conosce vale da fare - mai un lavoro aperto perso`() {
        val f = LetturaFaccende.daFare(Json.parseToJsonElement("""{ "id": 9, "titolo": "Letto", "stato": "boh" }"""))!!
        assertFalse(f.aspettaApprovazione)
    }

    @Test
    fun `da_approvare sulla faccenda intera`() {
        val f = LetturaFaccende.faccenda(
            Json.parseToJsonElement("""{ "id": 5, "titolo": "Letto", "stato": "fatta", "da_approvare": true, "foto_ts": "2026-10-07T14:10:00+00:00" }"""),
        )!!
        assertTrue(f.daApprovare)
        assertTrue(f.aperta)
        val approvata = LetturaFaccende.faccenda(Json.parseToJsonElement("""{ "id": 5, "titolo": "Letto", "stato": "fatta", "da_approvare": false }"""))!!
        assertFalse(approvata.aperta)
        val senza = LetturaFaccende.faccenda(Json.parseToJsonElement("""{ "id": 5, "titolo": "Letto", "stato": "fatta" }"""))!!
        assertFalse(senza.daApprovare)
    }

    // --- Nessuno sblocco locale ------------------------------------------------

    @Test
    fun `la foto arrivata non sblocca - resta bloccato finché il server lo dice`() {
        val bloccato = MemoriaBlocco().conServer(LetturaFaccende.bloccoDaCorpo(bloccoV40)!!, ora(t0), ora(t0), t0)
        assertTrue(bloccato.attivoAdesso(ora(t0)))
        // La risposta alla foto (la faccenda ora "fatta") entra nell'elenco, ma il blocco non cambia.
        val dopoFoto = bloccato.conFaccenda(FaccendaLocale(6, "Porta fuori il cane", stato = StatiFaccenda.FATTA, daApprovare = true))
        assertTrue(dopoFoto.attivoAdesso(ora(t0 + min)))
        // Il server: tutte e due le foto aspettano l'approvazione → ancora attivo.
        val tutteFoto = BloccoDalServer(
            attivo = true, dal = t0, prossimo = null, approvazione = true,
            daFare = listOf(
                FaccendaDaFare(5, "Svuota la lavastoviglie", bloccoDa = t0, stato = StatiFaccenda.FATTA, fotoIl = t0 + 10 * min),
                FaccendaDaFare(6, "Porta fuori il cane", bloccoDa = t0, stato = StatiFaccenda.FATTA, fotoIl = t0 + 12 * min),
            ),
        )
        val m = dopoFoto.conServer(tutteFoto, ora(t0 + 13 * min), ora(t0 + 13 * min), t0 + 13 * min)
        assertTrue(m.attivoAdesso(ora(t0 + 13 * min)))
        // Senza rete per giorni: sempre bloccato (nessuna scadenza).
        assertTrue(m.attivoAdesso(ora(t0 + 5 * giorno)))
        // Un genitore approva l'ultima: il server dice "non attivo" → libero.
        val libero = m.conServer(BloccoDalServer(false, null, null, emptyList(), approvazione = true), ora(t0 + 20 * min), ora(t0 + 20 * min), t0 + 20 * min)
        assertFalse(libero.attivoAdesso(ora(t0 + 20 * min)))
    }

    @Test
    fun `una foto mandata in anticipo e non approvata - il blocco parte da solo all'ora di prossimo`() {
        val prossimo = t0 + 2 * 60 * min
        val programmato = BloccoDalServer(
            attivo = false, dal = null, prossimo = prossimo, approvazione = true,
            daFare = listOf(FaccendaDaFare(5, "Letto", bloccoDa = prossimo, stato = StatiFaccenda.FATTA, fotoIl = t0)),
        )
        val m = MemoriaBlocco().conServer(programmato, ora(t0), ora(t0), t0)
        assertFalse(m.attivoAdesso(ora(t0 + min)))
        assertTrue("senza rete parte lo stesso", m.attivoAdesso(ora(prossimo + 1)))
        assertTrue(m.approvazione)
    }

    @Test
    fun `l'ora del server agganciata - solo nella stessa accensione`() {
        val m = MemoriaBlocco().conServer(BloccoDalServer(false, null, null, emptyList()), ora(t0), ora(t0), t0)
        assertTrue(m.oraAgganciata(ora(t0 + min)))
        assertFalse("dopo un riavvio no", m.oraAgganciata(Istante(t0 + min, 5_000L, avvio = 2)))
        assertFalse("mai sentito il server", MemoriaBlocco().oraAgganciata(ora(t0)))
    }

    // --- La pagina e la barriera: due gruppi ------------------------------------

    private fun memoriaV40(): MemoriaBlocco = MemoriaBlocco().conServer(LetturaFaccende.bloccoDaCorpo(bloccoV40)!!, ora(t0), ora(t0), t0)

    @Test
    fun `due gruppi - da fare e aspettano l'approvazione`() {
        val m = memoriaV40()
        assertEquals(listOf(6L), VistaFaccende.daFare(m).map { it.id })
        val aspetta = VistaFaccende.inApprovazione(m).single()
        assertEquals(5L, aspetta.id)
        assertTrue(aspetta.daApprovare)
        assertEquals(ms("2026-10-07T14:10:00+00:00"), aspetta.fotoIl)
        assertEquals(setOf(5L, 6L), VistaFaccende.aperte(m).map { it.id }.toSet())
        assertTrue(VistaFaccende.chiuse(m).isEmpty())
    }

    @Test
    fun `dall'elenco più fresco - le fatte da approvare sono aperte, le approvate chiuse`() {
        val elenco = listOf(
            FaccendaLocale(5, "Svuota la lavastoviglie", stato = StatiFaccenda.FATTA, daApprovare = true, fotoIl = t0),
            FaccendaLocale(6, "Porta fuori il cane", stato = StatiFaccenda.DA_FARE),
            FaccendaLocale(7, "Letto", stato = StatiFaccenda.FATTA, daApprovare = false, confermataIl = t0, chiusaIl = t0),
        )
        val m = memoriaV40().conElenco(elenco, ora(t0 + min), ora(t0 + min))
        assertEquals(listOf(6L), VistaFaccende.daFare(m).map { it.id })
        assertEquals(listOf(5L), VistaFaccende.inApprovazione(m).map { it.id })
        assertEquals(listOf(7L), VistaFaccende.chiuse(m).map { it.id })
    }

    @Test
    fun `con un server vecchio la pagina resta com'era - tutti da fare`() {
        val vecchio = BloccoDalServer(true, t0, null, listOf(FaccendaDaFare(5, "Letto", bloccoDa = t0)))
        val m = MemoriaBlocco().conServer(vecchio, ora(t0), ora(t0), t0)
        assertEquals(listOf(5L), VistaFaccende.daFare(m).map { it.id })
        assertTrue(VistaFaccende.inApprovazione(m).isEmpty())
        assertFalse(m.approvazione)
    }

    @Test
    fun `una foto che aspetta l'approvazione non porta Pactum prima del resto`() {
        val soloFoto = MemoriaBlocco().conServer(
            BloccoDalServer(false, null, t0 + 60 * min, listOf(FaccendaDaFare(5, "Letto", stato = StatiFaccenda.FATTA)), approvazione = true),
            ora(t0), ora(t0), t0,
        )
        assertFalse(VistaFaccende.primaDelResto(bloccato = false, memoria = soloFoto, arrivoDalleFaccende = false))
        assertTrue(VistaFaccende.primaDelResto(bloccato = true, memoria = soloFoto, arrivoDalleFaccende = false))
    }

    // --- Le notifiche ---------------------------------------------------------------

    @Test
    fun `faccenda_confermata rilegge subito il blocco`() {
        assertTrue(TipiNotificaFaccende.FACCENDA_CONFERMATA in TipiNotificaFaccende.CAMBIANO_IL_BLOCCO)
    }

    private val parole = ParoleFaccende(
        nuove = { _, _ -> "" }, bloccoSubito = "", bloccoAlle = "", bloccoDomani = "", bloccoGiorno = "",
        nuoveTesto = "", bocciata = "", bocciataTesto = "", annullata = "", annullataTesto = "",
        genitoreSenzaNome = "Il genitore",
        confermata = "%1\$s ha confermato «%2\$s»", confermataTesto = "Il lavoro è segnato come svolto.",
        approvata = "%1\$s ha approvato «%2\$s»", approvataSblocca = "Telefono e computer sono sbloccati.",
        approvataTesto = "Il lavoro è fatto.",
    )

    private fun payload(testo: String) = Json.parseToJsonElement(testo) as JsonObject

    @Test
    fun `approvato - l'ultimo sblocca e lo dice`() {
        val (titolo, testo) = TestoFaccende.avvisoConfermata(
            payload("""{ "faccenda_id": 5, "titolo": "Letto", "genitore": { "id": 2, "nome": "Mamma" }, "sblocca": true }"""),
            parole,
        )!!
        assertEquals("Mamma ha approvato «Letto»", titolo)
        assertEquals("Telefono e computer sono sbloccati.", testo)
        val (_, altro) = TestoFaccende.avvisoConfermata(
            payload("""{ "faccenda_id": 5, "titolo": "Letto", "genitore": { "id": 2, "nome": "Mamma" }, "sblocca": false }"""),
            parole,
        )!!
        assertEquals("Il lavoro è fatto.", altro)
    }

    @Test
    fun `un server v3_9 senza sblocca - i testi di prima`() {
        val (titolo, testo) = TestoFaccende.avvisoConfermata(
            payload("""{ "faccenda_id": 5, "titolo": "Letto", "genitore": { "id": 3, "nome": "Papà" } }"""),
            parole,
        )!!
        assertEquals("Papà ha confermato «Letto»", titolo)
        assertEquals("Il lavoro è segnato come svolto.", testo)
    }

    // --- La coda delle foto --------------------------------------------------------

    private val mio = ChiaveCollegamento.di(ChiaveCollegamento.impronta("codice-di-prova"), dispositivoId = 1, figlioId = 1)

    private fun foto(id: Long, quando: Long) = FotoInCoda(faccendaId = id, file = "$id.jpg", scattataIl = quando, collegamento = mio)

    @Test
    fun `la coda non butta mai una foto ancora da mandare - nemmeno oltre il tetto`() {
        var coda = MemoriaCodaFoto()
        val via = mutableListOf<String>()
        for (i in 1..35) {
            val (nuova, togliere) = coda.conScatto(foto(i.toLong(), t0 + i))
            coda = nuova
            via += togliere
        }
        assertEquals(35, coda.foto.size)
        assertEquals(35, coda.daMandare(mio).size)
        assertTrue(via.isEmpty())
    }

    @Test
    fun `oltre il tetto se ne vanno prima le mandate più vecchie, poi le rifiutate`() {
        var coda = MemoriaCodaFoto()
        for (i in 1..28) coda = coda.conScatto(foto(i.toLong(), t0 + i)).first
        coda = coda.conEsito(3, "3.jpg", EsitoFoto.ARRIVATA, t0 + 100).first
        coda = coda.conEsito(4, "4.jpg", EsitoFoto.ARRIVATA, t0 + 50).first
        coda = coda.conEsito(5, "5.jpg", EsitoFoto.RIFIUTATA, t0 + 10).first
        coda = coda.conScatto(foto(29, t0 + 29)).first
        coda = coda.conScatto(foto(30, t0 + 30)).first
        assertEquals(30, coda.foto.size)
        val (dopo, togliere) = coda.conScatto(foto(31, t0 + 31))
        // La mandata più vecchia (la 4, mandata prima) se ne va; la rifiutata resta.
        assertNull(dopo.di(4))
        assertTrue(dopo.di(3) != null && dopo.di(5) != null)
        assertTrue(togliere.isEmpty() || togliere == listOf("4.jpg"))
        val (ancora, _) = dopo.conScatto(foto(32, t0 + 32))
        assertNull(ancora.di(3))
        val (poi, _) = ancora.conScatto(foto(33, t0 + 33))
        assertNull("finite le mandate, tocca alla rifiutata", poi.di(5))
        assertEquals(30, poi.foto.size)
        assertTrue((1L..33L).filter { it !in setOf(3L, 4L, 5L) }.all { poi.di(it)?.stato == StatiFoto.IN_CODA })
    }

    @Test
    fun `dopo una risposta fresca, una foto in coda di un lavoro che aspetta l'approvazione non serve più`() {
        val coda = MemoriaCodaFoto().conScatto(foto(5, t0)).first.conScatto(foto(6, t0)).first
        val (dopo, via) = coda.soloDaFare(
            listOf(FaccendaDaFare(5, "Letto", stato = StatiFaccenda.FATTA, fotoIl = t0), FaccendaDaFare(6, "Cane")),
        )
        assertEquals(listOf(6L), dopo.foto.map { it.faccendaId })
        assertEquals(listOf("5.jpg"), via)
    }

    @Test
    fun `una foto mandata resta tale finché il lavoro aspetta l'approvazione, poi se ne va`() {
        val mandata = MemoriaCodaFoto().conScatto(foto(5, t0)).first.conEsito(5, "5.jpg", EsitoFoto.ARRIVATA, t0).first
        val aspetta = listOf(FaccendaDaFare(5, "Letto", stato = StatiFaccenda.FATTA, fotoIl = t0))
        assertEquals(StatiFoto.MANDATA, mandata.conDaFare(aspetta, t0 + 3 * giorno).first.foto.single().stato)
        // Approvato: non è più fra gli aperti.
        assertTrue(mandata.conDaFare(emptyList(), t0 + 3 * giorno).first.foto.isEmpty())
    }
}
