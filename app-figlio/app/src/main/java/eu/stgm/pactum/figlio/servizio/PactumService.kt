package eu.stgm.pactum.figlio.servizio

import android.app.KeyguardManager
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
import eu.stgm.pactum.figlio.avviso.Chiamata
import eu.stgm.pactum.figlio.bonus.ConsegnaBonus
import eu.stgm.pactum.figlio.dati.Battito
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.dati.PattoLocale
import eu.stgm.pactum.figlio.giornata.ChiusuraSerale
import eu.stgm.pactum.figlio.giornata.TestoSerale
import eu.stgm.pactum.figlio.notifiche.AvvisiLocali
import eu.stgm.pactum.figlio.permessi.PermessiHelper
import eu.stgm.pactum.figlio.rete.PostinoClient
import eu.stgm.pactum.figlio.sessione.AnnunciSessione
import eu.stgm.pactum.figlio.sessione.ArchivioSessioni
import eu.stgm.pactum.figlio.sessione.ConsegnaSessioni
import eu.stgm.pactum.figlio.sessione.PaginaFine
import eu.stgm.pactum.figlio.sessione.PaginaSessioneActivity
import eu.stgm.pactum.figlio.sessione.PagineSessione
import eu.stgm.pactum.figlio.sessione.PrimoPianoAdesso
import eu.stgm.pactum.figlio.sessione.SessioneAttiva
import eu.stgm.pactum.figlio.sessione.SorveglianzaSessione
import eu.stgm.pactum.figlio.sessione.StatoSessione
import eu.stgm.pactum.figlio.sessione.SvoltaLocale
import eu.stgm.pactum.figlio.sessione.TestoSessioni
import eu.stgm.pactum.figlio.sessione.nomeSessioneTraVirgolette
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
import kotlinx.coroutines.selects.select
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
 *
 * (0.11) E, solo mentre una Sessione è in corso, la sua barriera
 * (avviaLoopSessione): un giro al secondo a schermo acceso e sbloccato, e la
 * notifica fissa dice la sessione. Un loop a parte: qualsiasi cosa vada storta
 * lì non tocca battito, sentinella e chiusura della sera, e non copre niente.
 *
 * (0.12) Quando una Sessione finisce da sola (o la chiude il server), la sua
 * pagina animata della fine: sopra l'app in uso solo nei primi 10 minuti,
 * altrimenti una notifica e la pagina in Pactum (mostraFineSeServe).
 */
class PactumService : Service() {

    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopBattito: Job? = null
    private var loopSentinella: Job? = null
    private var loopSerale: Job? = null
    private var loopSessione: Job? = null

    // (0.9) Lo spegnimento dello schermo sveglia subito la sentinella: l'uso
    // fino a quell'istante si guarda adesso, non al giro dopo. (0.11)
    // L'accensione rinfresca la notifica fissa: una sessione finita mentre il
    // telefono dormiva non resta scritta lì. (0.12) L'accensione e lo sblocco
    // svegliano anche la sentinella (il primo preavviso dopo lo sblocco non
    // aspetta un minuto), e allo sblocco la pagina della fine di una sessione
    // finita a schermo spento.
    private val spegnimenti = Channel<Unit>(Channel.CONFLATED)
    private val accensioni = Channel<Unit>(Channel.CONFLATED)
    private val ricevitoreSchermo = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> spegnimenti.trySend(Unit)
                Intent.ACTION_SCREEN_ON -> {
                    accensioni.trySend(Unit)
                    ambito.launch(Dispatchers.IO) {
                        protetto { ArchivioSessioni.ricalcola(applicationContext) }
                        aggiornaNotifica(StatoSessione.attivaAdesso())
                        protetto { mostraFineSeServe() }
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    accensioni.trySend(Unit)
                    ambito.launch(Dispatchers.IO) { protetto { mostraFineSeServe() } }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        creaCanale()
        // SCREEN_OFF e SCREEN_ON si ricevono solo da un ricevitore registrato a mano, finché il servizio vive.
        ContextCompat.registerReceiver(
            this,
            ricevitoreSchermo,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            ID_NOTIFICA,
            notificaTestimone(StatoSessione.attivaAdesso()),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
        avviaLoopBattito()
        avviaLoopSentinella()
        avviaLoopSerale()
        avviaLoopSessione()
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
                var attesa = CadenzaSentinella.INTERVALLO_MS
                if (giro.oggi) {
                    // (0.12) A schermo acceso anche i preavvisi "il tempo sta per
                    // finire": il giro dopo arriva appena dopo la prossima soglia
                    // dell'app davanti, se viene prima del minuto.
                    protetto {
                        attesa = CadenzaSentinella.attesa(
                            SentinellaPatto(applicationContext).valutaConPreavvisi(now = adesso, preavvisi = accesoOra),
                        )
                    }
                    ultimoGiorno = oggi
                }
                protetto { ConsegnaEventi.riprovaSeServe(applicationContext, adesso) }
                // (0.11) Una "Termina la sessione" fatta senza rete: si riprova
                // con attesa crescente finché arriva al server.
                protetto { ConsegnaSessioni.riprovaSeServe(applicationContext, adesso) }
                // (0.12) La pagina della fine rimasta ad aspettare (una chiamata
                // in corso quando la sessione è finita): appena si può.
                if (accesoOra) protetto { mostraFineSeServe() }
                accesoPrima = accesoOra
                // Un minuto (o meno, per un preavviso), o meno se nel frattempo lo
                // schermo si spegne (si guarda subito l'uso fino a lì) o si
                // riaccende (il primo preavviso dopo lo sblocco non aspetta).
                spentoAdesso = withTimeoutOrNull(attesa) {
                    select {
                        spegnimenti.onReceive { true }
                        accensioni.onReceive { false }
                    }
                } == true
            }
        }
    }

    /**
     * (0.11) La Sessione in corso: la notifica fissa la dice, e la barriera
     * guarda l'app in primo piano circa una volta al secondo, SOLO finché la
     * sessione dura. Quando finisce (scaduta, terminata in Pactum, sparita)
     * il giro si ferma subito: collectLatest lo interrompe al primo cambio.
     */
    private fun avviaLoopSessione() {
        if (loopSessione?.isActive == true) return
        loopSessione = ambito.launch(Dispatchers.IO) {
            // Lo stato dal disco, se il processo è appena nato.
            try {
                ArchivioSessioni.leggi(applicationContext)
            } catch (e: Exception) {
                // senza archivio, nessuna sessione: nessuna barriera
            }
            // Telefono appena reinstallato (nessun archivio): una sessione in
            // corso la sa solo il server. Una lettura del patto la riporta.
            protetto { recuperaSessioneDalServer() }
            StatoSessione.attiva.collectLatest { attiva ->
                aggiornaNotifica(attiva?.takeIf { System.currentTimeMillis() < it.fine })
                if (attiva == null) {
                    // (0.12) Finita (da sola, o chiusa dal server), o il servizio
                    // appena ripartito: la pagina della fine, se è ancora da vedere.
                    protetto { mostraFineSeServe() }
                    return@collectLatest
                }
                if (!attiva.annunciata) {
                    // Una sessione che il ragazzo non ha visto partire (risposta
                    // persa, reinstallazione): prima glielo si dice. Se la
                    // notifica non arriva, niente barriera: la vedrà in Pactum.
                    val detta = try {
                        AnnunciSessione.partita(applicationContext, attiva)
                    } catch (e: Exception) {
                        false
                    }
                    if (detta) {
                        // Lo stato nuovo (annunciata) fa ripartire questo giro con la barriera.
                        protetto { ArchivioSessioni.annuncia(applicationContext, attiva.svoltaId) }
                        return@collectLatest
                    }
                    delay((attiva.fine - System.currentTimeMillis()).coerceAtLeast(0))
                } else {
                    protetto { sorvegliaSessione(attiva) }
                }
                // Arrivata alla fine: non è più "in corso" per nessuno.
                protetto { ArchivioSessioni.ricalcola(applicationContext) }
            }
        }
    }

    /** Il giro della barriera, finché la sessione dura. Un giro andato storto non copre niente. */
    private suspend fun sorvegliaSessione(attiva: SessioneAttiva) {
        var sorveglianza: SorveglianzaSessione? = null
        while (true) {
            val adesso = System.currentTimeMillis()
            if (adesso >= attiva.fine) return
            val attesa = try {
                val giro = sorveglianza ?: SorveglianzaSessione(applicationContext, attiva).also { sorveglianza = it }
                giro.giro(adesso, SystemClock.elapsedRealtime())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sorveglianza?.azzera()
                SorveglianzaSessione.ATTESA_ERRORE_MS
            }
            delay(minOf(attesa, attiva.fine - adesso).coerceAtLeast(MINIMO_ATTESA_MS))
        }
    }

    /**
     * (0.12) La pagina della fine di una Sessione finita da poco (entro 2 ore),
     * una volta sola. Sopra l'app in uso solo nei primi 10 minuti dalla fine,
     * a schermo acceso e sbloccato, fuori da una chiamata (anche via internet:
     * la schermata davanti, come per la barriera) e con "Mostra sopra le altre
     * app"; con Pactum davanti, lì. Altrimenti la notifica, una volta sola, e
     * la pagina alla prossima apertura di Pactum. "Fatta" la segna la pagina
     * quando arriva sullo schermo; mentre si sta aprendo nessuno la riapre.
     * La barriera non la copre (è di Pactum) e non la conta.
     */
    private fun mostraFineSeServe() {
        val adesso = System.currentTimeMillis()
        val svolta = PagineSessione.daMostrare(ArchivioSessioni.leggi(applicationContext).svolte, adesso) ?: return
        if (PaginaSessioneActivity.inApertura(svolta.id, adesso)) return
        val schermoAcceso = getSystemService(PowerManager::class.java)?.isInteractive == true
        val sbloccato = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false
        val mostraSopra = PermessiHelper.puoMostrareSopra(applicationContext)
        val pactumDavanti = MainActivity.inPrimoPiano
        // La schermata davanti (una chiamata via internet) si legge solo quando la
        // pagina potrebbe davvero aprirsi: non a ogni giro di una pagina già avvisata.
        val potrebbeAprirsi = schermoAcceso && sbloccato && (
            pactumDavanti || (svolta.paginaFine != PaginaFine.AVVISATA && mostraSopra && adesso - svolta.fine < PagineSessione.FINESTRA_SOPRA_MS)
            )
        val inChiamata = Chiamata.inCorso(applicationContext) ||
            (potrebbeAprirsi && PrimoPianoAdesso.inChiamata(applicationContext, adesso))
        val come = PagineSessione.come(
            svolta = svolta,
            adesso = adesso,
            schermoAcceso = schermoAcceso,
            sbloccato = sbloccato,
            inChiamata = inChiamata,
            mostraSopra = mostraSopra,
            pactumDavanti = pactumDavanti,
        )
        when (come) {
            PagineSessione.Come.APRI_IN_PACTUM, PagineSessione.Come.APRI_SOPRA -> {
                // Android non l'ha aperta: la notifica (una volta), e la pagina all'apertura di Pactum.
                if (!PaginaSessioneActivity.apriFine(applicationContext, svolta)) avvisaFine(svolta, adesso)
            }
            PagineSessione.Come.NOTIFICA -> avvisaFine(svolta, adesso)
            PagineSessione.Come.ASPETTA, PagineSessione.Come.NIENTE -> Unit
        }
    }

    /** La notifica "Sessione finita", solo se la pagina era ancora in attesa: mai due volte. */
    private fun avvisaFine(svolta: SvoltaLocale, adesso: Long) {
        if (ArchivioSessioni.avvisaPaginaFine(applicationContext, svolta.id)) {
            AnnunciSessione.finita(applicationContext, svolta, adesso)
        }
    }

    private suspend fun recuperaSessioneDalServer() {
        if (ArchivioSessioni.esiste(applicationContext)) return
        val configurazione = Impostazioni(applicationContext).leggiConfigurazione()
        if (!configurazione.completa) return
        // PattoLocale.salva porta con sé le sessioni (ArchivioSessioni.daServer).
        PostinoClient(configurazione).leggiPatto()?.let { PattoLocale(applicationContext).salva(it) }
    }

    private fun aggiornaNotifica(attiva: SessioneAttiva?) {
        try {
            getSystemService(NotificationManager::class.java)?.notify(ID_NOTIFICA, notificaTestimone(attiva))
        } catch (e: Exception) {
            // notifiche spente: la sessione vale lo stesso
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

    /**
     * La notifica fissa del testimone. (0.11) Con una Sessione in corso dice
     * quale e fino a quando: toccandola si arriva a Oggi, dove c'è "Termina la sessione".
     */
    private fun notificaTestimone(attiva: SessioneAttiva? = null): Notification {
        val testo = attiva?.let {
            val quando = TestoSessioni.quandoFinisce(it.fine, System.currentTimeMillis(), ZoneId.systemDefault())
            getString(
                if (quando.domani) R.string.notifica_testimone_sessione_domani else R.string.notifica_testimone_sessione,
                nomeSessioneTraVirgolette(this, it.nome),
                quando.ora,
            )
        } ?: getString(R.string.notifica_testimone_testo)
        val costruttore = NotificationCompat.Builder(this, CANALE_TESTIMONE)
            .setSmallIcon(R.drawable.ic_notifica_testimone)
            .setContentTitle(getString(R.string.notifica_testimone_titolo))
            .setContentText(testo)
            // Anche la notifica fissa porta da qualche parte: al patto di oggi.
            .setContentIntent(AvvisiLocali.apriScheda(this, MainActivity.DEST_OGGI))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        // (0.11) "Termina la sessione" anche da qui: apre Pactum sulla conferma.
        if (attiva != null) {
            costruttore.addAction(
                0,
                getString(R.string.sessione_termina),
                AvvisiLocali.apriScheda(this, MainActivity.DEST_TERMINA_SESSIONE),
            )
        }
        return costruttore.build()
    }

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
        private const val MINIMO_ATTESA_MS = 50L

        fun avvia(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PactumService::class.java))
        }
    }
}
