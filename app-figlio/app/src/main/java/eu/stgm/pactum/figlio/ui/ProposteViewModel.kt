package eu.stgm.pactum.figlio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.pactum.figlio.dati.ContestoDispositivi
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
        /** [regolaId]: la regola accettata, per dire "la regola sul computer è già aggiornata". */
        data class Accettata(val regolaId: Long) : Evento
        data object Rifiutata : Evento
        data object NonPiuPendente : Evento
        data object Errore : Evento
    }

    data class StatoProposte(
        val caricamento: Boolean = true,
        /** (v3) Tutte le proposte del figlio, anche sulle regole del computer. */
        val proposte: List<Proposta> = emptyList(),
        /**
         * Le regole attive del figlio, su TUTTI i suoi dispositivi (v3): il
         * confronto dice di quanto cambia, la regola dice COSA e dove. Senza,
         * la card non saprebbe dire "TikTok" né "sul computer".
         */
        val regole: List<Regola> = emptyList(),
        /** (v3) Questo telefono tra i dispositivi del figlio: per dire "sul computer". */
        val contesto: ContestoDispositivi = ContestoDispositivi(),
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
            // (v3) Una proposta può essere su una regola del computer: il patto di
            // questo telefono non la contiene, GET /api/regole (tutto il figlio) sì.
            val delFiglio = postino.leggiRegole()
            val regole = (delFiglio.orEmpty() + patto?.regole.orEmpty()).distinctBy { it.id }
            _stato.value = _stato.value.copy(
                caricamento = false,
                configurazioneMancante = false,
                errore = false,
                aggiornateIl = System.currentTimeMillis(),
                proposte = proposte,
                regole = regole.ifEmpty { _stato.value.regole },
                contesto = patto?.contestoDispositivi() ?: _stato.value.contesto,
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
                if (esito == EsitiRisposta.ACCETTA) {
                    Evento.Accettata(_stato.value.proposte.firstOrNull { it.id == propostaId }?.regolaId ?: 0)
                } else {
                    Evento.Rifiutata
                }
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
