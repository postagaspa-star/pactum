package eu.stgm.pactum.genitore.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.pactum.genitore.dati.CodiciErrore
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.Impostazioni
import eu.stgm.pactum.genitore.rete.EsitoScrittura
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
 */
class FinestraViewModel(application: Application) : AndroidViewModel(application) {

    /** L'esito del "Manda un segno", da dire una volta e poi consumare. */
    enum class EsitoSegno { MANDATO, GIA_MANDATO, FALLITO }

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
        val invioSegno: Boolean = false,
        /** Il giorno (del telefono) in cui il segno è partito da qui, per figlio. */
        val segniMandati: Map<Long?, LocalDate> = emptyMap(),
        val esitoSegno: EsitoSegno? = null,
    ) {
        /** Il giorno in cui è partito da qui il segno del figlio mostrato. */
        val segnoMandatoIl: LocalDate? get() = segniMandati[figlioId]

        /** true = questi dati sono del figlio [id] (null = server 0.7). */
        fun di(id: Long?): Boolean = richiesta && figlioId == id
    }

    /** L'ultima finestra arrivata per ciascun figlio, e quando. */
    private data class Ricordata(val finestra: Finestra, val alle: Instant)

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
                segniMandati = prima.segniMandati,
            )
        } else {
            prima.copy(caricamento = true)
        }
        lettura?.cancel()
        lettura = viewModelScope.launch {
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
                ricordate[figlioId] = Ricordata(finestra, adesso)
                // copy e non uno stato nuovo: un segno in volo non va dimenticato.
                _stato.value = _stato.value.copy(
                    caricamento = false,
                    finestra = finestra,
                    configurazioneMancante = false,
                    errore = false,
                    ricevutaAlle = adesso,
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
