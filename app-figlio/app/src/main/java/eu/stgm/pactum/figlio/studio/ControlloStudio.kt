package eu.stgm.pactum.figlio.studio

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import eu.stgm.pactum.figlio.MainActivity
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.dati.Patto
import eu.stgm.pactum.figlio.faccende.ControlloBlocco
import eu.stgm.pactum.figlio.faccende.Orologio
import eu.stgm.pactum.figlio.faccende.StatoBlocco
import eu.stgm.pactum.figlio.notifiche.AvvisiLocali
import eu.stgm.pactum.figlio.permessi.PermessiHelper
import eu.stgm.pactum.figlio.rete.PostinoClient
import eu.stgm.pactum.figlio.sessione.ConsegnaSessioni
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * (0.18, contratto v4.0, parte C) Lo Studio verso il server, e quello che ne
 * segue sul telefono.
 *
 *  - [interroga]: `GET /api/studio`. Almeno ogni minuto durante lo Studio,
 *    e subito alle notifiche `studio_chiuso` e `studio_risposta`
 *    (TipiNotificaStudio.CAMBIANO_LO_STUDIO, [richiedi]).
 *  - [daPatto]: lo `studio` di ogni `GET /api/patto` (assente = server
 *    vecchio: lo Studio si spegne).
 *  - [dopo]: dopo ogni cambio e a ogni giro: il tempo che passa (riavvio,
 *    mezzanotte, tratto da chiudere), la partenza (la sessione normale si
 *    chiude, la pagina d'inizio), l'avviso dei 5 minuti, la sveglia esatta.
 */
object ControlloStudio {

    private val mutex = Mutex()

    /** Un giro alla volta: la partenza (sessione chiusa, pagina aperta) si fa una volta sola. */
    private val mutexDopo = Mutex()
    private val _richieste = Channel<Unit>(Channel.CONFLATED)

    /** Il giro del servizio le aspetta: "chiedi subito lo Studio". */
    val richieste: ReceiveChannel<Unit> get() = _richieste

    fun richiedi() {
        _richieste.trySend(Unit)
    }

    enum class Esito { LETTO, SERVER_VECCHIO, SCOLLEGATO, SENZA_RETE, ERRORE, NON_COLLEGATO }

    /** `GET /api/studio` adesso. */
    suspend fun interroga(context: Context): Esito {
        val app = context.applicationContext
        val esito = withContext(Dispatchers.IO) {
            mutex.withLock {
                val configurazione = Impostazioni(app).leggiConfigurazione()
                if (!configurazione.completa) return@withLock Esito.NON_COLLEGATO
                val partita = Orologio.adesso()
                val lettura = PostinoClient(configurazione).leggiStudio()
                val arrivata = OraServer.adesso(app)
                val letto = LetturaStudio.statoDaCorpo(lettura.corpo)
                when {
                    letto != null -> {
                        ArchivioStudio.modifica(app) { it.conServer(letto, partita, lettura.dataServer ?: arrivata.server) }
                        Esito.LETTO
                    }
                    lettura.codice == 404 || lettura.codice == 405 -> {
                        ArchivioStudio.modifica(app) { it.conServerVecchio() }
                        Esito.SERVER_VECCHIO
                    }
                    lettura.codice == 401 -> {
                        ArchivioStudio.modifica(app) { it.conScollegato(arrivata.ora, arrivata.server, arrivata.agganciata) }
                        Esito.SCOLLEGATO
                    }
                    lettura.codice == 0 -> Esito.SENZA_RETE
                    else -> Esito.ERRORE
                }
            }
        }
        dopo(app)
        return esito
    }

    /** Lo `studio` di un patto appena letto (PattoLocale.salva). Senza il campo: server vecchio, lo Studio si spegne. */
    fun daPatto(context: Context, patto: Patto) {
        val app = context.applicationContext
        val adesso = Orologio.adesso()
        val partita = patto.lettaIl ?: adesso
        val stato = patto.studio
        ArchivioStudio.modifica(app) { m ->
            val conFuso = m.conFuso(patto.fuso)
            if (stato == null) {
                conFuso.senzaStudio(partita)
            } else {
                conFuso.conServer(stato, partita, patto.dataServer ?: OraServer.adesso(app).server)
            }
        }
        StatoStudio.svegliati()
    }

    /** Un 401 su un'altra lettura (il patto): questo telefono non è più collegato. */
    fun scollegato(context: Context) {
        val app = context.applicationContext
        val o = OraServer.adesso(app)
        ArchivioStudio.modifica(app) { it.conScollegato(o.ora, o.server, o.agganciata) }
    }

    /** L'orologio a muro spostato a mano: l'ordine delle risposte riparte, le sveglie si rifanno. */
    fun cambioOra(context: Context) {
        ArchivioStudio.modifica(context.applicationContext) { it.conCambioOra() }
        svegliaChiesta = null
        StatoStudio.svegliati()
    }

    @Volatile
    private var svegliaChiesta: Long? = null

    /**
     * Dopo ogni giro: il tempo che passa, la partenza, l'avviso dei 5
     * minuti, la sveglia della prossima cosa da fare.
     */
    suspend fun dopo(context: Context) = mutexDopo.withLock {
        val app = context.applicationContext
        val o = OraServer.adesso(app)
        val m = withContext(Dispatchers.IO) { ArchivioStudio.modifica(app) { it.normalizzata(o.ora, o.server, o.agganciata, o.perFine) } }
        val studio = m.attivo(o)
        if (studio != null && m.partitoIl != studio.inizio) partenza(app, studio, o)
        if (studio != null) AvvisiLocali.cancella(app, AvvisiLocali.ID_STUDIO_PREAVVISO)
        preavviso(app, m, o, studio)
        programmaSveglia(app, m, o, studio)
        // Lo Studio finito: il blocco dei lavori, se è dovuto, parte adesso.
        StatoBlocco.svegliati()
        if (studio == null && m.periodi.any { it.fine != null && o.server - it.fine < 2 * 60_000L }) ControlloBlocco.richiedi()
    }

    /**
     * La partenza dello Studio: la sessione normale in corso si chiude qui
     * (`terminata`, fine = l'inizio dello Studio), anche senza rete; e la
     * pagina d'inizio (Pactum su Oggi) se lo schermo è acceso e lo Studio è
     * appena partito. Una volta per Studio.
     */
    private suspend fun partenza(app: Context, studio: StudioAttivo, o: OraServer) {
        withContext(Dispatchers.IO) { ArchivioStudio.modifica(app) { it.conPartito(studio.inizio) } }
        // L'inizio dello Studio sull'orologio del telefono (quello delle sessioni).
        val alMuro = o.ora.muro - (o.server - studio.inizio)
        runCatching { ConsegnaSessioni.terminaPerStudio(app, alMuro) }
        if (o.server - studio.inizio < FINESTRA_PAGINA_MS) apriPagina(app)
    }

    /** La pagina d'inizio: Pactum su Oggi, dove c'è la card dello Studio. Solo a schermo acceso e sbloccato. */
    private fun apriPagina(app: Context) {
        try {
            val acceso = app.getSystemService(PowerManager::class.java)?.isInteractive == true
            val sbloccato = app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false
            if (!acceso || !sbloccato || !PermessiHelper.puoMostrareSopra(app)) return
            app.startActivity(
                Intent(app, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(MainActivity.EXTRA_DESTINAZIONE, MainActivity.DEST_OGGI),
            )
        } catch (e: Exception) {
            // la pagina non si apre: lo Studio vale lo stesso
        }
    }

    /** «Tra 5 minuti parte lo Studio», una volta per partenza. */
    private suspend fun preavviso(app: Context, m: MemoriaStudio, o: OraServer, studio: StudioAttivo?) {
        if (studio != null) return
        val p = m.prossimaPartenza(o.server) ?: return
        if (p.inizio - o.server > MemoriaStudio.PREAVVISO_MS || m.avvisato == p.giorno) return
        val ora = TestoStudio.ora(p.inizio, m.zona())
        AvvisiLocali.avvisa(
            app,
            id = AvvisiLocali.ID_STUDIO_PREAVVISO,
            titolo = app.getString(R.string.studio_notifica_preavviso),
            testo = app.getString(R.string.studio_notifica_preavviso_testo, ora),
            destinazione = MainActivity.DEST_OGGI,
            scadeTra = (p.inizio - o.server + 60_000L).coerceAtLeast(60_000L),
        )
        withContext(Dispatchers.IO) { ArchivioStudio.modifica(app) { it.conAvvisato(p.giorno) } }
    }

    /**
     * La sveglia esatta della prossima cosa: 5 minuti prima della partenza,
     * la partenza, la mezzanotte dello Studio in corso. Contata sull'orologio
     * che non si sposta (come quella del blocco): parte anche a schermo
     * spento e senza rete. Dopo un riavvio la rifà BootReceiver.
     */
    private fun programmaSveglia(app: Context, m: MemoriaStudio, o: OraServer, studio: StudioAttivo?) {
        val prossima = m.prossimaPartenza(o.server)
        val momenti = listOfNotNull(
            prossima?.let { it.inizio - MemoriaStudio.PREAVVISO_MS },
            prossima?.inizio,
            studio?.mezzanotte,
        ).filter { it > o.server }
        val quando = momenti.minOrNull()
        if (quando == svegliaChiesta) return
        if (quando != null) SvegliaStudio.programma(app, o.ora.monotono + (quando - o.server)) else SvegliaStudio.annulla(app)
        svegliaChiesta = quando
    }

    /** Lo si dimentica dopo un riavvio (le sveglie non sopravvivono): la rifà il prossimo [dopo]. */
    fun dimenticaSveglia() {
        svegliaChiesta = null
    }

    /** La pagina d'inizio si apre solo nei primi minuti dello Studio. */
    private const val FINESTRA_PAGINA_MS = 10L * 60 * 1000
}
