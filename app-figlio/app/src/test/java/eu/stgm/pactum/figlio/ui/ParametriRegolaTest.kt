package eu.stgm.pactum.figlio.ui

import eu.stgm.pactum.figlio.dati.TipiRegola
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (0.10) Il modulo di una regola è lo stesso per creare, modificare e proporre
 * al genitore: i parametri che partono devono essere quelli del contratto, e
 * un valore che il server rifiuterebbe deve tenere spento il pulsante.
 */
class ParametriRegolaTest {

    @Test
    fun `limite di tempo con bersaglio scelto e minuti da 1 a 1440`() {
        assertEquals(
            buildJsonObject {
                put("app_o_categoria", "totale")
                put("minuti_al_giorno", 180)
            },
            ParametriRegola.daCampi(TipiRegola.LIMITE_TEMPO, app = "totale", minuti = " 180 "),
        )
        assertEquals(1440, ParametriRegola.MINUTI_MASSIMI)
        for (minuti in listOf("", "0", "-5", "1441", "un'ora")) {
            assertNull(minuti, ParametriRegola.daCampi(TipiRegola.LIMITE_TEMPO, app = "totale", minuti = minuti))
        }
        assertNull(ParametriRegola.daCampi(TipiRegola.LIMITE_TEMPO, app = " ", minuti = "60"))
    }

    @Test
    fun `la partenza di una proposta e' il modulo appena aperto, non i parametri grezzi`() {
        // Il server può avere i giorni in un altro ordine: il modulo li riordina.
        val grezzi = buildJsonObject {
            put("dalle", "22:00")
            put("alle", "07:00")
            putJsonArray("giorni") {
                add("ven")
                add("lun")
            }
        }
        val partenza = ParametriRegola.daCampi(
            TipiRegola.FASCIA_ORARIA, dalle = "22:00", alle = "07:00", giorni = setOf("ven", "lun"),
        )
        // Confrontare coi grezzi accenderebbe "Manda la proposta" senza nessun cambio.
        assertNotEquals(grezzi, partenza)
        // Lo stesso modulo, toccato e rimesso com'era, è la partenza: niente da proporre.
        assertEquals(
            partenza,
            ParametriRegola.daCampi(TipiRegola.FASCIA_ORARIA, dalle = "22:00", alle = "07:00", giorni = setOf("lun", "ven")),
        )
    }

    @Test
    fun `oltre un giorno di minuti il campo lo dice`() {
        assertTrue(ParametriRegola.minutiOltreIlGiorno("1441"))
        assertTrue(ParametriRegola.minutiOltreIlGiorno(" 2000 "))
        assertTrue(ParametriRegola.minutiOltreIlGiorno("99999999999999999999999"))
        assertFalse(ParametriRegola.minutiOltreIlGiorno("1440"))
        assertFalse(ParametriRegola.minutiOltreIlGiorno("60"))
        // Vuoto o non un numero: il pulsante è spento, ma "24 ore" non c'entra.
        assertFalse(ParametriRegola.minutiOltreIlGiorno(""))
        assertFalse(ParametriRegola.minutiOltreIlGiorno("un'ora"))
        assertFalse(ParametriRegola.minutiOltreIlGiorno("-5"))
    }

    @Test
    fun `fascia oraria con orari veri e i giorni nell'ordine della settimana`() {
        assertEquals(
            buildJsonObject {
                put("dalle", "22:00")
                put("alle", "07:00")
                putJsonArray("giorni") {
                    add("lun")
                    add("ven")
                    add("dom")
                }
            },
            ParametriRegola.daCampi(
                TipiRegola.FASCIA_ORARIA, dalle = "22:00", alle = " 07:00", giorni = setOf("dom", "lun", "ven"),
            ),
        )
        assertNull(ParametriRegola.daCampi(TipiRegola.FASCIA_ORARIA, dalle = "24:00", alle = "07:00", giorni = setOf("lun")))
        assertNull(ParametriRegola.daCampi(TipiRegola.FASCIA_ORARIA, dalle = "22:00", alle = "7:00", giorni = setOf("lun")))
        assertNull(ParametriRegola.daCampi(TipiRegola.FASCIA_ORARIA, dalle = "22:00", alle = "07:00", giorni = emptySet()))
    }

    @Test
    fun `vita reale con tre campi pieni, senza spazi ai bordi`() {
        assertEquals(
            buildJsonObject {
                put("descrizione", "cammino un'ora")
                put("arbitro_nome", "la mamma")
                put("frequenza", "ogni giorno")
            },
            ParametriRegola.daCampi(
                TipiRegola.VITA_REALE, descrizione = " cammino un'ora ", arbitro = "la mamma", frequenza = "ogni giorno ",
            ),
        )
        assertNull(ParametriRegola.daCampi(TipiRegola.VITA_REALE, descrizione = "x", arbitro = "", frequenza = "y"))
        assertNull(ParametriRegola.daCampi("sconosciuto"))
    }
}
