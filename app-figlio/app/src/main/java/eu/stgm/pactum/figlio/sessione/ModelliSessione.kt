package eu.stgm.pactum.figlio.sessione

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

// (0.11) Le Sessioni del contratto v3.5: "Studio", "Lavoro". Una lista di app
// che il figlio si sceglie, il genitore approva una volta, e lui avvia quando
// vuole per quanto vuole. ATTENZIONE: niente a che vedere con le "sessioni" di
// misura/Sessioni.kt, che sono i pezzi di tempo in primo piano di un'app.
// Qui: le forme del contratto, lette a mano e con pazienza. Un campo scritto
// male non deve mai far cadere la lettura del patto, né far partire una
// barriera su dati sbagliati.

object StatiSessione {
    const val IN_ATTESA = "in_attesa"
    const val APPROVATA = "approvata"
    const val RIFIUTATA = "rifiutata"
}

object ChiusureSessione {
    /** Finita da sola, allo scadere della durata. */
    const val SCADUTA = "scaduta"

    /** Chiusa prima dal figlio, con "Termina la sessione". */
    const val TERMINATA = "terminata"
}

/**
 * Un cambio chiesto su una sessione già approvata: aspetta il genitore. Il
 * server lo manda sempre completo (quello che la sessione sarà se il genitore
 * approva); i null qui sono solo per un server che non lo dicesse.
 */
data class ModificaSessione(
    val nome: String?,
    val app: List<String>?,
    val nomi: Map<String, String>,
    val richiestaTs: Long? = null,
)

/**
 * Una sessione come la definisce il figlio (`GET /api/sessioni`, `sessioni` di
 * `GET /api/patto`). [app] = nomi di pacchetti e, se c'è, `gruppo:apk`;
 * [nomi] = le etichette leggibili mandate dal telefono. [versione] = cresce a
 * ogni cambio (serve al genitore per dire che cosa ha visto quando decide).
 */
data class SessioneDefinita(
    val id: Long,
    val nome: String,
    val app: List<String>,
    val nomi: Map<String, String>,
    val stato: String,
    val modificaInAttesa: ModificaSessione?,
    val motivazione: String?,
    val dispositivoId: Long? = null,
    val versione: Long? = null,
    /** (0.15, contratto v3.6) Il nome del genitore dell'ultima decisione, se il server lo dice. */
    val decisaDa: String? = null,
) {
    val approvata: Boolean get() = stato == StatiSessione.APPROVATA
    val inAttesa: Boolean get() = stato == StatiSessione.IN_ATTESA
    val rifiutata: Boolean get() = stato == StatiSessione.RIFIUTATA
}

/**
 * Una sessione svolta (contratto v3.5): nome, app e nomi congelati all'avvio.
 * Gli istanti sono epoch ms, dal `…_ts` ISO del server; null se mancano o non
 * si leggono.
 */
data class SessioneSvolta(
    val id: Long,
    val sessioneId: Long?,
    val nome: String,
    val app: List<String>,
    val nomi: Map<String, String>,
    val inizio: Long?,
    val durataMinuti: Int?,
    val finePrevista: Long?,
    val fine: Long?,
    val chiusura: String?,
    val inCorso: Boolean?,
) {
    /**
     * La copia da tenere sul telefono, null se non regge: senza un inizio e una
     * fine prevista leggibili non si sa quando vale, e una sessione di cui non
     * si sa quando vale non copre niente (fail open). Una durata oltre le 24 ore
     * del contratto si taglia a 24 ore.
     */
    fun inLocale(): SvoltaLocale? {
        if (id <= 0) return null
        val da = inizio ?: return null
        val prevista = finePrevista ?: durataMinuti?.takeIf { it > 0 }?.let { da + it * MINUTO_MS } ?: return null
        if (prevista <= da) return null
        return SvoltaLocale(
            id = id,
            sessioneId = sessioneId,
            nome = nome,
            app = app,
            nomi = nomi,
            inizio = da,
            finePrevista = minOf(prevista, da + DURATA_MASSIMA_MS),
            fineServer = fine?.coerceAtLeast(da),
            chiusura = chiusura,
        )
    }

    companion object {
        private const val MINUTO_MS = 60_000L

        /** 1440 minuti (contratto) più un minuto di tolleranza sugli orologi. */
        const val DURATA_MASSIMA_MS = (1440L + 1) * MINUTO_MS
    }
}

/** POST /api/sessioni. `nomi` viaggia solo se c'è (encodeDefaults = false). */
@Serializable
data class SessioneIn(
    val nome: String,
    val app: List<String>,
    val nomi: Map<String, String>? = null,
)

/** PATCH /api/sessioni/{id}: si manda sempre la sessione intera che si vuole. */
@Serializable
data class SessioneModificaIn(
    val nome: String? = null,
    val app: List<String>? = null,
    val nomi: Map<String, String>? = null,
)

/** POST /api/sessioni/{id}/avvia. */
@Serializable
data class AvvioSessioneIn(@SerialName("durata_minuti") val durataMinuti: Int)

/**
 * POST /api/sessioni/in_corso/termina: la fine vera, anche se arriva dopo, e
 * QUALE sessione svolta si chiude ([svoltaId], mandato sempre): una chiusura
 * rimasta in coda non chiude mai una sessione avviata dopo (il server
 * risponde 404 se non è quella).
 */
@Serializable
data class TerminaSessioneIn(
    @SerialName("ts_device") val tsDevice: Long,
    @SerialName("svolta_id") val svoltaId: Long,
)

/**
 * La lettura delle forme del contratto (logica pura). Tutto ciò che non si
 * legge diventa null o resta fuori: mai un'eccezione verso chi chiama.
 */
object LetturaSessioni {

    private val json = Json { ignoreUnknownKeys = true }

    /** Il JSON di un corpo di risposta, null se non è JSON. */
    fun albero(corpo: String?): JsonElement? =
        corpo?.takeIf { it.isNotBlank() }?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }

    /** Le sessioni da un array (`sessioni` del patto); null se l'elemento non è un array. */
    fun definite(elemento: JsonElement?): List<SessioneDefinita>? =
        (elemento as? JsonArray)?.mapNotNull { definita(it) }

    /** Le sessioni dal corpo di `GET /api/sessioni`: `{ "sessioni": [ … ] }`. */
    fun elenco(corpo: String?): List<SessioneDefinita>? {
        val radice = albero(corpo) ?: return null
        return when (radice) {
            is JsonObject -> definite(radice["sessioni"])
            is JsonArray -> definite(radice)
            else -> null
        }
    }

    fun definita(elemento: JsonElement?): SessioneDefinita? {
        val o = elemento as? JsonObject ?: return null
        val id = intero(o["id"]) ?: return null
        val modifica = (o["modifica_in_attesa"] as? JsonObject)?.let {
            ModificaSessione(
                nome = testo(it["nome"]),
                app = (it["app"] as? JsonArray)?.let(::stringhe),
                nomi = mappa(it["nomi"]),
                richiestaTs = istante(it["richiesta_ts"]),
            )
        }
        return SessioneDefinita(
            id = id,
            nome = testo(o["nome"]).orEmpty(),
            app = stringhe(o["app"]),
            nomi = mappa(o["nomi"]),
            stato = testo(o["stato"]).orEmpty(),
            modificaInAttesa = modifica,
            motivazione = testo(o["motivazione"])?.takeIf { it.isNotBlank() },
            dispositivoId = intero(o["dispositivo_id"]) ?: (o["dispositivo"] as? JsonObject)?.let { intero(it["id"]) },
            versione = intero(o["versione"]),
            decisaDa = eu.stgm.pactum.figlio.faccende.LetturaFaccende.nomeGenitore(o["decisa_da"]),
        )
    }

    fun svolta(elemento: JsonElement?): SessioneSvolta? {
        val o = elemento as? JsonObject ?: return null
        val id = intero(o["id"]) ?: return null
        return SessioneSvolta(
            id = id,
            sessioneId = intero(o["sessione_id"]),
            nome = testo(o["nome"]).orEmpty(),
            app = stringhe(o["app"]),
            nomi = mappa(o["nomi"]),
            inizio = istante(o["inizio_ts"]),
            durataMinuti = intero(o["durata_minuti"])?.toInt(),
            finePrevista = istante(o["fine_prevista_ts"]),
            fine = istante(o["fine_ts"]),
            chiusura = testo(o["chiusura"]),
            inCorso = (o["in_corso"] as? JsonPrimitive)?.booleanOrNull,
        )
    }

    /** Le sessioni svolte da un array (`sessioni_svolte`); vuota se non è un array. */
    fun svolte(elemento: JsonElement?): List<SessioneSvolta> =
        (elemento as? JsonArray)?.mapNotNull { svolta(it) }.orEmpty()

    /** La sessione svolta dal corpo di una risposta (avvia, termina). */
    fun svoltaDaCorpo(corpo: String?): SessioneSvolta? = svolta(albero(corpo))

    /** La sessione dal corpo di una risposta (crea, modifica). */
    fun definitaDaCorpo(corpo: String?): SessioneDefinita? = definita(albero(corpo))

    /**
     * Un istante del server: ISO 8601 con offset ("2026-10-01T14:00:00+00:00",
     * o con "Z"), oppure senza offset (si legge in UTC, come lo scrive il
     * server). Un numero vale come epoch (secondi o millisecondi).
     */
    fun istante(elemento: JsonElement?): Long? {
        val p = elemento as? JsonPrimitive ?: return null
        if (!p.isString) {
            val n = p.longOrNull ?: return null
            return if (n in 1..99_999_999_999L) n * 1000 else n.takeIf { it > 0 }
        }
        val t = p.content.trim().ifEmpty { return null }
        return try {
            OffsetDateTime.parse(t).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            try {
                LocalDateTime.parse(t).toInstant(ZoneOffset.UTC).toEpochMilli()
            } catch (e: DateTimeParseException) {
                null
            }
        }
    }

    private fun testo(elemento: JsonElement?): String? = (elemento as? JsonPrimitive)?.contentOrNull

    private fun intero(elemento: JsonElement?): Long? {
        val p = elemento as? JsonPrimitive ?: return null
        return p.longOrNull ?: p.contentOrNull?.trim()?.toLongOrNull()
    }

    /** Le stringhe di un array, senza vuoti né doppioni, nell'ordine in cui arrivano. */
    private fun stringhe(elemento: JsonElement?): List<String> =
        (elemento as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
            ?.distinct()
            .orEmpty()

    private fun mappa(elemento: JsonElement?): Map<String, String> =
        (elemento as? JsonObject)
            ?.mapNotNull { (chiave, valore) ->
                (valore as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { chiave to it }
            }
            ?.toMap()
            .orEmpty()
}
