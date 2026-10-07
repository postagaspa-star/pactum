package eu.stgm.pactum.figlio.studio

import eu.stgm.pactum.figlio.faccende.Istante
import eu.stgm.pactum.figlio.sessione.LetturaSessioni
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** (0.18) Com'è andata la consegna di una chiusura dello Studio. */
sealed interface EsitoChiusura {
    data class Chiusa(val studio: StudioSvolto?) : EsitoChiusura

    /** 409 `gia_chiuso`: per il server lo Studio è già chiuso (dal genitore, a mezzanotte). */
    data class GiaChiusa(val studio: StudioSvolto?) : EsitoChiusura

    /** 409 `troppo_presto` / `attivita_insufficiente`, o 422: lo Studio torna, col motivo. */
    data class Rifiutata(
        val motivo: String,
        val studio: StudioSvolto?,
        val chiudibileDal: Long?,
        val minuti: Int?,
        val minimi: Int?,
    ) : EsitoChiusura

    /** 404 «studio non trovato»: il server non lo conosce (oltre le 48 ore). */
    data object NonTrovata : EsitoChiusura
    data object ServerVecchio : EsitoChiusura
    data object Scollegato : EsitoChiusura
    data object SenzaRete : EsitoChiusura
    data object Errore : EsitoChiusura
}

/** (0.18) Com'è andato l'avvio a mano. */
sealed interface EsitoAvvioStudio {
    data class Avviato(val studio: StudioSvolto?) : EsitoAvvioStudio

    /** 409 `studio_non_approvato`, `troppo_tardi`, `blocco_faccende`, `avvio_scaduto` (o un 422). */
    data class Rifiutato(val motivo: String) : EsitoAvvioStudio
    data object ServerVecchio : EsitoAvvioStudio
    data object Scollegato : EsitoAvvioStudio
    data object SenzaRete : EsitoAvvioStudio
    data object Errore : EsitoAvvioStudio
}

/** (0.18) Com'è andato l'invio dei tratti. */
enum class EsitoTratti { CONSEGNATI, SCARTATI, SERVER_VECCHIO, SCOLLEGATO, SENZA_RETE, ERRORE }

/** (0.18) Com'è andata una proposta o un ritiro della configurazione. */
sealed interface EsitoProposta {
    data class Fatta(val config: ConfigStudio?) : EsitoProposta

    /** Un no del server col suo codice (`orari_impossibili`, `niente_da_ritirare`, …). */
    data class No(val errore: String?) : EsitoProposta
    data object ServerVecchio : EsitoProposta
    data object Scollegato : EsitoProposta
    data object SenzaRete : EsitoProposta
    data object Errore : EsitoProposta
}

/** (0.18) Le risposte del server sullo Studio (logica pura). */
object EsitiStudio {

    const val TROPPO_PRESTO = "troppo_presto"
    const val ATTIVITA_INSUFFICIENTE = "attivita_insufficiente"
    const val GIA_CHIUSO = "gia_chiuso"
    const val NON_VALIDA = "non_valida"

    /** Un 404 che parla di uno Studio che non c'è (non il "Not Found" generico di un server vecchio). */
    private fun studioNonTrovato(corpo: String?): Boolean {
        val radice = LetturaSessioni.albero(corpo) as? JsonObject ?: return false
        val dettaglio = (radice["detail"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() ?: return false
        return dettaglio != "not found" && "studio" in dettaglio
    }

    fun chiusura(ok: Boolean, codice: Int, corpo: String?): EsitoChiusura {
        if (ok) return EsitoChiusura.Chiusa(LetturaStudio.svoltoDaCorpo(corpo))
        val errore = LetturaStudio.errore(corpo)
        return when {
            errore == GIA_CHIUSO -> EsitoChiusura.GiaChiusa(LetturaStudio.studioNellErrore(corpo))
            errore == TROPPO_PRESTO || errore == ATTIVITA_INSUFFICIENTE -> EsitoChiusura.Rifiutata(
                motivo = errore,
                studio = LetturaStudio.studioNellErrore(corpo),
                chiudibileDal = LetturaStudio.istanteNellErrore(corpo, "chiudibile_dal"),
                minuti = LetturaStudio.numeroNellErrore(corpo, "minuti")?.toInt(),
                minimi = LetturaStudio.numeroNellErrore(corpo, "minimi")?.toInt(),
            )
            errore == "dispositivo_revocato" -> EsitoChiusura.Scollegato
            codice == 404 -> if (studioNonTrovato(corpo)) EsitoChiusura.NonTrovata else EsitoChiusura.ServerVecchio
            codice == 405 -> EsitoChiusura.ServerVecchio
            codice == 422 -> EsitoChiusura.Rifiutata(NON_VALIDA, null, null, null, null)
            codice == 401 -> EsitoChiusura.Scollegato
            codice == 0 -> EsitoChiusura.SenzaRete
            else -> EsitoChiusura.Errore
        }
    }

    fun avvio(ok: Boolean, codice: Int, corpo: String?): EsitoAvvioStudio {
        if (ok) return EsitoAvvioStudio.Avviato(LetturaStudio.svoltoDaCorpo(corpo))
        val errore = LetturaStudio.errore(corpo)
        return when {
            errore == "dispositivo_revocato" -> EsitoAvvioStudio.Scollegato
            codice == 409 && errore != null -> EsitoAvvioStudio.Rifiutato(errore)
            codice == 404 || codice == 405 -> EsitoAvvioStudio.ServerVecchio
            codice == 422 -> EsitoAvvioStudio.Rifiutato(errore ?: NON_VALIDA)
            codice == 401 -> EsitoAvvioStudio.Scollegato
            codice == 0 -> EsitoAvvioStudio.SenzaRete
            else -> EsitoAvvioStudio.Errore
        }
    }

    fun tratti(ok: Boolean, codice: Int, corpo: String?): EsitoTratti = when {
        ok -> EsitoTratti.CONSEGNATI
        LetturaStudio.errore(corpo) == "dispositivo_revocato" -> EsitoTratti.SCOLLEGATO
        codice == 422 -> EsitoTratti.SCARTATI
        codice == 404 || codice == 405 -> EsitoTratti.SERVER_VECCHIO
        codice == 401 -> EsitoTratti.SCOLLEGATO
        codice == 0 -> EsitoTratti.SENZA_RETE
        else -> EsitoTratti.ERRORE
    }

    fun proposta(ok: Boolean, codice: Int, corpo: String?): EsitoProposta {
        if (ok) return EsitoProposta.Fatta(LetturaStudio.configDaCorpo(corpo))
        val errore = LetturaStudio.errore(corpo)
        return when {
            errore == "dispositivo_revocato" -> EsitoProposta.Scollegato
            codice == 409 || codice == 422 -> EsitoProposta.No(errore)
            codice == 404 || codice == 405 -> EsitoProposta.ServerVecchio
            codice == 401 -> EsitoProposta.Scollegato
            codice == 0 -> EsitoProposta.SenzaRete
            else -> EsitoProposta.Errore
        }
    }
}

/** (0.18) I corpi delle richieste dello Studio (logica pura). */
object CorpiStudio {

    /** `POST /api/studio/avvia`: `{ "chiave", "ts_device" }` (l'inizio dichiarato, sull'ora del server). */
    fun avvia(a: AvvioManuale): String = buildJsonObject {
        put("chiave", a.chiave)
        put("ts_device", a.inizio)
    }.toString()

    /**
     * `POST /api/studio/{id}/chiudi` (o `/api/studio/chiudi` con `studio`):
     * `{ "chiave", "ts_device", "dichiarazione", "tratti"?, "studio"? }`.
     * Senza id: `studio: { "chiave" }` per uno Studio a mano, altrimenti
     * `studio: { "giorno" }` (lo Studio della partenza di quel giorno).
     */
    fun chiudi(c: ChiusuraLocale, tratti: List<TrattoLocale>, ora: Istante): String = buildJsonObject {
        put("chiave", c.chiave)
        put("ts_device", c.fine)
        put("dichiarazione", c.dichiarazione)
        if (tratti.isNotEmpty()) put("tratti", buildJsonArray { tratti.take(50).forEach { add(it.corpo(ora)) } })
        if (c.rif.id == null) {
            putJsonObject("studio") {
                if (c.rif.chiave != null) put("chiave", c.rif.chiave) else put("giorno", c.rif.giorno.orEmpty())
            }
        }
    }.toString()

    fun tratti(tratti: List<TrattoLocale>, ora: Istante): String = buildJsonObject {
        putJsonArray("tratti") { tratti.take(50).forEach { add(it.corpo(ora)) } }
    }.toString()

    /**
     * `PATCH /api/studio/config`: solo i campi che cambiano rispetto a
     * [base] (la proposta in attesa, se c'è, o quella approvata). Senza base
     * (prima proposta) tutti. `telefono` va intero.
     */
    fun proposta(nuova: ContenutoStudio, base: ContenutoStudio?): String? {
        val o = buildJsonObject {
            if (base == null || nuova.giorni.toSet() != base.giorni.toSet()) putJsonArray("giorni") { nuova.giorni.forEach { add(JsonPrimitive(it)) } }
            if (base == null || nuova.inizio != base.inizio) put("inizio", nuova.inizio)
            if (base == null || nuova.chiusuraMinima != base.chiusuraMinima) put("chiusura_minima", nuova.chiusuraMinima)
            if (base == null || nuova.minutiMinimi != base.minutiMinimi) put("minuti_minimi", nuova.minutiMinimi)
            if (base == null || nuova.app.toSet() != base.app.toSet() || nuova.nomi != base.nomi) {
                putJsonObject("telefono") {
                    putJsonArray("app") { nuova.app.forEach { add(JsonPrimitive(it)) } }
                    putJsonObject("nomi") { nuova.nomi.filterKeys { it in nuova.app }.forEach { (k, v) -> put(k, v) } }
                }
            }
        }
        return o.takeIf { it.isNotEmpty() }?.toString()
    }
}
