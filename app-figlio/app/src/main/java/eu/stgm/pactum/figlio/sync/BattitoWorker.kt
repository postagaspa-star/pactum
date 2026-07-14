package eu.stgm.pactum.figlio.sync

import android.content.Context
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
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
 * La misura + rete di sicurezza: ogni ~15 minuti rilegge l'uso del giorno (e
 * del giorno prima), lo accoda al registro e prova a consegnare battito +
 * eventi al postino. Design retroattivo (architettura.md): il sistema registra
 * la storia d'uso da solo, quindi la misura non dipende da un'app sempre viva.
 * Il canale PRIMARIO del battito è il loop dentro PactumService (Doze rinvia
 * il worker anche per ore); qui il battito resta come backstop — i doppi
 * battiti sono innocui lato server.
 *
 * Nessun vincolo di rete, di proposito: il worker deve SEMPRE girare e
 * misurare anche offline (una serata senza rete va comunque nel registro).
 * PostinoClient tollera l'offline e CodaEventi persiste: gli invii falliti
 * restano in coda per il giro successivo.
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
            val oggi = LocalDate.now()
            // Oggi + IERI: l'uso dopo l'ultima run del giorno andrebbe perso
            // per sempre (di notte il telefono dorme e la run di mezzanotte
            // non arriva). Il server tiene l'ultima fotografia per giorno,
            // quindi rimandare ieri è idempotente; la sostituzione per giorno
            // evita di riempire la coda di fotografie quasi identiche.
            coda.sostituisciUsoGiornaliero(eventoUsoGiornaliero(context, oggi))
            coda.sostituisciUsoGiornaliero(eventoUsoGiornaliero(context, oggi.minusDays(1)))
        }

        val postino = PostinoClient(configurazione)
        val battitoOk = postino.inviaBattito(
            Battito(
                tsDevice = System.currentTimeMillis(),
                versioneApp = BuildConfig.VERSION_NAME,
                elapsedRealtime = SystemClock.elapsedRealtime(),
            ),
        )
        if (battitoOk) impostazioni.registraBattitoConsegnato()

        val eventi = coda.inAttesa()
        val eventiOk = postino.inviaEventi(eventi)
        if (eventiOk) coda.rimuoviConsegnati(eventi)

        return if (battitoOk && eventiOk) Result.success() else Result.retry()
    }

    /**
     * Fotografia cumulativa dell'uso di [giorno]. Il server, ricevendo più
     * fotografie dello stesso giorno, tiene l'ultima: idempotente per design.
     */
    private fun eventoUsoGiornaliero(context: Context, giorno: LocalDate): Evento {
        val uso = UsageStatsReader(context).usoDelGiorno(giorno)
        return Evento(
            tipo = TipiEvento.USO_GIORNALIERO,
            tsDevice = System.currentTimeMillis(),
            dettagli = buildJsonObject {
                put("giorno", giorno.toString())
                put("uso_minuti", buildJsonObject {
                    uso.forEach { put(it.pacchetto, JsonPrimitive(it.millisPrimoPiano / 60_000)) }
                })
                // Totale del giorno dai millisecondi veri, non dalla somma dei
                // minuti arrotondati per app (contratto-api.md: totale_minuti).
                put("totale_minuti", uso.sumOf { it.millisPrimoPiano } / 60_000)
            },
        )
    }

    companion object {
        private const val NOME_LAVORO = "battito"

        /**
         * UPDATE: mantiene il ciclo dei 15 minuti già in corsa (niente riparti
         * da zero a ogni avvio dell'app) ma applica la richiesta nuova — con
         * KEEP un telefono che avesse già il worker in pancia si terrebbe per
         * sempre i vincoli vecchi (es. il vecchio vincolo di rete).
         */
        fun pianifica(context: Context) {
            val richiesta = PeriodicWorkRequestBuilder<BattitoWorker>(15, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NOME_LAVORO, ExistingPeriodicWorkPolicy.UPDATE, richiesta)
        }
    }
}
