package eu.stgm.pactum.genitore.ui

import androidx.compose.runtime.saveable.SaverScope
import eu.stgm.pactum.design.TemaSessione
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.CodiciErrore
import eu.stgm.pactum.genitore.dati.DispositivoFinestra
import eu.stgm.pactum.genitore.dati.Figlio
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.MASSIMO_MOTIVAZIONE_SESSIONE
import eu.stgm.pactum.genitore.dati.ModificaSessione
import eu.stgm.pactum.genitore.dati.Notifica
import eu.stgm.pactum.genitore.dati.RiferimentoDispositivo
import eu.stgm.pactum.genitore.dati.Sessione
import eu.stgm.pactum.genitore.dati.SessioneSvolta
import eu.stgm.pactum.genitore.rete.PostinoClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * (0.11) Le sessioni del figlio (contratto v3.5) viste dal genitore: che cosa
 * aspetta una decisione, che cosa cambia un cambio, su quale versione si risponde,
 * come si racconta una sessione fatta — e le frasi VERE (strings.xml) di card,
 * notifiche ed esiti. Se qui si sbaglia, il padre approva una lista che non ha
 * visto, o legge "chiusa prima" su una sessione durata tutta. (0.12) Accanto al
 * nome di ogni sessione, la prima emoji del suo tema.
 */
class SessioniTest {

    private val p = ParoleDiProva

    private val classeviva = "eu.spaggiari.classevivafamiglia"
    private val classroom = "com.google.android.apps.classroom"
    private val duolingo = "com.duolingo"
    private val youtube = "com.google.android.youtube"
    private val tiktok = "com.zhiliaoapp.musically"

    private val nomiStudio = mapOf(
        classeviva to "ClasseViva",
        classroom to "Classroom",
        GRUPPO_APK to "App installate da APK",
    )

    /** "Studio" sul telefono 1: ClasseViva, Classroom e le app da APK. */
    private fun sessione(
        id: Long = 3,
        nome: String = "Studio",
        app: List<String> = listOf(classeviva, classroom, GRUPPO_APK),
        stato: String = "in_attesa",
        modifica: ModificaSessione? = null,
        nomi: Map<String, String> = nomiStudio,
    ) = Sessione(
        id = id,
        dispositivoId = 1,
        nome = nome,
        app = app,
        nomi = nomi,
        stato = stato,
        modificaInAttesa = modifica,
    )

    // --- che cosa aspetta il genitore ----------------------------------------------------

    @Test
    fun `una sessione in attesa chiede di essere approvata, una approvata con un cambio chiede il cambio`() {
        val lista = sessioniDaApprovare(
            listOf(
                sessione(id = 3),
                sessione(id = 4, nome = "Lavoro", stato = "approvata"),
                sessione(id = 5, stato = "approvata", modifica = ModificaSessione(app = listOf(classeviva, duolingo))),
                sessione(id = 6, stato = "rifiutata"),
                sessione(id = 7, stato = "stato_del_futuro"),
                // Un cambio esiste solo su una sessione approvata: su un altro stato non si mostra.
                sessione(id = 8, stato = "rifiutata", modifica = ModificaSessione(app = listOf(youtube))),
            ),
        )
        // Senza orari, dalla più recente; approvate senza cambio, non approvate e stati sconosciuti non aspettano niente.
        assertEquals(listOf(5L, 3L), lista.map { it.sessione.id })
        val (cambio, nuova) = lista
        assertTrue(cambio.cambio)
        assertEquals(listOf(classeviva, duolingo), cambio.app)
        // Il cambio non tocca il nome: resta quello di adesso.
        assertEquals("Studio", cambio.nome)
        assertFalse(nuova.cambio)
        assertEquals(listOf(classeviva, classroom, GRUPPO_APK), nuova.app)
        assertNull(nuova.differenze)
        // Una sessione ripetuta nella lista fa una card sola.
        assertEquals(1, sessioniDaApprovare(listOf(sessione(), sessione())).size)
    }

    @Test
    fun `le card vanno dalla richiesta piu recente, un cambio dal suo richiesta_ts`() {
        val nuovaDiIeri = sessione(id = 9).copy(creataTs = "2026-09-30T10:00:00+00:00")
        val cambioDiOggi = sessione(
            id = 4,
            stato = "approvata",
            modifica = ModificaSessione(app = listOf(classeviva), richiestaTs = "2026-10-01T09:00:00+00:00"),
        ).copy(creataTs = "2026-09-01T10:00:00+00:00")
        val nuovaDiStamattina = sessione(id = 7).copy(creataTs = "2026-10-01T08:00:00+00:00")
        val senzaOrario = sessione(id = 12)
        assertEquals(
            listOf(4L, 7L, 9L, 12L),
            sessioniDaApprovare(listOf(nuovaDiIeri, cambioDiOggi, nuovaDiStamattina, senzaOrario)).map { it.sessione.id },
        )
        // Un cambio senza richiesta_ts vale da quando è nata la sessione.
        val cambioSenzaOra = cambioDiOggi.copy(modificaInAttesa = ModificaSessione(app = listOf(classeviva)))
        assertEquals(Instant.parse("2026-09-01T10:00:00Z"), chiestaAlle(checkNotNull(richiestaInAttesa(cambioSenzaOra))))
    }

    @Test
    fun `le sessioni di un telefono scollegato non fanno card - sono fra le non piu valide, non fra le approvate`() {
        val scollegati = setOf(2L)
        val vecchia = sessione(id = 10, nome = "Vecchia").copy(dispositivoId = 2)
        val gioco = sessione(id = 11, nome = "Gioco", stato = "approvata").copy(dispositivoId = 2)
        val musica = sessione(
            id = 12,
            nome = "Musica",
            stato = "approvata",
            modifica = ModificaSessione(app = listOf(classeviva)),
        ).copy(dispositivoId = 2)
        val sulTelefonoBuono = sessione(id = 3)
        val tutte = listOf(vecchia, gioco, musica, sulTelefonoBuono, sessione(id = 4, nome = "Lavoro", stato = "approvata"))
        // Come il conto del server: nessuna card per quelle del telefono scollegato.
        assertEquals(listOf(3L), sessioniDaApprovare(tutte, scollegati = scollegati).map { it.sessione.id })
        // Fra le approvate (che si possono avviare) solo quelle dei telefoni collegati.
        assertEquals(listOf(4L), sessioniApprovate(tutte, scollegati).map { it.id })
        // Le non più valide, in ordine di nome.
        assertEquals(listOf(11L, 12L, 10L), sessioniNonPiuValide(tutte, scollegati).map { it.id })
        assertEquals(
            "✨ Vecchia · non più valida: il telefono è scollegato",
            p.testo(R.string.sessione_non_piu_valida, nomeSessioneConEmoji(p, "Vecchia")),
        )
        // Il telefono si riconosce anche dal riferimento allegato.
        assertTrue(suTelefonoScollegato(Sessione(id = 1, dispositivo = RiferimentoDispositivo(2, "Vecchio", "telefono")), scollegati))
        assertFalse(suTelefonoScollegato(sulTelefonoBuono, scollegati))
    }

    @Test
    fun `appena decisa da qui sparisce dai dati letti prima, e torna con una lettura partita dopo`() {
        val decise = mapOf(3L to SessioneDecisa(figlioId = 2, alle = 1_000))
        listOf(900L, 1_000L, null).forEach { letta ->
            assertTrue("lettaAlle=$letta", sessioniDaApprovare(listOf(sessione()), decise, letta).isEmpty())
        }
        assertEquals(1, sessioniDaApprovare(listOf(sessione()), decise, lettaAlle = 1_001).size)
        // La finestra di quel figlio, letta con una lettura partita dopo, chiude il ponte;
        // le decisioni sugli altri figli restano finché non si rilegge la loro.
        val conAltro = decise + (9L to SessioneDecisa(figlioId = 7, alle = 1_000))
        assertEquals(setOf(9L), deciseDaTenere(conAltro, figlioId = 2, letturaIniziataAlle = 1_001).keys)
        assertEquals(setOf(3L, 9L), deciseDaTenere(conAltro, figlioId = 2, letturaIniziataAlle = 999).keys)
    }

    // --- che cosa cambia un cambio ---------------------------------------------------------

    @Test
    fun `un cambio dice le app aggiunte, quelle tolte, quelle che restano e il nome nuovo`() {
        val conCambio = sessione(
            stato = "approvata",
            app = listOf(classeviva, youtube, GRUPPO_APK),
            modifica = ModificaSessione(nome = "Compiti", app = listOf(classeviva, duolingo, GRUPPO_APK)),
        )
        val differenze = checkNotNull(cambioSessione(conCambio))
        assertEquals(listOf(duolingo), differenze.aggiunte)
        assertEquals(listOf(youtube), differenze.tolte)
        assertEquals(listOf(classeviva, GRUPPO_APK), differenze.restano)
        assertEquals("Compiti", differenze.nuovoNome)
        assertFalse(differenze.vuoto)
        assertFalse(differenze.soloNomi)
        // La richiesta porta il nome e la lista come sarebbero.
        val richiesta = checkNotNull(richiestaInAttesa(conCambio))
        assertEquals("Compiti", richiesta.nome)
        assertEquals(listOf(classeviva, duolingo, GRUPPO_APK), richiesta.app)
    }

    @Test
    fun `un campo del cambio che manca vale come adesso, e senza cambio non c'e niente`() {
        val soloNome = checkNotNull(cambioSessione(sessione(stato = "approvata", modifica = ModificaSessione(nome = "Compiti"))))
        assertTrue(soloNome.aggiunte.isEmpty())
        assertTrue(soloNome.tolte.isEmpty())
        assertEquals(listOf(classeviva, classroom, GRUPPO_APK), soloNome.restano)
        assertEquals("Compiti", soloNome.nuovoNome)
        // Stesso nome (spazi a parte), stesse app in un altro ordine: non cambia niente di vero.
        val stesso = sessione(
            stato = "approvata",
            modifica = ModificaSessione(nome = " Studio ", app = listOf(GRUPPO_APK, classroom, classeviva)),
        )
        assertTrue(checkNotNull(cambioSessione(stesso)).vuoto)
        assertNull(cambioSessione(sessione(stato = "approvata")))
    }

    @Test
    fun `doppioni, spazi e il gruppo in maiuscolo non fanno differenze finte`() {
        val conCambio = sessione(
            stato = "approvata",
            app = listOf(classeviva, GRUPPO_APK),
            modifica = ModificaSessione(app = listOf(" $classeviva", classeviva, "GRUPPO:APK", "")),
        )
        assertTrue(checkNotNull(cambioSessione(conCambio)).vuoto)
    }

    @Test
    fun `un cambio che rinomina solo le app lo dice, col nome di prima e quello nuovo`() {
        val rinomina = sessione(
            stato = "approvata",
            app = listOf(classeviva, classroom),
            modifica = ModificaSessione(
                app = listOf(classeviva, classroom),
                nomi = mapOf(classeviva to "Classe Viva Famiglia", classroom to "Classroom"),
            ),
        )
        val differenze = checkNotNull(cambioSessione(rinomina))
        assertEquals(listOf(NomeCambiato(classeviva, "ClasseViva", "Classe Viva Famiglia")), differenze.nomiCambiati)
        assertTrue(differenze.soloNomi)
        assertFalse(differenze.vuoto)
        assertEquals(
            listOf("Cambiano solo i nomi delle app: «ClasseViva» → «Classe Viva Famiglia»"),
            righeCambioSessione(p, checkNotNull(richiestaInAttesa(rinomina))),
        )
        // Insieme ad altro: "anche".
        val misto = rinomina.copy(
            modificaInAttesa = ModificaSessione(
                app = listOf(classeviva, classroom, duolingo),
                nomi = mapOf(classeviva to "Classe Viva Famiglia", duolingo to "Duolingo"),
            ),
        )
        assertEquals(
            listOf("Aggiunge Duolingo", "Cambiano anche i nomi delle app: «ClasseViva» → «Classe Viva Famiglia»"),
            righeCambioSessione(p, checkNotNull(richiestaInAttesa(misto))),
        )
        // Un cambio che non cambia niente lo dice.
        val niente = rinomina.copy(modificaInAttesa = ModificaSessione(app = listOf(classroom, classeviva), nomi = nomiStudio))
        assertEquals(listOf("Non cambia niente: stesse app, stesso nome."), righeCambioSessione(p, checkNotNull(richiestaInAttesa(niente))))
    }

    @Test
    fun `un'app rinominata in un'altra cosa - prima di tutto il nome che le danno i dati d'uso`() {
        val travestita = sessione(
            stato = "approvata",
            app = listOf(tiktok),
            nomi = mapOf(tiktok to "TikTok"),
            modifica = ModificaSessione(app = listOf(tiktok), nomi = mapOf(tiktok to "Calcolatrice")),
        )
        val nomiDallUso = mapOf(tiktok to "TikTok")
        val cambiato = checkNotNull(cambioSessione(travestita)).nomiCambiati.single()
        assertEquals("TikTok: «TikTok» → «Calcolatrice»", testoNomeCambiato(p, cambiato, nomiDallUso))
        // Senza i dati d'uso, o se coincidono col nome nuovo, solo il cambio.
        assertEquals("«TikTok» → «Calcolatrice»", testoNomeCambiato(p, cambiato))
        assertEquals("«TikTok» → «Calcolatrice»", testoNomeCambiato(p, cambiato, mapOf(tiktok to "calcolatrice")))
        assertEquals(
            listOf("Cambiano solo i nomi delle app: TikTok: «TikTok» → «Calcolatrice»"),
            righeCambioSessione(p, checkNotNull(richiestaInAttesa(travestita)), nomiDallUso),
        )
    }

    // --- la versione: il genitore non approva mai una lista che non ha visto ---------------

    @Test
    fun `la domanda aperta vale solo sulla versione vista, e si salva con quella`() {
        val domanda = DomandaSessione(3, "approva", 7)
        val card = checkNotNull(richiestaInAttesa(sessione().copy(versione = 7)))
        assertEquals(StatoDomanda.VALIDA, statoDomanda(domanda, listOf(card)))
        // Il giro di ogni minuto ha portato la versione 8: la domanda si chiude, e lo si dice.
        val cambiata = checkNotNull(richiestaInAttesa(sessione(app = listOf(classeviva, youtube)).copy(versione = 8)))
        assertEquals(StatoDomanda.CAMBIATA, statoDomanda(domanda, listOf(cambiata)))
        assertEquals(
            "Tuo figlio l'ha appena cambiata: guardala di nuovo.",
            p.testo(checkNotNull(messaggioDomandaChiusa(StatoDomanda.CAMBIATA))),
        )
        // Non aspetta più (decisa altrove, eliminata, telefono scollegato).
        assertEquals(StatoDomanda.NON_PIU_DA_DECIDERE, statoDomanda(domanda, emptyList()))
        assertEquals(
            "Non c'è più niente da decidere: è già stata decisa, o tuo figlio l'ha eliminata.",
            p.testo(checkNotNull(messaggioDomandaChiusa(StatoDomanda.NON_PIU_DA_DECIDERE))),
        )
        assertNull(messaggioDomandaChiusa(StatoDomanda.VALIDA))
        // Si salva id, gesto e versione (Android può chiudere l'app): ritrovata, vale solo su quella versione.
        val scope = SaverScope { true }
        val salvata = checkNotNull(with(SalvaDomandaSessione) { scope.save(domanda) })
        assertEquals(domanda, SalvaDomandaSessione.restore(salvata))
        assertNull(with(SalvaDomandaSessione) { scope.save(null) })
        assertNull(SalvaDomandaSessione.restore(listOf("non", "una", "domanda")))
    }

    @Test
    fun `richiesta cambiata - se aspetta ancora, la card nuova prende il posto della vecchia`() {
        val mostrata = checkNotNull(richiestaInAttesa(sessione().copy(versione = 2)))
        // Il figlio ha aggiunto YouTube: niente deciso, e la sessione di adesso si mostra.
        val adesso = sessione(app = listOf(classeviva, classroom, GRUPPO_APK, youtube)).copy(versione = 3)
        val esito = esitoRichiestaCambiata(mostrata, adesso)
        assertEquals(EsitoSessione.NonDecisa(CodiciErrore.RICHIESTA_CAMBIATA, adesso = adesso), esito)
        assertEquals(
            "Tuo figlio ha appena cambiato la richiesta: guardala di nuovo prima di decidere.",
            p.testo(messaggioEsitoSessione(esito)),
        )
        // La card non sparisce: al suo posto c'è quella nuova, con la versione nuova.
        assertFalse(sessioneDaTogliere(esito))
        assertTrue(daRileggereDopo(esito))
        val mostrate = listOf(sessione(id = 9, stato = "approvata"), sessione().copy(versione = 2))
        val card = sessioniDaApprovare(sostituisciSessione(mostrate, adesso)).single()
        assertEquals(3, card.sessione.versione)
        assertTrue(youtube in card.app)
        // Senza la sessione nel 409: si dice lo stesso, e la card si nasconde finché non si rilegge.
        val senza = esitoRichiestaCambiata(mostrata, null)
        assertEquals(EsitoSessione.NonDecisa(CodiciErrore.RICHIESTA_CAMBIATA), senza)
        assertTrue(sessioneDaTogliere(senza))
    }

    @Test
    fun `richiesta cambiata perche qualcuno ha gia deciso - si dice com'e finita, non che il figlio l'ha cambiata`() {
        // La versione aumenta anche a ogni decisione: l'altro genitore ha già risposto.
        val nuova = checkNotNull(richiestaInAttesa(sessione()))
        val giaApprovata = sessione(stato = "approvata").copy(versione = 2)
        val esito = esitoRichiestaCambiata(nuova, giaApprovata)
        assertEquals(EsitoSessione.NonDecisa(CodiciErrore.NIENTE_DA_DECIDERE, SessioneRiletta.APPROVATA, giaApprovata), esito)
        assertEquals("Era già stata approvata.", p.testo(messaggioEsitoSessione(esito)))
        assertTrue(sessioneDaTogliere(esito))
        assertEquals(
            "Era già stata rifiutata.",
            p.testo(messaggioEsitoSessione(esitoRichiestaCambiata(nuova, sessione(stato = "rifiutata")))),
        )
        val cambio = checkNotNull(
            richiestaInAttesa(sessione(stato = "approvata", modifica = ModificaSessione(app = listOf(classeviva)))),
        )
        assertEquals(
            "Il cambio era già stato deciso.",
            p.testo(messaggioEsitoSessione(esitoRichiestaCambiata(cambio, sessione(stato = "approvata")))),
        )
    }

    @Test
    fun `la sessione detta dal server prende il posto di quella mostrata, solo se non e piu vecchia`() {
        val prima = listOf(sessione(id = 3).copy(versione = 1), sessione(id = 4, nome = "Lavoro", stato = "approvata"))
        val approvata = sessione(id = 3, stato = "approvata").copy(versione = 2)
        assertEquals(listOf(approvata, prima[1]), sostituisciSessione(prima, approvata))
        assertEquals(prima, sostituisciSessione(prima, sessione(id = 99).copy(versione = 5)))
        // Approvata: nessuna card per lei.
        assertEquals(emptyList<Long>(), sessioniDaApprovare(sostituisciSessione(prima, approvata)).map { it.sessione.id })
        // Una risposta arrivata dopo una lettura più fresca non riporta indietro la sessione.
        val fresca = listOf(sessione(id = 3).copy(versione = 5))
        assertEquals(fresca, sostituisciSessione(fresca, approvata))
        val stessaVersione = sessione(id = 3, nome = "Compiti").copy(versione = 5)
        assertEquals(listOf(stessaVersione), sostituisciSessione(fresca, stessaVersione))
        // Senza versione non si sostituisce niente.
        assertEquals(fresca, sostituisciSessione(fresca, sessione(id = 3, stato = "approvata")))
        // L'esito porta la sessione di adesso, quando il server l'ha detta.
        assertEquals(approvata, sessioneDopo(EsitoSessione.Decisa("approva", cambio = false, adesso = approvata)))
        assertNull(sessioneDopo(EsitoSessione.NonDecisa(null)))
    }

    @Test
    fun `com'e finita una sessione su cui non c'era piu niente da decidere`() {
        assertEquals(SessioneRiletta.APPROVATA, sessioneRiletta(3, cambio = false, listOf(sessione(stato = "approvata"))))
        assertEquals(SessioneRiletta.NON_APPROVATA, sessioneRiletta(3, cambio = false, listOf(sessione(stato = "rifiutata"))))
        assertEquals(SessioneRiletta.CAMBIO_DECISO, sessioneRiletta(3, cambio = true, listOf(sessione(stato = "approvata"))))
        assertEquals(SessioneRiletta.ELIMINATA, sessioneRiletta(3, cambio = false, emptyList()))
        assertEquals(SessioneRiletta.CAMBIATA, sessioneRiletta(3, cambio = true, listOf(sessione(stato = "in_attesa"))))
        assertEquals(SessioneRiletta.NON_SI_SA, sessioneRiletta(3, cambio = false, null))
        assertEquals(SessioneRiletta.NON_SI_SA, sessioneRiletta(3, cambio = false, listOf(sessione(stato = "stato_del_futuro"))))
    }

    @Test
    fun `la card sparisce dopo una decisione o se non c'e piu niente da decidere, non dopo una rete caduta`() {
        assertTrue(sessioneDaTogliere(EsitoSessione.Decisa("approva", cambio = false)))
        assertTrue(sessioneDaTogliere(EsitoSessione.Decisa("rifiuta", cambio = true)))
        assertTrue(sessioneDaTogliere(EsitoSessione.NonDecisa(CodiciErrore.NIENTE_DA_DECIDERE)))
        assertTrue(sessioneDaTogliere(EsitoSessione.NonDecisa(CodiciErrore.NON_TROVATO)))
        // Niente da decidere, ma il figlio l'ha già cambiata: la card nuova subito, non nascosta.
        val nuovaRichiesta = sessione(app = listOf(duolingo)).copy(versione = 4)
        assertFalse(
            sessioneDaTogliere(EsitoSessione.NonDecisa(CodiciErrore.NIENTE_DA_DECIDERE, SessioneRiletta.CAMBIATA, nuovaRichiesta)),
        )
        assertTrue(
            sessioneDaTogliere(
                EsitoSessione.NonDecisa(CodiciErrore.NIENTE_DA_DECIDERE, SessioneRiletta.APPROVATA, sessione(stato = "approvata")),
            ),
        )
        // Un telefono scollegato nel frattempo: la card resta finché la rilettura non la toglie.
        assertFalse(sessioneDaTogliere(EsitoSessione.NonDecisa(CodiciErrore.DISPOSITIVO_REVOCATO)))
        assertFalse(sessioneDaTogliere(EsitoSessione.NonDecisa(null)))
        assertFalse(sessioneDaTogliere(EsitoSessione.NonDecisa(CodiciErrore.SERVER_DA_AGGIORNARE)))
        assertFalse(sessioneDaTogliere(EsitoSessione.NonDecisa(PostinoClient.PARAMETRI_NON_VALIDI)))
        // Si rilegge solo quando sul server qualcosa è cambiato, o si è scoperto che lo era.
        assertTrue(daRileggereDopo(EsitoSessione.Decisa("approva", cambio = false)))
        listOf(CodiciErrore.NIENTE_DA_DECIDERE, CodiciErrore.NON_TROVATO, CodiciErrore.DISPOSITIVO_REVOCATO).forEach {
            assertTrue(it, daRileggereDopo(EsitoSessione.NonDecisa(it)))
        }
        listOf(null, CodiciErrore.SERVER_DA_AGGIORNARE, PostinoClient.PARAMETRI_NON_VALIDI).forEach {
            assertFalse("$it", daRileggereDopo(EsitoSessione.NonDecisa(it)))
        }
    }

    // --- le app a parole ---------------------------------------------------------------------

    @Test
    fun `le app coi loro nomi in ordine, il gruppo in fondo, le app senza nome con la chiave a parte`() {
        val app = appDellaSessione(listOf(classroom, GRUPPO_APK, classeviva, "com.sconosciuta", "com.altra"), nomiStudio)
        assertEquals(listOf(NomeApp("ClasseViva"), NomeApp("Classroom")), app.nomi)
        // Sulla card da approvare si mostra la chiave; nelle righe si contano.
        assertEquals(listOf("com.sconosciuta", "com.altra"), app.senzaNome)
        assertTrue(app.gruppoApk)
        assertEquals(
            "ClasseViva, Classroom, 2 app senza nome e le app installate fuori dal Play Store",
            elencoAppSessione(p, app),
        )
        assertEquals("App senza nome: com.sconosciuta", p.testo(R.string.sessione_app_pacchetto, "com.sconosciuta"))
        // I nomi dei dati d'uso valgono per le app che la sessione non nomina.
        assertEquals(listOf(NomeApp("Duolingo")), appDellaSessione(listOf(duolingo), emptyMap(), mapOf(duolingo to "Duolingo")).nomi)
        // Un "nome" che è il pacchetto stesso (o una chiave) non è un nome.
        assertEquals(listOf(duolingo), appDellaSessione(listOf(duolingo), mapOf(duolingo to duolingo)).senzaNome)
        assertEquals(listOf(duolingo), appDellaSessione(listOf(duolingo), mapOf(duolingo to "gruppo:apk")).senzaNome)
    }

    @Test
    fun `un'app che la sessione chiama in un altro modo si mostra col nome dei dati d'uso, e accanto quello della sessione`() {
        val app = appDellaSessione(listOf(tiktok, classeviva), mapOf(tiktok to "Calcolatrice", classeviva to "classeviva"), mapOf(tiktok to "TikTok", classeviva to "ClasseViva"))
        // Stesso nome a parte le maiuscole: uno solo.
        assertEquals(listOf(NomeApp("ClasseViva"), NomeApp("TikTok", nellaSessione = "Calcolatrice")), app.nomi)
        assertEquals("TikTok (nella sessione si chiama «Calcolatrice»)", testoNomeApp(p, app.nomi[1]))
        assertEquals(
            "ClasseViva e TikTok (nella sessione si chiama «Calcolatrice»)",
            elencoAppSessione(p, app),
        )
    }

    @Test
    fun `le app installate fuori dal Play Store si dicono senza la parola APK, tranne sulla card che la spiega`() {
        val soloApk = appDellaSessione(listOf(GRUPPO_APK), emptyMap())
        assertEquals("Le app installate fuori dal Play Store", elencoAppSessione(p, soloApk))
        assertEquals("le app installate fuori dal Play Store", elencoAppSessione(p, soloApk, inFrase = true))
        assertEquals(
            "ClasseViva e le app installate fuori dal Play Store",
            elencoAppSessione(p, appDellaSessione(listOf(classeviva, GRUPPO_APK), nomiStudio)),
        )
        assertEquals("ClasseViva", elencoAppSessione(p, appDellaSessione(listOf(classeviva), nomiStudio)))
        assertNull(elencoAppSessione(p, appDellaSessione(emptyList(), emptyMap())))
        assertTrue(appDellaSessione(listOf(" ", ""), emptyMap()).vuota)
        // Sulla card, spiegata.
        assertEquals("App installate da APK", p.testo(R.string.sessione_app_apk))
        assertEquals(
            "Tutte le app installate fuori dal Play Store, comprese quelle che installerà dopo.",
            p.testo(R.string.sessione_app_apk_spiega),
        )
    }

    @Test
    fun `una lista lunghissima si ferma a dodici nomi, poi dice quante altre`() {
        val tante = (1..15).map { "com.esempio.app$it" }
        val nomi = tante.associateWith { "App ${it.removePrefix("com.esempio.app").padStart(2, '0')}" }
        val elenco = checkNotNull(elencoAppSessione(p, appDellaSessione(tante, nomi)))
        assertTrue(elenco, elenco.startsWith("App 01, App 02, "))
        assertTrue(elenco, elenco.endsWith("App 12 e altre 3 app"))
        assertFalse(elenco, elenco.contains("App 13"))
        assertEquals("e altre 3 app", p.testo(R.string.sessione_e_altre_app, 3))
        assertEquals(12, APP_VISIBILI)
    }

    // --- la card ---------------------------------------------------------------------------

    @Test
    fun `la card dice chi chiede e quale sessione, e che cosa vuol dire approvarla`() {
        assertEquals("Luca chiede di approvare la sessione 📚 «Studio»", chiedeLaSessione(p, "Luca", "Studio", cambio = false))
        assertEquals("Luca chiede di cambiare la sessione 📚 «Studio»", chiedeLaSessione(p, "Luca", "Studio", cambio = true))
        assertEquals("Tuo figlio chiede di approvare la sessione 📚 «Studio»", chiedeLaSessione(p, " ", "Studio", cambio = false))
        assertEquals("Tuo figlio chiede di cambiare la sessione ✨ «Sessione»", chiedeLaSessione(p, null, "  ", cambio = true))
        assertEquals("Se approvi, Luca potrà avviarla quando vuole, per quanto vuole.", seApproviLaSessione(p, "Luca"))
        assertEquals("Se approvi, tuo figlio potrà avviarla quando vuole, per quanto vuole.", seApproviLaSessione(p, null))
        // Che cosa accetta, senza ambiguità: quali app non contano, e che cosa succede alle altre.
        assertEquals(
            "Durante la sessione il tempo nelle app della sessione non conta; le altre app sono coperte da una schermata.",
            p.testo(R.string.sessione_non_conta),
        )
        // (0.15) La spiegazione fissa della sezione Sessioni non c'è più: la frase qui sopra
        // si dice una volta sola, sotto le card di "Da decidere".
        assertEquals("Approva", p.testo(R.string.sessione_approva))
        // (0.15) "Rifiuta" per le sessioni come per le proposte.
        assertEquals("Rifiuta", p.testo(R.string.sessione_rifiuta))
        assertEquals("Perché? (facoltativo)", p.testo(R.string.proposta_campo_perche))
        assertEquals("Al massimo 500 caratteri", p.testo(R.string.sessione_perche_massimo, MASSIMO_MOTIVAZIONE_SESSIONE))
        assertEquals(
            "Rifiuti la sessione 📚 «Studio»?",
            p.testo(R.string.sessione_rifiuta_titolo, nomeSessioneTraVirgolette(p, "Studio")),
        )
    }

    @Test
    fun `un cambio dice quando e stato chiesto, negli orari del telefono`() {
        val roma = ZoneId.of("Europe/Rome")
        val primoOttobre = LocalDate.of(2026, 10, 1)
        assertEquals("chiesto alle 15:02", quandoChiesta(p, "2026-10-01T13:02:00+00:00", roma, primoOttobre))
        assertEquals("chiesto ieri alle 23:40", quandoChiesta(p, "2026-09-30T21:40:00+00:00", roma, primoOttobre))
        assertEquals("chiesto il 28/09 alle 09:05", quandoChiesta(p, "2026-09-28T07:05:00+00:00", roma, primoOttobre))
        assertNull(quandoChiesta(p, null, roma, primoOttobre))
        assertNull(quandoChiesta(p, "ieri sera", roma, primoOttobre))
        // Il cambio porta richiesta_ts.
        val conCambio = sessione(
            stato = "approvata",
            modifica = ModificaSessione(app = listOf(classeviva), richiestaTs = "2026-10-01T13:02:00+00:00"),
        )
        assertEquals("2026-10-01T13:02:00+00:00", checkNotNull(richiestaInAttesa(conCambio)).sessione.modificaInAttesa?.richiestaTs)
    }

    @Test
    fun `un cambio si dice una riga per cosa, coi nomi delle app`() {
        val conCambio = sessione(
            stato = "approvata",
            app = listOf(classeviva, youtube),
            nomi = mapOf(classeviva to "ClasseViva", youtube to "YouTube"),
            modifica = ModificaSessione(
                nome = "Compiti",
                app = listOf(classeviva, duolingo, GRUPPO_APK),
                nomi = mapOf(duolingo to "Duolingo"),
            ),
        )
        assertEquals(
            listOf("Nuovo nome: 📚 «Compiti»", "Aggiunge Duolingo e le app installate fuori dal Play Store", "Toglie YouTube"),
            righeCambioSessione(p, checkNotNull(richiestaInAttesa(conCambio))),
        )
        // Una sessione nuova non ha righe di cambio.
        assertTrue(righeCambioSessione(p, checkNotNull(richiestaInAttesa(sessione()))).isEmpty())
    }

    @Test
    fun `su quale telefono, solo quando serve`() {
        val telefono = RiferimentoDispositivo(1, "Telefono di Luca", "telefono")
        assertNull(doveSta(p, telefono, piuDispositivi = false))
        assertEquals("sul telefono «Telefono di Luca»", doveSta(p, telefono, piuDispositivi = true))
        assertEquals("sul telefono", doveSta(p, RiferimentoDispositivo(1, "Telefono", "telefono"), piuDispositivi = true))
        assertNull(doveSta(p, null, piuDispositivi = true))
        // Il telefono della sessione: quello allegato, o quello della finestra col suo id.
        val vista = VistaDispositivo(id = 1, nome = "Telefono di Luca")
        assertEquals(telefono, telefonoDellaSessione(Sessione(id = 3, dispositivoId = 1), listOf(vista)))
        assertNull(telefonoDellaSessione(Sessione(id = 3, dispositivoId = 9), listOf(vista)))
        // Le righe delle sessioni dicono il telefono solo se il figlio ne ha più d'uno.
        val telefonoEComputer = Finestra(
            dispositivi = listOf(DispositivoFinestra(1, "Telefono"), DispositivoFinestra(2, "PC", tipo = "computer")),
        )
        assertFalse(piuTelefoni(telefonoEComputer))
        val dueTelefoni = Finestra(
            dispositivi = listOf(DispositivoFinestra(1, "Telefono"), DispositivoFinestra(3, "Vecchio", revocato = true)),
        )
        assertTrue(piuTelefoni(dueTelefoni))
    }

    // --- le sessioni fatte ---------------------------------------------------------------------

    private val roma: ZoneId = ZoneId.of("Europe/Rome")
    private val oggi: LocalDate = LocalDate.of(2026, 10, 1)
    private val formatoServer: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'+00:00'").withZone(ZoneOffset.UTC)

    /** Un istante del 2026 a Roma. */
    private fun istante(mese: Int, giorno: Int, ora: Int, minuto: Int): Instant =
        LocalDate.of(2026, mese, giorno).atTime(ora, minuto).atZone(roma).toInstant()

    /** Lo stesso istante scritto come lo scrive il server: ISO 8601 UTC coi secondi. */
    private fun ts(mese: Int, giorno: Int, ora: Int, minuto: Int): String =
        formatoServer.format(istante(mese, giorno, ora, minuto))

    private fun svolta(
        inizio: String,
        durata: Int?,
        finePrevista: String?,
        fine: String?,
        chiusura: String?,
        nome: String = "Studio",
        id: Long = 12,
    ) = SessioneSvolta(
        id = id,
        sessioneId = 3,
        dispositivoId = 1,
        nome = nome,
        app = listOf(classeviva),
        nomi = nomiStudio,
        inizioTs = inizio,
        durataMinuti = durata,
        finePrevistaTs = finePrevista,
        fineTs = fine,
        chiusura = chiusura,
        inCorso = chiusura == null && fine == null,
    )

    /** La riga di [s] vista [adesso], il 1° ottobre a Roma. */
    private fun riga(s: SessioneSvolta, adesso: Instant): String =
        testoSessioneSvolta(p, checkNotNull(raccontaSvolta(s, adesso)), roma, oggi)

    @Test
    fun `le sessioni fatte si leggono come le direbbe una persona`() {
        val adesso = istante(10, 1, 17, 0)
        assertEquals(
            "📚 Studio · oggi 15:02–16:40 · chiusa prima (prevista 2 h)",
            riga(svolta(ts(10, 1, 15, 2), 120, ts(10, 1, 17, 2), ts(10, 1, 16, 40), "terminata"), adesso),
        )
        assertEquals(
            "📚 Studio · ieri 15:00–17:00 · 2 h",
            riga(svolta(ts(9, 30, 15, 0), 120, ts(9, 30, 17, 0), ts(9, 30, 17, 0), "scaduta"), adesso),
        )
        assertEquals(
            "📚 Studio · 28/09 15:00–16:30 · 1 h 30 min",
            riga(svolta(ts(9, 28, 15, 0), 90, ts(9, 28, 16, 30), ts(9, 28, 16, 30), "scaduta"), adesso),
        )
    }

    @Test
    fun `una in corso dice quando e iniziata, la durata scelta e fino a quando`() {
        assertEquals(
            "✨ Lavoro · iniziata alle 15:02 · 2 h, fino alle 17:02",
            riga(svolta(ts(10, 1, 15, 2), 120, ts(10, 1, 17, 2), null, null, nome = "Lavoro"), istante(10, 1, 16, 0)),
        )
        assertEquals(
            "✨ Lavoro · iniziata alle 22:00 · 3 h, fino a domani alle 01:00",
            riga(svolta(ts(10, 1, 22, 0), 180, ts(10, 2, 1, 0), null, null, nome = "Lavoro"), istante(10, 1, 22, 30)),
        )
        assertEquals(
            "📚 Studio · iniziata ieri alle 23:30 · 10 h, fino alle 09:30",
            riga(svolta(ts(9, 30, 23, 30), 600, ts(10, 1, 9, 30), null, null), istante(10, 1, 8, 0)),
        )
        // Senza durata né fine prevista: da quando, e basta.
        assertEquals(
            "📚 Studio · iniziata alle 15:00 · in corso",
            riga(svolta(ts(10, 1, 15, 0), null, null, null, null), istante(10, 1, 15, 10)),
        )
        assertEquals("iniziata il 28/09 alle 09:05", iniziataQuando(p, istante(9, 28, 9, 5), roma, oggi))
    }

    @Test
    fun `una in corso letta prima della fine prevista, dopo la fine e finita`() {
        val studio = svolta(ts(10, 1, 15, 0), 60, ts(10, 1, 16, 0), null, null)
        assertEquals(FineSessione.IN_CORSO, checkNotNull(raccontaSvolta(studio, istante(10, 1, 15, 30))).fine)
        val dopo = checkNotNull(raccontaSvolta(studio, istante(10, 1, 16, 5)))
        assertEquals(FineSessione.COMPLETA, dopo.fine)
        assertEquals("📚 Studio · oggi 15:00–16:00 · 1 h", testoSessioneSvolta(p, dopo, roma, oggi))
    }

    @Test
    fun `a cavallo della mezzanotte`() {
        assertEquals(
            "📚 Studio · ieri 23:30 – oggi 00:45 · chiusa prima (prevista 2 h)",
            riga(svolta(ts(9, 30, 23, 30), 120, ts(10, 1, 1, 30), ts(10, 1, 0, 45), "terminata"), istante(10, 1, 9, 0)),
        )
    }

    @Test
    fun `una terminata alla fine prevista non e chiusa prima, e i dati che mancano non si inventano`() {
        val sera = istante(10, 1, 18, 0)
        assertEquals(
            "📚 Studio · oggi 15:00–17:00 · 2 h",
            riga(svolta(ts(10, 1, 15, 0), 120, ts(10, 1, 17, 0), ts(10, 1, 17, 0), "terminata"), sera),
        )
        // Senza la fine vera: da quando.
        assertEquals(
            "📚 Studio · oggi dalle 15:00 · chiusa prima (prevista 2 h)",
            riga(svolta(ts(10, 1, 15, 0), 120, ts(10, 1, 17, 0), null, "terminata"), sera),
        )
        // Senza durata né fine prevista: "chiusa prima" e basta.
        assertEquals(
            "📚 Studio · oggi 15:00–15:40 · chiusa prima",
            riga(svolta(ts(10, 1, 15, 0), null, null, ts(10, 1, 15, 40), "terminata"), sera),
        )
        // La durata scelta all'avvio si ricava dalla fine prevista, se manca.
        assertEquals(
            "📚 Studio · oggi 15:00–15:40 · chiusa prima (prevista 1 h)",
            riga(svolta(ts(10, 1, 15, 0), null, ts(10, 1, 16, 0), ts(10, 1, 15, 40), "terminata"), sera),
        )
        // Una chiusura che non si conosce: solo gli orari.
        assertEquals(
            "📚 Studio · oggi 15:00–15:40",
            riga(svolta(ts(10, 1, 15, 0), 120, ts(10, 1, 17, 0), ts(10, 1, 15, 40), "chiusura_del_futuro"), sera),
        )
        // Un nome che manca.
        assertEquals(
            "✨ Sessione · oggi 15:00–17:00 · 2 h",
            riga(svolta(ts(10, 1, 15, 0), 120, ts(10, 1, 17, 0), ts(10, 1, 17, 0), "scaduta", nome = " "), sera),
        )
        // Un inizio che non si legge: la sessione non si racconta.
        assertNull(raccontaSvolta(svolta("ieri", 120, null, null, null), sera))
    }

    @Test
    fun `le sessioni fatte vanno dalla piu recente, una volta sola`() {
        val ieri = svolta(ts(9, 30, 15, 0), 60, ts(9, 30, 16, 0), ts(9, 30, 16, 0), "scaduta", id = 1)
        val stamattina = svolta(ts(10, 1, 9, 0), 60, ts(10, 1, 10, 0), ts(10, 1, 10, 0), "scaduta", id = 2)
        val rotta = svolta("boh", 60, null, null, null, id = 3)
        val lista = sessioniSvolteRaccontate(listOf(ieri, stamattina, ieri, rotta), istante(10, 1, 12, 0))
        assertEquals(listOf(2L, 1L), lista.map { it.svolta.id })
    }

    @Test
    fun `le approvate in ordine di nome, anche con un cambio in attesa`() {
        val lista = sessioniApprovate(
            listOf(
                sessione(id = 1, nome = "Studio", stato = "approvata"),
                sessione(id = 2, nome = "lavoro", stato = "approvata", modifica = ModificaSessione(nome = "Zeta")),
                sessione(id = 3, nome = "Attesa"),
                sessione(id = 4, nome = "Rifiutata", stato = "rifiutata"),
            ),
        )
        assertEquals(listOf(2L, 1L), lista.map { it.id })
    }

    // --- il Tempo, e il numero accanto al nome ------------------------------------------------

    @Test
    fun `nel Tempo i minuti in sessione si dicono a parte, come nell'app del figlio`() {
        assertEquals("In più 1 h 20 min in sessione, che non contano.", testoInSessione(p, 80))
        assertEquals("In più 45 min in sessione, che non contano.", testoInSessione(p, 45))
        assertEquals("In più 1 min in sessione, che non conta.", testoInSessione(p, 1))
        assertNull(testoInSessione(p, 0))
        assertNull(testoInSessione(p, null))
    }

    @Test
    fun `accanto al nome, le proposte piu le sessioni da approvare`() {
        val luca = Figlio(id = 2, nome = "Luca", proposteDaDecidere = 1, sessioniDaApprovare = 2)
        assertEquals(3, quanteDaDecidere(luca))
        assertEquals("3 da decidere", testoDaDecidere(p, quanteDaDecidere(luca)))
        assertEquals(2, quanteDaDecidere(luca.copy(proposteDaDecidere = 0)))
        assertEquals(0, quanteDaDecidere(Figlio(id = 2)))
        assertNull(testoDaDecidere(p, quanteDaDecidere(Figlio(id = 2))))
    }

    // --- le notifiche ------------------------------------------------------------------------

    private val luca = Figlio(id = 2, nome = "Luca")
    private val nonConta =
        "Durante la sessione il tempo nelle app della sessione non conta; le altre app sono coperte da una schermata."

    /** Una notifica del figlio 2 (Luca), come le manda il server v3.5. */
    private fun notifica(tipo: String, payload: JsonObject) = Notifica(
        id = 30,
        tipo = tipo,
        messaggio = "messaggio del server",
        payload = payload,
        tsServer = "2026-10-01T13:00:00+00:00",
        figlioId = 2,
        dispositivoId = 1,
    )

    private fun daApprovare(cambio: Boolean, id: Long = 3) = buildJsonObject {
        put("sessione_id", id)
        put("nome", "Studio")
        put("cambio", cambio)
    }

    @Test
    fun `una sessione da approvare - chi chiede nel titolo, le app e che cosa vuol dire nel testo`() {
        val t = testoNotifica(
            p,
            notifica("sessione_da_approvare", daApprovare(cambio = false)),
            emptyMap(),
            figli = listOf(luca),
            sessioni = mapOf(3L to sessione()),
        )
        assertEquals(
            TestoNotifica(
                "Luca chiede di approvare la sessione 📚 «Studio»",
                "ClasseViva, Classroom e le app installate fuori dal Play Store\n$nonConta",
            ),
            t,
        )
    }

    @Test
    fun `un cambio di sessione dice che cosa cambia`() {
        val conCambio = sessione(
            stato = "approvata",
            app = listOf(classeviva, youtube),
            nomi = mapOf(classeviva to "ClasseViva", youtube to "YouTube"),
            modifica = ModificaSessione(app = listOf(classeviva, duolingo), nomi = mapOf(duolingo to "Duolingo")),
        )
        val t = testoNotifica(
            p,
            notifica("sessione_da_approvare", daApprovare(cambio = true)),
            emptyMap(),
            figli = listOf(luca),
            sessioni = mapOf(3L to conCambio),
        )
        assertEquals(TestoNotifica("Luca chiede di cambiare la sessione 📚 «Studio»", "Aggiunge Duolingo\nToglie YouTube"), t)
    }

    @Test
    fun `senza la sessione letta, o gia decisa, la notifica si legge lo stesso`() {
        // Finestra non arrivata e famiglia non letta.
        assertEquals(
            TestoNotifica("Tuo figlio chiede di approvare la sessione 📚 «Studio»", nonConta),
            testoNotifica(p, notifica("sessione_da_approvare", daApprovare(cambio = false)), emptyMap()),
        )
        // Già approvata: non c'è più una lista "da approvare" da mostrare.
        assertEquals(
            nonConta,
            testoNotifica(
                p,
                notifica("sessione_da_approvare", daApprovare(cambio = false)),
                emptyMap(),
                sessioni = mapOf(3L to sessione(stato = "approvata")),
            ).testo,
        )
        // Un cambio già deciso.
        assertEquals(
            TestoNotifica("Luca chiede di cambiare la sessione 📚 «Studio»", nonConta),
            testoNotifica(
                p,
                notifica("sessione_da_approvare", daApprovare(cambio = true)),
                emptyMap(),
                figli = listOf(luca),
                sessioni = mapOf(3L to sessione(stato = "approvata")),
            ),
        )
        // Senza il nome della sessione (né nel payload né letto): il tipo e il messaggio del server.
        assertEquals(
            TestoNotifica("Sessione da approvare", "messaggio del server"),
            testoNotifica(p, notifica("sessione_da_approvare", buildJsonObject { put("sessione_id", 99) }), emptyMap()),
        )
    }

    @Test
    fun `un cambio che rinomina - il nome di adesso nel titolo, quello chiesto nel testo`() {
        val rinomina = buildJsonObject {
            put("sessione_id", 3)
            put("nome", "Studio")
            put("nuovo_nome", "Compiti")
            put("cambio", true)
        }
        // La sessione non letta (o il cambio già deciso): il nome nuovo viene dal payload.
        assertEquals(
            TestoNotifica("Luca chiede di cambiare la sessione 📚 «Studio»", "Nuovo nome: 📚 «Compiti»"),
            testoNotifica(p, notifica("sessione_da_approvare", rinomina), emptyMap(), figli = listOf(luca)),
        )
        // Letta e ancora in attesa: che cosa cambia, nome compreso.
        val conCambio = sessione(
            stato = "approvata",
            app = listOf(classeviva, youtube),
            nomi = mapOf(classeviva to "ClasseViva", youtube to "YouTube"),
            modifica = ModificaSessione(nome = "Compiti", app = listOf(classeviva)),
        )
        assertEquals(
            "Nuovo nome: 📚 «Compiti»\nToglie YouTube",
            testoNotifica(p, notifica("sessione_da_approvare", rinomina), emptyMap(), sessioni = mapOf(3L to conCambio)).testo,
        )
        // Un nuovo nome uguale a quello di adesso non è un nome nuovo.
        val stessoNome = buildJsonObject {
            put("sessione_id", 3)
            put("nome", "Studio")
            put("nuovo_nome", " Studio ")
            put("cambio", true)
        }
        assertEquals(nonConta, testoNotifica(p, notifica("sessione_da_approvare", stessoNome), emptyMap()).testo)
    }

    @Test
    fun `una sessione eliminata dice chi e che la storia resta`() {
        val payload = buildJsonObject {
            put("sessione_id", 3)
            put("nome", "Studio")
        }
        assertEquals(
            TestoNotifica("Luca ha eliminato la sessione 📚 «Studio»", "Le sessioni già fatte restano nella Panoramica."),
            testoNotifica(p, notifica("sessione_eliminata", payload), emptyMap(), figli = listOf(luca)),
        )
        assertEquals(
            "Tuo figlio ha eliminato la sessione 📚 «Studio»",
            testoNotifica(p, notifica("sessione_eliminata", payload), emptyMap()).titolo,
        )
        assertEquals(
            TestoNotifica("Sessione eliminata", "messaggio del server"),
            testoNotifica(p, notifica("sessione_eliminata", buildJsonObject { }), emptyMap()),
        )
    }

    @Test
    fun `nelle frasi delle sessioni niente chiavi grezze, niente APK, null o date ISO`() {
        val conCambio = sessione(
            stato = "approvata",
            app = listOf(classeviva, "com.senza.nome"),
            modifica = ModificaSessione(app = listOf(GRUPPO_APK, "com.altra.senza.nome")),
        )
        val frasi = listOf(
            testoNotifica(p, notifica("sessione_da_approvare", daApprovare(false)), emptyMap(), listOf(luca), sessioni = mapOf(3L to sessione())),
            testoNotifica(p, notifica("sessione_da_approvare", daApprovare(true)), emptyMap(), listOf(luca), sessioni = mapOf(3L to conCambio)),
            testoNotifica(p, notifica("sessione_eliminata", daApprovare(false)), emptyMap(), listOf(luca)),
        )
        frasi.forEach { t ->
            val tutto = t.titolo + " " + t.testo
            listOf("gruppo:", "com.", "APK", "null", "2026-", "in_attesa", "approvata", "true", "false").forEach {
                assertFalse("'$it' in «$tutto»", tutto.contains(it))
            }
        }
    }

    // --- gli esiti della risposta ----------------------------------------------------------------

    private fun frase(esito: EsitoSessione): String = p.testo(messaggioEsitoSessione(esito))

    @Test
    fun `dopo la risposta, una frase che dice che cosa e successo`() {
        assertEquals("Fatto: la sessione è approvata.", frase(EsitoSessione.Decisa("approva", cambio = false)))
        assertEquals("Fatto: il cambio è approvato.", frase(EsitoSessione.Decisa("approva", cambio = true)))
        assertEquals("Hai rifiutato la sessione.", frase(EsitoSessione.Decisa("rifiuta", cambio = false)))
        assertEquals(
            "Hai rifiutato il cambio: la sessione resta com'era.",
            frase(EsitoSessione.Decisa("rifiuta", cambio = true)),
        )
        // Arrivato mentre il genitore guarda un altro figlio: di chi era.
        assertEquals("Luca · Fatto: la sessione è approvata.", esitoPerIlFiglio(p, "Luca", "Fatto: la sessione è approvata."))
        assertEquals("Fatto: la sessione è approvata.", esitoPerIlFiglio(p, null, "Fatto: la sessione è approvata."))
    }

    @Test
    fun `ogni rifiuto dice il motivo vero, e un server vecchio va aggiornato`() {
        assertEquals(
            "Per le sessioni serve aggiornare il server di Pactum.",
            frase(EsitoSessione.NonDecisa(CodiciErrore.SERVER_DA_AGGIORNARE)),
        )
        assertEquals(
            "Tuo figlio ha appena cambiato la richiesta: guardala di nuovo prima di decidere.",
            frase(EsitoSessione.NonDecisa(CodiciErrore.RICHIESTA_CAMBIATA)),
        )
        assertEquals(
            "Tuo figlio l'ha eliminata: non c'è più niente da decidere.",
            frase(EsitoSessione.NonDecisa(CodiciErrore.NON_TROVATO)),
        )
        // Un 422 non riguarda la lunghezza del perché (il campo si ferma a 500): app e server non si capiscono.
        assertEquals(
            "Il server non ha accettato la risposta: controlla che l'app e il server di Pactum siano aggiornati.",
            frase(EsitoSessione.NonDecisa(PostinoClient.PARAMETRI_NON_VALIDI)),
        )
        assertEquals(
            "Questo telefono è stato scollegato: la sessione non si può più approvare.",
            frase(EsitoSessione.NonDecisa(CodiciErrore.DISPOSITIVO_REVOCATO)),
        )
        assertEquals("Non sono riuscito a mandare la risposta: riprova.", frase(EsitoSessione.NonDecisa(null)))
        assertEquals("Non sono riuscito a mandare la risposta: riprova.", frase(EsitoSessione.NonDecisa("motivo_del_futuro")))
    }

    @Test
    fun `niente da decidere dice com'e davvero, mai un forse`() {
        val niente = CodiciErrore.NIENTE_DA_DECIDERE
        assertEquals("Era già stata approvata.", frase(EsitoSessione.NonDecisa(niente, SessioneRiletta.APPROVATA)))
        assertEquals("Era già stata rifiutata.", frase(EsitoSessione.NonDecisa(niente, SessioneRiletta.NON_APPROVATA)))
        assertEquals("Il cambio era già stato deciso.", frase(EsitoSessione.NonDecisa(niente, SessioneRiletta.CAMBIO_DECISO)))
        assertEquals(
            "Tuo figlio l'ha eliminata: non c'è più niente da decidere.",
            frase(EsitoSessione.NonDecisa(niente, SessioneRiletta.ELIMINATA)),
        )
        assertEquals(
            "Tuo figlio ha appena cambiato la richiesta: guardala di nuovo prima di decidere.",
            frase(EsitoSessione.NonDecisa(niente, SessioneRiletta.CAMBIATA)),
        )
        listOf(SessioneRiletta.NON_SI_SA, null).forEach { riletta ->
            assertEquals(
                "Non c'è più niente da decidere: è già stata decisa, o tuo figlio l'ha eliminata.",
                frase(EsitoSessione.NonDecisa(niente, riletta)),
            )
        }
    }

    // --- 0.12: accanto al nome, l'emoji del tema -------------------------------------------------

    @Test
    fun `accanto al nome la prima emoji del suo tema, come nell'app del figlio`() {
        // Il tema lo sceglie il nome (TemaSessione, core-design): maiuscole e parole in più non contano.
        assertEquals("📚", emojiSessione("Studio"))
        assertEquals("📚", emojiSessione("Compiti di matematica"))
        assertEquals("⚽", emojiSessione("Allenamento calcio"))
        assertEquals("🎵", emojiSessione("MUSICA"))
        assertEquals("📖", emojiSessione("Lettura"))
        assertEquals("💻", emojiSessione("Coding"))
        // Un nome che non si riconosce, o che manca: le stelline.
        assertEquals("✨", emojiSessione("Lavoro"))
        assertEquals("✨", emojiSessione(null))
        // È la prima emoji del tema: quella grande della pagina che vede il figlio.
        assertEquals(TemaSessione.daNome("Sport").emojiPrincipale, emojiSessione("Sport"))
        // In testa a una riga l'emoji e il nome; in una frase il nome esatto fra «», con l'emoji fuori.
        assertEquals("📚 Studio", nomeSessioneConEmoji(p, " Studio "))
        assertEquals("📚 «Studio»", nomeSessioneTraVirgolette(p, " Studio "))
        assertEquals("✨ Sessione", nomeSessioneConEmoji(p, "  "))
        assertEquals("✨ «Sessione»", nomeSessioneTraVirgolette(p, null))
    }

    @Test
    fun `un nome che comincia gia con quell'emoji non la ripete`() {
        assertNull(emojiSessione("📚 Ripasso storia"))
        assertEquals("📚 Ripasso storia", nomeSessioneConEmoji(p, "📚 Ripasso storia"))
        assertEquals("«📚 Ripasso storia»", nomeSessioneTraVirgolette(p, " 📚 Ripasso storia"))
        // Una tastiera che aggiunge il selettore di variante: è la stessa emoji.
        assertEquals("⚽\uFE0F Calcio", nomeSessioneConEmoji(p, "⚽\uFE0F Calcio"))
        // Un'altra emoji in testa al nome non è quella del tema: l'emoji del tema c'è lo stesso.
        assertEquals("📚 🔥 Studio", nomeSessioneConEmoji(p, "🔥 Studio"))
    }

    @Test
    fun `l'emoji accompagna il nome ovunque compaia - card, domanda, righe e notifiche`() {
        // La card da approvare, e la sua notifica (stesso titolo).
        assertEquals("Luca chiede di approvare la sessione 🎵 «Chitarra»", chiedeLaSessione(p, "Luca", "Chitarra", cambio = false))
        // La domanda prima del sì (o del no).
        assertEquals(
            "Approvi il cambio alla sessione 🎨 «Disegno»?",
            p.testo(R.string.sessione_approva_cambio_titolo, nomeSessioneTraVirgolette(p, "Disegno")),
        )
        // Il nome nuovo chiesto da un cambio, con l'emoji del nome nuovo.
        assertEquals("Nuovo nome: 🧘 «Relax»", testoNuovoNome(p, "Relax"))
        // Le righe della Panoramica: una sessione fatta, una approvata, una non più valida.
        assertEquals(
            "⚽ Calcio · oggi 15:00–16:00 · 1 h",
            riga(svolta(ts(10, 1, 15, 0), 60, ts(10, 1, 16, 0), ts(10, 1, 16, 0), "scaduta", nome = "Calcio"), istante(10, 1, 17, 0)),
        )
        assertEquals("💻 Progetto", nomeSessioneConEmoji(p, "Progetto"))
        assertEquals(
            "📖 Lettura · non più valida: il telefono è scollegato",
            p.testo(R.string.sessione_non_piu_valida, nomeSessioneConEmoji(p, "Lettura")),
        )
        // Le notifiche: anche una sessione eliminata.
        val eliminata = buildJsonObject {
            put("sessione_id", 5)
            put("nome", "Palestra")
        }
        assertEquals(
            "Luca ha eliminato la sessione ⚽ «Palestra»",
            testoNotifica(p, notifica("sessione_eliminata", eliminata), emptyMap(), figli = listOf(luca)).titolo,
        )
    }
}
