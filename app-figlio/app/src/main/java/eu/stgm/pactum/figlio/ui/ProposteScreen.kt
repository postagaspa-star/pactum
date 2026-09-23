package eu.stgm.pactum.figlio.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.StatiProposta

/** Le proposte del genitore: il confronto in evidenza, la decisione è tua. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProposteScreen(vm: ProposteViewModel = viewModel()) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

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
                    proposte = stato.proposte,
                    regole = stato.regole,
                    contesto = stato.contesto,
                    invioInCorso = stato.invioInCorso,
                    mostraErrore = stato.errore,
                    aggiornateIl = stato.aggiornateIl,
                    onRispondi = { id, esito, motivazione -> vm.rispondi(id, esito, motivazione) },
                )
            }
        }
    }
}

@Composable
private fun ContenutoProposte(
    proposte: List<Proposta>,
    regole: List<Regola>,
    contesto: ContestoDispositivi,
    invioInCorso: Boolean,
    mostraErrore: Boolean,
    aggiornateIl: Long?,
    onRispondi: (Long, String, String?) -> Unit,
) {
    val pendenti = proposte.filter { it.stato == StatiProposta.PENDENTE }
    val storia = proposte.filter { it.stato != StatiProposta.PENDENTE }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spazi.l + Spazi.xs),
        verticalArrangement = Arrangement.spacedBy(Spazi.l),
    ) {
        if (mostraErrore) {
            item { BannerDatiVecchi(aggiornateIl) }
        }

        item { TitoloSezione(stringResource(R.string.proposte_sezione_pendenti)) }
        if (pendenti.isEmpty()) {
            // Nessuna proposta in attesa è una buona notizia: si scrive.
            item {
                RigaVuota(Icons.Outlined.CheckCircle, stringResource(R.string.proposte_pendenti_vuoto))
            }
        } else {
            items(pendenti, key = { "pendente-${it.id}" }) { proposta ->
                CardPropostaPendente(proposta, regole, contesto, invioInCorso, onRispondi)
            }
        }

        item { TitoloSezione(stringResource(R.string.proposte_sezione_storia)) }
        if (storia.isEmpty()) {
            item { RigaVuota(Icons.Outlined.Info, stringResource(R.string.proposte_storia_vuota)) }
        } else {
            items(storia, key = { "storia-${it.id}" }) { proposta ->
                CardPropostaStorica(proposta, regole.firstOrNull { it.id == proposta.regolaId }, contesto)
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

@Composable
private fun CardPropostaStorica(proposta: Proposta, regola: Regola?, contesto: ContestoDispositivi) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spazi.l + Spazi.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = etichettaStatoProposta(proposta.stato),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TagDirezione(proposta.direzione)
            }
            proposta.confronto?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = Spazi.xs),
                )
            }
            // Su quale regola era, com'è adesso. Una regola eliminata non c'è
            // più nel patto: resta il solo confronto. (v3) Di un altro
            // dispositivo: "Regola sul computer: …".
            regola?.let {
                LocalConfiguration.current
                val frase = descrizioneRegolaSenzaDispositivo(context, it, contesto)
                val su = TestoDispositivi.etichetta(it, contesto, paroleDispositivo(context))
                Text(
                    text = if (su == null) {
                        stringResource(R.string.proposta_regola, frase)
                    } else {
                        stringResource(R.string.proposta_regola_su, su, frase)
                    },
                    style = MaterialTheme.typography.bodyMedium,
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
            proposta.risposta?.let { risposta ->
                Spacer(modifier = Modifier.height(Spazi.s))
                Text(
                    text = if (risposta.esito == EsitiRisposta.ACCETTA) {
                        stringResource(R.string.proposta_tua_risposta_accettata)
                    } else {
                        stringResource(R.string.proposta_tua_risposta_rifiutata)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                risposta.motivazione?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = stringResource(R.string.proposta_tua_motivazione, it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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

@Composable
private fun etichettaStatoProposta(stato: String): String = when (stato) {
    StatiProposta.PENDENTE -> stringResource(R.string.proposta_stato_pendente)
    StatiProposta.ACCETTATA -> stringResource(R.string.proposta_stato_accettata)
    StatiProposta.RIFIUTATA -> stringResource(R.string.proposta_stato_rifiutata)
    else -> stato
}
