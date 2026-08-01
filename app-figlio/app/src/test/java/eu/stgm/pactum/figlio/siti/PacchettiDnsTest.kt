package eu.stgm.pactum.figlio.siti

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * La lettura dei pacchetti è il punto in cui un errore non si vede come un
 * bug ma come "internet non va" (risposta ricucita male) o "il registro è
 * sempre vuoto" (nome letto male). Qui si costruisce una query DNS vera, byte
 * per byte, e si controlla tutto il giro.
 */
class PacchettiDnsTest {

    private val ipTelefono = byteArrayOf(10, 111.toByte(), 222.toByte(), 2)
    private val ipDnsFinto = byteArrayOf(10, 111.toByte(), 222.toByte(), 1)

    @Test
    fun `estrae porte, indirizzi e payload da una query`() {
        val dns = messaggioDns(risposta = false, nome = "www.example.com")
        val pacchetto = pacchettoUdp(ipTelefono, ipDnsFinto, 54321, 53, dns)

        val datagramma = PacchettiDns.analizzaUdp(pacchetto)!!
        assertEquals(54321, datagramma.portaSorgente)
        assertEquals(53, datagramma.portaDestinazione)
        assertArrayEquals(ipTelefono, datagramma.ipSorgente)
        assertArrayEquals(ipDnsFinto, datagramma.ipDestinazione)
        assertArrayEquals(dns, datagramma.payload)
    }

    @Test
    fun `legge il nome chiesto`() {
        val dns = messaggioDns(risposta = false, nome = "scontent.cdninstagram.com")
        assertEquals("scontent.cdninstagram.com", PacchettiDns.nomeChiesto(dns))
    }

    @Test
    fun `una risposta non e una domanda`() {
        val dns = messaggioDns(risposta = true, nome = "www.example.com")
        assertNull(PacchettiDns.nomeChiesto(dns))
    }

    @Test
    fun `messaggi troncati o assurdi non fanno danni`() {
        assertNull(PacchettiDns.nomeChiesto(ByteArray(4)))
        // Etichetta che dichiara più byte di quanti ce ne siano.
        val bugiardo = messaggioDns(risposta = false, nome = "www.example.com")
            .copyOf(16)
        assertNull(PacchettiDns.nomeChiesto(bugiardo))
        assertNull(PacchettiDns.analizzaUdp(ByteArray(10)))
    }

    @Test
    fun `il TCP non viene scambiato per UDP`() {
        val pacchetto = pacchettoUdp(ipTelefono, ipDnsFinto, 443, 53, ByteArray(20))
        pacchetto[9] = 6 // protocollo TCP
        assertNull(PacchettiDns.analizzaUdp(pacchetto))
    }

    @Test
    fun `la risposta torna indietro con sorgente e destinazione scambiate`() {
        val payload = messaggioDns(risposta = true, nome = "www.example.com")
        val risposta = PacchettiDns.rispostaUdp(
            ipSorgente = ipDnsFinto,
            ipDestinazione = ipTelefono,
            portaSorgente = 53,
            portaDestinazione = 54321,
            payload = payload,
        )

        assertEquals(20 + 8 + payload.size, risposta.size)
        val datagramma = PacchettiDns.analizzaUdp(risposta)!!
        assertEquals(53, datagramma.portaSorgente)
        assertEquals(54321, datagramma.portaDestinazione)
        assertArrayEquals(ipDnsFinto, datagramma.ipSorgente)
        assertArrayEquals(ipTelefono, datagramma.ipDestinazione)
        assertArrayEquals(payload, datagramma.payload)
        // Un'intestazione IP con checksum giusto somma a zero: se sbagliasse,
        // il telefono butterebbe la risposta e "internet non andrebbe".
        assertEquals(0, sommaIntestazione(risposta))
    }

    // --- Aiutanti: pacchetti veri, costruiti a mano -------------------------

    private fun messaggioDns(risposta: Boolean, nome: String): ByteArray {
        val etichette = ArrayList<Byte>()
        nome.split('.').forEach { pezzo ->
            etichette.add(pezzo.length.toByte())
            pezzo.forEach { etichette.add(it.code.toByte()) }
        }
        etichette.add(0)
        val dns = ByteArray(12 + etichette.size + 4)
        dns[0] = 0x12; dns[1] = 0x34 // id
        dns[2] = if (risposta) 0x81.toByte() else 0x01 // QR + RD
        dns[3] = if (risposta) 0x80.toByte() else 0x00
        dns[5] = 1 // una domanda
        if (risposta) dns[7] = 1 // una risposta
        etichette.forEachIndexed { indice, byte -> dns[12 + indice] = byte }
        dns[dns.size - 3] = 1 // QTYPE = A
        dns[dns.size - 1] = 1 // QCLASS = IN
        return dns
    }

    private fun pacchettoUdp(
        sorgente: ByteArray,
        destinazione: ByteArray,
        portaSorgente: Int,
        portaDestinazione: Int,
        payload: ByteArray,
    ): ByteArray {
        val totale = 28 + payload.size
        val pacchetto = ByteArray(totale)
        pacchetto[0] = 0x45
        pacchetto[2] = (totale shr 8).toByte()
        pacchetto[3] = totale.toByte()
        pacchetto[8] = 64
        pacchetto[9] = 17
        System.arraycopy(sorgente, 0, pacchetto, 12, 4)
        System.arraycopy(destinazione, 0, pacchetto, 16, 4)
        pacchetto[20] = (portaSorgente shr 8).toByte()
        pacchetto[21] = portaSorgente.toByte()
        pacchetto[22] = (portaDestinazione shr 8).toByte()
        pacchetto[23] = portaDestinazione.toByte()
        val lunghezzaUdp = 8 + payload.size
        pacchetto[24] = (lunghezzaUdp shr 8).toByte()
        pacchetto[25] = lunghezzaUdp.toByte()
        System.arraycopy(payload, 0, pacchetto, 28, payload.size)
        return pacchetto
    }

    /** Somma a complemento a uno dei 20 byte di intestazione: 0 se il checksum regge. */
    private fun sommaIntestazione(pacchetto: ByteArray): Int {
        var somma = 0L
        var i = 0
        while (i < 20) {
            somma += (((pacchetto[i].toInt() and 0xFF) shl 8) or (pacchetto[i + 1].toInt() and 0xFF))
            i += 2
        }
        while (somma shr 16 != 0L) somma = (somma and 0xFFFF) + (somma shr 16)
        return (somma.inv() and 0xFFFF).toInt()
    }
}
