package eu.stgm.pactum.figlio.ui

/**
 * (0.15) Chi ha fatto partire quale collegamento (per numero della corsa,
 * CorsaCollegamento.ultimoAvviato). Il collegamento gira nel processo e il suo
 * esito resta finché qualcuno non lo prende: senza sapere da dove era partito,
 * il modulo delle Impostazioni prendeva l'esito lasciato dal passo Collega del
 * primo avvio (sparito appena salvato il collegamento) e si richiudeva da solo
 * al primo "Cambia". Così ogni schermata prende solo i suoi.
 * Niente Android: si prova con JUnit (OriginiCollegamentoTest).
 */
class RegistroOrigini(private val massimo: Int = 20) {
    private val origini = LinkedHashMap<Long, String>()

    /** Il collegamento [numero] l'ha fatto partire [origine]. */
    @Synchronized
    fun segna(numero: Long, origine: String) {
        origini[numero] = origine
        // Se ne tengono pochi: contano solo gli ultimi.
        while (origini.size > massimo) origini.remove(origini.keys.first())
    }

    /** L'esito del collegamento [numero] è di [origine]? Uno mai segnato non è di nessuno. */
    @Synchronized
    fun eDi(numero: Long, origine: String): Boolean = origini[numero] == origine
}

/** Le origini dei collegamenti di questo processo. */
object OriginiCollegamento {
    const val PRIMO_AVVIO = "primo-avvio"
    const val PRIMA_REGOLA = "prima-regola"
    const val IMPOSTAZIONI = "impostazioni"

    private val registro = RegistroOrigini()

    fun segna(numero: Long, origine: String) = registro.segna(numero, origine)

    fun eDi(numero: Long, origine: String): Boolean = registro.eDi(numero, origine)
}
