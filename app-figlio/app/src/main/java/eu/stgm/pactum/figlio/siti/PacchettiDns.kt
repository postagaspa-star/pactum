package eu.stgm.pactum.figlio.siti

/**
 * I pochi byte di rete che servono al testimone: leggere una **query DNS** da
 * un pacchetto IPv4/UDP e ricucire la risposta per rimandarla dentro il
 * tunnel. Niente librerie: un pacchetto DNS in chiaro è un'intestazione IP di
 * 20 byte, 8 di UDP e un nome scritto a etichette.
 *
 * Si legge SOLO il nome chiesto (la domanda). Il contenuto — quale pagina,
 * quale video, quale ricerca — viaggia dentro HTTPS e non è leggibile
 * nemmeno volendo (docs/contratto-api.md, "Siti visitati").
 */
object PacchettiDns {

    const val PORTA_DNS = 53
    private const val PROTOCOLLO_UDP = 17

    /** Un datagramma UDP estratto da un pacchetto IPv4. */
    class DatagrammaUdp(
        val ipSorgente: ByteArray,
        val ipDestinazione: ByteArray,
        val portaSorgente: Int,
        val portaDestinazione: Int,
        val payload: ByteArray,
    )

    /**
     * Estrae il datagramma UDP da un pacchetto IPv4, null se il pacchetto non
     * è IPv4/UDP o è troncato/frammentato. Nel tunnel instradiamo SOLO il
     * finto server DNS, quindi qui passa praticamente solo DNS: tutto il resto
     * si scarta in silenzio.
     */
    fun analizzaUdp(pacchetto: ByteArray): DatagrammaUdp? {
        val lunghezza = pacchetto.size
        if (lunghezza < 28) return null
        val versione = (pacchetto[0].toInt() shr 4) and 0x0F
        if (versione != 4) return null
        val intestazione = (pacchetto[0].toInt() and 0x0F) * 4
        if (intestazione < 20 || lunghezza < intestazione + 8) return null
        if ((pacchetto[9].toInt() and 0xFF) != PROTOCOLLO_UDP) return null
        // Frammenti: scartati. Una query DNS sta in un pacchetto solo.
        val offsetFrammento = (((pacchetto[6].toInt() and 0x1F) shl 8) or (pacchetto[7].toInt() and 0xFF))
        if (offsetFrammento != 0) return null

        val lunghezzaUdp = leggi16(pacchetto, intestazione + 4)
        val inizioPayload = intestazione + 8
        val finePayload = (intestazione + lunghezzaUdp).coerceAtMost(lunghezza)
        if (finePayload < inizioPayload) return null

        return DatagrammaUdp(
            ipSorgente = pacchetto.copyOfRange(12, 16),
            ipDestinazione = pacchetto.copyOfRange(16, 20),
            portaSorgente = leggi16(pacchetto, intestazione),
            portaDestinazione = leggi16(pacchetto, intestazione + 2),
            payload = pacchetto.copyOfRange(inizioPayload, finePayload),
        )
    }

    /**
     * Il nome chiesto dalla prima domanda di una query DNS, null se il
     * messaggio non è una query standard o è malformato. Solo la QUESTION:
     * la risposta (gli indirizzi IP) non ci interessa e non viene letta.
     */
    fun nomeChiesto(dns: ByteArray): String? {
        if (dns.size < 12) return null
        val flag = leggi16(dns, 2)
        if ((flag and 0x8000) != 0) return null // è una risposta, non una domanda
        if (((flag shr 11) and 0x0F) != 0) return null // opcode != QUERY standard
        if (leggi16(dns, 4) < 1) return null // nessuna domanda

        val nome = StringBuilder()
        var i = 12
        while (i < dns.size) {
            val lunghezza = dns[i].toInt() and 0xFF
            if (lunghezza == 0) return if (nome.isEmpty()) null else nome.toString()
            // Puntatore di compressione: legale nelle risposte, non in una
            // domanda. Se compare, si rinuncia invece di inseguire offset.
            if ((lunghezza and 0xC0) != 0) return null
            i++
            if (i + lunghezza > dns.size) return null
            if (nome.isNotEmpty()) nome.append('.')
            nome.append(String(dns, i, lunghezza, Charsets.US_ASCII))
            if (nome.length > 253) return null
            i += lunghezza
        }
        return null
    }

    /**
     * Ricuce la risposta del vero server DNS in un pacchetto IPv4/UDP da
     * scrivere nel tunnel: sorgente e destinazione scambiate rispetto alla
     * query, così il telefono la riconosce come la risposta che aspettava.
     * Il checksum UDP resta 0 (ammesso in IPv4: "non calcolato"); quello
     * dell'intestazione IP invece è obbligatorio.
     */
    fun rispostaUdp(
        ipSorgente: ByteArray,
        ipDestinazione: ByteArray,
        portaSorgente: Int,
        portaDestinazione: Int,
        payload: ByteArray,
    ): ByteArray {
        val lunghezzaUdp = 8 + payload.size
        val totale = 20 + lunghezzaUdp
        val pacchetto = ByteArray(totale)

        pacchetto[0] = 0x45 // IPv4, intestazione di 20 byte
        scrivi16(pacchetto, 2, totale)
        pacchetto[6] = 0x40 // Don't Fragment
        pacchetto[8] = 64 // TTL
        pacchetto[9] = PROTOCOLLO_UDP.toByte()
        System.arraycopy(ipSorgente, 0, pacchetto, 12, 4)
        System.arraycopy(ipDestinazione, 0, pacchetto, 16, 4)
        scrivi16(pacchetto, 10, checksum(pacchetto, 20))

        scrivi16(pacchetto, 20, portaSorgente)
        scrivi16(pacchetto, 22, portaDestinazione)
        scrivi16(pacchetto, 24, lunghezzaUdp)
        // pacchetto[26..27] = checksum UDP = 0
        System.arraycopy(payload, 0, pacchetto, 28, payload.size)
        return pacchetto
    }

    private fun leggi16(dati: ByteArray, posizione: Int): Int =
        ((dati[posizione].toInt() and 0xFF) shl 8) or (dati[posizione + 1].toInt() and 0xFF)

    private fun scrivi16(dati: ByteArray, posizione: Int, valore: Int) {
        dati[posizione] = ((valore shr 8) and 0xFF).toByte()
        dati[posizione + 1] = (valore and 0xFF).toByte()
    }

    /** Checksum a complemento a uno dell'intestazione IP (RFC 1071). */
    private fun checksum(dati: ByteArray, lunghezza: Int): Int {
        var somma = 0L
        var i = 0
        while (i + 1 < lunghezza) {
            somma += leggi16(dati, i).toLong()
            i += 2
        }
        if (i < lunghezza) somma += ((dati[i].toInt() and 0xFF) shl 8).toLong()
        while (somma shr 16 != 0L) somma = (somma and 0xFFFF) + (somma shr 16)
        return (somma.inv() and 0xFFFF).toInt()
    }
}
