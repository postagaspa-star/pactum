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
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.Impostazioni
import eu.stgm.pactum.genitore.dati.Notifica
import eu.stgm.pactum.genitore.dati.StatoSilenzio
import eu.stgm.pactum.genitore.dati.UsoGiorno
import eu.stgm.pactum.genitore.rete.PostinoClient
import eu.stgm.pactum.genitore.ui.istanteServer
import eu.stgm.pactum.genitore.ui.oraOppureDataOra
import eu.stgm.pactum.genitore.ui.testoDurata
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * La vedetta: ogni ~15 minuti chiede al postino le notifiche non lette e
 * alza UNA notifica di sistema per ogni novità mai avvisata prima (gli id
 * già avvisati vivono in DataStore). NON segna niente come letta sul server:
 * "letta" è un gesto del genitore dentro l'app, non un effetto collaterale
 * del polling — altrimenti le novità sparirebbero prima di essere viste.
 *
 * Sorveglia anche il silenzio: legge la finestra e avvisa quando
 * `stato_silenzio.silente` passa da false a true (e, con tono tranquillo,
 * quando il contatto torna). È la promessa dei "silenzi" nelle stringhe.
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

        avvisaNovitaDelPatto(context, impostazioni, notifiche)

        // Una lettura sola della finestra per le due sorveglianze (silenzio e
        // digest): meglio sforzo — se non arriva, si ritenta al giro dopo (le
        // notifiche sono già state gestite, niente Result.retry per questo).
        val finestra = postino.leggiFinestra()
        sorvegliaSilenzio(context, impostazioni, finestra)
        inviaDigest(context, impostazioni, finestra)

        // Auto-aggiornamento (tappa 6): best effort, non deve MAI far fallire il
        // giro della vedetta. Se il server ha una versione più nuova del binocolo,
        // scarica l'APK e lancia PackageInstaller; il primo update passa dal
        // dialogo di sistema, i successivi più silenziosi dove Android lo permette.
        runCatching { Aggiornatore(context).controlla() }

        return Result.success()
    }

    private suspend fun avvisaNovitaDelPatto(
        context: Context,
        impostazioni: Impostazioni,
        notifiche: List<Notifica>,
    ) {
        val giaAvvisate = impostazioni.leggiIdAvvisati()
        val nuove = notifiche.filter { it.id !in giaAvvisate }
        if (nuove.isEmpty()) return

        // Senza permesso non si avvisa E non si segna: appena il permesso
        // arriva, il giro successivo recupera le novità arretrate.
        if (!puoAvvisare(context)) return

        creaCanale(context)
        val gestore = NotificationManagerCompat.from(context)
        nuove.forEach { notifica ->
            try {
                gestore.notify(notifica.id.toInt(), notificaDiSistema(context, notifica))
            } catch (e: SecurityException) {
                return // permesso revocato tra il controllo e la notify
            }
        }
        impostazioni.registraIdAvvisati(nuove.map { it.id })
    }

    /**
     * Avvisa quando `silente` scatta a true e quando il contatto torna.
     * Lo stato osservato (silente + battito su cui si basa) vive in DataStore
     * per non ri-avvisare a ogni giro sullo stesso silenzio.
     */
    private suspend fun sorvegliaSilenzio(
        context: Context,
        impostazioni: Impostazioni,
        finestra: Finestra?,
    ) {
        if (finestra == null) return
        val attuale = finestra.statoSilenzio
        val noto = impostazioni.leggiSilenzioNoto()

        if (noto == null) {
            // Prima osservazione (app appena configurata): si prende la base
            // senza allarmare — un silenzio già in corso non è un "flip".
            impostazioni.registraSilenzioNoto(attuale.silente, attuale.ultimoBattito)
            return
        }

        // Un silenzio è nuovo anche se il ritorno in contatto non si è mai
        // visto: silente=true con un battito DIVERSO da quello osservato
        // significa contatto ripreso e riperso tra due giri della vedetta.
        val nuovoSilenzio = attuale.silente &&
            (!noto.silente || noto.ultimoBattito != attuale.ultimoBattito)
        val contattoTornato = !attuale.silente && noto.silente

        if (!nuovoSilenzio && !contattoTornato) {
            // Nessun flip: si aggiorna solo il battito su cui poggia lo stato.
            impostazioni.registraSilenzioNoto(attuale.silente, attuale.ultimoBattito)
            return
        }

        // Senza permesso non si avvisa E non si registra: il giro dopo recupera.
        if (!puoAvvisare(context)) return
        creaCanale(context)
        val avviso = if (nuovoSilenzio) {
            avvisoSilenzio(context, attuale)
        } else {
            avvisoContattoTornato(context, attuale)
        }
        try {
            // Stesso id per i due versi: "di nuovo in contatto" sostituisce
            // l'avviso di silenzio ormai superato invece di accodarsi.
            NotificationManagerCompat.from(context).notify(ID_AVVISO_SILENZIO, avviso)
        } catch (e: SecurityException) {
            return
        }
        impostazioni.registraSilenzioNoto(attuale.silente, attuale.ultimoBattito)
    }

    /**
     * Il digest giornaliero (contratto v2.2 — comportamento dell'app genitore,
     * nessun endpoint nuovo): quando l'ora scelta dal genitore è passata e oggi
     * il digest non è ancora partito, UNA notifica col totale di oggi e le prime
     * app (col limite accanto dove esiste), costruita dall'`uso_recente` della
     * finestra. Toccarla apre la sezione Tempo. Un oggi senza fotografia lo
     * dice onestamente — MAI uno zero finto. Dedup per giorno locale: l'ultimo
     * giorno inviato vive in DataStore.
     */
    private suspend fun inviaDigest(
        context: Context,
        impostazioni: Impostazioni,
        finestra: Finestra?,
    ) {
        if (finestra == null) return // offline o server muto: si ritenta al giro dopo
        val config = impostazioni.leggiConfigDigest()
        if (!config.attivo) return

        if (LocalTime.now().hour < config.ora) return // l'ora scelta (fuso del genitore) non è ancora arrivata

        // "Oggi" del patto = l'ultima voce di uso_recente: il server la etichetta
        // nel fuso del patto, così sia il digest sia il suo dedup restano allineati
        // ai dati e non dipendono dal fuso del telefono del genitore.
        val oggiPatto = finestra.usoRecente.lastOrNull()
        val giornoChiave = oggiPatto?.giorno ?: LocalDate.now().toString()
        if (impostazioni.leggiDigestUltimoGiorno() == giornoChiave) return // già mandato oggi

        // Senza permesso non si manda E non si registra: appena il permesso
        // arriva, il giro successivo recupera il digest di oggi.
        if (!puoAvvisare(context)) return
        creaCanale(context)

        val (titolo, testo) = testiDigest(context, oggiPatto)
        try {
            NotificationManagerCompat.from(context).notify(
                ID_DIGEST,
                notificaBase(context, titolo, testo, MainActivity.DEST_TEMPO),
            )
        } catch (e: SecurityException) {
            return // permesso revocato tra il controllo e la notify
        }
        impostazioni.registraDigestInviato(giornoChiave)
    }

    /** Titolo e testo del digest; [uso] null o senza totale = nessun dato di oggi. */
    private fun testiDigest(context: Context, uso: UsoGiorno?): Pair<String, String> {
        val totale = uso?.totaleMinuti
            ?: return context.getString(R.string.digest_titolo_nessun_dato) to
                context.getString(R.string.digest_testo_nessun_dato)

        val titolo = context.getString(
            R.string.digest_titolo,
            testoDurata(context, totale.toLong()),
        )
        val prime = uso.app
            .sortedByDescending { it.minuti }
            .take(APP_NEL_DIGEST)
            .joinToString(" · ") { app ->
                val nome = app.nome ?: app.chiave
                val durata = testoDurata(context, app.minuti.toLong())
                val limite = app.limite
                if (limite != null) {
                    context.getString(
                        R.string.digest_app_con_limite,
                        nome,
                        durata,
                        testoDurata(context, limite.toLong()),
                    )
                } else {
                    context.getString(R.string.digest_app, nome, durata)
                }
            }
        val corpo = if (prime.isEmpty()) {
            context.getString(R.string.digest_tocca)
        } else {
            context.getString(R.string.digest_testo, prime)
        }
        // Caveat di freschezza: se la fotografia è ferma da oltre 90 minuti, il
        // totale è un parziale — dillo, non spacciarlo per il consuntivo di oggi
        // (concept: il registro non mente, mai stantìo mostrato come corrente).
        val istante = istanteServer(uso.aggiornatoTs)
        val testo = if (istante != null &&
            Duration.between(istante, Instant.now()).toMinutes() > SOGLIA_FRESCHEZZA_MIN
        ) {
            corpo + "\n" + context.getString(R.string.digest_freschezza, oraOppureDataOra(istante))
        } else {
            corpo
        }
        return titolo to testo
    }

    private fun notificaDiSistema(context: Context, notifica: Notifica): Notification =
        notificaBase(
            context,
            titolo = context.getString(etichettaTipo(notifica.tipo)),
            testo = notifica.messaggio,
            destinazione = destinazionePerTipo(notifica.tipo),
        )

    private fun avvisoSilenzio(context: Context, stato: StatoSilenzio): Notification {
        val quando = istanteServer(stato.ultimoBattito)?.let { oraOppureDataOra(it) }
        val testo = if (quando != null) {
            context.getString(R.string.notifica_silenzio_testo, quando)
        } else {
            context.getString(R.string.notifica_silenzio_testo_mai)
        }
        // Il silenzio si guarda sulla finestra: la riga di stato è in cima.
        return notificaBase(
            context,
            titolo = context.getString(R.string.notifica_silenzio_titolo),
            testo = testo,
            destinazione = MainActivity.DEST_FINESTRA,
        )
    }

    private fun avvisoContattoTornato(context: Context, stato: StatoSilenzio): Notification {
        val quando = istanteServer(stato.ultimoBattito)?.let { oraOppureDataOra(it) } ?: "—"
        return notificaBase(
            context,
            titolo = context.getString(R.string.notifica_contatto_titolo),
            testo = context.getString(R.string.notifica_contatto_testo, quando),
            destinazione = MainActivity.DEST_FINESTRA,
        )
    }

    private fun notificaBase(
        context: Context,
        titolo: String,
        testo: String,
        destinazione: String? = null,
    ): Notification {
        val intent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (destinazione != null) {
            intent.putExtra(MainActivity.EXTRA_DESTINAZIONE, destinazione)
        }
        // requestCode diverso per destinazione: con lo stesso PendingIntent
        // Android riuserebbe l'extra del primo (le notifiche aprirebbero tutte
        // la stessa scheda). L'extra cambia → serve un codice diverso.
        val apriApp = PendingIntent.getActivity(
            context,
            (destinazione ?: "").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CANALE_ID)
            .setSmallIcon(R.drawable.ic_notifica_binocolo)
            .setContentTitle(titolo)
            .setContentText(testo)
            .setStyle(NotificationCompat.BigTextStyle().bigText(testo))
            .setContentIntent(apriApp)
            .setAutoCancel(true)
            .build()
    }

    companion object {
        private const val NOME_LAVORO = "vedetta"
        const val CANALE_ID = "avvisi_patto"

        /** Id fisso per l'avviso di silenzio/contatto, fuori dalla portata degli id del server. */
        private const val ID_AVVISO_SILENZIO = 2_000_000_000

        /** Id fisso per il digest giornaliero: quello di oggi sostituisce quello di ieri. */
        private const val ID_DIGEST = 2_000_000_001

        /** Quante app entrano nel testo del digest (il dettaglio vive nella sezione Tempo). */
        private const val APP_NEL_DIGEST = 3

        // Oltre questi minuti dall'ultima fotografia, il totale del digest è un
        // parziale e va etichettato come tale (il sync normale è ogni ~15 min).
        private const val SOGLIA_FRESCHEZZA_MIN = 90L

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

        /** Titolo leggibile per il tipo di notifica del patto. */
        fun etichettaTipo(tipo: String): Int = when (tipo) {
            "sforamento" -> R.string.tipo_sforamento
            "manomissione" -> R.string.tipo_manomissione
            "bonus" -> R.string.tipo_bonus
            "modifica_regola" -> R.string.tipo_modifica_regola
            // Tappa 5: il genitore riceve anche le risposte alle proposte e le
            // dichiarazioni del figlio (contratto-api.md, notifiche con destinatario).
            "proposta_risposta" -> R.string.tipo_proposta_risposta
            "dichiarazione" -> R.string.tipo_dichiarazione
            else -> R.string.tipo_novita // tipo nuovo dal server: tolleranza evolutiva
        }

        /**
         * Dove aprire l'app toccando la notifica (hook di navigazione). Le
         * risposte che toccano al genitore vanno su "Il tuo turno"; tutto il
         * resto apre la lista delle notifiche sopra la finestra, dove quella
         * stessa notifica si legge per intero e si segna come letta.
         */
        private fun destinazionePerTipo(tipo: String): String = when (tipo) {
            "proposta_risposta", "dichiarazione" -> MainActivity.DEST_TURNO
            else -> MainActivity.DEST_NOTIFICHE
        }
    }
}
