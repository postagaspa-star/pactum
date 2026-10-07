package eu.stgm.pactum.figlio.studio

import eu.stgm.pactum.figlio.faccende.Istante
import eu.stgm.pactum.figlio.faccende.Ordine
import kotlinx.serialization.Serializable
import java.time.ZoneId

/** (0.18) Quale Studio: col suo `id` del server, il giorno della partenza automatica, o la chiave dell'avvio a mano. */
@Serializable
data class RifStudio(val id: Long? = null, val giorno: String? = null, val chiave: String? = null)

/**
 * (0.18) Uno Studio avviato a mano su QUESTO telefono e non ancora
 * consegnato al server (`POST /api/studio/avvia` in coda). [inizio] = `I`,
 * sull'ora del server; le liste sono quelle approvate.
 */
@Serializable
data class AvvioManuale(
    val chiave: String,
    val inizio: Long,
    val giorno: String,
    val app: List<String> = emptyList(),
    val nomi: Map<String, String> = emptyMap(),
    val minutiMinimi: Int = 60,
)

/**
 * (0.18) Una chiusura dello Studio fatta qui ("Chiudi lo Studio"): vale
 * subito sul telefono, e si consegna al server (anche dopo, senza rete).
 * [fine] = `T`, sull'ora del server agganciata. Chiude ogni Studio iniziato
 * entro `T`. Se il server la rifiuta, se ne va e lo Studio torna.
 */
@Serializable
data class ChiusuraLocale(
    val chiave: String,
    val rif: RifStudio,
    val inizioStudio: Long,
    val fine: Long,
    val dichiarazione: String,
    val consegnata: Boolean = false,
)

/**
 * (0.18) Il server ha rifiutato la chiusura: lo Studio torna, col motivo
 * («Per il server sono le 15:20» / «Mancano 15 minuti») e il testo come bozza.
 */
@Serializable
data class RifiutoChiusura(
    val motivo: String,
    val bozza: String,
    /** L'ora (del server) della chiusura rifiutata. */
    val il: Long,
    val chiudibileDal: Long? = null,
    val minuti: Int? = null,
    val minimi: Int? = null,
)

/** (0.18) Un periodo di Studio sul telefono, per la misura: il tempo nelle app della sua lista non conta. */
@Serializable
data class PeriodoStudio(val inizio: Long, val fine: Long? = null, val app: List<String> = emptyList())

/** (0.18) Lo Studio in corso adesso, come lo usano la barriera, la notifica, il timer e le schermate. */
data class StudioAttivo(
    val rif: RifStudio,
    val origine: String,
    val giorno: String,
    /** L'inizio, sull'ora del server. */
    val inizio: Long,
    val contaDal: Long,
    /** null = niente vincolo d'orario (a mano, nessuna partenza). */
    val chiudibileDal: Long?,
    val minutiMinimi: Int,
    val app: List<String>,
    val nomi: Map<String, String>,
    /** La mezzanotte che lo chiude (ora del server). */
    val mezzanotte: Long,
    /** Lo dice il server (con i suoi minuti). */
    val dalServer: Boolean,
    val minutiServer: Int? = null,
    val chiudibileServer: Boolean? = null,
    val trattiServer: List<TrattoDelServer> = emptyList(),
)

/**
 * (0.18, contratto v4.0, parte C) Quello che il telefono sa della Sessione
 * Studio (logica pura; il file sta in ArchivioStudio). Le regole del
 * contratto, «Il telefono (0.18)»:
 *
 *  - **In corso** quando lo dice il server (`in_corso`), quando è passata
 *    una partenza dopo l'ultima risposta del server senza una chiusura nota
 *    dopo di lei, o con uno Studio a mano. **Finisce** quando il server lo
 *    dà chiuso, con una chiusura fatta qui, a mezzanotte (fuso del patto),
 *    o con un 401. Un patto senza `studio` (server vecchio) lo spegne.
 *  - Partenze e chiusure si decidono sull'ora del server agganciata
 *    all'orologio che non si sposta (la stessa del blocco: chi chiama passa
 *    `oraServer`); senza rete dopo un riavvio vale l'orologio del telefono.
 *  - Il timer: un tratto alla volta, durata con l'orologio che non si
 *    sposta; un riavvio chiude il tratto in corso all'ultimo punto salvato.
 */
@Serializable
data class MemoriaStudio(
    // --- Quello che ha detto il server -----------------------------------------
    /** L'ultima risposta parlava dello Studio (server dalla v4.0). */
    val conosciuto: Boolean = false,
    /** `/api/studio` → 404/405: serve aggiornare il server. */
    val serverVecchio: Boolean = false,
    /** 401: questo telefono non è più collegato. */
    val scollegato: Boolean = false,
    val config: ConfigStudio? = null,
    val inCorsoServer: StudioSvolto? = null,
    val prossime: List<PartenzaStudio> = emptyList(),
    /** Gli Studi delle ultime 48 ore (chiusi o no), dal più recente. */
    val recenti: List<StudioSvolto> = emptyList(),
    /** Il fuso del patto (`fuso` di GET /api/patto). */
    val fuso: String? = null,
    /** La domanda dell'ultima risposta usata (una risposta più vecchia non cambia niente). */
    val ordine: Ordine? = null,
    /** L'ora del server dell'ultima risposta usata. */
    val sentito: Long? = null,
    // --- Quello che fa il telefono -------------------------------------------------
    val avvioManuale: AvvioManuale? = null,
    /** L'avvio a mano rifiutato dal server (il motivo, da dire una volta). */
    val avvioRifiutato: String? = null,
    val chiusure: List<ChiusuraLocale> = emptyList(),
    val rifiuto: RifiutoChiusura? = null,
    /** Una chiusura che il server non ha trovato (404): si dice una volta. */
    val chiusuraPersa: Boolean = false,
    val tratti: List<TrattoLocale> = emptyList(),
    val periodi: List<PeriodoStudio> = emptyList(),
    /** Il giorno della partenza già avvisata 5 minuti prima. */
    val avvisato: String? = null,
    /** L'inizio dello Studio di cui si è già fatta la partenza (sessione chiusa, pagina aperta). */
    val partitoIl: Long? = null,
) {

    fun zona(): ZoneId = eu.stgm.pactum.figlio.dati.zonaPatto(fuso)

    /** Una risposta a una domanda partita prima di quella già usata è vecchia. */
    private fun vecchia(richiesta: Istante): Boolean = ordine?.let { Ordine.di(richiesta).primaDi(it) } ?: false

    /**
     * La risposta del server sullo Studio (`GET /api/studio` o `studio` del
     * patto): la domanda partita a [richiesta], l'ora del server della
     * risposta [oraServerRisposta]. `recenti` si sostituisce solo se c'è.
     */
    fun conServer(stato: StatoStudioServer, richiesta: Istante, oraServerRisposta: Long): MemoriaStudio {
        if (vecchia(richiesta)) return this
        return copy(
            conosciuto = true,
            serverVecchio = false,
            scollegato = false,
            config = stato.config ?: config,
            inCorsoServer = stato.inCorso,
            prossime = stato.prossime,
            recenti = stato.recenti ?: recenti,
            ordine = Ordine.di(richiesta),
            sentito = oraServerRisposta,
        )
    }

    /**
     * Un patto senza `studio` (server più vecchio della v4.0): lo Studio si
     * spegne. Le cose da consegnare restano (un server aggiornato dopo le prende).
     */
    fun senzaStudio(richiesta: Istante): MemoriaStudio {
        if (vecchia(richiesta)) return this
        return copy(conosciuto = false, inCorsoServer = null, prossime = emptyList(), ordine = Ordine.di(richiesta))
    }

    /**
     * `/api/studio` → 404/405: forse il server non conosce lo Studio. Si
     * segna e basta (per i testi e per non avviare uno Studio a mano): lo
     * Studio in corso e le partenze restano com'erano. Un 404 può essere la
     * pagina di una rete Wi-Fi pubblica, come per il blocco dei lavori
     * (MemoriaBlocco.conServerVecchio); lo Studio lo spegne solo un patto
     * valido senza `studio` ([senzaStudio]).
     */
    fun conServerVecchio(): MemoriaStudio = copy(serverVecchio = true)

    /** 401: questo telefono non è più collegato. Lo Studio finisce. */
    fun conScollegato(ora: Istante, oraServer: Long, agganciata: Boolean): MemoriaStudio = copy(
        scollegato = true,
        conosciuto = false,
        inCorsoServer = null,
        prossime = emptyList(),
        avvioManuale = null,
        tratti = tratti.map { if (it.inCorso) it.chiuso(ora, oraServer, agganciata) else it },
    )

    /** Il fuso del patto, dal patto. */
    fun conFuso(nuovo: String?): MemoriaStudio = if (nuovo == null || nuovo == fuso) this else copy(fuso = nuovo)

    /** L'orologio a muro spostato a mano: l'ordine delle risposte riparte. */
    fun conCambioOra(): MemoriaStudio = copy(ordine = null)

    // --- Le partenze ---------------------------------------------------------------

    /**
     * Tutte le partenze note: quelle del server e, oltre i 14 giorni senza
     * rete, quelle calcolate dalla configurazione (mai se l'elenco del server
     * è vuoto: vuol dire «nessuna partenza»).
     */
    fun partenze(oraServer: Long): List<PartenzaStudio> {
        val zona = zona()
        val fino = RegoleStudio.giorno(oraServer, zona).plusDays(1)
        val oltre = RegoleStudio.partenzeOltre(prossime, config?.approvata, fino, zona)
        return (prossime + oltre).sortedBy { it.inizio }
    }

    /** La prossima partenza ancora da venire (per la sveglia, l'avviso e "sta per partire"). */
    fun prossimaPartenza(oraServer: Long): PartenzaStudio? = partenze(oraServer).firstOrNull { it.inizio > oraServer }

    // --- Lo Studio in corso -----------------------------------------------------------

    /** Chiuso da una chiusura fatta qui (non rifiutata) dopo il suo inizio. */
    private fun chiusoQui(inizio: Long): Boolean = chiusure.any { it.fine >= inizio }

    private fun mezzanotte(giorno: String, inizio: Long): Long {
        val zona = zona()
        return RegoleStudio.mezzanotteDopo(giorno, zona) ?: RegoleStudio.mezzanotteDopo(RegoleStudio.giorno(inizio, zona), zona)
    }

    /** Le partenze passate, dopo [da], fino a [oraServer]. */
    private fun partenzeTra(da: Long, oraServer: Long): List<PartenzaStudio> =
        partenze(oraServer).filter { it.inizio in da..oraServer }

    /**
     * Lo Studio in corso a [oraServer] (l'ora del server adesso), null se non
     * c'è. Le condizioni di chiusura vengono dall'ULTIMA partenza dentro lo
     * Studio (anche una assorbita qui, senza rete, dopo l'ultima risposta).
     */
    fun attivo(oraServer: Long): StudioAttivo? = attivo(oraServer, oraServer)

    /** Lo Studio in corso adesso, con l'ora per la mezzanotte di [o] (OraServer.perFine). */
    fun attivo(o: OraServer): StudioAttivo? = attivo(o.server, o.perFine)

    /**
     * [perFine]: l'ora che decide se uno Studio già cominciato è finito per la
     * mezzanotte (di solito [oraServer]; con l'orologio del telefono spostato a
     * mano e senza aggancio, un'ora di sicuro già passata). Uno Studio già
     * cominciato per quell'ora non torna «non ancora cominciato» se
     * l'orologio del telefono viene portato indietro.
     */
    fun attivo(oraServer: Long, perFine: Long): StudioAttivo? {
        if (!conosciuto || scollegato) return null
        val perInizio = maxOf(oraServer, perFine)
        val candidati = listOfNotNull(dalServer(oraServer), daAvvioManuale(oraServer), daPartenza(oraServer))
            .filter { perInizio >= it.inizio - TOLLERANZA_INIZIO_MS && perFine < it.mezzanotte && !chiusoQui(it.inizio) }
        return candidati.minByOrNull { it.inizio }
    }

    private fun appApprovate(): List<String> = config?.approvata?.app.orEmpty()
    private fun nomiApprovati(): Map<String, String> = config?.approvata?.nomi.orEmpty()

    private fun dalServer(oraServer: Long): StudioAttivo? {
        val s = inCorsoServer ?: return null
        val giorno = s.giorno.ifBlank { RegoleStudio.giorno(s.inizio, zona()).toString() }
        // Le partenze dentro lo Studio: quelle del server e quelle passate qui dopo l'ultima risposta.
        val dopo = sentito?.let { partenzeTra(maxOf(it + 1, s.inizio), oraServer) }.orEmpty()
        val ultima = (s.partenze + dopo).filter { it.inizio <= oraServer }.maxByOrNull { it.inizio }
        return StudioAttivo(
            rif = RifStudio(id = s.id, giorno = giorno, chiave = s.chiave),
            origine = s.origine,
            giorno = giorno,
            inizio = s.inizio,
            contaDal = ultima?.inizio ?: s.contaDal ?: s.inizio,
            chiudibileDal = if (ultima != null) ultima.chiudibileDal else s.chiudibileDal,
            minutiMinimi = ultima?.minutiMinimi ?: s.minutiMinimi,
            app = s.app ?: appApprovate(),
            nomi = if (s.app != null) s.nomi else nomiApprovati(),
            mezzanotte = mezzanotte(giorno, s.inizio),
            dalServer = true,
            minutiServer = s.minutiAttivita,
            chiudibileServer = s.chiudibile,
            trattiServer = s.tratti,
        )
    }

    private fun daAvvioManuale(oraServer: Long): StudioAttivo? {
        val a = avvioManuale ?: return null
        val ultima = partenzeTra(a.inizio, oraServer).maxByOrNull { it.inizio }
        return StudioAttivo(
            rif = RifStudio(chiave = a.chiave, giorno = a.giorno),
            origine = OriginiStudio.MANUALE,
            giorno = a.giorno,
            inizio = a.inizio,
            contaDal = ultima?.inizio ?: a.inizio,
            chiudibileDal = ultima?.chiudibileDal,
            minutiMinimi = ultima?.minutiMinimi ?: a.minutiMinimi,
            app = a.app,
            nomi = a.nomi,
            mezzanotte = mezzanotte(a.giorno, a.inizio),
            dalServer = false,
        )
    }

    /** L'ultima partenza passata DOPO l'ultima risposta del server: lo Studio automatico di quel giorno. */
    private fun daPartenza(oraServer: Long): StudioAttivo? {
        val p = partenze(oraServer)
            .filter { it.inizio <= oraServer && (sentito == null || it.inizio > sentito) }
            .maxByOrNull { it.inizio } ?: return null
        return StudioAttivo(
            rif = RifStudio(giorno = p.giorno),
            origine = OriginiStudio.AUTOMATICA,
            giorno = p.giorno,
            inizio = p.inizio,
            contaDal = p.inizio,
            chiudibileDal = p.chiudibileDal,
            minutiMinimi = p.minutiMinimi,
            app = appApprovate(),
            nomi = nomiApprovati(),
            mezzanotte = mezzanotte(p.giorno, p.inizio),
            dalServer = false,
        )
    }

    /** Era in Studio all'istante [t] (ora del server): per una «forza arresto» guardata dopo. */
    fun inStudioAl(t: Long): Boolean = periodi.any { t >= it.inizio && t < (it.fine ?: Long.MAX_VALUE) }

    // --- I minuti e le condizioni di chiusura -------------------------------------------

    /** I tratti di questo telefono nello Studio [studio]. */
    fun trattiDi(studio: StudioAttivo): List<TrattoLocale> = tratti.filter { (it.studio ?: it.inizio) >= studio.inizio && it.inizio < studio.mezzanotte }

    /**
     * I minuti di attività che contano, stimati qui: l'unione dei tratti di
     * questo telefono tagliata a `[conta_dal, adesso]`; e, se il server ha
     * parlato, i suoi `minuti_attivita` più i tratti che lui non contava
     * ancora (in corso, o non ancora arrivati). Quando il server ha parlato
     * vale il SUO conto (contratto v4.0: «Con due telefoni, "Chiudi lo Studio"
     * non si decide dai soli tratti locali: segue i minuti_attivita del
     * server»; senza rete, l'ultimo valore del server più i tratti locali
     * arrivati dopo). Un tratto che il server ha ricevuto e non conta (ore
     * fuori dalle tutele) non torna a contare qui.
     */
    fun minutiStimati(studio: StudioAttivo, ora: Istante, oraServer: Long): Int {
        val fino = minOf(oraServer, studio.mezzanotte)
        val miei = trattiDi(studio)
        val tutti = miei.map { it.intervallo(ora) }
        val locali = ContoMinuti.durata(tutti, studio.contaDal, fino)
        if (!studio.dalServer || studio.minutiServer == null) return (locali / 60_000).toInt()
        val contati = miei.filter { t -> t.consegnato && sentito != null && (t.consegnatoIl ?: Long.MAX_VALUE) <= sentito }
        val giaContati = ContoMinuti.durata(contati.map { it.intervallo(ora) }, studio.contaDal, fino)
        val conServer = studio.minutiServer * 60_000L + (locali - giaContati).coerceAtLeast(0)
        return (conServer / 60_000).toInt()
    }

    /**
     * «Chiudi lo Studio» compare solo quando le condizioni ci sono: dopo
     * `chiudibile_dal` (sull'ora del server) e con i minuti del minimo. Con la
     * rete segue anche `chiudibile` del server.
     */
    fun chiudibile(studio: StudioAttivo, ora: Istante, oraServer: Long): Boolean {
        val orario = studio.chiudibileDal?.let { oraServer >= it } ?: true
        if (!orario) return false
        if (studio.chiudibileServer == true && serverFresco(oraServer)) return true
        return minutiStimati(studio, ora, oraServer) >= studio.minutiMinimi
    }

    /** L'ultima risposta è di meno di due minuti fa. */
    fun serverFresco(oraServer: Long): Boolean = sentito?.let { oraServer - it < FRESCO_MS } ?: false

    // --- Il timer ------------------------------------------------------------------------

    /** Il tratto che gira adesso, se c'è. */
    val trattoInCorso: TrattoLocale? get() = tratti.lastOrNull { it.inCorso }

    /**
     * «Comincia un'attività»: un tratto nuovo, solo dentro uno Studio. Ne
     * gira uno alla volta: quello di prima finisce qui (`finito`).
     */
    fun conTrattoIniziato(
        id: String,
        tipo: String,
        parola: String?,
        faccendaId: Long?,
        ora: Istante,
        oraServer: Long,
        agganciata: Boolean,
    ): MemoriaStudio {
        val studio = attivo(oraServer) ?: return this
        if (tipo !in TipiTratto.TUTTI) return this
        val p = parola?.let { RegoleStudio.parola(it) }
        if (tipo == TipiTratto.ALTRO && p == null) return this
        val chiusi = tratti.map { if (it.inCorso) it.chiuso(ora, oraServer, agganciata) else it }
        val nuovo = TrattoLocale(
            id = id,
            tipo = tipo,
            parola = p,
            faccendaId = faccendaId?.takeIf { tipo == TipiTratto.LAVORI_DI_CASA && it > 0 },
            avvio = ora.avvio,
            monoInizio = ora.monotono,
            monoSalvato = ora.monotono,
            inizio = oraServer,
            oraAgganciata = agganciata,
            studio = studio.inizio,
        )
        return copy(tratti = chiusi + nuovo)
    }

    /** «Ferma»: il tratto in corso finisce adesso. Uno di meno di un secondo, mai mandato, non si tiene. */
    fun conTrattoFermato(ora: Istante, oraServer: Long, agganciata: Boolean): MemoriaStudio =
        copy(tratti = tratti.mapNotNull { if (it.inCorso) chiudiOTogli(it.chiuso(ora, oraServer, agganciata)) else it })

    /**
     * Il punto salvato del tratto in corso, adesso: al massimo ogni
     * [PUNTO_OGNI_MS]. Prima di allora la memoria resta la stessa (stesso
     * oggetto): niente scrittura del file e niente sveglia. Senza questo
     * limite il giro dello Studio, che si sveglia a ogni cambio della memoria,
     * si risvegliava da solo a ogni punto salvato e girava a vuoto.
     */
    fun conPuntoSalvato(ora: Istante): MemoriaStudio {
        val t = trattoInCorso ?: return this
        if (t.stessaAccensione(ora) && ora.monotono - t.monoSalvato < PUNTO_OGNI_MS) return this
        val s = t.salvato(ora)
        return if (s == t) this else copy(tratti = tratti.map { if (it.id == t.id) s else it })
    }

    private fun chiudiOTogli(t: TrattoLocale): TrattoLocale? =
        if ((t.secondi ?: 0L) < 1 && !t.inCorsoMandato) null else t.copy(secondi = (t.secondi ?: 0L).coerceAtLeast(1))

    // --- La chiusura del figlio ----------------------------------------------------------

    /**
     * «Chiudi lo Studio» a `T` = [oraServer]: il tratto in corso finisce a T
     * (`finito`), la chiusura entra in coda e vale subito qui. Null se non
     * c'è uno Studio, la dichiarazione non va o le condizioni mancano.
     */
    fun conChiusura(chiave: String, dichiarazione: String, ora: Istante, oraServer: Long, agganciata: Boolean): MemoriaStudio? {
        val studio = attivo(oraServer) ?: return null
        val testo = RegoleStudio.dichiarazione(dichiarazione) ?: return null
        if (!chiudibile(studio, ora, oraServer)) return null
        val fermati = conTrattoFermato(ora, oraServer, agganciata)
        val chiusura = ChiusuraLocale(chiave = chiave, rif = studio.rif, inizioStudio = studio.inizio, fine = oraServer, dichiarazione = testo)
        return fermati.copy(chiusure = fermati.chiusure + chiusura, rifiuto = null, chiusuraPersa = false)
    }

    /** Le chiusure ancora da consegnare, dalla più vecchia. */
    val chiusureDaConsegnare: List<ChiusuraLocale> get() = chiusure.filter { !it.consegnata }.sortedBy { it.fine }

    /** Il server ha preso la chiusura (200, o 409 `gia_chiuso`): lo Studio [chiuso], se c'è, va fra i recenti. */
    fun conChiusuraConsegnata(chiave: String, chiuso: StudioSvolto?): MemoriaStudio {
        val dopo = chiusure.map { if (it.chiave == chiave) it.copy(consegnata = true) else it }
        val recentiDopo = chiuso?.let { s -> listOf(s) + recenti.filterNot { it.id == s.id } } ?: recenti
        val inCorsoDopo = inCorsoServer?.takeUnless { s -> chiuso != null && s.id == chiuso.id && chiuso.fine != null }
        return copy(chiusure = dopo, recenti = recentiDopo, inCorsoServer = inCorsoDopo)
    }

    /**
     * Il server ha rifiutato la chiusura (`troppo_presto`, `attivita_insufficiente`,
     * o un corpo che non va): via dalla coda, lo Studio torna col motivo, e il
     * testo resta come bozza. [studio] = lo Studio com'è adesso per il server.
     */
    fun conChiusuraRifiutata(chiave: String, motivo: String, studio: StudioSvolto?, chiudibileDal: Long?, minuti: Int?, minimi: Int?): MemoriaStudio {
        val c = chiusure.firstOrNull { it.chiave == chiave } ?: return this
        return copy(
            chiusure = chiusure.filterNot { it.chiave == chiave },
            rifiuto = RifiutoChiusura(motivo, c.dichiarazione, c.fine, chiudibileDal, minuti, minimi),
            inCorsoServer = studio?.takeIf { it.fine == null } ?: inCorsoServer,
        )
    }

    /** 404: il server non trova quello Studio (oltre le 48 ore): via, e lo si dice. */
    fun conChiusuraPersa(chiave: String): MemoriaStudio =
        copy(chiusure = chiusure.filterNot { it.chiave == chiave }, chiusuraPersa = true)

    fun senzaAvvisi(): MemoriaStudio = copy(chiusuraPersa = false, avvioRifiutato = null)

    fun senzaRifiuto(): MemoriaStudio = copy(rifiuto = null)

    // --- L'avvio a mano --------------------------------------------------------------------

    /** Perché un avvio a mano non può partire (null = può). Lo controlla il telefono, anche senza rete. */
    enum class NoAvvio { NON_APPROVATO, GIA_IN_STUDIO, BLOCCO_FACCENDE, TROPPO_TARDI, SCOLLEGATO, SERVER_VECCHIO }

    /**
     * L'avvio a mano a `I` = [oraServer]: niente di approvato, già in Studio,
     * il blocco dei lavori attivo (o che lo diventa a `I`: [bloccoAllInizio]),
     * troppo tardi (da `I` a mezzanotte meno del minimo).
     */
    fun noAvvio(oraServer: Long, bloccoAllInizio: Boolean): NoAvvio? {
        if (scollegato) return NoAvvio.SCOLLEGATO
        if (serverVecchio) return NoAvvio.SERVER_VECCHIO
        val approvata = config?.approvata ?: return NoAvvio.NON_APPROVATO
        if (attivo(oraServer) != null) return NoAvvio.GIA_IN_STUDIO
        if (bloccoAllInizio) return NoAvvio.BLOCCO_FACCENDE
        val zona = zona()
        val mezzanotte = RegoleStudio.mezzanotteDopo(RegoleStudio.giorno(oraServer, zona), zona)
        if (mezzanotte - oraServer < approvata.minutiMinimi * 60_000L) return NoAvvio.TROPPO_TARDI
        return null
    }

    /** Lo Studio a mano parte qui, adesso (anche senza rete), e l'avvio va in coda. */
    fun conAvvioManuale(chiave: String, oraServer: Long): MemoriaStudio {
        val approvata = config?.approvata ?: return this
        return copy(
            avvioManuale = AvvioManuale(
                chiave = chiave,
                inizio = oraServer,
                giorno = RegoleStudio.giorno(oraServer, zona()).toString(),
                app = approvata.app,
                nomi = approvata.nomi,
                minutiMinimi = approvata.minutiMinimi,
            ),
            avvioRifiutato = null,
            // Uno Studio nuovo: una chiusura vecchia rifiutata non vale più.
            rifiuto = null,
        )
    }

    /**
     * Il server ha preso l'avvio (201/200): da qui lo Studio è quello del
     * server. Se l'avvio cade dentro uno Studio già chiuso, il telefono lo
     * adotta e chiude il suo.
     */
    fun conAvvioConsegnato(chiave: String, studio: StudioSvolto?): MemoriaStudio {
        if (avvioManuale?.chiave != chiave) return this
        if (studio == null) return copy(avvioManuale = null)
        // Una chiusura fatta qui, senza rete, su questo Studio a mano: da adesso
        // va con l'id del server. Se il server ha fatto ADOTTARE un altro Studio
        // (l'avvio cadeva dentro uno Studio che c'era già), la nostra chiave non
        // la conosce: con la chiave la chiusura riceverebbe un 404 e andrebbe persa.
        val chiusureDopo = chiusure.map { c ->
            if (!c.consegnata && c.rif.id == null && c.rif.chiave == chiave) c.copy(rif = c.rif.copy(id = studio.id)) else c
        }
        return if (studio.fine == null) {
            copy(avvioManuale = null, inCorsoServer = studio, chiusure = chiusureDopo)
        } else {
            copy(avvioManuale = null, recenti = listOf(studio) + recenti.filterNot { it.id == studio.id }, chiusure = chiusureDopo)
        }
    }

    /** Il server ha rifiutato l'avvio (409): lo Studio a mano non c'è, e lo si dice. */
    fun conAvvioRifiutato(chiave: String, motivo: String): MemoriaStudio {
        val a = avvioManuale ?: return this
        if (a.chiave != chiave) return this
        // Le chiusure di quello Studio non servono più.
        val inizio = a.inizio
        return copy(avvioManuale = null, avvioRifiutato = motivo, chiusure = chiusure.filterNot { it.inizioStudio == inizio && !it.consegnata })
    }

    // --- I tratti verso il server -----------------------------------------------------------

    /** I tratti da mandare (al massimo [quanti]), dal più vecchio. */
    fun trattiDaMandare(quanti: Int = 50): List<TrattoLocale> = tratti.filter { it.daMandare }.sortedBy { it.inizio }.take(quanti)

    /** I tratti di [ids] sono arrivati al server, così com'erano quando sono partiti ([inCorso] = in corso allora). */
    fun conTrattiConsegnati(mandati: List<TrattoLocale>, oraServer: Long): MemoriaStudio {
        val perId = mandati.associateBy { it.id }
        return copy(
            tratti = tratti.map { t ->
                val m = perId[t.id] ?: return@map t
                if (m.inCorso) t.copy(inCorsoMandato = true) else if (!t.inCorso) t.copy(consegnato = true, consegnatoIl = oraServer, inCorsoMandato = true) else t
            },
        )
    }

    // --- Il passare del tempo ------------------------------------------------------------------

    /**
     * Ogni giro (e dopo ogni cambio): un riavvio chiude il tratto in corso
     * all'ultimo punto salvato; la mezzanotte lo chiude lì; uno Studio finito
     * (chiuso dal genitore, dal server) lo ferma adesso. Poi i periodi per la
     * misura, e la pulizia di quello che è vecchio.
     */
    fun normalizzata(ora: Istante, oraServer: Long, agganciata: Boolean, perFine: Long = oraServer): MemoriaStudio {
        var m = this
        // 1. Un riavvio: il tratto in corso si chiude all'ultimo punto salvato.
        m = m.copy(tratti = m.tratti.mapNotNull { t -> if (t.inCorso && !t.stessaAccensione(ora)) m.chiudiOTogli(t.chiusoDalRiavvio()) else t })
        val studio = m.attivo(oraServer, perFine)
        // 2. Il tratto in corso fuori dallo Studio: a mezzanotte si chiude lì, altrimenti adesso.
        m = m.copy(
            tratti = m.tratti.mapNotNull { t ->
                if (!t.inCorso) return@mapNotNull t
                if (studio != null) return@mapNotNull t.copy(studio = studio.inizio)
                val giorno = RegoleStudio.giorno(t.studio ?: t.inizio, zona())
                val mezzanotte = RegoleStudio.mezzanotteDopo(giorno, zona())
                if (oraServer >= mezzanotte) m.chiudiOTogli(t.chiusoAMezzanotte(ora, mezzanotte)) else m.chiudiOTogli(t.chiuso(ora, oraServer, agganciata))
            },
        )
        // 3. I periodi dello Studio (la misura): quello in corso aperto, gli altri chiusi.
        m = m.copy(periodi = m.periodiAggiornati(studio, oraServer))
        // 4. La pulizia: tratti consegnati, chiusure consegnate e periodi di più di 9 giorni.
        val soglia = oraServer - GIORNI_MEMORIA * GIORNO_MS
        m = m.copy(
            tratti = m.tratti.filter { it.inCorso || !it.consegnato || (it.fine ?: it.inizio) >= soglia },
            chiusure = m.chiusure.filter { !it.consegnata || it.fine >= oraServer - 2 * GIORNO_MS },
            periodi = m.periodi.filter { (it.fine ?: Long.MAX_VALUE) >= soglia },
            recenti = m.recenti.filter { (it.fine ?: Long.MAX_VALUE) >= oraServer - 2 * GIORNO_MS },
            // Il motivo del rifiuto vale finché lo Studio c'è.
            rifiuto = m.rifiuto.takeIf { studio != null },
        )
        return m
    }

    private fun periodiAggiornati(studio: StudioAttivo?, oraServer: Long): List<PeriodoStudio> {
        val perInizio = LinkedHashMap<Long, PeriodoStudio>()
        periodi.forEach { perInizio[it.inizio] = it }
        // Quelli che dice il server (chiusi, con la loro fine).
        for (s in recenti + listOfNotNull(inCorsoServer)) {
            val gia = perInizio[s.inizio]
            perInizio[s.inizio] = PeriodoStudio(s.inizio, s.fine ?: gia?.fine, s.app ?: gia?.app ?: config?.approvata?.app.orEmpty())
        }
        if (studio != null) {
            val gia = perInizio[studio.inizio]
            perInizio[studio.inizio] = PeriodoStudio(studio.inizio, null, studio.app.ifEmpty { gia?.app.orEmpty() })
        }
        // Gli altri ancora aperti si chiudono: alla chiusura fatta qui, o a mezzanotte, o adesso.
        return perInizio.values.map { p ->
            if (p.fine != null || p.inizio == studio?.inizio) return@map p
            val zona = zona()
            val mezzanotte = RegoleStudio.mezzanotteDopo(RegoleStudio.giorno(p.inizio, zona), zona)
            val fine = chiusure.filter { it.fine >= p.inizio }.minOfOrNull { it.fine } ?: minOf(oraServer, mezzanotte)
            p.copy(fine = fine.coerceAtLeast(p.inizio))
        }.sortedBy { it.inizio }
    }

    /** La partenza dello Studio [inizio] è stata fatta (sessione chiusa, pagina aperta). */
    fun conPartito(inizio: Long): MemoriaStudio = copy(partitoIl = inizio)

    /** L'avviso dei 5 minuti per la partenza del giorno [giorno] è stato dato. */
    fun conAvvisato(giorno: String): MemoriaStudio = copy(avvisato = giorno)

    /** La configurazione appena tornata dal server (una proposta, un ritiro). */
    fun conConfig(nuova: ConfigStudio): MemoriaStudio = copy(config = nuova)

    companion object {
        /** L'ora del server può essere un po' avanti: due minuti di margine sull'inizio. */
        const val TOLLERANZA_INIZIO_MS = 2L * 60 * 1000

        /** «Con la rete»: una risposta del server di meno di due minuti fa. */
        const val FRESCO_MS = 2L * 60 * 1000

        const val GIORNI_MEMORIA = 9L
        const val GIORNO_MS = 24L * 60 * 60 * 1000

        /** L'avviso «Tra 5 minuti parte lo Studio». */
        const val PREAVVISO_MS = 5L * 60 * 1000

        /** Il punto del timer si salva al massimo ogni 30 secondi (un riavvio chiude il tratto lì). */
        const val PUNTO_OGNI_MS = 30_000L
    }
}
