package eu.stgm.pactum.figlio.servizio

import eu.stgm.pactum.figlio.misura.Ripresa
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (0.16) Il controllo leggero vicino al limite: quando scatta (solo con regole
 * vicine, ogni 5 s fino al giro), quando no, e quando lancia subito il giro
 * completo (un'app di una regola vicina arrivata davanti dopo il giro).
 */
class ControlloLeggeroTest {

    private val instagram = "com.instagram.android"
    private val tiktok = "com.zhiliaoapp.musically"
    private val whatsapp = "com.whatsapp"
    private val home = "com.android.launcher"
    private val social = "categoria:social"

    private fun categoria(pacchetto: String): String =
        if (pacchetto == instagram || pacchetto == tiktok) social else "categoria:altro"

    /** Conta tutto tranne la Home, come il filtro dell'uso. */
    private val conta: (String) -> Boolean = { it != home }
    private val cade: (String, String) -> Boolean = { chiave, pacchetto -> ControlloLeggero.cade(chiave, pacchetto, ::categoria) }

    /** Il controllo di una finestra, partendo da chi era davanti al giro. */
    private fun giro(riprese: List<String>, davantiAlGiro: Set<String>, vicine: List<String>): Boolean =
        ControlloLeggero.serveGiro(ControlloLeggero.arrivi(riprese, davantiAlGiro).arrivate, vicine, conta, cade)

    // --- quando scatta --------------------------------------------------------------

    @Test
    fun `vicino al limite un controllo ogni 5 secondi fino al giro`() {
        assertEquals(5_000L, ControlloLeggero.passo(restaMs = 60_000L, vicino = true))
        assertEquals(5_000L, ControlloLeggero.passo(restaMs = 41_000L, vicino = true))
        // L'ultimo pezzo prima del giro completo: solo quello che resta.
        assertEquals(3_000L, ControlloLeggero.passo(restaMs = 3_000L, vicino = true))
    }

    @Test
    fun `lontano dal limite nessun controllo, si aspetta il giro`() {
        assertEquals(60_000L, ControlloLeggero.passo(restaMs = 60_000L, vicino = false))
        assertEquals(41_000L, ControlloLeggero.passo(restaMs = 41_000L, vicino = false))
    }

    // --- quando lancia il giro completo ---------------------------------------------------

    @Test
    fun `arriva davanti l'app della regola vicina, subito il giro completo`() {
        // Instagram a 29:00 lasciato per WhatsApp; poi il ragazzo riapre Instagram.
        assertTrue(giro(listOf(instagram), davantiAlGiro = setOf(whatsapp), vicine = listOf(instagram)))
        // Dalla Home.
        assertTrue(giro(listOf(home, instagram), davantiAlGiro = setOf(home), vicine = listOf(instagram)))
    }

    @Test
    fun `l'app della regola gia' davanti al giro non rilancia niente`() {
        // Per lei vale la programmazione esatta del giro: cambiare schermata in Instagram non conta.
        assertFalse(giro(listOf(instagram, instagram), davantiAlGiro = setOf(instagram), vicine = listOf(instagram)))
    }

    @Test
    fun `va via e torna nella stessa finestra, conta`() {
        assertTrue(giro(listOf(whatsapp, instagram), davantiAlGiro = setOf(instagram), vicine = listOf(instagram)))
    }

    @Test
    fun `un'app che non cade in nessuna regola vicina, niente giro`() {
        // Il ragazzo ha chiuso Instagram a 30:10 e usa WhatsApp: solo letture minime.
        assertFalse(giro(listOf(whatsapp), davantiAlGiro = setOf(instagram), vicine = listOf(instagram)))
        assertFalse(giro(emptyList(), davantiAlGiro = setOf(whatsapp), vicine = listOf(instagram)))
        // La Home non conta mai, nemmeno per "tutto il telefono".
        assertFalse(giro(listOf(home), davantiAlGiro = setOf(whatsapp), vicine = listOf("totale")))
    }

    @Test
    fun `categoria e tutto il telefono`() {
        // TikTok cade in "social".
        assertTrue(giro(listOf(tiktok), davantiAlGiro = setOf(whatsapp), vicine = listOf(social)))
        // Per "tutto il telefono" qualunque app che conta, arrivata davanti.
        assertTrue(giro(listOf(whatsapp), davantiAlGiro = setOf(instagram), vicine = listOf("totale")))
        assertFalse(giro(listOf(whatsapp), davantiAlGiro = setOf(instagram), vicine = listOf(social)))
    }

    @Test
    fun `senza regole vicine il controllo non lancia mai il giro`() {
        assertFalse(giro(listOf(instagram), davantiAlGiro = emptySet(), vicine = emptyList()))
    }

    // --- le finestre: con margine all'indietro, senza contare due volte ---------------------

    @Test
    fun `la finestra rilegge qualche secondo all'indietro, fino ad adesso compreso`() {
        assertEquals(97_000L to 110_001L, ControlloLeggero.finestra(ultimo = 100_000L, adesso = 110_000L))
    }

    @Test
    fun `un evento scritto in ritardo con l'orario di prima non si perde`() {
        // Controllo a 110 s: Android non ha ancora scritto Instagram delle 109,5 s.
        val primo = ControlloLeggero.nuove(listOf(Ripresa(whatsapp, 106_000L)), emptySet(), adesso = 110_000L)
        assertEquals(listOf(whatsapp), primo.nuove)
        // Controllo a 115 s: la finestra parte da 107 s, Instagram 109,5 c'è (e WhatsApp 106 no).
        val (da, _) = ControlloLeggero.finestra(110_000L, 115_000L)
        val letti = listOf(Ripresa(whatsapp, 106_000L), Ripresa(instagram, 109_500L)).filter { it.istante >= da }
        val secondo = ControlloLeggero.nuove(letti, primo.viste, adesso = 115_000L)
        assertEquals(listOf(instagram), secondo.nuove)
    }

    @Test
    fun `un evento gia' visto non conta due volte`() {
        val r = Ripresa(instagram, 109_000L)
        val primo = ControlloLeggero.nuove(listOf(r), emptySet(), adesso = 110_000L)
        assertEquals(listOf(instagram), primo.nuove)
        assertTrue(r in primo.viste)
        // La finestra dopo lo rilegge (margine): niente di nuovo.
        assertTrue(ControlloLeggero.nuove(listOf(r), primo.viste, adesso = 111_000L).nuove.isEmpty())
        // Uscito dal margine non si ricorda più (non serve: non si rilegge).
        assertFalse(r in ControlloLeggero.nuove(emptyList(), primo.viste, adesso = 120_000L).viste)
    }

    @Test
    fun `le nuove in ordine di orario`() {
        val lette = ControlloLeggero.nuove(listOf(Ripresa(instagram, 109_000L), Ripresa(whatsapp, 108_000L)), emptySet(), 110_000L)
        assertEquals(listOf(whatsapp, instagram), lette.nuove)
    }

    // --- il log ---------------------------------------------------------------------------

    @Test
    fun `la riga di log quando arrivano app, se no una volta al minuto`() {
        assertTrue(ControlloLeggero.daLoggare(arrivate = false, giro = false, ultimoLog = null, adesso = 0L))
        assertFalse(ControlloLeggero.daLoggare(arrivate = false, giro = false, ultimoLog = 0L, adesso = 5_000L))
        assertFalse(ControlloLeggero.daLoggare(arrivate = false, giro = false, ultimoLog = 0L, adesso = 59_999L))
        assertTrue(ControlloLeggero.daLoggare(arrivate = false, giro = false, ultimoLog = 0L, adesso = 60_000L))
        assertTrue(ControlloLeggero.daLoggare(arrivate = true, giro = false, ultimoLog = 0L, adesso = 5_000L))
        assertTrue(ControlloLeggero.daLoggare(arrivate = false, giro = true, ultimoLog = 0L, adesso = 5_000L))
    }

    // --- il conto alla rovescia del giro dopo ------------------------------------------------

    @Test
    fun `l'attesa conta dalla lettura dell'uso, non dalla fine del giro`() {
        // Letto a 1000 (orologio che non si sposta), limite fra 40 s (+1 s di margine):
        // il giro è durato 7 s, restano 34 s, non 41.
        val attesa = CadenzaSentinella.attesa(40_000L)
        assertEquals(41_000L, attesa)
        assertEquals(34_000L, CadenzaSentinella.resta(riferimento = 1_000L, attesa = attesa, adesso = 8_000L))
        // Un giro più lungo dell'attesa: mai negativo, almeno un secondo.
        assertEquals(1_000L, CadenzaSentinella.resta(riferimento = 1_000L, attesa = 5_000L, adesso = 20_000L))
        assertEquals(60_000L, CadenzaSentinella.resta(riferimento = 1_000L, attesa = 60_000L, adesso = 1_000L))
    }

    @Test
    fun `chi e' davanti passa da una finestra all'altra`() {
        val prima = ControlloLeggero.arrivi(listOf(whatsapp), setOf(instagram))
        assertEquals(listOf(whatsapp), prima.arrivate)
        assertEquals(setOf(whatsapp), prima.davanti)
        // Finestra dopo: nessun evento, davanti resta WhatsApp; poi Instagram arriva.
        val vuota = ControlloLeggero.arrivi(emptyList(), prima.davanti)
        assertEquals(setOf(whatsapp), vuota.davanti)
        assertEquals(listOf(instagram), ControlloLeggero.arrivi(listOf(instagram), vuota.davanti).arrivate)
    }
}
