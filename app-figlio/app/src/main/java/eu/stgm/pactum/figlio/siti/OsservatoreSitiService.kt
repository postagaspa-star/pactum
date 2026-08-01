package eu.stgm.pactum.figlio.siti

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Il testimone dei siti: una VPN **locale** che non instrada il traffico.
 *
 * Nel tunnel entra SOLO il finto server DNS (`10.111.222.1`): è l'unica rotta
 * dichiarata. Tutto il resto — pagine, video, chat — continua a passare per la
 * rete normale senza nemmeno sfiorare Pactum: niente batteria bruciata, niente
 * traffico letto, niente da rallentare.
 *
 * Il giro di una richiesta:
 *   1. l'app del figlio chiede "instagram.com" al DNS di sistema, che ora è il
 *      nostro finto indirizzo → il pacchetto arriva nel tunnel;
 *   2. si legge il NOME della domanda (solo quello: dopo il nome, con HTTPS,
 *      non c'è niente di leggibile) e si conta nel registro del giorno;
 *   3. il pacchetto si inoltra al VERO server DNS della rete con una socket
 *      `protect()`-ata (fuori dal tunnel, altrimenti sarebbe un anello), e la
 *      risposta si riscrive dentro il tunnel.
 *
 * **Regola d'oro**: se l'inoltro fallisce ripetutamente, il tunnel si chiude e
 * l'osservazione si ferma. Meglio niente registro che internet rotto — il
 * patto non può costare la connessione al ragazzo (concept.md: testimone, non
 * carceriere). Al giro successivo del BattitoWorker si riprova.
 *
 * Nessun blocco, mai: nessun dominio viene filtrato o negato. Il genitore
 * VEDE, non blocca (docs/contratto-api.md, "Siti visitati — patto etico").
 */
class OsservatoreSitiService : VpnService() {

    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunnel: ParcelFileDescriptor? = null
    private var lettore: Thread? = null
    private var inoltratori: ExecutorService? = null
    private var vigilanza: Job? = null
    private var uscita: FileOutputStream? = null
    private val lucchettoScrittura = Any()
    private val fallimenti = AtomicInteger(0)

    @Volatile
    private var inEsecuzione = false

    @Volatile
    private var serverVeri: List<InetAddress> = emptyList()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == AZIONE_FERMA) {
            fermaTutto()
            stopSelf()
            return START_NOT_STICKY
        }
        avvia()
        return START_STICKY
    }

    override fun onDestroy() {
        fermaTutto()
        ambito.cancel()
        super.onDestroy()
    }

    /**
     * Il sistema ha revocato il consenso VPN (il figlio l'ha tolto dalle
     * impostazioni, oppure un'altra VPN ha preso il posto). È un'interruzione
     * dell'osservazione: va a registro come le altre, con il suo sotto_tipo.
     * Il registro non mente MAI, nemmeno quando la notizia è "ho smesso di
     * guardare".
     */
    override fun onRevoke() {
        // Scritture su DataStore e coda in un ambito staccato: onRevoke arriva
        // sul thread principale e bloccarlo qui rischierebbe un ANR. Il
        // processo resta vivo (c'è il testimone), quindi la scrittura arriva in
        // fondo; e se non arrivasse, il controllo di transizione del
        // BattitoWorker rileva comunque l'interruzione al giro dopo.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                OsservazioneSiti.spegniDopoInterruzione(
                    applicationContext,
                    OsservazioneSiti.ORIGINE_SISTEMA,
                )
            }
        }
        fermaTutto()
        stopSelf()
        super.onRevoke()
    }

    private fun avvia() {
        if (inEsecuzione) return
        serverVeri = ReteDns.serverVeri(applicationContext)
        val descrittore = costruisciTunnel() ?: return
        tunnel = descrittore
        inEsecuzione = true
        attivo = true
        fallimenti.set(0)

        val flusso = FileOutputStream(descrittore.fileDescriptor)
        uscita = flusso
        val esecutori = Executors.newFixedThreadPool(THREAD_INOLTRO)
        inoltratori = esecutori

        val ingresso = FileInputStream(descrittore.fileDescriptor)
        lettore = Thread({ cicloLettura(ingresso, esecutori) }, "pactum-siti").apply {
            isDaemon = true
            start()
        }

        // Vigilanza: ogni tanto rilegge il DNS privato (se si accende dopo,
        // la giornata va dichiarata cieca) e posa il registro su disco.
        vigilanza = ambito.launch {
            while (isActive && inEsecuzione) {
                if (ReteDns.dnsPrivatoAttivo(applicationContext)) {
                    RegistroSiti.dichiaraCieco(applicationContext)
                }
                // I DNS della rete cambiano quando si passa da Wi-Fi a dati:
                // senza rileggerli, gli inoltri fallirebbero tutti e il
                // tunnel si spegnerebbe per niente.
                serverVeri = ReteDns.serverVeri(applicationContext)
                RegistroSiti.salvaSubito(applicationContext)
                delay(PAUSA_VIGILANZA_MS)
            }
        }
    }

    private fun costruisciTunnel(): ParcelFileDescriptor? {
        val costruttore = Builder()
            .setSession(SESSIONE)
            .addAddress(IP_TUNNEL, 32)
            .addDnsServer(IP_DNS_FINTO)
            // L'UNICA rotta: il finto DNS. Il resto del traffico del telefono
            // non entra nel tunnel e Pactum non lo vede nemmeno.
            .addRoute(IP_DNS_FINTO, 32)
            .setMtu(MTU)
            .setBlocking(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            costruttore.setMetered(false)
        }
        // Pactum fuori dalla propria VPN: le sue chiamate al postino non
        // devono passare di qui (e il postino non è "un sito visitato").
        runCatching { costruttore.addDisallowedApplication(packageName) }
        return runCatching { costruttore.establish() }.getOrNull()
    }

    /**
     * Legge i pacchetti dal tunnel finché c'è qualcosa da leggere. Le letture
     * sono bloccanti (setBlocking): il thread dorme quando non c'è traffico
     * DNS, che è quasi sempre — da qui il costo trascurabile in batteria.
     */
    private fun cicloLettura(ingresso: FileInputStream, esecutori: ExecutorService) {
        val buffer = ByteArray(MTU)
        try {
            while (inEsecuzione) {
                val letti = ingresso.read(buffer)
                if (letti < 0) break
                if (letti == 0) continue
                val pacchetto = buffer.copyOf(letti)
                runCatching { esecutori.execute { gestisci(pacchetto) } }
            }
        } catch (e: IOException) {
            // Tunnel chiuso (nostro stop) o errore di lettura: si smette.
        } finally {
            runCatching { ingresso.close() }
        }
    }

    private fun gestisci(pacchetto: ByteArray) {
        val datagramma = PacchettiDns.analizzaUdp(pacchetto) ?: return
        if (datagramma.portaDestinazione != PacchettiDns.PORTA_DNS) return

        // Si conta PRIMA di inoltrare: la richiesta c'è stata comunque, anche
        // se poi la rete non risponde. Il registro racconta ciò che è successo.
        PacchettiDns.nomeChiesto(datagramma.payload)?.let { nome ->
            runCatching { RegistroSiti.osserva(applicationContext, nome) }
        }

        val risposta = inoltra(datagramma.payload)
        if (risposta == null) {
            contaFallimento()
            return
        }
        fallimenti.set(0)

        // Una risposta più grande dell'MTU non entra nel tunnel: si lascia
        // cadere invece di scriverne una monca (il telefono la ritenta da
        // solo). Con le risposte A/AAAA normali non capita mai.
        if (28 + risposta.size > MTU) return

        val pacchettoRisposta = PacchettiDns.rispostaUdp(
            ipSorgente = datagramma.ipDestinazione,
            ipDestinazione = datagramma.ipSorgente,
            portaSorgente = datagramma.portaDestinazione,
            portaDestinazione = datagramma.portaSorgente,
            payload = risposta,
        )
        val flusso = uscita ?: return
        runCatching {
            synchronized(lucchettoScrittura) { flusso.write(pacchettoRisposta) }
        }
    }

    /**
     * Inoltra la query al vero server DNS della rete, fuori dal tunnel
     * (`protect`). Si provano in ordine i server dichiarati dalla rete: se
     * nessuno risponde, null e chi chiama conta il fallimento.
     */
    private fun inoltra(query: ByteArray): ByteArray? {
        val server = serverVeri.ifEmpty {
            serverVeri = ReteDns.serverVeri(applicationContext)
            serverVeri
        }
        for (indirizzo in server) {
            try {
                DatagramSocket().use { socket ->
                    if (!protect(socket)) return null
                    socket.soTimeout = TIMEOUT_DNS_MS
                    socket.send(DatagramPacket(query, query.size, indirizzo, PacchettiDns.PORTA_DNS))
                    // EDNS0 permette risposte fino a 4 KB: si riceve tutto e
                    // poi si decide se sta nel tunnel, invece di troncare qui.
                    val buffer = ByteArray(BUFFER_RISPOSTA)
                    val risposta = DatagramPacket(buffer, buffer.size)
                    socket.receive(risposta)
                    return buffer.copyOf(risposta.length)
                }
            } catch (e: IOException) {
                // Server muto o rete giù: si prova il prossimo.
            } catch (e: SecurityException) {
                return null
            }
        }
        return null
    }

    /**
     * Troppi inoltri falliti di fila = il telefono, con la nostra VPN su, non
     * risolve più i nomi. Si chiude e si smette: **meglio niente registro che
     * internet rotto**. Il worker riproverà al giro dopo.
     */
    private fun contaFallimento() {
        val quanti = fallimenti.incrementAndGet()
        // Prima di arrendersi: forse è cambiata la rete (Wi-Fi → dati) e i
        // server DNS di prima non esistono più. Si rilegge e si riprova.
        if (quanti < FALLIMENTI_MASSIMI) {
            if (quanti == 1) serverVeri = ReteDns.serverVeri(applicationContext)
            return
        }
        fermaTutto()
        stopSelf()
    }

    private fun fermaTutto() {
        if (!inEsecuzione && tunnel == null) {
            attivo = false
            return
        }
        inEsecuzione = false
        attivo = false
        vigilanza?.cancel()
        vigilanza = null
        runCatching { RegistroSiti.salvaSubito(applicationContext) }
        runCatching { tunnel?.close() }
        tunnel = null
        runCatching { uscita?.close() }
        uscita = null
        runCatching { inoltratori?.shutdownNow() }
        inoltratori = null
        lettore = null
    }

    companion object {
        /** Vero mentre il tunnel è su (stesso processo di worker e UI). */
        @Volatile
        var attivo: Boolean = false
            private set

        const val AZIONE_FERMA = "eu.stgm.pactum.figlio.SITI_FERMA"

        private const val SESSIONE = "Pactum — siti visitati"
        private const val IP_TUNNEL = "10.111.222.2"
        private const val IP_DNS_FINTO = "10.111.222.1"
        private const val MTU = 1500
        private const val BUFFER_RISPOSTA = 4096
        private const val THREAD_INOLTRO = 4
        private const val TIMEOUT_DNS_MS = 4_000
        private const val FALLIMENTI_MASSIMI = 6
        private const val PAUSA_VIGILANZA_MS = 5L * 60 * 1000

        fun avvia(context: Context) {
            // startService dal background è vietato su Android 8+: qui l'app ha
            // sempre il testimone (FGS) acceso, quindi passa. Se per qualche
            // motivo non passasse, non è un motivo per far cadere il chiamante.
            runCatching {
                context.startService(Intent(context, OsservatoreSitiService::class.java))
            }
        }

        fun ferma(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, OsservatoreSitiService::class.java).setAction(AZIONE_FERMA),
                )
            }
        }
    }
}
