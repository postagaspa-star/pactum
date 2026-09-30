package eu.stgm.pactum.figlio.servizio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * (0.9) Il loop veloce della sentinella: quando guarda l'uso e quando no.
 * Un giro al minuto a schermo acceso; allo spegnimento si guarda subito (o al
 * primo giro dopo); al primo giro di un giorno nuovo, una volta anche ieri.
 */
class CadenzaSentinellaTest {

    private val oggi = LocalDate.of(2026, 9, 30)
    private val ieri = oggi.minusDays(1)

    private fun giro(accesoOra: Boolean, accesoPrima: Boolean, spentoAdesso: Boolean = false, ultimo: LocalDate? = oggi) =
        CadenzaSentinella.giro(accesoOra, accesoPrima, spentoAdesso, oggi, ultimo)

    @Test
    fun `a schermo acceso si guarda a ogni giro`() {
        assertTrue(giro(accesoOra = true, accesoPrima = true).oggi)
        assertTrue(giro(accesoOra = true, accesoPrima = false).oggi)
    }

    @Test
    fun `allo spegnimento si guarda subito, anche se al giro prima era spento`() {
        // Acceso e spento tra due giri: il ricevitore di SCREEN_OFF sveglia il loop.
        assertTrue(giro(accesoOra = false, accesoPrima = false, spentoAdesso = true).oggi)
    }

    @Test
    fun `se lo spegnimento non arriva, si guarda al primo giro da spento`() {
        assertTrue(giro(accesoOra = false, accesoPrima = true).oggi)
    }

    @Test
    fun `a schermo spento non si guarda`() {
        assertFalse(giro(accesoOra = false, accesoPrima = false).oggi)
    }

    @Test
    fun `al primo giro di un giorno nuovo si guarda una volta anche ieri`() {
        val primo = giro(accesoOra = true, accesoPrima = true, ultimo = ieri)
        assertTrue(primo.oggi)
        assertTrue(primo.ieri)
        // Il giro dopo l'ultimo giorno guardato è oggi: ieri non si riguarda.
        assertFalse(giro(accesoOra = true, accesoPrima = true, ultimo = oggi).ieri)
    }

    @Test
    fun `ieri non si guarda a schermo spento ne' dopo giorni senza guardare`() {
        assertFalse(giro(accesoOra = false, accesoPrima = false, ultimo = ieri).ieri)
        assertFalse(giro(accesoOra = true, accesoPrima = true, ultimo = oggi.minusDays(3)).ieri)
        assertFalse(giro(accesoOra = true, accesoPrima = true, ultimo = null).ieri)
    }

    @Test
    fun `ieri si guarda fino al suo ultimo istante`() {
        val roma = ZoneId.of("Europe/Rome")
        val mezzanotte = LocalDateTime.of(2026, 9, 30, 0, 0).atZone(roma).toInstant().toEpochMilli()
        assertEquals(mezzanotte - 1, CadenzaSentinella.fineDiIeri(oggi, roma))
    }

    @Test
    fun `un giro al minuto`() {
        assertEquals(60_000L, CadenzaSentinella.INTERVALLO_MS)
    }

    @Test
    fun `una sera al telefono, giro per giro`() {
        // Acceso 3 minuti, spento 3, riacceso: si guarda nei 3 accesi, al primo
        // giro da spento, poi più niente finché non si riaccende.
        val schermo = listOf(true, true, true, false, false, false, true)
        var prima = false
        val guardati = schermo.map { ora ->
            giro(accesoOra = ora, accesoPrima = prima).oggi.also { prima = ora }
        }
        assertEquals(listOf(true, true, true, true, false, false, true), guardati)
    }
}
