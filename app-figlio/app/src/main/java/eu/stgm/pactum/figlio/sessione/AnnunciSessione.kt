package eu.stgm.pactum.figlio.sessione

import android.content.Context
import eu.stgm.pactum.figlio.MainActivity
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.notifiche.AvvisiLocali
import java.time.ZoneId

/**
 * (0.11) Quello che si dice al ragazzo di una Sessione che non ha visto
 * partire davanti a sé: una risposta persa per strada, una reinstallazione.
 * Mai una barriera per una sessione di cui non sa niente: prima la notifica
 * "La sessione «Studio» è partita (fino alle 17:00)", poi la barriera.
 */
object AnnunciSessione {

    /** La sessione [attiva] è partita: true se la notifica è arrivata (allora vale la barriera). */
    fun partita(context: Context, attiva: SessioneAttiva): Boolean {
        val quando = TestoSessioni.quandoFinisce(attiva.fine, System.currentTimeMillis(), ZoneId.systemDefault())
        return AvvisiLocali.avvisa(
            context,
            id = AvvisiLocali.ID_SESSIONE,
            titolo = context.getString(
                if (quando.domani) R.string.notifica_sessione_partita_domani else R.string.notifica_sessione_partita,
                nomeSessioneTraVirgolette(context, attiva.nome),
                quando.ora,
            ),
            testo = context.getString(R.string.notifica_sessione_partita_testo),
            destinazione = MainActivity.DEST_OGGI,
        )
    }

    /** Dopo un "Inizia" senza risposta: la sessione non è partita. */
    fun nonPartita(context: Context, nome: String) {
        AvvisiLocali.avvisa(
            context,
            id = AvvisiLocali.ID_SESSIONE,
            titolo = context.getString(R.string.notifica_sessione_non_partita, nomeSessioneTraVirgolette(context, nome)),
            testo = context.getString(R.string.notifica_sessione_non_partita_testo),
            destinazione = MainActivity.DEST_SESSIONI,
        )
    }

    /**
     * (0.12) "Sessione 📚 «Studio» finita · 45 minuti di Studio", quando la
     * pagina della fine non si apre sopra l'app in uso (niente "Mostra sopra
     * le altre app", passati i 10 minuti, Android che non l'ha aperta): la
     * pagina si vede alla prossima apertura di Pactum. Una volta sola (chi
     * chiama ha già segnato la pagina come avvisata), senza suonare due volte,
     * e se ne va da sola alle 2 ore dalla fine, quando la pagina non vale più.
     */
    fun finita(context: Context, svolta: SvoltaLocale, adesso: Long = System.currentTimeMillis()): Boolean =
        AvvisiLocali.avvisa(
            context,
            id = AvvisiLocali.ID_SESSIONE_FINITA,
            titolo = context.getString(R.string.pagina_fine_titolo, nomeSessioneTraVirgolette(context, svolta.nome)),
            testo = testoDurataSvolta(context, svolta.nome, svolta.inizio, svolta.fine),
            destinazione = MainActivity.DEST_OGGI,
            scadeTra = PagineSessione.validaAncora(svolta, adesso),
            soloUnaVolta = true,
        )

    /** Dopo un "Inizia" senza risposta: era partita, ed è già finita. */
    fun giaFinita(context: Context, nome: String) {
        AvvisiLocali.avvisa(
            context,
            id = AvvisiLocali.ID_SESSIONE,
            titolo = context.getString(R.string.notifica_sessione_gia_finita, nomeSessioneTraVirgolette(context, nome)),
            testo = context.getString(R.string.notifica_sessione_gia_finita_testo),
            destinazione = MainActivity.DEST_SESSIONI,
        )
    }
}
