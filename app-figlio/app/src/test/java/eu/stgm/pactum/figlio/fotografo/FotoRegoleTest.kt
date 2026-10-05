package eu.stgm.pactum.figlio.fotografo

import eu.stgm.pactum.figlio.MainActivity
import eu.stgm.pactum.figlio.dati.CambioRegola
import eu.stgm.pactum.figlio.dati.EsitoProposta
import eu.stgm.pactum.figlio.ui.DichiarazioniViewModel
import eu.stgm.pactum.figlio.ui.ProposteViewModel
import eu.stgm.pactum.figlio.ui.RegoleViewModel
import org.junit.Test

/**
 * 07 — La scheda Regole (0.15): "Da decidere" in cima, una card compatta per
 * regola col menu ⋯, "Ce l'ho fatta / Non ce l'ho fatta" sulla vita reale, lo
 * Storico in fondo. 09 e 10 — le proposte e le dichiarazioni, che ora stanno qui.
 */
class FotoRegoleTest : Fotografo() {

    private fun regole(
        stato: RegoleViewModel.StatoRegole = DatiFinti.regoleNormali(),
        proposte: ProposteViewModel.StatoProposte = DatiFinti.proposteNormali(),
        diario: DichiarazioniViewModel.StatoDiario = DatiFinti.diarioNormale(),
        destinazione: String = MainActivity.DEST_REGOLE,
        dopo: (StatiFinti) -> Unit = {},
    ): Aperta {
        val stati = StatiFinti(regole = stato, proposte = proposte, diario = diario)
        return apriPactum(stati, destinazione).also { dopo(stati) }.comeAperta()
    }

    /** Le regole senza proposte in attesa: nel ⋯ di ogni regola c'è "Proponi al genitore". */
    private fun senzaProposte() = DatiFinti.regoleNormali().copy(proposteInAttesa = emptyMap())

    /** Apre il ⋯ della prima regola e tocca [voce]. */
    private fun menu(voce: String) {
        toccaIcona("Altre azioni sulla regola", 0)
        tocca(voce)
    }

    @Test
    fun normale() {
        Mondo.collegato(app)
        scatta(
            "07-regole-normale",
            "Regole: \"Da decidere\" (Mamma, Papà), 7 regole con ⋯ (una concordata, due con proposta in attesa), vita reale coi due pulsanti, 2 sul computer, Storico",
            Variante.SCHEDE,
            pagine = true,
        ) { regole() }
    }

    @Test
    fun senzaDaDecidere() {
        Mondo.collegato(app)
        scatta("07-regole-senza-proposte", "Regole senza proposte del genitore: si parte dalle regole") {
            regole(proposte = DatiFinti.proposteVuote())
        }
    }

    @Test
    fun unaSola() {
        Mondo.collegato(app)
        val stato = RegoleViewModel.StatoRegole(caricamento = false, letto = true, regole = listOf(DatiFinti.instagram))
        scatta("07-regole-una-sola", "Regole con una regola sola: nel ⋯ niente \"Elimina\"", pagine = false) {
            regole(stato, DatiFinti.proposteVuote()) { toccaIcona("Altre azioni sulla regola", 0) }
        }
    }

    @Test
    fun soloAltrove() {
        Mondo.collegato(app)
        val stato = RegoleViewModel.StatoRegole(caricamento = false, letto = true, regoleAltrove = 2)
        scatta("07-regole-vuote-qui", "Regole vuote su questo telefono (2 regole sul computer)", pagine = false) {
            regole(stato, DatiFinti.proposteVuote())
        }
    }

    @Test
    fun senzaRete() {
        Mondo.collegato(app)
        val stato = DatiFinti.regoleNormali().copy(errore = true, datiFermiAlle = DatiFinti.adesso() - 3 * DatiFinti.ORA)
        scatta("07-regole-senza-rete", "Regole senza rete: la riga \"Dati non aggiornati\"", pagine = false) {
            regole(stato)
        }
    }

    @Test
    fun foglioTuaProposta() {
        Mondo.collegato(app)
        scatta("07-regole-foglio-tua-proposta", "(0.16) Toccando «In attesa del genitore»: la tua proposta per intero, con «Ritira»", pagine = false) {
            regole(proposte = DatiFinti.proposteVuote()) { tocca("In attesa del genitore") }
        }
    }

    @Test
    fun menuAperto() {
        Mondo.collegato(app)
        scatta("07-regole-menu", "Il ⋯ di una regola: Modifica · Proponi al genitore · Elimina", pagine = false) {
            regole(senzaProposte(), DatiFinti.proposteVuote()) { toccaIcona("Altre azioni sulla regola", 0) }
        }
    }

    @Test
    fun dialogoModificaLimite() {
        Mondo.collegato(app)
        scatta("07-regole-dialogo-modifica-limite", "Dialogo \"Modifica la regola\" su Instagram (limite di tempo)", pagine = false) {
            regole(senzaProposte(), DatiFinti.proposteVuote()) { menu("Modifica") }
        }
    }

    @Test
    fun dialogoNuova() {
        Mondo.collegato(app)
        scatta("07-regole-dialogo-nuova", "Dialogo \"Nuova regola\" dal pulsante \"Nuova regola\" (tre tipi da scegliere)", pagine = false) {
            regole(proposte = DatiFinti.proposteVuote()) { toccaIcona("Nuova regola") }
        }
    }

    @Test
    fun dialogoSceltaApp() {
        Mondo.collegato(app)
        scatta("07-regole-dialogo-scelta-app", "Dialogo \"Su cosa vale il limite?\" aperto dalla modifica di Instagram", pagine = false) {
            regole(senzaProposte(), DatiFinti.proposteVuote()) {
                menu("Modifica")
                toccaNelDialogo("Instagram")
                aspetta("Brawl Stars")
            }
        }
    }

    @Test
    fun dialogoElimina() {
        Mondo.collegato(app)
        scatta("07-regole-dialogo-elimina", "Dialogo \"Eliminare la regola?\" (dal ⋯)", pagine = false) {
            regole(senzaProposte(), DatiFinti.proposteVuote()) { menu("Elimina") }
        }
    }

    @Test
    fun dialogoProponi() {
        Mondo.collegato(app)
        scatta("07-regole-dialogo-proponi", "Dialogo \"Proponi al genitore\" su Instagram (dal ⋯: stesso modulo + perché)") {
            regole(senzaProposte(), DatiFinti.proposteVuote()) { menu("Proponi al genitore") }
        }
    }

    @Test
    fun dialogoProponiEliminazione() {
        Mondo.collegato(app)
        scatta("07-regole-dialogo-proponi-eliminazione", "Dialogo \"Proponi di eliminarla\"", pagine = false) {
            regole(senzaProposte(), DatiFinti.proposteVuote()) {
                menu("Proponi al genitore")
                toccaNelDialogo("Proponi di eliminarla")
            }
        }
    }

    @Test
    fun dialogoBloccoQuattroGiorni() {
        Mondo.collegato(app)
        val cambio = CambioRegola.Modifica(DatiFinti.instagram.id, DatiFinti.parametriLimite("com.instagram.android", 90))
        val stato = DatiFinti.regoleNormali().copy(
            evento = RegoleViewModel.Evento.LockAttivo(secondiRimanenti = 2 * 86_400 + 5 * 3_600, cambio = cambio),
        )
        scatta("07-regole-dialogo-blocco-4-giorni", "Dialogo del blocco dei 4 giorni (allentare): attesa + \"Chiedi al genitore\"", pagine = false) {
            regole(stato)
        }
    }

    // --- 09 Le proposte, ora dentro Regole ----------------------------------------

    @Test
    fun daNotificaDiProposta() {
        Mondo.collegato(app)
        scatta("09-proposte-normale", "Da una notifica di proposta: Regole con \"Da decidere\" in cima (Proposta di Mamma, di Papà)", pagine = false) {
            regole(destinazione = MainActivity.DEST_PROPOSTE)
        }
    }

    @Test
    fun dialogoRitira() {
        Mondo.collegato(app)
        scatta("09-proposte-dialogo-ritira", "Dialogo \"Ritirare la proposta?\" dal ⋯ della regola con la tua proposta", pagine = false) {
            regole(proposte = DatiFinti.proposteVuote()) { menu("Ritira la proposta") }
        }
    }

    // --- 10 Le dichiarazioni: da Regole, e lo Storico --------------------------------

    @Test
    fun dialogoSuccesso() {
        Mondo.collegato(app)
        scatta("10-diario-dialogo-ce-l-ho-fatta", "Dialogo \"Ce l'ho fatta\" dalla card di una regola di vita reale: giorno a chip e nota", pagine = false) {
            regole(proposte = DatiFinti.proposteVuote()) { tocca("Ce l'ho fatta", 0) }
        }
    }

    @Test
    fun storico() {
        Mondo.collegato(app)
        scatta("10-storico", "Lo Storico da Regole: proposte chiuse (con i nomi dei genitori) e tutte le dichiarazioni, una volta sola", pagine = true) {
            regole { tocca("Storico: proposte e dichiarazioni") }
        }
    }

    @Test
    fun storicoDaNotifica() {
        Mondo.collegato(app)
        scatta("10-diario-normale", "Dalla notifica dell'esito di una dichiarazione: lo Storico aperto sulle dichiarazioni", pagine = false) {
            regole(destinazione = MainActivity.DEST_DIARIO)
        }
    }

    @Test
    fun storicoVuoto() {
        Mondo.collegato(app)
        scatta("10-diario-vuoto", "Lo Storico vuoto: nessuna proposta chiusa, nessuna dichiarazione", pagine = false) {
            regole(
                proposte = DatiFinti.proposteVuote(),
                diario = DichiarazioniViewModel.StatoDiario(caricamento = false),
            ) { tocca("Storico: proposte e dichiarazioni") }
        }
    }
}
