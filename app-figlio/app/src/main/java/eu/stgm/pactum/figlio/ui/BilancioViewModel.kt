package eu.stgm.pactum.figlio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.pactum.figlio.bilancio.ArchivioBilancio
import eu.stgm.pactum.figlio.bilancio.Bilancio
import eu.stgm.pactum.figlio.bilancio.Diario
import eu.stgm.pactum.figlio.bilancio.LogicaBilancio
import eu.stgm.pactum.figlio.bilancio.LogicaBilancio.GiornoDellaStoria
import eu.stgm.pactum.figlio.bilancio.LogicaBilancio.NuovaPromessa
import eu.stgm.pactum.figlio.bilancio.Promessa
import eu.stgm.pactum.figlio.bilancio.PromesseMisurabili
import eu.stgm.pactum.figlio.bilancio.PromemoriaPromesse
import eu.stgm.pactum.figlio.bilancio.RegolaDiUnGiorno
import eu.stgm.pactum.figlio.bilancio.regoleDiUnGiorno
import eu.stgm.pactum.figlio.dati.CreaRegolaIn
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.dati.PattoLocale
import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.rete.PostinoClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

/**
 * (0.24) Il bilancio della sera: legge il diario (sul telefono) e le regole di un
 * giorno (dal patto, per com'è andata una promessa misurabile), salva il bilancio,
 * risponde alle promesse e, per una promessa che il telefono controlla, crea prima
 * la regola di un giorno sul server: senza quella la promessa non si salva.
 */
class BilancioViewModel(application: Application) : AndroidViewModel(application) {

    enum class Errore {
        /** La regola di un giorno non è partita (rete, server). */
        RETE,

        /** Il server l'ha rifiutata (un giorno o una forma che non va). */
        RIFIUTATA,
    }

    data class StatoBilancio(
        val caricamento: Boolean = true,
        /** Il giorno di cui si fa il bilancio (prima delle 4 è ancora ieri). */
        val giorno: LocalDate = LocalDate.now(),
        val oggi: LocalDate = LocalDate.now(),
        val bilancio: Bilancio? = null,
        /** La promessa scritta nel bilancio di [giorno], se c'è. */
        val promessa: Promessa? = null,
        val daRispondere: List<Promessa> = emptyList(),
        /** Le misurabili di oggi e di ieri: cosa dice il telefono. */
        val misurate: List<Promessa> = emptyList(),
        val daCambiare: List<String> = emptyList(),
        val storia: List<GiornoDellaStoria> = emptyList(),
        val regole: List<RegolaDiUnGiorno> = emptyList(),
        /** Il server conosce le regole di un giorno (v4.2): si offre "la controlla il telefono". */
        val misurabiliPossibili: Boolean = false,
        val invio: Boolean = false,
        val errore: Errore? = null,
        /** Appena salvato: la pagina lo dice una volta. */
        val salvato: Boolean = false,
        val diario: Diario = Diario(),
    )

    companion object {
        /** Lo stato della pagina dal diario e dalle regole di un giorno, a [adesso]. Puro: lo usa anche il fotografo. */
        fun statoDa(
            diario: Diario,
            regole: List<RegolaDiUnGiorno>,
            misurabili: Boolean,
            adesso: LocalDateTime,
        ): StatoBilancio {
            val oggi = adesso.toLocalDate()
            val giorno = LogicaBilancio.giornoDelBilancio(adesso)
            val bilancio = LogicaBilancio.bilancioDi(diario, giorno)
            return StatoBilancio(
                caricamento = false,
                giorno = giorno,
                oggi = oggi,
                bilancio = bilancio,
                promessa = bilancio?.promessaId?.let { id -> diario.promesse.firstOrNull { it.id == id } },
                daRispondere = LogicaBilancio.daRispondere(diario, adesso),
                misurate = diario.promesse.filter {
                    it.regolaId != null && (it.giorno == oggi.toString() || it.giorno == oggi.minusDays(1).toString())
                },
                daCambiare = LogicaBilancio.daCambiare(diario, regole, oggi),
                storia = LogicaBilancio.storia(diario, regole, giorno),
                regole = regole,
                misurabiliPossibili = misurabili,
                diario = diario,
            )
        }
    }

    private val _stato = MutableStateFlow(StatoBilancio())
    val stato: StateFlow<StatoBilancio> = _stato.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    init {
        aggiorna()
        // Una risposta dalla notifica mentre la pagina è aperta: si rilegge.
        viewModelScope.launch { ArchivioBilancio.cambi.drop(1).collect { aggiorna() } }
    }

    fun aggiorna() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val diario = ArchivioBilancio.leggi(context)
            val patto = PattoLocale(context).leggi()
            mostra(diario, patto?.regoleDiUnGiorno().orEmpty(), patto?.conosceRegoleUnGiorno == true)
        }
    }

    private fun mostra(diario: Diario, regole: List<RegolaDiUnGiorno>, misurabili: Boolean) {
        _stato.value = statoDa(diario, regole, misurabili, LocalDateTime.now())
            .copy(invio = _stato.value.invio, errore = _stato.value.errore, salvato = _stato.value.salvato)
    }

    fun rispondi(id: String, esito: String) = cambia { LogicaBilancio.rispondi(it, id, esito) }

    fun rimanda(id: String, giorno: LocalDate, ora: LocalTime?) = cambia { LogicaBilancio.rimanda(it, id, giorno, ora) }

    private fun cambia(cambio: (Diario) -> Diario) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val diario = ArchivioBilancio.cambia(context, cambio)
            PromemoriaPromesse.togliAvvisiRisolti(context, diario)
            mostra(diario, _stato.value.regole, _stato.value.misurabiliPossibili)
        }
    }

    /**
     * Salva il bilancio di stasera. Con [misura] la promessa la controlla il telefono:
     * prima nasce la regola di un giorno (domani), e solo se il server la prende si
     * salva la promessa, col suo [testoMisura].
     */
    fun salva(
        voto: String,
        cosa: String?,
        promessa: NuovaPromessa?,
        misura: PromesseMisurabili.Misura? = null,
        testoMisura: String? = null,
    ) {
        if (_stato.value.invio) return
        _stato.value = _stato.value.copy(invio = true, errore = null, salvato = false)
        viewModelScope.launch {
            val context = getApplication<Application>()
            val giorno = LogicaBilancio.giornoDelBilancio(LocalDateTime.now())
            var nuova = promessa
            if (misura != null && promessa != null) {
                val (regola, errore) = creaRegolaDiUnGiorno(misura, promessa.giorno)
                if (regola == null) {
                    _stato.value = _stato.value.copy(invio = false, errore = errore ?: Errore.RETE)
                    return@launch
                }
                nuova = promessa.copy(testo = testoMisura ?: promessa.testo, ora = null, regolaId = regola.id)
            }
            val diario = ArchivioBilancio.cambia(context) {
                LogicaBilancio.salva(it, giorno, voto, cosa, nuova, UUID.randomUUID().toString(), System.currentTimeMillis())
            }
            _stato.value = _stato.value.copy(invio = false, salvato = true)
            // La regola nuova nel patto locale: la valuta il telefono, la storia la vede.
            val patto = if (misura != null) rileggiPatto() else PattoLocale(context).leggi()
            mostra(diario, patto?.regoleDiUnGiorno().orEmpty(), patto?.conosceRegoleUnGiorno == true)
        }
    }

    /** La regola nata (e niente errore), oppure niente regola e perché. */
    private suspend fun creaRegolaDiUnGiorno(misura: PromesseMisurabili.Misura, giorno: LocalDate): Pair<Regola?, Errore?> {
        val configurazione = Impostazioni(getApplication()).leggiConfigurazione()
        if (!configurazione.completa) return null to Errore.RETE
        val risposta = PostinoClient(configurazione).creaRegola(
            CreaRegolaIn(
                tipo = PromesseMisurabili.tipo(misura),
                parametri = PromesseMisurabili.parametri(misura, giorno),
                soloIl = giorno.toString(),
            ),
        )
        if (!risposta.ok) return null to if (risposta.codice in 400..499) Errore.RIFIUTATA else Errore.RETE
        val regola = risposta.corpo?.let { runCatching { json.decodeFromString(Regola.serializer(), it) }.getOrNull() }
            ?: return null to Errore.RETE
        // Un server che non sa delle regole di un giorno l'avrebbe fatta per sempre: qui non arriva
        // (la scelta si offre solo con un patto v4.2), ma se succede lo si dice.
        if (regola.soloIl != giorno.toString()) return null to Errore.RIFIUTATA
        return regola to null
    }

    private suspend fun rileggiPatto() = runCatching {
        val context = getApplication<Application>()
        val configurazione = Impostazioni(context).leggiConfigurazione()
        PostinoClient(configurazione).leggiPatto()?.also { PattoLocale(context).salva(it) }
    }.getOrNull() ?: PattoLocale(getApplication()).leggi()

    fun dimenticaSalvato() {
        if (_stato.value.salvato) _stato.value = _stato.value.copy(salvato = false)
    }

    fun dimenticaErrore() {
        if (_stato.value.errore != null) _stato.value = _stato.value.copy(errore = null)
    }
}
