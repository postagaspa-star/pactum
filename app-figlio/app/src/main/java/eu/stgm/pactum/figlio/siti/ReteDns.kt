package eu.stgm.pactum.figlio.siti

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.InetAddress

/**
 * Le due domande che il testimone fa alla rete del telefono:
 *
 *  1. **quali sono i veri server DNS** (quelli della rete sotto, non i nostri):
 *     le query si inoltrano LÌ, così la risoluzione dei nomi resta identica a
 *     prima — se in casa c'è un DNS che filtra, continua a filtrare. Pactum
 *     osserva, non cambia le regole della rete;
 *  2. **il DNS privato (DoT) è attivo?** Se sì, i nomi viaggiano cifrati e
 *     l'osservazione è cieca: si dichiara `dns_cifrato`, non si finge zero.
 */
object ReteDns {

    /** Ripiego quando la rete non dichiara nessun DNS (raro, ma succede). */
    private val RIPIEGO = listOf("1.1.1.1", "8.8.8.8")

    /**
     * I DNS delle reti REALI (Wi-Fi, dati): le reti VPN si saltano, altrimenti
     * quando il nostro tunnel è su ci inoltreremmo le query da soli.
     */
    fun serverVeri(context: Context): List<InetAddress> {
        val trovati = ArrayList<InetAddress>()
        percorriRetiReali(context) { _, linkProperties ->
            linkProperties.dnsServers.forEach { indirizzo ->
                // Solo IPv4: nel tunnel instradiamo un finto DNS IPv4 e le
                // risposte le ricuciamo in pacchetti IPv4.
                if (indirizzo.address.size == 4 && indirizzo !in trovati) trovati.add(indirizzo)
            }
        }
        if (trovati.isNotEmpty()) return trovati
        return RIPIEGO.mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
    }

    /**
     * true se è configurato il **DNS privato in modalità rigida** (Android 9+,
     * "DNS privato → nome host del provider): in quel caso il telefono manda
     * TUTTE le risoluzioni cifrate a quell'host, scavalcando il nostro finto
     * server DNS — l'osservazione è cieca e va dichiarata tale.
     *
     * La modalità **opportunistica** (impostazione "Automatico") NON conta: lì
     * Android prova il cifrato e, non trovandolo sul nostro server, ricade sul
     * chiaro — le query le vediamo eccome. Distinguere le due (il nome host
     * c'è solo nella rigida) evita di dichiararsi ciechi mentre si vede
     * benissimo: una confessione falsa sporca il registro quanto un numero
     * falso.
     */
    fun dnsPrivatoAttivo(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        var rigido = false
        percorriRetiReali(context) { _, linkProperties ->
            if (linkProperties.isPrivateDnsActive && linkProperties.privateDnsServerName != null) {
                rigido = true
            }
        }
        return rigido
    }

    @Suppress("DEPRECATION")
    private fun percorriRetiReali(
        context: Context,
        azione: (android.net.Network, android.net.LinkProperties) -> Unit,
    ) {
        val gestore = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val reti = runCatching { gestore.allNetworks }.getOrNull() ?: return
        for (rete in reti) {
            val capacita = gestore.getNetworkCapabilities(rete) ?: continue
            if (capacita.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!capacita.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            val linkProperties = gestore.getLinkProperties(rete) ?: continue
            azione(rete, linkProperties)
        }
    }
}
