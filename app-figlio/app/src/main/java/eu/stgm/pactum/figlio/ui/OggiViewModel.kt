package eu.stgm.pactum.figlio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.pactum.design.GiornoPatto
import eu.stgm.pactum.figlio.bonus.BonusInSospeso
import eu.stgm.pactum.figlio.bonus.CassettaBonus
import eu.stgm.pactum.figlio.bonus.ConsegnaBonus
import eu.stgm.pactum.figlio.bonus.EsitoBonus
import eu.stgm.pactum.figlio.bonus.RegoleBonus
import eu.stgm.pactum.figlio.catalogo.CatalogoApp
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.dati.PattoLocale
import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.Riepilogo
import eu.stgm.pactum.figlio.dati.StatoBonus
import eu.stgm.pactum.figlio.dati.TipiRegola
import eu.stgm.pactum.figlio.dati.zonaPatto
import eu.stgm.pactum.figlio.misura.UsageStatsReader
import eu.stgm.pactum.figlio.rete.PostinoClient
import eu.stgm.pactum.figlio.valutatore.MomentoFascia
import eu.stgm.pactum.figlio.valutatore.SentinellaPatto
import eu.stgm.pactum.figlio.valutatore.Valutatore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.ZoneId

/**
 * Oggi (redesign B4/B5): il patto prima del consumo. In cima la serie e la
 * striscia del server, poi una riga per regola attiva con i minuti di oggi e
 * il bonus in due tocchi, in fondo dove è finito il tempo.
 *
 * La striscia viene dal server (GET /api/patto, v2.4) e da lì si calcolano
 * serie e record, che restano sul telefono. I minuti di oggi vengono dal
 * telefono, contati come li conta la sentinella: la barra e lo sforamento
 * parlano degli stessi minuti.
 */
class OggiViewModel(application: Application) : AndroidViewModel(application) {

    data class RigaUso(val etichetta: String, val pacchetto: String, val minuti: Long)

    /** Una regola attiva, vista oggi. */
    sealed interface RigaRegola {
        val regola: Regola

        /** Limite di tempo: minuti di oggi sul limite efficace (limite + bonus di oggi). */
        data class Tempo(
            override val regola: Regola,
            val nome: String,
            val minuti: Long,
            val limiteEfficace: Int,
            val bonusOggi: Int,
        ) : RigaRegola

        data class Fascia(override val regola: Regola, val momento: MomentoFascia?) : RigaRegola

        data class VitaReale(override val regola: Regola) : RigaRegola

        /** Un tipo che l'app non conosce ancora: si mostra la descrizione, niente di più. */
        data class Altra(override val regola: Regola) : RigaRegola
    }

    sealed interface Evento {
        data class Bonus(val esito: EsitoBonus) : Evento

        /** Il bonus rimasto in sospeso per la rete è arrivato: dopo "Niente rete", lo si dice. */
        data class BonusPartito(val minuti: Int) : Evento

        /** "Aggiungi perché" arrivato un attimo dopo la partenza del bonus. */
        data object BonusGiaPartito : Evento
    }

    data class StatoOggi(
        val caricamento: Boolean = true,
        /** Il server non ha risposto: si mostra l'ultima copia, con la sua età. */
        val datiFermi: Boolean = false,
        val datiFermiAlle: Long? = null,
        val striscia: List<GiornoPatto> = emptyList(),
        /** La riga sotto la striscia, uguale a quella del genitore. null = server vecchio. */
        val riepilogo: Riepilogo? = null,
        val serie: Int = 0,
        val record: Int = 0,
        val regole: List<RigaRegola> = emptyList(),
        val bonus: StatoBonus? = null,
        val fuso: String? = null,
        val righe: List<RigaUso> = emptyList(),
        val minutiTotali: Long = 0,
        val bonusInSospeso: BonusInSospeso? = null,
        /** È aperta la snackbar "Ti sei dato 15 minuti · Aggiungi perché". */
        val finestraBonus: Boolean = false,
        val evento: Evento? = null,
    )

    private val _stato = MutableStateFlow(StatoOggi())
    val stato: StateFlow<StatoOggi> = _stato.asStateFlow()

    /**
     * Vero da quando il ragazzo tocca un bonus (o manda il perché) finché non
     * arriva il suo esito DEFINITIVO: solo quegli esiti si raccontano. Un
     * recupero fatto in silenzio dal servizio non deve spuntare come snackbar.
     * "Niente rete" non è definitivo: il bonus resta in sospeso e riparte da
     * solo, e com'è finita (partito, tetto, scaduto) si dice quando si sa.
     */
    private var attesaEsito = false

    /** Il ragazzo ha già letto "Niente rete" per il bonus che aspetta. */
    private var senzaReteGiaDetto = false

    init {
        viewModelScope.launch {
            ConsegnaBonus.esiti.collect { esito ->
                val daMostrare = attesaEsito && when (esito) {
                    // "Niente rete" una volta sola, non a ogni tentativo.
                    is EsitoBonus.SenzaRete -> !senzaReteGiaDetto
                    // Il sì si vede già sulla riga; dopo un "niente rete" si dice.
                    is EsitoBonus.Concesso -> senzaReteGiaDetto
                    else -> true
                }
                if (esito is EsitoBonus.SenzaRete) {
                    if (attesaEsito) senzaReteGiaDetto = true
                } else {
                    attesaEsito = false
                    senzaReteGiaDetto = false
                }
                val sospeso = CassettaBonus(getApplication()).leggi()
                val evento = when {
                    !daMostrare -> null
                    esito is EsitoBonus.Concesso -> Evento.BonusPartito(esito.minuti)
                    else -> Evento.Bonus(esito)
                }
                _stato.update {
                    it.copy(
                        bonusInSospeso = sospeso,
                        finestraBonus = false,
                        evento = evento ?: it.evento,
                    )
                }
                if (esito is EsitoBonus.Concesso) aggiorna()
            }
        }
    }

    fun aggiorna() {
        _stato.update { it.copy(caricamento = true) }
        val context = getApplication<Application>()
        // Un bonus rimasto a metà (app chiusa durante la snackbar) parte da qui.
        ConsegnaBonus.recuperaInFondo(context)
        viewModelScope.launch(Dispatchers.Default) {
            val impostazioni = Impostazioni(context)
            val configurazione = impostazioni.leggiConfigurazione()
            val locale = PattoLocale(context)
            val dalServer = if (configurazione.completa) {
                PostinoClient(configurazione).leggiPatto()
            } else {
                null
            }
            if (dalServer != null) locale.salva(dalServer)
            val patto = dalServer ?: locale.leggi()
            val fermi = configurazione.completa && dalServer == null
            val giorni = patto?.giorniPatto().orEmpty()
            val (serie, record) = impostazioni.aggiornaSerie(giorni)

            val adesso = System.currentTimeMillis()
            val uso = UsageStatsReader(context).usoDelGiorno()
            val indice = SentinellaPatto.indiceUso(context, uso)
            val bonusOggi = patto?.bonusValidiOggi(adesso).orEmpty()
            val regole = patto?.regole.orEmpty()
                .filter { it.attiva }
                .map { regola -> rigaRegola(regola, bonusOggi, indice::minuti, adesso) }

            // Stesso filtro della fotografia inviata al server (BattitoWorker):
            // fuori Home, sistema senza icona e le due app Pactum. Così il totale
            // del figlio coincide con quello che il genitore vede nella finestra.
            val contati = uso.filter { CatalogoApp.contaNellUso(context, it.pacchetto) }
            val righe = contati
                .filter { it.millisPrimoPiano >= 60_000 } // sotto il minuto: rumore
                .map {
                    RigaUso(
                        etichetta = CatalogoApp.etichettaValore(context, it.pacchetto),
                        pacchetto = it.pacchetto,
                        minuti = it.millisPrimoPiano / 60_000,
                    )
                }
                .sortedByDescending { it.minuti }
            val sospeso = CassettaBonus(context).leggi()
            val datiFermiAlle = if (fermi) locale.aggiornatoIl() else null

            _stato.update {
                // La lettura del cassetto può essere di un attimo PRIMA del tocco
                // sul bonus: un null qui non chiude la snackbar aperta. A
                // chiuderla sono l'esito della consegna, il perché o il tempo.
                val aperto = it.bonusInSospeso?.takeIf { _ -> it.finestraBonus }
                val finestra = aperto != null &&
                    (sospeso == null || (sospeso.id == aperto.id && !sospeso.inScrittura))
                it.copy(
                    caricamento = false,
                    datiFermi = fermi,
                    datiFermiAlle = datiFermiAlle,
                    striscia = giorni,
                    riepilogo = patto?.riepilogo,
                    serie = serie,
                    record = record,
                    regole = regole,
                    bonus = patto?.bonus,
                    fuso = patto?.fuso,
                    righe = righe,
                    minutiTotali = contati.sumOf { u -> u.millisPrimoPiano } / 60_000,
                    bonusInSospeso = if (sospeso == null && finestra) aperto else sospeso,
                    finestraBonus = finestra,
                )
            }
        }
    }

    private fun rigaRegola(
        regola: Regola,
        bonusOggi: Map<String, Int>,
        minutiSu: (String) -> Long,
        adesso: Long,
    ): RigaRegola = when (regola.tipo) {
        TipiRegola.LIMITE_TEMPO -> {
            val chiave = (regola.parametri["app_o_categoria"] as? JsonPrimitive)?.content
            val limite = Valutatore.limiteEfficace(regola, bonusOggi)
            if (chiave == null || limite == null) {
                RigaRegola.Altra(regola)
            } else {
                RigaRegola.Tempo(
                    regola = regola,
                    nome = CatalogoApp.etichettaValore(getApplication(), chiave),
                    minuti = minutiSu(chiave),
                    limiteEfficace = limite,
                    bonusOggi = bonusOggi[regola.id.toString()] ?: 0,
                )
            }
        }
        TipiRegola.FASCIA_ORARIA ->
            RigaRegola.Fascia(regola, Valutatore.momentoFascia(regola, adesso, ZoneId.systemDefault()))
        TipiRegola.VITA_REALE -> RigaRegola.VitaReale(regola)
        else -> RigaRegola.Altra(regola)
    }

    // --- Bonus in due tocchi (B5) --------------------------------------------

    /** Il primo tocco: il bonus va su disco e parte da solo quando si chiude la snackbar. */
    fun concedi(regolaId: Long, minuti: Int) {
        if (_stato.value.bonusInSospeso != null) return
        viewModelScope.launch {
            val giorno = LocalDate.now(zonaPatto(_stato.value.fuso)).toString()
            val bonus = ConsegnaBonus.prepara(getApplication(), regolaId, minuti, giorno)
            if (bonus == null) {
                // Ce n'era già uno (un'altra schermata, un recupero): si mostra quello.
                _stato.update { it.copy(bonusInSospeso = CassettaBonus(getApplication()).leggi()) }
                return@launch
            }
            attesaEsito = true
            senzaReteGiaDetto = false
            _stato.update { it.copy(bonusInSospeso = bonus, finestraBonus = true) }
            // La snackbar si chiude poco dopo la finestra anche se la rete è
            // lenta: da lì in poi la riga dice "+15 min in partenza" finché il
            // server non risponde. Non tocca il bonus, solo la snackbar.
            delay(RegoleBonus.FINESTRA_MS + 2_000)
            if (_stato.value.finestraBonus && _stato.value.bonusInSospeso?.id == bonus.id) {
                _stato.update { it.copy(finestraBonus = false) }
            }
        }
    }

    /** "Aggiungi perché": il bonus aspetta il perché invece di partire. */
    fun aggiungiPerche() {
        val bonus = _stato.value.bonusInSospeso ?: return
        viewModelScope.launch {
            if (ConsegnaBonus.aspettaPerche(getApplication(), bonus.id)) {
                _stato.update {
                    it.copy(bonusInSospeso = bonus.copy(inScrittura = true), finestraBonus = false)
                }
            } else {
                _stato.update { it.copy(finestraBonus = false, evento = Evento.BonusGiaPartito) }
            }
        }
    }

    /** Il secondo tocco del dialogo: parte col perché, o senza. */
    fun mandaBonus(motivo: String?) {
        val bonus = _stato.value.bonusInSospeso ?: return
        attesaEsito = true
        ConsegnaBonus.manda(getApplication(), bonus.id, motivo)
        _stato.update { it.copy(bonusInSospeso = bonus.copy(inScrittura = false), finestraBonus = false) }
    }

    fun consumaEvento() {
        _stato.update { it.copy(evento = null) }
    }
}
