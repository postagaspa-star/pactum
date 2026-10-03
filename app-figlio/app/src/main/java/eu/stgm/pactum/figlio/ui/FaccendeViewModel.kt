package eu.stgm.pactum.figlio.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.faccende.ConsegnaFoto
import eu.stgm.pactum.figlio.faccende.ControlloBlocco
import eu.stgm.pactum.figlio.rete.PostinoClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * (0.13) La pagina Faccende: l'elenco dal server (`GET /api/faccende`) e il
 * blocco (`GET /api/faccende/blocco`), che finiscono nell'archivio del blocco
 * (anche offline la pagina ha l'ultimo); le foto scattate, che entrano nella
 * coda e partono subito; la foto di una faccenda fatta, da guardare.
 */
class FaccendeViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface Evento {
        /** La foto è pronta ed è in coda: parte adesso, o appena c'è rete. */
        data object FotoInCoda : Evento

        /** Lo scatto non si è potuto preparare (vuoto, illeggibile, memoria finita). */
        data object FotoNonRiuscita : Evento

        /** Il telefono non è collegato al patto. */
        data object NonCollegato : Evento

        /** La foto di una faccenda fatta non si è potuta scaricare. */
        data class FotoNonScaricata(val nonCe: Boolean) : Evento
    }

    data class FotoAperta(val faccendaId: Long, val titolo: String, val immagine: Bitmap)

    data class StatoFaccende(
        val caricamento: Boolean = true,
        val letto: Boolean = false,
        val configurazioneMancante: Boolean = false,
        /** 404/405: il server non conosce le faccende (prima della v3.6). */
        val serverDaAggiornare: Boolean = false,
        /** Il server non ha risposto: si mostra l'ultimo elenco, con la sua età. */
        val datiFermi: Boolean = false,
        val scollegato: Boolean = false,
        val preparazioneInCorso: Boolean = false,
        val scaricamentoInCorso: Long? = null,
        val fotoAperta: FotoAperta? = null,
        val evento: Evento? = null,
    )

    private val _stato = MutableStateFlow(StatoFaccende())
    val stato: StateFlow<StatoFaccende> = _stato.asStateFlow()

    fun aggiorna() {
        _stato.update { it.copy(caricamento = true) }
        viewModelScope.launch {
            val app = getApplication<Application>()
            val configurazione = Impostazioni(app).leggiConfigurazione()
            if (!configurazione.completa) {
                _stato.update { it.copy(caricamento = false, letto = true, configurazioneMancante = true) }
                return@launch
            }
            val elenco = ControlloBlocco.leggiElenco(app)
            // Il blocco anche qui: la pagina dice subito se il telefono è bloccato.
            val blocco = ControlloBlocco.interroga(app)
            val esito = if (elenco == ControlloBlocco.Esito.LETTO) blocco else elenco
            _stato.update {
                it.copy(
                    caricamento = false,
                    letto = true,
                    configurazioneMancante = false,
                    serverDaAggiornare = elenco == ControlloBlocco.Esito.SERVER_VECCHIO,
                    datiFermi = esito == ControlloBlocco.Esito.SENZA_RETE || esito == ControlloBlocco.Esito.ERRORE,
                    scollegato = esito == ControlloBlocco.Esito.SCOLLEGATO,
                )
            }
        }
    }

    /** Lo scatto [originale] per [faccendaId] è tornato dalla fotocamera: si prepara e si manda. */
    fun scattata(faccendaId: Long, bocciature: Int, originale: File) {
        _stato.update { it.copy(preparazioneInCorso = true) }
        viewModelScope.launch {
            val esito = ConsegnaFoto.nuovoScatto(getApplication(), faccendaId, bocciature, originale)
            _stato.update {
                it.copy(
                    preparazioneInCorso = false,
                    evento = when (esito) {
                        ConsegnaFoto.EsitoScatto.IN_CODA -> Evento.FotoInCoda
                        ConsegnaFoto.EsitoScatto.NON_RIUSCITO -> Evento.FotoNonRiuscita
                        ConsegnaFoto.EsitoScatto.NON_COLLEGATO -> Evento.NonCollegato
                    },
                )
            }
        }
    }

    /** La foto di una faccenda fatta: si scarica e si apre. */
    fun apriFoto(faccendaId: Long, titolo: String) {
        _stato.update { it.copy(scaricamentoInCorso = faccendaId) }
        viewModelScope.launch {
            val app = getApplication<Application>()
            val (immagine, codice) = withContext(Dispatchers.IO) {
                val cartella = File(app.cacheDir, "faccende-viste").apply { mkdirs() }
                val file = File(cartella, "$faccendaId.jpg")
                val codice = PostinoClient(Impostazioni(app).leggiConfigurazione()).scaricaFoto(faccendaId, file)
                val immagine = if (codice in 200..299) leggiRimpicciolita(file) else null
                file.delete()
                immagine to codice
            }
            _stato.update {
                it.copy(
                    scaricamentoInCorso = null,
                    fotoAperta = immagine?.let { img -> FotoAperta(faccendaId, titolo, img) } ?: it.fotoAperta,
                    evento = if (immagine == null) Evento.FotoNonScaricata(nonCe = codice == 404) else it.evento,
                )
            }
        }
    }

    fun chiudiFoto() {
        _stato.update { it.copy(fotoAperta = null) }
    }

    fun consumaEvento() {
        _stato.update { it.copy(evento = null) }
    }

    /** Per guardarla basta poco: il lato lungo intorno ai 1600 pixel. */
    private fun leggiRimpicciolita(file: File): Bitmap? = try {
        val misure = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, misure)
        var passo = 1
        while (maxOf(misure.outWidth, misure.outHeight) / (passo * 2) >= LATO_VISTA) passo *= 2
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = passo })
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    private companion object {
        const val LATO_VISTA = 1600
    }
}
