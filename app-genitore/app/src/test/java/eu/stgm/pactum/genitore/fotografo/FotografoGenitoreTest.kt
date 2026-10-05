package eu.stgm.pactum.genitore.fotografo

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import eu.stgm.pactum.genitore.MainActivity
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.Famiglia
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.fotografo.DatiFinti.LUCA
import eu.stgm.pactum.genitore.fotografo.DatiFinti.SARA
import eu.stgm.pactum.genitore.fotografo.ServerFinto.Companion.corpo
import eu.stgm.pactum.genitore.fotografo.Variante.Companion.CHIARO_360
import eu.stgm.pactum.genitore.fotografo.Variante.Companion.CHIARO_360_GRANDE
import eu.stgm.pactum.genitore.fotografo.Variante.Companion.CHIARO_411
import eu.stgm.pactum.genitore.ui.giornoBreve
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * Il FOTOGRAFO dell'app del genitore (0.15): disegna sul PC, senza telefono né
 * emulatore, ogni scheda, ogni pagina e i dialoghi principali, coi dati finti di
 * [DatiFinti] serviti da un server finto. L'app è quella vera (MainActivity, i
 * ViewModel veri, la rete vera verso il server finto): niente è rifatto apposta.
 *
 * Non fa parte della suite normale: parte solo con
 *   gradlew :app:testDebugUnitTest -Pfotografo -Pfotografo.cartella=…
 * L'app è sempre chiara: ogni stato a 360 dp, testo normale e grande (1,3); le
 * quattro schede principali anche a 411 dp. Le pagine successive (-p2…) solo a 360.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [35], application = AppFotografo::class)
class FotografoGenitoreTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val server = ServerFinto()
    private lateinit var f: Fotografo

    /** Ogni stato: chiaro a 360, testo normale e grande. */
    private val due = listOf(CHIARO_360, CHIARO_360_GRANDE)

    /** Le quattro schede principali: anche a 411. */
    private val tre = due + CHIARO_411

    @Before
    fun prima() {
        server.avvia()
        f = Fotografo(compose, server)
    }

    @After
    fun dopo() {
        f.scriviElenco()
        f.chiudi()
        server.ferma()
    }

    private fun s(id: Int, vararg argomenti: Any) = f.s(id, *argomenti)

    // --- Scenari in più ------------------------------------------------------------------

    /** Sara con un telefono collegato ma senza nessuna regola. */
    private fun scenarioSaraSenzaRegole(): Scenario {
        val normale = DatiFinti.scenarioNormale()
        val famiglia = DatiFinti.famiglia().let { fam ->
            fam.copy(figli = fam.figli.map { if (it.id == SARA) it.copy(dispositivi = it.dispositivi.map { d -> d.copy(statoSilenzio = DatiFinti.silenzioInContatto()) }, striscia = DatiFinti.striscia("grigio", "grigio", "grigio", "grigio", "grigio", "grigio", "grigio", "grigio")) else it })
        }
        return normale.copy(
            famiglia = { corpo(Famiglia.serializer(), famiglia) },
            finestra = { id -> if (id == SARA) corpo(Finestra.serializer(), DatiFinti.finestraSenzaRegole()) else corpo(Finestra.serializer(), DatiFinti.finestraLuca()) },
        )
    }

    /** I lavori di casa su un server più vecchio della v3.6 (la rotta non c'è). */
    private fun scenarioFaccendeServerVecchio(): Scenario = DatiFinti.scenarioNormale().copy(
        faccende = { ServerFinto.Risposta.Errore(404) },
        finestra = { id ->
            if (id == SARA) corpo(Finestra.serializer(), DatiFinti.finestraSara().copy(faccende = null, blocco = null))
            else corpo(Finestra.serializer(), DatiFinti.finestraLuca(faccende = null).copy(blocco = null))
        },
    )

    private val luca = Preparazione(figlioScelto = LUCA)
    private val sara = Preparazione(figlioScelto = SARA)

    // --- 01 Panoramica ----------------------------------------------------------------------

    private val panoramicaPronta: Fotografo.() -> Boolean = {
        nonCe(s(R.string.finestra_caricamento)) && ce(s(R.string.patto_ultimi_giorni))
    }

    @Test
    fun panoramica() {
        tre.forEach { v ->
            f.scatta(
                "01-panoramica_normale", "Panoramica di Luca (2 figli): richieste da decidere, blocco attivo, patto, dispositivi", v,
                preparazione = luca, pagine = v == CHIARO_360, pronto = panoramicaPronta,
            )
        }
        due.forEach { v ->
            f.scatta(
                "01-panoramica_sara-telefono-silenzioso", "Panoramica di Sara: telefono silenzioso da 2 ore", v,
                preparazione = sara, pagine = v == CHIARO_360, pronto = panoramicaPronta,
            )
        }
        due.forEach { v ->
            f.scatta(
                "01-panoramica_un-figlio", "Panoramica con un figlio solo (Luca): il nome in cima alla card del patto", v,
                scenario = DatiFinti.scenarioNormale(soloLuca = true), pagine = v == CHIARO_360, pronto = panoramicaPronta,
            )
        }
        due.forEach { v ->
            f.scatta(
                "01-panoramica_vuoto-figlio-senza-dispositivi", "Panoramica di Sara appena creata: nessun dispositivo (col pulsante)", v,
                scenario = DatiFinti.scenarioVuoto(), preparazione = sara,
                pronto = { ce(s(R.string.nessun_dispositivo_titolo)) },
            )
        }
        f.scatta(
            "01-panoramica_vuoto-senza-regole", "Panoramica di Sara: telefono collegato, ancora nessuna regola", CHIARO_360,
            scenario = scenarioSaraSenzaRegole(), preparazione = sara,
            pronto = { ce(s(R.string.regole_vuoto_titolo)) },
        )
        due.forEach { v ->
            f.scatta(
                "01-panoramica_dati-vecchi", "Panoramica coi dati di prima: la rete è caduta dopo la prima lettura", v,
                preparazione = luca, pronto = panoramicaPronta,
                gesti = {
                    server.scenario = DatiFinti.scenarioSenzaRete()
                    toccaDescrizione(s(R.string.azione_aggiorna))
                },
                dopo = { ce("Dati non aggiornati") },
            )
        }
        // (0.15) Si guarda Luca, cade la rete, si tocca Sara: l'errore resta SOTTO la
        // scelta del figlio, e si può tornare a Luca.
        f.scatta(
            "01-panoramica_cambio-figlio-senza-rete", "Panoramica: tocco su Sara mentre la rete è caduta (la scelta del figlio resta in cima)", CHIARO_360,
            preparazione = luca, pronto = panoramicaPronta,
            gesti = {
                val normale = DatiFinti.scenarioNormale()
                server.scenario = normale.copy(
                    finestra = { id -> if (id == DatiFinti.SARA) ServerFinto.Risposta.SenzaRete else normale.finestra(id) },
                )
                tocca("Sara", esatto = true)
            },
            dopo = { ce(s(R.string.finestra_errore_nessun_dato)) && ce("Luca") },
        )
        due.forEach { v ->
            f.scatta(
                "01-panoramica_senza-rete", "Panoramica alla prima apertura senza rete (niente dati in mano)", v,
                scenario = DatiFinti.scenarioSenzaRete(),
                pronto = { ce(s(R.string.finestra_errore_nessun_dato)) },
            )
        }
        due.forEach { v ->
            f.scatta(
                "01-panoramica_collegamento-non-valido", "Panoramica: il collegamento di questo telefono non vale più (401)", v,
                scenario = DatiFinti.scenarioNonValido(),
                pronto = { ce(s(R.string.collegamento_non_valido_titolo)) },
            )
        }
        due.forEach { v ->
            f.scatta(
                "01-panoramica_prima-apertura", "Panoramica alla prima apertura: manca il collegamento (col pulsante)", v,
                scenario = DatiFinti.scenarioSenzaRete(), preparazione = Preparazione(configurato = false, ultimoControlloMinutiFa = null),
                pronto = { ce(s(R.string.config_mancante_titolo)) },
            )
        }
        f.scatta(
            "01-panoramica_server-vecchio", "Panoramica su un server 0.7 (niente famiglia, un telefono)", CHIARO_360,
            scenario = DatiFinti.scenarioServerVecchio(), pagine = true,
            pronto = { nonCe(s(R.string.finestra_caricamento)) && ce(s(R.string.patto_ultimi_giorni)) },
        )
        f.scatta(
            "01-panoramica_avvisi-spenti", "Panoramica con gli avvisi di Pactum spenti su questo telefono", CHIARO_360,
            preparazione = Preparazione(notifiche = false, figlioScelto = LUCA), pronto = panoramicaPronta,
        )
        f.scatta(
            "01-panoramica_avvisi-in-ritardo", "Panoramica: la vedetta non controlla da 3 ore", CHIARO_360,
            preparazione = Preparazione(ultimoControlloMinutiFa = 180, figlioScelto = LUCA), pronto = panoramicaPronta,
        )
        f.scatta(
            "02-panoramica_segno-mandato", "Panoramica dopo «Manda un segno» (la frase in basso)", CHIARO_360,
            preparazione = luca, pronto = panoramicaPronta,
            gesti = {
                scorriFino(s(R.string.segno_manda))
                tocca(R.string.segno_manda)
            },
            dopo = { ce(s(R.string.segno_mandato)) },
        )
    }

    // --- 11 Storico del patto, 12 dettaglio di una regola ----------------------------------------------

    @Test
    fun storicoERegola() {
        due.forEach { v ->
            f.scatta(
                "11-storico_normale", "Storico del patto di Luca: regole cambiate, sessioni, proposte chiuse, dichiarazioni", v,
                preparazione = luca, pagine = v == CHIARO_360, pronto = panoramicaPronta,
                gesti = {
                    scorriFino(s(R.string.sezione_storico))
                    tocca(s(R.string.sezione_storico), esatto = true)
                },
                dopo = { ce(s(R.string.storico_regole)) && nonCe(s(R.string.storico_caricamento)) },
            )
        }
        // (0.16) Le sessioni e tutte le regole, in pagine loro (dalla Panoramica).
        due.forEach { v ->
            f.scatta(
                "13-sessioni_normale", "Sessioni di Luca: in corso, approvate, fatte negli ultimi 8 giorni", v,
                preparazione = luca, pagine = v == CHIARO_360, pronto = panoramicaPronta,
                gesti = {
                    scorriFino(s(R.string.sezione_sessioni), esatto = true)
                    tocca(s(R.string.sezione_sessioni), esatto = true)
                },
                dopo = { ce(s(R.string.sessioni_approvate_titolo)) && nonCe(s(R.string.sessioni_caricamento)) },
            )
        }
        due.forEach { v ->
            f.scatta(
                "14-tutte-le-regole_normale", "Tutte le regole di Luca, per dispositivo, con la striscia di ciascuna", v,
                preparazione = luca, pagine = v == CHIARO_360, pronto = panoramicaPronta,
                gesti = {
                    scorriFino(s(R.string.tutte_le_regole), esatto = true)
                    tocca(s(R.string.tutte_le_regole), esatto = true)
                },
                dopo = { ce("TikTok: al massimo") && nonCe(s(R.string.tutte_le_regole_caricamento)) && nonCe(s(R.string.patto_ultimi_giorni)) },
            )
        }
        due.forEach { v ->
            f.scatta(
                "12-regola_dettaglio", "Dettaglio della regola «TikTok» (aperto dalla card del patto)", v,
                preparazione = luca, pagine = v == CHIARO_360, pronto = panoramicaPronta,
                gesti = { tocca("TikTok: al massimo") },
                dopo = { ce(s(R.string.regola_titolo)) && (ce(s(R.string.proposte_bottone_proponi)) || ce("già una proposta")) },
            )
        }
        due.forEach { v ->
            f.scatta(
                "12-regola-dialogo_nuova-proposta", "Dialogo «Proponi una modifica» su una regola di tempo (dal dettaglio)", v,
                pagineDialogo = true, preparazione = luca, pronto = panoramicaPronta,
                gesti = {
                    tocca("Tutto il telefono: al massimo")
                    aspetta("dettaglio") { ce(s(R.string.proposte_bottone_proponi)) }
                    tocca(R.string.proposte_bottone_proponi)
                },
                dopo = { ce(s(R.string.proposta_invia)) },
            )
        }
        f.scatta(
            "12-regola-dialogo_nuova-proposta-fascia", "Dialogo «Proponi una modifica» su una fascia oraria di Sara", CHIARO_360,
            pagineDialogo = true, preparazione = sara, pronto = panoramicaPronta,
            gesti = {
                tocca("Niente telefono dalle 21:30")
                aspetta("dettaglio") { ce(s(R.string.proposte_bottone_proponi)) }
                tocca(R.string.proposte_bottone_proponi)
            },
            dopo = { ce(s(R.string.proposta_invia)) },
        )
    }

    // --- 07 Da decidere --------------------------------------------------------------------------

    private val daDecidereProntaLuca: Fotografo.() -> Boolean = {
        nonCe(s(R.string.turno_caricamento)) && (ce("Luca chiede") || ce("Luca ti propone") || ce(s(R.string.verdetto_conferma)))
    }

    @Test
    fun daDecidere() {
        tre.forEach { v ->
            f.scatta(
                "07-da-decidere_normale", "Da decidere di Luca: proposta, due sessioni, una dichiarazione, la tua proposta in attesa", v,
                destinazione = MainActivity.DEST_DECIDERE, preparazione = luca,
                pagine = v == CHIARO_360, pronto = daDecidereProntaLuca,
            )
        }
        due.forEach { v ->
            f.scatta(
                "07-da-decidere_vuoto", "Da decidere di Sara: niente da decidere", v,
                destinazione = MainActivity.DEST_DECIDERE, preparazione = sara,
                pronto = { nonCe(s(R.string.turno_caricamento)) && ce(s(R.string.da_decidere_vuoto)) },
            )
        }
        f.scatta(
            "07-da-decidere_senza-rete", "Da decidere senza rete", CHIARO_360,
            scenario = DatiFinti.scenarioSenzaRete(), destinazione = MainActivity.DEST_DECIDERE,
            pronto = { ce(s(R.string.turno_errore)) },
        )
        f.scatta(
            "07-da-decidere_collegamento-non-valido", "Da decidere: collegamento non più valido (401)", CHIARO_360,
            scenario = DatiFinti.scenarioNonValido(), destinazione = MainActivity.DEST_DECIDERE,
            pronto = { ce(s(R.string.collegamento_non_valido_titolo)) },
        )
        f.scatta(
            "07-da-decidere_server-vecchio", "Da decidere su un server 0.7", CHIARO_360,
            scenario = DatiFinti.scenarioServerVecchio(), destinazione = MainActivity.DEST_DECIDERE, pagine = true,
            pronto = { nonCe(s(R.string.turno_caricamento)) && ce(s(R.string.sezione_storico)) },
        )
    }

    @Test
    fun daDecidereDialoghi() {
        due.forEach { v ->
            f.scatta(
                "07-da-decidere-dialogo_accetta-proposta", "Dialogo «Accetta» sulla proposta di Luca", v,
                destinazione = MainActivity.DEST_DECIDERE, preparazione = luca, pronto = daDecidereProntaLuca,
                gesti = {
                    scorriFino(s(R.string.proposta_accetta), esatto = true)
                    tocca(R.string.proposta_accetta)
                },
                dopo = { ce(s(R.string.azione_annulla)) },
            )
        }
        f.scatta(
            "07-da-decidere-dialogo_rifiuta-proposta", "Dialogo «Rifiuta» sulla proposta di Luca", CHIARO_360,
            destinazione = MainActivity.DEST_DECIDERE, preparazione = luca, pronto = daDecidereProntaLuca,
            gesti = {
                scorriFino(s(R.string.proposta_rifiuta), esatto = true)
                tocca(R.string.proposta_rifiuta)
            },
            dopo = { ce(s(R.string.azione_annulla)) },
        )
        due.forEach { v ->
            f.scatta(
                "07-da-decidere-dialogo_approva-sessione", "Dialogo «Approva» sul cambio della sessione Studio", v,
                pagineDialogo = true,
                destinazione = MainActivity.DEST_DECIDERE, preparazione = luca, pronto = daDecidereProntaLuca,
                gesti = {
                    scorriFino(s(R.string.sessione_approva), esatto = true)
                    tocca(R.string.sessione_approva)
                },
                dopo = { ce(s(R.string.azione_annulla)) },
            )
        }
        f.scatta(
            "07-da-decidere-dialogo_rifiuta-sessione", "Dialogo «Rifiuta» sulla sessione nuova «Allenamento»", CHIARO_360,
            pagineDialogo = true,
            destinazione = MainActivity.DEST_DECIDERE, preparazione = luca, pronto = daDecidereProntaLuca,
            gesti = {
                scorriFino("Allenamento")
                // Il primo «Rifiuta» della lista: la sessione nuova viene prima della proposta.
                scorriFino(s(R.string.sessione_rifiuta), esatto = true)
                tocca(s(R.string.sessione_rifiuta), indice = 0, esatto = true)
            },
            dopo = { ce(s(R.string.azione_annulla)) },
        )
        f.scatta(
            "07-da-decidere-dialogo_non-e-andata-cosi", "Domanda prima di «Non è andata così» (dal ⋯ della dichiarazione)", CHIARO_360,
            destinazione = MainActivity.DEST_DECIDERE, preparazione = luca, pronto = daDecidereProntaLuca,
            gesti = {
                scorriFino(s(R.string.verdetto_conferma), esatto = true)
                toccaDescrizione(s(R.string.azione_altre_risposte))
                tocca(R.string.verdetto_ribalta)
            },
            dopo = { ce(s(R.string.verdetto_ribalta_titolo)) },
        )
    }

    // --- 03 Notifiche ----------------------------------------------------------------------

    private val notifichePronte: Fotografo.() -> Boolean = {
        nonCe(s(R.string.notifiche_caricamento)) && ce(s(R.string.notifiche_segna_tutte))
    }

    @Test
    fun notifiche() {
        due.forEach { v ->
            f.scatta(
                "03-notifiche_normale", "Notifiche non lette (di tutti e due i figli): ogni riga si tocca", v,
                destinazione = MainActivity.DEST_NOTIFICHE, pagine = v == CHIARO_360, pronto = notifichePronte,
            )
        }
        f.scatta(
            "03-notifiche_vuoto", "Notifiche: niente di nuovo", CHIARO_360,
            scenario = DatiFinti.scenarioVuoto(), destinazione = MainActivity.DEST_NOTIFICHE,
            pronto = { ce(s(R.string.notifiche_vuoto)) },
        )
        f.scatta(
            "03-notifiche_senza-rete", "Notifiche senza rete", CHIARO_360,
            scenario = DatiFinti.scenarioSenzaRete(), destinazione = MainActivity.DEST_NOTIFICHE,
            pronto = { ce(s(R.string.notifiche_errore)) },
        )
        f.scatta(
            "03-notifiche_collegamento-non-valido", "Notifiche: collegamento non più valido (401)", CHIARO_360,
            scenario = DatiFinti.scenarioNonValido(), destinazione = MainActivity.DEST_NOTIFICHE,
            pronto = { ce(s(R.string.collegamento_non_valido_titolo)) },
        )
        f.scatta(
            "03-notifiche_dialogo-segna-tutte", "Dialogo «Segna tutte come lette»", CHIARO_360,
            destinazione = MainActivity.DEST_NOTIFICHE, pronto = notifichePronte,
            gesti = { tocca(R.string.notifiche_segna_tutte) },
            dopo = { ce(s(R.string.notifiche_segna_tutte_titolo)) },
        )
    }

    // --- 04 Lavori di casa ------------------------------------------------------------------------

    private val faccendePronte: Fotografo.() -> Boolean = {
        nonCe(s(R.string.faccende_caricamento)) && ce("Svuota la lavastoviglie")
    }

    @Test
    fun lavoriDiCasa() {
        tre.forEach { v ->
            f.scatta(
                "04-lavori-di-casa_normale", "Lavori di casa di Luca: blocco attivo, da fare, fatti (con foto), tolti", v,
                destinazione = MainActivity.DEST_FACCENDE, preparazione = luca,
                pagine = v == CHIARO_360, pronto = faccendePronte,
            )
        }
        due.forEach { v ->
            f.scatta(
                "04-lavori-di-casa_vuoto", "Lavori di casa di Sara: nessuno negli ultimi 30 giorni", v,
                destinazione = MainActivity.DEST_FACCENDE, preparazione = sara,
                pronto = { ce(s(R.string.faccende_nessuna)) },
            )
        }
        f.scatta(
            "04-lavori-di-casa_server-vecchio", "Lavori di casa su un server più vecchio della v3.6", CHIARO_360,
            scenario = scenarioFaccendeServerVecchio(), destinazione = MainActivity.DEST_FACCENDE, preparazione = luca,
            pronto = { ce(s(R.string.faccende_server_vecchio_titolo)) },
        )
        f.scatta(
            "04-lavori-di-casa_senza-rete", "Lavori di casa senza rete", CHIARO_360,
            scenario = DatiFinti.scenarioSenzaRete(), destinazione = MainActivity.DEST_FACCENDE,
            pronto = { ce(s(R.string.faccende_errore)) },
        )
        f.scatta(
            "04-lavori-di-casa_collegamento-non-valido", "Lavori di casa: collegamento non più valido (401)", CHIARO_360,
            scenario = DatiFinti.scenarioNonValido(), destinazione = MainActivity.DEST_FACCENDE,
            pronto = { ce(s(R.string.collegamento_non_valido_titolo)) },
        )
        f.scatta(
            "04-lavori-di-casa_prima-apertura", "Lavori di casa senza collegamento (il testo giusto, col pulsante)", CHIARO_360,
            scenario = DatiFinti.scenarioSenzaRete(), destinazione = MainActivity.DEST_FACCENDE,
            preparazione = Preparazione(configurato = false, ultimoControlloMinutiFa = null),
            pronto = { ce(s(R.string.faccende_config_mancante)) },
        )
        f.scatta(
            "04-lavori-di-casa_tolti-aperti", "Lavori di casa con la sezione «Tolti» aperta", CHIARO_360,
            destinazione = MainActivity.DEST_FACCENDE, preparazione = luca, pronto = faccendePronte,
            gesti = {
                scorriFino(s(R.string.faccende_annullate))
                tocca(s(R.string.faccende_annullate))
            },
            dopo = { ce("Tolto da") },
        )
    }

    // --- 05 Dai lavori di casa, dialoghi e foto ------------------------------------------------------

    @Test
    fun lavoriDiCasaDialoghi() {
        due.forEach { v ->
            f.scatta(
                "05-lavori-dai_vuoto", "Pagina «Dai lavori di casa» appena aperta", v,
                destinazione = MainActivity.DEST_FACCENDE, preparazione = luca, pronto = faccendePronte,
                pagine = v == CHIARO_360,
                gesti = { tocca(R.string.faccende_dai) },
                dopo = { ce(s(R.string.dai_manda)) },
            )
        }
        due.forEach { v ->
            f.scatta(
                "05-lavori-dai_compilato", "Pagina «Dai lavori di casa» compilata: due lavori, una nota, blocco dalle…", v,
                destinazione = MainActivity.DEST_FACCENDE, preparazione = luca, pronto = faccendePronte,
                pagine = v == CHIARO_360,
                gesti = {
                    tocca(R.string.faccende_dai)
                    aspetta("pagina dai") { ce(s(R.string.dai_manda)) }
                    scrivi(0, "Apparecchia la tavola per sei")
                    tocca(R.string.dai_aggiungi)
                    tocca("Porta fuori la spazzatura", indice = 0, esatto = true)
                    // La nota è l'ultimo campo di testo.
                    val campi = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size
                    scrivi(campi - 1, "Prima che arrivino i nonni, alle 19:30")
                    // "Dalle…": la seconda riga del blocco (tutta la riga si tocca).
                    val righe = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                    misuraTutto()
                    righe[1].performScrollTo()
                    toccaNodo(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton), 1)
                    inCima()
                },
                dopo = { ce(s(R.string.dai_manda)) },
            )
        }
        f.scatta(
            "05-lavori-dai_scegli-ora", "Dialogo «Da che ora» (orologio) sopra «Dai lavori di casa»", CHIARO_360,
            destinazione = MainActivity.DEST_FACCENDE, preparazione = luca, pronto = faccendePronte,
            gesti = {
                tocca(R.string.faccende_dai)
                aspetta("pagina dai") { ce(s(R.string.dai_manda)) }
                scorriFino(s(R.string.dai_cambia_ora), esatto = true)
                tocca(R.string.dai_cambia_ora)
            },
            dopo = { ce(s(R.string.dai_scegli_ora)) },
        )
        due.forEach { v ->
            f.scatta(
                "05-lavori-dialogo_boccia", "Dialogo «Bocciare…?» su «Porta fuori la spazzatura»", v,
                destinazione = MainActivity.DEST_FACCENDE, preparazione = luca, pronto = faccendePronte,
                gesti = {
                    scorriFino(s(R.string.faccenda_boccia), esatto = true)
                    tocca(R.string.faccenda_boccia)
                },
                dopo = { ce("Bocciare «") },
            )
        }
        f.scatta(
            "05-lavori-dialogo_togli", "Dialogo «Togliere…?» su un lavoro da fare", CHIARO_360,
            destinazione = MainActivity.DEST_FACCENDE, preparazione = luca, pronto = faccendePronte,
            gesti = {
                toccaDescrizione(s(R.string.faccenda_azioni, "Svuota la lavastoviglie"))
                aspetta("menu") { ce(s(R.string.faccenda_togli)) }
                tocca(R.string.faccenda_togli)
            },
            dopo = { ce(s(R.string.togli_faccenda_lascia)) },
        )
        // (0.17) Il ⋯ di un lavoro da fare, e la pagina "Cambia il lavoro".
        f.scatta(
            "05-lavori-menu_modifica", "Il ⋯ di un lavoro da fare: «Modifica» e «Togli»", CHIARO_360,
            destinazione = MainActivity.DEST_FACCENDE, preparazione = luca, pronto = faccendePronte,
            gesti = { toccaDescrizione(s(R.string.faccenda_azioni, "Svuota la lavastoviglie")) },
            dopo = { ce(s(R.string.faccenda_modifica)) },
        )
        due.forEach { v ->
            f.scatta(
                "05-lavori-modifica", "Pagina «Cambia il lavoro» (dal ⋯ di «Svuota la lavastoviglie»)", v,
                destinazione = MainActivity.DEST_FACCENDE, preparazione = luca, pronto = faccendePronte,
                pagine = v == CHIARO_360,
                gesti = {
                    toccaDescrizione(s(R.string.faccenda_azioni, "Svuota la lavastoviglie"))
                    aspetta("menu") { ce(s(R.string.faccenda_modifica)) }
                    tocca(R.string.faccenda_modifica)
                },
                dopo = { ce(s(R.string.modifica_salva)) && ce("Anche le posate") },
            )
        }
        // (0.17) Guardata la foto, il pulsante diventa "Segna come svolto"; poi la domanda.
        val guardaEChiudi: Fotografo.() -> Unit = {
            scorriFino(s(R.string.faccenda_guarda_foto), esatto = true)
            tocca(R.string.faccenda_guarda_foto)
            aspetta("foto") { nonCe(s(R.string.foto_caricamento)) && ce(s(R.string.faccenda_segna_svolto)) }
            toccaDescrizione(s(R.string.foto_chiudi))
            aspetta("lista") { nonCe(s(R.string.foto_chiudi)) && ce(s(R.string.faccenda_segna_svolto)) }
            scorriFino(s(R.string.faccenda_segna_svolto), esatto = true)
        }
        due.forEach { v ->
            f.scatta(
                "04-lavori-di-casa_segna-svolto", "Lavori di casa dopo aver guardato la foto: «Segna come svolto»", v,
                destinazione = MainActivity.DEST_FACCENDE, preparazione = luca, pronto = faccendePronte,
                gesti = guardaEChiudi,
                dopo = { ce(s(R.string.faccenda_segna_svolto)) },
            )
        }
        f.scatta(
            "05-lavori-dialogo_svolto", "Dialogo «Segnare… come svolto?»", CHIARO_360,
            destinazione = MainActivity.DEST_FACCENDE, preparazione = luca, pronto = faccendePronte,
            gesti = {
                guardaEChiudi()
                tocca(R.string.faccenda_segna_svolto)
            },
            dopo = { ce("come svolto?") },
        )
        // (0.17) La ricerca in tutta la storia: con risultati (anche vecchi) e vuota.
        due.forEach { v ->
            f.scatta(
                "04-lavori-di-casa_ricerca", "Ricerca «lava» nei lavori di casa: risultati di tutta la storia", v,
                destinazione = MainActivity.DEST_FACCENDE, preparazione = luca, pronto = faccendePronte,
                pagine = v == CHIARO_360,
                gesti = {
                    scorriFino(s(R.string.faccende_fatte), esatto = true)
                    scrivi(0, "lava")
                },
                dopo = { ce("Lavatrice: stendi i bianchi") && nonCe(s(R.string.ricerca_caricamento)) },
            )
        }
        f.scatta(
            "04-lavori-di-casa_ricerca-vuota", "Ricerca senza risultati", CHIARO_360,
            destinazione = MainActivity.DEST_FACCENDE, preparazione = luca, pronto = faccendePronte,
            gesti = {
                scorriFino(s(R.string.faccende_fatte), esatto = true)
                scrivi(0, "aspirapolvere")
            },
            dopo = { ce("Nessun lavoro con") },
        )
        val fotoAperta: Fotografo.() -> Boolean = {
            ce(s(R.string.foto_chiudi)) || compose.onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription),
            ).fetchSemanticsNodes().any { n ->
                n.config[SemanticsProperties.ContentDescription].any { it.startsWith("Foto del lavoro") }
            }
        }
        due.forEach { v ->
            f.scatta(
                "05-lavori-foto_a-tutto-schermo", "Foto a tutto schermo (aperta dalla notifica «lavoro fatto»)", v,
                destinazione = MainActivity.DEST_FACCENDE, faccenda = 103L, preparazione = luca,
                pronto = { fotoAperta() && nonCe(s(R.string.foto_caricamento)) && ce(s(R.string.faccenda_boccia)) },
            )
        }
        f.scatta(
            "05-lavori-foto_boccia-sopra-la-foto", "Dialogo «Bocciare…?» aperto dalla foto", CHIARO_360,
            destinazione = MainActivity.DEST_FACCENDE, faccenda = 103L, preparazione = luca,
            pronto = { nonCe(s(R.string.foto_caricamento)) && ce(s(R.string.faccenda_boccia)) && fotoAperta() },
            gesti = { tocca(s(R.string.faccenda_boccia), indice = 0, esatto = true) },
            dopo = { ce("Bocciare «") },
        )
        f.scatta(
            "05-lavori-foto_non-trovata", "Foto che non c'è più (bocciata o oltre 30 giorni)", CHIARO_360,
            scenario = DatiFinti.scenarioNormale().copy(foto = { ServerFinto.Risposta.Errore(404, "{\"detail\": \"foto non trovata\"}") }),
            destinazione = MainActivity.DEST_FACCENDE, faccenda = 103L, preparazione = luca,
            pronto = { ce(s(R.string.foto_non_trovata)) },
        )
        f.scatta(
            "05-lavori-foto_errore", "Foto che non si scarica (rete), con «Riprova»", CHIARO_360,
            scenario = DatiFinti.scenarioNormale().copy(foto = { ServerFinto.Risposta.SenzaRete }),
            destinazione = MainActivity.DEST_FACCENDE, faccenda = 103L, preparazione = luca,
            pronto = { ce(s(R.string.foto_errore)) },
        )
    }

    // --- 06 Tempo ------------------------------------------------------------------------------

    private val tempoPronto: Fotografo.() -> Boolean = {
        nonCe(s(R.string.tempo_caricamento)) && ce("Computer di camera") && ce(s(R.string.tempo_chip_oggi))
    }

    @Test
    fun tempo() {
        tre.forEach { v ->
            f.scatta(
                "06-tempo_normale", "Tempo di Luca, telefono, oggi (oggi scelto e visibile)", v,
                destinazione = MainActivity.DEST_TEMPO, preparazione = luca,
                pagine = v == CHIARO_360, pronto = tempoPronto,
            )
        }
        due.forEach { v ->
            f.scatta(
                "06-tempo_computer", "Tempo di Luca, computer (spento), oggi", v,
                destinazione = MainActivity.DEST_TEMPO, preparazione = luca,
                pagine = v == CHIARO_360, pronto = tempoPronto,
                gesti = { tocca("Computer di camera") },
                dopo = {
                    compose.onAllNodes(hasText("Computer di camera", substring = true) and isSelected())
                        .fetchSemanticsNodes().isNotEmpty()
                },
            )
        }
        val quattroGiorniFa = giornoBreve(DatiFinti.oggi().minusDays(3).toString())
        due.forEach { v ->
            f.scatta(
                "06-tempo_giorno-senza-dati", "Tempo di Luca, telefono, un giorno senza dati ($quattroGiorniFa)", v,
                destinazione = MainActivity.DEST_TEMPO, preparazione = luca, pronto = tempoPronto,
                gesti = { tocca(quattroGiorniFa, esatto = true) },
                dopo = { ce(s(R.string.tempo_nessun_dato)) },
            )
        }
        f.scatta(
            "06-tempo_sara", "Tempo di Sara (un telefono, senza siti)", CHIARO_360,
            destinazione = MainActivity.DEST_TEMPO, preparazione = sara, pagine = true,
            pronto = { nonCe(s(R.string.tempo_caricamento)) && ce(s(R.string.tempo_etichetta_oggi)) },
        )
        f.scatta(
            "06-tempo_spiegazione-siti", "Tempo di Luca: la «i» dei siti aperta, tutti i siti", CHIARO_360,
            destinazione = MainActivity.DEST_TEMPO, preparazione = luca, pronto = tempoPronto,
            gesti = {
                scorriFino(s(R.string.siti_sezione_titolo))
                toccaDescrizione(s(R.string.siti_spiegazione))
            },
            dopo = { ce(s(R.string.siti_stessa_lista)) },
        )
        f.scatta(
            "06-tempo_vuoto-figlio-senza-dispositivi", "Tempo di Sara appena creata (nessun dispositivo)", CHIARO_360,
            scenario = DatiFinti.scenarioVuoto(), destinazione = MainActivity.DEST_TEMPO, preparazione = sara,
            pronto = { nonCe(s(R.string.tempo_caricamento)) && ce("Sara") },
        )
        f.scatta(
            "06-tempo_vuoto-nessun-dato", "Tempo di Sara: telefono collegato, nessun dato ancora", CHIARO_360,
            scenario = scenarioSaraSenzaRegole(), destinazione = MainActivity.DEST_TEMPO, preparazione = sara,
            pronto = { ce(s(R.string.tempo_nessuna_fotografia_titolo)) || ce(s(R.string.tempo_nessun_dato)) },
        )
        f.scatta(
            "06-tempo_senza-rete", "Tempo senza rete", CHIARO_360,
            scenario = DatiFinti.scenarioSenzaRete(), destinazione = MainActivity.DEST_TEMPO,
            pronto = { ce(s(R.string.tempo_errore)) },
        )
        f.scatta(
            "06-tempo_collegamento-non-valido", "Tempo: collegamento non più valido (401, non «server irraggiungibile»)", CHIARO_360,
            scenario = DatiFinti.scenarioNonValido(), destinazione = MainActivity.DEST_TEMPO,
            pronto = { ce(s(R.string.collegamento_non_valido_titolo)) },
        )
        f.scatta(
            "06-tempo_dati-vecchi", "Tempo coi dati di prima (rete caduta)", CHIARO_360,
            destinazione = MainActivity.DEST_TEMPO, preparazione = luca, pronto = tempoPronto,
            gesti = {
                server.scenario = DatiFinti.scenarioSenzaRete()
                toccaDescrizione(s(R.string.azione_aggiorna))
            },
            dopo = { ce("Dati non aggiornati") },
        )
        f.scatta(
            "06-tempo_server-vecchio", "Tempo su un server 0.7", CHIARO_360,
            scenario = DatiFinti.scenarioServerVecchio(), destinazione = MainActivity.DEST_TEMPO,
            pronto = { nonCe(s(R.string.tempo_caricamento)) && ce(s(R.string.tempo_chip_oggi)) },
        )
    }

    // --- 08 Impostazioni -------------------------------------------------------------------------------

    private val impostazioniAperte: Fotografo.() -> Unit = { toccaDescrizione(s(R.string.impostazioni_titolo)) }
    private val impostazioniPronte: Fotografo.() -> Boolean = { ce("Computer di camera") && ce("Nonna") }

    @Test
    fun impostazioni() {
        due.forEach { v ->
            f.scatta(
                "08-impostazioni_normale", "Impostazioni: famiglia, avvisi, riassunto della sera, collegamento (chiuso), versione, come funziona", v,
                pagine = v == CHIARO_360, preparazione = luca, pronto = panoramicaPronta,
                gesti = impostazioniAperte, dopo = impostazioniPronte,
            )
        }
        due.forEach { v ->
            f.scatta(
                "08-impostazioni_prima-apertura", "Impostazioni dal primo avvio («Collega questo telefono»): il collegamento in vista", v,
                scenario = DatiFinti.scenarioSenzaRete(), preparazione = Preparazione(configurato = false, ultimoControlloMinutiFa = null),
                pagine = v == CHIARO_360,
                pronto = { ce(s(R.string.config_mancante_titolo)) },
                gesti = { tocca(R.string.azione_collega) },
                dopo = { ce(s(R.string.connessione_codice)) },
            )
        }
        f.scatta(
            "08-impostazioni_collegamento-non-valido", "Impostazioni: collegamento non più valido (401)", CHIARO_360,
            scenario = DatiFinti.scenarioNonValido(), pagine = true,
            pronto = { ce(s(R.string.collegamento_non_valido_titolo)) },
            gesti = { tocca(R.string.azione_collega_di_nuovo) },
            dopo = { ce(s(R.string.connessione_codice)) },
        )
        f.scatta(
            "08-impostazioni_server-vecchio", "Impostazioni su un server 0.7", CHIARO_360,
            scenario = DatiFinti.scenarioServerVecchio(), pagine = true,
            pronto = { nonCe(s(R.string.finestra_caricamento)) && ce(s(R.string.patto_ultimi_giorni)) },
            gesti = impostazioniAperte, dopo = { ce(s(R.string.famiglia_server_vecchio)) },
        )
        f.scatta(
            "08-impostazioni_senza-rete", "Impostazioni senza rete (famiglia e genitori non letti)", CHIARO_360,
            scenario = DatiFinti.scenarioSenzaRete(), pagine = true,
            pronto = { ce(s(R.string.finestra_errore_nessun_dato)) },
            gesti = impostazioniAperte, dopo = { ce(s(R.string.famiglia_non_letta)) },
        )
        due.forEach { v ->
            f.scatta(
                "08-impostazioni_avvisi-da-sistemare", "Impostazioni aperte sugli «Avvisi del patto» (notifiche spente, batteria non esente)", v,
                destinazione = MainActivity.DEST_AVVISI,
                preparazione = Preparazione(notifiche = false, esenteBatteria = false),
                pronto = { ce(s(R.string.impostazioni_attivo_titolo)) && ce("Nonna") },
            )
        }
        f.scatta(
            "08-impostazioni_codice-lungo", "Impostazioni: «Cambia» il collegamento, codice d'accesso lungo aperto (nascosto)", CHIARO_360,
            preparazione = luca, pronto = panoramicaPronta,
            gesti = {
                impostazioniAperte()
                aspetta("impostazioni") { impostazioniPronte() }
                scorriFino(s(R.string.connessione_cambia), esatto = true)
                tocca(R.string.connessione_cambia)
                scorriFino(s(R.string.connessione_codice_lungo_apri), esatto = true)
                tocca(R.string.connessione_codice_lungo_apri)
                scorriFino(s(R.string.azione_salva), esatto = true)
            },
            dopo = { ce(s(R.string.connessione_codice_lungo_chiudi)) },
        )
    }

    // --- 09 Dialoghi delle Impostazioni -----------------------------------------------------------------

    private fun Fotografo.apriImpostazioni() {
        toccaDescrizione(s(R.string.impostazioni_titolo))
        aspetta("impostazioni") { ce("Computer di camera") && ce("Nonna") }
    }

    @Test
    fun impostazioniDialoghi() {
        f.scatta(
            "09-impostazioni-dialogo_menu-genitore", "Il menu «⋯» di un genitore (Rinomina, Nuovo codice, Togli)", CHIARO_360,
            preparazione = luca, pronto = panoramicaPronta,
            gesti = {
                apriImpostazioni()
                toccaDescrizione(s(R.string.azioni_per, "Papà"))
            },
            dopo = { ce(s(R.string.famiglia_togli)) },
        )
        due.forEach { v ->
            f.scatta(
                "09-impostazioni-dialogo_aggiungi-genitore", "Dialogo «Nuovo genitore» col nome scritto", v,
                preparazione = luca, pronto = panoramicaPronta,
                gesti = {
                    apriImpostazioni()
                    scorriFino(s(R.string.famiglia_aggiungi_genitore), esatto = true)
                    tocca(R.string.famiglia_aggiungi_genitore)
                    aspetta("dialogo") { ce(s(R.string.famiglia_nuovo_genitore_titolo)) }
                    val campi = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size
                    scrivi(campi - 1, "Nonno Piero")
                },
                dopo = { ce(s(R.string.famiglia_crea_codice)) },
            )
        }
        due.forEach { v ->
            f.scatta(
                "09-impostazioni-dialogo_codice-genitore", "Dialogo del codice di 6 cifre per il telefono di un genitore", v,
                pagineDialogo = true, preparazione = luca, pronto = panoramicaPronta,
                gesti = {
                    apriImpostazioni()
                    scorriFino(s(R.string.famiglia_aggiungi_genitore), esatto = true)
                    tocca(R.string.famiglia_aggiungi_genitore)
                    aspetta("dialogo") { ce(s(R.string.famiglia_nuovo_genitore_titolo)) }
                    val campi = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size
                    scrivi(campi - 1, "Nonno Piero")
                    tocca(R.string.famiglia_crea_codice)
                },
                dopo = { ce("482") },
            )
        }
        f.scatta(
            "09-impostazioni-dialogo_nuovo-dispositivo", "Dialogo «Nuovo dispositivo di Luca»", CHIARO_360,
            pagineDialogo = true, preparazione = luca, pronto = panoramicaPronta,
            gesti = {
                apriImpostazioni()
                scorriFino(s(R.string.famiglia_aggiungi_dispositivo), esatto = true)
                tocca(R.string.famiglia_aggiungi_dispositivo)
                aspetta("dialogo") { ce(s(R.string.famiglia_tipo_scegli)) }
                val campi = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size
                scrivi(campi - 1, "Tablet")
            },
            dopo = { ce(s(R.string.famiglia_crea_codice)) },
        )
        due.forEach { v ->
            f.scatta(
                "09-impostazioni-dialogo_codice-dispositivo", "Dialogo del codice di 6 cifre per un dispositivo di Luca", v,
                pagineDialogo = true, preparazione = luca, pronto = panoramicaPronta,
                gesti = {
                    apriImpostazioni()
                    scorriFino(s(R.string.famiglia_aggiungi_dispositivo), esatto = true)
                    tocca(R.string.famiglia_aggiungi_dispositivo)
                    aspetta("dialogo") { ce(s(R.string.famiglia_tipo_scegli)) }
                    val campi = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size
                    scrivi(campi - 1, "Tablet")
                    tocca(R.string.famiglia_crea_codice)
                },
                dopo = { ce("730") },
            )
        }
        f.scatta(
            "09-impostazioni-dialogo_togli-genitore", "Conferma «Togliere…?» su un genitore (dal ⋯)", CHIARO_360,
            preparazione = luca, pronto = panoramicaPronta,
            gesti = {
                apriImpostazioni()
                toccaDescrizione(s(R.string.azioni_per, "Papà"))
                tocca(R.string.famiglia_togli)
            },
            dopo = { ce(s(R.string.azione_annulla)) },
        )
        f.scatta(
            "09-impostazioni-dialogo_scollega-dispositivo", "Conferma «Scollegare…?» su un dispositivo (dal ⋯)", CHIARO_360,
            preparazione = luca, pronto = panoramicaPronta,
            gesti = {
                apriImpostazioni()
                scorriFino("Computer di camera")
                toccaDescrizione(s(R.string.azioni_per, "Computer di camera"))
                tocca(R.string.famiglia_scollega)
            },
            dopo = { ce(s(R.string.azione_annulla)) },
        )
        f.scatta(
            "09-impostazioni-dialogo_rinomina-figlio", "Dialogo «Nuovo nome per Luca» (dal ⋯)", CHIARO_360,
            preparazione = luca, pronto = panoramicaPronta,
            gesti = {
                apriImpostazioni()
                scorriFino("Computer di camera")
                toccaDescrizione(s(R.string.azioni_per, "Luca"))
                tocca(R.string.famiglia_rinomina)
            },
            dopo = { ce(s(R.string.azione_salva)) && ce(s(R.string.azione_annulla)) },
        )
        f.scatta(
            "09-impostazioni-dialogo_nuovo-figlio", "Dialogo «Nuovo figlio»", CHIARO_360,
            preparazione = luca, pronto = panoramicaPronta,
            gesti = {
                apriImpostazioni()
                scorriFino(s(R.string.famiglia_aggiungi_figlio), esatto = true)
                tocca(R.string.famiglia_aggiungi_figlio)
            },
            dopo = { ce(s(R.string.famiglia_nuovo_figlio_titolo)) },
        )
        f.scatta(
            "09-impostazioni-dialogo_collega-come-altro", "Domanda «Collegare come un altro genitore?» (dopo «Cambia», codice scritto)", CHIARO_360,
            preparazione = luca, pronto = panoramicaPronta,
            gesti = {
                apriImpostazioni()
                scorriFino(s(R.string.connessione_cambia), esatto = true)
                tocca(R.string.connessione_cambia)
                aspetta("modulo") { ce(s(R.string.connessione_codice)) }
                val campi = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size
                // I campi del collegamento: indirizzo, codice di 6 cifre (i primi due dopo la famiglia).
                scrivi(campi - 1, "123456")
                tocca(R.string.connessione_collega)
            },
            dopo = { ce(s(R.string.connessione_conferma_titolo)) },
        )
    }

    // --- 10 Prima apertura: le due domande ------------------------------------------------------------

    @Test
    fun primaApertura() {
        f.scatta(
            "10-prima-apertura_permesso-notifiche", "Domanda del permesso per le notifiche (prima apertura)", CHIARO_360,
            preparazione = Preparazione(notifiche = false, domandeFatte = false, figlioScelto = LUCA),
            pronto = { ce(s(R.string.permesso_notifiche_titolo)) },
        )
        f.scatta(
            "10-prima-apertura_batteria", "Domanda dell'esenzione dalla batteria", CHIARO_360,
            preparazione = Preparazione(domandeFatte = false, esenteBatteria = false, figlioScelto = LUCA),
            pronto = { ce(s(R.string.batteria_titolo)) },
        )
    }
}
