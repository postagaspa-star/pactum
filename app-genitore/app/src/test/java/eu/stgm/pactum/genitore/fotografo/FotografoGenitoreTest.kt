package eu.stgm.pactum.genitore.fotografo

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import eu.stgm.pactum.genitore.fotografo.Variante.Companion.RIDOTTE
import eu.stgm.pactum.genitore.fotografo.Variante.Companion.SCURO_411
import eu.stgm.pactum.genitore.fotografo.Variante.Companion.TUTTE
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
 * Il FOTOGRAFO dell'app del genitore: disegna sul PC, senza telefono né
 * emulatore, ogni schermata e ogni dialogo importante, coi dati finti di
 * [DatiFinti] serviti da un server finto. L'app è quella vera (MainActivity, i
 * ViewModel veri, la rete vera verso il server finto): niente è rifatto apposta.
 *
 * Non fa parte della suite normale: parte solo con
 *   gradlew :app:testDebugUnitTest -Pfotografo -Pfotografo.cartella=…
 * Ogni schermata in tema chiaro e scuro, a 360 e 411 dp, testo normale e grande
 * (1,3); gli stati principali (normale, vuoto, senza rete, collegamento non
 * valido, server vecchio, più figli). Con le pagine (-p1, -p2…) quando la
 * schermata scorre.
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

    /** Le varianti in cui si scattano tutte le pagine dello stato principale. */
    private val conPagine = setOf(CHIARO_360, CHIARO_411, CHIARO_360_GRANDE)

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

    // --- 01 Panoramica ----------------------------------------------------------------------

    private val panoramicaPronta: Fotografo.() -> Boolean = {
        nonCe(s(R.string.finestra_caricamento)) && ce("Luca ti propone")
    }

    @Test
    fun panoramica() {
        TUTTE.forEach { v ->
            f.scatta(
                "01-panoramica_normale", "Panoramica di Luca (2 figli; proposte, sessioni e lavori in cima)", v,
                pagine = v in conPagine, pronto = panoramicaPronta,
            )
        }
        RIDOTTE.forEach { v ->
            f.scatta(
                "01-panoramica_sara-telefono-silenzioso", "Panoramica di Sara: telefono silenzioso da 2 ore", v,
                preparazione = Preparazione(figlioScelto = SARA), pagine = v == CHIARO_360,
                pronto = { nonCe(s(R.string.finestra_caricamento)) && ce("ULTIMI 8 GIORNI") || ce("Nessun aggiornamento") },
            )
        }
        f.scatta(
            "01-panoramica_un-figlio", "Panoramica con un figlio solo (Luca)", CHIARO_360,
            scenario = DatiFinti.scenarioNormale(soloLuca = true), pronto = panoramicaPronta,
        )
        f.scatta(
            "01-panoramica_prima-volta-intro", "Panoramica con la scheda «Come funziona Pactum» ancora aperta", CHIARO_360,
            preparazione = Preparazione(introChiusa = false), pagine = true, pronto = panoramicaPronta,
        )
        RIDOTTE.forEach { v ->
            f.scatta(
                "01-panoramica_vuoto-figlio-senza-dispositivi", "Panoramica di Sara appena creata: nessun dispositivo, nessuna regola", v,
                scenario = DatiFinti.scenarioVuoto(), preparazione = Preparazione(figlioScelto = SARA),
                pronto = { ce(s(R.string.nessun_dispositivo_titolo)) },
            )
        }
        listOf(CHIARO_360, SCURO_411).forEach { v ->
            f.scatta(
                "01-panoramica_vuoto-senza-regole", "Panoramica di Sara: telefono collegato, ancora nessuna regola", v,
                scenario = scenarioSaraSenzaRegole(), preparazione = Preparazione(figlioScelto = SARA),
                pronto = { ce(s(R.string.regole_vuoto_titolo)) },
            )
        }
        RIDOTTE.forEach { v ->
            f.scatta(
                "01-panoramica_dati-vecchi", "Panoramica coi dati di prima: la rete è caduta dopo la prima lettura", v,
                pronto = panoramicaPronta,
                gesti = {
                    server.scenario = DatiFinti.scenarioSenzaRete()
                    toccaDescrizione(s(R.string.azione_aggiorna))
                },
                dopo = { ce("Dati non aggiornati") },
            )
        }
        RIDOTTE.forEach { v ->
            f.scatta(
                "01-panoramica_senza-rete", "Panoramica alla prima apertura senza rete (niente dati in mano)", v,
                scenario = DatiFinti.scenarioSenzaRete(),
                pronto = { ce(s(R.string.finestra_errore_nessun_dato)) },
            )
        }
        RIDOTTE.forEach { v ->
            f.scatta(
                "01-panoramica_collegamento-non-valido", "Panoramica: il collegamento di questo telefono non vale più (401)", v,
                scenario = DatiFinti.scenarioNonValido(),
                pronto = { ce(s(R.string.collegamento_non_valido_titolo)) },
            )
        }
        RIDOTTE.forEach { v ->
            f.scatta(
                "01-panoramica_server-vecchio", "Panoramica su un server 0.7 (niente famiglia, un telefono)", v,
                scenario = DatiFinti.scenarioServerVecchio(), pagine = v == CHIARO_360,
                pronto = { nonCe(s(R.string.finestra_caricamento)) && ce("ULTIMI 8 GIORNI") },
            )
        }
        RIDOTTE.forEach { v ->
            f.scatta(
                "01-panoramica_prima-apertura", "Panoramica alla prima apertura: manca il collegamento", v,
                scenario = DatiFinti.scenarioSenzaRete(), preparazione = Preparazione(configurato = false, ultimoControlloMinutiFa = null),
                pronto = { ce(s(R.string.config_mancante_titolo)) },
            )
        }
        f.scatta(
            "01-panoramica_avvisi-spenti", "Panoramica con gli avvisi di Pactum spenti su questo telefono", CHIARO_360,
            preparazione = Preparazione(notifiche = false), pronto = panoramicaPronta,
        )
        f.scatta(
            "01-panoramica_avvisi-in-ritardo", "Panoramica: la vedetta non controlla da 3 ore", CHIARO_360,
            preparazione = Preparazione(ultimoControlloMinutiFa = 180), pronto = panoramicaPronta,
        )
        listOf(CHIARO_411, CHIARO_360_GRANDE).forEach { v ->
            f.scatta(
                "01-panoramica_storico-aperto", "Panoramica scorsa fino in fondo con lo «Storico del patto» aperto", v,
                pronto = panoramicaPronta,
                gesti = {
                    inFondo()
                    tocca(s(R.string.sezione_storico))
                },
                pagine = true,
            )
        }
    }

    // --- 02 Dialoghi della Panoramica -------------------------------------------------------------

    @Test
    fun panoramicaDialoghi() {
        RIDOTTE.forEach { v ->
            f.scatta(
                "02-panoramica-dialogo_accetta-proposta", "Dialogo «Accetta» sulla proposta di Luca", v,
                pronto = panoramicaPronta,
                gesti = { tocca(R.string.proposta_accetta) },
                dopo = { ce(s(R.string.azione_annulla)) },
            )
        }
        f.scatta(
            "02-panoramica-dialogo_rifiuta-proposta", "Dialogo «Rifiuta» sulla proposta di Luca", CHIARO_360,
            pronto = panoramicaPronta,
            gesti = { tocca(R.string.proposta_rifiuta) },
            dopo = { ce(s(R.string.azione_annulla)) },
        )
        RIDOTTE.forEach { v ->
            f.scatta(
                "02-panoramica-dialogo_approva-sessione", "Dialogo «Approva» sul cambio della sessione Studio", v,
                pagineDialogo = true,
                pronto = panoramicaPronta,
                gesti = {
                    scorriFino(s(R.string.sessione_approva), esatto = true)
                    tocca(R.string.sessione_approva)
                },
                dopo = { ce(s(R.string.azione_annulla)) },
            )
        }
        f.scatta(
            "02-panoramica-dialogo_non-approvare-sessione", "Dialogo «Non approvare» sulla sessione nuova «Allenamento»", CHIARO_360,
            pagineDialogo = true,
            pronto = panoramicaPronta,
            gesti = {
                scorriFino("Allenamento")
                // Il secondo "Non approvare": quello della sessione nuova (il primo è del cambio a «Studio»).
                tocca(s(R.string.sessione_non_approvare), indice = 1, esatto = true)
            },
            dopo = { ce(s(R.string.azione_annulla)) },
        )
        f.scatta(
            "02-panoramica_segno-mandato", "Panoramica dopo «Manda un segno» (la frase in basso)", CHIARO_360,
            pronto = panoramicaPronta,
            gesti = {
                scorriFino(s(R.string.segno_manda))
                tocca(R.string.segno_manda)
            },
            dopo = { ce(s(R.string.segno_mandato)) },
        )
    }

    // --- 03 Notifiche ----------------------------------------------------------------------

    private val notifichePronte: Fotografo.() -> Boolean = {
        nonCe(s(R.string.notifiche_caricamento)) && ce(s(R.string.notifiche_segna_tutte))
    }

    @Test
    fun notifiche() {
        TUTTE.forEach { v ->
            f.scatta(
                "03-notifiche_normale", "Notifiche non lette (9, di tutti e due i figli)", v,
                destinazione = MainActivity.DEST_NOTIFICHE, pagine = v in conPagine, pronto = notifichePronte,
            )
        }
        RIDOTTE.forEach { v ->
            f.scatta(
                "03-notifiche_vuoto", "Notifiche: niente di nuovo", v,
                scenario = DatiFinti.scenarioVuoto(), destinazione = MainActivity.DEST_NOTIFICHE,
                pronto = { ce(s(R.string.notifiche_vuoto)) },
            )
        }
        RIDOTTE.forEach { v ->
            f.scatta(
                "03-notifiche_senza-rete", "Notifiche senza rete", v,
                scenario = DatiFinti.scenarioSenzaRete(), destinazione = MainActivity.DEST_NOTIFICHE,
                pronto = { ce(s(R.string.notifiche_errore)) },
            )
        }
        f.scatta(
            "03-notifiche_server-vecchio", "Notifiche su un server 0.7", CHIARO_360,
            scenario = DatiFinti.scenarioServerVecchio(), destinazione = MainActivity.DEST_NOTIFICHE, pronto = notifichePronte,
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
        TUTTE.forEach { v ->
            f.scatta(
                "04-lavori-di-casa_normale", "Lavori di casa di Luca: blocco attivo, da fare, fatti (con foto), annullati", v,
                destinazione = MainActivity.DEST_FACCENDE, preparazione = Preparazione(figlioScelto = LUCA),
                pagine = v in conPagine, pronto = faccendePronte,
            )
        }
        RIDOTTE.forEach { v ->
            f.scatta(
                "04-lavori-di-casa_vuoto", "Lavori di casa di Sara: nessuno negli ultimi 30 giorni", v,
                destinazione = MainActivity.DEST_FACCENDE, preparazione = Preparazione(figlioScelto = SARA),
                pronto = { ce(s(R.string.faccende_nessuna)) },
            )
        }
        RIDOTTE.forEach { v ->
            f.scatta(
                "04-lavori-di-casa_server-vecchio", "Lavori di casa su un server più vecchio della v3.6", v,
                scenario = scenarioFaccendeServerVecchio(), destinazione = MainActivity.DEST_FACCENDE,
                preparazione = Preparazione(figlioScelto = LUCA),
                pronto = { ce(s(R.string.faccende_server_vecchio_titolo)) },
            )
        }
        RIDOTTE.forEach { v ->
            f.scatta(
                "04-lavori-di-casa_senza-rete", "Lavori di casa senza rete", v,
                scenario = DatiFinti.scenarioSenzaRete(), destinazione = MainActivity.DEST_FACCENDE,
                pronto = { ce(s(R.string.faccende_errore)) },
            )
        }
        f.scatta(
            "04-lavori-di-casa_collegamento-non-valido", "Lavori di casa: collegamento non più valido (401)", CHIARO_360,
            scenario = DatiFinti.scenarioNonValido(), destinazione = MainActivity.DEST_FACCENDE,
            pronto = { ce(s(R.string.collegamento_non_valido_titolo)) },
        )
        f.scatta(
            "04-lavori-di-casa_dati-vecchi", "Lavori di casa coi dati di prima (rete caduta)", CHIARO_360,
            destinazione = MainActivity.DEST_FACCENDE, preparazione = Preparazione(figlioScelto = LUCA),
            pronto = faccendePronte,
            gesti = {
                server.scenario = DatiFinti.scenarioSenzaRete()
                toccaDescrizione(s(R.string.azione_aggiorna))
            },
            dopo = { ce(s(R.string.faccende_dati_vecchi)) },
        )
    }

    // --- 05 Dialoghi dei lavori di casa e foto ------------------------------------------------------

    @Test
    fun lavoriDiCasaDialoghi() {
        val luca = Preparazione(figlioScelto = LUCA)
        RIDOTTE.forEach { v ->
            f.scatta(
                "05-lavori-dialogo_dai-vuoto", "Dialogo «Dai lavori di casa» appena aperto", v,
                pagineDialogo = true,
                destinazione = MainActivity.DEST_FACCENDE, preparazione = luca, pronto = faccendePronte,
                gesti = { tocca(R.string.faccende_dai) },
                dopo = { ce(s(R.string.dai_manda)) },
            )
        }
        listOf(CHIARO_360, CHIARO_360_GRANDE, SCURO_411).forEach { v ->
            f.scatta(
                "05-lavori-dialogo_dai-compilato", "Dialogo «Dai lavori di casa» compilato: due lavori, una nota, blocco dalle…", v,
                pagineDialogo = true,
                destinazione = MainActivity.DEST_FACCENDE, preparazione = luca, pronto = faccendePronte,
                gesti = {
                    tocca(R.string.faccende_dai)
                    scrivi(0, "Apparecchia la tavola per sei")
                    tocca(R.string.dai_aggiungi)
                    tocca("Porta fuori la spazzatura", indice = 0, esatto = true)
                    // La nota è l'ultimo campo di testo.
                    val campi = compose.onAllNodes(androidx.compose.ui.test.hasSetTextAction()).fetchSemanticsNodes().size
                    scrivi(campi - 1, "Prima che arrivino i nonni, alle 19:30")
                    // "Dalle…": il secondo pallino, in fondo al dialogo (si scorre fin lì).
                    val pallini = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                    misuraTutto()
                    pallini[1].performScrollTo()
                    toccaNodo(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton), 1)
                    misuraTutto()
                    colonnaDialogo()?.let { inCimaA(it) }
                },
                dopo = { ce(s(R.string.dai_manda)) },
            )
        }
        f.scatta(
            "05-lavori-dialogo_dai-scegli-ora", "Dialogo «Da che ora» (orologio) sopra «Dai lavori di casa»", CHIARO_360,
            destinazione = MainActivity.DEST_FACCENDE, preparazione = luca, pronto = faccendePronte,
            gesti = {
                tocca(R.string.faccende_dai)
                tocca("Dalle ")
            },
            dopo = { ce(s(R.string.dai_scegli_ora)) },
        )
        RIDOTTE.forEach { v ->
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
            "05-lavori-dialogo_annulla", "Dialogo «Annullare…?» su un lavoro da fare", CHIARO_360,
            destinazione = MainActivity.DEST_FACCENDE, preparazione = luca, pronto = faccendePronte,
            gesti = {
                scorriFino(s(R.string.faccenda_annulla), esatto = true)
                tocca(R.string.faccenda_annulla)
            },
            dopo = { ce(s(R.string.annulla_faccenda_lascia)) },
        )
        val fotoAperta: Fotografo.() -> Boolean = {
            ce(s(R.string.foto_chiudi)) || compose.onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription),
            ).fetchSemanticsNodes().any { n ->
                n.config[SemanticsProperties.ContentDescription].any { it.startsWith("Foto del lavoro") }
            }
        }
        (RIDOTTE + Variante.SCURO_360 + CHIARO_411).forEach { v ->
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
        TUTTE.forEach { v ->
            f.scatta(
                "06-tempo_normale", "Tempo di Luca, telefono, oggi", v,
                destinazione = MainActivity.DEST_TEMPO, preparazione = Preparazione(figlioScelto = LUCA),
                pagine = v in conPagine, pronto = tempoPronto,
            )
        }
        RIDOTTE.forEach { v ->
            f.scatta(
                "06-tempo_computer", "Tempo di Luca, computer (spento), oggi", v,
                destinazione = MainActivity.DEST_TEMPO, preparazione = Preparazione(figlioScelto = LUCA),
                pagine = v == CHIARO_360, pronto = tempoPronto,
                gesti = { tocca("Computer di camera") },
                dopo = {
                    compose.onAllNodes(hasText("Computer di camera", substring = true) and isSelected())
                        .fetchSemanticsNodes().isNotEmpty()
                },
            )
        }
        val quattroGiorniFa = giornoBreve(DatiFinti.oggi().minusDays(3).toString())
        listOf(CHIARO_360, CHIARO_360_GRANDE).forEach { v ->
            f.scatta(
                "06-tempo_giorno-senza-dati", "Tempo di Luca, telefono, un giorno senza dati ($quattroGiorniFa)", v,
                destinazione = MainActivity.DEST_TEMPO, preparazione = Preparazione(figlioScelto = LUCA),
                pronto = tempoPronto,
                gesti = { tocca(quattroGiorniFa, esatto = true) },
                dopo = { ce(s(R.string.tempo_nessun_dato)) },
            )
        }
        f.scatta(
            "06-tempo_sara", "Tempo di Sara (un telefono, senza siti)", CHIARO_360,
            destinazione = MainActivity.DEST_TEMPO, preparazione = Preparazione(figlioScelto = SARA),
            pagine = true,
            pronto = { nonCe(s(R.string.tempo_caricamento)) && ce(s(R.string.tempo_etichetta_oggi)) },
        )
        RIDOTTE.forEach { v ->
            f.scatta(
                "06-tempo_vuoto-figlio-senza-dispositivi", "Tempo di Sara appena creata (nessun dispositivo)", v,
                scenario = DatiFinti.scenarioVuoto(), destinazione = MainActivity.DEST_TEMPO,
                preparazione = Preparazione(figlioScelto = SARA),
                pronto = { nonCe(s(R.string.tempo_caricamento)) && ce("Sara") },
            )
        }
        f.scatta(
            "06-tempo_vuoto-nessun-dato", "Tempo di Sara: telefono collegato, nessun dato ancora", CHIARO_360,
            scenario = scenarioSaraSenzaRegole(), destinazione = MainActivity.DEST_TEMPO,
            preparazione = Preparazione(figlioScelto = SARA),
            pronto = { ce(s(R.string.tempo_nessuna_fotografia_titolo)) || ce(s(R.string.tempo_nessun_dato)) },
        )
        RIDOTTE.forEach { v ->
            f.scatta(
                "06-tempo_senza-rete", "Tempo senza rete", v,
                scenario = DatiFinti.scenarioSenzaRete(), destinazione = MainActivity.DEST_TEMPO,
                pronto = { ce(s(R.string.tempo_errore)) },
            )
        }
        f.scatta(
            "06-tempo_dati-vecchi", "Tempo coi dati di prima (rete caduta)", CHIARO_360,
            destinazione = MainActivity.DEST_TEMPO, preparazione = Preparazione(figlioScelto = LUCA),
            pronto = tempoPronto,
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

    // --- 07 Proposte e conferme -----------------------------------------------------------------------

    private val turnoPronto: Fotografo.() -> Boolean = {
        nonCe(s(R.string.turno_caricamento)) && ce(s(R.string.proposte_da_decidere))
    }

    @Test
    fun proposteEConferme() {
        TUTTE.forEach { v ->
            f.scatta(
                "07-proposte-e-conferme_normale", "Proposte e conferme di Luca", v,
                destinazione = MainActivity.DEST_TURNO, preparazione = Preparazione(figlioScelto = LUCA),
                pagine = v in conPagine, pronto = turnoPronto,
            )
        }
        RIDOTTE.forEach { v ->
            f.scatta(
                "07-proposte-e-conferme_vuoto", "Proposte e conferme di Sara appena creata (niente regole)", v,
                scenario = DatiFinti.scenarioVuoto(), destinazione = MainActivity.DEST_TURNO,
                preparazione = Preparazione(figlioScelto = SARA),
                pronto = { nonCe(s(R.string.turno_caricamento)) && ce("Sara") && (ce(s(R.string.proposte_nessuna_regola_attiva)) || ce(s(R.string.proposte_elenco_vuoto))) },
            )
        }
        f.scatta(
            "07-proposte-e-conferme_sara", "Proposte e conferme di Sara (regole, nessuna proposta)", CHIARO_360,
            destinazione = MainActivity.DEST_TURNO, preparazione = Preparazione(figlioScelto = SARA), pagine = true,
            pronto = { nonCe(s(R.string.turno_caricamento)) && ce(s(R.string.proposte_bottone_proponi)) },
        )
        RIDOTTE.forEach { v ->
            f.scatta(
                "07-proposte-e-conferme_senza-rete", "Proposte e conferme senza rete", v,
                scenario = DatiFinti.scenarioSenzaRete(), destinazione = MainActivity.DEST_TURNO,
                pronto = { ce(s(R.string.turno_errore)) },
            )
        }
        f.scatta(
            "07-proposte-e-conferme_server-vecchio", "Proposte e conferme su un server 0.7", CHIARO_360,
            scenario = DatiFinti.scenarioServerVecchio(), destinazione = MainActivity.DEST_TURNO, pagine = true,
            pronto = { nonCe(s(R.string.turno_caricamento)) && ce(s(R.string.proposte_bottone_proponi)) },
        )
        RIDOTTE.forEach { v ->
            f.scatta(
                "07-proposte-dialogo_nuova-proposta", "Dialogo «Proponi una modifica» su una regola di tempo", v,
                pagineDialogo = true,
                destinazione = MainActivity.DEST_TURNO, preparazione = Preparazione(figlioScelto = LUCA), pronto = turnoPronto,
                gesti = {
                    scorriFino(s(R.string.proposte_bottone_proponi), esatto = true)
                    tocca(R.string.proposte_bottone_proponi)
                },
                dopo = { ce(s(R.string.azione_annulla)) },
            )
        }
        f.scatta(
            "07-proposte-dialogo_nuova-proposta-fascia", "Dialogo «Proponi una modifica» su una fascia oraria", CHIARO_360,
            destinazione = MainActivity.DEST_TURNO, preparazione = Preparazione(figlioScelto = SARA),
            pagineDialogo = true,
            pronto = { nonCe(s(R.string.turno_caricamento)) && ce(s(R.string.proposte_bottone_proponi)) },
            gesti = {
                scorriFino("21:30")
                tocca(s(R.string.proposte_bottone_proponi), indice = 1, esatto = true)
            },
            dopo = { ce(s(R.string.azione_annulla)) },
        )
    }

    // --- 08 Impostazioni -------------------------------------------------------------------------------

    private val impostazioniAperte: Fotografo.() -> Unit = { tocca(R.string.scheda_impostazioni) }
    private val impostazioniPronte: Fotografo.() -> Boolean = { ce("Computer di camera") && ce("Nonna") }

    @Test
    fun impostazioni() {
        TUTTE.forEach { v ->
            f.scatta(
                "08-impostazioni_normale", "Impostazioni: collegamento, famiglia (genitori, figli, dispositivi), avvisi, riassunto, aggiornamenti", v,
                pagine = v in conPagine, pronto = panoramicaPronta,
                gesti = impostazioniAperte, dopo = impostazioniPronte,
            )
        }
        RIDOTTE.forEach { v ->
            f.scatta(
                "08-impostazioni_prima-apertura", "Impostazioni alla prima apertura (niente collegamento)", v,
                scenario = DatiFinti.scenarioSenzaRete(), preparazione = Preparazione(configurato = false, ultimoControlloMinutiFa = null),
                pagine = v == CHIARO_360,
                pronto = { ce(s(R.string.config_mancante_titolo)) },
                gesti = impostazioniAperte, dopo = { ce(s(R.string.famiglia_config_mancante)) },
            )
        }
        RIDOTTE.forEach { v ->
            f.scatta(
                "08-impostazioni_collegamento-non-valido", "Impostazioni: collegamento non più valido (401)", v,
                scenario = DatiFinti.scenarioNonValido(),
                pronto = { ce(s(R.string.collegamento_non_valido_titolo)) },
                gesti = impostazioniAperte, dopo = { ce(s(R.string.impostazioni_titolo)) && ce("Il collegamento di questo telefono") },
            )
        }
        f.scatta(
            "08-impostazioni_server-vecchio", "Impostazioni su un server 0.7", CHIARO_360,
            scenario = DatiFinti.scenarioServerVecchio(), pagine = true,
            pronto = { nonCe(s(R.string.finestra_caricamento)) && ce("ULTIMI 8 GIORNI") },
            gesti = impostazioniAperte, dopo = { ce(s(R.string.famiglia_server_vecchio)) },
        )
        listOf(CHIARO_360, SCURO_411).forEach { v ->
            f.scatta(
                "08-impostazioni_senza-rete", "Impostazioni senza rete (famiglia e genitori non letti)", v,
                scenario = DatiFinti.scenarioSenzaRete(), pagine = v == CHIARO_360,
                pronto = { ce(s(R.string.finestra_errore_nessun_dato)) },
                gesti = impostazioniAperte, dopo = { ce(s(R.string.famiglia_non_letta)) },
            )
        }
        listOf(CHIARO_360, CHIARO_360_GRANDE).forEach { v ->
            f.scatta(
                "08-impostazioni_avvisi-da-sistemare", "Impostazioni aperte sugli «Avvisi del patto» (notifiche spente, batteria non esente)", v,
                destinazione = MainActivity.DEST_AVVISI,
                preparazione = Preparazione(notifiche = false, esenteBatteria = false),
                pronto = { ce(s(R.string.impostazioni_titolo)) && ce("Nonna") },
            )
        }
        f.scatta(
            "08-impostazioni_codice-lungo", "Impostazioni col codice d'accesso lungo aperto", CHIARO_360,
            pronto = panoramicaPronta,
            gesti = {
                impostazioniAperte()
                aspetta("impostazioni") { impostazioniPronte() }
                tocca(R.string.connessione_codice_lungo_apri)
            },
            dopo = { ce(s(R.string.connessione_codice_lungo_chiudi)) },
        )
    }

    // --- 09 Dialoghi delle Impostazioni -----------------------------------------------------------------

    private fun Fotografo.apriImpostazioni() {
        tocca(R.string.scheda_impostazioni)
        aspetta("impostazioni") { ce("Computer di camera") && ce("Nonna") }
    }

    @Test
    fun impostazioniDialoghi() {
        RIDOTTE.forEach { v ->
            f.scatta(
                "09-impostazioni-dialogo_aggiungi-genitore", "Dialogo «Nuovo genitore» col nome scritto", v,
                pronto = panoramicaPronta,
                gesti = {
                    apriImpostazioni()
                    scorriFino(s(R.string.famiglia_aggiungi_genitore), esatto = true)
                    tocca(R.string.famiglia_aggiungi_genitore)
                    aspetta("dialogo") { ce(s(R.string.famiglia_nuovo_genitore_titolo)) }
                    val campi = compose.onAllNodes(androidx.compose.ui.test.hasSetTextAction()).fetchSemanticsNodes().size
                    scrivi(campi - 1, "Nonno Piero")
                },
                dopo = { ce(s(R.string.famiglia_crea_codice)) },
            )
        }
        RIDOTTE.forEach { v ->
            f.scatta(
                "09-impostazioni-dialogo_codice-genitore", "Dialogo del codice di 6 cifre per collegare il telefono di un genitore", v,
                pagineDialogo = true,
                pronto = panoramicaPronta,
                gesti = {
                    apriImpostazioni()
                    scorriFino(s(R.string.famiglia_aggiungi_genitore), esatto = true)
                    tocca(R.string.famiglia_aggiungi_genitore)
                    aspetta("dialogo") { ce(s(R.string.famiglia_nuovo_genitore_titolo)) }
                    val campi = compose.onAllNodes(androidx.compose.ui.test.hasSetTextAction()).fetchSemanticsNodes().size
                    scrivi(campi - 1, "Nonno Piero")
                    tocca(R.string.famiglia_crea_codice)
                },
                dopo = { ce("482") },
            )
        }
        RIDOTTE.forEach { v ->
            f.scatta(
                "09-impostazioni-dialogo_nuovo-dispositivo", "Dialogo «Nuovo dispositivo di Luca»", v,
                pagineDialogo = true,
                pronto = panoramicaPronta,
                gesti = {
                    apriImpostazioni()
                    scorriFino(s(R.string.famiglia_aggiungi_dispositivo), esatto = true)
                    tocca(R.string.famiglia_aggiungi_dispositivo)
                    aspetta("dialogo") { ce(s(R.string.famiglia_tipo_scegli)) }
                    val campi = compose.onAllNodes(androidx.compose.ui.test.hasSetTextAction()).fetchSemanticsNodes().size
                    scrivi(campi - 1, "Tablet")
                },
                dopo = { ce(s(R.string.famiglia_crea_codice)) },
            )
        }
        listOf(CHIARO_360, CHIARO_360_GRANDE).forEach { v ->
            f.scatta(
                "09-impostazioni-dialogo_codice-dispositivo", "Dialogo del codice di 6 cifre per collegare un dispositivo di Luca", v,
                pagineDialogo = true,
                pronto = panoramicaPronta,
                gesti = {
                    apriImpostazioni()
                    scorriFino(s(R.string.famiglia_aggiungi_dispositivo), esatto = true)
                    tocca(R.string.famiglia_aggiungi_dispositivo)
                    aspetta("dialogo") { ce(s(R.string.famiglia_tipo_scegli)) }
                    val campi = compose.onAllNodes(androidx.compose.ui.test.hasSetTextAction()).fetchSemanticsNodes().size
                    scrivi(campi - 1, "Tablet")
                    tocca(R.string.famiglia_crea_codice)
                },
                dopo = { ce("730") },
            )
        }
        f.scatta(
            "09-impostazioni-dialogo_togli-genitore", "Conferma «Togliere…?» su un genitore", CHIARO_360,
            pronto = panoramicaPronta,
            gesti = {
                apriImpostazioni()
                scorriFino(s(R.string.famiglia_togli), esatto = true)
                tocca(R.string.famiglia_togli)
            },
            dopo = { ce(s(R.string.azione_annulla)) },
        )
        f.scatta(
            "09-impostazioni-dialogo_scollega-dispositivo", "Conferma «Scollegare…?» su un dispositivo", CHIARO_360,
            pronto = panoramicaPronta,
            gesti = {
                apriImpostazioni()
                scorriFino(s(R.string.famiglia_scollega), esatto = true)
                tocca(R.string.famiglia_scollega)
            },
            dopo = { ce(s(R.string.azione_annulla)) },
        )
        f.scatta(
            "09-impostazioni-dialogo_rinomina-figlio", "Dialogo «Nuovo nome per Luca»", CHIARO_360,
            pronto = panoramicaPronta,
            gesti = {
                apriImpostazioni()
                scorriFino("Luca", esatto = true)
                tocca(s(R.string.famiglia_rinomina), indice = 0, esatto = true)
            },
            dopo = { ce(s(R.string.azione_salva)) && ce(s(R.string.azione_annulla)) },
        )
        f.scatta(
            "09-impostazioni-dialogo_nuovo-figlio", "Dialogo «Nuovo figlio»", CHIARO_360,
            pronto = panoramicaPronta,
            gesti = {
                apriImpostazioni()
                scorriFino(s(R.string.famiglia_aggiungi_figlio), esatto = true)
                tocca(R.string.famiglia_aggiungi_figlio)
            },
            dopo = { ce(s(R.string.famiglia_nuovo_figlio_titolo)) },
        )
        f.scatta(
            "09-impostazioni-dialogo_collega-come-altro", "Domanda «Collegare come un altro genitore?» (codice di 6 cifre scritto)", CHIARO_360,
            pronto = panoramicaPronta,
            gesti = {
                apriImpostazioni()
                inCima()
                scrivi(1, "123456")
                tocca(R.string.connessione_collega)
            },
            dopo = { ce(s(R.string.connessione_conferma_titolo)) },
        )
    }

    // --- 10 Prima apertura: le due domande ------------------------------------------------------------

    @Test
    fun primaApertura() {
        RIDOTTE.forEach { v ->
            f.scatta(
                "10-prima-apertura_permesso-notifiche", "Domanda del permesso per le notifiche (prima apertura)", v,
                preparazione = Preparazione(notifiche = false, domandeFatte = false),
                pronto = { ce(s(R.string.permesso_notifiche_titolo)) && ce("Luca ti propone") },
            )
        }
        RIDOTTE.forEach { v ->
            f.scatta(
                "10-prima-apertura_batteria", "Domanda dell'esenzione dalla batteria", v,
                preparazione = Preparazione(domandeFatte = false, esenteBatteria = false),
                pronto = { ce(s(R.string.batteria_titolo)) && ce("Luca ti propone") },
            )
        }
    }
}
