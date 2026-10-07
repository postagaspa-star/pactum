package eu.stgm.pactum.figlio.fotografo

import eu.stgm.pactum.figlio.MainActivity
import eu.stgm.pactum.figlio.faccende.ArchivioBlocco
import eu.stgm.pactum.figlio.faccende.BarrieraFaccendeActivity
import eu.stgm.pactum.figlio.studio.ArchivioStudio
import eu.stgm.pactum.figlio.studio.BarrieraStudioActivity
import eu.stgm.pactum.figlio.studio.MemoriaStudio
import org.junit.Test

/**
 * 20-22 — (0.18, contratto v4.0) La Sessione Studio e i lavori che aspettano
 * l'approvazione: la card in Oggi (in corso col timer, chiudibile, in
 * partenza, chiusura rifiutata), i dialoghi «Comincia un'attività» e «Chiudi
 * lo Studio», la sezione in cima a Sessioni (approvata, proposta in attesa,
 * nessuna, il modulo, lo storico), la barriera «Sei in Studio», e i Lavori
 * con «Aspettano l'approvazione» (pagina e barriera).
 */
class FotoStudioTest : Fotografo() {

    private fun prepara(studio: MemoriaStudio) {
        Mondo.collegato(app)
        ArchivioStudio.modifica(app) { studio }
    }

    // --- 20 Oggi -------------------------------------------------------------------

    @Test
    fun oggiInCorso() {
        prepara(DatiStudio.inCorso())
        scatta("20-studio-oggi-in-corso", "Oggi in Studio: card in cima con lo stato (42 min su 60, si chiude dopo le …), il timer di «allenamento» e «Ferma»", Variante.SCHEDE) {
            apriPactum(StatiFinti(), MainActivity.DEST_OGGI).comeAperta()
        }
    }

    @Test
    fun oggiChiudibile() {
        prepara(DatiStudio.chiudibile())
        ArchivioBlocco.modifica(app) { DatiStudio.bloccoRimandato() }
        scatta("20-studio-oggi-chiudibile", "Oggi in Studio con le condizioni fatte: «Chiudi lo Studio», e «Il blocco dei lavori parte a fine Studio»") {
            apriPactum(StatiFinti(), MainActivity.DEST_OGGI).comeAperta()
        }
    }

    @Test
    fun oggiInPartenza() {
        prepara(DatiStudio.inPartenza())
        scatta("20-studio-oggi-in-partenza", "Oggi prima dello Studio: «Lo Studio parte alle … · tra 20 min»") {
            apriPactum(StatiFinti(), MainActivity.DEST_OGGI).comeAperta()
        }
    }

    @Test
    fun oggiRifiuto() {
        prepara(DatiStudio.rifiutato())
        scatta("20-studio-oggi-rifiuto", "Il server ha rifiutato la chiusura: lo Studio torna col motivo («per il server erano le …»)") {
            apriPactum(StatiFinti(), MainActivity.DEST_OGGI).comeAperta()
        }
    }

    @Test
    fun dialogoAttivita() {
        prepara(DatiStudio.inCorso(timer = false))
        scatta("20-studio-comincia-attivita", "«Comincia un'attività»: compiti (con la materia), lavori di casa, altro con una parola", pagine = false) {
            apriPactum(StatiFinti(), MainActivity.DEST_OGGI).also { tocca("Comincia un'attività") }.comeAperta()
        }
    }

    @Test
    fun dialogoChiudi() {
        prepara(DatiStudio.chiudibile())
        scatta("20-studio-chiudi", "«Chiudi lo Studio»: il riepilogo dei tratti e «Cosa hai fatto?» (10-1000 caratteri)", pagine = false) {
            apriPactum(StatiFinti(), MainActivity.DEST_OGGI).also { tocca("Chiudi lo Studio") }.comeAperta()
        }
    }

    // --- 21 Sessioni -----------------------------------------------------------------

    @Test
    fun sessioniApprovata() {
        prepara(DatiStudio.inPartenza())
        scatta("21-studio-sessioni-approvata", "Sessioni: in cima la «Sessione Studio» approvata (giorni, orari, app, approvata da Mamma), «Chiedi di cambiarla», «Inizia adesso»", Variante.SCHEDE, pagine = true) {
            apriPactum(StatiFinti(), MainActivity.DEST_SESSIONI).comeAperta()
        }
    }

    @Test
    fun sessioniInStudio() {
        prepara(DatiStudio.inCorso())
        scatta("21-studio-sessioni-in-corso", "Sessioni durante lo Studio: «In corso», le sessioni non si iniziano") {
            apriPactum(StatiFinti(), MainActivity.DEST_SESSIONI).comeAperta()
        }
    }

    @Test
    fun sessioniProposta() {
        prepara(DatiStudio.conProposta())
        scatta("21-studio-sessioni-proposta", "Sessioni: la proposta di cambio che aspetta un genitore, sotto quella approvata") {
            apriPactum(StatiFinti(), MainActivity.DEST_SESSIONI).comeAperta()
        }
    }

    @Test
    fun sessioniNessuna() {
        prepara(DatiStudio.nessuna())
        scatta("21-studio-sessioni-nessuna", "Sessioni: la Sessione Studio non c'è ancora, «Proponi»") {
            apriPactum(StatiFinti(), MainActivity.DEST_SESSIONI).comeAperta()
        }
    }

    @Test
    fun modulo() {
        prepara(DatiStudio.inPartenza())
        scatta("21-studio-proposta-modulo", "Il modulo della proposta: giorni, orari, minuti, app", pagine = false) {
            apriPactum(StatiFinti(), MainActivity.DEST_SESSIONI).also { tocca("Chiedi di cambiarla") }.comeAperta()
        }
    }

    @Test
    fun storico() {
        prepara(DatiStudio.inPartenza())
        scatta("21-studio-storico", "Lo storico dello Studio: chiuso da Luca con la dichiarazione, chiuso da Mamma col motivo, non chiuso", pagine = false) {
            apriPactum(StatiFinti(studio = DatiStudio.storicoAperto()), MainActivity.DEST_SESSIONI).comeAperta()
        }
    }

    @Test
    fun versioni() {
        prepara(DatiStudio.inPartenza())
        scatta("21-studio-versioni", "Le versioni approvate dello Studio: chi le ha approvate e da quando valgono", pagine = false) {
            apriPactum(StatiFinti(studio = DatiStudio.versioniAperte()), MainActivity.DEST_SESSIONI).comeAperta()
        }
    }

    // --- 22 Barriere --------------------------------------------------------------------

    @Test
    fun barrieraStudio() {
        prepara(DatiStudio.inCorso())
        scatta("22-barriera-studio", "Barriera «Sei in Studio» sopra un'app fuori lista: la lista, come si chiude, «Esci»", pagine = false) {
            apriActivity(BarrieraStudioActivity::class.java).comeAperta()
        }
    }

    @Test
    fun lavoriApprovazione() {
        Mondo.collegato(app)
        ArchivioBlocco.modifica(app) { DatiStudio.bloccoConApprovazione() }
        scatta("22-lavori-approvazione", "Lavori di casa (v4.0): «Da fare» e «Aspettano l'approvazione» con l'ora della foto; si sblocca quando un genitore approva", pagine = true) {
            apriPactum(StatiFinti(), MainActivity.DEST_FACCENDE).comeAperta()
        }
    }

    @Test
    fun barrieraLavoriApprovazione() {
        ArchivioBlocco.modifica(app) { DatiStudio.bloccoConApprovazione() }
        scatta("22-barriera-lavori-approvazione", "Barriera «Prima i lavori di casa» con «Da fare» e «Aspettano l'approvazione»", pagine = false) {
            apriActivity(BarrieraFaccendeActivity::class.java).comeAperta()
        }
    }
}
