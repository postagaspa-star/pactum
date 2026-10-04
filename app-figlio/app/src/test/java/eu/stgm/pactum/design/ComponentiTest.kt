package eu.stgm.pactum.design

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La logica pura dei componenti comuni (0.15): quando i pulsanti vanno in
 * colonna, quanto è grande il numero del giorno, quanto si rimpicciolisce
 * un'etichetta, il contrasto. Se sbagliano, si vedono pulsanti tagliati,
 * numeri fuori dal quadretto o testi illeggibili. JUnit puro, niente Android.
 */
class ComponentiTest {

    // --- serveColonna (FilaPulsanti) ---

    @Test
    fun `i pulsanti che ci stanno restano in fila`() {
        // 120 + 8 + 100 = 228 in 300
        assertFalse(serveColonna(listOf(120, 100), spazio = 8, disponibile = 300))
    }

    @Test
    fun `i pulsanti che non ci stanno vanno in colonna`() {
        // 160 + 8 + 150 = 318 in 300
        assertTrue(serveColonna(listOf(160, 150), spazio = 8, disponibile = 300))
    }

    @Test
    fun `al pixel giusto ci stanno, un pixel in piu' no`() {
        assertFalse(serveColonna(listOf(146, 146), spazio = 8, disponibile = 300))
        assertTrue(serveColonna(listOf(146, 147), spazio = 8, disponibile = 300))
    }

    @Test
    fun `lo spazio tra i pulsanti conta`() {
        // 3 pulsanti da 96: 288 senza spazi, 304 con due spazi da 8
        assertFalse(serveColonna(listOf(96, 96, 96), spazio = 0, disponibile = 300))
        assertTrue(serveColonna(listOf(96, 96, 96), spazio = 8, disponibile = 300))
    }

    @Test
    fun `nessun pulsante, niente colonna`() {
        assertFalse(serveColonna(emptyList(), spazio = 8, disponibile = 0))
    }

    @Test
    fun `un pulsante solo piu' largo dello spazio va a tutta larghezza`() {
        assertTrue(serveColonna(listOf(400), spazio = 8, disponibile = 300))
    }

    // --- azioneSotto (RigaStato) ---

    @Test
    fun `un pulsante corto sta a destra del testo`() {
        // "Riprova": circa 80 dp su una riga da 296
        assertFalse(azioneSotto(larghezzaPulsante = 80, spazio = 8, disponibile = 296))
    }

    @Test
    fun `un pulsante lungo va sotto il testo`() {
        // "Ricollega il telefono": circa 170 dp
        assertTrue(azioneSotto(larghezzaPulsante = 170, spazio = 8, disponibile = 296))
    }

    // --- misuraStriscia + numero del giorno (StrisciaGiorni) ---

    @Test
    fun `senza spazio tra le celle 8 giorni da 32 stanno in 320 dp`() {
        // cella = 32 + 2×4 di riserva per l'anello; la striscia ora usa spazio 0
        assertEquals(40, misuraStriscia(8, cellaNaturale = 40, spazio = 0, larghezzaMassima = 320))
        // in una card su un telefono da 360 (288 utili) si stringe a 36: quadretto da 28
        assertEquals(36, misuraStriscia(8, cellaNaturale = 40, spazio = 0, larghezzaMassima = 288))
    }

    @Test
    fun `il numero del giorno non esce mai dal quadretto`() {
        for (lato in listOf(20f, 25f, 32f)) {
            for (fontScale in listOf(1.0f, 1.3f, 2.0f)) {
                val sp = misuraNumeroGiorno(lato, fontScale)
                assertTrue("lato $lato, scala $fontScale: numero mancante", sp > 0f)
                // Quanto è grande davvero sullo schermo, in dp.
                val dp = sp * fontScale
                // In altezza: la riga (interlinea 1,2) dentro il quadretto.
                assertTrue("lato $lato, scala $fontScale: alto $dp", dp * 1.2f <= lato)
                // In larghezza: due cifre (≈0,6 em l'una) con un po' di margine.
                assertTrue("lato $lato, scala $fontScale: largo $dp", 2 * 0.6f * dp <= lato * 0.8f)
                // E mai più grande del labelMedium normale (12).
                assertTrue(dp <= 12f + 1e-4f)
            }
        }
    }

    @Test
    fun `il testo grande di sistema non ingrandisce il numero`() {
        val normale = misuraNumeroGiorno(32f, 1.0f)
        val grande = misuraNumeroGiorno(32f, 2.0f)
        assertEquals(normale, grande * 2.0f, 1e-4f)
    }

    @Test
    fun `il numero si rimpicciolisce col quadretto e sparisce se e' minuscolo`() {
        assertTrue(misuraNumeroGiorno(25f, 1f) < misuraNumeroGiorno(32f, 1f))
        assertEquals(0f, misuraNumeroGiorno(12f, 1f), 0f)
    }

    // --- misuraSuUnaRiga (TestoSuUnaRiga) ---

    @Test
    fun `un testo che ci sta non cambia misura`() {
        assertEquals(12f, misuraSuUnaRiga(12f, larghezzaNaturale = 80, disponibile = 90, minimo = 9f), 0f)
    }

    @Test
    fun `un testo troppo largo scende in proporzione al quarto di sp`() {
        // 12 × 90 / 100 = 10,8 → 10,75
        assertEquals(10.75f, misuraSuUnaRiga(12f, larghezzaNaturale = 100, disponibile = 90, minimo = 9f), 0f)
    }

    @Test
    fun `mai sotto il minimo`() {
        assertEquals(9f, misuraSuUnaRiga(12f, larghezzaNaturale = 300, disponibile = 90, minimo = 9f), 0f)
    }

    @Test
    fun `spazio nullo o testo vuoto non rompono niente`() {
        assertEquals(9f, misuraSuUnaRiga(12f, larghezzaNaturale = 100, disponibile = 0, minimo = 9f), 0f)
        assertEquals(12f, misuraSuUnaRiga(12f, larghezzaNaturale = 0, disponibile = 0, minimo = 9f), 0f)
    }

    // --- testoBadge (BarraSchede) ---

    @Test
    fun `il pallino oltre 99 dice 99+`() {
        assertEquals("3", testoBadge(3))
        assertEquals("99", testoBadge(99))
        assertEquals("99+", testoBadge(100))
    }

    // --- contrasto ---

    @Test
    fun `bianco su nero fa 21 e due colori uguali fanno 1`() {
        assertEquals(21.0, contrasto(Color.White, Color.Black), 0.01)
        assertEquals(21.0, contrasto(Color.Black, Color.White), 0.01)
        assertEquals(1.0, contrasto(Color(0xFF1F6E5C), Color(0xFF1F6E5C)), 1e-9)
    }

    @Test
    fun `i colori del patto hanno i contrasti scritti in ColoriPatto`() {
        // I numeri nei commenti di ColoriPatto.kt, misurati sul bianco o sull'inchiostro.
        assertEquals(6.55, contrasto(ColoriPatto.InchiostroSuMantenuta, ColoriPatto.Mantenuta), 0.05)
        assertEquals(5.59, contrasto(ColoriPatto.InchiostroSuFuoriRegola, ColoriPatto.FuoriRegola), 0.05)
    }
}
