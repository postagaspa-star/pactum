package eu.stgm.pactum.figlio.bilancio

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import eu.stgm.pactum.figlio.MainActivity
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.notifiche.AvvisiLocali
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime

/**
 * (0.24) Il promemoria di una promessa all'ora detta: «Avevi detto: … Fatto?» con tre
 * pulsanti (Fatto · Non fatto · Fra un'ora). Una volta sola per promessa (rimandarla
 * lo fa ripartire). Lo guarda il servizio all'ora esatta, il worker fa da riserva.
 * Niente blocchi: una promessa senza risposta torna nel bilancio della sera.
 */
object PromemoriaPromesse {

    /** Canale suo: chi lo spegne non spegne gli altri avvisi. Importanza alta: il banner in alto. */
    const val CANALE = "promesse"

    private const val BASE_ID = 9_300_000
    private val lucchetto = Mutex()

    fun idNotifica(promessaId: String): Int = BASE_ID + (promessaId.hashCode() and 0xFFFF)

    /**
     * Manda i promemoria arrivati all'ora e li segna. Restituisce il prossimo da
     * aspettare (null = nessuno), per il giro del servizio.
     */
    suspend fun controlla(context: Context, adesso: LocalDateTime = LocalDateTime.now()): LocalDateTime? =
        lucchetto.withLock {
            var diario = ArchivioBilancio.leggi(context)
            val daDare = LogicaBilancio.promemoriaDaDare(diario, adesso)
            // Senza il permesso delle notifiche non si segnano: la promessa si chiede nel bilancio.
            if (daDare.isNotEmpty() && AvvisiLocali.puoAvvisare(context)) {
                val mandati = daDare.filter { avvisa(context, it) }.map { it.id }.toSet()
                if (mandati.isNotEmpty()) diario = ArchivioBilancio.cambia(context) { LogicaBilancio.segnaAvvisate(it, mandati) }
            }
            LogicaBilancio.prossimoPromemoria(diario, adesso)
        }

    /** Toglie dalla tendina i promemoria di promesse ormai risposte o rimandate. */
    fun togliAvvisiRisolti(context: Context, diario: Diario) {
        val gestore = NotificationManagerCompat.from(context)
        diario.promesse.filter { it.esito != null || !it.avvisoDato }.forEach { gestore.cancel(idNotifica(it.id)) }
    }

    private fun creaCanale(context: Context) {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CANALE, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.canale_promesse_nome))
                .setDescription(context.getString(R.string.canale_promesse_descrizione))
                .build(),
        )
    }

    private fun avvisa(context: Context, promessa: Promessa): Boolean {
        creaCanale(context)
        val id = idNotifica(promessa.id)
        val notifica = NotificationCompat.Builder(context, CANALE)
            .setSmallIcon(R.drawable.ic_notifica_testimone)
            .setContentTitle(context.getString(R.string.promemoria_titolo))
            .setContentText(context.getString(R.string.promemoria_testo, promessa.testo))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.promemoria_testo, promessa.testo)))
            .setContentIntent(AvvisiLocali.apriScheda(context, MainActivity.DEST_BILANCIO))
            .addAction(0, context.getString(R.string.promessa_fatta), risposta(context, promessa.id, RispostaPromessaReceiver.FATTA))
            .addAction(0, context.getString(R.string.promessa_non_fatta), risposta(context, promessa.id, RispostaPromessaReceiver.NON_FATTA))
            .addAction(0, context.getString(R.string.promessa_tra_un_ora), risposta(context, promessa.id, RispostaPromessaReceiver.TRA_UN_ORA))
            .setAutoCancel(true)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(id, notifica)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    private fun risposta(context: Context, promessaId: String, azione: String): PendingIntent {
        val intent = Intent(context, RispostaPromessaReceiver::class.java)
            .setAction("eu.stgm.pactum.figlio.PROMESSA_$azione")
            .putExtra(RispostaPromessaReceiver.EXTRA_ID, promessaId)
            .putExtra(RispostaPromessaReceiver.EXTRA_AZIONE, azione)
        // Un codice per promessa e azione: ognuno il suo PendingIntent.
        return PendingIntent.getBroadcast(
            context,
            (promessaId + azione).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** (0.24) Un pulsante del promemoria: la risposta va nel diario, la notifica se ne va. Interno. */
class RispostaPromessaReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val azione = intent.getStringExtra(EXTRA_AZIONE) ?: return
        val app = context.applicationContext
        val fine = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ArchivioBilancio.cambia(app) { diario ->
                    when (azione) {
                        FATTA -> LogicaBilancio.rispondi(diario, id, EsitiPromessa.FATTA)
                        NON_FATTA -> LogicaBilancio.rispondi(diario, id, EsitiPromessa.NON_FATTA)
                        TRA_UN_ORA -> {
                            val (giorno, ora) = LogicaBilancio.traUnOra(LocalDateTime.now())
                            LogicaBilancio.rimanda(diario, id, giorno, ora)
                        }
                        else -> diario
                    }
                }
                NotificationManagerCompat.from(app).cancel(PromemoriaPromesse.idNotifica(id))
            } finally {
                fine.finish()
            }
        }
    }

    companion object {
        const val EXTRA_ID = "promessa_id"
        const val EXTRA_AZIONE = "azione"
        const val FATTA = "fatta"
        const val NON_FATTA = "non_fatta"
        const val TRA_UN_ORA = "tra_un_ora"
    }
}
