package eu.stgm.pactum.genitore.ui

import eu.stgm.pactum.design.GiornoPatto
import eu.stgm.pactum.design.Segnale
import eu.stgm.pactum.design.contaGiorni
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.RegolaFinestra

// (0.23) Le regole di ciascun dispositivo, tenute separate (richiesta di Andrea:
// tutte le regole di oggi una sotto l'altra, telefono e computer mescolati, erano
// un muro di testo). Nella card del patto c'è UNA riga per dispositivo, e una per
// gli impegni di vita reale: la sua striscia e com'è andata oggi, in poche parole.
// Toccandola si apre la sua pagina (DispositivoScreen), con le sue regole una per
// una, ciascuna con la sua striscia. Logica pura, provata in LogicaDispositiviTest.

/** Di chi è una scheda: un dispositivo, o gli impegni di vita reale (che sono del figlio). */
sealed interface DiChi {
    data class Dispositivo(val id: Long) : DiChi
    data object Impegni : DiChi
}

/** Com'è andata oggi, in breve, per la riga della card del patto. */
enum class OggiInBreve {
    /** Nessuna regola in vigore oggi (un dispositivo appena collegato, o scollegato). */
    NESSUNA_REGOLA,

    /** Ci sono regole, ma oggi nessuna ha ancora dati (né mantenuta né fuori). */
    SENZA_DATI,

    /** Nessuna regola fuori: almeno una mantenuta, le altre senza dati. */
    TUTTO_DENTRO,

    /** Almeno una regola fuori: quante, in [SchedaDispositivo.fuoriOggi]. */
    FUORI,
}

/**
 * Un dispositivo (o gli impegni) con le sue regole.
 *
 * [giorni] per un dispositivo è la SUA striscia del server, la stessa che il
 * figlio vede in grande su quel dispositivo (senza la vita reale, che ha la sua
 * scheda); per gli impegni è messa insieme dai loro semafori, con la regola della
 * striscia del server (un rosso basta, poi un verde, poi grigio).
 */
data class SchedaDispositivo(
    val diChi: DiChi,
    /** Il dispositivo; null per gli impegni. */
    val dispositivo: VistaDispositivo?,
    val giorni: List<GiornoPatto>,
    /** Le regole di oggi ([regoleDelGiorno]): quelle in vigore e quelle che oggi hanno contato. */
    val oggi: List<RegolaDelGiorno>,
    /** Le regole che non ci sono più e oggi non hanno contato: solo nella pagina, in fondo. */
    val nonPiuAttive: List<RegolaFinestra>,
) {
    val fuoriOggi: Int get() = oggi.count { it.segnale == Segnale.FUORI_REGOLA }

    val oggiInBreve: OggiInBreve
        get() = when {
            oggi.isEmpty() -> OggiInBreve.NESSUNA_REGOLA
            fuoriOggi > 0 -> OggiInBreve.FUORI
            oggi.any { it.segnale == Segnale.MANTENUTA } -> OggiInBreve.TUTTO_DENTRO
            else -> OggiInBreve.SENZA_DATI
        }
}

/**
 * Tutte le schede del figlio: una per dispositivo, nell'ordine dei dispositivi
 * (come [raggruppaRegole]: anche senza regole se il dispositivo è attivo,
 * scollegato solo se ha regole), poi gli impegni se ce ne sono. Vuota su un
 * server 0.7 (nessun dispositivo: la card resta com'era).
 */
fun schedeDeiDispositivi(finestra: Finestra): List<SchedaDispositivo> {
    if (!finestraPerDispositivo(finestra)) return emptyList()
    val date = finestra.striscia.map { it.data }
    val delGiorno = date.lastOrNull()?.let { regoleDelGiorno(finestra, it) }.orEmpty().associateBy { it.regola.id }
    return raggruppaRegole(finestra.regole, dispositiviDellaFinestra(finestra)).mapNotNull { gruppo ->
        val oggi = gruppo.regole.mapNotNull { delGiorno[it.id] }
        val nonPiuAttive = gruppo.regole.filter { it.id !in delGiorno }
        when (gruppo.genere) {
            GenereGruppo.DISPOSITIVO -> {
                val dispositivo = gruppo.dispositivo ?: return@mapNotNull null
                val id = dispositivo.id ?: return@mapNotNull null
                SchedaDispositivo(DiChi.Dispositivo(id), dispositivo, giorniDaQuadretti(dispositivo.striscia), oggi, nonPiuAttive)
            }
            GenereGruppo.IMPEGNI ->
                SchedaDispositivo(DiChi.Impegni, null, aggregaSemafori(gruppo.regole, date), oggi, nonPiuAttive)
            // Regole senza dispositivo che non sono vita reale: in v3 non esistono.
            // Restano in "Tutte le regole".
            GenereGruppo.ALTRE -> null
        }
    }
}

/**
 * Le schede da mostrare nella card del patto: un dispositivo scollegato solo
 * finché ha giorni con dati negli 8 della striscia (come le righe "Computer: 5
 * su 7" dell'app del figlio); poi esce, le sue regole restano in "Tutte le regole".
 */
fun schedeDellaCard(finestra: Finestra): List<SchedaDispositivo> =
    schedeDeiDispositivi(finestra).filter { scheda ->
        scheda.dispositivo?.revocato != true || contaGiorni(scheda.giorni).second > 0
    }

/** La scheda di [diChi], per la sua pagina; null = non c'è (più). */
fun schedaDi(finestra: Finestra, diChi: DiChi): SchedaDispositivo? =
    schedeDeiDispositivi(finestra).firstOrNull { it.diChi == diChi }

/**
 * La striscia di un gruppo di regole messa insieme dai loro semafori, nelle
 * [date] della striscia del figlio: rosso se una è rossa, altrimenti verde se
 * una è verde, altrimenti senza dati. È la regola di `_aggrega` del server.
 */
fun aggregaSemafori(regole: List<RegolaFinestra>, date: List<String>): List<GiornoPatto> =
    date.map { data ->
        val segnali = regole.mapNotNull { segnaleDellaRegola(it, data) }
        val segnale = when {
            Segnale.FUORI_REGOLA in segnali -> Segnale.FUORI_REGOLA
            Segnale.MANTENUTA in segnali -> Segnale.MANTENUTA
            else -> Segnale.NESSUN_DATO
        }
        GiornoPatto(data, segnale)
    }
