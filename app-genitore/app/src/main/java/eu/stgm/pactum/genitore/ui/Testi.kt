package eu.stgm.pactum.genitore.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.RegolaFinestra
import eu.stgm.pactum.genitore.dati.TipiRegola
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Traduzioni dai dati del contratto all'italiano semplice della finestra:
 * descrizioni delle regole da tipo+parametri e orari del server (ISO UTC)
 * mostrati nel fuso del telefono.
 */

// istanteServer() vive in LogicaPatto.kt: è logica pura, serve anche ai test.

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

/** Un campo testuale dei parametri di una regola (es. arbitro_nome), null se assente. */
fun parametroTesto(parametri: JsonObject, nome: String): String? = campo(parametri, nome)

/** La regola della finestra raccontata col suo nome leggibile, se il server l'ha allegato. */
@Composable
fun descrizioneRegola(regola: RegolaFinestra): String =
    descrizioneRegola(regola.tipo, regola.parametri, regola.nome)

/** Il tipo della regola come sopra-titolo della sua scheda ("LIMITE DI TEMPO"). */
@Composable
fun etichettaTipoRegola(tipo: String): String = when (tipo) {
    TipiRegola.LIMITE_TEMPO -> stringResource(R.string.regola_tipo_limite_tempo)
    TipiRegola.FASCIA_ORARIA -> stringResource(R.string.regola_tipo_fascia_oraria)
    TipiRegola.VITA_REALE -> stringResource(R.string.regola_tipo_vita_reale)
    else -> tipo.uppercase()
}

/**
 * La regola raccontata in italiano semplice, costruita da tipo+parametri
 * (contratto-api.md). Un tipo sconosciuto mostra il tipo grezzo: meglio
 * onesto che muto (tolleranza evolutiva).
 *
 * `nomeApp` è il nome leggibile che la finestra allega alle limite_tempo su un
 * pacchetto ("TikTok"): se c'è, il genitore non legge mai com.zhiliaoapp.musically.
 */
@Composable
fun descrizioneRegola(tipo: String, parametri: JsonObject, nomeApp: String? = null): String = when (tipo) {
    TipiRegola.LIMITE_TEMPO -> stringResource(
        R.string.regola_limite_tempo,
        nomeApp?.takeIf { it.isNotBlank() }
            ?: etichettaAppOCategoria(campo(parametri, "app_o_categoria") ?: "?"),
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
fun testoDurata(minuti: Long): String = testoDurata(LocalContext.current, minuti)

/** Versione non-composable (serve anche alla vedetta per il digest). */
fun testoDurata(context: Context, minuti: Long): String =
    if (minuti < 60) {
        context.getString(R.string.formato_minuti, minuti)
    } else {
        context.getString(R.string.formato_ore_minuti, minuti / 60, minuti % 60)
    }

/** Un giorno ISO del contratto ("2026-07-15") come "15/07"; il grezzo se malformato. */
fun giornoBreve(giornoIso: String): String = try {
    LocalDate.parse(giornoIso).format(formatoGiornoBreve)
} catch (e: DateTimeParseException) {
    giornoIso
}

private val formatoGiornoBreve: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")

/**
 * L'etichetta leggibile di una chiave di categoria del contratto
 * ("categoria:social" → "Social"). Le cinque chiavi di convenzione hanno un
 * nome scritto per esteso — "altro" da solo, in una legenda, non si capisce:
 * diventa "Altre app". Una chiave fuori convenzione resta com'è, solo con
 * l'iniziale maiuscola: meglio onesta che muta (tolleranza evolutiva).
 *
 * Sta qui e non in strings.xml perché serve anche fuori da un Composable (la
 * vedetta, il digest) dove non c'è un Context a portata di mano.
 */
fun etichettaCategoria(chiave: String): String {
    val nome = chiave.removePrefix("categoria:")
    if (nome.isEmpty()) return chiave
    return when (nome.lowercase()) {
        "social" -> "Social"
        "video" -> "Video"
        "giochi" -> "Giochi"
        "musica" -> "Musica"
        "altro" -> "Altre app"
        else -> nome.replaceFirstChar { it.uppercaseChar() }
    }
}

/**
 * Il bersaglio di una regola limite_tempo reso leggibile: una `categoria:*`
 * diventa l'etichetta italiana ("categoria:social" → "Social"); un pacchetto
 * (es. `com.zhiliaoapp.musically`) resta com'è finché il server non allega un
 * nome risolto (S2). Così regole, sforamenti e storico non mostrano più la
 * chiave grezza `categoria:social` a un genitore che non l'ha mai vista.
 */
fun etichettaAppOCategoria(chiave: String): String =
    if (chiave.startsWith("categoria:")) etichettaCategoria(chiave) else chiave
