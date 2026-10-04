package eu.stgm.pactum.figlio.ui

import eu.stgm.pactum.design.RigaStato
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
    /**
     * (0.15) Da quale schermata ([OriginiCollegamento]): il modulo prende solo
     * gli esiti dei collegamenti partiti da lì (anche prima di una rotazione, o
     * dalle stesse Impostazioni chiuse a metà), mai quello lasciato da un'altra.
     */
    origine: String,
    /** Collegato: con l'esito da dire ancora, perché il modulo si può chiudere. */
    onCollegato: (AvvisoCollegamento) -> Unit,
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

    // L'esito di un collegamento partito da QUESTA schermata (anche prima di
    // una rotazione, o dalle stesse Impostazioni chiuse a metà). Quello lasciato
    // da un'altra (il passo Collega del primo avvio, sparito appena salvato il
    // collegamento) non si prende: non chiude il modulo e non dice cose vecchie.
    // Il messaggio resta in rememberSaveable: sopravvive a una rotazione dopo.
    LaunchedEffect(stato) {
        val finito = stato as? CorsaCollegamento.Stato.Finito ?: return@LaunchedEffect
        if (!OriginiCollegamento.eDi(finito.numero, origine)) return@LaunchedEffect
        val letto = leggiEsitoCollegamento(context, finito.esito)
        if (letto.indirizzoNonValido) urlNonValido = true
        if (letto.collegato) {
            val attuale = impostazioni.leggiConfigurazione()
            server = attuale.serverUrl
            token = attuale.token
            codice = ""
        }
        messaggio = letto.testo
        Collegamento.consuma(finito)
        if (letto.collegato) onCollegatoAttuale(AvvisoCollegamento(letto.testo.orEmpty(), letto.cambioDispositivo))
    }

    val pronto = server.isNotBlank() && Abbinamento.codiceCompleto(codice) && !inCorso
    val collega: () -> Unit = {
        messaggio = null
        if (Collegamento.avviaConCodice(context, server, codice)) {
            OriginiCollegamento.segna(Collegamento.ultimoAvviato, origine)
        }
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
        messaggio?.let { RigaStato(it) }

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
                    if (Collegamento.avviaConCodiceLungo(context, server, token)) {
                        OriginiCollegamento.segna(Collegamento.ultimoAvviato, origine)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.collega_codice_lungo_salva))
            }
        }
    }
}

/**
 * "Collegato come «Telefono di Luca»", finché si sa chi è questo telefono.
 * [alternativa] = cosa dire quando non si sa (un collegamento col codice
 * lungo): null = niente.
 */
@Composable
fun RigaCollegatoCome(modifier: Modifier = Modifier, alternativa: String? = null) {
    val context = LocalContext.current
    val impostazioni = remember { Impostazioni(context.applicationContext) }
    val identita by impostazioni.identita.collectAsState(initial = null)
    val testo = identita?.let { testoCollegatoCome(context, it) } ?: alternativa ?: return
    Text(
        text = testo,
        style = MaterialTheme.typography.bodyLarge,
        modifier = modifier,
    )
}

/** "Collegato come: Telefono di Andrea"; null se non si sa ancora chi è questo telefono. */
fun testoCollegatoCome(context: Context, identita: Identita): String? =
    collegatoCome(
        nomeDispositivo = identita.dispositivo?.nome,
        nomeFiglio = identita.figlio?.nome,
        formato = context.getString(R.string.collegato_come_formato),
    )?.let { context.getString(R.string.collegato_come, it) }

/** (0.15) L'esito di un collegamento riuscito, da dire ancora dopo che il modulo si chiude. */
data class AvvisoCollegamento(val testo: String, val cambioDispositivo: Boolean)

/** (0.15) Un esito letto per la schermata: la frase, e cosa cambia. */
data class EsitoLetto(
    val testo: String?,
    val collegato: Boolean,
    val cambioDispositivo: Boolean = false,
    val indirizzoNonValido: Boolean = false,
)

/**
 * (0.15) La frase di un esito, come la diceva il modulo nella 0.14: "Codice
 * lungo salvato", "Collegamento riuscito.", l'avviso del dispositivo diverso,
 * o perché non è riuscito. L'indirizzo scritto male lo dice il campo (testo null).
 */
fun leggiEsitoCollegamento(context: Context, esito: EsitoCollegamento): EsitoLetto = when (esito) {
    EsitoCollegamento.IndirizzoNonValido -> EsitoLetto(testo = null, collegato = false, indirizzoNonValido = true)
    EsitoCollegamento.CodiceLungoSalvato -> EsitoLetto(context.getString(R.string.impostazioni_salvate), collegato = true)
    is EsitoCollegamento.ConCodice -> if (esito.esito is EsitoAbbinamento.Collegato) {
        // Il "Collegato come «…»" lo dice la riga in cima: qui basta l'esito.
        // Se però il telefono è passato a un ALTRO dispositivo, lo si dice chiaro.
        EsitoLetto(
            testo = esito.cambio?.let { testoCambioDispositivo(it, paroleCambioDispositivo(context)) }
                ?: context.getString(R.string.collega_riuscito),
            collegato = true,
            cambioDispositivo = esito.cambio != null,
        )
    } else {
        EsitoLetto(testoEsitoAbbinamento(context, esito.esito), collegato = false)
    }
}

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
