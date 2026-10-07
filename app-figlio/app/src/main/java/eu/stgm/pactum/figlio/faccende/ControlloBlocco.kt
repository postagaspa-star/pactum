package eu.stgm.pactum.figlio.faccende

import android.content.Context
import eu.stgm.pactum.figlio.MainActivity
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.dati.Patto
import eu.stgm.pactum.figlio.notifiche.AvvisiLocali
import eu.stgm.pactum.figlio.notifiche.NovitaDalPatto
import eu.stgm.pactum.figlio.rete.PostinoClient
import eu.stgm.pactum.figlio.studio.ArchivioStudio
import eu.stgm.pactum.figlio.studio.OraServer
import eu.stgm.pactum.figlio.sync.ConsegnaEventi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * (0.13) Il blocco delle faccende verso il server, e quello che ne segue.
 *
 * - [interroga]: `GET /api/faccende/blocco`, la risposta piccola. La chiede il
 *   giro del servizio almeno ogni minuto a schermo acceso, subito allo sblocco
 *   dello schermo, quando arriva una notifica di faccende, quando una foto è
 *   arrivata al server e quando torna la rete ([richiedi]); a schermo spento
 *   il worker, col patto.
 * - [daPatto]: il `blocco` e le `faccende` di ogni `GET /api/patto`.
 * - [dopo]: dopo ogni cambio (e a ogni giro, perché il blocco parte anche da
 *   solo all'ora di `prossimo`): l'avviso "Prima i lavori di casa" (che sparisce
 *   quando il blocco finisce), la sveglia per il prossimo blocco, i permessi
 *   tolti durante il blocco.
 *
 * Mai si sblocca qui: lo fa solo una risposta del server (MemoriaBlocco), o un
 * 401 (questo telefono non è più collegato).
 */
object ControlloBlocco {

    private val mutex = Mutex()
    private val _richieste = Channel<Unit>(Channel.CONFLATED)

    /** Il giro del servizio le aspetta: "chiedi subito il blocco". */
    val richieste: ReceiveChannel<Unit> get() = _richieste

    /** Qualcosa è cambiato fra le faccende (dal patto): le notifiche si leggono al prossimo giro. */
    @Volatile
    private var novitaDaLeggere = false

    /** L'ultima sveglia chiesta ad Android: non si richiede la stessa a ogni giro. */
    @Volatile
    private var svegliaChiesta: Pair<Long, Ancora?>? = null

    fun richiedi() {
        _richieste.trySend(Unit)
    }

    /** Com'è andata l'ultima domanda (per la pagina). */
    enum class Esito { LETTO, SERVER_VECCHIO, SCOLLEGATO, SENZA_RETE, ERRORE, NON_COLLEGATO }

    /** `GET /api/faccende/blocco` adesso. Se qualcosa è cambiato, le notifiche si leggono subito. */
    suspend fun interroga(context: Context): Esito {
        val app = context.applicationContext
        val (esito, cambiate) = withContext(Dispatchers.IO) {
            mutex.withLock {
                val configurazione = Impostazioni(app).leggiConfigurazione()
                if (!configurazione.completa) return@withLock Esito.NON_COLLEGATO to false
                val partita = Orologio.adesso()
                val lettura = PostinoClient(configurazione).leggiBlocco()
                val arrivata = Orologio.adesso()
                val letto = LetturaFaccende.bloccoDaCorpo(lettura.corpo)
                when {
                    letto != null -> {
                        val prima = ArchivioBlocco.leggi(app)
                        val dopo = ArchivioBlocco.modifica(app) { it.conServer(letto, partita, arrivata, lettura.dataServer) }
                        Esito.LETTO to NovitaFaccende.cambiate(prima.daFare, dopo.daFare)
                    }
                    // Il server non conosce le faccende: si dice, ma il blocco resta com'era.
                    EsitiFaccende.serverVecchio(lettura.codice) -> {
                        ArchivioBlocco.modifica(app) { it.conServerVecchio() }
                        Esito.SERVER_VECCHIO to false
                    }
                    // Questo telefono non è più collegato: il blocco si toglie (come sul computer).
                    lettura.codice == 401 -> {
                        ArchivioBlocco.modifica(app) { it.conScollegato(partita, arrivata) }
                        Esito.SCOLLEGATO to false
                    }
                    lettura.codice == 0 -> Esito.SENZA_RETE to false
                    else -> Esito.ERRORE to false
                }
            }
        }
        if (cambiate || novitaDaLeggere) leggiNotifiche(app)
        dopo(app)
        return esito
    }

    /**
     * `GET /api/faccende`: l'elenco intero per la pagina. Restituisce l'esito;
     * l'elenco va nell'archivio (anche offline la pagina ha l'ultimo).
     */
    suspend fun leggiElenco(context: Context): Esito = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val configurazione = Impostazioni(app).leggiConfigurazione()
        if (!configurazione.completa) return@withContext Esito.NON_COLLEGATO
        val partita = Orologio.adesso()
        val (corpo, codice) = PostinoClient(configurazione).leggiFaccende()
        val arrivata = Orologio.adesso()
        val elenco = LetturaFaccende.elenco(corpo)
        when {
            elenco != null -> {
                ArchivioBlocco.modifica(app) { it.conElenco(elenco, partita, arrivata) }
                Esito.LETTO
            }
            EsitiFaccende.serverVecchio(codice) -> {
                ArchivioBlocco.modifica(app) { it.conServerVecchio() }
                Esito.SERVER_VECCHIO
            }
            codice == 401 -> {
                ArchivioBlocco.modifica(app) { it.conScollegato(partita, arrivata) }
                Esito.SCOLLEGATO
            }
            codice == 0 -> Esito.SENZA_RETE
            else -> Esito.ERRORE
        }
    }

    /**
     * Il `blocco` e le `faccende` di un patto appena letto (PattoLocale.salva).
     * Un patto senza `blocco` (server vecchio) non cambia niente. Il resto
     * (avviso, sveglia, notifiche) lo fa il giro, svegliato da qui.
     */
    fun daPatto(context: Context, patto: Patto) {
        val app = context.applicationContext
        val blocco = patto.blocco
        val faccende = patto.faccende
        if (blocco == null && faccende == null) return
        val adesso = Orologio.adesso()
        val partita = patto.lettaIl ?: adesso
        val arrivata = patto.arrivataIl ?: adesso
        val prima = ArchivioBlocco.leggi(app)
        val dopo = ArchivioBlocco.modifica(app) { m ->
            var nuova = m
            if (blocco != null) nuova = nuova.conServer(blocco, partita, arrivata, patto.dataServer)
            if (faccende != null) nuova = nuova.conElenco(faccende, partita, arrivata)
            nuova
        }
        if (NovitaFaccende.cambiate(prima.daFare, dopo.daFare)) novitaDaLeggere = true
        StatoBlocco.svegliati()
        richiedi()
    }

    /** Un 401 su un'altra lettura (il patto): questo telefono non è più collegato. */
    suspend fun scollegato(context: Context) {
        val app = context.applicationContext
        val ora = Orologio.adesso()
        withContext(Dispatchers.IO) { ArchivioBlocco.modifica(app) { it.conScollegato(ora, ora) } }
        dopo(app)
    }

    /** L'orologio a muro spostato a mano (OrologioReceiver): l'ordine delle risposte riparte. */
    fun cambioOra(context: Context) {
        ArchivioBlocco.modifica(context.applicationContext) { it.conCambioOra() }
        svegliaChiesta = null
        StatoBlocco.svegliati()
        richiedi()
    }

    /**
     * Dopo ogni giro: l'avviso della partenza (una volta per blocco, e via
     * quando il blocco finisce), la sveglia per il prossimo blocco (anche
     * senza rete parte all'ora giusta), le foto che non servono più, i
     * permessi tolti (una volta, al passaggio da concesso a tolto).
     */
    suspend fun dopo(context: Context, ora: Istante = Orologio.adesso()) {
        val app = context.applicationContext
        val memoria = withContext(Dispatchers.IO) { ArchivioBlocco.leggi(app) }
        // (0.18, contratto v4.0) Durante la Sessione Studio il blocco aspetta:
        // niente avviso adesso; a fine Studio parte, con l'avviso.
        val inStudio = withContext(Dispatchers.IO) { ArchivioStudio.leggi(app) }.attivo(OraServer.di(memoria, ora)) != null
        val bloccato = BloccoEStudio.applicato(memoria.attivoAdesso(ora), inStudio)
        annunciaSeServe(app, memoria, ora, bloccato)
        // (0.18) Un blocco che aspetta lo Studio si annuncia quando parte, a fine Studio.
        if (inStudio && memoria.annunciato != null) {
            withContext(Dispatchers.IO) { ArchivioBlocco.modifica(app) { it.copy(annunciato = null) } }
        }
        programmaSveglia(app, memoria, ora)
        withContext(Dispatchers.IO) { ArchivioCodaFoto.modifica(app) { it.conDaFare(memoria.daFare, ora.muro) } }
        val segnalati = try {
            PermessiRevocati.controlla(app, bloccato, ora.muro, inStudio)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        if (segnalati.isNotEmpty()) {
            try {
                ConsegnaEventi.subito(app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // restano in coda: le porta il worker
            }
        }
    }

    private suspend fun leggiNotifiche(app: Context) {
        novitaDaLeggere = false
        try {
            val impostazioni = Impostazioni(app)
            NovitaDalPatto.avvisa(app, impostazioni, PostinoClient(impostazioni.leggiConfigurazione()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            novitaDaLeggere = true // al giro dopo
        }
    }

    /** "Prima i lavori di casa: il telefono è bloccato", una volta per blocco; quando finisce, via dalla tendina. */
    private fun annunciaSeServe(app: Context, memoria: MemoriaBlocco, ora: Istante, bloccato: Boolean) {
        if (!bloccato) {
            AvvisiLocali.cancella(app, AvvisiLocali.ID_BLOCCO_FACCENDE)
            return
        }
        val episodio = memoria.daAnnunciare(ora) ?: return
        AvvisiLocali.avvisa(
            app,
            id = AvvisiLocali.ID_BLOCCO_FACCENDE,
            titolo = app.getString(R.string.notifica_blocco_partito),
            testo = app.getString(
                if (memoria.approvazione) R.string.notifica_blocco_partito_testo_approvazione else R.string.notifica_blocco_partito_testo,
            ),
            destinazione = MainActivity.DEST_FACCENDE,
            canale = AvvisiLocali.CANALE_FACCENDE,
        )
        // Segnato anche se gli avvisi sono spenti: un blocco vecchio non si annuncia ore dopo.
        ArchivioBlocco.modifica(app) { it.conAnnuncio(episodio) }
    }

    /** La sveglia all'ora (del server) del prossimo blocco, contata sull'orologio che non si sposta. */
    private fun programmaSveglia(app: Context, memoria: MemoriaBlocco, ora: Istante) {
        val partenza = memoria.prossimaPartenza(ora)
        val chiave = partenza?.let { it to memoria.ancora }
        if (chiave == svegliaChiesta) return
        val attesa = memoria.attesaPartenza(ora)
        if (attesa != null) SvegliaFaccende.programma(app, ora.monotono + attesa) else SvegliaFaccende.annulla(app)
        svegliaChiesta = chiave
    }
}
