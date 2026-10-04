package eu.stgm.pactum.figlio.fotografo

import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.faccende.ArchivioBlocco
import eu.stgm.pactum.figlio.sessione.ArchivioSessioni
import eu.stgm.pactum.figlio.sessione.MemoriaSessioni
import eu.stgm.pactum.figlio.ui.RegoleViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * 01-04 — Prima delle schede: i permessi, "Cosa vedono i tuoi genitori", il
 * collegamento e la prima regola, i lavori di casa che passano davanti a tutto.
 */
class FotoInizioTest : Fotografo() {

    private fun soloCosaVedeVista() = runBlocking { Impostazioni(app).registraCosaVedeVista() }

    // --- 01 Permessi ----------------------------------------------------------------

    @Test
    fun permessiTuttiMancanti() {
        Mondo.permessi(app, uso = false, batteria = false, notifiche = false, sopra = false)
        scatta("01-permessi-nessuno", "Permessi al primo avvio: nessuno dato (4 passi da fare)") {
            apriPactum(StatiFinti()).comeAperta()
        }
    }

    @Test
    fun permessiMancaSoloUso() {
        Mondo.permessi(app, uso = false)
        scatta("01-permessi-manca-uso", "Permessi: manca solo l'accesso ai dati di utilizzo, gli altri 3 fatti") {
            apriPactum(StatiFinti()).comeAperta()
        }
    }

    @Test
    fun permessiAiutoAperto() {
        Mondo.permessi(app, uso = false, batteria = false, notifiche = false, sopra = false)
        scatta("01-permessi-aiuto-aperto", "Permessi: \"Se Android ti blocca…\" aperto sul primo passo") {
            apriPactum(StatiFinti()).also { tocca("Se Android ti blocca…") }.comeAperta()
        }
    }

    // --- 02 Cosa vedono i tuoi genitori --------------------------------------------

    @Test
    fun cosaVedePrimoAvvio() {
        Mondo.collegato(app, cosaVedeVista = false)
        scatta("02-cosa-vedono-primo-avvio", "\"Cosa vedono i tuoi genitori\" dopo i permessi, con \"Ho capito\" in fondo") {
            apriPactum(StatiFinti()).also { aspetta("Cosa vedono") }.comeAperta()
        }
    }

    // --- 03 Collegamento e prima regola --------------------------------------------

    private fun regoleVuote(configurazioneMancante: Boolean, letto: Boolean = true) =
        RegoleViewModel.StatoRegole(caricamento = !letto, letto = letto, configurazioneMancante = configurazioneMancante)

    @Test
    fun primaRegolaDaCollegare() {
        soloCosaVedeVista()
        scatta("03-prima-regola-da-collegare", "Prima regola, telefono non collegato: indirizzo del server e codice di 6 cifre") {
            apriPactum(StatiFinti(regole = regoleVuote(configurazioneMancante = true)))
                .also { aspetta("Indirizzo del server") }.comeAperta()
        }
    }

    @Test
    fun primaRegolaCodiceLungo() {
        soloCosaVedeVista()
        scatta("03-prima-regola-codice-lungo", "Prima regola, non collegato, con \"Hai un codice lungo?\" aperto") {
            apriPactum(StatiFinti(regole = regoleVuote(configurazioneMancante = true)))
                .also { aspetta("Hai un codice lungo?"); tocca("Hai un codice lungo?") }.comeAperta()
        }
    }

    @Test
    fun primaRegolaCollegato() {
        Mondo.collegato(app)
        scatta("03-prima-regola-collegato", "Prima regola, appena collegato: \"Collegato come\" e \"Crea la prima regola\"") {
            apriPactum(StatiFinti(regole = regoleVuote(configurazioneMancante = false)))
                .also { aspetta("Collegato come") }.comeAperta()
        }
    }

    @Test
    fun primaRegolaCaricamento() {
        Mondo.collegato(app)
        scatta("03-prima-regola-caricamento", "Prima lettura delle regole (rotella a tutto schermo)", pagine = false) {
            apriPactum(StatiFinti(regole = regoleVuote(configurazioneMancante = false, letto = false))).comeAperta()
        }
    }

    @Test
    fun primaRegolaDialogoLimite() {
        Mondo.collegato(app)
        scatta("03-prima-regola-dialogo-limite", "Dialogo \"Crea la prima regola\": tipo Limite di tempo") {
            apriPactum(StatiFinti(regole = regoleVuote(false)))
                .also { aspetta("Collegato come"); tocca("Crea la prima regola") }.comeAperta()
        }
    }

    @Test
    fun primaRegolaDialogoFascia() {
        Mondo.collegato(app)
        scatta("03-prima-regola-dialogo-fascia", "Dialogo \"Crea la prima regola\": tipo Fascia oraria (dalle, alle, giorni)") {
            apriPactum(StatiFinti(regole = regoleVuote(false)))
                .also { aspetta("Collegato come"); tocca("Crea la prima regola"); toccaPallino(1) }.comeAperta()
        }
    }

    @Test
    fun primaRegolaDialogoVitaReale() {
        Mondo.collegato(app)
        scatta("03-prima-regola-dialogo-vita-reale", "Dialogo \"Crea la prima regola\": tipo Vita reale (descrizione, arbitro, frequenza)") {
            apriPactum(StatiFinti(regole = regoleVuote(false)))
                .also { aspetta("Collegato come"); tocca("Crea la prima regola"); toccaPallino(2) }.comeAperta()
        }
    }

    @Test
    fun primaRegolaSceltaApp() {
        Mondo.collegato(app)
        scatta("03-prima-regola-scelta-app", "Dialogo \"Su cosa vale il limite?\": tutto il telefono, categorie, app installate") {
            apriPactum(StatiFinti(regole = regoleVuote(false))).also {
                aspetta("Collegato come")
                tocca("Crea la prima regola")
                toccaNelDialogo("Scegli app o categoria")
                aspetta("Brawl Stars")
            }.comeAperta()
        }
    }

    // --- 04 I lavori di casa prima di tutto ----------------------------------------

    @Test
    fun lavoriPrimaDeiPermessi() {
        Mondo.permessi(app, uso = false)
        ArchivioBlocco.modifica(app) { DatiFinti.bloccoAttivo() }
        scatta("04-lavori-prima-dei-permessi", "Telefono bloccato e permessi mancanti: prima i Lavori di casa, sotto \"Dai i permessi a Pactum\"") {
            apriPactum(StatiFinti()).comeAperta()
        }
    }

    @Test
    fun lavoriPoiPermessi() {
        Mondo.permessi(app, uso = false)
        ArchivioBlocco.modifica(app) { DatiFinti.bloccoAttivo() }
        scatta("04-lavori-poi-permessi", "Dai Lavori di casa ai permessi: \"Torna ai lavori di casa da fare\" in cima") {
            apriPactum(StatiFinti()).also { tocca("Dai i permessi a Pactum") }.comeAperta()
        }
    }

    @Test
    fun lavoriPrimaDiCosaVede() {
        Mondo.collegato(app, cosaVedeVista = false)
        ArchivioBlocco.modifica(app) { DatiFinti.bloccoAttivo() }
        scatta("04-lavori-prima-di-cosa-vedono", "Telefono bloccato al primo avvio: Lavori di casa, sotto \"Leggi cosa vede il genitore\"", pagine = false) {
            apriPactum(StatiFinti()).also { aspetta("Leggi cosa vede") }.comeAperta()
        }
    }

    @Test
    fun sessioneSopraPermessi() {
        Mondo.permessi(app, uso = false)
        ArchivioSessioni.modifica(app) { MemoriaSessioni(svolte = listOf(DatiFinti.svoltaStudio())) }
        scatta("04-sessione-sopra-permessi", "Sessione in corso mentre mancano i permessi: la sua scheda sopra la checklist") {
            apriPactum(StatiFinti()).comeAperta()
        }
    }
}
