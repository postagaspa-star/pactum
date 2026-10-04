package eu.stgm.pactum.figlio.notifiche

import android.content.Context
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.dati.ContestoDispositivi
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.dati.Notifica
import eu.stgm.pactum.figlio.dati.Patto
import eu.stgm.pactum.figlio.dati.PattoLocale
import eu.stgm.pactum.figlio.dati.TipiNotifica
import eu.stgm.pactum.figlio.faccende.LetturaFaccende
import eu.stgm.pactum.figlio.faccende.ArchivioBlocco
import eu.stgm.pactum.figlio.faccende.ControlloBlocco
import eu.stgm.pactum.figlio.faccende.ParoleFaccende
import eu.stgm.pactum.figlio.faccende.TestoFaccende
import eu.stgm.pactum.figlio.faccende.TipiNotificaFaccende
import eu.stgm.pactum.figlio.rete.PostinoClient
import eu.stgm.pactum.figlio.ui.NovitaProposta
import eu.stgm.pactum.figlio.ui.TestoProposta
import eu.stgm.pactum.figlio.ui.avvisoNovitaProposta
import eu.stgm.pactum.figlio.ui.avvisoRispostaSessione
import eu.stgm.pactum.figlio.ui.raccontoProposta
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.time.ZoneId

/**
 * Le notifiche del server per il figlio diventano avvisi sul telefono (prima
 * stava dentro BattitoWorker). (0.13) Le chiama anche il giro delle faccende,
 * appena il blocco dice che qualcosa è cambiato: "Mamma ti ha dato 3
 * faccende" non aspetta il giro del quarto d'ora. Un mutex: due giri insieme
 * non avvisano due volte la stessa cosa.
 */
object NovitaDalPatto {

    private val mutex = Mutex()

    /**
     * Alza una notifica locale per ogni notifica del server mai avvisata prima
     * (il GET col token del figlio restituisce solo le sue: nuove proposte,
     * verdetti, il segno del genitore; dalla 0.10 anche le sue risposte alle
     * proposte del figlio e i ritiri delle sue; dalla 0.13 le faccende) e poi
     * le marca lette sul server. Senza permesso non si avvisa E non si segna
     * né marca: appena il permesso arriva, il giro successivo recupera
     * (marcare prima di avvisare perderebbe l'avviso).
     */
    suspend fun avvisa(context: Context, impostazioni: Impostazioni, postino: PostinoClient) = mutex.withLock {
        avvisaDentro(context, impostazioni, postino)
    }

    private suspend fun avvisaDentro(context: Context, impostazioni: Impostazioni, postino: PostinoClient) {
        val notifiche = postino.leggiNotifiche() ?: return
        if (notifiche.isEmpty()) return
        // (0.13) Una faccenda nuova, bocciata o annullata: il blocco si richiede
        // subito, anche se gli avvisi sono spenti.
        if (notifiche.any { it.tipo in TipiNotificaFaccende.CAMBIANO_IL_BLOCCO }) ControlloBlocco.richiedi()
        if (!AvvisiLocali.puoAvvisare(context)) return

        val giaAvvisate = impostazioni.leggiIdAvvisati()
        val nuove = notifiche.filter { it.id !in giaAvvisate }
        // (0.10) Il genitore ha risposto a una proposta del figlio, o ha ritirato
        // la sua: si dice con parole del figlio (TestoProposta.novita).
        val novita = nuove
            .mapNotNull { notifica -> TestoProposta.novita(notifica.tipo, notifica.payload)?.let { notifica.id to it } }
            .toMap()
        // La copia del patto appena sincronizzata: serve a dire su quale regola
        // verte una nuova proposta (o una risposta, o un ritiro). (0.11) E il
        // nome e il perché del genitore di una sessione decisa.
        val patto = if (novita.isNotEmpty() ||
            nuove.any { it.tipo == TipiNotifica.NUOVA_PROPOSTA || it.tipo == TipiNotifica.SESSIONE_RISPOSTA }
        ) {
            PattoLocale(context).leggi()
        } else {
            null
        }
        // (0.10) Per risposte e ritiri: la proposta chiusa, col confronto e il
        // perché del genitore; la regola, se non è in questo patto (di un altro
        // dispositivo), da GET /api/regole. Solo quando servono.
        val proposte = if (novita.isNotEmpty()) postino.leggiProposte().orEmpty() else emptyList()
        val diQuestoPatto = patto?.regole.orEmpty()
        val regole = if (novita.values.any { n -> diQuestoPatto.none { it.id == n.regolaId } }) {
            (diQuestoPatto + postino.leggiRegole().orEmpty()).distinctBy { it.id }
        } else {
            diQuestoPatto
        }
        val parole = paroleFaccende(context)
        nuove.forEach { notifica ->
            val n = novita[notifica.id]
            val (titolo, testo) = n
                ?.let {
                    avvisoNovitaProposta(
                        context,
                        novita = it,
                        proposta = proposte.firstOrNull { p -> p.id == it.propostaId },
                        regola = regole.firstOrNull { r -> r.id == it.regolaId },
                        contesto = patto?.contestoDispositivi() ?: ContestoDispositivi(),
                        messaggio = notifica.messaggio,
                        // (0.15) Chi ha risposto o ritirato, col suo nome (contratto v3.6).
                        genitore = LetturaFaccende.nomeGenitore(notifica.payload["genitore"]),
                    )
                }
                // (0.11) Il genitore ha deciso su una sessione o sul suo cambio.
                ?: notifica.takeIf { it.tipo == TipiNotifica.SESSIONE_RISPOSTA }
                    ?.let { avvisoRispostaSessione(context, it.payload, it.messaggio, patto) }
                // (0.13) Le faccende, col nome del genitore.
                ?: avvisoFaccende(context, notifica, parole)
                ?: (titoloConGenitore(context, notifica) to testoNotifica(context, notifica, patto))
            // (0.10) La proposta ritirata dal genitore non resta annunciata in
            // tendina come "Nuova proposta del genitore": quella si toglie.
            if (n is NovitaProposta.Ritiro) {
                n.propostaId?.let { AvvisiLocali.cancella(context, AvvisiLocali.idProposta(it)) }
            }
            // (0.10) La nuova proposta ha l'id della proposta (per poterla togliere
            // se viene ritirata); le altre quello della notifica del server.
            val propostaAnnunciata = if (notifica.tipo == TipiNotifica.NUOVA_PROPOSTA) {
                (notifica.payload["proposta_id"] as? JsonPrimitive)?.longOrNull
            } else {
                null
            }
            AvvisiLocali.avvisa(
                context,
                // Id con offset: l'id grezzo del server collide con la notifica
                // fissa del testimone (FGS id 1), che verrebbe sostituita.
                id = propostaAnnunciata?.let { AvvisiLocali.idProposta(it) }
                    ?: AvvisiLocali.idNotificaServer(notifica.id),
                titolo = titolo,
                testo = testo,
                destinazione = AvvisiLocali.destinazioneTipo(notifica.tipo),
                canale = AvvisiLocali.canaleTipo(notifica.tipo),
            )
        }
        if (nuove.isNotEmpty()) impostazioni.registraIdAvvisati(nuove.map { it.id })

        // Marcate lette sul server (best effort, idempotente): senza, il server
        // accumula le non lette all'infinito e, oltre il tetto locale di 500 id,
        // il figlio si ri-avviserebbe le vecchie. Il giro dopo riprova le fallite.
        notifiche.forEach { postino.marcaNotificaLetta(it.id) }
    }

    /**
     * (0.15) Il titolo della notifica col nome del genitore, dove il payload
     * lo dice (contratto v3.6): "Nuova proposta di Mamma", "Un segno da Papà".
     * Senza nome, quello di sempre.
     */
    private fun titoloConGenitore(context: Context, notifica: Notifica): String {
        val genitore = LetturaFaccende.nomeGenitore(notifica.payload["genitore"])
        return when {
            genitore != null && notifica.tipo == TipiNotifica.NUOVA_PROPOSTA ->
                context.getString(R.string.tipo_nuova_proposta_nome, genitore)
            genitore != null && notifica.tipo == TipiNotifica.SEGNO ->
                context.getString(R.string.tipo_segno_nome, genitore)
            else -> AvvisiLocali.titoloTipo(context, notifica.tipo)
        }
    }

    /** (0.13) Le notifiche delle faccende con le parole del figlio; null per gli altri tipi o un payload che non basta. */
    private fun avvisoFaccende(context: Context, notifica: Notifica, parole: ParoleFaccende): Pair<String, String>? =
        when (notifica.tipo) {
            TipiNotificaFaccende.NUOVE_FACCENDE -> {
                val ids = TestoFaccende.idNuove(notifica.payload).toSet()
                val note = ArchivioBlocco.leggi(context).daFare.filter { it.id in ids }.map { it.titolo }
                TestoFaccende.avvisoNuove(notifica.payload, note, System.currentTimeMillis(), ZoneId.systemDefault(), parole)
            }
            TipiNotificaFaccende.FACCENDA_BOCCIATA -> TestoFaccende.avvisoBocciata(notifica.payload, parole)
            TipiNotificaFaccende.FACCENDA_ANNULLATA -> TestoFaccende.avvisoAnnullata(notifica.payload, parole)
            else -> null
        }

    fun paroleFaccende(context: Context) = ParoleFaccende(
        nuove = { nome, quante -> context.resources.getQuantityString(R.plurals.notifica_faccende_nuove, quante, nome, quante) },
        bloccoSubito = context.getString(R.string.notifica_faccende_blocco_subito),
        bloccoAlle = context.getString(R.string.notifica_faccende_blocco_alle),
        bloccoDomani = context.getString(R.string.notifica_faccende_blocco_domani),
        bloccoGiorno = context.getString(R.string.notifica_faccende_blocco_giorno),
        nuoveTesto = context.getString(R.string.notifica_faccende_nuove_testo),
        bocciata = context.getString(R.string.notifica_faccenda_bocciata),
        bocciataTesto = context.getString(R.string.notifica_faccenda_bocciata_testo),
        annullata = context.getString(R.string.notifica_faccenda_annullata),
        annullataTesto = context.getString(R.string.notifica_faccenda_annullata_testo),
        genitoreSenzaNome = context.getString(R.string.faccende_genitore_senza_nome),
    )

    /**
     * Il testo della notifica: quello del server, tranne per la nuova proposta.
     * Lì il server dice "Nuova proposta del genitore: −15 min al giorno
     * rispetto ad ora" (il titolo lo ripete già) e non dice su QUALE regola:
     * si racconta come nella scheda Proposte, una riga per pezzo ("Ora:
     * TikTok: al massimo 1 h al giorno", "Se accetti: …"). Regola non trovata
     * = il testo del server.
     */
    private fun testoNotifica(context: Context, notifica: Notifica, patto: Patto?): String {
        if (notifica.tipo != TipiNotifica.NUOVA_PROPOSTA || patto == null) return notifica.messaggio
        val payload = notifica.payload
        val regolaId = (payload["regola_id"] as? JsonPrimitive)?.longOrNull ?: return notifica.messaggio
        val propostaId = (payload["proposta_id"] as? JsonPrimitive)?.longOrNull
        // La pendente del patto ha i parametri proposti e il confronto ricalcolato
        // sulla regola di adesso; il payload ha solo il confronto di allora.
        val proposta = patto.propostePendenti.firstOrNull { it.id == propostaId }
        val oggetto = TestoProposta.oggetto(
            regolaId,
            proposta?.direzione ?: (payload["direzione"] as? JsonPrimitive)?.contentOrNull,
            proposta?.parametriProposti,
            patto.regole,
        ) ?: return notifica.messaggio
        // (v3) Al telefono arrivano le proposte sulle sue regole e sulla vita
        // reale (il server filtra per dispositivo); se una fosse di un altro
        // dispositivo, la frase lo direbbe come nella scheda Proposte.
        return raccontoProposta(
            context = context,
            confronto = proposta?.confronto ?: (payload["confronto"] as? JsonPrimitive)?.contentOrNull,
            oggetto = oggetto,
            contesto = patto.contestoDispositivi(),
        ).testo
    }
}
