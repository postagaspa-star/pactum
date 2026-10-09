package eu.stgm.pactum.figlio.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eu.stgm.pactum.design.Caricamento
import eu.stgm.pactum.design.CardNormale
import eu.stgm.pactum.design.FilaPulsanti
import eu.stgm.pactum.design.MenuAzioni
import eu.stgm.pactum.design.Pillola
import eu.stgm.pactum.design.RigaStato
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.design.Tono
import eu.stgm.pactum.design.TitoloSezione
import eu.stgm.pactum.design.VoceMenu
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.studio.ChiusureStudio
import eu.stgm.pactum.figlio.studio.ConfigStudio
import eu.stgm.pactum.figlio.studio.ContenutoStudio
import eu.stgm.pactum.figlio.studio.EsitoProposta
import eu.stgm.pactum.figlio.studio.GIORNI_STUDIO
import eu.stgm.pactum.figlio.studio.Orari
import eu.stgm.pactum.figlio.studio.OriginiStudio
import eu.stgm.pactum.figlio.studio.RegoleStudio
import eu.stgm.pactum.figlio.studio.StatiConfigStudio
import eu.stgm.pactum.figlio.studio.StudioSvolto
import eu.stgm.pactum.figlio.studio.TestoStudio
import java.time.LocalDate
import java.time.ZoneId
import eu.stgm.pactum.figlio.sessione.SessioneDefinita
import eu.stgm.pactum.figlio.studio.StudioDaSessione
import androidx.compose.runtime.remember

/**
 * (0.18, contratto v4.0, parte C) In cima alla scheda Sessioni: la Sessione
 * Studio. La configurazione approvata (giorni, orari, minimo, la lista delle
 * app), la proposta in attesa (con «Ritira»), «Proponi» / «Chiedi di
 * cambiarla», «Inizia adesso», lo storico e le versioni approvate.
 */
@Composable
fun SezioneStudio(
    stato: StudioViewModel.StatoStudioUi,
    adesso: StudioAdesso,
    vm: StudioViewModel,
    sessioni: List<SessioneDefinita> = emptyList(),
    onMessaggio: (String) -> Unit,
) {
    val context = LocalContext.current
    val m = adesso.memoria
    val config = m.config
    val zona = m.zona()
    var modulo by rememberSaveable { mutableStateOf(false) }
    var confermaAvvio by rememberSaveable { mutableStateOf(false) }
    var confermaRitiro by rememberSaveable { mutableStateOf(false) }
    // (0.22) Le app dello Studio dalla sessione «Studio» che c'è già: proposte da sole, una volta.
    val sessioneStudio = remember(sessioni) { StudioDaSessione.sessione(sessioni) }
    val daSessione = StudioDaSessione.proposta(config, sessioneStudio)
    var autoInviata by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(daSessione, sessioneStudio, stato.invioInCorso) {
        val proposta = daSessione ?: return@LaunchedEffect
        val sessione = sessioneStudio ?: return@LaunchedEffect
        if (stato.invioInCorso || autoInviata || StudioDaSessione.giaMandata(context, sessione)) return@LaunchedEffect
        if (config?.stato == StatiConfigStudio.RIFIUTATA) return@LaunchedEffect
        autoInviata = true
        vm.proponi(proposta)
    }

    LaunchedEffect(stato.evento) {
        val evento = stato.evento ?: return@LaunchedEffect
        vm.consumaEvento()
        when (evento) {
            is StudioViewModel.Evento.Proposta -> {
                if (evento.esito is EsitoProposta.Fatta) modulo = false
                val sessione = sessioneStudio
                if (autoInviata && sessione != null && evento.esito is EsitoProposta.Fatta && !evento.ritiro) {
                    StudioDaSessione.segnaMandata(context, sessione)
                    autoInviata = false
                    onMessaggio(context.getString(R.string.studio_da_sessione_mandata, sessione.nome))
                } else {
                    autoInviata = false
                    onMessaggio(testoEsitoProposta(context, evento.esito, evento.ritiro))
                }
            }
            is StudioViewModel.Evento.Avvio ->
                onMessaggio(evento.motivo?.let { testoNoAvvio(context, it) } ?: context.getString(R.string.studio_iniziato))
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spazi.m)) {
        TitoloSezione(stringResource(R.string.studio_titolo))
        when {
            // Un 404 con lo Studio già conosciuto (forse una rete Wi-Fi pubblica): resta quello che si sa.
            m.serverVecchio && !m.conosciuto -> RigaStato(stringResource(R.string.studio_server_da_aggiornare))
            m.scollegato -> RigaStato(stringResource(R.string.scollegato))
            !m.conosciuto || config == null -> if (stato.caricamento) {
                Caricamento(centrato = false)
            } else {
                RigaStato(stringResource(R.string.studio_non_letto))
            }
            else -> CardNormale {
                Column(verticalArrangement = Arrangement.spacedBy(Spazi.s)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = testoStatoConfig(context, config),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        if (adesso.studio != null) Pillola(stringResource(R.string.studio_pillola_in_corso), tono = Tono.Positivo)
                        MenuAzioni(
                            voci = listOfNotNull(
                                VoceMenu(stringResource(R.string.studio_storico), onClick = { vm.apriStorico() }),
                                VoceMenu(stringResource(R.string.studio_versioni), onClick = { vm.apriVersioni() }),
                                config.inAttesa?.let {
                                    VoceMenu(stringResource(R.string.studio_ritira), onClick = { confermaRitiro = true }, abilitata = !stato.invioInCorso)
                                },
                            ),
                            descrizione = stringResource(R.string.studio_altre_azioni),
                        )
                    }
                    config.approvata?.let { a -> RigheContenuto(context, a, zona) }
                    config.inAttesa?.let { p ->
                        HorizontalDivider()
                        Text(
                            text = stringResource(if (config.approvata != null) R.string.studio_cambio_in_attesa else R.string.studio_stato_in_attesa),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        RigheContenuto(context, p, zona, proposta = true)
                    }
                    config.motivazione?.let { Nota(stringResource(R.string.studio_motivazione, it)) }
                    if (adesso.studio != null) Nota(stringResource(R.string.studio_vai_a_oggi))
                    FilaPulsanti {
                        OutlinedButton(enabled = !stato.invioInCorso, onClick = {
                            vm.dimenticaEsiti()
                            modulo = true
                        }) {
                            Text(
                                stringResource(if (config.approvata == null && config.inAttesa == null) R.string.studio_proponi else R.string.studio_cambia),
                                maxLines = 1,
                            )
                        }
                        if (config.approvata != null && adesso.studio == null) {
                            Button(enabled = !stato.invioInCorso, onClick = { confermaAvvio = true }) {
                                Text(stringResource(R.string.studio_avvia_adesso), maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
        // (0.22) La lista del telefono è vuota e niente è in attesa: si dice, e si sceglie da qui.
        if (config != null && config.inAttesa == null && config.approvata?.app?.isEmpty() == true && m.conosciuto) {
            RigaStato(
                testo = stringResource(R.string.studio_lista_vuota_avviso),
                tono = Tono.Attenzione,
                azione = if (sessioneStudio != null) {
                    stringResource(R.string.studio_usa_sessione, sessioneStudio.nome)
                } else {
                    stringResource(R.string.studio_scegli_app)
                },
                onAzione = if (stato.invioInCorso) {
                    null
                } else if (daSessione != null) {
                    {
                        autoInviata = true
                        vm.proponi(daSessione)
                    }
                } else {
                    {
                        vm.dimenticaEsiti()
                        modulo = true
                    }
                },
            )
        }
        m.avvioRifiutato?.let { RigaStato(testoNoAvvio(context, it), tono = Tono.Attenzione) }
    }

    if (modulo && config != null) {
        val base = config.inAttesa ?: config.approvata ?: ContenutoStudio()
        DialogoProposta(
            // (0.22) Lista vuota: si parte dalle app della sessione «Studio», se c'è.
            iniziale = if (base.app.isEmpty() && sessioneStudio != null) {
                base.copy(app = sessioneStudio.app.take(RegoleStudio.APP_MASSIME), nomi = sessioneStudio.nomi)
            } else {
                base
            },
            invioInCorso = stato.invioInCorso,
            esito = stato.esitoModulo,
            onAnnulla = {
                modulo = false
                vm.dimenticaEsiti()
            },
            onManda = { vm.proponi(it) },
        )
    }
    if (confermaAvvio) {
        val minimi = config?.approvata?.minutiMinimi ?: 60
        AlertDialog(
            onDismissRequest = { confermaAvvio = false },
            title = { Text(stringResource(R.string.studio_avvia_conferma_titolo)) },
            text = { Text(stringResource(R.string.studio_avvia_conferma_testo, minimi)) },
            confirmButton = {
                Button(onClick = {
                    confermaAvvio = false
                    vm.avviaAMano()
                }) { Text(stringResource(R.string.studio_avvia_adesso)) }
            },
            dismissButton = { TextButton(onClick = { confermaAvvio = false }) { Text(stringResource(R.string.azione_annulla)) } },
        )
    }
    if (confermaRitiro) {
        AlertDialog(
            onDismissRequest = { confermaRitiro = false },
            title = { Text(stringResource(R.string.studio_ritira_titolo)) },
            text = { Text(stringResource(R.string.studio_ritira_testo)) },
            confirmButton = {
                Button(onClick = {
                    confermaRitiro = false
                    vm.ritira()
                }) { Text(stringResource(R.string.studio_ritira)) }
            },
            dismissButton = { TextButton(onClick = { confermaRitiro = false }) { Text(stringResource(R.string.azione_annulla)) } },
        )
    }
    if (stato.storico.aperto) DialogoStorico(stato.storico, zona, onAltri = { vm.altroStorico() }, onChiudi = { vm.chiudiStorico() })
    if (stato.versioni.aperte) DialogoVersioni(stato.versioni, zona, onChiudi = { vm.chiudiVersioni() })
}

/** «Approvata», «La tua proposta aspetta un genitore», «Non approvata», «Ancora nessuna». */
private fun testoStatoConfig(context: Context, c: ConfigStudio): String = when {
    c.approvata != null -> context.getString(R.string.studio_stato_approvata)
    c.stato == StatiConfigStudio.IN_ATTESA -> context.getString(R.string.studio_stato_in_attesa)
    c.stato == StatiConfigStudio.RIFIUTATA -> context.getString(R.string.studio_stato_rifiutata)
    else -> context.getString(R.string.studio_stato_nessuna)
}

/** Gli orari, la chiusura, le app, chi l'ha decisa, da quando valgono. */
@Composable
private fun RigheContenuto(context: Context, c: ContenutoStudio, zona: ZoneId, proposta: Boolean = false) {
    Text(
        text = stringResource(R.string.studio_orari, TestoStudio.giorni(c.giorni), c.inizio),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        text = stringResource(R.string.studio_condizioni, c.chiusuraMinima, c.minutiMinimi),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        text = if (c.app.isEmpty()) {
            stringResource(R.string.studio_lista_nessuna)
        } else {
            stringResource(R.string.studio_lista, elencoApp(context, c.app, c.nomi))
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    if (!proposta) {
        val dal = RegoleStudio.data(c.orariDal)
        if (dal != null && dal.isAfter(LocalDate.now(zona))) {
            Nota(stringResource(R.string.studio_orari_dal, "${dal.dayOfMonth}/${dal.monthValue}"))
        }
        Nota(c.decisaDa?.let { stringResource(R.string.studio_decisa_da, it) } ?: stringResource(R.string.studio_decisa_famiglia))
    }
}

/**
 * La proposta della configurazione: i giorni, l'ora di partenza, l'ora dopo
 * cui si chiude, i minuti di attività, la lista delle app del telefono.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DialogoProposta(
    iniziale: ContenutoStudio,
    invioInCorso: Boolean,
    esito: EsitoProposta?,
    onAnnulla: () -> Unit,
    onManda: (ContenutoStudio) -> Unit,
) {
    val context = LocalContext.current
    var giorni by rememberSaveable { mutableStateOf(iniziale.giorni.joinToString(",")) }
    var inizio by rememberSaveable { mutableStateOf(iniziale.inizio) }
    var chiusura by rememberSaveable { mutableStateOf(iniziale.chiusuraMinima) }
    var minuti by rememberSaveable { mutableStateOf(iniziale.minutiMinimi.toString()) }
    var app by rememberSaveable { mutableStateOf(iniziale.app.joinToString("\n")) }
    var nomi by rememberSaveable { mutableStateOf(iniziale.nomi.entries.joinToString("\n") { "${it.key}\t${it.value}" }) }
    var sceltaApp by rememberSaveable { mutableStateOf(false) }

    val listaGiorni = giorni.split(',').filter { it in GIORNI_STUDIO }
    val listaApp = app.split('\n').filter { it.isNotBlank() }
    val mappaNomi = nomi.split('\n').mapNotNull { r -> r.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
    val nuova = ContenutoStudio(
        giorni = listaGiorni,
        inizio = Orari.normalizza(inizio) ?: inizio,
        chiusuraMinima = Orari.normalizza(chiusura) ?: chiusura,
        minutiMinimi = minuti.trim().toIntOrNull() ?: -1,
        app = listaApp,
        nomi = mappaNomi.filterKeys { it in listaApp },
    )
    val errore = RegoleStudio.controllaProposta(nuova)

    AlertDialog(
        onDismissRequest = onAnnulla,
        title = { Text(stringResource(R.string.studio_proposta_titolo)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spazi.m)) {
                Text(stringResource(R.string.studio_campo_giorni), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spazi.xs)) {
                    GIORNI_STUDIO.forEach { g ->
                        ChipGiornoStudio(g, g in listaGiorni) {
                            val nuovi = if (g in listaGiorni) listaGiorni - g else listaGiorni + g
                            giorni = GIORNI_STUDIO.filter { it in nuovi }.joinToString(",")
                        }
                    }
                }
                // Uno sotto l'altro: affiancati, col carattere grande l'etichetta
                // «Si chiude dopo le» non ci sta nel campo stretto e la schermata
                // non smette più di ridisegnarsi (il fotografo restava fermo lì).
                OutlinedTextField(
                    value = inizio, onValueChange = { inizio = it.take(5) },
                    label = { Text(stringResource(R.string.studio_campo_inizio)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = errore == RegoleStudio.ErroreProposta.INIZIO,
                )
                OutlinedTextField(
                    value = chiusura, onValueChange = { chiusura = it.take(5) },
                    label = { Text(stringResource(R.string.studio_campo_chiusura)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = errore == RegoleStudio.ErroreProposta.CHIUSURA || errore == RegoleStudio.ErroreProposta.CHIUSURA_PRIMA,
                )
                OutlinedTextField(
                    value = minuti, onValueChange = { minuti = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text(stringResource(R.string.studio_campo_minuti)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = errore == RegoleStudio.ErroreProposta.MINUTI,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.studio_campo_app), style = MaterialTheme.typography.labelLarge)
                Text(
                    text = if (listaApp.isEmpty()) {
                        stringResource(R.string.studio_lista_nessuna)
                    } else {
                        elencoApp(context, listaApp, mappaNomi)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = { sceltaApp = true }) { Text(stringResource(R.string.studio_scegli_app)) }
                Nota(stringResource(R.string.studio_proposta_spiega))
                errore?.let { Text(testoErroreProposta(context, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                esito?.let { Text(testoEsitoProposta(context, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            Button(enabled = errore == null && !invioInCorso, onClick = { onManda(nuova) }) { Text(stringResource(R.string.studio_manda)) }
        },
        dismissButton = { TextButton(onClick = onAnnulla) { Text(stringResource(R.string.azione_annulla)) } },
    )

    if (sceltaApp) {
        DialogoSceltaAppSessione(
            iniziali = listaApp,
            nomiNoti = mappaNomi,
            onFatto = { scelte, etichette ->
                app = scelte.joinToString("\n")
                nomi = (mappaNomi + etichette).filterKeys { it in scelte }.entries.joinToString("\n") { "${it.key}\t${it.value}" }
                sceltaApp = false
            },
            onAnnulla = { sceltaApp = false },
        )
    }
}

fun testoErroreProposta(context: Context, e: RegoleStudio.ErroreProposta): String = context.getString(
    when (e) {
        RegoleStudio.ErroreProposta.GIORNI -> R.string.studio_errore_giorni
        RegoleStudio.ErroreProposta.INIZIO, RegoleStudio.ErroreProposta.CHIUSURA -> R.string.studio_errore_orario
        RegoleStudio.ErroreProposta.CHIUSURA_PRIMA -> R.string.studio_errore_chiusura_prima
        RegoleStudio.ErroreProposta.MINUTI -> R.string.studio_errore_minuti
        RegoleStudio.ErroreProposta.ORARI_IMPOSSIBILI -> R.string.studio_errore_impossibili
        RegoleStudio.ErroreProposta.TROPPE_APP -> R.string.studio_errore_troppe_app
    },
)

fun testoEsitoProposta(context: Context, e: EsitoProposta, ritiro: Boolean = false): String = when (e) {
    is EsitoProposta.Fatta -> context.getString(if (ritiro) R.string.studio_esito_ritirata else R.string.studio_esito_mandata)
    is EsitoProposta.No -> context.getString(
        when (e.errore) {
            "orari_impossibili" -> R.string.studio_errore_impossibili
            "niente_da_ritirare" -> R.string.studio_esito_niente_da_ritirare
            else -> R.string.studio_esito_no
        },
    )
    EsitoProposta.ServerVecchio -> context.getString(R.string.studio_server_da_aggiornare)
    EsitoProposta.Scollegato -> context.getString(R.string.scollegato)
    EsitoProposta.SenzaRete -> context.getString(R.string.studio_esito_senza_rete)
    EsitoProposta.Errore -> context.getString(R.string.studio_esito_errore)
}

/** Lo storico: per ogni Studio l'inizio, i minuti e la chiusura (da te, da un genitore col motivo, o «non chiuso»). */
@Composable
private fun DialogoStorico(storico: StudioViewModel.Storico, zona: ZoneId, onAltri: () -> Unit, onChiudi: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onChiudi,
        title = { Text(stringResource(R.string.studio_storico_titolo)) },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(Spazi.m)) {
                when {
                    storico.errore -> item { Text(stringResource(R.string.studio_storico_errore)) }
                    storico.svolte.isEmpty() && !storico.caricamento -> item { Text(stringResource(R.string.studio_storico_vuoto)) }
                }
                items(storico.svolte, key = { it.id }) { s -> RigaStorico(context, s, zona) }
                if (storico.caricamento) item { Caricamento(centrato = false) }
                if (storico.altre && !storico.caricamento) item { TextButton(onClick = onAltri) { Text(stringResource(R.string.studio_storico_altri)) } }
            }
        },
        confirmButton = { TextButton(onClick = onChiudi) { Text(stringResource(R.string.pagina_chiudi)) } },
    )
}

@Composable
private fun RigaStorico(context: Context, s: StudioSvolto, zona: ZoneId) {
    val giorno = RegoleStudio.data(s.giorno)?.let { "${it.dayOfMonth}/${it.monthValue}" } ?: s.giorno
    Column(verticalArrangement = Arrangement.spacedBy(Spazi.xs)) {
        Text(
            text = stringResource(
                R.string.studio_storico_riga,
                giorno,
                TestoStudio.ora(s.inizio, zona),
                s.fine?.let { TestoStudio.ora(it, zona) } ?: "…",
                s.minuti,
            ),
            style = MaterialTheme.typography.titleSmall,
        )
        if (s.origine == OriginiStudio.MANUALE) Nota(stringResource(R.string.studio_storico_a_mano))
        val chiusura = when (s.chiusura) {
            ChiusureStudio.FIGLIO -> s.dichiarazione?.let { stringResource(R.string.studio_storico_chiuso_da_te, it) }
                ?: stringResource(R.string.studio_storico_chiuso_da_te_semplice)
            ChiusureStudio.GENITORE -> stringResource(R.string.studio_storico_chiuso_genitore, s.chiusaDa ?: context.getString(R.string.faccende_genitore_senza_nome), s.motivo ?: "")
            ChiusureStudio.NON_CHIUSO -> stringResource(R.string.studio_storico_non_chiuso)
            else -> stringResource(R.string.studio_pillola_in_corso)
        }
        Text(chiusura, style = MaterialTheme.typography.bodyMedium)
        if (s.tratti.isNotEmpty()) {
            Nota(TestoStudio.riepilogo(s.tratti.map { Triple(it.tipo, it.parola, it.secondiContati ?: it.secondi) }, paroleStudio(context)))
        }
    }
}

/** Le versioni approvate, dalla più recente, con chi le ha approvate. */
@Composable
private fun DialogoVersioni(versioni: StudioViewModel.Versioni, zona: ZoneId, onChiudi: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onChiudi,
        title = { Text(stringResource(R.string.studio_versioni)) },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(Spazi.m)) {
                if (versioni.caricamento) item { Caricamento(centrato = false) }
                if (versioni.errore) item { Text(stringResource(R.string.studio_storico_errore)) }
                items(versioni.versioni, key = { it.versione ?: it.hashCode() }) { v ->
                    Column(verticalArrangement = Arrangement.spacedBy(Spazi.xs)) {
                        Text(
                            text = stringResource(R.string.studio_versione_titolo, v.versione ?: 0, v.orariDal?.let { RegoleStudio.data(it) }?.let { "${it.dayOfMonth}/${it.monthValue}" } ?: "—"),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        RigheContenuto(context, v, zona)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onChiudi) { Text(stringResource(R.string.pagina_chiudi)) } },
    )
}
