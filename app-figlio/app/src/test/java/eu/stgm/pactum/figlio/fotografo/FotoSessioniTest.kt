package eu.stgm.pactum.figlio.fotografo

import eu.stgm.pactum.figlio.MainActivity
import eu.stgm.pactum.figlio.faccende.ArchivioBlocco
import eu.stgm.pactum.figlio.sessione.ArchivioSessioni
import eu.stgm.pactum.figlio.sessione.EsitoSessione
import eu.stgm.pactum.figlio.sessione.MemoriaSessioni
import eu.stgm.pactum.figlio.ui.SessioniViewModel
import org.junit.Test

/** 08 — La scheda Sessioni e i suoi dialoghi. */
class FotoSessioniTest : Fotografo() {

    private fun sessioni(stato: SessioniViewModel.StatoSessioni = DatiFinti.sessioniNormali(), dopo: (StatiFinti) -> Unit = {}): Aperta {
        val stati = StatiFinti(sessioni = stato)
        return apriPactum(stati, MainActivity.DEST_SESSIONI).also { dopo(stati) }.comeAperta()
    }

    private fun inCorso() {
        ArchivioSessioni.modifica(app) { MemoriaSessioni(svolte = listOf(DatiFinti.svoltaStudio())) }
    }

    @Test
    fun normale() {
        Mondo.collegato(app)
        scatta("08-sessioni-normale", "Sessioni: approvata, con cambio in attesa, in attesa, rifiutata, approvata con cambio rifiutato") {
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
    fun serverVecchio() {
        Mondo.collegato(app)
        scatta("08-sessioni-server-da-aggiornare", "Sessioni con un server che non le conosce", pagine = false) {
            sessioni(SessioniViewModel.StatoSessioni(caricamento = false, letto = true, serverDaAggiornare = true))
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
    fun caricamento() {
        Mondo.collegato(app)
        scatta("08-sessioni-caricamento", "Sessioni alla prima lettura (rotella)", pagine = false) {
            sessioni(SessioniViewModel.StatoSessioni())
        }
    }

    @Test
    fun avvioIncerto() {
        Mondo.collegato(app)
        ArchivioSessioni.modifica(app) { MemoriaSessioni(avvioIncerto = DatiFinti.avvioIncerto()) }
        scatta("08-sessioni-avvio-incerto", "Sessioni con un \"Inizia\" senza risposta", pagine = false) {
            sessioni()
        }
    }

    @Test
    fun dialogoNuova() {
        Mondo.collegato(app)
        scatta("08-sessioni-dialogo-nuova", "Dialogo \"Nuova sessione\" vuoto") {
            sessioni { tocca("Nuova sessione") }
        }
    }

    @Test
    fun dialogoModifica() {
        Mondo.collegato(app)
        scatta("08-sessioni-dialogo-modifica", "Dialogo di cambio della sessione \"Studio\" (approvata)") {
            sessioni { tocca("Modifica", 0) }
        }
    }

    @Test
    fun dialogoModificaConEsito() {
        Mondo.collegato(app)
        scatta("08-sessioni-dialogo-modifica-senza-rete", "Dialogo di cambio dopo un invio senza rete (riga neutra)") {
            sessioni { stati ->
                tocca("Modifica", 0)
                stati.flussi.sessioni.value = stati.flussi.sessioni.value.copy(esitoModulo = EsitoSessione.SenzaRete)
                calma()
            }
        }
    }

    @Test
    fun dialogoSceltaApp() {
        Mondo.collegato(app)
        scatta("08-sessioni-dialogo-scelta-app", "Dialogo \"Quali app nella sessione?\" (con la ricerca)") {
            sessioni {
                tocca("Modifica", 0)
                toccaNelDialogo("Scegli le app")
                aspetta("Brawl Stars")
            }
        }
    }

    @Test
    fun dialogoAvvio() {
        Mondo.collegato(app)
        scatta("08-sessioni-dialogo-inizia", "Dialogo \"Inizia «Studio»\": durate a chip, fino alle…, spiegazioni") {
            sessioni { tocca("Inizia", 0) }
        }
    }

    @Test
    fun dialogoAvvioAltro() {
        Mondo.collegato(app)
        scatta("08-sessioni-dialogo-inizia-altro", "Dialogo \"Inizia\" con durata \"Altro\" (ore e minuti scritti)") {
            sessioni {
                tocca("Inizia", 0)
                toccaNelDialogo("Altro")
            }
        }
    }

    @Test
    fun dialogoAvvioSenzaPermesso() {
        Mondo.collegato(app)
        Mondo.permessi(app, sopra = false)
        scatta("08-sessioni-dialogo-inizia-manca-permesso", "Dialogo \"Inizia\" senza \"Mostra sopra le altre app\": il permesso da dare") {
            sessioni { tocca("Inizia", 0) }
        }
    }

    @Test
    fun dialogoElimina() {
        Mondo.collegato(app)
        scatta("08-sessioni-dialogo-elimina", "Dialogo \"Eliminare la sessione?\"", pagine = false) {
            sessioni { tocca("Elimina", 0) }
        }
    }

    @Test
    fun dialogoRitiraCambio() {
        Mondo.collegato(app)
        val stato = DatiFinti.sessioniNormali().copy(sessioni = listOf(DatiFinti.musica, DatiFinti.studio))
        scatta("08-sessioni-dialogo-ritira-cambio", "Dialogo \"Ritira il cambio\" sulla sessione \"Musica\"", pagine = false) {
            sessioni(stato) { tocca("Ritira il cambio", 0) }
        }
    }

    @Test
    fun dialogoTermina() {
        Mondo.collegato(app)
        inCorso()
        scatta("08-sessioni-dialogo-termina", "Dialogo \"Termina la sessione?\" dalla scheda Sessioni", pagine = false) {
            sessioni { tocca("Termina la sessione", 0) }
        }
    }
}
