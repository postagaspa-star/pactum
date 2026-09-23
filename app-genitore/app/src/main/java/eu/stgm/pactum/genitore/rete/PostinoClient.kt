package eu.stgm.pactum.genitore.rete

import eu.stgm.pactum.genitore.dati.CodiceAbbinamento
import eu.stgm.pactum.genitore.dati.CodiciErrore
import eu.stgm.pactum.genitore.dati.ConfigurazionePostino
import eu.stgm.pactum.genitore.dati.CorpoNomeFiglio
import eu.stgm.pactum.genitore.dati.CorpoNuovoDispositivo
import eu.stgm.pactum.genitore.dati.CorpoSegno
import eu.stgm.pactum.genitore.dati.CorpoVerdetto
import eu.stgm.pactum.genitore.dati.Dichiarazione
import eu.stgm.pactum.genitore.dati.Famiglia
import eu.stgm.pactum.genitore.dati.FiglioRisposta
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.InfoVersioni
import eu.stgm.pactum.genitore.dati.Notifica
import eu.stgm.pactum.genitore.dati.NuovaProposta
import eu.stgm.pactum.genitore.dati.PaccoDichiarazioni
import eu.stgm.pactum.genitore.dati.PaccoNotifiche
import eu.stgm.pactum.genitore.dati.PaccoProposte
import eu.stgm.pactum.genitore.dati.Proposta
import eu.stgm.pactum.genitore.dati.SegnoMandato
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
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
 * L'esito di una scrittura verso il postino. `Riuscito` porta il dato creato
 * dal server (la proposta col confronto, la dichiarazione col verdetto);
 * `Rifiutato` è un 409 col codice `errore` del contratto (es. proposta già
 * pendente; null se il corpo non ne porta uno) o un 422 (PARAMETRI_NON_VALIDI);
 * `Fallito` è tutto il resto (offline, altro HTTP, JSON inatteso).
 */
sealed interface EsitoScrittura<out T> {
    data class Riuscito<T>(val dato: T) : EsitoScrittura<T>

    /**
     * [riprovaTraSecondi]: solo sui 429 (`troppi_tentativi`), quanto aspettare
     * prima di riprovare, se il server lo dice.
     */
    data class Rifiutato(
        val errore: String?,
        val riprovaTraSecondi: Long? = null,
    ) : EsitoScrittura<Nothing>

    data object Fallito : EsitoScrittura<Nothing>
}

/**
 * L'esito di GET /api/famiglia (v3). Un server 0.7 non la conosce e risponde
 * 404: non è un errore, è la versione del server — l'app lavora come la 0.7,
 * con un figlio e un dispositivo.
 */
sealed interface EsitoFamiglia {
    data class Letta(val famiglia: Famiglia) : EsitoFamiglia
    data object ServerVecchio : EsitoFamiglia
    data object Fallita : EsitoFamiglia
}

/**
 * Client verso il server, lato genitore. Protocollo: docs/contratto-api.md
 * (fonte di verità — ogni modifica passa prima da lì).
 *
 *   GET  {base}/api/famiglia                 → Famiglia (v3; 404 = server 0.7)
 *   GET  {base}/api/finestra?figlio_id=n     → Finestra
 *   GET  {base}/api/notifiche                → {"notifiche": [...]} (non lette, tutti i figli)
 *   POST {base}/api/notifiche/{id}/letta     → 2xx = segnata
 *   POST {base}/api/segno {figlio_id}        → il riconoscimento al figlio (v2.4)
 *   POST/PATCH figli, dispositivi, codici    → la famiglia (v3)
 *   header: Authorization: Bearer <token del genitore>
 *
 * `figlioId` null = server 0.7 (o figlio non ancora noto): la richiesta parte
 * senza `figlio_id`, identica a quella della 0.7, e il server risponde per il
 * primo figlio.
 *
 * Tollerante all'offline: qualunque fallimento (rete, HTTP non-2xx, JSON
 * inatteso) restituisce null/false — chi chiama decide se riprovare.
 */
class PostinoClient(private val configurazione: ConfigurazionePostino) {

    /** GET /api/famiglia (v3): i figli coi loro dispositivi, o "server 0.7", o fallita. */
    suspend fun leggiFamiglia(): EsitoFamiglia {
        val risposta = richiedi("GET", "/api/famiglia", null) ?: return EsitoFamiglia.Fallita
        return interpretaFamiglia(risposta.codice, risposta.corpo)
    }

    suspend fun leggiFinestra(figlioId: Long? = null): Finestra? =
        leggi(conFiglio("/api/finestra", figlioId))?.let { decodifica(Finestra.serializer(), it) }

    suspend fun leggiNotifiche(): List<Notifica>? =
        leggi("/api/notifiche")
            ?.let { decodifica(PaccoNotifiche.serializer(), it) }
            ?.notifiche

    suspend fun leggiProposte(figlioId: Long? = null): List<Proposta>? =
        leggi(conFiglio("/api/proposte", figlioId))
            ?.let { decodifica(PaccoProposte.serializer(), it) }
            ?.proposte

    suspend fun leggiDichiarazioni(figlioId: Long? = null): List<Dichiarazione>? =
        leggi(conFiglio("/api/dichiarazioni", figlioId))
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
     * tenere ~10 MB in memoria). [url] è quello di GET /api/versione e deve
     * essere un percorso RELATIVO al server del patto (es.
     * `/scarica/pactum-genitore.apk`): un URL assoluto arrivato nel metadata
     * viene rifiutato — l'update non segue mai un host del payload (come
     * nell'app del figlio). Nessun auth: è il download di un file pubblico; la
     * firma dell'APK (stessa chiave) è la vera garanzia d'integrità
     * (contratto-api.md). false = fallito.
     */
    suspend fun scaricaApk(url: String, destinazione: File): Boolean {
        if (!configurazione.completa) return false
        if ("://" in url || !url.startsWith("/")) return false
        val assoluto = configurazione.serverUrl + url
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
        val risposta = scrivi("POST", "/api/proposte", corpo) ?: return EsitoScrittura.Fallito
        return interpreta(risposta, Proposta.serializer())
    }

    /** POST /api/dichiarazioni/{id}/verdetto: la dichiarazione aggiornata o l'errore. */
    suspend fun emettiVerdetto(
        dichiarazioneId: Long,
        corpoVerdetto: CorpoVerdetto,
    ): EsitoScrittura<Dichiarazione> {
        val corpo = json.encodeToString(CorpoVerdetto.serializer(), corpoVerdetto)
        val risposta = scrivi("POST", "/api/dichiarazioni/$dichiarazioneId/verdetto", corpo)
            ?: return EsitoScrittura.Fallito
        return interpreta(risposta, Dichiarazione.serializer())
    }

    /**
     * POST /api/segno (v2.4): il riconoscimento al figlio, a testo fisso, max uno
     * al giorno. Il testo lo decide il server, non il genitore. (v3) Nel corpo
     * c'è solo `figlio_id`: un segno al giorno PER FIGLIO. Senza figlio (server
     * 0.7) il corpo resta vuoto, come prima.
     * `Rifiutato` = 409 `segno_gia_mandato` (oggi è già partito).
     */
    suspend fun mandaSegno(figlioId: Long? = null): EsitoScrittura<SegnoMandato> {
        val corpo = if (figlioId == null) {
            CORPO_VUOTO
        } else {
            json.encodeToString(CorpoSegno.serializer(), CorpoSegno(figlioId))
                .toRequestBody(JSON_MEDIA_TYPE)
        }
        val risposta = richiedi("POST", "/api/segno", corpo) ?: return EsitoScrittura.Fallito
        return interpreta(risposta, SegnoMandato.serializer())
    }

    // --- La famiglia (v3) ---------------------------------------------------------

    /** POST /api/figli: un figlio nuovo, col nome (1-40 caratteri). */
    suspend fun creaFiglio(nome: String): EsitoScrittura<FiglioRisposta> {
        val corpo = json.encodeToString(CorpoNomeFiglio.serializer(), CorpoNomeFiglio(nome))
        val risposta = scrivi("POST", "/api/figli", corpo) ?: return EsitoScrittura.Fallito
        return interpreta(risposta, FiglioRisposta.serializer())
    }

    /** PATCH /api/figli/{id}: il nome nuovo. */
    suspend fun rinominaFiglio(figlioId: Long, nome: String): EsitoScrittura<Unit> {
        val corpo = json.encodeToString(CorpoNomeFiglio.serializer(), CorpoNomeFiglio(nome))
        val risposta = scrivi("PATCH", "/api/figli/$figlioId", corpo)
            ?: return EsitoScrittura.Fallito
        return interpretaSenzaDato(risposta.codice, risposta.corpo)
    }

    /**
     * POST /api/figli/{id}/dispositivi: il dispositivo nasce NON abbinato, e la
     * risposta porta il codice di 6 cifre per collegarlo.
     */
    suspend fun creaDispositivo(
        figlioId: Long,
        nome: String,
        tipo: String,
    ): EsitoScrittura<CodiceAbbinamento> {
        val corpo = json.encodeToString(
            CorpoNuovoDispositivo.serializer(),
            CorpoNuovoDispositivo(nome, tipo),
        )
        val risposta = scrivi("POST", "/api/figli/$figlioId/dispositivi", corpo)
            ?: return EsitoScrittura.Fallito
        return interpreta(risposta, CodiceAbbinamento.serializer())
    }

    /**
     * POST /api/dispositivi/{id}/codice: un codice nuovo per un dispositivo non
     * ancora collegato o da ricollegare. Annulla il codice precedente.
     */
    suspend fun nuovoCodice(dispositivoId: Long): EsitoScrittura<CodiceAbbinamento> {
        val risposta = richiedi("POST", "/api/dispositivi/$dispositivoId/codice", CORPO_VUOTO)
            ?: return EsitoScrittura.Fallito
        return interpreta(risposta, CodiceAbbinamento.serializer())
    }

    /**
     * DELETE /api/dispositivi/{id}: la REVOCA. Il dispositivo smette di mandare
     * dati; niente di quello che ha registrato si cancella.
     */
    suspend fun scollegaDispositivo(dispositivoId: Long): EsitoScrittura<Unit> {
        val risposta = richiedi("DELETE", "/api/dispositivi/$dispositivoId", null)
            ?: return EsitoScrittura.Fallito
        return interpretaSenzaDato(risposta.codice, risposta.corpo)
    }

    suspend fun segnaLetta(notificaId: Long): Boolean {
        val risposta = richiedi("POST", "/api/notifiche/$notificaId/letta", CORPO_VUOTO)
        return risposta != null && risposta.codice in 200..299
    }

    /** Una richiesta con corpo JSON: codice HTTP + corpo (letto sempre), null se la rete cade. */
    private suspend fun scrivi(metodo: String, percorso: String, corpo: String): RispostaHttp? =
        richiedi(metodo, percorso, corpo.toRequestBody(JSON_MEDIA_TYPE))

    /**
     * Una richiesta qualunque: codice HTTP + corpo (letto sempre, anche sui
     * rifiuti: il codice `errore` sta lì), null se la rete cade o l'indirizzo
     * salvato non è un URL.
     */
    private suspend fun richiedi(metodo: String, percorso: String, corpo: RequestBody?): RispostaHttp? {
        if (!configurazione.completa) return null
        return withContext(Dispatchers.IO) {
            try {
                val richiesta = richiesta(percorso)
                    .method(metodo, corpo)
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
    ): EsitoScrittura<T> = interpretaRisposta(risposta.codice, risposta.corpo, serializer)

    /** Il corpo della risposta 2xx, null per qualunque fallimento. */
    private suspend fun leggi(percorso: String): String? {
        val risposta = richiedi("GET", percorso, null) ?: return null
        return if (risposta.codice in 200..299) risposta.corpo else null
    }

    private fun richiesta(percorso: String): Request.Builder = Request.Builder()
        .url(configurazione.serverUrl + percorso)
        .header("Authorization", "Bearer ${configurazione.token}")

    /** Codice HTTP + corpo grezzo di una risposta. */
    private data class RispostaHttp(val codice: Int, val corpo: String?)

    companion object {
        // Codice d'errore sintetico per il 422 di validazione del server: non è
        // un codice del contratto (il 422 non ne porta uno), lo coniamo qui per
        // dare alla UI un messaggio specifico invece del generico "riprova".
        const val PARAMETRI_NON_VALIDI = "parametri_non_validi"

        /** Il percorso con `?figlio_id=n`; senza figlio (server 0.7) com'era. */
        internal fun conFiglio(percorso: String, figlioId: Long?): String =
            if (figlioId == null) percorso else "$percorso?figlio_id=$figlioId"

        /**
         * GET /api/famiglia dal codice HTTP. Il 404 è il server 0.7 (la rotta non
         * esiste): da lì in poi l'app lavora come la 0.7. Tutto il resto che non
         * è una famiglia leggibile è un fallimento, da ritentare.
         */
        internal fun interpretaFamiglia(codice: Int, corpo: String?): EsitoFamiglia = when {
            codice in 200..299 ->
                corpo?.let { decodifica(Famiglia.serializer(), it) }
                    ?.let { EsitoFamiglia.Letta(it) }
                    ?: EsitoFamiglia.Fallita
            codice == 404 -> EsitoFamiglia.ServerVecchio
            else -> EsitoFamiglia.Fallita
        }

        /**
         * L'esito di una scrittura dal codice HTTP e dal corpo. Logica pura,
         * provata in PostinoClientTest.
         */
        internal fun <T> interpretaRisposta(
            codice: Int,
            corpo: String?,
            serializer: kotlinx.serialization.KSerializer<T>,
        ): EsitoScrittura<T> {
            if (codice !in 200..299) return rifiuto(codice, corpo)
            val dato = corpo?.let { decodifica(serializer, it) }
            return if (dato != null) EsitoScrittura.Riuscito(dato) else EsitoScrittura.Fallito
        }

        /**
         * Come [interpretaRisposta], per le scritture di cui basta sapere che sono
         * andate (una revoca, un nome cambiato): qualunque 2xx, anche senza corpo.
         */
        internal fun interpretaSenzaDato(codice: Int, corpo: String?): EsitoScrittura<Unit> =
            if (codice in 200..299) EsitoScrittura.Riuscito(Unit) else rifiuto(codice, corpo)

        private fun rifiuto(codice: Int, corpo: String?): EsitoScrittura<Nothing> = when (codice) {
            // 409 = rifiuto del contratto (proposta già pendente, dichiarazione non
            // più in attesa, regola non valida, segno già mandato): si porta su il
            // codice `errore`.
            409 -> EsitoScrittura.Rifiutato(codiceErrore(corpo))
            // 422 = validazione del server fallita (un campo libero non valido, es.
            // un orario o un nome troppo lungo). Rifiuto distinto: il corpo FastAPI
            // porta una lista in `detail`, non un `errore`, e un generico "riprova"
            // sarebbe fuorviante su un dato che va corretto.
            422 -> EsitoScrittura.Rifiutato(PARAMETRI_NON_VALIDI)
            // (v3) 404 = il figlio o il dispositivo non esiste (più): riprovare non
            // serve, serve rileggere la famiglia.
            404 -> EsitoScrittura.Rifiutato(CodiciErrore.NON_TROVATO)
            // (v3) 429 = troppi tentativi: si dice quanto aspettare, se il server lo sa.
            429 -> EsitoScrittura.Rifiutato(
                codiceErrore(corpo) ?: CodiciErrore.TROPPI_TENTATIVI,
                riprovaTraSecondi(corpo),
            )
            else -> EsitoScrittura.Fallito
        }

        /**
         * Il codice `errore` di un 409. Il server è FastAPI: `HTTPException(409,
         * detail={"errore": …})` arriva come `{"detail": {"errore": "…"}}`, quindi
         * il codice sta DENTRO `detail` quando è un oggetto. Si accetta anche
         * `errore` in cima, come lo scrive il contratto. null se il corpo non ne
         * porta uno (es. `detail` testuale): chi chiama dice un messaggio generico.
         */
        internal fun codiceErrore(corpo: String?): String? {
            val (dettaglio, oggetto) = corpoDelRifiuto(corpo) ?: return null
            return testo(dettaglio?.get("errore")) ?: testo(oggetto["errore"])
        }

        /**
         * `riprova_tra_secondi` di un 429 (v3, abbinamento), dentro `detail` o in
         * cima; null se manca o non è un numero.
         */
        internal fun riprovaTraSecondi(corpo: String?): Long? {
            val (dettaglio, oggetto) = corpoDelRifiuto(corpo) ?: return null
            return numero(dettaglio?.get("riprova_tra_secondi"))
                ?: numero(oggetto["riprova_tra_secondi"])
        }

        /** Il corpo di un rifiuto come (`detail` se è un oggetto, corpo intero); null se non è JSON. */
        private fun corpoDelRifiuto(corpo: String?): Pair<JsonObject?, JsonObject>? {
            if (corpo.isNullOrBlank()) return null
            return try {
                val oggetto = json.parseToJsonElement(corpo) as? JsonObject ?: return null
                (oggetto["detail"] as? JsonObject) to oggetto
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        // Solo una stringa JSON vale come codice: un `null` o un numero no.
        private fun testo(elemento: JsonElement?): String? =
            (elemento as? JsonPrimitive)?.takeIf { it.isString }?.content

        // Solo un numero JSON vale come attesa: una stringa o un `null` no.
        private fun numero(elemento: JsonElement?): Long? =
            (elemento as? JsonPrimitive)
                ?.takeIf { !it.isString && it !is JsonNull }
                ?.content
                ?.toDoubleOrNull()
                ?.toLong()
                ?.takeIf { it >= 0 }

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

        private val CORPO_VUOTO = ByteArray(0).toRequestBody(null)
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * `coerceInputValues`: un `null` dove il modello ha un default (es. una
         * lista) vale il default, invece di far saltare TUTTA la risposta — una
         * finestra buona con un campo nuovo a null non deve sembrare "server
         * irraggiungibile".
         */
        internal val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

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
