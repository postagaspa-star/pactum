package eu.stgm.pactum.figlio.sessione

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (0.11) Le app che la barriera non copre mai: Pactum, il sistema, il
 * Telefono e le emergenze, il menu della SIM, la Home SCELTA (non tutte quelle
 * installate), le tastiere attive, il servizio delle password, la fotocamera
 * DI SISTEMA, la scelta dei file e quella delle foto. Non l'assistente: l'app
 * Google intera si copre come le altre.
 */
class SempreUsabiliTest {

    private val pixel = "com.google.android.apps.nexuslauncher"
    private val nova = "com.teslacoilsw.launcher"
    private val gioco = "com.esempio.launcher.gioco"

    @Test
    fun `Pactum, il sistema, il Telefono, le emergenze e il menu della SIM ci sono sempre`() {
        for (app in listOf(
            "eu.stgm.pactum.figlio",
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.server.telecom",
            "com.google.android.dialer",
            "com.android.stk",
            "com.android.stk2",
            "com.android.emergency",
            "com.google.android.apps.safetyhub",
            "com.android.cellbroadcastreceiver",
            "com.google.android.permissioncontroller",
            "com.google.android.packageinstaller",
        )) {
            assertTrue(app, app in SempreUsabili.FISSE)
        }
    }

    @Test
    fun `le finestre Condividi e Apri con sono sempre usabili`() {
        assertTrue("android" in SempreUsabili.FISSE)
        assertTrue("com.android.intentresolver" in SempreUsabili.FISSE)
    }

    @Test
    fun `la scelta dei file e quella delle foto del sistema sono sempre usabili`() {
        for (app in listOf(
            "com.android.documentsui",
            "com.google.android.documentsui",
            "com.android.providers.media",
            "com.android.providers.media.module",
            "com.google.android.providers.media.module",
        )) {
            assertTrue(app, app in SempreUsabili.FISSE)
        }
        // Un'app dei file qualunque, o Drive, no.
        assertFalse("com.google.android.apps.nbu.files" in SempreUsabili.FISSE)
        assertFalse("com.google.android.apps.docs" in SempreUsabili.FISSE)
        assertFalse("com.google.android.apps.photos" in SempreUsabili.FISSE)
    }

    private val googleCamera = "com.google.android.GoogleCamera"
    private val samsungCamera = "com.sec.android.app.camera"
    private val openCamera = "net.sourceforge.opencamera"
    private val snapchat = "com.snapchat.android"
    private val diSistema: (String) -> Boolean = { it == googleCamera || it == samsungCamera }

    @Test
    fun `la fotocamera predefinita, se e' di sistema`() {
        assertEquals(setOf(googleCamera), SempreUsabili.fotocamereDaUsare(googleCamera, emptyList(), diSistema))
        // Scelta come fotocamera un'app installata dal ragazzo: si copre come le altre.
        assertTrue(SempreUsabili.fotocamereDaUsare(snapchat, emptyList(), diSistema).isEmpty())
        assertTrue(SempreUsabili.fotocamereDaUsare(openCamera, emptyList(), diSistema).isEmpty())
    }

    @Test
    fun `senza una fotocamera scelta, quelle di sistema tra le candidate`() {
        val candidate = listOf(googleCamera, samsungCamera, snapchat)
        assertEquals(setOf(googleCamera, samsungCamera), SempreUsabili.fotocamereDaUsare("android", candidate, diSistema))
        assertEquals(setOf(googleCamera, samsungCamera), SempreUsabili.fotocamereDaUsare(null, candidate, diSistema))
        assertTrue(SempreUsabili.fotocamereDaUsare(null, emptyList(), diSistema).isEmpty())
        // La finestra di scelta stessa non è una fotocamera.
        assertTrue(SempreUsabili.fotocamereDaUsare(null, listOf("android", ""), { true }).isEmpty())
    }

    @Test
    fun `l'app Google e l'assistente non sono fissi`() {
        for (app in listOf("com.google.android.googlequicksearchbox", "com.google.android.apps.bard", "com.samsung.android.bixby.agent")) {
            assertFalse(app, app in SempreUsabili.FISSE)
        }
    }

    @Test
    fun `nessuna schermata Home e' fissa - vale quella scelta`() {
        for (home in listOf(pixel, nova, "com.sec.android.app.launcher", "com.miui.home", "com.motorola.launcher3")) {
            assertFalse(home, home in SempreUsabili.FISSE)
        }
    }

    @Test
    fun `con una Home scelta, solo quella`() {
        // Un'altra "Home" installata (un gioco che si spaccia per Home) non diventa usabile.
        assertEquals(setOf(nova), SempreUsabili.homeDaUsare(nova, listOf(pixel, nova, gioco)))
    }

    @Test
    fun `senza una Home scelta, tutte quelle installate - Esci deve portare in un posto libero`() {
        assertEquals(setOf(pixel, nova), SempreUsabili.homeDaUsare(null, listOf(pixel, nova)))
        // "android" = la finestra di scelta: Android chiede ogni volta.
        assertEquals(setOf(pixel, nova), SempreUsabili.homeDaUsare("android", listOf(pixel, nova)))
        assertEquals(setOf(pixel), SempreUsabili.homeDaUsare("  ", listOf(pixel)))
    }

    @Test
    fun `il pacchetto da come lo scrivono le Impostazioni`() {
        // La tastiera in uso, il servizio delle password: "pacchetto/classe".
        assertEquals(
            "com.google.android.inputmethod.latin",
            SempreUsabili.pacchettoDaComponente("com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"),
        )
        assertEquals("com.x8bit.bitwarden", SempreUsabili.pacchettoDaComponente("com.x8bit.bitwarden/.Autofill.AutofillService"))
        assertEquals("com.solo.pacchetto", SempreUsabili.pacchettoDaComponente("com.solo.pacchetto"))
        assertNull(SempreUsabili.pacchettoDaComponente(null))
        assertNull(SempreUsabili.pacchettoDaComponente(""))
        assertNull(SempreUsabili.pacchettoDaComponente("/.SoloClasse"))
    }
}
