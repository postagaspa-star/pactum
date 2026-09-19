package eu.stgm.pactum.figlio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.pactum.figlio.dati.Dichiarazione
import eu.stgm.pactum.figlio.dati.DichiarazioneIn
import eu.stgm.pactum.figlio.dati.EsitiDichiarazione
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.dati.PattoLocale
import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.StatiDichiarazione
import eu.stgm.pactum.figlio.dati.TipiRegola
import eu.stgm.pactum.figlio.dati.leggiDettaglioErrore
import eu.stgm.pactum.figlio.dati.zonaPatto
import eu.stgm.pactum.figlio.rete.PostinoClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * Il diario delle regole di vita reale: il figlio dichiara com'è andata
 * (successo o fallimento) e vede lo stato delle conferme. Fallimento =
 * creduto sulla parola; successo = in attesa del verdetto (contratto-api.md,
 * sezione Dichiarazioni). Massimo una dichiarazione per regola per giorno:
 * il 409 `gia_dichiarato` lo dice anche quando l'app non lo sa già.
 *
 * (B7) Il successo si riconosce SUBITO: la dichiarazione entra come
 * provvisoria prima della risposta del server — il riconoscimento verso il
 * figlio non può aspettare un adulto, e nemmeno la rete. Se il server dice no,
 * la provvisoria sparisce e lo si dice a voce alta.
 */
class DichiarazioniViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface Evento {
        data class Inviata(val esito: String, val regolaId: Long) : Evento
        data object GiaDichiarato : Evento

        /** Un successo mostrato come fatto che il server non ha preso: si torna indietro. */
        data object SuccessoNonArrivato : Evento
        data object Errore : Evento
    }

    data class StatoDiario(
        val caricamento: Boolean = true,
        val regoleVitaReale: List<Regola> = emptyList(),
        val dichiarazioni: List<Dichiarazione> = emptyList(),
        /** Successi già mostrati come fatti, in attesa della risposta del server. */
        val provvisorie: List<Dichiarazione> = emptyList(),
        // Il fuso del patto (GET /api/patto): serve a calcolare "oggi" come il
        // server, non col fuso del telefono (il vincolo "già dichiarato oggi").
        val fuso: String? = null,
        val configurazioneMancante: Boolean = false,
        val errore: Boolean = false,
        val datiFermiAlle: Long? = null,
        val invioInCorso: Boolean = false,
        val evento: Evento? = null,
    ) {
        /**
         * Quello che si mostra: le provvisorie davanti, finché il server non ha
         * la sua versione dello stesso giorno sulla stessa regola.
         */
        val tutte: List<Dichiarazione>
            get() = (
                provvisorie.filter { p ->
                    dichiarazioni.none { it.regolaId == p.regolaId && it.giorno == p.giorno }
                } + dichiarazioni
                ).distinctBy { it.id } // gli id sono le chiavi della lista: mai due uguali
    }

    private val _stato = MutableStateFlow(StatoDiario())
    val stato: StateFlow<StatoDiario> = _stato.asStateFlow()

    /** Id negativi per le provvisorie: il server non li usa mai. */
    private var prossimoIdProvvisorio = -1L

    fun aggiorna() {
        _stato.update { it.copy(caricamento = true) }
        viewModelScope.launch {
            val configurazione = Impostazioni(getApplication()).leggiConfigurazione()
            if (!configurazione.completa) {
                _stato.value = StatoDiario(caricamento = false, configurazioneMancante = true)
                return@launch
            }
            val postino = PostinoClient(configurazione)
            val patto = postino.leggiPatto()
            val dichiarazioni = postino.leggiDichiarazioni()
            if (patto == null || dichiarazioni == null) {
                val copia = PattoLocale(getApplication())
                val locale = copia.leggi()
                _stato.update {
                    it.copy(
                        caricamento = false,
                        configurazioneMancante = false,
                        errore = true,
                        datiFermiAlle = copia.aggiornatoIl(),
                        regoleVitaReale = patto?.regole?.filter { r -> r.tipo == TipiRegola.VITA_REALE }
                            ?: locale?.regole?.filter { r -> r.tipo == TipiRegola.VITA_REALE }
                            ?: it.regoleVitaReale,
                        fuso = patto?.fuso ?: locale?.fuso ?: it.fuso,
                        dichiarazioni = dichiarazioni ?: it.dichiarazioni,
                    )
                }
                return@launch
            }
            PattoLocale(getApplication()).salva(patto)
            _stato.update {
                it.copy(
                    caricamento = false,
                    configurazioneMancante = false,
                    errore = false,
                    regoleVitaReale = patto.regole.filter { r -> r.tipo == TipiRegola.VITA_REALE },
                    fuso = patto.fuso,
                    dichiarazioni = dichiarazioni,
                    // Le provvisorie che il server ora conosce non servono più.
                    provvisorie = it.provvisorie.filter { p ->
                        dichiarazioni.none { d -> d.regolaId == p.regolaId && d.giorno == p.giorno }
                    },
                )
            }
        }
    }

    /**
     * [giorno] è il giorno del patto per cui si dichiara (ISO YYYY-MM-DD): null
     * = oggi (default del server). La UI lo vincola alla finestra oggi ↔ −7gg
     * del contratto, così il 409 `giorno_non_valido` non scatta; un giorno già
     * dichiarato torna come `gia_dichiarato`, gestito come per "oggi".
     */
    fun dichiara(regolaId: Long, esito: String, nota: String?, giorno: String? = null) {
        val successo = esito == EsitiDichiarazione.SUCCESSO
        val provvisoria = if (successo) {
            Dichiarazione(
                id = prossimoIdProvvisorio--,
                regolaId = regolaId,
                giorno = giorno ?: LocalDate.now(zonaPatto(_stato.value.fuso)).toString(),
                esito = esito,
                nota = nota?.ifBlank { null },
                stato = StatiDichiarazione.IN_ATTESA,
            )
        } else {
            null
        }
        // Il fallimento resta com'era: si aspetta il server, a dialogo aperto.
        _stato.update {
            it.copy(
                invioInCorso = !successo,
                provvisorie = if (provvisoria != null) it.provvisorie + provvisoria else it.provvisorie,
            )
        }
        viewModelScope.launch {
            val configurazione = Impostazioni(getApplication()).leggiConfigurazione()
            val risposta = PostinoClient(configurazione).creaDichiarazione(
                DichiarazioneIn(
                    regolaId = regolaId,
                    esito = esito,
                    nota = nota?.ifBlank { null },
                    giorno = giorno,
                ),
            )
            val creata = if (risposta.ok) leggiCreata(risposta.corpo) else null
            val gia = !risposta.ok && leggiDettaglioErrore(risposta.corpo)?.errore == "gia_dichiarato"
            val evento = when {
                risposta.ok -> Evento.Inviata(esito, regolaId)
                gia -> Evento.GiaDichiarato
                successo -> Evento.SuccessoNonArrivato
                else -> Evento.Errore
            }
            _stato.update {
                it.copy(
                    invioInCorso = false,
                    // Arrivata: la versione del server prende il posto della
                    // provvisoria (se il corpo non si legge, la provvisoria resta
                    // finché la rilettura non porta la vera). Rifiutata: sparisce.
                    provvisorie = if (risposta.ok && creata == null) {
                        it.provvisorie
                    } else {
                        it.provvisorie.filterNot { p -> p.id == provvisoria?.id }
                    },
                    // Una rilettura partita nel frattempo può averla già portata:
                    // due volte lo stesso id è una chiave doppia nella lista (crash).
                    dichiarazioni = if (creata != null) {
                        listOf(creata) + it.dichiarazioni.filterNot { d -> d.id == creata.id }
                    } else {
                        it.dichiarazioni
                    },
                    evento = evento,
                )
            }
            if (risposta.ok || gia) aggiorna()
        }
    }

    private fun leggiCreata(corpo: String?): Dichiarazione? = corpo?.let {
        runCatching { json.decodeFromString(Dichiarazione.serializer(), it) }.getOrNull()
    }

    fun consumaEvento() {
        _stato.update { it.copy(evento = null) }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
