package eu.stgm.pactum.figlio.rete

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Le modifiche non ritentano da sole, quindi OkHttp prova solo il primo
 * indirizzo del server: deve essere un IPv4, perché su una rete con l'IPv6
 * rotto la connessione non si aprirebbe. Indirizzi di documentazione, nessuna
 * rete vera.
 */
class DnsPrimaIpv4Test {

    private fun ipv4(ultimo: Int): InetAddress =
        InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, ultimo.toByte()))

    // 2001:db8::<ultimo>
    private fun ipv6(ultimo: Int): InetAddress =
        InetAddress.getByAddress(ByteArray(16).also { it[0] = 0x20; it[1] = 0x01; it[2] = 0x0d; it[3] = 0xb8.toByte(); it[15] = ultimo.toByte() })

    private fun sistema(vararg indirizzi: InetAddress) = object : Dns {
        var chiesto: String? = null
        override fun lookup(hostname: String): List<InetAddress> {
            chiesto = hostname
            return indirizzi.toList()
        }
    }

    @Test
    fun `gli indirizzi di prova sono davvero IPv4 e IPv6`() {
        assertTrue(ipv4(1) is Inet4Address)
        assertTrue(ipv6(1) is Inet6Address)
    }

    @Test
    fun `prima gli IPv4 poi gli IPv6, ciascuno nell'ordine del sistema`() {
        val a6 = ipv6(1)
        val b4 = ipv4(10)
        val c6 = ipv6(2)
        val d4 = ipv4(20)
        val dns = DnsPrimaIpv4(sistema(a6, b4, c6, d4))
        assertEquals(listOf(b4, d4, a6, c6), dns.lookup("pactum.taildbae63.ts.net"))
    }

    @Test
    fun `solo IPv6 o solo IPv4, niente cambia e niente si perde`() {
        val soloSei = listOf(ipv6(1), ipv6(2))
        assertEquals(soloSei, DnsPrimaIpv4(sistema(*soloSei.toTypedArray())).lookup("x"))
        val soloQuattro = listOf(ipv4(2), ipv4(1))
        assertEquals(soloQuattro, DnsPrimaIpv4(sistema(*soloQuattro.toTypedArray())).lookup("x"))
    }

    @Test
    fun `il nome chiesto arriva al sistema cosi' com'e'`() {
        val finto = sistema(ipv4(1))
        DnsPrimaIpv4(finto).lookup("pactum.taildbae63.ts.net")
        assertEquals("pactum.taildbae63.ts.net", finto.chiesto)
    }

    @Test
    fun `un nome che non esiste resta un errore`() {
        val introvabile = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException(hostname)
        }
        assertThrows(UnknownHostException::class.java) { DnsPrimaIpv4(introvabile).lookup("non.esiste") }
    }
}
