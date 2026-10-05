package eu.stgm.pactum.figlio.fotografo

import org.junit.Test

/**
 * 19 — (0.16) La pagina Tempo, da «Vedi tutto» in Oggi: la stessa del
 * genitore (giorni, totale, anello delle categorie, 8 giorni coi totali e le
 * medie, tutte le app), per questo telefono e per il computer.
 */
class FotoTempoTest : Fotografo() {

    private fun giorno(indietro: Long): String = giornoNumerico(DatiFinti.oggi().minusDays(indietro).toString())

    private fun giornoNumerico(iso: String) = eu.stgm.pactum.figlio.ui.giornoNumerico(iso)

    @Test
    fun oggi() {
        Mondo.collegato(app)
        scatta("19-tempo-oggi", "Tempo, oggi su questo telefono: totale, anello delle categorie, 8 giorni coi totali di 7 e 30 giorni, tutte le app", pagine = true) {
            apriPactum(StatiFinti()).also { tocca("Vedi tutto") }.comeAperta()
        }
    }

    @Test
    fun ieri() {
        Mondo.collegato(app)
        scatta("19-tempo-ieri", "Tempo, scelto il giorno prima nella fila dei giorni: la sua barra accesa") {
            apriPactum(StatiFinti()).also {
                tocca("Vedi tutto")
                tocca(giorno(1))
            }.comeAperta()
        }
    }

    @Test
    fun senzaDati() {
        Mondo.collegato(app)
        scatta("19-tempo-giorno-senza-dati", "Tempo, un giorno senza dati: «Nessun dato ricevuto», non zero") {
            apriPactum(StatiFinti()).also {
                tocca("Vedi tutto")
                tocca(giorno(4))
            }.comeAperta()
        }
    }

    @Test
    fun computer() {
        Mondo.collegato(app)
        scatta("19-tempo-computer", "Tempo, scelto il computer: i suoi giorni, i suoi totali e i programmi", pagine = true) {
            apriPactum(StatiFinti()).also {
                tocca("Vedi tutto")
                tocca("PC di Luca")
            }.comeAperta()
        }
    }

    @Test
    fun serverVecchio() {
        Mondo.collegato(app)
        val stato = DatiFinti.oggiNormale().copy(tempi = DatiFinti.tempiServerVecchio())
        scatta("19-tempo-server-vecchio", "Tempo con un server di prima della v3.8: solo oggi, letto sul telefono, senza totali", pagine = true) {
            apriPactum(StatiFinti(oggi = stato)).also { tocca("Vedi tutto") }.comeAperta()
        }
    }
}
