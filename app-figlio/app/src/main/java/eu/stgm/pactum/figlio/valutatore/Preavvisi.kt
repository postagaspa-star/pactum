package eu.stgm.pactum.figlio.valutatore

import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.TipiRegola
import eu.stgm.pactum.figlio.misura.LetturaGiorno
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId

/**
 * (0.12) Un preavviso "il tempo sta per finire": alla regola [regolaId]
 * mancano [minutiMancanti] minuti al limite di oggi ([limiteEfficace] =
 * minuti_al_giorno + bonus di oggi). [soglia] = la soglia raggiunta (5 o 1);
 * [soglieRaggiunte] = tutte quelle raggiunte adesso, da segnare come dette:
 * se si salta da 6 minuti a 40 secondi arriva solo quello di 1 minuto.
 */
data class Preavviso(
    val regolaId: Long,
    val chiave: String,
    val soglia: Int,
    val minutiMancanti: Int,
    val limiteEfficace: Int,
    val soglieRaggiunte: List<Int>,
)

/**
 * (0.12) Un'app in primo piano adesso. [contaTra] = fra quanti ms il suo tempo
 * comincia a contare: 0 se conta già, la fine della Sessione se è una sua app
 * (finché la sessione dura il suo tempo non conta, come nella 0.11).
 */
data class AppDavanti(val pacchetto: String, val contaTra: Long = 0L)

/**
 * (0.12) "Il tempo sta per finire" (logica pura: la applica SentinellaPatto
 * sotto il suo mutex, la memoria su disco sta in Impostazioni).
 *
 * Per ogni limite_tempo di questo telefono (un'app, una categoria, "totale"):
 * quando a (limite + bonus di oggi) − uso mancano 5 minuti o meno, un
 * preavviso; quando manca 1 minuto o meno, un secondo. Al secondo, non al
 * minuto: l'uso si conta in millisecondi, con le stesse app dei limiti (niente
 * Home, niente Pactum, niente tempo nelle app di una Sessione in corso).
 *
 * Una volta per regola, per giorno, per soglia, per limite: se un bonus alza
 * il limite, le soglie del limite nuovo valgono di nuovo. Mai a limite già
 * superato: lì c'è lo sforamento, con la sua notifica. Mai per un limite
 * piccolo (1-5 minuti) a uso zero: solo se un'app della regola è davanti o
 * oggi è già stata usata. Un preavviso che non vale più (un bonus che riporta
 * sopra i 5 minuti, una regola tolta) si toglie dalla tendina; a mezzanotte
 * se ne va da solo.
 */
object Preavvisi {

    /** Le soglie, dalla più lontana alla più vicina: 5 minuti, poi 1. */
    val SOGLIE_MINUTI = listOf(5, 1)

    private const val MINUTO_MS = 60_000L

    /** La chiave del dedup su disco: "regolaId:giorno:limite:soglia". */
    fun chiave(regolaId: Long, giorno: String, limiteEfficace: Int, soglia: Int): String =
        "$regolaId:$giorno:$limiteEfficace:$soglia"

    /** Una limite_tempo vista adesso, con il tempo che resta (> 0). */
    private class Resto(val regola: Regola, val chiave: String, val limite: Int, val mancaMs: Long)

    /**
     * Null se non c'è niente da preavvisare: non è un limite di tempo attivo,
     * ha parametri illeggibili, è già superata (come la conta il valutatore) o
     * il tempo è finito.
     */
    private fun resto(regola: Regola, bonusOggiPerRegola: Map<String, Int>, indice: IndiceUso): Resto? {
        if (!regola.attiva || regola.tipo != TipiRegola.LIMITE_TEMPO) return null
        val chiave = (regola.parametri["app_o_categoria"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: return null
        val limite = Valutatore.limiteEfficace(regola, bonusOggiPerRegola) ?: return null
        // Già oltre il limite: c'è lo sforamento, non il preavviso.
        if (indice.minuti(chiave) > limite) return null
        val manca = limite * MINUTO_MS - indice.millis(chiave)
        if (manca <= 0) return null
        return Resto(regola, chiave, limite, manca)
    }

    /**
     * I preavvisi da dare adesso (uno per regola al massimo: la soglia più
     * vicina raggiunta). [davanti] = le app in primo piano: un limite piccolo
     * a uso zero si preavvisa solo quando una sua app è davanti.
     */
    fun daDare(
        regole: List<Regola>,
        bonusOggiPerRegola: Map<String, Int>,
        indice: IndiceUso,
        giorno: String,
        giaFatti: Set<String>,
        davanti: List<AppDavanti> = emptyList(),
    ): List<Preavviso> = regole.mapNotNull { regola ->
        val r = resto(regola, bonusOggiPerRegola, indice) ?: return@mapNotNull null
        // Uso zero e nessuna sua app davanti (un limite di 3 minuti, appena sveglio): niente.
        val inUso = indice.millis(r.chiave) > 0 || davanti.any { it.contaTra <= 0 && indice.cade(r.chiave, it.pacchetto) }
        if (!inUso) return@mapNotNull null
        val raggiunte = SOGLIE_MINUTI.filter { r.mancaMs <= it * MINUTO_MS }
        val soglia = raggiunte.minOrNull() ?: return@mapNotNull null
        if (chiave(regola.id, giorno, r.limite, soglia) in giaFatti) return@mapNotNull null
        // "Mancano 5 minuti", "manca 1 minuto": arrotondato in su, mai oltre la soglia.
        val minuti = ((r.mancaMs + MINUTO_MS - 1) / MINUTO_MS).toInt().coerceIn(1, soglia)
        Preavviso(regola.id, r.chiave, soglia, minuti, r.limite, raggiunte)
    }

    /**
     * Le regole con un preavviso di oggi che non vale più: non è più un limite
     * attivo (tolta, spenta, cambiata in altro), o ha di nuovo più di 5 minuti
     * davanti (un bonus). Il loro preavviso va tolto dalla tendina. Un limite
     * superato no: lì la notifica dello sforamento prende il suo posto.
     */
    fun daTogliere(
        regole: List<Regola>,
        bonusOggiPerRegola: Map<String, Int>,
        indice: IndiceUso,
        giorno: String,
        giaFatti: Set<String>,
    ): List<Long> {
        val avvisateOggi = giaFatti.mapNotNull { chiave ->
            val parti = chiave.split(':')
            if (parti.getOrNull(1) == giorno) parti.getOrNull(0)?.toLongOrNull() else null
        }.toSet()
        if (avvisateOggi.isEmpty()) return emptyList()
        val perId = regole.associateBy { it.id }
        return avvisateOggi.filter { id ->
            val regola = perId[id]
            if (regola == null || !regola.attiva || regola.tipo != TipiRegola.LIMITE_TEMPO) return@filter true
            val r = resto(regola, bonusOggiPerRegola, indice) ?: return@filter false
            r.mancaMs > SOGLIE_MINUTI.max() * MINUTO_MS
        }.sorted()
    }

    /** Fra quanti ms finisce il giorno di [adesso] nel fuso [zona]: lì un preavviso non vale più. */
    fun finoAFineGiorno(adesso: Long, zona: ZoneId): Long {
        val domani = Instant.ofEpochMilli(adesso).atZone(zona).toLocalDate().plusDays(1).atStartOfDay(zona)
        return (domani.toInstant().toEpochMilli() - adesso).coerceAtLeast(1_000)
    }

    /** Le chiavi da segnare come dette per [preavvisi] (anche le soglie saltate). */
    fun chiaviDette(preavvisi: List<Preavviso>, giorno: String): List<String> =
        preavvisi.flatMap { p -> p.soglieRaggiunte.map { chiave(p.regolaId, giorno, p.limiteEfficace, it) } }

    /**
     * Fra quanti ms un'app in primo piano ([davanti]), se resta lì, porta una
     * regola alla sua prossima soglia non ancora detta. Null se non ce n'è
     * nessuna in vista (nessuna app davanti, o nessuna che consuma un limite):
     * allora vale il solito giro al minuto. Più app davanti sulla stessa chiave
     * la consumano insieme (il totale, una categoria).
     */
    fun prossimaSoglia(
        regole: List<Regola>,
        bonusOggiPerRegola: Map<String, Int>,
        indice: IndiceUso,
        giorno: String,
        giaFatti: Set<String>,
        davanti: List<AppDavanti>,
    ): Long? {
        if (davanti.isEmpty()) return null
        var prima: Long? = null
        for (regola in regole) {
            val r = resto(regola, bonusOggiPerRegola, indice) ?: continue
            val soglia = SOGLIE_MINUTI.firstOrNull {
                r.mancaMs > it * MINUTO_MS && chiave(regola.id, giorno, r.limite, it) !in giaFatti
            } ?: continue
            val allaSoglia = r.mancaMs - soglia * MINUTO_MS
            // Le app davanti che consumano questa chiave già adesso.
            val subito = davanti.count { it.contaTra <= 0 && indice.cade(r.chiave, it.pacchetto) }
            if (subito > 0) prima = minOf(prima ?: Long.MAX_VALUE, allaSoglia / subito)
            // Un'app di una Sessione in corso: conta dalla fine della sessione.
            for (app in davanti) {
                if (app.contaTra > 0 && indice.cade(r.chiave, app.pacchetto)) {
                    prima = minOf(prima ?: Long.MAX_VALUE, app.contaTra + allaSoglia)
                }
            }
        }
        return prima
    }

    /**
     * Le app in primo piano alla fine di [lettura]: quelle il cui pezzo arriva
     * fino ad adesso. Quelle che contano ([conta]: lo stesso filtro dell'uso),
     * e quelle di una Sessione in corso fino a [fineSessione], che contano solo
     * dopo. Schermo spento: nessuna (i pezzi si chiudono allo spegnimento).
     */
    fun davanti(lettura: LetturaGiorno, conta: (String) -> Boolean, fineSessione: Long?): List<AppDavanti> {
        val adesso = lettura.fine
        val risultato = LinkedHashMap<String, AppDavanti>()
        for (pezzo in lettura.sessioni) {
            if (pezzo.fine == adesso && pezzo.fine > pezzo.inizio && conta(pezzo.pacchetto)) {
                risultato[pezzo.pacchetto] = AppDavanti(pezzo.pacchetto)
            }
        }
        if (fineSessione != null && fineSessione > adesso) {
            for (pezzo in lettura.inSessione) {
                if (pezzo.fine == adesso && pezzo.pacchetto !in risultato && conta(pezzo.pacchetto)) {
                    risultato[pezzo.pacchetto] = AppDavanti(pezzo.pacchetto, contaTra = fineSessione - adesso)
                }
            }
        }
        return risultato.values.toList()
    }
}
