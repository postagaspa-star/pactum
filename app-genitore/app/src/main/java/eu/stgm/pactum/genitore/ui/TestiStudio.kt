package eu.stgm.pactum.genitore.ui

import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.ChiusureStudio
import eu.stgm.pactum.genitore.dati.CodiciErrore
import eu.stgm.pactum.genitore.dati.ContenutoStudio
import eu.stgm.pactum.genitore.dati.EsitiSessione
import eu.stgm.pactum.genitore.dati.EsitiTratto
import eu.stgm.pactum.genitore.dati.ListaComputerStudio
import eu.stgm.pactum.genitore.dati.ListaTelefonoStudio
import eu.stgm.pactum.genitore.dati.MASSIMO_MOTIVO_STUDIO
import eu.stgm.pactum.genitore.dati.MINIMO_MOTIVO_STUDIO
import eu.stgm.pactum.genitore.dati.OriginiStudio
import eu.stgm.pactum.genitore.dati.RiferimentoGenitore
import eu.stgm.pactum.genitore.dati.StudioSvolto
import eu.stgm.pactum.genitore.dati.TipiDispositivo
import eu.stgm.pactum.genitore.dati.TipiTratto
import eu.stgm.pactum.genitore.dati.TrattoStudio
import eu.stgm.pactum.genitore.rete.PostinoClient
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.TextStyle
import java.util.Locale

// (0.18, contratto v4.0, parte C) Le frasi della Sessione Studio: da dati e logica
// (LogicaStudio.kt) all'italiano semplice. "Sessione Studio" nei titoli, "lo
// Studio" nel discorso. I minuti si dicono SEMPRE "dichiarati col timer": il
// genitore deve sapere che cosa legge (non è attività verificata).

private val formatoOraStudio: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val formatoGiornoStudio: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")

/**
 * L'ora di un istante del server nel fuso [zona]: "15:00"; "—" se non si legge. Per
 * gli orari dello Studio è SEMPRE il fuso del patto (parte E, «Fuso»): gli orari
 * approvati ("15:00", "16:00") sono già lì, e un genitore in viaggio deve leggere
 * le stesse ore della configurazione e lo stesso "oggi".
 */
private fun oraDi(ts: String?, zona: ZoneId): String =
    istanteServer(ts)?.let { formatoOraStudio.format(it.atZone(zona)) } ?: DATO_MANCANTE

// --- Lo Studio in corso e quelli fatti -------------------------------------------------

/**
 * Lo Studio in corso in una riga: "Studio dalle 15:00 · 42 min dichiarati col
 * timer su 60 · si chiude dopo le 16:00" (l'ultima parte solo se le 16:00 devono
 * ancora venire; senza un minimo, senza "su 60").
 */
fun testoStatoStudio(
    parole: Parole,
    studio: StudioSvolto,
    adesso: Instant,
    zona: ZoneId = FUSO_PATTO,
): String {
    val dalle = oraDi(studio.inizioTs, zona)
    val minuti = minutiDichiarati(studio)
    val base = studio.minutiMinimi
        ?.let { parole.testo(R.string.studio_stato, dalle, minuti, it) }
        ?: parole.testo(R.string.studio_stato_senza_minimo, dalle, minuti)
    val dopo = chiudeDopo(studio, adesso) ?: return base
    return parole.testo(R.string.studio_stato_chiude_dopo, base, formatoOraStudio.format(dopo.atZone(zona)))
}

/** "Luca lo può già chiudere dal suo telefono." quando le condizioni ci sono (per il server); null se no. */
fun testoChiudibile(parole: Parole, studio: StudioSvolto, nomeFiglio: String?): String? {
    if (!studio.chiudibile || studio.fineTs != null) return null
    return nomeDaScrivere(nomeFiglio)?.let { parole.testo(R.string.studio_chiudibile, it) }
        ?: parole.testo(R.string.studio_chiudibile_senza_nome)
}

/**
 * Uno Studio chiuso in una riga: "Studio dalle 15:00, chiuso alle 17:40 da Luca ·
 * 65 min dichiarati col timer", "… da Mamma …", "… da te …", oppure "Studio dalle
 * 15:00, non chiuso · 40 min dichiarati col timer". In corso: lo stato.
 */
fun testoStudioSvolto(
    parole: Parole,
    studio: StudioSvolto,
    io: RiferimentoGenitore?,
    nomeFiglio: String?,
    adesso: Instant,
    zona: ZoneId = FUSO_PATTO,
): String {
    if (studio.fineTs == null) return testoStatoStudio(parole, studio, adesso, zona)
    val dalle = oraDi(studio.inizioTs, zona)
    val alle = oraDi(studio.fineTs, zona)
    val minuti = minutiDichiarati(studio)
    return when (studio.chiusura) {
        ChiusureStudio.GENITORE -> {
            val chi = studio.chiusaDa
            when {
                chi != null && io != null && chi.id == io.id -> parole.testo(R.string.studio_chiuso_da_te, dalle, alle, minuti)
                else -> parole.testo(
                    R.string.studio_chiuso_genitore,
                    dalle,
                    alle,
                    nomeDelGenitore(parole, chi?.nome),
                    minuti,
                )
            }
        }
        ChiusureStudio.NON_CHIUSO -> parole.testo(R.string.studio_non_chiuso, dalle, minuti)
        else -> nomeDaScrivere(nomeFiglio)
            ?.let { parole.testo(R.string.studio_chiuso_figlio, dalle, alle, it, minuti) }
            ?: parole.testo(R.string.studio_chiuso_figlio_senza_nome, dalle, alle, minuti)
    }
}

/** "Cosa ha fatto: «…»" (la dichiarazione del figlio), "Motivo: «…»" (del genitore): le righe sotto. */
fun righeChiusuraStudio(parole: Parole, studio: StudioSvolto): List<String> = listOfNotNull(
    studio.dichiarazione?.trim()?.takeIf { it.isNotEmpty() }?.let { parole.testo(R.string.studio_dichiarazione, it) },
    studio.motivo?.trim()?.takeIf { it.isNotEmpty() }?.let { parole.testo(R.string.studio_motivo, it) },
)

/** Come è cominciato: "partito da solo", "avviato a mano da «Telefono di Luca»". */
fun testoOrigineStudio(parole: Parole, studio: StudioSvolto): String =
    if (studio.origine == OriginiStudio.MANUALE) {
        nomeDaScrivere(studio.avviatoDa?.nome)?.let { parole.testo(R.string.studio_avviato_a_mano, it) }
            ?: parole.testo(R.string.studio_avviato_a_mano_senza_nome)
    } else {
        parole.testo(R.string.studio_partito_da_solo)
    }

/** "Lo Studio di ieri non è stato chiuso: 40 min dichiarati col timer." */
fun testoIeriNonChiuso(parole: Parole, studio: StudioSvolto): String =
    parole.testo(R.string.studio_ieri_non_chiuso, minutiDichiarati(studio))

/**
 * Un tratto di attività: "Compiti (matematica) · 30 min", "Lavori di casa: «Svuota
 * la lavastoviglie» · 10 min", "Altro (allenamento) · 40 min · interrotto",
 * "Compiti · in corso". [titoloLavoro] = il titolo del lavoro di casa, se si sa.
 */
fun testoTratto(parole: Parole, tratto: TrattoStudio, titoloLavoro: (Long) -> String? = { null }): String {
    val tipo = parole.testo(
        when (tratto.tipo) {
            TipiTratto.COMPITI -> R.string.tratto_compiti
            TipiTratto.LAVORI_DI_CASA -> R.string.tratto_lavori_di_casa
            else -> R.string.tratto_altro
        },
    )
    val lavoro = tratto.faccendaId?.takeIf { tratto.tipo == TipiTratto.LAVORI_DI_CASA }?.let(titoloLavoro)
    val parola = tratto.parola?.trim()?.takeIf { it.isNotEmpty() }
    val cosa = when {
        lavoro != null -> parole.testo(R.string.tratto_lavoro, tipo, lavoro)
        parola != null -> parole.testo(R.string.tratto_con_parola, tipo, parola)
        else -> tipo
    }
    if (trattoInCorso(tratto)) return parole.testo(R.string.tratto_in_corso, cosa)
    val pezzi = listOfNotNull(
        parole.testo(R.string.tratto_minuti, cosa, minutiTratto(tratto)),
        parole.testo(R.string.tratto_interrotto).takeIf { tratto.esito == EsitiTratto.INTERROTTO },
        parole.testo(R.string.tratto_non_contato).takeIf { tratto.conta == false },
    )
    return pezzi.joinToString(" · ")
}

// --- La configurazione -----------------------------------------------------------------

private val GIORNI_SETTIMANA = mapOf(
    "lun" to DayOfWeek.MONDAY,
    "mar" to DayOfWeek.TUESDAY,
    "mer" to DayOfWeek.WEDNESDAY,
    "gio" to DayOfWeek.THURSDAY,
    "ven" to DayOfWeek.FRIDAY,
    "sab" to DayOfWeek.SATURDAY,
    "dom" to DayOfWeek.SUNDAY,
)

private fun nomeGiorno(giorno: String): String =
    GIORNI_SETTIMANA[giorno]?.getDisplayName(TextStyle.FULL, Locale.ITALIAN) ?: giorno

/**
 * I giorni dello Studio come li direbbe una persona: "tutti i giorni", "dal lunedì
 * al venerdì" (tre o più di fila), altrimenti "lunedì, mercoledì e venerdì".
 */
fun testoGiorniStudio(parole: Parole, giorni: List<String>): String {
    val ordinati = giorniOrdinati(giorni)
    if (ordinati.size == GIORNI_STUDIO.size) return parole.testo(R.string.studio_giorni_tutti)
    if (ordinati.isEmpty()) return DATO_MANCANTE
    val indici = ordinati.map { GIORNI_STUDIO.indexOf(it) }
    val diFila = indici.zipWithNext().all { (a, b) -> b == a + 1 }
    if (diFila && ordinati.size >= 3) {
        return parole.testo(R.string.studio_giorni_da_a, nomeGiorno(ordinati.first()), nomeGiorno(ordinati.last()))
    }
    val nomi = ordinati.map(::nomeGiorno)
    return if (nomi.size == 1) nomi.first() else parole.testo(R.string.elenco_ultimo, nomi.dropLast(1).joinToString(", "), nomi.last())
}

/** "Dalle 15:00, si chiude dopo le 16:00". */
fun testoOrariStudio(parole: Parole, contenuto: ContenutoStudio): String =
    parole.testo(R.string.studio_orari, contenuto.inizio ?: DATO_MANCANTE, contenuto.chiusuraMinima ?: DATO_MANCANTE)

/** "Almeno 60 min di attività col timer"; null se il minimo non si sa. */
fun testoMinimoStudio(parole: Parole, contenuto: ContenutoStudio): String? =
    contenuto.minutiMinimi?.let { parole.testo(R.string.studio_minimo, it) }

/**
 * Il nome di una voce della lista del telefono. [conChiave] = accanto
 * all'etichetta anche il pacchetto ("ClasseViva (com.epicgames.fortnite)"): nelle
 * richieste da approvare, perché un'etichetta mandata dal figlio può nascondere
 * un'altra app. Quando il nome È il pacchetto, lo si scrive una volta sola.
 */
fun testoVoceTelefono(parole: Parole, chiave: String, nomi: Map<String, String>, conChiave: Boolean = false): String =
    when (val voce = voceTelefono(chiave, nomi)) {
        VoceTelefono.FuoriStore -> parole.testo(R.string.studio_app_fuori_store)
        is VoceTelefono.App -> voce.pacchetto?.takeIf { conChiave }
            ?.let { parole.testo(R.string.studio_voce_con_chiave, voce.nome, it) }
            ?: voce.nome
    }

/**
 * Il nome di un programma: l'etichetta e, con [conChiave], il nome del file
 * accanto ("Word (winword.exe)") se è diverso dall'etichetta.
 */
private fun nomeProgramma(parole: Parole, voce: VoceComputer.Programma, conChiave: Boolean): String =
    if (conChiave && !voce.nome.equals(voce.file, ignoreCase = true)) {
        parole.testo(R.string.studio_voce_con_chiave, voce.nome, voce.file)
    } else {
        voce.nome
    }

/**
 * Il nome di una voce della lista del computer: "Word, firmato da Microsoft
 * Corporation", "classeviva.it (sito)". [conChiave]: v. [testoVoceTelefono]
 * ("Word (winword.exe), firmato da Microsoft Corporation").
 */
fun testoVoceComputer(
    parole: Parole,
    chiave: String,
    nomi: Map<String, String>,
    firme: Map<String, String>,
    conChiave: Boolean = false,
): String =
    when (val voce = voceComputer(chiave, nomi, firme)) {
        is VoceComputer.Sito -> parole.testo(R.string.studio_sito, voce.dominio)
        is VoceComputer.Programma -> {
            val nome = nomeProgramma(parole, voce, conChiave)
            voce.firma?.let { parole.testo(R.string.studio_firmato, nome, it) } ?: nome
        }
    }

/**
 * "Sul telefono: ClasseViva, Duolingo" (o "nessuna app: solo quelle sempre
 * usabili"). [conChiave] = per quali voci scrivere anche il pacchetto.
 */
fun testoListaTelefono(parole: Parole, lista: ListaTelefonoStudio?, conChiave: (String) -> Boolean = { false }): String {
    val voci = lista?.app.orEmpty().distinct().map { testoVoceTelefono(parole, it, lista?.nomi.orEmpty(), conChiave(it)) }
    return parole.testo(R.string.studio_lista_telefono, voci.joinToString(", ").ifEmpty { parole.testo(R.string.studio_lista_vuota) })
}

/** "Sul computer: Word, firmato da Microsoft Corporation, classeviva.it (sito)". [conChiave]: v. [testoListaTelefono]. */
fun testoListaComputer(parole: Parole, lista: ListaComputerStudio?, conChiave: (String) -> Boolean = { false }): String {
    val voci = lista?.programmi.orEmpty().distinct()
        .map { testoVoceComputer(parole, it, lista?.nomi.orEmpty(), lista?.firme.orEmpty(), conChiave(it)) }
    return parole.testo(
        R.string.studio_lista_computer,
        voci.joinToString(" · ").ifEmpty { parole.testo(R.string.studio_lista_vuota_computer) },
    )
}

/** Le righe di una configurazione: giorni, orari, minimo, le due liste. */
fun righeConfigStudio(parole: Parole, contenuto: ContenutoStudio): List<String> = listOfNotNull(
    testoGiorniStudio(parole, contenuto.giorni).replaceFirstChar { it.uppercase() },
    testoOrariStudio(parole, contenuto),
    testoMinimoStudio(parole, contenuto),
    testoListaTelefono(parole, contenuto.telefono),
    testoListaComputer(parole, contenuto.computer),
)

/**
 * Chi l'ha approvata: "Approvata da Mamma il 05/10 18:00", "Approvata da te oggi
 * 15:10", o, senza nessuno (la prima configurazione, scritta all'aggiornamento),
 * "Decisa dalla famiglia all'aggiornamento".
 */
fun testoDecisaStudio(
    parole: Parole,
    contenuto: ContenutoStudio,
    io: RiferimentoGenitore?,
    zona: ZoneId = ZoneId.systemDefault(),
    oggi: LocalDate = LocalDate.now(zona),
): String {
    val chi = contenuto.decisaDa ?: return parole.testo(R.string.studio_decisa_famiglia)
    val quando = istanteServer(contenuto.approvataTs)?.let { testoQuando(parole, it, zona, oggi) } ?: ""
    return if (io != null && chi.id == io.id) {
        parole.testo(R.string.studio_decisa_da_te, quando).trim()
    } else {
        parole.testo(R.string.studio_decisa_da, nomeDelGenitore(parole, chi.nome), quando).trim()
    }
}

/**
 * Da quando valgono gli orari approvati, se è ancora da venire ([oggi] nel fuso
 * del patto): "Questi orari valgono da domani." / "…dal 09/10."; null se valgono già.
 */
fun testoOrariDal(parole: Parole, orariDal: String?, oggi: LocalDate): String? {
    val giorno = try {
        orariDal?.let(LocalDate::parse)
    } catch (e: DateTimeParseException) {
        null
    } ?: return null
    return when {
        !giorno.isAfter(oggi) -> null
        giorno == oggi.plusDays(1) -> parole.testo(R.string.studio_orari_da_domani)
        else -> parole.testo(R.string.studio_orari_dal, formatoGiornoStudio.format(giorno))
    }
}

/** "Versione 4 · Approvata da Mamma il 05/10 18:00 · orari dal 06/10". */
fun testoVersioneStudio(
    parole: Parole,
    contenuto: ContenutoStudio,
    io: RiferimentoGenitore?,
    zona: ZoneId = ZoneId.systemDefault(),
    oggi: LocalDate = LocalDate.now(zona),
): String = listOfNotNull(
    contenuto.versione?.let { parole.testo(R.string.studio_versione, it) },
    testoDecisaStudio(parole, contenuto, io, zona, oggi),
    contenuto.orariDal?.let { dal ->
        try {
            parole.testo(R.string.studio_versione_orari_dal, formatoGiornoStudio.format(LocalDate.parse(dal)))
        } catch (e: DateTimeParseException) {
            null
        }
    },
).joinToString(" · ")

// --- La configurazione da approvare ------------------------------------------------------

/** "Luca chiede di approvare lo Studio" / "…di cambiare lo Studio". */
fun testoChiedeStudio(parole: Parole, nomeFiglio: String?, cambio: Boolean): String {
    val nome = nomeDaScrivere(nomeFiglio)
    return when {
        cambio && nome != null -> parole.testo(R.string.studio_chiede_cambiare, nome)
        cambio -> parole.testo(R.string.studio_chiede_cambiare_senza_nome)
        nome != null -> parole.testo(R.string.studio_chiede_approvare, nome)
        else -> parole.testo(R.string.studio_chiede_approvare_senza_nome)
    }
}

/** "Dal telefono «Telefono di Luca» · oggi 15:10"; null se non si sa da dove. */
fun testoPropostaDa(
    parole: Parole,
    contenuto: ContenutoStudio,
    zona: ZoneId = ZoneId.systemDefault(),
    oggi: LocalDate = LocalDate.now(zona),
): String? {
    val quando = istanteServer(contenuto.richiestaTs)?.let { testoQuando(parole, it, zona, oggi) }
    val da = contenuto.da
    val nome = nomeDaScrivere(da?.nome)
    if (da == null || nome == null) return quando
    val tipo = parole.testo(if (da.tipo == TipiDispositivo.COMPUTER) R.string.dispositivo_computer else R.string.dispositivo_telefono)
        .lowercase(Locale.ITALIAN)
    return parole.testo(R.string.studio_proposta_da, tipo, nome, quando ?: DATO_MANCANTE)
}

/**
 * Le righe della card da approvare: com'è la proposta (giorni, orari, minimo, le
 * due liste) con, per un cambio, com'era prima ("prima: …") e che cosa le liste
 * aggiungono e tolgono rispetto all'approvata; poi quando vale: "I nuovi orari
 * valgono da domani." e "La nuova lista vale dal prossimo Studio.".
 */
fun righeRichiestaStudio(parole: Parole, richiesta: RichiestaStudio): List<String> {
    val cambi = cambiStudio(richiesta)
    val prima = richiesta.approvata
    val dopo = richiesta.proposta
    fun conPrima(riga: String, cambiato: Boolean, vecchia: String?): String =
        if (prima != null && cambiato && vecchia != null) "$riga · ${parole.testo(R.string.studio_prima, vecchia)}" else riga
    val righe = mutableListOf<String>()
    righe += conPrima(
        testoGiorniStudio(parole, dopo.giorni).replaceFirstChar { it.uppercase() },
        cambi.giorni,
        prima?.let { testoGiorniStudio(parole, it.giorni) },
    )
    righe += conPrima(
        testoOrariStudio(parole, dopo),
        cambi.inizio || cambi.chiusuraMinima,
        prima?.let { testoOrariStudio(parole, it).replaceFirstChar { c -> c.lowercase() } },
    )
    testoMinimoStudio(parole, dopo)?.let { minimo ->
        righe += conPrima(minimo, cambi.minutiMinimi, prima?.let { testoMinimoStudio(parole, it)?.replaceFirstChar { c -> c.lowercase() } })
    }
    // Le etichette: quelle della proposta, e per le app che non ne hanno quelle già
    // note dall'approvata. Le firme no: valgono solo quelle della proposta.
    // (correzione 0.18) Accanto a ogni voce NUOVA anche il nome tecnico (il pacchetto,
    // il nome del file): l'etichetta la può mandare il figlio stesso, e se l'app non è
    // mai stata vista nell'uso il server rimanda quella. "ClasseViva" può essere
    // Fortnite: il nome tecnico no.
    val nomiTelefono = prima?.telefono?.nomi.orEmpty() + dopo.telefono?.nomi.orEmpty()
    val appNuove = cambi.appAggiunte.toSet()
    righe += testoListaTelefono(parole, dopo.telefono?.copy(nomi = nomiTelefono)) { it in appNuove }
    if (prima != null) {
        val nomi = nomiTelefono
        cambi.appAggiunte.takeIf { it.isNotEmpty() }
            ?.let { righe += parole.testo(R.string.studio_aggiunge, it.joinToString(", ") { k -> testoVoceTelefono(parole, k, nomi, conChiave = true) }) }
        cambi.appTolte.takeIf { it.isNotEmpty() }
            ?.let { righe += parole.testo(R.string.studio_toglie, it.joinToString(", ") { k -> testoVoceTelefono(parole, k, nomi) }) }
        cambi.nomiTelefonoCambiati.forEach { righe += parole.testo(R.string.studio_nome_cambiato, it.chiave.trim(), it.dopo, it.prima) }
    }
    // (correzione 0.18) La lista del telefono proposta (o cambiata) dall'ultimo che ha
    // proposto, un computer: lo si dice chiaro.
    if (listaTelefonoDalComputer(richiesta, cambi)) righe += parole.testo(R.string.studio_lista_telefono_dal_computer)
    val nomiComputer = prima?.computer?.nomi.orEmpty() + dopo.computer?.nomi.orEmpty()
    val programmiNuovi = cambi.programmiAggiunti.toSet()
    righe += testoListaComputer(parole, dopo.computer?.copy(nomi = nomiComputer)) { it in programmiNuovi }
    if (prima != null) {
        val nomi = nomiComputer
        // Per quello che toglie, la firma che aveva; per quello che aggiunge, quella proposta.
        val firme = prima.computer?.firme.orEmpty() + dopo.computer?.firme.orEmpty()
        cambi.programmiAggiunti.takeIf { it.isNotEmpty() }
            ?.let { righe += parole.testo(R.string.studio_aggiunge, it.joinToString(" · ") { k -> testoVoceComputer(parole, k, nomi, firme, conChiave = true) }) }
        cambi.programmiTolti.takeIf { it.isNotEmpty() }
            ?.let { righe += parole.testo(R.string.studio_toglie, it.joinToString(" · ") { k -> testoVoceComputer(parole, k, nomi, firme) }) }
        cambi.nomiComputerCambiati.forEach {
            righe += parole.testo(R.string.studio_nome_cambiato, it.chiave.trim().removePrefix("exe:"), it.dopo, it.prima)
        }
        // (correzione 0.18) Una firma tolta o cambiata si dice in evidenza: senza firma,
        // qualunque programma rinominato winword.exe passerebbe (parte C, «Programmi firmati»).
        cambi.firmeCambiate.forEach { cambio ->
            val voce = voceComputer(cambio.chiave, nomi, emptyMap()) as? VoceComputer.Programma ?: return@forEach
            val nome = nomeProgramma(parole, voce, conChiave = true)
            righe += when {
                cambio.dopo == null -> parole.testo(R.string.studio_firma_tolta, nome, cambio.prima ?: DATO_MANCANTE)
                cambio.prima == null -> parole.testo(R.string.studio_firma_nuova, nome, cambio.dopo)
                else -> parole.testo(R.string.studio_firma_cambiata, nome, cambio.dopo, cambio.prima)
            }
        }
    }
    return righe
}

/** Quando valgono i cambi: "I nuovi orari valgono da domani.", "La nuova lista vale dal prossimo Studio.". */
fun righeQuandoValeStudio(parole: Parole, richiesta: RichiestaStudio): List<String> {
    val cambi = cambiStudio(richiesta)
    return listOfNotNull(
        parole.testo(R.string.studio_nuovi_orari_domani).takeIf { cambi.orari },
        parole.testo(R.string.studio_nuova_lista_prossimo).takeIf { richiesta.cambio && cambi.liste },
    )
}

// --- Il motivo della chiusura e gli esiti --------------------------------------------------

/** Sotto il campo del motivo: l'aiuto, o che cosa non va. */
fun testoProblemaMotivo(parole: Parole, problema: ProblemaMotivo?): String = when (problema) {
    ProblemaMotivo.CORTO -> parole.testo(R.string.studio_chiudi_corto, MINIMO_MOTIVO_STUDIO)
    ProblemaMotivo.LUNGO -> parole.testo(R.string.dai_errore_lungo, MASSIMO_MOTIVO_STUDIO)
    ProblemaMotivo.INVISIBILI -> parole.testo(R.string.dai_errore_invisibili)
    ProblemaMotivo.VUOTO, null -> parole.testo(R.string.studio_chiudi_aiuto, MINIMO_MOTIVO_STUDIO, MASSIMO_MOTIVO_STUDIO)
}

/** Che cosa dire dopo un gesto sullo Studio. */
fun messaggioEventoStudio(parole: Parole, evento: EventoStudio): String = when (evento) {
    is EventoStudio.Decisa -> parole.testo(if (evento.esito == EsitiSessione.APPROVA) R.string.studio_approvato else R.string.studio_rifiutato)
    EventoStudio.Cambiata -> parole.testo(R.string.studio_richiesta_cambiata)
    EventoStudio.NienteDaDecidere -> parole.testo(R.string.studio_niente_da_decidere)
    EventoStudio.Chiuso -> parole.testo(R.string.studio_chiudi_fatto)
    EventoStudio.GiaChiuso -> parole.testo(R.string.studio_errore_gia_chiuso)
    is EventoStudio.Rifiuto -> when (evento.codice) {
        null -> parole.testo(R.string.studio_errore_rete)
        CodiciErrore.GIA_CHIUSO -> parole.testo(R.string.studio_errore_gia_chiuso)
        CodiciErrore.NIENTE_DA_DECIDERE -> parole.testo(R.string.studio_niente_da_decidere)
        CodiciErrore.RICHIESTA_CAMBIATA -> parole.testo(R.string.studio_richiesta_cambiata)
        CodiciErrore.NON_TROVATO -> parole.testo(R.string.studio_errore_non_trovato)
        CodiciErrore.SERVER_DA_AGGIORNARE -> parole.testo(R.string.studio_server_vecchio)
        CodiciErrore.COLLEGAMENTO_NON_VALIDO -> parole.testo(R.string.collegamento_non_valido)
        CodiciErrore.CONFIGURAZIONE_MANCANTE -> parole.testo(R.string.faccende_errore_config_mancante)
        PostinoClient.PARAMETRI_NON_VALIDI ->
            parole.testo(if (evento.gesto == GestoStudio.CHIUDI) R.string.studio_errore_parametri else R.string.studio_errore_rifiutato)
        else -> parole.testo(R.string.studio_errore_rifiutato)
    }
}

/** Un dispositivo su cui lo Studio non c'è, in una frase. */
fun testoDispositivoSenzaStudio(parole: Parole, senza: DispositivoSenzaBlocco): String {
    val nome = nomeDelDispositivo(parole, senza.dispositivo.nome, senza.dispositivo.tipo)
    return senza.versione?.let { parole.testo(R.string.studio_dispositivo_vecchio, nome, it) }
        ?: parole.testo(R.string.studio_dispositivo_sconosciuto, nome)
}

/** "I minuti sono quelli col timer acceso dichiarati da Luca: …". */
fun testoMinutiDichiarati(parole: Parole, nomeFiglio: String?): String =
    nomeDaScrivere(nomeFiglio)?.let { parole.testo(R.string.studio_minuti_spiega, it) }
        ?: parole.testo(R.string.studio_minuti_spiega_senza_nome)
