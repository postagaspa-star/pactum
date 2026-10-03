package eu.stgm.pactum.figlio.faccende

/**
 * (0.13) Cosa mostra la pagina Faccende (logica pura): le faccende da fare e
 * quelle chiuse (fatte o annullate negli ultimi 30 giorni), da due fonti, il
 * blocco (che si chiede ogni minuto) e l'elenco intero (che si chiede
 * aprendo la pagina): vale la più fresca.
 */
object VistaFaccende {

    /**
     * La pagina Faccende viene prima di tutto il resto (i permessi, "Cosa vede
     * il genitore", la prima regola): col telefono bloccato, con faccende da
     * fare, o arrivando da "Apri Pactum" o da una notifica di faccende. Le
     * foto non hanno bisogno né di regole né dell'accesso all'uso.
     */
    fun primaDelResto(bloccato: Boolean, memoria: MemoriaBlocco, arrivoDalleFaccende: Boolean): Boolean =
        bloccato || memoria.daFare.isNotEmpty() || arrivoDalleFaccende

    /** Le faccende da fare, dalla più vecchia. */
    fun daFare(memoria: MemoriaBlocco): List<FaccendaLocale> {
        val elenco = memoria.elenco.associateBy { it.id }
        val lista = if (memoria.bloccoPiuFresco || memoria.elenco.isEmpty()) {
            memoria.daFare.map { d ->
                val e = elenco[d.id]
                FaccendaLocale(
                    id = d.id,
                    titolo = d.titolo,
                    nota = d.nota,
                    stato = StatiFaccenda.DA_FARE,
                    bloccoDa = d.bloccoDa,
                    creataIl = e?.creataIl,
                    genitore = d.genitore ?: e?.genitore,
                    bocciature = d.bocciature,
                    ultimaBocciatura = d.ultimaBocciatura,
                )
            }
        } else {
            memoria.elenco.filter { it.daFare }
        }
        return lista.sortedWith(compareBy<FaccendaLocale>({ it.creataIl ?: Long.MAX_VALUE }, { it.id }))
    }

    /** Le fatte e le annullate, dalla più recente; mai una che adesso è da fare. */
    fun chiuse(memoria: MemoriaBlocco): List<FaccendaLocale> {
        val aperte = daFare(memoria).mapTo(HashSet()) { it.id }
        return memoria.elenco
            .filter { (it.fatta || it.annullata) && it.id !in aperte }
            .sortedWith(compareByDescending<FaccendaLocale> { it.chiusaIl ?: it.creataIl ?: 0L }.thenByDescending { it.id })
    }

    /** A che punto è la foto di una faccenda da fare, sul telefono. */
    enum class Foto { NESSUNA, IN_CODA, MANDATA, RIFIUTATA }

    fun foto(faccenda: FaccendaLocale, coda: MemoriaCodaFoto): Foto {
        val voce = coda.di(faccenda.id) ?: return Foto.NESSUNA
        // Una foto di prima di una bocciatura non conta più.
        if (faccenda.bocciature > voce.bocciature) return Foto.NESSUNA
        return when (voce.stato) {
            StatiFoto.IN_CODA -> Foto.IN_CODA
            StatiFoto.MANDATA -> Foto.MANDATA
            StatiFoto.RIFIUTATA -> Foto.RIFIUTATA
            else -> Foto.NESSUNA
        }
    }
}
