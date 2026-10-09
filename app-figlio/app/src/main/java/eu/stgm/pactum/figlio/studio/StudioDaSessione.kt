package eu.stgm.pactum.figlio.studio

import android.content.Context
import eu.stgm.pactum.design.TemaSessione
import eu.stgm.pactum.figlio.sessione.SessioneDefinita

/**
 * (0.22) Le app dello Studio dalla sessione «Studio» che il figlio ha già
 * (richiesta di Andrea, 09/10: «perché non fare che siano quelle già
 * selezionate nella Sessione Studio già presente nel mio account?»).
 *
 * Alla prima partenza lo Studio è approvato con la lista vuota (migrazione
 * v4.0): sul telefono restano solo le app sempre usabili. Se il figlio ha una
 * sessione di studio approvata, le sue app diventano da sole la proposta per
 * lo Studio: il telefono la manda una volta, e un genitore la approva (la
 * regola resta quella decisa da Andrea: le liste le propone il figlio e le
 * approva un genitore). Logica pura, provata in StudioDaSessioneTest.
 */
object StudioDaSessione {

    /**
     * La sessione da cui prendere le app: approvata, con almeno un'app, di
     * tema Studio (il nome: «Studio», «Compiti», «Matematica»…). Se sono più
     * d'una, prima quella che si chiama proprio «Studio», poi quella con più app.
     */
    fun sessione(sessioni: List<SessioneDefinita>): SessioneDefinita? =
        sessioni
            .filter { it.approvata && it.app.isNotEmpty() && TemaSessione.daNome(it.nome) == TemaSessione.STUDIO }
            .sortedWith(
                compareByDescending<SessioneDefinita> { TemaSessione.paroleDi(it.nome) == listOf("studio") }
                    .thenByDescending { it.app.size }
                    .thenBy { it.id },
            )
            .firstOrNull()

    /**
     * La proposta da mandare: solo se lo Studio è approvato con la lista del
     * telefono vuota e non c'è già una proposta in attesa. Il resto (giorni,
     * orari, minimo, programmi del computer) resta quello approvato.
     */
    fun proposta(config: ConfigStudio?, sessione: SessioneDefinita?): ContenutoStudio? {
        if (config == null || sessione == null) return null
        if (config.inAttesa != null) return null
        val base = config.approvata ?: return null
        if (base.app.isNotEmpty()) return null
        val app = sessione.app.distinct().take(RegoleStudio.APP_MASSIME)
        return base.copy(app = app, nomi = sessione.nomi.filterKeys { it in app })
    }

    /** La chiave del ricordo: una proposta automatica per sessione e versione, mai di più. */
    fun chiave(sessione: SessioneDefinita): String = "${sessione.id}@${sessione.versione ?: 0}"

    private const val FILE = "studio_da_sessione"
    private const val MANDATA = "mandata"

    /** true = per questa sessione (e versione) la proposta automatica è già partita. */
    fun giaMandata(context: Context, sessione: SessioneDefinita): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(MANDATA, null) == chiave(sessione)

    /** Da ricordare quando la proposta automatica è arrivata al server. */
    fun segnaMandata(context: Context, sessione: SessioneDefinita) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(MANDATA, chiave(sessione)).apply()
    }
}
