package eu.stgm.pactum.figlio.fotografo

import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.ui.SitiViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** 11 — Siti visitati (dalla scheda in Oggi) · 12 — Impostazioni (dalla rotella in Oggi). */
class FotoSitiImpostazioniTest : Fotografo() {

    private fun siti(stato: SitiViewModel.StatoSiti, dopo: () -> Unit = {}): Aperta =
        apriPactum(StatiFinti(siti = stato)).also {
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
        scatta("11-siti-attiva", "Siti visitati, osservazione accesa: 4 giorni (vuoto, zero, tagliato + DNS cifrato, oggi)") {
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
    fun sitiVuoti() {
        Mondo.collegato(app)
        scatta("11-siti-vuoti", "Siti visitati: osservazione spenta, nessun giorno (stato vuoto)", pagine = false) {
            siti(SitiViewModel.StatoSiti(caricamento = false))
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
    fun sitiNonCollegato() {
        Mondo.collegato(app)
        scatta("11-siti-non-collegato", "Siti visitati con il telefono non collegato", pagine = false) {
            siti(SitiViewModel.StatoSiti(caricamento = false, configurazioneMancante = true))
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
        scatta("12-impostazioni-collegato", "Impostazioni: collegato come Telefono di Luca, chiusura della sera, avviso a tutto schermo attivo") {
            impostazioni()
        }
    }

    @Test
    fun impostazioniAvvisoSpento() {
        Mondo.collegato(app)
        Mondo.permessi(app, sopra = false)
        scatta("12-impostazioni-avviso-spento", "Impostazioni con l'avviso a tutto schermo spento (manca il permesso)") {
            impostazioni()
        }
    }

    @Test
    fun impostazioniSeraleSpenta() {
        Mondo.collegato(app)
        runBlocking { Impostazioni(app).salvaChiusuraSerale(false, 21 * 60 + 30) }
        scatta("12-impostazioni-serale-spenta", "Impostazioni con la chiusura della sera spenta") {
            impostazioni()
        }
    }

    @Test
    fun impostazioniCodiceLungo() {
        Mondo.collegato(app)
        scatta("12-impostazioni-codice-lungo", "Impostazioni con \"Hai un codice lungo?\" aperto") {
            impostazioni { tocca("Hai un codice lungo?") }
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
            impostazioni { tocca("Guarda l'elenco") }
        }
    }
}
