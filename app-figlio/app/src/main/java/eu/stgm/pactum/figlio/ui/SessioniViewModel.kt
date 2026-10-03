package eu.stgm.pactum.figlio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.dati.PattoLocale
import eu.stgm.pactum.figlio.faccende.ControlloBlocco
import eu.stgm.pactum.figlio.rete.PostinoClient
import eu.stgm.pactum.figlio.sessione.ConsegnaSessioni
import eu.stgm.pactum.figlio.sessione.EsitiSessioni
import eu.stgm.pactum.figlio.sessione.EsitoAvvio
import eu.stgm.pactum.figlio.sessione.EsitoSessione
import eu.stgm.pactum.figlio.sessione.LetturaSessioni
import eu.stgm.pactum.figlio.sessione.SessioneDefinita
import eu.stgm.pactum.figlio.sessione.SessioneIn
import eu.stgm.pactum.figlio.sessione.SessioneModificaIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * (0.11) Le Sessioni (contratto v3.5): l'elenco dal server (le `sessioni` di
 * GET /api/patto, oppure GET /api/sessioni), la creazione e il cambio (li
 * approva il genitore), l'eliminazione, "Inizia" e "Termina la sessione".
 * Senza rete l'elenco è quello dell'ultima copia del patto, con la sua età.
 */
class SessioniViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface Evento {
        /** Mandata al genitore: nuova, o un cambio ([cambio] = era già approvata). */
        data class Mandata(val cambio: Boolean) : Evento

        data object Eliminata : Evento

        /** Un'eliminazione che non è andata: si dice in una riga sotto. */
        data class NonEliminata(val esito: EsitoSessione) : Evento

        /** Il cambio in attesa è stato ritirato: resta la sessione approvata. */
        data object CambioRitirato : Evento

        data class CambioNonRitirato(val esito: EsitoSessione) : Evento

        /**
         * "Inizia" ha chiuso la finestra: iniziata, già in corso (con la sua
         * fine vera), o non si sa ancora (si controlla appena c'è rete).
         */
        data class Iniziata(val esito: EsitoAvvio) : Evento
    }

    data class StatoSessioni(
        val caricamento: Boolean = true,
        val letto: Boolean = false,
        val configurazioneMancante: Boolean = false,
        /** Il server non conosce le sessioni (prima della v3.5): va aggiornato. */
        val serverDaAggiornare: Boolean = false,
        /** Il server non ha risposto: si mostra l'ultima copia, con la sua età. */
        val datiFermi: Boolean = false,
        val datiFermiAlle: Long? = null,
        /** 401: questo telefono non è più collegato al patto. */
        val scollegato: Boolean = false,
        val sessioni: List<SessioneDefinita> = emptyList(),
        val invioInCorso: Boolean = false,
        /** Com'è andato l'ultimo invio dal modulo (nuova o cambia), se non è arrivato: si dice lì dentro. */
        val esitoModulo: EsitoSessione? = null,
        /** Com'è andato l'ultimo "Inizia", se non è iniziata: si dice nella finestra di avvio. */
        val esitoAvvio: EsitoAvvio? = null,
        val evento: Evento? = null,
    )

    private val _stato = MutableStateFlow(StatoSessioni())
    val stato: StateFlow<StatoSessioni> = _stato.asStateFlow()

    fun aggiorna() {
        _stato.update { it.copy(caricamento = true) }
        viewModelScope.launch {
            val context = getApplication<Application>()
            val configurazione = Impostazioni(context).leggiConfigurazione()
            if (!configurazione.completa) {
                _stato.value = StatoSessioni(caricamento = false, letto = true, configurazioneMancante = true)
                return@launch
            }
            val postino = PostinoClient(configurazione)
            val locale = PattoLocale(context)
            val (patto, codice) = postino.leggiPattoConCodice()
            if (patto != null) {
                // Anche le sessioni svolte e quella in corso (ArchivioSessioni).
                locale.salva(patto)
                if (patto.conosceSessioni) {
                    mostra(patto.sessioni)
                    return@launch
                }
                // Il patto non porta le sessioni: le chiede a parte, e un 404 o
                // un 405 vogliono dire che il server va aggiornato.
                val (corpo, codiceElenco) = postino.leggiSessioni()
                val elenco = LetturaSessioni.elenco(corpo)
                when {
                    elenco != null -> mostra(elenco)
                    EsitiSessioni.elencoDaServerVecchio(codiceElenco) -> _stato.update {
                        it.copy(
                            caricamento = false,
                            letto = true,
                            configurazioneMancante = false,
                            serverDaAggiornare = true,
                            datiFermi = false,
                            scollegato = false,
                            sessioni = emptyList(),
                        )
                    }
                    else -> mostraCopia(codiceElenco)
                }
                return@launch
            }
            mostraCopia(codice)
        }
    }

    private fun mostra(sessioni: List<SessioneDefinita>) {
        _stato.update {
            it.copy(
                caricamento = false,
                letto = true,
                configurazioneMancante = false,
                serverDaAggiornare = false,
                datiFermi = false,
                datiFermiAlle = null,
                scollegato = false,
                sessioni = sessioni,
            )
        }
    }

    /** Senza risposta: le sessioni dell'ultima copia del patto, sotto "Dati non aggiornati". */
    private suspend fun mostraCopia(codice: Int) {
        val locale = PattoLocale(getApplication())
        val copia = locale.leggi()
        val alle = locale.aggiornatoIl()
        _stato.update {
            it.copy(
                caricamento = false,
                letto = true,
                configurazioneMancante = false,
                datiFermi = true,
                datiFermiAlle = alle,
                scollegato = codice == 401,
                sessioni = copia?.takeIf { p -> p.conosceSessioni }?.sessioni ?: it.sessioni,
            )
        }
    }

    /** Una sessione nuova: [nomi] = le etichette leggibili delle app, solo il telefono le sa. */
    fun crea(nome: String, app: List<String>, nomi: Map<String, String>) =
        manda(cambio = false) { it.creaSessione(SessioneIn(nome = nome, app = app, nomi = nomi.ifEmpty { null })) }

    /**
     * Il cambio di una sessione: se era approvata nasce un cambio in attesa del
     * genitore e quella approvata resta usabile ([eraApprovata] serve solo a
     * dirlo); se era in attesa o non approvata, torna in attesa così com'è.
     */
    fun modifica(sessioneId: Long, eraApprovata: Boolean, nome: String, app: List<String>, nomi: Map<String, String>) =
        manda(cambio = eraApprovata) {
            it.modificaSessione(sessioneId, SessioneModificaIn(nome = nome, app = app, nomi = nomi.ifEmpty { null }))
        }

    private fun manda(cambio: Boolean, operazione: suspend (PostinoClient) -> PostinoClient.RispostaHttp) {
        _stato.update { it.copy(invioInCorso = true, esitoModulo = null) }
        viewModelScope.launch {
            val postino = PostinoClient(Impostazioni(getApplication()).leggiConfigurazione())
            val risposta = operazione(postino)
            val esito = EsitiSessioni.sessione(risposta.ok, risposta.codice, risposta.corpo)
            _stato.update {
                it.copy(
                    invioInCorso = false,
                    esitoModulo = esito.takeUnless { e -> e is EsitoSessione.Fatta },
                    evento = if (esito is EsitoSessione.Fatta) Evento.Mandata(cambio) else it.evento,
                    serverDaAggiornare = it.serverDaAggiornare || esito == EsitoSessione.ServerDaAggiornare,
                )
            }
            if (esito is EsitoSessione.Fatta || esito == EsitoSessione.NonTrovata) aggiorna()
        }
    }

    fun elimina(sessioneId: Long) {
        _stato.update { it.copy(invioInCorso = true) }
        viewModelScope.launch {
            val postino = PostinoClient(Impostazioni(getApplication()).leggiConfigurazione())
            val risposta = postino.eliminaSessione(sessioneId)
            val esito = EsitiSessioni.sessione(risposta.ok, risposta.codice, risposta.corpo)
            _stato.update {
                it.copy(
                    invioInCorso = false,
                    evento = if (esito is EsitoSessione.Fatta) Evento.Eliminata else Evento.NonEliminata(esito),
                )
            }
            if (esito is EsitoSessione.Fatta || esito == EsitoSessione.NonTrovata) aggiorna()
        }
    }

    /**
     * "Ritira il cambio": la sessione torna com'è approvata (contratto v3.5: un
     * PATCH col contenuto approvato ritira il cambio). Si mandano solo il nome e
     * le app approvati: le etichette le rimette il server, le sue.
     */
    fun ritiraCambio(sessione: SessioneDefinita) {
        _stato.update { it.copy(invioInCorso = true) }
        viewModelScope.launch {
            val postino = PostinoClient(Impostazioni(getApplication()).leggiConfigurazione())
            val risposta = postino.modificaSessione(sessione.id, SessioneModificaIn(nome = sessione.nome, app = sessione.app))
            val esito = EsitiSessioni.sessione(risposta.ok, risposta.codice, risposta.corpo)
            _stato.update {
                it.copy(
                    invioInCorso = false,
                    evento = if (esito is EsitoSessione.Fatta) Evento.CambioRitirato else Evento.CambioNonRitirato(esito),
                )
            }
            if (esito is EsitoSessione.Fatta || esito == EsitoSessione.NonTrovata) aggiorna()
        }
    }

    /**
     * "Inizia" per [durataMinuti]: serve il server. Iniziata, già in corso o
     * incerta: la finestra si chiude e lo si dice. Gli altri no si dicono
     * dentro la finestra di avvio.
     */
    fun avvia(sessioneId: Long, nome: String, durataMinuti: Int) {
        _stato.update { it.copy(invioInCorso = true, esitoAvvio = null) }
        viewModelScope.launch {
            val esito = ConsegnaSessioni.avvia(getApplication(), sessioneId, nome, durataMinuti)
            val chiude = esito is EsitoAvvio.Avviata || esito == EsitoAvvio.Incerto ||
                (esito is EsitoAvvio.GiaInCorso && esito.svolta != null)
            _stato.update {
                it.copy(
                    invioInCorso = false,
                    esitoAvvio = esito.takeUnless { chiude },
                    evento = if (chiude) Evento.Iniziata(esito) else it.evento,
                    serverDaAggiornare = it.serverDaAggiornare || esito == EsitoAvvio.ServerDaAggiornare,
                )
            }
            if (esito == EsitoAvvio.NonApprovata || esito == EsitoAvvio.NonTrovata) aggiorna()
            // (0.13) Il server dice che il telefono è bloccato dalle faccende: il blocco si rilegge subito.
            if (esito == EsitoAvvio.BloccoFaccende) runCatching { ControlloBlocco.interroga(getApplication()) }
        }
    }

    /** Il modulo o la finestra di avvio si sono chiusi: il loro esito non si dice più. */
    fun dimenticaEsiti() {
        _stato.update { it.copy(esitoModulo = null, esitoAvvio = null) }
    }

    fun consumaEvento() {
        _stato.update { it.copy(evento = null) }
    }
}
