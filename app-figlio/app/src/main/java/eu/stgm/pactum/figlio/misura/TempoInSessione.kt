package eu.stgm.pactum.figlio.misura

/**
 * (0.11) Un periodo di Sessione (contratto v3.5, "Studio", "Lavoro": NON i
 * pezzi di primo piano di [Sessione]): da [inizio] a [fine] (epoch ms, fine
 * esclusa) il tempo delle app che [nellaSessione] ammette non conta. Le app
 * fuori dalla lista contano come sempre.
 */
data class PeriodoSessione(val inizio: Long, val fine: Long, val nellaSessione: (String) -> Boolean)

/**
 * (0.11) Il tempo passato in una Sessione non conta (logica pura): non entra
 * nei minuti per app, nelle categorie, nel totale, nei limiti né nelle fasce
 * orarie. Si taglia alla fonte, sui pezzi di primo piano del giorno: tutto
 * quello che viene dopo (UsoContato, la fotografia, la sentinella, la
 * schermata Oggi, la chiusura della sera) li vede già tagliati.
 *
 * Un pezzo che scavalca l'inizio o la fine di una sessione si spezza: conta la
 * parte fuori, non conta quella dentro. A mezzanotte non serve niente di
 * speciale: i pezzi sono già tagliati al giorno, i periodi sono istanti.
 */
object TempoInSessione {

    /** I pezzi che contano e quelli passati in sessione. */
    data class Separati(val contati: List<Sessione>, val inSessione: List<Sessione>)

    fun separa(pezzi: List<Sessione>, periodi: List<PeriodoSessione>): Separati {
        if (periodi.isEmpty()) return Separati(pezzi, emptyList())
        val contati = ArrayList<Sessione>(pezzi.size)
        val dentro = ArrayList<Sessione>()
        for (pezzo in pezzi) {
            val coperti = periodi
                .filter { it.fine > pezzo.inizio && it.inizio < pezzo.fine && it.fine > it.inizio }
                .filter { it.nellaSessione(pezzo.pacchetto) }
                .map { maxOf(it.inizio, pezzo.inizio) to minOf(it.fine, pezzo.fine) }
            if (coperti.isEmpty()) {
                contati += pezzo
                continue
            }
            var cursore = pezzo.inizio
            for ((da, a) in unione(coperti)) {
                if (da > cursore) contati += Sessione(pezzo.pacchetto, cursore, da)
                dentro += Sessione(pezzo.pacchetto, da, a)
                cursore = a
            }
            if (pezzo.fine > cursore) contati += Sessione(pezzo.pacchetto, cursore, pezzo.fine)
        }
        return Separati(contati, dentro)
    }

    /**
     * La lettura di un giorno senza il tempo in sessione. [periodi] = quelli
     * che toccano il giorno: se ce n'è almeno uno il giorno è "con sessioni".
     * [note] = il telefono sa quali sessioni ci sono state (v. LetturaGiorno).
     */
    fun togli(lettura: LetturaGiorno, periodi: List<PeriodoSessione>, note: Boolean = true): LetturaGiorno {
        val separati = separa(lettura.sessioni, periodi)
        return LetturaGiorno(
            giorno = lettura.giorno,
            fine = lettura.fine,
            sessioni = separati.contati,
            inSessione = lettura.inSessione + separati.inSessione,
            conSessioni = lettura.conSessioni || periodi.isNotEmpty(),
            sessioniNote = note,
        )
    }

    /** Gli intervalli sovrapposti fusi in uno: un minuto non si toglie due volte. */
    private fun unione(intervalli: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        val ordinati = intervalli.filter { it.second > it.first }.sortedBy { it.first }
        val fusi = ArrayList<Pair<Long, Long>>(ordinati.size)
        for ((da, a) in ordinati) {
            val ultimo = fusi.lastOrNull()
            if (ultimo != null && da <= ultimo.second) {
                fusi[fusi.size - 1] = ultimo.first to maxOf(ultimo.second, a)
            } else {
                fusi += da to a
            }
        }
        return fusi
    }
}
