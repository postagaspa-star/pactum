package eu.stgm.pactum.figlio.studio

import eu.stgm.pactum.figlio.faccende.BloccoDalServer
import eu.stgm.pactum.figlio.faccende.Istante
import eu.stgm.pactum.figlio.faccende.MemoriaBlocco
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * (0.18, contratto v4.0, parte E) Altri casi limite dello Studio sul
 * telefono: l'orologio del telefono spostato, il telefono spento alle 15:00,
 * giorni interi senza rete, la chiusura senza rete di uno Studio a mano poi
 * «adottato» dal server, e il conto del server che vale su quello locale.
 */
class StudioCasiLimiteTest {

    private val roma: ZoneId = ZoneId.of("Europe/Rome")
    private val min = 60_000L
    private val ora = 60 * min

    private fun t(giorno: String, ore: Int, minuti: Int = 0): Long =
        ZonedDateTime.of(LocalDate.parse(giorno), LocalTime.of(ore, minuti), roma).toInstant().toEpochMilli()

    private val oggi = "2026-10-08" // giovedì
    private val domani = "2026-10-09" // venerdì
    private val p15 = t(oggi, 15)
    private val p16 = t(oggi, 16)

    /** Il telefono acceso (accensione 1): monotono e muro vanno insieme. */
    private fun istante(muro: Long, avvio: Int? = 1) = Istante(muro, muro - p15 + 5 * ora, avvio)

    private val partenzaOggi = PartenzaStudio(oggi, p15, p16, 60)
    private val partenzaDomani = PartenzaStudio(domani, t(domani, 15), t(domani, 16), 60)
    private val approvata = ContenutoStudio(app = listOf("com.google.android.apps.docs"), orariDal = "2026-10-01")
    private val config = ConfigStudio(StatiConfigStudio.APPROVATA, 4, approvata)

    private fun dalServer(quando: Long, inCorso: StudioSvolto? = null, prossime: List<PartenzaStudio> = listOf(partenzaOggi, partenzaDomani)) =
        MemoriaStudio(fuso = "Europe/Rome").conServer(StatoStudioServer(config, inCorso, prossime, null), istante(quando), quando)

    private fun studioServer(id: Long = 41, inizio: Long = p15, minuti: Int = 0, chiave: String? = null) = StudioSvolto(
        id = id, origine = if (chiave == null) OriginiStudio.AUTOMATICA else OriginiStudio.MANUALE, giorno = oggi, chiave = chiave,
        inizio = inizio, partenze = listOf(partenzaOggi), contaDal = p15, chiudibileDal = p16, minutiMinimi = 60,
        minutiAttivita = minuti, inCorso = true,
    )

    // --- L'orologio del telefono spostato --------------------------------------------------

    @Test
    fun `orologio del telefono spostato avanti - lo Studio parte all'ora del server, non a quella del telefono`() {
        // Alle 14:00 il server ha risposto: l'ora del server è agganciata all'orologio che non si sposta.
        val alle14 = t(oggi, 14)
        val blocco = MemoriaBlocco().conServer(BloccoDalServer(false, null, null, emptyList()), istante(alle14), istante(alle14), alle14)
        val studio = dalServer(alle14)
        // Mezz'ora dopo il figlio sposta l'orologio del telefono avanti di tre ore (le 17:30 «finte»).
        val adesso = Istante(muro = alle14 + 3 * ora + 30 * min, monotono = istante(alle14).monotono + 30 * min, avvio = 1)
        val o = OraServer.di(blocco, adesso)
        assertTrue(o.agganciata)
        assertEquals(alle14 + 30 * min, o.server)
        assertNull("per il server sono le 14:30: lo Studio non è ancora partito", studio.attivo(o.server))
        // E all'ora vera delle 15:00 parte, anche se l'orologio del telefono dice le 18:00.
        val alle15 = Istante(muro = alle14 + 4 * ora, monotono = istante(alle14).monotono + ora, avvio = 1)
        assertEquals(p15, studio.attivo(OraServer.di(blocco, alle15).server)?.inizio)
    }

    @Test
    fun `orologio spostato indietro - lo Studio in corso non si chiude, e Chiudi lo Studio non anticipa`() {
        val alle16 = p16 + 5 * min
        val blocco = MemoriaBlocco().conServer(BloccoDalServer(false, null, null, emptyList()), istante(alle16), istante(alle16), alle16)
        val studio = dalServer(alle16, inCorso = studioServer(minuti = 10))
        // Il figlio porta l'orologio alle 23:59 del giorno prima: l'ora del server non si muove.
        val adesso = Istante(muro = t("2026-10-07", 23, 59), monotono = istante(alle16).monotono + min, avvio = 1)
        val o = OraServer.di(blocco, adesso)
        assertEquals(alle16 + min, o.server)
        assertNotNull(studio.attivo(o.server))
        assertFalse(studio.chiudibile(studio.attivo(o.server)!!, adesso, o.server))
    }

    @Test
    fun `dopo un riavvio senza rete l'ora non è agganciata - e per chiudere serve la rete`() {
        val alle16 = p16 + 5 * min
        val blocco = MemoriaBlocco().conServer(BloccoDalServer(false, null, null, emptyList()), istante(alle16), istante(alle16), alle16)
        // Riavvio: un'altra accensione, l'orologio che non si sposta riparte da poco.
        val dopo = OraServer.di(blocco, Istante(muro = alle16 + 10 * min, monotono = 30_000L, avvio = 2))
        assertFalse(dopo.agganciata)
        // Vale l'orologio del telefono (più lo scarto misurato): lo Studio continua lo stesso.
        assertNotNull(dalServer(alle16, inCorso = studioServer()).attivo(dopo.server))
    }

    // --- Telefono spento alle 15:00, ore senza rete ----------------------------------------------

    @Test
    fun `telefono spento alle 15 e riacceso senza rete - è in Studio da quell'istante`() {
        // L'ultima risposta alle 13:00; spento dalle 14:50 alle 15:40, nessuna rete al rientro.
        val m = dalServer(t(oggi, 13))
        val riacceso = m.attivo(t(oggi, 15, 40))
        assertNotNull(riacceso)
        assertEquals("lo Studio va dalle 15:00, non da quando si riaccende", p15, riacceso!!.inizio)
        assertEquals(p15, riacceso.contaDal)
        assertEquals(RifStudio(giorno = oggi), riacceso.rif)
    }

    @Test
    fun `senza rete per due giorni - lo Studio di ieri si chiude a mezzanotte, quello di oggi parte alle 15`() {
        val m = dalServer(t(oggi, 13))
        assertEquals(p15, m.attivo(t(oggi, 23, 59))?.inizio)
        assertNull("il mattino dopo non è in Studio", m.attivo(t(domani, 9)))
        val venerdi = m.attivo(t(domani, 15, 1))
        assertEquals(t(domani, 15), venerdi?.inizio)
        assertEquals(RifStudio(giorno = domani), venerdi?.rif)
    }

    @Test
    fun `senza rete - una chiusura fatta ieri non chiude lo Studio di oggi`() {
        var m = dalServer(t(oggi, 13))
            .conTrattoIniziato("t1", TipiTratto.COMPITI, null, null, istante(p15 + min), p15 + min, true)
            .conTrattoFermato(istante(p15 + 62 * min), p15 + 62 * min, true)
        m = m.conChiusura("c1", "Esercizi di inglese e un riassunto", istante(p16 + 5 * min), p16 + 5 * min, true)!!
        assertNull(m.attivo(p16 + 6 * min))
        assertNotNull(m.attivo(t(domani, 15, 1)))
    }

    // --- Lo Studio a mano chiuso senza rete, poi adottato dal server -----------------------------

    @Test
    fun `avvio a mano adottato dal server - la chiusura fatta senza rete va con l'id dello Studio`() {
        // Alle 14:00 lo Studio a mano parte senza rete; alle 15:10, ancora senza rete, si chiude (minimo 10 per comodità).
        val corto = config.copy(approvata = approvata.copy(minutiMinimi = 10))
        var m = MemoriaStudio(fuso = "Europe/Rome")
            .conServer(StatoStudioServer(corto, null, emptyList(), null), istante(t(oggi, 13)), t(oggi, 13))
            .conAvvioManuale("mia-chiave", t(oggi, 14))
            .conTrattoIniziato("t1", TipiTratto.COMPITI, null, null, istante(t(oggi, 14)), t(oggi, 14), true)
            .conTrattoFermato(istante(t(oggi, 14, 20)), t(oggi, 14, 20), true)
        m = m.conChiusura("c1", "Ripasso di storia, capitolo due", istante(t(oggi, 14, 25)), t(oggi, 14, 25), true)!!
        assertEquals(RifStudio(giorno = oggi, chiave = "mia-chiave"), m.chiusureDaConsegnare.single().rif)
        // Il server dice: a quell'ora c'era già uno Studio (avviato da un altro telefono), e lo adotta.
        val altro = studioServer(id = 77, inizio = t(oggi, 13, 30), chiave = "chiave-altro-telefono")
        m = m.conAvvioConsegnato("mia-chiave", altro)
        val c = m.chiusureDaConsegnare.single()
        assertEquals(77L, c.rif.id)
        // Il corpo va con l'id nel percorso: niente `studio` con una chiave che il server non conosce.
        val corpo = CorpiStudio.chiudi(c, emptyList(), istante(t(oggi, 14, 30)))
        assertFalse(corpo.contains("\"studio\""))
        // E lo Studio adottato resta chiuso sul telefono finché il server non dice la sua.
        assertNull(m.attivo(t(oggi, 14, 30)))
    }

    @Test
    fun `avvio a mano consegnato con la sua chiave - la chiusura prende l'id e resta in coda`() {
        val corto = config.copy(approvata = approvata.copy(minutiMinimi = 10))
        var m = MemoriaStudio(fuso = "Europe/Rome")
            .conServer(StatoStudioServer(corto, null, emptyList(), null), istante(t(oggi, 13)), t(oggi, 13))
            .conAvvioManuale("k1", t(oggi, 14))
            .conTrattoIniziato("t1", TipiTratto.ALTRO, "corsa", null, istante(t(oggi, 14)), t(oggi, 14), true)
            .conTrattoFermato(istante(t(oggi, 14, 15)), t(oggi, 14, 15), true)
        m = m.conChiusura("c1", "Sono andato a correre al parco", istante(t(oggi, 14, 16)), t(oggi, 14, 16), true)!!
        m = m.conAvvioConsegnato("k1", studioServer(id = 90, inizio = t(oggi, 14), chiave = "k1"))
        assertEquals(RifStudio(id = 90, giorno = oggi, chiave = "k1"), m.chiusureDaConsegnare.single().rif)
        assertNull(m.avvioManuale)
    }

    // --- Il conto del server vale -----------------------------------------------------------------

    @Test
    fun `un tratto che il server ha ricevuto e non conta non torna a contare qui`() {
        var m = dalServer(p15 + min, inCorso = studioServer())
            .conTrattoIniziato("t1", TipiTratto.COMPITI, null, null, istante(p15 + min), p15 + min, true)
            .conTrattoFermato(istante(p16 + 5 * min), p16 + 5 * min, true)
        m = m.conTrattiConsegnati(m.trattiDaMandare(), p16 + 6 * min)
        // Per il server quel tratto non conta (ore fuori dalle tutele): 0 minuti.
        m = m.conServer(StatoStudioServer(config, studioServer(minuti = 0), listOf(partenzaOggi), null), istante(p16 + 7 * min), p16 + 7 * min)
        val s = m.attivo(p16 + 8 * min)!!
        assertEquals(0, m.minutiStimati(s, istante(p16 + 8 * min), p16 + 8 * min))
        assertFalse("«Chiudi lo Studio» segue i minuti del server", m.chiudibile(s, istante(p16 + 8 * min), p16 + 8 * min))
    }

    @Test
    fun `il tratto che gira adesso si somma ai minuti del server`() {
        var m = dalServer(p16, inCorso = studioServer(minuti = 50))
        m = m.conTrattoIniziato("t1", TipiTratto.LAVORI_DI_CASA, null, 5, istante(p16 + min), p16 + min, true)
        val s = m.attivo(p16 + 11 * min)!!
        assertEquals(60, m.minutiStimati(s, istante(p16 + 11 * min), p16 + 11 * min))
        assertTrue(m.chiudibile(s, istante(p16 + 11 * min), p16 + 11 * min))
    }

    // --- Lo Studio e il blocco dei lavori ------------------------------------------------------------

    @Test
    fun `avvio a mano col blocco che parte proprio adesso - il telefono rifiuta anche senza rete`() {
        val m = dalServer(t(oggi, 13))
        assertEquals(MemoriaStudio.NoAvvio.BLOCCO_FACCENDE, m.noAvvio(t(oggi, 14), bloccoAllInizio = true))
        assertNull(m.noAvvio(t(oggi, 14), bloccoAllInizio = false))
    }

    @Test
    fun `nessuna configurazione approvata - lo Studio non parte, né da solo né a mano`() {
        val m = MemoriaStudio(fuso = "Europe/Rome")
            .conServer(StatoStudioServer(ConfigStudio(StatiConfigStudio.IN_ATTESA, 1), null, emptyList(), null), istante(t(oggi, 13)), t(oggi, 13))
        assertNull(m.attivo(p15 + min))
        assertEquals(MemoriaStudio.NoAvvio.NON_APPROVATO, m.noAvvio(t(oggi, 14), bloccoAllInizio = false))
    }
}
