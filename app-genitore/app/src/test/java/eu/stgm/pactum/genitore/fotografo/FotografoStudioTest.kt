package eu.stgm.pactum.genitore.fotografo

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import eu.stgm.pactum.genitore.MainActivity
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.fotografo.DatiFinti.LUCA
import eu.stgm.pactum.genitore.fotografo.Variante.Companion.CHIARO_360
import eu.stgm.pactum.genitore.fotografo.Variante.Companion.CHIARO_360_GRANDE
import eu.stgm.pactum.genitore.fotografo.Variante.Companion.CHIARO_411
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
 * (0.18, contratto v4.0) Le foto delle schermate nuove dell'app del genitore, con
 * un server finto dalla v4.0 ([DatiFintiV40]): i lavori da approvare (gruppo in
 * cima, "Approva" dopo la foto, la domanda che dice lo sblocco, il blocco che
 * aspetta lo Studio), lo Studio nella Panoramica (in corso e chiuso, "Chiudi lo
 * Studio" col motivo), la configurazione da approvare in "Da decidere", la pagina
 * dello Studio e le notifiche nuove.
 *
 * Come il resto del fotografo, parte solo con -Pfotografo (e -Pfotografo.solo=18).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [35], application = AppFotografo::class)
class FotografoStudioTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val server = ServerFinto()
    private lateinit var f: Fotografo

    private val due = listOf(CHIARO_360, CHIARO_360_GRANDE)
    private val tre = due + CHIARO_411
    private val luca = Preparazione(figlioScelto = LUCA)

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

    private val lavoriPronti: Fotografo.() -> Boolean = {
        nonCe(s(R.string.faccende_caricamento)) && ce(s(R.string.faccende_da_approvare))
    }

    // --- Lavori: le foto da approvare --------------------------------------------------------

    @Test
    fun lavoriDaApprovare() {
        tre.forEach { v ->
            f.scatta(
                "18-lavori_da-approvare", "Lavori (v4.0): il blocco aspetta lo Studio, due foto da approvare in cima", v,
                scenario = DatiFintiV40.scenario(inCorso = true),
                destinazione = MainActivity.DEST_FACCENDE, preparazione = luca,
                pagine = v == CHIARO_360, pronto = lavoriPronti,
            )
        }
        f.scatta(
            "18-lavori_bloccato", "Lavori (v4.0) senza Studio: «Blocco attivo» con le foto da approvare", CHIARO_360,
            scenario = DatiFintiV40.scenario(inCorso = false),
            destinazione = MainActivity.DEST_FACCENDE, preparazione = luca, pronto = lavoriPronti,
        )
        val fotoAperta: Fotografo.() -> Boolean = { ce(s(R.string.foto_chiudi)) || ce(s(R.string.faccenda_approva)) }
        due.forEach { v ->
            f.scatta(
                "18-lavori_foto-con-approva", "La foto da approvare a tutto schermo: «Approva» e «Boccia», senza le 24 ore", v,
                scenario = DatiFintiV40.scenario(
                    inCorso = false,
                    faccende = DatiFintiV40.faccendeUltimaDaApprovare(),
                    blocco = DatiFintiV40.bloccoUltimaDaApprovare(),
                ),
                destinazione = MainActivity.DEST_FACCENDE, faccenda = 107L, preparazione = luca,
                pronto = { fotoAperta() && nonCe(s(R.string.foto_caricamento)) && ce(s(R.string.faccenda_approva)) },
            )
        }
        due.forEach { v ->
            f.scatta(
                "18-lavori_domanda-approva", "«Approvi «Rifai il letto»?»: è l'ultimo che blocca, telefono e computer si sbloccano", v,
                scenario = DatiFintiV40.scenario(
                    inCorso = false,
                    faccende = DatiFintiV40.faccendeUltimaDaApprovare(),
                    blocco = DatiFintiV40.bloccoUltimaDaApprovare(),
                ),
                destinazione = MainActivity.DEST_FACCENDE, faccenda = 107L, preparazione = luca,
                pronto = { nonCe(s(R.string.foto_caricamento)) && ce(s(R.string.faccenda_approva)) },
                gesti = { tocca(s(R.string.faccenda_approva), indice = 0, esatto = true) },
                dopo = { ce("si sbloccano") },
            )
        }
        f.scatta(
            "18-lavori_domanda-approva-rimandato", "«Approvi…?» durante lo Studio: il blocco non partirà a fine Studio", CHIARO_360,
            scenario = DatiFintiV40.scenario(
                inCorso = true,
                faccende = DatiFintiV40.faccendeUltimaDaApprovare(),
                blocco = DatiFintiV40.bloccoUltimaDaApprovare().copy(rimandato = true),
            ),
            destinazione = MainActivity.DEST_FACCENDE, faccenda = 107L, preparazione = luca,
            pronto = { nonCe(s(R.string.foto_caricamento)) && ce(s(R.string.faccenda_approva)) },
            gesti = { tocca(s(R.string.faccenda_approva), indice = 0, esatto = true) },
            dopo = { ce("non partirà a fine Studio") },
        )
    }

    // --- Panoramica: lo Studio di oggi --------------------------------------------------------

    private val panoramicaPronta: Fotografo.() -> Boolean = {
        nonCe(s(R.string.finestra_caricamento)) && ce(s(R.string.studio_di_oggi))
    }

    @Test
    fun panoramicaStudio() {
        tre.forEach { v ->
            f.scatta(
                "18-panoramica_studio-in-corso", "Panoramica (v4.0): Luca in Studio, il blocco aspetta, foto da approvare", v,
                scenario = DatiFintiV40.scenario(inCorso = true), preparazione = luca,
                pagine = v == CHIARO_360, pronto = panoramicaPronta,
            )
        }
        due.forEach { v ->
            f.scatta(
                "18-panoramica_studio-chiuso", "Panoramica (v4.0): lo Studio di oggi chiuso da Luca, con quello che ha fatto", v,
                scenario = DatiFintiV40.scenario(inCorso = false), preparazione = luca,
                pronto = panoramicaPronta,
            )
        }
        due.forEach { v ->
            f.scatta(
                "18-panoramica_chiudi-studio", "«Chiudere lo Studio di Luca?»: il motivo è obbligatorio (pulsante spento)", v,
                scenario = DatiFintiV40.scenario(inCorso = true), preparazione = luca,
                pronto = panoramicaPronta,
                gesti = { tocca(s(R.string.studio_chiudi), indice = 0, esatto = true) },
                dopo = { ce(s(R.string.studio_chiudi_campo)) },
            )
        }
        f.scatta(
            "18-panoramica_chiudi-studio-motivo", "«Chiudere lo Studio di Luca?» col motivo scritto", CHIARO_360,
            scenario = DatiFintiV40.scenario(inCorso = true), preparazione = luca,
            pronto = panoramicaPronta,
            gesti = {
                tocca(s(R.string.studio_chiudi), indice = 0, esatto = true)
                aspetta("dialogo") { ce(s(R.string.studio_chiudi_campo)) }
                scrivi(0, "Visita dal dentista alle 16:30")
            },
            dopo = { ce("Visita dal dentista") },
        )
    }

    // --- Da decidere: la configurazione e le foto ----------------------------------------------

    private val daDecidereProntoStudio: Fotografo.() -> Boolean = {
        nonCe(s(R.string.turno_caricamento)) && (ce("Luca chiede") || ce(s(R.string.verdetto_conferma)))
    }

    @Test
    fun daDecidereStudio() {
        tre.forEach { v ->
            f.scatta(
                "18-da-decidere_studio", "Da decidere (v4.0): le foto da approvare e il cambio dello Studio", v,
                scenario = DatiFintiV40.scenario(inCorso = true), destinazione = MainActivity.DEST_DECIDERE,
                preparazione = luca, pagine = v == CHIARO_360, pronto = daDecidereProntoStudio,
                gesti = { if (v != CHIARO_360) scorriFino("chiede di cambiare lo Studio") },
                dopo = { ce("chiede di cambiare lo Studio") || v == CHIARO_360 },
            )
        }
        due.forEach { v ->
            f.scatta(
                "18-da-decidere_studio-approva", "«Approvi lo Studio di Luca?»: com'è e quando vale", v,
                scenario = DatiFintiV40.scenario(inCorso = true, soloStudio = true), destinazione = MainActivity.DEST_DECIDERE,
                preparazione = luca, pronto = { nonCe(s(R.string.turno_caricamento)) && ce("Porta fuori la spazzatura") }, pagineDialogo = v == CHIARO_360,
                gesti = {
                    scorriFino(s(R.string.studio_approva), esatto = true)
                    tocca(s(R.string.studio_approva), indice = 0, esatto = true)
                },
                dopo = { ce("Approvi lo Studio") },
            )
        }
        f.scatta(
            "18-da-decidere_studio-rifiuta", "«Non approvi lo Studio di Luca?» col perché facoltativo", CHIARO_360,
            scenario = DatiFintiV40.scenario(inCorso = true, soloStudio = true), destinazione = MainActivity.DEST_DECIDERE,
            preparazione = luca, pronto = { nonCe(s(R.string.turno_caricamento)) && ce("Porta fuori la spazzatura") },
            gesti = {
                scorriFino(s(R.string.studio_rifiuta), esatto = true)
                tocca(s(R.string.studio_rifiuta), indice = 0, esatto = true)
            },
            dopo = { ce("Non approvi lo Studio") },
        )
    }

    // --- La pagina dello Studio e le notifiche --------------------------------------------------

    @Test
    fun paginaStudio() {
        tre.forEach { v ->
            f.scatta(
                "18-studio_pagina", "Pagina «Sessione Studio»: in corso, com'è approvato, gli Studi fatti", v,
                scenario = DatiFintiV40.scenario(inCorso = true), destinazione = MainActivity.DEST_STUDIO,
                preparazione = luca, pagine = v == CHIARO_360,
                pronto = { nonCe(s(R.string.studio_caricamento)) && ce(s(R.string.studio_in_corso_titolo)) },
            )
        }
        f.scatta(
            "18-studio_pagina-versioni", "Pagina «Sessione Studio» con le versioni approvate aperte", CHIARO_360,
            scenario = DatiFintiV40.scenario(inCorso = false), destinazione = MainActivity.DEST_STUDIO,
            preparazione = luca,
            pronto = { nonCe(s(R.string.studio_caricamento)) && ce(s(R.string.studio_config_titolo)) },
            gesti = {
                scorriFino(s(R.string.studio_versioni_titolo))
                tocca(s(R.string.studio_versioni_titolo), indice = 0)
                inFondo()
            },
            dopo = { ce("Versione 5") },
        )
        f.scatta(
            "18-studio_server-vecchio", "Pagina «Sessione Studio» su un server più vecchio della v4.0", CHIARO_360,
            scenario = DatiFinti.scenarioNormale(), destinazione = MainActivity.DEST_STUDIO, preparazione = luca,
            pronto = { ce(s(R.string.studio_server_vecchio)) },
        )
        due.forEach { v ->
            f.scatta(
                "18-notifiche", "Le notifiche nuove: foto da approvare, lavoro approvato, Studio, computer che non risponde", v,
                scenario = DatiFintiV40.scenario(inCorso = true), destinazione = MainActivity.DEST_NOTIFICHE,
                preparazione = luca, pagine = v == CHIARO_360,
                pronto = { nonCe(s(R.string.notifiche_caricamento)) && ce("Luca") },
            )
        }
    }
}
