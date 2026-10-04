package eu.stgm.pactum.figlio.fotografo

import eu.stgm.pactum.figlio.MainActivity
import eu.stgm.pactum.figlio.dati.CambioRegola
import eu.stgm.pactum.figlio.dati.EsitoProposta
import eu.stgm.pactum.figlio.ui.RegoleViewModel
import org.junit.Test

/** 07 — La scheda Le mie regole e i suoi dialoghi. */
class FotoRegoleTest : Fotografo() {

    private fun regole(stato: RegoleViewModel.StatoRegole, dopo: (StatiFinti) -> Unit = {}): Aperta {
        val stati = StatiFinti(regole = stato)
        return apriPactum(stati, MainActivity.DEST_REGOLE).also { dopo(stati) }.comeAperta()
    }

    /** Le regole senza proposte in attesa: su ogni scheda c'è "Proponi al genitore". */
    private fun senzaProposte() = DatiFinti.regoleNormali().copy(proposteInAttesa = emptyMap())

    @Test
    fun normale() {
        Mondo.collegato(app)
        scatta("07-regole-normale", "Le mie regole: 7 regole (limiti, fasce, vita reale), una concordata, due con proposta in attesa, 2 sul computer") {
            regole(DatiFinti.regoleNormali())
        }
    }

    @Test
    fun unaSola() {
        Mondo.collegato(app)
        val stato = RegoleViewModel.StatoRegole(caricamento = false, letto = true, regole = listOf(DatiFinti.instagram))
        scatta("07-regole-una-sola", "Le mie regole con una regola sola (\"è l'unica: non si può eliminare\")", pagine = false) {
            regole(stato)
        }
    }

    @Test
    fun soloAltrove() {
        Mondo.collegato(app)
        val stato = RegoleViewModel.StatoRegole(caricamento = false, letto = true, regoleAltrove = 2)
        scatta("07-regole-vuote-qui", "Le mie regole vuote su questo telefono (2 regole sul computer)", pagine = false) {
            regole(stato)
        }
    }

    @Test
    fun senzaRete() {
        Mondo.collegato(app)
        val stato = DatiFinti.regoleNormali().copy(errore = true, datiFermiAlle = DatiFinti.adesso() - 3 * DatiFinti.ORA)
        scatta("07-regole-senza-rete", "Le mie regole senza rete: \"Dati non aggiornati\"") {
            regole(stato)
        }
    }

    @Test
    fun caricamento() {
        Mondo.collegato(app)
        val stato = RegoleViewModel.StatoRegole(caricamento = true, letto = true, regoleAltrove = 2)
        scatta("07-regole-caricamento", "Le mie regole mentre le rilegge (rotella)", pagine = false) {
            regole(stato)
        }
    }

    @Test
    fun dialogoModificaLimite() {
        Mondo.collegato(app)
        scatta("07-regole-dialogo-modifica-limite", "Dialogo \"Modifica la regola\" su Instagram (limite di tempo)") {
            regole(DatiFinti.regoleNormali()) { tocca("Modifica", 0) }
        }
    }

    @Test
    fun dialogoModificaFascia() {
        Mondo.collegato(app)
        val stato = DatiFinti.regoleNormali().copy(regole = listOf(DatiFinti.notte, DatiFinti.instagram))
        scatta("07-regole-dialogo-modifica-fascia", "Dialogo \"Modifica la regola\" su una fascia oraria (7 giorni a chip)") {
            regole(stato) { tocca("Modifica", 0) }
        }
    }

    @Test
    fun dialogoNuova() {
        Mondo.collegato(app)
        scatta("07-regole-dialogo-nuova", "Dialogo \"Nuova regola\" dal pulsante + (tre tipi da scegliere)") {
            regole(DatiFinti.regoleNormali()) { toccaIcona("Nuova regola") }
        }
    }

    @Test
    fun dialogoSceltaApp() {
        Mondo.collegato(app)
        scatta("07-regole-dialogo-scelta-app", "Dialogo \"Su cosa vale il limite?\" aperto dalla modifica di Instagram") {
            regole(DatiFinti.regoleNormali()) {
                tocca("Modifica", 0)
                toccaNelDialogo("Instagram")
                aspetta("Brawl Stars")
            }
        }
    }

    @Test
    fun dialogoMinutiTroppi() {
        Mondo.collegato(app)
        val stato = DatiFinti.regoleNormali().copy(
            regole = listOf(DatiFinti.limite(1, "com.instagram.android", 2000)) + DatiFinti.regoleTelefono.drop(1),
        )
        scatta("07-regole-dialogo-minuti-troppi", "Dialogo di modifica con troppi minuti: il campo in rosso \"al massimo 24 ore\"", pagine = false) {
            regole(stato) { tocca("Modifica", 0) }
        }
    }

    @Test
    fun dialogoElimina() {
        Mondo.collegato(app)
        scatta("07-regole-dialogo-elimina", "Dialogo \"Eliminare la regola?\"", pagine = false) {
            regole(DatiFinti.regoleNormali()) { tocca("Elimina", 0) }
        }
    }

    @Test
    fun dialogoProponi() {
        Mondo.collegato(app)
        scatta("07-regole-dialogo-proponi", "Dialogo \"Proponi al genitore\" su Instagram (stesso modulo + perché)") {
            regole(senzaProposte()) { tocca("Proponi al genitore", 0) }
        }
    }

    @Test
    fun dialogoProponiEliminazione() {
        Mondo.collegato(app)
        scatta("07-regole-dialogo-proponi-eliminazione", "Dialogo \"Proponi di eliminarla\"") {
            regole(senzaProposte()) {
                tocca("Proponi al genitore", 0)
                toccaNelDialogo("Proponi di eliminarla")
            }
        }
    }

    @Test
    fun dialogoProponiSenzaRete() {
        Mondo.collegato(app)
        scatta("07-regole-dialogo-proponi-senza-rete", "Dialogo \"Proponi al genitore\" dopo un invio senza rete (riga neutra nel dialogo)") {
            regole(senzaProposte()) { stati ->
                tocca("Proponi al genitore", 0)
                stati.flussi.regole.value = stati.flussi.regole.value.copy(esitoProposta = EsitoProposta.SenzaRete)
                calma()
            }
        }
    }

    @Test
    fun dialogoProponiGiaInAttesa() {
        Mondo.collegato(app)
        scatta("07-regole-dialogo-proponi-gia-in-attesa", "Dialogo \"Proponi al genitore\": c'è già una proposta del genitore (\"Apri Proposte\")") {
            regole(senzaProposte()) { stati ->
                tocca("Proponi al genitore", 0)
                stati.flussi.regole.value = stati.flussi.regole.value.copy(esitoProposta = EsitoProposta.GiaPendente)
                calma()
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
        scatta("07-regole-dialogo-blocco-4-giorni", "Dialogo del blocco dei 4 giorni (allentare): attesa + \"Chiedi al genitore\"") {
            regole(stato)
        }
    }

    @Test
    fun dialogoBloccoEliminazione() {
        Mondo.collegato(app)
        val stato = DatiFinti.regoleNormali().copy(
            evento = RegoleViewModel.Evento.LockAttivo(secondiRimanenti = 3 * 3_600 + 20 * 60, cambio = CambioRegola.Eliminazione(DatiFinti.tiktok.id)),
        )
        scatta("07-regole-dialogo-blocco-eliminazione", "Dialogo del blocco dei 4 giorni per un'eliminazione") {
            regole(stato)
        }
    }
}
