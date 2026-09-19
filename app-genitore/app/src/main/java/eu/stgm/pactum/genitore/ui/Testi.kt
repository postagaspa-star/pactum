package eu.stgm.pactum.genitore.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.CodiciErrore
import eu.stgm.pactum.genitore.dati.EsitiDichiarazione
import eu.stgm.pactum.genitore.dati.EsitiRisposta
import eu.stgm.pactum.genitore.dati.Notifica
import eu.stgm.pactum.genitore.dati.RegolaFinestra
import eu.stgm.pactum.genitore.dati.TipiRegola
import eu.stgm.pactum.genitore.rete.PostinoClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Traduzioni dai dati del contratto all'italiano semplice della finestra:
 * descrizioni delle regole da tipo+parametri, i testi delle notifiche e gli
 * orari del server (ISO UTC) mostrati nel fuso del telefono.
 */

// istanteServer() vive in LogicaPatto.kt: è logica pura, serve anche ai test.

/**
 * Chi trasforma un id di strings.xml in testo. Nell'app è il Context — anche
 * fuori da un Composable, nella vedetta; nei test JVM è un lettore di
 * strings.xml, così i test provano le frasi VERE che leggerà il genitore.
 * Le parole restano tutte in strings.xml: qui si decide solo quale usare.
 */
interface Parole {
    fun testo(@StringRes id: Int, vararg argomenti: Any): String
}

fun paroleDi(context: Context): Parole = object : Parole {
    override fun testo(id: Int, vararg argomenti: Any): String = context.getString(id, *argomenti)
}

@Composable
fun parole(): Parole = paroleDi(LocalContext.current)

private val formatoOra: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val formatoDataOra: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm")
private val formatoDataOraCompleta: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

/** "HH:mm" se l'istante è di oggi (fuso del telefono), altrimenti "dd/MM HH:mm". */
fun oraOppureDataOra(istante: Instant): String {
    val fuso = ZoneId.systemDefault()
    val locale = istante.atZone(fuso)
    val formato = if (locale.toLocalDate() == LocalDate.now(fuso)) formatoOra else formatoDataOra
    return formato.format(locale)
}

fun dataOraLocale(istante: Instant): String =
    formatoDataOra.format(istante.atZone(ZoneId.systemDefault()))

fun dataOraCompletaLocale(istante: Instant): String =
    formatoDataOraCompleta.format(istante.atZone(ZoneId.systemDefault()))

// contentOrNull e non content: un `null` JSON è un JsonPrimitive il cui
// content è la parola "null", che finirebbe scritta in faccia al genitore.
private fun campo(parametri: JsonObject, nome: String): String? =
    (parametri[nome] as? JsonPrimitive)?.contentOrNull

/** Un campo testuale dei parametri di una regola (es. arbitro_nome), null se assente. */
fun parametroTesto(parametri: JsonObject, nome: String): String? = campo(parametri, nome)

/** La regola della finestra raccontata col suo nome leggibile, se il server l'ha allegato. */
@Composable
fun descrizioneRegola(regola: RegolaFinestra): String = descrizioneRegola(parole(), regola)

fun descrizioneRegola(parole: Parole, regola: RegolaFinestra): String =
    descrizioneRegola(parole, regola.tipo, regola.parametri, regola.nome)

/** Il tipo della regola come sopra-titolo della sua scheda ("LIMITE DI TEMPO"). */
@Composable
fun etichettaTipoRegola(tipo: String): String = when (tipo) {
    TipiRegola.LIMITE_TEMPO -> stringResource(R.string.regola_tipo_limite_tempo)
    TipiRegola.FASCIA_ORARIA -> stringResource(R.string.regola_tipo_fascia_oraria)
    TipiRegola.VITA_REALE -> stringResource(R.string.regola_tipo_vita_reale)
    else -> tipo.uppercase()
}

/**
 * La regola raccontata in italiano semplice, costruita da tipo+parametri
 * (contratto-api.md). Un tipo sconosciuto mostra il tipo grezzo: meglio
 * onesto che muto (tolleranza evolutiva).
 *
 * `nomeApp` è il nome leggibile che la finestra allega alle limite_tempo su un
 * pacchetto ("TikTok"): se c'è, il genitore non legge mai com.zhiliaoapp.musically.
 */
fun descrizioneRegola(
    parole: Parole,
    tipo: String,
    parametri: JsonObject,
    nomeApp: String? = null,
): String = when (tipo) {
    TipiRegola.LIMITE_TEMPO -> parole.testo(
        R.string.regola_limite_tempo,
        nomeBersaglio(parametri, nomeApp),
        testoDurata(parole, campo(parametri, "minuti_al_giorno")?.toLongOrNull() ?: 0),
    )

    TipiRegola.FASCIA_ORARIA -> parole.testo(
        R.string.regola_fascia_oraria,
        campo(parametri, "dalle") ?: "?",
        campo(parametri, "alle") ?: "?",
        (parametri["giorni"] as? JsonArray)
            ?.joinToString(", ") { (it as? JsonPrimitive)?.contentOrNull ?: "?" }
            ?: "?",
    )

    TipiRegola.VITA_REALE -> parole.testo(
        R.string.regola_vita_reale,
        campo(parametri, "descrizione") ?: "?",
        campo(parametri, "arbitro_nome") ?: "?",
        campo(parametri, "frequenza") ?: "?",
    )

    else -> tipo
}

@Composable
fun descrizioneParametri(regola: RegolaFinestra, parametri: JsonObject): String =
    descrizioneParametri(parole(), regola, parametri)

/**
 * La regola raccontata coi parametri di un momento del passato (una modifica,
 * una creazione), non con quelli vigenti: lo storico e le notifiche raccontano
 * quello che è successo allora. Il nome leggibile dell'app vale solo se l'app
 * è ancora la stessa.
 */
fun descrizioneParametri(parole: Parole, regola: RegolaFinestra, parametri: JsonObject): String {
    val stessaApp = campo(parametri, "app_o_categoria") ==
        campo(regola.parametri, "app_o_categoria")
    return descrizioneRegola(parole, regola.tipo, parametri, regola.nome.takeIf { stessaApp })
}

/** L'app o la categoria di una limite_tempo, col nome leggibile se c'è. */
private fun nomeBersaglio(parametri: JsonObject, nomeApp: String?): String =
    nomeApp?.takeIf { it.isNotBlank() }
        ?: etichettaAppOCategoria(campo(parametri, "app_o_categoria") ?: "?")

@Composable
fun testoDurata(minuti: Long): String = testoDurata(parole(), minuti)

/**
 * Una durata come la direbbe una persona: "45 min", "1 h", "2 h 30 min".
 * Con i minuti a zero si dice solo l'ora: "1 h 0 min" non lo scrive nessuno.
 */
fun testoDurata(parole: Parole, minuti: Long): String = when {
    minuti < 60 -> parole.testo(R.string.formato_minuti, minuti)
    minuti % 60 == 0L -> parole.testo(R.string.formato_ore, minuti / 60)
    else -> parole.testo(R.string.formato_ore_minuti, minuti / 60, minuti % 60)
}

/** Un giorno ISO del contratto ("2026-07-15") come "15/07"; il grezzo se malformato. */
fun giornoBreve(giornoIso: String): String = try {
    LocalDate.parse(giornoIso).format(formatoGiornoBreve)
} catch (e: DateTimeParseException) {
    giornoIso
}

private val formatoGiornoBreve: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")

/**
 * L'etichetta leggibile di una chiave di categoria del contratto
 * ("categoria:social" → "Social"). Le cinque chiavi di convenzione hanno un
 * nome scritto per esteso — "altro" da solo, in una legenda, non si capisce:
 * diventa "Altre app". Una chiave fuori convenzione resta com'è, solo con
 * l'iniziale maiuscola: meglio onesta che muta (tolleranza evolutiva).
 *
 * Sta qui e non in strings.xml perché serve anche fuori da un Composable (la
 * vedetta, il digest) dove non c'è un Context a portata di mano.
 */
fun etichettaCategoria(chiave: String): String {
    val nome = chiave.removePrefix("categoria:")
    if (nome.isEmpty()) return chiave
    return when (nome.lowercase()) {
        "social" -> "Social"
        "video" -> "Video"
        "giochi" -> "Giochi"
        "musica" -> "Musica"
        "altro" -> "Altre app"
        else -> nome.replaceFirstChar { it.uppercaseChar() }
    }
}

/**
 * Il bersaglio di una regola limite_tempo reso leggibile: una `categoria:*`
 * diventa l'etichetta italiana ("categoria:social" → "Social"); un pacchetto
 * (es. `com.zhiliaoapp.musically`) resta com'è finché il server non allega un
 * nome risolto (S2). Così regole, sforamenti e storico non mostrano più la
 * chiave grezza `categoria:social` a un genitore che non l'ha mai vista.
 */
fun etichettaAppOCategoria(chiave: String): String =
    if (chiave.startsWith("categoria:")) etichettaCategoria(chiave) else chiave

// --- Interruzioni nella registrazione -------------------------------------------

@Composable
fun descrizioneBuco(sottoTipo: String?): String = descrizioneBuco(parole(), sottoTipo)

/**
 * Un'interruzione nella registrazione (evento `manomissione`) detta per quello
 * che è: quasi sempre batteria, rete o un'impostazione del telefono, mai
 * un'accusa. La stessa frase in "Da guardare insieme" e nelle notifiche.
 *
 * Ogni `sotto_tipo` che può arrivare ha la sua frase: quelli che manda l'app
 * del figlio (cambio_ora e cambio_fuso dall'orologio, permesso_revocato e
 * notifiche_disattivate dal worker, osservazione_siti_interrotta dalla VPN dei
 * siti) e `silenzio` del contratto. Un sotto-tipo nuovo ripiega su "Anomalia".
 */
fun descrizioneBuco(parole: Parole, sottoTipo: String?): String = when (sottoTipo) {
    "cambio_ora" -> parole.testo(R.string.manomissione_cambio_ora)
    "cambio_fuso" -> parole.testo(R.string.manomissione_cambio_fuso)
    "silenzio" -> parole.testo(R.string.manomissione_silenzio)
    // Tappa 6: rilevate al giro del worker sul telefono del figlio.
    "permesso_revocato" -> parole.testo(R.string.manomissione_permesso_revocato)
    "notifiche_disattivate" -> parole.testo(R.string.manomissione_notifiche_disattivate)
    // (v2.3) La VPN locale dei siti spenta o revocata sul telefono del figlio.
    "osservazione_siti_interrotta" ->
        parole.testo(R.string.manomissione_osservazione_siti_interrotta)
    else -> parole.testo(R.string.manomissione_generica, sottoTipo ?: "?")
}

// --- Rifiuti del server (409) ---------------------------------------------------

/**
 * Che cosa dire quando il server rifiuta una proposta. [codice] è l'`errore`
 * del 409 (o PARAMETRI_NON_VALIDI per un 422); null = nessun codice leggibile
 * o rete caduta: "riprova".
 */
@StringRes
fun messaggioRifiutoProposta(codice: String?): Int = when (codice) {
    CodiciErrore.PROPOSTA_GIA_PENDENTE -> R.string.proposta_errore_gia_pendente
    CodiciErrore.REGOLA_NON_VALIDA -> R.string.proposta_errore_regola_non_valida
    PostinoClient.PARAMETRI_NON_VALIDI -> R.string.proposta_errore_parametri_non_validi
    else -> R.string.proposta_errore_generico
}

/**
 * I rifiuti di una proposta che non si risolvono riprovando: la regola ha già
 * una proposta in attesa, o non è più attiva. Si chiude il dialogo e si rilegge,
 * così la scheda mostra com'è davvero.
 */
fun rifiutoPropostaDefinitivo(codice: String?): Boolean =
    codice == CodiciErrore.PROPOSTA_GIA_PENDENTE || codice == CodiciErrore.REGOLA_NON_VALIDA

/** Che cosa dire quando il server rifiuta una conferma (verdetto). */
@StringRes
fun messaggioRifiutoVerdetto(codice: String?): Int = when (codice) {
    CodiciErrore.DICHIARAZIONE_NON_IN_ATTESA -> R.string.verdetto_errore_non_in_attesa
    else -> R.string.verdetto_errore
}

// --- Dichiarazioni -------------------------------------------------------------

/** L'esito dichiarato dal figlio, detto come lo direbbe lui; il grezzo se sconosciuto. */
@Composable
fun testoEsitoDichiarato(esito: String): String = when (esito) {
    EsitiDichiarazione.SUCCESSO -> stringResource(R.string.dichiarazione_esito_successo)
    EsitiDichiarazione.FALLIMENTO -> stringResource(R.string.dichiarazione_esito_fallimento)
    else -> esito
}

// --- Notifiche -----------------------------------------------------------------

/** Titolo e frase di una notifica del patto: identici nella lista e nella tendina. */
data class TestoNotifica(val titolo: String, val testo: String)

/**
 * Il testo di una notifica, costruito DALL'APP a partire da `tipo` e `payload`
 * (contratto-api.md, GET /api/notifiche): il `messaggio` del server è una
 * riga di registro ("Evento sforamento registrato"), non una frase per un
 * padre. Il nome leggibile delle regole viene da [regolePerId] (le regole della
 * finestra, anche le eliminate).
 *
 * Tipo sconosciuto o payload che non basta (una regola che non si trova, un
 * campo mancante): titolo del tipo e il `messaggio` del server così com'è —
 * meglio una frase grezza che una frase inventata.
 *
 * Unica per la lista delle notifiche e per le notifiche di sistema della
 * vedetta: le due non possono dire cose diverse sullo stesso fatto.
 */
fun testoNotifica(
    parole: Parole,
    notifica: Notifica,
    regolePerId: Map<Long, RegolaFinestra>,
): TestoNotifica =
    fraseNotifica(parole, notifica, regolePerId)
        ?: TestoNotifica(parole.testo(etichettaTipoNotifica(notifica.tipo)), notifica.messaggio)

/**
 * La regola a cui si riferisce una notifica: per sforamenti e buchi nel
 * registro sta dentro i `dettagli` dell'evento, per gli altri tipi è in cima
 * al payload. null se la notifica non parla di una regola.
 */
fun regolaIdNotifica(notifica: Notifica): Long? {
    val contenitore = when (notifica.tipo) {
        "sforamento", "manomissione" -> notifica.payload["dettagli"] as? JsonObject
        else -> notifica.payload
    }
    return contenitore?.let { campo(it, "regola_id")?.toLongOrNull() }
}

/** Il titolo per tipo: quello di ripiego, e quello dei tipi che non ne hanno uno proprio. */
@StringRes
private fun etichettaTipoNotifica(tipo: String): Int = when (tipo) {
    "sforamento" -> R.string.tipo_sforamento
    "manomissione" -> R.string.tipo_manomissione
    "bonus" -> R.string.tipo_bonus
    "modifica_regola" -> R.string.tipo_modifica_regola
    // Tappa 5: il genitore riceve anche le risposte alle proposte e le
    // dichiarazioni del figlio (contratto-api.md, notifiche con destinatario).
    "proposta_risposta" -> R.string.tipo_proposta_risposta
    "proposta_annullata" -> R.string.tipo_proposta_annullata
    "dichiarazione" -> R.string.tipo_dichiarazione
    else -> R.string.tipo_novita // tipo nuovo dal server: tolleranza evolutiva
}

/** null = tipo sconosciuto o payload che non basta: si ripiega sul messaggio del server. */
private fun fraseNotifica(
    parole: Parole,
    notifica: Notifica,
    regolePerId: Map<Long, RegolaFinestra>,
): TestoNotifica? {
    val payload = notifica.payload
    val regola = regolaIdNotifica(notifica)?.let { regolePerId[it] }
    val titolo = parole.testo(etichettaTipoNotifica(notifica.tipo))
    return when (notifica.tipo) {
        // "Fuori regola" / "TikTok: al massimo 1 h al giorno".
        "sforamento" -> regola?.let { TestoNotifica(titolo, descrizioneRegola(parole, it)) }

        // "Anomalia" / "Uso non registrato in questo periodo".
        "manomissione" -> {
            val dettagli = payload["dettagli"] as? JsonObject ?: return null
            val sottoTipo = campo(dettagli, "sotto_tipo") ?: return null
            TestoNotifica(titolo, descrizioneBuco(parole, sottoTipo))
        }

        "bonus" -> {
            val minuti = campo(payload, "minuti")?.toLongOrNull() ?: return null
            val su = regola?.let { suRegola(parole, it) } ?: return null
            val frase = parole.testo(R.string.notifica_bonus, testoDurata(parole, minuti), su)
            val motivo = campo(payload, "motivo")?.takeIf { it.isNotBlank() }
            TestoNotifica(
                titolo,
                if (motivo != null) parole.testo(R.string.notifica_bonus_motivo, frase, motivo) else frase,
            )
        }

        "modifica_regola" -> regola?.let { fraseModifica(parole, payload, it) }

        "proposta_risposta" -> {
            val su = regola?.let { suRegola(parole, it) } ?: return null
            val frase = when (campo(payload, "esito")) {
                EsitiRisposta.ACCETTA -> R.string.notifica_proposta_accettata
                EsitiRisposta.RIFIUTA -> R.string.notifica_proposta_rifiutata
                else -> return null
            }
            TestoNotifica(titolo, parole.testo(frase, su))
        }

        "proposta_annullata" -> {
            val su = regola?.let { suRegola(parole, it) } ?: return null
            TestoNotifica(titolo, parole.testo(R.string.notifica_proposta_annullata, su))
        }

        // "Dice di aver fatto: Camminare un'ora (16/09)".
        "dichiarazione" -> {
            val cosa = regola?.let { campo(it.parametri, "descrizione") } ?: return null
            val giorno = campo(payload, "giorno") ?: return null
            val frase = when (campo(payload, "esito")) {
                EsitiDichiarazione.SUCCESSO -> R.string.notifica_dichiarazione_successo
                EsitiDichiarazione.FALLIMENTO -> R.string.notifica_dichiarazione_fallimento
                else -> return null
            }
            TestoNotifica(titolo, parole.testo(frase, cosa, giornoBreve(giorno)))
        }

        else -> null
    }
}

/**
 * Una regola creata, cambiata o uscita dal patto: il titolo dice che cosa è
 * successo ("Nuova regola", "Regola stretta"), la frase è la regola coi
 * parametri di quel momento — come nella storia del patto.
 */
private fun fraseModifica(parole: Parole, payload: JsonObject, regola: RegolaFinestra): TestoNotifica? {
    val (titolo, parametri) = when (campo(payload, "azione")) {
        "creazione" -> parole.testo(R.string.notifica_regola_nuova) to payload["parametri"]
        "modifica" -> when (campo(payload, "direzione")) {
            "allenta" -> parole.testo(R.string.storico_modifica_allenta) to payload["dopo"]
            "stringe" -> parole.testo(R.string.storico_modifica_stringe) to payload["dopo"]
            else -> return null
        }
        "eliminazione" -> parole.testo(R.string.storico_eliminazione) to payload["prima"]
        else -> return null
    }
    val oggetto = parametri as? JsonObject ?: return null
    val concordata = campo(payload, "concordata")?.toBooleanStrictOrNull() == true
    return TestoNotifica(
        titolo = if (concordata) "$titolo · ${parole.testo(R.string.storico_concordata)}" else titolo,
        testo = descrizioneParametri(parole, regola, oggetto),
    )
}

/**
 * "su TikTok", "sulla fascia 21:00–07:00", "su «Camminare un'ora»": la regola
 * come complemento di una frase ("Ha accettato la tua proposta su TikTok").
 * null per un tipo di regola sconosciuto.
 */
private fun suRegola(parole: Parole, regola: RegolaFinestra): String? = when (regola.tipo) {
    TipiRegola.LIMITE_TEMPO ->
        parole.testo(R.string.regola_su_app, nomeBersaglio(regola.parametri, regola.nome))
    TipiRegola.FASCIA_ORARIA -> parole.testo(
        R.string.regola_su_fascia,
        campo(regola.parametri, "dalle") ?: "?",
        campo(regola.parametri, "alle") ?: "?",
    )
    TipiRegola.VITA_REALE ->
        parole.testo(R.string.regola_su_vita_reale, campo(regola.parametri, "descrizione") ?: "?")
    else -> null
}
