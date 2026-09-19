package eu.stgm.pactum.figlio.bonus

import android.content.Context
import eu.stgm.pactum.figlio.dati.DettaglioErrore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Un bonus che il ragazzo si è appena dato e che non è ancora arrivato al
 * server. Il contratto vuole il `motivo` DENTRO il POST /api/bonus e non
 * permette di aggiungerlo dopo, quindi il bonus aspetta qui il tempo della
 * snackbar ("Aggiungi perché"). Vive su disco dal primo tocco: se l'app va in
 * background, la schermata sparisce o il processo muore, il bonus resta.
 */
@Serializable
data class BonusInSospeso(
    val id: String,
    @SerialName("regola_id") val regolaId: Long,
    val minuti: Int,
    /** Il giorno del patto in cui se l'è dato: un bonus vale per quel giorno. */
    val giorno: String,
    @SerialName("creato_il") val creatoIl: Long,
    val motivo: String? = null,
    /** Sta scrivendo il perché: il timer della snackbar non lo manda. */
    @SerialName("in_scrittura") val inScrittura: Boolean = false,
    /** Un POST è partito senza risposta certa: prima di rimandarlo si guarda il server. */
    val inviato: Boolean = false,
    /** I minuti bonus su questa regola letti dal server subito prima del POST. */
    val base: Int? = null,
    /**
     * Il giorno del SERVER in cui è stata letta [base] (l'ultima data della
     * striscia). Il contatore del server riparte a mezzanotte: se il giorno è
     * cambiato, [base] non si può più confrontare e il bonus non si rimanda.
     */
    @SerialName("giorno_base") val giornoBase: String? = null,
)

/** Com'è finita una consegna: quello che la schermata deve dire. */
sealed interface EsitoBonus {
    val minuti: Int

    data class Concesso(override val minuti: Int) : EsitoBonus
    data class TettoSuperato(
        override val minuti: Int,
        val residuoGiorno: Int,
        val residuoSettimana: Int,
    ) : EsitoBonus
    data class RegolaNonValida(override val minuti: Int) : EsitoBonus
    data class Rifiutato(override val minuti: Int) : EsitoBonus

    /** Niente rete (o risposta persa): il bonus resta su disco e riparte da solo. */
    data class SenzaRete(override val minuti: Int) : EsitoBonus

    /** Era di un giorno ormai passato: mandarlo oggi darebbe minuti mai chiesti. */
    data class Scaduto(override val minuti: Int) : EsitoBonus

    /**
     * Era già partito, ma la conferma non è arrivata e nel frattempo il giorno
     * è cambiato: non si rimanda. Se è arrivato vale per il suo giorno, se non
     * è arrivato non vale: in tutti e due i casi non vale per oggi.
     */
    data class GiornoCambiato(override val minuti: Int) : EsitoBonus
}

/**
 * Le regole della consegna, pure (niente Android né rete): quando un bonus è
 * pronto a partire da solo, cosa fare quando si riprende un invio rimasto a
 * metà, come leggere la risposta del server.
 */
object RegoleBonus {

    /** Quanto resta aperta la snackbar "Aggiungi perché". */
    const val FINESTRA_MS = 10_000L

    /** Un perché lasciato a metà non trattiene il bonus per sempre. */
    const val SCRITTURA_MASSIMA_MS = 10 * 60_000L

    /** Pronto a partire senza un gesto del ragazzo: finestra chiusa, o perché abbandonato. */
    fun pronto(bonus: BonusInSospeso, adesso: Long): Boolean {
        val eta = adesso - bonus.creatoIl
        return if (bonus.inScrittura) eta >= SCRITTURA_MASSIMA_MS else eta >= FINESTRA_MS
    }

    sealed interface Passo {
        /** Di un altro giorno, mai partito: si butta, non si manda. */
        data object Scarta : Passo

        /**
         * Già partito senza risposta, e il giorno è cambiato: si butta senza
         * rimandarlo, e senza dire che "non è partito in tempo".
         */
        data object ScartaGiaPartito : Passo

        /** Un invio precedente era arrivato: il server lo conta già, non si rimanda. */
        data object GiaArrivato : Passo

        data object Manda : Passo
    }

    /**
     * Cosa fare adesso, sapendo quanti minuti bonus il server conta oggi su
     * quella regola. È il cuore del "mai due volte": un invio con esito incerto
     * si considera arrivato se il contatore del server è cresciuto almeno dei
     * suoi minuti rispetto a prima. Funziona perché i bonus partono solo da
     * qui, uno alla volta: nessun altro può far crescere quel contatore.
     *
     * Il confronto vale solo nello stesso giorno del server: a mezzanotte il
     * contatore riparte da zero, e un bonus arrivato ieri sembrerebbe mai
     * arrivato. Per questo [giornoServer] (l'ultima data della striscia) si
     * confronta col giorno in cui è stata letta la base: se è cambiato, non si
     * rimanda. [oggi] è il giorno del patto secondo l'orologio del telefono,
     * lo stesso con cui è stato scritto `bonus.giorno`.
     */
    fun passo(
        bonus: BonusInSospeso,
        oggi: String,
        minutiSulServer: Int,
        giornoServer: String? = null,
    ): Passo = when {
        bonus.inviato && bonus.giornoBase != null && giornoServer != null &&
            giornoServer != bonus.giornoBase -> Passo.ScartaGiaPartito
        bonus.inviato && bonus.base != null && minutiSulServer >= bonus.base + bonus.minuti ->
            Passo.GiaArrivato
        bonus.giorno != oggi -> if (bonus.inviato) Passo.ScartaGiaPartito else Passo.Scarta
        else -> Passo.Manda
    }

    /**
     * I minuti bonus di oggi per regola come li deve contare la sentinella:
     * quelli del server più il bonus in sospeso, se è di [oggi] (giorno del
     * patto) e sta nel [residuo] noto (il più piccolo dei due tetti; null =
     * non noto, e allora conta). Senza, chi si dà +15 a limite già superato
     * verrebbe segnato fuori regola nei secondi in cui il bonus aspetta la
     * snackbar o la rete. Se poi il server lo rifiuta, il cassetto si svuota e
     * la valutazione successiva registra lo sforamento come sempre.
     *
     * Un bonus già partito e già contato dal server (contatore cresciuto
     * almeno dei suoi minuti rispetto alla base) non si somma due volte.
     */
    fun bonusConSospeso(
        bonusOggi: Map<String, Int>,
        sospeso: BonusInSospeso?,
        oggi: String,
        residuo: Int?,
    ): Map<String, Int> {
        if (sospeso == null || sospeso.giorno != oggi) return bonusOggi
        val chiave = sospeso.regolaId.toString()
        val sulServer = bonusOggi[chiave] ?: 0
        val giaContato = sospeso.inviato && sospeso.base != null &&
            sulServer >= sospeso.base + sospeso.minuti
        if (giaContato) return bonusOggi
        if (residuo != null && sospeso.minuti > residuo) return bonusOggi
        return bonusOggi + (chiave to sulServer + sospeso.minuti)
    }

    /**
     * La risposta al POST. Solo un 4xx è un "no" sicuro; rete assente (codice 0)
     * o 5xx lasciano il dubbio che sia arrivato, quindi il bonus resta in
     * sospeso e si verifica prima di rimandarlo.
     */
    fun esitoRisposta(
        minuti: Int,
        ok: Boolean,
        codice: Int,
        dettaglio: DettaglioErrore?,
    ): EsitoBonus = when {
        ok -> EsitoBonus.Concesso(minuti)
        codice in 400..499 -> when (dettaglio?.errore) {
            "tetto_superato" -> EsitoBonus.TettoSuperato(
                minuti = minuti,
                residuoGiorno = dettaglio.residuoGiorno ?: 0,
                residuoSettimana = dettaglio.residuoSettimana ?: 0,
            )
            "regola_non_valida" -> EsitoBonus.RegolaNonValida(minuti)
            else -> EsitoBonus.Rifiutato(minuti)
        }
        else -> EsitoBonus.SenzaRete(minuti)
    }
}

/**
 * Il cassetto su disco del bonus in sospeso: uno solo alla volta. Un file JSON
 * in filesDir con scrittura atomica (tmp + rename), come PattoLocale.
 */
class CassettaBonus(context: Context) {

    private val file = File(context.filesDir, "bonus_in_sospeso.json")

    suspend fun leggi(): BonusInSospeso? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!file.exists()) return@withLock null
            runCatching { json.decodeFromString(BonusInSospeso.serializer(), file.readText()) }
                .getOrNull()
        }
    }

    suspend fun scrivi(bonus: BonusInSospeso) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(json.encodeToString(BonusInSospeso.serializer(), bonus))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }

    suspend fun svuota() = withContext(Dispatchers.IO) {
        mutex.withLock { file.delete() }
    }

    private companion object {
        val mutex = Mutex()
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
