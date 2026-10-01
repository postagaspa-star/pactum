package eu.stgm.pactum.figlio.sessione

import eu.stgm.pactum.figlio.misura.Sessioni
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (0.11) Quando si apre la barriera: al cambio dell'app davanti, solo se ci
 * resta due giri di fila, mai a raffica, mai sopra se stessa; e un
 * interruttore che ferma ogni giro in tondo, ma che finita la pausa copre di
 * nuovo l'app rimasta lì.
 */
class RitmoBarrieraTest {

    private val instagram = "com.instagram.android"
    private val tiktok = "com.zhiliaoapp.musically"
    private val pactum = "eu.stgm.pactum.figlio"
    private val home = "com.google.android.apps.nexuslauncher"

    /** L'app da coprire davanti per due giri di fila (a [t] e un secondo dopo): l'apertura arriva al secondo. */
    private fun apreDopoDueGiri(ritmo: RitmoBarriera, app: String, t: Long) {
        assertFalse("al primo giro no", ritmo.passo(app, copri = true, adesso = t))
        assertTrue("al secondo giro sì", ritmo.passo(app, copri = true, adesso = t + 1_000))
    }

    @Test
    fun `si apre alla seconda volta di fila che l'app da coprire e' davanti, non a ogni giro`() {
        val ritmo = RitmoBarriera()
        apreDopoDueGiri(ritmo, instagram, 0)
        // Stessa app al giro dopo: niente (la barriera sta arrivando).
        assertFalse(ritmo.passo(instagram, copri = true, adesso = 2_000))
    }

    @Test
    fun `un lampo non apre niente`() {
        val ritmo = RitmoBarriera()
        // Una notifica che apre un'app e la richiude subito, una chiamata che arriva.
        assertFalse(ritmo.passo(instagram, true, 0))
        assertFalse(ritmo.passo(home, false, 1_000))
        assertFalse(ritmo.passo(home, false, 2_000))
    }

    @Test
    fun `con la barriera davanti non si riapre`() {
        val ritmo = RitmoBarriera()
        apreDopoDueGiri(ritmo, instagram, 0)
        // Davanti c'è la barriera (Pactum): mai da coprire.
        for (t in 2_000L..60_000L step 1_000L) assertFalse(ritmo.passo(pactum, false, t))
    }

    @Test
    fun `tornando nell'app dopo Esci la barriera torna`() {
        val ritmo = RitmoBarriera()
        apreDopoDueGiri(ritmo, instagram, 0)
        assertFalse(ritmo.passo(pactum, false, 2_000))
        // Esci: la schermata Home.
        assertFalse(ritmo.passo(home, false, 3_000))
        // Di nuovo Instagram: si copre di nuovo.
        apreDopoDueGiri(ritmo, instagram, 4_000)
    }

    @Test
    fun `dopo Esci rivedere subito la stessa app e' un cambio`() {
        val ritmo = RitmoBarriera()
        apreDopoDueGiri(ritmo, instagram, 0)
        // Esci e di nuovo Instagram tra due giri: la Home non si è vista.
        ritmo.azzera()
        assertFalse(ritmo.passo(instagram, true, 1_600))
        assertTrue(ritmo.passo(instagram, true, 2_600))
    }

    @Test
    fun `mai due aperture troppo vicine - la seconda aspetta il primo giro buono`() {
        val ritmo = RitmoBarriera()
        apreDopoDueGiri(ritmo, instagram, 0) // aperta a 1_000
        assertFalse(ritmo.passo(pactum, false, 1_300))
        assertFalse(ritmo.passo(home, false, 1_600))
        assertFalse(ritmo.passo(instagram, true, 1_900))
        // Due giri di fila, ma troppo vicino all'apertura di prima.
        assertFalse(ritmo.passo(instagram, true, 2_200))
        // Il giro dopo, se è ancora lì, sì.
        assertTrue(ritmo.passo(instagram, true, 2_600))
    }

    @Test
    fun `se la barriera non parte si riprova poche volte, sempre piu' piano, poi basta`() {
        val ritmo = RitmoBarriera()
        apreDopoDueGiri(ritmo, instagram, 0) // aperta a 1_000
        assertFalse(ritmo.passo(instagram, true, 2_000))
        assertFalse(ritmo.passo(instagram, true, 4_000))
        assertTrue(ritmo.passo(instagram, true, 5_000)) // 4 s dopo la prima
        assertFalse(ritmo.passo(instagram, true, 14_000))
        assertTrue(ritmo.passo(instagram, true, 15_000)) // 10 s dopo la seconda
        assertFalse(ritmo.passo(instagram, true, 44_000))
        assertTrue(ritmo.passo(instagram, true, 45_000)) // 30 s dopo la terza
        // Poi basta, finché l'app davanti non cambia.
        for (t in 46_000L..400_000L step 1_000L) assertFalse(ritmo.passo(instagram, true, t))
        assertFalse(ritmo.passo(home, false, 401_000))
        apreDopoDueGiri(ritmo, instagram, 402_000)
    }

    @Test
    fun `un'app che non si copre non apre niente`() {
        val ritmo = RitmoBarriera()
        for (t in 0L..10_000L step 1_000L) assertFalse(ritmo.passo(home, false, t))
    }

    @Test
    fun `la stessa app che diventa da coprire (finita la chiamata) apre la barriera`() {
        val ritmo = RitmoBarriera()
        // Durante la chiamata Instagram resta davanti, ma non si copre.
        assertFalse(ritmo.passo(instagram, false, 0))
        assertFalse(ritmo.passo(instagram, false, 1_000))
        // Chiamata finita: si copre, dopo due giri, una volta.
        apreDopoDueGiri(ritmo, instagram, 2_000)
        assertFalse(ritmo.passo(instagram, true, 4_000))
    }

    @Test
    fun `l'interruttore di sicurezza ferma un giro in tondo, e finita la pausa l'app rimasta si copre di nuovo`() {
        val ritmo = RitmoBarriera()
        var t = 0L
        // Otto aperture in meno di un minuto (Instagram che torna davanti da solo).
        repeat(RitmoBarriera.LANCI_MASSIMI) {
            apreDopoDueGiri(ritmo, instagram, t)
            assertFalse(ritmo.passo(pactum, false, t + 1_300))
            t += 2_000
        }
        // La nona: basta, pausa.
        assertFalse(ritmo.passo(instagram, true, t))
        assertFalse(ritmo.passo(instagram, true, t + 1_000))
        assertTrue(ritmo.inPausa(t + 1_000))
        // Il ragazzo resta su Instagram: durante la pausa niente.
        val finePausa = t + 1_000 + RitmoBarriera.PAUSA_MS
        var dentro = t + 2_000
        while (dentro < finePausa) {
            assertFalse(ritmo.passo(instagram, true, dentro))
            dentro += 1_000
        }
        // Finita la pausa, è ancora lì: si copre di nuovo.
        assertFalse(ritmo.inPausa(finePausa))
        assertTrue(ritmo.passo(instagram, true, finePausa))
    }

    @Test
    fun `durante la pausa un'app nuova da coprire aspetta la fine della pausa`() {
        val ritmo = RitmoBarriera()
        var t = 0L
        repeat(RitmoBarriera.LANCI_MASSIMI) {
            apreDopoDueGiri(ritmo, instagram, t)
            assertFalse(ritmo.passo(pactum, false, t + 1_300))
            t += 2_000
        }
        assertFalse(ritmo.passo(instagram, true, t))
        assertFalse(ritmo.passo(instagram, true, t + 1_000)) // pausa da qui
        assertFalse(ritmo.passo(home, false, t + 2_000))
        assertFalse(ritmo.passo(tiktok, true, t + 3_000))
        assertFalse(ritmo.passo(tiktok, true, t + 4_000))
        assertTrue(ritmo.passo(tiktok, true, t + 1_000 + RitmoBarriera.PAUSA_MS))
    }

    @Test
    fun `aperture lontane nel tempo non fanno scattare l'interruttore`() {
        val ritmo = RitmoBarriera()
        var t = 0L
        repeat(30) {
            apreDopoDueGiri(ritmo, instagram, t)
            assertFalse(ritmo.passo(home, false, t + 2_000))
            t += 10_000 // una ogni 10 secondi: 6 al minuto
        }
    }

    @Test
    fun `l'app davanti che non si sa non apre niente, e quando torna nota si guarda da capo`() {
        val ritmo = RitmoBarriera()
        apreDopoDueGiri(ritmo, instagram, 0)
        assertFalse(ritmo.passo(null, false, 5_000))
        apreDopoDueGiri(ritmo, instagram, 6_000)
    }

    @Test
    fun `dopo lo schermo spento l'app ritrovata si copre di nuovo`() {
        val ritmo = RitmoBarriera()
        apreDopoDueGiri(ritmo, instagram, 0)
        assertFalse(ritmo.passo(pactum, false, 2_000))
        ritmo.azzera()
        // Allo sblocco davanti c'è di nuovo Instagram (la barriera si era chiusa).
        apreDopoDueGiri(ritmo, instagram, 60_000)
    }

    // --- quale app è davanti ----------------------------------------------------

    @Test
    fun `l'ultima RESUMED e' l'app davanti, con la sua schermata`() {
        val traccia = TracciaPrimoPiano()
        assertNull(traccia.attuale)
        traccia.evento(Sessioni.RIPRESA, home, 1_000, "Launcher")
        traccia.evento(Sessioni.PAUSA, home, 2_000)
        traccia.evento(Sessioni.RIPRESA, instagram, 2_001, "com.instagram.mainactivity.MainActivity")
        assertEquals(instagram, traccia.attuale)
        assertEquals("com.instagram.mainactivity.MainActivity", traccia.classe)
    }

    @Test
    fun `un'altra schermata della stessa app cambia solo la schermata`() {
        val traccia = TracciaPrimoPiano()
        traccia.evento(Sessioni.RIPRESA, instagram, 2_000, "A")
        traccia.evento(Sessioni.RIPRESA, instagram, 3_000, "B")
        assertEquals(instagram, traccia.attuale)
        assertEquals("B", traccia.classe)
    }

    @Test
    fun `dopo la finestra Condividi e' davanti l'app scelta`() {
        val traccia = TracciaPrimoPiano()
        val whatsapp = "com.whatsapp"
        traccia.evento(Sessioni.RIPRESA, "eu.spaggiari.classevivafamiglia", 1_000)
        traccia.evento(Sessioni.RIPRESA, "android", 2_000, "com.android.internal.app.ChooserActivity")
        assertEquals("android", traccia.attuale)
        traccia.evento(Sessioni.RIPRESA, whatsapp, 3_000, "com.whatsapp.contact.picker.ContactPicker")
        assertEquals(whatsapp, traccia.attuale)
        assertEquals("com.whatsapp.contact.picker.ContactPicker", traccia.classe)
    }

    @Test
    fun `PAUSED e STOPPED non cambiano l'app davanti`() {
        val traccia = TracciaPrimoPiano()
        traccia.evento(Sessioni.RIPRESA, instagram, 1_000)
        traccia.evento(Sessioni.PAUSA, instagram, 2_000)
        traccia.evento(Sessioni.STOP, instagram, 2_500)
        assertEquals(instagram, traccia.attuale)
    }

    @Test
    fun `gli eventi riletti, o piu' vecchi, non cambiano niente`() {
        val traccia = TracciaPrimoPiano()
        traccia.evento(Sessioni.RIPRESA, home, 1_000)
        traccia.evento(Sessioni.RIPRESA, instagram, 3_000)
        // Il giro dopo rilegge da un po' prima: gli stessi eventi, in ordine.
        traccia.evento(Sessioni.RIPRESA, home, 1_000)
        assertEquals(instagram, traccia.attuale)
        traccia.evento(Sessioni.RIPRESA, instagram, 3_000)
        assertEquals(instagram, traccia.attuale)
    }

    @Test
    fun `schermo spento o blocco - non si sa piu' fino alla prossima app`() {
        val traccia = TracciaPrimoPiano()
        traccia.evento(Sessioni.RIPRESA, home, 500)
        traccia.evento(Sessioni.RIPRESA, instagram, 1_000, "com.instagram.mainactivity.MainActivity")
        traccia.evento(Sessioni.SCHERMO_SPENTO, "android", 2_000)
        assertNull(traccia.attuale)
        assertNull(traccia.classe)
        traccia.evento(Sessioni.RIPRESA, instagram, 1_000) // vecchio: niente
        assertNull(traccia.attuale)
        traccia.evento(Sessioni.RIPRESA, pactum, 9_000)
        assertEquals(pactum, traccia.attuale)
        traccia.evento(Sessioni.BLOCCO, "android", 10_000)
        assertNull(traccia.attuale)
        traccia.azzera()
        traccia.evento(Sessioni.RIPRESA, home, 500)
        assertEquals(home, traccia.attuale)
    }

    @Test
    fun `una RESUMED senza pacchetto non cambia niente`() {
        val traccia = TracciaPrimoPiano()
        traccia.evento(Sessioni.RIPRESA, instagram, 1_000)
        traccia.evento(Sessioni.RIPRESA, null, 2_000)
        traccia.evento(Sessioni.RIPRESA, "", 3_000)
        assertEquals(instagram, traccia.attuale)
    }
}
