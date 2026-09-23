package eu.stgm.pactum.genitore.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.CodiceAbbinamento
import eu.stgm.pactum.genitore.dati.Dispositivo
import eu.stgm.pactum.genitore.dati.Famiglia
import eu.stgm.pactum.genitore.dati.Figlio
import eu.stgm.pactum.genitore.dati.Impostazioni
import eu.stgm.pactum.genitore.dati.TipiDispositivo
import eu.stgm.pactum.genitore.rete.EsitoFamiglia
import eu.stgm.pactum.genitore.rete.EsitoScrittura
import eu.stgm.pactum.genitore.rete.PostinoClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import java.time.Instant

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
        /** Sull'orologio di questo telefono (v. scadenzaCodice). */
        val scadenza: Instant,
        /** Il dispositivo era già collegato: il codice lo ricollega. */
        val ricollegamento: Boolean,
    )

    /** Un esito da dire una volta (snackbar) e poi consumare. */
    sealed interface Evento {
        data class Fatto(val messaggio: Int) : Evento
        data class Errore(val codice: String?, val secondi: Long?) : Evento
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
                    )
                }
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
        _stato.value = StatoFamiglia()
        viewModelScope.launch {
            // Se il server è lo stesso la scelta è ancora salvata e resta valida.
            _stato.value = _stato.value.copy(sceltoSalvato = impostazioni.figlioScelto.first())
            aggiorna()
        }
    }

    /** La scelta del figlio in cima alle schermate: resta ricordata. */
    fun scegli(figlioId: Long) {
        _stato.value = _stato.value.copy(sceltoSalvato = figlioId)
        viewModelScope.launch { impostazioni.salvaFiglioScelto(figlioId) }
    }

    // --- I gesti della sezione Famiglia -------------------------------------------

    fun creaFiglio(nome: String) = gesto { postino ->
        when (val esito = postino.creaFiglio(nome.trim())) {
            is EsitoScrittura.Riuscito -> Evento.Fatto(R.string.famiglia_figlio_aggiunto)
            else -> errore(esito)
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
        when (val esito = postino.creaDispositivo(figlioId, pulito, tipo)) {
            is EsitoScrittura.Riuscito -> {
                mostraCodice(esito.dato, nomeRiserva = pulito, tipoRiserva = tipo, ricollegamento = false)
                null
            }
            else -> errore(esito)
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
    private fun gesto(azione: suspend (PostinoClient) -> Evento?) {
        if (_stato.value.lavoroInCorso) return
        _stato.value = _stato.value.copy(lavoroInCorso = true)
        viewModelScope.launch {
            val configurazione = impostazioni.leggiConfigurazione()
            val evento = if (configurazione.completa) {
                azione(PostinoClient(configurazione))
            } else {
                Evento.Errore(null, null)
            }
            _stato.value = _stato.value.copy(lavoroInCorso = false, evento = evento)
            aggiorna()
        }
    }

    private fun mostraCodice(
        codice: CodiceAbbinamento,
        nomeRiserva: String,
        tipoRiserva: String,
        ricollegamento: Boolean,
        idRiserva: Long? = null,
    ) {
        val adesso = Instant.now()
        _stato.value = _stato.value.copy(
            codice = CodiceMostrato(
                dispositivoId = codice.dispositivo?.id ?: idRiserva,
                nomeDispositivo = codice.dispositivo?.nome?.takeIf { it.isNotBlank() } ?: nomeRiserva,
                tipo = codice.dispositivo?.tipo ?: tipoRiserva.ifBlank { TipiDispositivo.TELEFONO },
                codice = codice.codice,
                scadenza = scadenzaCodice(codice.scadeTs, adesso),
                ricollegamento = ricollegamento,
            ),
        )
    }

    private fun errore(esito: EsitoScrittura<*>): Evento = when (esito) {
        is EsitoScrittura.Rifiutato -> Evento.Errore(esito.errore, esito.riprovaTraSecondi)
        else -> Evento.Errore(null, null)
    }

    private fun decodificaFamiglia(json: String): Famiglia? = try {
        PostinoClient.json.decodeFromString(Famiglia.serializer(), json)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}
