package eu.stgm.pactum.figlio.ui

import eu.stgm.pactum.figlio.dati.AutoriProposta
import eu.stgm.pactum.figlio.dati.DirezioniProposta
import eu.stgm.pactum.figlio.dati.EsitiRisposta
import eu.stgm.pactum.figlio.dati.EsitoProposta
import eu.stgm.pactum.figlio.dati.EsitoRitiro
import eu.stgm.pactum.figlio.dati.Proposta
import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.StatiProposta
import eu.stgm.pactum.figlio.dati.TipiNotifica
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

// Logica pura della proposta del genitore: su QUALE regola verte e come
// raccontarlo. Il confronto del server dice di quanto cambia ("−15 min al
// giorno rispetto ad ora"), non su cosa: il ragazzo deve sapere cosa accetta.
// Niente Android: le parole arrivano da strings.xml attraverso ParoleProposta,
// la descrizione di una regola dalla stessa funzione che la mostra ovunque.
// (0.10) Anche le proposte del figlio (contratto v3.4): com'è andato l'invio,
// com'è finita una proposta chiusa, le notifiche delle risposte del genitore.

/** Su cosa verte una proposta, risolta contro le regole del patto. */
sealed interface OggettoProposta {
    val regola: Regola

    /** Una modifica: la regola com'è ora e, se si sa, come diventa accettando. */
    data class Modifica(override val regola: Regola, val parametriDopo: JsonObject?) : OggettoProposta

    /** L'eliminazione: la regola che uscirebbe dal patto. */
    data class Eliminazione(override val regola: Regola) : OggettoProposta
}

/**
 * Le frasi, da strings.xml. (0.10) Per una proposta del figlio le stesse
 * frasi dette dalla sua parte: "Se il genitore accetta: …", "Eliminare la
 * regola: …" (paroleTuaProposta).
 */
data class ParoleProposta(
    val senzaConfronto: String,
    /** "Ora: %1$s" */
    val ora: String,
    /** "Se accetti: %1$s" */
    val seAccetti: String,
    /** "Propone di eliminare la regola: %1$s" */
    val togliere: String,
    /** (v3) Regola di un altro dispositivo: "Ora %1$s: %2$s" → "Ora sul computer: …" */
    val oraSu: String = "Ora %1\$s: %2\$s",
    /** (v3) "Propone di eliminare la regola %1$s: %2$s" → "… la regola sul computer: …" */
    val togliereSu: String = "Propone di eliminare la regola %1\$s: %2\$s",
)

/** La proposta raccontata: la frase in evidenza e le righe sotto. */
data class RaccontoProposta(val titolo: String, val righe: List<String>) {
    /** Tutto in un testo solo, una riga per pezzo (la notifica). */
    val testo: String get() = (listOf(titolo) + righe).joinToString("\n")
}

/** (0.10) Le frasi dopo l'invio di una proposta del figlio, da strings.xml. */
data class ParoleEsitoProposta(
    /** "Proposta mandata: %1$s. Se il genitore accetta, vale subito." */
    val mandata: String,
    /** "Proposta mandata: eliminare la regola. Se il genitore accetta, vale subito." */
    val mandataEliminazione: String,
    /** "Proposta mandata. Se il genitore accetta, vale subito." */
    val mandataSenzaConfronto: String,
    /** "C'è già una proposta in attesa su questa regola." */
    val giaPendente: String,
    /** "Hai già una proposta in attesa su questa regola." */
    val giaTua: String,
    val regolaNonValida: String,
    val dispositivoRevocato: String,
    val valoriNonValidi: String,
    /** "Per mandare proposte serve aggiornare il server di Pactum." */
    val serverDaAggiornare: String,
    val scollegato: String,
    val senzaRete: String,
    val errore: String,
)

/** (0.10) Le frasi dopo il ritiro di una proposta del figlio, da strings.xml. */
data class ParoleRitiro(
    val ritirata: String,
    val nonPiuPendente: String,
    /** "Non trovo più questa proposta." */
    val nonTrovata: String,
    /** "Per ritirare una proposta serve aggiornare il server di Pactum." */
    val serverDaAggiornare: String,
    val scollegato: String,
    val errore: String,
)

/** (0.10) Com'è finita una proposta chiusa, detto dalla parte del figlio. */
enum class FineProposta {
    HAI_ACCETTATO,
    HAI_RIFIUTATO,
    GENITORE_HA_ACCETTATO,
    GENITORE_HA_RIFIUTATO,
    HAI_RITIRATO,
    GENITORE_HA_RITIRATO,

    /** La regola è stata eliminata direttamente mentre la proposta aspettava. */
    ANNULLATA,

    /** Uno stato che questa versione non conosce: si mostra com'è. */
    ALTRO,
}

/** (0.10) Una riga sotto il confronto di una proposta chiusa; [tenue] = il perché di qualcuno. */
data class RigaStoria(val testo: String, val tenue: Boolean)

/** (0.10) Le frasi della storia delle proposte, da strings.xml. */
data class ParoleStoria(
    val haiAccettato: String,
    val haiRifiutato: String,
    val genitoreHaAccettato: String,
    val genitoreHaRifiutato: String,
    val haiRitirato: String,
    val genitoreHaRitirato: String,
    val annullata: String,
    /** "Il genitore dice: %1$s" */
    val genitoreDice: String,
    /** "Hai detto: %1$s" (il perché della tua risposta) */
    val haiDetto: String,
    /** "Il tuo perché: %1$s" (il perché della tua proposta) */
    val tuoPerche: String,
)

/**
 * (0.10, v3.4) Una notifica del server su una proposta, che al figlio va detta
 * con parole sue: il genitore ha risposto a una SUA proposta, oppure ha
 * ritirato una delle proprie.
 */
sealed interface NovitaProposta {
    val propostaId: Long?
    val regolaId: Long?

    data class Risposta(
        override val propostaId: Long?,
        override val regolaId: Long?,
        val accettata: Boolean,
    ) : NovitaProposta

    data class Ritiro(override val propostaId: Long?, override val regolaId: Long?) : NovitaProposta
}

/** (0.10) Le frasi delle notifiche sulle proposte del figlio, da strings.xml. */
data class ParoleNovita(
    /** "Il genitore ha accettato la tua proposta" */
    val accettataTitolo: String,
    /** "Il genitore ha rifiutato la tua proposta" */
    val rifiutataTitolo: String,
    /** "Il genitore ha ritirato la sua proposta" */
    val ritirataTitolo: String,
    /** "Ora: %1$s" */
    val ora: String,
    /** "La regola resta: %1$s" */
    val resta: String,
    /** "La regola è stata eliminata." */
    val tolta: String,
    /** "Regola: %1$s" */
    val regola: String,
    /** "Il genitore dice: %1$s" */
    val genitoreDice: String,
    /** "Era la proposta di eliminare la regola: %1$s" */
    val ritirataEliminazione: String = "Era la proposta di eliminare la regola: %1\$s",
    /** "Era la proposta di eliminare una regola." */
    val ritirataEliminazioneSenzaRegola: String = "Era la proposta di eliminare una regola.",
)

object TestoProposta {

    /**
     * La regola su cui verte la proposta, cercata per [regolaId] tra le regole
     * del patto; null se non c'è (eliminata nel frattempo, copia locale
     * indietro). Un'eliminazione arriva col marcatore `{"azione": "elimina"}`
     * nei parametri proposti e con direzione `elimina` (contratto-api.md).
     */
    fun oggetto(
        regolaId: Long,
        direzione: String?,
        parametriProposti: JsonObject?,
        regole: List<Regola>,
    ): OggettoProposta? {
        val regola = regole.firstOrNull { it.id == regolaId } ?: return null
        if (eliminazione(direzione, parametriProposti)) return OggettoProposta.Eliminazione(regola)
        // "Se accetti" solo con parametri veri e diversi da quelli di adesso.
        val dopo = parametriProposti?.takeIf { it.isNotEmpty() && it != regola.parametri }
        return OggettoProposta.Modifica(regola, dopo)
    }

    fun eliminazione(direzione: String?, parametriProposti: JsonObject?): Boolean =
        direzione == DirezioniProposta.ELIMINA ||
            (parametriProposti?.get("azione") as? JsonPrimitive)?.content == "elimina"

    /**
     * In evidenza resta il confronto del server; sotto, la regola com'è ora e
     * come diventa. Per l'eliminazione il confronto del server è una frase
     * fissa ("propone di eliminare la regola") che non dice quale: al suo posto
     * va la stessa frase CON la regola, così non si ripete due volte.
     *
     * [descrivi] è la descrizione delle regole che l'app usa ovunque, per la
     * regola e i parametri dati. (v3) [dispositivoDi] dice su quale ALTRO
     * dispositivo sta la regola ("sul computer"), null se è di questo telefono
     * o del figlio: allora la frase lo dice ("Ora sul computer: …"). Il
     * dispositivo si dice una volta, sulla regola di adesso: "Se accetti" parla
     * della stessa regola.
     *
     * (0.10) [descriviProposta] descrive i parametri proposti; null quando il
     * bersaglio nuovo non ha un nome che una persona legge (un'app che questo
     * telefono non ha mai visto): allora la riga "Se accetti" non c'è, e il
     * confronto del server, che dalla v3.4 scrive i nomi, basta da solo.
     */
    fun racconto(
        confronto: String?,
        oggetto: OggettoProposta?,
        parole: ParoleProposta,
        descrivi: (regola: Regola, parametri: JsonObject) -> String,
        dispositivoDi: (Regola) -> String? = { null },
        descriviProposta: (regola: Regola, parametri: JsonObject) -> String? = descrivi,
    ): RaccontoProposta {
        val titolo = confronto?.ifBlank { null } ?: parole.senzaConfronto
        if (oggetto == null) return RaccontoProposta(titolo, emptyList())
        val regola = oggetto.regola
        val su = dispositivoDi(regola)
        val adesso = descrivi(regola, regola.parametri)
        return when (oggetto) {
            is OggettoProposta.Eliminazione -> RaccontoProposta(
                if (su == null) parole.togliere.format(adesso) else parole.togliereSu.format(su, adesso),
                emptyList(),
            )
            is OggettoProposta.Modifica -> RaccontoProposta(
                titolo,
                listOfNotNull(
                    if (su == null) parole.ora.format(adesso) else parole.oraSu.format(su, adesso),
                    oggetto.parametriDopo?.let { dopo -> descriviProposta(regola, dopo)?.let { parole.seAccetti.format(it) } },
                ),
            )
        }
    }

    /**
     * (0.10, contratto v3.4 "I nomi nel confronto") Il nome di un bersaglio che
     * una persona legge: l'[etichetta] trovata (app, categoria, "Tutto il
     * telefono", programma, sito) se non è la [chiave] tecnica stessa. null =
     * questo telefono non sa come si chiama: mai mostrare un nome di pacchetto.
     */
    fun nomeLeggibile(chiave: String, etichetta: String?): String? =
        etichetta?.trim()?.takeIf { it.isNotEmpty() && it != chiave.trim() }

    // --- (0.10) Le proposte del figlio (contratto v3.4) ------------------------

    /**
     * Cosa dire dopo aver mandato una proposta. Arrivata: il confronto del
     * server ("+30 min al giorno rispetto ad ora") e che, se il genitore
     * accetta, vale subito. Per l'eliminazione il confronto del server è una
     * frase fissa scritta per il genitore ("propone di eliminare la regola"):
     * al figlio si dice "eliminare la regola". Quando la causa di un rifiuto si
     * sa, si dice quella: un server vecchio va aggiornato, non è "un errore".
     */
    fun esito(esito: EsitoProposta, parole: ParoleEsitoProposta): String = when (esito) {
        is EsitoProposta.Mandata -> {
            // Il punto lo mette la frase: "…rispetto ad ora. Se il genitore…".
            val confronto = esito.confronto?.trim()?.trimEnd('.')?.trim()?.ifEmpty { null }
            when {
                esito.eliminazione -> parole.mandataEliminazione
                confronto == null -> parole.mandataSenzaConfronto
                else -> parole.mandata.format(confronto)
            }
        }
        EsitoProposta.GiaPendente -> parole.giaPendente
        EsitoProposta.GiaTua -> parole.giaTua
        EsitoProposta.RegolaNonValida -> parole.regolaNonValida
        EsitoProposta.DispositivoRevocato -> parole.dispositivoRevocato
        EsitoProposta.ValoriNonValidi -> parole.valoriNonValidi
        EsitoProposta.ServerDaAggiornare -> parole.serverDaAggiornare
        EsitoProposta.Scollegato -> parole.scollegato
        EsitoProposta.SenzaRete -> parole.senzaRete
        EsitoProposta.Errore -> parole.errore
    }

    /** Cosa dire dopo il ritiro di una proposta del figlio. */
    fun ritiro(esito: EsitoRitiro, parole: ParoleRitiro): String = when (esito) {
        EsitoRitiro.Ritirata -> parole.ritirata
        EsitoRitiro.NonPiuPendente -> parole.nonPiuPendente
        EsitoRitiro.NonTrovata -> parole.nonTrovata
        EsitoRitiro.ServerDaAggiornare -> parole.serverDaAggiornare
        EsitoRitiro.Scollegato -> parole.scollegato
        EsitoRitiro.Errore -> parole.errore
    }

    /** Com'è finita una proposta chiusa, dalla parte del figlio: chi ha deciso cosa. */
    fun fine(proposta: Proposta): FineProposta = when (proposta.stato) {
        StatiProposta.ACCETTATA ->
            if (proposta.delFiglio) FineProposta.GENITORE_HA_ACCETTATO else FineProposta.HAI_ACCETTATO
        StatiProposta.RIFIUTATA ->
            if (proposta.delFiglio) FineProposta.GENITORE_HA_RIFIUTATO else FineProposta.HAI_RIFIUTATO
        StatiProposta.RITIRATA ->
            if (proposta.delFiglio) FineProposta.HAI_RITIRATO else FineProposta.GENITORE_HA_RITIRATO
        StatiProposta.ANNULLATA -> FineProposta.ANNULLATA
        else -> FineProposta.ALTRO
    }

    /**
     * Le righe di una proposta chiusa, nell'ordine in cui le cose sono
     * successe: il perché di chi ha proposto, com'è finita, il perché di chi
     * ha risposto. Del genitore: "Il genitore dice: …", "Hai accettato", "Hai
     * detto: …". Del figlio: "Il tuo perché: …", "Il genitore ha rifiutato",
     * "Il genitore dice: …".
     */
    fun righeChiusa(proposta: Proposta, parole: ParoleStoria): List<RigaStoria> {
        val delFiglio = proposta.delFiglio
        val perchePropone = proposta.motivazione?.trim()?.ifEmpty { null }
            ?.let { RigaStoria((if (delFiglio) parole.tuoPerche else parole.genitoreDice).format(it), tenue = true) }
        val finita = when (fine(proposta)) {
            FineProposta.HAI_ACCETTATO -> parole.haiAccettato
            FineProposta.HAI_RIFIUTATO -> parole.haiRifiutato
            FineProposta.GENITORE_HA_ACCETTATO -> parole.genitoreHaAccettato
            FineProposta.GENITORE_HA_RIFIUTATO -> parole.genitoreHaRifiutato
            FineProposta.HAI_RITIRATO -> parole.haiRitirato
            FineProposta.GENITORE_HA_RITIRATO -> parole.genitoreHaRitirato
            FineProposta.ANNULLATA -> parole.annullata
            FineProposta.ALTRO -> proposta.stato
        }
        // Il perché della risposta lo scrive chi ha risposto: l'altro.
        val percheRisponde = proposta.risposta?.motivazione?.trim()?.ifEmpty { null }
            ?.let { RigaStoria((if (delFiglio) parole.genitoreDice else parole.haiDetto).format(it), tenue = true) }
        return listOfNotNull(perchePropone, RigaStoria(finita, tenue = false), percheRisponde)
    }

    /**
     * La notifica del server che riguarda una proposta del figlio, null per
     * tutte le altre (si dicono come prima). `proposta_risposta` vale solo con
     * `autore: "figlio"`: è il genitore che risponde a una proposta del figlio.
     * `proposta_ritirata` arriva al figlio quando il genitore ritira la sua.
     */
    fun novita(tipo: String, payload: JsonObject): NovitaProposta? {
        val autore = (payload["autore"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
        val propostaId = (payload["proposta_id"] as? JsonPrimitive)?.longOrNull
        val regolaId = (payload["regola_id"] as? JsonPrimitive)?.longOrNull
        return when (tipo) {
            TipiNotifica.PROPOSTA_RISPOSTA -> {
                if (autore != AutoriProposta.FIGLIO) return null
                when ((payload["esito"] as? JsonPrimitive)?.contentOrNull) {
                    EsitiRisposta.ACCETTA -> NovitaProposta.Risposta(propostaId, regolaId, accettata = true)
                    EsitiRisposta.RIFIUTA -> NovitaProposta.Risposta(propostaId, regolaId, accettata = false)
                    else -> null
                }
            }
            TipiNotifica.PROPOSTA_RITIRATA ->
                if (autore == AutoriProposta.FIGLIO) null else NovitaProposta.Ritiro(propostaId, regolaId)
            else -> null
        }
    }

    /**
     * Titolo e testo della notifica per [novita]. [proposta] = la proposta da
     * GET /api/proposte, col confronto e il perché del genitore (null se non
     * si legge); [regolaAdesso] = la regola com'è adesso, già detta in chiaro
     * (null se non c'è più o non si trova); [messaggio] = il testo del server,
     * il ripiego quando non si sa dire niente di più.
     */
    fun avviso(
        novita: NovitaProposta,
        proposta: Proposta?,
        regolaAdesso: String?,
        messaggio: String,
        parole: ParoleNovita,
    ): Pair<String, String> {
        val eliminazione = proposta != null && eliminazione(proposta.direzione, proposta.parametriProposti)
        // Il confronto di un'eliminazione è scritto per il genitore: al figlio non serve.
        val confronto = proposta?.confronto?.trim()?.ifEmpty { null }?.takeIf { !eliminazione }
        val perche = proposta?.risposta?.motivazione?.trim()?.ifEmpty { null }?.let { parole.genitoreDice.format(it) }
        val (titolo, righe) = when (novita) {
            is NovitaProposta.Risposta -> if (novita.accettata) {
                parole.accettataTitolo to listOfNotNull(
                    confronto,
                    if (eliminazione) parole.tolta else regolaAdesso?.let { parole.ora.format(it) },
                    perche,
                )
            } else {
                parole.rifiutataTitolo to listOfNotNull(
                    confronto,
                    regolaAdesso?.let { parole.resta.format(it) },
                    perche,
                )
            }
            // Ritirata una proposta di eliminare: lo si dice, sennò "Regola: …" da
            // sola sembrerebbe una modifica.
            is NovitaProposta.Ritiro -> parole.ritirataTitolo to if (eliminazione) {
                listOf(
                    regolaAdesso?.let { parole.ritirataEliminazione.format(it) }
                        ?: parole.ritirataEliminazioneSenzaRegola,
                )
            } else {
                listOfNotNull(confronto, regolaAdesso?.let { parole.regola.format(it) })
            }
        }
        return titolo to righe.joinToString("\n").ifEmpty { messaggio }
    }
}
