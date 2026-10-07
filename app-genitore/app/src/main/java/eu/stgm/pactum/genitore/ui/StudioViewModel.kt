package eu.stgm.pactum.genitore.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.pactum.genitore.dati.Impostazioni
import eu.stgm.pactum.genitore.rete.PostinoClient
import kotlinx.coroutines.flow.StateFlow

/**
 * (0.18, contratto v4.0) La Sessione Studio del figlio scelto, nello scope
 * dell'attività: la pagina dello Studio, la Panoramica (Chiudi lo Studio) e "Da
 * decidere" (la configurazione) vedono le stesse cose. Tutta la logica sta in
 * [GestoreStudio] (provata con un server finto, GestoreStudioTest).
 */
class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val impostazioni = Impostazioni(application)

    private val gestore = GestoreStudio(
        ambito = viewModelScope,
        fonte = { impostazioni.leggiConfigurazione().takeIf { it.completa }?.let { PostinoClient(it) } },
    )

    val stato: StateFlow<StatoStudio> = gestore.stato

    fun aggiorna(figlioId: Long?, daCapo: Boolean = false) = gestore.aggiorna(figlioId, daCapo)

    fun altri(figlioId: Long?) = gestore.altri(figlioId)

    fun rispondi(figlioId: Long?, versione: Int, esito: String, motivazione: String?) =
        gestore.rispondi(figlioId, versione, esito, motivazione)

    fun chiudi(figlioId: Long?, studioId: Long, motivo: String) = gestore.chiudi(figlioId, studioId, motivo)

    fun consumaEvento() = gestore.consumaEvento()

    /** Dopo un cambio di server: lo Studio di prima è di un altro collegamento. */
    fun dimentica() = gestore.dimentica()
}
