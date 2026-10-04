package eu.stgm.pactum.figlio.rete

import eu.stgm.pactum.figlio.dati.AbbinaIn
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
import eu.stgm.pactum.figlio.dati.PaccoRegole
import eu.stgm.pactum.figlio.dati.Patto
import eu.stgm.pactum.figlio.dati.Proposta
import eu.stgm.pactum.figlio.dati.PropostaIn
import eu.stgm.pactum.figlio.dati.ProposteDelFiglio
import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.RispostaPropostaIn
import eu.stgm.pactum.figlio.faccende.Orologio
import eu.stgm.pactum.figlio.sync.PacchiEventi
import eu.stgm.pactum.figlio.sessione.AvvioSessioneIn
import eu.stgm.pactum.figlio.sessione.SessioneIn
import eu.stgm.pactum.figlio.sessione.SessioneModificaIn
import eu.stgm.pactum.figlio.sessione.TerminaSessioneIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

/**
 * Client verso il server postino. Protocollo: docs/contratto-api.md
 * (fonte di verità — ogni modifica passa prima da lì).
 *
 *   POST {base}/api/battito · POST {base}/api/eventi   → consegna tollerante all'offline
 *   GET  {base}/api/patto · /api/regole · /api/proposte?autori=tutti (v3.4) · /api/dichiarazioni · /api/notifiche
 *   POST/PATCH/DELETE {base}/api/regole · POST /api/bonus · /api/dichiarazioni ·
 *        /api/proposte/{id}/risposta                    → mutazioni con esito HTTP
 *   POST {base}/api/proposte · /api/proposte/{id}/ritira (v3.4) → le proposte del figlio
 *   GET/POST/PATCH/DELETE {base}/api/sessioni · …/{id}/avvia · …/in_corso/termina (v3.5) → le Sessioni
 *   GET {base}/api/faccende · /api/faccende/blocco · GET/PUT /api/faccende/{id}/foto (v3.6) → le faccende
 *   POST {base}/api/abbina (v3, senza token)            → il codice di 6 cifre diventa un token
 *   header: Authorization: Bearer <token di questo dispositivo>
 *
 * Battito ed eventi restituiscono Boolean (o passa o resta in coda). Le
 * mutazioni del patto restituiscono [RispostaHttp] (codice + corpo) perché
 * all'app serve distinguere un 409 di lock da un errore di rete e leggerne i
 * dettagli (secondi residui, tetto superato, ultima regola).
 */
class PostinoClient(private val configurazione: ConfigurazionePostino) {

    /**
     * Esito grezzo di una mutazione: [codice] 0 = errore di rete o URL
     * malformato. (0.11) [incerta] = con codice 0, la richiesta forse è
     * arrivata al server (la rete è caduta dopo l'invio): non si sa com'è andata.
     */
    data class RispostaHttp(val ok: Boolean, val codice: Int, val corpo: String?, val incerta: Boolean = false)

    suspend fun inviaBattito(battito: Battito): Boolean =
        inviaSemplice("/api/battito", json.encodeToString(Battito.serializer(), battito))

    /**
     * (0.14) Pochi eventi mandati di corsa, con un tempo massimo per tutta la
     * richiesta ([limiteMs]): la `sospensione` mentre il telefono si spegne,
     * che non deve trattenere lo spegnimento. True se il server li ha presi;
     * se no restano in coda e partono alla riaccensione (stesso id: il server
     * non li conta due volte).
     */
    suspend fun inviaEventiSubito(eventi: List<Evento>, limiteMs: Long): Boolean {
        if (eventi.isEmpty()) return true
        if (!configurazione.completa) return false
        val corpo = json.encodeToString(PaccoEventi.serializer(), PaccoEventi(eventi))
        return withContext(Dispatchers.IO) {
            try {
                val veloce = http.newBuilder().callTimeout(limiteMs, TimeUnit.MILLISECONDS).build()
                val richiesta = richiesta("/api/eventi").post(corpo.toRequestBody(JSON_MEDIA_TYPE)).build()
                veloce.newCall(richiesta).execute().use { it.isSuccessful }
            } catch (e: IOException) {
                false
            } catch (e: IllegalArgumentException) {
                false
            }
        }
    }

    /**
     * La coda degli eventi, in pacchi (0.13): tutta in una volta, e se il
     * server risponde 413 (corpo oltre 8 MB) in pacchi sempre più piccoli
     * (PacchiEventi). Restituisce quali eventi sono arrivati (o sono da
     * togliere perché troppo grandi anche da soli) e se non è rimasto niente.
     */
    suspend fun inviaEventi(eventi: List<Evento>): PacchiEventi.Esito<Evento> =
        PacchiEventi.consegna(eventi) { pacco -> inviaPaccoEventi(pacco) }

    /** Un pacco di eventi: il codice HTTP (0 = rete, URL malformato o non configurato). */
    private suspend fun inviaPaccoEventi(eventi: List<Evento>): Int {
        if (!configurazione.completa) return 0
        val corpo = json.encodeToString(PaccoEventi.serializer(), PaccoEventi(eventi))
        return withContext(Dispatchers.IO) {
            try {
                val richiesta = richiesta("/api/eventi")
                    .post(corpo.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                http.newCall(richiesta).execute().use { it.code }
            } catch (e: IOException) {
                0
            } catch (e: IllegalArgumentException) {
                0
            }
        }
    }

    /**
     * Il battito di prova delle Impostazioni, col suo codice HTTP: 200 = il
     * server risponde, 401 = questo telefono non è (più) collegato (revocato,
     * o ricollegato con un codice nuovo altrove), 0 = niente rete.
     */
    suspend fun provaBattito(battito: Battito): Int {
        if (!configurazione.completa) return 0
        return withContext(Dispatchers.IO) {
            try {
                val richiesta = richiesta("/api/battito")
                    .post(json.encodeToString(Battito.serializer(), battito).toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                http.newCall(richiesta).execute().use { it.code }
            } catch (e: IOException) {
                0
            } catch (e: IllegalArgumentException) {
                0
            }
        }
    }

    // --- Letture (sync dell'app) --------------------------------------------

    /**
     * GET /api/patto. La copia porta l'impronta del collegamento con cui è
     * stata letta (`letto_con`): PattoLocale non la salva se nel frattempo il
     * telefono è stato ricollegato.
     */
    suspend fun leggiPatto(): Patto? = leggiPattoConCodice().first

    /** Il patto e il codice HTTP della lettura: 401 = questo telefono non è più collegato. */
    suspend fun leggiPattoConCodice(): Pair<Patto?, Int> {
        // (0.13) Quando è partita e arrivata la domanda (sull'orologio che non
        // si sposta) e l'ora del server: una lettura lenta del patto non deve
        // rimettere un blocco delle faccende tolto da una risposta più fresca,
        // e il blocco programmato parte all'ora del server, non del telefono.
        val partita = Orologio.adesso()
        val lettura = leggiConData("/api/patto")
        val arrivata = Orologio.adesso()
        val patto = lettura.corpo
            ?.let { decodifica(Patto.serializer(), it) }
            ?.copy(lettoCon = configurazione.impronta, lettaIl = partita, arrivataIl = arrivata, dataServer = lettura.dataServer)
        return patto to lettura.codice
    }

    /**
     * (v3) GET /api/regole: le regole attive di TUTTO il figlio, di ogni suo
     * dispositivo, ciascuna col suo `dispositivo`. Serve a dire su quale regola
     * del computer verte una proposta. Un server vecchio dà le stesse del patto.
     */
    suspend fun leggiRegole(): List<Regola>? =
        leggi("/api/regole")?.let { decodifica(PaccoRegole.serializer(), it) }?.regole

    /**
     * GET /api/proposte: le proposte del figlio, dalla più recente, al massimo
     * 50. (0.10, v3.4) Sempre con `?autori=tutti`: senza, un server v3.4 manda
     * solo quelle del genitore (per le app 0.8 e 0.9). Chi deve rispondere lo
     * dice `autore`. Un server vecchio ignora il parametro.
     */
    suspend fun leggiProposte(): List<Proposta>? =
        leggi(ProposteDelFiglio.PERCORSO_ELENCO)?.let { decodifica(PaccoProposte.serializer(), it) }?.proposte

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

    /**
     * (0.10, v3.4) Il figlio propone al genitore un cambio a una sua regola: se
     * il genitore accetta, vale subito. Un server di prima della v3.4 risponde
     * 403 (ruolo sbagliato): lo legge ProposteDelFiglio.esito.
     */
    suspend fun mandaProposta(corpo: PropostaIn): RispostaHttp =
        mutazione("POST", "/api/proposte", json.encodeToString(PropostaIn.serializer(), corpo))

    /** (0.10, v3.4) Ritira una proposta del figlio ancora in attesa. Nessun corpo. */
    suspend fun ritiraProposta(propostaId: Long): RispostaHttp =
        mutazione("POST", "/api/proposte/$propostaId/ritira", null)

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

    // --- (0.11, v3.5) Le Sessioni ---------------------------------------------
    // Un server di prima della v3.5 risponde 404 o 405: lo legge EsitiSessioni.

    /** GET /api/sessioni: il corpo (null se non 2xx) e il codice HTTP. */
    suspend fun leggiSessioni(): Pair<String?, Int> = leggiConCodice("/api/sessioni")

    suspend fun creaSessione(corpo: SessioneIn): RispostaHttp =
        mutazione("POST", "/api/sessioni", json.encodeToString(SessioneIn.serializer(), corpo))

    suspend fun modificaSessione(sessioneId: Long, corpo: SessioneModificaIn): RispostaHttp =
        mutazione("PATCH", "/api/sessioni/$sessioneId", json.encodeToString(SessioneModificaIn.serializer(), corpo))

    suspend fun eliminaSessione(sessioneId: Long): RispostaHttp =
        mutazione("DELETE", "/api/sessioni/$sessioneId", null)

    /** "Inizia": mai ritentata da sola (due avvii al posto di uno), come le altre mutazioni. */
    suspend fun avviaSessione(sessioneId: Long, corpo: AvvioSessioneIn): RispostaHttp =
        mutazione("POST", "/api/sessioni/$sessioneId/avvia", json.encodeToString(AvvioSessioneIn.serializer(), corpo))

    /** "Termina la sessione", con l'istante vero della chiusura fatta sul telefono. */
    suspend fun terminaSessione(corpo: TerminaSessioneIn): RispostaHttp =
        mutazione("POST", "/api/sessioni/in_corso/termina", json.encodeToString(TerminaSessioneIn.serializer(), corpo))

    // --- (0.13, v3.6) Le faccende --------------------------------------------
    // Un server di prima della v3.6 risponde 404 o 405: lo legge EsitiFaccende.

    /**
     * GET /api/faccende/blocco: la risposta piccola che si chiede spesso. Il
     * corpo (null se non 2xx), il codice e l'ora del server (intestazione Date).
     */
    suspend fun leggiBlocco(): LetturaConData = leggiConData("/api/faccende/blocco")

    /** GET /api/faccende: le da fare e quelle chiuse negli ultimi 30 giorni. */
    suspend fun leggiFaccende(): Pair<String?, Int> = leggiConCodice("/api/faccende")

    /**
     * PUT /api/faccende/{id}/foto, il corpo = il JPEG (non JSON). Mai ritentata
     * da OkHttp: la ritenta la coda (ConsegnaFoto), e il server accetta la
     * stessa foto due volte senza farne due. [bocciature] = quante bocciature
     * aveva la faccenda quando la foto è stata scattata (`?bocciature=N`): se
     * intanto è stata bocciata di nuovo, il server risponde 409
     * `bocciata_nel_frattempo` e la foto vecchia non sblocca niente.
     */
    suspend fun mandaFoto(faccendaId: Long, jpeg: ByteArray, bocciature: Int): RispostaHttp {
        if (!configurazione.completa) return RispostaHttp(ok = false, codice = 0, corpo = null)
        return withContext(Dispatchers.IO) {
            try {
                val richiesta = richiesta("/api/faccende/$faccendaId/foto?bocciature=${bocciature.coerceAtLeast(0)}")
                    .put(jpeg.toRequestBody(JPEG_MEDIA_TYPE))
                    .build()
                httpFoto.newCall(richiesta).execute().use { risposta ->
                    RispostaHttp(ok = risposta.isSuccessful, codice = risposta.code, corpo = risposta.body?.string())
                }
            } catch (e: IOException) {
                RispostaHttp(ok = false, codice = 0, corpo = null, incerta = forseArrivata(e))
            } catch (e: IllegalArgumentException) {
                RispostaHttp(ok = false, codice = 0, corpo = null)
            }
        }
    }

    /**
     * GET /api/faccende/{id}/foto su [destinazione]: il codice HTTP (200 =
     * scaricata tutta; 404 = la foto non c'è, o il server non conosce le
     * faccende; 0 = niente rete).
     */
    suspend fun scaricaFoto(faccendaId: Long, destinazione: File): Int {
        if (!configurazione.completa) return 0
        return withContext(Dispatchers.IO) {
            try {
                val richiesta = richiesta("/api/faccende/$faccendaId/foto").get().build()
                httpFoto.newCall(richiesta).execute().use { risposta ->
                    val corpo = risposta.body
                    if (!risposta.isSuccessful || corpo == null) return@use risposta.code
                    val temp = File(destinazione.parentFile, destinazione.name + ".tmp")
                    temp.outputStream().use { out -> corpo.byteStream().copyTo(out) }
                    if (!temp.renameTo(destinazione)) {
                        destinazione.delete()
                        if (!temp.renameTo(destinazione)) return@use 0
                    }
                    risposta.code
                }
            } catch (e: IOException) {
                0
            } catch (e: IllegalArgumentException) {
                0
            }
        }
    }

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
    private suspend fun leggi(percorso: String): String? = leggiConCodice(percorso).first

    /** Come [leggi], più il codice HTTP (0 = rete, URL malformato o non configurato). */
    private suspend fun leggiConCodice(percorso: String): Pair<String?, Int> {
        if (!configurazione.completa) return null to 0
        return withContext(Dispatchers.IO) {
            try {
                val richiesta = richiesta(percorso).get().build()
                http.newCall(richiesta).execute().use { risposta ->
                    (if (risposta.isSuccessful) risposta.body?.string() else null) to risposta.code
                }
            } catch (e: IOException) {
                null to 0
            } catch (e: IllegalArgumentException) {
                null to 0
            }
        }
    }

    /** (0.13) Una lettura col corpo (null se non 2xx), il codice e l'ora del server della risposta. */
    data class LetturaConData(val corpo: String?, val codice: Int, val dataServer: Long?)

    private suspend fun leggiConData(percorso: String): LetturaConData {
        if (!configurazione.completa) return LetturaConData(null, 0, null)
        return withContext(Dispatchers.IO) {
            try {
                val richiesta = richiesta(percorso).get().build()
                http.newCall(richiesta).execute().use { risposta ->
                    LetturaConData(
                        corpo = if (risposta.isSuccessful) risposta.body?.string() else null,
                        codice = risposta.code,
                        dataServer = try {
                            risposta.headers.getDate("Date")?.time
                        } catch (e: Exception) {
                            null
                        },
                    )
                }
            } catch (e: IOException) {
                LetturaConData(null, 0, null)
            } catch (e: IllegalArgumentException) {
                LetturaConData(null, 0, null)
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
                httpMutazioni.newCall(richiesta).execute().use { risposta ->
                    RispostaHttp(
                        ok = risposta.isSuccessful,
                        codice = risposta.code,
                        corpo = risposta.body?.string(),
                    )
                }
            } catch (e: IOException) {
                RispostaHttp(ok = false, codice = 0, corpo = null, incerta = forseArrivata(e))
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
        /**
         * (0.11) Una richiesta fallita forse è arrivata lo stesso al server?
         * No se non si è mai collegato (nome del server sconosciuto, nessuna
         * strada, collegamento rifiutato o scaduto prima di collegarsi, cifratura
         * mai partita); negli altri casi (risposta mai arrivata, collegamento
         * caduto a metà) non si sa.
         */
        fun forseArrivata(errore: IOException): Boolean = when (errore) {
            is UnknownHostException, is ConnectException, is NoRouteToHostException, is SSLHandshakeException -> false
            is SocketTimeoutException -> errore.message?.contains("connect", ignoreCase = true) != true
            else -> true
        }

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val JPEG_MEDIA_TYPE = "image/jpeg".toMediaType()
        private val CORPO_VUOTO = ByteArray(0).toRequestBody(null)
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        // Un solo client OkHttp per processo: riusa pool di connessioni e thread.
        private val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

        /**
         * Le mutazioni (bonus, dichiarazioni, proposte e risposte, regole)
         * NON si ritentano da sole. Con il ritentativo di OkHttp, una
         * connessione caduta dopo che il server ha già ricevuto il POST lo
         * rimanderebbe in silenzio: due bonus al posto di uno. Il dubbio dopo
         * una risposta persa lo risolve chi chiama (per il bonus: `inviato` e
         * `base` in ConsegnaBonus), non la rete.
         *
         * Pool proprio, senza connessioni tenute aperte: senza ritentativo, una
         * connessione rimasta ferma e già chiusa dal server (uvicorn la chiude
         * dopo 5 s) farebbe fallire il primo invio dopo ogni pausa. Visto
         * sull'emulatore: il primo "Accetto" falliva, il secondo andava. Una
         * connessione nuova per ogni mutazione costa un giro in più, e le
         * mutazioni sono rare.
         *
         * Prima gli indirizzi IPv4 ([DnsPrimaIpv4]): senza ritentativo OkHttp
         * prova SOLO il primo indirizzo del server, che ne ha IPv4 e IPv6. Su
         * una rete con l'IPv6 rotto ogni modifica fallirebbe, mentre le letture
         * (che ritentano sull'indirizzo dopo) vanno. Il ritentativo resta spento.
         */
        private val httpMutazioni: OkHttpClient = http.newBuilder()
            .retryOnConnectionFailure(false)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .dns(DnsPrimaIpv4())
            .build()

        /**
         * (0.13) Le foto delle faccende: come le mutazioni (niente ritentativo
         * di OkHttp, prima IPv4), ma con più tempo per mandare e ricevere 2-3
         * MB su una rete mobile lenta.
         */
        private val httpFoto: OkHttpClient = httpMutazioni.newBuilder()
            .writeTimeout(90, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()

        /**
         * (v3) POST /api/abbina: nessun token (è proprio quello che si chiede).
         * Come le mutazioni, niente ritentativo automatico: il codice vale una
         * volta sola, e un secondo invio dopo una risposta persa riceverebbe
         * "codice non valido" al posto del collegamento riuscito. [serverUrl] è
         * già normalizzato (normalizzaUrlServer).
         *
         * [NonCancellable]: il token arriva una volta sola. Se chi chiama
         * venisse annullato mentre la richiesta è in viaggio, la risposta
         * andrebbe persa con il codice già consumato dal server.
         */
        suspend fun abbina(serverUrl: String, corpo: AbbinaIn): RispostaHttp =
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    val richiesta = Request.Builder()
                        .url("$serverUrl/api/abbina")
                        .post(json.encodeToString(AbbinaIn.serializer(), corpo).toRequestBody(JSON_MEDIA_TYPE))
                        .build()
                    httpMutazioni.newCall(richiesta).execute().use { risposta ->
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
            val analizzato = conSchema.toHttpUrlOrNull() ?: return null
            // La base si ricostruisce da zero tenendo SOLO schema, host, porta e
            // segmenti di percorso: query e frammento incollati prima di
            // "/api/..." produrrebbero un endpoint rotto (il figlio non
            // consegnerebbe mai senza un errore chiaro), e le credenziali
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
