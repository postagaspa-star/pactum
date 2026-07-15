package eu.stgm.pactum.genitore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.DirezioniProposta
import eu.stgm.pactum.genitore.dati.EsitiRisposta
import eu.stgm.pactum.genitore.dati.Proposta
import eu.stgm.pactum.genitore.dati.RegolaFinestra
import eu.stgm.pactum.genitore.dati.StatiProposta
import eu.stgm.pactum.genitore.dati.TipiRegola
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Le proposte: creane una da una regola attiva, e vedi come sono andate. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProposteScreen(vm: ProposteViewModel = viewModel()) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // rememberSaveable: una rotazione non deve buttare via la proposta in corso né
    // il confronto appena ricevuto. Della regola scelta si salva l'id (Long,
    // salvabile) e la si risale dall'elenco corrente.
    var regolaSceltaId by rememberSaveable { mutableStateOf<Long?>(null) }
    var confrontoInviato by rememberSaveable { mutableStateOf<String?>(null) }
    val regolaScelta = regolaSceltaId?.let { id -> stato.regoleAttive.firstOrNull { it.id == id } }

    LifecycleResumeEffect(Unit) {
        vm.aggiorna()
        onPauseOrDispose { }
    }

    val messaggioErroreGenerico = stringResource(R.string.proposta_errore_generico)
    val messaggioGiaPendente = stringResource(R.string.proposta_errore_gia_pendente)
    val messaggioRegolaNonValida = stringResource(R.string.proposta_errore_regola_non_valida)
    val messaggioParametriNonValidi = stringResource(R.string.proposta_errore_parametri_non_validi)
    LaunchedEffect(stato.evento) {
        when (val evento = stato.evento) {
            is ProposteViewModel.Evento.Inviata -> {
                regolaSceltaId = null // chiudi il dialogo di creazione
                confrontoInviato = evento.confronto
            }
            is ProposteViewModel.Evento.Errore -> {
                val messaggio = when (evento.codice) {
                    "proposta_gia_pendente" -> messaggioGiaPendente
                    "regola_non_valida" -> messaggioRegolaNonValida
                    "parametri_non_validi" -> messaggioParametriNonValidi
                    else -> messaggioErroreGenerico
                }
                snackbarHostState.showSnackbar(messaggio)
            }
            null -> Unit
        }
        if (stato.evento != null) vm.consumaEvento()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.proposte_titolo)) },
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
                stato.caricamento && stato.proposte.isEmpty() && stato.regoleAttive.isEmpty() ->
                    Centro {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text(
                                text = stringResource(R.string.proposte_caricamento),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }

                stato.configurazioneMancante -> Centro {
                    TestoCentrato(stringResource(R.string.proposte_config_mancante))
                }

                stato.errore && stato.proposte.isEmpty() && stato.regoleAttive.isEmpty() ->
                    Centro { TestoCentrato(stringResource(R.string.proposte_errore)) }

                else -> ContenutoProposte(
                    regoleAttive = stato.regoleAttive,
                    proposte = stato.proposte,
                    mostraErrore = stato.errore,
                    onProponi = { regolaSceltaId = it.id },
                )
            }
        }
    }

    regolaScelta?.let { regola ->
        DialogoNuovaProposta(
            regola = regola,
            invioInCorso = stato.invioInCorso,
            onAnnulla = { regolaSceltaId = null },
            onInvia = { parametri, motivazione -> vm.creaProposta(regola.id, parametri, motivazione) },
        )
    }

    confrontoInviato?.let { confronto ->
        AlertDialog(
            onDismissRequest = { confrontoInviato = null },
            title = { Text(stringResource(R.string.proposta_inviata_titolo)) },
            text = { Text(stringResource(R.string.proposta_inviata_confronto, confronto)) },
            confirmButton = {
                TextButton(onClick = { confrontoInviato = null }) {
                    Text(stringResource(R.string.azione_ok))
                }
            },
        )
    }
}

@Composable
private fun ContenutoProposte(
    regoleAttive: List<RegolaFinestra>,
    proposte: List<Proposta>,
    mostraErrore: Boolean,
    onProponi: (RegolaFinestra) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (mostraErrore) {
            item { BannerDatiVecchi() }
        }

        item { TitoloSezione(stringResource(R.string.proposte_sezione_proponi)) }
        if (regoleAttive.isEmpty()) {
            item { TestoVuoto(stringResource(R.string.proposte_nessuna_regola_attiva)) }
        } else {
            items(regoleAttive, key = { "attiva-${it.id}" }) { regola ->
                CardRegolaProponibile(regola, onProponi)
            }
        }

        item { TitoloSezione(stringResource(R.string.proposte_sezione_elenco)) }
        if (proposte.isEmpty()) {
            item { TestoVuoto(stringResource(R.string.proposte_elenco_vuoto)) }
        } else {
            items(proposte, key = { "proposta-${it.id}" }) { CardProposta(it) }
        }
    }
}

@Composable
private fun CardRegolaProponibile(regola: RegolaFinestra, onProponi: (RegolaFinestra) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = descrizioneRegola(regola.tipo, regola.parametri),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(8.dp))
            FilledTonalButton(onClick = { onProponi(regola) }) {
                Text(stringResource(R.string.proposte_bottone_proponi))
            }
        }
    }
}

@Composable
private fun CardProposta(proposta: Proposta) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = etichettaStatoProposta(proposta.stato),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TagDirezione(proposta.direzione)
            }
            // Il confronto autoritativo del server: la stessa frase che vede il figlio.
            if (proposta.confronto.isNotBlank()) {
                Text(
                    text = proposta.confronto,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            proposta.motivazione?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = stringResource(R.string.proposta_motivazione, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            proposta.tsServer.takeIf { it.isNotBlank() }?.let { ts ->
                istanteServer(ts)?.let {
                    Text(
                        text = dataOraLocale(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            // La risposta del figlio, quando c'è: esito + motivazione.
            proposta.risposta?.let { risposta ->
                Spacer(modifier = Modifier.height(8.dp))
                val titoloRisposta = if (risposta.esito == EsitiRisposta.ACCETTA) {
                    stringResource(R.string.proposta_risposta_accettata)
                } else {
                    stringResource(R.string.proposta_risposta_rifiutata)
                }
                Text(
                    text = titoloRisposta,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                risposta.motivazione?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = stringResource(R.string.proposta_risposta_motivazione, it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TagDirezione(direzione: String) {
    val testo = when (direzione) {
        DirezioniProposta.ALLENTA -> stringResource(R.string.proposta_tag_allenta)
        DirezioniProposta.STRINGE -> stringResource(R.string.proposta_tag_stringe)
        DirezioniProposta.ELIMINA -> stringResource(R.string.proposta_tag_elimina)
        else -> return
    }
    Etichetta(testo)
}

@Composable
private fun etichettaStatoProposta(stato: String): String = when (stato) {
    StatiProposta.PENDENTE -> stringResource(R.string.proposta_stato_pendente)
    StatiProposta.ACCETTATA -> stringResource(R.string.proposta_stato_accettata)
    StatiProposta.RIFIUTATA -> stringResource(R.string.proposta_stato_rifiutata)
    else -> stato
}

// I sette giorni del contratto (giorni:[lun..dom]), in ordine canonico: il
// selettore a chip li usa come token e per l'ordine di serializzazione.
private val GIORNI_SETTIMANA = listOf("lun", "mar", "mer", "gio", "ven", "sab", "dom")

// Tetto dei minuti al giorno di una regola limite_tempo: un giorno intero.
private const val MINUTI_MAX = 1440

/**
 * Creazione di una proposta: modifica dei parametri (per tipo di regola) oppure
 * eliminazione (il marcatore {"azione":"elimina"}). Il confronto lo calcola il
 * server e si mostra dopo l'invio.
 */
@Composable
private fun DialogoNuovaProposta(
    regola: RegolaFinestra,
    invioInCorso: Boolean,
    onAnnulla: () -> Unit,
    onInvia: (JsonObject, String?) -> Unit,
) {
    // rememberSaveable: una rotazione col dialogo aperto non deve azzerare i campi
    // in corso. I valori iniziali vengono dai parametri attuali della regola; dopo
    // una ricreazione si ripristina invece ciò che il genitore stava scrivendo.
    var elimina by rememberSaveable { mutableStateOf(false) }
    var motivazione by rememberSaveable { mutableStateOf("") }
    var app by rememberSaveable {
        mutableStateOf(parametroTesto(regola.parametri, "app_o_categoria") ?: "")
    }
    var minuti by rememberSaveable {
        mutableStateOf(parametroTesto(regola.parametri, "minuti_al_giorno") ?: "")
    }
    var dalle by rememberSaveable { mutableStateOf(parametroTesto(regola.parametri, "dalle") ?: "") }
    var alle by rememberSaveable { mutableStateOf(parametroTesto(regola.parametri, "alle") ?: "") }
    // I giorni restano una stringa "lun, mar" (salvabile); i chip la leggono e la
    // riscrivono in ordine canonico.
    var giorni by rememberSaveable { mutableStateOf(giorniTesto(regola.parametri)) }
    var descrizione by rememberSaveable {
        mutableStateOf(parametroTesto(regola.parametri, "descrizione") ?: "")
    }
    var arbitro by rememberSaveable {
        mutableStateOf(parametroTesto(regola.parametri, "arbitro_nome") ?: "")
    }
    var frequenza by rememberSaveable {
        mutableStateOf(parametroTesto(regola.parametri, "frequenza") ?: "")
    }

    val parametri: JsonObject? = when {
        elimina -> buildJsonObject { put("azione", "elimina") }
        regola.tipo == TipiRegola.LIMITE_TEMPO -> {
            val n = minuti.trim().toIntOrNull()
            // Limite entro 1..1440 (un giorno): oltre non ha senso e il server lo
            // rifiuterebbe comunque. Resta il server l'autorità sul valore.
            if (app.isBlank() || n == null || n !in 1..MINUTI_MAX) {
                null
            } else {
                buildJsonObject {
                    put("app_o_categoria", app.trim())
                    put("minuti_al_giorno", n)
                }
            }
        }
        regola.tipo == TipiRegola.FASCIA_ORARIA -> {
            val listaGiorni = giorni.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (dalle.isBlank() || alle.isBlank() || listaGiorni.isEmpty()) {
                null
            } else {
                buildJsonObject {
                    put("dalle", dalle.trim())
                    put("alle", alle.trim())
                    putJsonArray("giorni") { listaGiorni.forEach { add(it) } }
                }
            }
        }
        regola.tipo == TipiRegola.VITA_REALE -> {
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
        title = { Text(stringResource(R.string.proposta_crea_titolo)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(
                        R.string.proposta_crea_regola,
                        descrizioneRegola(regola.tipo, regola.parametri),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ScegliModalita(elimina = elimina, onElimina = { elimina = it })

                if (!elimina) {
                    when (regola.tipo) {
                        TipiRegola.LIMITE_TEMPO -> {
                            CampoTesto(app, { app = it }, R.string.proposta_campo_app)
                            CampoMinuti(minuti) { minuti = it }
                        }
                        TipiRegola.FASCIA_ORARIA -> {
                            CampoTesto(dalle, { dalle = it }, R.string.proposta_campo_dalle)
                            CampoTesto(alle, { alle = it }, R.string.proposta_campo_alle)
                            val giorniSelezionati = giorni.split(",")
                                .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                            SelettoreGiorni(
                                selezionati = giorniSelezionati,
                                onToggle = { g ->
                                    val nuovo = if (g in giorniSelezionati) {
                                        giorniSelezionati - g
                                    } else {
                                        giorniSelezionati + g
                                    }
                                    giorni = GIORNI_SETTIMANA.filter { it in nuovo }
                                        .joinToString(", ")
                                },
                            )
                        }
                        TipiRegola.VITA_REALE -> {
                            CampoTesto(
                                descrizione, { descrizione = it },
                                R.string.proposta_campo_descrizione,
                            )
                            CampoTesto(arbitro, { arbitro = it }, R.string.proposta_campo_arbitro)
                            CampoTesto(
                                frequenza, { frequenza = it }, R.string.proposta_campo_frequenza,
                            )
                        }
                    }
                }

                CampoTesto(motivazione, { motivazione = it }, R.string.proposta_campo_motivazione)
            }
        },
        confirmButton = {
            Button(
                enabled = parametri != null && !invioInCorso,
                onClick = { parametri?.let { onInvia(it, motivazione) } },
            ) {
                Text(stringResource(R.string.proposta_invia))
            }
        },
        dismissButton = {
            TextButton(onClick = onAnnulla) { Text(stringResource(R.string.azione_annulla)) }
        },
    )
}

@Composable
private fun ScegliModalita(elimina: Boolean, onElimina: (Boolean) -> Unit) {
    Column {
        RigaRadio(
            selezionato = !elimina,
            testo = stringResource(R.string.proposta_modalita_modifica),
            onClick = { onElimina(false) },
        )
        RigaRadio(
            selezionato = elimina,
            testo = stringResource(R.string.proposta_modalita_elimina),
            onClick = { onElimina(true) },
        )
    }
}

@Composable
private fun RigaRadio(selezionato: Boolean, testo: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selezionato, onClick = onClick)
        Text(text = testo, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CampoTesto(valore: String, onValore: (String) -> Unit, etichetta: Int) {
    OutlinedTextField(
        value = valore,
        onValueChange = onValore,
        label = { Text(stringResource(etichetta)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * I minuti al giorno come numero limitato: solo cifre, entro 1..1440 (un giorno).
 * Il campo si limita da sé per ridurre i 422, ma il valore lo valida il server.
 */
@Composable
private fun CampoMinuti(valore: String, onValore: (String) -> Unit) {
    val n = valore.trim().toIntOrNull()
    val fuoriRange = valore.isNotBlank() && (n == null || n !in 1..MINUTI_MAX)
    OutlinedTextField(
        value = valore,
        onValueChange = { grezzo -> onValore(grezzo.filter { it.isDigit() }.take(4)) },
        label = { Text(stringResource(R.string.proposta_campo_minuti)) },
        singleLine = true,
        isError = fuoriRange,
        supportingText = { Text(stringResource(R.string.proposta_minuti_range)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * I giorni della fascia oraria come chip a selezione multipla (invece del testo
 * libero separato da virgole): meno errori di battitura, meno 422. La scelta
 * viaggia comunque come i token del contratto (lun..dom).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelettoreGiorni(selezionati: Set<String>, onToggle: (String) -> Unit) {
    val etichette = stringArrayResource(R.array.proposta_giorni_etichette)
    Column {
        Text(
            text = stringResource(R.string.proposta_giorni_scegli),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GIORNI_SETTIMANA.forEachIndexed { indice, giorno ->
                FilterChip(
                    selected = giorno in selezionati,
                    onClick = { onToggle(giorno) },
                    label = { Text(etichette.getOrElse(indice) { giorno }) },
                )
            }
        }
    }
}

private fun giorniTesto(parametri: JsonObject): String =
    (parametri["giorni"] as? JsonArray)
        ?.joinToString(", ") { (it as? JsonPrimitive)?.content ?: "" }
        ?.trim(',', ' ')
        ?: ""

@Composable
private fun BannerDatiVecchi() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Text(
            text = stringResource(R.string.notifiche_dati_vecchi),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun TitoloSezione(testo: String) {
    Text(
        text = testo,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun TestoVuoto(testo: String) {
    Text(
        text = testo,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Etichetta(testo: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = testo,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun Centro(contenuto: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        contenuto()
    }
}

@Composable
private fun TestoCentrato(testo: String) {
    Text(
        text = testo,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 32.dp),
    )
}
