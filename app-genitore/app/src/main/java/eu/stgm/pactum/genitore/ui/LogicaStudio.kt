package eu.stgm.pactum.genitore.ui

import eu.stgm.pactum.genitore.dati.ChiusureStudio
import eu.stgm.pactum.genitore.dati.ConfigStudio
import eu.stgm.pactum.genitore.dati.ContenutoStudio
import eu.stgm.pactum.genitore.dati.Dispositivo
import eu.stgm.pactum.genitore.dati.EsitiTratto
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.MASSIMO_MOTIVO_STUDIO
import eu.stgm.pactum.genitore.dati.MINIMO_MOTIVO_STUDIO
import eu.stgm.pactum.genitore.dati.StudioPatto
import eu.stgm.pactum.genitore.dati.StudioSvolto
import eu.stgm.pactum.genitore.dati.TipiDispositivo
import eu.stgm.pactum.genitore.dati.TrattoStudio
import java.time.Instant
import java.time.LocalDate

// (0.18, contratto v4.0, parte C) La logica pura della Sessione Studio dalla parte
// del genitore: niente Compose, niente risorse (provata in StudioTest). Qui si
// decide COSA mostrare; le parole le mette TestiStudio.kt.
//
// Due cose da tenere a mente, scritte nel contratto:
// - i "minuti di attività" sono minuti col timer acceso DICHIARATI dal figlio, non
//   attività verificata: i testi lo dicono sempre;
// - lo Studio si chiude dal telefono del figlio (dopo l'orario minimo e il minimo
//   di minuti) o da un genitore con un motivo, mai dal computer; a mezzanotte si
//   chiude da solo come "non chiuso".

// --- Il server conosce lo Studio? ------------------------------------------------------

/**
 * true = la finestra viene da un server dalla v4.0 (porta lo `studio`). Un server
 * più vecchio non conosce né lo Studio né i lavori approvati: allora lo Studio non
 * si mostra e i testi dei lavori restano quelli della v3.9.
 */
fun conStudio(finestra: Finestra?): Boolean = finestra?.studio != null

// --- Lo Studio di oggi, nella Panoramica -----------------------------------------------

/** Che cosa dire dello Studio nella Panoramica. */
sealed interface StudioDiOggi {
    /** In corso adesso. */
    data class InCorso(val studio: StudioSvolto) : StudioDiOggi

    /** Fatto oggi e chiuso (dal figlio o da un genitore). */
    data class Chiuso(val studio: StudioSvolto) : StudioDiOggi

    /** Niente oggi, e quello di ieri si è chiuso da solo a mezzanotte, senza la chiusura del figlio. */
    data class NonChiusoIeri(val studio: StudioSvolto) : StudioDiOggi

    /** Oggi parte più tardi, a [inizio]. */
    data class Parte(val inizio: Instant) : StudioDiOggi

    /** Niente approvato: lo Studio non parte finché un genitore non approva una configurazione. */
    data object NonApprovato : StudioDiOggi

    /**
     * Una configurazione approvata ma nessuna partenza in arrivo: per il server il
     * figlio non ha un telefono con Pactum 0.18 (contratto v4.0: `prossime_partenze`
     * vuoto), oppure oggi non è un giorno di Studio.
     */
    data object Niente : StudioDiOggi
}

/**
 * Lo Studio di oggi (nel fuso del patto, [oggi]) dai dati della finestra: quello
 * in corso; se no l'ultimo di oggi; se no quello di ieri chiuso da solo a
 * mezzanotte; se no la prossima partenza di oggi. null = il server non conosce lo
 * Studio ([studio] null).
 */
fun studioDiOggi(studio: StudioPatto?, svolte: List<StudioSvolto>, oggi: LocalDate, adesso: Instant): StudioDiOggi? {
    if (studio == null) return null
    val inCorso = studio.inCorso?.takeIf { it.fineTs == null }
        ?: svolte.firstOrNull { it.inCorso && it.fineTs == null }
    if (inCorso != null) return StudioDiOggi.InCorso(inCorso)
    val diOggi = svolte.filter { it.giorno == oggi.toString() && it.fineTs != null }
        .maxByOrNull { istanteServer(it.inizioTs) ?: Instant.EPOCH }
    if (diOggi != null) return StudioDiOggi.Chiuso(diOggi)
    val ieri = svolte.filter { it.giorno == oggi.minusDays(1).toString() && it.fineTs != null }
        .maxByOrNull { istanteServer(it.inizioTs) ?: Instant.EPOCH }
    if (ieri != null && ieri.chiusura == ChiusureStudio.NON_CHIUSO) return StudioDiOggi.NonChiusoIeri(ieri)
    val prossima = studio.prossimePartenze
        .filter { it.giorno == oggi.toString() }
        .mapNotNull { istanteServer(it.inizioTs) }
        .filter { it.isAfter(adesso) }
        .minOrNull()
    if (prossima != null) return StudioDiOggi.Parte(prossima)
    if (studio.config?.approvata == null) return StudioDiOggi.NonApprovato
    return StudioDiOggi.Niente
}

/** I minuti DICHIARATI col timer: congelati alla chiusura, o quelli fino a adesso. */
fun minutiDichiarati(studio: StudioSvolto): Int =
    (studio.minutiAllaChiusura ?: studio.minutiAttivita ?: 0).coerceAtLeast(0)

/**
 * Da quando lo si può chiudere, se è ancora da venire: per "si chiude dopo le
 * 16:00". null = nessun vincolo d'orario (Studio a mano), o già passato.
 */
fun chiudeDopo(studio: StudioSvolto, adesso: Instant): Instant? =
    istanteServer(studio.chiudibileDal)?.takeIf { it.isAfter(adesso) }

/** true = lo Studio è aperto (in corso) e un genitore lo può chiudere. */
fun chiudibileDalGenitore(studio: StudioSvolto): Boolean = studio.fineTs == null && studio.chiusura == null

// --- La chiusura del genitore: il motivo -----------------------------------------------

/** Che cosa non va nel motivo della chiusura. */
enum class ProblemaMotivo { VUOTO, CORTO, LUNGO, INVISIBILI }

/**
 * Il motivo della chiusura del genitore: obbligatorio, da 3 a 300 caratteri dopo
 * aver tolto gli spazi ai bordi, con le regole di una nota (a capo ammessi, niente
 * caratteri invisibili). null = va bene: il pulsante si accende solo allora.
 */
fun problemaMotivo(motivo: String): ProblemaMotivo? {
    val pulito = ripulisci(motivo)
    val lunghezza = pulito.codePointCount(0, pulito.length)
    return when {
        pulito.isEmpty() -> ProblemaMotivo.VUOTO
        lunghezza < MINIMO_MOTIVO_STUDIO -> ProblemaMotivo.CORTO
        lunghezza > MASSIMO_MOTIVO_STUDIO -> ProblemaMotivo.LUNGO
        problemaNota(pulito) == ProblemaTesto.CARATTERI_INVISIBILI -> ProblemaMotivo.INVISIBILI
        else -> null
    }
}

// --- La configurazione da approvare ----------------------------------------------------

/**
 * Una configurazione che aspetta un genitore: il contenuto proposto, la [versione]
 * vista (va con la risposta) e se è un cambio di una già approvata ([cambio]).
 */
data class RichiestaStudio(
    val proposta: ContenutoStudio,
    val approvata: ContenutoStudio?,
    val versione: Int,
    val motivazionePrecedente: String?,
) {
    val cambio: Boolean get() = approvata != null
}

/**
 * La configurazione in attesa, se c'è e se non è già stata decisa da qui
 * ([decisaVersione] = la versione a cui si è appena risposto, finché la finestra
 * è di prima). Senza `versione` non si risponde: il server non saprebbe su che
 * cosa si decide.
 */
fun richiestaStudio(config: ConfigStudio?, decisaVersione: Int? = null): RichiestaStudio? {
    val proposta = config?.inAttesa ?: return null
    val versione = config.versione ?: return null
    if (decisaVersione != null && decisaVersione >= versione) return null
    return RichiestaStudio(proposta, config.approvata, versione, config.motivazione)
}

/**
 * (correzione 0.18) La firma di un programma che resta in lista ma cambia: [prima]
 * e [dopo] = chi lo firma (null = non si controlla la firma). Togliere la firma di
 * `exe:winword.exe` vuol dire che qualunque programma chiamato winword.exe passa.
 */
data class CambioFirma(val chiave: String, val prima: String?, val dopo: String?)

/** (correzione 0.18) L'etichetta di una voce che resta in lista ma cambia nome. */
data class CambioNome(val chiave: String, val prima: String, val dopo: String)

/** Che cosa è cambiato tra la configurazione approvata e quella proposta. */
data class CambiStudio(
    val giorni: Boolean,
    val inizio: Boolean,
    val chiusuraMinima: Boolean,
    val minutiMinimi: Boolean,
    val appAggiunte: List<String>,
    val appTolte: List<String>,
    val programmiAggiunti: List<String>,
    val programmiTolti: List<String>,
    /** I programmi che restano ma con un'altra firma (o senza): solo per un cambio. */
    val firmeCambiate: List<CambioFirma> = emptyList(),
    /** Le app del telefono che restano ma con un'altra etichetta: solo per un cambio. */
    val nomiTelefonoCambiati: List<CambioNome> = emptyList(),
    /** I programmi che restano ma con un'altra etichetta: solo per un cambio. */
    val nomiComputerCambiati: List<CambioNome> = emptyList(),
) {
    /** Giorni, orari o minimo: valgono dal giorno dopo (anche alla prima approvazione). */
    val orari: Boolean get() = giorni || inizio || chiusuraMinima || minutiMinimi

    /**
     * Le liste: valgono dalla partenza successiva. Come per il server, cambiano anche
     * se cambiano solo le firme o le etichette.
     */
    val liste: Boolean
        get() = appAggiunte.isNotEmpty() || appTolte.isNotEmpty() || programmiAggiunti.isNotEmpty() ||
            programmiTolti.isNotEmpty() || firmeCambiate.isNotEmpty() || nomiTelefonoCambiati.isNotEmpty() ||
            nomiComputerCambiati.isNotEmpty()
}

/**
 * I cambi della [richiesta]. Alla prima proposta (niente di approvato) tutto è
 * "nuovo": gli orari valgono da domani e le liste sono tutte aggiunte.
 */
fun cambiStudio(richiesta: RichiestaStudio): CambiStudio {
    val prima = richiesta.approvata
    val dopo = richiesta.proposta
    val appPrima = prima?.telefono?.app.orEmpty()
    val appDopo = dopo.telefono?.app.orEmpty()
    val progPrima = prima?.computer?.programmi.orEmpty()
    val progDopo = dopo.computer?.programmi.orEmpty()
    val restanoProgrammi = progDopo.filter { it in progPrima }.distinct()
    val restanoApp = appDopo.filter { it in appPrima }.distinct()
    fun pulito(testo: String?): String? = testo?.trim()?.takeIf { it.isNotEmpty() }
    // Un'etichetta cambia solo se c'era prima e c'è adesso, ed è diversa (una che manca
    // nella proposta si mostra con quella approvata: non è un cambio).
    fun nomiCambiati(chiavi: List<String>, nomiPrima: Map<String, String>, nomiDopo: Map<String, String>): List<CambioNome> =
        chiavi.mapNotNull { chiave ->
            val a = pulito(nomiPrima[chiave]) ?: return@mapNotNull null
            val b = pulito(nomiDopo[chiave]) ?: return@mapNotNull null
            CambioNome(chiave, a, b).takeIf { a != b }
        }
    return CambiStudio(
        giorni = prima == null || giorniOrdinati(prima.giorni) != giorniOrdinati(dopo.giorni),
        inizio = prima == null || prima.inizio != dopo.inizio,
        chiusuraMinima = prima == null || prima.chiusuraMinima != dopo.chiusuraMinima,
        minutiMinimi = prima == null || prima.minutiMinimi != dopo.minutiMinimi,
        appAggiunte = appDopo.filter { it !in appPrima }.distinct(),
        appTolte = appPrima.filter { it !in appDopo }.distinct(),
        programmiAggiunti = progDopo.filter { it !in progPrima }.distinct(),
        programmiTolti = progPrima.filter { it !in progDopo }.distinct(),
        firmeCambiate = if (prima == null) {
            emptyList()
        } else {
            restanoProgrammi.filter { !it.trim().startsWith("sito:") }.mapNotNull { chiave ->
                val a = pulito(prima.computer?.firme?.get(chiave))
                val b = pulito(dopo.computer?.firme?.get(chiave))
                CambioFirma(chiave, a, b).takeIf { a != b }
            }
        },
        nomiTelefonoCambiati = if (prima == null) {
            emptyList()
        } else {
            nomiCambiati(restanoApp.filter { !eGruppoApk(it) }, prima.telefono?.nomi.orEmpty(), dopo.telefono?.nomi.orEmpty())
        },
        nomiComputerCambiati = if (prima == null) {
            emptyList()
        } else {
            nomiCambiati(restanoProgrammi.filter { !it.trim().startsWith("sito:") }, prima.computer?.nomi.orEmpty(), dopo.computer?.nomi.orEmpty())
        },
    )
}

/**
 * (correzione 0.18) true = l'ultima proposta arriva da un computer ma aggiunge (o
 * rinomina) app del TELEFONO. Di solito la lista del telefono la propone il
 * telefono: un'etichetta mandata dal computer non è stata vista nell'uso e può
 * nascondere un'altra app. (L'app non sa chi ha proposto ogni pezzo: sa solo chi
 * ha proposto per ultimo, quindi lo dice come un invito a controllare.)
 */
fun listaTelefonoDalComputer(richiesta: RichiestaStudio, cambi: CambiStudio = cambiStudio(richiesta)): Boolean =
    richiesta.proposta.da?.tipo == TipiDispositivo.COMPUTER &&
        (cambi.appAggiunte.any { !eGruppoApk(it) } || cambi.nomiTelefonoCambiati.isNotEmpty())

/** I giorni del contratto, nell'ordine della settimana. */
val GIORNI_STUDIO = listOf("lun", "mar", "mer", "gio", "ven", "sab", "dom")

/** I giorni senza doppioni, nell'ordine della settimana (quelli che non si conoscono si lasciano cadere). */
fun giorniOrdinati(giorni: List<String>): List<String> {
    val puliti = giorni.map { it.trim().lowercase() }.toSet()
    return GIORNI_STUDIO.filter { it in puliti }
}

// --- Le liste, a parole ----------------------------------------------------------------

/**
 * Una voce della lista del telefono: il nome da mostrare, o il gruppo delle app
 * fuori dal Play Store. [pacchetto] = il nome tecnico, quando il nome mostrato è
 * un'etichetta (diverso dal pacchetto); null quando il nome È il pacchetto.
 */
sealed interface VoceTelefono {
    data class App(val nome: String, val pacchetto: String? = null) : VoceTelefono
    data object FuoriStore : VoceTelefono
}

/** Il nome di una voce del telefono: l'etichetta (letta dal server nell'uso) o il pacchetto. */
fun voceTelefono(chiave: String, nomi: Map<String, String>): VoceTelefono {
    if (eGruppoApk(chiave)) return VoceTelefono.FuoriStore
    val etichetta = nomeVero(chiave, nomi[chiave])
    return if (etichetta != null) VoceTelefono.App(etichetta, chiave.trim()) else VoceTelefono.App(chiave)
}

/**
 * Una voce della lista del computer: un programma (con chi lo firma) o un sito.
 * [file] = il nome del file del programma ("winword.exe"), quello che il computer
 * riconosce davvero.
 */
sealed interface VoceComputer {
    data class Programma(val nome: String, val firma: String?, val file: String = nome) : VoceComputer
    data class Sito(val dominio: String) : VoceComputer
}

/**
 * Il nome di una voce del computer: per `exe:` l'etichetta vista nell'uso o il
 * nome del file, più chi lo firma ("firmato da Microsoft Corporation"); per
 * `sito:` il dominio.
 */
fun voceComputer(chiave: String, nomi: Map<String, String>, firme: Map<String, String>): VoceComputer {
    val pulita = chiave.trim()
    return when {
        pulita.startsWith("sito:") -> VoceComputer.Sito(pulita.removePrefix("sito:"))
        else -> {
            val file = pulita.removePrefix("exe:")
            VoceComputer.Programma(
                nome = nomi[chiave]?.trim()?.takeIf { it.isNotEmpty() } ?: file,
                firma = firme[chiave]?.trim()?.takeIf { it.isNotEmpty() },
                file = file,
            )
        }
    }
}

// --- I tratti dell'attività ------------------------------------------------------------

/** I minuti di un tratto da mostrare: quelli del server, o i secondi contati (per difetto). */
fun minutiTratto(tratto: TrattoStudio): Int =
    tratto.minuti ?: ((tratto.secondiContati ?: tratto.secondi ?: 0L) / 60L).toInt().coerceAtLeast(0)

/** I tratti dal primo all'ultimo (per ora di fine, o d'inizio se è ancora in corso). */
fun trattiInOrdine(studio: StudioSvolto): List<TrattoStudio> =
    studio.tratti.sortedWith(compareBy<TrattoStudio> { it.fine ?: it.inizio ?: Long.MAX_VALUE }.thenBy { it.id })

/** true = il tratto è ancora in corso (nessun esito finale). */
fun trattoInCorso(tratto: TrattoStudio): Boolean = tratto.esito == EsitiTratto.IN_CORSO || (tratto.esito == null && tratto.fine == null)

// --- I dispositivi troppo vecchi per lo Studio -------------------------------------------

/** La prima versione delle app (e del programma del computer) che conosce lo Studio. */
const val VERSIONE_CON_STUDIO = "0.18.0"

/**
 * I dispositivi collegati del figlio con un'app più vecchia della 0.18 (o una
 * versione che non si sa): lì lo Studio non c'è e il blocco dei lavori non aspetta.
 * Stesse regole di [dispositiviSenzaBlocco].
 */
fun dispositiviSenzaStudio(dispositivi: List<Dispositivo>): List<DispositivoSenzaBlocco> =
    dispositivi
        .filter { it.abbinato && !it.revocato }
        .sortedBy { it.id }
        .mapNotNull { dispositivo ->
            val versione = dispositivo.versioneApp?.trim()?.takeIf { it.isNotEmpty() }
            val confronto = versione?.let { confrontaVersioni(it, VERSIONE_CON_STUDIO) }
            when {
                confronto != null && confronto >= 0 -> null
                confronto != null -> DispositivoSenzaBlocco(dispositivo, versione)
                else -> DispositivoSenzaBlocco(dispositivo, null)
            }
        }

/**
 * true = il figlio ha almeno un telefono collegato con Pactum dalla 0.18: senza, il
 * server non fa partire lo Studio (nessuno potrebbe chiuderlo).
 */
fun haTelefonoConStudio(dispositivi: List<Dispositivo>): Boolean =
    dispositivi.any { d ->
        d.tipo == TipiDispositivo.TELEFONO && d.abbinato && !d.revocato &&
            d.versioneApp?.let { confrontaVersioni(it, VERSIONE_CON_STUDIO) }?.let { it >= 0 } == true
    }

// --- Le notifiche dello Studio ------------------------------------------------------------

/** I tipi delle notifiche dello Studio che arrivano al genitore (contratto v4.0). */
object TipiNotificaStudio {
    const val DA_APPROVARE = "studio_da_approvare"
    const val NON_PARTITO = "studio_non_partito"
    const val INIZIATO = "studio_iniziato"
    const val CHIUSO = "studio_chiuso"
    const val NON_CHIUSO = "studio_non_chiuso"
}

/**
 * true = la notifica parla dello Studio fatto (iniziato, chiuso, non chiuso, non
 * partito): toccarla apre la pagina dello Studio del figlio. `studio_da_approvare`
 * porta invece a "Da decidere".
 */
fun notificaDelloStudio(tipo: String): Boolean =
    tipo == TipiNotificaStudio.NON_PARTITO || tipo == TipiNotificaStudio.INIZIATO ||
        tipo == TipiNotificaStudio.CHIUSO || tipo == TipiNotificaStudio.NON_CHIUSO

// --- Quante cose aspettano il genitore (Da decidere) -----------------------------------------

/**
 * (0.18) Le cose della v4.0 che aspettano il genitore, per il figlio della
 * finestra: le foto da approvare e la configurazione dello Studio (se non è già
 * stata decisa da qui).
 */
fun quanteDaDecidereV40(finestra: Finestra, studioDecisaVersione: Int? = null): Int =
    (finestra.faccende?.let(::fotoDaApprovare) ?: finestra.faccendeDaApprovare ?: 0) +
        (if (richiestaStudio(finestra.studio?.config, studioDecisaVersione) != null) 1 else 0)
