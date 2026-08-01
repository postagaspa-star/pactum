package eu.stgm.pactum.figlio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.pactum.figlio.dati.Dichiarazione
import eu.stgm.pactum.figlio.dati.DichiarazioneIn
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.dati.PattoLocale
import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.TipiRegola
import eu.stgm.pactum.figlio.dati.leggiDettaglioErrore
import eu.stgm.pactum.figlio.rete.PostinoClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Il diario delle regole di vita reale: il figlio dichiara com'è andata
 * (successo o fallimento) e vede lo stato delle conferme. Fallimento =
 * creduto sulla parola; successo = in attesa del verdetto (contratto-api.md,
 * sezione Dichiarazioni). Massimo una dichiarazione per regola per giorno:
 * il 409 `gia_dichiarato` lo dice anche quando l'app non lo sa già.
 */
class DichiarazioniViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface Evento {
        data class Inviata(val esito: String) : Evento
        data object GiaDichiarato : Evento
        data object Errore : Evento
    }

    data class StatoDiario(
        val caricamento: Boolean = true,
        val regoleVitaReale: List<Regola> = emptyList(),
        val dichiarazioni: List<Dichiarazione> = emptyList(),
        // Il fuso del patto (GET /api/patto): serve a calcolare "oggi" come il
        // server, non col fuso del telefono (il vincolo "già dichiarato oggi").
        val fuso: String? = null,
        val configurazioneMancante: Boolean = false,
        val errore: Boolean = false,
        val invioInCorso: Boolean = false,
        val evento: Evento? = null,
    )

    private val _stato = MutableStateFlow(StatoDiario())
    val stato: StateFlow<StatoDiario> = _stato.asStateFlow()

    fun aggiorna() {
        _stato.value = _stato.value.copy(caricamento = true)
        viewModelScope.launch {
            val configurazione = Impostazioni(getApplication()).leggiConfigurazione()
            if (!configurazione.completa) {
                _stato.value = StatoDiario(caricamento = false, configurazioneMancante = true)
                return@launch
            }
            val postino = PostinoClient(configurazione)
            val patto = postino.leggiPatto()
            val dichiarazioni = postino.leggiDichiarazioni()
            if (patto == null || dichiarazioni == null) {
                val locale = PattoLocale(getApplication()).leggi()
                _stato.value = _stato.value.copy(
                    caricamento = false,
                    configurazioneMancante = false,
                    errore = true,
                    regoleVitaReale = patto?.regole?.filter { it.tipo == TipiRegola.VITA_REALE }
                        ?: locale?.regole?.filter { it.tipo == TipiRegola.VITA_REALE }
                        ?: _stato.value.regoleVitaReale,
                    fuso = patto?.fuso ?: locale?.fuso ?: _stato.value.fuso,
                    dichiarazioni = dichiarazioni ?: _stato.value.dichiarazioni,
                )
                return@launch
            }
            PattoLocale(getApplication()).salva(patto)
            _stato.value = _stato.value.copy(
                caricamento = false,
                configurazioneMancante = false,
                errore = false,
                regoleVitaReale = patto.regole.filter { it.tipo == TipiRegola.VITA_REALE },
                fuso = patto.fuso,
                dichiarazioni = dichiarazioni,
            )
        }
    }

    /**
     * [giorno] è il giorno del patto per cui si dichiara (ISO YYYY-MM-DD): null
     * = oggi (default del server). La UI lo vincola alla finestra oggi ↔ −7gg
     * del contratto, così il 409 `giorno_non_valido` non scatta; un giorno già
     * dichiarato torna come `gia_dichiarato`, gestito come per "oggi".
     */
    fun dichiara(regolaId: Long, esito: String, nota: String?, giorno: String? = null) {
        _stato.value = _stato.value.copy(invioInCorso = true)
        viewModelScope.launch {
            val configurazione = Impostazioni(getApplication()).leggiConfigurazione()
            val risposta = PostinoClient(configurazione).creaDichiarazione(
                DichiarazioneIn(
                    regolaId = regolaId,
                    esito = esito,
                    nota = nota?.ifBlank { null },
                    giorno = giorno,
                ),
            )
            val evento = if (risposta.ok) {
                Evento.Inviata(esito)
            } else if (leggiDettaglioErrore(risposta.corpo)?.errore == "gia_dichiarato") {
                Evento.GiaDichiarato
            } else {
                Evento.Errore
            }
            _stato.value = _stato.value.copy(invioInCorso = false, evento = evento)
            if (risposta.ok) aggiorna()
        }
    }

    fun consumaEvento() {
        _stato.value = _stato.value.copy(evento = null)
    }
}
