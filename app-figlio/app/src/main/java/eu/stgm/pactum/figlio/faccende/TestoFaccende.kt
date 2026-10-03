package eu.stgm.pactum.figlio.faccende

import eu.stgm.pactum.figlio.sessione.LetturaSessioni
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** (0.13) Quando blocca una faccenda, visto da adesso. */
sealed interface QuandoBlocca {
    /** Già adesso (o fra meno di un minuto). */
    data object Subito : QuandoBlocca

    data class Oggi(val ora: String) : QuandoBlocca

    data class Domani(val ora: String) : QuandoBlocca

    /** Entro la settimana: "giovedì". */
    data class Giorno(val giorno: String, val ora: String) : QuandoBlocca

    /** Più avanti: "12/10". */
    data class Data(val data: String, val ora: String) : QuandoBlocca
}

/**
 * Le frasi delle notifiche delle faccende (da strings.xml), passate dentro
 * così la logica resta pura e si prova senza Android.
 */
data class ParoleFaccende(
    /** (nome del genitore, quante) → "Mamma ti ha dato 3 faccende" / "…una faccenda". */
    val nuove: (String, Int) -> String,
    /** (frase, …) → "… · blocco da subito". */
    val bloccoSubito: String,
    /** (frase, ora) → "… · blocco dalle 16:00". */
    val bloccoAlle: String,
    /** (frase, ora) → "… · blocco domani dalle 16:00". */
    val bloccoDomani: String,
    /** (frase, giorno, ora) → "… · blocco giovedì dalle 16:00". */
    val bloccoGiorno: String,
    /** Il testo sotto: "Per ognuna scatta la foto da Pactum." */
    val nuoveTesto: String,
    /** (nome, titolo) → "Mamma ha bocciato «Svuota la lavastoviglie»". */
    val bocciata: String,
    val bocciataTesto: String,
    /** (nome, titolo) → "Mamma ha annullato «…»". */
    val annullata: String,
    val annullataTesto: String,
    /** Quando il nome del genitore non c'è. */
    val genitoreSenzaNome: String,
)

/**
 * (0.13) Le parole delle faccende (logica pura). Il nome del genitore viene
 * dal payload della notifica: "Mamma ti ha dato 3 faccende · blocco dalle 16:00".
 */
object TestoFaccende {

    private val formatoOra: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val formatoData: DateTimeFormatter = DateTimeFormatter.ofPattern("d/M")
    private val italiano: Locale = Locale.ITALIAN

    /** Un minuto di margine: "subito" anche se il blocco parte fra qualche secondo. */
    private const val MARGINE_SUBITO_MS = 60_000L

    fun quandoBlocca(bloccoDa: Long?, adesso: Long, zona: ZoneId): QuandoBlocca {
        if (bloccoDa == null || bloccoDa <= adesso + MARGINE_SUBITO_MS) return QuandoBlocca.Subito
        val quando = Instant.ofEpochMilli(bloccoDa).atZone(zona)
        val oggi = Instant.ofEpochMilli(adesso).atZone(zona).toLocalDate()
        val giorno = quando.toLocalDate()
        val ora = formatoOra.format(quando)
        return when {
            !giorno.isAfter(oggi) -> QuandoBlocca.Oggi(ora)
            giorno == oggi.plusDays(1) -> QuandoBlocca.Domani(ora)
            giorno.isBefore(oggi.plusDays(7)) ->
                QuandoBlocca.Giorno(giorno.dayOfWeek.getDisplayName(TextStyle.FULL, italiano), ora)
            else -> QuandoBlocca.Data(formatoData.format(quando), ora)
        }
    }

    /** "[frase] · blocco dalle 16:00", nei suoi modi. */
    fun conBlocco(frase: String, quando: QuandoBlocca, parole: ParoleFaccende): String = when (quando) {
        QuandoBlocca.Subito -> parole.bloccoSubito.format(frase)
        is QuandoBlocca.Oggi -> parole.bloccoAlle.format(frase, quando.ora)
        is QuandoBlocca.Domani -> parole.bloccoDomani.format(frase, quando.ora)
        is QuandoBlocca.Giorno -> parole.bloccoGiorno.format(frase, quando.giorno, quando.ora)
        is QuandoBlocca.Data -> parole.bloccoGiorno.format(frase, quando.data, quando.ora)
    }

    /** Il nome del genitore dal payload (`genitore: { id, nome }`), null se non c'è. */
    fun genitore(payload: JsonObject): String? = LetturaFaccende.nomeGenitore(payload["genitore"])

    /**
     * Titolo e testo della notifica `nuove_faccende`. [titoli] = i titoli delle
     * faccende nuove se il telefono li sa già (dal blocco appena letto). Null
     * se il payload non dice quante sono: allora vale il messaggio del server.
     */
    fun avvisoNuove(
        payload: JsonObject,
        titoli: List<String>,
        adesso: Long,
        zona: ZoneId,
        parole: ParoleFaccende,
    ): Pair<String, String>? {
        val quante = (payload["faccenda_ids"] as? JsonArray)?.size?.takeIf { it > 0 } ?: return null
        val nome = genitore(payload) ?: parole.genitoreSenzaNome
        val quando = quandoBlocca(LetturaSessioni.istante(payload["blocco_da"]), adesso, zona)
        val titolo = conBlocco(parole.nuove(nome, quante), quando, parole)
        val elenco = titoli.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ") { "«$it»" }
        val testo = listOf(elenco, parole.nuoveTesto).filter { it.isNotEmpty() }.joinToString("\n")
        return titolo to testo
    }

    /** `faccenda_bocciata`: "Mamma ha bocciato «…»", la sua nota, e cosa fare. Null se manca il titolo. */
    fun avvisoBocciata(payload: JsonObject, parole: ParoleFaccende): Pair<String, String>? {
        val titoloFaccenda = testo(payload["titolo"]) ?: return null
        val nome = genitore(payload) ?: parole.genitoreSenzaNome
        val nota = testo(payload["nota"])
        val testo = listOfNotNull(nota?.let { "«$it»" }, parole.bocciataTesto).joinToString("\n")
        return parole.bocciata.format(nome, titoloFaccenda) to testo
    }

    /** `faccenda_annullata`: "Mamma ha annullato «…»". Null se manca il titolo. */
    fun avvisoAnnullata(payload: JsonObject, parole: ParoleFaccende): Pair<String, String>? {
        val titoloFaccenda = testo(payload["titolo"]) ?: return null
        val nome = genitore(payload) ?: parole.genitoreSenzaNome
        return parole.annullata.format(nome, titoloFaccenda) to parole.annullataTesto
    }

    /** Gli id delle faccende nuove nel payload di `nuove_faccende`. */
    fun idNuove(payload: JsonObject): List<Long> =
        (payload["faccenda_ids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }.orEmpty()

    private fun testo(elemento: JsonElement?): String? =
        (elemento as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}
