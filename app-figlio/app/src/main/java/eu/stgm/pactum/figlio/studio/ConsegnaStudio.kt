package eu.stgm.pactum.figlio.studio

import android.content.Context
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.faccende.ArchivioBlocco
import eu.stgm.pactum.figlio.faccende.ControlloBlocco
import eu.stgm.pactum.figlio.rete.PostinoClient
import eu.stgm.pactum.figlio.sync.Ritento
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * (0.18, contratto v4.0, parte C) Le azioni del figlio sullo Studio e la loro
 * consegna al server.
 *
 * Tutto vale SUBITO sul telefono, anche senza rete: lo Studio a mano parte,
 * il timer cronometra, la chiusura chiude. Poi la coda parte appena può,
 * nell'ordine giusto: prima l'avvio di uno Studio a mano (l'`avvia` parte
 * sempre prima della sua `chiudi`), poi i tratti, poi le chiusure. Si
 * riprova con attesa crescente (Ritento) dal giro del servizio, quando torna
 * la rete e dal worker. Il server risponde allo stesso modo a una richiesta
 * ripetuta (`chiave`, `id` dei tratti): riprovare è sicuro.
 */
object ConsegnaStudio {

    private val mutex = Mutex()
    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var falliti = 0
    @Volatile private var ultimoTentativo = 0L

    // --- Le azioni del figlio -------------------------------------------------------

    /** «Comincia un'attività»: un tratto nuovo (quello di prima finisce). False se non è partito. */
    suspend fun iniziaTratto(context: Context, tipo: String, parola: String?, faccendaId: Long?): Boolean {
        val app = context.applicationContext
        val o = OraServer.adesso(app)
        val id = UUID.randomUUID().toString()
        val dopo = withContext(Dispatchers.IO) {
            ArchivioStudio.modifica(app) { it.conTrattoIniziato(id, tipo, parola, faccendaId, o.ora, o.server, o.agganciata) }
        }
        val partito = dopo.trattoInCorso?.id == id
        if (partito) consegnaPiuTardi(app)
        return partito
    }

    /** «Ferma»: il tratto in corso finisce adesso. */
    suspend fun fermaTratto(context: Context) {
        val app = context.applicationContext
        val o = OraServer.adesso(app)
        withContext(Dispatchers.IO) { ArchivioStudio.modifica(app) { it.conTrattoFermato(o.ora, o.server, o.agganciata) } }
        consegnaPiuTardi(app)
    }

    /** Com'è andato «Chiudi lo Studio» sul telefono. */
    enum class EsitoChiudi { CHIUSO, SERVE_LA_RETE, NON_CHIUDIBILE, DICHIARAZIONE_NON_VALIDA, NIENTE_DA_CHIUDERE }

    /**
     * «Chiudi lo Studio»: chiude subito qui e manda la chiusura. Senza l'ora
     * del server agganciata in questa accensione (riavvio senza rete) prima
     * la si chiede al server: se non risponde, per chiudere serve la rete.
     */
    suspend fun chiudi(context: Context, dichiarazione: String): EsitoChiudi {
        val app = context.applicationContext
        if (RegoleStudio.dichiarazione(dichiarazione) == null) return EsitoChiudi.DICHIARAZIONE_NON_VALIDA
        var o = OraServer.adesso(app)
        if (!o.agganciata) {
            runCatching { ControlloBlocco.interroga(app) }
            o = OraServer.adesso(app)
            if (!o.agganciata) return EsitoChiudi.SERVE_LA_RETE
        }
        val chiave = UUID.randomUUID().toString()
        var esito = EsitoChiudi.NIENTE_DA_CHIUDERE
        withContext(Dispatchers.IO) {
            ArchivioStudio.modifica(app) { m ->
                val studio = m.attivo(o)
                when {
                    studio == null -> m
                    !m.chiudibile(studio, o.ora, o.server) -> m.also { esito = EsitoChiudi.NON_CHIUDIBILE }
                    else -> (m.conChiusura(chiave, dichiarazione, o.ora, o.server, o.agganciata) ?: m)
                        .also { if (it !== m) esito = EsitoChiudi.CHIUSO }
                }
            }
        }
        if (esito == EsitoChiudi.CHIUSO) {
            falliti = 0
            ambito.launch { runCatching { consegna(app) } }
            runCatching { ControlloStudio.dopo(app) }
        }
        return esito
    }

    /**
     * L'avvio a mano: il telefono controlla da solo (anche senza rete) se si
     * può (niente di approvato, il blocco dei lavori attivo o che lo diventa
     * all'inizio, troppo tardi); poi prova col server. Senza rete parte qui e
     * la consegna aspetta. Null = partito; altrimenti il perché no.
     */
    suspend fun avviaAMano(context: Context): String? {
        val app = context.applicationContext
        val o = OraServer.adesso(app)
        val blocco = ArchivioBlocco.leggi(app)
        // Il blocco attivo, o che lo diventa all'inizio dichiarato (lo stesso 409 del server).
        val bloccoAllInizio = blocco.attivoAdesso(o.ora) || (blocco.prossimo?.let { it <= o.server } ?: false)
        ArchivioStudio.leggi(app).noAvvio(o.server, bloccoAllInizio)?.let { return it.name.lowercase() }
        val chiave = UUID.randomUUID().toString()
        withContext(Dispatchers.IO) { ArchivioStudio.modifica(app) { it.conAvvioManuale(chiave, o.server) } }
        falliti = 0
        // Subito al server (se c'è la rete): un no del server toglie lo Studio e lo dice.
        runCatching { consegna(app) }
        runCatching { ControlloStudio.dopo(app) }
        val m = ArchivioStudio.leggi(app)
        // Ancora in coda (senza rete) o adottato dal server: partito. Altrimenti il no del server.
        if (m.avvioManuale?.chiave == chiave || m.attivo(OraServer.adesso(app)) != null) return null
        return m.avvioRifiutato ?: EsitiStudio.GIA_CHIUSO
    }

    /** Proporre (o ritirare con un PATCH uguale all'approvata) la configurazione. */
    suspend fun proponi(context: Context, nuova: ContenutoStudio): EsitoProposta {
        val app = context.applicationContext
        val configurazione = Impostazioni(app).leggiConfigurazione()
        if (!configurazione.completa) return EsitoProposta.Scollegato
        val config = ArchivioStudio.leggi(app).config
        val base = config?.inAttesa ?: config?.approvata
        val corpo = CorpiStudio.proposta(nuova, base) ?: return EsitoProposta.Fatta(config)
        val r = PostinoClient(configurazione).proponiStudio(corpo)
        return EsitiStudio.proposta(r.ok, r.codice, r.corpo).also { conConfig(app, it) }
    }

    /** «Ritira la proposta». */
    suspend fun ritira(context: Context): EsitoProposta {
        val app = context.applicationContext
        val configurazione = Impostazioni(app).leggiConfigurazione()
        if (!configurazione.completa) return EsitoProposta.Scollegato
        val r = PostinoClient(configurazione).ritiraPropostaStudio()
        return EsitiStudio.proposta(r.ok, r.codice, r.corpo).also { conConfig(app, it) }
    }

    private fun conConfig(app: Context, esito: EsitoProposta) {
        if (esito is EsitoProposta.Fatta) esito.config?.let { c -> ArchivioStudio.modifica(app) { it.conConfig(c) } }
        if (esito == EsitoProposta.ServerVecchio) ArchivioStudio.modifica(app) { it.conServerVecchio() }
    }

    // --- La consegna -----------------------------------------------------------------

    private fun consegnaPiuTardi(app: Context) {
        ambito.launch { runCatching { consegna(app) } }
    }

    /** Dal giro del servizio e dal worker ([forza]): si riprova se l'attesa è passata. */
    suspend fun riprovaSeServe(context: Context, adesso: Long = System.currentTimeMillis(), forza: Boolean = false) {
        val app = context.applicationContext
        val m = ArchivioStudio.leggi(app)
        if (m.avvioManuale == null && m.trattiDaMandare().isEmpty() && m.chiusureDaConsegnare.isEmpty()) {
            falliti = 0
            return
        }
        if (!forza && !Ritento.pronto(falliti, ultimoTentativo, adesso)) return
        consegna(app)
    }

    /** La rete è tornata: si riprova subito. */
    fun reteTornata(context: Context) {
        falliti = 0
        consegnaPiuTardi(context.applicationContext)
    }

    /** Manda adesso quello che c'è in coda. True se non resta niente. */
    suspend fun consegna(context: Context): Boolean = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val ok = mutex.withLock {
            ultimoTentativo = System.currentTimeMillis()
            val configurazione = Impostazioni(app).leggiConfigurazione()
            if (!configurazione.completa) return@withLock false
            val postino = PostinoClient(configurazione)
            consegnaAvvio(app, postino) && consegnaTratti(app, postino) && consegnaChiusure(app, postino)
        }
        if (ok) falliti = 0 else falliti += 1
        ok
    }

    /** L'avvio a mano: sempre prima della sua chiusura. */
    private suspend fun consegnaAvvio(app: Context, postino: PostinoClient): Boolean {
        val a = ArchivioStudio.leggi(app).avvioManuale ?: return true
        val r = postino.avviaStudio(CorpiStudio.avvia(a))
        return when (val esito = EsitiStudio.avvio(r.ok, r.codice, r.corpo)) {
            is EsitoAvvioStudio.Avviato -> {
                ArchivioStudio.modifica(app) { it.conAvvioConsegnato(a.chiave, esito.studio) }
                // Lo Studio del server può essere cambiato (spostato, adottato): si rilegge subito.
                ControlloStudio.richiedi()
                true
            }
            is EsitoAvvioStudio.Rifiutato -> {
                ArchivioStudio.modifica(app) { it.conAvvioRifiutato(a.chiave, esito.motivo) }
                true
            }
            EsitoAvvioStudio.ServerVecchio -> {
                // Un 404/405 può essere la pagina di una rete Wi-Fi pubblica: l'avvio
                // resta in coda, come senza rete. Un server davvero vecchio lo dice
                // il patto senza `studio`, che spegne lo Studio (MemoriaStudio.senzaStudio).
                ArchivioStudio.modifica(app) { it.conServerVecchio() }
                false
            }
            EsitoAvvioStudio.Scollegato -> {
                ControlloStudio.scollegato(app)
                false
            }
            EsitoAvvioStudio.SenzaRete, EsitoAvvioStudio.Errore -> false
        }
    }

    private suspend fun consegnaTratti(app: Context, postino: PostinoClient): Boolean {
        while (true) {
            val o = OraServer.adesso(app)
            val pacco = ArchivioStudio.leggi(app).trattiDaMandare(50)
            if (pacco.isEmpty()) return true
            val r = postino.mandaTrattiStudio(CorpiStudio.tratti(pacco, o.ora))
            when (EsitiStudio.tratti(r.ok, r.codice, r.corpo)) {
                // 422: il pacco così non va e non andrà mai: si lascia andare (resta solo sul telefono).
                EsitoTratti.CONSEGNATI, EsitoTratti.SCARTATI ->
                    ArchivioStudio.modifica(app) { it.conTrattiConsegnati(pacco, OraServer.adesso(app).server) }
                EsitoTratti.SCOLLEGATO -> {
                    ControlloStudio.scollegato(app)
                    return false
                }
                EsitoTratti.SERVER_VECCHIO, EsitoTratti.SENZA_RETE, EsitoTratti.ERRORE -> return false
            }
        }
    }

    private suspend fun consegnaChiusure(app: Context, postino: PostinoClient): Boolean {
        for (c in ArchivioStudio.leggi(app).chiusureDaConsegnare) {
            val o = OraServer.adesso(app)
            val m = ArchivioStudio.leggi(app)
            // Uno Studio a mano non ancora arrivato al server: prima l'avvio.
            if (c.rif.id == null && c.rif.chiave != null && m.avvioManuale?.chiave == c.rif.chiave) return false
            val tratti = m.tratti.filter { (it.studio ?: it.inizio) >= c.inizioStudio && it.inizio <= c.fine && !it.inCorso }
            val r = postino.chiudiStudio(c.rif.id, CorpiStudio.chiudi(c, tratti, o.ora))
            val esito = EsitiStudio.chiusura(r.ok, r.codice, r.corpo)
            // I tratti nel corpo si registrano prima dei controlli, anche se la chiusura è rifiutata.
            if (esito is EsitoChiusura.Chiusa || esito is EsitoChiusura.GiaChiusa || esito is EsitoChiusura.Rifiutata) {
                ArchivioStudio.modifica(app) { it.conTrattiConsegnati(tratti, o.server) }
            }
            // Dopo una chiusura consegnata o rifiutata lo stato del server si rilegge
            // subito: una chiusura fatta senza rete prima di una partenza assorbita fa
            // nascere sul server lo Studio di quella partenza (contratto v4.0, parte C).
            when (esito) {
                is EsitoChiusura.Chiusa -> {
                    ArchivioStudio.modifica(app) { it.conChiusuraConsegnata(c.chiave, esito.studio) }
                    ControlloStudio.richiedi()
                }
                is EsitoChiusura.GiaChiusa -> {
                    ArchivioStudio.modifica(app) { it.conChiusuraConsegnata(c.chiave, esito.studio) }
                    ControlloStudio.richiedi()
                }
                is EsitoChiusura.Rifiutata -> {
                    ArchivioStudio.modifica(app) {
                        it.conChiusuraRifiutata(c.chiave, esito.motivo, esito.studio, esito.chiudibileDal, esito.minuti, esito.minimi)
                    }
                    ControlloStudio.richiedi()
                }
                EsitoChiusura.NonTrovata -> ArchivioStudio.modifica(app) { it.conChiusuraPersa(c.chiave) }
                EsitoChiusura.Scollegato -> {
                    ControlloStudio.scollegato(app)
                    return false
                }
                EsitoChiusura.ServerVecchio, EsitoChiusura.SenzaRete, EsitoChiusura.Errore -> return false
            }
        }
        return true
    }
}
