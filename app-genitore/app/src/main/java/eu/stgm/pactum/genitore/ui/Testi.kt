package eu.stgm.pactum.genitore.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import eu.stgm.pactum.design.Segnale
import eu.stgm.pactum.design.TemaSessione
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.AutoriProposta
import eu.stgm.pactum.genitore.dati.CodiciErrore
import eu.stgm.pactum.genitore.dati.DirezioniProposta
import eu.stgm.pactum.genitore.dati.Dispositivo
import eu.stgm.pactum.genitore.dati.EsitiDichiarazione
import eu.stgm.pactum.genitore.dati.EsitiRisposta
import eu.stgm.pactum.genitore.dati.EsitiSessione
import eu.stgm.pactum.genitore.dati.Figlio
import eu.stgm.pactum.genitore.dati.Notifica
import eu.stgm.pactum.genitore.dati.Proposta
import eu.stgm.pactum.genitore.dati.RegolaFinestra
import eu.stgm.pactum.genitore.dati.RiferimentoDispositivo
import eu.stgm.pactum.genitore.dati.RiferimentoGenitore
import eu.stgm.pactum.genitore.dati.Sessione
import eu.stgm.pactum.genitore.dati.StatiProposta
import eu.stgm.pactum.genitore.dati.StatoSilenzio
import eu.stgm.pactum.genitore.dati.TipiDispositivo
import eu.stgm.pactum.genitore.dati.TipiRegola
import eu.stgm.pactum.genitore.dati.UsoGiorno
import eu.stgm.pactum.genitore.rete.PostinoClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Duration
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

/** "HH:mm" se l'istante è di oggi (fuso del telefono), altrimenti "dd/MM HH:mm". */
fun oraOppureDataOra(istante: Instant): String {
    val fuso = ZoneId.systemDefault()
    val locale = istante.atZone(fuso)
    val formato = if (locale.toLocalDate() == LocalDate.now(fuso)) formatoOra else formatoDataOra
    return formato.format(locale)
}


/**
 * (0.15) Un dato che manca, a schermo: una lineetta, mai un "?" (che sembra una
 * domanda rivolta al genitore).
 */
const val DATO_MANCANTE = "—"

/**
 * (0.15) Un giorno detto in UN formato solo: "oggi", "ieri", altrimenti "14/09".
 * Lo usano tutte le righe che dicono "quando" a livello di giorno.
 */
fun testoGiorno(parole: Parole, giorno: LocalDate, oggi: LocalDate): String = when (giorno) {
    oggi -> parole.testo(R.string.giorno_oggi)
    oggi.minusDays(1) -> parole.testo(R.string.giorno_ieri)
    else -> formatoGiornoBreve.format(giorno)
}

/**
 * (0.15) Giorno e ora in UN formato solo, nel fuso del telefono: "oggi 15:10",
 * "ieri 15:10", altrimenti "14/09 15:10".
 */
fun testoQuando(
    parole: Parole,
    istante: Instant,
    zona: ZoneId = ZoneId.systemDefault(),
    oggi: LocalDate = LocalDate.now(zona),
): String {
    val locale = istante.atZone(zona)
    val ora = formatoOra.format(locale)
    return when (locale.toLocalDate()) {
        oggi -> parole.testo(R.string.quando_oggi, ora)
        oggi.minusDays(1) -> parole.testo(R.string.quando_ieri, ora)
        else -> formatoDataOra.format(locale)
    }
}

/** I giorni della settimana nell'ordine del contratto (giorni:[lun..dom]). */
private val GIORNI_CONTRATTO = listOf("lun", "mar", "mer", "gio", "ven", "sab", "dom")

/**
 * (0.15) I giorni di una fascia oraria detti come li direbbe una persona: tutti e
 * sette "ogni giorno", da lunedì a venerdì "da lunedì a venerdì", sabato e
 * domenica "sabato e domenica"; gli altri casi come prima ("lun, mer, ven"),
 * nell'ordine della settimana (B34).
 */
fun fraseGiorniFascia(parole: Parole, giorni: List<String>): String {
    val puliti = giorni.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
    val noti = puliti.filter { it in GIORNI_CONTRATTO }.toSet()
    if (noti.size == puliti.size) {
        when (noti) {
            GIORNI_CONTRATTO.toSet() -> return parole.testo(R.string.fascia_ogni_giorno)
            GIORNI_CONTRATTO.take(5).toSet() -> return parole.testo(R.string.fascia_feriali)
            GIORNI_CONTRATTO.takeLast(2).toSet() -> return parole.testo(R.string.fascia_fine_settimana)
        }
    }
    return puliti.sortedBy { GIORNI_CONTRATTO.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        .joinToString(", ")
}

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
    descrizioneRegola(parole, regola.tipo, regola.parametri, regola.nome, regola.dispositivo?.tipo)

/**
 * (0.9) Lo stato di una regola in un giorno, in parole, per l'elenco della
 * scheda del patto: "mantenuta", "fuori regola · 15 min oltre", "senza dati";
 * per la vita reale "successo confermato", "non riuscito", "nessuna conferma per
 * ora" (grigio vuol dire niente dichiarato, o un successo ancora da confermare).
 * Una regola che oggi non c'è più lo dice: "· non più attiva".
 */
fun testoStatoRegola(parole: Parole, riga: RegolaDelGiorno): String {
    val stato = if (riga.regola.tipo == TipiRegola.VITA_REALE) {
        when (riga.segnale) {
            Segnale.MANTENUTA -> parole.testo(R.string.stato_impegno_confermato)
            Segnale.FUORI_REGOLA -> parole.testo(R.string.stato_impegno_non_riuscito)
            Segnale.NESSUN_DATO -> parole.testo(R.string.stato_impegno_nessuna_conferma)
        }
    } else {
        when (riga.segnale) {
            Segnale.MANTENUTA -> parole.testo(R.string.stato_regola_mantenuta)
            Segnale.FUORI_REGOLA -> riga.minutiOltre
                ?.let { parole.testo(R.string.stato_regola_fuori_oltre, testoDurata(parole, it.toLong())) }
                ?: parole.testo(R.string.stato_regola_fuori)
            Segnale.NESSUN_DATO -> parole.testo(R.string.stato_regola_senza_dati)
        }
    }
    return if (riga.regola.attiva) stato else parole.testo(R.string.stato_regola_non_piu_attiva, stato)
}

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
 * `tipoDispositivo` (v3): una fascia oraria di un computer dice "Niente
 * computer", non "Niente telefono"; (v3.3) un limite sul totale dice "Tutto il
 * computer", non "Tutto il telefono".
 */
fun descrizioneRegola(
    parole: Parole,
    tipo: String,
    parametri: JsonObject,
    nomeApp: String? = null,
    tipoDispositivo: String? = null,
): String = when (tipo) {
    TipiRegola.LIMITE_TEMPO -> parole.testo(
        R.string.regola_limite_tempo,
        nomeBersaglio(parole, parametri, nomeApp, tipoDispositivo),
        testoDurata(parole, campo(parametri, "minuti_al_giorno")?.toLongOrNull() ?: 0),
    )

    TipiRegola.FASCIA_ORARIA -> parole.testo(
        if (tipoDispositivo == TipiDispositivo.COMPUTER) {
            R.string.regola_fascia_oraria_computer
        } else {
            R.string.regola_fascia_oraria
        },
        campo(parametri, "dalle") ?: DATO_MANCANTE,
        campo(parametri, "alle") ?: DATO_MANCANTE,
        // (0.15) I giorni detti come li direbbe una persona: "ogni giorno", non i
        // sette token del contratto (B34).
        (parametri["giorni"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.let { fraseGiorniFascia(parole, it) }
            ?: DATO_MANCANTE,
    )

    TipiRegola.VITA_REALE -> parole.testo(
        R.string.regola_vita_reale,
        campo(parametri, "descrizione") ?: DATO_MANCANTE,
        campo(parametri, "arbitro_nome") ?: DATO_MANCANTE,
        campo(parametri, "frequenza") ?: DATO_MANCANTE,
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
    return descrizioneRegola(
        parole,
        regola.tipo,
        parametri,
        regola.nome.takeIf { stessaApp },
        regola.dispositivo?.tipo,
    )
}

/**
 * L'app, il programma, il sito o la categoria di una limite_tempo, col nome
 * leggibile se c'è; (v3.3) "Tutto il telefono" o "Tutto il computer" per il
 * totale del dispositivo, dal tipo del dispositivo della regola.
 */
private fun nomeBersaglio(
    parole: Parole,
    parametri: JsonObject,
    nomeApp: String?,
    tipoDispositivo: String?,
): String {
    val chiave = campo(parametri, "app_o_categoria")
    if (eTotale(chiave)) return nomeTotale(parole, tipoDispositivo)
    return nomeLeggibile(chiave ?: DATO_MANCANTE, nomeApp)
}

/**
 * Il bersaglio di una regola limite_tempo come lo legge il genitore ("TikTok",
 * "Social", "Tutto il telefono"): lo stesso della frase della regola. Serve al
 * dialogo delle proposte ("Su: Tutto il telefono").
 */
fun bersaglioRegola(parole: Parole, regola: RegolaFinestra): String =
    nomeBersaglio(parole, regola.parametri, regola.nome, regola.dispositivo?.tipo)

/**
 * (v3.3) Il totale del dispositivo detto per il tipo del dispositivo della
 * regola: "Tutto il computer" per un computer, "Tutto il telefono" per il resto.
 * Una regola senza dispositivo viene da un server che non conosce i computer:
 * è del telefono.
 */
fun nomeTotale(parole: Parole, tipoDispositivo: String?): String = parole.testo(
    if (tipoDispositivo == TipiDispositivo.COMPUTER) {
        R.string.bersaglio_totale_computer
    } else {
        R.string.bersaglio_totale_telefono
    },
)

/**
 * Il nome da mostrare per una chiave del contratto: il `nome` leggibile quando
 * c'è ed è davvero un nome ("TikTok", "Minecraft"), altrimenti la chiave resa
 * leggibile ([etichettaAppOCategoria]). Un `nome` che è la chiave stessa (il
 * ripiego del server: "il pacchetto stesso") o una chiave con prefisso
 * (`exe:…`, `sito:…`) non è un nome: il genitore non legge mai `exe:`.
 */
fun nomeLeggibile(chiave: String, nome: String?): String = nomeVero(chiave, nome) ?: etichettaAppOCategoria(chiave)

/**
 * Il [nome] di una [chiave] se è davvero un nome ("TikTok", "Minecraft"); null se
 * manca, è la chiave stessa (il ripiego del server) o ha un prefisso tecnico.
 */
fun nomeVero(chiave: String, nome: String?): String? =
    nome?.trim()?.takeIf { candidato ->
        candidato.isNotEmpty() &&
            candidato != chiave &&
            PREFISSI_TECNICI.none { candidato.startsWith(it) }
    }

// (0.11) Anche "gruppo:" (gruppo:apk, le sessioni): una chiave, mai un nome.
private val PREFISSI_TECNICI = listOf("exe:", "sito:", "categoria:", "gruppo:")

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

/**
 * (0.16) Una durata scritta corta, per stare sopra una barra del grafico:
 * "2h31", "3h", "45 min". Per esteso la dice [testoDurata].
 */
fun testoDurataBreve(parole: Parole, minuti: Long): String = when {
    minuti < 60 -> parole.testo(R.string.formato_minuti, minuti)
    minuti % 60 == 0L -> parole.testo(R.string.formato_breve_ore, minuti / 60)
    else -> parole.testo(R.string.formato_breve_ore_minuti, minuti / 60, minuti % 60)
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
 *
 * (v3) Sul computer: `sito:youtube.com` → "youtube.com (sito)" e
 * `exe:minecraft.exe` → "minecraft.exe (programma)", quando il server non
 * allega un nome. Come le categorie, sta qui e non in strings.xml.
 */
fun etichettaAppOCategoria(chiave: String): String = when {
    chiave.startsWith("categoria:") -> etichettaCategoria(chiave)
    chiave.startsWith("sito:") && chiave.length > "sito:".length ->
        "${chiave.removePrefix("sito:")} (sito)"
    chiave.startsWith("exe:") && chiave.length > "exe:".length ->
        "${chiave.removePrefix("exe:")} (programma)"
    else -> chiave
}

// --- Interruzioni nella registrazione -------------------------------------------

@Composable
fun descrizioneBuco(sottoTipo: String?): String = descrizioneBuco(parole(), sottoTipo)

/** Un'interruzione raccontata coi suoi dettagli (v3: quando è stato chiuso Pactum sul computer). */
@Composable
fun descrizioneBuco(dettagli: JsonObject): String = descrizioneBuco(parole(), dettagli)

/**
 * Come [descrizioneBuco] per sotto-tipo, ma coi `dettagli` dell'evento: i
 * sotto-tipi del computer (v3) portano con sé un intervallo.
 * - `programma_chiuso` con `dal`/`al` (millisecondi): "Pactum è stato chiuso sul
 *   computer (dalle 15:10 alle 15:40)"; chiuso dal suo menu (`volontario`), o
 *   senza orari, una frase senza intervallo — mai un orario inventato;
 * - `siti_non_leggibili`: il programma non è riuscito a leggere i siti.
 * Gli orari si mostrano nel fuso del telefono, come ogni orario del registro.
 */
fun descrizioneBuco(
    parole: Parole,
    dettagli: JsonObject,
    zona: ZoneId = ZoneId.systemDefault(),
    oggi: LocalDate = LocalDate.now(zona),
): String {
    val sottoTipo = campo(dettagli, "sotto_tipo")
    val intervallo = intervalloOrario(
        parole,
        istanteMillisecondi(dettagli, "dal"),
        istanteMillisecondi(dettagli, "al"),
        zona,
        oggi,
    )
    return when (sottoTipo) {
        "programma_chiuso" -> when {
            intervallo != null ->
                parole.testo(R.string.manomissione_programma_chiuso_quando, intervallo)
            campo(dettagli, "volontario")?.toBooleanStrictOrNull() == true ->
                parole.testo(R.string.manomissione_programma_chiuso_volontario)
            else -> parole.testo(R.string.manomissione_programma_chiuso)
        }
        "siti_non_leggibili" -> if (intervallo != null) {
            parole.testo(R.string.manomissione_siti_non_leggibili_quando, intervallo)
        } else {
            parole.testo(R.string.manomissione_siti_non_leggibili)
        }
        // (0.13) Dalla v3.6 il telefono dice anche QUALE permesso ha perso durante il
        // blocco delle faccende ("il permesso nei dettagli"): l'accesso all'uso resta
        // la frase di sempre; "Mostra sopra le altre app" si dice per nome; un altro
        // permesso si dice senza inventarne il nome.
        "permesso_revocato" -> when (tipoPermesso(campo(dettagli, "permesso"))) {
            TipoPermesso.SOVRAPPOSIZIONE -> parole.testo(R.string.manomissione_permesso_sovrapposizione)
            TipoPermesso.ALTRO -> parole.testo(R.string.manomissione_permesso_generico)
            TipoPermesso.USO, null -> descrizioneBuco(parole, sottoTipo)
        }
        else -> descrizioneBuco(parole, sottoTipo)
    }
}

/** (0.13) Quale permesso ha perso il telefono del figlio, dal campo `permesso` dei dettagli. */
private enum class TipoPermesso { USO, SOVRAPPOSIZIONE, ALTRO }

/**
 * Il nome del permesso nei dettagli di un `permesso_revocato` (contratto v3.6: "il
 * permesso nei dettagli"; il nome esatto del valore il contratto non lo fissa, e
 * si riconoscono le forme probabili). null = nessun permesso nei dettagli (le app
 * di prima della 0.13): è l'accesso all'uso, come sempre.
 */
private fun tipoPermesso(permesso: String?): TipoPermesso? {
    val valore = permesso?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return when {
        listOf("overlay", "sovrappos", "sopra", "system_alert_window").any { it in valore } -> TipoPermesso.SOVRAPPOSIZIONE
        listOf("usage", "uso", "utilizzo").any { it in valore } -> TipoPermesso.USO
        else -> TipoPermesso.ALTRO
    }
}

/** Un campo in millisecondi (epoch) come istante; null se manca o non è un numero. */
private fun istanteMillisecondi(oggetto: JsonObject, nome: String): Instant? =
    campo(oggetto, nome)?.toLongOrNull()?.takeIf { it > 0 }?.let(Instant::ofEpochMilli)

/**
 * "dalle 15:10 alle 15:40" (oggi), "il 22/09 dalle 15:10 alle 15:40" (un altro
 * giorno), "dal 22/09 alle 22:10 al 23/09 alle 07:30" (a cavallo di due giorni).
 * null se manca un estremo o la fine viene prima dell'inizio: un intervallo
 * storto non si racconta.
 */
fun intervalloOrario(
    parole: Parole,
    dal: Instant?,
    al: Instant?,
    zona: ZoneId,
    oggi: LocalDate,
): String? {
    if (dal == null || al == null || al.isBefore(dal)) return null
    val inizio = dal.atZone(zona)
    val fine = al.atZone(zona)
    if (inizio.toLocalDate() != fine.toLocalDate()) {
        return parole.testo(
            R.string.intervallo_giorni_diversi,
            formatoGiornoBreve.format(inizio),
            formatoOra.format(inizio),
            formatoGiornoBreve.format(fine),
            formatoOra.format(fine),
        )
    }
    val ore = parole.testo(R.string.intervallo_stesso_giorno, formatoOra.format(inizio), formatoOra.format(fine))
    return if (inizio.toLocalDate() == oggi) {
        ore
    } else {
        parole.testo(R.string.intervallo_altro_giorno, formatoGiornoBreve.format(inizio), ore)
    }
}

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
    // (v3) Dal computer, senza dettagli: la frase senza intervallo.
    "programma_chiuso" -> parole.testo(R.string.manomissione_programma_chiuso)
    "siti_non_leggibili" -> parole.testo(R.string.manomissione_siti_non_leggibili)
    // (0.13) Il programma del computer chiuso mentre le faccende lo bloccavano.
    "chiuso_durante_blocco" -> parole.testo(R.string.manomissione_chiuso_durante_blocco)
    // (0.15) Senza sotto-tipo niente "Anomalia: ?": solo "Anomalia".
    else -> sottoTipo?.let { parole.testo(R.string.manomissione_generica, it) } ?: parole.testo(R.string.tipo_manomissione)
}

// --- Dispositivi (v3) -----------------------------------------------------------

/** "alle 15:10" se è oggi, "il 22/09 alle 15:10" un altro giorno — nel fuso del telefono. */
fun alleQuando(
    parole: Parole,
    istante: Instant,
    zona: ZoneId = ZoneId.systemDefault(),
    oggi: LocalDate = LocalDate.now(zona),
): String {
    val locale = istante.atZone(zona)
    return if (locale.toLocalDate() == oggi) {
        parole.testo(R.string.quando_alle_oggi, formatoOra.format(locale))
    } else {
        parole.testo(R.string.quando_alle_giorno, formatoGiornoBreve.format(locale), formatoOra.format(locale))
    }
}

/** "dalle 15:10" se è oggi, "dal 22/09 alle 15:10" un altro giorno — nel fuso del telefono. */
fun dalleQuando(
    parole: Parole,
    istante: Instant,
    zona: ZoneId = ZoneId.systemDefault(),
    oggi: LocalDate = LocalDate.now(zona),
): String {
    val locale = istante.atZone(zona)
    return if (locale.toLocalDate() == oggi) {
        parole.testo(R.string.quando_dalle_oggi, formatoOra.format(locale))
    } else {
        parole.testo(R.string.quando_dal_giorno, formatoGiornoBreve.format(locale), formatoOra.format(locale))
    }
}

/** Il nome di un dispositivo; senza nome, il suo tipo ("Telefono", "Computer"). */
fun nomeDelDispositivo(parole: Parole, nome: String?, tipo: String): String =
    nome?.trim()?.takeIf { it.isNotEmpty() }
        ?: when (tipo) {
            TipiDispositivo.COMPUTER -> parole.testo(R.string.dispositivo_computer)
            TipiDispositivo.TELEFONO -> parole.testo(R.string.dispositivo_telefono)
            else -> parole.testo(R.string.dispositivo_senza_nome)
        }

@Composable
fun nomeDelDispositivo(dispositivo: VistaDispositivo): String =
    nomeDelDispositivo(parole(), dispositivo.nome, dispositivo.tipo)

/**
 * La riga di stato di un dispositivo, dai flag del SERVER: "In contatto —
 * ultimo aggiornamento alle 15:10", "Spento dalle 23:10", "Nessun
 * aggiornamento dalle 15:10"… Un orario che non si legge non si inventa: si
 * dice la frase senza orario.
 *
 * Un dispositivo "spento" da più di 24 ore ([spentoALungo]) non si dice spento:
 * "Nessun dato dal computer dal 23/09 alle 22:10: spento, oppure Pactum non è
 * partito" ([computer]; (0.14, contratto v3.7) per un telefono "dal telefono").
 * Da quando: dallo spegnimento, o, se il server non lo dice, dall'ultimo battito.
 */
fun testoStatoCanale(
    parole: Parole,
    stato: StatoCanale,
    silenzio: StatoSilenzio?,
    zona: ZoneId = ZoneId.systemDefault(),
    oggi: LocalDate = LocalDate.now(zona),
    adesso: Instant = Instant.now(),
    computer: Boolean = false,
): String {
    val battito = istanteServer(silenzio?.ultimoBattito)
    return when (stato) {
        StatoCanale.IN_CONTATTO -> battito
            ?.let { parole.testo(R.string.dispositivo_in_contatto, alleQuando(parole, it, zona, oggi)) }
            ?: parole.testo(R.string.dispositivo_in_contatto_senza_ora)
        StatoCanale.SPENTO -> {
            val dal = istanteServer(silenzio?.spentoDal)
            val ultimoSegno = dal ?: battito
            when {
                ultimoSegno != null && spentoALungo(ultimoSegno, adesso) -> parole.testo(
                    if (computer) R.string.dispositivo_spento_a_lungo else R.string.dispositivo_spento_a_lungo_telefono,
                    dalleQuando(parole, ultimoSegno, zona, oggi),
                )
                dal != null -> parole.testo(R.string.dispositivo_spento, dalleQuando(parole, dal, zona, oggi))
                else -> parole.testo(R.string.dispositivo_spento_senza_ora)
            }
        }
        StatoCanale.SILENTE -> battito
            ?.let { parole.testo(R.string.dispositivo_silente, dalleQuando(parole, it, zona, oggi)) }
            ?: parole.testo(R.string.dispositivo_mai_sentito)
        StatoCanale.MAI_SENTITO -> parole.testo(R.string.dispositivo_mai_sentito)
        StatoCanale.DA_COLLEGARE -> parole.testo(R.string.dispositivo_da_collegare)
        StatoCanale.SCOLLEGATO -> parole.testo(R.string.dispositivo_scollegato)
        StatoCanale.SCONOSCIUTO -> parole.testo(R.string.dispositivo_stato_sconosciuto)
    }
}

/**
 * L'avviso di sistema della vedetta per UN dispositivo (v3). Di chi è lo dice la
 * riga sopra il titolo ([etichettaDi]); qui il fatto, detto per il tipo di
 * dispositivo. null = niente da avvisare ([CambioSilenzio.BASE], [CambioSilenzio.NESSUNO]).
 * Un dispositivo che risulta spento da più di 24 ore ([spentoALungo]) non si dice
 * spento, né "non è un'interruzione": non lo si sa.
 *
 * (0.14, contratto v3.7) Il silenzio non accusa ("…può essere senza rete o
 * scarico, oppure Pactum è stato fermato"), e anche un telefono può risultare
 * spento dopo un avviso di silenzio: "Il telefono era spento dalle 15:10: non è
 * un'interruzione".
 */
fun testoAvvisoSilenzio(
    parole: Parole,
    cambio: CambioSilenzio,
    computer: Boolean,
    silenzio: StatoSilenzio?,
    zona: ZoneId = ZoneId.systemDefault(),
    oggi: LocalDate = LocalDate.now(zona),
    adesso: Instant = Instant.now(),
): TestoNotifica? {
    val battito = istanteServer(silenzio?.ultimoBattito)
    return when (cambio) {
        CambioSilenzio.BASE, CambioSilenzio.NESSUNO -> null
        CambioSilenzio.NUOVO_SILENZIO -> TestoNotifica(
            parole.testo(R.string.notifica_silenzio_titolo),
            if (battito == null) {
                parole.testo(R.string.notifica_silenzio_mai_dispositivo)
            } else {
                parole.testo(
                    if (computer) R.string.notifica_silenzio_computer else R.string.notifica_silenzio_telefono,
                    dalleQuando(parole, battito, zona, oggi),
                )
            },
        )
        CambioSilenzio.CONTATTO_TORNATO -> TestoNotifica(
            parole.testo(R.string.notifica_contatto_titolo),
            parole.testo(
                if (computer) R.string.notifica_contatto_computer else R.string.notifica_contatto_telefono,
                battito?.let { alleQuando(parole, it, zona, oggi) } ?: "—",
            ),
        )
        CambioSilenzio.SPENTO_DOPO_SILENZIO -> {
            val dal = istanteServer(silenzio?.spentoDal)
            val ultimoSegno = dal ?: battito
            when {
                ultimoSegno != null && spentoALungo(ultimoSegno, adesso) -> TestoNotifica(
                    parole.testo(
                        if (computer) R.string.notifica_spento_a_lungo_titolo else R.string.notifica_spento_a_lungo_titolo_telefono,
                    ),
                    parole.testo(
                        if (computer) R.string.notifica_spento_a_lungo else R.string.notifica_spento_a_lungo_telefono,
                        dalleQuando(parole, ultimoSegno, zona, oggi),
                    ),
                )
                computer -> TestoNotifica(
                    parole.testo(R.string.tipo_computer_spento),
                    dal?.let { parole.testo(R.string.notifica_spento_dopo_silenzio, dalleQuando(parole, it, zona, oggi)) }
                        ?: parole.testo(R.string.notifica_spento_dopo_silenzio_senza_ora),
                )
                else -> TestoNotifica(
                    parole.testo(R.string.tipo_telefono_spento),
                    dal?.let { parole.testo(R.string.notifica_telefono_era_spento, dalleQuando(parole, it, zona, oggi)) }
                        ?: parole.testo(R.string.notifica_telefono_era_spento_senza_ora),
                )
            }
        }
    }
}

/**
 * La riga del dialogo "Nuovo dispositivo" quando il figlio ha già un dispositivo
 * attivo di quel tipo ([presenti], v. dispositiviDelloStessoTipo): "Andrea ha
 * già «Telefono». Se è lo stesso telefono da ricollegare, usa «Nuovo codice»
 * sulla sua riga: così regole e storia restano insieme." null se non ne ha.
 * Non impedisce niente: un secondo telefono vero si aggiunge lo stesso.
 */
fun avvisoDispositivoGiaPresente(
    parole: Parole,
    nomeFiglio: String,
    tipo: String,
    presenti: List<Dispositivo>,
): String? {
    if (presenti.isEmpty()) return null
    val nomi = elencoTraVirgolette(parole, presenti.map { nomeDelDispositivo(parole, it.nome, it.tipo) })
    return parole.testo(
        if (tipo == TipiDispositivo.COMPUTER) {
            R.string.famiglia_gia_presente_computer
        } else {
            R.string.famiglia_gia_presente_telefono
        },
        nomeFiglio,
        nomi,
    )
}

/** «Telefono» · «Telefono» e «Vecchio» · «A», «B» e «C». */
fun elencoTraVirgolette(parole: Parole, nomi: List<String>): String {
    val tra = nomi.map { parole.testo(R.string.elenco_nome, it) }
    if (tra.size <= 1) return tra.firstOrNull().orEmpty()
    return parole.testo(R.string.elenco_ultimo, tra.dropLast(1).joinToString(", "), tra.last())
}

// --- Il digest della sera ----------------------------------------------------------

/** Oltre questi minuti dall'ultima fotografia, il totale del digest è un parziale e va detto. */
const val SOGLIA_FRESCHEZZA_DIGEST_MIN = 90L

/** Quante app (o programmi) entrano nel digest per dispositivo: il dettaglio vive nel Tempo. */
private const val APP_NEL_DIGEST = 3

/**
 * Il digest giornaliero di UN figlio: titolo e testo, dalla fotografia di oggi
 * di ciascun suo dispositivo (l'ultima voce di `uso_recente`).
 *
 * - Un telefono solo (o server 0.7): come la 0.7 — "Oggi: 3 h" e le prime app.
 * - Più dispositivi, o un computer: il titolo mette in fila i totali
 *   ("Oggi: Telefono 3 h · Computer 2 h"), il testo una riga per dispositivo.
 * - (v3.3) Un limite su tutto il dispositivo sta accanto al suo totale, come
 *   quello di un'app: "Oggi: 3 h 20 min (limite 3 h)".
 * Un oggi senza fotografia lo dice, MAI uno zero finto; una fotografia ferma
 * da più di 90 minuti è un parziale, e lo si scrive.
 * I dispositivi scollegati o non ancora collegati non entrano: non mandano dati.
 */
fun testoDigest(parole: Parole, dispositivi: List<VistaDispositivo>, adesso: Instant = Instant.now()): TestoNotifica {
    val attivi = dispositivi.filter { !it.revocato && it.abbinato }
    val solo = attivi.singleOrNull()
    if (attivi.isEmpty() || (solo != null && !solo.computer)) {
        return digestUnTelefono(parole, solo?.usoRecente?.lastOrNull(), adesso)
    }
    val parti = attivi.map { dispositivo ->
        val nome = nomeDelDispositivo(parole, dispositivo.nome, dispositivo.tipo)
        val uso = dispositivo.usoRecente.lastOrNull()
        if (uso?.totaleMinuti == null) {
            parole.testo(R.string.digest_parte_dispositivo_nessun_dato, nome)
        } else {
            parteDispositivo(parole, nome, uso, uso.totaleMinuti)
        }
    }
    val righe = attivi.map { dispositivo ->
        val nome = nomeDelDispositivo(parole, dispositivo.nome, dispositivo.tipo)
        val uso = dispositivo.usoRecente.lastOrNull()
        if (uso?.totaleMinuti == null) {
            parole.testo(R.string.digest_riga_dispositivo_nessun_dato, nome)
        } else {
            val prime = primeApp(parole, uso)
            if (prime.isEmpty()) {
                parteDispositivo(parole, nome, uso, uso.totaleMinuti)
            } else {
                parole.testo(R.string.digest_riga_dispositivo, nome, prime)
            }
        }
    }
    val freschezza = attivi.mapNotNull { dispositivo ->
        val istante = istanteServer(dispositivo.usoRecente.lastOrNull()?.aggiornatoTs) ?: return@mapNotNull null
        if (Duration.between(istante, adesso).toMinutes() <= SOGLIA_FRESCHEZZA_DIGEST_MIN) return@mapNotNull null
        parole.testo(
            R.string.digest_freschezza_dispositivo,
            nomeDelDispositivo(parole, dispositivo.nome, dispositivo.tipo),
            oraOppureDataOra(istante),
        )
    }
    val testo = (righe + freschezza + parole.testo(R.string.digest_tocca)).joinToString("\n")
    return TestoNotifica(parole.testo(R.string.digest_titolo, parti.joinToString(" · ")), testo)
}

/**
 * "Telefono 3 h 20 min", e (v3.3) "Telefono 3 h 20 min (limite 3 h)" quando il
 * dispositivo ha un limite sul totale: il limite accanto al totale, come per le
 * app ("TikTok 1 h (limite 1 h)"). Col bonus concesso oggi su quella regola il
 * limite di oggi è la somma, come nel Tempo: "(limite 3 h + 15 min di bonus)".
 */
private fun parteDispositivo(parole: Parole, nome: String, uso: UsoGiorno, totale: Int): String {
    val durata = testoDurata(parole, totale.toLong())
    val limite = uso.limite
        ?: return parole.testo(R.string.digest_parte_dispositivo, nome, durata)
    val bonus = uso.bonus
    return if (bonus > 0) {
        parole.testo(
            R.string.digest_parte_dispositivo_con_limite_bonus,
            nome,
            durata,
            testoDurata(parole, limite.toLong()),
            testoDurata(parole, bonus.toLong()),
        )
    } else {
        parole.testo(R.string.digest_parte_dispositivo_con_limite, nome, durata, testoDurata(parole, limite.toLong()))
    }
}

/**
 * Il digest della 0.7: un telefono, il totale nel titolo (v3.3: col limite sul
 * totale, se c'è), le prime app nel testo.
 */
private fun digestUnTelefono(parole: Parole, uso: UsoGiorno?, adesso: Instant): TestoNotifica {
    val totale = uso?.totaleMinuti
        ?: return TestoNotifica(
            parole.testo(R.string.digest_titolo_nessun_dato),
            parole.testo(R.string.digest_testo_nessun_dato),
        )
    val limite = uso.limite
    val titolo = when {
        limite == null -> parole.testo(R.string.digest_titolo, testoDurata(parole, totale.toLong()))
        uso.bonus > 0 -> parole.testo(
            R.string.digest_titolo_con_limite_bonus,
            testoDurata(parole, totale.toLong()),
            testoDurata(parole, limite.toLong()),
            testoDurata(parole, uso.bonus.toLong()),
        )
        else -> parole.testo(
            R.string.digest_titolo_con_limite,
            testoDurata(parole, totale.toLong()),
            testoDurata(parole, limite.toLong()),
        )
    }
    val prime = primeApp(parole, uso)
    val corpo = if (prime.isEmpty()) {
        parole.testo(R.string.digest_tocca)
    } else {
        parole.testo(R.string.digest_testo, prime)
    }
    // Caveat di freschezza: se la fotografia è ferma da oltre 90 minuti, il
    // totale è un parziale — dillo, non spacciarlo per il consuntivo di oggi
    // (concept: il registro non mente, mai stantìo mostrato come corrente).
    val istante = istanteServer(uso.aggiornatoTs)
    val testo = if (istante != null &&
        Duration.between(istante, adesso).toMinutes() > SOGLIA_FRESCHEZZA_DIGEST_MIN
    ) {
        corpo + "\n" + parole.testo(R.string.digest_freschezza, oraOppureDataOra(istante))
    } else {
        corpo
    }
    return TestoNotifica(titolo, testo)
}

/**
 * "TikTok 1 h (limite 1 h) · YouTube 40 min": le app più usate del giorno, col
 * limite dove c'è — e (0.9) col bonus concesso oggi su quella regola, come nel
 * Tempo: "(limite 1 h + 15 min di bonus)".
 */
private fun primeApp(parole: Parole, uso: UsoGiorno): String =
    uso.app
        .sortedByDescending { it.minuti }
        .take(APP_NEL_DIGEST)
        .joinToString(" · ") { app ->
            val nome = nomeLeggibile(app.chiave, app.nome)
            val durata = testoDurata(parole, app.minuti.toLong())
            val limite = app.limite
            when {
                limite == null -> parole.testo(R.string.digest_app, nome, durata)
                app.bonus > 0 -> parole.testo(
                    R.string.digest_app_con_limite_bonus,
                    nome,
                    durata,
                    testoDurata(parole, limite.toLong()),
                    testoDurata(parole, app.bonus.toLong()),
                )
                else -> parole.testo(R.string.digest_app_con_limite, nome, durata, testoDurata(parole, limite.toLong()))
            }
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
 * (v3) Che cosa dire quando il server rifiuta un gesto sulla famiglia (un
 * figlio, un dispositivo, un codice). Ogni rifiuto col suo motivo vero; un
 * codice che non si conosce dice solo che il server non ha accettato — non si
 * inventa un perché. [secondi] vale per il 429: quanto aspettare.
 */
fun messaggioRifiutoFamiglia(
    parole: Parole,
    codice: String?,
    secondi: Long?,
    suGenitore: Boolean = false,
): String = when (codice) {
    CodiciErrore.TROPPI_TENTATIVI -> if (secondi != null && secondi > 0) {
        parole.testo(R.string.famiglia_errore_troppi_tentativi_tra, attesaInMinuti(secondi))
    } else {
        parole.testo(R.string.famiglia_errore_troppi_tentativi)
    }
    // (0.13) Un genitore che non c'è più ha la sua frase.
    CodiciErrore.NON_TROVATO ->
        parole.testo(if (suGenitore) R.string.famiglia_errore_genitore_non_trovato else R.string.famiglia_errore_non_trovato)
    // (0.13) I rifiuti sui genitori (contratto v3.6), e il server che non li conosce.
    CodiciErrore.NON_TE_STESSO -> parole.testo(R.string.famiglia_errore_non_te_stesso)
    CodiciErrore.ULTIMO_GENITORE -> parole.testo(R.string.famiglia_errore_ultimo_genitore)
    CodiciErrore.TROPPI_GENITORI -> parole.testo(R.string.famiglia_errore_troppi_genitori)
    CodiciErrore.GENITORE_REVOCATO -> parole.testo(R.string.famiglia_errore_genitore_revocato)
    CodiciErrore.SERVER_DA_AGGIORNARE -> parole.testo(R.string.genitori_server_vecchio)
    // (0.13) 401: il collegamento di QUESTO telefono non vale più. Non è la rete.
    CodiciErrore.COLLEGAMENTO_NON_VALIDO -> parole.testo(R.string.collegamento_non_valido)
    CodiciErrore.DISPOSITIVO_REVOCATO -> parole.testo(R.string.famiglia_errore_dispositivo_scollegato)
    CodiciErrore.NOME_NON_VALIDO, PostinoClient.PARAMETRI_NON_VALIDI ->
        parole.testo(R.string.famiglia_errore_nome, LUNGHEZZA_MASSIMA_NOME)
    CodiciErrore.CODICE_NON_VALIDO -> parole.testo(R.string.famiglia_errore_codice_non_valido)
    // La rete è caduta su una creazione e la famiglia non si è potuta rileggere:
    // riprovare alla cieca potrebbe fare un doppione.
    CodiciErrore.ESITO_INCERTO -> parole.testo(R.string.famiglia_errore_esito_incerto)
    null -> parole.testo(R.string.famiglia_errore_rete)
    else -> parole.testo(R.string.famiglia_errore_rifiutato)
}

/** I secondi d'attesa di un 429 in minuti interi, arrotondati per eccesso (mai "0 minuti"). */
fun attesaInMinuti(secondi: Long): Long = ((secondi + 59) / 60).coerceAtLeast(1)

/**
 * I rifiuti di una proposta che non si risolvono riprovando: la regola ha già
 * una proposta in attesa, o non è più attiva. Si chiude il dialogo e si rilegge,
 * così la scheda mostra com'è davvero.
 */
fun rifiutoPropostaDefinitivo(codice: String?): Boolean =
    codice == CodiciErrore.PROPOSTA_GIA_PENDENTE || codice == CodiciErrore.REGOLA_NON_VALIDA

/**
 * (0.10) Che cosa dire quando la risposta del genitore a una proposta del figlio
 * è arrivata. Un sì su un'eliminazione non "cambia" la regola: la toglie.
 */
@StringRes
fun messaggioDecisione(esito: String, eliminazione: Boolean): Int = when {
    esito != EsitiRisposta.ACCETTA -> R.string.decisione_rifiutata
    eliminazione -> R.string.decisione_accettata_eliminazione
    else -> R.string.decisione_accettata
}

/**
 * (0.10) Che cosa dire quando il server non prende la risposta a una proposta
 * del figlio, ciascun rifiuto col suo motivo vero. Con `ultima_regola` e
 * `dispositivo_revocato` la proposta resta in attesa (contratto v3.4): si dice,
 * e si dice che resta il no. `proposta_non_pendente` dice com'è finita davvero,
 * dalla rilettura ([statoFinale], v. [messaggioNonPiuInAttesa]). null = rete
 * caduta o un rifiuto che non si conosce: "riprova".
 */
@StringRes
fun messaggioRifiutoDecisione(codice: String?, statoFinale: String? = null): Int = when (codice) {
    CodiciErrore.PROPOSTA_NON_PENDENTE -> messaggioNonPiuInAttesa(statoFinale, propostaDelFiglio = true)
    CodiciErrore.ULTIMA_REGOLA -> R.string.decisione_errore_ultima_regola
    CodiciErrore.DISPOSITIVO_REVOCATO -> R.string.decisione_errore_dispositivo_scollegato
    CodiciErrore.NON_TROVATO -> R.string.decisione_errore_non_trovata
    CodiciErrore.SERVER_DA_AGGIORNARE -> R.string.decisione_errore_server_da_aggiornare
    else -> R.string.decisione_errore_generico
}

/**
 * (0.10) Che cosa dire quando il ritiro di una proposta del genitore non passa.
 * Un server più vecchio della v3.4 non conosce il ritiro: non è un errore da
 * riprovare, serve aggiornare il server. `proposta_non_pendente` come nella
 * risposta: com'è finita davvero.
 */
@StringRes
fun messaggioRifiutoRitiro(codice: String?, statoFinale: String? = null): Int = when (codice) {
    CodiciErrore.PROPOSTA_NON_PENDENTE -> messaggioNonPiuInAttesa(statoFinale, propostaDelFiglio = false)
    CodiciErrore.SERVER_DA_AGGIORNARE -> R.string.ritiro_errore_server_da_aggiornare
    CodiciErrore.NON_TROVATO -> R.string.ritiro_errore_non_trovata
    else -> R.string.ritiro_errore_generico
}

/**
 * (0.10) Una proposta che il server dice non più in attesa (`proposta_non_pendente`):
 * com'è finita, dallo stato letto subito dopo ([statoFinale]). I motivi sono tanti
 * — un primo tentativo arrivato anche se la risposta si è persa, l'altro genitore,
 * il figlio che l'ha ritirata o ha tolto la regola — e un "forse" sarebbe spesso
 * falso. Stato che non si sa (rilettura non riuscita, proposta non trovata): una
 * frase che non sceglie. [propostaDelFiglio] = a una sua proposta rispondi tu;
 * a una tua risponde lui.
 */
@StringRes
fun messaggioNonPiuInAttesa(statoFinale: String?, propostaDelFiglio: Boolean): Int = when (statoFinale) {
    StatiProposta.ACCETTATA ->
        if (propostaDelFiglio) R.string.non_pendente_accettata else R.string.non_pendente_tua_accettata
    StatiProposta.RIFIUTATA ->
        if (propostaDelFiglio) R.string.non_pendente_rifiutata else R.string.non_pendente_tua_rifiutata
    StatiProposta.RITIRATA ->
        if (propostaDelFiglio) R.string.non_pendente_ritirata_dal_figlio else R.string.non_pendente_tua_ritirata
    StatiProposta.ANNULLATA -> R.string.non_pendente_annullata
    else -> R.string.non_pendente_generico
}

// --- Le proposte del figlio (0.10) -----------------------------------------------

/** Il nome del figlio da scrivere in una frase; null se non si sa o è vuoto (le frasi dicono "tuo figlio"). */
fun nomeDaScrivere(nome: String?): String? = nome?.trim()?.takeIf { it.isNotEmpty() }

/** "Luca ti propone:", o "Tuo figlio ti propone:" se il nome non si sa. */
fun chiTiPropone(parole: Parole, nomeFiglio: String?): String =
    nomeDaScrivere(nomeFiglio)?.let { parole.testo(R.string.proposta_ti_propone, it) }
        ?: parole.testo(R.string.proposta_ti_propone_senza_nome)

/**
 * Che cosa chiede una proposta, detto come le regole: coi parametri PROPOSTI
 * ("TikTok: al massimo 1 h 30 min al giorno", "Tutto il computer: …", "Social:
 * …"), oppure "Togliere la regola «TikTok: al massimo 1 h al giorno»" per
 * un'eliminazione. null se la regola non si conosce (finestra non arrivata) o
 * se la proposta non porta i parametri: meglio niente che "?: al massimo 0 min".
 *
 * Un limite che cambia app (o categoria, o totale) dice la nuova col suo nome
 * ([bersaglioDellaProposta], coi [nomi] che la finestra conosce): mai un pacchetto.
 */
fun descrizioneProposta(
    parole: Parole,
    proposta: Proposta,
    regola: RegolaFinestra?,
    nomi: Map<String, String> = emptyMap(),
): String? {
    if (regola == null) return null
    if (eliminazione(proposta)) {
        return parole.testo(R.string.proposta_togliere_regola, descrizioneRegola(parole, regola))
    }
    val parametri = proposta.parametriProposti
    if (parametri.isEmpty()) return null
    if (regola.tipo != TipiRegola.LIMITE_TEMPO) return descrizioneParametri(parole, regola, parametri)
    return parole.testo(
        R.string.regola_limite_tempo,
        bersaglioDellaProposta(parole, campo(parametri, "app_o_categoria"), nomiColNomeDellaRegola(regola, nomi), regola.dispositivo?.tipo),
        testoDurata(parole, campo(parametri, "minuti_al_giorno")?.toLongOrNull() ?: 0),
    )
}

/**
 * Il bersaglio di un limite di tempo in una proposta, sempre a parole:
 * - il totale: "Tutto il telefono" / "Tutto il computer";
 * - una categoria col suo nome ("Social", "Altre app");
 * - un'app o un programma col nome che la finestra conosce ([nomi]: fotografie e
 *   regole); un programma senza nome come "minecraft.exe (programma)", un sito come
 *   "youtube.com (sito)" — dentro una frase solo "minecraft.exe", "youtube.com";
 * - un'app di cui non si sa il nome: "Un'altra app". Mai il pacchetto.
 * [inFrase] = dentro una frase ("Da tutto il telefono … a un'altra app …"): le
 * parole dell'app in minuscolo; i nomi restano come sono.
 */
fun bersaglioDellaProposta(
    parole: Parole,
    chiave: String?,
    nomi: Map<String, String>,
    tipoDispositivo: String?,
    inFrase: Boolean = false,
): String {
    val pulita = chiave?.trim().orEmpty()
    val computer = tipoDispositivo == TipiDispositivo.COMPUTER
    return when {
        eTotale(pulita) -> parole.testo(
            when {
                computer && inFrase -> R.string.bersaglio_totale_computer_in_frase
                computer -> R.string.bersaglio_totale_computer
                inFrase -> R.string.bersaglio_totale_telefono_in_frase
                else -> R.string.bersaglio_totale_telefono
            },
        )
        pulita.startsWith("categoria:") -> etichettaCategoria(pulita)
        else -> nomeVero(pulita, nomi[pulita])
            ?: when {
                pulita.startsWith("sito:") && pulita.length > "sito:".length ->
                    if (inFrase) pulita.removePrefix("sito:") else etichettaAppOCategoria(pulita)
                pulita.startsWith("exe:") && pulita.length > "exe:".length ->
                    if (inFrase) pulita.removePrefix("exe:") else etichettaAppOCategoria(pulita)
                else -> parole.testo(if (inFrase) R.string.bersaglio_altra_app_in_frase else R.string.bersaglio_altra_app)
            }
    }
}

/** I [nomi] della finestra, più quello che la regola porta per la sua app (il `nome` della finestra). */
private fun nomiColNomeDellaRegola(regola: RegolaFinestra, nomi: Map<String, String>): Map<String, String> {
    val chiave = campo(regola.parametri, "app_o_categoria")?.trim() ?: return nomi
    val nome = nomeVero(chiave, regola.nome) ?: return nomi
    return nomi + (chiave to nome)
}

/**
 * Un cambio di bersaglio raccontato dall'app, con le parole delle persone: "Da
 * TikTok (1 h) a Instagram (1 h) al giorno", "Da Social (2 h) a un'altra app (1 h)
 * al giorno". Solo per un limite di tempo IN ATTESA che cambia app, categoria o
 * totale: il confronto di una pendente è sempre rispetto alla regola di adesso,
 * quella di una chiusa no. null negli altri casi.
 */
fun confrontoDiBersaglio(
    parole: Parole,
    proposta: Proposta,
    regola: RegolaFinestra,
    nomi: Map<String, String> = emptyMap(),
): String? {
    if (regola.tipo != TipiRegola.LIMITE_TEMPO || proposta.stato != StatiProposta.PENDENTE || eliminazione(proposta)) {
        return null
    }
    val prima = campo(regola.parametri, "app_o_categoria")?.trim() ?: return null
    val dopo = campo(proposta.parametriProposti, "app_o_categoria")?.trim() ?: return null
    if (prima == dopo || (eTotale(prima) && eTotale(dopo))) return null
    val minutiPrima = campo(regola.parametri, "minuti_al_giorno")?.toLongOrNull() ?: return null
    val minutiDopo = campo(proposta.parametriProposti, "minuti_al_giorno")?.toLongOrNull() ?: return null
    val tutti = nomiColNomeDellaRegola(regola, nomi)
    val tipo = regola.dispositivo?.tipo
    return parole.testo(
        R.string.proposta_confronto_bersaglio,
        bersaglioDellaProposta(parole, prima, tutti, tipo, inFrase = true),
        testoDurata(parole, minutiPrima),
        bersaglioDellaProposta(parole, dopo, tutti, tipo, inFrase = true),
        testoDurata(parole, minutiDopo),
    )
}

/**
 * true = un confronto del server che racconta un cambio di bersaglio ("da TikTok
 * (60 min) a Instagram (60 min) al giorno"): il server scrive i bersagli coi nomi,
 * ma con la chiave com'è quando un nome non è mai arrivato. L'app lo mostra solo
 * se non sa raccontarlo da sé.
 */
fun raccontaUnCambioDiBersaglio(confronto: String): Boolean =
    confronto.startsWith("da ") && confronto.endsWith(" al giorno")

/**
 * Su quale dispositivo vale la regola di una proposta, detto quando serve:
 * - una regola di un computer: "sul computer" (chi legge pensa al telefono, se
 *   non glielo si dice);
 * - con più dispositivi ([piuDispositivi]), anche una del telefono: "sul
 *   telefono"; e il nome, se il dispositivo ne ha uno suo: "sul computer
 *   «Computer di camera»".
 * null = non serve: la vita reale è del figlio, e un telefono solo è il caso di
 * sempre.
 */
fun doveValeLaRegola(parole: Parole, regola: RegolaFinestra?, piuDispositivi: Boolean): String? =
    doveSta(parole, regola?.dispositivo, piuDispositivi)

/**
 * Come [doveValeLaRegola], per un dispositivo qualunque: (0.11) anche per una
 * sessione, che è sempre di un telefono ("sul telefono «Telefono di Luca»" quando
 * il figlio ha più dispositivi). null = non serve, o il dispositivo non si sa.
 */
fun doveSta(parole: Parole, dispositivo: RiferimentoDispositivo?, piuDispositivi: Boolean): String? {
    if (dispositivo == null) return null
    val computer = dispositivo.tipo == TipiDispositivo.COMPUTER
    if (!computer && !piuDispositivi) return null
    val sul = parole.testo(if (computer) R.string.proposta_sul_computer else R.string.proposta_sul_telefono)
    val nome = dispositivo.nome.trim()
    val soloIlTipo = nome.isEmpty() || nome.equals(nomeDelDispositivo(parole, null, dispositivo.tipo), ignoreCase = true)
    return if (piuDispositivi && !soloIlTipo) parole.testo(R.string.proposta_sul_dispositivo_col_nome, sul, nome) else sul
}

/**
 * La riga principale di una proposta del figlio: che cosa chiede e, quando
 * serve, dove vale ("Minecraft: al massimo 2 h al giorno · sul computer"). Se
 * la regola non si conosce, il confronto del server (ma non un cambio di
 * bersaglio, che può avere le chiavi tecniche); null se non resta niente.
 */
fun rigaProposta(
    parole: Parole,
    proposta: Proposta,
    regola: RegolaFinestra?,
    piuDispositivi: Boolean,
    nomi: Map<String, String> = emptyMap(),
): String? {
    val cosa = descrizioneProposta(parole, proposta, regola, nomi)
        ?: return proposta.confronto.takeIf { it.isNotBlank() && !raccontaUnCambioDiBersaglio(it) }
    val dove = doveValeLaRegola(parole, regola, piuDispositivi) ?: return cosa
    return parole.testo(R.string.proposta_regola_e_dove, cosa, dove)
}

/**
 * Il confronto sotto la riga della proposta:
 * - un cambio di bersaglio in attesa: la frase dell'app, coi nomi ("Da TikTok
 *   (1 h) a Instagram (1 h) al giorno", [confrontoDiBersaglio]);
 * - se no il confronto del server ("+30 min al giorno rispetto ad ora"), la stessa
 *   frase che vede il figlio — ma non un cambio di bersaglio che l'app non sa
 *   raccontare (una proposta chiusa: la regola di adesso non è più quella di
 *   allora), perché il server ci scrive la chiave quando un nome non è mai
 *   arrivato. La riga dice già il bersaglio nuovo.
 * null anche quando non aggiunge niente: vuoto, già usato come riga principale
 * ([rigaProposta] senza la regola o senza i parametri) o un'eliminazione, che la
 * riga dice già ("Togliere la regola…").
 */
fun confrontoDaMostrare(
    parole: Parole,
    proposta: Proposta,
    regola: RegolaFinestra?,
    nomi: Map<String, String> = emptyMap(),
): String? {
    if (regola == null || eliminazione(proposta) || proposta.parametriProposti.isEmpty()) return null
    confrontoDiBersaglio(parole, proposta, regola, nomi)?.let { return it }
    return proposta.confronto.takeIf { it.isNotBlank() && !raccontaUnCambioDiBersaglio(it) }
}

/**
 * Chi ha fatto una proposta, per la storia: "Proposta tua" o "Proposta di Luca";
 * null per un autore che non si conosce. (0.13) Con più genitori (contratto v3.6)
 * una proposta di un altro genitore dice il suo nome: "Proposta di Mamma" ([io] =
 * chi sei tu; senza, e su un server più vecchio, è "tua").
 */
fun autoreProposta(
    parole: Parole,
    proposta: Proposta,
    nomeFiglio: String?,
    io: RiferimentoGenitore? = null,
): String? = when (proposta.autore) {
    AutoriProposta.GENITORE -> when (val chi = chiHaFatto(proposta.genitore, io)) {
        is ChiHaFatto.Altro -> parole.testo(R.string.proposta_autore_genitore, chi.nome)
        else -> parole.testo(R.string.proposta_autore_tua)
    }
    AutoriProposta.FIGLIO -> nomeDaScrivere(nomeFiglio)?.let { parole.testo(R.string.proposta_autore_figlio, it) }
        ?: parole.testo(R.string.proposta_autore_figlio_senza_nome)
    else -> null
}

/**
 * (0.13) La risposta a una proposta, in due righe: com'è finita e il perché (null
 * se non c'è). A una proposta del genitore risponde il figlio ("Il figlio ha
 * accettato"); a una del figlio risponde un genitore: tu ("Hai accettato") o un
 * altro ("Mamma ha accettato", dal `risposta_di` della v3.6). null = nessuna risposta.
 */
fun rispostaProposta(
    parole: Parole,
    proposta: Proposta,
    io: RiferimentoGenitore? = null,
): Pair<String, String?>? {
    val risposta = proposta.risposta ?: return null
    val accettata = risposta.esito == EsitiRisposta.ACCETTA
    val perche = risposta.motivazione?.trim()?.takeIf { it.isNotEmpty() }
    if (proposta.autore != AutoriProposta.FIGLIO) {
        return parole.testo(if (accettata) R.string.proposta_risposta_accettata else R.string.proposta_risposta_rifiutata) to
            perche?.let { parole.testo(R.string.proposta_risposta_motivazione, it) }
    }
    return when (val chi = chiHaFatto(proposta.rispostaDi, io)) {
        is ChiHaFatto.Altro -> parole.testo(
            if (accettata) R.string.proposta_risposta_genitore_accettata else R.string.proposta_risposta_genitore_rifiutata,
            chi.nome,
        ) to perche?.let { parole.testo(R.string.proposta_risposta_genitore_motivazione, chi.nome, it) }
        else -> parole.testo(if (accettata) R.string.proposta_tua_risposta_accettata else R.string.proposta_tua_risposta_rifiutata) to
            perche?.let { parole.testo(R.string.proposta_tua_risposta_motivazione, it) }
    }
}

/** Com'è finita una proposta, in una parola; uno stato che non si conosce resta com'è. */
fun testoStatoProposta(parole: Parole, stato: String): String = when (stato) {
    StatiProposta.PENDENTE -> parole.testo(R.string.proposta_stato_pendente)
    StatiProposta.ACCETTATA -> parole.testo(R.string.proposta_stato_accettata)
    StatiProposta.RIFIUTATA -> parole.testo(R.string.proposta_stato_rifiutata)
    StatiProposta.ANNULLATA -> parole.testo(R.string.proposta_stato_annullata)
    StatiProposta.RITIRATA -> parole.testo(R.string.proposta_stato_ritirata)
    else -> stato
}

/**
 * Nella scelta del figlio in cima: quante sue proposte aspettano il genitore
 * ("1 da decidere"); null se nessuna.
 */
fun testoDaDecidere(parole: Parole, quante: Int): String? =
    if (quante > 0) parole.testo(R.string.figlio_da_decidere, quante) else null

// --- Le sessioni (0.11) -----------------------------------------------------------

/** Il nome di una sessione da scrivere fra «»; senza nome (non dovrebbe succedere) "Sessione". */
fun nomeSessione(parole: Parole, nome: String?): String =
    nome?.trim()?.takeIf { it.isNotEmpty() } ?: parole.testo(R.string.sessione_senza_nome)

/**
 * (0.12) L'emoji da mettere accanto al nome di una sessione: la prima del suo tema,
 * scelto dal nome come nell'app del figlio (TemaSessione, core-design): 📚 per
 * "Studio", ⚽ per "Calcio", ✨ per un nome che non si riconosce. null se il nome
 * comincia già con quell'emoji ("📚 Ripasso"): due di fila sembrerebbero un errore.
 */
fun emojiSessione(nome: String?): String? {
    val emoji = TemaSessione.daNome(nome.orEmpty()).emojiPrincipale
    // Il selettore di variante (U+FE0F) non conta: con o senza, ⚽ resta la stessa emoji.
    fun senzaVariante(testo: String) = testo.replace("\uFE0F", "")
    return emoji.takeUnless { senzaVariante(nome.orEmpty().trim()).startsWith(senzaVariante(it)) }
}

/** (0.12) Il nome di una sessione con la sua emoji, in testa a una riga: "📚 Studio". */
fun nomeSessioneConEmoji(parole: Parole, nome: String?): String =
    conEmojiDellaSessione(parole, nome, nomeSessione(parole, nome))

/**
 * (0.12) Il nome di una sessione dentro una frase: fra «», con l'emoji fuori, così
 * fra le virgolette resta il nome esatto scritto dal figlio: "📚 «Studio»".
 */
fun nomeSessioneTraVirgolette(parole: Parole, nome: String?): String =
    conEmojiDellaSessione(parole, nome, parole.testo(R.string.sessione_nome_tra_virgolette, nomeSessione(parole, nome)))

private fun conEmojiDellaSessione(parole: Parole, nome: String?, scritto: String): String =
    emojiSessione(nome)?.let { parole.testo(R.string.sessione_con_emoji, it, scritto) } ?: scritto

/** Il nome nuovo chiesto da un cambio: "Nuovo nome: 📚 «Compiti»". */
fun testoNuovoNome(parole: Parole, nome: String): String =
    parole.testo(R.string.sessione_nuovo_nome, nomeSessioneTraVirgolette(parole, nome))

/**
 * "Luca chiede di approvare la sessione 📚 «Studio»", o per un cambio a una sessione
 * già approvata "Luca chiede di cambiare la sessione 📚 «Studio»"; "Tuo figlio…" se il
 * nome non si sa. Lo stesso titolo nella card della Panoramica e nella notifica.
 */
fun chiedeLaSessione(parole: Parole, nomeFiglio: String?, nome: String?, cambio: Boolean): String {
    val chi = nomeDaScrivere(nomeFiglio)
    val sessione = nomeSessioneTraVirgolette(parole, nome)
    return when {
        cambio && chi != null -> parole.testo(R.string.sessione_chiede_cambiare, chi, sessione)
        cambio -> parole.testo(R.string.sessione_chiede_cambiare_senza_nome, sessione)
        chi != null -> parole.testo(R.string.sessione_chiede_approvare, chi, sessione)
        else -> parole.testo(R.string.sessione_chiede_approvare_senza_nome, sessione)
    }
}

/** "Se approvi, Luca potrà avviarla quando vuole, per quanto vuole." (o "tuo figlio"). */
fun seApproviLaSessione(parole: Parole, nomeFiglio: String?): String =
    nomeDaScrivere(nomeFiglio)?.let { parole.testo(R.string.sessione_se_approvi, it) }
        ?: parole.testo(R.string.sessione_se_approvi_senza_nome)

/**
 * Un'app a parole: il suo nome e, se la sessione la chiama in un altro modo, anche
 * quello: "TikTok (nella sessione si chiama «Calcolatrice»)".
 */
fun testoNomeApp(parole: Parole, app: NomeApp): String =
    app.nellaSessione?.let { parole.testo(R.string.sessione_app_chiamata, app.nome, it) } ?: app.nome

/**
 * Le app di una sessione in una riga, fuori dalla card che le spiega (notifiche,
 * "Restano", sessioni approvate): "ClasseViva, Classroom e le app installate fuori
 * dal Play Store", "ClasseViva e 2 app senza nome". Un'app che la sessione chiama
 * in un altro modo lo dice ([testoNomeApp]). Al massimo [APP_VISIBILI] nomi, poi
 * "altre N app". In testa a una riga ([inFrase] false) il gruppo da solo comincia
 * con la maiuscola. Mai una chiave tecnica, mai la parola "APK". null = nessuna app.
 */
fun elencoAppSessione(parole: Parole, app: AppDellaSessione, inFrase: Boolean = false): String? {
    if (!inFrase && app.gruppoApk && app.nomi.isEmpty() && app.senzaNome.isEmpty()) {
        return parole.testo(R.string.sessione_app_fuori_store)
    }
    val visibili = app.nomi.take(APP_VISIBILI)
    val altre = app.nomi.size - visibili.size
    val parti = buildList {
        visibili.forEach { add(testoNomeApp(parole, it)) }
        if (altre > 0) add(parole.testo(R.string.sessione_altre_app, altre))
        if (app.senzaNome.isNotEmpty()) add(parole.testo(R.string.sessione_app_senza_nome, app.senzaNome.size))
        if (app.gruppoApk) add(parole.testo(R.string.sessione_app_fuori_store_in_frase))
    }
    return when (parti.size) {
        0 -> null
        1 -> parti.single()
        else -> parole.testo(R.string.elenco_ultimo, parti.dropLast(1).joinToString(", "), parti.last())
    }
}

/**
 * Un'app che resta nella sessione ma cambia nome: "«Classe Viva» → «ClasseViva»".
 * Se i dati d'uso del telefono la chiamano in un altro modo ancora, prima di tutto
 * quello, perché un nome nuovo non nasconda l'app vera: "TikTok: «TikTok» →
 * «Calcolatrice»".
 */
fun testoNomeCambiato(parole: Parole, cambiato: NomeCambiato, nomiFinestra: Map<String, String> = emptyMap()): String {
    val daA = parole.testo(R.string.sessione_nome_app_da_a, cambiato.prima, cambiato.dopo)
    val dallUso = nomeSecondoLUso(cambiato.chiave, nomiFinestra)?.takeIf { !it.equals(cambiato.dopo, ignoreCase = true) }
    return dallUso?.let { parole.testo(R.string.sessione_nome_app_di, it, daA) } ?: daA
}

/**
 * Che cosa cambia un cambio chiesto su una sessione approvata, una riga per cosa:
 * "Nuovo nome: 📚 «Compiti»", "Aggiunge Duolingo e le app installate fuori dal Play
 * Store", "Toglie YouTube", "Cambiano solo i nomi delle app: «Classe Viva» →
 * «ClasseViva»" (o "Cambiano anche i nomi…" insieme ad altro). Un cambio che non
 * cambia niente lo dice. I nomi delle app dai dati d'uso ([nomiFinestra]), poi
 * dalla sessione e dal cambio. Vuota se non è un cambio.
 */
fun righeCambioSessione(
    parole: Parole,
    richiesta: SessioneDaApprovare,
    nomiFinestra: Map<String, String> = emptyMap(),
): List<String> {
    val differenze = richiesta.differenze ?: return emptyList()
    if (differenze.vuoto) return listOf(parole.testo(R.string.sessione_non_cambia_niente))
    fun elenco(chiavi: List<String>): String? =
        elencoAppSessione(parole, appDellaSessione(chiavi, richiesta.nomi, nomiFinestra), inFrase = true)
    val nomiCambiati = differenze.nomiCambiati
        .takeIf { it.isNotEmpty() }
        ?.joinToString(", ") { testoNomeCambiato(parole, it, nomiFinestra) }
        ?.let {
            parole.testo(
                if (differenze.soloNomi) R.string.notifica_sessione_solo_nomi else R.string.notifica_sessione_anche_nomi,
                it,
            )
        }
    return listOfNotNull(
        differenze.nuovoNome?.let { testoNuovoNome(parole, it) },
        elenco(differenze.aggiunte)?.let { parole.testo(R.string.notifica_sessione_aggiunge, it) },
        elenco(differenze.tolte)?.let { parole.testo(R.string.notifica_sessione_toglie, it) },
        nomiCambiati,
    )
}

/**
 * Una sessione fatta in una riga, negli orari del telefono: "📚 Studio · oggi
 * 15:02–16:40 · chiusa prima (prevista 2 h)", "📚 Studio · ieri 15:00–17:00 · 2 h";
 * una in corso con l'inizio e la durata scelta: "✨ Lavoro · iniziata alle 15:02 · 2
 * h, fino alle 17:02". Senza la fine vera (un dato che manca) "oggi dalle 15:02";
 * senza la durata prevista "chiusa prima" e basta. Mai un orario inventato.
 */
fun testoSessioneSvolta(
    parole: Parole,
    sessione: SessioneRaccontata,
    zona: ZoneId = ZoneId.systemDefault(),
    oggi: LocalDate = LocalDate.now(zona),
): String {
    val nome = nomeSessioneConEmoji(parole, sessione.svolta.nome)
    if (sessione.fine == FineSessione.IN_CORSO) {
        val iniziata = iniziataQuando(parole, sessione.inizio, zona, oggi)
        val finePrevista = sessione.finePrevista
        val durata = sessione.durataPrevista
        if (finePrevista == null || durata == null) {
            return parole.testo(R.string.sessione_svolta_in_corso_senza_fine, nome, iniziata)
        }
        return parole.testo(
            R.string.sessione_svolta_in_corso,
            nome,
            iniziata,
            testoDurata(parole, durata),
            finoAlle(parole, finePrevista, zona, oggi),
        )
    }
    val quando = quandoSessione(parole, sessione.inizio, sessione.finitaAlle, zona, oggi)
    val prevista = sessione.durataPrevista?.let { testoDurata(parole, it) }
    return when (sessione.fine) {
        FineSessione.CHIUSA_PRIMA -> if (prevista != null) {
            parole.testo(R.string.sessione_svolta_chiusa_prima, nome, quando, prevista)
        } else {
            parole.testo(R.string.sessione_svolta_chiusa_prima_senza_durata, nome, quando)
        }
        FineSessione.COMPLETA -> {
            val durata = prevista
                ?: sessione.finitaAlle
                    ?.let { Duration.between(sessione.inizio, it).toMinutes() }
                    ?.takeIf { it > 0 }
                    ?.let { testoDurata(parole, it) }
            if (durata != null) {
                parole.testo(R.string.sessione_svolta_completa, nome, quando, durata)
            } else {
                parole.testo(R.string.sessione_svolta, nome, quando)
            }
        }
        else -> parole.testo(R.string.sessione_svolta, nome, quando)
    }
}

/**
 * Quando è stata una sessione: "oggi 15:02–16:40", "ieri 23:30 – oggi 00:45", "29/09
 * 15:00–17:00"; senza la fine (o con una fine prima dell'inizio) "oggi dalle 15:02".
 */
fun quandoSessione(
    parole: Parole,
    inizio: Instant,
    fine: Instant?,
    zona: ZoneId = ZoneId.systemDefault(),
    oggi: LocalDate = LocalDate.now(zona),
): String {
    val da = inizio.atZone(zona)
    val giorno = giornoDellaSessione(parole, da.toLocalDate(), oggi)
    if (fine == null || fine.isBefore(inizio)) {
        return parole.testo(R.string.sessione_dalle, giorno, formatoOra.format(da))
    }
    val a = fine.atZone(zona)
    return if (a.toLocalDate() == da.toLocalDate()) {
        parole.testo(R.string.sessione_ore, giorno, formatoOra.format(da), formatoOra.format(a))
    } else {
        parole.testo(
            R.string.sessione_ore_due_giorni,
            giorno,
            formatoOra.format(da),
            giornoDellaSessione(parole, a.toLocalDate(), oggi),
            formatoOra.format(a),
        )
    }
}

/** "iniziata alle 15:02" oggi, "iniziata ieri alle 23:30", "iniziata il 29/09 alle 15:02". */
fun iniziataQuando(
    parole: Parole,
    inizio: Instant,
    zona: ZoneId = ZoneId.systemDefault(),
    oggi: LocalDate = LocalDate.now(zona),
): String {
    val locale = inizio.atZone(zona)
    val ora = formatoOra.format(locale)
    return when (locale.toLocalDate()) {
        oggi -> parole.testo(R.string.sessione_iniziata_alle, ora)
        oggi.minusDays(1) -> parole.testo(R.string.sessione_iniziata_ieri, ora)
        else -> parole.testo(R.string.sessione_iniziata_il, formatoGiornoBreve.format(locale), ora)
    }
}

/** "oggi", "ieri", oppure "29/09". */
private fun giornoDellaSessione(parole: Parole, data: LocalDate, oggi: LocalDate): String = when (data) {
    oggi -> parole.testo(R.string.sessione_oggi)
    oggi.minusDays(1) -> parole.testo(R.string.sessione_ieri)
    else -> formatoGiornoBreve.format(data)
}

/**
 * Quando il figlio ha chiesto un cambio ([richiestaTs], il `richiesta_ts` del
 * cambio), negli orari del telefono: "chiesto alle 15:02" oggi, "chiesto ieri alle
 * 15:02", "chiesto il 28/09 alle 15:02". null se l'orario non c'è o non si legge.
 */
fun quandoChiesta(
    parole: Parole,
    richiestaTs: String?,
    zona: ZoneId = ZoneId.systemDefault(),
    oggi: LocalDate = LocalDate.now(zona),
): String? {
    val locale = istanteServer(richiestaTs)?.atZone(zona) ?: return null
    val ora = formatoOra.format(locale)
    return when (locale.toLocalDate()) {
        oggi -> parole.testo(R.string.sessione_chiesto_alle, ora)
        oggi.minusDays(1) -> parole.testo(R.string.sessione_chiesto_ieri, ora)
        else -> parole.testo(R.string.sessione_chiesto_il, formatoGiornoBreve.format(locale), ora)
    }
}

/** "fino alle 18:30", "fino a domani alle 01:30", "fino al 03/10 alle 18:30". */
fun finoAlle(
    parole: Parole,
    istante: Instant,
    zona: ZoneId = ZoneId.systemDefault(),
    oggi: LocalDate = LocalDate.now(zona),
): String {
    val locale = istante.atZone(zona)
    val ora = formatoOra.format(locale)
    return when (locale.toLocalDate()) {
        oggi -> parole.testo(R.string.sessione_fino_alle, ora)
        oggi.plusDays(1) -> parole.testo(R.string.sessione_fino_a_domani, ora)
        else -> parole.testo(R.string.sessione_fino_al_giorno, formatoGiornoBreve.format(locale), ora)
    }
}

/**
 * Nel Tempo, sotto il totale del giorno, con le stesse parole dell'app del figlio:
 * "In più 1 h 20 min in sessione, che non contano." (un minuto solo: "…che non
 * conta."): i minuti passati nelle app di una sessione, che il totale non conta.
 * null se non ce ne sono, o se il telefono (o il server) non lo dice.
 */
fun testoInSessione(parole: Parole, minuti: Int?): String? {
    val inSessione = minuti?.takeIf { it > 0 } ?: return null
    return if (inSessione == 1) {
        parole.testo(R.string.tempo_in_sessione_un_minuto)
    } else {
        parole.testo(R.string.tempo_in_sessione, testoDurata(parole, inSessione.toLong()))
    }
}

/**
 * Perché si è chiusa da sola la domanda aperta su una sessione ([StatoDomanda]):
 * il figlio l'ha cambiata, o non aspetta più il genitore. null = la domanda vale.
 */
@StringRes
fun messaggioDomandaChiusa(stato: StatoDomanda): Int? = when (stato) {
    StatoDomanda.VALIDA -> null
    StatoDomanda.CAMBIATA -> R.string.sessione_cambiata_mentre_decidevi
    StatoDomanda.NON_PIU_DA_DECIDERE -> R.string.sessione_niente_da_decidere
}

/**
 * Un esito detto mentre si guarda un altro figlio: col nome di quello su cui si è
 * deciso ("Luca · Fatto: la sessione è approvata."). Senza nome, com'è.
 */
fun esitoPerIlFiglio(parole: Parole, nomeFiglio: String?, messaggio: String): String =
    nomeDaScrivere(nomeFiglio)?.let { parole.testo(R.string.sessione_esito_di, it, messaggio) } ?: messaggio

/**
 * Che cosa dire dopo la risposta del genitore a una sessione, ciascun rifiuto col
 * suo motivo vero. "Niente da decidere" dice com'è davvero, dalla rilettura
 * ([messaggioSessioneRiletta]); una richiesta cambiata (la `versione` non era più
 * quella) chiede di guardarla di nuovo. Un server più vecchio della v3.5 va
 * aggiornato. Rete caduta o un rifiuto che non si conosce: "riprova".
 */
@StringRes
fun messaggioEsitoSessione(esito: EsitoSessione): Int = when (esito) {
    is EsitoSessione.Decisa -> when {
        esito.esito == EsitiSessione.APPROVA && esito.cambio -> R.string.sessione_fatto_cambio_approvato
        esito.esito == EsitiSessione.APPROVA -> R.string.sessione_fatto_approvata
        esito.cambio -> R.string.sessione_fatto_cambio_non_approvato
        else -> R.string.sessione_fatto_non_approvata
    }
    is EsitoSessione.NonDecisa -> when (esito.codice) {
        CodiciErrore.NIENTE_DA_DECIDERE -> messaggioSessioneRiletta(esito.riletta)
        CodiciErrore.RICHIESTA_CAMBIATA -> R.string.sessione_cambiata_nel_frattempo
        CodiciErrore.NON_TROVATO -> R.string.sessione_eliminata_dal_figlio
        CodiciErrore.DISPOSITIVO_REVOCATO -> R.string.sessione_errore_telefono_scollegato
        CodiciErrore.SERVER_DA_AGGIORNARE -> R.string.sessione_errore_server_da_aggiornare
        PostinoClient.PARAMETRI_NON_VALIDI -> R.string.sessione_errore_non_accettata
        else -> R.string.decisione_errore_generico
    }
}

/**
 * Una sessione su cui non c'era più niente da decidere: com'è davvero, dalla
 * rilettura. Stato che non si sa: una frase che non sceglie, mai un "forse".
 */
@StringRes
fun messaggioSessioneRiletta(riletta: SessioneRiletta?): Int = when (riletta) {
    SessioneRiletta.APPROVATA -> R.string.sessione_gia_approvata
    SessioneRiletta.NON_APPROVATA -> R.string.sessione_gia_non_approvata
    SessioneRiletta.CAMBIO_DECISO -> R.string.sessione_cambio_gia_deciso
    SessioneRiletta.ELIMINATA -> R.string.sessione_eliminata_dal_figlio
    SessioneRiletta.CAMBIATA -> R.string.sessione_cambiata_nel_frattempo
    SessioneRiletta.NON_SI_SA, null -> R.string.sessione_niente_da_decidere
}

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
 *
 * (0.10) Per le proposte del figlio servono anche [figli] (il nome di chi
 * propone), [proposte] (le proposte in attesa lette con le finestre, per id:
 * che cosa chiede) e [nomi] (i nomi delle app che le finestre conoscono: una
 * proposta che cambia app la dice col suo nome). Se mancano, la frase si fa con
 * quello che c'è.
 *
 * (0.11) Per le sessioni da approvare servono anche [sessioni] (lette con le
 * finestre, per id): quali app chiede, o che cosa cambia.
 */
fun testoNotifica(
    parole: Parole,
    notifica: Notifica,
    regolePerId: Map<Long, RegolaFinestra>,
    figli: List<Figlio> = emptyList(),
    proposte: Map<Long, Proposta> = emptyMap(),
    nomi: Map<String, String> = emptyMap(),
    sessioni: Map<Long, Sessione> = emptyMap(),
    // (0.13) true = la notifica di sistema, che si tocca ("tocca per vedere la
    // foto"); false = la lista in app, dove toccare la riga non fa niente e c'è il
    // pulsante "Guarda la foto".
    nellaTendina: Boolean = true,
): TestoNotifica =
    fraseNotifica(parole, notifica, regolePerId, figli, proposte, nomi, sessioni, nellaTendina)
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
    // (0.10) Il figlio propone e ritira (contratto v3.4).
    "nuova_proposta" -> R.string.tipo_nuova_proposta
    "proposta_ritirata" -> R.string.tipo_proposta_ritirata
    // (0.11) Le sessioni del figlio (contratto v3.5).
    "sessione_da_approvare" -> R.string.tipo_sessione_da_approvare
    "sessione_eliminata" -> R.string.tipo_sessione_eliminata
    "dichiarazione" -> R.string.tipo_dichiarazione
    // (0.13) Le faccende (contratto v3.6): al genitore arrivano la foto e la fine.
    TipiNotificaFaccende.FACCENDA_FATTA -> R.string.tipo_faccenda_fatta
    TipiNotificaFaccende.FACCENDE_FINITE -> R.string.tipo_faccende_finite
    // (v3) Il computer che si spegne e si riaccende: non sono interruzioni.
    "sospensione" -> R.string.tipo_computer_spento
    "ripresa" -> R.string.tipo_computer_acceso
    else -> R.string.tipo_novita // tipo nuovo dal server: tolleranza evolutiva
}

/** null = tipo sconosciuto o payload che non basta: si ripiega sul messaggio del server. */
private fun fraseNotifica(
    parole: Parole,
    notifica: Notifica,
    regolePerId: Map<Long, RegolaFinestra>,
    figli: List<Figlio>,
    proposte: Map<Long, Proposta>,
    nomi: Map<String, String>,
    sessioni: Map<Long, Sessione>,
    nellaTendina: Boolean = true,
): TestoNotifica? {
    val payload = notifica.payload
    val regola = regolaIdNotifica(notifica)?.let { regolePerId[it] }
    val titolo = parole.testo(etichettaTipoNotifica(notifica.tipo))
    return when (notifica.tipo) {
        // "Fuori regola" / "TikTok: al massimo 1 h al giorno".
        "sforamento" -> regola?.let { TestoNotifica(titolo, descrizioneRegola(parole, it)) }

        // "Anomalia" / "Uso non registrato in questo periodo"; (v3) "Pactum è
        // stato chiuso sul computer (dalle 15:10 alle 15:40)".
        "manomissione" -> {
            val dettagli = payload["dettagli"] as? JsonObject ?: return null
            campo(dettagli, "sotto_tipo") ?: return null
            TestoNotifica(titolo, descrizioneBuco(parole, dettagli))
        }

        // (v3) Il computer spento o riacceso: una cosa normale, detta come tale.
        // (0.14, contratto v3.7) Anche un telefono si spegne e si riaccende: le parole dal tipo.
        "sospensione" -> fraseSospensione(parole, motivoEvento(payload), telefonoDi(notifica, figli))
        "ripresa" -> fraseRipresa(parole, motivoEvento(payload), telefonoDi(notifica, figli))

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

        // (0.10) Dalla v3.4 si annullano anche le proposte del figlio: allora non è "la tua".
        "proposta_annullata" -> {
            val su = regola?.let { suRegola(parole, it) } ?: return null
            val frase = if (campo(payload, "autore") == AutoriProposta.FIGLIO) {
                nomeFiglioDi(notifica, figli)
                    ?.let { parole.testo(R.string.notifica_proposta_del_figlio_annullata, it, su) }
                    ?: parole.testo(R.string.notifica_proposta_del_figlio_annullata_senza_nome, su)
            } else {
                parole.testo(R.string.notifica_proposta_annullata, su)
            }
            TestoNotifica(titolo, frase)
        }

        // (0.10) "Luca ti propone un cambio" / la regola come sarebbe, e il confronto.
        "nuova_proposta" -> fraseNuovaProposta(parole, notifica, regola, figli, proposte, nomi)

        // (0.10) "Luca ha ritirato la sua proposta" / "La regola su TikTok resta com'è."
        "proposta_ritirata" -> {
            // Il genitore riceve solo i ritiri del figlio: i suoi vanno al figlio.
            if (campo(payload, "autore") == AutoriProposta.GENITORE) return null
            val su = regola?.let { suRegola(parole, it) } ?: return null
            TestoNotifica(
                titolo = nomeFiglioDi(notifica, figli)?.let { parole.testo(R.string.notifica_ha_ritirato, it) }
                    ?: parole.testo(R.string.notifica_ha_ritirato_senza_nome),
                testo = parole.testo(R.string.notifica_regola_resta, su),
            )
        }

        // (0.11) "Luca chiede di approvare la sessione 📚 «Studio»" / le sue app.
        "sessione_da_approvare" -> fraseSessioneDaApprovare(parole, notifica, figli, sessioni, nomi)

        // (0.11) "Luca ha eliminato la sessione 📚 «Studio»" / la storia resta.
        "sessione_eliminata" -> {
            val nome = campo(payload, "nome")?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { nomeSessioneTraVirgolette(parole, it) }
                ?: return null
            TestoNotifica(
                titolo = nomeFiglioDi(notifica, figli)?.let { parole.testo(R.string.notifica_sessione_eliminata, it, nome) }
                    ?: parole.testo(R.string.notifica_sessione_eliminata_senza_nome, nome),
                testo = parole.testo(R.string.notifica_sessione_eliminata_testo),
            )
        }

        // (0.13) "Faccenda fatta" / "Luca ha fatto «Svuota la lavastoviglie»: tocca
        // per vedere la foto." Senza il titolo nel payload, il messaggio del server.
        // Nella lista in app senza "tocca per…": lì c'è il pulsante "Guarda la foto".
        TipiNotificaFaccende.FACCENDA_FATTA -> {
            val faccenda = campo(payload, "titolo")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val nome = nomeFiglioDi(notifica, figli)
            val testo = when {
                nellaTendina && nome != null -> parole.testo(R.string.notifica_faccenda_fatta, nome, faccenda)
                nellaTendina -> parole.testo(R.string.notifica_faccenda_fatta_senza_nome, faccenda)
                nome != null -> parole.testo(R.string.notifica_faccenda_fatta_lista, nome, faccenda)
                else -> parole.testo(R.string.notifica_faccenda_fatta_lista_senza_nome, faccenda)
            }
            TestoNotifica(titolo, testo)
        }

        // (0.13) "Faccende finite" / la frase del server, che sa se il blocco era
        // partito ("Luca ha finito le faccende: telefono e computer sbloccati"), e
        // (solo nella tendina) dove si vedono le foto.
        TipiNotificaFaccende.FACCENDE_FINITE -> {
            val frase = notifica.messaggio.trim().takeIf { it.isNotEmpty() }
            val tocca = parole.testo(R.string.notifica_faccende_finite_tocca)
            TestoNotifica(
                titolo,
                when {
                    !nellaTendina -> frase ?: return null
                    frase != null -> "$frase\n$tocca"
                    else -> tocca
                },
            )
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

/** (0.10) Il nome del figlio di una notifica, se la famiglia lo sa e non è vuoto. */
private fun nomeFiglioDi(notifica: Notifica, figli: List<Figlio>): String? =
    nomeDaScrivere(figli.firstOrNull { it.id == notifica.figlioId }?.nome)

/**
 * (0.10) `nuova_proposta` arrivata al genitore: una proposta del figlio
 * (contratto v3.4). Il titolo dice chi propone ("Luca ti propone un cambio"),
 * il testo che cosa:
 * - la proposta letta con la finestra: la regola come sarebbe, coi nomi delle
 *   app ([nomi]), e sotto il confronto ("TikTok: al massimo 1 h 30 min al giorno"
 *   / "+30 min al giorno rispetto ad ora"; un cambio di app "Da TikTok (1 h) a
 *   Instagram (1 h) al giorno");
 * - un'eliminazione: "Togliere la regola «TikTok: al massimo 1 h al giorno»";
 * - la proposta non letta (non più in attesa, o finestra non arrivata): su
 *   quale regola, col confronto del payload ("Proposta su TikTok: +30 min al
 *   giorno rispetto ad ora") — senza, se è un cambio di app che può avere le
 *   chiavi tecniche ("Proposta su TikTok").
 * Su quale dispositivo lo dice già la riga sopra il titolo (etichettaNotifica).
 * null (regola che non si conosce, o una proposta "del genitore", che al
 * genitore non arriva) = il messaggio del server.
 */
private fun fraseNuovaProposta(
    parole: Parole,
    notifica: Notifica,
    regola: RegolaFinestra?,
    figli: List<Figlio>,
    proposte: Map<Long, Proposta>,
    nomi: Map<String, String>,
): TestoNotifica? {
    val payload = notifica.payload
    if (regola == null || campo(payload, "autore") == AutoriProposta.GENITORE) return null
    val proposta = campo(payload, "proposta_id")?.toLongOrNull()
        ?.let { proposte[it] }
        ?.takeIf { it.regolaId == regola.id }
    val elimina = (proposta != null && eliminazione(proposta)) ||
        campo(payload, "direzione") == DirezioniProposta.ELIMINA
    val testo = if (elimina) {
        parole.testo(R.string.proposta_togliere_regola, descrizioneRegola(parole, regola))
    } else {
        val su = suRegola(parole, regola) ?: return null
        proposta
            ?.let { descrizioneProposta(parole, it, regola, nomi) }
            ?.let { cosa -> listOfNotNull(cosa, confrontoDaMostrare(parole, proposta, regola, nomi)).joinToString("\n") }
            ?: campo(payload, "confronto")
                ?.takeIf { it.isNotBlank() && !raccontaUnCambioDiBersaglio(it) }
                ?.let { parole.testo(R.string.notifica_proposta_su, su, it) }
            ?: parole.testo(R.string.notifica_proposta_su_regola, su)
    }
    val titolo = nomeFiglioDi(notifica, figli)?.let { parole.testo(R.string.notifica_ti_propone, it) }
        ?: parole.testo(R.string.notifica_ti_propone_senza_nome)
    return TestoNotifica(titolo, testo)
}

/**
 * (0.11) `sessione_da_approvare` arrivata al genitore (contratto v3.5). Il titolo
 * dice chi chiede e quale sessione: "Luca chiede di approvare la sessione
 * 📚 «Studio»", o per un cambio "Luca chiede di cambiare la sessione 📚 «Studio»" (col
 * nome di adesso, quello che il genitore conosce). Il testo, se la sessione è
 * stata letta con la finestra ([sessioni]) e chiede ancora quella cosa: le sue app
 * ("ClasseViva e le app installate fuori dal Play Store") e che cosa vuol dire, o per un cambio
 * che cosa cambia ("Nuovo nome: 📚 «Compiti»", "Aggiunge Duolingo", "Toglie
 * YouTube"). Se no, per un cambio che rinomina il nome nuovo del payload
 * (`nuovo_nome`), e per il resto che cosa vuol dire una sessione. Senza il nome
 * della sessione (null) il messaggio del server, che dice già la stessa cosa.
 */
private fun fraseSessioneDaApprovare(
    parole: Parole,
    notifica: Notifica,
    figli: List<Figlio>,
    sessioni: Map<Long, Sessione>,
    nomi: Map<String, String>,
): TestoNotifica? {
    val payload = notifica.payload
    val sessione = campo(payload, "sessione_id")?.toLongOrNull()?.let { sessioni[it] }
    val nome = campo(payload, "nome")?.trim()?.takeIf { it.isNotEmpty() }
        ?: sessione?.nome?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null
    val cambio = campo(payload, "cambio")?.toBooleanStrictOrNull() ?: false
    val nuovoNome = campo(payload, "nuovo_nome")?.trim()?.takeIf { cambio && it.isNotEmpty() && it != nome }
    val richiesta = sessione?.let(::richiestaInAttesa)?.takeIf { it.cambio == cambio }
    val nonConta = parole.testo(R.string.sessione_non_conta)
    val testo = when {
        richiesta == null -> null
        cambio -> righeCambioSessione(parole, richiesta, nomi).takeIf { it.isNotEmpty() }?.joinToString("\n")
        else -> elencoAppSessione(parole, appDellaSessione(richiesta.app, richiesta.nomi, nomi))?.let { "$it\n$nonConta" }
    }
    return TestoNotifica(
        titolo = chiedeLaSessione(parole, nomeFiglioDi(notifica, figli), nome, cambio),
        testo = testo ?: nuovoNome?.let { testoNuovoNome(parole, it) } ?: nonConta,
    )
}

/** Il `motivo` di un evento del computer: nei dettagli dell'evento, o in cima al payload. */
private fun motivoEvento(payload: JsonObject): String? =
    (payload["dettagli"] as? JsonObject)?.let { campo(it, "motivo") } ?: campo(payload, "motivo")

/** (0.14) true = il dispositivo della notifica è un telefono (la famiglia lo sa); se no, il computer di sempre. */
private fun telefonoDi(notifica: Notifica, figli: List<Figlio>): Boolean =
    figli.flatMap { it.dispositivi }.firstOrNull { it.id == notifica.dispositivoId }?.tipo == TipiDispositivo.TELEFONO

/**
 * `sospensione` (v3): il computer si spegne, va in sospensione o il figlio esce
 * dall'account; (0.14, contratto v3.7) anche il telefono si spegne. Non è
 * un'interruzione nella registrazione, e la frase lo dice.
 */
private fun fraseSospensione(parole: Parole, motivo: String?, telefono: Boolean = false): TestoNotifica = when {
    telefono -> TestoNotifica(
        parole.testo(R.string.tipo_telefono_spento),
        parole.testo(R.string.notifica_telefono_spento),
    )
    else -> fraseSospensioneComputer(parole, motivo)
}

private fun fraseSospensioneComputer(parole: Parole, motivo: String?): TestoNotifica = when (motivo) {
    "sospensione" -> TestoNotifica(
        parole.testo(R.string.tipo_computer_in_sospensione),
        parole.testo(R.string.notifica_computer_in_sospensione),
    )
    "disconnessione" -> TestoNotifica(
        parole.testo(R.string.tipo_uscita_account),
        parole.testo(R.string.notifica_uscita_account),
    )
    else -> TestoNotifica(
        parole.testo(R.string.tipo_computer_spento),
        parole.testo(R.string.notifica_computer_spento),
    )
}

/** `ripresa` (v3): il computer riparte e Pactum riprende a registrare. */
private fun fraseRipresa(parole: Parole, motivo: String?, telefono: Boolean = false): TestoNotifica = when {
    telefono -> TestoNotifica(
        parole.testo(R.string.tipo_telefono_acceso),
        parole.testo(R.string.notifica_telefono_acceso),
    )
    else -> fraseRipresaComputer(parole, motivo)
}

private fun fraseRipresaComputer(parole: Parole, motivo: String?): TestoNotifica = when (motivo) {
    "riattivazione" -> TestoNotifica(
        parole.testo(R.string.tipo_computer_riattivato),
        parole.testo(R.string.notifica_computer_riattivato),
    )
    "accesso" -> TestoNotifica(
        parole.testo(R.string.tipo_accesso_account),
        parole.testo(R.string.notifica_accesso_account),
    )
    else -> TestoNotifica(
        parole.testo(R.string.tipo_computer_acceso),
        parole.testo(R.string.notifica_computer_acceso),
    )
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
 * "su TikTok", "sulla fascia 21:00–07:00", "su «Camminare un'ora»", (v3.3) "su
 * tutto il telefono": la regola come complemento di una frase ("Ha accettato la
 * tua proposta su TikTok"). null per un tipo di regola sconosciuto.
 */
private fun suRegola(parole: Parole, regola: RegolaFinestra): String? = when (regola.tipo) {
    TipiRegola.LIMITE_TEMPO -> if (eTotale(campo(regola.parametri, "app_o_categoria"))) {
        parole.testo(
            if (regola.dispositivo?.tipo == TipiDispositivo.COMPUTER) {
                R.string.regola_su_totale_computer
            } else {
                R.string.regola_su_totale_telefono
            },
        )
    } else {
        parole.testo(R.string.regola_su_app, bersaglioRegola(parole, regola))
    }
    TipiRegola.FASCIA_ORARIA -> parole.testo(
        R.string.regola_su_fascia,
        campo(regola.parametri, "dalle") ?: DATO_MANCANTE,
        campo(regola.parametri, "alle") ?: DATO_MANCANTE,
    )
    TipiRegola.VITA_REALE ->
        parole.testo(R.string.regola_su_vita_reale, campo(regola.parametri, "descrizione") ?: DATO_MANCANTE)
    else -> null
}
