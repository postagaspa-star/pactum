package eu.stgm.pactum.figlio.fotografo

import eu.stgm.pactum.figlio.faccende.ArchivioBlocco
import eu.stgm.pactum.figlio.sessione.ArchivioSessioni
import eu.stgm.pactum.figlio.sessione.MemoriaSessioni
import eu.stgm.pactum.figlio.ui.RegoleViewModel
import org.junit.Test

/**
 * 01-04 — Il primo avvio, nell'ordine nuovo (0.15): Collega → Permessi →
 * "Cosa vedono i tuoi genitori" → la prima regola; i lavori di casa che
 * passano davanti a tutto e la sessione in corso sopra i passi.
 */
class FotoInizioTest : Fotografo() {

    private fun regoleVuote(letto: Boolean = true) =
        RegoleViewModel.StatoRegole(caricamento = !letto, letto = letto)

    // --- 03 (ora il primo passo) Collega --------------------------------------------

    @Test
    fun collega() {
        scatta("03-prima-regola-da-collegare", "Primo avvio, passo 1 \"Collega\": la frase su Pactum, indirizzo del server e codice di 6 cifre") {
            apriPactum(StatiFinti()).also { aspetta("Indirizzo del server") }.comeAperta()
        }
    }

    @Test
    fun collegaCodiceLungo() {
        scatta("03-prima-regola-codice-lungo", "Primo avvio, \"Collega\" con \"Hai un codice lungo?\" aperto", pagine = false) {
            apriPactum(StatiFinti()).also { aspetta("Hai un codice lungo?"); tocca("Hai un codice lungo?") }.comeAperta()
        }
    }

    // --- 01 Permessi -------------------------------------------------------------

    @Test
    fun permessiTuttiMancanti() {
        Mondo.collegato(app)
        Mondo.permessi(app, uso = false, batteria = false, notifiche = false, sopra = false)
        scatta("01-permessi-nessuno", "Passo 2 \"Permessi\": i quattro, nessuno dato, ognuno con \"Apri\"") {
            apriPactum(StatiFinti()).comeAperta()
        }
    }

    @Test
    fun permessiAiutoAperto() {
        Mondo.collegato(app)
        Mondo.permessi(app, uso = false, batteria = false, notifiche = false, sopra = false)
        scatta("01-permessi-aiuto-aperto", "Permessi: \"Se Android ti blocca…\" aperto (una volta sola, sotto i quattro)", pagine = true) {
            apriPactum(StatiFinti()).also { tocca("Se Android ti blocca…") }.comeAperta()
        }
    }

    // --- 02 Cosa vedono i tuoi genitori --------------------------------------------

    @Test
    fun cosaVedePrimoAvvio() {
        Mondo.collegato(app, cosaVedeVista = false)
        scatta("02-cosa-vedono-primo-avvio", "Passo 3 \"Cosa vedono i tuoi genitori\", con \"Ho capito\" in fondo", pagine = true) {
            apriPactum(StatiFinti()).also { aspetta("Cosa vedono") }.comeAperta()
        }
    }

    // --- 03 La prima regola ----------------------------------------------------------

    @Test
    fun primaRegolaCollegato() {
        Mondo.collegato(app)
        scatta("03-prima-regola-collegato", "Passo 4: \"Collegato come «Telefono di Luca»\" e \"Crea la prima regola\"", pagine = false) {
            apriPactum(StatiFinti(regole = regoleVuote())).also { aspetta("Collegato come") }.comeAperta()
        }
    }

    @Test
    fun primaRegolaDialogoLimite() {
        Mondo.collegato(app)
        scatta("03-prima-regola-dialogo-limite", "Dialogo \"Nuova regola\": tipo Limite di tempo (si tocca tutta la riga del tipo)", pagine = false) {
            apriPactum(StatiFinti(regole = regoleVuote()))
                .also { aspetta("Collegato come"); tocca("Crea la prima regola") }.comeAperta()
        }
    }

    @Test
    fun primaRegolaDialogoFascia() {
        Mondo.collegato(app)
        scatta("03-prima-regola-dialogo-fascia", "Dialogo \"Nuova regola\": tipo Fascia oraria (dalle, alle, giorni)") {
            apriPactum(StatiFinti(regole = regoleVuote()))
                .also { aspetta("Collegato come"); tocca("Crea la prima regola"); toccaNelDialogo("Fascia oraria") }.comeAperta()
        }
    }

    @Test
    fun primaRegolaSceltaApp() {
        Mondo.collegato(app)
        scatta("03-prima-regola-scelta-app", "Dialogo \"Su cosa vale il limite?\": tutto il telefono, categorie, app installate") {
            apriPactum(StatiFinti(regole = regoleVuote())).also {
                aspetta("Collegato come")
                tocca("Crea la prima regola")
                toccaNelDialogo("Scegli app o categoria")
                aspetta("Brawl Stars")
            }.comeAperta()
        }
    }

    // --- 04 I lavori di casa prima di tutto, la sessione sopra i passi ----------------

    @Test
    fun lavoriPrimaDeiPermessi() {
        Mondo.collegato(app)
        Mondo.permessi(app, uso = false)
        ArchivioBlocco.modifica(app) { DatiFinti.bloccoAttivo() }
        scatta("04-lavori-prima-dei-permessi", "Telefono bloccato e permessi mancanti: prima i Lavori di casa, in fondo \"Dai i permessi a Pactum\"") {
            apriPactum(StatiFinti()).comeAperta()
        }
    }

    @Test
    fun lavoriPoiPermessi() {
        Mondo.collegato(app)
        Mondo.permessi(app, uso = false)
        ArchivioBlocco.modifica(app) { DatiFinti.bloccoAttivo() }
        scatta("04-lavori-poi-permessi", "Dai Lavori di casa ai permessi: \"Torna ai lavori di casa da fare\" in fondo", pagine = false) {
            apriPactum(StatiFinti()).also { tocca("Dai i permessi a Pactum") }.comeAperta()
        }
    }

    @Test
    fun sessioneSopraPermessi() {
        Mondo.collegato(app)
        Mondo.permessi(app, uso = false)
        ArchivioSessioni.modifica(app) { MemoriaSessioni(svolte = listOf(DatiFinti.svoltaStudio())) }
        scatta("04-sessione-sopra-permessi", "Sessione in corso mentre mancano i permessi: la sua riga con \"Termina\" sopra la pagina", pagine = false) {
            apriPactum(StatiFinti()).comeAperta()
        }
    }

    @Test
    fun lavoriESessioneInsieme() {
        Mondo.collegato(app)
        Mondo.permessi(app, uso = false)
        ArchivioBlocco.modifica(app) { DatiFinti.bloccoAttivo() }
        ArchivioSessioni.modifica(app) { MemoriaSessioni(svolte = listOf(DatiFinti.svoltaStudio())) }
        scatta("04-lavori-e-sessione-insieme", "Lavori di casa, sessione e permessi insieme: in cima una cosa sola (B23)", pagine = false) {
            apriPactum(StatiFinti()).also { tocca("Dai i permessi a Pactum") }.comeAperta()
        }
    }
}
