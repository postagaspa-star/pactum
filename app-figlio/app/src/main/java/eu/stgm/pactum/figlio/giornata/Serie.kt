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
     * Restituisce null quando la serie è zero (la memoria va dimenticata).
     */
    fun calcola(giorni: List<GiornoPatto>, salvata: SerieSalvata?): SerieSalvata? {
        val corta = serieDiGiorni(giorni)
        if (corta == 0) return null
        // Come serieDiGiorni: oggi ancora senza dati non rompe la serie, e non la allunga.
        val contati = if (giorni.last().segnale == Segnale.NESSUN_DATO) giorni.dropLast(1) else giorni
        val fine = contati.last().data
        val daSola = SerieSalvata(fine, corta)
        if (salvata == null || corta < contati.size) return daSola

        val primo = data(contati.first().data) ?: return daSola
        val ultimo = data(fine) ?: return daSola
        val fineRicordata = data(salvata.fine) ?: return daSola
        if (fineRicordata.isBefore(primo.minusDays(1)) || fineRicordata.isAfter(ultimo)) return daSola
        // Dal giorno ricordato a oggi la finestra è tutta mantenuta: si aggiungono
        // i giorni nuovi. Il massimo protegge da una memoria più corta del vero
        // (un giorno grigio diventato verde quando è arrivata la sua fotografia).
        val giorniNuovi = ChronoUnit.DAYS.between(fineRicordata, ultimo).toInt()
        return SerieSalvata(fine, maxOf(corta, salvata.lunghezza + giorniNuovi))
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

    private fun data(testo: String): LocalDate? = runCatching { LocalDate.parse(testo) }.getOrNull()
}
