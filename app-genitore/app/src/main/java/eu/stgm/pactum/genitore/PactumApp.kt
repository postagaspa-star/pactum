package eu.stgm.pactum.genitore

import android.app.Application
import eu.stgm.pactum.genitore.sync.VedettaWorker

class PactumApp : Application() {
    override fun onCreate() {
        super.onCreate()
        VedettaWorker.pianifica(this)
    }
}
