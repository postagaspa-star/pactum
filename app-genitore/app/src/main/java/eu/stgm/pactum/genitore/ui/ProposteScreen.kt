package eu.stgm.pactum.genitore.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eu.stgm.pactum.design.Spazi
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

// Le proposte: la prima metà di "Proposte e conferme" (TurnoScreen). Proporre, mai
// imporre: il confronto lo calcola il server ed è la stessa frase che vede il
// figlio. Tre blocchi — da mandare, in attesa di risposta, come sono andate.

/**
 * La sezione delle proposte dentro "Proposte e conferme".
 * - DA MANDARE: le regole attive, ciascuna col suo "Proponi una modifica" —
 *   tranne quelle che ne hanno già una in attesa (il server ne accetta una sola
 *   per regola): lì una riga dice perché;
 * - IN ATTESA DI RISPOSTA: le proposte pendenti, il confronto in grande;
 * - COME SONO ANDATE: le chiuse, come righe di storia.
 */
internal fun LazyListScope.sezioneProposte(
    regoleAttive: List<RegolaFinestra>,
    proposte: List<Proposta>,
    onProponi: (RegolaFinestra) -> Unit,
) {
    val regolePerId = regoleAttive.associateBy { it.id }
    val pendenti = proposte.filter { it.stato == StatiProposta.PENDENTE }
    val chiuse = proposte.filter { it.stato != StatiProposta.PENDENTE }
    val conPropostaInAttesa = regoleConPropostaInAttesa(proposte)

    item { TitoloSezione(stringResource(R.string.turno_sezione_proposte)) }

    item { SopraTitolo(stringResource(R.string.proposte_da_mandare)) }
    if (regoleAttive.isEmpty()) {
        item { RigaVuota(stringResource(R.string.proposte_nessuna_regola_attiva)) }
    } else {
        items(regoleAttive, key = { "attiva-${it.id}" }) { regola ->
            CardRegolaProponibile(
                regola = regola,
                propostaInAttesa = regola.id in conPropostaInAttesa,
                onProponi = onProponi,
            )
        }
    }

    if (proposte.isEmpty()) {
        item { RigaVuota(stringResource(R.string.proposte_elenco_vuoto)) }
    }

    if (pendenti.isNotEmpty()) {
        item {
            SopraTitolo(
                stringResource(R.string.proposte_in_attesa),
                modifier = Modifier.padding(top = Spazi.s),
            )
        }
        items(pendenti, key = { "pendente-${it.id}" }) {
            CardPropostaPendente(it, regolePerId[it.regolaId])
        }
    }

    if (chiuse.isNotEmpty()) {
        item {
            Column(modifier = Modifier.fillMaxWidth().padding(top = Spazi.s)) {
                SopraTitolo(stringResource(R.string.proposte_come_sono_andate))
                ListaRighe(chiuse) { RigaPropostaChiusa(it) }
            }
        }
    }
}

/**
 * (v3) Il sopra-titolo di una regola: il tipo, e di quale dispositivo è
 * ("LIMITE DI TEMPO · COMPUTER DI CAMERA"). La vita reale è del figlio: solo il tipo.
 */
@Composable
private fun SopraTitoloRegola(regola: RegolaFinestra) {
    val tipo = etichettaTipoRegola(regola.tipo)
    val dispositivo = regola.dispositivo
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (dispositivo != null) {
            IconaDispositivo(dispositivo.tipo, tinta = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(Spazi.xs))
        }
        SopraTitolo(
            testo = if (dispositivo != null) {
                "$tipo · ${nomeDelDispositivo(parole(), dispositivo.nome, dispositivo.tipo).uppercase()}"
            } else {
                tipo
            },
            colore = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Una regola attiva su cui proporre. Con una proposta già in attesa il pulsante
 * non c'è: il server rifiuterebbe la seconda (409 `proposta_gia_pendente`), e
 * al suo posto una riga dice perché.
 */
@Composable
private fun CardRegolaProponibile(
    regola: RegolaFinestra,
    propostaInAttesa: Boolean,
    onProponi: (RegolaFinestra) -> Unit,
) {
    CardContenuto {
        Column(modifier = Modifier.padding(Spazi.l)) {
            SopraTitoloRegola(regola)
            Text(
                text = descrizioneRegola(regola),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = Spazi.xs),
            )
            Spacer(modifier = Modifier.height(Spazi.s))
            if (propostaInAttesa) {
                Text(
                    text = stringResource(R.string.proposte_gia_in_attesa),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FilledTonalButton(onClick = { onProponi(regola) }) {
                    Text(stringResource(R.string.proposte_bottone_proponi))
                }
            }
        }
    }
}

/**
 * Una proposta che aspetta il figlio: il confronto calcolato dal server in
 * `headlineSmall` — è l'elemento più forte, ed è la stessa frase che legge lui.
 */
@Composable
private fun CardPropostaPendente(proposta: Proposta, regola: RegolaFinestra?) {
    CardContenuto {
        Column(modifier = Modifier.padding(Spazi.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TagDirezione(proposta.direzione)
                Spacer(modifier = Modifier.weight(1f))
                TestoOrario(proposta.tsServer)
            }
            if (proposta.confronto.isNotBlank()) {
                Text(
                    text = proposta.confronto,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = Spazi.s),
                )
            }
            // Su quale regola (e di quale dispositivo): il confronto da solo non lo dice.
            if (regola != null) {
                val dispositivo = regola.dispositivo
                val descrizione = descrizioneRegola(regola)
                Text(
                    text = if (dispositivo != null) {
                        "${nomeDelDispositivo(parole(), dispositivo.nome, dispositivo.tipo)} · $descrizione"
                    } else {
                        descrizione
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spazi.xs),
                )
            }
            proposta.motivazione?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = stringResource(R.string.proposta_motivazione, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spazi.xs),
                )
            }
        }
    }
}

/** Una proposta chiusa: com'è andata, e la risposta del figlio se c'è. */
@Composable
private fun RigaPropostaChiusa(proposta: Proposta) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spazi.m)) {
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
                modifier = Modifier.padding(top = Spazi.xs),
            )
        }
        proposta.motivazione?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = stringResource(R.string.proposta_motivazione, it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spazi.xs),
            )
        }
        // La risposta del figlio, quando c'è: esito + motivazione.
        proposta.risposta?.let { risposta ->
            Text(
                text = if (risposta.esito == EsitiRisposta.ACCETTA) {
                    stringResource(R.string.proposta_risposta_accettata)
                } else {
                    stringResource(R.string.proposta_risposta_rifiutata)
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = Spazi.s),
            )
            risposta.motivazione?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = stringResource(R.string.proposta_risposta_motivazione, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TestoOrario(proposta.tsServer, Modifier.padding(top = Spazi.xs))
    }
}

/**
 * La direzione della proposta, con tre vestiti per tre azioni diverse:
 * stringe → ocra (`tertiaryContainer`), allenta → blu (`primaryContainer`),
 * eliminazione → neutro con bordo. Mai i colori del patto.
 */
@Composable
private fun TagDirezione(direzione: String) {
    val schema = MaterialTheme.colorScheme
    when (direzione) {
        DirezioniProposta.STRINGE -> Etichetta(
            testo = stringResource(R.string.proposta_tag_stringe),
            contenitore = schema.tertiaryContainer,
            inchiostro = schema.onTertiaryContainer,
        )
        DirezioniProposta.ALLENTA -> Etichetta(
            testo = stringResource(R.string.proposta_tag_allenta),
            contenitore = schema.primaryContainer,
            inchiostro = schema.onPrimaryContainer,
        )
        DirezioniProposta.ELIMINA -> Etichetta(
            testo = stringResource(R.string.proposta_tag_elimina),
            contenitore = schema.surfaceVariant,
            inchiostro = schema.onSurfaceVariant,
            bordo = BorderStroke(1.dp, schema.outline),
        )
        else -> Unit
    }
}

@Composable
private fun etichettaStatoProposta(stato: String): String = when (stato) {
    StatiProposta.PENDENTE -> stringResource(R.string.proposta_stato_pendente)
    StatiProposta.ACCETTATA -> stringResource(R.string.proposta_stato_accettata)
    StatiProposta.RIFIUTATA -> stringResource(R.string.proposta_stato_rifiutata)
    StatiProposta.ANNULLATA -> stringResource(R.string.proposta_stato_annullata)
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
internal fun DialogoNuovaProposta(
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
    // Il bersaglio NON si cambia: si propone un nuovo limite, non un'altra app
    // (contratto v2.1: la chiave nasce da un selettore, mai da testo libero). Sul
    // computer poi sarebbe un `exe:` o un `sito:` da scrivere a mano.
    val app = parametroTesto(regola.parametri, "app_o_categoria") ?: ""
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
                verticalArrangement = Arrangement.spacedBy(Spazi.m),
            ) {
                // (v3) Di quale dispositivo è la regola: la proposta vale lì.
                if (regola.dispositivo != null) SopraTitoloRegola(regola)
                Text(
                    text = stringResource(
                        R.string.proposta_crea_regola,
                        descrizioneRegola(regola),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ScegliModalita(elimina = elimina, onElimina = { elimina = it })

                if (!elimina) {
                    when (regola.tipo) {
                        TipiRegola.LIMITE_TEMPO -> {
                            Column {
                                Text(
                                    text = stringResource(
                                        R.string.proposta_bersaglio,
                                        nomeLeggibile(app, regola.nome),
                                    ),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = stringResource(R.string.proposta_app_non_modificabile),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
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
        Spacer(modifier = Modifier.height(Spazi.xs))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spazi.s)) {
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
