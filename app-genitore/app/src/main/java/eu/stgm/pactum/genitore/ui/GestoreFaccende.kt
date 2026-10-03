package eu.stgm.pactum.genitore.ui

import eu.stgm.pactum.genitore.dati.CodiciErrore
import eu.stgm.pactum.genitore.dati.CorpoFaccenda
import eu.stgm.pactum.genitore.dati.CorpoNuoveFaccende
import eu.stgm.pactum.genitore.dati.Faccenda
import eu.stgm.pactum.genitore.dati.RiferimentoGenitore
import eu.stgm.pactum.genitore.rete.EsitoFaccende
import eu.stgm.pactum.genitore.rete.EsitoFoto
import eu.stgm.pactum.genitore.rete.EsitoScrittura
import eu.stgm.pactum.genitore.rete.FonteFaccende
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

// (0.13) Il motore delle faccende: l'elenco del figlio scelto, i gesti del genitore
// (dai, boccia, annulla) e la foto a tutto schermo. Niente Android: il ViewModel
// (FaccendeViewModel) gli dà lo scope, il server (PostinoClient come FonteFaccende)
// e il modo di trasformare i byte in un'immagine; i test gli danno un server finto.

/** Un esito da dire una volta (snackbar) e poi consumare. */
sealed interface EventoFaccende {
    /** Faccende date: quante. */
    data class Date(val quante: Int) : EventoFaccende
    data object Bocciata : EventoFaccende
    data object Annullata : EventoFaccende

    /** Un gesto che il server non ha preso (o la rete caduta: [codice] null), e su quale figlio. */
    data class Rifiuto(val codice: String?, val gesto: GestoFaccende, val figlioId: Long?) : EventoFaccende
}

/** Perché la foto non si vede. */
enum class ProblemaFoto { NON_TROVATA, ERRORE, SERVER_VECCHIO }

/**
 * La foto aperta a tutto schermo: di quale faccenda e QUALE foto ([fotoTs], l'ora
 * della foto scritta dal server: dopo una bocciatura la stessa faccenda può avere
 * una foto nuova), e l'immagine quando arriva.
 */
data class FotoAperta<I>(
    val faccendaId: Long,
    val fotoTs: String?,
    val caricamento: Boolean = true,
    val immagine: I? = null,
    val problema: ProblemaFoto? = null,
)

data class StatoFaccende<I>(
    /** Di quale figlio parla questo stato. */
    val figlioId: Long? = null,
    /** false finché non si sono chieste le faccende di [figlioId]. */
    val richiesta: Boolean = false,
    val caricamento: Boolean = true,
    /** null = mai lette (per questo figlio). */
    val faccende: List<Faccenda>? = null,
    /** Il server non conosce /api/faccende: per le faccende serve aggiornarlo. */
    val serverVecchio: Boolean = false,
    val configurazioneMancante: Boolean = false,
    /** L'ultima lettura è fallita: l'elenco mostrato è quello di prima. */
    val errore: Boolean = false,
    /**
     * Il server non riconosce più il collegamento di questo telefono (401: un altro
     * genitore l'ha tolto). Faccende e foto in memoria sono state buttate.
     */
    val collegamentoNonValido: Boolean = false,
    val ricevutaAlle: Instant? = null,
    /** Un gesto è in volo: uno alla volta. */
    val invio: Boolean = false,
    val evento: EventoFaccende? = null,
    val foto: FotoAperta<I>? = null,
) {
    /** true = questi dati sono del figlio [id]. */
    fun di(id: Long?): Boolean = richiesta && figlioId == id
}

/** Una foto in memoria: la faccenda E l'ora della foto (una faccenda bocciata ne ha poi un'altra). */
data class ChiaveFoto(val faccendaId: Long, val fotoTs: String)

/**
 * Il motore. [fonte] = il server del collegamento salvato, null se manca;
 * [decodifica] = i byte della foto in un'immagine (null se non lo sono), fatta in
 * [contestoDecodifica]; [orologio] = l'ora del telefono.
 *
 * Come la finestra, lo stato dice SEMPRE di quale figlio sono i dati: dare
 * faccende al figlio sbagliato blocca il telefono sbagliato. Le foto restano SOLO
 * in memoria (le ultime [FOTO_IN_MEMORIA]), e per faccenda E ora della foto.
 */
class GestoreFaccende<I : Any>(
    private val ambito: CoroutineScope,
    private val fonte: suspend () -> FonteFaccende?,
    private val decodifica: (ByteArray) -> I?,
    private val contestoDecodifica: CoroutineContext = EmptyCoroutineContext,
    private val orologio: () -> Instant = Instant::now,
) {
    private data class Ricordate(val faccende: List<Faccenda>, val alle: Instant)

    private val _stato = MutableStateFlow(StatoFaccende<I>())
    val stato: StateFlow<StatoFaccende<I>> = _stato.asStateFlow()
    private val ricordate = mutableMapOf<Long?, Ricordate>()
    private var lettura: Job? = null
    private var scaricamento: Job? = null

    /** Le ultime foto viste, solo in memoria: riaprirle non le riscarica. */
    private val fotoInMemoria = object : LinkedHashMap<ChiaveFoto, I>(FOTO_IN_MEMORIA, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ChiaveFoto, I>?): Boolean =
            size > FOTO_IN_MEMORIA
    }

    /** Quante foto sono in memoria adesso (per i test). */
    internal val fotoTenute: Set<ChiaveFoto> get() = fotoInMemoria.keys.toSet()

    /** Rilegge le faccende di [figlioId]. Se il figlio cambia, si riparte da quello che si ricorda di lui. */
    fun aggiorna(figlioId: Long?) {
        val prima = _stato.value
        _stato.value = if (!prima.richiesta || prima.figlioId != figlioId) {
            val ricordata = ricordate[figlioId]
            StatoFaccende(
                figlioId = figlioId,
                richiesta = true,
                caricamento = true,
                faccende = ricordata?.faccende,
                ricevutaAlle = ricordata?.alle,
                collegamentoNonValido = prima.collegamentoNonValido,
                // Un gesto in volo resta in volo, e la foto aperta resta aperta.
                invio = prima.invio,
                evento = prima.evento,
                foto = prima.foto,
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
            when (val esito = postino.leggiFaccende(figlioId)) {
                is EsitoFaccende.Lette -> {
                    val adesso = orologio()
                    ricordate[figlioId] = Ricordate(esito.faccende, adesso)
                    dimenticaFotoCambiate(esito.faccende)
                    _stato.value = _stato.value.copy(
                        caricamento = false,
                        faccende = esito.faccende,
                        serverVecchio = false,
                        configurazioneMancante = false,
                        collegamentoNonValido = false,
                        errore = false,
                        ricevutaAlle = adesso,
                    )
                    riconciliaFotoAperta(esito.faccende)
                }
                EsitoFaccende.ServerVecchio -> _stato.value = _stato.value.copy(
                    caricamento = false,
                    faccende = emptyList(),
                    serverVecchio = true,
                    configurazioneMancante = false,
                    errore = false,
                )
                EsitoFaccende.NonAutorizzato -> nonPiuCollegato()
                EsitoFaccende.Fallita -> _stato.value = _stato.value.copy(caricamento = false, errore = true)
            }
        }
    }

    /**
     * "Dai faccende": [titoli] (da 1 a 10, già controllati), la [nota] uguale per
     * tutte (facoltativa), da quando bloccano ([bloccoDa], null = subito) e chi sei
     * tu ([io], per riconoscere le tue se la risposta si perde). Una creazione,
     * senza ritentativi automatici: se la rete cade si rilegge l'elenco e decide
     * [esitoDopoRispostaPersa] (con l'elenco mai letto prima: incerto).
     */
    fun daiFaccende(figlioId: Long, titoli: List<String>, nota: String?, bloccoDa: String?, io: RiferimentoGenitore?) {
        if (_stato.value.invio) return
        val puliti = titoli.map { ripulisci(it) }.filter { it.isNotEmpty() }
        if (puliti.isEmpty()) return
        val notaPulita = nota?.let { ripulisci(it) }?.ifEmpty { null }
        val prima = (if (_stato.value.di(figlioId)) _stato.value.faccende else ricordate[figlioId]?.faccende)
            ?.map { it.id }
        val inizio = orologio()
        gesto(figlioId) { postino ->
            val corpo = CorpoNuoveFaccende(
                figlioId = figlioId,
                faccende = puliti.map { CorpoFaccenda(titolo = it, nota = notaPulita) },
                bloccoDa = bloccoDa,
            )
            when (val esito = postino.daiFaccende(corpo)) {
                is EsitoScrittura.Riuscito -> EventoFaccende.Date(esito.dato?.size?.takeIf { it > 0 } ?: puliti.size)
                is EsitoScrittura.Rifiutato -> EventoFaccende.Rifiuto(esito.errore, GestoFaccende.DAI, figlioId)
                EsitoScrittura.Fallito -> {
                    val dopo = (postino.leggiFaccende(figlioId) as? EsitoFaccende.Lette)?.faccende
                    when (val risultato = esitoDopoRispostaPersa(prima, dopo, puliti, io, inizio)) {
                        is DopoRispostaPersa.Date -> EventoFaccende.Date(risultato.quante)
                        DopoRispostaPersa.Incerto ->
                            EventoFaccende.Rifiuto(CodiciErrore.ESITO_INCERTO, GestoFaccende.DAI, figlioId)
                        DopoRispostaPersa.NonDate -> EventoFaccende.Rifiuto(null, GestoFaccende.DAI, figlioId)
                    }
                }
            }
        }
    }

    /**
     * "Boccia": la foto non va, la faccenda torna da fare e il blocco riparte
     * subito. La [nota] (facoltativa) la legge il figlio. La foto si toglie dalla
     * memoria e, se è aperta, si chiude: il server la cancella.
     */
    fun boccia(figlioId: Long?, faccenda: Faccenda, nota: String?) {
        val notaPulita = nota?.let { ripulisci(it) }?.ifEmpty { null }
        gesto(figlioId) { postino ->
            when (val esito = postino.bocciaFaccenda(faccenda.id, notaPulita)) {
                is EsitoScrittura.Riuscito -> {
                    dimenticaFotoDi(faccenda.id)
                    if (_stato.value.foto?.faccendaId == faccenda.id) _stato.value = _stato.value.copy(foto = null)
                    EventoFaccende.Bocciata
                }
                is EsitoScrittura.Rifiutato -> EventoFaccende.Rifiuto(esito.errore, GestoFaccende.BOCCIA, figlioId)
                EsitoScrittura.Fallito -> EventoFaccende.Rifiuto(null, GestoFaccende.BOCCIA, figlioId)
            }
        }
    }

    /** "Annulla": solo una faccenda da fare. Se era l'ultima che bloccava, il blocco finisce. */
    fun annulla(figlioId: Long?, faccenda: Faccenda) = gesto(figlioId) { postino ->
        when (val esito = postino.annullaFaccenda(faccenda.id)) {
            is EsitoScrittura.Riuscito -> EventoFaccende.Annullata
            is EsitoScrittura.Rifiutato -> EventoFaccende.Rifiuto(esito.errore, GestoFaccende.ANNULLA, figlioId)
            EsitoScrittura.Fallito -> EventoFaccende.Rifiuto(null, GestoFaccende.ANNULLA, figlioId)
        }
    }

    /**
     * Un gesto sul server, uno alla volta (un doppio tocco non manda due volte), poi
     * SEMPRE una rilettura dell'elenco di quel figlio, se è ancora lui sullo schermo.
     * Un 401 butta faccende e foto: il collegamento di questo telefono non vale più.
     */
    private fun gesto(figlioId: Long?, azione: suspend (FonteFaccende) -> EventoFaccende) {
        if (_stato.value.invio) return
        _stato.value = _stato.value.copy(invio = true)
        ambito.launch {
            val postino = fonte()
            val evento = if (postino != null) {
                azione(postino)
            } else {
                EventoFaccende.Rifiuto(null, GestoFaccende.DAI, figlioId)
            }
            _stato.value = _stato.value.copy(invio = false, evento = evento)
            if (evento is EventoFaccende.Rifiuto && evento.codice == CodiciErrore.COLLEGAMENTO_NON_VALIDO) {
                nonPiuCollegato()
            } else if (_stato.value.di(figlioId)) {
                aggiorna(figlioId)
            } else {
                ricordate.remove(figlioId)
            }
        }
    }

    fun consumaEvento() {
        _stato.value = _stato.value.copy(evento = null)
    }

    // --- La foto a tutto schermo ---------------------------------------------------------

    /**
     * Apre la foto [fotoTs] della faccenda [faccendaId]: dalla memoria se c'è già
     * QUELLA foto, se no la scarica. Senza [fotoTs] (non si sa ancora quale) la si
     * scarica e non la si tiene: alla prossima lettura dell'elenco si sa quale è.
     */
    fun apriFoto(faccendaId: Long, fotoTs: String?) {
        val chiave = fotoTs?.let { ChiaveFoto(faccendaId, it) }
        val inMemoria = chiave?.let { fotoInMemoria[it] }
        scaricamento?.cancel()
        if (inMemoria != null) {
            _stato.value = _stato.value.copy(foto = FotoAperta(faccendaId, fotoTs, caricamento = false, immagine = inMemoria))
            return
        }
        _stato.value = _stato.value.copy(foto = FotoAperta(faccendaId, fotoTs))
        scaricamento = ambito.launch {
            val esito = fonte()?.scaricaFoto(faccendaId) ?: EsitoFoto.Fallita
            if (esito == EsitoFoto.NonAutorizzato) {
                nonPiuCollegato()
                return@launch
            }
            val aperta: FotoAperta<I> = when (esito) {
                is EsitoFoto.Arrivata -> {
                    val immagine = withContext(contestoDecodifica) { decodifica(esito.byte) }
                    if (immagine != null) {
                        if (chiave != null) fotoInMemoria[chiave] = immagine
                        FotoAperta(faccendaId, fotoTs, caricamento = false, immagine = immagine)
                    } else {
                        FotoAperta(faccendaId, fotoTs, caricamento = false, problema = ProblemaFoto.ERRORE)
                    }
                }
                EsitoFoto.NonTrovata -> FotoAperta(faccendaId, fotoTs, caricamento = false, problema = ProblemaFoto.NON_TROVATA)
                EsitoFoto.ServerVecchio -> FotoAperta(faccendaId, fotoTs, caricamento = false, problema = ProblemaFoto.SERVER_VECCHIO)
                else -> FotoAperta(faccendaId, fotoTs, caricamento = false, problema = ProblemaFoto.ERRORE)
            }
            // Nel frattempo il genitore può aver chiuso, o aperto un'altra foto.
            val attuale = _stato.value.foto
            if (attuale?.faccendaId == faccendaId && attuale.fotoTs == fotoTs) _stato.value = _stato.value.copy(foto = aperta)
        }
    }

    fun chiudiFoto() {
        scaricamento?.cancel()
        _stato.value = _stato.value.copy(foto = null)
    }

    /**
     * Le foto in memoria che l'elenco dice cambiate: una faccenda bocciata (e poi
     * rifatta) ha un'altra ora della foto, o nessuna; una foto cancellata dopo 30
     * giorni non c'è più. Resta solo quella che l'elenco dice attuale.
     */
    private fun dimenticaFotoCambiate(faccende: List<Faccenda>) {
        val perId = faccende.associateBy { it.id }
        fotoInMemoria.keys.removeAll { chiave ->
            val faccenda = perId[chiave.faccendaId] ?: return@removeAll false
            !faccenda.foto || faccenda.fotoTs != chiave.fotoTs
        }
    }

    private fun dimenticaFotoDi(faccendaId: Long) {
        fotoInMemoria.keys.removeAll { it.faccendaId == faccendaId }
    }

    /**
     * La foto aperta non è più quella della faccenda (bocciata da un altro genitore,
     * e magari rifatta): se ce n'è una nuova la si scarica al suo posto; se non c'è,
     * lo si dice. Mai una foto vecchia con "Boccia" per quella nuova.
     */
    private fun riconciliaFotoAperta(faccende: List<Faccenda>) {
        val aperta = _stato.value.foto ?: return
        val faccenda = faccende.firstOrNull { it.id == aperta.faccendaId } ?: return
        if (faccenda.fotoTs == aperta.fotoTs) return
        val nuova = faccenda.fotoTs
        if (nuova != null && faccenda.foto) {
            apriFoto(faccenda.id, nuova)
        } else {
            scaricamento?.cancel()
            _stato.value = _stato.value.copy(
                foto = FotoAperta(faccenda.id, nuova, caricamento = false, problema = ProblemaFoto.NON_TROVATA),
            )
        }
    }

    /** 401: il collegamento di questo telefono non vale più. Via faccende e foto in memoria. */
    private fun nonPiuCollegato() {
        lettura?.cancel()
        scaricamento?.cancel()
        ricordate.clear()
        fotoInMemoria.clear()
        _stato.value = _stato.value.copy(
            caricamento = false,
            faccende = null,
            foto = null,
            errore = false,
            collegamentoNonValido = true,
        )
    }

    /** Dopo un cambio di server: le faccende e le foto di prima sono di un altro collegamento. */
    fun dimentica() {
        lettura?.cancel()
        scaricamento?.cancel()
        ricordate.clear()
        fotoInMemoria.clear()
        _stato.value = StatoFaccende()
    }

    companion object {
        /** Quante foto si tengono in memoria al massimo. */
        const val FOTO_IN_MEMORIA = 4
    }
}
