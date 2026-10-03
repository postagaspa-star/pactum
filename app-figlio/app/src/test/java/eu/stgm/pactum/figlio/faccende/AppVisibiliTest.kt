package eu.stgm.pactum.figlio.faccende

import eu.stgm.pactum.figlio.misura.Sessioni
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (0.13) Le app ancora visibili (finestrella, schermo diviso) durante il
 * blocco, la finestra lunga di lettura all'inizio del blocco, e quando la
 * copertura sopra le finestrelle si mostra.
 */
class AppVisibiliTest {

    private val youtube = "com.google.android.youtube"
    private val pactum = "eu.stgm.pactum.figlio"
    private val messaggi = "com.google.android.apps.messaging"
    private val t0 = 1_790_000_000_000L
    private val attesa = DecisioneFinestrelle.ATTESA_MS

    /** Copre tutto tranne Pactum e gli SMS. */
    private val copri: (String, String?) -> Boolean = { p, _ -> p != pactum && p != messaggi }

    @Test
    fun `una finestrella di YouTube sopra Pactum - da coprire dopo qualche secondo`() {
        val v = AppVisibili()
        v.evento(Sessioni.RIPRESA, youtube, t0, "$youtube.WatchActivity")
        // YouTube va in finestrella (resta in pausa, visibile) e davanti c'è Pactum.
        v.evento(Sessioni.PAUSA, youtube, t0 + 100, "$youtube.WatchActivity")
        v.evento(Sessioni.RIPRESA, pactum, t0 + 200, "$pactum.MainActivity")
        assertTrue(v.daCoprire(t0 + 1_000, pactum, attesa, copri).isEmpty())
        assertEquals(setOf(youtube), v.daCoprire(t0 + 200 + attesa, pactum, attesa, copri))
    }

    @Test
    fun `un'app coperta dalla barriera si ferma - niente da coprire`() {
        val v = AppVisibili()
        v.evento(Sessioni.RIPRESA, youtube, t0, "$youtube.WatchActivity")
        v.evento(Sessioni.PAUSA, youtube, t0 + 100, "$youtube.WatchActivity")
        v.evento(Sessioni.RIPRESA, pactum, t0 + 200, "$pactum.faccende.BarrieraFaccendeActivity")
        v.evento(Sessioni.STOP, youtube, t0 + 600, "$youtube.WatchActivity")
        assertTrue(v.daCoprire(t0 + 10_000, pactum, attesa, copri).isEmpty())
    }

    @Test
    fun `schermo diviso - YouTube nell'altra metà si copre, gli SMS no`() {
        val v = AppVisibili()
        v.evento(Sessioni.RIPRESA, messaggi, t0, "$messaggi.ConversationListActivity")
        v.evento(Sessioni.RIPRESA, youtube, t0 + 100, "$youtube.WatchActivity")
        // Il ragazzo tocca la metà degli SMS: davanti ci sono loro, YouTube resta visibile.
        v.evento(Sessioni.RIPRESA, messaggi, t0 + 200, "$messaggi.ConversationListActivity")
        assertEquals(setOf(youtube), v.daCoprire(t0 + 200 + attesa, messaggi, attesa, copri))
        // Con YouTube davanti non è una finestrella: lo copre la barriera, non questa.
        v.evento(Sessioni.RIPRESA, youtube, t0 + 10_000, "$youtube.WatchActivity")
        assertTrue(v.daCoprire(t0 + 20_000, youtube, attesa, copri).isEmpty())
    }

    @Test
    fun `schermo spento o bloccato - non si vede più niente`() {
        val v = AppVisibili()
        v.evento(Sessioni.RIPRESA, youtube, t0, "$youtube.WatchActivity")
        v.evento(Sessioni.RIPRESA, pactum, t0 + 100, "$pactum.MainActivity")
        v.evento(Sessioni.SCHERMO_SPENTO, null, t0 + 200)
        assertTrue(v.daCoprire(t0 + 10_000, null, attesa, copri).isEmpty())
    }

    @Test
    fun `una schermata chiusa non è più visibile`() {
        val v = AppVisibili()
        v.evento(Sessioni.RIPRESA, youtube, t0, "$youtube.WatchActivity")
        v.evento(Sessioni.RIPRESA, pactum, t0 + 100, "$pactum.MainActivity")
        v.evento(AppVisibili.CHIUSA, youtube, t0 + 200, "$youtube.WatchActivity")
        assertTrue(v.daCoprire(t0 + 10_000, pactum, attesa, copri).isEmpty())
    }

    @Test
    fun `gli eventi vecchi riletti non cambiano niente`() {
        val v = AppVisibili()
        v.evento(Sessioni.RIPRESA, youtube, t0, "$youtube.WatchActivity")
        v.evento(Sessioni.RIPRESA, pactum, t0 + 100, "$pactum.MainActivity")
        // Un evento più vecchio, riletto per la sovrapposizione.
        v.evento(Sessioni.STOP, youtube, t0 - 5_000, "$youtube.WatchActivity")
        assertEquals(setOf(youtube), v.daCoprire(t0 + 100 + attesa, pactum, attesa, copri))
    }

    // --- La finestra lunga all'inizio del blocco ----------------------------

    @Test
    fun `all'inizio del blocco si rilegge un giorno intero, o dall'accensione`() {
        val ore = 60L * 60 * 1000
        assertEquals(t0 - 24 * ore, LetturaEventi.inizio(daCapo = true, lettoFinoA = t0 - 1000, adesso = t0, dallAccensione = 72 * ore))
        assertEquals(t0 - 3 * ore, LetturaEventi.inizio(daCapo = true, lettoFinoA = null, adesso = t0, dallAccensione = 3 * ore))
    }

    @Test
    fun `un gioco aperto da più di dieci minuti si trova all'inizio del blocco`() {
        val aperto = t0 - 45 * 60 * 1000L
        val da = LetturaEventi.inizio(daCapo = true, lettoFinoA = null, adesso = t0, dallAccensione = 5 * 60 * 60 * 1000L)
        assertTrue(da <= aperto)
        // Dopo uno schermo spento, invece, bastano gli ultimi dieci minuti.
        assertFalse(LetturaEventi.inizio(daCapo = false, lettoFinoA = null, adesso = t0, dallAccensione = 5 * 60 * 60 * 1000L) <= aperto)
    }

    @Test
    fun `nel giro normale si riparte da dove si era arrivati`() {
        assertEquals(t0 - 5_000, LetturaEventi.inizio(daCapo = false, lettoFinoA = t0, adesso = t0 + 1_000, dallAccensione = 1_000_000))
        assertEquals(0L, LetturaEventi.inizio(daCapo = true, lettoFinoA = null, adesso = 1_000, dallAccensione = 5_000))
    }

    // --- Quando la copertura si mostra --------------------------------------

    @Test
    fun `la copertura si mostra se c'è qualcosa da coprire, non durante la pausa per chiudere la finestrella`() {
        assertFalse(DecisioneFinestrelle.mostra(emptySet(), 10_000, null))
        assertTrue(DecisioneFinestrelle.mostra(setOf(youtube), 10_000, null))
        assertFalse(DecisioneFinestrelle.mostra(setOf(youtube), 10_000, 20_000))
        assertTrue(DecisioneFinestrelle.mostra(setOf(youtube), 20_000, 20_000))
    }
}
