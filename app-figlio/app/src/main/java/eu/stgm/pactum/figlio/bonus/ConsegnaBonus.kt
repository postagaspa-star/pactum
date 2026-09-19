package eu.stgm.pactum.figlio.bonus

import android.content.Context
import eu.stgm.pactum.figlio.dati.BonusIn
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.dati.PattoLocale
import eu.stgm.pactum.figlio.dati.leggiDettaglioErrore
import eu.stgm.pactum.figlio.dati.zonaPatto
import eu.stgm.pactum.figlio.rete.PostinoClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.util.UUID

/**
 * La consegna del bonus in due tocchi (redesign B5). Tre garanzie:
 *
 * 1. **Mai perso.** Il bonus va su disco (CassettaBonus) al primo tocco, prima
 *    di qualunque altra cosa. Il timer della snackbar gira nell'ambito del
 *    PROCESSO, non della schermata: se la schermata viene distrutta il bonus
 *    parte lo stesso. Se muore il processo, lo riprende [recupera] — chiamato
 *    dalla schermata Oggi, dal loop del servizio e dal worker.
 * 2. **Mai due volte.** Ogni invio passa da un solo mutex e da un solo record:
 *    chi arriva secondo non trova più niente da mandare. Il record si segna
 *    `inviato` PRIMA del POST, con quanti minuti bonus il server contava in
 *    quel momento: se la risposta si perde, prima di rimandare si guarda se il
 *    contatore è già cresciuto (RegoleBonus.passo).
 * 3. **Uno alla volta.** Finché un bonus è in sospeso non se ne prepara un
 *    altro: è anche ciò che rende affidabile il confronto col contatore.
 */
object ConsegnaBonus {

    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _esiti = MutableSharedFlow<EsitoBonus>(extraBufferCapacity = 8)

    /** Gli esiti delle consegne, per chi sta guardando la schermata (best effort). */
    val esiti: SharedFlow<EsitoBonus> = _esiti.asSharedFlow()

    /**
     * Il tocco su +15: il bonus va su disco e parte da solo quando si chiude la
     * finestra della snackbar. Null se ce n'è già uno in sospeso.
     */
    suspend fun prepara(context: Context, regolaId: Long, minuti: Int, giorno: String): BonusInSospeso? {
        val app = context.applicationContext
        val bonus = mutex.withLock {
            val cassetta = CassettaBonus(app)
            if (cassetta.leggi() != null) return null
            BonusInSospeso(
                id = UUID.randomUUID().toString(),
                regolaId = regolaId,
                minuti = minuti,
                giorno = giorno,
                creatoIl = System.currentTimeMillis(),
            ).also { cassetta.scrivi(it) }
        }
        ambito.launch {
            delay(RegoleBonus.FINESTRA_MS)
            consegna(app, bonus.id, motivo = null, soloSePronto = true)
        }
        return bonus
    }

    /**
     * "Aggiungi perché": il timer non lo manderà più, aspetta il perché. False
     * se il bonus è già partito (la finestra si è chiusa un attimo prima).
     */
    suspend fun aspettaPerche(context: Context, id: String): Boolean = mutex.withLock {
        val cassetta = CassettaBonus(context.applicationContext)
        val bonus = cassetta.leggi()
        if (bonus == null || bonus.id != id || bonus.inviato) return@withLock false
        cassetta.scrivi(bonus.copy(inScrittura = true))
        true
    }

    /** Manda adesso, col perché (o senza, se [motivo] è vuoto). */
    fun manda(context: Context, id: String, motivo: String?) {
        val app = context.applicationContext
        ambito.launch { consegna(app, id, motivo?.trim()?.ifBlank { null }, soloSePronto = false) }
    }

    /** Riprende un bonus rimasto a metà (processo morto, rete assente). Idempotente. */
    suspend fun recupera(context: Context) {
        consegna(context.applicationContext, id = null, motivo = null, soloSePronto = true)
    }

    fun recuperaInFondo(context: Context) {
        val app = context.applicationContext
        ambito.launch { recupera(app) }
    }

    /**
     * Server o codice cambiati nelle Impostazioni: il bonus in sospeso era del
     * patto vecchio e non deve partire verso quello nuovo (né essere
     * "verificato" sul contatore di un altro patto). Sotto lo stesso mutex
     * della consegna, così un invio già in corso finisce prima. Si butta solo
     * il bonus [id] che c'era al momento del cambio, non uno preparato dopo.
     */
    fun dimenticaInFondo(context: Context, id: String) {
        val app = context.applicationContext
        ambito.launch {
            mutex.withLock {
                val cassetta = CassettaBonus(app)
                if (cassetta.leggi()?.id == id) cassetta.svuota()
            }
        }
    }

    private suspend fun consegna(
        context: Context,
        id: String?,
        motivo: String?,
        soloSePronto: Boolean,
    ): EsitoBonus? = mutex.withLock {
        val cassetta = CassettaBonus(context)
        val registrato = cassetta.leggi() ?: return@withLock null
        if (id != null && registrato.id != id) return@withLock null
        if (soloSePronto && !RegoleBonus.pronto(registrato, System.currentTimeMillis())) {
            return@withLock null
        }
        val bonus = registrato.copy(
            motivo = motivo ?: registrato.motivo,
            inScrittura = false,
        )
        // Il perché appena scritto va su disco prima di tutto il resto: se
        // qualcosa si rompe dopo, il recupero lo manda col perché.
        if (bonus != registrato) cassetta.scrivi(bonus)
        val esito = try {
            tenta(context, cassetta, bonus)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Qualunque imprevisto: il record resta su disco (al più già segnato
            // `inviato`, quindi verificato prima di rimandarlo) e si riprova.
            EsitoBonus.SenzaRete(bonus.minuti)
        }
        _esiti.tryEmit(esito)
        esito
    }

    private suspend fun tenta(
        context: Context,
        cassetta: CassettaBonus,
        bonus: BonusInSospeso,
    ): EsitoBonus {
        val configurazione = Impostazioni(context).leggiConfigurazione()
        val postino = PostinoClient(configurazione)
        // Prima si legge il patto: dà il giorno del server e quanti minuti bonus
        // conta già su questa regola. Senza questa lettura non si manda niente:
        // un invio senza base non si potrebbe verificare, e rischierebbe il doppio.
        val patto = postino.leggiPatto()
        return if (patto == null) {
            EsitoBonus.SenzaRete(bonus.minuti)
        } else {
            PattoLocale(context).salva(patto)
            val oggi = LocalDate.now(zonaPatto(patto.fuso)).toString()
            // Il giorno del server è l'ultima data della striscia (v2.4). Un
            // server vecchio senza striscia: il giorno del patto sul telefono.
            val giornoServer = patto.striscia.lastOrNull()?.data?.takeIf { it.isNotBlank() } ?: oggi
            val sulServer = patto.bonusOggiPerRegola[bonus.regolaId.toString()] ?: 0
            when (RegoleBonus.passo(bonus, oggi, sulServer, giornoServer)) {
                RegoleBonus.Passo.Scarta -> {
                    cassetta.svuota()
                    EsitoBonus.Scaduto(bonus.minuti)
                }
                RegoleBonus.Passo.ScartaGiaPartito -> {
                    cassetta.svuota()
                    EsitoBonus.GiornoCambiato(bonus.minuti)
                }
                RegoleBonus.Passo.GiaArrivato -> {
                    cassetta.svuota()
                    EsitoBonus.Concesso(bonus.minuti)
                }
                RegoleBonus.Passo.Manda -> {
                    cassetta.scrivi(
                        bonus.copy(inviato = true, base = sulServer, giornoBase = giornoServer),
                    )
                    val risposta = postino.inviaBonus(
                        BonusIn(minuti = bonus.minuti, regolaId = bonus.regolaId, motivo = bonus.motivo),
                    )
                    val esito = RegoleBonus.esitoRisposta(
                        minuti = bonus.minuti,
                        ok = risposta.ok,
                        codice = risposta.codice,
                        dettaglio = leggiDettaglioErrore(risposta.corpo),
                    )
                    if (esito !is EsitoBonus.SenzaRete) cassetta.svuota()
                    // Il limite efficace di oggi è cambiato: la sentinella deve
                    // saperlo subito, non al prossimo giro del worker.
                    if (esito is EsitoBonus.Concesso) {
                        postino.leggiPatto()?.let { PattoLocale(context).salva(it) }
                    }
                    esito
                }
            }
        }
    }
}
