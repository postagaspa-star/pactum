package eu.stgm.pactum.figlio

import android.app.Application
import eu.stgm.pactum.figlio.catalogo.CatalogoApp
import eu.stgm.pactum.figlio.sessione.ArchivioSessioni
import eu.stgm.pactum.figlio.sync.BattitoWorker

class PactumApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // I nomi delle app ricordati si leggono dal disco adesso, in un thread
        // di I/O: la composizione li trova già in memoria.
        CatalogoApp.precarica(this)
        // (0.11) Anche le Sessioni: una in corso si sa da subito (schermate,
        // notifica fissa, barriera), anche dopo la morte del processo.
        ArchivioSessioni.precarica(this)
        BattitoWorker.pianifica(this)
    }
}
