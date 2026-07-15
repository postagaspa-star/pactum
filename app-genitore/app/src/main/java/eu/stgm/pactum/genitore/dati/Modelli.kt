package eu.stgm.pactum.genitore.dati

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Le risposte del postino per il genitore (docs/contratto-api.md, sezione
 * "Endpoint del genitore" — la fonte di verità del protocollo). I timestamp
 * `ts_server` sono ISO 8601 UTC e fanno fede; si mostrano nel fuso del
 * telefono. I campi sconosciuti si ignorano (tolleranza evolutiva).
 */
@Serializable
data class Finestra(
    val regole: List<RegolaFinestra> = emptyList(),
    @SerialName("sforamenti_recenti") val sforamentiRecenti: List<EventoFinestra> = emptyList(),
    @SerialName("manomissioni_recenti") val manomissioniRecenti: List<EventoFinestra> = emptyList(),
    @SerialName("storico_modifiche") val storicoModifiche: List<ModificaStorico> = emptyList(),
    val bonus: StatoBonus,
    @SerialName("bonus_giornalieri") val bonusGiornalieri: List<BonusGiorno> = emptyList(),
    @SerialName("stato_silenzio") val statoSilenzio: StatoSilenzio,
)

@Serializable
data class RegolaFinestra(
    val id: Long,
    val tipo: String,
    val parametri: JsonObject = JsonObject(emptyMap()),
    val attiva: Boolean = true,
    @SerialName("creata_ts") val creataTs: String = "",
    @SerialName("ultima_modifica_ts") val ultimaModificaTs: String = "",
    @SerialName("allentabile_dal") val allentabileDal: String? = null,
    val semaforo: List<QuadrettoSemaforo> = emptyList(),
)

object TipiRegola {
    const val LIMITE_TEMPO = "limite_tempo"
    const val FASCIA_ORARIA = "fascia_oraria"
    const val VITA_REALE = "vita_reale"
}

/** Un giorno del semaforo: stato ∈ verde / rosso / grigio (niente giallo). */
@Serializable
data class QuadrettoSemaforo(val data: String, val stato: String)

object StatiSemaforo {
    const val VERDE = "verde"
    const val ROSSO = "rosso"
    const val GRIGIO = "grigio"
}

@Serializable
data class EventoFinestra(
    val id: String,
    val tipo: String,
    val dettagli: JsonObject = JsonObject(emptyMap()),
    @SerialName("ts_device") val tsDevice: Long? = null,
    @SerialName("ts_server") val tsServer: String,
)

@Serializable
data class ModificaStorico(
    val id: Long,
    @SerialName("regola_id") val regolaId: Long,
    val azione: String,
    val direzione: String? = null,
    val prima: JsonObject? = null,
    val dopo: JsonObject? = null,
    val concordata: Boolean = false,
    @SerialName("ts_server") val tsServer: String,
)

@Serializable
data class ContatoreBonus(val usati: Int, val tetto: Int, val residui: Int)

@Serializable
data class StatoBonus(val giorno: ContatoreBonus, val settimana: ContatoreBonus)

@Serializable
data class BonusGiorno(val giorno: String, val minuti: Int)

@Serializable
data class StatoSilenzio(
    @SerialName("ultimo_battito") val ultimoBattito: String? = null,
    val silente: Boolean,
)

@Serializable
data class Notifica(
    val id: Long,
    val tipo: String,
    val messaggio: String,
    val payload: JsonObject = JsonObject(emptyMap()),
    @SerialName("ts_server") val tsServer: String,
)

@Serializable
data class PaccoNotifiche(val notifiche: List<Notifica> = emptyList())
