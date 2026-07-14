package eu.stgm.pactum.figlio.sync

import android.content.Context
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.stgm.pactum.figlio.BuildConfig
import eu.stgm.pactum.figlio.dati.AncoraTempo
import eu.stgm.pactum.figlio.dati.Battito
import eu.stgm.pactum.figlio.dati.CodaEventi
import eu.stgm.pactum.figlio.dati.Evento
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.dati.TipiEvento
import eu.stgm.pactum.figlio.misura.UsageStatsReader
import eu.stgm.pactum.figlio.permessi.PermessiHelper
import eu.stgm.pactum.figlio.rete.PostinoClient
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Il battito: ogni ~15 minuti rilegge l'uso del giorno, lo accoda al registro
 * e prova a consegnare battito + eventi al postino. Design retroattivo
 * (architettura.md): il sistema registra la storia d'uso da solo, quindi la
 * misura non dipende da un'app sempre viva. Se il telefono tace, è il server
 * a notare il gap nei battiti.
 */
class BattitoWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val impostazioni = Impostazioni(context)
        val coda = CodaEventi(context)

        // Ancora temporale aggiornata a ogni battito: OrologioReceiver la usa
        // per distinguere la sincronizzazione automatica dai cambi d'ora manuali.
        impostazioni.salvaAncoraTempo(
            AncoraTempo(
                wallClock = System.currentTimeMillis(),
                elapsedRealtime = SystemClock.elapsedRealtime(),
            ),
        )

        val configurazione = impostazioni.leggiConfigurazione()
        if (!configurazione.completa) return Result.success() // patto non ancora configurato

        if (PermessiHelper.haAccessoUso(context)) {
            coda.accoda(eventoUsoGiornaliero(context))
        }

        val postino = PostinoClient(configurazione)
        val battitoOk = postino.inviaBattito(
            Battito(
                tsDevice = System.currentTimeMillis(),
                versioneApp = BuildConfig.VERSION_NAME,
                elapsedRealtime = SystemClock.elapsedRealtime(),
            ),
        )

        val eventi = coda.inAttesa()
        val eventiOk = postino.inviaEventi(eventi)
        if (eventiOk) coda.rimuoviPrimi(eventi.size)

        return if (battitoOk && eventiOk) Result.success() else Result.retry()
    }

    /**
     * Fotografia cumulativa dell'uso di oggi. Il server, ricevendo più
     * fotografie dello stesso giorno, tiene l'ultima: idempotente per design.
     */
    private fun eventoUsoGiornaliero(context: Context): Evento {
        val oggi = LocalDate.now()
        val uso = UsageStatsReader(context).usoDelGiorno(oggi)
        return Evento(
            tipo = TipiEvento.USO_GIORNALIERO,
            tsDevice = System.currentTimeMillis(),
            dettagli = buildJsonObject {
                put("giorno", oggi.toString())
                put("uso_minuti", buildJsonObject {
                    uso.forEach { put(it.pacchetto, JsonPrimitive(it.millisPrimoPiano / 60_000)) }
                })
            },
        )
    }

    companion object {
        private const val NOME_LAVORO = "battito"

        /** KEEP: il ciclo dei 15 minuti non riparte da zero a ogni avvio dell'app. */
        fun pianifica(context: Context) {
            val richiesta = PeriodicWorkRequestBuilder<BattitoWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NOME_LAVORO, ExistingPeriodicWorkPolicy.KEEP, richiesta)
        }
    }
}
