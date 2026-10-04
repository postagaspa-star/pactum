package eu.stgm.pactum.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/** L'area di tocco minima di Android (e di Material): niente di toccabile sotto. */
internal val AreaTocco = 48.dp

/** Una riga toccabile con il sottotitolo: due righe di testo hanno bisogno di più aria. */
private val AreaToccoDueRighe = 56.dp

/** La rotella di [Caricamento]: più piccola dei 40 di Material, non deve sembrare un allarme. */
private val LatoRotella = 32.dp

/** L'icona dello [StatoVuoto] a tutto schermo; in linea resta la misura normale delle icone (24). */
private val LatoIconaGrande = 48.dp

// --- Titolo di sezione ---------------------------------------------------------

/**
 * L'UNICO titolo di sezione delle due app: `titleMedium` SemiBold, `onSurface`,
 * una riga. Se c'è [azione] (es. "Vedi tutte"), un pulsante di testo a destra.
 *
 * Quando usarlo: sopra ogni gruppo di card o di righe ("Oggi", "Regole",
 * "Da decidere"). Mai un `Text` con uno stile fatto a mano al suo posto.
 * TalkBack lo legge come intestazione (si salta da un titolo all'altro).
 */
@Composable
fun TitoloSezione(
    testo: String,
    modifier: Modifier = Modifier,
    azione: String? = null,
    onAzione: (() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = testo,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        if (azione != null) {
            TextButton(onClick = onAzione ?: {}, enabled = onAzione != null) {
                Text(azione, maxLines = 1, softWrap = false)
            }
        }
    }
}

// --- Riga di stato -------------------------------------------------------------

/**
 * La riga che dice com'è messa una cosa: "dati non aggiornati", "il telefono
 * di Luca è scollegato", "da sistemare". Fondo chiaro del [tono], angoli piccoli,
 * alta almeno 48, testo `bodyMedium` che va a capo se serve.
 *
 * - [azione]: un pulsante di testo a destra ("Riprova"). Non va mai a capo: se
 *   a destra non c'è posto, sta sotto il testo.
 * - [onClick]: tutta la riga si tocca, con la freccia › in fondo (per aprire il dettaglio).
 *
 * Quando usarla: per uno stato, non per un'informazione qualunque. Una per
 * problema; se i problemi sono tanti, una riga che porta all'elenco.
 */
@Composable
fun RigaStato(
    testo: String,
    modifier: Modifier = Modifier,
    tono: Tono = Tono.Neutro,
    azione: String? = null,
    onAzione: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val schema = MaterialTheme.colorScheme
    val colori = coloriTono(tono, schema)
    val forma = MaterialTheme.shapes.small
    val contenuto: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = AreaTocco)
                .padding(
                    start = Spazi.m,
                    // Il pulsante di testo e la freccia hanno già il loro margine dentro.
                    end = if (azione != null || onClick != null) Spazi.xs else Spazi.m,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TestoConAzione(
                testo = testo,
                coloreTesto = colori.testo,
                azione = azione,
                onAzione = onAzione,
                coloreAzione = coloreAzione(tono, schema),
                modifier = Modifier.weight(1f),
            )
            if (onClick != null) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colori.testo,
                    modifier = Modifier.padding(start = Spazi.xs),
                )
            }
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = forma, color = colori.fondo, contentColor = colori.testo) {
            contenuto()
        }
    } else {
        Surface(modifier = modifier, shape = forma, color = colori.fondo, contentColor = colori.testo) {
            contenuto()
        }
    }
}

/** Testo + pulsante della [RigaStato]: il pulsante a destra se c'è posto, altrimenti sotto (v. [azioneSotto]). */
@Composable
private fun TestoConAzione(
    testo: String,
    coloreTesto: androidx.compose.ui.graphics.Color,
    azione: String?,
    onAzione: (() -> Unit)?,
    coloreAzione: androidx.compose.ui.graphics.Color,
    modifier: Modifier,
) {
    Layout(
        content = {
            Text(
                text = testo,
                style = MaterialTheme.typography.bodyMedium,
                color = coloreTesto,
                modifier = Modifier.padding(vertical = Spazi.s),
            )
            if (azione != null) {
                TextButton(
                    onClick = onAzione ?: {},
                    enabled = onAzione != null,
                    colors = ButtonDefaults.textButtonColors(contentColor = coloreAzione),
                ) {
                    Text(azione, maxLines = 1, softWrap = false)
                }
            }
        },
        modifier = modifier,
    ) { misurabili, vincoli ->
        val testoM = misurabili[0]
        val pulsanteM = misurabili.getOrNull(1)
        val gap = Spazi.s.roundToPx()
        val libere = vincoli.copy(minWidth = 0, minHeight = 0)
        if (pulsanteM == null) {
            val t = testoM.measure(libere)
            val larghezza = if (vincoli.hasBoundedWidth) vincoli.maxWidth else t.width
            return@Layout layout(larghezza, t.height) { t.placeRelative(0, 0) }
        }
        val larghezzaPulsante = pulsanteM.maxIntrinsicWidth(Constraints.Infinity)
        val larghezza = if (vincoli.hasBoundedWidth) {
            vincoli.maxWidth
        } else {
            testoM.maxIntrinsicWidth(Constraints.Infinity) + gap + larghezzaPulsante
        }
        val p = pulsanteM.measure(Constraints(minWidth = 0, maxWidth = larghezzaPulsante.coerceAtMost(larghezza)))
        if (azioneSotto(larghezzaPulsante, gap, larghezza)) {
            // Il testo si ferma prima del bordo destro come a sinistra (la riga ha
            // a destra solo 4 di margine, pensato per il pulsante di testo).
            val t = testoM.measure(Constraints(maxWidth = (larghezza - gap).coerceAtLeast(0)))
            layout(larghezza, t.height + p.height) {
                t.placeRelative(0, 0)
                p.placeRelative(larghezza - p.width, t.height)
            }
        } else {
            val t = testoM.measure(Constraints(maxWidth = (larghezza - p.width - gap).coerceAtLeast(0)))
            val altezza = maxOf(t.height, p.height)
            layout(larghezza, altezza) {
                t.placeRelative(0, (altezza - t.height) / 2)
                p.placeRelative(larghezza - p.width, (altezza - p.height) / 2)
            }
        }
    }
}

// --- Stato vuoto e caricamento -------------------------------------------------

/**
 * Il solo stato vuoto: icona + (titolo) + frase + eventuale pulsante.
 *
 * - `centrato = false` (normale): dentro una sezione, icona a sinistra e testo a
 *   fianco, pulsante con il bordo sotto la frase. Es. "Nessuna proposta da decidere".
 * - `centrato = true`: lo stato a schermo intero (la schermata non ha nient'altro),
 *   in mezzo alla pagina con margini 32, pulsante pieno.
 *
 * Una frase sola, che dica cosa succede o cosa fare: niente spiegazioni lunghe.
 */
@Composable
fun StatoVuoto(
    testo: String,
    modifier: Modifier = Modifier,
    titolo: String? = null,
    icona: ImageVector? = Icons.Outlined.Info,
    azione: String? = null,
    onAzione: (() -> Unit)? = null,
    centrato: Boolean = false,
) {
    val schema = MaterialTheme.colorScheme
    if (centrato) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(Spazi.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (icona != null) {
                Icon(icona, contentDescription = null, tint = schema.onSurfaceVariant, modifier = Modifier.size(LatoIconaGrande))
                Spacer(Modifier.height(Spazi.l))
            }
            if (titolo != null) {
                Text(
                    text = titolo,
                    style = MaterialTheme.typography.titleMedium,
                    color = schema.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(Spazi.s))
            }
            Text(
                text = testo,
                style = MaterialTheme.typography.bodyMedium,
                color = schema.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (azione != null) {
                Spacer(Modifier.height(Spazi.xl))
                Button(onClick = onAzione ?: {}, enabled = onAzione != null) {
                    Text(azione, maxLines = 1, softWrap = false)
                }
            }
        }
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = Spazi.m),
            verticalAlignment = Alignment.Top,
        ) {
            if (icona != null) {
                Icon(icona, contentDescription = null, tint = schema.onSurfaceVariant)
                Spacer(Modifier.width(Spazi.m))
            }
            Column(Modifier.weight(1f)) {
                if (titolo != null) {
                    Text(
                        text = titolo,
                        style = MaterialTheme.typography.titleSmall,
                        color = schema.onSurface,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(Spazi.xs))
                }
                Text(text = testo, style = MaterialTheme.typography.bodyMedium, color = schema.onSurfaceVariant)
                if (azione != null) {
                    Spacer(Modifier.height(Spazi.s))
                    OutlinedButton(onClick = onAzione ?: {}, enabled = onAzione != null) {
                        Text(azione, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

/**
 * Il solo caricamento: la rotella `primary` e, se c'è, una frase sotto
 * ("Carico le regole…"). `centrato = true` (normale) = in mezzo alla pagina;
 * `false` = dentro una sezione, largo quanto la sezione.
 * TalkBack legge la frase insieme alla rotella.
 */
@Composable
fun Caricamento(
    modifier: Modifier = Modifier,
    testo: String? = null,
    centrato: Boolean = true,
) {
    val base = if (centrato) modifier.fillMaxSize() else modifier.fillMaxWidth().padding(vertical = Spazi.l)
    Column(
        modifier = base.semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (centrato) Arrangement.Center else Arrangement.Top,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(LatoRotella), color = MaterialTheme.colorScheme.primary)
        if (testo != null) {
            Spacer(Modifier.height(Spazi.m))
            Text(
                text = testo,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// --- Pillola -------------------------------------------------------------------

/**
 * La sola pillola di stato (sola lettura, non si tocca): "Attiva", "In pausa",
 * "Scollegato". Una riga, `labelMedium`, angoli tondi, fondo chiaro del [tono] e
 * bordo sottile nel colore pieno del tono: si vede su ogni fondo chiaro (pagina,
 * card normale, card in evidenza dello stesso tono).
 *
 * Quando usarla: per uno stato corto (1-2 parole). Una frase va in una [RigaStato].
 * Non è un pulsante: per un'azione usa un pulsante vero.
 */
@Composable
fun Pillola(
    testo: String,
    modifier: Modifier = Modifier,
    tono: Tono = Tono.Neutro,
) {
    val colori = coloriTono(tono, MaterialTheme.colorScheme)
    Text(
        text = testo,
        style = MaterialTheme.typography.labelMedium,
        color = colori.testo,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(colori.fondo, CircleShape)
            .border(BordoSottile, colori.pieno, CircleShape)
            .padding(horizontal = Spazi.m, vertical = Spazi.xs),
    )
}

// --- Menu "⋯" ------------------------------------------------------------------

/**
 * Una voce del [MenuAzioni]. [distruttiva] = in rosso (`error`): "Elimina",
 * "Scollega". [abilitata] = false la mostra spenta (per dire che c'è ma ora no).
 */
data class VoceMenu(
    val testo: String,
    val onClick: () -> Unit,
    val distruttiva: Boolean = false,
    val abilitata: Boolean = true,
)

/**
 * Il pulsante "⋯" (tre puntini, area 48) che apre un menu a tendina con le [voci].
 * [descrizione] è quello che legge TalkBack sul pulsante ("Altre azioni per Luca").
 * Se [voci] è vuota non mostra niente.
 *
 * Quando usarlo: su una card o una riga che ha più di un'azione. Il pulsante
 * visibile è UNO (quello che si usa di più); tutte le altre azioni vengono qui.
 */
@Composable
fun MenuAzioni(
    voci: List<VoceMenu>,
    descrizione: String,
    modifier: Modifier = Modifier,
) = MenuAzioni(voci, descrizione, modifier, apertoAllInizio = false)

/** Come [MenuAzioni], ma si può aprire già aperto: serve al catalogo fotografico. */
@Composable
internal fun MenuAzioni(
    voci: List<VoceMenu>,
    descrizione: String,
    modifier: Modifier,
    apertoAllInizio: Boolean,
) {
    if (voci.isEmpty()) return
    var aperto by remember { mutableStateOf(apertoAllInizio) }
    Box(modifier) {
        IconButton(onClick = { aperto = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = descrizione)
        }
        DropdownMenu(expanded = aperto, onDismissRequest = { aperto = false }) {
            val errore = MaterialTheme.colorScheme.error
            voci.forEach { voce ->
                DropdownMenuItem(
                    text = { Text(voce.testo) },
                    onClick = {
                        aperto = false
                        voce.onClick()
                    },
                    enabled = voce.abilitata,
                    colors = if (voce.distruttiva) MenuDefaults.itemColors(textColor = errore) else MenuDefaults.itemColors(),
                )
            }
        }
    }
}

// --- Riga toccabile --------------------------------------------------------------

/**
 * Una riga intera che si tocca e porta da un'altra parte: titolo `bodyLarge`,
 * (sottotitolo `bodyMedium` grigio), e in fondo la freccia ›.
 * - [inizio]: un'icona o un avatar a sinistra;
 * - [fine]: un numero o una [Pillola] prima della freccia.
 * Alta almeno 48 (56 col sottotitolo). Non ha margini ai lati: si allinea al
 * contenuto della card o della pagina in cui sta.
 *
 * Quando usarla: elenchi di cose da aprire (i figli, le app, le impostazioni).
 */
@Composable
fun RigaToccabile(
    titolo: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sottotitolo: String? = null,
    inizio: (@Composable () -> Unit)? = null,
    fine: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (sottotitolo != null) AreaToccoDueRighe else AreaTocco)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = Spazi.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spazi.m),
    ) {
        inizio?.invoke()
        Column(Modifier.weight(1f)) {
            Text(titolo, style = MaterialTheme.typography.bodyLarge)
            if (sottotitolo != null) {
                Text(
                    sottotitolo,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        fine?.invoke()
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --- Sezione espandibile ---------------------------------------------------------

/**
 * Una sezione che si apre e si chiude: intestazione toccabile (alta almeno 48)
 * con il titolo — lo stesso stile di [TitoloSezione] — più "(N)" se c'è
 * [conteggio], e la freccia che gira. Aperta/chiusa resta com'era anche
 * ruotando il telefono o cambiando scheda (`rememberSaveable`, legato a [chiave]).
 *
 * Quando usarla: per le cose che servono ogni tanto (lo storico, le regole
 * vecchie, i dettagli): così la schermata resta corta.
 * TalkBack la annuncia come pulsante che si espande o si comprime.
 */
@Composable
fun SezioneEspandibile(
    titolo: String,
    modifier: Modifier = Modifier,
    conteggio: Int? = null,
    apertaAllInizio: Boolean = false,
    chiave: String = titolo,
    content: @Composable ColumnScope.() -> Unit,
) {
    var aperta by rememberSaveable(chiave) { mutableStateOf(apertaAllInizio) }
    val rotazione by animateFloatAsState(if (aperta) 180f else 0f, label = "freccia sezione")
    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = AreaTocco)
                .clickable(role = Role.Button) { aperta = !aperta }
                .semantics {
                    if (aperta) {
                        collapse { aperta = false; true }
                    } else {
                        expand { aperta = true; true }
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (conteggio != null) "$titolo ($conteggio)" else titolo,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(rotazione),
            )
        }
        AnimatedVisibility(visible = aperta) {
            Column(Modifier.padding(bottom = Spazi.s), content = content)
        }
    }
}
