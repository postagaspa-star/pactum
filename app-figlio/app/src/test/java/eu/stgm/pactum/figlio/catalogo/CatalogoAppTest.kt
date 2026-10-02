package eu.stgm.pactum.figlio.catalogo

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (02/10, revisione completa) Le categorie delle app: prima le liste scritte
 * da noi (CatalogoApp.LISTE, riportate in docs/categorie.md), poi i giochi
 * dichiarati; tutto il resto in "Altre app". La categoria che un'app dichiara
 * di sé vale solo per i giochi: Firefox si dichiara "social" e finiva nei
 * Social. La stessa categoria vale nella valutazione, in Oggi e nella
 * fotografia per il genitore.
 */
class CatalogoAppTest {

    /** Tutto quello che un'app può dichiarare di sé, compreso "non trovata" (null). */
    private val dichiarazioni = listOf(
        null,
        ApplicationInfo.CATEGORY_UNDEFINED,
        ApplicationInfo.CATEGORY_SOCIAL,
        ApplicationInfo.CATEGORY_GAME,
        ApplicationInfo.CATEGORY_VIDEO,
        ApplicationInfo.CATEGORY_AUDIO,
        ApplicationInfo.CATEGORY_PRODUCTIVITY,
    )

    /** [pacchetti] stanno in [attesa] qualunque cosa dichiarino di sé. */
    private fun sempreIn(attesa: String, vararg pacchetti: String) {
        pacchetti.forEach { pacchetto ->
            dichiarazioni.forEach { dichiarata ->
                assertEquals("$pacchetto (dichiara $dichiarata)", attesa, CatalogoApp.categoriaDi(pacchetto, dichiarata))
            }
        }
    }

    @Test
    fun `i browser sono in Altre app, anche se si dichiarano social come Firefox`() {
        assertEquals(CatalogoApp.CAT_ALTRO, CatalogoApp.categoriaDi("org.mozilla.firefox", ApplicationInfo.CATEGORY_SOCIAL))
        sempreIn(
            CatalogoApp.CAT_ALTRO,
            "org.mozilla.firefox", // Firefox
            "com.android.chrome", // Chrome
            "com.brave.browser", // Brave
            "com.microsoft.emmx", // Edge
            "com.sec.android.app.sbrowser", // Samsung Internet
            "com.opera.browser", // Opera
            "com.opera.gx", // Opera GX, il browser "per giocatori"
            "com.duckduckgo.mobile.android", // DuckDuckGo
            "com.vivaldi.browser", // Vivaldi
        )
    }

    @Test
    fun `un'app sconosciuta che si dichiara social, video o musica e' in Altre app`() {
        listOf(
            ApplicationInfo.CATEGORY_SOCIAL,
            ApplicationInfo.CATEGORY_VIDEO,
            ApplicationInfo.CATEGORY_AUDIO,
            ApplicationInfo.CATEGORY_UNDEFINED,
            ApplicationInfo.CATEGORY_PRODUCTIVITY,
        ).forEach { dichiarata ->
            assertEquals("dichiara $dichiarata", CatalogoApp.CAT_ALTRO, CatalogoApp.categoriaDi("com.esempio.sconosciuta", dichiarata))
        }
        // Un'app che non si trova (disinstallata nel frattempo).
        assertEquals(CatalogoApp.CAT_ALTRO, CatalogoApp.categoriaDi("com.esempio.sconosciuta", null))
    }

    @Test
    fun `un'app sconosciuta che si dichiara un gioco e' in Giochi`() {
        assertEquals(CatalogoApp.CAT_GIOCHI, CatalogoApp.categoriaDi("com.esempio.gioco", ApplicationInfo.CATEGORY_GAME))
    }

    @Test
    fun `Instagram, TikTok, YouTube e Twitch sono social, qualunque cosa dichiarino`() {
        sempreIn(
            CatalogoApp.CAT_SOCIAL,
            "com.instagram.android", // Instagram
            "com.zhiliaoapp.musically", // TikTok
            "com.zhiliaoapp.musically.go", // TikTok Lite
            "com.google.android.youtube", // YouTube (dichiara "video")
            "tv.twitch.android.app", // Twitch
            "com.dailymotion.dailymotion", // Dailymotion (02/10, Andrea)
            "com.rumble.battles", // Rumble
            "com.snapchat.android", // Snapchat
            "com.discord", // Discord
        )
    }

    @Test
    fun `Netflix e le altre piattaforme sono video, anche se non lo dichiarano`() {
        sempreIn(
            CatalogoApp.CAT_VIDEO,
            "com.netflix.mediaclient", // Netflix
            "com.amazon.avod.thirdpartyclient", // Prime Video
            "it.rainet", // RaiPlay
            "com.vimeo.android.videoapp", // Vimeo (02/10, Andrea)
            "it.fabbricadigitale.android.videomediaset", // Mediaset Infinity
            "com.dazn", // DAZN
        )
    }

    @Test
    fun `Spotify e YouTube Music sono musica`() {
        sempreIn(
            CatalogoApp.CAT_MUSICA,
            "com.spotify.music", // Spotify
            "com.google.android.apps.youtube.music", // YouTube Music: musica, non social come YouTube
        )
    }

    @Test
    fun `i giochi piu' comuni sono giochi anche se non si dichiarano`() {
        sempreIn(
            CatalogoApp.CAT_GIOCHI,
            "com.supercell.brawlstars", // Brawl Stars
            "com.supercell.clashroyale", // Clash Royale
            "com.mojang.minecraftpe", // Minecraft
            "com.roblox.client", // Roblox
            "com.epicgames.fortnite", // Fortnite
            "com.miHoYo.GenshinImpact", // Genshin Impact
        )
    }

    @Test
    fun `WhatsApp e le altre app di messaggi non sono in nessuna categoria`() {
        assertEquals(CatalogoApp.CAT_ALTRO, CatalogoApp.categoriaDi("com.whatsapp", ApplicationInfo.CATEGORY_SOCIAL))
        sempreIn(
            CatalogoApp.CAT_ALTRO,
            "com.whatsapp", "com.whatsapp.w4b",
            "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram",
            "com.facebook.orca", "com.facebook.mlite",
            "org.thoughtcrime.securesms",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.viber.voip",
            "com.tencent.mm",
            "jp.naver.line.android",
            "ch.threema.app",
            "com.skype.raider",
        )
    }

    @Test
    fun `le liste usano le cinque categorie del contratto`() {
        assertEquals(CatalogoApp.CATEGORIE.toSet(), CatalogoApp.LISTE.keys)
    }

    @Test
    fun `nessuna app sta in due liste`() {
        val tutte = CatalogoApp.LISTE.values.flatten()
        val doppioni = tutte.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue("in due liste: $doppioni", doppioni.isEmpty())
    }

    @Test
    fun `i nomi dei pacchetti sono scritti come li scrive Android`() {
        val pacchetto = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        CatalogoApp.LISTE.values.flatten().forEach { assertTrue("pacchetto scritto male: '$it'", pacchetto.matches(it)) }
    }
}
