package eu.stgm.pactum.figlio.sessione

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * (0.11) Quanto dura una sessione (logica pura): le scelte rapide, le ore e i
 * minuti scritti a mano, i limiti del contratto (da 1 minuto a 24 ore).
 */
object DurataSessione {

    const val MINIMA = 1
    const val MASSIMA = 1440

    /** Le scelte rapide: 30 min, 1 h, 2 h, 3 h. */
    val SCELTE = listOf(30, 60, 120, 180)

    fun valida(minuti: Int?): Boolean = minuti != null && minuti in MINIMA..MASSIMA

    /**
     * Le ore e i minuti scritti dal ragazzo, in minuti; null se non è una
     * durata valida (lettere, minuti oltre 59, zero, più di 24 ore). Un campo
     * vuoto vale zero: "2" ore e niente minuti sono 120.
     */
    fun daOreMinuti(ore: String, minuti: String): Int? {
        val o = numero(ore) ?: return null
        val m = numero(minuti) ?: return null
        if (m > 59 || o > 24) return null
        val totale = o * 60 + m
        return totale.takeIf { valida(it) }
    }

    /** Il campo ha senso ma va oltre le 24 ore: si dice sul campo. */
    fun oltreIlMassimo(ore: String, minuti: String): Boolean {
        val o = numero(ore) ?: return false
        val m = numero(minuti) ?: return false
        return o * 60 + m > MASSIMA
    }

    private fun numero(testo: String): Int? {
        val t = testo.trim()
        if (t.isEmpty()) return 0
        if (t.length > 4 || !t.all { it.isDigit() }) return null
        return t.toInt()
    }
}

/**
 * (0.11) Il nome di una sessione (logica pura), come lo controlla il server:
 * 1–40 caratteri senza spazi ai bordi, in forma NFC (la stessa parola scritta
 * in due modi è lo stesso nome), niente caratteri invisibili o di controllo
 * (a capo, tab, zero-width, i segni che girano il verso del testo), unico
 * senza distinguere le maiuscole.
 */
object NomeSessione {

    const val MASSIMO = 40

    enum class Problema { VUOTO, TROPPO_LUNGO, INVISIBILI }

    /** Il nome come lo salva il server: senza spazi ai bordi, in forma NFC. */
    fun pulito(nome: String): String = java.text.Normalizer.normalize(nome.trim(), java.text.Normalizer.Form.NFC)

    /** Cosa non va nel nome, null se va bene. */
    fun problema(nome: String): Problema? {
        val p = pulito(nome)
        return when {
            p.isEmpty() -> Problema.VUOTO
            p.codePoints().anyMatch { invisibile(it) } -> Problema.INVISIBILI
            p.codePointCount(0, p.length) > MASSIMO -> Problema.TROPPO_LUNGO
            else -> null
        }
    }

    /** Un carattere che non si vede (categorie Unicode Cc, Cf, Zl, Zp). */
    fun invisibile(carattere: Int): Boolean = when (Character.getType(carattere).toByte()) {
        Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
        else -> false
    }

    /** Lo stesso nome per il server: uguale in forma NFC, senza guardare le maiuscole. */
    fun stesso(uno: String, altro: String): Boolean = pulito(uno).lowercase() == pulito(altro).lowercase()
}

/** A che ora finisce: "17:00", e se è già domani. */
data class OraFine(val ora: String, val domani: Boolean)

/** (0.11) Le frasi della risposta del genitore a una sessione, da strings.xml. */
data class ParoleRispostaSessione(
    /** "Il genitore ha approvato la sessione «%1$s»" */
    val approvata: String,
    /** "Il genitore non ha approvato la sessione «%1$s»" */
    val rifiutata: String,
    /** "Il genitore ha approvato il cambio alla sessione «%1$s»" */
    val cambioApprovato: String,
    /** "Il genitore non ha approvato il cambio alla sessione «%1$s»" */
    val cambioRifiutato: String,
    val approvataTesto: String,
    val rifiutataTesto: String,
    val cambioApprovatoTesto: String,
    val cambioRifiutatoTesto: String,
    /** "Il genitore dice: %1$s" */
    val genitoreDice: String,
)

/** (0.11) La risposta del genitore letta dal payload di `sessione_risposta`. */
data class RispostaSessione(val sessioneId: Long?, val nome: String?, val approvata: Boolean, val cambio: Boolean)

/**
 * (0.11) Le parole delle Sessioni (logica pura: le frasi arrivano da
 * strings.xml). Mai il nome tecnico di un pacchetto davanti al ragazzo.
 */
object TestoSessioni {

    private val formatoOra: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** A che ora finisce [fine] visto da [adesso], nel fuso [zona]: "17:00", o domani. */
    fun quandoFinisce(fine: Long, adesso: Long, zona: ZoneId): OraFine {
        val quando = Instant.ofEpochMilli(fine).atZone(zona)
        val oggi = Instant.ofEpochMilli(adesso).atZone(zona).toLocalDate()
        return OraFine(formatoOra.format(quando), domani = quando.toLocalDate().isAfter(oggi))
    }

    /** I minuti che mancano alla fine, arrotondati in su (mai meno di zero). */
    fun minutiMancanti(fine: Long, adesso: Long): Long =
        if (fine <= adesso) 0 else (fine - adesso + 59_999) / 60_000

    /**
     * I nomi leggibili delle app di una sessione: il nome mandato dal telefono
     * ([nomi]), oppure quello che [risolvi] trova qui; `gruppo:apk` è
     * [gruppoApk]; un'app che qui non ha nome è [sconosciuta] (una volta sola).
     */
    fun nomiApp(
        app: List<String>,
        nomi: Map<String, String>,
        risolvi: (String) -> String?,
        gruppoApk: String,
        sconosciuta: String,
    ): List<String> =
        app.map { chiave ->
            when {
                chiave == AppDellaSessione.GRUPPO_APK -> gruppoApk
                else -> nomi[chiave]?.trim()?.takeIf { it.isNotEmpty() && it != chiave }
                    ?: risolvi(chiave)?.trim()?.takeIf { it.isNotEmpty() && it != chiave }
                    ?: sconosciuta
            }
        }.distinct()

    /**
     * Un elenco in una riga: "ClasseViva, Calcolatrice". Oltre [massimo] nomi
     * si dice quanti altri ([altri] = "%1$d altre" già al plurale giusto):
     * "ClasseViva, Calcolatrice e 3 altre".
     */
    fun elenco(nomi: List<String>, massimo: Int, altri: (Int) -> String, e: String = " e "): String {
        if (nomi.size <= massimo) return nomi.joinToString(", ")
        return nomi.take(massimo).joinToString(", ") + e + altri(nomi.size - massimo)
    }

    /** Il payload di `sessione_risposta`; null se non dice com'è andata. */
    fun risposta(payload: JsonObject): RispostaSessione? {
        val esito = (payload["esito"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
        val approvata = when (esito) {
            EsitiRispostaSessione.APPROVA -> true
            EsitiRispostaSessione.RIFIUTA -> false
            else -> return null
        }
        val cambio = (payload["cambio"] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull == "true") } ?: false
        return RispostaSessione(
            sessioneId = (payload["sessione_id"] as? JsonPrimitive)?.longOrNull,
            nome = (payload["nome"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
            approvata = approvata,
            cambio = cambio,
        )
    }

    /**
     * Titolo e testo della notifica di [risposta]. [nome] = il nome della
     * sessione (dal payload, o dalla copia del patto); se non si sa, il titolo
     * è [messaggio], il testo del server. [motivazione] = il perché del
     * genitore, se non ha approvato.
     */
    fun avvisoRisposta(
        risposta: RispostaSessione,
        nome: String?,
        motivazione: String?,
        messaggio: String,
        parole: ParoleRispostaSessione,
    ): Pair<String, String> {
        val (formato, testo) = when {
            risposta.cambio && risposta.approvata -> parole.cambioApprovato to parole.cambioApprovatoTesto
            risposta.cambio -> parole.cambioRifiutato to parole.cambioRifiutatoTesto
            risposta.approvata -> parole.approvata to parole.approvataTesto
            else -> parole.rifiutata to parole.rifiutataTesto
        }
        val titolo = nome?.let { formato.format(it) } ?: messaggio
        val perche = motivazione?.trim()?.takeIf { it.isNotEmpty() && !risposta.approvata }
            ?.let { parole.genitoreDice.format(it) }
        return titolo to listOfNotNull(testo, perche).joinToString("\n")
    }
}

/** (0.11) Le decisioni del genitore su una sessione (`esito` di `sessione_risposta`). */
object EsitiRispostaSessione {
    const val APPROVA = "approva"
    const val RIFIUTA = "rifiuta"
}
