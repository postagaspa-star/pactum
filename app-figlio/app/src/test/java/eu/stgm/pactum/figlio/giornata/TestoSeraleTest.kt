package eu.stgm.pactum.figlio.giornata

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * La chiusura della sera: una frase sola, e deve essere vera. Le parole sono
 * le stesse di strings.xml, così il test legge quello che legge il ragazzo.
 */
class TestoSeraleTest {

    private val parole = ParoleSerale(
        dentro = "Oggi dentro tutte le tue regole.",
        giornoInParole = "%1\$s giorno.",
        ordinali = listOf(
            "Primo", "Secondo", "Terzo", "Quarto", "Quinto",
            "Sesto", "Settimo", "Ottavo", "Nono", "Decimo",
        ),
        giornoInCifre = "%1\$d° giorno.",
        oltre = "Oggi %1\$s oltre su %2\$s.",
        fascia = "Oggi %1\$s di telefono nella fascia che ti sei chiuso.",
        fuori = "Oggi non tutte le regole hanno tenuto.",
        unAltraRegola = "E un'altra regola fuori.",
        altreRegole = "E altre %1\$d regole fuori.",
        domani = "Domani riparte.",
        durata = { m -> if (m < 60) "$m min" else if (m % 60 == 0) "${m / 60} h" else "${m / 60} h ${m % 60} min" },
    )

    private fun frase(fuori: List<FuoriOggi>, rosso: Boolean = false, serie: Int? = null) =
        TestoSerale.testo(TestoSerale.chiusura(fuori, rosso, serie), parole)

    @Test
    fun `giornata dentro col numero del giorno in parole`() {
        assertEquals("Oggi dentro tutte le tue regole. Nono giorno.", frase(emptyList(), serie = 9))
        assertEquals("Oggi dentro tutte le tue regole. Primo giorno.", frase(emptyList(), serie = 1))
    }

    @Test
    fun `oltre il decimo giorno si passa alle cifre`() {
        assertEquals("Oggi dentro tutte le tue regole. 23° giorno.", frase(emptyList(), serie = 23))
    }

    @Test
    fun `senza serie conosciuta non si inventa il numero`() {
        assertEquals("Oggi dentro tutte le tue regole.", frase(emptyList(), serie = null))
    }

    @Test
    fun `un limite superato dice di quanto e dove`() {
        val fuori = listOf(FuoriOggi(TipoFuori.LIMITE, "TikTok", 40))
        assertEquals("Oggi 40 min oltre su TikTok. Domani riparte.", frase(fuori, serie = 9))
    }

    @Test
    fun `con piu' limiti si racconta il piu' grande e si contano gli altri`() {
        val fuori = listOf(
            FuoriOggi(TipoFuori.LIMITE, "Instagram", 10),
            FuoriOggi(TipoFuori.LIMITE, "TikTok", 70),
            FuoriOggi(TipoFuori.FASCIA, null, 5),
        )
        assertEquals(
            "Oggi 1 h 10 min oltre su TikTok. E altre 2 regole fuori. Domani riparte.",
            frase(fuori),
        )
    }

    @Test
    fun `la fascia usata si dice senza nomi`() {
        val fuori = listOf(FuoriOggi(TipoFuori.FASCIA, null, 25))
        assertEquals(
            "Oggi 25 min di telefono nella fascia che ti sei chiuso. Domani riparte.",
            frase(fuori),
        )
    }

    @Test
    fun `un'altra regola fuori al singolare`() {
        val fuori = listOf(
            FuoriOggi(TipoFuori.LIMITE, "TikTok", 40),
            FuoriOggi(TipoFuori.LIMITE, "YouTube", 5),
        )
        assertEquals(
            "Oggi 40 min oltre su TikTok. E un'altra regola fuori. Domani riparte.",
            frase(fuori),
        )
    }

    @Test
    fun `il rosso del server senza misure sul telefono non diventa una giornata dentro`() {
        // Es. una dichiarazione di vita reale andata male: il telefono non lo sa.
        assertEquals(
            "Oggi non tutte le regole hanno tenuto. Domani riparte.",
            frase(emptyList(), rosso = true, serie = 9),
        )
    }

    @Test
    fun `una serie a zero non si scrive`() {
        assertEquals(Chiusura.Dentro(null), TestoSerale.chiusura(emptyList(), false, 0))
    }

    // --- quando guardare ---

    private val roma = ZoneId.of("Europe/Rome")

    @Test
    fun `prima dell'ora il prossimo controllo e' stasera`() {
        val adesso = ZonedDateTime.of(2026, 9, 19, 18, 0, 0, 0, roma)
        assertEquals(
            ZonedDateTime.of(2026, 9, 19, 21, 30, 0, 0, roma),
            TestoSerale.prossimoControllo(adesso, LocalTime.of(21, 30)),
        )
    }

    @Test
    fun `dopo l'ora il prossimo controllo e' domani sera`() {
        val adesso = ZonedDateTime.of(2026, 9, 19, 21, 30, 0, 0, roma)
        assertEquals(
            ZonedDateTime.of(2026, 9, 20, 21, 30, 0, 0, roma),
            TestoSerale.prossimoControllo(adesso, LocalTime.of(21, 30)),
        )
    }

    @Test
    fun `il cambio dell'ora legale non sposta l'appuntamento`() {
        // Notte tra 24 e 25 ottobre 2026: si torna all'ora solare.
        val adesso = ZonedDateTime.of(2026, 10, 24, 22, 0, 0, 0, roma)
        val prossimo = TestoSerale.prossimoControllo(adesso, LocalTime.of(21, 30))
        assertEquals(LocalTime.of(21, 30), prossimo.toLocalTime())
        assertEquals(25, prossimo.dayOfMonth)
    }
}
