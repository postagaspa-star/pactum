package eu.stgm.pactum.genitore.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.TipiDispositivo

// I mattoni comuni alle schermate del genitore: stessi titoli, stesse righe,
// stessi stati vuoti ovunque. Tre livelli di peso (tavola rotonda §3.3): la
// scheda eroe sta sopra per colore, le card di contenuto per un filo d'ombra,
// le righe di lista non sono card affatto.

/** Titolo di un blocco della schermata: `titleMedium` SemiBold. */
@Composable
fun TitoloSezione(testo: String, modifier: Modifier = Modifier) {
    Text(
        text = testo,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.padding(top = Spazi.s),
    )
}

/** Sopra-titolo MAIUSCOLO (la maiuscola sta nella stringa): "DENTRO IL PATTO". */
@Composable
fun SopraTitolo(
    testo: String,
    modifier: Modifier = Modifier,
    colore: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text = testo,
        style = MaterialTheme.typography.labelMedium,
        color = colore,
        modifier = modifier,
    )
}

/**
 * Un chip di sola lettura. Di norma `secondaryContainer`; chi ha un significato
 * diverso (la direzione di una proposta) passa il suo vestito. Mai i colori del
 * patto: quelli vivono solo nella striscia.
 */
@Composable
fun Etichetta(
    testo: String,
    contenitore: Color = MaterialTheme.colorScheme.secondaryContainer,
    inchiostro: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    bordo: BorderStroke? = null,
) {
    Surface(shape = RoundedCornerShape(50), color = contenitore, border = bordo) {
        Text(
            text = testo,
            style = MaterialTheme.typography.labelSmall,
            color = inchiostro,
            // 3.dp: l'altezza del chip, più bassa di ogni passo di Spazi.
            modifier = Modifier.padding(horizontal = Spazi.s, vertical = 3.dp),
        )
    }
}

/**
 * Lo stato vuoto di una sezione: icona + frase, a sinistra, dentro il flusso.
 * Quando va bene si scrive (tavola rotonda §3.4), ma senza un banner verde:
 * una riga asciutta. `buonaNotizia` cambia solo l'icona.
 */
@Composable
fun RigaVuota(testo: String, modifier: Modifier = Modifier, buonaNotizia: Boolean = false) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (buonaNotizia) Icons.Outlined.CheckCircle else Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = testo,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spazi.s),
        )
    }
}

/**
 * Lo stato vuoto di PRIMA APERTURA (niente collegamento, niente regole, nessun
 * dato mai arrivato): lì il vuoto è la schermata, quindi titolo grande e
 * spiegazione. `centrato` per quando occupa tutto lo schermo.
 */
@Composable
fun StatoPrimaApertura(
    titolo: String,
    testo: String,
    modifier: Modifier = Modifier,
    centrato: Boolean = false,
) {
    val allineamento = if (centrato) TextAlign.Center else TextAlign.Start
    Column(
        modifier = modifier,
        horizontalAlignment = if (centrato) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Text(
            text = titolo,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = allineamento,
        )
        Text(
            text = testo,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = allineamento,
            modifier = Modifier.padding(top = Spazi.s),
        )
    }
}

/**
 * Dati vecchi: è un'ETÀ, non un fallimento. Una riga su `surfaceVariant`, mai
 * `errorContainer` — il rosso di sistema resta alla validazione dei form, così
 * il genitore non confonde "mio figlio ha sforato" con "il mio telefono non ha
 * campo".
 */
@Composable
fun RigaDatiVecchi(testo: String) {
    Text(
        text = testo,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.shapes.small,
            )
            .padding(horizontal = Spazi.m, vertical = Spazi.s),
    )
}

/**
 * Card di contenuto (regole, proposte, dichiarazioni): un filo d'ombra su un
 * fondo più chiaro della scheda eroe, così l'eroe resta sopra per colore.
 */
@Composable
fun CardContenuto(modifier: Modifier = Modifier, contenuto: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        content = contenuto,
    )
}

/**
 * Righe di lista con il divisore in mezzo, su `surface`: eventi, storico, tempi
 * per app, notifiche. Non sono card — sono un elenco, e si leggono come tale.
 */
@Composable
fun <T> ListaRighe(
    voci: List<T>,
    modifier: Modifier = Modifier,
    riga: @Composable (T) -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        voci.forEachIndexed { indice, voce ->
            if (indice > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            riga(voce)
        }
    }
}

/** L'orario di un fatto del registro, nel fuso del telefono: `labelSmall`, sottovoce. */
@Composable
fun TestoOrario(tsServer: String?, modifier: Modifier = Modifier) {
    val istante = istanteServer(tsServer) ?: return
    Text(
        text = dataOraLocale(istante),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** Il numero dentro un badge: oltre 99 non serve contare. */
fun testoBadge(quante: Int): String = if (quante > 99) "99+" else quante.toString()

// --- Famiglia (v3) ---------------------------------------------------------------

/** L'icona del tipo di dispositivo: telefono o computer. Mai un colore d'allarme. */
@Composable
fun IconaDispositivo(
    tipo: String,
    modifier: Modifier = Modifier,
    tinta: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    descrizione: String? = null,
) {
    Icon(
        painter = painterResource(
            if (tipo == TipiDispositivo.COMPUTER) {
                R.drawable.ic_dispositivo_computer
            } else {
                R.drawable.ic_dispositivo_telefono
            },
        ),
        contentDescription = descrizione,
        tint = tinta,
        modifier = modifier.size(20.dp),
    )
}

/**
 * In cima a Panoramica, Tempo e "Proposte e conferme" (v3). Con più figli, un
 * chip per figlio (la scelta resta ricordata) col numero delle sue notifiche non
 * lette; con un figlio solo, il suo nome e basta. Sul server 0.7 (o finché la
 * famiglia non si conosce) non c'è niente: l'app è quella di prima.
 */
@Composable
fun IntestazioneFiglio(famiglia: FamigliaViewModel.StatoFamiglia, onScegli: (Long) -> Unit) {
    if (famiglia.serverVecchio || famiglia.figli.isEmpty()) return
    val scelto = famiglia.figlioScelto
    if (!famiglia.piuFigli) {
        val nome = scelto?.nome?.trim().orEmpty()
        if (nome.isEmpty()) return
        Text(
            text = nome,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spazi.l, end = Spazi.l, top = Spazi.s),
        )
        return
    }
    val descrizione = stringResource(R.string.figlio_scelta_descrizione)
    val senzaNome = stringResource(R.string.figlio_senza_nome)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spazi.l)
            .semantics { contentDescription = descrizione },
        horizontalArrangement = Arrangement.spacedBy(Spazi.s),
    ) {
        famiglia.figli.forEach { figlio ->
            val nonLette = figlio.notificheNonLette
            val etichettaNonLette = if (nonLette > 0) {
                pluralStringResource(R.plurals.figlio_notifiche_non_lette, nonLette, nonLette)
            } else {
                null
            }
            FilterChip(
                selected = figlio.id == scelto?.id,
                onClick = { onScegli(figlio.id) },
                label = { Text(figlio.nome.ifBlank { senzaNome }) },
                trailingIcon = if (etichettaNonLette != null) {
                    {
                        // Il blu dell'app, come il badge della campanella: mai il
                        // rosso `error` (il rosso vive solo nella striscia).
                        Badge(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.semantics { contentDescription = etichettaNonLette },
                        ) {
                            Text(testoBadge(nonLette))
                        }
                    }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
fun Centro(contenuto: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        contenuto()
    }
}

@Composable
fun TestoCentrato(testo: String) {
    Text(
        text = testo,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = Spazi.xxl),
    )
}

/** La rotella della prima lettura, con due parole sotto. */
@Composable
fun Caricamento(testo: String) {
    Centro {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = testo,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = Spazi.s),
            )
        }
    }
}
