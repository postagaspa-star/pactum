package eu.stgm.pactum.figlio.valutatore

import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.TipiRegola
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Uno sforamento rilevato sul telefono (contratto-api.md, evento `sforamento`).
 * `limiteEfficace` è valorizzato solo per limite_tempo (= minuti_al_giorno +
 * bonus di oggi su quella regola); per le fasce orarie non c'è un tetto di
 * minuti, `minutiOltre` racconta quanti minuti d'uso sono caduti nella fascia.
 */
data class Sforamento(
    val regolaId: Long,
    val tipo: String,
    val limiteEfficace: Int?,
    val minutiOltre: Int,
)

/**
 * Il valutatore locale: confronta l'uso di oggi con le regole attive del patto e
 * restituisce gli sforamenti. È logica pura (nessun IO): l'uso arriva da due
 * lambde — per etichetta app (limite_tempo) e per intervallo di tempo
 * (fascia_oraria) — così è testabile e riusabile dal worker e dal loop del
 * servizio. NON blocca niente e NON deduplica: la deduplica (una per regola per
 * giorno) e la notifica le fa il chiamante.
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
            val sforamento = when (regola.tipo) {
                TipiRegola.LIMITE_TEMPO ->
                    valutaLimite(regola, bonusOggiPerRegola, usoMinutiEtichetta)
                TipiRegola.FASCIA_ORARIA ->
                    valutaFascia(regola, usoMinutiIntervallo, now, zona)
                // vita_reale: niente sforamento automatico, si dichiara a mano.
                else -> null
            }
            if (sforamento != null) risultati += sforamento
        }
        return risultati
    }

    private fun valutaLimite(
        regola: Regola,
        bonusOggiPerRegola: Map<String, Int>,
        usoMinutiEtichetta: (String) -> Long,
    ): Sforamento? {
        val app = stringa(regola.parametri, "app_o_categoria") ?: return null
        val limite = intero(regola.parametri, "minuti_al_giorno") ?: return null
        val bonus = bonusOggiPerRegola[regola.id.toString()] ?: 0
        val limiteEfficace = limite + bonus
        val uso = usoMinutiEtichetta(app)
        if (uso <= limiteEfficace) return null
        return Sforamento(
            regolaId = regola.id,
            tipo = regola.tipo,
            limiteEfficace = limiteEfficace,
            minutiOltre = (uso - limiteEfficace).toInt(),
        )
    }

    private fun valutaFascia(
        regola: Regola,
        usoMinutiIntervallo: (Long, Long) -> Long,
        now: Long,
        zona: ZoneId,
    ): Sforamento? {
        val dalle = ora(regola.parametri, "dalle") ?: return null
        val alle = ora(regola.parametri, "alle") ?: return null
        val giorni = stringhe(regola.parametri, "giorni")
        if (giorni.isEmpty()) return null

        val intervalli = intervalliProibitiOggi(dalle, alle, giorni, now, zona)
        val usoTot = intervalli.sumOf { usoMinutiIntervallo(it.first, it.second) }
        if (usoTot < TOLLERANZA_FASCIA_MIN) return null
        return Sforamento(
            regolaId = regola.id,
            tipo = regola.tipo,
            limiteEfficace = null,
            minutiOltre = usoTot.toInt(),
        )
    }

    /**
     * Gli intervalli proibiti da una fascia oraria che ricadono OGGI (fuso del
     * telefono) e sono già iniziati, clippati a [inizio di oggi, adesso]. Gestisce
     * le fasce che scavalcano la mezzanotte (es. 23:00→07:00): la parte serale
     * appartiene al giorno che la fa partire, quella mattutina al giorno prima —
     * il controllo su `giorni` usa il giorno di ANCORAGGIO (quando la fascia parte).
     */
    fun intervalliProibitiOggi(
        dalle: LocalTime,
        alle: LocalTime,
        giorni: List<String>,
        now: Long,
        zona: ZoneId,
    ): List<Pair<Long, Long>> {
        val oggi = java.time.Instant.ofEpochMilli(now).atZone(zona).toLocalDate()
        val inizioOggi = oggi.atStartOfDay(zona).toInstant().toEpochMilli()
        val risultati = mutableListOf<Pair<Long, Long>>()
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
            if (e > s) risultati += s to e
        }
        return risultati
    }

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
