package eu.stgm.pactum.genitore.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.CodiciErrore
import eu.stgm.pactum.genitore.dati.Dispositivo
import eu.stgm.pactum.genitore.dati.EsitiDichiarazione
import eu.stgm.pactum.genitore.dati.EsitiRisposta
import eu.stgm.pactum.genitore.dati.Notifica
import eu.stgm.pactum.genitore.dati.RegolaFinestra
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
    descrizioneRegola(parole, regola.tipo, regola.parametri, regola.nome, regola.dispositivo?.tipo)

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
 * computer", non "Niente telefono".
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
        nomeBersaglio(parametri, nomeApp),
        testoDurata(parole, campo(parametri, "minuti_al_giorno")?.toLongOrNull() ?: 0),
    )

    TipiRegola.FASCIA_ORARIA -> parole.testo(
        if (tipoDispositivo == TipiDispositivo.COMPUTER) {
            R.string.regola_fascia_oraria_computer
        } else {
            R.string.regola_fascia_oraria
        },
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
    return descrizioneRegola(
        parole,
        regola.tipo,
        parametri,
        regola.nome.takeIf { stessaApp },
        regola.dispositivo?.tipo,
    )
}

/** L'app, il programma, il sito o la categoria di una limite_tempo, col nome leggibile se c'è. */
private fun nomeBersaglio(parametri: JsonObject, nomeApp: String?): String =
    nomeLeggibile(campo(parametri, "app_o_categoria") ?: "?", nomeApp)

/**
 * Il nome da mostrare per una chiave del contratto: il `nome` leggibile quando
 * c'è ed è davvero un nome ("TikTok", "Minecraft"), altrimenti la chiave resa
 * leggibile ([etichettaAppOCategoria]). Un `nome` che è la chiave stessa (il
 * ripiego del server: "il pacchetto stesso") o una chiave con prefisso
 * (`exe:…`, `sito:…`) non è un nome: il genitore non legge mai `exe:`.
 */
fun nomeLeggibile(chiave: String, nome: String?): String {
    val pulito = nome?.trim()?.takeIf { candidato ->
        candidato.isNotEmpty() &&
            candidato != chiave &&
            PREFISSI_TECNICI.none { candidato.startsWith(it) }
    }
    return pulito ?: etichettaAppOCategoria(chiave)
}

private val PREFISSI_TECNICI = listOf("exe:", "sito:", "categoria:")

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
        else -> descrizioneBuco(parole, sottoTipo)
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
    else -> parole.testo(R.string.manomissione_generica, sottoTipo ?: "?")
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
 * Un computer "spento" da più di 24 ore ([spentoALungo]) non si dice spento:
 * "Nessun dato dal computer dal 23/09 alle 22:10: spento, oppure Pactum non è
 * partito". Da quando: dallo spegnimento, o, se il server non lo dice,
 * dall'ultimo battito.
 */
fun testoStatoCanale(
    parole: Parole,
    stato: StatoCanale,
    silenzio: StatoSilenzio?,
    zona: ZoneId = ZoneId.systemDefault(),
    oggi: LocalDate = LocalDate.now(zona),
    adesso: Instant = Instant.now(),
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
                ultimoSegno != null && spentoALungo(ultimoSegno, adesso) ->
                    parole.testo(R.string.dispositivo_spento_a_lungo, dalleQuando(parole, ultimoSegno, zona, oggi))
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
 * Un computer che risulta spento da più di 24 ore ([spentoALungo]) non si dice
 * spento, né "non è un'interruzione": non lo si sa.
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
            if (ultimoSegno != null && spentoALungo(ultimoSegno, adesso)) {
                TestoNotifica(
                    parole.testo(R.string.notifica_spento_a_lungo_titolo),
                    parole.testo(R.string.notifica_spento_a_lungo, dalleQuando(parole, ultimoSegno, zona, oggi)),
                )
            } else {
                TestoNotifica(
                    parole.testo(R.string.tipo_computer_spento),
                    dal?.let { parole.testo(R.string.notifica_spento_dopo_silenzio, dalleQuando(parole, it, zona, oggi)) }
                        ?: parole.testo(R.string.notifica_spento_dopo_silenzio_senza_ora),
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
        val totale = dispositivo.usoRecente.lastOrNull()?.totaleMinuti
        if (totale == null) {
            parole.testo(R.string.digest_parte_dispositivo_nessun_dato, nome)
        } else {
            parole.testo(R.string.digest_parte_dispositivo, nome, testoDurata(parole, totale.toLong()))
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
                parole.testo(R.string.digest_parte_dispositivo, nome, testoDurata(parole, uso.totaleMinuti.toLong()))
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

/** Il digest della 0.7: un telefono, il totale nel titolo, le prime app nel testo. */
private fun digestUnTelefono(parole: Parole, uso: UsoGiorno?, adesso: Instant): TestoNotifica {
    val totale = uso?.totaleMinuti
        ?: return TestoNotifica(
            parole.testo(R.string.digest_titolo_nessun_dato),
            parole.testo(R.string.digest_testo_nessun_dato),
        )
    val titolo = parole.testo(R.string.digest_titolo, testoDurata(parole, totale.toLong()))
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

/** "TikTok 1 h (limite 1 h) · YouTube 40 min": le app più usate del giorno, col limite dove c'è. */
private fun primeApp(parole: Parole, uso: UsoGiorno): String =
    uso.app
        .sortedByDescending { it.minuti }
        .take(APP_NEL_DIGEST)
        .joinToString(" · ") { app ->
            val nome = nomeLeggibile(app.chiave, app.nome)
            val durata = testoDurata(parole, app.minuti.toLong())
            val limite = app.limite
            if (limite != null) {
                parole.testo(R.string.digest_app_con_limite, nome, durata, testoDurata(parole, limite.toLong()))
            } else {
                parole.testo(R.string.digest_app, nome, durata)
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
fun messaggioRifiutoFamiglia(parole: Parole, codice: String?, secondi: Long?): String = when (codice) {
    CodiciErrore.TROPPI_TENTATIVI -> if (secondi != null && secondi > 0) {
        parole.testo(R.string.famiglia_errore_troppi_tentativi_tra, attesaInMinuti(secondi))
    } else {
        parole.testo(R.string.famiglia_errore_troppi_tentativi)
    }
    CodiciErrore.NON_TROVATO -> parole.testo(R.string.famiglia_errore_non_trovato)
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
        "sospensione" -> fraseSospensione(parole, motivoEvento(payload))
        "ripresa" -> fraseRipresa(parole, motivoEvento(payload))

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

/** Il `motivo` di un evento del computer: nei dettagli dell'evento, o in cima al payload. */
private fun motivoEvento(payload: JsonObject): String? =
    (payload["dettagli"] as? JsonObject)?.let { campo(it, "motivo") } ?: campo(payload, "motivo")

/**
 * `sospensione` (v3): il computer si spegne, va in sospensione o il figlio esce
 * dall'account. Non è un'interruzione nella registrazione, e la frase lo dice.
 */
private fun fraseSospensione(parole: Parole, motivo: String?): TestoNotifica = when (motivo) {
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
private fun fraseRipresa(parole: Parole, motivo: String?): TestoNotifica = when (motivo) {
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
