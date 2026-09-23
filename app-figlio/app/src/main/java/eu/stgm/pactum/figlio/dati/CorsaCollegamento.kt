package eu.stgm.pactum.figlio.dati

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Come è finito un collegamento (Collegamento), per la schermata. */
sealed interface EsitoCollegamento {
    /** Indirizzo del server scritto male: lo si dice sul campo, senza chiamare nessuno. */
    data object IndirizzoNonValido : EsitoCollegamento

    /**
     * Il codice di 6 cifre: la risposta del server. Con [cambio], collegato sì,
     * ma a un dispositivo DIVERSO da quello di prima (Abbinamento.cambioDispositivo).
     */
    data class ConCodice(
        val esito: EsitoAbbinamento,
        val cambio: CambioDispositivo? = null,
    ) : EsitoCollegamento

    /** Il vecchio codice lungo, salvato. */
    data object CodiceLungoSalvato : EsitoCollegamento
}

/**
 * Un collegamento alla volta, nell'[ambito] del processo e non della schermata
 * che lo avvia: se la schermata se ne va (rotazione, pausa, uscita dalle
 * Impostazioni) l'operazione continua, e il suo esito resta in [stato] finché
 * una schermata non lo prende ([consuma]), anche se è stata ricreata nel
 * frattempo. Niente Android: si prova con JUnit semplice.
 */
class CorsaCollegamento(private val ambito: CoroutineScope) {

    /** A che punto è il collegamento, per la schermata (anche ricreata). */
    sealed interface Stato {
        data object Fermo : Stato
        data object InCorso : Stato

        /** Finito: resta qui finché una schermata non lo mostra ([consuma]). */
        data class Finito(val numero: Long, val esito: EsitoCollegamento) : Stato
    }

    private val _stato = MutableStateFlow<Stato>(Stato.Fermo)
    val stato: StateFlow<Stato> = _stato.asStateFlow()
    private var ultimoNumero = 0L

    /**
     * Fa partire [operazione]. False se un collegamento è già in corso: un
     * secondo tocco non manda un secondo codice. Un esito finito e non ancora
     * preso lo sostituisce il collegamento nuovo.
     */
    fun avvia(operazione: suspend () -> EsitoCollegamento): Boolean {
        val numero = synchronized(this) {
            if (_stato.value == Stato.InCorso) return false
            _stato.value = Stato.InCorso
            ++ultimoNumero
        }
        ambito.launch {
            var esito: EsitoCollegamento = EsitoCollegamento.ConCodice(EsitoAbbinamento.Errore)
            try {
                esito = operazione()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Un imprevisto (disco pieno, …): si dice "riprova", e l'app non cade.
            } finally {
                // Mai appeso a "in corso": il pulsante tornerebbe solo riavviando l'app.
                _stato.value = Stato.Finito(numero, esito)
            }
        }
        return true
    }

    /**
     * La schermata ha mostrato l'esito [finito]: non va detto un'altra volta.
     * Un esito vecchio non tocca un collegamento partito dopo.
     */
    fun consuma(finito: Stato.Finito) {
        _stato.compareAndSet(finito, Stato.Fermo)
    }
}
