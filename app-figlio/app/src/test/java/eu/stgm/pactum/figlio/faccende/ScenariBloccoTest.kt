package eu.stgm.pactum.figlio.faccende

import eu.stgm.pactum.figlio.misura.Sessioni
import eu.stgm.pactum.figlio.sessione.GuardiaSessione
import eu.stgm.pactum.figlio.sessione.RitmoBarriera
import eu.stgm.pactum.figlio.sessione.SempreUsabili
import eu.stgm.pactum.figlio.sessione.SessioneAttiva
import eu.stgm.pactum.figlio.sessione.SituazioneBarriera
import eu.stgm.pactum.figlio.sessione.TracciaPrimoPiano
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (0.13) La barriera delle faccende giro per giro, come la fa girare
 * SorveglianzaFaccende (gli eventi d'uso, la guardia, il ritmo), senza
 * Android: il blocco che parte all'ora giusta, WhatsApp coperto anche durante
 * una chiamata, gli SMS e la fotocamera liberi, le app senza icona, le pagine
 * web, l'interruttore di sicurezza che non si fa scattare apposta, e una
 * sessione in corso che si fa da parte solo dove il blocco copre già.
 */
class ScenariBloccoTest {

    private val min = 60_000L
    private val t0 = 1_790_000_000_000L

    private val pactum = "eu.stgm.pactum.figlio"
    private val home = "com.google.android.apps.nexuslauncher"
    private val messaggi = "com.google.android.apps.messaging"
    private val whatsapp = "com.whatsapp"
    private val classeViva = "eu.spaggiari.classevivafamiglia"
    private val fotocamera = "com.google.android.GoogleCamera"
    private val instagram = "com.instagram.android"
    private val tiktok = "com.zhiliaoapp.musically"
    private val telefonoDiSistema = "com.google.android.dialer"
    private val wallet = "com.google.android.apps.walletnfcrel"
    private val tinaba = "it.tinaba.app"
    private val chrome = "com.android.chrome"
    private val cassaforte = "com.esempio.calcolatrice.cassaforte"
    private val servizi = "com.google.android.gms"

    private val sempre = SempreUsabiliFaccende.unisci(
        base = SempreUsabili.FISSE + home + fotocamera,
        ruoli = emptySet(),
        smsPredefinita = messaggi,
        pactum = pactum,
    )

    /** Le app senza icona fra le app (servizi, cassaforte); di sistema: i servizi, il Telefono, la fotocamera, la Home. */
    private val senzaIcona = setOf(servizi, cassaforte)
    private val diSistema = setOf(servizi, telefonoDiSistema, fotocamera, home, "com.android.settings", messaggi)

    private val studio = SessioneAttiva(
        svoltaId = 12,
        sessioneId = 3,
        nome = "Studio",
        app = setOf(classeViva),
        nomi = emptyMap(),
        inizio = t0,
        fine = t0 + 4 * 60 * min,
    )

    private fun ora(t: Long) = Istante(t, t - t0 + 3_600_000L, 1)

    /** Il telefono: gli eventi d'uso e un giro della barriera circa ogni secondo. */
    private inner class Telefono(var memoria: MemoriaBlocco, inizio: Long = t0 + min) {
        private val traccia = TracciaPrimoPiano()
        private val ritmo = RitmoBarriera()
        var orologio = inizio
        val aperture = mutableListOf<String>()
        val apertureSessione = mutableListOf<String>()

        /** La barriera aperta arriva sullo schermo (false = Android non la lascia comparire). */
        var barrieraCompare = true

        fun apre(pacchetto: String, classe: String = "$pacchetto.MainActivity") {
            orologio += 200
            traccia.evento(Sessioni.RIPRESA, pacchetto, orologio, classe)
        }

        fun giro(): Boolean {
            orologio += 1_000
            val primoPiano = traccia.attuale
            val bloccato = memoria.attivoAdesso(ora(orologio))
            val decisione = GuardiaFaccende.decidi(
                SituazioneFaccende(
                    bloccoAttivo = bloccato,
                    primoPiano = primoPiano,
                    schermoAcceso = true,
                    sbloccato = true,
                    mostraSopra = true,
                    accessoUso = true,
                    sempreUsabili = sempre,
                    contaNellUso = { it !in senzaIcona },
                    diSistema = { it in diSistema },
                    classe = traccia.classe,
                    precedente = traccia.precedente,
                    home = setOf(home),
                ),
            )
            // La barriera della sessione, con lo stesso stato: si fa da parte solo dove il blocco copre già.
            val sessione = GuardiaSessione.decidi(
                SituazioneBarriera(
                    sessione = studio,
                    adesso = orologio,
                    primoPiano = primoPiano,
                    schermoAcceso = true,
                    sbloccato = true,
                    mostraSopra = true,
                    accessoUso = true,
                    inChiamata = false,
                    sempreUsabili = SempreUsabili.FISSE + home + fotocamera,
                    contaNellUso = { true },
                    nelGruppoApk = { false },
                    classe = traccia.classe,
                    copertaDalBlocco = bloccato && decisione.copri,
                ),
            )
            if (sessione.copri) apertureSessione += primoPiano.orEmpty()
            val apri = ritmo.passo(primoPiano, decisione.copri, orologio)
            if (apri) {
                aperture += primoPiano.orEmpty()
                if (barrieraCompare) {
                    apre(pactum, "eu.stgm.pactum.figlio.faccende.BarrieraFaccendeActivity")
                    ritmo.comparsa()
                }
            }
            return apri
        }

        /** Home: dalla barriera, o il ragazzo che esce (il servizio riguarda da capo). */
        fun home() {
            apre(home, "com.google.android.apps.nexuslauncher.NexusLauncherActivity")
            ritmo.azzera()
        }

        fun giri(quanti: Int): List<Boolean> = List(quanti) { giro() }
    }

    private val lavastoviglie = FaccendaDaFare(5, "Svuota la lavastoviglie", genitore = "Mamma")

    private fun bloccato() = MemoriaBlocco().conServer(BloccoDalServer(true, t0, null, listOf(lavastoviglie)), ora(t0), ora(t0), t0)

    @Test
    fun `WhatsApp col blocco - coperto al secondo giro`() {
        val telefono = Telefono(bloccato())
        telefono.apre(whatsapp, "com.whatsapp.HomeActivity")
        assertEquals(listOf(false, true), telefono.giri(2))
        assertEquals(listOf(whatsapp), telefono.aperture)
    }

    @Test
    fun `gli SMS, la fotocamera e Pactum - mai coperti`() {
        val telefono = Telefono(bloccato())
        for (app in listOf(messaggi, fotocamera, pactum, home, "com.android.settings")) {
            telefono.apre(app)
            assertTrue(app, telefono.giri(5).none { it })
        }
        assertTrue(telefono.aperture.isEmpty())
    }

    @Test
    fun `chiamata WhatsApp in corso - la sua schermata si copre, la chiamata continua sotto`() {
        val telefono = Telefono(bloccato())
        telefono.apre(whatsapp, "com.whatsapp.voipcalling.VoipActivityV2")
        assertEquals(listOf(false, true), telefono.giri(2))
        assertEquals(listOf(whatsapp), telefono.aperture)
    }

    @Test
    fun `chiamata in corso con TikTok davanti - coperto`() {
        val telefono = Telefono(bloccato())
        telefono.apre(telefonoDiSistema, "com.android.incallui.InCallActivity")
        assertTrue(telefono.giri(3).none { it })
        // Durante la chiamata il ragazzo passa a TikTok.
        telefono.apre(tiktok)
        assertTrue(telefono.giri(2).last())
        assertEquals(listOf(tiktok), telefono.aperture)
    }

    @Test
    fun `il Telefono di sistema durante una chiamata - libero`() {
        val telefono = Telefono(bloccato())
        telefono.apre(telefonoDiSistema, "com.android.incallui.InCallActivity")
        assertTrue(telefono.giri(10).none { it })
    }

    @Test
    fun `un'app senza icona che non è di sistema si copre, un servizio di sistema no`() {
        val telefono = Telefono(bloccato())
        telefono.apre(servizi, "com.google.android.gms.auth.UiActivity")
        assertTrue(telefono.giri(5).none { it })
        telefono.apre(cassaforte, "$cassaforte.Nascosta")
        assertTrue(telefono.giri(2).last())
        assertEquals(listOf(cassaforte), telefono.aperture)
    }

    @Test
    fun `una pagina web aperta da Tinaba è libera, la stessa ripresa dalle Recenti no`() {
        val scheda = "org.chromium.chrome.browser.customtabs.CustomTabActivity"
        val telefono = Telefono(bloccato())
        telefono.apre(tinaba)
        telefono.giri(2)
        telefono.apre(chrome, scheda)
        assertTrue(telefono.giri(5).none { it })
        // Home, poi la stessa scheda dalle Recenti.
        telefono.home()
        telefono.giro()
        telefono.apre(chrome, scheda)
        assertTrue(telefono.giri(2).last())
        assertEquals(listOf(chrome), telefono.aperture)
    }

    @Test
    fun `l'interruttore di sicurezza non si fa scattare apposta - TikTok, barriera, Home, di nuovo`() {
        val telefono = Telefono(bloccato())
        // Venti volte in meno di un minuto: ogni volta la barriera compare.
        repeat(20) {
            telefono.apre(tiktok)
            assertTrue("giro $it", telefono.giri(2).last())
            telefono.home()
            telefono.giro()
        }
        assertEquals(20, telefono.aperture.size)
    }

    @Test
    fun `se la barriera non riesce a comparire, l'interruttore si ferma - nessun giro a vuoto`() {
        val telefono = Telefono(bloccato())
        telefono.barrieraCompare = false
        repeat(20) {
            telefono.apre(tiktok)
            telefono.giri(2)
            telefono.home()
            telefono.giro()
        }
        assertTrue("al massimo ${RitmoBarriera.LANCI_MASSIMI} in un minuto", telefono.aperture.size <= RitmoBarriera.LANCI_MASSIMI)
    }

    @Test
    fun `in sessione, l'app della sessione si copre col blocco e la barriera della sessione tace`() {
        val telefono = Telefono(bloccato())
        telefono.apre(classeViva)
        assertEquals(listOf(false, true), telefono.giri(2))
        telefono.apre(instagram)
        telefono.giri(3)
        assertEquals(listOf(classeViva, instagram), telefono.aperture)
        assertTrue("la barriera della sessione non si apre dove il blocco copre", telefono.apertureSessione.isEmpty())
    }

    @Test
    fun `in sessione col blocco, Wallet resta coperto dalla sessione - vale il più stretto`() {
        val telefono = Telefono(bloccato())
        telefono.apre(wallet)
        telefono.giri(3)
        assertTrue("il blocco non copre Wallet", telefono.aperture.isEmpty())
        assertTrue("la sessione sì", telefono.apertureSessione.contains(wallet))
    }

    @Test
    fun `senza blocco, in sessione vale la barriera della sessione`() {
        val telefono = Telefono(MemoriaBlocco())
        telefono.apre(instagram)
        telefono.giri(3)
        assertTrue(telefono.aperture.isEmpty())
        assertTrue(telefono.apertureSessione.contains(instagram))
    }

    @Test
    fun `il blocco programmato parte da solo all'ora giusta, senza rete`() {
        val prossimo = t0 + 10 * min
        val memoria = MemoriaBlocco().conServer(BloccoDalServer(false, null, prossimo, listOf(lavastoviglie)), ora(t0), ora(t0), t0)
        val telefono = Telefono(memoria, inizio = prossimo - 5_000)
        telefono.apre(instagram)
        val giri = telefono.giri(10)
        assertFalse(giri.take(3).any { it })
        assertTrue(giri.any { it })
        assertEquals(listOf(instagram), telefono.aperture)
    }

    @Test
    fun `la foto arrivata non basta - si sblocca quando il server lo dice`() {
        val telefono = Telefono(bloccato())
        telefono.apre(instagram)
        assertEquals(listOf(false, true), telefono.giri(2))
        assertFalse(telefono.giro())
        telefono.apre(instagram)
        assertTrue(telefono.giri(3).any { it })
        telefono.memoria = telefono.memoria.conServer(
            BloccoDalServer(false, null, null, emptyList()),
            ora(telefono.orologio),
            ora(telefono.orologio),
            telefono.orologio,
        )
        telefono.apre(instagram)
        assertTrue(telefono.giri(5).none { it })
    }
}
