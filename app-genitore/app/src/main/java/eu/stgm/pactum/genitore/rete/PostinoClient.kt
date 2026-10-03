package eu.stgm.pactum.genitore.rete

import eu.stgm.pactum.genitore.dati.AbbinamentoGenitore
import eu.stgm.pactum.genitore.dati.CodiceAbbinamento
import eu.stgm.pactum.genitore.dati.CodiceGenitore
import eu.stgm.pactum.genitore.dati.CodiciErrore
import eu.stgm.pactum.genitore.dati.ConfigurazionePostino
import eu.stgm.pactum.genitore.dati.CorpoAbbinaGenitore
import eu.stgm.pactum.genitore.dati.CorpoBoccia
import eu.stgm.pactum.genitore.dati.CorpoNomeFiglio
import eu.stgm.pactum.genitore.dati.CorpoNomeGenitore
import eu.stgm.pactum.genitore.dati.CorpoNuoveFaccende
import eu.stgm.pactum.genitore.dati.CorpoNuovoDispositivo
import eu.stgm.pactum.genitore.dati.Faccenda
import eu.stgm.pactum.genitore.dati.PaccoFaccende
import eu.stgm.pactum.genitore.dati.PaccoGenitori
import eu.stgm.pactum.genitore.dati.RiferimentoGenitore
import eu.stgm.pactum.genitore.dati.TIPO_ABBINAMENTO_GENITORE
import eu.stgm.pactum.genitore.dati.CorpoRispostaProposta
import eu.stgm.pactum.genitore.dati.CorpoRispostaSessione
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
import eu.stgm.pactum.genitore.dati.PaccoSessioni
import eu.stgm.pactum.genitore.dati.Proposta
import eu.stgm.pactum.genitore.dati.PropostaDecisa
import eu.stgm.pactum.genitore.dati.SegnoMandato
import eu.stgm.pactum.genitore.dati.Sessione
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

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

/** (0.13) Lo stesso esito col dato trasformato: rifiuti e fallimenti restano com'erano. */
fun <T, R> EsitoScrittura<T>.mappa(trasforma: (T) -> R): EsitoScrittura<R> = when (this) {
    is EsitoScrittura.Riuscito -> EsitoScrittura.Riuscito(trasforma(dato))
    is EsitoScrittura.Rifiutato -> this
    EsitoScrittura.Fallito -> EsitoScrittura.Fallito
}

/**
 * Un codice di abbinamento appena arrivato, con l'ora del server scritta nella
 * stessa risposta (header `Date`): la scadenza si conta da lì e dall'orologio
 * monotono del telefono, mai dall'ora del telefono (v. validitaCodice).
 * [oraServer] null = header assente o illeggibile.
 */
data class CodiceRicevuto(val codice: CodiceAbbinamento, val oraServer: Instant?)

/**
 * L'esito di GET /api/famiglia (v3). Un server 0.7 non la conosce e risponde
 * 404: non è un errore, è la versione del server — l'app lavora come la 0.7,
 * con un figlio e un dispositivo.
 */
sealed interface EsitoFamiglia {
    data class Letta(val famiglia: Famiglia) : EsitoFamiglia
    data object ServerVecchio : EsitoFamiglia

    /**
     * (0.13) 401: il server non riconosce più il codice di questo telefono (un
     * altro genitore l'ha tolto, o il codice è sbagliato). Non è la rete.
     */
    data object NonAutorizzato : EsitoFamiglia
    data object Fallita : EsitoFamiglia
}

/**
 * (0.11) L'esito di GET /api/sessioni?figlio_id=n (contratto v3.5). Un server più
 * vecchio della v3.5 non conosce la rotta (404 "Not Found", o 405): per le
 * sessioni serve aggiornare il server di Pactum.
 */
sealed interface EsitoSessioni {
    data class Lette(val sessioni: List<Sessione>) : EsitoSessioni
    data object ServerVecchio : EsitoSessioni
    data object Fallita : EsitoSessioni
}

/**
 * (0.11) L'esito di POST /api/sessioni/{id}/risposta (contratto v3.5).
 * - [Decisa]: il server l'ha presa; la sessione aggiornata, se il corpo si legge.
 * - [Cambiata]: 409 `richiesta_cambiata`, la `versione` mandata non è più quella:
 *   niente è stato deciso; la sessione com'è adesso, se il server l'ha mandata.
 * - [Rifiutata]: un altro rifiuto col suo codice (`niente_da_decidere`,
 *   `dispositivo_revocato`, `non_trovato`, server da aggiornare, 422…).
 * - [Fallita]: rete caduta o risposta che non si capisce.
 */
sealed interface EsitoRispostaSessione {
    data class Decisa(val sessione: Sessione?) : EsitoRispostaSessione
    data class Cambiata(val sessione: Sessione?) : EsitoRispostaSessione
    data class Rifiutata(val codice: String?) : EsitoRispostaSessione
    data object Fallita : EsitoRispostaSessione
}

/**
 * (0.13) Un codice di 6 cifre per il telefono di un genitore, con l'ora del server
 * della stessa risposta (come [CodiceRicevuto]).
 */
data class CodiceGenitoreRicevuto(val codice: CodiceGenitore, val oraServer: Instant?)

/**
 * (0.13) Com'è andato POST /api/abbina col tipo "genitore" (contratto v3.6), nelle
 * categorie che il genitore deve distinguere.
 */
sealed interface EsitoAbbinamento {
    /** Collegato: il token (il server lo dà una volta sola) e chi sei. */
    data class Collegato(val token: String, val genitore: RiferimentoGenitore?) : EsitoAbbinamento

    /** Sbagliato, scaduto o già usato: il server dà la stessa risposta per i tre casi. */
    data object CodiceNonValido : EsitoAbbinamento

    /** Troppi codici sbagliati sul server: si aspetta, anche col codice giusto. */
    data class TroppiTentativi(val riprovaTraSecondi: Long?) : EsitoAbbinamento

    /**
     * Il codice è di un telefono o di un computer del figlio ([tipoAtteso]): il
     * server non l'ha consumato. null = il server non ha detto di che tipo.
     */
    data class TipoNonCorrispondente(val tipoAtteso: String?) : EsitoAbbinamento

    /**
     * Il server conosce i codici ma non quelli dei genitori (più vecchio della
     * v3.6: il tipo "genitore" non lo accetta, 422). Serve aggiornarlo; intanto
     * resta il codice d'accesso lungo.
     */
    data object ServerDaAggiornare : EsitoAbbinamento

    /** A quell'indirizzo non c'è un server che conosca i codici (indirizzo sbagliato o server 0.7). */
    data object ServerSenzaCodici : EsitoAbbinamento

    /** Il server non si raggiunge. */
    data object SenzaRete : EsitoAbbinamento

    /** Qualsiasi altra cosa: si riprova. */
    data object Errore : EsitoAbbinamento
}

/**
 * (0.13) L'esito di GET /api/genitori: i genitori, o "server più vecchio della
 * v3.6", o il collegamento di questo telefono che non vale più (401), o fallita.
 */
sealed interface EsitoGenitori {
    data class Letti(val pacco: PaccoGenitori) : EsitoGenitori
    data object ServerVecchio : EsitoGenitori
    data object NonAutorizzato : EsitoGenitori
    data object Fallita : EsitoGenitori
}

/** (0.13) L'esito di GET /api/faccende?figlio_id=n: come [EsitoGenitori]. */
sealed interface EsitoFaccende {
    data class Lette(val faccende: List<Faccenda>) : EsitoFaccende
    data object ServerVecchio : EsitoFaccende
    data object NonAutorizzato : EsitoFaccende
    data object Fallita : EsitoFaccende
}

/**
 * (0.13) L'esito di GET /api/faccende/{id}/foto. La foto arriva come byte e resta
 * in memoria: non si scrive mai su disco, né in galleria.
 */
sealed interface EsitoFoto {
    class Arrivata(val byte: ByteArray) : EsitoFoto

    /** La foto non c'è: mai arrivata, bocciata o già cancellata (dopo 30 giorni). */
    data object NonTrovata : EsitoFoto
    data object ServerVecchio : EsitoFoto
    data object NonAutorizzato : EsitoFoto
    data object Fallita : EsitoFoto
}

/**
 * (0.13) Quello che le faccende chiedono al server: [PostinoClient] nell'app, un
 * finto nei test (così la logica delle faccende si prova senza Android).
 */
interface FonteFaccende {
    suspend fun leggiFaccende(figlioId: Long?): EsitoFaccende
    suspend fun daiFaccende(corpo: CorpoNuoveFaccende): EsitoScrittura<List<Faccenda>?>
    suspend fun bocciaFaccenda(faccendaId: Long, nota: String?): EsitoScrittura<Faccenda?>
    suspend fun annullaFaccenda(faccendaId: Long): EsitoScrittura<Faccenda?>
    suspend fun scaricaFoto(faccendaId: Long): EsitoFoto
}

/**
 * Client verso il server, lato genitore. Protocollo: docs/contratto-api.md
 * (fonte di verità — ogni modifica passa prima da lì).
 *
 *   GET  {base}/api/famiglia                 → Famiglia (v3; 404 = server 0.7)
 *   GET  {base}/api/finestra?figlio_id=n     → Finestra
 *   GET  {base}/api/notifiche[?dopo_id=n]    → {"notifiche": [...]} (non lette, tutti i figli; v3.3: solo id > n)
 *   POST {base}/api/notifiche/{id}/letta     → 2xx = segnata
 *   POST {base}/api/segno {figlio_id}        → il riconoscimento al figlio (v2.4)
 *   POST/PATCH figli, dispositivi, codici    → la famiglia (v3)
 *   POST {base}/api/proposte/{id}/risposta   → la decisione su una proposta del figlio (v3.4)
 *   POST {base}/api/proposte/{id}/ritira     → il ritiro di una proposta del genitore (v3.4)
 *   GET  {base}/api/sessioni?figlio_id=n     → le sessioni del figlio (v3.5)
 *   POST {base}/api/sessioni/{id}/risposta   → la decisione su una sessione (v3.5)
 *   POST {base}/api/abbina {tipo: genitore}  → il codice di 6 cifre diventa un token (v3.6, senza token)
 *   GET/POST/PATCH/DELETE genitori, codici   → i genitori (v3.6)
 *   GET/POST faccende, foto, boccia, annulla → le faccende (v3.6)
 *   header: Authorization: Bearer <token del genitore>
 *
 * `figlioId` null = server 0.7 (o figlio non ancora noto): la richiesta parte
 * senza `figlio_id`, identica a quella della 0.7, e il server risponde per il
 * primo figlio.
 *
 * Tollerante all'offline: qualunque fallimento (rete, HTTP non-2xx, JSON
 * inatteso) restituisce null/false — chi chiama decide se riprovare.
 *
 * (0.9) [perLaVedetta] = il client del giro della vedetta: ogni richiesta ha un
 * tempo massimo di 30 secondi in tutto (connessione compresa), così un giro non
 * resta appeso a un server che non risponde. E ogni richiesta si interrompe
 * davvero quando chi l'ha chiesta rinuncia (una coroutine annullata, un tempo
 * scaduto): non resta a consumare rete per conto suo.
 */
class PostinoClient(
    private val configurazione: ConfigurazionePostino,
    perLaVedetta: Boolean = false,
) : FonteFaccende {

    /** Il client delle richieste normali (letture e scritture): quello della vedetta ha il tempo massimo. */
    private val clientNormale: OkHttpClient = if (perLaVedetta) httpVedetta else http

    /** GET /api/famiglia (v3): i figli coi loro dispositivi, o "server 0.7", o fallita. */
    suspend fun leggiFamiglia(): EsitoFamiglia {
        val risposta = richiedi("GET", "/api/famiglia", null) ?: return EsitoFamiglia.Fallita
        return interpretaFamiglia(risposta.codice, risposta.corpo)
    }

    suspend fun leggiFinestra(figlioId: Long? = null): Finestra? =
        leggi(conFiglio("/api/finestra", figlioId))?.let { decodifica(Finestra.serializer(), it) }

    /**
     * GET /api/notifiche: le non lette. (v3.3) Con [dopoId] solo quelle con id
     * maggiore ("Solo le notifiche nuove"): il giro di ogni minuto scarica solo
     * il nuovo. Un server più vecchio ignora il parametro e manda tutto: chi
     * chiama non deve contare su una lista corta.
     */
    suspend fun leggiNotifiche(dopoId: Long? = null): List<Notifica>? =
        leggi(percorsoNotifiche(dopoId))
            ?.let { decodifica(PaccoNotifiche.serializer(), it) }
            ?.notifiche

    /**
     * GET /api/proposte. (0.10) Sempre con `autori=tutti` (contratto v3.4): senza,
     * il server manda solo le proposte del genitore, come alle app 0.8 e 0.9. Un
     * server più vecchio ignora il parametro (e lì le proposte sono tutte del genitore).
     */
    suspend fun leggiProposte(figlioId: Long? = null): List<Proposta>? =
        leggi(percorsoProposte(figlioId))
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

    /**
     * (0.10) POST /api/proposte/{id}/risposta col token del genitore (contratto
     * v3.4): la decisione su una proposta DEL FIGLIO, [esito] `accetta` o
     * `rifiuta`. `accetta` applica subito la modifica lato server, anche se
     * allenta. `Rifiutato` = 409 (`proposta_non_pendente`, `ultima_regola`,
     * `dispositivo_revocato`: in questi ultimi due la proposta resta in attesa) o
     * 404 (la proposta non c'è più). Il doppio invio non applica due volte: il
     * server ne fa passare uno solo, l'altro riceve `proposta_non_pendente`.
     * V. [interpretaDecisione]: conta il 2xx, non la forma del corpo.
     */
    suspend fun rispondiProposta(
        propostaId: Long,
        esito: String,
        motivazione: String?,
        figlioId: Long? = null,
    ): EsitoScrittura<PropostaDecisa?> {
        val corpo = json.encodeToString(
            CorpoRispostaProposta.serializer(),
            CorpoRispostaProposta(esito, motivazione, figlioId),
        )
        val risposta = scrivi("POST", "/api/proposte/$propostaId/risposta", corpo)
            ?: return EsitoScrittura.Fallito
        return interpretaDecisione(risposta.codice, risposta.corpo)
    }

    /**
     * (0.10) POST /api/proposte/{id}/ritira (contratto v3.4): il genitore ritira
     * una SUA proposta ancora in attesa. Nessun corpo; la regola non cambia. Un
     * server più vecchio non conosce la rotta: v. [interpretaRitiro].
     */
    suspend fun ritiraProposta(propostaId: Long): EsitoScrittura<Proposta?> {
        val risposta = richiedi("POST", "/api/proposte/$propostaId/ritira", CORPO_VUOTO)
            ?: return EsitoScrittura.Fallito
        return interpretaRitiro(risposta.codice, risposta.corpo)
    }

    /**
     * (0.11) GET /api/sessioni?figlio_id=n (contratto v3.5): le sessioni non
     * eliminate di tutti i telefoni del figlio (revocati compresi). Serve a
     * rileggere com'è una sessione quando il server dice che non c'era niente da
     * decidere. V. [interpretaSessioni].
     */
    suspend fun leggiSessioni(figlioId: Long? = null): EsitoSessioni {
        val risposta = richiedi("GET", conFiglio("/api/sessioni", figlioId), null) ?: return EsitoSessioni.Fallita
        return interpretaSessioni(risposta.codice, risposta.corpo)
    }

    /**
     * (0.11) POST /api/sessioni/{id}/risposta (contratto v3.5): il genitore approva
     * ([esito] `approva`) o non approva (`rifiuta`, col perché facoltativo) quello
     * che è in attesa: una sessione nuova o un cambio della lista. [versione] è
     * quella della sessione che ha sullo schermo: se nel frattempo è cambiata, il
     * server non decide niente e manda la sessione com'è adesso. V.
     * [interpretaRispostaSessione].
     */
    suspend fun rispondiSessione(
        sessioneId: Long,
        esito: String,
        versione: Int?,
        motivazione: String?,
        figlioId: Long? = null,
    ): EsitoRispostaSessione {
        val corpo = json.encodeToString(
            CorpoRispostaSessione.serializer(),
            CorpoRispostaSessione(esito, versione, motivazione, figlioId),
        )
        val risposta = scrivi("POST", "/api/sessioni/$sessioneId/risposta", corpo)
            ?: return EsitoRispostaSessione.Fallita
        return interpretaRispostaSessione(risposta.codice, risposta.corpo)
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
    // Le tre creazioni (figlio, dispositivo, codice) passano da [httpCreazioni]:
    // niente ritentativi automatici. Dopo un `Fallito` chi chiama rilegge la
    // famiglia prima di proporre di riprovare (FamigliaViewModel).

    /** POST /api/figli: un figlio nuovo, col nome (1-40 caratteri). */
    suspend fun creaFiglio(nome: String): EsitoScrittura<FiglioRisposta> {
        val corpo = json.encodeToString(CorpoNomeFiglio.serializer(), CorpoNomeFiglio(nome))
        val risposta = richiedi("POST", "/api/figli", corpo.toRequestBody(JSON_MEDIA_TYPE), httpCreazioni)
            ?: return EsitoScrittura.Fallito
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
    ): EsitoScrittura<CodiceRicevuto> {
        val corpo = json.encodeToString(
            CorpoNuovoDispositivo.serializer(),
            CorpoNuovoDispositivo(nome, tipo),
        )
        val risposta = richiedi(
            "POST",
            "/api/figli/$figlioId/dispositivi",
            corpo.toRequestBody(JSON_MEDIA_TYPE),
            httpCreazioni,
        ) ?: return EsitoScrittura.Fallito
        return codiceDa(risposta)
    }

    /**
     * POST /api/dispositivi/{id}/codice: un codice nuovo per un dispositivo non
     * ancora collegato o da ricollegare. Annulla il codice precedente.
     */
    suspend fun nuovoCodice(dispositivoId: Long): EsitoScrittura<CodiceRicevuto> {
        val risposta = richiedi("POST", "/api/dispositivi/$dispositivoId/codice", CORPO_VUOTO, httpCreazioni)
            ?: return EsitoScrittura.Fallito
        return codiceDa(risposta)
    }

    /** Il codice della risposta, con l'ora del server della stessa risposta. */
    private fun codiceDa(risposta: RispostaHttp): EsitoScrittura<CodiceRicevuto> =
        when (val esito = interpreta(risposta, CodiceAbbinamento.serializer())) {
            is EsitoScrittura.Riuscito -> EsitoScrittura.Riuscito(CodiceRicevuto(esito.dato, risposta.oraServer))
            is EsitoScrittura.Rifiutato -> esito
            EsitoScrittura.Fallito -> EsitoScrittura.Fallito
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

    // --- I genitori (0.13, contratto v3.6) -------------------------------------------
    // Come per i dispositivi: la creazione e i codici passano da [httpCreazioni],
    // senza ritentativi; dopo un `Fallito` chi chiama rilegge i genitori. Un server
    // più vecchio della v3.6 non conosce /api/genitori (404 "Not Found" o 405):
    // [CodiciErrore.SERVER_DA_AGGIORNARE].

    /** GET /api/genitori: chi sei tu e tutti i genitori (revocati compresi). */
    suspend fun leggiGenitori(): EsitoGenitori {
        val risposta = richiedi("GET", "/api/genitori", null) ?: return EsitoGenitori.Fallita
        return interpretaGenitori(risposta.codice, risposta.corpo)
    }

    /** POST /api/genitori: un genitore nuovo, non ancora collegato, e il codice per collegarlo. */
    suspend fun creaGenitore(nome: String): EsitoScrittura<CodiceGenitoreRicevuto> {
        val corpo = json.encodeToString(CorpoNomeGenitore.serializer(), CorpoNomeGenitore(nome))
        val risposta = richiedi("POST", "/api/genitori", corpo.toRequestBody(JSON_MEDIA_TYPE), httpCreazioni)
            ?: return EsitoScrittura.Fallito
        return codiceGenitoreDa(risposta)
    }

    /**
     * POST /api/genitori/{id}/codice: un codice nuovo per un genitore già creato
     * (telefono cambiato o reinstallato). Annulla il codice di prima; quando viene
     * usato, il collegamento di prima di quel genitore smette di funzionare.
     */
    suspend fun nuovoCodiceGenitore(genitoreId: Long): EsitoScrittura<CodiceGenitoreRicevuto> {
        val risposta = richiedi("POST", "/api/genitori/$genitoreId/codice", CORPO_VUOTO, httpCreazioni)
            ?: return EsitoScrittura.Fallito
        return codiceGenitoreDa(risposta)
    }

    /** PATCH /api/genitori/{id}: il nome nuovo (anche di un altro genitore: sono tutti uguali). */
    suspend fun rinominaGenitore(genitoreId: Long, nome: String): EsitoScrittura<Unit> {
        val corpo = json.encodeToString(CorpoNomeGenitore.serializer(), CorpoNomeGenitore(nome))
        val risposta = scrivi("PATCH", "/api/genitori/$genitoreId", corpo) ?: return EsitoScrittura.Fallito
        return interpretaNuovaRotta(risposta.codice, risposta.corpo)
    }

    /**
     * DELETE /api/genitori/{id}: la REVOCA (il suo telefono smette di vedere il
     * patto). Niente si cancella. `non_te_stesso`, `ultimo_genitore` sono rifiuti.
     */
    suspend fun togliGenitore(genitoreId: Long): EsitoScrittura<Unit> {
        val risposta = richiedi("DELETE", "/api/genitori/$genitoreId", null) ?: return EsitoScrittura.Fallito
        return interpretaNuovaRotta(risposta.codice, risposta.corpo)
    }

    private fun codiceGenitoreDa(risposta: RispostaHttp): EsitoScrittura<CodiceGenitoreRicevuto> =
        when (val esito = interpretaCodiceGenitore(risposta.codice, risposta.corpo)) {
            is EsitoScrittura.Riuscito -> EsitoScrittura.Riuscito(CodiceGenitoreRicevuto(esito.dato, risposta.oraServer))
            is EsitoScrittura.Rifiutato -> esito
            EsitoScrittura.Fallito -> EsitoScrittura.Fallito
        }

    // --- Le faccende (0.13, contratto v3.6) ------------------------------------------

    /**
     * GET /api/faccende?figlio_id=n: tutte le faccende da fare, più le fatte e le
     * annullate degli ultimi 30 giorni, dalla più recente.
     */
    override suspend fun leggiFaccende(figlioId: Long?): EsitoFaccende {
        val risposta = richiedi("GET", conFiglio("/api/faccende", figlioId), null) ?: return EsitoFaccende.Fallita
        return interpretaFaccende(risposta.codice, risposta.corpo)
    }

    /**
     * POST /api/faccende: da 1 a 10 faccende al figlio, con lo stesso inizio del
     * blocco. Una creazione: niente ritentativi automatici (due invii farebbero
     * due volte le faccende); dopo un `Fallito` chi chiama rilegge l'elenco.
     * Il dato è null se il server ha detto sì ma il corpo non si legge.
     */
    override suspend fun daiFaccende(corpo: CorpoNuoveFaccende): EsitoScrittura<List<Faccenda>?> {
        val testo = json.encodeToString(CorpoNuoveFaccende.serializer(), corpo)
        val risposta = richiedi("POST", "/api/faccende", testo.toRequestBody(JSON_MEDIA_TYPE), httpCreazioni)
            ?: return EsitoScrittura.Fallito
        return interpretaNuovaRotta(risposta.codice, risposta.corpo, PaccoFaccende.serializer())
            .mappa { it?.faccende }
    }

    /**
     * POST /api/faccende/{id}/boccia: la foto non va, la faccenda torna da fare e
     * il blocco riparte subito. Solo entro 24 ore dalla foto (`non_bocciabile`).
     * Come le creazioni, senza ritentativi automatici ([httpCreazioni]): un secondo
     * invio fatto in silenzio dalla rete, dopo un primo arrivato al server, si
     * prenderebbe un `non_bocciabile` al posto del "fatto".
     */
    override suspend fun bocciaFaccenda(faccendaId: Long, nota: String?): EsitoScrittura<Faccenda?> {
        val corpo = json.encodeToString(CorpoBoccia.serializer(), CorpoBoccia(nota))
        val risposta = richiedi(
            "POST",
            "/api/faccende/$faccendaId/boccia",
            corpo.toRequestBody(JSON_MEDIA_TYPE),
            httpCreazioni,
        ) ?: return EsitoScrittura.Fallito
        return interpretaNuovaRotta(risposta.codice, risposta.corpo, Faccenda.serializer())
    }

    /** POST /api/faccende/{id}/annulla: solo una faccenda da fare (`non_annullabile`). Senza ritentativi, come "Boccia". */
    override suspend fun annullaFaccenda(faccendaId: Long): EsitoScrittura<Faccenda?> {
        val risposta = richiedi("POST", "/api/faccende/$faccendaId/annulla", CORPO_VUOTO, httpCreazioni)
            ?: return EsitoScrittura.Fallito
        return interpretaNuovaRotta(risposta.codice, risposta.corpo, Faccenda.serializer())
    }

    /**
     * GET /api/faccende/{id}/foto, col token come tutto il resto. La foto arriva
     * in memoria e lì resta: niente file, niente galleria. Oltre [MASSIMO_BYTE_FOTO]
     * non si legge (il server ne tiene al massimo 4 MB).
     */
    override suspend fun scaricaFoto(faccendaId: Long): EsitoFoto {
        if (!configurazione.completa) return EsitoFoto.Fallita
        val richiesta = try {
            richiesta("/api/faccende/$faccendaId/foto").get().build()
        } catch (e: IllegalArgumentException) {
            return EsitoFoto.Fallita
        }
        return suspendCancellableCoroutine { continuazione ->
            val chiamata = clientNormale.newCall(richiesta)
            continuazione.invokeOnCancellation { chiamata.cancel() }
            chiamata.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuazione.isActive) continuazione.resume(EsitoFoto.Fallita)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val esito = try {
                            response.use { risposta ->
                                if (risposta.isSuccessful) {
                                    leggiAlMassimo(risposta.body?.byteStream(), MASSIMO_BYTE_FOTO)
                                        ?.let { EsitoFoto.Arrivata(it) }
                                        ?: EsitoFoto.Fallita
                                } else {
                                    interpretaFotoMancante(risposta.code, risposta.body?.string())
                                }
                            }
                        } catch (e: IOException) {
                            EsitoFoto.Fallita
                        }
                        if (continuazione.isActive) continuazione.resume(esito)
                    }
                },
            )
        }
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
     * rifiuti: il codice `errore` sta lì) + l'ora del server (header `Date`),
     * null se la rete cade o l'indirizzo salvato non è un URL. [client]: quello
     * di tutti, o [httpCreazioni] per le creazioni.
     *
     * (0.9) La chiamata è asincrona e si annulla con chi l'ha chiesta: un
     * `withTimeoutOrNull` attorno (la vedetta aspetta i nomi al massimo 10 s)
     * la chiude davvero, invece di aspettare che finisca da sola.
     */
    private suspend fun richiedi(
        metodo: String,
        percorso: String,
        corpo: RequestBody?,
        client: OkHttpClient = clientNormale,
    ): RispostaHttp? {
        if (!configurazione.completa) return null
        val richiesta = try {
            richiesta(percorso).method(metodo, corpo).build()
        } catch (e: IllegalArgumentException) {
            return null // URL malformato nelle impostazioni: non è un motivo per crashare.
        }
        return suspendCancellableCoroutine { continuazione ->
            val chiamata = client.newCall(richiesta)
            continuazione.invokeOnCancellation { chiamata.cancel() }
            chiamata.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuazione.isActive) continuazione.resume(null)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val letta = try {
                            response.use { risposta ->
                                RispostaHttp(risposta.code, risposta.body?.string(), oraDalHeader(risposta.header("Date")))
                            }
                        } catch (e: IOException) {
                            null
                        }
                        if (continuazione.isActive) continuazione.resume(letta)
                    }
                },
            )
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

    /** Codice HTTP + corpo grezzo di una risposta + l'ora del server, se l'ha scritta. */
    private data class RispostaHttp(val codice: Int, val corpo: String?, val oraServer: Instant? = null)

    companion object {
        // Codice d'errore sintetico per il 422 di validazione del server: non è
        // un codice del contratto (il 422 non ne porta uno), lo coniamo qui per
        // dare alla UI un messaggio specifico invece del generico "riprova".
        const val PARAMETRI_NON_VALIDI = "parametri_non_validi"

        /** Il percorso con `?figlio_id=n`; senza figlio (server 0.7) com'era. */
        internal fun conFiglio(percorso: String, figlioId: Long?): String =
            if (figlioId == null) percorso else "$percorso?figlio_id=$figlioId"

        /**
         * (0.10) GET /api/proposte con le proposte di tutti e due gli autori
         * (`autori=tutti`, contratto v3.4), e `figlio_id` quando il figlio è noto.
         */
        internal fun percorsoProposte(figlioId: Long?): String =
            if (figlioId == null) "/api/proposte?autori=tutti" else "/api/proposte?figlio_id=$figlioId&autori=tutti"

        /**
         * (v3.3) Il percorso delle notifiche, con `?dopo_id=n` solo se c'è un id
         * valido (il server rifiuta un numero negativo con un 422).
         */
        internal fun percorsoNotifiche(dopoId: Long?): String =
            if (dopoId == null || dopoId < 0) "/api/notifiche" else "/api/notifiche?dopo_id=$dopoId"

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
            // (0.13) Il codice di questo telefono non vale più: non è la rete.
            codice == 401 -> EsitoFamiglia.NonAutorizzato
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

        /**
         * (0.10) L'esito di una risposta del genitore a una proposta del figlio.
         * Qualunque 2xx vuol dire che il server l'ha presa (e, su un sì, che la
         * regola è già cambiata): il dato è la proposta chiusa se il corpo si legge,
         * null se no. Un corpo inatteso non deve far dire "riprova" su una decisione
         * già fatta: riprovando, il padre si sentirebbe dire che la proposta non è
         * più in attesa. L'app comunque rilegge. Come nel ritiro, una rotta che il
         * server non conosce (404 "Not Found", 405) vuol dire server da aggiornare;
         * un 404 della rotta, una proposta (o un figlio) che non c'è. Il resto come
         * ogni scrittura.
         */
        internal fun interpretaDecisione(codice: Int, corpo: String?): EsitoScrittura<PropostaDecisa?> = when {
            codice in 200..299 -> EsitoScrittura.Riuscito(corpo?.let { decodifica(PropostaDecisa.serializer(), it) })
            codice == 405 -> EsitoScrittura.Rifiutato(CodiciErrore.SERVER_DA_AGGIORNARE)
            codice == 404 && rottaSconosciuta(corpo) -> EsitoScrittura.Rifiutato(CodiciErrore.SERVER_DA_AGGIORNARE)
            else -> rifiuto(codice, corpo)
        }

        /**
         * (0.10) L'esito di POST /api/proposte/{id}/ritira. Come per la decisione,
         * qualunque 2xx è un ritiro fatto (il dato è la proposta ritirata, se il
         * corpo si legge). Un server più vecchio della v3.4 non ha la rotta e
         * risponde 404 o 405 (contratto v3.4, "Compatibilità"): non è un errore da
         * riprovare, serve aggiornare il server ([CodiciErrore.SERVER_DA_AGGIORNARE]).
         * Un 404 detto dalla rotta stessa (`{"detail": "proposta non trovata"}`) è
         * invece una proposta che non c'è più. Il resto come ogni scrittura (409
         * `proposta_non_pendente`, …).
         */
        internal fun interpretaRitiro(codice: Int, corpo: String?): EsitoScrittura<Proposta?> = when {
            codice in 200..299 -> EsitoScrittura.Riuscito(corpo?.let { decodifica(Proposta.serializer(), it) })
            codice == 405 -> EsitoScrittura.Rifiutato(CodiciErrore.SERVER_DA_AGGIORNARE)
            codice == 404 && rottaSconosciuta(corpo) -> EsitoScrittura.Rifiutato(CodiciErrore.SERVER_DA_AGGIORNARE)
            else -> rifiuto(codice, corpo)
        }

        /**
         * (0.11) GET /api/sessioni dal codice HTTP. Come per il ritiro, una rotta che
         * il server non conosce (404 "Not Found", 405) vuol dire server più vecchio
         * della v3.5. Tutto il resto che non è un elenco leggibile è un fallimento.
         */
        internal fun interpretaSessioni(codice: Int, corpo: String?): EsitoSessioni = when {
            codice in 200..299 ->
                corpo?.let { decodifica(PaccoSessioni.serializer(), it) }
                    ?.let { EsitoSessioni.Lette(it.sessioni) }
                    ?: EsitoSessioni.Fallita
            codice == 405 -> EsitoSessioni.ServerVecchio
            codice == 404 && rottaSconosciuta(corpo) -> EsitoSessioni.ServerVecchio
            else -> EsitoSessioni.Fallita
        }

        /**
         * (0.11) L'esito della risposta del genitore a una sessione. Come per le
         * proposte, qualunque 2xx vuol dire che il server l'ha presa: la sessione
         * aggiornata se il corpo si legge, null se no — un corpo inatteso non fa
         * dire "riprova" su una decisione già fatta. Il 409 `richiesta_cambiata`
         * porta la sessione com'è adesso ([sessioneDelRifiuto]). Una rotta che il
         * server non conosce (404 "Not Found", 405) = server da aggiornare; un 404
         * della rotta (`{"detail": "sessione non trovata"}`), una sessione (o un
         * figlio) che non c'è. Il resto come ogni scrittura (409
         * `niente_da_decidere`, `dispositivo_revocato`, 422).
         */
        internal fun interpretaRispostaSessione(codice: Int, corpo: String?): EsitoRispostaSessione = when {
            codice in 200..299 -> EsitoRispostaSessione.Decisa(corpo?.let { decodifica(Sessione.serializer(), it) })
            codice == 405 -> EsitoRispostaSessione.Rifiutata(CodiciErrore.SERVER_DA_AGGIORNARE)
            codice == 404 && rottaSconosciuta(corpo) -> EsitoRispostaSessione.Rifiutata(CodiciErrore.SERVER_DA_AGGIORNARE)
            codice == 409 && codiceErrore(corpo) == CodiciErrore.RICHIESTA_CAMBIATA ->
                EsitoRispostaSessione.Cambiata(sessioneDelRifiuto(corpo))
            else -> when (val esito = rifiuto(codice, corpo)) {
                is EsitoScrittura.Rifiutato -> EsitoRispostaSessione.Rifiutata(esito.errore)
                else -> EsitoRispostaSessione.Fallita
            }
        }

        /**
         * (0.11) La sessione com'è adesso, dentro un 409 `richiesta_cambiata`: in
         * `detail` (come la manda FastAPI) o in cima, come la scrive il contratto.
         * null se manca o non si legge: allora si rilegge la finestra.
         */
        internal fun sessioneDelRifiuto(corpo: String?): Sessione? {
            val (dettaglio, oggetto) = corpoDelRifiuto(corpo) ?: return null
            val elemento = dettaglio?.get("sessione") as? JsonObject ?: oggetto["sessione"] as? JsonObject ?: return null
            return try {
                json.decodeFromJsonElement(Sessione.serializer(), elemento)
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        // --- (0.13) Genitori, abbinamento e faccende (contratto v3.6) -------------------

        /** Oltre questa misura una foto non si legge: il server ne tiene al massimo 4 MB. */
        const val MASSIMO_BYTE_FOTO = 8 * 1024 * 1024

        /**
         * GET /api/genitori dal codice HTTP. Una rotta che il server non conosce (404
         * "Not Found", 405) vuol dire server più vecchio della v3.6: per i genitori
         * serve aggiornarlo. Tutto il resto che non è un elenco leggibile è un fallimento.
         */
        internal fun interpretaGenitori(codice: Int, corpo: String?): EsitoGenitori = when {
            codice in 200..299 ->
                corpo?.let { decodifica(PaccoGenitori.serializer(), it) }
                    ?.let { EsitoGenitori.Letti(it) }
                    ?: EsitoGenitori.Fallita
            codice == 405 -> EsitoGenitori.ServerVecchio
            codice == 404 && rottaSconosciuta(corpo) -> EsitoGenitori.ServerVecchio
            codice == 401 -> EsitoGenitori.NonAutorizzato
            else -> EsitoGenitori.Fallita
        }

        /** GET /api/faccende dal codice HTTP: come [interpretaGenitori]. */
        internal fun interpretaFaccende(codice: Int, corpo: String?): EsitoFaccende = when {
            codice in 200..299 ->
                corpo?.let { decodifica(PaccoFaccende.serializer(), it) }
                    ?.let { EsitoFaccende.Lette(it.faccende) }
                    ?: EsitoFaccende.Fallita
            codice == 405 -> EsitoFaccende.ServerVecchio
            codice == 404 && rottaSconosciuta(corpo) -> EsitoFaccende.ServerVecchio
            codice == 401 -> EsitoFaccende.NonAutorizzato
            else -> EsitoFaccende.Fallita
        }

        /**
         * Il codice di un genitore (creato, o nuovo). Serve il codice: un 2xx che
         * non lo porta è `Fallito`, e chi chiama rilegge i genitori e ne chiede uno
         * nuovo. Una rotta che il server non conosce = server da aggiornare.
         */
        internal fun interpretaCodiceGenitore(codice: Int, corpo: String?): EsitoScrittura<CodiceGenitore> = when {
            codice in 200..299 ->
                corpo?.let { decodifica(CodiceGenitore.serializer(), it) }
                    ?.takeIf { it.codice.isNotBlank() }
                    ?.let { EsitoScrittura.Riuscito(it) }
                    ?: EsitoScrittura.Fallito
            codice == 405 -> EsitoScrittura.Rifiutato(CodiciErrore.SERVER_DA_AGGIORNARE)
            codice == 404 && rottaSconosciuta(corpo) -> EsitoScrittura.Rifiutato(CodiciErrore.SERVER_DA_AGGIORNARE)
            else -> rifiuto(codice, corpo)
        }

        /**
         * Una scrittura su una rotta della v3.6 di cui basta sapere che è andata (un
         * nome cambiato, una revoca): qualunque 2xx. Una rotta che il server non
         * conosce = server da aggiornare; un 404 della rotta (`genitore non trovato`)
         * = [CodiciErrore.NON_TROVATO]; il resto come ogni scrittura.
         */
        internal fun interpretaNuovaRotta(codice: Int, corpo: String?): EsitoScrittura<Unit> = when {
            codice in 200..299 -> EsitoScrittura.Riuscito(Unit)
            codice == 405 -> EsitoScrittura.Rifiutato(CodiciErrore.SERVER_DA_AGGIORNARE)
            codice == 404 && rottaSconosciuta(corpo) -> EsitoScrittura.Rifiutato(CodiciErrore.SERVER_DA_AGGIORNARE)
            else -> rifiuto(codice, corpo)
        }

        /**
         * Come [interpretaNuovaRotta], col dato della risposta (null se il corpo non
         * si legge): conta il 2xx, non la forma del corpo. Un "sì" del server con un
         * corpo inatteso non deve far dire "riprova" su un gesto già fatto — una
         * faccenda bocciata due volte, faccende date due volte.
         */
        internal fun <T> interpretaNuovaRotta(
            codice: Int,
            corpo: String?,
            serializer: kotlinx.serialization.KSerializer<T>,
        ): EsitoScrittura<T?> = when {
            codice in 200..299 -> EsitoScrittura.Riuscito(corpo?.let { decodifica(serializer, it) })
            codice == 405 -> EsitoScrittura.Rifiutato(CodiciErrore.SERVER_DA_AGGIORNARE)
            codice == 404 && rottaSconosciuta(corpo) -> EsitoScrittura.Rifiutato(CodiciErrore.SERVER_DA_AGGIORNARE)
            else -> rifiuto(codice, corpo)
        }

        /**
         * Una foto che non è arrivata: un 404 della rotta (`foto non trovata`, ma
         * anche `faccenda non trovata`) = la foto non c'è; una rotta che il server
         * non conosce = server da aggiornare; il resto = da riprovare.
         */
        internal fun interpretaFotoMancante(codice: Int, corpo: String?): EsitoFoto = when {
            codice == 405 -> EsitoFoto.ServerVecchio
            codice == 404 && rottaSconosciuta(corpo) -> EsitoFoto.ServerVecchio
            codice == 404 -> EsitoFoto.NonTrovata
            codice == 401 -> EsitoFoto.NonAutorizzato
            else -> EsitoFoto.Fallita
        }

        /** I byte di [flusso], al massimo [massimo]; null se ce ne sono di più (o se manca). */
        internal fun leggiAlMassimo(flusso: java.io.InputStream?, massimo: Int): ByteArray? {
            if (flusso == null) return null
            val uscita = java.io.ByteArrayOutputStream()
            val pezzo = ByteArray(16 * 1024)
            var letti = 0
            while (true) {
                val n = flusso.read(pezzo)
                if (n < 0) break
                letti += n
                if (letti > massimo) return null
                uscita.write(pezzo, 0, n)
            }
            return uscita.toByteArray().takeIf { it.isNotEmpty() }
        }

        /**
         * POST /api/abbina col tipo "genitore" (contratto v3.6): nessun token (è
         * proprio quello che si chiede). Come le creazioni, niente ritentativo
         * automatico: il codice vale una volta sola, e un secondo invio dopo una
         * risposta persa riceverebbe "codice non valido" al posto del collegamento.
         * [NonCancellable]: il token arriva una volta sola, e una risposta persa
         * perché chi chiama ha rinunciato lascerebbe il codice consumato per niente.
         * [serverUrl] è già normalizzato (normalizzaUrlServer).
         */
        suspend fun abbinaGenitore(serverUrl: String, codice: String, versioneApp: String?): EsitoAbbinamento =
            withContext(NonCancellable + Dispatchers.IO) {
                val corpo = json.encodeToString(
                    CorpoAbbinaGenitore.serializer(),
                    CorpoAbbinaGenitore(codice = codice, tipo = TIPO_ABBINAMENTO_GENITORE, versioneApp = versioneApp),
                )
                try {
                    val richiesta = Request.Builder()
                        .url("$serverUrl/api/abbina")
                        .post(corpo.toRequestBody(JSON_MEDIA_TYPE))
                        .build()
                    httpCreazioni.newCall(richiesta).execute().use { risposta ->
                        interpretaAbbinamento(risposta.code, risposta.body?.string())
                    }
                } catch (e: IOException) {
                    EsitoAbbinamento.SenzaRete
                } catch (e: IllegalArgumentException) {
                    EsitoAbbinamento.Errore // indirizzo che non è un URL: non è un motivo per crashare
                }
            }

        /**
         * La risposta di POST /api/abbina tradotta in un esito. L'errore si legge sia
         * dentro `detail` (FastAPI) sia in cima (il contratto). Un 200 senza token
         * non collega niente: meglio dirlo che salvare il vuoto. Il 422 è il server
         * più vecchio della v3.6, che il tipo "genitore" non lo conosce.
         */
        internal fun interpretaAbbinamento(codice: Int, corpo: String?): EsitoAbbinamento {
            if (codice in 200..299) {
                val risposta = corpo?.let { decodifica(AbbinamentoGenitore.serializer(), it) }
                val token = risposta?.token?.trim().orEmpty()
                return if (token.isEmpty()) EsitoAbbinamento.Errore else EsitoAbbinamento.Collegato(token, risposta?.genitore)
            }
            val errore = codiceErrore(corpo)
            return when {
                errore == CodiciErrore.TROPPI_TENTATIVI || codice == 429 ->
                    EsitoAbbinamento.TroppiTentativi(riprovaTraSecondi(corpo)?.takeIf { it > 0 })
                // Prima del 409 generico: il codice è buono, ma per un dispositivo.
                errore == CodiciErrore.TIPO_NON_CORRISPONDENTE ->
                    EsitoAbbinamento.TipoNonCorrispondente(tipoAtteso(corpo))
                errore == CodiciErrore.CODICE_NON_VALIDO || codice == 409 -> EsitoAbbinamento.CodiceNonValido
                codice == 422 -> EsitoAbbinamento.ServerDaAggiornare
                codice == 404 || codice == 405 -> EsitoAbbinamento.ServerSenzaCodici
                else -> EsitoAbbinamento.Errore
            }
        }

        /** Il `tipo_atteso` di un 409 `tipo_non_corrispondente`, in minuscolo; null se manca. */
        private fun tipoAtteso(corpo: String?): String? {
            val (dettaglio, oggetto) = corpoDelRifiuto(corpo) ?: return null
            return (testo(dettaglio?.get("tipo_atteso")) ?: testo(oggetto["tipo_atteso"]))
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() }
        }

        /**
         * (0.10) true = un 404 della rotta che non esiste: `{"detail": "Not Found"}`,
         * la risposta di FastAPI a un indirizzo che non conosce, oppure un corpo che
         * non si legge. Un 404 con un altro `detail` viene da una rotta che c'è.
         */
        internal fun rottaSconosciuta(corpo: String?): Boolean {
            val (_, oggetto) = corpoDelRifiuto(corpo) ?: return true
            val dettaglio = oggetto["detail"] ?: return true
            return testo(dettaglio) == "Not Found"
        }

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
            // (0.13) 401 = il codice di questo telefono non vale più (un altro
            // genitore l'ha tolto, contratto v3.6): non è la rete, e "riprova" non serve.
            401 -> EsitoScrittura.Rifiutato(CodiciErrore.COLLEGAMENTO_NON_VALIDO)
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
        // OkHttp chiede da solo le risposte compresse (gzip, contratto v3.3) e
        // le scompatta prima che arrivino qui.
        private val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

        /**
         * (0.9) Il client del giro della vedetta: lo stesso, con 30 secondi al
         * massimo per richiesta in tutto. Il download dell'aggiornamento non
         * passa di qui (può durare di più, ed è del worker).
         */
        private val httpVedetta: OkHttpClient = http.newBuilder()
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

        /**
         * Le creazioni (figlio, dispositivo, codice) NON si ritentano da sole.
         * Con il ritentativo di OkHttp, una connessione caduta dopo che il server
         * ha già ricevuto il POST lo rimanderebbe in silenzio: due figli, o due
         * dispositivi, al posto di uno. Il dubbio dopo una risposta persa lo
         * risolve chi chiama rileggendo la famiglia, non la rete. (Come le
         * mutazioni dell'app del figlio, `httpMutazioni`.)
         *
         * Pool proprio, senza connessioni tenute aperte: senza ritentativo, una
         * connessione rimasta ferma e già chiusa dal server (uvicorn la chiude
         * dopo 5 s) farebbe fallire il primo invio dopo ogni pausa. E prima gli
         * indirizzi IPv4 ([DnsPrimaIpv4]): senza ritentativo OkHttp non prova
         * l'indirizzo successivo quando il primo non risponde, e su una rete con
         * IPv6 rotto un indirizzo IPv6 in testa farebbe fallire ogni creazione.
         */
        private val httpCreazioni: OkHttpClient = http.newBuilder()
            .retryOnConnectionFailure(false)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .dns(DnsPrimaIpv4)
            .build()

        /**
         * L'ora del server da un header `Date` (RFC 1123: "Thu, 24 Sep 2026
         * 10:00:00 GMT"), null se manca o non si legge.
         */
        internal fun oraDalHeader(valore: String?): Instant? {
            if (valore.isNullOrBlank()) return null
            return try {
                ZonedDateTime.parse(valore.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            } catch (e: DateTimeParseException) {
                null
            }
        }

        /** Gli indirizzi con gli IPv4 in testa, poi gli altri; dentro ciascun gruppo l'ordine del sistema. */
        internal fun primaIpv4(indirizzi: List<InetAddress>): List<InetAddress> {
            val (ipv4, altri) = indirizzi.partition { it is Inet4Address }
            return ipv4 + altri
        }

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

/** Il DNS del sistema, con gli indirizzi IPv4 prima degli altri (v. httpCreazioni). */
private object DnsPrimaIpv4 : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        PostinoClient.primaIpv4(Dns.SYSTEM.lookup(hostname))
}
