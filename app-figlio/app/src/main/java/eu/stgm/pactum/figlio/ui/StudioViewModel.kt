package eu.stgm.pactum.figlio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.rete.PostinoClient
import eu.stgm.pactum.figlio.studio.ConsegnaStudio
import eu.stgm.pactum.figlio.studio.ContenutoStudio
import eu.stgm.pactum.figlio.studio.ControlloStudio
import eu.stgm.pactum.figlio.studio.EsitoProposta
import eu.stgm.pactum.figlio.studio.LetturaStudio
import eu.stgm.pactum.figlio.studio.StudioSvolto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * (0.18, contratto v4.0, parte C) La sezione «Sessione Studio» della scheda
 * Sessioni: la configurazione (letta con `GET /api/studio`, tenuta in
 * ArchivioStudio), la proposta e il suo ritiro, l'avvio a mano, lo storico
 * (`GET /api/studio/svolte`) e le versioni approvate (`GET /api/studio/versioni`).
 * Lo Studio in corso, il timer e la chiusura stanno nella card di Oggi.
 */
class StudioViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface Evento {
        data class Proposta(val esito: EsitoProposta, val ritiro: Boolean) : Evento

        /** L'avvio a mano: null = partito; altrimenti il perché no. */
        data class Avvio(val motivo: String?) : Evento
    }

    /** Lo storico, pagina dopo pagina. */
    data class Storico(
        val aperto: Boolean = false,
        val svolte: List<StudioSvolto> = emptyList(),
        val altre: Boolean = false,
        val caricamento: Boolean = false,
        /** Non si è potuto leggere (rete, server vecchio). */
        val errore: Boolean = false,
    )

    data class Versioni(
        val aperte: Boolean = false,
        val versioni: List<ContenutoStudio> = emptyList(),
        val caricamento: Boolean = false,
        val errore: Boolean = false,
    )

    data class StatoStudioUi(
        val caricamento: Boolean = false,
        val invioInCorso: Boolean = false,
        /** L'esito dell'ultima proposta, se non è andata: si dice nel modulo. */
        val esitoModulo: EsitoProposta? = null,
        val storico: Storico = Storico(),
        val versioni: Versioni = Versioni(),
        val evento: Evento? = null,
    )

    private val _stato = MutableStateFlow(StatoStudioUi())
    val stato: StateFlow<StatoStudioUi> = _stato.asStateFlow()

    private val app: Application get() = getApplication()

    fun aggiorna() {
        _stato.update { it.copy(caricamento = true) }
        viewModelScope.launch {
            runCatching { ControlloStudio.interroga(app) }
            _stato.update { it.copy(caricamento = false) }
        }
    }

    fun proponi(nuova: ContenutoStudio) {
        if (_stato.value.invioInCorso) return
        _stato.update { it.copy(invioInCorso = true, esitoModulo = null) }
        viewModelScope.launch {
            val esito = runCatching { ConsegnaStudio.proponi(app, nuova) }.getOrDefault(EsitoProposta.Errore)
            _stato.update {
                it.copy(
                    invioInCorso = false,
                    esitoModulo = esito.takeUnless { e -> e is EsitoProposta.Fatta },
                    evento = if (esito is EsitoProposta.Fatta) Evento.Proposta(esito, ritiro = false) else it.evento,
                )
            }
        }
    }

    fun ritira() {
        if (_stato.value.invioInCorso) return
        _stato.update { it.copy(invioInCorso = true) }
        viewModelScope.launch {
            val esito = runCatching { ConsegnaStudio.ritira(app) }.getOrDefault(EsitoProposta.Errore)
            _stato.update { it.copy(invioInCorso = false, evento = Evento.Proposta(esito, ritiro = true)) }
        }
    }

    fun avviaAMano() {
        if (_stato.value.invioInCorso) return
        _stato.update { it.copy(invioInCorso = true) }
        viewModelScope.launch {
            val motivo = runCatching { ConsegnaStudio.avviaAMano(app) }.getOrDefault("errore")
            _stato.update { it.copy(invioInCorso = false, evento = Evento.Avvio(motivo)) }
        }
    }

    fun apriStorico() {
        _stato.update { it.copy(storico = Storico(aperto = true, caricamento = true)) }
        caricaStorico(null)
    }

    fun altroStorico() {
        val ultima = _stato.value.storico.svolte.lastOrNull()?.id ?: return
        _stato.update { it.copy(storico = it.storico.copy(caricamento = true)) }
        caricaStorico(ultima)
    }

    private fun caricaStorico(primaDi: Long?) {
        viewModelScope.launch {
            val configurazione = Impostazioni(app).leggiConfigurazione()
            val (corpo, _) = if (configurazione.completa) PostinoClient(configurazione).leggiSvolteStudio(primaDi) else (null to 0)
            val pagina = LetturaStudio.pagina(corpo)
            _stato.update { s ->
                if (!s.storico.aperto) return@update s
                if (pagina == null) {
                    s.copy(storico = s.storico.copy(caricamento = false, errore = s.storico.svolte.isEmpty()))
                } else {
                    s.copy(
                        storico = s.storico.copy(
                            svolte = (s.storico.svolte + pagina.svolte).distinctBy { it.id },
                            altre = pagina.altre,
                            caricamento = false,
                            errore = false,
                        ),
                    )
                }
            }
        }
    }

    fun chiudiStorico() {
        _stato.update { it.copy(storico = Storico()) }
    }

    fun apriVersioni() {
        _stato.update { it.copy(versioni = Versioni(aperte = true, caricamento = true)) }
        viewModelScope.launch {
            val configurazione = Impostazioni(app).leggiConfigurazione()
            val (corpo, _) = if (configurazione.completa) PostinoClient(configurazione).leggiVersioniStudio() else (null to 0)
            val versioni = LetturaStudio.versioni(corpo)
            _stato.update { s ->
                if (!s.versioni.aperte) return@update s
                s.copy(versioni = s.versioni.copy(versioni = versioni.orEmpty(), caricamento = false, errore = versioni == null))
            }
        }
    }

    fun chiudiVersioni() {
        _stato.update { it.copy(versioni = Versioni()) }
    }

    fun dimenticaEsiti() {
        _stato.update { it.copy(esitoModulo = null) }
    }

    fun consumaEvento() {
        _stato.update { it.copy(evento = null) }
    }
}
