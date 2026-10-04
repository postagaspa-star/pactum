package eu.stgm.pactum.figlio.fotografo

import eu.stgm.pactum.figlio.MainActivity
import eu.stgm.pactum.figlio.ui.ProposteViewModel
import org.junit.Test

/** 09 — La scheda Proposte e i suoi dialoghi. */
class FotoProposteTest : Fotografo() {

    private fun proposte(stato: ProposteViewModel.StatoProposte = DatiFinti.proposteNormali(), dopo: () -> Unit = {}): Aperta =
        apriPactum(StatiFinti(proposte = stato), MainActivity.DEST_PROPOSTE).also { dopo() }.comeAperta()

    @Test
    fun normale() {
        Mondo.collegato(app)
        scatta("09-proposte-normale", "Proposte: 2 da decidere (Mamma, Papà), 1 tua in attesa, storia di 4") {
            proposte()
        }
    }

    @Test
    fun vuote() {
        Mondo.collegato(app)
        scatta("09-proposte-vuote", "Proposte: niente da decidere, nessuna tua, storia vuota (stato vuoto)", pagine = false) {
            proposte(DatiFinti.proposteVuote())
        }
    }

    @Test
    fun erroreLettura() {
        Mondo.collegato(app)
        scatta("09-proposte-errore", "Proposte che non si leggono (senza rete, nessuna copia)", pagine = false) {
            proposte(ProposteViewModel.StatoProposte(caricamento = false, errore = true))
        }
    }

    @Test
    fun senzaRete() {
        Mondo.collegato(app)
        scatta("09-proposte-senza-rete", "Proposte senza rete con la lista di prima (\"Dati non aggiornati\")") {
            proposte(DatiFinti.proposteNormali().copy(errore = true, aggiornateIl = DatiFinti.adesso() - 5 * DatiFinti.ORA))
        }
    }

    @Test
    fun caricamento() {
        Mondo.collegato(app)
        scatta("09-proposte-caricamento", "Proposte alla prima lettura (rotella)", pagine = false) {
            proposte(ProposteViewModel.StatoProposte())
        }
    }

    @Test
    fun motivazioneAperta() {
        Mondo.collegato(app)
        scatta("09-proposte-motivazione-aperta", "Proposta del genitore con il campo \"motivazione\" aperto", pagine = false) {
            proposte { tocca("aggiungi una motivazione", 0) }
        }
    }

    @Test
    fun dialogoSceltaRegola() {
        Mondo.collegato(app)
        scatta("09-proposte-dialogo-scegli-regola", "Dialogo \"Quale regola vuoi cambiare?\" (3 regole occupate da proposte)") {
            proposte { tocca("Nuova proposta") }
        }
    }

    @Test
    fun dialogoProponi() {
        Mondo.collegato(app)
        scatta("09-proposte-dialogo-proponi", "Dialogo \"Proponi al genitore\" sulla regola Social, da \"Nuova proposta\"") {
            proposte {
                tocca("Nuova proposta")
                toccaNelDialogo("Social", sottostringa = true)
            }
        }
    }

    @Test
    fun dialogoRitira() {
        Mondo.collegato(app)
        scatta("09-proposte-dialogo-ritira", "Dialogo \"Ritirare la proposta?\" su una tua proposta", pagine = false) {
            proposte { tocca("Ritira", 0) }
        }
    }
}
