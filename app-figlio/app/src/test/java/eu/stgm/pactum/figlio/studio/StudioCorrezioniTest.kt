package eu.stgm.pactum.figlio.studio

import eu.stgm.pactum.figlio.faccende.AppVisibili
import eu.stgm.pactum.figlio.faccende.BloccoDalServer
import eu.stgm.pactum.figlio.faccende.Istante
import eu.stgm.pactum.figlio.faccende.LetturaEventi
import eu.stgm.pactum.figlio.faccende.MemoriaBlocco
import eu.stgm.pactum.figlio.faccende.ParoleFaccende
import eu.stgm.pactum.figlio.faccende.TestoFaccende
import eu.stgm.pactum.figlio.misura.Sessioni
import eu.stgm.pactum.figlio.sessione.GuardiaSessione
import eu.stgm.pactum.figlio.sessione.MotivoBarriera
import eu.stgm.pactum.figlio.sessione.RitmoBarriera
import eu.stgm.pactum.figlio.sessione.SessioneAttiva
import eu.stgm.pactum.figlio.sessione.SituazioneBarriera
import eu.stgm.pactum.figlio.sessione.TracciaPrimoPiano
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * (0.18, contratto v4.0) Le correzioni dopo i due revisori, pezzo figlio:
 * il giro dello Studio che girava a vuoto, la barriera con l'orologio portato
 * indietro, le finestrelle e lo schermo diviso, l'interruttore di sicurezza
 * fatto scattare apposta, la dichiarazione con le regole del server, la
 * rilettura dopo una consegna, il 404 che non spegne lo Studio, la mezzanotte
 * finta, la pagina web dentro un'app e il testo dell'approvazione durante lo Studio.
 */
class StudioCorrezioniTest {

    private val roma: ZoneId = ZoneId.of("Europe/Rome")
    private val sec = 1_000L
    private val min = 60_000L
    private val ora = 60 * min

    private fun t(giorno: String, ore: Int, minuti: Int = 0): Long =
        ZonedDateTime.of(LocalDate.parse(giorno), LocalTime.of(ore, minuti), roma).toInstant().toEpochMilli()

    private val oggi = "2026-10-08" // giovedì
    private val domani = "2026-10-09"
    private val p15 = t(oggi, 15)
    private val p16 = t(oggi, 16)

    /** Il telefono acceso (accensione 1): monotono e muro vanno insieme. */
    private fun istante(muro: Long, avvio: Int? = 1) = Istante(muro, muro - t(oggi, 0) + 5 * ora, avvio)

    private val partenzaOggi = PartenzaStudio(oggi, p15, p16, 60)
    private val partenzaDomani = PartenzaStudio(domani, t(domani, 15), t(domani, 16), 60)
    private val classeviva = "com.spaggiari.classevivastudenti"
    private val gioco = "com.esempio.gioco"
    private val youtube = "com.google.android.youtube"
    private val home = "com.android.launcher3"
    private val impostazioni = "com.android.settings"
    private val chrome = "com.android.chrome"
    private val approvata = ContenutoStudio(app = listOf(classeviva), orariDal = "2026-10-01")
    private val config = ConfigStudio(StatiConfigStudio.APPROVATA, 4, approvata)

    private fun dalServer(quando: Long, inCorso: StudioSvolto? = null, m: MemoriaStudio = MemoriaStudio(fuso = "Europe/Rome")) =
        m.conServer(StatoStudioServer(config, inCorso, listOf(partenzaOggi, partenzaDomani), null), istante(quando), quando)

    private fun studioServer(id: Long = 41, inizio: Long = p15, origine: String = OriginiStudio.AUTOMATICA, chiave: String? = null, fine: Long? = null) =
        StudioSvolto(
            id = id, origine = origine, giorno = oggi, chiave = chiave, inizio = inizio,
            partenze = listOf(partenzaOggi), contaDal = p15, chiudibileDal = p16, minutiMinimi = 60,
            fine = fine, inCorso = fine == null,
        )

    @After
    fun pulisci() {
        StatoStudio.aggiorna(MemoriaStudio())
    }

    // --- 1. Il punto del timer: al massimo ogni 30 secondi -------------------------------

    @Test
    fun `il punto salvato - due giri a meno di 30 secondi danno la stessa memoria, niente scrittura`() {
        val conTratto = dalServer(p15 + min, studioServer())
            .conTrattoIniziato("tr-1", TipiTratto.COMPITI, null, null, istante(p15 + 2 * min), p15 + 2 * min, true)
        val a = conTratto.conPuntoSalvato(istante(p15 + 2 * min + 1 * sec))
        val b = a.conPuntoSalvato(istante(p15 + 2 * min + 20 * sec))
        assertSame(conTratto, a)
        assertSame(a, b)
        // Dopo 30 secondi il punto si salva.
        val c = b.conPuntoSalvato(istante(p15 + 2 * min + 30 * sec))
        assertTrue(c != b)
        assertEquals(istante(p15 + 2 * min + 30 * sec).monotono, c.trattoInCorso!!.monoSalvato)
    }

    @Test
    fun `il punto salvato - due giri di fila in Studio con un tratto in corso non danno due sveglie`() {
        var m = dalServer(p15 + min, studioServer())
            .conTrattoIniziato("tr-1", TipiTratto.COMPITI, null, null, istante(p15 + 2 * min), p15 + 2 * min, true)
        StatoStudio.aggiorna(m)
        StatoStudio.sveglia.tryReceive() // quella dell'aggiornamento di partenza
        // Il giro: punto salvato e aggiornamento (come ArchivioStudio.modifica), poi di nuovo subito.
        m = m.conPuntoSalvato(istante(p15 + 2 * min + 30 * sec))
        StatoStudio.aggiorna(m)
        assertTrue(StatoStudio.sveglia.tryReceive().isSuccess)
        m = m.conPuntoSalvato(istante(p15 + 2 * min + 31 * sec))
        StatoStudio.aggiorna(m)
        assertTrue("il giro dopo non si sveglia da solo", StatoStudio.sveglia.tryReceive().isFailure)
    }

    // --- 2. L'orologio spostato indietro durante lo Studio ------------------------------------

    @Test
    fun `orologio spostato indietro durante lo Studio - la barriera riparte da capo e vede il gioco`() {
        // Alle 15:30 davanti c'è Impostazioni; Luca porta l'ora alle 12:30 e apre un gioco.
        val traccia = TracciaPrimoPiano()
        val prima = p15 + 30 * min
        traccia.evento(Sessioni.RIPRESA, impostazioni, prima - 5 * sec)
        val lettoFinoA = prima
        val adesso = prima - 3 * ora
        traccia.evento(Sessioni.RIPRESA, gioco, adesso + 10 * sec)
        // Senza ripartire, l'evento più vecchio dell'ultimo istante si scarta: davanti resta Impostazioni.
        assertEquals(impostazioni, traccia.attuale)
        // La regola: il punto di lettura è nel futuro → da capo, con la finestra lunga.
        assertTrue(LetturaEventi.orologioIndietro(lettoFinoA, adesso))
        traccia.azzera()
        val da = LetturaEventi.inizio(daCapo = true, lettoFinoA = null, adesso = adesso + 20 * sec, dallAccensione = 6 * ora)
        assertEquals(adesso + 20 * sec - 6 * ora, da)
        traccia.evento(Sessioni.RIPRESA, gioco, adesso + 10 * sec)
        assertEquals(gioco, traccia.attuale)
        // E il gioco, fuori lista, si copre.
        assertTrue(decidi(studioAttivo(), gioco).copri)
        // Spostamenti piccoli (sotto il minuto) non ripartono da capo.
        assertFalse(LetturaEventi.orologioIndietro(prima, prima - 30 * sec))
        assertFalse(LetturaEventi.orologioIndietro(null, prima))
    }

    // --- 3. Finestrelle e schermo diviso ----------------------------------------------------------

    private fun studioAttivo(app: List<String> = listOf(classeviva)) = StudioAttivo(
        rif = RifStudio(id = 41), origine = OriginiStudio.AUTOMATICA, giorno = oggi, inizio = p15, contaDal = p15,
        chiudibileDal = p16, minutiMinimi = 60, app = app, nomi = emptyMap(), mezzanotte = t(domani, 0), dalServer = true,
    )

    private val sempreUsabili = setOf(home, impostazioni, "eu.stgm.pactum.figlio")

    private fun decidi(studio: StudioAttivo?, app: String?, classe: String? = null, precedente: String? = null) = GuardiaStudio.decidi(
        studio = studio,
        oraServer = p15 + 20 * min,
        primoPiano = app,
        schermoAcceso = true,
        sbloccato = true,
        mostraSopra = true,
        accessoUso = true,
        inChiamata = false,
        sempreUsabili = sempreUsabili,
        contaNellUso = { true },
        nelGruppoApk = { false },
        classe = classe,
        precedente = precedente,
        home = setOf(home),
    )

    @Test
    fun `finestrella - un video fuori lista in una finestrella sopra la Home si copre`() {
        val v = AppVisibili()
        val t0 = p15 + 20 * min
        v.evento(Sessioni.RIPRESA, youtube, t0, "$youtube.WatchActivity")
        // Home: YouTube va in pausa ma resta visibile nella finestrella (nessuno STOP).
        v.evento(Sessioni.RIPRESA, home, t0 + 500, "$home.Launcher")
        val studio = studioAttivo()
        val copre = { p: String, c: String? -> decidi(studio, p, c).copri }
        // Un attimo dopo non ancora (il passaggio fra due app dura poco).
        assertTrue(GuardiaStudio.finestrelleDaCoprire(v, t0 + 1_000, home, copre).isEmpty())
        assertEquals(setOf(youtube), GuardiaStudio.finestrelleDaCoprire(v, t0 + 5_000, home, copre))
        // Chiusa la finestrella: niente più da coprire.
        v.evento(Sessioni.STOP, youtube, t0 + 6_000, "$youtube.WatchActivity")
        assertTrue(GuardiaStudio.finestrelleDaCoprire(v, t0 + 8_000, home, copre).isEmpty())
        // Senza Studio non si copre niente.
        v.evento(Sessioni.RIPRESA, youtube, t0 + 9_000, "$youtube.WatchActivity")
        v.evento(Sessioni.RIPRESA, home, t0 + 9_500, "$home.Launcher")
        assertTrue(GuardiaStudio.finestrelleDaCoprire(v, t0 + 15_000, home) { p, c -> decidi(null, p, c).copri }.isEmpty())
    }

    @Test
    fun `schermo diviso - ClasseViva davanti e il gioco nell'altra metà - si copre il gioco, non ClasseViva`() {
        val v = AppVisibili()
        val t0 = p15 + 20 * min
        v.evento(Sessioni.RIPRESA, classeviva, t0, "$classeviva.Main")
        v.evento(Sessioni.RIPRESA, gioco, t0 + 1_000, "$gioco.Gioco")
        // Un tocco su ClasseViva la mette davanti: il gioco resta visibile nell'altra metà.
        v.evento(Sessioni.RIPRESA, classeviva, t0 + 2_000, "$classeviva.Main")
        val studio = studioAttivo()
        val copre = { p: String, c: String? -> decidi(studio, p, c).copri }
        assertEquals(setOf(gioco), GuardiaStudio.finestrelleDaCoprire(v, t0 + 6_000, classeviva, copre))
        // Un'app della lista in una finestrella non si copre.
        val w = AppVisibili()
        w.evento(Sessioni.RIPRESA, classeviva, t0, "$classeviva.Main")
        w.evento(Sessioni.RIPRESA, home, t0 + 500, "$home.Launcher")
        assertTrue(GuardiaStudio.finestrelleDaCoprire(w, t0 + 6_000, home, copre).isEmpty())
    }

    // --- 4. L'interruttore di sicurezza non si fa scattare apposta --------------------------------

    /** Un giro «gioco → barriera comparsa → Esci» ripetuto; [compare] = la barriera arriva sullo schermo. */
    private fun giri(compare: Boolean): Pair<Int, Boolean> {
        val ritmo = RitmoBarriera()
        var uscite = 0
        var comparse = 0
        val conti = ContiBarriera(ritmo, uscite, comparse)
        var adesso = 1_000_000L
        var aperture = 0
        var maiInPausa = true
        repeat(10) {
            // Il gioco davanti per qualche giro, finché la barriera si apre.
            var aperta = false
            var passi = 0
            while (!aperta && passi < 10) {
                conti.aggiorna(uscite, comparse)
                aperta = ritmo.passo(gioco, true, adesso)
                if (ritmo.inPausa(adesso)) maiInPausa = false
                adesso += 1_000
                passi++
            }
            if (aperta) aperture++
            if (aperta && compare) comparse++
            // «Esci»: alla Home.
            uscite++
            conti.aggiorna(uscite, comparse)
            ritmo.passo(home, false, adesso)
            adesso += 1_000
        }
        return aperture to maiInPausa
    }

    @Test
    fun `dieci giri di fila barriera comparsa, Esci, gioco - il ritmo non va mai in pausa`() {
        val (aperture, maiInPausa) = giri(compare = true)
        assertEquals(10, aperture)
        assertTrue(maiInPausa)
    }

    @Test
    fun `se la barriera non riesce a comparire, l'interruttore di sicurezza scatta ancora`() {
        val (aperture, maiInPausa) = giri(compare = false)
        assertTrue(aperture < 10)
        assertFalse(maiInPausa)
    }

    // --- 5 e 11. La dichiarazione con le regole del server -----------------------------------------

    @Test
    fun `dichiarazione - un'emoji composta con lo ZWJ o un tab non passano, come sul server`() {
        val zwj = "Matematica e corsa \uD83C\uDFC3\u200D\u2642\uFE0F"
        assertNull(RegoleStudio.dichiarazione(zwj))
        assertEquals(RegoleStudio.ErroreDichiarazione.CARATTERI, RegoleStudio.erroreDichiarazione(zwj))
        val tab = "Matematica\te storia"
        assertNull(RegoleStudio.dichiarazione(tab))
        assertEquals(RegoleStudio.ErroreDichiarazione.CARATTERI, RegoleStudio.erroreDichiarazione(tab))
        // Un tab o degli spazi ai bordi si tolgono; l'a capo resta ammesso.
        assertEquals("Matematica\ne storia", RegoleStudio.dichiarazione("\tMatematica\r\ne storia  "))
        // Un'emoji semplice va bene.
        assertNotNull(RegoleStudio.dichiarazione("Fatto matematica \uD83D\uDE00"))
        // I separatori di riga non passano.
        assertEquals(RegoleStudio.ErroreDichiarazione.CARATTERI, RegoleStudio.erroreDichiarazione("Matematica\u2028storia"))
    }

    @Test
    fun `dichiarazione - si conta in forma NFC, come sul server`() {
        // «caffè ok!» scritto con l'accento staccato: 10 caratteri prima, 9 dopo NFC.
        val scomposta = "caffe\u0300 ok!"
        assertEquals(10, scomposta.codePointCount(0, scomposta.length))
        assertEquals(9, RegoleStudio.caratteri(scomposta))
        assertEquals(RegoleStudio.ErroreDichiarazione.CORTA, RegoleStudio.erroreDichiarazione(scomposta))
        // Il testo che parte è quello in forma NFC.
        assertEquals("caffè ok, matematica", RegoleStudio.dichiarazione("caffe\u0300 ok, matematica"))
        assertEquals(RegoleStudio.ErroreDichiarazione.LUNGA, RegoleStudio.erroreDichiarazione("a".repeat(1001)))
    }

    @Test
    fun `una chiusura con un'emoji composta non chiude lo Studio sul telefono`() {
        val m = dalServer(p16 + min, studioServer().copy(minutiAttivita = 65, chiudibile = true))
        val o = istante(p16 + 2 * min)
        assertNull(m.conChiusura("ch-1", "Matematica e corsa \uD83C\uDFC3\u200D\u2642\uFE0F", o, p16 + 2 * min, true))
        assertNotNull(m.conChiusura("ch-1", "Matematica e corsa", o, p16 + 2 * min, true))
    }

    // --- 6. Chiusura senza rete prima di una partenza assorbita, con la GET prima della consegna ---

    @Test
    fun `chiusura senza rete prima di una partenza assorbita - dopo la consegna si rilegge e lo Studio delle 15 torna`() {
        val alle9 = t(oggi, 9)
        val alle10 = t(oggi, 10)
        val alle1130 = t(oggi, 11, 30)
        val alle17 = t(oggi, 17)
        // Alle 9 il server ha risposto; poi niente rete. Alle 10 lo Studio a mano, 75 minuti di compiti.
        var m = dalServer(alle9).conAvvioManuale("avvio-1", alle10)
        m = m.conTrattoIniziato("tr-1", TipiTratto.COMPITI, null, null, istante(alle10 + min), alle10 + min, true)
            .conTrattoFermato(istante(alle10 + 76 * min), alle10 + 76 * min, true)
        m = m.conChiusura("ch-1", "Matematica e storia", istante(alle1130), alle1130, true)!!
        assertNull(m.attivo(alle1130 + min))
        // Alle 15 parte lo Studio automatico, anche senza rete.
        assertEquals(p15, m.attivo(p15 + min)!!.inizio)
        // Alle 17 torna la rete. Prima la GET: per il server è in corso lo Studio 41 delle 15:00.
        m = m.conServer(StatoStudioServer(config, studioServer(), listOf(partenzaOggi, partenzaDomani), null), istante(alle17), alle17)
        assertNotNull(m.attivo(alle17))
        // Poi la consegna: l'avvio sposta lo Studio 41 alle 10:00 (la partenza delle 15 dentro).
        m = m.conAvvioConsegnato("avvio-1", studioServer(inizio = alle10, origine = OriginiStudio.MANUALE, chiave = "avvio-1"))
        // La chiusura delle 11:30 è consegnata: lo Studio 41 è chiuso.
        m = m.conChiusuraConsegnata("ch-1", studioServer(inizio = alle10, origine = OriginiStudio.MANUALE, chiave = "avvio-1", fine = alle1130))
        // Qui il telefono non sa ancora dello Studio 42 che il server ha fatto nascere alle 15:00:
        // per questo dopo la consegna lo stato si rilegge SUBITO (ControlloStudio.richiedi).
        assertNull(m.attivo(alle17 + sec))
        m = m.conServer(StatoStudioServer(config, studioServer(id = 42), listOf(partenzaOggi, partenzaDomani), null), istante(alle17 + 2 * sec), alle17 + 2 * sec)
        val s = m.attivo(alle17 + 3 * sec)!!
        assertEquals(42L, s.rif.id)
        assertEquals(p15, s.inizio)
    }

    @Test
    fun `una richiesta di rilettura arriva al giro dello Studio`() {
        while (ControlloStudio.richieste.tryReceive().isSuccess) Unit
        ControlloStudio.richiedi()
        assertTrue(ControlloStudio.richieste.tryReceive().isSuccess)
    }

    // --- 7. Un 404 su /api/studio non spegne lo Studio -------------------------------------------------

    @Test
    fun `un 404 su api studio - lo Studio in corso e le partenze restano, l'avvio a mano no`() {
        val m = dalServer(p15 + 5 * min, studioServer()).conServerVecchio()
        assertTrue(m.serverVecchio)
        assertTrue(m.conosciuto)
        assertEquals(41L, m.attivo(p15 + 6 * min)!!.rif.id)
        assertEquals(t(domani, 15), m.prossimaPartenza(p15 + 6 * min)!!.inizio)
        // Anche senza Studio in corso: la partenza di domani parte lo stesso.
        val fuori = dalServer(p15 - ora).conServerVecchio()
        assertEquals(p15, fuori.attivo(p15 + min)!!.inizio)
        // Un nuovo avvio a mano no (il server forse non lo conosce).
        assertEquals(MemoriaStudio.NoAvvio.SERVER_VECCHIO, fuori.noAvvio(p15 - 30 * min, false))
        // La prossima risposta buona toglie il segno.
        assertFalse(dalServer(p15 + 7 * min, studioServer(), m).serverVecchio)
        // Un patto valido SENZA `studio` invece spegne lo Studio.
        assertNull(m.senzaStudio(istante(p15 + 8 * min)).attivo(p15 + 9 * min))
    }

    // --- 8. La mezzanotte finta, dopo un riavvio senza rete col'orologio spostato --------------------

    @Test
    fun `riavvio senza rete e orologio portato alle 23 e 59 - lo Studio non finisce a una mezzanotte finta`() {
        // Alle 15:30 il server ha risposto (accensione 1): Studio 41 in corso.
        val alle1530 = p15 + 30 * min
        val blocco = MemoriaBlocco().conServer(BloccoDalServer(false, null, null, emptyList()), istante(alle1530), istante(alle1530), alle1530)
        val studio = dalServer(alle1530, studioServer())
        // Luca spegne la rete, riavvia (accensione 2) e porta l'orologio a mezzanotte e un minuto.
        val dopoMezzanotte = t(domani, 0, 1)
        val riavviato = Istante(dopoMezzanotte, 10 * min, avvio = 2)
        val aMano = blocco.conCambioOra()
        val o = OraServer.di(aMano, riavviato)
        assertFalse(o.agganciata)
        assertEquals(dopoMezzanotte, o.server)
        // Prima la mezzanotte finta lo chiudeva; adesso conta un'ora di sicuro passata: 15:30 + 10 minuti.
        assertNull(studio.attivo(o.server))
        assertEquals(alle1530 + 10 * min, o.perFine)
        assertEquals(41L, studio.attivo(o)!!.rif.id)
        // Il tratto in corso non si chiude a quella mezzanotte.
        val conTratto = studio.copy(
            tratti = listOf(TrattoLocale("tr-1", TipiTratto.COMPITI, avvio = 2, monoInizio = 5 * min, monoSalvato = 5 * min, inizio = alle1530, oraAgganciata = false)),
        )
        val n = conTratto.normalizzata(riavviato, o.server, o.agganciata, o.perFine)
        assertTrue(n.tratti.single().inCorso)
        // Portato indietro, alle 12:00: lo Studio già cominciato non torna «non ancora cominciato».
        val indietro = OraServer.di(aMano, Istante(t(oggi, 12), 10 * min, avvio = 2))
        assertNotNull(studio.attivo(indietro))
        // Quando l'orologio che non si sposta conferma che la mezzanotte è passata, finisce.
        val tardi = OraServer.di(aMano, Istante(dopoMezzanotte, 9 * ora, avvio = 2))
        assertNull(studio.attivo(tardi))
        // Senza un cambio d'ora a mano vale l'orologio del telefono (con lo scarto), come dice il contratto.
        val senzaCambio = OraServer.di(blocco, riavviato)
        assertEquals(senzaCambio.server, senzaCambio.perFine)
        assertNull(studio.attivo(senzaCambio))
        // E con l'ora agganciata vale quella.
        val agganciata = OraServer.di(aMano, istante(alle1530 + min))
        assertTrue(agganciata.agganciata)
        assertEquals(agganciata.server, agganciata.perFine)
    }

    // --- 9. La pagina web «dentro un'app» nello Studio --------------------------------------------------

    @Test
    fun `una pagina web dentro un'app - libera solo se l'ha aperta un'app della lista o una sempre usabile`() {
        val studio = studioAttivo()
        val scheda = "org.chromium.chrome.browser.customtabs.CustomTabActivity"
        // Aperta da ClasseViva (in lista) o dalle Impostazioni: fa parte di quell'app.
        assertEquals(MotivoBarriera.PARTE_DI_UN_APP, decidi(studio, chrome, scheda, precedente = classeviva).motivo)
        assertFalse(decidi(studio, chrome, scheda, precedente = impostazioni).copri)
        // Ripresa dalle Recenti o dalla Home, o senza un «prima» (schermo spento): si copre.
        assertTrue(decidi(studio, chrome, scheda, precedente = home).copri)
        assertTrue(decidi(studio, chrome, scheda, precedente = "com.android.systemui").copri)
        assertTrue(decidi(studio, chrome, scheda, precedente = null).copri)
        // Aperta da un gioco fuori lista: si copre.
        assertTrue(decidi(studio, chrome, scheda, precedente = gioco).copri)
        // Le Sessioni restano come prima: la pagina dentro un'app resta libera.
        val sessione = SituazioneBarriera(
            sessione = SessioneAttiva(1, 1, "Compiti", setOf(classeviva), emptyMap(), p15, p16),
            adesso = p15 + min, primoPiano = chrome, schermoAcceso = true, sbloccato = true, mostraSopra = true,
            accessoUso = true, inChiamata = false, sempreUsabili = sempreUsabili, contaNellUso = { true },
            nelGruppoApk = { false }, classe = scheda,
        )
        assertEquals(MotivoBarriera.PARTE_DI_UN_APP, GuardiaSessione.decidi(sessione).motivo)
    }

    // --- 13. L'approvazione durante lo Studio -----------------------------------------------------------

    private val parole = ParoleFaccende(
        nuove = { _, _ -> "" }, bloccoSubito = "", bloccoAlle = "", bloccoDomani = "", bloccoGiorno = "",
        nuoveTesto = "", bocciata = "", bocciataTesto = "", annullata = "", annullataTesto = "",
        genitoreSenzaNome = "Il genitore",
        confermata = "%1\$s ha confermato «%2\$s»", confermataTesto = "Il lavoro è segnato come svolto.",
        approvata = "%1\$s ha approvato «%2\$s»", approvataSblocca = "Telefono e computer sono sbloccati.",
        approvataTesto = "Il lavoro è fatto.", approvataNonParte = "Il lavoro è fatto: il blocco non partirà a fine Studio.",
    )

    private fun payload(testo: String) = Json.parseToJsonElement(testo) as JsonObject

    @Test
    fun `approvato col blocco rimandato dallo Studio - il telefono dice che il blocco non partirà`() {
        val p = payload("""{ "faccenda_id": 5, "titolo": "Letto", "genitore": { "id": 2, "nome": "Mamma" }, "sblocca": false }""")
        val (titolo, testo) = TestoFaccende.avvisoConfermata(p, parole, "Mamma ha approvato «Letto»: il blocco non partirà a fine Studio")!!
        assertEquals("Mamma ha approvato «Letto»", titolo)
        assertEquals("Il lavoro è fatto: il blocco non partirà a fine Studio.", testo)
        // Un lavoro che non era l'ultimo: niente frase sul blocco.
        assertEquals("Il lavoro è fatto.", TestoFaccende.avvisoConfermata(p, parole, "Mamma ha approvato «Letto»")!!.second)
        assertEquals("Il lavoro è fatto.", TestoFaccende.avvisoConfermata(p, parole)!!.second)
    }
}
