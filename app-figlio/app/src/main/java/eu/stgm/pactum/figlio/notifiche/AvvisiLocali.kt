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
import eu.stgm.pactum.figlio.faccende.TipiNotificaFaccende
import eu.stgm.pactum.figlio.permessi.PermessiHelper
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Gli avvisi locali del figlio: promemoria gentili sugli sforamenti, novità
 * dal patto (nuova proposta, verdetto, il segno del genitore; dalla 0.10 la
 * sua risposta a una proposta del figlio e il ritiro della sua) e la chiusura
 * della sera. Canale separato dal "testimone"
 * (IMPORTANCE_MIN, fisso): questi si devono vedere, ma senza allarme —
 * l'app non punisce, ricorda.
 */
object AvvisiLocali {

    const val CANALE_PATTO = "avvisi_patto"

    /**
     * (0.9) Lo sforamento quando l'avviso a tutto schermo non può partire
     * (niente "Mostra sopra le altre app", una chiamata in corso): importanza
     * alta, cioè il banner in alto sopra l'app in uso. Un canale nuovo apposta:
     * l'importanza di un canale già creato non si cambia più da codice.
     */
    const val CANALE_SFORAMENTI = "sforamenti"

    /**
     * (0.12) "Il tempo sta per finire": a 5 minuti e a 1 minuto dal limite.
     * Importanza alta (il banner in alto sopra l'app in uso), canale suo:
     * chi lo spegne non spegne gli sforamenti, e viceversa.
     */
    const val CANALE_PREAVVISI = "tempo_in_scadenza"

    /**
     * (0.13) Le faccende: quelle nuove, il blocco che parte, le bocciature e
     * gli annullamenti. Importanza alta (il banner in alto), canale suo: chi
     * lo spegne non spegne gli altri avvisi.
     */
    const val CANALE_FACCENDE = "faccende"

    /** Basi separate per non collidere tra loro né con la notifica fissa (FGS id 1). */
    private const val BASE_ID_SFORAMENTO = 1_000_000L
    private const val BASE_ID_SERVER = 2_000_000L

    /** Id fissi (tappa 6), fuori dalla portata delle basi sopra. */
    const val ID_MANOMISSIONE_PERMESSO = 3_000_001
    const val ID_AGGIORNAMENTO = 4_000_001

    /** La chiusura della sera: una al giorno, la nuova sostituisce quella di ieri. */
    const val ID_CHIUSURA_SERALE = 5_000_001

    /** (0.11) Una sessione partita senza che il ragazzo l'abbia vista partire (o non partita): una alla volta. */
    const val ID_SESSIONE = 7_000_001

    /** (0.12) "Sessione «Studio» finita", quando la pagina della fine non può aprirsi sopra le altre app. */
    const val ID_SESSIONE_FINITA = 7_000_002

    /** (0.13) "Prima le faccende: il telefono è bloccato": uno alla volta. */
    const val ID_BLOCCO_FACCENDE = 9_000_001

    /** (0.18) «Tra 5 minuti parte lo Studio». */
    const val ID_STUDIO_PREAVVISO = 9_200_001

    /** (0.18) Lo Studio: la chiusura non arrivata, l'avvio a mano rifiutato. */
    const val ID_STUDIO_AVVISO = 9_200_002

    /** (0.12) Il preavviso di una regola: quello di 1 minuto sostituisce quello di 5. */
    private const val BASE_ID_PREAVVISO = 8_000_000L

    /**
     * (0.10) La "Nuova proposta del genitore" ha l'id della SUA proposta, non
     * quello della notifica del server: se il genitore la ritira, la si toglie
     * dalla tendina col solo id della proposta (payload di `proposta_ritirata`).
     */
    private const val BASE_ID_PROPOSTA = 6_000_000L

    fun idSforamento(regolaId: Long): Int = (BASE_ID_SFORAMENTO + (regolaId % 100_000)).toInt()

    /** (0.12) L'id del preavviso della regola [regolaId]. */
    fun idPreavviso(regolaId: Long): Int = (BASE_ID_PREAVVISO + (regolaId % 100_000)).toInt()

    /**
     * (0.17, contratto v3.9) Le notifiche "ha cambiato un lavoro di casa" hanno
     * l'id del LAVORO: la nuova (16:00 → 18:00, poi 18:00 → 17:00) prende il
     * posto della vecchia, e in tendina non resta un'ora sbagliata.
     */
    private const val BASE_ID_LAVORO_CAMBIATO = 9_100_000L

    fun idLavoroCambiato(faccendaId: Long): Int = (BASE_ID_LAVORO_CAMBIATO + (faccendaId % 100_000)).toInt()

    /**
     * L'id della notifica locale per una notifica del server: la nuova proposta
     * con l'id della proposta ([idProposta]), un lavoro cambiato con quello del
     * lavoro ([idLavoroCambiato]), il resto con quello della notifica del server.
     */
    fun idPerNotifica(tipo: String, payload: JsonObject, idServer: Long): Int {
        fun numero(chiave: String) = (payload[chiave] as? JsonPrimitive)?.longOrNull
        return when (tipo) {
            TipiNotifica.NUOVA_PROPOSTA -> numero("proposta_id")?.let { idProposta(it) }
            TipiNotificaFaccende.FACCENDA_MODIFICATA -> numero("faccenda_id")?.let { idLavoroCambiato(it) }
            else -> null
        } ?: idNotificaServer(idServer)
    }

    /** (0.10) L'id della notifica locale che annuncia la proposta [propostaId]. */
    fun idProposta(propostaId: Long): Int = (BASE_ID_PROPOSTA + (propostaId % 100_000)).toInt()

    /** (0.10) Toglie dalla tendina una notifica, se c'è ancora; se non c'è, niente. */
    fun cancella(context: Context, id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
    }

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

    private fun creaCanaleSforamenti(context: Context) {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(
                CANALE_SFORAMENTI,
                NotificationManagerCompat.IMPORTANCE_HIGH,
            )
                .setName(context.getString(R.string.canale_sforamenti_nome))
                .setDescription(context.getString(R.string.canale_sforamenti_descrizione))
                .build(),
        )
    }

    private fun creaCanaleFaccende(context: Context) {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(
                CANALE_FACCENDE,
                NotificationManagerCompat.IMPORTANCE_HIGH,
            )
                .setName(context.getString(R.string.canale_faccende_nome))
                .setDescription(context.getString(R.string.canale_faccende_descrizione))
                .build(),
        )
    }

    private fun creaCanalePreavvisi(context: Context) {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(
                CANALE_PREAVVISI,
                NotificationManagerCompat.IMPORTANCE_HIGH,
            )
                .setName(context.getString(R.string.canale_preavvisi_nome))
                .setDescription(context.getString(R.string.canale_preavvisi_descrizione))
                .build(),
        )
    }

    /**
     * Alza una notifica; false se il permesso manca (il chiamante NON segna
     * l'avviso come fatto). (0.12) [scadeTra] = fra quanti ms se ne va da sola
     * (non vale più); [soloUnaVolta] = se c'è già, la si aggiorna senza suonare.
     */
    fun avvisa(
        context: Context,
        id: Int,
        titolo: String,
        testo: String,
        destinazione: String = MainActivity.DEST_OGGI,
        canale: String = CANALE_PATTO,
        scadeTra: Long? = null,
        soloUnaVolta: Boolean = false,
    ): Boolean {
        if (!puoAvvisare(context)) return false
        when (canale) {
            CANALE_SFORAMENTI -> creaCanaleSforamenti(context)
            CANALE_PREAVVISI -> creaCanalePreavvisi(context)
            CANALE_FACCENDE -> creaCanaleFaccende(context)
            else -> creaCanale(context)
        }
        val costruttore = NotificationCompat.Builder(context, canale)
            .setSmallIcon(R.drawable.ic_notifica_testimone)
            .setContentTitle(titolo)
            .setContentText(testo)
            .setStyle(NotificationCompat.BigTextStyle().bigText(testo))
            .setContentIntent(apriScheda(context, destinazione))
            .setAutoCancel(true)
            .setOnlyAlertOnce(soloUnaVolta)
        scadeTra?.takeIf { it > 0 }?.let { costruttore.setTimeoutAfter(it) }
        val notifica = costruttore.build()
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
        TipiNotifica.SESSIONE_RISPOSTA -> context.getString(R.string.tipo_sessione)
        in TipiNotificaFaccende.DEL_FIGLIO -> context.getString(R.string.tipo_faccende)
        // (0.18) La Sessione Studio (il testo è il messaggio del server).
        in eu.stgm.pactum.figlio.studio.TipiNotificaStudio.TUTTI -> context.getString(R.string.tipo_studio)
        else -> context.getString(R.string.tipo_novita)
    }

    /** (0.13) Il canale di una notifica del server: le faccende nel loro, il resto negli avvisi del patto. */
    fun canaleTipo(tipo: String): String = when (tipo) {
        // (0.17) Anche un lavoro cambiato o confermato (contratto v3.9).
        in TipiNotificaFaccende.DEL_FIGLIO -> CANALE_FACCENDE
        else -> CANALE_PATTO
    }

    /**
     * Su quale scheda aprire l'app toccando la notifica. Ogni tipo ha la sua:
     * un tocco non deve mai finire nel vuoto. I tipi che non si conoscono
     * ancora aprono Oggi, la schermata del patto. (0.10) La risposta del
     * genitore a una tua proposta e il ritiro della sua aprono Proposte.
     * (0.11) La sua decisione su una sessione apre Sessioni. (0.13) Le
     * faccende aprono la pagina Faccende.
     */
    fun destinazioneTipo(tipo: String): String = when (tipo) {
        TipiNotifica.NUOVA_PROPOSTA,
        TipiNotifica.PROPOSTA_RISPOSTA,
        TipiNotifica.PROPOSTA_RITIRATA,
        -> MainActivity.DEST_PROPOSTE
        TipiNotifica.VERDETTO -> MainActivity.DEST_DIARIO
        TipiNotifica.SESSIONE_RISPOSTA -> MainActivity.DEST_SESSIONI
        // (0.13) Le faccende (nuove, bocciate, annullate; 0.17 cambiate, confermate) aprono la loro pagina.
        in TipiNotificaFaccende.DEL_FIGLIO -> MainActivity.DEST_FACCENDE
        // (0.18) La decisione sulla configurazione apre Sessioni (dove c'è la Sessione Studio);
        // lo Studio chiuso da un genitore apre Oggi.
        eu.stgm.pactum.figlio.studio.TipiNotificaStudio.STUDIO_RISPOSTA -> MainActivity.DEST_SESSIONI
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
