package eu.stgm.pactum.figlio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.dati.PattoLocale
import eu.stgm.pactum.figlio.dati.SitiGiorno
import eu.stgm.pactum.figlio.rete.PostinoClient
import eu.stgm.pactum.figlio.siti.OsservazioneSiti
import eu.stgm.pactum.figlio.siti.RegistroSiti
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * I siti visitati visti dal FIGLIO. La lista arriva da `GET /api/patto`
 * (campo `siti_recenti`), che per contratto è **identica** a quella della
 * finestra del genitore: è il principio della tavola rotonda, e per rispettarlo
 * l'app mostra quella lista lì, senza ricalcolarla per conto suo.
 *
 * Offline si mostra l'ultima copia locale del patto, marcata come non
 * aggiornata (stesso schema delle altre schermate). Il conteggio "di oggi sul
 * telefono" viene invece dal registro locale: è materiale ancora da
 * consegnare, dichiarato come tale — il figlio vede sempre almeno quanto il
 * genitore, mai meno.
 */
class SitiViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface Evento {
        data object Attivata : Evento
        data object Disattivata : Evento
        data object ConsensoNegato : Evento
    }

    data class StatoSiti(
        val caricamento: Boolean = true,
        val giorni: List<SitiGiorno> = emptyList(),
        val configurazioneMancante: Boolean = false,
        val datiVecchi: Boolean = false,
        val osservazioneAttiva: Boolean = false,
        val dominiOggi: Int = 0,
        val evento: Evento? = null,
    )

    private val _stato = MutableStateFlow(StatoSiti())
    val stato: StateFlow<StatoSiti> = _stato.asStateFlow()

    fun aggiorna() {
        _stato.value = _stato.value.copy(caricamento = true)
        viewModelScope.launch {
            val context = getApplication<Application>()
            // Se il figlio l'aveva accesa e il servizio è morto per strada
            // (processo ucciso, memoria), aprire questa schermata lo rimette
            // in piedi: qui siamo in primo piano, il sistema ce lo lascia fare.
            OsservazioneSiti.riprendiSeConsentita(context)
            val attiva = OsservazioneSiti.attivaOra(context)
            val dominiOggi = RegistroSiti.dominiDiOggi(context)
            val configurazione = Impostazioni(context).leggiConfigurazione()
            val locale = PattoLocale(context).leggi()

            if (!configurazione.completa) {
                _stato.value = _stato.value.copy(
                    caricamento = false,
                    configurazioneMancante = true,
                    datiVecchi = false,
                    giorni = locale?.sitiRecenti.orEmpty(),
                    osservazioneAttiva = attiva,
                    dominiOggi = dominiOggi,
                )
                return@launch
            }

            val patto = PostinoClient(configurazione).leggiPatto()
            if (patto != null) PattoLocale(context).salva(patto)
            _stato.value = _stato.value.copy(
                caricamento = false,
                configurazioneMancante = false,
                datiVecchi = patto == null,
                giorni = (patto ?: locale)?.sitiRecenti.orEmpty(),
                osservazioneAttiva = attiva,
                dominiOggi = dominiOggi,
            )
        }
    }

    /** Il consenso VPN di sistema è arrivato: si accende e si registra la scelta. */
    fun accendi() {
        viewModelScope.launch {
            OsservazioneSiti.accendi(getApplication())
            _stato.value = _stato.value.copy(evento = Evento.Attivata)
            aggiorna()
        }
    }

    fun consensoNegato() {
        _stato.value = _stato.value.copy(evento = Evento.ConsensoNegato)
    }

    fun spegni() {
        viewModelScope.launch {
            OsservazioneSiti.spegniDaApp(getApplication())
            _stato.value = _stato.value.copy(evento = Evento.Disattivata)
            aggiorna()
        }
    }

    fun consumaEvento() {
        _stato.value = _stato.value.copy(evento = null)
    }
}
