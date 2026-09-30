package eu.stgm.pactum.genitore.sync

import eu.stgm.pactum.genitore.dati.Notifica
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La decisione del giro della vedetta (0.9): che cosa avvisare, che cosa
 * ricordare, quando guardare. Se sbaglia, il padre riceve lo stesso avviso ogni
 * minuto — o, peggio, non riceve lo sforamento di suo figlio.
 */
class GiroVedettaTest {

    private fun notifica(id: Long) = Notifica(
        id = id,
        tipo = "sforamento",
        messaggio = "Evento sforamento registrato",
        tsServer = "2026-09-30T10:00:00+00:00",
    )

    private fun lista(vararg id: Long) = id.map(::notifica)

    /**
     * Un giro come lo fa Vedetta: le novità, gli avvisi (se c'è il permesso) e
     * il ricordo. Restituisce gli id avvisati a questo giro e il ricordo nuovo.
     */
    private fun giro(
        nonLette: List<Notifica>,
        ricordate: Set<Long>,
        permesso: Boolean = true,
    ): Pair<List<Long>, Set<Long>> {
        val nuove = novitaDaAvvisare(nonLette, ricordate)
        val avvisate = if (permesso) nuove.map { it.id } else emptyList()
        return avvisate to avvisateDaRicordare(ricordate, avvisate, nonLette.map { it.id })
    }

    // --- che cosa avvisare --------------------------------------------------------------

    @Test
    fun `al primo giro si avvisano tutte le non lette, dalla piu vecchia`() {
        assertEquals(listOf(3L, 5L, 9L), novitaDaAvvisare(lista(9, 3, 5), emptySet()).map { it.id })
    }

    @Test
    fun `una novita gia avvisata non si riavvisa, ne a questo giro ne ai prossimi`() {
        // Lo sforamento delle 18:02 arriva: un avviso. Poi, un giro al minuto,
        // la stessa lista: nessun altro avviso finché il padre non la legge.
        val (primo, ricordo1) = giro(lista(7), emptySet())
        assertEquals(listOf(7L), primo)
        val (secondo, ricordo2) = giro(lista(7), ricordo1)
        assertTrue(secondo.isEmpty())
        val (terzo, _) = giro(lista(7), ricordo2)
        assertTrue(terzo.isEmpty())
        // Una novità dopo: avvisa solo lei.
        val (quarto, _) = giro(lista(7, 8), ricordo2)
        assertEquals(listOf(8L), quarto)
    }

    @Test
    fun `un id ripetuto nella stessa lista avvisa una volta sola`() {
        assertEquals(listOf(4L), novitaDaAvvisare(lista(4, 4), emptySet()).map { it.id })
    }

    @Test
    fun `lista vuota, niente da avvisare`() {
        assertTrue(novitaDaAvvisare(emptyList(), setOf(1L, 2L)).isEmpty())
    }

    // --- che cosa ricordare ---------------------------------------------------------------

    @Test
    fun `si ricordano solo le non lette - una letta si dimentica, e non torna`() {
        val ricordo = avvisateDaRicordare(setOf(1L, 2L, 3L), avvisateOra = listOf(4L), nonLette = listOf(3L, 4L))
        assertEquals(setOf(3L, 4L), ricordo)
        // Letto tutto: il ricordo si svuota, e al giro dopo non c'è niente da avvisare.
        assertTrue(avvisateDaRicordare(ricordo, emptyList(), nonLette = emptyList()).isEmpty())
    }

    @Test
    fun `con piu di 500 non lette nessuna torna nuova a ogni giro`() {
        // Il vecchio tetto di 500 id: con 600 non lette le 100 più vecchie
        // tornavano "nuove" a ogni giro. Col servizio sarebbe stato ogni minuto.
        val seicento = (1L..600L).map(::notifica)
        val (primo, ricordo) = giro(seicento, emptySet())
        assertEquals(600, primo.size)
        assertEquals(600, ricordo.size)
        val (secondo, _) = giro(seicento, ricordo)
        assertTrue(secondo.isEmpty())
    }

    @Test
    fun `il ricordo della 0,8 col tetto non riavvisa piu di una volta`() {
        // Aggiornata dalla 0.8: il ricordo ha solo i 500 id più alti. Le non lette
        // più vecchie si avvisano UNA volta, poi restano ricordate.
        val nonLette = (1L..520L).map(::notifica)
        val ricordoDella08 = (21L..520L).toSet()
        val (primo, ricordo) = giro(nonLette, ricordoDella08)
        assertEquals((1L..20L).toList(), primo)
        val (secondo, _) = giro(nonLette, ricordo)
        assertTrue(secondo.isEmpty())
    }

    @Test
    fun `dopo un ripristino del registro sul server un id riusato si avvisa di nuovo`() {
        // Prima del ripristino: avvisati 10, 11, 12. Il server torna alle 3 di
        // notte (c'era solo la 10) e la prossima notifica nuova riprende l'id 11.
        val (_, primaDelRipristino) = giro(lista(10, 11, 12), emptySet())
        val (_, dopoIlRipristino) = giro(lista(10), primaDelRipristino)
        assertEquals(setOf(10L), dopoIlRipristino)
        val (avvisate, _) = giro(lista(10, 11), dopoIlRipristino)
        assertEquals(listOf(11L), avvisate)
    }

    @Test
    fun `senza permesso non si ricorda niente di nuovo, e le novita aspettano il permesso`() {
        val (senza, ricordo) = giro(lista(5, 6), emptySet(), permesso = false)
        assertTrue(senza.isEmpty())
        assertTrue(ricordo.isEmpty())
        // Il permesso arriva: al giro dopo partono tutte e due.
        val (con, _) = giro(lista(5, 6), ricordo, permesso = true)
        assertEquals(listOf(5L, 6L), con)
    }

    // --- il giro veloce: solo le nuove (dopo_id, contratto v3.3) --------------------------------

    @Test
    fun `il giro veloce chiede solo quelle dopo l'ultima avvisata`() {
        assertEquals(9L, dopoIdPerIlGiroVeloce(setOf(3L, 9L, 5L)))
        // Mai avvisato niente (o primo giro): la lista intera.
        assertEquals(null, dopoIdPerIlGiroVeloce(emptySet()))
        assertEquals(null, dopoIdPerIlGiroVeloce(null))
    }

    @Test
    fun `una lista parziale non fa dimenticare niente, quella intera si`() {
        // Giro veloce: arrivano solo le nuove. Le vecchie non lette non ci sono, ma
        // restano ricordate: se no il giro completo le riavviserebbe.
        assertEquals(setOf(1L, 2L, 3L), avvisateDaRicordare(setOf(1L, 2L), listOf(3L), nonLette = null))
        // Giro completo: la lista intera, e le lette si dimenticano.
        assertEquals(setOf(2L, 3L), avvisateDaRicordare(setOf(1L, 2L), listOf(3L), nonLette = listOf(2L, 3L)))
    }

    @Test
    fun `un server vecchio che ignora dopo_id manda tutto, e non cambia niente`() {
        val ricordo = setOf(1L, 2L)
        // Server v3.3: solo la nuova. Server vecchio: tutte le non lette.
        val nuoveV33 = novitaDaAvvisare(lista(3), ricordo)
        val nuoveVecchio = novitaDaAvvisare(lista(1, 2, 3), ricordo)
        assertEquals(listOf(3L), nuoveV33.map { it.id })
        assertEquals(nuoveV33, nuoveVecchio)
        assertEquals(
            avvisateDaRicordare(ricordo, listOf(3L), null),
            avvisateDaRicordare(ricordo, listOf(3L), null),
        )
    }

    // --- niente raffiche, e posto nella tendina ----------------------------------------------

    @Test
    fun `al primo giro senza ricordo un riassunto, non una raffica`() {
        assertEquals(ModoAvviso.RIASSUNTO, modoAvviso(primoGiro = true, quante = 5))
        // Una sola novità: la sua notifica, non "Novità da leggere: 1".
        assertEquals(ModoAvviso.UNA_PER_UNA, modoAvviso(primoGiro = true, quante = 1))
        // Un giro normale: una per una, finché stanno nella tendina.
        assertEquals(ModoAvviso.UNA_PER_UNA, modoAvviso(primoGiro = false, quante = 5))
        assertEquals(ModoAvviso.UNA_PER_UNA, modoAvviso(primoGiro = false, quante = TETTO_AVVISI_ATTIVI))
        assertEquals(ModoAvviso.RIASSUNTO, modoAvviso(primoGiro = false, quante = TETTO_AVVISI_ATTIVI + 1))
    }

    @Test
    fun `prima di avvisare si fa posto togliendo gli avvisi del patto piu vecchi`() {
        // 19 nella tendina, 3 in arrivo, tetto 20: via i 2 più vecchi tra i togliibili.
        val attivi = (1..19).map { AvvisoAttivo(id = it, quando = 1_000L + it, togliibile = true) } +
            AvvisoAttivo(id = 2_000_000_011, quando = 1L, togliibile = false) // un silenzio, il più vecchio
        val tolti = avvisiDaTogliere(attivi.drop(1), inArrivo = 3) // 18 togliibili + il silenzio = 19
        assertEquals(listOf(2, 3), tolti)
        // Un avviso di silenzio non si toglie mai, anche se è il più vecchio.
        assertFalse(tolti.contains(2_000_000_011))
        // C'è posto: niente da togliere.
        assertTrue(avvisiDaTogliere(attivi.take(5), inArrivo = 3).isEmpty())
        // Solo non togliibili: non si toglie niente (meglio stretti che perdere un silenzio).
        assertTrue(avvisiDaTogliere(List(25) { AvvisoAttivo(it, it.toLong(), togliibile = false) }, 1).isEmpty())
    }

    // --- che cosa dice la notifica fissa -----------------------------------------------------

    @Test
    fun `la notifica fissa dice la verita sugli avvisi`() {
        assertEquals(StatoAvvisi.IN_ORDINE, statoAvvisi(avvisiAccesi = true, esenteDallaBatteria = true))
        assertEquals(StatoAvvisi.POSSIBILI_RITARDI, statoAvvisi(avvisiAccesi = true, esenteDallaBatteria = false))
        // Avvisi spenti pesano di più: non arriva proprio niente.
        assertEquals(StatoAvvisi.SPENTI, statoAvvisi(avvisiAccesi = false, esenteDallaBatteria = true))
        assertEquals(StatoAvvisi.SPENTI, statoAvvisi(avvisiAccesi = false, esenteDallaBatteria = false))
    }

    // --- quando guardare --------------------------------------------------------------------

    private val minuto = 60_000L

    @Test
    fun `il giro completo subito, poi ogni 15 minuti`() {
        assertTrue(CadenzaVedetta.giroCompletoDovuto(ultimo = null, adesso = 5 * minuto))
        assertFalse(CadenzaVedetta.giroCompletoDovuto(ultimo = 0, adesso = minuto))
        assertFalse(CadenzaVedetta.giroCompletoDovuto(ultimo = 0, adesso = 14 * minuto + 59_999))
        assertTrue(CadenzaVedetta.giroCompletoDovuto(ultimo = 0, adesso = 15 * minuto))
        // Un orologio che torna indietro vale "dovuto".
        assertTrue(CadenzaVedetta.giroCompletoDovuto(ultimo = 10 * minuto, adesso = minuto))
    }

    @Test
    fun `le notifiche si guardano ogni minuto, il resto ogni 15`() {
        assertEquals(minuto, CadenzaVedetta.INTERVALLO_MS)
        assertEquals(15 * minuto, CadenzaVedetta.INTERVALLO_COMPLETO_MS)
    }

    @Test
    fun `server muto - i giri si diradano fino a 15 minuti, e un singhiozzo non ritarda niente`() {
        assertEquals(minuto, CadenzaVedetta.attesaDopo(0))
        assertEquals(minuto, CadenzaVedetta.attesaDopo(1))
        assertEquals(2 * minuto, CadenzaVedetta.attesaDopo(2))
        assertEquals(4 * minuto, CadenzaVedetta.attesaDopo(3))
        assertEquals(8 * minuto, CadenzaVedetta.attesaDopo(4))
        assertEquals(15 * minuto, CadenzaVedetta.attesaDopo(5))
        assertEquals(15 * minuto, CadenzaVedetta.attesaDopo(1_000))
        assertEquals(minuto, CadenzaVedetta.attesaDopo(-3))
    }

    @Test
    fun `senza avvisi possibili il giro rallenta a uno ogni 15 minuti`() {
        assertEquals(15 * minuto, CadenzaVedetta.attesa(fallimentiDiFila = 0, avvisiAccesi = false))
        assertEquals(minuto, CadenzaVedetta.attesa(fallimentiDiFila = 0, avvisiAccesi = true))
        assertEquals(4 * minuto, CadenzaVedetta.attesa(fallimentiDiFila = 3, avvisiAccesi = true))
    }

    @Test
    fun `l'ultimo controllo si riscrive al massimo ogni 10 minuti, non a ogni giro`() {
        assertTrue(CadenzaVedetta.registrazioneDovuta(ultima = null, adesso = 0))
        assertFalse(CadenzaVedetta.registrazioneDovuta(ultima = 0, adesso = 9 * minuto))
        assertTrue(CadenzaVedetta.registrazioneDovuta(ultima = 0, adesso = 10 * minuto))
        assertTrue(CadenzaVedetta.registrazioneDovuta(ultima = 10 * minuto, adesso = minuto))
    }

    @Test
    fun `il risveglio dura piu del giro piu lungo, e i nomi aspettano meno di una richiesta`() {
        assertTrue(CadenzaVedetta.RISVEGLIO_MASSIMO_MS > CadenzaVedetta.GIRO_MASSIMO_MS)
        assertTrue(CadenzaVedetta.TEMPO_PER_I_NOMI_MS <= 10_000L)
    }
}
