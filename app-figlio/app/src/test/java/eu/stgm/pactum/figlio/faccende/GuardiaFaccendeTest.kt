package eu.stgm.pactum.figlio.faccende

import eu.stgm.pactum.figlio.sessione.GuardiaSessione
import eu.stgm.pactum.figlio.sessione.MotivoBarriera
import eu.stgm.pactum.figlio.sessione.SempreUsabili
import eu.stgm.pactum.figlio.sessione.SessioneAttiva
import eu.stgm.pactum.figlio.sessione.SituazioneBarriera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (0.13) Quando la barriera delle faccende copre l'app in primo piano. Col
 * blocco restano usabili solo le app fondamentali; WhatsApp e le altre app di
 * messaggi si coprono, anche durante una chiamata; un'app senza icona si
 * copre se non è di sistema; una pagina web è libera solo se l'ha aperta
 * un'app dell'elenco; la barriera della sessione si fa da parte solo dove il
 * blocco copre già.
 */
class GuardiaFaccendeTest {

    private val pactum = "eu.stgm.pactum.figlio"
    private val home = "com.motorola.launcher3"
    private val smsPredefinita = "com.google.android.apps.messaging"
    private val whatsapp = "com.whatsapp"
    private val telegram = "org.telegram.messenger"
    private val instagram = "com.instagram.android"
    private val tiktok = "com.zhiliaoapp.musically"
    private val classeViva = "eu.spaggiari.classevivafamiglia"
    private val fotocamera = "com.motorola.camera3"
    private val impostazioni = "com.android.settings"
    private val telefono = "com.google.android.dialer"
    private val chrome = "com.android.chrome"
    private val tinaba = "it.tinaba.app"
    private val cassaforte = "com.esempio.calcolatrice.cassaforte"
    private val servizi = "com.google.android.gms"

    /** Quello che SempreUsabiliFaccende.leggi trova su un Motorola: le fisse, la Home, la fotocamera, l'SMS, il Telefono di sistema. */
    private val sempre: Set<String> = SempreUsabiliFaccende.unisci(
        base = SempreUsabili.FISSE + home + fotocamera + telefono,
        ruoli = setOf("com.google.android.contacts"),
        smsPredefinita = smsPredefinita,
        pactum = pactum,
    )

    private val diSistema: (String) -> Boolean? = { it in setOf(servizi, telefono, fotocamera, impostazioni, home) }

    private fun situazione(
        primoPiano: String?,
        blocco: Boolean = true,
        schermo: Boolean = true,
        sbloccato: Boolean = true,
        mostraSopra: Boolean = true,
        accessoUso: Boolean = true,
        conta: (String) -> Boolean? = { true },
        sistema: (String) -> Boolean? = diSistema,
        classe: String? = null,
        precedente: String? = null,
    ) = SituazioneFaccende(
        bloccoAttivo = blocco,
        primoPiano = primoPiano,
        schermoAcceso = schermo,
        sbloccato = sbloccato,
        mostraSopra = mostraSopra,
        accessoUso = accessoUso,
        sempreUsabili = sempre,
        contaNellUso = conta,
        diSistema = sistema,
        classe = classe,
        precedente = precedente,
        home = setOf(home),
    )

    private fun decidi(s: SituazioneFaccende) = GuardiaFaccende.decidi(s)

    @Test
    fun `WhatsApp si copre`() {
        assertEquals(DecisioneFaccende(true, MotivoFaccende.PRIMA_LE_FACCENDE), decidi(situazione(whatsapp)))
    }

    @Test
    fun `Telegram e Instagram si coprono`() {
        assertTrue(decidi(situazione(telegram)).copri)
        assertTrue(decidi(situazione(instagram)).copri)
    }

    @Test
    fun `un'app qualsiasi si copre`() {
        assertTrue(decidi(situazione("com.esempio.gioco")).copri)
    }

    @Test
    fun `l'app degli SMS predefinita non si copre`() {
        assertEquals(DecisioneFaccende(false, MotivoFaccende.SEMPRE_USABILE), decidi(situazione(smsPredefinita)))
    }

    @Test
    fun `le Impostazioni non si coprono`() {
        assertFalse(decidi(situazione(impostazioni)).copri)
    }

    @Test
    fun `la fotocamera di sistema non si copre`() {
        assertFalse(decidi(situazione(fotocamera)).copri)
    }

    @Test
    fun `Pactum, la Home, il Telefono e i Contatti non si coprono`() {
        for (app in listOf(pactum, home, telefono, "com.google.android.contacts")) {
            assertFalse(app, decidi(situazione(app)).copri)
        }
    }

    @Test
    fun `Wallet, eWeLink, Tinaba e Google Foto non si coprono`() {
        for (app in listOf("com.google.android.apps.walletnfcrel", "com.coolkit", tinaba, "com.google.android.apps.photos")) {
            assertFalse(app, decidi(situazione(app)).copri)
        }
    }

    // --- Le chiamate non spengono niente (decisione di Andrea) --------------

    @Test
    fun `WhatsApp durante una chiamata - coperto`() {
        assertTrue(decidi(situazione(whatsapp, classe = "com.whatsapp.Conversation")).copri)
    }

    @Test
    fun `la schermata di una chiamata WhatsApp - coperta`() {
        val decisione = decidi(situazione(whatsapp, classe = "com.whatsapp.voipcalling.VoipActivityV2"))
        assertEquals(DecisioneFaccende(true, MotivoFaccende.PRIMA_LE_FACCENDE), decisione)
    }

    @Test
    fun `il Telefono di sistema durante una chiamata - libero`() {
        val decisione = decidi(situazione(telefono, classe = "com.android.incallui.InCallActivity"))
        assertEquals(DecisioneFaccende(false, MotivoFaccende.SEMPRE_USABILE), decisione)
    }

    @Test
    fun `chiamata in corso con TikTok davanti - coperto`() {
        assertTrue(decidi(situazione(tiktok, classe = "com.ss.android.ugc.aweme.main.MainActivity")).copri)
    }

    @Test
    fun `un gioco con la chat vocale - coperto anche con l'audio in chiamata`() {
        assertTrue(decidi(situazione("com.discord", classe = "com.discord.main.MainActivity")).copri)
    }

    // --- Il resto -----------------------------------------------------------

    @Test
    fun `senza blocco non si copre niente`() {
        assertEquals(MotivoFaccende.NESSUN_BLOCCO, decidi(situazione(whatsapp, blocco = false)).motivo)
        assertFalse(decidi(situazione(whatsapp, blocco = false)).copri)
    }

    @Test
    fun `schermo spento o telefono bloccato - niente`() {
        assertEquals(MotivoFaccende.SCHERMO_SPENTO, decidi(situazione(whatsapp, schermo = false)).motivo)
        assertEquals(MotivoFaccende.BLOCCATO, decidi(situazione(whatsapp, sbloccato = false)).motivo)
    }

    @Test
    fun `senza i permessi non si copre`() {
        assertEquals(MotivoFaccende.SENZA_MOSTRA_SOPRA, decidi(situazione(whatsapp, mostraSopra = false)).motivo)
        assertEquals(MotivoFaccende.SENZA_ACCESSO_USO, decidi(situazione(whatsapp, accessoUso = false)).motivo)
    }

    @Test
    fun `chi c'è davanti non si sa - niente`() {
        assertEquals(MotivoFaccende.PRIMO_PIANO_IGNOTO, decidi(situazione(null)).motivo)
        assertEquals(MotivoFaccende.PRIMO_PIANO_IGNOTO, decidi(situazione("  ")).motivo)
    }

    @Test
    fun `i pezzi di sistema senza icona non si coprono`() {
        assertEquals(MotivoFaccende.NON_CONTA, decidi(situazione(servizi, conta = { false })).motivo)
    }

    @Test
    fun `un'app senza icona che non è di sistema si copre - la calcolatrice cassaforte`() {
        assertTrue(decidi(situazione(cassaforte, conta = { false })).copri)
        // Se non si sa se ha l'icona, conta lo stesso se è di sistema o no.
        assertTrue(decidi(situazione(cassaforte, conta = { null })).copri)
    }

    @Test
    fun `un'app senza icona di cui non si sa se è di sistema - non si copre`() {
        assertEquals(MotivoFaccende.INCERTO, decidi(situazione(cassaforte, conta = { false }, sistema = { null })).motivo)
    }

    @Test
    fun `una pagina web aperta da Tinaba, appena prima - libera`() {
        val decisione = decidi(
            situazione(chrome, classe = "org.chromium.chrome.browser.customtabs.CustomTabActivity", precedente = tinaba),
        )
        assertEquals(MotivoFaccende.PARTE_DI_UN_APP, decisione.motivo)
    }

    @Test
    fun `una pagina web aperta da un'app bloccata, dalle Recenti o senza un prima - coperta`() {
        val scheda = "org.chromium.chrome.browser.customtabs.CustomTabActivity"
        assertTrue(decidi(situazione(chrome, classe = scheda, precedente = whatsapp)).copri)
        assertTrue(decidi(situazione(chrome, classe = scheda, precedente = home)).copri)
        assertTrue(decidi(situazione(chrome, classe = scheda, precedente = "com.android.systemui")).copri)
        assertTrue(decidi(situazione(chrome, classe = scheda, precedente = null)).copri)
    }

    @Test
    fun `un errore dentro la decisione non copre`() {
        val decisione = decidi(situazione("com.esempio.gioco", conta = { error("rotto") }))
        assertEquals(DecisioneFaccende(false, MotivoFaccende.INCERTO), decisione)
    }

    // --- La sessione: si fa da parte solo dove il blocco copre già ----------

    private val studio = SessioneAttiva(
        svoltaId = 12,
        sessioneId = 3,
        nome = "Studio",
        app = setOf(classeViva),
        nomi = emptyMap(),
        inizio = 1_790_000_000_000L,
        fine = 1_790_000_000_000L + 3_600_000L,
    )

    private fun situazioneSessione(primoPiano: String, copertaDalBlocco: Boolean) = SituazioneBarriera(
        sessione = studio,
        adesso = studio.inizio + 60_000L,
        primoPiano = primoPiano,
        schermoAcceso = true,
        sbloccato = true,
        mostraSopra = true,
        accessoUso = true,
        inChiamata = false,
        sempreUsabili = SempreUsabili.FISSE,
        contaNellUso = { true },
        nelGruppoApk = { false },
        copertaDalBlocco = copertaDalBlocco,
    )

    /** Come SorveglianzaSessione: la sessione chiede alla guardia delle faccende. */
    private fun sessioneCoprirebbe(app: String): Boolean =
        GuardiaSessione.decidi(situazioneSessione(app, copertaDalBlocco = decidi(situazione(app)).copri)).copri

    @Test
    fun `dove il blocco copre già, la barriera della sessione si fa da parte`() {
        val decisione = GuardiaSessione.decidi(situazioneSessione(instagram, copertaDalBlocco = true))
        assertEquals(MotivoBarriera.BLOCCO_FACCENDE, decisione.motivo)
        assertFalse(sessioneCoprirebbe(instagram))
        assertFalse(sessioneCoprirebbe(classeViva))
    }

    @Test
    fun `dove il blocco lascia libero, vale la sessione - il più stretto dei due`() {
        // Wallet è libero col blocco ma non è nella sessione Studio: la sessione lo copre.
        assertFalse(decidi(situazione("com.google.android.apps.walletnfcrel")).copri)
        assertTrue(sessioneCoprirebbe("com.google.android.apps.walletnfcrel"))
    }

    @Test
    fun `senza blocco la barriera della sessione resta com'era`() {
        assertTrue(GuardiaSessione.decidi(situazioneSessione(instagram, copertaDalBlocco = false)).copri)
        assertFalse(GuardiaSessione.decidi(situazioneSessione(classeViva, copertaDalBlocco = false)).copri)
    }

    @Test
    fun `un'app della sessione si copre lo stesso col blocco - è più stretto`() {
        assertTrue(decidi(situazione(classeViva)).copri)
    }
}
