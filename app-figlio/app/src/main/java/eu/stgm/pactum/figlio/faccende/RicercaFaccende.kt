package eu.stgm.pactum.figlio.faccende

/**
 * (0.17, contratto v3.9) La ricerca nei lavori di casa (logica pura):
 * `GET /api/faccende?cerca=…` su tutta la storia, dalla più recente, al
 * massimo 50. La pagina aspetta [ATTESA_MS] dopo l'ultima lettera, e una
 * risposta a una domanda vecchia (l'utente ha già scritto altro) non conta
 * ([Sequenza]).
 */
object RicercaFaccende {

    /** Quanto aspettare dopo l'ultima lettera prima di chiedere. */
    const val ATTESA_MS = 300L

    /** Il contratto: da 1 a 80 caratteri, dopo aver tolto gli spazi ai bordi. */
    const val MASSIMO_CARATTERI = 80

    /** Il testo da cercare: senza spazi ai bordi, al massimo 80 caratteri; null se vuoto (niente ricerca). */
    fun testo(scritto: String): String? = scritto.trim().take(MASSIMO_CARATTERI).trim().takeIf { it.isNotEmpty() }

    /** Com'è andata una ricerca. */
    sealed interface Esito {
        /** Trovati (anche nessuno); [altre] = ce ne sono più di quelli mostrati. */
        data class Trovati(val faccende: List<FaccendaLocale>, val altre: Boolean) : Esito

        /** Il server non sa cercare (prima della v3.9): va aggiornato. */
        data object ServerVecchio : Esito

        data object SenzaRete : Esito

        /** 401: questo telefono non è più collegato. */
        data object Scollegato : Esito

        data object Errore : Esito
    }

    /**
     * La risposta: [corpo] (null se non è un 2xx) e il [codice] HTTP (0 =
     * senza rete). Un server prima della v3.9 ignora `cerca` e manda gli ultimi
     * 30 giorni senza `altre`: non è una ricerca, e lo si dice (non si mostrano
     * risultati sbagliati). Un 404/405 è un server ancora più vecchio.
     */
    fun esito(corpo: String?, codice: Int): Esito = when {
        codice == 0 -> Esito.SenzaRete
        codice == 401 -> Esito.Scollegato
        EsitiFaccende.serverVecchio(codice) -> Esito.ServerVecchio
        codice !in 200..299 -> Esito.Errore
        else -> when (val r = LetturaFaccende.ricerca(corpo)) {
            null -> Esito.Errore
            else -> r.altre?.let { Esito.Trovati(r.faccende, it) } ?: Esito.ServerVecchio
        }
    }

    /**
     * Il numero d'ordine delle domande: vale solo la risposta all'ultima
     * ([valida]). Così una risposta lenta a "le" non copre quella a "letto".
     */
    class Sequenza {
        private var ultima = 0L

        @Synchronized
        fun nuova(): Long = ++ultima

        @Synchronized
        fun valida(numero: Long): Boolean = numero == ultima

        /** Nessuna domanda vale più (la ricerca è stata cancellata). */
        @Synchronized
        fun annulla() {
            ultima++
        }
    }
}
