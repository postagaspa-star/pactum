package eu.stgm.pactum.genitore.ui

import eu.stgm.pactum.genitore.dati.CodiciErrore
import eu.stgm.pactum.genitore.dati.ContenutoStudio
import eu.stgm.pactum.genitore.dati.EsitiSessione
import eu.stgm.pactum.genitore.dati.MASSIMO_MOTIVAZIONE_STUDIO
import eu.stgm.pactum.genitore.dati.PaccoStudio
import eu.stgm.pactum.genitore.dati.StudioSvolto
import eu.stgm.pactum.genitore.rete.EsitoChiusuraStudio
import eu.stgm.pactum.genitore.rete.EsitoLetturaStudio
import eu.stgm.pactum.genitore.rete.EsitoRispostaStudio
import eu.stgm.pactum.genitore.rete.FonteStudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// (0.18, contratto v4.0, parte C) Il motore dello Studio dalla parte del genitore:
// la pagina dello Studio (GET /api/studio, le versioni approvate, gli Studi fatti
// 20 per volta), la risposta alla configurazione da approvare e la chiusura del
// genitore col motivo. Niente Android: il ViewModel (StudioViewModel) gli dà lo
// scope e il server; i test gli danno un server finto (GestoreStudioTest).

/** I gesti del genitore sullo Studio, per dire il rifiuto giusto. */
enum class GestoStudio { APPROVA, RIFIUTA, CHIUDI }

/** Un esito da dire una volta (in basso) e poi consumare. */
sealed interface EventoStudio {
    /** La configurazione è stata approvata ([esito] `approva`) o no. */
    data class Decisa(val esito: String) : EventoStudio

    /** Nel frattempo la configurazione è cambiata: niente deciso, la si guarda di nuovo. */
    data object Cambiata : EventoStudio

    /** Non c'era più niente da decidere (un altro genitore, o il figlio ha ritirato). */
    data object NienteDaDecidere : EventoStudio

    /** Lo Studio è chiuso. */
    data object Chiuso : EventoStudio

    /** Lo Studio era già chiuso (dal figlio, da un altro genitore, a mezzanotte). */
    data object GiaChiuso : EventoStudio

    /** Un gesto che il server non ha preso (o la rete caduta: [codice] null). */
    data class Rifiuto(val codice: String?, val gesto: GestoStudio, val figlioId: Long?) : EventoStudio
}

data class StatoStudio(
    /** Di quale figlio parla questo stato. */
    val figlioId: Long? = null,
    /** false finché non si è chiesto lo Studio di [figlioId]. */
    val richiesta: Boolean = false,
    val caricamento: Boolean = true,
    /** GET /api/studio: configurazione, in corso, prossime partenze, recenti. null = mai letto. */
    val pacco: PaccoStudio? = null,
    /** Le configurazioni approvate, dalla più recente. null = non lette. */
    val versioni: List<ContenutoStudio>? = null,
    /** Gli Studi fatti, dal più recente, 20 per volta. null = non letti. */
    val svolte: List<StudioSvolto>? = null,
    /** Ce ne sono di più vecchi da chiedere. */
    val altre: Boolean = false,
    val caricoAltre: Boolean = false,
    /** Il server non conosce lo Studio (più vecchio della v4.0). */
    val serverVecchio: Boolean = false,
    val configurazioneMancante: Boolean = false,
    /** L'ultima lettura è fallita: quello che si vede è di prima. */
    val errore: Boolean = false,
    /** 401: il collegamento di questo telefono non vale più. */
    val collegamentoNonValido: Boolean = false,
    /** Un gesto è in volo: uno alla volta. */
    val invio: Boolean = false,
    val evento: EventoStudio? = null,
    /**
     * Le configurazioni a cui si è appena risposto da qui, per figlio: la versione
     * decisa. La card sparisce subito, finché la finestra è di prima.
     */
    val decise: Map<Long?, Int> = emptyMap(),
) {
    /** true = questi dati sono del figlio [id]. */
    fun di(id: Long?): Boolean = richiesta && figlioId == id
}

/**
 * (correzione 0.18) La prima pagina degli Studi fatti appena riletta ([nuova], con
 * [altreNuova]) unita a quelle già caricate con "Altri" ([vecchie], con
 * [altreVecchie]): prima le nuove (sono le più fresche), poi le vecchie con un id
 * più piccolo di tutte le nuove, senza doppioni. Così la rilettura di ogni minuto
 * non toglie le pagine che il genitore sta leggendo. Se le due liste non hanno
 * nessuno Studio in comune, in mezzo potrebbero mancarne: allora si riparte dalla
 * sola prima pagina (meglio corta che con un buco).
 */
internal fun unisciPrimaPagina(
    nuova: List<StudioSvolto>,
    altreNuova: Boolean,
    vecchie: List<StudioSvolto>?,
    altreVecchie: Boolean,
): Pair<List<StudioSvolto>, Boolean> {
    // Senza pagine vecchie, o se la prima pagina è già tutto: vale lei.
    if (vecchie.isNullOrEmpty() || nuova.isEmpty() || !altreNuova) return nuova to altreNuova
    val idNuove = nuova.map { it.id }.toSet()
    val minimo = nuova.minOf { it.id }
    val coda = vecchie.filter { it.id < minimo && it.id !in idNuove }.distinctBy { it.id }
    if (coda.isEmpty() || vecchie.none { it.id in idNuove }) return nuova to altreNuova
    return (nuova + coda) to altreVecchie
}

/**
 * Il motore. [fonte] = il server del collegamento salvato, null se manca. Come la
 * finestra, lo stato dice SEMPRE di quale figlio sono i dati.
 */
class GestoreStudio(
    private val ambito: CoroutineScope,
    private val fonte: suspend () -> FonteStudio?,
) {
    private val _stato = MutableStateFlow(StatoStudio())
    val stato: StateFlow<StatoStudio> = _stato.asStateFlow()
    private var lettura: Job? = null

    /**
     * Rilegge lo Studio di [figlioId]: GET /api/studio, le versioni e la prima pagina
     * degli Studi fatti. (correzione 0.18) La prima pagina nuova si UNISCE alle pagine
     * già caricate con "Altri" (v. [unisciPrimaPagina]): la rilettura di ogni minuto
     * non accorcia la lista che il genitore sta leggendo. Si riparte dalla prima
     * pagina solo cambiando figlio o con [daCapo].
     */
    fun aggiorna(figlioId: Long?, daCapo: Boolean = false) {
        val prima = _stato.value
        _stato.value = if (!prima.richiesta || prima.figlioId != figlioId || daCapo) {
            StatoStudio(
                figlioId = figlioId,
                richiesta = true,
                caricamento = true,
                collegamentoNonValido = prima.collegamentoNonValido,
                invio = prima.invio,
                evento = prima.evento,
                decise = prima.decise,
            )
        } else {
            prima.copy(caricamento = true)
        }
        lettura?.cancel()
        lettura = ambito.launch {
            val postino = fonte()
            if (postino == null) {
                _stato.value = _stato.value.copy(caricamento = false, configurazioneMancante = true)
                return@launch
            }
            val studio = async { postino.leggiStudio(figlioId) }
            val versioni = async { postino.leggiVersioniStudio(figlioId) }
            val svolte = async { postino.leggiSvolteStudio(figlioId, null) }
            val esitoStudio = studio.await()
            val esitoVersioni = versioni.await()
            val esitoSvolte = svolte.await()
            val tutti = listOf(esitoStudio, esitoVersioni, esitoSvolte)
            when {
                tutti.any { it == EsitoLetturaStudio.NonAutorizzato } -> nonPiuCollegato()
                esitoStudio == EsitoLetturaStudio.ServerVecchio -> _stato.value = _stato.value.copy(
                    caricamento = false,
                    serverVecchio = true,
                    configurazioneMancante = false,
                    errore = false,
                    pacco = null,
                    versioni = null,
                    svolte = null,
                )
                esitoStudio is EsitoLetturaStudio.Letta -> {
                    val attuale = _stato.value
                    val pagina = (esitoSvolte as? EsitoLetturaStudio.Letta)?.dato
                    val unite = pagina?.let { unisciPrimaPagina(it.svolte, it.altre, attuale.svolte, attuale.altre) }
                    _stato.value = attuale.copy(
                        caricamento = false,
                        pacco = esitoStudio.dato,
                        versioni = (esitoVersioni as? EsitoLetturaStudio.Letta)?.dato?.versioni ?: attuale.versioni,
                        svolte = unite?.first ?: attuale.svolte,
                        altre = unite?.second ?: attuale.altre,
                        serverVecchio = false,
                        configurazioneMancante = false,
                        collegamentoNonValido = false,
                        // Una lettura a metà (versioni o Studi fatti non arrivati) si dice.
                        errore = esitoVersioni !is EsitoLetturaStudio.Letta || esitoSvolte !is EsitoLetturaStudio.Letta,
                        // `decise` resta: una richiesta nuova ha comunque una versione più alta.
                    )
                }
                else -> _stato.value = _stato.value.copy(caricamento = false, errore = true)
            }
        }
    }

    /** Gli Studi fatti più vecchi di quelli già mostrati (20 per volta). */
    fun altri(figlioId: Long?) {
        val attuale = _stato.value
        if (!attuale.di(figlioId) || attuale.caricoAltre || !attuale.altre) return
        val ultimo = attuale.svolte?.lastOrNull()?.id ?: return
        _stato.value = attuale.copy(caricoAltre = true)
        ambito.launch {
            val esito = fonte()?.leggiSvolteStudio(figlioId, ultimo) ?: EsitoLetturaStudio.Fallita
            val ora = _stato.value
            if (!ora.di(figlioId)) return@launch
            _stato.value = when (esito) {
                is EsitoLetturaStudio.Letta -> ora.copy(
                    caricoAltre = false,
                    svolte = (ora.svolte.orEmpty() + esito.dato.svolte).distinctBy { it.id },
                    altre = esito.dato.altre,
                )
                EsitoLetturaStudio.NonAutorizzato -> {
                    nonPiuCollegato()
                    return@launch
                }
                else -> ora.copy(caricoAltre = false, errore = true)
            }
        }
    }

    /**
     * La risposta alla configurazione in attesa: [esito] `approva` o `rifiuta` (col
     * perché facoltativo, solo per il no), con la [versione] che il genitore aveva
     * sullo schermo. Se nel frattempo è cambiata, il server non decide niente.
     */
    fun rispondi(figlioId: Long?, versione: Int, esito: String, motivazione: String?) {
        val gesto = if (esito == EsitiSessione.APPROVA) GestoStudio.APPROVA else GestoStudio.RIFIUTA
        val perche = motivazione?.trim()?.take(MASSIMO_MOTIVAZIONE_STUDIO)?.ifBlank { null }
            .takeIf { esito == EsitiSessione.RIFIUTA }
        gesto(figlioId, gesto) { postino ->
            when (val risposta = postino.rispondiConfigStudio(figlioId, esito, versione, perche)) {
                is EsitoRispostaStudio.Decisa -> {
                    decisa(figlioId, versione)
                    EventoStudio.Decisa(esito)
                }
                is EsitoRispostaStudio.Cambiata -> EventoStudio.Cambiata
                is EsitoRispostaStudio.Rifiutata -> if (risposta.codice == CodiciErrore.NIENTE_DA_DECIDERE) {
                    decisa(figlioId, versione)
                    EventoStudio.NienteDaDecidere
                } else {
                    EventoStudio.Rifiuto(risposta.codice, gesto, figlioId)
                }
                EsitoRispostaStudio.Fallita -> EventoStudio.Rifiuto(null, gesto, figlioId)
            }
        }
    }

    /** "Chiudi lo Studio": senza condizioni, col [motivo] (già controllato: v. problemaMotivo). */
    fun chiudi(figlioId: Long?, studioId: Long, motivo: String) {
        val pulito = ripulisci(motivo)
        if (problemaMotivo(pulito) != null) return
        gesto(figlioId, GestoStudio.CHIUDI) { postino ->
            when (val esito = postino.chiudiStudio(figlioId, studioId, pulito)) {
                is EsitoChiusuraStudio.Chiuso -> EventoStudio.Chiuso
                is EsitoChiusuraStudio.GiaChiuso -> EventoStudio.GiaChiuso
                is EsitoChiusuraStudio.Rifiutata -> EventoStudio.Rifiuto(esito.codice, GestoStudio.CHIUDI, figlioId)
                EsitoChiusuraStudio.Fallita -> EventoStudio.Rifiuto(null, GestoStudio.CHIUDI, figlioId)
            }
        }
    }

    private fun decisa(figlioId: Long?, versione: Int) {
        _stato.value = _stato.value.copy(decise = _stato.value.decise + (figlioId to versione))
    }

    /**
     * Un gesto sul server, uno alla volta, poi una rilettura se lo stato è di quel
     * figlio. Un 401 butta quello che si sa: il collegamento non vale più.
     */
    private fun gesto(figlioId: Long?, tipo: GestoStudio, azione: suspend (FonteStudio) -> EventoStudio) {
        if (_stato.value.invio) return
        _stato.value = _stato.value.copy(invio = true)
        ambito.launch {
            val postino = fonte()
            val evento = if (postino != null) {
                azione(postino)
            } else {
                EventoStudio.Rifiuto(CodiciErrore.CONFIGURAZIONE_MANCANTE, tipo, figlioId)
            }
            _stato.value = _stato.value.copy(invio = false, evento = evento)
            when {
                evento is EventoStudio.Rifiuto && evento.codice == CodiciErrore.COLLEGAMENTO_NON_VALIDO -> nonPiuCollegato()
                // Una rete caduta non ha cambiato niente; il resto sì: si rilegge.
                evento is EventoStudio.Rifiuto && evento.codice == null -> Unit
                _stato.value.di(figlioId) -> aggiorna(figlioId)
            }
        }
    }

    fun consumaEvento() {
        _stato.value = _stato.value.copy(evento = null)
    }

    private fun nonPiuCollegato() {
        lettura?.cancel()
        _stato.value = _stato.value.copy(
            caricamento = false,
            caricoAltre = false,
            pacco = null,
            versioni = null,
            svolte = null,
            errore = false,
            collegamentoNonValido = true,
        )
    }

    /** Dopo un cambio di server: quello che si sapeva è di un altro collegamento. */
    fun dimentica() {
        lettura?.cancel()
        _stato.value = StatoStudio()
    }
}
