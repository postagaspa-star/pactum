package eu.stgm.pactum.figlio.studio

import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import eu.stgm.pactum.figlio.avviso.Chiamata
import eu.stgm.pactum.figlio.catalogo.CatalogoApp
import eu.stgm.pactum.figlio.faccende.AppVisibili
import eu.stgm.pactum.figlio.faccende.CoperturaFinestrelle
import eu.stgm.pactum.figlio.faccende.DecisioneFinestrelle
import eu.stgm.pactum.figlio.faccende.GiudiceFaccende
import eu.stgm.pactum.figlio.faccende.LetturaEventi
import eu.stgm.pactum.figlio.faccende.ScattoInCorso
import eu.stgm.pactum.figlio.permessi.PermessiHelper
import eu.stgm.pactum.figlio.sessione.DecisioneBarriera
import eu.stgm.pactum.figlio.sessione.GruppoApk
import eu.stgm.pactum.figlio.sessione.GuardiaSessione
import eu.stgm.pactum.figlio.sessione.RitmoBarriera
import eu.stgm.pactum.figlio.sessione.SempreUsabili
import eu.stgm.pactum.figlio.sessione.SessioneAttiva
import eu.stgm.pactum.figlio.sessione.SituazioneBarriera
import eu.stgm.pactum.figlio.sessione.TracciaPrimoPiano

/**
 * (0.18, contratto v4.0, «La barriera») La barriera dello Studio, logica
 * pura: la stessa delle Sessioni (GuardiaSessione), con la lista dello
 * Studio. Sempre usabili: quelle della barriera delle sessioni (Pactum, Home,
 * tastiera, interfaccia di sistema, Telefono, chiamate ed emergenze,
 * Impostazioni) più la fotocamera quando la apre Pactum per la foto di un
 * lavoro. Una lista vuota vuol dire «solo le app sempre usabili». Lo Studio
 * non si annuncia: parte e basta. Se qualcosa non si sa, NON si copre.
 *
 * In più, rispetto alle Sessioni: una pagina web «dentro un'app» resta
 * libera solo se l'ha aperta, appena prima, un'app della lista o una sempre
 * usabile ([precedente], non la Home né le Recenti).
 */
object GuardiaStudio {

    /** Lo Studio nella forma della barriera delle sessioni (sull'ora del server). */
    fun comeSessione(studio: StudioAttivo): SessioneAttiva = SessioneAttiva(
        svoltaId = -1,
        sessioneId = null,
        nome = "Studio",
        app = studio.app.toSet(),
        nomi = studio.nomi,
        inizio = studio.inizio,
        fine = studio.mezzanotte,
        annunciata = true,
    )

    fun decidi(
        studio: StudioAttivo?,
        oraServer: Long,
        primoPiano: String?,
        schermoAcceso: Boolean,
        sbloccato: Boolean,
        mostraSopra: Boolean,
        accessoUso: Boolean,
        inChiamata: Boolean,
        sempreUsabili: Set<String>,
        contaNellUso: (String) -> Boolean?,
        nelGruppoApk: (String) -> Boolean?,
        classe: String? = null,
        precedente: String? = null,
        home: Set<String> = emptySet(),
    ): DecisioneBarriera = GuardiaSessione.decidi(
        SituazioneBarriera(
            sessione = studio?.let { comeSessione(it) },
            adesso = oraServer,
            primoPiano = primoPiano,
            schermoAcceso = schermoAcceso,
            sbloccato = sbloccato,
            mostraSopra = mostraSopra,
            accessoUso = accessoUso,
            inChiamata = inChiamata,
            sempreUsabili = sempreUsabili,
            contaNellUso = contaNellUso,
            nelGruppoApk = nelGruppoApk,
            classe = classe,
            listaVuotaValida = true,
            paginaSoloDaUnApp = true,
            precedente = precedente,
            home = home,
        ),
    )

    /**
     * (0.18) Le app ancora VISIBILI ma non davanti (una finestrella, metà di
     * uno schermo diviso) da almeno [DecisioneFinestrelle.ATTESA_MS] che lo
     * Studio coprirebbe ([copre]: la stessa decisione della barriera, per
     * quell'app). La barriera, che è una schermata, non sta sopra una
     * finestrella: queste le copre CoperturaFinestrelle, come nel blocco dei lavori.
     */
    fun finestrelleDaCoprire(visibili: AppVisibili, adesso: Long, davanti: String?, copre: (pacchetto: String, classe: String?) -> Boolean): Set<String> =
        visibili.daCoprire(adesso, davanti, DecisioneFinestrelle.ATTESA_MS, copre)
}

/**
 * (0.18) Le uscite («Esci», Home) e le comparse della barriera dello Studio,
 * viste dal giro (logica pura). Un'uscita: l'app davanti si guarda da capo.
 * Una barriera arrivata sullo schermo non conta per l'interruttore di
 * sicurezza ([RitmoBarriera.comparsa]): l'interruttore serve quando la
 * barriera NON riesce a comparire, e non si deve poter far scattare apposta
 * aprendo e chiudendo la barriera (gioco, «Esci», gioco…). Come nel blocco dei lavori.
 */
class ContiBarriera(private val ritmo: RitmoBarriera, private var usciteViste: Int, private var comparseViste: Int) {

    fun aggiorna(uscite: Int, comparse: Int) {
        if (uscite != usciteViste) {
            usciteViste = uscite
            ritmo.azzera()
        }
        if (comparse != comparseViste) {
            repeat((comparse - comparseViste).coerceIn(0, 100)) { ritmo.comparsa() }
            comparseViste = comparse
        }
    }
}

/**
 * (0.18) Un giro della barriera dello Studio, circa una volta al secondo a
 * schermo acceso e sbloccato, solo mentre lo Studio dura (PactumService).
 * Come SorveglianzaFaccende: le decisioni in GuardiaStudio e RitmoBarriera,
 * qui solo le domande al sistema. In più:
 *  - l'orologio a muro spostato indietro fa ripartire la lettura da capo
 *    (LetturaEventi.orologioIndietro), altrimenti nessun evento arriva più;
 *  - le finestrelle e lo schermo diviso si coprono (AppVisibili, CoperturaFinestrelle);
 *  - l'interruttore di sicurezza conta solo le aperture che non sono arrivate sullo schermo.
 */
class SorveglianzaStudio(context: Context) {

    private val app = context.applicationContext
    private val usm = app.getSystemService(UsageStatsManager::class.java)
    private val potenza = app.getSystemService(PowerManager::class.java)
    private val serratura = app.getSystemService(KeyguardManager::class.java)
    private val traccia = TracciaPrimoPiano()
    private val visibili = AppVisibili()
    private val ritmo = RitmoBarriera()
    private val conti = ContiBarriera(ritmo, BarrieraStudioActivity.uscite, BarrieraStudioActivity.comparse)

    /** Fin dove si sono letti gli eventi (orologio a muro): il giro dopo riparte da lì, un po' prima. */
    private var lettoFinoA: Long? = null

    /** La prossima lettura riguarda una finestra lunga: all'inizio dello Studio, o dopo l'orologio spostato indietro. */
    private var lungo = true
    private var sempreUsabili: Set<String> = SempreUsabili.FISSE
    private var home: Set<String> = emptySet()
    private var contaNellUso: (String) -> Boolean? = { null }
    private var gruppoApk: (String) -> Boolean? = { null }
    private var rinfrescatoIl: Long? = null

    /** Un giro: quanto aspettare prima del giro dopo. */
    fun giro(adesso: Long, monotono: Long): Long {
        val acceso = potenza?.isInteractive == true
        val sbloccato = serratura?.isKeyguardLocked == false
        if (!acceso || !sbloccato) {
            azzera()
            CoperturaFinestrelle.togli(app, CoperturaFinestrelle.Motivo.STUDIO)
            return ATTESA_SCHERMO_MS
        }
        conti.aggiorna(BarrieraStudioActivity.uscite, BarrieraStudioActivity.comparse)
        rinfresca(monotono)
        leggiEventi(adesso, monotono)
        var primoPiano = traccia.attuale
        var copri = decidi(primoPiano, traccia.classe, traccia.precedente).copri
        if (copri) {
            leggiEventi(System.currentTimeMillis(), monotono)
            val ancora = traccia.attuale
            if (ancora != primoPiano || Chiamata.inCorso(app)) {
                primoPiano = ancora
                copri = false
            }
        }
        if (ritmo.passo(primoPiano, copri && !BarrieraStudioActivity.visibile, monotono)) BarrieraStudioActivity.apri(app)
        // Le finestrelle e lo schermo diviso (si sa chi si ferma solo da Android 10).
        val daCoprire = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            GuardiaStudio.finestrelleDaCoprire(visibili, System.currentTimeMillis(), primoPiano) { pacchetto, classe ->
                decidi(pacchetto, classe, null).copri
            }
        } else {
            emptySet()
        }
        CoperturaFinestrelle.aggiorna(app, daCoprire, monotono, CoperturaFinestrelle.Motivo.STUDIO)
        return ATTESA_GIRO_MS
    }

    private fun decidi(primoPiano: String?, classe: String?, precedente: String?): DecisioneBarriera {
        val o = OraServer.adesso()
        return GuardiaStudio.decidi(
            studio = StatoStudio.attivoAdesso(o),
            oraServer = o.server,
            primoPiano = primoPiano,
            schermoAcceso = true,
            sbloccato = true,
            mostraSopra = PermessiHelper.puoMostrareSopra(app),
            accessoUso = PermessiHelper.haAccessoUso(app),
            inChiamata = Chiamata.inCorso(app),
            // La fotocamera aperta da "Scatta la foto" resta usabile mentre si scatta.
            sempreUsabili = sempreUsabili + ScattoInCorso.attuali(o.ora.muro),
            contaNellUso = contaNellUso,
            nelGruppoApk = gruppoApk,
            classe = classe,
            precedente = precedente,
            home = home,
        )
    }

    /** Dopo uno schermo spento o un blocco dello schermo: si riguarda l'app davanti (gli ultimi minuti bastano). */
    fun azzera() {
        traccia.azzera()
        visibili.azzera()
        ritmo.azzera()
        lettoFinoA = null
    }

    /** Tutto da capo, e chi c'è davanti si cerca nella finestra lunga. */
    fun daCapo() {
        azzera()
        lungo = true
    }

    private fun rinfresca(monotono: Long) {
        val ultimo = rinfrescatoIl
        if (ultimo != null && monotono - ultimo < RINFRESCO_MS) return
        rinfrescatoIl = monotono
        sempreUsabili = SempreUsabili.leggi(app)
        home = GiudiceFaccende.schermateHome(app)
        val filtro = CatalogoApp.filtroUso(app)
        contaNellUso = { p -> try { filtro(p) } catch (e: Exception) { null } }
        gruppoApk = GruppoApk.classificatore(app)
    }

    private fun leggiEventi(adesso: Long, dallAccensione: Long) {
        // L'orologio a muro spostato indietro (anche durante lo Studio, dalle
        // Impostazioni): il punto a cui si era arrivati è nel futuro. Da capo.
        if (LetturaEventi.orologioIndietro(lettoFinoA, adesso)) daCapo()
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
        private const val RINFRESCO_MS = 60_000L
    }
}
