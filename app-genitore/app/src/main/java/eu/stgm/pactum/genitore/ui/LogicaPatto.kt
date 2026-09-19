package eu.stgm.pactum.genitore.ui

import eu.stgm.pactum.design.GiornoPatto
import eu.stgm.pactum.design.Segnale
import eu.stgm.pactum.design.segnaleDaStato
import eu.stgm.pactum.genitore.dati.EventoFinestra
import eu.stgm.pactum.genitore.dati.QuadrettoSemaforo
import eu.stgm.pactum.genitore.dati.UsoGiorno
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

// Logica pura della finestra e del tempo: niente Compose, niente risorse, così
// si prova con JUnit semplice (app/src/test/.../ui/LogicaPattoTest.kt). Le
// parole le mette la UI; qui si decide solo COSA mostrare e in che ordine.

/** Il ts_server ISO 8601 UTC come istante, null se malformato. */
fun istanteServer(tsServer: String?): Instant? {
    if (tsServer.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(tsServer).toInstant()
    } catch (e: DateTimeParseException) {
        null
    }
}

/** Quanti giorni racconta la finestra: gli stessi della striscia e del semaforo. */
const val GIORNI_FINESTRA = 8L

/** Una striscia del contratto (la `striscia` v2.4 o il `semaforo` di una regola) nei giorni di core-design. */
fun giorniDaQuadretti(quadretti: List<QuadrettoSemaforo>): List<GiornoPatto> =
    quadretti.map { GiornoPatto(it.data, segnaleDaStato(it.stato)) }

// --- La riga di riepilogo della scheda del patto ------------------------------

/** Quello che la scheda in cima dice in una riga: i fatti degli ultimi 8 giorni. */
data class RiepilogoPatto(val giorniFuoriRegola: Int, val buchiNelRegistro: Int)

/**
 * I giorni fuori regola sono i quadretti terracotta della striscia: la riga dice
 * a parole la stessa cosa che la striscia disegna. Se la striscia manca (server
 * vecchio) si contano i giorni distinti degli sforamenti arrivati negli ultimi 8.
 * I buchi nel registro sono le manomissioni degli stessi 8 giorni.
 *
 * La finestra parte dal primo giorno della striscia; senza striscia, da sette
 * giorni prima di oggi. Un evento con la data illeggibile non si conta: meglio
 * un buco in meno che un giorno inventato.
 */
fun riepilogoPatto(
    giorni: List<GiornoPatto>,
    sforamenti: List<EventoFinestra>,
    manomissioni: List<EventoFinestra>,
    zona: ZoneId,
    oggi: LocalDate,
): RiepilogoPatto {
    val inizio = giorni.firstOrNull()?.data?.let(::dataOppureNull)
        ?: oggi.minusDays(GIORNI_FINESTRA - 1)
    fun giornoDi(evento: EventoFinestra): LocalDate? =
        istanteServer(evento.tsServer)?.atZone(zona)?.toLocalDate()?.takeIf { it >= inizio }

    val fuori = if (giorni.isNotEmpty()) {
        giorni.count { it.segnale == Segnale.FUORI_REGOLA }
    } else {
        sforamenti.mapNotNull(::giornoDi).distinct().size
    }
    return RiepilogoPatto(
        giorniFuoriRegola = fuori,
        buchiNelRegistro = manomissioni.count { giornoDi(it) != null },
    )
}

private fun dataOppureNull(iso: String): LocalDate? = try {
    LocalDate.parse(iso)
} catch (e: DateTimeParseException) {
    null
}

// --- "Da guardare insieme" -----------------------------------------------------

enum class GenereVoce { FUORI_REGOLA, BUCO_NEL_REGISTRO }

/** Una riga di "Da guardare insieme": un giorno fuori regola o un buco nel registro. */
data class VoceDaGuardare(val genere: GenereVoce, val evento: EventoFinestra)

/**
 * Sforamenti e manomissioni fusi in una lista sola, dal più recente. Le voci con
 * la data illeggibile vanno in fondo invece di sparire: il registro non si
 * accorcia per un orario storto. A parità di istante, prima i fuori regola.
 */
fun daGuardareInsieme(
    sforamenti: List<EventoFinestra>,
    manomissioni: List<EventoFinestra>,
): List<VoceDaGuardare> =
    (sforamenti.map { VoceDaGuardare(GenereVoce.FUORI_REGOLA, it) } +
        manomissioni.map { VoceDaGuardare(GenereVoce.BUCO_NEL_REGISTRO, it) })
        .sortedWith(
            compareByDescending<VoceDaGuardare> {
                istanteServer(it.evento.tsServer)?.toEpochMilli() ?: Long.MIN_VALUE
            }.thenBy { it.genere.ordinal },
        )

/** Quante righe di "Da guardare insieme" si vedono senza toccare niente. */
const val VOCI_DA_GUARDARE_VISIBILI = 5

// --- Il segno -------------------------------------------------------------------

/**
 * Il pulsante "Manda un segno" è spento se il server dice che oggi è già
 * partito, oppure se è partito da qui oggi (la finestra successiva può non
 * essere ancora arrivata).
 */
fun segnoGiaMandato(segnoOggi: Boolean, mandatoIl: LocalDate?, oggi: LocalDate): Boolean =
    segnoOggi || mandatoIl == oggi

// --- Tempo: dentro il patto / il resto della giornata ---------------------------

/** Una voce dell'elenco del Tempo: un'app o una categoria, col limite se c'è. */
data class VoceTempo(
    val chiave: String,
    val nome: String?,
    val minuti: Int,
    val limite: Int?,
    val categoria: Boolean,
)

/**
 * L'elenco sotto i grafici, in due blocchi.
 * - [dentroIlPatto]: le voci con un limite (app e categorie), dalla più vicina
 *   al limite — prima le promesse, ordinate per quanto sono tirate.
 * - [restoDellaGiornata]: le app senza limite, dalla più usata: contesto.
 * - [massimoDelGiorno]: l'app più usata del giorno, la scala delle barre senza limite.
 */
data class ElencoTempo(
    val dentroIlPatto: List<VoceTempo>,
    val restoDellaGiornata: List<VoceTempo>,
    val massimoDelGiorno: Int,
)

fun elencoTempo(giorno: UsoGiorno): ElencoTempo {
    val app = giorno.app.map {
        VoceTempo(it.chiave, it.nome, it.minuti, it.limite, categoria = false)
    }
    // Le categorie entrano solo se hanno un limite e un uso: quelle senza limite
    // vivono già nella legenda della ciambella.
    val categorie = giorno.categorie
        .filter { it.limite != null && it.minuti > 0 }
        .map { VoceTempo(it.chiave, null, it.minuti, it.limite, categoria = true) }

    val dentro = (app.filter { it.limite != null } + categorie).sortedWith(
        compareByDescending<VoceTempo> { vicinanzaAlLimite(it) }
            .thenByDescending { it.minuti }
            .thenBy { it.chiave },
    )
    val resto = app.filter { it.limite == null }.sortedWith(
        compareByDescending<VoceTempo> { it.minuti }.thenBy { it.chiave },
    )
    return ElencoTempo(
        dentroIlPatto = dentro,
        restoDellaGiornata = resto,
        massimoDelGiorno = giorno.app.maxOfOrNull { it.minuti } ?: 0,
    )
}

/** Minuti usati sul limite: 1.0 = limite raggiunto, oltre 1 = oltre. */
fun vicinanzaAlLimite(voce: VoceTempo): Double {
    val limite = voce.limite ?: return 0.0
    return voce.minuti.toDouble() / limite.coerceAtLeast(1)
}

/** Di quanto si è andati oltre il limite; 0 se dentro o senza limite. */
fun minutiOltre(minuti: Int, limite: Int?): Int =
    if (limite == null) 0 else (minuti - limite).coerceAtLeast(0)
