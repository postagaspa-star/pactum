package eu.stgm.pactum.figlio.fotografo

import androidx.lifecycle.ViewModelProvider
import eu.stgm.pactum.figlio.ui.DichiarazioniViewModel
import eu.stgm.pactum.figlio.ui.FaccendeViewModel
import eu.stgm.pactum.figlio.ui.OggiViewModel
import eu.stgm.pactum.figlio.ui.ProposteViewModel
import eu.stgm.pactum.figlio.ui.RegoleViewModel
import eu.stgm.pactum.figlio.ui.SessioniViewModel
import eu.stgm.pactum.figlio.ui.SitiViewModel
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Gli stati finti dei sette ViewModel dell'app. [applica] li mette al posto di
 * quelli veri; i flussi restano qui ([flussi]) per cambiarli dopo l'apertura
 * (un esito che arriva con un dialogo già aperto).
 */
data class StatiFinti(
    val oggi: OggiViewModel.StatoOggi = DatiFinti.oggiNormale(),
    val regole: RegoleViewModel.StatoRegole = DatiFinti.regoleNormali(),
    val sessioni: SessioniViewModel.StatoSessioni = DatiFinti.sessioniNormali(),
    val proposte: ProposteViewModel.StatoProposte = DatiFinti.proposteNormali(),
    val diario: DichiarazioniViewModel.StatoDiario = DatiFinti.diarioNormale(),
    val siti: SitiViewModel.StatoSiti = DatiFinti.sitiNormali(),
    val faccende: FaccendeViewModel.StatoFaccende = DatiFinti.faccendeLette(),
) {
    class Flussi {
        lateinit var oggi: MutableStateFlow<OggiViewModel.StatoOggi>
        lateinit var regole: MutableStateFlow<RegoleViewModel.StatoRegole>
        lateinit var sessioni: MutableStateFlow<SessioniViewModel.StatoSessioni>
        lateinit var proposte: MutableStateFlow<ProposteViewModel.StatoProposte>
        lateinit var diario: MutableStateFlow<DichiarazioniViewModel.StatoDiario>
        lateinit var siti: MutableStateFlow<SitiViewModel.StatoSiti>
        lateinit var faccende: MutableStateFlow<FaccendeViewModel.StatoFaccende>
    }

    val flussi = Flussi()

    fun applica(p: ViewModelProvider) {
        flussi.oggi = p[OggiViewModel::class.java].fingi(oggi)
        flussi.regole = p[RegoleViewModel::class.java].fingi(regole)
        flussi.sessioni = p[SessioniViewModel::class.java].fingi(sessioni)
        flussi.proposte = p[ProposteViewModel::class.java].fingi(proposte)
        flussi.diario = p[DichiarazioniViewModel::class.java].fingi(diario)
        flussi.siti = p[SitiViewModel::class.java].fingi(siti)
        flussi.faccende = p[FaccendeViewModel::class.java].fingi(faccende)
    }
}
