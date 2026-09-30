package eu.stgm.pactum.genitore.sync

import eu.stgm.pactum.genitore.dati.Notifica

// Logica pura del giro della vedetta (0.9): niente Android, così si prova con
// JUnit semplice (GiroVedettaTest). Qui si decide QUANDO guardare, CHE COSA
// chiedere al server e CHE COSA avvisare; le notifiche di sistema le alza Vedetta.

/** Com'è andato un giro della vedetta. */
enum class EsitoGiro {
    /** Manca l'indirizzo del server o il codice d'accesso: non c'è niente da guardare. */
    NON_CONFIGURATA,

    /** Il telefono è senza rete: non è colpa del server, si riprova al giro dopo. */
    SENZA_RETE,

    /** Il telefono ha rete, ma il server non ha risposto (o ha risposto male). */
    SERVER_MUTO,

    /** Le notifiche sono arrivate, e le novità sono state avvisate. */
    FATTO,
}

/**
 * Quando la vedetta fa il giro. Il servizio sempre attivo chiede le notifiche
 * nuove circa ogni minuto: una richiesta leggera. Silenzio dei dispositivi,
 * digest e la lista intera delle notifiche restano ai loro tempi, circa ogni 15
 * minuti. Se il server non risponde, i giri si diradano fino a uno ogni 15
 * minuti: un server spento per un giorno non deve svegliare il telefono ogni
 * minuto per niente. Il primo giro andato storto non allunga niente: un
 * singhiozzo della rete non deve ritardare un avviso. Senza il permesso per gli
 * avvisi guardare ogni minuto non serve a niente: un giro ogni 15 minuti.
 *
 * "Circa": sono sveglie di Android, e Android può ritardarle (a telefono fermo,
 * o col risparmio batteria di certe marche). Qui si decide cosa CHIEDERE.
 */
object CadenzaVedetta {

    /** Il giro veloce: solo le notifiche nuove. */
    const val INTERVALLO_MS = 60_000L

    /** Il giro completo: la lista intera, il silenzio dei dispositivi, il digest. */
    const val INTERVALLO_COMPLETO_MS = 15 * 60_000L

    /** Il massimo che si aspetta quando il server non risponde. */
    const val ATTESA_MASSIMA_MS = 15 * 60_000L

    /** Senza il permesso per gli avvisi: il giro si fa, ma di rado. */
    const val ATTESA_SENZA_AVVISI_MS = 15 * 60_000L

    /**
     * Il tempo massimo di un giro del servizio: ogni richiesta al server ha il suo
     * limite (30 s, PostinoClient), e il giro intero non va oltre questo.
     */
    const val GIRO_MASSIMO_MS = 150_000L

    /**
     * Quanto resta sveglio il telefono al massimo per un giro: più del giro
     * massimo, solo come sicurezza. Di norma il giro finisce prima e lo lascia.
     */
    const val RISVEGLIO_MASSIMO_MS = 180_000L

    /** Quanto si aspettano la famiglia e la finestra per scrivere un avviso per bene. */
    const val TEMPO_PER_I_NOMI_MS = 10_000L

    /** Ogni quanto, al massimo, il giro riscrive "ultimo controllo" nelle impostazioni. */
    const val REGISTRAZIONE_MS = 10 * 60_000L

    /**
     * true = questo giro è completo. [ultimo] = quando è partito l'ultimo giro
     * completo, sull'orologio monotono (SystemClock.elapsedRealtime); null = mai,
     * in questo processo o con questo server. Un orologio che torna indietro (non
     * succede con quello monotono, ma costa poco) vale "dovuto".
     */
    fun giroCompletoDovuto(ultimo: Long?, adesso: Long): Boolean =
        ultimo == null || adesso < ultimo || adesso - ultimo >= INTERVALLO_COMPLETO_MS

    /**
     * Quanto aspettare prima del giro dopo, quando il server non ha risposto
     * [fallimentiDiFila] volte di fila: 0 o 1 → un minuto; poi 2, 4, 8 minuti;
     * dal quinto in poi 15 minuti.
     */
    fun attesaDopo(fallimentiDiFila: Int): Long {
        if (fallimentiDiFila <= 1) return INTERVALLO_MS
        val raddoppi = (fallimentiDiFila - 1).coerceAtMost(4)
        return (INTERVALLO_MS shl raddoppi).coerceAtMost(ATTESA_MASSIMA_MS)
    }

    /** L'attesa del giro dopo: senza avvisi possibili, di rado; se no come dice il server. */
    fun attesa(fallimentiDiFila: Int, avvisiAccesi: Boolean): Long =
        if (!avvisiAccesi) ATTESA_SENZA_AVVISI_MS else attesaDopo(fallimentiDiFila)

    /** true = è ora di riscrivere "ultimo controllo" (mai fatto, o più di 10 minuti fa). */
    fun registrazioneDovuta(ultima: Long?, adesso: Long): Boolean =
        ultima == null || adesso < ultima || adesso - ultima >= REGISTRAZIONE_MS
}

/**
 * Il `dopo_id` del giro veloce (contratto v3.3, "Solo le notifiche nuove"): l'id
 * più alto già avvisato, così il server manda solo quello che è arrivato dopo.
 * null = nessuno avvisato ancora: si chiede la lista intera (che allora è corta,
 * o è il primo giro). Una notifica più vecchia mai avvisata (per esempio senza
 * permesso) la ritrova il giro completo, che chiede sempre la lista intera.
 * Un server vecchio ignora il parametro e manda tutto: il ricordo fa il resto.
 */
fun dopoIdPerIlGiroVeloce(giaAvvisate: Set<Long>?): Long? = giaAvvisate?.maxOrNull()

/**
 * Le notifiche da avvisare a questo giro: le non lette mai avvisate prima, una
 * volta sola ciascuna (un id ripetuto nella stessa lista non avvisa due volte),
 * nell'ordine d'arrivo: l'id del server cresce sempre.
 */
fun novitaDaAvvisare(nonLette: List<Notifica>, giaAvvisate: Set<Long>): List<Notifica> =
    nonLette.filter { it.id !in giaAvvisate }.distinctBy { it.id }.sortedBy { it.id }

/**
 * Gli id da ricordare come "già avvisati" dopo il giro: quelli di prima più
 * quelli appena avvisati. Con la lista INTERA delle non lette ([nonLette], giro
 * completo) si tengono solo quelli ancora non letti sul server; con una lista
 * parziale (null: il giro veloce, che chiede solo le nuove) non si toglie niente.
 *
 * - Una notifica letta non torna più nella lista: ricordarla non serve.
 * - Una ancora non letta va ricordata SEMPRE, quante che siano. Prima c'era un
 *   tetto di 500 id: con più di 500 non lette le più vecchie tornavano "nuove"
 *   a ogni giro — col servizio sempre attivo, ogni minuto.
 * - Dopo un ripristino del registro sul server gli id ricominciano da un numero
 *   più basso: un id che non c'è più si dimentica al giro completo, così una
 *   notifica nuova con un id riusato non resta zittita da un vecchio ricordo.
 */
fun avvisateDaRicordare(
    giaAvvisate: Set<Long>,
    avvisateOra: Collection<Long>,
    nonLette: Collection<Long>?,
): Set<Long> {
    val tutte = LinkedHashSet(giaAvvisate).apply { addAll(avvisateOra) }
    if (nonLette == null) return tutte
    val ancoraNonLette = nonLette.toHashSet()
    return tutte.filterTo(LinkedHashSet()) { it in ancoraNonLette }
}

/** Quante notifiche del patto si tengono al massimo nella tendina (Android ne tiene 50 per app). */
const val TETTO_AVVISI_ATTIVI = 20

/** Come avvisare le novità di un giro. */
enum class ModoAvviso {
    /** Una notifica di sistema per ciascuna novità. */
    UNA_PER_UNA,

    /** Una sola notifica "Novità da leggere: N": niente raffica. */
    RIASSUNTO,
}

/**
 * Una notifica per novità, tranne due casi in cui arriverebbe una raffica: il
 * primo giro senza ricordo (app appena installata, o server cambiato: tutte le
 * non lette sembrerebbero nuove) e un giro con più novità di quante la tendina
 * ne tiene. Allora UNA notifica riassuntiva; il dettaglio è nella lista in app.
 */
fun modoAvviso(primoGiro: Boolean, quante: Int, tetto: Int = TETTO_AVVISI_ATTIVI): ModoAvviso =
    if (quante > 1 && (primoGiro || quante > tetto)) ModoAvviso.RIASSUNTO else ModoAvviso.UNA_PER_UNA

/**
 * Una notifica del canale degli avvisi già nella tendina. [togliibile] = un
 * avviso del patto o il riassunto (sì), un avviso di silenzio o il digest (no:
 * dicono uno stato, non un fatto già visto).
 */
data class AvvisoAttivo(val id: Int, val quando: Long, val togliibile: Boolean)

/**
 * Le notifiche da togliere dalla tendina PRIMA di alzarne [inArrivo] nuove, così
 * il canale non supera [tetto]: le più vecchie tra quelle togliibili. Android
 * tiene al massimo 50 notifiche per app e oltre scarta in silenzio le nuove:
 * meglio togliere un avviso vecchio (resta nella lista in app) che perderne uno
 * nuovo. Lato server non cambia niente: "letta" resta un gesto nell'app.
 */
fun avvisiDaTogliere(attivi: List<AvvisoAttivo>, inArrivo: Int, tetto: Int = TETTO_AVVISI_ATTIVI): List<Int> {
    val eccesso = attivi.size + inArrivo - tetto
    if (eccesso <= 0) return emptyList()
    return attivi.filter { it.togliibile }.sortedBy { it.quando }.take(eccesso).map { it.id }
}

/** Che cosa dice la notifica fissa del servizio. */
enum class StatoAvvisi {
    /** Avvisi accesi, e Android lascia Pactum sempre attivo. */
    IN_ORDINE,

    /** Avvisi accesi, ma Android può mettere in pausa Pactum: possono arrivare in ritardo. */
    POSSIBILI_RITARDI,

    /** Gli avvisi del patto sono spenti su questo telefono: non arriva niente. */
    SPENTI,
}

/** Lo stato degli avvisi da due fatti del telefono: gli avvisi spenti pesano di più. */
fun statoAvvisi(avvisiAccesi: Boolean, esenteDallaBatteria: Boolean): StatoAvvisi = when {
    !avvisiAccesi -> StatoAvvisi.SPENTI
    !esenteDallaBatteria -> StatoAvvisi.POSSIBILI_RITARDI
    else -> StatoAvvisi.IN_ORDINE
}
