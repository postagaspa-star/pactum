package eu.stgm.pactum.genitore.dati

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- La Sessione Studio (0.18, contratto v4.0, parte C) -------------------------------
// Lo Studio è un periodo in cui, su telefoni e computer del figlio con Pactum dalla
// 0.18, restano usabili solo le app, i programmi e i siti di una lista approvata.
// Parte da solo (di base dal lunedì al venerdì alle 15:00), si chiude solo dal
// telefono del figlio dopo l'orario minimo E dopo un minimo di minuti di attività
// cronometrata col timer (minuti DICHIARATI dal figlio, non attività verificata), o
// da un genitore con un motivo, o da solo a mezzanotte ("non chiuso"). La
// configurazione la propone il figlio e la approva un genitore. Durante lo Studio
// il blocco dei lavori di casa aspetta.
//
// Tutti i campi hanno un valore di riserva: un server che ne manda meno (o di più)
// non fa sembrare "server irraggiungibile" una risposta buona.

/** Lo `studio` di GET /api/finestra (e del patto): configurazione, Studio in corso, prossime partenze. */
@Serializable
data class StudioPatto(
    val config: ConfigStudio? = null,
    @SerialName("in_corso") val inCorso: StudioSvolto? = null,
    @SerialName("prossime_partenze") val prossimePartenze: List<PartenzaStudio> = emptyList(),
)

/**
 * La configurazione dello Studio di un figlio. [stato] ∈ `nessuna`, `in_attesa`,
 * `approvata`, `rifiutata`. [approvata] = quella in vigore (null finché nessuno
 * approva); [inAttesa] = quella proposta, completa (null se non c'è). [versione]
 * cresce a ogni proposta e decisione: la risposta del genitore porta quella vista.
 */
@Serializable
data class ConfigStudio(
    val stato: String = StatiConfigStudio.NESSUNA,
    val versione: Int? = null,
    val approvata: ContenutoStudio? = null,
    @SerialName("in_attesa") val inAttesa: ContenutoStudio? = null,
    /** Il perché dell'ultimo rifiuto. */
    val motivazione: String? = null,
)

/**
 * Il contenuto di una configurazione: giorni, orari, minimo di minuti e le due
 * liste. Gli stessi campi servono alla configurazione approvata (con [orariDal],
 * [approvataTs], [decisaDa]), a quella in attesa (con [richiestaTs] e [da], il
 * dispositivo che l'ha proposta) e alle versioni approvate (con [versione]).
 */
@Serializable
data class ContenutoStudio(
    /** `lun mar mer gio ven sab dom`. */
    val giorni: List<String> = emptyList(),
    /** "HH:MM" nel fuso del patto. */
    val inizio: String? = null,
    @SerialName("chiusura_minima") val chiusuraMinima: String? = null,
    @SerialName("minuti_minimi") val minutiMinimi: Int? = null,
    val telefono: ListaTelefonoStudio? = null,
    val computer: ListaComputerStudio? = null,
    /** Da quale giorno valgono giorni, orari e minimo ("2026-10-08"). */
    @SerialName("orari_dal") val orariDal: String? = null,
    @SerialName("approvata_ts") val approvataTs: String? = null,
    /** Chi l'ha approvata; null = "decisa dalla famiglia all'aggiornamento". */
    @SerialName("decisa_da") val decisaDa: RiferimentoGenitore? = null,
    @SerialName("richiesta_ts") val richiestaTs: String? = null,
    /** Il dispositivo che l'ha proposta per ultimo. */
    val da: RiferimentoDispositivo? = null,
    /** Solo nelle versioni approvate (GET /api/studio/versioni). */
    val versione: Int? = null,
)

/** La lista del telefono: pacchetti Android e `gruppo:apk`, con le etichette. */
@Serializable
data class ListaTelefonoStudio(
    val app: List<String> = emptyList(),
    val nomi: Map<String, String> = emptyMap(),
)

/**
 * La lista del computer: `exe:<nome>` e `sito:<dominio>`, con le etichette e, per
 * i programmi firmati, chi li firma ("Microsoft Corporation").
 */
@Serializable
data class ListaComputerStudio(
    val programmi: List<String> = emptyList(),
    val nomi: Map<String, String> = emptyMap(),
    val firme: Map<String, String> = emptyMap(),
)

/** Una partenza automatica: il giorno, quando parte, da quando si può chiudere, il minimo. */
@Serializable
data class PartenzaStudio(
    val giorno: String? = null,
    @SerialName("inizio_ts") val inizioTs: String? = null,
    @SerialName("chiudibile_dal") val chiudibileDal: String? = null,
    @SerialName("minuti_minimi") val minutiMinimi: Int? = null,
)

/** Chi ha chiuso uno Studio o l'ha avviato: un dispositivo (con [tipo]) o un genitore (senza). */
@Serializable
data class ChiStudio(
    val id: Long? = null,
    val nome: String = "",
    val tipo: String? = null,
)

/** La sessione normale chiusa all'inizio dello Studio. */
@Serializable
data class SessioneChiusaDalloStudio(
    val id: Long? = null,
    val nome: String = "",
)

/** Uno Studio fatto, o in corso (contratto v4.0, "Lo Studio svolto"). */
@Serializable
data class StudioSvolto(
    val id: Long,
    /** `automatica` o `manuale`. */
    val origine: String? = null,
    val giorno: String? = null,
    @SerialName("inizio_ts") val inizioTs: String? = null,
    /** Il dispositivo che l'ha avviato a mano (null per l'automatico). */
    @SerialName("avviato_da") val avviatoDa: ChiStudio? = null,
    val partenze: List<PartenzaStudio> = emptyList(),
    @SerialName("conta_dal") val contaDal: String? = null,
    /** Da quando si può chiudere; null = a mano, senza vincolo d'orario. */
    @SerialName("chiudibile_dal") val chiudibileDal: String? = null,
    @SerialName("minuti_minimi") val minutiMinimi: Int? = null,
    /** Minuti col timer acceso DICHIARATI dal figlio, fino a adesso. */
    @SerialName("minuti_attivita") val minutiAttivita: Int? = null,
    /** I minuti congelati alla chiusura (null finché è aperto). */
    @SerialName("minuti_alla_chiusura") val minutiAllaChiusura: Int? = null,
    val chiudibile: Boolean = false,
    val tratti: List<TrattoStudio> = emptyList(),
    @SerialName("sessione_chiusa") val sessioneChiusa: SessioneChiusaDalloStudio? = null,
    @SerialName("fine_ts") val fineTs: String? = null,
    /** null (in corso), `figlio`, `genitore`, `non_chiuso`. */
    val chiusura: String? = null,
    @SerialName("chiusa_da") val chiusaDa: ChiStudio? = null,
    /** Il testo del figlio alla chiusura ("Cosa hai fatto?"). */
    val dichiarazione: String? = null,
    @SerialName("dichiarazione_ts") val dichiarazioneTs: String? = null,
    /** Il motivo del genitore che l'ha chiuso. */
    val motivo: String? = null,
    @SerialName("in_corso") val inCorso: Boolean = false,
)

/** Un tratto di attività cronometrato col timer del telefono. */
@Serializable
data class TrattoStudio(
    val id: String = "",
    @SerialName("dispositivo_id") val dispositivoId: Long? = null,
    /** `compiti`, `lavori_di_casa`, `altro`. */
    val tipo: String = "",
    /** La parola del figlio ("allenamento"); obbligatoria per `altro`. */
    val parola: String? = null,
    @SerialName("faccenda_id") val faccendaId: Long? = null,
    val inizio: Long? = null,
    val fine: Long? = null,
    val secondi: Long? = null,
    @SerialName("secondi_contati") val secondiContati: Long? = null,
    val minuti: Int? = null,
    /** `in_corso`, `finito`, `interrotto`. */
    val esito: String? = null,
    val conta: Boolean? = null,
)

/** GET /api/studio: configurazione, in corso, prossime partenze, gli Studi delle ultime 48 ore. */
@Serializable
data class PaccoStudio(
    val config: ConfigStudio? = null,
    @SerialName("in_corso") val inCorso: StudioSvolto? = null,
    @SerialName("prossime_partenze") val prossimePartenze: List<PartenzaStudio> = emptyList(),
    val recenti: List<StudioSvolto> = emptyList(),
)

/** GET /api/studio/versioni: le configurazioni approvate, dalla più recente. */
@Serializable
data class PaccoVersioniStudio(val versioni: List<ContenutoStudio> = emptyList())

/** GET /api/studio/svolte: 20 per volta, dal più recente; [altre] = ce ne sono di più vecchi. */
@Serializable
data class PaccoSvolteStudio(
    val svolte: List<StudioSvolto> = emptyList(),
    val altre: Boolean = false,
)

/**
 * Corpo di POST /api/studio/config/risposta: [esito] `approva` o `rifiuta`, la
 * [versione] che il genitore ha sullo schermo (obbligatoria), il perché del no
 * (facoltativo) e il figlio. I null non si scrivono.
 */
@Serializable
data class CorpoRispostaStudio(
    val esito: String,
    val versione: Int,
    val motivazione: String? = null,
    @SerialName("figlio_id") val figlioId: Long? = null,
)

/** Corpo di POST /api/studio/{id}/chiudi col token del genitore: il motivo, obbligatorio. */
@Serializable
data class CorpoChiusuraStudio(
    val motivo: String,
    @SerialName("figlio_id") val figlioId: Long? = null,
)

object StatiConfigStudio {
    const val NESSUNA = "nessuna"
    const val IN_ATTESA = "in_attesa"
    const val APPROVATA = "approvata"
    const val RIFIUTATA = "rifiutata"
}

object ChiusureStudio {
    const val FIGLIO = "figlio"
    const val GENITORE = "genitore"
    const val NON_CHIUSO = "non_chiuso"
}

object OriginiStudio {
    const val AUTOMATICA = "automatica"
    const val MANUALE = "manuale"
}

object TipiTratto {
    const val COMPITI = "compiti"
    const val LAVORI_DI_CASA = "lavori_di_casa"
    const val ALTRO = "altro"
}

object EsitiTratto {
    const val IN_CORSO = "in_corso"
    const val FINITO = "finito"
    const val INTERROTTO = "interrotto"
}

/** Il motivo di chi chiude lo Studio: da 3 a 300 caratteri dopo aver tolto gli spazi ai bordi. */
const val MINIMO_MOTIVO_STUDIO = 3
const val MASSIMO_MOTIVO_STUDIO = 300

/** Il perché di un "non approvo" della configurazione (come quello di una sessione). */
const val MASSIMO_MOTIVAZIONE_STUDIO = 500
