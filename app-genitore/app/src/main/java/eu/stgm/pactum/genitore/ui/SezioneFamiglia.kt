package eu.stgm.pactum.genitore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.Dispositivo
import eu.stgm.pactum.genitore.dati.Figlio
import eu.stgm.pactum.genitore.dati.TipiDispositivo
import kotlinx.coroutines.delay
import java.time.Instant

// Impostazioni, sezione "Famiglia" (v3): i figli e i loro dispositivi. Qui il
// genitore aggiunge un figlio, gli cambia nome, aggiunge un telefono o un
// computer (e riceve il codice di 6 cifre per collegarlo), crea un codice nuovo
// o scollega un dispositivo. Scollegare è una REVOCA: il dispositivo smette di
// mandare dati, e niente di quello che ha registrato si cancella — lo dice la
// conferma, con queste parole.

/**
 * La sezione Famiglia, dentro la colonna delle Impostazioni. [indirizzoServer]
 * è l'indirizzo salvato, per dire dove si scarica il programma del computer;
 * [mostraMessaggio] porta gli esiti in basso (snackbar).
 */
@Composable
fun SezioneFamiglia(
    famigliaVm: FamigliaViewModel,
    indirizzoServer: String?,
    mostraMessaggio: (String) -> Unit,
) {
    val stato by famigliaVm.stato.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val p = parole()

    // Quale dialogo è aperto: si salvano gli id (una rotazione non li perde).
    var nuovoFiglio by rememberSaveable { mutableStateOf(false) }
    var rinominaId by rememberSaveable { mutableStateOf<Long?>(null) }
    var nuovoDispositivoPer by rememberSaveable { mutableStateOf<Long?>(null) }
    var scollegaId by rememberSaveable { mutableStateOf<Long?>(null) }

    // Gli esiti si dicono una volta: consumati subito, poi in basso.
    LaunchedEffect(stato.evento) {
        val evento = stato.evento ?: return@LaunchedEffect
        famigliaVm.consumaEvento()
        val messaggio = when (evento) {
            is FamigliaViewModel.Evento.Fatto -> context.getString(evento.messaggio)
            is FamigliaViewModel.Evento.Errore -> messaggioRifiutoFamiglia(p, evento.codice, evento.secondi)
        }
        mostraMessaggio(messaggio)
    }

    TitoloSezione(stringResource(R.string.famiglia_titolo))
    Text(
        text = stringResource(R.string.famiglia_descrizione),
        style = MaterialTheme.typography.bodyMedium,
    )

    when {
        stato.configurazioneMancante -> RigaVuota(stringResource(R.string.famiglia_config_mancante))

        stato.serverVecchio -> RigaVuota(stringResource(R.string.famiglia_server_vecchio))

        stato.figli.isEmpty() && stato.errore -> {
            RigaDatiVecchi(stringResource(R.string.famiglia_non_letta))
            OutlinedButton(onClick = { famigliaVm.aggiorna() }) {
                Text(stringResource(R.string.famiglia_riprova))
            }
        }

        stato.figli.isEmpty() -> RigaVuota(stringResource(R.string.famiglia_caricamento))

        else -> {
            // Una famiglia già in mano ma non riletta: si dice, non si finge fresca.
            if (stato.errore) RigaDatiVecchi(stringResource(R.string.famiglia_non_letta))
            stato.figli.forEach { figlio ->
                CardFiglio(
                    figlio = figlio,
                    occupato = stato.lavoroInCorso,
                    onRinomina = { rinominaId = figlio.id },
                    onAggiungiDispositivo = { nuovoDispositivoPer = figlio.id },
                    onNuovoCodice = { famigliaVm.nuovoCodice(it) },
                    onScollega = { scollegaId = it.id },
                )
            }
            OutlinedButton(
                onClick = { nuovoFiglio = true },
                enabled = !stato.lavoroInCorso,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.famiglia_aggiungi_figlio))
            }
        }
    }

    // --- I dialoghi -----------------------------------------------------------------

    if (nuovoFiglio) {
        DialogoNome(
            titolo = stringResource(R.string.famiglia_nuovo_figlio_titolo),
            etichetta = stringResource(R.string.famiglia_nome_figlio),
            iniziale = "",
            conferma = stringResource(R.string.azione_salva),
            onConferma = { nome ->
                nuovoFiglio = false
                famigliaVm.creaFiglio(nome)
            },
            onAnnulla = { nuovoFiglio = false },
        )
    }

    stato.figli.firstOrNull { it.id == rinominaId }?.let { figlio ->
        DialogoNome(
            titolo = stringResource(R.string.famiglia_rinomina_titolo, figlio.nome),
            etichetta = stringResource(R.string.famiglia_nome_figlio),
            iniziale = figlio.nome,
            conferma = stringResource(R.string.azione_salva),
            onConferma = { nome ->
                rinominaId = null
                famigliaVm.rinominaFiglio(figlio.id, nome)
            },
            onAnnulla = { rinominaId = null },
        )
    }

    stato.figli.firstOrNull { it.id == nuovoDispositivoPer }?.let { figlio ->
        DialogoNuovoDispositivo(
            figlio = figlio,
            onConferma = { nome, tipo ->
                nuovoDispositivoPer = null
                famigliaVm.aggiungiDispositivo(figlio.id, nome, tipo)
            },
            onAnnulla = { nuovoDispositivoPer = null },
        )
    }

    stato.figli.flatMap { it.dispositivi }.firstOrNull { it.id == scollegaId }?.let { dispositivo ->
        AlertDialog(
            onDismissRequest = { scollegaId = null },
            title = {
                Text(
                    stringResource(
                        R.string.scollega_titolo,
                        nomeDelDispositivo(p, dispositivo.nome, dispositivo.tipo),
                    ),
                )
            },
            text = { Text(stringResource(R.string.scollega_testo)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scollegaId = null
                        famigliaVm.scollega(dispositivo)
                    },
                ) {
                    Text(stringResource(R.string.scollega_conferma))
                }
            },
            dismissButton = {
                TextButton(onClick = { scollegaId = null }) {
                    Text(stringResource(R.string.azione_annulla))
                }
            },
        )
    }

    stato.codice?.let { codice ->
        DialogoCodice(
            codice = codice,
            indirizzoServer = indirizzoServer,
            occupato = stato.lavoroInCorso,
            onNuovoCodice = {
                val id = codice.dispositivoId ?: return@DialogoCodice
                famigliaVm.nuovoCodice(
                    Dispositivo(
                        id = id,
                        nome = codice.nomeDispositivo,
                        tipo = codice.tipo,
                        abbinato = codice.ricollegamento,
                    ),
                )
            },
            onChiudi = { famigliaVm.chiudiCodice() },
        )
    }
}

/** Un figlio: il nome (rinominabile), i suoi dispositivi, e "Aggiungi un dispositivo". */
@Composable
private fun CardFiglio(
    figlio: Figlio,
    occupato: Boolean,
    onRinomina: () -> Unit,
    onAggiungiDispositivo: () -> Unit,
    onNuovoCodice: (Dispositivo) -> Unit,
    onScollega: (Dispositivo) -> Unit,
) {
    CardContenuto {
        Column(modifier = Modifier.padding(Spazi.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = figlio.nome.ifBlank { stringResource(R.string.figlio_senza_nome) },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRinomina, enabled = !occupato) {
                    Text(stringResource(R.string.famiglia_rinomina))
                }
            }
            if (figlio.dispositivi.isEmpty()) {
                RigaVuota(stringResource(R.string.famiglia_nessun_dispositivo))
            } else {
                ListaRighe(figlio.dispositivi) { dispositivo ->
                    RigaDispositivoFamiglia(
                        dispositivo = dispositivo,
                        occupato = occupato,
                        onNuovoCodice = { onNuovoCodice(dispositivo) },
                        onScollega = { onScollega(dispositivo) },
                    )
                }
            }
            Spacer(Modifier.height(Spazi.s))
            OutlinedButton(onClick = onAggiungiDispositivo, enabled = !occupato) {
                Text(stringResource(R.string.famiglia_aggiungi_dispositivo))
            }
        }
    }
}

/**
 * Un dispositivo nella Famiglia: che cos'è, se è collegato, e i due gesti.
 * "Nuovo codice" serve al primo collegamento non riuscito o a un telefono
 * reinstallato; uno scollegato non ha più gesti — la sua storia resta.
 */
@Composable
private fun RigaDispositivoFamiglia(
    dispositivo: Dispositivo,
    occupato: Boolean,
    onNuovoCodice: () -> Unit,
    onScollega: () -> Unit,
) {
    val p = parole()
    val stato = when {
        dispositivo.revocato -> stringResource(R.string.famiglia_stato_scollegato)
        !dispositivo.abbinato -> stringResource(R.string.famiglia_stato_da_collegare)
        else -> stringResource(R.string.famiglia_stato_collegato)
    }
    val versione = dispositivo.versioneApp?.takeIf { it.isNotBlank() && dispositivo.abbinato && !dispositivo.revocato }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spazi.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconaDispositivo(dispositivo.tipo)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = Spazi.m),
            ) {
                Text(
                    text = nomeDelDispositivo(p, dispositivo.nome, dispositivo.tipo),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = if (versione != null) {
                        stringResource(R.string.famiglia_stato_con_versione, stato, versione)
                    } else {
                        stato
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!dispositivo.revocato) {
            Row(modifier = Modifier.padding(start = 32.dp)) {
                TextButton(onClick = onNuovoCodice, enabled = !occupato) {
                    Text(stringResource(R.string.famiglia_nuovo_codice))
                }
                TextButton(onClick = onScollega, enabled = !occupato) {
                    Text(stringResource(R.string.famiglia_scollega))
                }
            }
        }
    }
}

/** Un nome da scrivere (figlio): 1-40 caratteri, controllati prima di mandarlo. */
@Composable
private fun DialogoNome(
    titolo: String,
    etichetta: String,
    iniziale: String,
    conferma: String,
    onConferma: (String) -> Unit,
    onAnnulla: () -> Unit,
) {
    var nome by rememberSaveable { mutableStateOf(iniziale) }
    val troppoLungo = nome.trim().length > LUNGHEZZA_MASSIMA_NOME
    AlertDialog(
        onDismissRequest = onAnnulla,
        title = { Text(titolo) },
        text = {
            OutlinedTextField(
                value = nome,
                onValueChange = { nome = it },
                label = { Text(etichetta) },
                singleLine = true,
                isError = troppoLungo,
                supportingText = {
                    Text(stringResource(R.string.famiglia_nome_lunghezza, LUNGHEZZA_MASSIMA_NOME))
                },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(onClick = { onConferma(nome.trim()) }, enabled = nomeValido(nome)) {
                Text(conferma)
            }
        },
        dismissButton = {
            TextButton(onClick = onAnnulla) { Text(stringResource(R.string.azione_annulla)) }
        },
    )
}

/**
 * Un dispositivo nuovo: il nome e che cosa è (telefono o computer). Il nome
 * parte dal tipo ("Telefono"): se il genitore sceglie "Computer" senza averlo
 * cambiato, segue il tipo.
 */
@Composable
private fun DialogoNuovoDispositivo(
    figlio: Figlio,
    onConferma: (String, String) -> Unit,
    onAnnulla: () -> Unit,
) {
    val p = parole()
    var tipo by rememberSaveable { mutableStateOf(TipiDispositivo.TELEFONO) }
    var nome by rememberSaveable { mutableStateOf(nomeDelDispositivo(p, null, TipiDispositivo.TELEFONO)) }
    val scegliTipo = { nuovo: String ->
        // Il nome segue il tipo solo finché è quello proposto.
        if (nome.trim() == nomeDelDispositivo(p, null, tipo)) nome = nomeDelDispositivo(p, null, nuovo)
        tipo = nuovo
    }
    val troppoLungo = nome.trim().length > LUNGHEZZA_MASSIMA_NOME
    AlertDialog(
        onDismissRequest = onAnnulla,
        title = {
            Text(
                stringResource(
                    R.string.famiglia_nuovo_dispositivo_titolo,
                    figlio.nome.ifBlank { stringResource(R.string.figlio_senza_nome) },
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spazi.s),
            ) {
                Text(
                    text = stringResource(R.string.famiglia_tipo_scegli),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                listOf(TipiDispositivo.TELEFONO, TipiDispositivo.COMPUTER).forEach { opzione ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = tipo == opzione, onClick = { scegliTipo(opzione) })
                        IconaDispositivo(opzione)
                        Text(
                            text = nomeDelDispositivo(p, null, opzione),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = Spazi.s),
                        )
                    }
                }
                OutlinedTextField(
                    value = nome,
                    onValueChange = { nome = it },
                    label = { Text(stringResource(R.string.famiglia_nome_dispositivo)) },
                    placeholder = { Text(stringResource(R.string.famiglia_nome_dispositivo_esempio)) },
                    singleLine = true,
                    isError = troppoLungo,
                    supportingText = {
                        Text(stringResource(R.string.famiglia_nome_lunghezza, LUNGHEZZA_MASSIMA_NOME))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConferma(nome.trim(), tipo) }, enabled = nomeValido(nome)) {
                Text(stringResource(R.string.famiglia_crea_codice))
            }
        },
        dismissButton = {
            TextButton(onClick = onAnnulla) { Text(stringResource(R.string.azione_annulla)) }
        },
    )
}

/**
 * Il codice di 6 cifre, in grande, col conto alla rovescia dei 15 minuti e la
 * frase che dice cosa farne. Per un computer, anche dove si scarica il
 * programma. Scaduto, lo dice e offre un codice nuovo. Il codice si può
 * selezionare e copiare (per mandarlo al figlio).
 */
@Composable
private fun DialogoCodice(
    codice: FamigliaViewModel.CodiceMostrato,
    indirizzoServer: String?,
    occupato: Boolean,
    onNuovoCodice: () -> Unit,
    onChiudi: () -> Unit,
) {
    var adesso by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(codice) {
        while (true) {
            adesso = Instant.now()
            delay(1_000)
        }
    }
    val rimasti = secondiRimasti(codice.scadenza, adesso)
    val scaduto = rimasti <= 0
    val scarica = indirizzoServer?.takeIf { it.isNotBlank() }?.let { "$it/scarica" }

    AlertDialog(
        onDismissRequest = onChiudi,
        title = { Text(stringResource(R.string.codice_titolo, codice.nomeDispositivo)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spazi.s),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.codice_scrivilo),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                SelectionContainer {
                    Text(
                        text = codiceADueGruppi(codice.codice),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (scaduto) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        textAlign = TextAlign.Center,
                    )
                }
                Text(
                    text = if (scaduto) {
                        stringResource(R.string.codice_scaduto)
                    } else {
                        stringResource(R.string.codice_scade_tra, testoContoAllaRovescia(rimasti))
                    },
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.codice_una_volta),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (scarica != null) {
                    Text(
                        text = stringResource(
                            if (codice.tipo == TipiDispositivo.COMPUTER) {
                                R.string.codice_dove_computer
                            } else {
                                R.string.codice_dove_telefono
                            },
                            scarica,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
                if (codice.ricollegamento) {
                    Text(
                        text = stringResource(R.string.codice_ricollegamento),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        },
        confirmButton = {
            if (scaduto && codice.dispositivoId != null) {
                Button(onClick = onNuovoCodice, enabled = !occupato) {
                    Text(stringResource(R.string.famiglia_nuovo_codice))
                }
            } else {
                Button(onClick = onChiudi) { Text(stringResource(R.string.codice_fatto)) }
            }
        },
        dismissButton = if (scaduto && codice.dispositivoId != null) {
            { TextButton(onClick = onChiudi) { Text(stringResource(R.string.codice_fatto)) } }
        } else {
            null
        },
    )
}
