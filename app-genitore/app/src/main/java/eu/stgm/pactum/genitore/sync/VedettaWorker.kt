package eu.stgm.pactum.genitore.sync

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.stgm.pactum.genitore.MainActivity
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.aggiornamento.Aggiornatore
import eu.stgm.pactum.genitore.dati.Figlio
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.Impostazioni
import eu.stgm.pactum.genitore.dati.Notifica
import eu.stgm.pactum.genitore.dati.RegolaFinestra
import eu.stgm.pactum.genitore.dati.SilenzioNoto
import eu.stgm.pactum.genitore.dati.StatoSilenzio
import eu.stgm.pactum.genitore.dati.TipiDispositivo
import eu.stgm.pactum.genitore.rete.EsitoFamiglia
import eu.stgm.pactum.genitore.rete.PostinoClient
import eu.stgm.pactum.genitore.ui.CHIAVE_SERVER_VECCHIO
import eu.stgm.pactum.genitore.ui.CambioSilenzio
import eu.stgm.pactum.genitore.ui.FUSO_PATTO
import eu.stgm.pactum.genitore.ui.ID_AVVISO_UNICO
import eu.stgm.pactum.genitore.ui.ID_DIGEST_07
import eu.stgm.pactum.genitore.ui.SilenzioAttuale
import eu.stgm.pactum.genitore.ui.TestoNotifica
import eu.stgm.pactum.genitore.ui.cambioSilenzio
import eu.stgm.pactum.genitore.ui.digestDopoAggiornamento
import eu.stgm.pactum.genitore.ui.dispositiviDellaFinestra
import eu.stgm.pactum.genitore.ui.etichettaDi
import eu.stgm.pactum.genitore.ui.etichettaNotifica
import eu.stgm.pactum.genitore.ui.idAvvisoSilenzio
import eu.stgm.pactum.genitore.ui.idDigest
import eu.stgm.pactum.genitore.ui.istanteServer
import eu.stgm.pactum.genitore.ui.oraOppureDataOra
import eu.stgm.pactum.genitore.ui.paroleDi
import eu.stgm.pactum.genitore.ui.partenzaSilenzi
import eu.stgm.pactum.genitore.ui.silenzioDaSorvegliare
import eu.stgm.pactum.genitore.ui.silenzioDelServerVecchio
import eu.stgm.pactum.genitore.ui.testoAvvisoSilenzio
import eu.stgm.pactum.genitore.ui.testoDigest
import eu.stgm.pactum.genitore.ui.testoNotifica
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * La vedetta: ogni ~15 minuti chiede al server le notifiche non lette e
 * alza UNA notifica di sistema per ogni novità mai avvisata prima (gli id
 * già avvisati vivono in DataStore). NON segna niente come letta sul server:
 * "letta" è un gesto del genitore dentro l'app, non un effetto collaterale
 * del polling — altrimenti le novità sparirebbero prima di essere viste.
 *
 * Sorveglia anche il silenzio: avvisa quando un dispositivo smette di mandare
 * dati (e, con tono tranquillo, quando il contatto torna). (v3) Il silenzio è
 * PER DISPOSITIVO, letto da GET /api/famiglia; un computer spento non è un
 * silenzio e non allarma. Su un server 0.7 si guarda la finestra, come prima.
 */
class VedettaWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val impostazioni = Impostazioni(context)

        val configurazione = impostazioni.leggiConfigurazione()
        if (!configurazione.completa) return Result.success() // patto non ancora configurato

        val postino = PostinoClient(configurazione)
        val notifiche = postino.leggiNotifiche()
            ?: return Result.retry() // offline o server muto: si riprova col backoff
        impostazioni.registraVerificaRiuscita()

        // (v3) La famiglia: di chi è ogni avviso, e lo stato di ogni dispositivo.
        // Meglio sforzo: se non arriva, le notifiche partono lo stesso (senza la
        // riga "di chi è") e il silenzio si guarda al giro dopo.
        val famiglia = postino.leggiFamiglia()
        val figli = (famiglia as? EsitoFamiglia.Letta)?.famiglia?.figli.orEmpty()
        val finestre = Finestre(postino)

        avvisaNovitaDelPatto(context, impostazioni, notifiche, figli, finestre)

        when (famiglia) {
            is EsitoFamiglia.Letta -> sorvegliaDispositivi(context, impostazioni, figli)
            EsitoFamiglia.ServerVecchio -> sorvegliaSilenzioServerVecchio(context, impostazioni, finestre.di(null))
            EsitoFamiglia.Fallita -> Unit // si ritenta al giro dopo
        }

        // Il digest: per figlio in v3, uno solo sul server 0.7. Con la famiglia
        // non letta si aspetta il giro dopo: non si sa di quanti figli dirlo.
        // Prima, una volta: il digest già mandato dalla versione di prima passa
        // al figlio che quella conosceva (l'id più basso; 0 sul server 0.7), se
        // no il giorno dell'aggiornamento il padre lo riceverebbe due volte.
        when (famiglia) {
            is EsitoFamiglia.Letta -> {
                val erede = figli.minOfOrNull { it.id }
                if (erede != null) {
                    impostazioni.passaggioDigest { inviati, giorno07 -> digestDopoAggiornamento(inviati, giorno07, erede) }
                }
                figli.forEach { figlio ->
                    inviaDigest(
                        context,
                        impostazioni,
                        chiave = figlio.id,
                        figlio = figlio,
                        figli = figli,
                        finestre = finestre,
                        erede = figlio.id == erede,
                    )
                }
            }
            EsitoFamiglia.ServerVecchio -> {
                impostazioni.passaggioDigest { inviati, giorno07 ->
                    digestDopoAggiornamento(inviati, giorno07, CHIAVE_SERVER_VECCHIO)
                }
                inviaDigest(
                    context,
                    impostazioni,
                    chiave = CHIAVE_SERVER_VECCHIO,
                    figlio = null,
                    figli = figli,
                    finestre = finestre,
                    erede = true,
                )
            }
            EsitoFamiglia.Fallita -> Unit
        }

        // Auto-aggiornamento (tappa 6): best effort, non deve MAI far fallire il
        // giro della vedetta. Se il server ha una versione più nuova del binocolo,
        // scarica l'APK e lancia PackageInstaller; il primo update passa dal
        // dialogo di sistema, i successivi più silenziosi dove Android lo permette.
        runCatching { Aggiornatore(context).controlla() }

        return Result.success()
    }

    /**
     * Le finestre lette in questo giro, una volta per figlio (null = server 0.7,
     * nessun `figlio_id`): servono ai testi delle notifiche e al digest.
     */
    private class Finestre(private val postino: PostinoClient) {
        private val lette = mutableMapOf<Long?, Finestra?>()
        suspend fun di(figlioId: Long?): Finestra? {
            if (figlioId !in lette) lette[figlioId] = postino.leggiFinestra(figlioId)
            return lette[figlioId]
        }
    }

    private suspend fun avvisaNovitaDelPatto(
        context: Context,
        impostazioni: Impostazioni,
        notifiche: List<Notifica>,
        figli: List<Figlio>,
        finestre: Finestre,
    ) {
        val giaAvvisate = impostazioni.leggiIdAvvisati()
        val nuove = notifiche.filter { it.id !in giaAvvisate }
        if (nuove.isEmpty()) return

        // Senza permesso non si avvisa E non si segna: appena il permesso
        // arriva, il giro successivo recupera le novità arretrate.
        if (!puoAvvisare(context)) return

        // Il nome leggibile delle regole viene dalla finestra DEL FIGLIO della
        // notifica: gli id delle regole sono unici su tutto il server, quindi le
        // regole di più figli stanno in una mappa sola.
        val regolePerId = mutableMapOf<Long, RegolaFinestra>()
        nuove.map { it.figlioId }.distinct().forEach { figlioId ->
            finestre.di(figlioId)?.regole?.forEach { regolePerId[it.id] = it }
        }

        creaCanale(context)
        val gestore = NotificationManagerCompat.from(context)
        nuove.forEach { notifica ->
            try {
                gestore.notify(
                    notifica.id.toInt(),
                    notificaDiSistema(context, notifica, regolePerId, figli),
                )
            } catch (e: SecurityException) {
                return // permesso revocato tra il controllo e la notify
            }
        }
        impostazioni.registraIdAvvisati(nuove.map { it.id })
    }

    /**
     * (v3) Il silenzio di OGNI dispositivo di OGNI figlio, dai flag del server.
     * Lo stato osservato (allarme + battito) vive in DataStore per dispositivo,
     * per non riavvisare a ogni giro lo stesso silenzio. Non si sorvegliano i
     * dispositivi non ancora collegati né quelli scollegati.
     *
     * Al primo giro per dispositivo (appena aggiornata dalla 0.7) lo stato della
     * 0.7 passa al dispositivo che la 0.7 guardava, e il suo avviso — con l'id di
     * un dispositivo solo, che nessuno aggiornerebbe più — si toglie
     * (partenzaSilenzi).
     */
    private suspend fun sorvegliaDispositivi(
        context: Context,
        impostazioni: Impostazioni,
        figli: List<Figlio>,
    ) {
        val partenza = partenzaSilenzi(
            salvati = impostazioni.leggiSilenziNoti(),
            versioneVecchia = impostazioni.leggiSilenzioDellaVersioneVecchia(),
            figli = figli,
        )
        val noti = partenza.noti
        val nuovi = mutableMapOf<Long, SilenzioNoto>()
        val parole = paroleDi(context)
        val gestore = NotificationManagerCompat.from(context)
        if (partenza.togliAvvisoUnico) gestore.cancel(ID_AVVISO_UNICO)
        figli.forEach { figlio ->
            figlio.dispositivi.forEach dispositivo@{ dispositivo ->
                val attuale = silenzioDaSorvegliare(
                    dispositivo.tipo,
                    dispositivo.abbinato,
                    dispositivo.revocato,
                    dispositivo.statoSilenzio,
                )
                if (attuale == null) {
                    // Scollegato: un vecchio avviso di silenzio non ha più senso.
                    if (dispositivo.revocato) gestore.cancel(idAvvisoSilenzio(dispositivo.id))
                    return@dispositivo
                }
                val noto = noti[dispositivo.id]
                val testo = testoAvvisoSilenzio(
                    parole,
                    cambioSilenzio(noto, attuale),
                    computer = dispositivo.tipo == TipiDispositivo.COMPUTER,
                    silenzio = dispositivo.statoSilenzio,
                )
                val avvisato = testo == null || avvisa(
                    context,
                    idAvvisoSilenzio(dispositivo.id),
                    testo,
                    sopra = etichettaDi(figlio.id, dispositivo.id, figli),
                    figlioId = figlio.id,
                )
                // Senza permesso (o permesso tolto a metà) non si registra: il
                // giro dopo ritrova il cambio e avvisa.
                nuovi[dispositivo.id] = if (avvisato || noto == null) attuale.noto() else noto
            }
        }
        impostazioni.salvaSilenziNoti(nuovi)
    }

    /**
     * Server 0.7: un dispositivo solo, dal silenzio della finestra, coi testi di
     * prima ("L'app del figlio non invia aggiornamenti dalle 15:10").
     */
    private suspend fun sorvegliaSilenzioServerVecchio(
        context: Context,
        impostazioni: Impostazioni,
        finestra: Finestra?,
    ) {
        val silenzio = finestra?.statoSilenzio ?: return
        val attuale = SilenzioAttuale(
            allarme = silenzio.silente,
            spento = false,
            ultimoBattito = silenzio.ultimoBattito,
        )
        // Appena aggiornata dalla 0.7 vale lo stato della 0.7: stesso dispositivo,
        // stesso id dell'avviso.
        val noto = silenzioDelServerVecchio(
            impostazioni.leggiSilenziNoti(),
            impostazioni.leggiSilenzioDellaVersioneVecchia(),
        )
        val testo = when (cambioSilenzio(noto, attuale)) {
            CambioSilenzio.NUOVO_SILENZIO -> testoSilenzioServerVecchio(context, silenzio)
            CambioSilenzio.CONTATTO_TORNATO -> testoContattoServerVecchio(context, silenzio)
            else -> null
        }
        val avvisato = testo == null || avvisa(
            context,
            idAvvisoSilenzio(CHIAVE_SERVER_VECCHIO),
            testo,
            sopra = null,
            figlioId = null,
        )
        val registrato = if (avvisato || noto == null) attuale.noto() else noto
        impostazioni.salvaSilenziNoti(mapOf(CHIAVE_SERVER_VECCHIO to registrato))
    }

    /** Alza un avviso sul canale del patto; false = senza permesso, non partito. */
    private fun avvisa(
        context: Context,
        id: Int,
        testo: TestoNotifica,
        sopra: String?,
        figlioId: Long?,
    ): Boolean {
        if (!puoAvvisare(context)) return false
        creaCanale(context)
        return try {
            // Stesso id per i due versi: "di nuovo in contatto" sostituisce
            // l'avviso di silenzio ormai superato invece di accodarsi.
            NotificationManagerCompat.from(context).notify(
                id,
                notificaBase(context, testo.titolo, testo.testo, MainActivity.DEST_FINESTRA, sopra, figlioId),
            )
            true
        } catch (e: SecurityException) {
            false
        }
    }

    /**
     * Il digest giornaliero (contratto v2.2 — comportamento dell'app genitore,
     * nessun endpoint nuovo): quando l'ora scelta dal genitore è passata e oggi
     * il digest di quel figlio non è ancora partito, UNA notifica col totale di
     * oggi e le prime app (per dispositivo, se sono più d'uno). Toccarla apre il
     * Tempo di quel figlio. Un oggi senza fotografia lo dice onestamente — MAI
     * uno zero finto. Dedup per figlio e per giorno del patto.
     * [erede] = il figlio che conosceva la 0.7 (l'id più basso): il suo digest
     * prende il posto di quello della 0.7, che aveva un id suo.
     */
    private suspend fun inviaDigest(
        context: Context,
        impostazioni: Impostazioni,
        chiave: Long,
        figlio: Figlio?,
        figli: List<Figlio>,
        finestre: Finestre,
        erede: Boolean,
    ) {
        val config = impostazioni.leggiConfigDigest()
        if (!config.attivo) return

        // (v3) Un figlio senza nessun dispositivo collegato (e non scollegato) non
        // ha niente da raccontare: un "nessun dato" ogni sera sarebbe solo rumore.
        if (figlio != null && figlio.dispositivi.none { it.abbinato && !it.revocato }) return

        if (LocalTime.now().hour < config.ora) return // l'ora scelta (fuso del genitore) non è ancora arrivata
        // Già mandato per l'oggi del patto: non serve nemmeno leggere la finestra.
        if (impostazioni.digestGiaInviato(chiave, LocalDate.now(FUSO_PATTO).toString())) return

        // offline o server muto: si ritenta al giro dopo
        val finestra = finestre.di(figlio?.id) ?: return

        // "Oggi" del patto = l'ultima voce di uso_recente: il server la etichetta
        // nel fuso del patto, così sia il digest sia il suo dedup restano allineati
        // ai dati e non dipendono dal fuso del telefono del genitore.
        val dispositivi = dispositiviDellaFinestra(finestra)
        val giornoChiave = dispositivi.firstNotNullOfOrNull { it.usoRecente.lastOrNull()?.giorno }
            ?: LocalDate.now(FUSO_PATTO).toString()
        if (impostazioni.digestGiaInviato(chiave, giornoChiave)) return // già mandato oggi

        // Senza permesso non si manda E non si registra: appena il permesso
        // arriva, il giro successivo recupera il digest di oggi.
        if (!puoAvvisare(context)) return
        creaCanale(context)

        val testo = testoDigest(paroleDi(context), dispositivi)
        val gestore = NotificationManagerCompat.from(context)
        // "Quello di oggi sostituisce quello di ieri" anche a cavallo
        // dell'aggiornamento: il digest della 0.7 aveva un altro id.
        if (erede) gestore.cancel(ID_DIGEST_07)
        try {
            gestore.notify(
                idDigest(chiave),
                notificaBase(
                    context,
                    testo.titolo,
                    testo.testo,
                    MainActivity.DEST_TEMPO,
                    sopra = figlio?.nome?.takeIf { figli.size > 1 && it.isNotBlank() },
                    figlioId = figlio?.id,
                ),
            )
        } catch (e: SecurityException) {
            return // permesso revocato tra il controllo e la notify
        }
        impostazioni.registraDigestInviato(chiave, giornoChiave)
    }

    /** Titolo e frase dalla stessa funzione della lista in app (testoNotifica, Testi.kt). */
    private fun notificaDiSistema(
        context: Context,
        notifica: Notifica,
        regolePerId: Map<Long, RegolaFinestra>,
        figli: List<Figlio>,
    ): Notification {
        val testo = testoNotifica(paroleDi(context), notifica, regolePerId)
        return notificaBase(
            context,
            titolo = testo.titolo,
            testo = testo.testo,
            destinazione = destinazionePerTipo(notifica.tipo),
            // (v3) Di quale figlio (e dispositivo): la stessa riga della lista in app.
            sopra = etichettaNotifica(notifica, figli),
            figlioId = notifica.figlioId,
        )
    }

    private fun testoSilenzioServerVecchio(context: Context, stato: StatoSilenzio): TestoNotifica {
        val quando = istanteServer(stato.ultimoBattito)?.let { oraOppureDataOra(it) }
        val testo = if (quando != null) {
            context.getString(R.string.notifica_silenzio_testo, quando)
        } else {
            context.getString(R.string.notifica_silenzio_testo_mai)
        }
        // Il silenzio si guarda sulla finestra: la riga di stato è in cima.
        return TestoNotifica(context.getString(R.string.notifica_silenzio_titolo), testo)
    }

    private fun testoContattoServerVecchio(context: Context, stato: StatoSilenzio): TestoNotifica {
        val quando = istanteServer(stato.ultimoBattito)?.let { oraOppureDataOra(it) } ?: "—"
        return TestoNotifica(
            context.getString(R.string.notifica_contatto_titolo),
            context.getString(R.string.notifica_contatto_testo, quando),
        )
    }

    /**
     * [sopra] = la riga "di chi è" (figlio · dispositivo), mostrata sopra il
     * titolo; [figlioId] = il figlio da scegliere quando si tocca la notifica.
     */
    private fun notificaBase(
        context: Context,
        titolo: String,
        testo: String,
        destinazione: String? = null,
        sopra: String? = null,
        figlioId: Long? = null,
    ): Notification {
        val intent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (destinazione != null) {
            intent.putExtra(MainActivity.EXTRA_DESTINAZIONE, destinazione)
        }
        if (figlioId != null) {
            intent.putExtra(MainActivity.EXTRA_FIGLIO, figlioId)
        }
        // requestCode diverso per destinazione e figlio: con lo stesso
        // PendingIntent Android riuserebbe gli extra del primo (le notifiche
        // aprirebbero tutte la stessa scheda, sullo stesso figlio).
        val apriApp = PendingIntent.getActivity(
            context,
            "${destinazione.orEmpty()}|${figlioId ?: ""}".hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CANALE_ID)
            .setSmallIcon(R.drawable.ic_notifica_binocolo)
            .setContentTitle(titolo)
            .setContentText(testo)
            .setSubText(sopra)
            .setStyle(NotificationCompat.BigTextStyle().bigText(testo))
            .setContentIntent(apriApp)
            .setAutoCancel(true)
            .build()
    }

    companion object {
        private const val NOME_LAVORO = "vedetta"
        const val CANALE_ID = "avvisi_patto"

        // Gli id degli avvisi (silenzio per dispositivo, digest per figlio) e la
        // chiave del server 0.7 stanno in LogicaFamiglia.kt, provati da JUnit.

        private fun SilenzioAttuale.noto(): SilenzioNoto = SilenzioNoto(allarme, ultimoBattito, spento)

        /**
         * UPDATE: mantiene il ciclo dei 15 minuti già in corsa (niente riparti
         * da zero a ogni avvio dell'app) ma applica la richiesta nuova.
         */
        fun pianifica(context: Context) {
            val richiesta = PeriodicWorkRequestBuilder<VedettaWorker>(15, TimeUnit.MINUTES)
                // Rete richiesta: la vedetta SOLO interroga (l'opposto del BattitoWorker
                // del figlio, che deve girare anche offline perché MISURA) — senza rete
                // sarebbero solo catene di retry a vuoto tutta la notte.
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NOME_LAVORO, ExistingPeriodicWorkPolicy.UPDATE, richiesta)
        }

        /** Canale creato pigramente, solo quando c'è davvero qualcosa da dire. */
        fun creaCanale(context: Context) {
            NotificationManagerCompat.from(context).createNotificationChannel(
                NotificationChannelCompat.Builder(
                    CANALE_ID,
                    NotificationManagerCompat.IMPORTANCE_DEFAULT,
                )
                    .setName(context.getString(R.string.canale_patto_nome))
                    .setDescription(context.getString(R.string.canale_patto_descrizione))
                    .build(),
            )
        }

        fun puoAvvisare(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                NotificationManagerCompat.from(context).areNotificationsEnabled()
            }

        /**
         * Dove aprire l'app toccando la notifica (hook di navigazione). Le
         * risposte che toccano al genitore vanno su "Proposte e conferme"; tutto il
         * resto apre la lista delle notifiche sopra la finestra, dove quella
         * stessa notifica si legge per intero e si segna come letta.
         */
        private fun destinazionePerTipo(tipo: String): String = when (tipo) {
            "proposta_risposta", "proposta_annullata", "dichiarazione" -> MainActivity.DEST_TURNO
            else -> MainActivity.DEST_NOTIFICHE
        }
    }
}
