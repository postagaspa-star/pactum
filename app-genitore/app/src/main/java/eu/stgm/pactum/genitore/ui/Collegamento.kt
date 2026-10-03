package eu.stgm.pactum.genitore.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.pactum.genitore.BuildConfig
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.ConfigurazionePostino
import eu.stgm.pactum.genitore.dati.Impostazioni
import eu.stgm.pactum.genitore.dati.RiferimentoGenitore
import eu.stgm.pactum.genitore.rete.EsitoAbbinamento
import eu.stgm.pactum.genitore.rete.PostinoClient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// (0.13) Il collegamento di questo telefono con il codice di 6 cifre (contratto
// v3.6, POST /api/abbina col tipo "genitore").
//
// Il server dà il token UNA volta sola, e consuma il codice. Quindi abbinamento e
// salvataggio sono un blocco solo che non si interrompe ([collegaESalva]): se la
// pagina si chiude a metà (rotazione, cambio di scheda, cambio di tema, l'app
// chiusa), il token arrivato si salva comunque. Altrimenti il genitore, riprovando,
// si sentirebbe dire "codice già usato" senza essere collegato. Lo stato (in corso,
// com'è andata) vive nel ViewModel, non nella pagina.

/**
 * L'abbinamento ([abbina]) e, se è riuscito, il salvataggio del token ([salva]),
 * in un blocco che chi chiama non può interrompere a metà. Se l'abbinamento non
 * riesce non si salva niente: il collegamento di prima (anche il codice d'accesso
 * lungo) resta com'era finché quello nuovo non è riuscito.
 */
suspend fun collegaESalva(
    abbina: suspend () -> EsitoAbbinamento,
    salva: suspend (token: String) -> Unit,
): EsitoAbbinamento = withContext(NonCancellable) {
    val esito = abbina()
    if (esito is EsitoAbbinamento.Collegato) salva(esito.token)
    esito
}

/**
 * La domanda prima di collegare con un codice di 6 cifre un telefono che è già
 * collegato ([configurato]): "Questo telefono è «Papà»: vuoi collegarlo come un
 * altro genitore? …". null = non c'è niente da chiedere (non è collegato).
 */
fun domandaPrimaDiCollegare(parole: Parole, configurato: Boolean, io: RiferimentoGenitore?): String? {
    if (!configurato) return null
    val nome = io?.nome?.trim()?.takeIf { it.isNotEmpty() }
    return nome?.let { parole.testo(R.string.connessione_conferma_testo, it) }
        ?: parole.testo(R.string.connessione_conferma_testo_senza_nome)
}

/**
 * Il collegamento col codice di 6 cifre, nello scope dell'attività: sopravvive alla
 * pagina delle Impostazioni (rotazione, cambio di scheda). E il punto dove si
 * accorge che il collegamento è cambiato, per far dimenticare a tutte le
 * schermate quello che sapevano del collegamento di prima ([eUnAltroCollegamento]).
 */
class CollegamentoViewModel(application: Application) : AndroidViewModel(application) {

    data class StatoCollegamento(
        /** Un collegamento è in volo: il pulsante si spegne. */
        val inCorso: Boolean = false,
        /** Com'è andato l'ultimo, da dire una volta e poi consumare. */
        val esito: EsitoAbbinamento? = null,
    )

    private val _stato = MutableStateFlow(StatoCollegamento())
    val stato: StateFlow<StatoCollegamento> = _stato.asStateFlow()

    /** L'ultimo collegamento visto (sopravvive alla rotazione, come i ViewModel da far dimenticare). */
    private var configurazioneNota: ConfigurazionePostino? = null

    /** Collega con [codice] il server [serverUrl] (già normalizzato), e salva. Uno alla volta. */
    fun collega(serverUrl: String, codice: String) {
        if (_stato.value.inCorso) return
        _stato.value = _stato.value.copy(inCorso = true)
        val impostazioni = Impostazioni(getApplication())
        viewModelScope.launch {
            try {
                val esito = collegaESalva(
                    abbina = { PostinoClient.abbinaGenitore(serverUrl, codice, BuildConfig.VERSION_NAME) },
                    salva = { token -> impostazioni.salvaConfigurazione(serverUrl, token) },
                )
                _stato.value = _stato.value.copy(esito = esito)
            } finally {
                _stato.value = _stato.value.copy(inCorso = false)
            }
        }
    }

    fun consumaEsito() {
        _stato.value = _stato.value.copy(esito = null)
    }

    /**
     * true = [nuova] è un collegamento diverso da quello visto prima: le schermate
     * dimenticano quello che sapevano. La prima volta (app appena aperta) no.
     */
    fun eUnAltroCollegamento(nuova: ConfigurazionePostino): Boolean {
        val cambiata = configurazioneCambiata(configurazioneNota, nuova)
        configurazioneNota = nuova
        return cambiata
    }
}
