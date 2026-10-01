package eu.stgm.pactum.figlio.ui

import eu.stgm.pactum.figlio.dati.AutoriProposta
import eu.stgm.pactum.figlio.dati.DirezioniProposta
import eu.stgm.pactum.figlio.dati.Dispositivo
import eu.stgm.pactum.figlio.dati.EsitiRisposta
import eu.stgm.pactum.figlio.dati.EsitoProposta
import eu.stgm.pactum.figlio.dati.EsitoRitiro
import eu.stgm.pactum.figlio.dati.Proposta
import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.RispostaProposta
import eu.stgm.pactum.figlio.dati.StatiProposta
import eu.stgm.pactum.figlio.dati.TipiDispositivo
import eu.stgm.pactum.figlio.dati.TipiNotifica
import eu.stgm.pactum.figlio.dati.TipiRegola
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La proposta del genitore deve dire su QUALE regola è: il ragazzo deve sapere
 * cosa accetta. Le parole sono le stesse di strings.xml, e la descrizione
 * finta delle regole scrive come la vera ("TikTok: al massimo 1 h al giorno").
 */
class TestoPropostaTest {

    private val parole = ParoleProposta(
        senzaConfronto = "Proposta di modifica",
        ora = "Ora: %1\$s",
        seAccetti = "Se accetti: %1\$s",
        togliere = "Propone di eliminare la regola: %1\$s",
        oraSu = "Ora %1\$s: %2\$s",
        togliereSu = "Propone di eliminare la regola %1\$s: %2\$s",
    )

    private val nomi = mapOf("com.zhiliaoapp.musically" to "TikTok")

    private fun durata(m: Long) = if (m < 60) "$m min" else if (m % 60 == 0L) "${m / 60} h" else "${m / 60} h ${m % 60} min"

    // Come l'app: una regola di questo telefono "TikTok: al massimo 1 h al giorno";
    // una di un altro dispositivo nella forma breve, senza i due punti.
    private val descrivi: (Regola, JsonObject) -> String = { regola, parametri ->
        when (regola.tipo) {
            TipiRegola.LIMITE_TEMPO -> {
                val app = (parametri["app_o_categoria"] as JsonPrimitive).content
                val minuti = (parametri["minuti_al_giorno"] as JsonPrimitive).content.toLong()
                val nome = ChiaviComputer.etichetta(app, null, "%1\$s (sito)") ?: nomi[app] ?: app
                if (regola.dispositivo?.tipo == TipiDispositivo.COMPUTER) {
                    "$nome al massimo ${durata(minuti)} al giorno"
                } else {
                    "$nome: al massimo ${durata(minuti)} al giorno"
                }
            }
            else -> regola.tipo
        }
    }

    private val sulComputer: (Regola) -> String? = {
        if (it.dispositivo?.tipo == TipiDispositivo.COMPUTER) "sul computer" else null
    }

    private fun limite(minuti: Int) = buildJsonObject {
        put("app_o_categoria", "com.zhiliaoapp.musically")
        put("minuti_al_giorno", minuti)
    }

    private val tiktok = Regola(id = 7, tipo = TipiRegola.LIMITE_TEMPO, parametri = limite(60))
    private val fascia = Regola(id = 8, tipo = TipiRegola.FASCIA_ORARIA)
    private val regole = listOf(fascia, tiktok)

    private val marcatoreElimina = buildJsonObject { put("azione", "elimina") }

    private fun racconto(confronto: String?, oggetto: OggettoProposta?) =
        TestoProposta.racconto(confronto, oggetto, parole, descrivi)

    @Test
    fun `una modifica dice la regola di ora e come diventa se accetti`() {
        val oggetto = TestoProposta.oggetto(7, DirezioniProposta.STRINGE, limite(45), regole)
        val racconto = racconto("−15 min al giorno rispetto ad ora", oggetto)
        assertEquals("−15 min al giorno rispetto ad ora", racconto.titolo)
        assertEquals(
            listOf(
                "Ora: TikTok: al massimo 1 h al giorno",
                "Se accetti: TikTok: al massimo 45 min al giorno",
            ),
            racconto.righe,
        )
    }

    @Test
    fun `l'eliminazione dice quale regola toglie, senza ripetere la frase del server`() {
        val oggetto = TestoProposta.oggetto(7, DirezioniProposta.ELIMINA, marcatoreElimina, regole)
        assertTrue(oggetto is OggettoProposta.Eliminazione)
        val racconto = racconto("propone di eliminare la regola", oggetto)
        assertEquals("Propone di eliminare la regola: TikTok: al massimo 1 h al giorno", racconto.titolo)
        assertEquals(emptyList<String>(), racconto.righe)
    }

    @Test
    fun `l'eliminazione si riconosce anche dal solo marcatore`() {
        val oggetto = TestoProposta.oggetto(7, null, marcatoreElimina, regole)
        assertTrue(oggetto is OggettoProposta.Eliminazione)
    }

    @Test
    fun `senza parametri proposti o con gli stessi di ora resta la sola regola di ora`() {
        for (proposti in listOf(null, limite(60), JsonObject(emptyMap()))) {
            val oggetto = TestoProposta.oggetto(7, DirezioniProposta.STRINGE, proposti, regole)
            assertEquals(
                listOf("Ora: TikTok: al massimo 1 h al giorno"),
                racconto("−15 min al giorno rispetto ad ora", oggetto).righe,
            )
        }
    }

    @Test
    fun `regola non trovata nel patto resta il solo confronto, com'era`() {
        val oggetto = TestoProposta.oggetto(99, DirezioniProposta.STRINGE, limite(45), regole)
        assertNull(oggetto)
        val racconto = racconto("−15 min al giorno rispetto ad ora", oggetto)
        assertEquals("−15 min al giorno rispetto ad ora", racconto.titolo)
        assertEquals(emptyList<String>(), racconto.righe)
    }

    @Test
    fun `senza confronto del server si dice che e' una proposta di modifica`() {
        val oggetto = TestoProposta.oggetto(7, null, limite(45), regole)
        assertEquals("Proposta di modifica", racconto(null, oggetto).titolo)
        assertEquals("Proposta di modifica", racconto("  ", oggetto).titolo)
    }

    @Test
    fun `nella notifica una riga per pezzo`() {
        val oggetto = TestoProposta.oggetto(7, DirezioniProposta.ALLENTA, limite(90), regole)
        assertEquals(
            "+30 min al giorno rispetto ad ora\n" +
                "Ora: TikTok: al massimo 1 h al giorno\n" +
                "Se accetti: TikTok: al massimo 1 h 30 min al giorno",
            racconto("+30 min al giorno rispetto ad ora", oggetto).testo,
        )
    }

    // --- v3: la proposta su una regola del computer, vista dal telefono -------

    private fun sitoYoutube(minuti: Int) = buildJsonObject {
        put("app_o_categoria", "sito:youtube.com")
        put("minuti_al_giorno", minuti)
    }

    private val youtubeSulComputer = Regola(
        id = 12,
        tipo = TipiRegola.LIMITE_TEMPO,
        parametri = sitoYoutube(60),
        dispositivoId = 2,
        dispositivo = Dispositivo(id = 2, nome = "Computer", tipo = TipiDispositivo.COMPUTER),
    )

    private fun raccontoV3(confronto: String?, oggetto: OggettoProposta?) =
        TestoProposta.racconto(confronto, oggetto, parole, descrivi, sulComputer)

    @Test
    fun `una modifica su una regola del computer dice su quale dispositivo`() {
        val oggetto = TestoProposta.oggetto(
            12, DirezioniProposta.STRINGE, sitoYoutube(30), regole + youtubeSulComputer,
        )
        val racconto = raccontoV3("−30 min al giorno rispetto ad ora", oggetto)
        assertEquals("−30 min al giorno rispetto ad ora", racconto.titolo)
        assertEquals(
            listOf(
                "Ora sul computer: youtube.com (sito) al massimo 1 h al giorno",
                "Se accetti: youtube.com (sito) al massimo 30 min al giorno",
            ),
            racconto.righe,
        )
    }

    @Test
    fun `eliminare una regola del computer dice il dispositivo nel titolo`() {
        val oggetto = TestoProposta.oggetto(12, DirezioniProposta.ELIMINA, marcatoreElimina, listOf(youtubeSulComputer))
        assertEquals(
            "Propone di eliminare la regola sul computer: youtube.com (sito) al massimo 1 h al giorno",
            raccontoV3("propone di eliminare la regola", oggetto).titolo,
        )
    }

    @Test
    fun `una regola di questo telefono resta com'era anche con i dispositivi`() {
        val oggetto = TestoProposta.oggetto(7, DirezioniProposta.STRINGE, limite(45), regole)
        assertEquals(
            listOf(
                "Ora: TikTok: al massimo 1 h al giorno",
                "Se accetti: TikTok: al massimo 45 min al giorno",
            ),
            raccontoV3("−15 min al giorno rispetto ad ora", oggetto).righe,
        )
    }

    // --- (0.10) Le proposte del figlio (contratto v3.4) ------------------------

    /** Le stesse frasi di paroleTuaProposta in strings.xml. */
    private val paroleTue = ParoleProposta(
        senzaConfronto = "Proposta di modifica",
        ora = "Ora: %1\$s",
        seAccetti = "Se il genitore accetta: %1\$s",
        togliere = "Eliminare la regola: %1\$s",
        oraSu = "Ora %1\$s: %2\$s",
        togliereSu = "Eliminare la regola %1\$s: %2\$s",
    )

    @Test
    fun `la tua proposta si racconta dalla tua parte`() {
        val modifica = TestoProposta.oggetto(7, DirezioniProposta.ALLENTA, limite(90), regole)
        assertEquals(
            RaccontoProposta(
                "+30 min al giorno rispetto ad ora",
                listOf("Ora: TikTok: al massimo 1 h al giorno", "Se il genitore accetta: TikTok: al massimo 1 h 30 min al giorno"),
            ),
            TestoProposta.racconto("+30 min al giorno rispetto ad ora", modifica, paroleTue, descrivi),
        )
        val eliminare = TestoProposta.oggetto(7, DirezioniProposta.ELIMINA, marcatoreElimina, regole)
        assertEquals(
            "Eliminare la regola: TikTok: al massimo 1 h al giorno",
            TestoProposta.racconto("propone di eliminare la regola", eliminare, paroleTue, descrivi).titolo,
        )
    }

    // --- (0.10, contratto v3.4 "I nomi nel confronto") mai un nome di pacchetto --

    private fun limiteSu(app: String, minuti: Int) = buildJsonObject {
        put("app_o_categoria", app)
        put("minuti_al_giorno", minuti)
    }

    @Test
    fun `un bersaglio nuovo senza nome leggibile non diventa una riga col pacchetto`() {
        // Su questo telefono Instagram non c'è: la riga "Se accetti" non si scrive,
        // il confronto del server (coi nomi) basta da solo.
        val oggetto = TestoProposta.oggetto(7, DirezioniProposta.ALLENTA, limiteSu("com.instagram.android", 60), regole)
        val racconto = TestoProposta.racconto(
            confronto = "da TikTok (60 min) a Instagram (60 min) al giorno",
            oggetto = oggetto,
            parole = paroleTue,
            descrivi = descrivi,
            descriviProposta = { _, parametri ->
                val app = (parametri["app_o_categoria"] as JsonPrimitive).content
                if (app in nomi) descrivi(tiktok, parametri) else null
            },
        )
        assertEquals("da TikTok (60 min) a Instagram (60 min) al giorno", racconto.titolo)
        assertEquals(listOf("Ora: TikTok: al massimo 1 h al giorno"), racconto.righe)
        assertTrue("com.instagram.android" !in racconto.testo)
    }

    @Test
    fun `un nome e' leggibile solo se non e' la chiave tecnica`() {
        assertEquals("TikTok", TestoProposta.nomeLeggibile("com.zhiliaoapp.musically", " TikTok "))
        assertEquals("Tutto il telefono", TestoProposta.nomeLeggibile("totale", "Tutto il telefono"))
        assertEquals("Social", TestoProposta.nomeLeggibile("categoria:social", "Social"))
        assertNull(TestoProposta.nomeLeggibile("com.instagram.android", "com.instagram.android"))
        assertNull(TestoProposta.nomeLeggibile("com.instagram.android", "  "))
        assertNull(TestoProposta.nomeLeggibile("com.instagram.android", null))
    }

    private val paroleEsito = ParoleEsitoProposta(
        mandata = "Proposta mandata: %1\$s. Se il genitore accetta, vale subito.",
        mandataEliminazione = "Proposta mandata: eliminare la regola. Se il genitore accetta, vale subito.",
        mandataSenzaConfronto = "Proposta mandata. Se il genitore accetta, vale subito.",
        giaPendente = "C'è già una proposta in attesa su questa regola.",
        giaTua = "Hai già una proposta in attesa su questa regola.",
        regolaNonValida = "Questa regola non è più attiva: non si può proporre di cambiarla.",
        dispositivoRevocato = "Questa regola è di un dispositivo scollegato dal patto: non si può più cambiare, nemmeno con una proposta.",
        valoriNonValidi = "Qualche valore non va bene: controlla i campi e riprova.",
        serverDaAggiornare = "Per mandare proposte serve aggiornare il server di Pactum.",
        scollegato = "Questo telefono non è più collegato al patto.",
        senzaRete = "Non riesco a raggiungere il server: controlla la connessione e riprova.",
        errore = "Non sono riuscito a mandare la proposta: riprova.",
    )

    @Test
    fun `proposta mandata, col confronto del server e senza doppi punti`() {
        assertEquals(
            "Proposta mandata: +30 min al giorno rispetto ad ora. Se il genitore accetta, vale subito.",
            TestoProposta.esito(EsitoProposta.Mandata("+30 min al giorno rispetto ad ora", false), paroleEsito),
        )
        assertEquals(
            "Proposta mandata: arbitro: mamma -> papà. Se il genitore accetta, vale subito.",
            TestoProposta.esito(EsitoProposta.Mandata(" arbitro: mamma -> papà. ", false), paroleEsito),
        )
        assertEquals(
            "Proposta mandata. Se il genitore accetta, vale subito.",
            TestoProposta.esito(EsitoProposta.Mandata("  ", false), paroleEsito),
        )
        // La frase del server per l'eliminazione è scritta per il genitore: qui no.
        assertEquals(
            "Proposta mandata: eliminare la regola. Se il genitore accetta, vale subito.",
            TestoProposta.esito(EsitoProposta.Mandata("propone di eliminare la regola", true), paroleEsito),
        )
    }

    @Test
    fun `ogni rifiuto del server ha la sua frase, mai un errore generico se la causa si sa`() {
        assertEquals(paroleEsito.giaPendente, TestoProposta.esito(EsitoProposta.GiaPendente, paroleEsito))
        assertEquals(
            "Hai già una proposta in attesa su questa regola.",
            TestoProposta.esito(EsitoProposta.GiaTua, paroleEsito),
        )
        assertEquals(paroleEsito.regolaNonValida, TestoProposta.esito(EsitoProposta.RegolaNonValida, paroleEsito))
        assertEquals(paroleEsito.dispositivoRevocato, TestoProposta.esito(EsitoProposta.DispositivoRevocato, paroleEsito))
        assertEquals(paroleEsito.valoriNonValidi, TestoProposta.esito(EsitoProposta.ValoriNonValidi, paroleEsito))
        assertEquals(
            "Per mandare proposte serve aggiornare il server di Pactum.",
            TestoProposta.esito(EsitoProposta.ServerDaAggiornare, paroleEsito),
        )
        assertEquals(paroleEsito.scollegato, TestoProposta.esito(EsitoProposta.Scollegato, paroleEsito))
        assertEquals(paroleEsito.senzaRete, TestoProposta.esito(EsitoProposta.SenzaRete, paroleEsito))
        assertEquals(paroleEsito.errore, TestoProposta.esito(EsitoProposta.Errore, paroleEsito))
    }

    @Test
    fun `il ritiro, la proposta sparita e il server da aggiornare con la sua frase`() {
        val parole = ParoleRitiro(
            ritirata = "Proposta ritirata: la regola resta com'è.",
            nonPiuPendente = "Questa proposta non è più in attesa.",
            nonTrovata = "Non trovo più questa proposta.",
            serverDaAggiornare = "Per ritirare una proposta serve aggiornare il server di Pactum.",
            scollegato = "Non più collegato.",
            errore = "Non sono riuscito a ritirare la proposta: riprova.",
        )
        assertEquals(parole.ritirata, TestoProposta.ritiro(EsitoRitiro.Ritirata, parole))
        assertEquals(parole.nonPiuPendente, TestoProposta.ritiro(EsitoRitiro.NonPiuPendente, parole))
        assertEquals("Non trovo più questa proposta.", TestoProposta.ritiro(EsitoRitiro.NonTrovata, parole))
        assertEquals(
            "Per ritirare una proposta serve aggiornare il server di Pactum.",
            TestoProposta.ritiro(EsitoRitiro.ServerDaAggiornare, parole),
        )
        assertEquals(parole.errore, TestoProposta.ritiro(EsitoRitiro.Errore, parole))
    }

    // --- (0.10) La storia: com'è finita, e chi ha detto cosa --------------------

    private val paroleStoria = ParoleStoria(
        haiAccettato = "Hai accettato",
        haiRifiutato = "Hai rifiutato",
        genitoreHaAccettato = "Il genitore ha accettato",
        genitoreHaRifiutato = "Il genitore ha rifiutato",
        haiRitirato = "L'hai ritirata",
        genitoreHaRitirato = "Il genitore l'ha ritirata",
        annullata = "Annullata: la regola è stata eliminata",
        genitoreDice = "Il genitore dice: %1\$s",
        haiDetto = "Hai detto: %1\$s",
        tuoPerche = "Il tuo perché: %1\$s",
    )

    private fun chiusa(stato: String, autore: String?, motivazione: String? = null, risposta: RispostaProposta? = null) =
        Proposta(id = 1, regolaId = 7, stato = stato, autore = autore, motivazione = motivazione, risposta = risposta)

    @Test
    fun `una tua proposta rifiutata dice il tuo perche', l'esito e il perche' del genitore`() {
        val proposta = chiusa(
            StatiProposta.RIFIUTATA,
            AutoriProposta.FIGLIO,
            motivazione = "c'è la verifica",
            risposta = RispostaProposta(EsitiRisposta.RIFIUTA, "prima finisci i compiti"),
        )
        assertEquals(FineProposta.GENITORE_HA_RIFIUTATO, TestoProposta.fine(proposta))
        assertEquals(
            listOf(
                RigaStoria("Il tuo perché: c'è la verifica", tenue = true),
                RigaStoria("Il genitore ha rifiutato", tenue = false),
                RigaStoria("Il genitore dice: prima finisci i compiti", tenue = true),
            ),
            TestoProposta.righeChiusa(proposta, paroleStoria),
        )
    }

    @Test
    fun `una proposta del genitore accettata resta com'era, lui dice, tu accetti, tu dici`() {
        val proposta = chiusa(
            StatiProposta.ACCETTATA,
            autore = null,
            motivazione = "dormi poco",
            risposta = RispostaProposta(EsitiRisposta.ACCETTA, "ok"),
        )
        assertEquals(
            listOf("Il genitore dice: dormi poco", "Hai accettato", "Hai detto: ok"),
            TestoProposta.righeChiusa(proposta, paroleStoria).map { it.testo },
        )
    }

    @Test
    fun `ritirate e annullate dicono chi e cosa, senza perche' vuoti`() {
        assertEquals(
            listOf("L'hai ritirata"),
            TestoProposta.righeChiusa(chiusa(StatiProposta.RITIRATA, AutoriProposta.FIGLIO, "  "), paroleStoria).map { it.testo },
        )
        assertEquals(
            listOf("Il genitore l'ha ritirata"),
            TestoProposta.righeChiusa(chiusa(StatiProposta.RITIRATA, AutoriProposta.GENITORE), paroleStoria).map { it.testo },
        )
        assertEquals(FineProposta.ANNULLATA, TestoProposta.fine(chiusa(StatiProposta.ANNULLATA, AutoriProposta.FIGLIO)))
        assertEquals(FineProposta.GENITORE_HA_ACCETTATO, TestoProposta.fine(chiusa(StatiProposta.ACCETTATA, AutoriProposta.FIGLIO)))
        // Uno stato che questa versione non conosce si mostra com'è.
        assertEquals(listOf("sospesa"), TestoProposta.righeChiusa(chiusa("sospesa", null), paroleStoria).map { it.testo })
    }

    // --- (0.10) Le notifiche: il genitore risponde o ritira ----------------------

    private fun payload(vararg coppie: Pair<String, Any>) = buildJsonObject {
        coppie.forEach { (chiave, valore) ->
            when (valore) {
                is Long -> put(chiave, valore)
                is Int -> put(chiave, valore)
                else -> put(chiave, valore.toString())
            }
        }
    }

    @Test
    fun `la risposta del genitore a una tua proposta si riconosce dall'autore`() {
        assertEquals(
            NovitaProposta.Risposta(propostaId = 21, regolaId = 7, accettata = true),
            TestoProposta.novita(
                TipiNotifica.PROPOSTA_RISPOSTA,
                payload("proposta_id" to 21L, "regola_id" to 7L, "esito" to "accetta", "autore" to "figlio"),
            ),
        )
        assertEquals(
            NovitaProposta.Risposta(propostaId = 21, regolaId = 7, accettata = false),
            TestoProposta.novita(
                TipiNotifica.PROPOSTA_RISPOSTA,
                payload("proposta_id" to 21L, "regola_id" to 7L, "esito" to "rifiuta", "autore" to "figlio"),
            ),
        )
        // Senza autore figlio non è una risposta alla tua: si dice come prima.
        assertNull(
            TestoProposta.novita(TipiNotifica.PROPOSTA_RISPOSTA, payload("proposta_id" to 21L, "esito" to "accetta")),
        )
        assertNull(TestoProposta.novita(TipiNotifica.NUOVA_PROPOSTA, payload("proposta_id" to 3L, "autore" to "figlio")))
    }

    @Test
    fun `il ritiro del genitore si riconosce`() {
        assertEquals(
            NovitaProposta.Ritiro(propostaId = 3, regolaId = 7),
            TestoProposta.novita(
                TipiNotifica.PROPOSTA_RITIRATA,
                payload("proposta_id" to 3L, "regola_id" to 7L, "autore" to "genitore"),
            ),
        )
        assertNull(TestoProposta.novita(TipiNotifica.PROPOSTA_RITIRATA, payload("proposta_id" to 3L, "autore" to "figlio")))
    }

    private val paroleNovita = ParoleNovita(
        accettataTitolo = "Il genitore ha accettato la tua proposta",
        rifiutataTitolo = "Il genitore ha rifiutato la tua proposta",
        ritirataTitolo = "Il genitore ha ritirato la sua proposta",
        ora = "Ora: %1\$s",
        resta = "La regola resta: %1\$s",
        tolta = "La regola è stata eliminata.",
        regola = "Regola: %1\$s",
        genitoreDice = "Il genitore dice: %1\$s",
        ritirataEliminazione = "Era la proposta di eliminare la regola: %1\$s",
        ritirataEliminazioneSenzaRegola = "Era la proposta di eliminare una regola.",
    )

    @Test
    fun `accettata dice il confronto, la regola com'e' adesso e il perche' del genitore`() {
        val proposta = Proposta(
            id = 21, regolaId = 7, stato = StatiProposta.ACCETTATA, autore = AutoriProposta.FIGLIO,
            confronto = "+30 min al giorno rispetto ad ora", direzione = DirezioniProposta.ALLENTA,
            risposta = RispostaProposta(EsitiRisposta.ACCETTA, "solo per la verifica"),
        )
        assertEquals(
            "Il genitore ha accettato la tua proposta" to
                "+30 min al giorno rispetto ad ora\n" +
                "Ora: TikTok: al massimo 1 h 30 min al giorno\n" +
                "Il genitore dice: solo per la verifica",
            TestoProposta.avviso(
                NovitaProposta.Risposta(21, 7, accettata = true),
                proposta,
                "TikTok: al massimo 1 h 30 min al giorno",
                "Il genitore ha accettato la tua proposta: +30 min al giorno rispetto ad ora",
                paroleNovita,
            ),
        )
    }

    @Test
    fun `rifiutata o eliminazione accettata, dette per il figlio`() {
        val rifiutata = Proposta(
            id = 21, regolaId = 7, stato = StatiProposta.RIFIUTATA, autore = AutoriProposta.FIGLIO,
            confronto = "+30 min al giorno rispetto ad ora", direzione = DirezioniProposta.ALLENTA,
        )
        assertEquals(
            "Il genitore ha rifiutato la tua proposta" to
                "+30 min al giorno rispetto ad ora\nLa regola resta: TikTok: al massimo 1 h al giorno",
            TestoProposta.avviso(
                NovitaProposta.Risposta(21, 7, accettata = false), rifiutata,
                "TikTok: al massimo 1 h al giorno", "…", paroleNovita,
            ),
        )
        val tolta = Proposta(
            id = 22, regolaId = 7, stato = StatiProposta.ACCETTATA, autore = AutoriProposta.FIGLIO,
            parametriProposti = marcatoreElimina, confronto = "propone di eliminare la regola",
            direzione = DirezioniProposta.ELIMINA,
        )
        assertEquals(
            "Il genitore ha accettato la tua proposta" to "La regola è stata eliminata.",
            TestoProposta.avviso(NovitaProposta.Risposta(22, 7, accettata = true), tolta, null, "…", paroleNovita),
        )
    }

    @Test
    fun `il ritiro del genitore, e il testo del server quando non si sa altro`() {
        val ritirata = Proposta(
            id = 3, regolaId = 7, stato = StatiProposta.RITIRATA, autore = AutoriProposta.GENITORE,
            confronto = "−15 min al giorno rispetto ad ora", direzione = DirezioniProposta.STRINGE,
        )
        assertEquals(
            "Il genitore ha ritirato la sua proposta" to
                "−15 min al giorno rispetto ad ora\nRegola: TikTok: al massimo 1 h al giorno",
            TestoProposta.avviso(NovitaProposta.Ritiro(3, 7), ritirata, "TikTok: al massimo 1 h al giorno", "…", paroleNovita),
        )
        assertEquals(
            "Il genitore ha ritirato la sua proposta" to "Il genitore ha ritirato la sua proposta",
            TestoProposta.avviso(
                NovitaProposta.Ritiro(3, 7), null, null, "Il genitore ha ritirato la sua proposta", paroleNovita,
            ),
        )
    }

    @Test
    fun `ritirata una proposta di eliminare, si dice che era quella`() {
        val ritirata = Proposta(
            id = 4, regolaId = 7, stato = StatiProposta.RITIRATA, autore = AutoriProposta.GENITORE,
            parametriProposti = marcatoreElimina, confronto = "propone di eliminare la regola",
            direzione = DirezioniProposta.ELIMINA,
        )
        assertEquals(
            "Il genitore ha ritirato la sua proposta" to
                "Era la proposta di eliminare la regola: TikTok: al massimo 1 h al giorno",
            TestoProposta.avviso(NovitaProposta.Ritiro(4, 7), ritirata, "TikTok: al massimo 1 h al giorno", "…", paroleNovita),
        )
        assertEquals(
            "Il genitore ha ritirato la sua proposta" to "Era la proposta di eliminare una regola.",
            TestoProposta.avviso(NovitaProposta.Ritiro(4, 7), ritirata, null, "…", paroleNovita),
        )
    }
}
