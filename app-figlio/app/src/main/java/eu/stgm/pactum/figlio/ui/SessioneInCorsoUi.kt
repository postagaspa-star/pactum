package eu.stgm.pactum.figlio.ui

import eu.stgm.pactum.design.CardEvidenza
import eu.stgm.pactum.design.RigaStato
import eu.stgm.pactum.design.Tono
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import eu.stgm.pactum.figlio.sessione.PaginaSessioneActivity
import eu.stgm.pactum.figlio.sessione.RichiestaTermine
import eu.stgm.pactum.figlio.sessione.SessioneAttiva
import eu.stgm.pactum.figlio.sessione.StatoSessione
import eu.stgm.pactum.figlio.sessione.TestoSessioni
import eu.stgm.pactum.figlio.sessione.nomeSessioneTraVirgolette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
 * (0.11) Le schermate del primo avvio (Collega, i permessi, "Cosa vedono i
 * tuoi genitori", la prima regola): con una sessione in corso, in cima la sua
 * riga con "Termina", così terminarla si può anche da lì. (0.15) Una riga, non
 * una card: sopra la schermata c'è al massimo una cosa.
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
                .padding(horizontal = Spazi.l + Spazi.xs, vertical = Spazi.s),
        ) {
            RigaSessioneInCorso(attiva = attiva, adesso = vista.adesso, onTerminata = {})
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
    RigaStato(stringResource(R.string.sessione_incerta_riga, nomeSessioneTraVirgolette(LocalContext.current, incerto.nome)))
}

/**
 * (0.11, 0.15) "Termina" della sessione in corso: la conferma ("Terminare la
 * sessione?") e tutto quello che serve a chi mostra la sessione, card o riga.
 * Vederla vuol dire saperla partita: la si segna annunciata. "Termina la
 * sessione" toccato nella notifica fissa (RichiestaTermine) apre la conferma.
 * Terminare vale subito, anche senza rete: la barriera si ferma adesso e il
 * server lo saprà appena può. [onTerminata] = per dirlo nella snackbar.
 */
@Stable
class TermineSessione internal constructor(private val apriConferma: () -> Unit) {
    fun chiedi() = apriConferma()
}

@Composable
fun rememberTermineSessione(
    attiva: SessioneAttiva,
    onTerminata: () -> Unit,
    /**
     * (0.15) Ascolta "Termina la sessione" della notifica fissa: solo la card di
     * Oggi (dove porta quel pulsante) e la riga sopra i passi del primo avvio.
     * La card di Sessioni no: prendeva la richiesta prima del cambio di scheda,
     * la conferma restava aperta là e ricompariva aprendo Sessioni.
     */
    ascoltaNotifica: Boolean,
): TermineSessione {
    val context = LocalContext.current
    val ambito = rememberCoroutineScope()
    var conferma by rememberSaveable(attiva.svoltaId) { mutableStateOf(false) }
    var terminando by remember { mutableStateOf(false) }
    val onTerminataAttuale by rememberUpdatedState(onTerminata)

    // Il ragazzo la sta guardando: adesso lo sa.
    LaunchedEffect(attiva.svoltaId, attiva.annunciata) {
        if (!attiva.annunciata) {
            withContext(Dispatchers.IO) { runCatching { ArchivioSessioni.annuncia(context, attiva.svoltaId) } }
        }
    }
    // "Termina la sessione" toccato nella notifica fissa: qui la conferma.
    val richiesta by RichiestaTermine.richiesta.collectAsStateWithLifecycle()
    LaunchedEffect(richiesta, ascoltaNotifica) {
        if (ascoltaNotifica && richiesta != 0L) {
            if (RichiestaTermine.fresca(richiesta)) conferma = true
            RichiestaTermine.consuma()
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
                            // (0.12) Fino in fondo anche se questa schermata se ne va
                            // (la sessione è finita: la card sparisce, e il suo
                            // ambito con lei). Fra "termina" e la pagina della fine
                            // niente altre attese: la sessione chiusa la dà termina.
                            withContext(NonCancellable) {
                                val chiusa = try {
                                    ConsegnaSessioni.termina(context)
                                } finally {
                                    terminando = false
                                    conferma = false
                                }
                                if (chiusa != null) {
                                    PaginaSessioneActivity.apriFine(context, chiusa)
                                    onTerminataAttuale()
                                }
                            }
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
    return remember(attiva.svoltaId) { TermineSessione { conferma = true } }
}

/** "In sessione 📚 «Studio» fino alle 17:00" (o "fino a domani alle"). */
@Composable
fun titoloSessioneInCorso(attiva: SessioneAttiva, adesso: Long): String {
    val context = LocalContext.current
    return testoFinoAlle(
        context,
        attiva.fine,
        adesso,
        R.string.sessione_in_corso_titolo,
        R.string.sessione_in_corso_titolo_domani,
        nomeSessioneTraVirgolette(context, attiva.nome),
    )
}

/**
 * (0.11, 0.15) La card della sessione in corso, in cima a Oggi e a Sessioni:
 * quale, fino a quando, quanto manca, e "Termina" con la conferma.
 */
@Composable
fun SchedaSessioneInCorso(
    attiva: SessioneAttiva,
    adesso: Long,
    onTerminata: () -> Unit,
    modifier: Modifier = Modifier,
    /** La card di Oggi sì, quella di Sessioni no (rememberTermineSessione). */
    ascoltaNotifica: Boolean = true,
    /** (0.16) "Puoi usare: ClasseViva, Calcolatrice e altre 3." (non in Sessioni, dove la card della sessione le dice). */
    mostraApp: Boolean = true,
) {
    val context = LocalContext.current
    val termine = rememberTermineSessione(attiva, onTerminata, ascoltaNotifica)
    val mancano = TestoSessioni.minutiMancanti(attiva.fine, adesso)
    CardEvidenza(modifier = modifier, tono = Tono.Neutro) {
        Text(text = titoloSessioneInCorso(attiva, adesso), style = MaterialTheme.typography.titleMedium)
        Text(
            text = if (mancano <= 1) {
                stringResource(R.string.sessione_manca_poco)
            } else {
                stringResource(R.string.sessione_mancano, testoDurata(mancano))
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (mostraApp) {
            Text(
                text = stringResource(
                    R.string.sessione_in_corso_app,
                    elencoAppSessione(context, attiva.app.toList(), attiva.nomi, massimo = 2),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(modifier = Modifier.height(Spazi.m))
        OutlinedButton(onClick = { termine.chiedi() }) {
            Text(stringResource(R.string.sessione_termina_conferma), maxLines = 1)
        }
    }
}

/**
 * (0.15) La sessione in corso in una riga ("In sessione 📚 «Studio» fino alle
 * 17:00 · Termina"): in Oggi quando il blocco dei lavori di casa ha la card, e
 * sopra le schermate del primo avvio.
 */
@Composable
fun RigaSessioneInCorso(
    attiva: SessioneAttiva,
    adesso: Long,
    onTerminata: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val termine = rememberTermineSessione(attiva, onTerminata, ascoltaNotifica = true)
    RigaStato(
        testo = titoloSessioneInCorso(attiva, adesso),
        modifier = modifier,
        azione = stringResource(R.string.sessione_termina_conferma),
        onAzione = { termine.chiedi() },
    )
}

private const val INTERVALLO_ORA_MS = 15_000L
