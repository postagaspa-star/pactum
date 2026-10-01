package eu.stgm.pactum.genitore.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.pactum.genitore.dati.CodiciErrore
import eu.stgm.pactum.genitore.dati.Impostazioni
import eu.stgm.pactum.genitore.dati.NuovaProposta
import eu.stgm.pactum.genitore.dati.Proposta
import eu.stgm.pactum.genitore.dati.RegolaFinestra
import eu.stgm.pactum.genitore.rete.EsitoScrittura
import eu.stgm.pactum.genitore.rete.PostinoClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Le proposte del genitore: elenco (con la risposta del figlio) e creazione di
 * una nuova proposta da una regola attiva. Il `confronto` che comparirà al figlio
 * lo calcola il SERVER e torna nella risposta a POST /api/proposte: si mostra
 * dopo la creazione (contratto-api.md, sezione Proposte).
 *
 * (v3) Tutto è del figlio scelto: `figlio_id` in lettura e nel corpo della
 * proposta. Le regole su cui proporre sono di tutti i suoi dispositivi, tranne
 * quelli scollegati.
 *
 * (0.10) Dal contratto v3.4 propone anche il figlio: qui il genitore risponde
 * alle sue proposte ([decidi]: se accetta, vale subito) e ritira le proprie
 * ancora in attesa ([ritira]). Vive nello scope dell'attività: la Panoramica,
 * che mostra le stesse proposte da decidere in cima, decide da qui, così le due
 * schermate sanno le stesse cose ([StatoProposte.giaChiuse]). Gli esiti arrivano
 * a ciascuna schermata per conto suo ([esiti]): nessuno si perde, nessuno finisce
 * sulla schermata sbagliata.
 */
class ProposteViewModel(application: Application) : AndroidViewModel(application) {

    /** (0.10) Le due schermate che usano queste proposte, ciascuna coi suoi esiti. */
    enum class Schermata { PANORAMICA, PROPOSTE }

    /** Un esito una-tantum da mostrare (dialogo del confronto o frase in basso). */
    sealed interface Evento {
        data class Inviata(val confronto: String) : Evento
        data class Errore(val codice: String?) : Evento

        /**
         * (0.10) La risposta a una proposta del figlio è arrivata: [esito] `accetta`
         * o `rifiuta`; [eliminazione] = la proposta era di togliere la regola.
         */
        data class Decisa(val esito: String, val eliminazione: Boolean) : Evento

        /**
         * (0.10) La risposta non è passata: [codice] dice perché (null = rete
         * caduta); [statoFinale] = com'è finita la proposta, se il server ha detto
         * che non era più in attesa e la rilettura l'ha trovata.
         */
        data class NonDecisa(val codice: String?, val statoFinale: String? = null) : Evento

        /** (0.10) La proposta del genitore è ritirata. */
        data object Ritirata : Evento

        /** (0.10) Il ritiro non è passato: come [NonDecisa]. */
        data class NonRitirata(val codice: String?, val statoFinale: String? = null) : Evento
    }

    data class StatoProposte(
        /** Di quale figlio parla questo stato; null = server 0.7. */
        val figlioId: Long? = null,
        val richiesta: Boolean = false,
        val caricamento: Boolean = true,
        val regoleAttive: List<RegolaFinestra> = emptyList(),
        /**
         * (0.10) Quelle di GET /api/proposte (di tutti e due gli autori) più le
         * pendenti della finestra che lì mancano (proposteUnite).
         */
        val proposte: List<Proposta> = emptyList(),
        /** (0.10) Quando è partita la lettura di [proposte], sull'orologio monotono. */
        val lettaAlle: Long? = null,
        /**
         * (0.10) Tutte le regole della finestra (anche eliminate o di un
         * dispositivo scollegato): servono a raccontare le proposte del figlio e
         * la storia, non solo a proporre.
         */
        val regolePerId: Map<Long, RegolaFinestra> = emptyMap(),
        /** (0.10) Il figlio ha più dispositivi: una proposta dice anche su quale vale. */
        val piuDispositivi: Boolean = false,
        /** (0.10) I nomi delle app che la finestra conosce: una proposta che cambia app la dice col nome. */
        val nomi: Map<String, String> = emptyMap(),
        /** (0.10) I dispositivi scollegati: sulle loro regole una proposta si può solo rifiutare. */
        val scollegati: Set<Long> = emptySet(),
        val configurazioneMancante: Boolean = false,
        val errore: Boolean = false,
        /**
         * Una scrittura è in volo (proposta, risposta, ritiro): una alla volta, e
         * (0.10) anche cambiando figlio il pulsante resta spento finché non torna.
         */
        val invioInCorso: Boolean = false,
        /**
         * (0.10) Le proposte appena decise o ritirate da qui (o che il server ha
         * detto non più in attesa): spariscono subito dalle card, anche nella
         * Panoramica, ma solo nei dati letti prima (PropostaChiusa). Gli id restano
         * finché l'elenco e la finestra di quel figlio non sono stati riletti.
         */
        val giaChiuse: Map<Long, PropostaChiusa> = emptyMap(),
    ) {
        /** true = questi dati sono del figlio [id]. */
        fun di(id: Long?): Boolean = richiesta && figlioId == id
    }

    private val _stato = MutableStateFlow(StatoProposte())
    val stato: StateFlow<StatoProposte> = _stato.asStateFlow()
    private var lettura: Job? = null

    // (0.10) Gli esiti, uno per schermata: ciascuno arriva una volta, nell'ordine, a
    // chi l'ha chiesto, anche se in quel momento è su un'altra scheda (lo trova al
    // ritorno). Con un posto solo, un esito ne copriva un altro.
    private val esitiPanoramica = Channel<Evento>(Channel.UNLIMITED)
    private val esitiProposte = Channel<Evento>(Channel.UNLIMITED)

    /**
     * (0.10) Gli inizi delle ultime letture riuscite della finestra della Panoramica,
     * per figlio (orologio monotono): servono a sapere quando una chiusura non fa
     * più da ponte (chiusureDaTenere).
     */
    private val lettureFinestra = mutableMapOf<Long?, Long>()

    /** (0.10) Gli esiti per [schermata], uno per volta: chi li raccoglie li mostra. */
    fun esiti(schermata: Schermata): Flow<Evento> = canale(schermata).receiveAsFlow()

    private fun canale(schermata: Schermata): Channel<Evento> =
        if (schermata == Schermata.PANORAMICA) esitiPanoramica else esitiProposte

    fun aggiorna(figlioId: Long?) {
        val prima = _stato.value
        // Un altro figlio: via i dati del precedente, mai sotto il nome sbagliato.
        // (0.10) Restano le proposte chiuse da qui (gli id sono di tutto il server)
        // e una scrittura in volo (il pulsante resta spento finché non torna).
        _stato.value = if (!prima.richiesta || prima.figlioId != figlioId) {
            StatoProposte(
                figlioId = figlioId,
                richiesta = true,
                caricamento = true,
                invioInCorso = prima.invioInCorso,
                giaChiuse = prima.giaChiuse,
            )
        } else {
            prima.copy(caricamento = true)
        }
        lettura?.cancel()
        lettura = viewModelScope.launch {
            val inizio = SystemClock.elapsedRealtime()
            val impostazioni = Impostazioni(getApplication())
            val configurazione = impostazioni.leggiConfigurazione()
            if (!configurazione.completa) {
                _stato.value = StatoProposte(
                    figlioId = figlioId,
                    richiesta = true,
                    caricamento = false,
                    configurazioneMancante = true,
                    invioInCorso = _stato.value.invioInCorso,
                    giaChiuse = _stato.value.giaChiuse,
                )
                return@launch
            }
            val postino = PostinoClient(configurazione)
            val proposte = postino.leggiProposte(figlioId)
            if (proposte == null) {
                // Elenco proposte non arrivato: si tiene l'ultimo buono sotto l'avviso.
                _stato.value = _stato.value.copy(caricamento = false, errore = true)
                return@launch
            }
            impostazioni.registraVerificaRiuscita()
            // Le regole attive servono per la creazione. Se la finestra non arriva
            // (ma le proposte sì) si tiene l'ultimo elenco buono E si segnala l'errore:
            // altrimenti la sezione "Proponi" mostrerebbe una lista vecchia o vuota
            // spacciandola per aggiornata (o "nessuna regola attiva" quando in realtà
            // non l'abbiamo letta).
            val finestra = postino.leggiFinestra(figlioId)
            val regoleAttive = finestra?.let(::regoleProponibili) ?: _stato.value.regoleAttive
            val attuale = _stato.value
            _stato.value = attuale.copy(
                caricamento = false,
                regoleAttive = regoleAttive,
                // (0.10) Più le pendenti della finestra: una in attesa più vecchia delle
                // ultime 50 non sparisce, e la sua regola non offre un'altra proposta.
                proposte = proposteUnite(proposte, finestra?.propostePendenti.orEmpty()),
                lettaAlle = inizio,
                regolePerId = finestra?.regole?.associateBy { it.id } ?: attuale.regolePerId,
                piuDispositivi = finestra?.let(::piuDispositiviAttivi) ?: attuale.piuDispositivi,
                nomi = finestra?.let(::nomiDelleApp) ?: attuale.nomi,
                scollegati = finestra?.let(::dispositiviScollegati) ?: attuale.scollegati,
                giaChiuse = chiusureDaTenere(attuale.giaChiuse, LetturaElenco(figlioId, inizio), lettureFinestra),
                configurazioneMancante = false,
                errore = finestra == null,
            )
        }
    }

    /**
     * (0.10) La Panoramica ha letto la finestra di [figlioId], con una lettura
     * partita a [iniziataAlle] (orologio monotono): le chiusure di quel figlio
     * che ormai tutte e due le schermate hanno superato non servono più.
     */
    fun finestraLetta(figlioId: Long?, iniziataAlle: Long) {
        val prima = lettureFinestra[figlioId]
        if (prima != null && prima >= iniziataAlle) return
        lettureFinestra[figlioId] = iniziataAlle
        val attuale = _stato.value
        val tenute = chiusureDaTenere(
            attuale.giaChiuse,
            LetturaElenco(attuale.figlioId, attuale.lettaAlle).takeIf { attuale.richiesta },
            lettureFinestra,
        )
        if (tenute.size != attuale.giaChiuse.size) _stato.value = attuale.copy(giaChiuse = tenute)
    }

    fun creaProposta(
        figlioId: Long?,
        regolaId: Long,
        parametriProposti: JsonObject,
        motivazione: String?,
    ) {
        _stato.value = _stato.value.copy(invioInCorso = true)
        viewModelScope.launch {
            val configurazione = Impostazioni(getApplication()).leggiConfigurazione()
            val esito = PostinoClient(configurazione).creaProposta(
                NuovaProposta(
                    regolaId = regolaId,
                    parametriProposti = parametriProposti,
                    motivazione = motivazione?.ifBlank { null },
                    figlioId = figlioId,
                ),
            )
            val evento = when (esito) {
                is EsitoScrittura.Riuscito -> Evento.Inviata(esito.dato.confronto)
                is EsitoScrittura.Rifiutato -> Evento.Errore(esito.errore)
                EsitoScrittura.Fallito -> Evento.Errore(null)
            }
            _stato.value = _stato.value.copy(invioInCorso = false)
            esitiProposte.trySend(evento)
            if (esito is EsitoScrittura.Riuscito && _stato.value.di(figlioId)) aggiorna(figlioId)
        }
    }

    /**
     * (0.10) La risposta del genitore a una proposta del figlio (contratto v3.4):
     * [esito] `accetta` (la modifica vale subito, il server la applica da solo) o
     * `rifiuta`, con un perché facoltativo. Una alla volta. L'esito va a [da], la
     * schermata da cui si è risposto. Se il server dice che non era più in attesa
     * si rilegge com'è finita, per dirlo. Dopo, sempre una rilettura: dopo un sì la
     * regola è cambiata, dopo un rifiuto del server la proposta non è più com'era.
     */
    fun decidi(
        figlioId: Long?,
        proposta: Proposta,
        esito: String,
        motivazione: String?,
        da: Schermata,
    ) {
        if (_stato.value.invioInCorso) return
        _stato.value = _stato.value.copy(invioInCorso = true)
        viewModelScope.launch {
            val configurazione = Impostazioni(getApplication()).leggiConfigurazione()
            val postino = PostinoClient(configurazione)
            val risposta = postino.rispondiProposta(
                proposta.id,
                esito,
                motivazione?.trim()?.ifBlank { null },
                figlioId,
            )
            val evento = when (risposta) {
                is EsitoScrittura.Riuscito -> Evento.Decisa(esito, eliminazione(proposta))
                is EsitoScrittura.Rifiutato ->
                    Evento.NonDecisa(risposta.errore, statoFinale(postino, figlioId, proposta, risposta))
                EsitoScrittura.Fallito -> Evento.NonDecisa(null)
            }
            concludi(figlioId, proposta.id, risposta, evento, da)
        }
    }

    /**
     * (0.10) Il ritiro di una proposta del genitore ancora in attesa (contratto
     * v3.4): la regola non cambia, e il figlio non la vede più da decidere.
     */
    fun ritira(figlioId: Long?, proposta: Proposta) {
        if (_stato.value.invioInCorso) return
        _stato.value = _stato.value.copy(invioInCorso = true)
        viewModelScope.launch {
            val configurazione = Impostazioni(getApplication()).leggiConfigurazione()
            val postino = PostinoClient(configurazione)
            val risposta = postino.ritiraProposta(proposta.id)
            val evento = when (risposta) {
                is EsitoScrittura.Riuscito -> Evento.Ritirata
                is EsitoScrittura.Rifiutato ->
                    Evento.NonRitirata(risposta.errore, statoFinale(postino, figlioId, proposta, risposta))
                EsitoScrittura.Fallito -> Evento.NonRitirata(null)
            }
            concludi(figlioId, proposta.id, risposta, evento, Schermata.PROPOSTE)
        }
    }

    /**
     * Com'è finita una proposta che il server ha detto non più in attesa: lo stato
     * letto subito dopo. null per gli altri rifiuti, o se la rilettura non la trova.
     */
    private suspend fun statoFinale(
        postino: PostinoClient,
        figlioId: Long?,
        proposta: Proposta,
        rifiuto: EsitoScrittura.Rifiutato,
    ): String? {
        if (rifiuto.errore != CodiciErrore.PROPOSTA_NON_PENDENTE) return null
        return postino.leggiProposte(figlioId)?.firstOrNull { it.id == proposta.id }?.stato
    }

    /**
     * La fine di una risposta o di un ritiro: la card che sparisce se la proposta
     * non è più in attesa (da adesso: i dati letti prima non la mostrano più),
     * l'esito alla schermata [da], e la rilettura (solo se l'elenco mostrato è di
     * quel figlio: la Panoramica rilegge la sua finestra da sé).
     */
    private fun concludi(
        figlioId: Long?,
        propostaId: Long,
        risposta: EsitoScrittura<*>,
        evento: Evento,
        da: Schermata,
    ) {
        val prima = _stato.value
        val chiusa = PropostaChiusa(figlioId, SystemClock.elapsedRealtime())
        _stato.value = prima.copy(
            invioInCorso = false,
            giaChiuse = if (propostaChiusaDopo(risposta)) prima.giaChiuse + (propostaId to chiusa) else prima.giaChiuse,
        )
        canale(da).trySend(evento)
        if (_stato.value.di(figlioId) && risposta !is EsitoScrittura.Fallito) aggiorna(figlioId)
    }

    /** Dopo un cambio di server: quello che si sapeva è di un altro server. */
    fun dimentica() {
        lettura?.cancel()
        lettureFinestra.clear()
        _stato.value = StatoProposte()
    }
}

/**
 * (0.10) true = dopo questa risposta del server la proposta non è più in attesa:
 * la sua card sparisce subito, senza aspettare la rilettura. Un sì, un no o un
 * ritiro andati a buon fine; una proposta che il server dice non più in attesa
 * (ritirata, già decisa, annullata) o che non c'è più. Con `ultima_regola` e
 * `dispositivo_revocato` la proposta resta in attesa (si può ancora rifiutare),
 * e una rete caduta non dice niente: la card resta.
 */
fun propostaChiusaDopo(risposta: EsitoScrittura<*>): Boolean = when (risposta) {
    is EsitoScrittura.Riuscito -> true
    is EsitoScrittura.Rifiutato ->
        risposta.errore == CodiciErrore.PROPOSTA_NON_PENDENTE || risposta.errore == CodiciErrore.NON_TROVATO
    EsitoScrittura.Fallito -> false
}
