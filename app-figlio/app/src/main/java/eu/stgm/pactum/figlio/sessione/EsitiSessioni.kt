package eu.stgm.pactum.figlio.sessione

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** (0.11) Com'è andata la creazione, il cambio o l'eliminazione di una sessione. */
sealed interface EsitoSessione {
    /** Arrivata: [sessione] = quella che torna dal server, se si legge. */
    data class Fatta(val sessione: SessioneDefinita?) : EsitoSessione

    /** 409 `nome_gia_usato`. */
    data object NomeGiaUsato : EsitoSessione

    /** 409 `troppe_sessioni`: al massimo 20 sessioni per telefono. */
    data object TroppeSessioni : EsitoSessione

    /** 409 `sessione_in_corso`: non si elimina la sessione in corso. */
    data object InCorso : EsitoSessione

    /** 404 di un server v3.5: la sessione non c'è più. */
    data object NonTrovata : EsitoSessione

    /** 422: qualche valore non va (nome, app). */
    data object ValoriNonValidi : EsitoSessione

    /** 404 "Not Found" o 405: il server non conosce le sessioni (prima della v3.5). */
    data object ServerDaAggiornare : EsitoSessione

    /** 401, o 409 `dispositivo_revocato`: questo telefono non è più collegato. */
    data object Scollegato : EsitoSessione

    data object SenzaRete : EsitoSessione

    data object Errore : EsitoSessione
}

/** (0.11) Com'è andato "Inizia" (POST /api/sessioni/{id}/avvia). */
sealed interface EsitoAvvio {
    /** Iniziata. [svolta] = la sessione svolta, se si legge. */
    data class Avviata(val svolta: SvoltaLocale?) : EsitoAvvio

    /** 409 `sessione_non_approvata`. */
    data object NonApprovata : EsitoAvvio

    /**
     * (0.13) 409 `blocco_faccende`, o il telefono già bloccato dalle faccende:
     * con il blocco una sessione non si avvia (contratto v3.6).
     */
    data object BloccoFaccende : EsitoAvvio

    /**
     * (0.18, contratto v4.0) 409 `studio_in_corso`, o il telefono già in
     * Sessione Studio: durante lo Studio le sessioni non si avviano.
     */
    data object StudioInCorso : EsitoAvvio

    /** 409 `sessione_gia_in_corso`. [svolta] = quella in corso, se si è ritrovata: la sua fine vera. */
    data class GiaInCorso(val svolta: SvoltaLocale? = null) : EsitoAvvio

    /** 404 di un server v3.5: la sessione non c'è più. */
    data object NonTrovata : EsitoAvvio

    /** 422: la durata non va (da 1 a 1440 minuti). */
    data object DurataNonValida : EsitoAvvio

    data object ServerDaAggiornare : EsitoAvvio

    /** 401, o 409 `dispositivo_revocato`. */
    data object Scollegato : EsitoAvvio

    /** La richiesta non è partita (niente rete): la sessione NON è iniziata, e lo si dice chiaro. */
    data object SenzaRete : EsitoAvvio

    /**
     * La richiesta è partita ma la risposta no (rete caduta, server lento):
     * non si sa se la sessione è partita. Si ricontrolla appena c'è rete.
     */
    data object Incerto : EsitoAvvio

    data object Errore : EsitoAvvio
}

/** (0.11) Com'è andata la consegna di "Termina la sessione". */
sealed interface EsitoTermina {
    /** Il server l'ha chiusa: [svolta] = la sessione chiusa, se si legge. */
    data class Terminata(val svolta: SessioneSvolta?) : EsitoTermina

    /** 404: per il server quella sessione non è in corso. Niente da consegnare: si lascia andare. */
    data object NienteInCorso : EsitoTermina

    /** 401: si tiene e si riprova quando il telefono torna collegato. */
    data object Scollegato : EsitoTermina

    /** La rete non c'è: si tiene e si riprova. */
    data object SenzaRete : EsitoTermina

    /** Il server (o qualcosa davanti a lui) ha detto di no per un altro motivo: si tiene e si riprova. */
    data object ErroreServer : EsitoTermina
}

/**
 * (0.11) Le risposte del server sulle sessioni tradotte in esiti (logica
 * pura). L'errore si legge nella forma di FastAPI (`{"detail": {"errore": …}}`)
 * e in quella del contratto (`{"errore": …}`). Un server di prima della v3.5
 * risponde 404 "Not Found" (la pagina non c'è) o 405: va aggiornato, non è
 * "un errore" (contratto v3.5, Compatibilità).
 */
object EsitiSessioni {

    /** Le risposte dei proxy quando il server ha forse già fatto il lavoro (gateway in ritardo). */
    private val FORSE_ARRIVATA = setOf(502, 504, 524)

    /** GET /api/sessioni: 404 o 405 vogliono dire solo "server da aggiornare". */
    fun elencoDaServerVecchio(codiceHttp: Int): Boolean = codiceHttp == 404 || codiceHttp == 405

    fun sessione(ok: Boolean, codiceHttp: Int, corpo: String?): EsitoSessione {
        if (ok) return EsitoSessione.Fatta(LetturaSessioni.definitaDaCorpo(corpo))
        return when (errore(corpo)) {
            "nome_gia_usato" -> EsitoSessione.NomeGiaUsato
            "troppe_sessioni" -> EsitoSessione.TroppeSessioni
            "sessione_in_corso" -> EsitoSessione.InCorso
            "dispositivo_revocato" -> EsitoSessione.Scollegato
            else -> when {
                serverVecchio(codiceHttp, corpo) -> EsitoSessione.ServerDaAggiornare
                codiceHttp == 404 -> EsitoSessione.NonTrovata
                codiceHttp == 422 -> EsitoSessione.ValoriNonValidi
                codiceHttp == 401 -> EsitoSessione.Scollegato
                codiceHttp == 0 -> EsitoSessione.SenzaRete
                else -> EsitoSessione.Errore
            }
        }
    }

    /**
     * [incerta] = la richiesta è partita e la risposta si è persa (codice 0 dopo
     * l'invio): non si sa se è iniziata. Lo stesso per i gateway in ritardo.
     */
    fun avvio(ok: Boolean, codiceHttp: Int, corpo: String?, incerta: Boolean = false): EsitoAvvio {
        if (ok) return EsitoAvvio.Avviata(LetturaSessioni.svoltaDaCorpo(corpo)?.inLocale())
        return when (errore(corpo)) {
            "sessione_non_approvata" -> EsitoAvvio.NonApprovata
            "sessione_gia_in_corso" -> EsitoAvvio.GiaInCorso()
            "blocco_faccende" -> EsitoAvvio.BloccoFaccende
            "studio_in_corso" -> EsitoAvvio.StudioInCorso
            "dispositivo_revocato" -> EsitoAvvio.Scollegato
            else -> when {
                codiceHttp == 0 -> if (incerta) EsitoAvvio.Incerto else EsitoAvvio.SenzaRete
                codiceHttp in FORSE_ARRIVATA -> EsitoAvvio.Incerto
                serverVecchio(codiceHttp, corpo) -> EsitoAvvio.ServerDaAggiornare
                codiceHttp == 404 -> EsitoAvvio.NonTrovata
                codiceHttp == 422 -> EsitoAvvio.DurataNonValida
                codiceHttp == 401 -> EsitoAvvio.Scollegato
                else -> EsitoAvvio.Errore
            }
        }
    }

    /**
     * Si lascia andare una chiusura solo quando il server dice che quella
     * sessione non è in corso: 404, o un 409 che lo dice. Tutto il resto (rete,
     * 5xx, un 400 o un 403 di un proxy davanti al server) si tiene e si riprova:
     * il genitore deve vedere che è stata chiusa prima.
     */
    fun termina(ok: Boolean, codiceHttp: Int, corpo: String?): EsitoTermina = when {
        ok -> EsitoTermina.Terminata(LetturaSessioni.svoltaDaCorpo(corpo))
        codiceHttp == 404 -> EsitoTermina.NienteInCorso
        codiceHttp == 409 && nonInCorso(errore(corpo)) -> EsitoTermina.NienteInCorso
        codiceHttp == 401 -> EsitoTermina.Scollegato
        codiceHttp == 0 -> EsitoTermina.SenzaRete
        else -> EsitoTermina.ErroreServer
    }

    /** Un 409 che dice "non c'è niente in corso", in qualsiasi forma ragionevole. */
    private fun nonInCorso(errore: String?): Boolean {
        val e = errore?.lowercase() ?: return false
        return "non_in_corso" in e || "nessuna" in e || "niente_in_corso" in e || "non_aperta" in e
    }

    /**
     * La pagina non c'è: 405, oppure 404 con il "Not Found" di FastAPI, senza
     * corpo o con una pagina che non è JSON (un proxy davanti al server). Il
     * 404 di un server v3.5 su una sessione che non c'è è JSON e dice altro:
     * quello vuol dire "non trovata".
     */
    fun serverVecchio(codiceHttp: Int, corpo: String?): Boolean {
        if (codiceHttp == 405) return true
        if (codiceHttp != 404) return false
        val radice = LetturaSessioni.albero(corpo) as? JsonObject ?: return true
        val dettaglio = (radice["detail"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
        return dettaglio == "not found"
    }

    /** Il codice d'errore nelle due forme possibili, null se il corpo non lo dice. */
    fun errore(corpo: String?): String? {
        val radice = LetturaSessioni.albero(corpo) as? JsonObject ?: return null
        val dentro = radice["detail"] as? JsonObject ?: radice
        return (dentro["errore"] as? JsonPrimitive)?.contentOrNull
    }
}
