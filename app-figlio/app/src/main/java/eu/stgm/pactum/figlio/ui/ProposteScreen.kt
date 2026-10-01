package eu.stgm.pactum.figlio.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.dati.ContestoDispositivi
import eu.stgm.pactum.figlio.dati.DirezioniProposta
import eu.stgm.pactum.figlio.dati.EsitiRisposta
import eu.stgm.pactum.figlio.dati.Proposta
import eu.stgm.pactum.figlio.dati.ProposteDelFiglio
import eu.stgm.pactum.figlio.dati.Regola

/**
 * Le proposte del genitore: il confronto in evidenza, la decisione è tua.
 * (0.10, contratto v3.4) Anche le tue: quelle che aspettano il genitore, da
 * ritirare se cambi idea; una nuova con "Nuova proposta"; la storia breve
 * delle proposte chiuse, di tutti e due.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProposteScreen(vm: ProposteViewModel = viewModel()) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // (0.10) "Nuova proposta": prima la regola (se ce n'è più d'una), poi il
    // modulo. I dialoghi si ricordano per id: una rotazione o la morte del
    // processo non li chiudono, né buttano il perché scritto.
    var sceltaRegolaAperta by rememberSaveable { mutableStateOf(false) }
    var regolaDaProporreId by rememberSaveable { mutableStateOf<Long?>(null) }
    var daRitirareId by rememberSaveable { mutableStateOf<Long?>(null) }
    // L'ultima copia vista di quello che un dialogo ha aperto: se intanto
    // sparisce dalla lista (rilettura), il dialogo resta e dice perché.
    val regoleViste = remember { HashMap<Long, Regola>() }
    val proposteViste = remember { HashMap<Long, Proposta>() }
    val regolaDaProporre = regolaDaProporreId?.let { id -> stato.regoleDiQui.firstOrNull { it.id == id } ?: regoleViste[id] }
    val daRitirare = daRitirareId?.let { id -> stato.inviate.firstOrNull { it.id == id } ?: proposteViste[id] }
    fun proponiSu(regola: Regola) {
        regoleViste[regola.id] = regola
        vm.dimenticaEsitoProposta()
        regolaDaProporreId = regola.id
    }
    fun chiudiProposta() {
        regolaDaProporreId = null
        vm.dimenticaEsitoProposta()
    }

    LifecycleResumeEffect(Unit) {
        vm.aggiorna()
        onPauseOrDispose { }
    }

    val context = LocalContext.current
    val messaggioAccettata = stringResource(R.string.proposta_accettata_ok)
    val messaggioRifiutata = stringResource(R.string.proposta_rifiutata_ok)
    val messaggioNonPendente = stringResource(R.string.proposta_non_pendente)
    val messaggioErrore = stringResource(R.string.proposta_errore)
    LaunchedEffect(stato.evento) {
        when (val evento = stato.evento) {
            is ProposteViewModel.Evento.Accettata -> {
                // (v3) Accettata da qui una proposta su un altro dispositivo: si dice quale.
                val su = stato.regole.firstOrNull { it.id == evento.regolaId }
                    ?.let { TestoDispositivi.etichetta(it, stato.contesto, paroleDispositivo(context)) }
                snackbarHostState.showSnackbar(
                    su?.let { context.getString(R.string.proposta_accettata_ok_su, it) } ?: messaggioAccettata,
                )
            }
            is ProposteViewModel.Evento.Rifiutata -> snackbarHostState.showSnackbar(messaggioRifiutata)
            is ProposteViewModel.Evento.NonPiuPendente ->
                snackbarHostState.showSnackbar(messaggioNonPendente)
            is ProposteViewModel.Evento.Errore -> snackbarHostState.showSnackbar(messaggioErrore)
            is ProposteViewModel.Evento.PropostaMandata -> {
                val moduloAperto = regolaDaProporreId != null
                if (ProposteDelFiglio.chiudeIlModulo(evento.esito)) {
                    // Arrivata: il modulo si chiude e lo si dice.
                    regolaDaProporreId = null
                    snackbarHostState.showSnackbar(
                        testoEsitoProposta(context, evento.esito),
                        duration = SnackbarDuration.Long,
                    )
                } else if (!moduloAperto) {
                    // Il modulo era già chiuso quando è arrivata la risposta.
                    snackbarHostState.showSnackbar(
                        testoEsitoProposta(context, evento.esito),
                        duration = SnackbarDuration.Long,
                    )
                }
                // Con il modulo aperto l'esito lo dice il modulo (stato.esitoProposta).
            }
            is ProposteViewModel.Evento.Ritiro -> {
                daRitirareId = null
                snackbarHostState.showSnackbar(testoRitiro(context, evento.esito), duration = SnackbarDuration.Long)
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
        floatingActionButton = {
            // (0.10) Il modo per chiedere qualcosa al genitore, sempre in vista.
            if (puoProporre(stato)) {
                ExtendedFloatingActionButton(
                    onClick = {
                        // Una regola sola: niente da scegliere, si va al modulo.
                        val unica = stato.regoleDiQui.singleOrNull()
                        if (unica != null && unica.id !in stato.inAttesaPerRegola) {
                            proponiSu(unica)
                        } else {
                            sceltaRegolaAperta = true
                        }
                    },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.proposte_nuova)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                stato.caricamento && stato.proposte.isEmpty() -> Centro {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.proposte_caricamento),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = Spazi.s),
                        )
                    }
                }

                stato.configurazioneMancante -> Centro {
                    TestoCentrato(stringResource(R.string.regole_config_mancante))
                }

                stato.errore && stato.proposte.isEmpty() -> Centro {
                    TestoCentrato(stringResource(R.string.proposte_errore_lettura))
                }

                else -> ContenutoProposte(
                    stato = stato,
                    onRispondi = { id, esito, motivazione -> vm.rispondi(id, esito, motivazione) },
                    onRitira = {
                        proposteViste[it.id] = it
                        daRitirareId = it.id
                    },
                )
            }
        }
    }

    if (sceltaRegolaAperta) {
        DialogoSceltaRegola(
            regole = stato.regoleDiQui,
            occupate = stato.inAttesaPerRegola.keys,
            onScegli = {
                sceltaRegolaAperta = false
                proponiSu(it)
            },
            onAnnulla = { sceltaRegolaAperta = false },
        )
    }

    regolaDaProporre?.let { regola ->
        DialogoProposta(
            regola = regola,
            invioInCorso = stato.invioInCorso,
            // Eliminare l'ultima regola del patto non si può, nemmeno d'accordo.
            eliminabile = stato.totaleFiglio > 1,
            esito = stato.esitoProposta,
            onAnnulla = { chiudiProposta() },
            onManda = { cambio, perche -> vm.proponi(cambio, perche) },
        )
    }

    daRitirare?.let { proposta ->
        AlertDialog(
            onDismissRequest = { daRitirareId = null },
            title = { Text(stringResource(R.string.proposta_ritira_titolo)) },
            text = { Text(stringResource(R.string.proposta_ritira_testo)) },
            confirmButton = {
                Button(enabled = !stato.invioInCorso, onClick = { vm.ritira(proposta.id) }) {
                    Text(stringResource(R.string.proposta_ritira))
                }
            },
            dismissButton = {
                TextButton(onClick = { daRitirareId = null }) { Text(stringResource(R.string.azione_annulla)) }
            },
        )
    }
}

/** (0.10) "Nuova proposta" si vede: collegati, e con almeno una regola su cui proporre da qui. */
private fun puoProporre(stato: ProposteViewModel.StatoProposte): Boolean =
    !stato.configurazioneMancante && stato.regoleDiQui.isNotEmpty()

@Composable
private fun ContenutoProposte(
    stato: ProposteViewModel.StatoProposte,
    onRispondi: (Long, String, String?) -> Unit,
    onRitira: (Proposta) -> Unit,
) {
    // (0.10) "Da decidere" sono solo quelle del genitore (le tue aspettano
    // lui), e nessuna manca: la lista più le pendenti del patto (stato.daDecidere).
    val daDecidere = stato.daDecidere
    val storia = ProposteDelFiglio.storia(stato.proposte)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // In fondo lo spazio di "Nuova proposta", perché non copra l'ultima card.
        contentPadding = PaddingValues(
            start = Spazi.l + Spazi.xs,
            end = Spazi.l + Spazi.xs,
            top = Spazi.l + Spazi.xs,
            bottom = 88.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(Spazi.l),
    ) {
        if (stato.errore) {
            item { BannerDatiVecchi(stato.aggiornateIl) }
        }

        item { TitoloSezione(stringResource(R.string.proposte_sezione_pendenti)) }
        if (daDecidere.isEmpty()) {
            // Nessuna proposta in attesa è una buona notizia: si scrive.
            item {
                RigaVuota(Icons.Outlined.CheckCircle, stringResource(R.string.proposte_pendenti_vuoto))
            }
        } else {
            items(daDecidere, key = { "pendente-${it.id}" }) { proposta ->
                CardPropostaPendente(proposta, stato.regole, stato.contesto, stato.invioInCorso, onRispondi)
            }
        }

        // (0.10) Le tue proposte che aspettano il genitore.
        item { TitoloSezione(stringResource(R.string.proposte_sezione_tue)) }
        if (stato.inviate.isEmpty()) {
            // "Tocca «Nuova proposta»" solo se il pulsante c'è.
            item {
                val vuoto = stringResource(R.string.proposte_tue_vuoto)
                RigaVuota(
                    Icons.Outlined.Info,
                    if (puoProporre(stato)) "$vuoto ${stringResource(R.string.proposte_tue_come)}" else vuoto,
                )
            }
        } else {
            items(stato.inviate, key = { "tua-${it.id}" }) { proposta ->
                CardPropostaTua(proposta, stato.regole, stato.contesto, stato.invioInCorso) { onRitira(proposta) }
            }
        }

        item { TitoloSezione(stringResource(R.string.proposte_sezione_storia)) }
        if (storia.isEmpty()) {
            item { RigaVuota(Icons.Outlined.Info, stringResource(R.string.proposte_storia_vuota)) }
        } else {
            items(storia, key = { "storia-${it.id}" }) { proposta ->
                CardPropostaStorica(proposta, stato.regole.firstOrNull { it.id == proposta.regolaId }, stato.contesto)
            }
        }
    }
}

@Composable
private fun CardPropostaPendente(
    proposta: Proposta,
    regole: List<Regola>,
    contesto: ContestoDispositivi,
    invioInCorso: Boolean,
    onRispondi: (Long, String, String?) -> Unit,
) {
    var motivazione by rememberSaveable(proposta.id) { mutableStateOf("") }
    // Chiusa di default: un campo sempre aperto suggerisce che serva
    // giustificarsi per rispondere. Non serve.
    var motivazioneAperta by rememberSaveable(proposta.id) { mutableStateOf(false) }
    val motivazionePulita = { motivazione.trim().ifBlank { null } }

    // Su QUALE regola: il ragazzo deve sapere cosa accetta. Regola non
    // trovata (copia vecchia) = resta il solo confronto, com'era. (v3) Se la
    // regola è di un altro dispositivo, la frase dice quale: "Ora sul computer: …".
    val context = LocalContext.current
    LocalConfiguration.current
    val racconto = raccontoProposta(
        context = context,
        confronto = proposta.confronto,
        oggetto = TestoProposta.oggetto(
            proposta.regolaId,
            proposta.direzione,
            proposta.parametriProposti,
            regole,
        ),
        contesto = contesto,
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spazi.l + Spazi.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.proposta_dal_genitore),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TagDirezione(proposta.direzione)
            }
            // Il confronto autoritativo del server, IN EVIDENZA: è la frase che
            // dice cosa cambierebbe rispetto ad ora (per l'eliminazione, la
            // stessa frase con dentro la regola che uscirebbe).
            Text(
                text = racconto.titolo,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = Spazi.s),
            )
            // Sotto, la regola com'è ora e come diventa se accetti.
            racconto.righe.forEachIndexed { indice, riga ->
                Text(
                    text = riga,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = if (indice == 0) Spazi.s else 0.dp),
                )
            }
            proposta.motivazione?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = stringResource(R.string.proposta_motivazione_genitore, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spazi.xs),
                )
            }
            istanteServer(proposta.tsServer)?.let {
                Text(
                    text = dataOraLocale(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spazi.xs),
                )
            }

            Spacer(modifier = Modifier.height(Spazi.s))
            if (motivazioneAperta) {
                OutlinedTextField(
                    value = motivazione,
                    onValueChange = { motivazione = it },
                    label = { Text(stringResource(R.string.proposta_campo_motivazione)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(Spazi.s))
            } else {
                TextButton(
                    onClick = { motivazioneAperta = true },
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(stringResource(R.string.proposta_aggiungi_motivazione))
                }
            }
            Row {
                Button(
                    enabled = !invioInCorso,
                    onClick = {
                        onRispondi(proposta.id, EsitiRisposta.ACCETTA, motivazionePulita())
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.proposta_accetta))
                }
                Spacer(modifier = Modifier.width(Spazi.s))
                OutlinedButton(
                    enabled = !invioInCorso,
                    onClick = {
                        onRispondi(proposta.id, EsitiRisposta.RIFIUTA, motivazionePulita())
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.proposta_rifiuta))
                }
            }
        }
    }
}

/**
 * (0.10) Una tua proposta che aspetta il genitore: cosa hai chiesto (il
 * confronto del server, ricalcolato sulla regola di adesso), su quale regola,
 * il tuo perché, quando. "Ritira" chiede conferma prima di partire.
 */
@Composable
private fun CardPropostaTua(
    proposta: Proposta,
    regole: List<Regola>,
    contesto: ContestoDispositivi,
    invioInCorso: Boolean,
    onRitira: () -> Unit,
) {
    val context = LocalContext.current
    LocalConfiguration.current
    // Il confronto di un'eliminazione è scritto per il genitore ("propone di
    // eliminare la regola"): qui si dice "Eliminare la regola", con la regola se c'è.
    val eliminazione = TestoProposta.eliminazione(proposta.direzione, proposta.parametriProposti)
    val racconto = raccontoProposta(
        context = context,
        confronto = if (eliminazione) stringResource(R.string.proposta_tua_eliminare_semplice) else proposta.confronto,
        oggetto = TestoProposta.oggetto(proposta.regolaId, proposta.direzione, proposta.parametriProposti, regole),
        contesto = contesto,
        parole = paroleTuaProposta(context),
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spazi.l + Spazi.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.proposta_tua),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TagDirezione(proposta.direzione)
            }
            Text(
                text = racconto.titolo,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = Spazi.s),
            )
            // La regola com'è ora e come diventa se il genitore accetta.
            racconto.righe.forEachIndexed { indice, riga ->
                Text(
                    text = riga,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = if (indice == 0) Spazi.xs else 0.dp),
                )
            }
            proposta.motivazione?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = stringResource(R.string.proposta_tuo_perche, it.trim()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spazi.xs),
                )
            }
            val quando = istanteServer(proposta.tsServer)?.let { dataOraLocale(it) }
            Text(
                text = quando?.let { stringResource(R.string.proposta_tua_in_attesa, it) }
                    ?: stringResource(R.string.proposta_stato_pendente),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spazi.xs),
            )
            Spacer(modifier = Modifier.height(Spazi.s))
            OutlinedButton(enabled = !invioInCorso, onClick = onRitira) {
                Text(stringResource(R.string.proposta_ritira))
            }
        }
    }
}

/**
 * Una proposta chiusa, di chiunque sia. (0.10) In alto di chi era; sotto il
 * confronto, la regola e com'è finita, con il perché di chi ha proposto e di
 * chi ha risposto (TestoProposta.righeChiusa).
 */
@Composable
private fun CardPropostaStorica(proposta: Proposta, regola: Regola?, contesto: ContestoDispositivi) {
    val context = LocalContext.current
    LocalConfiguration.current
    val eliminazione = TestoProposta.eliminazione(proposta.direzione, proposta.parametriProposti)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spazi.l + Spazi.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        if (proposta.delFiglio) R.string.proposta_tua else R.string.proposta_dal_genitore,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TagDirezione(proposta.direzione)
            }
            // Il confronto di un'eliminazione è scritto per il genitore ("propone di
            // eliminare la regola"): per una tua si dice "Eliminare la regola".
            val confronto = when {
                !(proposta.delFiglio && eliminazione) -> proposta.confronto?.takeIf { it.isNotBlank() }
                regola != null -> stringResource(
                    R.string.proposta_tua_eliminare,
                    descrizioneRegolaConDispositivo(context, regola, contesto),
                )
                else -> stringResource(R.string.proposta_tua_eliminare_semplice)
            }
            confronto?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = Spazi.xs),
                )
            }
            // Su quale regola era, com'è adesso. Una regola eliminata non c'è
            // più nel patto: resta il solo confronto. (v3) Di un altro
            // dispositivo: "Regola sul computer: …".
            if (regola != null && !(proposta.delFiglio && eliminazione)) {
                val frase = descrizioneRegolaSenzaDispositivo(context, regola, contesto)
                val su = TestoDispositivi.etichetta(regola, contesto, paroleDispositivo(context))
                Text(
                    text = if (su == null) {
                        stringResource(R.string.proposta_regola, frase)
                    } else {
                        stringResource(R.string.proposta_regola_su, su, frase)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(modifier = Modifier.height(Spazi.xs))
            TestoProposta.righeChiusa(proposta, paroleStoria(context)).forEach { riga ->
                Text(
                    text = riga.testo,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (riga.tenue) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.padding(top = Spazi.xs),
                )
            }
            istanteServer(proposta.tsServer)?.let {
                Text(
                    text = dataOraLocale(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spazi.xs),
                )
            }
        }
    }
}

/**
 * (0.10) "Nuova proposta": su quale regola. Quelle di questo telefono e la
 * vita reale; una regola su cui aspetta già una proposta non si sceglie (il
 * server ne vuole una sola per regola) e lo dice.
 */
@Composable
private fun DialogoSceltaRegola(
    regole: List<Regola>,
    occupate: Set<Long>,
    onScegli: (Regola) -> Unit,
    onAnnulla: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onAnnulla,
        title = { Text(stringResource(R.string.proposte_scegli_regola_titolo)) },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                items(regole, key = { it.id }) { regola ->
                    val occupata = regola.id in occupate
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !occupata) { onScegli(regola) }
                            .padding(vertical = Spazi.m),
                    ) {
                        Text(
                            text = descrizioneRegola(regola.tipo, regola.parametri, leggibile = true),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (occupata) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        if (occupata) {
                            Text(
                                text = stringResource(R.string.proposte_regola_occupata),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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

/**
 * La direzione della proposta: tre azioni diverse, tre vestiti diversi (B9).
 * Stringe → ocra, allenta → verde del patto, eliminazione → neutro col bordo.
 * Nessuno dei tre è un colore del patto: quelli vivono solo nella striscia.
 */
@Composable
private fun TagDirezione(direzione: String?) {
    val schema = MaterialTheme.colorScheme
    val (testo, fondo, inchiostro) = when (direzione) {
        DirezioniProposta.STRINGE -> Triple(
            stringResource(R.string.proposta_tag_stringe),
            schema.tertiaryContainer,
            schema.onTertiaryContainer,
        )
        DirezioniProposta.ALLENTA -> Triple(
            stringResource(R.string.proposta_tag_allenta),
            schema.primaryContainer,
            schema.onPrimaryContainer,
        )
        DirezioniProposta.ELIMINA -> Triple(
            stringResource(R.string.proposta_tag_elimina),
            schema.surfaceVariant,
            schema.onSurfaceVariant,
        )
        else -> return
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = fondo,
        border = if (direzione == DirezioniProposta.ELIMINA) BorderStroke(1.dp, schema.outline) else null,
    ) {
        Text(
            text = testo,
            style = MaterialTheme.typography.labelSmall,
            color = inchiostro,
            modifier = Modifier.padding(horizontal = Spazi.s, vertical = 3.dp),
        )
    }
}
