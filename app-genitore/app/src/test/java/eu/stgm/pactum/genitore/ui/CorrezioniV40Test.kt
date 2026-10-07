package eu.stgm.pactum.genitore.ui

import eu.stgm.pactum.genitore.dati.BloccoFaccende
import eu.stgm.pactum.genitore.dati.ContenutoStudio
import eu.stgm.pactum.genitore.dati.Faccenda
import eu.stgm.pactum.genitore.dati.Figlio
import eu.stgm.pactum.genitore.dati.ListaComputerStudio
import eu.stgm.pactum.genitore.dati.ListaTelefonoStudio
import eu.stgm.pactum.genitore.dati.Notifica
import eu.stgm.pactum.genitore.dati.RiferimentoDispositivo
import eu.stgm.pactum.genitore.dati.StatiFaccenda
import eu.stgm.pactum.genitore.dati.StudioDelBlocco
import eu.stgm.pactum.genitore.dati.StudioSvolto
import eu.stgm.pactum.genitore.dati.TipiDispositivo
import eu.stgm.pactum.genitore.dati.VoceBlocco
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * (correzione 0.18) Le regole aggiunte dopo la revisione avversaria del pezzo
 * genitore della v4.0: il nome tecnico accanto alle etichette nella configurazione
 * da approvare, le firme tolte, il fuso del patto per le ore dello Studio, lo
 * sblocco promesso solo con un margine per l'orologio, i testi dei lavori con
 * l'ora e durante lo Studio, la domanda "Boccia" legata alla foto vista, la
 * notifica di una foto riconosciuta solo dalla coda del messaggio, l'attività
 * spenta (non tolta) sul computer. Dati sempre finti (Luca, Mamma, Papà).
 */
class CorrezioniV40Test {

    private val p = ParoleDiProva
    private val roma = ZoneId.of("Europe/Rome")
    private val londra = ZoneId.of("Europe/London")

    // --- 1. Un'etichetta finta non nasconde l'app o il programma vero ------------------------

    private val approvata = ContenutoStudio(
        giorni = listOf("lun", "mar", "mer", "gio", "ven"),
        inizio = "15:00",
        chiusuraMinima = "16:00",
        minutiMinimi = 60,
        telefono = ListaTelefonoStudio(
            app = listOf("eu.spaggiari.classevivafamiglia"),
            nomi = mapOf("eu.spaggiari.classevivafamiglia" to "ClasseViva"),
        ),
        computer = ListaComputerStudio(
            programmi = listOf("exe:winword.exe", "sito:classeviva.it"),
            nomi = mapOf("exe:winword.exe" to "Word"),
            firme = mapOf("exe:winword.exe" to "Microsoft Corporation"),
        ),
    )

    private val pcDiLuca = RiferimentoDispositivo(3, "PC di Luca", TipiDispositivo.COMPUTER)
    private val telefonoDiLuca = RiferimentoDispositivo(1, "Telefono di Luca", TipiDispositivo.TELEFONO)

    @Test
    fun `un'etichetta diversa dalla chiave lascia vedere la chiave, sul telefono e sul computer`() {
        // Il caso dei revisori: dal computer, Fortnite col nome "ClasseViva".
        val proposta = approvata.copy(
            telefono = ListaTelefonoStudio(
                app = listOf("eu.spaggiari.classevivafamiglia", "com.epicgames.fortnite"),
                nomi = mapOf(
                    "eu.spaggiari.classevivafamiglia" to "ClasseViva",
                    "com.epicgames.fortnite" to "ClasseViva",
                ),
            ),
            computer = approvata.computer!!.copy(
                programmi = listOf("exe:winword.exe", "sito:classeviva.it", "exe:steam.exe"),
                nomi = mapOf("exe:winword.exe" to "Word", "exe:steam.exe" to "Word"),
            ),
            da = pcDiLuca,
        )
        val righe = righeRichiestaStudio(p, RichiestaStudio(proposta, approvata, 7, null))
        assertTrue(righe.contains("Sul telefono: ClasseViva, ClasseViva (com.epicgames.fortnite)"))
        assertTrue(righe.contains("Aggiunge: ClasseViva (com.epicgames.fortnite)"))
        assertTrue(righe.contains("Aggiunge: Word (steam.exe)"))
        assertTrue(righe.any { it.startsWith("Sul computer:") && "Word (steam.exe)" in it })
        // La lista del telefono cambiata da una proposta arrivata dal computer: lo si dice chiaro.
        assertTrue(
            righe.contains(
                "Attenzione: l'ultima proposta arriva dal computer, ma cambia anche le app del telefono. " +
                    "Guarda il nome tecnico tra parentesi: è quello che conta.",
            ),
        )
        // La stessa proposta dal telefono: niente avviso del computer, il pacchetto si vede lo stesso.
        val dalTelefono = RichiestaStudio(proposta.copy(da = telefonoDiLuca), approvata, 7, null)
        assertFalse(listaTelefonoDalComputer(dalTelefono))
        assertTrue(righeRichiestaStudio(p, dalTelefono).contains("Aggiunge: ClasseViva (com.epicgames.fortnite)"))
        // Dal computer, ma solo la lista del computer: nessun avviso.
        val soloComputer = RichiestaStudio(approvata.copy(computer = proposta.computer, da = pcDiLuca), approvata, 8, null)
        assertFalse(listaTelefonoDalComputer(soloComputer))
    }

    @Test
    fun `alla prima proposta ogni voce con un'etichetta mostra anche la chiave`() {
        val prima = ContenutoStudio(
            giorni = listOf("lun"),
            inizio = "15:00",
            chiusuraMinima = "16:00",
            minutiMinimi = 60,
            telefono = ListaTelefonoStudio(
                app = listOf("com.epicgames.fortnite", "com.duolingo", "gruppo:apk"),
                nomi = mapOf("com.epicgames.fortnite" to "ClasseViva"),
            ),
            computer = ListaComputerStudio(
                programmi = listOf("exe:steam.exe", "exe:notepad.exe", "sito:wikipedia.org"),
                nomi = mapOf("exe:steam.exe" to "Word", "exe:notepad.exe" to "notepad.exe"),
                firme = mapOf("exe:notepad.exe" to "Microsoft Corporation"),
            ),
            da = pcDiLuca,
        )
        val righe = righeRichiestaStudio(p, RichiestaStudio(prima, null, 1, null))
        assertTrue(
            righe.contains("Sul telefono: ClasseViva (com.epicgames.fortnite), com.duolingo, le app installate fuori dal Play Store"),
        )
        // Un nome uguale al file non si ripete; la firma resta accanto.
        assertTrue(
            righe.contains(
                "Sul computer: Word (steam.exe) · notepad.exe, firmato da Microsoft Corporation · wikipedia.org (sito)",
            ),
        )
        // Anche qui: la lista del telefono l'ha mandata il computer.
        assertTrue(righe.any { it.startsWith("Attenzione: l'ultima proposta arriva dal computer") })
        // La configurazione GIÀ approvata resta com'era: solo le etichette.
        assertEquals("Sul telefono: ClasseViva", testoListaTelefono(p, approvata.telefono))
    }

    // --- 11. Una richiesta che cambia solo le firme o le etichette ------------------------------

    @Test
    fun `togliere solo la firma di un programma e un cambio della lista, e si dice in evidenza`() {
        val senzaFirma = approvata.copy(computer = approvata.computer!!.copy(firme = emptyMap()))
        val richiesta = RichiestaStudio(senzaFirma, approvata, 9, null)
        val cambi = cambiStudio(richiesta)
        assertEquals(listOf(CambioFirma("exe:winword.exe", "Microsoft Corporation", null)), cambi.firmeCambiate)
        assertTrue(cambi.liste)
        assertFalse(cambi.orari)
        assertTrue(
            righeRichiestaStudio(p, richiesta).contains(
                "Attenzione: per Word (winword.exe) non si controlla più chi l'ha firmato (prima: Microsoft Corporation). " +
                    "Un altro programma con lo stesso nome passerebbe.",
            ),
        )
        assertEquals(listOf("La nuova lista vale dal prossimo Studio."), righeQuandoValeStudio(p, richiesta))
        // Una firma cambiata, e una messa dove non c'era.
        val altraFirma = RichiestaStudio(
            approvata.copy(computer = approvata.computer!!.copy(firme = mapOf("exe:winword.exe" to "Luca Games"))),
            approvata,
            10,
            null,
        )
        assertTrue(
            righeRichiestaStudio(p, altraFirma)
                .contains("Attenzione: Word (winword.exe) ora deve essere firmato da Luca Games (prima: Microsoft Corporation)."),
        )
        val senzaPrima = approvata.copy(computer = approvata.computer!!.copy(firme = emptyMap()))
        val nuovaFirma = RichiestaStudio(approvata, senzaPrima, 11, null)
        assertTrue(
            righeRichiestaStudio(p, nuovaFirma)
                .contains("Word (winword.exe) ora deve essere firmato da Microsoft Corporation (prima non si controllava)."),
        )
        // Stessa firma con spazi diversi: nessun cambio.
        val stessa = approvata.copy(computer = approvata.computer!!.copy(firme = mapOf("exe:winword.exe" to " Microsoft Corporation ")))
        assertFalse(cambiStudio(RichiestaStudio(stessa, approvata, 12, null)).liste)
    }

    @Test
    fun `un'etichetta cambiata su una voce gia approvata si vede`() {
        val rinominata = approvata.copy(
            telefono = approvata.telefono!!.copy(nomi = mapOf("eu.spaggiari.classevivafamiglia" to "Registro")),
            computer = approvata.computer!!.copy(nomi = mapOf("exe:winword.exe" to "Excel")),
        )
        val richiesta = RichiestaStudio(rinominata, approvata, 13, null)
        val cambi = cambiStudio(richiesta)
        assertTrue(cambi.liste)
        val righe = righeRichiestaStudio(p, richiesta)
        assertTrue(righe.contains("eu.spaggiari.classevivafamiglia ora si chiama «Registro» (prima: «ClasseViva»)."))
        assertTrue(righe.contains("winword.exe ora si chiama «Excel» (prima: «Word»)."))
        // Un'etichetta che manca nella proposta non è un cambio (si mostra quella approvata).
        val senzaNomi = approvata.copy(telefono = approvata.telefono!!.copy(nomi = emptyMap()))
        assertFalse(cambiStudio(RichiestaStudio(senzaNomi, approvata, 14, null)).liste)
    }

    // --- 6 e 10. Le ore dello Studio nel fuso del patto ----------------------------------------

    @Test
    fun `le ore dello Studio sono quelle del patto anche con il telefono in un altro fuso`() {
        val studio = StudioSvolto(
            id = 41,
            giorno = "2026-10-07",
            inizioTs = "2026-10-07T13:00:00+00:00",
            chiudibileDal = "2026-10-07T14:00:00+00:00",
            minutiMinimi = 60,
            minutiAttivita = 42,
            inCorso = true,
        )
        val adesso = Instant.parse("2026-10-07T13:42:00Z")
        val diBase = testoStatoStudio(p, studio, adesso)
        // Il fuso di base è quello del patto: 15:00 e 16:00, come la configurazione.
        assertEquals(testoStatoStudio(p, studio, adesso, roma), diBase)
        assertTrue(diBase.startsWith("Studio dalle 15:00"))
        assertTrue(diBase.endsWith("si chiude dopo le 16:00"))
        // A Londra le ore del telefono sarebbero un'ora indietro: non sono quelle mostrate.
        assertTrue(testoStatoStudio(p, studio, adesso, londra).startsWith("Studio dalle 14:00"))
        assertEquals(testoStudioSvolto(p, studio, null, "Luca", adesso, roma), testoStudioSvolto(p, studio, null, "Luca", adesso))
        // "Oggi lo Studio parte alle …": anche lui nel fuso del patto.
        assertEquals("15:00", oraBreve(Instant.parse("2026-10-07T13:00:00Z")))
        assertEquals("14:00", oraBreve(Instant.parse("2026-10-07T13:00:00Z"), londra))
    }

    // --- 3 e 12. "Si sbloccano" solo con un margine per l'orologio ---------------------------

    private fun lavoroDaApprovare(id: Long) = Faccenda(
        id = id,
        figlioId = 1,
        titolo = "Svuota la lavastoviglie",
        stato = StatiFaccenda.FATTA,
        bloccoDa = "2026-10-07T14:00:00+00:00",
        fotoTs = "2026-10-07T13:50:00+00:00",
        foto = true,
        daApprovare = true,
    )

    private fun blocco(attivo: Boolean, rimandato: Boolean? = false, vararg aperti: Pair<Long, String?>) = BloccoFaccende(
        attivo = attivo,
        rimandato = rimandato,
        daFare = aperti.map { (id, da) -> VoceBlocco(id = id, titolo = "L$id", bloccoDa = da) },
    )

    @Test
    fun `con l'orologio del telefono indietro non si promette lo sblocco`() {
        val a = lavoroDaApprovare(1)
        // A da approvare dalle 16:00, B da fare dalle 16:30. Per il server sono le 16:31
        // (B blocca già); il telefono di Mamma segna le 16:29.
        val server = blocco(true, false, 1L to "2026-10-07T14:00:00+00:00", 2L to "2026-10-07T14:30:00+00:00")
        val telefonoIndietro = Instant.parse("2026-10-07T14:29:00Z")
        assertEquals(EffettoApprovazione.NESSUNO, effettoApprovazione(a, server, telefonoIndietro))
        // Orologio indietro di 10 minuti: A (16:00) passato per il server, B... il caso dei
        // revisori al contrario: approvo B, e A per il telefono sembra futuro.
        val b = lavoroDaApprovare(2)
        val alContrario = blocco(true, false, 1L to "2026-10-07T14:00:00+00:00", 2L to "2026-10-07T13:30:00+00:00")
        assertEquals(EffettoApprovazione.NESSUNO, effettoApprovazione(b, alContrario, Instant.parse("2026-10-07T13:55:00Z")))
        // Un altro lavoro che parte fra più di 15 minuti: lì sì, si sblocca adesso.
        val lontano = blocco(true, false, 1L to "2026-10-07T14:00:00+00:00", 2L to "2026-10-07T15:00:00+00:00")
        assertEquals(EffettoApprovazione.SBLOCCA, effettoApprovazione(a, lontano, Instant.parse("2026-10-07T14:40:00Z")))
        // Fra 14 minuti: troppo vicino, niente promesse.
        assertEquals(EffettoApprovazione.NESSUNO, effettoApprovazione(a, lontano, Instant.parse("2026-10-07T14:46:00Z")))
        // Un altro senza ora leggibile: niente promesse.
        assertEquals(EffettoApprovazione.NESSUNO, effettoApprovazione(a, blocco(true, false, 1L to "2026-10-07T14:00:00+00:00", 2L to null), Instant.parse("2026-10-07T14:40:00Z")))
        // Il lavoro non è tra gli aperti del blocco (elenco vecchio): niente promesse.
        assertEquals(EffettoApprovazione.NESSUNO, effettoApprovazione(a, blocco(true, false, 2L to "2026-10-07T13:00:00+00:00"), Instant.parse("2026-10-07T14:40:00Z")))
    }

    @Test
    fun `dopo il si lo sblocco si dice solo se il blocco riletto e spento`() {
        val spento = blocco(false, false)
        val ancoraAttivo = blocco(true, false, 3L to "2026-10-07T14:30:00+00:00")
        assertEquals(EffettoApprovazione.SBLOCCA, effettoConfermato(EffettoApprovazione.SBLOCCA, spento))
        assertEquals(EffettoApprovazione.NESSUNO, effettoConfermato(EffettoApprovazione.SBLOCCA, ancoraAttivo))
        // Rilettura fallita, o server più vecchio: non si dice.
        assertEquals(EffettoApprovazione.NESSUNO, effettoConfermato(EffettoApprovazione.SBLOCCA, null))
        assertEquals(EffettoApprovazione.NESSUNO, effettoConfermato(EffettoApprovazione.SBLOCCA, spento.copy(rimandato = null)))
        assertEquals(EffettoApprovazione.NON_PARTE_A_FINE_STUDIO, effettoConfermato(EffettoApprovazione.NON_PARTE_A_FINE_STUDIO, spento))
        assertEquals(EffettoApprovazione.NESSUNO, effettoConfermato(EffettoApprovazione.NESSUNO, spento))
    }

    // --- 7 e 8. I testi del blocco quando si danno i lavori, e durante lo Studio ----------------

    private val alle1510 = ZonedDateTime.of(2026, 10, 7, 15, 10, 0, 0, roma)

    @Test
    fun `con un'ora e la v4 il testo dice che serve l'approvazione prima`() {
        val oggi = inizioBlocco(LocalTime.of(16, 0), alle1510)
        assertEquals(
            "Il blocco parte oggi, mercoledì 07/10, alle 16:00, se a quell'ora un genitore non ha ancora approvato la foto di ogni lavoro. " +
                "Può mandare le foto anche prima: il blocco non parte se le approvate prima.",
            testoInizioBlocco(p, oggi, "Luca", conApprovazione = true),
        )
        val domani = inizioBlocco(LocalTime.of(8, 0), alle1510)
        assertEquals(
            "Le 08:00 di oggi sono già passate: il blocco parte domani, giovedì 08/10, alle 08:00, se a quell'ora un genitore " +
                "non ha ancora approvato la foto di ogni lavoro. Può mandare le foto anche prima: il blocco non parte se le approvate prima.",
            testoInizioBlocco(p, domani, "Luca", conApprovazione = true),
        )
        // Server più vecchio: i testi della v3.9, come prima.
        assertTrue(testoInizioBlocco(p, oggi, "Luca").endsWith("se a quell'ora non li ha ancora fatti tutti. Può farli anche prima."))
    }

    @Test
    fun `durante lo Studio il blocco parte a fine Studio, per i lavori dati e per una bocciatura`() {
        val inCorso = BloccoFaccende(attivo = false, rimandato = false, studio = StudioDelBlocco(inCorso = true, id = 41))
        val rimandato = BloccoFaccende(attivo = true, rimandato = true)
        assertTrue(inStudio(inCorso))
        assertTrue(inStudio(rimandato))
        assertFalse(inStudio(BloccoFaccende(attivo = true, rimandato = false)))
        assertFalse(inStudio(null))
        assertEquals(
            "Luca ora è in Studio: il blocco parte a fine Studio. Poi telefono e computer restano bloccati finché un genitore non approva la foto di ogni lavoro.",
            testoInizioBlocco(p, null, "Luca", conApprovazione = true, inStudio = true),
        )
        assertTrue(testoInizioBlocco(p, null, " ", conApprovazione = true, inStudio = true).startsWith("Tuo figlio ora è in Studio"))
        // Con un'ora: la stessa frase di prima, più il rinvio se a quell'ora è ancora in Studio.
        assertTrue(
            testoInizioBlocco(p, inizioBlocco(LocalTime.of(16, 0), alle1510), "Luca", conApprovazione = true, inStudio = true)
                .endsWith("le approvate prima. Se a quell'ora è ancora in Studio, il blocco parte a fine Studio."),
        )
        // Fuori dallo Studio, "subito" come prima.
        assertTrue(testoInizioBlocco(p, null, "Luca", conApprovazione = true).startsWith("Il blocco parte appena li dai"))
        // Un server che lo Studio non lo conosce: i testi di prima anche "in Studio".
        assertTrue(testoInizioBlocco(p, null, "Luca", conApprovazione = false, inStudio = true).startsWith("Il blocco parte appena li dai"))
        // La bocciatura.
        assertEquals(
            "Il lavoro torna da fare. Luca ora è in Studio: il blocco parte a fine Studio. Luca legge il perché, se lo scrivi.",
            testoDomandaBoccia(p, "Luca", inStudio = true),
        )
        assertEquals(
            "Il lavoro torna da fare e il blocco riparte subito. Luca legge il perché, se lo scrivi.",
            testoDomandaBoccia(p, "Luca"),
        )
        assertTrue(testoDomandaBoccia(p, null, inStudio = true).startsWith("Il lavoro torna da fare. Tuo figlio ora è in Studio"))
    }

    // --- 4. "Boccia" vale solo per la foto vista -------------------------------------------------

    @Test
    fun `la domanda boccia si chiude se intanto la foto e cambiata`() {
        val x = lavoroDaApprovare(5)
        assertTrue(bocciaAncoraLaStessaFoto(x, x.fotoTs))
        // Papà ha bocciato e Luca ha rifatto la foto: un'altra foto, che Mamma non ha visto.
        val y = x.copy(fotoTs = "2026-10-07T15:20:00+00:00")
        assertFalse(bocciaAncoraLaStessaFoto(y, x.fotoTs))
        // Bocciato e non ancora rifatto: non è più fatto.
        assertFalse(bocciaAncoraLaStessaFoto(x.copy(stato = StatiFaccenda.DA_FARE, fotoTs = null), x.fotoTs))
        // Approvato nel frattempo: resta "fatta" con la stessa foto, il server dirà non_bocciabile.
        assertTrue(bocciaAncoraLaStessaFoto(x.copy(daApprovare = false, confermataTs = "2026-10-07T15:00:00+00:00"), x.fotoTs))
        assertEquals("La foto è cambiata: guardala di nuovo prima di bocciarla.", p.testo(eu.stgm.pactum.genitore.R.string.boccia_foto_cambiata))
    }

    // --- 9. La foto da approvare si riconosce dalla coda del messaggio ----------------------------

    @Test
    fun `un titolo con approvazione e un server vecchio non promettono l'approvazione`() {
        // Server v3.9: niente dopo il titolo.
        assertFalse(fotoDaApprovareNelMessaggio("Luca ha fatto «Firma l'approvazione della gita»"))
        // Anche con un titolo che contiene «»».
        assertFalse(fotoDaApprovareNelMessaggio("Luca ha fatto «Prova » approvazione»"))
        // Server v4.0: la coda del contratto.
        assertTrue(fotoDaApprovareNelMessaggio("Luca ha mandato la foto di «Firma l'approvazione della gita»: aspetta la vostra approvazione"))
        assertTrue(fotoDaApprovareNelMessaggio("Luca ha mandato la foto di «Rifai il letto»: aspetta che lo approviate"))
        // E la notifica intera.
        val luca = Figlio(id = 1, nome = "Luca")
        val v39 = Notifica(
            id = 9,
            tipo = "faccenda_fatta",
            messaggio = "Luca ha fatto «Firma l'approvazione della gita»",
            payload = buildJsonObject {
                put("faccenda_id", JsonPrimitive("5"))
                put("titolo", JsonPrimitive("Firma l'approvazione della gita"))
            },
            tsServer = "2026-10-07T14:10:00+00:00",
            figlioId = 1,
        )
        assertEquals(
            TestoNotifica("Lavoro fatto", "Luca ha fatto «Firma l'approvazione della gita»: tocca per vedere la foto."),
            testoNotifica(p, v39, emptyMap(), listOf(luca)),
        )
    }

    // --- 13. L'attività del computer spenta, non tolta -------------------------------------------

    @Test
    fun `guardiano disattivato e detto spento, mancante e detto tolto`() {
        fun dettagli(stato: String) = buildJsonObject {
            put("sotto_tipo", JsonPrimitive("guardiano_assente"))
            put("stato", JsonPrimitive(stato))
        }
        val oggi = java.time.LocalDate.of(2026, 10, 7)
        assertEquals(
            "Sul computer era stata spenta l'attività che riapre Pactum: Pactum l'ha riaccesa",
            descrizioneBuco(p, dettagli("disattivata"), roma, oggi),
        )
        assertEquals(
            "Sul computer era stata tolta l'attività che riapre Pactum: Pactum l'ha rimessa",
            descrizioneBuco(p, dettagli("mancante"), roma, oggi),
        )
    }
}
