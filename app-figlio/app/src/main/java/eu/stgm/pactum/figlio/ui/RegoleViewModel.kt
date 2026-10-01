package eu.stgm.pactum.figlio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.pactum.figlio.dati.CambioRegola
import eu.stgm.pactum.figlio.dati.CreaRegolaIn
import eu.stgm.pactum.figlio.dati.EsitoProposta
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.dati.ModificaRegolaIn
import eu.stgm.pactum.figlio.dati.PattoLocale
import eu.stgm.pactum.figlio.dati.Proposta
import eu.stgm.pactum.figlio.dati.PropostaIn
import eu.stgm.pactum.figlio.dati.ProposteDelFiglio
import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.RegoleAltrove
import eu.stgm.pactum.figlio.dati.StatiProposta
import eu.stgm.pactum.figlio.dati.leggiDettaglioErrore
import eu.stgm.pactum.figlio.rete.PostinoClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Le regole del patto: lista dal server (fonte di verità), creazione, modifica
 * ed eliminazione. Il lock asimmetrico dei 4 giorni lo applica il SERVER:
 * l'app mostra il conto alla rovescia leggendo i secondi residui dal 409
 * (contratto-api.md, sezione Regole). La copia locale (PattoLocale) fa da
 * riserva in lettura quando la rete manca.
 *
 * (0.10) In più il figlio può proporre al genitore un cambio a una sua regola
 * (contratto v3.4): se il genitore accetta, vale subito, anche se allenta.
 * Quando il blocco dei 4 giorni ferma un cambio, "Chiedi al genitore" manda
 * come proposta proprio quel cambio.
 */
class RegoleViewModel(application: Application) : AndroidViewModel(application) {

    /** Un esito una-tantum da mostrare, poi consumato. */
    sealed interface Evento {
        data object Salvata : Evento
        data object Eliminata : Evento

        /**
         * Il 409 del lock: [secondiRimanenti] al primo momento buono per
         * allentare. (0.10) [cambio] = il cambio fermato, tale e quale: è
         * quello che "Chiedi al genitore" manda come proposta.
         */
        data class LockAttivo(val secondiRimanenti: Long, val cambio: CambioRegola) : Evento {
            val perEliminazione: Boolean get() = cambio is CambioRegola.Eliminazione
        }
        data object UltimaRegola : Evento
        data object Errore : Evento

        /** (0.10) Com'è andata una proposta al genitore. */
        data class PropostaAlGenitore(val esito: EsitoProposta) : Evento
    }

    data class StatoRegole(
        val caricamento: Boolean = true,
        /**
         * Almeno una lettura è finita (dal server, dalla copia o "non
         * collegato"). Il cancello della prima regola aspetta solo la prima:
         * le riletture non lo tolgono di mezzo mentre il ragazzo ci scrive.
         */
        val letto: Boolean = false,
        /** (v3) Le regole di QUESTO telefono e quelle di vita reale del figlio. */
        val regole: List<Regola> = emptyList(),
        /**
         * (v3) Quante regole attive ha il figlio sugli ALTRI suoi dispositivi
         * (GET /api/regole). Il patto è del figlio: "almeno una regola" e
         * "l'ultima non si toglie" contano anche quelle (contratto v3, Regole).
         */
        val regoleAltrove: Int = 0,
        /** Le regole i cui parametri attuali sono nati da una proposta accettata. */
        val concordate: Set<Long> = emptySet(),
        /**
         * (0.10) La proposta che aspetta su ciascuna regola (id della regola →
         * proposta), del genitore o del figlio: al massimo una per regola. La
         * regola lo dice, invece di far proporre un doppione che il server rifiuta.
         */
        val proposteInAttesa: Map<Long, Proposta> = emptyMap(),
        /**
         * (0.10) Com'è andata l'ultima proposta, se NON è arrivata: si dice
         * dentro il modulo ancora aperto (proposta o blocco), dove il ragazzo
         * guarda. null = niente da dire; si cancella a ogni nuovo invio e
         * quando il modulo si chiude (dimenticaEsitoProposta).
         */
        val esitoProposta: EsitoProposta? = null,
        val configurazioneMancante: Boolean = false,
        val errore: Boolean = false,
        /** Con `errore`: quando è arrivata la copia che si sta mostrando. */
        val datiFermiAlle: Long? = null,
        val invioInCorso: Boolean = false,
        val evento: Evento? = null,
    ) {
        /** Tutte le regole attive del figlio, su ogni dispositivo. */
        val totaleFiglio: Int get() = regole.size + regoleAltrove
    }

    private val _stato = MutableStateFlow(StatoRegole())
    val stato: StateFlow<StatoRegole> = _stato.asStateFlow()

    fun aggiorna() {
        _stato.value = _stato.value.copy(caricamento = true)
        viewModelScope.launch {
            val impostazioni = Impostazioni(getApplication())
            val configurazione = impostazioni.leggiConfigurazione()
            if (!configurazione.completa) {
                _stato.value = StatoRegole(caricamento = false, letto = true, configurazioneMancante = true)
                return@launch
            }
            val postino = PostinoClient(configurazione)
            val patto = postino.leggiPatto()
            if (patto == null) {
                // Offline o server muto: si mostra la copia locale sotto l'avviso.
                // (v3) Le regole sugli altri dispositivi: l'ultimo numero saputo con
                // questo collegamento. Senza, chi ha regole solo sul computer si
                // vedrebbe chiedere "Crea la prima regola" appena manca la rete.
                val copia = PattoLocale(getApplication())
                val locale = copia.leggi()
                _stato.value = _stato.value.copy(
                    caricamento = false,
                    letto = true,
                    configurazioneMancante = false,
                    errore = true,
                    datiFermiAlle = copia.aggiornatoIl(),
                    regole = locale?.regoleDiQuestoDispositivo() ?: _stato.value.regole,
                    regoleAltrove = RegoleAltrove.ultimoNoto(impostazioni.leggiRegoleAltrove(), configurazione.impronta),
                    proposteInAttesa = locale
                        ?.let { ProposteDelFiglio.inAttesaPerRegola(it.propostePendenti, it.proposteInviate) }
                        ?: _stato.value.proposteInAttesa,
                )
                return@launch
            }
            PattoLocale(getApplication()).salva(patto)
            val qui = patto.regoleDiQuestoDispositivo()

            // (v3) Le regole del figlio sugli altri dispositivi: solo contate,
            // qui non si mostrano (si cambiano da lì). Quelle di un dispositivo
            // scollegato dal genitore non contano più: da lì non si cambiano. Il
            // numero si ricorda per quando manca la rete; senza risposta si tiene
            // l'ultimo saputo con questo collegamento: meglio vecchio che uno zero finto.
            val scollegati = patto.dispositivi.filter { it.revocato }.map { it.id }.toSet()
            val letteAltrove = postino.leggiRegole()?.let { RegoleAltrove.conta(it, qui, scollegati) }
            if (letteAltrove != null) impostazioni.salvaRegoleAltrove(letteAltrove, configurazione)
            val regoleAltrove = letteAltrove
                ?: RegoleAltrove.ultimoNoto(impostazioni.leggiRegoleAltrove(), configurazione.impronta)

            // Badge "concordata": una modifica nata da proposta accettata applica
            // ESATTAMENTE i parametri proposti (contratto) — quindi la regola è
            // concordata se i suoi parametri attuali coincidono con quelli di una
            // proposta accettata e usata. Best effort: senza proposte, nessun badge.
            // (0.10) Vale anche per le proposte del figlio accettate dal genitore.
            val proposte = postino.leggiProposte().orEmpty()
            val concordate = qui
                .filter { regola ->
                    proposte.any { proposta ->
                        proposta.stato == StatiProposta.ACCETTATA &&
                            proposta.usata &&
                            proposta.regolaId == regola.id &&
                            proposta.parametriProposti == regola.parametri
                    }
                }
                .map { it.id }
                .toSet()

            _stato.value = _stato.value.copy(
                caricamento = false,
                letto = true,
                configurazioneMancante = false,
                errore = false,
                regole = qui,
                regoleAltrove = regoleAltrove,
                concordate = concordate,
                proposteInAttesa = ProposteDelFiglio.inAttesaPerRegola(patto.propostePendenti, patto.proposteInviate),
            )
        }
    }

    fun crea(tipo: String, parametri: JsonObject) = muta(Evento.Salvata, cambio = null) {
        it.creaRegola(CreaRegolaIn(tipo, parametri))
    }

    fun modifica(regolaId: Long, parametri: JsonObject) =
        muta(Evento.Salvata, CambioRegola.Modifica(regolaId, parametri)) {
            it.modificaRegola(regolaId, ModificaRegolaIn(parametri))
        }

    fun elimina(regolaId: Long) = muta(Evento.Eliminata, CambioRegola.Eliminazione(regolaId)) {
        it.eliminaRegola(regolaId)
    }

    /**
     * (0.10) Propone al genitore [cambio]: dal modulo "Proponi al genitore",
     * oppure da "Chiedi al genitore" quando il blocco dei 4 giorni l'ha
     * fermato. [motivazione] = il perché, facoltativo.
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
                evento = Evento.PropostaAlGenitore(esito),
            )
            // La regola ora dice "proposta in attesa" (o non c'è più): si rilegge.
            if (ProposteDelFiglio.serveRileggere(esito)) aggiorna()
        }
    }

    /** (0.10) Il modulo della proposta si è chiuso: il suo esito non si dice più. */
    fun dimenticaEsitoProposta() {
        if (_stato.value.esitoProposta != null) _stato.value = _stato.value.copy(esitoProposta = null)
    }

    /** [cambio] null = creazione: creare stringe sempre, il blocco non c'entra. */
    private fun muta(
        eventoOk: Evento,
        cambio: CambioRegola?,
        operazione: suspend (PostinoClient) -> PostinoClient.RispostaHttp,
    ) {
        _stato.value = _stato.value.copy(invioInCorso = true)
        viewModelScope.launch {
            val configurazione = Impostazioni(getApplication()).leggiConfigurazione()
            val risposta = operazione(PostinoClient(configurazione))
            val evento = eventoCambio(risposta, eventoOk, cambio)
            _stato.value = _stato.value.copy(invioInCorso = false, evento = evento)
            if (risposta.ok) aggiorna()
        }
    }

    fun consumaEvento() {
        _stato.value = _stato.value.copy(evento = null)
    }
}

/**
 * La risposta a una creazione, modifica o eliminazione come evento da
 * mostrare. Il 409 del blocco dei 4 giorni porta con sé il [cambio] fermato:
 * "Chiedi al genitore" manda proprio quello (0.10). Senza Android: si prova
 * con JUnit (RegoleViewModelTest).
 */
internal fun eventoCambio(
    risposta: PostinoClient.RispostaHttp,
    eventoOk: RegoleViewModel.Evento,
    cambio: CambioRegola?,
): RegoleViewModel.Evento {
    if (risposta.ok) return eventoOk
    val dettaglio = leggiDettaglioErrore(risposta.corpo) ?: return RegoleViewModel.Evento.Errore
    return when (dettaglio.errore) {
        "lock_attivo" -> cambio
            ?.let { RegoleViewModel.Evento.LockAttivo(dettaglio.secondiRimanenti ?: 0, it) }
            ?: RegoleViewModel.Evento.Errore
        "ultima_regola" -> RegoleViewModel.Evento.UltimaRegola
        else -> RegoleViewModel.Evento.Errore
    }
}

/**
 * (0.10) Il cuore di "Proponi al genitore" e di "Chiedi al genitore", senza
 * Android: il cambio diventa il corpo di POST /api/proposte (proprio quei
 * parametri, o il marcatore dell'eliminazione), [manda] lo spedisce, la
 * risposta diventa un esito. Lo usano le due schermate (Regole e Proposte).
 *
 * Su un 409 `proposta_gia_pendente` si guarda di chi è quella in attesa:
 * [inviate] rilegge le proposte del figlio (`proposte_inviate` del patto). Se
 * è sua (una risposta persa e un secondo tentativo), è [EsitoProposta.GiaTua].
 */
internal suspend fun mandaCambio(
    cambio: CambioRegola,
    motivazione: String?,
    manda: suspend (PropostaIn) -> PostinoClient.RispostaHttp,
    inviate: suspend () -> List<Proposta>? = { null },
): EsitoProposta {
    val risposta = manda(ProposteDelFiglio.richiesta(cambio, motivazione))
    val esito = ProposteDelFiglio.esito(cambio, risposta.ok, risposta.codice, risposta.corpo)
    if (esito != EsitoProposta.GiaPendente) return esito
    return ProposteDelFiglio.precisaGiaPendente(esito, cambio, inviate().orEmpty())
}
