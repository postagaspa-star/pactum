package eu.stgm.pactum.figlio.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.permessi.PermessiHelper
import eu.stgm.pactum.figlio.permessi.StatoPermessi

/**
 * Checklist dei tre permessi. Ogni passo mostra lo stato e apre la schermata
 * di sistema giusta; il passo sull'accesso ai dati di utilizzo include la
 * guida alle impostazioni con limitazioni di Android 15/16 (architettura.md).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(statoPermessi: StatoPermessi, onAggiorna: () -> Unit) {
    val context = LocalContext.current
    val lanciaPermessoNotifiche = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { onAggiorna() }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.onboarding_titolo)) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.onboarding_intro),
                style = MaterialTheme.typography.bodyMedium,
            )

            PassoPermesso(
                titolo = stringResource(R.string.passo_uso_titolo),
                descrizione = stringResource(R.string.passo_uso_descrizione),
                fatto = statoPermessi.accessoUso,
                etichettaAzione = stringResource(R.string.passo_apri_impostazioni),
                onAzione = { context.startActivity(PermessiHelper.intentAccessoUso()) },
                contenutoExtra = { AiutoRestrizioni() },
            )

            PassoPermesso(
                titolo = stringResource(R.string.passo_batteria_titolo),
                descrizione = stringResource(R.string.passo_batteria_descrizione),
                fatto = statoPermessi.esenzioneBatteria,
                etichettaAzione = stringResource(R.string.passo_apri_impostazioni),
                onAzione = { context.startActivity(PermessiHelper.intentEsenzioneBatteria(context)) },
            )

            PassoPermesso(
                titolo = stringResource(R.string.passo_notifiche_titolo),
                descrizione = stringResource(R.string.passo_notifiche_descrizione),
                fatto = statoPermessi.notifiche,
                etichettaAzione = stringResource(R.string.passo_apri_impostazioni),
                onAzione = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        lanciaPermessoNotifiche.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        context.startActivity(PermessiHelper.intentImpostazioniNotifiche(context))
                    }
                },
            )

            OutlinedButton(onClick = onAggiorna, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.onboarding_ricontrolla))
            }
            Text(
                text = stringResource(R.string.onboarding_nota_avvio),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PassoPermesso(
    titolo: String,
    descrizione: String,
    fatto: Boolean,
    etichettaAzione: String,
    onAzione: () -> Unit,
    contenutoExtra: (@Composable () -> Unit)? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (fatto) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = stringResource(
                        if (fatto) R.string.passo_fatto else R.string.passo_da_fare,
                    ),
                    tint = if (fatto) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                )
                Text(titolo, style = MaterialTheme.typography.titleMedium)
            }
            Text(descrizione, style = MaterialTheme.typography.bodyMedium)
            if (!fatto) {
                Button(onClick = onAzione) { Text(etichettaAzione) }
                contenutoExtra?.invoke()
            }
        }
    }
}

/** "Se Android ti blocca…": la guida passo-passo alle impostazioni con limitazioni. */
@Composable
private fun AiutoRestrizioni() {
    val context = LocalContext.current
    var aperto by rememberSaveable { mutableStateOf(false) }

    TextButton(onClick = { aperto = !aperto }) {
        Text(stringResource(R.string.aiuto_restrizioni_titolo))
    }
    if (aperto) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.aiuto_restrizioni_testo),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { context.startActivity(PermessiHelper.intentInfoApp(context)) }) {
                Text(stringResource(R.string.aiuto_apri_info_app))
            }
        }
    }
}
