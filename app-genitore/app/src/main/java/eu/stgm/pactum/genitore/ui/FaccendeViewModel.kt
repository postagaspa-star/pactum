package eu.stgm.pactum.genitore.ui

import android.app.Application
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.pactum.genitore.dati.Faccenda
import eu.stgm.pactum.genitore.dati.Impostazioni
import eu.stgm.pactum.genitore.dati.RiferimentoGenitore
import eu.stgm.pactum.genitore.rete.PostinoClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

/**
 * (0.13) Le faccende del figlio scelto (contratto v3.6), nello scope dell'attività:
 * la pagina delle faccende e la Panoramica vedono le stesse cose. Tutta la logica
 * sta in [GestoreFaccende] (provata con un server finto, FaccendeViewModelTest);
 * qui ci sono solo le cose di Android: lo scope, il collegamento salvato e la foto
 * trasformata in un'immagine. Le foto restano SOLO in memoria.
 */
class FaccendeViewModel(application: Application) : AndroidViewModel(application) {

    private val gestore = GestoreFaccende(
        ambito = viewModelScope,
        fonte = {
            Impostazioni(application).leggiConfigurazione().takeIf { it.completa }?.let { PostinoClient(it) }
        },
        decodifica = { decodificaFoto(it) },
        contestoDecodifica = Dispatchers.Default,
    )

    val stato: StateFlow<StatoFaccende<ImageBitmap>> = gestore.stato

    fun aggiorna(figlioId: Long?) = gestore.aggiorna(figlioId)

    fun daiFaccende(figlioId: Long, titoli: List<String>, nota: String?, bloccoDa: String?, io: RiferimentoGenitore?) =
        gestore.daiFaccende(figlioId, titoli, nota, bloccoDa, io)

    fun boccia(figlioId: Long?, faccenda: Faccenda, nota: String?) = gestore.boccia(figlioId, faccenda, nota)

    fun annulla(figlioId: Long?, faccenda: Faccenda) = gestore.annulla(figlioId, faccenda)

    fun consumaEvento() = gestore.consumaEvento()

    fun apriFoto(faccendaId: Long, fotoTs: String?) = gestore.apriFoto(faccendaId, fotoTs)

    fun chiudiFoto() = gestore.chiudiFoto()

    /** Dopo un cambio di server: le faccende e le foto di prima sono di un altro collegamento. */
    fun dimentica() = gestore.dimentica()

    private companion object {
        /** Il lato lungo massimo di una foto in memoria: il telefono del figlio le manda già così. */
        const val LATO_MASSIMO = 2048

        /** La foto in un'immagine, rimpicciolita se serve; null se non è un'immagine. */
        fun decodificaFoto(byte: ByteArray): ImageBitmap? {
            val misure = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(byte, 0, byte.size, misure)
            if (misure.outWidth <= 0 || misure.outHeight <= 0) return null
            var campione = 1
            while (maxOf(misure.outWidth, misure.outHeight) / campione > LATO_MASSIMO) campione *= 2
            val opzioni = BitmapFactory.Options().apply { inSampleSize = campione }
            return BitmapFactory.decodeByteArray(byte, 0, byte.size, opzioni)?.asImageBitmap()
        }
    }
}
