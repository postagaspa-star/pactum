package eu.stgm.pactum.figlio

import android.app.Application
import eu.stgm.pactum.figlio.catalogo.CatalogoApp
import eu.stgm.pactum.figlio.sync.BattitoWorker

class PactumApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // I nomi delle app ricordati si leggono dal disco adesso, in un thread
        // di I/O: la composizione li trova già in memoria.
        CatalogoApp.precarica(this)
        BattitoWorker.pianifica(this)
    }
}
