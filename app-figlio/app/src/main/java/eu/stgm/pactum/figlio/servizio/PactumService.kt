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
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import eu.stgm.pactum.figlio.MainActivity
import eu.stgm.pactum.figlio.diagnostica.TempiLog
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.avviso.Chiamata
import eu.stgm.pactum.figlio.bonus.ConsegnaBonus
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.dati.PattoLocale
import eu.stgm.pactum.figlio.faccende.ArchivioBlocco
import eu.stgm.pactum.figlio.faccende.ArchivioCodaFoto
import eu.stgm.pactum.figlio.faccende.ConsegnaFoto
import eu.stgm.pactum.figlio.faccende.ControlloBlocco
import eu.stgm.pactum.figlio.faccende.CoperturaFinestrelle
import eu.stgm.pactum.figlio.faccende.Orologio
import eu.stgm.pactum.figlio.faccende.FotoFaccenda
import eu.stgm.pactum.figlio.faccende.SorveglianzaFaccende
import eu.stgm.pactum.figlio.faccende.StatoBlocco
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
import eu.stgm.pactum.figlio.studio.ArchivioStudio
import eu.stgm.pactum.figlio.studio.ConsegnaStudio
import eu.stgm.pactum.figlio.studio.ControlloStudio
import eu.stgm.pactum.figlio.studio.OraServer
import eu.stgm.pactum.figlio.studio.SorveglianzaStudio
import eu.stgm.pactum.figlio.studio.StatoStudio
import eu.stgm.pactum.figlio.studio.TestoStudio
import eu.stgm.pactum.figlio.ui.paroleStudio
import eu.stgm.pactum.figlio.sync.BattitoCadenzato
import eu.stgm.pactum.figlio.sync.ConsegnaEventi
import eu.stgm.pactum.figlio.sync.Spegnimento
import eu.stgm.pactum.figlio.sync.SpegnimentoReceiver
import eu.stgm.pactum.figlio.catalogo.CatalogoApp
import eu.stgm.pactum.figlio.misura.Ripresa
import eu.stgm.pactum.figlio.misura.UsageStatsReader
import eu.stgm.pactum.figlio.valutatore.ProssimoGiro
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
 *
 * (0.13) Le faccende, in due loop a parte: il giro che chiede il blocco al
 * server (almeno ogni minuto a schermo acceso, subito allo sblocco, quando
 * arriva una notifica di faccende, una foto arriva o torna la rete) e manda le
 * foto in coda (avviaLoopFaccende); e, solo mentre il telefono è bloccato, la
 * barriera "Prima le faccende", circa una volta al secondo
 * (avviaLoopBarrieraFaccende). Col blocco la barriera delle Sessioni si fa da
 * parte: vale questa, più stretta.
 */
class PactumService : Service() {

    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopBattito: Job? = null
    private var loopSentinella: Job? = null
    private var loopSerale: Job? = null
    private var loopSessione: Job? = null
    private var loopFaccende: Job? = null
    private var loopBarrieraFaccende: Job? = null
    private var loopStudio: Job? = null
    private var loopBarrieraStudio: Job? = null

    // (0.13) Lo schermo che si riaccende o si sblocca sveglia il giro delle
    // faccende (il blocco si chiede subito). Un canale suo: quello della
    // sentinella lo consuma lei.
    private val accensioniFaccende = Channel<Unit>(Channel.CONFLATED)

    // (0.14) L'avviso di spegnimento del telefono (v. onCreate).
    private val ricevitoreSpegnimento = SpegnimentoReceiver()

    // (0.13) La rete che torna: le foto in coda partono subito, e il blocco si richiede.
    private val ascoltoRete = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            ConsegnaFoto.reteTornata(applicationContext)
            ControlloBlocco.richiedi()
            // (0.18) Lo Studio: la coda (avvio, tratti, chiusure) parte subito, e lo stato si rilegge.
            ConsegnaStudio.reteTornata(applicationContext)
            ControlloStudio.richiedi()
        }
    }

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
                    accensioniFaccende.trySend(Unit)
                    StatoBlocco.svegliati()
                    StatoStudio.svegliati()
                    ambito.launch(Dispatchers.IO) {
                        protetto { ArchivioSessioni.ricalcola(applicationContext) }
                        aggiornaNotifica(StatoSessione.attivaAdesso())
                        protetto { mostraFineSeServe() }
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    accensioni.trySend(Unit)
                    // (0.13) Allo sblocco dello schermo il blocco si chiede subito.
                    accensioniFaccende.trySend(Unit)
                    StatoBlocco.svegliati()
                    ambito.launch(Dispatchers.IO) { protetto { mostraFineSeServe() } }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        vivo = true
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
        try {
            getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(ascoltoRete)
        } catch (e: Exception) {
            // senza: le foto ripartono al giro, con attesa crescente
        }
        // (0.14) L'avviso di spegnimento: da Android 9 arriva solo a chi lo
        // ascolta mentre è vivo. La `sospensione` parte da qui.
        try {
            ContextCompat.registerReceiver(
                this,
                ricevitoreSpegnimento,
                IntentFilter().apply { Spegnimento.AZIONI.forEach { addAction(it) } },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } catch (e: Exception) {
            // senza: la `sospensione` la ritrova la riaccensione negli eventi d'uso
        }
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
        // (0.14) La sveglia del battito anche in stand-by.
        BattitoCadenzato.programma(applicationContext)
        avviaLoopSentinella()
        avviaLoopSerale()
        avviaLoopSessione()
        avviaLoopFaccende()
        avviaLoopBarrieraFaccende()
        avviaLoopStudio()
        avviaLoopBarrieraStudio()
        return START_STICKY
    }

    override fun onDestroy() {
        vivo = false
        try {
            unregisterReceiver(ricevitoreSchermo)
        } catch (e: IllegalArgumentException) {
            // mai registrato: niente da togliere
        }
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(ascoltoRete)
        } catch (e: Exception) {
            // mai registrato
        }
        try {
            unregisterReceiver(ricevitoreSpegnimento)
        } catch (e: IllegalArgumentException) {
            // mai registrato
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
                // (0.16) Lo stesso istante sull'orologio che non si sposta: l'attesa
                // del giro dopo conta da qui (CadenzaSentinella.resta), non dalla fine del giro.
                val adessoMono = SystemClock.elapsedRealtime()
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
                // (0.16) Le regole vicine al limite: fino al giro dopo, il controllo leggero.
                var vicino: ProssimoGiro? = null
                if (giro.oggi) {
                    // (0.12) A schermo acceso anche i preavvisi "il tempo sta per
                    // finire": il giro dopo arriva appena dopo la prossima soglia
                    // dell'app davanti, se viene prima del minuto. (0.16) E proprio
                    // quando una regola arriva al limite, o lo supera.
                    protetto {
                        val inizio = TempiLog.ora()
                        // (0.16) E, se una regola è vicina al limite o allo sforamento, fino
                        // ad allora il controllo leggero ogni pochi secondi (ControlloLeggero).
                        val prossimo = SentinellaPatto(applicationContext).valutaConPreavvisi(now = adesso, preavvisi = accesoOra)
                        attesa = CadenzaSentinella.attesa(prossimo)
                        vicino = prossimo.takeIf { it.vicino && accesoOra }
                        TempiLog.riga(
                            "giro",
                            TempiLog.da(inizio),
                            "prossimo=${attesa}ms vicine=${prossimo.vicine.size}",
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
                // (0.16) O meno se, vicino al limite, arriva davanti un'app della regola.
                spentoAdesso = aspettaGiro(
                    restaMs = CadenzaSentinella.resta(adessoMono, attesa, SystemClock.elapsedRealtime()),
                    vicino = vicino,
                    dal = adesso,
                )
            }
        }
    }

    /**
     * (0.16) L'attesa fino al giro dopo ([restaMs] ms). True se nel frattempo lo
     * schermo si è spento; false allo scadere, a un'accensione, o quando il
     * controllo leggero vuole subito il giro completo. Con [vicino] (regole a
     * meno di due minuti dal limite o dallo sforamento, schermo acceso), ogni
     * ControlloLeggero.PASSO_MS si guardano solo gli eventi da [dal] in poi
     * (con qualche secondo di margine all'indietro, senza contare due volte lo
     * stesso evento): se è arrivata davanti un'app che cade in una di quelle
     * regole, subito il giro completo. Mai il giorno intero. Un errore nel
     * controllo leggero non ferma il servizio: vale come "fai il giro completo".
     */
    private suspend fun aspettaGiro(restaMs: Long, vicino: ProssimoGiro?, dal: Long): Boolean {
        val fine = SystemClock.elapsedRealtime() + restaMs
        var davanti = vicino?.davanti.orEmpty()
        var ultimo = dal
        var viste = emptySet<Ripresa>()
        var controlli = 0
        var ultimoLog: Long? = null
        while (true) {
            val resta = fine - SystemClock.elapsedRealtime()
            if (resta <= 0) return false
            val schermo = withTimeoutOrNull(ControlloLeggero.passo(resta, vicino != null)) {
                select {
                    spegnimenti.onReceive { true }
                    accensioni.onReceive { false }
                }
            }
            if (schermo != null) return schermo
            if (vicino == null) return false
            val inizio = TempiLog.ora()
            val adesso = System.currentTimeMillis()
            controlli++
            var arrivate = 0
            var errore = false
            val giro = try {
                val (da, a) = ControlloLeggero.finestra(ultimo, adesso)
                val lette = ControlloLeggero.nuove(UsageStatsReader(applicationContext).ripreseTra(da, a), viste, adesso)
                viste = lette.viste
                ultimo = adesso
                val arrivi = ControlloLeggero.arrivi(lette.nuove, davanti)
                davanti = arrivi.davanti
                arrivate = arrivi.arrivate.size
                val filtro by lazy { CatalogoApp.filtroUso(applicationContext) }
                ControlloLeggero.serveGiro(
                    arrivate = arrivi.arrivate,
                    vicine = vicino.vicine,
                    conta = { filtro(it) },
                    cade = { chiave, pacchetto ->
                        ControlloLeggero.cade(chiave, pacchetto) { CatalogoApp.categoriaDiPacchetto(applicationContext, it) }
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Il PackageManager o il sistema hanno dato errore: il giro completo
                // (protetto) al posto di questo controllo, mai il servizio fermo.
                errore = true
                true
            }
            val mono = SystemClock.elapsedRealtime()
            if (ControlloLeggero.daLoggare(arrivate > 0 || errore, giro, ultimoLog, mono)) {
                TempiLog.riga(
                    "controllo-leggero",
                    TempiLog.da(inizio),
                    "controlli=$controlli arrivate=$arrivate giro=" + (if (giro) "si" else "no") + (if (errore) " errore" else ""),
                )
                ultimoLog = mono
                controlli = 0
            }
            if (giro) return false
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

    /**
     * (0.13) Il giro delle faccende: il blocco dal server almeno ogni minuto a
     * schermo acceso (subito allo sblocco, a una richiesta: notifica di
     * faccende, foto arrivata, rete tornata, sveglia), poi quello che ne
     * segue (ControlloBlocco.dopo: l'avviso, la sveglia, i permessi) e le foto
     * in coda. A schermo spento niente domande: ci pensano il worker e la
     * sveglia del blocco programmato.
     */
    private fun avviaLoopFaccende() {
        if (loopFaccende?.isActive == true) return
        val schermo = getSystemService(PowerManager::class.java)
        loopFaccende = ambito.launch(Dispatchers.IO) {
            protetto { ArchivioBlocco.leggi(applicationContext) }
            protetto { FotoFaccenda.pulisciScattiVecchi(applicationContext) }
            protetto { ArchivioCodaFoto.pulisciOrfani(applicationContext) }
            var ultimaDomanda: Long? = null
            while (isActive) {
                val acceso = schermo?.isInteractive ?: true
                val monotono = SystemClock.elapsedRealtime()
                val ultima = ultimaDomanda
                if (acceso && (ultima == null || monotono - ultima >= CadenzaSentinella.INTERVALLO_MS)) {
                    protetto { ControlloBlocco.interroga(applicationContext) }
                    ultimaDomanda = monotono
                } else {
                    protetto { ControlloBlocco.dopo(applicationContext) }
                }
                protetto { ConsegnaFoto.riprovaSeServe(applicationContext) }
                val prossimaDomanda = ultimaDomanda?.let { it + CadenzaSentinella.INTERVALLO_MS - SystemClock.elapsedRealtime() }
                val attesa = (prossimaDomanda ?: CadenzaSentinella.INTERVALLO_MS)
                    .coerceIn(ATTESA_MINIMA_FACCENDE_MS, CadenzaSentinella.INTERVALLO_MS)
                val svegliato = withTimeoutOrNull(attesa) {
                    select {
                        ControlloBlocco.richieste.onReceive { true }
                        accensioniFaccende.onReceive { true }
                    }
                } == true
                // Una richiesta, o lo schermo che si riaccende: si chiede subito.
                if (svegliato) ultimaDomanda = null
            }
        }
    }

    /**
     * (0.13) La barriera delle faccende, solo mentre il telefono è bloccato:
     * un giro circa ogni secondo a schermo acceso e sbloccato. Il blocco
     * dipende anche dall'ora (parte da solo all'ora di `prossimo`): fuori dal
     * blocco il giro dorme fino a quell'ora, o finché qualcosa lo sveglia (la
     * sveglia del blocco, lo schermo che si riaccende, una risposta nuova).
     */
    private fun avviaLoopBarrieraFaccende() {
        if (loopBarrieraFaccende?.isActive == true) return
        loopBarrieraFaccende = ambito.launch(Dispatchers.IO) {
            protetto { ArchivioBlocco.leggi(applicationContext) }
            // Un solo giro per tutto il blocco: il ritmo delle aperture (e il
            // suo interruttore di sicurezza) non riparte a ogni risposta del server.
            var sorveglianza: SorveglianzaFaccende? = null
            var bloccatoPrima = false
            while (isActive) {
                val ora = Orologio.adesso()
                val memoria = StatoBlocco.memoria.value
                // (0.18, contratto v4.0) Durante la Sessione Studio il blocco aspetta:
                // si ricontrolla spesso, così parte appena lo Studio finisce.
                if (memoria.attivoAdesso(ora) && !StatoBlocco.applicatoAdesso(ora)) {
                    if (bloccatoPrima) {
                        sorveglianza?.daCapo()
                        CoperturaFinestrelle.togli(applicationContext)
                    }
                    bloccatoPrima = false
                    withTimeoutOrNull(ATTESA_BLOCCO_IN_STUDIO_MS) {
                        select {
                            StatoBlocco.sveglia.onReceive { }
                            StatoStudio.sveglia.onReceive { }
                        }
                    }
                    if (StatoBlocco.applicatoAdesso()) protetto { ControlloBlocco.dopo(applicationContext) }
                    continue
                }
                if (!memoria.attivoAdesso(ora)) {
                    if (bloccatoPrima) {
                        // Finito: niente più copertura, e il prossimo blocco riparte da capo.
                        sorveglianza?.daCapo()
                        CoperturaFinestrelle.togli(applicationContext)
                    }
                    bloccatoPrima = false
                    val attesa = memoria.attesaPartenza(ora)?.coerceAtLeast(MINIMO_ATTESA_MS) ?: Long.MAX_VALUE
                    // Fino all'ora del blocco, o finché qualcosa cambia (una
                    // risposta nuova, la sveglia, lo schermo che si riaccende).
                    withTimeoutOrNull(attesa) { StatoBlocco.sveglia.receive() }
                    // Partito adesso: l'avviso, subito.
                    if (StatoBlocco.attivoAdesso()) protetto { ControlloBlocco.dopo(applicationContext) }
                    continue
                }
                val giro = sorveglianza ?: SorveglianzaFaccende(applicationContext).also { sorveglianza = it }
                // All'inizio di ogni blocco chi c'è davanti si cerca nelle ultime 24 ore.
                if (!bloccatoPrima) giro.daCapo()
                bloccatoPrima = true
                val attesa = try {
                    giro.giro(ora)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Dopo un errore, tutto da capo con la finestra lunga.
                    giro.daCapo()
                    SorveglianzaFaccende.ATTESA_ERRORE_MS
                }
                delay(attesa.coerceAtLeast(MINIMO_ATTESA_MS))
            }
        }
    }

    /**
     * (0.18, contratto v4.0) La Sessione Studio: il giro di controllo. Durante
     * lo Studio rilegge GET /api/studio almeno ogni minuto (subito a una
     * notifica studio_chiuso o studio_risposta, ControlloStudio.richiedi),
     * salva il punto del timer ogni 30 secondi (un riavvio chiude il tratto
     * lì), consegna la coda e aggiorna la notifica fissa con lo stato. Fuori
     * dallo Studio guarda la partenza (anche senza rete: la sveglia esatta lo
     * sveglia all'ora giusta) e rilegge lo Studio ogni quarto d'ora a schermo acceso.
     */
    private fun avviaLoopStudio() {
        if (loopStudio?.isActive == true) return
        val schermo = getSystemService(PowerManager::class.java)
        loopStudio = ambito.launch(Dispatchers.IO) {
            protetto { ArchivioStudio.leggi(applicationContext) }
            var ultimaLettura: Long? = null
            var inStudioPrima: Boolean? = null
            // Una rilettura chiesta (notifica dello Studio, avvio o chiusura appena
            // consegnati): si fa anche a schermo spento e fuori dallo Studio.
            var richiesta = false
            while (isActive) {
                val mono = SystemClock.elapsedRealtime()
                val acceso = schermo?.isInteractive ?: true
                val inStudio = StatoStudio.inCorsoAdesso(OraServer.adesso(applicationContext))
                val intervallo = if (inStudio) CadenzaSentinella.INTERVALLO_MS else INTERVALLO_STUDIO_FUORI_MS
                val ultima = ultimaLettura
                if ((inStudio || acceso || richiesta) && (ultima == null || mono - ultima >= intervallo)) {
                    protetto { ControlloStudio.interroga(applicationContext) }
                    ultimaLettura = mono
                    richiesta = false
                } else {
                    protetto { ControlloStudio.dopo(applicationContext) }
                }
                if (inStudio) {
                    protetto {
                        val o = OraServer.adesso(applicationContext)
                        ArchivioStudio.modifica(applicationContext) { it.conPuntoSalvato(o.ora) }
                    }
                }
                protetto { ConsegnaStudio.riprovaSeServe(applicationContext) }
                val oraInStudio = StatoStudio.inCorsoAdesso(OraServer.adesso(applicationContext))
                if (inStudio || oraInStudio || inStudioPrima != oraInStudio) aggiornaNotifica(StatoSessione.attivaAdesso())
                inStudioPrima = oraInStudio
                val attesa = if (oraInStudio) ATTESA_GIRO_STUDIO_MS else CadenzaSentinella.INTERVALLO_MS
                val svegliato = withTimeoutOrNull(attesa) {
                    select {
                        ControlloStudio.richieste.onReceive { true }
                        StatoStudio.sveglia.onReceive { false }
                    }
                }
                // Una notifica dello Studio, la rete tornata o una consegna: si rilegge subito.
                if (svegliato == true) {
                    ultimaLettura = null
                    richiesta = true
                }
            }
        }
    }

    /** (0.18) La barriera dello Studio, solo mentre lo Studio c'è: un giro circa ogni secondo. */
    private fun avviaLoopBarrieraStudio() {
        if (loopBarrieraStudio?.isActive == true) return
        loopBarrieraStudio = ambito.launch(Dispatchers.IO) {
            protetto { ArchivioStudio.leggi(applicationContext) }
            var sorveglianza: SorveglianzaStudio? = null
            while (isActive) {
                val o = OraServer.adesso(applicationContext)
                if (StatoStudio.attivoAdesso(o) == null) {
                    if (sorveglianza != null) {
                        // Lo Studio è finito: via anche la copertura delle finestrelle.
                        sorveglianza.azzera()
                        CoperturaFinestrelle.togli(applicationContext, CoperturaFinestrelle.Motivo.STUDIO)
                    }
                    sorveglianza = null
                    val prossima = StatoStudio.memoria.value.prossimaPartenza(o.server)
                    val attesa = prossima?.let { (it.inizio - o.server).coerceAtLeast(MINIMO_ATTESA_MS) } ?: ATTESA_STUDIO_FUORI_MS
                    withTimeoutOrNull(attesa.coerceAtMost(ATTESA_BARRIERA_STUDIO_FUORI_MS)) { StatoStudio.sveglia.receive() }
                    continue
                }
                val giro = sorveglianza ?: SorveglianzaStudio(applicationContext).also { sorveglianza = it }
                val attesa = try {
                    giro.giro(System.currentTimeMillis(), SystemClock.elapsedRealtime())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    giro.azzera()
                    SorveglianzaStudio.ATTESA_ERRORE_MS
                }
                delay(attesa.coerceAtLeast(MINIMO_ATTESA_MS))
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

    /**
     * (0.14) Il battito passa da BattitoCadenzato: un solo lucchetto con la
     * sveglia dello stand-by e col worker, mai due battiti insieme. Dopo,
     * la sveglia del prossimo (se non ce n'è già una in arrivo).
     */
    private suspend fun inviaBattito() {
        try {
            BattitoCadenzato.batti(applicationContext)
        } finally {
            BattitoCadenzato.programma(applicationContext)
        }
    }

    /**
     * La notifica fissa del testimone. (0.11) Con una Sessione in corso dice
     * quale e fino a quando: toccandola si arriva a Oggi, dove c'è "Termina la sessione".
     */
    private fun notificaTestimone(attiva: SessioneAttiva? = null): Notification {
        // (0.18, contratto v4.0) Durante la Sessione Studio la notifica fissa dice
        // lo stato: «Studio dalle 15:00 · 42 min su 60 · si chiude dopo le 16:00».
        val studio = testoStudio()
        val testo = studio ?: attiva?.let {
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
        if (attiva != null && studio == null) {
            costruttore.addAction(
                0,
                getString(R.string.sessione_termina),
                AvvisiLocali.apriScheda(this, MainActivity.DEST_TERMINA_SESSIONE),
            )
        }
        return costruttore.build()
    }

    /** (0.18) Lo stato dello Studio in corso per la notifica fissa; null se non c'è. */
    private fun testoStudio(): String? = try {
        val o = OraServer.adesso(applicationContext)
        val m = ArchivioStudio.leggi(applicationContext)
        m.attivo(o)?.let { s ->
            TestoStudio.stato(s, m.minutiStimati(s, o.ora, o.server), m.chiudibile(s, o.ora, o.server), m.zona(), paroleStudio(this))
        }
    } catch (e: Exception) {
        null
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

        /** (0.18) Durante lo Studio il blocco dovuto si ricontrolla così spesso (parte a fine Studio). */
        private const val ATTESA_BLOCCO_IN_STUDIO_MS = 15_000L

        /** (0.18) Il giro di controllo dello Studio: ogni mezzo minuto durante, la lettura ogni quarto d'ora fuori. */
        private const val ATTESA_GIRO_STUDIO_MS = 30_000L
        private const val INTERVALLO_STUDIO_FUORI_MS = 15L * 60 * 1000
        private const val ATTESA_STUDIO_FUORI_MS = 60_000L

        /** (0.18) Fuori dallo Studio la barriera guarda ogni pochi secondi se è partito (un avvio a mano, una risposta). */
        private const val ATTESA_BARRIERA_STUDIO_FUORI_MS = 5_000L

        /** (0.13) Tra una domanda e l'altra sul blocco, mai meno di un secondo. */
        private const val ATTESA_MINIMA_FACCENDE_MS = 1_000L

        /** (0.13) Il servizio è in piedi in questo processo (la sveglia del blocco non lo riavvia per niente). */
        @Volatile
        var vivo: Boolean = false
            private set

        fun avvia(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PactumService::class.java))
        }
    }
}
