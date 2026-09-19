package eu.stgm.pactum.genitore.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.Impostazioni
import eu.stgm.pactum.genitore.rete.EsitoScrittura
import eu.stgm.pactum.genitore.rete.PostinoClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

class FinestraViewModel(application: Application) : AndroidViewModel(application) {

    /** L'esito del "Manda un segno", da dire una volta e poi consumare. */
    enum class EsitoSegno { MANDATO, GIA_MANDATO, FALLITO }

    data class StatoFinestra(
        val caricamento: Boolean = true,
        val finestra: Finestra? = null,
        val configurazioneMancante: Boolean = false,
        val errore: Boolean = false,
        /** Quando la finestra mostrata è arrivata: su errore resta quella vecchia. */
        val ricevutaAlle: Instant? = null,
        val invioSegno: Boolean = false,
        /** Il giorno (del telefono) in cui il segno è partito da qui. */
        val segnoMandatoIl: LocalDate? = null,
        val esitoSegno: EsitoSegno? = null,
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
                // copy e non uno stato nuovo: un segno in volo non va dimenticato.
                _stato.value = _stato.value.copy(
                    caricamento = false,
                    finestra = finestra,
                    configurazioneMancante = false,
                    errore = false,
                    ricevutaAlle = Instant.now(),
                )
            }
        }
    }

    /**
     * Il riconoscimento al figlio (POST /api/segno): testo fisso, uno al giorno.
     * Un 409 vuol dire che oggi è già partito: si spegne il pulsante e basta.
     */
    fun mandaSegno() {
        if (_stato.value.invioSegno) return
        _stato.value = _stato.value.copy(invioSegno = true)
        viewModelScope.launch {
            val configurazione = Impostazioni(getApplication()).leggiConfigurazione()
            val esito = when (val risposta = PostinoClient(configurazione).mandaSegno()) {
                is EsitoScrittura.Riuscito -> EsitoSegno.MANDATO
                // Il 422 non è "già mandato": è una risposta che non ci aspettiamo.
                is EsitoScrittura.Rifiutato ->
                    if (risposta.errore == PostinoClient.PARAMETRI_NON_VALIDI) {
                        EsitoSegno.FALLITO
                    } else {
                        EsitoSegno.GIA_MANDATO
                    }
                EsitoScrittura.Fallito -> EsitoSegno.FALLITO
            }
            _stato.value = _stato.value.copy(
                invioSegno = false,
                segnoMandatoIl = if (esito == EsitoSegno.FALLITO) {
                    _stato.value.segnoMandatoIl
                } else {
                    LocalDate.now()
                },
                esitoSegno = esito,
            )
            if (esito == EsitoSegno.MANDATO) aggiorna()
        }
    }

    fun consumaEsitoSegno() {
        _stato.value = _stato.value.copy(esitoSegno = null)
    }
}
