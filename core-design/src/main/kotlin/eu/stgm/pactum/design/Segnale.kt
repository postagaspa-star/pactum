package eu.stgm.pactum.design

// Logica pura della striscia: niente Compose qui dentro, così si prova con
// JUnit semplice (test in app-figlio/app/src/test/.../design/).

/** Cosa dice un giorno del patto. Tre significati, uno per forma. */
enum class Segnale { MANTENUTA, FUORI_REGOLA, NESSUN_DATO }

/** Un giorno della striscia: `data` ISO ("2026-07-14"), come arriva dal server. */
data class GiornoPatto(val data: String, val segnale: Segnale)

/**
 * Lo `stato` del contratto (`striscia`/`semaforo`, v2.4) tradotto in segnale.
 * Solo "verde" e "rosso" hanno un significato; tutto il resto ("grigio", un
 * valore nuovo, una stringa vuota) è NESSUN_DATO: un giorno che non si conosce
 * non può figurare né mantenuto né infranto.
 */
fun segnaleDaStato(stato: String): Segnale = when (stato) {
    "verde" -> Segnale.MANTENUTA
    "rosso" -> Segnale.FUORI_REGOLA
    else -> Segnale.NESSUN_DATO
}

/**
 * La frase "6 su 7": (giorni mantenuti, giorni con dati).
 * I giorni senza dati escono dal denominatore: contarli come falliti sarebbe
 * una bugia, contarli come riusciti anche.
 */
fun contaGiorni(giorni: List<GiornoPatto>): Pair<Int, Int> {
    val conDati = giorni.filter { it.segnale != Segnale.NESSUN_DATO }
    return conDati.count { it.segnale == Segnale.MANTENUTA } to conDati.size
}

/**
 * La serie: giorni MANTENUTA di fila, contati all'indietro dal più recente
 * (la striscia va dal più vecchio a oggi, oggi in coda).
 * Se oggi non ha ancora dati si parte da ieri: una giornata appena cominciata
 * non rompe la serie. Per il resto si ferma al primo FUORI_REGOLA o NESSUN_DATO.
 * Solo nell'app del figlio: al genitore va il conteggio dei fatti, mai la serie.
 */
fun serieDiGiorni(giorni: List<GiornoPatto>): Int {
    val daContare = if (giorni.lastOrNull()?.segnale == Segnale.NESSUN_DATO) {
        giorni.dropLast(1)
    } else {
        giorni
    }
    return daContare.takeLastWhile { it.segnale == Segnale.MANTENUTA }.size
}
