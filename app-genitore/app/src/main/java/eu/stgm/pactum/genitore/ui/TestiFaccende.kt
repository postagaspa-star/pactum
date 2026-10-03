package eu.stgm.pactum.genitore.ui

import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.CodiciErrore
import eu.stgm.pactum.genitore.dati.Faccenda
import eu.stgm.pactum.genitore.dati.MASSIMO_NOTA_FACCENDA
import eu.stgm.pactum.genitore.dati.MASSIMO_TITOLO_FACCENDA
import eu.stgm.pactum.genitore.dati.RiferimentoGenitore
import eu.stgm.pactum.genitore.dati.Sessione
import eu.stgm.pactum.genitore.dati.TipiDispositivo
import eu.stgm.pactum.genitore.rete.EsitoAbbinamento
import eu.stgm.pactum.genitore.rete.PostinoClient
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// (0.13) Le frasi dei genitori e delle faccende (contratto v3.6): da dati e logica
// (LogicaFaccende.kt) all'italiano semplice. Le parole stanno in strings.xml; qui
// si sceglie quale usare, come in Testi.kt.

private val formatoOraFaccende: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

// --- I genitori e il collegamento con il codice di 6 cifre ---------------------------

/** Il nome di un genitore; senza nome, "Genitore". */
fun nomeDelGenitore(parole: Parole, nome: String?): String =
    nome?.trim()?.takeIf { it.isNotEmpty() } ?: parole.testo(R.string.genitore_senza_nome)

/**
 * Che cosa dire dopo POST /api/abbina col tipo "genitore", quando non ha
 * collegato: ogni caso col suo motivo vero. null = collegato (lo dice chi chiama,
 * col nome).
 */
fun messaggioAbbinamento(parole: Parole, esito: EsitoAbbinamento): String? = when (esito) {
    is EsitoAbbinamento.Collegato -> null
    EsitoAbbinamento.CodiceNonValido -> parole.testo(R.string.abbina_codice_non_valido)
    is EsitoAbbinamento.TroppiTentativi ->
        messaggioRifiutoFamiglia(parole, CodiciErrore.TROPPI_TENTATIVI, esito.riprovaTraSecondi)
    is EsitoAbbinamento.TipoNonCorrispondente -> parole.testo(
        when (esito.tipoAtteso) {
            TipiDispositivo.TELEFONO -> R.string.abbina_tipo_telefono
            TipiDispositivo.COMPUTER -> R.string.abbina_tipo_computer
            else -> R.string.abbina_tipo_altro
        },
    )
    EsitoAbbinamento.ServerDaAggiornare -> parole.testo(R.string.abbina_server_da_aggiornare)
    EsitoAbbinamento.ServerSenzaCodici -> parole.testo(R.string.abbina_server_senza_codici)
    EsitoAbbinamento.SenzaRete -> parole.testo(R.string.abbina_senza_rete)
    EsitoAbbinamento.Errore -> parole.testo(R.string.abbina_errore)
}

/** "Collegato: sei «Mamma»." (o solo "Collegato.", se il server non dice il nome). */
fun testoCollegato(parole: Parole, genitore: RiferimentoGenitore?): String =
    genitore?.nome?.trim()?.takeIf { it.isNotEmpty() }?.let { parole.testo(R.string.connessione_collegato_fatto, it) }
        ?: parole.testo(R.string.connessione_collegato_fatto_senza_nome)

// --- Le faccende -----------------------------------------------------------------------

/**
 * Lo stato del blocco in una riga: "Blocco attivo dalle 16:00", "Il blocco parte
 * alle 16:00" (o "il 03/10 alle 07:30"), "Niente da fare: nessun blocco." Con
 * faccende da fare ma nessun blocco né attivo né in arrivo (non dovrebbe succedere)
 * null: si dice solo quante sono.
 */
fun testoStatoBlocco(
    parole: Parole,
    stato: StatoBlocco,
    zona: ZoneId = ZoneId.systemDefault(),
    oggi: LocalDate = LocalDate.now(zona),
): String? {
    val dal = stato.dal
    val prossimo = stato.prossimo
    return when {
        stato.attivo -> dal?.let { parole.testo(R.string.blocco_attivo_dal, dalleQuando(parole, it, zona, oggi)) }
            ?: parole.testo(R.string.blocco_attivo)
        prossimo != null -> parole.testo(R.string.blocco_parte, alleQuando(parole, prossimo, zona, oggi))
        stato.daFare == 0 -> parole.testo(R.string.blocco_nessuno)
        else -> null
    }
}

/** "Una faccenda da fare", "3 faccende da fare"; null se nessuna. */
fun testoQuanteDaFare(parole: Parole, quante: Int): String? = when {
    quante <= 0 -> null
    quante == 1 -> parole.testo(R.string.faccende_una_da_fare)
    else -> parole.testo(R.string.faccende_quante_da_fare, quante)
}

/** "Una foto arrivata da meno di 24 ore…"; null se nessuna. */
fun testoFotoDaGuardare(parole: Parole, quante: Int): String? = when {
    quante <= 0 -> null
    quante == 1 -> parole.testo(R.string.faccende_foto_da_guardare_una)
    else -> parole.testo(R.string.faccende_foto_da_guardare, quante)
}

/** Quante faccende fatte si possono ancora bocciare adesso: le foto da guardare. */
fun fotoDaGuardare(faccende: List<Faccenda>, adesso: Instant): Int =
    faccende.count { bocciabile(it, adesso) is Bocciabile.Si }

/** Che cosa vuol dire dare faccende, col nome del figlio (o "tuo figlio"). */
fun spiegaFaccende(parole: Parole, nomeFiglio: String?): String =
    nomeDaScrivere(nomeFiglio)?.let { parole.testo(R.string.faccende_spiega, it) }
        ?: parole.testo(R.string.faccende_spiega_senza_nome)

/** "data da Mamma", "data da te"; null se non si sa. */
fun testoDataDa(parole: Parole, faccenda: Faccenda, io: RiferimentoGenitore?): String? =
    when (val chi = chiHaFatto(faccenda.creataDa, io)) {
        ChiHaFatto.Tu -> parole.testo(R.string.faccenda_data_da_te)
        is ChiHaFatto.Altro -> parole.testo(R.string.faccenda_data_da, chi.nome)
        ChiHaFatto.NonSi -> null
    }

/** "blocca dalle 16:00" per una faccenda il cui blocco deve ancora partire; null se è già partito. */
fun testoBloccaDalle(
    parole: Parole,
    faccenda: Faccenda,
    adesso: Instant,
    zona: ZoneId = ZoneId.systemDefault(),
    oggi: LocalDate = LocalDate.now(zona),
): String? = bloccoFuturo(faccenda, adesso)?.let { parole.testo(R.string.faccenda_blocca, dalleQuando(parole, it, zona, oggi)) }

/**
 * Le bocciature di una faccenda: quante volte ("Bocciata una volta", "Bocciata 2
 * volte") e l'ultima, con chi e perché ("L'ultima volta da Papà: «non è pulito»").
 * Vuoto se non è mai stata bocciata.
 */
fun righeBocciature(parole: Parole, faccenda: Faccenda, io: RiferimentoGenitore?): List<String> {
    val volte = faccenda.bocciature
    val ultima = faccenda.ultimaBocciatura
    if (volte <= 0 && ultima == null) return emptyList()
    val quante = if (volte <= 1) {
        parole.testo(R.string.faccenda_bocciata_una_volta)
    } else {
        parole.testo(R.string.faccenda_bocciata_volte, volte)
    }
    val nota = ultima?.nota?.trim()?.takeIf { it.isNotEmpty() }
    val dettaglio = when (val chi = chiHaFatto(ultima?.da, io)) {
        ChiHaFatto.Tu -> nota?.let { parole.testo(R.string.faccenda_ultima_bocciatura_tua, it) }
            ?: parole.testo(R.string.faccenda_ultima_bocciatura_tua_senza_nota)
        is ChiHaFatto.Altro -> nota?.let { parole.testo(R.string.faccenda_ultima_bocciatura, chi.nome, it) }
            ?: parole.testo(R.string.faccenda_ultima_bocciatura_senza_nota, chi.nome)
        ChiHaFatto.NonSi -> nota?.let { "«$it»" }
    }
    return listOfNotNull(quante, dettaglio)
}

/** "Annullata da Mamma", "Annullata da te", "Annullata". */
fun testoAnnullata(parole: Parole, faccenda: Faccenda, io: RiferimentoGenitore?): String =
    when (val chi = chiHaFatto(faccenda.annullataDa, io)) {
        ChiHaFatto.Tu -> parole.testo(R.string.faccenda_annullata_da_te)
        is ChiHaFatto.Altro -> parole.testo(R.string.faccenda_annullata_da, chi.nome)
        ChiHaFatto.NonSi -> parole.testo(R.string.faccenda_annullata)
    }

/**
 * Quanto manca per bocciare: "Puoi bocciarla ancora per 3 h 20 min." (per difetto:
 * mai più tempo di quello che c'è), "…per meno di un minuto.", oppure "Sono
 * passate 24 ore dalla foto: non si può più bocciare." null = non c'è niente da
 * bocciare.
 */
fun testoBocciabile(parole: Parole, stato: Bocciabile): String? = when (stato) {
    is Bocciabile.Si -> {
        val minuti = stato.resta.toMinutes()
        if (minuti < 1) {
            parole.testo(R.string.faccenda_boccia_resta_poco)
        } else {
            parole.testo(R.string.faccenda_boccia_resta, testoDurata(parole, minuti))
        }
    }
    Bocciabile.Scaduta -> parole.testo(R.string.faccenda_boccia_scaduta)
    Bocciabile.No -> null
}

/** Il giorno scritto per intero, come lo dice una persona: "domenica 25/10". */
private val formatoGiornoFaccende: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE dd/MM", Locale.ITALIAN)

/**
 * Che cosa succede col blocco scelto, detto prima di mandare: [inizio] null =
 * subito; altrimenti oggi o, se l'ora è già passata, domani — sempre con la data
 * scritta ("domani, domenica 25/10, alle 08:00"): "domani" da solo, con un dialogo
 * rimasto aperto a cavallo di mezzanotte, direbbe il giorno sbagliato. È la cosa
 * che il genitore deve sapere prima del tocco.
 */
fun testoInizioBlocco(parole: Parole, inizio: InizioBlocco?, nomeFiglio: String?): String {
    if (inizio == null) {
        return nomeDaScrivere(nomeFiglio)?.let { parole.testo(R.string.dai_succede_subito, it) }
            ?: parole.testo(R.string.dai_succede_subito_senza_nome)
    }
    val ora = formatoOraFaccende.format(inizio.quando)
    val giorno = formatoGiornoFaccende.format(inizio.quando)
    return parole.testo(if (inizio.domani) R.string.dai_succede_domani else R.string.dai_succede_oggi, ora, giorno)
}

/** Il pulsante dell'ora scelta: "Dalle 16:00". */
fun testoDalle(parole: Parole, ora: LocalTime): String = parole.testo(R.string.dai_dalle, formatoOraFaccende.format(ora))

/** Un dispositivo su cui il blocco non parte, in una frase. */
fun testoDispositivoSenzaBlocco(parole: Parole, senza: DispositivoSenzaBlocco): String {
    val nome = nomeDelDispositivo(parole, senza.dispositivo.nome, senza.dispositivo.tipo)
    return senza.versione?.let { parole.testo(R.string.faccende_versione_vecchia, nome, it) }
        ?: parole.testo(R.string.faccende_versione_sconosciuta, nome)
}

/** Che cosa non va in un titolo, sotto il suo campo; null = va bene. */
fun testoProblemaTitolo(parole: Parole, problema: ProblemaTesto?): String? = when (problema) {
    ProblemaTesto.VUOTO -> parole.testo(R.string.dai_errore_vuoto)
    ProblemaTesto.TROPPO_LUNGO -> parole.testo(R.string.dai_errore_lungo, MASSIMO_TITOLO_FACCENDA)
    ProblemaTesto.CARATTERI_INVISIBILI -> parole.testo(R.string.dai_errore_invisibili)
    null -> null
}

/** Che cosa non va in una nota; null = va bene (anche vuota). */
fun testoProblemaNota(parole: Parole, problema: ProblemaTesto?): String? = when (problema) {
    ProblemaTesto.TROPPO_LUNGO -> parole.testo(R.string.dai_errore_lungo, MASSIMO_NOTA_FACCENDA)
    ProblemaTesto.CARATTERI_INVISIBILI -> parole.testo(R.string.dai_errore_invisibili)
    ProblemaTesto.VUOTO, null -> null
}

/** I gesti sulle faccende, per dire il rifiuto giusto. */
enum class GestoFaccende { DAI, BOCCIA, ANNULLA }

/**
 * Che cosa dire quando il server non prende un gesto sulle faccende, ciascun
 * rifiuto col suo motivo vero. Un 404 dopo "Dai faccende" è il figlio che non c'è
 * più; dopo "Boccia" o "Annulla", la faccenda. Un codice che non si conosce dice
 * solo che il server non ha accettato: non si inventa un perché.
 */
fun messaggioRifiutoFaccende(parole: Parole, codice: String?, gesto: GestoFaccende, nomeFiglio: String?): String =
    when (codice) {
        CodiciErrore.TROPPE_FACCENDE -> nomeDaScrivere(nomeFiglio)?.let { parole.testo(R.string.faccende_errore_troppe, it) }
            ?: parole.testo(R.string.faccende_errore_troppe_senza_nome)
        CodiciErrore.NON_BOCCIABILE -> parole.testo(R.string.faccende_errore_non_bocciabile)
        CodiciErrore.NON_ANNULLABILE -> parole.testo(R.string.faccende_errore_non_annullabile)
        CodiciErrore.NON_TROVATO -> parole.testo(
            if (gesto == GestoFaccende.DAI) R.string.faccende_errore_figlio_non_trovato else R.string.faccende_errore_non_trovata,
        )
        CodiciErrore.SERVER_DA_AGGIORNARE -> parole.testo(R.string.faccende_server_vecchio)
        CodiciErrore.COLLEGAMENTO_NON_VALIDO -> parole.testo(R.string.collegamento_non_valido)
        PostinoClient.PARAMETRI_NON_VALIDI -> parole.testo(R.string.faccende_errore_parametri)
        CodiciErrore.ESITO_INCERTO -> parole.testo(R.string.faccende_errore_esito_incerto)
        null -> parole.testo(R.string.faccende_errore_rete)
        else -> parole.testo(R.string.faccende_errore_rifiutato)
    }

/** "Faccenda data." o "Faccende date: 3." */
fun testoFaccendeDate(parole: Parole, quante: Int): String =
    if (quante <= 1) parole.testo(R.string.dai_fatto_una) else parole.testo(R.string.dai_fatto, quante)

/**
 * "ultima decisione: Mamma", "ultima decisione: tu"; null se non si sa chi.
 * `decisa_da` (contratto v3.6) è l'ULTIMA decisione sulla sessione: può essere
 * anche il no a un cambio chiesto dopo, quindi non si scrive "approvata da".
 */
fun testoUltimaDecisione(parole: Parole, sessione: Sessione, io: RiferimentoGenitore?): String? =
    when (val chi = chiHaFatto(sessione.decisaDa, io)) {
        ChiHaFatto.Tu -> parole.testo(R.string.sessione_ultima_decisione_tua)
        is ChiHaFatto.Altro -> parole.testo(R.string.sessione_ultima_decisione, chi.nome)
        ChiHaFatto.NonSi -> null
    }
