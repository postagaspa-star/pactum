package eu.stgm.pactum.genitore.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.Dichiarazione
import eu.stgm.pactum.genitore.dati.RegolaFinestra
import eu.stgm.pactum.genitore.dati.StatiDichiarazione
import eu.stgm.pactum.genitore.dati.TipiVerdetto

// Le dichiarazioni del figlio: la seconda metà di "Il tuo turno". Il genitore
// non emette verdetti, risponde — conferma, conferma per conto dell'arbitro, o
// dice che non è andata così.

/**
 * La sezione delle dichiarazioni dentro "Il tuo turno": prima quelle DA
 * CONFERMARE, poi quelle già NEL REGISTRO come righe di storia. Quando non c'è
 * niente da confermare lo si scrive in grande: è una buona notizia.
 */
internal fun LazyListScope.sezioneDichiarazioni(
    dichiarazioni: List<Dichiarazione>,
    regolePerId: Map<Long, RegolaFinestra>,
    invioInCorso: Boolean,
    onVerdetto: (Long, String, String?) -> Unit,
) {
    val inAttesa = dichiarazioni.filter { it.stato == StatiDichiarazione.IN_ATTESA }
    val risolte = dichiarazioni.filter { it.stato != StatiDichiarazione.IN_ATTESA }

    item {
        TitoloSezione(
            stringResource(R.string.turno_sezione_dichiarazioni),
            modifier = Modifier.padding(top = Spazi.l),
        )
    }

    item { SopraTitolo(stringResource(R.string.verdetti_da_confermare)) }
    if (inAttesa.isEmpty()) {
        item {
            Text(
                text = stringResource(R.string.turno_niente_in_attesa),
                style = MaterialTheme.typography.headlineSmall,
            )
        }
    } else {
        items(inAttesa, key = { "attesa-${it.id}" }) { dichiarazione ->
            CardInAttesa(
                dichiarazione = dichiarazione,
                regola = regolePerId[dichiarazione.regolaId],
                invioInCorso = invioInCorso,
                onVerdetto = onVerdetto,
            )
        }
    }

    if (risolte.isNotEmpty()) {
        item {
            Column(modifier = Modifier.fillMaxWidth().padding(top = Spazi.s)) {
                SopraTitolo(stringResource(R.string.verdetti_nel_registro))
                ListaRighe(risolte) { RigaRisolta(it, regolePerId[it.regolaId]) }
            }
        }
    }
}

@Composable
private fun CardInAttesa(
    dichiarazione: Dichiarazione,
    regola: RegolaFinestra?,
    invioInCorso: Boolean,
    onVerdetto: (Long, String, String?) -> Unit,
) {
    var nota by remember(dichiarazione.id) { mutableStateOf("") }
    val notaPulita = { nota.trim().ifBlank { null } }
    val arbitro = regola?.let { parametroTesto(it.parametri, "arbitro_nome") }

    CardContenuto {
        Column(modifier = Modifier.padding(Spazi.l)) {
            IntestazioneDichiarazione(dichiarazione, regola)
            Text(
                text = testoEsitoDichiarato(dichiarazione.esito),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = Spazi.xs),
            )
            dichiarazione.nota?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = stringResource(R.string.dichiarazione_nota, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spazi.xs),
                )
            }

            Spacer(modifier = Modifier.height(Spazi.s))
            OutlinedTextField(
                value = nota,
                onValueChange = { nota = it },
                label = { Text(stringResource(R.string.verdetto_nota_campo)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(Spazi.s))

            Button(
                enabled = !invioInCorso,
                onClick = { onVerdetto(dichiarazione.id, TipiVerdetto.CONFERMA, notaPulita()) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.verdetto_conferma))
            }
            Spacer(modifier = Modifier.height(Spazi.xs))
            OutlinedButton(
                enabled = !invioInCorso,
                onClick = {
                    onVerdetto(dichiarazione.id, TipiVerdetto.CONFERMA_PER_CONTO, notaPulita())
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (arbitro != null) {
                        stringResource(R.string.verdetto_conferma_per_conto, arbitro)
                    } else {
                        stringResource(R.string.verdetto_conferma_per_conto_arbitro)
                    },
                )
            }
            // L'azione rara e pesante: non merita il peso di un bordo pieno
            // accanto alla conferma.
            TextButton(
                enabled = !invioInCorso,
                onClick = { onVerdetto(dichiarazione.id, TipiVerdetto.RIBALTA, notaPulita()) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.verdetto_ribalta))
            }
        }
    }
}

@Composable
private fun RigaRisolta(dichiarazione: Dichiarazione, regola: RegolaFinestra?) {
    val arbitro = regola?.let { parametroTesto(it.parametri, "arbitro_nome") } ?: "?"
    // (v2.1) La frase del registro la congela il server sul verdetto (cita
    // l'arbitro di allora): si mostra QUELLA verbatim, non la si ricostruisce
    // dai parametri attuali della regola. Se manca (es. fallimento dichiarato,
    // che non passa da un verdetto) si ripiega sulla descrizione locale.
    val registro = dichiarazione.verdetto?.registro?.takeIf { it.isNotBlank() }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spazi.m)) {
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
    }
}

@Composable
private fun IntestazioneDichiarazione(dichiarazione: Dichiarazione, regola: RegolaFinestra?) {
    val titolo = regola?.let { descrizioneRegola(it) }
        ?: stringResource(R.string.dichiarazione_regola_sconosciuta)
    Text(text = titolo, style = MaterialTheme.typography.titleSmall)
    if (dichiarazione.giorno.isNotBlank()) {
        Text(
            text = stringResource(R.string.dichiarazione_giorno, giornoBreve(dichiarazione.giorno)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun descrizioneStato(dichiarazione: Dichiarazione, arbitro: String): String =
    when (dichiarazione.stato) {
        StatiDichiarazione.REGISTRATA -> stringResource(R.string.dichiarazione_stato_registrata)
        StatiDichiarazione.CONFERMATA -> stringResource(R.string.dichiarazione_stato_confermata)
        StatiDichiarazione.CONFERMATA_PER_CONTO ->
            stringResource(R.string.dichiarazione_stato_confermata_per_conto, arbitro)
        StatiDichiarazione.RIBALTATA -> stringResource(R.string.dichiarazione_stato_ribaltata)
        else -> dichiarazione.stato
    }
