package eu.stgm.pactum.genitore.ui

import eu.stgm.pactum.genitore.dati.CodiciErrore
import eu.stgm.pactum.genitore.dati.CorpoFaccenda
import eu.stgm.pactum.genitore.dati.CorpoNuoveFaccende
import eu.stgm.pactum.genitore.dati.Faccenda
import eu.stgm.pactum.genitore.dati.RiferimentoGenitore
import eu.stgm.pactum.genitore.dati.StatiFaccenda
import eu.stgm.pactum.genitore.rete.EsitoFaccende
import eu.stgm.pactum.genitore.rete.EsitoFoto
import eu.stgm.pactum.genitore.rete.EsitoRicercaFaccende
import eu.stgm.pactum.genitore.rete.EsitoScrittura
import eu.stgm.pactum.genitore.rete.FonteFaccende
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    /** (0.17) Il lavoro è stato cambiato. */
    data object Modificata : EventoFaccende

    /** (0.17) "Salva" senza nessun cambio: il server non si chiama. */
    data object NessunCambio : EventoFaccende

    /** (0.17) Il lavoro è segnato come svolto. */
    data object Confermata : EventoFaccende

    /** Un gesto che il server non ha preso (o la rete caduta: [codice] null), e su quale figlio. */
    data class Rifiuto(val codice: String?, val gesto: GestoFaccende, val figlioId: Long?) : EventoFaccende
}

/** Perché la foto non si vede. */
enum class ProblemaFoto { NON_TROVATA, ERRORE, SERVER_VECCHIO }

/** (0.17) Perché la ricerca non ha risultati da mostrare. */
enum class ProblemaRicerca { SENZA_RETE, SERVER_VECCHIO, ERRORE }

/**
 * (0.17, contratto v3.9) La ricerca nei lavori di casa di [figlioId]: il [testo]
 * cercato, i [risultati] (null = non ancora arrivati), se ce ne sono [altre] oltre
 * i 50, o il [problema].
 */
data class StatoRicerca(
    val figlioId: Long?,
    val testo: String,
    val caricamento: Boolean = true,
    val risultati: List<Faccenda>? = null,
    val altre: Boolean = false,
    val problema: ProblemaRicerca? = null,
)

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
    /**
     * (0.17) Il server sa modificare e confermare i lavori (v3.9). false = server
     * più vecchio: niente "Modifica" né "Segna come svolto".
     */
    val conModifiche: Boolean = true,
    /** (0.17) La ricerca nello storico, se se ne sta facendo una. */
    val ricerca: StatoRicerca? = null,
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
    /** (0.17) Una foto è stata guardata su questo telefono (si ricorda per "Segna come svolto"). */
    private val fotoGuardata: (ChiaveFoto) -> Unit = {},
    /** (0.17) Quanto si aspetta dopo l'ultima lettera prima di cercare. */
    private val attesaRicerca: Long = ATTESA_RICERCA_MS,
) {
    private data class Ricordate(val faccende: List<Faccenda>, val alle: Instant)

    private val _stato = MutableStateFlow(StatoFaccende<I>())
    val stato: StateFlow<StatoFaccende<I>> = _stato.asStateFlow()
    private val ricordate = mutableMapOf<Long?, Ricordate>()
    private var lettura: Job? = null
    private var scaricamento: Job? = null
    private var cercando: Job? = null

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
                // (0.17) Quello che si sa del server non cambia col figlio: un server
                // vecchio resta vecchio finché un elenco non dice il contrario.
                conModifiche = prima.conModifiche,
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
                        conModifiche = esito.conModifiche ?: _stato.value.conModifiche,
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
        gesto(figlioId, GestoFaccende.DAI) { postino ->
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
        gesto(figlioId, GestoFaccende.BOCCIA) { postino ->
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
    fun annulla(figlioId: Long?, faccenda: Faccenda) = gesto(figlioId, GestoFaccende.ANNULLA) { postino ->
        when (val esito = postino.annullaFaccenda(faccenda.id)) {
            is EsitoScrittura.Riuscito -> EventoFaccende.Annullata
            is EsitoScrittura.Rifiutato -> EventoFaccende.Rifiuto(esito.errore, GestoFaccende.ANNULLA, figlioId)
            EsitoScrittura.Fallito -> EventoFaccende.Rifiuto(null, GestoFaccende.ANNULLA, figlioId)
        }
    }

    /**
     * (0.17) "Modifica": titolo, nota e ora del blocco di un lavoro ancora da fare,
     * solo quello che cambia ([modifica]). Senza cambi il server non si chiama. Un
     * server più vecchio della v3.9 lo dice, e "Modifica" non si offre più.
     */
    fun modifica(figlioId: Long?, faccenda: Faccenda, modifica: ModificaFaccenda) {
        if (_stato.value.invio) return
        if (modifica.vuota) {
            _stato.value = _stato.value.copy(evento = EventoFaccende.NessunCambio)
            return
        }
        gesto(figlioId, GestoFaccende.MODIFICA) { postino ->
            when (val esito = postino.modificaFaccenda(faccenda.id, corpoModifica(modifica))) {
                // Il server può dire sì senza cambiare niente (gli stessi valori): si dice così.
                is EsitoScrittura.Riuscito ->
                    if (esito.dato?.let { stessiValori(faccenda, it) } == true) EventoFaccende.NessunCambio else EventoFaccende.Modificata
                is EsitoScrittura.Rifiutato -> {
                    if (esito.errore == CodiciErrore.SERVER_DA_AGGIORNARE) senzaModifiche()
                    // Non più modificabile: arrivata la foto, o tolto? Si rilegge e si dice quale.
                    val codice = if (esito.errore == CodiciErrore.NON_MODIFICABILE) {
                        motivoNonModificabile(postino, figlioId, faccenda.id)
                    } else {
                        esito.errore
                    }
                    EventoFaccende.Rifiuto(codice, GestoFaccende.MODIFICA, figlioId)
                }
                EsitoScrittura.Fallito -> EventoFaccende.Rifiuto(null, GestoFaccende.MODIFICA, figlioId)
            }
        }
    }

    /** Il perché di un `non_modificabile`, riletto l'elenco: arrivata la foto, tolto, o non si sa. */
    private suspend fun motivoNonModificabile(postino: FonteFaccende, figlioId: Long?, faccendaId: Long): String {
        val ora = (postino.leggiFaccende(figlioId) as? EsitoFaccende.Lette)?.faccende?.firstOrNull { it.id == faccendaId }
        return when (ora?.stato) {
            StatiFaccenda.FATTA -> CodiciErrore.NON_MODIFICABILE
            StatiFaccenda.ANNULLATA -> CodiciErrore.LAVORO_TOLTO
            else -> CodiciErrore.LAVORO_NON_PIU_DA_FARE
        }
    }

    /**
     * (0.17) "Segna come svolto": la conferma del genitore, per la foto [fotoVista]
     * (quella guardata su questo telefono): se intanto è cambiata il server dice
     * `foto_cambiata` e non conferma niente. Lo sblocco non cambia (è già avvenuto
     * all'ultima foto); dopo, il lavoro non si può più bocciare.
     */
    fun conferma(figlioId: Long?, faccenda: Faccenda, fotoVista: String?) = gesto(figlioId, GestoFaccende.CONFERMA) { postino ->
        when (val esito = postino.confermaFaccenda(faccenda.id, fotoVista)) {
            is EsitoScrittura.Riuscito -> EventoFaccende.Confermata
            is EsitoScrittura.Rifiutato -> {
                if (esito.errore == CodiciErrore.SERVER_DA_AGGIORNARE) senzaModifiche()
                EventoFaccende.Rifiuto(esito.errore, GestoFaccende.CONFERMA, figlioId)
            }
            EsitoScrittura.Fallito -> EventoFaccende.Rifiuto(null, GestoFaccende.CONFERMA, figlioId)
        }
    }

    /** true = il server ha risposto con gli stessi titolo, nota e ora del blocco di prima. */
    private fun stessiValori(prima: Faccenda, dopo: Faccenda): Boolean =
        ripulisci(prima.titolo) == ripulisci(dopo.titolo) &&
            ripulisci(prima.nota.orEmpty()) == ripulisci(dopo.nota.orEmpty()) &&
            istanteServer(prima.bloccoDa) == istanteServer(dopo.bloccoDa)

    private fun senzaModifiche() {
        _stato.value = _stato.value.copy(conModifiche = false)
    }

    // --- (0.17) La ricerca nello storico ----------------------------------------------

    /**
     * Cerca [testo] nei lavori di [figlioId], in TUTTA la storia (contratto v3.9),
     * dopo [attesaRicerca] dall'ultima lettera: ogni lettera nuova annulla la
     * ricerca di prima, e una risposta arrivata per un testo (o un figlio) che non
     * è più quello cercato si butta. Un testo vuoto chiude la ricerca.
     */
    fun cerca(figlioId: Long?, testo: String) {
        val pulito = testoDaCercare(testo)
        cercando?.cancel()
        if (pulito == null) {
            _stato.value = _stato.value.copy(ricerca = null)
            return
        }
        val prima = _stato.value.ricerca?.takeIf { it.figlioId == figlioId }
        _stato.value = _stato.value.copy(
            ricerca = StatoRicerca(figlioId, pulito, caricamento = true, risultati = prima?.risultati, altre = prima?.altre ?: false),
        )
        cercando = ambito.launch {
            if (attesaRicerca > 0) delay(attesaRicerca)
            val postino = fonte()
            val esito = postino?.cercaFaccende(figlioId, pulito) ?: EsitoRicercaFaccende.SenzaRete
            val attuale = _stato.value.ricerca
            if (attuale == null || attuale.figlioId != figlioId || attuale.testo != pulito) return@launch
            _stato.value = _stato.value.copy(
                ricerca = when (esito) {
                    is EsitoRicercaFaccende.Trovate ->
                        attuale.copy(caricamento = false, risultati = esito.faccende, altre = esito.altre, problema = null)
                    EsitoRicercaFaccende.ServerVecchio ->
                        attuale.copy(caricamento = false, risultati = null, altre = false, problema = ProblemaRicerca.SERVER_VECCHIO)
                    EsitoRicercaFaccende.NonAutorizzato -> {
                        nonPiuCollegato()
                        return@launch
                    }
                    EsitoRicercaFaccende.SenzaRete ->
                        attuale.copy(caricamento = false, risultati = null, altre = false, problema = ProblemaRicerca.SENZA_RETE)
                    EsitoRicercaFaccende.Errore ->
                        attuale.copy(caricamento = false, risultati = null, altre = false, problema = ProblemaRicerca.ERRORE)
                },
            )
        }
    }

    /** Dopo un gesto, la ricerca aperta si rifà (subito): i risultati dicono lo stato nuovo. */
    private fun rifaiRicerca() {
        val ricerca = _stato.value.ricerca ?: return
        cerca(ricerca.figlioId, ricerca.testo)
    }

    /**
     * Un gesto sul server, uno alla volta (un doppio tocco non manda due volte), poi
     * SEMPRE una rilettura dell'elenco di quel figlio, se è ancora lui sullo schermo.
     * Un 401 butta faccende e foto: il collegamento di questo telefono non vale più.
     */
    private fun gesto(figlioId: Long?, tipo: GestoFaccende, azione: suspend (FonteFaccende) -> EventoFaccende) {
        if (_stato.value.invio) return
        _stato.value = _stato.value.copy(invio = true)
        ambito.launch {
            val postino = fonte()
            val evento = if (postino != null) {
                azione(postino)
            } else {
                // (0.17) Senza collegamento il gesto non parte: lo si dice, col gesto giusto.
                EventoFaccende.Rifiuto(CodiciErrore.CONFIGURAZIONE_MANCANTE, tipo, figlioId)
            }
            _stato.value = _stato.value.copy(invio = false, evento = evento)
            if (evento is EventoFaccende.Rifiuto && evento.codice == CodiciErrore.COLLEGAMENTO_NON_VALIDO) {
                nonPiuCollegato()
            } else if (_stato.value.di(figlioId)) {
                aggiorna(figlioId)
                rifaiRicerca()
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
            fotoGuardata(chiave)
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
                        // (0.17) Guardata QUI: il pulsante diventa "Segna come svolto".
                        if (chiave != null) fotoGuardata(chiave)
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
        cercando?.cancel()
        ricordate.clear()
        fotoInMemoria.clear()
        _stato.value = _stato.value.copy(
            caricamento = false,
            faccende = null,
            foto = null,
            ricerca = null,
            errore = false,
            collegamentoNonValido = true,
        )
    }

    /** Dopo un cambio di server: le faccende e le foto di prima sono di un altro collegamento. */
    fun dimentica() {
        lettura?.cancel()
        scaricamento?.cancel()
        cercando?.cancel()
        ricordate.clear()
        fotoInMemoria.clear()
        _stato.value = StatoFaccende()
    }

    companion object {
        /** Quante foto si tengono in memoria al massimo. */
        const val FOTO_IN_MEMORIA = 4
    }
}
