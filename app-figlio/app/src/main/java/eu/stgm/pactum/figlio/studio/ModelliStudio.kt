package eu.stgm.pactum.figlio.studio

import eu.stgm.pactum.figlio.faccende.EsitiFaccende
import eu.stgm.pactum.figlio.faccende.LetturaFaccende
import eu.stgm.pactum.figlio.sessione.LetturaSessioni
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

// (0.18, contratto v4.0, parte C) La Sessione Studio: dal lunedì al venerdì
// alle 15:00 parte da sola, anche senza rete; il telefono lascia usare solo
// le app della lista approvata (più quelle sempre usabili); si chiude solo
// dopo le 16:00 e dopo almeno un'ora di attività cronometrata col timer, e
// chiudendo il figlio scrive cosa ha fatto. Qui: le forme del contratto,
// lette con pazienza come le Sessioni e i lavori di casa. Un campo scritto
// male non fa mai cadere la lettura del patto.

/** I tipi di un tratto di attività. */
object TipiTratto {
    const val COMPITI = "compiti"
    const val LAVORI_DI_CASA = "lavori_di_casa"

    /** Un'attività lontana dai dispositivi, con una parola (es. «allenamento»). */
    const val ALTRO = "altro"

    val TUTTI = listOf(COMPITI, LAVORI_DI_CASA, ALTRO)
}

/** L'esito di un tratto: `in_corso` passa una volta sola a `finito` o `interrotto`. */
object EsitiTratto {
    const val IN_CORSO = "in_corso"
    const val FINITO = "finito"

    /** Chiuso da un riavvio del telefono o dalla mezzanotte: conta lo stesso, fino a lì. */
    const val INTERROTTO = "interrotto"
}

object StatiConfigStudio {
    const val NESSUNA = "nessuna"
    const val IN_ATTESA = "in_attesa"
    const val APPROVATA = "approvata"
    const val RIFIUTATA = "rifiutata"
}

object ChiusureStudio {
    const val FIGLIO = "figlio"
    const val GENITORE = "genitore"

    /** Chiuso da solo a mezzanotte. */
    const val NON_CHIUSO = "non_chiuso"
}

object OriginiStudio {
    const val AUTOMATICA = "automatica"
    const val MANUALE = "manuale"
}

/** (0.18) Le notifiche dello Studio (contratto v4.0, «Notifiche»). */
object TipiNotificaStudio {
    /** Ai genitori: se arrivassero al figlio, si mostrano col messaggio del server. */
    const val STUDIO_DA_APPROVARE = "studio_da_approvare"
    const val STUDIO_NON_PARTITO = "studio_non_partito"
    const val STUDIO_INIZIATO = "studio_iniziato"
    const val STUDIO_NON_CHIUSO = "studio_non_chiuso"

    /** Al figlio: un genitore ha deciso sulla configurazione. */
    const val STUDIO_RISPOSTA = "studio_risposta"

    /** Al figlio: un genitore ha chiuso lo Studio (col motivo). */
    const val STUDIO_CHIUSO = "studio_chiuso"

    /** Quelle che cambiano lo Studio: si rilegge subito `GET /api/studio`. */
    val CAMBIANO_LO_STUDIO = setOf(STUDIO_CHIUSO, STUDIO_RISPOSTA)

    val TUTTI = setOf(STUDIO_DA_APPROVARE, STUDIO_NON_PARTITO, STUDIO_INIZIATO, STUDIO_NON_CHIUSO, STUDIO_RISPOSTA, STUDIO_CHIUSO)
}

/** I giorni del contratto, nell'ordine della settimana. */
val GIORNI_STUDIO = listOf("lun", "mar", "mer", "gio", "ven", "sab", "dom")

/** I valori di partenza della prima proposta (contratto v4.0). */
val GIORNI_FERIALI = listOf("lun", "mar", "mer", "gio", "ven")

/**
 * Il contenuto di una configurazione dello Studio (approvata, proposta, o
 * una versione): gli orari, il minimo di minuti e la lista delle app del
 * telefono. Le liste del computer si tengono solo per mostrarle.
 */
@Serializable
data class ContenutoStudio(
    val giorni: List<String> = GIORNI_FERIALI,
    val inizio: String = "15:00",
    val chiusuraMinima: String = "16:00",
    val minutiMinimi: Int = 60,
    val app: List<String> = emptyList(),
    val nomi: Map<String, String> = emptyMap(),
    val programmi: List<String> = emptyList(),
    val nomiProgrammi: Map<String, String> = emptyMap(),
    /** Il giorno (fuso del patto) da cui valgono questi orari. */
    val orariDal: String? = null,
    val approvataIl: Long? = null,
    /** Chi l'ha approvata (null = decisa dalla famiglia all'aggiornamento). */
    val decisaDa: String? = null,
    /** Per una proposta: quando e da quale dispositivo. */
    val richiestaIl: Long? = null,
    val da: String? = null,
    /** Per una versione dello storico. */
    val versione: Int? = null,
) {
    /** Gli stessi orari (giorni, inizio, chiusura, minimo). */
    fun stessiOrari(altro: ContenutoStudio): Boolean =
        giorni.toSet() == altro.giorni.toSet() && inizio == altro.inizio &&
            chiusuraMinima == altro.chiusuraMinima && minutiMinimi == altro.minutiMinimi

    /** La stessa lista del telefono (anche in un altro ordine). */
    fun stessaLista(altro: ContenutoStudio): Boolean = app.toSet() == altro.app.toSet()
}

/** La configurazione dello Studio di questo figlio. */
@Serializable
data class ConfigStudio(
    val stato: String = StatiConfigStudio.NESSUNA,
    val versione: Int = 0,
    val approvata: ContenutoStudio? = null,
    val inAttesa: ContenutoStudio? = null,
    val motivazione: String? = null,
)

/** Una partenza automatica (`prossime_partenze`): l'inizio e le condizioni di chiusura di quel giorno. */
@Serializable
data class PartenzaStudio(
    val giorno: String,
    val inizio: Long,
    val chiudibileDal: Long? = null,
    val minutiMinimi: Int = 60,
)

/** Un tratto come lo dice il server (dentro uno Studio svolto). */
@Serializable
data class TrattoDelServer(
    val id: String,
    val dispositivoId: Long? = null,
    val tipo: String = TipiTratto.COMPITI,
    val parola: String? = null,
    val faccendaId: Long? = null,
    val inizio: Long? = null,
    val fine: Long? = null,
    val oraAgganciata: Boolean = true,
    val secondi: Long = 0,
    val secondiContati: Long? = null,
    val minuti: Int? = null,
    val esito: String = EsitiTratto.FINITO,
    val conta: Boolean? = null,
)

/** Uno Studio svolto (o in corso) come lo dice il server. */
@Serializable
data class StudioSvolto(
    val id: Long,
    val origine: String = OriginiStudio.AUTOMATICA,
    val giorno: String = "",
    val chiave: String? = null,
    val inizio: Long,
    val avviatoDa: String? = null,
    val partenze: List<PartenzaStudio> = emptyList(),
    val contaDal: Long? = null,
    val chiudibileDal: Long? = null,
    val minutiMinimi: Int = 60,
    val minutiAttivita: Int = 0,
    val minutiAllaChiusura: Int? = null,
    val chiudibile: Boolean = false,
    val tratti: List<TrattoDelServer> = emptyList(),
    val sessioneChiusa: String? = null,
    val fine: Long? = null,
    val chiusura: String? = null,
    val chiusaDa: String? = null,
    val dichiarazione: String? = null,
    val motivo: String? = null,
    val inCorso: Boolean = false,
    /** La lista dello Studio, se il server la manda (null = quella approvata). */
    val app: List<String>? = null,
    val nomi: Map<String, String> = emptyMap(),
) {
    /** I minuti da mostrare: quelli congelati alla chiusura, o quelli fino a adesso. */
    val minuti: Int get() = minutiAllaChiusura ?: minutiAttivita
}

/** Quello che il server dice dello Studio (`GET /api/studio`, `studio` del patto). */
data class StatoStudioServer(
    val config: ConfigStudio?,
    val inCorso: StudioSvolto?,
    val prossime: List<PartenzaStudio>,
    val recenti: List<StudioSvolto>?,
)

/** Una pagina dello storico (`GET /api/studio/svolte`). */
data class PaginaSvolte(val svolte: List<StudioSvolto>, val altre: Boolean)

/**
 * La lettura delle forme del contratto (logica pura). Tutto ciò che non si
 * legge diventa null o resta fuori: mai un'eccezione verso chi chiama.
 */
object LetturaStudio {

    /** `{ "config", "in_corso", "prossime_partenze", "recenti"? }`; null se non è un oggetto. */
    fun stato(elemento: JsonElement?): StatoStudioServer? {
        val o = elemento as? JsonObject ?: return null
        return StatoStudioServer(
            config = config(o["config"]),
            inCorso = svolto(o["in_corso"])?.takeIf { it.fine == null },
            prossime = (o["prossime_partenze"] as? JsonArray)?.mapNotNull { partenza(it) }.orEmpty().sortedBy { it.inizio },
            recenti = (o["recenti"] as? JsonArray)?.mapNotNull { svolto(it) },
        )
    }

    fun statoDaCorpo(corpo: String?): StatoStudioServer? = stato(LetturaSessioni.albero(corpo))

    fun config(elemento: JsonElement?): ConfigStudio? {
        val o = elemento as? JsonObject ?: return null
        val stato = testo(o["stato"])?.trim()?.lowercase() ?: return null
        return ConfigStudio(
            stato = stato,
            versione = intero(o["versione"])?.toInt() ?: 0,
            approvata = contenuto(o["approvata"]),
            inAttesa = contenuto(o["in_attesa"]),
            motivazione = testo(o["motivazione"])?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    fun configDaCorpo(corpo: String?): ConfigStudio? = config(LetturaSessioni.albero(corpo))

    fun contenuto(elemento: JsonElement?): ContenutoStudio? {
        val o = elemento as? JsonObject ?: return null
        val telefono = o["telefono"] as? JsonObject
        val computer = o["computer"] as? JsonObject
        val giorni = (o["giorni"] as? JsonArray)?.mapNotNull { testo(it)?.trim()?.lowercase() }
            ?.filter { it in GIORNI_STUDIO }?.distinct()?.sortedBy { GIORNI_STUDIO.indexOf(it) }
        return ContenutoStudio(
            giorni = giorni ?: GIORNI_FERIALI,
            inizio = orario(o["inizio"]) ?: "15:00",
            chiusuraMinima = orario(o["chiusura_minima"]) ?: "16:00",
            minutiMinimi = intero(o["minuti_minimi"])?.toInt() ?: 60,
            app = chiavi(telefono?.get("app")),
            nomi = nomi(telefono?.get("nomi")),
            programmi = chiavi(computer?.get("programmi")),
            nomiProgrammi = nomi(computer?.get("nomi")),
            orariDal = testo(o["orari_dal"])?.trim()?.takeIf { it.isNotEmpty() },
            approvataIl = LetturaSessioni.istante(o["approvata_ts"]),
            decisaDa = LetturaFaccende.nomeGenitore(o["decisa_da"]),
            richiestaIl = LetturaSessioni.istante(o["richiesta_ts"]),
            da = nomeDispositivo(o["da"]),
            versione = intero(o["versione"])?.toInt(),
        )
    }

    fun partenza(elemento: JsonElement?): PartenzaStudio? {
        val o = elemento as? JsonObject ?: return null
        val giorno = testo(o["giorno"])?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val inizio = LetturaSessioni.istante(o["inizio_ts"]) ?: return null
        return PartenzaStudio(
            giorno = giorno,
            inizio = inizio,
            chiudibileDal = LetturaSessioni.istante(o["chiudibile_dal"]),
            minutiMinimi = intero(o["minuti_minimi"])?.toInt() ?: 60,
        )
    }

    fun svolto(elemento: JsonElement?): StudioSvolto? {
        val o = elemento as? JsonObject ?: return null
        val id = intero(o["id"])?.takeIf { it > 0 } ?: return null
        val inizio = LetturaSessioni.istante(o["inizio_ts"]) ?: return null
        // Le liste congelate dello Studio: il server v4.0 le manda in
        // `liste.telefono` (il contratto dice solo «con le liste»); si leggono
        // anche `telefono` e `app` da soli, per tolleranza.
        val telefono = ((o["liste"] as? JsonObject)?.get("telefono") ?: o["telefono"]) as? JsonObject
        val app = (telefono?.get("app") ?: o["app"])?.let { if (it is JsonArray) chiavi(it) else null }
        return StudioSvolto(
            id = id,
            origine = testo(o["origine"])?.trim()?.lowercase() ?: OriginiStudio.AUTOMATICA,
            giorno = testo(o["giorno"])?.trim().orEmpty(),
            chiave = testo(o["chiave"])?.trim()?.takeIf { it.isNotEmpty() },
            inizio = inizio,
            avviatoDa = nomeDispositivo(o["avviato_da"]),
            partenze = (o["partenze"] as? JsonArray)?.mapNotNull { partenza(it) }.orEmpty(),
            contaDal = LetturaSessioni.istante(o["conta_dal"]),
            chiudibileDal = LetturaSessioni.istante(o["chiudibile_dal"]),
            minutiMinimi = intero(o["minuti_minimi"])?.toInt() ?: 60,
            minutiAttivita = intero(o["minuti_attivita"])?.toInt()?.coerceAtLeast(0) ?: 0,
            minutiAllaChiusura = intero(o["minuti_alla_chiusura"])?.toInt(),
            chiudibile = booleano(o["chiudibile"]) == true,
            tratti = (o["tratti"] as? JsonArray)?.mapNotNull { tratto(it) }.orEmpty(),
            sessioneChiusa = (o["sessione_chiusa"] as? JsonObject)?.let { testo(it["nome"]) },
            fine = LetturaSessioni.istante(o["fine_ts"]),
            chiusura = testo(o["chiusura"])?.trim()?.lowercase(),
            chiusaDa = (o["chiusa_da"] as? JsonObject)?.let { testo(it["nome"])?.trim() },
            dichiarazione = testo(o["dichiarazione"])?.trim()?.takeIf { it.isNotEmpty() },
            motivo = testo(o["motivo"])?.trim()?.takeIf { it.isNotEmpty() },
            inCorso = booleano(o["in_corso"]) ?: (o["fine_ts"] == null || o["fine_ts"] is JsonNull),
            app = app,
            nomi = nomi(telefono?.get("nomi") ?: o["nomi"]),
        )
    }

    fun svoltoDaCorpo(corpo: String?): StudioSvolto? = svolto(LetturaSessioni.albero(corpo))

    fun tratto(elemento: JsonElement?): TrattoDelServer? {
        val o = elemento as? JsonObject ?: return null
        val id = testo(o["id"])?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return TrattoDelServer(
            id = id,
            dispositivoId = intero(o["dispositivo_id"]),
            tipo = testo(o["tipo"])?.trim()?.lowercase()?.takeIf { it in TipiTratto.TUTTI } ?: TipiTratto.COMPITI,
            parola = testo(o["parola"])?.trim()?.takeIf { it.isNotEmpty() },
            faccendaId = intero(o["faccenda_id"])?.takeIf { it > 0 },
            inizio = msGrezzi(o["inizio"]),
            fine = msGrezzi(o["fine"]),
            oraAgganciata = booleano(o["ora_agganciata"]) ?: true,
            secondi = intero(o["secondi"])?.coerceAtLeast(0) ?: 0,
            secondiContati = intero(o["secondi_contati"]),
            minuti = intero(o["minuti"])?.toInt(),
            esito = testo(o["esito"])?.trim()?.lowercase() ?: EsitiTratto.FINITO,
            conta = booleano(o["conta"]),
        )
    }

    /** `GET /api/studio/svolte`: `{ "svolte": [ … ], "altre": bool }`. */
    fun pagina(corpo: String?): PaginaSvolte? {
        val o = LetturaSessioni.albero(corpo) as? JsonObject ?: return null
        val svolte = (o["svolte"] as? JsonArray)?.mapNotNull { svolto(it) } ?: return null
        return PaginaSvolte(svolte, booleano(o["altre"]) == true)
    }

    /** `GET /api/studio/versioni`: `{ "versioni": [ … ] }`, dalla più recente. */
    fun versioni(corpo: String?): List<ContenutoStudio>? {
        val o = LetturaSessioni.albero(corpo) as? JsonObject ?: return null
        return (o["versioni"] as? JsonArray)?.mapNotNull { v ->
            val oggetto = v as? JsonObject ?: return@mapNotNull null
            // Il contenuto può stare dentro la versione o in un sotto-oggetto.
            val dentro = (oggetto["contenuto"] as? JsonObject) ?: (oggetto["approvata"] as? JsonObject)
            val base = contenuto(dentro ?: oggetto) ?: return@mapNotNull null
            base.copy(
                versione = intero(oggetto["versione"])?.toInt() ?: base.versione,
                orariDal = testo(oggetto["orari_dal"])?.trim() ?: base.orariDal,
                approvataIl = LetturaSessioni.istante(oggetto["approvata_ts"]) ?: base.approvataIl,
                decisaDa = LetturaFaccende.nomeGenitore(oggetto["decisa_da"]) ?: base.decisaDa,
            )
        }
    }

    /** Il codice d'errore di una risposta (`{"errore"}` o `{"detail": {"errore"}}`). */
    fun errore(corpo: String?): String? = EsitiFaccende.errore(corpo)

    /** Un numero dentro un errore (`"minuti": 42`). */
    fun numeroNellErrore(corpo: String?, chiave: String): Long? {
        val radice = LetturaSessioni.albero(corpo) as? JsonObject ?: return null
        val dentro = radice["detail"] as? JsonObject ?: radice
        return intero(dentro[chiave])
    }

    /** Un istante dentro un errore (`"chiudibile_dal": "…"`). */
    fun istanteNellErrore(corpo: String?, chiave: String): Long? {
        val radice = LetturaSessioni.albero(corpo) as? JsonObject ?: return null
        val dentro = radice["detail"] as? JsonObject ?: radice
        return LetturaSessioni.istante(dentro[chiave])
    }

    /** Lo Studio dentro una risposta d'errore (`"studio": {…}`). */
    fun studioNellErrore(corpo: String?): StudioSvolto? {
        val radice = LetturaSessioni.albero(corpo) as? JsonObject ?: return null
        val dentro = radice["detail"] as? JsonObject ?: radice
        return svolto(dentro["studio"])
    }

    /** La configurazione dentro una risposta d'errore (`richiesta_cambiata`). */
    fun configNellErrore(corpo: String?): ConfigStudio? {
        val radice = LetturaSessioni.albero(corpo) as? JsonObject ?: return null
        val dentro = radice["detail"] as? JsonObject ?: radice
        return config(dentro["config"])
    }

    /** "HH:MM" valido, altrimenti null. */
    fun orario(elemento: JsonElement?): String? = testo(elemento)?.trim()?.takeIf { Orari.minuti(it) != null }

    private fun nomeDispositivo(elemento: JsonElement?): String? = when (elemento) {
        is JsonObject -> testo(elemento["nome"])
        is JsonPrimitive -> elemento.takeIf { it.isString }?.contentOrNull
        else -> null
    }?.trim()?.takeIf { it.isNotEmpty() }

    private fun chiavi(elemento: JsonElement?): List<String> =
        (elemento as? JsonArray)?.mapNotNull { testo(it)?.trim()?.takeIf { c -> c.isNotEmpty() } }?.distinct().orEmpty()

    private fun nomi(elemento: JsonElement?): Map<String, String> =
        (elemento as? JsonObject)?.mapNotNull { (k, v) -> testo(v)?.trim()?.takeIf { it.isNotEmpty() }?.let { k to it } }?.toMap().orEmpty()

    /** Un istante in millisecondi scritto come numero (i tratti), o come data. */
    private fun msGrezzi(elemento: JsonElement?): Long? {
        val p = elemento as? JsonPrimitive ?: return null
        if (!p.isString) return p.longOrNull?.takeIf { it > 0 }
        return LetturaSessioni.istante(p)
    }

    private fun booleano(elemento: JsonElement?): Boolean? =
        (elemento as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

    private fun testo(elemento: JsonElement?): String? = (elemento as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

    private fun intero(elemento: JsonElement?): Long? {
        val p = elemento as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.longOrNull ?: p.contentOrNull?.trim()?.toLongOrNull()
    }
}

/** (0.18) Gli orari "HH:MM" dello Studio (logica pura). */
object Orari {
    /** "15:00" → 900; null se non è un orario. */
    fun minuti(testo: String?): Int? {
        val t = testo?.trim() ?: return null
        val m = Regex("""^(\d{1,2}):(\d{2})$""").matchEntire(t) ?: return null
        val ore = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (ore > 23 || min > 59) return null
        return ore * 60 + min
    }

    /** 900 → "15:00". */
    fun testo(minuti: Int): String = "%02d:%02d".format((minuti / 60) % 24, minuti % 60)

    /** Un orario scritto a mano ("9", "9.30", "930", "09:30") nella forma "HH:MM"; null se non si capisce. */
    fun normalizza(scritto: String): String? {
        val t = scritto.trim().replace('.', ':').replace(',', ':')
        val conDuePunti = when {
            ':' in t -> t
            t.length <= 2 -> "$t:00"
            t.length == 3 -> "${t.substring(0, 1)}:${t.substring(1)}"
            t.length == 4 -> "${t.substring(0, 2)}:${t.substring(2)}"
            else -> return null
        }
        return minuti(conDuePunti)?.let { testo(it) }
    }
}
