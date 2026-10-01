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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
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
import eu.stgm.pactum.figlio.dati.BloccoCambio
import eu.stgm.pactum.figlio.dati.CambioRegola
import eu.stgm.pactum.figlio.dati.EsitoProposta
import eu.stgm.pactum.figlio.dati.Proposta
import eu.stgm.pactum.figlio.dati.ProposteDelFiglio
import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.TipiRegola
import eu.stgm.pactum.figlio.dati.inGiorniPatto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/**
 * Le regole del patto: le scrive il figlio, il server le custodisce. (0.10) Su
 * ogni regola anche "Proponi al genitore" (contratto v3.4): se il genitore
 * accetta, il cambio vale subito, anche se allenta. [onApriProposte] porta
 * alla scheda Proposte.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegoleScreen(
    onApriProposte: () -> Unit = {},
    vm: RegoleViewModel = viewModel(),
) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    // (0.10) Le snackbar delle proposte partono qui, fuori dall'effetto degli
    // eventi: l'evento si consuma subito, così "Apri Proposte" può cambiare
    // scheda senza che la snackbar ricompaia al ritorno.
    val ambito = rememberCoroutineScope()

    // I dialoghi aperti si ricordano per id della regola (e il blocco come
    // testo): una rotazione o la morte del processo non li chiudono, e non
    // buttano quello che il ragazzo ha scritto (0.10). Per la modifica: null =
    // nessun dialogo, NUOVA_REGOLA = creazione, altrimenti la regola.
    var dialogoRegolaId by rememberSaveable { mutableStateOf<Long?>(null) }
    var regolaDaEliminareId by rememberSaveable { mutableStateOf<Long?>(null) }
    // (0.10) La regola su cui si sta scrivendo una proposta al genitore.
    var regolaDaProporreId by rememberSaveable { mutableStateOf<Long?>(null) }
    // (0.10) Il cambio fermato dal blocco dei 4 giorni (BloccoCambio.inTesto).
    var bloccoTesto by rememberSaveable { mutableStateOf<String?>(null) }
    // L'ultima copia di ogni regola aperta in un dialogo: se intanto sparisce
    // dal patto (eliminata, rilettura) il dialogo resta e dice perché.
    val viste = remember { HashMap<Long, Regola>() }
    fun regolaAperta(id: Long?): Regola? =
        id?.let { cercata -> stato.regole.firstOrNull { it.id == cercata } ?: viste[cercata] }
    fun ricorda(regola: Regola) {
        viste[regola.id] = regola
    }

    // (0.10) Chiuso il modulo della proposta (o il blocco), il suo esito non si dice più.
    fun chiudiProposta() {
        regolaDaProporreId = null
        vm.dimenticaEsitoProposta()
    }
    fun chiudiBlocco() {
        bloccoTesto = null
        vm.dimenticaEsitoProposta()
    }

    LifecycleResumeEffect(Unit) {
        vm.aggiorna()
        onPauseOrDispose { }
    }

    val messaggioSalvata = stringResource(R.string.regola_salvata)
    val messaggioEliminata = stringResource(R.string.regola_eliminata_ok)
    val messaggioUltima = stringResource(R.string.regola_ultima_messaggio)
    val messaggioErrore = stringResource(R.string.regola_errore_generico)
    val azioneApriProposte = stringResource(R.string.proposta_apri_proposte)
    LaunchedEffect(stato.evento) {
        when (val evento = stato.evento) {
            is RegoleViewModel.Evento.Salvata -> {
                dialogoRegolaId = null
                snackbarHostState.showSnackbar(messaggioSalvata)
            }
            is RegoleViewModel.Evento.Eliminata -> {
                regolaDaEliminareId = null
                snackbarHostState.showSnackbar(messaggioEliminata)
            }
            is RegoleViewModel.Evento.LockAttivo -> {
                regolaDaEliminareId = null
                // (0.10) L'attesa resta quella di prima, ma in un dialogo che offre
                // anche di chiederlo al genitore. Si apre sempre: il server guarda
                // "l'ultima regola" prima del blocco, quindi qui le regole sono almeno due.
                vm.dimenticaEsitoProposta()
                bloccoTesto = BloccoCambio(
                    evento.cambio,
                    sbloccoAlle = System.currentTimeMillis() + evento.secondiRimanenti * 1000,
                ).inTesto()
            }
            is RegoleViewModel.Evento.UltimaRegola -> {
                regolaDaEliminareId = null
                snackbarHostState.showSnackbar(messaggioUltima)
            }
            is RegoleViewModel.Evento.Errore -> snackbarHostState.showSnackbar(messaggioErrore)
            is RegoleViewModel.Evento.PropostaAlGenitore -> {
                val esito = evento.esito
                val moduloAperto = regolaDaProporreId != null || bloccoTesto != null
                // Si consuma subito: "Apri Proposte" può cambiare scheda, e al
                // ritorno la snackbar non deve ricomparire.
                vm.consumaEvento()
                if (ProposteDelFiglio.chiudeIlModulo(esito)) {
                    // Arrivata: si chiude tutto (anche la modifica sotto il blocco).
                    bloccoTesto = null
                    regolaDaProporreId = null
                    dialogoRegolaId = null
                } else if (moduloAperto) {
                    // Il resto lo dice il modulo aperto, dove il ragazzo guarda
                    // (stato.esitoProposta), non una snackbar sotto l'ombra.
                    return@LaunchedEffect
                }
                // Arrivata, oppure il modulo era già chiuso quando è arrivata la risposta.
                val messaggio = testoEsitoProposta(context, esito)
                val verso = esito == EsitoProposta.GiaPendente || esito == EsitoProposta.GiaTua
                ambito.launch {
                    val scelta = snackbarHostState.showSnackbar(
                        message = messaggio,
                        // Già una proposta su questa regola: si va a vederla.
                        actionLabel = azioneApriProposte.takeIf { verso },
                        duration = SnackbarDuration.Long,
                    )
                    if (scelta == SnackbarResult.ActionPerformed) onApriProposte()
                }
                return@LaunchedEffect
            }
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
                FloatingActionButton(onClick = { dialogoRegolaId = NUOVA_REGOLA }) {
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
                                inAttesa = stato.proposteInAttesa[regola.id],
                                onModifica = {
                                    ricorda(regola)
                                    dialogoRegolaId = regola.id
                                },
                                onElimina = {
                                    ricorda(regola)
                                    regolaDaEliminareId = regola.id
                                },
                                onProponi = {
                                    ricorda(regola)
                                    vm.dimenticaEsitoProposta()
                                    regolaDaProporreId = regola.id
                                },
                                onApriProposte = onApriProposte,
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

    // Creazione (NUOVA_REGOLA) o modifica di una regola: quella della modifica
    // si mostra solo quando la si ritrova (dopo la morte del processo, a
    // rilettura finita).
    dialogoRegolaId?.let { id ->
        val nuova = id == NUOVA_REGOLA
        val regola = if (nuova) null else regolaAperta(id)
        if (nuova || regola != null) {
            DialogoRegola(
                regola = regola,
                invioInCorso = stato.invioInCorso,
                onAnnulla = { dialogoRegolaId = null },
                onSalva = { tipo, parametri ->
                    if (regola == null) vm.crea(tipo, parametri) else vm.modifica(regola.id, parametri)
                },
            )
        }
    }

    regolaAperta(regolaDaEliminareId)?.let { regola ->
        AlertDialog(
            onDismissRequest = { regolaDaEliminareId = null },
            title = { Text(stringResource(R.string.regola_elimina_conferma_titolo)) },
            text = {
                Text(
                    stringResource(
                        R.string.regola_elimina_conferma_testo,
                        descrizioneRegola(regola.tipo, regola.parametri, leggibile = true),
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
                TextButton(onClick = { regolaDaEliminareId = null }) {
                    Text(stringResource(R.string.azione_annulla))
                }
            },
        )
    }

    regolaAperta(regolaDaProporreId)?.let { regola ->
        DialogoProposta(
            regola = regola,
            invioInCorso = stato.invioInCorso,
            // Eliminare l'ultima regola del patto non si può, nemmeno d'accordo.
            eliminabile = stato.totaleFiglio > 1,
            esito = stato.esitoProposta,
            onAnnulla = { chiudiProposta() },
            onManda = { cambio, perche -> vm.proponi(cambio, perche) },
            onApriProposte = {
                chiudiProposta()
                onApriProposte()
            },
        )
    }

    // Sopra al modulo della modifica, che resta aperto sotto: chiuso il
    // blocco, si torna lì e si può ancora stringere invece di allentare.
    BloccoCambio.daTesto(bloccoTesto)?.let { blocco ->
        DialogoBlocco(
            blocco = blocco,
            invioInCorso = stato.invioInCorso,
            esito = stato.esitoProposta,
            onChiudi = { chiudiBlocco() },
            onChiedi = { perche -> vm.proponi(blocco.cambio, perche) },
            onApriProposte = {
                chiudiBlocco()
                dialogoRegolaId = null
                onApriProposte()
            },
        )
    }
}

/** Il dialogo della modifica aperto su una regola nuova (creazione). */
private const val NUOVA_REGOLA = -1L

@Composable
private fun CardRegola(
    regola: Regola,
    concordata: Boolean,
    inAttesa: Proposta?,
    onModifica: () -> Unit,
    onElimina: () -> Unit,
    onProponi: () -> Unit,
    onApriProposte: () -> Unit,
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
                // (0.10) Mai il nome di un pacchetto, anche dopo un cambio di bersaglio.
                text = descrizioneRegola(regola.tipo, regola.parametri, leggibile = true),
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
            // (0.10) Proporre al genitore, ben visibile su ogni regola (Andrea,
            // 30/09: il pulsante si deve vedere). Se su questa regola aspetta già
            // una proposta, di chiunque sia, si dice quale e si porta a Proposte:
            // una seconda il server la rifiuterebbe.
            if (inAttesa == null) {
                FilledTonalButton(onClick = onProponi, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(Spazi.s))
                    Text(stringResource(R.string.regola_proponi))
                }
            } else {
                Text(
                    text = testoPropostaInAttesa(inAttesa),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spazi.xs, bottom = Spazi.s),
                )
                OutlinedButton(onClick = onApriProposte, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.regola_vedi_proposta))
                }
            }
        }
    }
}

/**
 * (0.10) La proposta che aspetta su una regola, in una riga: la propria
 * aspetta il genitore, quella del genitore aspetta il figlio. Il confronto di
 * un'eliminazione è scritto per il genitore: qui si dice "eliminarla".
 */
@Composable
private fun testoPropostaInAttesa(proposta: Proposta): String {
    val eliminazione = TestoProposta.eliminazione(proposta.direzione, proposta.parametriProposti)
    val confronto = proposta.confronto?.trim()?.ifEmpty { null }
        ?: stringResource(R.string.proposta_senza_confronto)
    return when {
        proposta.delFiglio && eliminazione -> stringResource(R.string.regola_proposta_tua_elimina)
        proposta.delFiglio -> stringResource(R.string.regola_proposta_tua, confronto)
        eliminazione -> stringResource(R.string.regola_proposta_genitore_elimina)
        else -> stringResource(R.string.regola_proposta_genitore, confronto)
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

/**
 * I campi di una regola, partendo dai valori di [regola] (vuoti per una
 * nuova). (0.10) Lo stesso modulo serve a creare, a modificare e a proporre
 * al genitore: stessi selettori, stessi controlli (ParametriRegola), mai testo
 * libero per l'app o la categoria.
 */
@Stable
private class ModuloRegola(
    tipo: String,
    app: String = "",
    minuti: String = "",
    dalle: String = "",
    alle: String = "",
    giorni: Set<String> = emptySet(),
    descrizione: String = "",
    arbitro: String = "",
    frequenza: String = "",
) {
    var tipo by mutableStateOf(tipo)
    var app by mutableStateOf(app)
    var minuti by mutableStateOf(minuti)
    var dalle by mutableStateOf(dalle)
    var alle by mutableStateOf(alle)
    var giorni by mutableStateOf(giorni)
    var descrizione by mutableStateOf(descrizione)
    var arbitro by mutableStateOf(arbitro)
    var frequenza by mutableStateOf(frequenza)

    /** I parametri pronti da mandare, null se qualche campo non va: il pulsante resta spento. */
    val parametri: JsonObject?
        get() = ParametriRegola.daCampi(tipo, app, minuti, dalle, alle, giorni, descrizione, arbitro, frequenza)

    /** (0.10) Più minuti di un giorno: il campo lo dice, non solo il pulsante spento. */
    val minutiTroppi: Boolean
        get() = tipo == TipiRegola.LIMITE_TEMPO && ParametriRegola.minutiOltreIlGiorno(minuti)

    fun cambiaGiorno(giorno: String) {
        giorni = if (giorno in giorni) giorni - giorno else giorni + giorno
    }

    companion object {
        /** Il modulo coi valori di [regola]; vuoto (limite di tempo) per una regola nuova. */
        fun da(regola: Regola?): ModuloRegola {
            val iniziali = regola?.parametri ?: JsonObject(emptyMap())
            return ModuloRegola(
                tipo = regola?.tipo ?: TipiRegola.LIMITE_TEMPO,
                app = parametroTesto(iniziali, "app_o_categoria") ?: "",
                minuti = parametroTesto(iniziali, "minuti_al_giorno") ?: "",
                dalle = parametroTesto(iniziali, "dalle") ?: "",
                alle = parametroTesto(iniziali, "alle") ?: "",
                giorni = (iniziali["giorni"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
                    ?.toSet()
                    ?: emptySet(),
                descrizione = parametroTesto(iniziali, "descrizione") ?: "",
                arbitro = parametroTesto(iniziali, "arbitro_nome") ?: "",
                frequenza = parametroTesto(iniziali, "frequenza") ?: "",
            )
        }

        /**
         * (0.10) Quello che il ragazzo ha scritto resta attraverso una
         * rotazione o la morte del processo: tutti i campi come testo.
         */
        val Salvataggio: Saver<ModuloRegola, Any> = listSaver(
            save = {
                listOf(
                    it.tipo, it.app, it.minuti, it.dalle, it.alle, it.giorni.joinToString(","),
                    it.descrizione, it.arbitro, it.frequenza,
                )
            },
            restore = { valori ->
                ModuloRegola(
                    tipo = valori[0],
                    app = valori[1],
                    minuti = valori[2],
                    dalle = valori[3],
                    alle = valori[4],
                    giorni = valori[5].split(',').filter { it.isNotBlank() }.toSet(),
                    descrizione = valori[6],
                    arbitro = valori[7],
                    frequenza = valori[8],
                )
            },
        )
    }
}

/**
 * I campi del [modulo], dentro la colonna di un dialogo. [sceltaTipo] = si
 * sceglie anche il tipo: solo creando (il contratto non prevede di cambiarlo).
 */
@Composable
private fun CampiRegola(modulo: ModuloRegola, sceltaTipo: Boolean) {
    if (sceltaTipo) {
        Column {
            RigaRadio(
                selezionato = modulo.tipo == TipiRegola.LIMITE_TEMPO,
                testo = stringResource(R.string.regola_tipo_limite_tempo),
                onClick = { modulo.tipo = TipiRegola.LIMITE_TEMPO },
            )
            RigaRadio(
                selezionato = modulo.tipo == TipiRegola.FASCIA_ORARIA,
                testo = stringResource(R.string.regola_tipo_fascia_oraria),
                onClick = { modulo.tipo = TipiRegola.FASCIA_ORARIA },
            )
            RigaRadio(
                selezionato = modulo.tipo == TipiRegola.VITA_REALE,
                testo = stringResource(R.string.regola_tipo_vita_reale),
                onClick = { modulo.tipo = TipiRegola.VITA_REALE },
            )
        }
    }

    when (modulo.tipo) {
        TipiRegola.LIMITE_TEMPO -> {
            SelettoreAppOCategoria(valore = modulo.app, onScegli = { modulo.app = it })
            CampoTesto(
                modulo.minuti, { modulo.minuti = it }, R.string.regola_campo_minuti,
                numerico = true,
                // (0.10) Il server accetta al massimo 1440 minuti: lo si dice sul campo.
                avviso = if (modulo.minutiTroppi) stringResource(R.string.regola_minuti_troppi) else null,
            )
        }
        TipiRegola.FASCIA_ORARIA -> {
            CampoTesto(modulo.dalle, { modulo.dalle = it }, R.string.regola_campo_dalle)
            CampoTesto(modulo.alle, { modulo.alle = it }, R.string.regola_campo_alle)
            Text(
                text = stringResource(R.string.regola_campo_giorni),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spazi.xs)) {
                ParametriRegola.GIORNI.take(4).forEach { giorno ->
                    ChipGiorno(giorno, giorno in modulo.giorni) { modulo.cambiaGiorno(giorno) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spazi.xs)) {
                ParametriRegola.GIORNI.drop(4).forEach { giorno ->
                    ChipGiorno(giorno, giorno in modulo.giorni) { modulo.cambiaGiorno(giorno) }
                }
            }
        }
        TipiRegola.VITA_REALE -> {
            CampoTesto(modulo.descrizione, { modulo.descrizione = it }, R.string.regola_campo_descrizione)
            CampoTesto(modulo.arbitro, { modulo.arbitro = it }, R.string.regola_campo_arbitro)
            CampoTesto(modulo.frequenza, { modulo.frequenza = it }, R.string.regola_campo_frequenza)
        }
    }
}

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
    // (0.10) Salvato: una rotazione non butta quello che si sta scrivendo.
    val modulo = rememberSaveable(regola?.id, saver = ModuloRegola.Salvataggio) { ModuloRegola.da(regola) }
    val parametri = modulo.parametri

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
                CampiRegola(modulo, sceltaTipo = regola == null)
            }
        },
        confirmButton = {
            Button(
                enabled = parametri != null && !invioInCorso,
                onClick = { parametri?.let { onSalva(modulo.tipo, it) } },
            ) {
                Text(stringResource(R.string.azione_salva))
            }
        },
        dismissButton = {
            TextButton(onClick = onAnnulla) { Text(stringResource(R.string.azione_annulla)) }
        },
    )
}

/**
 * (0.10) "Proponi al genitore" (contratto v3.4): lo stesso modulo della
 * modifica, coi valori di adesso, più il perché facoltativo. Da qui si può
 * anche proporre di eliminarla ([eliminabile]: non l'unica regola del patto).
 * Se il genitore accetta, il server applica il cambio subito, anche se
 * allenta. La usano la scheda Regole e la "Nuova proposta" della scheda Proposte.
 *
 * [esito] = com'è andato l'ultimo invio, se non è arrivato: si dice qui
 * dentro. Il pulsante resta acceso solo quando rimandare ha senso; per una
 * proposta già in attesa, [onApriProposte] (se c'è) porta a vederla.
 */
@Composable
internal fun DialogoProposta(
    regola: Regola,
    invioInCorso: Boolean,
    eliminabile: Boolean,
    esito: EsitoProposta?,
    onAnnulla: () -> Unit,
    onManda: (CambioRegola, String?) -> Unit,
    onApriProposte: (() -> Unit)? = null,
) {
    val modulo = rememberSaveable(regola.id, saver = ModuloRegola.Salvataggio) { ModuloRegola.da(regola) }
    var perche by rememberSaveable(regola.id) { mutableStateOf("") }
    var eliminazione by rememberSaveable(regola.id) { mutableStateOf(false) }
    // Il punto di partenza è il modulo stesso appena aperto, non i parametri
    // grezzi: i giorni di una fascia, per esempio, il modulo li rimette in ordine.
    val partenza = remember(regola.id) { ModuloRegola.da(regola).parametri }
    val parametri = modulo.parametri
    // Una proposta uguale alla regola di adesso non chiede niente: il pulsante resta spento.
    val cambio: CambioRegola? = when {
        eliminazione -> CambioRegola.Eliminazione(regola.id)
        parametri != null && parametri != partenza -> CambioRegola.Modifica(regola.id, parametri)
        else -> null
    }
    val adesso = descrizioneRegola(regola.tipo, regola.parametri, leggibile = true)

    AlertDialog(
        onDismissRequest = onAnnulla,
        title = {
            Text(stringResource(if (eliminazione) R.string.proponi_elimina else R.string.proponi_titolo))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spazi.m),
            ) {
                if (eliminazione) {
                    Text(
                        text = stringResource(R.string.proponi_elimina_testo, adesso),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.proposta_regola_ora, adesso),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CampiRegola(modulo, sceltaTipo = false)
                }
                CampoPerche(perche) { perche = it }
                Text(
                    text = stringResource(
                        if (eliminazione) R.string.proponi_elimina_spiegazione else R.string.proponi_spiegazione,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!eliminazione && eliminabile) {
                    TextButton(
                        onClick = { eliminazione = true },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(stringResource(R.string.proponi_elimina))
                    }
                }
                esito?.let { RigaEsitoProposta(it, onApriProposte) }
            }
        },
        confirmButton = {
            Button(
                enabled = cambio != null && !invioInCorso && (esito == null || ProposteDelFiglio.riprovabile(esito)),
                onClick = { cambio?.let { onManda(it, perche) } },
            ) {
                Text(stringResource(R.string.proponi_manda))
            }
        },
        dismissButton = {
            // Dall'eliminazione si torna al modulo; dal modulo si chiude.
            TextButton(onClick = { if (eliminazione) eliminazione = false else onAnnulla() }) {
                Text(stringResource(if (eliminazione) R.string.azione_indietro else R.string.azione_annulla))
            }
        },
    )
}

/**
 * (0.10) Il blocco dei 4 giorni ha fermato un cambio che allenta (o
 * un'eliminazione). Resta l'attesa di sempre, "potrai allentarla tra…", e in
 * più "Chiedi al genitore": se accetta, vale subito. Manda ESATTAMENTE il
 * cambio fermato ([blocco].cambio), col perché se c'è. [esito] = com'è
 * andato l'ultimo invio, se non è arrivato: si dice qui dentro.
 */
@Composable
private fun DialogoBlocco(
    blocco: BloccoCambio,
    invioInCorso: Boolean,
    esito: EsitoProposta?,
    onChiudi: () -> Unit,
    onChiedi: (String?) -> Unit,
    onApriProposte: () -> Unit,
) {
    val context = LocalContext.current
    val perEliminazione = blocco.cambio is CambioRegola.Eliminazione
    var perche by rememberSaveable(blocco.cambio.regolaId) { mutableStateOf("") }
    // Dall'istante dello sblocco: dopo una rotazione o la morte del processo
    // l'attesa resta giusta.
    val attesa = testoAttesa(context, blocco.secondiRimanenti(System.currentTimeMillis()))

    AlertDialog(
        onDismissRequest = onChiudi,
        title = {
            Text(
                stringResource(
                    if (perEliminazione) R.string.regola_elimina_lock_titolo else R.string.regola_lock_titolo,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spazi.m),
            ) {
                Text(
                    text = if (perEliminazione) {
                        stringResource(R.string.regola_elimina_lock_messaggio, attesa)
                    } else {
                        stringResource(R.string.regola_lock_messaggio, attesa)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(
                        if (perEliminazione) {
                            R.string.regola_elimina_lock_chiedi_spiegazione
                        } else {
                            R.string.regola_lock_chiedi_spiegazione
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                CampoPerche(perche) { perche = it }
                esito?.let { RigaEsitoProposta(it, onApriProposte) }
            }
        },
        confirmButton = {
            Button(
                enabled = !invioInCorso && (esito == null || ProposteDelFiglio.riprovabile(esito)),
                onClick = { onChiedi(perche) },
            ) {
                Text(stringResource(R.string.regola_lock_chiedi))
            }
        },
        dismissButton = {
            // Sotto un cambio che allenta resta aperta la modifica: si torna lì.
            // Sotto un'eliminazione non c'è niente da modificare.
            TextButton(onClick = onChiudi) {
                Text(stringResource(if (perEliminazione) R.string.azione_annulla else R.string.regola_lock_torna))
            }
        },
    )
}

/**
 * (0.10) Com'è andata la proposta, dentro il modulo ancora aperto: una riga
 * neutra (§3.1: niente rosso per un esito del server), e per una proposta già
 * in attesa su quella regola la strada per vederla ([onApriProposte], se c'è).
 */
@Composable
private fun RigaEsitoProposta(esito: EsitoProposta, onApriProposte: (() -> Unit)?) {
    val context = LocalContext.current
    LocalConfiguration.current
    RigaNeutra(testoEsitoProposta(context, esito))
    if (onApriProposte != null && (esito == EsitoProposta.GiaPendente || esito == EsitoProposta.GiaTua)) {
        TextButton(onClick = onApriProposte, contentPadding = PaddingValues(0.dp)) {
            Text(stringResource(R.string.proposta_apri_proposte))
        }
    }
}

/** (0.10) Il perché di una proposta: facoltativo, arriva al genitore con la proposta. */
@Composable
private fun CampoPerche(valore: String, onValore: (String) -> Unit) {
    OutlinedTextField(
        value = valore,
        onValueChange = onValore,
        label = { Text(stringResource(R.string.proponi_perche)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
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

/** Un campo del modulo. (0.10) [avviso] = cosa non va nel valore scritto, detto sul campo. */
@Composable
private fun CampoTesto(
    valore: String,
    onValore: (String) -> Unit,
    etichetta: Int,
    numerico: Boolean = false,
    avviso: String? = null,
) {
    OutlinedTextField(
        value = valore,
        onValueChange = onValore,
        label = { Text(stringResource(etichetta)) },
        // Validazione del campo: l'unico posto dove il rosso di sistema vale (§3.1).
        isError = avviso != null,
        supportingText = avviso?.let { { Text(it) } },
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
 * mai in silenzio). Si sceglie tutto il telefono (0.9), una categoria fissa o
 * un'app installata dal selettore; si salva "totale", la chiave `categoria:*`
 * o il nome pacchetto.
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
                // (0.9) In cima: tutto l'uso del telefono, lo stesso totale che vede il genitore.
                item(key = CatalogoApp.CHIAVE_TOTALE) {
                    RigaScelta(stringResource(R.string.chiave_totale)) { onScegli(CatalogoApp.CHIAVE_TOTALE) }
                }
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
