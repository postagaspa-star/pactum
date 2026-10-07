package eu.stgm.pactum.genitore.ui

import eu.stgm.pactum.genitore.MainActivity
import eu.stgm.pactum.genitore.dati.ChiStudio
import eu.stgm.pactum.genitore.dati.ChiusureStudio
import eu.stgm.pactum.genitore.dati.CodiciErrore
import eu.stgm.pactum.genitore.dati.ConfigStudio
import eu.stgm.pactum.genitore.dati.ContenutoStudio
import eu.stgm.pactum.genitore.dati.Dispositivo
import eu.stgm.pactum.genitore.dati.EsitiSessione
import eu.stgm.pactum.genitore.dati.EsitiTratto
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.ListaComputerStudio
import eu.stgm.pactum.genitore.dati.ListaTelefonoStudio
import eu.stgm.pactum.genitore.dati.Notifica
import eu.stgm.pactum.genitore.dati.OriginiStudio
import eu.stgm.pactum.genitore.dati.PartenzaStudio
import eu.stgm.pactum.genitore.dati.RiferimentoDispositivo
import eu.stgm.pactum.genitore.dati.RiferimentoGenitore
import eu.stgm.pactum.genitore.dati.StatiConfigStudio
import eu.stgm.pactum.genitore.dati.StudioPatto
import eu.stgm.pactum.genitore.dati.StudioSvolto
import eu.stgm.pactum.genitore.dati.TipiDispositivo
import eu.stgm.pactum.genitore.dati.TipiTratto
import eu.stgm.pactum.genitore.dati.TrattoStudio
import eu.stgm.pactum.genitore.rete.PostinoClient
import eu.stgm.pactum.genitore.sync.Vedetta
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * (0.18, contratto v4.0, parte C) La Sessione Studio dalla parte del genitore:
 * lo Studio di oggi nella Panoramica, i minuti DICHIARATI col timer, il motivo
 * obbligatorio per chiuderlo, la configurazione da approvare (con quello che
 * cambia e quando vale), lo storico (inizio, tratti, chiusura, versioni), i
 * dispositivi più vecchi della 0.18, le notifiche e dove portano.
 */
class StudioTest {

    private val p = ParoleDiProva
    private val roma = ZoneId.of("Europe/Rome")
    private val oggi = LocalDate.of(2026, 10, 7)
    private val io = RiferimentoGenitore(2, "Mamma")

    /** 15:42 a Roma. */
    private val adesso = Instant.parse("2026-10-07T13:42:00Z")

    private fun studio(
        id: Long = 41,
        giorno: String = "2026-10-07",
        inizio: String = "2026-10-07T13:00:00+00:00",
        chiudibileDal: String? = "2026-10-07T14:00:00+00:00",
        minuti: Int = 42,
        minimi: Int? = 60,
        fine: String? = null,
        chiusura: String? = null,
        chiusaDa: ChiStudio? = null,
        dichiarazione: String? = null,
        motivo: String? = null,
        alla: Int? = null,
        chiudibile: Boolean = false,
        tratti: List<TrattoStudio> = emptyList(),
        origine: String = OriginiStudio.AUTOMATICA,
        avviatoDa: ChiStudio? = null,
    ) = StudioSvolto(
        id = id,
        origine = origine,
        giorno = giorno,
        inizioTs = inizio,
        avviatoDa = avviatoDa,
        chiudibileDal = chiudibileDal,
        minutiMinimi = minimi,
        minutiAttivita = minuti,
        minutiAllaChiusura = alla,
        chiudibile = chiudibile,
        tratti = tratti,
        fineTs = fine,
        chiusura = chiusura,
        chiusaDa = chiusaDa,
        dichiarazione = dichiarazione,
        motivo = motivo,
        inCorso = fine == null,
    )

    private val approvata = ContenutoStudio(
        giorni = listOf("lun", "mar", "mer", "gio", "ven"),
        inizio = "15:00",
        chiusuraMinima = "16:00",
        minutiMinimi = 60,
        telefono = ListaTelefonoStudio(app = listOf("eu.spaggiari.classevivafamiglia"), nomi = mapOf("eu.spaggiari.classevivafamiglia" to "ClasseViva")),
        computer = ListaComputerStudio(
            programmi = listOf("exe:winword.exe", "sito:classeviva.it"),
            nomi = mapOf("exe:winword.exe" to "Word"),
            firme = mapOf("exe:winword.exe" to "Microsoft Corporation"),
        ),
        orariDal = "2026-10-06",
        approvataTs = "2026-10-05T16:00:00+00:00",
        decisaDa = RiferimentoGenitore(1, "Papà"),
    )

    // --- lo Studio di oggi ------------------------------------------------------------------

    @Test
    fun `lo Studio in corso si dice coi minuti dichiarati col timer e l'ora di chiusura`() {
        val s = studio()
        assertEquals(
            "Studio dalle 15:00 · 42 min dichiarati col timer su 60 · si chiude dopo le 16:00",
            testoStatoStudio(p, s, adesso, roma),
        )
        // Dopo le 16:00 "si chiude dopo le 16:00" non serve più.
        assertEquals(
            "Studio dalle 15:00 · 42 min dichiarati col timer su 60",
            testoStatoStudio(p, s, Instant.parse("2026-10-07T14:05:00Z"), roma),
        )
        // A mano, senza partenze: niente vincolo d'orario; senza minimo, niente "su".
        assertEquals(
            "Studio dalle 15:00 · 42 min dichiarati col timer",
            testoStatoStudio(p, s.copy(chiudibileDal = null, minutiMinimi = null), adesso, roma),
        )
        assertNull(testoChiudibile(p, s, "Luca"))
        assertEquals("Luca lo può già chiudere dal suo telefono.", testoChiudibile(p, s.copy(chiudibile = true), "Luca"))
        assertTrue(chiudibileDalGenitore(s))
    }

    @Test
    fun `lo Studio di oggi sceglie che cosa raccontare`() {
        val config = ConfigStudio(stato = StatiConfigStudio.APPROVATA, versione = 4, approvata = approvata)
        val inCorso = studio()
        assertEquals(StudioDiOggi.InCorso(inCorso), studioDiOggi(StudioPatto(config, inCorso), emptyList(), oggi, adesso))
        // Il server non lo mette in `in_corso`, ma tra gli svolti è aperto: in corso lo stesso.
        assertEquals(StudioDiOggi.InCorso(inCorso), studioDiOggi(StudioPatto(config), listOf(inCorso), oggi, adesso))
        val chiuso = studio(fine = "2026-10-07T15:40:00+00:00", chiusura = ChiusureStudio.FIGLIO, alla = 65)
        assertEquals(StudioDiOggi.Chiuso(chiuso), studioDiOggi(StudioPatto(config), listOf(chiuso), oggi, adesso))
        val ieri = studio(id = 40, giorno = "2026-10-06", fine = "2026-10-06T22:00:00+00:00", chiusura = ChiusureStudio.NON_CHIUSO, alla = 40)
        assertEquals(StudioDiOggi.NonChiusoIeri(ieri), studioDiOggi(StudioPatto(config), listOf(ieri), oggi, adesso))
        assertEquals("Lo Studio di ieri non è stato chiuso: 40 min dichiarati col timer.", testoIeriNonChiuso(p, ieri))
        // Ieri chiuso dal figlio: niente da dire oggi.
        assertEquals(StudioDiOggi.Niente, studioDiOggi(StudioPatto(config), listOf(ieri.copy(chiusura = ChiusureStudio.FIGLIO)), oggi, adesso))
        // Oggi parte più tardi.
        val partenza = PartenzaStudio(giorno = "2026-10-07", inizioTs = "2026-10-07T15:00:00+00:00")
        assertEquals(
            StudioDiOggi.Parte(Instant.parse("2026-10-07T15:00:00Z")),
            studioDiOggi(StudioPatto(config, prossimePartenze = listOf(partenza)), emptyList(), oggi, adesso),
        )
        // Già passata (e niente Studio): non "parte".
        assertEquals(StudioDiOggi.Niente, studioDiOggi(StudioPatto(config, prossimePartenze = listOf(partenza)), emptyList(), oggi, Instant.parse("2026-10-07T16:00:00Z")))
        // Niente di approvato: lo Studio non parte.
        assertEquals(StudioDiOggi.NonApprovato, studioDiOggi(StudioPatto(ConfigStudio()), emptyList(), oggi, adesso))
        // Un server più vecchio della v4.0: non si dice niente.
        assertNull(studioDiOggi(null, emptyList(), oggi, adesso))
        assertFalse(conStudio(Finestra()))
        assertTrue(conStudio(Finestra(studio = StudioPatto())))
    }

    @Test
    fun `uno Studio chiuso dice chi, quando e quanti minuti, congelati alla chiusura`() {
        val figlio = studio(fine = "2026-10-07T15:40:00+00:00", chiusura = ChiusureStudio.FIGLIO, minuti = 70, alla = 65, dichiarazione = "Matematica e storia")
        // I minuti della chiusura, non quelli arrivati dopo.
        assertEquals(65, minutiDichiarati(figlio))
        assertEquals(
            "Studio dalle 15:00, chiuso alle 17:40 da Luca · 65 min dichiarati col timer",
            testoStudioSvolto(p, figlio, io, "Luca", adesso, roma),
        )
        assertEquals(listOf("Cosa ha fatto: «Matematica e storia»"), righeChiusuraStudio(p, figlio))
        val genitore = studio(
            fine = "2026-10-07T13:20:00+00:00",
            chiusura = ChiusureStudio.GENITORE,
            chiusaDa = ChiStudio(1, "Papà"),
            motivo = "visita medica",
            alla = 12,
        )
        assertEquals(
            "Studio dalle 15:00, chiuso alle 15:20 da Papà · 12 min dichiarati col timer",
            testoStudioSvolto(p, genitore, io, "Luca", adesso, roma),
        )
        assertEquals(listOf("Motivo: «visita medica»"), righeChiusuraStudio(p, genitore))
        assertEquals(
            "Studio dalle 15:00, chiuso alle 15:20 da te · 12 min dichiarati col timer",
            testoStudioSvolto(p, genitore.copy(chiusaDa = ChiStudio(2, "Mamma")), io, "Luca", adesso, roma),
        )
        val nonChiuso = studio(fine = "2026-10-07T22:00:00+00:00", chiusura = ChiusureStudio.NON_CHIUSO, alla = 40)
        assertEquals("Studio dalle 15:00, non chiuso · 40 min dichiarati col timer", testoStudioSvolto(p, nonChiuso, io, "Luca", adesso, roma))
        assertFalse(chiudibileDalGenitore(nonChiuso))
    }

    @Test
    fun `come e cominciato e i tratti di attivita`() {
        assertEquals("partito da solo", testoOrigineStudio(p, studio()))
        assertEquals(
            "avviato a mano da «Telefono di Luca»",
            testoOrigineStudio(p, studio(origine = OriginiStudio.MANUALE, avviatoDa = ChiStudio(1, "Telefono di Luca", TipiDispositivo.TELEFONO))),
        )
        val compiti = TrattoStudio(id = "a", tipo = TipiTratto.COMPITI, parola = "matematica", fine = 2, secondi = 1800, secondiContati = 1800, minuti = 30, esito = EsitiTratto.FINITO, conta = true)
        assertEquals("Compiti (matematica) · 30 min", testoTratto(p, compiti))
        val lavoro = TrattoStudio(id = "b", tipo = TipiTratto.LAVORI_DI_CASA, faccendaId = 5, fine = 3, secondi = 600, esito = EsitiTratto.FINITO, conta = true)
        assertEquals("Lavori di casa: «Svuota la lavastoviglie» · 10 min", testoTratto(p, lavoro) { if (it == 5L) "Svuota la lavastoviglie" else null })
        assertEquals("Lavori di casa · 10 min", testoTratto(p, lavoro))
        val altro = TrattoStudio(id = "c", tipo = TipiTratto.ALTRO, parola = "allenamento", fine = 4, secondi = 2400, secondiContati = 2399, esito = EsitiTratto.INTERROTTO, conta = true)
        assertEquals("Altro (allenamento) · 39 min · interrotto", testoTratto(p, altro))
        val fuori = altro.copy(conta = false, esito = EsitiTratto.FINITO, minuti = 0)
        assertEquals("Altro (allenamento) · 0 min · non contato", testoTratto(p, fuori))
        val inCorso = TrattoStudio(id = "d", tipo = TipiTratto.COMPITI, inizio = 5, esito = EsitiTratto.IN_CORSO, secondi = 60)
        assertEquals("Compiti · in corso", testoTratto(p, inCorso))
        // In ordine: per fine (o per inizio se è in corso).
        assertEquals(listOf("a", "b", "c", "d"), trattiInOrdine(studio(tratti = listOf(inCorso, altro, compiti, lavoro))).map { it.id })
    }

    // --- chiudere lo Studio: il motivo -----------------------------------------------------------

    @Test
    fun `il motivo della chiusura e obbligatorio, da 3 a 300 caratteri`() {
        assertEquals(ProblemaMotivo.VUOTO, problemaMotivo(""))
        assertEquals(ProblemaMotivo.VUOTO, problemaMotivo("   "))
        assertEquals(ProblemaMotivo.CORTO, problemaMotivo(" ok "))
        assertNull(problemaMotivo("  ok!  "))
        assertNull(problemaMotivo("Visita\nmedica"))
        assertNull(problemaMotivo("a".repeat(300)))
        assertEquals(ProblemaMotivo.LUNGO, problemaMotivo("a".repeat(301)))
        assertEquals(ProblemaMotivo.INVISIBILI, problemaMotivo("visita​medica"))
        assertEquals("Obbligatorio: da 3 a 300 caratteri.", testoProblemaMotivo(p, ProblemaMotivo.VUOTO))
        assertEquals("Scrivi almeno 3 caratteri.", testoProblemaMotivo(p, ProblemaMotivo.CORTO))
    }

    // --- la configurazione da approvare --------------------------------------------------------

    @Test
    fun `la configurazione in attesa si risponde con la versione vista`() {
        assertNull(richiestaStudio(null))
        assertNull(richiestaStudio(ConfigStudio(stato = StatiConfigStudio.APPROVATA, versione = 4, approvata = approvata)))
        // Senza versione non si risponde (il server non saprebbe su che cosa).
        assertNull(richiestaStudio(ConfigStudio(versione = null, inAttesa = approvata)))
        val config = ConfigStudio(stato = StatiConfigStudio.APPROVATA, versione = 5, approvata = approvata, inAttesa = approvata.copy(inizio = "14:30"))
        val richiesta = richiestaStudio(config)!!
        assertEquals(5, richiesta.versione)
        assertTrue(richiesta.cambio)
        // Appena decisa da qui: sparisce; una richiesta nuova (versione più alta) torna.
        assertNull(richiestaStudio(config, decisaVersione = 5))
        assertEquals(6, richiestaStudio(config.copy(versione = 6), decisaVersione = 5)?.versione)
    }

    @Test
    fun `alla prima proposta tutto e nuovo e gli orari valgono da domani`() {
        val prima = ContenutoStudio(
            giorni = listOf("ven", "lun", "mar", "mer", "gio", "lun"),
            inizio = "15:00",
            chiusuraMinima = "16:00",
            minutiMinimi = 60,
            telefono = ListaTelefonoStudio(app = listOf("gruppo:apk")),
            da = RiferimentoDispositivo(1, "Telefono di Luca", TipiDispositivo.TELEFONO),
            richiestaTs = "2026-10-07T13:10:00+00:00",
        )
        val richiesta = RichiestaStudio(prima, approvata = null, versione = 1, motivazionePrecedente = null)
        assertFalse(richiesta.cambio)
        assertTrue(cambiStudio(richiesta).orari)
        assertEquals(
            listOf(
                "Dal lunedì al venerdì",
                "Dalle 15:00, si chiude dopo le 16:00",
                "Almeno 60 min di attività col timer",
                "Sul telefono: le app installate fuori dal Play Store",
                "Sul computer: nessun programma né sito: solo quelli sempre usabili",
            ),
            righeRichiestaStudio(p, richiesta),
        )
        assertEquals(listOf("I nuovi orari valgono da domani."), righeQuandoValeStudio(p, richiesta))
        assertEquals("Luca chiede di approvare lo Studio", testoChiedeStudio(p, "Luca", richiesta.cambio))
        assertEquals("Dal telefono «Telefono di Luca» · oggi 15:10", testoPropostaDa(p, prima, roma, oggi))
    }

    @Test
    fun `un cambio dice cosa aggiunge e cosa toglie, e com'era prima`() {
        val dopo = approvata.copy(
            inizio = "14:30",
            telefono = ListaTelefonoStudio(
                app = listOf("eu.spaggiari.classevivafamiglia", "com.duolingo"),
                nomi = mapOf("com.duolingo" to "Duolingo"),
            ),
            computer = ListaComputerStudio(programmi = listOf("exe:winword.exe", "sito:wikipedia.org")),
            orariDal = null,
            decisaDa = null,
        )
        val richiesta = RichiestaStudio(dopo, approvata, versione = 6, motivazionePrecedente = "troppi siti")
        val cambi = cambiStudio(richiesta)
        assertTrue(cambi.inizio)
        assertFalse(cambi.giorni)
        assertFalse(cambi.minutiMinimi)
        assertEquals(listOf("com.duolingo"), cambi.appAggiunte)
        assertEquals(listOf("sito:wikipedia.org"), cambi.programmiAggiunti)
        assertEquals(listOf("sito:classeviva.it"), cambi.programmiTolti)
        assertEquals(
            listOf(
                "Dal lunedì al venerdì",
                "Dalle 14:30, si chiude dopo le 16:00 · prima: dalle 15:00, si chiude dopo le 16:00",
                "Almeno 60 min di attività col timer",
                // (correzione 0.18) Le voci nuove con anche il pacchetto accanto all'etichetta.
                "Sul telefono: ClasseViva, Duolingo (com.duolingo)",
                "Aggiunge: Duolingo (com.duolingo)",
                "Sul computer: Word · wikipedia.org (sito)",
                "Aggiunge: wikipedia.org (sito)",
                "Toglie: classeviva.it (sito)",
                // (correzione 0.18) Questa proposta toglie anche la firma di Word: si dice.
                "Attenzione: per Word (winword.exe) non si controlla più chi l'ha firmato (prima: Microsoft Corporation). " +
                    "Un altro programma con lo stesso nome passerebbe.",
            ),
            righeRichiestaStudio(p, richiesta),
        )
        assertEquals(
            listOf("I nuovi orari valgono da domani.", "La nuova lista vale dal prossimo Studio."),
            righeQuandoValeStudio(p, richiesta),
        )
        assertEquals("Luca chiede di cambiare lo Studio", testoChiedeStudio(p, "Luca", true))
        // Solo le liste: niente "da domani".
        val soloListe = RichiestaStudio(approvata.copy(telefono = ListaTelefonoStudio()), approvata, 7, null)
        assertEquals(listOf("La nuova lista vale dal prossimo Studio."), righeQuandoValeStudio(p, soloListe))
        // I giorni in un altro ordine non sono un cambio.
        val stessiGiorni = RichiestaStudio(approvata.copy(giorni = listOf("ven", "gio", "mer", "mar", "lun")), approvata, 8, null)
        assertFalse(cambiStudio(stessiGiorni).giorni)
    }

    @Test
    fun `la configurazione approvata a parole, e chi l'ha approvata`() {
        assertEquals(
            listOf(
                "Dal lunedì al venerdì",
                "Dalle 15:00, si chiude dopo le 16:00",
                "Almeno 60 min di attività col timer",
                "Sul telefono: ClasseViva",
                "Sul computer: Word, firmato da Microsoft Corporation · classeviva.it (sito)",
            ),
            righeConfigStudio(p, approvata),
        )
        assertEquals("Approvata da Papà 05/10 18:00", testoDecisaStudio(p, approvata, io, roma, oggi))
        assertEquals("Approvata da te 05/10 18:00", testoDecisaStudio(p, approvata.copy(decisaDa = io), io, roma, oggi))
        // La prima, scritta all'aggiornamento del server: nessuno l'ha approvata a mano.
        assertEquals("Decisa dalla famiglia all'aggiornamento", testoDecisaStudio(p, approvata.copy(decisaDa = null), io, roma, oggi))
        assertEquals("Versione 1 · Decisa dalla famiglia all'aggiornamento · orari dal 06/10", testoVersioneStudio(p, approvata.copy(decisaDa = null, versione = 1), io, roma, oggi))
        assertEquals("Questi orari valgono da domani.", testoOrariDal(p, "2026-10-08", oggi))
        assertEquals("Questi orari valgono dal 09/10.", testoOrariDal(p, "2026-10-09", oggi))
        assertNull(testoOrariDal(p, "2026-10-07", oggi))
        assertNull(testoOrariDal(p, "non è una data", oggi))
    }

    @Test
    fun `i giorni come li direbbe una persona`() {
        assertEquals("tutti i giorni", testoGiorniStudio(p, listOf("dom", "sab", "ven", "gio", "mer", "mar", "lun")))
        assertEquals("dal lunedì al venerdì", testoGiorniStudio(p, listOf("lun", "mar", "mer", "gio", "ven")))
        assertEquals("lunedì, mercoledì e venerdì", testoGiorniStudio(p, listOf("ven", "lun", "mer")))
        assertEquals("sabato e domenica", testoGiorniStudio(p, listOf("sab", "dom")))
        assertEquals("martedì", testoGiorniStudio(p, listOf("mar", "xyz")))
    }

    // --- i dispositivi più vecchi della 0.18 ------------------------------------------------------

    @Test
    fun `dove lo Studio non c'e lo si dice, e senza telefono 0_18 non parte`() {
        val telefono17 = Dispositivo(id = 1, nome = "Telefono di Luca", tipo = TipiDispositivo.TELEFONO, versioneApp = "0.17.0")
        val computer18 = Dispositivo(id = 2, nome = "Computer", tipo = TipiDispositivo.COMPUTER, versioneApp = "0.18.0")
        val senza = dispositiviSenzaStudio(listOf(telefono17, computer18))
        assertEquals(listOf(1L), senza.map { it.dispositivo.id })
        assertEquals(
            "Su «Telefono di Luca» c'è Pactum 0.17.0: lì lo Studio non c'è e il blocco dei lavori non aspetta. Serve la 0.18 o più nuova.",
            testoDispositivoSenzaStudio(p, senza.first()),
        )
        assertFalse(haTelefonoConStudio(listOf(telefono17, computer18)))
        assertTrue(haTelefonoConStudio(listOf(telefono17.copy(versioneApp = "0.18.1"))))
        // Un telefono scollegato non conta.
        assertFalse(haTelefonoConStudio(listOf(telefono17.copy(versioneApp = "0.18.0", revocato = true))))
    }

    // --- gli esiti e le notifiche ---------------------------------------------------------------

    @Test
    fun `gli esiti dei gesti sullo Studio`() {
        assertEquals("Studio approvato.", messaggioEventoStudio(p, EventoStudio.Decisa(EsitiSessione.APPROVA)))
        assertEquals("Studio non approvato.", messaggioEventoStudio(p, EventoStudio.Decisa(EsitiSessione.RIFIUTA)))
        assertEquals("Nel frattempo la richiesta è cambiata: guardala di nuovo.", messaggioEventoStudio(p, EventoStudio.Cambiata))
        assertEquals("Studio chiuso.", messaggioEventoStudio(p, EventoStudio.Chiuso))
        assertEquals("Lo Studio era già chiuso: ho riletto.", messaggioEventoStudio(p, EventoStudio.GiaChiuso))
        assertEquals("Non riesco a raggiungere il server: riprova.", messaggioEventoStudio(p, EventoStudio.Rifiuto(null, GestoStudio.CHIUDI, 1)))
        assertEquals(
            "Il motivo non va bene: da 3 a 300 caratteri, senza caratteri invisibili.",
            messaggioEventoStudio(p, EventoStudio.Rifiuto(PostinoClient.PARAMETRI_NON_VALIDI, GestoStudio.CHIUDI, 1)),
        )
        assertEquals(
            "Per lo Studio serve aggiornare il server di Pactum.",
            messaggioEventoStudio(p, EventoStudio.Rifiuto(CodiciErrore.SERVER_DA_AGGIORNARE, GestoStudio.APPROVA, 1)),
        )
    }

    private fun notifica(tipo: String, messaggio: String) = Notifica(
        id = 3,
        tipo = tipo,
        messaggio = messaggio,
        payload = buildJsonObject { put("studio_id", JsonPrimitive(41)) },
        tsServer = "2026-10-07T13:00:00+00:00",
        figlioId = 1,
    )

    @Test
    fun `le notifiche dello Studio hanno il loro titolo e portano al posto giusto`() {
        val casi = mapOf(
            "studio_iniziato" to "Studio iniziato",
            "studio_chiuso" to "Studio chiuso",
            "studio_non_chiuso" to "Studio non chiuso",
            "studio_non_partito" to "Studio non partito",
        )
        casi.forEach { (tipo, titolo) ->
            val n = notifica(tipo, "Luca è in Studio dalle 15:00")
            assertEquals(TestoNotifica(titolo, "Luca è in Studio dalle 15:00"), testoNotifica(p, n, emptyMap()))
            assertEquals(MainActivity.DEST_STUDIO, Vedetta.destinazionePerTipo(tipo))
            assertEquals(ApriDaNotifica(Schermo.SuPagina(Pagina.Studio), 1), destinazioneDellaRiga(n))
        }
        // La configurazione da approvare si decide in "Da decidere".
        val daApprovare = notifica("studio_da_approvare", "Luca chiede di approvare lo Studio")
        assertEquals(TestoNotifica("Studio da approvare", "Luca chiede di approvare lo Studio"), testoNotifica(p, daApprovare, emptyMap()))
        assertEquals(MainActivity.DEST_DECIDERE, Vedetta.destinazionePerTipo("studio_da_approvare"))
        assertEquals(Schermo.SuScheda(Scheda.DA_DECIDERE), destinazioneDellaRiga(daApprovare).schermo)
    }

    @Test
    fun `la pagina dello Studio si apre sopra la Panoramica e sopravvive alla rotazione`() {
        val dopo = ingresso(MainActivity.DEST_STUDIO, Navigazione().scegli(Scheda.TEMPO))
        assertEquals(listOf(Schermo.SuScheda(Scheda.PANORAMICA), Schermo.SuPagina(Pagina.Studio)), dopo.pila)
        assertEquals(dopo, decodificaNavigazione(codificaNavigazione(dopo)))
        // Le schede restano 4: lo Studio è una pagina.
        assertEquals(4, Scheda.entries.size)
    }

    // --- le anomalie nuove ------------------------------------------------------------------

    @Test
    fun `le anomalie nuove si dicono senza accusare`() {
        fun dettagli(vararg coppie: Pair<String, String>) = buildJsonObject { coppie.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }
        assertEquals(
            "Il computer non risponde durante lo Studio: può essere senza rete, oppure Pactum è stato fermato.",
            descrizioneBuco(p, dettagli("sotto_tipo" to "computer_sparito", "durante" to "studio"), roma, oggi),
        )
        assertEquals(
            "Il computer non risponde durante il blocco dei lavori di casa: può essere senza rete, oppure Pactum è stato fermato.",
            descrizioneBuco(p, dettagli("sotto_tipo" to "computer_sparito", "durante" to "blocco"), roma, oggi),
        )
        assertEquals("Pactum è stato chiuso sul computer durante lo Studio", descrizioneBuco(p, "chiuso_durante_studio"))
        // (0.18) Il promemoria perso sul computer: una frase chiara, senza "Anomalia: ?".
        assertEquals(
            "Sul computer era sparito il promemoria dello Studio: Pactum ha tenuto lo Studio aperto lo stesso",
            descrizioneBuco(p, dettagli("sotto_tipo" to "stato_studio_perso", "durante" to "studio"), roma, oggi),
        )
        assertEquals(
            "Sul computer era sparito il promemoria del blocco dei lavori di casa: Pactum è rimasto chiuso lo stesso",
            descrizioneBuco(p, "stato_blocco_perso"),
        )
        assertEquals(
            "Sul computer Pactum non è riuscito a creare l'attività che lo riapre se si chiude",
            descrizioneBuco(p, dettagli("sotto_tipo" to "guardiano_assente", "stato" to "non_creata"), roma, oggi),
        )
        assertEquals(
            // (correzione 0.18) "disattivata" è spenta, non tolta (parte B).
            "Sul computer era stata spenta l'attività che riapre Pactum: Pactum l'ha riaccesa",
            descrizioneBuco(p, dettagli("sotto_tipo" to "guardiano_assente", "stato" to "disattivata"), roma, oggi),
        )
        assertEquals(
            "Pactum si è chiuso per uno spegnimento del computer che poi non è avvenuto (dalle 15:10 alle 15:40)",
            descrizioneBuco(
                p,
                dettagli(
                    "sotto_tipo" to "programma_chiuso",
                    "causa" to "spegnimento_annullato",
                    "dal" to Instant.parse("2026-10-07T13:10:00Z").toEpochMilli().toString(),
                    "al" to Instant.parse("2026-10-07T13:40:00Z").toEpochMilli().toString(),
                ),
                roma,
                oggi,
            ),
        )
        assertEquals(
            "Accesso ai dati di utilizzo revocato, durante lo Studio",
            descrizioneBuco(p, dettagli("sotto_tipo" to "permesso_revocato", "durante" to "studio"), roma, oggi),
        )
        // Un sotto-tipo che non si conosce resta com'era.
        assertEquals("Anomalia: batteria_strana", descrizioneBuco(p, "batteria_strana"))
    }
}
