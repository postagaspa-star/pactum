package eu.stgm.pactum.figlio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.pactum.figlio.dati.BonusIn
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.dati.PattoLocale
import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.StatoBonus
import eu.stgm.pactum.figlio.dati.TipiRegola
import eu.stgm.pactum.figlio.dati.leggiDettaglioErrore
import eu.stgm.pactum.figlio.rete.PostinoClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Il bonus time: +5/15/30 minuti su una regola limite_tempo ATTIVA specifica
 * (contratto-api.md, POST /api/bonus — i tetti li applica il SERVER). Dopo un
 * bonus si risincronizza il patto locale: il limite efficace di oggi cambia e
 * la sentinella deve saperlo subito, non al prossimo giro del worker.
 */
class BonusViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface Evento {
        data class Concesso(val minuti: Int) : Evento
        data class TettoSuperato(val residuoGiorno: Int, val residuoSettimana: Int) : Evento
        data object RegolaNonValida : Evento
        data object Errore : Evento
    }

    data class StatoSchermata(
        val caricamento: Boolean = true,
        val regoleLimite: List<Regola> = emptyList(),
        val bonus: StatoBonus? = null,
        /** Minuti bonus concessi OGGI per regola (chiave = id come stringa). */
        val bonusOggiPerRegola: Map<String, Int> = emptyMap(),
        val configurazioneMancante: Boolean = false,
        val errore: Boolean = false,
        val invioInCorso: Boolean = false,
        val evento: Evento? = null,
    )

    private val _stato = MutableStateFlow(StatoSchermata())
    val stato: StateFlow<StatoSchermata> = _stato.asStateFlow()

    fun aggiorna() {
        _stato.value = _stato.value.copy(caricamento = true)
        viewModelScope.launch {
            val impostazioni = Impostazioni(getApplication())
            val configurazione = impostazioni.leggiConfigurazione()
            if (!configurazione.completa) {
                _stato.value = StatoSchermata(caricamento = false, configurazioneMancante = true)
                return@launch
            }
            val patto = PostinoClient(configurazione).leggiPatto()
            if (patto == null) {
                val locale = PattoLocale(getApplication()).leggi()
                _stato.value = _stato.value.copy(
                    caricamento = false,
                    configurazioneMancante = false,
                    errore = true,
                    regoleLimite = locale?.regole?.filter { it.tipo == TipiRegola.LIMITE_TEMPO }
                        ?: _stato.value.regoleLimite,
                    bonus = locale?.bonus ?: _stato.value.bonus,
                    bonusOggiPerRegola = locale?.bonusOggiPerRegola
                        ?: _stato.value.bonusOggiPerRegola,
                )
                return@launch
            }
            PattoLocale(getApplication()).salva(patto)
            _stato.value = _stato.value.copy(
                caricamento = false,
                configurazioneMancante = false,
                errore = false,
                regoleLimite = patto.regole.filter { it.tipo == TipiRegola.LIMITE_TEMPO },
                bonus = patto.bonus,
                bonusOggiPerRegola = patto.bonusOggiPerRegola,
            )
        }
    }

    fun concedi(regolaId: Long, minuti: Int, motivo: String?) {
        _stato.value = _stato.value.copy(invioInCorso = true)
        viewModelScope.launch {
            val configurazione = Impostazioni(getApplication()).leggiConfigurazione()
            val risposta = PostinoClient(configurazione).inviaBonus(
                BonusIn(minuti = minuti, regolaId = regolaId, motivo = motivo?.ifBlank { null }),
            )
            val evento = if (risposta.ok) {
                Evento.Concesso(minuti)
            } else {
                val dettaglio = leggiDettaglioErrore(risposta.corpo)
                when (dettaglio?.errore) {
                    "tetto_superato" -> Evento.TettoSuperato(
                        residuoGiorno = dettaglio.residuoGiorno ?: 0,
                        residuoSettimana = dettaglio.residuoSettimana ?: 0,
                    )
                    "regola_non_valida" -> Evento.RegolaNonValida
                    else -> Evento.Errore
                }
            }
            _stato.value = _stato.value.copy(invioInCorso = false, evento = evento)
            if (risposta.ok) aggiorna() // residui e limite efficace aggiornati subito
        }
    }

    fun consumaEvento() {
        _stato.value = _stato.value.copy(evento = null)
    }
}
