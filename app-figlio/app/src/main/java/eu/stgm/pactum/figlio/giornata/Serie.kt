package eu.stgm.pactum.figlio.giornata

import eu.stgm.pactum.design.GiornoPatto
import eu.stgm.pactum.design.Segnale
import eu.stgm.pactum.design.serieDiGiorni
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// Logica pura (niente Android): si prova con JUnit semplice.

/**
 * La serie ricordata sul telefono: quanti giorni di fila, e l'ultimo giorno
 * contato. Serve perché la striscia del server è lunga 8 giorni: da sola non
 * potrebbe mai dire "12 giorni di fila".
 */
data class SerieSalvata(val fine: String, val lunghezza: Int)

/**
 * Serie e record del figlio (tavola rotonda D3/C3/C6). Si calcolano SOLO dalla
 * striscia del server, mai dalle misure del telefono, e non viaggiano mai:
 * nessun campo nel contratto, il genitore non li vede.
 */
object Serie {

    /**
     * La serie di oggi: [serieDiGiorni] sulla striscia, allungata con la
     * memoria locale quando la fila di giorni mantenuti arriva fino al primo
     * giorno della finestra (e quindi il suo inizio sta fuori dagli 8 giorni).
     *
     * La memoria vale solo se si attacca alla finestra: se l'ultimo giorno
     * ricordato è più vecchio del giorno prima della finestra, in mezzo ci sono
     * giorni che nessuno ha visto, e un giorno che non si conosce non si conta.
     * Se invece la striscia è più VECCHIA della memoria (una copia locale letta
     * senza rete), la memoria sa di più: si restituisce lei, intera.
     * Restituisce null quando la serie è zero. Questa è la serie da MOSTRARE;
     * quello che si tiene sul telefono lo decide [memoria].
     */
    fun calcola(giorni: List<GiornoPatto>, salvata: SerieSalvata?): SerieSalvata? {
        // Come serieDiGiorni: oggi ancora senza dati non rompe la serie, e non la allunga.
        val contati = giorniContati(giorni)
        val ultimo = contati.lastOrNull()?.let { data(it.data) }
        val fineRicordata = salvata?.let { data(it.fine) }
        if (salvata != null && ultimo != null && fineRicordata != null && fineRicordata.isAfter(ultimo)) {
            return salvata
        }
        val corta = serieDiGiorni(giorni)
        if (corta == 0) return null
        val daSola = SerieSalvata(contati.last().data, corta)
        if (salvata == null || ultimo == null || fineRicordata == null || corta < contati.size) return daSola

        val primo = data(contati.first().data) ?: return daSola
        if (fineRicordata.isBefore(primo.minusDays(1))) return daSola
        // Dal giorno ricordato a oggi la finestra è tutta mantenuta: si aggiungono
        // i giorni nuovi. Il massimo protegge da una memoria più corta del vero
        // (un giorno grigio diventato verde quando è arrivata la sua fotografia).
        val giorniNuovi = ChronoUnit.DAYS.between(fineRicordata, ultimo).toInt()
        return SerieSalvata(daSola.fine, maxOf(corta, salvata.lunghezza + giorniNuovi))
    }

    /**
     * La serie da tenere sul telefono dopo aver letto [giorni]. Di solito è
     * quella di [calcola]; la differenza è un giorno GRIGIO dopo la fine
     * ricordata. Un grigio non è una rottura, è un "non si sa ancora": di
     * solito le fotografie di quel giorno devono ancora arrivare, e quando
     * arrivano diventa verde. Se la memoria si cancellasse lì, la serie oltre
     * gli 8 giorni della striscia sarebbe persa per sempre. Quindi:
     *  - un FUORI_REGOLA dopo la fine ricordata rompe la serie: vale [calcola];
     *  - solo grigi dopo la fine ricordata: la memoria resta com'è, e si
     *    riattacca da sola quando il grigio diventa verde. Se resta grigio, esce
     *    dalla finestra e la memoria non si attacca più (niente giorni inventati);
     *  - una memoria che non si può più attaccare alla finestra non serve più.
     */
    fun memoria(giorni: List<GiornoPatto>, salvata: SerieSalvata?): SerieSalvata? {
        val corrente = calcola(giorni, salvata)
        if (salvata == null || corrente == salvata) return corrente
        val contati = giorniContati(giorni)
        val fineRicordata = data(salvata.fine) ?: return corrente
        val primo = contati.firstOrNull()?.let { data(it.data) } ?: return salvata
        if (fineRicordata.isBefore(primo.minusDays(1))) return corrente
        val dopo = contati.filter { g -> data(g.data)?.isAfter(fineRicordata) == true }
        return when {
            dopo.any { it.segnale == Segnale.FUORI_REGOLA } -> corrente
            dopo.any { it.segnale == Segnale.NESSUN_DATO } -> salvata
            else -> corrente
        }
    }

    /** Il record non scende MAI: quando la serie si rompe resta scritto il migliore. */
    fun record(precedente: Int, serie: Int): Int = maxOf(precedente, serie, 0)

    /**
     * La serie contando anche OGGI come mantenuto: serve alla chiusura della
     * sera ("Nono giorno."), quando le misure del telefono dicono che la
     * giornata è andata. Null se la striscia non arriva a ieri: senza gli ultimi
     * giorni il numero sarebbe inventato.
     */
    fun conOggi(giorni: List<GiornoPatto>, salvata: SerieSalvata?, oggi: String): Int? {
        val giorno = data(oggi) ?: return null
        val ultimo = giorni.lastOrNull()?.let { data(it.data) } ?: return null
        val completata = when (ultimo) {
            giorno -> giorni.dropLast(1) + GiornoPatto(oggi, Segnale.MANTENUTA)
            giorno.minusDays(1) -> giorni + GiornoPatto(oggi, Segnale.MANTENUTA)
            else -> return null
        }
        return calcola(completata, salvata)?.lunghezza
    }

    /** I giorni che contano: oggi ancora senza dati resta fuori, come in serieDiGiorni. */
    private fun giorniContati(giorni: List<GiornoPatto>): List<GiornoPatto> =
        if (giorni.lastOrNull()?.segnale == Segnale.NESSUN_DATO) giorni.dropLast(1) else giorni

    private fun data(testo: String): LocalDate? = runCatching { LocalDate.parse(testo) }.getOrNull()
}
