package eu.stgm.pactum.figlio.sync

/**
 * (0.13) La coda degli eventi verso `POST /api/eventi`, in pacchi (logica
 * pura: chi manda è [manda], che restituisce il codice HTTP, 0 = niente rete).
 *
 * Di solito la coda parte tutta in una volta. Se il server risponde
 * `413 corpo_troppo_grande` (un corpo oltre 8 MB, contratto v3.6, "I corpi
 * delle richieste"), la coda si rimanda in pacchi più piccoli: metà, e ogni
 * metà ancora a metà, fino a un evento solo. Niente si perde: un pacco
 * arrivato esce dalla coda, uno non arrivato per la rete o per il server
 * resta e si riprova al giro dopo. Lo stesso pacco troppo grande non si
 * ripete mai: si divide. Un evento da solo oltre gli 8 MB non può arrivare
 * mai (un evento è di qualche KB): si toglie dalla coda e si conta in
 * [Esito.scartati], così non blocca per sempre quelli dopo.
 */
object PacchiEventi {

    const val TROPPO_GRANDE = 413

    /**
     * [consegnati] = arrivati al server (escono dalla coda); [scartati] =
     * troppo grandi anche da soli (escono anche loro); [tutti] = la coda è
     * vuota (niente è rimasto indietro per la rete o per il server).
     */
    data class Esito<T>(val consegnati: List<T>, val scartati: List<T>, val tutti: Boolean)

    suspend fun <T> consegna(eventi: List<T>, manda: suspend (List<T>) -> Int): Esito<T> {
        if (eventi.isEmpty()) return Esito(emptyList(), emptyList(), tutti = true)
        val consegnati = ArrayList<T>()
        val scartati = ArrayList<T>()
        // I pacchi da mandare, in ordine: si comincia dalla coda intera.
        val pacchi = ArrayDeque<List<T>>()
        pacchi.addLast(eventi)
        while (pacchi.isNotEmpty()) {
            val pacco = pacchi.removeFirst()
            val codice = manda(pacco)
            when {
                codice in 200..299 -> consegnati += pacco
                codice == TROPPO_GRANDE && pacco.size == 1 -> scartati += pacco
                codice == TROPPO_GRANDE -> {
                    // Diviso a metà, e le due metà prima del resto: l'ordine non cambia.
                    val meta = pacco.size / 2
                    pacchi.addFirst(pacco.subList(meta, pacco.size).toList())
                    pacchi.addFirst(pacco.subList(0, meta).toList())
                }
                // Rete, server fermo, un altro no: il resto aspetta il giro dopo.
                else -> return Esito(consegnati, scartati, tutti = false)
            }
        }
        return Esito(consegnati, scartati, tutti = true)
    }
}
