package eu.stgm.pactum.figlio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.pactum.figlio.dati.EsitiRisposta
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.dati.PattoLocale
import eu.stgm.pactum.figlio.dati.Proposta
import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.RispostaPropostaIn
import eu.stgm.pactum.figlio.dati.StatiProposta
import eu.stgm.pactum.figlio.dati.leggiDettaglioErrore
import eu.stgm.pactum.figlio.rete.PostinoClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Le proposte del genitore viste dal figlio: il CONFRONTO calcolato dal server
 * in evidenza, accetta o rifiuta con motivazione opzionale. L'accettazione
 * APPLICA da sola la modifica lato server (contratto-api.md): dopo, si
 * risincronizza il patto locale così regole e sentinella sono già aggiornate.
 */
class ProposteViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface Evento {
        data object Accettata : Evento
        data object Rifiutata : Evento
        data object NonPiuPendente : Evento
        data object Errore : Evento
    }

    data class StatoProposte(
        val caricamento: Boolean = true,
        val proposte: List<Proposta> = emptyList(),
        /**
         * Le regole attive del patto: il confronto dice di quanto cambia, la
         * regola dice COSA. Senza, la card non saprebbe dire "TikTok".
         */
        val regole: List<Regola> = emptyList(),
        val configurazioneMancante: Boolean = false,
        val errore: Boolean = false,
        /** Quando è arrivata la lista che si sta mostrando: l'età dei dati. */
        val aggiornateIl: Long? = null,
        val invioInCorso: Boolean = false,
        val evento: Evento? = null,
    ) {
        /** Quante aspettano una risposta: il badge sulla scheda. */
        val pendenti: Int get() = proposte.count { it.stato == StatiProposta.PENDENTE }
    }

    private val _stato = MutableStateFlow(StatoProposte())
    val stato: StateFlow<StatoProposte> = _stato.asStateFlow()

    fun aggiorna() {
        _stato.value = _stato.value.copy(caricamento = true)
        viewModelScope.launch {
            val configurazione = Impostazioni(getApplication()).leggiConfigurazione()
            if (!configurazione.completa) {
                _stato.value = StatoProposte(caricamento = false, configurazioneMancante = true)
                return@launch
            }
            val postino = PostinoClient(configurazione)
            val proposte = postino.leggiProposte()
            if (proposte == null) {
                _stato.value = _stato.value.copy(caricamento = false, errore = true)
                return@launch
            }
            // Le regole fresche dal server: il confronto delle pendenti è
            // ricalcolato sulla regola di ADESSO, e "Ora: …" deve dire la
            // stessa cosa. Senza rete, l'ultima copia locale.
            val locale = PattoLocale(getApplication())
            val patto = postino.leggiPatto()?.also { locale.salva(it) } ?: locale.leggi()
            _stato.value = _stato.value.copy(
                caricamento = false,
                configurazioneMancante = false,
                errore = false,
                aggiornateIl = System.currentTimeMillis(),
                proposte = proposte,
                regole = patto?.regole ?: _stato.value.regole,
            )
        }
    }

    fun rispondi(propostaId: Long, esito: String, motivazione: String?) {
        _stato.value = _stato.value.copy(invioInCorso = true)
        viewModelScope.launch {
            val configurazione = Impostazioni(getApplication()).leggiConfigurazione()
            val postino = PostinoClient(configurazione)
            val risposta = postino.rispondiProposta(
                propostaId,
                RispostaPropostaIn(esito = esito, motivazione = motivazione?.ifBlank { null }),
            )
            val evento = if (risposta.ok) {
                if (esito == EsitiRisposta.ACCETTA) Evento.Accettata else Evento.Rifiutata
            } else if (leggiDettaglioErrore(risposta.corpo)?.errore == "proposta_non_pendente") {
                Evento.NonPiuPendente
            } else {
                Evento.Errore
            }
            if (risposta.ok && esito == EsitiRisposta.ACCETTA) {
                // La regola è già cambiata sul server: la copia locale deve seguire.
                postino.leggiPatto()?.let { PattoLocale(getApplication()).salva(it) }
            }
            _stato.value = _stato.value.copy(invioInCorso = false, evento = evento)
            if (risposta.ok || evento == Evento.NonPiuPendente) aggiorna()
        }
    }

    fun consumaEvento() {
        _stato.value = _stato.value.copy(evento = null)
    }
}
