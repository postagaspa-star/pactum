package eu.stgm.pactum.figlio.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.dati.Dichiarazione
import eu.stgm.pactum.figlio.dati.EsitiDichiarazione
import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.StatiDichiarazione
import eu.stgm.pactum.figlio.dati.zonaPatto
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Il diario: dichiara com'è andata sulle regole di vita reale, a viso aperto. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DichiarazioniScreen(vm: DichiarazioniViewModel = viewModel()) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Il dialogo di dichiarazione: regola + esito già scelti dai due pulsanti.
    var dichiarazioneInCorso by remember { mutableStateOf<Pair<Regola, String>?>(null) }

    LifecycleResumeEffect(Unit) {
        vm.aggiorna()
        onPauseOrDispose { }
    }

    val messaggioFallimento = stringResource(R.string.dichiarazione_inviata_fallimento)
    val messaggioGia = stringResource(R.string.dichiarazione_gia_dichiarato)
    val messaggioNonArrivato = stringResource(R.string.dichiarazione_non_arrivata)
    val messaggioErrore = stringResource(R.string.dichiarazione_errore)
    LaunchedEffect(stato.evento) {
        when (val evento = stato.evento) {
            is DichiarazioniViewModel.Evento.Inviata -> {
                dichiarazioneInCorso = null
                snackbarHostState.showSnackbar(
                    if (evento.esito == EsitiDichiarazione.SUCCESSO) {
                        context.getString(
                            R.string.dichiarazione_inviata_successo,
                            arbitroDi(stato.regoleVitaReale.firstOrNull { it.id == evento.regolaId }),
                        )
                    } else {
                        messaggioFallimento
                    },
                )
            }
            is DichiarazioniViewModel.Evento.GiaDichiarato -> {
                dichiarazioneInCorso = null
                snackbarHostState.showSnackbar(messaggioGia)
            }
            is DichiarazioniViewModel.Evento.SuccessoNonArrivato ->
                snackbarHostState.showSnackbar(messaggioNonArrivato)
            is DichiarazioniViewModel.Evento.Errore -> snackbarHostState.showSnackbar(messaggioErrore)
            null -> Unit
        }
        if (stato.evento != null) vm.consumaEvento()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diario_titolo)) },
                actions = {
                    IconButton(onClick = { vm.aggiorna() }) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.azione_aggiorna))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                stato.caricamento && stato.regoleVitaReale.isEmpty() &&
                    stato.dichiarazioni.isEmpty() -> Centro {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.diario_caricamento),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = Spazi.s),
                        )
                    }
                }

                stato.configurazioneMancante -> Centro {
                    TestoCentrato(stringResource(R.string.regole_config_mancante))
                }

                else -> ContenutoDiario(
                    regole = stato.regoleVitaReale,
                    dichiarazioni = stato.tutte,
                    fuso = stato.fuso,
                    mostraErrore = stato.errore,
                    datiFermiAlle = stato.datiFermiAlle,
                    onDichiara = { regola, esito -> dichiarazioneInCorso = regola to esito },
                )
            }
        }
    }

    dichiarazioneInCorso?.let { (regola, esito) ->
        val oggiIso = LocalDate.now(zonaPatto(stato.fuso)).toString()
        // I giorni già dichiarati su QUESTA regola: restano non selezionabili nel
        // dialogo (il server li rifiuterebbe con gia_dichiarato).
        val giorniDichiarati = stato.tutte
            .filter { it.regolaId == regola.id }
            .map { it.giorno }
            .toSet()
        DialogoDichiarazione(
            regola = regola,
            esito = esito,
            oggiIso = oggiIso,
            giorniDichiarati = giorniDichiarati,
            invioInCorso = stato.invioInCorso,
            onAnnulla = { dichiarazioneInCorso = null },
            onConferma = { nota, giorno ->
                vm.dichiara(regola.id, esito, nota, giorno)
                // "Ce l'ho fatta": il riconoscimento è subito, il dialogo si
                // chiude senza aspettare il server. "Non ce l'ho fatta" resta
                // com'era: si chiude quando il server ha registrato.
                if (esito == EsitiDichiarazione.SUCCESSO) dichiarazioneInCorso = null
            },
        )
    }
}

@Composable
private fun ContenutoDiario(
    regole: List<Regola>,
    dichiarazioni: List<Dichiarazione>,
    fuso: String?,
    mostraErrore: Boolean,
    datiFermiAlle: Long?,
    onDichiara: (Regola, String) -> Unit,
) {
    // "Oggi" nel fuso del patto, come lo assegna il server alle dichiarazioni:
    // col fuso del telefono, vicino a mezzanotte, il figlio vedrebbe libero un
    // giorno che il server considera già dichiarato (o viceversa).
    val oggi = LocalDate.now(zonaPatto(fuso)).toString()
    val inAttesa = dichiarazioni.filter { it.stato == StatiDichiarazione.IN_ATTESA }
    val risolte = dichiarazioni.filter { it.stato != StatiDichiarazione.IN_ATTESA }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spazi.l + Spazi.xs),
        verticalArrangement = Arrangement.spacedBy(Spazi.l),
    ) {
        if (mostraErrore) {
            item { BannerDatiVecchi(datiFermiAlle) }
        }

        item { TitoloSezione(stringResource(R.string.diario_sezione_regole)) }
        if (regole.isEmpty()) {
            item { RigaVuota(Icons.Outlined.Info, stringResource(R.string.diario_regole_vuoto)) }
        } else {
            items(regole, key = { "regola-${it.id}" }) { regola ->
                val diOggi = dichiarazioni.firstOrNull { it.regolaId == regola.id && it.giorno == oggi }
                CardRegolaVitaReale(regola, diOggi, onDichiara)
            }
        }

        item { TitoloSezione(stringResource(R.string.diario_sezione_dichiarazioni)) }
        if (dichiarazioni.isEmpty()) {
            item { RigaVuota(Icons.Outlined.Info, stringResource(R.string.diario_dichiarazioni_vuoto)) }
        } else {
            // In attesa della firma: già riconosciute, in evidenza.
            items(inAttesa, key = { "attesa-${it.id}" }) { dichiarazione ->
                CardFatto(
                    regola = regole.firstOrNull { it.id == dichiarazione.regolaId },
                    dichiarazione = dichiarazione,
                )
            }
            // Risolte: righe con divisore, non card. Nessun contatore.
            if (risolte.isNotEmpty()) {
                item {
                    Column {
                        risolte.forEachIndexed { indice, dichiarazione ->
                            if (indice > 0) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                            RigaDichiarazione(
                                dichiarazione = dichiarazione,
                                regola = regole.firstOrNull { it.id == dichiarazione.regolaId },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * La regola di vita reale com'è oggi: da dichiarare, già fatta (e allora si
 * vede subito, piena), o già dichiarata in un altro modo.
 */
@Composable
private fun CardRegolaVitaReale(
    regola: Regola,
    diOggi: Dichiarazione?,
    onDichiara: (Regola, String) -> Unit,
) {
    if (diOggi != null && diOggi.esito == EsitiDichiarazione.SUCCESSO && diOggi.stato in STATI_FATTO) {
        CardFatto(regola = regola, dichiarazione = diOggi)
        return
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spazi.l + Spazi.xs)) {
            Text(
                text = descrizioneRegola(regola.tipo, regola.parametri),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (diOggi != null) {
                Text(
                    text = stringResource(R.string.diario_gia_dichiarato_oggi),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spazi.xs),
                )
            } else {
                Spacer(modifier = Modifier.height(Spazi.s))
                Row {
                    FilledTonalButton(
                        onClick = { onDichiara(regola, EsitiDichiarazione.SUCCESSO) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.dichiara_successo))
                    }
                    Spacer(modifier = Modifier.width(Spazi.s))
                    OutlinedButton(
                        onClick = { onDichiara(regola, EsitiDichiarazione.FALLIMENTO) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.dichiara_fallimento))
                    }
                }
            }
        }
    }
}

/**
 * Il successo riconosciuto (B7): spunta, `primaryContainer` pieno e "L'hai
 * fatto. Manca la firma di [arbitro]". La firma convalida verso il genitore;
 * il riconoscimento verso il figlio arriva adesso.
 */
@Composable
private fun CardFatto(regola: Regola?, dichiarazione: Dichiarazione) {
    val registro = dichiarazione.verdetto?.registro?.takeIf { it.isNotBlank() }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(Spazi.l + Spazi.xs),
            verticalArrangement = Arrangement.spacedBy(Spazi.xs),
        ) {
            regola?.let {
                Text(
                    text = descrizioneRegola(it.tipo, it.parametri),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spazi.s),
            ) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Text(
                    text = registro ?: descrizioneStato(dichiarazione, arbitroDi(regola)),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (dichiarazione.giorno.isNotBlank()) {
                Text(
                    text = stringResource(R.string.dichiarazione_giorno, dichiarazione.giorno),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            dichiarazione.nota?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = stringResource(R.string.dichiarazione_tua_nota, it),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** Una dichiarazione risolta: una riga, col fatto congelato dal server. */
@Composable
private fun RigaDichiarazione(dichiarazione: Dichiarazione, regola: Regola?) {
    // (v2.1) La frase del verdetto la congela il server (cita l'arbitro di
    // allora): si mostra QUELLA verbatim, non la si ricostruisce dai parametri
    // attuali della regola — che nel frattempo può aver cambiato arbitro o
    // essere stata eliminata. Se manca (fallimento registrato, che non passa da
    // un verdetto) si ripiega sul racconto locale.
    val registro = dichiarazione.verdetto?.registro?.takeIf { it.isNotBlank() }
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spazi.m),
        verticalArrangement = Arrangement.spacedBy(Spazi.xs),
    ) {
        regola?.let {
            Text(
                text = descrizioneRegola(it.tipo, it.parametri),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Text(
            text = registro ?: descrizioneStato(dichiarazione, arbitroDi(regola)),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (dichiarazione.giorno.isNotBlank()) {
            Text(
                text = stringResource(R.string.dichiarazione_giorno, dichiarazione.giorno),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        dichiarazione.nota?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = stringResource(R.string.dichiarazione_tua_nota, it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        dichiarazione.verdetto?.nota?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = stringResource(R.string.dichiarazione_verdetto_nota, it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Gli stati in cui un successo è "fatto": in attesa della firma o già firmato. */
private val STATI_FATTO = setOf(
    StatiDichiarazione.IN_ATTESA,
    StatiDichiarazione.CONFERMATA,
    StatiDichiarazione.CONFERMATA_PER_CONTO,
)

private fun arbitroDi(regola: Regola?): String =
    regola?.let { parametroTesto(it.parametri, "arbitro_nome") } ?: "?"

/** Lo stato raccontato dal punto di vista del figlio. */
@Composable
private fun descrizioneStato(dichiarazione: Dichiarazione, arbitro: String): String =
    when (dichiarazione.stato) {
        StatiDichiarazione.IN_ATTESA ->
            stringResource(R.string.dichiarazione_stato_in_attesa, arbitro)
        StatiDichiarazione.REGISTRATA -> stringResource(R.string.dichiarazione_stato_registrata)
        StatiDichiarazione.CONFERMATA -> stringResource(R.string.dichiarazione_stato_confermata)
        StatiDichiarazione.CONFERMATA_PER_CONTO ->
            stringResource(R.string.dichiarazione_stato_confermata_per_conto, arbitro)
        StatiDichiarazione.RIBALTATA -> stringResource(R.string.dichiarazione_stato_ribaltata)
        else -> dichiarazione.stato
    }

@Composable
private fun DialogoDichiarazione(
    regola: Regola,
    esito: String,
    oggiIso: String,
    giorniDichiarati: Set<String>,
    invioInCorso: Boolean,
    onAnnulla: () -> Unit,
    onConferma: (String?, String?) -> Unit,
) {
    var nota by remember(regola.id, esito) { mutableStateOf("") }
    // Giorno per cui si dichiara: default oggi. Il contratto permette oggi ↔ −7gg
    // (fuso del patto): "ieri ho camminato ma ho scordato di segnarlo" si può.
    var giornoScelto by rememberSaveable(regola.id, esito) { mutableStateOf(oggiIso) }

    AlertDialog(
        onDismissRequest = onAnnulla,
        title = {
            Text(
                stringResource(
                    if (esito == EsitiDichiarazione.SUCCESSO) {
                        R.string.dichiarazione_conferma_successo_titolo
                    } else {
                        R.string.dichiarazione_conferma_fallimento_titolo
                    },
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = descrizioneRegola(regola.tipo, regola.parametri),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        if (esito == EsitiDichiarazione.SUCCESSO) {
                            R.string.dichiarazione_conferma_successo_testo
                        } else {
                            R.string.dichiarazione_conferma_fallimento_testo
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
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
                enabled = !invioInCorso,
                // Oggi è il default del server: si passa null per non forzare il
                // campo quando non serve; un giorno passato viaggia esplicito.
                onClick = {
                    onConferma(
                        nota.trim().ifBlank { null },
                        giornoScelto.takeIf { it != oggiIso },
                    )
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
 * — il server li rifiuterebbe con `gia_dichiarato`. Oggi è sempre selezionabile
 * (la card apre il dialogo solo se oggi è ancora libero).
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.dichiarazione_scegli_giorno),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (indietro in 0..7) {
                val giorno = oggi.minusDays(indietro.toLong())
                val iso = giorno.toString()
                FilterChip(
                    selected = iso == giornoScelto,
                    onClick = { onGiorno(iso) },
                    enabled = iso !in giorniDichiarati,
                    label = { Text(etichettaGiorno(indietro, giorno)) },
                )
            }
        }
    }
}

private val FORMATO_GIORNO_BREVE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")

@Composable
private fun etichettaGiorno(indietro: Int, giorno: LocalDate): String = when (indietro) {
    0 -> stringResource(R.string.dichiarazione_giorno_oggi)
    1 -> stringResource(R.string.dichiarazione_giorno_ieri)
    else -> giorno.format(FORMATO_GIORNO_BREVE)
}
