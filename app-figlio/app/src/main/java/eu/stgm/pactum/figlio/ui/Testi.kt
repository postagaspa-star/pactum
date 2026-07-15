package eu.stgm.pactum.figlio.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.catalogo.CatalogoApp
import eu.stgm.pactum.figlio.dati.TipiRegola
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Traduzioni dai dati del contratto all'italiano semplice: descrizioni delle
 * regole da tipo+parametri, orari del server (ISO UTC) nel fuso del telefono,
 * attese del lock in parole ("3 giorni e 4 ore").
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

private val formatoDataOra: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm")

fun dataOraLocale(istante: Instant): String =
    formatoDataOra.format(istante.atZone(ZoneId.systemDefault()))

fun parametroTesto(parametri: JsonObject, nome: String): String? =
    (parametri[nome] as? JsonPrimitive)?.content

fun giorniTesto(parametri: JsonObject): String =
    (parametri["giorni"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.content }
        ?.joinToString(", ")
        ?: ""

/**
 * La regola raccontata in italiano semplice, costruita da tipo+parametri
 * (contratto-api.md). Un tipo sconosciuto mostra il tipo grezzo: meglio
 * onesto che muto (tolleranza evolutiva).
 */
@Composable
fun descrizioneRegola(tipo: String, parametri: JsonObject): String = when (tipo) {
    TipiRegola.LIMITE_TEMPO -> {
        // app_o_categoria è un pacchetto o una chiave categoria:* (contratto
        // v2.1): si mostra l'etichetta leggibile, non il valore grezzo.
        val context = LocalContext.current
        stringResource(
            R.string.regola_limite_tempo,
            parametroTesto(parametri, "app_o_categoria")
                ?.let { CatalogoApp.etichettaValore(context, it) } ?: "?",
            testoDurata(parametroTesto(parametri, "minuti_al_giorno")?.toLongOrNull() ?: 0),
        )
    }

    TipiRegola.FASCIA_ORARIA -> stringResource(
        R.string.regola_fascia_oraria,
        parametroTesto(parametri, "dalle") ?: "?",
        parametroTesto(parametri, "alle") ?: "?",
        giorniTesto(parametri).ifBlank { "?" },
    )

    TipiRegola.VITA_REALE -> stringResource(
        R.string.regola_vita_reale,
        parametroTesto(parametri, "descrizione") ?: "?",
        parametroTesto(parametri, "arbitro_nome") ?: "?",
        parametroTesto(parametri, "frequenza") ?: "?",
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

/**
 * Un'attesa in parole a partire dai secondi del 409 di lock: "3 giorni e
 * 4 ore", "un'ora e 20 minuti", "5 minuti". Due unità bastano: la terza è
 * precisione finta per un lock di giorni.
 */
fun testoAttesa(context: Context, secondi: Long): String {
    val minutiTotali = ((secondi + 59) / 60).coerceAtLeast(1)
    val giorni = minutiTotali / (24 * 60)
    val ore = (minutiTotali % (24 * 60)) / 60
    val minuti = minutiTotali % 60

    fun testoGiorni() = if (giorni == 1L) {
        context.getString(R.string.durata_un_giorno)
    } else {
        context.getString(R.string.durata_giorni, giorni)
    }

    fun testoOre(n: Long) = if (n == 1L) {
        context.getString(R.string.durata_un_ora)
    } else {
        context.getString(R.string.durata_ore, n)
    }

    fun testoMinuti(n: Long) = if (n == 1L) {
        context.getString(R.string.durata_un_minuto)
    } else {
        context.getString(R.string.durata_minuti, n)
    }

    return when {
        giorni > 0 && ore > 0 -> context.getString(R.string.durata_e, testoGiorni(), testoOre(ore))
        giorni > 0 -> testoGiorni()
        ore > 0 && minuti > 0 -> context.getString(R.string.durata_e, testoOre(ore), testoMinuti(minuti))
        ore > 0 -> testoOre(ore)
        else -> testoMinuti(minuti)
    }
}
