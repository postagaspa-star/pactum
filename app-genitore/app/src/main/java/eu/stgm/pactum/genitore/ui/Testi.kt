package eu.stgm.pactum.genitore.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.TipiRegola
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Traduzioni dai dati del contratto all'italiano semplice della finestra:
 * descrizioni delle regole da tipo+parametri e orari del server (ISO UTC)
 * mostrati nel fuso del telefono.
 */

/** Il ts_server ISO 8601 UTC come istante, null se malformato. */
fun istanteServer(tsServer: String?): Instant? {
    if (tsServer.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(tsServer).toInstant()
    } catch (e: DateTimeParseException) {
        null
    }
}

private val formatoOra: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val formatoDataOra: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm")
private val formatoDataOraCompleta: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

/** "HH:mm" se l'istante è di oggi (fuso del telefono), altrimenti "dd/MM HH:mm". */
fun oraOppureDataOra(istante: Instant): String {
    val fuso = ZoneId.systemDefault()
    val locale = istante.atZone(fuso)
    val formato = if (locale.toLocalDate() == LocalDate.now(fuso)) formatoOra else formatoDataOra
    return formato.format(locale)
}

fun dataOraLocale(istante: Instant): String =
    formatoDataOra.format(istante.atZone(ZoneId.systemDefault()))

fun dataOraCompletaLocale(istante: Instant): String =
    formatoDataOraCompleta.format(istante.atZone(ZoneId.systemDefault()))

private fun campo(parametri: JsonObject, nome: String): String? =
    (parametri[nome] as? JsonPrimitive)?.content

/**
 * La regola raccontata in italiano semplice, costruita da tipo+parametri
 * (contratto-api.md). Un tipo sconosciuto mostra il tipo grezzo: meglio
 * onesto che muto (tolleranza evolutiva).
 */
@Composable
fun descrizioneRegola(tipo: String, parametri: JsonObject): String = when (tipo) {
    TipiRegola.LIMITE_TEMPO -> stringResource(
        R.string.regola_limite_tempo,
        campo(parametri, "app_o_categoria") ?: "?",
        testoDurata(campo(parametri, "minuti_al_giorno")?.toLongOrNull() ?: 0),
    )

    TipiRegola.FASCIA_ORARIA -> stringResource(
        R.string.regola_fascia_oraria,
        campo(parametri, "dalle") ?: "?",
        campo(parametri, "alle") ?: "?",
        (parametri["giorni"] as? JsonArray)
            ?.joinToString(", ") { (it as? JsonPrimitive)?.content ?: "?" }
            ?: "?",
    )

    TipiRegola.VITA_REALE -> stringResource(
        R.string.regola_vita_reale,
        campo(parametri, "descrizione") ?: "?",
        campo(parametri, "arbitro_nome") ?: "?",
        campo(parametri, "frequenza") ?: "?",
    )

    else -> tipo
}

@Composable
fun testoDurata(minuti: Long): String =
    if (minuti < 60) {
        stringResource(R.string.formato_minuti, minuti)
    } else {
        stringResource(R.string.formato_ore_minuti, minuti / 60, minuti % 60)
    }
