package eu.stgm.pactum.genitore.ui

import eu.stgm.pactum.genitore.dati.ContatoreBonus
import eu.stgm.pactum.genitore.dati.DispositivoFinestra
import eu.stgm.pactum.genitore.dati.EventoFinestra
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.RegolaFinestra
import eu.stgm.pactum.genitore.dati.Sessione
import eu.stgm.pactum.genitore.dati.SessioneSvolta
import eu.stgm.pactum.genitore.dati.StatiSessione
import eu.stgm.pactum.genitore.dati.StatoBonus
import eu.stgm.pactum.genitore.dati.TipiRegola
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * (0.16) Quello che l'inventario della 0.15 ha trovato sparito e che torna in
 * vista: la sessione in corso in cima, la riga e la pagina Sessioni, il bonus di
 * oggi nella card del patto, l'ora delle interruzioni in "Da guardare insieme".
 */
class InventarioPanoramicaTest {

    private val p = ParoleDiProva
    private val zona = ZoneId.of("Europe/Rome")
    private val adesso = Instant.parse("2026-10-05T15:00:00Z")

    private fun svolta(id: Long, inizio: String, durata: Int, chiusura: String? = null, fine: String? = null) =
        SessioneSvolta(id = id, nome = "Studio", inizioTs = inizio, durataMinuti = durata, chiusura = chiusura, fineTs = fine, dispositivoId = 7)

    private val finestra = Finestra(
        dispositivi = listOf(DispositivoFinestra(id = 7), DispositivoFinestra(id = 8, revocato = true)),
        sessioniSvolte = listOf(
            // Iniziata un'ora fa per 2 ore: in corso.
            svolta(1, "2026-10-05T14:00:00Z", 120),
            // Ieri, arrivata in fondo.
            svolta(2, "2026-10-04T14:00:00Z", 60, chiusura = "scaduta", fine = "2026-10-04T15:00:00Z"),
        ),
        sessioni = listOf(
            Sessione(id = 10, nome = "Studio", stato = StatiSessione.APPROVATA, dispositivoId = 7),
            Sessione(id = 11, nome = "Musica", stato = StatiSessione.APPROVATA, dispositivoId = 7),
            // Del telefono scollegato: non più valida.
            Sessione(id = 12, nome = "Allenamento", stato = StatiSessione.APPROVATA, dispositivoId = 8),
        ),
    )

    @Test
    fun `le sessioni in breve - in corso, approvate, fatte, non piu valide`() {
        val conto = contoSessioni(finestra, adesso)
        assertEquals(listOf(1L), conto.inCorso.map { it.svolta.id })
        assertEquals(2, conto.approvate)
        assertEquals(1, conto.fatte)
        assertEquals(1, conto.nonPiuValide)
        assertTrue(contoSessioni(Finestra(), adesso).nessuna)
    }

    @Test
    fun `una sessione di un dispositivo scollegato non e in corso (la revoca non la chiude)`() {
        // Iniziata sul telefono 8, poi scollegato: la finestra la darebbe in corso
        // fino alla fine prevista. Non è in corso, e non è "fatta" (non si sa come è finita).
        val conScollegata = finestra.copy(
            sessioniSvolte = finestra.sessioniSvolte + svolta(3, "2026-10-05T14:30:00Z", 120).copy(dispositivoId = 8),
        )
        val (inCorso, fatte) = sessioniInCorsoEFatte(conScollegata, adesso)
        assertEquals(listOf(1L), inCorso.map { it.svolta.id })
        assertEquals(listOf(2L), fatte.map { it.svolta.id })
        val conto = contoSessioni(conScollegata, adesso)
        assertEquals(listOf(1L), conto.inCorso.map { it.svolta.id })
        assertEquals(1, conto.fatte)
    }

    @Test
    fun `una sessione in corso e una riga in cima, dopo il blocco`() {
        val conto = contoSessioni(finestra, adesso)
        val blocco = StatoBlocco(attivo = true, dal = adesso.minusSeconds(600), prossimo = null, daFare = 2)
        val righe = righeInCima(
            daDecidere = 0,
            blocco = blocco,
            fotoDaGuardare = 1,
            silenziosi = emptyList(),
            avvisiAccesi = true,
            ultimoControllo = null,
            adesso = adesso,
            inRete = true,
            errore = false,
            ricevutaAlle = null,
            sessioniInCorso = conto.inCorso,
        )
        assertEquals(listOf(RigaInCima.Blocco(blocco, 1), RigaInCima.SessioneInCorso(conto.inCorso.single())), righe)
        assertEquals("riga-sessione-1", chiaveRiga(righe[1]))
    }

    @Test
    fun `il bonus si dice per i dispositivi con un limite di tempo attivo, non scollegati`() {
        val bonus = StatoBonus(ContatoreBonus(15, 30, 15), ContatoreBonus(15, 90, 75))
        val telefono = VistaDispositivo(id = 7, nome = "Telefono", bonus = bonus)
        val computer = VistaDispositivo(id = 9, nome = "Computer", bonus = bonus)
        val scollegato = VistaDispositivo(id = 8, nome = "Vecchio", revocato = true, bonus = bonus)
        val regole = listOf(
            RegolaFinestra(id = 1, tipo = TipiRegola.LIMITE_TEMPO, dispositivoId = 7),
            RegolaFinestra(id = 2, tipo = TipiRegola.FASCIA_ORARIA, dispositivoId = 9),
            RegolaFinestra(id = 3, tipo = TipiRegola.LIMITE_TEMPO, dispositivoId = 9, attiva = false),
            RegolaFinestra(id = 4, tipo = TipiRegola.LIMITE_TEMPO, dispositivoId = 8),
        )
        assertEquals(bonus, bonusDaMostrare(telefono, regole, perDispositivo = true))
        // Solo una fascia e un limite non più attivo: niente bonus da dire.
        assertNull(bonusDaMostrare(computer, regole, perDispositivo = true))
        assertNull(bonusDaMostrare(scollegato, regole, perDispositivo = true))
        // Server 0.7: un telefono solo, le regole sono sue.
        val unico = VistaDispositivo(id = null, nome = null, bonus = bonus)
        assertEquals(bonus, bonusDaMostrare(unico, listOf(RegolaFinestra(id = 1, tipo = TipiRegola.LIMITE_TEMPO)), perDispositivo = false))
    }

    @Test
    fun `da guardare insieme - le interruzioni con l'ora, i fuori regola col giorno`() {
        val oggi = LocalDate.of(2026, 10, 5)
        val ieri = oggi.minusDays(1)
        val evento = EventoFinestra(id = "a", tipo = "manomissione", tsServer = "2026-10-04T13:10:00Z")
        assertEquals("ieri 15:10", quandoDaGuardare(p, VoceDaGuardare(GenereVoce.INTERRUZIONE, evento, ieri), oggi, zona))
        assertEquals("ieri", quandoDaGuardare(p, VoceDaGuardare(GenereVoce.FUORI_REGOLA, evento.copy(tipo = "sforamento"), ieri), oggi, zona))
        // Un giorno dichiarato (sforamento consegnato in ritardo): solo il giorno.
        assertEquals("ieri", quandoDaGuardare(p, VoceDaGuardare(GenereVoce.INTERRUZIONE, evento, ieri, giornoDichiarato = true), oggi, zona))
        val vecchio = EventoFinestra(id = "b", tipo = "manomissione", tsServer = "2026-10-01T13:10:00Z")
        assertEquals("01/10 15:10", quandoDaGuardare(p, VoceDaGuardare(GenereVoce.INTERRUZIONE, vecchio, LocalDate.of(2026, 10, 1)), oggi, zona))
    }
}
