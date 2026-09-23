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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * I verdetti del genitore sulle dichiarazioni di successo `in_attesa`: conferma,
 * conferma per conto dell'arbitro, o ribalta. Serve anche la finestra per la
 * mappa regola→arbitro (il nome dell'arbitro vive nei parametri della regola
 * vita_reale). Contratto-api.md, sezione Dichiarazioni/Verdetto.
 *
 * (v3) Le dichiarazioni sono del figlio scelto (la vita reale è del figlio, non
 * di un dispositivo): `figlio_id` in lettura e nel corpo del verdetto.
 */
class VerdettiViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface Evento {
        data object Inviato : Evento
        data class Errore(val codice: String?) : Evento
    }

    data class StatoVerdetti(
        /** Di quale figlio parla questo stato; null = server 0.7. */
        val figlioId: Long? = null,
        val richiesta: Boolean = false,
        val caricamento: Boolean = true,
        val regolePerId: Map<Long, RegolaFinestra> = emptyMap(),
        val dichiarazioni: List<Dichiarazione> = emptyList(),
        val configurazioneMancante: Boolean = false,
        val errore: Boolean = false,
        val invioInCorso: Boolean = false,
        val evento: Evento? = null,
    ) {
        /** true = questi dati sono del figlio [id]. */
        fun di(id: Long?): Boolean = richiesta && figlioId == id
    }

    private val _stato = MutableStateFlow(StatoVerdetti())
    val stato: StateFlow<StatoVerdetti> = _stato.asStateFlow()
    private var lettura: Job? = null

    fun aggiorna(figlioId: Long?) {
        val prima = _stato.value
        _stato.value = if (!prima.richiesta || prima.figlioId != figlioId) {
            StatoVerdetti(figlioId = figlioId, richiesta = true, caricamento = true)
        } else {
            prima.copy(caricamento = true)
        }
        lettura?.cancel()
        lettura = viewModelScope.launch {
            val impostazioni = Impostazioni(getApplication())
            val configurazione = impostazioni.leggiConfigurazione()
            if (!configurazione.completa) {
                _stato.value = StatoVerdetti(
                    figlioId = figlioId,
                    richiesta = true,
                    caricamento = false,
                    configurazioneMancante = true,
                )
                return@launch
            }
            val postino = PostinoClient(configurazione)
            val dichiarazioni = postino.leggiDichiarazioni(figlioId)
            if (dichiarazioni == null) {
                _stato.value = _stato.value.copy(caricamento = false, errore = true)
                return@launch
            }
            impostazioni.registraVerificaRiuscita()
            // Serve la finestra per la mappa regola→descrizione/arbitro. Se non arriva
            // (ma le dichiarazioni sì) si tiene l'ultima mappa buona E si segnala
            // l'errore: senza il flag le card mostrerebbero regola/arbitro vecchi o
            // "sconosciuta" facendoli passare per aggiornati.
            val finestra = postino.leggiFinestra(figlioId)
            val regolePerId = finestra?.regole?.associateBy { it.id }
                ?: _stato.value.regolePerId
            _stato.value = _stato.value.copy(
                caricamento = false,
                regolePerId = regolePerId,
                dichiarazioni = dichiarazioni,
                configurazioneMancante = false,
                errore = finestra == null,
            )
        }
    }

    fun emettiVerdetto(figlioId: Long?, dichiarazioneId: Long, verdetto: String, nota: String?) {
        _stato.value = _stato.value.copy(invioInCorso = true)
        viewModelScope.launch {
            val configurazione = Impostazioni(getApplication()).leggiConfigurazione()
            val esito = PostinoClient(configurazione).emettiVerdetto(
                dichiarazioneId,
                CorpoVerdetto(verdetto, nota?.ifBlank { null }, figlioId),
            )
            val evento = when (esito) {
                is EsitoScrittura.Riuscito -> Evento.Inviato
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
        _stato.value = StatoVerdetti()
    }
}
