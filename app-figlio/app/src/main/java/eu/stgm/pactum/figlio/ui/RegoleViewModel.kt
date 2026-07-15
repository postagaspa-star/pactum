package eu.stgm.pactum.figlio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.pactum.figlio.dati.CreaRegolaIn
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.dati.ModificaRegolaIn
import eu.stgm.pactum.figlio.dati.PattoLocale
import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.StatiProposta
import eu.stgm.pactum.figlio.dati.leggiDettaglioErrore
import eu.stgm.pactum.figlio.rete.PostinoClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Le regole del patto: lista dal server (fonte di verità), creazione, modifica
 * ed eliminazione. Il lock asimmetrico dei 4 giorni lo applica il SERVER:
 * l'app mostra il conto alla rovescia leggendo i secondi residui dal 409
 * (contratto-api.md, sezione Regole). La copia locale (PattoLocale) fa da
 * riserva in lettura quando la rete manca.
 */
class RegoleViewModel(application: Application) : AndroidViewModel(application) {

    /** Un esito una-tantum da mostrare, poi consumato. */
    sealed interface Evento {
        data object Salvata : Evento
        data object Eliminata : Evento

        /** Il 409 del lock: [secondiRimanenti] al primo momento buono per allentare. */
        data class LockAttivo(val secondiRimanenti: Long, val perEliminazione: Boolean) : Evento
        data object UltimaRegola : Evento
        data object Errore : Evento
    }

    data class StatoRegole(
        val caricamento: Boolean = true,
        val regole: List<Regola> = emptyList(),
        /** Le regole i cui parametri attuali sono nati da una proposta accettata. */
        val concordate: Set<Long> = emptySet(),
        val configurazioneMancante: Boolean = false,
        val errore: Boolean = false,
        val invioInCorso: Boolean = false,
        val evento: Evento? = null,
    )

    private val _stato = MutableStateFlow(StatoRegole())
    val stato: StateFlow<StatoRegole> = _stato.asStateFlow()

    fun aggiorna() {
        _stato.value = _stato.value.copy(caricamento = true)
        viewModelScope.launch {
            val impostazioni = Impostazioni(getApplication())
            val configurazione = impostazioni.leggiConfigurazione()
            if (!configurazione.completa) {
                _stato.value = StatoRegole(caricamento = false, configurazioneMancante = true)
                return@launch
            }
            val postino = PostinoClient(configurazione)
            val patto = postino.leggiPatto()
            if (patto == null) {
                // Offline o server muto: si mostra la copia locale sotto l'avviso.
                val locale = PattoLocale(getApplication()).leggi()
                _stato.value = _stato.value.copy(
                    caricamento = false,
                    configurazioneMancante = false,
                    errore = true,
                    regole = locale?.regole ?: _stato.value.regole,
                )
                return@launch
            }
            PattoLocale(getApplication()).salva(patto)

            // Badge "concordata": una modifica nata da proposta accettata applica
            // ESATTAMENTE i parametri proposti (contratto) — quindi la regola è
            // concordata se i suoi parametri attuali coincidono con quelli di una
            // proposta accettata e usata. Best effort: senza proposte, nessun badge.
            val proposte = postino.leggiProposte().orEmpty()
            val concordate = patto.regole
                .filter { regola ->
                    proposte.any { proposta ->
                        proposta.stato == StatiProposta.ACCETTATA &&
                            proposta.usata &&
                            proposta.regolaId == regola.id &&
                            proposta.parametriProposti == regola.parametri
                    }
                }
                .map { it.id }
                .toSet()

            _stato.value = _stato.value.copy(
                caricamento = false,
                configurazioneMancante = false,
                errore = false,
                regole = patto.regole,
                concordate = concordate,
            )
        }
    }

    fun crea(tipo: String, parametri: JsonObject) = muta(Evento.Salvata) {
        it.creaRegola(CreaRegolaIn(tipo, parametri))
    }

    fun modifica(regolaId: Long, parametri: JsonObject) = muta(Evento.Salvata) {
        it.modificaRegola(regolaId, ModificaRegolaIn(parametri))
    }

    fun elimina(regolaId: Long) = muta(Evento.Eliminata, perEliminazione = true) {
        it.eliminaRegola(regolaId)
    }

    private fun muta(
        eventoOk: Evento,
        perEliminazione: Boolean = false,
        operazione: suspend (PostinoClient) -> PostinoClient.RispostaHttp,
    ) {
        _stato.value = _stato.value.copy(invioInCorso = true)
        viewModelScope.launch {
            val configurazione = Impostazioni(getApplication()).leggiConfigurazione()
            val risposta = operazione(PostinoClient(configurazione))
            val evento = if (risposta.ok) {
                eventoOk
            } else {
                when (val dettaglio = leggiDettaglioErrore(risposta.corpo)) {
                    null -> Evento.Errore
                    else -> when (dettaglio.errore) {
                        "lock_attivo" -> Evento.LockAttivo(
                            secondiRimanenti = dettaglio.secondiRimanenti ?: 0,
                            perEliminazione = perEliminazione,
                        )
                        "ultima_regola" -> Evento.UltimaRegola
                        else -> Evento.Errore
                    }
                }
            }
            _stato.value = _stato.value.copy(invioInCorso = false, evento = evento)
            if (risposta.ok) aggiorna()
        }
    }

    fun consumaEvento() {
        _stato.value = _stato.value.copy(evento = null)
    }
}
