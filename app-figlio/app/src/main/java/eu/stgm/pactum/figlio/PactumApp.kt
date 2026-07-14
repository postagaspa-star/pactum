package eu.stgm.pactum.figlio

import android.app.Application
import eu.stgm.pactum.figlio.sync.BattitoWorker

class PactumApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BattitoWorker.pianifica(this)
    }
}
