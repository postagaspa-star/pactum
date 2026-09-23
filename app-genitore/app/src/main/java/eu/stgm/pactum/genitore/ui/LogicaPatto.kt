package eu.stgm.pactum.genitore.ui

import eu.stgm.pactum.design.GiornoPatto
import eu.stgm.pactum.design.Segnale
import eu.stgm.pactum.design.segnaleDaStato
import eu.stgm.pactum.genitore.dati.EventoFinestra
import eu.stgm.pactum.genitore.dati.Notifica
import eu.stgm.pactum.genitore.dati.Proposta
import eu.stgm.pactum.genitore.dati.QuadrettoSemaforo
import eu.stgm.pactum.genitore.dati.RiepilogoFinestra
import eu.stgm.pactum.genitore.dati.StatiProposta
import eu.stgm.pactum.genitore.dati.UsoGiorno
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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

// --- I giorni del patto --------------------------------------------------------

/**
 * Il fuso in cui il server conta i giorni del patto (`PACTUM_TIMEZONE`). La
 * finestra non lo porta (il campo `fuso` esiste solo in GET /api/patto, che è
 * del figlio): si usa il default del server. MAI il fuso del telefono che
 * legge — un genitore in viaggio vedrebbe i fatti spostati di un giorno.
 */
val FUSO_PATTO: ZoneId = ZoneId.of("Europe/Rome")

/** Il giorno di un evento dal suo `ts_server`, nel fuso del patto; null se l'orario è illeggibile. */
fun giornoDelServer(evento: EventoFinestra, zona: ZoneId): LocalDate? =
    istanteServer(evento.tsServer)?.atZone(zona)?.toLocalDate()

/**
 * (v2.4) Il `giorno` che il telefono ha scritto nei dettagli di uno sforamento,
 * se è una data vera: è il giorno in cui lo sforamento è SUCCESSO, anche se è
 * arrivato al server più tardi. null se manca o non è una data.
 */
fun giornoDichiarato(evento: EventoFinestra): LocalDate? =
    (evento.dettagli["giorno"] as? JsonPrimitive)?.contentOrNull?.let(::dataOppureNull)

/**
 * Il giorno di uno sforamento, come lo mette il semaforo del server: il
 * `giorno` dei dettagli se è una data vera, altrimenti il giorno d'arrivo.
 */
fun giornoSforamento(evento: EventoFinestra, zona: ZoneId): LocalDate? =
    giornoDichiarato(evento) ?: giornoDelServer(evento, zona)

/**
 * Gli 8 giorni di cui parla la finestra: le date della striscia. Senza striscia
 * (server vecchio) oggi e i sette giorni prima, [oggi] nel fuso del patto.
 */
fun giorniDellaFinestra(giorni: List<GiornoPatto>, oggi: LocalDate): Set<LocalDate> {
    val dallaStriscia = giorni.mapNotNull { dataOppureNull(it.data) }.toSet()
    if (dallaStriscia.isNotEmpty()) return dallaStriscia
    return (0 until GIORNI_FINESTRA).map { oggi.minusDays(it) }.toSet()
}

// --- La riga di riepilogo della scheda del patto ------------------------------

/** Quello che la scheda in cima dice in una riga: i fatti degli ultimi 8 giorni. */
data class RiepilogoPatto(val giorniFuoriRegola: Int, val interruzioni: Int)

/**
 * La riga sotto la striscia. Se il server manda il suo `riepilogo` (v2.4) vale
 * QUELLO: è contato nel fuso del patto su tutto il registro, ed è identico a
 * quello del figlio.
 *
 * Server vecchio, ripiego contato qui: i giorni fuori regola sono i quadretti
 * terracotta della striscia (o, senza striscia, i giorni distinti degli
 * sforamenti arrivati); le interruzioni sono le manomissioni degli stessi 8
 * giorni. Il ripiego vede solo i 20 eventi più recenti che il server manda. Un
 * evento con la data illeggibile non si conta: meglio un'interruzione in meno
 * che un giorno inventato.
 */
fun riepilogoPatto(
    dalServer: RiepilogoFinestra?,
    giorni: List<GiornoPatto>,
    sforamenti: List<EventoFinestra>,
    manomissioni: List<EventoFinestra>,
    zona: ZoneId,
    oggi: LocalDate,
): RiepilogoPatto {
    if (dalServer != null) {
        return RiepilogoPatto(dalServer.giorniFuoriRegola, dalServer.interruzioni)
    }
    val finestra = giorniDellaFinestra(giorni, oggi)
    val fuori = if (giorni.isNotEmpty()) {
        giorni.count { it.segnale == Segnale.FUORI_REGOLA }
    } else {
        sforamenti.mapNotNull { giornoSforamento(it, zona) }.filter { it in finestra }.distinct().size
    }
    return RiepilogoPatto(
        giorniFuoriRegola = fuori,
        interruzioni = manomissioni.count { evento ->
            giornoDelServer(evento, zona)?.let { it in finestra } == true
        },
    )
}

private fun dataOppureNull(iso: String): LocalDate? = try {
    LocalDate.parse(iso)
} catch (e: DateTimeParseException) {
    null
}

// --- "Da guardare insieme" -----------------------------------------------------

enum class GenereVoce { FUORI_REGOLA, INTERRUZIONE }

/**
 * Una riga di "Da guardare insieme": un giorno fuori regola o un'interruzione
 * nella registrazione. [giorno] è il giorno della striscia in cui cade (fuso del
 * patto). [giornoDichiarato] = il giorno viene dai dettagli dello sforamento: la
 * riga mostra QUEL giorno, non l'ora in cui l'evento è arrivato al server.
 */
data class VoceDaGuardare(
    val genere: GenereVoce,
    val evento: EventoFinestra,
    val giorno: LocalDate,
    val giornoDichiarato: Boolean = false,
)

/**
 * Sforamenti e manomissioni fusi in una lista sola, SOLO quelli che cadono negli
 * 8 giorni della striscia: la lista racconta gli stessi giorni della riga di
 * riepilogo, mai uno sforamento di 12 giorni fa sotto "Nessun giorno fuori
 * regola". Il giorno di uno sforamento è quello dei suoi dettagli (se è una data
 * vera), altrimenti quello d'arrivo; quello di un'interruzione, il giorno
 * d'arrivo. Tutti nel fuso del patto. Un evento senza giorno leggibile resta
 * fuori, come nel riepilogo.
 *
 * Dal giorno più recente; nello stesso giorno dall'arrivo più recente, e a
 * parità di istante prima i fuori regola.
 */
fun daGuardareInsieme(
    sforamenti: List<EventoFinestra>,
    manomissioni: List<EventoFinestra>,
    giorni: List<GiornoPatto>,
    zona: ZoneId,
    oggi: LocalDate,
): List<VoceDaGuardare> {
    val finestra = giorniDellaFinestra(giorni, oggi)
    val fuori = sforamenti.mapNotNull { evento ->
        val dichiarato = giornoDichiarato(evento)
        val giorno = dichiarato ?: giornoDelServer(evento, zona) ?: return@mapNotNull null
        VoceDaGuardare(GenereVoce.FUORI_REGOLA, evento, giorno, giornoDichiarato = dichiarato != null)
    }
    val interruzioni = manomissioni.mapNotNull { evento ->
        giornoDelServer(evento, zona)?.let { VoceDaGuardare(GenereVoce.INTERRUZIONE, evento, it) }
    }
    return (fuori + interruzioni)
        .filter { it.giorno in finestra }
        .sortedWith(
            compareByDescending<VoceDaGuardare> { it.giorno }
                .thenByDescending { istanteServer(it.evento.tsServer)?.toEpochMilli() ?: Long.MIN_VALUE }
                .thenBy { it.genere.ordinal },
        )
}

/** Quante righe di "Da guardare insieme" si vedono senza toccare niente. */
const val VOCI_DA_GUARDARE_VISIBILI = 5

// --- Notifiche ------------------------------------------------------------------

/**
 * Le notifiche dalla più recente alla più vecchia. Il server le manda per `id`
 * crescente (contratto-api.md): l'id è autoincrementale, quindi è anche
 * l'ordine d'arrivo — e non dipende da un orario da interpretare.
 */
fun dallaPiuRecente(notifiche: List<Notifica>): List<Notifica> =
    notifiche.sortedByDescending { it.id }

// --- Il segno -------------------------------------------------------------------

/**
 * Il pulsante "Manda un segno" è spento se il server dice che oggi è già
 * partito, oppure se è partito da qui oggi (la finestra successiva può non
 * essere ancora arrivata).
 */
fun segnoGiaMandato(segnoOggi: Boolean, mandatoIl: LocalDate?, oggi: LocalDate): Boolean =
    segnoOggi || mandatoIl == oggi

// --- Proposte -------------------------------------------------------------------

/**
 * Le regole che hanno già una proposta in attesa: il server ne accetta una sola
 * per regola (409 `proposta_gia_pendente`), quindi su queste "Proponi una
 * modifica" non si offre.
 */
fun regoleConPropostaInAttesa(proposte: List<Proposta>): Set<Long> =
    proposte.filter { it.stato == StatiProposta.PENDENTE }.map { it.regolaId }.toSet()

// --- Tempo: dentro il patto / il resto della giornata ---------------------------

/**
 * Una voce dell'elenco del Tempo: un'app (o un programma, o un sito sul
 * computer) o una categoria, col limite se c'è.
 * [bonus] = minuti concessi quel giorno su quella regola (v2.4, 0 se nessuno o
 * server vecchio): il limite di quel giorno è `limite + bonus`, come per il figlio.
 * [bonusNoto] = false quando il bonus di quella regola non si conosce (un limite
 * su un sito, v3): allora "quanto oltre" non si calcola — meglio tacere che
 * dire un numero sbagliato.
 */
data class VoceTempo(
    val chiave: String,
    val nome: String?,
    val minuti: Int,
    val limite: Int?,
    val categoria: Boolean,
    val bonus: Int = 0,
    val bonusNoto: Boolean = true,
) {
    /** Il limite vero di quel giorno: base + bonus. null senza limite. */
    val limiteDelGiorno: Int? get() = limite?.let { it + bonus.coerceAtLeast(0) }
}

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

/**
 * [altreNelPatto]: voci col limite che non stanno nella fotografia dei programmi
 * (v3: i limiti sui siti di un computer, v. vociSitiNelPatto). Entrano in
 * "dentro il patto" con le altre, stesso ordine.
 */
fun elencoTempo(giorno: UsoGiorno, altreNelPatto: List<VoceTempo> = emptyList()): ElencoTempo {
    val app = giorno.app.map {
        VoceTempo(it.chiave, it.nome, it.minuti, it.limite, categoria = false, bonus = it.bonus)
    }
    // Le categorie entrano solo se hanno un limite e un uso: quelle senza limite
    // vivono già nella legenda della ciambella.
    val categorie = giorno.categorie
        .filter { it.limite != null && it.minuti > 0 }
        .map { VoceTempo(it.chiave, null, it.minuti, it.limite, categoria = true, bonus = it.bonus) }

    val dentro = (app.filter { it.limite != null } + categorie + altreNelPatto.filter { it.limite != null }).sortedWith(
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

/** Minuti usati sul limite del giorno (base + bonus): 1.0 = raggiunto, oltre 1 = oltre. */
fun vicinanzaAlLimite(voce: VoceTempo): Double {
    val limite = voce.limiteDelGiorno ?: return 0.0
    return voce.minuti.toDouble() / limite.coerceAtLeast(1)
}

/**
 * Di quanto si è andati oltre il limite di quel giorno, cioè `limite + bonus`
 * (contratto v2.4, uso_recente): con +15 concessi, 70 su 60 è DENTRO, come lo
 * vede il figlio. 0 se dentro o senza limite; [bonus] 0 su un server vecchio.
 */
fun minutiOltre(minuti: Int, limite: Int?, bonus: Int = 0): Int =
    if (limite == null) 0 else (minuti - limite - bonus.coerceAtLeast(0)).coerceAtLeast(0)
