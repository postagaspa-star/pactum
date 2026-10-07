package eu.stgm.pactum.figlio.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stgm.pactum.design.CardEvidenza
import eu.stgm.pactum.design.CardNormale
import eu.stgm.pactum.design.FilaPulsanti
import eu.stgm.pactum.design.RigaStato
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.design.Tono
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.catalogo.CatalogoApp
import eu.stgm.pactum.figlio.faccende.StatoBlocco
import eu.stgm.pactum.figlio.faccende.VistaFaccende
import eu.stgm.pactum.figlio.sessione.AppDellaSessione
import eu.stgm.pactum.figlio.studio.ConsegnaStudio
import eu.stgm.pactum.figlio.studio.EsitiStudio
import eu.stgm.pactum.figlio.studio.MemoriaStudio
import eu.stgm.pactum.figlio.studio.OraServer
import eu.stgm.pactum.figlio.studio.ParoleStudio
import eu.stgm.pactum.figlio.studio.RegoleStudio
import eu.stgm.pactum.figlio.studio.RifiutoChiusura
import eu.stgm.pactum.figlio.studio.StatoStudio
import eu.stgm.pactum.figlio.studio.StudioAttivo
import eu.stgm.pactum.figlio.studio.TestoStudio
import eu.stgm.pactum.figlio.studio.TipiTratto
import eu.stgm.pactum.figlio.studio.TrattoLocale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId

/** (0.18) Le parole dello Studio da strings.xml. */
fun paroleStudio(context: Context) = ParoleStudio(
    dalle = context.getString(R.string.studio_dalle),
    minutiSu = context.getString(R.string.studio_minuti_su),
    chiudeDopo = context.getString(R.string.studio_chiude_dopo),
    chiudibile = context.getString(R.string.studio_chiudibile),
    separatore = " · ",
    compiti = context.getString(R.string.studio_compiti),
    lavori = context.getString(R.string.studio_lavori),
    minuti = context.getString(R.string.studio_minuti),
)

/** (0.18) Lo Studio adesso per le schermate: la memoria e l'ora del server, rilette ogni secondo durante lo Studio. */
data class StudioAdesso(val memoria: MemoriaStudio, val ora: OraServer, val studio: StudioAttivo?)

@Composable
fun rememberStudio(): StudioAdesso {
    val memoria by StatoStudio.memoria.collectAsStateWithLifecycle()
    val blocco by StatoBlocco.memoria.collectAsStateWithLifecycle()
    var ora by remember { mutableStateOf(OraServer.di(blocco, eu.stgm.pactum.figlio.faccende.Orologio.adesso())) }
    LaunchedEffect(memoria, blocco) {
        while (true) {
            ora = OraServer.di(blocco, eu.stgm.pactum.figlio.faccende.Orologio.adesso())
            delay(if (memoria.attivo(ora) != null || memoria.trattoInCorso != null) 1_000L else 15_000L)
        }
    }
    return StudioAdesso(memoria, ora, memoria.attivo(ora))
}

/** Il nome leggibile di una chiave della lista (pacchetto o `gruppo:apk`). */
fun nomeAppStudio(context: Context, chiave: String, nomi: Map<String, String>): String = when (chiave) {
    AppDellaSessione.GRUPPO_APK -> context.getString(R.string.gruppo_apk_nome)
    else -> CatalogoApp.etichettaValore(context, chiave).takeIf { it != chiave } ?: nomi[chiave] ?: chiave
}

/**
 * (0.18, contratto v4.0) In cima a Oggi: la Sessione Studio in corso (lo
 * stato, il timer con «Comincia un'attività» / «Ferma», «Chiudi lo Studio»
 * quando le condizioni ci sono), o una riga quando sta per partire. Più le
 * righe di quello che il server ha detto di no.
 */
@Composable
fun CardStudioOggi(adesso: StudioAdesso, onMessaggio: (String) -> Unit) {
    val context = LocalContext.current
    val ambito = rememberCoroutineScope()
    val m = adesso.memoria
    val zona = m.zona()
    val studio = adesso.studio
    var dialogoAttivita by rememberSaveable { mutableStateOf(false) }
    var dialogoChiudi by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(Spazi.m)) {
        if (m.chiusuraPersa) {
            RigaStato(
                testo = stringResource(R.string.studio_chiusura_persa),
                tono = Tono.Attenzione,
                azione = stringResource(R.string.studio_ho_capito),
                onAzione = { ambito.launch { eu.stgm.pactum.figlio.studio.ArchivioStudio.modifica(context) { it.senzaAvvisi() } } },
            )
        }
        if (studio == null) {
            val prossima = m.prossimaPartenza(adesso.ora.server)
            if (prossima != null && prossima.inizio - adesso.ora.server <= UN_ORA_MS) {
                val minuti = ((prossima.inizio - adesso.ora.server + 59_999) / 60_000).toInt()
                RigaStato(
                    testo = stringResource(R.string.studio_parte_tra, TestoStudio.ora(prossima.inizio, zona), minuti),
                    tono = Tono.Attenzione,
                )
            }
            return@Column
        }
        val minuti = m.minutiStimati(studio, adesso.ora.ora, adesso.ora.server)
        val chiudibile = m.chiudibile(studio, adesso.ora.ora, adesso.ora.server)
        val tratto = m.trattoInCorso
        CardEvidenza(tono = Tono.Positivo) {
            Column(verticalArrangement = Arrangement.spacedBy(Spazi.s)) {
                Text(text = stringResource(R.string.studio_titolo), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = TestoStudio.stato(studio, minuti, chiudibile, zona, paroleStudio(context)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (tratto != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = TestoStudio.nome(tratto.tipo, tratto.parola, paroleStudio(context)),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = TestoStudio.durata(tratto.secondiAdesso(adesso.ora.ora)),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                    }
                } else {
                    Nota(stringResource(R.string.studio_timer_spiega))
                }
                if (!chiudibile) {
                    Nota(
                        studio.chiudibileDal?.let { stringResource(R.string.studio_come_si_chiude, TestoStudio.ora(it, zona), studio.minutiMinimi) }
                            ?: stringResource(R.string.studio_come_si_chiude_senza_ora, studio.minutiMinimi),
                    )
                }
                FilaPulsanti {
                    if (tratto == null) {
                        Button(onClick = { dialogoAttivita = true }) { Text(stringResource(R.string.studio_comincia), maxLines = 1) }
                    } else {
                        Button(onClick = { ambito.launch { ConsegnaStudio.fermaTratto(context) } }) {
                            Text(stringResource(R.string.studio_ferma), maxLines = 1)
                        }
                    }
                    // «Chiudi lo Studio» compare solo quando le condizioni ci sono.
                    if (chiudibile) {
                        OutlinedButton(onClick = { dialogoChiudi = true }) { Text(stringResource(R.string.studio_chiudi), maxLines = 1) }
                    }
                }
            }
        }
        m.rifiuto?.let { RigaStato(testoRifiuto(context, it, zona), tono = Tono.Attenzione) }
        if (rememberBloccoRimandato()) RigaStato(stringResource(R.string.studio_blocco_rimandato), tono = Tono.Attenzione)
    }

    if (dialogoAttivita && studio != null) {
        DialogoAttivita(
            onAnnulla = { dialogoAttivita = false },
            onInizia = { tipo, parola, faccenda ->
                dialogoAttivita = false
                ambito.launch { ConsegnaStudio.iniziaTratto(context, tipo, parola, faccenda) }
            },
        )
    }
    if (dialogoChiudi && studio != null) {
        DialogoChiudiStudio(
            adesso = adesso,
            studio = studio,
            bozzaIniziale = m.rifiuto?.bozza.orEmpty(),
            onAnnulla = { dialogoChiudi = false },
            onChiudi = { testo ->
                ambito.launch {
                    when (ConsegnaStudio.chiudi(context, testo)) {
                        ConsegnaStudio.EsitoChiudi.CHIUSO -> {
                            dialogoChiudi = false
                            onMessaggio(context.getString(R.string.studio_chiuso))
                        }
                        ConsegnaStudio.EsitoChiudi.SERVE_LA_RETE -> onMessaggio(context.getString(R.string.studio_serve_rete))
                        ConsegnaStudio.EsitoChiudi.NON_CHIUDIBILE -> onMessaggio(context.getString(R.string.studio_non_ancora))
                        ConsegnaStudio.EsitoChiudi.DICHIARAZIONE_NON_VALIDA -> onMessaggio(context.getString(R.string.studio_rifiuto_testo))
                        ConsegnaStudio.EsitoChiudi.NIENTE_DA_CHIUDERE -> dialogoChiudi = false
                    }
                }
            },
        )
    }
}

/** (0.18) La card dello Studio in Oggi c'è: in corso, in partenza entro un'ora, o una chiusura non arrivata da dire. */
fun haCardStudio(adesso: StudioAdesso): Boolean {
    if (adesso.studio != null || adesso.memoria.chiusuraPersa) return true
    val p = adesso.memoria.prossimaPartenza(adesso.ora.server) ?: return false
    return p.inizio - adesso.ora.server <= UN_ORA_MS
}

/** «Lo Studio non si è chiuso: per il server erano le 15:20…» / «…mancano 15 minuti di attività». */
fun testoRifiuto(context: Context, r: RifiutoChiusura, zona: ZoneId): String = when (r.motivo) {
    EsitiStudio.TROPPO_PRESTO -> r.chiudibileDal
        ?.let { context.getString(R.string.studio_rifiuto_presto, TestoStudio.ora(r.il, zona), TestoStudio.ora(it, zona)) }
        ?: context.getString(R.string.studio_rifiuto_presto_semplice)
    EsitiStudio.ATTIVITA_INSUFFICIENTE -> if (r.minuti != null && r.minimi != null) {
        context.getString(R.string.studio_rifiuto_minuti, (r.minimi - r.minuti).coerceAtLeast(1))
    } else {
        context.getString(R.string.studio_rifiuto_minuti_semplice)
    }
    else -> context.getString(R.string.studio_rifiuto_testo)
}

/**
 * «Comincia un'attività»: compiti, lavori di casa o altro (con una parola).
 * Per i compiti una parola facoltativa; per i lavori di casa il lavoro, se si vuole.
 */
@Composable
private fun DialogoAttivita(onAnnulla: () -> Unit, onInizia: (String, String?, Long?) -> Unit) {
    var tipo by rememberSaveable { mutableStateOf(TipiTratto.COMPITI) }
    var parola by rememberSaveable { mutableStateOf("") }
    var faccenda by rememberSaveable { mutableStateOf<Long?>(null) }
    val memoriaBlocco by StatoBlocco.memoria.collectAsStateWithLifecycle()
    val lavori = VistaFaccende.aperte(memoriaBlocco)
    val parolaPulita = RegoleStudio.parola(parola)
    val valida = tipo != TipiTratto.ALTRO || parolaPulita != null
    AlertDialog(
        onDismissRequest = onAnnulla,
        title = { Text(stringResource(R.string.studio_dialogo_attivita_titolo)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spazi.xs)) {
                for ((t, etichetta) in listOf(
                    TipiTratto.COMPITI to R.string.studio_compiti,
                    TipiTratto.LAVORI_DI_CASA to R.string.studio_lavori,
                    TipiTratto.ALTRO to R.string.studio_altro,
                )) {
                    RigaScelta(stringResource(etichetta), tipo == t) { tipo = t }
                }
                when (tipo) {
                    TipiTratto.ALTRO -> OutlinedTextField(
                        value = parola,
                        onValueChange = { parola = it.take(40) },
                        label = { Text(stringResource(R.string.studio_parola)) },
                        singleLine = true,
                        isError = parola.isNotBlank() && parolaPulita == null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TipiTratto.COMPITI -> OutlinedTextField(
                        value = parola,
                        onValueChange = { parola = it.take(40) },
                        label = { Text(stringResource(R.string.studio_parola_compiti)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    else -> if (lavori.isNotEmpty()) {
                        Text(stringResource(R.string.studio_lavoro_quale), style = MaterialTheme.typography.labelLarge)
                        RigaScelta(stringResource(R.string.studio_lavoro_nessuno), faccenda == null) { faccenda = null }
                        lavori.forEach { f -> RigaScelta(f.titolo.ifBlank { "—" }, faccenda == f.id) { faccenda = f.id } }
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = valida, onClick = {
                val p = if (tipo == TipiTratto.LAVORI_DI_CASA) null else parolaPulita
                onInizia(tipo, p, faccenda.takeIf { tipo == TipiTratto.LAVORI_DI_CASA })
            }) { Text(stringResource(R.string.studio_inizia)) }
        },
        dismissButton = { TextButton(onClick = onAnnulla) { Text(stringResource(R.string.azione_annulla)) } },
    )
}

@Composable
private fun RigaScelta(testo: String, scelta: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = scelta, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = scelta, onClick = null, modifier = Modifier.padding(end = Spazi.m))
        Text(testo, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * «Chiudi lo Studio»: il riepilogo dei tratti, «Cosa hai fatto?» (da 10 a
 * 1000 caratteri) e Chiudi. Il testo resta come bozza se il server rifiuta.
 */
@Composable
private fun DialogoChiudiStudio(
    adesso: StudioAdesso,
    studio: StudioAttivo,
    bozzaIniziale: String,
    onAnnulla: () -> Unit,
    onChiudi: (String) -> Unit,
) {
    val context = LocalContext.current
    var testo by rememberSaveable { mutableStateOf(bozzaIniziale) }
    val m = adesso.memoria
    val minuti = m.minutiStimati(studio, adesso.ora.ora, adesso.ora.server)
    val miei: List<TrattoLocale> = m.trattiDi(studio)
    val idMiei = miei.map { it.id }.toSet()
    val tratti = miei.map { Triple(it.tipo, it.parola, it.secondiAdesso(adesso.ora.ora)) } +
        studio.trattiServer.filter { it.id !in idMiei }.map { Triple(it.tipo, it.parola, it.secondiContati ?: it.secondi) }
    val riepilogo = TestoStudio.riepilogo(tratti, paroleStudio(context))
    val caratteri = RegoleStudio.caratteri(testo)
    val errore = RegoleStudio.erroreDichiarazione(testo)
    val valido = errore == null
    AlertDialog(
        onDismissRequest = onAnnulla,
        title = { Text(stringResource(R.string.studio_chiudi_titolo)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spazi.m)) {
                Text(
                    text = if (riepilogo.isEmpty()) {
                        stringResource(R.string.studio_chiudi_riepilogo_vuoto, minuti)
                    } else {
                        stringResource(R.string.studio_chiudi_riepilogo, minuti, riepilogo)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = testo,
                    onValueChange = { testo = it.take(RegoleStudio.DICHIARAZIONE_MASSIMA + 50) },
                    label = { Text(stringResource(R.string.studio_chiudi_cosa)) },
                    minLines = 3,
                    isError = errore == RegoleStudio.ErroreDichiarazione.LUNGA || errore == RegoleStudio.ErroreDichiarazione.CARATTERI,
                    supportingText = {
                        Text(
                            when {
                                // Le stesse regole del server: un'emoji composta o un tab non passano.
                                errore == RegoleStudio.ErroreDichiarazione.CARATTERI -> stringResource(R.string.studio_chiudi_caratteri_speciali)
                                caratteri < RegoleStudio.DICHIARAZIONE_MINIMA -> stringResource(R.string.studio_chiudi_caratteri_pochi, caratteri)
                                else -> stringResource(R.string.studio_chiudi_caratteri, caratteri)
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Nota(stringResource(R.string.studio_chiudi_spiega))
            }
        },
        confirmButton = {
            Button(enabled = valido, onClick = { onChiudi(testo) }) { Text(stringResource(R.string.studio_chiudi)) }
        },
        dismissButton = { TextButton(onClick = onAnnulla) { Text(stringResource(R.string.azione_annulla)) } },
    )
}

/** (0.18) Il motivo per cui l'avvio a mano non è partito, in parole. */
fun testoNoAvvio(context: Context, motivo: String): String = context.getString(
    when (motivo) {
        "blocco_faccende" -> R.string.studio_no_avvio_blocco
        "troppo_tardi" -> R.string.studio_no_avvio_tardi
        "studio_non_approvato", "non_approvato" -> R.string.studio_no_avvio_non_approvato
        "avvio_scaduto" -> R.string.studio_no_avvio_scaduto
        "gia_in_studio" -> R.string.studio_no_avvio_gia
        "scollegato" -> R.string.scollegato
        "server_vecchio" -> R.string.studio_server_da_aggiornare
        EsitiStudio.GIA_CHIUSO -> R.string.studio_no_avvio_gia_chiuso
        else -> R.string.studio_no_avvio
    },
)

/** Le chiavi della lista, coi loro nomi, in una riga. */
fun elencoApp(context: Context, app: List<String>, nomi: Map<String, String>): String =
    app.map { nomeAppStudio(context, it, nomi) }.joinToString(", ")

@Suppress("unused")
private val keyboardNumeri = KeyboardOptions(keyboardType = KeyboardType.Number)

/** Un'ora, in ms. */
private const val UN_ORA_MS = 60L * 60 * 1000

/** Un giorno della settimana come chip, per il modulo. */
@Composable
internal fun ChipGiornoStudio(etichetta: String, scelto: Boolean, onClick: () -> Unit) {
    FilterChip(selected = scelto, onClick = onClick, label = { Text(etichetta) })
}
