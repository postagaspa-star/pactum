package eu.stgm.pactum.figlio.sessione

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (0.11) Quando la barriera di una Sessione copre l'app in primo piano. La
 * regola: si copre solo se tutto è certo; nel dubbio, mai.
 */
class GuardiaSessioneTest {

    private val min = 60_000L
    private val inizio = 1_790_000_000_000L
    private val fine = inizio + 60 * min
    private val adesso = inizio + 10 * min

    private val classeViva = "eu.spaggiari.classevivafamiglia"
    private val calcolatrice = "com.google.android.calculator"
    private val instagram = "com.instagram.android"
    private val giocoApk = "com.esempio.gioco"

    private val studio = SessioneAttiva(
        svoltaId = 12,
        sessioneId = 3,
        nome = "Studio",
        app = setOf(classeViva, calcolatrice),
        nomi = emptyMap(),
        inizio = inizio,
        fine = fine,
    )

    private val home = "com.google.android.apps.nexuslauncher"
    private val sempre = setOf(
        "eu.stgm.pactum.figlio",
        home,
        "com.android.settings",
        "com.google.android.dialer",
        "com.google.android.inputmethod.latin",
        "com.android.systemui",
        "com.android.phone",
    )

    private fun situazione(
        primoPiano: String?,
        sessione: SessioneAttiva? = studio,
        quando: Long = adesso,
        schermo: Boolean = true,
        sbloccato: Boolean = true,
        mostraSopra: Boolean = true,
        accessoUso: Boolean = true,
        chiamata: Boolean = false,
        conta: (String) -> Boolean? = { true },
        apk: (String) -> Boolean? = { false },
        classe: String? = null,
    ) = SituazioneBarriera(
        sessione = sessione,
        adesso = quando,
        primoPiano = primoPiano,
        schermoAcceso = schermo,
        sbloccato = sbloccato,
        mostraSopra = mostraSopra,
        accessoUso = accessoUso,
        inChiamata = chiamata,
        sempreUsabili = sempre,
        contaNellUso = conta,
        nelGruppoApk = apk,
        classe = classe,
    )

    private fun decidi(s: SituazioneBarriera) = GuardiaSessione.decidi(s)

    @Test
    fun `un'app fuori dalla sessione si copre`() {
        assertEquals(DecisioneBarriera(true, MotivoBarriera.FUORI_SESSIONE), decidi(situazione(instagram)))
    }

    @Test
    fun `le app della sessione non si coprono`() {
        assertEquals(DecisioneBarriera(false, MotivoBarriera.NELLA_SESSIONE), decidi(situazione(classeViva)))
        assertEquals(DecisioneBarriera(false, MotivoBarriera.NELLA_SESSIONE), decidi(situazione(calcolatrice)))
    }

    @Test
    fun `Pactum, la Home, le Impostazioni, il Telefono, la tastiera e il sistema non si coprono mai`() {
        for (app in sempre) {
            assertEquals(app, DecisioneBarriera(false, MotivoBarriera.SEMPRE_USABILE), decidi(situazione(app)))
        }
    }

    @Test
    fun `durante una chiamata niente barriera, nemmeno su un'app fuori`() {
        assertEquals(DecisioneBarriera(false, MotivoBarriera.IN_CHIAMATA), decidi(situazione(instagram, chiamata = true)))
    }

    @Test
    fun `schermo spento o telefono bloccato, niente barriera`() {
        assertEquals(MotivoBarriera.SCHERMO_SPENTO, decidi(situazione(instagram, schermo = false)).motivo)
        assertEquals(MotivoBarriera.BLOCCATO, decidi(situazione(instagram, sbloccato = false)).motivo)
        assertFalse(decidi(situazione(instagram, schermo = false)).copri)
        assertFalse(decidi(situazione(instagram, sbloccato = false)).copri)
    }

    @Test
    fun `senza Mostra sopra le altre app o senza accesso ai dati di utilizzo, niente barriera`() {
        assertEquals(DecisioneBarriera(false, MotivoBarriera.SENZA_MOSTRA_SOPRA), decidi(situazione(instagram, mostraSopra = false)))
        assertEquals(DecisioneBarriera(false, MotivoBarriera.SENZA_ACCESSO_USO), decidi(situazione(instagram, accessoUso = false)))
    }

    @Test
    fun `senza sessione, o a sessione finita, niente barriera`() {
        assertEquals(DecisioneBarriera(false, MotivoBarriera.NESSUNA_SESSIONE), decidi(situazione(instagram, sessione = null)))
        // La fine è esclusa: all'istante della fine la sessione non c'è più.
        assertEquals(DecisioneBarriera(false, MotivoBarriera.SESSIONE_FINITA), decidi(situazione(instagram, quando = fine)))
        assertEquals(DecisioneBarriera(false, MotivoBarriera.SESSIONE_FINITA), decidi(situazione(instagram, quando = fine + min)))
        assertTrue(decidi(situazione(instagram, quando = fine - 1)).copri)
    }

    @Test
    fun `una sessione che per l'orologio non e' ancora iniziata non copre`() {
        val troppoPresto = inizio - MemoriaSessioni.TOLLERANZA_INIZIO_MS - 1
        assertFalse(decidi(situazione(instagram, quando = troppoPresto)).copri)
        // Qualche secondo prima dell'inizio del server sì: l'orologio del server può essere avanti.
        assertTrue(decidi(situazione(instagram, quando = inizio - 30_000)).copri)
    }

    @Test
    fun `l'app in primo piano che non si sa non si copre`() {
        assertEquals(DecisioneBarriera(false, MotivoBarriera.PRIMO_PIANO_IGNOTO), decidi(situazione(null)))
        assertEquals(DecisioneBarriera(false, MotivoBarriera.PRIMO_PIANO_IGNOTO), decidi(situazione("  ")))
    }

    @Test
    fun `gruppo apk - un'app installata da file e' nella sessione`() {
        val conApk = studio.copy(app = setOf(classeViva, AppDellaSessione.GRUPPO_APK))
        val decisione = decidi(situazione(giocoApk, sessione = conApk, apk = { it == giocoApk }))
        assertEquals(DecisioneBarriera(false, MotivoBarriera.NELLA_SESSIONE), decisione)
    }

    @Test
    fun `gruppo apk - un'app del Play Store resta fuori`() {
        val conApk = studio.copy(app = setOf(classeViva, AppDellaSessione.GRUPPO_APK))
        assertTrue(decidi(situazione(instagram, sessione = conApk, apk = { false })).copri)
    }

    @Test
    fun `gruppo apk - se non si sa da dove viene un'app, non si copre`() {
        val conApk = studio.copy(app = setOf(AppDellaSessione.GRUPPO_APK))
        assertEquals(DecisioneBarriera(false, MotivoBarriera.INCERTO), decidi(situazione(instagram, sessione = conApk, apk = { null })))
    }

    @Test
    fun `senza gruppo apk, un'app installata da file resta fuori`() {
        assertTrue(decidi(situazione(giocoApk, apk = { true })).copri)
    }

    @Test
    fun `i pezzi di sistema senza icona non si coprono (e non contano nell'uso)`() {
        val gms = "com.google.android.gms"
        assertEquals(DecisioneBarriera(false, MotivoBarriera.NON_CONTA), decidi(situazione(gms, conta = { it != gms })))
        // Non si sa se conta: non si copre.
        assertEquals(DecisioneBarriera(false, MotivoBarriera.NON_CONTA), decidi(situazione(gms, conta = { null })))
    }

    @Test
    fun `un errore nel decidere non copre niente`() {
        val rotto = situazione(instagram, conta = { throw IllegalStateException("PackageManager morto") })
        assertEquals(DecisioneBarriera(false, MotivoBarriera.INCERTO), decidi(rotto))
    }

    @Test
    fun `una sessione senza app non copre niente`() {
        assertFalse(decidi(situazione(instagram, sessione = studio.copy(app = emptySet()))).copri)
    }

    @Test
    fun `la decisione dell'app della sessione - dentro, fuori, non si sa`() {
        val lista = listOf(classeViva, AppDellaSessione.GRUPPO_APK)
        assertEquals(true, AppDellaSessione.ammette(lista, classeViva) { null })
        assertEquals(true, AppDellaSessione.ammette(lista, giocoApk) { true })
        assertEquals(false, AppDellaSessione.ammette(lista, instagram) { false })
        assertNull(AppDellaSessione.ammette(lista, instagram) { null })
        assertEquals(false, AppDellaSessione.ammette(listOf(classeViva), instagram) { true })
    }

    @Test
    fun `il gruppo apk - di sistema no, nessun negozio, il dubbio resta dubbio`() {
        // Di sistema, o di sistema aggiornata: mai, chiunque l'abbia installata.
        assertEquals(false, AppDellaSessione.nelGruppoApk(diSistema = true, installatore = null, installatoreLetto = true))
        assertEquals(false, AppDellaSessione.nelGruppoApk(diSistema = true, installatore = "com.android.packageinstaller", installatoreLetto = true))
        assertEquals(false, AppDellaSessione.nelGruppoApk(diSistema = false, installatore = "com.android.vending", installatoreLetto = true))
        assertEquals(false, AppDellaSessione.nelGruppoApk(diSistema = false, installatore = "com.google.android.feedback", installatoreLetto = true))
        // Un file APK aperto (l'installatore del sistema), adb, o nessun installatore.
        assertEquals(true, AppDellaSessione.nelGruppoApk(diSistema = false, installatore = "com.google.android.packageinstaller", installatoreLetto = true))
        assertEquals(true, AppDellaSessione.nelGruppoApk(diSistema = false, installatore = null, installatoreLetto = true))
        assertEquals(true, AppDellaSessione.nelGruppoApk(diSistema = false, installatore = "com.android.shell", installatoreLetto = true))
        assertNull(AppDellaSessione.nelGruppoApk(diSistema = null, installatore = null, installatoreLetto = true))
        assertNull(AppDellaSessione.nelGruppoApk(diSistema = false, installatore = null, installatoreLetto = false))
    }

    @Test
    fun `gruppo apk - da dove viene l'app, con installatori finti`() {
        // Da un file: l'installatore di pacchetti del sistema (di qualsiasi marca), adb, nessuno.
        val daFile = listOf(
            null,
            "",
            "  ",
            "com.android.shell",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.samsung.android.packageinstaller",
            "COM.GOOGLE.ANDROID.PACKAGEINSTALLER",
        )
        for (installatore in daFile) {
            assertTrue("da file: $installatore", AppDellaSessione.installatoreDaFile(installatore))
            assertEquals("da file: $installatore", true, AppDellaSessione.nelGruppoApk(false, installatore, installatoreLetto = true))
        }
        // Da un negozio, qualunque: fuori. E anche un'app che installa da sé (non è il sistema).
        val daNegozio = listOf(
            "com.android.vending",
            "com.sec.android.app.samsungapps",
            "com.motorola.appstore",
            "com.huawei.appmarket",
            "com.amazon.venezia",
            "org.fdroid.fdroid",
            "com.aurora.store",
            "com.xiaomi.market",
            "com.android.chrome",
        )
        for (installatore in daNegozio) {
            assertFalse("negozio: $installatore", AppDellaSessione.installatoreDaFile(installatore))
            assertEquals("negozio: $installatore", false, AppDellaSessione.nelGruppoApk(false, installatore, installatoreLetto = true))
        }
    }

    // --- chi non sa ancora della sessione, le chiamate, quello che un'app apre ------------

    @Test
    fun `una sessione che il ragazzo non sa partita non copre niente`() {
        val nonDetta = studio.copy(annunciata = false)
        assertEquals(DecisioneBarriera(false, MotivoBarriera.NON_ANNUNCIATA), decidi(situazione(instagram, sessione = nonDetta)))
        // Detta: si copre.
        assertTrue(decidi(situazione(instagram, sessione = nonDetta.copy(annunciata = true))).copri)
    }

    @Test
    fun `la schermata di una chiamata via internet non si copre mai`() {
        val whatsapp = "com.whatsapp"
        val schermateChiamata = listOf(
            "com.whatsapp.voipcalling.VoipActivityV2",
            "com.whatsapp.calling.callgrid.view.CallGridActivity",
            "org.telegram.ui.VoIPFragment",
            "org.telegram.messenger.voip.VoIPService",
            "com.google.android.apps.tachyon.call.CallActivity",
            "com.esempio.chat.calls.ui.CallActivity",
            "com.skype.android.app.calling.InCallActivity",
            "us.zoom.videomeetings.ConfActivityNormal",
            "com.instagram.rtc.activity.RtcCallActivity",
            "com.facebook.rtc.activities.WebrtcIncallFragmentHostActivity",
        )
        for (classe in schermateChiamata) {
            assertEquals(classe, DecisioneBarriera(false, MotivoBarriera.IN_CHIAMATA), decidi(situazione(whatsapp, classe = classe)))
        }
        // La chat di WhatsApp, fuori dalla sessione: si copre.
        assertTrue(decidi(situazione(whatsapp, classe = "com.whatsapp.Conversation")).copri)
        assertTrue(decidi(situazione(whatsapp, classe = "com.whatsapp.home.ui.HomeActivity")).copri)
    }

    @Test
    fun `Viber si chiama voip, ma solo le sue chiamate sono chiamate`() {
        val viber = "com.viber.voip"
        assertTrue(decidi(situazione(viber, classe = "com.viber.voip.HomeActivity")).copri)
        assertTrue(decidi(situazione(viber, classe = "com.viber.voip.messages.ui.ConversationActivity")).copri)
        assertFalse(decidi(situazione(viber, classe = "com.viber.voip.calls.ui.VideoCallActivity")).copri)
    }

    @Test
    fun `una pagina web aperta da un'app nella scheda del browser fa parte di quell'app`() {
        val chrome = "com.android.chrome"
        val decisione = decidi(situazione(chrome, classe = "org.chromium.chrome.browser.customtabs.CustomTabActivity"))
        assertEquals(DecisioneBarriera(false, MotivoBarriera.PARTE_DI_UN_APP), decisione)
        // Firefox e Samsung Internet le chiamano allo stesso modo.
        assertFalse(decidi(situazione("org.mozilla.firefox", classe = "org.mozilla.fenix.customtabs.ExternalAppBrowserActivity")).copri)
        assertFalse(decidi(situazione("com.sec.android.app.sbrowser", classe = "com.sec.android.app.sbrowser.customtab.CustomTabActivity")).copri)
        // Chrome aperto da solo, fuori dalla sessione: si copre.
        assertTrue(decidi(situazione(chrome, classe = "com.google.android.apps.chrome.Main")).copri)
        assertTrue(decidi(situazione(chrome, classe = "org.chromium.chrome.browser.ChromeTabbedActivity")).copri)
    }

    @Test
    fun `i fogli del Play Store dentro un'app non si coprono, il Play Store si'`() {
        val play = "com.android.vending"
        val fogli = listOf(
            "com.google.android.finsky.inappreviewdialog.InAppReviewActivity",
            "com.google.android.finsky.appupdate.AppUpdateActivity",
            "com.google.android.finsky.billing.acquire.LockToPortraitUiBuilderHostActivity",
            "com.google.android.finsky.billing.iab.InAppBillingActivity",
        )
        for (classe in fogli) {
            assertEquals(classe, DecisioneBarriera(false, MotivoBarriera.PARTE_DI_UN_APP), decidi(situazione(play, classe = classe)))
        }
        assertTrue(decidi(situazione(play, classe = "com.google.android.finsky.activities.MainActivity")).copri)
        // "billing" in un'altra app non vuol dire niente.
        assertTrue(decidi(situazione(instagram, classe = "com.instagram.billing.PurchaseActivity")).copri)
    }
}
