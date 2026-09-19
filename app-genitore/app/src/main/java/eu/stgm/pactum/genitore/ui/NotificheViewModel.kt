package eu.stgm.pactum.genitore.ui

import android.app.Application
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.pactum.genitore.dati.Impostazioni
import eu.stgm.pactum.genitore.dati.Notifica
import eu.stgm.pactum.genitore.dati.RegolaFinestra
import eu.stgm.pactum.genitore.rete.PostinoClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class NotificheViewModel(application: Application) : AndroidViewModel(application) {

    data class StatoNotifiche(
        val caricamento: Boolean = true,
        /** Le non lette, dalla più recente alla più vecchia. */
        val notifiche: List<Notifica> = emptyList(),
        /**
         * Le regole della finestra (tutte, anche le eliminate): servono solo a
         * scrivere i testi col nome leggibile dell'app ("TikTok").
         */
        val regolePerId: Map<Long, RegolaFinestra> = emptyMap(),
        val configurazioneMancante: Boolean = false,
        val errore: Boolean = false,
        /**
         * Almeno una lettura dal server è finita (riuscita o no). La rotella a
         * schermo intero è solo per la PRIMA: il badge rilegge ogni minuto, e una
         * lista vuota non deve diventare una rotella a ogni giro.
         */
        val primaLetturaFatta: Boolean = false,
        /** "Segna come letta" è fallita: da dire UNA volta, poi consumare. */
        val lettaFallita: Boolean = false,
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
            val postino = PostinoClient(configurazione)
            val notifiche = postino.leggiNotifiche()
            if (notifiche == null) {
                _stato.value = _stato.value.copy(
                    caricamento = false,
                    errore = true,
                    primaLetturaFatta = true,
                )
                return@launch
            }
            val ordinate = dallaPiuRecente(notifiche)
            val regole = regoleAggiornate(postino, ordinate, _stato.value)
            _stato.value = _stato.value.copy(
                caricamento = false,
                notifiche = ordinate,
                regolePerId = regole,
                configurazioneMancante = false,
                errore = false,
                primaLetturaFatta = true,
            )
        }
    }

    /**
     * Le regole si rileggono dalla finestra solo quando servono davvero: è
     * arrivata una notifica nuova (una modifica può aver cambiato la regola) o
     * una notifica cita una regola che non si conosce ancora. Il badge si
     * riconta ogni minuto: senza novità non si scarica la finestra ogni volta.
     * Se la finestra non arriva, si tengono le regole di prima e i testi che
     * non si possono scrivere ripiegano sul messaggio del server.
     */
    private suspend fun regoleAggiornate(
        postino: PostinoClient,
        notifiche: List<Notifica>,
        prima: StatoNotifiche,
    ): Map<Long, RegolaFinestra> {
        val citate = notifiche.mapNotNull(::regolaIdNotifica)
        if (citate.isEmpty()) return prima.regolePerId
        val giaViste = prima.notifiche.map { it.id }.toSet()
        val novita = notifiche.any { it.id !in giaViste } ||
            citate.any { it !in prima.regolePerId }
        if (!novita) return prima.regolePerId
        return postino.leggiFinestra()?.regole?.associateBy { it.id } ?: prima.regolePerId
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
                _stato.value = _stato.value.copy(lettaFallita = true)
            }
        }
    }

    fun consumaLettaFallita() {
        _stato.value = _stato.value.copy(lettaFallita = false)
    }
}
