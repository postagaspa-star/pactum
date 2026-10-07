package eu.stgm.pactum.genitore.ui

import eu.stgm.pactum.genitore.dati.Dichiarazione
import eu.stgm.pactum.genitore.dati.Faccenda
import eu.stgm.pactum.genitore.dati.Figlio
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.Proposta
import eu.stgm.pactum.genitore.dati.RegolaFinestra
import eu.stgm.pactum.genitore.dati.StatiDichiarazione
import eu.stgm.pactum.genitore.dati.StatoBonus
import eu.stgm.pactum.genitore.dati.TipiRegola
import java.time.LocalDate
import java.time.ZoneId
import androidx.compose.ui.geometry.Offset
import java.time.Instant

// (0.15) La logica pura della Panoramica e di "Da decidere" (provata in
// LogicaRiordinoTest): quali righe di stato stanno in cima alla Panoramica, quante
// cose aspettano il genitore, e in che ordine si mostrano in "Da decidere".

// --- Le righe in cima alla Panoramica -----------------------------------------------

/**
 * Una riga compatta in cima alla Panoramica: c'è SOLO se c'è qualcosa da dire.
 * Quando tutto va bene non c'è nessuna riga (né "Aggiornato alle…", né "Avvisi:
 * ultimo controllo alle…").
 */
sealed interface RigaInCima {
    /** Quante richieste del figlio aspettano il genitore (→ Da decidere). */
    data class DaDecidere(val quante: Int) : RigaInCima

    /**
     * Il blocco dei lavori di casa: attivo, o in arrivo (→ Lavori). (0.16) Con le
     * foto che si possono ancora bocciare, se ce ne sono: non spariscono più
     * sotto il blocco.
     */
    data class Blocco(val stato: StatoBlocco, val fotoDaGuardare: Int = 0, val fotoDaApprovare: Int = 0) : RigaInCima

    /**
     * (0.18, contratto v4.0) Foto di lavori che aspettano l'approvazione, senza un
     * blocco partito o in arrivo (→ Lavori): "2 foto da approvare".
     */
    data class FotoDaApprovare(val quante: Int) : RigaInCima

    /** (0.16) Una sessione in corso adesso (→ Sessioni). */
    data class SessioneInCorso(val sessione: SessioneRaccontata) : RigaInCima

    /** Foto di lavori fatti che si possono ancora bocciare, senza un blocco (→ Lavori). */
    data class FotoDaGuardare(val quante: Int) : RigaInCima

    /** Un dispositivo che non manda aggiornamenti: una riga, non più la card piena col triangolo. */
    data class Silenzioso(val dispositivo: VistaDispositivo) : RigaInCima

    /** Gli avvisi di Pactum sono spenti su questo telefono (→ Impostazioni, Avvisi). */
    data object AvvisiSpenti : RigaInCima

    /** Pactum non riesce a guardare il patto da più di 15 minuti (→ Impostazioni, Avvisi). */
    data class AvvisiInRitardo(val ultimoControllo: Instant) : RigaInCima

    /** L'ultima lettura non è riuscita: questi sono i dati di prima. */
    data class DatiVecchi(val ricevutaAlle: Instant?) : RigaInCima
}

/**
 * Le righe in cima alla Panoramica, in quest'ordine: le richieste da decidere, il
 * blocco dei lavori (o, senza blocco, le foto da guardare), i dispositivi
 * silenziosi, gli avvisi, i dati non aggiornati.
 *
 * Gli avvisi in ritardo si dicono solo se questo telefono è in rete ([inRete]):
 * senza rete il ritardo non si sistema con la batteria (la riga porterebbe alle
 * impostazioni della batteria, che non c'entrano), e i dati non aggiornati lo
 * dicono già.
 */
fun righeInCima(
    daDecidere: Int,
    blocco: StatoBlocco?,
    fotoDaGuardare: Int,
    silenziosi: List<VistaDispositivo>,
    avvisiAccesi: Boolean,
    ultimoControllo: Instant?,
    adesso: Instant,
    inRete: Boolean,
    errore: Boolean,
    ricevutaAlle: Instant?,
    sessioniInCorso: List<SessioneRaccontata> = emptyList(),
    /** (0.18, v4.0) Le foto che aspettano l'approvazione di un genitore. */
    fotoDaApprovare: Int = 0,
): List<RigaInCima> = buildList {
    if (daDecidere > 0) add(RigaInCima.DaDecidere(daDecidere))
    when {
        blocco != null && (blocco.attivo || blocco.prossimo != null) ->
            add(RigaInCima.Blocco(blocco, fotoDaGuardare.coerceAtLeast(0), fotoDaApprovare.coerceAtLeast(0)))
        else -> {
            if (fotoDaApprovare > 0) add(RigaInCima.FotoDaApprovare(fotoDaApprovare))
            if (fotoDaGuardare > 0) add(RigaInCima.FotoDaGuardare(fotoDaGuardare))
        }
    }
    sessioniInCorso.forEach { add(RigaInCima.SessioneInCorso(it)) }
    silenziosi.forEach { add(RigaInCima.Silenzioso(it)) }
    when {
        !avvisiAccesi -> add(RigaInCima.AvvisiSpenti)
        inRete && ultimoControllo != null && controlloVecchio(ultimoControllo, adesso) ->
            add(RigaInCima.AvvisiInRitardo(ultimoControllo))
    }
    if (errore) add(RigaInCima.DatiVecchi(ricevutaAlle))
}

/** La chiave stabile di una riga in cima (le righe non saltano sotto il dito alle riletture). */
fun chiaveRiga(riga: RigaInCima): String = when (riga) {
    is RigaInCima.DaDecidere -> "riga-da-decidere"
    is RigaInCima.Blocco -> "riga-blocco"
    is RigaInCima.FotoDaGuardare -> "riga-foto"
    is RigaInCima.FotoDaApprovare -> "riga-foto-da-approvare"
    is RigaInCima.Silenzioso -> "riga-silenzio-${riga.dispositivo.id}"
    RigaInCima.AvvisiSpenti -> "riga-avvisi"
    is RigaInCima.AvvisiInRitardo -> "riga-avvisi"
    is RigaInCima.DatiVecchi -> "riga-dati-vecchi"
    is RigaInCima.SessioneInCorso -> "riga-sessione-${riga.sessione.svolta.id}"
}

// --- (0.16) Le sessioni, il bonus, "Da guardare insieme" --------------------------------

/**
 * Le sessioni del figlio in breve, per la riga "Sessioni" della Panoramica e per
 * la pagina: quelle in corso adesso, quante approvate, quante fatte negli 8 giorni
 * della finestra (chiuse), quante non più valide (telefono scollegato).
 */
data class ContoSessioni(
    val inCorso: List<SessioneRaccontata>,
    val approvate: Int,
    val fatte: Int,
    val nonPiuValide: Int,
) {
    val nessuna: Boolean get() = inCorso.isEmpty() && approvate == 0 && fatte == 0 && nonPiuValide == 0
}

fun contoSessioni(finestra: Finestra, adesso: Instant): ContoSessioni {
    val scollegati = dispositiviScollegati(finestra)
    val (inCorso, fatte) = sessioniInCorsoEFatte(finestra, adesso)
    return ContoSessioni(
        inCorso = inCorso,
        approvate = sessioniApprovate(finestra.sessioni, scollegati).size,
        fatte = fatte.size,
        nonPiuValide = sessioniNonPiuValide(finestra.sessioni, scollegati).size,
    )
}

/**
 * Le sessioni fatte negli 8 giorni divise in quelle in corso adesso e quelle
 * finite (raccontate rispetto ad [adesso]). Una "in corso" di un dispositivo
 * scollegato non è in corso: la revoca non chiude la sessione aperta, e la
 * finestra la darebbe in corso fino alla fine prevista. Non è nemmeno "fatta":
 * non si sa com'è finita, quindi non si racconta.
 */
fun sessioniInCorsoEFatte(finestra: Finestra, adesso: Instant): Pair<List<SessioneRaccontata>, List<SessioneRaccontata>> {
    val scollegati = dispositiviScollegati(finestra)
    val (inCorso, fatte) = sessioniSvolteRaccontate(finestra.sessioniSvolte, adesso)
        .partition { it.fine == FineSessione.IN_CORSO }
    return inCorso.filterNot { it.svolta.dispositivoId?.let { id -> id in scollegati } == true } to fatte
}

/**
 * Il bonus da dire per [dispositivo] nella card del patto: solo se ha un limite
 * di tempo attivo (il bonus allunga solo quelli) e non è scollegato. Su un server
 * 0.7 ([perDispositivo] false) le regole sono tutte dell'unico telefono.
 */
fun bonusDaMostrare(dispositivo: VistaDispositivo, regole: List<RegolaFinestra>, perDispositivo: Boolean): StatoBonus? {
    if (dispositivo.revocato) return null
    val conLimite = regole.any { regola ->
        regola.attiva && regola.tipo == TipiRegola.LIMITE_TEMPO &&
            (!perDispositivo || (regola.dispositivoId ?: regola.dispositivo?.id) == dispositivo.id)
    }
    return dispositivo.bonus.takeIf { conLimite }
}

/**
 * Quando mettere accanto a una voce di "Da guardare insieme": per un'interruzione
 * il giorno con l'ora ("ieri 15:10"), come nella 0.14; per un fuori regola (o un
 * giorno dichiarato) basta il giorno ("ieri", "01/10").
 */
fun quandoDaGuardare(
    parole: Parole,
    voce: VoceDaGuardare,
    oggi: LocalDate,
    zona: ZoneId = ZoneId.systemDefault(),
): String {
    val istante = istanteServer(voce.evento.tsServer)
    return if (voce.genere == GenereVoce.INTERRUZIONE && !voce.giornoDichiarato && istante != null) {
        testoQuando(parole, istante, zona, oggi)
    } else {
        testoGiorno(parole, voce.giorno, oggi)
    }
}

// --- Quante cose aspettano il genitore -----------------------------------------------

/** Le dichiarazioni ancora da confermare. */
fun dichiarazioniInAttesa(dichiarazioni: List<Dichiarazione>): Int =
    dichiarazioni.count { it.stato == StatiDichiarazione.IN_ATTESA }

/**
 * (0.15) Le proposte del figlio da decidere che si CONTANO: come le conta il
 * server nella famiglia, non quelle sulle regole di un dispositivo scollegato
 * (accettarle non si può più: restano nella lista solo per rifiutarle).
 */
fun proposteDaContare(finestra: Finestra, giaChiuse: Map<Long, PropostaChiusa>, lettaAlle: Long?): List<Proposta> {
    val scollegati = dispositiviScollegati(finestra)
    val regole = finestra.regole.associateBy { it.id }
    return proposteDaDecidere(finestra.propostePendenti, giaChiuse, lettaAlle)
        .filterNot { suDispositivoScollegato(regole[it.regolaId], scollegati) }
}

/**
 * (0.15) Quante cose aspettano il genitore per il figlio di [finestra]: le sue
 * proposte da decidere ([proposteDaContare]), le sue sessioni da approvare e le
 * sue dichiarazioni da confermare ([dichiarazioni] null = non ancora lette). Le
 * stesse letture da cui nasce la lista di "Da decidere": numero e lista non si
 * contraddicono, e il numero non salta aprendo la scheda. [giaChiuse],
 * [sessioniDecise] e [lettaAlle]: quelle appena decise da qui non si contano più,
 * finché la finestra è di prima.
 */
fun quanteDaDecidereDellaFinestra(
    finestra: Finestra,
    giaChiuse: Map<Long, PropostaChiusa>,
    sessioniDecise: Map<Long, SessioneDecisa>,
    lettaAlle: Long?,
    dichiarazioni: List<Dichiarazione>?,
    /** (0.18) La versione della configurazione dello Studio a cui si è appena risposto da qui. */
    studioDecisaVersione: Int? = null,
): Int =
    proposteDaContare(finestra, giaChiuse, lettaAlle).size +
        sessioniDaApprovare(finestra.sessioni, sessioniDecise, lettaAlle, dispositiviScollegati(finestra)).size +
        (dichiarazioni?.let(::dichiarazioniInAttesa) ?: 0) +
        // (0.18, contratto v4.0) Le foto da approvare e la configurazione dello Studio.
        quanteDaDecidereV40(finestra, studioDecisaVersione)

/**
 * Il numero sulla voce "Da decidere" della barra, di TUTTI i figli: per il figlio
 * scelto [delScelto] (dalla sua finestra, v. [quanteDaDecidereDellaFinestra]; null
 * = non si sa ancora), per gli altri quello della famiglia (proposte + sessioni:
 * GET /api/famiglia non conta le dichiarazioni).
 */
fun quanteDaDecidereInTutto(figli: List<Figlio>, sceltoId: Long?, delScelto: Int?): Int =
    figli.sumOf { figlio ->
        if (figlio.id == sceltoId && delScelto != null) delScelto.coerceAtLeast(0) else quanteDaDecidere(figlio)
    }

// --- "Da decidere": una lista sola, dal più vecchio ------------------------------------

/** Una voce di "Da decidere": quello che aspetta il genitore. */
sealed interface VoceDaDecidere {
    /** Quando è arrivata la richiesta (null = non si sa: va in fondo). */
    val quando: Instant?

    /** La chiave stabile nella lista. */
    val chiave: String

    data class DiProposta(val proposta: Proposta) : VoceDaDecidere {
        override val quando: Instant? get() = istanteServer(proposta.tsServer)
        override val chiave: String get() = "proposta-${proposta.id}"
    }

    data class DiSessione(val richiesta: SessioneDaApprovare) : VoceDaDecidere {
        override val quando: Instant? get() = chiestaAlle(richiesta)
        override val chiave: String get() = "sessione-${richiesta.sessione.id}"
    }

    data class DiDichiarazione(val dichiarazione: Dichiarazione) : VoceDaDecidere {
        override val quando: Instant? get() = istanteServer(dichiarazione.tsServer)
        override val chiave: String get() = "dichiarazione-${dichiarazione.id}"
    }

    /** (0.18, contratto v4.0) La foto di un lavoro che aspetta l'approvazione (si approva nei Lavori). */
    data class DiFoto(val faccenda: Faccenda) : VoceDaDecidere {
        override val quando: Instant? get() = istanteServer(faccenda.fotoTs)
        override val chiave: String get() = "foto-${faccenda.id}"
    }

    /** (0.18, contratto v4.0) La configurazione dello Studio da approvare. */
    data class DiStudio(val richiesta: RichiestaStudio) : VoceDaDecidere {
        override val quando: Instant? get() = istanteServer(richiesta.proposta.richiestaTs)
        override val chiave: String get() = "studio"
    }
}

/**
 * Tutto quello che aspetta il genitore, in UNA lista, dal più vecchio al più
 * nuovo: chi aspetta da più tempo viene prima. Senza orario in fondo; a parità,
 * proposte, poi sessioni, poi dichiarazioni, e dentro ciascun tipo per id.
 */
fun vociDaDecidere(
    proposte: List<Proposta>,
    sessioni: List<SessioneDaApprovare>,
    dichiarazioni: List<Dichiarazione>,
    /** (0.18) I lavori con la foto da approvare. */
    foto: List<Faccenda> = emptyList(),
    /** (0.18) La configurazione dello Studio da approvare. */
    studio: RichiestaStudio? = null,
): List<VoceDaDecidere> {
    val voci = proposte.map { VoceDaDecidere.DiProposta(it) } +
        sessioni.map { VoceDaDecidere.DiSessione(it) } +
        dichiarazioni.filter { it.stato == StatiDichiarazione.IN_ATTESA }.map { VoceDaDecidere.DiDichiarazione(it) } +
        foto.filter(::daApprovare).distinctBy { it.id }.map { VoceDaDecidere.DiFoto(it) } +
        listOfNotNull(studio?.let { VoceDaDecidere.DiStudio(it) })
    return voci.sortedWith(
        compareBy<VoceDaDecidere> { it.quando == null }
            .thenBy { it.quando ?: Instant.MAX }
            .thenBy { ordineTipo(it) }
            .thenBy { idDi(it) },
    )
}

private fun ordineTipo(voce: VoceDaDecidere): Int = when (voce) {
    is VoceDaDecidere.DiProposta -> 0
    is VoceDaDecidere.DiSessione -> 1
    is VoceDaDecidere.DiDichiarazione -> 2
    is VoceDaDecidere.DiFoto -> 3
    is VoceDaDecidere.DiStudio -> 4
}

private fun idDi(voce: VoceDaDecidere): Long = when (voce) {
    is VoceDaDecidere.DiProposta -> voce.proposta.id
    is VoceDaDecidere.DiSessione -> voce.richiesta.sessione.id
    is VoceDaDecidere.DiDichiarazione -> voce.dichiarazione.id
    is VoceDaDecidere.DiFoto -> voce.faccenda.id
    is VoceDaDecidere.DiStudio -> voce.richiesta.versione.toLong()
}

// --- Le liste lunghe: le prime N, poi "Vedi tutte" ----------------------------------------

/** Le voci di "Da guardare insieme" visibili senza toccare niente, nella Panoramica. */
const val VOCI_DA_GUARDARE_IN_PANORAMICA = 3

/** Le app e i siti del Tempo visibili prima di "Vedi tutte" / "Vedi tutti". */
const val VOCI_TEMPO_VISIBILI = 10

/** Le app di una sessione visibili sulla card da approvare, prima di "Vedi tutte". */
const val APP_VISIBILI_CARD = 4

/** Le prime [quante] di [voci], o tutte se [tutte]; e quante restano nascoste. */
fun <T> primeVoci(voci: List<T>, quante: Int, tutte: Boolean): Pair<List<T>, Int> =
    if (tutte || voci.size <= quante) voci to 0 else voci.take(quante) to (voci.size - quante)

// --- La foto a tutto schermo ------------------------------------------------------------

/**
 * Lo spostamento di una foto ingrandita di [scala] volte (dal centro) in uno
 * spazio [larghezza]×[altezza]: al massimo di quanto la foto sporge da ogni lato,
 * così non esce mai dallo schermo (B22). A scala 1 non si sposta.
 */
fun limitaSpostamento(spostamento: Offset, scala: Float, larghezza: Float, altezza: Float): Offset {
    val sporgenzaX = ((scala - 1f) * larghezza / 2f).coerceAtLeast(0f)
    val sporgenzaY = ((scala - 1f) * altezza / 2f).coerceAtLeast(0f)
    return Offset(spostamento.x.coerceIn(-sporgenzaX, sporgenzaX), spostamento.y.coerceIn(-sporgenzaY, sporgenzaY))
}
