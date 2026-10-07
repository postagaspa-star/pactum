package eu.stgm.pactum.figlio.studio

import java.time.Instant
import java.time.ZoneId

/**
 * (0.18) Le frasi dello Studio passate da fuori (strings.xml), così la
 * logica resta pura e si prova senza Android.
 */
data class ParoleStudio(
    /** (ora) → "Studio dalle 15:00". */
    val dalle: String = "Studio dalle %1\$s",
    /** (minuti, minimi) → "42 min su 60". */
    val minutiSu: String = "%1\$d min su %2\$d",
    /** (ora) → "si chiude dopo le 16:00". */
    val chiudeDopo: String = "si chiude dopo le %1\$s",
    /** "puoi chiuderlo". */
    val chiudibile: String = "puoi chiuderlo",
    val separatore: String = " · ",
    val compiti: String = "Compiti",
    val lavori: String = "Lavori di casa",
    /** (minuti) → "30 min". */
    val minuti: String = "%1\$d min",
)

/** (0.18) Le parole dello Studio (logica pura). */
object TestoStudio {

    /** "15:00" nel fuso [zona]. */
    fun ora(istante: Long, zona: ZoneId): String {
        val t = Instant.ofEpochMilli(istante).atZone(zona)
        return "%02d:%02d".format(t.hour, t.minute)
    }

    /**
     * Lo stato in una riga, quello della notifica fissa: «Studio dalle 15:00 ·
     * 42 min su 60 · si chiude dopo le 16:00». Quando si può chiudere: «…
     * · puoi chiuderlo». Uno Studio a mano senza vincolo d'orario non dice l'ora.
     */
    fun stato(studio: StudioAttivo, minuti: Int, chiudibile: Boolean, zona: ZoneId, p: ParoleStudio = ParoleStudio()): String {
        val pezzi = mutableListOf(p.dalle.format(ora(studio.inizio, zona)), p.minutiSu.format(minuti, studio.minutiMinimi))
        when {
            chiudibile -> pezzi += p.chiudibile
            studio.chiudibileDal != null -> pezzi += p.chiudeDopo.format(ora(studio.chiudibileDal, zona))
        }
        return pezzi.joinToString(p.separatore)
    }

    /** Il timer: "4:05", "12:34", "1:02:03". */
    fun durata(secondi: Long): String {
        val s = secondi.coerceAtLeast(0)
        val ore = s / 3600
        val min = (s % 3600) / 60
        val sec = s % 60
        return if (ore > 0) "%d:%02d:%02d".format(ore, min, sec) else "%d:%02d".format(min, sec)
    }

    /** Il nome di un tratto: «Compiti», «Lavori di casa», o la parola di «altro» (e la parola anche coi compiti). */
    fun nome(tipo: String, parola: String?, p: ParoleStudio = ParoleStudio()): String = when (tipo) {
        TipiTratto.COMPITI -> parola?.let { "${p.compiti} ($it)" } ?: p.compiti
        TipiTratto.LAVORI_DI_CASA -> parola?.let { "${p.lavori} ($it)" } ?: p.lavori
        else -> parola ?: tipo
    }

    /**
     * Il riepilogo dei tratti per «Chiudi lo Studio»: «Compiti 30 min ·
     * Lavori di casa 10 min · allenamento 25 min», gli stessi tipi sommati,
     * nell'ordine in cui sono cominciati. I tratti di meno di un minuto non si dicono.
     */
    fun riepilogo(tratti: List<Triple<String, String?, Long>>, p: ParoleStudio = ParoleStudio()): String {
        val somme = LinkedHashMap<String, Long>()
        for ((tipo, parola, secondi) in tratti) {
            val chiave = nome(tipo, parola, p)
            somme[chiave] = (somme[chiave] ?: 0L) + secondi
        }
        return somme.filterValues { it >= 60 }.entries.joinToString(p.separatore) { (n, s) -> "$n ${p.minuti.format((s / 60).toInt())}" }
    }

    private val NOMI_GIORNI = mapOf(
        "lun" to "lunedì", "mar" to "martedì", "mer" to "mercoledì", "gio" to "giovedì",
        "ven" to "venerdì", "sab" to "sabato", "dom" to "domenica",
    )

    /**
     * I giorni detti in chiaro: «dal lunedì al venerdì» se sono di fila (da
     * almeno 3), «tutti i giorni», altrimenti «lunedì, mercoledì e venerdì».
     */
    fun giorni(giorni: List<String>): String {
        val ordinati = GIORNI_STUDIO.filter { it in giorni }
        if (ordinati.isEmpty()) return ""
        if (ordinati.size == 7) return "tutti i giorni"
        val indici = ordinati.map { GIORNI_STUDIO.indexOf(it) }
        val diFila = indici.zipWithNext().all { (a, b) -> b == a + 1 }
        if (diFila && ordinati.size >= 3) return "dal ${NOMI_GIORNI[ordinati.first()]} al ${NOMI_GIORNI[ordinati.last()]}"
        val nomi = ordinati.map { NOMI_GIORNI.getValue(it) }
        return if (nomi.size == 1) nomi.single() else nomi.dropLast(1).joinToString(", ") + " e " + nomi.last()
    }
}
