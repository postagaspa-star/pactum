package eu.stgm.pactum.genitore.sync

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import eu.stgm.pactum.genitore.MainActivity
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.ConfigurazionePostino
import eu.stgm.pactum.genitore.dati.Figlio
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.Impostazioni
import eu.stgm.pactum.genitore.dati.Notifica
import eu.stgm.pactum.genitore.dati.Proposta
import eu.stgm.pactum.genitore.dati.RegolaFinestra
import eu.stgm.pactum.genitore.dati.Sessione
import eu.stgm.pactum.genitore.dati.SilenzioNoto
import eu.stgm.pactum.genitore.dati.StatoSilenzio
import eu.stgm.pactum.genitore.dati.TipiDispositivo
import eu.stgm.pactum.genitore.rete.EsitoFamiglia
import eu.stgm.pactum.genitore.rete.PostinoClient
import eu.stgm.pactum.genitore.ui.CHIAVE_SERVER_VECCHIO
import eu.stgm.pactum.genitore.ui.CambioSilenzio
import eu.stgm.pactum.genitore.ui.FUSO_PATTO
import eu.stgm.pactum.genitore.ui.ID_AVVISO_UNICO
import eu.stgm.pactum.genitore.ui.ID_DIGEST_07
import eu.stgm.pactum.genitore.ui.ID_RIASSUNTO
import eu.stgm.pactum.genitore.ui.SilenzioAttuale
import eu.stgm.pactum.genitore.ui.TestoNotifica
import eu.stgm.pactum.genitore.ui.avvisoTogliibile
import eu.stgm.pactum.genitore.ui.cambioSilenzio
import eu.stgm.pactum.genitore.ui.digestDopoAggiornamento
import eu.stgm.pactum.genitore.ui.dispositiviDellaFinestra
import eu.stgm.pactum.genitore.ui.etichettaDi
import eu.stgm.pactum.genitore.ui.etichettaNotifica
import eu.stgm.pactum.genitore.ui.faccendaDellaNotifica
import eu.stgm.pactum.genitore.ui.idAvvisoSilenzio
import eu.stgm.pactum.genitore.ui.notificaDiFaccende
import eu.stgm.pactum.genitore.ui.notificaDelloStudio
import eu.stgm.pactum.genitore.ui.idDigest
import eu.stgm.pactum.genitore.ui.istanteServer
import eu.stgm.pactum.genitore.ui.nomiDelleApp
import eu.stgm.pactum.genitore.ui.oraOppureDataOra
import eu.stgm.pactum.genitore.ui.paroleDi
import eu.stgm.pactum.genitore.ui.partenzaSilenzi
import eu.stgm.pactum.genitore.ui.silenzioDaSorvegliare
import eu.stgm.pactum.genitore.ui.silenzioDelServerVecchio
import eu.stgm.pactum.genitore.ui.testoAvvisoSilenzio
import eu.stgm.pactum.genitore.ui.testoDigest
import eu.stgm.pactum.genitore.ui.testoNotifica
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalTime

/**
 * Il giro della vedetta (0.9: estratto da VedettaWorker, uno solo per il
 * servizio sempre attivo e per il worker di riserva). Chiede al server le
 * notifiche non lette e alza UNA notifica di sistema per ogni novità mai
 * avvisata prima (gli id già avvisati vivono in DataStore). NON segna niente
 * come letta sul server: "letta" è un gesto del genitore dentro l'app — né il
 * giro, né toccare o scorrere via la notifica di sistema la cambiano.
 *
 * Due giri (CadenzaVedetta):
 * - VELOCE, circa ogni minuto dal servizio: GET /api/notifiche?dopo_id=N, solo
 *   quelle arrivate dopo l'ultima avvisata (contratto v3.3; un server vecchio
 *   ignora il parametro e manda tutto, e va bene lo stesso). La famiglia e la
 *   finestra si leggono solo quando c'è davvero una novità da scrivere (di chi
 *   è, e il nome leggibile della regola), e al massimo per 10 secondi: se non
 *   arrivano, l'avviso parte col testo base.
 * - COMPLETO, circa ogni 15 minuti: la lista INTERA delle non lette (così il
 *   ricordo dimentica le lette), il silenzio dei dispositivi (avvisa quando un
 *   dispositivo smette di mandare dati e, con tono tranquillo, quando il
 *   contatto torna; un dispositivo spento, computer o (0.14) telefono, non è
 *   un silenzio) e il digest della
 *   sera. Su un server 0.7 il silenzio si guarda sulla finestra, come prima.
 *
 * L'aggiornamento dell'app NON passa di qui: lo scarica solo il worker
 * (VedettaWorker), così un download lento non ferma i giri del servizio.
 *
 * (0.13) Dal contratto v3.6 ogni genitore legge le notifiche per conto suo: le
 * non lette e `dopo_id` sono di questo genitore (del suo token), e quelle segnate
 * qui restano non lette per gli altri. Il ricordo degli avvisati è di questo
 * telefono e basta; un genitore appena collegato parte senza le notifiche di prima
 * (per lui valgono come lette). I tipi che non si conoscono (anche quelli che il
 * server aggiungerà) ripiegano sul titolo "Novità dal patto" e sul messaggio del
 * server: un tipo nuovo non ferma il giro.
 *
 * Un giro alla volta in tutto il processo ([turno]): servizio e worker non
 * avvisano mai due volte la stessa novità. Ogni scrittura del giro porta con sé
 * il server per cui è stata fatta: se nel frattempo il genitore ha cambiato
 * server, il giro vecchio non riscrive niente.
 */
class Vedetta(context: Context) {

    private val context: Context = context.applicationContext
    private val impostazioni = Impostazioni(this.context)

    suspend fun giro(): EsitoGiro = turno.withLock { giroInTurno() }

    /** Il giro vero, uno alla volta. */
    private suspend fun giroInTurno(): EsitoGiro {
        val configurazione = impostazioni.leggiConfigurazione()
        if (!configurazione.completa) return EsitoGiro.NON_CONFIGURATA // patto non ancora configurato
        if (!reteDisponibile(context)) return EsitoGiro.SENZA_RETE

        val stato = statoPer(configurazione)
        val adesso = SystemClock.elapsedRealtime()
        val completo = CadenzaVedetta.giroCompletoDovuto(stato.ultimoGiroCompleto, adesso)
        val postino = PostinoClient(configurazione, perLaVedetta = true)
        val ricordo = impostazioni.leggiIdAvvisatiSeCi()
        val notifiche = postino.leggiNotifiche(if (completo) null else dopoIdPerIlGiroVeloce(ricordo))
            ?: return EsitoGiro.SERVER_MUTO // server muto: si riprova
        controlloRiuscito(configurazione, stato)

        val contesto = Contesto(postino)
        if (completo) {
            // Segnato prima: un giro completo che si inceppa a metà non deve
            // ripetersi a ogni giro. Il prossimo completo è fra 15 minuti.
            stato.ultimoGiroCompleto = adesso
            giroCompleto(configurazione, contesto, notifiche, ricordo)
        } else {
            avvisaNovitaDelPatto(configurazione, contesto, notifiche, ricordo, listaIntera = false)
        }
        return EsitoGiro.FATTO
    }

    /**
     * Un giro andato: l'ora in memoria subito (la riga "Avvisi: ultimo
     * controllo" della Panoramica), su disco al massimo ogni 10 minuti.
     */
    private suspend fun controlloRiuscito(configurazione: ConfigurazionePostino, stato: StatoDelServer) {
        val ora = System.currentTimeMillis()
        _ultimoControllo.value = ora
        val adesso = SystemClock.elapsedRealtime()
        if (CadenzaVedetta.registrazioneDovuta(stato.ultimaRegistrazione, adesso)) {
            impostazioni.registraControlloVedetta(ora, configurazione)
            stato.ultimaRegistrazione = adesso
        }
    }

    private suspend fun giroCompleto(
        configurazione: ConfigurazionePostino,
        contesto: Contesto,
        notifiche: List<Notifica>,
        ricordo: Set<Long>?,
    ) {
        avvisaNovitaDelPatto(configurazione, contesto, notifiche, ricordo, listaIntera = true)

        // (v3) La famiglia: lo stato di ogni dispositivo. Se non arriva, silenzio
        // e digest si guardano al giro completo dopo.
        val famiglia = contesto.famiglia()
        val figli = (famiglia as? EsitoFamiglia.Letta)?.famiglia?.figli.orEmpty()

        when (famiglia) {
            is EsitoFamiglia.Letta -> sorvegliaDispositivi(configurazione, figli)
            EsitoFamiglia.ServerVecchio -> sorvegliaSilenzioServerVecchio(configurazione, contesto.finestra(null))
            // si ritenta al giro completo dopo; (0.13) un collegamento che non vale più
            // lo dice l'app, non un avviso di silenzio
            EsitoFamiglia.Fallita, EsitoFamiglia.NonAutorizzato -> Unit
        }

        // Il digest: per figlio in v3, uno solo sul server 0.7. Con la famiglia
        // non letta si aspetta il giro dopo: non si sa di quanti figli dirlo.
        // Prima, una volta: il digest già mandato dalla versione di prima passa
        // al figlio che quella conosceva (l'id più basso; 0 sul server 0.7), se
        // no il giorno dell'aggiornamento il padre lo riceverebbe due volte.
        when (famiglia) {
            is EsitoFamiglia.Letta -> {
                val erede = figli.minOfOrNull { it.id }
                if (erede != null) {
                    impostazioni.passaggioDigest(configurazione) { inviati, giorno07 ->
                        digestDopoAggiornamento(inviati, giorno07, erede)
                    }
                }
                figli.forEach { figlio ->
                    inviaDigest(
                        configurazione,
                        chiave = figlio.id,
                        figlio = figlio,
                        figli = figli,
                        contesto = contesto,
                        erede = figlio.id == erede,
                    )
                }
            }
            EsitoFamiglia.ServerVecchio -> {
                impostazioni.passaggioDigest(configurazione) { inviati, giorno07 ->
                    digestDopoAggiornamento(inviati, giorno07, CHIAVE_SERVER_VECCHIO)
                }
                inviaDigest(
                    configurazione,
                    chiave = CHIAVE_SERVER_VECCHIO,
                    figlio = null,
                    figli = figli,
                    contesto = contesto,
                    erede = true,
                )
            }
            EsitoFamiglia.Fallita, EsitoFamiglia.NonAutorizzato -> Unit
        }
    }

    /**
     * Quello che il giro legge dal server oltre alle notifiche, una volta sola
     * per giro: la famiglia e le finestre (null = server 0.7, nessun `figlio_id`).
     */
    private class Contesto(private val postino: PostinoClient) {
        private var famiglia: EsitoFamiglia? = null
        private val finestre = mutableMapOf<Long?, Finestra?>()

        suspend fun famiglia(): EsitoFamiglia = famiglia ?: postino.leggiFamiglia().also { famiglia = it }

        suspend fun finestra(figlioId: Long?): Finestra? {
            if (figlioId !in finestre) finestre[figlioId] = postino.leggiFinestra(figlioId)
            return finestre[figlioId]
        }
    }

    /**
     * Le novità del patto: una notifica di sistema per ciascuna (o un riassunto,
     * v. modoAvviso), poi il ricordo degli id avvisati. [listaIntera] = il giro
     * completo, con tutte le non lette: il ricordo dimentica quelle già lette.
     */
    private suspend fun avvisaNovitaDelPatto(
        configurazione: ConfigurazionePostino,
        contesto: Contesto,
        notifiche: List<Notifica>,
        ricordo: Set<Long>?,
        listaIntera: Boolean,
    ) {
        val primoGiro = ricordo == null
        val nuove = novitaDaAvvisare(notifiche, ricordo.orEmpty())
        // Senza permesso (o col canale degli avvisi spento) non si avvisa E non si
        // segna: appena gli avvisi tornano, il giro successivo recupera le novità.
        val avvisate = when {
            nuove.isEmpty() || !avvisiAccesi(context) -> emptyList()
            modoAvviso(primoGiro, nuove.size) == ModoAvviso.RIASSUNTO ->
                if (alzaRiassunto(nuove.size)) nuove.map { it.id } else emptyList()
            else -> alzaAvvisi(nuove, contesto)
        }
        // Il primo giro senza avvisi resta "primo": quando gli avvisi tornano, un
        // riassunto e non una raffica.
        if (primoGiro && nuove.isNotEmpty() && avvisate.isEmpty()) return
        impostazioni.ricordaIdAvvisati(
            avvisate,
            nonLette = if (listaIntera) notifiche.map { it.id } else null,
            perConfigurazione = configurazione,
        )
    }

    /** Una notifica di sistema per ogni novità; restituisce gli id davvero avvisati. */
    private suspend fun alzaAvvisi(nuove: List<Notifica>, contesto: Contesto): List<Long> {
        // Di chi è ogni avviso e il nome leggibile della regola, al massimo 10
        // secondi: se il server tarda, l'avviso parte col testo base (il tipo e il
        // messaggio del server), meglio che arrivare tardi.
        val nomi = withTimeoutOrNull(CadenzaVedetta.TEMPO_PER_I_NOMI_MS) { nomiPerGliAvvisi(nuove, contesto) }
            ?: NomiPerGliAvvisi()

        creaCanale(context)
        faiSpazio(inArrivo = nuove.size)
        val gestore = NotificationManagerCompat.from(context)
        val avvisate = mutableListOf<Long>()
        for (notifica in nuove) {
            try {
                gestore.notify(
                    notifica.id.toInt(),
                    notificaDiSistema(notifica, nomi),
                )
            } catch (e: SecurityException) {
                break // permesso revocato tra il controllo e la notify: le altre al giro dopo
            }
            avvisate += notifica.id
        }
        return avvisate
    }

    /**
     * Quello che serve per scrivere gli avvisi per bene: la famiglia (di chi è),
     * le regole (il nome leggibile) e (0.10) le proposte in attesa (che cosa
     * propone il figlio) coi nomi delle app (una proposta che cambia app la dice
     * col nome). (0.11) E le sessioni: quali app chiede una sessione da approvare.
     * Vuoto = testo base.
     */
    private data class NomiPerGliAvvisi(
        val figli: List<Figlio> = emptyList(),
        val regolePerId: Map<Long, RegolaFinestra> = emptyMap(),
        val proposte: Map<Long, Proposta> = emptyMap(),
        val nomi: Map<String, String> = emptyMap(),
        val sessioni: Map<Long, Sessione> = emptyMap(),
    )

    /**
     * La famiglia (di chi è) e le regole delle finestre dei figli delle novità
     * (il nome leggibile): gli id delle regole sono unici su tutto il server,
     * quindi le regole di più figli stanno in una mappa sola. (0.10) Dalle stesse
     * finestre le proposte in attesa, per id, e i nomi delle app; (0.11) e le
     * sessioni, per id: nessuna richiesta in più.
     */
    private suspend fun nomiPerGliAvvisi(
        nuove: List<Notifica>,
        contesto: Contesto,
    ): NomiPerGliAvvisi {
        val figli = (contesto.famiglia() as? EsitoFamiglia.Letta)?.famiglia?.figli.orEmpty()
        val regolePerId = mutableMapOf<Long, RegolaFinestra>()
        val proposte = mutableMapOf<Long, Proposta>()
        val nomi = mutableMapOf<String, String>()
        val sessioni = mutableMapOf<Long, Sessione>()
        nuove.map { it.figlioId }.distinct().forEach { figlioId ->
            val finestra = contesto.finestra(figlioId) ?: return@forEach
            finestra.regole.forEach { regolePerId[it.id] = it }
            finestra.propostePendenti.forEach { proposte[it.id] = it }
            nomi.putAll(nomiDelleApp(finestra))
            finestra.sessioni.forEach { sessioni[it.id] = it }
        }
        return NomiPerGliAvvisi(figli, regolePerId, proposte, nomi, sessioni)
    }

    /**
     * Una sola notifica per tante novità insieme ("Novità da leggere: 12"):
     * toccarla apre la lista. false = non partita (permesso tolto a metà).
     */
    private fun alzaRiassunto(quante: Int): Boolean {
        creaCanale(context)
        faiSpazio(inArrivo = 1)
        val parole = paroleDi(context)
        return try {
            NotificationManagerCompat.from(context).notify(
                ID_RIASSUNTO,
                notificaBase(
                    titolo = parole.testo(R.string.riassunto_novita_titolo, quante),
                    testo = parole.testo(R.string.riassunto_novita_testo),
                    destinazione = MainActivity.DEST_NOTIFICHE,
                ),
            )
            true
        } catch (e: SecurityException) {
            false
        }
    }

    /**
     * Prima di alzare [inArrivo] avvisi: se nella tendina ce ne sono già tanti,
     * via i più vecchi del patto (restano nella lista in app, e sul server non
     * cambia niente). Android tiene al massimo 50 notifiche per app e oltre
     * scarta in silenzio le nuove.
     */
    private fun faiSpazio(inArrivo: Int) {
        val attive = try {
            context.getSystemService(NotificationManager::class.java)?.activeNotifications
        } catch (e: RuntimeException) {
            null
        } ?: return
        val delCanale = attive
            .filter { it.notification.channelId == CANALE_ID }
            .map { AvvisoAttivo(it.id, it.postTime, togliibile = avvisoTogliibile(it.id)) }
        val gestore = NotificationManagerCompat.from(context)
        avvisiDaTogliere(delCanale, inArrivo).forEach { gestore.cancel(it) }
    }

    /**
     * (v3) Il silenzio di OGNI dispositivo di OGNI figlio, dai flag del server.
     * Lo stato osservato (allarme + battito) vive in DataStore per dispositivo,
     * per non riavvisare a ogni giro lo stesso silenzio. Non si sorvegliano i
     * dispositivi non ancora collegati né quelli scollegati.
     *
     * Al primo giro per dispositivo (appena aggiornata dalla 0.7) lo stato della
     * 0.7 passa al dispositivo che la 0.7 guardava, e il suo avviso — con l'id di
     * un dispositivo solo, che nessuno aggiornerebbe più — si toglie
     * (partenzaSilenzi).
     */
    private suspend fun sorvegliaDispositivi(configurazione: ConfigurazionePostino, figli: List<Figlio>) {
        val partenza = partenzaSilenzi(
            salvati = impostazioni.leggiSilenziNoti(),
            versioneVecchia = impostazioni.leggiSilenzioDellaVersioneVecchia(),
            figli = figli,
        )
        val noti = partenza.noti
        val nuovi = mutableMapOf<Long, SilenzioNoto>()
        val parole = paroleDi(context)
        val gestore = NotificationManagerCompat.from(context)
        if (partenza.togliAvvisoUnico) gestore.cancel(ID_AVVISO_UNICO)
        figli.forEach { figlio ->
            figlio.dispositivi.forEach dispositivo@{ dispositivo ->
                val attuale = silenzioDaSorvegliare(
                    dispositivo.tipo,
                    dispositivo.abbinato,
                    dispositivo.revocato,
                    dispositivo.statoSilenzio,
                )
                if (attuale == null) {
                    // Scollegato: un vecchio avviso di silenzio non ha più senso.
                    if (dispositivo.revocato) gestore.cancel(idAvvisoSilenzio(dispositivo.id))
                    return@dispositivo
                }
                val noto = noti[dispositivo.id]
                val testo = testoAvvisoSilenzio(
                    parole,
                    cambioSilenzio(noto, attuale),
                    computer = dispositivo.tipo == TipiDispositivo.COMPUTER,
                    silenzio = dispositivo.statoSilenzio,
                )
                val avvisato = testo == null || avvisa(
                    idAvvisoSilenzio(dispositivo.id),
                    testo,
                    sopra = etichettaDi(figlio.id, dispositivo.id, figli),
                    figlioId = figlio.id,
                )
                // Senza permesso (o permesso tolto a metà) non si registra: il
                // giro dopo ritrova il cambio e avvisa.
                nuovi[dispositivo.id] = if (avvisato || noto == null) attuale.noto() else noto
            }
        }
        impostazioni.salvaSilenziNoti(nuovi, configurazione)
    }

    /**
     * Server 0.7: un dispositivo solo, dal silenzio della finestra, coi testi di
     * prima ("L'app del figlio non invia aggiornamenti dalle 15:10").
     */
    private suspend fun sorvegliaSilenzioServerVecchio(configurazione: ConfigurazionePostino, finestra: Finestra?) {
        val silenzio = finestra?.statoSilenzio ?: return
        val attuale = SilenzioAttuale(
            allarme = silenzio.silente,
            spento = false,
            ultimoBattito = silenzio.ultimoBattito,
        )
        // Appena aggiornata dalla 0.7 vale lo stato della 0.7: stesso dispositivo,
        // stesso id dell'avviso.
        val noto = silenzioDelServerVecchio(
            impostazioni.leggiSilenziNoti(),
            impostazioni.leggiSilenzioDellaVersioneVecchia(),
        )
        val testo = when (cambioSilenzio(noto, attuale)) {
            CambioSilenzio.NUOVO_SILENZIO -> testoSilenzioServerVecchio(silenzio)
            CambioSilenzio.CONTATTO_TORNATO -> testoContattoServerVecchio(silenzio)
            else -> null
        }
        val avvisato = testo == null || avvisa(
            idAvvisoSilenzio(CHIAVE_SERVER_VECCHIO),
            testo,
            sopra = null,
            figlioId = null,
        )
        val registrato = if (avvisato || noto == null) attuale.noto() else noto
        impostazioni.salvaSilenziNoti(mapOf(CHIAVE_SERVER_VECCHIO to registrato), configurazione)
    }

    /** Alza un avviso sul canale del patto; false = avvisi spenti, non partito. */
    private fun avvisa(
        id: Int,
        testo: TestoNotifica,
        sopra: String?,
        figlioId: Long?,
    ): Boolean {
        if (!avvisiAccesi(context)) return false
        creaCanale(context)
        return try {
            // Stesso id per i due versi: "di nuovo in contatto" sostituisce
            // l'avviso di silenzio ormai superato invece di accodarsi.
            NotificationManagerCompat.from(context).notify(
                id,
                notificaBase(testo.titolo, testo.testo, MainActivity.DEST_FINESTRA, sopra, figlioId),
            )
            true
        } catch (e: SecurityException) {
            false
        }
    }

    /**
     * Il digest giornaliero (contratto v2.2 — comportamento dell'app genitore,
     * nessun endpoint nuovo): quando l'ora scelta dal genitore è passata e oggi
     * il digest di quel figlio non è ancora partito, UNA notifica col totale di
     * oggi e le prime app (per dispositivo, se sono più d'uno). Toccarla apre il
     * Tempo di quel figlio. Un oggi senza fotografia lo dice onestamente — MAI
     * uno zero finto. Dedup per figlio e per giorno del patto.
     * [erede] = il figlio che conosceva la 0.7 (l'id più basso): il suo digest
     * prende il posto di quello della 0.7, che aveva un id suo.
     */
    private suspend fun inviaDigest(
        configurazione: ConfigurazionePostino,
        chiave: Long,
        figlio: Figlio?,
        figli: List<Figlio>,
        contesto: Contesto,
        erede: Boolean,
    ) {
        val config = impostazioni.leggiConfigDigest()
        if (!config.attivo) return

        // (v3) Un figlio senza nessun dispositivo collegato (e non scollegato) non
        // ha niente da raccontare: un "nessun dato" ogni sera sarebbe solo rumore.
        if (figlio != null && figlio.dispositivi.none { it.abbinato && !it.revocato }) return

        if (LocalTime.now().hour < config.ora) return // l'ora scelta (fuso del genitore) non è ancora arrivata
        // Già mandato per l'oggi del patto: non serve nemmeno leggere la finestra.
        if (impostazioni.digestGiaInviato(chiave, LocalDate.now(FUSO_PATTO).toString())) return

        // offline o server muto: si ritenta al giro dopo
        val finestra = contesto.finestra(figlio?.id) ?: return

        // "Oggi" del patto = l'ultima voce di uso_recente: il server la etichetta
        // nel fuso del patto, così sia il digest sia il suo dedup restano allineati
        // ai dati e non dipendono dal fuso del telefono del genitore.
        val dispositivi = dispositiviDellaFinestra(finestra)
        val giornoChiave = dispositivi.firstNotNullOfOrNull { it.usoRecente.lastOrNull()?.giorno }
            ?: LocalDate.now(FUSO_PATTO).toString()
        if (impostazioni.digestGiaInviato(chiave, giornoChiave)) return // già mandato oggi

        // Con gli avvisi spenti non si manda E non si registra: appena tornano,
        // il giro successivo recupera il digest di oggi.
        if (!avvisiAccesi(context)) return
        creaCanale(context)

        val testo = testoDigest(paroleDi(context), dispositivi)
        val gestore = NotificationManagerCompat.from(context)
        // "Quello di oggi sostituisce quello di ieri" anche a cavallo
        // dell'aggiornamento: il digest della 0.7 aveva un altro id.
        if (erede) gestore.cancel(ID_DIGEST_07)
        try {
            gestore.notify(
                idDigest(chiave),
                notificaBase(
                    testo.titolo,
                    testo.testo,
                    MainActivity.DEST_TEMPO,
                    sopra = figlio?.nome?.takeIf { figli.size > 1 && it.isNotBlank() },
                    figlioId = figlio?.id,
                ),
            )
        } catch (e: SecurityException) {
            return // permesso revocato tra il controllo e la notify
        }
        impostazioni.registraDigestInviato(chiave, giornoChiave, configurazione)
    }

    /** Titolo e frase dalla stessa funzione della lista in app (testoNotifica, Testi.kt). */
    private fun notificaDiSistema(notifica: Notifica, nomi: NomiPerGliAvvisi): Notification {
        val testo = testoNotifica(
            paroleDi(context),
            notifica,
            nomi.regolePerId,
            nomi.figli,
            nomi.proposte,
            nomi.nomi,
            nomi.sessioni,
        )
        return notificaBase(
            titolo = testo.titolo,
            testo = testo.testo,
            destinazione = destinazionePerTipo(notifica.tipo),
            // (v3) Di quale figlio (e dispositivo): la stessa riga della lista in app.
            sopra = etichettaNotifica(notifica, nomi.figli),
            figlioId = notifica.figlioId,
            // (0.13) Una faccenda fatta: toccarla apre la sua foto.
            faccendaId = faccendaDellaNotifica(notifica),
        )
    }

    private fun testoSilenzioServerVecchio(stato: StatoSilenzio): TestoNotifica {
        val quando = istanteServer(stato.ultimoBattito)?.let { oraOppureDataOra(it) }
        val testo = if (quando != null) {
            context.getString(R.string.notifica_silenzio_testo, quando)
        } else {
            context.getString(R.string.notifica_silenzio_testo_mai)
        }
        // Il silenzio si guarda sulla finestra: la riga di stato è in cima.
        return TestoNotifica(context.getString(R.string.notifica_silenzio_titolo), testo)
    }

    private fun testoContattoServerVecchio(stato: StatoSilenzio): TestoNotifica {
        val quando = istanteServer(stato.ultimoBattito)?.let { oraOppureDataOra(it) } ?: "—"
        return TestoNotifica(
            context.getString(R.string.notifica_contatto_titolo),
            context.getString(R.string.notifica_contatto_testo, quando),
        )
    }

    /**
     * [sopra] = la riga "di chi è" (figlio · dispositivo), mostrata sopra il
     * titolo; [figlioId] = il figlio da scegliere quando si tocca la notifica.
     * Toccarla apre l'app e basta; scorrerla via non fa niente: sul server la
     * notifica resta non letta finché il genitore non la segna nell'app.
     */
    private fun notificaBase(
        titolo: String,
        testo: String,
        destinazione: String? = null,
        sopra: String? = null,
        figlioId: Long? = null,
        faccendaId: Long? = null,
    ): Notification {
        val intent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (destinazione != null) {
            intent.putExtra(MainActivity.EXTRA_DESTINAZIONE, destinazione)
        }
        if (figlioId != null) {
            intent.putExtra(MainActivity.EXTRA_FIGLIO, figlioId)
        }
        if (faccendaId != null) {
            intent.putExtra(MainActivity.EXTRA_FACCENDA, faccendaId)
        }
        // requestCode diverso per destinazione, figlio e (0.13) faccenda: con lo
        // stesso PendingIntent Android riuserebbe gli extra del primo (le notifiche
        // aprirebbero tutte la stessa scheda, sullo stesso figlio, la stessa foto).
        val apriApp = PendingIntent.getActivity(
            context,
            requestCodeAvviso(destinazione, figlioId, faccendaId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CANALE_ID)
            .setSmallIcon(R.drawable.ic_notifica_binocolo)
            .setContentTitle(titolo)
            .setContentText(testo)
            .setSubText(sopra)
            .setStyle(NotificationCompat.BigTextStyle().bigText(testo))
            .setContentIntent(apriApp)
            .setAutoCancel(true)
            // (0.21) A comparsa anche sui telefoni che guardano la priorità della notifica.
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    /**
     * Quello che il processo ricorda del server attuale, tra un giro e l'altro:
     * cambiato il server (o il codice), si riparte da zero, col giro completo.
     */
    private class StatoDelServer(val configurazione: ConfigurazionePostino) {
        var ultimoGiroCompleto: Long? = null
        var ultimaRegistrazione: Long? = null
    }

    companion object {
        /**
         * (0.21) Il canale degli avvisi del patto, a importanza ALTA: le notifiche
         * compaiono in alto sopra l'app in uso (a comparsa), non solo nella tendina.
         * Quello di prima ("avvisi_patto") era a importanza normale e Android non
         * lascia alzarla a un canale già creato: perciò un canale nuovo, e il
         * vecchio si toglie ([creaCanale]).
         */
        const val CANALE_ID = "avvisi_patto_2"

        /** (0.21) Il canale delle versioni fino alla 0.20, a importanza normale: si toglie. */
        const val CANALE_VECCHIO = "avvisi_patto"

        // Gli id degli avvisi (silenzio per dispositivo, digest per figlio,
        // riassunto) e la chiave del server 0.7 stanno in LogicaFamiglia.kt,
        // provati da JUnit.

        /** Un giro alla volta in tutto il processo: servizio e worker si mettono in fila. */
        private val turno = Mutex()

        @Volatile
        private var stato: StatoDelServer? = null

        private val _ultimoControllo = MutableStateFlow<Long?>(null)

        /**
         * L'ora (epoch ms) dell'ultimo giro andato a buon fine in questo processo,
         * null = nessuno ancora (o server appena cambiato). Preciso al minuto: su
         * disco va solo ogni 10 minuti (Impostazioni.ultimoControlloAvvisi).
         */
        val ultimoControllo: StateFlow<Long?> = _ultimoControllo.asStateFlow()

        private fun statoPer(configurazione: ConfigurazionePostino): StatoDelServer =
            stato?.takeIf { it.configurazione == configurazione }
                ?: StatoDelServer(configurazione).also {
                    stato = it
                    _ultimoControllo.value = null
                }

        private fun SilenzioAttuale.noto(): SilenzioNoto = SilenzioNoto(allarme, ultimoBattito, spento)

        /**
         * Il canale degli avvisi. (0.21) Nasce a importanza alta; se c'era quello
         * vecchio, chi l'aveva spento lo ritrova spento (una scelta del genitore
         * non si cancella con un aggiornamento) e il vecchio si toglie. Lo chiama
         * il servizio quando parte (così dopo l'aggiornamento il canale è subito
         * quello nuovo) e ogni avviso.
         */
        fun creaCanale(context: Context) {
            val gestore = NotificationManagerCompat.from(context)
            if (gestore.getNotificationChannelCompat(CANALE_ID) == null) {
                val vecchio = gestore.getNotificationChannelCompat(CANALE_VECCHIO)
                gestore.createNotificationChannel(
                    NotificationChannelCompat.Builder(CANALE_ID, importanzaCanaleNuovo(vecchio?.importance))
                        .setName(context.getString(R.string.canale_patto_nome))
                        .setDescription(context.getString(R.string.canale_patto_descrizione))
                        .build(),
                )
            }
            if (gestore.getNotificationChannelCompat(CANALE_VECCHIO) != null) {
                gestore.deleteNotificationChannel(CANALE_VECCHIO)
            }
        }

        fun puoAvvisare(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                NotificationManagerCompat.from(context).areNotificationsEnabled()
            }

        /**
         * (0.9) true = gli avvisi del patto possono arrivare: permesso dato,
         * notifiche dell'app accese e il canale "Avvisi del patto" non spento. Un
         * canale che non esiste ancora non è spento: nasce col primo avviso.
         */
        fun avvisiAccesi(context: Context): Boolean {
            if (!puoAvvisare(context)) return false
            val gestore = NotificationManagerCompat.from(context)
            if (!gestore.areNotificationsEnabled()) return false
            // (0.21) Prima che nasca quello nuovo, conta quello vecchio (se l'avevi spento).
            val canale = gestore.getNotificationChannelCompat(CANALE_ID)
                ?: gestore.getNotificationChannelCompat(CANALE_VECCHIO)
                ?: return true
            return canale.importance != NotificationManagerCompat.IMPORTANCE_NONE
        }

        /**
         * true = il telefono ha una rete con internet. Senza, il giro non chiede
         * niente: non è il server a tacere, e il servizio non deve diradare i giri.
         */
        fun reteDisponibile(context: Context): Boolean {
            val connettivita = context.getSystemService(ConnectivityManager::class.java) ?: return true
            val rete = connettivita.activeNetwork ?: return false
            val capacita = connettivita.getNetworkCapabilities(rete) ?: return false
            return capacita.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        /**
         * Dove aprire l'app toccando la notifica (hook di navigazione). Tutto
         * quello che aspetta il genitore o che gli risponde va su "Da decidere";
         * tutto il resto apre la lista delle notifiche, dove quella stessa
         * notifica si legge per intero, si tocca per andare dove serve e si segna
         * come letta.
         *
         * (0.15) Le proposte del figlio, le sessioni da approvare, le risposte alle
         * tue proposte, i ritiri e le dichiarazioni: tutte su "Da decidere" (prima
         * le prime due aprivano la Panoramica, le altre "Proposte e conferme").
         */
        internal fun destinazionePerTipo(tipo: String): String = when {
            tipo in TIPI_DA_DECIDERE -> MainActivity.DEST_DECIDERE
            // (0.13) Le faccende (contratto v3.6): la pagina delle faccende di quel
            // figlio, e per una faccenda fatta la sua foto.
            notificaDiFaccende(tipo) -> MainActivity.DEST_FACCENDE
            // (0.18) Lo Studio fatto (iniziato, chiuso, non chiuso, non partito): la sua pagina.
            notificaDelloStudio(tipo) -> MainActivity.DEST_STUDIO
            else -> MainActivity.DEST_NOTIFICHE
        }

        /** (0.15) I tipi di notifica che portano a "Da decidere". */
        private val TIPI_DA_DECIDERE = setOf(
            "nuova_proposta",
            "sessione_da_approvare",
            // (0.18) La configurazione dello Studio da approvare.
            "studio_da_approvare",
            "proposta_risposta",
            "proposta_annullata",
            "proposta_ritirata",
            "dichiarazione",
        )

        /**
         * (0.13) Il requestCode del tocco su un avviso: uno per destinazione, figlio e
         * faccenda, così due avvisi diversi non si rubano gli extra. Con [faccendaId]
         * null è quello di prima (stesso hash della 0.12).
         */
        internal fun requestCodeAvviso(destinazione: String?, figlioId: Long?, faccendaId: Long?): Int {
            val base = "${destinazione.orEmpty()}|${figlioId ?: ""}"
            return (if (faccendaId == null) base else "$base|$faccendaId").hashCode()
        }
    }
}
