package eu.stgm.pactum.figlio.sessione

import eu.stgm.pactum.figlio.misura.Sessioni

// (0.11) La barriera di una Sessione, logica pura: quando coprire l'app in
// primo piano, quale app è in primo piano, e quando aprire la schermata senza
// mai farlo a raffica. Due regole: un'app fuori dalla lista si copre sempre,
// anche se l'ha aperta un'app della sessione (le sole eccezioni sono quelle
// scritte qui e in SempreUsabili); e se qualcosa non si sa o va storto, NON si
// copre. Una barriera che non compare quando dovrebbe è un minuto contato; una
// barriera che compare quando non dovrebbe è un telefono che non si usa più.

/** Perché la barriera copre, o non copre: lo dicono i test. */
enum class MotivoBarriera {
    NESSUNA_SESSIONE,

    /** (0.13) Il blocco delle faccende copre già quest'app: vale la sua barriera, più stretta. */
    BLOCCO_FACCENDE,
    SESSIONE_FINITA,

    /** Una sessione che il ragazzo non sa ancora che è partita: niente barriera finché non glielo si dice. */
    NON_ANNUNCIATA,
    SCHERMO_SPENTO,
    BLOCCATO,
    SENZA_MOSTRA_SOPRA,
    SENZA_ACCESSO_USO,
    IN_CHIAMATA,
    PRIMO_PIANO_IGNOTO,
    SEMPRE_USABILE,
    NELLA_SESSIONE,
    INCERTO,

    /** Una pagina web o un foglio del Play Store aperti da un'app: fanno parte di quell'app. */
    PARTE_DI_UN_APP,
    NON_CONTA,
    FUORI_SESSIONE,
}

data class DecisioneBarriera(val copri: Boolean, val motivo: MotivoBarriera)

/** Tutto quello che serve per decidere, letto dal telefono un attimo prima. */
data class SituazioneBarriera(
    val sessione: SessioneAttiva?,
    val adesso: Long,
    /** Il pacchetto in primo piano; null = non si sa. */
    val primoPiano: String?,
    val schermoAcceso: Boolean,
    val sbloccato: Boolean,
    val mostraSopra: Boolean,
    val accessoUso: Boolean,
    val inChiamata: Boolean,
    /** Pactum, la Home, la tastiera, il sistema, il Telefono e le chiamate, le Impostazioni, la fotocamera, la scelta di file e foto. */
    val sempreUsabili: Set<String>,
    /** L'app conta nell'uso (ha un'icona, non è la Home né Pactum); null = non si sa. */
    val contaNellUso: (String) -> Boolean?,
    /** L'app è installata fuori da ogni negozio (`gruppo:apk`); null = non si sa. */
    val nelGruppoApk: (String) -> Boolean?,
    /** La schermata (classe dell'activity) in primo piano, se si sa. */
    val classe: String? = null,
    /**
     * (0.13) Il blocco delle faccende copre già quest'app adesso
     * (GuardiaFaccende): la barriera della sessione si fa da parte solo per
     * queste. Per le altre vale la sessione: il più stretto dei due.
     */
    val copertaDalBlocco: Boolean = false,
)

object GuardiaSessione {

    /**
     * Si copre solo se TUTTO è certo: una sessione in corso che il ragazzo
     * conosce, schermo acceso e telefono sbloccato, i due permessi, nessuna
     * chiamata, un'app in primo piano che si conosce, che non è mai da
     * coprire, che non è nella sessione, che non è una pagina web o un foglio
     * del Play Store dentro un'altra app, e che conta nell'uso. Chi l'ha
     * aperta non conta: anche aperta da un'app della sessione, si copre.
     * Qualunque errore = non si copre.
     */
    fun decidi(s: SituazioneBarriera): DecisioneBarriera = try {
        decidiDentro(s)
    } catch (e: Exception) {
        lascia(MotivoBarriera.INCERTO)
    }

    private fun decidiDentro(s: SituazioneBarriera): DecisioneBarriera {
        // (0.13) Col blocco delle faccende la sessione continua sul server, ma
        // sul telefono vale il più stretto dei due (contratto v3.6): dove il
        // blocco copre già, questa si fa da parte, così le due barriere non si
        // aprono a vicenda; dove il blocco lascia libero (Wallet, gli SMS…) la
        // sessione vale come sempre.
        if (s.copertaDalBlocco) return lascia(MotivoBarriera.BLOCCO_FACCENDE)
        val sessione = s.sessione ?: return lascia(MotivoBarriera.NESSUNA_SESSIONE)
        if (s.adesso >= sessione.fine) return lascia(MotivoBarriera.SESSIONE_FINITA)
        if (s.adesso < sessione.inizio - MemoriaSessioni.TOLLERANZA_INIZIO_MS) {
            return lascia(MotivoBarriera.NESSUNA_SESSIONE)
        }
        if (sessione.app.isEmpty()) return lascia(MotivoBarriera.NESSUNA_SESSIONE)
        if (!sessione.annunciata) return lascia(MotivoBarriera.NON_ANNUNCIATA)
        if (!s.schermoAcceso) return lascia(MotivoBarriera.SCHERMO_SPENTO)
        if (!s.sbloccato) return lascia(MotivoBarriera.BLOCCATO)
        if (!s.mostraSopra) return lascia(MotivoBarriera.SENZA_MOSTRA_SOPRA)
        if (!s.accessoUso) return lascia(MotivoBarriera.SENZA_ACCESSO_USO)
        // Durante una chiamata (anche WhatsApp, anche mentre squilla) mai; e la
        // schermata di una chiamata via internet si lascia sempre stare.
        if (s.inChiamata || ClassiAttivita.chiamata(s.classe)) return lascia(MotivoBarriera.IN_CHIAMATA)
        val app = s.primoPiano?.trim()?.takeIf { it.isNotEmpty() } ?: return lascia(MotivoBarriera.PRIMO_PIANO_IGNOTO)
        if (app in s.sempreUsabili) return lascia(MotivoBarriera.SEMPRE_USABILE)
        when (AppDellaSessione.ammette(sessione.app, app, s.nelGruppoApk)) {
            true -> return lascia(MotivoBarriera.NELLA_SESSIONE)
            null -> return lascia(MotivoBarriera.INCERTO)
            false -> Unit
        }
        if (ClassiAttivita.aiutoDiUnApp(app, s.classe)) return lascia(MotivoBarriera.PARTE_DI_UN_APP)
        // I pezzi di sistema senza icona (servizi Google, selettore dei file,
        // finestre dei permessi…) li apre un'altra app, e non contano nell'uso:
        // la barriera copre solo quello che conta.
        if (s.contaNellUso(app) != true) return lascia(MotivoBarriera.NON_CONTA)
        return DecisioneBarriera(copri = true, motivo = MotivoBarriera.FUORI_SESSIONE)
    }

    private fun lascia(motivo: MotivoBarriera) = DecisioneBarriera(copri = false, motivo = motivo)
}

/**
 * (0.11) Le schermate che si riconoscono dal nome (logica pura): quelle di una
 * chiamata via internet, e quelle che un'app apre per sé dentro un'altra app
 * (una pagina web in una "scheda personalizzata", i fogli del Play Store per
 * una recensione, un aggiornamento o un acquisto). Il loro tempo conta come
 * sempre, per pacchetto: qui si decide solo cosa non si copre.
 */
object ClassiAttivita {

    /** Nel nome breve della classe: le schermate delle chiamate via internet. */
    private val CHIAMATA_NOME = listOf(
        "voip", "incall", "callactivity", "callingactivity", "callscreen", "videocall",
        "voicecall", "webrtccall", "ongoingcall", "groupcall", "confactivity",
    )

    /** All'inizio del nome intero: le schermate delle chiamate di app note. */
    private val CHIAMATA_PREFISSI = listOf(
        "com.whatsapp.voipcalling.", "com.whatsapp.calling.", "com.facebook.rtc.", "com.instagram.rtc.",
        "com.google.android.apps.tachyon.call", "org.telegram.ui.voip", "org.telegram.messenger.voip",
    )

    private const val PLAY_STORE = "com.android.vending"
    private val PLAY_DENTRO_UN_APP = listOf("inappreview", "appupdate", "billing", "acquire", "purchase")

    /**
     * La schermata di una chiamata via internet (WhatsApp, Telegram, Meet…).
     * Si guarda il nome breve della classe, non il pacchetto: "com.viber.voip"
     * è tutta Viber, non solo le sue chiamate.
     */
    fun chiamata(classe: String?): Boolean {
        val intera = classe?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val breve = intera.substringAfterLast('.').lowercase()
        return CHIAMATA_NOME.any { it in breve } || CHIAMATA_PREFISSI.any { intera.startsWith(it) }
    }

    /** Una scheda personalizzata del browser, o un foglio del Play Store dentro un'app. */
    fun aiutoDiUnApp(pacchetto: String?, classe: String?): Boolean {
        val intera = classe?.lowercase() ?: return false
        if ("customtab" in intera) return true
        return pacchetto == PLAY_STORE && PLAY_DENTRO_UN_APP.any { it in intera }
    }
}

/**
 * Quale app è in primo piano, dagli eventi d'uso (logica pura): l'ultima
 * ACTIVITY_RESUMED, con la sua schermata. Gli eventi si rileggono con un po'
 * di sovrapposizione: uno più vecchio di quello che si sa già non cambia
 * niente, lo stesso riletto nemmeno. Schermo spento, blocco, spegnimento o
 * accensione: non si sa più (null) finché un'app non torna davanti.
 */
class TracciaPrimoPiano {

    var attuale: String? = null
        private set

    var classe: String? = null
        private set

    /**
     * (0.13) L'app che era davanti appena prima di quella di adesso (chi ha
     * aperto una pagina web, per la barriera delle faccende). Null dopo uno
     * schermo spento o un blocco: chi riprende un'app dalle Recenti non ha un
     * "prima" che l'abbia aperta.
     */
    var precedente: String? = null
        private set

    private var istante = Long.MIN_VALUE

    fun evento(tipo: Int, pacchetto: String?, quando: Long, classeEvento: String? = null) {
        if (quando < istante) return
        when (tipo) {
            Sessioni.RIPRESA -> if (!pacchetto.isNullOrBlank()) {
                if (pacchetto != attuale) precedente = attuale
                attuale = pacchetto
                classe = classeEvento
                istante = quando
            }
            Sessioni.SCHERMO_SPENTO, Sessioni.BLOCCO, Sessioni.SPEGNIMENTO, Sessioni.ACCENSIONE -> {
                attuale = null
                classe = null
                precedente = null
                istante = quando
            }
        }
    }

    fun azzera() {
        attuale = null
        classe = null
        precedente = null
        istante = Long.MIN_VALUE
    }
}

/**
 * Quando aprire la barriera (logica pura, orologio monotono in ms).
 *  - Si apre quando l'app davanti CAMBIA e va coperta, o quando la stessa app
 *    da "non si copre" passa a "si copre" (finisce una chiamata, torna il
 *    permesso): non a ogni giro.
 *  - Solo se la stessa app da coprire è davanti per [conferme] giri di fila:
 *    un lampo (una notifica che apre e richiude, una chiamata che arriva) non
 *    fa comparire niente.
 *  - Mai due aperture più vicine di [intervalloMinimo]: se tocca prima, si
 *    apre al primo giro buono (se quell'app è ancora davanti).
 *  - Davanti c'è la barriera (o Pactum): non si apre niente.
 *  - Se dopo un'apertura l'app resta davanti (Android non l'ha lasciata
 *    partire), si riprova poche volte, sempre più piano ([riprove]), poi
 *    basta finché l'app davanti non cambia.
 *  - Interruttore di sicurezza: più di [lanciMassimi] aperture in
 *    [finestraLanci] = qualcosa gira in tondo (un'app che torna davanti da
 *    sola, una Home che non si riconosce). Si smette per [pausa]: meglio un
 *    minuto senza barriera che un telefono che non si usa più. Finita la
 *    pausa, se l'app da coprire è ancora lì, si copre di nuovo.
 */
class RitmoBarriera(
    private val intervalloMinimo: Long = INTERVALLO_MINIMO_MS,
    private val riprove: List<Long> = RIPROVE_MS,
    private val lanciMassimi: Int = LANCI_MASSIMI,
    private val finestraLanci: Long = FINESTRA_LANCI_MS,
    private val pausa: Long = PAUSA_MS,
    private val conferme: Int = CONFERME,
) {
    private var ultimo: String? = null
    private var ultimoCopri = false
    private var visteDiFila = 0
    private var ultimoLancio: Long? = null
    private var lanciQui = 0
    private var dovuto = false
    private val lanci = ArrayDeque<Long>()
    private var pausaFino: Long? = null

    /** In pausa per l'interruttore di sicurezza. */
    fun inPausa(adesso: Long): Boolean = pausaFino?.let { adesso < it } ?: false

    /**
     * (0.13) L'ultima apertura è arrivata sullo schermo: non conta per
     * l'interruttore di sicurezza. Lo usa solo la barriera delle faccende:
     * l'interruttore serve quando la barriera NON riesce a comparire (qualcosa
     * gira in tondo), e una barriera comparsa e chiusa apposta (app, barriera,
     * Home, di nuovo) non deve poterlo far scattare.
     */
    fun comparsa() {
        lanci.removeLastOrNull()
    }

    /**
     * Un giro: [primoPiano] = l'app davanti (null = non si sa); [copri] = la
     * guardia dice di coprirla adesso. True = aprire la barriera adesso.
     */
    fun passo(primoPiano: String?, copri: Boolean, adesso: Long): Boolean {
        if (primoPiano == null) {
            ultimo = null
            ultimoCopri = false
            visteDiFila = 0
            dovuto = false
            return false
        }
        if (primoPiano != ultimo) {
            ultimo = primoPiano
            lanciQui = 0
            visteDiFila = 0
            dovuto = copri
        } else if (copri && !ultimoCopri) {
            // La stessa app, che adesso va coperta (una chiamata finita, un permesso tornato).
            lanciQui = 0
            visteDiFila = 0
            dovuto = true
        }
        ultimoCopri = copri
        if (!copri) {
            visteDiFila = 0
            dovuto = false
            return false
        }
        visteDiFila += 1
        // La stessa app da coprire, davanti ancora al giro dopo: non è un lampo.
        if (visteDiFila < conferme) return false
        pausaFino?.let { fine -> if (adesso < fine) return false else pausaFino = null }
        val riprova = lanciQui in 1..riprove.size &&
            ultimoLancio?.let { adesso - it >= riprove[lanciQui - 1] } == true
        if (!dovuto && !riprova) return false
        val precedente = ultimoLancio
        if (precedente != null && adesso - precedente < intervalloMinimo) {
            dovuto = true
            return false
        }
        while (lanci.isNotEmpty() && adesso - lanci.first() >= finestraLanci) lanci.removeFirst()
        if (lanci.size >= lanciMassimi) {
            pausaFino = adesso + pausa
            lanci.clear()
            lanciQui = 0
            // Finita la pausa, se l'app da coprire è ancora lì, si copre di nuovo.
            dovuto = true
            return false
        }
        lanci.addLast(adesso)
        ultimoLancio = adesso
        lanciQui += 1
        dovuto = false
        return true
    }

    /**
     * Schermo spento, telefono bloccato, o "Esci" appena toccato: quando
     * torna, l'app davanti si guarda da capo (rivederla è un cambio). Il conto
     * delle aperture e la pausa restano.
     */
    fun azzera() {
        ultimo = null
        ultimoCopri = false
        visteDiFila = 0
        lanciQui = 0
        dovuto = false
    }

    companion object {
        const val INTERVALLO_MINIMO_MS = 1_500L
        val RIPROVE_MS = listOf(4_000L, 10_000L, 30_000L)
        const val LANCI_MASSIMI = 8
        const val FINESTRA_LANCI_MS = 60_000L
        const val PAUSA_MS = 60_000L

        /** La stessa app da coprire, davanti per due giri di fila. */
        const val CONFERME = 2
    }
}
