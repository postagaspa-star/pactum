package eu.stgm.pactum.figlio.servizio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import eu.stgm.pactum.figlio.BuildConfig
import eu.stgm.pactum.figlio.MainActivity
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.bonus.ConsegnaBonus
import eu.stgm.pactum.figlio.dati.Battito
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.giornata.ChiusuraSerale
import eu.stgm.pactum.figlio.giornata.TestoSerale
import eu.stgm.pactum.figlio.notifiche.AvvisiLocali
import eu.stgm.pactum.figlio.rete.PostinoClient
import eu.stgm.pactum.figlio.siti.OsservazioneSiti
import eu.stgm.pactum.figlio.sync.ConsegnaEventi
import eu.stgm.pactum.figlio.valutatore.SentinellaPatto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * FGS di tipo specialUse (v. manifest: PROPERTY_SPECIAL_USE_FGS_SUBTYPE).
 *
 * Canale PRIMARIO del battito (decisione di design, review 14/07): di notte
 * Doze rinvia WorkManager anche di 2-6 ore, e ogni mattina la finestra del
 * genitore mostrerebbe un falso "silente". Il servizio è foreground e
 * l'esenzione batteria concede la rete anche in Doze, quindi il loop qui
 * dentro manda un battito ogni ~15 minuti; BattitoWorker resta come misura +
 * mittente di riserva. I doppi battiti sono innocui lato server.
 *
 * (0.9) Accanto al battito gira la sentinella quasi in tempo reale: guarda i
 * limiti ogni minuto a schermo acceso (avviaLoopSentinella). La misura vive
 * comunque in BattitoWorker (design retroattivo), quindi la morte di questo
 * servizio non buca il registro: al massimo riconsegna battito e sforamenti
 * al worker.
 */
class PactumService : Service() {

    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopBattito: Job? = null
    private var loopSentinella: Job? = null
    private var loopSerale: Job? = null

    // (0.9) Lo spegnimento dello schermo sveglia subito la sentinella: l'uso
    // fino a quell'istante si guarda adesso, non al giro dopo.
    private val spegnimenti = Channel<Unit>(Channel.CONFLATED)
    private val ricevitoreSchermo = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) spegnimenti.trySend(Unit)
        }
    }

    override fun onCreate() {
        super.onCreate()
        creaCanale()
        // SCREEN_OFF si riceve solo da un ricevitore registrato a mano, finché il servizio vive.
        ContextCompat.registerReceiver(
            this,
            ricevitoreSchermo,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            ID_NOTIFICA,
            notificaTestimone(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
        avviaLoopBattito()
        avviaLoopSentinella()
        avviaLoopSerale()
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(ricevitoreSchermo)
        } catch (e: IllegalArgumentException) {
            // mai registrato: niente da togliere
        }
        ambito.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Idempotente: onStartCommand può arrivare più volte, il loop è uno solo. */
    private fun avviaLoopBattito() {
        if (loopBattito?.isActive == true) return
        loopBattito = ambito.launch {
            while (isActive) {
                inviaBattito()
                // Sentinella di riserva (tappa 5): il giro veloce è quello di
                // avviaLoopSentinella, questo passa anche a schermo spento.
                // Dedup interno (una per regola per giorno); un errore qui non
                // deve uccidere il battito, che è la promessa più vecchia.
                try {
                    SentinellaPatto(applicationContext).valuta()
                } catch (e: Exception) {
                    // meglio un giro senza valutazione che un testimone morto
                }
                // (v2.3) L'osservazione dei siti si rimette in piedi da qui se
                // il figlio l'aveva accesa: il loop del testimone è il canale
                // affidabile, Doze può rinviare il worker per ore.
                try {
                    OsservazioneSiti.riprendiSeConsentita(applicationContext)
                } catch (e: Exception) {
                    // idem: un tunnel che non riparte non deve fermare il battito
                }
                // Un bonus rimasto a metà (processo morto durante la snackbar,
                // rete assente) riparte da qui. Idempotente: mai due volte.
                try {
                    ConsegnaBonus.recupera(applicationContext)
                } catch (e: Exception) {
                    // riprova al giro dopo
                }
                delay(INTERVALLO_BATTITO_MS)
            }
        }
    }

    /**
     * (0.9) La sentinella quasi in tempo reale, separata dal battito: guarda
     * l'uso ogni minuto mentre lo schermo è acceso, così un limite superato si
     * vede entro circa un minuto (prima: al giro del battito, fino a 15 minuti
     * dopo). Uno sforamento nuovo parte subito verso il server e apre l'avviso
     * (SentinellaPatto). A schermo spento l'uso non cresce: niente valutazioni,
     * tranne una appena si spegne (CadenzaSentinella). Al primo giro di un
     * giorno nuovo si guarda una volta anche il giorno prima: l'ultimo minuto
     * prima di mezzanotte. E se uno sforamento non è arrivato al server, si
     * riprova con attesa crescente (ConsegnaEventi).
     */
    private fun avviaLoopSentinella() {
        if (loopSentinella?.isActive == true) return
        val schermo = getSystemService(PowerManager::class.java)
        loopSentinella = ambito.launch {
            var accesoPrima = false
            var spentoAdesso = false
            var ultimoGiorno: LocalDate? = null
            while (isActive) {
                val adesso = System.currentTimeMillis()
                val zona = ZoneId.systemDefault()
                val oggi = Instant.ofEpochMilli(adesso).atZone(zona).toLocalDate()
                val accesoOra = schermo?.isInteractive ?: true
                val giro = CadenzaSentinella.giro(accesoOra, accesoPrima, spentoAdesso, oggi, ultimoGiorno)
                if (giro.ieri) {
                    protetto {
                        SentinellaPatto(applicationContext).valuta(
                            now = CadenzaSentinella.fineDiIeri(oggi, zona),
                            giornoPassato = true,
                        )
                    }
                }
                if (giro.oggi) {
                    protetto { SentinellaPatto(applicationContext).valuta(now = adesso) }
                    ultimoGiorno = oggi
                }
                protetto { ConsegnaEventi.riprovaSeServe(applicationContext, adesso) }
                accesoPrima = accesoOra
                // Un minuto, o meno se nel frattempo si spegne lo schermo.
                spentoAdesso = withTimeoutOrNull(CadenzaSentinella.INTERVALLO_MS) { spegnimenti.receive() } != null
            }
        }
    }

    /** Un giro andato storto non ferma il loop: il prossimo è fra un minuto. */
    private suspend fun protetto(azione: suspend () -> Unit) {
        try {
            azione()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // giro saltato
        }
    }

    /**
     * La chiusura della sera all'ora esatta: il loop dorme fino all'ora scelta
     * invece di aspettare il giro del battito (che arriverebbe fino a 15 minuti
     * dopo). Riparte da capo se il figlio cambia ora o la spegne. Se l'ora è già
     * passata e oggi non è partita (telefono riacceso alle 22), parte subito:
     * ChiusuraSerale sa da sola se oggi l'ha già mandata.
     */
    private fun avviaLoopSerale() {
        if (loopSerale?.isActive == true) return
        loopSerale = ambito.launch {
            Impostazioni(applicationContext).chiusuraSerale.collectLatest { config ->
                if (!config.attiva) return@collectLatest
                while (isActive) {
                    try {
                        ChiusuraSerale.controlla(applicationContext)
                    } catch (e: Exception) {
                        // la riserva è il worker
                    }
                    val adesso = ZonedDateTime.now()
                    val prossimo = TestoSerale.prossimoControllo(adesso, config.ora)
                    delay(Duration.between(adesso, prossimo).toMillis().coerceAtLeast(1_000))
                }
            }
        }
    }

    private suspend fun inviaBattito() {
        val impostazioni = Impostazioni(applicationContext)
        val configurazione = impostazioni.leggiConfigurazione()
        if (!configurazione.completa) return // patto non ancora configurato
        val consegnato = PostinoClient(configurazione).inviaBattito(
            Battito(
                tsDevice = System.currentTimeMillis(),
                versioneApp = BuildConfig.VERSION_NAME,
                elapsedRealtime = SystemClock.elapsedRealtime(),
            ),
        )
        if (consegnato) impostazioni.registraBattitoConsegnato()
    }

    private fun notificaTestimone(): Notification =
        NotificationCompat.Builder(this, CANALE_TESTIMONE)
            .setSmallIcon(R.drawable.ic_notifica_testimone)
            .setContentTitle(getString(R.string.notifica_testimone_titolo))
            .setContentText(getString(R.string.notifica_testimone_testo))
            // Anche la notifica fissa porta da qualche parte: al patto di oggi.
            .setContentIntent(AvvisiLocali.apriScheda(this, MainActivity.DEST_OGGI))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun creaCanale() {
        val canale = NotificationChannel(
            CANALE_TESTIMONE,
            getString(R.string.canale_testimone_nome),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = getString(R.string.canale_testimone_descrizione)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(canale)
    }

    companion object {
        private const val CANALE_TESTIMONE = "testimone"
        private const val ID_NOTIFICA = 1
        private const val INTERVALLO_BATTITO_MS = 15L * 60 * 1000

        fun avvia(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PactumService::class.java))
        }
    }
}
