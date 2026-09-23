package eu.stgm.pactum.figlio.ui

import eu.stgm.pactum.figlio.dati.DirezioniProposta
import eu.stgm.pactum.figlio.dati.Regola
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Logica pura della proposta del genitore: su QUALE regola verte e come
// raccontarlo. Il confronto del server dice di quanto cambia ("−15 min al
// giorno rispetto ad ora"), non su cosa: il ragazzo deve sapere cosa accetta.
// Niente Android: le parole arrivano da strings.xml attraverso ParoleProposta,
// la descrizione di una regola dalla stessa funzione che la mostra ovunque.

/** Su cosa verte una proposta, risolta contro le regole del patto. */
sealed interface OggettoProposta {
    val regola: Regola

    /** Una modifica: la regola com'è ora e, se si sa, come diventa accettando. */
    data class Modifica(override val regola: Regola, val parametriDopo: JsonObject?) : OggettoProposta

    /** L'eliminazione: la regola che uscirebbe dal patto. */
    data class Eliminazione(override val regola: Regola) : OggettoProposta
}

/** Le frasi, da strings.xml. */
data class ParoleProposta(
    val senzaConfronto: String,
    /** "Ora: %1$s" */
    val ora: String,
    /** "Se accetti: %1$s" */
    val seAccetti: String,
    /** "Propone di togliere la regola: %1$s" */
    val togliere: String,
    /** (v3) Regola di un altro dispositivo: "Ora %1$s: %2$s" → "Ora sul computer: …" */
    val oraSu: String = "Ora %1\$s: %2\$s",
    /** (v3) "Propone di togliere la regola %1$s: %2$s" → "… la regola sul computer: …" */
    val togliereSu: String = "Propone di togliere la regola %1\$s: %2\$s",
)

/** La proposta raccontata: la frase in evidenza e le righe sotto. */
data class RaccontoProposta(val titolo: String, val righe: List<String>) {
    /** Tutto in un testo solo, una riga per pezzo (la notifica). */
    val testo: String get() = (listOf(titolo) + righe).joinToString("\n")
}

object TestoProposta {

    /**
     * La regola su cui verte la proposta, cercata per [regolaId] tra le regole
     * del patto; null se non c'è (eliminata nel frattempo, copia locale
     * indietro). Un'eliminazione arriva col marcatore `{"azione": "elimina"}`
     * nei parametri proposti e con direzione `elimina` (contratto-api.md).
     */
    fun oggetto(
        regolaId: Long,
        direzione: String?,
        parametriProposti: JsonObject?,
        regole: List<Regola>,
    ): OggettoProposta? {
        val regola = regole.firstOrNull { it.id == regolaId } ?: return null
        if (eliminazione(direzione, parametriProposti)) return OggettoProposta.Eliminazione(regola)
        // "Se accetti" solo con parametri veri e diversi da quelli di adesso.
        val dopo = parametriProposti?.takeIf { it.isNotEmpty() && it != regola.parametri }
        return OggettoProposta.Modifica(regola, dopo)
    }

    fun eliminazione(direzione: String?, parametriProposti: JsonObject?): Boolean =
        direzione == DirezioniProposta.ELIMINA ||
            (parametriProposti?.get("azione") as? JsonPrimitive)?.content == "elimina"

    /**
     * In evidenza resta il confronto del server; sotto, la regola com'è ora e
     * come diventa. Per l'eliminazione il confronto del server è una frase
     * fissa ("propone di eliminare la regola") che non dice quale: al suo posto
     * va la stessa frase CON la regola, così non si ripete due volte.
     *
     * [descrivi] è la descrizione delle regole che l'app usa ovunque, per la
     * regola e i parametri dati. (v3) [dispositivoDi] dice su quale ALTRO
     * dispositivo sta la regola ("sul computer"), null se è di questo telefono
     * o del figlio: allora la frase lo dice ("Ora sul computer: …"). Il
     * dispositivo si dice una volta, sulla regola di adesso: "Se accetti" parla
     * della stessa regola.
     */
    fun racconto(
        confronto: String?,
        oggetto: OggettoProposta?,
        parole: ParoleProposta,
        descrivi: (regola: Regola, parametri: JsonObject) -> String,
        dispositivoDi: (Regola) -> String? = { null },
    ): RaccontoProposta {
        val titolo = confronto?.ifBlank { null } ?: parole.senzaConfronto
        if (oggetto == null) return RaccontoProposta(titolo, emptyList())
        val regola = oggetto.regola
        val su = dispositivoDi(regola)
        val adesso = descrivi(regola, regola.parametri)
        return when (oggetto) {
            is OggettoProposta.Eliminazione -> RaccontoProposta(
                if (su == null) parole.togliere.format(adesso) else parole.togliereSu.format(su, adesso),
                emptyList(),
            )
            is OggettoProposta.Modifica -> RaccontoProposta(
                titolo,
                listOfNotNull(
                    if (su == null) parole.ora.format(adesso) else parole.oraSu.format(su, adesso),
                    oggetto.parametriDopo?.let { parole.seAccetti.format(descrivi(regola, it)) },
                ),
            )
        }
    }
}
