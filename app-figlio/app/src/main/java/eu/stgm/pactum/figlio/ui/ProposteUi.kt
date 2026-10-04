package eu.stgm.pactum.figlio.ui

import eu.stgm.pactum.design.Pillola
import eu.stgm.pactum.design.CardNormale
import eu.stgm.pactum.design.CardEvidenza
import eu.stgm.pactum.design.FilaPulsanti
import eu.stgm.pactum.design.Tono
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.dati.ContestoDispositivi
import eu.stgm.pactum.figlio.dati.DirezioniProposta
import eu.stgm.pactum.figlio.dati.EsitiRisposta
import eu.stgm.pactum.figlio.dati.Proposta
import eu.stgm.pactum.figlio.dati.Regola

/*
 * (0.15) Le proposte non hanno più una scheda: quelle del genitore stanno in
 * cima a Regole ("Da decidere"), quelle del figlio sulla card della sua regola
 * (con "Ritira la proposta" nel ⋯), quelle chiuse nello Storico. Qui le card.
 */

/**
 * Una proposta del genitore che aspetta la risposta del figlio: il confronto
 * del server in grande, la regola com'è ora e come diventa se accetti, il
 * perché del genitore, "aggiungi una motivazione", Accetto / Rifiuto.
 */
@Composable
fun CardPropostaPendente(
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
        oggetto = TestoProposta.oggetto(proposta.regolaId, proposta.direzione, proposta.parametriProposti, regole),
        contesto = contesto,
    )

    CardEvidenza(tono = Tono.Neutro) {
        Column(verticalArrangement = Arrangement.spacedBy(Spazi.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // (0.15) Col nome del genitore, se il server lo dice: "Proposta di Mamma".
                    text = conNomeGenitore(context, proposta.nomeGenitore, R.string.proposta_dal_genitore, R.string.proposta_dal_genitore_nome),
                    style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                )
                PillolaDirezione(proposta.direzione)
            }
            // Il confronto autoritativo del server, IN EVIDENZA: è la frase che
            // dice cosa cambierebbe rispetto ad ora.
            Text(text = racconto.titolo, style = MaterialTheme.typography.headlineSmall)
            // Sotto, la regola com'è ora e come diventa se accetti.
            Column {
                racconto.righe.forEach { riga ->
                    Text(text = riga, style = MaterialTheme.typography.bodyMedium)
                }
            }
            proposta.motivazione?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = modelloGenitoreDice(context, proposta.nomeGenitore).format(it),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            istanteServer(proposta.tsServer)?.let {
                Text(
                    text = dataOraLocale(it),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (motivazioneAperta) {
                OutlinedTextField(
                    value = motivazione,
                    onValueChange = { motivazione = it },
                    label = { Text(stringResource(R.string.proposta_campo_motivazione)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                TextButton(onClick = { motivazioneAperta = true }, contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(R.string.proposta_aggiungi_motivazione))
                }
            }
            FilaPulsanti {
                Button(
                    enabled = !invioInCorso,
                    onClick = { onRispondi(proposta.id, EsitiRisposta.ACCETTA, motivazionePulita()) },
                ) {
                    Text(stringResource(R.string.proposta_accetta), maxLines = 1)
                }
                OutlinedButton(
                    enabled = !invioInCorso,
                    onClick = { onRispondi(proposta.id, EsitiRisposta.RIFIUTA, motivazionePulita()) },
                ) {
                    Text(stringResource(R.string.proposta_rifiuta), maxLines = 1)
                }
            }
        }
    }
}

/**
 * Una proposta del figlio che aspetta il genitore su una regola che qui non
 * c'è (di un altro dispositivo): cosa ha chiesto, il suo perché, quando, e
 * "Ritira". Quelle sulle regole di qui stanno sulla card della regola.
 */
@Composable
fun CardPropostaTua(
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
    CardNormale {
        Column(verticalArrangement = Arrangement.spacedBy(Spazi.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.proposta_tua),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                PillolaDirezione(proposta.direzione)
            }
            Text(text = racconto.titolo, style = MaterialTheme.typography.titleMedium)
            Column {
                racconto.righe.forEach { riga -> Text(text = riga, style = MaterialTheme.typography.bodyMedium) }
            }
            proposta.motivazione?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = stringResource(R.string.proposta_tuo_perche, it.trim()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val quando = istanteServer(proposta.tsServer)?.let { dataOraLocale(it) }
            Text(
                text = quando?.let { stringResource(R.string.proposta_tua_in_attesa, it) }
                    ?: stringResource(R.string.proposta_stato_pendente),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(enabled = !invioInCorso, onClick = onRitira) {
                Text(stringResource(R.string.proposta_ritira))
            }
        }
    }
}

/**
 * Una proposta chiusa, di chiunque sia: di chi era, il confronto, la regola e
 * com'è finita, con il perché di chi ha proposto e di chi ha risposto
 * (TestoProposta.righeChiusa). Nello Storico.
 */
@Composable
fun CardPropostaStorica(proposta: Proposta, regola: Regola?, contesto: ContestoDispositivi) {
    val context = LocalContext.current
    LocalConfiguration.current
    val eliminazione = TestoProposta.eliminazione(proposta.direzione, proposta.parametriProposti)
    CardNormale {
        Column(verticalArrangement = Arrangement.spacedBy(Spazi.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (proposta.delFiglio) {
                        stringResource(R.string.proposta_tua)
                    } else {
                        conNomeGenitore(context, proposta.nomeGenitore, R.string.proposta_dal_genitore, R.string.proposta_dal_genitore_nome)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                PillolaDirezione(proposta.direzione)
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
            confronto?.let { Text(text = it, style = MaterialTheme.typography.titleMedium) }
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
            Column {
                // (0.15) Il genitore di questa proposta, col suo nome se c'è: chi l'ha
                // fatta, o chi ha risposto a una del figlio.
                val genitore = if (proposta.delFiglio) proposta.nomeRispostaDi else proposta.nomeGenitore
                TestoProposta.righeChiusa(proposta, paroleStoria(context, genitore)).forEach { riga ->
                    Text(
                        text = riga.testo,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (riga.tenue) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = Spazi.xs),
                    )
                }
            }
            istanteServer(proposta.tsServer)?.let {
                Text(
                    text = dataOraLocale(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * La direzione della proposta in una pillola: stringe (attenzione), allenta
 * (positivo), eliminazione (neutra). Nessuno dei tre è un colore del patto:
 * quelli vivono solo nella striscia.
 */
@Composable
fun PillolaDirezione(direzione: String?) {
    when (direzione) {
        DirezioniProposta.STRINGE -> Pillola(stringResource(R.string.proposta_tag_stringe), tono = Tono.Attenzione)
        DirezioniProposta.ALLENTA -> Pillola(stringResource(R.string.proposta_tag_allenta), tono = Tono.Positivo)
        DirezioniProposta.ELIMINA -> Pillola(stringResource(R.string.proposta_tag_elimina), tono = Tono.Neutro)
        else -> Unit
    }
}
