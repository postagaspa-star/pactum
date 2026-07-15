package eu.stgm.pactum.genitore.ui

import android.app.Application
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.pactum.genitore.dati.Impostazioni
import eu.stgm.pactum.genitore.dati.Notifica
import eu.stgm.pactum.genitore.rete.PostinoClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class NotificheViewModel(application: Application) : AndroidViewModel(application) {

    data class StatoNotifiche(
        val caricamento: Boolean = true,
        val notifiche: List<Notifica> = emptyList(),
        val configurazioneMancante: Boolean = false,
        val errore: Boolean = false,
        /** Contatore di fallimenti di "segna come letta": ogni scatto = uno snackbar. */
        val lettaFallita: Int = 0,
    )

    private val _stato = MutableStateFlow(StatoNotifiche())
    val stato: StateFlow<StatoNotifiche> = _stato.asStateFlow()

    fun aggiorna() {
        _stato.value = _stato.value.copy(caricamento = true)
        viewModelScope.launch {
            val impostazioni = Impostazioni(getApplication())
            val configurazione = impostazioni.leggiConfigurazione()
            if (!configurazione.completa) {
                _stato.value = StatoNotifiche(caricamento = false, configurazioneMancante = true)
                return@launch
            }
            val notifiche = PostinoClient(configurazione).leggiNotifiche()
            _stato.value = if (notifiche == null) {
                _stato.value.copy(caricamento = false, errore = true)
            } else {
                _stato.value.copy(
                    caricamento = false,
                    notifiche = notifiche,
                    configurazioneMancante = false,
                    errore = false,
                )
            }
        }
    }

    fun segnaLetta(notifica: Notifica) {
        viewModelScope.launch {
            val configurazione = Impostazioni(getApplication()).leggiConfigurazione()
            val riuscita = PostinoClient(configurazione).segnaLetta(notifica.id)
            if (riuscita) {
                // Via anche l'eventuale notifica di sistema gemella: letta è letta.
                NotificationManagerCompat.from(getApplication()).cancel(notifica.id.toInt())
                _stato.value = _stato.value.copy(
                    notifiche = _stato.value.notifiche.filterNot { it.id == notifica.id },
                )
            } else {
                _stato.value = _stato.value.copy(lettaFallita = _stato.value.lettaFallita + 1)
            }
        }
    }
}
