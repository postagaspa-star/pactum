package eu.stgm.pactum.figlio.siti

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il filtro dei domini è ciò che decide cosa il genitore leggerà: se sbaglia
 * per eccesso la lista diventa illeggibile (40 righe di CDN), se sbaglia per
 * difetto sparisce un sito vero. Va provato.
 */
class DominiTest {

    @Test
    fun `aggrega i sottodomini sul dominio registrabile`() {
        assertEquals("youtube.com", Domini.dominioOsservabile("m.youtube.com"))
        assertEquals("youtube.com", Domini.dominioOsservabile("www.youtube.com"))
        assertEquals("instagram.com", Domini.dominioOsservabile("i.instagram.com"))
        assertEquals("wikipedia.org", Domini.dominioOsservabile("it.m.wikipedia.org"))
    }

    @Test
    fun `i CDN dei servizi finiscono sul marchio, non spariscono`() {
        // L'esempio letterale del contratto.
        assertEquals("instagram.com", Domini.dominioOsservabile("scontent.cdninstagram.com"))
        assertEquals("facebook.com", Domini.dominioOsservabile("static.xx.fbcdn.net"))
        assertEquals("youtube.com", Domini.dominioOsservabile("rr3---sn-abc.googlevideo.com"))
        assertEquals("tiktok.com", Domini.dominioOsservabile("v16-webapp.tiktokcdn.com"))
        assertEquals("whatsapp.com", Domini.dominioOsservabile("g.whatsapp.net"))
    }

    @Test
    fun `il rumore tecnico non entra nel registro`() {
        assertNull(Domini.dominioOsservabile("android.googleapis.com"))
        assertNull(Domini.dominioOsservabile("ssl.gstatic.com"))
        assertNull(Domini.dominioOsservabile("ad.doubleclick.net"))
        assertNull(Domini.dominioOsservabile("firebase-settings.crashlytics.com"))
        assertNull(Domini.dominioOsservabile("e123.akamaiedge.net"))
        assertNull(Domini.dominioOsservabile("d1234.cloudfront.net"))
        assertNull(Domini.dominioOsservabile("app-measurement.com"))
        assertNull(Domini.dominioOsservabile("ocsp.digicert.com"))
    }

    @Test
    fun `i servizi di sistema non gonfiano un dominio vero`() {
        // google.com è un sito vero e resta visibile...
        assertEquals("google.com", Domini.dominioOsservabile("www.google.com"))
        // ...ma la messaggistica di sistema e i controlli di rete no.
        assertNull(Domini.dominioOsservabile("mtalk.google.com"))
        assertNull(Domini.dominioOsservabile("clients3.google.com"))
        assertNull(Domini.dominioOsservabile("connectivitycheck.gstatic.com"))
    }

    @Test
    fun `i suffissi a due livelli tengono tre etichette`() {
        assertEquals("bbc.co.uk", Domini.dominioOsservabile("www.bbc.co.uk"))
        assertEquals("globo.com.br", Domini.dominioOsservabile("g1.globo.com.br"))
        assertEquals("repubblica.it", Domini.dominioOsservabile("www.repubblica.it"))
    }

    @Test
    fun `quello che non e un sito pubblico resta fuori`() {
        assertNull(Domini.dominioOsservabile("192.168.1.1"))
        assertNull(Domini.dominioOsservabile("nas.local"))
        assertNull(Domini.dominioOsservabile("router"))
        assertNull(Domini.dominioOsservabile("1.0.168.192.in-addr.arpa"))
        assertNull(Domini.dominioOsservabile(""))
    }

    @Test
    fun `maiuscole e punto finale non creano righe doppie`() {
        assertEquals("youtube.com", Domini.dominioOsservabile("M.YouTube.com."))
    }

    @Test
    fun `i resolver cifrati sono un segnale di cecita, non un sito`() {
        assertTrue(Domini.eResolverCifrato("mozilla.cloudflare-dns.com"))
        assertTrue(Domini.eResolverCifrato("dns.google"))
        assertFalse(Domini.eResolverCifrato("www.google.com"))
    }
}
