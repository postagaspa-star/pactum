package eu.stgm.pactum.genitore.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.pactum.genitore.dati.CodiciErrore
import eu.stgm.pactum.genitore.dati.EsitiSessione
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.Impostazioni
import eu.stgm.pactum.genitore.dati.MASSIMO_MOTIVAZIONE_SESSIONE
import eu.stgm.pactum.genitore.rete.EsitoRispostaSessione
import eu.stgm.pactum.genitore.rete.EsitoScrittura
import eu.stgm.pactum.genitore.rete.EsitoSessioni
import eu.stgm.pactum.genitore.rete.PostinoClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

/**
 * La finestra del figlio scelto (GET /api/finestra?figlio_id=n). La condividono
 * Panoramica e Tempo (scope dell'attività): una lettura sola serve entrambe.
 *
 * (v3) Lo stato dice SEMPRE di quale figlio sono i dati ([StatoFinestra.figlioId]):
 * cambiando figlio, i dati dell'altro non restano sullo schermo col nome sbagliato
 * sopra. Di ogni figlio si ricorda l'ultima finestra arrivata, con la sua ora,
 * così tornando su un figlio lo si rivede subito — con l'età del dato scritta.
 *
 * (0.11) Da qui il genitore risponde alle sessioni del figlio (contratto v3.5):
 * approva o non approva quello che è in attesa, una sessione nuova o un cambio
 * della lista ([decidiSessione]). Solo la Panoramica le mostra.
 */
class FinestraViewModel(application: Application) : AndroidViewModel(application) {

    /** L'esito del "Manda un segno", da dire una volta e poi consumare. */
    enum class EsitoSegno { MANDATO, GIA_MANDATO, FALLITO }

    /**
     * (0.11) L'esito di una risposta a una sessione, e di quale figlio era: se nel
     * frattempo il genitore guarda un altro figlio, la frase lo dice.
     */
    data class EsitoSessioneDi(val figlioId: Long?, val esito: EsitoSessione)

    data class StatoFinestra(
        /** Di quale figlio parla questo stato; null = server 0.7 (o figlio non ancora noto). */
        val figlioId: Long? = null,
        /** false finché non si è chiesta la finestra di [figlioId]. */
        val richiesta: Boolean = false,
        val caricamento: Boolean = true,
        val finestra: Finestra? = null,
        val configurazioneMancante: Boolean = false,
        val errore: Boolean = false,
        /** Quando la finestra mostrata è arrivata: su errore resta quella vecchia. */
        val ricevutaAlle: Instant? = null,
        /**
         * (0.10) Quando è PARTITA la lettura della finestra mostrata, sull'orologio
         * monotono: una proposta decisa dopo non si mostra più in questi dati
         * (chiusaPrimaDellaLettura).
         */
        val lettaAlle: Long? = null,
        val invioSegno: Boolean = false,
        /** Il giorno (del telefono) in cui il segno è partito da qui, per figlio. */
        val segniMandati: Map<Long?, LocalDate> = emptyMap(),
        val esitoSegno: EsitoSegno? = null,
        /** (0.11) Una risposta a una sessione è in volo: una alla volta, anche cambiando figlio. */
        val invioSessione: Boolean = false,
        /**
         * (0.11) Le sessioni appena decise da qui (o trovate non più in attesa): la
         * loro card sparisce subito, nei dati letti prima (SessioneDecisa). Restano
         * finché non si rilegge la finestra di quel figlio.
         */
        val sessioniDecise: Map<Long, SessioneDecisa> = emptyMap(),
        /** (0.11) L'esito dell'ultima risposta a una sessione, da dire una volta e poi consumare. */
        val esitoSessione: EsitoSessioneDi? = null,
    ) {
        /** Il giorno in cui è partito da qui il segno del figlio mostrato. */
        val segnoMandatoIl: LocalDate? get() = segniMandati[figlioId]

        /** true = questi dati sono del figlio [id] (null = server 0.7). */
        fun di(id: Long?): Boolean = richiesta && figlioId == id
    }

    /** L'ultima finestra arrivata per ciascun figlio, e quando ((0.10) e quando era partita la sua lettura). */
    private data class Ricordata(val finestra: Finestra, val alle: Instant, val lettaAlle: Long)

    private val _stato = MutableStateFlow(StatoFinestra())
    val stato: StateFlow<StatoFinestra> = _stato.asStateFlow()
    private val ricordate = mutableMapOf<Long?, Ricordata>()
    private var lettura: Job? = null

    /**
     * Rilegge la finestra di [figlioId] (null = server 0.7: nessun `figlio_id`).
     * Se il figlio cambia, lo stato riparte da quello che si ricorda di lui.
     */
    fun aggiorna(figlioId: Long?) {
        val prima = _stato.value
        _stato.value = if (!prima.richiesta || prima.figlioId != figlioId) {
            val ricordata = ricordate[figlioId]
            StatoFinestra(
                figlioId = figlioId,
                richiesta = true,
                caricamento = true,
                finestra = ricordata?.finestra,
                ricevutaAlle = ricordata?.alle,
                lettaAlle = ricordata?.lettaAlle,
                segniMandati = prima.segniMandati,
                // (0.11) Gli id delle sessioni sono di tutto il server, e una
                // risposta in volo resta in volo: si tengono, come i segni.
                invioSessione = prima.invioSessione,
                sessioniDecise = prima.sessioniDecise,
                esitoSessione = prima.esitoSessione,
            )
        } else {
            prima.copy(caricamento = true)
        }
        lettura?.cancel()
        lettura = viewModelScope.launch {
            val inizio = SystemClock.elapsedRealtime()
            val impostazioni = Impostazioni(getApplication())
            val configurazione = impostazioni.leggiConfigurazione()
            if (!configurazione.completa) {
                _stato.value = StatoFinestra(
                    figlioId = figlioId,
                    richiesta = true,
                    caricamento = false,
                    configurazioneMancante = true,
                )
                return@launch
            }
            val finestra = PostinoClient(configurazione).leggiFinestra(figlioId)
            if (finestra == null) {
                // L'ultima finestra buona resta visibile sotto l'avviso di errore.
                _stato.value = _stato.value.copy(caricamento = false, errore = true)
            } else {
                impostazioni.registraVerificaRiuscita()
                val adesso = Instant.now()
                ricordate[figlioId] = Ricordata(finestra, adesso, inizio)
                // copy e non uno stato nuovo: un segno in volo non va dimenticato.
                val attuale = _stato.value
                _stato.value = attuale.copy(
                    caricamento = false,
                    finestra = finestra,
                    configurazioneMancante = false,
                    errore = false,
                    ricevutaAlle = adesso,
                    lettaAlle = inizio,
                    // (0.11) Le decisioni di prima su questo figlio: la finestra nuova sa com'è.
                    sessioniDecise = deciseDaTenere(attuale.sessioniDecise, figlioId, inizio),
                )
            }
        }
    }

    /**
     * Il riconoscimento al figlio (POST /api/segno {figlio_id}): testo fisso, uno
     * al giorno PER FIGLIO. Un 409 `segno_gia_mandato` vuol dire che oggi è già
     * partito: si spegne il pulsante e basta.
     */
    fun mandaSegno(figlioId: Long?) {
        if (_stato.value.invioSegno) return
        _stato.value = _stato.value.copy(invioSegno = true)
        viewModelScope.launch {
            val configurazione = Impostazioni(getApplication()).leggiConfigurazione()
            val esito = esitoDelSegno(PostinoClient(configurazione).mandaSegno(figlioId))
            val segni = if (esito == EsitoSegno.FALLITO) {
                _stato.value.segniMandati
            } else {
                _stato.value.segniMandati + (figlioId to LocalDate.now())
            }
            _stato.value = _stato.value.copy(
                invioSegno = false,
                segniMandati = segni,
                esitoSegno = esito,
            )
            if (esito == EsitoSegno.MANDATO && _stato.value.figlioId == figlioId) aggiorna(figlioId)
        }
    }

    fun consumaEsitoSegno() {
        _stato.value = _stato.value.copy(esitoSegno = null)
    }

    /**
     * (0.11) La risposta del genitore a una sessione del figlio (contratto v3.5):
     * [esito] `approva` o `rifiuta`, col perché facoltativo (solo per il no, al
     * massimo 500 caratteri). Una alla volta.
     *
     * [richiesta] è quella che il genitore aveva davanti quando ha aperto la
     * domanda (non quella che c'è sullo schermo adesso), e con la risposta va la
     * SUA `versione`: se nel frattempo la sessione è cambiata, il server non decide
     * niente e manda la sessione com'è adesso, che prende subito il posto di quella
     * di prima (esitoRichiestaCambiata), ma solo se non è più vecchia di quella
     * mostrata (sostituisciSessione). Il genitore non approva mai una lista che non
     * ha visto. Se il server dice che non c'era niente da decidere, si rilegge com'è
     * finita, per dirlo. Dopo, se qualcosa è cambiato, la finestra si rilegge.
     */
    fun decidiSessione(figlioId: Long?, richiesta: SessioneDaApprovare, esito: String, motivazione: String?) {
        if (_stato.value.invioSessione) return
        _stato.value = _stato.value.copy(invioSessione = true)
        viewModelScope.launch {
            val configurazione = Impostazioni(getApplication()).leggiConfigurazione()
            val postino = PostinoClient(configurazione)
            val perche = motivazione?.trim()?.take(MASSIMO_MOTIVAZIONE_SESSIONE)?.ifBlank { null }
                .takeIf { esito == EsitiSessione.RIFIUTA }
            val risultato = rispostaSessione(postino, figlioId, richiesta, esito, perche)
            val decisa = SessioneDecisa(figlioId, SystemClock.elapsedRealtime())
            val adesso = sessioneDopo(risultato)
            val prima = _stato.value
            _stato.value = prima.copy(
                invioSessione = false,
                esitoSessione = EsitoSessioneDi(figlioId, risultato),
                sessioniDecise = if (sessioneDaTogliere(risultato)) {
                    prima.sessioniDecise + (richiesta.sessione.id to decisa)
                } else {
                    prima.sessioniDecise
                },
                // La sessione com'è adesso, se il server l'ha detta: subito al suo posto.
                finestra = if (adesso != null && prima.di(figlioId)) {
                    prima.finestra?.let { it.copy(sessioni = sostituisciSessione(it.sessioni, adesso)) }
                } else {
                    prima.finestra
                },
            )
            if (adesso != null) {
                ricordate[figlioId]?.let { ricordata ->
                    val finestra = ricordata.finestra
                    ricordate[figlioId] = ricordata.copy(finestra = finestra.copy(sessioni = sostituisciSessione(finestra.sessioni, adesso)))
                }
            }
            // Qualcosa è cambiato (deciso, o non più com'era): si rilegge. Una rete
            // caduta o un server da aggiornare non hanno cambiato niente.
            if (daRileggereDopo(risultato) && _stato.value.di(figlioId)) aggiorna(figlioId)
        }
    }

    /** La risposta; e se non c'era niente da decidere, com'è finita. */
    private suspend fun rispostaSessione(
        postino: PostinoClient,
        figlioId: Long?,
        richiesta: SessioneDaApprovare,
        esito: String,
        motivazione: String?,
    ): EsitoSessione {
        val sessione = richiesta.sessione
        // Senza la versione vista non si risponde (la card spegne i gesti): il server
        // non saprebbe su che cosa si decide.
        val versione = sessione.versione ?: return EsitoSessione.NonDecisa(CodiciErrore.SERVER_DA_AGGIORNARE)
        return when (val risposta = postino.rispondiSessione(sessione.id, esito, versione, motivazione, figlioId)) {
            is EsitoRispostaSessione.Decisa -> EsitoSessione.Decisa(esito, richiesta.cambio, risposta.sessione)
            is EsitoRispostaSessione.Cambiata -> esitoRichiestaCambiata(richiesta, risposta.sessione)
            is EsitoRispostaSessione.Rifiutata ->
                if (risposta.codice == CodiciErrore.NIENTE_DA_DECIDERE) {
                    val dopo = (postino.leggiSessioni(figlioId) as? EsitoSessioni.Lette)?.sessioni
                    EsitoSessione.NonDecisa(
                        risposta.codice,
                        sessioneRiletta(sessione.id, richiesta.cambio, dopo),
                        adesso = dopo?.firstOrNull { it.id == sessione.id },
                    )
                } else {
                    EsitoSessione.NonDecisa(risposta.codice)
                }
            EsitoRispostaSessione.Fallita -> EsitoSessione.NonDecisa(null)
        }
    }

    fun consumaEsitoSessione() {
        _stato.value = _stato.value.copy(esitoSessione = null)
    }

    /**
     * Dopo un cambio di server o di codice d'accesso: le finestre ricordate sono
     * di un altro server, e il "figlio 1" di prima non è il figlio 1 di adesso.
     */
    fun dimentica() {
        lettura?.cancel()
        ricordate.clear()
        _stato.value = StatoFinestra()
    }
}

/**
 * Che cosa dire dopo POST /api/segno. Solo il 409 `segno_gia_mandato` è "oggi è
 * già partito" (e spegne il pulsante); qualunque altro rifiuto — un altro 409, un
 * 409 senza codice, un 422 — è una risposta che non ci aspettiamo: "riprova",
 * senza fingere che il segno sia arrivato.
 */
fun esitoDelSegno(risposta: EsitoScrittura<*>): FinestraViewModel.EsitoSegno = when (risposta) {
    is EsitoScrittura.Riuscito -> FinestraViewModel.EsitoSegno.MANDATO
    is EsitoScrittura.Rifiutato ->
        if (risposta.errore == CodiciErrore.SEGNO_GIA_MANDATO) {
            FinestraViewModel.EsitoSegno.GIA_MANDATO
        } else {
            FinestraViewModel.EsitoSegno.FALLITO
        }
    EsitoScrittura.Fallito -> FinestraViewModel.EsitoSegno.FALLITO
}
