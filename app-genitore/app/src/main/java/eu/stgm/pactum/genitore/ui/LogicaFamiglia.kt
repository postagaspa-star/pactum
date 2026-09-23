package eu.stgm.pactum.genitore.ui

import eu.stgm.pactum.design.contaGiorni
import eu.stgm.pactum.genitore.dati.BonusGiorno
import eu.stgm.pactum.genitore.dati.Dispositivo
import eu.stgm.pactum.genitore.dati.Figlio
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.Medie
import eu.stgm.pactum.genitore.dati.Notifica
import eu.stgm.pactum.genitore.dati.QuadrettoSemaforo
import eu.stgm.pactum.genitore.dati.RegolaFinestra
import eu.stgm.pactum.genitore.dati.SilenzioNoto
import eu.stgm.pactum.genitore.dati.SitiGiorno
import eu.stgm.pactum.genitore.dati.SitoVisitato
import eu.stgm.pactum.genitore.dati.StatoBonus
import eu.stgm.pactum.genitore.dati.StatoSilenzio
import eu.stgm.pactum.genitore.dati.TipiDispositivo
import eu.stgm.pactum.genitore.dati.TipiRegola
import eu.stgm.pactum.genitore.dati.UsoGiorno
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Duration
import java.time.Instant
import java.util.Locale

// Logica pura della v3 (famiglia, figli, dispositivi): niente Compose, niente
// risorse, così si prova con JUnit semplice (LogicaFamigliaTest). Qui si decide
// COSA mostrare e di chi è; le parole le mette la UI.

// --- Quale figlio -----------------------------------------------------------------

/**
 * Il figlio da mostrare: quello scelto l'ultima volta, se c'è ancora; altrimenti
 * il primo (lo stesso che il server usa quando `figlio_id` manca). null = nessun
 * figlio noto (server 0.7, o famiglia non ancora letta).
 */
fun figlioEffettivo(figli: List<Figlio>, scelto: Long?): Figlio? =
    figli.firstOrNull { it.id == scelto } ?: figli.firstOrNull()

// --- I dispositivi della finestra ------------------------------------------------

/**
 * Un dispositivo come lo usano Panoramica e Tempo. Arriva dai `dispositivi`
 * della finestra v3; su un server 0.7 ce n'è uno solo, ricostruito dai campi di
 * primo livello, con [id] e [nome] null — così le schermate hanno UNA strada
 * sola per tutti e due i server.
 */
data class VistaDispositivo(
    val id: Long?,
    val nome: String?,
    val tipo: String = TipiDispositivo.TELEFONO,
    val abbinato: Boolean = true,
    val revocato: Boolean = false,
    val statoSilenzio: StatoSilenzio? = null,
    val striscia: List<QuadrettoSemaforo> = emptyList(),
    val usoRecente: List<UsoGiorno> = emptyList(),
    val sitiRecenti: List<SitiGiorno>? = null,
    val medie: Medie? = null,
    val bonus: StatoBonus? = null,
    val bonusGiornalieri: List<BonusGiorno> = emptyList(),
) {
    val computer: Boolean get() = tipo == TipiDispositivo.COMPUTER
}

/**
 * I dispositivi della finestra, in ordine di id (revocati compresi). Server
 * 0.7 (nessun `dispositivi`): uno solo, dai campi di primo livello.
 */
fun dispositiviDellaFinestra(finestra: Finestra): List<VistaDispositivo> {
    if (finestra.dispositivi.isEmpty()) {
        return listOf(
            VistaDispositivo(
                id = null,
                nome = null,
                statoSilenzio = finestra.statoSilenzio,
                usoRecente = finestra.usoRecente,
                sitiRecenti = finestra.sitiRecenti,
                medie = finestra.medie,
                bonus = finestra.bonus,
                bonusGiornalieri = finestra.bonusGiornalieri,
            ),
        )
    }
    return finestra.dispositivi.sortedBy { it.id }.map {
        VistaDispositivo(
            id = it.id,
            nome = it.nome,
            tipo = it.tipo,
            abbinato = it.abbinato,
            revocato = it.revocato,
            statoSilenzio = it.statoSilenzio,
            striscia = it.striscia,
            usoRecente = it.usoRecente,
            sitiRecenti = it.sitiRecenti,
            medie = it.medie,
            bonus = it.bonus,
            bonusGiornalieri = it.bonusGiornalieri,
        )
    }
}

/** true = la finestra è della v3 (ha i suoi dispositivi): Panoramica per dispositivo. */
fun finestraPerDispositivo(finestra: Finestra): Boolean = finestra.dispositivi.isNotEmpty()

/**
 * Il dispositivo da mostrare nel Tempo: quello scelto, se c'è ancora; altrimenti
 * il primo non scollegato (un telefono scollegato non è la prima cosa da
 * guardare); altrimenti il primo.
 */
fun dispositivoEffettivo(dispositivi: List<VistaDispositivo>, scelto: Long?): VistaDispositivo? =
    dispositivi.firstOrNull { it.id != null && it.id == scelto }
        ?: dispositivi.firstOrNull { !it.revocato }
        ?: dispositivi.firstOrNull()

/**
 * Le strisce dei singoli dispositivi, accanto a quella del figlio. Principio D3:
 * il padre vede le stesse righe del figlio, con la STESSA regola delle righe
 * "Computer: 5 su 7" dell'app del figlio (TestoDispositivi.kt, RigheDispositivi):
 *
 * - contano i dispositivi attivi, e quelli scollegati finché hanno giorni con
 *   dati negli 8 della striscia: sono giorni che contano nella striscia del
 *   figlio, e il figlio li vede ("Computer (non più collegato): 2 su 3");
 * - le strisce ci sono solo se i dispositivi così contati sono almeno due: con
 *   uno solo la striscia del figlio È la sua.
 *
 * Nell'ordine dei dispositivi (id), come le manda il server.
 */
fun strisceDeiDispositivi(dispositivi: List<VistaDispositivo>): List<VistaDispositivo> {
    val righe = dispositivi
        .filter { it.id != null }
        .distinctBy { it.id }
        .filter { !it.revocato || giorniConDati(it.striscia) > 0 }
    return if (righe.size < 2) emptyList() else righe
}

/** Quanti giorni della striscia hanno dati (verdi o fuori regola), come li conta la frase grande. */
private fun giorniConDati(striscia: List<QuadrettoSemaforo>): Int =
    contaGiorni(giorniDaQuadretti(striscia)).second

/** Il nome di un dispositivo dal suo id (eventi, notifiche), null se non si conosce. */
fun nomeDispositivo(id: Long?, dispositivi: List<VistaDispositivo>): String? =
    id?.let { cercato -> dispositivi.firstOrNull { it.id == cercato } }?.nome?.takeIf { it.isNotBlank() }

// --- Lo stato del canale di un dispositivo -----------------------------------------

/** Che cosa si sa del contatto con un dispositivo. */
enum class StatoCanale {
    /** Manda dati: l'ultimo battito è recente. */
    IN_CONTATTO,

    /** Solo computer: spento, in sospensione o fuori dall'account. Non è un'interruzione. */
    SPENTO,

    /** Non manda dati da un po': un'interruzione nella registrazione. */
    SILENTE,

    /** Collegato, ma non ha ancora mandato niente. */
    MAI_SENTITO,

    /** Creato, non ancora collegato col codice. */
    DA_COLLEGARE,

    /** Revocato dal genitore: non manda più dati; la sua storia resta. */
    SCOLLEGATO,

    /** Il server non ha detto niente del contatto. */
    SCONOSCIUTO,
}

/**
 * Lo stato del canale deciso dai flag del SERVER. Per un computer `spento` vince
 * su tutto: un computer spento la sera è normale, non un silenzio. Per un
 * telefono `spento` non esiste (contratto v3) e decide `silente`.
 */
fun statoCanale(
    tipo: String,
    abbinato: Boolean,
    revocato: Boolean,
    silenzio: StatoSilenzio?,
): StatoCanale = when {
    revocato -> StatoCanale.SCOLLEGATO
    !abbinato -> StatoCanale.DA_COLLEGARE
    silenzio == null -> StatoCanale.SCONOSCIUTO
    tipo == TipiDispositivo.COMPUTER && silenzio.spento -> StatoCanale.SPENTO
    silenzio.silente && silenzio.ultimoBattito == null -> StatoCanale.MAI_SENTITO
    silenzio.silente -> StatoCanale.SILENTE
    else -> StatoCanale.IN_CONTATTO
}

fun statoCanale(dispositivo: VistaDispositivo): StatoCanale =
    statoCanale(dispositivo.tipo, dispositivo.abbinato, dispositivo.revocato, dispositivo.statoSilenzio)

fun statoCanale(dispositivo: Dispositivo): StatoCanale =
    statoCanale(dispositivo.tipo, dispositivo.abbinato, dispositivo.revocato, dispositivo.statoSilenzio)

// --- La vedetta: quando avvisare del silenzio -----------------------------------------

/** Quello che la vedetta vede di un dispositivo a questo giro. */
data class SilenzioAttuale(
    /** Un silenzio che è un'interruzione (mai un computer spento). */
    val allarme: Boolean,
    val spento: Boolean,
    val ultimoBattito: String?,
)

/**
 * Cosa guarda la vedetta di un dispositivo; null = niente da sorvegliare (non
 * ancora collegato, scollegato, o stato ignoto): nessun avviso, mai.
 */
fun silenzioDaSorvegliare(
    tipo: String,
    abbinato: Boolean,
    revocato: Boolean,
    silenzio: StatoSilenzio?,
): SilenzioAttuale? = when (statoCanale(tipo, abbinato, revocato, silenzio)) {
    StatoCanale.SCOLLEGATO, StatoCanale.DA_COLLEGARE, StatoCanale.SCONOSCIUTO -> null
    StatoCanale.SPENTO -> SilenzioAttuale(allarme = false, spento = true, ultimoBattito = silenzio?.ultimoBattito)
    StatoCanale.SILENTE, StatoCanale.MAI_SENTITO ->
        SilenzioAttuale(allarme = true, spento = false, ultimoBattito = silenzio?.ultimoBattito)
    StatoCanale.IN_CONTATTO ->
        SilenzioAttuale(allarme = false, spento = false, ultimoBattito = silenzio?.ultimoBattito)
}

enum class CambioSilenzio {
    /** Prima osservazione: si prende la base senza allarmare. */
    BASE,

    /** Niente di nuovo: si aggiorna solo il battito. */
    NESSUNO,

    /** Un silenzio nuovo: avviso. */
    NUOVO_SILENZIO,

    /** Il contatto è tornato: avviso tranquillo. */
    CONTATTO_TORNATO,

    /** Era in silenzio, ora risulta spento (computer): non era un'interruzione. */
    SPENTO_DOPO_SILENZIO,
}

/**
 * Il cambio da avvisare per UN dispositivo, dall'ultimo stato osservato.
 *
 * Un silenzio è nuovo anche se il ritorno in contatto non si è mai visto:
 * silente con un battito DIVERSO da quello osservato vuol dire contatto ripreso
 * e riperso tra due giri della vedetta. Il primo giro su un dispositivo (appena
 * collegato, o appena aggiornata l'app) prende la base: un silenzio già in corso
 * non è un "cambio". E il PRIMO battito di un dispositivo mai sentito non è un
 * "ripreso": non si era mai interrotto niente.
 */
fun cambioSilenzio(noto: SilenzioNoto?, attuale: SilenzioAttuale): CambioSilenzio = when {
    noto == null -> CambioSilenzio.BASE
    attuale.allarme && (!noto.silente || noto.ultimoBattito != attuale.ultimoBattito) ->
        CambioSilenzio.NUOVO_SILENZIO
    !attuale.allarme && noto.silente && noto.ultimoBattito == null -> CambioSilenzio.NESSUNO
    !attuale.allarme && noto.silente && attuale.spento -> CambioSilenzio.SPENTO_DOPO_SILENZIO
    !attuale.allarme && noto.silente -> CambioSilenzio.CONTATTO_TORNATO
    else -> CambioSilenzio.NESSUNO
}

// --- Un computer "spento" da troppo tempo ----------------------------------------------

/** Oltre questa durata un computer non si racconta più come "spento". */
val SOGLIA_SPENTO_A_LUNGO: Duration = Duration.ofHours(24)

/**
 * true = il computer risulta spento da PIÙ di 24 ore. Il server lo segna spento
 * dopo una sospensione e non gli dà scadenza: se Pactum non riparte (tolto
 * dall'avvio automatico, per esempio) il computer sembra spento per giorni.
 * Oltre le 24 ore l'app non scrive "Spento dal…": dice che dal computer non
 * arriva niente, e che cosa può essere. Non diventa un allarme: lo stato resta
 * [StatoCanale.SPENTO] e la vedetta non avvisa.
 */
fun spentoALungo(dal: Instant, adesso: Instant): Boolean =
    Duration.between(dal, adesso) > SOGLIA_SPENTO_A_LUNGO

// --- Gli id delle notifiche di sistema della vedetta -----------------------------------
// Tutti fuori dalla portata degli id del server: le notifiche del patto usano il loro.

/** Il dispositivo unico del server 0.7, nei silenzi osservati; e il "figlio 0" dei digest. */
const val CHIAVE_SERVER_VECCHIO = 0L

/** L'avviso di silenzio/contatto della 0.7 (un dispositivo solo). La 0.8 lo usa ancora su un server 0.7. */
const val ID_AVVISO_UNICO = 2_000_000_000

/** Il digest della 0.7 (un figlio solo). La 0.8 non lo usa: i suoi digest sono per figlio. */
const val ID_DIGEST_07 = 2_000_000_001

/**
 * L'avviso di silenzio/contatto di UN dispositivo (v3). Parte da 2.000.000.010,
 * lontano dai due id della 0.7: col vecchio calcolo il dispositivo 1 prendeva
 * 2.000.000.001, l'id del digest della 0.7 — il suo avviso avrebbe preso il
 * posto di quel digest, e scollegarlo lo avrebbe cancellato. La chiave 0 (server
 * 0.7) resta sull'id di prima: lì è davvero lo stesso avviso.
 */
fun idAvvisoSilenzio(chiave: Long): Int =
    if (chiave == CHIAVE_SERVER_VECCHIO) {
        ID_AVVISO_UNICO
    } else {
        2_000_000_010 + Math.floorMod(chiave, 100_000_000L).toInt()
    }

/** Il digest di un figlio (0 = server 0.7): quello di oggi sostituisce quello di ieri. */
fun idDigest(chiave: Long): Int = 1_900_000_000 + Math.floorMod(chiave, 50_000_000L).toInt()

// --- Il passaggio dalla 0.7 (un figlio, un dispositivo) alla 0.8 ------------------------

/**
 * Il dispositivo che la 0.7 guardava: il primo non scollegato del figlio con
 * l'id più basso — lo stesso dei campi di primo livello della finestra, che la
 * 0.7 leggeva (contratto v3, "Compatibilità 0.7"). null se non c'è.
 */
fun dispositivoEreditato(figli: List<Figlio>): Dispositivo? =
    figli.minByOrNull { it.id }
        ?.dispositivi
        ?.sortedBy { it.id }
        ?.firstOrNull { !it.revocato }

/** Da dove parte la vedetta a questo giro, per dispositivo. */
data class PartenzaSilenzi(
    /** L'ultimo stato osservato di ciascun dispositivo (quelli mai osservati non ci sono). */
    val noti: Map<Long, SilenzioNoto>,
    /**
     * Primo giro per dispositivo: l'avviso con l'id di un dispositivo solo
     * ([ID_AVVISO_UNICO]) da qui in poi non lo aggiorna più nessuno, e va tolto.
     */
    val togliAvvisoUnico: Boolean = false,
)

/**
 * Gli stati da cui parte la vedetta con la famiglia (v3). [salvati] = i silenzi
 * per dispositivo già salvati (null = mai: primo giro dopo l'aggiornamento dalla
 * 0.7, o app nuova); [versioneVecchia] = lo stato salvato dalla 0.7
 * (`silenzio_noto`), che guardava un dispositivo solo.
 *
 * Al primo giro per dispositivo — nessun silenzio salvato, o solo quello del
 * server 0.7 (chiave 0) — lo stato di prima passa al dispositivo che la 0.7
 * guardava ([dispositivoEreditato]): così un silenzio già avvisato non si
 * riavvisa, e un contatto che torna si avvisa. E l'avviso di un dispositivo solo
 * si toglie: ogni dispositivo ha ormai il suo.
 */
fun partenzaSilenzi(
    salvati: Map<Long, SilenzioNoto>?,
    versioneVecchia: SilenzioNoto?,
    figli: List<Figlio>,
): PartenzaSilenzi {
    val perDispositivo = salvati.orEmpty().filterKeys { it != CHIAVE_SERVER_VECCHIO }
    val primoGiro = salvati == null || (salvati.isNotEmpty() && perDispositivo.isEmpty())
    if (!primoGiro) return PartenzaSilenzi(perDispositivo)
    val statoDiPrima = salvati?.get(CHIAVE_SERVER_VECCHIO) ?: versioneVecchia
    val erede = dispositivoEreditato(figli)?.id
    val noti = if (erede != null && statoDiPrima != null) perDispositivo + (erede to statoDiPrima) else perDispositivo
    return PartenzaSilenzi(noti, togliAvvisoUnico = true)
}

/**
 * Lo stato da cui parte la vedetta su un server 0.7 (un dispositivo solo, chiave
 * 0). Se la 0.8 non ha ancora salvato niente vale quello della 0.7: il silenzio
 * che la 0.7 aveva già avvisato non si riavvisa, e il suo "di nuovo in contatto"
 * sostituisce l'avviso della 0.7 (stesso id).
 */
fun silenzioDelServerVecchio(
    salvati: Map<Long, SilenzioNoto>?,
    versioneVecchia: SilenzioNoto?,
): SilenzioNoto? = if (salvati == null) versioneVecchia else salvati[CHIAVE_SERVER_VECCHIO]

/**
 * Il digest già mandato dalla versione di prima vale per il figlio che quella
 * versione conosceva: [erede] = il figlio con l'id più basso (0 sul server 0.7).
 * Senza questo passaggio, il giorno dell'aggiornamento il padre riceverebbe il
 * digest due volte. [inviati] = i digest già registrati dalla 0.8 ("figlio|giorno",
 * null = mai); [ultimoGiorno07] = `digest_ultimo_giorno` della 0.7.
 *
 * - Primo giro della 0.8 ([inviati] null): si parte dal giorno della 0.7, se c'è.
 *   L'insieme si salva anche vuoto: il passaggio si fa una volta sola.
 * - Un digest mandato dalla 0.8 su un server 0.7 (figlio 0) passa al figlio con
 *   l'id più basso quando il server diventa v3: è lo stesso figlio.
 *
 * null = niente da cambiare.
 */
fun digestDopoAggiornamento(inviati: Set<String>?, ultimoGiorno07: String?, erede: Long): Set<String>? {
    if (inviati == null) {
        return setOfNotNull(ultimoGiorno07?.trim()?.takeIf { it.isNotEmpty() }?.let { "$erede|$it" })
    }
    if (erede == CHIAVE_SERVER_VECCHIO) return null
    val delServerVecchio = inviati.filter { it.substringBefore('|') == CHIAVE_SERVER_VECCHIO.toString() }
    if (delServerVecchio.isEmpty()) return null
    val giaDellErede = inviati.any { it.substringBefore('|') == erede.toString() }
    val passati = if (giaDellErede) emptyList() else delServerVecchio.map { "$erede|${it.substringAfter('|')}" }
    return inviati - delServerVecchio.toSet() + passati
}

// --- Le regole raggruppate per dispositivo -------------------------------------------

enum class GenereGruppo {
    /** Le regole di un dispositivo (limiti e fasce). */
    DISPOSITIVO,

    /** La vita reale: è del figlio, non di un dispositivo. */
    IMPEGNI,

    /** Regole senza dispositivo che non sono vita reale: non dovrebbero esistere in v3. */
    ALTRE,
}

data class GruppoRegole(
    val genere: GenereGruppo,
    /** Il dispositivo delle regole; null per Impegni e Altre. */
    val dispositivo: VistaDispositivo?,
    val regole: List<RegolaFinestra>,
)

/**
 * Le regole della finestra raggruppate: un gruppo per dispositivo, nell'ordine
 * dei dispositivi (anche vuoto, se il dispositivo è attivo: "nessuna regola qui"
 * è un'informazione), poi gli Impegni della vita reale. Un dispositivo scollegato
 * compare solo se ha regole. Una regola di un dispositivo che la finestra non
 * elenca fa gruppo a sé, col nome allegato alla regola: non sparisce.
 * Dentro ogni gruppo le regole restano in ordine di id, come le manda il server.
 */
fun raggruppaRegole(
    regole: List<RegolaFinestra>,
    dispositivi: List<VistaDispositivo>,
): List<GruppoRegole> {
    val (impegni, altre) = regole.partition { it.tipo == TipiRegola.VITA_REALE }
    val perDispositivo = altre.groupBy { it.dispositivoId ?: it.dispositivo?.id }
    val noti = dispositivi.filter { it.id != null }
    val gruppi = mutableListOf<GruppoRegole>()

    noti.forEach { dispositivo ->
        val sue = perDispositivo[dispositivo.id].orEmpty().sortedBy { it.id }
        if (sue.isNotEmpty() || !dispositivo.revocato) {
            gruppi += GruppoRegole(GenereGruppo.DISPOSITIVO, dispositivo, sue)
        }
    }
    perDispositivo
        .filterKeys { id -> id != null && noti.none { it.id == id } }
        .toSortedMap(compareBy { it })
        .forEach { (id, sue) ->
            val riferimento = sue.firstNotNullOfOrNull { it.dispositivo }
            gruppi += GruppoRegole(
                GenereGruppo.DISPOSITIVO,
                VistaDispositivo(
                    id = id,
                    nome = riferimento?.nome,
                    tipo = riferimento?.tipo ?: TipiDispositivo.TELEFONO,
                ),
                sue.sortedBy { it.id },
            )
        }
    val orfane = perDispositivo[null].orEmpty()
    if (orfane.isNotEmpty()) gruppi += GruppoRegole(GenereGruppo.ALTRE, null, orfane.sortedBy { it.id })
    if (impegni.isNotEmpty()) gruppi += GruppoRegole(GenereGruppo.IMPEGNI, null, impegni.sortedBy { it.id })
    return gruppi
}

/**
 * Gli id dei dispositivi scollegati: le loro regole restano nella storia ma non
 * contano più, e non si propone più niente su di esse.
 */
fun dispositiviScollegati(finestra: Finestra): Set<Long> =
    finestra.dispositivi.filter { it.revocato }.map { it.id }.toSet()

/** Le regole su cui si può proporre: attive e non di un dispositivo scollegato. */
fun regoleProponibili(finestra: Finestra): List<RegolaFinestra> {
    val scollegati = dispositiviScollegati(finestra)
    return finestra.regole.filter { regola ->
        regola.attiva && (regola.dispositivoId ?: regola.dispositivo?.id)?.let { it in scollegati } != true
    }
}

// --- Notifiche: di quale figlio, di quale dispositivo ------------------------------

/**
 * La riga "di chi è" di una notifica: il nome del figlio se i figli sono più di
 * uno, e quello del dispositivo se quel figlio ne ha più di uno. null = non
 * serve (un figlio, un dispositivo) o non si sa (famiglia non letta).
 * La stessa per la lista in app e per la notifica di sistema.
 */
fun etichettaNotifica(notifica: Notifica, figli: List<Figlio>): String? =
    etichettaDi(notifica.figlioId, notifica.dispositivoId, figli)

/** La stessa riga "di chi è" per un figlio e un dispositivo qualunque (gli avvisi di silenzio). */
fun etichettaDi(figlioId: Long?, dispositivoId: Long?, figli: List<Figlio>): String? {
    val figlio = figli.firstOrNull { it.id == figlioId } ?: return null
    val dispositivo = figlio.dispositivi.firstOrNull { it.id == dispositivoId }
    val parti = buildList {
        if (figli.size > 1) add(figlio.nome)
        if (dispositivo != null && figlio.dispositivi.size > 1) add(dispositivo.nome)
    }.filter { it.isNotBlank() }
    return parti.joinToString(" · ").ifEmpty { null }
}

// --- Il codice di abbinamento -------------------------------------------------------

/** Quanto vale un codice di abbinamento (contratto v3): 15 minuti, una volta sola. */
val VALIDITA_CODICE: Duration = Duration.ofMinutes(15)

/**
 * Quanto vale ancora il codice nel momento in cui è arrivata la risposta,
 * secondo il SERVER: `scade_ts` meno l'ora del server scritta nella stessa
 * risposta (header `Date`, [oraServer]). Sono due ore dello stesso orologio,
 * quello del server: l'orologio del telefono non entra, e un telefono avanti di
 * cinque minuti non fa sembrare scaduto un codice buono. Mai meno di zero, mai
 * più dei 15 minuti del contratto. Senza una delle due ore valgono i 15 minuti:
 * il codice è appena nato.
 */
fun validitaCodice(scadeTs: String?, oraServer: Instant?): Duration {
    val scade = istanteServer(scadeTs) ?: return VALIDITA_CODICE
    val ora = oraServer ?: return VALIDITA_CODICE
    return Duration.between(ora, scade).coerceIn(Duration.ZERO, VALIDITA_CODICE)
}

/**
 * I secondi che restano al codice: la [validita] di quando è arrivato, meno il
 * tempo passato da allora ([trascorsoMs], misurato con l'orologio monotono del
 * telefono, che non salta se qualcuno cambia l'ora). Arrotondati per eccesso,
 * così il conto parte da "15:00" e arriva a zero solo quando è finito davvero.
 * Mai negativi.
 */
fun secondiRimasti(validita: Duration, trascorsoMs: Long): Long {
    val restoMs = validita.toMillis() - trascorsoMs.coerceAtLeast(0)
    return if (restoMs <= 0) 0 else (restoMs + 999) / 1000
}

/**
 * Il codice mostrato risulta usato: il dispositivo, che quando è nato il codice
 * non era collegato, adesso lo è. Per un ricollegamento (il dispositivo era già
 * collegato) la famiglia non lo può dire: false, e il dialogo resta col conto
 * alla rovescia.
 */
fun codiceUsato(figli: List<Figlio>, dispositivoId: Long?, ricollegamento: Boolean): Boolean {
    if (ricollegamento || dispositivoId == null) return false
    val dispositivo = figli.flatMap { it.dispositivi }.firstOrNull { it.id == dispositivoId } ?: return false
    return dispositivo.abbinato && !dispositivo.revocato
}

// --- I gesti sulla famiglia -------------------------------------------------------------

/**
 * Dopo un "Aggiungi un figlio" rimasto senza risposta (la rete è caduta): il
 * figlio che risulta creato, cioè un figlio NUOVO (un id che [prima] non c'era)
 * con quel nome, come lo salva il server (senza spazi ai bordi). null = non
 * risulta creato: riprovare non fa doppioni. Se ce n'è più d'uno, il più recente.
 */
fun figlioCreato(prima: List<Figlio>, dopo: List<Figlio>, nome: String): Figlio? {
    val noti = prima.map { it.id }.toSet()
    val cercato = nome.trim()
    return dopo.filter { it.id !in noti && it.nome.trim() == cercato }.maxByOrNull { it.id }
}

/**
 * Come [figlioCreato], per "Aggiungi un dispositivo": un dispositivo NUOVO di
 * quel figlio, con quel nome e quel tipo, non scollegato.
 */
fun dispositivoCreato(
    prima: List<Figlio>,
    dopo: List<Figlio>,
    figlioId: Long,
    nome: String,
    tipo: String,
): Dispositivo? {
    val noti = prima.flatMap { it.dispositivi }.map { it.id }.toSet()
    val cercato = nome.trim()
    return dopo.firstOrNull { it.id == figlioId }
        ?.dispositivi
        ?.filter { it.id !in noti && !it.revocato && it.tipo == tipo && it.nome.trim() == cercato }
        ?.maxByOrNull { it.id }
}

/**
 * I dispositivi ATTIVI (non scollegati) del figlio dello stesso tipo di quello
 * che si sta aggiungendo, in ordine di id. Se ce n'è uno, forse è lo stesso
 * telefono da ricollegare: il dialogo lo dice prima della conferma, senza
 * impedire niente. Collegati o ancora da collegare, tutti: per tutti la strada
 * giusta è "Nuovo codice" sulla loro riga.
 */
fun dispositiviDelloStessoTipo(figlio: Figlio, tipo: String): List<Dispositivo> =
    figlio.dispositivi.filter { !it.revocato && it.tipo == tipo }.sortedBy { it.id }

/** Il conto alla rovescia come si legge su un orologio: "14:05", "0:09". */
fun testoContoAllaRovescia(secondi: Long): String {
    val s = secondi.coerceAtLeast(0)
    return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
}

/** "483920" → "483 920": sei cifre si leggono e si dettano meglio in due gruppi. */
fun codiceADueGruppi(codice: String): String =
    if (codice.length == 6 && codice.all { it.isDigit() }) "${codice.take(3)} ${codice.takeLast(3)}" else codice

/** Un nome di figlio o di dispositivo accettabile per il contratto: 1-40 caratteri, spazi esclusi. */
fun nomeValido(nome: String): Boolean = nome.trim().length in 1..LUNGHEZZA_MASSIMA_NOME

const val LUNGHEZZA_MASSIMA_NOME = 40

// --- Tempo: i siti del giorno e i limiti sui siti -----------------------------------

/**
 * I siti di un giorno nell'ordine del contratto: sui computer (le voci hanno i
 * `minuti`) per minuti decrescenti, poi per dominio; sui telefoni per visite
 * decrescenti, poi per dominio. Si riapplica lo stesso ordine del server, così
 * una fotografia disordinata non cambia la lista che vede anche il figlio.
 */
fun sitiOrdinati(siti: SitiGiorno): List<SitoVisitato> =
    if (sitiConMinuti(siti)) {
        siti.domini.sortedWith(
            compareByDescending<SitoVisitato> { it.minuti ?: 0 }.thenBy { it.dominio },
        )
    } else {
        siti.domini.sortedWith(compareByDescending<SitoVisitato> { it.visite }.thenBy { it.dominio })
    }

/** true = la fotografia porta i minuti (computer): la lista si legge in tempo, non in visite. */
fun sitiConMinuti(siti: SitiGiorno): Boolean = siti.domini.any { it.minuti != null }

private const val PREFISSO_SITO = "sito:"

/**
 * I limiti sui siti (`sito:youtube.com`) di un computer, come voci di "DENTRO
 * IL PATTO" per un giorno. Il server mette i limiti accanto alle chiavi della
 * fotografia dei programmi (`exe:`), dove un sito non c'è: i minuti di un sito
 * stanno nei siti del giorno, e da lì si prendono.
 *
 * - Una regola già presente fra le voci del giorno (il server l'ha messa lui)
 *   non si duplica.
 * - Senza la fotografia dei siti di quel giorno la voce NON c'è: "0 minuti"
 *   sarebbe uno zero finto. Idem se la lista era tagliata e il sito non c'è.
 * - Se per una parte del giorno il programma non è riuscito a leggere i siti
 *   (`dns_cifrato`) e il sito non c'è, non si sa se è stato usato: la voce c'è
 *   (la regola esiste, e nasconderla sarebbe un altro silenzio) ma SENZA minuti
 *   ([VoceTempo.minutiNoti] false), con [VoceTempo.parziale] che la UI dice a
 *   parole. Se il sito c'è, i suoi minuti sono veri ma possono essere di più:
 *   la voce li mostra, e dice lo stesso che la lettura è parziale.
 * - Il bonus di QUELLA regola quel giorno non arriva: si sa solo il totale dei
 *   bonus del dispositivo. Se il totale è 0 il bonus è 0 davvero; altrimenti
 *   [VoceTempo.bonusNoto] è false e la UI non calcola "quanto oltre".
 */
fun vociSitiNelPatto(
    regoleDelDispositivo: List<RegolaFinestra>,
    giorno: UsoGiorno,
    siti: SitiGiorno?,
    bonusDelGiorno: Int?,
): List<VoceTempo> {
    val giaDentro = giorno.app.map { it.chiave }.toSet()
    return regoleDelDispositivo
        .filter { it.attiva && it.tipo == TipiRegola.LIMITE_TEMPO }
        .mapNotNull { regola ->
            val chiave = campoRegola(regola, "app_o_categoria") ?: return@mapNotNull null
            if (!chiave.startsWith(PREFISSO_SITO) || chiave in giaDentro) return@mapNotNull null
            val limite = campoRegola(regola, "minuti_al_giorno")?.toIntOrNull() ?: return@mapNotNull null
            if (siti?.totaleDomini == null) return@mapNotNull null
            val dominio = chiave.removePrefix(PREFISSO_SITO)
            val trovato = siti.domini.firstOrNull { it.dominio == dominio }
            val minuti: Int? = when {
                trovato != null -> trovato.minuti ?: return@mapNotNull null
                siti.totaleDomini > siti.domini.size -> return@mapNotNull null
                // Non letto per una parte del giorno: non si sa, e non si scrive zero.
                siti.dnsCifrato -> null
                else -> 0
            }
            VoceTempo(
                chiave = chiave,
                nome = null,
                minuti = minuti ?: 0,
                limite = limite,
                categoria = false,
                bonus = 0,
                bonusNoto = bonusDelGiorno == 0,
                minutiNoti = minuti != null,
                parziale = siti.dnsCifrato,
            )
        }
}

/** I minuti bonus di un dispositivo in un giorno, null se il giorno non c'è. */
fun bonusDelGiorno(bonusGiornalieri: List<BonusGiorno>, giorno: String): Int? =
    bonusGiornalieri.firstOrNull { it.giorno == giorno }?.minuti

private fun campoRegola(regola: RegolaFinestra, nome: String): String? =
    (regola.parametri[nome] as? JsonPrimitive)?.contentOrNull
