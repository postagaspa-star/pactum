package eu.stgm.pactum.figlio.faccende

import eu.stgm.pactum.figlio.sessione.LetturaSessioni
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
    /** (nome del genitore, quante) → "Mamma ti ha dato 3 lavori di casa" / "…un lavoro di casa". */
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
    /** (0.17, v3.9) (nome) → "Mamma ha cambiato un lavoro di casa". */
    val modificata: String = "",
    /** (titolo nuovo, titolo di prima) → "«Leggere 20 pagine» (prima «Leggere»)". */
    val modificataTitolo: String = "",
    /** "Ora blocca il telefono da subito". */
    val modificataBloccoSubito: String = "",
    /** (ora) → "Ora blocca il telefono dalle 18:00". */
    val modificataBloccoAlle: String = "",
    /** (ora) → "Ora blocca il telefono domani dalle 18:00". */
    val modificataBloccoDomani: String = "",
    /** (giorno, ora) → "Ora blocca il telefono giovedì dalle 18:00". */
    val modificataBloccoGiorno: String = "",
    /** (nota) → "Nota: «…»". */
    val modificataNota: String = "",
    val modificataNotaTolta: String = "",
    /** (nome, titolo) → "Mamma ha confermato «Letto»". */
    val confermata: String = "",
    val confermataTesto: String = "",
    /** (0.18, v4.0) (nome, titolo) → "Mamma ha approvato «Letto»". */
    val approvata: String = "",
    /** "Telefono e computer sono sbloccati." */
    val approvataSblocca: String = "",
    /** "Il lavoro è fatto." */
    val approvataTesto: String = "",
    /** (0.18) "Il lavoro è fatto: il blocco non partirà a fine Studio." */
    val approvataNonParte: String = "",
)

/**
 * (0.13) Le parole delle faccende (logica pura). Il nome del genitore viene
 * dal payload della notifica: "Mamma ti ha dato 3 lavori di casa · blocco dalle 16:00".
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

    /**
     * (0.17, contratto v3.9) `faccenda_modificata`: "Mamma ha cambiato un
     * lavoro di casa", e sotto il titolo (nuovo, col vecchio se è cambiato) e
     * cosa è cambiato: la nuova ora del blocco detta in chiaro ("da subito" se
     * adesso blocca subito), la nota. I cambi vengono da `cambi` del payload;
     * senza, il titolo e basta. Null se manca il titolo.
     */
    fun avvisoModificata(payload: JsonObject, adesso: Long, zona: ZoneId, parole: ParoleFaccende): Pair<String, String>? {
        val titoloFaccenda = testo(payload["titolo"]) ?: return null
        val nome = genitore(payload) ?: parole.genitoreSenzaNome
        val cambi = payload["cambi"] as? JsonObject
        val cambioTitolo = cambi?.get("titolo") as? JsonObject
        val prima = cambioTitolo?.let { testo(it["prima"]) }?.takeIf { it != titoloFaccenda }
        val righe = mutableListOf(
            if (prima != null) parole.modificataTitolo.format(titoloFaccenda, prima) else "«$titoloFaccenda»",
        )
        // Il blocco: "dopo" null (o un'ora già passata) = da subito.
        val cambioBlocco = cambi?.get("blocco_da")
        if (cambioBlocco != null) {
            val dopo = (cambioBlocco as? JsonObject)?.get("dopo")?.let { LetturaSessioni.istante(it) }
            righe += when (val quando = quandoBlocca(dopo, adesso, zona)) {
                QuandoBlocca.Subito -> parole.modificataBloccoSubito
                is QuandoBlocca.Oggi -> parole.modificataBloccoAlle.format(quando.ora)
                is QuandoBlocca.Domani -> parole.modificataBloccoDomani.format(quando.ora)
                is QuandoBlocca.Giorno -> parole.modificataBloccoGiorno.format(quando.giorno, quando.ora)
                is QuandoBlocca.Data -> parole.modificataBloccoGiorno.format(quando.data, quando.ora)
            }
        }
        val cambioNota = cambi?.get("nota")
        if (cambioNota != null) {
            val nota = (cambioNota as? JsonObject)?.let { testo(it["dopo"]) }
            righe += if (nota != null) parole.modificataNota.format(nota) else parole.modificataNotaTolta
        }
        return parole.modificata.format(nome) to righe.joinToString("\n")
    }

    /**
     * (0.17, v3.9) `faccenda_confermata`: "Mamma ha confermato «Letto»". Null se manca il titolo.
     * (0.18, contratto v4.0) Dalla v4.0 il payload porta `sblocca`: confermare
     * è approvare. "Mamma ha approvato «Letto»", e se era l'ultimo che
     * bloccava "Telefono e computer sono sbloccati.". Se il blocco era
     * rimandato dallo Studio il server chiude il suo [messaggio] con
     * «: il blocco non partirà a fine Studio» (`sblocca` è false, come per un
     * lavoro che non era l'ultimo): allora lo dice anche il telefono.
     */
    fun avvisoConfermata(payload: JsonObject, parole: ParoleFaccende, messaggio: String? = null): Pair<String, String>? {
        val titoloFaccenda = testo(payload["titolo"]) ?: return null
        val nome = genitore(payload) ?: parole.genitoreSenzaNome
        val sblocca = (payload["sblocca"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        if (sblocca != null && parole.approvata.isNotEmpty()) {
            val dettaglio = when {
                sblocca -> parole.approvataSblocca
                bloccoNonParte(messaggio) && parole.approvataNonParte.isNotEmpty() -> parole.approvataNonParte
                else -> parole.approvataTesto
            }
            return parole.approvata.format(nome, titoloFaccenda) to dettaglio
        }
        return parole.confermata.format(nome, titoloFaccenda) to parole.confermataTesto
    }

    /** (0.18, contratto v4.0, parte A) La coda del messaggio del server quando il blocco era rimandato dallo Studio. */
    const val CODA_NON_PARTE = "il blocco non partirà a fine Studio"

    private fun bloccoNonParte(messaggio: String?): Boolean =
        messaggio?.trim()?.trimEnd('.')?.endsWith(CODA_NON_PARTE) == true

    /**
     * (0.17, contratto v3.9) Da quando blocca un lavoro già partito: "14:02"
     * se è partito oggi, "3/10" (e l'ora) se prima; null se non è ancora
     * partito (o l'ora non si sa).
     */
    fun partitoDa(bloccoDa: Long?, adesso: Long, zona: ZoneId): Partito? {
        if (bloccoDa == null || bloccoDa > adesso + MARGINE_SUBITO_MS) return null
        val quando = Instant.ofEpochMilli(bloccoDa).atZone(zona)
        val oggi = Instant.ofEpochMilli(adesso).atZone(zona).toLocalDate()
        return if (quando.toLocalDate() == oggi) {
            Partito.Oggi(formatoOra.format(quando))
        } else {
            Partito.Prima(formatoData.format(quando), formatoOra.format(quando))
        }
    }

    /** (0.17) Da quando blocca un lavoro già partito: oggi a un'ora, o un giorno prima. */
    sealed interface Partito {
        data class Oggi(val ora: String) : Partito
        data class Prima(val data: String, val ora: String) : Partito
    }

    /**
     * (0.17, contratto v3.9) Il lavoro è stato dato "da subito": il blocco è
     * partito quando il genitore l'ha dato (`blocco_da` entro un minuto da
     * `creata_ts`, come nell'app del genitore). Senza `blocco_da` è subito;
     * senza `creata_ts` non si sa, e si dice l'ora.
     */
    fun datoDaSubito(bloccoDa: Long?, creataIl: Long?): Boolean {
        if (bloccoDa == null) return true
        return creataIl != null && bloccoDa <= creataIl + MARGINE_SUBITO_MS
    }

    /** (0.17) L'ora di un lavoro da fare, come la dice la pagina. */
    sealed interface OraLavoro {
        /** Dato "da subito": "Blocca il telefono da subito (dalle 14:02)"; [partito] null se l'ora non si sa. */
        data class DaSubito(val partito: Partito?) : OraLavoro

        /** Dato per un'ora che è già arrivata: "Blocca il telefono dalle 16:00" (non "da subito"). */
        data class AllOra(val partito: Partito) : OraLavoro

        /** Non ancora: "Blocca il telefono dalle 18:00 / domani dalle … / giovedì dalle …". */
        data class Prossimo(val quando: QuandoBlocca) : OraLavoro
    }

    /**
     * (0.17, contratto v3.9) L'ora di un lavoro da fare, sempre (anche a
     * blocco partito): non ancora partito → quando; partito → "da subito" solo
     * se è stato dato a blocco subito ([datoDaSubito]), altrimenti la sua ora.
     */
    fun oraLavoro(bloccoDa: Long?, creataIl: Long?, adesso: Long, zona: ZoneId): OraLavoro {
        val quando = quandoBlocca(bloccoDa, adesso, zona)
        if (quando != QuandoBlocca.Subito) return OraLavoro.Prossimo(quando)
        val partito = partitoDa(bloccoDa, adesso, zona)
        if (datoDaSubito(bloccoDa, creataIl) || partito == null) return OraLavoro.DaSubito(partito)
        return OraLavoro.AllOra(partito)
    }

    /** Gli id delle faccende nuove nel payload di `nuove_faccende`. */
    fun idNuove(payload: JsonObject): List<Long> =
        (payload["faccenda_ids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }.orEmpty()

    private fun testo(elemento: JsonElement?): String? =
        (elemento as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}
