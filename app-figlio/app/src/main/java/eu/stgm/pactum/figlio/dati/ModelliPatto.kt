package eu.stgm.pactum.figlio.dati

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import eu.stgm.pactum.design.GiornoPatto
import eu.stgm.pactum.design.segnaleDaStato
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId

/**
 * Le forme del contratto per il patto completo (tappa 5, docs/contratto-api.md,
 * sezione "Endpoint del figlio"). I `ts_server` sono ISO 8601 UTC e fanno fede;
 * si mostrano nel fuso del telefono. Campi sconosciuti ignorati (tolleranza
 * evolutiva): l'app non si rompe se il server aggiunge chiavi.
 */

/** GET /api/patto — lo stato completo per il sync dell'app del figlio. */
@Serializable
data class Patto(
    val regole: List<Regola> = emptyList(),
    val bonus: StatoBonus? = null,
    // Chiave = regola_id come stringa (contratto): minuti bonus concessi OGGI.
    @SerialName("bonus_oggi_per_regola") val bonusOggiPerRegola: Map<String, Int> = emptyMap(),
    @SerialName("proposte_pendenti") val propostePendenti: List<Proposta> = emptyList(),
    @SerialName("dichiarazioni_in_attesa") val dichiarazioniInAttesa: List<Dichiarazione> = emptyList(),
    // (v2.3) La stessa identica lista che il genitore vede in GET /api/finestra:
    // è il principio della tavola rotonda: niente esiste nella finestra del
    // genitore che il figlio non veda identico. Vuota = server vecchio senza
    // la sezione (l'app la nasconde) oppure nessuna fotografia ancora arrivata.
    @SerialName("siti_recenti") val sitiRecenti: List<SitiGiorno> = emptyList(),
    // (v2.4) La striscia aggregata degli 8 giorni, IDENTICA a quella della
    // finestra del genitore (stessa funzione del server). È un fatto: la si
    // mostra così com'è. Serie e record si calcolano da qui, in locale, e non
    // tornano mai indietro. Vuota = server vecchio senza il campo.
    val striscia: List<GiornoStriscia> = emptyList(),
    // (v2.4) La riga sotto la striscia, IDENTICA a quella della finestra del
    // genitore: la conta il server nel fuso del patto. null = server vecchio
    // senza il campo, e allora la riga non si mostra.
    val riepilogo: Riepilogo? = null,
    val fuso: String? = null,
    // (v3) Di chi è questo telefono e come si chiama: "Collegato come:
    // Telefono di Andrea". null = server vecchio, che conosce un telefono solo.
    val figlio: Figlio? = null,
    val dispositivo: Dispositivo? = null,
    // (v3) La striscia di QUESTO telefono, identica a quella che il genitore
    // vede sulla sua scheda. `striscia` qui sopra resta quella del figlio.
    @SerialName("striscia_dispositivo") val strisciaDispositivo: List<GiornoStriscia> = emptyList(),
    // (v3) Tutti i dispositivi del figlio, ciascuno con la sua striscia: la
    // riga "Computer: 5 su 7" sotto la striscia del figlio.
    val dispositivi: List<Dispositivo> = emptyList(),
    // App-interno (NON dal server): il giorno del patto in cui `bonusOggiPerRegola`
    // è valido, stampato da PattoLocale al salvataggio. Se al momento della
    // valutazione non è più oggi (notte offline), i bonus di "oggi" non valgono.
    @SerialName("bonus_giorno_locale") val bonusGiornoLocale: String? = null,
    // App-interno (NON dal server): l'impronta del collegamento con cui questa
    // copia è stata letta (ConfigurazionePostino.impronta). Una lettura partita
    // col collegamento vecchio e arrivata dopo un nuovo abbinamento non deve
    // entrare nella copia locale: sarebbe il patto di un altro dispositivo.
    @SerialName("letto_con") val lettoCon: String? = null,
) {
    /** La striscia nel linguaggio del design system (core-design). */
    fun giorniPatto(): List<GiornoPatto> = striscia.inGiorniPatto()

    /**
     * (v3) Le regole che valgono su QUESTO telefono: le sue e quelle di vita
     * reale. Il server manda già solo queste; il filtro è una cintura in più,
     * perché una fascia oraria del computer valutata sul telefono diventerebbe
     * uno sforamento falso nel registro.
     */
    fun regoleDiQuestoDispositivo(): List<Regola> = regoleDelDispositivo(regole, dispositivo?.id)

    /** (v3) Dove sta questo telefono tra i dispositivi del figlio. */
    fun contestoDispositivi(): ContestoDispositivi =
        ContestoDispositivi(questo = dispositivo?.id?.takeIf { it > 0 }, dispositivi = dispositivi)

    /**
     * I bonus di oggi per regola, ma solo se la copia è stata sincronizzata OGGI
     * nel fuso del patto: dopo una notte offline il bonus di ieri non deve
     * allargare il limite di oggi (contratto: il bonus è del giorno).
     */
    fun bonusValidiOggi(now: Long = System.currentTimeMillis()): Map<String, Int> =
        if (copiaDiOggi(now)) bonusOggiPerRegola else emptyMap()

    /**
     * Quanti minuti di bonus restano oggi: il più piccolo dei due tetti. Null
     * se non si sa (server vecchio senza contatori, o copia di un altro giorno).
     */
    fun residuoBonusOggi(now: Long = System.currentTimeMillis()): Int? {
        val contatori = bonus ?: return null
        if (!copiaDiOggi(now)) return null
        return minOf(contatori.giorno.residui, contatori.settimana.residui)
    }

    /** La copia è stata sincronizzata oggi, nel fuso del patto (o non si sa quando). */
    private fun copiaDiOggi(now: Long): Boolean {
        val giornoPatto = Instant.ofEpochMilli(now).atZone(zonaPatto(fuso)).toLocalDate().toString()
        return bonusGiornoLocale == null || bonusGiornoLocale == giornoPatto
    }
}

/** (v2.4) Un giorno della striscia: `stato` ∈ verde · rosso · grigio. */
@Serializable
data class GiornoStriscia(val data: String = "", val stato: String = "")

/** Una striscia del contratto (`striscia` o `semaforo`) nel linguaggio di core-design. */
fun List<GiornoStriscia>.inGiorniPatto(): List<GiornoPatto> =
    map { GiornoPatto(it.data, segnaleDaStato(it.stato)) }

/**
 * (v2.4) Il `riepilogo` di GET /api/patto, identico a quello di GET
 * /api/finestra: i giorni `rosso` della striscia e le interruzioni nella
 * registrazione negli stessi 8 giorni.
 */
@Serializable
data class Riepilogo(
    @SerialName("giorni_fuori_regola") val giorniFuoriRegola: Int = 0,
    val interruzioni: Int = 0,
)

/**
 * Il fuso in cui contare i "giorni" del patto (bonus, dichiarazioni): quello
 * dato dal server (GET /api/patto, campo `fuso`), col ripiego sul fuso del
 * telefono se assente o ignoto. Il server resta la fonte di verità; questo
 * serve solo alla logica locale che deve dire "oggi" come lo direbbe il server.
 */
fun zonaPatto(fuso: String?): ZoneId =
    fuso?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

@Serializable
data class Regola(
    val id: Long,
    val tipo: String,
    val parametri: JsonObject = JsonObject(emptyMap()),
    val attiva: Boolean = true,
    @SerialName("creata_ts") val creataTs: String = "",
    @SerialName("ultima_modifica_ts") val ultimaModificaTs: String = "",
    @SerialName("allentabile_dal") val allentabileDal: String? = null,
    // (v2.4, solo in GET /api/patto) Gli 8 giorni di QUESTA regola, identici
    // a quelli che il genitore vede sulla sua scheda. Vuoto = server vecchio.
    val semaforo: List<GiornoStriscia> = emptyList(),
    // (v3) Di quale dispositivo è la regola: null = vita reale, che è del
    // figlio, oppure server vecchio. `dispositivo` porta anche nome e tipo, così
    // si scrive "sul computer" senza un'altra chiamata.
    @SerialName("dispositivo_id") val dispositivoId: Long? = null,
    val dispositivo: Dispositivo? = null,
    // Il nome leggibile del bersaglio, se il server lo manda (es. "Minecraft"
    // per `exe:minecraft.exe`). Assente quasi sempre: si ripiega da soli.
    val nome: String? = null,
) {
    /** Il dispositivo della regola, dal campo o dall'oggetto: null = del figlio. */
    val idDispositivo: Long?
        get() = dispositivoId ?: dispositivo?.id?.takeIf { it > 0 }
}

/**
 * (v3) Un dispositivo del figlio: `telefono` o `computer`. In `dispositivi` di
 * GET /api/patto porta anche la sua `striscia`; altrove solo id, nome e tipo.
 * Tutto con un valore di ripiego: un campo mancante non deve far cadere la
 * lettura dell'intero patto.
 */
@Serializable
data class Dispositivo(
    val id: Long = 0,
    val nome: String = "",
    val tipo: String = "",
    val striscia: List<GiornoStriscia> = emptyList(),
    val revocato: Boolean = false,
)

/** (v3) Il figlio a cui appartiene questo telefono. */
@Serializable
data class Figlio(val id: Long = 0, val nome: String = "")

object TipiDispositivo {
    const val TELEFONO = "telefono"
    const val COMPUTER = "computer"
}

/** (v3) Questo telefono (null = non si sa) e tutti i dispositivi del figlio. */
data class ContestoDispositivi(
    val questo: Long? = null,
    val dispositivi: List<Dispositivo> = emptyList(),
)

/**
 * Le regole che valgono sul dispositivo [questo]: le sue e quelle del figlio
 * (vita reale, `dispositivo_id` null). [questo] null = server vecchio, un
 * telefono solo: valgono tutte.
 */
fun regoleDelDispositivo(regole: List<Regola>, questo: Long?): List<Regola> =
    if (questo == null || questo <= 0) {
        regole
    } else {
        regole.filter { it.idDispositivo == null || it.idDispositivo == questo }
    }

object TipiRegola {
    const val LIMITE_TEMPO = "limite_tempo"
    const val FASCIA_ORARIA = "fascia_oraria"
    const val VITA_REALE = "vita_reale"
}

object StatiProposta {
    const val PENDENTE = "pendente"
    const val ACCETTATA = "accettata"
    const val RIFIUTATA = "rifiutata"
}

object DirezioniProposta {
    const val ALLENTA = "allenta"
    const val STRINGE = "stringe"
    const val ELIMINA = "elimina"
}

object EsitiRisposta {
    const val ACCETTA = "accetta"
    const val RIFIUTA = "rifiuta"
}

object StatiDichiarazione {
    const val REGISTRATA = "registrata"
    const val IN_ATTESA = "in_attesa"
    const val CONFERMATA = "confermata"
    const val CONFERMATA_PER_CONTO = "confermata_per_conto"
    const val RIBALTATA = "ribaltata"
}

object EsitiDichiarazione {
    const val SUCCESSO = "successo"
    const val FALLIMENTO = "fallimento"
}

object TipiNotifica {
    const val NUOVA_PROPOSTA = "nuova_proposta"
    const val VERDETTO = "verdetto"

    /** (v2.4) Il riconoscimento del genitore, a testo fisso. */
    const val SEGNO = "segno"
}

/**
 * (v2.3) Un giorno della lista dei siti, come lo calcola il server per
 * ENTRAMBE le app. `totaleDomini` **null** = nessuna fotografia per quel
 * giorno: "non è arrivato niente", che è diverso da "zero siti" — mai uno
 * zero finto. `dnsCifrato` = per un pezzo di giornata l'app non ha potuto
 * vedere (DoH/DoT): è un dato dichiarato, non un errore.
 */
@Serializable
data class SitiGiorno(
    val giorno: String = "",
    @SerialName("totale_domini") val totaleDomini: Int? = null,
    @SerialName("dns_cifrato") val dnsCifrato: Boolean = false,
    @SerialName("aggiornato_ts") val aggiornatoTs: String? = null,
    val domini: List<DominioVisite> = emptyList(),
)

@Serializable
data class DominioVisite(val dominio: String = "", val visite: Int = 0)

@Serializable
data class ContatoreBonus(val usati: Int = 0, val tetto: Int = 0, val residui: Int = 0)

@Serializable
data class StatoBonus(
    val giorno: ContatoreBonus = ContatoreBonus(),
    val settimana: ContatoreBonus = ContatoreBonus(),
)

@Serializable
data class Proposta(
    val id: Long,
    @SerialName("regola_id") val regolaId: Long,
    @SerialName("parametri_proposti") val parametriProposti: JsonObject? = null,
    val motivazione: String? = null,
    val confronto: String? = null,
    val direzione: String? = null,
    val stato: String,
    val usata: Boolean = false,
    @SerialName("ts_server") val tsServer: String = "",
    val risposta: RispostaProposta? = null,
)

@Serializable
data class RispostaProposta(
    val esito: String,
    val motivazione: String? = null,
    @SerialName("ts_server") val tsServer: String = "",
)

@Serializable
data class Dichiarazione(
    val id: Long,
    @SerialName("regola_id") val regolaId: Long,
    val giorno: String = "",
    val esito: String,
    val nota: String? = null,
    val stato: String,
    @SerialName("ts_server") val tsServer: String = "",
    val verdetto: VerdettoDichiarazione? = null,
)

@Serializable
data class VerdettoDichiarazione(
    val verdetto: String,
    val nota: String? = null,
    val registro: String? = null,
    @SerialName("ts_server") val tsServer: String = "",
)

@Serializable
data class Notifica(
    val id: Long,
    val tipo: String,
    val messaggio: String,
    val payload: JsonObject = JsonObject(emptyMap()),
    @SerialName("ts_server") val tsServer: String = "",
    // (v3) Il server manda a questo telefono solo le sue e quelle del figlio
    // (null): il filtro è suo, qui il campo si legge e basta.
    @SerialName("dispositivo_id") val dispositivoId: Long? = null,
)

/**
 * GET /api/versione (tappa 6, nessun auth): l'ultima versione disponibile di
 * ciascuna app. L'app confronta `versioneCode` col proprio BuildConfig.VERSION_CODE
 * e, se il server è più avanti, scarica `url` (relativo al base del server).
 */
@Serializable
data class InfoVersioni(
    val figlio: InfoVersione? = null,
    val genitore: InfoVersione? = null,
)

@Serializable
data class InfoVersione(
    @SerialName("versione_code") val versioneCode: Int = 0,
    @SerialName("versione_nome") val versioneNome: String = "",
    val url: String = "",
    val note: String? = null,
)

// Buste degli elenchi.
@Serializable
data class PaccoProposte(val proposte: List<Proposta> = emptyList())

/** (v3) GET /api/regole: le regole attive di TUTTO il figlio, di ogni dispositivo. */
@Serializable
data class PaccoRegole(val regole: List<Regola> = emptyList())

/**
 * (v3) POST /api/abbina: il codice di 6 cifre che il genitore ha generato.
 * (v3.1) `tipo` senza valore di ripiego, di proposito: il client non scrive i
 * valori uguali al ripiego (`encodeDefaults = false`), e il tipo deve partire
 * sempre. Lo mette Abbinamento.richiesta.
 */
@Serializable
data class AbbinaIn(
    val codice: String,
    val tipo: String,
    @SerialName("versione_app") val versioneApp: String,
)

/** (v3) La risposta all'abbinamento: il token si riceve UNA volta sola. */
@Serializable
data class AbbinaOut(
    val token: String = "",
    val dispositivo: Dispositivo? = null,
    val figlio: Figlio? = null,
)

@Serializable
data class PaccoDichiarazioni(val dichiarazioni: List<Dichiarazione> = emptyList())

@Serializable
data class PaccoNotifiche(val notifiche: List<Notifica> = emptyList())

// Corpi delle richieste (POST/PATCH del figlio).
@Serializable
data class CreaRegolaIn(val tipo: String, val parametri: JsonObject)

@Serializable
data class ModificaRegolaIn(
    val parametri: JsonObject,
    @SerialName("proposta_id") val propostaId: Long? = null,
)

@Serializable
data class BonusIn(
    val minuti: Int,
    @SerialName("regola_id") val regolaId: Long,
    val motivo: String? = null,
)

@Serializable
data class RispostaPropostaIn(val esito: String, val motivazione: String? = null)

@Serializable
data class DichiarazioneIn(
    @SerialName("regola_id") val regolaId: Long,
    val esito: String,
    val nota: String? = null,
    val giorno: String? = null,
)

/**
 * Il corpo d'errore del server (FastAPI HTTPException → {"detail": {...}}).
 * `detail` è un oggetto per i 409 che ci interessano (lock, tetti, ultima
 * regola); per i 422 è una lista e resta null qui (si mostra un messaggio
 * generico). Tutti i campi opzionali: si legge quello che serve caso per caso.
 */
@Serializable
data class RispostaErrore(val detail: DettaglioErrore? = null)

@Serializable
data class DettaglioErrore(
    val errore: String? = null,
    @SerialName("secondi_rimanenti") val secondiRimanenti: Long? = null,
    @SerialName("sblocco_ts") val sbloccoTs: String? = null,
    @SerialName("residuo_giorno") val residuoGiorno: Int? = null,
    @SerialName("residuo_settimana") val residuoSettimana: Int? = null,
    // (v3) 429 troppi_tentativi dell'abbinamento: fra quanto si può riprovare.
    @SerialName("riprova_tra_secondi") val riprovaTraSecondi: Long? = null,
    // (v3.1) 409 tipo_non_corrispondente dell'abbinamento: per che tipo di
    // dispositivo è il codice (es. "computer").
    @SerialName("tipo_atteso") val tipoAtteso: String? = null,
)

private val jsonErrori = Json { ignoreUnknownKeys = true }

/**
 * Il dettaglio d'errore da un corpo non-2xx, null se il corpo non è il 409
 * strutturato che ci interessa (es. la lista dei 422): chi chiama mostra
 * allora un messaggio generico.
 */
fun leggiDettaglioErrore(corpo: String?): DettaglioErrore? {
    if (corpo.isNullOrBlank()) return null
    return runCatching {
        jsonErrori.decodeFromString(RispostaErrore.serializer(), corpo).detail
    }.getOrNull()
}
