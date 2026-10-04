package eu.stgm.pactum.figlio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.permessi.PermessiHelper

/**
 * (0.9) Pactum può mostrarsi sopra le altre app? Si riguarda a ogni ritorno in
 * primo piano: il permesso si dà nelle impostazioni di sistema.
 */
@Composable
fun rememberMostraSopra(): Boolean {
    val context = LocalContext.current
    var concesso by remember { mutableStateOf(PermessiHelper.puoMostrareSopra(context)) }
    LifecycleResumeEffect(Unit) {
        concesso = PermessiHelper.puoMostrareSopra(context)
        onPauseOrDispose { }
    }
    return concesso
}
