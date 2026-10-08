package eu.stgm.pactum.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Lo spessore dei bordi sottili (card normale, pillola): un pixel "vero" a xhdpi e oltre. */
internal val BordoSottile = 1.dp

/**
 * Il padding della [CardEvidenza]: 20, uno scalino sopra i 16 della card
 * normale, perché la card importante "respiri" di più. Non è in [Spazi]
 * apposta: vale solo qui (e nel [FoglioDalBasso], che è la stessa misura).
 */
internal val PaddingEvidenza = 20.dp

/**
 * La card di tutti i giorni: fondo bianco (`surfaceContainerLowest`), bordo
 * sottile `outlineVariant`, angoli `shapes.medium` (14), padding 16, nessuna ombra.
 *
 * Quando usarla: ogni gruppo di informazioni normale (una regola, un figlio,
 * un'app, una sessione passata). Per la UNA cosa importante della schermata
 * usa [CardEvidenza]. Su una card al massimo UN pulsante visibile: le altre
 * azioni nel [MenuAzioni] "⋯".
 *
 * Se c'è [onClick] tutta la card si tocca (e TalkBack la legge come un pulsante).
 * Il contenuto è una colonna senza spaziatura (la decide chi la usa). La card
 * va a tutta larghezza da sola: per stringerla, `Modifier.width(...)`.
 */
@Composable
fun CardNormale(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val schema = MaterialTheme.colorScheme
    val forma = MaterialTheme.shapes.medium
    val bordo = BorderStroke(BordoSottile, schema.outlineVariant)
    // (0.19) Entra a cascata con la schermata; se si tocca, si schiaccia e rimbalza.
    if (onClick != null) {
        val sorgente = remember { MutableInteractionSource() }
        Surface(
            onClick = onClick,
            modifier = modifier.entrata().rimbalzoTocco(sorgente),
            interactionSource = sorgente,
            shape = forma,
            color = schema.surfaceContainerLowest,
            contentColor = schema.onSurface,
            border = bordo,
        ) {
            Column(Modifier.fillMaxWidth().padding(Spazi.l), content = content)
        }
    } else {
        Surface(
            modifier = modifier.entrata(),
            shape = forma,
            color = schema.surfaceContainerLowest,
            contentColor = schema.onSurface,
            border = bordo,
        ) {
            Column(Modifier.fillMaxWidth().padding(Spazi.l), content = content)
        }
    }
}

/**
 * La card importante: fondo pieno chiaro del tono, testo `on…Container`, angoli
 * `shapes.large` (20), padding 20, niente bordo né ombra. Si stacca dalla pagina
 * e dalla [CardNormale] (bianca col bordo) per colore.
 *
 * Quando usarla: UNA per schermata, per la cosa che conta di più — la serie del
 * figlio, il riepilogo del patto del genitore, il blocco dei lavori di casa, la
 * sessione in corso.
 * Toni: [Tono.Positivo] = `primaryContainer` (il normale), [Tono.Attenzione] =
 * `tertiaryContainer`, [Tono.Neutro] = `secondaryContainer`, [Tono.Negativo] = `errorContainer`.
 *
 * Dentro, i testi ereditano l'inchiostro giusto: non dargli colori fissi
 * (niente `onSurfaceVariant` qui dentro). Le [Pillola] si vedono lo stesso: hanno il bordo.
 */
@Composable
fun CardEvidenza(
    modifier: Modifier = Modifier,
    tono: Tono = Tono.Positivo,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val (fondo, testo) = coloriEvidenza(tono, MaterialTheme.colorScheme)
    val forma = MaterialTheme.shapes.large
    if (onClick != null) {
        val sorgente = remember { MutableInteractionSource() }
        Surface(
            onClick = onClick,
            modifier = modifier.entrata().rimbalzoTocco(sorgente),
            interactionSource = sorgente,
            shape = forma,
            color = fondo,
            contentColor = testo,
        ) {
            Column(Modifier.fillMaxWidth().padding(PaddingEvidenza), content = content)
        }
    } else {
        Surface(
            modifier = modifier.entrata(),
            shape = forma,
            color = fondo,
            contentColor = testo,
        ) {
            Column(Modifier.fillMaxWidth().padding(PaddingEvidenza), content = content)
        }
    }
}
