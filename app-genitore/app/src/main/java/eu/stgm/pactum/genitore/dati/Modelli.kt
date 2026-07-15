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

// --- Proposte (tappa 5) -----------------------------------------------------
// Il genitore propone una modifica; il server calcola il `confronto` testuale e
// la `direzione`, e il figlio accetta/rifiuta. La proposta accettata applica da
// sola la modifica lato server (contratto-api.md, sezione Proposte).

/** La proposta come la restituisce POST /api/proposte e GET /api/proposte. */
@Serializable
data class Proposta(
    val id: Long,
    @SerialName("regola_id") val regolaId: Long,
    @SerialName("parametri_proposti") val parametriProposti: JsonObject = JsonObject(emptyMap()),
    val motivazione: String? = null,
    val confronto: String = "",
    val direzione: String = "",
    val stato: String,
    val usata: Boolean = false,
    @SerialName("ts_server") val tsServer: String = "",
    val risposta: RispostaProposta? = null,
)

/** La risposta del figlio a una proposta (presente quando ha risposto). */
@Serializable
data class RispostaProposta(
    val esito: String,
    val motivazione: String? = null,
    @SerialName("ts_server") val tsServer: String = "",
)

@Serializable
data class PaccoProposte(val proposte: List<Proposta> = emptyList())

/** Corpo di POST /api/proposte. Per l'eliminazione, `parametriProposti` è il marcatore. */
@Serializable
data class NuovaProposta(
    @SerialName("regola_id") val regolaId: Long,
    @SerialName("parametri_proposti") val parametriProposti: JsonObject,
    val motivazione: String? = null,
)

object StatiProposta {
    const val PENDENTE = "pendente"
    const val ACCETTATA = "accettata"
    const val RIFIUTATA = "rifiutata"
}

object DirezioniProposta {
    const val ALLENTA = "allenta"
    const val STRINGE = "stringe"
    const val ELIMINA = "elimina"
}

object EsitiRisposta {
    const val ACCETTA = "accetta"
    const val RIFIUTA = "rifiuta"
}

// --- Dichiarazioni e verdetti (tappa 5) -------------------------------------
// Il figlio dichiara com'è andata una regola di vita reale; il successo resta
// `in_attesa` finché il genitore conferma / conferma per conto dell'arbitro /
// ribalta (contratto-api.md, sezione Dichiarazioni).

@Serializable
data class Dichiarazione(
    val id: Long,
    @SerialName("regola_id") val regolaId: Long,
    val giorno: String = "",
    val esito: String,
    val nota: String? = null,
    val stato: String,
    @SerialName("ts_server") val tsServer: String = "",
    val verdetto: Verdetto? = null,
)

/** Il verdetto del genitore su una dichiarazione (presente quando emesso). */
@Serializable
data class Verdetto(
    val verdetto: String,
    val nota: String? = null,
    // (v2.1) La frase autoritativa del registro, congelata dal server al momento
    // del verdetto (es. "confermato dal genitore per conto di Nonna"): l'app la
    // mostra così com'è, senza ricostruirla — cita l'arbitro di allora.
    val registro: String? = null,
    @SerialName("ts_server") val tsServer: String = "",
)

@Serializable
data class PaccoDichiarazioni(val dichiarazioni: List<Dichiarazione> = emptyList())

/** Corpo di POST /api/dichiarazioni/{id}/verdetto. */
@Serializable
data class CorpoVerdetto(
    val verdetto: String,
    val nota: String? = null,
)

object StatiDichiarazione {
    const val REGISTRATA = "registrata"
    const val IN_ATTESA = "in_attesa"
    const val CONFERMATA = "confermata"
    const val CONFERMATA_PER_CONTO = "confermata_per_conto"
    const val RIBALTATA = "ribaltata"
}

object EsitiDichiarazione {
    const val SUCCESSO = "successo"
    const val FALLIMENTO = "fallimento"
}

object TipiVerdetto {
    const val CONFERMA = "conferma"
    const val CONFERMA_PER_CONTO = "conferma_per_conto"
    const val RIBALTA = "ribalta"
}

// --- Versioni e auto-aggiornamento (tappa 6) --------------------------------
// GET /api/versione (senza auth): l'ultima versione disponibile di ciascuna app.
// L'app confronta `versione_code` col proprio versionCode e, se il server è più
// avanti, scarica `url` (relativo al base del server) e lancia PackageInstaller
// (contratto-api.md, sezione "GET /api/versione").

@Serializable
data class InfoVersioni(
    val figlio: InfoApp? = null,
    val genitore: InfoApp? = null,
)

@Serializable
data class InfoApp(
    @SerialName("versione_code") val versioneCode: Int,
    @SerialName("versione_nome") val versioneNome: String = "",
    val url: String = "",
    val note: String? = null,
)
