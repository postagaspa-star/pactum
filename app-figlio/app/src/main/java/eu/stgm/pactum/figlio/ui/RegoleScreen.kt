package eu.stgm.pactum.figlio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.design.GiornoPatto
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.design.StrisciaGiorni
import eu.stgm.pactum.design.contaGiorni
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.catalogo.AppInstallata
import eu.stgm.pactum.figlio.catalogo.CatalogoApp
import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.TipiRegola
import eu.stgm.pactum.figlio.dati.inGiorniPatto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant

/** Le regole del patto: le scrive il figlio, il server le custodisce. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegoleScreen(vm: RegoleViewModel = viewModel()) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // null = nessun dialogo; CREA senza regola; MODIFICA con la regola.
    var dialogoAperto by remember { mutableStateOf<DialogoRegole?>(null) }
    var regolaDaEliminare by remember { mutableStateOf<Regola?>(null) }

    LifecycleResumeEffect(Unit) {
        vm.aggiorna()
        onPauseOrDispose { }
    }

    val messaggioSalvata = stringResource(R.string.regola_salvata)
    val messaggioEliminata = stringResource(R.string.regola_eliminata_ok)
    val messaggioUltima = stringResource(R.string.regola_ultima_messaggio)
    val messaggioErrore = stringResource(R.string.regola_errore_generico)
    LaunchedEffect(stato.evento) {
        when (val evento = stato.evento) {
            is RegoleViewModel.Evento.Salvata -> {
                dialogoAperto = null
                snackbarHostState.showSnackbar(messaggioSalvata)
            }
            is RegoleViewModel.Evento.Eliminata -> {
                regolaDaEliminare = null
                snackbarHostState.showSnackbar(messaggioEliminata)
            }
            is RegoleViewModel.Evento.LockAttivo -> {
                regolaDaEliminare = null
                val attesa = testoAttesa(context, evento.secondiRimanenti)
                val messaggio = if (evento.perEliminazione) {
                    context.getString(R.string.regola_elimina_lock_messaggio, attesa)
                } else {
                    context.getString(R.string.regola_lock_messaggio, attesa)
                }
                snackbarHostState.showSnackbar(messaggio)
            }
            is RegoleViewModel.Evento.UltimaRegola -> {
                regolaDaEliminare = null
                snackbarHostState.showSnackbar(messaggioUltima)
            }
            is RegoleViewModel.Evento.Errore -> snackbarHostState.showSnackbar(messaggioErrore)
            null -> Unit
        }
        if (stato.evento != null) vm.consumaEvento()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.regole_titolo)) },
                actions = {
                    IconButton(onClick = { vm.aggiorna() }) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.azione_aggiorna))
                    }
                },
            )
        },
        floatingActionButton = {
            if (!stato.configurazioneMancante) {
                FloatingActionButton(onClick = { dialogoAperto = DialogoRegole(null) }) {
                    Icon(Icons.Filled.Add, stringResource(R.string.regole_nuova))
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                stato.caricamento && stato.regole.isEmpty() -> Centro {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.regole_caricamento),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = Spazi.s),
                        )
                    }
                }

                stato.configurazioneMancante -> Centro {
                    TestoCentrato(stringResource(R.string.regole_config_mancante))
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // Densità del figlio: 20 attorno, 16 tra i blocchi; in fondo
                    // lo spazio del pulsante + perché non copra l'ultima regola.
                    contentPadding = PaddingValues(
                        start = Spazi.l + Spazi.xs,
                        end = Spazi.l + Spazi.xs,
                        top = Spazi.l + Spazi.xs,
                        bottom = 88.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spazi.l),
                ) {
                    if (stato.errore) {
                        item { BannerDatiVecchi(stato.datiFermiAlle) }
                    }
                    if (stato.regole.isEmpty()) {
                        // (v3) Il figlio può avere regole solo sul computer: il patto
                        // c'è, su questo telefono no.
                        val vuoto = if (stato.regoleAltrove > 0) {
                            R.string.regole_vuoto_questo_telefono
                        } else {
                            R.string.regole_vuoto
                        }
                        item { RigaVuota(Icons.Outlined.Info, stringResource(vuoto)) }
                    } else {
                        // "L'ultima non si toglie" vale per il figlio, su tutti i
                        // suoi dispositivi (contratto v3): conta anche il computer.
                        if (stato.totaleFiglio == 1) {
                            item {
                                RigaVuota(Icons.Outlined.Info, stringResource(R.string.regole_unica_regola))
                            }
                        }
                        items(stato.regole, key = { it.id }) { regola ->
                            CardRegola(
                                regola = regola,
                                concordata = regola.id in stato.concordate,
                                onModifica = { dialogoAperto = DialogoRegole(regola) },
                                onElimina = { regolaDaEliminare = regola },
                            )
                        }
                    }
                    // (v3) Qui ci sono le regole di questo telefono e la vita reale;
                    // quelle degli altri dispositivi si vedono e si cambiano da lì.
                    if (stato.regoleAltrove > 0) {
                        item {
                            RigaVuota(
                                Icons.Outlined.Info,
                                pluralStringResource(
                                    R.plurals.regole_altri_dispositivi,
                                    stato.regoleAltrove,
                                    stato.regoleAltrove,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    dialogoAperto?.let { dialogo ->
        DialogoRegola(
            regola = dialogo.regola,
            invioInCorso = stato.invioInCorso,
            onAnnulla = { dialogoAperto = null },
            onSalva = { tipo, parametri ->
                val regola = dialogo.regola
                if (regola == null) vm.crea(tipo, parametri) else vm.modifica(regola.id, parametri)
            },
        )
    }

    regolaDaEliminare?.let { regola ->
        AlertDialog(
            onDismissRequest = { regolaDaEliminare = null },
            title = { Text(stringResource(R.string.regola_elimina_conferma_titolo)) },
            text = {
                Text(
                    stringResource(
                        R.string.regola_elimina_conferma_testo,
                        descrizioneRegola(regola.tipo, regola.parametri),
                    ),
                )
            },
            confirmButton = {
                Button(
                    enabled = !stato.invioInCorso,
                    onClick = { vm.elimina(regola.id) },
                ) {
                    Text(stringResource(R.string.azione_elimina))
                }
            },
            dismissButton = {
                TextButton(onClick = { regolaDaEliminare = null }) {
                    Text(stringResource(R.string.azione_annulla))
                }
            },
        )
    }
}

/** Il dialogo da aprire: regola null = creazione. */
private data class DialogoRegole(val regola: Regola?)

@Composable
private fun CardRegola(
    regola: Regola,
    concordata: Boolean,
    onModifica: () -> Unit,
    onElimina: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spazi.l + Spazi.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = etichettaTipoRegola(regola.tipo),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                if (concordata) Etichetta(stringResource(R.string.regola_concordata))
            }
            Text(
                text = descrizioneRegola(regola.tipo, regola.parametri),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = Spazi.xs),
            )
            // Il lock asimmetrico, in chiaro: quando la regola tornerà allentabile.
            istanteServer(regola.allentabileDal)
                ?.takeIf { it.isAfter(Instant.now()) }
                ?.let {
                    Text(
                        text = stringResource(R.string.regola_allentabile_dal, dataOraLocale(it)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spazi.xs),
                    )
                }
            // (v2.4) Gli 8 giorni di QUESTA regola, la stessa striscia piccola che
            // il genitore vede sulla sua scheda (D3). Server vecchio: niente.
            val giorni = remember(regola.semaforo) { regola.semaforo.inGiorniPatto() }
            if (giorni.isNotEmpty()) {
                Spacer(modifier = Modifier.height(Spazi.m))
                StrisciaGiorni(
                    giorni = giorni,
                    lato = 20.dp,
                    mostraNumero = false,
                    descrizione = descrizioneStrisciaRegola(giorni),
                )
            }
            Row(modifier = Modifier.padding(top = Spazi.xs)) {
                TextButton(onClick = onModifica) {
                    Text(stringResource(R.string.azione_modifica))
                }
                Spacer(modifier = Modifier.width(Spazi.s))
                TextButton(onClick = onElimina) {
                    Text(stringResource(R.string.azione_elimina))
                }
            }
        }
    }
}

/** Quello che TalkBack legge al posto dei quadretti: "6 su 7 giorni dentro questa regola". */
@Composable
private fun descrizioneStrisciaRegola(giorni: List<GiornoPatto>): String {
    val (mantenuti, conDati) = contaGiorni(giorni)
    return if (conDati == 0) {
        stringResource(R.string.oggi_striscia_senza_dati)
    } else {
        pluralStringResource(R.plurals.regola_striscia_frase, conDati, mantenuti, conDati)
    }
}

@Composable
private fun etichettaTipoRegola(tipo: String): String = when (tipo) {
    TipiRegola.LIMITE_TEMPO -> stringResource(R.string.regola_tipo_limite_tempo)
    TipiRegola.FASCIA_ORARIA -> stringResource(R.string.regola_tipo_fascia_oraria)
    TipiRegola.VITA_REALE -> stringResource(R.string.regola_tipo_vita_reale)
    else -> tipo
}

private val ORA_REGEX = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
private val GIORNI = listOf("lun", "mar", "mer", "gio", "ven", "sab", "dom")

/**
 * Creazione o modifica di una regola. In creazione si sceglie il tipo; in
 * modifica il tipo è fisso (il contratto non prevede di cambiarlo). Il
 * pulsante Salva si accende solo con parametri validi: un 422 evitabile è
 * un errore in meno da spiegare.
 */
@Composable
private fun DialogoRegola(
    regola: Regola?,
    invioInCorso: Boolean,
    onAnnulla: () -> Unit,
    onSalva: (String, JsonObject) -> Unit,
) {
    var tipo by remember { mutableStateOf(regola?.tipo ?: TipiRegola.LIMITE_TEMPO) }

    val parametriIniziali = regola?.parametri ?: JsonObject(emptyMap())
    var app by remember { mutableStateOf(parametroTesto(parametriIniziali, "app_o_categoria") ?: "") }
    var minuti by remember {
        mutableStateOf(parametroTesto(parametriIniziali, "minuti_al_giorno") ?: "")
    }
    var dalle by remember { mutableStateOf(parametroTesto(parametriIniziali, "dalle") ?: "") }
    var alle by remember { mutableStateOf(parametroTesto(parametriIniziali, "alle") ?: "") }
    var giorni by remember {
        mutableStateOf(
            (parametriIniziali["giorni"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?.toSet()
                ?: emptySet(),
        )
    }
    var descrizione by remember {
        mutableStateOf(parametroTesto(parametriIniziali, "descrizione") ?: "")
    }
    var arbitro by remember {
        mutableStateOf(parametroTesto(parametriIniziali, "arbitro_nome") ?: "")
    }
    var frequenza by remember {
        mutableStateOf(parametroTesto(parametriIniziali, "frequenza") ?: "")
    }

    val parametri: JsonObject? = when (tipo) {
        TipiRegola.LIMITE_TEMPO -> {
            val n = minuti.trim().toIntOrNull()
            if (app.isBlank() || n == null || n <= 0) {
                null
            } else {
                buildJsonObject {
                    put("app_o_categoria", app.trim())
                    put("minuti_al_giorno", n)
                }
            }
        }
        TipiRegola.FASCIA_ORARIA -> {
            if (!ORA_REGEX.matches(dalle.trim()) || !ORA_REGEX.matches(alle.trim()) ||
                giorni.isEmpty()
            ) {
                null
            } else {
                buildJsonObject {
                    put("dalle", dalle.trim())
                    put("alle", alle.trim())
                    putJsonArray("giorni") {
                        GIORNI.filter { it in giorni }.forEach { add(it) }
                    }
                }
            }
        }
        TipiRegola.VITA_REALE -> {
            if (descrizione.isBlank() || arbitro.isBlank() || frequenza.isBlank()) {
                null
            } else {
                buildJsonObject {
                    put("descrizione", descrizione.trim())
                    put("arbitro_nome", arbitro.trim())
                    put("frequenza", frequenza.trim())
                }
            }
        }
        else -> null
    }

    AlertDialog(
        onDismissRequest = onAnnulla,
        title = {
            Text(
                stringResource(
                    if (regola == null) R.string.regola_crea_titolo else R.string.regola_modifica_titolo,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spazi.m),
            ) {
                if (regola == null) {
                    Column {
                        RigaRadio(
                            selezionato = tipo == TipiRegola.LIMITE_TEMPO,
                            testo = stringResource(R.string.regola_tipo_limite_tempo),
                            onClick = { tipo = TipiRegola.LIMITE_TEMPO },
                        )
                        RigaRadio(
                            selezionato = tipo == TipiRegola.FASCIA_ORARIA,
                            testo = stringResource(R.string.regola_tipo_fascia_oraria),
                            onClick = { tipo = TipiRegola.FASCIA_ORARIA },
                        )
                        RigaRadio(
                            selezionato = tipo == TipiRegola.VITA_REALE,
                            testo = stringResource(R.string.regola_tipo_vita_reale),
                            onClick = { tipo = TipiRegola.VITA_REALE },
                        )
                    }
                }

                when (tipo) {
                    TipiRegola.LIMITE_TEMPO -> {
                        SelettoreAppOCategoria(valore = app, onScegli = { app = it })
                        CampoTesto(
                            minuti, { minuti = it }, R.string.regola_campo_minuti,
                            numerico = true,
                        )
                    }
                    TipiRegola.FASCIA_ORARIA -> {
                        CampoTesto(dalle, { dalle = it }, R.string.regola_campo_dalle)
                        CampoTesto(alle, { alle = it }, R.string.regola_campo_alle)
                        Text(
                            text = stringResource(R.string.regola_campo_giorni),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(Spazi.xs)) {
                            GIORNI.take(4).forEach { giorno ->
                                ChipGiorno(giorno, giorno in giorni) {
                                    giorni = if (giorno in giorni) giorni - giorno else giorni + giorno
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(Spazi.xs)) {
                            GIORNI.drop(4).forEach { giorno ->
                                ChipGiorno(giorno, giorno in giorni) {
                                    giorni = if (giorno in giorni) giorni - giorno else giorni + giorno
                                }
                            }
                        }
                    }
                    TipiRegola.VITA_REALE -> {
                        CampoTesto(descrizione, { descrizione = it }, R.string.regola_campo_descrizione)
                        CampoTesto(arbitro, { arbitro = it }, R.string.regola_campo_arbitro)
                        CampoTesto(frequenza, { frequenza = it }, R.string.regola_campo_frequenza)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = parametri != null && !invioInCorso,
                onClick = { parametri?.let { onSalva(tipo, it) } },
            ) {
                Text(stringResource(R.string.azione_salva))
            }
        },
        dismissButton = {
            TextButton(onClick = onAnnulla) { Text(stringResource(R.string.azione_annulla)) }
        },
    )
}

@Composable
private fun ChipGiorno(giorno: String, selezionato: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selezionato,
        onClick = onClick,
        label = { Text(giorno) },
    )
}

@Composable
private fun RigaRadio(selezionato: Boolean, testo: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selezionato, onClick = onClick)
        Text(text = testo, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CampoTesto(
    valore: String,
    onValore: (String) -> Unit,
    etichetta: Int,
    numerico: Boolean = false,
) {
    OutlinedTextField(
        value = valore,
        onValueChange = onValore,
        label = { Text(stringResource(etichetta)) },
        singleLine = true,
        keyboardOptions = if (numerico) {
            androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number)
        } else {
            androidx.compose.foundation.text.KeyboardOptions.Default
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Il selettore di `app_o_categoria` (contratto v2.1): niente più testo libero
 * (una regola scritta a mano non troverebbe mai un pacchetto e non scatterebbe
 * mai in silenzio). Si sceglie una categoria fissa o un'app installata dal
 * selettore; si salva la chiave `categoria:*` o il nome pacchetto.
 */
@Composable
private fun SelettoreAppOCategoria(valore: String, onScegli: (String) -> Unit) {
    val context = LocalContext.current
    var apertoPicker by remember { mutableStateOf(false) }
    val etichetta = remember(valore) {
        if (valore.isBlank()) null else CatalogoApp.etichettaValore(context, valore)
    }

    OutlinedButton(onClick = { apertoPicker = true }, modifier = Modifier.fillMaxWidth()) {
        Text(etichetta ?: stringResource(R.string.regola_scegli_app))
    }

    if (apertoPicker) {
        DialogoSceltaApp(
            onScegli = { onScegli(it); apertoPicker = false },
            onAnnulla = { apertoPicker = false },
        )
    }
}

@Composable
private fun DialogoSceltaApp(onScegli: (String) -> Unit, onAnnulla: () -> Unit) {
    val context = LocalContext.current
    // PackageManager è lento: si carica l'elenco fuori dal main thread una volta.
    val app by produceState<List<AppInstallata>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { CatalogoApp.appInstallate(context) }
    }

    AlertDialog(
        onDismissRequest = onAnnulla,
        title = { Text(stringResource(R.string.regola_scegli_app_titolo)) },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                item { TitoloSezione(stringResource(R.string.regola_sezione_categorie)) }
                items(CatalogoApp.CATEGORIE, key = { it }) { chiave ->
                    RigaScelta(CatalogoApp.nomeCategoria(context, chiave)) { onScegli(chiave) }
                }
                item { TitoloSezione(stringResource(R.string.regola_sezione_app)) }
                when (val lista = app) {
                    null -> item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = Spazi.m),
                            horizontalArrangement = Arrangement.Center,
                        ) { CircularProgressIndicator() }
                    }
                    else -> items(lista, key = { it.pacchetto }) { installata ->
                        RigaScelta(installata.etichetta) { onScegli(installata.pacchetto) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onAnnulla) { Text(stringResource(R.string.azione_annulla)) }
        },
    )
}

@Composable
private fun RigaScelta(testo: String, onClick: () -> Unit) {
    Text(
        text = testo,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Spazi.m),
    )
}

/**
 * Il gate della prima regola (concept.md: "almeno una obbligatoria"). Dopo i
 * permessi, il figlio non entra nell'app finché non si dà la prima regola: è lui
 * a scrivere il patto, e un patto senza regole non esiste. Creata la prima, il
 * server vieta di togliere l'ultima, quindi il gate non ricompare; offline la
 * copia locale (già sincronizzata almeno una volta) basta a superarlo.
 *
 * (v3) Prima ancora, se il telefono non è collegato, qui si collega: indirizzo
 * del server e codice di 6 cifre dal genitore. Fatto il collegamento, la
 * schermata dice "Collegato come: …" e passa alla prima regola (o, se il
 * figlio ha già un patto, direttamente all'app).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrimaRegolaScreen(vm: RegoleViewModel) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var dialogoAperto by remember { mutableStateOf(false) }

    val messaggioErrore = stringResource(R.string.regola_errore_generico)
    LaunchedEffect(stato.evento) {
        when (stato.evento) {
            is RegoleViewModel.Evento.Salvata -> dialogoAperto = false
            is RegoleViewModel.Evento.Errore -> snackbarHostState.showSnackbar(messaggioErrore)
            else -> Unit
        }
        if (stato.evento != null) vm.consumaEvento()
    }

    // La prima impressione dell'app: il titolo È l'eroe della schermata, quindi
    // niente barra in alto che lo ripeta.
    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spazi.xl, vertical = Spazi.xxl),
            verticalArrangement = Arrangement.spacedBy(Spazi.l),
        ) {
            Text(
                text = stringResource(R.string.prima_regola_titolo),
                style = MaterialTheme.typography.displaySmall,
            )
            if (stato.configurazioneMancante) {
                Text(
                    text = stringResource(R.string.prima_regola_config_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
                ModuloCollegamento(onCollegato = { vm.aggiorna() })
            } else {
                // Appena collegato: il ragazzo vede con che nome lo vede il patto.
                RigaCollegatoCome()
                Text(
                    text = stringResource(R.string.prima_regola_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.prima_regola_spiegazione),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { dialogoAperto = true },
                    enabled = !stato.invioInCorso,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.prima_regola_crea))
                }
            }
        }
    }

    if (dialogoAperto) {
        DialogoRegola(
            regola = null,
            invioInCorso = stato.invioInCorso,
            onAnnulla = { dialogoAperto = false },
            onSalva = { tipo, parametri -> vm.crea(tipo, parametri) },
        )
    }
}

// --- Mattoni condivisi dalle schermate del patto -----------------------------

/**
 * Dati vecchi: è un'ETÀ, non un fallimento ("Dati non aggiornati: ultimo
 * aggiornamento alle 14:32.", terminologia dell'audit).
 * Una riga su `surfaceVariant`, mai `errorContainer` — il rosso di sistema
 * resta alla validazione dei form. [aggiornatiIl] null = età sconosciuta.
 */
@Composable
internal fun BannerDatiVecchi(aggiornatiIl: Long?) {
    Text(
        text = aggiornatiIl?.let {
            stringResource(R.string.dati_fermi_alle, quandoLocale(Instant.ofEpochMilli(it)))
        } ?: stringResource(R.string.dati_fermi),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.shapes.small,
            )
            .padding(horizontal = Spazi.m, vertical = Spazi.s),
    )
}

@Composable
internal fun TitoloSezione(testo: String) {
    Text(
        text = testo,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = Spazi.s),
    )
}

/** Il sopra-titolo di una sezione: `labelMedium`, scritto MAIUSCOLO nella stringa. */
@Composable
internal fun Sopratitolo(testo: String, modifier: Modifier = Modifier) {
    Text(
        text = testo,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * Lo stato vuoto (§3.4): una riga asciutta, icona 20.dp + testo, allineati a
 * sinistra dentro il flusso. Quando va bene si scrive; ma senza un banner
 * verde speculare al rosso.
 */
@Composable
internal fun RigaVuota(icona: ImageVector, testo: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spazi.s),
    ) {
        Icon(
            imageVector = icona,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = testo,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun Etichetta(testo: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = testo,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = Spazi.s, vertical = 3.dp),
        )
    }
}

@Composable
internal fun Centro(contenuto: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        contenuto()
    }
}

@Composable
internal fun TestoCentrato(testo: String) {
    Text(
        text = testo,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = Spazi.xxl),
    )
}
