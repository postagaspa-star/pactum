package eu.stgm.pactum.figlio.fotografo

import eu.stgm.pactum.figlio.MainActivity
import eu.stgm.pactum.figlio.bilancio.Bilancio
import eu.stgm.pactum.figlio.bilancio.Diario
import eu.stgm.pactum.figlio.bilancio.EsitiPromessa
import eu.stgm.pactum.figlio.bilancio.Promessa
import eu.stgm.pactum.figlio.bilancio.RegolaDiUnGiorno
import eu.stgm.pactum.figlio.bilancio.Voti
import eu.stgm.pactum.figlio.ui.BilancioInOggi
import eu.stgm.pactum.figlio.ui.BilancioViewModel
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 25 — (0.24) Il bilancio della sera: la pagina nei suoi stati, e la sua card in Oggi.
 * Lo stato della pagina viene dalla stessa funzione del ViewModel (statoDa), su un
 * diario finto, alle 21:45 di oggi. Come in «Cosa vedono i tuoi genitori», le card in
 * una pagina che scorre restano trasparenti nella prima foto (l'entrata a cascata non
 * parte sotto Robolectric): si vedono dalla seconda pagina in poi.
 */
class FotoBilancioTest : Fotografo() {

    private val oggi = LocalDate.now()
    private val stasera = LocalDateTime.of(oggi, LocalTime.of(21, 45))
    private fun giorno(indietro: Long) = oggi.minusDays(indietro).toString()

    /** Una promessa di ieri senza risposta, una misurabile di oggi già fuori, una che non riesce da tre volte. */
    private fun diarioConPromesse() = Diario(
        bilanci = listOf(
            Bilancio(giorno(1), Voti.COSI_COSI, "Troppi video la sera", "social"),
            Bilancio(giorno(2), Voti.MALE, "Ho aperto TikTok appena tornato da scuola", "ieri"),
            Bilancio(giorno(3), Voti.BENE),
        ),
        promesse = listOf(
            Promessa("ieri", "40 minuti di mate prima di prendere il telefono", giorno(1), "15:30", nataIl = giorno(2), rimandi = 1),
            Promessa("social", "Social: al massimo 1 h al giorno", giorno(0), nataIl = giorno(1), regolaId = 41),
            Promessa("a", "Leggere prima di dormire", giorno(6), "22:00", nataIl = giorno(7), esito = EsitiPromessa.NON_FATTA),
            Promessa("b", "Leggere prima di dormire", giorno(5), "22:00", nataIl = giorno(6), esito = EsitiPromessa.NON_FATTA),
            Promessa("c", "Leggere prima di dormire", giorno(4), "22:00", nataIl = giorno(5), esito = EsitiPromessa.NON_FATTA),
        ),
    )

    private val regole = listOf(RegolaDiUnGiorno(41, giorno(0), mapOf(giorno(0) to "rosso")))

    private fun stati(diario: Diario) = StatiFinti(bilancio = BilancioViewModel.statoDa(diario, regole, true, stasera))

    @Test
    fun conPromesse() {
        Mondo.collegato(app)
        scatta("25-bilancio-promesse", "Il bilancio: una promessa che aspetta (Fatto · Non fatto · Rimando), una misurata dal telefono, una da cambiare, gli ultimi giorni", pagine = true) {
            apriPactum(stati(diarioConPromesse()), MainActivity.DEST_BILANCIO).comeAperta()
        }
    }

    @Test
    fun male() {
        Mondo.collegato(app)
        scatta("25-bilancio-male", "«Male»: cosa non ha funzionato, la promessa, domani, l'ora", pagine = true) {
            apriPactum(stati(Diario()), MainActivity.DEST_BILANCIO).also {
                tocca("Male")
            }.comeAperta()
        }
    }

    @Test
    fun controllaIlTelefono() {
        Mondo.collegato(app)
        scatta("25-bilancio-telefono", "«La controlla il telefono»: domani una regola di un giorno (al massimo 1 h di Social)", pagine = true) {
            apriPactum(stati(Diario()), MainActivity.DEST_BILANCIO).also {
                tocca("Così così")
                tocca("La controlla il telefono")
            }.comeAperta()
        }
    }

    @Test
    fun fatto() {
        Mondo.collegato(app)
        val diario = Diario(
            bilanci = listOf(Bilancio(giorno(0), Voti.COSI_COSI, "Troppo telefono dopo cena", "domani")),
            promesse = listOf(Promessa("domani", "Telefono in cucina alle 21:00", oggi.plusDays(1).toString(), "21:00", nataIl = giorno(0))),
        )
        scatta("25-bilancio-fatto", "Il bilancio di stasera già fatto, con la promessa di domani e «Cambia»") {
            apriPactum(stati(diario), MainActivity.DEST_BILANCIO).comeAperta()
        }
    }

    @Test
    fun cardInOggi() {
        Mondo.collegato(app)
        val promessa = Promessa("p", "40 minuti di mate prima di prendere il telefono", giorno(0), "15:30", nataIl = giorno(1))
        val stato = DatiFinti.oggiNormale().copy(bilancio = BilancioInOggi(daFare = true, daDire = 1, diOggi = promessa))
        scatta("25-oggi-card-bilancio", "Oggi con la card del bilancio della sera (da fare, una promessa che aspetta, la promessa di oggi)", pagine = true) {
            apriPactum(StatiFinti(oggi = stato)).comeAperta()
        }
    }
}
