package eu.stgm.pactum.figlio.dati

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

// (0.10) Logica pura delle proposte del figlio (contratto v3.4, "le proposte
// del figlio"): il figlio continua a cambiare le sue regole da solo (stringere
// subito, allentare dopo 4 giorni) e in più può chiedere al genitore un cambio
// che, se il genitore accetta, vale subito. Niente Android: si prova con JUnit
// semplice. La rete la fa PostinoClient; qui si decide cosa mandare e cosa
// vuol dire la risposta.

/**
 * Un cambio a una regola: lo stesso oggetto per la modifica fatta da soli
 * (PATCH / DELETE) e per quella chiesta al genitore (POST /api/proposte). Così
 * "Chiedi al genitore", dopo il blocco dei 4 giorni, manda ESATTAMENTE il
 * cambio che il blocco ha fermato.
 */
sealed interface CambioRegola {
    val regolaId: Long

    /** Parametri nuovi, già controllati dal modulo della regola. */
    data class Modifica(override val regolaId: Long, val parametri: JsonObject) : CambioRegola

    /** Togliere la regola dal patto. */
    data class Eliminazione(override val regolaId: Long) : CambioRegola
}

/**
 * (0.10) Il blocco dei 4 giorni aperto su un cambio: il cambio fermato e
 * l'istante (epoch ms) da cui si potrà farlo da soli. Si conserva come testo
 * ([inTesto]: id della regola e parametri in JSON) attraverso una rotazione o
 * la morte del processo, così il dialogo e il perché scritto non si perdono.
 */
data class BloccoCambio(val cambio: CambioRegola, val sbloccoAlle: Long) {

    /** I secondi che mancano adesso (mai meno di zero). */
    fun secondiRimanenti(adesso: Long): Long = ((sbloccoAlle - adesso) / 1000).coerceAtLeast(0)

    fun inTesto(): String = buildJsonObject {
        put(CHIAVE_REGOLA, cambio.regolaId)
        put(CHIAVE_SBLOCCO, sbloccoAlle)
        when (cambio) {
            is CambioRegola.Modifica -> put(CHIAVE_PARAMETRI, cambio.parametri)
            is CambioRegola.Eliminazione -> put(CHIAVE_ELIMINA, true)
        }
    }.toString()

    companion object {
        /** Il blocco da [inTesto]; null se il testo non c'è o non si legge. */
        fun daTesto(testo: String?): BloccoCambio? {
            if (testo.isNullOrBlank()) return null
            val radice = runCatching { Json.parseToJsonElement(testo) }.getOrNull() as? JsonObject ?: return null
            val regolaId = (radice[CHIAVE_REGOLA] as? JsonPrimitive)?.longOrNull ?: return null
            val sblocco = (radice[CHIAVE_SBLOCCO] as? JsonPrimitive)?.longOrNull ?: return null
            val parametri = radice[CHIAVE_PARAMETRI] as? JsonObject
            val cambio = when {
                (radice[CHIAVE_ELIMINA] as? JsonPrimitive)?.booleanOrNull == true -> CambioRegola.Eliminazione(regolaId)
                parametri != null -> CambioRegola.Modifica(regolaId, parametri)
                else -> return null
            }
            return BloccoCambio(cambio, sblocco)
        }

        private const val CHIAVE_REGOLA = "regola_id"
        private const val CHIAVE_SBLOCCO = "sblocco_alle"
        private const val CHIAVE_PARAMETRI = "parametri"
        private const val CHIAVE_ELIMINA = "elimina"
    }
}

/** Com'è andata POST /api/proposte dal telefono, nei casi che il ragazzo deve distinguere. */
sealed interface EsitoProposta {
    /**
     * Arrivata al genitore. [confronto] = la frase del server ("+30 min al
     * giorno rispetto ad ora"), null se il corpo non la dice; [eliminazione] =
     * era la richiesta di togliere la regola.
     */
    data class Mandata(val confronto: String?, val eliminazione: Boolean) : EsitoProposta

    /** 409 `proposta_gia_pendente`: su questa regola aspetta già una proposta, di chiunque sia. */
    data object GiaPendente : EsitoProposta

    /**
     * 409 `proposta_gia_pendente`, e quella che aspetta è del figlio: di solito
     * una risposta persa per strada e un secondo tentativo. La proposta c'è.
     */
    data object GiaTua : EsitoProposta

    /** 409 `regola_non_valida`: la regola non c'è più o non è più attiva. */
    data object RegolaNonValida : EsitoProposta

    /** 409 `dispositivo_revocato`: la regola è di un dispositivo scollegato dal genitore. */
    data object DispositivoRevocato : EsitoProposta

    /** 422: qualche valore non va bene per questa regola. */
    data object ValoriNonValidi : EsitoProposta

    /**
     * 403: un server di prima della v3.4 risponde "ruolo sbagliato" a un
     * telefono che propone. Non è uno sbaglio del ragazzo: il server va aggiornato.
     */
    data object ServerDaAggiornare : EsitoProposta

    /** 401: questo telefono non è più collegato al patto. */
    data object Scollegato : EsitoProposta

    /** Il server non si raggiunge (o la risposta si è persa per strada). */
    data object SenzaRete : EsitoProposta

    /** Qualsiasi altra cosa: si riprova. */
    data object Errore : EsitoProposta
}

/** Com'è andata POST /api/proposte/{id}/ritira. */
sealed interface EsitoRitiro {
    data object Ritirata : EsitoRitiro

    /** 409 `proposta_non_pendente`: il genitore ha già deciso, o è già stata ritirata. */
    data object NonPiuPendente : EsitoRitiro

    /** 404 `proposta non trovata` di un server v3.4: la proposta non c'è più. */
    data object NonTrovata : EsitoRitiro

    /** 404 (la pagina non c'è) o 405: il server non conosce ancora il ritiro (prima della v3.4). */
    data object ServerDaAggiornare : EsitoRitiro

    /** 401: questo telefono non è più collegato al patto. */
    data object Scollegato : EsitoRitiro

    /** Niente rete o qualsiasi altra cosa: si riprova. */
    data object Errore : EsitoRitiro
}

object ProposteDelFiglio {

    /**
     * GET /api/proposte di tutti e due gli autori (contratto v3.4, "Dove si
     * vedono"): senza `?autori=tutti` un server v3.4 manda solo quelle del
     * genitore, per le app 0.8 e 0.9. Un server vecchio ignora il parametro.
     */
    const val PERCORSO_ELENCO = "/api/proposte?autori=tutti"

    /** Il marcatore dell'eliminazione nei parametri proposti (contratto, POST /api/proposte). */
    val MARCATORE_ELIMINA: JsonObject = buildJsonObject { put("azione", "elimina") }

    /**
     * Il corpo di POST /api/proposte per [cambio]: proprio quei parametri, o il
     * marcatore dell'eliminazione. Il perché viaggia solo se c'è davvero.
     */
    fun richiesta(cambio: CambioRegola, motivazione: String?): PropostaIn = PropostaIn(
        regolaId = cambio.regolaId,
        parametriProposti = when (cambio) {
            is CambioRegola.Modifica -> cambio.parametri
            is CambioRegola.Eliminazione -> MARCATORE_ELIMINA
        },
        motivazione = motivazione?.trim()?.ifEmpty { null },
    )

    /**
     * La risposta di POST /api/proposte tradotta in un esito. L'errore si legge
     * nella forma di FastAPI (`{"detail": {"errore": …}}`) e in quella del
     * contratto (`{"errore": …}`); senza, decide il codice HTTP. Un 403 qui vuol
     * dire solo "server vecchio": su un server v3.4 una regola che non è del
     * figlio risponde 409 `regola_non_valida`.
     */
    fun esito(cambio: CambioRegola, ok: Boolean, codiceHttp: Int, corpo: String?): EsitoProposta {
        val eliminazione = cambio is CambioRegola.Eliminazione
        if (ok) {
            val proposta = corpo?.let { runCatching { json.decodeFromString(Proposta.serializer(), it) }.getOrNull() }
            return EsitoProposta.Mandata(proposta?.confronto?.trim()?.ifEmpty { null }, eliminazione)
        }
        return when (errore(corpo)) {
            ERRORE_GIA_PENDENTE -> EsitoProposta.GiaPendente
            ERRORE_REGOLA -> EsitoProposta.RegolaNonValida
            ERRORE_REVOCATO -> EsitoProposta.DispositivoRevocato
            else -> when (codiceHttp) {
                422 -> EsitoProposta.ValoriNonValidi
                403 -> EsitoProposta.ServerDaAggiornare
                401 -> EsitoProposta.Scollegato
                0 -> EsitoProposta.SenzaRete
                else -> EsitoProposta.Errore
            }
        }
    }

    /**
     * La risposta di POST /api/proposte/{id}/ritira. Un server di prima della
     * v3.4 non ha la pagina del ritiro: 404 "Not Found" o 405, e va aggiornato.
     * Un server v3.4 risponde 404 "proposta non trovata" quando è la proposta a
     * non esserci più: lì non c'è niente da aggiornare.
     */
    fun esitoRitiro(ok: Boolean, codiceHttp: Int, corpo: String?): EsitoRitiro = when {
        ok -> EsitoRitiro.Ritirata
        errore(corpo) == ERRORE_NON_PENDENTE -> EsitoRitiro.NonPiuPendente
        codiceHttp == 404 && propostaNonTrovata(corpo) -> EsitoRitiro.NonTrovata
        codiceHttp == 404 || codiceHttp == 405 -> EsitoRitiro.ServerDaAggiornare
        codiceHttp == 401 -> EsitoRitiro.Scollegato
        else -> EsitoRitiro.Errore
    }

    /**
     * Il modulo della proposta si chiude solo quando la proposta è arrivata.
     * Ogni altro esito si dice DENTRO il modulo, dove il ragazzo sta guardando
     * (non in una snackbar sotto l'ombra del dialogo).
     */
    fun chiudeIlModulo(esito: EsitoProposta): Boolean = esito is EsitoProposta.Mandata

    /**
     * Rimandare la stessa proposta ha senso: un valore da correggere, la rete
     * che torna, un intoppo del server. Negli altri casi il pulsante si spegne
     * (già una in attesa, regola che non si può più cambiare, server da aggiornare).
     */
    fun riprovabile(esito: EsitoProposta): Boolean = when (esito) {
        EsitoProposta.ValoriNonValidi, EsitoProposta.SenzaRete, EsitoProposta.Errore -> true
        else -> false
    }

    /** Dopo l'esito le regole e le proposte vanno rilette: qualcosa è cambiato sul server. */
    fun serveRileggere(esito: EsitoProposta): Boolean = when (esito) {
        is EsitoProposta.Mandata,
        EsitoProposta.GiaPendente,
        EsitoProposta.GiaTua,
        EsitoProposta.RegolaNonValida,
        EsitoProposta.DispositivoRevocato,
        -> true
        else -> false
    }

    /**
     * Un 409 `proposta_gia_pendente` quando quella in attesa sulla regola del
     * [cambio] è del figlio stesso ([inviate], appena rilette): è [EsitoProposta.GiaTua].
     */
    fun precisaGiaPendente(esito: EsitoProposta, cambio: CambioRegola, inviate: List<Proposta>): EsitoProposta =
        if (esito == EsitoProposta.GiaPendente &&
            inviate.any { it.regolaId == cambio.regolaId && it.stato == StatiProposta.PENDENTE && it.delFiglio }
        ) {
            EsitoProposta.GiaTua
        } else {
            esito
        }

    /**
     * Le proposte a cui il FIGLIO deve rispondere: in attesa e del genitore.
     * Sono quelle di "Da decidere", del numero sulla scheda e delle regole
     * "occupate". GET /api/proposte ([proposte]) arriva al massimo a 50: le
     * pendenti del patto appena letto ([pendentiDelPatto], senza tetto) si
     * aggiungono, così una proposta vecchia ancora in attesa non sparisce.
     * Una per id, dalla più recente.
     */
    fun daDecidere(proposte: List<Proposta>, pendentiDelPatto: List<Proposta> = emptyList()): List<Proposta> =
        (proposte + pendentiDelPatto)
            .filter { it.stato == StatiProposta.PENDENTE && it.delGenitore }
            .distinctBy { it.id }
            .sortedByDescending { it.id }

    /**
     * Le proposte del figlio che aspettano il genitore: `proposte_inviate` del
     * patto appena letto; se il patto non è arrivato, le stesse prese da
     * GET /api/proposte ([proposte], tutte e due gli autori).
     */
    fun inviate(patto: Patto?, proposte: List<Proposta>): List<Proposta> =
        patto?.proposteInviate?.filter { it.stato == StatiProposta.PENDENTE }
            ?: proposte.filter { it.stato == StatiProposta.PENDENTE && it.delFiglio }

    /**
     * La proposta che aspetta su ciascuna regola, di chiunque sia (ce n'è al
     * massimo una per regola, contratto v3.4). Serve a dire sulla regola "c'è
     * già una proposta in attesa" invece di farla rifiutare dal server.
     */
    fun inAttesaPerRegola(vararg elenchi: List<Proposta>): Map<Long, Proposta> =
        elenchi.asSequence()
            .flatten()
            .filter { it.stato == StatiProposta.PENDENTE }
            .distinctBy { it.id }
            .groupBy { it.regolaId }
            .mapValues { (_, proposte) -> proposte.first() }

    /** Le proposte chiuse (di tutti e due), dalla più recente: la storia breve. */
    fun storia(proposte: List<Proposta>, massimo: Int = STORIA_MASSIMA): List<Proposta> =
        proposte.filter { it.stato != StatiProposta.PENDENTE }.take(massimo)

    /** Quante proposte chiuse si mostrano nella storia. */
    const val STORIA_MASSIMA = 10

    /** Il codice d'errore nelle due forme possibili, null se il corpo non lo dice. */
    private fun errore(corpo: String?): String? {
        if (corpo.isNullOrBlank()) return null
        val radice = runCatching { json.parseToJsonElement(corpo) }.getOrNull() as? JsonObject ?: return null
        val dentro = radice["detail"] as? JsonObject ?: radice
        return (dentro["errore"] as? JsonPrimitive)?.contentOrNull
    }

    /**
     * Il 404 di un server v3.4 sulla proposta che non c'è: `{"detail": "proposta
     * non trovata"}`. Quello di un server vecchio dice "Not Found".
     */
    private fun propostaNonTrovata(corpo: String?): Boolean {
        if (corpo.isNullOrBlank()) return false
        val radice = runCatching { json.parseToJsonElement(corpo) }.getOrNull() as? JsonObject ?: return false
        val dettaglio = (radice["detail"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
        return dettaglio == DETTAGLIO_NON_TROVATA || errore(corpo) == ERRORE_NON_TROVATA
    }

    private const val DETTAGLIO_NON_TROVATA = "proposta non trovata"
    private const val ERRORE_NON_TROVATA = "proposta_non_trovata"
    private const val ERRORE_GIA_PENDENTE = "proposta_gia_pendente"
    private const val ERRORE_REGOLA = "regola_non_valida"
    private const val ERRORE_REVOCATO = "dispositivo_revocato"
    private const val ERRORE_NON_PENDENTE = "proposta_non_pendente"
    private val json = Json { ignoreUnknownKeys = true }
}
