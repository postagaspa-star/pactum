package eu.stgm.pactum.figlio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.permessi.PermessiHelper
import eu.stgm.pactum.figlio.sessione.ArchivioSessioni
import eu.stgm.pactum.figlio.sessione.AvvioIncerto
import eu.stgm.pactum.figlio.sessione.ConsegnaSessioni
import eu.stgm.pactum.figlio.sessione.RichiestaTermine
import eu.stgm.pactum.figlio.sessione.SessioneAttiva
import eu.stgm.pactum.figlio.sessione.StatoSessione
import eu.stgm.pactum.figlio.sessione.TestoSessioni
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** (0.11) La Sessione in corso adesso e l'ora di adesso, che si aggiorna da sola. */
data class SessioneVista(val attiva: SessioneAttiva?, val adesso: Long)

/**
 * (0.11) La Sessione in corso, per le schermate: null appena finisce
 * (scaduta, terminata, sparita). L'ora si rilegge ogni quarto di minuto, e
 * proprio alla fine: "Ancora 12 min" non resta indietro.
 */
@Composable
fun rememberSessioneInCorso(): SessioneVista {
    val attiva by StatoSessione.attiva.collectAsStateWithLifecycle()
    var adesso by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(attiva) {
        while (true) {
            adesso = System.currentTimeMillis()
            val fine = attiva?.fine ?: break
            if (adesso >= fine) break
            delay(minOf(INTERVALLO_ORA_MS, fine - adesso + 50))
        }
    }
    return SessioneVista(attiva?.takeIf { adesso < it.fine }, adesso)
}

/** (0.11) Pactum può leggere l'uso delle app? Si riguarda a ogni ritorno in primo piano. */
@Composable
fun rememberAccessoUso(): Boolean {
    val context = LocalContext.current
    var concesso by remember { mutableStateOf(PermessiHelper.haAccessoUso(context)) }
    LifecycleResumeEffect(Unit) {
        concesso = PermessiHelper.haAccessoUso(context)
        onPauseOrDispose { }
    }
    return concesso
}

/**
 * (0.11) Le schermate che prendono il posto delle schede (i permessi, "Cosa
 * vede tuo padre", la prima regola): con una sessione in corso, la sua scheda
 * sta sopra, così "Termina la sessione" si raggiunge anche da lì.
 */
@Composable
fun ConSessioneInCorso(contenuto: @Composable () -> Unit) {
    val vista = rememberSessioneInCorso()
    val attiva = vista.attiva
    if (attiva == null) {
        contenuto()
        return
    }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = Spazi.l, vertical = Spazi.s),
        ) {
            SchedaSessioneInCorso(attiva = attiva, adesso = vista.adesso, onTerminata = {})
        }
        Box(modifier = Modifier.weight(1f).consumeWindowInsets(WindowInsets.statusBars)) {
            contenuto()
        }
    }
}

/** (0.11) Un "Inizia" rimasto senza risposta, finché non si chiarisce (null = niente). */
@Composable
fun rememberAvvioIncerto(): AvvioIncerto? {
    val incerto by StatoSessione.incerto.collectAsStateWithLifecycle()
    return incerto
}

/** (0.11) Lo si dice in una riga neutra (Oggi e Sessioni). */
@Composable
fun RigaAvvioIncerto(incerto: AvvioIncerto) {
    RigaNeutra(stringResource(R.string.sessione_incerta_riga, incerto.nome))
}

/**
 * (0.11) In cima a Oggi (e a Sessioni) mentre una Sessione è in corso: quale,
 * fino a quando, quanto manca, le app che si possono usare, e "Termina la
 * sessione" con la conferma. Terminare vale subito, anche senza rete: la
 * barriera si ferma adesso e il server lo saprà appena può. [onTerminata] =
 * per dirlo nella snackbar della schermata. Vederla qui vuol dire saperla
 * partita: da qui in poi vale la barriera.
 */
@Composable
fun SchedaSessioneInCorso(
    attiva: SessioneAttiva,
    adesso: Long,
    onTerminata: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val ambito = rememberCoroutineScope()
    var conferma by rememberSaveable(attiva.svoltaId) { mutableStateOf(false) }
    var terminando by remember { mutableStateOf(false) }
    val mancano = TestoSessioni.minutiMancanti(attiva.fine, adesso)

    // Il ragazzo la sta guardando: adesso lo sa.
    LaunchedEffect(attiva.svoltaId, attiva.annunciata) {
        if (!attiva.annunciata) {
            withContext(Dispatchers.IO) { runCatching { ArchivioSessioni.annuncia(context, attiva.svoltaId) } }
        }
    }
    // "Termina la sessione" toccato nella notifica fissa: qui la conferma.
    val richiesta by RichiestaTermine.richiesta.collectAsStateWithLifecycle()
    LaunchedEffect(richiesta) {
        if (richiesta != 0L) {
            if (RichiestaTermine.fresca(richiesta)) conferma = true
            RichiestaTermine.consuma()
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(Spazi.l + Spazi.xs),
            verticalArrangement = Arrangement.spacedBy(Spazi.s),
        ) {
            Text(
                text = testoFinoAlle(
                    context,
                    attiva.fine,
                    adesso,
                    R.string.sessione_in_corso_titolo,
                    R.string.sessione_in_corso_titolo_domani,
                    attiva.nome,
                ),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = if (mancano <= 1) {
                    stringResource(R.string.sessione_manca_poco)
                } else {
                    stringResource(R.string.sessione_mancano, testoDurata(mancano))
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = stringResource(
                    R.string.sessione_in_corso_app,
                    elencoAppSessione(context, attiva.app.toList(), attiva.nomi, massimo = 6),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            OutlinedButton(onClick = { conferma = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.sessione_termina))
            }
        }
    }

    if (conferma) {
        AlertDialog(
            onDismissRequest = { conferma = false },
            title = { Text(stringResource(R.string.sessione_termina_titolo)) },
            text = { Text(stringResource(R.string.sessione_termina_testo)) },
            confirmButton = {
                Button(
                    enabled = !terminando,
                    onClick = {
                        terminando = true
                        ambito.launch {
                            val terminata = try {
                                ConsegnaSessioni.termina(context)
                            } finally {
                                terminando = false
                                conferma = false
                            }
                            if (terminata) onTerminata()
                        }
                    },
                ) {
                    Text(stringResource(R.string.sessione_termina_conferma))
                }
            },
            dismissButton = {
                TextButton(onClick = { conferma = false }) { Text(stringResource(R.string.azione_annulla)) }
            },
        )
    }
}

private const val INTERVALLO_ORA_MS = 15_000L
