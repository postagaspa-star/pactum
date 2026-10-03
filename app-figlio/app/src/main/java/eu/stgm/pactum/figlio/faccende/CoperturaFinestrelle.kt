package eu.stgm.pactum.figlio.faccende

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import eu.stgm.pactum.figlio.MainActivity
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.permessi.PermessiHelper

/**
 * (0.13) La copertura delle finestrelle durante il blocco delle faccende.
 *
 * La barriera è una schermata (un'activity): non sta sopra una finestrella
 * (picture-in-picture), e in uno schermo diviso copre solo la sua metà. Se
 * un'app che durante il blocco non si usa resta visibile così per qualche
 * secondo (AppVisibili), si copre tutto lo schermo con una finestra sopra le
 * altre app (TYPE_APPLICATION_OVERLAY, il permesso "Mostra sopra le altre app"
 * c'è già), che sta anche sopra la finestrella.
 *
 * Mai una trappola: "Chiudi la finestrella" toglie la copertura per
 * [DecisioneFinestrelle.PAUSA_MS], il tempo di trascinare la finestrella sulla
 * X o di chiudere lo schermo diviso; "Apri Pactum" fa lo stesso e porta alle
 * faccende. Si toglie da sola appena non c'è più niente da coprire, a schermo
 * spento e quando il blocco finisce.
 */
object CoperturaFinestrelle {

    private val principale = Handler(Looper.getMainLooper())

    /** La copertura sullo schermo adesso (solo dal filo principale). */
    private var vista: View? = null

    /** Quella che si vuole: il filo principale la mette o la toglie per arrivarci. */
    @Volatile
    private var voluta = false

    @Volatile
    private var pausaFino: Long? = null

    /** Dal giro della barriera: [daCoprire] = le app da coprire ancora visibili. */
    fun aggiorna(context: Context, daCoprire: Set<String>, monotono: Long = SystemClock.elapsedRealtime()) {
        val mostra = DecisioneFinestrelle.mostra(daCoprire, monotono, pausaFino) && PermessiHelper.puoMostrareSopra(context)
        imposta(context.applicationContext, mostra)
    }

    fun togli(context: Context) = imposta(context.applicationContext, false)

    private fun imposta(app: Context, mostra: Boolean) {
        if (voluta == mostra && !mostra) return
        voluta = mostra
        principale.post { riconcilia(app) }
    }

    private fun pausa() {
        pausaFino = SystemClock.elapsedRealtime() + DecisioneFinestrelle.PAUSA_MS
    }

    /** Sul filo principale: la copertura come la si vuole adesso. */
    private fun riconcilia(app: Context) {
        val wm = app.getSystemService(WindowManager::class.java) ?: return
        val attuale = vista
        if (!voluta) {
            if (attuale != null) {
                vista = null
                try {
                    wm.removeView(attuale)
                } catch (e: Exception) {
                    // già tolta
                }
            }
            return
        }
        if (attuale == null) {
            val v = costruisci(app)
            val parametri = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE,
            ).apply { gravity = Gravity.CENTER }
            try {
                wm.addView(v, parametri)
                vista = v
            } catch (e: Exception) {
                // Android non l'ha lasciata aprire: niente copertura, come per la barriera.
            }
        }
    }

    private fun costruisci(app: Context): View {
        val dp = { valore: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, valore, app.resources.displayMetrics).toInt() }
        val testo = { id: Int, grande: Boolean ->
            TextView(app).apply {
                text = app.getString(id)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (grande) 24f else 16f)
                setPadding(0, 0, 0, dp(16f))
            }
        }
        return LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(0x1C, 0x1B, 0x1F))
            setPadding(dp(24f), dp(32f), dp(24f), dp(32f))
            addView(testo(R.string.barriera_faccende_titolo, true))
            addView(testo(R.string.finestrella_testo, false))
            addView(testo(R.string.finestrella_aiuto, false))
            addView(
                Button(app).apply {
                    text = app.getString(R.string.finestrella_chiudi)
                    setOnClickListener {
                        pausa()
                        togli(app)
                    }
                },
            )
            addView(
                Button(app).apply {
                    text = app.getString(R.string.barriera_faccende_apri)
                    setOnClickListener {
                        pausa()
                        togli(app)
                        try {
                            app.startActivity(
                                Intent(app, MainActivity::class.java)
                                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                    .putExtra(MainActivity.EXTRA_DESTINAZIONE, MainActivity.DEST_FACCENDE),
                            )
                        } catch (e: Exception) {
                            // Pactum non si apre: la copertura è tolta lo stesso, per la pausa
                        }
                    }
                },
            )
        }
    }
}
