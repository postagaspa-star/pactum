package eu.stgm.pactum.figlio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.pactum.figlio.dati.CambioRegola
import eu.stgm.pactum.figlio.dati.ContestoDispositivi
import eu.stgm.pactum.figlio.dati.EsitiRisposta
import eu.stgm.pactum.figlio.dati.EsitoProposta
import eu.stgm.pactum.figlio.dati.EsitoRitiro
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.dati.PattoLocale
import eu.stgm.pactum.figlio.dati.Proposta
import eu.stgm.pactum.figlio.dati.ProposteDelFiglio
import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.RegoleAltrove
import eu.stgm.pactum.figlio.dati.RispostaPropostaIn
import eu.stgm.pactum.figlio.dati.leggiDettaglioErrore
import eu.stgm.pactum.figlio.rete.PostinoClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Le proposte del genitore viste dal figlio: il CONFRONTO calcolato dal server
 * in evidenza, accetta o rifiuta con motivazione opzionale. L'accettazione
 * APPLICA da sola la modifica lato server (contratto-api.md): dopo, si
 * risincronizza il patto locale così regole e sentinella sono già aggiornate.
 *
 * (0.10) In più le proposte del figlio (contratto v3.4): quelle che aspettano
 * il genitore (`proposte_inviate`, ciascuna si può ritirare), una nuova da qui
 * ("Nuova proposta") e la storia breve delle chiuse, di tutti e due.
 */
class ProposteViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface Evento {
        /** [regolaId]: la regola accettata, per dire "la regola sul computer è già aggiornata". */
        data class Accettata(val regolaId: Long) : Evento
        data object Rifiutata : Evento
        data object NonPiuPendente : Evento
        data object Errore : Evento

        /** (0.10) Com'è andata una proposta mandata da qui ("Nuova proposta"). */
        data class PropostaMandata(val esito: EsitoProposta) : Evento

        /** (0.10) Com'è andato il ritiro di una proposta del figlio. */
        data class Ritiro(val esito: EsitoRitiro) : Evento
    }

    data class StatoProposte(
        val caricamento: Boolean = true,
        /** (v3) Tutte le proposte del figlio, anche sulle regole del computer. (v3.4) Di tutti e due gli autori. */
        val proposte: List<Proposta> = emptyList(),
        /**
         * (0.10) Le proposte del genitore che aspettano la risposta del figlio:
         * "Da decidere", il numero sulla scheda e le regole "occupate". Da
         * GET /api/proposte (al massimo 50) più `proposte_pendenti` del patto
         * appena letto (senza tetto), una per id: nessuna in attesa manca mai.
         */
        val daDecidere: List<Proposta> = emptyList(),
        /**
         * (0.10, v3.4) Le proposte del figlio che aspettano il genitore
         * (`proposte_inviate` del patto): "Le tue proposte".
         */
        val inviate: List<Proposta> = emptyList(),
        /**
         * Le regole attive del figlio, su TUTTI i suoi dispositivi (v3): il
         * confronto dice di quanto cambia, la regola dice COSA e dove. Senza,
         * la card non saprebbe dire "TikTok" né "sul computer".
         */
        val regole: List<Regola> = emptyList(),
        /**
         * (0.10) Le regole su cui si propone da qui: quelle di questo telefono
         * e la vita reale, le stesse della scheda Le mie regole (quelle degli
         * altri dispositivi si cambiano da lì).
         */
        val regoleDiQui: List<Regola> = emptyList(),
        /** (0.10) Tutte le regole attive del figlio: l'ultima non si propone di eliminarla. */
        val totaleFiglio: Int = 0,
        /** (v3) Questo telefono tra i dispositivi del figlio: per dire "sul computer". */
        val contesto: ContestoDispositivi = ContestoDispositivi(),
        val configurazioneMancante: Boolean = false,
        val errore: Boolean = false,
        /** Quando è arrivata la lista che si sta mostrando: l'età dei dati. */
        val aggiornateIl: Long? = null,
        val invioInCorso: Boolean = false,
        /**
         * (0.10) Com'è andata l'ultima proposta da "Nuova proposta", se NON è
         * arrivata: si dice dentro il modulo ancora aperto. null = niente.
         */
        val esitoProposta: EsitoProposta? = null,
        val evento: Evento? = null,
    ) {
        /**
         * Quante aspettano una risposta DEL FIGLIO: il badge sulla scheda.
         * (0.10) Le sue, che aspettano il genitore, non contano.
         */
        val pendenti: Int get() = daDecidere.size

        /** (0.10) La proposta che aspetta su ciascuna regola, di chiunque sia. */
        val inAttesaPerRegola: Map<Long, Proposta>
            get() = ProposteDelFiglio.inAttesaPerRegola(daDecidere, inviate)
    }

    private val _stato = MutableStateFlow(StatoProposte())
    val stato: StateFlow<StatoProposte> = _stato.asStateFlow()

    fun aggiorna() {
        _stato.value = _stato.value.copy(caricamento = true)
        viewModelScope.launch {
            val impostazioni = Impostazioni(getApplication())
            val configurazione = impostazioni.leggiConfigurazione()
            if (!configurazione.completa) {
                _stato.value = StatoProposte(caricamento = false, configurazioneMancante = true)
                return@launch
            }
            val postino = PostinoClient(configurazione)
            val proposte = postino.leggiProposte()
            if (proposte == null) {
                _stato.value = _stato.value.copy(caricamento = false, errore = true)
                return@launch
            }
            // Le regole fresche dal server: il confronto delle pendenti è
            // ricalcolato sulla regola di ADESSO, e "Ora: …" deve dire la
            // stessa cosa. Senza rete, l'ultima copia locale.
            val locale = PattoLocale(getApplication())
            val fresco = postino.leggiPatto()?.also { locale.salva(it) }
            val patto = fresco ?: locale.leggi()
            // (v3) Una proposta può essere su una regola del computer: il patto di
            // questo telefono non la contiene, GET /api/regole (tutto il figlio) sì.
            val delFiglio = postino.leggiRegole()
            val regole = (delFiglio.orEmpty() + patto?.regole.orEmpty()).distinctBy { it.id }
            // (0.10) Le regole su cui proporre da qui e quante sono in tutto: come
            // nella scheda Le mie regole, quelle dei dispositivi scollegati non
            // contano, e senza GET /api/regole vale l'ultimo numero saputo con
            // questo collegamento (lo stesso della scheda Le mie regole). Senza
            // nessuna copia del patto si tiene quello che c'era.
            val qui = patto?.regoleDiQuestoDispositivo()
            val scollegati = patto?.dispositivi.orEmpty().filter { it.revocato }.map { it.id }.toSet()
            val letteAltrove = if (qui != null && delFiglio != null) RegoleAltrove.conta(delFiglio, qui, scollegati) else null
            if (letteAltrove != null) impostazioni.salvaRegoleAltrove(letteAltrove, configurazione)
            val altrove = letteAltrove
                ?: RegoleAltrove.ultimoNoto(impostazioni.leggiRegoleAltrove(), configurazione.impronta)
            _stato.value = _stato.value.copy(
                caricamento = false,
                configurazioneMancante = false,
                errore = false,
                aggiornateIl = System.currentTimeMillis(),
                proposte = proposte,
                // (0.10) Quelle del genitore in attesa: la lista (50 al massimo) più
                // le pendenti del patto appena letto, che non ha tetto. Una copia
                // locale vecchia no: rimetterebbe in attesa una proposta già decisa.
                daDecidere = ProposteDelFiglio.daDecidere(proposte, fresco?.propostePendenti.orEmpty()),
                // (0.10) Dal patto appena letto; senza, dalle proposte appena lette.
                inviate = ProposteDelFiglio.inviate(fresco, proposte),
                regole = regole.ifEmpty { _stato.value.regole },
                regoleDiQui = qui ?: _stato.value.regoleDiQui,
                totaleFiglio = qui?.let { it.size + altrove } ?: _stato.value.totaleFiglio,
                contesto = patto?.contestoDispositivi() ?: _stato.value.contesto,
            )
        }
    }

    fun rispondi(propostaId: Long, esito: String, motivazione: String?) {
        _stato.value = _stato.value.copy(invioInCorso = true)
        viewModelScope.launch {
            val configurazione = Impostazioni(getApplication()).leggiConfigurazione()
            val postino = PostinoClient(configurazione)
            val risposta = postino.rispondiProposta(
                propostaId,
                RispostaPropostaIn(esito = esito, motivazione = motivazione?.ifBlank { null }),
            )
            val evento = if (risposta.ok) {
                if (esito == EsitiRisposta.ACCETTA) {
                    // (0.10) Anche una pendente venuta solo dal patto (oltre le 50 della lista).
                    val decisa = (_stato.value.daDecidere + _stato.value.proposte).firstOrNull { it.id == propostaId }
                    Evento.Accettata(decisa?.regolaId ?: 0)
                } else {
                    Evento.Rifiutata
                }
            } else if (leggiDettaglioErrore(risposta.corpo)?.errore == "proposta_non_pendente") {
                Evento.NonPiuPendente
            } else {
                Evento.Errore
            }
            if (risposta.ok && esito == EsitiRisposta.ACCETTA) {
                // La regola è già cambiata sul server: la copia locale deve seguire.
                postino.leggiPatto()?.let { PattoLocale(getApplication()).salva(it) }
            }
            _stato.value = _stato.value.copy(invioInCorso = false, evento = evento)
            if (risposta.ok || evento == Evento.NonPiuPendente) aggiorna()
        }
    }

    /**
     * (0.10) "Nuova proposta": propone al genitore [cambio] su una regola del
     * figlio. Se il genitore accetta, vale subito (contratto v3.4).
     */
    fun proponi(cambio: CambioRegola, motivazione: String?) {
        _stato.value = _stato.value.copy(invioInCorso = true, esitoProposta = null)
        viewModelScope.launch {
            val postino = PostinoClient(Impostazioni(getApplication()).leggiConfigurazione())
            val esito = mandaCambio(
                cambio,
                motivazione,
                manda = { postino.mandaProposta(it) },
                // Su un 409 "già in attesa": di chi è quella che aspetta?
                inviate = { postino.leggiPatto()?.proposteInviate },
            )
            _stato.value = _stato.value.copy(
                invioInCorso = false,
                esitoProposta = esito.takeUnless { it is EsitoProposta.Mandata },
                evento = Evento.PropostaMandata(esito),
            )
            if (ProposteDelFiglio.serveRileggere(esito)) aggiorna()
        }
    }

    /** (0.10) Il modulo della proposta si è chiuso: il suo esito non si dice più. */
    fun dimenticaEsitoProposta() {
        if (_stato.value.esitoProposta != null) _stato.value = _stato.value.copy(esitoProposta = null)
    }

    /**
     * (0.10) Ritira una proposta del figlio ancora in attesa: la regola resta
     * com'è, il genitore riceve un avviso. Dopo, la lista si rilegge: ritirata,
     * già decisa o sparita, comunque non aspetta più.
     */
    fun ritira(propostaId: Long) {
        _stato.value = _stato.value.copy(invioInCorso = true)
        viewModelScope.launch {
            val postino = PostinoClient(Impostazioni(getApplication()).leggiConfigurazione())
            val risposta = postino.ritiraProposta(propostaId)
            val esito = ProposteDelFiglio.esitoRitiro(risposta.ok, risposta.codice, risposta.corpo)
            _stato.value = _stato.value.copy(invioInCorso = false, evento = Evento.Ritiro(esito))
            if (esito == EsitoRitiro.Ritirata || esito == EsitoRitiro.NonPiuPendente || esito == EsitoRitiro.NonTrovata) {
                aggiorna()
            }
        }
    }

    fun consumaEvento() {
        _stato.value = _stato.value.copy(evento = null)
    }
}
