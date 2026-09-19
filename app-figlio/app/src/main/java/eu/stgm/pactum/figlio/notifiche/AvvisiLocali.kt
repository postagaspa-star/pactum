package eu.stgm.pactum.figlio.notifiche

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import eu.stgm.pactum.figlio.MainActivity
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.dati.TipiNotifica
import eu.stgm.pactum.figlio.permessi.PermessiHelper

/**
 * Gli avvisi locali del figlio: promemoria gentili sugli sforamenti, novità
 * dal patto (nuova proposta, verdetto, il segno del genitore) e la chiusura
 * della sera. Canale separato dal "testimone"
 * (IMPORTANCE_MIN, fisso): questi si devono vedere, ma senza allarme —
 * l'app non punisce, ricorda.
 */
object AvvisiLocali {

    const val CANALE_PATTO = "avvisi_patto"

    /** Basi separate per non collidere tra loro né con la notifica fissa (FGS id 1). */
    private const val BASE_ID_SFORAMENTO = 1_000_000L
    private const val BASE_ID_SERVER = 2_000_000L

    /** Id fissi (tappa 6), fuori dalla portata delle basi sopra. */
    const val ID_MANOMISSIONE_PERMESSO = 3_000_001
    const val ID_AGGIORNAMENTO = 4_000_001

    /** La chiusura della sera: una al giorno, la nuova sostituisce quella di ieri. */
    const val ID_CHIUSURA_SERALE = 5_000_001

    fun idSforamento(regolaId: Long): Int = (BASE_ID_SFORAMENTO + (regolaId % 100_000)).toInt()

    /**
     * Id di notifica per una notifica del server: l'id grezzo del server
     * partirebbe da 1 e collide con la notifica fissa del testimone (FGS id 1),
     * che verrebbe sostituita. L'offset la mette fuori portata.
     */
    fun idNotificaServer(id: Long): Int = (BASE_ID_SERVER + (id % 100_000)).toInt()

    fun puoAvvisare(context: Context): Boolean = PermessiHelper.haPermessoNotifiche(context)

    /** Canale creato pigramente, solo quando c'è davvero qualcosa da dire. */
    fun creaCanale(context: Context) {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(
                CANALE_PATTO,
                NotificationManagerCompat.IMPORTANCE_DEFAULT,
            )
                .setName(context.getString(R.string.canale_patto_nome))
                .setDescription(context.getString(R.string.canale_patto_descrizione))
                .build(),
        )
    }

    /** Alza una notifica; false se il permesso manca (il chiamante NON segna l'avviso come fatto). */
    fun avvisa(
        context: Context,
        id: Int,
        titolo: String,
        testo: String,
        destinazione: String = MainActivity.DEST_OGGI,
    ): Boolean {
        if (!puoAvvisare(context)) return false
        creaCanale(context)
        val notifica = NotificationCompat.Builder(context, CANALE_PATTO)
            .setSmallIcon(R.drawable.ic_notifica_testimone)
            .setContentTitle(titolo)
            .setContentText(testo)
            .setStyle(NotificationCompat.BigTextStyle().bigText(testo))
            .setContentIntent(apriScheda(context, destinazione))
            .setAutoCancel(true)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(id, notifica)
            true
        } catch (e: SecurityException) {
            false // permesso revocato tra il controllo e la notify
        }
    }

    /**
     * Il tocco su una notifica: apre l'app sulla scheda giusta. requestCode
     * diverso per destinazione: con lo stesso PendingIntent Android riuserebbe
     * l'extra del primo e ogni notifica aprirebbe la stessa scheda.
     */
    fun apriScheda(context: Context, destinazione: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_DESTINAZIONE, destinazione)
        return PendingIntent.getActivity(
            context,
            destinazione.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Titolo leggibile per il tipo di notifica del server (tolleranza evolutiva). */
    fun titoloTipo(context: Context, tipo: String): String = when (tipo) {
        TipiNotifica.NUOVA_PROPOSTA -> context.getString(R.string.tipo_nuova_proposta)
        TipiNotifica.VERDETTO -> context.getString(R.string.tipo_verdetto)
        TipiNotifica.SEGNO -> context.getString(R.string.tipo_segno)
        else -> context.getString(R.string.tipo_novita)
    }

    /**
     * Su quale scheda aprire l'app toccando la notifica. Ogni tipo ha la sua:
     * un tocco non deve mai finire nel vuoto. I tipi che non si conoscono
     * ancora aprono Oggi, la schermata del patto.
     */
    fun destinazioneTipo(tipo: String): String = when (tipo) {
        TipiNotifica.NUOVA_PROPOSTA -> MainActivity.DEST_PROPOSTE
        TipiNotifica.VERDETTO -> MainActivity.DEST_DIARIO
        else -> MainActivity.DEST_OGGI
    }

    /**
     * L'aggiornamento è pronto e Android chiede conferma: si mostra come notifica
     * (l'avvio diretto dell'Activity dal background è soppresso), toccarla apre il
     * dialogo di installazione di sistema. [conferma] è l'intent di conferma dato
     * da PackageInstaller (già completo): FLAG_IMMUTABLE va bene.
     */
    fun avvisaAggiornamento(context: Context, conferma: Intent): Boolean {
        if (!puoAvvisare(context)) return false
        creaCanale(context)
        val apri = PendingIntent.getActivity(
            context,
            ID_AGGIORNAMENTO,
            conferma,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notifica = NotificationCompat.Builder(context, CANALE_PATTO)
            .setSmallIcon(R.drawable.ic_notifica_testimone)
            .setContentTitle(context.getString(R.string.notifica_aggiornamento_titolo))
            .setContentText(context.getString(R.string.notifica_aggiornamento_testo))
            .setContentIntent(apri)
            .setAutoCancel(true)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(ID_AGGIORNAMENTO, notifica)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun cancellaAggiornamento(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_AGGIORNAMENTO)
    }
}
