package eu.stgm.pactum.figlio.faccende

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
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
 *
 * (0.18, contratto v4.0) La usa anche la Sessione Studio («Ogni app fuori
 * dalla lista si copre entro pochi secondi»): stessa finestra, col testo
 * «Sei in Studio» e «Apri Pactum» che porta a Oggi ([Motivo.STUDIO]). Il
 * blocco dei lavori e lo Studio non coprono mai insieme (durante lo Studio il
 * blocco aspetta); se il motivo cambia, la copertura si rifà col testo giusto.
 */
object CoperturaFinestrelle {

    /** (0.18) Per cosa copre: il blocco dei lavori di casa o la Sessione Studio. */
    enum class Motivo { FACCENDE, STUDIO }

    private val principale = Handler(Looper.getMainLooper())

    /** La copertura sullo schermo adesso (solo dal filo principale). */
    private var vista: View? = null

    /** Quella che si vuole: il filo principale la mette o la toglie per arrivarci. */
    @Volatile
    private var voluta = false

    @Volatile
    private var pausaFino: Long? = null

    /** (0.18) Il motivo della copertura voluta, e quello della copertura sullo schermo (solo dal filo principale). */
    @Volatile
    private var motivoVoluto = Motivo.FACCENDE
    private var motivoVista = Motivo.FACCENDE

    /** Dal giro della barriera: [daCoprire] = le app da coprire ancora visibili. */
    fun aggiorna(context: Context, daCoprire: Set<String>, monotono: Long = SystemClock.elapsedRealtime(), motivo: Motivo = Motivo.FACCENDE) {
        val mostra = DecisioneFinestrelle.mostra(daCoprire, monotono, pausaFino) && PermessiHelper.puoMostrareSopra(context)
        // Il giro di uno non toglie la copertura dell'altro (solo nel passaggio fra blocco e Studio).
        if (!mostra && voluta && motivoVoluto != motivo) return
        imposta(context.applicationContext, mostra, motivo)
    }

    /** Via la copertura; con [solo], solo se è quella di quel motivo. */
    fun togli(context: Context, solo: Motivo? = null) {
        if (solo != null && motivoVoluto != solo) return
        imposta(context.applicationContext, false, motivoVoluto)
    }

    private fun imposta(app: Context, mostra: Boolean, motivo: Motivo) {
        if (voluta == mostra && !mostra) return
        voluta = mostra
        if (mostra) motivoVoluto = motivo
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
        // (0.18) Una copertura col testo dell'altro motivo: si rifà.
        if (attuale != null && motivoVista != motivoVoluto) {
            vista = null
            try {
                wm.removeView(attuale)
            } catch (e: Exception) {
                // già tolta
            }
        }
        if (vista == null) {
            val motivo = motivoVoluto
            val v = costruisci(app, motivo)
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
                motivoVista = motivo
            } catch (e: Exception) {
                // Android non l'ha lasciata aprire: niente copertura, come per la barriera.
            }
        }
    }

    /**
     * (0.15) Solo l'aspetto: lo stesso mondo dell'app (fondo chiaro, testo
     * scuro, pulsanti verdi con gli angoli tondi e senza MAIUSCOLO), una
     * colonna che scorre (coi caratteri grandi o in orizzontale niente esce
     * dallo schermo) e i margini delle barre di sistema. Quando si apre, si
     * chiude e cosa copre non cambia.
     */
    private fun costruisci(app: Context, motivo: Motivo): View {
        val studio = motivo == Motivo.STUDIO
        val dp = { valore: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, valore, app.resources.displayMetrics).toInt() }
        val testo = { id: Int, grande: Boolean ->
            TextView(app).apply {
                text = app.getString(id)
                setTextColor(if (grande) INCHIOSTRO else INCHIOSTRO_TENUE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (grande) 24f else 16f)
                setLineSpacing(0f, 1.15f)
                setPadding(0, 0, 0, dp(16f))
            }
        }
        val pulsante = { id: Int, pieno: Boolean, azione: () -> Unit ->
            Button(app).apply {
                text = app.getString(id)
                isAllCaps = false
                stateListAnimator = null
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(if (pieno) Color.WHITE else VERDE)
                minHeight = dp(48f)
                background = GradientDrawable().apply {
                    cornerRadius = dp(20f).toFloat()
                    if (pieno) setColor(VERDE) else {
                        setColor(Color.TRANSPARENT)
                        setStroke(dp(1f), BORDO)
                    }
                }
                setOnClickListener { azione() }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(8f) }
            }
        }
        val colonna = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24f), dp(32f), dp(24f), dp(32f))
            addView(testo(if (studio) R.string.studio_barriera_titolo else R.string.barriera_faccende_titolo, true))
            addView(testo(if (studio) R.string.studio_finestrella_testo else R.string.finestrella_testo, false))
            addView(testo(R.string.finestrella_aiuto, false))
            addView(
                pulsante(R.string.finestrella_chiudi, true) {
                    pausa()
                    togli(app)
                },
            )
            addView(
                pulsante(R.string.barriera_faccende_apri, false) {
                    pausa()
                    togli(app)
                    try {
                        app.startActivity(
                            Intent(app, MainActivity::class.java)
                                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                .putExtra(MainActivity.EXTRA_DESTINAZIONE, if (studio) MainActivity.DEST_OGGI else MainActivity.DEST_FACCENDE),
                        )
                    } catch (e: Exception) {
                        // Pactum non si apre: la copertura è tolta lo stesso, per la pausa
                    }
                },
            )
        }
        return ScrollView(app).apply {
            setBackgroundColor(FONDO)
            isFillViewport = true
            addView(colonna, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // I margini delle barre di sistema (e del ritaglio della fotocamera).
            setOnApplyWindowInsetsListener { vista, insets ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val barre = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    vista.setPadding(barre.left, barre.top, barre.right, barre.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    vista.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
                insets
            }
        }
    }

    // I colori dell'app (ui/theme/Theme.kt): fondo, testo, verde, bordo.
    private val FONDO = Color.rgb(0xFB, 0xFD, 0xFC)
    private val INCHIOSTRO = Color.rgb(0x18, 0x1D, 0x1B)
    private val INCHIOSTRO_TENUE = Color.rgb(0x41, 0x49, 0x44)
    private val VERDE = Color.rgb(0x1F, 0x6E, 0x5C)
    private val BORDO = Color.rgb(0x71, 0x79, 0x73)
}
