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
 */
class ProposteViewModel(application: Application) : AndroidViewModel(application) {

    /** Un esito una-tantum da mostrare (dialogo del confronto o errore), poi consumato. */
    sealed interface Evento {
        data class Inviata(val confronto: String) : Evento
        data class Errore(val codice: String?) : Evento
    }

    data class StatoProposte(
        val caricamento: Boolean = true,
        val regoleAttive: List<RegolaFinestra> = emptyList(),
        val proposte: List<Proposta> = emptyList(),
        val configurazioneMancante: Boolean = false,
        val errore: Boolean = false,
        val invioInCorso: Boolean = false,
        val evento: Evento? = null,
    )

    private val _stato = MutableStateFlow(StatoProposte())
    val stato: StateFlow<StatoProposte> = _stato.asStateFlow()

    fun aggiorna() {
        _stato.value = _stato.value.copy(caricamento = true)
        viewModelScope.launch {
            val impostazioni = Impostazioni(getApplication())
            val configurazione = impostazioni.leggiConfigurazione()
            if (!configurazione.completa) {
                _stato.value = StatoProposte(caricamento = false, configurazioneMancante = true)
                return@launch
            }
            val postino = PostinoClient(configurazione)
            val proposte = postino.leggiProposte()
            if (proposte == null) {
                // Elenco proposte non arrivato: si tiene l'ultimo buono sotto l'avviso.
                _stato.value = _stato.value.copy(caricamento = false, errore = true)
                return@launch
            }
            impostazioni.registraVerificaRiuscita()
            // Le regole attive servono per la creazione: best effort (stesso server,
            // se le proposte arrivano di solito arrivano anche loro).
            val finestra = postino.leggiFinestra()
            val regoleAttive = finestra?.regole?.filter { it.attiva }
                ?: _stato.value.regoleAttive
            _stato.value = _stato.value.copy(
                caricamento = false,
                regoleAttive = regoleAttive,
                proposte = proposte,
                configurazioneMancante = false,
                errore = false,
            )
        }
    }

    fun creaProposta(regolaId: Long, parametriProposti: JsonObject, motivazione: String?) {
        _stato.value = _stato.value.copy(invioInCorso = true)
        viewModelScope.launch {
            val configurazione = Impostazioni(getApplication()).leggiConfigurazione()
            val esito = PostinoClient(configurazione).creaProposta(
                NuovaProposta(regolaId, parametriProposti, motivazione?.ifBlank { null }),
            )
            val evento = when (esito) {
                is EsitoScrittura.Riuscito -> Evento.Inviata(esito.dato.confronto)
                is EsitoScrittura.Rifiutato -> Evento.Errore(esito.errore)
                EsitoScrittura.Fallito -> Evento.Errore(null)
            }
            _stato.value = _stato.value.copy(invioInCorso = false, evento = evento)
            if (esito is EsitoScrittura.Riuscito) aggiorna()
        }
    }

    fun consumaEvento() {
        _stato.value = _stato.value.copy(evento = null)
    }
}
