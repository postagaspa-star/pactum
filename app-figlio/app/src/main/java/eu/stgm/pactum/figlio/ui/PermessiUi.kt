package eu.stgm.pactum.figlio.ui

import eu.stgm.pactum.design.Pillola
import eu.stgm.pactum.design.Tono
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.permessi.PermessiHelper
import eu.stgm.pactum.figlio.permessi.StatoPermessi

/** Il nome di un permesso, come lo chiama Android. */
fun nomePermesso(permesso: Permesso): Int = when (permesso) {
    Permesso.USO -> R.string.permesso_uso
    Permesso.BATTERIA -> R.string.permesso_batteria
    Permesso.NOTIFICHE -> R.string.permesso_notifiche
    Permesso.MOSTRA_SOPRA -> R.string.permesso_mostra_sopra
}

private fun aCosaServe(permesso: Permesso): Int = when (permesso) {
    Permesso.USO -> R.string.passo_uso_descrizione
    Permesso.BATTERIA -> R.string.passo_batteria_descrizione
    Permesso.NOTIFICHE -> R.string.passo_notifiche_descrizione
    Permesso.MOSTRA_SOPRA -> R.string.passo_mostra_sopra_descrizione
}

/**
 * (0.15) I quattro permessi di Pactum, ognuno col suo stato e "Apri" se manca;
 * l'aiuto "Se Android ti blocca…" una volta sola, sotto. Lo stesso elenco nel
 * primo avvio e nelle Impostazioni: lì si vedono e si sistemano tutti e
 * quattro. [onAggiorna] = rileggere lo stato (dopo la richiesta delle notifiche).
 */
@Composable
fun ElencoPermessi(stato: StatoPermessi, onAggiorna: () -> Unit) {
    val context = LocalContext.current
    val lanciaPermessoNotifiche = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { onAggiorna() }

    Column {
        Permesso.entries.forEachIndexed { indice, permesso ->
            if (indice > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val concesso = Permessi.concesso(stato, permesso)
            RigaPermesso(
                nome = stringResource(nomePermesso(permesso)),
                aCosaServe = stringResource(aCosaServe(permesso)),
                concesso = concesso,
                onApri = {
                    when (permesso) {
                        Permesso.USO -> PermessiHelper.apri(context, PermessiHelper.intentAccessoUso())
                        Permesso.BATTERIA -> PermessiHelper.apri(context, PermessiHelper.intentEsenzioneBatteria(context))
                        Permesso.NOTIFICHE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            lanciaPermessoNotifiche.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            PermessiHelper.apri(context, PermessiHelper.intentImpostazioniNotifiche(context))
                        }
                        Permesso.MOSTRA_SOPRA -> PermessiHelper.apri(context, PermessiHelper.intentMostraSopra(context))
                    }
                },
            )
        }
        // Le impostazioni con limitazioni di Android 15/16 bloccano questi due.
        if (!stato.accessoUso || !stato.mostraSopra) {
            AiutoRestrizioni(stringResource(R.string.aiuto_restrizioni_testo))
        }
    }
}

@Composable
private fun RigaPermesso(nome: String, aCosaServe: String, concesso: Boolean, onApri: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = Spazi.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spazi.m),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spazi.xs)) {
            Text(nome, style = MaterialTheme.typography.bodyLarge)
            Text(aCosaServe, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (concesso) {
            Pillola(stringResource(R.string.permesso_concesso), tono = Tono.Positivo)
        } else {
            OutlinedButton(onClick = onApri) { Text(stringResource(R.string.azione_apri)) }
        }
    }
}

/**
 * "Se Android ti blocca…": la guida passo-passo alle impostazioni con
 * limitazioni di Android 15/16 per le app installate a mano.
 */
@Composable
internal fun AiutoRestrizioni(testo: String) {
    val context = LocalContext.current
    var aperto by rememberSaveable { mutableStateOf(false) }

    TextButton(onClick = { aperto = !aperto }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = Spazi.s)) {
        Text(stringResource(R.string.aiuto_restrizioni_titolo))
    }
    if (aperto) {
        Column(verticalArrangement = Arrangement.spacedBy(Spazi.s)) {
            Text(
                text = testo,
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { PermessiHelper.apri(context, PermessiHelper.intentInfoApp(context)) }) {
                Text(stringResource(R.string.aiuto_apri_info_app))
            }
        }
    }
}
