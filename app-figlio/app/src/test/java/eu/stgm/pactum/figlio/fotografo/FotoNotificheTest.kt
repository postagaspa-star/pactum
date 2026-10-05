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
    fun avvisiLavoriCambiatiEConfermati() {
        val parole = NovitaDalPatto.paroleFaccende(app)
        val adesso = DatiFinti.adesso()
        val spostato = TestoFaccende.avvisoModificata(
            buildJsonObject {
                put("faccenda_id", 61)
                put("titolo", "Svuotare la lavastoviglie")
                put("genitore", "Mamma")
                put(
                    "cambi",
                    buildJsonObject {
                        put(
                            "blocco_da",
                            buildJsonObject {
                                put("prima", DatiFinti.iso(DatiFinti.oggiAlle(16)))
                                put("dopo", DatiFinti.iso(DatiFinti.oggiAlle(18, 30).coerceAtLeast(adesso + 2 * DatiFinti.ORA)))
                            },
                        )
                    },
                )
            },
            adesso,
            ZoneId.systemDefault(),
            parole,
        )!!
        avvisa(AvvisiLocali.idNotificaServer(11), spostato.first, spostato.second, AvvisiLocali.CANALE_FACCENDE)
        val subito = TestoFaccende.avvisoModificata(
            buildJsonObject {
                put("faccenda_id", 64)
                put("titolo", "Leggere 20 pagine del libro di storia")
                put("genitore", "Papà")
                put(
                    "cambi",
                    buildJsonObject {
                        put("titolo", buildJsonObject { put("prima", "Leggere"); put("dopo", "Leggere 20 pagine del libro di storia") })
                        put("blocco_da", buildJsonObject { put("prima", DatiFinti.iso(adesso + 3 * DatiFinti.ORA)) })
                    },
                )
            },
            adesso,
            ZoneId.systemDefault(),
            parole,
        )!!
        avvisa(AvvisiLocali.idNotificaServer(12), subito.first, subito.second, AvvisiLocali.CANALE_FACCENDE)
        val confermata = TestoFaccende.avvisoConfermata(
            buildJsonObject {
                put("faccenda_id", 55)
                put("titolo", "Stendere il bucato")
                put("genitore", "Mamma")
            },
            parole,
        )!!
        avvisa(AvvisiLocali.idNotificaServer(13), confermata.first, confermata.second, AvvisiLocali.CANALE_FACCENDE)
        scatta("18-notifiche-lavori-cambiati", "(0.17) Notifiche: un lavoro spostato più avanti, uno cambiato e messo «da subito», uno confermato", pagine = false) {
            tendina(AvvisiLocali.idNotificaServer(11), AvvisiLocali.idNotificaServer(12), AvvisiLocali.idNotificaServer(13))
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

}
