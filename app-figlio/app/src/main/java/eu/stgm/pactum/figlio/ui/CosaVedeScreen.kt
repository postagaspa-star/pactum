package eu.stgm.pactum.figlio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.figlio.R

/**
 * "Cosa vede tuo padre" (redesign C6): l'elenco letterale di ciò che arriva
 * nella sua app e di ciò che resta fuori. Il primo giorno un sedicenne apre
 * l'app per un motivo solo, vedere cosa vedono di lui: la risposta arriva
 * subito dopo i permessi, e resta raggiungibile dalle Impostazioni.
 *
 * Il contenuto è ricavato dai campi del contratto (GET /api/finestra, più le
 * dichiarazioni, le proposte e gli avvisi che il genitore riceve): se la
 * finestra cambia, questo elenco cambia con lei — è così che "una finestra,
 * non una vetrata" diventa una cosa verificabile.
 *
 * [onChiudi] = arrivo dalle Impostazioni (freccia indietro); [onHoCapito] =
 * arrivo dall'onboarding (pulsante in fondo).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CosaVedeScreen(onChiudi: (() -> Unit)? = null, onHoCapito: (() -> Unit)? = null) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cosa_vede_titolo)) },
                navigationIcon = {
                    if (onChiudi != null) {
                        IconButton(onClick = onChiudi) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                stringResource(R.string.azione_indietro),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Spazi.l + Spazi.xs),
            verticalArrangement = Arrangement.spacedBy(Spazi.l),
        ) {
            Text(
                text = stringResource(R.string.cosa_vede_intro),
                style = MaterialTheme.typography.bodyMedium,
            )
            Blocco(
                titolo = stringResource(R.string.cosa_vede_si_titolo),
                voci = stringArrayResource(R.array.cosa_vede_si).toList(),
            )
            Blocco(
                titolo = stringResource(R.string.cosa_vede_no_titolo),
                voci = stringArrayResource(R.array.cosa_vede_no).toList(),
            )
            Blocco(
                titolo = stringResource(R.string.cosa_vede_tuo_titolo),
                voci = stringArrayResource(R.array.cosa_vede_tuo).toList(),
            )
            if (onHoCapito != null) {
                Button(onClick = onHoCapito, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.cosa_vede_ho_capito))
                }
            }
        }
    }
}

@Composable
private fun Blocco(titolo: String, voci: List<String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(Spazi.l + Spazi.xs),
            verticalArrangement = Arrangement.spacedBy(Spazi.s),
        ) {
            Text(
                text = titolo,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            voci.forEach { voce ->
                Row(horizontalArrangement = Arrangement.spacedBy(Spazi.s)) {
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(text = voce, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
