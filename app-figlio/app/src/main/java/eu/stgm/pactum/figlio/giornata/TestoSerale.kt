package eu.stgm.pactum.figlio.giornata

import java.time.LocalTime
import java.time.ZonedDateTime

// Logica pura della chiusura della sera: cosa dire e quando. Niente Android,
// le parole arrivano da strings.xml attraverso ParoleSerale.

/** Una regola uscita oggi, già tradotta: tipo di regola, nome leggibile, minuti. */
data class FuoriOggi(val tipo: TipoFuori, val nome: String?, val minuti: Int)

enum class TipoFuori { LIMITE, FASCIA }

/** Com'è andata la giornata, in una delle quattro frasi possibili. */
sealed interface Chiusura {
    /** Dentro tutte le regole; [serie] contando oggi, null se non si può sapere. */
    data class Dentro(val serie: Int?) : Chiusura

    /** Oltre un limite di tempo: il più grande, e quante altre regole fuori. */
    data class OltreLimite(val nome: String, val minuti: Int, val altre: Int) : Chiusura

    /** Telefono usato in una fascia chiusa, e quante altre regole fuori. */
    data class NellaFascia(val minuti: Int, val altre: Int) : Chiusura

    /** Il server sa che oggi una regola non ha tenuto (es. dichiarata) e il telefono no. */
    data object Fuori : Chiusura
}

/** Le frasi, da strings.xml. `durata` scrive i minuti come il resto dell'app. */
data class ParoleSerale(
    val dentro: String,
    val giornoInParole: String,
    val ordinali: List<String>,
    val giornoInCifre: String,
    val oltre: String,
    val fascia: String,
    val fuori: String,
    val unAltraRegola: String,
    val altreRegole: String,
    val domani: String,
    val durata: (Int) -> String,
)

object TestoSerale {

    /**
     * La giornata in una frase. Prima i fatti misurati sul telefono (un limite
     * superato dice di quanto e dove), poi quello che sa solo il server; se
     * niente è uscito, la giornata è dentro.
     */
    fun chiusura(fuori: List<FuoriOggi>, rossoSulServer: Boolean, serieConOggi: Int?): Chiusura {
        val limite = fuori.filter { it.tipo == TipoFuori.LIMITE }.maxByOrNull { it.minuti }
        if (limite != null) return Chiusura.OltreLimite(limite.nome ?: "?", limite.minuti, fuori.size - 1)
        val fascia = fuori.filter { it.tipo == TipoFuori.FASCIA }.maxByOrNull { it.minuti }
        if (fascia != null) return Chiusura.NellaFascia(fascia.minuti, fuori.size - 1)
        if (rossoSulServer) return Chiusura.Fuori
        return Chiusura.Dentro(serieConOggi?.takeIf { it > 0 })
    }

    fun testo(chiusura: Chiusura, parole: ParoleSerale): String = when (chiusura) {
        is Chiusura.Dentro -> listOfNotNull(
            parole.dentro,
            chiusura.serie?.let { ordinale(it, parole) },
        ).joinToString(" ")
        is Chiusura.OltreLimite -> listOfNotNull(
            parole.oltre.format(parole.durata(chiusura.minuti), chiusura.nome),
            altre(chiusura.altre, parole),
            parole.domani,
        ).joinToString(" ")
        is Chiusura.NellaFascia -> listOfNotNull(
            parole.fascia.format(parole.durata(chiusura.minuti)),
            altre(chiusura.altre, parole),
            parole.domani,
        ).joinToString(" ")
        Chiusura.Fuori -> "${parole.fuori} ${parole.domani}"
    }

    /** "Nono giorno." in parole fin dove ci sono le parole, poi "11° giorno.". */
    fun ordinale(n: Int, parole: ParoleSerale): String =
        parole.ordinali.getOrNull(n - 1)?.let { parole.giornoInParole.format(it) }
            ?: parole.giornoInCifre.format(n)

    private fun altre(n: Int, parole: ParoleSerale): String? = when {
        n <= 0 -> null
        n == 1 -> parole.unAltraRegola
        else -> parole.altreRegole.format(n)
    }

    /**
     * Il prossimo momento in cui guardare: oggi all'ora scelta se non è ancora
     * passata, altrimenti domani. Con ZonedDateTime il cambio d'ora legale non
     * sposta l'appuntamento.
     */
    fun prossimoControllo(adesso: ZonedDateTime, ora: LocalTime): ZonedDateTime {
        val oggi = adesso.toLocalDate().atTime(ora).atZone(adesso.zone)
        return if (oggi.isAfter(adesso)) oggi else adesso.toLocalDate().plusDays(1).atTime(ora).atZone(adesso.zone)
    }
}
