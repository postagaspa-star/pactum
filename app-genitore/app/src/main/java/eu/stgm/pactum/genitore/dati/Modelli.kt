package eu.stgm.pactum.genitore.dati

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Le risposte del postino per il genitore (docs/contratto-api.md, sezione
 * "Endpoint del genitore" — la fonte di verità del protocollo). I timestamp
 * `ts_server` sono ISO 8601 UTC e fanno fede; si mostrano nel fuso del
 * telefono, ma i GIORNI del patto si contano nel fuso del patto (v.
 * FUSO_PATTO in ui/LogicaPatto.kt). I campi sconosciuti si ignorano
 * (tolleranza evolutiva).
 */
@Serializable
data class Finestra(
    val regole: List<RegolaFinestra> = emptyList(),
    @SerialName("sforamenti_recenti") val sforamentiRecenti: List<EventoFinestra> = emptyList(),
    @SerialName("manomissioni_recenti") val manomissioniRecenti: List<EventoFinestra> = emptyList(),
    @SerialName("storico_modifiche") val storicoModifiche: List<ModificaStorico> = emptyList(),
    val bonus: StatoBonus,
    @SerialName("bonus_giornalieri") val bonusGiornalieri: List<BonusGiorno> = emptyList(),
    @SerialName("stato_silenzio") val statoSilenzio: StatoSilenzio,
    @SerialName("uso_recente") val usoRecente: List<UsoGiorno> = emptyList(),
    // Medie settimanale/mensile del tempo d'uso (contratto-api.md, GET /api/finestra).
    // Nullable per tolleranza: un server più vecchio non manda il campo → l'app
    // nasconde la riga invece di crashare.
    val medie: Medie? = null,
    // I siti visitati, 8 giorni (contratto v2.3). Nullable per lo STESSO motivo
    // delle medie, ma qui la distinzione pesa di più: `null` = "il server non sa
    // niente di siti" (versione vecchia) → la sezione si nasconde del tutto,
    // mentre una lista con `totale_domini: null` dentro = "il server sa, ma per
    // quel giorno non è arrivata nessuna fotografia". Due silenzi diversi.
    @SerialName("siti_recenti") val sitiRecenti: List<SitiGiorno>? = null,
    // (v2.4) La striscia AGGREGATA degli 8 giorni, dal più vecchio a oggi: la
    // calcola il server con la stessa funzione che la manda al figlio, quindi le
    // due app mostrano la stessa striscia per costruzione. Vuota = server vecchio:
    // la scheda del patto mostra solo quello che può, senza inventarla.
    val striscia: List<QuadrettoSemaforo> = emptyList(),
    // (v2.4) La riga sotto la striscia, contata dal SERVER nel fuso del patto e
    // su tutto il registro (non sui 20 eventi che arrivano all'app): è la stessa
    // che vede il figlio. `null` = server vecchio: l'app ripiega sul suo conto.
    val riepilogo: RiepilogoFinestra? = null,
    // (v2.4) Il genitore ha già mandato il segno oggi (fuso del patto). Assente
    // su un server vecchio: false, e sarà il server a dire di no se serve.
    @SerialName("segno_oggi") val segnoOggi: Boolean = false,
)

/** (v2.4) Il `riepilogo` della finestra: giorni fuori regola e interruzioni negli 8 giorni. */
@Serializable
data class RiepilogoFinestra(
    @SerialName("giorni_fuori_regola") val giorniFuoriRegola: Int = 0,
    val interruzioni: Int = 0,
)

/**
 * I codici `errore` dei 409 del contratto che l'app sa dire a parole. FastAPI li
 * manda dentro `detail` (`{"detail": {"errore": "…"}}`): li estrae PostinoClient.
 */
object CodiciErrore {
    const val PROPOSTA_GIA_PENDENTE = "proposta_gia_pendente"
    const val REGOLA_NON_VALIDA = "regola_non_valida"
    const val DICHIARAZIONE_NON_IN_ATTESA = "dichiarazione_non_in_attesa"
    const val SEGNO_GIA_MANDATO = "segno_gia_mandato"
}

/** Risposta di POST /api/segno (v2.4): il riconoscimento a testo fisso è partito. */
@Serializable
data class SegnoMandato(
    val mandato: Boolean = true,
    @SerialName("ts_server") val tsServer: String? = null,
)

// --- Siti visitati (v2.3) ----------------------------------------------------
// Il genitore VEDE quali siti, mai cosa ci fa dentro: solo il dominio
// registrabile e quante volte è stato richiesto nel giorno. Nessun URL, nessun
// contenuto, nessuna ricerca, nessun orario — e nessun blocco: non esiste (e non
// esisterà) un endpoint per bloccare un sito (contratto-api.md, sezione "Siti
// visitati — limiti e patto etico").
//
// Le stesse 8 voci arrivano IDENTICHE al figlio da GET /api/patto: è il
// principio della tavola rotonda, niente esiste solo dalla parte del genitore.

@Serializable
data class SitiGiorno(
    val giorno: String,
    // `null` = nessuna fotografia per quel giorno. MAI uno zero finto: "non lo
    // so" e "zero siti" sono due notizie diverse. Può essere MAGGIORE della
    // lunghezza di `domini` se la fotografia era tagliata ai primi 200: in quel
    // caso la differenza si mostra, non si finge.
    @SerialName("totale_domini") val totaleDomini: Int? = null,
    // `true` quando il telefono usava DNS cifrato (DoH/DoT) e i domini non erano
    // visibili. È un DATO, non un errore: il registro dichiara di non aver visto
    // invece di raccontare una giornata a zero traffico. Quindi niente rosso.
    @SerialName("dns_cifrato") val dnsCifrato: Boolean = false,
    @SerialName("aggiornato_ts") val aggiornatoTs: String? = null,
    val domini: List<SitoVisitato> = emptyList(),
)

/** Un sito del giorno: il dominio registrabile e quante volte è stato richiesto. */
@Serializable
data class SitoVisitato(
    val dominio: String,
    val visite: Int = 0,
)

// --- Medie (settimana / mese) ------------------------------------------------
// Media di `totale_minuti` sui SOLI giorni con fotografia nella finestra (7 e 30
// giorni, fuso del patto). Ogni sotto-oggetto è `null` se in quella finestra non
// c'è nessun giorno con dati: MAI uno zero finto (contratto-api.md).

@Serializable
data class Medie(
    val settimana: MediaPeriodo? = null,
    val mese: MediaPeriodo? = null,
)

@Serializable
data class MediaPeriodo(
    // Media dei minuti, già arrotondata a intero dal server; `giorni` = quanti
    // giorni della finestra avevano una fotografia (>= 1 quando il sotto-oggetto
    // esiste). I default coprono un JSON parziale senza far saltare la decodifica.
    val minuti: Int = 0,
    val giorni: Int = 0,
)

// --- Uso recente (v2.2) ------------------------------------------------------
// I tempi d'uso giornalieri di TUTTE le app, dalla fotografia `uso_giornaliero`
// vigente: 8 voci dal più vecchio a oggi. Un giorno senza fotografia ha
// `totale_minuti: null` e liste vuote — MAI uno zero finto: "nessun dato
// ricevuto" è un'informazione (contratto-api.md, sezione uso_recente).

@Serializable
data class UsoGiorno(
    val giorno: String,
    @SerialName("totale_minuti") val totaleMinuti: Int? = null,
    @SerialName("aggiornato_ts") val aggiornatoTs: String? = null,
    val app: List<UsoApp> = emptyList(),
    val categorie: List<UsoCategoria> = emptyList(),
)

/** Una app della fotografia: `nome` risolto sul telefono del figlio (fallback: il pacchetto). */
@Serializable
data class UsoApp(
    val chiave: String,
    val nome: String? = null,
    val minuti: Int = 0,
    // `limite`/`regolaId` presenti SOLO dove una regola limite_tempo attiva
    // combacia esattamente con la chiave (limite base, senza i bonus del giorno).
    val limite: Int? = null,
    @SerialName("regola_id") val regolaId: Long? = null,
    // (v2.4) I minuti bonus concessi QUEL giorno su QUELLA regola: il limite del
    // giorno è `limite + bonus`, come per il figlio. Assente (server vecchio) = 0.
    val bonus: Int = 0,
)

@Serializable
data class UsoCategoria(
    val chiave: String,
    val minuti: Int = 0,
    val limite: Int? = null,
    @SerialName("regola_id") val regolaId: Long? = null,
    // (v2.4) Come in UsoApp: i minuti bonus del giorno su questa regola.
    val bonus: Int = 0,
)

@Serializable
data class RegolaFinestra(
    val id: Long,
    val tipo: String,
    val parametri: JsonObject = JsonObject(emptyMap()),
    // Il nome leggibile dell'app per le limite_tempo su un pacchetto ("TikTok"
    // invece di com.zhiliaoapp.musically). Assente per le categorie e sui server
    // vecchi: allora si ripiega sulla chiave.
    val nome: String? = null,
    val attiva: Boolean = true,
    @SerialName("creata_ts") val creataTs: String = "",
    @SerialName("ultima_modifica_ts") val ultimaModificaTs: String = "",
    @SerialName("allentabile_dal") val allentabileDal: String? = null,
    val semaforo: List<QuadrettoSemaforo> = emptyList(),
)

object TipiRegola {
    const val LIMITE_TEMPO = "limite_tempo"
    const val FASCIA_ORARIA = "fascia_oraria"
    const val VITA_REALE = "vita_reale"
}

/** Un giorno del semaforo: stato ∈ verde / rosso / grigio (niente giallo). */
@Serializable
data class QuadrettoSemaforo(val data: String, val stato: String)

object StatiSemaforo {
    const val VERDE = "verde"
    const val ROSSO = "rosso"
    const val GRIGIO = "grigio"
}

@Serializable
data class EventoFinestra(
    val id: String,
    val tipo: String,
    val dettagli: JsonObject = JsonObject(emptyMap()),
    @SerialName("ts_device") val tsDevice: Long? = null,
    @SerialName("ts_server") val tsServer: String,
)

@Serializable
data class ModificaStorico(
    val id: Long,
    @SerialName("regola_id") val regolaId: Long,
    val azione: String,
    val direzione: String? = null,
    val prima: JsonObject? = null,
    val dopo: JsonObject? = null,
    val concordata: Boolean = false,
    @SerialName("ts_server") val tsServer: String,
)

@Serializable
data class ContatoreBonus(val usati: Int, val tetto: Int, val residui: Int)

@Serializable
data class StatoBonus(val giorno: ContatoreBonus, val settimana: ContatoreBonus)

@Serializable
data class BonusGiorno(val giorno: String, val minuti: Int)

@Serializable
data class StatoSilenzio(
    @SerialName("ultimo_battito") val ultimoBattito: String? = null,
    val silente: Boolean,
)

@Serializable
data class Notifica(
    val id: Long,
    val tipo: String,
    val messaggio: String,
    val payload: JsonObject = JsonObject(emptyMap()),
    @SerialName("ts_server") val tsServer: String,
)

@Serializable
data class PaccoNotifiche(val notifiche: List<Notifica> = emptyList())

// --- Proposte (tappa 5) -----------------------------------------------------
// Il genitore propone una modifica; il server calcola il `confronto` testuale e
// la `direzione`, e il figlio accetta/rifiuta. La proposta accettata applica da
// sola la modifica lato server (contratto-api.md, sezione Proposte).

/** La proposta come la restituisce POST /api/proposte e GET /api/proposte. */
@Serializable
data class Proposta(
    val id: Long,
    @SerialName("regola_id") val regolaId: Long,
    @SerialName("parametri_proposti") val parametriProposti: JsonObject = JsonObject(emptyMap()),
    val motivazione: String? = null,
    val confronto: String = "",
    val direzione: String = "",
    val stato: String,
    val usata: Boolean = false,
    @SerialName("ts_server") val tsServer: String = "",
    val risposta: RispostaProposta? = null,
)

/** La risposta del figlio a una proposta (presente quando ha risposto). */
@Serializable
data class RispostaProposta(
    val esito: String,
    val motivazione: String? = null,
    @SerialName("ts_server") val tsServer: String = "",
)

@Serializable
data class PaccoProposte(val proposte: List<Proposta> = emptyList())

/** Corpo di POST /api/proposte. Per l'eliminazione, `parametriProposti` è il marcatore. */
@Serializable
data class NuovaProposta(
    @SerialName("regola_id") val regolaId: Long,
    @SerialName("parametri_proposti") val parametriProposti: JsonObject,
    val motivazione: String? = null,
)

object StatiProposta {
    const val PENDENTE = "pendente"
    const val ACCETTATA = "accettata"
    const val RIFIUTATA = "rifiutata"
    const val ANNULLATA = "annullata"
}

object DirezioniProposta {
    const val ALLENTA = "allenta"
    const val STRINGE = "stringe"
    const val ELIMINA = "elimina"
}

object EsitiRisposta {
    const val ACCETTA = "accetta"
    const val RIFIUTA = "rifiuta"
}

// --- Dichiarazioni e verdetti (tappa 5) -------------------------------------
// Il figlio dichiara com'è andata una regola di vita reale; il successo resta
// `in_attesa` finché il genitore conferma / conferma per conto dell'arbitro /
// ribalta (contratto-api.md, sezione Dichiarazioni).

@Serializable
data class Dichiarazione(
    val id: Long,
    @SerialName("regola_id") val regolaId: Long,
    val giorno: String = "",
    val esito: String,
    val nota: String? = null,
    val stato: String,
    @SerialName("ts_server") val tsServer: String = "",
    val verdetto: Verdetto? = null,
)

/** Il verdetto del genitore su una dichiarazione (presente quando emesso). */
@Serializable
data class Verdetto(
    val verdetto: String,
    val nota: String? = null,
    // (v2.1) La frase autoritativa del registro, congelata dal server al momento
    // del verdetto (es. "confermato dal genitore per conto di Nonna"): l'app la
    // mostra così com'è, senza ricostruirla — cita l'arbitro di allora.
    val registro: String? = null,
    @SerialName("ts_server") val tsServer: String = "",
)

@Serializable
data class PaccoDichiarazioni(val dichiarazioni: List<Dichiarazione> = emptyList())

/** Corpo di POST /api/dichiarazioni/{id}/verdetto. */
@Serializable
data class CorpoVerdetto(
    val verdetto: String,
    val nota: String? = null,
)

object StatiDichiarazione {
    const val REGISTRATA = "registrata"
    const val IN_ATTESA = "in_attesa"
    const val CONFERMATA = "confermata"
    const val CONFERMATA_PER_CONTO = "confermata_per_conto"
    const val RIBALTATA = "ribaltata"
}

object EsitiDichiarazione {
    const val SUCCESSO = "successo"
    const val FALLIMENTO = "fallimento"
}

object TipiVerdetto {
    const val CONFERMA = "conferma"
    const val CONFERMA_PER_CONTO = "conferma_per_conto"
    const val RIBALTA = "ribalta"
}

// --- Versioni e auto-aggiornamento (tappa 6) --------------------------------
// GET /api/versione (senza auth): l'ultima versione disponibile di ciascuna app.
// L'app confronta `versione_code` col proprio versionCode e, se il server è più
// avanti, scarica `url` (relativo al base del server) e lancia PackageInstaller
// (contratto-api.md, sezione "GET /api/versione").

@Serializable
data class InfoVersioni(
    val figlio: InfoApp? = null,
    val genitore: InfoApp? = null,
)

@Serializable
data class InfoApp(
    @SerialName("versione_code") val versioneCode: Int,
    @SerialName("versione_nome") val versioneNome: String = "",
    val url: String = "",
    val note: String? = null,
)
