package eu.stgm.pactum.genitore.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.stgm.pactum.genitore.aggiornamento.Aggiornatore
import eu.stgm.pactum.genitore.servizio.VedettaService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.TimeUnit

/**
 * La vedetta di RISERVA (0.9). Fino alla 0.8 era l'unica: WorkManager ogni ~15
 * minuti, e su un telefono che si apre poco Android la rimandava anche di ore —
 * uno sforamento arrivato al server restava lì senza avviso. Dalla 0.9 la
 * vedetta vera è il servizio sempre attivo (VedettaService, un giro circa ogni
 * minuto); questo worker fa lo stesso giro (Vedetta) quando Android lo lascia
 * girare, e se il servizio non c'è (fermato da Android, o mai partito) prova a
 * rimetterlo in piedi. I due non avvisano mai due volte la stessa novità: il
 * giro è uno alla volta, e il dedup è lo stesso.
 *
 * L'auto-aggiornamento sta SOLO qui: scaricare l'APK può durare minuti, e nel
 * servizio fermerebbe i giri di ogni minuto.
 */
class VedettaWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        VedettaService.riprendiSeServe(applicationContext)
        val esito = Vedetta(applicationContext).giro()
        if (esito == EsitoGiro.FATTO) controllaAggiornamento()
        return when (esito) {
            // offline o server muto: si riprova col backoff
            EsitoGiro.SENZA_RETE, EsitoGiro.SERVER_MUTO -> Result.retry()
            // patto non ancora configurato, o giro fatto
            EsitoGiro.NON_CONFIGURATA, EsitoGiro.FATTO -> Result.success()
        }
    }

    /**
     * Auto-aggiornamento (tappa 6): best effort, non deve MAI far fallire il
     * giro della vedetta. Se il server ha una versione più nuova del binocolo,
     * scarica l'APK e lancia PackageInstaller; il primo update passa dal
     * dialogo di sistema, i successivi più silenziosi dove Android lo permette.
     * Uno alla volta: se un download lento è ancora in corso, questo giro salta.
     */
    private suspend fun controllaAggiornamento() {
        if (!aggiornamento.tryLock()) return
        try {
            Aggiornatore(applicationContext).controlla()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // il prossimo giro del worker ci riprova
        } finally {
            aggiornamento.unlock()
        }
    }

    companion object {
        private const val NOME_LAVORO = "vedetta"

        /** Un controllo degli aggiornamenti alla volta: due download sullo stesso file no. */
        private val aggiornamento = Mutex()

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
    }
}
