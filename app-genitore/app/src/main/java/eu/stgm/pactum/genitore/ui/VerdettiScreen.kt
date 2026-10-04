package eu.stgm.pactum.genitore.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.design.CardNormale
import eu.stgm.pactum.design.MenuAzioni
import eu.stgm.pactum.design.VoceMenu
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.Dichiarazione
import eu.stgm.pactum.genitore.dati.RegolaFinestra
import eu.stgm.pactum.genitore.dati.RiferimentoGenitore
import eu.stgm.pactum.genitore.dati.StatiDichiarazione
import eu.stgm.pactum.genitore.dati.TipiVerdetto

// Le dichiarazioni del figlio sulle regole di vita reale. Il genitore non emette
// verdetti, risponde — conferma, conferma per conto dell'arbitro, o dice che non è
// andata così. (0.15) Quelle da confermare stanno in "Da decidere", con "Confermo"
// in vista e le altre due risposte nel ⋯ (con una domanda prima); quelle già nel
// registro nello Storico del patto.

/**
 * Una dichiarazione da confermare: la regola, il giorno, che cosa dice il figlio
 * (e la sua nota), una nota facoltativa del genitore, "Confermo" e il ⋯ con
 * "Confermo per conto di …" e "Non è andata così". Questi due chiedono prima di
 * mandare: sono risposte che non si tolgono.
 */
@Composable
internal fun CardDichiarazione(
    dichiarazione: Dichiarazione,
    regola: RegolaFinestra?,
    invioInCorso: Boolean,
    onVerdetto: (Long, String, String?) -> Unit,
) {
    // (0.15) La nota scritta sopravvive alla rotazione (B15).
    var nota by rememberSaveable(dichiarazione.id) { mutableStateOf("") }
    val notaPulita = { nota.trim().ifBlank { null } }
    val arbitro = regola?.let { parametroTesto(it.parametri, "arbitro_nome") }?.trim()?.takeIf { it.isNotEmpty() }
    // La domanda prima di "per conto di" o "Non è andata così": quale delle due.
    var domanda by rememberSaveable(dichiarazione.id) { mutableStateOf<String?>(null) }
    val perConto = if (arbitro != null) {
        stringResource(R.string.verdetto_conferma_per_conto, arbitro)
    } else {
        stringResource(R.string.verdetto_conferma_per_conto_arbitro)
    }

    CardNormale {
        Column {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    IntestazioneDichiarazione(dichiarazione, regola)
                }
                MenuAzioni(
                    voci = listOf(
                        VoceMenu(perConto, { domanda = TipiVerdetto.CONFERMA_PER_CONTO }, abilitata = !invioInCorso),
                        VoceMenu(stringResource(R.string.verdetto_ribalta), { domanda = TipiVerdetto.RIBALTA }, abilitata = !invioInCorso),
                    ),
                    descrizione = stringResource(R.string.azione_altre_risposte),
                )
            }
            Text(
                text = testoEsitoDichiarato(dichiarazione.esito),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = Spazi.xs),
            )
            dichiarazione.nota?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = stringResource(R.string.dichiarazione_nota, it),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = Spazi.xs),
                )
            }
            OutlinedTextField(
                value = nota,
                onValueChange = { nota = it },
                label = { Text(stringResource(R.string.verdetto_nota_campo)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = Spazi.s),
            )
            Button(
                enabled = !invioInCorso,
                onClick = { onVerdetto(dichiarazione.id, TipiVerdetto.CONFERMA, notaPulita()) },
                modifier = Modifier.fillMaxWidth().padding(top = Spazi.s),
            ) {
                Text(stringResource(R.string.verdetto_conferma))
            }
        }
    }

    domanda?.let { verdetto ->
        val ribalta = verdetto == TipiVerdetto.RIBALTA
        AlertDialog(
            onDismissRequest = { domanda = null },
            title = {
                Text(
                    when {
                        ribalta -> stringResource(R.string.verdetto_ribalta_titolo)
                        arbitro != null -> stringResource(R.string.verdetto_per_conto_titolo, arbitro)
                        else -> stringResource(R.string.verdetto_per_conto_titolo_arbitro)
                    },
                )
            },
            text = {
                Text(
                    when {
                        ribalta -> stringResource(R.string.verdetto_ribalta_testo)
                        arbitro != null -> stringResource(R.string.verdetto_per_conto_testo, arbitro)
                        else -> stringResource(R.string.verdetto_per_conto_testo_arbitro)
                    },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !invioInCorso,
                    onClick = {
                        domanda = null
                        onVerdetto(dichiarazione.id, verdetto, notaPulita())
                    },
                ) {
                    Text(if (ribalta) stringResource(R.string.verdetto_ribalta) else stringResource(R.string.verdetto_conferma))
                }
            },
            dismissButton = {
                TextButton(onClick = { domanda = null }) { Text(stringResource(R.string.azione_annulla)) }
            },
        )
    }
}

/** Una dichiarazione già nel registro, come riga di storia. */
@Composable
internal fun RigaRisolta(dichiarazione: Dichiarazione, regola: RegolaFinestra?, io: RiferimentoGenitore? = null) {
    val arbitro = regola?.let { parametroTesto(it.parametri, "arbitro_nome") }?.trim()?.takeIf { it.isNotEmpty() }
    // (v2.1) La frase del registro la congela il server sul verdetto (cita
    // l'arbitro di allora): si mostra QUELLA verbatim, non la si ricostruisce
    // dai parametri attuali della regola. Se manca (es. fallimento dichiarato,
    // che non passa da un verdetto) si ripiega sulla descrizione locale.
    val registro = dichiarazione.verdetto?.registro?.takeIf { it.isNotBlank() }
    Column(modifier = Modifier.fillMaxWidth()) {
        IntestazioneDichiarazione(dichiarazione, regola)
        Text(
            text = registro ?: descrizioneStato(dichiarazione, arbitro),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = Spazi.xs),
        )
        dichiarazione.verdetto?.nota?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = stringResource(R.string.dichiarazione_verdetto_nota, it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spazi.xs),
            )
        }
        // (0.13) Con più genitori (contratto v3.6): la risposta di un altro genitore dice di chi è.
        (chiHaFatto(dichiarazione.verdetto?.da, io) as? ChiHaFatto.Altro)?.let {
            Text(
                text = stringResource(R.string.dichiarazione_verdetto_da, it.nome),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spazi.xs),
            )
        }
    }
}

@Composable
private fun IntestazioneDichiarazione(dichiarazione: Dichiarazione, regola: RegolaFinestra?) {
    val titolo = regola?.let { descrizioneRegola(it) }
        ?: stringResource(R.string.dichiarazione_regola_sconosciuta)
    if (dichiarazione.giorno.isNotBlank()) {
        SopraTitolo(stringResource(R.string.dichiarazione_giorno, giornoBreve(dichiarazione.giorno)))
    }
    Text(text = titolo, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun descrizioneStato(dichiarazione: Dichiarazione, arbitro: String?): String =
    when (dichiarazione.stato) {
        StatiDichiarazione.REGISTRATA -> stringResource(R.string.dichiarazione_stato_registrata)
        StatiDichiarazione.CONFERMATA -> stringResource(R.string.dichiarazione_stato_confermata)
        StatiDichiarazione.CONFERMATA_PER_CONTO -> if (arbitro != null) {
            stringResource(R.string.dichiarazione_stato_confermata_per_conto, arbitro)
        } else {
            stringResource(R.string.dichiarazione_stato_confermata_per_conto_arbitro)
        }
        StatiDichiarazione.RIBALTATA -> stringResource(R.string.dichiarazione_stato_ribaltata)
        else -> dichiarazione.stato
    }
