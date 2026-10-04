package eu.stgm.pactum.figlio.fotografo

import eu.stgm.pactum.figlio.MainActivity
import eu.stgm.pactum.figlio.ui.DichiarazioniViewModel
import org.junit.Test

/** 10 — Il Diario (regole di vita reale) e il dialogo della dichiarazione. */
class FotoDiarioTest : Fotografo() {

    private fun diario(stato: DichiarazioniViewModel.StatoDiario = DatiFinti.diarioNormale(), dopo: () -> Unit = {}): Aperta =
        apriPactum(StatiFinti(diario = stato), MainActivity.DEST_DIARIO).also { dopo() }.comeAperta()

    @Test
    fun normale() {
        Mondo.collegato(app)
        scatta("10-diario-normale", "Diario: una regola da dichiarare, una già fatta oggi (in attesa della firma), 4 dichiarazioni risolte") {
            diario()
        }
    }

    @Test
    fun vuoto() {
        Mondo.collegato(app)
        scatta("10-diario-vuoto", "Diario senza regole di vita reale e senza dichiarazioni (stato vuoto)", pagine = false) {
            diario(DichiarazioniViewModel.StatoDiario(caricamento = false))
        }
    }

    @Test
    fun senzaRete() {
        Mondo.collegato(app)
        scatta("10-diario-senza-rete", "Diario senza rete (\"Dati non aggiornati\")") {
            diario(DatiFinti.diarioNormale().copy(errore = true, datiFermiAlle = DatiFinti.adesso() - 40 * DatiFinti.MINUTO))
        }
    }

    @Test
    fun caricamento() {
        Mondo.collegato(app)
        scatta("10-diario-caricamento", "Diario alla prima lettura (rotella)", pagine = false) {
            diario(DichiarazioniViewModel.StatoDiario())
        }
    }

    @Test
    fun dialogoSuccesso() {
        Mondo.collegato(app)
        scatta("10-diario-dialogo-ce-l-ho-fatta", "Dialogo \"Ce l'ho fatta\": giorno a chip (oggi … 7 giorni fa) e nota", pagine = false) {
            diario { tocca("Ce l'ho fatta", 0) }
        }
    }

    @Test
    fun dialogoFallimento() {
        Mondo.collegato(app)
        scatta("10-diario-dialogo-non-ce-l-ho-fatta", "Dialogo \"Non ce l'ho fatta\"", pagine = false) {
            diario { tocca("Non ce l'ho fatta", 0) }
        }
    }
}
