package eu.stgm.pactum.genitore.fotografo

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Il server di Pactum finto del fotografo: risponde alle stesse rotte del server
 * vero (docs/contratto-api.md) con i DATI FINTI di [Scenario]. L'app non sa di
 * parlare con un finto: le schermate si disegnano con i loro ViewModel veri.
 *
 * Lo scenario si può cambiare a metà (es. la rete che cade dopo la prima lettura).
 */
class ServerFinto {

    /** Una risposta: un corpo JSON, un codice d'errore, o la rete che cade. */
    sealed interface Risposta {
        data class Corpo(val json: String, val codice: Int = 200) : Risposta
        data class Byte(val byte: ByteArray, val tipo: String = "image/jpeg") : Risposta
        data class Errore(val codice: Int, val json: String = "{\"detail\": \"Not Found\"}") : Risposta
        data object SenzaRete : Risposta
    }

    @Volatile
    var scenario: Scenario = Scenario()

    /** Le richieste ricevute (metodo + percorso), per capire cosa ha chiesto l'app. */
    val richieste = CopyOnWriteArrayList<String>()

    private val server = MockWebServer()

    val indirizzo: String get() = server.url("/").toString().trimEnd('/')

    fun avvia() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val percorso = request.path ?: "/"
                richieste += "${request.method} $percorso"
                return when (val r = scenario.rispondi(request.method ?: "GET", percorso)) {
                    is Risposta.Corpo -> MockResponse()
                        .setResponseCode(r.codice)
                        .setHeader("Content-Type", "application/json")
                        .setHeader("Date", DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now()))
                        .setBody(r.json)
                    is Risposta.Byte -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", r.tipo)
                        .setBody(Buffer().write(r.byte))
                    is Risposta.Errore -> MockResponse()
                        .setResponseCode(r.codice)
                        .setHeader("Content-Type", "application/json")
                        .setBody(r.json)
                    Risposta.SenzaRete -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
                }
            }
        }
        server.start()
    }

    fun ferma() {
        try {
            server.shutdown()
        } catch (_: Exception) {
        }
    }

    companion object {
        /** Lo stesso JSON del server: i null restano fuori, i default si scrivono. */
        val json = Json {
            encodeDefaults = true
            explicitNulls = false
        }

        fun <T> corpo(serializer: KSerializer<T>, dato: T, codice: Int = 200): Risposta =
            Risposta.Corpo(json.encodeToString(serializer, dato), codice)
    }
}

/**
 * Che cosa risponde il server finto, rotta per rotta. Ogni campo è una funzione
 * del percorso completo (con la query), così si risponde per figlio.
 */
data class Scenario(
    val famiglia: () -> ServerFinto.Risposta = { ServerFinto.Risposta.SenzaRete },
    val genitori: () -> ServerFinto.Risposta = { ServerFinto.Risposta.SenzaRete },
    val finestra: (figlioId: Long?) -> ServerFinto.Risposta = { ServerFinto.Risposta.SenzaRete },
    val notifiche: () -> ServerFinto.Risposta = { ServerFinto.Risposta.SenzaRete },
    val proposte: (figlioId: Long?) -> ServerFinto.Risposta = { ServerFinto.Risposta.SenzaRete },
    val dichiarazioni: (figlioId: Long?) -> ServerFinto.Risposta = { ServerFinto.Risposta.SenzaRete },
    val faccende: (figlioId: Long?) -> ServerFinto.Risposta = { ServerFinto.Risposta.SenzaRete },
    val foto: (faccendaId: Long) -> ServerFinto.Risposta = { ServerFinto.Risposta.SenzaRete },
    /** (0.17, v3.9) GET /api/faccende?…&cerca=…: il figlio e il testo cercato. */
    val cerca: (figlioId: Long?, testo: String) -> ServerFinto.Risposta = { _, _ -> ServerFinto.Risposta.SenzaRete },
    val sessioni: (figlioId: Long?) -> ServerFinto.Risposta = { ServerFinto.Risposta.SenzaRete },
    /** (0.18, v4.0) GET /api/studio, /api/studio/versioni e /api/studio/svolte (con `prima_di`). */
    val studio: (figlioId: Long?) -> ServerFinto.Risposta = { ServerFinto.Risposta.Errore(404) },
    val studioVersioni: (figlioId: Long?) -> ServerFinto.Risposta = { ServerFinto.Risposta.Errore(404) },
    val studioSvolte: (figlioId: Long?, primaDi: Long?) -> ServerFinto.Risposta = { _, _ -> ServerFinto.Risposta.Errore(404) },
    val versione: () -> ServerFinto.Risposta = { ServerFinto.Risposta.SenzaRete },
    /** Le scritture (POST/PATCH/DELETE): metodo, percorso → risposta. */
    val scritture: (metodo: String, percorso: String) -> ServerFinto.Risposta = { _, _ ->
        ServerFinto.Risposta.Corpo("{}")
    },
) {
    fun rispondi(metodo: String, percorsoCompleto: String): ServerFinto.Risposta {
        val percorso = percorsoCompleto.substringBefore('?')
        val figlio = Regex("figlio_id=(\\d+)").find(percorsoCompleto)?.groupValues?.get(1)?.toLongOrNull()
        if (metodo != "GET") return scritture(metodo, percorso)
        return when {
            percorso == "/api/famiglia" -> famiglia()
            percorso == "/api/genitori" -> genitori()
            percorso == "/api/finestra" -> finestra(figlio)
            percorso == "/api/notifiche" -> notifiche()
            percorso == "/api/proposte" -> proposte(figlio)
            percorso == "/api/dichiarazioni" -> dichiarazioni(figlio)
            percorso == "/api/faccende" && Regex("[?&]cerca=").containsMatchIn(percorsoCompleto) -> cerca(
                figlio,
                java.net.URLDecoder.decode(Regex("[?&]cerca=([^&]*)").find(percorsoCompleto)!!.groupValues[1], "UTF-8"),
            )
            percorso == "/api/faccende" -> faccende(figlio)
            percorso.startsWith("/api/faccende/") && percorso.endsWith("/foto") ->
                foto(percorso.removePrefix("/api/faccende/").removeSuffix("/foto").toLong())
            percorso == "/api/sessioni" -> sessioni(figlio)
            percorso == "/api/studio" -> studio(figlio)
            percorso == "/api/studio/versioni" -> studioVersioni(figlio)
            percorso == "/api/studio/svolte" ->
                studioSvolte(figlio, Regex("prima_di=(\\d+)").find(percorsoCompleto)?.groupValues?.get(1)?.toLongOrNull())
            percorso == "/api/versione" -> versione()
            else -> ServerFinto.Risposta.Errore(404)
        }
    }
}
