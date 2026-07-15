package eu.stgm.pactum.figlio.rete

import eu.stgm.pactum.figlio.dati.Battito
import eu.stgm.pactum.figlio.dati.BonusIn
import eu.stgm.pactum.figlio.dati.ConfigurazionePostino
import eu.stgm.pactum.figlio.dati.CreaRegolaIn
import eu.stgm.pactum.figlio.dati.Dichiarazione
import eu.stgm.pactum.figlio.dati.DichiarazioneIn
import eu.stgm.pactum.figlio.dati.Evento
import eu.stgm.pactum.figlio.dati.InfoVersioni
import eu.stgm.pactum.figlio.dati.ModificaRegolaIn
import eu.stgm.pactum.figlio.dati.Notifica
import eu.stgm.pactum.figlio.dati.PaccoDichiarazioni
import eu.stgm.pactum.figlio.dati.PaccoEventi
import eu.stgm.pactum.figlio.dati.PaccoNotifiche
import eu.stgm.pactum.figlio.dati.PaccoProposte
import eu.stgm.pactum.figlio.dati.Patto
import eu.stgm.pactum.figlio.dati.Proposta
import eu.stgm.pactum.figlio.dati.RispostaPropostaIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client verso il server postino. Protocollo: docs/contratto-api.md
 * (fonte di verità — ogni modifica passa prima da lì).
 *
 *   POST {base}/api/battito · POST {base}/api/eventi   → consegna tollerante all'offline
 *   GET  {base}/api/patto · /api/proposte · /api/dichiarazioni · /api/notifiche
 *   POST/PATCH/DELETE {base}/api/regole · POST /api/bonus · /api/dichiarazioni ·
 *        /api/proposte/{id}/risposta                    → mutazioni con esito HTTP
 *   header: Authorization: Bearer <token del figlio>
 *
 * Battito ed eventi restituiscono Boolean (o passa o resta in coda). Le
 * mutazioni del patto restituiscono [RispostaHttp] (codice + corpo) perché
 * all'app serve distinguere un 409 di lock da un errore di rete e leggerne i
 * dettagli (secondi residui, tetto superato, ultima regola).
 */
class PostinoClient(private val configurazione: ConfigurazionePostino) {

    /** Esito grezzo di una mutazione: [codice] 0 = errore di rete o URL malformato. */
    data class RispostaHttp(val ok: Boolean, val codice: Int, val corpo: String?)

    suspend fun inviaBattito(battito: Battito): Boolean =
        inviaSemplice("/api/battito", json.encodeToString(Battito.serializer(), battito))

    suspend fun inviaEventi(eventi: List<Evento>): Boolean {
        if (eventi.isEmpty()) return true
        return inviaSemplice(
            "/api/eventi",
            json.encodeToString(PaccoEventi.serializer(), PaccoEventi(eventi)),
        )
    }

    // --- Letture (sync dell'app) --------------------------------------------

    suspend fun leggiPatto(): Patto? =
        leggi("/api/patto")?.let { decodifica(Patto.serializer(), it) }

    suspend fun leggiProposte(): List<Proposta>? =
        leggi("/api/proposte")?.let { decodifica(PaccoProposte.serializer(), it) }?.proposte

    suspend fun leggiDichiarazioni(): List<Dichiarazione>? =
        leggi("/api/dichiarazioni")
            ?.let { decodifica(PaccoDichiarazioni.serializer(), it) }
            ?.dichiarazioni

    suspend fun leggiNotifiche(): List<Notifica>? =
        leggi("/api/notifiche")?.let { decodifica(PaccoNotifiche.serializer(), it) }?.notifiche

    /**
     * GET /api/versione (nessun auth lato server; l'header non dà fastidio):
     * il metadata delle ultime versioni. null se offline, 404 (server vecchio
     * senza l'endpoint) o corpo inatteso — in tutti i casi "niente da aggiornare".
     */
    suspend fun leggiVersioni(): InfoVersioni? =
        leggi("/api/versione")?.let { decodifica(InfoVersioni.serializer(), it) }

    /**
     * Scarica un file (l'APK dell'aggiornamento) da un percorso RELATIVO al
     * server configurato, in streaming su [destinazione]. Rifiuta i percorsi
     * assoluti (`http…`): l'aggiornamento arriva SOLO dal server del patto,
     * mai da un host suggerito dal payload. `true` solo a download completo.
     */
    suspend fun scaricaSuFile(percorso: String, destinazione: File): Boolean {
        if (!configurazione.completa) return false
        if (!percorso.startsWith("/")) return false
        return withContext(Dispatchers.IO) {
            try {
                val richiesta = richiesta(percorso).get().build()
                http.newCall(richiesta).execute().use { risposta ->
                    val corpo = risposta.body
                    if (!risposta.isSuccessful || corpo == null) return@use false
                    destinazione.outputStream().use { out -> corpo.byteStream().copyTo(out) }
                    true
                }
            } catch (e: IOException) {
                false
            } catch (e: IllegalArgumentException) {
                false // URL malformato nelle impostazioni: non è un motivo per crashare.
            }
        }
    }

    // --- Mutazioni del patto -------------------------------------------------

    suspend fun creaRegola(corpo: CreaRegolaIn): RispostaHttp =
        mutazione("POST", "/api/regole", json.encodeToString(CreaRegolaIn.serializer(), corpo))

    suspend fun modificaRegola(regolaId: Long, corpo: ModificaRegolaIn): RispostaHttp =
        mutazione(
            "PATCH",
            "/api/regole/$regolaId",
            json.encodeToString(ModificaRegolaIn.serializer(), corpo),
        )

    suspend fun eliminaRegola(regolaId: Long, propostaId: Long? = null): RispostaHttp {
        val percorso = if (propostaId != null) {
            "/api/regole/$regolaId?proposta_id=$propostaId"
        } else {
            "/api/regole/$regolaId"
        }
        return mutazione("DELETE", percorso, null)
    }

    suspend fun inviaBonus(corpo: BonusIn): RispostaHttp =
        mutazione("POST", "/api/bonus", json.encodeToString(BonusIn.serializer(), corpo))

    suspend fun rispondiProposta(propostaId: Long, corpo: RispostaPropostaIn): RispostaHttp =
        mutazione(
            "POST",
            "/api/proposte/$propostaId/risposta",
            json.encodeToString(RispostaPropostaIn.serializer(), corpo),
        )

    suspend fun creaDichiarazione(corpo: DichiarazioneIn): RispostaHttp =
        mutazione(
            "POST",
            "/api/dichiarazioni",
            json.encodeToString(DichiarazioneIn.serializer(), corpo),
        )

    /**
     * Marca come letta una notifica del figlio (ciascun ruolo può marcare le
     * proprie — contratto-api.md). Best effort: senza, il server accumula le
     * non lette all'infinito e, oltre il tetto locale, il figlio si ri-avvisa.
     */
    suspend fun marcaNotificaLetta(id: Long): Boolean =
        mutazione("POST", "/api/notifiche/$id/letta", null).ok

    // --- Interni -------------------------------------------------------------

    private suspend fun inviaSemplice(percorso: String, corpo: String): Boolean {
        if (!configurazione.completa) return false
        return withContext(Dispatchers.IO) {
            try {
                val richiesta = richiesta(percorso)
                    .post(corpo.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                http.newCall(richiesta).execute().use { it.isSuccessful }
            } catch (e: IOException) {
                false
            } catch (e: IllegalArgumentException) {
                // URL malformato nelle impostazioni: non è un motivo per crashare il worker.
                false
            }
        }
    }

    /** Il corpo della risposta 2xx, null per qualunque fallimento (rete o HTTP non-2xx). */
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

    private suspend fun mutazione(metodo: String, percorso: String, corpo: String?): RispostaHttp {
        if (!configurazione.completa) return RispostaHttp(ok = false, codice = 0, corpo = null)
        return withContext(Dispatchers.IO) {
            try {
                val body: RequestBody? = corpo?.toRequestBody(JSON_MEDIA_TYPE)
                    ?: if (metodo == "DELETE") null else CORPO_VUOTO
                val richiesta = richiesta(percorso).method(metodo, body).build()
                http.newCall(richiesta).execute().use { risposta ->
                    RispostaHttp(
                        ok = risposta.isSuccessful,
                        codice = risposta.code,
                        corpo = risposta.body?.string(),
                    )
                }
            } catch (e: IOException) {
                RispostaHttp(ok = false, codice = 0, corpo = null)
            } catch (e: IllegalArgumentException) {
                RispostaHttp(ok = false, codice = 0, corpo = null)
            }
        }
    }

    private fun richiesta(percorso: String): Request.Builder = Request.Builder()
        .url(configurazione.serverUrl + percorso)
        .header("Authorization", "Bearer ${configurazione.token}")

    private fun <T> decodifica(serializer: KSerializer<T>, corpo: String): T? = try {
        json.decodeFromString(serializer, corpo)
    } catch (e: SerializationException) {
        null // risposta inattesa: si tratta come un fallimento di rete
    } catch (e: IllegalArgumentException) {
        null
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val CORPO_VUOTO = ByteArray(0).toRequestBody(null)
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        // Un solo client OkHttp per processo: riusa pool di connessioni e thread.
        private val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

        /**
         * Normalizza e valida l'indirizzo del server prima del salvataggio:
         * aggiunge https:// se manca lo schema e valida con okhttp3.HttpUrl.
         * Restituisce null se l'indirizzo va rifiutato — un URL sbagliato
         * accettato in silenzio è un'app che non consegna mai niente senza
         * che nessuno se ne accorga.
         */
        fun normalizzaUrlServer(grezzo: String): String? {
            val ripulito = grezzo.trim()
            if (ripulito.isEmpty()) return null
            val conSchema = if ("://" in ripulito) ripulito else "https://$ripulito"
            // toString() di HttpUrl aggiunge la "/" del percorso radice:
            // via, perché i percorsi ("/api/...") si concatenano dopo.
            return conSchema.toHttpUrlOrNull()?.toString()?.trimEnd('/')
        }
    }
}
