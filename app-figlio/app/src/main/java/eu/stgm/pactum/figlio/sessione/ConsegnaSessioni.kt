package eu.stgm.pactum.figlio.sessione

import android.content.Context
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.dati.PattoLocale
import eu.stgm.pactum.figlio.faccende.ArchivioBlocco
import eu.stgm.pactum.figlio.faccende.Orologio
import eu.stgm.pactum.figlio.rete.PostinoClient
import eu.stgm.pactum.figlio.servizio.PactumService
import eu.stgm.pactum.figlio.studio.ArchivioStudio
import eu.stgm.pactum.figlio.sync.Ritento
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * (0.11) L'avvio e la chiusura anticipata di una Sessione verso il server.
 *
 * Iniziare ha bisogno del server (è lui a dire che la sessione è approvata):
 * senza rete non parte, e lo si dice chiaro. Se la richiesta parte e la
 * risposta si perde, non si sa: lo si dice ("controllo appena c'è rete"), si
 * ricorda, e si ricontrolla dal giro della sentinella e dal worker. Una
 * sessione che il ragazzo non sa partita non copre niente finché non gliela si
 * annuncia (AnnunciSessione).
 *
 * Terminare prima invece vale SUBITO, qui: la barriera si ferma adesso, e la
 * chiusura (con l'istante vero e `svolta_id`) parte verso il server appena
 * può, ritentata con attesa crescente finché il server la prende o dice che
 * quella sessione non è in corso (404): consegnata tardi, non chiude mai una
 * sessione avviata dopo, e vale comunque da quando è stata fatta.
 *
 * Un solo mutex per avvio e consegne, e prima di ogni avvio si consegnano le
 * chiusure ancora in attesa: per il server la sessione di prima deve essere
 * chiusa, sennò il nuovo avvio sarebbe rifiutato. Tutto gira nell'ambito del
 * PROCESSO: se la schermata se ne va a metà, una sessione appena avviata viene
 * comunque registrata.
 */
object ConsegnaSessioni {

    private val mutex = Mutex()
    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // I tentativi falliti di fila e quando è partito l'ultimo, come
    // ConsegnaEventi. In memoria: chiusure e avvio incerto restano su disco, e
    // il primo giro dopo la morte del processo riprova.
    @Volatile private var falliti = 0
    @Volatile private var ultimoTentativo = 0L

    /** Com'è andata la consegna delle chiusure in attesa. */
    internal enum class Consegna { FATTA, SENZA_RETE, ERRORE_SERVER, SCOLLEGATO }

    /**
     * Prima di un avvio si consegnano le chiusure in attesa: se non riesce,
     * l'avvio si ferma e lo si dice per quello che è. Un no del server non è
     * "niente rete". Null = consegnate, si va avanti.
     */
    internal fun esitoPrimaDellAvvio(consegna: Consegna): EsitoAvvio? = when (consegna) {
        Consegna.FATTA -> null
        Consegna.SENZA_RETE -> EsitoAvvio.SenzaRete
        Consegna.ERRORE_SERVER -> EsitoAvvio.Errore
        Consegna.SCOLLEGATO -> EsitoAvvio.Scollegato
    }

    /** "Inizia" per [durataMinuti]: com'è andata. Un imprevisto è un "riprova", mai un crash. */
    suspend fun avvia(context: Context, sessioneId: Long, nome: String, durataMinuti: Int): EsitoAvvio {
        val app = context.applicationContext
        return ambito.async {
            try {
                mutex.withLock { avviaDentro(app, sessioneId, nome, durataMinuti) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EsitoAvvio.Errore
            }
        }.await()
    }

    /**
     * "Termina la sessione": finita adesso, sul telefono, e la barriera si
     * ferma. Restituisce la sessione appena chiusa (con la sua fine vera: per
     * la pagina della fine), null se non c'era niente in corso. La consegna al
     * server parte da sola, e se non riesce si riprova.
     */
    suspend fun termina(context: Context): SvoltaLocale? {
        val app = context.applicationContext
        val chiusa = try {
            withContext(Dispatchers.IO) {
                ArchivioSessioni.modificaCon(app) { memoria ->
                    val (nuova, chiusura) = memoria.conTermine(System.currentTimeMillis())
                    nuova to chiusura?.let { c -> nuova.svolte.firstOrNull { it.id == c.svoltaId } }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (chiusa == null) return null
        falliti = 0
        ambito.launch {
            try {
                consegna(app)
            } catch (e: Exception) {
                // resta in coda: riprova il giro della sentinella, poi il worker
            }
        }
        return chiusa
    }

    /**
     * Dal giro della sentinella (e dal worker, con [forza]): chiusure da
     * consegnare e "Inizia" senza risposta da chiarire. Se l'attesa è passata
     * (o con [forza]), si riprova.
     */
    suspend fun riprovaSeServe(context: Context, adesso: Long = System.currentTimeMillis(), forza: Boolean = false) {
        val app = context.applicationContext
        val memoria = withContext(Dispatchers.IO) { ArchivioSessioni.leggi(app) }
        if (memoria.terminazioni.isEmpty() && memoria.avvioIncerto == null) {
            falliti = 0
            return
        }
        if (!forza && !Ritento.pronto(falliti, ultimoTentativo, adesso)) return
        val ok = withContext(Dispatchers.IO) {
            mutex.withLock {
                ultimoTentativo = System.currentTimeMillis()
                val configurazione = Impostazioni(app).leggiConfigurazione()
                if (!configurazione.completa) return@withLock false
                val postino = PostinoClient(configurazione)
                val consegnate = consegnaDentro(app, postino) == Consegna.FATTA
                val chiarito = chiarisciAvvioIncerto(app, postino)
                consegnate && chiarito
            }
        }
        if (ok) falliti = 0 else falliti += 1
    }

    /** Manda adesso le chiusure in attesa. True se non ne resta nessuna. */
    suspend fun consegna(context: Context): Boolean = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        mutex.withLock {
            val configurazione = Impostazioni(app).leggiConfigurazione()
            ultimoTentativo = System.currentTimeMillis()
            val ok = configurazione.completa && consegnaDentro(app, PostinoClient(configurazione)) == Consegna.FATTA
            if (ok) falliti = 0 else falliti += 1
            ok
        }
    }

    private suspend fun avviaDentro(app: Context, sessioneId: Long, nome: String, durataMinuti: Int): EsitoAvvio {
        if (!DurataSessione.valida(durataMinuti)) return EsitoAvvio.DurataNonValida
        val configurazione = Impostazioni(app).leggiConfigurazione()
        if (!configurazione.completa) return EsitoAvvio.Scollegato
        if (ArchivioSessioni.leggi(app).inCorso(System.currentTimeMillis()) != null) {
            return EsitoAvvio.GiaInCorso(svoltaInCorso(app))
        }
        // (0.18, contratto v4.0) Durante la Sessione Studio le sessioni non si
        // avviano (controllato prima del blocco, come sul server).
        val ora = Orologio.adesso()
        val blocco = ArchivioBlocco.leggi(app)
        if (ArchivioStudio.leggi(app).attivo(eu.stgm.pactum.figlio.studio.OraServer.di(blocco, ora)) != null) return EsitoAvvio.StudioInCorso
        // (0.13) Col blocco delle faccende una sessione non si avvia: il
        // telefono lo sa già (anche senza rete), e non lo chiede al server.
        if (blocco.attivoAdesso(ora)) return EsitoAvvio.BloccoFaccende
        val postino = PostinoClient(configurazione)
        // Prima le chiusure ancora in attesa: finché il server ha aperta la
        // sessione di prima, rifiuterebbe questa ("già in corso").
        esitoPrimaDellAvvio(consegnaDentro(app, postino))?.let { return it }

        val richiestoIl = System.currentTimeMillis()
        val risposta = postino.avviaSessione(sessioneId, AvvioSessioneIn(durataMinuti))
        val esito = EsitiSessioni.avvio(risposta.ok, risposta.codice, risposta.corpo, risposta.incerta)
        if (esito is EsitoAvvio.Avviata && esito.svolta != null) {
            // Inizio e fine sull'orologio del telefono: quello degli eventi d'uso.
            val ancorata = esito.svolta.ancorataAlTelefono(System.currentTimeMillis(), durataMinuti)
            ArchivioSessioni.modifica(app) { it.conAvvio(ancorata, System.currentTimeMillis()) }
            assicuraServizio(app)
            return EsitoAvvio.Avviata(ancorata)
        }
        val daVerificare = esito is EsitoAvvio.Avviata || esito is EsitoAvvio.GiaInCorso ||
            esito == EsitoAvvio.Incerto || esito == EsitoAvvio.Errore
        if (!daVerificare) return esito

        // Iniziata ma illeggibile, già in corso, o risposta persa per strada:
        // lo dice il patto (sessione_in_corso), che la riporta anche qui.
        val ritrovata = ritrovaInCorso(app, postino)
        if (ritrovata != null) {
            // Il ragazzo sta guardando la risposta: lo sa, e vale la barriera.
            ArchivioSessioni.modifica(app) { it.conAvvio(ritrovata, System.currentTimeMillis()) }
            assicuraServizio(app)
            return if (ritrovata.sessioneId == sessioneId && esito !is EsitoAvvio.GiaInCorso) {
                EsitoAvvio.Avviata(ritrovata)
            } else {
                EsitoAvvio.GiaInCorso(ritrovata)
            }
        }
        return when (esito) {
            // Partita di sicuro ma non si ritrova (patto non letto), o non si sa:
            // si ricontrolla appena c'è rete. Intanto non si copre niente.
            is EsitoAvvio.Avviata, EsitoAvvio.Incerto -> {
                ArchivioSessioni.modifica(app) {
                    it.conAvvioIncerto(AvvioIncerto(sessioneId, nome, durataMinuti, richiestoIl))
                }
                EsitoAvvio.Incerto
            }
            else -> esito
        }
    }

    /** La sessione svolta in corso adesso, se c'è. */
    private fun svoltaInCorso(app: Context): SvoltaLocale? {
        val memoria = ArchivioSessioni.leggi(app)
        val attiva = memoria.inCorso(System.currentTimeMillis()) ?: return null
        return memoria.svolte.firstOrNull { it.id == attiva.svoltaId }
    }

    /** Il patto fresco, e la sessione in corso che riporta (null se non si legge o non c'è). */
    private suspend fun ritrovaInCorso(app: Context, postino: PostinoClient): SvoltaLocale? {
        val patto = postino.leggiPatto() ?: return null
        if (!PattoLocale(app).salva(patto)) return null
        return svoltaInCorso(app)
    }

    /**
     * Un "Inizia" rimasto senza risposta: una lettura fresca del server dice
     * com'è andata. Partita e in corso: la si annuncia come ogni sessione
     * ritrovata (AnnunciSessione, dal servizio) e vale la barriera. Già finita,
     * o mai partita: lo si dice al ragazzo. True se non resta niente da chiarire.
     */
    private suspend fun chiarisciAvvioIncerto(app: Context, postino: PostinoClient): Boolean {
        val incerto = ArchivioSessioni.leggi(app).avvioIncerto ?: return true
        // Una risposta appena persa può essere ancora in viaggio: si aspetta un po'.
        if (System.currentTimeMillis() - incerto.richiestoIl < ATTESA_PRIMA_DI_CHIARIRE_MS) return false
        val patto = postino.leggiPatto() ?: return false
        if (!PattoLocale(app).salva(patto) || !patto.conosceSessioni) return false
        val esito = ArchivioSessioni.leggi(app).esitoAvvioIncerto(System.currentTimeMillis()) ?: return true
        ArchivioSessioni.modifica(app) { it.senzaAvvioIncerto() }
        when (esito) {
            EsitoAvvioIncerto.PARTITA -> Unit
            EsitoAvvioIncerto.GIA_FINITA -> AnnunciSessione.giaFinita(app, incerto.nome)
            EsitoAvvioIncerto.NON_PARTITA -> AnnunciSessione.nonPartita(app, incerto.nome)
        }
        return true
    }

    /**
     * Le chiusure in attesa, una per una. Quelle che non servono più (la
     * sessione non c'è più, o il server la dice già terminata) se ne vanno
     * senza partire; un 404 del server vuol dire "quella sessione non è in
     * corso": si lascia andare, senza riprovare e senza dire niente. Ogni altro
     * no si tiene e si riprova.
     */
    private suspend fun consegnaDentro(app: Context, postino: PostinoClient): Consegna {
        val memoria = ArchivioSessioni.leggi(app)
        if (memoria.terminazioni.isEmpty()) return Consegna.FATTA
        val daMandare = memoria.daConsegnare()
        val inutili = memoria.terminazioni.filterNot { it in daMandare }.map { it.svoltaId }.toSet()
        if (inutili.isNotEmpty()) {
            ArchivioSessioni.modifica(app) { m -> inutili.fold(m) { acc, id -> acc.senzaTerminazione(id) } }
        }
        for (chiusura in daMandare) {
            val risposta = postino.terminaSessione(TerminaSessioneIn(tsDevice = chiusura.tsDevice, svoltaId = chiusura.svoltaId))
            when (val esito = EsitiSessioni.termina(risposta.ok, risposta.codice, risposta.corpo)) {
                is EsitoTermina.Terminata -> ArchivioSessioni.modifica(app) {
                    it.conTerminata(chiusura.svoltaId, esito.svolta?.inLocale())
                }
                EsitoTermina.NienteInCorso -> ArchivioSessioni.modifica(app) { it.senzaTerminazione(chiusura.svoltaId) }
                EsitoTermina.SenzaRete -> return Consegna.SENZA_RETE
                EsitoTermina.ErroreServer -> return Consegna.ERRORE_SERVER
                EsitoTermina.Scollegato -> return Consegna.SCOLLEGATO
            }
        }
        return if (ArchivioSessioni.leggi(app).terminazioni.isEmpty()) Consegna.FATTA else Consegna.ERRORE_SERVER
    }

    /**
     * (0.18, contratto v4.0) Alla partenza della Sessione Studio, anche senza
     * rete, la sessione normale in corso si chiude qui (`terminata`, fine =
     * l'inizio dello Studio, [al] sull'orologio del telefono) e la chiusura si
     * consegna come quella di "Termina la sessione". Solo una sessione già
     * iniziata a quell'istante. Restituisce la sessione chiusa, se c'era.
     */
    suspend fun terminaPerStudio(context: Context, al: Long): SvoltaLocale? {
        val app = context.applicationContext
        val chiusa = try {
            withContext(Dispatchers.IO) {
                ArchivioSessioni.modificaCon(app) { memoria ->
                    val (nuova, chiusura) = memoria.conTermineAl(al, System.currentTimeMillis())
                    nuova to chiusura?.let { c -> nuova.svolte.firstOrNull { it.id == c.svoltaId } }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (chiusa == null) return null
        falliti = 0
        ambito.launch {
            try {
                consegna(app)
            } catch (e: Exception) {
                // resta in coda: riprova il giro della sentinella, poi il worker
            }
        }
        return chiusa
    }

    /** La barriera vive nel servizio del testimone: se non c'è, si accende (siamo in primo piano). */
    private fun assicuraServizio(app: Context) {
        try {
            PactumService.avvia(app)
        } catch (e: Exception) {
            // rifiutato: lo riaccende il worker al suo giro
        }
    }

    /** Un avvio incerto si chiarisce dopo almeno mezzo minuto: la richiesta può essere ancora in viaggio. */
    private const val ATTESA_PRIMA_DI_CHIARIRE_MS = 30_000L
}
