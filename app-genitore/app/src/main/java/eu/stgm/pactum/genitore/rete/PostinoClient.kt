package eu.stgm.pactum.genitore.rete

import eu.stgm.pactum.genitore.dati.ConfigurazionePostino
import eu.stgm.pactum.genitore.dati.CorpoVerdetto
import eu.stgm.pactum.genitore.dati.Dichiarazione
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.InfoVersioni
import eu.stgm.pactum.genitore.dati.Notifica
import eu.stgm.pactum.genitore.dati.NuovaProposta
import eu.stgm.pactum.genitore.dati.PaccoDichiarazioni
import eu.stgm.pactum.genitore.dati.PaccoNotifiche
import eu.stgm.pactum.genitore.dati.PaccoProposte
import eu.stgm.pactum.genitore.dati.Proposta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * L'esito di una scrittura verso il postino. `Riuscito` porta il dato creato
 * dal server (la proposta col confronto, la dichiarazione col verdetto);
 * `Rifiutato` è un 409 col codice `errore` del contratto (es. proposta già
 * pendente); `Fallito` è tutto il resto (offline, altro HTTP, JSON inatteso).
 */
sealed interface EsitoScrittura<out T> {
    data class Riuscito<T>(val dato: T) : EsitoScrittura<T>
    data class Rifiutato(val errore: String?) : EsitoScrittura<Nothing>
    data object Fallito : EsitoScrittura<Nothing>
}

/**
 * Client verso il server postino, lato genitore. Protocollo: docs/contratto-api.md
 * (fonte di verità — ogni modifica passa prima da lì).
 *
 *   GET  {base}/api/finestra                 → Finestra
 *   GET  {base}/api/notifiche                → {"notifiche": [...]} (solo non lette)
 *   POST {base}/api/notifiche/{id}/letta     → 2xx = segnata
 *   header: Authorization: Bearer <token del genitore>
 *
 * Tollerante all'offline: qualunque fallimento (rete, HTTP non-2xx, JSON
 * inatteso) restituisce null/false — chi chiama decide se riprovare.
 */
class PostinoClient(private val configurazione: ConfigurazionePostino) {

    suspend fun leggiFinestra(): Finestra? =
        leggi("/api/finestra")?.let { decodifica(Finestra.serializer(), it) }

    suspend fun leggiNotifiche(): List<Notifica>? =
        leggi("/api/notifiche")
            ?.let { decodifica(PaccoNotifiche.serializer(), it) }
            ?.notifiche

    suspend fun leggiProposte(): List<Proposta>? =
        leggi("/api/proposte")
            ?.let { decodifica(PaccoProposte.serializer(), it) }
            ?.proposte

    suspend fun leggiDichiarazioni(): List<Dichiarazione>? =
        leggi("/api/dichiarazioni")
            ?.let { decodifica(PaccoDichiarazioni.serializer(), it) }
            ?.dichiarazioni

    /**
     * GET /api/versione (tappa 6): l'ultima versione disponibile delle due app.
     * Endpoint pubblico (nessun auth), ma passa dal solito `leggi` — l'header
     * Bearer è innocuo su un endpoint pubblico e ci serve comunque il base URL
     * configurato per sapere da dove scaricare. null = offline o risposta strana.
     */
    suspend fun leggiVersione(): InfoVersioni? =
        leggi("/api/versione")?.let { decodifica(InfoVersioni.serializer(), it) }

    /**
     * Scarica l'APK di aggiornamento in [destinazione] (streaming, per non
     * tenere ~10 MB in memoria). [url] è quello di GET /api/versione: relativo
     * al base del server (es. `/scarica/pactum-genitore.apk`) o assoluto.
     * Nessun auth: è il download di un file pubblico; la firma dell'APK (stessa
     * chiave) è la vera garanzia d'integrità (contratto-api.md). false = fallito.
     */
    suspend fun scaricaApk(url: String, destinazione: File): Boolean {
        if (!configurazione.completa) return false
        val assoluto = if ("://" in url) url else configurazione.serverUrl + url
        return withContext(Dispatchers.IO) {
            try {
                val richiesta = Request.Builder().url(assoluto).get().build()
                http.newCall(richiesta).execute().use { risposta ->
                    val corpo = risposta.body
                    if (!risposta.isSuccessful || corpo == null) return@use false
                    destinazione.outputStream().use { uscita ->
                        corpo.byteStream().copyTo(uscita)
                    }
                    true
                }
            } catch (e: IOException) {
                false
            } catch (e: IllegalArgumentException) {
                false // URL malformato: non è un motivo per crashare.
            }
        }
    }

    /** POST /api/proposte: la proposta creata (col confronto del server) o l'errore. */
    suspend fun creaProposta(nuova: NuovaProposta): EsitoScrittura<Proposta> {
        val corpo = json.encodeToString(NuovaProposta.serializer(), nuova)
        val risposta = scrivi("/api/proposte", corpo) ?: return EsitoScrittura.Fallito
        return interpreta(risposta, Proposta.serializer())
    }

    /** POST /api/dichiarazioni/{id}/verdetto: la dichiarazione aggiornata o l'errore. */
    suspend fun emettiVerdetto(
        dichiarazioneId: Long,
        corpoVerdetto: CorpoVerdetto,
    ): EsitoScrittura<Dichiarazione> {
        val corpo = json.encodeToString(CorpoVerdetto.serializer(), corpoVerdetto)
        val risposta = scrivi("/api/dichiarazioni/$dichiarazioneId/verdetto", corpo)
            ?: return EsitoScrittura.Fallito
        return interpreta(risposta, Dichiarazione.serializer())
    }

    suspend fun segnaLetta(notificaId: Long): Boolean = withContext(Dispatchers.IO) {
        if (!configurazione.completa) return@withContext false
        try {
            val richiesta = richiesta("/api/notifiche/$notificaId/letta")
                .post(CORPO_VUOTO)
                .build()
            http.newCall(richiesta).execute().use { it.isSuccessful }
        } catch (e: IOException) {
            false
        } catch (e: IllegalArgumentException) {
            false // URL malformato nelle impostazioni: non è un motivo per crashare.
        }
    }

    /** Una POST con corpo JSON: codice HTTP + corpo (letto sempre), null se la rete cade. */
    private suspend fun scrivi(percorso: String, corpo: String): RispostaHttp? {
        if (!configurazione.completa) return null
        return withContext(Dispatchers.IO) {
            try {
                val richiesta = richiesta(percorso)
                    .post(corpo.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                http.newCall(richiesta).execute().use { risposta ->
                    RispostaHttp(risposta.code, risposta.body?.string())
                }
            } catch (e: IOException) {
                null
            } catch (e: IllegalArgumentException) {
                null // URL malformato nelle impostazioni: non è un motivo per crashare.
            }
        }
    }

    private fun <T> interpreta(
        risposta: RispostaHttp,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): EsitoScrittura<T> = when {
        risposta.codice in 200..299 -> {
            val dato = risposta.corpo?.let { decodifica(serializer, it) }
            if (dato != null) EsitoScrittura.Riuscito(dato) else EsitoScrittura.Fallito
        }
        // 409 = rifiuto del contratto (proposta già pendente, dichiarazione non
        // più in attesa, regola non valida): si porta su il codice `errore`.
        risposta.codice == 409 -> EsitoScrittura.Rifiutato(estraiErrore(risposta.corpo))
        // 422 = validazione del server fallita (un campo libero non valido, es.
        // un orario o un giorno malformato nella proposta). Rifiuto distinto: il
        // corpo FastAPI porta `detail`, non `errore`, e un generico "riprova"
        // sarebbe fuorviante su un dato che va corretto, non ritentato.
        risposta.codice == 422 -> EsitoScrittura.Rifiutato(PARAMETRI_NON_VALIDI)
        else -> EsitoScrittura.Fallito
    }

    private fun estraiErrore(corpo: String?): String? {
        if (corpo == null) return null
        return try {
            val oggetto = json.parseToJsonElement(corpo) as? JsonObject ?: return null
            (oggetto["errore"] as? JsonPrimitive)?.content
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** Il corpo della risposta 2xx, null per qualunque fallimento. */
    private suspend fun leggi(percorso: String): String? {
        if (!configurazione.completa) return null
        return withContext(Dispatchers.IO) {
            try {
                val richiesta = richiesta(percorso).get().build()
                http.newCall(richiesta).execute().use { risposta ->
                    if (risposta.isSuccessful) risposta.body?.string() else null
                }
            } catch (e: IOException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }

    private fun richiesta(percorso: String): Request.Builder = Request.Builder()
        .url(configurazione.serverUrl + percorso)
        .header("Authorization", "Bearer ${configurazione.token}")

    private fun <T> decodifica(
        serializer: kotlinx.serialization.KSerializer<T>,
        corpo: String,
    ): T? = try {
        json.decodeFromString(serializer, corpo)
    } catch (e: SerializationException) {
        null // risposta inattesa: si tratta come un fallimento di rete
    } catch (e: IllegalArgumentException) {
        null
    }

    /** Codice HTTP + corpo grezzo di una risposta di scrittura. */
    private data class RispostaHttp(val codice: Int, val corpo: String?)

    companion object {
        // Codice d'errore sintetico per il 422 di validazione del server: non è
        // un codice del contratto (il 422 non ne porta uno), lo coniamo qui per
        // dare alla UI un messaggio specifico invece del generico "riprova".
        const val PARAMETRI_NON_VALIDI = "parametri_non_validi"

        private val CORPO_VUOTO = ByteArray(0).toRequestBody(null)
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }

        // Un solo client OkHttp per processo: riusa pool di connessioni e thread.
        private val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

        /**
         * Normalizza e valida l'indirizzo del server prima del salvataggio:
         * aggiunge https:// se manca lo schema e valida con okhttp3.HttpUrl
         * (che accetta SOLO http/https: tutto il resto viene rifiutato).
         * Restituisce null se l'indirizzo va rifiutato — un URL sbagliato
         * accettato in silenzio è un'app che non vede mai niente senza che
         * nessuno se ne accorga.
         */
        fun normalizzaUrlServer(grezzo: String): String? {
            val ripulito = grezzo.trim()
            if (ripulito.isEmpty()) return null
            val conSchema = if ("://" in ripulito) ripulito else "https://$ripulito"
            val analizzato = conSchema.toHttpUrlOrNull() ?: return null
            // La base si ricostruisce da zero tenendo SOLO schema, host, porta
            // e segmenti di percorso: query e frammento incollati prima di
            // "/api/..." produrrebbero endpoint rotti, e le credenziali
            // nell'URL non hanno motivo di sopravvivere al salvataggio.
            val base = HttpUrl.Builder()
                .scheme(analizzato.scheme)
                .host(analizzato.host)
                .port(analizzato.port)
                .apply { analizzato.pathSegments.forEach { addPathSegment(it) } }
                .build()
            // toString() aggiunge la "/" del percorso radice: via, perché i
            // percorsi ("/api/...") si concatenano dopo.
            return base.toString().trimEnd('/')
        }
    }
}
