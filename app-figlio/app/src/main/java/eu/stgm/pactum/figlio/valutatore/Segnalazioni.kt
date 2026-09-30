package eu.stgm.pactum.figlio.valutatore

import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.TipiRegola
import java.util.UUID

/**
 * Cosa fare degli sforamenti visti adesso (logica pura: la applica
 * SentinellaPatto sotto il suo mutex, la memoria su disco sta in Impostazioni).
 *
 * Il dedup è quello di sempre (contratto: al massimo UNO sforamento per regola
 * per giorno): chiave "regolaId:giorno", col giorno del telefono per i limiti e
 * il giorno di ancoraggio per le fasce. L'avviso a tutto schermo (0.9) non ha
 * regole sue: segue lo stesso dedup della notifica.
 */
object Segnalazioni {

    /** La chiave di dedup su disco: "regolaId:giorno". */
    fun chiave(regolaId: Long, giorno: String): String = "$regolaId:$giorno"

    /**
     * [nuovi]: evento nel registro, notifica, e parte subito verso il server.
     * [aTuttoSchermo]: gli stessi, ma solo se l'avviso a tutto schermo può
     * partire; altrimenti vuota e resta la sola notifica.
     */
    data class Decisione(val nuovi: List<Sforamento>, val aTuttoSchermo: List<Sforamento>)

    /**
     * Nuovo = la sua chiave non è tra [giaSegnalati] (né già vista in questo
     * stesso giro). Una volta segnalato, per quel giorno non torna: nemmeno se
     * un bonus alza il limite e il limite nuovo viene superato di nuovo.
     */
    fun nuovi(sforamenti: List<Sforamento>, giornoTelefono: String, giaSegnalati: Set<String>): List<Sforamento> {
        val viste = HashSet(giaSegnalati)
        return sforamenti.filter {
            viste.add(chiave(it.regolaId, Valutatore.giornoDelloSforamento(it, giornoTelefono)))
        }
    }

    /**
     * A tutto schermo solo con "Mostra sopra le altre app" ([mostraSopra]),
     * fuori da una chiamata ([inChiamata]: coprirebbe la chiamata) e per un
     * giorno in corso ([giornoPassato]: lo sforamento di ieri visto dopo
     * mezzanotte è già storia, basta la notifica).
     */
    fun decidi(
        sforamenti: List<Sforamento>,
        giornoTelefono: String,
        giaSegnalati: Set<String>,
        mostraSopra: Boolean,
        inChiamata: Boolean = false,
        giornoPassato: Boolean = false,
    ): Decisione {
        val nuovi = nuovi(sforamenti, giornoTelefono, giaSegnalati)
        val schermo = mostraSopra && !inChiamata && !giornoPassato
        return Decisione(nuovi = nuovi, aTuttoSchermo = if (schermo) nuovi else emptyList())
    }

    /**
     * (0.9) L'id dell'evento `sforamento`: un UUID (contratto: l'id è un UUID)
     * ricavato da dispositivo + regola + giorno, sempre lo stesso per lo stesso
     * fatto. Se il processo muore tra l'accodamento e il dedup, lo sforamento
     * rinasce con lo stesso id e il server scarta da solo il doppione (l'id è
     * la chiave di idempotenza, unica su tutto il server: per questo c'è il
     * dispositivo).
     */
    fun idEvento(dispositivo: String, regolaId: Long, giorno: String): String =
        UUID.nameUUIDFromBytes("pactum/sforamento/$dispositivo/$regolaId/$giorno".toByteArray(Charsets.UTF_8))
            .toString()

    /** C'è qualcosa da guardare: un limite di tempo o una fascia oraria attivi. */
    fun daGuardare(regole: List<Regola>): Boolean = regole.any {
        it.attiva && (it.tipo == TipiRegola.LIMITE_TEMPO || it.tipo == TipiRegola.FASCIA_ORARIA)
    }
}
