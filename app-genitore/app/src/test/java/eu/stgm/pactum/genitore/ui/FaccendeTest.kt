package eu.stgm.pactum.genitore.ui

import eu.stgm.pactum.genitore.MainActivity
import eu.stgm.pactum.genitore.dati.BloccoFaccende
import eu.stgm.pactum.genitore.dati.Bocciatura
import eu.stgm.pactum.genitore.dati.CodiciErrore
import eu.stgm.pactum.genitore.dati.Dispositivo
import eu.stgm.pactum.genitore.dati.Faccenda
import eu.stgm.pactum.genitore.dati.Figlio
import eu.stgm.pactum.genitore.dati.Genitore
import eu.stgm.pactum.genitore.dati.Notifica
import eu.stgm.pactum.genitore.dati.Proposta
import eu.stgm.pactum.genitore.dati.RiferimentoGenitore
import eu.stgm.pactum.genitore.dati.RispostaProposta
import eu.stgm.pactum.genitore.dati.Sessione
import eu.stgm.pactum.genitore.dati.StatiFaccenda
import eu.stgm.pactum.genitore.rete.EsitoAbbinamento
import eu.stgm.pactum.genitore.rete.PostinoClient
import eu.stgm.pactum.genitore.sync.Vedetta
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * (0.13) Le faccende e i genitori (contratto v3.6): da quando parte il blocco
 * (oggi o domani, detto prima di mandare), la finestra delle 24 ore per bocciare,
 * le app troppo vecchie per il blocco, chi ha fatto cosa, e le frasi che il padre
 * legge — quelle vere di strings.xml (ParoleDiProva). Se sbagliano, un padre crede
 * bloccato un telefono libero, o boccia una foto che non si può più bocciare.
 */
class FaccendeTest {

    private val p = ParoleDiProva
    private val roma: ZoneId = ZoneId.of("Europe/Rome")

    // 2 ottobre 2026, 15:10 a Roma (13:10 UTC).
    private val adesso: Instant = Instant.parse("2026-10-02T13:10:00Z")
    private val oggi: LocalDate = LocalDate.of(2026, 10, 2)

    private val mamma = RiferimentoGenitore(2, "Mamma")
    private val papa = RiferimentoGenitore(1, "Papà")

    private fun faccenda(
        id: Long = 5,
        titolo: String = "Svuota la lavastoviglie",
        stato: String = StatiFaccenda.DA_FARE,
        bloccoDa: String? = "2026-10-02T12:00:00+00:00",
        creataTs: String? = "2026-10-02T11:00:00+00:00",
        creataDa: RiferimentoGenitore? = mamma,
        fotoTs: String? = null,
        foto: Boolean = false,
        bocciature: Int = 0,
        ultimaBocciatura: Bocciatura? = null,
        chiusaTs: String? = null,
        annullataDa: RiferimentoGenitore? = null,
    ) = Faccenda(
        id = id,
        figlioId = 1,
        titolo = titolo,
        stato = stato,
        bloccoDa = bloccoDa,
        creataTs = creataTs,
        creataDa = creataDa,
        fotoTs = fotoTs,
        foto = foto,
        bocciature = bocciature,
        ultimaBocciatura = ultimaBocciatura,
        chiusaTs = chiusaTs,
        annullataDa = annullataDa,
    )

    // --- (0.14, contratto v3.7) "lavori di casa", mai "faccende" ------------------------

    @Test
    fun `nessuna frase che il genitore legge dice faccenda o faccende`() {
        val documento = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(java.io.File("src/main/res/values/strings.xml"))
        val nodi = documento.getElementsByTagName("string")
        val conFaccende = (0 until nodi.length)
            .map { nodi.item(it) as org.w3c.dom.Element }
            .filter { it.textContent.contains("faccend", ignoreCase = true) }
            .map { it.getAttribute("name") }
        assertTrue("Dicono ancora «faccende»: $conFaccende", conFaccende.isEmpty())
        // E al singolare "lavoro", maschile.
        assertEquals("Lavoro fatto", p.testo(eu.stgm.pactum.genitore.R.string.tipo_faccenda_fatta))
        assertEquals("Lavori di casa", p.testo(eu.stgm.pactum.genitore.R.string.faccende_titolo))
        assertEquals("Dai lavori di casa", p.testo(eu.stgm.pactum.genitore.R.string.faccende_dai))
        assertEquals("Lavoro annullato.", p.testo(eu.stgm.pactum.genitore.R.string.annulla_faccenda_fatto))
        assertEquals("Bocciato: il lavoro è di nuovo da fare.", p.testo(eu.stgm.pactum.genitore.R.string.boccia_fatto))
    }

    // --- l'ora del blocco: oggi o domani ---------------------------------------------

    @Test
    fun `un'ora che deve ancora venire vale oggi`() {
        val inizio = inizioBlocco(LocalTime.of(16, 0), ZonedDateTime.ofInstant(adesso, roma))
        assertFalse(inizio.domani)
        assertEquals("2026-10-02T16:00:00+02:00", testoBloccoDa(inizio.quando))
    }

    @Test
    fun `un'ora già passata oggi vale domani, e il testo lo dice`() {
        val inizio = inizioBlocco(LocalTime.of(7, 30), ZonedDateTime.ofInstant(adesso, roma))
        assertTrue(inizio.domani)
        assertEquals("2026-10-03T07:30:00+02:00", testoBloccoDa(inizio.quando))
        assertEquals(
            "Le 07:30 di oggi sono già passate: il blocco parte domani, sabato 03/10, alle 07:30, se a quell'ora non li ha ancora fatti tutti.",
            testoInizioBlocco(p, inizio, "Luca"),
        )
    }

    @Test
    fun `l'ora di adesso è già cominciata, vale domani`() {
        // 15:10:30 e il genitore sceglie le 15:10: quel minuto è già partito.
        val inizio = inizioBlocco(LocalTime.of(15, 10), ZonedDateTime.ofInstant(adesso.plusSeconds(30), roma))
        assertTrue(inizio.domani)
        assertEquals("2026-10-03T15:10:00+02:00", testoBloccoDa(inizio.quando))
    }

    @Test
    fun `i secondi dell'ora scelta non contano`() {
        val inizio = inizioBlocco(LocalTime.of(16, 0, 45), ZonedDateTime.ofInstant(adesso, roma))
        assertEquals("2026-10-02T16:00:00+02:00", testoBloccoDa(inizio.quando))
    }

    @Test
    fun `domani col cambio dell'ora, il fuso giusto nel testo per il server`() {
        // Il 25 ottobre 2026 l'Italia torna all'ora solare (+01:00).
        val sabatoSera = ZonedDateTime.of(2026, 10, 24, 20, 0, 0, 0, roma)
        val inizio = inizioBlocco(LocalTime.of(8, 0), sabatoSera)
        assertTrue(inizio.domani)
        assertEquals("2026-10-25T08:00:00+01:00", testoBloccoDa(inizio.quando))
        assertEquals(
            "Le 08:00 di oggi sono già passate: il blocco parte domani, domenica 25/10, alle 08:00, se a quell'ora non li ha ancora fatti tutti.",
            testoInizioBlocco(p, inizio, "Luca"),
        )
    }

    // --- l'ora doppia (fine ottobre) e l'ora che manca (fine marzo) ----------------------

    @Test
    fun `nell'ora doppia, la prima volta delle 02,45 è ancora da venire`() {
        // Il 25/10/2026 alle 01:30 (ora legale, +02:00): le 02:45 arrivano due volte, vale la prima.
        val adessoLegale = ZonedDateTime.of(2026, 10, 25, 1, 30, 0, 0, roma)
        val inizio = inizioBlocco(LocalTime.of(2, 45), adessoLegale)
        assertFalse(inizio.domani)
        assertEquals("2026-10-25T02:45:00+02:00", testoBloccoDa(inizio.quando))
    }

    @Test
    fun `nell'ora doppia con l'ora solare già cominciata, vale la seconda volta e non domani`() {
        // Le 02:30 della seconda volta (+01:00): le 02:45 "legali" sono passate, quelle "solari" no.
        val adessoSolare = ZonedDateTime.of(2026, 10, 25, 2, 30, 0, 0, roma).withLaterOffsetAtOverlap()
        val inizio = inizioBlocco(LocalTime.of(2, 45), adessoSolare)
        assertFalse(inizio.domani)
        assertEquals("2026-10-25T02:45:00+01:00", testoBloccoDa(inizio.quando))
        // E un'ora passata tutte e due le volte vale domani.
        val passata = inizioBlocco(LocalTime.of(2, 15), adessoSolare)
        assertTrue(passata.domani)
        assertEquals("2026-10-26T02:15:00+01:00", testoBloccoDa(passata.quando))
    }

    @Test
    fun `nell'ora che manca, l'ora scelta slitta all'ora vera e il testo dice quella`() {
        // Il 28/03/2027 le 02:00-03:00 non esistono: le 02:30 sono le 03:30 (+02:00).
        val notte = ZonedDateTime.of(2027, 3, 28, 1, 0, 0, 0, roma)
        val inizio = inizioBlocco(LocalTime.of(2, 30), notte)
        assertFalse(inizio.domani)
        assertEquals("2027-03-28T03:30:00+02:00", testoBloccoDa(inizio.quando))
        assertEquals(
            "Il blocco parte oggi, domenica 28/03, alle 03:30, se a quell'ora non li ha ancora fatti tutti. Può farli anche prima.",
            testoInizioBlocco(p, inizio, "Luca"),
        )
    }

    // --- il controllo prima di mandare ------------------------------------------------------

    @Test
    fun `se l'istante mostrato è ancora quello, si manda col suo blocco_da`() {
        val alle = ZonedDateTime.ofInstant(adesso, roma)
        val mostrato = inizioBlocco(LocalTime.of(16, 0), alle)
        assertEquals(
            ControlloInvio.Manda("2026-10-02T16:00:00+02:00"),
            controlloPrimaDiMandare(mostrato, LocalTime.of(16, 0), alle.plusMinutes(5)),
        )
        assertEquals(ControlloInvio.Manda(null), controlloPrimaDiMandare(null, null, alle))
    }

    @Test
    fun `dialogo aperto a cavallo di mezzanotte, niente parte e si mostra la data nuova`() {
        // Alle 23:58 del 24/10 "domani alle 08:00" è domenica 25; alle 00:01 del 25 le
        // 08:00 sono di oggi, lo stesso istante: si manda.
        val primaDiMezzanotte = ZonedDateTime.of(2026, 10, 24, 23, 58, 0, 0, roma)
        val mostrato = inizioBlocco(LocalTime.of(8, 0), primaDiMezzanotte)
        val dopo = ZonedDateTime.of(2026, 10, 25, 0, 1, 0, 0, roma)
        assertEquals(ControlloInvio.Manda("2026-10-25T08:00:00+01:00"), controlloPrimaDiMandare(mostrato, LocalTime.of(8, 0), dopo))
        // Mostrato alle 23:58 "oggi alle 23:59"; tocco alle 00:01: le 23:59 sono di
        // lunedì 26, un altro istante. Niente parte, e il dialogo mostra il giorno nuovo.
        val mostratoTardi = inizioBlocco(LocalTime.of(23, 59), primaDiMezzanotte)
        val controllo = controlloPrimaDiMandare(mostratoTardi, LocalTime.of(23, 59), dopo)
        assertTrue(controllo is ControlloInvio.Cambiato)
        val nuovo = (controllo as ControlloInvio.Cambiato).nuovo
        assertEquals("2026-10-25T23:59:00+01:00", testoBloccoDa(nuovo.quando))
        assertEquals(
            "Il blocco parte oggi, domenica 25/10, alle 23:59, se a quell'ora non li ha ancora fatti tutti. Può farli anche prima.",
            testoInizioBlocco(p, nuovo, "Luca"),
        )
        // Senza niente di mostrato (non dovrebbe succedere) non si manda alla cieca.
        assertTrue(controlloPrimaDiMandare(null, LocalTime.of(8, 0), dopo) is ControlloInvio.Cambiato)
    }

    @Test
    fun `domani non scivola di un giorno, se il dialogo resta aperto tutta la notte`() {
        // Venerdì 02/10 alle 23:50 il dialogo dice "domani, sabato 03/10, alle 07:30".
        val venerdiSera = ZonedDateTime.of(2026, 10, 2, 23, 50, 0, 0, roma)
        val mostrato = inizioBlocco(LocalTime.of(7, 30), venerdiSera)
        assertEquals("2026-10-03T07:30:00+02:00", testoBloccoDa(mostrato.quando))
        // Il tocco arriva sabato alle 07:31: è ancora "domani", ma domenica. Prima si dice.
        val controllo = controlloPrimaDiMandare(mostrato, LocalTime.of(7, 30), ZonedDateTime.of(2026, 10, 3, 7, 31, 0, 0, roma))
        val nuovo = (controllo as ControlloInvio.Cambiato).nuovo
        assertTrue(nuovo.domani)
        assertEquals(
            "Le 07:30 di oggi sono già passate: il blocco parte domani, domenica 04/10, alle 07:30, se a quell'ora non li ha ancora fatti tutti.",
            testoInizioBlocco(p, nuovo, "Luca"),
        )
    }

    @Test
    fun `l'ora proposta è la prossima ora piena`() {
        assertEquals(LocalTime.of(16, 0), oraProposta(LocalTime.of(15, 10)))
        assertEquals(LocalTime.of(0, 0), oraProposta(LocalTime.of(23, 59)))
    }

    @Test
    fun `che cosa succede col blocco, prima di mandare`() {
        assertEquals(
            "Il blocco parte appena li dai: telefono e computer di Luca restano bloccati finché non manda la foto di ogni lavoro.",
            testoInizioBlocco(p, null, "Luca"),
        )
        assertEquals(
            "Il blocco parte appena li dai: telefono e computer di tuo figlio restano bloccati finché non manda la foto di ogni lavoro.",
            testoInizioBlocco(p, null, " "),
        )
        val oggiAlle16 = inizioBlocco(LocalTime.of(16, 0), ZonedDateTime.ofInstant(adesso, roma))
        assertEquals(
            "Il blocco parte oggi, venerdì 02/10, alle 16:00, se a quell'ora non li ha ancora fatti tutti. Può farli anche prima.",
            testoInizioBlocco(p, oggiAlle16, "Luca"),
        )
        assertEquals("Dalle 07:30", testoDalle(p, LocalTime.of(7, 30)))
    }

    // --- lo stato del blocco ---------------------------------------------------------

    @Test
    fun `il blocco è attivo quando una da fare ha il blocco già partito, dal più vecchio`() {
        val stato = statoBlocco(
            listOf(
                faccenda(id = 1, bloccoDa = "2026-10-02T12:00:00+00:00"),
                faccenda(id = 2, bloccoDa = "2026-10-02T11:30:00+00:00"),
                faccenda(id = 3, bloccoDa = "2026-10-02T18:00:00+00:00"),
                faccenda(id = 4, stato = StatiFaccenda.FATTA, bloccoDa = "2026-10-01T08:00:00+00:00"),
            ),
            adesso,
        )
        assertTrue(stato.attivo)
        assertEquals(Instant.parse("2026-10-02T11:30:00Z"), stato.dal)
        assertNull(stato.prossimo)
        assertEquals(3, stato.daFare)
        assertEquals("Blocco attivo dalle 13:30", testoStatoBlocco(p, stato, roma, oggi))
    }

    @Test
    fun `senza blocchi partiti, il prossimo`() {
        val stato = statoBlocco(
            listOf(
                faccenda(id = 1, bloccoDa = "2026-10-03T05:30:00+00:00"),
                faccenda(id = 2, bloccoDa = "2026-10-02T14:00:00+00:00"),
            ),
            adesso,
        )
        assertFalse(stato.attivo)
        assertEquals(Instant.parse("2026-10-02T14:00:00Z"), stato.prossimo)
        assertEquals("Il blocco parte alle 16:00", testoStatoBlocco(p, stato, roma, oggi))
        val domani = statoBlocco(listOf(faccenda(bloccoDa = "2026-10-03T05:30:00+00:00")), adesso)
        assertEquals("Il blocco parte il 03/10 alle 07:30", testoStatoBlocco(p, domani, roma, oggi))
    }

    @Test
    fun `niente da fare, nessun blocco`() {
        val stato = statoBlocco(listOf(faccenda(stato = StatiFaccenda.FATTA)), adesso)
        assertFalse(stato.attivo)
        assertEquals(0, stato.daFare)
        assertEquals("Niente da fare: nessun blocco.", testoStatoBlocco(p, stato, roma, oggi))
        assertNull(testoQuanteDaFare(p, 0))
        assertEquals("Un lavoro di casa da fare", testoQuanteDaFare(p, 1))
        assertEquals("3 lavori di casa da fare", testoQuanteDaFare(p, 3))
    }

    @Test
    fun `una da fare senza blocco leggibile vale bloccata, mai libera`() {
        val stato = statoBlocco(listOf(faccenda(bloccoDa = null)), adesso)
        assertTrue(stato.attivo)
        assertNull(stato.dal)
        assertEquals("Blocco attivo", testoStatoBlocco(p, stato, roma, oggi))
    }

    @Test
    fun `col blocco del server vale il server`() {
        val stato = statoBlocco(
            listOf(faccenda(bloccoDa = "2026-10-02T14:00:00+00:00")),
            adesso,
            BloccoFaccende(attivo = true, dal = "2026-10-02T13:05:00+00:00", prossimo = null),
        )
        assertTrue(stato.attivo)
        assertEquals(Instant.parse("2026-10-02T13:05:00Z"), stato.dal)
    }

    @Test
    fun `blocca dalle, solo per una faccenda che non blocca ancora`() {
        assertEquals("blocca dalle 16:00", testoBloccaDalle(p, faccenda(bloccoDa = "2026-10-02T14:00:00+00:00"), adesso, roma, oggi))
        assertNull(testoBloccaDalle(p, faccenda(bloccoDa = "2026-10-02T12:00:00+00:00"), adesso, roma, oggi))
        assertNull(testoBloccaDalle(p, faccenda(stato = StatiFaccenda.FATTA, bloccoDa = "2026-10-02T14:00:00+00:00"), adesso, roma, oggi))
    }

    // --- le 24 ore per bocciare ------------------------------------------------------

    private fun fatta(fotoTs: String?) =
        faccenda(stato = StatiFaccenda.FATTA, fotoTs = fotoTs, foto = true, chiusaTs = fotoTs)

    @Test
    fun `si boccia entro 24 ore dalla foto, e si dice quanto manca (per difetto)`() {
        val stato = bocciabile(fatta("2026-10-02T10:00:00+00:00"), adesso)
        assertEquals(Bocciabile.Si(Duration.ofHours(20).plusMinutes(50)), stato)
        assertEquals("Puoi bocciarlo ancora per 20 h 50 min.", testoBocciabile(p, stato))
        val quasi = bocciabile(fatta("2026-10-02T10:00:00+00:00"), Instant.parse("2026-10-03T09:39:30Z"))
        assertEquals("Puoi bocciarlo ancora per 20 min.", testoBocciabile(p, quasi))
    }

    @Test
    fun `nell'ultimo minuto si dice meno di un minuto, mai zero`() {
        val stato = bocciabile(fatta("2026-10-02T10:00:00+00:00"), Instant.parse("2026-10-03T09:59:30Z"))
        assertTrue(stato is Bocciabile.Si)
        assertEquals("Puoi bocciarlo ancora per meno di un minuto.", testoBocciabile(p, stato))
    }

    @Test
    fun `dopo 24 ore esatte non si boccia più`() {
        val stato = bocciabile(fatta("2026-10-02T10:00:00+00:00"), Instant.parse("2026-10-03T10:00:00Z"))
        assertEquals(Bocciabile.Scaduta, stato)
        assertEquals("Sono passate 24 ore dalla foto: non si può più bocciare.", testoBocciabile(p, stato))
    }

    @Test
    fun `una da fare, un'annullata o una fatta senza ora della foto non si bocciano`() {
        assertEquals(Bocciabile.No, bocciabile(faccenda(), adesso))
        assertEquals(Bocciabile.No, bocciabile(faccenda(stato = StatiFaccenda.ANNULLATA), adesso))
        assertEquals(Bocciabile.No, bocciabile(fatta(null), adesso))
        assertNull(testoBocciabile(p, Bocciabile.No))
    }

    @Test
    fun `le foto da guardare sono quelle ancora bocciabili`() {
        val faccende = listOf(
            fatta("2026-10-02T10:00:00+00:00"),
            fatta("2026-09-30T10:00:00+00:00").copy(id = 6),
            faccenda(id = 7),
        )
        assertEquals(1, fotoDaGuardare(faccende, adesso))
        assertEquals("Una foto arrivata da meno di 24 ore: si può ancora bocciare.", testoFotoDaGuardare(p, 1))
        assertNull(testoFotoDaGuardare(p, 0))
    }

    // --- le app troppo vecchie per il blocco -------------------------------------------

    private fun dispositivo(
        id: Long,
        nome: String,
        tipo: String = "telefono",
        versione: String?,
        abbinato: Boolean = true,
        revocato: Boolean = false,
    ) = Dispositivo(id = id, nome = nome, tipo = tipo, abbinato = abbinato, revocato = revocato, versioneApp = versione)

    @Test
    fun `un'app più vecchia della 0,13 si dice, una nuova no`() {
        val senza = dispositiviSenzaBlocco(
            listOf(
                dispositivo(1, "Telefono", versione = "0.12.0"),
                dispositivo(2, "Computer", tipo = "computer", versione = "0.13.0"),
                dispositivo(3, "Tablet", versione = "0.14"),
                dispositivo(4, "Vecchio", versione = "0.13.1"),
            ),
        )
        assertEquals(listOf(1L), senza.map { it.dispositivo.id })
        assertEquals(
            "Su «Telefono» c'è Pactum 0.12.0: lì il blocco non parte. Serve la 0.13 o più nuova.",
            testoDispositivoSenzaBlocco(p, senza.single()),
        )
    }

    @Test
    fun `una versione che non si sa si dice, senza inventarla`() {
        val senza = dispositiviSenzaBlocco(
            listOf(
                dispositivo(1, "Computer", tipo = "computer", versione = null),
                dispositivo(2, "Telefono", versione = "boh"),
            ),
        )
        assertEquals(listOf(1L, 2L), senza.map { it.dispositivo.id })
        assertEquals(
            "Su «Computer» non so che versione di Pactum c'è: se è più vecchia della 0.13, lì il blocco non parte.",
            testoDispositivoSenzaBlocco(p, senza.first()),
        )
        // "boh" non è una versione: vale come una che non si sa.
        assertNull(senza[1].versione)
    }

    @Test
    fun `i dispositivi scollegati o non ancora collegati non contano`() {
        val senza = dispositiviSenzaBlocco(
            listOf(
                dispositivo(1, "Scollegato", versione = "0.10.0", revocato = true),
                dispositivo(2, "Da collegare", versione = null, abbinato = false),
            ),
        )
        assertTrue(senza.isEmpty())
    }

    @Test
    fun `il confronto delle versioni`() {
        assertEquals(0, confrontaVersioni("0.13", "0.13.0"))
        assertTrue(confrontaVersioni("0.12.9", "0.13.0")!! < 0)
        assertTrue(confrontaVersioni("0.13.0-prova", "0.13.0") == 0)
        assertTrue(confrontaVersioni("1.0.0", "0.13.0")!! > 0)
        assertNull(confrontaVersioni("", "0.13.0"))
        assertNull(confrontaVersioni("0.x", "0.13.0"))
    }

    // --- chi ha fatto cosa --------------------------------------------------------------

    @Test
    fun `chi ha fatto, tu, un altro, o non si sa`() {
        assertEquals(ChiHaFatto.Tu, chiHaFatto(mamma, mamma))
        assertEquals(ChiHaFatto.Altro("Mamma"), chiHaFatto(mamma, papa))
        assertEquals(ChiHaFatto.Altro("Mamma"), chiHaFatto(mamma, null))
        assertEquals(ChiHaFatto.NonSi, chiHaFatto(null, papa))
        assertEquals(ChiHaFatto.NonSi, chiHaFatto(RiferimentoGenitore(3, "  "), papa))
    }

    @Test
    fun `data da, bocciata da, annullata da`() {
        assertEquals("dato da Mamma", testoDataDa(p, faccenda(creataDa = mamma), papa))
        assertEquals("dato da te", testoDataDa(p, faccenda(creataDa = mamma), mamma))
        assertNull(testoDataDa(p, faccenda(creataDa = null), mamma))
        assertEquals(
            listOf("Bocciato 2 volte", "L'ultima volta da Papà: «manca il cestello»"),
            righeBocciature(
                p,
                faccenda(bocciature = 2, ultimaBocciatura = Bocciatura(ts = "…", nota = "manca il cestello", da = papa)),
                mamma,
            ),
        )
        assertEquals(
            listOf("Bocciato una volta", "L'ultima volta da te"),
            righeBocciature(p, faccenda(bocciature = 1, ultimaBocciatura = Bocciatura(da = mamma)), mamma),
        )
        assertTrue(righeBocciature(p, faccenda(), mamma).isEmpty())
        assertEquals("Annullato da Papà", testoAnnullata(p, faccenda(annullataDa = papa), mamma))
        assertEquals("Annullato da te", testoAnnullata(p, faccenda(annullataDa = papa), papa))
        assertEquals("Annullato", testoAnnullata(p, faccenda(), papa))
    }

    @Test
    fun `una proposta di un altro genitore dice il suo nome, e si ritira anche lei`() {
        val diMamma = Proposta(id = 1, regolaId = 1, stato = "pendente", autore = "genitore", genitore = mamma)
        assertEquals("Proposta di Mamma", autoreProposta(p, diMamma, "Luca", papa))
        assertEquals("Proposta tua", autoreProposta(p, diMamma, "Luca", mamma))
        // Contratto v3.6, "Precisazioni": tutti i genitori sono uguali anche nel ritirare.
        assertTrue(ritirabile(diMamma))
        // Server più vecchio: nessun genitore sulla proposta, è tua come prima.
        val vecchia = diMamma.copy(genitore = null)
        assertEquals("Proposta tua", autoreProposta(p, vecchia, "Luca", null))
        assertTrue(ritirabile(vecchia))
    }

    @Test
    fun `la risposta a una proposta del figlio dice quale genitore`() {
        val delFiglio = Proposta(
            id = 2,
            regolaId = 1,
            stato = "accettata",
            autore = "figlio",
            risposta = RispostaProposta(esito = "accetta", motivazione = "va bene"),
            rispostaDi = mamma,
        )
        assertEquals("Mamma ha accettato" to "Mamma ha detto: va bene", rispostaProposta(p, delFiglio, papa))
        assertEquals("Hai accettato" to "Hai detto: va bene", rispostaProposta(p, delFiglio, mamma))
        assertEquals("Hai accettato" to "Hai detto: va bene", rispostaProposta(p, delFiglio.copy(rispostaDi = null), papa))
        val tua = Proposta(
            id = 3,
            regolaId = 1,
            stato = "rifiutata",
            autore = "genitore",
            risposta = RispostaProposta(esito = "rifiuta", motivazione = null),
        )
        assertEquals("Il figlio ha rifiutato" to null, rispostaProposta(p, tua, papa))
        assertNull(rispostaProposta(p, tua.copy(risposta = null), papa))
    }

    @Test
    fun `chi ha deciso per ultimo su una sessione`() {
        val sessione = Sessione(id = 1, nome = "Studio", stato = "approvata", decisaDa = mamma)
        // L'ULTIMA decisione: può essere anche il no a un cambio, mai "approvata da".
        assertEquals("ultima decisione: Mamma", testoUltimaDecisione(p, sessione, papa))
        assertEquals("ultima decisione: tu", testoUltimaDecisione(p, sessione, mamma))
        assertNull(testoUltimaDecisione(p, sessione.copy(decisaDa = null), mamma))
    }

    // --- i genitori -----------------------------------------------------------------------

    private val genitori = listOf(
        Genitore(id = 1, nome = "Papà"),
        Genitore(id = 2, nome = "Mamma", abbinato = false),
        Genitore(id = 3, nome = "Nonna", revocato = true),
    )

    @Test
    fun `non si toglie te stesso, né l'ultimo, né uno già tolto`() {
        assertFalse(puoiTogliere(genitori[0], papa, genitori))
        assertTrue(puoiTogliere(genitori[1], papa, genitori))
        assertFalse(puoiTogliere(genitori[2], papa, genitori))
        val soloTu = listOf(Genitore(id = 1, nome = "Papà"), Genitore(id = 3, nome = "Nonna", revocato = true))
        assertFalse(puoiTogliere(soloTu[0], mamma, soloTu))
    }

    @Test
    fun `un codice nuovo si dà agli altri, non a te`() {
        assertFalse(puoiDareNuovoCodice(genitori[0], papa))
        assertTrue(puoiDareNuovoCodice(genitori[1], papa))
        assertFalse(puoiDareNuovoCodice(genitori[2], papa))
    }

    @Test
    fun `il codice di un genitore risulta usato quando si collega`() {
        assertFalse(codiceGenitoreUsato(genitori, 2, ricollegamento = false))
        val collegata = genitori.map { if (it.id == 2L) it.copy(abbinato = true) else it }
        assertTrue(codiceGenitoreUsato(collegata, 2, ricollegamento = false))
        assertFalse(codiceGenitoreUsato(collegata, 2, ricollegamento = true))
        assertFalse(codiceGenitoreUsato(collegata, null, ricollegamento = false))
    }

    @Test
    fun `un genitore creato con la risposta persa si ritrova`() {
        val dopo = genitori + Genitore(id = 4, nome = "Zia", abbinato = false)
        assertEquals(4L, genitoreCreato(genitori, dopo, " Zia ")?.id)
        assertNull(genitoreCreato(genitori, genitori, "Zia"))
    }

    @Test
    fun `il codice di 6 cifre tiene solo le cifre`() {
        assertEquals("483920", soloCifre("483 920"))
        assertEquals("483920", soloCifre("483-9201"))
        assertTrue(codiceCompleto("483920"))
        assertFalse(codiceCompleto("48392"))
    }

    // --- titoli e note --------------------------------------------------------------------

    @Test
    fun `il titolo di una faccenda`() {
        assertNull(problemaTitolo("  Svuota la lavastoviglie  "))
        assertEquals(ProblemaTesto.VUOTO, problemaTitolo("   "))
        assertNull(problemaTitolo("a".repeat(80)))
        assertEquals(ProblemaTesto.TROPPO_LUNGO, problemaTitolo("a".repeat(81)))
        // Un'emoji è un carattere solo, come per il server.
        assertNull(problemaTitolo("🧹".repeat(80)))
        assertEquals(ProblemaTesto.CARATTERI_INVISIBILI, problemaTitolo("Rifai\til letto"))
        assertEquals(ProblemaTesto.CARATTERI_INVISIBILI, problemaTitolo("Rifai\nil letto"))
        assertEquals(ProblemaTesto.CARATTERI_INVISIBILI, problemaTitolo("Rifai​il letto"))
        assertEquals("Al massimo 80 caratteri.", testoProblemaTitolo(p, ProblemaTesto.TROPPO_LUNGO))
        assertEquals("Scrivi il lavoro, o togli la riga.", testoProblemaTitolo(p, ProblemaTesto.VUOTO))
    }

    @Test
    fun `la nota ammette gli a capo, non il resto`() {
        assertNull(problemaNota(""))
        assertNull(problemaNota("anche le pentole\r\ne i bicchieri"))
        assertEquals(ProblemaTesto.TROPPO_LUNGO, problemaNota("a".repeat(301)))
        assertEquals(ProblemaTesto.CARATTERI_INVISIBILI, problemaNota("anche\tle pentole"))
        assertEquals("anche le pentole\ne i bicchieri", ripulisci(" anche le pentole\r\ne i bicchieri "))
        assertNull(testoProblemaNota(p, ProblemaTesto.VUOTO))
    }

    @Test
    fun `la stessa parola scritta in due modi è la stessa (NFC)`() {
        assertEquals("perché", ripulisci("perché"))
    }

    @Test
    fun `i titoli usati di recente, dal più recente, senza doppioni né quelli già scritti`() {
        val storia = listOf(
            faccenda(id = 1, titolo = "Rifai il letto", creataTs = "2026-09-30T08:00:00+00:00"),
            faccenda(id = 2, titolo = "Svuota la lavastoviglie", creataTs = "2026-10-01T08:00:00+00:00"),
            faccenda(id = 3, titolo = "rifai il letto ", creataTs = "2026-10-02T08:00:00+00:00"),
            faccenda(id = 4, titolo = "Porta fuori il cane", creataTs = "2026-09-29T08:00:00+00:00"),
        )
        assertEquals(
            listOf("rifai il letto", "Svuota la lavastoviglie", "Porta fuori il cane"),
            titoliRecenti(storia),
        )
        assertEquals(listOf("rifai il letto", "Porta fuori il cane"), titoliRecenti(storia, listOf("svuota la lavastoviglie")))
        assertEquals(listOf("rifai il letto"), titoliRecenti(storia, quanti = 1))
    }

    @Test
    fun `l'elenco diviso per stato`() {
        val gruppi = faccendeInGruppi(
            listOf(
                faccenda(id = 1, creataTs = "2026-10-02T09:00:00+00:00"),
                faccenda(id = 2, creataTs = "2026-10-01T09:00:00+00:00"),
                fatta("2026-10-01T10:00:00+00:00").copy(id = 3),
                fatta("2026-10-02T10:00:00+00:00").copy(id = 4),
                faccenda(id = 5, stato = StatiFaccenda.ANNULLATA, chiusaTs = "2026-10-01T10:00:00+00:00"),
                faccenda(id = 6, stato = "stato_del_futuro"),
            ),
        )
        assertEquals(listOf(2L, 1L), gruppi.daFare.map { it.id })
        assertEquals(listOf(4L, 3L), gruppi.fatte.map { it.id })
        assertEquals(listOf(5L), gruppi.annullate.map { it.id })
    }

    @Test
    fun `dopo una risposta persa, le faccende nuove coi titoli mandati`() {
        val dopo = listOf(
            faccenda(id = 1),
            faccenda(id = 8, titolo = "Rifai il letto"),
            faccenda(id = 9, titolo = "Altro"),
        )
        assertEquals(listOf(8L), faccendeCreate(listOf(1L), dopo, listOf(" Rifai il letto ")).map { it.id })
        assertTrue(faccendeCreate(listOf(1L, 8L, 9L), dopo, listOf("Rifai il letto")).isEmpty())
    }

    // --- i rifiuti -------------------------------------------------------------------------

    @Test
    fun `ogni rifiuto delle faccende col suo motivo`() {
        assertEquals(
            "Luca ha già 20 lavori di casa da fare: non se ne possono dare altri finché non ne fa qualcuno, o finché non ne annulli.",
            messaggioRifiutoFaccende(p, CodiciErrore.TROPPE_FACCENDE, GestoFaccende.DAI, "Luca"),
        )
        assertEquals(
            "Non si può bocciare: sono passate 24 ore dalla foto, oppure qualcuno l'ha già bocciato o annullato. Ho riletto l'elenco.",
            messaggioRifiutoFaccende(p, CodiciErrore.NON_BOCCIABILE, GestoFaccende.BOCCIA, "Luca"),
        )
        assertEquals(
            "Non si può annullare: non è più da fare (la foto è arrivata, o qualcuno l'ha già annullato). Ho riletto l'elenco.",
            messaggioRifiutoFaccende(p, CodiciErrore.NON_ANNULLABILE, GestoFaccende.ANNULLA, "Luca"),
        )
        assertEquals(
            "Non trovo più questo lavoro: ho riletto l'elenco.",
            messaggioRifiutoFaccende(p, CodiciErrore.NON_TROVATO, GestoFaccende.BOCCIA, "Luca"),
        )
        assertEquals(
            "Non trovo più questo figlio: ho riletto la famiglia.",
            messaggioRifiutoFaccende(p, CodiciErrore.NON_TROVATO, GestoFaccende.DAI, "Luca"),
        )
        assertEquals(
            "Per i lavori di casa serve aggiornare il server di Pactum.",
            messaggioRifiutoFaccende(p, CodiciErrore.SERVER_DA_AGGIORNARE, GestoFaccende.DAI, "Luca"),
        )
        assertEquals(
            "Non riesco a raggiungere il server: riprova.",
            messaggioRifiutoFaccende(p, null, GestoFaccende.ANNULLA, "Luca"),
        )
        assertEquals(
            "Il server non ha accettato la richiesta: aggiorna e riprova.",
            messaggioRifiutoFaccende(p, "codice_del_futuro", GestoFaccende.DAI, "Luca"),
        )
        assertEquals(
            "Qualcosa non va: ogni lavoro da 1 a 80 caratteri, la nota fino a 300. Controlla e riprova.",
            messaggioRifiutoFaccende(p, PostinoClient.PARAMETRI_NON_VALIDI, GestoFaccende.DAI, "Luca"),
        )
        assertEquals("Lavoro di casa dato.", testoFaccendeDate(p, 1))
        assertEquals("Lavori di casa dati: 3.", testoFaccendeDate(p, 3))
    }

    @Test
    fun `i rifiuti sui genitori`() {
        assertEquals(
            "Non puoi togliere te stesso: lo può fare un altro genitore dal suo telefono.",
            messaggioRifiutoFamiglia(p, CodiciErrore.NON_TE_STESSO, null, suGenitore = true),
        )
        assertEquals(
            "È l'unico genitore rimasto: non si può togliere.",
            messaggioRifiutoFamiglia(p, CodiciErrore.ULTIMO_GENITORE, null, suGenitore = true),
        )
        assertEquals(
            "Ci sono già 10 genitori: per aggiungerne un altro, prima togline uno.",
            messaggioRifiutoFamiglia(p, CodiciErrore.TROPPI_GENITORI, null, suGenitore = true),
        )
        assertEquals(
            "Non trovo più questo genitore: ho riletto la famiglia.",
            messaggioRifiutoFamiglia(p, CodiciErrore.NON_TROVATO, null, suGenitore = true),
        )
        assertEquals(
            "Per avere più genitori serve aggiornare il server di Pactum.",
            messaggioRifiutoFamiglia(p, CodiciErrore.SERVER_DA_AGGIORNARE, null, suGenitore = true),
        )
        assertEquals(
            "Questo genitore è stato tolto e non riceve più codici. Per collegare di nuovo il suo telefono, aggiungilo come genitore nuovo.",
            messaggioRifiutoFamiglia(p, CodiciErrore.GENITORE_REVOCATO, null, suGenitore = true),
        )
    }

    @Test
    fun `un 401 non è la rete, né per la famiglia né per le faccende`() {
        val frase = "Il collegamento di questo telefono non vale più: forse un altro genitore l'ha tolto. " +
            "Per collegarlo di nuovo serve un codice nuovo (Impostazioni, Connessione)."
        assertEquals(frase, messaggioRifiutoFamiglia(p, CodiciErrore.COLLEGAMENTO_NON_VALIDO, null))
        assertEquals(frase, messaggioRifiutoFaccende(p, CodiciErrore.COLLEGAMENTO_NON_VALIDO, GestoFaccende.BOCCIA, "Luca"))
    }

    // --- collegare un telefono già collegato -----------------------------------------------

    @Test
    fun `un telefono già collegato chiede prima di diventare un altro genitore`() {
        assertEquals(
            "Questo telefono è «Papà»: vuoi collegarlo come un altro genitore? Smetterà di essere «Papà». " +
                "Per tornare «Papà» servirà un codice nuovo.",
            domandaPrimaDiCollegare(p, configurato = true, io = papa),
        )
        assertEquals(
            "Questo telefono è già collegato al patto: vuoi collegarlo come un altro genitore? " +
                "Il collegamento di adesso smetterà di valere su questo telefono.",
            domandaPrimaDiCollegare(p, configurato = true, io = null),
        )
        assertNull(domandaPrimaDiCollegare(p, configurato = false, io = papa))
    }

    @Test
    fun `un collegamento diverso fa dimenticare, la prima lettura no`() {
        val uno = eu.stgm.pactum.genitore.dati.ConfigurazionePostino("https://pactum.esempio.ts.net", "a")
        val due = uno.copy(token = "b")
        assertFalse(configurazioneCambiata(null, uno))
        assertFalse(configurazioneCambiata(uno, uno))
        assertTrue(configurazioneCambiata(uno, due))
        assertTrue(configurazioneCambiata(uno, uno.copy(serverUrl = "https://altro.esempio.ts.net")))
    }

    // --- la risposta persa di "Dai faccende" -----------------------------------------------

    private val inizioInvio: Instant = Instant.parse("2026-10-02T13:10:00.700Z")

    private fun nuova(id: Long, titolo: String, da: RiferimentoGenitore?, creataTs: String) =
        faccenda(id = id, titolo = titolo, creataDa = da, creataTs = creataTs)

    @Test
    fun `con l'elenco mai letto non si sa quali sono nuove, incerto`() {
        val dopo = listOf(nuova(9, "Rifai il letto", mamma, "2026-10-02T13:10:01+00:00"))
        assertEquals(DopoRispostaPersa.Incerto, esitoDopoRispostaPersa(null, dopo, listOf("Rifai il letto"), mamma, inizioInvio))
        assertEquals(DopoRispostaPersa.Incerto, esitoDopoRispostaPersa(listOf(1L), null, listOf("Rifai il letto"), mamma, inizioInvio))
    }

    @Test
    fun `contano solo le tue, create dopo l'inizio dell'invio`() {
        val prima = listOf(1L)
        // La tua, arrivata al server nello stesso secondo dell'invio (il server scrive i secondi interi).
        assertEquals(
            DopoRispostaPersa.Date(1),
            esitoDopoRispostaPersa(prima, listOf(nuova(9, "Rifai il letto", mamma, "2026-10-02T13:10:00+00:00")), listOf("Rifai il letto"), mamma, inizioInvio),
        )
        // Lo stesso titolo dato da un altro genitore: non si può dire che sia la tua.
        assertEquals(
            DopoRispostaPersa.Incerto,
            esitoDopoRispostaPersa(prima, listOf(nuova(9, "Rifai il letto", papa, "2026-10-02T13:10:01+00:00")), listOf("Rifai il letto"), mamma, inizioInvio),
        )
        // Una tua di prima dell'invio (l'elenco che si aveva era vecchio): nemmeno.
        assertEquals(
            DopoRispostaPersa.Incerto,
            esitoDopoRispostaPersa(prima, listOf(nuova(9, "Rifai il letto", mamma, "2026-10-02T13:00:00+00:00")), listOf("Rifai il letto"), mamma, inizioInvio),
        )
        // Chi sei tu non si sa: incerto, mai "date".
        assertEquals(
            DopoRispostaPersa.Incerto,
            esitoDopoRispostaPersa(prima, listOf(nuova(9, "Rifai il letto", mamma, "2026-10-02T13:10:01+00:00")), listOf("Rifai il letto"), null, inizioInvio),
        )
        // Niente di nuovo coi titoli mandati: riprovare è sicuro.
        assertEquals(
            DopoRispostaPersa.NonDate,
            esitoDopoRispostaPersa(prima, listOf(faccenda(id = 1)), listOf("Rifai il letto"), mamma, inizioInvio),
        )
    }

    @Test
    fun `il collegamento col codice di 6 cifre, caso per caso`() {
        assertNull(messaggioAbbinamento(p, EsitoAbbinamento.Collegato("t", mamma)))
        assertEquals("Collegato: sei «Mamma».", testoCollegato(p, mamma))
        assertEquals("Collegato.", testoCollegato(p, null))
        assertEquals(
            "Codice non valido: forse è sbagliato, è scaduto (vale 15 minuti) o è già stato usato. Chiedine uno nuovo.",
            messaggioAbbinamento(p, EsitoAbbinamento.CodiceNonValido),
        )
        assertEquals(
            "Questo codice è per un telefono del figlio, non per l'app del genitore: chiedi il codice giusto.",
            messaggioAbbinamento(p, EsitoAbbinamento.TipoNonCorrispondente("telefono")),
        )
        assertEquals(
            "Questo codice è per un computer del figlio, non per l'app del genitore: chiedi il codice giusto.",
            messaggioAbbinamento(p, EsitoAbbinamento.TipoNonCorrispondente("computer")),
        )
        assertEquals(
            "Questo codice non è per l'app del genitore: chiedi il codice giusto.",
            messaggioAbbinamento(p, EsitoAbbinamento.TipoNonCorrispondente(null)),
        )
        assertEquals("Troppi tentativi: riprova tra 2 min.", messaggioAbbinamento(p, EsitoAbbinamento.TroppiTentativi(90)))
        assertEquals(
            "Il server di Pactum non conosce ancora i codici per i genitori: serve aggiornarlo. Intanto puoi usare il codice d'accesso lungo.",
            messaggioAbbinamento(p, EsitoAbbinamento.ServerDaAggiornare),
        )
    }

    // --- le notifiche delle faccende ---------------------------------------------------------

    private fun notifica(tipo: String, payload: JsonObject, messaggio: String = "messaggio del server") = Notifica(
        id = 40,
        tipo = tipo,
        messaggio = messaggio,
        payload = payload,
        tsServer = "2026-10-02T13:00:00+00:00",
        figlioId = 1,
    )

    private val figli = listOf(Figlio(id = 1, nome = "Luca"), Figlio(id = 2, nome = "Sara"))

    @Test
    fun `faccenda fatta, col nome del figlio e il titolo`() {
        val n = notifica(
            "faccenda_fatta",
            buildJsonObject {
                put("faccenda_id", 5)
                put("titolo", "Svuota la lavastoviglie")
            },
        )
        assertEquals(
            TestoNotifica("Lavoro fatto", "Luca ha fatto «Svuota la lavastoviglie»: tocca per vedere la foto."),
            testoNotifica(p, n, emptyMap(), figli),
        )
        assertEquals(
            TestoNotifica("Lavoro fatto", "Tuo figlio ha fatto «Svuota la lavastoviglie»: tocca per vedere la foto."),
            testoNotifica(p, n, emptyMap()),
        )
        assertEquals(5L, faccendaDellaNotifica(n))
    }

    @Test
    fun `nella lista in app niente tocca per vedere, c'è il pulsante`() {
        val fatta = notifica(
            "faccenda_fatta",
            buildJsonObject {
                put("faccenda_id", 5)
                put("titolo", "Svuota la lavastoviglie")
            },
        )
        assertEquals(
            TestoNotifica("Lavoro fatto", "Luca ha fatto «Svuota la lavastoviglie»."),
            testoNotifica(p, fatta, emptyMap(), figli, nellaTendina = false),
        )
        assertEquals(
            TestoNotifica("Lavoro fatto", "Tuo figlio ha fatto «Svuota la lavastoviglie»."),
            testoNotifica(p, fatta, emptyMap(), nellaTendina = false),
        )
        val finite = notifica(
            "faccende_finite",
            buildJsonObject { putJsonArray("faccenda_ids") { add(5) } },
            messaggio = "Luca ha finito i lavori di casa: telefono e computer sbloccati",
        )
        assertEquals(
            TestoNotifica("Lavori di casa finiti", "Luca ha finito i lavori di casa: telefono e computer sbloccati"),
            testoNotifica(p, finite, emptyMap(), figli, nellaTendina = false),
        )
    }

    @Test
    fun `faccenda fatta senza titolo, il messaggio del server, col titolo del tipo`() {
        val n = notifica("faccenda_fatta", buildJsonObject { put("faccenda_id", 5) })
        assertEquals(TestoNotifica("Lavoro fatto", "messaggio del server"), testoNotifica(p, n, emptyMap(), figli))
    }

    @Test
    fun `faccende finite, la frase del server e dove si vedono le foto`() {
        val n = notifica(
            "faccende_finite",
            buildJsonObject { putJsonArray("faccenda_ids") { add(5); add(6) } },
            messaggio = "Luca ha finito i lavori di casa: telefono e computer sbloccati",
        )
        assertEquals(
            TestoNotifica("Lavori di casa finiti", "Luca ha finito i lavori di casa: telefono e computer sbloccati\nTocca per vedere le foto."),
            testoNotifica(p, n, emptyMap(), figli),
        )
        assertNull(faccendaDellaNotifica(n))
        assertEquals(2, quanteFaccendeFinite(n))
    }

    @Test
    fun `un tipo che non si conosce non rompe niente`() {
        val n = notifica("faccenda_del_futuro", buildJsonObject { putJsonObject("x") { put("y", 1) } })
        assertEquals(TestoNotifica("Novità dal patto", "messaggio del server"), testoNotifica(p, n, emptyMap(), figli))
        assertFalse(notificaDiFaccende(n.tipo))
        assertEquals(MainActivity.DEST_NOTIFICHE, Vedetta.destinazionePerTipo(n.tipo))
    }

    @Test
    fun `toccare una notifica delle faccende apre le faccende, e la foto`() {
        assertEquals(MainActivity.DEST_FACCENDE, Vedetta.destinazionePerTipo("faccenda_fatta"))
        assertEquals(MainActivity.DEST_FACCENDE, Vedetta.destinazionePerTipo("faccende_finite"))
        // Due faccende fatte dello stesso figlio non si rubano il tocco.
        assertNotEquals(
            Vedetta.requestCodeAvviso(MainActivity.DEST_FACCENDE, 1, 5),
            Vedetta.requestCodeAvviso(MainActivity.DEST_FACCENDE, 1, 6),
        )
        // Senza faccenda, lo stesso requestCode della 0.12.
        assertEquals("finestra|1".hashCode(), Vedetta.requestCodeAvviso("finestra", 1, null))
    }

    @Test
    fun `il programma chiuso durante il blocco, e i permessi tolti`() {
        val chiuso = notifica("manomissione", buildJsonObject { putJsonObject("dettagli") { put("sotto_tipo", "chiuso_durante_blocco") } })
        assertEquals(
            TestoNotifica("Anomalia", "Pactum è stato chiuso sul computer durante il blocco dei lavori di casa"),
            testoNotifica(p, chiuso, emptyMap()),
        )
        fun permesso(valore: String?) = testoNotifica(
            p,
            notifica(
                "manomissione",
                buildJsonObject {
                    putJsonObject("dettagli") {
                        put("sotto_tipo", "permesso_revocato")
                        if (valore != null) put("permesso", valore)
                    }
                },
            ),
            emptyMap(),
        ).testo
        assertEquals("Accesso ai dati di utilizzo revocato", permesso(null))
        assertEquals("Accesso ai dati di utilizzo revocato", permesso("accesso_utilizzo"))
        assertEquals("Tolto a Pactum il permesso «Mostra sopra le altre app» sul telefono del figlio", permesso("overlay"))
        assertEquals("Tolto un permesso di Pactum sul telefono del figlio", permesso("fotocamera"))
    }
}
