package eu.stgm.pactum.figlio.rete

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * (0.11) Dopo un errore di rete, la richiesta è forse arrivata al server? Se
 * non si è mai collegati, no: "Inizia" non è partito, e lo si dice chiaro. Se
 * il collegamento è caduto a metà, non si sa: "Non so se la sessione è
 * partita", e si ricontrolla.
 */
class ForseArrivataTest {

    @Test
    fun `mai collegati - la richiesta non e' arrivata`() {
        assertFalse(PostinoClient.forseArrivata(UnknownHostException("pactum.esempio.ts.net")))
        assertFalse(PostinoClient.forseArrivata(ConnectException("Connection refused")))
        assertFalse(PostinoClient.forseArrivata(NoRouteToHostException("No route to host")))
        assertFalse(PostinoClient.forseArrivata(SSLHandshakeException("handshake failed")))
        // Scaduto il tempo per collegarsi (JVM e Android lo scrivono diverso).
        assertFalse(PostinoClient.forseArrivata(SocketTimeoutException("connect timed out")))
        assertFalse(PostinoClient.forseArrivata(SocketTimeoutException("failed to connect to /192.0.2.1 (port 443) after 15000ms")))
    }

    @Test
    fun `collegamento caduto a meta' o risposta mai arrivata - non si sa`() {
        assertTrue(PostinoClient.forseArrivata(SocketTimeoutException("timeout")))
        assertTrue(PostinoClient.forseArrivata(SocketTimeoutException("Read timed out")))
        assertTrue(PostinoClient.forseArrivata(SocketTimeoutException()))
        assertTrue(PostinoClient.forseArrivata(SocketException("Connection reset")))
        assertTrue(PostinoClient.forseArrivata(EOFException()))
        assertTrue(PostinoClient.forseArrivata(InterruptedIOException("timeout")))
        assertTrue(PostinoClient.forseArrivata(IOException("unexpected end of stream on https://pactum.esempio.ts.net/...")))
    }
}
