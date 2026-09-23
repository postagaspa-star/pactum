package eu.stgm.pactum.figlio.dati

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

// Logica pura dell'abbinamento con codice (contratto v3, "Abbinamento con
// codice"): niente Android, si prova con JUnit semplice. La rete e il disco li
// fa Collegamento; qui si decide solo cosa vuol dire una risposta.

/** Com'è andato POST /api/abbina, nelle categorie che il ragazzo deve distinguere. */
sealed interface EsitoAbbinamento {
    /** Collegato: il token (che il server dà una volta sola), il dispositivo e il figlio. */
    data class Collegato(
        val token: String,
        val dispositivo: Dispositivo?,
        val figlio: Figlio?,
    ) : EsitoAbbinamento

    /** Sbagliato, scaduto o già usato: il server dà la stessa risposta per i tre casi. */
    data object CodiceNonValido : EsitoAbbinamento

    /** Troppi codici sbagliati sul server: si aspetta, anche con il codice giusto. */
    data class TroppiTentativi(val riprovaTraSecondi: Long?) : EsitoAbbinamento

    /**
     * (v3.1) Il codice è di un dispositivo di un altro tipo ([tipoAtteso], di
     * solito `computer`): il server non l'ha consumato, il genitore deve dare
     * quello giusto. null = il server non ha detto quale tipo aspettava.
     */
    data class TipoNonCorrispondente(val tipoAtteso: String?) : EsitoAbbinamento

    /** A quell'indirizzo non c'è un server che conosca i codici (indirizzo sbagliato o server vecchio). */
    data object ServerSenzaCodici : EsitoAbbinamento

    /** Il server non si raggiunge. */
    data object SenzaRete : EsitoAbbinamento

    /** Qualsiasi altra cosa: si riprova. */
    data object Errore : EsitoAbbinamento
}

/**
 * Il telefono si è collegato a un dispositivo del patto diverso da quello di
 * prima: come si chiamano i due (vuoto = non si sa) e se quello di prima è
 * stato scollegato dal genitore (allora non riceve più codici nuovi).
 */
data class CambioDispositivo(
    val nomeNuovo: String,
    val nomePrima: String,
    val primaScollegato: Boolean = false,
)

object Abbinamento {

    /** Il codice che il genitore genera dalla sua app: 6 cifre. */
    const val CIFRE = 6

    /**
     * Il dispositivo che il server, al primo avvio della v3, dà al vecchio
     * codice lungo (PACTUM_TOKEN_FIGLIO): il dispositivo 1 del figlio 1
     * (contratto v3, "Compatibilità con le app 0.7" e "Migrazione").
     */
    const val DISPOSITIVO_STORICO = 1L
    const val FIGLIO_STORICO = 1L

    /**
     * Quello che il campo del codice tiene di quanto si scrive o si incolla:
     * solo le cifre, al massimo 6. "483 920" e "483-920" diventano "483920".
     */
    fun soloCifre(testo: String): String = testo.filter { it in '0'..'9' }.take(CIFRE)

    /** Vero se [codice] è pronto da mandare: esattamente 6 cifre. */
    fun codiceCompleto(codice: String): Boolean =
        codice.length == CIFRE && codice.all { it in '0'..'9' }

    /**
     * Il corpo di POST /api/abbina. (v3.1) Questa app dice sempre che è un
     * telefono: così il codice di un computer, scritto qui per sbaglio, non fa
     * prendere a questo telefono il posto del computer (il server risponde
     * `tipo_non_corrispondente` e non consuma il codice).
     */
    fun richiesta(codice: String, versioneApp: String): AbbinaIn =
        AbbinaIn(codice = codice, tipo = TipiDispositivo.TELEFONO, versioneApp = versioneApp)

    /**
     * La risposta del server tradotta in un esito. L'errore si legge dal corpo
     * sia nella forma del contratto (`{"errore": …}`) sia in quella che FastAPI
     * dà agli errori (`{"detail": {"errore": …}}`); se il corpo non si legge,
     * decide il codice HTTP (409 codice non valido, 429 troppi tentativi).
     */
    fun esito(ok: Boolean, codiceHttp: Int, corpo: String?): EsitoAbbinamento {
        if (ok) {
            val risposta = corpo?.let {
                runCatching { json.decodeFromString(AbbinaOut.serializer(), it) }.getOrNull()
            }
            val token = risposta?.token?.trim().orEmpty()
            // Un 200 senza token non collega niente: meglio dirlo che salvare il vuoto.
            return if (token.isEmpty()) {
                EsitoAbbinamento.Errore
            } else {
                EsitoAbbinamento.Collegato(token, risposta?.dispositivo, risposta?.figlio)
            }
        }
        val dettaglio = dettaglio(corpo)
        return when {
            dettaglio?.errore == ERRORE_TROPPI || codiceHttp == 429 ->
                EsitoAbbinamento.TroppiTentativi(dettaglio?.riprovaTraSecondi?.takeIf { it > 0 })
            // Prima del 409 generico: anche questo è un 409, ma il codice è buono
            // (per un altro dispositivo) e non va detto "non valido".
            dettaglio?.errore == ERRORE_TIPO -> EsitoAbbinamento.TipoNonCorrispondente(
                dettaglio.tipoAtteso?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
            )
            dettaglio?.errore == ERRORE_CODICE || codiceHttp == 409 -> EsitoAbbinamento.CodiceNonValido
            codiceHttp == 0 -> EsitoAbbinamento.SenzaRete
            // Nessuna pagina /api/abbina: indirizzo che non è Pactum, o server
            // ancora alla 0.7. Il codice non c'entra: non va detto "non valido".
            codiceHttp == 404 || codiceHttp == 405 -> EsitoAbbinamento.ServerSenzaCodici
            else -> EsitoAbbinamento.Errore
        }
    }

    /**
     * Il nuovo collegamento continua la storia di QUESTO dispositivo? Succede
     * quando il genitore genera un codice nuovo per lo stesso dispositivo
     * (telefono reinstallato, primo abbinamento non riuscito): stesso server,
     * stesso dispositivo, cambia solo il token. Allora sul telefono non si
     * butta niente. Un telefono collegato col vecchio codice lungo, di cui non
     * si è mai saputo il dispositivo, è il dispositivo 1.
     */
    fun stessoDispositivo(
        eraCollegato: Boolean,
        serverPrima: String,
        serverDopo: String,
        dispositivoPrima: Long?,
        dispositivoDopo: Long?,
    ): Boolean = stesso(eraCollegato, serverPrima, serverDopo, dispositivoPrima, dispositivoDopo, DISPOSITIVO_STORICO)

    /**
     * Stesso figlio (anche con un altro dispositivo): la serie di giorni di
     * fila è sua, calcolata dalla SUA striscia, e resta. Un altro figlio sullo
     * stesso telefono riparte da capo.
     */
    fun stessoFiglio(
        eraCollegato: Boolean,
        serverPrima: String,
        serverDopo: String,
        figlioPrima: Long?,
        figlioDopo: Long?,
    ): Boolean = stesso(eraCollegato, serverPrima, serverDopo, figlioPrima, figlioDopo, FIGLIO_STORICO)

    private fun stesso(
        eraCollegato: Boolean,
        serverPrima: String,
        serverDopo: String,
        idPrima: Long?,
        idDopo: Long?,
        storico: Long,
    ): Boolean {
        if (!eraCollegato || idDopo == null || idDopo <= 0) return false
        if (!stessoServer(serverPrima, serverDopo)) return false
        return (idPrima?.takeIf { it > 0 } ?: storico) == idDopo
    }

    /**
     * Stesso indirizzo del server. Tutti e due sono già normalizzati
     * (normalizzaUrlServer, anche nella 0.7): basta la "/" in fondo.
     */
    fun stessoServer(serverPrima: String, serverDopo: String): Boolean =
        serverPrima.trim().trimEnd('/') == serverDopo.trim().trimEnd('/')

    /**
     * Gli sforamenti ancora in coda si buttano quando portano i numeri delle
     * regole di un altro patto: mandati col collegamento nuovo finirebbero su
     * regole che non sono le loro. Succede con un altro server (anche col
     * codice lungo: i numeri delle regole sono quelli del server vecchio) e con
     * un altro dispositivo. Col codice lungo sullo stesso server non si sa di
     * che dispositivo sia ([stessoDispositivo] null): la coda resta, niente
     * sforamenti buttati per un sospetto.
     */
    fun scartaSforamentiInCoda(
        eraCollegato: Boolean,
        serverPrima: String,
        serverDopo: String,
        stessoDispositivo: Boolean?,
    ): Boolean {
        if (!eraCollegato) return false
        if (!stessoServer(serverPrima, serverDopo)) return true
        return stessoDispositivo == false
    }

    /**
     * Il collegamento riuscito ha portato questo telefono su un dispositivo del
     * patto DIVERSO da quello di prima? Caso tipico: per un telefono che c'era
     * già, il genitore ha usato "Aggiungi un dispositivo" invece di "Nuovo
     * codice". Il collegamento resta valido; il telefono però lo dice chiaro,
     * invece di "Collegamento riuscito". null = niente da dire (primo
     * collegamento, stesso dispositivo, o il server non dice quale).
     *
     * Si confrontano i dispositivi, non gli indirizzi: lo stesso dispositivo
     * raggiunto con un altro indirizzo non è "un dispositivo nuovo". Un telefono
     * di cui non si è mai saputo il dispositivo (vecchio codice lungo) è il
     * dispositivo 1, come in [stessoDispositivo].
     *
     * [dispositiviDopo] sono i dispositivi del figlio nel patto letto subito
     * dopo il collegamento (vuoto se la lettura non è riuscita): sullo stesso
     * server dicono il nome di adesso di quello di prima e se il genitore l'ha
     * scollegato. Su un altro server lo stesso numero è un altro dispositivo, e
     * non si guardano.
     */
    fun cambioDispositivo(
        eraCollegato: Boolean,
        stessoServer: Boolean,
        prima: Dispositivo?,
        dopo: Dispositivo?,
        dispositiviDopo: List<Dispositivo> = emptyList(),
    ): CambioDispositivo? {
        if (!eraCollegato || dopo == null || dopo.id <= 0) return null
        val noto = prima?.takeIf { it.id > 0 }
        val idPrima = noto?.id ?: DISPOSITIVO_STORICO
        if (idPrima == dopo.id) return null
        val adesso = if (stessoServer) dispositiviDopo.firstOrNull { it.id == idPrima } else null
        return CambioDispositivo(
            nomeNuovo = dopo.nome.trim(),
            nomePrima = (adesso?.nome?.trim()?.takeIf { it.isNotEmpty() } ?: noto?.nome?.trim()).orEmpty(),
            primaScollegato = adesso?.revocato == true,
        )
    }

    /** Il dettaglio d'errore nelle due forme possibili, null se il corpo non è JSON. */
    private fun dettaglio(corpo: String?): DettaglioErrore? {
        if (corpo.isNullOrBlank()) return null
        val radice = runCatching { json.parseToJsonElement(corpo) }.getOrNull() as? JsonObject
            ?: return null
        val dentro = radice["detail"] as? JsonObject ?: radice
        return runCatching { json.decodeFromJsonElement(DettaglioErrore.serializer(), dentro) }.getOrNull()
    }

    private const val ERRORE_CODICE = "codice_non_valido"
    private const val ERRORE_TROPPI = "troppi_tentativi"
    private const val ERRORE_TIPO = "tipo_non_corrispondente"
    private val json = Json { ignoreUnknownKeys = true }
}
