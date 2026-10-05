package eu.stgm.pactum.genitore.ui

import eu.stgm.pactum.genitore.dati.BloccoFaccende
import eu.stgm.pactum.genitore.dati.ConfigurazionePostino
import eu.stgm.pactum.genitore.dati.Dispositivo
import eu.stgm.pactum.genitore.dati.Faccenda
import eu.stgm.pactum.genitore.dati.Genitore
import eu.stgm.pactum.genitore.dati.MASSIMO_NOTA_FACCENDA
import eu.stgm.pactum.genitore.dati.MASSIMO_TITOLO_FACCENDA
import eu.stgm.pactum.genitore.dati.Notifica
import eu.stgm.pactum.genitore.dati.RiferimentoGenitore
import eu.stgm.pactum.genitore.dati.StatiFaccenda
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// Logica pura della 0.13 (contratto v3.6: più genitori e le faccende): niente
// Compose, niente risorse, così si prova con JUnit semplice (FaccendeTest). Qui
// si decide COSA mostrare e quando; le parole le mette Testi.kt.

// --- Chi ha fatto cosa ------------------------------------------------------------

/** Chi ha fatto una cosa, visto da questo telefono. */
sealed interface ChiHaFatto {
    /** Tu (lo stesso genitore di `io`). */
    data object Tu : ChiHaFatto

    /** Un altro genitore, col suo nome di adesso. */
    data class Altro(val nome: String) : ChiHaFatto

    /** Non si sa: server più vecchio della v3.6 (un genitore solo), o nome vuoto. */
    data object NonSi : ChiHaFatto
}

/**
 * Chi ha fatto una cosa ([chi], dalle risposte del server), rispetto a chi sei tu
 * ([io], da GET /api/famiglia o /api/genitori). Senza [io] non si sa se sei tu:
 * vale il nome. Un nome vuoto non si scrive.
 */
fun chiHaFatto(chi: RiferimentoGenitore?, io: RiferimentoGenitore?): ChiHaFatto {
    if (chi == null) return ChiHaFatto.NonSi
    if (io != null && io.id == chi.id) return ChiHaFatto.Tu
    val nome = chi.nome.trim()
    return if (nome.isEmpty()) ChiHaFatto.NonSi else ChiHaFatto.Altro(nome)
}

// --- I genitori -------------------------------------------------------------------

/** Il codice per collegare il telefono di un genitore: 6 cifre (contratto v3.6). */
const val CIFRE_CODICE = 6

/**
 * Quello che il campo del codice tiene di quanto si scrive o si incolla: solo le
 * cifre, al massimo 6. "483 920" e "483-920" diventano "483920".
 */
fun soloCifre(testo: String): String = testo.filter { it in '0'..'9' }.take(CIFRE_CODICE)

/** true = [codice] è pronto da mandare: esattamente 6 cifre. */
fun codiceCompleto(codice: String): Boolean = codice.length == CIFRE_CODICE && codice.all { it in '0'..'9' }

/** Quanti genitori non revocati ci sono: l'ultimo non si toglie (`ultimo_genitore`). */
fun genitoriAttivi(genitori: List<Genitore>): Int = genitori.count { !it.revocato }

/**
 * true = a questo [genitore] si può offrire "Togli": non sei tu (`non_te_stesso`),
 * non è già tolto, e non è l'ultimo rimasto (`ultimo_genitore`). Il server decide
 * comunque: qui si evita solo di offrire un gesto che direbbe di no.
 */
fun puoiTogliere(genitore: Genitore, io: RiferimentoGenitore?, genitori: List<Genitore>): Boolean =
    !genitore.revocato && genitore.id != io?.id && genitoriAttivi(genitori) > 1

/**
 * true = a questo [genitore] si può offrire "Nuovo codice": non è tolto e non sei
 * tu. Un codice nuovo per te stesso, usato su un altro telefono, spegnerebbe
 * QUESTO telefono: lo crea un altro genitore, per te.
 */
fun puoiDareNuovoCodice(genitore: Genitore, io: RiferimentoGenitore?): Boolean =
    !genitore.revocato && genitore.id != io?.id

/**
 * Il codice mostrato per un genitore risulta usato: il genitore, che quando è nato
 * il codice non era collegato, adesso lo è. Per un ricollegamento (era già
 * collegato) l'elenco non lo può dire: false.
 */
fun codiceGenitoreUsato(genitori: List<Genitore>, genitoreId: Long?, ricollegamento: Boolean): Boolean {
    if (ricollegamento || genitoreId == null) return false
    val genitore = genitori.firstOrNull { it.id == genitoreId } ?: return false
    return genitore.abbinato && !genitore.revocato
}

/**
 * Dopo un "Aggiungi un genitore" rimasto senza risposta: il genitore che risulta
 * creato, cioè uno NUOVO (un id che [prima] non c'era) con quel nome, non tolto.
 * null = non risulta creato: riprovare non fa doppioni. Se ce n'è più d'uno, il
 * più recente.
 */
fun genitoreCreato(prima: List<Genitore>, dopo: List<Genitore>, nome: String): Genitore? {
    val noti = prima.map { it.id }.toSet()
    val cercato = nome.trim()
    return dopo.filter { it.id !in noti && !it.revocato && it.nome.trim() == cercato }.maxByOrNull { it.id }
}

// --- Il blocco --------------------------------------------------------------------

/**
 * Il blocco delle faccende in un momento: [attivo] (c'è una faccenda da fare il
 * cui blocco è già partito) da [dal]; se non è attivo, il [prossimo]; quante ce
 * ne sono da fare ([daFare]).
 */
data class StatoBlocco(
    val attivo: Boolean,
    val dal: Instant?,
    val prossimo: Instant?,
    val daFare: Int,
)

/**
 * Lo stato del blocco. Con [dalServer] (il `blocco` della finestra) vale quello
 * del server, che usa il suo orologio; senza, si calcola dalle faccende con le
 * stesse regole del contratto (GET /api/faccende/blocco): attivo se una da fare ha
 * `blocco_da` già passato, dal più vecchio di quei `blocco_da`; se no il prossimo
 * nel futuro. Una da fare senza `blocco_da` leggibile (il server lo manda sempre)
 * vale "subito": meglio dire bloccato che dire libero un telefono che non lo è.
 */
fun statoBlocco(faccende: List<Faccenda>, adesso: Instant, dalServer: BloccoFaccende? = null): StatoBlocco {
    val daFare = faccende.filter { it.stato == StatiFaccenda.DA_FARE }
    if (dalServer != null) {
        return StatoBlocco(
            attivo = dalServer.attivo,
            dal = istanteServer(dalServer.dal),
            prossimo = istanteServer(dalServer.prossimo).takeIf { !dalServer.attivo },
            daFare = daFare.size,
        )
    }
    val inizi = daFare.map { istanteServer(it.bloccoDa) }
    val partiti = inizi.filter { it == null || !it.isAfter(adesso) }
    val attivo = partiti.isNotEmpty()
    return StatoBlocco(
        attivo = attivo,
        dal = partiti.filterNotNull().minOrNull(),
        prossimo = if (attivo) null else inizi.filterNotNull().filter { it.isAfter(adesso) }.minOrNull(),
        daFare = daFare.size,
    )
}

// --- L'ora del blocco scelta dal genitore -----------------------------------------

/**
 * Da quando bloccano le faccende date "dalle [ora]": oggi se quell'ora deve ancora
 * venire, altrimenti domani ([domani] true: va detto chiaro prima di mandare).
 * L'ora è quella del telefono del genitore ([adesso] porta il suo fuso).
 */
data class InizioBlocco(val quando: ZonedDateTime, val domani: Boolean)

/**
 * Col cambio dell'ora (fuso del telefono):
 * - nell'ora doppia (fine ottobre, 02:00-03:00 due volte) l'ora scelta vale la
 *   prima volta che arriva; se quella è già passata ma la seconda no (adesso ha già
 *   lo scarto dell'ora solare), vale la seconda (`withLaterOffsetAtOverlap`), non
 *   domani;
 * - nell'ora che manca (fine marzo, 02:00-03:00 non esiste) l'ora scelta slitta
 *   avanti dello scarto (02:30 → 03:30), come fa java.time: il testo dice l'ora
 *   vera, quella che c'è.
 */
fun inizioBlocco(ora: LocalTime, adesso: ZonedDateTime): InizioBlocco {
    val scelta = ora.truncatedTo(ChronoUnit.MINUTES)
    occorrenzaFutura(adesso, scelta)?.let { return InizioBlocco(it, domani = false) }
    return InizioBlocco(ZonedDateTime.of(adesso.toLocalDate().plusDays(1), scelta, adesso.zone), domani = true)
}

/** L'ora [ora] di oggi ancora da venire, rispetto ad [adesso]; null se oggi è già passata (tutte e due le volte). */
private fun occorrenzaFutura(adesso: ZonedDateTime, ora: LocalTime): ZonedDateTime? {
    val prima = ZonedDateTime.of(adesso.toLocalDate(), ora, adesso.zone)
    if (prima.isAfter(adesso)) return prima
    val seconda = prima.withLaterOffsetAtOverlap()
    return seconda.takeIf { it.isAfter(adesso) }
}

/** Che cosa fare quando il genitore tocca "Dai le faccende". */
sealed interface ControlloInvio {
    /** Si manda, con questo `blocco_da` (null = subito). */
    data class Manda(val bloccoDa: String?) : ControlloInvio

    /**
     * Fra quello che il dialogo mostra e adesso, l'istante del blocco è cambiato
     * (il dialogo è rimasto aperto a cavallo di mezzanotte, o di un'ora che nel
     * frattempo è passata): non si manda niente, si mostra [nuovo].
     */
    data class Cambiato(val nuovo: InizioBlocco) : ControlloInvio
}

/**
 * Il controllo prima di mandare: l'istante VERO del blocco, ricalcolato adesso
 * dall'[ora] scelta (null = subito), deve essere quello che il dialogo mostra
 * ([mostrato]). Se no, niente parte: il genitore vede prima la data nuova.
 */
fun controlloPrimaDiMandare(mostrato: InizioBlocco?, ora: LocalTime?, adesso: ZonedDateTime): ControlloInvio {
    if (ora == null) return ControlloInvio.Manda(null)
    val nuovo = inizioBlocco(ora, adesso)
    if (mostrato == null || nuovo.quando.toInstant() != mostrato.quando.toInstant()) return ControlloInvio.Cambiato(nuovo)
    return ControlloInvio.Manda(testoBloccoDa(nuovo.quando))
}

/** L'ora che il selettore propone: la prossima ora piena ("15:10" → "16:00"). */
fun oraProposta(adesso: LocalTime): LocalTime = LocalTime.of((adesso.hour + 1) % 24, 0)

private val formatoBloccoDa: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")

/**
 * `blocco_da` per il server: una data CON il fuso ("2026-10-03T16:00:00+02:00"),
 * come chiede il contratto (un testo senza fuso → 422). Al minuto.
 */
fun testoBloccoDa(quando: ZonedDateTime): String = formatoBloccoDa.format(quando.truncatedTo(ChronoUnit.MINUTES))

// --- La foto e la bocciatura ------------------------------------------------------

/** Quanto tempo dopo la foto si può ancora bocciare una faccenda (contratto v3.6). */
val FINESTRA_BOCCIATURA: Duration = Duration.ofHours(24)

/** Si può bocciare una faccenda, adesso? */
sealed interface Bocciabile {
    /** Sì, per ancora [resta]. */
    data class Si(val resta: Duration) : Bocciabile

    /** Sono passate più di 24 ore dalla foto: non più. */
    data object Scaduta : Bocciabile

    /** Non è una faccenda fatta con la foto: non c'è niente da bocciare. */
    data object No : Bocciabile
}

/**
 * Si conta dall'ora della foto scritta dal SERVER (`foto_ts`) e dall'orologio del
 * telefono: è un'indicazione per il genitore, il server decide (`non_bocciabile`).
 */
fun bocciabile(faccenda: Faccenda, adesso: Instant): Bocciabile {
    if (faccenda.stato != StatiFaccenda.FATTA) return Bocciabile.No
    val foto = istanteServer(faccenda.fotoTs) ?: return Bocciabile.No
    val resta = Duration.between(adesso, foto.plus(FINESTRA_BOCCIATURA))
    return if (resta.isNegative || resta.isZero) Bocciabile.Scaduta else Bocciabile.Si(resta)
}

// --- Titoli e note ----------------------------------------------------------------

/** Che cosa non va in un titolo o in una nota, prima di mandarli. */
enum class ProblemaTesto {
    VUOTO,
    TROPPO_LUNGO,

    /** Caratteri invisibili o di controllo (a capo, tab, i segni che girano il testo): il server li rifiuta. */
    CARATTERI_INVISIBILI,
}

/**
 * Un titolo o una nota come li salva il server: in forma NFC (la stessa parola
 * scritta in due modi è la stessa parola), senza spazi ai bordi; nelle note gli a
 * capo di Windows diventano a capo semplici.
 */
fun ripulisci(testo: String): String =
    Normalizer.normalize(testo.replace("\r\n", "\n").replace('\r', '\n'), Normalizer.Form.NFC).trim()

/** Che cosa non va nel titolo di una faccenda (1-80 caratteri, niente invisibili); null = va bene. */
fun problemaTitolo(titolo: String): ProblemaTesto? {
    val pulito = ripulisci(titolo)
    return when {
        pulito.isEmpty() -> ProblemaTesto.VUOTO
        pulito.codePointCount(0, pulito.length) > MASSIMO_TITOLO_FACCENDA -> ProblemaTesto.TROPPO_LUNGO
        haInvisibili(pulito, aCapoAmmessi = false) -> ProblemaTesto.CARATTERI_INVISIBILI
        else -> null
    }
}

/**
 * Che cosa non va in una nota (di una faccenda o di una bocciatura): facoltativa,
 * fino a 300 caratteri, a capo ammessi, il resto come i titoli. null = va bene
 * (anche vuota).
 */
fun problemaNota(nota: String): ProblemaTesto? {
    val pulita = ripulisci(nota)
    return when {
        pulita.codePointCount(0, pulita.length) > MASSIMO_NOTA_FACCENDA -> ProblemaTesto.TROPPO_LUNGO
        haInvisibili(pulita, aCapoAmmessi = true) -> ProblemaTesto.CARATTERI_INVISIBILI
        else -> null
    }
}

/**
 * Le categorie Unicode che il server rifiuta (Cc, Cf, Zl, Zp: come per il nome di
 * una sessione). Un'emoji fatta di più pezzi uniti da un "unificatore invisibile"
 * (👨‍👩‍👧) ne contiene uno: anche quella il server la rifiuta, e lo si dice prima.
 */
private fun haInvisibili(testo: String, aCapoAmmessi: Boolean): Boolean =
    testo.codePoints().anyMatch { punto ->
        if (aCapoAmmessi && punto == '\n'.code) return@anyMatch false
        when (Character.getType(punto).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
            else -> false
        }
    }

/**
 * I titoli usati di recente, da toccare per riusarli: dalla storia di GET
 * /api/faccende, dal più recente, senza doppioni (maiuscole e spazi non contano) e
 * senza quelli già scritti nel modulo ([giaScritti]). Al massimo [quanti].
 */
fun titoliRecenti(faccende: List<Faccenda>, giaScritti: Collection<String> = emptyList(), quanti: Int = 8): List<String> {
    val esclusi = giaScritti.map { chiaveTitolo(it) }.toSet()
    return faccende
        .sortedWith(compareByDescending<Faccenda> { istanteServer(it.creataTs) ?: Instant.EPOCH }.thenByDescending { it.id })
        .map { ripulisci(it.titolo) }
        .filter { it.isNotEmpty() && problemaTitolo(it) == null }
        .distinctBy { chiaveTitolo(it) }
        .filterNot { chiaveTitolo(it) in esclusi }
        .take(quanti)
}

private fun chiaveTitolo(titolo: String): String = ripulisci(titolo).lowercase()

// --- L'elenco ---------------------------------------------------------------------

/** Le faccende divise come le legge il genitore. */
data class FaccendeInGruppi(
    /** Da fare, dalla più vecchia (lo stesso ordine che vede il figlio). */
    val daFare: List<Faccenda>,
    /** Fatte, dalla foto più recente. */
    val fatte: List<Faccenda>,
    /** Annullate, dalla più recente. */
    val annullate: List<Faccenda>,
)

/** Uno stato che non si conosce non entra in nessun gruppo: meglio tacere che mettere una faccenda dove non è. */
fun faccendeInGruppi(faccende: List<Faccenda>): FaccendeInGruppi {
    val uniche = faccende.distinctBy { it.id }
    return FaccendeInGruppi(
        daFare = uniche.filter { it.stato == StatiFaccenda.DA_FARE }
            .sortedWith(compareBy<Faccenda> { istanteServer(it.creataTs) ?: Instant.EPOCH }.thenBy { it.id }),
        fatte = uniche.filter { it.stato == StatiFaccenda.FATTA }
            .sortedWith(
                compareByDescending<Faccenda> { istanteServer(it.fotoTs ?: it.chiusaTs) ?: Instant.EPOCH }
                    .thenByDescending { it.id },
            ),
        annullate = uniche.filter { it.stato == StatiFaccenda.ANNULLATA }
            .sortedWith(compareByDescending<Faccenda> { istanteServer(it.chiusaTs) ?: Instant.EPOCH }.thenByDescending { it.id }),
    )
}

/**
 * Dopo un "Dai faccende" rimasto senza risposta (la rete è caduta): le faccende che
 * risultano create, cioè NUOVE (un id che [prima] non c'era), da fare, con uno dei
 * titoli mandati. Vuoto = non risultano create: riprovare non fa doppioni (il
 * server le crea tutte insieme o nessuna).
 */
fun faccendeCreate(prima: Collection<Long>, dopo: List<Faccenda>, titoli: List<String>): List<Faccenda> {
    val noti = prima.toSet()
    val cercati = titoli.map { ripulisci(it) }.toSet()
    return dopo.filter { it.id !in noti && it.stato == StatiFaccenda.DA_FARE && ripulisci(it.titolo) in cercati }
}

/** Com'è andato un "Dai faccende" la cui risposta si è persa, dopo aver riletto l'elenco. */
sealed interface DopoRispostaPersa {
    /** Le faccende ci sono: le ha date questo genitore, adesso. */
    data class Date(val quante: Int) : DopoRispostaPersa

    /** Non si sa: si dice di guardare l'elenco prima di riprovare (riprovare potrebbe darle due volte). */
    data object Incerto : DopoRispostaPersa

    /** Non ci sono: riprovare è sicuro (il server le crea tutte insieme o nessuna). */
    data object NonDate : DopoRispostaPersa
}

/** Il margine per l'orologio del telefono, quando si confronta con l'ora del server. */
val MARGINE_OROLOGIO: Duration = Duration.ofSeconds(5)

/**
 * Dopo un "Dai faccende" rimasto senza risposta: [prima] = gli id dell'elenco
 * prima dell'invio (null = mai letto: non si sa quali sono nuove), [dopo] =
 * l'elenco riletto (null = non arrivato), [titoli] = quelli mandati, [io] = chi
 * sei tu, [inizioInvio] = quando è partito l'invio (orologio del telefono).
 *
 * Contano come date da te SOLO le faccende nuove, da fare, con un titolo mandato,
 * con `creata_da` = te e create non prima dell'inizio dell'invio (al secondo, come
 * scrive il server, con [MARGINE_OROLOGIO]). Se ce ne sono di nuove coi titoli
 * mandati ma non si può dire che siano queste (un altro genitore, un orologio
 * storto, chi sei tu non si sa): incerto. Mai "riprova" quando riprovare potrebbe
 * dare le faccende due volte.
 */
fun esitoDopoRispostaPersa(
    prima: Collection<Long>?,
    dopo: List<Faccenda>?,
    titoli: List<String>,
    io: RiferimentoGenitore?,
    inizioInvio: Instant,
): DopoRispostaPersa {
    if (prima == null || dopo == null) return DopoRispostaPersa.Incerto
    val candidate = faccendeCreate(prima, dopo, titoli)
    if (candidate.isEmpty()) return DopoRispostaPersa.NonDate
    val dal = inizioInvio.truncatedTo(ChronoUnit.SECONDS).minus(MARGINE_OROLOGIO)
    val nostre = candidate.filter { faccenda ->
        io != null &&
            faccenda.creataDa?.id == io.id &&
            istanteServer(faccenda.creataTs)?.let { !it.isBefore(dal) } == true
    }
    return if (nostre.isNotEmpty()) DopoRispostaPersa.Date(nostre.size) else DopoRispostaPersa.Incerto
}

// --- La configurazione che cambia -----------------------------------------------------

/**
 * true = il collegamento è cambiato (un altro server, un altro codice): quello che
 * le schermate ricordano è di un altro collegamento, e va dimenticato. [nota] =
 * l'ultimo visto (null = nessuno ancora: la prima lettura non è un cambio).
 */
fun configurazioneCambiata(nota: ConfigurazionePostino?, nuova: ConfigurazionePostino): Boolean =
    nota != null && nota != nuova

// --- Le app troppo vecchie per il blocco ------------------------------------------

/** La prima versione delle app (e del programma del computer) che conosce il blocco delle faccende. */
const val VERSIONE_CON_BLOCCO = "0.13.0"

/** Un dispositivo del figlio su cui il blocco non parte: app più vecchia della 0.13, o versione che non si sa. */
data class DispositivoSenzaBlocco(
    val dispositivo: Dispositivo,
    /** La versione dichiarata ("0.12.0"); null = non l'ha mai detta, o non si legge. */
    val versione: String?,
)

/**
 * I dispositivi collegati (e non scollegati) del figlio con un'app più vecchia
 * della 0.13 (`versione_app` di GET /api/famiglia): lì il blocco non parte. Una
 * versione che manca o non si legge si dice anche lei: non si sa se il blocco
 * parte, e un genitore che crede bloccato un telefono libero sbaglia di più di uno
 * che controlla. Un dispositivo non ancora collegato non ha ancora un'app: tace.
 */
fun dispositiviSenzaBlocco(dispositivi: List<Dispositivo>): List<DispositivoSenzaBlocco> =
    dispositivi
        .filter { it.abbinato && !it.revocato }
        .sortedBy { it.id }
        .mapNotNull { dispositivo ->
            val versione = dispositivo.versioneApp?.trim()?.takeIf { it.isNotEmpty() }
            val confronto = versione?.let { confrontaVersioni(it, VERSIONE_CON_BLOCCO) }
            when {
                confronto != null && confronto >= 0 -> null
                // Più vecchia: si dice quale. Non si legge: come una che non si sa.
                confronto != null -> DispositivoSenzaBlocco(dispositivo, versione)
                else -> DispositivoSenzaBlocco(dispositivo, null)
            }
        }

/**
 * Due versioni "0.12.0" e "0.13.0" a confronto, pezzo per pezzo (un pezzo che
 * manca vale 0: "0.13" = "0.13.0"; quello che segue un "-" o un "+" non conta).
 * null = una delle due non si legge.
 */
fun confrontaVersioni(a: String, b: String): Int? {
    val pezziA = pezziVersione(a) ?: return null
    val pezziB = pezziVersione(b) ?: return null
    for (i in 0 until maxOf(pezziA.size, pezziB.size)) {
        val diff = (pezziA.getOrNull(i) ?: 0).compareTo(pezziB.getOrNull(i) ?: 0)
        if (diff != 0) return diff
    }
    return 0
}

private fun pezziVersione(versione: String): List<Int>? {
    val base = versione.trim().removePrefix("v").substringBefore('-').substringBefore('+').trim()
    if (base.isEmpty()) return null
    val pezzi = base.split('.')
    return pezzi.map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
}

// --- Le notifiche delle faccende ------------------------------------------------------

/** I tipi delle notifiche delle faccende che arrivano al genitore (contratto v3.6). */
object TipiNotificaFaccende {
    const val FACCENDA_FATTA = "faccenda_fatta"
    const val FACCENDE_FINITE = "faccende_finite"
}

/** true = la notifica parla di faccende: toccarla apre le faccende del figlio. */
fun notificaDiFaccende(tipo: String): Boolean =
    tipo == TipiNotificaFaccende.FACCENDA_FATTA || tipo == TipiNotificaFaccende.FACCENDE_FINITE

/**
 * La faccenda di una notifica da aprire: per `faccenda_fatta` quella della foto
 * (`faccenda_id`). `faccende_finite` ne chiude tante: si apre l'elenco (null).
 */
fun faccendaDellaNotifica(notifica: Notifica): Long? {
    if (notifica.tipo != TipiNotificaFaccende.FACCENDA_FATTA) return null
    return (notifica.payload["faccenda_id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
}

/** Quante faccende chiude una `faccende_finite` (`faccenda_ids`); null se non si sa. */
fun quanteFaccendeFinite(notifica: Notifica): Int? =
    (notifica.payload["faccenda_ids"] as? JsonArray)?.size?.takeIf { it > 0 }

// --- (0.17, contratto v3.9) L'ora del blocco di ogni lavoro ---------------------------

/** Da quando blocca un lavoro da fare, come lo si dice: "da subito" o "dalle". */
sealed interface OraBlocco {
    /** Il blocco è partito quando il lavoro è stato dato ("Blocco da subito (14:02)"). */
    data class Subito(val dal: Instant) : OraBlocco

    /** Il blocco parte (o è partito) a un'ora scelta ("Blocco dalle 16:00"). */
    data class Dalle(val dal: Instant) : OraBlocco
}

/** Lo scarto entro cui `blocco_da` = `creata_ts` vuol dire "subito" (il server scrive al secondo). */
val MARGINE_SUBITO: Duration = Duration.ofSeconds(60)

/**
 * L'ora del blocco di un lavoro da fare, SEMPRE (anche a blocco partito): "subito"
 * se `blocco_da` coincide con la creazione, altrimenti "dalle". null per un lavoro
 * non da fare o senza `blocco_da` leggibile.
 */
fun oraBlocco(faccenda: Faccenda): OraBlocco? {
    if (faccenda.stato != StatiFaccenda.DA_FARE) return null
    val dal = istanteServer(faccenda.bloccoDa) ?: return null
    val creata = istanteServer(faccenda.creataTs)
    return if (creata != null && !dal.isAfter(creata.plus(MARGINE_SUBITO))) OraBlocco.Subito(dal) else OraBlocco.Dalle(dal)
}

// --- (0.17) Modificare un lavoro da fare ------------------------------------------------

/** La scelta del blocco in "Modifica": com'era (non si manda), subito, o dalle un'ora. */
sealed interface BloccoModificato {
    data object Invariato : BloccoModificato
    data object Subito : BloccoModificato

    /** [bloccoDa] = la data col fuso per il server (v. [testoBloccoDa]). */
    data class Dalle(val bloccoDa: String) : BloccoModificato
}

/**
 * Che cosa mandare con PATCH /api/faccende/{id}: solo quello che cambia. [titolo]
 * e [nota] ripuliti; [nota] "" = togli la nota.
 */
data class ModificaFaccenda(
    val titolo: String? = null,
    val nota: String? = null,
    val blocco: BloccoModificato = BloccoModificato.Invariato,
) {
    /** true = non cambia niente: non si chiama il server. */
    val vuota: Boolean get() = titolo == null && nota == null && blocco == BloccoModificato.Invariato
}

/**
 * I cambi rispetto al lavoro com'è adesso ([faccenda]): un titolo o una nota uguali
 * (dopo averli ripuliti come fa il server) non partono; la nota cancellata parte
 * come "" (il server la toglie).
 */
fun cambiDellaModifica(faccenda: Faccenda, titolo: String, nota: String, blocco: BloccoModificato): ModificaFaccenda =
    cambiDellaModifica(faccenda.titolo, faccenda.nota.orEmpty(), titolo, nota, blocco)

/**
 * Come sopra, rispetto ai valori con cui la pagina è partita ([titoloPrima],
 * [notaPrima], salvati con la pagina): ritrovata dopo che Android ha chiuso l'app,
 * un cambio fatto intanto da un altro genitore non si rimanda indietro.
 */
fun cambiDellaModifica(titoloPrima: String, notaPrima: String, titolo: String, nota: String, blocco: BloccoModificato): ModificaFaccenda {
    val titoloNuovo = ripulisci(titolo)
    val notaNuova = ripulisci(nota)
    return ModificaFaccenda(
        titolo = titoloNuovo.takeIf { it != ripulisci(titoloPrima) },
        nota = notaNuova.takeIf { it != ripulisci(notaPrima) },
        blocco = blocco,
    )
}

/**
 * Il corpo di PATCH /api/faccende/{id}: i campi assenti restano com'erano;
 * `"nota": null` toglie la nota, `"blocco_da": null` vuol dire subito.
 */
fun corpoModifica(modifica: ModificaFaccenda): JsonObject = buildJsonObject {
    modifica.titolo?.let { put("titolo", it) }
    modifica.nota?.let { if (it.isEmpty()) put("nota", JsonNull) else put("nota", it) }
    when (val blocco = modifica.blocco) {
        BloccoModificato.Invariato -> Unit
        BloccoModificato.Subito -> put("blocco_da", JsonNull)
        is BloccoModificato.Dalle -> put("blocco_da", blocco.bloccoDa)
    }
}

/**
 * Da dove parte la scelta del blocco in "Modifica": l'istante del blocco se deve
 * ancora partire ("Dalle" con la sua ora); null se è già partito ("Subito": il
 * telefono è già bloccato).
 */
fun bloccoIniziale(faccenda: Faccenda, adesso: Instant): Instant? =
    istanteServer(faccenda.bloccoDa)?.takeIf { it.isAfter(adesso) }

// --- (0.17) "Svolto": i pulsanti di un lavoro fatto -------------------------------------

/** Il pulsante principale di un lavoro fatto. */
enum class PulsanteFatto {
    /** "Guarda la foto": la foto c'è e questo telefono non l'ha ancora aperta (o il server non sa confermare). */
    GUARDA_FOTO,

    /** "Segna come svolto": la foto è stata guardata qui, o non c'è più. */
    SEGNA_SVOLTO,

    /** Niente: confermato senza foto, o il server non sa confermare e la foto non c'è. */
    NESSUNO,
}

/**
 * Che cosa offre un lavoro fatto: il pulsante principale, se si può ancora
 * bocciare, e se è confermato.
 */
data class AzioniFatto(val principale: PulsanteFatto, val boccia: Boolean, val confermato: Boolean)

/**
 * [vista] = questo telefono ha aperto QUESTA foto (lavoro e ora della foto);
 * [conConferma] = il server sa confermare (v3.9). Confermato = niente più
 * "Boccia" (il server direbbe `non_bocciabile`); la foto resta da guardare. Una
 * foto già cancellata (30 giorni) non si può guardare: si può segnare svolto lo
 * stesso (il contratto lo permette).
 */
fun azioniFatto(faccenda: Faccenda, adesso: Instant, vista: Boolean, conConferma: Boolean): AzioniFatto {
    if (faccenda.stato != StatiFaccenda.FATTA) return AzioniFatto(PulsanteFatto.NESSUNO, boccia = false, confermato = false)
    val confermato = faccenda.confermataTs != null
    val principale = when {
        confermato || !conConferma -> if (faccenda.foto) PulsanteFatto.GUARDA_FOTO else PulsanteFatto.NESSUNO
        !faccenda.foto || vista -> PulsanteFatto.SEGNA_SVOLTO
        else -> PulsanteFatto.GUARDA_FOTO
    }
    val boccia = !confermato && bocciabile(faccenda, adesso) is Bocciabile.Si
    return AzioniFatto(principale, boccia, confermato)
}

// --- (0.17) Le foto guardate da questo telefono ------------------------------------------

/** Quante foto guardate si ricordano al massimo, e per quanto. */
const val FOTO_VISTE_MASSIMO = 200
val FOTO_VISTE_DURATA: Duration = Duration.ofDays(45)

/** Una foto guardata: QUALE foto (lavoro e ora della foto) e quando la si è aperta. */
data class FotoVista(val chiave: ChiaveFoto, val alle: Instant)

/** Come si scrive nel DataStore: "id|ora della foto|millisecondi". */
fun codificaFotoVista(vista: FotoVista): String = "${vista.chiave.faccendaId}|${vista.chiave.fotoTs}|${vista.alle.toEpochMilli()}"

/** Il contrario di [codificaFotoVista]; null se la riga non si legge. */
fun decodificaFotoVista(riga: String): FotoVista? {
    val pezzi = riga.split('|')
    if (pezzi.size != 3 || pezzi[1].isEmpty()) return null
    val id = pezzi[0].toLongOrNull() ?: return null
    val millis = pezzi[2].toLongOrNull() ?: return null
    return FotoVista(ChiaveFoto(id, pezzi[1]), Instant.ofEpochMilli(millis))
}

/**
 * Le foto guardate da tenere: niente doppioni (vale l'ultima volta), via quelle
 * più vecchie di [FOTO_VISTE_DURATA] (a 30 giorni la foto sparisce comunque), e al
 * massimo [FOTO_VISTE_MASSIMO], le più recenti.
 */
fun potaFotoViste(viste: Collection<FotoVista>, adesso: Instant): List<FotoVista> {
    val dal = adesso.minus(FOTO_VISTE_DURATA)
    return viste
        .groupBy { it.chiave }
        .map { (_, stesse) -> stesse.maxBy { it.alle } }
        .filter { it.alle.isAfter(dal) }
        .sortedByDescending { it.alle }
        .take(FOTO_VISTE_MASSIMO)
}

// --- (0.17) La ricerca nello storico ---------------------------------------------------

/** Quanto si aspetta dopo l'ultima lettera prima di cercare. */
const val ATTESA_RICERCA_MS = 300L

/** Quanti caratteri al massimo si cercano (contratto v3.9: 1-80 dopo aver tolto gli spazi ai bordi). */
const val MASSIMO_RICERCA = 80

/** Il testo da cercare come lo vuole il server: senza spazi ai bordi, al massimo 80 caratteri; null = niente da cercare. */
fun testoDaCercare(testo: String): String? =
    testo.trim().take(MASSIMO_RICERCA).trim().takeIf { it.isNotEmpty() }
