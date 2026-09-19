package eu.stgm.pactum.figlio.valutatore

import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.TipiRegola
import eu.stgm.pactum.figlio.misura.UsoApp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Uno sforamento rilevato sul telefono (contratto-api.md, evento `sforamento`).
 * `limiteEfficace` è valorizzato solo per limite_tempo (= minuti_al_giorno +
 * bonus di oggi su quella regola); per le fasce orarie non c'è un tetto di
 * minuti, `minutiOltre` racconta quanti minuti d'uso sono caduti nella fascia.
 * `giornoAncora` è valorizzato solo per le fasce: è il giorno di ANCORAGGIO
 * dell'occorrenza (quando la fascia parte) e fa da chiave di dedup, così una
 * fascia che scavalca la mezzanotte resta UNA sola occorrenza.
 */
data class Sforamento(
    val regolaId: Long,
    val tipo: String,
    val limiteEfficace: Int?,
    val minutiOltre: Int,
    val giornoAncora: String? = null,
)

/** Un intervallo proibito da una fascia, con il giorno di ancoraggio dell'occorrenza. */
data class IntervalloProibito(val giornoAncora: String, val inizio: Long, val fine: Long)

/** Dove sta oggi una fascia oraria rispetto ad adesso. */
sealed interface MomentoFascia {
    /** La fascia parte più tardi, oggi: mancano [minuti] alle [inizio]. */
    data class Prima(val minuti: Long, val inizio: LocalTime) : MomentoFascia

    /** Adesso sei dentro la fascia, fino alle [fine]. */
    data class InCorso(val minuti: Long, val fine: LocalTime) : MomentoFascia

    /** Oggi la fascia c'è stata ed è finita. */
    data object Finita : MomentoFascia

    /** Oggi la fascia non vale. */
    data object NonOggi : MomentoFascia
}

/**
 * L'uso di oggi indicizzato come lo legge il valutatore: per pacchetto (in
 * minuscolo) e per chiave `categoria:*`, con i minuti arrotondati per app —
 * lo stesso conto di SentinellaPatto e della schermata Oggi, così "48 min su
 * 1 h" e lo sforamento parlano degli stessi minuti.
 */
class IndiceUso(
    uso: List<Pair<String, Long>>,
    categoriaDi: (String) -> String,
) {
    private val perPacchetto = HashMap<String, Long>()
    private val perCategoria = HashMap<String, Long>()

    init {
        for ((pacchetto, millis) in uso) {
            val minuti = millis / 60_000
            perPacchetto.merge(pacchetto.lowercase(), minuti, Long::plus)
            perCategoria.merge(categoriaDi(pacchetto), minuti, Long::plus)
        }
    }

    /** I minuti di oggi su una chiave `app_o_categoria` (match esatto, contratto v2.1). */
    fun minuti(chiave: String): Long {
        val k = chiave.trim().lowercase()
        return if (k.startsWith(PREFISSO_CATEGORIA)) perCategoria[k] ?: 0L else perPacchetto[k] ?: 0L
    }

    companion object {
        private const val PREFISSO_CATEGORIA = "categoria:"

        /**
         * L'indice dall'uso letto sul telefono, tenendo SOLO i pacchetti che
         * [contaNellUso] ammette: lo stesso filtro della fotografia inviata al
         * server, così una categoria qui somma le stesse app che il genitore
         * vede sommate nella sua finestra.
         */
        fun daUso(
            uso: List<UsoApp>,
            contaNellUso: (String) -> Boolean,
            categoriaDi: (String) -> String,
        ): IndiceUso = IndiceUso(
            uso = uso.filter { contaNellUso(it.pacchetto) }.map { it.pacchetto to it.millisPrimoPiano },
            categoriaDi = categoriaDi,
        )
    }
}

/**
 * Il valutatore locale: confronta l'uso di oggi con le regole attive del patto e
 * restituisce gli sforamenti. È logica pura (nessun IO): l'uso arriva da due
 * lambde — per chiave app_o_categoria (limite_tempo) e per intervallo di tempo
 * (fascia_oraria) — così è testabile e riusabile dal worker e dal loop del
 * servizio. NON blocca niente e NON deduplica: la deduplica (una per regola per
 * giorno, o per giorno di ancoraggio per le fasce) e la notifica le fa il chiamante.
 */
object Valutatore {

    // Ordine ISO: lun=1 … dom=7 (LocalDate.dayOfWeek.value), indicizzato da 0.
    private val GIORNI = listOf("lun", "mar", "mer", "gio", "ven", "sab", "dom")

    /** Sotto il minuto d'uso in una fascia = rumore di misura, non uno sforamento. */
    const val TOLLERANZA_FASCIA_MIN = 1L

    fun valuta(
        regole: List<Regola>,
        bonusOggiPerRegola: Map<String, Int>,
        usoMinutiEtichetta: (String) -> Long,
        usoMinutiIntervallo: (Long, Long) -> Long,
        now: Long,
        zona: ZoneId,
    ): List<Sforamento> {
        val risultati = mutableListOf<Sforamento>()
        for (regola in regole) {
            if (!regola.attiva) continue
            when (regola.tipo) {
                TipiRegola.LIMITE_TEMPO ->
                    valutaLimite(regola, bonusOggiPerRegola, usoMinutiEtichetta)
                        ?.let { risultati += it }
                TipiRegola.FASCIA_ORARIA ->
                    risultati += valutaFascia(regola, usoMinutiIntervallo, now, zona)
                // vita_reale: niente sforamento automatico, si dichiara a mano.
                else -> Unit
            }
        }
        return risultati
    }

    /**
     * Il limite efficace di oggi di una limite_tempo: `minuti_al_giorno` + i bonus
     * concessi oggi su QUELLA regola (contratto, POST /api/bonus). Null se la
     * regola non è un limite di tempo o ha parametri illeggibili.
     */
    fun limiteEfficace(regola: Regola, bonusOggiPerRegola: Map<String, Int>): Int? {
        if (regola.tipo != TipiRegola.LIMITE_TEMPO) return null
        val limite = intero(regola.parametri, "minuti_al_giorno") ?: return null
        return limite + (bonusOggiPerRegola[regola.id.toString()] ?: 0)
    }

    /**
     * (v2.4) Il `giorno` dei dettagli di uno sforamento: il giorno locale del
     * telefono in cui è successo, oppure — per le fasce che scavalcano la
     * mezzanotte — il giorno di ancoraggio dell'occorrenza (lo stesso del dedup).
     * Così uno sforamento consegnato in ritardo cade nel giorno giusto.
     */
    fun giornoDelloSforamento(sforamento: Sforamento, giornoTelefono: String): String =
        sforamento.giornoAncora ?: giornoTelefono

    /** I dettagli dell'evento `sforamento` (contratto-api.md, POST /api/eventi). */
    fun dettagliSforamento(sforamento: Sforamento, giornoTelefono: String): JsonObject =
        buildJsonObject {
            put("regola_id", sforamento.regolaId)
            sforamento.limiteEfficace?.let { put("limite_efficace", it) }
            put("minuti_oltre", sforamento.minutiOltre)
            put("giorno", giornoDelloSforamento(sforamento, giornoTelefono))
        }

    private fun valutaLimite(
        regola: Regola,
        bonusOggiPerRegola: Map<String, Int>,
        usoMinutiEtichetta: (String) -> Long,
    ): Sforamento? {
        val app = stringa(regola.parametri, "app_o_categoria") ?: return null
        val limiteEfficace = limiteEfficace(regola, bonusOggiPerRegola) ?: return null
        val uso = usoMinutiEtichetta(app)
        if (uso <= limiteEfficace) return null
        return Sforamento(
            regolaId = regola.id,
            tipo = regola.tipo,
            limiteEfficace = limiteEfficace,
            minutiOltre = (uso - limiteEfficace).toInt(),
        )
    }

    /**
     * Una fascia può avere due parti visibili oggi (la coda mattutina della
     * fascia di ieri e la testa serale di quella di oggi): sono DUE occorrenze
     * diverse, quindi si raggruppano per giorno di ancoraggio e ognuna genera al
     * più uno sforamento. Le due parti della stessa occorrenza (sera + mattina
     * dopo mezzanotte) condividono invece lo stesso ancoraggio, così non si
     * contano due volte.
     */
    private fun valutaFascia(
        regola: Regola,
        usoMinutiIntervallo: (Long, Long) -> Long,
        now: Long,
        zona: ZoneId,
    ): List<Sforamento> {
        val dalle = ora(regola.parametri, "dalle") ?: return emptyList()
        val alle = ora(regola.parametri, "alle") ?: return emptyList()
        val giorni = stringhe(regola.parametri, "giorni")
        if (giorni.isEmpty()) return emptyList()

        return intervalliProibitiOggi(dalle, alle, giorni, now, zona)
            .groupBy { it.giornoAncora }
            .mapNotNull { (giornoAncora, intervalli) ->
                val usoTot = intervalli.sumOf { usoMinutiIntervallo(it.inizio, it.fine) }
                if (usoTot < TOLLERANZA_FASCIA_MIN) return@mapNotNull null
                Sforamento(
                    regolaId = regola.id,
                    tipo = regola.tipo,
                    limiteEfficace = null,
                    minutiOltre = usoTot.toInt(),
                    giornoAncora = giornoAncora,
                )
            }
    }

    /**
     * Gli intervalli proibiti da una fascia oraria che ricadono OGGI (fuso del
     * telefono) e sono già iniziati, clippati a [inizio di oggi, adesso], ognuno
     * etichettato col giorno di ANCORAGGIO (quando la fascia parte). Gestisce le
     * fasce che scavalcano la mezzanotte (es. 23:00→07:00): la parte serale
     * appartiene al giorno che la fa partire, quella mattutina al giorno prima —
     * il controllo su `giorni` e l'ancoraggio usano il giorno di partenza.
     */
    fun intervalliProibitiOggi(
        dalle: LocalTime,
        alle: LocalTime,
        giorni: List<String>,
        now: Long,
        zona: ZoneId,
    ): List<IntervalloProibito> {
        val oggi = Instant.ofEpochMilli(now).atZone(zona).toLocalDate()
        val inizioOggi = oggi.atStartOfDay(zona).toInstant().toEpochMilli()
        val risultati = mutableListOf<IntervalloProibito>()
        // Due ancoraggi bastano: la fascia di ieri (parte mattutina di oggi) e
        // quella di oggi (parte serale). Clippando a [inizioOggi, now] non c'è
        // doppio conteggio con i giorni vicini.
        for (ancora in listOf(oggi.minusDays(1), oggi)) {
            val etichetta = GIORNI[ancora.dayOfWeek.value - 1]
            if (etichetta !in giorni) continue
            val inizioLocale = ancora.atTime(dalle)
            val fineLocale =
                if (alle.isAfter(dalle)) ancora.atTime(alle) else ancora.plusDays(1).atTime(alle)
            val inizioMs = inizioLocale.atZone(zona).toInstant().toEpochMilli()
            val fineMs = fineLocale.atZone(zona).toInstant().toEpochMilli()
            val s = maxOf(inizioMs, inizioOggi)
            val e = minOf(fineMs, now)
            if (e > s) risultati += IntervalloProibito(ancora.toString(), s, e)
        }
        return risultati
    }

    /**
     * Dove sta oggi una fascia oraria rispetto ad adesso, per dirlo a parole
     * nella schermata Oggi ("mancano 3 h alle 23:00"). Stessa geometria di
     * [intervalliProibitiOggi]: la fascia appartiene al giorno in cui parte.
     * Null se la regola non è una fascia leggibile.
     */
    fun momentoFascia(regola: Regola, now: Long, zona: ZoneId): MomentoFascia? {
        if (regola.tipo != TipiRegola.FASCIA_ORARIA) return null
        val dalle = ora(regola.parametri, "dalle") ?: return null
        val alle = ora(regola.parametri, "alle") ?: return null
        val giorni = stringhe(regola.parametri, "giorni")
        val adesso = Instant.ofEpochMilli(now)
        val oggi = adesso.atZone(zona).toLocalDate()

        var passataOggi = false
        for (ancora in listOf(oggi.minusDays(1), oggi)) {
            if (GIORNI[ancora.dayOfWeek.value - 1] !in giorni) continue
            val inizio = ancora.atTime(dalle).atZone(zona).toInstant()
            val fine = (if (alle.isAfter(dalle)) ancora.atTime(alle) else ancora.plusDays(1).atTime(alle))
                .atZone(zona).toInstant()
            when {
                !adesso.isBefore(inizio) && adesso.isBefore(fine) ->
                    return MomentoFascia.InCorso(minutiTra(adesso, fine), alle)
                ancora == oggi && adesso.isBefore(inizio) ->
                    return MomentoFascia.Prima(minutiTra(adesso, inizio), dalle)
                // Finita oggi: quella di oggi, o la coda mattutina di quella di ieri.
                !fine.isAfter(adesso) && !fine.isBefore(oggi.atStartOfDay(zona).toInstant()) ->
                    passataOggi = true
            }
        }
        return if (passataOggi) MomentoFascia.Finita else MomentoFascia.NonOggi
    }

    private fun minutiTra(da: Instant, a: Instant): Long =
        java.time.Duration.between(da, a).toMinutes().coerceAtLeast(1)

    private fun stringa(parametri: JsonObject, nome: String): String? =
        (parametri[nome] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    private fun intero(parametri: JsonObject, nome: String): Int? =
        (parametri[nome] as? JsonPrimitive)?.content?.toIntOrNull()

    private fun stringhe(parametri: JsonObject, nome: String): List<String> =
        (parametri[nome] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?: emptyList()

    private fun ora(parametri: JsonObject, nome: String): LocalTime? {
        val testo = stringa(parametri, nome) ?: return null
        return try {
            LocalTime.parse(testo)
        } catch (e: DateTimeParseException) {
            null
        }
    }
}
