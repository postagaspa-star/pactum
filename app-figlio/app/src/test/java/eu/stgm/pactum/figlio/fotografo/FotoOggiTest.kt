package eu.stgm.pactum.figlio.fotografo

import eu.stgm.pactum.figlio.MainActivity
import eu.stgm.pactum.figlio.faccende.ArchivioBlocco
import eu.stgm.pactum.figlio.faccende.ArchivioCodaFoto
import eu.stgm.pactum.figlio.sessione.ArchivioSessioni
import eu.stgm.pactum.figlio.sessione.MemoriaSessioni
import org.junit.Test

/** 05 — La scheda Oggi, nei suoi stati. */
class FotoOggiTest : Fotografo() {

    private fun sessioneInCorso() {
        ArchivioSessioni.modifica(app) { MemoriaSessioni(svolte = listOf(DatiFinti.svoltaStudio())) }
    }

    @Test
    fun normale() {
        Mondo.collegato(app)
        scatta("05-oggi-normale", "Oggi, stato normale: serie, striscia, 7 regole (una oltre il limite), bonus, tempo per app, telefono + computer") {
            apriPactum(StatiFinti()).comeAperta()
        }
    }

    @Test
    fun primoGiorno() {
        Mondo.collegato(app)
        scatta("05-oggi-primo-giorno", "Oggi, primo giorno (stato vuoto): niente serie, striscia senza dati, una regola, nessun uso") {
            apriPactum(StatiFinti(oggi = DatiFinti.oggiInizio())).comeAperta()
        }
    }

    @Test
    fun caricamento() {
        Mondo.collegato(app)
        scatta("05-oggi-caricamento", "Oggi mentre legge la prima volta (rotella)", pagine = false) {
            apriPactum(StatiFinti(oggi = eu.stgm.pactum.figlio.ui.OggiViewModel.StatoOggi())).comeAperta()
        }
    }

    @Test
    fun senzaRete() {
        Mondo.collegato(app)
        val stato = DatiFinti.oggiNormale().copy(datiFermi = true, datiFermiAlle = DatiFinti.adesso() - 26 * DatiFinti.ORA)
        scatta("05-oggi-senza-rete", "Oggi senza rete: l'ultima copia con \"Dati non aggiornati\"") {
            apriPactum(StatiFinti(oggi = stato)).comeAperta()
        }
    }

    @Test
    fun scollegato() {
        Mondo.collegato(app)
        val stato = DatiFinti.oggiNormale().copy(datiFermi = true, scollegato = true)
        scatta("05-oggi-scollegato", "Oggi con il telefono scollegato dal patto (401)", pagine = false) {
            apriPactum(StatiFinti(oggi = stato)).comeAperta()
        }
    }

    @Test
    fun sessioneInCorsoOggi() {
        Mondo.collegato(app)
        sessioneInCorso()
        val stato = DatiFinti.oggiNormale().copy(minutiInSessione = 25)
        scatta("05-oggi-sessione-in-corso", "Oggi con la sessione \"Studio\" in corso (scheda in cima, minuti in sessione)") {
            apriPactum(StatiFinti(oggi = stato)).comeAperta()
        }
    }

    @Test
    fun avvioIncerto() {
        Mondo.collegato(app)
        ArchivioSessioni.modifica(app) { MemoriaSessioni(avvioIncerto = DatiFinti.avvioIncerto()) }
        scatta("05-oggi-avvio-incerto", "Oggi con un \"Inizia\" rimasto senza risposta (riga neutra in cima)", pagine = false) {
            apriPactum(StatiFinti()).comeAperta()
        }
    }

    @Test
    fun terminaSessione() {
        Mondo.collegato(app)
        sessioneInCorso()
        scatta("05-oggi-dialogo-termina-sessione", "Oggi, dialogo \"Termina la sessione?\" (da notifica fissa)", pagine = false) {
            apriPactum(StatiFinti(), MainActivity.DEST_TERMINA_SESSIONE).comeAperta()
        }
    }

    @Test
    fun mostraSopraMancante() {
        Mondo.collegato(app)
        Mondo.permessi(app, sopra = false)
        scatta("05-oggi-manca-mostra-sopra", "Oggi senza \"Mostra sopra le altre app\": scheda che chiede il permesso") {
            apriPactum(StatiFinti()).comeAperta()
        }
    }

    @Test
    fun aiutoRestrizioniAperto() {
        Mondo.collegato(app)
        Mondo.permessi(app, sopra = false)
        scatta("05-oggi-manca-mostra-sopra-aiuto", "Oggi, scheda del permesso con \"Se Android ti blocca…\" aperto") {
            apriPactum(StatiFinti()).also { tocca("Se Android ti blocca…") }.comeAperta()
        }
    }

    @Test
    fun bonusInPartenza() {
        Mondo.collegato(app)
        val stato = DatiFinti.oggiNormale().copy(bonusInSospeso = DatiFinti.bonusInSospeso(false), finestraBonus = true)
        scatta("05-oggi-bonus-snackbar", "Oggi, appena toccato +15 min su Instagram: snackbar \"Aggiungi perché\" e riga \"in partenza\"", pagine = false) {
            apriPactum(StatiFinti(oggi = stato)).comeAperta()
        }
    }

    @Test
    fun dialogoPerche() {
        Mondo.collegato(app)
        val stato = DatiFinti.oggiNormale().copy(bonusInSospeso = DatiFinti.bonusInSospeso(true))
        scatta("05-oggi-dialogo-perche", "Oggi, dialogo del perché del bonus", pagine = false) {
            apriPactum(StatiFinti(oggi = stato)).comeAperta()
        }
    }

    @Test
    fun conLavoriDiCasa() {
        Mondo.collegato(app)
        ArchivioBlocco.modifica(app) { DatiFinti.bloccoProgrammato() }
        ArchivioCodaFoto.modifica(app) { DatiFinti.codaFoto() to emptyList() }
        scatta("05-oggi-con-lavori", "Oggi quando ci sono lavori di casa: sei schede in basso, numero sulla scheda Lavori", pagine = false) {
            apriPactum(StatiFinti()).comeAperta()
        }
    }
}
