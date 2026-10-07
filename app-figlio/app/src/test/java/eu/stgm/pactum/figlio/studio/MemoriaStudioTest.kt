package eu.stgm.pactum.figlio.studio

import eu.stgm.pactum.figlio.faccende.Istante
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * (0.18, contratto v4.0, parte C e parte E) Lo Studio come lo vede il
 * telefono: in corso quando lo dice il server, quando passa una partenza
 * dopo l'ultima risposta (anche senza rete), o con uno Studio a mano; finisce
 * col server, con una chiusura fatta qui, a mezzanotte o con un 401.
 */
class MemoriaStudioTest {

    private val roma: ZoneId = ZoneId.of("Europe/Rome")
    private val min = 60_000L
    private val ora = 60 * min

    /** Un istante nel fuso del patto. */
    private fun t(giorno: String, ore: Int, minuti: Int = 0): Long =
        ZonedDateTime.of(LocalDate.parse(giorno), java.time.LocalTime.of(ore, minuti), roma).toInstant().toEpochMilli()

    private val oggi = "2026-10-08" // giovedì
    private val p15 = t(oggi, 15)
    private val p16 = t(oggi, 16)

    /** Il telefono acceso: monotono e muro vanno insieme (accensione 1). */
    private fun istante(muro: Long, avvio: Int? = 1) = Istante(muro, muro - p15 + 5 * ora, avvio)

    private val partenzaOggi = PartenzaStudio(oggi, p15, p16, 60)
    private val partenzaDomani = PartenzaStudio("2026-10-09", t("2026-10-09", 15), t("2026-10-09", 16), 60)

    private val approvata = ContenutoStudio(app = listOf("com.spaggiari.classevivastudenti", "com.google.android.apps.docs"), orariDal = "2026-10-01")
    private val config = ConfigStudio(StatiConfigStudio.APPROVATA, 4, approvata)

    private fun dalServer(
        quando: Long,
        inCorso: StudioSvolto? = null,
        prossime: List<PartenzaStudio> = listOf(partenzaOggi, partenzaDomani),
        m: MemoriaStudio = MemoriaStudio(fuso = "Europe/Rome"),
    ) = m.conServer(StatoStudioServer(config, inCorso, prossime, null), istante(quando), quando)

    private fun studioServer(inizio: Long = p15, minuti: Int = 0, chiudibile: Boolean = false, app: List<String>? = null) = StudioSvolto(
        id = 41, origine = OriginiStudio.AUTOMATICA, giorno = oggi, inizio = inizio,
        partenze = listOf(partenzaOggi), contaDal = inizio, chiudibileDal = p16, minutiMinimi = 60,
        minutiAttivita = minuti, chiudibile = chiudibile, inCorso = true, app = app,
    )

    // --- In corso ----------------------------------------------------------------------

    @Test
    fun `prima della partenza niente Studio - e la prossima partenza si sa`() {
        val m = dalServer(p15 - ora)
        assertNull(m.attivo(p15 - min))
        assertEquals(p15, m.prossimaPartenza(p15 - min)?.inizio)
    }

    @Test
    fun `senza rete - alla partenza lo Studio parte da solo, con le condizioni di quel giorno`() {
        val m = dalServer(p15 - ora)
        val s = m.attivo(p15 + min)!!
        assertEquals(p15, s.inizio)
        assertEquals(p16, s.chiudibileDal)
        assertEquals(60, s.minutiMinimi)
        assertEquals(RifStudio(giorno = oggi), s.rif)
        assertFalse(s.dalServer)
        assertEquals(approvata.app, s.app)
    }

    @Test
    fun `lo Studio del server - in corso finché lo dice`() {
        val m = dalServer(p15 + 5 * min, inCorso = studioServer(minuti = 3))
        val s = m.attivo(p15 + 6 * min)!!
        assertTrue(s.dalServer)
        assertEquals(41L, s.rif.id)
        // Il server dice: niente in corso (chiuso dal genitore). Lo Studio finisce.
        val chiuso = m.conServer(StatoStudioServer(config, null, listOf(partenzaDomani), null), istante(p15 + 20 * min), p15 + 20 * min)
        assertNull(chiuso.attivo(p15 + 21 * min))
    }

    @Test
    fun `una partenza passata PRIMA dell'ultima risposta non fa partire niente - decide il server`() {
        // Il server ha risposto alle 15:20 senza Studio in corso (era già stato chiuso).
        val m = dalServer(p15 + 20 * min, inCorso = null)
        assertNull(m.attivo(p15 + 21 * min))
    }

    @Test
    fun `una risposta vecchia non cambia niente`() {
        val m = dalServer(p15 + 5 * min, inCorso = studioServer())
        // Una lettura partita prima (alle 14:00) e arrivata dopo.
        val vecchia = m.conServer(StatoStudioServer(config, null, emptyList(), null), istante(p15 - ora), p15 - ora)
        assertEquals(m, vecchia)
    }

    @Test
    fun `mezzanotte - lo Studio aperto finisce da solo, il mattino dopo il telefono non è in Studio`() {
        val m = dalServer(p15 + 5 * min, inCorso = studioServer())
        val mezzanotte = t("2026-10-09", 0)
        assertNotNull(m.attivo(mezzanotte - 1))
        assertNull(m.attivo(mezzanotte))
        assertNull(m.attivo(t("2026-10-09", 9)))
        // E alle 15:00 del giorno dopo parte quello nuovo.
        assertEquals("2026-10-09", m.attivo(t("2026-10-09", 15, 1))?.giorno)
    }

    @Test
    fun `fuso - sempre quello del patto, non quello del telefono`() {
        // La partenza delle 15:00 di Roma, letta da un telefono a Londra, resta alle 15:00 di Roma.
        val m = dalServer(p15 - ora)
        assertNotNull(m.attivo(p15))
        assertNull(m.attivo(p15 - 3 * min))
        // La mezzanotte è quella di Roma.
        assertEquals(t("2026-10-09", 0), m.attivo(p15)!!.mezzanotte)
    }

    @Test
    fun `un patto senza studio (server vecchio) spegne lo Studio`() {
        val m = dalServer(p15 + 5 * min, inCorso = studioServer())
        val senza = m.senzaStudio(istante(p15 + 6 * min))
        assertNull(senza.attivo(p15 + 7 * min))
        assertFalse(senza.conosciuto)
    }

    @Test
    fun `un 401 spegne lo Studio e ferma il timer`() {
        val m = dalServer(p15 + 5 * min, inCorso = studioServer())
            .conTrattoIniziato("t1", TipiTratto.COMPITI, null, null, istante(p15 + 6 * min), p15 + 6 * min, true)
        val fuori = m.conScollegato(istante(p15 + 10 * min), p15 + 10 * min, true)
        assertNull(fuori.attivo(p15 + 10 * min))
        assertNull(fuori.trattoInCorso)
    }

    @Test
    fun `nessun telefono 0_18 - prossime vuote, niente partenze nemmeno coi conti oltre i 14 giorni`() {
        val m = dalServer(p15 - ora, prossime = emptyList())
        assertNull(m.attivo(p15 + min))
        assertNull(m.prossimaPartenza(p15 - min))
        assertNull(m.attivo(t("2026-11-20", 15, 1)))
    }

    @Test
    fun `oltre i 14 giorni senza rete - le partenze dalla configurazione, nel fuso del patto`() {
        val m = dalServer(p15 - ora, prossime = listOf(partenzaOggi, partenzaDomani))
        // Il 23/10 (venerdì) è oltre l'elenco: la calcola il telefono.
        val venerdi = t("2026-10-23", 15)
        assertEquals("2026-10-23", m.attivo(venerdi + min)?.giorno)
        // Sabato no (lun–ven).
        assertNull(m.attivo(t("2026-10-24", 15, 1)))
    }

    @Test
    fun `il server senza lo Studio - niente`() {
        assertNull(MemoriaStudio().attivo(p15 + min))
    }

    // --- Avvio a mano ----------------------------------------------------------------------

    @Test
    fun `avvio a mano - parte subito qui, e alle 15 assorbe la partenza con le sue condizioni`() {
        val m = dalServer(t(oggi, 13)).conAvvioManuale("chiave-1", t(oggi, 14))
        val s = m.attivo(t(oggi, 14, 10))!!
        assertEquals(OriginiStudio.MANUALE, s.origine)
        assertEquals(t(oggi, 14), s.inizio)
        assertNull("a mano, nessuna partenza: niente vincolo d'orario", s.chiudibileDal)
        assertEquals(t(oggi, 14), s.contaDal)
        val dopo = m.attivo(p15 + min)!!
        assertEquals("lo stesso Studio", t(oggi, 14), dopo.inizio)
        assertEquals(p15, dopo.contaDal)
        assertEquals(p16, dopo.chiudibileDal)
    }

    @Test
    fun `avvio a mano - i no del telefono, anche senza rete`() {
        val m = dalServer(t(oggi, 13))
        assertNull(m.noAvvio(t(oggi, 14), bloccoAllInizio = false))
        assertEquals(MemoriaStudio.NoAvvio.BLOCCO_FACCENDE, m.noAvvio(t(oggi, 14), bloccoAllInizio = true))
        // Alle 23:01 (lo Studio di oggi già chiuso per il server): da lì a mezzanotte meno di 60 minuti.
        assertEquals(MemoriaStudio.NoAvvio.TROPPO_TARDI, dalServer(t(oggi, 22)).noAvvio(t(oggi, 23, 1), bloccoAllInizio = false))
        assertEquals(MemoriaStudio.NoAvvio.GIA_IN_STUDIO, m.noAvvio(p15 + min, bloccoAllInizio = false))
        val senza = MemoriaStudio(conosciuto = true, config = ConfigStudio(StatiConfigStudio.IN_ATTESA, 1, null, ContenutoStudio()))
        assertEquals(MemoriaStudio.NoAvvio.NON_APPROVATO, senza.noAvvio(t(oggi, 14), false))
    }

    @Test
    fun `avvio a mano consegnato - da lì vale lo Studio del server`() {
        val m = dalServer(t(oggi, 13)).conAvvioManuale("chiave-1", t(oggi, 14))
        val server = studioServer(inizio = t(oggi, 14)).copy(id = 50, origine = OriginiStudio.MANUALE, chiave = "chiave-1", chiudibileDal = null, partenze = emptyList())
        val dopo = m.conAvvioConsegnato("chiave-1", server)
        assertNull(dopo.avvioManuale)
        assertEquals(50L, dopo.attivo(t(oggi, 14, 5))!!.rif.id)
    }

    @Test
    fun `avvio a mano rifiutato dal server - niente Studio, e il motivo`() {
        val m = dalServer(t(oggi, 13)).conAvvioManuale("chiave-1", t(oggi, 14))
        val dopo = m.conAvvioRifiutato("chiave-1", "blocco_faccende")
        assertNull(dopo.attivo(t(oggi, 14, 5)))
        assertEquals("blocco_faccende", dopo.avvioRifiutato)
    }

    @Test
    fun `avvio a mano dentro uno Studio già chiuso - il telefono chiude il suo`() {
        val m = dalServer(t(oggi, 13)).conAvvioManuale("chiave-1", t(oggi, 14))
        val chiuso = studioServer(inizio = t(oggi, 13)).copy(fine = t(oggi, 14, 30), chiusura = ChiusureStudio.GENITORE, inCorso = false)
        val dopo = m.conAvvioConsegnato("chiave-1", chiuso)
        assertNull(dopo.attivo(t(oggi, 14, 40)))
        assertEquals(41L, dopo.recenti.single().id)
    }

    // --- Il timer --------------------------------------------------------------------------

    @Test
    fun `il timer - tratti di durata libera e mescolabili, uno alla volta`() {
        var m = dalServer(p15 + min, inCorso = studioServer())
        m = m.conTrattoIniziato("t1", TipiTratto.LAVORI_DI_CASA, null, 5, istante(p15 + 2 * min), p15 + 2 * min, true)
        // Un altro tratto: quello di prima finisce.
        m = m.conTrattoIniziato("t2", TipiTratto.COMPITI, "matematica", null, istante(p15 + 12 * min), p15 + 12 * min, true)
        val primo = m.tratti.first { it.id == "t1" }
        assertEquals(EsitiTratto.FINITO, primo.esito)
        assertEquals(600L, primo.secondi)
        assertEquals(5L, primo.faccendaId)
        assertEquals("t2", m.trattoInCorso?.id)
        m = m.conTrattoFermato(istante(p15 + 42 * min), p15 + 42 * min, true)
        assertNull(m.trattoInCorso)
        // 10 + 30 minuti.
        assertEquals(40, m.minutiStimati(m.attivo(p15 + 43 * min)!!, istante(p15 + 43 * min), p15 + 43 * min))
    }

    @Test
    fun `altro vuole una parola - senza non parte`() {
        val m = dalServer(p15 + min, inCorso = studioServer())
        assertNull(m.conTrattoIniziato("t1", TipiTratto.ALTRO, null, null, istante(p15 + 2 * min), p15 + 2 * min, true).trattoInCorso)
        assertNull(m.conTrattoIniziato("t1", TipiTratto.ALTRO, "   ", null, istante(p15 + 2 * min), p15 + 2 * min, true).trattoInCorso)
        val ok = m.conTrattoIniziato("t1", TipiTratto.ALTRO, " allenamento ", null, istante(p15 + 2 * min), p15 + 2 * min, true)
        assertEquals("allenamento", ok.trattoInCorso?.parola)
    }

    @Test
    fun `fuori dallo Studio il timer non parte`() {
        val m = dalServer(p15 - ora)
        assertNull(m.conTrattoIniziato("t1", TipiTratto.COMPITI, null, null, istante(p15 - min), p15 - min, true).trattoInCorso)
    }

    @Test
    fun `la faccenda vale solo coi lavori di casa`() {
        val m = dalServer(p15 + min, inCorso = studioServer())
            .conTrattoIniziato("t1", TipiTratto.COMPITI, null, 5, istante(p15 + 2 * min), p15 + 2 * min, true)
        assertNull(m.trattoInCorso?.faccendaId)
    }

    @Test
    fun `riavvio durante un tratto - si chiude all'ultimo punto salvato, interrotto, e conta fino a lì`() {
        var m = dalServer(p15 + min, inCorso = studioServer())
            .conTrattoIniziato("t1", TipiTratto.COMPITI, null, null, istante(p15 + 2 * min), p15 + 2 * min, true)
        m = m.conPuntoSalvato(istante(p15 + 22 * min))
        // Il telefono si riavvia alle 15:30 (accensione 2: l'orologio che non si sposta riparte da zero).
        val dopoRiavvio = Istante(p15 + 30 * min, 40_000L, avvio = 2)
        m = m.normalizzata(dopoRiavvio, p15 + 30 * min, agganciata = false)
        val t1 = m.tratti.single()
        assertEquals(EsitiTratto.INTERROTTO, t1.esito)
        assertEquals(20 * 60L, t1.secondi)
        assertEquals(p15 + 22 * min, t1.fine)
        assertTrue("con l'aggancio dell'inizio", t1.oraAgganciata)
    }

    @Test
    fun `mezzanotte - il tratto in corso si chiude lì, interrotto`() {
        val sera = studioServer().copy(inizio = p15)
        var m = dalServer(t(oggi, 23, 30), inCorso = sera)
            .conTrattoIniziato("t1", TipiTratto.ALTRO, "allenamento", null, istante(t(oggi, 23, 40)), t(oggi, 23, 40), true)
        val mezzanotte = t("2026-10-09", 0)
        m = m.normalizzata(istante(mezzanotte + 5 * min), mezzanotte + 5 * min, true)
        val t1 = m.tratti.single()
        assertEquals(EsitiTratto.INTERROTTO, t1.esito)
        assertEquals(20 * 60L, t1.secondi)
        assertEquals(mezzanotte, t1.fine)
    }

    @Test
    fun `lo Studio chiuso dal genitore - il tratto in corso si ferma adesso`() {
        var m = dalServer(p15 + min, inCorso = studioServer())
            .conTrattoIniziato("t1", TipiTratto.COMPITI, null, null, istante(p15 + 2 * min), p15 + 2 * min, true)
        m = m.conServer(StatoStudioServer(config, null, listOf(partenzaDomani), null), istante(p15 + 20 * min), p15 + 20 * min)
            .normalizzata(istante(p15 + 20 * min), p15 + 20 * min, true)
        assertEquals(EsitiTratto.FINITO, m.tratti.single().esito)
        assertEquals(18 * 60L, m.tratti.single().secondi)
    }

    @Test
    fun `un tratto di meno di un secondo, mai mandato, non si tiene`() {
        val m = dalServer(p15 + min, inCorso = studioServer())
            .conTrattoIniziato("t1", TipiTratto.COMPITI, null, null, istante(p15 + 2 * min), p15 + 2 * min, true)
            .conTrattoFermato(istante(p15 + 2 * min + 500), p15 + 2 * min + 500, true)
        assertTrue(m.tratti.isEmpty())
    }

    @Test
    fun `il corpo di un tratto - in corso con inizio e fine null, finito con fine`() {
        val m = dalServer(p15 + min, inCorso = studioServer())
            .conTrattoIniziato("t1", TipiTratto.LAVORI_DI_CASA, null, 5, istante(p15 + 2 * min), p15 + 2 * min, true)
        val inCorso = m.trattoInCorso!!.corpo(istante(p15 + 2 * min)).toString()
        assertTrue(inCorso, "\"fine\":null" in inCorso)
        assertTrue(inCorso, "\"inizio\":${p15 + 2 * min}" in inCorso)
        assertTrue(inCorso, "\"secondi\":1" in inCorso)
        assertTrue(inCorso, "\"faccenda_id\":5" in inCorso)
        assertTrue(inCorso, "\"esito\":\"in_corso\"" in inCorso)
        val finito = m.conTrattoFermato(istante(p15 + 12 * min), p15 + 12 * min, true).tratti.single().corpo(istante(p15 + 12 * min)).toString()
        assertTrue(finito, "\"fine\":${p15 + 12 * min}" in finito)
        assertTrue(finito, "\"secondi\":600" in finito)
        assertFalse(finito, "\"inizio\"" in finito)
        assertTrue(finito, "\"ora_agganciata\":true" in finito)
    }

    @Test
    fun `i tratti da mandare e consegnati`() {
        var m = dalServer(p15 + min, inCorso = studioServer())
            .conTrattoIniziato("t1", TipiTratto.COMPITI, null, null, istante(p15 + 2 * min), p15 + 2 * min, true)
        val primi = m.trattiDaMandare()
        assertEquals(listOf("t1"), primi.map { it.id })
        m = m.conTrattiConsegnati(primi, p15 + 3 * min)
        assertTrue("l'in_corso è partito", m.trattiDaMandare().isEmpty())
        m = m.conTrattoFermato(istante(p15 + 30 * min), p15 + 30 * min, true)
        val finali = m.trattiDaMandare()
        assertEquals(EsitiTratto.FINITO, finali.single().esito)
        m = m.conTrattiConsegnati(finali, p15 + 31 * min)
        assertTrue(m.trattiDaMandare().isEmpty())
        assertTrue(m.tratti.single().consegnato)
    }

    // --- La chiusura ------------------------------------------------------------------------

    private fun conUnOraDiCompiti(): MemoriaStudio = dalServer(p15 + min, inCorso = studioServer())
        .conTrattoIniziato("t1", TipiTratto.COMPITI, null, null, istante(p15 + 2 * min), p15 + 2 * min, true)

    @Test
    fun `Chiudi lo Studio solo con le condizioni - dopo le 16 e dopo il minimo`() {
        val m = conUnOraDiCompiti()
        // Alle 15:55 i minuti non bastano e non sono le 16.
        assertFalse(m.chiudibile(m.attivo(p15 + 55 * min)!!, istante(p15 + 55 * min), p15 + 55 * min))
        // Alle 16:01: 59 minuti, non bastano.
        assertFalse(m.chiudibile(m.attivo(p16 + min)!!, istante(p16 + min), p16 + min))
        // Alle 16:03: 61 minuti.
        assertTrue(m.chiudibile(m.attivo(p16 + 3 * min)!!, istante(p16 + 3 * min), p16 + 3 * min))
    }

    @Test
    fun `con due telefoni vale il conto del server`() {
        // L'altro telefono ha già mandato 58 minuti; qui solo 3.
        var m = dalServer(p16 + 2 * min, inCorso = studioServer(minuti = 58))
        m = m.conTrattoIniziato("t1", TipiTratto.COMPITI, null, null, istante(p16 + 2 * min), p16 + 2 * min, true)
            .conTrattoFermato(istante(p16 + 5 * min), p16 + 5 * min, true)
        val s = m.attivo(p16 + 5 * min)!!
        assertEquals(61, m.minutiStimati(s, istante(p16 + 5 * min), p16 + 5 * min))
        assertTrue(m.chiudibile(s, istante(p16 + 5 * min), p16 + 5 * min))
    }

    @Test
    fun `i tratti già contati dal server non si contano due volte`() {
        var m = dalServer(p15 + min, inCorso = studioServer())
            .conTrattoIniziato("t1", TipiTratto.COMPITI, null, null, istante(p15 + 2 * min), p15 + 2 * min, true)
            .conTrattoFermato(istante(p15 + 32 * min), p15 + 32 * min, true)
        val mandati = m.trattiDaMandare()
        m = m.conTrattiConsegnati(mandati, p15 + 33 * min)
        // Il server, dopo, conta 30 minuti.
        m = m.conServer(StatoStudioServer(config, studioServer(minuti = 30), listOf(partenzaOggi), null), istante(p15 + 34 * min), p15 + 34 * min)
        assertEquals(30, m.minutiStimati(m.attivo(p15 + 35 * min)!!, istante(p15 + 35 * min), p15 + 35 * min))
    }

    @Test
    fun `la chiusura vale subito qui, ferma il tratto a T, e va in coda`() {
        val m = conUnOraDiCompiti()
        val chiuso = m.conChiusura("c1", "Ho fatto gli esercizi di matematica", istante(p16 + 10 * min), p16 + 10 * min, true)!!
        assertNull(chiuso.attivo(p16 + 10 * min))
        assertEquals(EsitiTratto.FINITO, chiuso.tratti.single().esito)
        assertEquals(p16 + 10 * min, chiuso.tratti.single().fine)
        val c = chiuso.chiusureDaConsegnare.single()
        assertEquals(RifStudio(id = 41, giorno = oggi), c.rif)
        assertEquals(p16 + 10 * min, c.fine)
        // Anche se il server, prima di riceverla, dice ancora "in corso".
        val ancora = chiuso.conServer(StatoStudioServer(config, studioServer(minuti = 60), listOf(partenzaOggi), null), istante(p16 + 11 * min), p16 + 11 * min)
        assertNull(ancora.attivo(p16 + 11 * min))
    }

    @Test
    fun `la chiusura non parte senza dichiarazione o senza condizioni`() {
        val m = conUnOraDiCompiti()
        assertNull(m.conChiusura("c1", "corto", istante(p16 + 10 * min), p16 + 10 * min, true))
        assertNull(m.conChiusura("c1", "Ho fatto i compiti di storia", istante(p15 + 30 * min), p15 + 30 * min, true))
    }

    @Test
    fun `il server rifiuta la chiusura - lo Studio torna col motivo e la bozza`() {
        val chiuso = conUnOraDiCompiti().conChiusura("c1", "Ho fatto gli esercizi di matematica", istante(p16 + 10 * min), p16 + 10 * min, true)!!
        val tornato = chiuso.conChiusuraRifiutata("c1", "troppo_presto", null, p16 + 30 * min, null, null)
        assertNotNull(tornato.attivo(p16 + 11 * min))
        assertEquals("Ho fatto gli esercizi di matematica", tornato.rifiuto?.bozza)
        assertEquals("troppo_presto", tornato.rifiuto?.motivo)
        assertTrue(tornato.chiusureDaConsegnare.isEmpty())
    }

    @Test
    fun `chiusura consegnata - resta chiuso`() {
        val chiuso = conUnOraDiCompiti().conChiusura("c1", "Ho fatto gli esercizi di matematica", istante(p16 + 10 * min), p16 + 10 * min, true)!!
        val fatto = studioServer().copy(fine = p16 + 10 * min, chiusura = ChiusureStudio.FIGLIO, inCorso = false)
        val dopo = chiuso.conChiusuraConsegnata("c1", fatto)
        assertNull(dopo.attivo(p16 + 20 * min))
        assertTrue(dopo.chiusureDaConsegnare.isEmpty())
        assertEquals(41L, dopo.recenti.single().id)
    }

    @Test
    fun `chiusura fatta senza rete prima di una partenza - la partenza dopo fa un nuovo Studio`() {
        // Studio a mano alle 13, chiuso alle 14:30 (senza vincolo d'orario, con un'ora di attività).
        var m = dalServer(t(oggi, 12)).conAvvioManuale("k", t(oggi, 13))
            .conTrattoIniziato("t1", TipiTratto.COMPITI, null, null, istante(t(oggi, 13, 1)), t(oggi, 13, 1), true)
        m = m.conChiusura("c1", "Ho studiato geografia tutto il tempo", istante(t(oggi, 14, 30)), t(oggi, 14, 30), true)!!
        assertNull(m.attivo(t(oggi, 14, 40)))
        // Alle 15 parte quello automatico.
        assertEquals(RifStudio(giorno = oggi), m.attivo(p15 + min)?.rif)
    }

    @Test
    fun `chiusura persa (404) - se ne va e lo si dice`() {
        val chiuso = conUnOraDiCompiti().conChiusura("c1", "Ho fatto gli esercizi di matematica", istante(p16 + 10 * min), p16 + 10 * min, true)!!
        val dopo = chiuso.conChiusuraPersa("c1")
        assertTrue(dopo.chiusuraPersa)
        assertTrue(dopo.chiusure.isEmpty())
    }

    // --- Periodi (la misura) e memoria ---------------------------------------------------------

    @Test
    fun `i periodi dello Studio - aperto mentre dura, chiuso alla chiusura`() {
        var m = conUnOraDiCompiti().normalizzata(istante(p16), p16, true)
        assertEquals(PeriodoStudio(p15, null, approvata.app), m.periodi.single())
        assertTrue(m.inStudioAl(p15 + 30 * min))
        m = m.conChiusura("c1", "Ho fatto gli esercizi di matematica", istante(p16 + 10 * min), p16 + 10 * min, true)!!
            .normalizzata(istante(p16 + 11 * min), p16 + 11 * min, true)
        assertEquals(p16 + 10 * min, m.periodi.single().fine)
        assertFalse(m.inStudioAl(p16 + 20 * min))
    }

    @Test
    fun `la lista dello Studio in corso resta la sua, anche se la configurazione cambia`() {
        val propria = listOf("com.duolingo")
        val m = dalServer(p15 + min, inCorso = studioServer(app = propria))
        assertEquals(propria, m.attivo(p15 + 2 * min)!!.app)
    }

    @Test
    fun `la memoria si salva e si rilegge uguale`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val m = conUnOraDiCompiti().conChiusura("c1", "Ho fatto gli esercizi di matematica", istante(p16 + 10 * min), p16 + 10 * min, true)!!
            .normalizzata(istante(p16 + 11 * min), p16 + 11 * min, true)
        val riletta = json.decodeFromString(MemoriaStudio.serializer(), json.encodeToString(MemoriaStudio.serializer(), m))
        assertEquals(m, riletta)
    }
}
