package eu.stgm.pactum.figlio.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.dati.Abbinamento
import eu.stgm.pactum.figlio.dati.Collegamento
import eu.stgm.pactum.figlio.dati.CorsaCollegamento
import eu.stgm.pactum.figlio.dati.EsitoAbbinamento
import eu.stgm.pactum.figlio.dati.EsitoCollegamento
import eu.stgm.pactum.figlio.dati.Identita
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.dati.TipiDispositivo

/**
 * Il collegamento di questo telefono al patto (contratto v3, "Abbinamento con
 * codice"): l'indirizzo del server e il codice di 6 cifre che il genitore
 * genera dalla sua app. Dietro "Hai un codice lungo?" resta il vecchio campo:
 * i telefoni collegati prima della v3 continuano col loro codice.
 *
 * Lo usano il primo avvio (PrimaRegolaScreen) e le Impostazioni. Gli esiti del
 * server si dicono in una riga neutra, mai in rosso: un codice scaduto non è
 * un errore del ragazzo. Il rosso di sistema resta al solo indirizzo scritto
 * male (validazione del campo).
 *
 * Il collegamento non gira qui ma in Collegamento, fuori dalla schermata: una
 * rotazione, una pausa o l'uscita dalle Impostazioni non lo interrompono più
 * (il codice sarebbe bruciato). Questa schermata ne segue lo stato e ne prende
 * l'esito, anche se è stata ricreata nel frattempo.
 */
@Composable
fun ModuloCollegamento(
    onCollegato: () -> Unit,
    modifier: Modifier = Modifier,
    /** Già collegato: il pulsante dice "Collega con un codice nuovo". */
    giaCollegato: Boolean = false,
) {
    val context = LocalContext.current
    val impostazioni = remember { Impostazioni(context.applicationContext) }

    var server by rememberSaveable { mutableStateOf("") }
    var codice by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    var caricato by rememberSaveable { mutableStateOf(false) }
    var lungoAperto by rememberSaveable { mutableStateOf(false) }
    var urlNonValido by rememberSaveable { mutableStateOf(false) }
    var messaggio by rememberSaveable { mutableStateOf<String?>(null) }
    val stato by Collegamento.stato.collectAsState()
    val inCorso = stato is CorsaCollegamento.Stato.InCorso
    val onCollegatoAttuale by rememberUpdatedState(onCollegato)

    LaunchedEffect(Unit) {
        if (!caricato) {
            val configurazione = impostazioni.leggiConfigurazione()
            server = configurazione.serverUrl
            token = configurazione.token
            caricato = true
        }
    }

    // L'esito di un collegamento finito, anche partito da un'altra schermata (la
    // stessa prima di una rotazione, o le Impostazioni chiuse a metà). Il
    // messaggio resta in rememberSaveable: sopravvive a una rotazione dopo.
    LaunchedEffect(stato) {
        val finito = stato as? CorsaCollegamento.Stato.Finito ?: return@LaunchedEffect
        var collegato = false
        when (val esito = finito.esito) {
            EsitoCollegamento.IndirizzoNonValido -> urlNonValido = true
            EsitoCollegamento.CodiceLungoSalvato -> {
                server = impostazioni.leggiConfigurazione().serverUrl
                messaggio = context.getString(R.string.impostazioni_salvate)
                collegato = true
            }
            is EsitoCollegamento.ConCodice -> if (esito.esito is EsitoAbbinamento.Collegato) {
                val attuale = impostazioni.leggiConfigurazione()
                server = attuale.serverUrl
                token = attuale.token
                codice = ""
                // Il "Collegato come: …" lo dice la riga in cima (RigaCollegatoCome):
                // qui sotto basta l'esito, senza ripeterlo. Se però il telefono è
                // passato a un ALTRO dispositivo, lo si dice chiaro.
                messaggio = esito.cambio
                    ?.let { testoCambioDispositivo(it, paroleCambioDispositivo(context)) }
                    ?: context.getString(R.string.collega_riuscito)
                collegato = true
            } else {
                messaggio = testoEsitoAbbinamento(context, esito.esito)
            }
        }
        Collegamento.consuma(finito)
        if (collegato) onCollegatoAttuale()
    }

    val pronto = server.isNotBlank() && Abbinamento.codiceCompleto(codice) && !inCorso
    val collega: () -> Unit = {
        messaggio = null
        Collegamento.avviaConCodice(context, server, codice)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spazi.m)) {
        OutlinedTextField(
            value = server,
            onValueChange = {
                server = it
                urlNonValido = false
            },
            label = { Text(stringResource(R.string.impostazioni_server_url)) },
            placeholder = { Text(stringResource(R.string.impostazioni_server_url_esempio)) },
            isError = urlNonValido,
            supportingText = if (urlNonValido) {
                { Text(stringResource(R.string.impostazioni_url_non_valido)) }
            } else {
                null
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = codice,
            // Solo cifre, al massimo 6: "483 920" incollato diventa "483920".
            onValueChange = {
                codice = Abbinamento.soloCifre(it)
                messaggio = null
            },
            label = { Text(stringResource(R.string.collega_codice)) },
            supportingText = { Text(stringResource(R.string.collega_spiegazione)) },
            singleLine = true,
            // NumberPassword: tastierino numerico senza suggerimenti; le cifre restano visibili.
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (pronto) collega() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = collega,
            enabled = pronto,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (inCorso) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(
                    stringResource(if (giaCollegato) R.string.collega_nuovo_codice else R.string.collega_azione),
                )
            }
        }
        messaggio?.let { RigaNeutra(it) }

        // Il vecchio codice lungo: per chi era già collegato prima dei codici di 6 cifre.
        TextButton(
            onClick = { lungoAperto = !lungoAperto },
            contentPadding = PaddingValues(0.dp),
        ) {
            Text(stringResource(R.string.collega_codice_lungo_apri))
        }
        if (lungoAperto) {
            Text(
                text = stringResource(R.string.collega_codice_lungo_spiegazione),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text(stringResource(R.string.impostazioni_token)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                enabled = server.isNotBlank() && token.isNotBlank() && !inCorso,
                onClick = {
                    messaggio = null
                    Collegamento.avviaConCodiceLungo(context, server, token)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.collega_codice_lungo_salva))
            }
        }
    }
}

/** "Collegato come: Telefono di Andrea", finché si sa chi è questo telefono. */
@Composable
fun RigaCollegatoCome(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val impostazioni = remember { Impostazioni(context.applicationContext) }
    val identita by impostazioni.identita.collectAsState(initial = null)
    val testo = identita?.let { testoCollegatoCome(context, it) } ?: return
    Text(
        text = testo,
        style = MaterialTheme.typography.bodyLarge,
        modifier = modifier,
    )
}

/**
 * Un esito in una riga neutra (§3.1: fuori dalla striscia niente rosso, e
 * `error` resta alla validazione dei campi). Stessa veste di "Dati non aggiornati".
 */
@Composable
internal fun RigaNeutra(testo: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .padding(horizontal = Spazi.m, vertical = Spazi.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = testo,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Collegato come: Telefono di Andrea"; null se non si sa ancora chi è questo telefono. */
fun testoCollegatoCome(context: Context, identita: Identita): String? =
    collegatoCome(
        nomeDispositivo = identita.dispositivo?.nome,
        nomeFiglio = identita.figlio?.nome,
        formato = context.getString(R.string.collegato_come_formato),
    )?.let { context.getString(R.string.collegato_come, it) }

/** Gli esiti dell'abbinamento, in frasi chiare. */
fun testoEsitoAbbinamento(context: Context, esito: EsitoAbbinamento): String = when (esito) {
    is EsitoAbbinamento.Collegato -> context.getString(R.string.collega_riuscito)
    EsitoAbbinamento.CodiceNonValido -> context.getString(R.string.collega_codice_non_valido)
    // Senza il numero dal server, i 10 minuti del contratto.
    is EsitoAbbinamento.TroppiTentativi -> context.getString(
        R.string.collega_troppi_tentativi,
        testoAttesa(context, esito.riprovaTraSecondi ?: ATTESA_TROPPI_TENTATIVI_S),
    )
    // (v3.1) Il codice di un computer scritto sul telefono: il server non l'ha consumato.
    is EsitoAbbinamento.TipoNonCorrispondente -> context.getString(
        if (esito.tipoAtteso == TipiDispositivo.COMPUTER) R.string.collega_tipo_computer else R.string.collega_tipo_altro,
    )
    EsitoAbbinamento.ServerSenzaCodici -> context.getString(R.string.collega_server_senza_codici)
    EsitoAbbinamento.SenzaRete -> context.getString(R.string.collega_senza_rete)
    EsitoAbbinamento.Errore -> context.getString(R.string.collega_errore)
}

private const val ATTESA_TROPPI_TENTATIVI_S = 10L * 60
