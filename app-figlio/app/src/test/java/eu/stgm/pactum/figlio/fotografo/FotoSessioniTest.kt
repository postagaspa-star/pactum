package eu.stgm.pactum.figlio.fotografo

import eu.stgm.pactum.figlio.MainActivity
import eu.stgm.pactum.figlio.faccende.ArchivioBlocco
import eu.stgm.pactum.figlio.sessione.ArchivioSessioni
import eu.stgm.pactum.figlio.sessione.EsitoSessione
import eu.stgm.pactum.figlio.sessione.MemoriaSessioni
import eu.stgm.pactum.figlio.ui.SessioniViewModel
import org.junit.Test

/** 08 — La scheda Sessioni (0.15: card compatte, un pulsante, il resto nel ⋯) e i suoi dialoghi. */
class FotoSessioniTest : Fotografo() {

    private fun sessioni(stato: SessioniViewModel.StatoSessioni = DatiFinti.sessioniNormali(), dopo: (StatiFinti) -> Unit = {}): Aperta {
        val stati = StatiFinti(sessioni = stato)
        return apriPactum(stati, MainActivity.DEST_SESSIONI).also { dopo(stati) }.comeAperta()
    }

    /** Apre il ⋯ della sessione numero [indice] e tocca [voce]. */
    private fun menu(voce: String, indice: Int = 0) {
        toccaIcona("Altre azioni sulla sessione", indice)
        tocca(voce)
    }

    private fun inCorso() {
        ArchivioSessioni.modifica(app) { MemoriaSessioni(svolte = listOf(DatiFinti.svoltaStudio())) }
    }

    @Test
    fun normale() {
        Mondo.collegato(app)
        scatta("08-sessioni-normale", "Sessioni: approvata, con cambio in attesa, in attesa, rifiutata (Papà dice…), approvata con cambio non approvato", Variante.SCHEDE, pagine = true) {
            sessioni()
        }
    }

    @Test
    fun conSessioneInCorso() {
        Mondo.collegato(app)
        inCorso()
        scatta("08-sessioni-in-corso", "Sessioni con \"Studio\" in corso: scheda in cima, le altre non si iniziano") {
            sessioni()
        }
    }

    @Test
    fun conBloccoLavori() {
        Mondo.collegato(app)
        ArchivioBlocco.modifica(app) { DatiFinti.bloccoAttivo() }
        scatta("08-sessioni-blocco-lavori", "Sessioni con il telefono bloccato dai lavori di casa: \"Inizia\" spento e il perché") {
            sessioni()
        }
    }

    @Test
    fun vuote() {
        Mondo.collegato(app)
        scatta("08-sessioni-vuote", "Sessioni, nessuna ancora (stato vuoto)", pagine = false) {
            sessioni(SessioniViewModel.StatoSessioni(caricamento = false, letto = true))
        }
    }

    @Test
    fun senzaRete() {
        Mondo.collegato(app)
        scatta("08-sessioni-senza-rete", "Sessioni senza rete: \"Dati non aggiornati\"") {
            sessioni(DatiFinti.sessioniNormali().copy(datiFermi = true, datiFermiAlle = DatiFinti.adesso() - 2 * DatiFinti.GIORNO))
        }
    }

    @Test
    fun scollegato() {
        Mondo.collegato(app)
        scatta("08-sessioni-scollegato", "Sessioni con il telefono scollegato (401)", pagine = false) {
            sessioni(DatiFinti.sessioniNormali().copy(scollegato = true))
        }
    }

    @Test
    fun menuAperto() {
        Mondo.collegato(app)
        scatta("08-sessioni-menu", "Il ⋯ di una sessione: Modifica · Ritira il cambio · Elimina", pagine = false) {
            sessioni { toccaIcona("Altre azioni sulla sessione", 1) }
        }
    }

    @Test
    fun dialogoNuova() {
        Mondo.collegato(app)
        scatta("08-sessioni-dialogo-nuova", "Dialogo \"Nuova sessione\" vuoto") {
            sessioni { toccaIcona("Nuova sessione") }
        }
    }

    @Test
    fun dialogoModifica() {
        Mondo.collegato(app)
        scatta("08-sessioni-dialogo-modifica", "Dialogo di cambio della sessione \"Studio\" (approvata)") {
            sessioni { menu("Modifica") }
        }
    }

    @Test
    fun dialogoSceltaApp() {
        Mondo.collegato(app)
        scatta("08-sessioni-dialogo-scelta-app", "Dialogo \"Quali app nella sessione?\" (con la ricerca)") {
            sessioni {
                menu("Modifica")
                toccaNelDialogo("Scegli le app")
                aspetta("Brawl Stars")
            }
        }
    }

    @Test
    fun dialogoAvvio() {
        Mondo.collegato(app)
        scatta("08-sessioni-dialogo-inizia", "Dialogo \"Inizia «Studio»\": durate a chip, fino alle…, una riga sola di spiegazione") {
            sessioni { tocca("Inizia", 0) }
        }
    }

    @Test
    fun dialogoAvvioSenzaPermesso() {
        Mondo.collegato(app)
        Mondo.permessi(app, sopra = false)
        scatta("08-sessioni-dialogo-inizia-manca-permesso", "Dialogo \"Inizia\" senza \"Mostra sopra le altre app\": la riga \"Manca il permesso\" con \"Risolvi\"") {
            sessioni { tocca("Inizia", 0) }
        }
    }

    @Test
    fun dialogoElimina() {
        Mondo.collegato(app)
        scatta("08-sessioni-dialogo-elimina", "Dialogo \"Eliminare la sessione?\"", pagine = false) {
            sessioni { menu("Elimina") }
        }
    }

    @Test
    fun dialogoTermina() {
        Mondo.collegato(app)
        inCorso()
        scatta("08-sessioni-dialogo-termina", "Dialogo \"Termina la sessione?\" dalla scheda Sessioni", pagine = false) {
            sessioni { tocca("Termina", 0) }
        }
    }
}
