package eu.stgm.pactum.genitore.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.CodiciErrore
import eu.stgm.pactum.genitore.dati.Dispositivo
import eu.stgm.pactum.genitore.dati.Famiglia
import eu.stgm.pactum.genitore.dati.Figlio
import eu.stgm.pactum.genitore.dati.Genitore
import eu.stgm.pactum.genitore.dati.Impostazioni
import eu.stgm.pactum.genitore.dati.RiferimentoGenitore
import eu.stgm.pactum.genitore.dati.TipiDispositivo
import eu.stgm.pactum.genitore.rete.CodiceGenitoreRicevuto
import eu.stgm.pactum.genitore.rete.CodiceRicevuto
import eu.stgm.pactum.genitore.rete.EsitoFamiglia
import eu.stgm.pactum.genitore.rete.EsitoGenitori
import eu.stgm.pactum.genitore.rete.EsitoScrittura
import eu.stgm.pactum.genitore.rete.PostinoClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import java.time.Duration

/**
 * La famiglia (v3): chi sono i figli, quali dispositivi hanno, quale figlio è
 * scelto in cima alle schermate, e i gesti delle Impostazioni (aggiungi figlio,
 * rinomina, aggiungi dispositivo, nuovo codice, scollega).
 *
 * Vive nello scope dell'attività: Panoramica, Tempo, "Proposte e conferme",
 * Impostazioni e notifiche vedono LA STESSA famiglia e LA STESSA scelta.
 *
 * Un server 0.7 non conosce GET /api/famiglia (404): [StatoFamiglia.serverVecchio]
 * e l'app lavora come la 0.7 — nessuna scelta del figlio, nessun `figlio_id`.
 */
class FamigliaViewModel(application: Application) : AndroidViewModel(application) {

    /** Il codice di 6 cifre da mostrare in grande, finché il genitore non chiude. */
    data class CodiceMostrato(
        val dispositivoId: Long?,
        val nomeDispositivo: String,
        val tipo: String,
        val codice: String,
        /** Quanto valeva il codice quando è arrivata la risposta, secondo il server (v. validitaCodice). */
        val validita: Duration,
        /**
         * Quando è arrivata la risposta, sull'orologio monotono del telefono
         * (SystemClock.elapsedRealtime): il conto alla rovescia parte da qui, e
         * non salta se qualcuno cambia l'ora del telefono.
         */
        val ricevutoMs: Long,
        /** Il dispositivo era già collegato: il codice lo ricollega. */
        val ricollegamento: Boolean,
        /**
         * (0.13) Il codice è per il telefono di un GENITORE (contratto v3.6), non per
         * un dispositivo del figlio: allora [dispositivoId] è null e il genitore è
         * [genitoreId]; [nomeDispositivo] è il suo nome.
         */
        val genitoreId: Long? = null,
    ) {
        /** (0.13) true = un codice per un genitore. */
        val perGenitore: Boolean get() = genitoreId != null

        /** I secondi che restano a [adessoMs] (orologio monotono); 0 = scaduto. */
        fun rimasti(adessoMs: Long = SystemClock.elapsedRealtime()): Long =
            secondiRimasti(validita, adessoMs - ricevutoMs)
    }

    /** Un esito da dire una volta (snackbar) e poi consumare. */
    sealed interface Evento {
        data class Fatto(val messaggio: Int) : Evento

        /** [suGenitore] (0.13): il gesto era su un genitore (un 404 lo dice così). */
        data class Errore(val codice: String?, val secondi: Long?, val suGenitore: Boolean = false) : Evento
    }

    data class StatoFamiglia(
        /** Si sa già chi mostrare: famiglia ricordata, risposta del server o server 0.7. */
        val pronta: Boolean = false,
        /** Il server non conosce la famiglia (0.7): un figlio, un dispositivo, come prima. */
        val serverVecchio: Boolean = false,
        val figli: List<Figlio> = emptyList(),
        /** La scelta salvata; può non esserci più (vale allora il primo figlio). */
        val sceltoSalvato: Long? = null,
        /** L'ultima lettura è fallita: la famiglia mostrata è quella di prima. */
        val errore: Boolean = false,
        /** Almeno una lettura dal server è andata (o il server è 0.7). */
        val lettaDalServer: Boolean = false,
        val configurazioneMancante: Boolean = false,
        /** Un gesto sulla famiglia è in volo: i pulsanti si spengono. */
        val lavoroInCorso: Boolean = false,
        val codice: CodiceMostrato? = null,
        val evento: Evento? = null,
        /**
         * (0.13) Chi sei tu (contratto v3.6: `io` di GET /api/famiglia e di GET
         * /api/genitori). null = server più vecchio, o non ancora letto: allora
         * tutto quello che ha fatto "il genitore" è tuo, come prima.
         */
        val io: RiferimentoGenitore? = null,
        /** (0.13) I genitori, revocati compresi (GET /api/genitori): solo dove servono, nelle Impostazioni. */
        val genitori: List<Genitore> = emptyList(),
        /** (0.13) Almeno una lettura dei genitori è andata. */
        val genitoriLetti: Boolean = false,
        /** (0.13) Il server non conosce /api/genitori: per più genitori serve aggiornarlo. */
        val genitoriServerVecchio: Boolean = false,
        /** (0.13) L'ultima lettura dei genitori è fallita: quelli mostrati sono di prima. */
        val genitoriErrore: Boolean = false,
        /**
         * (0.13) Il server non riconosce più il collegamento di questo telefono (401:
         * un altro genitore l'ha tolto, o il codice è sbagliato). Non è la rete, e lo
         * si dice per quello che è.
         */
        val collegamentoNonValido: Boolean = false,
    ) {
        val figlioScelto: Figlio? get() = figlioEffettivo(figli, sceltoSalvato)

        /**
         * Il figlio da passare al server. null = server 0.7, oppure famiglia mai
         * letta e nessuna scelta salvata: la richiesta parte senza `figlio_id` e
         * il server risponde per il primo figlio.
         */
        val figlioId: Long?
            get() = if (serverVecchio) null else figlioScelto?.id ?: sceltoSalvato

        val piuFigli: Boolean get() = figli.size > 1
    }

    private val impostazioni = Impostazioni(application)
    private val _stato = MutableStateFlow(StatoFamiglia())
    val stato: StateFlow<StatoFamiglia> = _stato.asStateFlow()
    private var lettura: Job? = null

    init {
        viewModelScope.launch {
            // Prima la famiglia ricordata e la scelta salvata: nomi e scelta ci
            // sono subito, anche senza rete. Poi il server.
            val salvato = impostazioni.figlioScelto.first()
            val ricordata = impostazioni.leggiFamigliaLetta()?.let(::decodificaFamiglia)
            // Se nel frattempo il server ha già risposto, vale la sua famiglia:
            // quella ricordata è più vecchia per definizione.
            val attuale = _stato.value
            _stato.value = attuale.copy(
                sceltoSalvato = attuale.sceltoSalvato ?: salvato,
                figli = if (attuale.lettaDalServer || ricordata == null) attuale.figli else ricordata.figli,
                pronta = attuale.pronta || ricordata != null,
                io = attuale.io ?: ricordata?.io,
            )
            aggiorna()
        }
    }

    fun aggiorna() {
        lettura?.cancel()
        lettura = viewModelScope.launch {
            val configurazione = impostazioni.leggiConfigurazione()
            if (!configurazione.completa) {
                _stato.value = _stato.value.copy(
                    pronta = true,
                    configurazioneMancante = true,
                    figli = emptyList(),
                    serverVecchio = false,
                )
                return@launch
            }
            when (val esito = PostinoClient(configurazione).leggiFamiglia()) {
                is EsitoFamiglia.Letta -> {
                    impostazioni.registraVerificaRiuscita()
                    impostazioni.salvaFamigliaLetta(
                        PostinoClient.json.encodeToString(Famiglia.serializer(), esito.famiglia),
                    )
                    _stato.value = _stato.value.copy(
                        pronta = true,
                        serverVecchio = false,
                        figli = esito.famiglia.figli,
                        errore = false,
                        lettaDalServer = true,
                        configurazioneMancante = false,
                        collegamentoNonValido = false,
                        // (0.13) Chi sei tu: un server più vecchio della v3.6 non lo dice.
                        io = esito.famiglia.io,
                    )
                }
                EsitoFamiglia.ServerVecchio -> {
                    impostazioni.salvaFamigliaLetta(null)
                    _stato.value = _stato.value.copy(
                        pronta = true,
                        serverVecchio = true,
                        figli = emptyList(),
                        errore = false,
                        lettaDalServer = true,
                        configurazioneMancante = false,
                        collegamentoNonValido = false,
                        io = null,
                    )
                }
                // (0.13) 401: il collegamento di questo telefono non vale più (un altro
                // genitore l'ha tolto). Non è la rete: si dice per quello che è.
                EsitoFamiglia.NonAutorizzato -> _stato.value = _stato.value.copy(
                    pronta = true,
                    errore = false,
                    collegamentoNonValido = true,
                    configurazioneMancante = false,
                )
                // Si tiene la famiglia di prima (o quella ricordata): la scelta resta
                // valida, e le schermate dicono da sole che i dati non sono aggiornati.
                EsitoFamiglia.Fallita -> _stato.value = _stato.value.copy(
                    pronta = true,
                    errore = true,
                    configurazioneMancante = false,
                )
            }
        }
    }

    /**
     * Dopo un cambio di server o di codice d'accesso: la famiglia di prima è di
     * un altro server. Si dimentica tutto (anche la scelta, già cancellata da
     * Impostazioni.salvaConfigurazione) e si rilegge.
     */
    fun ricomincia() {
        lettura?.cancel()
        letturaGenitori?.cancel()
        _stato.value = StatoFamiglia()
        viewModelScope.launch {
            // Se il server è lo stesso la scelta è ancora salvata e resta valida.
            _stato.value = _stato.value.copy(sceltoSalvato = impostazioni.figlioScelto.first())
            aggiorna()
        }
    }

    /**
     * Una rilettura della famiglia, ma solo se non ce n'è già una in volo: chi la
     * chiede a intervalli (il dialogo del codice) non deve interrompere a ogni
     * giro una lettura lenta, che così non arriverebbe mai.
     */
    fun rileggiSeLibera() {
        if (lettura?.isActive == true) return
        aggiorna()
    }

    /** La scelta del figlio in cima alle schermate: resta ricordata. */
    fun scegli(figlioId: Long) {
        _stato.value = _stato.value.copy(sceltoSalvato = figlioId)
        viewModelScope.launch { impostazioni.salvaFiglioScelto(figlioId) }
    }

    // --- I gesti della sezione Famiglia -------------------------------------------
    // Le creazioni (figlio, dispositivo) non si ritentano da sole (PostinoClient,
    // httpCreazioni). Se la rete cade, la richiesta può essere arrivata al server
    // anche se la risposta si è persa: prima di dire "riprova" si rilegge la
    // famiglia. Se la cosa c'è, si mostra quella; se non c'è, riprovare è sicuro;
    // se non si riesce a rileggere, si dice di guardare la lista prima di riprovare.

    fun creaFiglio(nome: String) = gesto { postino ->
        val pulito = nome.trim()
        val prima = _stato.value.figli
        when (val esito = postino.creaFiglio(pulito)) {
            is EsitoScrittura.Riuscito -> Evento.Fatto(R.string.famiglia_figlio_aggiunto)
            is EsitoScrittura.Rifiutato -> errore(esito)
            EsitoScrittura.Fallito -> {
                val dopo = famigliaDopoErrore(postino)
                when {
                    dopo == null -> Evento.Errore(CodiciErrore.ESITO_INCERTO, null)
                    figlioCreato(prima, dopo, pulito) != null -> Evento.Fatto(R.string.famiglia_figlio_aggiunto)
                    else -> Evento.Errore(null, null)
                }
            }
        }
    }

    fun rinominaFiglio(figlioId: Long, nome: String) = gesto { postino ->
        when (val esito = postino.rinominaFiglio(figlioId, nome.trim())) {
            is EsitoScrittura.Riuscito -> Evento.Fatto(R.string.famiglia_figlio_rinominato)
            else -> errore(esito)
        }
    }

    fun aggiungiDispositivo(figlioId: Long, nome: String, tipo: String) = gesto { postino ->
        val pulito = nome.trim()
        val prima = _stato.value.figli
        when (val esito = postino.creaDispositivo(figlioId, pulito, tipo)) {
            is EsitoScrittura.Riuscito -> {
                mostraCodice(esito.dato, nomeRiserva = pulito, tipoRiserva = tipo, ricollegamento = false)
                null
            }
            is EsitoScrittura.Rifiutato -> errore(esito)
            EsitoScrittura.Fallito -> {
                val dopo = famigliaDopoErrore(postino)
                    ?: return@gesto Evento.Errore(CodiciErrore.ESITO_INCERTO, null)
                val creato = dispositivoCreato(prima, dopo, figlioId, pulito, tipo)
                    ?: return@gesto Evento.Errore(null, null)
                // Il dispositivo c'è, ma il suo codice si è perso con la risposta:
                // se ne chiede uno nuovo (annulla quello perso). Se non arriva
                // nemmeno quello, si dice dove crearlo.
                when (val codice = postino.nuovoCodice(creato.id)) {
                    is EsitoScrittura.Riuscito -> {
                        mostraCodice(
                            codice.dato,
                            nomeRiserva = creato.nome,
                            tipoRiserva = creato.tipo,
                            ricollegamento = false,
                            idRiserva = creato.id,
                        )
                        null
                    }
                    else -> Evento.Fatto(R.string.famiglia_dispositivo_aggiunto_senza_codice)
                }
            }
        }
    }

    fun nuovoCodice(dispositivo: Dispositivo) = gesto { postino ->
        when (val esito = postino.nuovoCodice(dispositivo.id)) {
            is EsitoScrittura.Riuscito -> {
                mostraCodice(
                    esito.dato,
                    nomeRiserva = dispositivo.nome,
                    tipoRiserva = dispositivo.tipo,
                    ricollegamento = dispositivo.abbinato,
                    idRiserva = dispositivo.id,
                )
                null
            }
            else -> errore(esito)
        }
    }

    fun scollega(dispositivo: Dispositivo) = gesto { postino ->
        when (val esito = postino.scollegaDispositivo(dispositivo.id)) {
            is EsitoScrittura.Riuscito -> Evento.Fatto(R.string.famiglia_dispositivo_scollegato)
            else -> errore(esito)
        }
    }

    // --- (0.13) I genitori (contratto v3.6) ------------------------------------------
    // Si leggono solo dove servono (Impostazioni, sezione Famiglia), non a ogni giro
    // del badge: chi sei tu lo dice già GET /api/famiglia.

    private var letturaGenitori: Job? = null

    /** Rilegge i genitori (GET /api/genitori). Un server più vecchio della v3.6 non li conosce. */
    fun aggiornaGenitori() {
        letturaGenitori?.cancel()
        letturaGenitori = viewModelScope.launch {
            val configurazione = impostazioni.leggiConfigurazione()
            if (!configurazione.completa) return@launch
            _stato.value = when (val esito = PostinoClient(configurazione).leggiGenitori()) {
                is EsitoGenitori.Letti -> _stato.value.copy(
                    genitori = esito.pacco.genitori.sortedBy { it.id },
                    io = esito.pacco.io ?: _stato.value.io,
                    genitoriLetti = true,
                    genitoriServerVecchio = false,
                    genitoriErrore = false,
                )
                EsitoGenitori.ServerVecchio -> _stato.value.copy(
                    genitori = emptyList(),
                    genitoriLetti = true,
                    genitoriServerVecchio = true,
                    genitoriErrore = false,
                )
                // (0.13) 401: come per la famiglia, non è la rete.
                EsitoGenitori.NonAutorizzato -> _stato.value.copy(collegamentoNonValido = true)
                EsitoGenitori.Fallita -> _stato.value.copy(genitoriErrore = true)
            }
        }
    }

    /** Come [rileggiSeLibera], per i genitori (il dialogo del codice di un genitore). */
    fun rileggiGenitoriSeLibera() {
        if (letturaGenitori?.isActive == true) return
        aggiornaGenitori()
    }

    /**
     * "Aggiungi un genitore": nasce non collegato, e il suo codice si mostra in
     * grande. Se la rete cade, come per un figlio: prima di dire "riprova" si
     * rileggono i genitori; se c'è, gli si chiede un codice nuovo (quello perso si
     * annulla).
     */
    fun creaGenitore(nome: String) = gesto(suGenitori = true) { postino ->
        val pulito = nome.trim()
        val prima = _stato.value.genitori
        when (val esito = postino.creaGenitore(pulito)) {
            is EsitoScrittura.Riuscito -> {
                mostraCodiceGenitore(esito.dato, nomeRiserva = pulito, ricollegamento = false)
                null
            }
            is EsitoScrittura.Rifiutato -> errore(esito, suGenitore = true)
            EsitoScrittura.Fallito -> {
                val dopo = (postino.leggiGenitori() as? EsitoGenitori.Letti)?.pacco?.genitori
                    ?: return@gesto Evento.Errore(CodiciErrore.ESITO_INCERTO, null, suGenitore = true)
                val creato = genitoreCreato(prima, dopo, pulito)
                    ?: return@gesto Evento.Errore(null, null, suGenitore = true)
                when (val codice = postino.nuovoCodiceGenitore(creato.id)) {
                    is EsitoScrittura.Riuscito -> {
                        mostraCodiceGenitore(codice.dato, nomeRiserva = creato.nome, ricollegamento = false, idRiserva = creato.id)
                        null
                    }
                    else -> Evento.Fatto(R.string.famiglia_genitore_aggiunto_senza_codice)
                }
            }
        }
    }

    /** Un codice nuovo per un altro genitore (telefono cambiato, o primo collegamento non riuscito). */
    fun nuovoCodiceGenitore(genitore: Genitore) = gesto(suGenitori = true) { postino ->
        when (val esito = postino.nuovoCodiceGenitore(genitore.id)) {
            is EsitoScrittura.Riuscito -> {
                mostraCodiceGenitore(
                    esito.dato,
                    nomeRiserva = genitore.nome,
                    ricollegamento = genitore.abbinato,
                    idRiserva = genitore.id,
                )
                null
            }
            else -> errore(esito, suGenitore = true)
        }
    }

    /** Il nome di un genitore (anche di un altro: sono tutti uguali). */
    fun rinominaGenitore(genitoreId: Long, nome: String) = gesto(suGenitori = true) { postino ->
        when (val esito = postino.rinominaGenitore(genitoreId, nome.trim())) {
            is EsitoScrittura.Riuscito -> Evento.Fatto(R.string.famiglia_figlio_rinominato)
            else -> errore(esito, suGenitore = true)
        }
    }

    /** "Togli": la revoca. Il suo telefono smette di vedere il patto; niente si cancella. */
    fun togliGenitore(genitore: Genitore) = gesto(suGenitori = true) { postino ->
        when (val esito = postino.togliGenitore(genitore.id)) {
            is EsitoScrittura.Riuscito -> Evento.Fatto(R.string.famiglia_genitore_tolto)
            else -> errore(esito, suGenitore = true)
        }
    }

    private fun mostraCodiceGenitore(
        ricevuto: CodiceGenitoreRicevuto,
        nomeRiserva: String,
        ricollegamento: Boolean,
        idRiserva: Long? = null,
    ) {
        val arrivo = SystemClock.elapsedRealtime()
        val codice = ricevuto.codice
        val id = codice.genitore?.id ?: idRiserva ?: return
        _stato.value = _stato.value.copy(
            codice = CodiceMostrato(
                dispositivoId = null,
                nomeDispositivo = codice.genitore?.nome?.takeIf { it.isNotBlank() } ?: nomeRiserva,
                tipo = TipiDispositivo.TELEFONO,
                codice = codice.codice,
                validita = validitaCodice(codice.scadeTs, ricevuto.oraServer),
                ricevutoMs = arrivo,
                ricollegamento = ricollegamento,
                genitoreId = id,
            ),
        )
    }

    fun chiudiCodice() {
        _stato.value = _stato.value.copy(codice = null)
    }

    fun consumaEvento() {
        _stato.value = _stato.value.copy(evento = null)
    }

    /**
     * Un gesto sul server: uno alla volta, poi SEMPRE una rilettura della
     * famiglia (anche dopo un rifiuto: un 404 vuol dire che la famiglia che
     * vediamo non è più quella vera).
     */
    private fun gesto(suGenitori: Boolean = false, azione: suspend (PostinoClient) -> Evento?) {
        if (_stato.value.lavoroInCorso) return
        _stato.value = _stato.value.copy(lavoroInCorso = true)
        viewModelScope.launch {
            val configurazione = impostazioni.leggiConfigurazione()
            val evento = if (configurazione.completa) {
                azione(PostinoClient(configurazione))
            } else {
                Evento.Errore(null, null, suGenitore = suGenitori)
            }
            _stato.value = _stato.value.copy(lavoroInCorso = false, evento = evento)
            aggiorna()
            // (0.13) Dopo un gesto sui genitori si rileggono anche loro.
            if (suGenitori) aggiornaGenitori()
        }
    }

    private fun mostraCodice(
        ricevuto: CodiceRicevuto,
        nomeRiserva: String,
        tipoRiserva: String,
        ricollegamento: Boolean,
        idRiserva: Long? = null,
    ) {
        // Il momento dell'arrivo sull'orologio monotono: il conto alla rovescia
        // parte da qui, con la validità decisa dall'orologio del SERVER.
        val arrivo = SystemClock.elapsedRealtime()
        val codice = ricevuto.codice
        _stato.value = _stato.value.copy(
            codice = CodiceMostrato(
                dispositivoId = codice.dispositivo?.id ?: idRiserva,
                nomeDispositivo = codice.dispositivo?.nome?.takeIf { it.isNotBlank() } ?: nomeRiserva,
                tipo = codice.dispositivo?.tipo ?: tipoRiserva.ifBlank { TipiDispositivo.TELEFONO },
                codice = codice.codice,
                validita = validitaCodice(codice.scadeTs, ricevuto.oraServer),
                ricevutoMs = arrivo,
                ricollegamento = ricollegamento,
            ),
        )
    }

    /**
     * I figli come sono adesso sul server, dopo una creazione rimasta senza
     * risposta; null se non si riesce a rileggerli (rete ancora giù, server 0.7).
     */
    private suspend fun famigliaDopoErrore(postino: PostinoClient): List<Figlio>? =
        (postino.leggiFamiglia() as? EsitoFamiglia.Letta)?.famiglia?.figli

    private fun errore(esito: EsitoScrittura<*>, suGenitore: Boolean = false): Evento = when (esito) {
        is EsitoScrittura.Rifiutato -> Evento.Errore(esito.errore, esito.riprovaTraSecondi, suGenitore)
        else -> Evento.Errore(null, null, suGenitore)
    }

    private fun decodificaFamiglia(json: String): Famiglia? = try {
        PostinoClient.json.decodeFromString(Famiglia.serializer(), json)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}
