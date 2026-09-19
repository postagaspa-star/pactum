package eu.stgm.pactum.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La logica pura di core-design (condivisa con l'app del genitore): "6 su 7" e
 * la serie sono le due frasi che il ragazzo legge per prime. Se sbagliano,
 * l'app dice una cosa falsa sul suo patto. Va provata.
 */
class GiorniPattoTest {

    private fun striscia(vararg segnali: Segnale): List<GiornoPatto> =
        segnali.mapIndexed { i, s -> GiornoPatto("2026-07-%02d".format(i + 1), s) }

    private val M = Segnale.MANTENUTA
    private val F = Segnale.FUORI_REGOLA
    private val N = Segnale.NESSUN_DATO

    // --- segnaleDaStato ---

    @Test
    fun `verde e rosso sono gli unici stati con un significato`() {
        assertEquals(Segnale.MANTENUTA, segnaleDaStato("verde"))
        assertEquals(Segnale.FUORI_REGOLA, segnaleDaStato("rosso"))
    }

    @Test
    fun `tutto il resto e' nessun dato`() {
        assertEquals(Segnale.NESSUN_DATO, segnaleDaStato("grigio"))
        assertEquals(Segnale.NESSUN_DATO, segnaleDaStato(""))
        // Il vecchio "giallo" non esiste più, e un valore nuovo del server non
        // può diventare per sbaglio un giorno mantenuto.
        assertEquals(Segnale.NESSUN_DATO, segnaleDaStato("giallo"))
        assertEquals(Segnale.NESSUN_DATO, segnaleDaStato("VERDE"))
    }

    // --- contaGiorni ---

    @Test
    fun `i giorni senza dati escono dal denominatore`() {
        assertEquals(6 to 7, contaGiorni(striscia(M, M, F, M, M, M, M, N)))
    }

    @Test
    fun `una striscia tutta vuota fa zero su zero`() {
        assertEquals(0 to 0, contaGiorni(striscia(N, N, N)))
        assertEquals(0 to 0, contaGiorni(emptyList()))
    }

    @Test
    fun `tutti fuori regola fa zero su tutti`() {
        assertEquals(0 to 3, contaGiorni(striscia(F, F, F)))
    }

    // --- serieDiGiorni ---

    @Test
    fun `la serie conta all'indietro da oggi e si ferma al primo fuori regola`() {
        assertEquals(3, serieDiGiorni(striscia(M, M, F, M, M, M)))
    }

    @Test
    fun `oggi fuori regola azzera la serie`() {
        assertEquals(0, serieDiGiorni(striscia(M, M, M, F)))
    }

    @Test
    fun `oggi senza dati non rompe la serie, si parte da ieri`() {
        assertEquals(4, serieDiGiorni(striscia(F, M, M, M, M, N)))
    }

    @Test
    fun `un giorno senza dati prima di oggi ferma la serie`() {
        assertEquals(2, serieDiGiorni(striscia(M, M, N, M, M)))
        // Oggi vuoto si salta, ieri vuoto no.
        assertEquals(0, serieDiGiorni(striscia(M, M, N, N)))
    }

    @Test
    fun `otto giorni mantenuti sono una serie di otto`() {
        assertEquals(8, serieDiGiorni(striscia(M, M, M, M, M, M, M, M)))
    }

    @Test
    fun `striscia vuota o di soli vuoti fa zero`() {
        assertEquals(0, serieDiGiorni(emptyList()))
        assertEquals(0, serieDiGiorni(striscia(N)))
    }

    // --- misuraStriscia (la larghezza della striscia grande, in dp a densità 1) ---

    @Test
    fun `se c'e' posto le celle restano alla misura di progetto`() {
        // 8 celle da 40 + 7 spazi da 4 = 348: su un 412 dp col padding del
        // genitore dentro una card (412 - 32 - 32 = 348) ci sta preciso.
        assertEquals(40, misuraStriscia(8, cellaNaturale = 40, spazio = 4, larghezzaMassima = 348))
        assertEquals(40, misuraStriscia(8, cellaNaturale = 40, spazio = 4, larghezzaMassima = null))
    }

    @Test
    fun `su 360 dp le celle si stringono tutte uguali invece di uscire`() {
        // Figlio: 360 - 20 - 20 (schermata) - 20 - 20 (card eroe) = 280.
        val cella = misuraStriscia(8, cellaNaturale = 40, spazio = 4, larghezzaMassima = 280)
        assertEquals(31, cella)
        assertTrue(cella * 8 + 4 * 7 <= 280)
        // Genitore: 360 - 16 - 16 - 16 - 16 = 296.
        assertEquals(33, misuraStriscia(8, cellaNaturale = 40, spazio = 4, larghezzaMassima = 296))
    }

    @Test
    fun `la striscia piccola da 20 ci sta sempre intera su 360 dp`() {
        // 8 x 28 + 7 x 4 = 252, meno dei 280 del caso più stretto.
        assertEquals(28, misuraStriscia(8, cellaNaturale = 28, spazio = 4, larghezzaMassima = 280))
    }

    @Test
    fun `mai una misura negativa`() {
        assertEquals(0, misuraStriscia(8, cellaNaturale = 40, spazio = 4, larghezzaMassima = 10))
        assertEquals(0, misuraStriscia(0, cellaNaturale = 40, spazio = 4, larghezzaMassima = 300))
    }
}
