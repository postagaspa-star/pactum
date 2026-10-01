package eu.stgm.pactum.figlio.misura

import android.app.usage.UsageEvents
import java.time.LocalDate
import java.time.ZoneId

/** Un pezzo di tempo in primo piano di un'app, da [inizio] a [fine] (epoch ms). */
data class Sessione(val pacchetto: String, val inizio: Long, val fine: Long)

/** Un'app in primo piano al confine: le sue activity aperte, e da quando è aperta. */
data class Attiva(val activity: Set<String>, val dal: Long)

/**
 * Scorre gli eventi d'uso con istante in [da, a): la fine è esclusa, come in
 * queryEvents(). Per ogni evento: tipo, pacchetto, activity, istante.
 */
typealias ScorriEventi = (da: Long, a: Long, azione: (Int, String?, String?, Long) -> Unit) -> Unit

/**
 * (0.9) L'uso di un giorno letto UNA volta dagli eventi del sistema: da qui
 * escono i minuti per app, il totale e l'uso dentro le fasce, senza rileggere
 * gli eventi per ogni fascia. [fine] = fin dove arriva la lettura (adesso, o
 * la mezzanotte dopo per un giorno finito).
 *
 * (0.11) [sessioni] sono i pezzi che CONTANO. Il tempo passato in una Sessione
 * (contratto v3.5: "Studio", "Lavoro") nelle app della sua lista sta a parte,
 * in [inSessione] (TempoInSessione): non entra nei minuti per app, nelle
 * categorie, nel totale, nei limiti né nelle fasce. [conSessioni] = una
 * Sessione ha toccato questo giorno (anche senza minuti dentro).
 * [sessioniNote] = il telefono sa quali sessioni ci sono state (dopo una
 * reinstallazione no, finché il server non le riporta): solo allora i minuti
 * in sessione si possono dire.
 */
class LetturaGiorno(
    val giorno: LocalDate,
    val fine: Long,
    val sessioni: List<Sessione>,
    val inSessione: List<Sessione> = emptyList(),
    val conSessioni: Boolean = false,
    val sessioniNote: Boolean = false,
) {

    /** Il tempo in primo piano per app, dalla più usata. */
    val perApp: List<UsoApp> by lazy { Sessioni.perApp(sessioni) }

    /** (0.11) Il tempo passato in una Sessione, per app: non conta. */
    val perAppInSessione: List<UsoApp> by lazy { Sessioni.perApp(inSessione) }

    /** I millisecondi in primo piano dentro [da, a] delle sole app che [conta] ammette. */
    fun millisNellIntervallo(da: Long, a: Long, conta: (String) -> Boolean): Long =
        Sessioni.millisNellIntervallo(sessioni, da, a, conta)
}

/**
 * Le sessioni in primo piano dagli eventi d'uso, logica pura: gli eventi
 * arrivano uno alla volta (tipo, pacchetto, activity, istante), come li dà
 * queryEvents(). Le regole (architettura.md, rivedute nella 0.9):
 * - ACTIVITY_RESUMED apre la sessione del pacchetto, la PAUSED/STOPPED della
 *   sua ultima activity in primo piano la chiude: questa è la chiusura vera, e
 *   si conta fino a lei;
 * - una PAUSED o STOPPED senza una RESUMED prima (nemmeno nelle 12 ore
 *   dell'innesco) non era primo piano: zero minuti;
 * - una sessione senza la sua chiusura finisce al primo spegnimento dello
 *   schermo, blocco o spegnimento del telefono dopo il suo inizio; se l'app
 *   viene ripresa dopo lo schermo spento (Android mette in pausa tutto quando
 *   lo schermo si spegne: la pausa si è persa), il pezzo di prima finisce lì e
 *   se ne apre uno nuovo;
 * - nessun pezzo dura più di 12 ore dalla sua RESUMED: è la stessa ipotesi
 *   dell'innesco (chi è in primo piano ha una RESUMED nelle 12 ore prima), ed
 *   è quella che nella 0.8 teneva fuori dalle fasce le sessioni fantasma;
 * - DEVICE_STARTUP butta senza contare le sessioni ancora aperte, pezzi
 *   compresi (spento senza SHUTDOWN, es. batteria staccata: meglio non contare
 *   che contare tempo a telefono spento).
 */
class CalcoloSessioni(
    private val inizio: Long,
    private val fine: Long,
    attiveAllInizio: Map<String, Attiva> = emptyMap(),
) {
    private class StatoPacchetto {
        val activityAttive = mutableSetOf<String>()

        /**
         * I pezzi già finiti della sessione in corso: quelli fermati da uno
         * schermo spento e seguiti da una nuova RESUMED (la pausa si era
         * persa). Diventano sessioni solo quando la sessione si chiude davvero:
         * un'accensione del telefono prima li butta, come la 0.8.
         */
        val pezzi = mutableListOf<Sessione>()

        /** Da quando si conta il pezzo in corso: mai prima dell'inizio dell'intervallo. */
        var contaDa = 0L

        /** Quando è partito davvero il pezzo in corso (anche prima dell'intervallo): per le 12 ore. */
        var avviataIl = 0L

        /** Il primo spegnimento dello schermo o blocco nel pezzo in corso: senza chiusura, finisce lì. */
        var limite: Long? = null

        val aperta: Boolean get() = activityAttive.isNotEmpty()
    }

    private val stati = HashMap<String, StatoPacchetto>()
    private val chiuse = mutableListOf<Sessione>()

    init {
        for ((pacchetto, attiva) in attiveAllInizio) {
            if (attiva.activity.isEmpty()) continue
            stati[pacchetto] = StatoPacchetto().apply {
                activityAttive.addAll(attiva.activity)
                contaDa = inizio
                avviataIl = attiva.dal
            }
        }
    }

    fun evento(tipo: Int, pacchetto: String?, classe: String?, istante: Long) {
        val ts = istante.coerceIn(inizio, fine)
        when (tipo) {
            Sessioni.SPEGNIMENTO -> {
                // Senza chiusura: fino allo spegnimento, o prima allo schermo spento.
                for ((nome, stato) in stati) if (stato.aperta) chiudiSessione(nome, stato, minOf(ts, stato.limite ?: ts))
                return
            }
            Sessioni.ACCENSIONE -> {
                // Spento senza SHUTDOWN: la sessione non si sa quando è finita, e non si conta.
                for (stato in stati.values) {
                    stato.activityAttive.clear()
                    stato.pezzi.clear()
                }
                return
            }
            Sessioni.SCHERMO_SPENTO, Sessioni.BLOCCO -> {
                for (stato in stati.values) if (stato.aperta && stato.limite == null) stato.limite = ts
                return
            }
        }
        val nome = pacchetto ?: return
        val activity = classe ?: nome
        when (tipo) {
            Sessioni.RIPRESA -> {
                val stato = stati.getOrPut(nome) { StatoPacchetto() }
                if (!stato.aperta) {
                    stato.contaDa = ts
                    stato.avviataIl = ts
                    stato.limite = null
                } else {
                    val limite = stato.limite
                    if (limite != null) {
                        // Ripresa dopo uno schermo spento: Android aveva messo in
                        // pausa tutto, la pausa si è persa. Il pezzo di prima
                        // finisce allo spegnimento, e si riparte da qui.
                        aggiungiPezzo(nome, stato, limite)
                        stato.activityAttive.clear()
                        stato.contaDa = ts
                        stato.avviataIl = ts
                        stato.limite = null
                    }
                }
                stato.activityAttive.add(activity)
            }
            Sessioni.PAUSA, Sessioni.STOP -> {
                // Mai vista in primo piano (nessuna RESUMED prima): niente da chiudere.
                val stato = stati[nome] ?: return
                if (!stato.aperta) return
                stato.activityAttive.remove(activity)
                // La chiusura vera: si conta fino a lei.
                if (!stato.aperta) chiudiSessione(nome, stato, ts)
            }
        }
    }

    /** Le sessioni chiuse, più quelle rimaste senza chiusura, fino a dove possono arrivare. */
    fun sessioni(): List<Sessione> {
        val tutte = ArrayList(chiuse)
        for ((nome, stato) in stati) {
            if (!stato.aperta) continue
            tutte += stato.pezzi
            val fineSessione = minOf(fine, stato.limite ?: fine, stato.avviataIl + DURATA_MASSIMA_MS)
            if (fineSessione > stato.contaDa) tutte += Sessione(nome, stato.contaDa, fineSessione)
        }
        return tutte
    }

    /** La sessione finisce in [a] (mai oltre 12 ore dal suo pezzo in corso): i suoi pezzi diventano sessioni. */
    private fun chiudiSessione(nome: String, stato: StatoPacchetto, a: Long) {
        aggiungiPezzo(nome, stato, a)
        chiuse += stato.pezzi
        stato.pezzi.clear()
        stato.activityAttive.clear()
    }

    private fun aggiungiPezzo(nome: String, stato: StatoPacchetto, a: Long) {
        val finePezzo = minOf(a, stato.avviataIl + DURATA_MASSIMA_MS)
        if (finePezzo > stato.contaDa) stato.pezzi += Sessione(nome, stato.contaDa, finePezzo)
    }

    private companion object {
        const val DURATA_MASSIMA_MS = Sessioni.INNESCO_MS
    }
}

/**
 * Quali activity erano in primo piano al confine dell'intervallo, e da quando,
 * rigiocando gli eventi delle 12 ore prima. Spegnimento dello schermo, blocco,
 * spegnimento o accensione del telefono: nessuna sessione sopravvive al
 * confine (senza, spegnere dentro un'app la sera avvelenerebbe l'innesco del
 * giorno dopo con una sessione fantasma dalla mezzanotte).
 */
class InnescoSessioni {
    private val attive = HashMap<String, MutableSet<String>>()
    private val dal = HashMap<String, Long>()

    fun evento(tipo: Int, pacchetto: String?, classe: String?, istante: Long) {
        if (tipo == Sessioni.SPEGNIMENTO || tipo == Sessioni.ACCENSIONE ||
            tipo == Sessioni.SCHERMO_SPENTO || tipo == Sessioni.BLOCCO
        ) {
            attive.clear()
            dal.clear()
            return
        }
        val nome = pacchetto ?: return
        val activity = classe ?: nome
        when (tipo) {
            Sessioni.RIPRESA -> {
                val suo = attive.getOrPut(nome) { mutableSetOf() }
                // Una sessione nuova (o la stessa activity ripresa: quella di prima si è chiusa senza dirlo).
                if (suo.isEmpty() || activity in suo) dal[nome] = istante
                suo.add(activity)
            }
            Sessioni.PAUSA, Sessioni.STOP -> {
                val suo = attive[nome] ?: return
                suo.remove(activity)
                if (suo.isEmpty()) {
                    attive.remove(nome)
                    dal.remove(nome)
                }
            }
        }
    }

    fun attive(): Map<String, Attiva> =
        attive.filterValues { it.isNotEmpty() }.mapValues { (nome, suo) -> Attiva(suo.toSet(), dal[nome] ?: 0L) }
}

object Sessioni {
    // Su API 26-28 il sistema emette MOVE_TO_FOREGROUND/MOVE_TO_BACKGROUND, con
    // gli stessi valori di RESUMED/PAUSED (1 e 2). SCREEN_NON_INTERACTIVE e
    // KEYGUARD_SHOWN sono di API 28, SHUTDOWN e STARTUP di API 29: copiate qui
    // al momento della compilazione, prima semplicemente non arrivano mai.
    const val RIPRESA = UsageEvents.Event.ACTIVITY_RESUMED
    const val PAUSA = UsageEvents.Event.ACTIVITY_PAUSED
    const val STOP = UsageEvents.Event.ACTIVITY_STOPPED
    const val SCHERMO_SPENTO = UsageEvents.Event.SCREEN_NON_INTERACTIVE
    const val BLOCCO = UsageEvents.Event.KEYGUARD_SHOWN
    const val SPEGNIMENTO = UsageEvents.Event.DEVICE_SHUTDOWN
    const val ACCENSIONE = UsageEvents.Event.DEVICE_STARTUP

    /** Quanto indietro si guarda per sapere chi era aperto al confine; e la durata massima di una sessione. */
    const val INNESCO_MS = 12 * 60 * 60 * 1000L

    /**
     * L'uso di [giorno] dalla sua mezzanotte fino ad [adesso] o alla mezzanotte
     * successiva. Le due finestre si toccano senza buchi né doppioni: l'innesco
     * è [inizio − 12 h, inizio) e la lettura [inizio, fine), con la fine esclusa
     * di queryEvents (un evento alle 23:59:59.999 sta nell'innesco del giorno dopo).
     */
    fun giorno(giorno: LocalDate, zona: ZoneId, adesso: Long, scorri: ScorriEventi): LetturaGiorno {
        val inizio = giorno.atStartOfDay(zona).toInstant().toEpochMilli()
        val mezzanotteDopo = giorno.plusDays(1).atStartOfDay(zona).toInstant().toEpochMilli()
        val fine = minOf(adesso, mezzanotteDopo)
        return LetturaGiorno(giorno, fine, if (fine <= inizio) emptyList() else calcola(inizio, fine, scorri))
    }

    /** Le sessioni in [inizio, fine), con l'innesco sulle 12 ore prima di [inizio]. */
    fun calcola(inizio: Long, fine: Long, scorri: ScorriEventi): List<Sessione> {
        val innesco = InnescoSessioni()
        scorri(inizio - INNESCO_MS, inizio) { tipo, pacchetto, classe, istante ->
            innesco.evento(tipo, pacchetto, classe, istante)
        }
        val calcolo = CalcoloSessioni(inizio, fine, innesco.attive())
        scorri(inizio, fine) { tipo, pacchetto, classe, istante ->
            calcolo.evento(tipo, pacchetto, classe, istante)
        }
        return calcolo.sessioni()
    }

    /** Il tempo in primo piano per app, dalla più usata; le app a zero restano fuori. */
    fun perApp(sessioni: List<Sessione>): List<UsoApp> =
        sessioni.groupBy { it.pacchetto }
            .map { (pacchetto, suoi) -> UsoApp(pacchetto, suoi.sumOf { it.fine - it.inizio }) }
            .filter { it.millisPrimoPiano > 0 }
            .sortedByDescending { it.millisPrimoPiano }

    /** I millisecondi delle sessioni ammesse da [conta] che cadono dentro [da, a]. */
    fun millisNellIntervallo(sessioni: List<Sessione>, da: Long, a: Long, conta: (String) -> Boolean): Long =
        sessioni.sumOf { s ->
            val dentro = minOf(s.fine, a) - maxOf(s.inizio, da)
            if (dentro > 0 && conta(s.pacchetto)) dentro else 0L
        }
}
