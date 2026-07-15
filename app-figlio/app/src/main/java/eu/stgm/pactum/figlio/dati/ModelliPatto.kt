package eu.stgm.pactum.figlio.dati

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Le forme del contratto per il patto completo (tappa 5, docs/contratto-api.md,
 * sezione "Endpoint del figlio"). I `ts_server` sono ISO 8601 UTC e fanno fede;
 * si mostrano nel fuso del telefono. Campi sconosciuti ignorati (tolleranza
 * evolutiva): l'app non si rompe se il server aggiunge chiavi.
 */

/** GET /api/patto — lo stato completo per il sync dell'app del figlio. */
@Serializable
data class Patto(
    val regole: List<Regola> = emptyList(),
    val bonus: StatoBonus? = null,
    // Chiave = regola_id come stringa (contratto): minuti bonus concessi OGGI.
    @SerialName("bonus_oggi_per_regola") val bonusOggiPerRegola: Map<String, Int> = emptyMap(),
    @SerialName("proposte_pendenti") val propostePendenti: List<Proposta> = emptyList(),
    @SerialName("dichiarazioni_in_attesa") val dichiarazioniInAttesa: List<Dichiarazione> = emptyList(),
    val fuso: String? = null,
)

@Serializable
data class Regola(
    val id: Long,
    val tipo: String,
    val parametri: JsonObject = JsonObject(emptyMap()),
    val attiva: Boolean = true,
    @SerialName("creata_ts") val creataTs: String = "",
    @SerialName("ultima_modifica_ts") val ultimaModificaTs: String = "",
    @SerialName("allentabile_dal") val allentabileDal: String? = null,
)

object TipiRegola {
    const val LIMITE_TEMPO = "limite_tempo"
    const val FASCIA_ORARIA = "fascia_oraria"
    const val VITA_REALE = "vita_reale"
}

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

object TipiNotifica {
    const val NUOVA_PROPOSTA = "nuova_proposta"
    const val VERDETTO = "verdetto"
}

@Serializable
data class ContatoreBonus(val usati: Int = 0, val tetto: Int = 0, val residui: Int = 0)

@Serializable
data class StatoBonus(
    val giorno: ContatoreBonus = ContatoreBonus(),
    val settimana: ContatoreBonus = ContatoreBonus(),
)

@Serializable
data class Proposta(
    val id: Long,
    @SerialName("regola_id") val regolaId: Long,
    @SerialName("parametri_proposti") val parametriProposti: JsonObject? = null,
    val motivazione: String? = null,
    val confronto: String? = null,
    val direzione: String? = null,
    val stato: String,
    val usata: Boolean = false,
    @SerialName("ts_server") val tsServer: String = "",
    val risposta: RispostaProposta? = null,
)

@Serializable
data class RispostaProposta(
    val esito: String,
    val motivazione: String? = null,
    @SerialName("ts_server") val tsServer: String = "",
)

@Serializable
data class Dichiarazione(
    val id: Long,
    @SerialName("regola_id") val regolaId: Long,
    val giorno: String = "",
    val esito: String,
    val nota: String? = null,
    val stato: String,
    @SerialName("ts_server") val tsServer: String = "",
    val verdetto: VerdettoDichiarazione? = null,
)

@Serializable
data class VerdettoDichiarazione(
    val verdetto: String,
    val nota: String? = null,
    val registro: String? = null,
    @SerialName("ts_server") val tsServer: String = "",
)

@Serializable
data class Notifica(
    val id: Long,
    val tipo: String,
    val messaggio: String,
    val payload: JsonObject = JsonObject(emptyMap()),
    @SerialName("ts_server") val tsServer: String = "",
)

// Buste degli elenchi.
@Serializable
data class PaccoProposte(val proposte: List<Proposta> = emptyList())

@Serializable
data class PaccoDichiarazioni(val dichiarazioni: List<Dichiarazione> = emptyList())

@Serializable
data class PaccoNotifiche(val notifiche: List<Notifica> = emptyList())

// Corpi delle richieste (POST/PATCH del figlio).
@Serializable
data class CreaRegolaIn(val tipo: String, val parametri: JsonObject)

@Serializable
data class ModificaRegolaIn(
    val parametri: JsonObject,
    @SerialName("proposta_id") val propostaId: Long? = null,
)

@Serializable
data class BonusIn(
    val minuti: Int,
    @SerialName("regola_id") val regolaId: Long,
    val motivo: String? = null,
)

@Serializable
data class RispostaPropostaIn(val esito: String, val motivazione: String? = null)

@Serializable
data class DichiarazioneIn(
    @SerialName("regola_id") val regolaId: Long,
    val esito: String,
    val nota: String? = null,
    val giorno: String? = null,
)

/**
 * Il corpo d'errore del server (FastAPI HTTPException → {"detail": {...}}).
 * `detail` è un oggetto per i 409 che ci interessano (lock, tetti, ultima
 * regola); per i 422 è una lista e resta null qui (si mostra un messaggio
 * generico). Tutti i campi opzionali: si legge quello che serve caso per caso.
 */
@Serializable
data class RispostaErrore(val detail: DettaglioErrore? = null)

@Serializable
data class DettaglioErrore(
    val errore: String? = null,
    @SerialName("secondi_rimanenti") val secondiRimanenti: Long? = null,
    @SerialName("sblocco_ts") val sbloccoTs: String? = null,
    @SerialName("residuo_giorno") val residuoGiorno: Int? = null,
    @SerialName("residuo_settimana") val residuoSettimana: Int? = null,
)

private val jsonErrori = Json { ignoreUnknownKeys = true }

/**
 * Il dettaglio d'errore da un corpo non-2xx, null se il corpo non è il 409
 * strutturato che ci interessa (es. la lista dei 422): chi chiama mostra
 * allora un messaggio generico.
 */
fun leggiDettaglioErrore(corpo: String?): DettaglioErrore? {
    if (corpo.isNullOrBlank()) return null
    return runCatching {
        jsonErrori.decodeFromString(RispostaErrore.serializer(), corpo).detail
    }.getOrNull()
}
