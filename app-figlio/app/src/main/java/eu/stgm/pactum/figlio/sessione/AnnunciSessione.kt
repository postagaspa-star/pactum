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
                attiva.nome,
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
            titolo = context.getString(R.string.notifica_sessione_non_partita, nome),
            testo = context.getString(R.string.notifica_sessione_non_partita_testo),
            destinazione = MainActivity.DEST_SESSIONI,
        )
    }

    /** Dopo un "Inizia" senza risposta: era partita, ed è già finita. */
    fun giaFinita(context: Context, nome: String) {
        AvvisiLocali.avvisa(
            context,
            id = AvvisiLocali.ID_SESSIONE,
            titolo = context.getString(R.string.notifica_sessione_gia_finita, nome),
            testo = context.getString(R.string.notifica_sessione_gia_finita_testo),
            destinazione = MainActivity.DEST_SESSIONI,
        )
    }
}
