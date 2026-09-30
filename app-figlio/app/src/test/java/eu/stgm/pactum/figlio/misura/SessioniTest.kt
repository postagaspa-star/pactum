package eu.stgm.pactum.figlio.misura

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * (0.9) Le regole delle sessioni in primo piano, evento per evento: da qui
 * escono i minuti per app, il totale e l'uso dentro le fasce.
 */
class SessioniTest {

    private val min = 60_000L
    private val inizio = 0L
    private val fine = 600 * min

    private fun calcolo(attive: Map<String, Attiva> = emptyMap()) = CalcoloSessioni(inizio, fine, attive)

    @Test
    fun `una app aperta e chiusa fa una sessione`() {
        val c = calcolo()
        c.evento(Sessioni.RIPRESA, "tiktok", "Main", 10 * min)
        c.evento(Sessioni.PAUSA, "tiktok", "Main", 40 * min)
        assertEquals(listOf(Sessione("tiktok", 10 * min, 40 * min)), c.sessioni())
    }

    @Test
    fun `due activity della stessa app sono una sessione sola`() {
        val c = calcolo()
        c.evento(Sessioni.RIPRESA, "ig", "Feed", 10 * min)
        c.evento(Sessioni.RIPRESA, "ig", "Storie", 12 * min)
        c.evento(Sessioni.PAUSA, "ig", "Feed", 13 * min)
        c.evento(Sessioni.STOP, "ig", "Storie", 20 * min)
        assertEquals(listOf(Sessione("ig", 10 * min, 20 * min)), c.sessioni())
    }

    @Test
    fun `una sessione ancora aperta conta fino alla fine`() {
        val c = calcolo()
        c.evento(Sessioni.RIPRESA, "tiktok", "Main", 590 * min)
        assertEquals(listOf(Sessione("tiktok", 590 * min, fine)), c.sessioni())
    }

    @Test
    fun `una app gia' aperta al confine conta dall'inizio`() {
        val c = calcolo(attive = mapOf("tiktok" to Attiva(setOf("Main"), dal = inizio - 5 * min)))
        c.evento(Sessioni.PAUSA, "tiktok", "Main", 5 * min)
        assertEquals(listOf(Sessione("tiktok", inizio, 5 * min)), c.sessioni())
    }

    @Test
    fun `una PAUSED o STOPPED senza una RESUMED prima non e' primo piano`() {
        val c = calcolo()
        c.evento(Sessioni.PAUSA, "yt", "Player", 7 * min)
        c.evento(Sessioni.STOP, "ig", "Feed", 9 * min)
        c.evento(Sessioni.ACCENSIONE, null, null, 100 * min)
        c.evento(Sessioni.PAUSA, "maps", "Map", 104 * min)
        assertEquals(emptyList<Sessione>(), c.sessioni())
    }

    @Test
    fun `lo spegnimento chiude le sessioni, l'accensione non conta il buio`() {
        val c = calcolo()
        c.evento(Sessioni.RIPRESA, "tiktok", "Main", 10 * min)
        c.evento(Sessioni.SPEGNIMENTO, null, null, 20 * min)
        c.evento(Sessioni.RIPRESA, "ig", "Feed", 30 * min)
        // Spento senza SHUTDOWN (batteria staccata): all'accensione non si conta niente.
        c.evento(Sessioni.ACCENSIONE, null, null, 50 * min)
        c.evento(Sessioni.PAUSA, "ig", "Feed", 55 * min)
        assertEquals(listOf(Sessione("tiktok", 10 * min, 20 * min)), c.sessioni())
    }

    @Test
    fun `una chiusura vera subito dopo lo schermo spento conta fino a lei`() {
        // L'ordine normale di Android: lo schermo si spegne, l'app va in pausa un attimo dopo.
        val c = calcolo()
        c.evento(Sessioni.RIPRESA, "tiktok", "Main", 10 * min)
        c.evento(Sessioni.SCHERMO_SPENTO, "android", null, 30 * min)
        c.evento(Sessioni.PAUSA, "tiktok", "Main", 30 * min + 300)
        assertEquals(listOf(Sessione("tiktok", 10 * min, 30 * min + 300)), c.sessioni())
    }

    @Test
    fun `senza chiusura la sessione finisce al primo spegnimento dello schermo o blocco`() {
        val c = calcolo()
        c.evento(Sessioni.RIPRESA, "yt", "Player", 10 * min)
        c.evento(Sessioni.BLOCCO, "android", null, 25 * min)
        c.evento(Sessioni.SCHERMO_SPENTO, "android", null, 26 * min)
        assertEquals(listOf(Sessione("yt", 10 * min, 25 * min)), c.sessioni())
    }

    @Test
    fun `lo spegnimento del telefono non allunga una sessione gia' passata da uno schermo spento`() {
        val c = calcolo()
        c.evento(Sessioni.RIPRESA, "yt", "Player", 10 * min)
        c.evento(Sessioni.SCHERMO_SPENTO, "android", null, 15 * min)
        c.evento(Sessioni.SPEGNIMENTO, null, null, 90 * min)
        assertEquals(listOf(Sessione("yt", 10 * min, 15 * min)), c.sessioni())
    }

    @Test
    fun `l'innesco sa chi era aperto al confine e da quando, ma non dopo uno schermo spento`() {
        val innesco = InnescoSessioni()
        innesco.evento(Sessioni.RIPRESA, "tiktok", "Main", 100)
        innesco.evento(Sessioni.RIPRESA, "ig", "Feed", 200)
        innesco.evento(Sessioni.PAUSA, "ig", "Feed", 300)
        assertEquals(mapOf("tiktok" to Attiva(setOf("Main"), 100)), innesco.attive())
        innesco.evento(Sessioni.SCHERMO_SPENTO, "android", null, 400)
        assertEquals(emptyMap<String, Attiva>(), innesco.attive())
        innesco.evento(Sessioni.RIPRESA, "yt", "Player", 500)
        innesco.evento(Sessioni.SPEGNIMENTO, null, null, 600)
        assertEquals(emptyMap<String, Attiva>(), innesco.attive())
    }

    @Test
    fun `per app si somma e si ordina dalla piu' usata`() {
        val sessioni = listOf(
            Sessione("ig", 0, 5 * min),
            Sessione("tiktok", 10 * min, 30 * min),
            Sessione("ig", 40 * min, 50 * min),
        )
        assertEquals(
            listOf(UsoApp("tiktok", 20 * min), UsoApp("ig", 15 * min)),
            Sessioni.perApp(sessioni),
        )
    }

    @Test
    fun `nelle fasce conta solo il pezzo dentro, e solo le app del totale`() {
        val lettura = LetturaGiorno(
            giorno = LocalDate.of(2026, 9, 30),
            fine = fine,
            sessioni = listOf(
                Sessione("tiktok", 100 * min, 130 * min),
                Sessione("launcher", 110 * min, 125 * min),
                Sessione("ig", 200 * min, 210 * min),
            ),
        )
        // Fascia da 120 a 205: 10 min di TikTok e 5 di Instagram; la Home non conta.
        val conta = { pacchetto: String -> pacchetto != "launcher" }
        assertEquals(15 * min, lettura.millisNellIntervallo(120 * min, 205 * min, conta))
    }
}
