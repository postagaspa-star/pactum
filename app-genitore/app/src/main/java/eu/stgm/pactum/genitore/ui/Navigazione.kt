package eu.stgm.pactum.genitore.ui

import eu.stgm.pactum.genitore.MainActivity
import eu.stgm.pactum.genitore.dati.Notifica
import eu.stgm.pactum.genitore.sync.Vedetta

// (0.15) Dove si va nell'app del genitore, detto con logica pura (provata in
// NavigazioneTest): le quattro schede fisse della barra in basso, le pagine che
// si aprono sopra (Notifiche, Impostazioni, Storico, il dettaglio di una regola,
// "Dai lavori di casa"), il tasto Indietro, e dove porta il tocco su una notifica
// (di sistema o della lista in app).
//
// La navigazione è una PILA: in fondo c'è sempre una scheda; sopra, le pagine (e
// le schede aperte da una pagina, come i Lavori aperti dalle Notifiche). Indietro
// toglie l'ultima; dalla sola scheda torna alla Panoramica; dalla Panoramica esce.

/** Le quattro schede fisse della barra in basso, in quest'ordine. */
enum class Scheda { PANORAMICA, DA_DECIDERE, LAVORI, TEMPO }

/** Le sezioni delle Impostazioni a cui si può arrivare direttamente. */
enum class SezioneImpostazioni { FAMIGLIA, AVVISI, COLLEGAMENTO }

/** Le pagine che si aprono sopra le schede. */
sealed interface Pagina {
    data object Notifiche : Pagina

    /** [sezione] = la sezione da portare in vista all'apertura (null = dall'inizio). */
    data class Impostazioni(val sezione: SezioneImpostazioni? = null) : Pagina

    data object Storico : Pagina

    /** Il dettaglio di una regola del figlio scelto. */
    data class Regola(val regolaId: Long) : Pagina

    /** "Dai lavori di casa", a pagina intera. */
    data object DaiLavori : Pagina

    /** (0.16) Le sessioni del figlio scelto: in corso, approvate, fatte, non più valide. */
    data object Sessioni : Pagina

    /** (0.16) Tutte le regole del figlio scelto, per dispositivo, anche le non più attive. */
    data object TutteLeRegole : Pagina

    /** (0.17) "Cambia il lavoro": un lavoro da fare del figlio scelto. */
    data class ModificaLavoro(val faccendaId: Long) : Pagina
}

/** Una voce della pila: una scheda o una pagina. */
sealed interface Schermo {
    data class SuScheda(val scheda: Scheda) : Schermo
    data class SuPagina(val pagina: Pagina) : Schermo
}

/** La pila di partenza: la Panoramica. */
private val PARTENZA = listOf<Schermo>(Schermo.SuScheda(Scheda.PANORAMICA))

/**
 * Dove si è adesso, e da dove si è venuti. [pila] non è mai vuota e comincia
 * sempre con una scheda.
 */
data class Navigazione(val pila: List<Schermo> = PARTENZA) {

    /** Quello che si vede. */
    val inCima: Schermo get() = pila.last()

    /** La scheda accesa nella barra: l'ultima scheda della pila. */
    val scheda: Scheda
        get() = pila.filterIsInstance<Schermo.SuScheda>().lastOrNull()?.scheda ?: Scheda.PANORAMICA

    /** true = in cima c'è una pagina (niente barra in basso). */
    val suUnaPagina: Boolean get() = inCima is Schermo.SuPagina

    /** Un tocco sulla barra in basso: si riparte da quella scheda, sola. */
    fun scegli(scheda: Scheda): Navigazione = Navigazione(listOf(Schermo.SuScheda(scheda)))

    /**
     * Apre [pagina] sopra quello che c'è. Se la stessa pagina è già nella pila
     * (per esempio le Notifiche, riaperte dai Lavori aperti dalle Notifiche) si
     * torna a lei invece di impilarne un'altra: la pila non cresce senza fine.
     */
    fun apri(pagina: Pagina): Navigazione {
        val voce = Schermo.SuPagina(pagina)
        val gia = pila.indexOf(voce)
        return if (gia >= 0) Navigazione(pila.take(gia + 1)) else Navigazione(pila + voce)
    }

    /**
     * Una scheda aperta da una pagina (una riga delle Notifiche che porta a "Da
     * decidere"): si impila, così Indietro torna alla pagina. Senza pagine sopra
     * è come un tocco sulla barra.
     */
    fun apriScheda(scheda: Scheda): Navigazione = when {
        !suUnaPagina -> scegli(scheda)
        else -> Navigazione(pila + Schermo.SuScheda(scheda))
    }

    /**
     * Il tasto Indietro: toglie quello che c'è in cima; da una scheda sola diversa
     * dalla Panoramica torna alla Panoramica; dalla Panoramica sola esce (null).
     */
    fun indietro(): Navigazione? = when {
        pila.size > 1 -> Navigazione(pila.dropLast(1))
        scheda != Scheda.PANORAMICA -> scegli(Scheda.PANORAMICA)
        else -> null
    }
}

// --- Il salvataggio (rotazione, app chiusa da Android) -------------------------------

/** La pila in parole semplici, una voce per riga: si salva in un Bundle. */
fun codificaNavigazione(navigazione: Navigazione): ArrayList<String> =
    ArrayList(navigazione.pila.map(::codificaSchermo))

/** Il contrario di [codificaNavigazione]; una pila rovinata torna alla Panoramica. */
fun decodificaNavigazione(voci: List<String>?): Navigazione {
    val pila = voci.orEmpty().map { decodificaSchermo(it) ?: return Navigazione() }
    if (pila.isEmpty() || pila.first() !is Schermo.SuScheda) return Navigazione()
    return Navigazione(pila)
}

private fun codificaSchermo(schermo: Schermo): String = when (schermo) {
    is Schermo.SuScheda -> "scheda:${schermo.scheda.name}"
    is Schermo.SuPagina -> when (val p = schermo.pagina) {
        Pagina.Notifiche -> "notifiche"
        is Pagina.Impostazioni -> "impostazioni:${p.sezione?.name.orEmpty()}"
        Pagina.Storico -> "storico"
        is Pagina.Regola -> "regola:${p.regolaId}"
        Pagina.DaiLavori -> "dai"
        Pagina.Sessioni -> "sessioni"
        Pagina.TutteLeRegole -> "regole"
        is Pagina.ModificaLavoro -> "modifica:${p.faccendaId}"
    }
}

private fun decodificaSchermo(testo: String): Schermo? {
    val pezzi = testo.split(":", limit = 2)
    return when (pezzi[0]) {
        "scheda" -> Scheda.entries.firstOrNull { it.name == pezzi.getOrNull(1) }?.let { Schermo.SuScheda(it) }
        "notifiche" -> Schermo.SuPagina(Pagina.Notifiche)
        "impostazioni" -> Schermo.SuPagina(
            Pagina.Impostazioni(SezioneImpostazioni.entries.firstOrNull { it.name == pezzi.getOrNull(1) }),
        )
        "storico" -> Schermo.SuPagina(Pagina.Storico)
        "regola" -> pezzi.getOrNull(1)?.toLongOrNull()?.let { Schermo.SuPagina(Pagina.Regola(it)) }
        "dai" -> Schermo.SuPagina(Pagina.DaiLavori)
        "sessioni" -> Schermo.SuPagina(Pagina.Sessioni)
        "regole" -> Schermo.SuPagina(Pagina.TutteLeRegole)
        "modifica" -> pezzi.getOrNull(1)?.toLongOrNull()?.let { Schermo.SuPagina(Pagina.ModificaLavoro(it)) }
        else -> null
    }
}

// --- Gli ingressi: il tocco su una notifica di sistema -------------------------------

/**
 * Dove porta l'extra `destinazione_iniziale` di una notifica toccata, partendo da
 * [attuale]. Le destinazioni nuove e quelle che le notifiche di prima (ancora
 * nella tendina dopo l'aggiornamento) portano con sé:
 * - "decidere", e le vecchie "turno", "proposte", "verdetti" → Da decidere;
 * - "faccende" → Lavori (la foto la apre la scheda, con l'extra `faccenda`);
 * - "tempo" → Tempo;
 * - "notifiche" → la pagina Notifiche, sopra la scheda di adesso;
 * - "avvisi" → le Impostazioni, sezione Avvisi, sopra la scheda di adesso;
 * - "finestra" e qualunque valore che non si conosce → Panoramica.
 * null = nessuna destinazione: si resta dove si è.
 */
fun ingresso(destinazione: String?, attuale: Navigazione = Navigazione()): Navigazione {
    val base = attuale.scegli(attuale.scheda)
    return when (destinazione) {
        null -> attuale
        MainActivity.DEST_DECIDERE,
        MainActivity.DEST_TURNO,
        MainActivity.DEST_PROPOSTE,
        MainActivity.DEST_VERDETTI,
        -> attuale.scegli(Scheda.DA_DECIDERE)
        MainActivity.DEST_FACCENDE -> attuale.scegli(Scheda.LAVORI)
        MainActivity.DEST_TEMPO -> attuale.scegli(Scheda.TEMPO)
        MainActivity.DEST_NOTIFICHE -> base.apri(Pagina.Notifiche)
        MainActivity.DEST_AVVISI -> base.apri(Pagina.Impostazioni(SezioneImpostazioni.AVVISI))
        else -> attuale.scegli(Scheda.PANORAMICA)
    }
}

// --- Il tocco su una riga della lista delle notifiche ---------------------------------

/**
 * Dove porta una riga delle Notifiche: [schermo] (una scheda o una pagina da aprire
 * sopra), il figlio da scegliere ([figlioId]) e, per un lavoro fatto, la foto da
 * aprire ([faccendaId]).
 */
data class ApriDaNotifica(val schermo: Schermo, val figlioId: Long?, val faccendaId: Long? = null)

/** I tipi che parlano di una regola: la riga apre la regola (se si sa quale). */
private val TIPI_SU_UNA_REGOLA = setOf("sforamento", "modifica_regola")

/**
 * La stessa tabella delle notifiche di sistema ([Vedetta.destinazionePerTipo]),
 * più quello che una riga sa in più: le notifiche che di sistema aprono la lista
 * (lì sono già) portano dove si guarda il fatto — un fuori regola o una modifica
 * alla sua regola (o alla Panoramica, se la regola non si sa), un bonus al Tempo,
 * il resto (anomalie, spento/acceso, sessioni eliminate, tipi nuovi) alla
 * Panoramica del figlio.
 */
fun destinazioneDellaRiga(notifica: Notifica): ApriDaNotifica {
    val figlio = notifica.figlioId
    return when (Vedetta.destinazionePerTipo(notifica.tipo)) {
        MainActivity.DEST_DECIDERE -> ApriDaNotifica(Schermo.SuScheda(Scheda.DA_DECIDERE), figlio)
        MainActivity.DEST_FACCENDE ->
            ApriDaNotifica(Schermo.SuScheda(Scheda.LAVORI), figlio, faccendaDellaNotifica(notifica))
        else -> {
            val regola = regolaIdNotifica(notifica)
            when {
                notifica.tipo in TIPI_SU_UNA_REGOLA && regola != null ->
                    ApriDaNotifica(Schermo.SuPagina(Pagina.Regola(regola)), figlio)
                notifica.tipo == "bonus" -> ApriDaNotifica(Schermo.SuScheda(Scheda.TEMPO), figlio)
                else -> ApriDaNotifica(Schermo.SuScheda(Scheda.PANORAMICA), figlio)
            }
        }
    }
}

/** Dove si va dopo il tocco su una riga, partendo da [attuale] (la pagina Notifiche). */
fun Navigazione.dopoLaRiga(apri: ApriDaNotifica): Navigazione = when (val s = apri.schermo) {
    is Schermo.SuScheda -> apriScheda(s.scheda)
    is Schermo.SuPagina -> apri(s.pagina)
}
