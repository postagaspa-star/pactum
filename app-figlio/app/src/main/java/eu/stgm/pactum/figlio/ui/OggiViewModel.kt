package eu.stgm.pactum.figlio.ui

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.stgm.pactum.figlio.catalogo.CatalogoApp
import eu.stgm.pactum.figlio.misura.UsageStatsReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class OggiViewModel(application: Application) : AndroidViewModel(application) {

    data class RigaUso(val etichetta: String, val pacchetto: String, val minuti: Long)

    data class StatoOggi(
        val caricamento: Boolean = true,
        val righe: List<RigaUso> = emptyList(),
        val minutiTotali: Long = 0,
    )

    private val _stato = MutableStateFlow(StatoOggi())
    val stato: StateFlow<StatoOggi> = _stato.asStateFlow()

    fun aggiorna() {
        _stato.value = _stato.value.copy(caricamento = true)
        viewModelScope.launch(Dispatchers.Default) {
            val context = getApplication<Application>()
            // Stesso filtro della fotografia inviata al server (BattitoWorker):
            // fuori Home, sistema senza icona e le due app Pactum. Così il totale
            // "Oggi" del figlio coincide con quello che il genitore vede nella
            // finestra, invece di gonfiarsi di minuti che lì non compaiono.
            val uso = UsageStatsReader(context).usoDelGiorno()
                .filter { CatalogoApp.contaNellUso(context, it.pacchetto) }
            val pm = context.packageManager
            val righe = uso
                .filter { it.millisPrimoPiano >= 60_000 } // sotto il minuto: rumore
                .map {
                    RigaUso(
                        etichetta = etichettaApp(pm, it.pacchetto),
                        pacchetto = it.pacchetto,
                        minuti = it.millisPrimoPiano / 60_000,
                    )
                }
                .sortedByDescending { it.minuti }
            val minutiTotali = uso.sumOf { it.millisPrimoPiano } / 60_000
            _stato.value = StatoOggi(caricamento = false, righe = righe, minutiTotali = minutiTotali)
        }
    }

    private fun etichettaApp(pm: PackageManager, pacchetto: String): String = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(pacchetto, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(pacchetto, 0)
        }
        info.loadLabel(pm).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pacchetto // app disinstallata nel frattempo: resta il nome del pacchetto
    }
}
