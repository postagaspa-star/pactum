package eu.stgm.pactum.genitore.rete

import eu.stgm.pactum.genitore.dati.ConfigurazionePostino
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.Notifica
import eu.stgm.pactum.genitore.dati.PaccoNotifiche
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

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

    companion object {
        private val CORPO_VUOTO = ByteArray(0).toRequestBody(null)
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
