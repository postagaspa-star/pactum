package eu.stgm.pactum.genitore.ui

import eu.stgm.pactum.design.GiornoPatto
import eu.stgm.pactum.design.Segnale
import eu.stgm.pactum.design.segnaleDaStato
import eu.stgm.pactum.genitore.dati.AutoriProposta
import eu.stgm.pactum.genitore.dati.ChiusureSessione
import eu.stgm.pactum.genitore.dati.CodiciErrore
import eu.stgm.pactum.genitore.dati.DirezioniProposta
import eu.stgm.pactum.genitore.dati.EventoFinestra
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.Notifica
import eu.stgm.pactum.genitore.dati.Proposta
import eu.stgm.pactum.genitore.dati.QuadrettoSemaforo
import eu.stgm.pactum.genitore.dati.RegolaFinestra
import eu.stgm.pactum.genitore.dati.RiepilogoFinestra
import eu.stgm.pactum.genitore.dati.Sessione
import eu.stgm.pactum.genitore.dati.SessioneSvolta
import eu.stgm.pactum.genitore.dati.StatiProposta
import eu.stgm.pactum.genitore.dati.StatiSessione
import eu.stgm.pactum.genitore.dati.TipiRegola
import eu.stgm.pactum.genitore.dati.UsoGiorno
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Duration
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

// --- Le regole di un giorno, nella scheda del patto (0.9) ---------------------------

/**
 * Lo stato di [regola] in [giorno] (ISO), dal suo `semaforo`: la stessa voce che
 * colora la sua striscia piccola, calcolata dal server con la stessa funzione
 * della striscia del patto. Nessuna logica nuova: verde = mantenuta, rosso =
 * fuori regola, grigio (o altro) = senza dati — per la vita reale verde =
 * confermata, rosso = non riuscita, grigio = nessuna conferma. null = il
 * semaforo non ha quel giorno (server vecchio, o giorno fuori dagli 8).
 */
fun segnaleDellaRegola(regola: RegolaFinestra, giorno: String): Segnale? =
    regola.semaforo.firstOrNull { it.data == giorno }?.let { segnaleDaStato(it.stato) }

/**
 * Una riga dell'elenco "oggi, regola per regola" della scheda del patto.
 * [minutiOltre] solo per un limite di tempo fuori regola, se i minuti si sanno.
 */
data class RegolaDelGiorno(
    val regola: RegolaFinestra,
    val segnale: Segnale,
    val minutiOltre: Int? = null,
)

/**
 * Le regole di [giorno] per la scheda del patto, in ordine di id:
 * - quelle in vigore (attive, non di un dispositivo scollegato), anche quando
 *   quel giorno non hanno dati: il padre vede anche le regole mantenute, non
 *   solo quello che è andato storto;
 * - e quelle che quel giorno hanno contato nella striscia (verde o rosso) anche
 *   se adesso non ci sono più (eliminate quel giorno, o di un dispositivo
 *   scollegato quel giorno): così un giorno rosso ha sempre la sua regola rossa.
 * Lo stato viene da [segnaleDellaRegola].
 */
fun regoleDelGiorno(finestra: Finestra, giorno: String): List<RegolaDelGiorno> {
    val inVigore = regoleProponibili(finestra).map { it.id }.toSet()
    val dispositivi = dispositiviDellaFinestra(finestra)
    return finestra.regole.sortedBy { it.id }.mapNotNull { regola ->
        val segnale = segnaleDellaRegola(regola, giorno)
        val contato = segnale == Segnale.MANTENUTA || segnale == Segnale.FUORI_REGOLA
        if (regola.id !in inVigore && !contato) return@mapNotNull null
        val stato = segnale ?: Segnale.NESSUN_DATO
        RegolaDelGiorno(
            regola = regola,
            segnale = stato,
            minutiOltre = if (stato == Segnale.FUORI_REGOLA) minutiOltreDellaRegola(regola, giorno, dispositivi) else null,
        )
    }
}

/**
 * Di quanto un limite di tempo è andato oltre in [giorno]: i minuti del giorno
 * sul limite di quel giorno (base + bonus), dalla stessa voce di `uso_recente`
 * che usa il Tempo (app, categoria o totale del dispositivo con quel
 * `regola_id`). null = non si sa (fasce, siti, giorno senza fotografia, limite
 * che quel giorno era un altro) o non è oltre.
 */
fun minutiOltreDellaRegola(regola: RegolaFinestra, giorno: String, dispositivi: List<VistaDispositivo>): Int? {
    if (regola.tipo != TipiRegola.LIMITE_TEMPO || !limiteValidoIl(giorno, regola)) return null
    val idDispositivo = regola.dispositivoId ?: regola.dispositivo?.id
    val uso = dispositivi.firstOrNull { it.id == idDispositivo }
        ?.usoRecente
        ?.firstOrNull { it.giorno == giorno }
        ?: return null
    val totale = uso.totaleMinuti
    val oltre = when {
        uso.regolaId == regola.id && totale != null -> minutiOltre(totale, uso.limite, uso.bonus)
        else -> uso.app.firstOrNull { it.regolaId == regola.id }?.let { minutiOltre(it.minuti, it.limite, it.bonus) }
            ?: uso.categorie.firstOrNull { it.regolaId == regola.id }?.let { minutiOltre(it.minuti, it.limite, it.bonus) }
    }
    return oltre?.takeIf { it > 0 }
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

/** (0.9) Quante richieste insieme per "Segna tutte come lette": il server è un NAS di casa. */
const val SEGNATURE_IN_PARALLELO = 4

/**
 * (0.9) [lavoro] su ogni voce, al massimo [massimo] alla volta; i risultati
 * nell'ordine delle voci. "Segna tutte come lette" fa una richiesta per
 * notifica: con cento notifiche, non cento richieste nello stesso istante.
 */
suspend fun <T, R> perOgnuna(voci: List<T>, massimo: Int, lavoro: suspend (T) -> R): List<R> = coroutineScope {
    val posti = Semaphore(massimo.coerceAtLeast(1))
    voci.map { voce -> async { posti.withPermit { lavoro(voce) } } }.awaitAll()
}

// --- Avvisi: l'ultimo controllo (0.9) ----------------------------------------------

/** Oltre questa durata dall'ultimo controllo, la riga "Avvisi: ultimo controllo" si fa notare. */
val SOGLIA_CONTROLLO_VECCHIO: Duration = Duration.ofMinutes(15)

/**
 * true = l'ultimo giro della vedetta andato a buon fine è più vecchio di 15
 * minuti: Pactum non riesce a guardare il patto (server che non risponde,
 * telefono senza rete, app fermata), e gli avvisi possono arrivare in ritardo.
 */
fun controlloVecchio(ultimo: Instant, adesso: Instant): Boolean =
    Duration.between(ultimo, adesso) > SOGLIA_CONTROLLO_VECCHIO

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
 * modifica" non si offre. (0.10) Vale per le proposte di tutti e due: sulla
 * stessa regola non ci sono mai due richieste incrociate (contratto v3.4).
 */
fun regoleConPropostaInAttesa(proposte: List<Proposta>): Set<Long> =
    proposte.filter { it.stato == StatiProposta.PENDENTE }.map { it.regolaId }.toSet()

/**
 * (0.10) Le regole su cui la proposta in attesa è DEL FIGLIO: lì "Proponi una
 * modifica" non c'è, e la riga dice che la sua proposta è da decidere.
 */
fun regoleConPropostaDelFiglio(proposte: List<Proposta>): Set<Long> =
    proposte.filter { it.stato == StatiProposta.PENDENTE && it.autore == AutoriProposta.FIGLIO }
        .map { it.regolaId }
        .toSet()

// --- Le proposte del figlio (0.10) ---------------------------------------------------

/**
 * Una proposta appena chiusa da qui: decisa, ritirata, o che il server ha detto
 * non più in attesa. [alle] = quando, sull'orologio monotono del telefono;
 * [figlioId] = di chi era (null = server 0.7).
 *
 * Fa da ponte fino alla prima lettura riuscita INIZIATA dopo: nei dati letti
 * prima la sua card non si vede più ([chiusaPrimaDellaLettura]), quelli letti dopo
 * dicono com'è davvero — anche se, dopo un ripristino del server, lo stesso id
 * fosse quello di un'altra proposta.
 */
data class PropostaChiusa(val figlioId: Long?, val alle: Long)

/**
 * true = nei dati letti a partire da [lettaAlle] (orologio monotono, quando è
 * PARTITA la lettura) la proposta [id] risulta ancora com'era prima di essere
 * chiusa da qui: la card non si mostra. Una lettura partita nello stesso istante,
 * o dati di cui non si sa quando sono stati letti (null), valgono come vecchi:
 * vale "dopo" solo una lettura partita dopo, come in [chiusureDaTenere].
 */
fun chiusaPrimaDellaLettura(id: Long, giaChiuse: Map<Long, PropostaChiusa>, lettaAlle: Long?): Boolean {
    val chiusa = giaChiuse[id] ?: return false
    return lettaAlle == null || lettaAlle <= chiusa.alle
}

/**
 * L'elenco delle proposte che "Proposte e conferme" ha in mano: di quale figlio,
 * e quando è partita la lettura che l'ha portato (null = nessuna lettura ancora
 * riuscita per quel figlio).
 */
data class LetturaElenco(val figlioId: Long?, val iniziataAlle: Long?)

/**
 * Le chiusure che fanno ancora da ponte. Quella di un figlio si lascia andare
 * quando nessuna delle due schermate può più avere i dati di prima:
 * - la finestra della Panoramica di quel figlio è stata riletta con una lettura
 *   partita dopo ([lettureFinestra]: l'inizio dell'ultima lettura riuscita, per
 *   figlio — la Panoramica si ricorda la finestra di ogni figlio visto);
 * - e l'elenco di "Proposte e conferme" ([elenco], null = nessuno) non è di quel
 *   figlio, oppure è di una lettura partita dopo.
 * Un elenco di un altro figlio non conta: cambiando figlio l'elenco si rilegge da capo.
 */
fun chiusureDaTenere(
    giaChiuse: Map<Long, PropostaChiusa>,
    elenco: LetturaElenco?,
    lettureFinestra: Map<Long?, Long>,
): Map<Long, PropostaChiusa> = giaChiuse.filterValues { chiusa ->
    val elencoDiPrima = elenco != null && elenco.figlioId == chiusa.figlioId &&
        (elenco.iniziataAlle == null || elenco.iniziataAlle <= chiusa.alle)
    val finestraDiDopo = lettureFinestra[chiusa.figlioId]?.let { it > chiusa.alle } == true
    elencoDiPrima || !finestraDiDopo
}

/**
 * Le proposte del figlio che aspettano il genitore (contratto v3.4): in attesa e
 * con autore "figlio", una volta ciascuna, dalla più recente. Quelle appena
 * chiuse da qui ([giaChiuse]) non ci sono, finché i dati sono di una lettura
 * iniziata prima ([lettaAlle]).
 */
fun proposteDaDecidere(
    proposte: List<Proposta>,
    giaChiuse: Map<Long, PropostaChiusa> = emptyMap(),
    lettaAlle: Long? = null,
): List<Proposta> =
    proposte
        .filter { it.stato == StatiProposta.PENDENTE && it.autore == AutoriProposta.FIGLIO }
        .filterNot { chiusaPrimaDellaLettura(it.id, giaChiuse, lettaAlle) }
        .distinctBy { it.id }
        .sortedByDescending { it.id }

/**
 * Le proposte che aspettano la risposta del FIGLIO: quelle del genitore ancora
 * in attesa (un autore che non si conosce resta qui, senza "Ritira"). Nell'ordine
 * del server, dalla più recente. [giaChiuse] e [lettaAlle] come in [proposteDaDecidere].
 */
fun proposteInAttesaDelFiglio(
    proposte: List<Proposta>,
    giaChiuse: Map<Long, PropostaChiusa> = emptyMap(),
    lettaAlle: Long? = null,
): List<Proposta> =
    proposte
        .filter { it.stato == StatiProposta.PENDENTE && it.autore != AutoriProposta.FIGLIO }
        .filterNot { chiusaPrimaDellaLettura(it.id, giaChiuse, lettaAlle) }

/**
 * L'elenco di "Proposte e conferme": quelle di GET /api/proposte (al massimo le
 * ultime 50) più le pendenti della finestra che lì mancano — una proposta in
 * attesa più vecchia delle ultime 50 non deve sparire, né lasciare "Proponi una
 * modifica" su una regola che ne ha già una. Una volta sola per id; prima
 * l'ordine del server, poi le aggiunte.
 */
fun proposteUnite(elenco: List<Proposta>, pendentiDellaFinestra: List<Proposta>): List<Proposta> {
    val noti = elenco.map { it.id }.toSet()
    return elenco + pendentiDellaFinestra.filter { it.id !in noti }.distinctBy { it.id }
}

/**
 * true = la regola è di un dispositivo scollegato ([scollegati], v.
 * dispositiviScollegati): una proposta su di lei non si può più accettare (il
 * server risponde `dispositivo_revocato`), solo rifiutare.
 */
fun suDispositivoScollegato(regola: RegolaFinestra?, scollegati: Set<Long>): Boolean =
    (regola?.dispositivoId ?: regola?.dispositivo?.id)?.let { it in scollegati } == true

/**
 * I nomi leggibili che la finestra conosce, per chiave (pacchetti e `exe:`): dalle
 * fotografie (`uso_recente`, di primo livello e di ogni dispositivo, dal giorno
 * più vecchio al più recente: vince il più recente) e dal `nome` delle regole.
 * Servono a dire il bersaglio di una proposta che cambia app ("da TikTok a
 * Instagram") senza scrivere mai un pacchetto. Solo nomi veri: un `nome` uguale
 * alla chiave (il ripiego del server) non conta.
 */
fun nomiDelleApp(finestra: Finestra): Map<String, String> {
    val nomi = mutableMapOf<String, String>()
    fun ricorda(chiave: String?, nome: String?) {
        val vera = chiave?.trim()?.takeIf { it.isNotEmpty() } ?: return
        nomeVero(vera, nome)?.let { nomi[vera] = it }
    }
    (listOf(finestra.usoRecente) + finestra.dispositivi.map { it.usoRecente }).forEach { giorni ->
        giorni.forEach { giorno -> giorno.app.forEach { ricorda(it.chiave, it.nome) } }
    }
    finestra.regole.forEach { regola ->
        ricorda((regola.parametri["app_o_categoria"] as? JsonPrimitive)?.contentOrNull, regola.nome)
    }
    return nomi
}

/** La storia: le proposte chiuse, di tutti e due gli autori, nell'ordine del server. */
fun proposteChiuse(proposte: List<Proposta>): List<Proposta> =
    proposte.filter { it.stato != StatiProposta.PENDENTE }

/** true = il genitore può ritirare questa proposta: è sua ed è ancora in attesa. */
fun ritirabile(proposta: Proposta): Boolean =
    proposta.autore == AutoriProposta.GENITORE && proposta.stato == StatiProposta.PENDENTE

/** true = la proposta chiede di togliere la regola (il marcatore `{"azione": "elimina"}`). */
fun eliminazione(proposta: Proposta): Boolean =
    proposta.direzione == DirezioniProposta.ELIMINA ||
        (proposta.parametriProposti["azione"] as? JsonPrimitive)?.contentOrNull == "elimina"

// --- Le sessioni (0.11, contratto v3.5) ----------------------------------------------
// Il figlio si crea una sessione ("Studio": alcune app del telefono); il genitore
// la approva una volta, e di nuovo a ogni cambio della lista. Qui si decide che
// cosa aspetta il genitore, che cosa cambia, e come raccontare le sessioni fatte.

/**
 * La chiave del contratto per "tutte le app installate fuori dal Play Store",
 * comprese quelle installate dopo. Minuscola, proprio così.
 */
const val GRUPPO_APK = "gruppo:apk"

/** true = la chiave è il gruppo delle app installate fuori dal Play Store (da APK). */
fun eGruppoApk(chiave: String?): Boolean = chiave?.trim()?.lowercase() == GRUPPO_APK

/** Le chiavi di una lista di app: senza spazi ai bordi, senza vuote, senza doppioni. */
private fun chiaviPulite(chiavi: List<String>): List<String> =
    chiavi.map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { if (eGruppoApk(it)) GRUPPO_APK else it }
        .distinct()

/**
 * Un'app di una sessione, a parole. [nome] = quello dei dati d'uso del telefono
 * (le fotografie e le regole della finestra), o, se lì non c'è, quello che manda la
 * sessione. [nellaSessione] = il nome che le dà la sessione quando è diverso da
 * quello dei dati d'uso: si mostrano tutti e due, così un'etichetta sbagliata
 * ("Calcolatrice" su TikTok) non nasconde l'app vera.
 */
data class NomeApp(val nome: String, val nellaSessione: String? = null)

/**
 * Le app di una sessione come si mostrano: [nomi] in ordine alfabetico;
 * [senzaNome] = le chiavi delle app senza un nome leggibile (sulla card da
 * approvare si mostra la chiave, perché il genitore non approvi un'app che non
 * riconosce; altrove si contano); [gruppoApk] = c'è il gruppo delle app installate
 * fuori dal Play Store (sempre in fondo).
 */
data class AppDellaSessione(val nomi: List<NomeApp>, val senzaNome: List<String>, val gruppoApk: Boolean) {
    val vuota: Boolean get() = nomi.isEmpty() && senzaNome.isEmpty() && !gruppoApk
}

/** Il nome leggibile di una chiave secondo i dati d'uso del telefono, se c'è. */
private fun nomeDallUso(chiave: String, nomiFinestra: Map<String, String>): String? = nomeVero(chiave, nomiFinestra[chiave])

/**
 * Le app di [chiavi] coi loro nomi: prima quello dei dati d'uso ([nomiFinestra],
 * v. nomiDelleApp), che non dipende dalla sessione; poi quello della sessione
 * ([nomiSessione], risolto sul telefono del figlio). Se tutti e due ci sono e non
 * coincidono, si tengono tutti e due ([NomeApp.nellaSessione]). Un nome che è la
 * chiave stessa non è un nome.
 */
fun appDellaSessione(
    chiavi: List<String>,
    nomiSessione: Map<String, String>,
    nomiFinestra: Map<String, String> = emptyMap(),
): AppDellaSessione {
    val pulite = chiaviPulite(chiavi)
    val pacchetti = pulite.filterNot(::eGruppoApk)
    val conNome = mutableListOf<NomeApp>()
    val senzaNome = mutableListOf<String>()
    pacchetti.forEach { chiave ->
        val dallUso = nomeDallUso(chiave, nomiFinestra)
        val dallaSessione = nomeVero(chiave, nomiSessione[chiave])
        when {
            dallUso != null -> conNome += NomeApp(dallUso, dallaSessione?.takeIf { !it.equals(dallUso, ignoreCase = true) })
            dallaSessione != null -> conNome += NomeApp(dallaSessione)
            else -> senzaNome += chiave
        }
    }
    return AppDellaSessione(
        nomi = conNome.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.nome }),
        senzaNome = senzaNome,
        gruppoApk = pacchetti.size < pulite.size,
    )
}

/**
 * Quante app si mostrano per nome prima di "e altre N", sulla card da approvare e
 * nelle righe: nella domanda prima del sì ci sono tutte.
 */
const val APP_VISIBILI = 12

/**
 * Un'app che resta nella sessione ma cambia nome: [prima] e [dopo] sono i nomi che
 * le danno la sessione approvata e il cambio. Come la chiamano i dati d'uso del
 * telefono lo dice il testo ([nomeSecondoLUso]): se è diverso da [dopo], si mostra.
 */
data class NomeCambiato(val chiave: String, val prima: String, val dopo: String)

/** Come chiamano i dati d'uso del telefono l'app [chiave] ([nomiFinestra], v. nomiDelleApp); null se non lo si sa. */
fun nomeSecondoLUso(chiave: String, nomiFinestra: Map<String, String>): String? = nomeDallUso(chiave, nomiFinestra)

/**
 * Che cosa cambia un cambio chiesto su una sessione approvata: le chiavi in più
 * ([aggiunte]), quelle in meno ([tolte]), quelle che restano, il nome nuovo se
 * cambia (null = stesso nome), e le app che restano ma cambiano nome
 * ([nomiCambiati]).
 */
data class CambioSessione(
    val aggiunte: List<String>,
    val tolte: List<String>,
    val restano: List<String>,
    val nuovoNome: String?,
    val nomiCambiati: List<NomeCambiato> = emptyList(),
) {
    /** true = non cambiano né le app, né il nome della sessione. */
    private val appENomeUguali: Boolean get() = aggiunte.isEmpty() && tolte.isEmpty() && nuovoNome == null

    /** true = non cambia proprio niente: stesse app, stesso nome, stessi nomi delle app. */
    val vuoto: Boolean get() = appENomeUguali && nomiCambiati.isEmpty()

    /** true = cambiano solo i nomi di alcune app: le app e il nome della sessione restano. */
    val soloNomi: Boolean get() = appENomeUguali && nomiCambiati.isNotEmpty()
}

/**
 * Il cambio in attesa di [sessione], confrontato con la versione approvata;
 * null = nessun cambio in attesa. Un campo del cambio che manca vale "come adesso".
 */
fun cambioSessione(sessione: Sessione): CambioSessione? {
    val modifica = sessione.modificaInAttesa ?: return null
    val prima = chiaviPulite(sessione.app)
    val dopo = modifica.app?.let(::chiaviPulite) ?: prima
    val restano = dopo.filter { it in prima }
    val nomiDopo = modifica.nomi
    val nomiCambiati = if (nomiDopo == null) {
        emptyList()
    } else {
        restano.filterNot(::eGruppoApk).mapNotNull { chiave ->
            val vecchio = nomeVero(chiave, sessione.nomi[chiave]) ?: return@mapNotNull null
            val nuovo = nomeVero(chiave, nomiDopo[chiave]) ?: return@mapNotNull null
            if (vecchio == nuovo) null else NomeCambiato(chiave, vecchio, nuovo)
        }
    }
    return CambioSessione(
        aggiunte = dopo.filter { it !in prima },
        tolte = prima.filter { it !in dopo },
        restano = restano,
        nuovoNome = modifica.nome?.trim()?.takeIf { it.isNotEmpty() && it != sessione.nome.trim() },
        nomiCambiati = nomiCambiati,
    )
}

/**
 * Una sessione che aspetta il genitore, e che cosa chiede: una sessione nuova (o
 * cambiata prima di essere approvata, [cambio] false) oppure un cambio a una già
 * approvata ([cambio] true, con le sue [differenze]). [nome], [app] e [nomi] sono
 * come sarebbero se il genitore approva.
 */
data class SessioneDaApprovare(
    val sessione: Sessione,
    val cambio: Boolean,
    val nome: String,
    val app: List<String>,
    val nomi: Map<String, String>,
    val differenze: CambioSessione? = null,
)

/**
 * Quello che [sessione] aspetta dal genitore; null = niente. Una sessione
 * `in_attesa` chiede di essere approvata; una `approvata` con `modifica_in_attesa`
 * chiede un cambio (un cambio esiste solo su una sessione approvata: su ogni altro
 * stato non si mostra). Le sessioni dei telefoni scollegati le toglie
 * [sessioniDaApprovare], come le toglie il server dal suo conto.
 */
fun richiestaInAttesa(sessione: Sessione): SessioneDaApprovare? {
    if (sessione.stato == StatiSessione.IN_ATTESA) {
        return SessioneDaApprovare(
            sessione = sessione,
            cambio = false,
            nome = sessione.nome.trim(),
            app = chiaviPulite(sessione.app),
            nomi = sessione.nomi,
        )
    }
    if (sessione.stato != StatiSessione.APPROVATA) return null
    val modifica = sessione.modificaInAttesa ?: return null
    return SessioneDaApprovare(
        sessione = sessione,
        cambio = true,
        nome = modifica.nome?.trim()?.takeIf { it.isNotEmpty() } ?: sessione.nome.trim(),
        app = modifica.app?.let(::chiaviPulite) ?: chiaviPulite(sessione.app),
        nomi = sessione.nomi + modifica.nomi.orEmpty(),
        differenze = cambioSessione(sessione),
    )
}

/**
 * Una sessione appena decisa da qui (o trovata non più in attesa): di quale
 * figlio ([figlioId], null = server 0.7) e quando ([alle], orologio monotono del
 * telefono). Fa da ponte fino alla prima lettura della finestra partita dopo,
 * come [PropostaChiusa]: la sua card sparisce subito.
 */
data class SessioneDecisa(val figlioId: Long?, val alle: Long)

/**
 * true = nei dati letti a partire da [lettaAlle] la sessione [id] è ancora com'era
 * prima della decisione presa da qui: la sua card non si mostra. Dati di cui non
 * si sa quando sono stati letti (null) valgono come vecchi.
 */
fun decisaPrimaDellaLettura(id: Long, decise: Map<Long, SessioneDecisa>, lettaAlle: Long?): Boolean {
    val decisa = decise[id] ?: return false
    return lettaAlle == null || lettaAlle <= decisa.alle
}

/**
 * Le decisioni che fanno ancora da ponte dopo una lettura riuscita della finestra
 * di [figlioId], partita a [letturaIniziataAlle]: quelle di quel figlio prese
 * prima non servono più (la finestra dice com'è davvero); quelle degli altri
 * figli restano, finché non si rilegge la loro.
 */
fun deciseDaTenere(
    decise: Map<Long, SessioneDecisa>,
    figlioId: Long?,
    letturaIniziataAlle: Long,
): Map<Long, SessioneDecisa> =
    decise.filterValues { it.figlioId != figlioId || it.alle >= letturaIniziataAlle }

/** true = la sessione è di un telefono scollegato ([scollegati], v. dispositiviScollegati). */
fun suTelefonoScollegato(sessione: Sessione, scollegati: Set<Long>): Boolean =
    (sessione.dispositivoId ?: sessione.dispositivo?.id)?.let { it in scollegati } == true

/**
 * Quando è stata chiesta una richiesta: un cambio dal suo `richiesta_ts`, una
 * sessione nuova (o un cambio senza orario) da quando è stata creata. null se non
 * si sa.
 */
fun chiestaAlle(richiesta: SessioneDaApprovare): Instant? {
    val sessione = richiesta.sessione
    val delCambio = if (richiesta.cambio) istanteServer(sessione.modificaInAttesa?.richiestaTs) else null
    return delCambio ?: istanteServer(sessione.creataTs)
}

/**
 * Le sessioni che aspettano il genitore, una card ciascuna, dalla richiesta più
 * recente (poi, senza orario, dalla sessione più recente). Non ci sono:
 * - quelle dei telefoni scollegati ([scollegati]): non si possono più approvare, e
 *   il server non le conta (le dice la sezione Sessioni, "non più valide");
 * - quelle appena decise da qui ([decise]), finché i dati sono di una lettura
 *   partita prima ([lettaAlle]).
 */
fun sessioniDaApprovare(
    sessioni: List<Sessione>,
    decise: Map<Long, SessioneDecisa> = emptyMap(),
    lettaAlle: Long? = null,
    scollegati: Set<Long> = emptySet(),
): List<SessioneDaApprovare> =
    sessioni
        .distinctBy { it.id }
        .filterNot { suTelefonoScollegato(it, scollegati) }
        .filterNot { decisaPrimaDellaLettura(it.id, decise, lettaAlle) }
        .mapNotNull(::richiestaInAttesa)
        .sortedWith(
            compareByDescending<SessioneDaApprovare> { chiestaAlle(it) ?: Instant.MIN }
                .thenByDescending { it.sessione.id },
        )

/**
 * Le sessioni con [nuova] al posto di quella col suo id: la sessione com'è adesso,
 * detta dal server nella risposta (o nel suo 409), si vede subito, senza
 * aspettare la finestra. Solo se è davvero più nuova: una risposta arrivata dopo
 * una lettura più fresca non riporta indietro la sessione (la `versione` di
 * [nuova] dev'essere almeno quella mostrata; senza versione non si sostituisce
 * niente). Una sessione che non c'era non si aggiunge.
 */
fun sostituisciSessione(sessioni: List<Sessione>, nuova: Sessione): List<Sessione> =
    sessioni.map { if (it.id == nuova.id && almenoComeMostrata(nuova, it)) nuova else it }

private fun almenoComeMostrata(nuova: Sessione, mostrata: Sessione): Boolean {
    val versione = nuova.versione ?: return false
    val vista = mostrata.versione ?: return true
    return versione >= vista
}

/**
 * La domanda aperta su una sessione (approva / non approvare): quale sessione, il
 * gesto ([esito]) e la [versione] che il genitore aveva davanti quando l'ha aperta.
 * Si salva così (un'app chiusa da Android la ritrova), e si risponde con quella
 * versione: mai con quella che c'è sullo schermo dopo.
 */
data class DomandaSessione(val sessioneId: Long, val esito: String, val versione: Int)

/** Che cosa fare della domanda aperta, coi dati di adesso. */
enum class StatoDomanda {
    /** La sessione chiede ancora la stessa cosa (stessa versione): la domanda resta. */
    VALIDA,

    /** Il figlio l'ha cambiata (altra versione): la domanda si chiude e lo si dice. */
    CAMBIATA,

    /** Non aspetta più il genitore (decisa, eliminata, telefono scollegato): la domanda si chiude. */
    NON_PIU_DA_DECIDERE,
}

/** La domanda [domanda] rispetto alle card di adesso ([daDecidere], v. sessioniDaApprovare). */
fun statoDomanda(domanda: DomandaSessione, daDecidere: List<SessioneDaApprovare>): StatoDomanda {
    val card = daDecidere.firstOrNull { it.sessione.id == domanda.sessioneId } ?: return StatoDomanda.NON_PIU_DA_DECIDERE
    return if (card.sessione.versione == domanda.versione) StatoDomanda.VALIDA else StatoDomanda.CAMBIATA
}

/** Com'è una sessione riletta quando il server dice che non c'era niente da decidere. */
enum class SessioneRiletta {
    /** Era già stata approvata (da te prima, o dall'altro genitore). */
    APPROVATA,

    /** Era già stata decisa: non approvata. */
    NON_APPROVATA,

    /** Il cambio chiesto non è più in attesa: deciso. */
    CAMBIO_DECISO,

    /** Il figlio l'ha eliminata. */
    ELIMINATA,

    /** Aspetta ancora, ma un'altra cosa: il figlio l'ha appena cambiata. */
    CAMBIATA,

    /** La rilettura non è riuscita, o lo stato non si capisce. */
    NON_SI_SA,
}

/**
 * Com'è finita la sessione [id] secondo le [sessioni] rilette subito dopo (null =
 * rilettura non riuscita). [cambio] = si stava decidendo un cambio a una sessione
 * approvata: allora "approvata" vuol dire solo che il cambio non aspetta più.
 */
fun sessioneRiletta(id: Long, cambio: Boolean, sessioni: List<Sessione>?): SessioneRiletta {
    if (sessioni == null) return SessioneRiletta.NON_SI_SA
    val sessione = sessioni.firstOrNull { it.id == id } ?: return SessioneRiletta.ELIMINATA
    if (richiestaInAttesa(sessione) != null) return SessioneRiletta.CAMBIATA
    return when {
        cambio -> SessioneRiletta.CAMBIO_DECISO
        sessione.stato == StatiSessione.APPROVATA -> SessioneRiletta.APPROVATA
        sessione.stato == StatiSessione.RIFIUTATA -> SessioneRiletta.NON_APPROVATA
        else -> SessioneRiletta.NON_SI_SA
    }
}

/**
 * Com'è andata la risposta del genitore a una sessione. [adesso] = la sessione
 * com'è adesso, quando il server l'ha detta (nella risposta, o nel 409
 * `richiesta_cambiata`): si mostra subito al posto di quella di prima.
 */
sealed interface EsitoSessione {
    /** Il server l'ha presa: [esito] `approva` o `rifiuta`; [cambio] = era un cambio a una sessione approvata. */
    data class Decisa(val esito: String, val cambio: Boolean, val adesso: Sessione? = null) : EsitoSessione

    /**
     * Non è passata, o non è partita: [codice] dice perché (null = rete caduta o un
     * rifiuto che non si conosce); [riletta] com'è la sessione, quando non c'era
     * più niente da decidere.
     */
    data class NonDecisa(
        val codice: String?,
        val riletta: SessioneRiletta? = null,
        val adesso: Sessione? = null,
    ) : EsitoSessione
}

/** La sessione com'è adesso secondo il server, se l'esito la porta. */
fun sessioneDopo(esito: EsitoSessione): Sessione? = when (esito) {
    is EsitoSessione.Decisa -> esito.adesso
    is EsitoSessione.NonDecisa -> esito.adesso
}

/**
 * Il 409 `richiesta_cambiata` sulla risposta a [mostrata]: niente è stato deciso,
 * e [adesso] è la sessione com'è sul server. La `versione` cambia a ogni cambio
 * del figlio ma anche a ogni decisione:
 * - se aspetta ancora qualcosa, il figlio ha cambiato la richiesta: la card si
 *   sostituisce con quella nuova, da guardare di nuovo;
 * - se non aspetta più niente, qualcuno (l'altro genitore) ha già deciso: si dice
 *   com'è finita, come per "niente da decidere";
 * - senza la sessione nel 409, si dice che è cambiata e si rilegge.
 */
fun esitoRichiestaCambiata(mostrata: SessioneDaApprovare, adesso: Sessione?): EsitoSessione.NonDecisa = when {
    adesso == null || richiestaInAttesa(adesso) != null ->
        EsitoSessione.NonDecisa(CodiciErrore.RICHIESTA_CAMBIATA, adesso = adesso)
    else -> EsitoSessione.NonDecisa(
        CodiciErrore.NIENTE_DA_DECIDERE,
        sessioneRiletta(adesso.id, mostrata.cambio, listOf(adesso)),
        adesso = adesso,
    )
}

/**
 * true = dopo [esito] la card di quella sessione si nasconde finché la finestra
 * non si rilegge: decisa, non più in attesa o eliminata. Se il server ha detto
 * com'è adesso ([EsitoSessione.NonDecisa.adesso]) e aspetta ancora qualcosa (il
 * figlio l'ha cambiata), la card nuova si mostra subito al suo posto, anche dopo
 * un "niente da decidere". Una rete caduta, un telefono scollegato o un server da
 * aggiornare non tolgono niente: la card resta.
 */
fun sessioneDaTogliere(esito: EsitoSessione): Boolean = when (esito) {
    is EsitoSessione.Decisa -> true
    is EsitoSessione.NonDecisa -> {
        val adesso = esito.adesso
        when {
            esito.codice !in CODICI_CHE_CHIUDONO -> false
            adesso != null -> richiestaInAttesa(adesso) == null
            else -> true
        }
    }
}

private val CODICI_CHE_CHIUDONO = setOf(
    CodiciErrore.NIENTE_DA_DECIDERE,
    CodiciErrore.NON_TROVATO,
    CodiciErrore.RICHIESTA_CAMBIATA,
)

/**
 * true = dopo [esito] sul server qualcosa è cambiato (o si è scoperto che era
 * cambiato): la finestra e il numero accanto al nome si rileggono. Una rete
 * caduta, un 422 o un server da aggiornare non hanno cambiato niente.
 */
fun daRileggereDopo(esito: EsitoSessione): Boolean = when (esito) {
    is EsitoSessione.Decisa -> true
    is EsitoSessione.NonDecisa -> esito.codice in CODICI_DA_RILEGGERE
}

private val CODICI_DA_RILEGGERE = setOf(
    CodiciErrore.NIENTE_DA_DECIDERE,
    CodiciErrore.NON_TROVATO,
    CodiciErrore.RICHIESTA_CAMBIATA,
    CodiciErrore.DISPOSITIVO_REVOCATO,
)

/**
 * Le sessioni approvate che il figlio può avviare, nella versione che si usa
 * adesso (anche con un cambio in attesa), in ordine di nome. Quelle di un telefono
 * scollegato ([scollegati]) non si avviano più: stanno fra le "non più valide".
 */
fun sessioniApprovate(sessioni: List<Sessione>, scollegati: Set<Long> = emptySet()): List<Sessione> =
    sessioni
        .filter { it.stato == StatiSessione.APPROVATA && !suTelefonoScollegato(it, scollegati) }
        .distinctBy { it.id }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.nome.trim() })

/**
 * Le sessioni non più valide perché il loro telefono è scollegato: approvate o in
 * attesa del genitore, ma non si avviano più (e il server non le conta da
 * approvare). In ordine di nome.
 */
fun sessioniNonPiuValide(sessioni: List<Sessione>, scollegati: Set<Long>): List<Sessione> =
    sessioni
        .filter {
            suTelefonoScollegato(it, scollegati) &&
                (it.stato == StatiSessione.APPROVATA || it.stato == StatiSessione.IN_ATTESA)
        }
        .distinctBy { it.id }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.nome.trim() })

// --- Le sessioni fatte (0.11) ----------------------------------------------------------

/** Come è finita (o non ancora) una sessione fatta. */
enum class FineSessione {
    /** Ancora in corso: la fine prevista non è arrivata. */
    IN_CORSO,

    /** Chiusa dal figlio prima della fine prevista ("Termina la sessione"). */
    CHIUSA_PRIMA,

    /** Durata tutto il tempo scelto all'avvio. */
    COMPLETA,

    /** Una chiusura che l'app non conosce: si dicono solo gli orari. */
    ALTRA,
}

/**
 * Una sessione fatta, pronta da raccontare. [finitaAlle] = la fine vera (null = in
 * corso, o non si sa); [finePrevista] e [durataPrevista] (minuti) quelle scelte
 * all'avvio, null se non si sanno.
 */
data class SessioneRaccontata(
    val svolta: SessioneSvolta,
    val fine: FineSessione,
    val inizio: Instant,
    val finitaAlle: Instant?,
    val finePrevista: Instant?,
    val durataPrevista: Long?,
)

/**
 * Come raccontare [svolta] [adesso]. Il server chiude una sessione scaduta solo
 * quando legge: una finestra letta prima della fine prevista può dire "in corso"
 * anche quando la fine è passata. Allora vale l'orologio: finita alla fine prevista,
 * come la chiuderà il server. Una "terminata" arrivata alla fine prevista non è
 * chiusa prima. null = inizio illeggibile: non si inventa un orario.
 */
fun raccontaSvolta(svolta: SessioneSvolta, adesso: Instant): SessioneRaccontata? {
    val inizio = istanteServer(svolta.inizioTs) ?: return null
    val durata = svolta.durataMinuti?.toLong()?.takeIf { it > 0 }
    val finePrevista = istanteServer(svolta.finePrevistaTs) ?: durata?.let { inizio.plus(Duration.ofMinutes(it)) }
    val durataPrevista = durata ?: finePrevista?.let { Duration.between(inizio, it).toMinutes() }?.takeIf { it > 0 }
    val fineVera = istanteServer(svolta.fineTs)
    val arrivataAllaFine = fineVera != null && finePrevista != null && !fineVera.isBefore(finePrevista)
    val (fine, finitaAlle) = when (svolta.chiusura) {
        null -> when {
            fineVera != null -> FineSessione.ALTRA to fineVera
            finePrevista != null && !adesso.isBefore(finePrevista) -> FineSessione.COMPLETA to finePrevista
            else -> FineSessione.IN_CORSO to null
        }
        ChiusureSessione.SCADUTA -> FineSessione.COMPLETA to (fineVera ?: finePrevista)
        ChiusureSessione.TERMINATA ->
            if (arrivataAllaFine) FineSessione.COMPLETA to fineVera else FineSessione.CHIUSA_PRIMA to fineVera
        else -> FineSessione.ALTRA to fineVera
    }
    return SessioneRaccontata(svolta, fine, inizio, finitaAlle, finePrevista, durataPrevista)
}

/** Le sessioni fatte negli 8 giorni, una volta sola ciascuna, dalla più recente. */
fun sessioniSvolteRaccontate(svolte: List<SessioneSvolta>, adesso: Instant): List<SessioneRaccontata> =
    svolte.distinctBy { it.id }
        .mapNotNull { raccontaSvolta(it, adesso) }
        .sortedByDescending { it.inizio }

/** Quante sessioni fatte si vedono senza toccare niente. */
const val SESSIONI_SVOLTE_VISIBILI = 5

// --- Tempo: dentro il patto / il resto della giornata ---------------------------

/**
 * Una voce dell'elenco del Tempo: un'app (o un programma, o un sito sul
 * computer) o una categoria, col limite se c'è.
 * [bonus] = minuti concessi quel giorno su quella regola (v2.4, 0 se nessuno o
 * server vecchio): il limite di quel giorno è `limite + bonus`, come per il figlio.
 * [bonusNoto] = false quando il bonus di quella regola non si conosce (un limite
 * su un sito, v3): allora "quanto oltre" non si calcola — meglio tacere che
 * dire un numero sbagliato.
 * [minutiNoti] = false quando i minuti di quel giorno non si sanno (un sito che
 * il programma del computer non è riuscito a leggere, v3): niente numero,
 * niente barra, niente "oltre" — [minuti] allora non vale niente.
 * [parziale] = per una parte del giorno il dato non si è potuto leggere: i
 * minuti mostrati possono essere di più, e la UI lo dice.
 */
data class VoceTempo(
    val chiave: String,
    val nome: String?,
    val minuti: Int,
    val limite: Int?,
    val categoria: Boolean,
    val bonus: Int = 0,
    val bonusNoto: Boolean = true,
    val minutiNoti: Boolean = true,
    val parziale: Boolean = false,
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

// --- Il limite di QUEL giorno (0.9) ------------------------------------------------

/**
 * true = il limite di [regola] valeva in [giorno] (ISO, giorno del patto): il
 * giorno è quello dell'ultima modifica della regola o uno dopo. Il server mette
 * accanto a ogni giorno degli 8 il limite di ADESSO; in un giorno PRIMA
 * dell'ultima modifica valeva un altro limite (o nessuno), e "20 min oltre"
 * direbbe una cosa falsa.
 *
 * Il giorno stesso della modifica vale: l'app del figlio valuta tutta la
 * giornata con la regola nuova (una regola creata alle 15:00 conta l'uso dalle
 * 00:00), quindi quel giorno il limite è in vigore e lo sforamento può arrivare
 * proprio quel giorno.
 *
 * Regola che non si trova, data illeggibile: si mostra come prima (non si sa, e
 * non si nasconde un limite per un dato mancante).
 */
fun limiteValidoIl(giorno: String, regola: RegolaFinestra?, zona: ZoneId = FUSO_PATTO): Boolean {
    if (regola == null) return true
    val modificata = istanteServer(regola.ultimaModificaTs)?.atZone(zona)?.toLocalDate() ?: return true
    val data = dataOppureNull(giorno) ?: return true
    return !data.isBefore(modificata)
}

/**
 * Il giorno del Tempo col solo limite che valeva davvero: toglie limite, regola
 * e bonus da app, categorie e totale del dispositivo nei giorni PRIMA
 * dell'ultima modifica della regola ([limiteValidoIl]). Il resto non cambia: i
 * minuti sono veri comunque.
 */
fun giornoConLimitiValidi(
    giorno: UsoGiorno,
    regole: Map<Long, RegolaFinestra>,
    zona: ZoneId = FUSO_PATTO,
): UsoGiorno {
    fun vale(regolaId: Long?): Boolean = regolaId == null || limiteValidoIl(giorno.giorno, regole[regolaId], zona)
    val totaleValido = giorno.limite == null || vale(giorno.regolaId)
    return giorno.copy(
        app = giorno.app.map { app ->
            if (app.limite == null || vale(app.regolaId)) app else app.copy(limite = null, regolaId = null, bonus = 0)
        },
        categorie = giorno.categorie.map { categoria ->
            if (categoria.limite == null || vale(categoria.regolaId)) {
                categoria
            } else {
                categoria.copy(limite = null, regolaId = null, bonus = 0)
            }
        },
        limite = if (totaleValido) giorno.limite else null,
        regolaId = if (totaleValido) giorno.regolaId else null,
        bonus = if (totaleValido) giorno.bonus else 0,
    )
}

// --- Il limite su tutto il dispositivo (v3.3) -------------------------------------

/**
 * La chiave del contratto per "tutto il dispositivo" (`app_o_categoria`): un
 * limite sul totale del giorno, "al telefono al massimo 3 ore". Minuscola,
 * proprio così; come l'app del figlio si tollerano spazi e maiuscole.
 */
const val CHIAVE_TOTALE = "totale"

/** true = la chiave è quella del totale del dispositivo. */
fun eTotale(chiave: String?): Boolean = chiave?.trim()?.lowercase() == CHIAVE_TOTALE

/**
 * Il limite su tutto il dispositivo di un giorno, letto come una voce del
 * patto: i minuti sono il totale del giorno, il limite e il bonus quelli che il
 * server mette accanto a `totale_minuti`. Così il Tempo lo mostra con le stesse
 * regole delle app: limite del giorno = base + bonus, "oltre" su quello.
 * null = nessuna regola sul totale, o giorno senza fotografia (niente totale,
 * niente confronto: mai uno zero finto).
 */
fun voceTotale(giorno: UsoGiorno): VoceTempo? {
    val totale = giorno.totaleMinuti ?: return null
    val limite = giorno.limite ?: return null
    return VoceTempo(
        chiave = CHIAVE_TOTALE,
        nome = null,
        minuti = totale,
        limite = limite,
        categoria = false,
        bonus = giorno.bonus,
    )
}

/**
 * Minuti usati sul limite del giorno (base + bonus): 1.0 = raggiunto, oltre 1 =
 * oltre. Una voce senza minuti noti non è vicina a niente: 0, in fondo al blocco.
 */
fun vicinanzaAlLimite(voce: VoceTempo): Double {
    val limite = voce.limiteDelGiorno ?: return 0.0
    if (!voce.minutiNoti) return 0.0
    return voce.minuti.toDouble() / limite.coerceAtLeast(1)
}

/**
 * Di quanto si è andati oltre il limite di quel giorno, cioè `limite + bonus`
 * (contratto v2.4, uso_recente): con +15 concessi, 70 su 60 è DENTRO, come lo
 * vede il figlio. 0 se dentro o senza limite; [bonus] 0 su un server vecchio.
 */
fun minutiOltre(minuti: Int, limite: Int?, bonus: Int = 0): Int =
    if (limite == null) 0 else (minuti - limite - bonus.coerceAtLeast(0)).coerceAtLeast(0)
