package eu.stgm.pactum.figlio.ui

import eu.stgm.pactum.figlio.permessi.StatoPermessi

/**
 * (0.15) Le piccole scelte di presentazione della schermata Oggi e delle
 * regole, senza Android: cosa sta in cima a Oggi, quali permessi mancano, i
 * giorni di una fascia detti in parole. Si provano con JUnit (RiepiloghiTest).
 */

/** La card piena in cima a Oggi: al massimo una. */
enum class CardCima { BLOCCO, SESSIONE }

/**
 * Cosa sta in cima a Oggi. [card] = la sola card piena (null = nessuna);
 * [sessioneInRiga] = la sessione in corso detta in una riga, perché il blocco
 * dei lavori di casa ha vinto la card; [bloccoProgrammatoInRiga] = "alle 16:00
 * il telefono si blocca se…", sempre una riga.
 */
data class CimaOggi(
    val card: CardCima?,
    val sessioneInRiga: Boolean,
    val bloccoProgrammatoInRiga: Boolean,
)

object Cima {
    /**
     * Mai due card piene insieme: il blocco vince, e la sessione diventa una
     * riga. Un blocco programmato (non ancora partito, con lavori da fare) è
     * sempre e solo una riga.
     */
    fun di(bloccato: Boolean, sessioneInCorso: Boolean, bloccoProgrammato: Boolean): CimaOggi = when {
        bloccato -> CimaOggi(CardCima.BLOCCO, sessioneInRiga = sessioneInCorso, bloccoProgrammatoInRiga = false)
        sessioneInCorso -> CimaOggi(CardCima.SESSIONE, sessioneInRiga = false, bloccoProgrammatoInRiga = bloccoProgrammato)
        else -> CimaOggi(null, sessioneInRiga = false, bloccoProgrammatoInRiga = bloccoProgrammato)
    }
}

/** I quattro permessi di Pactum, nell'ordine in cui si chiedono. */
enum class Permesso { USO, BATTERIA, NOTIFICHE, MOSTRA_SOPRA }

object Permessi {
    /** I permessi che mancano, nell'ordine dei passi. */
    fun mancanti(stato: StatoPermessi): List<Permesso> = buildList {
        if (!stato.accessoUso) add(Permesso.USO)
        if (!stato.esenzioneBatteria) add(Permesso.BATTERIA)
        if (!stato.notifiche) add(Permesso.NOTIFICHE)
        if (!stato.mostraSopra) add(Permesso.MOSTRA_SOPRA)
    }

    fun concesso(stato: StatoPermessi, permesso: Permesso): Boolean = permesso !in mancanti(stato)
}

/** Le parole per i giorni di una fascia: "ogni giorno", "dal lunedì al venerdì". */
data class ParoleGiorniFascia(val ogniGiorno: String, val feriali: String, val separatore: String = ", ")

object FraseGiorni {
    /** I giorni come li scrive il contratto, nel loro ordine. */
    val ORDINE = listOf("lun", "mar", "mer", "gio", "ven", "sab", "dom")

    /**
     * I giorni di una fascia in parole: tutti e sette = "ogni giorno", dal
     * lunedì al venerdì = "dal lunedì al venerdì", altrimenti l'elenco in
     * ordine ("lun, mer, ven"). Solo presentazione: la regola resta quella.
     * Vuoto = stringa vuota (chi chiama decide cosa dire).
     */
    fun di(giorni: List<String>, parole: ParoleGiorniFascia): String {
        val insieme = giorni.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (insieme.isEmpty()) return ""
        if (insieme == ORDINE.toSet()) return parole.ogniGiorno
        if (insieme == ORDINE.take(5).toSet()) return parole.feriali
        val noti = ORDINE.filter { it in insieme }
        val altri = insieme.filter { it !in ORDINE }.sorted()
        return (noti + altri).joinToString(parole.separatore)
    }
}
