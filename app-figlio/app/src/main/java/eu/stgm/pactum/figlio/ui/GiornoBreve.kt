package eu.stgm.pactum.figlio.ui

import java.time.LocalDate
import java.time.format.DateTimeFormatter

// Logica pura: un giorno del patto detto all'italiana. Il server manda i
// giorni in ISO (YYYY-MM-DD) e così devono restare nei dati; a schermo si
// legge "oggi", "ieri", "18/09". Le due parole arrivano da strings.xml.

/** "oggi" e "ieri" (minuscole dentro una frase, maiuscole su un chip). */
data class ParoleGiorno(val oggi: String, val ieri: String)

private val FORMATO_GIORNO: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")
private val FORMATO_GIORNO_ANNO: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

/**
 * [giorno] rispetto a [oggi]: "oggi", "ieri", altrimenti "18/09"; l'anno solo
 * se non è quello di [oggi], perché "18/09" di un altro anno mentirebbe.
 */
fun giornoBreve(giorno: LocalDate, oggi: LocalDate, parole: ParoleGiorno): String = when (giorno) {
    oggi -> parole.oggi
    oggi.minusDays(1) -> parole.ieri
    else -> giorno.format(if (giorno.year == oggi.year) FORMATO_GIORNO else FORMATO_GIORNO_ANNO)
}

/** Lo stesso a partire dal testo ISO del server; un testo che non è una data resta com'è. */
fun giornoBreve(iso: String, oggi: LocalDate, parole: ParoleGiorno): String =
    runCatching { LocalDate.parse(iso) }.getOrNull()
        ?.let { giornoBreve(it, oggi, parole) }
        ?: iso
