package eu.stgm.pactum.genitore.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.Impostazioni
import eu.stgm.pactum.genitore.rete.PostinoClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

class FinestraViewModel(application: Application) : AndroidViewModel(application) {

    data class StatoFinestra(
        val caricamento: Boolean = true,
        val finestra: Finestra? = null,
        val configurazioneMancante: Boolean = false,
        val errore: Boolean = false,
        /** Quando la finestra mostrata è arrivata: su errore resta quella vecchia. */
        val ricevutaAlle: Instant? = null,
    )

    private val _stato = MutableStateFlow(StatoFinestra())
    val stato: StateFlow<StatoFinestra> = _stato.asStateFlow()

    fun aggiorna() {
        _stato.value = _stato.value.copy(caricamento = true)
        viewModelScope.launch {
            val impostazioni = Impostazioni(getApplication())
            val configurazione = impostazioni.leggiConfigurazione()
            if (!configurazione.completa) {
                _stato.value = StatoFinestra(caricamento = false, configurazioneMancante = true)
                return@launch
            }
            val finestra = PostinoClient(configurazione).leggiFinestra()
            if (finestra == null) {
                // L'ultima finestra buona resta visibile sotto l'avviso di errore.
                _stato.value = _stato.value.copy(caricamento = false, errore = true)
            } else {
                impostazioni.registraVerificaRiuscita()
                _stato.value = StatoFinestra(
                    caricamento = false,
                    finestra = finestra,
                    ricevutaAlle = Instant.now(),
                )
            }
        }
    }
}
