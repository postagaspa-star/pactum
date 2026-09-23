package eu.stgm.pactum.figlio.rete

import okhttp3.Dns
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Gli indirizzi del server con prima gli IPv4 e poi gli IPv6, ciascun gruppo
 * nell'ordine dato dal sistema.
 *
 * Serve al client delle modifiche (PostinoClient.httpMutazioni), che non
 * ritenta da solo perché un bonus non deve partire due volte: senza
 * ritentativo OkHttp prova SOLO il primo indirizzo. Android mette spesso prima
 * l'IPv6, e su una rete che lo annuncia ma non lo fa passare (un Wi-Fi di
 * casa, di scuola) la connessione non si apre e la modifica fallisce, anche se
 * l'IPv4 andrebbe. L'IPv4 funziona quasi ovunque; un server raggiungibile solo
 * in IPv6 resta raggiungibile, perché i suoi indirizzi restano tutti.
 */
class DnsPrimaIpv4(private val sistema: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        sistema.lookup(hostname).sortedBy { it is Inet6Address }
}
