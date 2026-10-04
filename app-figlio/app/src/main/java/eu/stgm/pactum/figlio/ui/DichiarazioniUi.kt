package eu.stgm.pactum.figlio.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.dati.Dichiarazione
import eu.stgm.pactum.figlio.dati.EsitiDichiarazione
import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.StatiDichiarazione
import eu.stgm.pactum.figlio.dati.zonaPatto
import kotlinx.coroutines.launch
import java.time.LocalDate

/*
 * (0.15) Le dichiarazioni sulle regole di vita reale, fuori dal vecchio
 * Diario: si dichiara da Oggi ("Segna") e da Regole ("Ce l'ho fatta / Non ce
 * l'ho fatta"), si rilegge nello Storico. La logica è quella di sempre
 * (DichiarazioniViewModel): qui solo il dialogo, gli esiti e le frasi.
 */

/** "Oggi" nel fuso del patto, come lo assegna il server alle dichiarazioni. */
fun oggiDelPatto(fuso: String?): LocalDate = LocalDate.now(zonaPatto(fuso))

/** La dichiarazione di oggi su [regola], se c'è (la più recente vince: provvisorie davanti). */
fun dichiarazioneDiOggi(regola: Regola, dichiarazioni: List<Dichiarazione>, oggi: LocalDate): Dichiarazione? =
    dichiarazioni.firstOrNull { it.regolaId == regola.id && it.giorno == oggi.toString() }

/** L'arbitro di una regola di vita reale, null se non è scritto. */
fun arbitroDi(regola: Regola?): String? =
    regola?.let { parametroTesto(it.parametri, "arbitro_nome") }?.trim()?.ifEmpty { null }

/**
 * Lo stato raccontato dal punto di vista del figlio. (v2.1) La frase del
 * verdetto la congela il server: se c'è si mostra QUELLA, com'è.
 */
@Composable
fun descrizioneStato(dichiarazione: Dichiarazione, regola: Regola?): String {
    dichiarazione.verdetto?.registro?.takeIf { it.isNotBlank() }?.let { return it }
    val arbitro = arbitroDi(regola)
    return when (dichiarazione.stato) {
        StatiDichiarazione.IN_ATTESA -> if (arbitro != null) {
            stringResource(R.string.dichiarazione_stato_in_attesa, arbitro)
        } else {
            stringResource(R.string.dichiarazione_stato_in_attesa_senza_nome)
        }
        StatiDichiarazione.REGISTRATA -> stringResource(R.string.dichiarazione_stato_registrata)
        StatiDichiarazione.CONFERMATA -> stringResource(R.string.dichiarazione_stato_confermata)
        StatiDichiarazione.CONFERMATA_PER_CONTO -> if (arbitro != null) {
            stringResource(R.string.dichiarazione_stato_confermata_per_conto, arbitro)
        } else {
            stringResource(R.string.dichiarazione_stato_confermata_per_conto_senza_nome)
        }
        StatiDichiarazione.RIBALTATA -> stringResource(R.string.dichiarazione_stato_ribaltata)
        else -> stringResource(R.string.dichiarazione_stato_altro)
    }
}

/**
 * Chi dichiara (Oggi e Regole) tiene qui il dialogo aperto: per quale regola
 * e con quale esito (null = da scegliere nel dialogo). Si ricorda attraverso
 * una rotazione e la morte del processo, con quello che si è scritto.
 */
@Stable
class Dichiarare internal constructor(
    private val apriSu: (Regola, String?) -> Unit,
) {
    /** Apre la dichiarazione su [regola]; [esito] già scelto ("Ce l'ho fatta"), o null = "Segna". */
    fun apri(regola: Regola, esito: String? = null) = apriSu(regola, esito)
}

/**
 * Il dialogo della dichiarazione e i suoi esiti in snackbar, per la schermata
 * che lo usa. [regole] = le regole di vita reale che la schermata mostra.
 */
@Composable
fun rememberDichiarare(
    vm: DichiarazioniViewModel,
    regole: List<Regola>,
    snackbarHostState: SnackbarHostState,
): Dichiarare {
    val stato by vm.stato.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val ambito = rememberCoroutineScope()
    var regolaId by rememberSaveable { mutableStateOf<Long?>(null) }
    var esitoIniziale by rememberSaveable { mutableStateOf<String?>(null) }
    // L'ultima copia della regola aperta: se intanto sparisce, il dialogo resta.
    val viste = remember { HashMap<Long, Regola>() }
    val tutteLeRegole = regole + stato.regoleVitaReale
    fun trova(id: Long?): Regola? = id?.let { cercato -> tutteLeRegole.firstOrNull { it.id == cercato } ?: viste[cercato] }

    LaunchedEffect(stato.evento) {
        val evento = stato.evento ?: return@LaunchedEffect
        val messaggio = when (evento) {
            is DichiarazioniViewModel.Evento.Inviata -> {
                regolaId = null
                if (evento.esito == EsitiDichiarazione.SUCCESSO) {
                    val arbitro = arbitroDi(trova(evento.regolaId))
                    if (arbitro != null) {
                        context.getString(R.string.dichiarazione_inviata_successo, arbitro)
                    } else {
                        context.getString(R.string.dichiarazione_inviata_successo_senza_nome)
                    }
                } else {
                    context.getString(R.string.dichiarazione_inviata_fallimento)
                }
            }
            DichiarazioniViewModel.Evento.GiaDichiarato -> {
                regolaId = null
                context.getString(R.string.dichiarazione_gia_dichiarato)
            }
            DichiarazioniViewModel.Evento.SuccessoNonArrivato -> context.getString(R.string.dichiarazione_non_arrivata)
            DichiarazioniViewModel.Evento.Errore -> context.getString(R.string.dichiarazione_errore)
        }
        vm.consumaEvento()
        ambito.launch { snackbarHostState.showSnackbar(messaggio) }
    }

    trova(regolaId)?.let { regola ->
        val oggiIso = oggiDelPatto(stato.fuso).toString()
        // I giorni già dichiarati su QUESTA regola: restano non selezionabili nel
        // dialogo (il server li rifiuterebbe con gia_dichiarato).
        val giorniDichiarati = stato.tutte.filter { it.regolaId == regola.id }.map { it.giorno }.toSet()
        DialogoDichiarazione(
            regola = regola,
            esitoIniziale = esitoIniziale,
            oggiIso = oggiIso,
            giorniDichiarati = giorniDichiarati,
            invioInCorso = stato.invioInCorso,
            onAnnulla = { regolaId = null },
            onConferma = { esito, nota, giorno ->
                vm.dichiara(regola.id, esito, nota, giorno)
                // "Ce l'ho fatta": il riconoscimento è subito, il dialogo si
                // chiude senza aspettare il server. "Non ce l'ho fatta" resta
                // com'era: si chiude quando il server ha registrato.
                if (esito == EsitiDichiarazione.SUCCESSO) regolaId = null
            },
        )
    }

    return remember {
        Dichiarare { regola, esito ->
            viste[regola.id] = regola
            esitoIniziale = esito
            regolaId = regola.id
        }
    }
}

/**
 * (0.15) La dichiarazione: com'è andata (già scelta dai pulsanti di Regole, o
 * da scegliere qui quando si arriva da "Segna" in Oggi), per quale giorno, e
 * una nota facoltativa. Scorre: coi caratteri grandi o la tastiera aperta
 * niente resta tagliato fuori.
 */
@Composable
private fun DialogoDichiarazione(
    regola: Regola,
    esitoIniziale: String?,
    oggiIso: String,
    giorniDichiarati: Set<String>,
    invioInCorso: Boolean,
    onAnnulla: () -> Unit,
    onConferma: (esito: String, nota: String?, giorno: String?) -> Unit,
) {
    var esito by rememberSaveable(regola.id, esitoIniziale) { mutableStateOf(esitoIniziale) }
    var nota by rememberSaveable(regola.id, esitoIniziale) { mutableStateOf("") }
    // Giorno per cui si dichiara: default oggi. Il contratto permette oggi ↔ −7gg
    // (fuso del patto): "ieri ho camminato ma ho scordato di segnarlo" si può.
    var giornoScelto by rememberSaveable(regola.id, esitoIniziale) { mutableStateOf(oggiIso) }
    val scelto = esito

    AlertDialog(
        onDismissRequest = onAnnulla,
        title = {
            Text(
                stringResource(
                    when (scelto) {
                        EsitiDichiarazione.SUCCESSO -> R.string.dichiarazione_conferma_successo_titolo
                        EsitiDichiarazione.FALLIMENTO -> R.string.dichiarazione_conferma_fallimento_titolo
                        else -> R.string.dichiarazione_segna_titolo
                    },
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spazi.m),
            ) {
                Text(
                    text = descrizioneRegola(regola.tipo, regola.parametri),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Da "Segna": prima com'è andata.
                if (esitoIniziale == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spazi.s)) {
                        FilterChip(
                            selected = scelto == EsitiDichiarazione.SUCCESSO,
                            onClick = { esito = EsitiDichiarazione.SUCCESSO },
                            label = { Text(stringResource(R.string.dichiara_successo)) },
                        )
                        FilterChip(
                            selected = scelto == EsitiDichiarazione.FALLIMENTO,
                            onClick = { esito = EsitiDichiarazione.FALLIMENTO },
                            label = { Text(stringResource(R.string.dichiara_fallimento)) },
                        )
                    }
                }
                if (scelto != null) {
                    Text(
                        text = stringResource(
                            if (scelto == EsitiDichiarazione.SUCCESSO) {
                                R.string.dichiarazione_conferma_successo_testo
                            } else {
                                R.string.dichiarazione_conferma_fallimento_testo
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                SelettoreGiorno(
                    oggiIso = oggiIso,
                    giorniDichiarati = giorniDichiarati,
                    giornoScelto = giornoScelto,
                    onGiorno = { giornoScelto = it },
                )
                OutlinedTextField(
                    value = nota,
                    onValueChange = { nota = it },
                    label = { Text(stringResource(R.string.dichiarazione_nota_campo)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !invioInCorso && scelto != null,
                // Oggi è il default del server: si passa null per non forzare il
                // campo quando non serve; un giorno passato viaggia esplicito.
                onClick = {
                    scelto?.let {
                        onConferma(it, nota.trim().ifBlank { null }, giornoScelto.takeIf { g -> g != oggiIso })
                    }
                },
            ) {
                Text(stringResource(R.string.azione_conferma))
            }
        },
        dismissButton = {
            TextButton(onClick = onAnnulla) { Text(stringResource(R.string.azione_annulla)) }
        },
    )
}

/**
 * La striscia di giorni per cui dichiarare: oggi e i 7 precedenti (finestra del
 * contratto), scorribile. I giorni già dichiarati su questa regola sono spenti
 * — il server li rifiuterebbe con `gia_dichiarato`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelettoreGiorno(
    oggiIso: String,
    giorniDichiarati: Set<String>,
    giornoScelto: String,
    onGiorno: (String) -> Unit,
) {
    val oggi = remember(oggiIso) {
        runCatching { LocalDate.parse(oggiIso) }.getOrDefault(LocalDate.now())
    }
    // Sui chip le parole stanno da sole: maiuscole.
    val parole = ParoleGiorno(
        oggi = stringResource(R.string.dichiarazione_giorno_oggi),
        ieri = stringResource(R.string.dichiarazione_giorno_ieri),
    )
    Column(verticalArrangement = Arrangement.spacedBy(Spazi.xs)) {
        Text(
            text = stringResource(R.string.dichiarazione_scegli_giorno),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spazi.s),
        ) {
            for (indietro in 0..7) {
                val giorno = oggi.minusDays(indietro.toLong())
                val iso = giorno.toString()
                FilterChip(
                    selected = iso == giornoScelto,
                    onClick = { onGiorno(iso) },
                    enabled = iso !in giorniDichiarati,
                    label = { Text(giornoBreve(giorno, oggi, parole)) },
                )
            }
        }
    }
}
