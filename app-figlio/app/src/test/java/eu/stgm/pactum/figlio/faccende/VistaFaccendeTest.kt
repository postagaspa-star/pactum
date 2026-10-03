package eu.stgm.pactum.figlio.faccende

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** (0.13) La pagina Faccende: da fare e chiuse, dalla fonte più fresca, la foto di ognuna, e quando viene prima di tutto. */
class VistaFaccendeTest {

    private val t0 = 1_790_000_000_000L
    private fun ora(t: Long) = Istante(t, t - t0 + 3_600_000L, 1)

    private val mio = ChiaveCollegamento.di(ChiaveCollegamento.impronta("codice-di-prova"), 1, 1)

    private val lavastoviglie = FaccendaDaFare(5, "Svuota la lavastoviglie", genitore = "Mamma")
    private val cane = FaccendaDaFare(6, "Porta fuori il cane", genitore = "Papà")

    private fun MemoriaBlocco.blocco(r: BloccoDalServer, t: Long) = conServer(r, ora(t), ora(t), t)
    private fun MemoriaBlocco.elenco(t: Long, vararg f: FaccendaLocale) = conElenco(f.toList(), ora(t), ora(t))

    @Test
    fun `il blocco più fresco dell'elenco vince`() {
        val m = MemoriaBlocco()
            .elenco(
                t0,
                FaccendaLocale(5, "Svuota la lavastoviglie", stato = StatiFaccenda.DA_FARE, creataIl = t0),
                FaccendaLocale(6, "Porta fuori il cane", stato = StatiFaccenda.DA_FARE, creataIl = t0 + 1),
            )
            // Un minuto dopo il blocco dice: resta solo il cane (la lavastoviglie è fatta).
            .blocco(BloccoDalServer(true, t0, null, listOf(cane)), t0 + 60_000)
        assertEquals(listOf(6L), VistaFaccende.daFare(m).map { it.id })
    }

    @Test
    fun `l'elenco più fresco del blocco vince`() {
        val m = MemoriaBlocco()
            .blocco(BloccoDalServer(true, t0, null, listOf(lavastoviglie, cane)), t0)
            .elenco(
                t0 + 60_000,
                FaccendaLocale(5, "Svuota la lavastoviglie", stato = StatiFaccenda.FATTA, chiusaIl = t0 + 30_000),
                FaccendaLocale(6, "Porta fuori il cane", stato = StatiFaccenda.DA_FARE),
            )
        assertEquals(listOf(6L), VistaFaccende.daFare(m).map { it.id })
        assertEquals(listOf(5L), VistaFaccende.chiuse(m).map { it.id })
    }

    @Test
    fun `una faccenda da fare non compare mai anche fra le chiuse`() {
        val m = MemoriaBlocco()
            .elenco(t0, FaccendaLocale(5, "Svuota la lavastoviglie", stato = StatiFaccenda.FATTA))
            .blocco(BloccoDalServer(true, t0, null, listOf(lavastoviglie.copy(bocciature = 1))), t0 + 60_000)
        assertEquals(listOf(5L), VistaFaccende.daFare(m).map { it.id })
        assertEquals(emptyList<Long>(), VistaFaccende.chiuse(m).map { it.id })
    }

    @Test
    fun `le chiuse dalla più recente`() {
        val m = MemoriaBlocco().elenco(
            t0,
            FaccendaLocale(1, "A", stato = StatiFaccenda.FATTA, chiusaIl = t0),
            FaccendaLocale(2, "B", stato = StatiFaccenda.ANNULLATA, chiusaIl = t0 + 2),
            FaccendaLocale(3, "C", stato = StatiFaccenda.FATTA, chiusaIl = t0 + 1),
        )
        assertEquals(listOf(2L, 3L, 1L), VistaFaccende.chiuse(m).map { it.id })
    }

    @Test
    fun `a che punto è la foto`() {
        val faccenda = FaccendaLocale(5, "Svuota la lavastoviglie", stato = StatiFaccenda.DA_FARE)
        val vuota = MemoriaCodaFoto()
        assertEquals(VistaFaccende.Foto.NESSUNA, VistaFaccende.foto(faccenda, vuota))
        val inCoda = vuota.conScatto(FotoInCoda(5, "a.jpg", t0, mio)).first
        assertEquals(VistaFaccende.Foto.IN_CODA, VistaFaccende.foto(faccenda, inCoda))
        val mandata = inCoda.conEsito(5, "a.jpg", EsitoFoto.ARRIVATA, t0).first
        assertEquals(VistaFaccende.Foto.MANDATA, VistaFaccende.foto(faccenda, mandata))
        val rifiutata = inCoda.conEsito(5, "a.jpg", EsitoFoto.RIFIUTATA, t0).first
        assertEquals(VistaFaccende.Foto.RIFIUTATA, VistaFaccende.foto(faccenda, rifiutata))
        assertEquals(VistaFaccende.Foto.NESSUNA, VistaFaccende.foto(faccenda.copy(bocciature = 1), mandata))
    }

    @Test
    fun `la pagina Faccende viene prima delle regole e dei permessi quando serve`() {
        val vuota = MemoriaBlocco()
        // Telefono senza regole e senza faccende: niente pagina prima.
        assertFalse(VistaFaccende.primaDelResto(bloccato = false, memoria = vuota, arrivoDalleFaccende = false))
        // Bloccato: la pagina prima, anche senza regole e senza accesso all'uso.
        assertTrue(VistaFaccende.primaDelResto(bloccato = true, memoria = vuota, arrivoDalleFaccende = false))
        // Faccende da fare (il blocco parte più tardi): prima anche loro.
        val conFaccende = vuota.blocco(BloccoDalServer(false, null, t0 + 3_600_000, listOf(cane)), t0)
        assertTrue(VistaFaccende.primaDelResto(bloccato = false, memoria = conFaccende, arrivoDalleFaccende = false))
        // Da "Apri Pactum" o da una notifica di faccende.
        assertTrue(VistaFaccende.primaDelResto(bloccato = false, memoria = vuota, arrivoDalleFaccende = true))
    }
}
