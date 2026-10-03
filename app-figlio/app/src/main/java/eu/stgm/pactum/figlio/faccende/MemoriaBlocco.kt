package eu.stgm.pactum.figlio.faccende

import kotlinx.serialization.Serializable

/**
 * (0.13) Quale domanda al server è partita prima: si conta sull'orologio che
 * non si sposta, dentro la stessa accensione. Fra accensioni diverse vince
 * quella dopo. Se l'accensione non si sa, non si può dire: la risposta vale.
 */
@Serializable
data class Ordine(val avvio: Int? = null, val monotono: Long = 0L) {

    /** Questa domanda è partita prima di [altra] (sicuramente). */
    fun primaDi(altra: Ordine): Boolean {
        if (avvio == null || altra.avvio == null) return false
        if (avvio != altra.avvio) return avvio < altra.avvio
        return monotono < altra.monotono
    }

    companion object {
        fun di(istante: Istante) = Ordine(istante.avvio, istante.monotono)
    }
}

/**
 * (0.13) Dove si trovava l'ora del server all'ultima risposta: l'ora del
 * server ([server], dall'intestazione Date della risposta) in quell'istante
 * del telefono ([avvio], [monotono]). Da qui l'ora del server di adesso si
 * conta sull'orologio che non si sposta.
 */
@Serializable
data class Ancora(val avvio: Int? = null, val monotono: Long = 0L, val server: Long = 0L)

/**
 * (0.13) Quello che il telefono sa del blocco delle faccende (logica pura; il
 * file sta in ArchivioBlocco). Tre regole del contratto v3.6, "Il blocco sul
 * telefono":
 *
 *  1. **Il server è la verità**: il blocco è quello dell'ultima risposta di
 *     `GET /api/faccende/blocco` (o del `blocco` del patto).
 *  2. **Senza rete il blocco resta com'era**: niente si sblocca da solo, né il
 *     tempo, né una risposta che non si legge, né un server che non conosce le
 *     faccende, né una foto mandata. Solo un `401` (questo telefono non è più
 *     collegato) toglie il blocco, come sul computer.
 *  3. **Un blocco programmato parte all'ora di `prossimo` anche senza rete**:
 *     se dopo quell'ora (del server) il server non ha ancora parlato, il
 *     telefono si blocca da solo ([partitoDaSolo]).
 *
 * L'orologio: l'ordine delle risposte ([ordine]) e l'ora del server
 * ([oraServer]) si contano sull'orologio del telefono che non si sposta
 * (Istante.monotono), partendo dall'ora del server dell'ultima risposta
 * ([ancora]). L'orologio a muro serve solo dopo un riavvio del telefono (con
 * lo scarto misurato, [scarto]) e per dire l'età dei dati ([sentitoIl]).
 *
 * In più, contro i piccoli ritardi: un telefono già bloccato a cui il server
 * dice "non ancora, parte fra poco" ([TOLLERANZA_MS]) resta bloccato.
 */
@Serializable
data class MemoriaBlocco(
    /** L'ultima risposta del server dice che il blocco c'è (o lo trattiene, v. sopra). */
    val attivo: Boolean = false,
    val dal: Long? = null,
    /** Il prossimo blocco programmato (ora del server), se adesso non c'è. */
    val prossimo: Long? = null,
    val daFare: List<FaccendaDaFare> = emptyList(),
    /** La domanda dell'ultima risposta usata. */
    val ordine: Ordine? = null,
    /** L'ora del server all'ultima risposta usata. */
    val ancora: Ancora? = null,
    /** Ora del server meno orologio a muro, all'ultima risposta (null dopo un cambio d'ora a mano). */
    val scarto: Long? = null,
    /** Quando è arrivata l'ultima risposta, sull'orologio a muro: solo per dire l'età dei dati. */
    val sentitoIl: Long? = null,
    /** Da quando dura questo blocco, senza interruzioni: la chiave dell'avviso. */
    val episodio: Long? = null,
    /** L'episodio di blocco già annunciato con la notifica "Prima le faccende". */
    val annunciato: Long? = null,
    /** Il server ha già risposto sulle faccende (v3.6). */
    val conosciuto: Boolean = false,
    /** L'ultima domanda è finita in un 404/405: il server non conosce le faccende. */
    val serverVecchio: Boolean = false,
    /** L'ultima risposta è un 401: questo telefono non è più collegato, e il blocco è tolto. */
    val scollegato: Boolean = false,
    /** Tutte le faccende (da fare e chiuse negli ultimi 30 giorni), per la pagina. */
    val elenco: List<FaccendaLocale> = emptyList(),
    val ordineElenco: Ordine? = null,
    val elencoIl: Long? = null,
    /** L'ultima uscita forzata del processo già guardata (ApplicationExitInfo, ms). */
    val uscitaVista: Long? = null,
) {

    /**
     * L'ora del server adesso. Nella stessa accensione dell'ultima risposta:
     * l'ora del server di allora più il tempo passato sull'orologio che non si
     * sposta. Dopo un riavvio: l'orologio a muro più lo scarto misurato.
     */
    fun oraServer(ora: Istante): Long {
        val a = ancora ?: return ora.muro + (scarto ?: 0L)
        if (a.avvio != null && a.avvio == ora.avvio && ora.monotono >= a.monotono) {
            return a.server + (ora.monotono - a.monotono)
        }
        return ora.muro + (scarto ?: 0L)
    }

    /** Partito da solo all'ora di [prossimo]: dopo quell'ora il server non ha ancora parlato. */
    fun partitoDaSolo(ora: Istante): Boolean {
        val p = prossimo ?: return false
        return !attivo && !sentitoDopo(p) && oraServer(ora) >= p
    }

    /** Il telefono è bloccato adesso. */
    fun attivoAdesso(ora: Istante): Boolean = attivo || partitoDaSolo(ora)

    /** Da quando è bloccato adesso (ora del server), null se non lo è. */
    fun dalAdesso(ora: Istante): Long? = when {
        attivo -> dal ?: episodio
        partitoDaSolo(ora) -> prossimo
        else -> null
    }

    /** La chiave del blocco in corso adesso: la stessa finché dura senza interruzioni. */
    fun episodioAdesso(ora: Istante): Long? = when {
        attivo -> episodio ?: dal ?: ancora?.server ?: 0L
        partitoDaSolo(ora) -> prossimo
        else -> null
    }

    /** L'ora (del server) in cui partirà da solo il prossimo blocco; null se non ce n'è o se è già partito. */
    fun prossimaPartenza(ora: Istante): Long? =
        if (attivo) null else prossimo?.takeIf { !sentitoDopo(it) && oraServer(ora) < it }

    /** Fra quanti ms partirà da solo il prossimo blocco (per la sveglia e il giro della barriera). */
    fun attesaPartenza(ora: Istante): Long? = prossimaPartenza(ora)?.let { it - oraServer(ora) }

    /** L'ultima risposta del server è arrivata dopo l'ora [t] (del server): ha già detto la sua. */
    private fun sentitoDopo(t: Long): Boolean = ancora?.let { it.server >= t } ?: false

    /**
     * Il telefono era bloccato all'istante [muro] passato (per la "forza
     * arresto" guardata dopo): bloccato per il server, o partito da solo a
     * quell'ora, contata con lo scarto misurato.
     */
    fun attivoAlMuro(muro: Long): Boolean {
        if (attivo) return true
        val p = prossimo ?: return false
        return !sentitoDopo(p) && muro + (scarto ?: 0L) >= p
    }

    /** C'è qualcosa da mostrare nella pagina Faccende (e quindi la sua scheda). */
    val haFaccende: Boolean
        get() = elenco.isNotEmpty() || daFare.isNotEmpty() || attivo || prossimo != null

    /** Una risposta a una domanda partita prima di quella già usata è vecchia. */
    private fun vecchia(richiesta: Istante, ultima: Ordine?): Boolean = ultima?.let { Ordine.di(richiesta).primaDi(it) } ?: false

    /**
     * La risposta del server: la domanda è partita a [richiesta] ed è
     * arrivata a [arrivo]; [dataServer] = l'ora del server della risposta
     * (intestazione Date), null se non c'era. Una risposta a una domanda
     * partita prima di quella già usata è vecchia: non cambia niente (una
     * lettura lenta del patto non rimette il blocco tolto da una più fresca).
     */
    fun conServer(r: BloccoDalServer, richiesta: Istante, arrivo: Istante, dataServer: Long?): MemoriaBlocco {
        if (vecchia(richiesta, ordine)) return this
        val bloccatoPrima = attivoAdesso(arrivo)
        val episodioPrima = episodioAdesso(arrivo)
        val serverAdesso = dataServer ?: oraServer(arrivo)
        val prossimoServer = r.prossimo
        // Già bloccato, e per il server parte fra pochissimo: è un ritardo.
        val trattieni = !r.attivo && bloccatoPrima && prossimoServer != null &&
            prossimoServer <= serverAdesso + TOLLERANZA_MS
        val attivoDopo = r.attivo || trattieni
        val dalDopo = when {
            r.attivo -> r.dal ?: dalAdesso(arrivo) ?: serverAdesso
            trattieni -> dalAdesso(arrivo) ?: prossimoServer
            else -> null
        }
        return copy(
            attivo = attivoDopo,
            dal = dalDopo,
            prossimo = prossimoServer,
            daFare = r.daFare,
            ordine = Ordine.di(richiesta),
            ancora = Ancora(arrivo.avvio, arrivo.monotono, serverAdesso),
            scarto = serverAdesso - arrivo.muro,
            sentitoIl = arrivo.muro,
            episodio = if (attivoDopo) episodioPrima ?: dalDopo else null,
            conosciuto = true,
            serverVecchio = false,
            scollegato = false,
        )
    }

    /**
     * Un 404/405: il server non conosce le faccende. Si dice nella pagina, ma
     * il blocco resta com'era: un server che non risponde sulle faccende non
     * è un server che dice "niente da fare" (potrebbe essere una pagina di un
     * Wi-Fi pubblico, che risponde a tutto con un 404).
     */
    fun conServerVecchio(): MemoriaBlocco = copy(serverVecchio = true)

    /**
     * Un 401: questo telefono non è più collegato al patto (revocato, o
     * collegato con un codice che non vale più). Il blocco si toglie e la
     * pagina lo dice, come sul computer (contratto v3.6). Torna appena un
     * collegamento nuovo riceve una risposta.
     */
    fun conScollegato(richiesta: Istante, arrivo: Istante): MemoriaBlocco {
        if (vecchia(richiesta, ordine)) return this
        return copy(
            attivo = false,
            dal = null,
            prossimo = null,
            daFare = emptyList(),
            ordine = Ordine.di(richiesta),
            sentitoIl = arrivo.muro,
            episodio = null,
            scollegato = true,
        )
    }

    /**
     * L'orologio a muro spostato a mano: l'ordine delle risposte riparte (la
     * prossima vale comunque) e lo scarto misurato non vale più. L'ora del
     * server nella stessa accensione non cambia: si conta sull'orologio che
     * non si sposta. Il blocco resta com'era.
     */
    fun conCambioOra(): MemoriaBlocco = copy(ordine = null, ordineElenco = null, scarto = null)

    /** L'elenco intero delle faccende, per la pagina. Un elenco più vecchio di quello che c'è non entra. */
    fun conElenco(faccende: List<FaccendaLocale>, richiesta: Istante, arrivo: Istante): MemoriaBlocco {
        if (vecchia(richiesta, ordineElenco)) return this
        return copy(
            elenco = faccende,
            ordineElenco = Ordine.di(richiesta),
            elencoIl = arrivo.muro,
            conosciuto = true,
            serverVecchio = false,
        )
    }

    /** Il blocco è più fresco dell'elenco (la pagina prende le faccende da fare da lì). */
    val bloccoPiuFresco: Boolean
        get() {
            val b = ordine ?: return false
            val e = ordineElenco ?: return true
            return !b.primaDi(e)
        }

    /**
     * Una faccenda appena tornata dal server (la risposta alla foto): prende il
     * posto della sua copia nell'elenco della pagina. Il blocco non cambia:
     * lo dice solo la risposta sul blocco.
     */
    fun conFaccenda(faccenda: FaccendaLocale): MemoriaBlocco {
        val gia = elenco.any { it.id == faccenda.id }
        return copy(elenco = if (gia) elenco.map { if (it.id == faccenda.id) faccenda else it } else listOf(faccenda) + elenco)
    }

    /** L'episodio di blocco da annunciare adesso, null se non c'è blocco o è già stato annunciato. */
    fun daAnnunciare(ora: Istante): Long? = episodioAdesso(ora)?.takeIf { it != annunciato }

    fun conAnnuncio(episodio: Long): MemoriaBlocco = copy(annunciato = episodio)

    companion object {
        /** Un piccolo ritardo (la risposta in viaggio, l'ora del server al secondo) non deve sbloccare e ribloccare. */
        const val TOLLERANZA_MS = 2L * 60 * 1000

        /** I permessi che tengono in piedi la barriera (dettagli `permesso` della manomissione). */
        const val PERMESSO_MOSTRA_SOPRA = "mostra_sopra"
        const val PERMESSO_ACCESSO_USO = "accesso_uso"
    }
}

/**
 * (0.13) Cosa è cambiato fra le faccende da fare di prima e quelle di adesso:
 * faccende nuove, bocciature nuove, faccende sparite (fatte o annullate). Se
 * qualcosa è cambiato, le notifiche del server si leggono subito, senza
 * aspettare il giro del quarto d'ora.
 */
object NovitaFaccende {
    fun cambiate(prima: List<FaccendaDaFare>, dopo: List<FaccendaDaFare>): Boolean {
        val vecchie = prima.associateBy { it.id }
        if (dopo.any { nuova -> vecchie[nuova.id]?.let { nuova.bocciature > it.bocciature } ?: true }) return true
        val nuoveId = dopo.mapTo(HashSet()) { it.id }
        return prima.any { it.id !in nuoveId }
    }
}
