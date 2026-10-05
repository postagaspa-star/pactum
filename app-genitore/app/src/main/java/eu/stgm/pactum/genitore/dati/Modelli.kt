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
    // (v3) bonus e silenzio di primo livello valgono per il PRIMO dispositivo del
    // figlio: un figlio appena creato, senza dispositivi, può non averli. Nullable
    // perché un campo mancante non faccia sembrare "server irraggiungibile" una
    // finestra buona.
    val bonus: StatoBonus? = null,
    @SerialName("bonus_giornalieri") val bonusGiornalieri: List<BonusGiorno> = emptyList(),
    @SerialName("stato_silenzio") val statoSilenzio: StatoSilenzio? = null,
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
    // (v3) Tutto quello che è PER DISPOSITIVO (tempi, siti, bonus, silenzio,
    // striscia del dispositivo), revocati compresi. Vuota = server 0.7: valgono i
    // campi di primo livello, come prima.
    val dispositivi: List<DispositivoFinestra> = emptyList(),
    // (0.10) Le proposte in attesa del figlio, di tutti e due gli autori, dalla
    // più recente (contratto v3.4): quelle con autore "figlio" aspettano il
    // genitore, e la Panoramica le mette in cima. Assente (server più vecchio
    // della v3.4) = nessuna.
    @SerialName("proposte_pendenti") val propostePendenti: List<Proposta> = emptyList(),
    // (0.11) Le sessioni del figlio (contratto v3.5): quelle non eliminate di tutti
    // i suoi telefoni, quante aspettano il genitore (sessioni nuove più cambi) e
    // quelle fatte negli 8 giorni, dalla più recente. Assenti (server più vecchio
    // della v3.5) = nessuna, e la Panoramica non ne parla.
    val sessioni: List<Sessione> = emptyList(),
    @SerialName("sessioni_da_approvare") val sessioniDaApprovare: Int = 0,
    @SerialName("sessioni_svolte") val sessioniSvolte: List<SessioneSvolta> = emptyList(),
    // (0.13) Le faccende del figlio (contratto v3.6): tutte le da fare, più le
    // fatte e le annullate degli ultimi 30 giorni. null (campo assente) = server
    // più vecchio della v3.6: per le faccende serve aggiornarlo. Una lista vuota
    // invece è "nessuna faccenda".
    val faccende: List<Faccenda>? = null,
    // (0.13) Il blocco delle faccende come lo vede il server; null = server vecchio.
    val blocco: BloccoFaccende? = null,
)

// --- v3: famiglia, figli e dispositivi ----------------------------------------
// La famiglia ha uno o più figli; ogni figlio ha uno o più dispositivi (telefono
// o computer) con regole, tempi, bonus e registro separati. Vita reale e
// striscia restano del figlio (contratto-api.md, "v3 — Famiglia, figli e
// dispositivi"). Un server 0.7 non conosce GET /api/famiglia (404): l'app
// resta com'era, un figlio e un dispositivo.

object TipiDispositivo {
    const val TELEFONO = "telefono"
    const val COMPUTER = "computer"
}

/**
 * GET /api/famiglia: i figli in ordine di id, ciascuno coi suoi dispositivi.
 * (0.13) Dalla v3.6 anche chi sei tu ([io]) e i genitori (con `revocato`); su
 * un server più vecchio non ci sono (null, lista vuota): un genitore solo.
 */
@Serializable
data class Famiglia(
    val figli: List<Figlio> = emptyList(),
    val io: RiferimentoGenitore? = null,
    val genitori: List<GenitoreFamiglia> = emptyList(),
)

@Serializable
data class Figlio(
    val id: Long,
    val nome: String = "",
    val striscia: List<QuadrettoSemaforo> = emptyList(),
    val riepilogo: RiepilogoFinestra? = null,
    @SerialName("notifiche_non_lette") val notificheNonLette: Int = 0,
    val dispositivi: List<Dispositivo> = emptyList(),
    // (0.10) Quante proposte di questo figlio aspettano il genitore (pendenti con
    // autore "figlio", contratto v3.4): il numero accanto al suo nome nella scelta
    // in cima. Assente (server più vecchio) = 0.
    @SerialName("proposte_da_decidere") val proposteDaDecidere: Int = 0,
    // (0.11) Quante sue sessioni aspettano il genitore (nuove più cambi, contratto
    // v3.5): si sommano alle proposte nel numero accanto al nome. Assente = 0.
    @SerialName("sessioni_da_approvare") val sessioniDaApprovare: Int = 0,
    // (0.13) Quante faccende ha da fare, e se il blocco è attivo adesso (contratto
    // v3.6). Assenti (server più vecchio) = 0 e false.
    @SerialName("faccende_da_fare") val faccendeDaFare: Int = 0,
    @SerialName("blocco_attivo") val bloccoAttivo: Boolean = false,
)

/** Un dispositivo come lo racconta GET /api/famiglia (revocati compresi). */
@Serializable
data class Dispositivo(
    val id: Long,
    val nome: String = "",
    val tipo: String = TipiDispositivo.TELEFONO,
    val abbinato: Boolean = true,
    val revocato: Boolean = false,
    @SerialName("versione_app") val versioneApp: String? = null,
    @SerialName("stato_silenzio") val statoSilenzio: StatoSilenzio? = null,
)

/** Il dispositivo come lo allegano regole e codici: solo chi è. */
@Serializable
data class RiferimentoDispositivo(
    val id: Long,
    val nome: String = "",
    val tipo: String = TipiDispositivo.TELEFONO,
)

/**
 * (v3) Un dispositivo dentro GET /api/finestra, con tutto quello che è suo:
 * tempi, siti, medie, bonus, la sua striscia e il suo stato di silenzio.
 */
@Serializable
data class DispositivoFinestra(
    val id: Long,
    val nome: String = "",
    val tipo: String = TipiDispositivo.TELEFONO,
    val abbinato: Boolean = true,
    val revocato: Boolean = false,
    @SerialName("stato_silenzio") val statoSilenzio: StatoSilenzio? = null,
    val striscia: List<QuadrettoSemaforo> = emptyList(),
    @SerialName("uso_recente") val usoRecente: List<UsoGiorno> = emptyList(),
    // Stessa distinzione della finestra: null = niente siti da questo server.
    @SerialName("siti_recenti") val sitiRecenti: List<SitiGiorno>? = null,
    val medie: Medie? = null,
    val bonus: StatoBonus? = null,
    @SerialName("bonus_giornalieri") val bonusGiornalieri: List<BonusGiorno> = emptyList(),
)

/** Risposta di POST /api/figli e PATCH /api/figli/{id}. */
@Serializable
data class FiglioRisposta(
    val id: Long,
    val nome: String = "",
    @SerialName("creato_ts") val creatoTs: String? = null,
)

/** Corpo di POST /api/figli e PATCH /api/figli/{id}: il nome, 1-40 caratteri. */
@Serializable
data class CorpoNomeFiglio(val nome: String)

/** Corpo di POST /api/figli/{id}/dispositivi. */
@Serializable
data class CorpoNuovoDispositivo(val nome: String, val tipo: String)

/**
 * Il codice di 6 cifre per collegare un dispositivo: vale 15 minuti, una volta
 * sola. Risposta di POST /api/figli/{id}/dispositivi e di
 * POST /api/dispositivi/{id}/codice.
 */
@Serializable
data class CodiceAbbinamento(
    val dispositivo: RiferimentoDispositivo? = null,
    val codice: String,
    @SerialName("scade_ts") val scadeTs: String? = null,
)

/** Corpo di POST /api/segno in v3: il segno va a UN figlio. */
@Serializable
data class CorpoSegno(@SerialName("figlio_id") val figlioId: Long)

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

    // (v3) Abbinamento e famiglia.
    const val CODICE_NON_VALIDO = "codice_non_valido"
    const val TROPPI_TENTATIVI = "troppi_tentativi"
    const val DISPOSITIVO_REVOCATO = "dispositivo_revocato"
    const val NOME_NON_VALIDO = "nome_non_valido"

    // (v3.4) La risposta del genitore a una proposta del figlio, e il ritiro.
    const val PROPOSTA_NON_PENDENTE = "proposta_non_pendente"
    const val ULTIMA_REGOLA = "ultima_regola"

    // (v3.5) La risposta del genitore a una sessione: non c'era niente in attesa.
    const val NIENTE_DA_DECIDERE = "niente_da_decidere"

    /**
     * (v3.5) La `versione` mandata con la risposta non è più quella della sessione:
     * nel frattempo è cambiata (il figlio ha cambiato la richiesta, o qualcuno ha
     * già deciso). Niente è stato deciso, e il 409 porta la sessione com'è adesso:
     * il genitore non approva mai una lista che non ha visto.
     */
    const val RICHIESTA_CAMBIATA = "richiesta_cambiata"

    /**
     * Coniato qui: un server più vecchio della v3.4 non conosce il ritiro di una
     * proposta (la rotta risponde 404 o 405). Non è un errore da riprovare: serve
     * aggiornare il server di Pactum. (0.11) Lo stesso per le sessioni: un server
     * più vecchio della v3.5 non conosce /api/sessioni.
     */
    const val SERVER_DA_AGGIORNARE = "server_da_aggiornare"

    /** Coniato qui per il 404 (figlio o dispositivo che non esiste più): il 404 non porta un codice. */
    const val NON_TROVATO = "non_trovato"

    // (v3.6) I genitori.
    /** POST /api/abbina: il codice è di un altro tipo (un telefono o un computer del figlio). */
    const val TIPO_NON_CORRISPONDENTE = "tipo_non_corrispondente"
    const val TROPPI_GENITORI = "troppi_genitori"
    const val NON_TE_STESSO = "non_te_stesso"
    const val ULTIMO_GENITORE = "ultimo_genitore"

    /**
     * Un codice nuovo chiesto per un genitore già tolto. Il contratto v3.6 non lo
     * elenca ancora; il server lo manda come `dispositivo_revocato` per i dispositivi.
     */
    const val GENITORE_REVOCATO = "genitore_revocato"

    /**
     * Coniato qui per il 401: il server non riconosce più il codice di questo
     * telefono (un altro genitore l'ha tolto, o il codice d'accesso è sbagliato). Non
     * è la rete: riprovare non serve, serve un codice nuovo.
     */
    const val COLLEGAMENTO_NON_VALIDO = "collegamento_non_valido"

    // (v3.6) Le faccende.
    const val TROPPE_FACCENDE = "troppe_faccende"
    const val NON_BOCCIABILE = "non_bocciabile"
    const val NON_ANNULLABILE = "non_annullabile"

    /**
     * Coniato qui: una creazione è rimasta senza risposta e nemmeno la famiglia si
     * è potuta rileggere. Non si sa se il server l'ha ricevuta: prima di riprovare
     * va guardata la lista, o si fa un doppione.
     */
    const val ESITO_INCERTO = "esito_incerto"
}

// --- I genitori (0.13, contratto v3.6) ------------------------------------------------
// Più genitori, tutti uguali: vedono tutto, ricevono gli avvisi, propongono,
// approvano, danno faccende. Ognuno ha il suo nome e il suo token. Il genitore 1
// è quello del vecchio codice d'accesso lungo; gli altri si collegano con un
// codice di 6 cifre creato da un genitore già collegato.

/** Chi è un genitore, come lo allegano le risposte ("chi ha fatto cosa", `io`). */
@Serializable
data class RiferimentoGenitore(
    val id: Long,
    val nome: String = "",
)

/** Un genitore come lo racconta GET /api/genitori (revocati compresi). */
@Serializable
data class Genitore(
    val id: Long,
    val nome: String = "",
    val abbinato: Boolean = true,
    val revocato: Boolean = false,
    @SerialName("creato_ts") val creatoTs: String? = null,
)

/** Un genitore come lo racconta GET /api/famiglia: senza `abbinato`. */
@Serializable
data class GenitoreFamiglia(
    val id: Long,
    val nome: String = "",
    val revocato: Boolean = false,
)

/** GET /api/genitori: chi sei tu e tutti i genitori, in ordine di id. */
@Serializable
data class PaccoGenitori(
    val io: RiferimentoGenitore? = null,
    val genitori: List<Genitore> = emptyList(),
)

/** Corpo di POST /api/genitori e PATCH /api/genitori/{id}: il nome, 1-40 caratteri. */
@Serializable
data class CorpoNomeGenitore(val nome: String)

/**
 * Il codice di 6 cifre per collegare il telefono di un genitore: risposta di
 * POST /api/genitori e di POST /api/genitori/{id}/codice. Stesse regole di quello
 * dei dispositivi: 15 minuti, una volta sola.
 */
@Serializable
data class CodiceGenitore(
    val genitore: Genitore? = null,
    val codice: String,
    @SerialName("scade_ts") val scadeTs: String? = null,
)

/**
 * Corpo di POST /api/abbina dall'app del genitore: il codice di 6 cifre, il tipo
 * (sempre "genitore": un codice di un telefono o di un computer del figlio non
 * si consuma qui) e la versione dell'app. Il tipo non ha un valore di riserva: i
 * valori di riserva non si scrivono nel JSON, e senza tipo il server lo
 * prenderebbe per un dispositivo.
 */
@Serializable
data class CorpoAbbinaGenitore(
    val codice: String,
    val tipo: String,
    @SerialName("versione_app") val versioneApp: String? = null,
)

/** Il `tipo` di POST /api/abbina per l'app del genitore (contratto v3.6). */
const val TIPO_ABBINAMENTO_GENITORE = "genitore"

/** Risposta di POST /api/abbina col tipo "genitore": il token (una volta sola) e chi sei. */
@Serializable
data class AbbinamentoGenitore(
    val token: String = "",
    val genitore: RiferimentoGenitore? = null,
)

// --- Le faccende (0.13, contratto v3.6) ----------------------------------------------
// Un genitore dà al figlio delle faccende di casa. Da quando lo decide lui (subito
// o da un'ora scelta), e finché il figlio non le ha fatte tutte mandando una foto
// per ognuna, i suoi dispositivi sono bloccati. Un genitore può bocciare una foto
// entro 24 ore: la faccenda si riapre e il blocco torna subito.

/** Una faccenda come la manda il server (GET /api/faccende, la finestra, le risposte). */
@Serializable
data class Faccenda(
    val id: Long,
    @SerialName("figlio_id") val figlioId: Long? = null,
    val titolo: String = "",
    val nota: String? = null,
    val stato: String = "",
    /** Da quando blocca. Mai null nelle risposte del server. */
    @SerialName("blocco_da") val bloccoDa: String? = null,
    @SerialName("creata_ts") val creataTs: String? = null,
    @SerialName("creata_da") val creataDa: RiferimentoGenitore? = null,
    /** Quando è arrivata la foto (ora del server); resta anche quando la foto si cancella. */
    @SerialName("foto_ts") val fotoTs: String? = null,
    /** true = il file della foto c'è ancora (si tiene 30 giorni). */
    val foto: Boolean = false,
    val bocciature: Int = 0,
    @SerialName("ultima_bocciatura") val ultimaBocciatura: Bocciatura? = null,
    @SerialName("chiusa_ts") val chiusaTs: String? = null,
    @SerialName("annullata_da") val annullataDa: RiferimentoGenitore? = null,
)

/** L'ultima bocciatura di una faccenda: quando, perché e chi. */
@Serializable
data class Bocciatura(
    val ts: String? = null,
    val nota: String? = null,
    val da: RiferimentoGenitore? = null,
)

object StatiFaccenda {
    const val DA_FARE = "da_fare"
    const val FATTA = "fatta"
    const val ANNULLATA = "annullata"
}

/**
 * Il blocco delle faccende (GET /api/faccende/blocco, `blocco` nella finestra):
 * [attivo] = c'è una faccenda da fare il cui blocco è già partito, [dal] = da
 * quando; [prossimo] = se non è attivo, quando parte il prossimo.
 */
@Serializable
data class BloccoFaccende(
    val attivo: Boolean = false,
    val dal: String? = null,
    val prossimo: String? = null,
)

@Serializable
data class PaccoFaccende(val faccende: List<Faccenda> = emptyList())

/** Una faccenda nel corpo di POST /api/faccende: il titolo e la nota facoltativa. */
@Serializable
data class CorpoFaccenda(
    val titolo: String,
    val nota: String? = null,
)

/**
 * Corpo di POST /api/faccende: il figlio (sempre: dare faccende al figlio
 * sbagliato blocca il telefono sbagliato), da 1 a 10 faccende, e da quando
 * bloccano. [bloccoDa] null non si scrive: vuol dire "subito".
 */
@Serializable
data class CorpoNuoveFaccende(
    @SerialName("figlio_id") val figlioId: Long,
    val faccende: List<CorpoFaccenda>,
    @SerialName("blocco_da") val bloccoDa: String? = null,
)

/** Corpo di POST /api/faccende/{id}/boccia: il perché, facoltativo. */
@Serializable
data class CorpoBoccia(val nota: String? = null)

/** Quanti caratteri al massimo per il titolo di una faccenda, per la sua nota e per quella di una bocciatura. */
const val MASSIMO_TITOLO_FACCENDA = 80
const val MASSIMO_NOTA_FACCENDA = 300

/** Quante faccende si danno al massimo in una volta. */
const val MASSIMO_FACCENDE_PER_VOLTA = 10

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

/**
 * Un sito del giorno: il dominio registrabile e quante volte è stato richiesto.
 * (v3) Sui computer anche i `minuti` passati con quel sito in primo piano; sui
 * telefoni il campo non c'è (null): il telefono vede le richieste, non il tempo.
 */
@Serializable
data class SitoVisitato(
    val dominio: String,
    val visite: Int = 0,
    val minuti: Int? = null,
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
    // (v3.8) La SOMMA dei minuti degli stessi giorni della media (ultimi 7 o 30,
    // oggi compreso). null = server prima della v3.8: niente riga dei totali.
    val totale: Int? = null,
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
    // (v3.3) Il limite su TUTTO il dispositivo ("Tutto il telefono": una regola
    // limite_tempo con app_o_categoria = "totale"), accanto al totale del giorno
    // e mai in `app` o `categorie`. Stesso significato che in UsoApp: limite
    // base, e i minuti bonus concessi quel giorno su quella regola. Ci sono solo
    // se il dispositivo ha una regola sul totale attiva e il giorno ha la sua
    // fotografia; assenti (server vecchio, nessuna regola) = null, null, 0.
    val limite: Int? = null,
    @SerialName("regola_id") val regolaId: Long? = null,
    val bonus: Int = 0,
    // (0.11) I minuti del giorno NON contati perché passati in una sessione, nelle
    // sue app (contratto v3.5): sono fuori da `totale_minuti`, e il Tempo li dice
    // a parte. Assente (server o telefono più vecchi) = null: non se ne parla.
    @SerialName("sessioni_minuti") val sessioniMinuti: Int? = null,
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
    // invece di com.zhiliaoapp.musically). Assente per le categorie, per il
    // totale del dispositivo (v3.3: "Tutto il telefono" lo scrive l'app) e sui
    // server vecchi: allora si ripiega sulla chiave.
    val nome: String? = null,
    val attiva: Boolean = true,
    @SerialName("creata_ts") val creataTs: String = "",
    @SerialName("ultima_modifica_ts") val ultimaModificaTs: String = "",
    @SerialName("allentabile_dal") val allentabileDal: String? = null,
    val semaforo: List<QuadrettoSemaforo> = emptyList(),
    // (v3) Di quale dispositivo è la regola; null per la vita reale (è del
    // figlio) e sui server 0.7.
    @SerialName("dispositivo_id") val dispositivoId: Long? = null,
    val dispositivo: RiferimentoDispositivo? = null,
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
    // (v3) Il dispositivo che l'ha mandato; null sui server 0.7.
    @SerialName("dispositivo_id") val dispositivoId: Long? = null,
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
    // (v3) Dopo una `sospensione` (spegnimento, sospensione, uscita
    // dall'account) il silenzio NON è un'interruzione. Il server manda
    // silente=false, spento=true e da quando. (0.14, contratto v3.7) Non più
    // solo i computer: anche il telefono manda la `sospensione` quando si spegne.
    val spento: Boolean = false,
    @SerialName("spento_dal") val spentoDal: String? = null,
)

@Serializable
data class Notifica(
    val id: Long,
    val tipo: String,
    val messaggio: String,
    val payload: JsonObject = JsonObject(emptyMap()),
    @SerialName("ts_server") val tsServer: String,
    // (v3) Di quale figlio e di quale dispositivo (null = del figlio intero o
    // server 0.7). Il genitore riceve quelle di tutti i figli.
    @SerialName("figlio_id") val figlioId: Long? = null,
    @SerialName("dispositivo_id") val dispositivoId: Long? = null,
)

/**
 * La risposta di GET /api/notifiche. (0.9) `notifiche` è OBBLIGATORIO, senza
 * valore di riserva: una risposta `{}` o `{"notifiche": null}` non è "zero non
 * lette" ma una risposta sbagliata, e si tratta come un server muto. Con una
 * lista vuota di riserva la vedetta dimenticherebbe gli avvisi già dati e al
 * giro dopo li ridarebbe tutti.
 */
@Serializable
data class PaccoNotifiche(val notifiche: List<Notifica>)

// --- Proposte (tappa 5) -----------------------------------------------------
// Il genitore propone una modifica; il server calcola il `confronto` testuale e
// la `direzione`, e il figlio accetta/rifiuta. La proposta accettata applica da
// sola la modifica lato server (contratto-api.md, sezione Proposte).
//
// (0.10) Dal contratto v3.4 propone anche il figlio e risponde il genitore: se
// il genitore accetta, la modifica vale subito, anche se allenta. Chi ha fatto
// la proposta lo dice `autore`; risponde sempre l'altro, e chi l'ha fatta la può
// ritirare finché è in attesa.

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
    // (0.10) Chi l'ha fatta: "genitore" o "figlio" (contratto v3.4). Le proposte
    // nate prima della v3.4, e tutte quelle di un server più vecchio, sono del
    // genitore: assente (o null) vale "genitore".
    val autore: String = AutoriProposta.GENITORE,
    // (0.13) Quale genitore l'ha fatta (contratto v3.6, "Chi ha fatto cosa"); null
    // sulle proposte del figlio e sui server più vecchi (un genitore solo).
    val genitore: RiferimentoGenitore? = null,
    // (0.13) Quale genitore ha risposto a una proposta del figlio; null se nessuno
    // (o server più vecchio).
    @SerialName("risposta_di") val rispostaDi: RiferimentoGenitore? = null,
)

/** La risposta a una proposta (presente quando qualcuno ha risposto): del figlio, o (0.10) del genitore. */
@Serializable
data class RispostaProposta(
    val esito: String,
    val motivazione: String? = null,
    @SerialName("ts_server") val tsServer: String = "",
)

@Serializable
data class PaccoProposte(val proposte: List<Proposta> = emptyList())

/**
 * (0.10) Corpo di POST /api/proposte/{id}/risposta col token del genitore: la
 * decisione su una proposta del figlio. Come nel verdetto, `figlio_id` dice di
 * quale figlio è la proposta (il server risponde 404 se non combacia). I null
 * non si scrivono: `motivazione` vuota e `figlio_id` sconosciuto restano fuori.
 */
@Serializable
data class CorpoRispostaProposta(
    val esito: String,
    val motivazione: String? = null,
    @SerialName("figlio_id") val figlioId: Long? = null,
)

/**
 * (0.10) Risposta di POST /api/proposte/{id}/risposta: la proposta chiusa e la
 * regola che ne risulta (null se la proposta era di eliminarla, o se è stata
 * rifiutata). La regola resta un oggetto grezzo: l'app non la usa (rilegge la
 * finestra), e una sua forma inattesa non deve far sembrare fallita una
 * risposta andata a buon fine.
 */
@Serializable
data class PropostaDecisa(
    val proposta: Proposta,
    val regola: JsonObject? = null,
)

/**
 * Corpo di POST /api/proposte. Per l'eliminazione, `parametriProposti` è il
 * marcatore. (v3) `figlioId` va nel corpo; null (server 0.7) non si scrive
 * affatto: i default non si codificano.
 */
@Serializable
data class NuovaProposta(
    @SerialName("regola_id") val regolaId: Long,
    @SerialName("parametri_proposti") val parametriProposti: JsonObject,
    val motivazione: String? = null,
    @SerialName("figlio_id") val figlioId: Long? = null,
)

object StatiProposta {
    const val PENDENTE = "pendente"
    const val ACCETTATA = "accettata"
    const val RIFIUTATA = "rifiutata"
    const val ANNULLATA = "annullata"

    /** (0.10) Ritirata da chi l'aveva fatta, prima di una risposta (contratto v3.4). */
    const val RITIRATA = "ritirata"
}

/** (0.10) Chi ha fatto una proposta (contratto v3.4): risponde sempre l'altro. */
object AutoriProposta {
    const val GENITORE = "genitore"
    const val FIGLIO = "figlio"
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

// --- Sessioni (0.11, contratto v3.5) -----------------------------------------------
// Una sessione ("Studio", "Lavoro") è una lista di app del telefono del figlio,
// più `gruppo:apk` = tutte le app installate fuori dal Play Store, anche quelle
// installate dopo. La crea il figlio; il genitore la approva una volta, e di nuovo
// a ogni cambio della lista (`modifica_in_attesa`); il figlio la avvia quando
// vuole, per quanto vuole, e la può chiudere prima. Durante la sessione il tempo
// nelle sue app non conta, e il telefono copre le altre app. Il genitore vede
// inizio, durata, fine e chiusure anticipate: mai quali app il figlio ha provato
// ad aprire.

/** Una sessione come la manda il server (GET /api/finestra, GET /api/sessioni). */
@Serializable
data class Sessione(
    val id: Long,
    @SerialName("dispositivo_id") val dispositivoId: Long? = null,
    val dispositivo: RiferimentoDispositivo? = null,
    val nome: String = "",
    /** I pacchetti Android, e `gruppo:apk`. */
    val app: List<String> = emptyList(),
    /** Le etichette leggibili delle chiavi, risolte sul telefono del figlio. */
    val nomi: Map<String, String> = emptyMap(),
    val stato: String = "",
    /** Un cambio chiesto su una sessione già approvata; null = nessuno. */
    @SerialName("modifica_in_attesa") val modificaInAttesa: ModificaSessione? = null,
    /** Il perché dell'ultimo "non approvare" del genitore. */
    val motivazione: String? = null,
    /**
     * Aumenta a ogni cambio della sessione (creazione, ogni cambio chiesto dal
     * figlio, ogni decisione). La risposta del genitore porta quella che aveva
     * sullo schermo: se nel frattempo è cambiata, il server non decide niente
     * (409 `richiesta_cambiata`). null = server che non la manda.
     */
    val versione: Int? = null,
    @SerialName("creata_ts") val creataTs: String? = null,
    @SerialName("approvata_ts") val approvataTs: String? = null,
    /** (0.13) Il genitore dell'ultima decisione (contratto v3.6); null = nessuna, o server vecchio. */
    @SerialName("decisa_da") val decisaDa: RiferimentoGenitore? = null,
)

/**
 * Il cambio chiesto su una sessione approvata: il nome, le app e i nomi come
 * sarebbero dopo (il server lo manda sempre completo), e quando il figlio l'ha
 * chiesto. Per tolleranza un campo che manca (null) vale "come adesso".
 */
@Serializable
data class ModificaSessione(
    val nome: String? = null,
    val app: List<String>? = null,
    val nomi: Map<String, String>? = null,
    @SerialName("richiesta_ts") val richiestaTs: String? = null,
)

/**
 * Una sessione fatta (o in corso): nome, app e nomi congelati all'avvio. Una
 * sessione scaduta il server la chiude da sé quando legge (`chiusura: "scaduta"`,
 * `fine_ts` = `fine_prevista_ts`); una chiusa prima dal figlio ha `chiusura:
 * "terminata"`. In corso: `chiusura` e `fine_ts` null.
 */
@Serializable
data class SessioneSvolta(
    val id: Long,
    @SerialName("sessione_id") val sessioneId: Long? = null,
    @SerialName("dispositivo_id") val dispositivoId: Long? = null,
    val nome: String = "",
    val app: List<String> = emptyList(),
    val nomi: Map<String, String> = emptyMap(),
    @SerialName("inizio_ts") val inizioTs: String? = null,
    @SerialName("durata_minuti") val durataMinuti: Int? = null,
    @SerialName("fine_prevista_ts") val finePrevistaTs: String? = null,
    @SerialName("fine_ts") val fineTs: String? = null,
    val chiusura: String? = null,
    @SerialName("in_corso") val inCorso: Boolean = false,
)

@Serializable
data class PaccoSessioni(val sessioni: List<Sessione> = emptyList())

/**
 * Corpo di POST /api/sessioni/{id}/risposta: [esito] `approva` o `rifiuta`, la
 * [versione] della sessione che il genitore ha sullo schermo, il perché
 * facoltativo (al massimo [MASSIMO_MOTIVAZIONE_SESSIONE] caratteri) e il figlio
 * (404 se non combacia). I null non si scrivono.
 */
@Serializable
data class CorpoRispostaSessione(
    val esito: String,
    val versione: Int? = null,
    val motivazione: String? = null,
    @SerialName("figlio_id") val figlioId: Long? = null,
)

/** Quanti caratteri al massimo per il perché di una risposta a una sessione (contratto v3.5). */
const val MASSIMO_MOTIVAZIONE_SESSIONE = 500

object StatiSessione {
    const val IN_ATTESA = "in_attesa"
    const val APPROVATA = "approvata"
    const val RIFIUTATA = "rifiutata"
}

object EsitiSessione {
    const val APPROVA = "approva"
    const val RIFIUTA = "rifiuta"
}

/** Come si è chiusa una sessione fatta; null = ancora in corso. */
object ChiusureSessione {
    /** Finita da sola, alla fine prevista. */
    const val SCADUTA = "scaduta"

    /** Chiusa prima dal figlio, con "Termina la sessione". */
    const val TERMINATA = "terminata"
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
    /** (0.13) Quale genitore l'ha dato (contratto v3.6); null = server vecchio. */
    val da: RiferimentoGenitore? = null,
)

@Serializable
data class PaccoDichiarazioni(val dichiarazioni: List<Dichiarazione> = emptyList())

/** Corpo di POST /api/dichiarazioni/{id}/verdetto ((v3) col figlio, se noto). */
@Serializable
data class CorpoVerdetto(
    val verdetto: String,
    val nota: String? = null,
    @SerialName("figlio_id") val figlioId: Long? = null,
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
