package eu.stgm.pactum.figlio.valutatore

import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.TipiRegola
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * (0.16) In Oggi ogni fascia dice se oggi è rispettata, con i minuti dentro
 * la fascia contati dalle STESSE funzioni degli sforamenti.
 */
class StatoFasciaTest {

    private val zona = ZoneId.of("Europe/Rome")
    private val tutti = listOf("lun", "mar", "mer", "gio", "ven", "sab", "dom")

    private fun fascia(dalle: String, alle: String, giorni: List<String> = tutti) = Regola(
        id = 9,
        tipo = TipiRegola.FASCIA_ORARIA,
        parametri = buildJsonObject {
            put("dalle", dalle)
            put("alle", alle)
            putJsonArray("giorni") { giorni.forEach { add(it) } }
        },
        attiva = true,
    )

    /** Lunedì 5 ottobre 2026 alle [ora]:[minuti]. */
    private fun alle(ora: Int, minuti: Int = 0): Long =
        LocalDateTime.of(2026, 10, 5, ora, minuti).atZone(zona).toInstant().toEpochMilli()

    /** Uso in minuti per intervallo: [dentro] minuti per ogni intervallo che tocca quello usato. */
    private fun uso(daMs: Long, aMs: Long): (Long, Long) -> Long = { inizio, fine ->
        val s = maxOf(inizio, daMs)
        val e = minOf(fine, aMs)
        if (e > s) (e - s) / 60_000 else 0
    }

    private val nessunUso: (Long, Long) -> Long = { _, _ -> 0 }

    @Test
    fun `in corso senza uso e' rispettata finora`() {
        val stato = Valutatore.statoFasciaOggi(fascia("14:00", "16:00"), nessunUso, alle(15), zona)
        assertEquals(StatoFascia.RispettataFinora(LocalTime.of(16, 0)), stato)
    }

    @Test
    fun `finita senza uso e' rispettata`() {
        assertEquals(StatoFascia.Rispettata, Valutatore.statoFasciaOggi(fascia("14:00", "16:00"), nessunUso, alle(18), zona))
    }

    @Test
    fun `uso dentro la fascia, con gli stessi minuti dello sforamento`() {
        val regola = fascia("14:00", "16:00")
        val usoReale = uso(alle(15, 0), alle(15, 12))
        assertEquals(StatoFascia.Fuori(12), Valutatore.statoFasciaOggi(regola, usoReale, alle(18), zona))
        val sforamento = Valutatore.valuta(listOf(regola), emptyMap(), { 0 }, usoReale, alle(18), zona).single()
        assertEquals(12, sforamento.minutiOltre)
    }

    @Test
    fun `sotto il minuto non conta, come per lo sforamento`() {
        val regola = fascia("14:00", "16:00")
        val quasiNiente: (Long, Long) -> Long = { _, _ -> 0 }
        assertEquals(StatoFascia.Rispettata, Valutatore.statoFasciaOggi(regola, quasiNiente, alle(18), zona))
    }

    @Test
    fun `deve ancora cominciare`() {
        assertEquals(StatoFascia.Inizia(LocalTime.of(22, 30)), Valutatore.statoFasciaOggi(fascia("22:30", "07:00"), nessunUso, alle(10), zona))
    }

    @Test
    fun `la coda della notte con uso conta anche se stasera deve ancora cominciare`() {
        // 22:30-07:00: stamattina alle 6 venti minuti di telefono (la fascia di ieri sera).
        val stato = Valutatore.statoFasciaOggi(fascia("22:30", "07:00"), uso(alle(6, 0), alle(6, 20)), alle(10), zona)
        assertEquals(StatoFascia.Fuori(20), stato)
    }

    @Test
    fun `oggi non c'e'`() {
        // Il 5 ottobre 2026 è lunedì: una fascia solo nel fine settimana oggi non vale.
        assertEquals(StatoFascia.NonOggi, Valutatore.statoFasciaOggi(fascia("14:00", "16:00", listOf("sab", "dom")), nessunUso, alle(15), zona))
    }

    @Test
    fun `una regola che non e' una fascia non ha stato`() {
        val limite = Regola(id = 1, tipo = TipiRegola.LIMITE_TEMPO, parametri = buildJsonObject { put("minuti_al_giorno", 30) }, attiva = true)
        assertNull(Valutatore.statoFasciaOggi(limite, nessunUso, alle(15), zona))
    }

    @Test
    fun `lo stato dai pezzi`() {
        assertEquals(StatoFascia.Fuori(3), Valutatore.statoFascia(MomentoFascia.InCorso(30, LocalTime.of(16, 0)), 3))
        assertEquals(StatoFascia.RispettataFinora(LocalTime.of(16, 0)), Valutatore.statoFascia(MomentoFascia.InCorso(30, LocalTime.of(16, 0)), 0))
        assertEquals(StatoFascia.Inizia(LocalTime.of(22, 0)), Valutatore.statoFascia(MomentoFascia.Prima(60, LocalTime.of(22, 0)), 0))
        assertEquals(StatoFascia.Rispettata, Valutatore.statoFascia(MomentoFascia.Finita, 0))
        assertEquals(StatoFascia.NonOggi, Valutatore.statoFascia(MomentoFascia.NonOggi, 0))
    }
}
