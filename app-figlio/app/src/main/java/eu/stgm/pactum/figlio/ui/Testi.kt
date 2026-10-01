package eu.stgm.pactum.figlio.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.catalogo.CatalogoApp
import eu.stgm.pactum.figlio.dati.ContestoDispositivi
import eu.stgm.pactum.figlio.dati.EsitoProposta
import eu.stgm.pactum.figlio.dati.EsitoRitiro
import eu.stgm.pactum.figlio.dati.Proposta
import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.TipiDispositivo
import eu.stgm.pactum.figlio.dati.TipiRegola
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Traduzioni dai dati del contratto all'italiano semplice: descrizioni delle
 * regole da tipo+parametri, orari del server (ISO UTC) nel fuso del telefono,
 * attese del lock in parole ("3 giorni e 4 ore").
 */

/** Il ts_server ISO 8601 UTC come istante, null se malformato. */
fun istanteServer(tsServer: String?): Instant? {
    if (tsServer.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(tsServer).toInstant()
    } catch (e: DateTimeParseException) {
        null
    }
}

private val formatoDataOra: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm")

fun dataOraLocale(istante: Instant): String =
    formatoDataOra.format(istante.atZone(ZoneId.systemDefault()))

fun parametroTesto(parametri: JsonObject, nome: String): String? =
    (parametri[nome] as? JsonPrimitive)?.content

fun giorniTesto(parametri: JsonObject): String =
    (parametri["giorni"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.content }
        ?.joinToString(", ")
        ?: ""

/**
 * La regola raccontata in italiano semplice, costruita da tipo+parametri
 * (contratto-api.md). Un tipo sconosciuto mostra il tipo grezzo: meglio
 * onesto che muto (tolleranza evolutiva).
 */
@Composable
fun descrizioneRegola(tipo: String, parametri: JsonObject, leggibile: Boolean = false): String {
    // Si rilegge a ogni cambio di configurazione (lingua), come stringResource.
    LocalConfiguration.current
    return descrizioneRegola(LocalContext.current, tipo, parametri, leggibile = leggibile)
}

/**
 * Il bersaglio di un limite di tempo in chiaro: un'app o una categoria del
 * telefono, "Tutto il telefono" (0.9), oppure (v3) un programma o un sito del
 * computer ("Minecraft", "youtube.com (sito)"). [nomeServer] è il nome
 * leggibile che il server può mandare sulla regola: per un'app di un altro
 * telefono, che qui non è installata, è l'unico nome che c'è. [tipoDispositivo]
 * = di che dispositivo è la regola: "totale" su un computer è "Tutto il computer".
 */
fun etichettaChiave(
    context: Context,
    chiave: String,
    nomeServer: String? = null,
    tipoDispositivo: String? = null,
): String {
    if (tipoDispositivo == TipiDispositivo.COMPUTER && CatalogoApp.eTotale(chiave)) {
        return context.getString(R.string.chiave_totale_computer)
    }
    ChiaviComputer.etichetta(chiave, nomeServer, context.getString(R.string.chiave_sito))?.let { return it }
    val etichetta = CatalogoApp.etichettaValore(context, chiave)
    if (etichetta != chiave) return etichetta
    return nomeServer?.trim()?.takeIf { it.isNotEmpty() } ?: etichetta
}

/**
 * (0.10, contratto v3.4 "I nomi nel confronto") Il bersaglio con un nome che
 * una persona legge (app, categoria, "Tutto il telefono", programma, sito);
 * null se questo telefono non sa come si chiama: un'app di un altro telefono,
 * mai vista qui. Nelle proposte mai il nome del pacchetto.
 */
fun etichettaLeggibile(
    context: Context,
    chiave: String,
    nomeServer: String? = null,
    tipoDispositivo: String? = null,
): String? = TestoProposta.nomeLeggibile(chiave, etichettaChiave(context, chiave, nomeServer, tipoDispositivo))

/**
 * La stessa descrizione fuori da Compose (notifiche, worker).
 * [tipoDispositivo] = di che dispositivo è la regola: una fascia oraria del
 * computer è "Niente computer dalle…". [breve] = la forma che segue il nome del
 * dispositivo ("Sul computer: youtube.com (sito) al massimo 1 h al giorno"),
 * senza i due punti dopo il bersaglio e con l'iniziale minuscola.
 * (0.10) [leggibile] = per le regole e le proposte: un'app che qui non ha un
 * nome diventa "un'app non installata qui", mai il nome del pacchetto.
 */
fun descrizioneRegola(
    context: Context,
    tipo: String,
    parametri: JsonObject,
    tipoDispositivo: String? = null,
    nomeServer: String? = null,
    breve: Boolean = false,
    leggibile: Boolean = false,
): String = when (tipo) {
    // app_o_categoria è un pacchetto, una chiave categoria:* (contratto v2.1)
    // o, sul computer, exe:/sito: (v3): si mostra l'etichetta leggibile.
    TipiRegola.LIMITE_TEMPO -> context.getString(
        if (breve) R.string.regola_limite_tempo_breve else R.string.regola_limite_tempo,
        parametroTesto(parametri, "app_o_categoria")
            ?.let { chiave ->
                if (leggibile) {
                    etichettaLeggibile(context, chiave, nomeServer, tipoDispositivo)
                        ?: context.getString(R.string.chiave_app_sconosciuta)
                            .let { if (breve) it else TestoDispositivi.maiuscola(it) }
                } else {
                    etichettaChiave(context, chiave, nomeServer, tipoDispositivo)
                }
            } ?: "?",
        testoDurata(context, parametroTesto(parametri, "minuti_al_giorno")?.toLongOrNull() ?: 0),
    )

    TipiRegola.FASCIA_ORARIA -> context.getString(
        when {
            tipoDispositivo == TipiDispositivo.COMPUTER && breve -> R.string.regola_fascia_oraria_computer_breve
            tipoDispositivo == TipiDispositivo.COMPUTER -> R.string.regola_fascia_oraria_computer
            breve -> R.string.regola_fascia_oraria_breve
            else -> R.string.regola_fascia_oraria
        },
        parametroTesto(parametri, "dalle") ?: "?",
        parametroTesto(parametri, "alle") ?: "?",
        giorniTesto(parametri).ifBlank { "?" },
    )

    TipiRegola.VITA_REALE -> context.getString(
        R.string.regola_vita_reale,
        parametroTesto(parametri, "descrizione") ?: "?",
        parametroTesto(parametri, "arbitro_nome") ?: "?",
        parametroTesto(parametri, "frequenza") ?: "?",
    )

    else -> tipo
}

/**
 * (v3) La regola con i suoi [parametri] (di default quelli di adesso) nella
 * forma che va dopo il nome del dispositivo, se è di un altro dispositivo
 * ("youtube.com (sito) al massimo 1 h al giorno"), altrimenti quella di sempre.
 * Il nome del server vale solo se il bersaglio è ancora quello della regola.
 * (0.10) Si usa per le proposte: i bersagli sempre con nomi leggibili.
 */
fun descrizioneRegolaSenzaDispositivo(
    context: Context,
    regola: Regola,
    contesto: ContestoDispositivi,
    parametri: JsonObject = regola.parametri,
): String {
    val stessoBersaglio = parametroTesto(parametri, "app_o_categoria") ==
        parametroTesto(regola.parametri, "app_o_categoria")
    return descrizioneRegola(
        context,
        regola.tipo,
        parametri,
        tipoDispositivo = TestoDispositivi.tipoDi(regola, contesto),
        nomeServer = regola.nome.takeIf { stessoBersaglio },
        breve = TestoDispositivi.diUnAltro(regola, contesto),
        leggibile = true,
    )
}

/**
 * (0.10) I parametri proposti per [regola], detti in chiaro. null quando la
 * proposta cambia il bersaglio verso un'app che questo telefono non sa come
 * si chiama: meglio nessuna riga che un nome di pacchetto, e il confronto del
 * server (contratto v3.4, "I nomi nel confronto") quel nome lo scrive già.
 */
fun descrizioneParametriProposti(
    context: Context,
    regola: Regola,
    contesto: ContestoDispositivi,
    parametri: JsonObject,
): String? {
    val chiave = parametroTesto(parametri, "app_o_categoria")
    if (chiave != null &&
        chiave != parametroTesto(regola.parametri, "app_o_categoria") &&
        etichettaLeggibile(context, chiave, tipoDispositivo = TestoDispositivi.tipoDi(regola, contesto)) == null
    ) {
        return null
    }
    return descrizioneRegolaSenzaDispositivo(context, regola, contesto, parametri)
}

/**
 * (v3) La regola detta per intero: se è di un altro dispositivo, lo dice
 * ("Sul computer: youtube.com (sito) al massimo 1 h al giorno"); se è di
 * questo telefono o del figlio, com'è sempre stata.
 */
fun descrizioneRegolaConDispositivo(context: Context, regola: Regola, contesto: ContestoDispositivi): String {
    val su = TestoDispositivi.etichetta(regola, contesto, paroleDispositivo(context))
    val frase = descrizioneRegolaSenzaDispositivo(context, regola, contesto)
    return if (su == null) {
        frase
    } else {
        context.getString(R.string.regola_con_dispositivo, TestoDispositivi.maiuscola(su), frase)
    }
}

/** Le parole per dire su quale dispositivo sta una regola (TestoDispositivi). */
fun paroleDispositivo(context: Context) = ParoleDispositivo(
    sulComputer = context.getString(R.string.dispositivo_sul_computer),
    sulComputerNome = context.getString(R.string.dispositivo_sul_computer_nome),
    sulTelefonoNome = context.getString(R.string.dispositivo_sul_telefono_nome),
    sullAltroTelefono = context.getString(R.string.dispositivo_sull_altro_telefono),
    suNome = context.getString(R.string.dispositivo_su_nome),
    suAltro = context.getString(R.string.dispositivo_su_altro),
)

/** Le frasi dell'avviso "collegato a un dispositivo diverso da prima" (testoCambioDispositivo). */
fun paroleCambioDispositivo(context: Context) = ParoleCambioDispositivo(
    conNomi = context.getString(R.string.collega_altro_dispositivo),
    senzaNomi = context.getString(R.string.collega_altro_dispositivo_senza_nomi),
    consiglio = context.getString(R.string.collega_altro_dispositivo_consiglio),
    consiglioSenzaNome = context.getString(R.string.collega_altro_dispositivo_consiglio_senza_nome),
)

/** Le frasi della proposta (TestoProposta), da strings.xml. */
fun paroleProposta(context: Context) = ParoleProposta(
    senzaConfronto = context.getString(R.string.proposta_senza_confronto),
    ora = context.getString(R.string.proposta_regola_ora),
    seAccetti = context.getString(R.string.proposta_regola_se_accetti),
    togliere = context.getString(R.string.proposta_regola_togliere),
    oraSu = context.getString(R.string.proposta_regola_ora_su),
    togliereSu = context.getString(R.string.proposta_regola_togliere_su),
)

/**
 * (0.10) Le stesse frasi per una proposta del figlio, dette dalla sua parte:
 * "Se il genitore accetta: …", "Eliminare la regola: …".
 */
fun paroleTuaProposta(context: Context) = ParoleProposta(
    senzaConfronto = context.getString(R.string.proposta_senza_confronto),
    ora = context.getString(R.string.proposta_regola_ora),
    seAccetti = context.getString(R.string.proposta_tua_se_accetta),
    togliere = context.getString(R.string.proposta_tua_eliminare),
    oraSu = context.getString(R.string.proposta_regola_ora_su),
    togliereSu = context.getString(R.string.proposta_tua_eliminare_su),
)

/**
 * Il racconto di una proposta con le regole del patto, per la scheda Proposte
 * e per la notifica: la regola detta in chiaro e, se è di un altro
 * dispositivo, su quale ("Ora sul computer: …"). (0.10) [parole] =
 * paroleTuaProposta per le proposte del figlio; un bersaglio proposto senza un
 * nome leggibile qui non diventa una riga col nome del pacchetto.
 */
fun raccontoProposta(
    context: Context,
    confronto: String?,
    oggetto: OggettoProposta?,
    contesto: ContestoDispositivi,
    parole: ParoleProposta = paroleProposta(context),
): RaccontoProposta {
    val paroleDispositivo = paroleDispositivo(context)
    return TestoProposta.racconto(
        confronto = confronto,
        oggetto = oggetto,
        parole = parole,
        descrivi = { regola, parametri -> descrizioneRegolaSenzaDispositivo(context, regola, contesto, parametri) },
        dispositivoDi = { regola -> TestoDispositivi.etichetta(regola, contesto, paroleDispositivo) },
        descriviProposta = { regola, parametri -> descrizioneParametriProposti(context, regola, contesto, parametri) },
    )
}

/** (0.10) Cosa dire dopo aver mandato una proposta al genitore (TestoProposta.esito). */
fun testoEsitoProposta(context: Context, esito: EsitoProposta): String = TestoProposta.esito(
    esito,
    ParoleEsitoProposta(
        mandata = context.getString(R.string.proposta_mandata),
        mandataEliminazione = context.getString(R.string.proposta_mandata_elimina),
        mandataSenzaConfronto = context.getString(R.string.proposta_mandata_semplice),
        giaPendente = context.getString(R.string.proposta_gia_pendente),
        giaTua = context.getString(R.string.proposta_gia_tua),
        regolaNonValida = context.getString(R.string.proposta_regola_non_valida),
        dispositivoRevocato = context.getString(R.string.proposta_dispositivo_revocato),
        valoriNonValidi = context.getString(R.string.proposta_valori_non_validi),
        serverDaAggiornare = context.getString(R.string.proposta_server_da_aggiornare),
        scollegato = context.getString(R.string.oggi_scollegato),
        senzaRete = context.getString(R.string.proposta_senza_rete),
        errore = context.getString(R.string.proposta_non_mandata),
    ),
)

/** (0.10) Cosa dire dopo il ritiro di una proposta del figlio (TestoProposta.ritiro). */
fun testoRitiro(context: Context, esito: EsitoRitiro): String = TestoProposta.ritiro(
    esito,
    ParoleRitiro(
        ritirata = context.getString(R.string.proposta_ritirata_ok),
        nonPiuPendente = context.getString(R.string.proposta_non_pendente),
        nonTrovata = context.getString(R.string.proposta_non_trovata),
        // Il ritiro ha la sua frase: "per mandare proposte" qui non c'entra.
        serverDaAggiornare = context.getString(R.string.proposta_ritiro_server_da_aggiornare),
        scollegato = context.getString(R.string.oggi_scollegato),
        errore = context.getString(R.string.proposta_ritiro_errore),
    ),
)

/** (0.10) Le frasi della storia delle proposte (TestoProposta.righeChiusa). */
fun paroleStoria(context: Context) = ParoleStoria(
    haiAccettato = context.getString(R.string.proposta_tua_risposta_accettata),
    haiRifiutato = context.getString(R.string.proposta_tua_risposta_rifiutata),
    genitoreHaAccettato = context.getString(R.string.proposta_genitore_ha_accettato),
    genitoreHaRifiutato = context.getString(R.string.proposta_genitore_ha_rifiutato),
    haiRitirato = context.getString(R.string.proposta_hai_ritirato),
    genitoreHaRitirato = context.getString(R.string.proposta_genitore_ha_ritirato),
    annullata = context.getString(R.string.proposta_annullata),
    genitoreDice = context.getString(R.string.proposta_motivazione_genitore),
    haiDetto = context.getString(R.string.proposta_tua_motivazione),
    tuoPerche = context.getString(R.string.proposta_tuo_perche),
)

/**
 * (0.10) Titolo e testo della notifica di una risposta del genitore a una
 * proposta del figlio, o di un suo ritiro (TestoProposta.avviso). [regola] =
 * la regola com'è adesso, se c'è ancora; [messaggio] = il testo del server.
 */
fun avvisoNovitaProposta(
    context: Context,
    novita: NovitaProposta,
    proposta: Proposta?,
    regola: Regola?,
    contesto: ContestoDispositivi,
    messaggio: String,
): Pair<String, String> = TestoProposta.avviso(
    novita = novita,
    proposta = proposta,
    regolaAdesso = regola?.let { descrizioneRegolaConDispositivo(context, it, contesto) },
    messaggio = messaggio,
    parole = ParoleNovita(
        accettataTitolo = context.getString(R.string.notifica_proposta_accettata_titolo),
        rifiutataTitolo = context.getString(R.string.notifica_proposta_rifiutata_titolo),
        ritirataTitolo = context.getString(R.string.notifica_proposta_ritirata_titolo),
        ora = context.getString(R.string.proposta_regola_ora),
        resta = context.getString(R.string.notifica_proposta_regola_resta),
        tolta = context.getString(R.string.notifica_proposta_regola_eliminata),
        regola = context.getString(R.string.proposta_regola),
        genitoreDice = context.getString(R.string.proposta_motivazione_genitore),
        ritirataEliminazione = context.getString(R.string.notifica_proposta_ritirata_eliminare),
        ritirataEliminazioneSenzaRegola = context.getString(R.string.notifica_proposta_ritirata_eliminare_senza_regola),
    ),
)

/** "oggi", "ieri", "18/09" dentro una frase (minuscolo). */
@Composable
fun giornoBreve(iso: String, oggi: LocalDate): String = giornoBreve(
    iso,
    oggi,
    ParoleGiorno(stringResource(R.string.giorno_oggi), stringResource(R.string.giorno_ieri)),
)

@Composable
fun testoDurata(minuti: Long): String {
    // Si rilegge a ogni cambio di configurazione (lingua), come stringResource.
    LocalConfiguration.current
    return testoDurata(LocalContext.current, minuti)
}

/** "48 min", "1 h", "1 h 20 min": le ore tonde senza " 0 min" in coda. */
fun testoDurata(context: Context, minuti: Long): String = when {
    minuti < 60 -> context.getString(R.string.formato_minuti, minuti)
    minuti % 60 == 0L -> context.getString(R.string.formato_ore, minuti / 60)
    else -> context.getString(R.string.formato_ore_minuti, minuti / 60, minuti % 60)
}

private val formatoOra: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

fun oraLocale(istante: Instant): String = formatoOra.format(istante.atZone(ZoneId.systemDefault()))

/** "14:32" se è di oggi, "18/09 14:32" se è più vecchio: l'età dei dati. */
fun quandoLocale(istante: Instant): String {
    val zona = ZoneId.systemDefault()
    return if (istante.atZone(zona).toLocalDate() == java.time.LocalDate.now(zona)) {
        oraLocale(istante)
    } else {
        dataOraLocale(istante)
    }
}

/**
 * Un'attesa in parole a partire dai secondi del 409 di lock: "3 giorni e
 * 4 ore", "un'ora e 20 minuti", "5 minuti". Due unità bastano: la terza è
 * precisione finta per un lock di giorni.
 */
fun testoAttesa(context: Context, secondi: Long): String {
    val minutiTotali = ((secondi + 59) / 60).coerceAtLeast(1)
    val giorni = minutiTotali / (24 * 60)
    val ore = (minutiTotali % (24 * 60)) / 60
    val minuti = minutiTotali % 60

    fun testoGiorni() = if (giorni == 1L) {
        context.getString(R.string.durata_un_giorno)
    } else {
        context.getString(R.string.durata_giorni, giorni)
    }

    fun testoOre(n: Long) = if (n == 1L) {
        context.getString(R.string.durata_un_ora)
    } else {
        context.getString(R.string.durata_ore, n)
    }

    fun testoMinuti(n: Long) = if (n == 1L) {
        context.getString(R.string.durata_un_minuto)
    } else {
        context.getString(R.string.durata_minuti, n)
    }

    return when {
        giorni > 0 && ore > 0 -> context.getString(R.string.durata_e, testoGiorni(), testoOre(ore))
        giorni > 0 -> testoGiorni()
        ore > 0 && minuti > 0 -> context.getString(R.string.durata_e, testoOre(ore), testoMinuti(minuti))
        ore > 0 -> testoOre(ore)
        else -> testoMinuti(minuti)
    }
}
