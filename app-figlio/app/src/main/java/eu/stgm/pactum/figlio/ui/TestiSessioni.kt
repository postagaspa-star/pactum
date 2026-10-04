package eu.stgm.pactum.figlio.ui

import android.content.Context
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.catalogo.CatalogoApp
import eu.stgm.pactum.figlio.dati.Patto
import eu.stgm.pactum.figlio.sessione.EsitoAvvio
import eu.stgm.pactum.figlio.sessione.EsitoSessione
import eu.stgm.pactum.figlio.sessione.ParoleRispostaSessione
import eu.stgm.pactum.figlio.sessione.TestoSessioni
import eu.stgm.pactum.figlio.sessione.nomeSessioneTraVirgolette
import kotlinx.serialization.json.JsonObject
import java.time.ZoneId

// (0.11) Le parole delle Sessioni dentro l'app: strings.xml più la logica pura
// di TestoSessioni. Mai il nome tecnico di un pacchetto davanti al ragazzo.

/** I nomi leggibili delle app di una sessione: "ClasseViva", "App installate da APK". */
fun nomiAppSessione(context: Context, app: List<String>, nomi: Map<String, String>): List<String> =
    TestoSessioni.nomiApp(
        app = app,
        nomi = nomi,
        risolvi = { CatalogoApp.etichettaValore(context, it) },
        gruppoApk = context.getString(R.string.gruppo_apk_nome),
        sconosciuta = context.getString(R.string.chiave_app_sconosciuta),
    )

/** Le app di una sessione in una riga: "ClasseViva, Calcolatrice e altre 3". */
fun elencoAppSessione(context: Context, app: List<String>, nomi: Map<String, String>, massimo: Int = 4): String =
    TestoSessioni.elenco(
        nomi = nomiAppSessione(context, app, nomi),
        massimo = massimo,
        altri = { n -> context.resources.getQuantityString(R.plurals.sessione_altre_app, n, n) },
    )

/** "fino alle 17:00" o "fino a domani alle 9:00", per [formato] e [formatoDomani] con nome e ora. */
fun testoFinoAlle(context: Context, fine: Long, adesso: Long, formato: Int, formatoDomani: Int, vararg prima: Any): String {
    val quando = TestoSessioni.quandoFinisce(fine, adesso, ZoneId.systemDefault())
    return context.getString(if (quando.domani) formatoDomani else formato, *prima, quando.ora)
}

/**
 * Le frasi della risposta a una sessione. (0.15) [genitore] = chi ha deciso,
 * se il payload lo dice: "Mamma ha approvato la sessione %1$s". I modelli
 * restano col solo segnaposto della sessione.
 */
fun paroleRispostaSessione(context: Context, genitore: String? = null) = ParoleRispostaSessione(
    approvata = modelloSessione(context, genitore, R.string.notifica_sessione_approvata, R.string.notifica_sessione_approvata_nome),
    rifiutata = modelloSessione(context, genitore, R.string.notifica_sessione_rifiutata, R.string.notifica_sessione_rifiutata_nome),
    cambioApprovato = modelloSessione(context, genitore, R.string.notifica_sessione_cambio_approvato, R.string.notifica_sessione_cambio_approvato_nome),
    cambioRifiutato = modelloSessione(context, genitore, R.string.notifica_sessione_cambio_rifiutato, R.string.notifica_sessione_cambio_rifiutato_nome),
    approvataTesto = context.getString(R.string.notifica_sessione_approvata_testo),
    rifiutataTesto = context.getString(R.string.notifica_sessione_rifiutata_testo),
    cambioApprovatoTesto = context.getString(R.string.notifica_sessione_cambio_approvato_testo),
    cambioRifiutatoTesto = context.getString(R.string.notifica_sessione_cambio_rifiutato_testo),
    genitoreDice = modelloGenitoreDice(context, genitore),
)

/** Il modello col nome del genitore già dentro ("Mamma ha approvato la sessione %1$s"), o quello di sempre. */
private fun modelloSessione(context: Context, genitore: String?, senza: Int, conNome: Int): String =
    genitore?.let { context.getString(conNome, it.replace("%", "%%"), "%1\$s") } ?: context.getString(senza)

/**
 * (0.11) Titolo e testo della notifica `sessione_risposta`: il nome della
 * sessione dal payload (o dalla copia del patto appena letta) e, se il
 * genitore non ha approvato, il suo perché. Null se il payload non dice com'è
 * andata: allora si mostra il messaggio del server, come per ogni tipo nuovo.
 */
fun avvisoRispostaSessione(context: Context, payload: JsonObject, messaggio: String, patto: Patto?): Pair<String, String>? {
    val risposta = TestoSessioni.risposta(payload) ?: return null
    val sessione = risposta.sessioneId?.let { id -> patto?.sessioni?.firstOrNull { it.id == id } }
    val nome = risposta.nome ?: sessione?.nome?.takeIf { it.isNotBlank() }
    // (0.12) Il nome con l'emoji del suo tema, fra «»: "📚 «Studio»".
    val scritto = nome?.let { nomeSessioneTraVirgolette(context, it) }
    // (0.15) Chi ha deciso, dal payload (contratto v3.6), o dalla sessione del patto.
    val genitore = eu.stgm.pactum.figlio.faccende.LetturaFaccende.nomeGenitore(payload["genitore"]) ?: sessione?.decisaDa
    return TestoSessioni.avvisoRisposta(risposta, scritto, sessione?.motivazione, messaggio, paroleRispostaSessione(context, genitore))
}

/** Cosa dire dopo aver mandato, cambiato o eliminato una sessione. [cambio] = era già approvata. */
fun testoEsitoSessione(context: Context, esito: EsitoSessione, cambio: Boolean = false): String = when (esito) {
    is EsitoSessione.Fatta -> context.getString(if (cambio) R.string.sessione_cambio_mandato else R.string.sessione_mandata)
    EsitoSessione.NomeGiaUsato -> context.getString(R.string.sessione_esito_nome_doppio)
    EsitoSessione.TroppeSessioni -> context.getString(R.string.sessione_esito_troppe)
    EsitoSessione.InCorso -> context.getString(R.string.sessione_esito_in_corso)
    EsitoSessione.NonTrovata -> context.getString(R.string.sessione_esito_non_trovata)
    EsitoSessione.ValoriNonValidi -> context.getString(R.string.sessione_esito_valori)
    EsitoSessione.ServerDaAggiornare -> context.getString(R.string.sessioni_server_da_aggiornare)
    EsitoSessione.Scollegato -> context.getString(R.string.scollegato)
    EsitoSessione.SenzaRete -> context.getString(R.string.sessione_esito_senza_rete)
    EsitoSessione.Errore -> context.getString(R.string.sessione_esito_errore)
}

/**
 * Cosa dire dopo "Inizia". Senza rete la sessione non è iniziata, e lo si
 * dice; con la risposta persa non si sa, e si dice anche quello. Già in
 * corso: con la sua fine vera.
 */
fun testoEsitoAvvio(context: Context, esito: EsitoAvvio, adesso: Long = System.currentTimeMillis()): String = when (esito) {
    is EsitoAvvio.Avviata -> esito.svolta?.let {
        testoFinoAlle(context, it.fine, adesso, R.string.sessione_iniziata, R.string.sessione_iniziata_domani)
    } ?: context.getString(R.string.sessione_iniziata_semplice)
    EsitoAvvio.NonApprovata -> context.getString(R.string.sessione_esito_non_approvata)
    EsitoAvvio.BloccoFaccende -> context.getString(R.string.sessione_esito_blocco_faccende)
    is EsitoAvvio.GiaInCorso -> esito.svolta?.let {
        testoFinoAlle(
            context,
            it.fine,
            adesso,
            R.string.sessione_esito_gia_in_corso_fino,
            R.string.sessione_esito_gia_in_corso_fino_domani,
            nomeSessioneTraVirgolette(context, it.nome),
        )
    } ?: context.getString(R.string.sessione_esito_gia_in_corso)
    EsitoAvvio.Incerto -> context.getString(R.string.sessione_esito_incerto)
    EsitoAvvio.NonTrovata -> context.getString(R.string.sessione_esito_non_trovata)
    EsitoAvvio.DurataNonValida -> context.getString(R.string.sessione_durata_non_valida)
    EsitoAvvio.ServerDaAggiornare -> context.getString(R.string.sessioni_server_da_aggiornare)
    EsitoAvvio.Scollegato -> context.getString(R.string.scollegato)
    EsitoAvvio.SenzaRete -> context.getString(R.string.sessione_esito_avvio_senza_rete)
    EsitoAvvio.Errore -> context.getString(R.string.sessione_esito_avvio_errore)
}
