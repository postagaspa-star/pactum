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

    /** A quell'indirizzo non c'è un server che conosca i codici (indirizzo sbagliato o server vecchio). */
    data object ServerSenzaCodici : EsitoAbbinamento

    /** Il server non si raggiunge. */
    data object SenzaRete : EsitoAbbinamento

    /** Qualsiasi altra cosa: si riprova. */
    data object Errore : EsitoAbbinamento
}

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
        if (serverPrima.trim().trimEnd('/') != serverDopo.trim().trimEnd('/')) return false
        return (idPrima?.takeIf { it > 0 } ?: storico) == idDopo
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
    private val json = Json { ignoreUnknownKeys = true }
}
