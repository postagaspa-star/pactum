package eu.stgm.pactum.figlio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.design.Caricamento
import eu.stgm.pactum.design.CardEvidenza
import eu.stgm.pactum.design.CardNormale
import eu.stgm.pactum.design.SchermataColorata
import eu.stgm.pactum.design.Sezione
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.design.TitoloBarra
import eu.stgm.pactum.design.TitoloSezione
import eu.stgm.pactum.design.Tono
import eu.stgm.pactum.design.coloriBarra
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.bilancio.EsitiPromessa
import eu.stgm.pactum.figlio.bilancio.LogicaBilancio
import eu.stgm.pactum.figlio.bilancio.LogicaBilancio.NuovaPromessa
import eu.stgm.pactum.figlio.bilancio.LogicaBilancio.Problema
import eu.stgm.pactum.figlio.bilancio.Promessa
import eu.stgm.pactum.figlio.bilancio.PromesseMisurabili
import eu.stgm.pactum.figlio.bilancio.PromesseMisurabili.Misura
import eu.stgm.pactum.figlio.bilancio.Voti
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * (0.24) Il bilancio della sera. Dall'alto: le promesse che aspettano una risposta
 * (Fatto · Non fatto · Rimando), quelle che il telefono controlla, quelle che non
 * riescono da tre volte; poi "Com'è andata oggi?" (Bene salva subito; Così così e
 * Male chiedono cosa non ha funzionato e una promessa, con un giorno e un'ora, o
 * una regola di un giorno che controlla il telefono); in fondo gli ultimi giorni.
 * Niente di questo va ai genitori, e la pagina lo dice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BilancioScreen(onChiudi: () -> Unit, vm: BilancioViewModel = viewModel()) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        vm.aggiorna()
        onPauseOrDispose { }
    }
    SchermataColorata(Sezione.OGGI) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { TitoloBarra(stringResource(R.string.bilancio_titolo)) },
                    colors = coloriBarra(),
                    navigationIcon = {
                        IconButton(onClick = onChiudi) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.azione_indietro))
                        }
                    },
                )
            },
        ) { padding ->
            if (stato.caricamento) {
                Caricamento(modifier = Modifier.padding(padding))
                return@Scaffold
            }
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(Spazi.l),
                verticalArrangement = Arrangement.spacedBy(Spazi.m),
            ) {
                PromesseDaDire(stato, vm)
                PromesseMisurate(stato)
                stato.daCambiare.forEach { testo ->
                    CardEvidenza(tono = Tono.Attenzione) {
                        Text(stringResource(R.string.bilancio_da_cambiare_titolo), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.bilancio_da_cambiare, testo),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = Spazi.xs),
                        )
                    }
                }
                Serata(stato, vm)
                Storia(stato)
                Text(
                    text = stringResource(R.string.bilancio_privato),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// --- Le promesse che aspettano ---------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PromesseDaDire(stato: BilancioViewModel.StatoBilancio, vm: BilancioViewModel) {
    if (stato.daRispondere.isEmpty()) return
    var daRimandare by remember { mutableStateOf<Promessa?>(null) }
    TitoloSezione(stringResource(R.string.bilancio_promesse_titolo))
    stato.daRispondere.forEach { promessa ->
        CardNormale {
            Text(stringResource(R.string.bilancio_avevi_detto, promessa.testo), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = quandoPromessa(promessa, stato.oggi) + rimandi(promessa),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spazi.xs),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spazi.s),
                modifier = Modifier.padding(top = Spazi.s),
            ) {
                Button(onClick = { vm.rispondi(promessa.id, EsitiPromessa.FATTA) }) { Text(stringResource(R.string.promessa_fatta)) }
                OutlinedButton(onClick = { vm.rispondi(promessa.id, EsitiPromessa.NON_FATTA) }) {
                    Text(stringResource(R.string.promessa_non_fatta))
                }
                TextButton(onClick = { daRimandare = promessa }) { Text(stringResource(R.string.promessa_rimando)) }
            }
        }
    }
    daRimandare?.let { promessa ->
        DialogoQuando(
            oggi = stato.oggi,
            daDomani = false,
            onAnnulla = { daRimandare = null },
            onScegli = { giorno, ora ->
                daRimandare = null
                vm.rimanda(promessa.id, giorno, ora)
            },
        )
    }
}

/** Le promesse che controlla il telefono, di oggi e di ieri: cosa dice il suo semaforo. */
@Composable
private fun PromesseMisurate(stato: BilancioViewModel.StatoBilancio) {
    if (stato.misurate.isEmpty()) return
    TitoloSezione(stringResource(R.string.bilancio_controlla_titolo))
    stato.misurate.forEach { promessa ->
        val esito = LogicaBilancio.esito(promessa, stato.regole, stato.oggi)
        CardNormale {
            Text(promessa.testo, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = nomeGiorno(LocalDate.parse(promessa.giorno), stato.oggi).replaceFirstChar { it.titlecase(Locale.ITALIAN) } +
                    " · " + testoEsito(esito ?: ESITO_IN_CORSO),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spazi.xs),
            )
        }
    }
}

// --- Com'è andata --------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Serata(stato: BilancioViewModel.StatoBilancio, vm: BilancioViewModel) {
    val bilancio = stato.bilancio
    var modifica by rememberSaveable(stato.giorno) { mutableStateOf(false) }
    LaunchedEffect(stato.salvato) { if (stato.salvato) modifica = false }

    TitoloSezione(
        stringResource(if (stato.giorno == stato.oggi) R.string.bilancio_domanda_oggi else R.string.bilancio_domanda_ieri),
    )
    if (bilancio != null && !modifica) {
        CardNormale {
            Text(stringResource(R.string.bilancio_fatto_titolo), style = MaterialTheme.typography.labelLarge)
            Text(testoVoto(bilancio.voto), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Spazi.xs))
            bilancio.cosaNonHaFunzionato?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Spazi.xs))
            }
            stato.promessa?.let { p ->
                Text(
                    stringResource(R.string.bilancio_promessa_riga, p.testo) + " · " + quandoPromessa(p, stato.oggi),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = Spazi.xs),
                )
            }
            if (stato.salvato) {
                Text(
                    stringResource(R.string.bilancio_salvato),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = Spazi.s),
                )
            }
            // Una promessa che il telefono controlla non si riscrive: la regola c'è già.
            if (stato.promessa?.regolaId == null) {
                TextButton(onClick = { vm.dimenticaSalvato(); modifica = true }) { Text(stringResource(R.string.bilancio_cambia)) }
            }
        }
        return
    }
    ModuloBilancio(stato, vm)
}

/** Il modulo del bilancio. Le scelte sopravvivono a una rotazione (rememberSaveable). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModuloBilancio(stato: BilancioViewModel.StatoBilancio, vm: BilancioViewModel) {
    val context = LocalContext.current
    val giorno = stato.giorno
    var voto by rememberSaveable(giorno) { mutableStateOf(stato.bilancio?.voto) }
    var cosa by rememberSaveable(giorno) { mutableStateOf(stato.bilancio?.cosaNonHaFunzionato.orEmpty()) }
    var conTelefono by rememberSaveable(giorno) { mutableStateOf(false) }
    var testo by rememberSaveable(giorno) { mutableStateOf(stato.promessa?.testo.orEmpty()) }
    var traGiorni by rememberSaveable(giorno) { mutableIntStateOf(1) }
    // Minuti del giorno, -1 = nessun orario.
    var minutiOra by rememberSaveable(giorno) { mutableIntStateOf(-1) }
    var sceltaOra by remember { mutableStateOf(false) }
    // La promessa misurabile: limite (bersaglio + minuti) o fascia (dalle-alle).
    var eFascia by rememberSaveable(giorno) { mutableStateOf(false) }
    var bersaglio by rememberSaveable(giorno) { mutableStateOf(PromesseMisurabili.BERSAGLI[1]) }
    var minutiLimite by rememberSaveable(giorno) { mutableIntStateOf(60) }
    var dalle by rememberSaveable(giorno) { mutableIntStateOf(15 * 60) }
    var alle by rememberSaveable(giorno) { mutableIntStateOf(17 * 60) }
    var sceltaFascia by remember { mutableStateOf<String?>(null) }
    var tentato by rememberSaveable(giorno) { mutableStateOf(false) }

    // Le tre risposte: "Bene" salva subito, un tocco.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spazi.s)) {
        Voti.TUTTI.forEach { v ->
            FilterChip(
                selected = voto == v,
                onClick = {
                    voto = v
                    if (v == Voti.BENE) vm.salva(Voti.BENE, null, null)
                },
                label = { Text(testoVoto(v)) },
            )
        }
    }
    if (voto == null || voto == Voti.BENE) return

    val adesso = LocalDateTime.now()
    val misura: Misura? = if (!conTelefono) {
        null
    } else if (eFascia) {
        Misura.Fascia(LocalTime.of(dalle / 60, dalle % 60), LocalTime.of(alle / 60, alle % 60))
    } else {
        Misura.Limite(bersaglio, minutiLimite)
    }
    val giornoPromessa = giorno.plusDays(if (conTelefono) 1L else traGiorni.toLong())
    val testoMisura = misura?.let {
        descrizioneRegola(context, PromesseMisurabili.tipo(it), PromesseMisurabili.parametri(it, giornoPromessa))
    }
    val nuova = NuovaPromessa(
        testo = testoMisura ?: testo,
        giorno = giornoPromessa,
        ora = if (conTelefono || minutiOra < 0) null else LocalTime.of(minutiOra / 60, minutiOra % 60),
    )
    val problema = LogicaBilancio.problema(voto!!, cosa, nuova, adesso)
        ?: if (misura != null && !PromesseMisurabili.valida(misura)) Problema.ORA_PASSATA else null

    OutlinedTextField(
        value = cosa,
        onValueChange = { cosa = it.take(LogicaBilancio.MAX_CARATTERI) },
        label = { Text(stringResource(R.string.bilancio_cosa)) },
        placeholder = { Text(stringResource(R.string.bilancio_cosa_esempio)) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
    )

    Text(stringResource(R.string.bilancio_promessa_titolo), style = MaterialTheme.typography.titleSmall)
    if (stato.misurabiliPossibili) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spazi.s)) {
            FilterChip(selected = !conTelefono, onClick = { conTelefono = false }, label = { Text(stringResource(R.string.bilancio_la_segno_io)) })
            FilterChip(selected = conTelefono, onClick = { conTelefono = true }, label = { Text(stringResource(R.string.bilancio_la_controlla_il_telefono)) })
        }
    }

    if (!conTelefono) {
        OutlinedTextField(
            value = testo,
            onValueChange = { testo = it.take(LogicaBilancio.MAX_CARATTERI) },
            placeholder = { Text(stringResource(R.string.bilancio_promessa_esempio)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        if (testo.length >= LogicaBilancio.MIN_CARATTERI &&
            LogicaBilancio.eDaCambiare(testo, stato.diario, stato.regole, stato.oggi)
        ) {
            Text(stringResource(R.string.bilancio_ripetuta), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Text(stringResource(R.string.bilancio_quando), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spazi.s)) {
            FilterChip(selected = traGiorni == 1, onClick = { traGiorni = 1 }, label = { Text(stringResource(R.string.bilancio_domani)) })
            FilterChip(selected = traGiorni == 2, onClick = { traGiorni = 2 }, label = { Text(stringResource(R.string.bilancio_dopodomani)) })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spazi.s)) {
            FilterChip(selected = minutiOra < 0, onClick = { minutiOra = -1 }, label = { Text(stringResource(R.string.bilancio_nessun_orario)) })
            FilterChip(
                selected = minutiOra >= 0,
                onClick = { sceltaOra = true },
                label = {
                    Text(
                        if (minutiOra >= 0) stringResource(R.string.bilancio_alle, oraDaMinuti(minutiOra))
                        else stringResource(R.string.bilancio_scegli_ora),
                    )
                },
            )
        }
        Text(stringResource(R.string.bilancio_ora_spiegazione), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spazi.s)) {
            FilterChip(selected = !eFascia, onClick = { eFascia = false }, label = { Text(stringResource(R.string.bilancio_misura_limite)) })
            FilterChip(selected = eFascia, onClick = { eFascia = true }, label = { Text(stringResource(R.string.bilancio_misura_fascia)) })
        }
        if (!eFascia) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spazi.s)) {
                PromesseMisurabili.MINUTI.forEach { m ->
                    FilterChip(selected = minutiLimite == m, onClick = { minutiLimite = m }, label = { Text(testoDurata(context, m.toLong())) })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spazi.s)) {
                PromesseMisurabili.BERSAGLI.forEach { chiave ->
                    FilterChip(selected = bersaglio == chiave, onClick = { bersaglio = chiave }, label = { Text(etichettaChiave(context, chiave)) })
                }
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spazi.s)) {
                OutlinedButton(onClick = { sceltaFascia = "dalle" }) { Text(stringResource(R.string.bilancio_misura_dalle, oraDaMinuti(dalle))) }
                OutlinedButton(onClick = { sceltaFascia = "alle" }) { Text(stringResource(R.string.bilancio_misura_alle, oraDaMinuti(alle))) }
            }
            if (dalle >= alle) {
                Text(stringResource(R.string.bilancio_misura_fascia_non_valida), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        testoMisura?.let {
            Text(
                stringResource(R.string.bilancio_domani) + ": " + it,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Text(stringResource(R.string.bilancio_misura_spiegazione), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (!stato.misurabiliPossibili) {
        Text(stringResource(R.string.bilancio_misura_server_vecchio), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (tentato && problema != null) {
        Text(testoProblema(problema, conTelefono), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    stato.errore?.let {
        Text(
            stringResource(if (it == BilancioViewModel.Errore.RETE) R.string.bilancio_errore_rete else R.string.bilancio_errore_rifiutata),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Button(
        enabled = !stato.invio,
        onClick = {
            tentato = true
            if (problema == null) vm.salva(voto!!, cosa, nuova, misura, testoMisura)
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.bilancio_salva)) }

    if (sceltaOra) {
        DialogoOra(
            minuti = if (minutiOra >= 0) minutiOra else 15 * 60 + 30,
            titolo = stringResource(R.string.bilancio_scegli_ora),
            onAnnulla = { sceltaOra = false },
            onScegli = {
                sceltaOra = false
                minutiOra = it
            },
        )
    }
    sceltaFascia?.let { quale ->
        DialogoOra(
            minuti = if (quale == "dalle") dalle else alle,
            titolo = stringResource(R.string.bilancio_scegli_ora),
            onAnnulla = { sceltaFascia = null },
            onScegli = {
                sceltaFascia = null
                if (quale == "dalle") dalle = it else alle = it
            },
        )
    }
}

/** "Rimando": domani o dopodomani (o più tardi oggi), con o senza un'ora. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DialogoQuando(
    oggi: LocalDate,
    daDomani: Boolean,
    onAnnulla: () -> Unit,
    onScegli: (LocalDate, LocalTime?) -> Unit,
) {
    var traGiorni by remember { mutableIntStateOf(if (daDomani) 1 else 0) }
    var minuti by remember { mutableIntStateOf(-1) }
    var sceltaOra by remember { mutableStateOf(false) }
    val adesso = LocalDateTime.now()
    val giorno = oggi.plusDays(traGiorni.toLong())
    val ora = if (minuti >= 0) LocalTime.of(minuti / 60, minuti % 60) else null
    val valida = ora == null || LocalDateTime.of(giorno, ora).isAfter(adesso)
    AlertDialog(
        onDismissRequest = onAnnulla,
        title = { Text(stringResource(R.string.bilancio_rimando_titolo)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spazi.s)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spazi.s)) {
                    if (!daDomani) {
                        FilterChip(selected = traGiorni == 0, onClick = { traGiorni = 0 }, label = { Text(stringResource(R.string.giorno_oggi).replaceFirstChar { it.titlecase(Locale.ITALIAN) }) })
                    }
                    FilterChip(selected = traGiorni == 1, onClick = { traGiorni = 1 }, label = { Text(stringResource(R.string.bilancio_domani)) })
                    FilterChip(selected = traGiorni == 2, onClick = { traGiorni = 2 }, label = { Text(stringResource(R.string.bilancio_dopodomani)) })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spazi.s)) {
                    FilterChip(selected = minuti < 0, onClick = { minuti = -1 }, label = { Text(stringResource(R.string.bilancio_nessun_orario)) })
                    FilterChip(
                        selected = minuti >= 0,
                        onClick = { sceltaOra = true },
                        label = { Text(if (minuti >= 0) stringResource(R.string.bilancio_alle, oraDaMinuti(minuti)) else stringResource(R.string.bilancio_scegli_ora)) },
                    )
                }
                if (!valida) Text(stringResource(R.string.bilancio_errore_ora), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { Button(enabled = valida, onClick = { onScegli(giorno, ora) }) { Text(stringResource(R.string.azione_conferma)) } },
        dismissButton = { TextButton(onClick = onAnnulla) { Text(stringResource(R.string.azione_annulla)) } },
    )
    if (sceltaOra) {
        DialogoOra(
            minuti = if (minuti >= 0) minuti else adesso.hour * 60 + adesso.minute,
            titolo = stringResource(R.string.bilancio_scegli_ora),
            onAnnulla = { sceltaOra = false },
            onScegli = {
                sceltaOra = false
                minuti = it
            },
        )
    }
}

// --- Gli ultimi giorni ---------------------------------------------------------------

@Composable
private fun Storia(stato: BilancioViewModel.StatoBilancio) {
    if (stato.storia.none { it.voto != null }) return
    TitoloSezione(stringResource(R.string.bilancio_storia_titolo))
    CardNormale {
        Column(verticalArrangement = Arrangement.spacedBy(Spazi.s)) {
            stato.storia.forEach { voce ->
                val giorno = nomeGiorno(voce.giorno, stato.oggi).replaceFirstChar { it.titlecase(Locale.ITALIAN) }
                val riga = if (voce.voto == null) {
                    stringResource(R.string.bilancio_storia_riga, giorno, stringResource(R.string.bilancio_storia_nessuno))
                } else {
                    val promessa = voce.promessa?.let { " · «${it.testo}» → " + testoEsito(voce.esito ?: ESITO_DA_DIRE) }.orEmpty()
                    stringResource(R.string.bilancio_storia_riga, giorno, testoVoto(voce.voto)) + promessa
                }
                Text(
                    riga,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (voce.voto == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

// --- Le parole -----------------------------------------------------------------------

private const val ESITO_IN_CORSO = "in_corso"
private const val ESITO_DA_DIRE = "da_dire"

private val FORMATO_GIORNO = DateTimeFormatter.ofPattern("EEE d/M", Locale.ITALIAN)

/** "oggi", "ieri", "domani", "dopodomani", altrimenti "mer 14/10". */
@Composable
private fun nomeGiorno(giorno: LocalDate, oggi: LocalDate): String = when (giorno) {
    oggi -> stringResource(R.string.giorno_oggi)
    oggi.minusDays(1) -> stringResource(R.string.giorno_ieri)
    oggi.plusDays(1) -> stringResource(R.string.giorno_domani)
    oggi.plusDays(2) -> stringResource(R.string.giorno_dopodomani)
    else -> giorno.format(FORMATO_GIORNO)
}

@Composable
private fun quandoPromessa(promessa: Promessa, oggi: LocalDate): String {
    val giorno = runCatching { LocalDate.parse(promessa.giorno) }.getOrNull() ?: return promessa.giorno
    val nome = nomeGiorno(giorno, oggi).replaceFirstChar { it.titlecase(Locale.ITALIAN) }
    return promessa.ora?.let { stringResource(R.string.bilancio_quando_ora, nome, it) }
        ?: stringResource(R.string.bilancio_quando_senza_ora, nome)
}

@Composable
private fun rimandi(promessa: Promessa): String =
    if (promessa.rimandi == 0) "" else " · " + pluralStringResource(R.plurals.bilancio_rimandata, promessa.rimandi, promessa.rimandi)

@Composable
private fun testoVoto(voto: String): String = stringResource(
    when (voto) {
        Voti.BENE -> R.string.voto_bene
        Voti.COSI_COSI -> R.string.voto_cosi_cosi
        else -> R.string.voto_male
    },
)

@Composable
private fun testoEsito(esito: String): String = stringResource(
    when (esito) {
        EsitiPromessa.FATTA -> R.string.esito_fatta
        EsitiPromessa.NON_FATTA -> R.string.esito_non_fatta
        EsitiPromessa.SENZA_DATI -> R.string.esito_senza_dati
        ESITO_IN_CORSO -> R.string.esito_in_corso
        else -> R.string.esito_da_dire
    },
)

@Composable
private fun testoProblema(problema: Problema, conTelefono: Boolean): String = stringResource(
    when (problema) {
        Problema.COSA_MANCANTE -> R.string.bilancio_errore_cosa
        Problema.PROMESSA_MANCANTE -> R.string.bilancio_errore_promessa
        Problema.TROPPO_LUNGO -> R.string.bilancio_errore_lungo
        Problema.GIORNO_NON_VALIDO -> R.string.bilancio_errore_giorno
        Problema.ORA_PASSATA -> if (conTelefono) R.string.bilancio_misura_fascia_non_valida else R.string.bilancio_errore_ora
    },
)
