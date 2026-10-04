package eu.stgm.pactum.figlio.fotografo

import android.app.Notification
import android.app.NotificationManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.app.NotificationCompat
import eu.stgm.pactum.figlio.MainActivity
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.dati.TipiNotifica
import eu.stgm.pactum.figlio.faccende.TestoFaccende
import eu.stgm.pactum.figlio.giornata.Chiusura
import eu.stgm.pactum.figlio.giornata.ParoleSerale
import eu.stgm.pactum.figlio.giornata.TestoSerale
import eu.stgm.pactum.figlio.notifiche.AvvisiLocali
import eu.stgm.pactum.figlio.notifiche.NovitaDalPatto
import eu.stgm.pactum.figlio.sessione.AnnunciSessione
import eu.stgm.pactum.figlio.sessione.SessioneAttiva
import eu.stgm.pactum.figlio.sessione.TestoSessioni
import eu.stgm.pactum.figlio.sessione.nomeSessioneTraVirgolette
import eu.stgm.pactum.figlio.ui.TestoProposta
import eu.stgm.pactum.figlio.ui.raccontoProposta
import eu.stgm.pactum.figlio.ui.testoDurata
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import java.time.ZoneId

/**
 * 18 — Le notifiche: alzate con il codice vero dell'app (AvvisiLocali,
 * AnnunciSessione) e disegnate col modello di notifica di Android (la parte
 * "espansa"), su una carta che imita la tendina. La tendina vera (la cornice,
 * l'ora, l'icona dell'app) la disegna il telefono: qui non c'è.
 */
class FotoNotificheTest : Fotografo() {

    private val notifiche get() = shadowOf(app.getSystemService(NotificationManager::class.java))

    /** Disegna le notifiche [ids] una sotto l'altra, come nella tendina. */
    private fun tendina(vararg ids: Int): Aperta {
        val controller = apriVuota()
        val activity = controller.get()
        val scuro = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, activity.resources.displayMetrics).toInt() }
        val colonna = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (scuro) Color.rgb(0x12, 0x12, 0x14) else Color.rgb(0xE6, 0xE6, 0xEB))
            setPadding(dp(12f), dp(24f), dp(12f), dp(24f))
        }
        for (id in ids) {
            val notifica: Notification = notifiche.getNotification(id) ?: continue
            val costruttore = Notification.Builder.recoverBuilder(activity, notifica)
            val remote = costruttore.createBigContentView() ?: costruttore.createContentView()
            val carta = FrameLayout(activity).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(20f).toFloat()
                    setColor(if (scuro) Color.rgb(0x2A, 0x2A, 0x2E) else Color.WHITE)
                }
                setPadding(0, dp(4f), 0, dp(4f))
            }
            carta.addView(remote.apply(activity, carta), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            colonna.addView(
                carta,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8f) },
            )
        }
        activity.setContentView(colonna)
        shadowOf(Looper.getMainLooper()).idle()
        return controller.comeAperta()
    }

    private fun avvisa(id: Int, titolo: String, testo: String, canale: String = AvvisiLocali.CANALE_PATTO) {
        AvvisiLocali.avvisa(app, id = id, titolo = titolo, testo = testo, destinazione = MainActivity.DEST_OGGI, canale = canale)
    }

    @Test
    fun avvisiDelTempo() {
        avvisa(
            AvvisiLocali.idPreavviso(1),
            app.getString(R.string.preavviso_titolo),
            app.resources.getQuantityString(R.plurals.preavviso_testo, 5, "Instagram", 5),
            AvvisiLocali.CANALE_PREAVVISI,
        )
        avvisa(
            AvvisiLocali.idSforamento(2),
            app.getString(R.string.notifica_sforamento_limite_titolo),
            app.getString(R.string.notifica_sforamento_limite_testo, "TikTok", 7, 45),
            AvvisiLocali.CANALE_SFORAMENTI,
        )
        avvisa(
            AvvisiLocali.idSforamento(4),
            app.getString(R.string.notifica_sforamento_fascia_titolo),
            app.getString(R.string.notifica_sforamento_fascia_testo, 12, "22:30", "07:00"),
            AvvisiLocali.CANALE_SFORAMENTI,
        )
        val parole = ParoleSerale(
            dentro = app.getString(R.string.serale_dentro),
            finoraDentro = app.getString(R.string.serale_finora_dentro),
            giornoInParole = app.getString(R.string.serale_giorno_in_parole),
            ordinali = app.resources.getStringArray(R.array.serale_ordinali).toList(),
            giornoInCifre = app.getString(R.string.serale_giorno_in_cifre),
            oltre = app.getString(R.string.serale_oltre),
            fascia = app.getString(R.string.serale_fascia),
            fuori = app.getString(R.string.serale_fuori),
            unAltraRegola = app.getString(R.string.serale_un_altra_regola),
            altreRegole = app.getString(R.string.serale_altre_regole),
            domani = app.getString(R.string.serale_domani),
            durata = { testoDurata(app, it.toLong()) },
        )
        avvisa(
            AvvisiLocali.ID_CHIUSURA_SERALE,
            app.getString(R.string.serale_titolo),
            TestoSerale.testo(Chiusura.OltreLimite("TikTok", 7, 1), parole),
        )
        scatta("18-notifiche-tempo", "Notifiche: preavviso (5 min), oltre il limite, nella fascia, chiusura della sera", pagine = false) {
            tendina(AvvisiLocali.idPreavviso(1), AvvisiLocali.idSforamento(2), AvvisiLocali.idSforamento(4), AvvisiLocali.ID_CHIUSURA_SERALE)
        }
    }

    @Test
    fun avvisiLavoriDiCasa() {
        val parole = NovitaDalPatto.paroleFaccende(app)
        val nuove = TestoFaccende.avvisoNuove(
            buildJsonObject {
                putJsonArray("faccenda_ids") { add(61); add(63) }
                put("genitore", "Mamma")
                put("blocco_da", DatiFinti.iso(DatiFinti.oggiAlle(18)))
            },
            listOf("Svuotare la lavastoviglie", "Portare fuori la spazzatura (carta e plastica)"),
            DatiFinti.adesso(),
            ZoneId.systemDefault(),
            parole,
        )!!
        avvisa(AvvisiLocali.idNotificaServer(1), nuove.first, nuove.second, AvvisiLocali.CANALE_FACCENDE)
        avvisa(
            AvvisiLocali.ID_BLOCCO_FACCENDE,
            app.getString(R.string.notifica_blocco_partito),
            app.getString(R.string.notifica_blocco_partito_testo),
            AvvisiLocali.CANALE_FACCENDE,
        )
        val bocciata = TestoFaccende.avvisoBocciata(
            buildJsonObject {
                put("titolo", "Riordinare la camera")
                put("genitore", "Papà")
                put("nota", "Il letto non è fatto e i vestiti sono sulla sedia")
            },
            parole,
        )!!
        avvisa(AvvisiLocali.idNotificaServer(2), bocciata.first, bocciata.second, AvvisiLocali.CANALE_FACCENDE)
        scatta("18-notifiche-lavori", "Notifiche dei lavori di casa: nuovi lavori, telefono bloccato, foto bocciata", pagine = false) {
            tendina(AvvisiLocali.idNotificaServer(1), AvvisiLocali.ID_BLOCCO_FACCENDE, AvvisiLocali.idNotificaServer(2))
        }
    }

    @Test
    fun avvisiSessioniEProposte() {
        val svolta = DatiFinti.svoltaStudio()
        val attiva = SessioneAttiva(svolta.id, svolta.sessioneId, svolta.nome, svolta.app.toSet(), svolta.nomi, svolta.inizio, svolta.fine)
        AnnunciSessione.partita(app, attiva)
        AnnunciSessione.finita(app, svolta.copy(id = 502, inizio = DatiFinti.adesso() - 47 * DatiFinti.MINUTO, finePrevista = DatiFinti.adesso()))
        val proposta = DatiFinti.propostaMammaTiktok
        val racconto = raccontoProposta(
            context = app,
            confronto = proposta.confronto,
            oggetto = TestoProposta.oggetto(proposta.regolaId, proposta.direzione, proposta.parametriProposti, DatiFinti.regoleTutte),
            contesto = DatiFinti.contesto,
        )
        avvisa(AvvisiLocali.idProposta(proposta.id), AvvisiLocali.titoloTipo(app, TipiNotifica.NUOVA_PROPOSTA), racconto.testo)
        scatta("18-notifiche-sessioni-proposte", "Notifiche: sessione partita, sessione finita, nuova proposta della mamma", pagine = false) {
            tendina(AvvisiLocali.ID_SESSIONE, AvvisiLocali.ID_SESSIONE_FINITA, AvvisiLocali.idProposta(proposta.id))
        }
    }

    @Test
    fun notificaFissa() {
        // La notifica fissa del testimone la costruisce il servizio (codice privato):
        // qui è rifatta con le stesse parole e lo stesso stile.
        val svolta = DatiFinti.svoltaStudio()
        val quando = TestoSessioni.quandoFinisce(svolta.fine, System.currentTimeMillis(), ZoneId.systemDefault())
        AvvisiLocali.creaCanale(app)
        val conSessione = NotificationCompat.Builder(app, AvvisiLocali.CANALE_PATTO)
            .setSmallIcon(R.drawable.ic_notifica_testimone)
            .setContentTitle(app.getString(R.string.notifica_testimone_titolo))
            .setContentText(app.getString(R.string.notifica_testimone_sessione, nomeSessioneTraVirgolette(app, svolta.nome), quando.ora))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .addAction(0, app.getString(R.string.sessione_termina), AvvisiLocali.apriScheda(app, MainActivity.DEST_TERMINA_SESSIONE))
            .build()
        val semplice = NotificationCompat.Builder(app, AvvisiLocali.CANALE_PATTO)
            .setSmallIcon(R.drawable.ic_notifica_testimone)
            .setContentTitle(app.getString(R.string.notifica_testimone_titolo))
            .setContentText(app.getString(R.string.notifica_testimone_testo))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        app.getSystemService(NotificationManager::class.java).notify(1, semplice)
        app.getSystemService(NotificationManager::class.java).notify(2, conSessione)
        scatta("18-notifiche-fissa", "Notifica fissa del testimone, normale e con una sessione in corso (\"Termina la sessione\")", pagine = false) {
            tendina(1, 2)
        }
    }
}
