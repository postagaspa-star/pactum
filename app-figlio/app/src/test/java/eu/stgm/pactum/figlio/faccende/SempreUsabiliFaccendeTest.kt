package eu.stgm.pactum.figlio.faccende

import eu.stgm.pactum.figlio.sessione.SempreUsabili
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (0.13) Le app sempre usabili durante il blocco delle faccende (decisione di
 * Andrea): quelle delle Sessioni, più Contatti, l'app degli SMS predefinita,
 * Wallet, eWeLink, Tinaba, Google Foto e la galleria. Mai le app di messaggi.
 */
class SempreUsabiliFaccendeTest {

    private val pactum = "eu.stgm.pactum.figlio"

    private fun unisci(sms: String? = "com.google.android.apps.messaging", ruoli: Set<String> = emptySet()) =
        SempreUsabiliFaccende.unisci(
            base = SempreUsabili.FISSE,
            ruoli = ruoli,
            smsPredefinita = sms,
            pactum = pactum,
        )

    @Test
    fun `l'app degli SMS predefinita c'è, quella di un'altra marca no`() {
        val samsung = unisci(sms = "com.samsung.android.messaging")
        assertTrue("com.samsung.android.messaging" in samsung)
        assertFalse("com.google.android.apps.messaging" in samsung)
    }

    @Test
    fun `WhatsApp, Telegram, Signal e Messenger mai, nemmeno come app degli SMS`() {
        for (app in listOf("com.whatsapp", "org.telegram.messenger", "org.thoughtcrime.securesms", "com.facebook.orca")) {
            assertFalse(app, app in unisci(sms = app))
            assertFalse(app, app in unisci(ruoli = setOf(app)))
            assertFalse(app, app in SempreUsabiliFaccende.unisci(SempreUsabili.FISSE + app, emptySet(), null, pactum))
        }
    }

    @Test
    fun `Wallet, eWeLink, Tinaba, Google Foto e i Contatti di Google ci sono per nome`() {
        val insieme = unisci()
        for (app in listOf(
            "com.google.android.apps.walletnfcrel",
            "com.coolkit",
            "it.tinaba.app",
            "com.google.android.apps.photos",
            "com.google.android.contacts",
        )) {
            assertTrue(app, app in insieme)
        }
    }

    @Test
    fun `le gallerie e i Contatti delle marche solo se sono di sistema`() {
        // Senza la conferma "è di sistema" (diSistemaPerNome vuoto) non entrano.
        val senza = unisci()
        assertFalse("com.miui.gallery" in senza)
        assertFalse("com.sec.android.gallery3d" in senza)
        val con = SempreUsabiliFaccende.unisci(
            SempreUsabili.FISSE, emptySet(), null, pactum,
            diSistemaPerNome = setOf("com.miui.gallery", "com.android.contacts"),
        )
        assertTrue("com.miui.gallery" in con)
        assertTrue("com.android.contacts" in con)
    }

    @Test
    fun `quelle delle Sessioni restano - Impostazioni, Telefono, sistema, scelta dei file`() {
        val insieme = unisci()
        for (app in listOf(
            "com.android.settings",
            "com.google.android.dialer",
            "com.android.systemui",
            "com.android.documentsui",
            "com.google.android.permissioncontroller",
        )) {
            assertTrue(app, app in insieme)
        }
    }

    @Test
    fun `Pactum c'è sempre, e senza app degli SMS non si rompe niente`() {
        val insieme = unisci(sms = null)
        assertTrue(pactum in insieme)
        assertFalse("" in unisci(sms = "  "))
    }

    @Test
    fun `i ruoli trovati a runtime entrano`() {
        assertTrue("com.samsung.android.app.contacts" in unisci(ruoli = setOf("com.samsung.android.app.contacts")))
    }

    @Test
    fun `i nomi verificati delle marche sono nella lista di sistema`() {
        for (app in listOf("com.sec.android.gallery3d", "com.miui.gallery", "com.samsung.android.app.contacts", "com.motorola.camera3")) {
            assertTrue(app, app in SempreUsabiliFaccende.DI_SISTEMA_PER_NOME)
        }
    }

    // --- Solo se di sistema, o dal Play Store --------------------------------

    @Test
    fun `il Telefono e la schermata di chiamata solo se sono di sistema`() {
        val base = SempreUsabili.FISSE + "com.truecaller" + "com.android.settings"
        val telefono = SempreUsabiliFaccende.TELEFONO_PER_NOME + "com.truecaller"
        val sistema: (String) -> Boolean? = { it == "com.google.android.dialer" || it == "com.android.incallui" || it == "com.android.settings" }
        val filtrate = SempreUsabiliFaccende.filtraTelefono(base, telefono, sistema)
        assertTrue("com.google.android.dialer" in filtrate)
        assertTrue("com.android.incallui" in filtrate)
        // Un'app installata a mano che si spaccia per il Telefono, o il Telefono
        // predefinito di un'altra ditta: fuori.
        assertFalse("com.samsung.android.dialer" in filtrate)
        assertFalse("com.truecaller" in filtrate)
        // Il resto delle Sessioni resta.
        assertTrue("com.android.settings" in filtrate)
        assertTrue("com.android.documentsui" in filtrate)
    }

    @Test
    fun `l'app degli SMS predefinita vale solo se è di sistema`() {
        val sistema: (String) -> Boolean? = { it == "com.google.android.apps.messaging" }
        assertEquals("com.google.android.apps.messaging", SempreUsabiliFaccende.smsValida("com.google.android.apps.messaging", sistema))
        assertNull(SempreUsabiliFaccende.smsValida("com.esempio.sms", sistema))
        assertNull(SempreUsabiliFaccende.smsValida(null, sistema))
        assertNull(SempreUsabiliFaccende.smsValida("  ", sistema))
    }

    @Test
    fun `Wallet, eWeLink, Tinaba, Google Foto e Contatti valgono se di sistema o installate dal Play Store`() {
        val sistema: (String) -> Boolean? = { p ->
            when (p) {
                "com.google.android.apps.walletnfcrel", "com.google.android.apps.photos" -> true
                "com.google.android.contacts" -> null // non installata
                else -> false
            }
        }
        val installatore: (String) -> String? = { p ->
            when (p) {
                "com.coolkit" -> SempreUsabiliFaccende.PLAY_STORE
                "it.tinaba.app" -> "com.google.android.packageinstaller" // installata a mano da un file
                else -> null
            }
        }
        val valide = SempreUsabiliFaccende.perNomeValide(sistema, installatore)
        assertEquals(
            setOf("com.google.android.apps.walletnfcrel", "com.google.android.apps.photos", "com.coolkit"),
            valide,
        )
    }

    @Test
    fun `la fotocamera dello scatto è quella di sistema - mai un'altra app che scatta`() {
        val sistema: (String) -> Boolean? = { it == "com.motorola.camera3" || it == "com.google.android.GoogleCamera" }
        assertEquals("com.motorola.camera3", ScattoInCorso.scegliFotocamera("com.motorola.camera3", listOf("com.motorola.camera3"), sistema))
        // La predefinita è un'app installata a mano: si apre quella di sistema.
        assertEquals(
            "com.google.android.GoogleCamera",
            ScattoInCorso.scegliFotocamera("com.esempio.camera", listOf("com.esempio.camera", "com.google.android.GoogleCamera"), sistema),
        )
        // Nessuna scelta (la finestra "android"): la prima di sistema.
        assertEquals("com.motorola.camera3", ScattoInCorso.scegliFotocamera("android", listOf("com.esempio.camera", "com.motorola.camera3"), sistema))
        // Nessuna fotocamera di sistema: niente foto.
        assertNull(ScattoInCorso.scegliFotocamera(null, listOf("com.esempio.camera"), sistema))
    }

    @Test
    fun `mentre si scatta, solo la fotocamera scelta resta usabile, e per poco`() {
        ScattoInCorso.inizia("com.motorola.camera3", adesso = 1_000L)
        assertEquals(setOf("com.motorola.camera3"), ScattoInCorso.attuali(1_000L + 60_000L))
        assertTrue(ScattoInCorso.attuali(1_000L + 11 * 60_000L).isEmpty())
        ScattoInCorso.finisce()
        assertTrue(ScattoInCorso.attuali(1_000L).isEmpty())
    }
}
