package eu.stgm.pactum.genitore.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.pactum.genitore.dati.Impostazioni
import eu.stgm.pactum.genitore.dati.NuovaProposta
import eu.stgm.pactum.genitore.dati.Proposta
import eu.stgm.pactum.genitore.dati.RegolaFinestra
import eu.stgm.pactum.genitore.rete.EsitoScrittura
import eu.stgm.pactum.genitore.rete.PostinoClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 */
class ProposteViewModel(application: Application) : AndroidViewModel(application) {

    /** Un esito una-tantum da mostrare (dialogo del confronto o errore), poi consumato. */
    sealed interface Evento {
        data class Inviata(val confronto: String) : Evento
        data class Errore(val codice: String?) : Evento
    }

    data class StatoProposte(
        /** Di quale figlio parla questo stato; null = server 0.7. */
        val figlioId: Long? = null,
        val richiesta: Boolean = false,
        val caricamento: Boolean = true,
        val regoleAttive: List<RegolaFinestra> = emptyList(),
        val proposte: List<Proposta> = emptyList(),
        val configurazioneMancante: Boolean = false,
        val errore: Boolean = false,
        val invioInCorso: Boolean = false,
        val evento: Evento? = null,
    ) {
        /** true = questi dati sono del figlio [id]. */
        fun di(id: Long?): Boolean = richiesta && figlioId == id
    }

    private val _stato = MutableStateFlow(StatoProposte())
    val stato: StateFlow<StatoProposte> = _stato.asStateFlow()
    private var lettura: Job? = null

    fun aggiorna(figlioId: Long?) {
        val prima = _stato.value
        // Un altro figlio: via i dati del precedente, mai sotto il nome sbagliato.
        _stato.value = if (!prima.richiesta || prima.figlioId != figlioId) {
            StatoProposte(figlioId = figlioId, richiesta = true, caricamento = true)
        } else {
            prima.copy(caricamento = true)
        }
        lettura?.cancel()
        lettura = viewModelScope.launch {
            val impostazioni = Impostazioni(getApplication())
            val configurazione = impostazioni.leggiConfigurazione()
            if (!configurazione.completa) {
                _stato.value = StatoProposte(
                    figlioId = figlioId,
                    richiesta = true,
                    caricamento = false,
                    configurazioneMancante = true,
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
            _stato.value = _stato.value.copy(
                caricamento = false,
                regoleAttive = regoleAttive,
                proposte = proposte,
                configurazioneMancante = false,
                errore = finestra == null,
            )
        }
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
            _stato.value = _stato.value.copy(invioInCorso = false, evento = evento)
            if (esito is EsitoScrittura.Riuscito && _stato.value.figlioId == figlioId) aggiorna(figlioId)
        }
    }

    fun consumaEvento() {
        _stato.value = _stato.value.copy(evento = null)
    }

    /** Dopo un cambio di server: quello che si sapeva è di un altro server. */
    fun dimentica() {
        lettura?.cancel()
        _stato.value = StatoProposte()
    }
}
