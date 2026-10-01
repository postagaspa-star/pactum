package eu.stgm.pactum.figlio.sessione

import eu.stgm.pactum.figlio.misura.Sessioni
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (0.11) La barriera giro per giro, come la fa girare SorveglianzaSessione
 * (gli eventi d'uso, la guardia, il ritmo), senza Android. La regola: un'app
 * fuori dalla lista si copre sempre, anche se l'ha aperta un'app della
 * sessione; restano usabili solo le eccezioni scritte (le pagine web nella
 * scheda del browser, la fotocamera di sistema, la scelta di file e foto…).
 */
class ScenariBarrieraTest {

    private val min = 60_000L
    private val inizio = 1_790_000_000_000L

    private val classeViva = "eu.spaggiari.classevivafamiglia"
    private val gmail = "com.google.android.gm"
    private val instagram = "com.instagram.android"
    private val drive = "com.google.android.apps.docs"
    private val chrome = "com.android.chrome"
    private val whatsapp = "com.whatsapp"
    private val fotocamera = "com.google.android.GoogleCamera"
    private val home = "com.google.android.apps.nexuslauncher"
    private val pactum = "eu.stgm.pactum.figlio"

    private val studio = SessioneAttiva(
        svoltaId = 12,
        sessioneId = 3,
        nome = "Studio",
        app = setOf(classeViva, gmail),
        nomi = emptyMap(),
        inizio = inizio,
        fine = inizio + 60 * min,
    )

    /** Quello che SempreUsabili.leggi trova su un Pixel: le fisse, la Home scelta, la fotocamera di sistema. */
    private val sempre: Set<String> = SempreUsabili.FISSE +
        SempreUsabili.homeDaUsare(home, listOf(home)) +
        SempreUsabili.fotocamereDaUsare(fotocamera, emptyList()) { it == fotocamera }

    /** Il telefono: gli eventi d'uso e un giro della barriera circa ogni secondo. */
    private inner class Telefono {
        private val traccia = TracciaPrimoPiano()
        private val ritmo = RitmoBarriera()
        private var orologio = inizio + 5 * min
        private var monotono = 1_000_000L
        val aperture = mutableListOf<String>()

        /** Un'app (una sua schermata) passa davanti. */
        fun apre(pacchetto: String, classe: String = "$pacchetto.MainActivity") {
            orologio += 200
            traccia.evento(Sessioni.RIPRESA, pacchetto, orologio, classe)
        }

        /** Un giro della barriera. True = la barriera si apre adesso (e passa davanti). */
        fun giro(): Boolean {
            orologio += 1_000
            monotono += 1_000
            val primoPiano = traccia.attuale
            val decisione = GuardiaSessione.decidi(
                SituazioneBarriera(
                    sessione = studio,
                    adesso = orologio,
                    primoPiano = primoPiano,
                    schermoAcceso = true,
                    sbloccato = true,
                    mostraSopra = true,
                    accessoUso = true,
                    inChiamata = false,
                    sempreUsabili = sempre,
                    // Tutto conta nell'uso: quello che non si copre, non si copre per le eccezioni.
                    contaNellUso = { true },
                    nelGruppoApk = { false },
                    classe = traccia.classe,
                ),
            )
            val apri = ritmo.passo(primoPiano, decisione.copri, monotono)
            if (apri) {
                aperture += primoPiano.orEmpty()
                apre(pactum, "eu.stgm.pactum.figlio.sessione.BarrieraActivity")
            }
            return apri
        }

        /** "Esci": la Home, e il servizio riguarda da capo. */
        fun esci() {
            apre(home, "com.google.android.apps.nexuslauncher.NexusLauncherActivity")
            ritmo.azzera()
        }

        fun giri(quanti: Int): List<Boolean> = List(quanti) { giro() }
    }

    @Test
    fun `da ClasseViva a Instagram scorrendo sulla barra dei gesti - coperta al secondo giro`() {
        val telefono = Telefono()
        telefono.apre(classeViva)
        assertEquals(listOf(false, false), telefono.giri(2))
        // Nessuna Home in mezzo: dritto da un'app della sessione a Instagram.
        telefono.apre(instagram, "com.instagram.mainactivity.MainActivity")
        assertFalse("al primo giro può essere un lampo", telefono.giro())
        assertTrue("al secondo giro si copre", telefono.giro())
        assertEquals(listOf(instagram), telefono.aperture)
    }

    @Test
    fun `una notifica toccata dentro ClasseViva che apre WhatsApp - coperta`() {
        val telefono = Telefono()
        telefono.apre(classeViva)
        telefono.giro()
        telefono.apre(whatsapp, "com.whatsapp.Conversation")
        assertEquals(listOf(false, true), telefono.giri(2))
    }

    @Test
    fun `Drive aperto da ClasseViva si copre`() {
        val telefono = Telefono()
        telefono.apre(classeViva)
        telefono.giro()
        telefono.apre(drive, "com.google.android.apps.docs.app.DocumentOpenerActivity")
        assertEquals(listOf(false, true), telefono.giri(2))
        assertEquals(listOf(drive), telefono.aperture)
    }

    @Test
    fun `una pagina web aperta da Gmail nella scheda del browser non si copre`() {
        val telefono = Telefono()
        telefono.apre(gmail, "com.google.android.gm.ConversationListActivityGmail")
        telefono.giro()
        telefono.apre(chrome, "org.chromium.chrome.browser.customtabs.CustomTabActivity")
        assertTrue(telefono.giri(10).none { it })
        // "Apri in Chrome": il browser vero è un'app fuori dalla lista, e si copre.
        telefono.apre(chrome, "org.chromium.chrome.browser.ChromeTabbedActivity")
        assertEquals(listOf(false, true), telefono.giri(2))
        assertEquals(listOf(chrome), telefono.aperture)
    }

    @Test
    fun `la fotocamera non si copre, da ClasseViva o dalla Home`() {
        val telefono = Telefono()
        telefono.apre(classeViva)
        telefono.giro()
        telefono.apre(fotocamera, "com.android.camera.CaptureActivity")
        assertTrue(telefono.giri(10).none { it })
        telefono.esci()
        telefono.apre(fotocamera, "com.android.camera.CameraLauncher")
        assertTrue(telefono.giri(5).none { it })
        // La galleria aperta dalla fotocamera è un'app come le altre.
        telefono.apre("com.google.android.apps.photos", "com.google.android.apps.photos.home.HomeActivity")
        assertEquals(listOf(false, true), telefono.giri(2))
    }

    @Test
    fun `la scelta dei file e quella delle foto non si coprono`() {
        val telefono = Telefono()
        telefono.apre(classeViva)
        telefono.giro()
        telefono.apre("com.google.android.documentsui", "com.android.documentsui.picker.PickActivity")
        assertTrue(telefono.giri(5).none { it })
        telefono.apre("com.google.android.providers.media.module", "com.android.providers.media.photopicker.PhotoPickerActivity")
        assertTrue(telefono.giri(5).none { it })
        assertTrue(telefono.aperture.isEmpty())
    }

    @Test
    fun `Condividi da ClasseViva - la finestra di scelta no, WhatsApp si'`() {
        val telefono = Telefono()
        telefono.apre(classeViva)
        telefono.giro()
        telefono.apre("android", "com.android.internal.app.ChooserActivity")
        assertTrue(telefono.giri(3).none { it })
        telefono.apre(whatsapp, "com.whatsapp.contact.picker.ContactPicker")
        assertEquals(listOf(false, true), telefono.giri(2))
    }

    @Test
    fun `dopo Esci, tornare subito su Instagram la copre di nuovo`() {
        val telefono = Telefono()
        telefono.apre(instagram)
        assertEquals(listOf(false, true), telefono.giri(2))
        assertFalse(telefono.giro()) // la barriera è davanti
        // Esci, e di nuovo Instagram prima del giro dopo: la Home non si è vista.
        telefono.esci()
        telefono.apre(instagram)
        assertEquals(listOf(false, true), telefono.giri(2))
        assertEquals(listOf(instagram, instagram), telefono.aperture)
    }

    @Test
    fun `l'app Google si copre come le altre, anche se e' l'assistente`() {
        val telefono = Telefono()
        telefono.apre(home)
        telefono.giro()
        telefono.apre("com.google.android.googlequicksearchbox", "com.google.android.googlequicksearchbox.SearchActivity")
        assertEquals(listOf(false, true), telefono.giri(2))
    }

    @Test
    fun `le app della sessione e Pactum non si coprono mai`() {
        val telefono = Telefono()
        telefono.apre(classeViva)
        telefono.apre(gmail)
        telefono.apre(pactum)
        telefono.apre(home)
        assertTrue(telefono.giri(10).none { it })
    }
}
