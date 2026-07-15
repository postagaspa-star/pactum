package eu.stgm.pactum.genitore.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.pactum.genitore.dati.CorpoVerdetto
import eu.stgm.pactum.genitore.dati.Dichiarazione
import eu.stgm.pactum.genitore.dati.Impostazioni
import eu.stgm.pactum.genitore.dati.RegolaFinestra
import eu.stgm.pactum.genitore.rete.EsitoScrittura
import eu.stgm.pactum.genitore.rete.PostinoClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * I verdetti del genitore sulle dichiarazioni di successo `in_attesa`: conferma,
 * conferma per conto dell'arbitro, o ribalta. Serve anche la finestra per la
 * mappa regola→arbitro (il nome dell'arbitro vive nei parametri della regola
 * vita_reale). Contratto-api.md, sezione Dichiarazioni/Verdetto.
 */
class VerdettiViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface Evento {
        data object Inviato : Evento
        data class Errore(val codice: String?) : Evento
    }

    data class StatoVerdetti(
        val caricamento: Boolean = true,
        val regolePerId: Map<Long, RegolaFinestra> = emptyMap(),
        val dichiarazioni: List<Dichiarazione> = emptyList(),
        val configurazioneMancante: Boolean = false,
        val errore: Boolean = false,
        val invioInCorso: Boolean = false,
        val evento: Evento? = null,
    )

    private val _stato = MutableStateFlow(StatoVerdetti())
    val stato: StateFlow<StatoVerdetti> = _stato.asStateFlow()

    fun aggiorna() {
        _stato.value = _stato.value.copy(caricamento = true)
        viewModelScope.launch {
            val impostazioni = Impostazioni(getApplication())
            val configurazione = impostazioni.leggiConfigurazione()
            if (!configurazione.completa) {
                _stato.value = StatoVerdetti(caricamento = false, configurazioneMancante = true)
                return@launch
            }
            val postino = PostinoClient(configurazione)
            val dichiarazioni = postino.leggiDichiarazioni()
            if (dichiarazioni == null) {
                _stato.value = _stato.value.copy(caricamento = false, errore = true)
                return@launch
            }
            impostazioni.registraVerificaRiuscita()
            val finestra = postino.leggiFinestra()
            val regolePerId = finestra?.regole?.associateBy { it.id }
                ?: _stato.value.regolePerId
            _stato.value = _stato.value.copy(
                caricamento = false,
                regolePerId = regolePerId,
                dichiarazioni = dichiarazioni,
                configurazioneMancante = false,
                errore = false,
            )
        }
    }

    fun emettiVerdetto(dichiarazioneId: Long, verdetto: String, nota: String?) {
        _stato.value = _stato.value.copy(invioInCorso = true)
        viewModelScope.launch {
            val configurazione = Impostazioni(getApplication()).leggiConfigurazione()
            val esito = PostinoClient(configurazione).emettiVerdetto(
                dichiarazioneId,
                CorpoVerdetto(verdetto, nota?.ifBlank { null }),
            )
            val evento = when (esito) {
                is EsitoScrittura.Riuscito -> Evento.Inviato
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
