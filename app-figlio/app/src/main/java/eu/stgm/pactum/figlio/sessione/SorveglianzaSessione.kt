package eu.stgm.pactum.figlio.sessione

import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import eu.stgm.pactum.figlio.avviso.Chiamata
import eu.stgm.pactum.figlio.catalogo.CatalogoApp
import eu.stgm.pactum.figlio.permessi.PermessiHelper

/**
 * (0.11) Un giro della barriera, mentre una Sessione è in corso: lo chiama il
 * servizio del testimone circa una volta al secondo (PactumService), solo
 * finché la sessione dura. Niente servizio di accessibilità, niente permessi
 * nuovi: l'app in primo piano si legge dagli eventi d'uso degli ultimi
 * secondi, come fa già la misura.
 *
 * Le decisioni stanno in GuardiaSessione e RitmoBarriera (logica pura); qui
 * solo le domande al sistema. Schermo spento o telefono bloccato: niente
 * barriera, e niente letture fino a quando torna.
 */
class SorveglianzaSessione(context: Context, private val attiva: SessioneAttiva) {

    private val app = context.applicationContext
    private val usm = app.getSystemService(UsageStatsManager::class.java)
    private val potenza = app.getSystemService(PowerManager::class.java)
    private val serratura = app.getSystemService(KeyguardManager::class.java)
    private val traccia = TracciaPrimoPiano()
    private val ritmo = RitmoBarriera()

    /** Fin dove si sono letti gli eventi (orologio a muro): il giro dopo riparte da lì, un po' prima. */
    private var lettoFinoA: Long? = null

    /** La prima lettura guarda indietro un'ora; dopo uno schermo spento basta poco (allo sblocco l'app davanti si riprende). */
    private var primaLettura = true

    /** Quante volte è stato toccato "Esci" l'ultima volta che si è guardato. */
    private var usciteViste = BarrieraActivity.uscite

    // Le risposte del sistema che cambiano di rado: si rileggono ogni minuto.
    private var sempreUsabili: Set<String> = SempreUsabili.FISSE
    private var contaNellUso: (String) -> Boolean? = { null }
    private var gruppoApk: (String) -> Boolean? = { null }
    private var rinfrescatoIl: Long? = null

    /**
     * Un giro. [adesso] = orologio a muro (eventi e fine della sessione),
     * [monotono] = elapsedRealtime (il ritmo delle aperture). Restituisce
     * quanto aspettare prima del giro dopo.
     */
    fun giro(adesso: Long, monotono: Long): Long {
        val acceso = potenza?.isInteractive == true
        val sbloccato = serratura?.isKeyguardLocked == false
        if (!acceso || !sbloccato) {
            azzera()
            return ATTESA_SCHERMO_MS
        }
        // "Esci" appena toccato: rivedere la stessa app è un cambio, anche se
        // nel frattempo non si è vista la Home.
        val uscite = BarrieraActivity.uscite
        if (uscite != usciteViste) {
            usciteViste = uscite
            ritmo.azzera()
        }
        rinfresca(monotono)
        leggiEventi(adesso)
        var primoPiano = traccia.attuale
        var copri = decidi(adesso, primoPiano).copri
        if (copri) {
            // Un attimo prima di aprire: l'app davanti e la chiamata, di nuovo.
            leggiEventi(System.currentTimeMillis())
            val ancora = traccia.attuale
            if (ancora != primoPiano || Chiamata.inCorso(app)) {
                primoPiano = ancora
                copri = false
            }
        }
        // La barriera già davanti non si riapre sopra se stessa.
        if (ritmo.passo(primoPiano, copri && !BarrieraActivity.visibile, monotono)) BarrieraActivity.apri(app, attiva)
        return ATTESA_GIRO_MS
    }

    private fun decidi(adesso: Long, primoPiano: String?): DecisioneBarriera = GuardiaSessione.decidi(
        SituazioneBarriera(
            sessione = attiva,
            adesso = adesso,
            primoPiano = primoPiano,
            schermoAcceso = true,
            sbloccato = true,
            mostraSopra = PermessiHelper.puoMostrareSopra(app),
            accessoUso = PermessiHelper.haAccessoUso(app),
            inChiamata = Chiamata.inCorso(app),
            sempreUsabili = sempreUsabili,
            contaNellUso = contaNellUso,
            nelGruppoApk = gruppoApk,
            classe = traccia.classe,
        ),
    )

    /** Da capo: dopo uno schermo spento, un blocco o un errore si riguarda tutto. */
    fun azzera() {
        traccia.azzera()
        ritmo.azzera()
        lettoFinoA = null
    }

    private fun rinfresca(monotono: Long) {
        val ultimo = rinfrescatoIl
        if (ultimo != null && monotono - ultimo < RINFRESCO_MS) return
        rinfrescatoIl = monotono
        sempreUsabili = SempreUsabili.leggi(app)
        val filtro = CatalogoApp.filtroUso(app)
        contaNellUso = { p -> try { filtro(p) } catch (e: Exception) { null } }
        gruppoApk = GruppoApk.classificatore(app)
    }

    private fun leggiEventi(adesso: Long) {
        val indietro = if (primaLettura) INDIETRO_ALL_INIZIO_MS else INDIETRO_DOPO_SCHERMO_MS
        val da = lettoFinoA?.let { it - SOVRAPPOSIZIONE_MS } ?: (adesso - indietro)
        val eventi = usm?.queryEvents(da.coerceAtLeast(0), adesso + 1) ?: return
        val evento = UsageEvents.Event()
        while (eventi.hasNextEvent()) {
            eventi.getNextEvent(evento)
            traccia.evento(evento.eventType, evento.packageName, evento.timeStamp, evento.className)
        }
        lettoFinoA = maxOf(adesso, lettoFinoA ?: adesso)
        primaLettura = false
    }

    companion object {
        /** Circa una volta al secondo, mentre lo schermo è acceso e il telefono sbloccato. */
        const val ATTESA_GIRO_MS = 1_000L
        const val ATTESA_SCHERMO_MS = 2_000L
        const val ATTESA_ERRORE_MS = 3_000L

        /** Gli eventi arrivano con un attimo di ritardo: si rilegge un pezzo del giro prima. */
        private const val SOVRAPPOSIZIONE_MS = 5_000L

        /** Al primo giro: chi è davanti si cerca nell'ultima ora. */
        private const val INDIETRO_ALL_INIZIO_MS = 60L * 60 * 1000
        private const val INDIETRO_DOPO_SCHERMO_MS = 10L * 60 * 1000
        private const val RINFRESCO_MS = 60_000L
    }
}
