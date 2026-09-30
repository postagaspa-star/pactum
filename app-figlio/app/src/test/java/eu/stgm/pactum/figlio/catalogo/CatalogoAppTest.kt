package eu.stgm.pactum.figlio.catalogo

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * (0.9) Le categorie delle app: prima le eccezioni fisse decise da Andrea
 * (i messaggi in nessuna categoria, YouTube social), poi quella che l'app
 * dichiara. La stessa categoria vale nella valutazione, in Oggi e nella
 * fotografia per il genitore.
 */
class CatalogoAppTest {

    @Test
    fun `WhatsApp non e' in nessuna categoria, anche se si dichiara social`() {
        assertEquals(CatalogoApp.CAT_ALTRO, CatalogoApp.categoriaDi("com.whatsapp", ApplicationInfo.CATEGORY_SOCIAL))
        assertEquals(CatalogoApp.CAT_ALTRO, CatalogoApp.categoriaDi("com.whatsapp.w4b", ApplicationInfo.CATEGORY_UNDEFINED))
    }

    @Test
    fun `tutte le app di messaggi della lista stanno fuori dalle categorie`() {
        listOf(
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
        ).forEach { pacchetto ->
            assertEquals(pacchetto, CatalogoApp.CAT_ALTRO, CatalogoApp.categoriaDi(pacchetto, ApplicationInfo.CATEGORY_SOCIAL))
        }
    }

    @Test
    fun `YouTube e' social anche se si dichiara video`() {
        assertEquals(
            CatalogoApp.CAT_SOCIAL,
            CatalogoApp.categoriaDi("com.google.android.youtube", ApplicationInfo.CATEGORY_VIDEO),
        )
    }

    @Test
    fun `Instagram, Discord, Snapchat e TikTok restano social`() {
        listOf("com.instagram.android", "com.discord", "com.snapchat.android", "com.zhiliaoapp.musically").forEach {
            assertEquals(it, CatalogoApp.CAT_SOCIAL, CatalogoApp.categoriaDi(it, ApplicationInfo.CATEGORY_SOCIAL))
        }
    }

    @Test
    fun `un gioco resta gioco, e le altre categorie dichiarate valgono come prima`() {
        assertEquals(CatalogoApp.CAT_GIOCHI, CatalogoApp.categoriaDi("com.supercell.clashroyale", ApplicationInfo.CATEGORY_GAME))
        assertEquals(CatalogoApp.CAT_VIDEO, CatalogoApp.categoriaDi("com.netflix.mediaclient", ApplicationInfo.CATEGORY_VIDEO))
        assertEquals(CatalogoApp.CAT_MUSICA, CatalogoApp.categoriaDi("com.spotify.music", ApplicationInfo.CATEGORY_AUDIO))
        assertEquals(CatalogoApp.CAT_ALTRO, CatalogoApp.categoriaDi("com.android.chrome", ApplicationInfo.CATEGORY_UNDEFINED))
    }

    @Test
    fun `un'app che non si trova e' in Altre app, tranne le eccezioni`() {
        assertEquals(CatalogoApp.CAT_ALTRO, CatalogoApp.categoriaDi("com.sconosciuta", null))
        assertEquals(CatalogoApp.CAT_SOCIAL, CatalogoApp.categoriaDi("com.google.android.youtube", null))
    }
}
