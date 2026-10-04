package eu.stgm.pactum.figlio.fotografo

import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.ui.SitiViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** 11 — Siti visitati · 12 — Impostazioni (da ⚙ in ogni scheda; i Siti si aprono da lì). */
class FotoSitiImpostazioniTest : Fotografo() {

    private fun siti(stato: SitiViewModel.StatoSiti, dopo: () -> Unit = {}): Aperta =
        apriPactum(StatiFinti(siti = stato)).also {
            toccaIcona("Impostazioni")
            aspetta("Siti visitati")
            tocca("Siti visitati")
            dopo()
        }.comeAperta()

    private fun impostazioni(dopo: () -> Unit = {}): Aperta =
        apriPactum(StatiFinti()).also {
            toccaIcona("Impostazioni")
            aspetta("Collegato come")
            dopo()
        }.comeAperta()

    // --- 11 Siti ------------------------------------------------------------------

    @Test
    fun sitiAttiva() {
        Mondo.collegato(app)
        scatta("11-siti-attiva", "Siti visitati, osservazione accesa: 4 giorni (vuoto, zero, tagliato + DNS cifrato, oggi)", pagine = true) {
            siti(DatiFinti.sitiNormali())
        }
    }

    @Test
    fun sitiSpenta() {
        Mondo.collegato(app)
        scatta("11-siti-spenta", "Siti visitati, osservazione spenta, con la lista dei giorni") {
            siti(DatiFinti.sitiNormali().copy(osservazioneAttiva = false))
        }
    }

    @Test
    fun sitiSenzaRete() {
        Mondo.collegato(app)
        scatta("11-siti-senza-rete", "Siti visitati senza rete (\"dati non aggiornati\")") {
            siti(DatiFinti.sitiNormali().copy(datiVecchi = true))
        }
    }

    @Test
    fun sitiConsenso() {
        Mondo.collegato(app)
        scatta("11-siti-consenso", "\"Cosa stai accettando\": il consenso prima di accendere l'osservazione dei siti") {
            siti(DatiFinti.sitiNormali().copy(osservazioneAttiva = false)) { tocca("Attiva l'osservazione") }
        }
    }

    // --- 12 Impostazioni -------------------------------------------------------------

    @Test
    fun impostazioniCollegato() {
        Mondo.collegato(app)
        scatta("12-impostazioni-collegato", "Impostazioni: \"Collegato come «Telefono di Luca»\" · Cambia, i 4 permessi attivi, la sera, Siti, Cosa vedono, in fondo la prova", pagine = true) {
            impostazioni()
        }
    }

    @Test
    fun impostazioniAvvisoSpento() {
        Mondo.collegato(app)
        Mondo.permessi(app, sopra = false)
        scatta("12-impostazioni-avviso-spento", "Impostazioni arrivando da \"Da sistemare\" in Oggi: manca \"Mostra sopra le altre app\" (\"Apri\")", pagine = false) {
            apriPactum(StatiFinti()).also { tocca("Risolvi"); aspetta("Permessi") }.comeAperta()
        }
    }

    @Test
    fun impostazioniCodiceLungo() {
        Mondo.collegato(app)
        scatta("12-impostazioni-codice-lungo", "Impostazioni dopo \"Cambia\": il modulo del collegamento, con \"Hai un codice lungo?\" aperto") {
            impostazioni { tocca("Cambia"); tocca("Hai un codice lungo?") }
        }
    }

    @Test
    fun impostazioniDialogoOra() {
        Mondo.collegato(app)
        scatta("12-impostazioni-dialogo-ora", "Impostazioni, dialogo dell'ora della chiusura della sera (orologio)", pagine = false) {
            impostazioni { tocca("21:30") }
        }
    }

    @Test
    fun cosaVedeDaImpostazioni() {
        Mondo.collegato(app)
        scatta("12-impostazioni-cosa-vedono", "\"Cosa vedono i tuoi genitori\" aperto dalle Impostazioni (freccia indietro)") {
            impostazioni { tocca("Cosa vedono i tuoi genitori") }
        }
    }
}
