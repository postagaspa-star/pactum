package eu.stgm.pactum.genitore.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import eu.stgm.pactum.design.TitoloBarra
import eu.stgm.pactum.design.coloriBarra
import eu.stgm.pactum.genitore.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// (0.15) La cornice comune alle schermate: la barra in alto delle schede (titolo,
// campanella, ⟳, Impostazioni), quella delle pagine (← e titolo), i messaggi in
// basso e i gesti per andare altrove. Le schermate li prendono da [LocalCornice],
// così non serve passarli a mano attraverso ogni livello.

/**
 * I messaggi in basso (snackbar) di tutta l'app: vivono nella radice, quindi un
 * esito arrivato mentre una pagina si chiude (i lavori di casa appena dati) si
 * vede lo stesso sulla schermata dove si torna.
 */
class Messaggi(val stato: SnackbarHostState, private val ambito: CoroutineScope?) {
    fun mostra(testo: String) {
        ambito?.launch { stato.showSnackbar(testo) }
    }
}

/** Quello che la radice dà a ogni schermata: dove andare, e quante notifiche non lette. */
class Cornice(
    val notificheNonLette: Int = 0,
    val messaggi: Messaggi = Messaggi(SnackbarHostState(), null),
    /** Un'altra scheda (da una pagina si impila: Indietro torna alla pagina). */
    val vaiAScheda: (Scheda) -> Unit = {},
    /** Una pagina sopra quello che c'è. */
    val apri: (Pagina) -> Unit = {},
    /** Come il tasto Indietro. */
    val indietro: () -> Unit = {},
    /** Via tutte le pagine: si torna alla Panoramica (dopo il primo collegamento). */
    val allaPanoramica: () -> Unit = {},
    /** (0.18) I Lavori del figlio scelto, con la foto di questo lavoro aperta (da "Da decidere"). */
    val apriFoto: (faccendaId: Long) -> Unit = {},
)

val LocalCornice = staticCompositionLocalOf { Cornice() }

/**
 * La barra in alto di una SCHEDA: il titolo su una riga e, in ogni scheda, la
 * campanella delle notifiche (col numero delle non lette), ⟳ e le Impostazioni.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarraScheda(titolo: String, onAggiorna: () -> Unit) {
    val cornice = LocalCornice.current
    TopAppBar(
        // Il titolo sta su una riga anche col testo grande: si rimpicciolisce, non si tronca (B32).
        // (0.19) Accanto gli adesivi della sezione, e la barra nei suoi colori.
        title = { TitoloBarra(titolo) },
        colors = coloriBarra(),
        actions = {
            PulsanteNotifiche(cornice.notificheNonLette) { cornice.apri(Pagina.Notifiche) }
            IconButton(onClick = onAggiorna) {
                Icon(Icons.Filled.Refresh, stringResource(R.string.azione_aggiorna))
            }
            IconButton(onClick = { cornice.apri(Pagina.Impostazioni()) }) {
                Icon(painterResource(R.drawable.ic_scheda_impostazioni), stringResource(R.string.impostazioni_titolo))
            }
        },
    )
}

/** La barra in alto di una PAGINA: ← per tornare, il titolo, e le sue azioni. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarraPagina(titolo: String, azioni: @Composable RowScope.() -> Unit = {}) {
    val cornice = LocalCornice.current
    TopAppBar(
        title = { TitoloBarra(titolo) },
        colors = coloriBarra(),
        navigationIcon = {
            IconButton(onClick = cornice.indietro) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.azione_indietro))
            }
        },
        actions = azioni,
    )
}

/**
 * La campanella delle notifiche non lette. Il badge è nel blu dell'app, non nel
 * rosso `error` di Material: il rosso vive solo nella striscia dei giorni.
 */
@Composable
private fun PulsanteNotifiche(nonLette: Int, onClick: () -> Unit) {
    val descrizione = if (nonLette > 0) {
        pluralStringResource(R.plurals.notifiche_non_lette, nonLette, nonLette)
    } else {
        stringResource(R.string.notifiche_titolo)
    }
    IconButton(onClick = onClick) {
        BadgedBox(
            badge = {
                if (nonLette > 0) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Text(testoBadge(nonLette))
                    }
                }
            },
        ) {
            Icon(Icons.Outlined.Notifications, contentDescription = descrizione)
        }
    }
}
