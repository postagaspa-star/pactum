package eu.stgm.pactum.figlio.faccende

import eu.stgm.pactum.figlio.sessione.LetturaSessioni
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

// (0.13) Le faccende del contratto v3.6: un genitore dà al figlio delle
// faccende di casa; da quando lo decide lui, e finché il figlio non ha mandato
// una foto per ognuna, il telefono è bloccato tranne poche app fondamentali.
// Qui: le forme del contratto, lette a mano e con pazienza come le Sessioni.
// Un campo scritto male non deve mai far cadere la lettura del patto, e una
// risposta che non si legge non deve MAI sbloccare il telefono.

object StatiFaccenda {
    const val DA_FARE = "da_fare"
    const val FATTA = "fatta"
    const val ANNULLATA = "annullata"
}

/** (0.13) I tipi di notifica delle faccende che arrivano al figlio (contratto v3.6). */
object TipiNotificaFaccende {
    const val NUOVE_FACCENDE = "nuove_faccende"
    const val FACCENDA_BOCCIATA = "faccenda_bocciata"
    const val FACCENDA_ANNULLATA = "faccenda_annullata"

    /** Destinati ai genitori: se arrivassero al figlio, si mostrano come una novità qualsiasi. */
    const val FACCENDA_FATTA = "faccenda_fatta"
    const val FACCENDE_FINITE = "faccende_finite"

    /**
     * (0.17, contratto v3.9) Il genitore ha cambiato un lavoro da fare (titolo,
     * nota, ora del blocco): il blocco si rilegge subito, l'ora può essere cambiata.
     */
    const val FACCENDA_MODIFICATA = "faccenda_modificata"

    /**
     * (0.17, v3.9) Il genitore ha confermato un lavoro fatto ("svolto").
     * (0.18, contratto v4.0) Confermare è approvare, e approvare SBLOCCA:
     * l'ultimo lavoro approvato toglie il blocco (payload `sblocca`).
     */
    const val FACCENDA_CONFERMATA = "faccenda_confermata"

    /**
     * Quelle che dicono che il blocco può essere cambiato: si richiede subito
     * lo stato. (0.18, v4.0) Anche `faccenda_confermata`: un lavoro approvato
     * può togliere il blocco, e il telefono non si sblocca mai da solo.
     */
    val CAMBIANO_IL_BLOCCO = setOf(
        NUOVE_FACCENDE,
        FACCENDA_BOCCIATA,
        FACCENDA_ANNULLATA,
        FACCENDA_FATTA,
        FACCENDE_FINITE,
        FACCENDA_MODIFICATA,
        FACCENDA_CONFERMATA,
    )

    /** Quelle del canale dei lavori di casa, che aprono la pagina Lavori. */
    val DEL_FIGLIO = setOf(NUOVE_FACCENDE, FACCENDA_BOCCIATA, FACCENDA_ANNULLATA, FACCENDA_MODIFICATA, FACCENDA_CONFERMATA)
}

/** L'ultima bocciatura di una foto: quando, la nota e chi (il nome del genitore). */
@Serializable
data class Bocciatura(val ts: Long? = null, val nota: String? = null, val da: String? = null)

/**
 * Una faccenda da fare, come la dice `GET /api/faccende/blocco` (`da_fare`):
 * quella che il telefono deve ricordare anche senza rete. [genitore] = il
 * nome di chi l'ha data; [bloccoDa] = da quando blocca (epoch ms).
 *
 * (0.18, contratto v4.0) `da_fare` del blocco porta TUTTI i lavori aperti:
 * quelli da fare ([stato] `da_fare`) e quelli con la foto che aspetta
 * l'approvazione di un genitore ([stato] `fatta`, [fotoIl] = l'ora della
 * foto). Un server di prima della v4.0 non manda `stato`: vale `da_fare`.
 */
@Serializable
data class FaccendaDaFare(
    val id: Long,
    val titolo: String,
    val nota: String? = null,
    val bloccoDa: Long? = null,
    val genitore: String? = null,
    val bocciature: Int = 0,
    val ultimaBocciatura: Bocciatura? = null,
    val stato: String = StatiFaccenda.DA_FARE,
    val fotoIl: Long? = null,
) {
    /** (0.18) La foto è arrivata e aspetta che un genitore la approvi: niente "Scatta la foto". */
    val aspettaApprovazione: Boolean get() = stato == StatiFaccenda.FATTA
}

/** Una faccenda intera (`GET /api/faccende`, `faccende` del patto): per la pagina Faccende. */
@Serializable
data class FaccendaLocale(
    val id: Long,
    val titolo: String,
    val nota: String? = null,
    val stato: String = "",
    val bloccoDa: Long? = null,
    val creataIl: Long? = null,
    val genitore: String? = null,
    val fotoIl: Long? = null,
    val foto: Boolean = false,
    val bocciature: Int = 0,
    val ultimaBocciatura: Bocciatura? = null,
    val chiusaIl: Long? = null,
    val annullataDa: String? = null,
    /** (0.17, contratto v3.9) Il genitore l'ha confermata ("svolto"): quando e chi. */
    val confermataIl: Long? = null,
    val confermataDa: String? = null,
    /**
     * (0.18, contratto v4.0) Fatta, con la foto che aspetta l'approvazione di
     * un genitore: è ancora un lavoro APERTO (blocca dal suo `blocco_da`).
     */
    val daApprovare: Boolean = false,
) {
    val confermata: Boolean get() = confermataIl != null

    /** (0.18) Aperta: da fare, o fatta con la foto che aspetta l'approvazione. */
    val aperta: Boolean get() = stato == StatiFaccenda.DA_FARE || (stato == StatiFaccenda.FATTA && daApprovare)
    val daFare: Boolean get() = stato == StatiFaccenda.DA_FARE
    val fatta: Boolean get() = stato == StatiFaccenda.FATTA
    val annullata: Boolean get() = stato == StatiFaccenda.ANNULLATA
}

/** (0.17, v3.9) I lavori trovati da una ricerca; [altre] = ce ne sono più di 50 (null = server vecchio). */
data class RisultatiRicerca(val faccende: List<FaccendaLocale>, val altre: Boolean?)

/**
 * `GET /api/faccende/blocco` letto. [attivo] = c'è almeno una faccenda da
 * fare che blocca già; [dal] = da quando; [prossimo] = se non è attivo, quando
 * parte il prossimo blocco (anche senza rete); [daFare] = tutte le da fare.
 *
 * (0.18, contratto v4.0) [daFare] = tutti i lavori aperti (anche quelli che
 * aspettano l'approvazione); [rimandato] = il blocco è dovuto ma aspetta la
 * fine della Sessione Studio; [studio] = lo Studio in corso per il server;
 * [approvazione] = il server è dalla v4.0 (il blocco porta `rimandato`):
 * i lavori si sbloccano con l'approvazione, non con la foto.
 */
data class BloccoDalServer(
    val attivo: Boolean,
    val dal: Long?,
    val prossimo: Long?,
    val daFare: List<FaccendaDaFare>,
    val rimandato: Boolean = false,
    val approvazione: Boolean = false,
    val studio: StudioNelBlocco? = null,
)

/** (0.18, contratto v4.0) Lo `studio` del blocco: `{ "in_corso", "id", "inizio_ts" }`. */
data class StudioNelBlocco(val inCorso: Boolean, val id: Long?, val inizio: Long?)

/**
 * La lettura delle forme del contratto (logica pura). Tutto ciò che non si
 * legge diventa null o resta fuori: mai un'eccezione verso chi chiama. Un
 * blocco senza `attivo` leggibile non è una risposta: non cambia niente.
 */
object LetturaFaccende {

    /** Il blocco da un elemento JSON (`blocco` del patto); null se non si legge. */
    fun blocco(elemento: JsonElement?): BloccoDalServer? {
        val o = elemento as? JsonObject ?: return null
        val attivo = (o["attivo"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: return null
        val rimandato = (o["rimandato"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        return BloccoDalServer(
            attivo = attivo,
            dal = LetturaSessioni.istante(o["dal"]),
            prossimo = LetturaSessioni.istante(o["prossimo"]),
            daFare = (o["da_fare"] as? JsonArray)?.mapNotNull { daFare(it) }.orEmpty(),
            // (0.18, v4.0) Solo un `true` vero rimanda; il campo c'è = server v4.0.
            rimandato = rimandato == true,
            approvazione = rimandato != null || o.containsKey("studio"),
            studio = studioNelBlocco(o["studio"]),
        )
    }

    /** (0.18) Lo `studio` del blocco; null se manca o non si legge. */
    private fun studioNelBlocco(elemento: JsonElement?): StudioNelBlocco? {
        val o = elemento as? JsonObject ?: return null
        val inCorso = (o["in_corso"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: return null
        return StudioNelBlocco(inCorso, intero(o["id"])?.takeIf { it > 0 }, LetturaSessioni.istante(o["inizio_ts"]))
    }

    /** Il blocco dal corpo di `GET /api/faccende/blocco`. */
    fun bloccoDaCorpo(corpo: String?): BloccoDalServer? = blocco(LetturaSessioni.albero(corpo))

    fun daFare(elemento: JsonElement?): FaccendaDaFare? {
        val o = elemento as? JsonObject ?: return null
        val id = intero(o["id"])?.takeIf { it > 0 } ?: return null
        return FaccendaDaFare(
            id = id,
            titolo = testo(o["titolo"]).orEmpty(),
            nota = testo(o["nota"])?.takeIf { it.isNotBlank() },
            bloccoDa = LetturaSessioni.istante(o["blocco_da"]),
            genitore = nomeGenitore(o["creata_da"]),
            bocciature = intero(o["bocciature"])?.toInt()?.coerceAtLeast(0) ?: 0,
            ultimaBocciatura = bocciatura(o["ultima_bocciatura"]),
            // (0.18, v4.0) Un lavoro con la foto che aspetta l'approvazione. Uno
            // stato che non si conosce vale "da fare": mai un lavoro aperto perso.
            stato = if (testo(o["stato"])?.trim()?.lowercase() == StatiFaccenda.FATTA) StatiFaccenda.FATTA else StatiFaccenda.DA_FARE,
            fotoIl = LetturaSessioni.istante(o["foto_ts"]),
        )
    }

    fun faccenda(elemento: JsonElement?): FaccendaLocale? {
        val o = elemento as? JsonObject ?: return null
        val id = intero(o["id"])?.takeIf { it > 0 } ?: return null
        return FaccendaLocale(
            id = id,
            titolo = testo(o["titolo"]).orEmpty(),
            nota = testo(o["nota"])?.takeIf { it.isNotBlank() },
            stato = testo(o["stato"])?.trim()?.lowercase().orEmpty(),
            bloccoDa = LetturaSessioni.istante(o["blocco_da"]),
            creataIl = LetturaSessioni.istante(o["creata_ts"]),
            genitore = nomeGenitore(o["creata_da"]),
            fotoIl = LetturaSessioni.istante(o["foto_ts"]),
            foto = (o["foto"] as? JsonPrimitive)?.booleanOrNull ?: false,
            bocciature = intero(o["bocciature"])?.toInt()?.coerceAtLeast(0) ?: 0,
            ultimaBocciatura = bocciatura(o["ultima_bocciatura"]),
            chiusaIl = LetturaSessioni.istante(o["chiusa_ts"]),
            annullataDa = nomeGenitore(o["annullata_da"]),
            confermataIl = LetturaSessioni.istante(o["confermata_ts"]),
            confermataDa = nomeGenitore(o["confermata_da"]),
            daApprovare = (o["da_approvare"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true,
        )
    }

    /** Le faccende da un array (`faccende` del patto); null se l'elemento non è un array. */
    fun faccende(elemento: JsonElement?): List<FaccendaLocale>? =
        (elemento as? JsonArray)?.mapNotNull { faccenda(it) }

    /** Le faccende dal corpo di `GET /api/faccende`: `{ "faccende": [ … ] }`; null se non si legge. */
    fun elenco(corpo: String?): List<FaccendaLocale>? = when (val radice = LetturaSessioni.albero(corpo)) {
        is JsonObject -> faccende(radice["faccende"])
        is JsonArray -> faccende(radice)
        else -> null
    }

    /**
     * (0.17, contratto v3.9) La risposta di `GET /api/faccende?cerca=…`:
     * `{ "faccende": [ … ], "altre": true|false }`. [RisultatiRicerca.altre]
     * null = la risposta non ha `altre`: un server di prima della v3.9, che
     * ignora `cerca` e manda gli ultimi 30 giorni (non è una ricerca). Null se
     * il corpo non si legge.
     */
    fun ricerca(corpo: String?): RisultatiRicerca? {
        val radice = LetturaSessioni.albero(corpo) as? JsonObject ?: return null
        val faccende = faccende(radice["faccende"]) ?: return null
        val altre = (radice["altre"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        return RisultatiRicerca(faccende, altre)
    }

    /** La faccenda dal corpo di una risposta (la foto appena mandata). */
    fun faccendaDaCorpo(corpo: String?): FaccendaLocale? = faccenda(LetturaSessioni.albero(corpo))

    /** Il nome di un genitore: `{ "id", "nome" }` (o, per tolleranza, il nome scritto e basta). */
    fun nomeGenitore(elemento: JsonElement?): String? = when (elemento) {
        is JsonObject -> testo(elemento["nome"])
        is JsonPrimitive -> elemento.takeIf { it.isString }?.contentOrNull
        else -> null
    }?.trim()?.takeIf { it.isNotEmpty() }

    private fun bocciatura(elemento: JsonElement?): Bocciatura? {
        val o = elemento as? JsonObject ?: return null
        return Bocciatura(
            ts = LetturaSessioni.istante(o["ts"]),
            nota = testo(o["nota"])?.trim()?.takeIf { it.isNotEmpty() },
            da = nomeGenitore(o["da"]),
        )
    }

    private fun testo(elemento: JsonElement?): String? = (elemento as? JsonPrimitive)?.contentOrNull

    private fun intero(elemento: JsonElement?): Long? {
        val p = elemento as? JsonPrimitive ?: return null
        return p.longOrNull ?: p.contentOrNull?.trim()?.toLongOrNull()
    }
}

/**
 * (0.13) Com'è andato l'invio di una foto (`PUT /api/faccende/{id}/foto`).
 * Ogni no che non dipende dalla foto (rete, server, collegamento) la tiene in
 * coda; solo il server che dice "non serve più" la lascia andare, e solo una
 * foto rifiutata per com'è chiede di scattarla di nuovo.
 */
enum class EsitoFoto {
    /** 200: arrivata (anche la stessa foto mandata due volte). */
    ARRIVATA,

    /** 409 `non_da_fare`, o la faccenda non c'è più: non serve più mandarla. */
    NON_SERVE,

    /**
     * 409 `bocciata_nel_frattempo`: la faccenda è stata bocciata dopo lo
     * scatto. La foto non vale più: fuori dalla coda, e ne va scattata un'altra.
     */
    BOCCIATA_NEL_FRATTEMPO,

    /** 413 o 422: la foto così non va (troppo grande, non è un JPEG): va scattata di nuovo. */
    RIFIUTATA,

    /** 404 "Not Found" o 405: il server non conosce le faccende. Si tiene e si riprova più tardi. */
    SERVER_VECCHIO,

    /** 401, o 409 `dispositivo_revocato`: si tiene finché il telefono torna collegato. */
    SCOLLEGATO,

    SENZA_RETE,

    /** Qualunque altro no (5xx, un proxy): si tiene e si riprova. */
    ERRORE,
}

/**
 * (0.13) Le risposte del server sulle faccende (logica pura). L'errore si
 * legge nelle due forme, `{"detail": {"errore": …}}` e `{"errore": …}`.
 */
object EsitiFaccende {

    /** `GET /api/faccende` o `…/blocco`: 404 o 405 = il server non conosce le faccende (prima della v3.6). */
    fun serverVecchio(codiceHttp: Int): Boolean = codiceHttp == 404 || codiceHttp == 405

    fun foto(ok: Boolean, codiceHttp: Int, corpo: String?): EsitoFoto {
        if (ok) return EsitoFoto.ARRIVATA
        val errore = errore(corpo)
        return when {
            errore == "non_da_fare" -> EsitoFoto.NON_SERVE
            errore == "bocciata_nel_frattempo" -> EsitoFoto.BOCCIATA_NEL_FRATTEMPO
            errore == "dispositivo_revocato" -> EsitoFoto.SCOLLEGATO
            codiceHttp == 413 -> EsitoFoto.RIFIUTATA
            codiceHttp == 422 -> EsitoFoto.RIFIUTATA
            codiceHttp == 405 -> EsitoFoto.SERVER_VECCHIO
            // Il 404 di un server v3.6 è JSON e dice "faccenda non trovata";
            // il "Not Found" generico (o una pagina che non è JSON) vuol dire
            // che la strada non c'è: server vecchio.
            codiceHttp == 404 -> if (nonTrovata(corpo)) EsitoFoto.NON_SERVE else EsitoFoto.SERVER_VECCHIO
            codiceHttp == 401 -> EsitoFoto.SCOLLEGATO
            codiceHttp == 0 -> EsitoFoto.SENZA_RETE
            else -> EsitoFoto.ERRORE
        }
    }

    /** Il codice d'errore nelle due forme possibili, null se il corpo non lo dice. */
    fun errore(corpo: String?): String? {
        val radice = LetturaSessioni.albero(corpo) as? JsonObject ?: return null
        val dentro = radice["detail"] as? JsonObject ?: radice
        return (dentro["errore"] as? JsonPrimitive)?.contentOrNull?.trim()
    }

    /** Un 404 JSON che parla di una faccenda (o di una foto) che non c'è: non il "Not Found" generico. */
    private fun nonTrovata(corpo: String?): Boolean {
        val radice = LetturaSessioni.albero(corpo) as? JsonObject ?: return false
        val dettaglio = (radice["detail"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() ?: return false
        return dettaglio != "not found" && "faccenda" in dettaglio
    }
}
