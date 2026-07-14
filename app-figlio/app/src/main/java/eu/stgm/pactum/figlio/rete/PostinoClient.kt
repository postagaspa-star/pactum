package eu.stgm.pactum.figlio.rete

import eu.stgm.pactum.figlio.dati.Battito
import eu.stgm.pactum.figlio.dati.ConfigurazionePostino
import eu.stgm.pactum.figlio.dati.Evento
import eu.stgm.pactum.figlio.dati.PaccoEventi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client verso il server postino. Protocollo: docs/contratto-api.md
 * (fonte di verità — ogni modifica passa prima da lì).
 *
 *   POST {base}/api/battito   corpo: Battito              → 2xx = ricevuto
 *   POST {base}/api/eventi    corpo: {"eventi": [Evento]} → 2xx = ricevuto
 *   header: Authorization: Bearer <token del figlio>
 *
 * Tollerante all'offline: qualunque fallimento restituisce false e gli
 * eventi restano nella CodaEventi fino al battito successivo.
 */
class PostinoClient(private val configurazione: ConfigurazionePostino) {

    suspend fun inviaBattito(battito: Battito): Boolean =
        invia("/api/battito", json.encodeToString(Battito.serializer(), battito))

    suspend fun inviaEventi(eventi: List<Evento>): Boolean {
        if (eventi.isEmpty()) return true
        return invia("/api/eventi", json.encodeToString(PaccoEventi.serializer(), PaccoEventi(eventi)))
    }

    private suspend fun invia(percorso: String, corpo: String): Boolean {
        if (!configurazione.completa) return false
        return withContext(Dispatchers.IO) {
            try {
                val richiesta = Request.Builder()
                    .url(configurazione.serverUrl + percorso)
                    .header("Authorization", "Bearer ${configurazione.token}")
                    .post(corpo.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                http.newCall(richiesta).execute().use { risposta -> risposta.isSuccessful }
            } catch (e: IOException) {
                false
            } catch (e: IllegalArgumentException) {
                // URL malformato nelle impostazioni: non è un motivo per crashare il worker.
                false
            }
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val json = Json { ignoreUnknownKeys = true }

        // Un solo client OkHttp per processo: riusa pool di connessioni e thread.
        val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
