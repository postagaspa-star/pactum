package eu.stgm.pactum.figlio.fotografo

import android.content.Intent
import eu.stgm.pactum.figlio.MainActivity
import eu.stgm.pactum.figlio.faccende.ArchivioBlocco
import eu.stgm.pactum.figlio.faccende.ArchivioCodaFoto
import eu.stgm.pactum.figlio.sessione.ArchivioSessioni
import eu.stgm.pactum.figlio.sessione.MemoriaSessioni
import eu.stgm.pactum.figlio.ui.OggiViewModel
import org.junit.Test

/** 05 — La scheda Oggi, nei suoi stati (0.15: una card in cima al massimo, righe di stato, il patto, le regole, il tempo). */
class FotoOggiTest : Fotografo() {

    private fun sessioneInCorso() {
        ArchivioSessioni.modifica(app) { MemoriaSessioni(svolte = listOf(DatiFinti.svoltaStudio())) }
    }

    @Test
    fun normale() {
        Mondo.collegato(app)
        scatta("05-oggi-normale", "Oggi, stato normale: card del patto, 7 regole (una oltre il limite) col \"+\", le prime 3 app, telefono + computer", Variante.SCHEDE, pagine = true) {
            apriPactum(StatiFinti()).comeAperta()
        }
    }

    @Test
    fun primoGiorno() {
        Mondo.collegato(app)
        scatta("05-oggi-primo-giorno", "Oggi, primo giorno: \"Si comincia da oggi\", striscia senza dati, una regola, nessun uso", pagine = false) {
            apriPactum(StatiFinti(oggi = DatiFinti.oggiInizio())).comeAperta()
        }
    }

    @Test
    fun caricamento() {
        Mondo.collegato(app)
        scatta("05-oggi-caricamento", "Oggi alla prima lettura: solo la rotella, niente numeri finti", pagine = false) {
            apriPactum(StatiFinti(oggi = OggiViewModel.StatoOggi())).comeAperta()
        }
    }

    @Test
    fun senzaRete() {
        Mondo.collegato(app)
        val stato = DatiFinti.oggiNormale().copy(datiFermi = true, datiFermiAlle = DatiFinti.adesso() - 26 * DatiFinti.ORA)
        scatta("05-oggi-senza-rete", "Oggi senza rete: la riga \"Dati non aggiornati\"", pagine = false) {
            apriPactum(StatiFinti(oggi = stato)).comeAperta()
        }
    }

    @Test
    fun scollegato() {
        Mondo.collegato(app)
        val stato = DatiFinti.oggiNormale().copy(datiFermi = true, scollegato = true)
        scatta("05-oggi-scollegato", "Oggi con il telefono non più collegato (401): una riga con \"Collega\"", pagine = false) {
            apriPactum(StatiFinti(oggi = stato)).comeAperta()
        }
    }

    @Test
    fun sessioneInCorsoOggi() {
        Mondo.collegato(app)
        sessioneInCorso()
        val stato = DatiFinti.oggiNormale().copy(minutiInSessione = 25)
        scatta("05-oggi-sessione-in-corso", "Oggi con la sessione \"Studio\" in corso: la sua card in cima, \"Termina\"") {
            apriPactum(StatiFinti(oggi = stato)).comeAperta()
        }
    }

    @Test
    fun bloccatoConSessione() {
        Mondo.collegato(app)
        sessioneInCorso()
        ArchivioBlocco.modifica(app) { DatiFinti.bloccoAttivo() }
        scatta("05-oggi-bloccato", "Oggi col telefono bloccato dai lavori di casa e una sessione in corso: il blocco ha la card, la sessione una riga", pagine = false) {
            apriPactum(StatiFinti()).comeAperta()
        }
    }

    @Test
    fun conLavoriDiCasa() {
        Mondo.collegato(app)
        ArchivioBlocco.modifica(app) { DatiFinti.bloccoProgrammato() }
        ArchivioCodaFoto.modifica(app) { DatiFinti.codaFoto() to emptyList() }
        scatta("05-oggi-con-lavori", "Oggi con lavori di casa e il blocco programmato: una riga, e il numero sulla scheda Lavori", pagine = false) {
            apriPactum(StatiFinti()).comeAperta()
        }
    }

    @Test
    fun terminaSessione() {
        Mondo.collegato(app)
        sessioneInCorso()
        scatta("05-oggi-dialogo-termina-sessione", "Oggi, dialogo \"Terminare la sessione?\" (dalla notifica fissa)", pagine = false) {
            apriPactum(StatiFinti(), MainActivity.DEST_TERMINA_SESSIONE).comeAperta()
        }
    }

    /** Pactum già aperto su Sessioni, poi "Termina la sessione" dalla notifica fissa. */
    private fun terminaDallaNotificaSuSessioni(dopo: () -> Unit = {}): Aperta {
        val controller = apriPactum(StatiFinti(), MainActivity.DEST_SESSIONI)
        aspetta("Nuova sessione")
        controller.newIntent(
            Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_DESTINAZIONE, MainActivity.DEST_TERMINA_SESSIONE),
        )
        aspetta("Terminare la sessione?")
        dopo()
        return controller.comeAperta()
    }

    @Test
    fun terminaSessioneDaSessioni() {
        Mondo.collegato(app)
        sessioneInCorso()
        scatta("05-oggi-dialogo-termina-da-sessioni", "Pactum aperto su Sessioni, \"Termina la sessione\" dalla notifica: Oggi con la conferma aperta") {
            terminaDallaNotificaSuSessioni()
        }
    }

    @Test
    fun sessioniDopoTerminaAnnullato() {
        Mondo.collegato(app)
        sessioneInCorso()
        scatta("08-sessioni-dopo-termina-annullato", "Dopo \"Annulla\" sulla conferma in Oggi, di nuovo Sessioni: la conferma non ricompare") {
            terminaDallaNotificaSuSessioni {
                toccaNelDialogo("Annulla")
                tocca("Sessioni")
                aspetta("Nuova sessione")
            }
        }
    }

    @Test
    fun mostraSopraMancante() {
        Mondo.collegato(app)
        Mondo.permessi(app, sopra = false)
        scatta("05-oggi-manca-mostra-sopra", "Oggi senza \"Mostra sopra le altre app\": la riga \"Da sistemare\" con \"Risolvi\"", pagine = false) {
            apriPactum(StatiFinti()).comeAperta()
        }
    }

    @Test
    fun foglioBonus() {
        Mondo.collegato(app)
        scatta("05-oggi-foglio-bonus", "Oggi, il \"+\" di Instagram: il foglio del bonus (+5 / +15 / +30 e quanto ne resta)", pagine = false) {
            apriPactum(StatiFinti()).also { toccaIcona("Più tempo su Instagram") }.comeAperta()
        }
    }

    @Test
    fun bonusInPartenza() {
        Mondo.collegato(app)
        val stato = DatiFinti.oggiNormale().copy(bonusInSospeso = DatiFinti.bonusInSospeso(false), finestraBonus = true)
        scatta("05-oggi-bonus-snackbar", "Oggi, appena dato +15 min su Instagram: snackbar \"Aggiungi perché\" e \"in partenza\"", pagine = false) {
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
    fun segna() {
        Mondo.collegato(app)
        scatta("05-oggi-dialogo-segna", "Oggi, \"Segna\" su una regola di vita reale: la dichiarazione (com'è andata, giorno, nota)", pagine = false) {
            apriPactum(StatiFinti()).also { tocca("Segna", 0) }.comeAperta()
        }
    }

    @Test
    fun serverVecchio() {
        Mondo.collegato(app)
        val stato = DatiFinti.oggiNormale().copy(tempi = DatiFinti.tempiServerVecchio())
        scatta("05-oggi-server-vecchio", "(0.16) Oggi con un server di prima della v3.8: il tempo solo di oggi, come prima, e «Vedi tutto»", pagine = true) {
            apriPactum(StatiFinti(oggi = stato)).comeAperta()
        }
    }
}
