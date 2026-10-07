package eu.stgm.pactum.figlio

import android.app.Application
import eu.stgm.pactum.figlio.catalogo.CatalogoApp
import eu.stgm.pactum.figlio.faccende.ArchivioBlocco
import eu.stgm.pactum.figlio.faccende.FermatoDuranteBlocco
import eu.stgm.pactum.figlio.faccende.Orologio
import eu.stgm.pactum.figlio.sessione.ArchivioSessioni
import eu.stgm.pactum.figlio.sync.BattitoWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PactumApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // I nomi delle app ricordati si leggono dal disco adesso, in un thread
        // di I/O: la composizione li trova già in memoria.
        CatalogoApp.precarica(this)
        // (0.11) Anche le Sessioni: una in corso si sa da subito (schermate,
        // notifica fissa, barriera), anche dopo la morte del processo.
        ArchivioSessioni.precarica(this)
        // (0.13) E il blocco delle faccende: un telefono bloccato lo sa da subito.
        // L'orologio del blocco sa quale accensione è (l'ordine delle risposte
        // si conta su quello che non si sposta).
        Orologio.inizializza(this)
        ArchivioBlocco.precarica(this)
        // (0.18) E la Sessione Studio: uno Studio in corso si sa da subito.
        eu.stgm.pactum.figlio.studio.ArchivioStudio.precarica(this)
        BattitoWorker.pianifica(this)
        // (0.13) Pactum fermato a mano ("Forza arresto") durante il blocco: lo si
        // dice al registro appena riparte.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { FermatoDuranteBlocco.controlla(this@PactumApp) }
        }
    }
}
