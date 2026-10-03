package eu.stgm.pactum.figlio.faccende

import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import eu.stgm.pactum.figlio.sessione.RitmoBarriera
import eu.stgm.pactum.figlio.sessione.TracciaPrimoPiano

/**
 * (0.13) Un giro della barriera delle faccende, mentre il telefono è bloccato:
 * lo chiama il servizio del testimone circa una volta al secondo
 * (PactumService), solo finché il blocco dura. Gli stessi strumenti della
 * barriera delle Sessioni: l'app in primo piano dagli eventi d'uso
 * (TracciaPrimoPiano) e il ritmo delle aperture (RitmoBarriera, mai a
 * raffica). Le decisioni stanno in GuardiaFaccende (logica pura, per
 * GiudiceFaccende); qui solo le domande al sistema.
 *
 * In più, rispetto alle Sessioni:
 *  - una chiamata non spegne niente (decisione di Andrea);
 *  - all'inizio di ogni blocco, e dopo un errore, chi c'è davanti si cerca
 *    nelle ultime 24 ore ([daCapo], LetturaEventi);
 *  - l'interruttore di sicurezza conta solo le aperture che non sono arrivate
 *    sullo schermo (RitmoBarriera.comparsa);
 *  - le app ancora visibili in una finestrella o in uno schermo diviso si
 *    coprono con una finestra sopra le altre app (AppVisibili, CoperturaFinestrelle).
 */
class SorveglianzaFaccende(context: Context) {

    private val app = context.applicationContext
    private val usm = app.getSystemService(UsageStatsManager::class.java)
    private val potenza = app.getSystemService(PowerManager::class.java)
    private val serratura = app.getSystemService(KeyguardManager::class.java)
    private val traccia = TracciaPrimoPiano()
    private val visibili = AppVisibili()
    private val ritmo = RitmoBarriera()
    private val giudice = GiudiceFaccende(app)

    /** Fin dove si sono letti gli eventi (orologio a muro): il giro dopo riparte da lì, un po' prima. */
    private var lettoFinoA: Long? = null

    /** La prossima lettura riguarda una finestra lunga: all'inizio di un blocco, o dopo un errore. */
    private var lungo = true

    private var usciteViste = BarrieraFaccendeActivity.uscite
    private var comparseViste = BarrieraFaccendeActivity.comparse

    /**
     * Un giro. [ora] = l'istante (eventi e blocco), [ora.monotono] il ritmo
     * delle aperture. Restituisce quanto aspettare prima del giro dopo.
     */
    fun giro(ora: Istante): Long {
        val acceso = potenza?.isInteractive == true
        val sbloccato = serratura?.isKeyguardLocked == false
        if (!acceso || !sbloccato) {
            azzera()
            CoperturaFinestrelle.togli(app)
            return ATTESA_SCHERMO_MS
        }
        val monotono = ora.monotono
        // "Apri Pactum" o Home appena toccati: rivedere la stessa app è un cambio.
        val uscite = BarrieraFaccendeActivity.uscite
        if (uscite != usciteViste) {
            usciteViste = uscite
            ritmo.azzera()
        }
        // Le barriere arrivate sullo schermo non contano per l'interruttore di sicurezza.
        val comparse = BarrieraFaccendeActivity.comparse
        if (comparse != comparseViste) {
            repeat((comparse - comparseViste).coerceIn(0, 100)) { ritmo.comparsa() }
            comparseViste = comparse
        }
        giudice.rinfresca(monotono)
        leggiEventi(ora.muro, monotono)
        var primoPiano = traccia.attuale
        var copri = giudice.decidi(primoPiano, traccia.classe, traccia.precedente, ora).copri
        if (copri) {
            // Un attimo prima di aprire: l'app davanti, di nuovo.
            leggiEventi(System.currentTimeMillis(), monotono)
            val ancora = traccia.attuale
            if (ancora != primoPiano) {
                primoPiano = ancora
                copri = false
            }
        }
        if (ritmo.passo(primoPiano, copri && !BarrieraFaccendeActivity.visibile, monotono)) {
            BarrieraFaccendeActivity.apri(app)
        }
        // Le finestrelle e lo schermo diviso (si sa chi si ferma solo da Android 10).
        val daCoprire = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            visibili.daCoprire(ora.muro, primoPiano, DecisioneFinestrelle.ATTESA_MS) { pacchetto, classe ->
                giudice.decidi(pacchetto, classe, null, ora).copri
            }
        } else {
            emptySet()
        }
        CoperturaFinestrelle.aggiorna(app, daCoprire, monotono)
        return ATTESA_GIRO_MS
    }

    /** Dopo uno schermo spento o un blocco dello schermo: si riguarda l'app davanti (gli ultimi minuti bastano). */
    fun azzera() {
        traccia.azzera()
        visibili.azzera()
        ritmo.azzera()
        lettoFinoA = null
    }

    /** All'inizio di un blocco, o dopo un errore: tutto da capo, e chi c'è davanti si cerca nelle ultime 24 ore. */
    fun daCapo() {
        azzera()
        lungo = true
    }

    private fun leggiEventi(adesso: Long, dallAccensione: Long) {
        // L'orologio a muro spostato indietro: il punto a cui si era arrivati è
        // nel futuro. Da capo, con la finestra lunga.
        if ((lettoFinoA ?: Long.MIN_VALUE) > adesso + SOVRAPPOSIZIONE_FUTURA_MS) daCapo()
        val da = LetturaEventi.inizio(lungo, lettoFinoA, adesso, dallAccensione)
        val eventi = usm?.queryEvents(da, adesso + 1) ?: return
        val evento = UsageEvents.Event()
        while (eventi.hasNextEvent()) {
            eventi.getNextEvent(evento)
            traccia.evento(evento.eventType, evento.packageName, evento.timeStamp, evento.className)
            visibili.evento(evento.eventType, evento.packageName, evento.timeStamp, evento.className)
        }
        lettoFinoA = maxOf(adesso, lettoFinoA ?: adesso)
        lungo = false
    }

    companion object {
        const val ATTESA_GIRO_MS = 1_000L
        const val ATTESA_SCHERMO_MS = 2_000L
        const val ATTESA_ERRORE_MS = 3_000L
        private const val SOVRAPPOSIZIONE_FUTURA_MS = 60_000L
    }
}
