package eu.stgm.pactum.genitore.servizio

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import eu.stgm.pactum.genitore.MainActivity
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.Impostazioni
import eu.stgm.pactum.genitore.sync.CadenzaVedetta
import eu.stgm.pactum.genitore.sync.EsitoGiro
import eu.stgm.pactum.genitore.sync.StatoAvvisi
import eu.stgm.pactum.genitore.sync.Vedetta
import eu.stgm.pactum.genitore.sync.statoAvvisi
import eu.stgm.pactum.genitore.ui.ID_NOTIFICA_FISSA
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * (0.9) Pactum sempre attivo: un servizio in primo piano di tipo specialUse
 * (v. manifest: PROPERTY_SPECIAL_USE_FGS_SUBTYPE), come quello dell'app del
 * figlio. Fa il giro della vedetta (Vedetta) circa ogni minuto, anche a schermo
 * spento: il genitore va avvisato quando il figlio sfora, non quando riaccende
 * il telefono. Prima c'era solo il worker ogni ~15 minuti, e su un telefono che
 * si apre poco Android lo rimandava anche di ore.
 *
 * Il ritmo lo dà una sveglia di Android (Sveglia), chiesta all'INIZIO di ogni
 * giro: un giro lento non sposta quello dopo. La sveglia tiene sveglio il
 * telefono solo il tempo del giro (Risveglio), e un giro non dura più di
 * CadenzaVedetta.GIRO_MASSIMO_MS. Cosa Android non garantisce: a telefono fermo
 * può ritardare le sveglie (meno con l'esenzione dalla batteria), e certe marche
 * fermano le app col loro risparmio batteria. La notifica fissa lo dice quando
 * serve ("Gli avvisi possono arrivare in ritardo: tocca per sistemare").
 *
 * Parte all'apertura dell'app, all'accensione del telefono, dopo un
 * aggiornamento (BootReceiver) e alla sveglia se era stato chiuso
 * (SvegliaReceiver), solo se l'app è configurata (indirizzo + codice); se il
 * collegamento viene tolto, si ferma da solo. Il worker resta come riserva e, se
 * il servizio manca, prova a rimetterlo in piedi.
 */
class VedettaService : Service() {

    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    /** Quello che dice adesso la notifica fissa: si riscrive solo quando cambia. */
    @Volatile
    private var statoMostrato: StatoAvvisi? = null

    override fun onCreate() {
        super.onCreate()
        creaCanale(this)
        // (0.21) Il canale degli avvisi a comparsa subito, anche prima del primo avviso.
        Vedetta.creaCanale(this)
        attivo = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val stato = statoAttuale()
        try {
            ServiceCompat.startForeground(
                this,
                ID_NOTIFICA_FISSA,
                notificaFissa(stato),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                },
            )
            statoMostrato = stato
        } catch (e: RuntimeException) {
            // Android non lascia partire il servizio adesso (per esempio da dietro
            // le quinte senza esenzione): meglio fermo che a metà. Ci riproverà
            // l'app alla prossima apertura, la sveglia o il worker.
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop?.isActive == true) {
            // Già in giro: l'app è stata aperta (o l'hanno riavviato). Un giro
            // subito, così chi apre l'app vede lo stato di adesso.
            Sveglia.suona()
        } else {
            avviaLoop()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        attivo = false
        ambito.cancel()
        Sveglia.annulla(this)
        Risveglio.lascia()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Uno solo: onStartCommand può arrivare più volte. */
    private fun avviaLoop() {
        loop = ambito.launch {
            var fallimenti = 0
            while (isActive) {
                // La sveglia del giro dopo si chiede PRIMA del giro: un giro lento
                // (server che tarda) non sposta il ritmo.
                val inizio = SystemClock.elapsedRealtime()
                val attesaPrevista = CadenzaVedetta.attesa(fallimenti, Vedetta.avvisiAccesi(applicationContext))
                Sveglia.dimenticaSuonate()
                Sveglia.programma(applicationContext, inizio + attesaPrevista)

                val esito = Risveglio.durante(applicationContext) {
                    withTimeoutOrNull(CadenzaVedetta.GIRO_MASSIMO_MS) { giroSicuro() } ?: EsitoGiro.SERVER_MUTO
                }
                when (esito) {
                    EsitoGiro.NON_CONFIGURATA -> {
                        fermati()
                        return@launch
                    }
                    EsitoGiro.SERVER_MUTO -> fallimenti++
                    EsitoGiro.FATTO -> fallimenti = 0
                    // Senza rete non è il server a tacere: si riguarda fra un minuto.
                    EsitoGiro.SENZA_RETE -> Unit
                }
                aggiornaNotificaFissa()

                // Se il giro ha cambiato l'attesa (server muto, avvisi spenti o
                // riaccesi), la sveglia si sposta, sempre contando dall'inizio.
                val attesa = CadenzaVedetta.attesa(fallimenti, Vedetta.avvisiAccesi(applicationContext))
                if (attesa != attesaPrevista) {
                    Sveglia.dimenticaSuonate()
                    Sveglia.programma(applicationContext, inizio + attesa)
                }
                Sveglia.aspetta(inizio + attesa)
            }
        }
    }

    /** Un giro che non fa mai cadere il servizio: un errore vale "server muto". */
    private suspend fun giroSicuro(): EsitoGiro = try {
        Vedetta(applicationContext).giro()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        EsitoGiro.SERVER_MUTO
    }

    /** Il collegamento non c'è più: via la notifica fissa, e il servizio si ferma. */
    private suspend fun fermati() {
        withContext(Dispatchers.Main) {
            ServiceCompat.stopForeground(this@VedettaService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun statoAttuale(): StatoAvvisi =
        statoAvvisi(Vedetta.avvisiAccesi(this), EsenzioneBatteria.concessa(this))

    /**
     * La notifica fissa dice la verità sugli avvisi: si riscrive quando lo
     * stato cambia (esenzione tolta o data, avvisi spenti o riaccesi).
     */
    private fun aggiornaNotificaFissa() {
        val stato = statoAttuale()
        if (stato == statoMostrato) return
        try {
            NotificationManagerCompat.from(this).notify(ID_NOTIFICA_FISSA, notificaFissa(stato))
            statoMostrato = stato
        } catch (e: SecurityException) {
            // senza permesso Android non la mostra: lo dice la Panoramica
        }
    }

    /**
     * La notifica fissa: silenziosa, importanza minima, e onesta. Se gli avvisi
     * sono in ordine, toccarla apre la Panoramica; se no dice che cosa non va e
     * porta alla sezione delle Impostazioni dove si sistema.
     */
    private fun notificaFissa(stato: StatoAvvisi): Notification {
        val testo = when (stato) {
            StatoAvvisi.IN_ORDINE -> R.string.attivo_notifica_testo
            StatoAvvisi.POSSIBILI_RITARDI -> R.string.attivo_notifica_ritardi
            StatoAvvisi.SPENTI -> R.string.attivo_notifica_spenti
        }
        val intent = Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (stato != StatoAvvisi.IN_ORDINE) {
            intent.putExtra(MainActivity.EXTRA_DESTINAZIONE, MainActivity.DEST_AVVISI)
        }
        val apriApp = PendingIntent.getActivity(
            this,
            if (stato == StatoAvvisi.IN_ORDINE) RICHIESTA_APRI_APP else RICHIESTA_APRI_AVVISI,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CANALE_ATTIVO)
            .setSmallIcon(R.drawable.ic_notifica_binocolo)
            .setContentTitle(getString(R.string.attivo_notifica_titolo))
            .setContentText(getString(testo))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(testo)))
            .setContentIntent(apriApp)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        /** Il canale della notifica fissa: separato da quello degli avvisi del patto. */
        private const val CANALE_ATTIVO = "pactum_attivo"

        /** I requestCode del tocco sulla notifica fissa (quelli degli avvisi sono hash di stringhe). */
        private const val RICHIESTA_APRI_APP = 1_800_000_001
        private const val RICHIESTA_APRI_AVVISI = 1_800_000_002

        /** true = il servizio c'è, in questo processo. */
        @Volatile
        var attivo: Boolean = false
            private set

        /**
         * Avvia (o riconferma) il servizio. false = Android non l'ha permesso
         * adesso (da Android 12, da dietro le quinte, senza un'eccezione di
         * Android): nessun crash, ci si riprova alla prossima occasione.
         */
        fun avvia(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, VedettaService::class.java))
            true
        } catch (e: IllegalStateException) {
            false // ForegroundServiceStartNotAllowedException (Android 12+) è un IllegalStateException
        } catch (e: SecurityException) {
            false
        }

        /** Lo avvia solo se l'app è configurata (indirizzo + codice). */
        suspend fun avviaSeConfigurata(context: Context): Boolean =
            Impostazioni(context).leggiConfigurazione().completa && avvia(context)

        /** Per il worker di riserva: se il servizio manca, prova a rimetterlo in piedi. */
        suspend fun riprendiSeServe(context: Context) {
            if (!attivo) avviaSeConfigurata(context)
        }

        private fun creaCanale(context: Context) {
            NotificationManagerCompat.from(context).createNotificationChannel(
                NotificationChannelCompat.Builder(CANALE_ATTIVO, NotificationManagerCompat.IMPORTANCE_MIN)
                    .setName(context.getString(R.string.canale_attivo_nome))
                    .setDescription(context.getString(R.string.canale_attivo_descrizione))
                    .setShowBadge(false)
                    .build(),
            )
        }
    }
}
