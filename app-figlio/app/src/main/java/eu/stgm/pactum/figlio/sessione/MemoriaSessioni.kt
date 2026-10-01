package eu.stgm.pactum.figlio.sessione

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * (0.11) Una sessione svolta come la ricorda il telefono: quella del server
 * (inizio, fine prevista, fine vera, chiusura) più la fine decisa qui con
 * "Termina la sessione" ([fineLocale]), che vale subito, anche senza rete.
 * Istanti in epoch ms.
 */
@Serializable
data class SvoltaLocale(
    val id: Long,
    @SerialName("sessione_id") val sessioneId: Long? = null,
    val nome: String = "",
    val app: List<String> = emptyList(),
    val nomi: Map<String, String> = emptyMap(),
    val inizio: Long,
    @SerialName("fine_prevista") val finePrevista: Long,
    /** La fine che dice il server (`fine_ts`): null finché per lui è in corso. */
    @SerialName("fine_server") val fineServer: Long? = null,
    /** "Termina la sessione" toccato qui: da quell'istante la sessione è finita. */
    @SerialName("fine_locale") val fineLocale: Long? = null,
    val chiusura: String? = null,
    /**
     * Il ragazzo sa che è partita (l'ha avviata e l'ha visto, o gliel'ha detto
     * una notifica, o l'ha vista in Pactum). Finché no, niente barriera.
     */
    val annunciata: Boolean = true,
    /**
     * Avviata da questo telefono: inizio e fine prevista sono sull'orologio del
     * telefono (lo stesso degli eventi d'uso), non su quello del server.
     */
    @SerialName("ancorata_qui") val ancorataQui: Boolean = false,
) {
    /**
     * Quando è finita (o finirà) davvero: la prima tra la fine prevista, quella
     * del server e quella decisa qui. Una sessione chiusa prima non torna mai
     * aperta perché una lettura vecchia del server dice "in corso".
     */
    val fine: Long
        get() = minOf(finePrevista, fineServer ?: Long.MAX_VALUE, fineLocale ?: Long.MAX_VALUE).coerceAtLeast(inizio)

    /**
     * (0.11) Il server ha appena confermato l'avvio: inizio = adesso sul
     * telefono, fine = adesso + la durata. Così un orologio del server avanti
     * o indietro non sposta la sessione, né la barriera né la misura.
     */
    fun ancorataAlTelefono(adesso: Long, durataMinuti: Int? = null): SvoltaLocale {
        val durata = durataMinuti?.takeIf { it > 0 }?.let { it * MINUTO_MS } ?: (finePrevista - inizio)
        return copy(inizio = adesso, finePrevista = adesso + durata, annunciata = true, ancorataQui = true)
    }

    private companion object {
        const val MINUTO_MS = 60_000L
    }
}

/** Una chiusura anticipata da consegnare al server (`POST …/in_corso/termina`). */
@Serializable
data class TerminazioneInAttesa(
    @SerialName("svolta_id") val svoltaId: Long,
    @SerialName("ts_device") val tsDevice: Long,
)

/**
 * (0.11) "Inizia" senza risposta (la rete è caduta dopo l'invio): non si sa
 * se la sessione è partita. Si ricontrolla appena c'è rete, e intanto non si
 * copre niente.
 */
@Serializable
data class AvvioIncerto(
    @SerialName("sessione_id") val sessioneId: Long,
    val nome: String = "",
    @SerialName("durata_minuti") val durataMinuti: Int = 0,
    /** Quando è partito "Inizia", sull'orologio del telefono. */
    @SerialName("richiesto_il") val richiestoIl: Long,
)

/** (0.11) Com'è finito un avvio incerto, guardando le sessioni del server. */
enum class EsitoAvvioIncerto {
    /** Partita, ed è ancora in corso: la si annuncia e poi vale la barriera. */
    PARTITA,

    /** Partita, ma è già finita. */
    GIA_FINITA,

    /** Non è partita. */
    NON_PARTITA,
}

/**
 * (0.11) La sessione in corso adesso, come la usano la barriera, la notifica
 * e le schermate. Solo quello che serve: un cambio altrove non la fa ripartire.
 */
data class SessioneAttiva(
    val svoltaId: Long,
    val sessioneId: Long?,
    val nome: String,
    val app: Set<String>,
    val nomi: Map<String, String>,
    val inizio: Long,
    val fine: Long,
    val annunciata: Boolean = true,
)

/**
 * (0.11) Quello che il telefono sa delle sessioni svolte (logica pura; il file
 * sta in ArchivioSessioni). Serve a: sapere se adesso c'è una sessione in
 * corso (la barriera), sapere quali periodi non contano (la misura, anche dopo
 * un riavvio o una reinstallazione), non perdere una chiusura anticipata fatta
 * senza rete, e ricordare un "Inizia" rimasto senza risposta.
 */
@Serializable
data class MemoriaSessioni(
    val svolte: List<SvoltaLocale> = emptyList(),
    val terminazioni: List<TerminazioneInAttesa> = emptyList(),
    @SerialName("avvio_incerto") val avvioIncerto: AvvioIncerto? = null,
) {

    /**
     * La sessione in corso a [adesso], null se non ce n'è. Una sessione senza
     * app non è una sessione (la barriera coprirebbe tutto): non vale. Un
     * piccolo margine sull'inizio: l'orologio del server può essere avanti di
     * qualche secondo, e appena avviata la sessione deve già esserci.
     */
    fun inCorso(adesso: Long): SessioneAttiva? =
        svolte.asSequence()
            .filter { it.fineLocale == null && it.app.isNotEmpty() }
            .filter { adesso >= it.inizio - TOLLERANZA_INIZIO_MS && adesso < it.fine }
            .maxByOrNull { it.inizio }
            ?.let {
                SessioneAttiva(
                    svoltaId = it.id,
                    sessioneId = it.sessioneId,
                    nome = it.nome,
                    app = it.app.toSet(),
                    nomi = it.nomi,
                    inizio = it.inizio,
                    fine = it.fine,
                    annunciata = it.annunciata,
                )
            }

    /** Le sessioni svolte che toccano [da, a): i periodi in cui il tempo delle loro app non conta. */
    fun periodi(da: Long, a: Long): List<SvoltaLocale> =
        svolte.filter { it.app.isNotEmpty() && it.inizio < a && it.fine > da && it.fine > it.inizio }

    /**
     * Avviata adesso (201 del server, o ritrovata dopo una risposta persa):
     * entra, o sostituisce la copia con lo stesso id; il ragazzo lo sa.
     * L'avvio incerto è chiarito. Le chiusure in attesa restano: portano
     * `svolta_id`, quindi non possono chiudere la sessione nuova, e la loro
     * sessione deve risultare terminata (non scaduta) anche se arrivano tardi.
     */
    fun conAvvio(svolta: SvoltaLocale, adesso: Long): MemoriaSessioni =
        copy(
            svolte = (svolte.filterNot { it.id == svolta.id } + svolta.copy(annunciata = true)).sortedBy { it.inizio },
            avvioIncerto = null,
        ).potata(adesso)

    /** "Inizia" senza risposta: da ricontrollare. */
    fun conAvvioIncerto(incerto: AvvioIncerto): MemoriaSessioni = copy(avvioIncerto = incerto)

    fun senzaAvvioIncerto(): MemoriaSessioni = copy(avvioIncerto = null)

    /** Il ragazzo ora sa che la sessione [svoltaId] è partita: vale la barriera. */
    fun conAnnuncio(svoltaId: Long): MemoriaSessioni =
        copy(svolte = svolte.map { if (it.id == svoltaId) it.copy(annunciata = true) else it })

    /**
     * "Termina la sessione": finita adesso, qui, subito. Restituisce la
     * memoria nuova e la chiusura da consegnare (null se non c'era niente in corso).
     */
    fun conTermine(adesso: Long): Pair<MemoriaSessioni, TerminazioneInAttesa?> {
        val attiva = inCorso(adesso) ?: return this to null
        val chiusura = TerminazioneInAttesa(attiva.svoltaId, adesso)
        val nuova = copy(
            svolte = svolte.map {
                if (it.id == attiva.svoltaId) it.copy(fineLocale = adesso.coerceAtLeast(it.inizio)) else it
            },
            terminazioni = terminazioni.filterNot { it.svoltaId == attiva.svoltaId } + chiusura,
        )
        return nuova to chiusura
    }

    /**
     * Quello che dice il server (`sessioni_svolte` e `sessione_in_corso` di
     * GET /api/patto), unito a quello che sa il telefono:
     *  - le sessioni del server entrano o aggiornano la copia, ma la fine
     *    decisa qui resta: una lettura partita prima del "Termina" non riapre
     *    niente; e una fine già nota non sparisce per una lettura vecchia;
     *  - una sessione avviata da qui tiene inizio e fine sull'orologio del
     *    telefono: dal server prende solo una chiusura anticipata;
     *  - una sessione in corso che il telefono non conosceva (una risposta
     *    persa, una reinstallazione) entra NON annunciata: prima di coprire
     *    qualcosa, la si dice al ragazzo;
     *  - quelle che il server non nomina restano (una lettura partita prima
     *    dell'avvio, o oltre gli 8 giorni): si potano solo per età;
     *  - una chiusura in attesa se ne va solo quando il server la dice già
     *    `terminata` (consegnata, e la risposta si era persa). Una sessione
     *    "scaduta" per il server la aspetta ancora: consegnata tardi, vale da
     *    quando è stata fatta (contratto v3.5), e il genitore vede che è stata
     *    chiusa prima.
     */
    fun conServer(svolteServer: List<SvoltaLocale>, inCorsoServer: SvoltaLocale?, adesso: Long): MemoriaSessioni {
        val perId = LinkedHashMap<Long, SvoltaLocale>()
        svolte.forEach { perId[it.id] = it }
        val dalServer = svolteServer + listOfNotNull(inCorsoServer)
        for (s in dalServer) {
            val locale = perId[s.id]
            perId[s.id] = when {
                locale == null -> s.copy(annunciata = !(adesso < s.fine && s.fineServer == null))
                locale.ancorataQui -> locale.copy(
                    // Finita da sola: vale la fine sull'orologio del telefono.
                    fineServer = if (s.chiusura == ChiusureSessione.SCADUTA) locale.fineServer else s.fineServer ?: locale.fineServer,
                    chiusura = s.chiusura ?: locale.chiusura,
                )
                else -> s.copy(
                    fineServer = s.fineServer ?: locale.fineServer,
                    fineLocale = locale.fineLocale,
                    chiusura = s.chiusura ?: locale.chiusura,
                    annunciata = locale.annunciata,
                )
            }
        }
        val terminateSulServer = dalServer.filter { it.chiusura == ChiusureSessione.TERMINATA }.map { it.id }.toSet()
        val restano = terminazioni.filterNot { it.svoltaId in terminateSulServer }
        return copy(svolte = perId.values.sortedBy { it.inizio }, terminazioni = restano).potata(adesso)
    }

    /**
     * Com'è finito l'avvio incerto, dopo una lettura fresca del server: è
     * partito se il server ha una sessione svolta di quella sessione iniziata
     * da quando è partito "Inizia" (con un margine per gli orologi). Null se
     * non c'è niente da chiarire.
     */
    fun esitoAvvioIncerto(adesso: Long): EsitoAvvioIncerto? {
        val incerto = avvioIncerto ?: return null
        val partita = svolte
            .filter { it.sessioneId == incerto.sessioneId && it.inizio >= incerto.richiestoIl - MARGINE_AVVIO_MS }
            .maxByOrNull { it.inizio }
            ?: return EsitoAvvioIncerto.NON_PARTITA
        return if (adesso < partita.fine) EsitoAvvioIncerto.PARTITA else EsitoAvvioIncerto.GIA_FINITA
    }

    /**
     * Il server ha risposto al "termina" (200): la chiusura è consegnata. La
     * sessione chiusa che torna ([chiusa], se si legge) porta la fine del server.
     */
    fun conTerminata(svoltaId: Long, chiusa: SvoltaLocale?): MemoriaSessioni = copy(
        svolte = svolte.map { s ->
            if (s.id == svoltaId && chiusa != null && chiusa.id == svoltaId) {
                s.copy(fineServer = chiusa.fineServer ?: s.fineServer, chiusura = chiusa.chiusura ?: s.chiusura)
            } else {
                s
            }
        },
        terminazioni = terminazioni.filterNot { it.svoltaId == svoltaId },
    )

    /** La chiusura di [svoltaId] non va più consegnata (consegnata, inutile o impossibile). */
    fun senzaTerminazione(svoltaId: Long): MemoriaSessioni =
        copy(terminazioni = terminazioni.filterNot { it.svoltaId == svoltaId })

    /**
     * Le chiusure che vale la pena mandare: la sessione c'è ancora e il server
     * non la dice già terminata. Anche dopo la fine prevista: una chiusura
     * fatta prima vale da quando è stata fatta. Decide il server (404 = non
     * serve più), e con `svolta_id` non può chiudere una sessione sbagliata.
     */
    fun daConsegnare(): List<TerminazioneInAttesa> =
        terminazioni.filter { t ->
            val s = svolte.firstOrNull { it.id == t.svoltaId }
            s != null && s.chiusura != ChiusureSessione.TERMINATA
        }

    /**
     * Si dimenticano le sessioni finite da più di [GIORNI_MEMORIA] giorni (e
     * le loro chiusure), e un avvio incerto di più di due giorni fa.
     */
    fun potata(adesso: Long): MemoriaSessioni {
        val soglia = adesso - GIORNI_MEMORIA * GIORNO_MS
        val restano = svolte.filter { it.fine >= soglia }
        val ids = restano.map { it.id }.toSet()
        return MemoriaSessioni(
            svolte = restano,
            terminazioni = terminazioni.filter { it.svoltaId in ids },
            avvioIncerto = avvioIncerto?.takeIf { adesso - it.richiestoIl < 2 * GIORNO_MS },
        )
    }

    companion object {
        /** La striscia è di 8 giorni: se ne tiene uno in più. */
        const val GIORNI_MEMORIA = 9L
        private const val GIORNO_MS = 24L * 60 * 60 * 1000

        /** L'orologio del server può essere un po' avanti: due minuti di margine sull'inizio. */
        const val TOLLERANZA_INIZIO_MS = 2L * 60 * 1000

        /** Avvio incerto: una sessione iniziata fino a 10 minuti prima di "Inizia" (orologi diversi) è quella. */
        const val MARGINE_AVVIO_MS = 10L * 60 * 1000
    }
}

/**
 * (0.11) Quali app stanno in una sessione (logica pura). Una chiave è il nome
 * di un pacchetto, oppure `gruppo:apk`: le app installate fuori da ogni
 * negozio (da un file, con adb).
 */
object AppDellaSessione {

    const val GRUPPO_APK = "gruppo:apk"

    /** adb, e chi installa le app scaricate da un file. */
    private const val SHELL = "com.android.shell"

    /**
     * true = [pacchetto] è nella sessione; false = è fuori; null = non si sa
     * (la sessione ha `gruppo:apk` e di quell'app non si sa da dove viene).
     * Chi decide cosa fare del "non si sa" è chi chiama: la barriera non copre,
     * la misura conta come sempre.
     */
    fun ammette(app: Collection<String>, pacchetto: String, nelGruppoApk: (String) -> Boolean?): Boolean? {
        if (pacchetto in app) return true
        if (GRUPPO_APK !in app) return false
        return nelGruppoApk(pacchetto)
    }

    /**
     * Un'app è nel gruppo `gruppo:apk` se non è di sistema (né di sistema
     * aggiornata) e nessun negozio l'ha installata: chi l'ha installata è
     * l'installatore di pacchetti del sistema (un file APK), adb, o non si sa.
     * Il Play Store, i negozi delle marche e ogni altro negozio: fuori.
     * [diSistema] null o installatore non letto ([installatoreLetto] falso) = non si sa.
     */
    fun nelGruppoApk(diSistema: Boolean?, installatore: String?, installatoreLetto: Boolean): Boolean? = when {
        diSistema == null -> null
        diSistema -> false
        !installatoreLetto -> null
        else -> installatoreDaFile(installatore)
    }

    /** Nessun installatore, adb, o l'installatore di pacchetti del sistema (di qualsiasi marca). */
    fun installatoreDaFile(installatore: String?): Boolean {
        val nome = installatore?.trim()?.lowercase()
        return nome.isNullOrEmpty() || nome == SHELL || nome.endsWith(".packageinstaller")
    }
}
